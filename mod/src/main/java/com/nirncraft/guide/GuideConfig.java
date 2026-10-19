package com.nirncraft.guide;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Guide module settings: {@code config/nirncraft-guide-common.toml} (the first-join gift) and
 * {@code config/nirncraft-guide-client.toml} (tutorial hints, per player).
 */
public final class GuideConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec CLIENT_SPEC;

    // common
    public static final ModConfigSpec.BooleanValue GIVE_HANDBOOK;

    // client
    public static final ModConfigSpec.BooleanValue HINTS;
    public static final ModConfigSpec.IntValue HINT_SECONDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SEEN_HINTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("handbook");
        GIVE_HANDBOOK = b.comment("Give every player a Traveler's Handbook the first time they join a world.")
                .define("giveOnFirstJoin", true);
        b.pop();
        COMMON_SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
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
