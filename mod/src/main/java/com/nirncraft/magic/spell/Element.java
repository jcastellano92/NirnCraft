package com.nirncraft.magic.spell;

/** Visual/damage flavour of a spell. Sent to clients as its ordinal for particle effects. */
public enum Element {
    NONE, FIRE, FROST, SHOCK, HOLY, ARCANE, CONJURE, MIND, SOUL, NATURE;

    public static final Element[] VALUES = values();

    public static Element byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : NONE;
    }
}
