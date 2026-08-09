package net.server.webadmin;

import client.Character;
import client.Skill;
import client.SkillFactory;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.TimerManager;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Keeps a chosen set of buffs up, re-casting each one as soon as it is gone.
 *
 * <h3>Casting</h3>
 * A buff is one method: {@link StatEffect#applyTo(Character)}. That is not a shortcut - it is the
 * whole of what the client's own cast does. {@code SpecialMoveHandler} checks the cooldown,
 * registers it, and calls the same method; everything else about a buff (paying MP and HP,
 * consuming an item, cancelling the previous instance, sending the buff to the client and the
 * foreign look to the map, scheduling the expiry) lives inside it.
 *
 * <h3>Why the effect packets are sent by hand</h3>
 * The cast flash is the one thing {@code applyTo} does not produce for the caster. It broadcasts
 * {@code showBuffEffect} with {@code repeatToSource = false}, so everyone else in the map sees the
 * flash and the caster does not - because normally the caster's own client plays that animation
 * locally, having been the one to start the cast. A cast the client did not initiate has nothing to
 * play. So this sends {@code showOwnBuffEffect} to the caster first, exactly as the map-wide buff a
 * dying boss hands out already does.
 *
 * <h3>Noticing a buff is gone, or nearly gone</h3>
 * {@link Character#hasActiveBuff(int)} answers for both ways a buff can end. Running out and being
 * dispelled by a monster both finish in {@code cancelEffect}, which drops the entry from the same
 * table {@code hasActiveBuff} reads - so one check covers both, and there is nothing to subscribe
 * to.
 * <p>
 * Waiting for that leaves a gap, though: the buff is off for however long the tick takes to come
 * round. So a buff is also renewed when it is within the lead time of running out, read from
 * {@link Character#getBuffExpiry(int)} - the same deadline the game's own expiry sweeper uses.
 *
 * <h3>Cooldowns</h3>
 * Several buffs last less time than their own cooldown - Infinity is 40 seconds on a 600 second
 * cooldown, Holy Shield 40 on 120, Enrage 240 on 360. "Re-cast the moment it drops" is impossible
 * for those, so a skill on cooldown is skipped and picked up on a later tick. The cooldown is
 * registered on the character and sent to the client the same way the packet handler does it;
 * without that the client would believe the skill was ready while the server did not.
 */
public final class AutoBuff {
    private static final Logger log = LoggerFactory.getLogger(AutoBuff.class);

    private static final int MIN_INTERVAL = 500;
    private static final int MAX_INTERVAL = 10_000;
    /** Only one cast per tick, so a fresh set of buffs goes up as a sequence rather than a burst. */
    private static final int CASTS_PER_TICK = 1;

    private static final int MAX_LEAD = 120;

    /** {@code lead} is how many seconds before a buff runs out to renew it; 0 waits for the gap. */
    public record Options(Set<Integer> skills, int interval, int lead) {
        public Options {
            skills = Set.copyOf(skills);
            interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
            lead = Math.max(0, Math.min(MAX_LEAD, lead));
        }
    }

    private static final class Session {
        final int chrId;
        final Options options;
        volatile ScheduledFuture<?> task;
        volatile long cast;
        volatile long renewed;
        volatile String note = "";

        Session(int chrId, Options options) {
            this.chrId = chrId;
            this.options = options;
        }
    }

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private AutoBuff() {
    }

    public static synchronized void start(int chrId, Options options) {
        stop(chrId);
        Session session = new Session(chrId, options);
        sessions.put(chrId, session);
        session.task = TimerManager.getInstance().register(() -> tick(session),
                options.interval(), options.interval());
        log.info("Web admin: auto buff on for chr {} ({} skills, every {}ms, {}s lead)",
                chrId, options.skills().size(), options.interval(), options.lead());
    }

    public static synchronized void stop(int chrId) {
        Session session = sessions.remove(chrId);
        if (session != null) {
            if (session.task != null) {
                session.task.cancel(false);
            }
            log.info("Web admin: auto buff off for chr {} after {} casts ({} of them renewals)",
                    chrId, session.cast, session.renewed);
        }
    }

    public static synchronized void stopAll() {
        for (Integer chrId : new ArrayList<>(sessions.keySet())) {
            stop(chrId);
        }
    }

    public static List<Object> describe() {
        List<Object> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            Map<String, Object> m = Json.obj();
            m.put("chrId", s.chrId);
            m.put("skills", new ArrayList<>(s.options.skills()));
            m.put("interval", s.options.interval());
            m.put("lead", s.options.lead());
            m.put("cast", s.cast);
            m.put("renewed", s.renewed);
            m.put("note", s.note);
            out.add(m);
        }
        return out;
    }

    /**
     * The buffs this character could be asked to keep up: the ones it has actually learnt, that the
     * skill data calls buffs, that put something on the character, and that last a while.
     * <p>
     * All three parts of that test earn their place, measured against the whole 535-skill tree.
     * The buff flag alone lets through Hero's Will, Dispel, Resurrection and the monster debuffs
     * (Threaten, Slow, Seal, Doom), none of which stay on you. Statups alone let through Final
     * Attack and the pirate summons, which are not cast the way a buff is. And a positive duration
     * is what separates a real buff from the four skills that pass the first two but have no
     * duration at all: the three Big Bangs, which are charged attacks, and Aran's Combo Ability,
     * which is passive. 178 skills satisfy all three.
     */
    public static List<Object> offer(Character chr) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<Skill, Character.SkillEntry> entry : chr.getSkills().entrySet()) {
            Skill skill = entry.getKey();
            int level = entry.getValue().skillevel;
            if (level <= 0) {
                continue;
            }
            StatEffect effect;
            try {
                effect = skill.getEffect(level);
            } catch (RuntimeException e) {
                continue;
            }
            if (effect == null || !effect.isOverTime() || effect.getStatups().isEmpty()
                    || effect.getDuration() <= 0) {
                continue;
            }

            Map<String, Object> m = Json.obj();
            m.put("id", skill.getId());
            m.put("name", SkillFactory.getSkillName(skill.getId()));
            m.put("level", level);
            m.put("duration", effect.getDuration());
            m.put("cooldown", effect.getCooldown());
            m.put("mpCon", effect.getMpCon());
            m.put("hpCon", effect.getHpCon());
            m.put("active", chr.hasActiveBuff(skill.getId()));
            // A snapshot: the list is read on demand rather than polled, so this is right when the
            // page asked and drifts from then on.
            long expiry = chr.getBuffExpiry(skill.getId());
            m.put("left", expiry < 0 ? -1
                    : Math.max(0, (expiry - Server.getInstance().getCurrentTime()) / 1000));
            m.put("warning", warn(skill.getId(), effect));
            out.add(m);
        }
        out.sort(java.util.Comparator.comparingInt(s -> (Integer) ((Map<?, ?>) s).get("id")));
        return out;
    }

    /**
     * What is odd about keeping this particular skill up, or null when nothing is.
     * <p>
     * None of these are refused - they are all legitimate things to want - but each behaves in a way
     * that would be a surprise if it were switched on without saying so.
     */
    private static String warn(int skillId, StatEffect effect) {
        if (SUMMONS.contains(skillId)) {
            return ITEM_COST.contains(skillId)
                    ? "召唤兽：重新召唤是换一只新的，而且每次吃一颗召唤石"
                    : "召唤兽：重新召唤是换一只新的，不是续时间";
        }
        if (ITEM_COST.contains(skillId)) {
            return "每次施放消耗一个道具";
        }
        if (effect.isMonsterRiding()) {
            return "骑宠：要求骑宠道具还戴在身上";
        }
        if (effect.isMorph()) {
            return "变身：外观会被改掉";
        }
        if (effect.getCooldown() > 0 && effect.getCooldown() * 1000 > effect.getDuration()) {
            return "冷却（" + effect.getCooldown() + "秒）比持续时间还长，中间必然会空一段";
        }
        return null;
    }

    /**
     * The summons among the offered skills, by id. Taken from the switch in
     * {@code StatEffect.getSummonMovementType()}, which is private - listing them here rather than
     * widening that method keeps the change to the game's own classes to the one accessor this
     * feature genuinely needed.
     */
    private static final Set<Integer> SUMMONS = Set.of(
            1321007,                            // Beholder
            2121005, 2221005, 2311006, 2321003, // Elquines, Ifrit, Summon Dragon, Bahamut
            3111002, 3111005, 3121006,          // Puppet, Silver Hawk, Phoenix
            3211002, 3211005, 3221005,          // Puppet, Golden Eagle, Frostprey
            11001004, 12001004, 12111004,       // Soul, Flame, Ifrit
            13001004, 13111004, 14001005, 15001004);

    /** Buffs that really take an item out of the bag on every cast (item id and count both set). */
    private static final Set<Integer> ITEM_COST = Set.of(
            2311002,            // Mystic Door - a Magic Door scroll
            2311006, 3111005, 3211005, 4111002, 14111000);  // summoning rock

    private static void tick(Session session) {
        try {
            Character chr = MobVac.findOnlineCharacter(session.chrId);
            if (chr == null) {
                stop(session.chrId);
                return;
            }
            // Same two pauses as the rest of the panel: in the cash shop the character is online but
            // not on a map, and a dead one cannot cast at all - the packet handler checks isAlive()
            // before it calls applyTo, and so does this.
            if (!chr.isLoggedinWorld()) {
                session.note = "在商城 / 拍卖场里，暂停";
                return;
            }
            if (!chr.isAlive()) {
                session.note = "角色已死亡，暂停";
                return;
            }

            long now = Server.getInstance().getCurrentTime();
            int leadMs = session.options.lead() * 1000;
            int casts = 0;
            int cooling = 0, missing = 0, poor = 0;
            for (int skillId : session.options.skills()) {
                Skill skill = SkillFactory.getSkill(skillId);
                int level = skill == null ? 0 : chr.getSkillLevel(skill);
                if (level <= 0) {
                    missing++;
                    continue;
                }
                StatEffect effect = skill.getEffect(level);
                if (effect == null) {
                    missing++;
                    continue;
                }
                boolean renewing = false;
                if (chr.hasActiveBuff(skillId)) {
                    if (!dueSoon(chr, skillId, effect, leadMs, now)) {
                        continue;
                    }
                    renewing = true;
                }
                if (chr.skillIsCooling(skillId)) {
                    cooling++;
                    continue;
                }
                // Checked here as well as inside applyHpMpChange so the panel can say why nothing
                // is happening; applyTo would just quietly return false.
                if (chr.getMp() < effect.getMpCon() || chr.getHp() <= effect.getHpCon()) {
                    poor++;
                    continue;
                }
                if (casts >= CASTS_PER_TICK) {
                    continue;
                }
                casts++;
                if (cast(chr, skillId, level, effect)) {
                    session.cast++;
                    if (renewing) {
                        session.renewed++;
                    }
                }
            }

            session.note = note(cooling, missing, poor);
        } catch (Exception e) {
            // A throw out of a TimerManager task kills the schedule without a word, and the panel
            // would go on showing auto buff as running while nothing was being cast.
            log.error("Web admin: auto buff tick failed for chr {}, switching it off", session.chrId, e);
            stop(session.chrId);
        }
    }

    /**
     * Whether a buff that is still up is close enough to the end to be renewed now.
     * <p>
     * Renewing costs nothing but the MP: {@code applyBuffEffect} cancels the running instance and
     * re-applies the full duration, which is exactly what happens when a player re-casts a buff by
     * hand, so the bar goes back to full rather than being extended by the remainder.
     * <p>
     * The lead is capped at half the buff's own length. A ten second lead on Nimble Feet, which
     * lasts twelve, would otherwise mean re-casting it every couple of seconds forever.
     */
    private static boolean dueSoon(Character chr, int skillId, StatEffect effect, int leadMs, long now) {
        if (leadMs <= 0) {
            return false;
        }
        long expiry = chr.getBuffExpiry(skillId);
        if (expiry < 0) {
            return false;       // up, but with no deadline recorded - nothing to be early for
        }
        return expiry - now <= Math.min(leadMs, effect.getDuration() / 2L);
    }

    /** One cast, in the order the packet handler does it: cooldown, flash, effect. */
    private static boolean cast(Character chr, int skillId, int level, StatEffect effect) {
        if (effect.getCooldown() > 0) {
            chr.sendPacket(PacketCreator.skillCooldown(skillId, effect.getCooldown()));
            // The server's own clock, not the wall clock: the sweeper that clears expired cooldowns
            // compares against Server.getCurrentTime(), which lags real time by its update interval.
            chr.addCooldown(skillId, Server.getInstance().getCurrentTime(),
                    SECONDS.toMillis(effect.getCooldown()));
        }
        // The caster's own flash, which applyTo deliberately does not send - see the class comment.
        // Sent before the effect so the animation and the icon land in the same order as a real cast.
        chr.sendPacket(PacketCreator.showOwnBuffEffect(skillId, 1));
        chr.getMap().broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, level), false);
        return effect.applyTo(chr);
    }

    private static String note(int cooling, int missing, int poor) {
        List<String> parts = new ArrayList<>();
        if (cooling > 0) {
            parts.add(cooling + " 个在冷却");
        }
        if (poor > 0) {
            parts.add(poor + " 个 HP/MP 不够");
        }
        if (missing > 0) {
            parts.add(missing + " 个角色已经没有了");
        }
        return String.join("，", parts);
    }

    /**
     * Keeps only the ids this character could really be offered, so neither a stale page nor a
     * hand-written request can arm something that is not a buff - an attack skill on a one second
     * timer would just drain MP.
     */
    public static Set<Integer> keepCastable(Character chr, Set<Integer> wanted) {
        Set<Integer> offered = new LinkedHashSet<>();
        for (Object o : offer(chr)) {
            offered.add((Integer) ((Map<?, ?>) o).get("id"));
        }
        offered.retainAll(wanted);
        return offered;
    }
}
