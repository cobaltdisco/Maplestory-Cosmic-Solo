package net.server.webadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everywhere a teleport can send someone, in the two shapes the panel offers: a flat searchable
 * list of every map, and the game's own world map to click around on.
 *
 * <h3>The list</h3>
 * A map is in the list only if {@code Map.wz} actually holds its data, because that is what
 * {@link server.maps.MapFactory} loads and what therefore decides whether a warp arrives or
 * throws. The two files disagree in both directions: of the 4,416 maps {@code String.wz/Map.img}
 * names, 533 have no data here and are left out, while 1,379 of the 5,262 maps that do have data
 * carry no name and are listed by id alone. Names, street names and the region grouping come
 * from String.wz - the region is the game's own top-level grouping (maple, victoria, ossyria,
 * ...), not a bracketing invented from id ranges.
 *
 * <h3>The world map</h3>
 * Layout and artwork both come from {@code webadmin/worldmap}, written by tools-local's
 * WorldMapDump out of the client archive - see that file for why they cannot be taken from
 * different places. This class only merges in the names, and drops any spot whose maps do not
 * exist on this server.
 */
public final class MapIndex {
    private static final Logger log = LoggerFactory.getLogger(MapIndex.class);
    private static final Path WORLD_MAP_DIR = Path.of("webadmin", "worldmap");

    public record Entry(int id, String name, String street, String region) {
    }

    /** A clickable point on a world map, standing for one or more actual maps. */
    public record Spot(int x, int y, int type, String title, String desc, List<Integer> maps) {
    }

    /** A region of a world map that opens another world map. {@code index} names its PNG. */
    public record Link(int index, int x, int y, int w, int h, String toolTip, String target) {
    }

    public record World(String name, int width, int height, List<Spot> spots, List<Link> links) {
    }

    private static volatile List<Entry> entries;
    private static volatile Map<Integer, Entry> byId = Map.of();
    private static volatile Map<String, World> worlds = Map.of();
    private static final Object buildLock = new Object();

    private MapIndex() {
    }

    public static List<Entry> get() {
        return entries;
    }

    public static Entry byId(int id) {
        return byId.get(id);
    }

    public static Map<String, World> worlds() {
        return worlds;
    }

    public static void build() {
        synchronized (buildLock) {
            if (entries != null) {
                return;
            }
            long start = System.currentTimeMillis();
            Map<Integer, String[]> named = readNames();
            List<Entry> found = new ArrayList<>();
            Map<Integer, Entry> index = new HashMap<>();
            for (int id : existingMapIds()) {
                String[] name = named.get(id);
                Entry entry = new Entry(id,
                        name == null ? "" : name[0],
                        name == null ? "" : name[1],
                        name == null ? "" : name[2]);
                found.add(entry);
                index.put(id, entry);
            }
            found.sort((a, b) -> Integer.compare(a.id(), b.id()));
            entries = List.copyOf(found);
            byId = Map.copyOf(index);
            worlds = readWorldMaps();

            int unnamed = (int) entries.stream().filter(e -> e.name().isEmpty()).count();
            log.info("Web admin: indexed {} maps in {} ms ({} with no String.wz name, "
                            + "{} named maps have no Map.wz data and were left out), {} world maps",
                    entries.size(), System.currentTimeMillis() - start, unnamed,
                    named.size() - (entries.size() - unnamed), worlds.size());
        }
    }

    /** Every map id Map.wz actually holds data for. The file name is the zero-padded id. */
    private static List<Integer> existingMapIds() {
        List<Integer> ids = new ArrayList<>();
        Path root = WZFiles.MAP.getFile().resolve("Map");
        try (DirectoryStream<Path> areas = Files.newDirectoryStream(root, "Map*")) {
            for (Path area : areas) {
                if (!Files.isDirectory(area)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(area, "*.img.xml")) {
                    for (Path file : files) {
                        String base = file.getFileName().toString();
                        base = base.substring(0, base.length() - ".img.xml".length());
                        try {
                            ids.add(Integer.parseInt(base));
                        } catch (NumberFormatException e) {
                            // AreaCode.img and friends sit in the same tree but are not maps
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("Web admin: could not list {}", root, e);
        }
        return ids;
    }

    /**
     * id -> {mapName, streetName, region}, read straight off String.wz/Map.img.xml.
     * <p>
     * The file is two levels deep - region, then one directory per map - and one tag per line, so
     * a line scanner is enough and skips the DOM (and the provider's file-wide lock) entirely.
     */
    private static Map<Integer, String[]> readNames() {
        Map<Integer, String[]> out = new HashMap<>();
        Path file = WZFiles.STRING.getFile().resolve("Map.img.xml");
        try (BufferedReader reader = Wz.reader(file)) {
            String line;
            int depth = 0;
            String region = "";
            int id = -1;
            String name = "", street = "";
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("<imgdir")) {
                    depth++;
                    String label = Wz.attr(trimmed, "name");
                    if (depth == 2) {
                        region = label == null ? "" : label;
                    } else if (depth == 3) {
                        id = parseId(label);
                        name = "";
                        street = "";
                    }
                } else if (trimmed.startsWith("</imgdir>")) {
                    if (depth == 3 && id >= 0) {
                        out.put(id, new String[]{name, street, region});
                    }
                    depth--;
                } else if (depth == 3) {
                    String key = Wz.attr(trimmed, "name");
                    if ("mapName".equals(key)) {
                        name = Wz.unescape(Wz.attr(trimmed, "value"));
                    } else if ("streetName".equals(key)) {
                        street = Wz.unescape(Wz.attr(trimmed, "value"));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Web admin: could not read {}", file, e);
        }
        return out;
    }

    /** Reads the layout WorldMapDump wrote, keeping only the maps this server can actually load. */
    private static Map<String, World> readWorldMaps() {
        Path file = WORLD_MAP_DIR.resolve("layout.txt");
        if (!Files.isReadable(file)) {
            log.info("Web admin: no world map layout at {} - run tools-local/WorldMapDump to"
                    + " browse maps on the world map", file);
            return Map.of();
        }
        Map<String, World> out = new LinkedHashMap<>();
        String name = null;
        int width = 0, height = 0;
        List<Spot> spots = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        try (BufferedReader reader = Wz.reader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    case "M" -> {
                        if (name != null) {
                            out.put(name, new World(name, width, height, List.copyOf(spots), List.copyOf(links)));
                        }
                        name = f[1];
                        width = Integer.parseInt(f[2]);
                        height = Integer.parseInt(f[3]);
                        spots = new ArrayList<>();
                        links = new ArrayList<>();
                    }
                    case "S" -> {
                        List<Integer> maps = new ArrayList<>();
                        for (String raw : f[6].split(",")) {
                            int id = parseId(raw);
                            // A spot can name a map this server has no data for, and a few name
                            // the same map twice. Both would be dead rows in the panel.
                            if (id >= 0 && byId.containsKey(id) && !maps.contains(id)) {
                                maps.add(id);
                            }
                        }
                        if (!maps.isEmpty()) {
                            spots.add(new Spot(Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                                    Integer.parseInt(f[3]), f[4], f[5], List.copyOf(maps)));
                        }
                    }
                    case "L" -> links.add(new Link(Integer.parseInt(f[1]),
                            Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                            Integer.parseInt(f[4]), Integer.parseInt(f[5]), f[6], f[7]));
                    default -> {
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("Web admin: could not read the world map layout at {}", file, e);
            return Map.of();
        }
        if (name != null) {
            out.put(name, new World(name, width, height, List.copyOf(spots), List.copyOf(links)));
        }
        return Map.copyOf(out);
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
