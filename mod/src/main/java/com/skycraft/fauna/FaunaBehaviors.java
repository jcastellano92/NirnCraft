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
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * Skyrim fauna behavior & ambient population:
 * - Wild non-predator animals flee from players (unless tamed, farm livestock, or player-owned).
 * - Town pets: dogs (wolves) and cats spawn in settlements near players if none are present.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
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
        // The old persistent marker survived reload although goals do not. Migrate it away.
        animal.getPersistentData().remove(FLEE_FLAG);
        if (animal.goalSelector.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof WildFleeGoal)) return;

        // Predators, neutral/defensive fauna, SkyBeast (which manages its own goals by Temper), tamed pets, or farm animals don't flee
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
        animal.goalSelector.addGoal(2, new WildFleeGoal(animal));
    }

    private static boolean mayFlee(Animal animal) {
        return !(animal instanceof TamableAnimal tamable && tamable.isTame())
                && !(animal instanceof AbstractHorse horse && horse.isTamed())
                && !Livestock.isOwned(animal) && !animal.getPersistentData().getBooleanOr(Livestock.PLAYER_OWNED, false);
    }

    /** Runtime identity prevents duplicate installation; ownership is evaluated on every start and continuation. */
    private static final class WildFleeGoal extends AvoidEntityGoal<Player> {
        private final Animal animal;

        WildFleeGoal(Animal animal) {
            super(animal, Player.class,
                    p -> !p.isShiftKeyDown() || animal.distanceToSqr(p) < 16.0,
                    16.0f, 1.4, 1.8,
                    p -> !(p instanceof Player pl && (pl.isCreative() || pl.isSpectator())));
            this.animal = animal;
        }

        @Override public boolean canUse() {
            return mayFlee(animal) && super.canUse();
        }

        @Override public boolean canContinueToUse() {
            return mayFlee(animal) && super.canContinueToUse();
        }
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        if (++tickCounter % 600 != 0) return; // check every 30 seconds

        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
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
        if (now - player.getPersistentData().getLongOr(PET_SPAWN_CHECK, 0L) < 2400) return;
        player.getPersistentData().putLong(PET_SPAWN_CHECK, now);

        // Check existing pets in the settlement area
        List<Cat> cats = level.getEntitiesOfClass(Cat.class, player.getBoundingBox().inflate(32));
        List<Wolf> dogs = level.getEntitiesOfClass(Wolf.class, player.getBoundingBox().inflate(32));

        if (cats.isEmpty() && level.getRandom().nextFloat() < 0.6f) {
            Cat cat = net.minecraft.world.entity.EntityTypes.CAT.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (cat != null) {
                BlockPos p = pos.offset(level.getRandom().nextInt(10) - 5, 0, level.getRandom().nextInt(10) - 5);
                cat.snapTo(p.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()), p.getZ() + 0.5, 0, 0);
                cat.getPersistentData().putBoolean(Livestock.OWNED, true);
                level.addFreshEntity(cat);
            }
        }

        if (dogs.isEmpty() && level.getRandom().nextFloat() < 0.5f) {
            Wolf dog = net.minecraft.world.entity.EntityTypes.WOLF.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (dog != null) {
                BlockPos p = pos.offset(level.getRandom().nextInt(10) - 5, 0, level.getRandom().nextInt(10) - 5);
                dog.snapTo(p.getX() + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()), p.getZ() + 0.5, 0, 0);
                dog.getPersistentData().putBoolean(Livestock.OWNED, true);
                level.addFreshEntity(dog);
            }
        }
    }
}

