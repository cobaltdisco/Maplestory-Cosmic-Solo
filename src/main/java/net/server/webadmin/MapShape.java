package net.server.webadmin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.wz.WZFiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shape of one map: where the platforms are, where the ropes are, and where the portals are.
 * <p>
 * Read straight out of the map's own XML rather than from the live {@link server.maps.MapleMap}.
 * The map object keeps its footholds in a quadtree that never hands the whole list back, and
 * {@code getPortals()} is commented out in this server, leaving only a lookup by id - so the file
 * is both the fuller source and the one the rest of the panel already reads.
 * <p>
 * Unlike the other indexes this is built on demand. There are 5,262 maps and no reason to parse a
 * map nobody is looking at; the shapes that do get parsed are cached, and the cache is dropped
 * wholesale once it grows past {@link #CACHE_LIMIT} rather than being made clever about it.
 */
public final class MapShape {
    private static final Logger log = LoggerFactory.getLogger(MapShape.class);

    private static final int CACHE_LIMIT = 64;

    /** One platform segment. Walls (x1 == x2) are in here too - they are what stops you sideways. */
    public record Line(int x1, int y1, int x2, int y2) {
    }

    /** A rope or a ladder: vertical, from y1 down to y2. */
    public record Climb(int x, int y1, int y2, boolean ladder) {
    }

    /**
     * {@code type} is the wz {@code pt}: 0 spawn point, 1 an in-map teleport, 2 a portal to another
     * map. {@code targetMapId} is 999999999 when the portal does not name a destination itself -
     * either it is scripted, or it is a within-map pair whose other half is {@code targetName}.
     */
    public record Portal(String name, int type, int x, int y, int targetMapId, String targetName,
                         String script) {
    }

    public record Shape(int mapId, int left, int top, int right, int bottom,
                        List<Line> footholds, List<Climb> climbs, List<Portal> portals) {
    }

    private static final Map<Integer, Shape> cache = new ConcurrentHashMap<>();

    private MapShape() {
    }

    public static Shape of(int mapId) {
        Shape cached = cache.get(mapId);
        if (cached != null) {
            return cached;
        }
        Shape shape = read(mapId);
        if (shape != null) {
            if (cache.size() >= CACHE_LIMIT) {
                cache.clear();
            }
            cache.put(mapId, shape);
        }
        return shape;
    }

    private static Shape read(int mapId) {
        Path file = WZFiles.MAP.getFile().resolve("Map")
                .resolve("Map" + mapId / 100000000)
                .resolve(String.format("%09d.img.xml", mapId));

        List<Line> footholds = new ArrayList<>();
        List<Climb> climbs = new ArrayList<>();
        List<Portal> portals = new ArrayList<>();

        try (BufferedReader reader = Wz.reader(file)) {
            String line;
            int depth = 0;
            String block = "";              // which top-level block we are inside
            Map<String, String> leaf = new HashMap<>();

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("<imgdir")) {
                    depth++;
                    if (depth == 2) {       // a direct child of the root: info, foothold, portal...
                        block = Wz.attr(trimmed, "name");
                    }
                    // Every open resets the pending values, so a group directory contributes
                    // nothing of its own and only the innermost node is ever flushed.
                    leaf.clear();
                } else if (trimmed.startsWith("</imgdir>")) {
                    flush(block, leaf, footholds, climbs, portals);
                    leaf.clear();
                    depth--;
                } else if (depth >= 3 && !block.isEmpty()) {
                    String key = Wz.attr(trimmed, "name");
                    if (key != null) {
                        leaf.put(key, Wz.attr(trimmed, "value"));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Web admin: could not read the shape of map {}", mapId, e);
            return null;
        }

        if (footholds.isEmpty() && portals.isEmpty()) {
            return null;
        }

        // Bounds from what is actually drawn, not from the map's VR rectangle: the VR box is the
        // camera limit and on some maps it is far wider than anything you can stand on.
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (Line f : footholds) {
            left = Math.min(left, Math.min(f.x1(), f.x2()));
            right = Math.max(right, Math.max(f.x1(), f.x2()));
            top = Math.min(top, Math.min(f.y1(), f.y2()));
            bottom = Math.max(bottom, Math.max(f.y1(), f.y2()));
        }
        for (Portal p : portals) {
            left = Math.min(left, p.x());
            right = Math.max(right, p.x());
            top = Math.min(top, p.y());
            bottom = Math.max(bottom, p.y());
        }
        int margin = 60;
        return new Shape(mapId, left - margin, top - margin, right + margin, bottom + margin,
                List.copyOf(footholds), List.copyOf(climbs), List.copyOf(portals));
    }

    private static void flush(String block, Map<String, String> leaf, List<Line> footholds,
                              List<Climb> climbs, List<Portal> portals) {
        switch (block) {
            case "foothold" -> {
                if (leaf.containsKey("x1") && leaf.containsKey("y2")) {
                    footholds.add(new Line(num(leaf, "x1"), num(leaf, "y1"),
                            num(leaf, "x2"), num(leaf, "y2")));
                }
            }
            case "ladderRope" -> {
                if (leaf.containsKey("x") && leaf.containsKey("y1")) {
                    climbs.add(new Climb(num(leaf, "x"), num(leaf, "y1"), num(leaf, "y2"),
                            num(leaf, "l") == 1));
                }
            }
            case "portal" -> {
                if (leaf.containsKey("pn")) {
                    portals.add(new Portal(Wz.unescape(leaf.getOrDefault("pn", "")),
                            num(leaf, "pt"), num(leaf, "x"), num(leaf, "y"),
                            num(leaf, "tm"), Wz.unescape(leaf.getOrDefault("tn", "")),
                            Wz.unescape(leaf.getOrDefault("script", ""))));
                }
            }
            default -> {
            }
        }
    }

    private static int num(Map<String, String> leaf, String key) {
        String raw = leaf.get(key);
        if (raw == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
