package com.skycraft.client;

import com.skycraft.Skycraft;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Wall-climbing for the races that climb (Tsaesci, Tang Mo, Imga): against a wall, holding jump climbs it like a
 * ladder and sneaking clings to it. Player movement is the client's, so this runs here; the server resets fall
 * distance while a climber hangs on a wall ({@code vitals.RacePassives}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class RaceMovement {
    private RaceMovement() {}

    @SubscribeEvent
    public static void onPlayerTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!player.horizontalCollision || player.getAbilities().flying || player.isPassenger() || player.isInWater()) return;
        Race race = SkyData.get(player).getRace();
        if (race == null || !race.climbs()) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 v = player.getDeltaMovement();
        if (mc.options.keyJump.isDown()) {
            player.setDeltaMovement(v.x, 0.2, v.z);
            player.resetFallDistance();
        } else if (player.isShiftKeyDown() && v.y < 0) {
            player.setDeltaMovement(v.x, 0.0, v.z);
            player.resetFallDistance();
        }
    }
}
