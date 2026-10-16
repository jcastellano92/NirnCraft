package com.skycraft.crime.client;

import com.skycraft.Skycraft;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.CrimePackets;
import com.skycraft.network.SkyNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge-bus client events: asks the server about the container under the crosshair, "Stolen" tooltips. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class CrimeClientEvents {
    private static BlockPos lastQuery;
    private static int sinceQuery;

    private CrimeClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        sinceQuery++;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = com.skycraft.crime.Locks.normalizePos(mc.level, bhr.getBlockPos());
        var block = mc.level.getBlockState(pos).getBlock();
        BlockEntity be = mc.level.getBlockEntity(pos);
        if (!(be instanceof Container) && !(block instanceof net.minecraft.world.level.block.DoorBlock) && !(block instanceof net.minecraft.world.level.block.TrapDoorBlock)) return;
        if (!pos.equals(lastQuery) || sinceQuery > 20) {
            lastQuery = pos.immutable();
            sinceQuery = 0;
            SkyNetwork.sendToServer(new CrimePackets.QueryContainer(lastQuery));
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        BlockPos cpos = event.getPos();
        var cstate = mc.level.getBlockState(cpos);
        if (cstate.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || cstate.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) {
            var info = CrimeClientHandlers.containerInfo;
            if (info != null && (info.pos().equals(cpos) || info.pos().equals(cpos.below()) || info.pos().equals(cpos.above()))
                    && info.lock() != com.skycraft.crime.Locks.NOT_LOCKED && !info.unlocked()) {
                // refuse on the client too, so the door never animates open before the server's lockpick check
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
                if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                    mc.level.playLocalSound(cpos, net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 0.5f, 1.3f, false);
                    com.skycraft.client.hud.HudNotifications.add(new com.skycraft.network.CorePackets.Notify(
                            com.skycraft.network.NotifyKind.MESSAGE,
                            Component.translatable("hud.skycraft.crime.locked_msg").withStyle(ChatFormatting.RED),
                            Component.empty(), 0, 0f));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (Bounty.isStolen(event.getItemStack())) {
            event.getToolTip().add(Component.translatable("tooltip.skycraft.stolen").withStyle(ChatFormatting.RED));
        }
    }
}
