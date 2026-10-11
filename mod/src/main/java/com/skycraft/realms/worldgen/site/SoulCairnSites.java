package com.skycraft.realms.worldgen.site;

import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.dungeons.world.Painter;
import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.block.RealmPortalBlock;
import com.skycraft.realms.worldgen.RealmPiece;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

import static com.skycraft.realms.worldgen.site.Gates.st;
import static com.skycraft.realms.worldgen.site.RealmKit.b;

/**
 * The Soul Cairn's structures: soul stone and black stone under the violet haze, lit by soul gems.
 * <ul>
 *     <li>{@code soul_cairn/arrival}: the soul-stone platform where travellers arrive (facing north, towards the
 *     keep), soul-gem pylons around it, the portal home six blocks behind them and a broken flagstone way north.</li>
 *     <li>{@code soul_cairn/valerica_keep}: Valerica's courtyard: an octagonal wall of soul stone and black stone with
 *     a south gate, the sealed mausoleum on its north side, four soul-gem pylons, rows of graves, her guardians and a
 *     chest. The middle of the courtyard is left open (the quests summon the Keeper there).</li>
 *     <li>{@code soul_cairn/boneyard}: Durnehviir's arena: a ring of giant curved ribs around a stepped dais, a great
 *     skull half sunk at its edge, and the skeletal dragon himself perched on the dais.</li>
 *     <li>{@code soul_cairn/mausoleum}: ruined mausoleums with soul-gem lanterns and restless dead (one variant
 *     sunken, with a Wrathman).</li>
 * </ul>
 */
final class SoulCairnSites {
    private SoulCairnSites() {}

    static final String BONEMAN = "skycraft:boneman";
    static final String MISTMAN = "skycraft:mistman";
    static final String WRATHMAN = "skycraft:wrathman";
    static final String DRAGON = "skycraft:dragon";

    static void register() {
        RealmSites.add("soul_cairn/arrival", new Arrival());
        RealmSites.add("soul_cairn/valerica_keep", new Keep());
        RealmSites.add("soul_cairn/boneyard", new Boneyard());
        RealmSites.add("soul_cairn/mausoleum", new Mausoleum());
    }

    // ------------------------------------------------------------------ palettes and pieces

    static Draw.Pal soulStone() {
        return Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.SOUL_STONE), b(Blocks.POLISHED_BLACKSTONE_BRICKS),
                b(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)}, new int[]{8, 3, 1});
    }

    static Draw.Pal blackStone() {
        return RealmKit.weighted(new Block[]{Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.BLACKSTONE},
                new int[]{6, 2, 1});
    }

    static Draw.Pal ground() {
        return Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.SOUL_STONE), b(Blocks.POLISHED_BLACKSTONE), st(RealmBlocks.SOUL_ASH)},
                new int[]{6, 3, 2});
    }

    /** A soul-gem pylon: a stepped base, a shaft, a crown of glowing soul gem and clusters. */
    static void pylon(Draw d, int x, int y, int z, int h) {
        d.box(x - 1, y, z - 1, x + 1, y, z + 1, blackStone());
        d.box(x, y + 1, z, x, y + h, z, st(RealmBlocks.SOUL_STONE));
        d.set(x, y + h + 1, z, st(RealmBlocks.SOUL_GEM_BLOCK));
        d.set(x, y + h + 2, z, RealmKit.cluster(RealmBlocks.SOUL_GEM_CLUSTER.get(), Direction.UP));
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            d.set(x + dir.getStepX(), y + h + 1, z + dir.getStepZ(), RealmKit.cluster(RealmBlocks.SOUL_GEM_CLUSTER.get(), dir));
        }
    }

    /** A soul lantern on a short black-stone post. */
    static void lantern(Draw d, int x, int y, int z) {
        d.box(x, y + 1, z, x, y + 2, z, b(Blocks.POLISHED_BLACKSTONE));
        d.set(x, y + 3, z, b(Blocks.SOUL_LANTERN));
    }

    // ================================================================================================ arrival

    static final class Arrival implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int z = RealmLayout.SC_ARRIVAL_Z;
            int y = RealmSites.ground(ctx, 0, z);
            return Optional.of(new RealmSites.Start(0, Mth.clamp(y, 40, 140), z, 0, 2));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return new BoundingBox(s.x() - 11, s.y() - 14, s.z() - 46, s.x() + 11, s.y() + 16, s.z() + 11);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, deck = piece.oy;
            // the platform: a stepped disc of soul stone, a ring of soul gem set into it
            d.footing(cx, cz, 9, deck - 1, 12, soulStone());
            d.cylinder(cx, cz, 9, deck - 1, deck - 1, blackStone());
            d.cylinder(cx, cz, 8, deck, deck, soulStone());
            d.tube(cx, cz, 5.6, 4.6, deck, deck, Draw.Pal.of(st(RealmBlocks.SOUL_GEM_BLOCK)));
            d.cylinder(cx, cz, 1.5, deck, deck, Draw.Pal.of(b(Blocks.CHISELED_POLISHED_BLACKSTONE)));
            d.clearCylinder(cx, cz, 9.5, deck + 1, deck + 12);
            d.set(cx, deck, cz, st(RealmBlocks.SOUL_STONE));
            // pylons on the diagonals, lanterns east and west
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) pylon(d, cx + 5 * sx, deck, cz + 5 * sz, 3);
            lantern(d, cx - 7, deck, cz);
            lantern(d, cx + 7, deck, cz);
            // the portal home, six blocks behind the arrival spot
            BlockState portal = st(RealmBlocks.SOUL_PORTAL).setValue(RealmPortalBlock.AXIS_X, true).setValue(RealmPortalBlock.RETURN, true);
            Gates.framedPortal(d, cx, deck, cz + 6, true, 1, 4, portal, Draw.Pal.of(st(RealmBlocks.SOUL_STONE)), blackStone());
            d.set(cx, deck + 8, cz + 6, st(RealmBlocks.SOUL_GEM_BLOCK));
            // a broken flagstone way north towards the keep
            for (int x = Math.max(cx - 1, p.minX()); x <= Math.min(cx + 1, p.maxX()); x++)
                for (int z = Math.max(cz - 44, p.minZ()); z <= Math.min(cz - 10, p.maxZ()); z++) {
                    if (p.chance(x, 0, z, 3, 3)) continue;
                    int top = p.surfaceAt(x, z) - 1;
                    if (top < deck - 12 || top > deck + 12) continue;
                    p.set(x, top, z, p.chance(x, top, z, 4, 4) ? b(Blocks.POLISHED_BLACKSTONE) : st(RealmBlocks.SOUL_STONE));
                }
        }
    }

    // ================================================================================================ Valerica's courtyard

    static final class Keep implements RealmSites.Site {
        static final double WALL = 30;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int z = RealmLayout.SC_KEEP_Z;
            int y = RealmSites.ground(ctx, 0, z);
            return Optional.of(new RealmSites.Start(0, Mth.clamp(y, 40, 140), z, 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 36, 14, 28);
        }

        /** Octagonal distance: the wall follows an octagon of "radius" {@link #WALL}. */
        static double oct(int dx, int dz) {
            int ax = Math.abs(dx), az = Math.abs(dz);
            return Math.max(Math.max(ax, az), (ax + az) * 0.7071);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy - 1;
            Draw.Pal wall = RealmKit.banded(soulStone(), Draw.Pal.of(b(Blocks.CHISELED_POLISHED_BLACKSTONE)), 4, fy);
            int wallTop = fy + 9;
            // ---- the courtyard floor and the wall, column by column
            for (int x = Math.max(cx - 33, p.minX()); x <= Math.min(cx + 33, p.maxX()); x++)
                for (int z = Math.max(cz - 33, p.minZ()); z <= Math.min(cz + 33, p.maxZ()); z++) {
                    double o = oct(x - cx, z - cz);
                    if (o > WALL + 0.6) continue;
                    if (o > WALL - 1.6) {
                        d.column(x, z, fy, 14, wall);
                        for (int y = fy + 1; y <= wallTop; y++) p.set(x, y, z, wall.at(p, x, y, z));
                        if (o > WALL - 0.5 && Math.floorMod(x + z, 2) == 0) p.set(x, wallTop + 1, z, wall.at(p, x, wallTop + 1, z));
                        for (int y = wallTop + 2; y <= wallTop + 8; y++) p.air(x, y, z);
                        continue;
                    }
                    d.column(x, z, fy, 10, ground());
                    BlockState top = ground().at(p, x, fy, z);
                    if (Math.abs(x - cx) <= 2 && z > cz) top = b(Blocks.POLISHED_BLACKSTONE_BRICKS); // the path from the gate
                    p.set(x, fy, z, top);
                    for (int y = fy + 1; y <= fy + 16; y++) p.air(x, y, z);
                }
            // the south gate
            for (int x = cx - 3; x <= cx + 3; x++) {
                int h = Math.abs(x - cx) == 3 ? 5 : 7;
                d.air(x, fy + 1, cz + (int) WALL - 2, x, fy + h, cz + (int) WALL + 1);
                for (int z = cz + (int) WALL - 2; z <= cz + (int) WALL + 1; z++) d.column(x, z, fy, 6, Draw.Pal.of(b(Blocks.POLISHED_BLACKSTONE_BRICKS)));
            }
            // towers at the octagon's corners, each crowned with a soul gem
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 8 + i * Math.PI / 4;
                double r = WALL / Math.cos(Math.PI / 8) - 1;
                int tx = (int) Math.round(cx + Math.cos(a) * r), tz = (int) Math.round(cz + Math.sin(a) * r);
                d.footing(tx, tz, 3, fy, 14, wall);
                d.cylinder(tx, tz, 3, fy + 1, wallTop + 4, wall);
                d.cone(tx, tz, 3.4, 0.6, wallTop + 5, wallTop + 9, blackStone());
                d.set(tx, wallTop + 10, tz, st(RealmBlocks.SOUL_GEM_BLOCK));
            }

            // ---- the sealed mausoleum on the north side
            int mz0 = cz - 26, mz1 = cz - 13;
            d.footing(cx - 9, mz0, cx + 9, mz1 + 3, fy, 12, blackStone());
            d.box(cx - 9, fy + 1, mz0, cx + 9, fy + 1, mz1 + 3, blackStone());
            d.box(cx - 3, fy + 1, mz1 + 4, cx + 3, fy + 1, mz1 + 4, RealmKit.stairs(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.NORTH));
            d.walls(cx - 8, fy + 2, mz0, cx + 8, fy + 11, mz1, wall);
            d.air(cx - 7, fy + 2, mz0 + 1, cx + 7, fy + 10, mz1 - 1);
            for (int k = 0; k <= 4; k++) {
                d.box(cx - 9 + k, fy + 12 + k, mz0 - 1 + k, cx + 9 - k, fy + 12 + k, mz1 + 1 - k, blackStone());
            }
            pylon(d, cx, fy + 16, (mz0 + mz1) / 2, 2);
            // a portico of four columns
            for (int x : new int[]{cx - 6, cx - 2, cx + 2, cx + 6}) {
                d.box(x, fy + 2, mz1 + 2, x, fy + 10, mz1 + 2, b(Blocks.POLISHED_BASALT));
                d.set(x, fy + 11, mz1 + 2, b(Blocks.CHISELED_POLISHED_BLACKSTONE));
            }
            d.box(cx - 7, fy + 12, mz1 + 1, cx + 7, fy + 12, mz1 + 2, blackStone());
            // the sealed door: soul stone with a glowing seal
            d.box(cx - 2, fy + 2, mz1, cx + 2, fy + 7, mz1, st(RealmBlocks.SOUL_STONE));
            d.set(cx, fy + 5, mz1, st(RealmBlocks.SOUL_GEM_BLOCK));
            d.set(cx - 1, fy + 4, mz1, b(Blocks.CRYING_OBSIDIAN));
            d.set(cx + 1, fy + 4, mz1, b(Blocks.CRYING_OBSIDIAN));
            d.set(cx, fy + 3, mz1, b(Blocks.CRYING_OBSIDIAN));
            d.set(cx, fy + 7, mz1, b(Blocks.CRYING_OBSIDIAN));
            // inside, unseen: the tomb
            d.box(cx - 1, fy + 2, mz0 + 3, cx + 1, fy + 2, mz0 + 6, b(Blocks.POLISHED_BLACKSTONE));
            d.set(cx, fy + 3, mz0 + 4, b(Blocks.SOUL_LANTERN));

            // ---- the four pylons that hold the seal
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) pylon(d, cx + 15 * sx, fy, cz + 4 + 11 * sz, 7);

            // ---- rows of graves east and west, lanterns along the path
            for (int sx = -1; sx <= 1; sx += 2)
                for (int row = 0; row < 3; row++)
                    for (int k = 0; k < 4; k++) {
                        int x = cx + sx * (19 + row * 3), z = cz - 4 + k * 5;
                        if (oct(x - cx, z - cz) > WALL - 3) continue;
                        if (p.chance(x, fy, z, 13, 5)) continue;
                        d.set(x, fy + 1, z, b(Blocks.POLISHED_BLACKSTONE));
                        d.set(x, fy + 2, z, p.chance(x, fy, z, 14, 3) ? b(Blocks.CHISELED_POLISHED_BLACKSTONE)
                                : b(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB));
                        d.set(x, fy + 1, z + 1, st(RealmBlocks.SOUL_ASH));
                    }
            for (int z = cz + 8; z <= cz + 24; z += 8) {
                lantern(d, cx - 4, fy, z);
                lantern(d, cx + 4, fy, z);
            }
            // ---- an offering chest by the mausoleum steps, and its guardians
            p.container(cx + 8, fy + 2, mz1 + 3, Draw.chest(Direction.SOUTH), RealmLoot.SOUL_CAIRN_KEEP);
            p.spawn(BONEMAN, cx - 3, fy + 1, cz + 22, 180f, null);
            p.spawn(BONEMAN, cx + 3, fy + 1, cz + 22, 180f, null);
            p.spawn(MISTMAN, cx - 15, fy + 1, cz - 2, 90f, null);
        }
    }

    // ================================================================================================ the Boneyard

    static final class Boneyard implements RealmSites.Site {
        static final int RIBS = 14;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = RealmLayout.SC_BONEYARD_X, z = RealmLayout.SC_BONEYARD_Z;
            int y = RealmSites.ground(ctx, x, z);
            return Optional.of(new RealmSites.Start(x, Mth.clamp(y, 40, 140), z, 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 38, 10, 34);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy - 1;
            Draw.Pal bone = Draw.Pal.of(b(Blocks.BONE_BLOCK));
            // the stepped dais, bone-rimmed
            d.footing(cx, cz, 13, fy, 12, soulStone());
            d.cylinder(cx, cz, 13, fy, fy, ground());
            d.clearCylinder(cx, cz, 13, fy + 1, fy + 14);
            d.cylinder(cx, cz, 12, fy + 1, fy + 1, soulStone());
            d.cylinder(cx, cz, 10, fy + 2, fy + 2, soulStone());
            d.cylinder(cx, cz, 8, fy + 3, fy + 3, blackStone());
            d.tube(cx, cz, 12, 11, fy + 1, fy + 1, bone);
            d.tube(cx, cz, 5.5, 4.5, fy + 3, fy + 3, Draw.Pal.of(st(RealmBlocks.SOUL_GEM_BLOCK)));
            // the ring of ribs, curving inwards over the dais
            for (int i = 0; i < RIBS; i++) {
                double a = i * Math.PI * 2 / RIBS + 0.12;
                long h = RealmSites.hash(piece.seed, i, 7);
                double r0 = 25 + (h & 3), hgt = 20 + (h >>> 2 & 7);
                double ca = Math.cos(a), sa = Math.sin(a);
                d.curve(cx + 0.5 + ca * r0, fy - 2, cz + 0.5 + sa * r0,
                        cx + 0.5 + ca * (r0 + 5), fy + hgt * 0.75, cz + 0.5 + sa * (r0 + 5),
                        cx + 0.5 + ca * (r0 - 9), fy + hgt, cz + 0.5 + sa * (r0 - 9), 1.7, 0.6, bone);
            }
            // a great skull half sunk at the north edge, its jaw agape towards the dais
            int kx = cx, kz = cz - 26, ky = fy + 1;
            d.blob(kx, ky + 2, kz, 4.5, 3.5, 6, bone);
            d.blob(kx, ky + 2, kz + 3, 3.0, 2.2, 4.2, Draw.Pal.of(b(Blocks.AIR)));
            for (int s = -1; s <= 1; s += 2) {
                d.blob(kx + 3 * s, ky + 4, kz + 1, 1.3, 1.3, 1.3, Draw.Pal.of(b(Blocks.AIR)));
                d.set(kx + 3 * s, ky + 3, kz + 1, st(RealmBlocks.SOUL_GEM_BLOCK));
                d.curve(kx + 0.5 + 3 * s, ky + 5, kz - 3, kx + 0.5 + 6 * s, ky + 9, kz - 6, kx + 0.5 + 4 * s, ky + 12, kz - 10, 1.0, 0.4, bone);
            }
            d.line(kx - 3.5, ky, kz + 2, kx - 2.5, ky - 0.5, kz + 9, 0.8, bone);
            d.line(kx + 4.5, ky, kz + 2, kx + 3.5, ky - 0.5, kz + 9, 0.8, bone);
            // shards of soul gem about the field
            for (int i = 0; i < 10; i++) {
                long h = RealmSites.hash(piece.seed, i, 9);
                double a = (h & 1023) / 1023.0 * Math.PI * 2, r = 15 + (h >>> 10 & 7);
                int x = (int) Math.floor(cx + 0.5 + Math.cos(a) * r), z = (int) Math.floor(cz + 0.5 + Math.sin(a) * r);
                if (!p.ownsColumn(x, z)) continue;
                int top = p.surfaceAt(x, z);
                if (top < fy - 4 || top > fy + 6) continue;
                p.set(x, top, z, RealmKit.cluster(RealmBlocks.SOUL_GEM_CLUSTER.get(), Direction.UP));
                if ((h >>> 14 & 1) == 0) p.set(x, top - 1, z, st(RealmBlocks.SOUL_GEM_BLOCK));
            }
            // Durnehviir, perched on his dais
            int dy = fy + 4;
            p.spawn(DRAGON, cx, dy, cz, 0f, "Durnehviir", m -> {
                if (m instanceof DragonEntity dragon) {
                    dragon.setVariant(DragonEntity.SKELETAL);
                    dragon.setRole(DragonEntity.ROLE_LAIR);
                    dragon.setFlying(false);
                    dragon.setPerch(new Vec3(cx + 0.5, dy, cz + 0.5));
                    dragon.getPersistentData().putBoolean("skycraft_durnehviir", true);
                }
            });
        }
    }

    // ================================================================================================ mausoleums

    static final class Mausoleum implements RealmSites.Site {
        static final int CRYPT = 0, COLONNADE = 1, SUNKEN = 2;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            if (RealmLayout.dist(x, z, 0, RealmLayout.SC_KEEP_Z) < 140) return Optional.empty();
            if (RealmLayout.dist(x, z, 0, RealmLayout.SC_ARRIVAL_Z) < 80) return Optional.empty();
            if (RealmLayout.dist(x, z, RealmLayout.SC_BONEYARD_X, RealmLayout.SC_BONEYARD_Z) < 130) return Optional.empty();
            int g = RealmSites.ground(ctx, x, z);
            int min = g, max = g, sum = g;
            for (int[] o : new int[][]{{8, 8}, {-8, 8}, {8, -8}, {-8, -8}}) {
                int h = RealmSites.ground(ctx, x + o[0], z + o[1]);
                min = Math.min(min, h);
                max = Math.max(max, h);
                sum += h;
            }
            if (min < 56 || max - min > 5) return Optional.empty(); // not in a rift, not on a slope
            int variant = (int) Math.floorMod(RealmSites.hash(ctx.seed(), ctx.chunkPos().x, ctx.chunkPos().z), 3L);
            return Optional.of(new RealmSites.Start(x, sum / 5, z, variant, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 12, 15, 16);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz;
            boolean sunken = piece.param == SUNKEN;
            int fy = piece.oy - 1 - (sunken ? 3 : 0);
            RealmKit.Frame f = new RealmKit.Frame(cx, cz, Direction.from2DDataValue(piece.facing));
            Draw.Pal wall = soulStone();
            // the plinth (or the pit of a sunken tomb) and a clear footprint
            f.box(d, -6, fy, -7, 6, fy, 8, blackStone());
            d.footing(f.x(-6, -7), f.z(-6, -7), f.x(6, 8), f.z(6, 8), fy - 1, 8, blackStone());
            f.air(d, -6, fy + 1, -7, 6, fy + 12 + (sunken ? 3 : 0), 8);
            if (sunken) {
                // retaining walls and steps up to the ground at the front
                for (int u = -6; u <= 6; u++) {
                    f.box(d, u, fy + 1, -7, u, fy + 3, -7, wall);
                    f.box(d, u, fy + 1, 8, u, fy + 3, 8, wall);
                }
                for (int v = -7; v <= 8; v++) {
                    f.box(d, -6, fy + 1, v, -6, fy + 3, v, wall);
                    f.box(d, 6, fy + 1, v, 6, fy + 3, v, wall);
                }
                for (int k = 0; k < 3; k++) {
                    f.box(d, -1, fy + 1, 7 - k, 1, fy + 3 - k, 7 - k, wall);
                    f.air(d, -1, fy + 4 - k, 7 - k, 1, fy + 7, 7 - k);
                }
            }
            // the tomb: the back half whole under its stepped roof, the front half fallen to different heights
            int wh = 7;
            for (int u = -5; u <= 5; u++)
                for (int v = -6; v <= 3; v++) {
                    boolean edge = Math.abs(u) == 5 || v == -6 || v == 3;
                    if (!edge) continue;
                    int x = f.x(u, v), z = f.z(u, v);
                    int top = v <= -2 ? fy + wh : fy + wh - 1 - (int) Math.round((p.noise(x, 0, z, 3, 15) * 0.5 + 0.5) * 4);
                    for (int y = fy + 1; y <= top; y++) d.set(x, y, z, wall.at(p, x, y, z));
                }
            // the door in the front wall
            f.air(d, -1, fy + 1, 3, 1, fy + 3, 3);
            for (int k = 0; k <= 2; k++) f.box(d, -5 + k, fy + wh + 1 + k, -6 + k, 5 - k, fy + wh + 1 + k, -2, blackStone());
            // rubble from the fallen roof
            for (int u = -4; u <= 4; u++)
                for (int v = -1; v <= 2; v++) {
                    int x = f.x(u, v), z = f.z(u, v);
                    if (Math.abs(u) <= 1 || !p.chance(x, fy, z, 16, 3)) continue;
                    d.set(x, fy + 1, z, p.chance(x, fy, z, 17, 2) ? b(Blocks.COBBLED_DEEPSLATE) : b(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB));
                }
            // inside: sarcophagi, rubble, a soul gem in the back wall, the chest
            for (int s = -1; s <= 1; s += 2) {
                f.box(d, 3 * s, fy + 1, -4, 3 * s, fy + 1, 0, b(Blocks.POLISHED_BLACKSTONE));
                f.box(d, 3 * s, fy + 2, -4, 3 * s, fy + 2, 0, b(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB));
            }
            f.set(d, 0, fy + 3, -6, st(RealmBlocks.SOUL_GEM_BLOCK));
            f.set(d, -2, fy + 1, 1, st(RealmBlocks.SOUL_ASH));
            f.set(d, 2, fy + 1, -5, b(Blocks.SKELETON_SKULL));
            p.container(f.x(0, -5), fy + 1, f.z(0, -5), Draw.chest(f.front()), RealmLoot.SOUL_CAIRN);
            // the porch: columns (one fallen) and soul-gem lanterns
            if (piece.param == COLONNADE || sunken) {
                f.box(d, -4, fy + 1, 6, -4, fy + 6, 6, b(Blocks.POLISHED_BASALT));
                f.box(d, 4, fy + 1, 6, 4, fy + 3, 6, b(Blocks.POLISHED_BASALT));
                f.box(d, 2, fy + 1, 7, 2, fy + 1, 8, b(Blocks.POLISHED_BASALT));
            }
            for (int s = -1; s <= 1; s += 2) {
                int x = f.x(2 * s, 5), z = f.z(2 * s, 5);
                lantern(d, x, fy, z);
                d.set(x, fy + 4, z, RealmKit.cluster(RealmBlocks.SOUL_GEM_CLUSTER.get(), Direction.UP));
            }
            // its restless keepers
            p.spawn(BONEMAN, f.x(0, 6), fy + 1, f.z(0, 6), Painter.yaw(f.front()), null);
            p.spawn(MISTMAN, f.x(0, -2), fy + 1, f.z(0, -2), Painter.yaw(f.front()), null);
            if (sunken) p.spawn(WRATHMAN, f.x(-2, -1), fy + 1, f.z(-2, -1), Painter.yaw(f.front()), null);
        }
    }
}
