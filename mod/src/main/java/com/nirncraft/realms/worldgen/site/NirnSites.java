package com.nirncraft.realms.worldgen.site;

import com.nirncraft.dungeons.world.Painter;
import com.nirncraft.realms.RealmBlocks;
import com.nirncraft.realms.block.BlackBookBlock;
import com.nirncraft.realms.block.RealmDoorBlock;
import com.nirncraft.realms.block.RealmPortalBlock;
import com.nirncraft.realms.worldgen.RealmPiece;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.world.LocationTags;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.nirncraft.realms.worldgen.site.Gates.st;

/**
 * The ways into the realms that stand in Nirn.
 * <ul>
 *     <li>{@code nirn/oblivion_gate}: rare random Oblivion gates outside Skyrim (Cyrodiil most of all), on scorched
 *     ground; {@code nirn/named_gate}: the same gate at a named place (the gate above Kvatch).</li>
 *     <li>{@code nirn/madness_door}: the Door to the Shivering Isles on its islet off Leyawiin.</li>
 *     <li>{@code nirn/black_book_altar}: Hermaeus Mora's altars on Solstheim, a Black Book open on each.</li>
 *     <li>{@code nirn/soul_cairn_portal}: the soul-stone portal by Castle Volkihar.</li>
 *     <li>{@code nirn/dark_anchor}: Molag Bal's chain from the sky in the Jerall Mountains.</li>
 * </ul>
 * Named sites stand exactly at their place ({@code nirncraft/tamriel/places/realms.json}, landmark variants).
 */
final class NirnSites {
    private NirnSites() {}

    static void register() {
        RealmSites.add("nirn/oblivion_gate", new Gate(false));
        RealmSites.add("nirn/named_gate", new Gate(true));
        RealmSites.add("nirn/madness_door", new MadnessDoor());
        RealmSites.add("nirn/black_book_altar", new BlackBookAltar());
        RealmSites.add("nirn/soul_cairn_portal", new SoulPortal());
        RealmSites.add("nirn/dark_anchor", new DarkAnchor());
    }

    static Optional<Place> placeIn(Structure.GenerationContext ctx, String variant) {
        for (Place p : Places.inChunk(ctx.chunkPos())) {
            if (p.type() == PlaceType.LANDMARK && variant.equals(p.variant())) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Facing from a place id (stable). */
    static int facingOf(String id) {
        return Math.floorMod(id.hashCode(), 4);
    }

    /** Plants, leaves, logs and snow are cleared from a site's footprint; terrain stays. */
    static void clearGrowth(Painter p, int x, int y, int z) {
        BlockState s = p.get(x, y, z);
        if (s.isAir()) return;
        if (Painter.open(s) && s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) p.air(x, y, z);
    }

    // ================================================================================================ Oblivion gates

    static final class Gate implements RealmSites.Site {
        private final boolean named;

        Gate(boolean named) {
            this.named = named;
        }

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            if (named) {
                Optional<Place> place = placeIn(ctx, "oblivion_gate");
                if (place.isEmpty()) return Optional.empty();
                Place pl = place.get();
                int y = RealmSites.ground(ctx, pl.x(), pl.z());
                return Optional.of(new RealmSites.Start(pl.x(), Math.max(y, ctx.chunkGenerator().getSeaLevel() + 1), pl.z(), 1, facingOf(pl.id())));
            }
            ChunkPos cp = ctx.chunkPos();
            int x = cp.getMiddleBlockX(), z = cp.getMiddleBlockZ();
            Region region = TamrielMap.region(x, z);
            if (region == null) return Optional.empty();
            String province = region.province();
            if ("skyrim".equals(province) || "sea".equals(province) || "sea".equals(region.kind())
                    || region.id().equals("morrowind/solstheim")) return Optional.empty();
            // Cyrodiil, where the Crisis was, always; elsewhere about a third of the candidate spots. When the places
            // files list Oblivion's own gates (landmark variant "oblivion_gate", e.g. places/oblivion_gates.json),
            // those stand in Cyrodiil instead and random gates keep to the other provinces, away from them.
            boolean namedGates = namedGateCount() >= 5;
            if ("cyrodiil".equals(province) ? namedGates : Math.floorMod(RealmSites.hash(ctx.seed(), cp.x(), cp.z()), 100) >= 35) {
                return Optional.empty();
            }
            if (namedGates) {
                for (Place p : Places.overlapping(x - 400, z - 400, x + 400, z + 400)) {
                    if ("oblivion_gate".equals(p.variant())) return Optional.empty();
                }
            }
            if (!Places.overlapping(x - 72, z - 72, x + 72, z + 72).isEmpty()) return Optional.empty();
            int sea = ctx.chunkGenerator().getSeaLevel();
            int[] hs = {RealmSites.ground(ctx, x, z), RealmSites.ground(ctx, x + 9, z + 9), RealmSites.ground(ctx, x - 9, z + 9),
                    RealmSites.ground(ctx, x + 9, z - 9), RealmSites.ground(ctx, x - 9, z - 9)};
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, sum = 0;
            for (int h : hs) {
                min = Math.min(min, h);
                max = Math.max(max, h);
                sum += h;
            }
            if (min <= sea + 1 || max - min > 8) return Optional.empty();
            for (int h : hs) {
                if (RealmSites.surface(ctx, x, z) > h + 1) return Optional.empty(); // standing water
            }
            if (nearTown(ctx, 6)) return Optional.empty();
            return Optional.of(new RealmSites.Start(x, sum / hs.length, z, 0, ctx.random().nextInt(4)));
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 17, 24, 28);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, base = piece.oy - 1;
            boolean axisX = piece.facing % 2 == 0;
            // scorched earth: the blight of Oblivion spreading from the gate
            for (int x = Math.max(cx - 16, p.minX()); x <= Math.min(cx + 16, p.maxX()); x++)
                for (int z = Math.max(cz - 16, p.minZ()); z <= Math.min(cz + 16, p.maxZ()); z++) {
                    double dist = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    double edge = 12.5 + p.noise(x, 0, z, 5, 3) * 3.5;
                    if (dist > edge) continue;
                    int top = p.surfaceAt(x, z) - 1;
                    if (top == Integer.MIN_VALUE - 1) continue;
                    for (int y = top + 1; y <= top + 18; y++) clearGrowth(p, x, y, z);
                    BlockState ground = p.chance(x, top, z, 7, 9) ? st(RealmBlocks.DEADLANDS_MAGMA_ROCK)
                            : p.chance(x, top, z, 8, 3) ? st(RealmBlocks.DEADLANDS_ROCK) : st(RealmBlocks.DEADLANDS_ASH);
                    if (dist > edge - 2.5 && p.chance(x, top, z, 9, 2)) ground = Blocks.COARSE_DIRT.defaultBlockState();
                    p.set(x, top, z, ground);
                    if (dist < 5) continue;
                    if (ground.is(RealmBlocks.DEADLANDS_MAGMA_ROCK.get()) && p.chance(x, top, z, 10, 2)) {
                        p.set(x, top + 1, z, Blocks.FIRE.defaultBlockState());
                    } else if (p.chance(x, top, z, 11, 5)) {
                        p.set(x, top + 1, z, st(RealmBlocks.BLOODGRASS));
                    } else if (p.chance(x, top, z, 12, 11)) {
                        p.set(x, top + 1, z, st(RealmBlocks.HARRADA));
                    }
                }
            // the dais under the arch
            int hw = 3, h = 10;
            for (int u = -hw - 4; u <= hw + 4; u++)
                for (int v = -3; v <= 3; v++) {
                    int x = axisX ? cx + u : cx + v, z = axisX ? cz + v : cz + u;
                    d.column(x, z, base, 12, DeadlandsSites.rock());
                    d.set(x, base, z, Draw.Pal.mix(st(RealmBlocks.DAEDRIC_BRICKS), st(RealmBlocks.DAEDRIC_GLYPH_BRICKS), 7).at(p, x, base, z));
                    for (int y = base + 1; y <= base + h + 12; y++) clearGrowth(p, x, y, z);
                }
            Gates.oblivionArch(d, cx, base, cz, axisX, hw, h, false);
            // thorn obelisks around it
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 + i * Math.PI / 2 + (piece.seed % 7) * 0.05;
                int ox = (int) Math.round(cx + Math.cos(a) * 11), oz = (int) Math.round(cz + Math.sin(a) * 11);
                int top = p.ownsColumn(ox, oz) ? p.surfaceAt(ox, oz) - 1 : base;
                d.cone(ox, oz, 1.6, 0.3, top, top + 7 + i % 3 * 2, Gates.daedric());
                d.column(ox, oz, top, 6, Gates.daedric());
                d.set(ox, top + 8 + i % 3 * 2, oz, Gates.spike(Direction.UP));
            }
            if (piece.param == 0) {
                p.spawn(DeadlandsSites.DREMORA, cx + (axisX ? 0 : 4), base + 1, cz + (axisX ? 4 : 0), 0f, null);
                p.spawn(DeadlandsSites.SCAMP, cx + (axisX ? 3 : -5), base + 1, cz + (axisX ? -5 : 3), 0f, null);
            } else {
                p.spawn(DeadlandsSites.SCAMP, cx + (axisX ? 6 : 0), base + 1, cz + (axisX ? 0 : 6), 0f, null);
            }
        }
    }

    private static volatile int namedGates = -1;

    /** How many named Oblivion gates the places files list (Kvatch's, plus Oblivion's own if a places file has them). */
    static int namedGateCount() {
        int n = namedGates;
        if (n < 0) {
            n = 0;
            for (Place p : Places.all()) if ("oblivion_gate".equals(p.variant())) n++;
            namedGates = n;
        }
        return n;
    }

    /** True if a town may start within {@code range} chunks of this context's chunk (gates keep away from towns). */
    static boolean nearTown(Structure.GenerationContext ctx, int range) {
        List<Holder<StructureSet>> sets = new ArrayList<>();
        ctx.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElements().forEach(set -> {
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                if (e.structure().is(LocationTags.TOWNS)) {
                    sets.add(set);
                    break;
                }
            }
        });
        if (sets.isEmpty()) return false;
        ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForFlat(ctx.randomState(), ctx.seed(),
                ctx.chunkPos(), ctx.biomeSource(), Stream.empty());
        ChunkPos cp = ctx.chunkPos();
        int y = QuartPos.fromBlock(ctx.chunkGenerator().getSeaLevel());
        for (Holder<StructureSet> set : sets) {
            StructurePlacement placement = set.value().placement();
            for (int x = cp.x() - range; x <= cp.x() + range; x++)
                for (int z = cp.z() - range; z <= cp.z() + range; z++) {
                    if (!placement.isStructureChunk(state, x, z)) continue;
                    Holder<Biome> biome = ctx.biomeSource().createUncachedResolver(ctx.randomState()).getNoiseBiome(QuartPos.fromBlock((x << 4) + 8), y,
                            QuartPos.fromBlock((z << 4) + 8));
                    for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                        if (e.structure().is(LocationTags.TOWNS) && e.structure().value().biomes().contains(biome)) return true;
                    }
                }
        }
        return false;
    }

    // ================================================================================================ named sites

    abstract static class Named implements RealmSites.Site {
        private final String variant;

        Named(String variant) {
            this.variant = variant;
        }

        @Override
        public Optional<RealmSites.Start> locate(Structure.GenerationContext ctx) {
            Optional<Place> place = placeIn(ctx, variant);
            if (place.isEmpty()) return Optional.empty();
            Place pl = place.get();
            int y = RealmSites.ground(ctx, pl.x(), pl.z());
            return Optional.of(new RealmSites.Start(pl.x(), y, pl.z(), Math.floorMod(pl.id().hashCode(), 1 << 16), facingOf(pl.id())));
        }
    }

    // ------------------------------------------------------------------ the Door to the Shivering Isles

    static final class MadnessDoor extends Named {
        MadnessDoor() {
            super("madness_door");
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 14, 30, 22);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz;
            int top = Math.max(piece.oy, 64) + 1;
            // the islet: a mound of stone and turf rising out of the bay
            for (int x = Math.max(cx - 13, p.minX()); x <= Math.min(cx + 13, p.maxX()); x++)
                for (int z = Math.max(cz - 13, p.minZ()); z <= Math.min(cz + 13, p.maxZ()); z++) {
                    double dist = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    double r = 10 + p.noise(x, 0, z, 6, 4) * 2.5;
                    if (dist > r) continue;
                    int h = top - (int) Math.round(Math.max(0, dist - 5) * 0.55);
                    for (int y = h; y > h - 30 && y >= p.minY(); y--) {
                        BlockState s = p.get(x, y, z);
                        if (y < h - 1 && Painter.ground(s) && s.getFluidState().isEmpty()) break;
                        p.set(x, y, z, y == h ? (h <= 63 ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState())
                                : y > h - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
                    }
                    for (int y = h + 1; y <= h + 12; y++) clearGrowth(p, x, y, z);
                    if (h > 63 && dist > 3 && p.chance(x, h, z, 3, 4)) {
                        p.set(x, h + 1, z, p.chance(x, h, z, 4, 3) ? st(RealmBlocks.MANIA_BLOOM)
                                : com.nirncraft.worldgen.VanillaBlocks.GRASS.defaultBlockState());
                    }
                }
            // the door in its carved frame, facing the Isles' way (east)
            Direction face = Direction.EAST;
            BlockState frame = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            // plinth, jambs and a stepped lintel
            d.box(cx - 1, top, cz - 2, cx + 1, top, cz + 2, frame);
            d.box(cx, top + 1, cz - 1, cx, top + 3, cz - 1, frame);
            d.box(cx, top + 1, cz + 1, cx, top + 3, cz + 1, frame);
            d.box(cx, top + 3, cz - 2, cx, top + 3, cz + 2, frame);
            d.set(cx, top + 3, cz, Blocks.AMETHYST_BLOCK.defaultBlockState());
            d.box(cx, top + 4, cz - 1, cx, top + 4, cz + 1, Blocks.PURPUR_PILLAR.defaultBlockState());
            d.set(cx, top + 5, cz, Blocks.AMETHYST_BLOCK.defaultBlockState());
            BlockState door = st(RealmBlocks.MADNESS_DOOR).setValue(RealmDoorBlock.FACING, face).setValue(RealmDoorBlock.RETURN, false);
            d.set(cx, top + 1, cz, door.setValue(RealmDoorBlock.HALF, DoubleBlockHalf.LOWER));
            d.set(cx, top + 2, cz, door.setValue(RealmDoorBlock.HALF, DoubleBlockHalf.UPPER));
            // two mad obelisks, one bright and one dark (Mania and Dementia)
            d.box(cx - 3, top, cz - 4, cx - 3, top + 4, cz - 4, Blocks.PURPUR_PILLAR.defaultBlockState());
            d.set(cx - 3, top + 5, cz - 4, st(RealmBlocks.MANIA_CAP_TEAL));
            d.box(cx - 3, top, cz + 4, cx - 3, top + 4, cz + 4, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            d.set(cx - 3, top + 5, cz + 4, Blocks.CRYING_OBSIDIAN.defaultBlockState());
            d.box(cx + 1, top, cz - 1, cx + 2, top, cz + 1, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
        }
    }

    // ------------------------------------------------------------------ Black Book altars

    static final class BlackBookAltar extends Named {
        BlackBookAltar() {
            super("black_book_altar");
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 10, 20, 16);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, y = piece.oy;
            Draw.Pal stone = Draw.Pal.mix(st(RealmBlocks.APOCRYPHA_STONE), st(RealmBlocks.APOCRYPHA_GLYPH_STONE), 6);
            d.footing(cx, cz, 5.5, y, 14, Draw.Pal.of(Blocks.COBBLED_DEEPSLATE.defaultBlockState()));
            d.cylinder(cx, cz, 5.5, y, y, stone);
            d.cylinder(cx, cz, 3.5, y + 1, y + 1, stone);
            for (int x = cx - 6; x <= cx + 6; x++)
                for (int z = cz - 6; z <= cz + 6; z++)
                    for (int h = y + 1; h <= y + 14; h++) if (!(h == y + 1 && Math.abs(x - cx) <= 3 && Math.abs(z - cz) <= 3)) clearGrowth(p, x, h, z);
            Direction face = Direction.from2DDataValue(piece.facing);
            d.set(cx, y + 2, cz, st(RealmBlocks.BLACK_BOOK).setValue(BlackBookBlock.FACING, face).setValue(BlackBookBlock.MODE, BlackBookBlock.Mode.ENTER));
            // tentacles curling up out of the ground around the altar
            for (int i = 0; i < 5; i++) {
                double a = i * Math.PI * 2 / 5 + 0.3;
                double r = 6.5;
                double bx = cx + 0.5 + Math.cos(a) * r, bz = cz + 0.5 + Math.sin(a) * r;
                int h = 6 + i % 3 * 2;
                d.curve(bx, y, bz, bx + Math.cos(a) * 2, y + h * 0.6, bz + Math.sin(a) * 2,
                        bx - Math.cos(a) * 1.5, y + h, bz - Math.sin(a) * 1.5, 1.1, 0.4, Draw.Pal.of(st(RealmBlocks.APOCRYPHA_TENTACLE)));
            }
            // fallen pages and a guardian
            for (int k = 0; k < 7; k++) {
                int x = cx + (int) (RealmSites.hash(piece.seed, k, 1) % 7), z = cz + (int) (RealmSites.hash(piece.seed, k, 2) % 7);
                if (p.ownsColumn(x, z)) {
                    int top = p.surfaceAt(x, z);
                    if (top > y - 4 && top < y + 4) d.set(x, top, z, st(RealmBlocks.PAGE_BLOCK));
                }
            }
            p.spawn("nirncraft:seeker", cx + 3, y + 1, cz - 3, 0f, null);
        }
    }

    // ------------------------------------------------------------------ the Soul Cairn portal

    static final class SoulPortal extends Named {
        SoulPortal() {
            super("soul_cairn_portal");
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 10, 16, 16);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, y = piece.oy;
            Draw.Pal ring = Draw.Pal.mix(st(RealmBlocks.SOUL_STONE), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), 3);
            d.footing(cx, cz, 6.5, y, 14, Draw.Pal.of(st(RealmBlocks.SOUL_STONE)));
            d.cylinder(cx, cz, 6.5, y, y, ring);
            d.tube(cx, cz, 6.5, 5.4, y + 1, y + 1, Draw.Pal.of(st(RealmBlocks.SOUL_STONE)));
            for (int x = cx - 7; x <= cx + 7; x++)
                for (int z = cz - 7; z <= cz + 7; z++)
                    for (int h = y + 1; h <= y + 14; h++) {
                        double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                        if (dd > 5.4) clearGrowth(p, x, h, z);
                        else if (h > y + 1 || dd < 5.4) clearGrowth(p, x, h, z);
                    }
            boolean axisX = piece.facing % 2 == 0;
            BlockState portal = st(RealmBlocks.SOUL_PORTAL).setValue(RealmPortalBlock.AXIS_X, axisX).setValue(RealmPortalBlock.RETURN, false);
            Gates.framedPortal(d, cx, y, cz, axisX, 1, 4, portal, Draw.Pal.of(st(RealmBlocks.SOUL_STONE)),
                    Draw.Pal.of(Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()));
            // soul gems set on four plinths around the ring
            for (int i = 0; i < 4; i++) {
                double a = Math.PI / 4 + i * Math.PI / 2;
                int ox = (int) Math.round(cx + Math.cos(a) * 5), oz = (int) Math.round(cz + Math.sin(a) * 5);
                d.box(ox, y + 1, oz, ox, y + 2, oz, st(RealmBlocks.SOUL_STONE));
                d.set(ox, y + 3, oz, st(RealmBlocks.SOUL_GEM_BLOCK));
            }
            d.set(cx + (axisX ? 0 : 3), y + 1, cz + (axisX ? 3 : 0), Blocks.SOUL_LANTERN.defaultBlockState());
        }
    }

    // ------------------------------------------------------------------ the Dark Anchor

    static final class DarkAnchor extends Named {
        DarkAnchor() {
            super("dark_anchor");
        }

        @Override
        public BoundingBox box(RealmSites.Start s) {
            return RealmSites.around(s, 12, 20, 70);
        }

        @Override
        public void paint(Painter p, RealmPiece piece) {
            Draw d = new Draw(p);
            int cx = piece.ox, cz = piece.oz, y = piece.oy;
            Draw.Pal dark = Draw.Pal.mix(st(RealmBlocks.COLDHARBOUR_BRICKS), st(RealmBlocks.COLDHARBOUR_STONE), 3);
            // a crater of blasted rock
            for (int x = Math.max(cx - 11, p.minX()); x <= Math.min(cx + 11, p.maxX()); x++)
                for (int z = Math.max(cz - 11, p.minZ()); z <= Math.min(cz + 11, p.maxZ()); z++) {
                    double dist = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    if (dist > 10 + p.noise(x, 0, z, 4, 9) * 2) continue;
                    int top = p.surfaceAt(x, z) - 1;
                    for (int h = top + 1; h <= top + 16; h++) clearGrowth(p, x, h, z);
                    p.set(x, top, z, p.chance(x, top, z, 3, 3) ? Blocks.BLACKSTONE.defaultBlockState() : st(RealmBlocks.COLDHARBOUR_STONE));
                    if (dist > 4 && p.chance(x, top, z, 5, 9)) {
                        p.set(x, top, z, Blocks.SOUL_SOIL.defaultBlockState());
                        p.set(x, top + 1, z, Blocks.SOUL_FIRE.defaultBlockState());
                    }
                }
            // the pinion: a spiked iron-black anchor head sunk into the ground
            d.footing(cx, cz, 4.5, y, 12, dark);
            d.cylinder(cx, cz, 4.5, y, y, dark);
            d.cone(cx, cz, 3.5, 1.2, y + 1, y + 6, dark);
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2;
                d.curve(cx + 0.5, y + 3, cz + 0.5, cx + 0.5 + Math.cos(a) * 6, y + 2, cz + 0.5 + Math.sin(a) * 6,
                        cx + 0.5 + Math.cos(a) * 7, y + 7, cz + 0.5 + Math.sin(a) * 7, 1.2, 0.5, dark);
            }
            // the chain rising into the sky
            BlockState chain = com.nirncraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.Y);
            for (int h = y + 7; h <= y + 66; h++) {
                d.set(cx, h, cz, chain);
                if (h % 6 == 0) {
                    d.set(cx + 1, h, cz, chain);
                    d.set(cx - 1, h, cz, chain);
                }
            }
            // the way through: a dark portal in the anchor's flank
            boolean axisX = piece.facing % 2 == 0;
            int px = cx + (axisX ? 0 : 6), pz = cz + (axisX ? 6 : 0);
            BlockState portal = st(RealmBlocks.COLDHARBOUR_PORTAL).setValue(RealmPortalBlock.AXIS_X, axisX).setValue(RealmPortalBlock.RETURN, false);
            Gates.framedPortal(d, px, y, pz, axisX, 1, 4, portal, dark, Draw.Pal.of(Blocks.POLISHED_BLACKSTONE.defaultBlockState()));
        }
    }
}
