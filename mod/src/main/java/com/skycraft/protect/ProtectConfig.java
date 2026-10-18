package com.skycraft.protect;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** World protection settings: {@code config/skycraft-protect.toml}. */
public final class ProtectConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue CREATIVE_BYPASS;
    public static final ModConfigSpec.ConfigValue<String> TERRAIN_DIGGING;
    public static final ModConfigSpec.BooleanValue HOMESTEAD_FREE_DIGGING;
    public static final ModConfigSpec.BooleanValue ORE_MINING;
    public static final ModConfigSpec.BooleanValue TREE_CHOPPING;
    public static final ModConfigSpec.BooleanValue GRIEFING_OFF;
    public static final ModConfigSpec.BooleanValue FIRE_SPREAD_OFF;
    public static final ModConfigSpec.BooleanValue FLUIDS_OFF;
    public static final ModConfigSpec.BooleanValue PROTECT_ENTITIES;
    public static final ModConfigSpec.BooleanValue TOOL_ACTIONS_OFF;
    public static final ModConfigSpec.IntValue HINT_COOLDOWN_TICKS;
    public static final ModConfigSpec.IntValue SETTLEMENT_MARGIN;
    public static final ModConfigSpec.IntValue ROAD_MARGIN;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Skyrim-style world protection: nobody can break or place blocks outside a homestead they may build in,",
                "except the designated harvests (ore veins, wild trees, plants) and blocks they placed themselves.")
                .define("enabled", true);
        CREATIVE_BYPASS = b.comment("Players in creative mode are not restricted (ops in survival use /skycraft-protect bypass).")
                .define("creativeBypass", true);

        b.push("harvest");
        TERRAIN_DIGGING = b.comment("Digging natural terrain (dirt, sand, stone...) outside a homestead:",
                "  perks     = wilderness digging stays a Mining-perk privilege (Excavator / Stonebreaker / Deep Delver),",
                "              but never inside a settlement, road, structure or landmark footprint;",
                "  homestead = never outside a homestead (strict Skyrim: the Mining perks only matter at home);",
                "  free      = no terrain rule of its own (the perk rules of the dig module still apply).")
                .define("terrainDigging", "perks", value -> value instanceof String mode && List.of("perks", "homestead", "free").contains(mode));
        HOMESTEAD_FREE_DIGGING = b.comment("Inside a homestead you may build in, terrain can be dug without the Mining perks.",
                "Off (owner decision, playtest 4): digging at home still needs Excavator / Stonebreaker / Deep Delver.")
                .define("homesteadFreeDiggingWithoutPerks", false);
        ORE_MINING = b.comment("Ore and mineral veins may be mined with a pickaxe anywhere outside settlements and roads.")
                .define("oreMining", true);
        TREE_CHOPPING = b.comment("Trees (logs, leaves) may be chopped away from settlements, roads, structures and landmarks.")
                .define("treeChopping", true);
        b.pop();

        b.push("griefing");
        GRIEFING_OFF = b.comment("Explosions (creepers, TNT, ghasts, the dragon), endermen, withers, door-breaking mobs and the like never",
                "destroy blocks. TNT included, in a homestead too: it hurts and throws, it never breaks a block.")
                .define("mobGriefingOff", true);
        FIRE_SPREAD_OFF = b.comment("Fire that appears anywhere, a homestead included (spread, lightning, lava, flint), is put out at once:",
                "fire never spreads and never destroys a block. Blocks tagged skycraft:persistent_fire (scripted scenes) stay.")
                .define("fireSpreadOff", true);
        FLUIDS_OFF = b.comment("No buckets of water or lava outside a homestead; lava and water do not turn into stone there.")
                .define("fluidsOff", true);
        PROTECT_ENTITIES = b.comment("Item frames, paintings and armor stands outside a homestead cannot be broken by players or explosions.")
                .define("protectDecorEntities", true);
        TOOL_ACTIONS_OFF = b.comment("Hoes, axes and shovels do not till, strip or flatten, bone meal does not grow, farmland is not trampled,",
                "outside a homestead.")
                .define("toolActionsOff", true);
        b.pop();

        b.push("tuning");
        HINT_COOLDOWN_TICKS = b.comment("Ticks between two 'This isn't yours to break' hints for one player.")
                .defineInRange("hintCooldownTicks", 40, 0, 6000);
        SETTLEMENT_MARGIN = b.comment("Blocks around a settlement's layout that still count as the settlement.")
                .defineInRange("settlementMargin", 6, 0, 64);
        ROAD_MARGIN = b.comment("Blocks around a road's paved band that still count as the road.")
                .defineInRange("roadMargin", 4, 0, 32);
        b.pop();
        SPEC = b.build();
    }

    private ProtectConfig() {}

    /** The configured terrain mode: {@code perks}, {@code homestead} or {@code free}. */
    public static String terrainMode() {
        return TERRAIN_DIGGING.get();
    }
}
