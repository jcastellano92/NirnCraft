package com.skycraft.audio;

import net.minecraftforge.common.ForgeConfigSpec;

/** Client settings of the audio module: {@code config/skycraft-audio-client.toml}. */
public final class AudioConfig {
    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec.BooleanValue AMBIENCE;
    public static final ForgeConfigSpec.DoubleValue AMBIENCE_VOLUME;
    public static final ForgeConfigSpec.BooleanValue AMBIENT_CALLS;
    public static final ForgeConfigSpec.BooleanValue DISTANT_DRAGONS;

    public static final ForgeConfigSpec.BooleanValue REVERB;
    public static final ForgeConfigSpec.DoubleValue REVERB_AMOUNT;
    public static final ForgeConfigSpec.BooleanValue OCCLUSION;

    public static final ForgeConfigSpec.BooleanValue PROVINCE_MUSIC;
    public static final ForgeConfigSpec.BooleanValue BOSS_MUSIC;
    public static final ForgeConfigSpec.BooleanValue JUKEBOX_DUCKING;
    public static final ForgeConfigSpec.IntValue MIN_SILENCE;
    public static final ForgeConfigSpec.IntValue MAX_SILENCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Skycraft ambience: looping environment beds (wind, forests, marshes, caves...) and random calls around you.")
                .push("ambience");
        AMBIENCE = b.comment("Play the ambience engine's looping beds.").define("enabled", true);
        AMBIENCE_VOLUME = b.comment("Ambience bed volume (also scaled by the Ambient/Environment slider).")
                .defineInRange("volume", 0.8, 0.0, 2.0);
        AMBIENT_CALLS = b.comment("Random positional calls: birds, owls, wolves, frogs, cave drips, gulls...").define("calls", true);
        DISTANT_DRAGONS = b.comment("Very rarely, hear a far-away dragon roar in Skyrim's wilds.").define("distantDragons", true);
        b.pop();

        b.comment("Environmental acoustics: reverb in caves, ruins and halls; muffled sounds through walls and from outside.")
                .push("acoustics");
        REVERB = b.comment("Add OpenAL EFX reverb when you are underground or indoors (needs an OpenAL with EFX; disables itself if missing).")
                .define("reverb", true);
        REVERB_AMOUNT = b.comment("Reverb strength multiplier.").defineInRange("reverbAmount", 1.0, 0.0, 2.0);
        OCCLUSION = b.comment("Muffle sounds that are behind walls or outside while you are inside.").define("occlusion", true);
        b.pop();

        b.comment("Situational soundtrack (the master switch is world/atmosphere.music in skycraft-world-client.toml).").push("music");
        PROVINCE_MUSIC = b.comment("Each Tamriel province (and the lands beyond) has its own exploration music; off = Skyrim's everywhere.")
                .define("provinceMusic", true);
        BOSS_MUSIC = b.comment("Play the boss / dragon battle music near dragons and bosses.").define("bossMusic", true);
        JUKEBOX_DUCKING = b.comment("Fade the soundtrack out while a jukebox (or a bard) is playing nearby.").define("jukeboxDucking", true);
        MIN_SILENCE = b.comment("Shortest pause between exploration pieces, in seconds.").defineInRange("minSilence", 20, 0, 600);
        MAX_SILENCE = b.comment("Longest pause between exploration pieces, in seconds.").defineInRange("maxSilence", 70, 0, 1200);
        b.pop();
        CLIENT_SPEC = b.build();
    }

    private AudioConfig() {}
}
