package net.server.webadmin;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Picks up everything lying on the character's map, however far away it is.
 * <p>
 * The pickup is {@link Character#pickupItem}, the same call both the player's own pickup handler
 * and the pet loot handler end at, so ownership, loot locks, party sharing, meso handling and the
 * inventory-full case are all decided by the server's own rules rather than re-implemented here.
 * <p>
 * The reach limit is not in that method - it is in the handler above it, which rejects anything
 * further than 800 by 600 from the character because a client should not be able to ask for what
 * it cannot see. Nothing needs lifting: a loop on the server side simply is not that handler, so
 * it can hand any drop on the map to the same call.
 * <p>
 * That also settles the pet: a pet's reach is decided by the client, which walks it over and asks
 * to loot what it got to. This does not make the pet reach further - it makes the pet unnecessary,
 * because the drop is collected wherever it fell.
 */
public final class AutoLoot {
    private static final Logger log = LoggerFactory.getLogger(AutoLoot.class);

    private static final int MIN_INTERVAL = 300;
    private static final int MAX_INTERVAL = 10_000;

    /** {@code radius} 0 is the whole map; the screen is roughly 800 by 600 around the character. */
    public record Options(int radius, int interval, int maxPerTick) {
        public Options {
            radius = Math.max(0, Math.min(100_000, radius));
            interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
            maxPerTick = Math.max(1, Math.min(200, maxPerTick));
        }
    }

    private static final class Session {
        final int chrId;
        final Options options;
        volatile ScheduledFuture<?> task;
        volatile int lastPicked;
        volatile int lastSeen;
        volatile long totalPicked;
        volatile String note = "";

        Session(int chrId, Options options) {
            this.chrId = chrId;
            this.options = options;
        }
    }

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private AutoLoot() {
    }

    public static synchronized void start(int chrId, Options options) {
        stop(chrId);
        Session session = new Session(chrId, options);
        sessions.put(chrId, session);
        session.task = TimerManager.getInstance().register(() -> tick(session),
                options.interval(), options.interval());
        log.info("Web admin: auto loot on for chr {} (radius {}, every {}ms, up to {} per tick)",
                chrId, options.radius() == 0 ? "whole map" : options.radius(),
                options.interval(), options.maxPerTick());
    }

    public static synchronized void stop(int chrId) {
        Session session = sessions.remove(chrId);
        if (session != null) {
            if (session.task != null) {
                session.task.cancel(false);
            }
            log.info("Web admin: auto loot off for chr {} after {} pickups", chrId, session.totalPicked);
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
            m.put("radius", s.options.radius());
            m.put("interval", s.options.interval());
            m.put("maxPerTick", s.options.maxPerTick());
            m.put("lastPicked", s.lastPicked);
            m.put("lastSeen", s.lastSeen);
            m.put("totalPicked", s.totalPicked);
            m.put("note", s.note);
            out.add(m);
        }
        return out;
    }

    private static void tick(Session session) {
        try {
            Character chr = MobVac.findOnlineCharacter(session.chrId);
            if (chr == null) {
                stop(session.chrId);
                return;
            }
            if (!chr.isLoggedinWorld() || !chr.isAlive()) {
                session.note = chr.isAlive() ? "不在游戏里，暂停" : "角色已死亡，暂停";
                session.lastPicked = session.lastSeen = 0;
                return;
            }
            MapleMap map = chr.getMap();
            if (map == null) {
                return;
            }
            session.note = "";

            long radiusSq = (long) session.options.radius() * session.options.radius();
            int picked = 0, seen = 0;

            for (MapObject object : map.getMapObjects()) {
                if (!(object instanceof MapItem drop)) {
                    continue;
                }
                if (radiusSq > 0 && drop.getPosition().distanceSq(chr.getPosition()) > radiusSq) {
                    continue;
                }
                seen++;
                if (picked >= session.options.maxPerTick()) {
                    continue;               // counted, not taken - the panel shows both numbers
                }
                // Whether this character may have it - owner, loot lock, party rules, a full
                // inventory - is pickupItem's decision, not ours.
                chr.pickupItem(drop);
                picked++;
            }

            session.lastPicked = picked;
            session.lastSeen = seen;
            session.totalPicked += picked;
        } catch (Exception e) {
            log.error("Web admin: auto loot tick failed for chr {}, switching it off",
                    session.chrId, e);
            stop(session.chrId);
        }
    }
}
