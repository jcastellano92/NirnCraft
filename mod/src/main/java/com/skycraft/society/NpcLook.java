package com.skycraft.society;

import net.minecraft.util.RandomSource;

/**
 * The appearance of a townsperson, packed into one synced int: race, sex, skin tone, hair style and colour, beard,
 * outfit and its colour variant, and whether it is a child. The client composites the matching texture layers once
 * per distinct look ({@code society.client.NpcSkins}); -1 means "not chosen yet" (old role skins are used).
 *
 * <pre>bits 0-3 race | 4 female | 5-6 tone | 7-10 hair | 11-14 hair colour | 15-17 beard | 18-23 outfit | 24-25 variant | 26 child</pre>
 */
public final class NpcLook {
    public static final int UNSET = -1;
    public static final int TONES_PER_RACE = 4;

    // hair styles (texture npc/hair/<n>.png)
    public static final int BALD = 0, SHORT = 1, SHOULDER = 2, LONG = 3, BRAIDS = 4, PONYTAIL = 5, MOHAWK = 6, BUN = 7,
            SHAVED = 8, WILD = 9, KHAJIIT_MANE = 10, KHAJIIT_BRAIDS = 11, ARGONIAN_CREST = 12, ARGONIAN_FRILLS = 13,
            ARGONIAN_SPINES = 14, NO_HAIR = 15;
    // beards (texture npc/beard/<n>.png)
    public static final int NO_BEARD = 0, STUBBLE = 1, FULL = 2, BRAIDED = 3, GOATEE = 4, MOUSTACHE = 5, SHORT_BEARD = 6;

    /** Hair colours (RGB), indexed by the hair colour field. 12-15 are Argonian crest colours. */
    public static final int[] HAIR_COLORS = {
            0x282220, 0x3E2A1C, 0x5E3E24, 0x8A6440, 0xA8844E, 0xCCA866, 0xE0D4A8, 0x8A3418,
            0x6E3420, 0xB0582A, 0x9A968E, 0xDCD8D0, 0xA02828, 0xC8641E, 0x6A3A8A, 0x2A7A7A
    };
    /** Skin / fur / scale tones (RGB) per race and tone; mirrors NPC_TONES in tools/textures/society.py. */
    public static final int[][] TONES = {
            {0xEDD3C0, 0xE3BFA3, 0xD6AD8E, 0xC79C7E}, // nord
            {0xDEB492, 0xCDA07C, 0xBB8C66, 0xA87B58}, // imperial
            {0xF2D8C4, 0xE8C6AC, 0xDCB699, 0xCFA588}, // breton
            {0xA46E4A, 0x925E3C, 0x7E4E32, 0x6A4029}, // redguard
            {0xEAD489, 0xDCC47A, 0xCFB46A, 0xC2A55E}, // altmer
            {0xC8A070, 0xB88F60, 0xA68052, 0x937047}, // bosmer
            {0x8E979C, 0x7D878E, 0x6C767D, 0x8F8582}, // dunmer
            {0xC9A46C, 0x9E7044, 0xDDD0B4, 0x8C7D6E}, // khajiit
            {0x5F7F4C, 0x7C6D44, 0x4D6F7C, 0x8A5C3C}, // argonian
            {0x84A05E, 0x72904F, 0x8E9E66, 0x627E48}  // orc
    };

    private NpcLook() {}

    public static int pack(NpcNames.Race race, boolean female, int tone, int hair, int hairColor, int beard, Outfit outfit,
                           int variant, boolean child) {
        return (race.ordinal() & 15) | (female ? 1 << 4 : 0) | ((tone & 3) << 5) | ((hair & 15) << 7) | ((hairColor & 15) << 11)
                | ((beard & 7) << 15) | ((outfit.ordinal() & 63) << 18) | ((variant & 3) << 24) | (child ? 1 << 26 : 0);
    }

    public static NpcNames.Race race(int look) {
        NpcNames.Race[] all = NpcNames.Race.values();
        return all[Math.min(all.length - 1, look & 15)];
    }

    public static boolean female(int look) {
        return (look >> 4 & 1) == 1;
    }

    public static int tone(int look) {
        return look >> 5 & 3;
    }

    public static int hair(int look) {
        return look >> 7 & 15;
    }

    public static int hairColor(int look) {
        return look >> 11 & 15;
    }

    public static int beard(int look) {
        return Math.min(SHORT_BEARD, look >> 15 & 7);
    }

    public static Outfit outfit(int look) {
        return Outfit.byOrdinal(look >> 18 & 63);
    }

    public static int variant(int look) {
        return Math.min(Outfit.VARIANTS - 1, look >> 24 & 3);
    }

    public static boolean child(int look) {
        return (look >> 26 & 1) == 1;
    }

    public static int toneColor(int look) {
        return TONES[race(look).ordinal()][tone(look)];
    }

    public static int withOutfit(int look, Outfit outfit, int variant) {
        return (look & ~(63 << 18) & ~(3 << 24)) | ((outfit.ordinal() & 63) << 18) | ((variant & 3) << 24);
    }

    // ------------------------------------------------------------------ generation

    /** A random appearance fitting the race and sex. */
    public static int random(NpcNames.Race race, boolean female, boolean child, Outfit outfit, RandomSource r) {
        int tone = r.nextInt(TONES_PER_RACE);
        boolean elder = !child && r.nextInt(8) == 0;
        int hair = hairStyle(race, female, child, elder, r);
        int color = hairColor(race, elder, r);
        int beard = female || child ? NO_BEARD : beard(race, r);
        return pack(race, female, tone, hair, color, beard, outfit, r.nextInt(Outfit.VARIANTS), child);
    }

    private static int hairStyle(NpcNames.Race race, boolean female, boolean child, boolean elder, RandomSource r) {
        switch (race) {
            case KHAJIIT -> {
                return pick(r, KHAJIIT_MANE, KHAJIIT_MANE, KHAJIIT_BRAIDS, NO_HAIR);
            }
            case ARGONIAN -> {
                return pick(r, ARGONIAN_CREST, ARGONIAN_FRILLS, ARGONIAN_SPINES);
            }
            default -> {
            }
        }
        if (child) return female ? pick(r, LONG, BRAIDS, PONYTAIL, SHOULDER) : pick(r, SHORT, SHORT, SHOULDER, WILD);
        if (female) {
            return switch (race) {
                case NORD -> pick(r, BRAIDS, BRAIDS, LONG, PONYTAIL, SHOULDER, WILD);
                case REDGUARD -> pick(r, BUN, PONYTAIL, SHAVED, LONG);
                case ORC -> pick(r, MOHAWK, PONYTAIL, BRAIDS, WILD);
                case ALTMER -> pick(r, BUN, LONG, PONYTAIL);
                case DUNMER -> pick(r, SHOULDER, BUN, SHAVED, PONYTAIL);
                default -> pick(r, LONG, BUN, PONYTAIL, SHOULDER, BRAIDS);
            };
        }
        if (elder && r.nextBoolean()) return BALD;
        return switch (race) {
            case NORD -> pick(r, SHOULDER, SHOULDER, LONG, BRAIDS, WILD, SHORT, PONYTAIL);
            case REDGUARD -> pick(r, SHAVED, MOHAWK, SHORT, BALD);
            case ORC -> pick(r, MOHAWK, MOHAWK, BALD, PONYTAIL, SHAVED);
            case ALTMER -> pick(r, SHORT, PONYTAIL, SHOULDER, BALD);
            case BOSMER -> pick(r, SHAVED, SHORT, PONYTAIL, WILD);
            case DUNMER -> pick(r, SHORT, SHAVED, MOHAWK, PONYTAIL);
            default -> pick(r, SHORT, SHORT, SHOULDER, PONYTAIL, BALD);
        };
    }

    private static int hairColor(NpcNames.Race race, boolean elder, RandomSource r) {
        if (race == NpcNames.Race.ARGONIAN) return pick(r, 12, 13, 14, 15);
        if (elder && race != NpcNames.Race.KHAJIIT) return pick(r, 10, 11);
        return switch (race) {
            case NORD -> pick(r, 4, 5, 5, 6, 7, 9, 3, 2, 8);
            case IMPERIAL -> pick(r, 0, 1, 1, 2, 2, 3);
            case BRETON -> pick(r, 1, 2, 3, 7, 8, 4, 0);
            case REDGUARD -> pick(r, 0, 0, 1);
            case ALTMER -> pick(r, 5, 6, 4, 11, 3);
            case BOSMER -> pick(r, 0, 1, 2, 7, 8);
            case DUNMER -> pick(r, 0, 0, 1, 7, 8, 11);
            case ORC -> pick(r, 0, 0, 1, 10);
            case KHAJIIT -> pick(r, 0, 1, 2, 3, 10);
            default -> 2;
        };
    }

    private static int beard(NpcNames.Race race, RandomSource r) {
        int chance = switch (race) {
            case NORD -> 75;
            case IMPERIAL, BRETON -> 40;
            case REDGUARD, ORC -> 50;
            case DUNMER -> 30;
            case BOSMER -> 10;
            case ALTMER -> 5;
            default -> 0;
        };
        if (r.nextInt(100) >= chance) return NO_BEARD;
        return switch (race) {
            case NORD -> pick(r, FULL, FULL, BRAIDED, SHORT_BEARD, STUBBLE, MOUSTACHE);
            case ORC -> pick(r, BRAIDED, FULL, STUBBLE, GOATEE);
            case REDGUARD -> pick(r, GOATEE, SHORT_BEARD, MOUSTACHE, STUBBLE);
            case DUNMER, ALTMER, BOSMER -> pick(r, GOATEE, MOUSTACHE, STUBBLE);
            default -> pick(r, SHORT_BEARD, STUBBLE, GOATEE, MOUSTACHE, FULL);
        };
    }

    private static int pick(RandomSource r, int... options) {
        return options[r.nextInt(options.length)];
    }
}
