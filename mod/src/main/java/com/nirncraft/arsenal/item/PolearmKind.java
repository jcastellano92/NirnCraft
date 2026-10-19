package com.nirncraft.arsenal.item;

import com.nirncraft.crafting.SmithingTier;

import java.util.Locale;

/**
 * The polearm family (Polearms skill): spears, halberds and glaives in every smithing tier. {@code damage} adds to the
 * tier's damage like {@link com.nirncraft.crafting.WeaponType}; {@code reach} is extra attack range in blocks.
 * Better Combat movesets: {@code bettercombat:spear}, {@code :halberd}, {@code :glaive} (data/nirncraft/weapon_attributes).
 */
public enum PolearmKind {
    /** Quick thrusts at long reach; light enough to carry a shield. */
    SPEAR(4, -2.6f, 1.0, false, 1.1f, 1.0f, 2, 1),
    /** Axe-bladed and hooked: slow, heavy, sweeping. */
    HALBERD(7, -3.1f, 1.5, true, 2.1f, 1.3f, 4, 2),
    /** A long blade on a staff: wide sweeps, between the two. */
    GLAIVE(6, -2.9f, 1.25, true, 1.9f, 1.2f, 3, 2);

    public static final PolearmKind[] VALUES = values();

    public final int damage;
    public final float speed;
    public final double reach;
    public final boolean twoHanded;
    public final float valueMult;
    public final float durabilityMult;
    /** Main material and leather strips needed at the forge. */
    public final int materialCount;
    public final int strips;

    PolearmKind(int damage, float speed, double reach, boolean twoHanded, float valueMult, float durabilityMult, int materialCount, int strips) {
        this.damage = damage;
        this.speed = speed;
        this.reach = reach;
        this.twoHanded = twoHanded;
        this.valueMult = valueMult;
        this.durabilityMult = durabilityMult;
        this.materialCount = materialCount;
        this.strips = strips;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Item id, e.g. {@code steel_halberd}. */
    public String itemId(SmithingTier tier) {
        return tier.id + "_" + id();
    }

    /** Halberds and glaives sweep like greatswords; spears only thrust. */
    public boolean sweeps() {
        return this != SPEAR;
    }
}
