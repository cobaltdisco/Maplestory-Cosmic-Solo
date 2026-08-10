package net.server.webadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import tools.DatabaseConnection;
import tools.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who drops what, and who lives where.
 *
 * <h3>Drops</h3>
 * Straight out of the {@code drop_data} table, which is where the server itself reads them.
 * {@code chance} is rolled as {@code Randomizer.nextInt(999999) < chance * dropRate}, so the
 * bare number is a probability out of a million <em>before</em> any of the server's rate
 * multipliers - which is exactly the "original" rate to show. Anything at or above 1,000,000
 * always drops. An item id of 0 is money.
 *
 * <h3>Spawns</h3>
 * From each map's {@code life} block, counting the entries whose {@code type} is {@code m}. That
 * is the map's spawn table: one entry is one monster the map keeps alive, so the count is how
 * many of that monster the map holds at once, not how many have ever spawned.
 * <p>
 * The block sits near the top of the file, before the tile and object data that make map XMLs
 * large, so the scan stops as soon as it closes and reads a small fraction of the 306 MB tree.
 */
public final class MobIndex {
    private static final Logger log = LoggerFactory.getLogger(MobIndex.class);

    public record Drop(int itemId, String itemName, int chance, int min, int max, int questId) {
    }

    /** The other direction: one monster that drops a given item. */
    public record Dropper(int mobId, int chance, int min, int max) {
    }

    /** How many of one monster a single map holds. */
    public record Spawn(int mapId, int mobId, int count) {
    }

    public record Mob(int id, String name, int dropCount, int mapCount, int totalSpawns) {
    }

    private static volatile List<Mob> mobs;
    private static volatile Map<Integer, List<Drop>> dropsByMob = Map.of();
    private static volatile Map<Integer, List<Dropper>> droppersByItem = Map.of();
    private static volatile Map<Integer, List<Spawn>> spawnsByMob = Map.of();
    private static volatile Map<Integer, List<Spawn>> spawnsByMap = Map.of();
    private static volatile Map<Integer, String> names = Map.of();
    private static final Object buildLock = new Object();

    private MobIndex() {
    }

    public static List<Mob> get() {
        return mobs;
    }

    public static String nameOf(int mobId) {
        return names.getOrDefault(mobId, "");
    }

    public static List<Drop> dropsOf(int mobId) {
        return dropsByMob.getOrDefault(mobId, List.of());
    }

    public static List<Dropper> droppersOf(int itemId) {
        return droppersByItem.getOrDefault(itemId, List.of());
    }

    public static List<Spawn> mapsOf(int mobId) {
        return spawnsByMob.getOrDefault(mobId, List.of());
    }

    public static List<Spawn> mobsOn(int mapId) {
        return spawnsByMap.getOrDefault(mapId, List.of());
    }

    public static void build() {
        synchronized (buildLock) {
            if (mobs != null) {
                return;
            }
            long start = System.currentTimeMillis();
            names = readNames();
            dropsByMob = readDrops();
            readSpawns();

            List<Mob> found = new ArrayList<>();
            // Every monster that either drops something or lives somewhere. A name on its own is
            // not enough: String.wz names plenty of ids no map ever spawns and nothing drops.
            java.util.Set<Integer> ids = new java.util.HashSet<>(dropsByMob.keySet());
            ids.addAll(spawnsByMob.keySet());
            for (int id : ids) {
                List<Spawn> where = mapsOf(id);
                found.add(new Mob(id, names.getOrDefault(id, ""), dropsOf(id).size(), where.size(),
                        where.stream().mapToInt(Spawn::count).sum()));
            }
            found.sort(Comparator.comparingInt(Mob::id));
            mobs = List.copyOf(found);

            log.info("Web admin: indexed {} monsters in {} ms ({} with drops, {} with spawns,"
                            + " {} map/monster pairs)", mobs.size(), System.currentTimeMillis() - start,
                    dropsByMob.size(), spawnsByMob.size(),
                    spawnsByMap.values().stream().mapToInt(List::size).sum());
        }
    }

    private static Map<Integer, String> readNames() {
        Map<Integer, String> out = new HashMap<>();
        Path file = WZFiles.STRING.getFile().resolve("Mob.img.xml");
        try (BufferedReader reader = Wz.reader(file)) {
            String line;
            int id = -1;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("<imgdir")) {
                    id = parseId(Wz.attr(trimmed, "name"));
                } else if (id >= 0 && "name".equals(Wz.attr(trimmed, "name"))) {
                    out.put(id, Wz.unescape(Wz.attr(trimmed, "value")));
                }
            }
        } catch (IOException e) {
            log.warn("Web admin: could not read {}", file, e);
        }
        return out;
    }

    /** One pass over the whole table - 22k rows is nothing, and per-monster queries would not be. */
    private static Map<Integer, List<Drop>> readDrops() {
        Map<Integer, String> itemNames = new HashMap<>();
        for (Pair<Integer, String> pair : ItemInformationProvider.getInstance().getAllItems()) {
            itemNames.put(pair.getLeft(), pair.getRight());
        }

        Map<Integer, List<Drop>> out = new HashMap<>();
        Map<Integer, List<Dropper>> reverse = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT dropperid, itemid, chance, minimum_quantity, maximum_quantity, questid"
                             + " FROM drop_data ORDER BY dropperid, chance DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int itemId = rs.getInt("itemid");
                int mobId = rs.getInt("dropperid");
                int chance = rs.getInt("chance");
                int min = rs.getInt("minimum_quantity");
                int max = rs.getInt("maximum_quantity");
                // Not run through Lang: the index is built once at startup, long before the panel
                // has said which language it wants. Item 0 is mesos, and the page names it from
                // the id rather than from this string.
                out.computeIfAbsent(mobId, k -> new ArrayList<>())
                        .add(new Drop(itemId,
                                itemId == 0 ? "金币" : itemNames.getOrDefault(itemId, ""),
                                chance, min, max, rs.getInt("questid")));
                reverse.computeIfAbsent(itemId, k -> new ArrayList<>())
                        .add(new Dropper(mobId, chance, min, max));
            }
        } catch (SQLException e) {
            log.error("Web admin: could not read drop_data", e);
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        // Best chance first, so "where do I farm this" is answered by the top row.
        reverse.replaceAll((k, v) -> {
            v.sort(Comparator.comparingInt(Dropper::chance).reversed());
            return List.copyOf(v);
        });
        droppersByItem = Map.copyOf(reverse);
        return Map.copyOf(out);
    }

    /** Fills both spawn directions in one walk of the map files. */
    private static void readSpawns() {
        Map<Integer, List<Spawn>> byMob = new HashMap<>();
        Map<Integer, List<Spawn>> byMap = new HashMap<>();
        List<MapIndex.Entry> maps = MapIndex.get();
        if (maps == null) {
            log.warn("Web admin: the map index is not built, so monster spawns were skipped");
            return;
        }

        for (MapIndex.Entry map : maps) {
            Map<Integer, Integer> counts = readLife(map.id());
            if (counts.isEmpty()) {
                continue;
            }
            List<Spawn> here = new ArrayList<>();
            counts.forEach((mobId, count) -> {
                Spawn spawn = new Spawn(map.id(), mobId, count);
                here.add(spawn);
                byMob.computeIfAbsent(mobId, k -> new ArrayList<>()).add(spawn);
            });
            here.sort(Comparator.comparingInt(Spawn::count).reversed());
            byMap.put(map.id(), List.copyOf(here));
        }

        byMob.replaceAll((k, v) -> {
            v.sort(Comparator.comparingInt(Spawn::count).reversed());
            return List.copyOf(v);
        });
        spawnsByMob = Map.copyOf(byMob);
        spawnsByMap = Map.copyOf(byMap);
    }

    /** mobId -> how many entries this map's life block has for it. */
    private static Map<Integer, Integer> readLife(int mapId) {
        Map<Integer, Integer> out = new HashMap<>();
        Path file = WZFiles.MAP.getFile().resolve("Map")
                .resolve("Map" + mapId / 100000000)
                .resolve(String.format("%09d.img.xml", mapId));
        try (BufferedReader reader = Wz.reader(file)) {
            String line;
            boolean inLife = false;
            int depth = 0;
            boolean isMob = false;
            int id = -1;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!inLife) {
                    if (trimmed.startsWith("<imgdir name=\"life\"")) {
                        inLife = true;
                    }
                    continue;
                }
                if (trimmed.startsWith("<imgdir")) {
                    depth++;
                    isMob = false;
                    id = -1;
                } else if (trimmed.startsWith("</imgdir>")) {
                    if (depth == 0) {
                        break;              // life closed - the rest of the file is scenery
                    }
                    if (isMob && id > 0) {
                        out.merge(id, 1, Integer::sum);
                    }
                    depth--;
                } else if (depth == 1) {
                    String key = Wz.attr(trimmed, "name");
                    if ("type".equals(key)) {
                        isMob = "m".equals(Wz.attr(trimmed, "value"));
                    } else if ("id".equals(key)) {
                        id = parseId(Wz.attr(trimmed, "value"));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Web admin: could not read the life block of map {}", mapId, e);
        }
        return out;
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
}
