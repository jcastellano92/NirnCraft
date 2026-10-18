package com.skycraft.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.quest.story.QuestPlaces;
import com.skycraft.quest.story.StoryData;
import com.skycraft.scatter.ScatterRules;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielBiomeSource;
import com.skycraft.tamriel.biome.Palettes;
import com.skycraft.tamriel.settlement.grammar.Styles;
import com.skycraft.tamriel.settlement.plan.CityPlans;
import com.skycraft.worldplan.scene.ScenePlan;
import com.skycraft.worldplan.scene.ScenePlanBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tier 1 in-JVM data reload ({@code /skycraft-dev reload <scenes|places|cityplans|quests|palettes|all>}):
 * syncs edited files from {@code mod/src/main/resources/skycraft} into {@code mod/build/resources/main/skycraft}
 * and re-reads the requested data caches in under a second without restarting the server.
 */
public final class DevReload {
    public static final String[] TARGETS = {"scenes", "places", "cityplans", "quests", "palettes", "all"};

    private DevReload() {}

    public static int execute(CommandSourceStack src, String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim().toLowerCase(Locale.ROOT);
        long t0 = System.nanoTime();
        try {
            int synced = syncResources() + DevResources.activate();
            List<String> lines = new ArrayList<>();
            switch (target) {
                case "cityplans" -> lines.add(reloadCityPlans());
                case "quests" -> lines.add(reloadQuests());
                case "scenes" -> lines.add(reloadScenes());
                case "places" -> lines.add(reloadPlaces());
                case "palettes" -> lines.add(reloadPalettes(src));
                case "all" -> {
                    lines.add(reloadPlaces());
                    lines.add(reloadCityPlans());
                    lines.add(reloadScenes());
                    lines.add(reloadQuests());
                    lines.add(reloadPalettes(src));
                    lines.add("not reloaded (requires restart/rebake): map rasters & tables (regions/elevation/terrain/water/coast PNGs, regions.json, hydrology.json, peaks.json), baked RoadPlan/StreetPlan, and Forge/datapack registries.");
                }
                default -> {
                    src.sendFailure(Component.literal("Unknown reload target '" + target
                            + "' (expected: scenes, places, cityplans, quests, palettes, all)"));
                    return 0;
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String header = "[reload " + target + "] OK in " + ms + " ms (synced " + synced + " resource file(s))";
            String report = header + "\n  " + String.join("\n  ", lines);
            Skycraft.LOGGER.info("[dev] {}", report.replace('\n', ' '));
            src.sendSuccess(() -> Component.literal(report), false);
            return 1;
        } catch (Exception e) {
            Skycraft.LOGGER.error("[dev] reload {} failed", target, e);
            src.sendFailure(Component.literal("Reload '" + target + "' failed: " + e));
            return 0;
        }
    }

    /**
     * Copies any modified files from {@code src/main/resources/skycraft} into {@code build/resources/main/skycraft}
     * so {@link Class#getResourceAsStream} sees on-disk edits without waiting for {@code gradlew processResources}.
     */
    private static int syncResources() throws IOException {
        Path modDir = findModDir();
        if (modDir == null) return 0;
        Path srcRoot = modDir.resolve("src/main/resources/skycraft");
        Path dstRoot = modDir.resolve("build/resources/main/skycraft");
        if (!Files.isDirectory(srcRoot) || !Files.isDirectory(dstRoot)) return 0;
        int copied = 0;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            for (Path s : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(s)) continue;
                Path rel = srcRoot.relativize(s);
                Path d = dstRoot.resolve(rel);
                boolean needsCopy = !Files.exists(d)
                        || Files.size(s) != Files.size(d)
                        || Files.getLastModifiedTime(s).toMillis() > Files.getLastModifiedTime(d).toMillis();
                if (needsCopy) {
                    Files.createDirectories(d.getParent());
                    Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        }
        return copied;
    }

    private static Path findModDir() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && cur != null; i++, cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("src/main/resources/skycraft"))) return cur;
            if (Files.isDirectory(cur.resolve("mod/src/main/resources/skycraft"))) return cur.resolve("mod");
        }
        return null;
    }

    private static String reloadCityPlans() {
        long t0 = System.nanoTime();
        Styles.reload();
        CityPlans.reload();
        int matched = 0;
        for (Place p : Places.all()) {
            if (CityPlans.of(p) != null) matched++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return "cityplans: " + CityPlans.all().size() + " authored plans (" + matched
                + " matched to places), style & overlay caches cleared (" + ms
                + " ms; note: baked StreetPlan geometry needs rebake/restart)";
    }

    private static String reloadQuests() throws Exception {
        long t0 = System.nanoTime();
        synchronized (StoryData.class) {
            for (String mapField : new String[]{"QUESTS", "NPCS", "RELICS", "SCENES", "SPEAKERS", "BY_LINE", "BOSSES"}) {
                Field f = StoryData.class.getDeclaredField(mapField);
                f.setAccessible(true);
                ((Map<?, ?>) f.get(null)).clear();
            }
            Field loaded = StoryData.class.getDeclaredField("loaded");
            loaded.setAccessible(true);
            loaded.setBoolean(null, false);
        }
        clearQuestPlaces();
        StoryData.ensureLoaded();
        int fallbacks = 0;
        for (String ignored : QuestPlaces.fallbackIds()) fallbacks++;
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return "quests: " + StoryData.quests().size() + " quests in " + StoryData.lines().size() + " lines, "
                + StoryData.npcs().size() + " NPCs, " + StoryData.relics().size() + " relics, "
                + StoryData.bosses().size() + " bosses, " + fallbacks + " quest places (" + ms + " ms)";
    }

    private static void clearQuestPlaces() throws Exception {
        synchronized (QuestPlaces.class) {
            Field fb = QuestPlaces.class.getDeclaredField("FALLBACK");
            fb.setAccessible(true);
            ((Map<?, ?>) fb.get(null)).clear();
            Field loaded = QuestPlaces.class.getDeclaredField("loaded");
            loaded.setAccessible(true);
            loaded.setBoolean(null, false);
        }
    }

    private static String reloadScenes() throws Exception {
        long t0 = System.nanoTime();
        Method load = ScatterRules.class.getDeclaredMethod("load");
        load.setAccessible(true);
        ScatterRules fresh = (ScatterRules) load.invoke(null);
        ScatterRules live = ScatterRules.get();
        synchronized (live) {
            live.kinds.clear();
            live.kinds.putAll(fresh.kinds);
            live.zones.clear();
            live.zones.putAll(fresh.zones);
            live.reach.clear();
            live.reach.putAll(fresh.reach);
            live.caps.chunk = fresh.caps.chunk;
            live.caps.perKind = fresh.caps.perKind;
            live.caps.attempts = fresh.caps.attempts;
            live.caps.isolated = fresh.caps.isolated;
        }
        ScenePlan replanned = ScenePlan.read(ScenePlanBuilder.computeLive());
        Class<?> holderCls = Class.forName("com.skycraft.worldplan.scene.ScenePlan$Holder");
        Field planField = holderCls.getDeclaredField("PLAN");
        setStaticFinal(planField, replanned);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return "scenes: " + live.kinds.size() + " scatter kinds (" + live.zones.size() + " zones), replanned "
                + replanned.scenes() + " scenes / " + replanned.props() + " props (" + ms + " ms)";
    }

    @SuppressWarnings("unchecked")
    private static String reloadPlaces() throws Exception {
        long t0 = System.nanoTime();
        Method readIndex = Places.class.getDeclaredMethod("readIndex");
        readIndex.setAccessible(true);
        Method read = Places.class.getDeclaredMethod("read", String.class);
        read.setAccessible(true);
        Method parse = Places.class.getDeclaredMethod("parse", JsonObject.class);
        parse.setAccessible(true);
        Method bucket = Places.class.getDeclaredMethod("bucket", int.class, int.class);
        bucket.setAccessible(true);

        List<String> files = (List<String>) readIndex.invoke(null);
        Map<String, Place> byId = new LinkedHashMap<>();
        for (String file : files) {
            JsonElement root = (JsonElement) read.invoke(null, "/skycraft/tamriel/places/" + file);
            if (root == null || !root.isJsonArray()) continue;
            for (JsonElement e : root.getAsJsonArray()) {
                Place p = (Place) parse.invoke(null, e.getAsJsonObject());
                byId.putIfAbsent(p.id(), p);
            }
        }
        Map<Long, List<Place>> byChunk = new HashMap<>();
        for (Place p : byId.values()) {
            byChunk.computeIfAbsent(ChunkPos.pack(p.x() >> 4, p.z() >> 4), k -> new ArrayList<>(1)).add(p);
        }
        byChunk.replaceAll((k, v) -> List.copyOf(v));
        Map<Long, List<Place>> byBucket = new HashMap<>();
        int maxRadius = 0;
        for (Place p : byId.values()) {
            long b = (long) bucket.invoke(null, p.x() >> 9, p.z() >> 9);
            byBucket.computeIfAbsent(b, k -> new ArrayList<>(4)).add(p);
            maxRadius = Math.max(maxRadius, p.radius());
        }
        byBucket.replaceAll((k, v) -> List.copyOf(v));

        setStaticFinal(Places.class.getDeclaredField("BY_ID"), Collections.unmodifiableMap(byId));
        setStaticFinal(Places.class.getDeclaredField("ALL"), List.copyOf(byId.values()));
        setStaticFinal(Places.class.getDeclaredField("BY_CHUNK"), byChunk);
        setStaticFinal(Places.class.getDeclaredField("BY_BUCKET"), byBucket);
        setStaticFinalInt(Places.class.getDeclaredField("MAX_RADIUS"), maxRadius);
        clearQuestPlaces();

        long ms = (System.nanoTime() - t0) / 1_000_000;
        return "places: " + byId.size() + " places across " + files.size() + " files, max radius " + maxRadius
                + " (" + ms + " ms; note: baked RoadPlan/StreetPlan anchors require rebake)";
    }

    private static String reloadPalettes(CommandSourceStack src) throws Exception {
        long t0 = System.nanoTime();
        Field rawField = Palettes.class.getDeclaredField("raw");
        rawField.setAccessible(true);
        rawField.set(null, null);
        JsonObject root = Palettes.raw();
        int paletteCount = root.has("palettes") ? root.getAsJsonObject("palettes").size() : 0;
        int catalogueCount = root.has("catalogue") ? root.getAsJsonArray("catalogue").size() : 0;

        boolean updatedLiveSource = false;
        if (src.getServer() != null) {
            ServerLevel overworld = src.getServer().overworld();
            if (overworld != null && overworld.getChunkSource().getGenerator().getBiomeSource() instanceof TamrielBiomeSource tbs) {
                Palettes.Compiled compiled = Palettes.compile(src.getServer().registryAccess().lookupOrThrow(Registries.BIOME));
                Field palettesField = TamrielBiomeSource.class.getDeclaredField("palettes");
                setInstanceFinal(tbs, palettesField, compiled);
                Field colsField = TamrielBiomeSource.class.getDeclaredField("COLUMNS");
                colsField.setAccessible(true);
                ((ThreadLocal<?>) colsField.get(null)).remove();
                updatedLiveSource = true;
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return "palettes: " + paletteCount + " palettes, " + catalogueCount + " catalogue biomes"
                + (updatedLiveSource ? ", live TamrielBiomeSource updated" : "")
                + " (" + ms + " ms; note: datapack biome/surface_rule JSONs require restart)";
    }

    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static void setStaticFinal(Field field, Object value) throws Exception {
        Unsafe u = unsafe();
        Object base = u.staticFieldBase(field);
        long offset = u.staticFieldOffset(field);
        u.putObjectVolatile(base, offset, value);
    }

    private static void setStaticFinalInt(Field field, int value) throws Exception {
        Unsafe u = unsafe();
        Object base = u.staticFieldBase(field);
        long offset = u.staticFieldOffset(field);
        u.putIntVolatile(base, offset, value);
    }

    private static void setInstanceFinal(Object target, Field field, Object value) throws Exception {
        Unsafe u = unsafe();
        long offset = u.objectFieldOffset(field);
        u.putObjectVolatile(target, offset, value);
    }
}
