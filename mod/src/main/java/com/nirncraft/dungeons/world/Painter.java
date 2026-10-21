package com.nirncraft.dungeons.world;

import com.nirncraft.NirnCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

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
    /** The named place being painted ({@link Identity}), "" for random sites. */
    public String place = "";
    /**
     * How the room being painted is staffed ({@link Pacing}): ordinary mobs of an empty room never spawn, those of a
     * sleeping room spawn dormant, and a packed room gets a second of each. Bosses and elites ignore it.
     */
    public int mood = Pacing.NORMAL;
    private boolean packing;
    private final int x0, y0, z0, x1, y1, z1;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public Painter(WorldGenLevel level, BoundingBox chunk, BoundingBox box, long seed) {
        this.level = level;
        this.chunk = chunk;
        this.box = box;
        this.seed = seed;
        this.x0 = Math.max(chunk.minX(), box.minX());
        this.y0 = Math.max(Math.max(chunk.minY(), box.minY()), level.getMinY());
        this.z0 = Math.max(chunk.minZ(), box.minZ());
        this.x1 = Math.min(chunk.maxX(), box.maxX());
        this.y1 = Math.min(Math.min(chunk.maxY(), box.maxY()), (level.getMaxY() + 1) - 1);
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

    // ------------------------------------------------------------------ analyzer hook

    /**
     * The dungeon analyzer ({@code dungeons.qa}) listens here for the set pieces and furnishings the builders announce,
     * keyed by the piece's box. Unset (and free) during real world generation.
     */
    public static final ThreadLocal<java.util.function.BiConsumer<BoundingBox, String>> FEATURES = new ThreadLocal<>();

    /**
     * Set while the client's overlays record a dungeon's blocks (AtlasOverlayTapes.recordRaw, over a level with no
     * world behind it): no mobs and no minecarts (entities, not blocks). Unset during real world generation.
     */
    public static final ThreadLocal<Boolean> RECORDING = new ThreadLocal<>();

    /** Announces a set piece or room purpose ("sarcophagus_hall", "story:journal"...) to the analyzer, if one listens. */
    public void feature(String id) {
        java.util.function.BiConsumer<BoundingBox, String> c = FEATURES.get();
        if (c != null) c.accept(box, id);
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
        if (state.hasBlockEntity()) {
            BlockPos imm = pos.immutable();
            level.setBlock(imm, state, 2);
            if (state.is(Blocks.DISPENSER) && level.getBlockEntity(imm) instanceof DispenserBlockEntity d && d.isEmpty()) {
                d.setItem(0, Deco.ARROWS.copy());
                d.getPersistentData().putBoolean("nirncraft_stocked", true);
            }
        } else {
            level.setBlock(pos, state, 2);
        }
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
        return level.getBlockEntity(new BlockPos(x, y, z));
    }

    // ------------------------------------------------------------------ containers & entities

    /** A container (chest, barrel, trapped chest) holding the loot table. Lockpickable via the crime module. */
    public void container(int x, int y, int z, BlockState state, Identifier loot) {
        if (!ok(x, y, z)) return;
        set(x, y, z, state);
        BlockPos imm = new BlockPos(x, y, z);
        // a named place's ordinary chests hold what its story says (half of them; boss and cache chests keep theirs)
        loot = Identity.loot(place, loot, hash(x, y, z, 32));
        if (level.getBlockEntity(imm) instanceof RandomizableContainerBlockEntity) {
            com.nirncraft.worldgen.WorldgenData.setLootTable(level, RandomSource.create(hash(x, y, z, 31)), imm, loot);
        }
    }

    /** An arrow trap dispenser facing {@code facing}, loaded with arrows. */
    public void dispenser(int x, int y, int z, Direction facing, ItemStack ammo) {
        if (!ok(x, y, z)) return;
        set(x, y, z, Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, facing));
        BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
        if (be instanceof DispenserBlockEntity d) {
            d.setItem(0, ammo.copy());
            d.getPersistentData().putBoolean("nirncraft_stocked", true);
        }
    }

    /** Looks a block up by id; {@code fallback} when the owning mod/module isn't present. */
    public static BlockState block(String id, BlockState fallback) {
        Identifier rl = Identifier.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return fallback;
        Block b = BuiltInRegistries.BLOCK.getValue(rl);
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
        Identifier rl = Identifier.tryParse(id);
        return rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
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
        return spawn(id, x, y, z, yaw, null, DormantMobs::makeDormant, true);
    }

    /** The dungeon's boss: tagged for playtest telemetry and the analyzer ({@code DungeonTelemetry#BOSS_TAG}). */
    @Nullable
    public Mob spawnBoss(@Nullable String id, int x, int y, int z, float yaw, @Nullable String name) {
        // a named place's master carries the name its story gives it
        if (name == null) name = Identity.bossName(place);
        return spawn(id, x, y, z, yaw, name, m -> {
            // the master of the place outlasts its guards
            var hp = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.5);
            m.setHealth(m.getMaxHealth());
            m.getPersistentData().putBoolean(com.nirncraft.dungeons.DungeonTelemetry.BOSS_TAG, true);
        }, true);
    }

    /** A tougher guard (the mini-boss beat): more health and a harder hit than its kind, tagged for the analyzer. */
    @Nullable
    public Mob spawnElite(@Nullable String id, int x, int y, int z, float yaw, @Nullable String name) {
        return spawn(id, x, y, z, yaw, name, m -> {
            var hp = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.75);
            var dmg = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * 1.3);
            m.setHealth(m.getMaxHealth());
            m.getPersistentData().putBoolean("nirncraft_dungeon_elite", true);
        }, true);
    }

    /** Asks the fluid at (x, y, z) to start flowing once the chunk ticks (a waterfall's source). */
    public void scheduleFluid(int x, int y, int z) {
        if (!ok(x, y, z)) return;
        BlockPos at = new BlockPos(x, y, z);
        net.minecraft.world.level.material.FluidState f = level.getFluidState(at);
        if (!f.isEmpty()) level.scheduleTick(at, f.getType(), 1);
    }

    /** {@link #spawn} with a hook that runs before the mob is added (proto chunks serialize entities on add). */
    @Nullable
    public Mob spawn(@Nullable String id, int x, int y, int z, float yaw, @Nullable String name,
                     @Nullable java.util.function.Consumer<Mob> setup) {
        return spawn(id, x, y, z, yaw, name, setup, false);
    }

    /** @param essential bosses, elites and sleepers placed on purpose: the room's {@link #mood} does not touch them */
    @Nullable
    private Mob spawn(@Nullable String id, int x, int y, int z, float yaw, @Nullable String name,
                      @Nullable java.util.function.Consumer<Mob> setup, boolean essential) {
        if (RECORDING.get() != null) return null;
        if (!essential) {
            if (mood == Pacing.EMPTY) return null;
            if (mood == Pacing.SLEEP) {
                java.util.function.Consumer<Mob> inner = setup;
                setup = m -> {
                    if (inner != null) inner.accept(m);
                    DormantMobs.makeDormant(m);
                };
            }
        }
        if (id == null || !ok(x, y, z) || !chunk.isInside(new BlockPos(x, y, z))) return null;
        Identifier rl = Identifier.tryParse(id);
        if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) return null;
        // bosses and bandits are never placed near the world spawn
        if ("nirncraft".equals(rl.getNamespace()) && com.nirncraft.creatures.spawn.SpawnPacing.bannedNearStart(rl.getPath())
                && com.nirncraft.creatures.spawn.SpawnPacing.nearStart(level.getLevel(), x, z)) return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(rl);
        if (type == null) return null;
        try {
            Entity e = type.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
            if (!(e instanceof Mob mob)) {
                if (e != null) e.discard();
                return null;
            }
            mob.snapTo(x + 0.5, y, z + 0.5, yaw, 0.0f);
            mob.setYHeadRot(yaw);
            // through Forge so MobSpawnEvent.FinalizeSpawn handlers (levelling, gear...) see dungeon mobs too
            net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(new BlockPos(x, y, z)),
                    EntitySpawnReason.STRUCTURE, null);
            if (mob.isSpawnCancelled()) {
                mob.discard();
                return null;
            }
            mob.setPersistenceRequired();
            if (name != null) mob.setCustomName(Component.literal(name));
            CompoundTag data = mob.getPersistentData();
            data.putBoolean("nirncraft_dungeon", true);
            if (setup != null) setup.accept(mob);
            level.addFreshEntityWithPassengers(mob);
            if (!essential && mood == Pacing.PACK && !packing && setup == null && name == null) {
                // a packed room: a second of each, on a free spot beside the first
                packing = true;
                try {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int sx = x + d.getStepX(), sz = z + d.getStepZ();
                        if (ok(sx, y, sz) && get(sx, y, sz).isAir() && get(sx, y + 1, sz).isAir() && !get(sx, y - 1, sz).isAir()) {
                            spawn(id, sx, y, sz, yaw + 90f, null, null, false);
                            break;
                        }
                    }
                } finally {
                    packing = false;
                }
            }
            return mob;
        } catch (RuntimeException ex) {
            NirnCraft.LOGGER.debug("NirnCraft dungeons: could not spawn {}", id, ex);
            return null;
        }
    }

    /** A chest minecart with loot (mines). */
    public void chestMinecart(int x, int y, int z, Identifier loot) {
        if (!ok(x, y, z) || RECORDING.get() != null) return;
        MinecartChest cart = new net.minecraft.world.entity.vehicle.minecart.MinecartChest(net.minecraft.world.entity.EntityTypes.CHEST_MINECART, level.getLevel());
        cart.setPos( x + 0.5, y + 0.5, z + 0.5);
        cart.setLootTable(net.minecraft.resources.ResourceKey.create(Registries.LOOT_TABLE, loot), hash(x, y, z, 77));
        level.addFreshEntity(cart);
    }

    /** Yaw (degrees) for a mob looking towards {@code dir}. */
    public static float yaw(Direction dir) {
        return dir.toYRot();
    }
}
