package com.nirncraft.survival;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Survival module settings: {@code config/nirncraft-survival.toml}. */
public final class SurvivalConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ORE_REGEN;
    public static final ModConfigSpec.IntValue ORE_REGEN_TICKS;
    public static final ModConfigSpec.BooleanValue ORE_REGEN_NETHER;

    public static final ModConfigSpec.BooleanValue DISEASES;
    public static final ModConfigSpec.DoubleValue DISEASE_CHANCE;
    public static final ModConfigSpec.IntValue CURE_PRICE;
    public static final ModConfigSpec.IntValue BLESSING_TICKS;

    public static final ModConfigSpec.BooleanValue INNKEEPERS;
    public static final ModConfigSpec.IntValue ROOM_PRICE;
    public static final ModConfigSpec.IntValue INNKEEPER_RESPAWN_TICKS;

    public static final ModConfigSpec.BooleanValue SURVIVAL_MODE;
    public static final ModConfigSpec.DoubleValue HUNGER_PER_HOUR;
    public static final ModConfigSpec.DoubleValue FATIGUE_PER_HOUR;
    public static final ModConfigSpec.DoubleValue COLD_RATE;
    public static final ModConfigSpec.DoubleValue CARRY_PENALTY;
    public static final ModConfigSpec.BooleanValue FREEZING_DAMAGE;
    public static final ModConfigSpec.BooleanValue BLOCK_FAST_TRAVEL;
    public static final ModConfigSpec.BooleanValue STAGE_MESSAGES;

    public static final ModConfigSpec.BooleanValue FISHING_MINIGAME;
    public static final ModConfigSpec.DoubleValue FISHING_BITE_SPEED;
    public static final ModConfigSpec.IntValue FISH_STOCK_REGEN_TICKS;

    public static final ModConfigSpec.BooleanValue PLAYER_CORPSES;
    public static final ModConfigSpec.ConfigValue<String> CORPSE_LOOT_ACCESS;
    public static final ModConfigSpec.IntValue EMPTY_CORPSE_SECONDS;

    public static final ModConfigSpec.IntValue PLOT_PRICE;
    public static final ModConfigSpec.IntValue STEWARD_PRICE;
    /** Where a homestead may be founded (docs/HOMESTEAD.md): distances in blocks from the table. */
    public static final ModConfigSpec.IntValue CLAIM_SETTLEMENT_DISTANCE;
    public static final ModConfigSpec.IntValue CLAIM_DUNGEON_DISTANCE;
    public static final ModConfigSpec.IntValue CLAIM_PLACE_CLEARANCE;
    public static final ModConfigSpec.IntValue CLAIM_SPACING;
    public static final ModConfigSpec.IntValue CLAIM_PRICE_DRAFTING_TABLE;
    public static final ModConfigSpec.IntValue CLAIM_EXPAND_GOLD;
    public static final ModConfigSpec.IntValue CLAIM_MAX_RADIUS;
    public static final ModConfigSpec.IntValue HOMESTEAD_HINT_RANGE;
    public static final ModConfigSpec.IntValue HOMESTEAD_DISCOVER_MARGIN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("ore_veins");
        ORE_REGEN = b.comment("Mined natural ore veins become depleted and grow back after a while (Skyrim ore veins).")
                .define("regenerateOre", true);
        ORE_REGEN_TICKS = b.comment("Ticks until a depleted vein grows back (72000 = three in-game days; sleeping and waiting count).")
                .defineInRange("regenTicks", 72000, 200, 100_000_000);
        ORE_REGEN_NETHER = b.comment("Also deplete ores mined in the Nether (the Overworld always does when enabled).")
                .define("nether", true);
        b.pop();

        b.push("diseases");
        DISEASES = b.comment("Creatures (skeevers, wolves, bears, trolls, draugr, vampires...) can give you diseases.")
                .define("enabled", true);
        DISEASE_CHANCE = b.comment("Base chance per hit from a disease carrier (Argonians and Bosmer resist half).")
                .defineInRange("chance", 0.07, 0.0, 1.0);
        CURE_PRICE = b.comment("Gold a priest asks to cure all diseases.").defineInRange("curePrice", 100, 0, 100000);
        BLESSING_TICKS = b.comment("Duration of a shrine blessing (8000 = eight in-game hours).")
                .defineInRange("blessingTicks", 8000, 20, 10_000_000);
        b.pop();

        b.push("inns");
        INNKEEPERS = b.comment("Spawn an innkeeper near the center of every settlement the first time a player visits it.")
                .define("spawnInnkeepers", true);
        ROOM_PRICE = b.comment("Gold for renting a room for one day.").defineInRange("roomPrice", 10, 0, 100000);
        INNKEEPER_RESPAWN_TICKS = b.comment("A settlement whose innkeeper has not been seen for this long gets a new one.")
                .defineInRange("innkeeperRespawnTicks", 72000, 1200, 100_000_000);
        b.pop();

        b.push("survival_mode");
        SURVIVAL_MODE = b.comment("Skyrim's Survival Mode: hunger, fatigue and cold meters with their penalties, warmth from clothing,",
                        "and a lower carry weight. Each world can still switch it off with the game rule nirncraft:survival_mode.")
                .define("enabled", true);
        HUNGER_PER_HOUR = b.comment("Hunger gained per in-game hour (0-240 scale: Peckish at 40, Hungry 80, Famished 120, Starving 180).")
                .defineInRange("hungerPerHour", 4.0, 0.0, 240.0);
        FATIGUE_PER_HOUR = b.comment("Fatigue gained per in-game hour awake (0-240 scale: Tired at 80, Weary 120, Debilitated 180).")
                .defineInRange("fatiguePerHour", 3.0, 0.0, 240.0);
        COLD_RATE = b.comment("Multiplier for how fast you get cold (and warm up again).")
                .defineInRange("coldRate", 1.0, 0.0, 20.0);
        CARRY_PENALTY = b.comment("Carry weight taken away while Survival Mode is on (Skyrim: you can carry less).")
                .defineInRange("carryPenalty", 100.0, 0.0, 10000.0);
        FREEZING_DAMAGE = b.comment("Numbing cold water hurts: swimming in freezing water can kill you.")
                .define("freezingDamage", true);
        BLOCK_FAST_TRAVEL = b.comment("Disable map fast travel while Survival Mode is on (Skyrim's Survival Mode does; carriages still work).")
                .define("blockFastTravel", false);
        STAGE_MESSAGES = b.comment("Show \"You are hungry\"-style messages when a meter reaches a new stage.")
                .define("stageMessages", true);
        b.pop();

        b.push("fishing");
        FISHING_MINIGAME = b.comment("Reel in at the right moment (Skyrim's fishing). Off: a bite is caught automatically.")
                .define("minigame", true);
        FISHING_BITE_SPEED = b.comment("Multiplier for how quickly fish bite.").defineInRange("biteSpeed", 1.0, 0.1, 10.0);
        FISH_STOCK_REGEN_TICKS = b.comment("Ticks for a fished-out spot to regain one fish (water keeps up to 10 per 16x16 area).")
                .defineInRange("stockRegenTicks", 1500, 20, 1_000_000);
        b.pop();

        b.push("player_corpses");
        PLAYER_CORPSES = b.comment("When you die, what you drop stays in your body where you fell (never despawns while it holds items).")
                .define("enabled", true);
        CORPSE_LOOT_ACCESS = b.comment("Who can take items from a player's body: owner, party (owner and their party) or anyone.")
                .define("lootAccess", "party");
        EMPTY_CORPSE_SECONDS = b.comment("Seconds an emptied player body stays before it fades away.")
                .defineInRange("emptyDespawnSeconds", 60, 1, 100000);
        b.pop();

        b.push("homesteads");
        PLOT_PRICE = b.comment("Gold for the deed to a Hearthfire plot (Lakeview Manor, Windstad Manor, Heljarchen Hall).")
                .defineInRange("plotPrice", 5000, 0, 1_000_000);
        STEWARD_PRICE = b.comment("Gold to hire a steward for your homestead.").defineInRange("stewardPrice", 1000, 0, 1_000_000);
        CLAIM_SETTLEMENT_DISTANCE = b.comment("A homestead may not be founded closer than this to a town, village or city.")
                .defineInRange("claimSettlementDistance", 96, 0, 1000);
        CLAIM_DUNGEON_DISTANCE = b.comment("A homestead may not be founded closer than this to a dungeon.")
                .defineInRange("claimDungeonDistance", 64, 0, 1000);
        CLAIM_PLACE_CLEARANCE = b.comment("A homestead may not be founded within this many blocks beyond the radius of any named place",
                        "on the map (standing stones, forts, shrines, ruins, farms...); the open homestead plots are exempt.")
                .defineInRange("claimPlaceClearance", 48, 0, 500);
        CLAIM_SPACING = b.comment("Minimum distance between the drafting tables of two players' homesteads.")
                .defineInRange("claimSpacing", 64, 0, 2000);
        CLAIM_PRICE_DRAFTING_TABLE = b.comment("Gold a builder or steward in a city asks for a drafting table (the item that founds a homestead).")
                .defineInRange("draftingTablePrice", 250, 0, 1_000_000);
        CLAIM_EXPAND_GOLD = b.comment("Gold for the first expansion of the grounds (+8 blocks of radius); each next costs twice as much.")
                .defineInRange("expandGold", 1500, 0, 1_000_000);
        CLAIM_MAX_RADIUS = b.comment("The largest radius a homestead's grounds can be expanded to.")
                .defineInRange("claimMaxRadius", 44, 28, 64);
        HOMESTEAD_HINT_RANGE = b.comment("Blocks from a homestead at which a stranger sees its undiscovered point on the compass.")
                .defineInRange("homesteadHintRange", 220, 0, 1000);
        HOMESTEAD_DISCOVER_MARGIN = b.comment("Blocks beyond a homestead's boundary at which a stranger discovers it.")
                .defineInRange("homesteadDiscoverMargin", 6, 0, 100);
        b.pop();
        SPEC = b.build();
    }

    private SurvivalConfig() {}
}
