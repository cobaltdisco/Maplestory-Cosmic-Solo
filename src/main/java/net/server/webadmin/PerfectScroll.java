package net.server.webadmin;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * The characters whose scrolls always succeed, for as long as it is switched on.
 * <p>
 * This is only the success roll. The slot is still spent, an equip with no slots left still
 * cannot be scrolled, and the scroll itself is still consumed - so the equip's {@code tuc} is
 * still the ceiling. That is deliberately less than what {@code USE_PERFECT_GM_SCROLL} does:
 * a GM's scroll also skips the slot cost and the "needs a free slot" guard, which turns one slot
 * into unlimited scrolling. Curses stop happening on their own, because the server only rolls for
 * one in the failure branch.
 * <p>
 * Nothing is persisted, matching how the panel's other switches behave: the flag lives in memory,
 * and a restart clears it.
 */
public final class PerfectScroll {
    private static final Logger log = LoggerFactory.getLogger(PerfectScroll.class);

    private static final int SWEEP_INTERVAL = 30_000;
    /**
     * How many consecutive sweeps a character has to be missing before its flag is dropped. Two
     * gives a 30-60 second grace, which is far longer than a channel change - during which the
     * character is briefly in flight and should not lose the switch it was given.
     */
    private static final int SWEEPS_BEFORE_DROP = 2;

    /** chrId -> consecutive sweeps the character has been offline. Presence means "switched on". */
    private static final Map<Integer, Integer> flagged = new ConcurrentHashMap<>();
    private static ScheduledFuture<?> sweeper;

    private PerfectScroll() {
    }

    public static boolean isOn(int chrId) {
        return flagged.containsKey(chrId);
    }

    public static List<Object> describe() {
        return new ArrayList<>(flagged.keySet());
    }

    public static synchronized void start(int chrId) {
        flagged.put(chrId, 0);
        if (sweeper == null) {
            sweeper = TimerManager.getInstance().register(PerfectScroll::sweep, SWEEP_INTERVAL, SWEEP_INTERVAL);
        }
        log.info("Web admin: scrolls always succeed for chr {}", chrId);
    }

    public static synchronized void stop(int chrId) {
        if (flagged.remove(chrId) != null) {
            log.info("Web admin: scrolls roll normally again for chr {}", chrId);
        }
        stopSweeperIfIdle();
    }

    public static synchronized void stopAll() {
        for (Integer chrId : new ArrayList<>(flagged.keySet())) {
            stop(chrId);
        }
    }

    /**
     * Drops the flag once its character has gone. The panel is not the only thing that has to
     * notice a logout: leaving a flag behind would quietly switch itself back on the next time
     * that character logged in, which is not what "until you turn it off" means.
     */
    private static synchronized void sweep() {
        try {
            for (Map.Entry<Integer, Integer> entry : new ArrayList<>(flagged.entrySet())) {
                int chrId = entry.getKey();
                Character chr = MobVac.findOnlineCharacter(chrId);
                if (chr != null) {
                    flagged.put(chrId, 0);
                } else if (entry.getValue() + 1 >= SWEEPS_BEFORE_DROP) {
                    flagged.remove(chrId);
                    log.info("Web admin: chr {} logged out, scrolls roll normally again", chrId);
                } else {
                    flagged.put(chrId, entry.getValue() + 1);
                }
            }
            stopSweeperIfIdle();
        } catch (Exception e) {
            // A throw out of a TimerManager task kills the schedule silently, and the flags would
            // then outlive every logout with nothing left to clean them up.
            log.error("Web admin: the perfect-scroll sweep failed", e);
        }
    }

    private static void stopSweeperIfIdle() {
        if (flagged.isEmpty() && sweeper != null) {
            sweeper.cancel(false);
            sweeper = null;
        }
    }
}
