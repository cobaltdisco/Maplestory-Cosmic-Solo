package net.server.webadmin;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
public final class AutoAttack {
    private static final Logger log = LoggerFactory.getLogger(AutoAttack.class);

    /** A floor on the tick rate: a kill is a burst of drop and death packets to everyone nearby. */
    private static final int MIN_INTERVAL = 300;
    private static final int MAX_INTERVAL = 10_000;

    public record Options(int radius, int damage, int interval, boolean bosses, int maxPerTick) {
        public Options {
            radius = Math.max(0, Math.min(100_000, radius));        // 0 = the whole map
            damage = Math.max(0, Math.min(999_999_999, damage));    // 0 = whatever it takes
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
        log.info("Web admin: auto attack on for chr {} ({} damage, every {}ms, radius {}, "
                        + "up to {} per tick, bosses {})", chrId,
                options.damage() == 0 ? "one-shot" : options.damage(), options.interval(),
                options.radius() == 0 ? "whole map" : options.radius(),
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
                session.note = "在商城 / 拍卖场里，暂停";
                session.lastHit = session.lastKilled = session.lastSeen = 0;
                return;
            }
            if (!chr.isAlive()) {
                session.note = "角色已死亡，暂停（死人拿不到经验）";
                session.lastHit = session.lastKilled = session.lastSeen = 0;
                return;
            }
            MapleMap map = chr.getMap();
            if (map == null) {
                return;
            }
            session.note = "";

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
                if (hit >= session.options.maxPerTick()) {
                    continue;                   // counted, not hit - the panel shows both numbers
                }
                // 0 means "whatever it takes": the monster's remaining HP, so one tick is one
                // kill. damageMonster books the real damage dealt, so the experience share is
                // still exactly right.
                int damage = session.options.damage() == 0 ? mob.getHp() : session.options.damage();
                map.damageMonster(chr, mob, damage);
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
