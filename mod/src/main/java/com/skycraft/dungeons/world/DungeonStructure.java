package com.skycraft.dungeons.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.dungeons.DungeonsRegistry;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One structure type for every Skycraft dungeon and landmark. The structure JSON picks what gets built: the
 * {@code theme}, optionally a culture {@code kit}, a {@code special} layout, a fixed {@code flavor}; and where it
 * comes from:
 * <ul>
 *     <li><b>random sites</b> ({@code random_spread} placement): the chunk centre, filtered by the provinces in
 *     {@code where} ({@link Cultures}); never on or next to a named place or a town;</li>
 *     <li><b>named places</b> ({@code skycraft:places} placement and a matching {@code place} filter): the place's
 *     exact position, always built (nudged off water if needed);</li>
 *     <li><b>site lists</b> ({@code skycraft:sites} placement and the same {@code sites} name): path stretches and
 *     cavern tiles ({@link Sites}).</li>
 * </ul>
 *
 * <p>{@link #findGenerationPoint} only samples a handful of noise heights; pieces are planned when the start is
 * created and painted chunk by chunk. Named places and site lists skip the biome check: the map decides.</p>
 */
public class DungeonStructure extends Structure {
    public static final Codec<DungeonStructure> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            settingsCodec(instance),
            Theme.CODEC.fieldOf("theme").forGetter(s -> s.theme),
            Kit.CODEC.optionalFieldOf("kit", Kit.NONE).forGetter(s -> s.kit),
            Codec.BOOL.optionalFieldOf("regional_kit", false).forGetter(s -> s.regionalKit),
            Special.CODEC.optionalFieldOf("special", Special.NONE).forGetter(s -> s.special),
            Codec.INT.optionalFieldOf("flavor", -1).forGetter(s -> s.flavor),
            PlaceFilter.CODEC.optionalFieldOf("place").forGetter(s -> s.place),
            Codec.STRING.listOf().optionalFieldOf("where", List.of()).forGetter(s -> s.where),
            Codec.STRING.optionalFieldOf("sites", "").forGetter(s -> s.sites)
    ).apply(instance, DungeonStructure::new));

    /** Which named places a structure builds: place types and (optionally) variants, like its placement. */
    public record PlaceFilter(List<String> types, List<String> variants) {
        public static final Codec<PlaceFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.listOf().fieldOf("types").forGetter(PlaceFilter::types),
                Codec.STRING.listOf().optionalFieldOf("variants", List.of()).forGetter(PlaceFilter::variants)
        ).apply(i, PlaceFilter::new));

        public boolean matches(Place p) {
            return types.contains(p.type().id()) && (variants.isEmpty() || variants.contains(p.variant()));
        }
    }

    private static final int WATER = Integer.MIN_VALUE;
    /** No dungeon starts within this many chunks of a {@code #skycraft:towns} start. */
    private static final int TOWN_RANGE_SURFACE = 6;
    private static final int TOWN_RANGE_UNDERGROUND = 4;
    /** Random sites keep this far from any named place's footprint. */
    private static final int PLACE_MARGIN = 48;

    public final Theme theme;
    public final Kit kit;
    public final boolean regionalKit;
    public final Special special;
    public final int flavor;
    public final Optional<PlaceFilter> place;
    public final List<String> where;
    public final String sites;

    public DungeonStructure(StructureSettings settings, Theme theme) {
        this(settings, theme, Kit.NONE, false, Special.NONE, -1, Optional.empty(), List.of(), "");
    }

    public DungeonStructure(StructureSettings settings, Theme theme, Kit kit, boolean regionalKit, Special special, int flavor,
                            Optional<PlaceFilter> place, List<String> where, String sites) {
        super(settings);
        this.theme = theme;
        this.kit = kit;
        this.regionalKit = regionalKit;
        this.special = special;
        this.flavor = flavor;
        this.place = place;
        this.where = List.copyOf(where);
        this.sites = sites;
    }

    @Override
    public StructureType<?> type() {
        return DungeonsRegistry.DUNGEON_STRUCTURE.get();
    }

    /**
     * The map decides, not biomes: named places and site lists build where they are, and random sites follow the
     * region's culture ({@code where}) and the ground (dry, flat enough). The structure's {@code biomes} only has to
     * name some overworld biome so the set is considered at all.
     */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext ctx) {
        return findGenerationPoint(ctx);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        if (!sites.isEmpty()) return siteStart(ctx);
        if (place.isPresent()) return placeStart(ctx);
        return randomStart(ctx);
    }

    // ------------------------------------------------------------------ random sites

    private Optional<GenerationStub> randomStart(GenerationContext ctx) {
        ChunkPos cp = ctx.chunkPos();
        int x = cp.getMiddleBlockX();
        int z = cp.getMiddleBlockZ();
        Region region = TamrielMap.region(x, z);
        if (!Cultures.allows(where, region, x, z)) return Optional.empty();
        if (!Places.overlapping(x - PLACE_MARGIN, z - PLACE_MARGIN, x + PLACE_MARGIN, z + PLACE_MARGIN).isEmpty()) return Optional.empty();
        // never in or right next to a town (surface sites keep a wider berth than buried ones)
        boolean surfaceSite = !theme.underground() || theme == Theme.FORT;
        if (TownExclusion.nearTown(ctx, surfaceSite ? TOWN_RANGE_SURFACE : TOWN_RANGE_UNDERGROUND)) return Optional.empty();
        WorldgenRandom random = ctx.random();
        long seed = random.nextLong();
        Direction out = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        int fl = flavor >= 0 ? flavor : randomFlavor(random);
        Kit k = regionalKit ? Cultures.kitFor(region, x, z) : kit;
        int param = theme == Theme.PRINCE_SHRINE ? random.nextInt(SiteParams.Prince.VALUES.length) : 0;
        Site site = new Site(theme, k, special, fl, param);
        int sea = ctx.chunkGenerator().getSeaLevel();
        int minY = ctx.heightAccessor().getMinBuildHeight();
        boolean pad = terrainAdaptation() != TerrainAdjustment.NONE;

        if (!theme.underground() || theme == Theme.FORT) {
            int radius = Layout.surfaceRadius(site);
            int r = (int) (radius * 0.75f);
            int[] hs = {
                    surface(ctx, x, z), surface(ctx, x + r, z + r), surface(ctx, x - r, z + r),
                    surface(ctx, x + r, z - r), surface(ctx, x - r, z - r)
            };
            int[] sorted = hs.clone();
            Arrays.sort(sorted);
            if (sorted[0] == WATER || sorted[0] <= sea) return Optional.empty();
            int tolerance = theme == Theme.DRAGON_LAIR ? 12 : theme == Theme.FORT ? 7 : pad ? 10 : 6;
            if (sorted[4] - sorted[0] > tolerance) return Optional.empty();
            int base = theme == Theme.DRAGON_LAIR ? sorted[1] : sorted[2];
            if (theme == Theme.FORT) {
                int depth = theme.minDepth + random.nextInt(theme.maxDepth - theme.minDepth + 1);
                // the cellars stay under the hill even where it falls away from the walls
                int fDepth = Math.min(44, coverDepth(ctx, x, z, out, base, depth));
                if (base - fDepth < minY + 12) return Optional.empty();
                Layout.Ground g = ground(ctx);
                return Optional.of(new GenerationStub(new BlockPos(x, base, z), builder -> {
                    Layout.surface(builder, site, x, base, z, out, seed, false);
                    Layout.underground(builder, site, x + out.getStepX() * 3, base, z + out.getStepZ() * 3, out, fDepth,
                            seed ^ 0x5DEECE66DL, minY + 10, true, g, -2);
                }));
            }
            return Optional.of(new GenerationStub(new BlockPos(x, base, z),
                    builder -> Layout.surface(builder, site, x, base, z, out, seed, pad)));
        }

        int surface = surface(ctx, x, z);
        if (surface == WATER || surface <= sea) return Optional.empty();
        int depth = theme.minDepth + random.nextInt(theme.maxDepth - theme.minDepth + 1);
        Direction dOut = downhill(ctx, x, z, surface, out);
        depth = coverDepth(ctx, x, z, dOut, surface, depth);
        if (depth > 44) return Optional.empty();
        if (surface - depth < minY + 12) return Optional.empty();
        int fDepth = depth;
        Layout.Ground g = ground(ctx);
        return Optional.of(new GenerationStub(new BlockPos(x, surface, z),
                builder -> Layout.underground(builder, site, x, surface, z, dOut, fDepth, seed, minY + 10, false, g, -2)));
    }

    /** Sub-themes for random sites: caves pick among their rosters, everything else splits two ways. */
    private int randomFlavor(WorldgenRandom random) {
        return theme == Theme.CAVE ? CaveRoster.random(random.nextInt(CaveRoster.RANDOM_COUNT)) : random.nextInt(4);
    }

    /**
     * The side the ground falls away to, for a dungeon's mouth: the lowest of the four directions 12 and 24 blocks out
     * (the rolled direction wins ties and gentle slopes, so flat sites keep their variety).
     */
    private static Direction downhill(GenerationContext ctx, int x, int z, int here, Direction rolled) {
        Direction best = rolled;
        int bestH = rise(ctx, x, z, rolled) - 2;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int h = rise(ctx, x, z, d);
            if (h < bestH) {
                bestH = h;
                best = d;
            }
        }
        return best;
    }

    private static int rise(GenerationContext ctx, int x, int z, Direction d) {
        int a = ground(ctx, x + d.getStepX() * 12, z + d.getStepZ() * 12);
        int b = ground(ctx, x + d.getStepX() * 24, z + d.getStepZ() * 24);
        return Math.max(a, b);
    }

    /** Deepens the dungeon until the ground over the room grid covers it. */
    private int coverDepth(GenerationContext ctx, int x, int z, Direction out, int surface, int depth) {
        int back = depth + 4 + 6;
        int lx = x - out.getStepX() * back;
        int lz = z - out.getStepZ() * back;
        int lowest = surface;
        // the room grid reaches about 50 blocks round the entrance hall: probe its middle, edges and corners
        int[][] offsets = {{0, 0}, {28, 0}, {-28, 0}, {0, 28}, {0, -28}, {42, 42}, {-42, 42}, {42, -42}, {-42, -42},
                {45, 0}, {-45, 0}, {0, 45}, {0, -45}};
        for (int[] o : offsets) lowest = Math.min(lowest, ground(ctx, lx + o[0], lz + o[1]));
        int cover = theme.maxHeight + 3 + 7;
        if (surface - depth + cover > lowest) depth = surface - (lowest - cover);
        return depth;
    }

    // ------------------------------------------------------------------ named places

    private Optional<GenerationStub> placeStart(GenerationContext ctx) {
        PlaceFilter filter = place.get();
        Place pl = null;
        for (Place p : Places.inChunk(ctx.chunkPos())) {
            if (filter.matches(p)) {
                pl = p;
                break;
            }
        }
        if (pl == null) return Optional.empty();
        WorldgenRandom random = ctx.random();
        long seed = random.nextLong() ^ pl.id().hashCode();
        Direction rolled = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        // Labyrinthian's stair opens towards the city's main street
        // a dungeon's mouth opens downhill, never into the slope above it
        int h0 = theme.underground() && theme != Theme.FORT && special != Special.LABYRINTHIAN ? surface(ctx, pl.x(), pl.z()) : WATER;
        Direction out = special == Special.LABYRINTHIAN ? Direction.NORTH : h0 != WATER ? downhill(ctx, pl.x(), pl.z(), h0, rolled) : rolled;
        int fl = flavor >= 0 ? flavor : randomFlavor(random);
        Kit k = regionalKit ? Cultures.kitFor(TamrielMap.region(pl.x(), pl.z()), pl.x(), pl.z()) : kit;
        Site site = new Site(theme, k, special, fl, SiteParams.forPlace(theme, pl));
        int sea = ctx.chunkGenerator().getSeaLevel();
        int minY = ctx.heightAccessor().getMinBuildHeight();
        boolean pad = terrainAdaptation() != TerrainAdjustment.NONE;

        // stand on dry land: nudge off water (shore sites keep their spot)
        int x = pl.x(), z = pl.z();
        int h = surface(ctx, x, z);
        if (h == WATER && !theme.coastal()) {
            int[] dry = nearestDry(ctx, x, z, 56);
            if (dry != null) {
                x = dry[0];
                z = dry[1];
                h = dry[2];
            }
        }
        if (h == WATER) h = Math.max(ground(ctx, x, z), sea + (theme.coastal() ? -3 : 1));
        int fx = x, fz = z;
        com.skycraft.Skycraft.LOGGER.debug("Skycraft dungeons: {} ({}) starts at {} {} {}", pl.id(), theme.id, x, h, z);

        if (theme == Theme.SHIPWRECK) {
            int base = Math.max(Math.min(h, sea + 1), ground(ctx, x, z));
            Direction facing = out;
            return Optional.of(new GenerationStub(new BlockPos(x, base, z),
                    builder -> Layout.surface(builder, site, fx, base, fz, facing, seed, false)));
        }
        if (theme == Theme.FORT) {
            int base = h;
            int depth = Math.max(4, Math.min(44, Math.min(coverDepth(ctx, x, z, out, base, theme.maxDepth), base - (minY + 14))));
            Layout.Ground g = ground(ctx);
            return Optional.of(new GenerationStub(new BlockPos(x, base, z), builder -> {
                Layout.surface(builder, site, fx, base, fz, out, seed, false);
                Layout.underground(builder, site, fx + out.getStepX() * 3, base, fz + out.getStepZ() * 3, out, depth,
                        seed ^ 0x5DEECE66DL, minY + 10, true, g, 3);
            }));
        }
        if (!theme.underground()) {
            int base = h;
            if (theme == Theme.GREAT_LIFT) {
                return Optional.of(new GenerationStub(new BlockPos(x, base, z),
                        builder -> Layout.greatLift(builder, site, fx, base, fz, out, seed)));
            }
            Direction facing = facingFor(pl, out);
            return Optional.of(new GenerationStub(new BlockPos(x, base, z),
                    builder -> Layout.surface(builder, site, fx, base, fz, facing, seed, pad)));
        }
        // underground: the place is the entrance
        int surface = h;
        int depth = theme.minDepth + random.nextInt(theme.maxDepth - theme.minDepth + 1) + (special != Special.NONE ? 4 : 0);
        depth = Math.min(44, coverDepth(ctx, x, z, out, surface, depth));
        depth = Math.max(6, Math.min(depth, surface - (minY + 14)));
        int fDepth = depth;
        Direction fOut = out;
        Layout.Ground g = ground(ctx);
        return Optional.of(new GenerationStub(new BlockPos(x, surface, z), builder -> {
            Layout.underground(builder, site, fx, surface, fz, fOut, fDepth, seed, minY + 10, false, g, 3);
            if (special != Special.NONE && special != Special.LABYRINTHIAN) Layout.exterior(builder, site, fx, surface, fz, fOut, seed);
        }));
    }

    /** Uniques face a fixed way (the monastery towards the pilgrims' path); others face a seeded direction. */
    private Direction facingFor(Place pl, Direction random) {
        return switch (theme) {
            case HIGH_HROTHGAR -> Direction.EAST;
            case SKY_HAVEN -> Direction.EAST;
            case THROAT_SUMMIT -> Direction.NORTH;
            case LABYRINTHIAN -> Direction.NORTH;
            default -> random;
        };
    }

    /** The nearest dry land within {@code max} blocks (probing rings of 8 directions): {x, z, height}, or null. */
    private static int[] nearestDry(GenerationContext ctx, int x, int z, int max) {
        int sea = ctx.chunkGenerator().getSeaLevel();
        for (int d = 8; d <= max; d += 8) {
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4;
                int px = x + (int) Math.round(Math.cos(ang) * d), pz = z + (int) Math.round(Math.sin(ang) * d);
                int h = surface(ctx, px, pz);
                if (h != WATER && h > sea) return new int[]{px, pz, h};
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ site lists

    private Optional<GenerationStub> siteStart(GenerationContext ctx) {
        ChunkPos cp = ctx.chunkPos();
        int i = Sites.indexAt(sites, cp.x, cp.z);
        if (i < 0) return Optional.empty();
        Site site = new Site(theme, kit, special, Math.max(0, flavor), 0);
        long seed = ctx.random().nextLong();
        if (theme == Theme.BLACKREACH) {
            int[] o = Sites.point(sites, i);
            return Optional.of(new GenerationStub(new BlockPos(o[0] + 32, Blackreach.FLOOR_Y, o[1] + 32),
                    builder -> builder.addPiece(Blackreach.piece(site, o[0], o[1], i, seed))));
        }
        int[] a = Sites.point(sites, i), b = Sites.point(sites, i + 1);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int s = 0; s <= 6; s++) {
            int px = a[0] + (b[0] - a[0]) * s / 6, pz = a[1] + (b[1] - a[1]) * s / 6;
            int h = ground(ctx, px, pz);
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        int route = Steps.routeIndex(sites);
        BoundingBox box = new BoundingBox(Math.min(a[0], b[0]) - 6, lo - 16, Math.min(a[1], b[1]) - 6,
                Math.max(a[0], b[0]) + 6, hi + 16, Math.max(a[1], b[1]) + 6);
        int fl = lo, fh = hi;
        return Optional.of(new GenerationStub(new BlockPos(a[0], fl, a[1]), builder -> builder.addPiece(
                new DungeonPiece(site, DungeonPiece.Kind.SURFACE, box, seed, fl, fh, 0, i, DungeonPiece.ROLE_NORMAL, 0, route, 0))));
    }

    // ------------------------------------------------------------------ terrain probes (noise heights only)

    /** Walking level of dry land at (x, z), or {@link #WATER} if water covers it. */
    static int surface(GenerationContext ctx, int x, int z) {
        ChunkGenerator gen = ctx.chunkGenerator();
        int land = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
        int top = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
        if (top > land) return WATER;
        // the map's lakes and rivers above sea level are filled by a feature later, so the noise heights don't see them
        if (gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator ng
                && ng.generatorSettings().is(com.skycraft.tamriel.terrain.TamrielSpawn.TAMRIEL_SETTINGS)) {
            com.skycraft.tamriel.MapSample s = new com.skycraft.tamriel.MapSample();
            TamrielMap.sample(x, z, s);
            if (s.inWater() && land < s.waterLevel) return WATER;
            // nor is the floor of a cave mouth far below the surrounding ground a building spot (treated like water)
            if (s.coastDistance > 32 && land < s.elevation - 24) return WATER;
        }
        return land;
    }

    /** The back exit's surface probe: noise heights only (the planner asks for one or two columns). */
    private static Layout.Ground ground(GenerationContext ctx) {
        return (x, z) -> {
            int h = surface(ctx, x, z);
            return h == WATER ? Integer.MIN_VALUE : h;
        };
    }

    /** Solid ground level at (x, z), even under water. */
    static int ground(GenerationContext ctx, int x, int z) {
        return ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
    }
}
