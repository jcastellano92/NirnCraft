package com.skycraft.skills.client;

import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Acrobatics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Acrobatics' Double Jump: press jump again in mid-air (once per jump). The server validates and pays stamina. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AcrobaticsClient {
    private static boolean jumpWasDown;
    private static boolean used;

    private AcrobaticsClient() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gui.screen() != null) {
            jumpWasDown = false;
            return;
        }
        boolean down = mc.options.keyJump.isDown();
        if (p.onGround() || p.isInWater() || p.onClimbable()) used = false;
        if (down && !jumpWasDown && !used && !p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isPassenger()
                && !p.isFallFlying() && p.getDeltaMovement().y < 0.25 && Perks.has(p, "acrobatics.double_jump")
                && (p.isCreative() || SkyData.get(p).getStamina() >= Acrobatics.DOUBLE_JUMP_STAMINA)) {
            used = true;
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, 0.42 * Acrobatics.jumpMultiplier(SkyData.get(p)), v.z);
            p.fallDistance = 0;
            SkyNetwork.sendToServer(new Acrobatics.DoubleJump());
        }
        jumpWasDown = down;
    }
}
