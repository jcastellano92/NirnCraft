package com.nirncraft.survival;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The Nine Divines and their shrine blessings (Skyrim):
 * <ul>
 *     <li>Akatosh: magicka regenerates 10% faster</li>
 *     <li>Arkay: +25 health</li>
 *     <li>Dibella: +10 Speech (flag {@code blessing_dibella} for economy/crime)</li>
 *     <li>Julianos: +25 magicka</li>
 *     <li>Kynareth: +25 stamina</li>
 *     <li>Mara: healing received +10% (Restoration is 10% stronger)</li>
 *     <li>Stendarr: blocking 10% more effective</li>
 *     <li>Talos: shout cooldown 20% shorter (flag {@code blessing_talos} for the magic module)</li>
 *     <li>Zenithar: prices 10% better (flag {@code blessing_zenithar} for the economy module)</li>
 * </ul>
 * Every active blessing is also visible as the {@link com.nirncraft.core.Buffs} flag {@code "blessing_<id>"} and the
 * mob effect {@code nirncraft:blessing_<id>}.
 */
public enum Divine {
    AKATOSH(0xE8B040),
    ARKAY(0xC8C8B0),
    DIBELLA(0xF08AB0),
    JULIANOS(0x5A8CFF),
    KYNARETH(0x7AD36A),
    MARA(0xE05A6A),
    STENDARR(0xF0E6A0),
    TALOS(0xD0A030),
    ZENITHAR(0xC08040);

    public static final Divine[] VALUES = values();

    public final int color;

    Divine(int color) {
        this.color = color;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Block id of this Divine's shrine. */
    public String shrineId() {
        return "shrine_of_" + id();
    }

    public String buffFlag() {
        return "blessing_" + id();
    }

    public Component displayName() {
        return Component.translatable("survival.nirncraft.divine." + id());
    }

    public static Divine byId(String id) {
        for (Divine d : VALUES) if (d.id().equals(id)) return d;
        return null;
    }
}
