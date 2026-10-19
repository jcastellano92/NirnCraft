package com.nirncraft.arsenal.item;

import net.minecraft.world.item.Rarity;

/**
 * Skyrim arrows and bolts. {@code damage} is the arrow's base damage (vanilla arrow: 2.0; damage dealt is base x
 * speed, so a fully drawn bow roughly triples it). Bolts only fit NirnCraft crossbows.
 */
public enum ArrowKind {
    IRON("iron_arrow", 2.0f, Element.NONE, false, Rarity.COMMON),
    STEEL("steel_arrow", 2.2f, Element.NONE, false, Rarity.COMMON),
    ORCISH("orcish_arrow", 2.4f, Element.NONE, false, Rarity.COMMON),
    DWARVEN("dwarven_arrow", 2.6f, Element.NONE, false, Rarity.COMMON),
    ELVEN("elven_arrow", 2.8f, Element.NONE, false, Rarity.COMMON),
    GLASS("glass_arrow", 3.0f, Element.NONE, false, Rarity.UNCOMMON),
    EBONY("ebony_arrow", 3.2f, Element.NONE, false, Rarity.UNCOMMON),
    DAEDRIC("daedric_arrow", 3.6f, Element.NONE, false, Rarity.RARE),
    DRAGONBONE("dragonbone_arrow", 3.8f, Element.NONE, false, Rarity.RARE),
    FIRE("fire_arrow", 2.2f, Element.FIRE, false, Rarity.UNCOMMON),
    FROST("frost_arrow", 2.2f, Element.FROST, false, Rarity.UNCOMMON),
    SHOCK("shock_arrow", 2.2f, Element.SHOCK, false, Rarity.UNCOMMON),
    STEEL_BOLT("steel_bolt", 2.6f, Element.NONE, true, Rarity.COMMON),
    DWARVEN_BOLT("dwarven_bolt", 3.0f, Element.NONE, true, Rarity.COMMON),
    EXPLOSIVE_BOLT("explosive_bolt", 2.6f, Element.EXPLOSIVE, true, Rarity.UNCOMMON);

    public enum Element { NONE, FIRE, FROST, SHOCK, EXPLOSIVE }

    public static final ArrowKind[] VALUES = values();

    public final String id;
    public final float damage;
    public final Element element;
    public final boolean bolt;
    public final Rarity rarity;

    ArrowKind(String id, float damage, Element element, boolean bolt, Rarity rarity) {
        this.id = id;
        this.damage = damage;
        this.element = element;
        this.bolt = bolt;
        this.rarity = rarity;
    }
}
