package com.skycraft.world;

import com.skycraft.tamriel.TamrielMap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic Elder Scrolls-style names for discovered generic structures, in the culture of the region they stand
 * in: a barrow in Skyrim is "Bleakwind Barrow", a ruin in Cyrodiil is an Ayleid "Nenalata", a tomb in Morrowind is
 * the "Dralas Ancestral Tomb", a ruin in Black Marsh is a Xanmeer. The same structure always gets the same name for
 * every player (the seed is the stable location id {@code dim|structure|cx,cz}, which also gives the region), so a
 * party sees the same name together and the signposts agree with the map.
 */
public final class LocationNames {
    private LocationNames() {}

    /** A culture's naming kit. Empty arrays fall back to the Nord kit. */
    private record Kit(String[] towns, String[] townPrefix, String[] townSuffix, String[] rootPrefix,
                       String[] rootSuffix, String[] gods, String[] forts) {
    }

    private static final String[] SHIPS = {
            "Brinehammer", "Icerunner", "Winter War", "Pride of Tel Vos", "Red Wave", "Sea Squall", "Northern Maiden",
            "Katariah", "Salty Skeever", "Frost Gull", "Stormcrow", "Horker's Folly", "Kraken's Kiss", "Ysgramor's Oar",
            "Bloated Man", "Grey Mare", "Solitude Star", "Wavebreaker", "Sea Elf's Bane", "Gold Coast Trader"
    };
    private static final String[] DAEDRA = {
            "Dagon", "Molag Bal", "Boethiah", "Mephala", "Vaermina", "Malacath", "Namira", "Peryite", "Sanguine",
            "Hircine", "Clavicus Vile", "Azura", "Meridia", "Nocturnal", "Sheogorath", "Hermaeus Mora"
    };
    private static final String[] INN_ADJ = {
            "Bannered", "Sleeping", "Drunken", "Frozen", "Winking", "Ragged", "Silver", "Old", "Laughing", "Mead-Soaked",
            "Wandering", "Golden", "Howling", "Lucky", "Rusty", "Snoring", "Faithful", "Merchant's", "Flowing", "Weary"
    };
    private static final String[] INN_NOUN = {
            "Mare", "Giant", "Huntsman", "Hearth", "Skeever", "Flagon", "Horker", "Mammoth", "Troll", "Bard", "Tankard",
            "Wolf", "Goat", "Draugr", "Nord", "Guar", "Netch", "Mudcrab", "Bowl", "Lantern"
    };
    private static final String[] HALLS = {"Valor", "Heroes", "the Honored Dead", "the Ancestors", "Shor", "the Brave"};
    private static final String[] DWEMER = {
            "Mzinchaleft", "Nchuand-Zel", "Alftand", "Arkngthamz", "Raldbthar", "Bthardamz", "Mzulft", "Irkngthand",
            "Kagrenzel", "Nchardak", "Mzark", "Avanchnzel", "Bthalft", "Rkundzelft", "Nchuthand", "Arkngthunch",
            "Nchurdamz", "Bamz-Amschend", "Mzahnch", "Druscashti", "Volkihar Deep", "Arkngamz"
    };
    private static final String[] MINE_SUFFIX = {"shard", "belly", "vein", "stone", "deep", "delve", "pit", "breaker", "wallow", "silver"};
    /** Ayleid ruin syllables (Cyrodiil and Valenwood). */
    private static final String[] AYLEID_A = {"Vil", "Nen", "Mal", "Sard", "Wend", "Hame", "Ond", "Lind", "Var", "Bawn",
            "Fan", "Ninen", "Ceyde", "Narf", "Vahn", "Tel", "Wel", "Sil", "Nor", "Ark", "Mis", "Rie"};
    private static final String[] AYLEID_B = {"verin", "alata", "acath", "ada", "avel", "irhen", "dalvel", "dalvilu",
            "andil", "inde", "ellam", "ahne", "anga", "asel", "elmo", "arwen", "anda", "ectus", "ellia", "inyon"};
    /** Dunmer ancestral families (ancestral tombs). */
    private static final String[] DUNMER_FAMILIES = {"Andrano", "Dralas", "Sarethi", "Indoril", "Venim", "Arethan",
            "Drinith", "Ienith", "Nerano", "Ravel", "Salvel", "Thelas", "Uveran", "Verelnim", "Dren", "Ravani",
            "Hlervu", "Sadryon", "Releth", "Orethi", "Raviro", "Llervu"};
    /** Daedric ruin syllables (Morrowind). */
    private static final String[] DAEDRIC_A = {"Ash", "Zain", "Kush", "Yas", "Mak", "Ib", "Ten", "Bal", "Ram", "Sul",
            "Ald", "Ass", "Bael", "Ze", "Shrine"};
    private static final String[] DAEDRIC_B = {"urnibibi", "tirari", "tashpi", "ammidan", "dahr", "ur", "imsun", "abi",
            "-Sotha", "arnibi", "ernibi", "amsun", "ushipal", "anammu"};
    /** Xanmeer syllables (Black Marsh). */
    private static final String[] XAN_A = {"Xal", "Tzi", "Thi", "Hei", "Sa", "Wuu", "Ixt", "Jaal", "Kota", "Seit", "Hal",
            "Nhal", "Mee", "Xit", "Vas"};
    private static final String[] XAN_B = {"rasku", "nghalis", "-Tees", "xith", "maleh", "nakal", "uxith", "-Meeus",
            "deesh", "hapa", "-Sei", "ixal", "zeel"};

    private static final Kit NORD = new Kit(
            new String[]{"Riverwood", "Rorikstead", "Ivarstead", "Dragon Bridge", "Karthwasten", "Shor's Stone",
                    "Kynesgrove", "Darkwater Crossing", "Stonehills", "Old Hroldan", "Soljund's Sinkhole",
                    "Heartwood Mill", "Anga's Mill", "Half-Moon Mill", "Riverside Hamlet", "Snowfall Crossing",
                    "Pinewatch", "Granite Hill", "Rimeholm", "Thornbury", "Eastbrook", "Greywater", "Mistveil",
                    "Rimerock", "Selvenhold"},
            new String[]{"Frost", "Iron", "Raven", "Wolf", "Pine", "Stone", "Snow", "Ash", "Elk", "Bear", "Mead", "Oak",
                    "Thorn", "Silver", "Winter", "Mist", "Amber", "Cold", "Grey", "High", "Rune", "Hearth", "Brook",
                    "Birch", "Hawk"},
            new String[]{"hollow", "stead", "wick", "wood", "ford", "holm", "bridge", "mere", "dale", "vale", "brook",
                    "haven", "moor", "crossing", "watch", "rest", "fell", "gate", "hill", "run"},
            new String[]{"Bleak", "Frost", "Raven", "Iron", "Shroud", "Silver", "Dead", "Black", "White", "Ice", "Storm",
                    "Wolf", "Bear", "Hag", "Gloom", "Shadow", "Fell", "Night", "Ember", "Snow", "Sky", "Ash", "Bone",
                    "Thorn", "Mist", "Grey", "Red", "Brittle", "Wind", "Dragon", "Gallows", "Korv", "Yngol", "Ust",
                    "Folg", "Ragn", "Geir", "Haal", "Rime", "Vald", "Saar", "Vol", "Sarth", "Ysgr", "Skuld", "Hjal",
                    "Draug", "Gjuk", "Morv", "Hrag"},
            new String[]{"wind", "fall", "mere", "crest", "hollow", "rock", "peak", "watch", "moor", "reach", "holm",
                    "gard", "vale", "spire", "helm", "shade", "fang", "crag", "shroud", "cairn", "maw", "tooth", "heim",
                    "stad", "run", "barrow", "kar", "ungr", "vild", "dun", "skar", "hund", "ald", "vard"},
            new String[]{"Akatosh", "Arkay", "Dibella", "Julianos", "Kynareth", "Mara", "Stendarr", "Talos", "Zenithar",
                    "Kyne"},
            new String[]{"Greymoor", "Dawnguard", "Sungard", "Amol", "Kastav", "Neugrad", "Snowhawk", "Hraggstad",
                    "Dunstad", "Greenwall", "Fellglow", "Valtheim"});

    private static final Map<String, Kit> KITS = new HashMap<>();

    static {
        KITS.put("nord", NORD);
        KITS.put("atmoran", new Kit(
                new String[]{"Saarthal", "Jylkurfyk", "Hsaarik Head", "Old Wyrmhold", "Skjaldheim"},
                new String[]{"Saar", "Ysg", "Jylk", "Hrol", "Vund", "Skjal", "Ulf", "Kjor", "Thjo", "Ragn"},
                new String[]{"thal", "heim", "gard", "stad", "holm", "vik"},
                new String[]{"Saar", "Ysg", "Jylk", "Hrol", "Vund", "Skjal", "Ulf", "Kjor", "Thjo", "Ragn", "Hjol", "Frygg",
                        "Gjal", "Mjor", "Orm"},
                new String[]{"thal", "rimr", "urfyk", "grav", "heim", "ungr", "vald", "skar", "frost", "gjall", "nir"},
                new String[]{"Shor", "Kyne", "Tsun", "Stuhn", "Jhunal", "Orkey", "Mara", "Dibella"},
                new String[]{"Ysgramor", "Wuuthrad", "Atmora", "Jeek", "Hoag"}));
        KITS.put("imperial", new Kit(
                new String[]{"Pell's Gate", "Weye", "Aleswell", "Hackdirt", "Harlun's Watch", "Water's Edge",
                        "Brindle Home", "Applewatch", "Sutch", "Ceyatatar", "Faregyl", "Cropsford", "Hammerfell Ford",
                        "Lord's Rest", "Bleaker's Way"},
                new String[]{"Pell", "Arrius", "Gold", "Silver", "Vine", "Rose", "Willow", "Hawk", "Elm", "Bay", "Glen",
                        "Fair", "Sable", "Crown"},
                new String[]{"'s Gate", "'s Watch", "ford", "dale", "brook", "field", "wood", " Hollow", " Crossing",
                        " Mill", "'s Rest", "ridge"},
                new String[]{"Sancre", "Vind", "Cast", "Aur", "Valus", "Corv", "Sard", "Ceno", "Lucr", "Vel", "Tarv",
                        "Hest", "Brin", "Sej", "Fal", "Marc", "Septim", "Varr", "Cyr", "Ner"},
                new String[]{"asus", "ium", "ia", "ora", "ex", "ane", "orum", "icus", "entis", "ara", "ius", "anus",
                        "ica", "atus"},
                new String[]{"Akatosh", "Arkay", "Dibella", "Julianos", "Kynareth", "Mara", "Stendarr", "Talos",
                        "Zenithar"},
                new String[]{"Sejanus", "Ash", "Black Boot", "Caractacus", "Chalman", "Cuptor", "Dirich", "Doublecross",
                        "Entius", "Facian", "Grief", "Homestead", "Istirus", "Linchal", "Nikel", "Redman", "Roebeck",
                        "Rayles", "Strand", "Teleman", "Variela", "Virtue", "Wariel"}));
        KITS.put("dunmer", new Kit(
                new String[]{"Gnisis", "Suran", "Pelagiad", "Seyda Neen", "Ald Velothi", "Gnaar Mok", "Hla Oad",
                        "Tel Branora", "Maar Gan", "Khuul", "Dagon Fel", "Tel Mora", "Molag Mar", "Tel Aruhn", "Vos"},
                new String[]{"Ald", "Hla", "Tel", "Gnaar", "Maar", "Sur", "Mol", "Tel Vos", "Uri", "Dren", "Bal"},
                new String[]{" Oad", " Mok", "an", "is", "adon", "uvar", " Mora", " Gan", "ruhn", "anipu"},
                new String[]{"Ald", "Ash", "Tel", "Hla", "Mar", "Sad", "Gnis", "Mol", "Vos", "Sur", "Dren", "Indo", "Sul",
                        "Ebon", "Kogo", "Ilu", "Shal", "Zal", "Mud", "Ules"},
                new String[]{"ruhn", "adon", "mora", "is", "alen", "oruhn", "uvar", "nibi", "anipu", "ashpi", "irbi",
                        "ammu", "gloan", "eroth", "hith", "arys", "elas"},
                new String[]{"Almalexia", "Vivec", "Sotha Sil", "Azura", "Boethiah", "Mephala", "Saint Veloth",
                        "Saint Nerevar"},
                new String[]{"Firewatch", "Frostmoth", "Ebonheart", "Buckmoth", "Pelagiad", "Moonmoth", "Darius",
                        "Telasero", "Andasreth", "Berandas", "Hlormaren", "Kogoruhn", "Marandus", "Falasmaryon"}));
        KITS.put("argonian", new Kit(
                new String[]{"Hissmir", "Thorn", "Archon", "Blackrose", "Stormhold", "Hatching Pool", "Sunk Root",
                        "Many-Eyes", "Bright-Throat", "Percolating Mire", "Hixeeth", "Lukiul Uxith"},
                new String[]{"Sunk", "Bright", "Many", "Hatch", "Slough", "Root", "Mire", "Reed", "Bog", "Gill", "Hist"},
                new String[]{"-Throat", "-Eyes", " Pool", " Root", "-Scale", " Hollow", " Mire", " Ford", "water"},
                XAN_A, XAN_B,
                new String[]{"Sithis", "the Hist", "Sithis", "Hermaeus Mora", "Nocturnal"},
                new String[]{"Arnesia", "Gideon", "Hissmir", "Thorn", "Teeth", "Bone", "Murk"}));
        KITS.put("khajiit", new Kit(
                new String[]{"Dune", "Orcrest", "Corinthe", "Tenmar", "Moonmont", "Rawl'kha", "Khenarthi's Roost",
                        "Pa'alat", "S'ren-ja", "Baandari Post", "Sunspire", "Valeguard"},
                new String[]{"Moon", "Sugar", "Sand", "Sun", "Dune", "Jade", "Cat's", "Tiger", "Silk", "Spice"},
                new String[]{"mont", "crest", " Roost", "'s Rest", " Post", "spire", " Oasis", "vale", " Gate"},
                new String[]{"Ja", "Dar", "Ri", "Ra", "Do", "Ma", "Kha", "Zaz", "Shaz", "Bah", "Aba", "Jo", "Ahz", "Ne",
                        "Sa", "Ro"},
                new String[]{"'khaj", "'zahr", "'sharr", "'jirr", "'dara", "ilyn", "rak", "aar", "'kha", "'rasha",
                        "zirr", "hjad"},
                new String[]{"Alkosh", "Riddle'Thar", "Jone", "Jode", "Mafala", "Khenarthi", "S'rendarr", "Azurah",
                        "Rajhin"},
                new String[]{"Sunguard", "Moonguard", "Khenarthi", "Jode", "Jone", "Sandshade", "Dune"}));
        KITS.put("bosmer", new Kit(
                new String[]{"Haven", "Silvenar", "Vulkwasten", "Marbruk", "Greenheart", "Athay", "Southpoint",
                        "Cormount", "Eldenroot Grove", "Driladan Pass", "Thormar", "Grimwatch"},
                new String[]{"Green", "Root", "Leaf", "Briar", "Moss", "Fern", "Thorn", "Elden", "Wild", "Vine"},
                new String[]{"heart", "hollow", "wood", " Grove", " Glade", "brook", "shade", "root", " Bough"},
                new String[]{"Eld", "Sil", "Fal", "Vul", "Tar", "Ath", "Lagen", "Bel", "Mar", "Gil", "Hal", "Glen",
                        "Wil", "Ner", "Thal", "Ul"},
                new String[]{"inesti", "venar", "kwasten", "bruk", "enthia", "adil", "aen", "oth", "wood", "anil",
                        "uin", "ethas"},
                new String[]{"Y'ffre", "Jephre", "Z'en", "Baan Dar", "Arkay", "Mara", "Stendarr"},
                new String[]{"Ash", "Laylith", "Sphinxmoth", "Greenheart", "Wild"}));
        KITS.put("altmer", new Kit(
                new String[]{"Cloudrest", "Shimmerene", "Lillandril", "Skywatch", "Vulkhel Guard", "Dusk", "Sunhold",
                        "Corgrad", "Errinorne Isle", "Ebon Stadmont", "Tower of Crystal Watch"},
                new String[]{"Cloud", "Sky", "Sun", "Dawn", "Star", "Crystal", "Shimmer", "Moon", "Silver", "Gold"},
                new String[]{"rest", "watch", "hold", " Guard", "ene", "reach", "spire", " Keep", "light"},
                new String[]{"Alin", "Lill", "Shim", "Corg", "Ald", "Ary", "Tor", "Ilm", "Sil", "Cy", "Ely", "Mathi",
                        "Kalu", "Ruv", "Ono"},
                new String[]{"or", "andril", "erene", "rad", "inor", "ondil", "ehol", "anwe", "ia", "inya", "aldo",
                        "orne"},
                new String[]{"Auri-El", "Trinimac", "Syrabane", "Phynaster", "Magnus", "Stendarr", "Mara", "Xarxes"},
                new String[]{"Firsthold", "Sunhold", "Skywatch", "Kingshaven", "Seaguard"}));
        KITS.put("redguard", new Kit(
                new String[]{"Gilane", "Hegathe", "Bergama", "Satakalaam", "Shiktar's Rest", "Abah's Landing",
                        "Kozanset", "Sandy Grotto", "Ragnthar", "Ojwambu", "Rkulftzel"},
                new String[]{"Sand", "Sun", "Dune", "Salt", "Scorch", "Bright", "Dry", "Spice", "Oasis", "Mirage"},
                new String[]{"'s Landing", " Well", "'s Rest", "wind", " Springs", "gate", " Bazaar", "vale"},
                new String[]{"Seh", "Tan", "Ri", "Gil", "Heg", "Ber", "Sat", "Kha", "Al", "Mor", "Ra", "Hal", "Kozan",
                        "Shik", "Ojw", "Yna"},
                new String[]{"eth", "had", "ane", "athe", "gama", "akalaam", "ir", "ush", "ara", "wen", "set", "ambu",
                        "tar"},
                new String[]{"Tu'whacca", "Zeht", "Morwha", "Satakal", "Onsi", "Tava", "Leki", "HoonDing", "Ruptga",
                        "Stendarr"},
                new String[]{"Dragonstar", "Sandblown", "Thorn", "Cinder", "Hew"}));
        KITS.put("yokudan", KITS.get("redguard"));
        KITS.put("breton", new Kit(
                new String[]{"Northpoint", "Farrun", "Glenpoint", "Aldcroft", "Koeglin Village", "Jehanna", "Davon's Watch",
                        "Lainlyn", "Crestshade", "Bal Foyen", "Baelborne Rock", "Westtry"},
                new String[]{"North", "Glen", "Ald", "Wind", "Crest", "Bal", "Jehan", "Farr", "Dav", "Aub", "Lain", "Mor"},
                new String[]{"point", "croft", "run", "shade", "ford", "mont", "ille", "'s Watch", " Village", "haven"},
                new String[]{"Shorn", "Ever", "North", "Glen", "Ald", "Wind", "Bal", "Jehan", "Farr", "Dav", "Aub",
                        "Phryg", "Gav", "Kar", "Mer", "Rav"},
                new String[]{"helm", "mor", "point", "croft", "run", "ennoc", "ais", "ille", "mont", "ford", "ias",
                        "aux", "enne"},
                new String[]{"Akatosh", "Arkay", "Dibella", "Julianos", "Kynareth", "Mara", "Stendarr", "Zenithar",
                        "Magnus"},
                new String[]{"Ravenwatch", "Tamrith", "Sanguine", "Northpoint", "Shornhelm", "Evermore"}));
        KITS.put("orc", new Kit(
                new String[]{"Dushnikh Yal", "Mor Khazgur", "Largashbur", "Narzulbur", "Kurog", "Morkul", "Fharun",
                        "Tumnosh", "Shatul", "Merovec"},
                new String[]{"Dush", "Mor", "Larg", "Narz", "Kur", "Burz", "Gor", "Shat", "Ulg", "Grat", "Mazg"},
                new String[]{" Yal", "bur", " Khazgur", "ashbur", "ulbur", "agh", "rosh"},
                new String[]{"Dush", "Mor", "Larg", "Narz", "Kur", "Burz", "Gor", "Shat", "Ulg", "Grat", "Mazg", "Bog",
                        "Yar", "Lum"},
                new String[]{"nikh", "khazgur", "ashbur", "ulbur", "ag", "rosh", "ul", "ok", "ub", "zag", "gol", "mash"},
                new String[]{"Malacath", "Trinimac", "Malacath"},
                new String[]{"Orsinium", "Malacath", "Morkul", "Fharun"}));
        KITS.put("akaviri", new Kit(
                new String[]{"Tsaesci Hold", "Kamal Watch", "Tang Mo", "Ka Po' Tun", "Pa'an", "Ri'Dao", "Hiroshen"},
                new String[]{"Jade", "Lotus", "Serpent", "Tiger", "Crane", "Ivory", "Cloud", "Snow"},
                new String[]{" Gate", " Court", " Hold", " Garden", "watch", " Terrace"},
                new String[]{"Tsa", "Ka", "Po", "Tun", "Mo", "Hiro", "Ryo", "Sei", "Ven", "Dar", "Kho", "Xen", "Shi",
                        "Ama"},
                new String[]{"esci", "mal", "sei", "ryu", "dao", "kai", "tun", "shin", "ren", "ko", "hata"},
                new String[]{"the Serpent", "the Tiger-Dragon", "the Monkey-Folk", "Kamal Kings", "Akatosh"},
                new String[]{"Dragonguard", "Serpent", "Tsaesci", "Kamal"}));
        KITS.put("maormer", new Kit(
                new String[]{"Pyandonea", "Orgnum's Rest", "Serpent Harbour", "Mistveil Isle"},
                new String[]{"Mist", "Sea", "Serpent", "Wave", "Tide", "Coral", "Pearl"},
                new String[]{"veil", "rest", " Harbour", " Isle", "deep", "reach"},
                new String[]{"Orgn", "Maor", "Pyan", "Thal", "Mael", "Ul", "Ael", "Sey", "Nyr"},
                new String[]{"um", "onea", "mir", "thas", "ess", "ion", "ira"},
                new String[]{"Orgnum", "Ius", "the Sea Serpent"},
                new String[]{"Tidewatch", "Serpentsgate"}));
        KITS.put("realm", new Kit(
                new String[]{"Lantern Hollow", "Waymeet", "Farhaven", "Embercross", "Dusk Harbour", "Lornmere"},
                new String[]{"Amber", "Ash", "Briar", "Cinder", "Dusk", "Ember", "Fallow", "Glimmer", "Hollow", "Jade",
                        "Lorn", "Moss", "Opal", "Pale", "Russet", "Sable", "Umber", "Vesper", "Yew"},
                new String[]{"reach", "fell", "mere", "hold", "wold", "marsh", "spire", "deep", "crest", "gate", "haven",
                        "watch"},
                new String[]{"Amber", "Ash", "Briar", "Cinder", "Dusk", "Ember", "Fallow", "Glimmer", "Hollow", "Iron",
                        "Jade", "Lorn", "Moss", "Nether", "Opal", "Pale", "Quill", "Russet", "Sable", "Thorn", "Umber",
                        "Vesper", "Wane", "Yew"},
                new String[]{"reach", "fell", "mere", "hold", "wold", "marsh", "spire", "deep", "crest", "gate", "maw",
                        "fang", "barrow"},
                new String[]{"the Old Gods", "the Nameless", "the Wanderer", "the Lost Stars", "the Deep Ones"},
                new String[]{"Lantern", "Farwatch", "Ember", "Last Light", "Wayfarer"}));
        KITS.put("none", NORD);
    }

    /** FNV-1a 64-bit: stable across JVMs and runs. */
    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static String pick(Random r, String[] list) {
        return list[r.nextInt(list.length)];
    }

    private static String root(Random r, Kit k) {
        String a = pick(r, k.rootPrefix());
        String b = pick(r, k.rootSuffix());
        if (a.toLowerCase(Locale.ROOT).endsWith(b.substring(0, 1).toLowerCase(Locale.ROOT))) b = pick(r, k.rootSuffix());
        return a + b;
    }

    /**
     * The culture of the region a location id points at ({@code minecraft:overworld|structure|cx,cz}); {@code nord}
     * when the id doesn't name an overworld chunk.
     */
    public static String cultureOf(String id) {
        try {
            String[] parts = id.split("\\|");
            if (parts.length < 3 || !parts[0].equals("minecraft:overworld")) return "nord";
            String[] xz = parts[parts.length - 1].split(",");
            int cx = Integer.parseInt(xz[0].trim());
            int cz = Integer.parseInt(xz[1].trim());
            String culture = TamrielMap.region(cx * 16 + 8, cz * 16 + 8).culture();
            return culture == null || culture.isEmpty() ? "nord" : culture.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "nord";
        }
    }

    /**
     * @param id   stable location id (dim|structure|chunk); also gives the region and so the naming culture
     * @param path structure registry path, used for flavour (crypt vs barrow)
     */
    public static String generate(String id, LocationKind kind, String path) {
        return generate(id, kind, path, cultureOf(id));
    }

    /** {@link #generate(String, LocationKind, String)} in an explicit culture ({@code nord}, {@code imperial}...). */
    public static String generate(String id, LocationKind kind, String path, String culture) {
        Random r = new Random(hash(id));
        r.nextInt(); // decorrelate from the raw seed
        String c = culture == null ? "nord" : culture.toLowerCase(Locale.ROOT);
        if (c.startsWith("dunmer")) c = "dunmer"; // dunmer_hlaalu, dunmer_telvanni...
        Kit k = KITS.getOrDefault(c, KITS.get("realm"));
        String special = special(c, kind, path, r, k);
        if (special != null) return special;
        return switch (kind) {
            case TOWN, CITY, CAPITAL -> r.nextInt(5) < 2 ? pick(r, k.towns()) : pick(r, k.townPrefix()) + pick(r, k.townSuffix());
            case MINE -> pick(r, k.rootPrefix()) + pick(r, MINE_SUFFIX) + " Mine";
            case SANCTUM -> r.nextInt(8) == 0 && c.equals("nord") ? "Labyrinthian" : root(r, k) + " Sanctum";
            case TOMB -> root(r, k) + " Tomb";
            case RUIN -> path.contains("ocean") ? "Sunken " + root(r, k) + " Ruins" : root(r, k) + " Ruins";
            case SHRINE -> root(r, k) + " Sunken Shrine";
            case DWEMER -> r.nextInt(4) == 0 && c.equals("nord") ? "Blackreach" : pick(r, DWEMER);
            case BARROW -> {
                if (path.contains("catacomb")) yield root(r, k) + " Catacombs";
                if (path.contains("crypt") || path.contains("mausoleum")) yield root(r, k) + " Crypt";
                yield root(r, k) + (r.nextBoolean() ? " Barrow" : " Tomb");
            }
            case HUT -> root(r, k) + (path.contains("swamp") || path.contains("witch") ? " Hagraven Hut" : r.nextBoolean() ? " Hut" : " Shack");
            case MANOR -> root(r, k) + " Manor";
            case BANDIT_CAMP -> root(r, k) + (r.nextBoolean() ? " Bandit Outpost" : " Redoubt");
            case WRECK -> "Wreck of the " + pick(r, SHIPS);
            case OBLIVION_GATE -> "Shattered Oblivion Gate";
            case CITADEL -> r.nextInt(3) == 0 ? "Daedric Citadel" : "Citadel of " + pick(r, DAEDRA);
            case DAEDRIC_TOWER -> r.nextInt(3) == 0 ? "Oblivion Tower" : "Sigil Tower of " + pick(r, DAEDRA);
            case HALL_OF_VALOR -> "Hall of " + pick(r, HALLS);
            case FORT -> "Fort " + (r.nextInt(3) == 0 ? pick(r, k.forts()) : root(r, k));
            case TOWER -> root(r, k) + (r.nextBoolean() ? " Tower" : " Watchtower");
            case CAMP -> root(r, k) + " Camp";
            case CAVE -> {
                int v = r.nextInt(4);
                yield root(r, k) + (v == 0 ? " Grotto" : v == 1 ? " Den" : " Cave");
            }
            case TEMPLE -> r.nextBoolean() ? "Temple of " + pick(r, k.gods()) : "Shrine of " + pick(r, k.gods());
            case INN -> "The " + pick(r, INN_ADJ) + " " + pick(r, INN_NOUN);
            case LANDMARK -> root(r, k) + (r.nextBoolean() ? " Stones" : " Monolith");
            case STANDING_STONE -> "The " + root(r, k) + " Stone";
            case WORD_WALL -> root(r, k) + " Wall";
            case DRAGON_MOUND -> root(r, k) + " Dragon Mound";
            case DAEDRIC_SHRINE -> "Shrine of " + pick(r, DAEDRA);
        };
    }

    /** Culture-specific forms that don't fit the shared pattern, or null. */
    private static String special(String c, LocationKind kind, String path, Random r, Kit k) {
        switch (c) {
            case "imperial", "bosmer" -> {
                // the Ayleids built in the Heartland and the Valenwood forests
                if (kind == LocationKind.RUIN || kind == LocationKind.TOMB || kind == LocationKind.SANCTUM
                        || (kind == LocationKind.BARROW && r.nextInt(3) > 0)) {
                    return pick(r, AYLEID_A) + pick(r, AYLEID_B);
                }
                if (kind == LocationKind.MANOR && c.equals("imperial")) return root(r, k) + " Estate";
            }
            case "dunmer" -> {
                if (kind == LocationKind.BARROW || kind == LocationKind.TOMB) return pick(r, DUNMER_FAMILIES) + " Ancestral Tomb";
                if (kind == LocationKind.RUIN || kind == LocationKind.SANCTUM || kind == LocationKind.TEMPLE && r.nextBoolean()) {
                    String a = pick(r, DAEDRIC_A);
                    return a + pick(r, DAEDRIC_B);
                }
                if (kind == LocationKind.FORT) return "Fort " + pick(r, k.forts());
                if (kind == LocationKind.TOWER) return "Tel " + root(r, k);
                if (kind == LocationKind.CAMP) return root(r, k) + " Ashlander Camp";
            }
            case "argonian" -> {
                if (kind == LocationKind.RUIN || kind == LocationKind.BARROW || kind == LocationKind.TOMB
                        || kind == LocationKind.SANCTUM || kind == LocationKind.TEMPLE) {
                    return root(r, k) + " Xanmeer";
                }
            }
            case "orc" -> {
                if (kind == LocationKind.TOWN || kind == LocationKind.FORT) return pick(r, k.rootPrefix()) + pick(r, new String[]{" Yal", "bur", " Khazgur", "ag"});
                if (kind == LocationKind.TEMPLE) return "Shrine of Malacath";
            }
            case "redguard", "yokudan" -> {
                if (kind == LocationKind.BARROW || kind == LocationKind.TOMB) return root(r, k) + (r.nextBoolean() ? " Tomb" : " Necropolis");
                if (kind == LocationKind.CAMP || kind == LocationKind.BANDIT_CAMP) return root(r, k) + " Encampment";
            }
            case "khajiit" -> {
                if (kind == LocationKind.TEMPLE) return "Temple of the Two Moons";
                if (kind == LocationKind.CAMP) return root(r, k) + " Caravan Camp";
            }
            case "akaviri" -> {
                if (kind == LocationKind.TEMPLE || kind == LocationKind.SHRINE) return "Shrine of " + pick(r, k.gods());
                if (kind == LocationKind.FORT || kind == LocationKind.TOWER) return root(r, k) + " Fortress";
            }
            case "atmoran" -> {
                if (kind == LocationKind.TOWN) return root(r, k) + (r.nextBoolean() ? "heim" : " Hall");
            }
            default -> {
                // nord, maormer, realm: the shared pattern
            }
        }
        return null;
    }
}
