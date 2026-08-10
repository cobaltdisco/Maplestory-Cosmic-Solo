package net.server.webadmin;

import client.Character;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Keeps dragging the monsters on a character's map to a point just in front of them, for as long
 * as it is switched on.
 * <p>
 * The move itself is {@link Monster#resetMobPosition}, which the server already uses whenever it
 * needs to overrule where a mob is: it drops the mob's controller, sets the position, broadcasts
 * the move, then hands control back. Dropping the controller is the part that makes it stick -
 * the controlling client simulates mob movement, so without that it would immediately walk the
 * mob back.
 * <p>
 * That controller handover is also the cost: two extra packets per mob per move. Mobs already
 * sitting on the target are therefore skipped, which means a settled pile costs nothing until
 * something wanders off it.
 */
public final class MobVac {
    private static final Logger log = LoggerFactory.getLogger(MobVac.class);

    /** Below this the controller churn stops being worth it; the mob is already there. */
    private static final int ARRIVED_TOLERANCE = 5;
    /** A floor on the tick rate: every move is 2 packets per mob to the controlling client. */
    private static final int MIN_INTERVAL = 300;
    private static final int MAX_INTERVAL = 10_000;

    public record Options(int distance, int radius, boolean bosses, int interval) {
        public Options {
            distance = Math.max(0, Math.min(1000, distance));
            radius = Math.max(0, Math.min(100_000, radius));    // 0 = the whole map
            interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
        }
    }

    private static final class Session {
        final int chrId;
        final Options options;
        volatile ScheduledFuture<?> task;
        volatile int lastMoved;
        volatile int lastSeen;
        volatile String mapName = "";
        volatile int mapId;

        Session(int chrId, Options options) {
            this.chrId = chrId;
            this.options = options;
        }
    }

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private MobVac() {
    }

    /** Starts the vac for a character, or re-applies it with new options if already running. */
    public static synchronized void start(int chrId, Options options) {
        stop(chrId);
        Session session = new Session(chrId, options);
        sessions.put(chrId, session);
        session.task = TimerManager.getInstance().register(() -> tick(session),
                options.interval(), options.interval());
        log.info("Web admin: mob vac on for chr {} ({}px ahead, every {}ms, radius {}, bosses {})",
                chrId, options.distance(), options.interval(),
                options.radius() == 0 ? "whole map" : options.radius(), options.bosses());
    }

    public static synchronized void stop(int chrId) {
        Session session = sessions.remove(chrId);
        if (session != null) {
            if (session.task != null) {
                session.task.cancel(false);
            }
            log.info("Web admin: mob vac off for chr {}", chrId);
        }
    }

    public static synchronized void stopAll() {
        for (Integer chrId : new ArrayList<>(sessions.keySet())) {
            stop(chrId);
        }
    }

    public static boolean isRunning(int chrId) {
        return sessions.containsKey(chrId);
    }

    /** One entry per running vac, for the panel to show. */
    public static List<Object> describe() {
        List<Object> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            Map<String, Object> m = Json.obj();
            m.put("chrId", s.chrId);
            m.put("distance", s.options.distance());
            m.put("radius", s.options.radius());
            m.put("bosses", s.options.bosses());
            m.put("interval", s.options.interval());
            m.put("lastMoved", s.lastMoved);
            m.put("lastSeen", s.lastSeen);
            m.put("mapId", s.mapId);
            m.put("mapName", s.mapName);
            out.add(m);
        }
        return out;
    }

    private static void tick(Session session) {
        try {
            Character chr = findOnlineCharacter(session.chrId);
            if (chr == null) {
                stop(session.chrId);        // logged out - nothing left to vac towards
                return;
            }
            MapleMap map = chr.getMap();
            if (map == null) {
                return;
            }
            session.mapId = map.getId();
            session.mapName = map.getMapName();

            Target target = targetFor(chr, map, session.options.distance());
            long radiusSq = (long) session.options.radius() * session.options.radius();
            int moved = 0;
            int seen = 0;

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
                if (mob.getPosition().distanceSq(target.at()) <= ARRIVED_TOLERANCE * ARRIVED_TOLERANCE) {
                    continue;               // already on the pile
                }
                // Before the move, not after: both packets resetMobPosition sends - the movement
                // broadcast and the control handover at the end - read getFh() as they are built.
                if (target.fh() >= 0) {
                    mob.setFh(target.fh());
                }
                mob.resetMobPosition(target.at());
                moved++;
            }

            session.lastMoved = moved;
            session.lastSeen = seen;
        } catch (Exception e) {
            // A throw out of a TimerManager task kills the schedule silently, and the vac would
            // then look switched on while doing nothing at all.
            log.error("Web admin: mob vac tick failed for chr {}, switching it off", session.chrId, e);
            stop(session.chrId);
        }
    }

    /** Where mobs go this tick, and the foothold that point stands on ({@code -1} if none does). */
    private record Target(Point at, int fh) {
    }

    /**
     * A point the given distance ahead of the character, snapped down onto whatever foothold is
     * there. {@code calcDropPos} falls back to the character's own position when the spot is off
     * the map, so a character standing at the edge of a platform pulls mobs onto itself rather
     * than into the void.
     *
     * <h3>Why the foothold comes with it</h3>
     * A mob carries a foothold id as well as a position, and the client is the one simulating mob
     * movement: it is told both, and if they disagree it believes the foothold and drops the mob
     * until it finds real ground. That id is written once when the map loads
     * ({@code MapFactory.loadLife}) or when a spawn point revives the mob, and nothing updates it
     * afterwards - not even a genuine client-driven move, which reads the client's own foothold
     * out of the movement packet and then throws it away
     * ({@code AbstractMovementPacketHandler.updatePosition} skips those six bytes).
     * <p>
     * Ordinarily that goes unnoticed, because a mob walking under its own steam is already where
     * its controller thinks it is. The vac is different: {@link Monster#resetMobPosition} both
     * broadcasts a movement and hands control to a client, and each of those packets carries a
     * foothold describing a position the mob has only just been given. The client resolves the
     * contradiction the only way it can, and the mob you just pulled in falls out of the pile.
     * (The movement blob had a second fault of its own, fixed in
     * {@code AbstractAnimatedMapObject.getIdleMovement}, which used to hard-code foothold 0.)
     * <p>
     * So the id is set to the foothold the mob is actually being put on, which is the one the drop
     * position was snapped to. {@code findBelow} returns the first non-wall foothold at or under
     * the point, and the drop position sits exactly on its surface, so it hands back that same
     * foothold. Nothing here can leak into the map's spawn data: {@code SpawnPoint} copies its
     * foothold from the template mob in its constructor, at map-load time.
     */
    private static Target targetFor(Character chr, MapleMap map, int distance) {
        Point at = chr.getPosition();
        int ahead = chr.isFacingLeft() ? -distance : distance;
        Point drop = map.calcDropPos(new Point(at.x + ahead, at.y), at);
        Foothold ground = map.getFootholds().findBelow(drop);
        return new Target(drop, ground == null ? -1 : ground.getId());
    }

    static Character findOnlineCharacter(int chrId) {
        if (chrId < 0) {
            return null;
        }
        for (World world : Server.getInstance().getWorlds()) {
            Character chr = world.getPlayerStorage().getCharacterById(chrId);
            if (chr != null && chr.isLoggedin()) {
                return chr;
            }
        }
        return null;
    }
}
