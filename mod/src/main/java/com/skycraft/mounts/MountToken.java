package com.skycraft.mounts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.Horse;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The mount token: a self-contained snapshot of ONE animal, the way it travels when it is not an entity (in its
 * owner's record while it is the active mount or a legend, in a stable's stock, in a pool). The individual is the
 * animal's, not the player's: while the animal exists as an entity its persistent data ({@link MountStats#TAG}, its
 * custom name, its coat, its health) is the truth, and a token is only a copy refreshed on every relevant change, on
 * unload and on logout ({@link MountGrowth#captureToken}). Selling an animal moves its token into the stable's stock;
 * buying it restores the token onto a fresh entity, stats, level, trust, name and coat as they were.
 *
 * <p>Fields: {@code id} (the animal, a UUID that survives respawns, also on the entity as {@link #ID_TAG}),
 * {@code kind}, {@code stats} ({@link MountStats#toTag}), {@code name} (custom name as JSON), {@code coat} (a horse's
 * variant and markings), {@code hp} (health fraction), {@code origin} (how the holder came by it: bought, wild, legend).
 * Tack and saddlebag items are never part of it.</p>
 */
public final class MountToken {
    /** Persistent-data UUID of an animal, on its entity. */
    public static final String ID_TAG = "skycraft_mid";
    /** Persistent-data string on the entity: how its holder came by it (mirrors the token). */
    public static final String ORIGIN_TAG = "skycraft_morigin";
    static final String ID = "id", KIND = "kind", STATS = "stats", NAME = "name", COAT = "coat", HP = "hp", ORIGIN = "origin";

    private MountToken() {}

    /** The animal's id on its entity (assigned the first time it is asked for, on the server). */
    public static UUID idOf(Entity e) {
        CompoundTag t = e.getPersistentData();
        if (t.hasUUID(ID_TAG)) return t.getUUID(ID_TAG);
        UUID id = UUID.randomUUID();
        t.putUUID(ID_TAG, id);
        return id;
    }

    /** A token for an animal that is not (yet) an entity: stock-generated or granted. */
    public static CompoundTag create(MountKind kind, MountStats stats, @Nullable String name, UUID id) {
        CompoundTag t = new CompoundTag();
        t.putUUID(ID, id);
        t.putString(KIND, kind.id);
        t.put(STATS, stats.toTag());
        if (name != null && !name.isEmpty()) t.putString(NAME, Component.Serializer.toJson(Component.literal(name)));
        t.putFloat(HP, 1f);
        return t;
    }

    /** The token of an animal in the world: its stats, name, coat and health as they are now. */
    public static CompoundTag capture(Mob mob, MountKind kind) {
        CompoundTag t = new CompoundTag();
        t.putUUID(ID, idOf(mob));
        t.putString(KIND, kind.id);
        t.put(STATS, MountStats.of(mob, kind).toTag());
        if (mob.hasCustomName() && !kind.unique()) t.putString(NAME, Component.Serializer.toJson(mob.getCustomName()));
        if (mob instanceof Horse h) {
            CompoundTag full = new CompoundTag();
            h.addAdditionalSaveData(full);
            if (full.contains("Variant")) t.putInt(COAT, full.getInt("Variant"));
        }
        float max = mob.getMaxHealth();
        t.putFloat(HP, max <= 0 ? 1f : Math.max(0f, Math.min(1f, mob.getHealth() / max)));
        String origin = mob.getPersistentData().getString(ORIGIN_TAG);
        if (!origin.isEmpty()) t.putString(ORIGIN, origin);
        return t;
    }

    /**
     * Puts the animal of a token onto a fresh entity of its kind: stats and id (the entity's persistent data), name,
     * coat. Health is applied by the caller once the attributes are in ({@link #health}).
     */
    public static void apply(Mob mob, CompoundTag token) {
        MountKind kind = kind(token);
        MountStats.store(mob, stats(token));
        mob.getPersistentData().putUUID(ID_TAG, id(token));
        if (!origin(token).isEmpty()) mob.getPersistentData().putString(ORIGIN_TAG, origin(token));
        if (token.contains(NAME, 8) && (kind == null || !kind.unique())) {
            try {
                Component name = Component.Serializer.fromJson(token.getString(NAME));
                if (name != null) mob.setCustomName(name);
            } catch (RuntimeException ignored) {
                // a malformed name is no name
            }
        }
        if (mob instanceof Horse h && token.contains(COAT, 3)) {
            CompoundTag full = new CompoundTag();
            h.addAdditionalSaveData(full);
            full.putInt("Variant", token.getInt(COAT));
            h.readAdditionalSaveData(full);
        }
    }

    /** Health fraction to restore (never below half: a mount brought back is up and about). */
    public static float health(CompoundTag token) {
        return Math.max(0.5f, Math.min(1f, token.contains(HP) ? token.getFloat(HP) : 1f));
    }

    @Nullable
    public static MountKind kind(CompoundTag token) {
        return MountKind.byId(token.getString(KIND));
    }

    public static MountStats stats(CompoundTag token) {
        MountKind kind = kind(token);
        return MountStats.fromTag(kind == null ? MountKind.WHITERUN_BAY : kind, token.getCompound(STATS));
    }

    /** Replaces the stats of a token. */
    public static void putStats(CompoundTag token, MountStats s) {
        token.put(STATS, s.toTag());
    }

    public static UUID id(CompoundTag token) {
        return token.hasUUID(ID) ? token.getUUID(ID) : new UUID(0L, 0L);
    }

    public static String name(CompoundTag token) {
        if (!token.contains(NAME, 8)) return "";
        try {
            Component c = Component.Serializer.fromJson(token.getString(NAME));
            return c == null ? "" : c.getString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static String origin(CompoundTag token) {
        return token.getString(ORIGIN);
    }

    public static void setOrigin(CompoundTag token, String origin) {
        token.putString(ORIGIN, origin);
    }
}
