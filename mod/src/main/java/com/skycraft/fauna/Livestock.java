package com.skycraft.fauna;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Mule;
import net.minecraft.world.entity.player.Player;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Village livestock: cows, pigs, sheep, chickens, goats and horses within {@link #RADIUS} blocks of a village meeting
 * point (bell) are "owned" by the villagers. Killing them is a crime (the crime module reads {@link #isOwned(Entity)}),
 * just like Skyrim's chickens.
 *
 * <p>Animals are checked lazily: when they join a level (queued, evaluated on the next server tick so POI lookups never
 * run during chunk loading) and periodically around players (villages generated after the animal loaded, wild animals
 * wandering into town). Animals bred by a player, tamed horses and animals a player leads on a leash are the player's
 * own and never become owned. Babies of owned animals born without a player's help are owned.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Livestock {
    /** Persistent-data boolean: this animal belongs to a village. */
    public static final String OWNED = "skycraft_owned_livestock";
    /** Persistent-data boolean: this animal belongs to a player (bred, tamed or led there) and is never village-owned. */
    public static final String PLAYER_OWNED = "skycraft_player_livestock";
    /** Persistent-data long: game time of the last village check. */
    private static final String CHECKED = "skycraft_livestock_checked";
    public static final int RADIUS = 48;
    private static final int RECHECK_TICKS = 1200;
    private static final int SCAN_INTERVAL = 400;

    private static final Deque<Entity> PENDING = new ArrayDeque<>();

    private Livestock() {}

    /** True if this entity is village livestock (killing it is a crime). */
    public static boolean isOwned(Entity entity) {
        return entity != null && entity.getPersistentData().getBooleanOr(OWNED, false);
    }

    /** The farm animals this module tracks. */
    public static boolean isLivestock(Entity entity) {
        return entity instanceof Cow || entity instanceof Pig || entity instanceof Sheep || entity instanceof Chicken
                || entity instanceof Goat || entity instanceof Horse || entity instanceof Donkey || entity instanceof Mule;
    }

    private static boolean candidate(Entity entity) {
        if (!isLivestock(entity) || !entity.isAlive()) return false;
        CompoundTag data = entity.getPersistentData();
        if (data.getBooleanOr(OWNED, false) || data.getBooleanOr(PLAYER_OWNED, false)) return false;
        if (entity instanceof AbstractHorse horse && horse.isTamed()) {
            data.putBoolean(PLAYER_OWNED, true);
            return false;
        }
        if (entity instanceof Animal animal && animal.getLeashHolder() instanceof Player) {
            data.putBoolean(PLAYER_OWNED, true);
            return false;
        }
        return true;
    }

    /** True if a village meeting point (bell) is within {@link #RADIUS} blocks. */
    public static boolean nearVillage(ServerLevel level, BlockPos pos) {
        return level.getPoiManager().findClosest(type -> type.is(PoiTypes.MEETING), pos, RADIUS, PoiManager.Occupancy.ANY).isPresent();
    }

    private static void check(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !candidate(entity)) return;
        CompoundTag data = entity.getPersistentData();
        data.putLong(CHECKED, level.getGameTime());
        if (nearVillage(level, entity.blockPosition())) data.putBoolean(OWNED, true);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (isLivestock(entity) && !entity.getPersistentData().contains(CHECKED)) PENDING.add(entity);
    }

    @SubscribeEvent
    public static void onBreed(BabyEntitySpawnEvent event) {
        if (event.getChild() == null || !isLivestock(event.getChild())) return;
        CompoundTag child = event.getChild().getPersistentData();
        if (event.getCausedByPlayer() != null) {
            child.putBoolean(PLAYER_OWNED, true);
        } else if (isOwned(event.getParentA()) || isOwned(event.getParentB())) {
            child.putBoolean(OWNED, true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post)) return;
        for (int i = 0; i < 64 && !PENDING.isEmpty(); i++) {
            Entity entity = PENDING.poll();
            if (entity != null && !entity.isRemoved()) check(entity);
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % SCAN_INTERVAL != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            long now = level.getGameTime();
            for (ServerPlayer player : level.players()) {
                for (Animal animal : level.getEntitiesOfClass(Animal.class, player.getBoundingBox().inflate(64), Livestock::candidate)) {
                    if (now - animal.getPersistentData().getLongOr(CHECKED, 0L) >= RECHECK_TICKS) check(animal);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }
}
