package com.skycraft.quest;

import net.minecraftforge.common.ForgeConfigSpec;

/** Quest & party settings: config/skycraft-quest.toml (common, server-authoritative). */
public final class QuestConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue FRIENDLY_FIRE;
    public static final ForgeConfigSpec.IntValue MAX_PARTY_SIZE;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_QUESTS;
    public static final ForgeConfigSpec.DoubleValue GOLD_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue SPAWN_DISTANCE;
    public static final ForgeConfigSpec.BooleanValue MAIN_QUEST;
    public static final ForgeConfigSpec.BooleanValue DARK_WHISPER;
    public static final ForgeConfigSpec.BooleanValue HELGEN_START;
    public static final ForgeConfigSpec.BooleanValue INTRO;
    public static final ForgeConfigSpec.BooleanValue RUINED_HELGEN;
    public static final ForgeConfigSpec.IntValue SKIP_DAYS;
    public static final ForgeConfigSpec.BooleanValue INTRO_LOCK_EXIT;
    public static final ForgeConfigSpec.BooleanValue INTRO_HIDE_PLAYERS;
    public static final ForgeConfigSpec.IntValue INTRO_LOADING_SECONDS;
    public static final ForgeConfigSpec.IntValue INTRO_CUTSCENE_SECONDS;
    public static final ForgeConfigSpec.EnumValue<IntroMigration> INTRO_EXISTING_SAVES;

    /** What an existing save (a character made before the cottage opening) does at its next login. */
    public enum IntroMigration { SKIP, REPLAY_IF_UNFINISHED }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("party");
        FRIENDLY_FIRE = b.comment("If false, members of the same party (and their pets) can't hurt each other.")
                .define("friendlyFire", false);
        MAX_PARTY_SIZE = b.defineInRange("maxPartySize", 6, 2, 32);
        b.pop();
        b.push("quests");
        MAX_ACTIVE_QUESTS = b.comment("Maximum active side quests per party (or per solo player). The main quest doesn't count.")
                .defineInRange("maxActiveQuests", 10, 1, 100);
        GOLD_MULTIPLIER = b.comment("Multiplier for every quest's gold reward.").defineInRange("goldMultiplier", 1.0, 0.0, 100.0);
        SPAWN_DISTANCE = b.comment("Quest enemies are spawned when a party member comes within this many blocks of the target spot.")
                .defineInRange("spawnDistance", 64, 16, 160);
        MAIN_QUEST = b.comment("Start the main quest 'The Dragonborn' automatically once a race is chosen.").define("mainQuest", true);
        DARK_WHISPER = b.comment("Murderers receive a whisper from the Dark Brotherhood at night.").define("darkBrotherhoodWhisper", true);
        HELGEN_START = b.comment("Legacy: teleport new players to Helgen at the start of the main quest (off: everybody starts at the world spawn on the road).")
                .define("helgenStart", false);
        INTRO = b.comment("Play the short opening (a wake-up scene and one distant dragon sighting). False skips it: the first objective is still set.")
                .define("intro", true);
        INTRO_LOCK_EXIT = b.comment("While in the opening (before the cottage key is used) the door is locked, teleports are blocked and a player",
                        "that ends up away from the cottage is put back. False leaves the door and teleports alone.")
                .define("introLockExit", true);
        INTRO_HIDE_PLAYERS = b.comment("Players still in the opening are not drawn for each other (they appear when they leave the cottage).")
                .define("introHidePlayers", true);
        INTRO_LOADING_SECONDS = b.comment("Longest the 'world loading' screen after character creation waits for the chunks, in seconds.")
                .defineInRange("introLoadingSeconds", 45, 10, 300);
        INTRO_CUTSCENE_SECONDS = b.comment("Length of the dragon-attack cutscene at the cottage window, in seconds.")
                .defineInRange("introCutsceneSeconds", 48, 30, 120);
        INTRO_EXISTING_SAVES = b.comment("A character that existed before the cottage opening: SKIP = never play it;",
                        "REPLAY_IF_UNFINISHED = play it once if the first quest 'Unbound' is not done yet (it starts at the cottage).",
                        "An operator can always run '/skycraft-quest intro restart [player]'.")
                .defineEnum("introExistingSaves", IntroMigration.REPLAY_IF_UNFINISHED);
        RUINED_HELGEN = b.comment("Helgen is a ruin after the dragon: only survivors and soldiers live there (no shopkeepers, no innkeeper).")
                .define("ruinedHelgen", true);
        SKIP_DAYS = b.comment("In-game days a quest step must stay unfinished before the journal offers to skip it (0 = never).",
                        "A fallback against broken states, e.g. a quest NPC or relic lost to another mod.")
                .defineInRange("skipAfterDays", 3, 0, 365);
        b.pop();
        SPEC = b.build();
    }

    private QuestConfig() {}
}
