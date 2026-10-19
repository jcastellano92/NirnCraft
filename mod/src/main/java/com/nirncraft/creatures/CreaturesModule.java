package com.nirncraft.creatures;

import com.nirncraft.creatures.entity.BanditChiefEntity;
import com.nirncraft.creatures.entity.BanditEntity;
import com.nirncraft.creatures.entity.DragonEntity;
import com.nirncraft.creatures.entity.DraugrDeathlordEntity;
import com.nirncraft.creatures.entity.DraugrEntity;
import com.nirncraft.creatures.entity.FalmerEntity;
import com.nirncraft.creatures.entity.ForswornEntity;
import com.nirncraft.creatures.entity.FrostbiteSpiderEntity;
import com.nirncraft.creatures.entity.GiantEntity;
import com.nirncraft.creatures.entity.GuardEntity;
import com.nirncraft.creatures.entity.HagravenEntity;
import com.nirncraft.creatures.entity.IceWraithEntity;
import com.nirncraft.creatures.entity.NecromancerEntity;
import com.nirncraft.creatures.entity.SkeeverEntity;
import com.nirncraft.creatures.entity.SprigganEntity;
import com.nirncraft.creatures.entity.TrollEntity;
import com.nirncraft.creatures.entity.VampireEntity;
import com.nirncraft.creatures.entity.WispmotherEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;

/**
 * Draugr, bandits, giants, trolls, skeevers, dragons, hold guards, lootable corpses, leveled enemies and
 * Skyrim-like spawning.
 *
 * <ul>
 *     <li>{@link ModEntities}: entity types (ids are a cross-module contract), spawn eggs, the bandit camp feature</li>
 *     <li>{@link Leveling}: leveled enemies and rank names</li>
 *     <li>{@link CorpseEvents}: lootable bodies</li>
 *     <li>{@link CreatureSpawns}: draugr instead of zombies, dragon attacks, village guards, no phantoms</li>
 *     <li>{@code creatures.client}: models and renderers</li>
 * </ul>
 */
public final class CreaturesModule {
    private CreaturesModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        com.nirncraft.NirnCraft.config(ModConfig.Type.LOCAL, CreaturesConfig.SPEC, "nirncraft-creatures.toml");
        ModEntities.init(modBus);
        Bestiary.init(modBus);
        com.nirncraft.creatures.dragons.NamedDragons.init();
        com.nirncraft.creatures.travel.StriderPorts.init();
        CreatureItems.init(modBus);
        modBus.addListener(CreaturesModule::onAttributes);
        modBus.addListener(CreaturesModule::onSpawnPlacements);
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        // Creatures sync everything through SynchedEntityData; no packets needed.
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.BANDIT.get(), BanditEntity.createAttributes().build());
        event.put(ModEntities.BANDIT_CHIEF.get(), BanditChiefEntity.createAttributes().build());
        event.put(ModEntities.DRAUGR.get(), DraugrEntity.createAttributes().build());
        event.put(ModEntities.DRAUGR_DEATHLORD.get(), DraugrDeathlordEntity.createAttributes().build());
        event.put(ModEntities.SKEEVER.get(), SkeeverEntity.createAttributes().build());
        event.put(ModEntities.TROLL.get(), TrollEntity.createAttributes().build());
        event.put(ModEntities.GIANT.get(), GiantEntity.createAttributes().build());
        event.put(ModEntities.DRAGON.get(), DragonEntity.createAttributes().build());
        event.put(ModEntities.GUARD.get(), GuardEntity.createAttributes().build());
        event.put(ModEntities.FORSWORN.get(), ForswornEntity.createAttributes().build());
        event.put(ModEntities.NECROMANCER.get(), NecromancerEntity.createAttributes().build());
        event.put(ModEntities.VAMPIRE.get(), VampireEntity.createAttributes().build());
        event.put(ModEntities.FALMER.get(), FalmerEntity.createAttributes().build());
        event.put(ModEntities.FROSTBITE_SPIDER.get(), FrostbiteSpiderEntity.createAttributes().build());
        event.put(ModEntities.ICE_WRAITH.get(), IceWraithEntity.createAttributes().build());
        event.put(ModEntities.SPRIGGAN.get(), SprigganEntity.createAttributes().build());
        event.put(ModEntities.HAGRAVEN.get(), HagravenEntity.createAttributes().build());
        event.put(ModEntities.WISPMOTHER.get(), WispmotherEntity.createAttributes().build());
    }

    private static void onSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        RegisterSpawnPlacementsEvent.Operation op = RegisterSpawnPlacementsEvent.Operation.REPLACE;
        // bandits and giants roam in daylight, on the surface; few at a time
        event.register(ModEntities.BANDIT.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> surfaceSpawn(type, level, reason, pos, random) && fewNearby(type, level, pos, 64, 4), op);
        event.register(ModEntities.GIANT.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> surfaceSpawn(type, level, reason, pos, random) && fewNearby(type, level, pos, 128, 1), op);
        // trolls hunt at any hour
        event.register(ModEntities.TROLL.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkAnyLightMonsterSpawnRules(type, level, reason, pos, random)
                        && fewNearby(type, level, pos, 96, 1), op);
        // draugr and skeevers need darkness (night, caves, tombs)
        event.register(ModEntities.DRAUGR.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Monster::checkMonsterSpawnRules, op);
        event.register(ModEntities.SKEEVER.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Monster::checkMonsterSpawnRules, op);
        event.register(ModEntities.DRAUGR_DEATHLORD.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && fewNearby(type, level, pos, 64, 1), op);
        // people of the wilds: Forsworn camps in the Reach, bandit chiefs, hagravens, spriggans guarding the forests
        event.register(ModEntities.FORSWORN.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> surfaceSpawn(type, level, reason, pos, random) && fewNearby(type, level, pos, 64, 4), op);
        event.register(ModEntities.BANDIT_CHIEF.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> surfaceSpawn(type, level, reason, pos, random) && fewNearby(type, level, pos, 96, 1), op);
        event.register(ModEntities.HAGRAVEN.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> surfaceSpawn(type, level, reason, pos, random) && fewNearby(type, level, pos, 96, 1), op);
        event.register(ModEntities.SPRIGGAN.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> surfaceSpawn(type, level, reason, pos, random) && fewNearby(type, level, pos, 64, 2), op);
        // ice wraiths drift over snowfields at any hour; wispmothers walk the woods at night
        event.register(ModEntities.ICE_WRAITH.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkAnyLightMonsterSpawnRules(type, level, reason, pos, random)
                        && (reason != EntitySpawnReason.NATURAL || level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel()))
                        && fewNearby(type, level, pos, 64, 2), op);
        event.register(ModEntities.WISPMOTHER.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && (reason != EntitySpawnReason.NATURAL || level.canSeeSky(pos)) && fewNearby(type, level, pos, 128, 1), op);
        // creatures of the dark: caves, tombs, ruins and night
        event.register(ModEntities.NECROMANCER.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && fewNearby(type, level, pos, 64, 2), op);
        event.register(ModEntities.VAMPIRE.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && fewNearby(type, level, pos, 64, 2), op);
        event.register(ModEntities.FALMER.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && (reason != EntitySpawnReason.NATURAL || !level.canSeeSky(pos)) && fewNearby(type, level, pos, 48, 4), op);
        event.register(ModEntities.FROSTBITE_SPIDER.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && fewNearby(type, level, pos, 48, 3), op);
        // dragons only come by attack events, quests and lairs, never by natural spawning
        event.register(ModEntities.DRAGON.get(), net.minecraft.world.entity.SpawnPlacementTypes.NO_RESTRICTIONS, Heightmap.Types.MOTION_BLOCKING,
                (type, level, reason, pos, random) -> reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION, op);
    }

    /**
     * Any light level, but only under the open sky (no bandits in caves). Daylight spawns are throttled: vanilla
     * monsters can't spawn in daylight, so without this the whole monster cap would fill with bandits and giants.
     */
    private static boolean surfaceSpawn(EntityType<? extends Monster> type, ServerLevelAccessor level, EntitySpawnReason reason,
                                        BlockPos pos, RandomSource random) {
        if (!Monster.checkAnyLightMonsterSpawnRules(type, level, reason, pos, random)) return false;
        if (reason != EntitySpawnReason.NATURAL) return true;
        if (!level.canSeeSky(pos)) return false;
        return level.getLevel().isDarkOutside() || random.nextInt(6) == 0;
    }

    /** True when fewer than {@code max} creatures of this type are within {@code radius} blocks. */
    private static boolean fewNearby(EntityType<?> type, ServerLevelAccessor level, BlockPos pos, double radius, int max) {
        return level.getEntitiesOfClass(Monster.class, new AABB(pos).inflate(radius), e -> e.getType() == type).size() < max;
    }
}
