package com.nirncraft.society;

import net.minecraft.util.RandomSource;

/**
 * The appearance of a townsperson (or of a player: see {@code client.RaceSkins}), packed into one synced int: race,
 * sex, skin tone, hair style and colour, beard, outfit and its colour variant, whether it is a child, and the race's
 * cosmetic variant (Nibenese, Wood Orc, Cathay-raht, Naga...). The client composites the matching texture layers once
 * per distinct look ({@code society.client.NpcSkins}); -1 means "not chosen yet" (old role skins are used).
 *
 * <pre>bits 0-3 race (low) | 4 female | 5-6 tone | 7-10 hair | 11-14 hair colour | 15-17 beard | 18-23 outfit
 * | 24-25 outfit variant | 26 child | 27-28 race (high) | 29-30 race variant</pre>
 */
public final class NpcLook {
    public static final int UNSET = -1;
    public static final int TONES_PER_RACE = 4;
    public static final int MAX_VARIANTS = 4;

    // hair styles (texture npc/hair/<n>.png)
    public static final int BALD = 0, SHORT = 1, SHOULDER = 2, LONG = 3, BRAIDS = 4, PONYTAIL = 5, MOHAWK = 6, BUN = 7,
            SHAVED = 8, WILD = 9, KHAJIIT_MANE = 10, KHAJIIT_BRAIDS = 11, ARGONIAN_CREST = 12, ARGONIAN_FRILLS = 13,
            ARGONIAN_SPINES = 14, NO_HAIR = 15;
    public static final int HAIR_STYLES = 16;
    // beards (texture npc/beard/<n>.png)
    public static final int NO_BEARD = 0, STUBBLE = 1, FULL = 2, BRAIDED = 3, GOATEE = 4, MOUSTACHE = 5, SHORT_BEARD = 6;
    public static final int BEARDS = 7;

    /** Hair colours (RGB), indexed by the hair colour field. 12-15 are Argonian crest colours. */
    public static final int[] HAIR_COLORS = {
            0x282220, 0x3E2A1C, 0x5E3E24, 0x8A6440, 0xA8844E, 0xCCA866, 0xE0D4A8, 0x8A3418,
            0x6E3420, 0xB0582A, 0x9A968E, 0xDCD8D0, 0xA02828, 0xC8641E, 0x6A3A8A, 0x2A7A7A
    };
    /**
     * Skin / fur / scale tones (RGB) per race and tone, in {@link NpcNames.Race} order; mirrors NPC_TONES in
     * tools/textures/society.py and NEW_TONES in tools/textures/races.py.
     */
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
            {0x84A05E, 0x72904F, 0x8E9E66, 0x627E48}, // orc
            {0xB8D0D4, 0x9CC0C8, 0x8AA8C0, 0xC4D8CC}, // maormer
            {0xE6C4A8, 0xD8B090, 0xC89C7C, 0xB88A6A}, // reachman
            {0xD8B48C, 0xC8A07A, 0xB88C64, 0xE0C8A4}, // ohmes-raht
            {0xC8682C, 0xA85A2C, 0x8A6A4A, 0xD89A58}, // lilmothiit
            {0x4A3A30, 0x5C4A3A, 0x3A3432, 0x6E5E4E}, // imga
            {0xC8A048, 0xA8463A, 0x4A5A3E, 0x7A8A50}, // tsaesci
            {0xD8822A, 0xC06A22, 0xE8DCC8, 0xB87A3A}, // ka po' tun
            {0x8A6A4A, 0x6A5646, 0xA8906A, 0x5A4A44}, // tang mo
            {0xF4E4DC, 0xECD8CC, 0xE0CCBC, 0xD6C2B2}, // atmoran
            {0xEEF0F4, 0xE2E8F0, 0xD6DEE8, 0xE8ECEC}, // snow elf
            {0xD4AC8C, 0xC49C7C, 0xB48C6C, 0xA47C5E}, // dwemer
            {0xECE0C4, 0xE0D2B2, 0xD4C4A0, 0xF2EAD6}, // ayleid
            {0xC8CCD2, 0xB2B8C0, 0x9CA2AC, 0xDCDEE2}  // kothringi
    };

    private NpcLook() {}

    /** Tones of a race's cosmetic variant (null = the race's own); mirrors VARIANT_TONES in races.py. */
    private static int[] variantTones(NpcNames.Race race, int variant) {
        if (variant <= 0) return null;
        return switch (race) {
            case IMPERIAL -> new int[]{0xB88658, 0xA87650, 0x98684A, 0x886040};   // Nibenese
            case ORC -> new int[]{0x98A066, 0x86905A, 0xA6A878, 0x74804C};        // Wood Orc
            case KHAJIIT -> new int[]{0xB08850, 0x7A5A3A, 0xD8C8A8, 0x5A5048};    // Cathay-raht
            case ARGONIAN -> variant == 1 ? new int[]{0x3E5034, 0x34402E, 0x4A4E38, 0x2E3436}  // Naga
                    : new int[]{0x8AAA86, 0x7A9CAE, 0xA4AC7A, 0x92A49C};          // Agaceph
            default -> null;
        };
    }

    public static int pack(NpcNames.Race race, boolean female, int tone, int hair, int hairColor, int beard, Outfit outfit,
                           int variant, boolean child) {
        int r = race.ordinal();
        return (r & 15) | (female ? 1 << 4 : 0) | ((tone & 3) << 5) | ((hair & 15) << 7) | ((hairColor & 15) << 11)
                | ((beard & 7) << 15) | ((outfit.ordinal() & 63) << 18) | ((variant & 3) << 24) | (child ? 1 << 26 : 0)
                | ((r >> 4 & 3) << 27);
    }

    /** {@link #pack} plus the race's cosmetic variant. */
    public static int pack(NpcNames.Race race, int raceVariant, boolean female, int tone, int hair, int hairColor, int beard,
                           Outfit outfit, int variant, boolean child) {
        return withRaceVariant(pack(race, female, tone, hair, hairColor, beard, outfit, variant, child), raceVariant);
    }

    public static NpcNames.Race race(int look) {
        NpcNames.Race[] all = NpcNames.Race.VALUES;
        int r = (look & 15) | (look >> 27 & 3) << 4;
        return all[Math.min(all.length - 1, r)];
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

    /** The race's cosmetic variant (0 = the common look), clamped to the variants the race has. */
    public static int raceVariant(int look) {
        int v = look >> 29 & 3;
        return v < race(look).core().variantCount() ? v : 0;
    }

    public static int withRaceVariant(int look, int raceVariant) {
        return (look & ~(3 << 29)) | ((raceVariant & 3) << 29);
    }

    public static int toneColor(int look) {
        int[] v = variantTones(race(look), raceVariant(look));
        return v != null ? v[tone(look)] : TONES[race(look).ordinal()][tone(look)];
    }

    public static int withOutfit(int look, Outfit outfit, int variant) {
        return (look & ~(63 << 18) & ~(3 << 24)) | ((outfit.ordinal() & 63) << 18) | ((variant & 3) << 24);
    }

    /** Body texture of a look ({@code npc/body/<key>.png}): race, variant, sex and tone. */
    public static String bodyKey(int look) {
        String race = race(look).name().toLowerCase(java.util.Locale.ROOT);
        int v = raceVariant(look);
        return race + (v > 0 ? "_v" + v : "") + "_" + (female(look) ? "f" : "m") + "_" + tone(look);
    }

    /** Render scale of the body: race, variant (Cathay-raht and Naga are bigger, Wood Orcs and Agaceph slighter), sex. */
    public static float bodyScale(int look) {
        NpcNames.Race race = race(look);
        float s = race.core().scale;
        int v = raceVariant(look);
        if (v > 0) {
            switch (race) {
                case KHAJIIT, ARGONIAN -> s *= v == 1 ? 1.06f : 0.95f;
                case ORC -> s *= 0.95f;
                default -> {
                }
            }
        }
        if (female(look)) s *= 0.97f;
        return s;
    }

    // ------------------------------------------------------------------ players

    /**
     * A look a player may wear: forced to {@code race}, adult, with the given outfit choice clamped to the player
     * outfits, every field in range. Used to validate the character creator's packet.
     */
    public static int sanitizePlayer(int look, com.nirncraft.core.Race race) {
        NpcNames.Race r = NpcNames.Race.of(race);
        int variant = (look >> 29 & 3) % race.variantCount();
        Outfit outfit = outfit(look);
        boolean allowed = false;
        for (Outfit o : playerOutfits(r)) allowed |= o == outfit;
        if (!allowed) outfit = Outfit.cultural(r);
        return pack(r, variant, female(look), tone(look), hair(look), hairColor(look), beard(look), outfit, variant(look), false);
    }

    /** The clothes a new character may start in: their homeland's, a traveller's, a farmhand's or rags. */
    public static Outfit[] playerOutfits(NpcNames.Race race) {
        return new Outfit[]{Outfit.cultural(race), Outfit.ADVENTURER, Outfit.FARM, Outfit.FINE, Outfit.BEGGAR};
    }

    /** A typical look for a player of {@code race} who never chose one (old saves), seeded so it stays the same. */
    public static int defaultFor(com.nirncraft.core.Race race, long seed) {
        NpcNames.Race r = NpcNames.Race.of(race);
        RandomSource rnd = RandomSource.create(seed);
        int look = random(r, false, false, Outfit.cultural(r), rnd);
        return withRaceVariant(look, 0);
    }

    // ------------------------------------------------------------------ generation

    /** A random appearance fitting the race and sex. */
    public static int random(NpcNames.Race race, boolean female, boolean child, Outfit outfit, RandomSource r) {
        int tone = r.nextInt(TONES_PER_RACE);
        boolean elder = !child && r.nextInt(8) == 0;
        int hair = hairStyle(race, female, child, elder, r);
        int color = hairColor(race, elder, r);
        int beard = female || child ? NO_BEARD : beard(race, r);
        return pack(race, raceVariant(race, r), female, tone, hair, color, beard, outfit, r.nextInt(Outfit.VARIANTS), child);
    }

    /** How common each sub-people is among a race's townsfolk. */
    private static int raceVariant(NpcNames.Race race, RandomSource r) {
        int roll = r.nextInt(100);
        return switch (race) {
            case IMPERIAL -> roll < 35 ? 1 : 0;
            case ORC -> roll < 20 ? 1 : 0;
            case KHAJIIT -> roll < 30 ? 1 : 0;
            case ARGONIAN -> roll < 15 ? 1 : roll < 30 ? 2 : 0;
            default -> 0;
        };
    }

    /** Hair styles that suit a race (for NPCs and for the character creator's choices). */
    public static int[] hairStyles(NpcNames.Race race, boolean female) {
        return switch (race) {
            case KHAJIIT -> new int[]{KHAJIIT_MANE, KHAJIIT_BRAIDS, NO_HAIR};
            case ARGONIAN -> new int[]{ARGONIAN_CREST, ARGONIAN_FRILLS, ARGONIAN_SPINES};
            case TSAESCI -> new int[]{NO_HAIR};
            case KA_PO_TUN -> new int[]{KHAJIIT_MANE, NO_HAIR, KHAJIIT_BRAIDS};
            case LILMOTHIIT -> new int[]{NO_HAIR, KHAJIIT_MANE, SHORT};
            case IMGA -> new int[]{NO_HAIR, WILD, SHAVED};
            case TANG_MO -> new int[]{NO_HAIR, SHORT, WILD, MOHAWK};
            case KOTHRINGI -> new int[]{BALD, SHAVED, SHORT, NO_HAIR};
            default -> female ? new int[]{LONG, BRAIDS, PONYTAIL, SHOULDER, BUN, WILD, SHAVED, MOHAWK, SHORT, BALD}
                    : new int[]{SHORT, SHOULDER, LONG, BRAIDS, PONYTAIL, MOHAWK, SHAVED, WILD, BUN, BALD};
        };
    }

    private static int hairStyle(NpcNames.Race race, boolean female, boolean child, boolean elder, RandomSource r) {
        switch (race) {
            case KHAJIIT -> {
                return pick(r, KHAJIIT_MANE, KHAJIIT_MANE, KHAJIIT_BRAIDS, NO_HAIR);
            }
            case ARGONIAN -> {
                return pick(r, ARGONIAN_CREST, ARGONIAN_FRILLS, ARGONIAN_SPINES);
            }
            case TSAESCI, KA_PO_TUN, LILMOTHIIT, IMGA, TANG_MO, KOTHRINGI -> {
                int[] styles = hairStyles(race, female);
                return styles[r.nextInt(styles.length)];
            }
            default -> {
            }
        }
        if (child) return female ? pick(r, LONG, BRAIDS, PONYTAIL, SHOULDER) : pick(r, SHORT, SHORT, SHOULDER, WILD);
        if (female) {
            return switch (race) {
                case NORD, ATMORAN -> pick(r, BRAIDS, BRAIDS, LONG, PONYTAIL, SHOULDER, WILD);
                case REDGUARD -> pick(r, BUN, PONYTAIL, SHAVED, LONG);
                case ORC -> pick(r, MOHAWK, PONYTAIL, BRAIDS, WILD);
                case ALTMER, AYLEID, FALMER, MAORMER -> pick(r, BUN, LONG, PONYTAIL);
                case DUNMER -> pick(r, SHOULDER, BUN, SHAVED, PONYTAIL);
                case REACHMAN -> pick(r, WILD, BRAIDS, LONG, MOHAWK);
                case DWEMER -> pick(r, BRAIDS, BUN, LONG);
                default -> pick(r, LONG, BUN, PONYTAIL, SHOULDER, BRAIDS);
            };
        }
        if (elder && r.nextBoolean()) return BALD;
        return switch (race) {
            case NORD, ATMORAN -> pick(r, SHOULDER, SHOULDER, LONG, BRAIDS, WILD, SHORT, PONYTAIL);
            case REDGUARD -> pick(r, SHAVED, MOHAWK, SHORT, BALD);
            case ORC -> pick(r, MOHAWK, MOHAWK, BALD, PONYTAIL, SHAVED);
            case ALTMER, AYLEID, FALMER -> pick(r, SHORT, PONYTAIL, SHOULDER, BALD, LONG);
            case MAORMER -> pick(r, LONG, PONYTAIL, SHAVED, SHORT);
            case BOSMER -> pick(r, SHAVED, SHORT, PONYTAIL, WILD);
            case DUNMER -> pick(r, SHORT, SHAVED, MOHAWK, PONYTAIL);
            case REACHMAN -> pick(r, WILD, WILD, BRAIDS, MOHAWK, LONG);
            case DWEMER -> pick(r, BRAIDS, LONG, SHORT, BALD);
            default -> pick(r, SHORT, SHORT, SHOULDER, PONYTAIL, BALD);
        };
    }

    /** Hair colours that suit a race (indices into {@link #HAIR_COLORS}). */
    public static int[] hairColors(NpcNames.Race race) {
        return switch (race) {
            case ARGONIAN -> new int[]{12, 13, 14, 15};
            case NORD -> new int[]{4, 5, 6, 7, 9, 3, 2, 8, 10, 11};
            case IMPERIAL -> new int[]{0, 1, 2, 3, 10, 11};
            case BRETON -> new int[]{1, 2, 3, 7, 8, 4, 0, 10, 11};
            case REDGUARD -> new int[]{0, 1, 10, 11};
            case ALTMER -> new int[]{5, 6, 4, 11, 3, 10};
            case BOSMER -> new int[]{0, 1, 2, 7, 8, 10};
            case DUNMER -> new int[]{0, 1, 7, 8, 11, 10};
            case ORC -> new int[]{0, 1, 10, 11};
            case KHAJIIT, OHMES_RAHT -> new int[]{0, 1, 2, 3, 10, 7};
            case MAORMER -> new int[]{11, 0, 15, 10, 14};
            case REACHMAN -> new int[]{0, 1, 2, 7, 8, 10};
            case LILMOTHIIT -> new int[]{11, 6, 9, 7};
            case IMGA -> new int[]{0, 1, 10};
            case TSAESCI -> new int[]{12, 13, 15};
            case KA_PO_TUN -> new int[]{6, 11, 0, 9};
            case TANG_MO -> new int[]{1, 2, 3, 10};
            case ATMORAN -> new int[]{5, 6, 4, 9, 11, 7};
            case FALMER -> new int[]{11, 6, 10};
            case DWEMER -> new int[]{0, 1, 2, 7, 8, 10};
            case AYLEID -> new int[]{11, 6, 5, 0};
            case KOTHRINGI -> new int[]{0, 10, 11};
        };
    }

    private static int hairColor(NpcNames.Race race, boolean elder, RandomSource r) {
        if (race == NpcNames.Race.ARGONIAN) return pick(r, 12, 13, 14, 15);
        if (elder && race != NpcNames.Race.KHAJIIT && race != NpcNames.Race.TSAESCI) return pick(r, 10, 11);
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
            default -> {
                int[] colors = hairColors(race);
                yield colors[r.nextInt(colors.length)];
            }
        };
    }

    /** Whether men of the race grow beards at all. */
    public static boolean bearded(NpcNames.Race race) {
        return switch (race) {
            case KHAJIIT, ARGONIAN, LILMOTHIIT, IMGA, TSAESCI, KA_PO_TUN, TANG_MO, KOTHRINGI -> false;
            default -> true;
        };
    }

    private static int beard(NpcNames.Race race, RandomSource r) {
        int chance = switch (race) {
            case NORD -> 75;
            case ATMORAN -> 85;
            case DWEMER -> 80;
            case REACHMAN -> 60;
            case IMPERIAL, BRETON, OHMES_RAHT -> 40;
            case REDGUARD, ORC -> 50;
            case DUNMER -> 30;
            case BOSMER -> 10;
            case ALTMER, AYLEID, FALMER, MAORMER -> 5;
            default -> 0;
        };
        if (r.nextInt(100) >= chance) return NO_BEARD;
        return switch (race) {
            case NORD, ATMORAN -> pick(r, FULL, FULL, BRAIDED, SHORT_BEARD, STUBBLE, MOUSTACHE);
            case DWEMER -> pick(r, BRAIDED, BRAIDED, FULL);
            case ORC -> pick(r, BRAIDED, FULL, STUBBLE, GOATEE);
            case REDGUARD -> pick(r, GOATEE, SHORT_BEARD, MOUSTACHE, STUBBLE);
            case REACHMAN -> pick(r, FULL, BRAIDED, STUBBLE);
            case DUNMER, ALTMER, BOSMER, AYLEID, FALMER, MAORMER -> pick(r, GOATEE, MOUSTACHE, STUBBLE);
            default -> pick(r, SHORT_BEARD, STUBBLE, GOATEE, MOUSTACHE, FULL);
        };
    }

    private static int pick(RandomSource r, int... options) {
        return options[r.nextInt(options.length)];
    }
}
