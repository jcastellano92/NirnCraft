"""Minor named sites: the caves, camps, farms, tombs and towers that bring each province up to Skyrim's density once
its lore places are in, named in each people's own idiom (as Daggerfall named the thousands of sites of the Iliac
Bay). Used by tools/provinces.py; everything here is original.

WEIGHTS[culture] -> [(kind, weight)]: what a minor site of that culture tends to be. Kinds marked coastal in
tools/provinces.py (C) only go on the sea shore.
name(culture, kind, rng) -> str and describe(culture, kind, region_name, rng) -> str.
"""

WEIGHTS = {
    "imperial": [("cave", 10), ("den", 5), ("bandit_cave", 6), ("spider_cave", 2), ("skeever_cave", 2), ("troll_cave", 1),
                 ("mine", 5), ("ayleid", 7), ("imperial_crypt", 3), ("necromancer_lair", 3), ("vampire_lair", 2),
                 ("fort", 2), ("watchtower", 2), ("bandit_camp", 6), ("legion_camp", 1), ("cabin", 4), ("grove", 1),
                 ("farm", 6), ("homestead", 3), ("hamlet", 3), ("inn", 2), ("mill", 1), ("estate", 1), ("ruin", 3),
                 ("campsite", 2), ("stones", 1), ("monument", 1), ("clearing", 1), ("source", 1), ("waterfall", 1),
                 ("shipwreck", 1), ("lighthouse", 0.5), ("smuggler_cave", 1), ("fishing_village", 1)],
    "dunmer": [("cave", 8), ("velothi_tomb", 10), ("daedric_ruin", 4), ("dwemer", 3), ("mine", 5), ("tower", 3),
               ("keep", 1), ("bandit_camp", 2), ("necromancer_lair", 3), ("vampire_lair", 2), ("den", 4),
               ("farm", 4), ("homestead", 2), ("hamlet", 3), ("estate", 1), ("mage_tower", 1), ("ruin", 2), ("rock", 2),
               ("source", 1), ("campsite", 1), ("dwemer_landmark", 1), ("altar", 1),
               ("shipwreck", 1), ("smuggler_cave", 2), ("fishing_village", 1)],
    "argonian": [("cave", 6), ("den", 6), ("xanmeer", 10), ("grove", 5), ("hamlet", 5), ("mine", 2), ("bandit_camp", 3),
                 ("necromancer_lair", 2), ("vampire_lair", 2), ("spider_cave", 2), ("ayleid", 1), ("ruin", 3),
                 ("keep", 1), ("source", 1), ("rock", 1), ("clearing", 1), ("farm", 1),
                 ("shipwreck", 1), ("smuggler_cave", 1), ("fishing_village", 2)],
    "khajiit": [("cave", 6), ("den", 5), ("moon_temple", 8), ("bandit_camp", 5), ("mine", 2), ("farm", 5),
                ("homestead", 2), ("hamlet", 4), ("inn", 2), ("keep", 2), ("tower", 2), ("ruin", 3), ("campsite", 2),
                ("source", 2), ("rock", 3), ("stones", 1), ("necromancer_lair", 2), ("vampire_lair", 1), ("grove", 1),
                ("spider_cave", 1), ("shipwreck", 1), ("smuggler_cave", 1), ("fishing_village", 1)],
    "bosmer": [("cave", 6), ("den", 7), ("tree_tomb", 8), ("grove", 6), ("spider_cave", 3), ("spriggan_cave", 3),
               ("hamlet", 5), ("ayleid", 3), ("bandit_camp", 3), ("keep", 1), ("tower", 1), ("ruin", 2),
               ("clearing", 3), ("waterfall", 2), ("source", 1), ("necromancer_lair", 1), ("vampire_lair", 1),
               ("shipwreck", 1), ("fishing_village", 1), ("smuggler_cave", 1)],
    "altmer": [("crystal_vault", 8), ("cave", 5), ("den", 3), ("estate", 4), ("farm", 4), ("hamlet", 4), ("inn", 1),
               ("tower", 3), ("keep", 1), ("mage_tower", 2), ("ruin", 3), ("monument", 3), ("grove", 2), ("source", 1),
               ("waterfall", 1), ("sea_vault", 1), ("lighthouse", 1), ("fishing_village", 2), ("shipwreck", 1),
               ("smuggler_cave", 1)],
    "redguard": [("yokudan_tomb", 8), ("cave", 6), ("den", 4), ("mine", 3), ("dwemer", 2), ("bandit_camp", 5),
                 ("keep", 2), ("tower", 3), ("farm", 3), ("homestead", 2), ("hamlet", 4), ("inn", 2), ("ruin", 4),
                 ("source", 3), ("rock", 3), ("campsite", 2), ("necromancer_lair", 2), ("vampire_lair", 1),
                 ("spider_cave", 1), ("shipwreck", 1), ("lighthouse", 1), ("fishing_village", 1), ("smuggler_cave", 1)],
    "breton": [("cave", 8), ("den", 4), ("breton_crypt", 6), ("keep", 5), ("tower", 3), ("farm", 6), ("homestead", 3),
               ("hamlet", 5), ("inn", 2), ("mill", 1), ("estate", 2), ("bandit_camp", 4), ("hagraven_lair", 3),
               ("grove", 3), ("ayleid", 2), ("mine", 3), ("dwemer", 1), ("necromancer_lair", 2), ("vampire_lair", 2),
               ("spider_cave", 1), ("troll_cave", 2), ("ruin", 3), ("stones", 2), ("monument", 1),
               ("shipwreck", 1), ("lighthouse", 1), ("fishing_village", 2), ("smuggler_cave", 1)],
    "orc": [("stronghold", 3), ("orc_ruin", 5), ("cave", 6), ("troll_cave", 3), ("ice_cave", 2), ("den", 4), ("mine", 3),
            ("dwemer", 2), ("hagraven_lair", 1), ("bandit_camp", 3), ("tower", 1), ("ruin", 2), ("peak", 1), ("pass", 1),
            ("campsite", 1), ("source", 1)],
}

SETTLEMENT_KINDS = {"farm", "homestead", "hamlet", "inn", "mill", "estate", "fishing_village", "stronghold"}

# ---------------------------------------------------------------------------------------------------------------------
# word stock

IMP_A = ["Black", "Bleak", "Broken", "Cold", "Copper", "Crooked", "Dim", "Dusk", "Ember", "Fallen", "Fern", "Fox",
         "Gray", "Green", "Iron", "Lost", "Moss", "Mist", "Owl", "Raven", "Red", "Silver", "Thorn", "Timber", "Wolf",
         "Amber", "Bramble", "Briar", "Echo", "Fog", "Flint", "Hare", "Hawk", "Moon", "Oak", "Pine", "Root", "Rust",
         "Sable", "Sage", "Shadow", "Wasp", "Wind", "Willow", "Toad", "Elk", "Bear", "Boar", "Badger", "Heron", "Lark",
         "Nettle", "Ash", "Birch", "Cinder", "Clay", "Crow", "Dew", "Frost", "Gold", "Hollow", "Ivy", "Marsh", "Mule",
         "Otter", "Pike", "Quail", "Reed", "Salt", "Slate", "Swallow", "Tallow", "Vine", "Weasel", "Wren", "Yew"]
IMP_B = ["fall", "stone", "root", "crest", "moor", "glen", "fen", "mere", "brook", "wood", "scar", "tooth", "horn",
         "mouth", "rock", "ridge", "shade", "hill", "dale", "ford", "field", "wick", "hollow", "barrow", "cliff",
         "water", "run", "gate", "watch", "hearth", "well", "hedge", "marsh", "spur", "rest", "bend", "hook"]
IMP_CAVE = ["Cave", "Cavern", "Hollow", "Grotto", "Caverns", "Hole", "Burrow"]
IMP_SURNAMES = ["Aurelius", "Benirus", "Calvus", "Caro", "Decimus", "Fabius", "Flavius", "Galerius", "Gratian",
                "Hostilius", "Junius", "Lentulus", "Licinius", "Lucan", "Marius", "Maro", "Metellus", "Nerva",
                "Octavian", "Ovidius", "Pontius", "Quintus", "Rufinus", "Sabinus", "Septimus", "Tertius", "Tullius",
                "Valerius", "Varro", "Vitellius", "Albus", "Antonius", "Atius", "Bassus", "Brutus", "Cassius",
                "Cornelius", "Crispus", "Didius", "Drusus", "Faustus", "Florus", "Gallus", "Horatius", "Laelius",
                "Longinus", "Manlius", "Naevius", "Nonius", "Otho", "Paulus", "Priscus", "Rullus", "Scaevola", "Sulla",
                "Titus", "Ulpius", "Verus", "Vibius", "Aemilian", "Corvinus", "Severus", "Domitian", "Livius"]
INN_ADJ = ["Gilded", "Drowsy", "Merry", "Rusty", "Prancing", "Lonely", "Sleepy", "Jolly", "Crooked", "Golden",
           "Silver", "Tipsy", "Laughing", "Wandering", "Bent", "Lucky", "Blue", "Copper", "Winking", "Thirsty"]
INN_NOUN = ["Goat", "Carafe", "Lantern", "Flagon", "Mare", "Boar", "Kettle", "Griffon", "Stag", "Barrel", "Anchor",
            "Wheel", "Tankard", "Hound", "Rooster", "Pilgrim", "Crown", "Tortoise", "Netch", "Mudcrab", "Guar", "Hare"]

AYL_1 = ["A", "Ar", "Be", "Ce", "E", "El", "Fa", "Ga", "Ha", "La", "Li", "Ma", "Mi", "Na", "Ne", "Ni", "No", "Pi",
         "Ri", "Sa", "Se", "Si", "Ta", "Te", "Va", "Ve", "Vi", "We", "Wi", "Ya", "Ny", "Ma", "Lo"]
AYL_2 = ["la", "le", "li", "lin", "len", "lor", "nal", "nen", "nya", "ran", "ren", "ro", "sel", "sil", "tar", "tel",
         "var", "ven", "yon", "ca", "da", "ma", "nda", "ria", "sta", "ta"]
AYL_3 = ["da", "de", "dil", "din", "del", "kar", "len", "lin", "nda", "nor", "rel", "ril", "sel", "tare", "tar", "var",
         "vel", "wik", "ylle", "yon", "ata", "ia", "ue", "nque", "rion", "sen", "tatar", "dawik", "mbek", "lwe"]

DUN_FAMILIES = ["Alen", "Andalen", "Andules", "Arys", "Athram", "Beran", "Dalvilu", "Dreth", "Faryon", "Galen",
                "Gilvayn", "Hleran", "Indalen", "Llethri", "Maren", "Mavel", "Nerethi", "Nethalen", "Olen", "Orvas",
                "Romori", "Sadri", "Salen", "Sendas", "Seloth", "Sethri", "Telas", "Uvren", "Vandril", "Velas",
                "Voryn", "Adri", "Aryon", "Bero", "Dalen", "Darys", "Dolmyn", "Dren", "Ervyn", "Fedris", "Golven",
                "Hlaren", "Iveran", "Llarys", "Llevel", "Malvayn", "Methal", "Mivan", "Neloth", "Nilen", "Othril",
                "Ralen", "Rathryon", "Redas", "Rilvin", "Savel", "Selvil", "Teleri", "Thirith", "Tilvur", "Ulvel",
                "Valen", "Varo", "Velyn", "Vendu", "Verano", "Vilyn", "Avani", "Belas", "Dalobar", "Drarayne",
                "Eloe", "Faven", "Gindas", "Ienas", "Lleryn", "Mallam", "Moroni", "Nerevan", "Oran", "Rendo",
                "Sarvyn", "Selaro", "Tharen", "Ules", "Vadryn"]
DUN_C = ["sh", "s", "z", "k", "m", "n", "l", "r", "b", "d", "p", "t", "h", "zl", "kh"]
DUN_V = ["a", "a", "a", "i", "u", "e", "ai", "u"]
DUN_END = ["anu", "amus", "abi", "amat", "appi", "arnit", "assu", "ipal", "unar", "sipal", "anit", "ammu", "ibi",
           "alit", "urus", "akku", "irra", "anabi", "ishpi", "amdun"]
DUN_PREFIX = ["Ald", "Bal", "Hla", "Gnaar", "Maar", "Mar", "Ul", "Dun", "Tel", "Ash", "Ebon", "Sur", "Var", "Tur"]
DWE_1 = ["Arkng", "Bthan", "Bthung", "Mzan", "Mzul", "Nchar", "Nchul", "Nchur", "Rkhun", "Mthar", "Druscash", "Kag",
         "Bthal", "Mzin", "Ngar", "Rkul", "Tzin", "Ark", "Bamz", "Nchu"]
DWE_2 = ["chend", "thunch", "ft", "dahrk", "dumz", "eft", "zel", "amz", "tzel", "kar", "zak", "nch", "ald", "uz",
         "duabthar", "gar", "zthumz", "deft", "amschend", "thzark"]

ARG_A = ["Mud", "Black", "Deep", "Silt", "Slow", "Green", "Bright", "Dead", "Root", "Still", "Sun", "Moss", "Reed",
         "Bog", "Mire", "Dark", "Sweet", "Old", "Soft", "Long", "Red", "Gray", "Swift", "Cold", "Warm", "Tall", "Hist",
         "Glow", "Sap", "Drift"]
ARG_B = ["Tree", "Tail", "Water", "Root", "Whisper", "Throat", "Scale", "Stride", "Pool", "Fen", "Shell", "Egg",
         "Fang", "Eye", "Vine", "Moon", "Song", "Heart", "Leaf", "Wallow", "Mist", "Branch", "Claw", "Tooth", "Marsh"]
ARG_SYL = ["xa", "xi", "xu", "tsa", "tse", "tso", "hi", "ka", "ke", "ku", "lee", "mei", "na", "ni", "sei", "ta", "tee",
           "ul", "vu", "wa", "zu", "sul", "xan", "teeb", "hix", "nush", "vaka", "jee", "lu", "sa", "ush", "kot"]

KHA_SYL = ["ha", "ka", "ko", "sha", "ra", "ri", "ja", "je", "da", "do", "ma", "mo", "ne", "ni", "sa", "se", "ta", "te",
           "vo", "zu", "khe", "kha", "tha", "rin", "baa", "dro", "jo", "na", "qa", "s'", "ah", "zah"]
KHA_END = ["ae", "al", "ar", "e", "sha", "khi", "nar", "ra", "ri", "set", "zir", "hai", "mar", "kha"]
KHA_MOON = ["Waxing Moon", "Waning Moon", "Twin Moons", "Hidden Moon", "Full Moons", "Crescent", "Lunar Lattice",
            "Moon-Sugar", "Jone", "Jode", "Masser", "Secunda", "Gibbous Moon", "New Moon", "Dark Moon"]
KHA_PLACE = ["Mesa", "Spires", "Gulch", "Bluffs", "Wash", "Arch", "Butte", "Rocks", "Canyon", "Flats", "Dunes"]

BOS_1 = ["Ath", "Bel", "Cor", "Dra", "El", "Fal", "Gil", "Gol", "Hav", "Il", "Lae", "Lor", "Mal", "Nin", "Oss", "Ra",
         "Sil", "Tha", "Tel", "Vul", "Wil", "Ar", "Bal", "Ce", "Ger", "Hel", "Ith", "Ma", "Ne", "Ro", "Va", "Xy"]
BOS_2 = ["wasten", "estia", "ith", "ion", "dar", "dell", "anor", "oria", "amath", "arata", "aril", "enwe", "uin",
         "athil", "orin", "uvar", "elle", "ara", "ath", "inor", "oth", "ayas"]
BOS_SUFFIX = ["Hollow", "Thicket", "Canopy", "Bough", "Glade", "Bole", "Brake"]

ALT_1 = ["Al", "An", "Cal", "El", "Ez", "Fa", "Il", "Kor", "Lan", "Lil", "Mat", "Nor", "Que", "Ren", "Sil", "Tan",
         "Tor", "Val", "Wan", "Aer", "Ber", "Cey", "Ear", "Fen", "Gal", "Hel", "Ir", "Lir", "Mir", "Or", "Sar", "Ty"]
ALT_2 = ["duin", "deluun", "zelwil", "inaan", "iisen", "dril", "lor", "tar", "wen", "iel", "andil", "arion", "eth",
         "ilmo", "itar", "yon", "aril", "orne", "uvar", "ethil", "ondur", "ilwe", "ancar", "imor"]

RED_1 = ["Ab", "Ah", "Ba", "Ber", "Da", "Ga", "Ha", "Ka", "Ko", "Ma", "Na", "Ra", "Sa", "Se", "Ta", "Za", "Ki", "Lu",
         "Zo", "Ash", "Hal", "Sul", "Tav", "Yas", "Nu", "Qa", "Fa", "Ja", "Sha", "Bul"]
RED_2 = ["am", "an", "ar", "set", "ama", "iz", "ud", "alaam", "oon", "heb", "ira", "ana", "ush", "abah", "aleb",
         "orra", "anset", "ezir", "ahim", "uun", "akir", "ilah"]
RED_PERSON = ["Hunding", "Tava", "Onsi", "Diagna", "Leki", "Morwha", "Zeht", "Satakal", "Ruptga", "Malooc", "Sep",
              "Tu'whacca", "Arkay", "HoonDing"]

BRE_A = ["Ald", "Ash", "Bal", "Bram", "Cam", "Cross", "Dun", "Eagle", "Glen", "Grey", "Hart", "Hawk", "Lin", "Mar",
         "North", "Oak", "Raven", "Red", "Rose", "Stone", "Thorn", "West", "Wyn", "Wolf", "Ever", "Fair", "Gold",
         "High", "Low", "Mill", "Moss", "Pen", "Sheep", "Swan", "Tor", "Wil", "Yar", "Blackthorn", "Frost", "Briar"]
BRE_B = ["croft", "wych", "ford", "helm", "haven", "moor", "mere", "gate", "point", "dale", "brook", "bury", "wick",
         "stead", "ham", "ley", "combe", "hurst", "minster", "thorpe", "holt", "field", "wold", "marsh", "cliff"]
BRE_SURNAMES = ["Aubert", "Belmont", "Caron", "Darnell", "Evrard", "Faucher", "Gerard", "Hallet", "Jourdain",
                "Lambert", "Marchand", "Noel", "Ormond", "Perrin", "Quentin", "Renaud", "Sauvage", "Thibault",
                "Vasseur", "Verlaine", "Montclair", "Dorell", "Gaston", "Lafont", "Mercier", "Rousseau", "Tessier",
                "Valois", "Bastien", "Celestin", "Delacroix", "Fontaine", "Giraud", "Leclair", "Moreau", "Roche",
                "Sorel", "Travers", "Vidal", "Aldous", "Benoit", "Corbin", "Duval", "Emeric", "Gauvain", "Lenoir"]

ORC_1 = ["Bor", "Dra", "Dush", "Fha", "Gor", "Gul", "Kar", "Khaz", "Lar", "Mor", "Nar", "Shul", "Tum", "Ul", "Yal",
         "Zgar", "Bag", "Gash", "Krag", "Lug", "Mog", "Ogr", "Rug", "Ugh", "Bur", "Gro", "Thuk", "Uzg"]
ORC_2 = ["bul", "osh", "ur", "ul", "ak", "ash", "um", "gar", "khul", "nosh", "uk", "rash", "dul", "mok", "zog", "bag"]

REGION_FLAVOR = {
    "cave": ["a damp cave", "a winding cavern", "a cave mouth hidden by brush", "a narrow cave", "a deep grotto"],
}


def cap(s):
    return s[:1].upper() + s[1:]


def pick(rng, seq):
    return seq[rng.randrange(len(seq))]


def compound(rng, a, b):
    x, y = pick(rng, a), pick(rng, b)
    return x + y if rng.random() < 0.6 else x + " " + cap(y)


def ayleid(rng):
    s = pick(rng, AYL_1) + pick(rng, AYL_2) + (pick(rng, AYL_3) if rng.random() < 0.8 else "")
    return cap(s.lower())


def dunmer_word(rng):
    s = pick(rng, ["A", "E", "I", "U", ""]) + "".join(pick(rng, DUN_C) + pick(rng, DUN_V) for _ in range(rng.randrange(1, 3)))
    s = s + pick(rng, DUN_END)
    return cap(s.lower())


def dwemer(rng):
    return pick(rng, DWE_1) + pick(rng, DWE_2)


def jel(rng, n=None):
    n = n or rng.randrange(2, 4)
    return cap("".join(pick(rng, ARG_SYL) for _ in range(n)))


def ta_agra(rng):
    s = "".join(pick(rng, KHA_SYL) for _ in range(rng.randrange(1, 3))) + pick(rng, KHA_END)
    return cap(s.replace("''", "'").strip("'"))


def bosmer(rng):
    return pick(rng, BOS_1) + pick(rng, BOS_2)


def altmer(rng):
    return pick(rng, ALT_1) + pick(rng, ALT_2)


def redguard(rng):
    return pick(rng, RED_1) + pick(rng, RED_2)


def breton(rng):
    return pick(rng, BRE_A) + pick(rng, BRE_B)


def orcish(rng):
    s = pick(rng, ORC_1) + pick(rng, ORC_2)
    return s if rng.random() < 0.7 else s[:3] + "'" + s[3:]


def imperial(rng):
    return compound(rng, IMP_A, IMP_B)


def place_word(culture, rng):
    return {"imperial": imperial, "dunmer": dunmer_word, "argonian": lambda r: pick(r, ARG_A) + "-" + pick(r, ARG_B),
            "khajiit": ta_agra, "bosmer": bosmer, "altmer": altmer, "redguard": redguard, "breton": breton,
            "orc": orcish}.get(culture, imperial)(rng)


def surname(culture, rng):
    return {"imperial": lambda r: pick(r, IMP_SURNAMES), "dunmer": lambda r: pick(r, DUN_FAMILIES),
            "breton": lambda r: pick(r, BRE_SURNAMES), "redguard": redguard, "khajiit": ta_agra, "bosmer": bosmer,
            "altmer": altmer, "argonian": lambda r: jel(r, 2), "orc": orcish}.get(culture, imperial)(rng)


def name(culture, kind, rng):
    w = place_word(culture, rng)
    if kind in ("cave", "den", "spider_cave", "skeever_cave", "troll_cave", "spriggan_cave", "ice_cave"):
        if culture == "dunmer":
            return dunmer_word(rng)
        if culture == "argonian":
            return f"{w} {pick(rng, ['Hollow', 'Cave', 'Burrow', 'Grotto'])}"
        tail = {"den": ["Den", "Lair", "Hollow", "Burrow"], "spider_cave": ["Web", "Nest", "Hollow"],
                "troll_cave": ["Cave", "Cavern", "Hole"], "ice_cave": ["Ice Cave", "Glacier Cave", "Rime Cave"],
                "skeever_cave": ["Warren", "Burrow", "Cave"], "spriggan_cave": ["Grotto", "Hollow"]}.get(kind, IMP_CAVE)
        return f"{w} {pick(rng, tail)}"
    if kind in ("bandit_cave", "smuggler_cave"):
        if culture == "dunmer":
            return dunmer_word(rng)
        return f"{w} {pick(rng, ['Cave', 'Grotto', 'Hideout', 'Cove' if kind == 'smuggler_cave' else 'Hole'])}"
    if kind == "mine":
        if culture == "dunmer":
            return f"{dunmer_word(rng)} {pick(rng, ['Egg Mine', 'Eggmine', 'Mine'])}"
        if culture == "argonian":
            return f"{w} Kwama Mine"
        return f"{w} {pick(rng, ['Mine', 'Mine', 'Quarry', 'Diggings', 'Delve'])}"
    if kind == "ayleid":
        return ayleid(rng)
    if kind == "velothi_tomb":
        return f"{pick(rng, DUN_FAMILIES)} Ancestral Tomb"
    if kind == "daedric_ruin":
        return dunmer_word(rng)
    if kind == "dwemer":
        return dwemer(rng)
    if kind == "dwemer_landmark":
        return f"{dwemer(rng)} {pick(rng, ['Spire', 'Ruins', 'Tower'])}"
    if kind == "xanmeer":
        return f"{jel(rng)}{pick(rng, ['', ' Xanmeer', ' Ruins'])}" if rng.random() < 0.7 else f"{jel(rng, 2)}-{jel(rng, 2)}"
    if kind == "moon_temple":
        return pick(rng, [f"Temple of the {pick(rng, KHA_MOON)}", f"{ta_agra(rng)} Moon Temple", f"Shrine of the {pick(rng, KHA_MOON)}",
                          f"{ta_agra(rng)} Ruins"])
    if kind == "tree_tomb":
        return f"{bosmer(rng)} {pick(rng, ['Barrow', 'Root-Tomb', 'Ossuary', 'Burial Tree'])}"
    if kind == "crystal_vault":
        return f"{altmer(rng)} {pick(rng, ['Vault', 'Ruins', 'Sanctum', 'Undercroft', 'Archive'])}"
    if kind == "yokudan_tomb":
        return pick(rng, [f"Tomb of {redguard(rng)}", f"{redguard(rng)} Necropolis", f"{redguard(rng)} Tombs",
                          f"Shrine of {pick(rng, RED_PERSON)}"])
    if kind == "breton_crypt":
        return f"{pick(rng, BRE_SURNAMES)} {pick(rng, ['Crypt', 'Catacombs', 'Family Crypt', 'Tomb'])}"
    if kind == "orc_ruin":
        return f"Old {orcish(rng)}"
    if kind == "imperial_crypt":
        return f"{pick(rng, IMP_SURNAMES)} {pick(rng, ['Family Crypt', 'Crypt', 'Catacombs', 'Tomb'])}"
    if kind == "sea_vault":
        return f"{altmer(rng)} Sea-Cave"
    if kind in ("necromancer_lair", "vampire_lair"):
        if culture == "dunmer":
            return dunmer_word(rng)
        return f"{w} {pick(rng, ['Crypt', 'Hollow', 'Sanctum', 'Pit', 'Vault', 'Undercroft'])}"
    if kind == "hagraven_lair":
        return f"{w} {pick(rng, ['Hag Fen', 'Witch Grove', 'Coven', 'Hagspit'])}"
    if kind == "fort":
        return f"Fort {pick(rng, IMP_SURNAMES)}"
    if kind == "keep":
        return {"breton": lambda: f"{pick(rng, ['Castle', 'Castle', 'Keep'])} {pick(rng, BRE_SURNAMES)}" if rng.random() < 0.7 else f"{breton(rng)} Keep",
                "dunmer": lambda: f"{dunmer_word(rng)} Stronghold" if rng.random() < 0.3 else cap((pick(rng, ['Andas', 'Falas', 'Hlor', 'Inder', 'Mar', 'Rothe', 'Tela', 'Valen', 'Beran', 'Kogo']) + pick(rng, ['aryon', 'andus', 'eran', 'maren', 'vanyon', 'ruhn', 'sero', 'reth'])).lower()),
                "redguard": lambda: f"{redguard(rng)} {pick(rng, ['Fort', 'Citadel', 'Keep', 'Watch'])}",
                "khajiit": lambda: f"{ta_agra(rng)} {pick(rng, ['Fort', 'Keep', 'Citadel'])}",
                "bosmer": lambda: f"{bosmer(rng)} {pick(rng, ['Fortress', 'Keep', 'Stockade'])}",
                "altmer": lambda: f"{altmer(rng)} {pick(rng, ['Keep', 'Citadel', 'Bastion'])}",
                "argonian": lambda: f"{jel(rng)} {pick(rng, ['Fort', 'Stockade'])}",
                }.get(culture, lambda: f"Fort {pick(rng, IMP_SURNAMES)}")()
    if kind in ("tower", "watchtower"):
        if culture == "dunmer":
            return f"Ald {dunmer_word(rng)}" if rng.random() < 0.6 else f"{dunmer_word(rng)} Tower"
        return f"{w} {pick(rng, ['Tower', 'Watch', 'Lookout', 'Watchtower'])}"
    if kind == "mage_tower":
        return f"{w} {pick(rng, ['Spire', 'Tower', 'Sanctum'])}"
    if kind in ("bandit_camp", "legion_camp", "campsite"):
        return f"{w} {pick(rng, ['Camp', 'Camp', 'Encampment', 'Outpost'] if kind != 'campsite' else ['Campsite', 'Fire', 'Rest'])}"
    if kind == "cabin":
        return f"{pick(rng, IMP_SURNAMES if culture == 'imperial' else BRE_SURNAMES)}'s {pick(rng, ['Shack', 'Cabin', 'Hut', 'Lodge'])}"
    if kind == "grove":
        tail = {"argonian": "Hist Grove", "breton": "Wyrd Grove", "bosmer": "Grove"}.get(culture, "Grove")
        return f"{w} {tail}"
    if kind in ("farm", "homestead"):
        tail = {"farm": {"dunmer": "Plantation", "khajiit": "Sugar Farm", "altmer": "Vineyard", "redguard": "Farm",
                         "argonian": "Rice Paddy"}.get(culture, "Farm"), "homestead": "Homestead"}[kind]
        return f"{surname(culture, rng)} {tail}"
    if kind == "estate":
        tail = {"altmer": "Villa", "dunmer": "Manor", "breton": "Manor", "redguard": "Estate", "khajiit": "Estate"}.get(culture, "Estate")
        return f"{surname(culture, rng)} {tail}"
    if kind == "inn":
        return f"The {pick(rng, INN_ADJ)} {pick(rng, INN_NOUN)}"
    if kind == "mill":
        return f"{w} Mill"
    if kind == "hamlet":
        return w if culture != "argonian" else f"{w} Village"
    if kind == "fishing_village":
        return f"{w} {pick(rng, ['Landing', 'Cove', 'Harbor', 'Docks', 'Wharf'])}" if culture != "argonian" else f"{w} Village"
    if kind == "stronghold":
        return orcish(rng)
    if kind == "shipwreck":
        return f"Wreck of the {pick(rng, INN_ADJ)} {pick(rng, INN_NOUN)}"
    if kind == "lighthouse":
        return f"{w} Lighthouse"
    if kind == "ruin":
        return f"{w} Ruins"
    if kind == "monument":
        return f"{w} {pick(rng, ['Monument', 'Memorial', 'Obelisk', 'Statue'])}"
    if kind == "stones":
        return f"{w} Stones"
    if kind == "peak":
        return f"{w} Peak" if rng.random() < 0.5 else f"Mount {w}"
    if kind == "pass":
        return f"{w} Pass"
    if kind == "clearing":
        return f"{w} Clearing" if culture != "bosmer" else f"{w} Glade"
    if kind == "rock":
        if culture == "khajiit":
            return f"{w} {pick(rng, KHA_PLACE)}"
        return f"{w} {pick(rng, ['Rock', 'Arch', 'Crag', 'Pillar', 'Spire'])}"
    if kind == "waterfall":
        return f"{w} Falls"
    if kind == "source":
        tail = {"redguard": "Oasis", "khajiit": "Oasis", "dunmer": "Springs"}.get(culture, "Spring")
        return f"{w} {tail}"
    if kind == "altar":
        return f"{w} Altar"
    return w


# ---------------------------------------------------------------------------------------------------------------------
# descriptions: kind -> templates ({r} = region name, {k} = a culture-flavoured detail)

CULTURE_DETAIL = {
    "imperial": ["Colovian woodsmen", "Nibenese pilgrims", "Imperial patrols", "goblin tribes", "highwaymen"],
    "dunmer": ["Ashlanders", "Hlaalu traders", "Redoran scouts", "Telvanni agents", "cliff racers"],
    "argonian": ["Saxhleel hunters", "Hist-sap tappers", "Naga raiders", "wamasu", "bog-dwelling Lukiul"],
    "khajiit": ["Khajiit caravans", "Anequina nomads", "Pellitine merchants", "dune raiders", "moon-priests"],
    "bosmer": ["Bosmer hunters", "the Green Pact's keepers", "Wood Orc raiders", "spriggans", "timber mammoths"],
    "altmer": ["Altmer scholars", "Thalmor patrols", "Maormer raiders", "Psijic wanderers", "vineyard folk"],
    "redguard": ["Crowns", "Forebears", "Alik'r nomads", "sword-singers' heirs", "desert raiders"],
    "breton": ["Breton knights", "Reachmen", "witch covens", "petty lords' men", "Bjoulsae river-folk"],
    "orc": ["Orc hunters", "ogres", "Reachmen raiders", "Wrothgar giants", "exiled Orcs"],
}

DESCS = {
    "cave": ["A cave in {r}, shunned by {k}.", "A dark cave in {r} where wild things shelter from the weather.",
             "A winding cave of {r}; travellers tell of eyes shining in its depths."],
    "den": ["A beast den in {r}, strewn with gnawed bones.", "Predators of {r} drag their kills into this lair."],
    "bandit_cave": ["Outlaws use this cave in {r} to hide their plunder.", "A bandit hideout dug into the hills of {r}."],
    "spider_cave": ["Thick webs choke this cave of {r}.", "Giant spiders nest in this cave, and {k} give it a wide berth."],
    "skeever_cave": ["A warren of skeevers beneath {r}.", "A cave crawling with skeevers and filth."],
    "troll_cave": ["A troll's cave in {r}, reeking of old meat.", "Trolls lair in this cold cave of {r}."],
    "spriggan_cave": ["A green-lit grotto of {r} where spriggans tend strange roots."],
    "ice_cave": ["A cave of blue ice high in {r}."],
    "smuggler_cave": ["A sea cave on the shores of {r} where smugglers land their cargo.", "Smugglers' cove on the shore of {r}."],
    "mine": ["A mine of {r}, worked by {k}.", "A worked-out mine in {r}, its tunnels now home to worse than miners.",
             "Ore carts still stand at the mouth of this mine of {r}."],
    "ayleid": ["A sunken Ayleid ruin in {r}, its welkynd stones still faintly glowing.",
               "White Ayleid stone breaks the ground here: the entrance to a ruin older than the Empire.",
               "An Ayleid ruin of {r}, haunted by the restless dead of the old elven lords."],
    "velothi_tomb": ["The ancestral tomb of a Dunmer family, guarded by its honoured dead.",
                     "A family tomb in {r}; the ancestors here do not welcome grave-robbers."],
    "daedric_ruin": ["A ruined Daedric shrine in {r}, its horned spires blackened by ash.",
                     "Worshippers of the Daedra still gather at this broken temple."],
    "dwemer": ["A Dwemer ruin in {r}, where brass constructs still keep their silent watch.",
               "The bronze towers of a Dwemer city rise from the rock of {r}."],
    "dwemer_landmark": ["A lone Dwemer spire of {r}, long since stripped of its brass."],
    "xanmeer": ["A stepped Xanmeer of the old Saxhleel, half swallowed by the swamp.",
                "An Argonian ziggurat in {r}, sacred to the Hist and dangerous to outsiders."],
    "moon_temple": ["A Khajiiti moon temple of {r}, where priests once read the Lunar Lattice.",
                    "Moon-sugar offerings still lie at the doors of this ruined temple."],
    "tree_tomb": ["A Bosmer burial tree of {r}, where the dead are laid in the roots.",
                  "A barrow hollowed beneath a great tree, where {k} leave their dead."],
    "crystal_vault": ["An Altmer vault of {r}, sealed with crystal wards.",
                      "Ancient Aldmeri ruins of {r}, their crystal lamps still burning."],
    "yokudan_tomb": ["A Yokudan tomb of {r}, where honoured warriors rest with their swords.",
                     "Sand-scoured tombs of {r}, raised by the first Ra Gada settlers."],
    "breton_crypt": ["The crypt of a Breton noble house, sealed and long forgotten.",
                     "A family crypt in {r}, said to be cursed by its last lord."],
    "orc_ruin": ["The ruins of an older Orc stronghold, abandoned after a siege.",
                 "Broken walls of an ancient Orcish fortress in {r}."],
    "imperial_crypt": ["The crypt of an old Imperial family, its doors cracked by the years.",
                       "A Nibenese family crypt in {r}, whispered about by locals."],
    "sea_vault": ["A sea cave on the shores of {r} once used by Maormer raiders."],
    "coral_pit": ["A Sload coral pit, dripping with slime."],
    "necromancer_lair": ["Necromancers practise their art in this hidden place in {r}.",
                         "Locals avoid this hollow in {r}, where the dead do not stay buried."],
    "vampire_lair": ["A vampire coven's lair, hidden from the sun in {r}.",
                     "Travellers who shelter here in {r} are rarely seen again."],
    "hagraven_lair": ["A witches' lair in {r}, hung with feathers and bones."],
    "fort": ["An old Legion fort in {r}, garrisoned by whoever holds it now.",
             "A crumbling Imperial fort of {r}, long since abandoned by the Legion."],
    "keep": ["A fortified keep of {r}, held by {k}.", "A ruined castle in {r}, once the seat of a petty lord."],
    "tower": ["A lone watchtower of {r}.", "An old tower in {r}, its signal fire long cold."],
    "watchtower": ["An Imperial watchtower overlooking {r}."],
    "mage_tower": ["A wizard's lonely tower in {r}."],
    "bandit_camp": ["A camp of outlaws in {r}, preying on the road.", "Raiders' tents in {r}, guarded day and night."],
    "legion_camp": ["A Legion camp in {r}."],
    "cabin": ["A trapper's cabin in {r}."],
    "grove": ["A sacred grove of {r}, tended by {k}.", "An old grove in {r} where the trees seem to listen."],
    "farm": ["A farm of {r}, worked by a single family.", "Fields and a farmhouse in {r}."],
    "homestead": ["A lone homestead in {r}."],
    "stables": ["Stables outside the city walls of {r}, where horses are bought, sold and kept for travellers."],
    "estate": ["A country estate of {r}, home to a well-off family."],
    "inn": ["A roadside inn of {r}, welcome to tired travellers."],
    "mill": ["A mill in {r}, grinding grain for the nearby farms."],
    "hamlet": ["A small hamlet of {r}.", "A few homes clustered together in {r}."],
    "fishing_village": ["A fishing village on the shores of {r}."],
    "stronghold": ["An Orc stronghold of {r}, ruled by its chief."],
    "shipwreck": ["A wrecked ship on the shores of {r}, picked over by scavengers."],
    "lighthouse": ["A lighthouse guiding ships along the shores of {r}."],
    "ruin": ["Crumbling ruins in {r}, their builders forgotten."],
    "monument": ["An old monument of {r}, its inscription worn smooth."],
    "stones": ["A ring of standing stones in {r}."],
    "peak": ["A summit of {r} with a view across the land."],
    "pass": ["A mountain pass of {r}."],
    "clearing": ["A quiet clearing in {r}."],
    "rock": ["A striking rock formation of {r}, a landmark for travellers."],
    "waterfall": ["A waterfall of {r}."],
    "source": ["A spring of {r}, said to have healing waters.", "Fresh water in {r}, prized by travellers."],
    "campsite": ["A travellers' campsite in {r}."],
    "altar": ["An old altar in {r}, stained by forgotten offerings."],
}


# region names read with "the" (the Gold Coast); the others stand bare (Stormhold, Grahtwood)
THE = {"Heartlands", "Great Forest", "Colovian Highlands", "Gold Coast", "West Weald", "Jerall Mountains",
       "Nibenay Basin", "Nibenay Valley", "Ashlands", "Bitter Coast", "West Gash", "Ascadian Isles", "Grazelands",
       "Alik'r Desert", "Dragontail Mountains", "Bjoulsae River", "Iliac Bay"}


def region_phrase(region_name):
    return ("the " + region_name) if region_name in THE else region_name


def describe(culture, kind, region_name, rng):
    tpl = DESCS.get(kind, ["A place in {r}."])
    k = pick(rng, CULTURE_DETAIL.get(culture, CULTURE_DETAIL["imperial"]))
    out = pick(rng, tpl).format(r=region_phrase(region_name), k=k)
    return out[:1].upper() + out[1:]
