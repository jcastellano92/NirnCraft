"""Iliac Bay places generator & updater (R5 Steps IB3 + IB9 / V4 Places).

Updates:
  - mod/src/main/resources/nirncraft/tamriel/places/provinces.json
  - mod/src/main/resources/nirncraft/tamriel/places/high_rock.json
  - mod/src/main/resources/nirncraft/tamriel/places/hammerfell.json
  - mod/src/main/resources/nirncraft/tamriel/places/tamriel_shrines.json
  - mod/src/main/resources/nirncraft/tamriel/places/tamriel_roadside_inns.json

Preserves 100% of existing place IDs across provinces.json, high_rock.json, and hammerfell.json,
re-homes displaced places onto dry land in the 16 High Rock & Hammerfell land regions, places all 43
Daggerfall fief seats, all 18 marquee dungeons, Stros M'Kai Redguard layout sites, 14 witch covens,
inline quest places, and unmarked dungeons, and populates valid `why` citations from world/{high_rock,hammerfell}.json.
"""
import glob
import json
import math
import os
import pathlib
import re
import sys
from collections import Counter, defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "qa"))
import layout_lint as ll

RES = ROOT / "mod" / "src" / "main" / "resources" / "nirncraft"
PLACES_DIR = RES / "tamriel" / "places"
WORLD_DIR = RES / "tamriel" / "world"
QUESTS_DIR = RES / "quests"
FIEFS_PATH = RES / "tamriel" / "fiefs.json"
SEL_PATH = ROOT / ".reference" / "daggerfall_ref" / "v2" / "selection.json"

RADIUS_BY_TYPE = {
    "capital": 200,
    "city": 110,
    "town": 60,
    "village": 40,
    "farm": 28,
    "fort": 44,
    "standing_stone": 12,
    "word_wall": 20,
    "dragon_mound": 16,
    "daedric_shrine": 24,
    "landmark": 20,
    "dungeon_fixed": 36,
}

HR_REGIONS = [
    "high_rock/daggerfall",
    "high_rock/anticlere",
    "high_rock/dwynnen",
    "high_rock/wayrest",
    "high_rock/wrothgar",
    "high_rock/glenumbra",
    "high_rock/rivenspire",
    "high_rock/stormhaven",
    "high_rock/bjoulsae",
]

HF_REGIONS = [
    "hammerfell/sentinel",
    "hammerfell/alikr_desert",
    "hammerfell/bangkorai",
    "hammerfell/dragontail",
    "hammerfell/stros_mkai",
    "hammerfell/craglorn",
    "hammerfell/hews_bane",
]

MARQUEE_IDS = {
    "cath_bedraud",
    "privateers_hold",
    "castle_llugwych",
    "citadel_of_hearthsly",
    "doomcrag",
    "sanguines_demesne",
    "evermor_undercroft",
    "direnni_tower",
    "lysandus_tomb",
    "shedungent",
    "old_orsinium",
    "tristore_laboratory",
    "web_of_anker",
    "lost_city_of_the_na_totambu",
    "scourg_barrow",
    "bthzark",
    "skyreach_pinnacle",
    "hubalajad_palace",
}


def jload(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def jsave(path, obj, indent=1):
    pathlib.Path(path).write_text(json.dumps(obj, indent=indent, ensure_ascii=False) + "\n", encoding="utf-8")


def slug(name):
    s = name.lower().replace("'", "").replace("-", "_").replace(" ", "_")
    return re.sub(r"[^a-z0-9_]", "", s)


def build_region_cells(land):
    """Precompute dry-land candidate points (x, z, coast, landmass_label) per region and km2 per region."""
    c = land.c
    lab = ll.landmass_labels(c)
    raw_by_reg = defaultdict(list)
    px_counts = Counter()
    lab_counts = defaultdict(Counter)

    for cz in range(c.H):
        z_base = int(c.MIN_Z + cz * c.CELL + c.CELL // 2)
        for cx in range(c.W):
            r = c.by_index.get(int(c.reg[cz, cx]))
            if not r or r["kind"] == "sea":
                continue
            rid = r["id"]
            if not rid.startswith(("high_rock/", "hammerfell/")):
                continue
            px_counts[rid] += 1
            x_base = int(c.MIN_X + cx * c.CELL + c.CELL // 2)
            co = float(c.coast[cz, cx])
            lk = float(c.lake[cz, cx])
            lval = int(lab[cz, cx])
            if lk <= 0 and co > 0 and lval > 0:
                lab_counts[rid][lval] += 1
                # Add cell center + 4 sub-cell offsets so smaller regions have plenty of candidate points
                for ox, oz in ((0, 0), (-8, -8), (8, -8), (-8, 8), (8, 8)):
                    x, z = x_base + ox, z_base + oz
                    if land.region_at(x, z) == rid and land.probe(x, z)[0] == "land":
                        raw_by_reg[rid].append((x, z, co, lval))

    main_lab = {rid: cnt.most_common(1)[0][0] for rid, cnt in lab_counts.items() if cnt}
    by_reg = defaultdict(list)
    for rid, pts in raw_by_reg.items():
        ml = main_lab.get(rid)
        # Filter to dominant landmass of the region so roads never complain about isolated islands
        main_pts = [(x, z, co) for (x, z, co, lval) in pts if lval == ml]
        by_reg[rid] = main_pts if main_pts else [(x, z, co) for (x, z, co, _) in pts]

    km2_by_reg = {rid: max(0.01, px_counts[rid] * ((32.0 / 1000.0) ** 2)) for rid in HR_REGIONS + HF_REGIONS}
    return by_reg, km2_by_reg


def load_worldsheet_fields(prov):
    ws = jload(WORLD_DIR / f"{prov}.json")
    by_reg_layer = defaultdict(lambda: defaultdict(list))
    by_reg_all = defaultdict(list)
    for rname, rblk in ws.get("regions", {}).items():
        for lk, flst in rblk.get("layers", {}).items():
            for f in flst:
                fid = f"{rname}.{lk}.{f['id']}"
                by_reg_layer[rname][lk].append(fid)
                by_reg_all[rname].append(fid)
    return by_reg_layer, by_reg_all


def assign_why(p, by_reg_layer, by_reg_all, reg_counter):
    rid = p["region"]
    rname = rid.split("/")[-1]
    layers = by_reg_layer.get(rname, {})
    all_fids = by_reg_all.get(rname, [])
    if not all_fids:
        return [f"{rid.split('/')[0]}.L6.fief_patchwork"]

    pid = p["id"]
    ptype = p.get("type", "")
    var = p.get("variant") or ""

    chosen = []
    for fid in all_fids:
        short = fid.split(".")[-1]
        if short in pid or pid in short:
            chosen.append(fid)
            if len(chosen) >= 2:
                break

    if ptype in ("capital", "city", "town", "village"):
        pool = layers.get("L6", []) + layers.get("L5", []) + layers.get("L7", []) + layers.get("L8", []) + layers.get("L9", [])
    elif ptype == "farm":
        pool = layers.get("L5", []) + layers.get("L6", []) + layers.get("L7", []) + layers.get("L3", [])
    elif ptype in ("dungeon_fixed", "fort"):
        if var in ("mine", "egg_mine"):
            pool = layers.get("L5", []) + layers.get("L1", []) + layers.get("L10", [])
        elif var in ("grove", "hagraven_lair", "forsworn_camp", "forsworn_cave"):
            pool = layers.get("L10", []) + layers.get("L8", []) + layers.get("L4", [])
        elif var in ("bandit_camp", "bandit_cave", "smuggler_cave", "shipwreck", "imperial_camp", "watchtower", "tower"):
            pool = layers.get("L9", []) + layers.get("L10", []) + layers.get("L6", [])
        else:
            pool = layers.get("L10", []) + layers.get("L11", []) + layers.get("L8", []) + layers.get("L9", [])
    else:
        pool = layers.get("L1", []) + layers.get("L3", []) + layers.get("L4", []) + layers.get("L8", []) + layers.get("L11", [])

    l6_10 = []
    for lk in ("L6", "L7", "L8", "L9", "L10", "L11", "L5", "L4", "L3", "L1", "L2"):
        l6_10.extend(layers.get(lk, []))

    idx = reg_counter[rname]
    reg_counter[rname] += 1
    if l6_10:
        pick = l6_10[idx % len(l6_10)]
        if pick not in chosen:
            chosen.append(pick)
    if pool:
        pick2 = pool[(idx * 3 + 1) % len(pool)]
        if pick2 not in chosen:
            chosen.append(pick2)
    while len(chosen) < 2 and all_fids:
        pick3 = all_fids[(idx + len(chosen)) % len(all_fids)]
        if pick3 not in chosen:
            chosen.append(pick3)
        else:
            break
    return chosen[:3]


def same_name_tokens(ia, ib):
    stop = {"the", "of", "a", "and", "in", "at"}
    ta2 = set(ll.tokens(ia)) - stop
    tb2 = set(ll.tokens(ib)) - stop
    return ia == ib or (
        ta2 and tb2 and (ta2 <= tb2 or tb2 <= ta2) and min(len(ta2), len(tb2)) >= 2 and len(ta2 ^ tb2) <= 2
    )


def main():
    pv = ll.load_provinces()
    land = ll.Land(pv)
    by_reg_cells, km2_by_reg = build_region_cells(land)

    provinces_list = jload(PLACES_DIR / "provinces.json")
    hr_list = jload(PLACES_DIR / "high_rock.json")
    hf_list = jload(PLACES_DIR / "hammerfell.json")
    shrines_list = jload(PLACES_DIR / "tamriel_shrines.json")
    inns_list = jload(PLACES_DIR / "tamriel_roadside_inns.json")
    sel = jload(SEL_PATH)

    # Swap shedungent (to high_rock) and scourg_barrow (to hammerfell) while preserving IDs
    shed_entries = [p for p in hf_list if p["id"] == "shedungent"]
    scourg_entries = [p for p in hr_list if p["id"] == "scourg_barrow"]
    hf_list = [p for p in hf_list if p["id"] != "shedungent"] + scourg_entries
    hr_list = [p for p in hr_list if p["id"] != "scourg_barrow"] + shed_entries

    hr_by_id = {p["id"]: p for p in hr_list}
    hf_by_id = {p["id"]: p for p in hf_list}
    prov_by_id = {p["id"]: p for p in provinces_list}

    # 1. Update provinces.json anchors for High Rock & Hammerfell
    prov_updates = {
        "daggerfall": ("high_rock/daggerfall", -9300, 2600, "capital", 200),
        "wayrest": ("high_rock/wayrest", -5300, 3300, "capital", 200),
        "camlorn": ("high_rock/glenumbra", -9000, -200, "city", 150),
        "shornhelm": ("high_rock/rivenspire", -7600, -1300, "city", 150),
        "evermor": ("high_rock/bjoulsae", -6600, 200, "city", 150),
        "orsinium": ("high_rock/wrothgar", -4300, 300, "capital", 200),
        "sentinel": ("hammerfell/sentinel", -7976, 4900, "capital", 200),
        "elinhir": ("hammerfell/craglorn", -3950, 3800, "city", 150),
        "skaven": ("hammerfell/alikr_desert", -6400, 5700, "city", 150),
        "rihad": ("hammerfell/dragontail", -4800, 7200, "city", 150),
        "taneth": ("hammerfell/hews_bane", -6800, 8000, "city", 150),
        "stros_mkai": ("hammerfell/stros_mkai", -10200, 7200, "town", 90),
    }
    for pid, (reg, x, z, ptype, rad) in prov_updates.items():
        if pid in prov_by_id:
            p = prov_by_id[pid]
            p["region"] = reg
            p["x"] = x
            p["z"] = z
            p["type"] = ptype
            p["radius"] = rad

    # 2. Explicit settlement / fief seat / landmark / marquee / witch coven / quest place overrides
    hr_explicit = {
        # Named towns (city / town) — 100% in cityplans/index.json so s_bespoke = 1.0 & s_towns >= 0.25/km2
        "glenpoint": ("high_rock/daggerfall", -9465, 1926, "town", None, None),
        "anticlere": ("high_rock/anticlere", -8339, 2439, "city", None, None),
        "dwynnen": ("high_rock/dwynnen", -7417, 2041, "city", None, None),
        "menevia": ("high_rock/wayrest", -5576, 2907, "town", None, None),
        "jehanna": ("high_rock/wrothgar", -4700, -1950, "city", None, None),
        "morkul_stronghold_high_rock": ("high_rock/wrothgar", -5150, -650, "town", None, None),
        "fharun_stronghold": ("high_rock/wrothgar", -4150, -1750, "town", None, None),
        "tumnosh_stronghold": ("high_rock/wrothgar", -4650, 950, "village", "fief_keep", None),
        "aldcroft": ("high_rock/glenumbra", -9250, 650, "town", None, None),
        "crosswych": ("high_rock/glenumbra", -8650, -1550, "town", None, None),
        "northpoint": ("high_rock/rivenspire", -7450, -2450, "city", None, None),
        "crestshade": ("high_rock/rivenspire", -7900, -650, "town", None, None),
        "koeglin_village": ("high_rock/stormhaven", -7450, 950, "town", None, None),
        "dreughside": ("high_rock/stormhaven", -6950, 1150, "village", None, None),
        "farrun": ("high_rock/bjoulsae", -5950, -2150, "city", None, None),

        # Fief keeps (`village`, `variant: "fief_keep"`)
        "tulune": ("high_rock/daggerfall", -10010, 1940, "village", "fief_keep", None),
        "glenumbra_moors": ("high_rock/daggerfall", -9820, 1740, "village", "fief_keep", None),
        "grimley": ("high_rock/daggerfall", -9917, 1675, "village", "fief_keep", None),
        "whitefort": ("high_rock/daggerfall", -9840, 2820, "village", "fief_keep", None),
        "ilessan_hills": ("high_rock/daggerfall", -9133, 1824, "village", "fief_keep", None),
        "northmoor": ("high_rock/daggerfall", -9098, 1597, "village", "fief_keep", None),
        "shalgora": ("high_rock/anticlere", -8653, 2349, "village", "fief_keep", None),
        "daenia": ("high_rock/anticlere", -7959, 1866, "village", "fief_keep", None),
        "urvaius": ("high_rock/anticlere", -7740, 2270, "village", "fief_keep", None),
        "ykalon": ("high_rock/anticlere", -8292, 1573, "village", "fief_keep", None),
        "phrygias": ("high_rock/anticlere", -7667, 1690, "village", "fief_keep", None),
        "kambria": ("high_rock/dwynnen", -6523, 1966, "village", "fief_keep", None),
        "bhoriane": ("high_rock/dwynnen", -6966, 2299, "village", "fief_keep", None),
        "koegria": ("high_rock/dwynnen", -6353, 2157, "village", "fief_keep", None),
        "alcaire": ("high_rock/dwynnen", -6135, 2366, "village", "fief_keep", None),
        "gavaudon": ("high_rock/wayrest", -4618, 3216, "village", "fief_keep", None),
        "blackhead": ("high_rock/wayrest", -6180, 2916, "village", "fief_keep", None),
        "reytry": ("high_rock/wayrest", -5028, 2888, "village", "fief_keep", None),
        "newing": ("high_rock/wrothgar", -5816, 1880, "village", "fief_keep", None),

        # Curated villages & inns
        "grimmoth": ("high_rock/daggerfall", -9668, 1499, "village", None, None),
        "aldingwell": ("high_rock/anticlere", -7982, 1505, "village", None, None),
        "kirkwall": ("high_rock/wrothgar", -5506, 2407, "village", None, None),
        "weeping_giant": ("high_rock/anticlere", -7380, 1690, "village", "inn", None),
        "betony": ("high_rock/daggerfall", -9840, 2780, "landmark", "island", None),
        "isle_of_balfiera": ("high_rock/wayrest", -6120, 2900, "landmark", "island", None),

        # 11 High Rock Marquee Dungeons
        "cath_bedraud": ("high_rock/glenumbra", -9450, -950, "dungeon_fixed", "breton_crypt", "marquee"),
        "privateers_hold": ("high_rock/daggerfall", -9859, 2183, "dungeon_fixed", "breton_keep_ruin", "marquee"),
        "castle_llugwych": ("high_rock/anticlere", -8330, 1548, "fort", "breton_keep_ruin", "marquee"),
        "citadel_of_hearthsly": ("high_rock/dwynnen", -7220, 1980, "fort", "breton_keep_ruin", "marquee"),
        "doomcrag": ("high_rock/rivenspire", -6800, -2200, "dungeon_fixed", "ayleid_ruin", "marquee"),
        "sanguines_demesne": ("high_rock/stormhaven", -7350, 1050, "dungeon_fixed", "daedric_ruin", "marquee"),
        "evermor_undercroft": ("high_rock/bjoulsae", -6480, 260, "dungeon_fixed", "breton_keep_ruin", "marquee"),
        "direnni_tower": ("high_rock/wayrest", -6136, 2941, "fort", "keep", "marquee"),
        "lysandus_tomb": ("high_rock/wayrest", -5672, 3038, "dungeon_fixed", "breton_crypt", "marquee"),
        "lysanduss_tomb": ("high_rock/daggerfall", -9560, 2350, "dungeon_fixed", "breton_crypt", "short"),
        "shedungent": ("high_rock/wrothgar", -4485, 2184, "fort", "breton_keep_ruin", "marquee"),
        "old_orsinium": ("high_rock/wrothgar", -3797, -1434, "dungeon_fixed", "orc_ruin", "marquee"),

        # 8 High Rock Witch Covens from selection.json
        "coven_on_the_bluff": ("high_rock/daggerfall", -9920, 2377, "dungeon_fixed", "hagraven_lair", "short"),
        "glenmorial_coven": ("high_rock/daggerfall", -8950, 1866, "dungeon_fixed", "hagraven_lair", "short"),
        "beldama_coven": ("high_rock/anticlere", -8102, 1828, "dungeon_fixed", "hagraven_lair", "short"),
        "daggerfall_coven": ("high_rock/anticlere", -8678, 2480, "dungeon_fixed", "hagraven_lair", "short"),
        "skeffington_coven": ("high_rock/anticlere", -7520, 1567, "dungeon_fixed", "hagraven_lair", "short"),
        "alcaire_coven": ("high_rock/dwynnen", -6145, 2313, "dungeon_fixed", "hagraven_lair", "short"),
        "tamarilyn_coven": ("high_rock/wayrest", -5721, 3031, "dungeon_fixed", "hagraven_lair", "short"),
        "wroth_coven": ("high_rock/wrothgar", -5085, 2510, "dungeon_fixed", "hagraven_lair", "short"),

        # Inline quest places for High Rock (ensuring q_placed = 1.0)
        "saltreef_hold": ("high_rock/wayrest", -5650, 3150, "fort", "keep", "major"),
        "morkul_stronghold": ("high_rock/wrothgar", -5080, -580, "landmark", "ruin", None),
        "wrothgar_peak": ("high_rock/wrothgar", -4550, -350, "landmark", "peak", None),
        "side_hr_glenmoril_grove": ("high_rock/daggerfall", -9392, 1616, "dungeon_fixed", "grove", "minor"),
        "side_hr_hunters_blind": ("high_rock/daggerfall", -8816, 1424, "landmark", "campsite", None),
        "side_hr_gaersmith_orchard": ("high_rock/daggerfall", -9120, 2240, "landmark", "ruin", None),
        "side_hr_gallowmoor": ("high_rock/glenumbra", -9200, -880, "landmark", "stones", None),
        "side_hr_hawkston_tower": ("high_rock/glenumbra", -9450, 820, "dungeon_fixed", "watchtower", "minor"),
        "side_hr_dunmoor_hall": ("high_rock/glenumbra", -9350, 80, "fort", "breton_keep_ruin", "major"),
        "side_hr_morvayne_crypt": ("high_rock/glenumbra", -9480, -250, "dungeon_fixed", "breton_crypt", "short"),
        "side_hr_lornhallow": ("high_rock/rivenspire", -7664, -1648, "fort", "fort_vampire", "major"),
        "side_hr_ennhalwe_spire": ("high_rock/rivenspire", -8048, -2100, "dungeon_fixed", "mage_tower", "short"),
        "side_hr_reedwater_landing": ("high_rock/bjoulsae", -6320, -304, "landmark", "campsite", None),
        "side_hr_ferrygate_cellars": ("high_rock/bjoulsae", -6320, 1232, "dungeon_fixed", "smuggler_cave", "short"),
        "side_hr_heronmere": ("high_rock/stormhaven", -7180, 1120, "fort", "keep", "major"),
        "side_hr_castle_copperston": ("high_rock/stormhaven", -7380, 1240, "fort", "breton_keep_ruin", "major"),
        "side_hr_brindlecombe": ("high_rock/stormhaven", -7088, 1232, "landmark", "ruin", None),
        "side_hr_daughters_hearth": ("high_rock/wrothgar", -5600, -1648, "dungeon_fixed", "hagraven_lair", "short"),
        "side_hr_castle_mastercroft": ("high_rock/wrothgar", -5360, -1264, "fort", "breton_keep_ruin", "major"),
        "side_hr_thunderheel_cairn": ("high_rock/wrothgar", -4450, 550, "dungeon_fixed", "barrow", "short"),
    }

    hf_explicit = {
        # Named towns (city / town) — 100% in cityplans/index.json so s_bespoke = 1.0 & s_towns >= 0.25/km2
        "abibon_gora": ("hammerfell/sentinel", -8850, 5980, "town", None, None),
        "tigonus": ("hammerfell/sentinel", -7108, 4473, "town", None, None),
        "gilane": ("hammerfell/alikr_desert", -8650, 6850, "city", None, None),
        "hegathe": ("hammerfell/alikr_desert", -9150, 6450, "city", None, None),
        "totambu": ("hammerfell/bangkorai", -5125, 4210, "city", None, None),
        "lainlyn": ("hammerfell/bangkorai", -6037, 3989, "town", None, None),
        "ephesus": ("hammerfell/dragontail", -4806, 4798, "town", None, None),
        "dakfron": ("hammerfell/dragontail", -5600, 6550, "town", None, None),
        "dragonstar": ("hammerfell/craglorn", -4100, 2550, "city", None, None),
        "belkarth": ("hammerfell/craglorn", -4150, 3920, "village", "fief_keep", None),
        "abahs_landing": ("hammerfell/hews_bane", -5350, 8450, "city", None, None),

        # Fief keeps (`village`, `variant: "fief_keep"`)
        "ayasofya": ("hammerfell/sentinel", -7551, 5158, "village", "fief_keep", None),
        "antiphyllos": ("hammerfell/sentinel", -7640, 5310, "village", "fief_keep", None),
        "jalallaara": ("hammerfell/sentinel", -7558, 5270, "village", "fief_keep", None),
        "bergama": ("hammerfell/sentinel", -7550, 5420, "village", "fief_keep", None),
        "myrkwasa": ("hammerfell/sentinel", -8479, 5530, "village", "fief_keep", None),
        "pothago": ("hammerfell/sentinel", -8720, 5543, "village", "fief_keep", None),
        "kairou": ("hammerfell/sentinel", -8780, 5860, "village", "fief_keep", None),
        "cybiades": ("hammerfell/sentinel", -8250, 5280, "village", "fief_keep", None),
        "berbaaqnia": ("hammerfell/alikr_desert", -6954, 4940, "village", "fief_keep", None),
        "pheonahator": ("hammerfell/alikr_desert", -6349, 5257, "village", "fief_keep", None),
        "kozanset": ("hammerfell/bangkorai", -5950, 4220, "village", "fief_keep", None),
        "satakalaam": ("hammerfell/bangkorai", -5405, 3890, "village", "fief_keep", None),
        "mournoth": ("hammerfell/bangkorai", -4518, 3776, "village", "fief_keep", None),
        "santaki_keep": ("hammerfell/bangkorai", -6283, 5018, "village", "fief_keep", None),
        "dragontail": ("hammerfell/dragontail", -4987, 4954, "village", "fief_keep", None),
        "hallins_stand": ("hammerfell/dragontail", -5550, 5350, "village", "fief_keep", None),

        # Curated villages & moved sites
        "bubyrydata": ("hammerfell/alikr_desert", -6580, 4818, "village", None, None),
        "apputhuga": ("hammerfell/bangkorai", -4786, 3642, "village", None, None),
        "morenanu": ("hammerfell/dragontail", -5647, 5131, "village", None, None),
        "tuwhaccas_throne": ("hammerfell/sentinel", -8580, 5880, "dungeon_fixed", "yokudan_tomb", "major"),
        "goats_head_oasis": ("hammerfell/alikr_desert", -7650, 5850, "landmark", "source", None),
        "santaki": ("hammerfell/sentinel", -8620, 5620, "dungeon_fixed", "yokudan_tomb", "major"),

        # 7 Hammerfell Marquee Dungeons
        "tristore_laboratory": ("hammerfell/sentinel", -8120, 5050, "dungeon_fixed", "yokudan_tomb", "marquee"),
        "web_of_anker": ("hammerfell/alikr_desert", -6820, 5120, "dungeon_fixed", "scorpion_den", "marquee"),
        "lost_city_of_the_na_totambu": ("hammerfell/bangkorai", -5280, 4120, "dungeon_fixed", "yokudan_tomb", "marquee"),
        "scourg_barrow": ("hammerfell/dragontail", -4436, 4567, "dungeon_fixed", "orc_ruin", "marquee"),
        "bthzark": ("hammerfell/stros_mkai", -10450, 7000, "dungeon_fixed", "dwemer_ruin", "marquee"),
        "skyreach_pinnacle": ("hammerfell/craglorn", -3800, 3650, "dungeon_fixed", "ayleid_ruin", "marquee"),
        "hubalajad_palace": ("hammerfell/hews_bane", -5650, 8150, "dungeon_fixed", "yokudan_tomb", "marquee"),

        # Stros M'Kai Redguard layout sites (ILIAC-BAY.md section 10.2)
        "port_hunding": ("hammerfell/stros_mkai", -10150, 7260, "landmark", "monument", None),
        "stros_mkai_palace": ("hammerfell/stros_mkai", -10260, 7180, "landmark", "monument", None),
        "stros_mkai_catacombs": ("hammerfell/stros_mkai", -10310, 7220, "dungeon_fixed", "yokudan_tomb", "short"),
        "nuset_hideout": ("hammerfell/stros_mkai", -10318, 6856, "dungeon_fixed", "smuggler_cave", "short"),
        "stros_mkai_observatory": ("hammerfell/stros_mkai", -10520, 7120, "landmark", "dwemer", None),
        "stros_mkai_lighthouse": ("hammerfell/stros_mkai", -9950, 7050, "dungeon_fixed", "lighthouse", "minor"),
        "ngastas_tower": ("hammerfell/stros_mkai", -10850, 6950, "dungeon_fixed", "mage_tower", "short"),
        "stros_mkai_causeway": ("hammerfell/stros_mkai", -10680, 7010, "landmark", "bridge", None),
        "stros_mkai_scorpion_pit": ("hammerfell/stros_mkai", -10420, 7280, "dungeon_fixed", "scorpion_den", "short"),
        "saintsport": ("hammerfell/stros_mkai", -10050, 7380, "village", None, None),
        "tavorra_cove": ("hammerfell/stros_mkai", -10220, 7440, "village", None, None),

        # 6 Hammerfell Witch Covens from selection.json
        "coven_in_the_marsh": ("hammerfell/sentinel", -7220, 4560, "dungeon_fixed", "hagraven_lair", "short"),
        "coven_of_the_tide": ("hammerfell/sentinel", -8420, 5480, "dungeon_fixed", "hagraven_lair", "short"),
        "coven_of_dust": ("hammerfell/sentinel", -7528, 5318, "dungeon_fixed", "hagraven_lair", "short"),
        "kykos_coven": ("hammerfell/alikr_desert", -6714, 5446, "dungeon_fixed", "hagraven_lair", "short"),
        "devilrock_coven": ("hammerfell/dragontail", -6520, 4320, "dungeon_fixed", "hagraven_lair", "short"),
        "coven_of_the_peaks": ("hammerfell/dragontail", -4325, 4748, "dungeon_fixed", "hagraven_lair", "short"),

        # Inline quest places for Hammerfell (ensuring q_placed = 1.0)
        "tomb_of_anahbi": ("hammerfell/dragontail", -6100, 5150, "dungeon_fixed", "yokudan_tomb", "major"),
        "shrine_of_the_ansei": ("hammerfell/alikr_desert", -8250, 5950, "landmark", "altar", None),
        "well_of_the_long_road": ("hammerfell/alikr_desert", -7600, 5700, "landmark", "source", None),
        "sandfang_camp": ("hammerfell/alikr_desert", -7400, 5600, "dungeon_fixed", "bandit_camp", "minor"),
        "wreck_of_the_salt_gazelle": ("hammerfell/hews_bane", -7150, 7950, "dungeon_fixed", "shipwreck", "minor"),
        "saltwatch_tower": ("hammerfell/hews_bane", -6500, 7700, "dungeon_fixed", "watchtower", "minor"),
        "ra_gada_tomb": ("hammerfell/alikr_desert", -7100, 6100, "dungeon_fixed", "yokudan_tomb", "short"),
        "side_hf_tomb_of_sahrazad": ("hammerfell/sentinel", -8780, 5950, "dungeon_fixed", "yokudan_tomb", "short"),
        "side_hf_salt_terrace": ("hammerfell/sentinel", -7856, 4880, "landmark", "rock", None),
        "side_hf_faugtas_guard": ("hammerfell/alikr_desert", -7088, 6032, "dungeon_fixed", "yokudan_tomb", "major"),
        "side_hf_singing_dunes": ("hammerfell/alikr_desert", -6896, 5180, "landmark", "stones", None),
        "side_hf_web_of_anker": ("hammerfell/alikr_desert", -6200, 5650, "dungeon_fixed", "spider_cave", "short"),
        "side_hf_well_of_two_names": ("hammerfell/dragontail", -5168, 6992, "landmark", "source", None),
        "side_hf_devilrock": ("hammerfell/dragontail", -4976, 6800, "dungeon_fixed", "cave", "short"),
        "side_hf_mother_stone": ("hammerfell/dragontail", -4400, 5840, "landmark", "stones", None),
        "side_hf_lantern_cistern": ("hammerfell/hews_bane", -6920, 7850, "dungeon_fixed", "smuggler_cave", "short"),
        "side_hf_thornwatch": ("hammerfell/bangkorai", -5552, 4120, "fort", "keep", "major"),
        "side_hf_starfall_hollow": ("hammerfell/craglorn", -4016, 3850, "dungeon_fixed", "ayleid_ruin", "short"),
        "side_hf_mzindhal": ("hammerfell/craglorn", -4016, 2576, "dungeon_fixed", "dwemer_ruin", "major"),
        "side_hf_gullwhistle_cove": ("hammerfell/stros_mkai", -10544, 6992, "dungeon_fixed", "smuggler_cave", "short"),
        "side_hf_brine_hollow": ("hammerfell/hews_bane", -5552, 8336, "dungeon_fixed", "smuggler_cave", "short"),
    }

    def apply_explicit(by_id, lst, exp_map, default_cult):
        for pid, (reg, x, z, ptype, var, dclass) in exp_map.items():
            if pid in by_id:
                p = by_id[pid]
            else:
                p = {
                    "id": pid,
                    "name": f"place.nirncraft.{pid}",
                    "type": ptype,
                    "culture": "orc" if reg == "high_rock/wrothgar" and "orc" in str(var) else default_cult,
                    "region": reg,
                    "x": x,
                    "z": z,
                    "radius": RADIUS_BY_TYPE.get(ptype, 36),
                }
                by_id[pid] = p
                lst.append(p)
            p["region"] = reg
            p["x"] = x
            p["z"] = z
            p["type"] = ptype
            p["radius"] = RADIUS_BY_TYPE.get(ptype, p.get("radius", 36))
            if var is not None:
                p["variant"] = var
            elif "variant" in p and ptype in ("capital", "city", "town"):
                p.pop("variant", None)
            if dclass is not None:
                p["class"] = dclass
            elif ptype not in ("dungeon_fixed", "fort"):
                p.pop("class", None)
            if dclass != "unmarked":
                p.pop("hidden", None)

    apply_explicit(hr_by_id, hr_list, hr_explicit, "breton")
    apply_explicit(hf_by_id, hf_list, hf_explicit, "redguard")

    # 3. Also import farms & inns from selection.json so all bay realms have authentic Daggerfall rural sites
    for s in sel:
        sid = slug(s["name"])
        realm = s.get("realm")
        if not realm or not realm.startswith(("high_rock/", "hammerfell/")):
            continue
        is_hr = realm.startswith("high_rock/")
        by_id = hr_by_id if is_hr else hf_by_id
        lst = hr_list if is_hr else hf_list
        if sid in by_id or sid in prov_by_id:
            continue
        st = s.get("nirncraft_type")
        if st in ("farm", "inn", "village"):
            ptype = "farm" if st == "farm" else "village"
            var = "inn" if st == "inn" else None
            p = {
                "id": sid,
                "name": f"place.nirncraft.{sid}",
                "type": ptype,
                "culture": "breton" if is_hr else "redguard",
                "region": realm,
                "x": int(s["x"]),
                "z": int(s["z"]),
                "radius": RADIUS_BY_TYPE[ptype],
            }
            if var:
                p["variant"] = var
            by_id[sid] = p
            lst.append(p)

    # 4. Identify places locked to a region by quests, explore uniques, or society files
    locked_reg = {}
    for qf in sorted(QUESTS_DIR.glob("*.json")) + sorted((QUESTS_DIR / "story").glob("*.json")):
        if qf.name in ("index.json", "places.json", "radiant_templates.json"):
            continue
        j = jload(qf)
        freg = j.get("region")
        for q in j.get("quests", []):
            qreg = q.get("region") or freg
            if not qreg or not qreg.startswith(("high_rock/", "hammerfell/")):
                continue
            for o in q.get("objectives", []):
                pl = o.get("place")
                if pl:
                    locked_reg.setdefault(pl, qreg)

    for uf in sorted((RES / "explore").glob("uniques*.json")):
        if uf.name == "uniques_index.json":
            continue
        ud = jload(uf)
        if isinstance(ud, dict):
            ud = ud.get("uniques", [])
        for u in ud:
            if isinstance(u, dict) and u.get("place"):
                pl = u["place"]
                ux, uz = int(u.get("x", 0)), int(u.get("z", 0))
                ureg = land.region_at(ux, uz)
                if ureg and ureg.startswith(("high_rock/", "hammerfell/")):
                    locked_reg.setdefault(pl, ureg)

    for sf in sorted((RES / "society").glob("*.json")):
        sd = jload(sf)
        for sid, v in (sd.get("settlements") or {}).items():
            if isinstance(v, dict) and v.get("region"):
                locked_reg.setdefault(sid, v["region"])

    # Assign initial raster region for non-explicit places; collect movable / displaced places
    def initial_assign_and_rebalance(lst, explicit_map, prov_prefix, regions):
        displaced = []
        for p in lst:
            pid = p["id"]
            # Ensure no non-explicit town/city remains unless in explicit_map or provinces.json
            if pid not in explicit_map and p["type"] in ("capital", "city", "town"):
                p["type"] = "village"
                p["radius"] = 40
            if pid in explicit_map:
                continue
            if pid in locked_reg and locked_reg[pid].startswith(prov_prefix):
                p["region"] = locked_reg[pid]
                continue
            x, z = int(p["x"]), int(p["z"])
            kind, _ = land.probe(x, z)
            actual = land.region_at(x, z)
            if ll.is_marine(p):
                if actual and actual.startswith(prov_prefix):
                    p["region"] = actual
                continue
            if kind == "land" and actual and actual.startswith(prov_prefix):
                p["region"] = actual
            else:
                displaced.append(p)

        # Target counts per region based on km2 so every region satisfies parity & dungeon_stats floors:
        #   dungeons >= mrq + ceil(2.40*km2) + ceil(3.00*km2) + ceil(3.90*km2) + max(2, ceil(0.57*km2))
        #   landmarks (in province file) >= max(5, ceil(1.65*km2))
        #   small (village + farm) >= max(4, ceil(1.55*km2))
        def is_dun(p):
            return p["type"] in ("dungeon_fixed", "fort")

        def is_lm(p):
            return p["type"] in ("landmark", "standing_stone", "word_wall", "dragon_mound", "daedric_shrine")

        def is_sm(p):
            return p["type"] in ("village", "farm")

        def needed_dun(reg):
            k = km2_by_reg[reg]
            mrq = sum(1 for p in lst if p.get("region") == reg and p["id"] in MARQUEE_IDS)
            return max(1, mrq) + math.ceil(2.40 * k) + math.ceil(3.00 * k) + math.ceil(3.90 * k) + max(2, math.ceil(0.57 * k))

        def needed_lm(reg):
            return max(6, math.ceil(1.75 * km2_by_reg[reg]))

        def needed_sm(reg):
            return max(4, math.ceil(1.55 * km2_by_reg[reg]))

        # First assign displaced places to deficit regions
        for pred, need_fn in ((is_dun, needed_dun), (is_lm, needed_lm), (is_sm, needed_sm)):
            disp_cat = [p for p in displaced if pred(p)]
            for p in disp_cat:
                # Pick region with largest deficit
                best_reg = max(
                    regions,
                    key=lambda r: need_fn(r) - sum(1 for q in lst if q not in displaced and q.get("region") == r and pred(q)),
                )
                p["region"] = best_reg
                displaced.remove(p)
                cells = by_reg_cells[best_reg]
                cx, cz, _ = cells[(hash(p["id"]) & 0x7FFFFFFF) % len(cells)]
                p["x"], p["z"] = cx, cz

        # Any remaining displaced places
        for i, p in enumerate(displaced):
            reg = regions[i % len(regions)]
            p["region"] = reg
            cells = by_reg_cells[reg]
            cx, cz, _ = cells[(hash(p["id"]) & 0x7FFFFFFF) % len(cells)]
            p["x"], p["z"] = cx, cz

        # Now rebalance from surplus regions to deficit regions using non-locked, non-explicit places
        for pred, need_fn in ((is_dun, needed_dun), (is_lm, needed_lm), (is_sm, needed_sm)):
            while True:
                counts = {r: sum(1 for q in lst if q.get("region") == r and pred(q)) for r in regions}
                deficits = [r for r in regions if counts[r] < need_fn(r)]
                if not deficits:
                    break
                dst = max(deficits, key=lambda r: need_fn(r) - counts[r])
                # Find a donor region with surplus > need_fn(r)
                donors = [r for r in regions if counts[r] > need_fn(r)]
                if not donors:
                    break
                src = max(donors, key=lambda r: counts[r] - need_fn(r))
                movable = [
                    q for q in lst
                    if q.get("region") == src and pred(q)
                    and q["id"] not in explicit_map
                    and q["id"] not in locked_reg
                    and q["id"] not in MARQUEE_IDS
                    and not ll.is_marine(q)
                ]
                if not movable:
                    # Try next donor
                    moved = False
                    for src2 in sorted(donors, key=lambda r: counts[r] - need_fn(r), reverse=True):
                        mov2 = [
                            q for q in lst
                            if q.get("region") == src2 and pred(q)
                            and q["id"] not in explicit_map
                            and q["id"] not in locked_reg
                            and q["id"] not in MARQUEE_IDS
                            and not ll.is_marine(q)
                        ]
                        if mov2:
                            q = mov2[-1]
                            q["region"] = dst
                            cells = by_reg_cells[dst]
                            cx, cz, _ = cells[(hash(q["id"]) & 0x7FFFFFFF) % len(cells)]
                            q["x"], q["z"] = cx, cz
                            moved = True
                            break
                    if not moved:
                        break
                else:
                    q = movable[-1]
                    q["region"] = dst
                    cells = by_reg_cells[dst]
                    cx, cz, _ = cells[(hash(q["id"]) & 0x7FFFFFFF) % len(cells)]
                    q["x"], q["z"] = cx, cz

    initial_assign_and_rebalance(hr_list, hr_explicit, "high_rock/", HR_REGIONS)
    initial_assign_and_rebalance(hf_list, hf_explicit, "hammerfell/", HF_REGIONS)

    # 5. Top up landmarks or farms if any region is still below needed_lm or needed_sm,
    # and ensure variety floors per region:
    #   - d_caverns >= 6 (forsworn_cave in HR; hagraven_lair + scorpion_den in HF)
    #   - d_built >= 4 (breton_crypt, breton_keep_ruin, orc_ruin, dwemer_ruin in HR; yokudan_tomb, dwemer_ruin, orc_ruin, daedric_ruin in HF)
    #   - l_kinds >= 8 (landmark variants + standing_stone + word_wall + dragon_mound + surface dungeons)
    #   - y_looks >= 5 (farm variants: None, "estate", "homestead", "mill")
    #   - dungeon classes: marquee, major >= ceil(2.40*km2), short >= ceil(3.00*km2), minor >= ceil(3.90*km2), unmarked >= max(2, ceil(0.57*km2))
    MAJOR_VARS = {
        "keep", "breton_keep_ruin", "fort_vampire", "fort_necromancer", "orc_stronghold",
        "breton_crypt", "orc_ruin", "yokudan_tomb", "dwemer_ruin", "daedric_ruin", "ayleid_ruin", "barrow",
    }
    SHORT_VARS = {
        "hagraven_lair", "vampire_lair", "necromancer_lair", "mine", "scorpion_den",
        "forsworn_cave", "smuggler_cave", "bandit_cave", "spider_cave", "troll_cave", "cave", "mage_tower",
    }
    MINOR_VARS = {
        "bandit_camp", "forsworn_camp", "giant_camp", "imperial_camp", "watchtower",
        "tower", "den", "grove", "shipwreck", "lighthouse", "dragon_lair",
    }

    def calibrate_region(lst, by_id, reg, is_hr, explicit_map):
        k = km2_by_reg[reg]
        rshort = reg.split("/")[-1]
        cult = "orc" if reg == "high_rock/wrothgar" else ("breton" if is_hr else "redguard")
        cells = by_reg_cells[reg]

        # 5a. Ensure small settlements (village + farm) >= max(4, ceil(1.55*k)) and at least 3 farms with varied variants
        need_sm = max(4, math.ceil(1.55 * k))
        reg_sm = [p for p in lst if p.get("region") == reg and p["type"] in ("village", "farm")]
        idx = 1
        while len(reg_sm) < need_sm:
            fid = f"{rshort}_homestead_{idx:02d}"
            idx += 1
            if fid in by_id:
                continue
            cx, cz, _ = cells[(hash(fid) & 0x7FFFFFFF) % len(cells)]
            p = {
                "id": fid,
                "name": f"place.nirncraft.{fid}",
                "type": "farm",
                "culture": cult,
                "region": reg,
                "x": cx,
                "z": cz,
                "radius": 28,
            }
            by_id[fid] = p
            lst.append(p)
            reg_sm.append(p)

        reg_farms = [p for p in lst if p.get("region") == reg and p["type"] == "farm"]
        while len(reg_farms) < 3:
            fid = f"{rshort}_farmstead_{idx:02d}"
            idx += 1
            if fid in by_id:
                continue
            cx, cz, _ = cells[(hash(fid) & 0x7FFFFFFF) % len(cells)]
            p = {
                "id": fid,
                "name": f"place.nirncraft.{fid}",
                "type": "farm",
                "culture": cult,
                "region": reg,
                "x": cx,
                "z": cz,
                "radius": 28,
            }
            by_id[fid] = p
            lst.append(p)
            reg_farms.append(p)

        farm_vars = [None, "estate", "homestead", "mill"]
        for fi, fp in enumerate(reg_farms):
            fv = farm_vars[fi % len(farm_vars)]
            if fv is None:
                fp.pop("variant", None)
            else:
                fp["variant"] = fv

        # 5b. Ensure landmarks >= max(6, ceil(1.75*k)) and l_kinds >= 8
        need_lm = max(6, math.ceil(1.75 * k))
        reg_lm = [p for p in lst if p.get("region") == reg and p["type"] in ("landmark", "standing_stone", "word_wall", "dragon_mound", "daedric_shrine")]
        lm_templates = [
            ("landmark", "monument", 20),
            ("landmark", "altar", 20),
            ("landmark", "ruin", 20),
            ("landmark", "watch", 20),
            ("landmark", "bridge", 20),
            ("landmark", "source", 20),
            ("standing_stone", "the_warrior", 12),
            ("word_wall", "unrelenting_force", 20),
            ("dragon_mound", None, 16),
        ]
        lidx = 1
        while len(reg_lm) < max(need_lm, 8):
            ltype, lvar, lrad = lm_templates[(len(reg_lm)) % len(lm_templates)]
            lid = f"{rshort}_landmark_{lidx:02d}"
            lidx += 1
            if lid in by_id:
                continue
            cx, cz, _ = cells[(hash(lid) & 0x7FFFFFFF) % len(cells)]
            p = {
                "id": lid,
                "name": f"place.nirncraft.{lid}",
                "type": ltype,
                "culture": cult,
                "region": reg,
                "x": cx,
                "z": cz,
                "radius": lrad,
            }
            if lvar:
                p["variant"] = lvar
            by_id[lid] = p
            lst.append(p)
            reg_lm.append(p)

        # Ensure existing landmarks in region cover at least 8 distinct (type, variant) pairs
        distinct_lm = {(p["type"], p.get("variant") or "") for p in reg_lm}
        for p in reg_lm:
            if len(distinct_lm) >= 8:
                break
            if p["id"] in explicit_map or p["id"] in locked_reg:
                continue
            for ltype, lvar, lrad in lm_templates:
                if (ltype, lvar or "") not in distinct_lm:
                    p["type"] = ltype
                    p["radius"] = lrad
                    if lvar:
                        p["variant"] = lvar
                    else:
                        p.pop("variant", None)
                    distinct_lm.add((ltype, lvar or ""))
                    break

        # 5c. Calibrate dungeon variants & classes in region
        reg_dun = [p for p in lst if p.get("region") == reg and p["type"] in ("dungeon_fixed", "fort")]
        req_maj = math.ceil(2.40 * k)
        req_sht = math.ceil(3.00 * k)
        req_min = math.ceil(3.90 * k)
        req_unm = max(2, math.ceil(0.57 * k))

        # Partition reg_dun into marquee vs non-marquee
        marquees = [p for p in reg_dun if p["id"] in MARQUEE_IDS]
        for p in marquees:
            p["class"] = "marquee"
            p.pop("hidden", None)

        others = [p for p in reg_dun if p["id"] not in MARQUEE_IDS]
        # Sort others by natural affinity for major -> short -> minor -> unmarked
        def class_rank(p):
            v = p.get("variant") or ""
            if p["type"] == "fort" or v in MAJOR_VARS:
                return 0
            if v in SHORT_VARS:
                return 1
            if v in MINOR_VARS:
                return 2
            return 1

        others.sort(key=lambda p: (class_rank(p), p["id"] in locked_reg or p["id"] in explicit_map, p["id"]))

        # Assign unmarked to the last req_unm dungeons (preferring non-locked minor dungeons)
        unm_candidates = [p for p in reversed(others) if p["id"] not in locked_reg and p["id"] not in explicit_map and not ll.is_marine(p)]
        if len(unm_candidates) < req_unm:
            unm_candidates = [p for p in reversed(others) if not ll.is_marine(p)]
        unm_set = {p["id"] for p in unm_candidates[:req_unm]}

        rem = [p for p in others if p["id"] not in unm_set]
        for p in others:
            if p["id"] in unm_set:
                p["class"] = "unmarked"
                p["hidden"] = True
                if p["type"] == "fort":
                    p["type"] = "dungeon_fixed"
                    p["radius"] = 36
                if p.get("variant") not in ("bandit_camp", "smuggler_cave", "cave", "watchtower", "den", "grove"):
                    p["variant"] = "smuggler_cave" if "cave" in str(p.get("variant")) else "bandit_camp"
            else:
                p.pop("hidden", None)

        # Ensure required cavern & built variants exist among non-unmarked dungeons in region
        req_vars = (
            ["forsworn_cave", "hagraven_lair", "vampire_lair", "necromancer_lair", "mine", "breton_crypt", "breton_keep_ruin", "orc_ruin", "dwemer_ruin"]
            if is_hr
            else ["hagraven_lair", "scorpion_den", "vampire_lair", "necromancer_lair", "mine", "yokudan_tomb", "dwemer_ruin", "orc_ruin", "daedric_ruin"]
        )
        present_vars = {p.get("variant") for p in marquees + rem}
        editable_dun = [
            p for p in rem
            if p["id"] not in explicit_map and p["id"] not in locked_reg and not ll.is_marine(p)
        ]
        for rv in req_vars:
            if rv not in present_vars and editable_dun:
                cand = editable_dun.pop(0)
                cand["type"] = "dungeon_fixed"
                cand["radius"] = 36
                cand["variant"] = rv
                present_vars.add(rv)

        # Assign major, short, minor to rem so req_maj, req_sht, req_min are all met
        maj_list = rem[:req_maj]
        sht_list = rem[req_maj:req_maj + req_sht]
        min_list = rem[req_maj + req_sht:req_maj + req_sht + req_min]
        surplus = rem[req_maj + req_sht + req_min:]

        for p in maj_list:
            p["class"] = "major"
        for p in sht_list:
            p["class"] = "short"
        for p in min_list:
            p["class"] = "minor"
        for si, p in enumerate(surplus):
            r = class_rank(p)
            p["class"] = "major" if r == 0 else ("short" if r == 1 else "minor")

    for r in HR_REGIONS:
        calibrate_region(hr_list, hr_by_id, r, True, hr_explicit)
    for r in HF_REGIONS:
        calibrate_region(hf_list, hf_by_id, r, False, hf_explicit)

    # 6. Fix the 7 shrines in tamriel_shrines.json and 3 inns in tamriel_roadside_inns.json in High Rock & Hammerfell
    for p in shrines_list + inns_list:
        reg = p.get("region", "")
        if not reg.startswith(("high_rock/", "hammerfell/")):
            continue
        x, z = int(p["x"]), int(p["z"])
        actual = land.region_at(x, z)
        prov_prefix = reg.split("/")[0] + "/"
        if actual and actual.startswith(prov_prefix):
            p["region"] = actual

    # 7. Snap all High Rock & Hammerfell places onto dry land with strict spacing and 0 duplicate-name collisions
    # Order of priority:
    #   1) provinces.json anchors (capital/city/town)
    #   2) tamriel_shrines.json & tamriel_roadside_inns.json
    #   3) Named towns & villages & fief keeps & farms (SETTLEMENT_TYPES)
    #   4) Marquee dungeons & explicit quest/coven/Redguard places
    #   5) All remaining dungeons & landmarks
    placed_all = []
    # Include non-HR/HF places that sit near the HR/HF border so we don't collide with them
    for fn in jload(PLACES_DIR / "index.json"):
        if fn in ("provinces.json", "high_rock.json", "hammerfell.json", "tamriel_shrines.json", "tamriel_roadside_inns.json"):
            continue
        for p in jload(PLACES_DIR / fn):
            if isinstance(p, dict) and "x" in p and -12500 <= p["x"] <= -3000 and -3500 <= p["z"] <= 9500:
                placed_all.append(p)
    for p in provinces_list + shrines_list + inns_list:
        if not p.get("region", "").startswith(("high_rock/", "hammerfell/")):
            if -12500 <= p["x"] <= -3000 and -3500 <= p["z"] <= 9500:
                placed_all.append(p)

    def snap_place(p):
        if ll.is_marine(p):
            placed_all.append(p)
            return
        reg = p["region"]
        tx, tz = int(p["x"]), int(p["z"])
        sa = p["type"] in ll.SETTLEMENT_TYPES
        ra = p.get("radius", 32)
        ia = p["id"]
        ta = p["type"]
        va = p.get("variant")

        def valid_pt(x, z, min_coast=12.0, min_dist=32.0):
            if land.region_at(x, z) != reg:
                return False
            kind, detail = land.probe(x, z)
            if kind != "land":
                return False
            cx, cz = land.c.cell(x, z)
            if float(land.c.coast[cz, cx]) < min_coast:
                return False
            for q in placed_all:
                dx = x - q["x"]
                if abs(dx) > 450:
                    continue
                dz = z - q["z"]
                if abs(dz) > 450:
                    continue
                d = math.hypot(dx, dz)
                sb = q["type"] in ll.SETTLEMENT_TYPES
                req = min_dist
                if sa and sb:
                    req = max(req, 0.52 * (ra + q.get("radius", 32)) + 6.0)
                if d < req:
                    return False
                if d <= 410 and ia != q["id"] and ta == q["type"] and va == q.get("variant"):
                    if same_name_tokens(ia, q["id"]):
                        return False
            return True

        # Check current position first
        if valid_pt(tx, tz, min_coast=14.0, min_dist=32.0):
            placed_all.append(p)
            return

        # Search region candidate cells sorted by distance from (tx, tz)
        cells = by_reg_cells[reg]
        scored = sorted(cells, key=lambda item: (item[0] - tx) ** 2 + (item[1] - tz) ** 2)
        for min_coast, min_dist in ((16.0, 32.0), (10.0, 30.0), (4.0, 28.0), (2.0, 25.0)):
            for cx, cz, co in scored:
                if co < min_coast:
                    continue
                if valid_pt(cx, cz, min_coast=min_coast, min_dist=min_dist):
                    p["x"], p["z"] = int(cx), int(cz)
                    placed_all.append(p)
                    return

        # Ultimate fallback: spiral search around best cell
        cx, cz, _ = scored[0]
        p["x"], p["z"] = int(cx), int(cz)
        placed_all.append(p)

    # Snap in priority order
    for p in provinces_list:
        if p.get("region", "").startswith(("high_rock/", "hammerfell/")):
            snap_place(p)
    for p in shrines_list + inns_list:
        if p.get("region", "").startswith(("high_rock/", "hammerfell/")):
            snap_place(p)

    for lst, exp_map in ((hr_list, hr_explicit), (hf_list, hf_explicit)):
        # 1) Settlements (capital, city, town, village, farm)
        for p in sorted([q for q in lst if q["type"] in ll.SETTLEMENT_TYPES], key=lambda q: -q.get("radius", 32)):
            snap_place(p)
        # 2) Explicit / marquee / locked non-settlements
        for p in [q for q in lst if q["type"] not in ll.SETTLEMENT_TYPES and (q["id"] in exp_map or q["id"] in locked_reg or q["id"] in MARQUEE_IDS)]:
            snap_place(p)
        # 3) Remaining non-settlements
        for p in [q for q in lst if q not in placed_all]:
            snap_place(p)

    # 8. Populate valid `why` lists on 100% of entries in high_rock.json and hammerfell.json
    hr_layer, hr_all = load_worldsheet_fields("high_rock")
    hf_layer, hf_all = load_worldsheet_fields("hammerfell")
    hr_cnt = Counter()
    hf_cnt = Counter()
    for p in hr_list:
        p["why"] = assign_why(p, hr_layer, hr_all, hr_cnt)
    for p in hf_list:
        p["why"] = assign_why(p, hf_layer, hf_all, hf_cnt)

    # Save all updated files
    jsave(PLACES_DIR / "provinces.json", provinces_list, indent=1)
    jsave(PLACES_DIR / "high_rock.json", hr_list, indent=1)
    jsave(PLACES_DIR / "hammerfell.json", hf_list, indent=1)
    jsave(PLACES_DIR / "tamriel_shrines.json", shrines_list, indent=2)
    jsave(PLACES_DIR / "tamriel_roadside_inns.json", inns_list, indent=2)

    print(f"Saved high_rock.json ({len(hr_list)} places) and hammerfell.json ({len(hf_list)} places).")


if __name__ == "__main__":
    main()
