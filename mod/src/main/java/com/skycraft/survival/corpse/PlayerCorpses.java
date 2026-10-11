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

/**
 * Puts a dead player's drops into a {@link PlayerCorpseEntity} where they fell (after {@code combat/DeathHandler}
 * took out what they keep). A body that would lie in lava or the void is placed on the nearest safe ground instead:
 * above the death point, or where the player last stood safely. The "Your Corpse" map marker follows the body.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlayerCorpses {
    /** Where each player last stood on solid, lava-free ground (refreshed every second). */
    private static final Map<UUID, BlockPos> LAST_SAFE = new HashMap<>();

    private PlayerCorpses() {}

    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 20 != 0 || !player.onGround() || player.isInLava() || player.isSpectator()) return;
        LAST_SAFE.put(player.getUUID(), player.blockPosition());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SAFE.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        if (!SurvivalConfig.PLAYER_CORPSES.get() || event.getDrops().isEmpty()) return;
        ServerLevel level = player.serverLevel();
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        PlayerCorpseEntity corpse = SurvivalRegistry.PLAYER_CORPSE.get().create(level);
        if (corpse == null) return;

        BlockPos lastSafe = LAST_SAFE.getOrDefault(player.getUUID(), level.getSharedSpawnPos());
        BlockPos at = safeSpot(level, player.blockPosition(), lastSafe);
        corpse.setOwner(player, at.equals(player.blockPosition()) ? lastSafe : at);

        List<ItemEntity> stored = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            net.minecraft.world.item.ItemStack rest = corpse.add(drop.getItem().copy());
            if (rest.isEmpty()) stored.add(drop);
            else drop.setItem(rest);
        }
        if (stored.isEmpty()) return;
        event.getDrops().removeAll(stored);
        float yaw = player.yBodyRot;
        corpse.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0);
        if (!level.noCollision(corpse)) corpse.setCentered(false);
        if (lava(level, at.below())) corpse.setNoGravity(true); // hovers over the lava it was pulled out of
        level.addFreshEntity(corpse);
        com.skycraft.combat.DeathHandler.moveCorpseMarker(player, at);
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
