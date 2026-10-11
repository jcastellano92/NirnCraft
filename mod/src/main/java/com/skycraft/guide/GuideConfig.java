package com.skycraft.guide;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Guide module settings: {@code config/skycraft-guide-common.toml} (the first-join gift) and
 * {@code config/skycraft-guide-client.toml} (tutorial hints, per player).
 */
public final class GuideConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    // common
    public static final ForgeConfigSpec.BooleanValue GIVE_HANDBOOK;

    // client
    public static final ForgeConfigSpec.BooleanValue HINTS;
    public static final ForgeConfigSpec.IntValue HINT_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SEEN_HINTS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("handbook");
        GIVE_HANDBOOK = b.comment("Give every player a Traveler's Handbook the first time they join a world.")
                .define("giveOnFirstJoin", true);
        b.pop();
        COMMON_SPEC = b.build();

        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        c.push("hints");
        HINTS = c.comment("Show Skyrim-style tutorial hints in the top-left corner the first time you do something",
                        "(sneak, pick a lock, learn a Word of Power, feel the cold...). Each hint is shown once.")
                .define("tutorialHints", true);
        HINT_SECONDS = c.comment("How long each hint stays on screen (seconds).")
                .defineInRange("hintSeconds", 10, 3, 60);
        SEEN_HINTS = c.comment("Hints already shown. Clear this list (or type /handbook hints reset) to see them again.")
                .defineListAllowEmpty(List.of("seen"), List::of, o -> o instanceof String);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private GuideConfig() {}
}
