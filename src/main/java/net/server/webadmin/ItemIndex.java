package net.server.webadmin;

import client.inventory.InventoryType;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Searchable index of everything that is <em>not</em> an equip - the USE / SETUP / ETC / CASH
 * inventories.
 * <p>
 * Unlike equips, these items have no job or gender to filter on, so the index instead carries
 * three orthogonal handles: the inventory tab (exact, straight from
 * {@link ItemConstants#getInventoryType}), a purpose category, and the id band.
 * <p>
 * Every purpose category below is decided by a predicate that already exists in the server
 * source ({@link ItemConstants} / {@link ItemId}) - none of them are guesses about what a
 * number range "probably" means. Anything without such a predicate is left as "other" and is
 * reached through the id band or the name search instead of being filed under an invented label.
 * The id band is the grouping Item.wz itself uses (Consume/0200.img holds 2000000-2009999).
 */
public final class ItemIndex {
    private static final Logger log = LoggerFactory.getLogger(ItemIndex.class);

    public record Entry(int id, String name, String inv, String category, int band) {
    }

    private static volatile List<Entry> entries;
    private static final Object buildLock = new Object();

    private ItemIndex() {
    }

    public static List<Entry> get() {
        return entries;
    }

    public static void build() {
        synchronized (buildLock) {
            if (entries != null) {
                return;
            }
            long start = System.currentTimeMillis();
            List<Entry> found = new ArrayList<>();
            for (Pair<Integer, String> pair : ItemInformationProvider.getInstance().getAllItems()) {
                int id = pair.getLeft();
                InventoryType type = ItemConstants.getInventoryType(id);
                if (type == InventoryType.EQUIP || type == InventoryType.UNDEFINED) {
                    continue;
                }
                String name = pair.getRight();
                found.add(new Entry(id, name == null || name.isEmpty() ? "NO-NAME" : name,
                        type.name(), categoryOf(id, type), id / 10000));
            }
            found.sort((a, b) -> Integer.compare(a.id(), b.id()));
            entries = List.copyOf(found);
            log.info("Web admin: indexed {} non-equip items in {} ms", entries.size(),
                    System.currentTimeMillis() - start);
        }
    }

    private static String categoryOf(int id, InventoryType type) {
        return switch (type) {
            case USE -> {
                if (ItemConstants.isPotion(id)) {
                    yield "potion";
                } else if (ItemConstants.isFood(id)) {
                    yield "food";
                } else if (ItemConstants.isCleanSlate(id) || ItemConstants.isChaosScroll(id)
                        || (id >= 2049000 && id < 2050000)) {
                    yield "specialScroll";
                } else if (id >= 2040000 && id < 2049000) {
                    yield "scroll";
                } else if (ItemConstants.isTownScroll(id) && id < 2040000) {
                    yield "townScroll";
                } else if (ItemConstants.isArrow(id)) {
                    yield "arrow";
                } else if (ItemConstants.isThrowingStar(id)) {
                    yield "star";
                } else if (ItemConstants.isBullet(id)) {
                    yield "bullet";
                } else if (ItemId.isMonsterCard(id)) {
                    yield "card";
                }
                yield "other";
            }
            case SETUP -> ItemConstants.isFishingChair(id) || id / 10000 == 301 ? "chair" : "other";
            case ETC -> ItemConstants.isMakerReagent(id) ? "reagent" : "other";
            case CASH -> {
                if (ItemConstants.isPet(id)) {
                    yield "pet";
                } else if (ItemId.isRateCoupon(id)) {
                    yield "rateCoupon";
                } else if (ItemConstants.isCashStore(id)) {
                    yield "store";
                }
                yield "other";
            }
            default -> "other";
        };
    }
}
