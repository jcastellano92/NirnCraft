"""The Skycraft arms & armour catalogue: every Skyrim weapon material and armour set plus the gear of the other
provinces of Tamriel and the lands beyond.

This table is the single source of truth. tools/textures/gear.py reads it and writes:
  * the generated enum constants of SmithingTier.java and SkyArmorMaterial.java (between the "gear:" markers),
  * item textures, item models, armour layers and the 3D armour geometry (skycraft/gear/armor_models.json),
  * lang (mod/src/main/lang/skycraft/gear.json), gold values, weights, item tags and loot tables.

Numbers follow Skyrim: `sword` is the base damage of the material's sword, `ar` the armour ratings of
helmet / cuirass / gauntlets / boots, `wt` and `val` weights and gold values (helmet, cuirass, gauntlets, boots,
shield). Minecraft stats are derived from them in to_java_*().
"""
from dataclasses import dataclass, field

ALL = ("dagger", "sword", "war_axe", "mace", "greatsword", "battleaxe", "warhammer", "bow")
MELEE = ALL[:-1]
TWO_HANDED = ("greatsword", "battleaxe", "warhammer")
TYPE_LABEL = {"dagger": "Dagger", "sword": "Sword", "war_axe": "War Axe", "mace": "Mace", "greatsword": "Greatsword",
              "battleaxe": "Battleaxe", "warhammer": "Warhammer", "bow": "Bow"}
# gold value of each type relative to the material's sword (WeaponType.valueMult)
TYPE_VALUE = {"dagger": 0.4, "sword": 1.0, "war_axe": 1.2, "mace": 1.4, "greatsword": 1.8, "battleaxe": 2.0,
              "warhammer": 2.2, "bow": 1.9}
# Skyrim weights of the Iron weapons; other materials scale them by Tier.wfac
TYPE_WEIGHT = {"dagger": 2, "sword": 9, "war_axe": 11, "mace": 13, "greatsword": 16, "battleaxe": 20, "warhammer": 24,
               "bow": 7}
# Better Combat presets per type (Tier.bc overrides per material)
BC_PRESET = {"dagger": "bettercombat:dagger", "sword": "bettercombat:sword", "war_axe": "bettercombat:axe",
             "mace": "bettercombat:mace", "greatsword": "bettercombat:claymore", "battleaxe": "bettercombat:double_axe",
             "warhammer": "bettercombat:hammer"}

PIECES = ("helmet", "chestplate", "leggings", "boots")
# Minecraft slot items carry the Skyrim pieces: leggings = gauntlets (the model draws gauntlets and the cuirass' greaves)
PIECE_LABEL = ("Helmet", "Armor", "Gauntlets", "Boots")

PROVINCES = ("skyrim", "solstheim", "cyrodiil", "morrowind", "summerset", "valenwood", "hammerfell", "high_rock",
             "orsinium", "black_marsh", "elsweyr", "akavir", "atmora", "yokuda", "pyandonea")


@dataclass
class Tier:
    id: str
    name: str
    sword: float            # Skyrim sword damage
    uses: int               # durability of the sword
    level: int              # harvest level (vanilla Tier)
    ench: int               # enchantability
    bow: float              # arrow base damage from this material's bow (vanilla 2.0)
    perk: str | None        # smithing perk to forge it (None = anyone)
    temper: str             # perk that doubles tempering
    cat: str                # forge category
    rarity: str
    main: str               # main material item id (forging and tempering)
    value: int              # gold value of the sword
    wfac: float             # weight relative to Iron
    min_level: int          # leveled lists: first character level it drops at
    provinces: tuple
    extra: tuple = ()       # extra forge costs ((item id, count), ...)
    types: tuple = ALL
    fire: bool = False
    names: dict = field(default_factory=dict)  # per-type display names
    bc: dict = field(default_factory=dict)     # per-type Better Combat preset overrides
    craftable: bool = True

    def damage_bonus(self):
        return 2.0 + 0.5 * (self.sword - 7)

    def mining_speed(self):
        return round(6.0 + 0.45 * (self.sword - 7), 2)

    def label(self, t):
        if t in self.names:
            return self.names[t]
        return f"{self.name} {TYPE_LABEL[t]}"


S = "smithing."
STEEL, ELVEN, ADV, GLASS, DRAGON = S + "steel_smithing", S + "elven_smithing", S + "advanced_armors", S + "glass_smithing", S + "dragon_armor"
DWARVEN, ORCISH, EBONY, DAEDRIC = S + "dwarven_smithing", S + "orcish_smithing", S + "ebony_smithing", S + "daedric_smithing"

IRON_INGOT, STEEL_INGOT = "minecraft:iron_ingot", "skycraft:steel_ingot"
CORUNDUM, ORICHALCUM, MOONSTONE = "skycraft:corundum_ingot", "skycraft:orichalcum_ingot", "skycraft:refined_moonstone"
MALACHITE, QUICKSILVER, EBONY_INGOT = "skycraft:refined_malachite", "skycraft:quicksilver_ingot", "skycraft:ebony_ingot"
SILVER, GOLD, DWEMER = "skycraft:silver_ingot", "minecraft:gold_ingot", "skycraft:dwarven_metal_ingot"
LEATHER, HIDE, STRIPS = "minecraft:leather", "skycraft:hide", "skycraft:leather_strips"
DRAGON_BONE, DRAGON_SCALE, DAEDRA_HEART = "skycraft:dragon_bone", "skycraft:dragon_scale", "skycraft:daedra_heart"
STALHRIM, CHITIN, CHAURUS, NETCH = "skycraft:stalhrim", "skycraft:chitin_plate", "skycraft:chaurus_chitin", "skycraft:netch_leather"
BONE, DRAUGR_BONE, PRISMARINE, WOOL = "minecraft:bone", "skycraft:bone_meal_draugr", "minecraft:prismarine_shard", "minecraft:black_wool"

SKYRIM = ("skyrim",)

TIERS = [
    # ------------------------------------------------------------------ Skyrim (the original nine keep their numbers)
    Tier("iron", "Iron", 7, 250, 2, 14, 2.00, None, STEEL, "iron", "common", IRON_INGOT, 25, 1.0, 1, SKYRIM + ("realm",),
         names={"bow": "Long Bow"}),
    Tier("steel", "Steel", 8, 400, 2, 12, 2.15, STEEL, STEEL, "steel", "common", STEEL_INGOT, 45, 1.1, 6, SKYRIM + ("cyrodiil", "high_rock", "realm"),
         extra=((IRON_INGOT, 1),), names={"bow": "Hunting Bow"}),
    Tier("orcish", "Orcish", 9, 550, 3, 10, 2.30, ORCISH, ORCISH, "orcish", "common", ORICHALCUM, 75, 1.25, 12, SKYRIM + ("orsinium",),
         extra=((IRON_INGOT, 1),)),
    Tier("dwarven", "Dwarven", 10, 700, 3, 12, 2.50, DWARVEN, DWARVEN, "dwarven", "common", DWEMER, 135, 1.35, 12, SKYRIM + ("morrowind",),
         extra=((IRON_INGOT, 1),)),
    Tier("elven", "Elven", 11, 650, 3, 20, 2.65, ELVEN, ELVEN, "elven", "uncommon", MOONSTONE, 235, 1.4, 19, SKYRIM + ("summerset", "valenwood"),
         extra=((IRON_INGOT, 1), (QUICKSILVER, 1))),
    Tier("glass", "Glass", 12, 600, 3, 18, 2.85, GLASS, GLASS, "glass", "uncommon", MALACHITE, 410, 1.5, 27, SKYRIM + ("summerset", "morrowind"),
         extra=((MOONSTONE, 1),)),
    Tier("ebony", "Ebony", 13, 1500, 4, 14, 3.05, EBONY, EBONY, "ebony", "uncommon", EBONY_INGOT, 720, 1.6, 36, SKYRIM + ("morrowind", "realm")),
    Tier("daedric", "Daedric", 14, 2000, 4, 16, 3.30, DAEDRIC, DAEDRIC, "daedric", "rare", EBONY_INGOT, 1250, 1.75, 46, SKYRIM + ("morrowind", "realm"),
         extra=((DAEDRA_HEART, 1),), fire=True),
    Tier("dragonbone", "Dragonbone", 15, 2200, 4, 18, 3.50, DRAGON, DRAGON, "dragon", "rare", DRAGON_BONE, 1500, 1.95, 46, SKYRIM,
         extra=((EBONY_INGOT, 1),), fire=True),
    # ------------------------------------------------------------------ Skyrim, Dawnguard and Dragonborn
    Tier("silver", "Silver", 8, 380, 2, 22, 2.15, STEEL, STEEL, "silver", "uncommon", SILVER, 100, 1.0, 6, SKYRIM + ("high_rock",),
         extra=((STEEL_INGOT, 1),)),
    Tier("ancient_nord", "Ancient Nord", 8, 380, 2, 12, 2.20, STEEL, STEEL, "ancient", "common", IRON_INGOT, 40, 1.1, 3, SKYRIM + ("atmora",),
         extra=((CORUNDUM, 1),), names={"bow": "Ancient Nord Bow"}),
    Tier("forsworn", "Forsworn", 8, 300, 2, 16, 2.25, None, STEEL, "ancient", "common", BONE, 30, 0.9, 3, SKYRIM,
         extra=((HIDE, 1),)),
    Tier("imperial", "Imperial", 8, 420, 2, 14, 2.20, STEEL, STEEL, "imperial", "common", STEEL_INGOT, 50, 1.05, 3, SKYRIM + ("cyrodiil",),
         extra=((CORUNDUM, 1),)),
    Tier("falmer", "Falmer", 10, 520, 3, 14, 2.50, ELVEN, ELVEN, "ancient", "common", CHAURUS, 120, 1.15, 15, SKYRIM,
         extra=((MOONSTONE, 1),)),
    Tier("nordic", "Nordic", 11, 900, 3, 16, 2.65, ADV, ADV, "nordic", "uncommon", QUICKSILVER, 300, 1.4, 27, ("skyrim", "solstheim"),
         extra=((STEEL_INGOT, 1),)),
    Tier("skyforge", "Skyforge Steel", 11, 820, 3, 16, 2.65, ADV, STEEL, "steel", "uncommon", STEEL_INGOT, 260, 1.15, 19, SKYRIM,
         extra=((CORUNDUM, 1), (SILVER, 1))),
    Tier("akaviri", "Akaviri", 11, 900, 3, 16, 2.65, ADV, ADV, "foreign", "uncommon", STEEL_INGOT, 280, 1.0, 19, ("skyrim", "akavir"),
         extra=((MOONSTONE, 1),), names={"dagger": "Akaviri Tanto", "sword": "Akaviri Katana", "greatsword": "Akaviri Dai-Katana",
                                          "war_axe": "Akaviri Ono", "mace": "Akaviri Kanabo", "battleaxe": "Akaviri Masakari",
                                          "warhammer": "Akaviri Otsuchi", "bow": "Akaviri Yumi"},
         bc={"sword": "bettercombat:katana", "dagger": "bettercombat:sickle"}),
    Tier("stalhrim", "Stalhrim", 13.5, 1800, 4, 20, 3.20, EBONY, EBONY, "stalhrim", "rare", STALHRIM, 900, 1.5, 40, ("skyrim", "solstheim")),
    # ------------------------------------------------------------------ the other provinces and the lands beyond
    Tier("chitin", "Chitin", 9, 500, 2, 16, 2.30, ELVEN, ELVEN, "morrowind", "common", CHITIN, 60, 0.7, 3, ("morrowind", "solstheim"),
         types=("dagger", "sword", "war_axe", "mace", "bow"), names={"sword": "Chitin Short Sword", "mace": "Chitin Club"}),
    Tier("redguard", "Redguard", 10, 650, 3, 14, 2.45, STEEL, STEEL, "tamriel", "common", STEEL_INGOT, 140, 1.05, 6, ("hammerfell",),
         extra=((CORUNDUM, 1),), types=("dagger", "sword", "war_axe", "mace", "greatsword", "bow"),
         names={"dagger": "Redguard Khanjar", "sword": "Redguard Scimitar", "greatsword": "Redguard Great Scimitar",
                "war_axe": "Redguard Tabar"},
         bc={"sword": "bettercombat:cutlass"}),
    Tier("yokudan", "Yokudan", 12, 950, 3, 18, 2.80, ADV, ADV, "foreign", "uncommon", STEEL_INGOT, 380, 1.1, 19, ("yokuda", "hammerfell"),
         extra=((MOONSTONE, 1),), types=("dagger", "sword", "greatsword", "battleaxe", "bow"),
         names={"sword": "Yokudan Sword", "greatsword": "Yokudan Shehai", "battleaxe": "Yokudan Glaive-Axe"},
         bc={"sword": "bettercombat:cutlass"}),
    Tier("khajiit", "Khajiiti", 9.5, 600, 3, 16, 2.35, STEEL, STEEL, "tamriel", "common", STEEL_INGOT, 110, 0.95, 6, ("elsweyr",),
         extra=((GOLD, 1),), types=("dagger", "sword", "war_axe", "greatsword", "bow"),
         names={"dagger": "Khajiiti Claw Dagger", "sword": "Khajiiti Saber", "greatsword": "Khajiiti Moon Glaive"},
         bc={"sword": "bettercombat:cutlass", "dagger": "bettercombat:claw"}),
    Tier("bosmer", "Bosmer", 9, 460, 2, 18, 2.60, None, STEEL, "tamriel", "common", BONE, 90, 0.75, 3, ("valenwood",),
         extra=((HIDE, 1),), types=("dagger", "sword", "war_axe", "mace", "bow"),
         names={"sword": "Bosmer Bone Sword", "mace": "Bosmer Bone Club", "bow": "Bosmer Longbow"}),
    Tier("breton", "Breton", 10, 680, 3, 16, 2.40, STEEL, STEEL, "tamriel", "common", STEEL_INGOT, 150, 1.15, 6, ("high_rock",),
         extra=((SILVER, 1),), types=("dagger", "sword", "mace", "greatsword", "warhammer", "bow"),
         names={"sword": "Breton Arming Sword", "greatsword": "Breton Longsword", "mace": "Breton Flanged Mace"}),
    Tier("argonian", "Argonian", 9, 480, 2, 18, 2.35, None, ELVEN, "tamriel", "common", CHITIN, 80, 0.8, 3, ("black_marsh",),
         extra=((BONE, 1),), types=("dagger", "sword", "war_axe", "mace", "bow"),
         names={"sword": "Argonian Hookblade", "mace": "Argonian Hist Club"}),
    Tier("atmoran", "Atmoran", 10, 750, 3, 14, 2.45, STEEL, STEEL, "foreign", "uncommon", STEEL_INGOT, 170, 1.25, 12, ("atmora",),
         extra=((CORUNDUM, 1),), types=("dagger", "sword", "war_axe", "greatsword", "battleaxe", "warhammer"),
         names={"greatsword": "Atmoran Ancestral Blade"}),
    Tier("maormer", "Maormer", 11, 700, 3, 20, 2.70, ELVEN, ELVEN, "foreign", "uncommon", MOONSTONE, 260, 1.0, 15, ("pyandonea",),
         extra=((PRISMARINE, 1),), types=("dagger", "sword", "war_axe", "bow"),
         names={"sword": "Maormer Serpent Blade", "bow": "Maormer Serpent Bow"}),
]


@dataclass
class Armor:
    id: str
    name: str
    heavy: bool
    ar: tuple               # Skyrim armour rating: helmet, cuirass, gauntlets, boots
    shield: int             # Skyrim shield rating (0 = the set has no shield)
    wt: tuple               # weights: helmet, cuirass, gauntlets, boots, shield
    val: tuple              # values: helmet, cuirass, gauntlets, boots, shield
    ench: int
    tough: float
    perk: str | None
    temper: str
    cat: str
    rarity: str
    main: str               # main material (forging and tempering)
    style: str              # 3D model style (gear_armor.STYLES)
    sound: str              # equip sound: leather, chain, iron, gold, diamond, netherite
    min_level: int
    provinces: tuple
    extra: tuple = ()
    kb: float = 0.0
    fire: bool = False
    labels: tuple = PIECE_LABEL + ("Shield",)
    counts: tuple | None = None   # main material per piece (helmet, cuirass, gauntlets, boots)

    def defense(self):
        h, c, g, b = self.ar

        def r(x):
            return max(1, int(x + 0.5))

        return r(h / 6.5), r(c / 5.0), r(c / 6.5), r(b / 5.5)

    def durability(self):
        return max(5, int((self.ar[1] - 14) * 1.1 + 0.5))

    def shield_defense(self):
        return max(1, int(self.shield / 9 + 0.5)) if self.shield else 0

    def label(self, i):
        lab = self.labels[i]
        return lab if lab.split()[0] == self.name.split()[0] else f"{self.name} {lab}"


def L(h="Helmet", c="Armor", g="Gauntlets", b="Boots", s="Shield"):
    return (h, c, g, b, s)


ARMORS = [
    # ------------------------------------------------------------------ Skyrim: light armour
    Armor("hide", "Hide", False, (10, 20, 5, 5), 15, (2, 5, 1, 1, 4), (25, 50, 10, 10, 25), 15, 0.0, None, STEEL, "leather", "common",
          HIDE, "hide", "leather", 1, SKYRIM + ("realm",), labels=L(g="Bracers")),
    Armor("leather", "Leather", False, (12, 26, 7, 7), 0, (2, 6, 2, 2, 0), (60, 125, 25, 25, 0), 15, 0.0, None, STEEL, "leather", "common",
          LEATHER, "leather", "leather", 3, SKYRIM + ("cyrodiil", "realm"), labels=L(g="Bracers")),
    Armor("fur", "Fur", False, (11, 23, 6, 6), 0, (2, 6, 1, 1, 0), (35, 70, 15, 15, 0), 14, 0.0, None, STEEL, "leather", "common",
          HIDE, "fur", "leather", 1, SKYRIM + ("atmora",), extra=((LEATHER, 1),), labels=L(h="Hood", g="Gloves")),
    Armor("studded", "Studded", False, (11, 23, 6, 6), 0, (2, 6, 1, 1, 0), (40, 75, 15, 15, 0), 14, 0.0, None, STEEL, "leather", "common",
          LEATHER, "studded", "chain", 3, SKYRIM, extra=((IRON_INGOT, 1),), labels=L(g="Bracers")),
    Armor("elven", "Elven", False, (13, 29, 8, 8), 21, (1, 4, 1, 1, 4), (110, 225, 45, 45, 115), 20, 0.5, ELVEN, ELVEN, "elven", "uncommon",
          MOONSTONE, "elven", "gold", 12, SKYRIM + ("summerset", "valenwood"), extra=((IRON_INGOT, 1),)),
    Armor("elven_gilded", "Elven Gilded", False, (15, 35, 9, 9), 0, (1, 4, 1, 1, 0), (280, 550, 110, 110, 0), 22, 0.75, ELVEN, ELVEN, "elven", "uncommon",
          MOONSTONE, "elven_gilded", "gold", 19, SKYRIM + ("summerset",), extra=((GOLD, 1), (IRON_INGOT, 1))),
    Armor("scaled", "Scaled", False, (14, 32, 9, 9), 0, (2, 6, 2, 2, 0), (175, 350, 70, 70, 0), 16, 0.75, ADV, ADV, "advanced", "uncommon",
          STEEL_INGOT, "scaled", "chain", 12, SKYRIM + ("cyrodiil",), extra=((CORUNDUM, 1),), labels=L(h="Horn Helmet", g="Bracers")),
    Armor("glass", "Glass", False, (16, 38, 11, 11), 27, (2, 7, 2, 2, 6), (450, 900, 190, 190, 450), 18, 1.0, GLASS, GLASS, "glass", "uncommon",
          MALACHITE, "glass", "diamond", 27, SKYRIM + ("summerset", "morrowind"), extra=((MOONSTONE, 1),)),
    Armor("stalhrim_light", "Stalhrim Light", False, (17, 39, 11, 11), 0, (2, 7, 2, 2, 0), (900, 1800, 360, 360, 0), 20, 1.5, EBONY, EBONY, "stalhrim", "rare",
          STALHRIM, "stalhrim_light", "diamond", 40, ("skyrim", "solstheim"),
          labels=("Stalhrim Light Helmet", "Stalhrim Light Armor", "Stalhrim Light Gauntlets", "Stalhrim Light Boots", "Shield")),
    Armor("dragonscale", "Dragonscale", False, (17, 41, 12, 12), 29, (4, 10, 3, 3, 6), (750, 1500, 300, 300, 750), 16, 2.0, DRAGON, DRAGON, "dragon", "rare",
          DRAGON_SCALE, "dragonscale", "leather", 46, SKYRIM, fire=True),
    Armor("forsworn", "Forsworn", False, (10, 20, 6, 6), 0, (2, 4, 1, 1, 0), (40, 80, 15, 15, 0), 18, 0.0, None, STEEL, "ancient", "common",
          HIDE, "forsworn", "leather", 3, SKYRIM, extra=((BONE, 1),), labels=L(h="Headdress")),
    Armor("thieves_guild", "Thieves Guild", False, (12, 29, 8, 8), 0, (2, 6, 1, 1, 0), (250, 500, 100, 100, 0), 20, 0.5, ELVEN, ELVEN, "factions", "uncommon",
          LEATHER, "thieves_guild", "leather", 10, SKYRIM + ("cyrodiil",), extra=((GOLD, 1),), labels=L(h="Hood", g="Gloves")),
    Armor("nightingale", "Nightingale", False, (16, 34, 10, 10), 0, (2, 12, 2, 2, 0), (600, 1300, 250, 250, 0), 24, 1.0, GLASS, GLASS, "factions", "rare",
          LEATHER, "nightingale", "leather", 30, SKYRIM, extra=((EBONY_INGOT, 1),), labels=L(h="Hood", g="Gloves")),
    Armor("shrouded", "Shrouded", False, (13, 28, 10, 10), 0, (2, 7, 2, 2, 0), (200, 400, 80, 80, 0), 22, 0.5, ELVEN, ELVEN, "factions", "uncommon",
          LEATHER, "shrouded", "leather", 15, SKYRIM + ("cyrodiil",), extra=((STRIPS, 2),), labels=L(h="Cowl", g="Gloves", b="Shoes")),
    Armor("vampire", "Vampire", False, (12, 29, 8, 8), 0, (1, 2, 1, 1, 0), (150, 300, 60, 60, 0), 24, 0.5, ELVEN, ELVEN, "factions", "uncommon",
          LEATHER, "vampire", "leather", 20, SKYRIM, extra=((SILVER, 1),), labels=L(h="Hood", g="Gloves")),
    Armor("chitin", "Chitin", False, (13, 30, 8, 8), 0, (1, 4, 1, 1, 0), (125, 250, 50, 50, 0), 18, 0.5, ELVEN, ELVEN, "morrowind", "common",
          CHITIN, "chitin", "chain", 6, ("morrowind", "solstheim"), labels=L(g="Bracers")),
    Armor("imperial_light", "Imperial Light", False, (14, 23, 8, 8), 20, (2, 9, 1, 2, 6), (40, 75, 15, 15, 30), 14, 0.0, None, STEEL, "imperial", "common",
          LEATHER, "imperial_light", "leather", 3, SKYRIM + ("cyrodiil",), extra=((IRON_INGOT, 1),), labels=L(g="Bracers")),
    # ------------------------------------------------------------------ Skyrim: heavy armour
    Armor("iron", "Iron", True, (15, 25, 10, 10), 20, (5, 30, 5, 6, 12), (60, 125, 25, 25, 60), 9, 0.0, None, STEEL, "iron", "common",
          IRON_INGOT, "iron", "iron", 1, SKYRIM + ("realm",)),
    Armor("banded_iron", "Banded Iron", True, (16, 28, 11, 11), 22, (6, 35, 6, 7, 12), (90, 200, 40, 40, 100), 9, 0.25, STEEL, STEEL, "iron", "common",
          IRON_INGOT, "banded_iron", "iron", 3, SKYRIM, extra=((CORUNDUM, 1),)),
    Armor("steel", "Steel", True, (17, 31, 12, 12), 24, (5, 35, 4, 8, 12), (125, 275, 55, 55, 150), 9, 0.5, STEEL, STEEL, "steel", "common",
          STEEL_INGOT, "steel", "iron", 6, SKYRIM + ("cyrodiil", "high_rock", "realm"), extra=((IRON_INGOT, 1),)),
    Armor("steel_plate", "Steel Plate", True, (19, 40, 14, 14), 0, (6, 38, 6, 9, 0), (300, 625, 125, 125, 0), 10, 1.5, ADV, ADV, "advanced", "uncommon",
          STEEL_INGOT, "steel_plate", "iron", 19, SKYRIM + ("cyrodiil", "high_rock"), extra=((CORUNDUM, 1),)),
    Armor("dwarven", "Dwarven", True, (18, 34, 13, 13), 26, (12, 45, 8, 10, 12), (200, 400, 85, 85, 225), 10, 1.0, DWARVEN, DWARVEN, "dwarven", "common",
          DWEMER, "dwarven", "gold", 12, SKYRIM + ("morrowind",), extra=((STEEL_INGOT, 1),)),
    Armor("orcish", "Orcish", True, (20, 40, 15, 15), 30, (8, 35, 7, 7, 14), (500, 1000, 200, 200, 500), 10, 1.5, ORCISH, ORCISH, "orcish", "common",
          ORICHALCUM, "orcish", "iron", 19, SKYRIM + ("orsinium",), extra=((IRON_INGOT, 1),)),
    Armor("ebony", "Ebony", True, (21, 43, 15, 15), 32, (10, 38, 7, 7, 14), (750, 1500, 275, 275, 750), 12, 2.5, EBONY, EBONY, "ebony", "uncommon",
          EBONY_INGOT, "ebony", "netherite", 36, SKYRIM + ("morrowind", "realm"), kb=0.05),
    Armor("stalhrim_heavy", "Stalhrim", True, (21, 46, 16, 16), 0, (7, 38, 7, 7, 0), (1000, 2000, 400, 400, 0), 18, 3.0, EBONY, EBONY, "stalhrim", "rare",
          STALHRIM, "stalhrim_heavy", "diamond", 40, ("skyrim", "solstheim"), extra=((EBONY_INGOT, 1),), kb=0.1),
    Armor("daedric", "Daedric", True, (23, 49, 18, 18), 36, (15, 50, 6, 10, 15), (1600, 3200, 625, 625, 1600), 15, 3.5, DAEDRIC, DAEDRIC, "daedric", "rare",
          EBONY_INGOT, "daedric", "netherite", 46, SKYRIM + ("morrowind", "realm"), extra=((DAEDRA_HEART, 1),), kb=0.1, fire=True),
    Armor("dragonplate", "Dragonplate", True, (22, 46, 17, 17), 34, (8, 40, 8, 8, 15), (1050, 2125, 425, 425, 1050), 14, 3.0, DRAGON, DRAGON, "dragon", "rare",
          DRAGON_BONE, "dragonplate", "netherite", 46, SKYRIM, extra=((DRAGON_SCALE, 1),), kb=0.15, fire=True, counts=(1, 2, 2, 1)),
    Armor("nordic", "Nordic Carved", True, (20, 43, 15, 15), 32, (8, 35, 6, 7, 13), (650, 1300, 260, 260, 600), 12, 2.0, ADV, ADV, "nordic", "uncommon",
          QUICKSILVER, "nordic", "iron", 27, ("skyrim", "solstheim"), extra=((STEEL_INGOT, 1),),
          labels=("Nordic Carved Helmet", "Nordic Carved Armor", "Nordic Carved Gauntlets", "Nordic Carved Boots", "Nordic Shield")),
    Armor("bonemold", "Bonemold", True, (18, 34, 13, 13), 26, (6, 25, 4, 5, 10), (210, 425, 85, 85, 215), 12, 1.0, ADV, ADV, "morrowind", "common",
          NETCH, "bonemold", "chain", 12, ("morrowind", "solstheim"), extra=((BONE, 2), (IRON_INGOT, 1))),
    Armor("ancient_nord", "Ancient Nord", True, (15, 25, 10, 10), 0, (5, 30, 5, 6, 0), (50, 125, 25, 25, 0), 9, 0.0, STEEL, STEEL, "ancient", "common",
          IRON_INGOT, "ancient_nord", "iron", 3, SKYRIM + ("atmora",), extra=((DRAUGR_BONE, 1),)),
    Armor("falmer", "Falmer", True, (19, 39, 14, 14), 0, (6, 30, 5, 6, 0), (375, 750, 150, 150, 0), 14, 1.5, ELVEN, ELVEN, "ancient", "uncommon",
          CHAURUS, "falmer", "chain", 15, SKYRIM, extra=((MOONSTONE, 1),)),
    Armor("blades", "Blades", True, (20, 46, 13, 13), 29, (6, 45, 5, 8, 10), (400, 800, 160, 160, 400), 12, 2.0, ADV, ADV, "factions", "uncommon",
          STEEL_INGOT, "blades", "iron", 25, ("skyrim", "akavir"), extra=((MOONSTONE, 1),)),
    Armor("imperial", "Imperial", True, (18, 25, 10, 10), 22, (5, 35, 4, 8, 12), (60, 100, 25, 25, 60), 10, 0.25, STEEL, STEEL, "imperial", "common",
          STEEL_INGOT, "imperial", "iron", 3, SKYRIM + ("cyrodiil",), extra=((LEATHER, 1),), labels=L(g="Bracers")),
    Armor("stormcloak", "Stormcloak Officer", True, (17, 25, 10, 10), 20, (6, 30, 4, 6, 12), (60, 125, 25, 25, 60), 10, 0.25, STEEL, STEEL, "imperial", "common",
          IRON_INGOT, "stormcloak", "iron", 3, SKYRIM, extra=((LEATHER, 1),), labels=L(g="Bracers", s="Stormcloak Shield")),
    Armor("dawnguard", "Dawnguard", True, (18, 39, 13, 13), 29, (6, 40, 6, 7, 12), (300, 600, 120, 120, 300), 12, 1.5, ADV, ADV, "factions", "uncommon",
          STEEL_INGOT, "dawnguard", "iron", 20, SKYRIM, extra=((QUICKSILVER, 1),),
          labels=("Dawnguard Heavy Helmet", "Dawnguard Heavy Armor", "Dawnguard Heavy Gauntlets", "Dawnguard Heavy Boots", "Dawnguard Rune Shield")),
    Armor("wolf", "Wolf", True, (17, 31, 12, 12), 0, (5, 30, 4, 8, 0), (125, 275, 55, 55, 0), 11, 0.75, STEEL, STEEL, "factions", "uncommon",
          STEEL_INGOT, "wolf", "iron", 15, SKYRIM, extra=((HIDE, 1),)),
    # ------------------------------------------------------------------ the other provinces
    Armor("netch", "Netch Leather", False, (12, 26, 7, 7), 0, (2, 4, 1, 1, 0), (75, 150, 30, 30, 0), 16, 0.0, None, STEEL, "morrowind", "common",
          NETCH, "netch", "leather", 3, ("morrowind", "solstheim"), labels=L(g="Bracers")),
    Armor("ordinator", "Ordinator", True, (20, 42, 15, 15), 0, (8, 40, 7, 8, 0), (600, 1200, 240, 240, 0), 12, 2.0, ADV, ADV, "morrowind", "uncommon",
          EBONY_INGOT, "ordinator", "netherite", 27, ("morrowind",), extra=((GOLD, 1),)),
    Armor("thalmor", "Thalmor", False, (12, 26, 7, 7), 0, (1, 2, 1, 1, 0), (200, 400, 80, 80, 0), 26, 0.25, ELVEN, ELVEN, "tamriel", "uncommon",
          WOOL, "thalmor", "leather", 12, ("summerset", "skyrim"), extra=((MOONSTONE, 1),),
          labels=("Thalmor Hood", "Thalmor Robes", "Thalmor Gloves", "Thalmor Boots", "Shield")),
    Armor("bosmer", "Bosmer", False, (12, 27, 8, 8), 18, (1, 4, 1, 1, 4), (60, 120, 25, 25, 60), 18, 0.25, None, STEEL, "tamriel", "common",
          HIDE, "bosmer", "leather", 3, ("valenwood",), extra=((BONE, 1),), labels=L(g="Bracers")),
    Armor("ra_gada", "Ra Gada", False, (14, 31, 9, 9), 22, (2, 6, 1, 2, 5), (180, 360, 70, 70, 180), 16, 0.75, STEEL, STEEL, "tamriel", "common",
          LEATHER, "ra_gada", "chain", 6, ("hammerfell", "yokuda"), extra=((STEEL_INGOT, 1),), labels=L(h="Helm", g="Bracers", s="Buckler")),
    Armor("breton", "Breton Knight", True, (19, 41, 14, 14), 30, (7, 40, 6, 8, 14), (350, 700, 140, 140, 350), 11, 1.5, ADV, ADV, "tamriel", "uncommon",
          STEEL_INGOT, "breton", "iron", 12, ("high_rock",), extra=((SILVER, 1),), labels=L(h="Great Helm", s="Kite Shield")),
    Armor("argonian", "Argonian", False, (13, 30, 8, 8), 0, (1, 4, 1, 1, 0), (120, 240, 50, 50, 0), 18, 0.5, None, ELVEN, "tamriel", "common",
          CHITIN, "argonian", "chain", 3, ("black_marsh",), extra=((LEATHER, 1),), labels=L(g="Bracers")),
    Armor("khajiit", "Khajiiti", False, (13, 29, 8, 8), 20, (1, 4, 1, 1, 4), (120, 240, 50, 50, 120), 18, 0.5, STEEL, STEEL, "tamriel", "common",
          LEATHER, "khajiit", "chain", 6, ("elsweyr",), extra=((GOLD, 1),), labels=L(g="Bracers")),
    Armor("orsinium", "Orsimer", True, (21, 41, 15, 15), 30, (9, 38, 7, 8, 14), (550, 1100, 220, 220, 550), 10, 2.0, ORCISH, ORCISH, "orcish", "uncommon",
          ORICHALCUM, "orsinium", "iron", 19, ("orsinium",), extra=((BONE, 1),)),
    Armor("akaviri", "Akaviri", False, (16, 36, 10, 10), 0, (2, 8, 2, 2, 0), (400, 800, 160, 160, 0), 18, 1.0, ADV, ADV, "foreign", "uncommon",
          STEEL_INGOT, "akaviri", "chain", 19, ("akavir",), extra=((LEATHER, 1),), labels=L(h="Kabuto", c="Lamellar")),
    Armor("atmoran", "Atmoran", True, (17, 32, 12, 12), 24, (6, 35, 5, 7, 12), (150, 300, 60, 60, 150), 10, 0.75, STEEL, STEEL, "foreign", "uncommon",
          STEEL_INGOT, "atmoran", "iron", 9, ("atmora",), extra=((CORUNDUM, 1),)),
    Armor("maormer", "Maormer", False, (16, 37, 11, 11), 26, (2, 7, 2, 2, 5), (450, 900, 180, 180, 450), 20, 1.0, ELVEN, ELVEN, "foreign", "uncommon",
          MOONSTONE, "maormer", "diamond", 15, ("pyandonea",), extra=((PRISMARINE, 2),)),
]

EXISTING_ARMOR = {"hide", "elven", "glass", "dragonscale", "steel", "dwarven", "orcish", "ebony", "daedric", "dragonplate"}
EXISTING_TIERS = {"iron", "steel", "orcish", "dwarven", "elven", "glass", "ebony", "daedric", "dragonbone"}

# smithing materials added by the catalogue: id -> (name, value, weight)
MATERIALS = {
    "stalhrim": ("Stalhrim", 125, 1),
    "chitin_plate": ("Chitin Plate", 25, 1),
    "chaurus_chitin": ("Chaurus Chitin", 30, 1),
    "netch_leather": ("Netch Leather", 15, 1),
}


def tier(tid):
    return next(t for t in TIERS if t.id == tid)


def armor(aid):
    return next(a for a in ARMORS if a.id == aid)


def weapon_items():
    """(item id, tier, type) for every weapon."""
    return [(f"{t.id}_{w}", t, w) for t in TIERS for w in t.types]


def armor_items():
    """(item id, set, piece index 0..3 or 4 for the shield)."""
    out = []
    for a in ARMORS:
        for i, p in enumerate(PIECES):
            out.append((f"{a.id}_{p}", a, i))
        if a.shield:
            out.append((f"{a.id}_shield", a, 4))
    return out
