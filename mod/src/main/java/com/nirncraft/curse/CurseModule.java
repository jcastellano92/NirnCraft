package com.nirncraft.curse;

import com.nirncraft.curse.entity.DeathHoundEntity;
import com.nirncraft.curse.entity.GargoyleEntity;
import com.nirncraft.curse.entity.VampireLordEntity;
import com.nirncraft.curse.entity.WerewolfEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.bus.api.IEventBus;

/**
 * NirnCraft's own vampirism and lycanthropy (replacing the Vampirism and Werewolves mods).
 *
 * <ul>
 *     <li>{@link Curses}: the public API (quests: {@code grantBeastBlood}, {@code grantVampireLord}, {@code cure})</li>
 *     <li>{@link Vampirism}: Sanguinare Vampiris, stages, the sun, feeding on sleepers</li>
 *     <li>{@link Lycanthropy} and {@link Howls}: Beast Form, feeding on corpses, howls</li>
 *     <li>{@link VampireLord}: the winged form, its spells and powers, bites</li>
 *     <li>{@link CursePowers}, {@link CursePerks}: powers in the magic menu, the two perk trees</li>
 *     <li>{@code curse.entity}: werewolves, Vampire Lords, death hounds and gargoyles; {@code curse.client}: their
 *     models and animations, the transformed player, the HUD, the form and perk screens</li>
 * </ul>
 */
public final class CurseModule {
    private CurseModule() {}

    public static void init(IEventBus modBus) {
        CurseRegistry.init(modBus);
        modBus.addListener(CurseModule::onAttributes);
        modBus.addListener(CurseModule::onSpawnPlacements);
    }

    public static void registerPackets() {
        CursePackets.register();
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(CurseRegistry.WEREWOLF.get(), WerewolfEntity.createAttributes().build());
        event.put(CurseRegistry.VAMPIRE_LORD.get(), VampireLordEntity.createAttributes().build());
        event.put(CurseRegistry.DEATH_HOUND.get(), DeathHoundEntity.createAttributes().build());
        event.put(CurseRegistry.GARGOYLE.get(), GargoyleEntity.createAttributes().build());
    }

    private static void onSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        RegisterSpawnPlacementsEvent.Operation op = RegisterSpawnPlacementsEvent.Operation.REPLACE;
        // werewolves come by WildWerewolves, quests and Hircine; Vampire Lords and gargoyles live in lairs
        event.register(CurseRegistry.WEREWOLF.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION
                        && Monster.checkAnyLightMonsterSpawnRules(type, level, reason, pos, random), op);
        event.register(CurseRegistry.VAMPIRE_LORD.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> reason != EntitySpawnReason.NATURAL, op);
        event.register(CurseRegistry.GARGOYLE.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> reason != EntitySpawnReason.NATURAL, op);
        // death hounds run with the vampires of the night
        event.register(CurseRegistry.DEATH_HOUND.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (type, level, reason, pos, random) -> Monster.checkMonsterSpawnRules(type, level, reason, pos, random)
                        && level.getEntitiesOfClass(Monster.class, new AABB(pos).inflate(64), e -> e.getType() == type).size() < 2, op);
    }
}
