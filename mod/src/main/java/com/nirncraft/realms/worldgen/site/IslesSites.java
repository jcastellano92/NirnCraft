package com.nirncraft.realms.worldgen.site;

import com.nirncraft.dungeons.world.Painter;
import com.nirncraft.realms.RealmBlocks;
import com.nirncraft.realms.RealmLayout;
import com.nirncraft.realms.block.RealmDoorBlock;
import com.nirncraft.realms.worldgen.RealmPiece;
import com.nirncraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Optional;

import static com.nirncraft.realms.worldgen.site.Gates.st;
import static com.nirncraft.realms.worldgen.site.RealmKit.b;

/**
 * The Shivering Isles' structures. Mania (bright, glowing, many-coloured) is the north half of the isle, Dementia
 * (dark, gnarled, foggy) the south; every landmark wears both.
 * <ul>
 *     <li>{@code isles/fringe}: the Fringe where travellers arrive: a pale silver plaza with twisted trees and standing
 *     stones, the Door home behind the arrival spot, a road west to the Gates of Madness (a great wall with an arched
 *     passage between two giant heads, Mania's and Dementia's) and their Gatekeeper.</li>
 *     <li>{@code isles/new_sheoth}: New Sheoth on its hill: a ring wall with gates to Mania (north), Dementia (south)
 *     and the Fringe road (east); bright Bliss and grim Crucible houses; the Palace of Sheogorath, half bright and
 *     half dark, with the Madgod's shrine in its courtyard.</li>
 *     <li>{@code isles/ruin}: a ruined Mania tower overgrown with crystals and glowing caps, or a sunken, mossy
 *     Dementia shrine.</li>
 * </ul>
 */
final class IslesSites {
    private IslesSites() {}

    static final String FLESH_ATRONACH = "nirncraft:flesh_atronach";
    static final String GRUMMITE = "nirncraft:grummite";
    static final String GNARL = "nirncraft:gnarl";
    static final String ELYTRA = "nirncraft:elytra";
    static final String BALIWOG = "nirncraft:baliwog";

    static void register() {
        RealmSites.add("isles/fringe", new Fringe());
        RealmSites.add("isles/new_sheoth", new NewSheoth());
        RealmSites.add("isles/ruin", new Ruin());
    }

    // ------------------------------------------------------------------ palettes

    /** The Fringe's pale silver stone. */
    static Draw.Pal silver() {
        return RealmKit.weighted(new Block[]{Blocks.CALCITE, Blocks.POLISHED_DIORITE, Blocks.SMOOTH_STONE, Blocks.DIORITE},
                new int[]{7, 4, 2, 2});
    }

    /** Mania's masonry: white stone with purpur and amethyst. */
    static Draw.Pal maniaStone() {
        return RealmKit.weighted(new Block[]{Blocks.CALCITE, Blocks.SMOOTH_QUARTZ, Blocks.PURPUR_BLOCK, Blocks.AMETHYST_BLOCK},
                new int[]{8, 4, 2, 1});
    }

    /** Dementia's masonry: dark bricks, cracked and mossy. */
    static Draw.Pal dementiaStone() {
        return RealmKit.weighted(new Block[]{Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS,
                Blocks.MOSSY_COBBLESTONE}, new int[]{7, 3, 2, 1});
    }

    /** Bright north, dark south of {@code splitZ}. */
    static Draw.Pal madness(int splitZ) {
        return RealmKit.byZ(splitZ, maniaStone(), dementiaStone());
    }

    static BlockState cap(int i) {
        return switch (Math.floorMod(i, 3)) {
            case 0 -> st(RealmBlocks.MANIA_CAP_VIOLET);
            case 1 -> st(RealmBlocks.MANIA_CAP_TEAL);
            default -> st(RealmBlocks.MANIA_CAP_AMBER);
        };
    }

    // ================================================================================================ the Fringe

    static final class Fringe implements RealmSites.Site {
        static final int PLAZA_R = 11;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = RealmLayout.SI_FRINGE_X;
            int y = RealmSites.ground(ctx, x, 0);
            return Optional.of(new RealmSites.Start(x, Mth.clamp(y, 66, 120), 0, 0, 1));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return new BoundingBox(RealmLayout.SI_GATES_X - 14, s.y() - 24, -40, s.x() + 28, s.y() + 40, 40);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int ax = piece.ox, az = piece.oz, fy = piece.oy - 1;
            int gx = RealmLayout.SI_GATES_X;
            Draw.Pal silver = silver();

            // ---- the plaza: rings of pale stone around the arrival spot, ragged where it meets the turf
            for (int x = Math.max(ax - PLAZA_R - 2, p.minX()); x <= Math.min(ax + PLAZA_R + 2, p.maxX()); x++)
                for (int z = Math.max(az - PLAZA_R - 2, p.minZ()); z <= Math.min(az + PLAZA_R + 2, p.maxZ()); z++) {
                    double e = Math.sqrt((x - ax) * (x - ax) + (z - az) * (z - az) * 1.3) / PLAZA_R;
                    double edge = 1.0 + p.noise(x, 0, z, 4, 3) * 0.12;
                    if (e > edge) continue;
                    if (e > edge - 0.15 && p.chance(x, fy, z, 4, 3)) continue;
                    d.column(x, z, fy, 14, silver);
                    BlockState top;
                    if (e < 0.12) top = b(Blocks.CHISELED_QUARTZ_BLOCK);
                    else if (Math.abs(e - 0.45) < 0.06 || Math.abs(e - 0.8) < 0.05) top = b(Blocks.POLISHED_ANDESITE);
                    else if (x == ax || z == az) top = b(Blocks.SMOOTH_QUARTZ);
                    else top = silver.at(p, x, fy, z);
                    p.set(x, fy, z, top);
                    for (int y = fy + 1; y <= fy + 10; y++) p.air(x, y, z);
                }
            p.set(ax, fy, az, b(Blocks.CHISELED_QUARTZ_BLOCK));

            // ---- the road west to the Gates, with pale lamps
            for (int x = Math.max(gx - 12, p.minX()); x <= Math.min(ax - PLAZA_R + 2, p.maxX()); x++)
                for (int z = Math.max(-2, p.minZ()); z <= Math.min(2, p.maxZ()); z++) {
                    if (Math.abs(z) == 2 && !p.chance(x, fy, z, 5, 3)) continue;
                    d.column(x, z, fy, 10, silver);
                    for (int y = fy + 1; y <= fy + 5; y++) p.air(x, y, z);
                }
            for (int x = gx + 10, i = 0; x <= ax - PLAZA_R - 2; x += 12, i++) {
                int z = i % 2 == 0 ? -3 : 3;
                d.column(x, z, fy, 10, silver);
                d.box(x, fy + 1, z, x, fy + 3, z, b(Blocks.CALCITE));
                d.set(x, fy + 4, z, b(Blocks.PEARLESCENT_FROGLIGHT));
            }

            // ---- twisted pale trees and standing stones around the plaza (never on the road or behind the door)
            for (int i = 0; i < 10; i++) {
                long h = RealmSites.hash(piece.seed, i, 91);
                double a = i * (Math.PI * 2 / 10) + ((h & 255) / 255.0 - 0.5) * 0.5;
                double sa = Math.sin(a), ca = Math.cos(a);
                if (Math.abs(sa) < 0.35) continue;
                double r = 14 + (h >>> 8 & 5);
                int tx = (int) Math.round(ax + ca * r), tz = (int) Math.round(az + sa * r * 0.95);
                if ((h >>> 12 & 3) == 0) standingStone(d, tx, fy, tz, h);
                else paleTree(d, tx, fy, tz, h);
            }

            // ---- the Door home, six blocks behind the arrival spot
            door(d, ax + 6, fy, az);

            // ---- the Gates of Madness
            gates(d, p, gx, fy, piece.seed);
        }

        /** A twisted silver-barked tree with a thin crown and crystal buds. */
        static void paleTree(Draw d, int x, int fy, int z, long h) {
            int ht = 6 + (int) (h >>> 16 & 3);
            double lx = (h >>> 20 & 7) - 3.5, lz = (h >>> 24 & 7) - 3.5;
            Draw.Pal wood = Draw.Pal.of(b(Blocks.STRIPPED_BIRCH_WOOD));
            Draw.Pal crown = Draw.Pal.mix(RealmKit.leaves(Blocks.BIRCH_LEAVES), RealmKit.leaves(Blocks.CHERRY_LEAVES), 9);
            d.column(x, z, fy, 6, wood);
            double tx = x + 0.5 + lx * 0.5, tz = z + 0.5 + lz * 0.5;
            for (int k = 0; k < 3; k++) {
                double ba = k * 2.1 + (h & 15) * 0.4;
                double bx = tx + Math.cos(ba) * 3.5, bz = tz + Math.sin(ba) * 3.5;
                d.blob((int) Math.floor(bx), fy + ht + 2 - k, (int) Math.floor(bz), 1.7, 1.0, 1.7, crown);
            }
            d.curve(x + 0.5, fy + 0.5, z + 0.5, x + 0.5 - lx * 0.6, fy + ht * 0.5, z + 0.5 - lz * 0.6, tx, fy + ht, tz, 1.0, 0.55, wood);
            for (int k = 0; k < 3; k++) {
                double ba = k * 2.1 + (h & 15) * 0.4;
                double bx = tx + Math.cos(ba) * 3.5, bz = tz + Math.sin(ba) * 3.5;
                d.curve(tx, fy + ht, tz, tx + Math.cos(ba + 0.9) * 2, fy + ht + 2.5, tz + Math.sin(ba + 0.9) * 2,
                        bx, fy + ht + 1.5 - k * 0.8, bz, 0.5, 0.4, wood);
                d.set((int) Math.floor(bx), fy + ht + 4 - k, (int) Math.floor(bz),
                        RealmKit.cluster(BiomeBlocks.CRYSTAL_CLUSTER.get(), Direction.UP));
            }
        }

        /** A leaning, twisted menhir. */
        static void standingStone(Draw d, int x, int fy, int z, long h) {
            int ht = 5 + (int) (h >>> 16 & 3);
            double sx = (h >>> 20 & 3) - 1.5, sz = (h >>> 22 & 3) - 1.5;
            Draw.Pal stone = Draw.Pal.mix(b(Blocks.DIORITE), b(Blocks.CALCITE), 3);
            d.column(x, z, fy, 6, stone);
            d.curve(x + 0.5, fy, z + 0.5, x + 0.5 + sx, fy + ht * 0.5, z + 0.5 - sz, x + 0.5 - sx * 0.5, fy + ht, z + 0.5 + sz * 0.5,
                    1.3, 0.6, stone);
        }

        /** The Door to the Shivering Isles' way home: a carved frame, bright on its Mania side, dark on Dementia's. */
        static void door(Draw d, int dx, int fy, int dz) {
            d.box(dx - 1, fy, dz - 2, dx + 1, fy, dz + 2, b(Blocks.POLISHED_DIORITE));
            d.box(dx, fy + 1, dz - 1, dx, fy + 3, dz - 1, b(Blocks.PURPUR_PILLAR));
            d.box(dx, fy + 1, dz + 1, dx, fy + 3, dz + 1, b(Blocks.POLISHED_BLACKSTONE));
            d.set(dx, fy + 3, dz, b(Blocks.CHISELED_QUARTZ_BLOCK));
            d.set(dx, fy + 4, dz - 1, b(Blocks.PURPUR_BLOCK));
            d.set(dx, fy + 4, dz + 1, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
            d.set(dx, fy + 4, dz, b(Blocks.AMETHYST_BLOCK));
            d.set(dx, fy + 5, dz, b(Blocks.CRYING_OBSIDIAN));
            // a curling flourish on each side
            d.curve(dx + 0.5, fy + 4.5, dz - 0.5, dx + 0.5, fy + 7.5, dz - 1.5, dx + 0.5, fy + 6.5, dz - 3.5, 0.6, 0.5,
                    Draw.Pal.of(b(Blocks.PURPUR_BLOCK)));
            d.blob(dx, fy + 7, dz - 4, 1.2, 0.8, 1.2, Draw.Pal.of(st(RealmBlocks.MANIA_CAP_TEAL)));
            d.curve(dx + 0.5, fy + 4.5, dz + 1.5, dx + 0.5, fy + 7.5, dz + 2.5, dx + 0.5, fy + 6.5, dz + 4.5, 0.6, 0.5,
                    Draw.Pal.of(b(Blocks.POLISHED_BLACKSTONE)));
            d.set(dx, fy + 7, dz + 4, b(Blocks.CRYING_OBSIDIAN));
            BlockState door = st(RealmBlocks.MADNESS_DOOR).setValue(RealmDoorBlock.FACING, Direction.WEST).setValue(RealmDoorBlock.RETURN, true);
            d.set(dx, fy + 1, dz, door.setValue(RealmDoorBlock.HALF, DoubleBlockHalf.LOWER));
            d.set(dx, fy + 2, dz, door.setValue(RealmDoorBlock.HALF, DoubleBlockHalf.UPPER));
        }

        /** The great wall across the Fringe, its arched passage and the two heads that watch it. */
        static void gates(Draw d, Painter p, int gx, int fy, long seed) {
            int top = fy + 14;
            Draw.Pal wall = RealmKit.byZ(0, RealmKit.banded(maniaStone(), Draw.Pal.of(b(Blocks.PURPUR_BLOCK)), 5, fy),
                    RealmKit.banded(dementiaStone(), Draw.Pal.of(b(Blocks.CHISELED_DEEPSLATE)), 5, fy));
            d.footing(gx - 1, -31, gx + 1, 31, fy, 20, wall);
            d.box(gx - 1, fy + 1, -31, gx + 1, top, 31, wall);
            for (int z = -31; z <= 31; z += 2) {
                d.set(gx - 1, top + 1, z, wall.at(p, gx - 1, top + 1, z));
                d.set(gx + 1, top + 1, z, wall.at(p, gx + 1, top + 1, z));
            }
            // glowing eyes in the wall's face, every few blocks
            for (int z = -28; z <= 28; z += 7) {
                if (Math.abs(z) < 12) continue;
                d.set(gx + 1, fy + 10, z, z < 0 ? b(Blocks.OCHRE_FROGLIGHT) : b(Blocks.CRYING_OBSIDIAN));
            }
            // the gate towers and the end towers
            for (int s = -1; s <= 1; s += 2) {
                d.footing(gx, 8 * s, 4.5, fy, 20, wall);
                d.cylinder(gx, 8 * s, 4.5, fy + 1, fy + 16, wall);
                d.footing(gx, 32 * s, 2.5, fy, 20, wall);
                d.cylinder(gx, 32 * s, 2.5, fy + 1, top + 3, wall);
                d.cone(gx, 32 * s, 3.0, 0.4, top + 4, top + 9, s < 0 ? Draw.Pal.of(b(Blocks.PURPUR_BLOCK)) : Draw.Pal.of(b(Blocks.BLACKSTONE)));
                d.set(gx, top + 10, 32 * s, s < 0 ? st(RealmBlocks.MANIA_CAP_AMBER) : b(Blocks.CRYING_OBSIDIAN));
            }
            // the vault over the passage, then the passage itself (a round-headed arch)
            d.box(gx - 4, fy + 9, -3, gx + 4, top, 3, wall);
            for (int z = -3; z <= 3; z++) {
                int h = 9 - (Math.abs(z) == 3 ? 2 : Math.abs(z) == 2 ? 1 : 0);
                d.air(gx - 5, fy + 1, z, gx + 5, fy + h, z);
                for (int x = gx - 6; x <= gx + 6; x++) d.column(x, z, fy, 10, silver());
            }
            // keystones over both mouths
            d.set(gx + 4, fy + 9, 0, b(Blocks.AMETHYST_BLOCK));
            d.set(gx - 4, fy + 9, 0, b(Blocks.AMETHYST_BLOCK));

            head(d, gx, fy + 16, -8, true);
            head(d, gx, fy + 16, 8, false);

            // the Gatekeeper and the bones of those it turned away
            p.spawn(FLESH_ATRONACH, gx + 7, fy + 1, 0, Painter.yaw(Direction.EAST), "Gatekeeper");
            for (int i = 0; i < 9; i++) {
                long h = RealmSites.hash(seed, i, 37);
                int x = gx + 3 + (int) (h & 7), z = (int) ((h >>> 3) % 13) - 6;
                if (Math.abs(z) <= 2 && x < gx + 6) continue;
                if (!p.ownsColumn(x, z)) continue;
                int y = p.surfaceAt(x, z);
                if (y < fy - 2 || y > fy + 3) continue;
                if ((h >>> 8 & 3) == 0) p.set(x, y, z, RealmKit.skull(Blocks.SKELETON_SKULL, (int) (h >>> 10)));
                else if ((h >>> 8 & 3) == 1) p.set(x, y, z, b(Blocks.BONE_BLOCK));
            }
        }

        /** Surface x of a head's face (+x) at a given offset from its centre. */
        static int face(int hx, double rx, double ry, double rz, double dy, double dz) {
            double t = 1 - (dy / ry) * (dy / ry) - (dz / rz) * (dz / rz);
            return hx + (int) Math.floor(rx * Math.sqrt(Math.max(0, t)));
        }

        /** A giant head on its neck, looking east: Mania's grinning and crowned with caps, Dementia's frowning and horned. */
        static void head(Draw d, int hx, int base, int hz, boolean mania) {
            Draw.Pal skin = mania ? RealmKit.weighted(new Block[]{Blocks.SMOOTH_QUARTZ, Blocks.CALCITE}, new int[]{5, 3})
                    : RealmKit.weighted(new Block[]{Blocks.POLISHED_BLACKSTONE, Blocks.DEEPSLATE_TILES, Blocks.BLACKSTONE}, new int[]{5, 3, 1});
            Draw.Pal hair = mania ? (pp, x, y, z) -> cap(pp.roll(x, y, z, 5, 3))
                    : RealmKit.weighted(new Block[]{Blocks.BLACKSTONE, Blocks.COBBLED_DEEPSLATE, Blocks.CRYING_OBSIDIAN}, new int[]{4, 3, 1});
            double rx = 4.0, ry = 6.0, rz = 4.6;
            int cy = base + 7;
            d.cylinder(hx, hz, 2.6, base, cy - 4, skin);
            d.blob(hx - 1, cy + 1, hz, rx, ry, rz + 0.4, hair);
            d.blob(hx, cy, hz, rx, ry, rz, skin);
            BlockState eye = mania ? b(Blocks.OCHRE_FROGLIGHT) : b(Blocks.CRYING_OBSIDIAN);
            BlockState brow = mania ? b(Blocks.PURPUR_BLOCK) : b(Blocks.POLISHED_BLACKSTONE_BRICKS);
            for (int s = -1; s <= 1; s += 2) {
                int ez = hz + 2 * s;
                d.set(face(hx, rx, ry, rz, 1, 2 * s), cy + 1, ez, eye);
                // brows: raised high for Mania, knitted down into a scowl for Dementia
                int outer = cy + 3, inner = mania ? cy + 3 : cy + 2;
                d.set(face(hx, rx, ry, rz, outer - cy, 2 * s) + 1, outer, ez, brow);
                d.set(face(hx, rx, ry, rz, inner - cy, s) + 1, inner, hz + s, brow);
            }
            // the nose
            d.set(face(hx, rx, ry, rz, 0, 0) + 1, cy, hz, skin.at(d.p, hx, cy, hz));
            d.set(face(hx, rx, ry, rz, -1, 0) + 1, cy - 1, hz, skin.at(d.p, hx, cy - 1, hz));
            // the mouth: a gold-toothed grin or a black frown
            int[][] mouth = mania ? new int[][]{{-2, -2}, {-1, -3}, {0, -3}, {1, -3}, {2, -2}}
                    : new int[][]{{-2, -4}, {-1, -3}, {0, -3}, {1, -3}, {2, -4}};
            BlockState lips = mania ? b(Blocks.GOLD_BLOCK) : b(Blocks.OBSIDIAN);
            for (int[] m : mouth) d.set(face(hx, rx, ry, rz, m[1], m[0]), cy + m[1], hz + m[0], lips);
            if (mania) {
                // a jester's crown of fungal horns
                for (int s = -1; s <= 1; s++) {
                    double tx = hx - 1.5 + 0.5, tz = hz + s * 4.5 + 0.5;
                    d.curve(hx - 0.5, cy + ry - 1, hz + s * 2 + 0.5, hx + 0.5, cy + ry + 3, hz + s * 3 + 0.5, tx, cy + ry + 5, tz,
                            0.9, 0.6, Draw.Pal.of(st(RealmBlocks.MANIA_STEM)));
                    d.blob((int) Math.floor(tx), (int) (cy + ry + 5), (int) Math.floor(tz), 1.8, 1.0, 1.8, Draw.Pal.of(cap(s + 1)));
                }
            } else {
                // two horns sweeping forwards
                for (int s = -1; s <= 1; s += 2) {
                    d.curve(hx + 0.5, cy + 4, hz + s * 3 + 0.5, hx + 1.5, cy + 9, hz + s * 6 + 0.5, hx + 3.5, cy + 10, hz + s * 4 + 0.5,
                            1.1, 0.4, Draw.Pal.of(b(Blocks.BLACKSTONE)));
                }
            }
        }
    }

    // ================================================================================================ New Sheoth

    static final class NewSheoth implements RealmSites.Site {
        static final int WALL_R = 104;
        static final int COURT = 34;
        static final int KX = 16, KZ0 = -14, KZ1 = 8, KH = 18;
        /** Bliss north of this line, Crucible south (the keep's middle). */
        static final int SPLIT = -3;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int y = RealmSites.ground(ctx, 0, 0);
            return Optional.of(new RealmSites.Start(0, Mth.clamp(y, 80, 120), 0, 0, 0));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, WALL_R + 8, 24, 70);
        }

        static boolean street(int x, int z) {
            if (Math.abs(x) <= 3 && Math.abs(z) <= WALL_R + 2) return true;
            if (x >= COURT && x <= WALL_R + 2 && Math.abs(z) <= 3) return true;
            // the square road around the palace courtyard
            return Math.abs(Math.max(Math.abs(x), Math.abs(z)) - 38) <= 2;
        }

        static Draw.Pal streetPal(boolean bliss) {
            return bliss ? RealmKit.weighted(new Block[]{Blocks.SMOOTH_STONE, Blocks.CALCITE, Blocks.POLISHED_ANDESITE, Blocks.PURPUR_BLOCK},
                    new int[]{5, 4, 3, 1})
                    : RealmKit.weighted(new Block[]{Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.GRAVEL, Blocks.COARSE_DIRT,
                    Blocks.PACKED_MUD}, new int[]{5, 3, 2, 2, 2});
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int fy = piece.oy - 1;

            // ---- streets and ground cover, column by column
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    double r = Math.sqrt(x * x + z * z);
                    if (r > WALL_R + 3) continue;
                    boolean bliss = z < SPLIT;
                    if (Math.abs(x) <= COURT && Math.abs(z) <= COURT) continue;
                    if (street(x, z)) {
                        d.column(x, z, fy, 6, streetPal(bliss));
                        for (int y = fy + 1; y <= fy + 5; y++) p.air(x, y, z);
                        continue;
                    }
                    if (r > WALL_R - 3) continue;
                    int top = p.surfaceAt(x, z) - 1;
                    if (top < fy - 3 || top > fy + 3) continue;
                    if (bliss) {
                        if (p.chance(x, top, z, 7, 9) && p.get(x, top + 1, z).isAir()) p.set(x, top + 1, z, st(RealmBlocks.MANIA_BLOOM));
                    } else {
                        if (p.chance(x, top, z, 8, 6)) p.set(x, top, z, p.chance(x, top, z, 9, 2) ? b(Blocks.COARSE_DIRT) : b(Blocks.MUD));
                        else if (p.chance(x, top, z, 10, 13) && p.get(x, top + 1, z).isAir()) p.set(x, top + 1, z, st(RealmBlocks.DEMENTIA_BULB));
                    }
                }

            courtyard(d, p, fy);
            keep(d, p, fy);
            shrine(d, fy, 21);
            ringWall(d, p, fy);

            // ---- the houses: a jittered grid of plots between the palace ring street and the wall
            for (int gx = -96; gx <= 96; gx += 16)
                for (int gz = -96; gz <= 96; gz += 16) {
                    long h = RealmSites.hash(piece.seed, gx, gz);
                    int hx = gx + (int) (h & 7) - 3, hz = gz + (int) (h >>> 3 & 7) - 3;
                    double r = Math.sqrt(hx * hx + hz * hz);
                    if (r > 90 || r < 51) continue;
                    if (Math.abs(hx) < 11 || Math.max(Math.abs(hx), Math.abs(hz)) < 47) continue;
                    if (hx > 0 && Math.abs(hz) < 11) continue;
                    if (Math.abs(hz - SPLIT) < 5) continue;
                    if ((h >>> 20) % 9 == 0) continue; // a garden or a yard
                    house(d, p, hx, hz, fy, h);
                }

            // ---- lamps along the avenue: glowing caps in Bliss, lanterns in the Crucible
            for (int z = -WALL_R + 10; z <= WALL_R - 10; z += 12) {
                if (Math.abs(z) <= COURT + 6) continue;
                for (int s = -1; s <= 1; s += 2) {
                    int x = 4 * s;
                    d.column(x, z, fy, 6, streetPal(z < SPLIT));
                    if (z < SPLIT) {
                        d.box(x, fy + 1, z, x, fy + 3, z, b(Blocks.STRIPPED_WARPED_STEM));
                        d.set(x, fy + 4, z, cap(z / 12 + s));
                    } else {
                        d.box(x, fy + 1, z, x, fy + 2, z, b(Blocks.DARK_OAK_LOG));
                        d.set(x, fy + 3, z, b(Blocks.LANTERN));
                    }
                }
            }
        }

        // ------------------------------------------------------------------ the palace courtyard

        static void courtyard(Draw d, Painter p, int fy) {
            for (int x = Math.max(-COURT, p.minX()); x <= Math.min(COURT, p.maxX()); x++)
                for (int z = Math.max(-COURT, p.minZ()); z <= Math.min(COURT, p.maxZ()); z++) {
                    boolean bliss = z < SPLIT;
                    int ax = Math.abs(x), az = Math.abs(z);
                    boolean bed = ax >= 22 && ax <= 30 && az >= 22 && az <= 30;
                    boolean tile = ((x >> 1) + (z >> 1) & 1) == 0;
                    BlockState top = bliss ? (tile ? b(Blocks.SMOOTH_QUARTZ) : b(Blocks.PURPUR_BLOCK))
                            : (tile ? b(Blocks.POLISHED_BLACKSTONE) : b(Blocks.POLISHED_DEEPSLATE));
                    if (bed) {
                        boolean rim = ax == 22 || ax == 30 || az == 22 || az == 30;
                        top = rim ? (bliss ? b(Blocks.CALCITE) : b(Blocks.MOSSY_STONE_BRICKS)) : bliss ? b(Blocks.GRASS_BLOCK) : b(Blocks.MOSS_BLOCK);
                    }
                    d.column(x, z, fy, 8, Draw.Pal.of(bliss ? b(Blocks.STONE_BRICKS) : b(Blocks.DEEPSLATE_BRICKS)));
                    p.set(x, fy, z, top);
                    for (int y = fy + 1; y <= fy + 8; y++) p.air(x, y, z);
                    if (bed && !(ax == 22 || ax == 30 || az == 22 || az == 30) && p.chance(x, fy, z, 12, 2)) {
                        p.set(x, fy + 1, z, bliss ? st(RealmBlocks.MANIA_BLOOM) : st(RealmBlocks.DEMENTIA_BULB));
                    }
                }
            // a giant cap in each Bliss bed, a dead tree hung with lanterns in each Crucible bed
            for (int s = -1; s <= 1; s += 2) {
                int x = 26 * s;
                d.box(x, fy + 1, -26, x, fy + 6, -26, st(RealmBlocks.MANIA_STEM));
                d.dome(x, fy + 6, -26, 3.5, 2.5, 3.5, 1.0, Draw.Pal.of(cap(s + 1)));
                d.box(x, fy + 1, 26, x, fy + 5, 26, b(Blocks.DARK_OAK_LOG));
                d.line(x + 0.5, fy + 5, 26.5, x + 0.5 + 2.5 * s, fy + 7, 26.5, 0.5, Draw.Pal.of(b(Blocks.DARK_OAK_LOG)));
                d.line(x + 0.5, fy + 4, 26.5, x + 0.5 - 2 * s, fy + 6, 24.5, 0.5, Draw.Pal.of(b(Blocks.DARK_OAK_LOG)));
                d.set(x + 2 * s, fy + 6, 26, RealmKit.hanging(Blocks.SOUL_LANTERN));
            }
            // the courtyard wall with gates north, south and east
            Draw.Pal wall = madness(SPLIT);
            d.walls(-COURT, fy + 1, -COURT, COURT, fy + 5, COURT, wall);
            for (int u = -COURT; u <= COURT; u += 2) {
                for (int[] e : new int[][]{{u, -COURT}, {u, COURT}, {-COURT, u}, {COURT, u}}) {
                    d.set(e[0], fy + 6, e[1], wall.at(p, e[0], fy + 6, e[1]));
                }
            }
            d.air(-2, fy + 1, -COURT, 2, fy + 4, -COURT);
            d.air(-2, fy + 1, COURT, 2, fy + 4, COURT);
            d.air(COURT, fy + 1, -2, COURT, fy + 4, 2);
            for (int s = -1; s <= 1; s += 2) {
                d.set(3 * s, fy + 6, -COURT, st(RealmBlocks.MANIA_CAP_TEAL));
                d.set(3 * s, fy + 6, COURT, b(Blocks.SOUL_LANTERN));
            }
        }

        // ------------------------------------------------------------------ the Palace of Sheogorath

        static Draw.Pal keepPal() {
            Draw.Pal bright = RealmKit.banded(RealmKit.weighted(new Block[]{Blocks.CALCITE, Blocks.SMOOTH_QUARTZ}, new int[]{3, 2}),
                    Draw.Pal.of(b(Blocks.PURPUR_BLOCK)), 6, 0);
            Draw.Pal dark = RealmKit.banded(RealmKit.weighted(new Block[]{Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS,
                    Blocks.CRACKED_DEEPSLATE_BRICKS}, new int[]{5, 3, 1}), Draw.Pal.of(b(Blocks.CHISELED_DEEPSLATE)), 6, 0);
            return RealmKit.byZ(SPLIT, bright, dark);
        }

        static void keep(Draw d, Painter p, int fy) {
            Draw.Pal pal = keepPal();
            int x0 = -KX, x1 = KX;
            d.footing(x0 - 1, KZ0 - 1, x1 + 1, KZ1 + 1, fy, 12, pal);
            d.walls(x0, fy + 1, KZ0, x1, fy + KH, KZ1, pal);
            d.box(x0, fy + KH, KZ0, x1, fy + KH, KZ1, pal);
            // buttresses and battlements
            for (int x = x0; x <= x1; x += 4) {
                if (Math.abs(x) <= 2) continue; // the doors
                for (int z : new int[]{KZ0 - 1, KZ1 + 1}) {
                    d.box(x, fy + 1, z, x, fy + KH + 1, z, z < SPLIT ? b(Blocks.PURPUR_PILLAR) : b(Blocks.POLISHED_BASALT));
                }
            }
            for (int z = KZ0; z <= KZ1; z += 4) {
                for (int x : new int[]{x0 - 1, x1 + 1}) {
                    d.box(x, fy + 1, z, x, fy + KH + 1, z, z < SPLIT ? b(Blocks.PURPUR_PILLAR) : b(Blocks.POLISHED_BASALT));
                }
            }
            for (int x = x0; x <= x1; x += 2) {
                d.set(x, fy + KH + 1, KZ0, pal.at(p, x, fy + KH + 1, KZ0));
                d.set(x, fy + KH + 1, KZ1, pal.at(p, x, fy + KH + 1, KZ1));
            }
            for (int z = KZ0; z <= KZ1; z += 2) {
                d.set(x0, fy + KH + 1, z, pal.at(p, x0, fy + KH + 1, z));
                d.set(x1, fy + KH + 1, z, pal.at(p, x1, fy + KH + 1, z));
            }
            // tall windows between the buttresses
            for (int x = x0 + 2; x < x1; x += 4) {
                for (int z : new int[]{KZ0, KZ1}) {
                    d.box(x, fy + 5, z, x, fy + 11, z, z < SPLIT ? b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_STAINED_GLASS) : b(com.nirncraft.worldgen.VanillaBlocks.BLACK_STAINED_GLASS));
                }
            }
            for (int z = KZ0 + 2; z < KZ1; z += 4) {
                for (int x : new int[]{x0, x1}) {
                    d.box(x, fy + 5, z, x, fy + 11, z, z < SPLIT ? b(com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS) : b(com.nirncraft.worldgen.VanillaBlocks.GRAY_STAINED_GLASS));
                }
            }
            // corner towers with bright and dark cones
            for (int sx = -1; sx <= 1; sx += 2)
                for (int z : new int[]{KZ0, KZ1}) {
                    int tx = KX * sx;
                    boolean bright = z < SPLIT;
                    d.footing(tx, z, 4, fy, 12, pal);
                    d.cylinder(tx, z, 4, fy + 1, fy + 28, pal);
                    d.tube(tx, z, 4.6, 3.4, fy + 28, fy + 29, pal);
                    d.cone(tx, z, 4.6, 0.4, fy + 30, fy + 42, bright
                            ? RealmKit.weighted(new Block[]{Blocks.PURPUR_BLOCK, com.nirncraft.worldgen.VanillaBlocks.MAGENTA_TERRACOTTA, Blocks.AMETHYST_BLOCK}, new int[]{4, 3, 1})
                            : RealmKit.weighted(new Block[]{Blocks.BLACKSTONE, Blocks.DEEPSLATE_TILES}, new int[]{3, 2}));
                    d.set(tx, fy + 43, z, bright ? b(Blocks.OCHRE_FROGLIGHT) : b(Blocks.CRYING_OBSIDIAN));
                    for (int y = fy + 12; y <= fy + 24; y += 6) {
                        d.set(tx + 4 * sx, y, z, bright ? b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_STAINED_GLASS) : b(com.nirncraft.worldgen.VanillaBlocks.BLACK_STAINED_GLASS));
                    }
                }
            // the great tower over the throne room, split down the middle
            int tz = SPLIT;
            d.cylinder(0, tz, 6, fy + KH + 1, fy + 44, pal);
            for (int y = fy + KH + 6; y <= fy + 40; y += 7) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    d.box(6 * dir.getStepX(), y, tz + 6 * dir.getStepZ(), 6 * dir.getStepX(), y + 3, tz + 6 * dir.getStepZ(),
                            tz + 6 * dir.getStepZ() < SPLIT ? b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_STAINED_GLASS) : b(com.nirncraft.worldgen.VanillaBlocks.BLACK_STAINED_GLASS));
                }
            }
            d.cylinder(0, tz, 7.5, fy + 44, fy + 44, pal);
            d.tube(0, tz, 7.5, 6.5, fy + 45, fy + 45, pal);
            d.cone(0, tz, 6.5, 0.5, fy + 46, fy + 64, RealmKit.byZ(SPLIT,
                    RealmKit.weighted(new Block[]{Blocks.PURPUR_BLOCK, com.nirncraft.worldgen.VanillaBlocks.MAGENTA_TERRACOTTA, Blocks.AMETHYST_BLOCK}, new int[]{4, 3, 1}),
                    RealmKit.weighted(new Block[]{Blocks.BLACKSTONE, Blocks.DEEPSLATE_TILES, Blocks.CRYING_OBSIDIAN}, new int[]{4, 3, 1})));
            d.set(0, fy + 65, tz, b(Blocks.GOLD_BLOCK));
            d.box(0, fy + 66, tz, 0, fy + 67, tz, b(Blocks.END_ROD));

            // ---- the throne room
            d.air(x0 + 1, fy + 1, KZ0 + 1, x1 - 1, fy + KH - 1, KZ1 - 1);
            for (int x = Math.max(x0 + 1, p.minX()); x <= Math.min(x1 - 1, p.maxX()); x++)
                for (int z = Math.max(KZ0 + 1, p.minZ()); z <= Math.min(KZ1 - 1, p.maxZ()); z++) {
                    boolean tile = (x + z & 1) == 0;
                    p.set(x, fy, z, z < SPLIT ? (tile ? b(Blocks.CALCITE) : b(Blocks.PURPUR_BLOCK))
                            : (tile ? b(Blocks.POLISHED_BLACKSTONE) : b(Blocks.POLISHED_DEEPSLATE)));
                }
            for (int z = KZ0 + 5; z <= KZ1 - 2; z += 4)
                for (int s = -1; s <= 1; s += 2) {
                    d.box(8 * s, fy + 1, z, 8 * s, fy + KH - 1, z, z < SPLIT ? b(Blocks.QUARTZ_PILLAR) : b(Blocks.POLISHED_BASALT));
                }
            // lights: froglights in the bright ceiling, soul lanterns hanging in the dark
            for (int x = -8; x <= 8; x += 8)
                for (int z = KZ0 + 3; z < KZ1; z += 4) {
                    if (z < SPLIT) d.set(x, fy + KH, z, x == 0 ? b(Blocks.PEARLESCENT_FROGLIGHT) : b(Blocks.OCHRE_FROGLIGHT));
                    else d.set(x, fy + KH - 1, z, RealmKit.hanging(Blocks.SOUL_LANTERN));
                }
            // doors north and south
            for (int z : new int[]{KZ0, KZ1}) {
                for (int x = -2; x <= 2; x++) d.air(x, fy + 1, z, x, fy + (Math.abs(x) == 2 ? 5 : 6), z);
            }
            // the runner from the south door to the dais
            for (int z = KZ0 + 5; z <= KZ1 - 1; z++) {
                d.set(0, fy + 1, z, b(com.nirncraft.worldgen.VanillaBlocks.PURPLE_CARPET));
                d.set(-1, fy + 1, z, z < SPLIT ? b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_CARPET) : b(com.nirncraft.worldgen.VanillaBlocks.BLACK_CARPET));
                d.set(1, fy + 1, z, z < SPLIT ? b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_CARPET) : b(com.nirncraft.worldgen.VanillaBlocks.BLACK_CARPET));
            }
            // the dais and the throne: half Mania, half Dementia
            d.box(-5, fy + 1, KZ0 + 1, 5, fy + 1, KZ0 + 4, b(Blocks.SMOOTH_QUARTZ));
            d.box(-3, fy + 2, KZ0 + 1, 3, fy + 2, KZ0 + 3, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
            int ty = fy + 3, tzz = KZ0 + 2;
            d.set(0, ty, tzz, RealmKit.stairs(Blocks.PURPUR_STAIRS, Direction.NORTH));
            d.set(-1, ty, tzz, b(Blocks.PURPUR_BLOCK));
            d.set(1, ty, tzz, b(Blocks.POLISHED_BLACKSTONE));
            d.box(-1, ty, tzz - 1, -1, ty + 4, tzz - 1, b(Blocks.PURPUR_PILLAR));
            d.box(1, ty, tzz - 1, 1, ty + 4, tzz - 1, b(Blocks.POLISHED_BLACKSTONE_BRICKS));
            d.box(0, ty, tzz - 1, 0, ty + 3, tzz - 1, b(Blocks.AMETHYST_BLOCK));
            d.set(0, ty + 4, tzz - 1, b(Blocks.GOLD_BLOCK));
            d.set(-1, ty + 5, tzz - 1, st(RealmBlocks.MANIA_CAP_AMBER));
            d.set(1, ty + 5, tzz - 1, b(Blocks.CRYING_OBSIDIAN));
            // the Madgod's treasures
            p.container(-4, fy + 2, KZ0 + 2, Draw.chest(Direction.SOUTH), RealmLoot.ISLES_PALACE);
            p.container(4, fy + 2, KZ0 + 2, Draw.chest(Direction.SOUTH), RealmLoot.ISLES_PALACE);
            p.container(x0 + 2, fy + 1, KZ1 - 2, Draw.chest(Direction.EAST), RealmLoot.ISLES_PALACE);
        }

        // ------------------------------------------------------------------ the shrine of Sheogorath

        /**
         * The Madgod as a painted statue on a two-coloured plinth, facing south: a wild-haired, bearded old man in a
         * coat of two colours, one hand on his cane.
         */
        static void shrine(Draw d, int fy, int sz) {
            Draw.Pal plinth = RealmKit.byX(0, Draw.Pal.of(b(Blocks.CALCITE)), Draw.Pal.of(b(Blocks.POLISHED_BLACKSTONE)));
            d.box(-4, fy + 1, sz - 4, 4, fy + 1, sz + 4, plinth);
            d.box(-3, fy + 2, sz - 3, 3, fy + 3, sz + 3, plinth);
            d.set(-4, fy + 2, sz + 4, st(RealmBlocks.MANIA_CAP_VIOLET));
            d.set(4, fy + 2, sz + 4, b(Blocks.SOUL_LANTERN));
            int y = fy + 4;
            Draw.Pal coat = RealmKit.byX(0, Draw.Pal.of(b(Blocks.PURPUR_BLOCK)), Draw.Pal.of(b(Blocks.RED_NETHER_BRICKS)));
            BlockState skin = b(Blocks.SMOOTH_QUARTZ), hair = b(Blocks.DIORITE), beard = b(Blocks.CALCITE);
            // shoes and legs
            d.set(-1, y, sz + 1, b(Blocks.BLACKSTONE));
            d.set(1, y, sz + 1, b(Blocks.BLACKSTONE));
            d.box(-1, y, sz, -1, y + 3, sz, b(com.nirncraft.worldgen.VanillaBlocks.PURPLE_TERRACOTTA));
            d.box(1, y, sz, 1, y + 3, sz, b(com.nirncraft.worldgen.VanillaBlocks.RED_TERRACOTTA));
            // the coat, its tails and its gold buttons
            d.box(-2, y + 4, sz - 1, 2, y + 8, sz + 1, coat);
            d.box(-2, y + 3, sz - 1, 2, y + 3, sz - 1, coat);
            d.set(0, y + 5, sz + 1, b(Blocks.GOLD_BLOCK));
            d.set(0, y + 7, sz + 1, b(Blocks.GOLD_BLOCK));
            // left arm hanging, right arm out to the cane
            d.box(-3, y + 5, sz, -3, y + 8, sz, coat);
            d.set(-3, y + 4, sz, skin);
            d.line(3.5, y + 8.5, sz + 0.5, 4.5, y + 6.5, sz + 1.5, 0.5, coat);
            d.box(4, y, sz + 2, 4, y + 9, sz + 2, b(Blocks.DARK_OAK_FENCE));
            d.set(4, y + 6, sz + 2, skin);
            d.set(4, y + 10, sz + 2, b(Blocks.GOLD_BLOCK));
            d.set(4, y + 11, sz + 2, RealmKit.cluster(Blocks.AMETHYST_CLUSTER, Direction.UP));
            // the head: eyes of two colours, a pointed beard, wild grey hair
            d.box(-1, y + 9, sz - 1, 1, y + 11, sz + 1, skin);
            d.set(-1, y + 10, sz + 1, b(Blocks.OCHRE_FROGLIGHT));
            d.set(1, y + 10, sz + 1, b(Blocks.CRYING_OBSIDIAN));
            d.box(-1, y + 8, sz + 2, 1, y + 9, sz + 2, beard);
            d.set(0, y + 7, sz + 2, beard);
            d.box(-1, y + 12, sz - 1, 1, y + 12, sz, hair);
            for (int s = -1; s <= 1; s += 2) {
                d.box(2 * s, y + 10, sz - 1, 2 * s, y + 12, sz, hair);
                d.set(3 * s, y + 12, sz - 1, hair);
            }
            d.set(0, y + 13, sz - 1, hair);
        }

        // ------------------------------------------------------------------ the city wall

        static void ringWall(Draw d, Painter p, int fy) {
            Draw.Pal pal = RealmKit.byZ(SPLIT, RealmKit.banded(RealmKit.weighted(new Block[]{Blocks.STONE_BRICKS, Blocks.CALCITE},
                            new int[]{4, 1}), Draw.Pal.of(b(Blocks.PURPUR_BLOCK)), 7, fy),
                    RealmKit.weighted(new Block[]{Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.MOSSY_STONE_BRICKS},
                            new int[]{5, 2, 2}));
            int top = fy + 11;
            for (int x = p.minX(); x <= p.maxX(); x++)
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    double r = Math.sqrt(x * x + z * z);
                    if (r < WALL_R - 2.5 || r > WALL_R + 0.5) continue;
                    d.column(x, z, fy, 24, pal);
                    for (int y = fy + 1; y <= top; y++) p.set(x, y, z, pal.at(p, x, y, z));
                    if (r > WALL_R - 0.5 && Math.floorMod((int) Math.floor(Math.atan2(z, x) * WALL_R / 2.0), 2) == 0) {
                        p.set(x, top + 1, z, pal.at(p, x, top + 1, z));
                    }
                }
            // towers along the wall, between the gates
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(15 + k * 30);
                int tx = (int) Math.round(Math.cos(a) * (WALL_R - 1)), tz = (int) Math.round(Math.sin(a) * (WALL_R - 1));
                tower(d, tx, tz, fy, 15, 3.5, tz < SPLIT, pal);
            }
            // gates: north to Mania, south to Dementia, east to the Fringe
            int[][] gates = {{0, -1}, {0, 1}, {1, 0}};
            for (int[] g : gates) {
                for (int t = WALL_R - 4; t <= WALL_R + 1; t++)
                    for (int u = -2; u <= 2; u++) {
                        int x = g[0] != 0 ? t * g[0] : u, z = g[1] != 0 ? t * g[1] : u;
                        d.air(x, fy + 1, z, x, fy + (Math.abs(u) == 2 ? 6 : 7), z);
                        d.column(x, z, fy, 6, streetPal(z < SPLIT));
                    }
                for (int s = -1; s <= 1; s += 2) {
                    int t = WALL_R - 1, u = 5 * s;
                    int x = g[0] != 0 ? t * g[0] : u, z = g[1] != 0 ? t * g[1] : u;
                    tower(d, x, z, fy, 17, 3.5, z < SPLIT, pal);
                }
                // the keystone over the gate: a cap for Mania, a black-glass eye for Dementia
                int kx = g[0] * (WALL_R - 1), kz = g[1] * (WALL_R - 1);
                if (g[1] < 0) d.set(kx, fy + 8, kz, st(RealmBlocks.MANIA_CAP_AMBER));
                else if (g[1] > 0) d.set(kx, fy + 8, kz, b(Blocks.CRYING_OBSIDIAN));
                else d.set(kx, fy + 8, kz, b(Blocks.AMETHYST_BLOCK));
            }
        }

        static void tower(Draw d, int x, int z, int fy, int h, double r, boolean bright, Draw.Pal pal) {
            d.footing(x, z, r, fy, 24, pal);
            d.cylinder(x, z, r, fy + 1, fy + h, pal);
            d.tube(x, z, r + 0.6, r - 0.6, fy + h + 1, fy + h + 1, pal);
            d.cone(x, z, r + 0.4, 0.4, fy + h + 2, fy + h + 9, bright
                    ? RealmKit.weighted(new Block[]{Blocks.PURPUR_BLOCK, com.nirncraft.worldgen.VanillaBlocks.MAGENTA_TERRACOTTA, com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_TERRACOTTA}, new int[]{3, 2, 1})
                    : RealmKit.weighted(new Block[]{Blocks.BLACKSTONE, Blocks.DEEPSLATE_TILES}, new int[]{3, 2}));
            d.set(x, fy + h + 10, z, bright ? cap(x + z) : b(Blocks.SOUL_LANTERN));
        }

        // ------------------------------------------------------------------ houses

        /** A house style: walls, timber frame, roof stairs, roof ridge, window glass (null: boarded) and door. */
        record Style(BlockState wall, BlockState frame, Block roof, BlockState ridge, BlockState window, Block door) {}

        static Style style(boolean bliss, int i) {
            if (bliss) {
                return switch (Math.floorMod(i, 5)) {
                    case 0 -> new Style(b(Blocks.CALCITE), b(Blocks.STRIPPED_WARPED_STEM), Blocks.WARPED_STAIRS, b(Blocks.WARPED_PLANKS),
                            b(com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS), Blocks.WARPED_DOOR);
                    case 1 -> new Style(b(com.nirncraft.worldgen.VanillaBlocks.PINK_TERRACOTTA), b(Blocks.CHERRY_LOG), Blocks.CHERRY_STAIRS, b(Blocks.CHERRY_PLANKS),
                            b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_STAINED_GLASS), Blocks.CHERRY_DOOR);
                    case 2 -> new Style(b(com.nirncraft.worldgen.VanillaBlocks.WHITE_TERRACOTTA), b(Blocks.STRIPPED_CRIMSON_STEM), Blocks.CRIMSON_STAIRS,
                            b(Blocks.CRIMSON_PLANKS), b(com.nirncraft.worldgen.VanillaBlocks.YELLOW_STAINED_GLASS), Blocks.CRIMSON_DOOR);
                    case 3 -> new Style(b(com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_TERRACOTTA), b(Blocks.PURPUR_PILLAR), Blocks.PURPUR_STAIRS, b(Blocks.PURPUR_BLOCK),
                            b(com.nirncraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS), Blocks.BIRCH_DOOR);
                    default -> new Style(b(com.nirncraft.worldgen.VanillaBlocks.YELLOW_TERRACOTTA), b(Blocks.BAMBOO_BLOCK), Blocks.BAMBOO_STAIRS, b(Blocks.BAMBOO_PLANKS),
                            b(com.nirncraft.worldgen.VanillaBlocks.ORANGE_STAINED_GLASS), Blocks.BAMBOO_DOOR);
                };
            }
            return switch (Math.floorMod(i, 4)) {
                case 0 -> new Style(b(Blocks.MUD_BRICKS), b(Blocks.DARK_OAK_LOG), Blocks.DARK_OAK_STAIRS, b(Blocks.DARK_OAK_PLANKS),
                        null, Blocks.DARK_OAK_DOOR);
                case 1 -> new Style(b(Blocks.COBBLED_DEEPSLATE), b(Blocks.DARK_OAK_LOG), Blocks.DEEPSLATE_TILE_STAIRS, b(Blocks.DEEPSLATE_TILES),
                        b(com.nirncraft.worldgen.VanillaBlocks.GRAY_STAINED_GLASS), Blocks.SPRUCE_DOOR);
                case 2 -> new Style(b(Blocks.MOSSY_COBBLESTONE), b(Blocks.STRIPPED_DARK_OAK_LOG), Blocks.MUD_BRICK_STAIRS, b(Blocks.MUD_BRICKS),
                        null, Blocks.DARK_OAK_DOOR);
                default -> new Style(b(Blocks.PACKED_MUD), b(Blocks.MANGROVE_LOG), Blocks.BLACKSTONE_STAIRS, b(Blocks.BLACKSTONE),
                        b(com.nirncraft.worldgen.VanillaBlocks.BROWN_STAINED_GLASS), Blocks.MANGROVE_DOOR);
            };
        }

        static void house(Draw d, Painter p, int hx, int hz, int fy, long h) {
            boolean bliss = hz < SPLIT;
            int hw = 3 + (int) (h >>> 8 & 1), hd = 3 + (int) (h >>> 9 & 1);
            int x0 = hx - hw, x1 = hx + hw, z0 = hz - hd, z1 = hz + hd;
            if (x1 + 2 < p.minX() || x0 - 2 > p.maxX() || z1 + 2 < p.minZ() || z0 - 2 > p.maxZ()) return;
            int wh = bliss ? 5 + (int) (h >>> 10 & 3) : 4 + (int) (h >>> 10 & 1);
            Style s = style(bliss, (int) (h >>> 12 & 15));
            Draw.Pal wall = bliss ? Draw.Pal.of(s.wall())
                    : Draw.Pal.weighted(new BlockState[]{s.wall(), b(Blocks.MOSSY_COBBLESTONE), b(Blocks.COBBLESTONE)}, new int[]{8, 2, 1});
            Draw.Pal found = bliss ? Draw.Pal.of(b(Blocks.STONE_BRICKS)) : Draw.Pal.of(b(Blocks.COBBLESTONE));
            boolean ridgeX = hw >= hd;
            int roofH = (ridgeX ? hd : hw) + 2;
            d.footing(x0, z0, x1, z1, fy, 10, found);
            d.clearAbove(x0 - 1, z0 - 1, x1 + 1, z1 + 1, fy + 1, fy + wh + roofH + 1);
            d.box(x0 + 1, fy, z0 + 1, x1 - 1, fy, z1 - 1, bliss ? b(Blocks.BIRCH_PLANKS) : b(Blocks.SPRUCE_PLANKS));
            d.walls(x0, fy + 1, z0, x1, fy + wh, z1, wall);
            for (int x : new int[]{x0, x1})
                for (int z : new int[]{z0, z1}) d.box(x, fy + 1, z, x, fy + wh, z, s.frame());
            d.walls(x0, fy + wh, z0, x1, fy + wh, z1, Draw.Pal.of(s.frame()));
            // windows
            for (int x = x0 + 2; x <= x1 - 2; x += 2) {
                for (int z : new int[]{z0, z1}) window(d, p, x, z, fy, wh, s, bliss);
            }
            for (int z = z0 + 2; z <= z1 - 2; z += 2) {
                for (int x : new int[]{x0, x1}) window(d, p, x, z, fy, wh, s, bliss);
            }
            // the door faces the palace
            Direction out;
            if (Math.abs(hx) > Math.abs(hz)) out = hx > 0 ? Direction.WEST : Direction.EAST;
            else out = hz > 0 ? Direction.NORTH : Direction.SOUTH;
            int dx = out.getStepX() == 0 ? hx : out.getStepX() > 0 ? x1 : x0;
            int dz = out.getStepZ() == 0 ? hz : out.getStepZ() > 0 ? z1 : z0;
            RealmKit.door(p, dx, fy + 1, dz, s.door(), out.getOpposite());
            d.box(dx + out.getStepX(), fy, dz + out.getStepZ(), dx + out.getStepX(), fy, dz + out.getStepZ(), found);
            d.air(dx + out.getStepX(), fy + 1, dz + out.getStepZ(), dx + out.getStepX(), fy + 2, dz + out.getStepZ());
            // the roof
            RealmKit.gableRoof(d, x0, z0, x1, z1, fy + wh + 1, ridgeX, s.roof(), Draw.Pal.of(s.ridge()), wall, bliss ? 0 : 7);
            // inside: a light, and either a bright rug or cobwebs and a barrel
            int ix = out == Direction.EAST ? x1 - 1 : x0 + 1, iz = out == Direction.SOUTH ? z0 + 1 : z1 - 1;
            if (bliss) {
                d.set(ix, fy + 1, iz, b(Blocks.LANTERN));
                d.box(hx - 1, fy + 1, hz - 1, hx + 1, fy + 1, hz + 1,
                        (h >>> 16 & 1) == 0 ? b(com.nirncraft.worldgen.VanillaBlocks.MAGENTA_CARPET) : b(com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_CARPET));
            } else {
                d.set(ix, fy + 1, iz, b(Blocks.BARREL));
                d.set(hx, fy + 1, hz, b(Blocks.CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
                d.set(ix, fy + wh - 1, iz == z0 + 1 ? z1 - 1 : z0 + 1, b(Blocks.COBWEB));
            }
        }

        static void window(Draw d, Painter p, int x, int z, int fy, int wh, Style s, boolean bliss) {
            for (int y = fy + 2; y <= fy + wh - 2; y++) {
                if (wh >= 7 && y == fy + 4) continue; // two storeys: a band between the rows
                BlockState glass = s.window();
                if (glass == null || !bliss && p.chance(x, y, z, 19, 4)) {
                    glass = p.chance(x, y, z, 20, 2) ? b(Blocks.DARK_OAK_PLANKS) : b(Blocks.AIR);
                }
                d.set(x, y, z, glass);
            }
        }
    }

    // ================================================================================================ ruins

    static final class Ruin implements RealmSites.Site {
        static final int MANIA = 0, DEMENTIA = 1;

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            int x = ctx.chunkPos().getMiddleBlockX(), z = ctx.chunkPos().getMiddleBlockZ();
            if (RealmLayout.dist(x, z, 0, 0) < RealmLayout.SI_SHEOTH_R + 80) return Optional.empty();
            if (RealmLayout.dist(x, z, RealmLayout.SI_FRINGE_X, 0) < RealmLayout.SI_FRINGE_R + 70) return Optional.empty();
            int g = RealmSites.ground(ctx, x, z);
            if (g < 64 || RealmSites.surface(ctx, x, z) > g) return Optional.empty();
            int min = g, max = g;
            for (int[] o : new int[][]{{7, 7}, {-7, 7}, {7, -7}, {-7, -7}}) {
                int h = RealmSites.ground(ctx, x + o[0], z + o[1]);
                if (h < 64 || RealmSites.surface(ctx, x + o[0], z + o[1]) > h) return Optional.empty();
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
            if (max - min > 6) return Optional.empty();
            int variant = z < -30 ? MANIA : DEMENTIA;
            return Optional.of(new RealmSites.Start(x, min, z, variant, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return s.param() == MANIA ? RealmSites.around(s, 13, 10, 34) : RealmSites.around(s, 13, 10, 16);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            if (piece.param == MANIA) maniaTower(p, piece);
            else dementiaShrine(p, piece);
        }

        /** A broken tower of white stone, cracked open by crystal and crowned with glowing caps. */
        static void maniaTower(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy - 1;
            double r = 5.5;
            int baseH = 16 + (int) (Math.abs(piece.seed) % 10);
            Draw.Pal stone = maniaStone();
            d.footing(cx, cz, r + 0.5, fy, 10, stone);
            d.cylinder(cx, cz, r - 1, fy, fy, Draw.Pal.mix(b(Blocks.CALCITE), b(Blocks.PURPUR_BLOCK), 4));
            Direction door = Direction.from2DDataValue(piece.facing);
            // the broken shell: each column of the wall ends at its own height
            for (int x = Math.max(cx - 6, p.minX()); x <= Math.min(cx + 6, p.maxX()); x++)
                for (int z = Math.max(cz - 6, p.minZ()); z <= Math.min(cz + 6, p.maxZ()); z++) {
                    double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    if (dd > r + 0.5 || dd <= r - 1.3) {
                        if (dd <= r - 1.3) for (int y = fy + 1; y <= fy + baseH + 2; y++) p.air(x, y, z);
                        continue;
                    }
                    double a = Math.atan2(z - cz, x - cx);
                    double brk = p.noise(Math.cos(a) * 7, 0, Math.sin(a) * 7, 4, 11);
                    int top = fy + (int) Math.round(baseH * (0.55 + 0.45 * (0.5 + 0.5 * brk)));
                    for (int y = fy + 1; y <= top; y++) {
                        if (y > fy + 3 && p.chance(x, y, z, 12, 23)) continue; // holes
                        p.set(x, y, z, stone.at(p, x, y, z));
                    }
                    for (int y = top + 1; y <= fy + baseH + 2; y++) p.air(x, y, z);
                }
            // the doorway and window slits
            for (int k = (int) r - 2; k <= (int) r + 1; k++)
                d.air(cx + door.getStepX() * k - (door.getStepZ() != 0 ? 1 : 0), fy + 1, cz + door.getStepZ() * k - (door.getStepX() != 0 ? 1 : 0),
                        cx + door.getStepX() * k + (door.getStepZ() != 0 ? 1 : 0), fy + 3, cz + door.getStepZ() * k + (door.getStepX() != 0 ? 1 : 0));
            // a broken upper floor
            for (int x = Math.max(cx - 4, p.minX()); x <= Math.min(cx + 4, p.maxX()); x++)
                for (int z = Math.max(cz - 4, p.minZ()); z <= Math.min(cz + 4, p.maxZ()); z++) {
                    double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    if (dd <= r - 1.3 && p.noise(x, 0, z, 3, 13) > 0.05) p.set(x, fy + 8, z, b(Blocks.PURPUR_SLAB));
                }
            // crystal bursting through the walls and out of the ground
            Draw.Pal crystal = Draw.Pal.of(st(BiomeBlocks.CRYSTAL_BLOCK));
            for (int i = 0; i < 5; i++) {
                long h = RealmSites.hash(piece.seed, i, 3);
                double a = (h & 1023) / 1023.0 * Math.PI * 2;
                double y0 = fy + 1 + (h >>> 10 & 7);
                double len = 5 + (h >>> 13 & 3);
                double bx = cx + 0.5 + Math.cos(a) * 3, bz = cz + 0.5 + Math.sin(a) * 3;
                double ex = bx + Math.cos(a) * len, ez = bz + Math.sin(a) * len, ey = y0 + len * 0.8;
                d.line(bx, y0, bz, ex, ey, ez, 1.2, crystal);
                d.set((int) Math.floor(ex), (int) Math.floor(ey) + 2, (int) Math.floor(ez), RealmKit.cluster(BiomeBlocks.CRYSTAL_CLUSTER.get(), Direction.UP));
            }
            // glowing shelf caps on the outside and a crown of caps on the highest stub
            for (int i = 0; i < 7; i++) {
                long h = RealmSites.hash(piece.seed, i, 4);
                double a = (h & 1023) / 1023.0 * Math.PI * 2;
                int y = fy + 3 + (int) (h >>> 10 & 7);
                int x = (int) Math.floor(cx + 0.5 + Math.cos(a) * (r + 1.2)), z = (int) Math.floor(cz + 0.5 + Math.sin(a) * (r + 1.2));
                d.blob(x, y, z, 1.6, 0.5, 1.6, Draw.Pal.of(i % 3 == 0 ? st(BiomeBlocks.GLOWING_MUSHROOM_CAP) : cap(i)));
            }
            // mania blooms around the foot
            for (int x = Math.max(cx - 12, p.minX()); x <= Math.min(cx + 12, p.maxX()); x++)
                for (int z = Math.max(cz - 12, p.minZ()); z <= Math.min(cz + 12, p.maxZ()); z++) {
                    if (!p.chance(x, 0, z, 14, 7)) continue;
                    int top = p.surfaceAt(x, z);
                    if (top > fy - 4 && top < fy + 4 && p.get(x, top, z).isAir() && Painter.ground(p.get(x, top - 1, z))) {
                        p.set(x, top, z, st(RealmBlocks.MANIA_BLOOM));
                    }
                }
            p.container(cx - door.getStepX() * 3, fy + 1, cz - door.getStepZ() * 3, Draw.chest(door), RealmLoot.ISLES_RUIN);
            p.spawn(ELYTRA, cx + door.getStepX() * 8, fy + 1, cz + door.getStepZ() * 8, 0f, null);
            p.spawn(ELYTRA, cx + 2, fy + 1, cz - 2, 90f, null);
            p.spawn(GNARL, cx - door.getStepZ() * 8, fy + 1, cz + door.getStepX() * 8, 0f, null);
        }

        /** A sunken shrine of mossy stone half drowned in a Dementia bog: a broken arch, an idol, an offering chest. */
        static void dementiaShrine(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, fy = piece.oy - 1;
            int floor = fy - 3;
            RealmKit.Frame f = new RealmKit.Frame(cx, cz, Direction.from2DDataValue(piece.facing));
            Draw.Pal mossy = RealmKit.weighted(new Block[]{Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE,
                    Blocks.STONE_BRICKS}, new int[]{5, 2, 2, 2});
            // the pit: retaining walls, a flagstone floor with puddles
            d.footing(cx - 8, cz - 8, cx + 8, cz + 8, floor, 8, mossy);
            for (int x = Math.max(cx - 8, p.minX()); x <= Math.min(cx + 8, p.maxX()); x++)
                for (int z = Math.max(cz - 8, p.minZ()); z <= Math.min(cz + 8, p.maxZ()); z++) {
                    boolean rim = Math.abs(x - cx) == 8 || Math.abs(z - cz) == 8;
                    for (int y = floor + 1; y <= fy + 8; y++) {
                        if (rim && y <= fy) p.set(x, y, z, mossy.at(p, x, y, z));
                        else p.air(x, y, z);
                    }
                    if (rim) {
                        if (p.chance(x, fy, z, 3, 3)) p.set(x, fy + 1, z, b(Blocks.MOSS_CARPET));
                        continue;
                    }
                    BlockState fl = mossy.at(p, x, floor, z);
                    if (p.noise(x, 0, z, 3, 5) > 0.45) {
                        p.set(x, floor - 1, z, b(Blocks.MUD));
                        fl = b(Blocks.WATER);
                    } else if (p.chance(x, floor, z, 6, 4)) {
                        fl = b(Blocks.MOSS_BLOCK);
                    }
                    p.set(x, floor, z, fl);
                    if (fl.is(Blocks.MOSS_BLOCK) && p.chance(x, floor, z, 7, 3)) p.set(x, floor + 1, z, st(RealmBlocks.DEMENTIA_BULB));
                }
            // steps down at the front
            for (int k = 0; k < 3; k++) {
                f.box(d, -1, floor + 1, 8 - k, 1, fy - k, 8 - k, mossy);
                f.air(d, -1, fy - k + 1, 8 - k, 1, fy + 3, 8 - k);
            }
            // the altar, the idol and the broken arch behind it
            f.box(d, -2, floor + 1, -3, 2, floor + 1, 1, mossy);
            f.box(d, -1, floor + 2, -2, 1, floor + 2, 0, Draw.Pal.of(b(Blocks.CHISELED_STONE_BRICKS)));
            f.set(d, 0, floor + 3, -1, b(Blocks.POLISHED_BLACKSTONE));
            f.set(d, 0, floor + 4, -1, b(Blocks.CRYING_OBSIDIAN));
            f.set(d, 0, floor + 5, -1, RealmKit.skull(Blocks.WITHER_SKELETON_SKULL, piece.facing * 4));
            int hl = 6, hr = 3 + (int) (Math.abs(piece.seed) % 3);
            f.box(d, -3, floor + 1, -5, -3, floor + hl, -5, mossy);
            f.box(d, 3, floor + 1, -5, 3, floor + hr, -5, mossy);
            f.box(d, -3, floor + hl + 1, -5, 0, floor + hl + 1, -5, mossy);
            // the fallen half of the lintel
            f.box(d, 3, floor + 1, -3, 5, floor + 1, -3, mossy);
            // corner pillars of differing heights, hung with vines and roots
            for (int su = -1; su <= 1; su += 2)
                for (int sv = -1; sv <= 1; sv += 2) {
                    int ph = 2 + (int) (RealmSites.hash(piece.seed, su, sv) & 3);
                    f.box(d, 6 * su, floor + 1, 6 * sv, 6 * su, fy + ph, 6 * sv, mossy);
                    f.set(d, 6 * su, fy + ph + 1, 6 * sv, st(RealmBlocks.DEMENTIA_BULB));
                }
            for (int x = Math.max(cx - 7, p.minX()); x <= Math.min(cx + 7, p.maxX()); x++)
                for (int z = Math.max(cz - 7, p.minZ()); z <= Math.min(cz + 7, p.maxZ()); z++) {
                    for (int y = floor + 1; y <= fy; y++) {
                        if (!p.get(x, y, z).isAir()) continue;
                        for (Direction dir : Direction.Plane.HORIZONTAL) {
                            BlockState n = p.ok(x + dir.getStepX(), y, z + dir.getStepZ()) ? p.get(x + dir.getStepX(), y, z + dir.getStepZ()) : null;
                            if (n != null && n.is(Blocks.MOSSY_STONE_BRICKS) && p.chance(x, y, z, 8 + dir.get2DDataValue(), 5)) {
                                p.set(x, y, z, RealmKit.vine(dir));
                                break;
                            }
                        }
                    }
                }
            f.set(d, -4, floor + 1, 4, b(Blocks.COBWEB));
            p.container(f.x(0, -4), floor + 1, f.z(0, -4), Draw.chest(f.front()), RealmLoot.ISLES_RUIN);
            p.spawn(GRUMMITE, f.x(-3, 3), floor + 1, f.z(-3, 3), 0f, null);
            p.spawn(GRUMMITE, f.x(4, 2), floor + 1, f.z(4, 2), 0f, null);
            p.spawn(BALIWOG, f.x(0, 11), fy + 1, f.z(0, 11), 0f, null);
        }
    }
}
