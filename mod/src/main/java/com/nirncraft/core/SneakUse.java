package com.nirncraft.core;

import com.nirncraft.NirnCraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Sneaking never stops a hand from working a door, gate, trapdoor, lever or button. Vanilla skips the block's use
 * while the player sneaks with something in either hand (so blocks can be placed against it); NirnCraft players sneak
 * all the time, so for these blocks the use wins. Runs on both sides (client prediction matches the server) and
 * after every guard: a lock, house door, claim or other handler that cancelled or denied the click is respected.
 * Chests, bodies and stations are handled by their own modules (crime/Locks, CraftingEvents).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SneakUse {
    private SneakUse() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isSecondaryUseActive() || player.isSpectator()) return;
        if (event.getUseBlock() == net.minecraft.util.TriState.FALSE) return;
        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof LeverBlock || block instanceof ButtonBlock) {
            event.setUseBlock(net.minecraft.util.TriState.TRUE);
        }
    }
}
