package com.skycraft.dungeons.qa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * An in-memory {@link WorldGenLevel} for the dungeon analyzer: dungeon pieces paint into it exactly as they paint into
 * a chunk during world generation, but nothing touches the real world. Blocks inside {@link #area} are stored in an
 * array; everything else is the modelled terrain (rock under the sampled ground height, sea water up to sea level, air
 * above). Mobs and minecarts the pieces spawn are recorded instead of added. World-wide queries (biomes, difficulty,
 * registries, the server) are answered by the real overworld, so spawn and loot hooks see the dungeon's real place.
 */
public final class QaLevel implements WorldGenLevel {
    /** Ground model: the y of the topmost solid block of column (x, z). */
    public interface Ground {
        int top(int x, int z);
    }

    public final ServerLevel real;
    public final BoundingBox area;
    private final Ground ground;
    private final int sea;
    private final int sx, sy, sz;
    private final BlockState[] states;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    /** Entities the pieces asked to add (mobs, chest minecarts), in order. */
    public final List<Entity> spawned = new ArrayList<>();

    public QaLevel(ServerLevel real, BoundingBox area, Ground ground) {
        this.real = real;
        this.area = area;
        this.ground = ground;
        this.sea = real.getSeaLevel();
        this.sx = area.getXSpan();
        this.sy = area.getYSpan();
        this.sz = area.getZSpan();
        this.states = new BlockState[sx * sy * sz];
    }

    private int index(int x, int y, int z) {
        if (x < area.minX() || x > area.maxX() || y < area.minY() || y > area.maxY() || z < area.minZ() || z > area.maxZ()) return -1;
        return ((x - area.minX()) * sz + (z - area.minZ())) * sy + (y - area.minY());
    }

    /** The modelled terrain at a position nothing has painted. */
    public BlockState background(int x, int y, int z) {
        int top = ground.top(x, z);
        if (y <= top) return y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        if (y < sea) return Blocks.WATER.defaultBlockState();
        return Blocks.AIR.defaultBlockState();
    }

    public int groundTop(int x, int z) {
        return ground.top(x, z);
    }

    public BlockState get(int x, int y, int z) {
        int i = index(x, y, z);
        BlockState s = i >= 0 ? states[i] : null;
        return s != null ? s : background(x, y, z);
    }

    /** True if a piece painted this position (as opposed to the modelled terrain). */
    public boolean painted(int x, int y, int z) {
        int i = index(x, y, z);
        return i >= 0 && states[i] != null;
    }

    public void put(BlockPos pos, BlockState state) {
        int i = index(pos.getX(), pos.getY(), pos.getZ());
        if (i < 0) return;
        BlockState old = states[i];
        states[i] = state;
        if (old != null && old.hasBlockEntity() && (!state.hasBlockEntity() || old.getBlock() != state.getBlock())) {
            blockEntities.remove(pos);
        }
        if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock eb) {
            BlockEntity be = blockEntities.get(pos);
            if (be == null || !be.getType().isValid(state)) {
                BlockPos key = pos.immutable();
                be = eb.newBlockEntity(key, state);
                if (be != null) { be.setLevel(real); blockEntities.put(key, be); }
            } else {
                be.setBlockState(state);
            }
        }
    }

    public Map<BlockPos, BlockEntity> blockEntities() {
        return blockEntities;
    }

    // ------------------------------------------------------------------ BlockGetter / writer

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
        BlockEntity be = blockEntities.get(pos);
        return be != null && be.getType() == type ? Optional.of((T) be) : Optional.empty();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        put(pos, state);
        return true;
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean moving) {
        put(pos, getFluidState(pos).createLegacyBlock());
        return true;
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursionLeft) {
        put(pos, Blocks.AIR.defaultBlockState());
        return true;
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        for (int y = area.maxY(); y >= area.minY(); y--) {
            if (type.isOpaque().test(get(x, y, z))) return y + 1;
        }
        int top = ground.top(x, z);
        boolean water = type == Heightmap.Types.WORLD_SURFACE_WG || type == Heightmap.Types.WORLD_SURFACE
                || type == Heightmap.Types.MOTION_BLOCKING || type == Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
        return (water ? Math.max(top, sea - 1) : top) + 1;
    }

    @Override
    public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
        return new BlockPos(pos.getX(), getHeight(type, pos.getX(), pos.getZ()), pos.getZ());
    }

    // ------------------------------------------------------------------ entities: recorded, never added

    @Override
    public boolean addFreshEntity(Entity entity) {
        spawned.add(entity);
        return true;
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> predicate) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) {
        return List.of();
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) {
        return List.of();
    }

    // ------------------------------------------------------------------ the rest: the real overworld, or nothing

    @Override
    public long getSeed() {
        return real.getSeed();
    }

    @Override
    public ServerLevel getLevel() {
        return real;
    }

    @Override
    public long nextSubTickCount() {
        return real.nextSubTickCount();
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelData getLevelData() {
        return real.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return real.getCurrentDifficultyAt(pos);
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        return real.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return real.getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return real.getRandom();
    }

    @Override
    public void playSound(@Nullable Entity player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
    }

    @Override
    public void levelEvent(@Nullable Entity player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean require) {
        return null;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return true;
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public BiomeManager getBiomeManager() {
        return real.getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return real.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return sea;
    }

    @Override
    public DimensionType dimensionType() {
        return real.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return real.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return real.enabledFeatures();
    }

    public float getShade(Direction direction, boolean shade) {
        return 1.0f;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return real.getLightEngine();
    }

    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return 0;
    }

    @Override
    public WorldBorder getWorldBorder() {
        return real.getWorldBorder();
    }

    @Nullable
    @Override
    public BlockGetter getChunkForCollisions(int x, int z) {
        return this;
    }

    public long dayTime() {
        return real.getOverworldClockTime();
    }

    @Override
    public int getHeight() {
        return real.getHeight();
    }

    @Override
    public int getMinY() {
        return real.getMinY();
    }

    @Override public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() { return real.environmentAttributes(); }
}
