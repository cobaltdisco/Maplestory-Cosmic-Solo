package net.server.webadmin;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.MapleMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * A small admin panel served straight out of the game server process, on loopback only.
 * <p>
 * Living inside the process is the point: rates, the online player list and item handouts all
 * need the live {@link World} and {@link Character} objects, which a separate web app talking
 * to MySQL could not reach. It uses the JDK's own HTTP server so the panel adds no dependency.
 */
public class WebAdminServer {
    private static final Logger log = LoggerFactory.getLogger(WebAdminServer.class);
    private static final String LOOPBACK = "127.0.0.1";
    private static final Path ICON_DIR = Path.of("webadmin", "icons");
    private static final Path WORLD_MAP_DIR = Path.of("webadmin", "worldmap");

    private static HttpServer server;
    private static volatile int iconCount = -1;
    private static volatile long iconCountAt;

    private WebAdminServer() {
    }

    public static synchronized void start(int port) {
        if (server != null) {
            return;
        }
        try {
            // Loopback only, deliberately: these endpoints hand out items and rewrite rates with
            // no authentication, matching how the rest of this server is bound (see LoginServer).
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", WebAdminServer::handlePage);
            server.createContext("/icons/", WebAdminServer::handleIcon);
            server.createContext("/worldmap/", WebAdminServer::handleWorldMapImage);
            server.createContext("/api/state", exchange -> respondJson(exchange, state()));
            server.createContext("/api/rates", WebAdminServer::handleRates);
            server.createContext("/api/equips", WebAdminServer::handleEquips);
            server.createContext("/api/items", WebAdminServer::handleItems);
            server.createContext("/api/gift", WebAdminServer::handleGift);
            server.createContext("/api/vac", WebAdminServer::handleVac);
            server.createContext("/api/maps", WebAdminServer::handleMaps);
            server.createContext("/api/worldmap", WebAdminServer::handleWorldMap);
            server.createContext("/api/warp", WebAdminServer::handleWarp);
            server.start();
            log.info("Web admin panel on http://{}:{}", LOOPBACK, port);
        } catch (IOException e) {
            server = null;
            log.error("Web admin panel failed to start on port {}", port, e);
            return;
        }

        Thread indexer = new Thread(() -> {
            MapIndex.build();
            ItemIndex.build();
            EquipIndex.build();
        }, "webadmin-indexer");
        indexer.setDaemon(true);
        indexer.start();
    }

    public static synchronized void stop() {
        MobVac.stopAll();
        if (server != null) {
            server.stop(1);
            server = null;
            log.info("Web admin panel stopped");
        }
    }

    // ------------------------------------------------------------------ page

    private static void handlePage(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath()) && !"/index.html".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = readPage();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Serves the item icons dumped out of the client archives by tools-local/IconDump. They live
     * on disk rather than in the jar: 15k PNGs are regenerable from the client and have no
     * business bloating the build.
     */
    private static void handleIcon(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring("/icons/".length());
        // The name goes straight into a path, so accept only what the dumper ever writes.
        sendPng(exchange, ICON_DIR, name, "[0-9]{1,8}\\.png");
    }

    /** The world map artwork, dumped by tools-local/WorldMapDump. Same deal as the icons. */
    private static void handleWorldMapImage(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring("/worldmap/".length());
        sendPng(exchange, WORLD_MAP_DIR, name, "WorldMap[0-9]{0,3}(\\.link[0-9]{1,3})?\\.png");
    }

    private static void sendPng(HttpExchange exchange, Path dir, String name, String allowed)
            throws IOException {
        Path file = dir.resolve(name);
        if (!name.matches(allowed) || !Files.isReadable(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        // The artwork only changes when the client's wz does, so let the browser keep it.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] readPage() throws IOException {
        // Prefer a copy next to the jar so the page can be edited without a rebuild; fall back
        // to the one packed into the jar.
        Path onDisk = Path.of("webadmin", "index.html");
        if (Files.isReadable(onDisk)) {
            return Files.readAllBytes(onDisk);
        }
        try (InputStream in = WebAdminServer.class.getResourceAsStream("/webadmin/index.html")) {
            if (in == null) {
                return "<h1>webadmin/index.html is missing</h1>".getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        }
    }

    // ----------------------------------------------------------------- state

    private static Map<String, Object> state() {
        Server srv = Server.getInstance();
        List<Object> worlds = new ArrayList<>();
        List<Object> players = new ArrayList<>();

        for (World world : srv.getWorlds()) {
            Map<String, Object> w = Json.obj();
            w.put("id", world.getId());
            w.put("name", worldName(world.getId()));
            w.put("exp", world.getExpRate());
            w.put("meso", world.getMesoRate());
            w.put("drop", world.getDropRate());
            w.put("bossDrop", world.getBossDropRate());
            w.put("quest", world.getQuestRate());
            w.put("fishing", world.getFishingRate());
            w.put("travel", world.getTravelRate());
            w.put("players", world.getPlayerStorage().getSize());
            worlds.add(w);

            for (Channel ch : world.getChannels()) {
                for (Character chr : ch.getPlayerStorage().getAllCharacters()) {
                    players.add(describe(chr, world.getId(), ch.getId()));
                }
            }
        }

        Map<String, Object> global = Json.obj();
        global.put("equipExp", YamlConfig.config.server.EQUIP_EXP_RATE);
        global.put("pqBonusExp", YamlConfig.config.server.PQ_BONUS_EXP_RATE);
        global.put("partyBonusExp", YamlConfig.config.server.PARTY_BONUS_EXP_RATE);
        global.put("useQuestRate", YamlConfig.config.server.USE_QUEST_RATE);

        Map<String, Object> index = Json.obj();
        index.put("equipStatus", EquipIndex.status());
        index.put("equipCount", EquipIndex.get() == null ? EquipIndex.progress() : EquipIndex.get().size());
        index.put("itemCount", ItemIndex.get() == null ? 0 : ItemIndex.get().size());
        index.put("mapCount", MapIndex.get() == null ? 0 : MapIndex.get().size());
        index.put("worldMapCount", MapIndex.worlds().size());
        index.put("iconCount", iconCount());

        Map<String, Object> out = Json.obj();
        out.put("worlds", worlds);
        out.put("global", global);
        out.put("players", players);
        out.put("index", index);
        out.put("vacs", MobVac.describe());
        return out;
    }

    private static Map<String, Object> describe(Character chr, int worldId, int channelId) {
        Map<String, Object> p = Json.obj();
        p.put("id", chr.getId());
        p.put("name", chr.getName());
        p.put("world", worldId);
        p.put("channel", channelId);
        p.put("level", chr.getLevel());
        p.put("job", chr.getJob().getId());
        p.put("jobName", chr.getJob().name());
        p.put("gender", chr.getGender());
        p.put("meso", chr.getMeso());
        p.put("gm", chr.gmLevel());
        p.put("mapId", chr.getMapId());
        MapleMap map = chr.getMap();
        p.put("mapName", map == null ? "" : map.getMapName());
        p.put("freeEquip", freeSlots(chr, InventoryType.EQUIP));
        p.put("freeUse", freeSlots(chr, InventoryType.USE));
        p.put("freeEtc", freeSlots(chr, InventoryType.ETC));
        p.put("freeCash", freeSlots(chr, InventoryType.CASH));
        return p;
    }

    /**
     * How many icons IconDump has dumped, so the page can say "run the dumper" instead of showing
     * 300 broken images. Listing 16k files on every 5-second poll would be silly, but caching it
     * for the process lifetime means re-running the dumper leaves the panel quoting a stale
     * number - so it is re-counted at most once a minute.
     */
    private static int iconCount() {
        long now = System.currentTimeMillis();
        if (iconCount < 0 || now - iconCountAt > MINUTES.toMillis(1)) {
            int found = 0;
            try (DirectoryStream<Path> icons = Files.newDirectoryStream(ICON_DIR, "*.png")) {
                for (Path ignored : icons) {
                    found++;
                }
            } catch (IOException e) {
                found = 0;      // the directory simply isn't there yet
            }
            iconCount = found;
            iconCountAt = now;
        }
        return iconCount;
    }

    private static int freeSlots(Character chr, InventoryType type) {
        Inventory inv = chr.getInventory(type);
        return inv == null ? 0 : inv.getNumFreeSlot();
    }

    private static String worldName(int id) {
        return id >= 0 && id < GameConstants.WORLD_NAMES.length ? GameConstants.WORLD_NAMES[id] : "World " + id;
    }

    // ----------------------------------------------------------------- rates

    private static void handleRates(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, error("POST only"));
            return;
        }
        Map<String, String> form = readForm(exchange);
        List<String> applied = new ArrayList<>();

        String worldParam = form.get("world");
        if (worldParam != null) {
            World world = Server.getInstance().getWorld(parseInt(worldParam, -1));
            if (world == null) {
                respondJson(exchange, error("no such world: " + worldParam));
                return;
            }
            // Rates are applied per world and take effect immediately: setExpRate & friends walk
            // the online players and re-apply their personal rates.
            applyRate(form, "exp", applied, v -> world.setExpRate(v));
            applyRate(form, "meso", applied, v -> world.setMesoRate(v));
            applyRate(form, "drop", applied, v -> world.setDropRate(v));
            applyRate(form, "bossDrop", applied, v -> world.setBossDropRate(v));
            applyRate(form, "quest", applied, v -> world.setQuestRate(v));
            applyRate(form, "fishing", applied, v -> world.setFishingRate(v));
            applyRate(form, "travel", applied, v -> world.setTravelRate(v));
        }

        // These four are server-wide rather than per world. They are read from the config object
        // at the moment they are used, so writing them here takes effect immediately too - but it
        // does not rewrite config.yaml, so a restart brings back the file's values.
        if (form.containsKey("equipExp")) {
            YamlConfig.config.server.EQUIP_EXP_RATE = clampDouble(form.get("equipExp"), 0, 1000);
            applied.add("equipExp");
        }
        if (form.containsKey("pqBonusExp")) {
            YamlConfig.config.server.PQ_BONUS_EXP_RATE = clampDouble(form.get("pqBonusExp"), 0, 1000);
            applied.add("pqBonusExp");
        }
        if (form.containsKey("partyBonusExp")) {
            YamlConfig.config.server.PARTY_BONUS_EXP_RATE = (float) clampDouble(form.get("partyBonusExp"), 0, 1000);
            applied.add("partyBonusExp");
        }
        if (form.containsKey("useQuestRate")) {
            YamlConfig.config.server.USE_QUEST_RATE = "true".equalsIgnoreCase(form.get("useQuestRate"));
            applied.add("useQuestRate");
        }

        log.info("Web admin: rates changed ({})", String.join(", ", applied));
        Map<String, Object> out = state();
        out.put("ok", true);
        out.put("applied", applied);
        respondJson(exchange, out);
    }

    private interface RateSetter {
        void set(int value);
    }

    private static void applyRate(Map<String, String> form, String key, List<String> applied, RateSetter setter) {
        String raw = form.get(key);
        if (raw == null) {
            return;
        }
        int value = parseInt(raw, Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE) {
            return;
        }
        setter.set(Math.max(1, Math.min(10000, value)));
        applied.add(key);
    }

    // ---------------------------------------------------------------- equips

    private static void handleEquips(HttpExchange exchange) throws IOException {
        List<EquipIndex.Entry> all = EquipIndex.get();
        if (all == null) {
            Map<String, Object> out = Json.obj();
            out.put("building", true);
            out.put("progress", EquipIndex.progress());
            out.put("results", List.of());
            respondJson(exchange, out);
            return;
        }

        Map<String, String> q = queryOf(exchange);
        int job = parseInt(q.get("job"), -1);           // -1 = any, otherwise a reqJob bit
        boolean jobStrict = "true".equalsIgnoreCase(q.get("jobStrict"));
        int gender = parseInt(q.get("gender"), -1);     // -1 = any, 0 male, 1 female, 2 unisex
        String slot = q.getOrDefault("slot", "");
        int maxLevel = parseInt(q.get("maxLevel"), -1);
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(500, parseInt(q.get("limit"), 200)));
        // Cash equips are the cash-shop cosmetics - roughly two thirds of the capes and a fifth
        // of the hats. They carry no stats, so they are hidden unless asked for.
        String cash = q.getOrDefault("cash", "hide");
        String sort = q.getOrDefault("sort", "id");
        // Items whose name is a generated stand-in cannot be picked out of a list on purpose.
        String named = q.getOrDefault("named", "hide");

        int weapon = parseInt(q.get("weapon"), -1);     // -1 = any, otherwise an id band like 145

        List<EquipIndex.Entry> matches = new ArrayList<>();
        Map<String, Integer> slotCounts = new HashMap<>();
        Map<Integer, Integer> weaponCounts = new TreeMap<>();
        for (EquipIndex.Entry e : all) {
            if (cash.equals("hide") && e.cash()) {
                continue;
            }
            if (cash.equals("only") && !e.cash()) {
                continue;
            }
            if (!matchesNamed(named, e.placeholder())) {
                continue;
            }
            if (!matchesJob(e.reqJob(), job, jobStrict)) {
                continue;
            }
            // A unisex item fits either character, so an explicit male/female filter keeps it.
            if (gender >= 0 && e.gender() != 2 && e.gender() != gender) {
                continue;
            }
            if (maxLevel >= 0 && e.reqLevel() > maxLevel) {
                continue;
            }
            if (!text.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(text)
                    && !String.valueOf(e.id()).contains(text)) {
                continue;
            }
            // Counted before their own filter, so each option shows how many items it would yield
            // under the other filters - an option's own selection must not shrink its own number.
            int band = EquipIndex.weaponBand(e);
            if (weapon < 0 || band == weapon) {
                slotCounts.merge(e.slot(), 1, Integer::sum);
            }
            if (band > 0 && (slot.isEmpty() || slot.equals(e.slot()))) {
                weaponCounts.merge(band, 1, Integer::sum);
            }
            if (!slot.isEmpty() && !slot.equals(e.slot())) {
                continue;
            }
            if (weapon >= 0 && band != weapon) {
                continue;
            }
            matches.add(e);
        }

        // Sorted before the cut to `limit`, otherwise "lowest level first" would only order
        // whichever 300 items happened to come first by id.
        matches.sort(comparatorFor(sort));

        List<Object> results = new ArrayList<>();
        for (EquipIndex.Entry e : matches.subList(0, Math.min(limit, matches.size()))) {
            results.add(equipJson(e));
        }

        Map<String, Object> out = Json.obj();
        out.put("building", false);
        out.put("total", matches.size());
        out.put("shown", results.size());
        out.put("results", results);
        out.put("slots", facetList(slotCounts, "slot"));
        // Weapon classes read best in id order (130 sword ... 149 gun, 170 cash), not by count.
        out.put("weapons", orderedFacetList(weaponCounts, "weapon"));
        respondJson(exchange, out);
    }

    /** "hide" drops generated names, "only" keeps just those, anything else keeps everything. */
    private static boolean matchesNamed(String named, boolean placeholder) {
        return switch (named) {
            case "hide" -> !placeholder;
            case "only" -> placeholder;
            default -> true;
        };
    }

    private static Comparator<EquipIndex.Entry> comparatorFor(String sort) {
        Comparator<EquipIndex.Entry> byId = Comparator.comparingInt(EquipIndex.Entry::id);
        return switch (sort) {
            // Ties broken by id so the order is stable and the same request always looks the same.
            case "level" -> Comparator.comparingInt(EquipIndex.Entry::reqLevel).thenComparing(byId);
            case "-level" -> Comparator.comparingInt(EquipIndex.Entry::reqLevel).reversed().thenComparing(byId);
            case "name" -> Comparator.comparing(EquipIndex.Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(byId);
            default -> byId;
        };
    }

    private static Map<String, Object> equipJson(EquipIndex.Entry e) {
        Map<String, Object> m = Json.obj();
        m.put("id", e.id());
        m.put("name", e.name());
        m.put("slot", e.slot());
        m.put("gender", e.gender());
        m.put("reqJob", e.reqJob());
        m.put("reqLevel", e.reqLevel());
        m.put("cash", e.cash());
        m.put("tuc", e.tuc());
        m.put("unsafe", e.unsafe());
        Map<String, Object> stats = Json.obj();
        for (String key : EquipIndex.STAT_KEYS) {
            stats.put(key, e.stats().getOrDefault(key, 0));
        }
        m.put("stats", stats);
        return m;
    }

    /**
     * reqJob is a bitmask (1 warrior, 2 magician, 4 bowman, 8 thief, 16 pirate); 0 means the item
     * has no job requirement at all and so fits everyone - which is true of about 8 in 10 equips,
     * hence the strict mode for when you want only the gear that class actually has a claim on.
     */
    private static boolean matchesJob(int reqJob, int wanted, boolean strict) {
        if (wanted < 0) {
            return true;
        }
        if (wanted == 0) {
            return reqJob == 0;
        }
        if ((reqJob & wanted) != 0) {
            return true;
        }
        return !strict && reqJob == 0;
    }

    /** Facet counts in the map's own order, under the key the front end filters on. */
    private static <K> List<Object> orderedFacetList(Map<K, Integer> counts, String key) {
        List<Object> out = new ArrayList<>();
        counts.forEach((facet, count) -> {
            Map<String, Object> m = Json.obj();
            m.put(key, facet);
            m.put("count", count);
            out.add(m);
        });
        return out;
    }

    /** Facet counts, biggest first, under the key the front end filters on. */
    private static <K> List<Object> facetList(Map<K, Integer> counts, String key) {
        List<Object> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(entry -> {
                    Map<String, Object> m = Json.obj();
                    m.put(key, entry.getKey());
                    m.put("count", entry.getValue());
                    out.add(m);
                });
        return out;
    }

    // ----------------------------------------------------------------- items

    private static void handleItems(HttpExchange exchange) throws IOException {
        List<ItemIndex.Entry> all = ItemIndex.get();
        if (all == null) {
            Map<String, Object> out = Json.obj();
            out.put("building", true);
            out.put("results", List.of());
            respondJson(exchange, out);
            return;
        }

        Map<String, String> q = queryOf(exchange);
        String inv = q.getOrDefault("inv", "");
        String category = q.getOrDefault("category", "");
        int band = parseInt(q.get("band"), -1);
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(500, parseInt(q.get("limit"), 200)));
        String named = q.getOrDefault("named", "hide");

        List<Object> results = new ArrayList<>();
        Map<Integer, Integer> bandCounts = new TreeMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        int matched = 0;

        for (ItemIndex.Entry e : all) {
            if (!inv.isEmpty() && !inv.equals(e.inv())) {
                continue;
            }
            if (!matchesNamed(named, e.placeholder())) {
                continue;
            }
            if (!text.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(text)
                    && !String.valueOf(e.id()).contains(text)) {
                continue;
            }
            // Each facet is counted with every filter applied except its own, so its numbers say
            // "pick me and you get this many" instead of collapsing to the current selection.
            if (band < 0 || e.band() == band) {
                categoryCounts.merge(e.category(), 1, Integer::sum);
            }
            if (category.isEmpty() || category.equals(e.category())) {
                bandCounts.merge(e.band(), 1, Integer::sum);
            }
            if (!category.isEmpty() && !category.equals(e.category())) {
                continue;
            }
            if (band >= 0 && e.band() != band) {
                continue;
            }
            matched++;
            if (results.size() < limit) {
                Map<String, Object> m = Json.obj();
                m.put("id", e.id());
                m.put("name", e.name());
                m.put("inv", e.inv());
                m.put("category", e.category());
                m.put("band", e.band());
                results.add(m);
            }
        }

        List<Object> bands = new ArrayList<>();
        bandCounts.forEach((key, count) -> {
            Map<String, Object> m = Json.obj();
            m.put("band", key);
            m.put("count", count);
            bands.add(m);
        });

        Map<String, Object> out = Json.obj();
        out.put("building", false);
        out.put("total", matched);
        out.put("shown", results.size());
        out.put("results", results);
        out.put("bands", bands);
        out.put("categories", facetList(categoryCounts, "category"));
        respondJson(exchange, out);
    }

    // ------------------------------------------------------------------- vac

    private static void handleVac(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, error("POST only"));
            return;
        }
        Map<String, String> form = readForm(exchange);
        int chrId = parseInt(form.get("chrId"), -1);

        if (!"true".equalsIgnoreCase(form.get("enabled"))) {
            MobVac.stop(chrId);
            Map<String, Object> out = state();
            out.put("ok", true);
            out.put("message", "mob vac off");
            respondJson(exchange, out);
            return;
        }

        Character chr = MobVac.findOnlineCharacter(chrId);
        if (chr == null) {
            respondJson(exchange, error("that character is not online any more"));
            return;
        }
        MobVac.start(chrId, new MobVac.Options(
                parseInt(form.get("distance"), 80),
                parseInt(form.get("radius"), 0),
                "true".equalsIgnoreCase(form.get("bosses")),
                parseInt(form.get("interval"), 800)));

        Map<String, Object> out = state();
        out.put("ok", true);
        out.put("message", "mob vac on for " + chr.getName());
        respondJson(exchange, out);
    }

    // ------------------------------------------------------------------- 传送

    private static void handleMaps(HttpExchange exchange) throws IOException {
        List<MapIndex.Entry> all = MapIndex.get();
        if (all == null) {
            Map<String, Object> out = Json.obj();
            out.put("building", true);
            out.put("results", List.of());
            respondJson(exchange, out);
            return;
        }

        Map<String, String> q = queryOf(exchange);
        String region = q.getOrDefault("region", "");
        String text = q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(500, parseInt(q.get("limit"), 200)));

        List<Object> results = new ArrayList<>();
        Map<String, Integer> regionCounts = new HashMap<>();
        int matched = 0;

        for (MapIndex.Entry e : all) {
            if (!text.isEmpty() && !matchesText(e, text)) {
                continue;
            }
            // The region facet is counted before the region filter, so each option says how many
            // it would yield rather than collapsing to whatever is already selected.
            regionCounts.merge(e.region(), 1, Integer::sum);
            if (!region.isEmpty() && !region.equals(e.region())) {
                continue;
            }
            matched++;
            if (results.size() < limit) {
                results.add(mapJson(e));
            }
        }

        Map<String, Object> out = Json.obj();
        out.put("building", false);
        out.put("total", matched);
        out.put("shown", results.size());
        out.put("results", results);
        out.put("regions", facetList(regionCounts, "region"));
        respondJson(exchange, out);
    }

    private static boolean matchesText(MapIndex.Entry e, String text) {
        return e.name().toLowerCase(Locale.ROOT).contains(text)
                || e.street().toLowerCase(Locale.ROOT).contains(text)
                || String.valueOf(e.id()).contains(text);
    }

    private static Map<String, Object> mapJson(MapIndex.Entry e) {
        Map<String, Object> m = Json.obj();
        m.put("id", e.id());
        m.put("name", e.name());
        m.put("street", e.street());
        m.put("region", e.region());
        return m;
    }

    /**
     * One node of the game's own world map, with the map ids behind each spot resolved to names.
     * Small enough (a few hundred spots at most) to send whole, so the page holds no state the
     * server does not.
     */
    private static void handleWorldMap(HttpExchange exchange) throws IOException {
        Map<String, MapIndex.World> worlds = MapIndex.worlds();
        Map<String, Object> out = Json.obj();

        String name = queryOf(exchange).getOrDefault("name", "WorldMap");
        MapIndex.World world = worlds.get(name);
        if (world == null) {
            out.put("ok", false);
            out.put("error", worlds.isEmpty()
                    ? "the world map has not been dumped yet - run tools-local/WorldMapDump"
                    : "no such world map: " + name);
            respondJson(exchange, out);
            return;
        }

        List<Object> spots = new ArrayList<>();
        for (MapIndex.Spot spot : world.spots()) {
            List<Object> maps = new ArrayList<>();
            for (int id : spot.maps()) {
                MapIndex.Entry e = MapIndex.byId(id);
                if (e != null) {
                    maps.add(mapJson(e));
                }
            }
            Map<String, Object> m = Json.obj();
            m.put("x", spot.x());
            m.put("y", spot.y());
            m.put("type", spot.type());
            m.put("title", spot.title());
            m.put("desc", spot.desc());
            // Most spots carry no title of their own; the street name is what the game itself
            // shows for a cluster of maps, so it is the label rather than an invented one.
            m.put("label", label(spot, maps));
            m.put("maps", maps);
            spots.add(m);
        }

        List<Object> links = new ArrayList<>();
        for (MapIndex.Link link : world.links()) {
            Map<String, Object> m = Json.obj();
            m.put("x", link.x());
            m.put("y", link.y());
            m.put("w", link.w());
            m.put("h", link.h());
            m.put("label", link.toolTip());
            m.put("target", link.target());
            m.put("image", name + ".link" + link.index() + ".png");
            m.put("known", worlds.containsKey(link.target()));
            links.add(m);
        }

        out.put("ok", true);
        out.put("name", name);
        out.put("image", name + ".png");
        out.put("width", world.width());
        out.put("height", world.height());
        out.put("spots", spots);
        out.put("links", links);
        respondJson(exchange, out);
    }

    @SuppressWarnings("unchecked")
    private static String label(MapIndex.Spot spot, List<Object> maps) {
        if (!spot.title().isEmpty()) {
            return spot.title();
        }
        if (maps.isEmpty()) {
            return "";
        }
        Map<String, Object> first = (Map<String, Object>) maps.get(0);
        String street = String.valueOf(first.get("street"));
        return street.isEmpty() ? String.valueOf(first.get("name")) : street;
    }

    private static void handleWarp(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, error("POST only"));
            return;
        }
        Map<String, String> form = readForm(exchange);

        Character chr = MobVac.findOnlineCharacter(parseInt(form.get("chrId"), -1));
        if (chr == null) {
            respondJson(exchange, error("that character is not online any more"));
            return;
        }
        Client client = chr.getClient();
        if (client == null) {
            respondJson(exchange, error("that character has no live session"));
            return;
        }
        // In the cash shop or the MTS the character is online but not standing on a map, and
        // changing the map underneath it there leaves the client in a state it cannot draw.
        if (!chr.isLoggedinWorld()) {
            respondJson(exchange, error(chr.getName() + " is in the cash shop or the MTS,"
                    + " not standing on a map"));
            return;
        }
        if (!chr.isAlive()) {
            respondJson(exchange, error(chr.getName() + " is dead - revive first"));
            return;
        }

        int mapId = parseInt(form.get("mapId"), -1);
        if (MapIndex.get() != null && MapIndex.byId(mapId) == null) {
            respondJson(exchange, error("Map.wz has no data for map " + form.get("mapId")));
            return;
        }

        MapleMap target;
        try {
            target = client.getChannelServer().getMapFactory().getMap(mapId);
        } catch (RuntimeException e) {
            // A map the index accepted can still fail to build - a broken link node, a portal
            // the factory cannot make. Better a message than a stack trace on the console.
            log.warn("Web admin: map {} failed to load", mapId, e);
            target = null;
        }
        if (target == null) {
            respondJson(exchange, error("map " + mapId + " failed to load - see the server log"));
            return;
        }

        // Moving a player from a thread that is not their own connection's is what the server
        // already does for the ferry rides (MapleMap schedules changeMap on a TimerManager
        // thread), so the HTTP thread is no different.
        //
        // saveLocationOnWarp is what the !warp command does, so the player's own return-scroll
        // location still points at where they were before the panel moved them.
        chr.saveLocationOnWarp();
        chr.changeMap(target, target.getRandomPlayerSpawnpoint());

        MapIndex.Entry entry = MapIndex.byId(mapId);
        String name = entry == null || entry.name().isEmpty() ? String.valueOf(mapId) : entry.name();
        log.info("Web admin: warped {} to {} ({})", chr.getName(), name, mapId);

        Map<String, Object> out = Json.obj();
        out.put("ok", true);
        out.put("message", chr.getName() + " → " + name + "（" + mapId + "）");
        respondJson(exchange, out);
    }

    // ------------------------------------------------------------------ gift

    private static void handleGift(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondJson(exchange, error("POST only"));
            return;
        }
        Map<String, String> form = readForm(exchange);

        Character chr = MobVac.findOnlineCharacter(parseInt(form.get("chrId"), -1));
        if (chr == null) {
            respondJson(exchange, error("that character is not online any more"));
            return;
        }
        Client client = chr.getClient();
        if (client == null) {
            respondJson(exchange, error("that character has no live session"));
            return;
        }

        int itemId = parseInt(form.get("itemId"), -1);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (itemId < 0 || ii.getName(itemId) == null) {
            respondJson(exchange, error("no such item: " + form.get("itemId")));
            return;
        }

        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (type == InventoryType.UNDEFINED) {
            respondJson(exchange, error("item " + itemId + " has no inventory tab"));
            return;
        }

        short qty = (short) Math.max(1, Math.min(Short.MAX_VALUE, parseInt(form.get("qty"), 1)));
        // Empty owner for stackables so a handout merges into whatever the player already has -
        // an owner name would force it into a slot of its own. Equips and pets do carry the name,
        // which is what the equivalent GM commands do and what the client displays.
        String owner = type == InventoryType.EQUIP || ItemConstants.isPet(itemId) ? chr.getName() : "";
        // checkSpace, not "is there a free slot": a stackable item can go into a partly filled
        // stack even when every slot is taken, and a rechargeable one needs a slot of its own.
        if (!InventoryManipulator.checkSpace(client, itemId, type == InventoryType.EQUIP ? 1 : qty, owner)) {
            respondJson(exchange, error(chr.getName() + " has no room for this in the "
                    + type.name() + " inventory"));
            return;
        }

        boolean ok;
        String describe;
        if (type == InventoryType.EQUIP) {
            Item item = ii.getEquipById(itemId);
            if (!(item instanceof Equip equip)) {
                respondJson(exchange, error("item " + itemId + " has no equip data"));
                return;
            }
            applyStats(equip, form);
            equip.setOwner(owner);
            ok = InventoryManipulator.addFromDrop(client, equip, false);
            describe = "equip";
        } else if (ItemConstants.isPet(itemId)) {
            // Pets need a pet record and an expiry, exactly as the !item command creates them.
            int days = Math.max(1, parseInt(form.get("petDays"), 90));
            long expiration = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days);
            int petId = Pet.createPet(itemId);
            if (petId == -1) {
                respondJson(exchange, error("could not create a pet record for " + itemId));
                return;
            }
            ok = InventoryManipulator.addById(client, itemId, (short) 1, owner, petId, expiration);
            describe = "pet (" + days + " days)";
        } else {
            ok = InventoryManipulator.addById(client, itemId, qty, owner, -1, (short) 0, -1);
            describe = "x" + qty;
        }

        if (!ok) {
            respondJson(exchange, error("the server refused the item (inventory full, or the item cannot be created)"));
            return;
        }

        String itemName = ii.getName(itemId);
        chr.dropMessage(6, "[GM] You received: " + itemName);
        log.info("Web admin: gave {} ({}) {} to {}", itemName, itemId, describe, chr.getName());

        Map<String, Object> out = Json.obj();
        out.put("ok", true);
        out.put("message", "sent " + itemName + " to " + chr.getName());
        respondJson(exchange, out);
    }

    /**
     * Overwrites the equip's stats with whatever the form supplied, leaving the base value in
     * place for anything the form left out.
     */
    private static void applyStats(Equip equip, Map<String, String> form) {
        setIfPresent(form, "STR", equip::setStr);
        setIfPresent(form, "DEX", equip::setDex);
        setIfPresent(form, "INT", equip::setInt);
        setIfPresent(form, "LUK", equip::setLuk);
        setIfPresent(form, "PAD", equip::setWatk);
        setIfPresent(form, "MAD", equip::setMatk);
        setIfPresent(form, "PDD", equip::setWdef);
        setIfPresent(form, "MDD", equip::setMdef);
        setIfPresent(form, "ACC", equip::setAcc);
        setIfPresent(form, "EVA", equip::setAvoid);
        setIfPresent(form, "MHP", equip::setHp);
        setIfPresent(form, "MMP", equip::setMp);
        setIfPresent(form, "Speed", equip::setSpeed);
        setIfPresent(form, "Jump", equip::setJump);

        String slots = form.get("tuc");
        if (slots != null) {
            equip.setUpgradeSlots((byte) Math.max(0, Math.min(127, parseInt(slots, equip.getUpgradeSlots()))));
        }
        if ("true".equalsIgnoreCase(form.get("untradeable"))) {
            equip.setFlag((short) (equip.getFlag() | ItemConstants.UNTRADEABLE));
        }
    }

    private interface ShortSetter {
        void set(short value);
    }

    private static void setIfPresent(Map<String, String> form, String key, ShortSetter setter) {
        String raw = form.get(key);
        if (raw == null || raw.isBlank()) {
            return;
        }
        int value = parseInt(raw, Integer.MIN_VALUE);
        if (value == Integer.MIN_VALUE) {
            return;
        }
        // MAX_EQUIPMNT_STAT is what the server itself caps equip stats at when levelling them up.
        int cap = Math.min(Short.MAX_VALUE, YamlConfig.config.server.MAX_EQUIPMNT_STAT);
        setter.set((short) Math.max(0, Math.min(cap, value)));
    }

    // ----------------------------------------------------------------- plumbing

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = Json.obj();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }

    private static void respondJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        byte[] body;
        try {
            body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.error("Web admin: failed to serialise a response", e);
            body = "{\"ok\":false,\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> queryOf(HttpExchange exchange) {
        return parseUrlEncoded(exchange.getRequestURI().getRawQuery());
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return parseUrlEncoded(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clampDouble(String raw, double min, double max) {
        try {
            return Math.max(min, Math.min(max, Double.parseDouble(raw.trim())));
        } catch (RuntimeException e) {
            return min;
        }
    }
}
