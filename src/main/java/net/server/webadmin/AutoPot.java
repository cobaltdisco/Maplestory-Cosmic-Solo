package net.server.webadmin;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.InventoryType;
import client.processor.action.PetAutopotProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Drinks a chosen potion whenever HP or MP drops below a chosen percentage.
 * <p>
 * The drinking itself is {@link PetAutopotProcessor#runAutopotAction}, which is what the pet
 * auto-potion already uses: it takes a slot and an item id and handles the rest - checking the
 * slot still holds that item, moving to the next stack when one runs out, applying the effect and
 * updating the inventory. Despite living under a pet-shaped name it needs no pet at all.
 * <p>
 * The threshold is checked here rather than through the character's own {@code autopotHpAlert},
 * because that one is driven by the client asking for a pet potion and only fires on damage. This
 * ticks on a timer, so it also tops the character up while nothing is hitting it.
 */
public final class AutoPot {
    private static final Logger log = LoggerFactory.getLogger(AutoPot.class);

    private static final int MIN_INTERVAL = 300;
    private static final int MAX_INTERVAL = 10_000;

    /** A percentage of 0 switches that half off; the item id is the potion to drink. */
    public record Options(int hpPercent, int hpItemId, int mpPercent, int mpItemId, int interval) {
        public Options {
            hpPercent = Math.max(0, Math.min(99, hpPercent));
            mpPercent = Math.max(0, Math.min(99, mpPercent));
            interval = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, interval));
        }
    }

    private static final class Session {
        final int chrId;
        final Options options;
        volatile ScheduledFuture<?> task;
        volatile long drank;
        volatile String note = "";

        Session(int chrId, Options options) {
            this.chrId = chrId;
            this.options = options;
        }
    }

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private AutoPot() {
    }

    public static synchronized void start(int chrId, Options options) {
        stop(chrId);
        Session session = new Session(chrId, options);
        sessions.put(chrId, session);
        session.task = TimerManager.getInstance().register(() -> tick(session),
                options.interval(), options.interval());
        log.info("Web admin: auto pot on for chr {} (HP<{}% -> {}, MP<{}% -> {}, every {}ms)",
                chrId, options.hpPercent(), options.hpItemId(),
                options.mpPercent(), options.mpItemId(), options.interval());
    }

    public static synchronized void stop(int chrId) {
        Session session = sessions.remove(chrId);
        if (session != null) {
            if (session.task != null) {
                session.task.cancel(false);
            }
            log.info("Web admin: auto pot off for chr {} after {} drinks", chrId, session.drank);
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
            m.put("hpPercent", s.options.hpPercent());
            m.put("hpItemId", s.options.hpItemId());
            m.put("mpPercent", s.options.mpPercent());
            m.put("mpItemId", s.options.mpItemId());
            m.put("interval", s.options.interval());
            m.put("drank", s.drank);
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
            Client client = chr.getClient();
            // A dead character cannot drink, and in the cash shop there is no HP bar to watch.
            if (client == null || !chr.isLoggedinWorld() || !chr.isAlive()) {
                session.note = chr.isAlive()
                        ? Lang.t("不在游戏里，暂停", "not in the game world, paused")
                        : Lang.t("角色已死亡，暂停", "character is dead, paused");
                return;
            }
            session.note = "";

            Options o = session.options;
            boolean drank = false;
            if (o.hpPercent() > 0 && percent(chr.getHp(), chr.getCurrentMaxHp()) < o.hpPercent()) {
                drank |= drink(chr, client, session, o.hpItemId(), "HP");
            }
            // Checked separately, and after: one potion can restore both, and this way a combined
            // potion that already fixed the HP is not drunk twice in the same tick.
            if (o.mpPercent() > 0 && percent(chr.getMp(), chr.getCurrentMaxMp()) < o.mpPercent()) {
                drank |= drink(chr, client, session, o.mpItemId(), "MP");
            }
            if (drank) {
                session.drank++;
            }
        } catch (Exception e) {
            // A throw out of a TimerManager task kills the schedule silently, and the panel would
            // then show auto pot as running while nothing was being drunk.
            log.error("Web admin: auto pot tick failed for chr {}, switching it off", session.chrId, e);
            stop(session.chrId);
        }
    }

    private static int percent(int current, int max) {
        return max <= 0 ? 100 : (int) ((long) current * 100 / max);
    }

    private static boolean drink(Character chr, Client client, Session session, int itemId, String what) {
        if (itemId <= 0) {
            session.note = Lang.t(what + " 低了，但没有选药",
                    what + " is low, but no potion was chosen");
            return false;
        }
        Item item = chr.getInventory(InventoryType.USE).findById(itemId);
        if (item == null) {
            session.note = Lang.t(what + " 低了，但背包里没有这个药了",
                    what + " is low, but there is none of that potion left in the bag");
            return false;
        }
        PetAutopotProcessor.runAutopotAction(client, item.getPosition(), itemId);
        return true;
    }
}
