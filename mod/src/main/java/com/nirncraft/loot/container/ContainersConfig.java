package com.nirncraft.loot.container;

import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Loot container settings: {@code config/nirncraft-containers-common.toml} (docs/CONTAINERS.md section 10).
 *
 * <p>{@code system} decides who places and runs per-player loot containers while the pack still ships Lootr:
 * {@code AUTO} (the default) leaves Lootr in charge while it is installed and uses NirnCraft's containers once it is
 * gone; {@code NIRNCRAFT} uses ours even with Lootr installed (both coexist: Lootr keeps the chests it already
 * converted until they are converted in turn); {@code LOOTR} and {@code VANILLA} place vanilla chests with loot
 * tables (Lootr, if present, makes them per player; otherwise {@code loot.Restock} restocks the shared chest).
 * Containers of ours that already stand in the world keep working in every mode.</p>
 *
 * <p>Every getter falls back to its default when the file is not loaded yet (world generation can ask early).</p>
 */
public final class ContainersConfig {
    public enum Mode { AUTO, NIRNCRAFT, LOOTR, VANILLA }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<Mode> SYSTEM;
    public static final ModConfigSpec.BooleanValue PER_PLAYER;
    public static final ModConfigSpec.BooleanValue CONVERT_NEW_CHUNKS;
    public static final ModConfigSpec.BooleanValue CONVERT_EXISTING;
    public static final ModConfigSpec.BooleanValue CONVERT_VANILLA_TABLES;
    public static final ModConfigSpec.BooleanValue LIMIT_WORLD_STASH;
    public static final ModConfigSpec.DoubleValue STASH_SCALE;
    public static final ModConfigSpec.DoubleValue RESTOCK_SCALE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("containers");
        SYSTEM = b.comment("Who runs loot containers. AUTO: Lootr while it is installed, NirnCraft's own once it is removed.",
                        "NIRNCRAFT: NirnCraft's per-player containers even with Lootr installed (world generation places ours).",
                        "LOOTR / VANILLA: world generation places vanilla chests with loot tables (Lootr or loot.Restock handle them).")
                .defineEnum("system", Mode.AUTO);
        PER_PLAYER = b.comment("World loot containers keep one copy per player: co-op players each find their own loot.",
                        "false: everyone shares one copy (a looted chest is empty for the next player too).")
                .define("perPlayer", true);
        CONVERT_NEW_CHUNKS = b.comment("When NirnCraft runs containers: vanilla chests and barrels with a loot table in freshly generated",
                        "chunks (structures that do not place ours directly) become NirnCraft containers.")
                .define("convertNewChunks", true);
        CONVERT_EXISTING = b.comment("When NirnCraft runs containers: unopened vanilla loot chests and every Lootr container in chunks",
                        "generated before are converted when their chunk loads. Turn on before removing Lootr from an old world.")
                .define("convertExisting", false);
        CONVERT_VANILLA_TABLES = b.comment("Also convert containers whose loot table is not NirnCraft's (minecraft:chests/... of vanilla structures).")
                .define("convertVanillaTables", true);
        b.pop();
        b.push("balance");
        LIMIT_WORLD_STASH = b.comment("World loot containers take only so much of a player's own things on top of their loot (a chest",
                        "150 kg, a hoard 250, a barrel 100, a sack 40, an urn 25, a strongbox 20, remains 15, a shelf 30 kg of books).",
                        "Player-placed containers are always unlimited.")
                .define("limitWorldStash", true);
        STASH_SCALE = b.comment("Multiplier on those stash allowances.")
                .defineInRange("stashScale", 1.0, 0.0, 100.0);
        RESTOCK_SCALE = b.comment("Multiplier on the restock times (30 days dungeons and wilderness, 10 households and shops; boss",
                        "hoards never).")
                .defineInRange("restockScale", 1.0, 0.01, 100.0);
        b.pop();
        SPEC = b.build();
    }

    private ContainersConfig() {}

    private static <T> T get(ModConfigSpec.ConfigValue<T> v, T fallback) {
        try {
            return SPEC.isLoaded() ? v.get() : fallback;
        } catch (RuntimeException notLoaded) {
            return fallback;
        }
    }

    public static Mode system() {
        return get(SYSTEM, Mode.AUTO);
    }

    /** Whether NirnCraft places and converts loot containers now (AUTO: once Lootr is gone). */
    public static boolean nirncraftRuns() {
        return switch (system()) {
            case NIRNCRAFT -> true;
            case AUTO -> !LootrCompat.present();
            default -> false;
        };
    }

    public static boolean perPlayer() {
        return get(PER_PLAYER, true);
    }

    public static boolean convertNewChunks() {
        return get(CONVERT_NEW_CHUNKS, true);
    }

    public static boolean convertExisting() {
        return get(CONVERT_EXISTING, false);
    }

    public static boolean convertVanillaTables() {
        return get(CONVERT_VANILLA_TABLES, true);
    }

    public static boolean limitWorldStash() {
        return get(LIMIT_WORLD_STASH, true);
    }

    public static double stashScale() {
        return get(STASH_SCALE, 1.0);
    }

    public static double restockScale() {
        return get(RESTOCK_SCALE, 1.0);
    }

    static boolean lootrLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("lootr");
        } catch (RuntimeException e) {
            return false;
        }
    }
}
