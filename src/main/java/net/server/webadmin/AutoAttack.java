package net.server.webadmin;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.StatEffect;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Keeps hitting the monsters around a character for as long as it is switched on.
 * <p>
 * The damage goes through {@link MapleMap#damageMonster}, which is the same call the real attack
 * handler ends at once it has finished validating a client's attack packet. Everything downstream
 * of it therefore happens exactly as it would in a real fight: the damage is booked against the
 * character in the monster's {@code takenDamage} table, so the experience is distributed to them
 * (and their party) by share; the kill drops loot, counts towards quest kill counts, and feeds
 * equipment mastery.
 * <p>
 * What it is <em>not</em> is a swing. A real attack starts at the client, which decides the
 * damage numbers and animation and sends them up; there is no server-side call that makes a
 * character attack. So the monsters play their normal death animation and their HP bars move,
 * but nobody swings a weapon and no damage numbers float up. Faking the attack packet would mean
 * hand-assembling something the client parses strictly, which is not worth a desync.
 * <p>
 * Three modes, all landing in the same place: {@code oneshot} deals the monster's remaining HP,
 * {@code fixed} a number you pick, and {@code skill} the character's own damage with one of its
 * learnt skills - paying that skill's MP and honouring its attack and mob counts. See
 * {@link #swingFor} for where the skill numbers come from and which skills it does not model.
 */
public final class AutoAttack {
    private static final Logger log = LoggerFactory.getLogger(AutoAttack.class);

    /** A floor on the tick rate: a kill is a burst of drop and death packets to everyone nearby. */
    private static final int MIN_INTERVAL = 300;
    private static final int MAX_INTERVAL = 10_000;

    /** {@code skillId} 0 means a plain weapon swing; only read when the mode is {@code skill}. */
    public record Options(String mode, int skillId, int radius, int damage, int interval,
                          boolean bosses, int maxPerTick) {
        public Options {
            mode = switch (mode == null ? "" : mode) {
                case "fixed", "skill" -> mode;
                default -> "oneshot";
            };
            radius = Math.max(0, Math.min(100_000, radius));        // 0 = the whole map
            damage = Math.max(1, Math.min(999_999_999, damage));
            interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
            maxPerTick = Math.max(1, Math.min(200, maxPerTick));
        }
    }

    private static final class Session {
        final int chrId;
        final Options options;
        volatile ScheduledFuture<?> task;
        volatile int lastHit;
        volatile int lastKilled;
        volatile int lastSeen;
        volatile long totalKilled;
        volatile String note = "";

        Session(int chrId, Options options) {
            this.chrId = chrId;
            this.options = options;
        }
    }

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private AutoAttack() {
    }

    public static synchronized void start(int chrId, Options options) {
        stop(chrId);
        Session session = new Session(chrId, options);
        sessions.put(chrId, session);
        session.task = TimerManager.getInstance().register(() -> tick(session),
                options.interval(), options.interval());
        log.info("Web admin: auto attack on for chr {} (mode {}{}, every {}ms, radius {}, "
                        + "up to {} per tick, bosses {})", chrId, options.mode(),
                switch (options.mode()) {
                    case "fixed" -> " " + options.damage();
                    case "skill" -> " " + options.skillId();
                    default -> "";
                },
                options.interval(), options.radius() == 0 ? "whole map" : options.radius(),
                options.maxPerTick(), options.bosses());
    }

    public static synchronized void stop(int chrId) {
        Session session = sessions.remove(chrId);
        if (session != null) {
            if (session.task != null) {
                session.task.cancel(false);
            }
            log.info("Web admin: auto attack off for chr {} after {} kills", chrId, session.totalKilled);
        }
    }

    public static synchronized void stopAll() {
        for (Integer chrId : new ArrayList<>(sessions.keySet())) {
            stop(chrId);
        }
    }

    /** One entry per running session, for the panel to show. */
    public static List<Object> describe() {
        List<Object> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            Map<String, Object> m = Json.obj();
            m.put("chrId", s.chrId);
            m.put("mode", s.options.mode());
            m.put("skillId", s.options.skillId());
            m.put("radius", s.options.radius());
            m.put("damage", s.options.damage());
            m.put("interval", s.options.interval());
            m.put("bosses", s.options.bosses());
            m.put("maxPerTick", s.options.maxPerTick());
            m.put("lastHit", s.lastHit);
            m.put("lastKilled", s.lastKilled);
            m.put("lastSeen", s.lastSeen);
            m.put("totalKilled", s.totalKilled);
            m.put("note", s.note);
            out.add(m);
        }
        return out;
    }

    /** What one tick is worth: {@code damagePerLine} 0 means "the monster's remaining HP". */
    private record Swing(int damagePerLine, int lines, int maxTargets) {
    }

    /**
     * Works out one swing, paying its MP if it is a skill. Returns null and leaves a reason in
     * the session note when the character cannot swing right now.
     *
     * <h3>Where the skill numbers come from, and what they miss</h3>
     * The damage is the character's own ceiling for that skill, composed from the same public
     * pieces the attack handler validates against: {@link Character#calculateMaxBaseDamage} times
     * the skill's damage percentage for a weapon, or the magic formula times the skill's matk for
     * a magician. Attack count and mob count come straight off the skill.
     * <p>
     * It is deliberately <em>not</em> the handler's own routine. That one lives inline inside
     * {@code parseDamage} and could only be shared by refactoring the live combat path - and a
     * slip there would change the damage ceiling every real attack is checked against, on a
     * server I have no way to play. So the handful of skills with bespoke formulas are not
     * modelled here: Lucky Seven, Triple Throw, Dragon Roar, Venomous Star/Stab, Shadow Meso and
     * Heal, plus element amplification and combo orbs. Those come out at the ordinary rate
     * instead of their real one; every other skill is exact.
     */
    private static Swing swingFor(Character chr, Session session) {
        Options o = session.options;
        if (!"skill".equals(o.mode())) {
            return new Swing("fixed".equals(o.mode()) ? o.damage() : 0, 1, Integer.MAX_VALUE);
        }

        Skill skill = SkillFactory.getSkill(o.skillId());
        int level = skill == null ? 0 : chr.getSkillLevel(skill);
        if (level <= 0) {
            session.note = Lang.t("角色没有这个技能（或还没加点），暂停",
                    "character does not have that skill (or has no points in it), paused");
            return null;
        }
        StatEffect effect = skill.getEffect(level);
        if (effect == null) {
            session.note = Lang.t("这个技能没有 " + level + " 级的数据，暂停",
                    "skill has no data for level " + level + ", paused");
            return null;
        }
        if (chr.getMp() < effect.getMpCon()) {
            session.note = Lang.t("MP 不够（需要 " + effect.getMpCon() + "），暂停",
                    "not enough MP (needs " + effect.getMpCon() + "), paused");
            return null;
        }
        // The same call the real attack handler makes: it pays the MP and applies whatever the
        // skill does to its caster.
        effect.applyTo(chr);

        boolean magic = chr.getJob().isA(Job.MAGICIAN);
        long damage;
        if (magic) {
            damage = (long) (Math.ceil((chr.getTotalMagic() * Math.ceil(chr.getTotalMagic() / 1000.0)
                    + chr.getTotalMagic()) / 30.0) + Math.ceil(chr.getTotalInt() / 200.0));
            damage *= effect.getMatk();
        } else {
            damage = (long) chr.calculateMaxBaseDamage(chr.getTotalWatk()) * effect.getDamage() / 100;
        }
        int perLine = (int) Math.max(1, Math.min(999_999_999, damage));
        return new Swing(perLine, Math.max(1, effect.getAttackCount()),
                Math.max(1, effect.getMobCount()));
    }

    private static void tick(Session session) {
        try {
            Character chr = MobVac.findOnlineCharacter(session.chrId);
            if (chr == null) {
                stop(session.chrId);            // logged out - nothing left to attack with
                return;
            }
            // In the cash shop the character is online but not standing on a map, and a dead one
            // is dropped by the experience distribution anyway - so both are a pause, not a stop.
            if (!chr.isLoggedinWorld()) {
                session.note = Lang.t("在商城 / 拍卖场里，暂停",
                        "in the Cash Shop or MTS, paused");
                session.lastHit = session.lastKilled = session.lastSeen = 0;
                return;
            }
            if (!chr.isAlive()) {
                session.note = Lang.t("角色已死亡，暂停（死人拿不到经验）",
                        "character is dead, paused (no EXP while dead)");
                session.lastHit = session.lastKilled = session.lastSeen = 0;
                return;
            }
            MapleMap map = chr.getMap();
            if (map == null) {
                return;
            }
            session.note = "";

            // Worked out once per tick, not once per monster: it costs an MP payment and the
            // numbers do not change between two monsters in the same swing.
            Swing swing = swingFor(chr, session);
            if (swing == null) {
                session.lastHit = session.lastKilled = session.lastSeen = 0;
                return;                         // swingFor already wrote the reason into the note
            }

            long radiusSq = (long) session.options.radius() * session.options.radius();
            int hit = 0, killed = 0, seen = 0;

            for (Monster mob : map.getAllMonsters()) {
                if (!mob.isAlive() || mob.isFake()) {
                    continue;
                }
                if (!session.options.bosses() && mob.isBoss()) {
                    continue;
                }
                if (radiusSq > 0 && mob.getPosition().distanceSq(chr.getPosition()) > radiusSq) {
                    continue;
                }
                seen++;
                if (hit >= Math.min(session.options.maxPerTick(), swing.maxTargets)) {
                    continue;                   // counted, not hit - the panel shows both numbers
                }
                // "one-shot" is the monster's remaining HP, so a tick is a kill. damageMonster
                // books the damage actually dealt either way, so the experience share stays right.
                int perLine = swing.damagePerLine == 0 ? mob.getHp() : swing.damagePerLine;
                for (int line = 0; line < swing.lines && mob.isAlive(); line++) {
                    map.damageMonster(chr, mob, perLine);
                }
                hit++;
                if (!mob.isAlive()) {
                    killed++;
                }
            }

            session.lastHit = hit;
            session.lastKilled = killed;
            session.lastSeen = seen;
            session.totalKilled += killed;
        } catch (Exception e) {
            // A throw out of a TimerManager task kills the schedule silently, and the panel would
            // then show auto attack as running while nothing was being hit at all.
            log.error("Web admin: auto attack tick failed for chr {}, switching it off",
                    session.chrId, e);
            stop(session.chrId);
        }
    }
}
