package com.skycraft.realms.worldgen.site;

import com.skycraft.dungeons.world.Painter;
import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.block.RealmPortalBlock;
import com.skycraft.realms.worldgen.RealmPiece;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

import static com.skycraft.realms.worldgen.site.Gates.st;

/**
 * Sovngarde's landmarks, laid out along the old road from the misty vale to the Hall ({@link RealmLayout}):
 * <ul>
 *     <li>{@code sovngarde/arrival}: a ring of carved stones where the dead (and the living) arrive, with the golden
 *     way home behind it;</li>
 *     <li>{@code sovngarde/arena}: the stone circle at the dragon fight's origin, where the heroes of old wait for
 *     Alduin (the End's own fight places his perch at its centre);</li>
 *     <li>{@code sovngarde/whalebone_bridge}: the spine and ribs of a vast whale spanning the misty chasm, Tsun at its
 *     near end;</li>
 *     <li>{@code sovngarde/hall_of_valor}: Shor's great mead hall on its heights, Ysgramor on his throne;</li>
 *     <li>{@code sovngarde/hero_cairn}: lonely cairns of the honoured dead in the fields.</li>
 * </ul>
 */
final class SovngardeSites {
    private SovngardeSites() {}

    static final String HERO = "skycraft:sovngarde_hero";
    static final String TSUN = "skycraft:tsun";

    static void register() {
        RealmSites.add("sovngarde/arrival", new Arrival());
        RealmSites.add("sovngarde/arena", new Arena());
        RealmSites.add("sovngarde/whalebone_bridge", new WhaleboneBridge());
        RealmSites.add("sovngarde/hall_of_valor", new Hall());
        RealmSites.add("sovngarde/hero_cairn", new HeroCairn());
    }

    static Draw.Pal nordStone() {
        return Draw.Pal.weighted(new BlockState[]{Blocks.STONE_BRICKS.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(),
                Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), Blocks.ANDESITE.defaultBlockState()}, new int[]{10, 4, 2, 1});
    }

    static Draw.Pal paving() {
        return Draw.Pal.weighted(new BlockState[]{Blocks.STONE_BRICKS.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(),
                Blocks.POLISHED_ANDESITE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState()}, new int[]{8, 4, 3, 2});
    }

    static BlockState stairs(BlockState base, Direction facing, boolean top) {
        return base.setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
    }

    static BlockState log(BlockState base, Direction.Axis axis) {
        return base.setValue(RotatedPillarBlock.AXIS, axis);
    }

    /** A carved standing stone, {@code h} tall, with a capstone. */
    static void standingStone(Draw d, int x, int y, int z, int h) {
        d.column(x, z, y - 1, 6, Draw.Pal.of(Blocks.STONE_BRICKS.defaultBlockState()));
        for (int dy = 0; dy < h; dy++) {
            BlockState s = dy == h - 2 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : nordStone().at(d.p, x, y + dy, z);
            d.set(x, y + dy, z, s);
        }
        d.set(x, y + h, z, Blocks.STONE_BRICK_SLAB.defaultBlockState());
    }

    static void brazier(Draw d, int x, int y, int z) {
        d.set(x, y, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        d.set(x, y + 1, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
    }

    static void hero(Painter p, int x, int y, int z, float yaw, String name) {
        p.spawn(HERO, x, y, z, yaw, name);
    }

    // ================================================================================================ arrival

    static final class Arrival implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            return Optional.of(new RealmSites.Start(0, 85, RealmLayout.SG_ARRIVAL_Z, 0, 2));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 16, 12, 14);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, y = piece.oy;
            d.footing(cx, cz, 9.5, y, 10, Draw.Pal.of(Blocks.STONE.defaultBlockState()));
            d.cylinder(cx, cz, 9.5, y, y, paving());
            d.tube(cx, cz, 9.5, 8.5, y, y, Draw.Pal.of(Blocks.POLISHED_ANDESITE.defaultBlockState()));
            d.clearCylinder(cx, cz, 10, y + 1, y + 10);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4 + Math.PI / 8;
                standingStone(d, (int) Math.round(cx + Math.cos(a) * 11), y + 1, (int) Math.round(cz + Math.sin(a) * 11), 4 + i % 2);
            }
            // the golden way home, framed in carved stone, behind the arrival point (south)
            int gz = cz + 6;
            BlockState portal = st(RealmBlocks.VALOR_PORTAL).setValue(RealmPortalBlock.RETURN, true).setValue(RealmPortalBlock.AXIS_X, true);
            Gates.framedPortal(d, cx, y, gz, true, 1, 4, portal, Draw.Pal.of(Blocks.CHISELED_STONE_BRICKS.defaultBlockState()),
                    Draw.Pal.of(Blocks.POLISHED_ANDESITE.defaultBlockState()));
            d.set(cx - 3, y + 1, gz, Blocks.GOLD_BLOCK.defaultBlockState());
            d.set(cx + 3, y + 1, gz, Blocks.GOLD_BLOCK.defaultBlockState());
            brazier(d, cx - 4, y, cz - 4);
            brazier(d, cx + 4, y, cz - 4);
            // the old road's first stones, heading north into the mist
            for (int z = cz - 10; z >= cz - 16; z--)
                for (int x = cx - 2; x <= cx + 2; x++) d.set(x, y, z, paving().at(p, x, y, z));
        }
    }

    // ================================================================================================ the arena

    static final class Arena implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            return Optional.of(new RealmSites.Start(0, 85, 0, 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 48, 10, 14);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int y = piece.oy;
            // a paved ring path around the open field where Alduin lands; the centre stays grass for his perch
            d.tube(0, 0, 33, 30, y, y, paving());
            for (int i = 0; i < 4; i++) {
                // the road crosses the ring north-south
                Direction dir = Direction.from2DDataValue(i);
                if (dir.getAxis() != Direction.Axis.Z) continue;
                for (int t = 30; t <= 46; t++)
                    for (int w = -2; w <= 2; w++) d.set(w, y, dir.getStepZ() * t, paving().at(p, w, y, dir.getStepZ() * t));
            }
            // twelve tall stones
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6 + Math.PI / 12;
                standingStone(d, (int) Math.round(Math.cos(a) * 38), y + 1, (int) Math.round(Math.sin(a) * 38), 6 + i % 3);
            }
            // burial mounds of the fallen between the stones
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                int mx = (int) Math.round(Math.cos(a) * 44), mz = (int) Math.round(Math.sin(a) * 44);
                d.blob(mx, y, mz, 2.5, 1.5, 2.5, Draw.Pal.of(Blocks.MOSS_BLOCK.defaultBlockState()));
                d.set(mx, y + 2, mz, Blocks.STONE_BRICK_WALL.defaultBlockState());
            }
            brazier(d, -4, y, 34);
            brazier(d, 4, y, 34);
            brazier(d, -4, y, -34);
            brazier(d, 4, y, -34);
            // the three who first fought the World-Eater wait at the northern stones
            hero(p, -6, y + 1, -36, 0f, "Gormlaith Golden-Hilt");
            hero(p, 0, y + 1, -38, 0f, "Hakon One-Eye");
            hero(p, 6, y + 1, -36, 0f, "Felldir the Old");
        }
    }

    // ================================================================================================ the Whalebone Bridge

    static final class WhaleboneBridge implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            return Optional.of(new RealmSites.Start(0, 86, (RealmLayout.SG_BRIDGE_SOUTH + RealmLayout.SG_BRIDGE_NORTH) / 2, 0, 2));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return new BoundingBox(-12, 20, RealmLayout.SG_BRIDGE_NORTH - 6, 12, 112, RealmLayout.SG_BRIDGE_SOUTH + 8);
        }

        /** Deck height along the bridge: a gentle arch over the chasm. */
        static int deck(int z) {
            double s = Mth.clamp((z - RealmLayout.SG_BRIDGE_SOUTH) / (double) (RealmLayout.SG_BRIDGE_NORTH - RealmLayout.SG_BRIDGE_SOUTH), 0, 1);
            return 86 + (int) Math.round(Math.sin(s * Math.PI) * 6);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            BlockState spine = log(Blocks.BONE_BLOCK.defaultBlockState(), Direction.Axis.Z);
            BlockState rib = log(Blocks.BONE_BLOCK.defaultBlockState(), Direction.Axis.Y);
            BlockState edge = Blocks.SMOOTH_QUARTZ.defaultBlockState();
            int z0 = RealmLayout.SG_BRIDGE_SOUTH + 4, z1 = RealmLayout.SG_BRIDGE_NORTH - 4;
            for (int z = z0; z >= z1; z--) {
                int y = deck(z);
                for (int x = -2; x <= 2; x++) {
                    d.set(x, y, z, Math.abs(x) == 2 ? edge : spine);
                    for (int h = 1; h <= 5; h++) d.p.air(x, y + h, z);
                }
                // the vertebrae under the deck
                if (Math.floorMod(z, 4) == 0) {
                    d.box(-1, y - 2, z, 1, y - 1, z, Draw.Pal.of(spine));
                    d.set(0, y - 3, z, rib);
                }
                // ends dig into the rims
                if (z > RealmLayout.SG_BRIDGE_SOUTH - 6 || z < RealmLayout.SG_BRIDGE_NORTH + 6) {
                    for (int x = -2; x <= 2; x++) d.column(x, z, y - 1, 30, Draw.Pal.of(Blocks.STONE_BRICKS.defaultBlockState()));
                }
            }
            // the ribs: curved pairs rising from the deck's edges and bending over the path
            for (int z = z0 - 3; z >= z1 + 3; z -= 6) {
                int y = deck(z);
                for (int s = -1; s <= 1; s += 2) {
                    d.curve(s * 2.5 + 0.5, y + 0.5, z + 0.5, s * 7.0 + 0.5, y + 7, z + 0.5, s * 2.2 + 0.5, y + 11, z + 0.5, 0.9, 0.5, Draw.Pal.of(rib));
                    // and the lower ribs hanging into the mist
                    d.curve(s * 2.5 + 0.5, y - 0.5, z + 0.5, s * 7.5 + 0.5, y - 6, z + 0.5, s * 3.5 + 0.5, y - 13, z + 0.5, 0.8, 0.4, Draw.Pal.of(rib));
                }
            }
            // lanterns on posts at both ends, and Tsun at the near end
            for (int zEnd : new int[]{RealmLayout.SG_BRIDGE_SOUTH + 2, RealmLayout.SG_BRIDGE_NORTH - 2}) {
                int y = deck(zEnd);
                for (int s = -1; s <= 1; s += 2) {
                    d.box(s * 3, y + 1, zEnd, s * 3, y + 3, zEnd, Blocks.STONE_BRICK_WALL.defaultBlockState());
                    d.set(s * 3, y + 4, zEnd, Blocks.LANTERN.defaultBlockState());
                }
            }
            int ty = deck(RealmLayout.SG_BRIDGE_SOUTH + 1);
            p.spawn(TSUN, 0, ty + 1, RealmLayout.SG_BRIDGE_SOUTH + 1, 0f, "Tsun", m -> m.setPersistenceRequired());
        }
    }

    // ================================================================================================ the Hall of Valor

    static final class Hall implements RealmSites.Site {
        static final int HW = 16;      // half width (walls at x = +-16)
        static final int HALF_LEN = 30;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            return Optional.of(new RealmSites.Start(0, 101, RealmLayout.SG_HALL_Z, 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return new BoundingBox(-30, s.y() - 14, s.z() - HALF_LEN - 14, 30, s.y() + 46, s.z() + HALF_LEN + 22);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int f = piece.oy, cz = piece.oz;
            int zS = cz + HALF_LEN, zN = cz - HALF_LEN;   // south front (doors), north back (throne)
            BlockState spruceLog = log(Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), Direction.Axis.Y);
            BlockState darkLog = log(Blocks.DARK_OAK_LOG.defaultBlockState(), Direction.Axis.Y);
            BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
            BlockState darkPlanks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
            BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();

            // ---- the platform: a stone plinth with steps down to the road
            d.footing(-HW - 4, zN - 4, HW + 4, zS + 4, f, 14, nordStone());
            d.box(-HW - 4, f, zN - 4, HW + 4, f, zS + 4, paving());
            d.box(-HW - 4, f + 1, zN - 4, HW + 4, f + 30, zS + 12, Blocks.AIR.defaultBlockState());
            for (int s = 1; s <= 2; s++) {
                for (int x = -6; x <= 6; x++) {
                    int z = zS + 4 + s;
                    d.set(x, f - s + 1, z, stairs(Blocks.STONE_BRICK_STAIRS.defaultBlockState(), Direction.NORTH, false));
                    d.column(x, z, f - s, 10, nordStone());
                }
            }

            // ---- floor and walls
            d.box(-HW, f, zN, HW, f, zS, Draw.Pal.mix(darkPlanks, planks, 3));
            int wallTop = f + 10;
            d.walls(-HW, f + 1, zN, HW, f + 2, zS, nordStone());
            d.walls(-HW, f + 3, zN, HW, wallTop, zS, Draw.Pal.of(planks));
            // timber frame: log pillars every 5 blocks and a log sill and plate
            for (int z = zN; z <= zS; z += 5) {
                d.box(-HW, f + 1, z, -HW, wallTop, z, spruceLog);
                d.box(HW, f + 1, z, HW, wallTop, z, spruceLog);
            }
            d.box(-HW, f + 3, zN, -HW, f + 3, zS, log(Blocks.SPRUCE_LOG.defaultBlockState(), Direction.Axis.Z));
            d.box(HW, f + 3, zN, HW, f + 3, zS, log(Blocks.SPRUCE_LOG.defaultBlockState(), Direction.Axis.Z));
            d.box(-HW, wallTop, zN, -HW, wallTop, zS, log(Blocks.SPRUCE_LOG.defaultBlockState(), Direction.Axis.Z));
            d.box(HW, wallTop, zN, HW, wallTop, zS, log(Blocks.SPRUCE_LOG.defaultBlockState(), Direction.Axis.Z));
            d.box(-HW, f + 1, zN, HW, f + 1, zN, nordStone());

            // ---- the roof: steep gables of dark oak, ridge along the hall, eaves overhanging
            for (int x = -HW - 2; x <= HW + 2; x++) {
                int ry = wallTop + (HW + 2 - Math.abs(x));
                Direction up = x < 0 ? Direction.EAST : Direction.WEST;
                for (int z = zN - 2; z <= zS + 2; z++) {
                    if (x == 0) {
                        d.set(x, ry, z, log(Blocks.DARK_OAK_LOG.defaultBlockState(), Direction.Axis.Z));
                    } else {
                        d.set(x, ry, z, stairs(Blocks.DARK_OAK_STAIRS.defaultBlockState(), up, false));
                        if (Math.abs(x) <= HW) d.set(x, ry - 1, z, darkPlanks);
                    }
                }
                // gable walls under the roof at both ends
                if (Math.abs(x) < HW) {
                    for (int y = wallTop + 1; y < ry - 1; y++) {
                        BlockState g = Math.floorMod(x, 4) == 0 ? spruceLog : planks;
                        d.set(x, y, zN, g);
                        d.set(x, y, zS, g);
                    }
                }
            }
            // crossed beams carved like dragon heads over each gable
            for (int zEnd : new int[]{zN - 3, zS + 3}) {
                int ridge = wallTop + HW + 2;
                d.line(-5.5, ridge - 5, zEnd + 0.5, 3.5, ridge + 4, zEnd + 0.5, 0.55, Draw.Pal.of(darkLog));
                d.line(5.5, ridge - 5, zEnd + 0.5, -3.5, ridge + 4, zEnd + 0.5, 0.55, Draw.Pal.of(darkLog));
                d.set(-4, ridge + 5, zEnd, gold);
                d.set(4, ridge + 5, zEnd, gold);
            }

            // ---- the great doors (open), framed in gold
            for (int y = f + 1; y <= f + 7; y++)
                for (int x = -3; x <= 3; x++) {
                    if (y == f + 7 && Math.abs(x) == 3) continue;
                    d.p.air(x, y, zS);
                }
            d.box(-4, f + 1, zS + 1, -4, f + 7, zS + 1, gold);
            d.box(4, f + 1, zS + 1, 4, f + 7, zS + 1, gold);
            d.box(-4, f + 8, zS + 1, 4, f + 8, zS + 1, gold);
            d.set(0, f + 9, zS + 1, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());

            // ---- inside: the long hearth, tables and benches, the throne, light
            d.air(-HW + 1, f + 1, zN + 1, HW - 1, wallTop - 1, zS - 1);
            for (int z = zN + 10; z <= zS - 8; z++) {
                d.set(-1, f, z, Blocks.STONE_BRICKS.defaultBlockState());
                d.set(1, f, z, Blocks.STONE_BRICKS.defaultBlockState());
                d.set(0, f, z, Blocks.NETHERRACK.defaultBlockState());
                if (Math.floorMod(z, 3) == 0) d.set(0, f + 1, z, Blocks.CAMPFIRE.defaultBlockState());
            }
            for (int s = -1; s <= 1; s += 2) {
                for (int z = zN + 10; z <= zS - 6; z++) {
                    // long tables and benches either side of the hearth
                    d.set(s * 6, f + 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                    d.set(s * 6, f + 2, z, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    d.set(s * 4, f + 1, z, stairs(Blocks.SPRUCE_STAIRS.defaultBlockState(), s < 0 ? Direction.WEST : Direction.EAST, false));
                    d.set(s * 8, f + 1, z, stairs(Blocks.SPRUCE_STAIRS.defaultBlockState(), s < 0 ? Direction.EAST : Direction.WEST, false));
                    if (Math.floorMod(z, 4) == 1) d.set(s * 6, f + 3, z, Blocks.CANDLE.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.CandleBlock.LIT, true)
                            .setValue(net.minecraft.world.level.block.CandleBlock.CANDLES, 2));
                }
                // banners of the honoured: red wool strips on the walls
                for (int z = zN + 4; z <= zS - 4; z += 5) {
                    d.box(s * (HW - 1), f + 5, z + 2, s * (HW - 1), f + 8, z + 2, Blocks.RED_WOOL.defaultBlockState());
                    d.set(s * (HW - 1), f + 9, z + 2, Blocks.YELLOW_WOOL.defaultBlockState());
                }
            }
            // chandeliers on chains from the roof beams
            BlockState chain = Blocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.Y);
            for (int z = zN + 8; z <= zS - 6; z += 9) {
                for (int y = wallTop + 5; y <= wallTop + HW; y++) d.set(0, y, z, chain);
                d.set(0, wallTop + 4, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                // cross beam
                d.box(-HW + 1, wallTop, z, HW - 1, wallTop, z, log(Blocks.DARK_OAK_LOG.defaultBlockState(), Direction.Axis.X));
                for (int s = -1; s <= 1; s += 2) d.set(s * 9, wallTop - 1, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
            // the dais and Ysgramor's throne at the north end
            d.box(-6, f + 1, zN + 1, 6, f + 1, zN + 6, Blocks.POLISHED_ANDESITE.defaultBlockState());
            d.box(-6, f + 1, zN + 6, 6, f + 1, zN + 6, stairs(Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState(), Direction.NORTH, false));
            d.set(0, f + 2, zN + 2, stairs(Blocks.SPRUCE_STAIRS.defaultBlockState(), Direction.NORTH, false));
            d.box(-1, f + 2, zN + 1, 1, f + 5, zN + 1, Draw.Pal.of(darkPlanks));
            d.set(0, f + 6, zN + 1, gold);
            d.set(-1, f + 2, zN + 2, gold);
            d.set(1, f + 2, zN + 2, gold);
            brazier(d, -4, f + 1, zN + 3);
            brazier(d, 4, f + 1, zN + 3);
            p.container(-5, f + 2, zN + 1, Draw.chest(Direction.SOUTH), com.skycraft.realms.worldgen.site.RealmLoot.SOVNGARDE_HALL);
            p.container(5, f + 2, zN + 1, Draw.chest(Direction.SOUTH), com.skycraft.realms.worldgen.site.RealmLoot.SOVNGARDE_HALL);
            // the honoured dead at their feast
            hero(p, 0, f + 2, zN + 3, 180f, "Ysgramor");
            hero(p, -6, f + 1, zN + 14, 90f, "Jurgen Windcaller");
            hero(p, 6, f + 1, zN + 20, 270f, "Ulfric the Elder");
            hero(p, -6, f + 1, zN + 28, 90f, "Shield-Brother Kaer");
            hero(p, 6, f + 1, zN + 36, 270f, "Svana Iron-Heart");
            hero(p, -2, f + 1, zS - 5, 0f, null);
            // braziers along the approach
            for (int z = zS + 2; z <= zS + 4; z += 2) {
                brazier(d, -6, f, z);
                brazier(d, 6, f, z);
            }
        }
    }

    // ================================================================================================ hero cairns

    static final class HeroCairn implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            // not on the road or in the landmarks' grounds, not in the mountains
            if (Math.abs(x) < 120 && z > RealmLayout.SG_HALL_Z - 120 && z < RealmLayout.SG_ARRIVAL_Z + 60) return Optional.empty();
            double d = Math.sqrt((double) x * x + (double) z * z);
            if (d > RealmLayout.SG_RING_IN) return Optional.empty();
            int y = RealmSites.ground(ctx, x, z);
            if (y < 70 || y > 110) return Optional.empty();
            return Optional.of(new RealmSites.Start(x, y, z, 0, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 8, 6, 10);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, y = piece.oy;
            d.blob(cx, y - 1, cz, 4, 2.5, 4, Draw.Pal.mix(Blocks.MOSS_BLOCK.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), 3));
            d.blob(cx, y + 1, cz, 2.5, 1.6, 2.5, Draw.Pal.mix(Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), 2));
            Direction face = Direction.from2DDataValue(piece.facing);
            int fx = cx + face.getStepX() * 3, fz = cz + face.getStepZ() * 3;
            d.box(fx, y, fz, fx, y + 1, fz, Blocks.AIR.defaultBlockState());
            p.container(fx, y, fz, Draw.chest(face), RealmLoot.SOVNGARDE_CAIRN);
            standingStone(d, cx - face.getStepX() * 4 + face.getStepZ() * 2, y, cz - face.getStepZ() * 4 + face.getStepX() * 2, 4);
            d.set(cx, y + 3, cz, Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
            hero(p, fx + face.getStepX() * 2, y, fz + face.getStepZ() * 2, 0f, null);
        }
    }
}
