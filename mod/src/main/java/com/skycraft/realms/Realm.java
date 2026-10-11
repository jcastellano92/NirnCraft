package com.skycraft.realms;

import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The planes of Oblivion and the afterlife that Skycraft builds as real dimensions. The Deadlands replace the
 * Nether's worldgen and Sovngarde the End's (so vanilla portals, the End's dragon fight and old saves keep working);
 * the other four are Skycraft's own dimensions.
 *
 * <p>{@link #lockBonus} raises lock tiers of containers in the realm, {@link #minLevel}/{@link #maxLevel} is the
 * level band of its creatures. Fixed coordinates of each realm's landmarks are in {@link RealmLayout}.</p>
 */
public enum Realm {
    /** Mehrunes Dagon's realm: jagged rock islands over lava seas, sigil towers, citadels. */
    DEADLANDS("deadlands", Level.NETHER, 1, 12, 30, 0xC83A1E),
    /** The Nordic afterlife: misty golden fields, the Whalebone Bridge and the Hall of Valor. */
    SOVNGARDE("sovngarde", Level.END, 2, 20, 40, 0xE8C878),
    /** Sheogorath's realm: Mania and Dementia, New Sheoth, the Fringe. */
    SHIVERING_ISLES("shivering_isles", key("shivering_isles"), 1, 10, 28, 0xB060E0),
    /** Hermaeus Mora's endless library over a sea of ink. */
    APOCRYPHA("apocrypha", key("apocrypha"), 2, 18, 36, 0x50A050),
    /** The Ideal Masters' plane of trapped souls. */
    SOUL_CAIRN("soul_cairn", key("soul_cairn"), 2, 16, 34, 0x9070D0),
    /** Molag Bal's dark, burning mirror of Tamriel. */
    COLDHARBOUR("coldharbour", key("coldharbour"), 3, 25, 45, 0x4060A0);

    private static final Realm[] VALUES = values();

    public final String id;
    public final ResourceKey<Level> dimension;
    public final int lockBonus;
    public final int minLevel;
    public final int maxLevel;
    /** Map and HUD accent colour. */
    public final int color;

    Realm(String id, ResourceKey<Level> dimension, int lockBonus, int minLevel, int maxLevel, int color) {
        this.id = id;
        this.dimension = dimension;
        this.lockBonus = lockBonus;
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.color = color;
    }

    private static ResourceKey<Level> key(String path) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(Skycraft.MODID, path));
    }

    /** {@code realm.skycraft.<id>}: "The Deadlands", "Sovngarde"... */
    public Component displayName() {
        return Component.translatable("realm.skycraft." + id);
    }

    /** The dimension effects id the realm's dimension type uses ({@code skycraft:<id>}). */
    public ResourceLocation effects() {
        return new ResourceLocation(Skycraft.MODID, id);
    }

    /** True for the four dimensions Skycraft adds (not the reused Nether and End). */
    public boolean ownDimension() {
        return this != DEADLANDS && this != SOVNGARDE;
    }

    @Nullable
    public static Realm of(@Nullable ResourceKey<Level> dimension) {
        if (dimension == null) return null;
        for (Realm r : VALUES) if (r.dimension == dimension || r.dimension.equals(dimension)) return r;
        return null;
    }

    @Nullable
    public static Realm of(@Nullable Level level) {
        return level == null ? null : of(level.dimension());
    }

    @Nullable
    public static Realm byId(@Nullable String id) {
        if (id == null) return null;
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains(":")) {
            for (Realm r : VALUES) if (r.dimension.location().toString().equals(s)) return r;
            s = s.substring(s.indexOf(':') + 1);
        }
        for (Realm r : VALUES) if (r.id.equals(s)) return r;
        if (s.equals("oblivion") || s.equals("the_nether") || s.equals("nether")) return DEADLANDS;
        if (s.equals("the_end") || s.equals("end")) return SOVNGARDE;
        return null;
    }
}
