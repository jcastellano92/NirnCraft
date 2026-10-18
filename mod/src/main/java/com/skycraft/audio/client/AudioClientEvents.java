package com.skycraft.audio.client;

import com.skycraft.Skycraft;
import com.skycraft.audio.AudioSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** Forge-bus client events of the audio module: the listener probe, ambience, acoustics. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class AudioClientEvents {
    private AudioClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ListenerEnvironment.tick(mc);
        AmbienceEngine.tick(mc);
        Acoustics.tick(mc);
        AudioDebug.tick(mc);
    }

    /** Skyrim's wolves: vanilla wolves howl and growl with Skycraft's voices. */
    @SubscribeEvent
    public static void onVanillaVoice(PlaySoundEvent event) {
        SoundInstance s = event.getSound();
        if (s == null || !"minecraft".equals(s.getIdentifier().getNamespace())) return;
        String kind = switch (s.getIdentifier().getPath()) {
            case "entity.wolf.howl" -> "howl";
            case "entity.wolf.growl" -> "growl";
            default -> null;
        };
        if (kind == null) return;
        // (the original isn't resolved yet, so its volume and pitch can't be read here)
        event.setSound(new SimpleSoundInstance(AudioSounds.creature("wolf", kind), s.getSource(), 1.0f,
                0.92f + (float) Math.random() * 0.16f, SoundInstance.createUnseededRandom(), s.getX(), s.getY(), s.getZ()));
    }

    /** Runs last, so it sees the sound that will really play: estimates its occlusion. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlaySound(PlaySoundEvent event) {
        AudioDebug.record(event.getSound());
        Acoustics.onPlay(event.getSound());
    }

    @SubscribeEvent
    public static void onSource(PlaySoundSourceEvent event) {
        Acoustics.onSource(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onStream(PlayStreamingSourceEvent event) {
        Acoustics.onSource(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onEngineLoad(SoundEngineLoadEvent event) {
        Acoustics.onEngineLoad();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AmbienceEngine.stopAll(Minecraft.getInstance());
        ListenerEnvironment.reset();
        Acoustics.reset();
    }
}
