package com.skycraft.explore.build;

import com.skycraft.dungeons.world.DormantMobs;
import com.skycraft.explore.ExploreLoot;
import com.skycraft.explore.ExploreRegistry;
import com.skycraft.survival.Divine;
import com.skycraft.survival.SurvivalRegistry;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * Holy and ancient stones: wayshrines of the Nine, minor stone circles, Nordic cairns, Ayleid wells, Velothi
 * shrines, Yokudan obelisks, Breton menhirs, Akaviri and sea shrines, and the monoliths of the uncharted lands.
 */
public final class Shrines {
    private Shrines() {}

    // ------------------------------------------------------------------ helpers

    static BlockState candles(SiteBuilder b) {
        return Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 1 + b.r.nextInt(4)).setValue(CandleBlock.LIT, true);
    }

    /** A square stepped platform of {@code half} (so 2*half+1 wide), one block high, edged with stairs. */
    static void platform(SiteBuilder b, int half, Block top, Block stairs) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                b.pillarDown(x, 0, z, b.pal.stone().defaultBlockState());
                boolean edge = Math.abs(x) == half || Math.abs(z) == half;
                if (edge && !(Math.abs(x) == half && Math.abs(z) == half)) {
                    Direction d = Math.abs(x) == half ? (x > 0 ? Direction.WEST : Direction.EAST) : (z > 0 ? Direction.NORTH : Direction.SOUTH);
                    b.set(x, 0, z, b.stair(stairs, d));
                } else {
                    b.set(x, 0, z, top);
                }
            }
        }
        b.y += 1;
    }

    /** A rough standing stone: a 1x1 stack of {@code height} with a weathered, sometimes leaning, top. */
    static void menhir(SiteBuilder b, int dx, int dz, int height, BlockState stone, BlockState top) {
        b.pillarDown(dx, 0, dz, stone);
        for (int h = 0; h < height; h++) b.set(dx, h, dz, h == height - 1 ? top : stone);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    static Mob spawnById(SiteBuilder b, String id, int dx, int dz, boolean dormant) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        if (type == null || type == EntityType.PIG) return null;
        Mob mob;
        try {
            mob = b.spawn((EntityType<? extends Mob>) type, dx, dz);
        } catch (ClassCastException e) {
            return null;
        }
        if (mob != null && dormant) DormantMobs.makeDormant(mob);
        return mob;
    }

    /** Which Divine a wayshrine serves: Skyrim keeps Talos close, the elves Auri-El (Akatosh) and Phynaster's Julianos. */
    static Divine divineFor(SiteBuilder b) {
        String p = b.site.province();
        Divine[] pool = switch (p) {
            case "skyrim", "atmora" -> new Divine[]{Divine.TALOS, Divine.TALOS, Divine.KYNARETH, Divine.ARKAY, Divine.MARA, Divine.DIBELLA,
                    Divine.STENDARR, Divine.JULIANOS, Divine.ZENITHAR, Divine.AKATOSH};
            case "summerset" -> new Divine[]{Divine.AKATOSH, Divine.AKATOSH, Divine.JULIANOS, Divine.STENDARR, Divine.MARA};
            case "valenwood" -> new Divine[]{Divine.KYNARETH, Divine.KYNARETH, Divine.ARKAY, Divine.MARA, Divine.ZENITHAR};
            case "elsweyr" -> new Divine[]{Divine.AKATOSH, Divine.MARA, Divine.KYNARETH, Divine.DIBELLA};
            case "black_marsh" -> new Divine[]{Divine.ARKAY, Divine.ZENITHAR, Divine.MARA, Divine.KYNARETH};
            case "hammerfell" -> new Divine[]{Divine.ZENITHAR, Divine.STENDARR, Divine.ARKAY, Divine.KYNARETH, Divine.TALOS};
            default -> Divine.VALUES;
        };
        return pool[b.r.nextInt(pool.length)];
    }

    static DyeColor colorOf(Divine d) {
        return switch (d) {
            case AKATOSH -> DyeColor.YELLOW;
            case ARKAY -> DyeColor.GRAY;
            case DIBELLA -> DyeColor.PINK;
            case JULIANOS -> DyeColor.PURPLE;
            case KYNARETH -> DyeColor.LIGHT_BLUE;
            case MARA -> DyeColor.RED;
            case STENDARR -> DyeColor.WHITE;
            case TALOS -> DyeColor.BLUE;
            case ZENITHAR -> DyeColor.ORANGE;
        };
    }

    // ------------------------------------------------------------------ wayshrine of the Nine

    public static boolean wayshrine(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(4, null, b.pal.path().defaultBlockState(), 7);
        platform(b, 3, b.pal.brick(), b.pal.brickStairs());
        Divine divine = divineFor(b);
        // back wall with two pillars and a little roof
        for (int x = -2; x <= 2; x++) {
            for (int h = 0; h <= 3; h++) {
                boolean pillar = Math.abs(x) == 2;
                if (pillar || h == 3) b.set(x, h, -2, pillar ? b.pal.brick().defaultBlockState() : b.pal.brickSlab().defaultBlockState());
            }
            b.set(x, 4, -2, b.pal.brickSlab().defaultBlockState());
            b.set(x, 3, -1, Camps.slab(b.pal.brickSlab(), true));
        }
        b.set(-1, 0, -2, b.pal.wall().defaultBlockState());
        b.set(1, 0, -2, b.pal.wall().defaultBlockState());
        b.set(0, 0, -2, b.pal.brick().defaultBlockState());
        b.set(0, 1, -2, SurvivalRegistry.SHRINES.get(divine).get().defaultBlockState());
        b.set(-1, 1, -2, candles(b));
        b.set(1, 1, -2, candles(b));
        b.set(-2, 4, -2, b.pal.brick().defaultBlockState());
        b.set(2, 4, -2, b.pal.brick().defaultBlockState());
        // banners in the Divine's colour, hung from the pillars' fronts
        DyeColor c = colorOf(divine);
        b.set(-2, 2, -1, wallBanner(c, Direction.SOUTH));
        b.set(2, 2, -1, wallBanner(c, Direction.SOUTH));
        // the offering chest and flowers
        b.chest(0, 0, -1, Direction.SOUTH, ExploreLoot.table("offerings"));
        b.set(-2, 0, 1, Blocks.POTTED_POPPY);
        b.set(2, 0, 1, Blocks.POTTED_LILY_OF_THE_VALLEY);
        b.set(-2, 0, 0, Blocks.LANTERN);
        b.set(2, 0, 0, Blocks.LANTERN);
        return true;
    }

    /** A wall banner facing a local direction (rotated by {@link SiteBuilder#set}). */
    static BlockState wallBanner(DyeColor color, Direction localFacing) {
        Block wall = switch (color) {
            case YELLOW -> Blocks.YELLOW_WALL_BANNER;
            case GRAY -> Blocks.GRAY_WALL_BANNER;
            case PINK -> Blocks.PINK_WALL_BANNER;
            case PURPLE -> Blocks.PURPLE_WALL_BANNER;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WALL_BANNER;
            case RED -> Blocks.RED_WALL_BANNER;
            case WHITE -> Blocks.WHITE_WALL_BANNER;
            case BLUE -> Blocks.BLUE_WALL_BANNER;
            case ORANGE -> Blocks.ORANGE_WALL_BANNER;
            case GREEN -> Blocks.GREEN_WALL_BANNER;
            case CYAN -> Blocks.CYAN_WALL_BANNER;
            case BLACK -> Blocks.BLACK_WALL_BANNER;
            default -> Blocks.BROWN_WALL_BANNER;
        };
        return wall.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, localFacing);
    }

    // ------------------------------------------------------------------ minor stone circle

    public static boolean stoneCircle(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(5) > 10) return false;
        b.clear(6, 6);
        int n = 7 + b.r.nextInt(3);
        BlockState stone = (b.site.province().equals("summerset") ? Blocks.CALCITE : Blocks.STONE).defaultBlockState();
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            int dx = (int) Math.round(Math.cos(a) * 5), dz = (int) Math.round(Math.sin(a) * 5);
            int h = b.ground(dx, dz) + 1 - b.y;
            int height = 2 + b.r.nextInt(3);
            for (int k = 0; k < height; k++) b.set(dx, h + k, dz, k == height - 1 && b.r.nextBoolean() ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : stone);
        }
        // the altar: a flat slab on two blocks, candles and an offering
        b.set(0, 0, 0, Blocks.CHISELED_STONE_BRICKS);
        b.set(0, 1, 0, Blocks.STONE_SLAB);
        b.set(-1, 0, 0, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.EAST));
        b.set(1, 0, 0, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.WEST));
        b.set(0, 2, 0, candles(b));
        b.chest(0, 0, -1, Direction.SOUTH, ExploreLoot.table("offerings"));
        b.scatter(4, 8, Blocks.MOSS_CARPET.defaultBlockState(), true);
        return true;
    }

    // ------------------------------------------------------------------ Breton menhir

    public static boolean bretonMenhir(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.clear(5, 9);
        BlockState stone = Blocks.STONE.defaultBlockState();
        // a tall stone, 2x2 at the foot, tapering, a carved face on the front
        for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) b.pillarDown(x, 0, z, stone);
        for (int h = 0; h < 4; h++) for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) b.set(x, h, z, stone);
        for (int h = 4; h < 7; h++) b.set(0, h, 0, h == 6 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : stone);
        b.set(0, 2, 1, Blocks.CHISELED_STONE_BRICKS);
        b.set(1, 1, 1, Blocks.CRACKED_STONE_BRICKS);
        // a ring of low stones and an offering hidden at the foot
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + 0.4;
            b.onGround((int) Math.round(Math.cos(a) * 4), (int) Math.round(Math.sin(a) * 4), 0, Blocks.COBBLESTONE.defaultBlockState());
        }
        b.chest(-1, 0, 1, Direction.SOUTH, ExploreLoot.table("offerings"));
        b.set(-1, 1, 1, Blocks.MOSS_CARPET);
        b.scatter(3, 5, (b.r.nextBoolean() ? Blocks.CORNFLOWER : Blocks.OXEYE_DAISY).defaultBlockState(), true);
        b.set(2, 0, 0, Blocks.TORCH);
        return true;
    }

    // ------------------------------------------------------------------ Nordic cairn

    public static boolean nordicCairn(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 10) return false;
        b.clear(5, 6);
        // a domed pile of stones over a grave
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                int d2 = x * x + z * z;
                if (d2 > 10) continue;
                int h = d2 <= 2 ? 3 : d2 <= 5 ? 2 : 1;
                b.pillarDown(x, 0, z, Blocks.COBBLESTONE.defaultBlockState());
                for (int k = 0; k < h; k++) {
                    b.set(x, k, z, (b.r.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE : b.r.nextInt(4) == 0 ? Blocks.ANDESITE : Blocks.COBBLESTONE).defaultBlockState());
                }
            }
        }
        // the grave marker: a carved upright stone with a skull, and the burial urn on top
        b.set(0, 3, 0, Blocks.COBBLESTONE_WALL);
        b.set(0, 4, 0, Blocks.COBBLESTONE_WALL);
        b.set(0, 5, 0, Blocks.SKELETON_SKULL.defaultBlockState().setValue(net.minecraft.world.level.block.SkullBlock.ROTATION, b.r.nextInt(16)));
        b.set(1, 3, 0, Blocks.DECORATED_POT);
        b.set(-1, 3, 1, candles(b));
        b.chest(0, 0, 3, Direction.SOUTH, ExploreLoot.table("cairn"));
        // ... and sometimes the dead do not rest
        if (b.r.nextInt(3) == 0) spawnById(b, "skycraft:draugr", 2, 4, true);
        return true;
    }

    // ------------------------------------------------------------------ Ayleid well

    public static boolean ayleidWell(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(4, null, Blocks.STONE.defaultBlockState(), 7);
        BlockState white = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        // round white stone dais, three wide with a ring
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                int d2 = x * x + z * z;
                if (d2 > 10) continue;
                b.pillarDown(x, 0, z, Blocks.STONE.defaultBlockState());
                b.set(x, 0, z, d2 <= 2 ? white : d2 <= 5 ? Blocks.QUARTZ_BRICKS.defaultBlockState()
                        : Blocks.QUARTZ_SLAB.defaultBlockState());
            }
        }
        // the well: a fluted pillar holding the glowing welkynd stone
        b.set(0, 1, 0, Blocks.QUARTZ_PILLAR);
        b.set(0, 2, 0, ExploreRegistry.AYLEID_WELL.get().defaultBlockState());
        // four lesser stones at the corners, glowing blue-white
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            b.set(c[0], 1, c[1], Blocks.CHISELED_QUARTZ_BLOCK);
            b.set(c[0], 2, c[1], Blocks.SEA_LANTERN);
            b.set(c[0], 3, c[1], Blocks.END_ROD);
        }
        if (b.r.nextInt(3) == 0) b.chest(0, 1, 2, Direction.SOUTH, ExploreLoot.table("ayleid"));
        return true;
    }

    // ------------------------------------------------------------------ Velothi wayshrine

    public static boolean velothiShrine(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(4, null, b.pal.stone().defaultBlockState(), 8);
        platform(b, 3, b.pal.brick(), b.pal.brickStairs());
        BlockState stone = b.pal.stone().defaultBlockState();
        // four columns under a stepped dome
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int h = 0; h <= 2; h++) b.set(c[0], h, c[1], b.pal.brick().defaultBlockState());
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                b.set(x, 3, z, stone);
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) b.set(x, 4, z, stone);
            }
        }
        b.set(0, 5, 0, b.pal.brickSlab().defaultBlockState());
        b.set(0, 6, 0, Blocks.LIGHTNING_ROD);
        // the altar: an ancestor's urn, candles, a lantern
        b.set(0, 0, -1, Blocks.CHISELED_SANDSTONE);
        b.set(0, 1, -1, Blocks.DECORATED_POT);
        b.set(-1, 0, -1, candles(b));
        b.set(1, 0, -1, candles(b));
        b.set(0, 2, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        b.chest(0, 0, -2, Direction.SOUTH, ExploreLoot.table("velothi"));
        b.banner(-3, 0, -3, DyeColor.ORANGE);
        return true;
    }

    // ------------------------------------------------------------------ Yokudan obelisk

    public static boolean yokudanObelisk(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(4, null, Blocks.SANDSTONE.defaultBlockState(), 12);
        platform(b, 3, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE_STAIRS);
        BlockState body = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        for (int h = 0; h < 3; h++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) b.set(x, h, z, body);
        // glyph bands
        for (int x = -1; x <= 1; x++) {
            b.set(x, 1, 1, Blocks.CHISELED_SANDSTONE);
            b.set(x, 1, -1, Blocks.CHISELED_SANDSTONE);
        }
        for (int h = 3; h < 9; h++) b.set(0, h, 0, h % 3 == 1 ? Blocks.CHISELED_SANDSTONE.defaultBlockState() : body);
        b.set(0, 9, 0, Blocks.GOLD_BLOCK);
        b.set(0, 10, 0, Blocks.LIGHTNING_ROD);
        // the buried offering beneath the dais
        b.chest(0, -2, 2, Direction.SOUTH, ExploreLoot.table("yokudan"));
        b.set(2, 0, 2, Blocks.DEAD_BUSH);
        b.set(-2, 0, -2, Blocks.DEAD_BUSH);
        return true;
    }

    // ------------------------------------------------------------------ Akaviri shrine

    public static boolean akaviriShrine(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(4, null, Blocks.GRAVEL.defaultBlockState(), 8);
        platform(b, 2, b.pal.brick(), b.pal.brickStairs());
        BlockState post = b.pal.log().defaultBlockState();
        for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) for (int h = 0; h <= 2; h++) b.set(c[0], h, c[1], post);
        // a curved roof with upturned eaves
        Block st = b.pal.brickStairs();
        for (int x = -2; x <= 2; x++) {
            b.set(x, 3, -2, b.stair(st, Direction.SOUTH));
            b.set(x, 3, 2, b.stair(st, Direction.NORTH));
        }
        for (int z = -1; z <= 1; z++) {
            b.set(-2, 3, z, b.stair(st, Direction.EAST));
            b.set(2, 3, z, b.stair(st, Direction.WEST));
            for (int x = -1; x <= 1; x++) b.set(x, 3, z, b.pal.brick().defaultBlockState());
        }
        b.set(0, 4, 0, b.pal.brickSlab().defaultBlockState());
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) b.set(c[0], 4, c[1], Camps.slab(b.pal.brickSlab(), false));
        // the serpent idol: a lantern over a little altar, incense smoke
        b.set(0, 2, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        b.set(0, 0, 0, Blocks.SOUL_CAMPFIRE);
        b.set(0, 0, -1, Blocks.POLISHED_DEEPSLATE);
        b.set(0, 1, -1, Blocks.DECORATED_POT);
        b.chest(1, 0, 0, Direction.WEST, ExploreLoot.table("akaviri"));
        b.banner(-3, 0, 3, DyeColor.RED);
        return true;
    }

    // ------------------------------------------------------------------ sea shrine (Pyandonea, Thras)

    public static boolean seaShrine(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(4, null, Blocks.SAND.defaultBlockState(), 8);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int dx = (int) Math.round(Math.cos(a) * 3), dz = (int) Math.round(Math.sin(a) * 3);
            b.pillarDown(dx, 0, dz, Blocks.PRISMARINE.defaultBlockState());
            for (int h = 0; h < 2 + (i % 2); h++) b.set(dx, h, dz, Blocks.PRISMARINE_WALL.defaultBlockState());
        }
        for (int h = 0; h < 4; h++) b.set(0, h, 0, h == 0 ? Blocks.DARK_PRISMARINE.defaultBlockState() : Blocks.PRISMARINE_BRICKS.defaultBlockState());
        b.set(0, 4, 0, Blocks.SEA_LANTERN);
        b.set(0, 5, 0, Blocks.CONDUIT);
        b.scatter(3, 6, Blocks.DEAD_TUBE_CORAL_FAN.defaultBlockState().setValue(net.minecraft.world.level.block.BaseCoralPlantTypeBlock.WATERLOGGED, false), true);
        b.chest(1, 0, -1, Direction.SOUTH, ExploreLoot.table("offerings"));
        return true;
    }

    // ------------------------------------------------------------------ realm monolith

    public static boolean realmMonolith(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 10) return false;
        b.clear(5, 10);
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI / 5;
            b.onGround((int) Math.round(Math.cos(a) * 4), (int) Math.round(Math.sin(a) * 4), -1, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
        }
        int height = 6 + b.r.nextInt(3);
        for (int h = 0; h < height; h++) {
            for (int x = 0; x <= 1; x++) {
                if (h >= height - 2 && x == 1) continue;
                b.pillarDown(x, 0, 0, Blocks.BLACKSTONE.defaultBlockState());
                b.set(x, h, 0, (h % 3 == 1 && x == 0 ? Blocks.CRYING_OBSIDIAN : h % 2 == 0 ? Blocks.POLISHED_BLACKSTONE : Blocks.OBSIDIAN).defaultBlockState());
            }
        }
        b.set(0, height, 0, Blocks.AMETHYST_CLUSTER);
        b.set(1, height - 2, 0, Blocks.END_ROD);
        b.chest(0, 0, 2, Direction.SOUTH, ExploreLoot.table("monolith"));
        b.scatter(3, 4, Blocks.SMALL_AMETHYST_BUD.defaultBlockState(), true);
        return true;
    }
}
