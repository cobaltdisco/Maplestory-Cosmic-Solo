package net.server.webadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import tools.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searchable index of every equip the server can hand out: id, name, equip slot, gender,
 * job and level requirement, plus base stats.
 * <p>
 * The index is built by reading the {@code info} node straight off the Character.wz XML files
 * rather than by going through {@link ItemInformationProvider}. That provider re-parses (and
 * never caches) a whole .img.xml per lookup behind a lock the game itself needs - with ~9700
 * equips averaging 35 KB apiece, indexing through it would stall the running server for
 * minutes. The info node is always the first block of the file, so reading a few KB per file
 * turns the whole build into a couple of seconds of I/O with no lock contention.
 */
public final class EquipIndex {
    private static final Logger log = LoggerFactory.getLogger(EquipIndex.class);

    /**
     * Character.wz subdirectories that hold actual equipment. Face/Hair/Afterimage/Dragon live
     * in the same tree but are not items you can put in an inventory.
     */
    private static final List<String> EQUIP_DIRS = List.of(
            "Accessory", "Cap", "Cape", "Coat", "Glove", "Longcoat", "Pants",
            "PetEquip", "Ring", "Shield", "Shoes", "TamingMob", "Weapon");

    /**
     * Pet equips whose artwork references a pet id at or above 5002540. Handing one of these to
     * a player crashes the v83 client the moment the Pet tab is opened (NAMESPACE.DLL+0xa47a,
     * null write). The boundary is empirical, the mechanism is unknown - see docs/incidents.md.
     * The set is fixed: a full scan of Character.wz/PetEquip finds exactly these 19.
     */
    private static final Set<Integer> CLIENT_UNSAFE = Set.of(
            1802991, 1802992, 1802993,
            1803000, 1803001, 1803002, 1803013, 1803014, 1803015,
            1803026, 1803027, 1803028, 1803038, 1803039, 1803040,
            1803065, 1803069, 1803070, 1803071);

    /** Stat keys as {@link ItemInformationProvider#getEquipStats} spells them (the "inc" prefix stripped). */
    static final List<String> STAT_KEYS = List.of(
            "STR", "DEX", "INT", "LUK", "PAD", "MAD", "PDD", "MDD",
            "ACC", "EVA", "MHP", "MMP", "Speed", "Jump");

    public record Entry(int id, String name, String slot, int gender, int reqJob, int reqLevel,
                        boolean cash, int tuc, boolean unsafe, Map<String, Integer> stats) {
    }

    private static volatile List<Entry> entries;
    private static volatile String status = "idle";
    private static final AtomicInteger progress = new AtomicInteger();
    private static final Object buildLock = new Object();

    private EquipIndex() {
    }

    public static List<Entry> get() {
        return entries;
    }

    public static String status() {
        return status;
    }

    public static int progress() {
        return progress.get();
    }

    /** Builds the index if it hasn't been built yet. Safe to call from any thread. */
    public static void build() {
        synchronized (buildLock) {
            if (entries != null) {
                return;
            }
            long start = System.currentTimeMillis();
            status = "building";
            progress.set(0);
            try {
                entries = scan();
                status = "ready";
                log.info("Web admin: indexed {} equips in {} ms", entries.size(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                status = "failed: " + e.getMessage();
                log.error("Web admin: failed to build the equip index", e);
                entries = List.of();
            }
        }
    }

    private static List<Entry> scan() throws IOException {
        Map<Integer, String> names = new HashMap<>();
        for (Pair<Integer, String> pair : ItemInformationProvider.getInstance().getAllItems()) {
            names.put(pair.getLeft(), pair.getRight());
        }

        Path characterWz = WZFiles.CHARACTER.getFile();
        List<Entry> found = new ArrayList<>();
        for (String dirName : EQUIP_DIRS) {
            Path dir = characterWz.resolve(dirName);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.img.xml")) {
                for (Path file : stream) {
                    String base = file.getFileName().toString();
                    base = base.substring(0, base.length() - ".img.xml".length());
                    int id;
                    try {
                        id = Integer.parseInt(base);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (id < 1000000) {     // hair/face ids share the tree but aren't equips
                        continue;
                    }
                    Entry entry = readEntry(file, id, names.get(id));
                    if (entry != null) {
                        found.add(entry);
                        progress.incrementAndGet();
                    }
                }
            }
        }
        found.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return List.copyOf(found);
    }

    /**
     * Reads just the {@code info} block. The files are one tag per line, so a line scanner that
     * stops at the end of the block is enough - and it stops before the (much larger) frame data.
     */
    private static Entry readEntry(Path file, int id, String name) {
        String slot = null;
        int reqJob = 0;
        int reqLevel = 0;
        int cash = 0;
        int tuc = 0;
        Map<String, Integer> stats = new LinkedHashMap<>();

        // ISO-8859-1 rather than the file's declared UTF-8: everything read here (islot, the
        // numeric properties) is ASCII, and a single stray byte elsewhere in the file would
        // otherwise abort the read with a MalformedInputException.
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String line;
            int depth = 0;              // nesting depth relative to the info block
            boolean inInfo = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inInfo) {
                    if (trimmed.startsWith("<imgdir name=\"info\"")) {
                        inInfo = true;
                    }
                    continue;
                }
                if (trimmed.startsWith("<imgdir")) {
                    depth++;            // e.g. the per-level exp table; skip its contents
                    continue;
                }
                if (trimmed.startsWith("</imgdir>")) {
                    if (depth == 0) {
                        break;          // info block closed
                    }
                    depth--;
                    continue;
                }
                if (depth > 0) {
                    continue;           // only direct children of info are item properties
                }
                String attr = attrName(trimmed);
                if (attr == null) {
                    continue;
                }
                switch (attr) {
                    case "islot" -> slot = attrValue(trimmed);
                    case "reqJob" -> reqJob = intValue(trimmed);
                    case "reqLevel" -> reqLevel = intValue(trimmed);
                    case "cash" -> cash = intValue(trimmed);
                    case "tuc" -> tuc = intValue(trimmed);
                    default -> {
                        if (attr.startsWith("inc")) {
                            String key = attr.substring(3);
                            if (STAT_KEYS.contains(key)) {
                                stats.put(key, intValue(trimmed));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Web admin: could not read {}", file, e);
            return null;
        }

        if (name == null || name.isEmpty()) {
            name = "NO-NAME";
        }
        if (slot == null) {
            slot = "";
        }
        return new Entry(id, name, slot, genderOf(id), reqJob, reqLevel, cash > 0, tuc,
                CLIENT_UNSAFE.contains(id), stats);
    }

    /**
     * The weapon's class, as its id band: 130 one-handed sword, 145 bow, 170 cash weapon, and so
     * on - 0 for anything that is not a weapon.
     * <p>
     * This is the same value {@link ItemInformationProvider#getWeaponType} keys on
     * ({@code (id / 10000) % 100}), just not collapsed: that method folds axes and blunt weapons
     * into one GENERAL_SWING type, and calls everything outside 130-149 NOT_A_WEAPON - which
     * includes all 689 ported cash weapons in the 170 band. The band keeps both distinctions.
     * <p>
     * Whether an item is a weapon comes from its islot rather than its id range, so it agrees
     * with the equip-slot facet by construction.
     */
    public static int weaponBand(Entry e) {
        return e.slot().startsWith("Wp") ? e.id() / 10000 : 0;
    }

    /**
     * 0 = male, 1 = female, 2 = either. This is the id convention the v83 client itself applies
     * when it decides whether a character may wear an item, so filtering on it matches what the
     * player will actually be able to equip - regardless of what the item was "meant" to be.
     */
    private static int genderOf(int id) {
        int digit = (id / 1000) % 10;
        return (digit == 0 || digit == 1) ? digit : 2;
    }

    private static String attrName(String tag) {
        int i = tag.indexOf("name=\"");
        if (i < 0) {
            return null;
        }
        int end = tag.indexOf('"', i + 6);
        return end < 0 ? null : tag.substring(i + 6, end);
    }

    private static String attrValue(String tag) {
        int i = tag.indexOf("value=\"");
        if (i < 0) {
            return null;
        }
        int end = tag.indexOf('"', i + 7);
        return end < 0 ? null : tag.substring(i + 7, end);
    }

    private static int intValue(String tag) {
        String v = attrValue(tag);
        if (v == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
