package com.nirncraft.atmosphere.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.atmosphere.AtmosphereConfig;
import com.nirncraft.atmosphere.AtmosphereMath;
import com.nirncraft.atmosphere.AtmosphereSounds;
import com.nirncraft.core.SkyData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Client tick work of the atmosphere module: smoothly updates the continuous world-Z latitude aurora factor, and
 * plays the level-up fanfare when the synced character level goes up.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class AtmosphereClientEvents {
    /** Ignore level changes this soon after the player object appears (initial capability sync on login/respawn). */
    private static final int SETTLE_TICKS = 100;

    private static Player trackedPlayer;
    private static int lastLevel = -1;
    private static int ticksSinceJoin;
    private static float coldTarget;
    private static net.minecraft.client.multiplayer.ClientLevel mediaLevel;

    private AtmosphereClientEvents() {}

    @SubscribeEvent
    public static void onRenderTick(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            try (var uploadState = TextureUploadState.preserve()) {
                FogRadianceTexture.renderClock(event.getPartialTick().getGameTimeDeltaPartialTick(false));
                if (mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                    AtmosphereBudget.beginFrame();
                    SmokeSourceTexture.renderClock(mc.level, event.getPartialTick().getGameTimeDeltaPartialTick(false));
                    LightningFieldTexture.renderUpdate(event.getPartialTick().getGameTimeDeltaPartialTick(false));
                }
            }
        }
    }

    @SubscribeEvent
    public static void afterRenderTick(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        AtmosphereBudget.endFrame();
    }

    @SubscribeEvent
    public static void closeBudgetOnLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        AtmosphereBudget.close();
    }

    @SubscribeEvent
    public static void closeBudgetOnStopping(net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent event) {
        AtmosphereBudget.close();
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.client.event.ClientTickEvent.Post)) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if(mediaLevel!=mc.level) { mediaLevel=mc.level;FogRadianceTexture.clearLocalState(); }
        if (player == null || mc.level == null) {
            trackedPlayer = null;
            lastLevel = -1;
            return;
        }
        if (mc.isPaused()) return;

        var media=com.nirncraft.atmosphere.DimensionMediaPolicy.of(mc.level.dimension().identifier().toString());
        try (var uploadState = TextureUploadState.preserve()) {
        if(media.localMedia()) {
            if(LocalOcclusionTexture.enabled() && ShaderCompat.shadersActive()) LocalOcclusionTexture.tick(mc.level,player.getEyePosition());
            FogRadianceTexture.tick(mc.level,player.getEyePosition());
        }

        // ---- shared atmospheric emission and nearby baked block light
        if (mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            NightSkyState.tick(mc.level, player.getEyePosition());
            SmokeSourceTexture.tick(mc.level, player.getEyePosition());
        }

        }

        // ---- aurora: continuous world-Z latitude from Atmora (-6500) through Skyrim (-2000) to Cyrodiil (+3500..+6000)
        double pz = player.getZ();
        if (AtmosphereConfig.AURORA_BY_REGION.get()) {
            float latAlpha = AtmosphereMath.auroraAlphaFactor(pz);
            if (latAlpha <= 0.0F) {
                coldTarget = 0.0F;
            } else {
                BlockPos pos = player.blockPosition();
                float temp = mc.level.getBiome(pos).value().getBaseTemperature();
                float tempBoost = Mth.clamp(1.12F - 0.25F * temp, 0.72F, 1.15F);
                coldTarget = Mth.clamp(latAlpha * tempBoost, 0.0F, 1.0F);
            }
        } else {
            BlockPos pos = player.blockPosition();
            float temp = mc.level.getBiome(pos).value().getBaseTemperature();
            boolean cold = temp <= AtmosphereConfig.AURORA_MAX_TEMPERATURE.get();
            coldTarget = cold ? 1.0F : 0.0F;
        }
        if (trackedPlayer != player) {
            SkyRenderer.coldness = coldTarget;
            SkyRenderer.coldnessPrev = coldTarget;
        } else {
            SkyRenderer.coldnessPrev = SkyRenderer.coldness;
            SkyRenderer.coldness += (coldTarget - SkyRenderer.coldness) * 0.08F;
        }

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
