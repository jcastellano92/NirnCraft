package com.skycraft.landmarks.build;

import com.skycraft.explore.build.Palette;
import com.skycraft.explore.build.SiteBuilder;
import com.skycraft.landmarks.H;
import com.skycraft.landmarks.Kind;
import com.skycraft.landmarks.LandCtx;
import com.skycraft.landmarks.LandmarkSite;
import com.skycraft.landmarks.Lore;
import com.skycraft.landmarks.Mats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

/**
 * Everything a landmark builder paints with: local coordinates over the explore module's {@link SiteBuilder}
 * (rotated with the site, writes limited to the 3x3 chunks of the feature), the site's seeded parameters (size,
 * weathering, overgrowth, rock and wood), shaping helpers (noise blobs, tapered towers, arches) and props (signs,
 * lecterns, books, item frames and stands set through NBT so a worldgen thread never deadlocks).
 */
public final class Stage {
    public final WorldGenLevel level;
    public final SiteBuilder b;
    public final LandmarkSite site;
    public final Kind kind;
    public final RandomSource r;
    public final long seed;
    public final Mats.Rock rock;
    public final Mats.Wood wood;
    public final String fam;
    public final List<String> families;
    /** Footprint radius the builder may use. */
    public final int rad;
    /** 0.75..1.3: scales the size of formations. */
    public final double size;
    /** 0..1: how worn the stone is (moss, cracks, rubble). */
    public final double wear;
    /** 0..1: how overgrown (moss and vines, snow cover, sand drift by family). */
    public final double grow;
    public final boolean snowy, sandy, lush, ashy, wet;
    /** True when the local style asks for dressed (cut) masonry: Imperial, Altmer, Redguard. */
    public final boolean cutStone;

    public Stage(WorldGenLevel level, SiteBuilder b, LandmarkSite site, LandCtx ctx) {
        this.level = level;
        this.b = b;
        this.site = site;
        this.kind = site.kind();
        this.r = b.r;
        this.seed = site.seed();
        this.families = ctx.families();
        this.fam = ctx.base;
        this.rock = pickRock();
        this.wood = Mats.woodFor(families, H.mix(seed ^ 0x77L));
        this.rad = kind.radius();
        this.size = H.between(seed, 11, 0.75, 1.3);
        this.wear = H.between(seed, 12, 0.0, 1.0);
        this.grow = H.between(seed, 13, 0.0, 1.0);
        BlockState ground = level.getBlockState(new BlockPos(site.x(), b.y - 1, site.z()));
        boolean snow = fam.equals("tundra") || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.SNOW) || ground.is(Blocks.POWDER_SNOW);
        this.snowy = snow;
        this.sandy = ground.is(BlockTags.SAND) || fam.equals("desert") || ground.is(Blocks.SANDSTONE);
        this.lush = fam.equals("forest") || fam.equals("plains") || fam.equals("jungle") || fam.equals("marsh") || fam.equals("mushroom");
        this.ashy = fam.equals("ash") || fam.equals("volcanic");
        this.wet = fam.equals("marsh") || fam.equals("jungle") || ground.is(Blocks.MUD);
        String prov = site.province();
        this.cutStone = prov.equals("cyrodiil") || prov.equals("summerset") || prov.equals("hammerfell") || prov.equals("high_rock");
    }

    private Mats.Rock pickRock() {
        String forced = kind.params().has("rock") ? kind.params().get("rock").getAsString() : "auto";
        if (!forced.equals("auto")) return Mats.rock(forced);
        return Mats.rockFor(families, H.mix(seed ^ 0x51L));
    }

    // ------------------------------------------------------------------ parameters

    /** Seeded integer in [lo, hi] for the named variation. */
    public int vary(int salt, int lo, int hi) {
        return lo + H.range(seed, 20 + salt, hi - lo + 1);
    }

    public double varyD(int salt, double lo, double hi) {
        return H.between(seed, 20 + salt, lo, hi);
    }

    public boolean chance(int salt, double p) {
        return H.unit(seed, 40 + salt) < p;
    }

    public String param(String key, String fallback) {
        return kind.params().has(key) ? kind.params().get(key).getAsString() : fallback;
    }

    public int paramInt(String key, int fallback) {
        return kind.params().has(key) ? kind.params().get(key).getAsInt() : fallback;
    }

    // ------------------------------------------------------------------ terrain

    /** Relative height of the first free block above the ground at local (dx, dz) (0 = the site's base level). */
    public int top(int dx, int dz) {
        return b.ground(dx, dz) + 1 - b.y;
    }

    public boolean within(int dx, int dz) {
        return dx * dx + dz * dz <= rad * rad;
    }

    public boolean dry(int dx, int dz) {
        return !b.waterAt(dx, dz);
    }

    // ------------------------------------------------------------------ noise

    private double hash01(int x, int y, int z, int salt) {
        long h = H.mix(seed + x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L + salt * 0xD6E8FEB86659FD93L);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth value noise in [0, 1) at a world-fixed lattice of spacing {@code scale} blocks. */
    public double noise(double x, double y, double z, double scale, int salt) {
        double fx = x / scale, fy = y / scale, fz = z / scale;
        int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), ty = smooth(fy - iy), tz = smooth(fz - iz);
        double c000 = hash01(ix, iy, iz, salt), c100 = hash01(ix + 1, iy, iz, salt);
        double c010 = hash01(ix, iy + 1, iz, salt), c110 = hash01(ix + 1, iy + 1, iz, salt);
        double c001 = hash01(ix, iy, iz + 1, salt), c101 = hash01(ix + 1, iy, iz + 1, salt);
        double c011 = hash01(ix, iy + 1, iz + 1, salt), c111 = hash01(ix + 1, iy + 1, iz + 1, salt);
        double x00 = c000 + (c100 - c000) * tx, x10 = c010 + (c110 - c010) * tx;
        double x01 = c001 + (c101 - c001) * tx, x11 = c011 + (c111 - c011) * tx;
        double y0 = x00 + (x10 - x00) * ty, y1 = x01 + (x11 - x01) * ty;
        return y0 + (y1 - y0) * tz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Noise in the site's local frame (so shapes do not depend on the world position being aligned). */
    public double n(int dx, int dy, int dz, double scale, int salt) {
        return noise(dx + 0.5, dy + 0.5, dz + 0.5, scale, salt);
    }

    // ------------------------------------------------------------------ blocks

    public void set(int dx, int dy, int dz, BlockState s) {
        b.set(dx, dy, dz, s);
    }

    public void set(int dx, int dy, int dz, Block block) {
        b.set(dx, dy, dz, block.defaultBlockState());
    }

    public void air(int dx, int dy, int dz) {
        b.set(dx, dy, dz, Blocks.AIR.defaultBlockState());
    }

    /** The block at local (dx, dy, dz) is air or a replaceable plant. */
    public boolean free(int dx, int dy, int dz) {
        return b.free(dx, dy, dz);
    }

    public BlockState get(int dx, int dy, int dz) {
        return level.getBlockState(b.at(dx, dy, dz));
    }

    /** A weathered rock block for the position: stratified when the set has bands, with moss and cracks by wear. */
    public BlockState rk(int dx, int dy, int dz) {
        long h = H.mix(seed + dx * 0x9E3779B97F4A7C15L + dy * 0xC2B2AE3D27D4EB4FL + dz * 0x165667B19E3779F9L);
        double u = (h >>> 11) * 0x1.0p-53;
        Mats.Rock k = rock;
        if (!k.band().empty() && u > 0.12) {
            // strata: each layer is a band; the offset varies per site so no two mesas share the same stripes
            int layer = Math.floorDiv(dy + (int) (n(dx, 0, dz, 24, 3) * 3) + vary(30, 0, 40), 2);
            return k.band().state(H.mix(seed ^ layer * 0x9E3779B97F4A7C15L) >>> 3);
        }
        if (u < wear * 0.22 && lush) return k.moss().state(h >>> 7);
        if (u < 0.16 + wear * 0.06) return k.alt().state(h >>> 7);
        return k.main().state(h >>> 7);
    }

    public void rock(int dx, int dy, int dz) {
        set(dx, dy, dz, rk(dx, dy, dz));
    }

    /** A rock block placed only where free (does not overwrite terrain). */
    public void rockFree(int dx, int dy, int dz) {
        if (free(dx, dy, dz)) rock(dx, dy, dz);
    }

    public BlockState rubble(int dx, int dy, int dz) {
        long h = H.mix(seed + dx * 31L + dy * 17L + dz * 7L);
        return rock.rubble().state(h >>> 5);
    }

    /** Fills a column of rock from {@code y0} to {@code y1} (inclusive), replacing anything. */
    public void column(int dx, int dz, int y0, int y1) {
        for (int y = y0; y <= y1; y++) rock(dx, y, dz);
    }

    /** Rock from the ground at (dx, dz) up to {@code y1} (relative), so formations sit on the real terrain. */
    public void columnFromGround(int dx, int dz, int y1) {
        int g = top(dx, dz) - 1;
        // dig out the plants above ground first
        for (int y = g + 1; y <= y1; y++) rock(dx, y, dz);
        // a short skirt below the ground so it never floats over a hollow
        for (int y = g; y >= g - 2; y--) {
            if (!solidAt(dx, y, dz)) rock(dx, y, dz);
        }
    }

    public boolean solidAt(int dx, int dy, int dz) {
        BlockPos p = b.at(dx, dy, dz);
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /** An ellipsoid blob of rock with noisy surface. {@code rough} 0 = smooth, 0.5 = very craggy. */
    public void blob(double cx, double cy, double cz, double rx, double ry, double rz, double rough, int salt) {
        int x0 = (int) Math.floor(cx - rx - 1), x1 = (int) Math.ceil(cx + rx + 1);
        int y0 = (int) Math.floor(cy - ry - 1), y1 = (int) Math.ceil(cy + ry + 1);
        int z0 = (int) Math.floor(cz - rz - 1), z1 = (int) Math.ceil(cz + rz + 1);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!within(x, z)) continue;
                for (int y = y0; y <= y1; y++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 1.0 + rough) continue;
                    double nz = (n(x, y, z, 3.5, salt) - 0.5) * 2 * rough + (n(x, y, z, 7, salt + 1) - 0.5) * rough;
                    if (d + nz > 1.0) continue;
                    rock(x, y, z);
                }
            }
        }
    }

    /** A tapered, noisy tower: radius {@code r0} at {@code y0} to {@code r1} at {@code y1}. */
    public void tower(double cx, double cz, int y0, int y1, double r0, double r1, double rough, int salt) {
        int maxR = (int) Math.ceil(Math.max(r0, r1) + 2);
        for (int y = y0; y <= y1; y++) {
            double t = y1 == y0 ? 0 : (double) (y - y0) / (y1 - y0);
            double rr = r0 + (r1 - r0) * t;
            for (int dx = -maxR; dx <= maxR; dx++) {
                for (int dz = -maxR; dz <= maxR; dz++) {
                    int x = (int) Math.round(cx) + dx, z = (int) Math.round(cz) + dz;
                    if (!within(x, z)) continue;
                    double d = Math.hypot(x - cx, z - cz);
                    double lim = rr + (n(x, y, z, 4.0, salt) - 0.5) * 2 * rough;
                    if (d <= lim) rock(x, y, z);
                }
            }
        }
    }

    /** Support under a floating part: rock from (dx, dy-1) down to the ground. */
    public void support(int dx, int dy, int dz) {
        for (int y = dy - 1; y > top(dx, dz) - 3 && y > dy - 40; y--) {
            if (solidAt(dx, y, dz)) break;
            rock(dx, y, dz);
        }
    }

    // ------------------------------------------------------------------ dressing

    /** Dresses the free block at (dx, dy, dz) for the family: snow, moss, ferns, vines, dead brush. */
    public void dress(int dx, int dy, int dz) {
        if (!free(dx, dy, dz)) return;
        double u = H.unit(seed ^ (dx * 73856093L) ^ (dy * 19349663L) ^ (dz * 83492791L), 1);
        if (snowy) {
            if (u < 0.35 + grow * 0.5) set(dx, dy, dz, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + (int) (u * 7) % 3));
        } else if (wet) {
            if (u < 0.25 * grow + 0.05) set(dx, dy, dz, Blocks.MOSS_CARPET);
            else if (u < 0.30 + grow * 0.2) set(dx, dy, dz, Blocks.FERN);
        } else if (lush) {
            if (u < 0.2 * grow + 0.05) set(dx, dy, dz, Blocks.MOSS_CARPET);
            else if (u < 0.3 + grow * 0.15) set(dx, dy, dz, u < 0.37 ? Blocks.FERN : Blocks.GRASS);
        } else if (sandy || fam.equals("savanna")) {
            if (u < 0.08) set(dx, dy, dz, Blocks.DEAD_BUSH);
        }
    }

    /** Vines on the open sides of the block at (dx, dy, dz), where there is a free neighbour. */
    public void vines(int dx, int dy, int dz, int length) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (r.nextInt(3) != 0) continue;
            int sx = dx + d.getStepX(), sz = dz + d.getStepZ();
            for (int i = 0; i < length; i++) {
                int y = dy - i;
                if (!free(sx, y, sz)) break;
                // the vine's face points at the block it clings to; set() rotates the local state with the site
                BlockState v = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true);
                set(sx, y, sz, v);
            }
        }
    }

    /** Scatters rubble on the ground within {@code radius}: {@code count} blocks. */
    public void rubbleRing(int cx, int cz, int radius, int count) {
        for (int i = 0; i < count; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * radius;
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            if (!within(x, z) || !dry(x, z)) continue;
            int y = top(x, z);
            if (free(x, y, z)) set(x, y, z, rubble(x, y, z));
        }
    }

    // ------------------------------------------------------------------ props

    /** A standing sign at (dx, dy, dz) with 4 lines, facing the 16-step rotation in the site's frame. */
    public void standingSign(int dx, int dy, int dz, int rotation, String... lines) {
        BlockPos p = b.at(dx, dy, dz);
        if (!b.writable(p)) return;
        set(dx, dy, dz, wood.sign().defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation & 15));
        writeSign(p, lines);
    }

    /** A wall sign attached to the block behind it; {@code face} is the local direction it looks towards. */
    public void wallSign(int dx, int dy, int dz, Direction face, String... lines) {
        BlockPos p = b.at(dx, dy, dz);
        if (!b.writable(p)) return;
        set(dx, dy, dz, wood.wallSign().defaultBlockState().setValue(WallSignBlock.FACING, face));
        writeSign(p, lines);
    }

    private void writeSign(BlockPos p, String[] lines) {
        BlockEntity be = level.getBlockEntity(p);
        if (!(be instanceof SignBlockEntity sign)) return;
        SignText text = new SignText();
        for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i] == null ? "" : lines[i]));
        CompoundTag tag = sign.saveWithoutMetadata();
        SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE, text).result().ifPresent(t -> tag.put("front_text", t));
        tag.putBoolean("is_waxed", true);
        sign.load(tag);
    }

    /** A lectern holding {@code book}, facing the local direction. */
    public void lectern(int dx, int dy, int dz, Direction face, ItemStack book) {
        BlockPos p = b.at(dx, dy, dz);
        if (!b.writable(p)) return;
        set(dx, dy, dz, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, face).setValue(LecternBlock.HAS_BOOK, true));
        if (level.getBlockEntity(p) instanceof LecternBlockEntity le) le.setBook(book);
    }

    /** A chest with a loot table (a path under {@code loot_tables/}). */
    public BlockPos chest(int dx, int dy, int dz, Direction face, String table) {
        return b.chest(dx, dy, dz, face, new ResourceLocation("skycraft", table));
    }

    /** A small loot table of the explore module for a pack, a cache or a camp (a path under loot_tables/). */
    public String lootSmall(int salt) {
        String[] t = {"explore/camp", "explore/adventurer", "explore/hunter", "explore/cairn", "explore/offerings"};
        return t[vary(60 + salt, 0, t.length - 1)];
    }

    public BlockPos barrel(int dx, int dy, int dz, String table) {
        return b.barrel(dx, dy, dz, table == null ? null : new ResourceLocation("skycraft", table));
    }

    /** Adds a stack to the container at a local position (a note into a barrel...). */
    public void put(BlockPos container, ItemStack stack) {
        b.addItem(container, stack);
    }

    public void lying(int dx, int dy, int dz, ItemStack stack) {
        b.lying(dx, dy, dz, stack);
    }

    public void lantern(int dx, int dy, int dz, boolean soul) {
        set(dx, dy, dz, soul ? Blocks.SOUL_LANTERN.defaultBlockState() : Blocks.LANTERN.defaultBlockState());
    }

    public Palette culture() {
        return b.pal;
    }

    public void setLootOn(BlockPos p, String table) {
        if (b.writable(p)) {
            RandomizableContainerBlockEntity.setLootTable(level, r, p, new ResourceLocation("skycraft", table));
        }
    }

    /** The grave-sign lines for this site, from the province's folk. */
    public String[] graveLines() {
        return Lore.graveSign(site.province(), RandomSource.create(seed ^ 0x6A09E667L));
    }

    public String story(String category) {
        return Lore.note(category, site.province(), site.x(), site.z(), RandomSource.create(seed ^ 0xBB67AE85L));
    }
}
