package com.skycraft.realms.worldgen.site;

import com.skycraft.dungeons.world.Painter;
import com.skycraft.realms.RealmBlocks;
import com.skycraft.realms.RealmLayout;
import com.skycraft.realms.worldgen.RealmPiece;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

import static com.skycraft.realms.worldgen.site.Gates.st;

/**
 * The Deadlands' structures.
 * <ul>
 *     <li>{@code deadlands/sigil_keep} (one per 640-block cell, {@link RealmLayout}): the arrival platform with the
 *     gate home, a causeway of black stone to the Great Sigil Tower, two flanking spires. The tower's spiral ramp and
 *     its blood-fire updraft both lead to the Sigil chamber, where the Sigil Keeper guards the Sigil Stone.</li>
 *     <li>{@code deadlands/citadel}: a walled Daedric citadel with corner towers, a gatehouse and a keep.</li>
 *     <li>{@code deadlands/spire}: a lone burning spire.</li>
 *     <li>{@code deadlands/bridge}: a long thorned bridge on arches across a lava sea.</li>
 * </ul>
 */
final class DeadlandsSites {
    private DeadlandsSites() {}

    static final String DREMORA = "skycraft:dremora";
    static final String SCAMP = "skycraft:scamp";
    static final String FLAME_ATRONACH = "skycraft:flame_atronach";
    static final String CLANNFEAR = "skycraft:clannfear";
    static final String DAEDROTH = "skycraft:daedroth";
    static final String SPIDER_DAEDRA = "skycraft:spider_daedra";

    static void register() {
        RealmSites.add("deadlands/sigil_keep", new Keep());
        RealmSites.add("deadlands/citadel", new Citadel());
        RealmSites.add("deadlands/spire", new Spire());
        RealmSites.add("deadlands/bridge", new Bridge());
    }

    static Draw.Pal rock() {
        return Draw.Pal.weighted(new BlockState[]{st(RealmBlocks.DEADLANDS_ROCK), Blocks.BLACKSTONE.defaultBlockState(),
                st(RealmBlocks.DEADLANDS_MAGMA_ROCK)}, new int[]{12, 3, 1});
    }

    static Draw.Pal glyphBand(Draw.Pal base, int every) {
        BlockState glyph = st(RealmBlocks.DAEDRIC_GLYPH_BRICKS);
        return (p, x, y, z) -> Math.floorMod(y, every) == 0 ? glyph : base.at(p, x, y, z);
    }

    /** A brazier: magma rock with eternal fire on it. */
    static void brazier(Draw d, int x, int y, int z) {
        d.set(x, y, z, st(RealmBlocks.DAEDRIC_BRICKS));
        d.set(x, y + 1, z, st(RealmBlocks.DEADLANDS_MAGMA_ROCK));
        d.set(x, y + 2, z, Blocks.FIRE.defaultBlockState());
    }

    // ================================================================================================ the Sigil Keep

    static final class Keep implements RealmSites.Site {
        static final int R_BASE = 11;
        static final double R_TOP = 8.5;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int cx = Math.floorDiv(ctx.chunkPos().x, RealmLayout.DL_SPACING / 16);
            int cz = Math.floorDiv(ctx.chunkPos().z, RealmLayout.DL_SPACING / 16);
            return Optional.of(new RealmSites.Start(RealmLayout.keepX(cx), RealmLayout.DL_KEEP_FLOOR, RealmLayout.keepZ(cz), 0, 2));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return new BoundingBox(s.x() - 46, s.y() - 60, s.z() - 46, s.x() + 46, RealmLayout.DL_SIGIL_FLOOR + 40,
                    s.z() + RealmLayout.DL_ARRIVAL_DZ + 16);
        }

        static double radius(int y) {
            double t = Mth.clamp((y - RealmLayout.DL_KEEP_FLOOR) / (double) (RealmLayout.DL_SIGIL_FLOOR - RealmLayout.DL_KEEP_FLOOR), 0, 1);
            return Mth.lerp(t, R_BASE, R_TOP);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int kx = piece.ox, kz = piece.oz;
            int fy = RealmLayout.DL_KEEP_FLOOR - 1;
            int sf = RealmLayout.DL_SIGIL_FLOOR;
            Draw.Pal wall = glyphBand(Gates.daedric(), 12);
            Draw.Pal floor = Draw.Pal.mix(st(RealmBlocks.DAEDRIC_BRICKS), st(RealmBlocks.DAEDRIC_GLYPH_BRICKS), 9);

            // ---- the plaza around the tower and the ground under everything
            d.footing(kx, kz, R_BASE + 6, fy, 70, rock());
            d.cylinder(kx, kz, R_BASE + 6, fy, fy, floor);
            d.clearCylinder(kx, kz, R_BASE + 6, fy + 1, fy + 12);

            // ---- buttress claws around the base
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 6 + i * Math.PI / 3;
                double ca = Math.cos(a), sa = Math.sin(a);
                d.curve(kx + 0.5 + ca * (R_BASE + 7), fy, kz + 0.5 + sa * (R_BASE + 7),
                        kx + 0.5 + ca * (R_BASE + 4), fy + 18, kz + 0.5 + sa * (R_BASE + 4),
                        kx + 0.5 + ca * (R_BASE - 0.5), fy + 44, kz + 0.5 + sa * (R_BASE - 0.5), 2.4, 1.2, Gates.daedric());
                d.set((int) Math.floor(kx + 0.5 + ca * (R_BASE + 8)), fy + 1, (int) Math.floor(kz + 0.5 + sa * (R_BASE + 8)),
                        Gates.spike(Direction.UP));
            }

            // ---- the tower shell, tapering, with glyph bands
            for (int y = fy; y <= sf + 8; y++) {
                double r = radius(y);
                d.tube(kx, kz, r, r - 2.05, y, y, wall);
            }
            // interior
            for (int y = fy + 1; y <= sf - 1; y++) d.clearCylinder(kx, kz, radius(y) - 2.05, y, y);
            d.cylinder(kx, kz, R_BASE - 2, fy, fy, floor);
            // window slits glowing with magma light
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4 + Math.PI / 8;
                for (int y0 = fy + 14; y0 < sf - 6; y0 += 16) {
                    double r = radius(y0) - 0.6;
                    int x = (int) Math.floor(kx + 0.5 + Math.cos(a) * r), z = (int) Math.floor(kz + 0.5 + Math.sin(a) * r);
                    for (int y = y0; y < y0 + 4; y++) d.set(x, y, z, Blocks.RED_STAINED_GLASS.defaultBlockState());
                }
            }
            // the spiral ramp and the updraft shaft
            d.spiral(kx, kz, 2.6, 6.4, fy + 1, sf - 1, 12.0, 0.0, floor);
            d.box(kx - 1, fy + 1, kz - 1, kx + 1, sf + 1, kz + 1, st(RealmBlocks.SIGIL_FLAME));
            d.tube(kx, kz, 2.4, 1.6, fy + 1, fy + 1, Draw.Pal.of(st(RealmBlocks.DAEDRIC_GLYPH_BRICKS)));
            // the door (south) with a pointed head
            for (int y = fy + 1; y <= fy + 6; y++) {
                int hw = y >= fy + 5 ? 0 : 1;
                d.air(kx - hw, y, kz + R_BASE - 3, kx + hw, y, kz + R_BASE);
            }
            d.set(kx - 2, fy + 1, kz + R_BASE + 1, Gates.spike(Direction.UP));
            d.set(kx + 2, fy + 1, kz + R_BASE + 1, Gates.spike(Direction.UP));

            // ---- the Sigil chamber
            double rt = radius(sf);
            d.cylinder(kx, kz, rt - 2.05, sf, sf, floor);
            d.box(kx - 1, sf, kz - 1, kx + 1, sf, kz + 1, st(RealmBlocks.SIGIL_FLAME));
            d.clearCylinder(kx, kz, rt - 2.05, sf + 1, sf + 7);
            d.box(kx - 1, sf + 1, kz - 1, kx + 1, sf + 1, kz + 1, st(RealmBlocks.SIGIL_FLAME));
            // the stairwell: where the ramp's last turn comes up, the chamber floor is open
            for (int x = Math.max(kx - 7, p.minX()); x <= Math.min(kx + 7, p.maxX()); x++)
                for (int z = Math.max(kz - 7, p.minZ()); z <= Math.min(kz + 7, p.maxZ()); z++) {
                    double dx = x - kx, dz = z - kz, d2 = dx * dx + dz * dz;
                    if (d2 < 2.6 * 2.6 || d2 > 6.4 * 6.4 + 0.25) continue;
                    double a = Math.atan2(dz, dx);
                    double base = fy + 1 + (a / (Math.PI * 2) - Math.floor(a / (Math.PI * 2))) * 12.0;
                    double top = base + Math.floor((sf - 1 - base) / 12.0) * 12.0;
                    if (top >= sf - 4) p.air(x, sf, z);
                }
            d.cylinder(kx, kz, rt, sf + 9, sf + 9, floor);
            d.tube(kx, kz, rt, rt - 2.05, sf + 8, sf + 8, Draw.Pal.of(st(RealmBlocks.DAEDRIC_GLYPH_BRICKS)));
            // four balcony openings
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                int ox = dir.getStepX(), oz = dir.getStepZ();
                for (int y = sf + 1; y <= sf + 4; y++)
                    for (int w = -1; w <= 1; w++) {
                        for (int r = (int) rt - 2; r <= (int) rt + 1; r++) {
                            int x = kx + ox * r + (oz != 0 ? w : 0), z = kz + oz * r + (ox != 0 ? w : 0);
                            d.p.air(x, y, z);
                        }
                    }
                for (int w = -1; w <= 1; w++)
                    for (int r = (int) rt - 1; r <= (int) rt + 2; r++) {
                        int x = kx + ox * r + (oz != 0 ? w : 0), z = kz + oz * r + (ox != 0 ? w : 0);
                        d.set(x, sf, z, st(RealmBlocks.DAEDRIC_BRICKS));
                    }
                d.set(kx + ox * ((int) rt + 2) + (oz != 0 ? -1 : 0), sf + 1, kz + oz * ((int) rt + 2) + (ox != 0 ? -1 : 0), Gates.spike(Direction.UP));
                d.set(kx + ox * ((int) rt + 2) + (oz != 0 ? 1 : 0), sf + 1, kz + oz * ((int) rt + 2) + (ox != 0 ? 1 : 0), Gates.spike(Direction.UP));
            }
            // the Sigil Stone on its pedestal (north side), braziers, chests, the Keeper
            int sx = kx, sz = kz - 4;
            d.box(sx - 1, sf + 1, sz - 1, sx + 1, sf + 1, sz + 1, st(RealmBlocks.DAEDRIC_GLYPH_BRICKS));
            d.set(sx, sf + 1, sz, st(RealmBlocks.DEADLANDS_MAGMA_ROCK));
            d.set(sx, sf + 2, sz, st(RealmBlocks.SIGIL_STONE));
            brazier(d, kx - 4, sf, kz - 2);
            brazier(d, kx + 4, sf, kz - 2);
            p.container(kx - 4, sf + 1, kz + 2, Draw.chest(Direction.EAST), RealmLoot.DEADLANDS_SIGIL);
            p.container(kx + 4, sf + 1, kz + 2, Draw.chest(Direction.WEST), RealmLoot.DEADLANDS_SIGIL);
            p.spawn(DREMORA, kx + 2, sf + 1, kz + 3, 180f, "Sigil Keeper", m -> {
                // the tower's master: tougher than any dremora on the island; its death is a story flag
                m.getPersistentData().putBoolean("skycraft_sigil_keeper", true);
                var hp = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                if (hp != null) {
                    hp.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                            java.util.UUID.fromString("5a1d9c3e-4b7f-4e21-9a3d-2f6c8e1b7d40"), "Sigil Keeper", 1.5,
                            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
                    m.setHealth(m.getMaxHealth());
                }
            });
            p.spawn(FLAME_ATRONACH, kx - 2, sf + 1, kz - 1, 0f, null);

            // ---- the crown: curved claws and a burning spire
            for (int i = 0; i < 6; i++) {
                double a = i * Math.PI / 3;
                double ca = Math.cos(a), sa = Math.sin(a);
                d.curve(kx + 0.5 + ca * (rt - 0.5), sf + 6, kz + 0.5 + sa * (rt - 0.5),
                        kx + 0.5 + ca * (rt + 5), sf + 16, kz + 0.5 + sa * (rt + 5),
                        kx + 0.5 + ca * 3.5, sf + 32, kz + 0.5 + sa * 3.5, 1.7, 0.6, Gates.daedric());
            }
            d.cone(kx, kz, 3.5, 0.4, sf + 10, sf + 30, Gates.daedric());
            d.box(kx, sf + 31, kz, kx, sf + 32, kz, st(RealmBlocks.DEADLANDS_MAGMA_ROCK));
            d.set(kx, sf + 33, kz, Blocks.FIRE.defaultBlockState());

            // ---- the causeway to the arrival platform
            int az = kz + RealmLayout.DL_ARRIVAL_DZ;
            int z0 = kz + R_BASE - 1, z1 = az - 6;
            d.footing(kx - 2, z0, kx + 2, z1, fy, 70, rock());
            d.box(kx - 2, fy, z0, kx + 2, fy, z1, floor);
            d.clearAbove(kx - 2, z0, kx + 2, z1, fy + 1, fy + 8);
            for (int z = z0 + 2; z <= z1; z += 4) {
                for (int s = -1; s <= 1; s += 2) {
                    d.footing(kx + 3 * s, z, kx + 3 * s, z, fy, 70, rock());
                    d.set(kx + 3 * s, fy, z, st(RealmBlocks.DAEDRIC_GLYPH_BRICKS));
                    d.set(kx + 3 * s, fy + 1, z, Gates.spike(Direction.UP));
                }
                if ((z - z0) % 12 == 2) {
                    brazier(d, kx - 3, fy, z + 2);
                    brazier(d, kx + 3, fy, z + 2);
                }
            }

            // ---- the arrival platform and the gate home
            d.footing(kx - 7, az - 7, kx + 7, az + 7, fy, 70, rock());
            d.box(kx - 7, fy, az - 7, kx + 7, fy, az + 7, floor);
            d.clearAbove(kx - 7, az - 7, kx + 7, az + 5, fy + 1, fy + 10);
            for (int u = -6; u <= 6; u += 3) d.set(kx + u, fy, az, st(RealmBlocks.DAEDRIC_GLYPH_BRICKS));
            Gates.oblivionArch(d, kx, fy, az + 6, true, 2, 7, true);
            brazier(d, kx - 6, fy, az - 6);
            brazier(d, kx + 6, fy, az - 6);

            // ---- two flanking spires
            for (int s = -1; s <= 1; s += 2) {
                spire(d, p, kx + 27 * s, fy, kz + 10, 4.5, 30, s < 0 ? Direction.EAST : Direction.WEST, RealmLoot.DEADLANDS_SPIRE);
                p.spawn(SCAMP, kx + 27 * s, fy + 1, kz + 10, 0f, null);
            }

            // ---- guards
            p.spawn(DREMORA, kx - 1, fy + 1, kz + 30, 0f, null);
            p.spawn(DREMORA, kx + 1, fy + 1, kz + 40, 0f, null);
            p.spawn(CLANNFEAR, kx + 4, fy + 1, az - 4, 90f, null);
            p.spawn(SCAMP, kx - 4, fy + 1, kz + 14, 0f, null);
        }
    }

    /** A round spire with a door, a cone roof and a chest inside. */
    static void spire(Draw d, Painter p, int cx, int fy, int cz, double r, int h, Direction door, net.minecraft.resources.ResourceLocation loot) {
        d.footing(cx, cz, r + 1, fy, 70, rock());
        d.cylinder(cx, cz, r + 1, fy, fy, Gates.daedric());
        d.tube(cx, cz, r, r - 1.2, fy + 1, fy + h, glyphBand(Gates.daedric(), 7));
        d.clearCylinder(cx, cz, r - 1.2, fy + 1, fy + h - 1);
        d.cylinder(cx, cz, r, fy + h, fy + h, Gates.daedric());
        d.cone(cx, cz, r + 0.5, 0.3, fy + h + 1, fy + h + 14, Gates.daedric());
        d.set(cx, fy + h + 15, cz, Gates.spike(Direction.UP));
        int ox = door.getStepX(), oz = door.getStepZ();
        int ir = (int) Math.round(r);
        for (int y = fy + 1; y <= fy + 3; y++) {
            for (int k = ir - 2; k <= ir + 1; k++) d.p.air(cx + ox * k, y, cz + oz * k);
        }
        // a ring of light and the chest
        d.tube(cx, cz, r - 1.2, r - 2.2, fy + h - 2, fy + h - 2, Draw.Pal.of(st(RealmBlocks.DAEDRIC_GLYPH_BRICKS)));
        p.container(cx - ox, fy + 1, cz - oz, Draw.chest(door), loot);
    }

    // ================================================================================================ citadel

    static final class Citadel implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            // not on a keep's shelf
            int kx = RealmLayout.keepX(RealmLayout.keepCellOf(x)), kz = RealmLayout.keepZ(RealmLayout.keepCellOf(z));
            if (RealmLayout.dist(x, z, kx, kz + 30) < 150) return Optional.empty();
            int[] hs = {RealmSites.ground(ctx, x, z), RealmSites.ground(ctx, x + 14, z + 14), RealmSites.ground(ctx, x - 14, z + 14),
                    RealmSites.ground(ctx, x + 14, z - 14), RealmSites.ground(ctx, x - 14, z - 14)};
            int max = 0, sum = 0;
            for (int h : hs) {
                max = Math.max(max, h);
                sum += h;
            }
            int base = Math.max(46, Math.min(max, sum / hs.length + 4));
            if (base > 150) return Optional.empty();
            return Optional.of(new RealmSites.Start(x, base, z, 0, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 22, 70, 45);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy;
            Draw.Pal wall = glyphBand(Gates.daedric(), 6);
            Draw.Pal floor = Draw.Pal.mix(st(RealmBlocks.DAEDRIC_BRICKS), st(RealmBlocks.DEADLANDS_ROCK), 5);
            int h = 16;
            // ground and walls
            d.footing(cx - 17, cz - 17, cx + 17, cz + 17, fy, 70, rock());
            d.box(cx - 17, fy, cz - 17, cx + 17, fy, cz + 17, floor);
            d.clearAbove(cx - 17, cz - 17, cx + 17, cz + 17, fy + 1, fy + 30);
            d.walls(cx - 16, fy + 1, cz - 16, cx + 16, fy + h, cz + 16, wall);
            d.walls(cx - 15, fy + 1, cz - 15, cx + 15, fy + h, cz + 15, wall);
            // crenellations with thorns
            for (int u = -16; u <= 16; u += 2) {
                for (int[] e : new int[][]{{u, -16}, {u, 16}, {-16, u}, {16, u}}) {
                    d.set(cx + e[0], fy + h + 1, cz + e[1], st(RealmBlocks.DAEDRIC_BRICKS));
                    if (Math.floorMod(u, 4) == 0) d.set(cx + e[0], fy + h + 2, cz + e[1], Gates.spike(Direction.UP));
                }
            }
            // corner towers
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2) {
                    int tx = cx + 16 * sx, tz = cz + 16 * sz;
                    d.footing(tx, tz, 4.5, fy, 70, rock());
                    d.cylinder(tx, tz, 4.5, fy + 1, fy + h + 8, wall);
                    d.clearCylinder(tx, tz, 3.2, fy + 1, fy + h + 7);
                    d.cone(tx, tz, 5, 0.3, fy + h + 9, fy + h + 20, Gates.daedric());
                    d.set(tx, fy + h + 21, tz, Gates.spike(Direction.UP));
                    // inner door to the courtyard
                    d.air(tx - 2 * sx, fy + 1, tz - 2 * sz, tx - 3 * sx, fy + 3, tz - 3 * sz);
                    d.air(tx - 4 * sx, fy + 1, tz - 4 * sz, tx - 4 * sx, fy + 3, tz - 4 * sz);
                }
            // gatehouse on the facing side
            Direction face = Direction.from2DDataValue(piece.facing);
            int gx = cx + face.getStepX() * 16, gz = cz + face.getStepZ() * 16;
            boolean along = face.getAxis() == Direction.Axis.Z;
            for (int y = fy + 1; y <= fy + 7; y++)
                for (int w = -2; w <= 2; w++) {
                    if (y == fy + 7 && Math.abs(w) == 2) continue;
                    for (int t = -1; t <= 1; t++) {
                        int x = along ? gx + w : gx + t * face.getStepX(), z = along ? gz + t * face.getStepZ() : gz + w;
                        d.p.air(x, y, z);
                    }
                }
            for (int w = -2; w <= 2; w++) {
                int x = along ? gx + w : gx + 2 * face.getStepX(), z = along ? gz + 2 * face.getStepZ() : gz + w;
                d.set(x, fy + 8, z, Blocks.IRON_BARS.defaultBlockState());
            }
            for (int s = -1; s <= 1; s += 2) {
                int x = along ? gx + 4 * s : gx + 2 * face.getStepX(), z = along ? gz + 2 * face.getStepZ() : gz + 4 * s;
                d.box(x, fy + 1, z, x, fy + h + 6, z, Gates.daedric());
                d.set(x, fy + h + 7, z, Gates.spike(Direction.UP));
            }
            // the keep in the courtyard
            int kh = 20;
            d.walls(cx - 7, fy + 1, cz - 7, cx + 7, fy + kh, cz + 7, wall);
            d.air(cx - 6, fy + 1, cz - 6, cx + 6, fy + kh - 1, cz + 6);
            d.box(cx - 7, fy + kh, cz - 7, cx + 7, fy + kh, cz + 7, floor);
            d.cone(cx, cz, 7, 0.5, fy + kh + 1, fy + kh + 16, Gates.daedric());
            d.set(cx, fy + kh + 17, cz, Gates.spike(Direction.UP));
            for (int y = fy + 1; y <= fy + 5; y++)
                for (int w = -1; w <= 1; w++) {
                    int x = along ? cx + w : cx + 7 * face.getStepX(), z = along ? cz + 7 * face.getStepZ() : cz + w;
                    d.p.air(x, y, z);
                }
            // inside: a blood fountain, chests, the garrison
            d.box(cx - 1, fy + 1, cz - 1, cx + 1, fy + 1, cz + 1, st(RealmBlocks.DAEDRIC_GLYPH_BRICKS));
            d.set(cx, fy + 1, cz, Blocks.LAVA.defaultBlockState());
            brazier(d, cx - 5, fy, cz - 5);
            brazier(d, cx + 5, fy, cz - 5);
            p.container(cx - 5, fy + 1, cz + 5, Draw.chest(Direction.EAST), RealmLoot.DEADLANDS_CITADEL);
            p.container(cx + 5, fy + 1, cz + 5, Draw.chest(Direction.WEST), RealmLoot.DEADLANDS_CITADEL);
            p.container(cx + 16, fy + 1, cz + 16, Draw.chest(Direction.NORTH), RealmLoot.DEADLANDS_SPIRE);
            p.spawn(DREMORA, cx, fy + 1, cz + 3, 0f, "Dremora Kynreeve");
            p.spawn(DREMORA, cx - 10, fy + 1, cz, 90f, null);
            p.spawn(DREMORA, cx + 10, fy + 1, cz + 2, 270f, null);
            p.spawn(DAEDROTH, cx, fy + 1, cz - 11, 0f, null);
            p.spawn(CLANNFEAR, cx + 8, fy + 1, cz - 10, 0f, null);
            p.spawn(SCAMP, cx - 8, fy + 1, cz + 10, 0f, null);
            // the courtyard outside the keep gets ash and harrada
            for (int x = cx - 14; x <= cx + 14; x++)
                for (int z = cz - 14; z <= cz + 14; z++) {
                    if (Math.abs(x - cx) <= 8 && Math.abs(z - cz) <= 8) continue;
                    if (p.chance(x, fy, z, 5, 7)) d.set(x, fy, z, st(RealmBlocks.DEADLANDS_ASH));
                    else if (p.chance(x, fy, z, 6, 13)) d.set(x, fy + 1, z, st(RealmBlocks.HARRADA));
                }
        }
    }

    // ================================================================================================ spire

    static final class Spire implements RealmSites.Site {
        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            int kx = RealmLayout.keepX(RealmLayout.keepCellOf(x)), kz = RealmLayout.keepZ(RealmLayout.keepCellOf(z));
            if (RealmLayout.dist(x, z, kx, kz + 30) < 110) return Optional.empty();
            int g = RealmSites.ground(ctx, x, z);
            int base = Math.max(45, g);
            if (base > 170) return Optional.empty();
            return Optional.of(new RealmSites.Start(x, base, z, 0, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 9, 70, 60);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            spire(d, p, piece.ox, piece.oy, piece.oz, 4.5, 26 + (int) (Math.abs(piece.seed) % 14),
                    Direction.from2DDataValue(piece.facing), RealmLoot.DEADLANDS_SPIRE);
            p.spawn(SPIDER_DAEDRA, piece.ox + 6, piece.oy + 1, piece.oz, 0f, null);
            p.spawn(SCAMP, piece.ox - 6, piece.oy + 1, piece.oz + 1, 0f, null);
        }
    }

    // ================================================================================================ bridge

    static final class Bridge implements RealmSites.Site {
        static final int HALF = 38;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            boolean alongX = ctx.random().nextBoolean();
            int mid = RealmSites.ground(ctx, x, z);
            if (mid > 42) return Optional.empty(); // only across the lava sea
            int ax = alongX ? HALF : 0, az = alongX ? 0 : HALF;
            int a = RealmSites.ground(ctx, x - ax, z - az), b = RealmSites.ground(ctx, x + ax, z + az);
            if (a < 46 || b < 46) return Optional.empty(); // land at both ends
            int deck = Math.min(110, Math.max(a, b) + 1);
            return Optional.of(new RealmSites.Start(x, deck, z, alongX ? 1 : 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return s.param() == 1 ? RealmSites.around(s, HALF + 6, 4, 80, 10) : RealmSites.around(s, 4, HALF + 6, 80, 10);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            boolean alongX = piece.param == 1;
            int cx = piece.ox, cz = piece.oz, y = piece.oy;
            Draw.Pal deck = Draw.Pal.mix(st(RealmBlocks.DAEDRIC_BRICKS), st(RealmBlocks.DAEDRIC_GLYPH_BRICKS), 11);
            for (int t = -HALF - 4; t <= HALF + 4; t++) {
                for (int w = -2; w <= 2; w++) {
                    int x = alongX ? cx + t : cx + w, z = alongX ? cz + w : cz + t;
                    d.set(x, y, z, deck.at(p, x, y, z));
                    if (Math.abs(t) > HALF) d.column(x, z, y - 1, 30, rock());
                    for (int h = 1; h <= 4; h++) d.p.air(x, y + h, z);
                }
                // thorned railings
                if (Math.floorMod(t, 3) == 0) {
                    for (int s = -1; s <= 1; s += 2) {
                        int x = alongX ? cx + t : cx + 3 * s, z = alongX ? cz + 3 * s : cz + t;
                        d.set(x, y, z, Gates.daedric().at(p, x, y, z));
                        d.set(x, y + 1, z, Gates.spike(Direction.UP));
                    }
                }
            }
            // arches down into the lava every 14 blocks
            for (int t = -HALF + 4; t <= HALF - 4; t += 14) {
                int x = alongX ? cx + t : cx, z = alongX ? cz : cz + t;
                for (int w = -2; w <= 2; w++) {
                    int px = alongX ? x : x + w, pz = alongX ? z + w : z;
                    d.column(px, pz, y - 1, 80, Gates.daedric());
                }
                // the flaring arch under the deck towards the next pillar
                if (t + 14 <= HALF - 4) {
                    for (int s = -2; s <= 2; s += 4) {
                        if (alongX) d.curve(x + 0.5, y - 1, z + s + 0.5, x + 7.5, y - 7, z + s + 0.5, x + 14.5, y - 1, z + s + 0.5, 0.9, 0.9, Gates.daedric());
                        else d.curve(x + s + 0.5, y - 1, z + 0.5, x + s + 0.5, y - 7, z + 7.5, x + s + 0.5, y - 1, z + 14.5, 0.9, 0.9, Gates.daedric());
                    }
                }
                brazier(d, alongX ? x : x - 3, y, alongX ? z - 3 : z);
            }
        }
    }
}
