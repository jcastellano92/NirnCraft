package com.skycraft.scatter;

import com.skycraft.arsenal.LeveledGear;
import com.skycraft.explore.ExploreLoot;
import com.skycraft.scatter.ScatterRules.Kind;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Procedural props that a block list cannot express: blobs and arcs of rock, ground-following lines, rings,
 * walks. Each takes the {@link Brush} (anchored at the prop's centre, base level = first air over the ground) and
 * the kind's {@code params}, and returns whether it built anything.
 */
public final class Builders {
    private static final Map<String, BiPredicate<Brush, Kind>> REGISTRY = new HashMap<>();

    static {
        REGISTRY.put("boulder_field", Builders::boulderField);
        REGISTRY.put("fallen_log", Builders::fallenLog);
        REGISTRY.put("hollow_stump", Builders::hollowStump);
        REGISTRY.put("mushroom_ring", Builders::mushroomRing);
        REGISTRY.put("bone_pile", Builders::bonePile);
        REGISTRY.put("animal_den", Builders::animalDen);
        REGISTRY.put("rock_arch", Builders::rockArch);
        REGISTRY.put("spire", Builders::spire);
        REGISTRY.put("ledge", Builders::ledge);
        REGISTRY.put("crystal_outcrop", Builders::crystalOutcrop);
        REGISTRY.put("ore_outcrop", Builders::oreOutcrop);
        REGISTRY.put("wildflowers", Builders::wildflowers);
        REGISTRY.put("berry_patch", Builders::berryPatch);
        REGISTRY.put("driftwood", Builders::driftwood);
        REGISTRY.put("reed_bed", Builders::reedBed);
        REGISTRY.put("fish_rack", Builders::fishRack);
        REGISTRY.put("boat_wreck", Builders::boatWreck);
        REGISTRY.put("stepping_stones", Builders::steppingStones);
        REGISTRY.put("blood_trail", Builders::bloodTrail);
        REGISTRY.put("broken_weapons", Builders::brokenWeapons);
        REGISTRY.put("rubble_pile", Builders::rubblePile);
        REGISTRY.put("broken_fence", Builders::brokenFence);
        REGISTRY.put("field_plot", Builders::fieldPlot);
        REGISTRY.put("broken_pen", Builders::brokenPen);
        REGISTRY.put("skull_stakes", Builders::skullStakes);
        REGISTRY.put("spire_grove", Formations::spireGrove);
        REGISTRY.put("erratic", Formations::erratic);
        REGISTRY.put("crystal_bloom", Formations::crystalBloom);
        REGISTRY.put("dragon_bones", Formations::dragonBones);
        REGISTRY.put("giant_hollow", Formations::giantHollow);
        REGISTRY.put("stash", Formations::stash);
    }

    private Builders() {}

    public static boolean has(String id) {
        return REGISTRY.containsKey(id);
    }

    public static boolean build(String id, Brush br, Kind k) {
        BiPredicate<Brush, Kind> f = REGISTRY.get(id);
        boolean ok = f != null && f.test(br, k);
        br.finish();
        return ok;
    }

    private static int p(Kind k, String key, int d) {
        return k.params.has(key) ? k.params.get(key).getAsInt() : d;
    }

    private static BlockState pillar(Block log, Direction.Axis axis) {
        return log.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    private static boolean soilBelow(Brush br, int dx, int dz) {
        BlockState g = br.b.level.getBlockState(br.b.at(dx, br.gy(dx, dz) - 1, dz));
        return g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) || g.is(Blocks.PODZOL) || g.is(Blocks.COARSE_DIRT)
                || g.is(Blocks.MOSS_BLOCK) || g.is(Blocks.ROOTED_DIRT) || g.is(Blocks.MYCELIUM);
    }

    // ------------------------------------------------------------------ rock

    /** A half-buried ellipsoid of rock whose base follows the ground of each column. */
    static boolean blob(Brush br, int cx, int cz, int rx, int ry, int rz, boolean moss) {
        boolean any = false;
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                double d2 = (double) dx * dx / (rx * rx) + (double) dz * dz / (rz * rz);
                if (d2 > 1.0) continue;
                int h = (int) Math.round(ry * Math.sqrt(Math.max(0, 1 - d2)));
                if (h < 1) h = 1;
                if (br.b.waterAt(cx + dx, cz + dz)) continue;
                for (int i = 0; i < h; i++) {
                    boolean topLayer = i == h - 1;
                    Block blk = moss && topLayer && br.r.nextInt(3) == 0 ? br.rock.c() : br.rock.pick(br.r);
                    any |= br.top(cx + dx, cz + dz, i, blk);
                }
            }
        }
        return any;
    }

    static boolean boulderField(Brush br, Kind k) {
        int n = br.range(p(k, "min", 3), p(k, "max", 5)), spread = p(k, "spread", 5);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int cx = br.r.nextInt(spread * 2 + 1) - spread, cz = br.r.nextInt(spread * 2 + 1) - spread;
            int rr = br.r.nextInt(5) == 0 ? 2 : 1;
            any |= blob(br, cx, cz, rr, rr + (br.r.nextInt(3) == 0 ? 1 : 0), rr, true);
        }
        // scree between them
        for (int i = 0; i < 4; i++) {
            br.top(br.r.nextInt(spread * 2 + 1) - spread, br.r.nextInt(spread * 2 + 1) - spread, 0, br.rock.slab());
        }
        return any;
    }

    static boolean rockArch(Brush br, Kind k) {
        int s = p(k, "scale", 1);
        double ox = 4.2 * s, oy = 5.0 * s, ix = 2.5 * s, iy = 3.3 * s;
        int hw = (int) Math.ceil(ox), hh = (int) Math.ceil(oy);
        boolean any = false;
        for (int x = -hw; x <= hw; x++) {
            for (int y = 0; y <= hh; y++) {
                double o = (x / ox) * (x / ox) + (y / oy) * (y / oy);
                double in = (x / ix) * (x / ix) + (y / iy) * (y / iy);
                if (o > 1.0 || in <= 1.0) continue;
                for (int z = 0; z < 2 * s; z++) {
                    Block blk = br.rock.pick(br.r);
                    any |= br.set(x, y, z - s / 2, blk);
                    if (y == 0) br.support(x, 0, z - s / 2, blk.defaultBlockState());
                }
            }
        }
        // fallen blocks at the feet
        for (int i = 0; i < 3 * s; i++) br.top(br.r.nextInt(hw * 2 + 1) - hw, br.r.nextInt(5) - 2, 0, br.rock.pick(br.r));
        return any;
    }

    static boolean spire(Brush br, Kind k) {
        boolean any = Formations.spireAt(br, 0, 0, br.range(p(k, "min", 6), p(k, "max", 12)));
        for (int i = 0; i < 4; i++) br.top(br.r.nextInt(7) - 3, br.r.nextInt(7) - 3, 0, br.rock.b());
        return any;
    }

    static boolean ledge(Brush br, Kind k) {
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        int best = -1, drop = 0;
        for (int i = 0; i < 4; i++) {
            int d = br.b.ground(0, 0) - br.b.ground(dirs[i][0] * 3, dirs[i][1] * 3);
            if (d > drop) {
                drop = d;
                best = i;
            }
        }
        if (best < 0 || drop < 3) return false;
        int fx = dirs[best][0], fz = dirs[best][1];
        int px = fz != 0 ? 1 : 0, pz = fx != 0 ? 1 : 0; // across
        boolean any = false;
        for (int step = 1; step <= 3; step++) {
            for (int w = -1; w <= 1; w++) {
                int dx = fx * step + px * w, dz = fz * step + pz * w;
                // flush with the top of the cliff at the centre
                if (step == 3 && br.r.nextInt(3) == 0) continue;
                any |= br.set(dx, -1, dz, step == 3 ? br.rock.slab().defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)
                        : br.rock.pick(br.r).defaultBlockState());
            }
        }
        br.top(fx * 2, fz * 2, 0, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 2));
        br.set(fx * 2 + px, 0, fz * 2 + pz, br.rock.c());
        return any;
    }

    static boolean crystalOutcrop(Brush br, Kind k) {
        Terrain.Ground g = Terrain.ground(br.b.level.getBlockState(br.b.at(0, -1, 0)));
        Block base = switch (g) {
            case ASH -> Blocks.BLACKSTONE;
            case SAND -> Blocks.SMOOTH_SANDSTONE;
            case SNOW -> Blocks.CALCITE;
            default -> br.r.nextBoolean() ? Blocks.CALCITE : Blocks.SMOOTH_BASALT;
        };
        boolean any = false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > 5 || (d2 > 2 && br.r.nextInt(3) == 0)) continue;
                if (br.b.waterAt(dx, dz)) continue;
                any |= br.top(dx, dz, 0, base);
            }
        }
        Block[] crystals = g == Terrain.Ground.ASH
                ? new Block[]{Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD}
                : new Block[]{Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD};
        int n = br.range(4, 7);
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(5) - 2, dz = br.r.nextInt(5) - 2;
            Block c = crystals[br.r.nextInt(crystals.length)];
            br.top(dx, dz, 1, c.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        }
        if (g == Terrain.Ground.ASH) br.top(0, 0, 1, Blocks.CRYING_OBSIDIAN);
        return any;
    }

    private static Block[] oresFor(String province) {
        Block iron = Blocks.IRON_ORE, coal = Blocks.COAL_ORE, copper = Blocks.COPPER_ORE;
        return switch (province) {
            case "skyrim", "atmora" -> new Block[]{Terrain.block("skycraft:silver_ore", iron), Terrain.block("skycraft:corundum_ore", iron), iron, coal};
            case "morrowind" -> new Block[]{Terrain.block("skycraft:ebony_ore", iron), Terrain.block("skycraft:malachite_ore", iron), iron, coal};
            case "hammerfell", "elsweyr", "yokuda" -> new Block[]{Terrain.block("skycraft:moonstone_ore", iron),
                    Terrain.block("skycraft:orichalcum_ore", iron), Blocks.GOLD_ORE, copper};
            case "black_marsh", "valenwood" -> new Block[]{Terrain.block("skycraft:malachite_ore", iron), Terrain.block("skycraft:quicksilver_ore", iron), copper, iron};
            default -> new Block[]{Terrain.block("skycraft:quicksilver_ore", iron), Terrain.block("skycraft:orichalcum_ore", iron), copper, iron, coal};
        };
    }

    static boolean oreOutcrop(Brush br, Kind k) {
        String province = br.b.site.province();
        Block[] ores = oresFor(province);
        boolean any = blob(br, 0, 0, 2, 2, 2, false);
        if (!any) return false;
        int n = br.range(3, 5);
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(5) - 2, dz = br.r.nextInt(5) - 2;
            if (dx * dx + dz * dz > 4) continue;
            int h = Math.max(0, 1 - (dx * dx + dz * dz) / 3);
            br.force(dx, br.gy(dx, dz) + h, dz, ores[br.r.nextInt(ores.length)].defaultBlockState());
        }
        if (br.r.nextInt(3) == 0) br.b.lying(3, br.gy(3, 0), 0, new ItemStack(Items.IRON_PICKAXE));
        if (br.r.nextInt(5) == 0) br.top(-3, 1, 0, Blocks.LANTERN);
        return true;
    }

    // ------------------------------------------------------------------ plants and wood

    static boolean fallenLog(Brush br, Kind k) {
        int len = br.range(p(k, "min", 4), p(k, "max", 7));
        Block log = br.b.pal.log();
        boolean any = false;
        boolean moss = br.r.nextInt(2) == 0;
        for (int i = 0; i < len; i++) {
            int dx = i - len / 2;
            if (br.b.waterAt(dx, 0)) continue;
            any |= br.top(dx, 0, 0, pillar(log, Direction.Axis.X));
            if (br.r.nextInt(5) == 0) br.top(dx, 1, 0, br.r.nextBoolean() ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM);
        }
        // snapped branches
        br.top(len / 2 - 1, 0, 1, br.b.pal.fence());
        if (br.r.nextBoolean()) br.top(-len / 2 + 1, -1, 1, br.b.pal.fence());
        return any;
    }

    static boolean hollowStump(Brush br, Kind k) {
        int[][] ring = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
        int gap = br.r.nextInt(ring.length);
        boolean any = false;
        for (int i = 0; i < ring.length; i++) {
            if (i == gap) continue;
            int h = br.range(1, 3);
            for (int y = 0; y < h; y++) any |= br.top(ring[i][0], ring[i][1], y, pillar(br.b.pal.log(), Direction.Axis.Y));
        }
        br.force(0, br.gy(0, 0) - 1, 0, Blocks.MOSS_BLOCK.defaultBlockState());
        br.top(0, 0, 0, br.r.nextBoolean() ? Blocks.BROWN_MUSHROOM : Blocks.RED_MUSHROOM);
        if (br.r.nextInt(3) == 0) br.b.lying(0, br.gy(0, 0), 0, new ItemStack(Items.STICK));
        return any;
    }

    static boolean mushroomRing(Brush br, Kind k) {
        int rad = p(k, "radius", 3);
        boolean red = br.r.nextInt(3) > 0;
        boolean any = false;
        int steps = 8 + rad * 3;
        for (int i = 0; i < steps; i++) {
            double a = i * 2 * Math.PI / steps;
            int dx = (int) Math.round(Math.cos(a) * rad), dz = (int) Math.round(Math.sin(a) * rad);
            if (br.b.waterAt(dx, dz)) continue;
            if (soilBelow(br, dx, dz)) br.force(dx, br.gy(dx, dz) - 1, dz, Blocks.PODZOL.defaultBlockState());
            any |= br.top(dx, dz, 0, red ? Blocks.RED_MUSHROOM : Blocks.BROWN_MUSHROOM);
        }

        return any;
    }

    static boolean wildflowers(Brush br, Kind k) {
        int n = br.range(p(k, "min", 8), p(k, "max", 14)), rad = p(k, "radius", 3);
        Terrain.Ground g = Terrain.ground(br.b.level.getBlockState(br.b.at(0, -1, 0)));
        Block[] set = g == Terrain.Ground.SNOW
                ? new Block[]{Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.AZURE_BLUET}
                : g == Terrain.Ground.MUD ? new Block[]{Blocks.BLUE_ORCHID, Blocks.FERN, Blocks.ALLIUM}
                : new Block[]{Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.ALLIUM};
        Block main = set[br.r.nextInt(set.length)];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(rad * 2 + 1) - rad, dz = br.r.nextInt(rad * 2 + 1) - rad;
            if (dx * dx + dz * dz > rad * rad + 1 || !soilBelow(br, dx, dz)) continue;
            any |= br.top(dx, dz, 0, br.r.nextInt(3) > 0 ? main : set[br.r.nextInt(set.length)]);
        }
        return any;
    }

    static boolean berryPatch(Brush br, Kind k) {
        int n = br.range(4, 7);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(5) - 2, dz = br.r.nextInt(5) - 2;
            if (!soilBelow(br, dx, dz)) continue;
            any |= br.top(dx, dz, 0, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1 + br.r.nextInt(3)));
        }
        return any;
    }

    // ------------------------------------------------------------------ bones and dens

    static boolean bonePile(Brush br, Kind k) {
        boolean any = false;
        any |= br.top(0, 0, 0, Blocks.BONE_BLOCK);
        if (br.r.nextBoolean()) br.top(0, 0, 1, Blocks.BONE_BLOCK);
        int n = br.range(2, 4);
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(5) - 2, dz = br.r.nextInt(5) - 2;
            if (br.r.nextBoolean()) br.top(dx, dz, 0, pillar(Blocks.BONE_BLOCK, br.r.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z));
            else br.b.lying(dx, br.gy(dx, dz), dz, new ItemStack(Items.BONE));
        }
        if (br.r.nextInt(2) == 0) {
            int dx = br.r.nextInt(3) - 1, dz = br.r.nextInt(3) - 1;
            int y = br.gy(dx, dz);
            if (br.b.free(dx, y, dz)) br.b.skull(dx, y, dz);
        }
        if (br.r.nextInt(6) == 0) br.top(1, 1, 0, Blocks.COBWEB);
        return any;
    }

    static boolean animalDen(Brush br, Kind k) {
        Block shell = br.rock.a() == Blocks.STONE || br.rock.a() == Blocks.BLACKSTONE ? Blocks.COARSE_DIRT : br.rock.a();
        boolean any = false;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 > 11) continue;
                int h = (int) Math.round(2.4 * Math.sqrt(Math.max(0, 1 - d2 / 11.0)));
                if (br.b.waterAt(dx, dz)) continue;
                for (int y = 0; y < Math.max(h, 1); y++) {
                    Block blk = br.r.nextInt(4) == 0 ? br.rock.pick(br.r) : shell;
                    any |= br.top(dx, dz, y, blk);
                }
            }
        }
        if (!any) return false;
        // the hollow and its mouth (towards local +z)
        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 0; dz++)
                for (int y = 0; y <= 1; y++) br.force(dx, br.gy(dx, dz) + y, dz, br.air());
        for (int dz = 1; dz <= 3; dz++) br.force(0, br.gy(0, dz), dz, br.air());
        br.force(0, br.gy(0, 2) + 1, 2, br.air());
        br.top(-1, -1, 0, Blocks.BROWN_CARPET);
        br.b.lying(0, br.gy(0, 0), 0, new ItemStack(Items.BONE));
        if (br.r.nextBoolean()) br.b.lying(-1, br.gy(-1, 0), 0, new ItemStack(Items.BONE));
        br.top(0, 4, 0, Blocks.BONE_BLOCK);
        return true;
    }

    // ------------------------------------------------------------------ shore

    private static Block strippedOf(Block log) {
        net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(log);
        if (key == null) return Blocks.STRIPPED_OAK_LOG;
        return Terrain.block(key.getNamespace() + ":stripped_" + key.getPath(), Blocks.STRIPPED_OAK_LOG);
    }

    static boolean driftwood(Brush br, Kind k) {
        int pieces = br.range(1, 3);
        boolean any = false;
        for (int j = 0; j < pieces; j++) {
            int oz = j * 2 - pieces + 1 + br.r.nextInt(2);
            int len = br.range(3, 5), start = br.r.nextInt(3) - 2;
            Block log = strippedOf(br.b.pal.log());
            for (int i = 0; i < len; i++) {
                if (br.b.waterAt(start + i, oz)) continue;
                any |= br.top(start + i, oz, 0, pillar(log, Direction.Axis.X));
            }
        }
        if (br.r.nextInt(3) == 0) br.top(2, 2, 0, br.b.pal.fence());
        return any;
    }

    static boolean reedBed(Brush br, Kind k) {
        int n = br.range(7, 12);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(7) - 3, dz = br.r.nextInt(7) - 3;
            if (br.b.waterAt(dx, dz)) continue;
            boolean wetEdge = br.b.waterAt(dx + 1, dz) || br.b.waterAt(dx - 1, dz) || br.b.waterAt(dx, dz + 1) || br.b.waterAt(dx, dz - 1);
            if (!wetEdge) continue;
            int h = br.range(1, 3);
            for (int y = 0; y < h; y++) any |= br.top(dx, dz, y, Blocks.SUGAR_CANE);
        }
        if (any) br.top(0, 0, 0, Blocks.TALL_GRASS);
        return any;
    }

    static boolean fishRack(Brush br, Kind k) {
        boolean any = false;
        for (int y = 0; y < 3; y++) {
            any |= br.set(-2, y, 0, br.b.pal.fence());
            br.set(2, y, 0, br.b.pal.fence());
        }
        for (int x = -2; x <= 2; x++) br.set(x, 2, 0, br.b.pal.fence());
        br.set(-1, 1, 0, Blocks.COBWEB.defaultBlockState());
        br.b.lying(-1, 0, 1, new ItemStack(Items.SALMON));
        br.b.lying(1, 0, 1, new ItemStack(Items.COD));
        br.set(3, 0, 1, Blocks.CAULDRON);
        br.b.barrel(-3, 0, 1, ExploreLoot.table("fisher"));
        br.written++;
        return any;
    }

    static boolean boatWreck(Brush br, Kind k) {
        Block planks = br.b.pal.planks();
        boolean any = false;
        for (int x = -3; x <= 3; x++) {
            int half = Math.abs(x) == 3 ? 0 : 1;
            for (int z = -half; z <= half; z++) {
                if (br.r.nextInt(6) == 0) continue;
                any |= br.set(x, -1, z, planks);
            }
            if (half == 1) {
                if (br.r.nextInt(4) > 0) br.set(x, 0, -1, br.b.pal.slab());
                if (br.r.nextInt(3) > 0) br.set(x, 0, 1, br.b.pal.slab());
            }
        }
        br.set(-3, 0, 0, br.b.stair(br.b.pal.stairs(), Direction.EAST));
        br.set(3, 0, 0, br.b.stair(br.b.pal.stairs(), Direction.WEST));
        for (int y = 0; y < 2 + br.r.nextInt(2); y++) br.set(0, y, 0, br.b.pal.fence());
        br.set(1, 1, 0, Blocks.WHITE_CARPET);
        if (br.r.nextInt(2) == 0) br.b.barrel(-1, 0, 0, ExploreLoot.table("shipwreck"));
        br.top(5, 1, 0, br.b.pal.slab());
        return any;
    }

    static boolean steppingStones(Brush br, Kind k) {
        boolean any = false;
        int x = 0;
        int stones = 0;
        for (int i = 0; i < 12 && stones < 6; i++) {
            x += 1 + br.r.nextInt(2);
            int z = br.r.nextInt(3) - 1;
            if (!br.b.waterAt(x, z)) continue;
            net.minecraft.core.BlockPos col = br.b.at(x, 0, z);
            int surface = br.b.groundAt(col.getX(), col.getZ());
            Block stone = br.r.nextInt(3) == 0 ? br.rock.c() : br.rock.a();
            br.b.put(new net.minecraft.core.BlockPos(col.getX(), surface, col.getZ()), stone.defaultBlockState());
            br.written++;
            stones++;
            any = true;
        }
        return stones >= 3;
    }

    // ------------------------------------------------------------------ the dead

    static boolean bloodTrail(Brush br, Kind k) {
        int len = br.range(6, 11);
        int dx = 0, dz = 0;
        boolean any = false;
        for (int i = 0; i < len; i++) {
            // (no carpet "blood": a red carpet strip outdoors reads as litter; the skull, bone and gear tell it)
            int step = br.r.nextInt(5);
            if (step < 3) dz++;
            else if (step == 3) dx++;
            else dx--;
            if (br.b.waterAt(dx, dz)) break;
        }
        int y = br.gy(dx, dz);
        if (br.b.free(dx, y, dz)) {
            br.b.skull(dx, y, dz);
            any = true;
        }
        br.b.lying(dx + 1, br.gy(dx + 1, dz), dz, new ItemStack(Items.BONE));
        int lvl = br.b.gearLevel();
        ItemStack w = LeveledGear.weapon(lvl, br.r);
        if (!w.isEmpty() && br.r.nextInt(2) == 0) {
            w.setDamageValue(w.getMaxDamage() / 2);
            br.b.lying(dx - 1, br.gy(dx - 1, dz), dz, w);
        }
        return any;
    }

    static boolean brokenWeapons(Brush br, Kind k) {
        int lvl = br.b.gearLevel();
        int n = br.range(2, 4);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int dx = br.r.nextInt(7) - 3, dz = br.r.nextInt(7) - 3;
            ItemStack w = br.r.nextInt(5) == 0 ? LeveledGear.bow(lvl, br.r) : LeveledGear.weapon(lvl, br.r);
            if (w.isEmpty()) w = new ItemStack(Items.IRON_SWORD);
            if (w.isDamageableItem()) w.setDamageValue(w.getMaxDamage() / 2 + br.r.nextInt(Math.max(1, w.getMaxDamage() / 3)));
            br.b.lying(dx, br.gy(dx, dz), dz, w);
            br.written++;
            any = true;
        }
        br.b.lying(br.r.nextInt(5) - 2, 0, br.r.nextInt(5) - 2, new ItemStack(Items.ARROW, 3));
        br.top(br.r.nextInt(5) - 2, br.r.nextInt(5) - 2, 0, br.rock.slab());
        // a torn banner on a snapped pole
        if (br.r.nextInt(2) == 0) {
            br.top(3, 0, 0, br.b.pal.fence());
            br.top(3, 0, 1, br.b.pal.fence());
            int y = br.gy(3, 0) + 2;
            if (br.b.free(3, y, 0)) br.b.banner(3, y, 0, br.b.dye(net.minecraft.world.item.DyeColor.RED));
        }
        return any;
    }

    static boolean skullStakes(Brush br, Kind k) {
        boolean any = false;
        int n = br.range(2, 4);
        for (int i = 0; i < n; i++) {
            int dx = i * 2 - n + 1 + br.r.nextInt(2), dz = br.r.nextInt(3) - 1;
            int h = br.range(2, 3);
            for (int y = 0; y < h; y++) any |= br.top(dx, dz, y, br.b.pal.fence());
            int y = br.gy(dx, dz) + h;
            if (br.b.free(dx, y, dz)) br.b.skull(dx, y, dz);
        }
        br.top(0, 2, 0, Blocks.COBWEB);
        return any;
    }

    static boolean rubblePile(Brush br, Kind k) {
        int n = br.range(7, 11);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            int dx = (int) Math.round(br.r.nextGaussian() * 1.4), dz = (int) Math.round(br.r.nextGaussian() * 1.4);
            if (br.b.waterAt(dx, dz)) continue;
            int roll = br.r.nextInt(8);
            Block blk = roll < 2 ? br.b.pal.brick() : roll < 3 ? br.b.pal.mossy() : roll < 4 ? Blocks.CRACKED_STONE_BRICKS
                    : roll < 5 ? br.rock.slab() : roll < 6 ? br.rock.wall() : br.rock.pick(br.r);
            any |= br.top(dx, dz, 0, blk);
            if (br.r.nextInt(3) == 0) br.top(dx, dz, 1, br.rock.pick(br.r));
        }
        // a stub of wall
        if (br.r.nextInt(2) == 0) {
            for (int y = 0; y < 3; y++) br.top(2, 2, y, br.b.pal.brick());
            br.top(2, 2, 3, br.b.pal.brick() == Blocks.STONE_BRICKS ? Blocks.STONE_BRICK_SLAB : br.rock.slab());
        }
        return any;
    }

    // ------------------------------------------------------------------ farm

    static boolean brokenFence(Brush br, Kind k) {
        int len = br.range(p(k, "min", 6), p(k, "max", 9));
        boolean any = false;
        for (int i = 0; i < len; i++) {
            int dx = i - len / 2;
            if (br.b.waterAt(dx, 0)) continue;
            int roll = br.r.nextInt(10);
            if (roll < 2) continue;                       // a gap
            if (roll == 2) {                              // a fallen rail
                br.top(dx, 1, 0, br.b.pal.slab());
                continue;
            }
            any |= br.top(dx, 0, 0, br.b.pal.fence());
            if (roll < 6 && i % 2 == 0) br.top(dx, 0, 1, br.b.pal.fence());
        }
        if (br.r.nextInt(2) == 0) br.top(len / 2 + 1, 0, 0, Blocks.HAY_BLOCK);
        return any;
    }

    static boolean brokenPen(Brush br, Kind k) {
        boolean any = false;
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) != 3 && Math.abs(z) != 2) continue;
                if (br.r.nextInt(100) < 30) continue;
                if (br.b.waterAt(x, z)) continue;
                any |= br.top(x, z, 0, br.b.pal.fence());
            }
        }
        br.top(-1, 0, 0, Blocks.HAY_BLOCK);
        br.top(1, 1, 0, Blocks.CAULDRON);
        if (br.r.nextInt(3) == 0) br.b.lying(0, br.gy(0, -1), -1, new ItemStack(Items.WHEAT));
        return any;
    }

    static boolean fieldPlot(Brush br, Kind k) {
        // an irrigated 5x3 plot: a water row between two farmland rows, capped at both ends
        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                if (br.b.waterAt(x, z)) return false;
            }
        }
        Block crop = switch (br.r.nextInt(3)) {
            case 0 -> Blocks.WHEAT;
            case 1 -> Blocks.CARROTS;
            default -> Blocks.POTATOES;
        };
        boolean any = false;
        for (int x = -2; x <= 2; x++) {
            br.force(x, -1, 0, Blocks.WATER.defaultBlockState());
            for (int z = -1; z <= 1; z += 2) {
                br.force(x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
                BlockState c = crop.defaultBlockState();
                c = c.setValue(net.minecraft.world.level.block.CropBlock.AGE, 2 + br.r.nextInt(5));
                any |= br.set(x, 0, z, c);
            }
            br.set(x, 0, 0, Blocks.AIR.defaultBlockState());
        }
        br.force(-3, -1, 0, br.b.pal.planks().defaultBlockState());
        br.force(3, -1, 0, br.b.pal.planks().defaultBlockState());
        br.set(-3, 0, -2, br.b.pal.fence());
        br.set(3, 0, 2, br.b.pal.fence());
        if (br.r.nextInt(3) == 0) {
            br.set(3, 0, -2, Blocks.HAY_BLOCK);
        }
        return any;
    }

}
