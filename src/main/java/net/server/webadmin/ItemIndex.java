package net.server.webadmin;

import client.inventory.InventoryType;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import tools.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Searchable index of everything that is <em>not</em> an equip - the USE / SETUP / ETC / CASH
 * inventories.
 * <p>
 * Unlike equips, these items have no job or gender to filter on, so the index instead carries
 * two handles: the inventory tab (exact, straight from
 * {@link ItemConstants#getInventoryType}) and a purpose category.
 * <p>
 * Every purpose category below is decided by a predicate that already exists in the server
 * source ({@link ItemConstants} / {@link ItemId}) - none of them are guesses about what a
 * number range "probably" means. Anything without such a predicate is left as "other" and is
 * reached through the name search instead of being filed under an invented label.
 * <p>
 * The description is the text the game shows in the item's tooltip. It comes straight off the
 * String.wz files: {@link ItemInformationProvider} has no getter for it, and its per-item
 * getters re-parse a whole .img.xml behind a lock the game itself needs.
 */
public final class ItemIndex {
    private static final Logger log = LoggerFactory.getLogger(ItemIndex.class);

    /** The String.wz files that name and describe non-equip items. */
    private static final List<String> STRING_FILES = List.of(
            "Cash.img.xml", "Consume.img.xml", "Etc.img.xml", "Ins.img.xml", "Pet.img.xml");

    public record Entry(int id, String name, String desc, String inv, String category,
                        boolean placeholder) {
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
            Map<Integer, String> descs = readDescriptions();
            List<Entry> found = new ArrayList<>();
            for (Pair<Integer, String> pair : ItemInformationProvider.getInstance().getAllItems()) {
                int id = pair.getLeft();
                InventoryType type = ItemConstants.getInventoryType(id);
                if (type == InventoryType.EQUIP || type == InventoryType.UNDEFINED) {
                    continue;
                }
                String name = pair.getRight();
                boolean placeholder = Names.isPlaceholder(id, name);
                found.add(new Entry(id, name == null || name.isEmpty() ? "NO-NAME" : name,
                        descs.getOrDefault(id, ""), type.name(), categoryOf(id, type), placeholder));
            }
            found.sort((a, b) -> Integer.compare(a.id(), b.id()));
            entries = List.copyOf(found);
            long described = entries.stream().filter(e -> !e.desc().isEmpty()).count();
            log.info("Web admin: indexed {} non-equip items in {} ms, {} of them with a description",
                    entries.size(), System.currentTimeMillis() - start, described);
        }
    }

    /**
     * id -> tooltip text. The item folders are named by id but sit at different depths per file
     * (Etc.img wraps them in another folder), so this keys on the folder name being a number
     * rather than on a fixed depth.
     */
    private static Map<Integer, String> readDescriptions() {
        Map<Integer, String> out = new HashMap<>();
        for (String fileName : STRING_FILES) {
            Path file = WZFiles.STRING.getFile().resolve(fileName);
            try (BufferedReader reader = Wz.reader(file)) {
                String line;
                int depth = 0;
                int id = -1;
                int idDepth = -1;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("<imgdir")) {
                        depth++;
                        if (id < 0) {
                            id = parseId(Wz.attr(trimmed, "name"));
                            idDepth = id < 0 ? -1 : depth;
                        }
                    } else if (trimmed.startsWith("</imgdir>")) {
                        if (depth == idDepth) {
                            id = -1;
                            idDepth = -1;
                        }
                        depth--;
                    } else if (id >= 0 && "desc".equals(Wz.attr(trimmed, "name"))) {
                        String desc = clean(Wz.unescape(Wz.attr(trimmed, "value")));
                        if (!desc.isEmpty()) {
                            out.put(id, desc);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Web admin: could not read {}", file, e);
            }
        }
        return out;
    }

    /**
     * Turns a tooltip into plain text.
     * <p>
     * Line breaks are stored as the two characters {@code \n}, not as real newlines. Everything
     * else is the client's own markup, in which {@code #} is the control character: a code opens
     * a run and a bare {@code #} closes it. Across all five files the only openers that actually
     * occur are {@code #c} (963 times) and {@code #s} (once), both of which only colour the run
     * and carry no argument - so dropping the markers leaves exactly the words a player reads.
     */
    private static String clean(String raw) {
        return raw.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
                .replaceAll("#[cs]", "")
                .replace("#", "")
                .strip();
    }

    private static int parseId(String raw) {
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
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
