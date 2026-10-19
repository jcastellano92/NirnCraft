package com.nirncraft.tamriel.settlement;

import java.util.Locale;

/**
 * Building cultures of Nirn. A region's {@code culture} string (docs/TAMRIEL.md section 5) maps onto one of these;
 * the culture picks the kit (block palettes and building shapes) in {@link com.nirncraft.tamriel.settlement.kit.Kits}.
 */
public enum Culture {
    NORD, IMPERIAL, DUNMER, ARGONIAN, KHAJIIT, BOSMER, ALTMER, REDGUARD, BRETON, ORC, AKAVIRI, ATMORAN, YOKUDAN, MAORMER;

    public static final Culture[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Maps region/place culture strings (and a few aliases) onto a culture; unknown strings become {@link #NORD}. */
    public static Culture of(String s) {
        if (s == null) return NORD;
        String k = s.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "imperial", "colovian", "nibenese", "cyrodiil", "cyrodiilic" -> IMPERIAL;
            case "dunmer", "dark_elf", "hlaalu", "redoran", "telvanni", "velothi", "ashlander", "indoril", "dres" -> DUNMER;
            case "argonian", "saxhleel", "black_marsh", "lilmoth" -> ARGONIAN;
            case "khajiit", "elsweyr" -> KHAJIIT;
            case "bosmer", "wood_elf", "valenwood" -> BOSMER;
            case "altmer", "high_elf", "summerset", "aldmer" -> ALTMER;
            case "redguard", "hammerfell", "crown", "forebear" -> REDGUARD;
            case "breton", "high_rock", "reachman", "forsworn" -> BRETON;
            case "orc", "orsimer", "orcish" -> ORC;
            case "akaviri", "tsaesci", "kamal", "tang_mo", "ka_po_tun", "akavir" -> AKAVIRI;
            case "atmoran", "skaal", "atmora" -> ATMORAN;
            case "yokudan", "yokuda" -> YOKUDAN;
            case "maormer", "pyandonean", "sea_elf", "thras", "sload" -> MAORMER;
            default -> NORD;
        };
    }

    /** Dunmer house style from a region or culture hint: 0 Hlaalu, 1 Redoran, 2 Telvanni, 3 Velothi (ash stone). */
    public static int dunmerStyle(String hint) {
        if (hint == null) return 0;
        String k = hint.toLowerCase(Locale.ROOT);
        if (k.contains("telvanni") || k.contains("sadrith") || k.contains("mithryn") || k.contains("azura") || k.contains("sheogorad")) return 2;
        if (k.contains("redoran") || k.contains("aldruhn") || k.contains("raven_rock") || k.contains("solstheim")
                || k.contains("ashlands") || k.contains("west_gash") || k.contains("red_mountain")) return 1;
        if (k.contains("velothi") || k.contains("necrom") || k.contains("molag") || k.contains("vivec") || k.contains("mournhold")
                || k.contains("deshaan") || k.contains("stonefalls")) return 3;
        return 0;
    }

    public static Culture byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : NORD;
    }
}
