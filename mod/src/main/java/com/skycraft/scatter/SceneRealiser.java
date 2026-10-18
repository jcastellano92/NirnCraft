package com.skycraft.scatter;

import com.skycraft.Skycraft;
import com.skycraft.explore.SiteKind;
import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.explore.MinorSites;
import com.skycraft.landmarks.LandCtx;
import com.skycraft.landmarks.LandmarkBuilder;
import com.skycraft.landmarks.LandmarkSite;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.lore.place.PlaceLore;
import com.skycraft.lore.place.StoryReader;
import com.skycraft.scatter.ScatterRules.Kind;
import com.skycraft.scatter.ZoneModel.Info;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.world.PlacementMask;
import com.skycraft.worldplan.RoadPlan;
import com.skycraft.worldplan.scene.ScenePlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Realises the planned scenes ({@link ScenePlan}) of the chunk being decorated (pipeline stage S11): every prop whose
 * centre lies in this chunk (a scatter kind through {@link Scatter#planned}, a landmark vignette through
 * {@link LandmarkBuilder}, the clue sign, the note barrel) and the columns of the scene's worn path inside it. Nothing
 * here rolls: what and where come from the plan; the randomness only picks a variant (seeded by the scene, the prop and
 * the world seed). Called by {@link ScatterFeature} before the natural scatter, so natural props keep off scenes.
 */
public final class SceneRealiser {
    private SceneRealiser() {}

    static long mix(long z) {
        return Scatter.mix(z);
    }

    /** Builds this chunk's share of every scene that reaches it. Returns the number of props built. */
    static int generate(WorldGenLevel level, ChunkPos cp) {
        if (ScatterConfig.density() <= 0) return 0;
        ScenePlan plan = ScenePlan.planFor(cp.getMiddleBlockX(), cp.getMiddleBlockZ());
        int[] ids = plan.inChunk(cp.x(), cp.z());
        if (ids.length == 0) return 0;
        int built = 0;
        for (int s : ids) {
            try {
                built += realise(level, cp, plan, s);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Skycraft scatter: scene {} ({}) failed in chunk {}", s, plan.kindOf(s), cp, e);
            }
        }
        return built;
    }

    private static int realise(WorldGenLevel level, ChunkPos cp, ScenePlan plan, int s) {
        long seed = mix(level.getSeed() ^ mix(s * 0x9E3779B97F4A7C15L + plan.x[s] * 31L + plan.z[s]));
        int built = 0, touched = 0;
        for (int p = plan.propOff[s]; p < plan.propOff[s + 1]; p++) {
            int px = plan.x[s] + plan.propDx[p], pz = plan.z[s] + plan.propDz[p];
            if (px >> 4 != cp.x() || pz >> 4 != cp.z()) continue;
            touched++;
            RandomSource rnd = RandomSource.create(mix(seed + p * 0xC2B2AE3D27D4EB4FL));
            String kind = plan.propKindOf(p);
            boolean ok;
            String why = "";
            if (kind.equals(ScenePlan.SIGN)) {
                ok = sign(level, cp, plan, s, px, pz, rnd);
                why = "sign";
            } else if (kind.equals(ScenePlan.NOTE)) {
                ok = note(level, cp, plan, s, px, pz, rnd);
                why = "note";
            } else if (kind.startsWith(ScenePlan.LANDMARK_PREFIX)) {
                ok = landmark(level, plan, s, kind.substring(ScenePlan.LANDMARK_PREFIX.length()), px, pz, seed + p);
                why = "landmark";
            } else {
                Kind k = ScatterRules.get().kinds.get(kind);
                ok = k != null && Scatter.planned(level, cp, k, px, pz, rnd);
                if (!ok) why = k == null ? "unknown" : Scatter.LAST_WHY;
            }
            ScatterStats.sceneProp(ok, why);
            if (ok) built++;
        }
        if (plan.pathX[s] != ScenePlan.NO_PATH) path(level, cp, plan, s);
        if (touched > 0) ScatterStats.scene();
        return built;
    }

    /** A builder helper centred on (x, z) with the culture's palette (ground lookups, safe writes, containers). */
    private static SiteBuilder builder(WorldGenLevel level, ChunkPos cp, int x, int z, int facing, RandomSource rnd) {
        Info info = ZoneModel.at(level.getSeed(), x, z);
        MinorSites.Site fake = new MinorSites.Site(cp.x(), cp.z(), x, z, SiteKind.ABANDONED_CAMP, rnd.nextLong(), facing & 3, null,
                info.regionId(), info.province());
        return new SiteBuilder(level, rnd, fake, Palette.of(info.province(), info.archetype()));
    }

    /** The ground column at (x, z) of a builder, or -1 when it is water, a road or a structure. */
    private static int dryGround(WorldGenLevel level, SiteBuilder b, int x, int z) {
        if (!PlacementMask.clearOfPlans(level, x, z, 0)) return -1;
        int g = b.groundAt(x, z);
        BlockPos col = new BlockPos(x, g, z);
        if (!level.getFluidState(col).isEmpty() || !level.getFluidState(col.above()).isEmpty()) return -1;
        BlockState up = level.getBlockState(col.above());
        if (!up.isAir() && !up.canBeReplaced()) return -1;
        return g;
    }

    // ------------------------------------------------------------------ the clue sign and note

    private static boolean sign(WorldGenLevel level, ChunkPos cp, ScenePlan plan, int s, int x, int z, RandomSource rnd) {
        SiteBuilder b = builder(level, cp, x, z, plan.facing[s], rnd);
        int g = dryGround(level, b, x, z);
        if (g < 0) return false;
        BlockPos p = new BlockPos(x, g + 1, z);
        Block signBlock = signFor(b.pal.planks());
        // the face looks towards the anchor (the road or the place), where the reader stands
        int rotation = (plan.facing[s] & 3) * 4;
        level.setBlock(p, signBlock.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), 2);
        BlockEntity be = level.getBlockEntity(p);
        if (!(be instanceof SignBlockEntity sign)) return true;
        Component[] lines = lines(plan, s, b, rnd);
        SignText front = SignText.EMPTY, back = SignText.EMPTY;
        for (int i = 0; i < 4; i++) front = front.asMutable().setLine(i, lines[i]).asImmutable();
        for (int i = 0; i < 4; i++) back = back.asMutable().setLine(i, lines[4 + i]).asImmutable();
        CompoundTag tag = sign.saveWithoutMetadata(level.registryAccess());
        SignText.CODEC.encodeStart(NbtOps.INSTANCE, front).result().ifPresent(t -> tag.put("front_text", t));
        SignText.CODEC.encodeStart(NbtOps.INSTANCE, back).result().ifPresent(t -> tag.put("back_text", t));
        tag.putBoolean("is_waxed", true);
        com.skycraft.worldgen.WorldgenData.load(sign, tag, level.registryAccess());
        // right-click / [F] reads the whole clue in the reading screen (the sign's own lines are small and split in two)
        StringBuilder text = new StringBuilder();
        boolean prose = !plan.clue[s].startsWith("@");
        for (Component line : lines) {
            String t = line.getString();
            if (t.isBlank()) continue;
            if (text.length() > 0) text.append(prose ? ' ' : '\n');
            text.append(t);
        }
        if (text.length() > 0) {
            String clue = plan.clue[s];
            String title = clue.equals("@grave") ? "A Grave Marker" : clue.equals("@warning") ? "A Warning" : clue.startsWith("@roads:") ? "A Signpost"
                    : "A Notice";
            StoryReader.mark(sign, title, "", List.of(text.toString()));
        }
        return true;
    }

    /** Eight sign lines (front then back) for the scene's clue. */
    private static Component[] lines(ScenePlan plan, int s, SiteBuilder b, RandomSource rnd) {
        Component[] out = new Component[8];
        for (int i = 0; i < 8; i++) out[i] = Component.empty();
        String clue = plan.clue[s];
        String province = b.site.province();
        if (clue.equals("@grave")) {
            String[] g = com.skycraft.landmarks.Lore.graveSign(province, rnd);
            for (int i = 0; i < Math.min(4, g.length); i++) out[i] = Component.literal(g[i] == null ? "" : g[i]);
        } else if (clue.equals("@warning")) {
            String[] w = com.skycraft.landmarks.Lore.wrap(com.skycraft.landmarks.Lore.warning(rnd));
            for (int i = 0; i < 4; i++) out[i] = Component.literal(w[i]);
        } else if (clue.startsWith("@roads:")) {
            String[] ids = clue.substring(7).split(",");
            int line = 0;
            for (String id : ids) {
                Optional<Place> place = Places.byId(id);
                if (place.isEmpty() || line >= 4) continue;
                out[line++] = Component.literal(line == 1 ? "→ " : "← ").append(place.get().name());
            }
        } else {
            List<String> wrapped = wrap(clue, 15, 8);
            for (int i = 0; i < wrapped.size(); i++) out[i] = Component.literal(wrapped.get(i));
        }
        return out;
    }

    /** Word-wraps {@code text} onto at most {@code max} lines of {@code width} characters. */
    static List<String> wrap(String text, int width, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.trim().split("\\s+")) {
            if (w.length() > width) w = w.substring(0, width);
            if (line.length() > 0 && line.length() + 1 + w.length() > width) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() >= max) return out;
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0 && out.size() < max) out.add(line.toString());
        return out;
    }

    private static Block signFor(Block planks) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(planks);
        if (id != null && id.getPath().endsWith("_planks")) {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath().replace("_planks", "_sign")));
            if (b instanceof StandingSignBlock) return b;
        }
        return Blocks.SPRUCE_SIGN;
    }

    /** A barrel beside the sign holding the place's lore note ({@code skycraft:book}, the place-lore readable). */
    private static boolean note(WorldGenLevel level, ChunkPos cp, ScenePlan plan, int s, int x, int z, RandomSource rnd) {
        LoreBooks.Book book = PlaceLore.book(plan.note[s]);
        if (book == null) return false;
        SiteBuilder b = builder(level, cp, x, z, plan.facing[s], rnd);
        int g = dryGround(level, b, x, z);
        if (g < 0) return false;
        BlockPos p = new BlockPos(x, g + 1, z);
        level.setBlock(p, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP), 2);
        ItemStack stack = LoreBookItem.create(book);
        b.addItem(p, stack);
        // right-click / [F] reads the note in the reading screen (sneaking opens the barrel)
        BlockEntity be = level.getBlockEntity(p);
        if (be != null) StoryReader.markNoteContainer(be);
        return true;
    }

    // ------------------------------------------------------------------ landmark vignettes

    private static boolean landmark(WorldGenLevel level, ScenePlan plan, int s, String id, int x, int z, long seed) {
        com.skycraft.landmarks.Kind k = com.skycraft.landmarks.Kinds.byId(id);
        if (k == null) return false;
        LandCtx ctx = LandCtx.of(x, z);
        // the scene at a trail's end stands on the very pad the plan flattened for the place, so it is pinned (it may
        // overlap the planned pad) and forced (the trail ends here: it is built even on a steep or wet footing)
        boolean trailEnd = plan.anchor[s] == ScenePlan.TRAIL_END;
        LandmarkSite site = new LandmarkSite(k.tier(), x >> 4, z >> 4, x, z, k, mix(seed), plan.facing[s] & 3, ctx.region.id(),
                ctx.province, ctx.base, "", trailEnd ? "trail_end" : null);
        return LandmarkBuilder.build(level, site, trailEnd, null);
    }

    // ------------------------------------------------------------------ the worn path

    /**
     * The columns of the scene's path (from its footprint's edge to the road's edge) that lie in this chunk become
     * dirt path where the ground is soft. Reads and writes only this chunk's columns.
     */
    private static void path(WorldGenLevel level, ChunkPos cp, ScenePlan plan, int s) {
        double x0 = plan.x[s] + 0.5, z0 = plan.z[s] + 0.5, x1 = plan.pathX[s] + 0.5, z1 = plan.pathZ[s] + 0.5;
        double len = Math.hypot(x1 - x0, z1 - z0);
        if (len < 2) return;
        double ux = (x1 - x0) / len, uz = (z1 - z0) / len;
        // outside the footprint, so no prop (built in whatever chunk order) ever stands beside a fresh path block
        int start = plan.radius[s] + 1;
        BlockState pathState = Blocks.DIRT_PATH.defaultBlockState();
        RoadPlan.Hit hit = new RoadPlan.Hit();
        RoadPlan roads = RoadPlan.get();
        SiteBuilder b = null;
        for (int i = start; i <= len; i++) {
            int px = (int) Math.floor(x0 + ux * i), pz = (int) Math.floor(z0 + uz * i);
            if (px >> 4 != cp.x() || pz >> 4 != cp.z()) continue;
            if (roads.at(px + 0.5, pz + 0.5, hit) && hit.distance <= hit.halfWidth + 0.5f) break; // the road itself
            if (b == null) b = builder(level, cp, px, pz, plan.facing[s], RandomSource.create(s));
            int g = b.groundAt(px, pz);
            BlockPos p = new BlockPos(px, g, pz);
            BlockState gs = level.getBlockState(p);
            if (!gs.is(Blocks.GRASS_BLOCK) && !gs.is(Blocks.DIRT) && !gs.is(Blocks.COARSE_DIRT) && !gs.is(Blocks.PODZOL)) continue;
            BlockState up = level.getBlockState(p.above());
            if (!up.isAir() && !up.canBeReplaced()) continue;
            if (!up.isAir() && up.getFluidState().isEmpty()) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(p, pathState, 2);
        }
    }
}
