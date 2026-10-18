package com.skycraft.inventory;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Inventory module settings: {@code config/skycraft-inventory-common.toml} (server rules: carry weight, persistent
 * drops) and {@code config/skycraft-inventory-client.toml} (per player: inventory screen, HUD).
 */
public final class InventoryConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // common
    public static final ModConfigSpec.BooleanValue CARRY_WEIGHT;
    public static final ModConfigSpec.DoubleValue BASE_CAPACITY;
    public static final ModConfigSpec.DoubleValue CAPACITY_PER_STAMINA;
    public static final ModConfigSpec.DoubleValue ENCUMBERED_SPEED_PENALTY;
    public static final ModConfigSpec.BooleanValue PERSISTENT_DROPS;
    public static final ModConfigSpec.IntValue PERSISTENT_CAP;

    // client
    public static final ModConfigSpec.BooleanValue REPLACE_INVENTORY;
    public static final ModConfigSpec.BooleanValue CARRY_HUD;
    public static final ModConfigSpec.DoubleValue CARRY_HUD_THRESHOLD;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("carryWeight");
        CARRY_WEIGHT = b.comment("Skyrim carry weight: items weigh something and carrying more than your capacity makes you",
                        "walk slowly and unable to run (data/<ns>/skycraft_weights/*.json).")
                .define("enabled", true);
        BASE_CAPACITY = b.comment("Carry capacity of every character before Stamina level-ups (Skyrim: 300).")
                .defineInRange("baseCapacity", 300.0, 1.0, 100000.0);
        CAPACITY_PER_STAMINA = b.comment("Extra carry capacity for every level-up spent on Stamina (Skyrim: 5).")
                .defineInRange("capacityPerStaminaLevel", 5.0, 0.0, 1000.0);
        ENCUMBERED_SPEED_PENALTY = b.comment("UNUSED since encumbrance became graded: speed falls smoothly from 100% at capacity to 30% at double",
                        "capacity (CarryWeight.speedFactor) and you cannot run beyond 10% over capacity. Kept so old configs load.")
                .defineInRange("encumberedSpeedPenalty", 0.2, 0.0, 0.95);
        b.pop();

        b.push("persistentDrops");
        PERSISTENT_DROPS = b.comment("Items dropped by players (and a player's death drops) never despawn, like Skyrim remembers",
                        "where you left things. Mob drops and broken blocks keep vanilla behaviour.")
                .define("enabled", true);
        PERSISTENT_CAP = b.comment("Maximum number of loaded persistent items per dimension. When exceeded, the oldest ones go back",
                        "to vanilla despawning (10 more minutes).")
                .defineInRange("maxPerDimension", 400, 0, 100000);
        b.pop();
        COMMON_SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        c.push("inventory");
        REPLACE_INVENTORY = c.comment("Open the Skyrim inventory instead of the vanilla survival inventory.",
                        "The vanilla inventory (crafting grid, armor slots) stays reachable with the C key or the Crafting button.")
                .define("replaceInventoryScreen", true);
        CARRY_HUD = c.comment("Show 'Carry 312/300' above the stamina bar when you are close to or over your carry capacity.")
                .define("carryHud", true);
        CARRY_HUD_THRESHOLD = c.comment("Fraction of the capacity from which the carry weight is shown on the HUD.")
                .defineInRange("carryHudThreshold", 0.9, 0.0, 1.0);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private InventoryConfig() {}
}
