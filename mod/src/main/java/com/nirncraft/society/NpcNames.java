package com.nirncraft.society;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Per-race name generators for the people of Tamriel, built from syllables so a hold full of NPCs rarely repeats a
 * name: hard Nordic names, Latin-like Imperial ones, French-flavoured Breton ones, Redguard names from the desert,
 * long flowing Altmer names, short Bosmer ones, sibilant Dunmer ones, Orc names with gro-/gra- parentage, Khajiit
 * names with their rank prefixes (Ri', Ra', J', M', Dar, Ja...), and Argonian deed names ("Walks-In-Shadow" style)
 * or Jel names. Every combination is generated; there is no list of game characters. Family names follow each
 * culture: Nord clan names, Imperial gens, Breton houses, Dunmer family names and Orc parentage. The other folk have
 * their own sounds: lilting Sea Elf names, Reach names, hissing Tsaesci ones, short Ka Po' Tun and Tang Mo names,
 * old Atmoran ones, Snow Elf and Ayleid names, the consonant clusters of the Dwemer and soft Kothringi names.
 */
public final class NpcNames {
    public enum Race {
        NORD, IMPERIAL, BRETON, REDGUARD, ALTMER, BOSMER, DUNMER, KHAJIIT, ARGONIAN, ORC,
        // the other playable folk (core.Race names); NpcLook packs up to 64
        MAORMER, REACHMAN, OHMES_RAHT, LILMOTHIIT, IMGA, TSAESCI, KA_PO_TUN, TANG_MO, ATMORAN, FALMER, DWEMER, AYLEID, KOTHRINGI;

        public static final Race[] VALUES = values();

        /** The playable race this NPC race matches (core.Race uses ORSIMER for Orcs). */
        public com.nirncraft.core.Race core() {
            return this == ORC ? com.nirncraft.core.Race.ORSIMER : com.nirncraft.core.Race.valueOf(name());
        }

        public static Race of(com.nirncraft.core.Race race) {
            return race == com.nirncraft.core.Race.ORSIMER ? ORC : valueOf(race.name());
        }
    }

    private static final String[][] MALE_PRE = {
            {"Bj", "Ulf", "Hro", "Sig", "Tor", "Ing", "Vig", "Gun", "Hal", "Ey", "As", "Bal", "Ar", "Gor", "Sv", "Kjel", "Hjal", "Ste",
                    "Rag", "Ulv", "Thor", "Frod", "Grim", "Hrag", "Jor", "Kod", "Orm", "Rol", "Skar", "Teg", "Vald", "Yng", "Brod", "Dag"},
            {"Luc", "Mar", "Gai", "Ser", "Ant", "Cass", "Vel", "Quin", "Tit", "Dec", "Fla", "Aul", "Corn", "Hadr", "Sept", "Oct", "Val",
                    "Rufin", "Marc", "Dom", "Sab", "Tull", "Cael", "Vit", "Pont"},
            {"Bren", "Mau", "Ger", "Cal", "Dan", "Ali", "Mel", "Ry", "Ad", "Em", "Jor", "Ver", "Gast", "Hen", "Lou", "Pier", "Rob", "Thib",
                    "Andr", "Bast", "Clem", "Fran"},
            {"Am", "Kem", "Saa", "Naz", "Ha", "Ja", "Ra", "Ishr", "Kay", "Cyr", "Bah", "Dar", "Far", "Jaf", "Kas", "Mir", "Rus", "Sha",
                    "Tah", "Yas", "Zab"},
            {"Ond", "Anc", "Taa", "Ael", "Rul", "Ner", "Cal", "Ven", "Ery", "Lar", "Sar", "Tel", "Hal", "Im", "Ol", "Quel", "Fin"},
            {"Faen", "Gwi", "Elr", "Ber", "Ind", "Nir", "Ath", "Gle", "Ang", "Cor", "Hab", "Mal", "Ren", "Thel", "Uld"},
            {"Ral", "Bru", "Dra", "Nil", "Tel", "Ad", "Ser", "Gol", "Fal", "Ath", "Ald", "Bra", "Dav", "Fer", "Gil", "Mal", "Neth",
                    "Rav", "Sad", "Tir", "Uvi", "Vel"},
            {"Ahk", "Bas", "Dro", "Kha", "Mo", "Sha", "Zah", "Jir", "Kir", "Nam", "Ush", "Vas", "Zaf", "Rah", "Tsa"},
            {"Dee", "Kee", "Mad", "Wu", "Nee", "Tee", "Sha", "Gul", "Ha", "Jee", "Mee", "Ok", "Ru", "Ska", "Va", "Xu"},
            {"Gra", "Mog", "Ghor", "Lob", "Dush", "Yam", "Bor", "Ugr", "Mau", "Shag", "Gash", "Kharz", "Lurg", "Nar", "Ogr", "Ruk",
                    "Tug", "Urz", "Grub", "Dorg"},
            // the races beyond Skyrim's ten (NpcNames.Race order)
            {"Ner", "Sael", "Thal", "Orv", "Mael", "Pyan", "Ilv", "Sor", "Vael", "Cyr", "Tsel", "Ul"},
            {"Mad", "Ken", "Uri", "Dru", "Bor", "Cael", "Fen", "Ard", "Mor", "Rhy", "Tol", "Bra", "Gar", "Ivr"},
            {"Ahn", "Bir", "Dar", "Faz", "Jah", "Kir", "Mur", "Nar", "Raz", "Sah", "Tah", "Zir"},
            {"Ki", "Ree", "Taa", "Vix", "Rus", "Fen", "Yip", "Sko", "Ta", "Ruk"},
            {"Mo", "Bu", "Gro", "Hu", "Oom", "Mu", "Bon", "Gu", "Ku", "Do"},
            {"Ssa", "Tsa", "Vas", "Zha", "Sar", "Kas", "Nir", "Ish", "Shal", "Thes", "Ves"},
            {"Tor", "Kha", "Rag", "Pao", "Rao", "Tun", "Gro", "Shan", "Kao", "Bao"},
            {"Ming", "Ta", "Ho", "Bo", "Lu", "Chi", "Mo", "Pi", "Tu", "Wo"},
            {"Yng", "Hjor", "Thun", "Grun", "Skj", "Vul", "Hrod", "Sten", "Ulr", "Bor", "Kaj", "Ask"},
            {"Gel", "Ael", "Vyr", "Ilu", "Cael", "Syr", "Aur", "Ely", "Thal", "Nym"},
            {"Bth", "Mz", "Ny", "Kag", "Dum", "Ach", "Ars", "Chz", "Dzr", "Rkh", "Nch", "Sth", "Az", "Tch", "Yhn"},
            {"Ume", "Nen", "Anu", "Hes", "Var", "Cel", "Mal", "Ayl", "Vin", "Fan", "Ner"},
            {"Tes", "Ko", "Ri", "Sa", "Mol", "Vo", "Ul", "Pe", "Xu", "Ha"}
    };
    private static final String[][] MALE_SUF = {
            {"orn", "ric", "gar", "mund", "ulf", "ald", "vald", "ir", "jof", "ar", "nir", "vard", "olf", "en", "geir", "bjorn", "rek",
                    "stein", "dar", "ulfr", "ik", "ard", "rod"},
            {"ius", "an", "us", "or", "ian", "o", "ilius", "eus", "ianus", "ellus", "inus", "atus"},
            {"uin", "rice", "ard", "ixte", "on", "aine", "bry", "ec", "ert", "ault", "ier", "aud", "ois", "el"},
            {"ren", "ir", "ad", "aan", "zir", "had", "ul", "im", "eem", "ar", "ib", "oud", "us"},
            {"olemar", "ano", "indil", "dil", "aril", "celin", "dore", "ndil", "ion", "inwe", "amir", "orin"},
            {"dal", "lin", "oth", "ewen", "ion", "ar", "ith", "ir", "andil", "ent"},
            {"is", "nus", "vas", "eth", "dyn", "ril", "oryn", "vys", "os", "ram", "alas", "elo", "ilu", "ur"},
            {"zargo", "saad", "dar", "jirr", "zhar", "kesh", "rash", "ajirr", "dirr", "hal", "jo", "ri", "zo"},
            {"leel", "rava", "esi", "tra", "zish", "lix", "teius", "jei", "deeh", "nuk", "sa", "tul"},
            {"kh", "bash", "ak", "gol", "nag", "uz", "rog", "ash", "ul", "gak", "ob", "dul", "ur"},
            {"orin", "nith", "eon", "ul", "andor", "ys", "emar", "iel", "oth", "aryn"},
            {"anach", "ric", "ag", "an", "ach", "wyn", "eth", "ard", "oc", "ir", "us"},
            {"ijo", "asi", "ahd", "ir", "ani", "esh", "um", "aaz", "orr"},
            {"tik", "rik", "sha", "fe", "kit", "rrin", "ta", "ix"},
            {"ndor", "rimbar", "anar", "mbel", "olas", "aril", "onwe", "ondil"},
            {"esh", "sith", "ark", "esk", "ith", "assa", "eth", "iir", "uss", "akk"},
            {"rak", "shan", "tun", "mau", "rao", "gar", "kai", "rok"},
            {"bo", "ki", "mo", "tan", "po", "lu", "hi", "ko"},
            {"ol", "gar", "vald", "mund", "vir", "ulf", "rek", "jorn", "hald", "nir"},
            {"ebor", "thas", "dil", "andor", "orin", "iel", "enar", "ath"},
            {"uand", "ahnch", "enac", "ac", "zan", "ulac", "athur", "rkar", "eft", "umar", "alft", "ard"},
            {"ril", "alin", "dil", "indel", "ondo", "emae", "ath", "arelm", "adel"},
            {"thrin", "rim", "sul", "osh", "ari", "neth", "um", "ok"}
    };
    private static final String[][] FEMALE_PRE = {
            {"Hil", "Sig", "Ing", "Ast", "Fri", "Gun", "Ra", "Ys", "Hul", "Bry", "Ey", "Tho", "Sva", "Ol", "Ag", "Lyd", "Jor", "Ald",
                    "Ber", "Dag", "Groa", "Hel", "Ked", "Ranv", "Solv", "Tyr", "Ulv", "Vil"},
            {"Cam", "Adri", "Liv", "Ser", "Vel", "Clau", "Jul", "Octa", "Ama", "Lu", "Aure", "Corn", "Fla", "Mar", "Sab", "Tert", "Val"},
            {"Mar", "Eli", "Ade", "Bri", "Ys", "Ca", "Gem", "Del", "Ane", "Bea", "Cel", "Gis", "Jeh", "Lis", "Odi", "Sol"},
            {"Sa", "Ne", "Aza", "Ka", "Ira", "Ra", "Na", "Ami", "Fa", "Ja", "Le", "Ma", "Ta", "Ze"},
            {"Ela", "Ary", "Faa", "Ilm", "Nel", "Ess", "Cir", "Ali", "Ear", "Ime", "Lan", "Sor", "Tau"},
            {"Ann", "Gwi", "Elv", "Ind", "Ari", "Fae", "Bel", "Mel", "Nim", "Sil"},
            {"Ery", "Dra", "Bra", "Ili", "Ade", "Sen", "Ves", "Ala", "Dre", "Gal", "Mer", "Nel", "Rel", "Til", "Uvo"},
            {"Ahn", "Ba", "Ka", "Ja", "Sha", "Za", "Ki", "Ma", "Ri", "Tsa", "Zu"},
            {"Dee", "Kee", "Shah", "Wu", "Nee", "Mee", "Ha", "Ja", "Ra", "Xe"},
            {"Gha", "Mor", "Yat", "Bor", "Sha", "Ur", "Lash", "Ag", "Bag", "Dul", "Gul", "Mur"},
            {"Sae", "Mira", "Ny", "Thae", "Ove", "Lys", "Ce", "Ula", "Isa"},
            {"Ner", "Bri", "Moi", "Ail", "Cei", "Dei", "Fia", "Gwe", "Rhia", "Sei"},
            {"Aji", "Ena", "Ilu", "Kesh", "Maji", "Nisa", "Ra", "Shaz", "Zi"},
            {"Vi", "Shee", "Kaa", "Ris", "Tii", "Fe", "Mia", "Lis"},
            {"Ma", "Bo", "Hu", "Oo", "Ga", "Mu", "Ru"},
            {"Iss", "Sesh", "Yss", "Vash", "Asi", "Zes", "Nis", "Shi"},
            {"Mei", "Rao", "Sha", "Tao", "Lin", "Kai", "Pai"},
            {"Mi", "Ta", "Lo", "Pi", "Ki", "Yu", "Su"},
            {"Hrod", "Ska", "Thy", "Vig", "Ylf", "Gyr", "Sig", "Run"},
            {"Ely", "Syl", "Aen", "Vae", "Ilsa", "Nym", "Caer", "Lir"},
            {"Ny", "Aka", "Mzu", "Bth", "Chra", "Az", "Dra", "Tyr"},
            {"Nen", "Lyn", "Mae", "Ana", "Rhel", "Ilm", "Sel", "Ner"},
            {"Sha", "Tee", "Ma", "Isa", "Ve", "Lu", "Pa"}
    };
    private static final String[][] FEMALE_SUF = {
            {"da", "rid", "run", "hild", "a", "ja", "dis", "fina", "olda", "nja", "ve", "ia", "unn", "gerd", "laug", "veig", "borg", "ny"},
            {"illa", "anne", "ia", "ina", "a", "ette", "ilia", "ena", "iana", "ella", "ura"},
            {"elle", "ane", "ette", "ise", "ina", "ra", "ma", "ine", "aude", "ienne", "oise"},
            {"ma", "ira", "adi", "isha", "ana", "ah", "ila", "ya", "ra", "ida"},
            {"nwe", "rie", "ime", "wen", "endil", "ara", "riel", "ane", "ilde"},
            {"ekke", "sa", "ewen", "ara", "inda", "ith", "ira", "eth"},
            {"sa", "ni", "vyn", "ra", "nde", "dra", "ila", "ne", "lyn", "si", "va"},
            {"rassa", "ini", "ressa", "sha", "dara", "ri", "jaa", "zura", "hirr", "ssa"},
            {"ja", "rava", "esi", "jeeta", "na", "rei", "teeh", "lei", "wa"},
            {"sha", "gul", "ak", "bura", "ga", "dush", "ra", "ob", "gash", "uk"},
            {"lia", "rys", "nae", "wyn", "ira", "thel", "ssa", "ene"},
            {"da", "ra", "nna", "dwen", "ach", "lis", "rae", "wen"},
            {"ara", "ni", "rri", "sha", "ya", "ji", "ssa"},
            {"ka", "sa", "rie", "ti", "xa", "na", "ri"},
            {"riel", "ndra", "lwe", "mbelle", "rinde", "nya"},
            {"ssa", "ith", "esha", "issa", "eth", "ira", "ais"},
            {"ra", "shi", "nai", "rae", "mei", "ka"},
            {"mi", "li", "ya", "po", "ni", "ka"},
            {"veig", "ja", "hild", "run", "dis", "borg", "lyn"},
            {"ewen", "ienne", "andra", "ira", "wyn", "eth", "ia"},
            {"ani", "ura", "ira", "elsa", "uli", "ana", "ari"},
            {"dhel", "ae", "ira", "yanwe", "wen", "ielle", "esse"},
            {"ri", "shi", "na", "lesh", "ithi", "ra"}
    };
    /** Khajiit rank/title prefixes (written with the name). */
    private static final String[] KHAJIIT_PREFIX = {"Ri'", "Ra'", "J'", "M'", "Dar", "Ja", "Do'", "Ma'", "S'", "Ri'", "Ra'", "J'"};
    /** Argonians are often called by what they did when they hatched. */
    private static final String[] ARGONIAN_VERBS = {"Swims", "Hides", "Walks", "Sees", "Reads", "Waits", "Sings", "Talks", "Climbs",
            "Dreams", "Counts", "Wades", "Sleeps", "Watches", "Listens", "Digs", "Bends", "Follows", "Carries", "Gathers", "Speaks"};
    private static final String[] ARGONIAN_NOUNS = {"In-Shadow", "The-Rain", "In-Reeds", "The-Mud", "Stars", "The-Tide", "In-Dusk",
            "Many-Roots", "Deep-Water", "The-Moons", "Old-Bones", "Two-Rivers", "Under-Ferns", "The-Wind", "Long-Grass", "Quiet-Stones",
            "Warm-Sand", "Green-Light", "Far-Shores", "The-Current"};

    // ------------------------------------------------------------------ family names
    private static final String[] NORD_CLAN_A = {"Ice", "Iron", "Frost", "Storm", "Snow", "Wolf", "Bear", "Elk", "Oak", "Ash",
            "Raven", "Horn", "Mead", "Shield", "Hammer", "Red", "Black", "White", "Gold", "Long", "Stone", "Thunder", "Hearth", "Fell"};
    private static final String[] NORD_CLAN_B = {"Hand", "Brow", "Heart", "Blood", "Song", "Strider", "Helm", "Hearth", "Tooth",
            "Beard", "Eye", "Arm", "Axe", "Watch", "Wake", "Runner", "Breaker", "Mantle", "Root", "Gale"};
    /** Clan names that belong to the game's own families; never generated. */
    private static final Set<String> RESERVED = Set.of("Battle-Born", "Gray-Mane", "Black-Briar", "Snow-Shod", "Shatter-Shield",
            "Stone-Fist", "Silver-Blood", "Cruel-Sea", "White-Hand", "Iron-Hand", "Red-Hand", "Storm-Blood");
    private static final String[] IMPERIAL_GENS_A = {"Aur", "Cass", "Flav", "Jul", "Marc", "Oct", "Sev", "Val", "Vic", "Corv", "Drus",
            "Hort", "Luc", "Petr", "Quint", "Sabin", "Tull", "Varr", "Ost", "Caecil", "Fabr", "Lent", "Memm", "Scaur"};
    private static final String[] IMPERIAL_GENS_B = {"ius", "ianus", "orius", "ellus", "inus", "atus", "ilius", "ennius", "onius"};
    private static final String[] BRETON_HOUSE_A = {"Ver", "Mont", "Beau", "Dur", "Lac", "Ger", "Mar", "Rou", "Sen", "Bel", "Cour",
            "Fon", "Gal", "Hau", "Lan", "Mor", "Sal", "Vau", "Del", "Char"};
    private static final String[] BRETON_HOUSE_B = {"ane", "eau", "ois", "ier", "elle", "ard", "on", "ette", "ville", "mont", "court",
            "fort", "lac", "rive", "champ", "vois"};
    private static final String[] DUNMER_FAMILY_A = {"Ar", "Dre", "Sad", "Uve", "Ra", "Tel", "Hle", "Mor", "Ser", "Ind", "Vel",
            "Ald", "Bal", "Gor", "Nil", "Oth", "Sar", "Ule"};
    private static final String[] DUNMER_FAMILY_B = {"valen", "loth", "rani", "las", "ethi", "aryon", "vani", "ndas", "dalen", "sario",
            "lothren", "nim", "veth", "dryn", "ram", "vys"};

    private NpcNames() {}

    /** The likely race for a role when no region is known (Thalmor are High Elves, Stormcloaks Nords...). */
    public static Race raceFor(NpcRole role, RandomSource r) {
        return switch (role) {
            case THALMOR -> Race.ALTMER;
            case STORMCLOAK_SOLDIER, JARL, HOUSECARL -> r.nextInt(8) == 0 ? Race.IMPERIAL : Race.NORD;
            case IMPERIAL_SOLDIER -> r.nextBoolean() ? Race.IMPERIAL : (r.nextInt(3) == 0 ? Race.REDGUARD : Race.NORD);
            case FORSWORN -> r.nextInt(3) == 0 ? Race.BRETON : Race.REACHMAN;
            case MAGE -> pick(r, Race.BRETON, Race.ALTMER, Race.DUNMER, Race.IMPERIAL, Race.NORD);
            case ASSASSIN -> pick(r, Race.DUNMER, Race.KHAJIIT, Race.IMPERIAL, Race.NORD, Race.REDGUARD);
            case THIEF -> pick(r, Race.KHAJIIT, Race.ARGONIAN, Race.BOSMER, Race.NORD, Race.IMPERIAL);
            case HUNTER -> pick(r, Race.BOSMER, Race.NORD, Race.NORD, Race.ORC);
            case CARAVAN_TRADER -> Race.KHAJIIT;
            default -> {
                int roll = r.nextInt(100);
                if (roll < 55) yield Race.NORD;
                if (roll < 66) yield Race.IMPERIAL;
                if (roll < 74) yield Race.BRETON;
                if (roll < 80) yield Race.REDGUARD;
                if (roll < 87) yield Race.DUNMER;
                if (roll < 91) yield Race.BOSMER;
                if (roll < 93) yield Race.ALTMER;
                if (roll < 95) yield Race.KHAJIIT;
                if (roll < 97) yield Race.ARGONIAN;
                yield Race.ORC;
            }
        };
    }

    private static Race pick(RandomSource r, Race... races) {
        return races[r.nextInt(races.length)];
    }

    private static String pick(RandomSource r, String[] list) {
        return list[r.nextInt(list.length)];
    }

    /** A first name. */
    public static String generate(Race race, boolean female, RandomSource r) {
        if (race == Race.ARGONIAN) {
            if (r.nextInt(5) < 2) return pick(r, ARGONIAN_VERBS) + "-" + pick(r, ARGONIAN_NOUNS);
            String jel = syllables(race, female, r);
            // Jel names are often two short words joined by a hyphen
            return r.nextInt(3) == 0 ? jel + "-" + capital(pick(r, female ? FEMALE_SUF[8] : MALE_SUF[8])) : jel;
        }
        String name = syllables(race, female, r);
        if (race == Race.KHAJIIT && r.nextInt(3) > 0 || race == Race.OHMES_RAHT && r.nextInt(3) == 0) {
            name = pick(r, KHAJIIT_PREFIX) + name.toLowerCase(java.util.Locale.ROOT);
        }
        return name;
    }

    private static String syllables(Race race, boolean female, RandomSource r) {
        int i = race.ordinal();
        String[] pre = female ? FEMALE_PRE[i] : MALE_PRE[i];
        String[] suf = female ? FEMALE_SUF[i] : MALE_SUF[i];
        String a = pick(r, pre);
        String b = pick(r, suf);
        // avoid doubled letters at the joint ("Hildda")
        if (!a.isEmpty() && !b.isEmpty() && Character.toLowerCase(a.charAt(a.length() - 1)) == b.charAt(0)) b = b.substring(1);
        if (b.isEmpty()) b = suf[0];
        return a + b;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A family name in the culture's style, or null where the culture doesn't use them (or this person has none). */
    @Nullable
    public static String familyName(Race race, boolean female, RandomSource r) {
        switch (race) {
            case NORD, ATMORAN -> {
                if (r.nextInt(5) >= 2) return null; // most Nords go by their given name alone
                for (int i = 0; i < 6; i++) {
                    String a = pick(r, NORD_CLAN_A);
                    String b = pick(r, NORD_CLAN_B);
                    String clan = a + "-" + b;
                    if (!a.equals(b) && !RESERVED.contains(clan)) return clan;
                }
                return null;
            }
            case IMPERIAL -> {
                return pick(r, IMPERIAL_GENS_A) + pick(r, IMPERIAL_GENS_B);
            }
            case BRETON -> {
                return r.nextInt(4) == 0 ? null : pick(r, BRETON_HOUSE_A) + pick(r, BRETON_HOUSE_B);
            }
            case DUNMER -> {
                return r.nextInt(3) == 0 ? null : pick(r, DUNMER_FAMILY_A) + pick(r, DUNMER_FAMILY_B);
            }
            case ORC -> {
                // named after the father (gro- for sons, gra- for daughters)
                return (female ? "gra-" : "gro-") + syllables(Race.ORC, false, r);
            }
            default -> {
                return null;
            }
        }
    }

    /** First name plus the culture's family name, if any. */
    public static String fullName(Race race, boolean female, RandomSource r) {
        String first = generate(race, female, r);
        String family = familyName(race, female, r);
        return family == null ? first : first + " " + family;
    }
}
