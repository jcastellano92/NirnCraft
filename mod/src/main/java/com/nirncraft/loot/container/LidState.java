package com.nirncraft.loot.container;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * The lid (or door, or flap) of a loot container: a blockstate property with one block model per value, so opening is
 * a model swap the far-field renderer sees, never a block-entity renderer. Opening steps closed, ajar, open two ticks
 * apart; closing steps back ({@link LootContainerBlock#tick}).
 */
public enum LidState implements StringRepresentable {
    CLOSED, AJAR, OPEN;

    private final String id = name().toLowerCase(Locale.ROOT);

    @Override
    public String getSerializedName() {
        return id;
    }

    /** One step toward {@code target} (closed and open are two steps apart). */
    public LidState toward(LidState target) {
        if (this == target) return this;
        return target.ordinal() > ordinal() ? values()[ordinal() + 1] : values()[ordinal() - 1];
    }
}
