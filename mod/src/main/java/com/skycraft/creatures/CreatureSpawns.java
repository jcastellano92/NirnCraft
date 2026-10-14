package com.skycraft.creatures;

import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.creatures.entity.SkeeverEntity;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skyrim-like world population: draugr replace zombies in the cold north and frostbite spiders the spiders of caves,
 * vanilla night monsters make way for skeevers and (a few, transient) wolves, phantoms are disabled, dragons attack
 * travelers, and iron golems make way for nothing (the guards are the settlement's own: society.GuardWatch).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CreatureSpawns {
    private static final int GUARD_CHECK_INTERVAL = 600;
    private static final int MIN_GUARDS = 2;
    private static final int MAX_GUARDS = 4;

    private CreatureSpawns() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (CreaturesConfig.DISABLE_INSOMNIA.get()) {
            event.getServer().getGameRules().getRule(GameRules.RULE_DOINSOMNIA).set(false, event.getServer());
        }
    }

    // ------------------------------------------------------------------ Skyrim-like spawning

    /** Wolves spawned instead of vanilla night monsters: capped per area, and they leave when no player is near. */
    public static final String WILD_WOLF_TAG = "skycraft.wild_wolf";
    private static final int MAX_WILD_WOLVES = 4;

    /** Settlement lookups by chunk section and dimension: {@code expiry << 4 | flags}, see {@link #poiFlags}. */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> POI_CACHE = new HashMap<>();
    private static final long POI_CACHE_TICKS = 600;
    private static final int SETTLEMENT_KNOWN = 1;
    private static final int SETTLEMENT = 2;
    private static final int HOME_KNOWN = 4;
    private static final int HOME = 8;

    /**
     * Inside a settlement (a meeting point or bed within 48 blocks) or under the roof of a house (a bed within 32).
     * Main server thread only (POI lookups, cached per chunk section for 30 s); false during world generation.
     */
    public static boolean isSettlementOrHouse(ServerLevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) return false;
        if ((poiFlags(serverLevel, pos, false) & SETTLEMENT) != 0) return true;
        // Inside a roofed house / building
        if (!level.canSeeSky(pos)) {
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
            if (pos.getY() < surfaceY - 1) return (poiFlags(serverLevel, pos, true) & HOME) != 0;
        }
        return false;
    }

    private static int poiFlags(ServerLevel level, BlockPos pos, boolean needHome) {
        Long2LongOpenHashMap cache = POI_CACHE.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        long key = SectionPos.asLong(pos);
        long now = level.getGameTime();
        long entry = cache.get(key);
        long expiry = entry >>> 4;
        int flags = (int) (entry & 15);
        if (expiry < now) {
            expiry = now + POI_CACHE_TICKS;
            flags = 0;
        }
        int before = flags;
        if ((flags & SETTLEMENT_KNOWN) == 0) {
            flags |= SETTLEMENT_KNOWN;
            if (level.getPoiManager().findClosest(holder -> holder.is(PoiTypes.MEETING) || holder.is(PoiTypes.HOME),
                    pos, 48, PoiManager.Occupancy.ANY).isPresent()) flags |= SETTLEMENT;
        }
        if (needHome && (flags & HOME_KNOWN) == 0) {
            flags |= HOME_KNOWN;
            if (level.getPoiManager().findClosest(holder -> holder.is(PoiTypes.HOME), pos, 32, PoiManager.Occupancy.ANY).isPresent()) {
                flags |= HOME;
            }
        }
        if (flags != before || entry == 0) {
            if (cache.size() > 16384) cache.clear();
            cache.put(key, expiry << 4 | flags);
        }
        return flags;
    }

    /**
     * Forge also fires this event while chunks generate (structure pieces, chunk-generation spawns) on worldgen threads,
     * with a WorldGenRegion instead of the ServerLevel. True for those: no POI or entity lookups allowed.
     */
    private static boolean isWorldgen(MobSpawnEvent.FinalizeSpawn event) {
        MobSpawnType type = event.getSpawnType();
        if (type == MobSpawnType.STRUCTURE || type == MobSpawnType.CHUNK_GENERATION) return true;
        return !(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread();
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        if (event.isSpawnCancelled()) return;
        Mob mob = event.getEntity();
        ServerLevelAccessor level = event.getLevel();
        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        MobSpawnType type = event.getSpawnType();
        EntityType<?> entityType = mob.getType();

        if (isWorldgen(event)) {
            // Only cheap replacements that stay inside the region being generated.
            if (entityType == EntityType.ZOMBIE && isNorth(level, pos) && level.getRandom().nextFloat() < CreaturesConfig.DRAUGR_REPLACES_ZOMBIES.get()) {
                replace(event, ModEntities.DRAUGR.get().create(level.getLevel()));
            } else if (entityType == EntityType.SPIDER || entityType == EntityType.CAVE_SPIDER) {
                replace(event, ModEntities.FROSTBITE_SPIDER.get().create(level.getLevel()));
            }
            return;
        }
        ServerLevel serverLevel = (ServerLevel) level;

        if (type == MobSpawnType.SPAWNER) {
            onSpawnerSpawn(event, serverLevel, pos, entityType);
            return;
        }

        // 1. Suppress all hostiles spawning on their own inside settlements or houses (including wild trolls); spawn
        // eggs, commands and scripted events still work there
        boolean wild = type == MobSpawnType.NATURAL || type == MobSpawnType.PATROL || type == MobSpawnType.REINFORCEMENT;
        if (wild && mob instanceof net.minecraft.world.entity.monster.Enemy && isSettlementOrHouse(serverLevel, pos)) {
            event.setSpawnCancelled(true);
            return;
        }

        // No Iron Golems in towns, and no guards in their place: the watch is the settlement's own (society.GuardWatch)
        if (entityType == EntityType.IRON_GOLEM && isSettlementOrHouse(serverLevel, pos)) {
            event.setSpawnCancelled(true);
            return;
        }

        // 2. Vanilla natural night hostiles in the Overworld make way for Skyrim's creatures
        if (serverLevel.dimension() == Level.OVERWORLD && type == MobSpawnType.NATURAL && isVanillaDarkHostile(entityType)) {
            RandomSource random = serverLevel.getRandom();
            // pacing: calm around spawn and towns, safe roads, one hostile event per cooldown near a player
            net.minecraft.server.level.ServerPlayer near = com.skycraft.creatures.spawn.SpawnPacing.playerNear(serverLevel, pos, 128);
            if (com.skycraft.creatures.spawn.SpawnPacing.calm(serverLevel, pos)
                    || com.skycraft.creatures.spawn.SpawnPacing.onRoad(serverLevel, pos)
                    && random.nextDouble() > CreaturesConfig.ROAD_HOSTILE_FACTOR.get()
                    || near != null && !com.skycraft.creatures.spawn.SpawnPacing.hostileReady(near)) {
                event.setSpawnCancelled(true);
                return;
            }
            // draugr rise in the cold north, frostbite spiders lurk underground
            if (entityType == EntityType.ZOMBIE && isNorth(serverLevel, pos) && random.nextFloat() < CreaturesConfig.DRAUGR_REPLACES_ZOMBIES.get()
                    && fewNearby(serverLevel, ModEntities.DRAUGR.get(), pos, 48, 4)) {
                if (near != null) com.skycraft.creatures.spawn.SpawnPacing.noteHostile(near);
                replace(event, ModEntities.DRAUGR.get().create(serverLevel));
                return;
            }
            if ((entityType == EntityType.SPIDER || entityType == EntityType.CAVE_SPIDER) && !serverLevel.canSeeSky(pos)
                    && random.nextFloat() < 0.35f && fewNearby(serverLevel, ModEntities.FROSTBITE_SPIDER.get(), pos, 32, 3)) {
                if (near != null) com.skycraft.creatures.spawn.SpawnPacing.noteHostile(near);
                replace(event, ModEntities.FROSTBITE_SPIDER.get().create(serverLevel));
                return;
            }
            event.setSpawnCancelled(true);

            // Small chance to spawn Skyrim wild night hostiles (skeevers or wolves) out in the wild
            if (serverLevel.isNight() && !isSettlementOrHouse(serverLevel, pos)) {
                float roll = random.nextFloat();
                if (roll < 0.12f) {
                    if (near != null) com.skycraft.creatures.spawn.SpawnPacing.noteHostile(near);
                    SkeeverEntity skeever = ModEntities.SKEEVER.get().create(serverLevel);
                    if (skeever != null) {
                        skeever.moveTo(event.getX(), event.getY(), event.getZ(), mob.getYRot(), 0);
                        skeever.finalizeSpawn(serverLevel, event.getDifficulty(), MobSpawnType.NATURAL, null, null);
                        serverLevel.addFreshEntity(skeever);
                    }
                } else if (roll < 0.20f && fewWildWolves(serverLevel, pos)) {
                    // Skyrim's wolves (ice wolves in the cold), cleaned up by the region spawner's despawn rule
                    if (near != null) com.skycraft.creatures.spawn.SpawnPacing.noteHostile(near);
                    Mob wolf = Bestiary.WOLF.get().create(serverLevel);
                    if (wolf != null) {
                        wolf.moveTo(event.getX(), event.getY(), event.getZ(), mob.getYRot(), 0);
                        wolf.finalizeSpawn(serverLevel, event.getDifficulty(), MobSpawnType.NATURAL, null, null);
                        wolf.addTag(com.skycraft.creatures.entity.SkyCreature.REGION_SPAWN_TAG);
                        serverLevel.addFreshEntity(wolf);
                    }
                }
            }
        }
    }

    /**
     * Spawners: vanilla spawner blocks inside Skycraft's own dungeons are removed (Skyrim dungeons are clearable). Any
     * other spawner (vanilla dungeons, other mods' structures) keeps working; its zombies become draugr in the north and
     * its spiders frostbite spiders.
     */
    private static void onSpawnerSpawn(MobSpawnEvent.FinalizeSpawn event, ServerLevel level, BlockPos pos, EntityType<?> entityType) {
        BaseSpawner spawner = event.getSpawner();
        BlockEntity block = spawner == null ? null : spawner.getSpawnerBlockEntity();
        if (block instanceof SpawnerBlockEntity && block.getBlockState().is(Blocks.SPAWNER) && inSkycraftStructure(level, block.getBlockPos())) {
            event.setSpawnCancelled(true);
            level.removeBlock(block.getBlockPos(), false);
            return;
        }
        EntityType<? extends Mob> instead = null;
        if (entityType == EntityType.ZOMBIE && isNorth(level, pos) && level.getRandom().nextFloat() < CreaturesConfig.DRAUGR_REPLACES_ZOMBIES.get()) {
            instead = ModEntities.DRAUGR.get();
        } else if ((entityType == EntityType.SPIDER || entityType == EntityType.CAVE_SPIDER) && !level.canSeeSky(pos)) {
            instead = ModEntities.FROSTBITE_SPIDER.get();
        }
        if (instead == null) return;
        // The spawner only counts its own type against its limit: count the replacements ourselves.
        if (fewNearby(level, instead, pos, 9, 6)) replace(event, instead.create(level));
        else event.setSpawnCancelled(true);
    }

    /** Inside (a piece of) a structure registered under the {@code skycraft} namespace. */
    private static boolean inSkycraftStructure(ServerLevel level, BlockPos pos) {
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
            ResourceLocation id = registry.getKey(structure);
            if (id == null || !Skycraft.MODID.equals(id.getNamespace())) continue;
            if (level.structureManager().getStructureWithPieceAt(pos, structure).isValid()) return true;
        }
        return false;
    }

    /** Cancels the event's spawn and puts {@code replacement} there instead (same spawn reason). */
    private static void replace(MobSpawnEvent.FinalizeSpawn event, @Nullable Mob replacement) {
        if (replacement == null) return;
        Mob mob = event.getEntity();
        ServerLevelAccessor level = event.getLevel();
        replacement.moveTo(event.getX(), event.getY(), event.getZ(), mob.getYRot(), 0);
        replacement.finalizeSpawn(level, event.getDifficulty(), event.getSpawnType(), null, null);
        if (mob.isPersistenceRequired()) replacement.setPersistenceRequired();
        event.setSpawnCancelled(true);
        level.addFreshEntity(replacement);
    }

    private static boolean isVanillaDarkHostile(EntityType<?> entityType) {
        return entityType == EntityType.ZOMBIE
                || entityType == EntityType.SKELETON
                || entityType == EntityType.CREEPER
                || entityType == EntityType.SPIDER
                || entityType == EntityType.CAVE_SPIDER
                || entityType == EntityType.WITCH
                || entityType == EntityType.ENDERMAN
                || entityType == EntityType.ZOMBIE_VILLAGER
                || entityType == EntityType.SLIME;
    }

    /** Cold, mountain and taiga biomes (draugr country). Region-safe: only reads the biome. */
    private static boolean isNorth(ServerLevelAccessor level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(Tags.Biomes.IS_SNOWY)
                || biome.value().coldEnoughToSnow(pos);
    }

    private static boolean fewNearby(ServerLevel level, EntityType<?> type, BlockPos pos, double radius, int max) {
        return level.getEntities(type, new AABB(pos).inflate(radius), e -> true).size() < max;
    }

    private static boolean fewWildWolves(ServerLevel level, BlockPos pos) {
        return level.getEntities(Bestiary.WOLF.get(), new AABB(pos).inflate(64), w -> true).size() < MAX_WILD_WOLVES;
    }

    /**
     * Vanilla wolves spawned as wild night wolves by older versions leave when no player is within 96 blocks (unless
     * someone tamed, leashed or named one). New wild wolves are Skyrim wolves ({@link Bestiary#WOLF}).
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Wolf wolf) || wolf.tickCount % 100 != 0 || wolf.level().isClientSide
                || !wolf.getTags().contains(WILD_WOLF_TAG)) return;
        if (wolf.isTame() || wolf.isLeashed() || wolf.hasCustomName() || wolf.isPersistenceRequired()) {
            wolf.removeTag(WILD_WOLF_TAG);
            return;
        }
        if (wolf.level().getNearestPlayer(wolf, 96) == null) wolf.discard();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        POI_CACHE.clear();
    }

    // ------------------------------------------------------------------ per-player world events

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        long time = level.getGameTime() + player.getId() * 37L;
        // guards are no longer topped up around any bell or settlement: the watch is society.GuardWatch's (none in the
        // wilderness, one patrol in a minor settlement, two posted at a major one's gate); maintainGuards is retired
        if (time % CreaturesConfig.DRAGON_ATTACK_INTERVAL.get() == 0) maybeDragonAttack(level, player);
    }

    /** Keeps 2-4 guards around the village meeting point (bell) nearest to the player. */
    private static void maintainGuards(ServerLevel level, ServerPlayer player) {
        if (player.isSpectator() || level.dimension() != Level.OVERWORLD) return; // Places are dimension-blind
        Optional<BlockPos> bell = level.getPoiManager().findClosest(holder -> holder.is(PoiTypes.MEETING), player.blockPosition(), 64,
                PoiManager.Occupancy.ANY);
        BlockPos center;
        int spread = 10;
        if (bell.isPresent()) {
            center = bell.get();
        } else {
            // Tamriel settlements have no bell: replace dead guards at the nearest settlement within reach
            com.skycraft.tamriel.Place best = null;
            long bd = Long.MAX_VALUE;
            int px = player.getBlockX(), pz = player.getBlockZ();
            for (com.skycraft.tamriel.Place p : com.skycraft.tamriel.Places.overlapping(px - 64, pz - 64, px + 64, pz + 64)) {
                if (!p.type().isSettlement()) continue;
                long d = p.distSqr(px, pz);
                if (d < bd) {
                    bd = d;
                    best = p;
                }
            }
            if (best == null || !level.isLoaded(new BlockPos(best.x(), level.getSeaLevel(), best.z()))) return;
            center = new BlockPos(best.x(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, best.x(), best.z()), best.z());
            spread = Math.max(10, Math.min(30, best.radius() / 2));
        }
        if (!level.isLoaded(center)) return;
        int guards = level.getEntitiesOfClass(GuardEntity.class, new AABB(center).inflate(64), g -> g.getPost() == null).size(); // gate posts are not patrols
        if (guards >= MIN_GUARDS) return;
        RandomSource random = level.getRandom();
        int wanted = Math.min(MAX_GUARDS, MIN_GUARDS + random.nextInt(2)) - guards;
        for (int i = 0; i < wanted; i++) {
            BlockPos pos = findGroundNear(level, center, spread, random);
            if (pos == null) continue;
            GuardEntity guard = ModEntities.GUARD.get().create(level);
            if (guard == null) continue;
            guard.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, random.nextFloat() * 360f, 0);
            if (!level.noCollision(guard)) continue;
            guard.setHome(center);
            guard.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            guard.setPersistenceRequired();
            level.addFreshEntity(guard);
        }
    }

    private static BlockPos findGroundNear(ServerLevel level, BlockPos center, int radius, RandomSource random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = center.getX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.getZ() + random.nextInt(radius * 2 + 1) - radius;
            BlockPos column = new BlockPos(x, center.getY(), z);
            if (!level.isLoaded(column)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos below = pos.below();
            if (Math.abs(y - center.getY()) > 8) continue;
            if (!level.getFluidState(below).isEmpty()) continue;
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
            return pos;
        }
        return null;
    }

    /** Skyrim: out of nowhere, a roar. A dragon appears ~100 blocks away and hunts the player. */
    private static void maybeDragonAttack(ServerLevel level, ServerPlayer player) {
        if (level.dimension() != Level.OVERWORLD || player.isCreative() || player.isSpectator() || !player.isAlive()) return;
        // no new dragon while the player (or the world around them) rests or waits
        if (com.skycraft.world.RestManager.spawnsBlocked(level, player.getX(), player.getY(), player.getZ())) return;
        com.skycraft.core.PlayerData data = SkyData.get(player);
        int pLevel = data.getLevel();
        if (pLevel < CreaturesConfig.DRAGON_MIN_LEVEL.get()) return;

        // Dragons: Almost none early on. The dragon rate scales with player level and Dragonborn quest progress.
        // The first dragon is a scripted questline encounter (Dragon Rising, stage 3). Random dragons start at stage 4+.
        int questStage = com.skycraft.quest.MainQuest.stage(data);
        if (questStage < 4) return;

        long dayTime = level.getDayTime() % 24000L;
        if (dayTime > 13500L) return; // day and dusk only

        float baseChance = (float) (double) CreaturesConfig.DRAGON_ATTACK_CHANCE.get();
        float scale = Math.min(2.5f, 0.4f + (pLevel / 40.0f) + ((questStage - 3) * 0.2f));
        if (level.getRandom().nextFloat() >= baseChance * scale) return;
        trySpawnDragonAttack(level, player);
    }

    /**
     * Spawns an attacking dragon for {@code player} (also usable by other modules / commands). Returns the dragon or
     * null if the player is underground or another dragon is already near.
     */
    public static DragonEntity trySpawnDragonAttack(ServerLevel level, ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ());
        if (feet.getY() < surface - 6 && !level.canSeeSky(feet)) return null; // underground
        if (!level.getEntitiesOfClass(DragonEntity.class, player.getBoundingBox().inflate(256)).isEmpty()) return null;

        RandomSource random = level.getRandom();
        for (double dist : new double[]{100, 80, 60, 40}) {
            double angle = random.nextDouble() * Math.PI * 2;
            double x = player.getX() + Math.cos(angle) * dist;
            double z = player.getZ() + Math.sin(angle) * dist;
            BlockPos column = BlockPos.containing(x, player.getY(), z);
            if (!level.isLoaded(column)) continue;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
            double y = Math.max(player.getY() + 40, ground + 30);
            y = Math.min(y, level.getMaxBuildHeight() - 5);
            DragonEntity dragon = ModEntities.DRAGON.get().create(level);
            if (dragon == null) return null;
            dragon.moveTo(x, y, z, (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * Mth.RAD_TO_DEG) - 90f, 0);
            dragon.finalizeSpawn(level, level.getCurrentDifficultyAt(column), MobSpawnType.EVENT, null, null);
            dragon.setFlying(true);
            dragon.noPhysics = true;
            dragon.setTarget(player);
            level.addFreshEntity(dragon);
            // the roar carries: play it between the dragon and the player so they hear where it comes from
            double rx = player.getX() + (x - player.getX()) * 0.3;
            double ry = player.getY() + 10;
            double rz = player.getZ() + (z - player.getZ()) * 0.3;
            level.playSound(null, rx, ry, rz, com.skycraft.audio.AudioSounds.creature("dragon", "roar"), SoundSource.HOSTILE, 8.0f, 0.95f);
            return dragon;
        }
        return null;
    }
}
