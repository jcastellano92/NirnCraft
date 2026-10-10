package com.skycraft.dungeons.world;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/**
 * Chunk-local block writer for one piece. Every write is clipped to both the piece's bounding box and the chunk
 * being generated, so a piece can describe itself in full on every call and each chunk only receives its own part.
 * Decorative variety comes from position hashes (never from the per-chunk random), so the result is seamless
 * whatever order chunks generate in. Entities and loot containers are created only by the chunk that owns them.
 */
public final class Painter {
    public final WorldGenLevel level;
    public final BoundingBox chunk;
    public final BoundingBox box;
    public final long seed;
    /** The piece's culture kit: masonry palettes consult it ({@link BuiltBuilder#wall} and friends). */
    public Kit kit = Kit.NONE;
    private final int x0, y0, z0, x1, y1, z1;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public Painter(WorldGenLevel level, BoundingBox chunk, BoundingBox box, long seed) {
        this.level = level;
        this.chunk = chunk;
        this.box = box;
        this.seed = seed;
        this.x0 = Math.max(chunk.minX(), box.minX());
        this.y0 = Math.max(Math.max(chunk.minY(), box.minY()), level.getMinBuildHeight());
        this.z0 = Math.max(chunk.minZ(), box.minZ());
        this.x1 = Math.min(chunk.maxX(), box.maxX());
        this.y1 = Math.min(Math.min(chunk.maxY(), box.maxY()), level.getMaxBuildHeight() - 1);
        this.z1 = Math.min(chunk.maxZ(), box.maxZ());
    }

    // ------------------------------------------------------------------ clip region (iterate only what this chunk owns)

    public int minX() { return x0; }
    public int minY() { return y0; }
    public int minZ() { return z0; }
    public int maxX() { return x1; }
    public int maxY() { return y1; }
    public int maxZ() { return z1; }

    public boolean empty() {
        return x0 > x1 || y0 > y1 || z0 > z1;
    }

    public boolean ok(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }

    // ------------------------------------------------------------------ blocks

    public BlockState get(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    public void set(int x, int y, int z, BlockState state) {
        if (!ok(x, y, z)) return;
        pos.set(x, y, z);
        BlockState old = level.getBlockState(pos);
        if (old.is(BlockTags.FEATURES_CANNOT_REPLACE)) return;
        level.setBlock(pos, state, 2);
    }

    public void air(int x, int y, int z) {
        if (!ok(x, y, z)) return;
        pos.set(x, y, z);
        BlockState old = level.getBlockState(pos);
        if (old.isAir() || old.is(BlockTags.FEATURES_CANNOT_REPLACE)) return;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
    }

    public void fill(int ax, int ay, int az, int bx, int by, int bz, BlockState state) {
        int minX = Math.max(Math.min(ax, bx), x0), maxX = Math.min(Math.max(ax, bx), x1);
        int minY = Math.max(Math.min(ay, by), y0), maxY = Math.min(Math.max(ay, by), y1);
        int minZ = Math.max(Math.min(az, bz), z0), maxZ = Math.min(Math.max(az, bz), z1);
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) set(x, y, z, state);
    }

    /** Sets the block only where the world currently has air, fluid or a replaceable plant. */
    public void setIfOpen(int x, int y, int z, BlockState state) {
        if (!ok(x, y, z)) return;
        if (open(get(x, y, z))) set(x, y, z, state);
    }

    public static boolean open(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)
                || s.is(BlockTags.FLOWERS) || s.is(Blocks.SNOW) || s.is(Blocks.POWDER_SNOW);
    }

    /** True for natural ground a foundation may rest on. */
    public static boolean ground(BlockState s) {
        return !open(s) && s.isSolid();
    }

    /** Fills from {@code topY} downwards with {@code state} until solid ground (at most {@code maxDepth} blocks). */
    public void foundation(int x, int topY, int z, BlockState state, int maxDepth) {
        if (x < x0 || x > x1 || z < z0 || z > z1) return;
        for (int y = topY; y > topY - maxDepth; y--) {
            if (y < y0) return;
            if (y <= y1 && ground(get(x, y, z)) && y < topY) return;
            set(x, y, z, state);
        }
    }

    /** Clears plants, snow, leaves and terrain from {@code fromY} up to {@code toY}. */
    public void clear(int x, int fromY, int z, int toY) {
        for (int y = Math.max(fromY, y0); y <= Math.min(toY, y1); y++) air(x, y, z);
    }

    /** A carved natural block: solid neighbours that are fluids (aquifers, lava) or falling blocks get sealed. */
    public void carve(int x, int y, int z, BlockState seal) {
        if (!ok(x, y, z)) return;
        air(x, y, z);
        sealIfFluid(x + 1, y, z, seal);
        sealIfFluid(x - 1, y, z, seal);
        sealIfFluid(x, y, z + 1, seal);
        sealIfFluid(x, y, z - 1, seal);
        sealIfFluid(x, y - 1, z, seal);
        if (ok(x, y + 1, z)) {
            BlockState above = get(x, y + 1, z);
            if (!above.getFluidState().isEmpty() || above.getBlock() instanceof FallingBlock) set(x, y + 1, z, seal);
        }
    }

    private void sealIfFluid(int x, int y, int z, BlockState seal) {
        if (!ok(x, y, z)) return;
        if (!get(x, y, z).getFluidState().isEmpty()) set(x, y, z, seal);
    }

    // ------------------------------------------------------------------ deterministic variety

    public long hash(int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0xD6E8FEB86659FD93L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** Uniform int in [0, n). */
    public int roll(int x, int y, int z, int salt, int n) {
        return (int) Math.floorMod(hash(x, y, z, salt), (long) n);
    }

    public boolean chance(int x, int y, int z, int salt, int oneIn) {
        return roll(x, y, z, salt, oneIn) == 0;
    }

    /** Smooth value noise in [-1, 1] with the given cell size. */
    public double noise(double x, double y, double z, double scale, int salt) {
        double fx = x / scale, fy = y / scale, fz = z / scale;
        int ix = Mth.floor(fx), iy = Mth.floor(fy), iz = Mth.floor(fz);
        double tx = smooth(fx - ix), ty = smooth(fy - iy), tz = smooth(fz - iz);
        double c000 = lattice(ix, iy, iz, salt), c100 = lattice(ix + 1, iy, iz, salt);
        double c010 = lattice(ix, iy + 1, iz, salt), c110 = lattice(ix + 1, iy + 1, iz, salt);
        double c001 = lattice(ix, iy, iz + 1, salt), c101 = lattice(ix + 1, iy, iz + 1, salt);
        double c011 = lattice(ix, iy + 1, iz + 1, salt), c111 = lattice(ix + 1, iy + 1, iz + 1, salt);
        double x00 = Mth.lerp(tx, c000, c100), x10 = Mth.lerp(tx, c010, c110);
        double x01 = Mth.lerp(tx, c001, c101), x11 = Mth.lerp(tx, c011, c111);
        return Mth.lerp(tz, Mth.lerp(ty, x00, x10), Mth.lerp(ty, x01, x11));
    }

    private double lattice(int x, int y, int z, int salt) {
        return (hash(x, y, z, salt + 7919) & 0xFFFF) / 32767.5 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * Height of the first free block above the terrain of column (x, z), from the chunk's worldgen heightmap. Only
     * for columns of the chunk being generated (it never reads neighbours); {@link Integer#MIN_VALUE} otherwise.
     */
    public int surfaceAt(int x, int z) {
        if (x < chunk.minX() || x > chunk.maxX() || z < chunk.minZ() || z > chunk.maxZ()) return Integer.MIN_VALUE;
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, x, z);
    }

    /** True when (x, z) is a column of the chunk being generated. */
    public boolean ownsColumn(int x, int z) {
        return x >= chunk.minX() && x <= chunk.maxX() && z >= chunk.minZ() && z <= chunk.maxZ();
    }

    /** The block entity at a position this chunk owns, or null. */
    @Nullable
    public BlockEntity blockEntity(int x, int y, int z) {
        if (!ok(x, y, z)) return null;
        return level.getBlockEntity(pos.set(x, y, z));
    }

    // ------------------------------------------------------------------ containers & entities

    /** A container (chest, barrel, trapped chest) holding the loot table. Lockpickable via the crime module. */
    public void container(int x, int y, int z, BlockState state, ResourceLocation loot) {
        if (!ok(x, y, z)) return;
        set(x, y, z, state);
        pos.set(x, y, z);
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity) {
            RandomizableContainerBlockEntity.setLootTable(level, RandomSource.create(hash(x, y, z, 31)), pos.immutable(), loot);
        }
    }

    /** An arrow trap dispenser facing {@code facing}, loaded with arrows. */
    public void dispenser(int x, int y, int z, Direction facing, ItemStack ammo) {
        if (!ok(x, y, z)) return;
        set(x, y, z, Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(pos.set(x, y, z));
        if (be instanceof DispenserBlockEntity d) d.setItem(0, ammo.copy());
    }

    /** Looks a block up by id; {@code fallback} when the owning mod/module isn't present. */
    public static BlockState block(String id, BlockState fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !ForgeRegistries.BLOCKS.containsKey(rl)) return fallback;
        Block b = ForgeRegistries.BLOCKS.getValue(rl);
        return b == null ? fallback : b.defaultBlockState();
    }

    /** Sets a horizontal "facing" property if the state has one (for blocks looked up by id). */
    public static BlockState facing(BlockState state, Direction dir) {
        Property<?> prop = state.getBlock().getStateDefinition().getProperty("facing");
        if (prop != null && prop.getValueClass() == Direction.class && prop.getPossibleValues().contains(dir)) {
            return with(state, prop, dir);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> prop, Object value) {
        return state.setValue(prop, prop.getValueClass().cast(value));
    }

    public static boolean entityExists(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && ForgeRegistries.ENTITY_TYPES.containsKey(rl);
    }

    /** Picks the first registered id, or null. */
    @Nullable
    public static String firstExisting(String... ids) {
        for (String id : ids) if (id != null && entityExists(id)) return id;
        return null;
    }

    /**
     * Spawns a persistent mob standing on the block at (x, y, z) if that block belongs to this chunk.
     * Unknown ids (module or mod missing) are skipped silently.
     */
    @Nullable
    public Mob spawn(@Nullable String id, int x, int y, int z, float yaw, @Nullable String name) {
        return spawn(id, x, y, z, yaw, name, null);
    }

    /**
     * A draugr (or any undead) lying dormant in its sarcophagus or alcove: no AI until a player comes close or hurts
     * it ({@link DormantMobs}).
     */
    @Nullable
    public Mob spawnDormant(@Nullable String id, int x, int y, int z, float yaw) {
        return spawn(id, x, y, z, yaw, null, DormantMobs::makeDormant);
    }

    /** {@link #spawn} with a hook that runs before the mob is added (proto chunks serialize entities on add). */
    @Nullable
    public Mob spawn(@Nullable String id, int x, int y, int z, float yaw, @Nullable String name,
                     @Nullable java.util.function.Consumer<Mob> setup) {
        if (id == null || !ok(x, y, z) || !chunk.isInside(new BlockPos(x, y, z))) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !ForgeRegistries.ENTITY_TYPES.containsKey(rl)) return null;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        if (type == null) return null;
        try {
            Entity e = type.create(level.getLevel());
            if (!(e instanceof Mob mob)) {
                if (e != null) e.discard();
                return null;
            }
            mob.moveTo(x + 0.5, y, z + 0.5, yaw, 0.0f);
            mob.setYHeadRot(yaw);
            // through Forge so MobSpawnEvent.FinalizeSpawn handlers (levelling, gear...) see dungeon mobs too
            net.minecraftforge.event.ForgeEventFactory.onFinalizeSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(x, y, z)),
                    MobSpawnType.STRUCTURE, null, null);
            if (mob.isSpawnCancelled()) {
                mob.discard();
                return null;
            }
            mob.setPersistenceRequired();
            if (name != null) mob.setCustomName(Component.literal(name));
            CompoundTag data = mob.getPersistentData();
            data.putBoolean("skycraft_dungeon", true);
            if (setup != null) setup.accept(mob);
            level.addFreshEntityWithPassengers(mob);
            return mob;
        } catch (RuntimeException ex) {
            Skycraft.LOGGER.debug("Skycraft dungeons: could not spawn {}", id, ex);
            return null;
        }
    }

    /** A chest minecart with loot (mines). */
    public void chestMinecart(int x, int y, int z, ResourceLocation loot) {
        if (!ok(x, y, z)) return;
        MinecartChest cart = new MinecartChest(level.getLevel(), x + 0.5, y + 0.5, z + 0.5);
        cart.setLootTable(loot, hash(x, y, z, 77));
        level.addFreshEntity(cart);
    }

    /** Yaw (degrees) for a mob looking towards {@code dir}. */
    public static float yaw(Direction dir) {
        return dir.toYRot();
    }
}
