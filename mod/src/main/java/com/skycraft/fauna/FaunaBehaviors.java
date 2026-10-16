package com.skycraft.fauna;

import com.skycraft.Skycraft;
import com.skycraft.fauna.entity.BearEntity;
import com.skycraft.fauna.entity.HorkerEntity;
import com.skycraft.fauna.entity.MammothEntity;
import com.skycraft.fauna.entity.MudcrabEntity;
import com.skycraft.fauna.entity.SabreCatEntity;
import com.skycraft.fauna.entity.SlaughterfishEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Skyrim fauna behavior & ambient population:
 * - Wild non-predator animals flee from players (unless tamed, farm livestock, or player-owned).
 * - Town pets: dogs (wolves) and cats spawn in settlements near players if none are present.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class FaunaBehaviors {
    private static final String FLEE_FLAG = "skycraft_flee_goal_added";
    private static final String PET_SPAWN_CHECK = "skycraft_pet_spawn_checked";
    private static int tickCounter = 0;

    private FaunaBehaviors() {}

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof Animal animal) {
            applyWildFleeGoal(animal);
        }
    }

    /**
     * Wild animals bolt from players unless they are tamed, farm livestock, or predators.
     */
    public static void applyWildFleeGoal(Animal animal) {
        if (animal.getPersistentData().getBoolean(FLEE_FLAG)) return;

        // Predators, neutral/defensive fauna, SkyBeast (which manages its own goals by Temper), tamed pets, or farm animals don't flee
        if (animal instanceof TamableAnimal tamable && tamable.isTame()) return;
        if (Livestock.isOwned(animal) || animal.getPersistentData().getBoolean(Livestock.PLAYER_OWNED)) return;
        if (animal instanceof BearEntity
                || animal instanceof SabreCatEntity
                || animal instanceof HorkerEntity
                || animal instanceof MudcrabEntity
                || animal instanceof MammothEntity
                || animal instanceof com.skycraft.creatures.entity.SkyBeast
                || animal instanceof net.minecraft.world.entity.NeutralMob) {
            return;
        }
        if (!animal.targetSelector.getAvailableGoals().isEmpty()
                || animal.goalSelector.getAvailableGoals().stream()
                        .anyMatch(w -> w.getGoal() instanceof net.minecraft.world.entity.ai.goal.MeleeAttackGoal)) {
            return;
        }

        // Only add to non-predator wild animals / passive animals
        animal.goalSelector.addGoal(2, new AvoidEntityGoal<>(
                animal,
                Player.class,
                (p) -> !p.isShiftKeyDown() || animal.distanceToSqr(p) < 16.0,
                16.0f,
                1.4,
                1.8,
                (p) -> !(p instanceof Player pl && (pl.isCreative() || pl.isSpectator()))
        ));
        animal.getPersistentData().putBoolean(FLEE_FLAG, true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter % 600 != 0) return; // check every 30 seconds

        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                checkTownPets(level, player);
            }
        }
    }

    /**
     * Ensures dogs and cats exist in towns/settlements.
     */
    private static void checkTownPets(ServerLevel level, ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        if (!Livestock.nearVillage(level, pos)) return;

        long now = level.getGameTime();
        if (now - player.getPersistentData().getLong(PET_SPAWN_CHECK) < 2400) return;
        player.getPersistentData().putLong(PET_SPAWN_CHECK, now);

        // Check existing pets in the settlement area
        List<Cat> cats = level.getEntitiesOfClass(Cat.class, player.getBoundingBox().inflate(32));
        List<Wolf> dogs = level.getEntitiesOfClass(Wolf.class, player.getBoundingBox().inflate(32));

        if (cats.isEmpty() && level.random.nextFloat() < 0.6f) {
            Cat cat = EntityType.CAT.create(level);
            if (cat != null) {
                BlockPos p = pos.offset(level.random.nextInt(10) - 5, 0, level.random.nextInt(10) - 5);
                cat.moveTo(p.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()), p.getZ() + 0.5, 0, 0);
                cat.getPersistentData().putBoolean(Livestock.OWNED, true);
                level.addFreshEntity(cat);
            }
        }

        if (dogs.isEmpty() && level.random.nextFloat() < 0.5f) {
            Wolf dog = EntityType.WOLF.create(level);
            if (dog != null) {
                BlockPos p = pos.offset(level.random.nextInt(10) - 5, 0, level.random.nextInt(10) - 5);
                dog.moveTo(p.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()), p.getZ() + 0.5, 0, 0);
                dog.getPersistentData().putBoolean(Livestock.OWNED, true);
                level.addFreshEntity(dog);
            }
        }
    }
}

