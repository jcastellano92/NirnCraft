package com.skycraft.dungeons.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.dungeons.DungeonsRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Arrays;
import java.util.Optional;

/**
 * One structure type for every Skyrim dungeon. The {@code theme} field of the structure JSON decides what gets
 * built; the layout itself is procedural ({@link Layout}): a surface entrance, a stair tunnel and a tree of rooms
 * joined by corridors, or a single surface site.
 *
 * <p>{@link #findGenerationPoint} only samples a handful of terrain heights; pieces are planned lazily when the
 * start is created and painted chunk by chunk.</p>
 */
public class DungeonStructure extends Structure {
    public static final Codec<DungeonStructure> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            settingsCodec(instance),
            Theme.CODEC.fieldOf("theme").forGetter(s -> s.theme)
    ).apply(instance, DungeonStructure::new));

    private static final int WATER = Integer.MIN_VALUE;
    /** No dungeon starts within this many chunks of a {@code #skycraft:towns} start. */
    private static final int TOWN_RANGE_SURFACE = 6;
    private static final int TOWN_RANGE_UNDERGROUND = 4;

    public final Theme theme;

    public DungeonStructure(StructureSettings settings, Theme theme) {
        super(settings);
        this.theme = theme;
    }

    @Override
    public StructureType<?> type() {
        return DungeonsRegistry.DUNGEON_STRUCTURE.get();
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        ChunkPos cp = ctx.chunkPos();
        // never in or right next to a town (surface sites keep a wider berth than buried ones)
        boolean surfaceSite = !theme.underground() || theme == Theme.FORT;
        if (TownExclusion.nearTown(ctx, surfaceSite ? TOWN_RANGE_SURFACE : TOWN_RANGE_UNDERGROUND)) return Optional.empty();
        int x = cp.getMiddleBlockX();
        int z = cp.getMiddleBlockZ();
        WorldgenRandom random = ctx.random();
        long seed = random.nextLong();
        Direction out = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        int flavor = random.nextInt(4);
        int sea = ctx.chunkGenerator().getSeaLevel();
        int minY = ctx.heightAccessor().getMinBuildHeight();

        if (!theme.underground() || theme == Theme.FORT) {
            int radius = Layout.surfaceRadius(theme);
            int r = (int) (radius * 0.75f);
            int[] hs = {
                    surface(ctx, x, z), surface(ctx, x + r, z + r), surface(ctx, x - r, z + r),
                    surface(ctx, x + r, z - r), surface(ctx, x - r, z - r)
            };
            int[] sorted = hs.clone();
            Arrays.sort(sorted);
            if (sorted[0] == WATER || sorted[0] <= sea) return Optional.empty();
            int tolerance = theme == Theme.DRAGON_LAIR ? 12 : theme == Theme.FORT ? 7 : 6;
            if (sorted[4] - sorted[0] > tolerance) return Optional.empty();
            int base = theme == Theme.DRAGON_LAIR ? sorted[1] : sorted[2];
            if (theme == Theme.FORT) {
                int depth = theme.minDepth + random.nextInt(theme.maxDepth - theme.minDepth + 1);
                if (base - depth < minY + 12) return Optional.empty();
                int fx = x, fz = z;
                return Optional.of(new GenerationStub(new BlockPos(x, base, z), builder -> {
                    Layout.surface(builder, theme, fx, base, fz, out, seed, flavor);
                    Layout.underground(builder, theme, fx + out.getStepX() * 3, base, fz + out.getStepZ() * 3, out, depth,
                            seed ^ 0x5DEECE66DL, minY + 10, flavor, true);
                }));
            }
            int fx = x, fz = z;
            return Optional.of(new GenerationStub(new BlockPos(x, base, z),
                    builder -> Layout.surface(builder, theme, fx, base, fz, out, seed, flavor)));
        }

        int surface = surface(ctx, x, z);
        if (surface == WATER || surface <= sea) return Optional.empty();
        int depth = theme.minDepth + random.nextInt(theme.maxDepth - theme.minDepth + 1);
        // keep the rooms buried: look at the ground over where the layout will sit
        int back = depth + 4 + 6;
        int lx = x - out.getStepX() * back;
        int lz = z - out.getStepZ() * back;
        int lowest = surface;
        int[][] offsets = {{0, 0}, {28, 0}, {-28, 0}, {0, 28}, {0, -28}};
        for (int[] o : offsets) {
            lowest = Math.min(lowest, ground(ctx, lx + o[0], lz + o[1]));
        }
        int cover = theme.maxHeight + 3 + 7;
        if (surface - depth + cover > lowest) depth = surface - (lowest - cover);
        if (depth > 44) return Optional.empty();
        int floor0 = surface - depth;
        if (floor0 < minY + 12) return Optional.empty();
        int fDepth = depth;
        return Optional.of(new GenerationStub(new BlockPos(x, surface, z),
                builder -> Layout.underground(builder, theme, x, surface, z, out, fDepth, seed, minY + 10, flavor, false)));
    }

    /** Walking level of dry land at (x, z), or {@link #WATER} if water covers it. */
    private static int surface(GenerationContext ctx, int x, int z) {
        ChunkGenerator gen = ctx.chunkGenerator();
        int land = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
        int top = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
        return top > land ? WATER : land;
    }

    /** Solid ground level at (x, z), even under water. */
    private static int ground(GenerationContext ctx, int x, int z) {
        return ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
    }
}
