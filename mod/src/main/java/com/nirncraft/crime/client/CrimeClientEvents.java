package com.nirncraft.crime.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.crime.Bounty;
import com.nirncraft.crime.CrimePackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Forge-bus client events: asks the server about the container under the crosshair, "Stolen" tooltips. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class CrimeClientEvents {
    private static BlockPos lastQuery;
    private static int sinceQuery;

    private CrimeClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null) return;
        sinceQuery++;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = com.nirncraft.crime.Locks.normalizePos(mc.level, bhr.getBlockPos());
        var block = mc.level.getBlockState(pos).getBlock();
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof Container) && !(block instanceof net.minecraft.world.level.block.DoorBlock) && !(block instanceof net.minecraft.world.level.block.TrapDoorBlock)) return;
        if (!pos.equals(lastQuery) || sinceQuery > 20) {
            lastQuery = pos.immutable();
            sinceQuery = 0;
            SkyNetwork.sendToServer(new CrimePackets.QueryContainer(lastQuery));
        }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        BlockPos cpos = event.getPos();
        var cstate = mc.level.getBlockState(cpos);
        if (cstate.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || cstate.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) {
            var info = CrimeClientHandlers.containerInfo;
            if (info != null && (info.pos().equals(cpos) || info.pos().equals(cpos.below()) || info.pos().equals(cpos.above()))
                    && info.lock() != com.nirncraft.crime.Locks.NOT_LOCKED && !info.unlocked()) {
                // refuse on the client too, so the door never animates open before the server's lockpick check
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
                if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                    mc.level.playLocalSound(cpos, net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 1.3f, false);
                    com.nirncraft.client.hud.HudNotifications.add(new com.nirncraft.network.CorePackets.Notify(
                            com.nirncraft.network.NotifyKind.MESSAGE,
                            Component.translatable("hud.nirncraft.crime.locked_msg").withStyle(ChatFormatting.RED),
                            Component.empty(), 0, 0f));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (Bounty.isStolen(event.getItemStack())) {
            event.getToolTip().add(Component.translatable("tooltip.nirncraft.stolen").withStyle(ChatFormatting.RED));
        }
    }
}
