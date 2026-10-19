package com.nirncraft.curse;

/** What a player currently looks like: themselves, the werewolf's Beast Form, or the winged Vampire Lord. */
public enum Form {
    NONE(""),
    BEAST("beast"),
    LORD("lord");

    public static final Form[] VALUES = values();

    public final String id;

    Form(String id) {
        this.id = id;
    }

    public static Form byId(String id) {
        for (Form f : VALUES) {
            if (f.id.equals(id)) return f;
        }
        return NONE;
    }

    public static Form byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : NONE;
    }
}
