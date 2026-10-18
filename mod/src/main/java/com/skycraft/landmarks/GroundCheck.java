package com.skycraft.landmarks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.explore.MinorSites;
import com.skycraft.worldplan.scene.ScenePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The floating-landmark census ({@code /skycraft-landmarks groundcheck <x> <z> <half>}): every landmark site, minor
 * site and scene prop of the area is scanned with {@link Grounding#scan} in the finished chunks, and parts of solid
 * blocks with no way down to the terrain are counted. A control sample of plain terrain (no site near) measures the
 * natural rate so the numbers can be read against it. Writes {@code skycraft-landmarks/groundcheck.json}.
 */
public final class GroundCheck {
    private GroundCheck() {}

    /** A part this big or bigger counts as a floating site (smaller ones are stray blocks). */
    private static final int SIGNIFICANT = 3;

    private static final class Tally {
        int sites, built, floating, strayOnly;
        long blocks;
        int worstGap;
    }

    public static int run(ServerLevel level, int cx, int cz, int half, Consumer<String> say) {
        long seed = level.getSeed();
        int x0 = cx - half, z0 = cz - half, x1 = cx + half, z1 = cz + half;
        Map<String, Tally> tally = new TreeMap<>();
        JsonArray bad = new JsonArray();

        // landmarks of the grid
        for (LandmarkSite s : Landmarks.inRect(seed, x0, z0, x1, z1)) {
            load(level, s.x() >> 4, s.z() >> 4);
            boolean built = LandmarkEvents.built(level, s);
            record(level, tally, bad, "landmark." + s.tier().id, s.kind().id(), s.x(), s.z(), s.kind().radius() + 3, built);
        }
        // minor sites of the explore module
        int sx0 = Math.floorDiv(x0, MinorSites.CELL), sx1 = Math.floorDiv(x1, MinorSites.CELL);
        int sz0 = Math.floorDiv(z0, MinorSites.CELL), sz1 = Math.floorDiv(z1, MinorSites.CELL);
        for (int ccx = sx0; ccx <= sx1; ccx++) {
            for (int ccz = sz0; ccz <= sz1; ccz++) {
                MinorSites.Site m = MinorSites.at(seed, ccx, ccz);
                if (m == null || m.x() < x0 || m.x() > x1 || m.z() < z0 || m.z() > z1) continue;
                load(level, m.x() >> 4, m.z() >> 4);
                record(level, tally, bad, "minor", m.kind().name().toLowerCase(Locale.ROOT), m.x(), m.z(), m.kind().radius + 3, true);
            }
        }
        // scene props
        ScenePlan plan = ScenePlan.get();
        for (int s = 0; s < plan.scenes(); s++) {
            if (plan.x[s] < x0 - 40 || plan.x[s] > x1 + 40 || plan.z[s] < z0 - 40 || plan.z[s] > z1 + 40) continue;
            for (int p = plan.propOff[s]; p < plan.propOff[s + 1]; p++) {
                int px = plan.x[s] + plan.propDx[p], pz = plan.z[s] + plan.propDz[p];
                if (px < x0 || px > x1 || pz < z0 || pz > z1) continue;
                String kind = plan.propKindOf(p);
                String cat;
                int r;
                if (kind.startsWith(ScenePlan.LANDMARK_PREFIX)) {
                    cat = "scene.landmark";
                    com.skycraft.landmarks.Kind k = Kinds.byId(kind.substring(ScenePlan.LANDMARK_PREFIX.length()));
                    r = (k == null ? 5 : k.radius()) + 3;
                } else if (kind.equals(ScenePlan.SIGN) || kind.equals(ScenePlan.NOTE)) {
                    cat = "scene.clue";
                    r = 3;
                } else {
                    cat = "scene.prop";
                    r = 5;
                }
                load(level, px >> 4, pz >> 4);
                record(level, tally, bad, cat, kind, px, pz, r, true);
            }
        }
        // control: plain terrain with no site of any kind near it
        int controls = 0;
        long h = H.mix(seed ^ 0xC0FFEEL ^ ((long) cx * 31 + cz));
        // scaled to the area: the census of one tile is small so the loaded chunks can unload between calls
        int want = Math.max(6, Math.min(120, (int) (half * (double) half * 4.0 / 1.0e6 * 14)));
        for (int i = 0; i < want * 8 && controls < want; i++) {
            h = H.mix(h + i * 0x9E3779B97F4A7C15L);
            int px = x0 + 24 + (int) ((h >>> 8) % Math.max(1, 2 * half - 48));
            int pz = z0 + 24 + (int) ((h >>> 36) % Math.max(1, 2 * half - 48));
            if (!Landmarks.within(seed, px, pz, 60).isEmpty()) continue;
            if (!MinorSites.within(seed, px, pz, 50).isEmpty()) continue;
            if (plan.nearest(px, pz, 40) >= 0) continue;
            load(level, px >> 4, pz >> 4);
            if (level.getFluidState(new BlockPos(px, level.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz) - 1, pz)).isEmpty()) {
                record(level, tally, bad, "control", "terrain", px, pz, 8, true);
                controls++;
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("x", cx);
        root.addProperty("z", cz);
        root.addProperty("half", half);
        root.addProperty("grounding", Grounding.enabled);
        JsonObject cats = new JsonObject();
        say.accept(String.format(Locale.ROOT, "groundcheck %d,%d +-%d (grounding %s): category sites built floating(>=%d blocks) stray-only blocks worst-gap",
                cx, cz, half, Grounding.enabled ? "ON" : "OFF", SIGNIFICANT));
        for (var e : tally.entrySet()) {
            Tally t = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("sites", t.sites);
            o.addProperty("built", t.built);
            o.addProperty("floating", t.floating);
            o.addProperty("stray", t.strayOnly);
            o.addProperty("blocks", t.blocks);
            o.addProperty("worstGap", t.worstGap);
            cats.add(e.getKey(), o);
            say.accept(String.format(Locale.ROOT, "  %-18s %5d %5d %5d (%4.1f%%) %4d %7d %s", e.getKey(), t.sites, t.built, t.floating,
                    t.built == 0 ? 0.0 : 100.0 * t.floating / t.built, t.strayOnly, t.blocks,
                    t.worstGap >= Grounding.NO_GROUND ? "none" : Integer.toString(t.worstGap)));
        }
        root.add("categories", cats);
        root.add("floatingSites", bad);
        root.addProperty("counters", Grounding.counters());
        say.accept("  counters since boot: " + Grounding.counters());
        try {
            Path dir = level.getServer().getServerDirectory().resolve("skycraft-landmarks");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("groundcheck.json"), root.toString(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            Skycraft.LOGGER.warn("Skycraft landmarks: could not write the ground check", e);
        }
        int floating = 0;
        for (var e : tally.entrySet()) if (!e.getKey().equals("control")) floating += e.getValue().floating;
        return floating;
    }

    private static void load(ServerLevel level, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(cx + dx, cz + dz);
    }

    private static void record(ServerLevel level, Map<String, Tally> tally, JsonArray bad, String cat, String kind, int x, int z,
                               int r, boolean built) {
        Tally t = tally.computeIfAbsent(cat, k -> new Tally());
        t.sites++;
        if (!built) return;
        t.built++;
        int cxk = x >> 4, czk = z >> 4;
        int bx0 = Math.max(x - r, (cxk - 1) << 4), bx1 = Math.min(x + r, ((cxk + 1) << 4) + 15);
        int bz0 = Math.max(z - r, (czk - 1) << 4), bz1 = Math.min(z + r, ((czk + 1) << 4) + 15);
        List<Grounding.Part> parts = Grounding.scan(level, bx0, bz0, bx1, bz1, Heightmap.Types.WORLD_SURFACE, 1);
        int big = 0, blocks = 0, gap = 0;
        for (Grounding.Part p : parts) {
            if (p.size() >= SIGNIFICANT) {
                big++;
                blocks += p.size();
                gap = Math.max(gap, p.maxGap());
            }
        }
        if (big > 0) {
            t.floating++;
            t.blocks += blocks;
            t.worstGap = Math.max(t.worstGap, gap);
            JsonObject o = new JsonObject();
            o.addProperty("category", cat);
            o.addProperty("kind", kind);
            o.addProperty("x", x);
            o.addProperty("z", z);
            o.addProperty("parts", big);
            o.addProperty("blocks", blocks);
            o.addProperty("gap", gap >= Grounding.NO_GROUND ? -1 : gap);
            bad.add(o);
        } else if (!parts.isEmpty()) {
            t.strayOnly++;
        }
    }

    /**
     * Builds every kind of a tier in a grid on the real terrain (forced: no placement rule refuses a spot) and scans
     * each build: the per-kind check of the grounding, independent of how few sites a world has. Returns the number of
     * kinds with a floating part. Regenerate the area first when comparing grounding on and off (the foundations of a
     * run stay in the terrain).
     */
    public static int showcase(ServerLevel level, Tier tier, int x0, int z0, Consumer<String> say) {
        List<Kind> kinds = new ArrayList<>(Kinds.of(tier));
        int cols = (int) Math.ceil(Math.sqrt(kinds.size()));
        int step = tier == Tier.S ? 48 : 64;
        int built = 0, floating = 0, parts = 0, blocks = 0;
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            Kind k = kinds.get(i);
            int x = x0 + (i % cols) * step, z = z0 + (i / cols) * step;
            LandmarkSite site = Landmarks.custom(level.getSeed(), k, x, z, i + 1);
            load(level, site.chunkX(), site.chunkZ());
            boolean ok;
            try {
                ok = LandmarkBuilder.build(level, site, true, null);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft landmarks showcheck: {} threw", k.id(), e);
                continue;
            }
            if (!ok) continue;
            built++;
            int r = k.radius() + 3;
            int cxk = site.x() >> 4, czk = site.z() >> 4;
            List<Grounding.Part> found = Grounding.scan(level, Math.max(site.x() - r, (cxk - 1) << 4), Math.max(site.z() - r, (czk - 1) << 4),
                    Math.min(site.x() + r, ((cxk + 1) << 4) + 15), Math.min(site.z() + r, ((czk + 1) << 4) + 15),
                    Heightmap.Types.WORLD_SURFACE, 1);
            int b = 0, g = 0;
            String what = "";
            int most = 0;
            for (Grounding.Part p : found) {
                if (p.size() >= SIGNIFICANT) {
                    b += p.size();
                    g = Math.max(g, p.maxGap());
                    parts++;
                    if (p.size() > most) {
                        // the lowest block of the biggest part, to tell what hovers
                        most = p.size();
                        BlockPos lowest = BlockPos.of(p.feet.getLong(0));
                        what = " " + level.getBlockState(lowest).getBlock().builtInRegistryHolder().key().identifier().getPath()
                                + "@" + (lowest.getX() - site.x()) + "," + lowest.getY() + "," + (lowest.getZ() - site.z());
                    }
                }
            }
            if (b > 0) {
                floating++;
                blocks += b;
                bad.add(String.format(Locale.ROOT, "%s(%d blocks, gap %s,%s)", k.id(), b, g >= Grounding.NO_GROUND ? "none" : Integer.toString(g), what));
            }
        }
        say.accept(String.format(Locale.ROOT, "showcheck %s (grounding %s): %d kinds, %d built, %d floating (%d parts, %d blocks)%s", tier.id,
                Grounding.enabled ? "ON" : "OFF", kinds.size(), built, floating, parts, blocks, bad.isEmpty() ? "" : ": " + bad));
        return floating;
    }

    /** All the kinds of a category that floated, for the report. */
    static List<String> kindsOf(JsonArray bad) {
        List<String> out = new ArrayList<>();
        for (var e : bad) out.add(e.getAsJsonObject().get("kind").getAsString());
        return out;
    }
}
