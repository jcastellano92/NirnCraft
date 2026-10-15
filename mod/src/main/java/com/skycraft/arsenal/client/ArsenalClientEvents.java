package com.skycraft.arsenal.client;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.ArsenalPackets;
import com.skycraft.arsenal.item.ArtifactItem;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge-bus client hooks: Bloodskal Blade swings into thin air still release the energy blade. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ArsenalClientEvents {
    private ArsenalClientEvents() {}

    @SubscribeEvent
    public static void onClickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !ArtifactItem.is(player.getMainHandItem(), "bloodskal_blade")) return;
        // creature hits are detected server-side (AttackEntityEvent); block hits start mining instead
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return;
        if (player.getAttackStrengthScale(0.5f) < 0.95f) return;
        SkyNetwork.sendToServer(new ArsenalPackets.BladeSwing());
    }

    @SubscribeEvent
    public static void onTooltip(net.minecraftforge.event.entity.player.ItemTooltipEvent event) {
        com.skycraft.loot.affix.ItemAffixes.appendTooltip(event.getItemStack(), event.getToolTip());
    }
}
