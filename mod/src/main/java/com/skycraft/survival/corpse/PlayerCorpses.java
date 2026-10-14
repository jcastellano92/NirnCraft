package com.skycraft.survival.corpse;

import com.skycraft.Skycraft;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts a dead player's drops into a {@link PlayerCorpseEntity} where they fell (after {@code combat/DeathHandler}
 * took out what they keep). A body that would lie in lava or the void is placed on the nearest safe ground instead:
 * above the death point, or where the player last stood safely. The "Your Corpse" map marker follows the body.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlayerCorpses {
    private record SafeKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, UUID id) {}

    /** Where each player last stood on solid, lava-free ground, per dimension (refreshed every second). */
    private static final Map<SafeKey, BlockPos> LAST_SAFE = new HashMap<>();

    private PlayerCorpses() {}

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0 || !player.onGround() || player.isInLava() || player.isSpectator()) return;
        LAST_SAFE.put(new SafeKey(player.level().dimension(), player.getUUID()), player.blockPosition());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_SAFE.keySet().removeIf(k -> k.id().equals(id));
    }

    /**
     * What the dying player's pack drops, handed over by {@code combat/DeathHandler} (full int counts, any size), until
     * {@link #onDrops} of the same death puts it into the body. Never visible to other mods as item entities, which
     * would have to save a count above 127 as a byte.
     */
    private static final Map<UUID, List<net.minecraft.world.item.ItemStack>> PACK_CARGO = new ConcurrentHashMap<>();

    /**
     * {@code combat/DeathHandler}: the pack part of a death's drops. With corpses on it goes into the body next to the
     * slots' drops (one body, nothing on the ground for lack of room); with corpses off it falls as item entities the
     * way it always did.
     */
    public static void stashPackDrops(ServerPlayer player, List<net.minecraft.world.item.ItemStack> cargo, LivingDropsEvent event) {
        if (cargo.isEmpty()) return;
        if (SurvivalConfig.PLAYER_CORPSES.get()) {
            PACK_CARGO.put(player.getUUID(), cargo);
        } else {
            spillAsEntities(player, cargo, event.getDrops());
        }
    }

    /**
     * For handlers that take a death's drops for themselves (the jail's evidence chest): the pack part handed over by
     * {@link #stashPackDrops}, removed from the stash. Empty when there is none.
     */
    public static List<net.minecraft.world.item.ItemStack> takePackCargo(ServerPlayer player) {
        List<net.minecraft.world.item.ItemStack> cargo = PACK_CARGO.remove(player.getUUID());
        return cargo == null ? List.of() : cargo;
    }

    private static void spillAsEntities(ServerPlayer player, List<net.minecraft.world.item.ItemStack> cargo, java.util.Collection<ItemEntity> out) {
        for (net.minecraft.world.item.ItemStack all : cargo) {
            for (net.minecraft.world.item.ItemStack part : com.skycraft.inventory.BulkStore.splitToStacks(all.copy())) {
                out.add(new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), part));
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        List<net.minecraft.world.item.ItemStack> cargo = PACK_CARGO.remove(player.getUUID());
        if (event.isCanceled()) {
            // another mod took the drops (a grave): the pack part we took out stays with the player and comes back with
            // the respawn copy of their data, so nothing is lost or duplicated
            if (cargo != null) for (net.minecraft.world.item.ItemStack s : cargo) com.skycraft.inventory.Pack.add(player, s);
            return;
        }
        boolean hasCargo = cargo != null && !cargo.isEmpty();
        if (!SurvivalConfig.PLAYER_CORPSES.get() || (event.getDrops().isEmpty() && !hasCargo)) {
            if (hasCargo) spillAsEntities(player, cargo, event.getDrops());
            return;
        }
        ServerLevel level = player.serverLevel();
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            if (hasCargo) for (net.minecraft.world.item.ItemStack s : cargo) com.skycraft.inventory.Pack.add(player, s);
            return;
        }
        PlayerCorpseEntity corpse = SurvivalRegistry.PLAYER_CORPSE.get().create(level);
        if (corpse == null) {
            if (hasCargo) spillAsEntities(player, cargo, event.getDrops());
            return;
        }

        BlockPos lastSafe = LAST_SAFE.getOrDefault(new SafeKey(level.dimension(), player.getUUID()), level.getSharedSpawnPos());
        BlockPos at = safeSpot(level, player.blockPosition(), lastSafe);
        corpse.setOwner(player, at.equals(player.blockPosition()) ? lastSafe : at);

        List<ItemEntity> stored = new ArrayList<>();
        List<ItemEntity> partial = new ArrayList<>();
        List<net.minecraft.world.item.ItemStack> partialRest = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            net.minecraft.world.item.ItemStack rest = corpse.add(drop.getItem().copy());
            if (rest.isEmpty()) {
                stored.add(drop);
            } else {
                partial.add(drop);
                partialRest.add(rest);
            }
        }
        List<net.minecraft.world.item.ItemStack> refused = new ArrayList<>();
        if (hasCargo) {
            for (net.minecraft.world.item.ItemStack all : cargo) {
                net.minecraft.world.item.ItemStack rest = corpse.addBulk(all.copy());
                if (!rest.isEmpty()) refused.add(rest);
            }
        }
        if (stored.isEmpty() && !corpse.hasItems()) return;
        float yaw = player.yBodyRot;
        corpse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
        if (!level.noCollision(corpse)) corpse.setCentered(false);
        if (lava(level, at.below())) corpse.setNoGravity(true); // hovers over the lava it was pulled out of
        if (!level.addFreshEntity(corpse)) {
            // another mod vetoed the body: leave every item where it was (nothing was removed from the drops yet)
            if (hasCargo) spillAsEntities(player, cargo, event.getDrops());
            return;
        }
        event.getDrops().removeAll(stored);
        for (int i = 0; i < partial.size(); i++) partial.get(i).setItem(partialRest.get(i)); // what the overflow's caps refused
        // only the overflow's engineering caps can refuse something: it falls at the body, it is never deleted
        if (!refused.isEmpty()) spillAsEntities(player, refused, event.getDrops());
        com.skycraft.combat.DeathHandler.moveCorpseMarker(player, at);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        PACK_CARGO.remove(event.getEntity().getUUID());
    }

    /**
     * {@code pos} if a body can lie there; out of lava, the spot above it; below the world, the surface above the
     * death point; otherwise {@code fallback}.
     */
    public static BlockPos safeSpot(ServerLevel level, BlockPos pos, BlockPos fallback) {
        if (pos.getY() < level.getMinBuildHeight() + 1) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
            if (top > level.getMinBuildHeight() + 1 && level.isLoaded(pos)) {
                BlockPos up = new BlockPos(pos.getX(), top, pos.getZ());
                if (!lava(level, up) && !lava(level, up.below())) return up;
            }
            return fallback;
        }
        if (lava(level, pos) || lava(level, pos.below())) {
            BlockPos.MutableBlockPos p = pos.mutable();
            for (int i = 0; i < 64 && p.getY() < level.getMaxBuildHeight() - 2; i++) {
                p.move(0, 1, 0);
                if (!lava(level, p) && level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                        && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                    return p.immutable();
                }
            }
            return fallback;
        }
        return pos;
    }

    static boolean lava(ServerLevel level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.LAVA) || level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FIRE);
    }
}
