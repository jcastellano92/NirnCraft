package com.skycraft.atmosphere.client;

import com.skycraft.Skycraft;
import com.skycraft.atmosphere.AtmosphereConfig;
import com.skycraft.atmosphere.AtmosphereSounds;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.core.SkyData;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client tick work of the atmosphere module: smooths the "cold biome" factor that gates the aurora, and plays the
 * level-up fanfare when the synced character level goes up.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AtmosphereClientEvents {
    /** Ignore level changes this soon after the player object appears (initial capability sync on login/respawn). */
    private static final int SETTLE_TICKS = 100;

    private static Player trackedPlayer;
    private static int lastLevel = -1;
    private static int ticksSinceJoin;
    private static float coldTarget;

    private AtmosphereClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            trackedPlayer = null;
            lastLevel = -1;
            return;
        }
        if (mc.isPaused()) return;

        // ---- aurora: a northern sky (Skyrim, Atmora, cold climates), brightest over cold ground
        if (player.tickCount % 20 == 0 || trackedPlayer != player) {
            BlockPos pos = player.blockPosition();
            float temp = mc.level.getBiome(pos).value().getBaseTemperature();
            boolean cold = temp <= AtmosphereConfig.AURORA_MAX_TEMPERATURE.get();
            if (AtmosphereConfig.AURORA_BY_REGION.get()) {
                Region region = Regions.at(mc.level, pos);
                boolean north = "skyrim".equals(region.province()) || "atmora".equals(region.province())
                        || WeatherProfile.of(region).aurora;
                coldTarget = north ? (cold ? 1.0F : 0.6F) : 0.0F;
            } else {
                coldTarget = cold ? 1.0F : 0.0F;
            }
        }
        SkyRenderer.coldnessPrev = SkyRenderer.coldness;
        SkyRenderer.coldness += (coldTarget - SkyRenderer.coldness) * 0.02F;

        // ---- level-up fanfare
        if (trackedPlayer != player) {
            trackedPlayer = player;
            ticksSinceJoin = 0;
            lastLevel = -1;
        }
        ticksSinceJoin++;
        int level = SkyData.get(player).getLevel();
        if (lastLevel >= 0 && level > lastLevel && ticksSinceJoin > SETTLE_TICKS && AtmosphereConfig.LEVEL_UP_STING.get()) {
            float music = mc.options.getSoundSourceVolume(SoundSource.MUSIC);
            if (music > 0f) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(AtmosphereSounds.MUSIC_LEVEL_UP.get(), 1.0f, 0.8f * music));
            }
        }
        lastLevel = level;
    }
}
