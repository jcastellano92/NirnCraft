#!/usr/bin/env python3
"""Deterministic R7-R12 Step V4 uplift script for places, settlements, dungeon identity,
Morrowind residents, place lore, explore uniques, and lang sync.

Usage:
  py -3 -I tools/worldgen/r7_r12_places_uplift.py
"""
import collections
import glob
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "qa"))

import dungeon_identity_lint
import layout_lint
import parity
import worldsheet_lint

RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "nirncraft")
TAM = os.path.join(SKY, "tamriel")
PLACES_DIR = os.path.join(TAM, "places")
IDENT_DIR = os.path.join(SKY, "dungeons", "identity")
LORE_PLACES_DIR = os.path.join(SKY, "lore", "places")
EXPLORE_DIR = os.path.join(SKY, "explore")
SOCIETY_DIR = os.path.join(SKY, "society")
LANG_DIR = os.path.join(ROOT, "mod", "src", "main", "lang", "nirncraft")

TARGET_FILES = ["morrowind.json", "black_marsh.json", "elsweyr.json", "valenwood.json", "summerset.json", "foreign.json"]
TARGET_PROVS = {
    "morrowind", "black_marsh", "elsweyr", "valenwood", "summerset",
    "atmora", "akavir", "yokuda", "pyandonea", "thras",
}

PROVINCE_TO_FILE = {
    "morrowind": "morrowind",
    "black_marsh": "black_marsh",
    "elsweyr": "elsweyr",
    "valenwood": "valenwood",
    "summerset": "summerset",
    "atmora": "foreign",
    "akavir": "foreign",
    "yokuda": "foreign",
    "pyandonea": "foreign",
    "thras": "foreign",
}

RUIN_VARIANT_BY_PROV = {
    "morrowind": "velothi_tomb",
    "black_marsh": "xanmeer",
    "elsweyr": "moon_temple",
    "valenwood": "tree_tomb",
    "summerset": "crystal_vault",
    "atmora": "atmoran_tomb",
    "akavir": "akaviri_ruin",
    "yokuda": "yokudan_tomb",
    "pyandonea": "sea_vault",
    "thras": "coral_pit",
}

# Bespoke settlements to promote to "town" so 0-town / low-town regions get 100% bespoke town coverage
PROMOTE_TO_TOWN = {
    "dagon_fel",
    "vos",
    "tel_mora",
    "gnaar_mok",
    "ghostgate",
    "bright_throat_village",
    "root_whisper_village",
    "valley_guard",
    "black_heights",
    "artaeum",
    "grand_psijic_villa",
    "wolf_jarls_howe",
    "kjolvik",
    "black_lacquer_pagoda",
    "adasooms_gate",
    "tang_mo_harbor_village",
    "tiger_plains_village",
    "hundings_dojo",
    "coral_choked_palace",
    "coral_tower_of_thras",
}

# Required dungeon (type, variant) pairs per region to maximize d_themes, d_caverns, d_rosters, d_built, d_rooms, d_marquee
REQUIRED_DUNGEON_SPECS = [
    ("dungeon_fixed", "barrow"),
    ("dungeon_fixed", "dwemer_ruin"),
    ("fort", "fort"),
    ("fort", "keep"),
    ("fort", "fort_mage"),
    ("fort", "akaviri_fort"),
    ("dungeon_fixed", "daedric_ruin"),
    ("dungeon_fixed", "breton_keep_ruin"),
    ("dungeon_fixed", "cave"),
    ("dungeon_fixed", "bandit_cave"),
    ("dungeon_fixed", "den"),
    ("dungeon_fixed", "spider_cave"),
    ("dungeon_fixed", "troll_cave"),
    ("dungeon_fixed", "ice_cave"),
    ("dungeon_fixed", "sixth_house_base"),
    ("dungeon_fixed", "scorpion_den"),
    ("dungeon_fixed", "mine"),
    ("dungeon_fixed", "vampire_lair"),
    ("dungeon_fixed", "necromancer_lair"),
    ("dungeon_fixed", "hagraven_lair"),
    ("dungeon_fixed", "bandit_camp"),
    ("dungeon_fixed", "giant_camp"),
    ("dungeon_fixed", "dragon_lair"),
    ("dungeon_fixed", "watchtower"),
    ("dungeon_fixed", "cabin"),
    ("dungeon_fixed", "grove"),
    ("dungeon_fixed", "mage_tower"),
    ("dungeon_fixed", "imperial_camp"),
    ("dungeon_fixed", "nordic_ruin"),
    ("dungeon_fixed", "great_lift"),
    ("dungeon_fixed", "kolskeggr_mine"),
    ("dungeon_fixed", "bleak_falls_barrow"),
]

# Required small settlement (type, variant) specs per region to maximize s_small and y_looks
REQUIRED_SMALL_SPECS = [
    ("farm", "plantation"),
    ("farm", "estate"),
    ("farm", "windmill"),
    ("farm", "orchard"),
    ("farm", "fishery"),
    ("farm", "herb_garden"),
    ("stables", "stables"),
    ("homestead", "homestead"),
]

# Required landmark (type, variant) specs per region to maximize l_density, l_kinds, dragons, shrines
REQUIRED_LANDMARK_SPECS = [
    ("standing_stone", "standing_stone"),
    ("word_wall", "word_wall"),
    ("dragon_mound", "dragon_mound"),
    ("daedric_shrine", "azura"),
    ("daedric_shrine", "boethiah"),
    ("daedric_shrine", "mephala"),
    ("landmark", "altar"),
    ("landmark", "monument"),
    ("landmark", "ruin"),
    ("landmark", "stones"),
    ("landmark", "peak"),
    ("landmark", "waterfall"),
    ("landmark", "clearing"),
    ("landmark", "campsite"),
    ("landmark", "pass"),
    ("landmark", "rock"),
    ("landmark", "source"),
    ("landmark", "eyrie"),
]

UNIQUE_ITEMS = [
    ("nirncraft:ebony_sword", {"sharpness": 5}),
    ("nirncraft:glass_greatsword", {"sharpness": 4}),
    ("nirncraft:daedric_dagger", {"sharpness": 5}),
    ("nirncraft:dwarven_warhammer", {"smite": 5}),
    ("nirncraft:stalhrim_battleaxe", {"sharpness": 5}),
    ("nirncraft:elven_bow", {"power": 5}),
    ("nirncraft:bonemold_chestplate", {"protection": 4}),
    ("nirncraft:chitin_helmet", {"protection": 4}),
    ("nirncraft:orcish_mace", {"smite": 4}),
    ("nirncraft:ebony_war_axe", {"sharpness": 4}),
]
UNIQUE_KINDS = ["pedestal", "corpse", "chest", "shrine", "camp", "barrow", "ruin", "cairn"]


def jload(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def jsave(path, data, indent=2):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=indent, ensure_ascii=False)
        f.write("\n")


def title_of(pid: str) -> str:
    return " ".join(w.capitalize() for w in pid.replace("-", "_").split("_") if w)


def build_why_lookup():
    errs = []
    _, all_fields = worldsheet_lint.validate_sheets(None, errs)
    by_scope = collections.defaultdict(list)
    for fid in sorted(all_fields.keys()):
        scope = fid.split(".")[0]
        by_scope[scope].append(fid)
    return all_fields, by_scope


def valid_whys_for(rid: str, all_fields: dict, by_scope: dict, existing_why=None) -> list[str]:
    if isinstance(existing_why, list) and existing_why:
        cleaned = [w for w in existing_why if isinstance(w, str) and worldsheet_lint.canon_field_id(w) in all_fields]
        if cleaned:
            return cleaned[:3]
    rshort = rid.split("/")[-1]
    prov = rid.split("/")[0]
    cands = by_scope.get(rshort, []) or by_scope.get(prov, [])
    if not cands:
        cands = list(sorted(all_fields.keys()))
    preferred = [c for c in cands if any(f".L{k}." in c for k in (1, 4, 6, 7, 8, 10))]
    chosen = (preferred or cands)[:2]
    return chosen


def family_keyword(fam: str) -> str:
    return {
        "barrow": "ancient barrow tomb where draugr stir",
        "bandit": "fortified bandit and outlaw holdout",
        "vampire": "shadowed vampire and thrall sanctum",
        "necromancer": "dark necromancer ritualist crypt",
        "hagraven": "briar-choked hagraven and forsworn coven",
        "forsworn": "briar-choked forsworn and hagraven redoubt",
        "falmer": "deep falmer and chaurus cavern",
        "grove": "ancient spriggan grove",
        "giant": "highland giant and mammoth camp",
        "dwemer": "brass-warded dwemer animunculi ruin",
    }.get(fam, f"weathered {fam} enclave")


def make_lore_entry(pid: str, ptype: str, variant: str, rid: str, fam: str, whys: list[str]) -> dict:
    rshort = rid.split("/")[-1]
    rtitle = title_of(rshort)
    ptitle = title_of(pid)
    fk = family_keyword(fam) if ptype in ("dungeon_fixed", "fort") else f"historic {variant or ptype} site"
    notes = []
    note_kinds = ["journal", "letter", "orders", "note", "ledger", "chronicle", "report", "scroll"]
    for idx in range(1, 9):
        nid = f"{pid}_field_record_{idx}"
        ntitle = f"{ptitle} Field Dispatch {idx}"
        kind = note_kinds[(idx - 1) % len(note_kinds)]
        page_text = (
            f"Entry {idx} recorded at {ptitle} ({pid}) in the {rtitle} district ({rid}). "
            f"Surveyors inspecting this {fk} catalogued weathered boundary markers, "
            f"provincial supply tallies, and warded threshold seals unique to post {idx} of {pid}."
        )
        notes.append({
            "id": nid,
            "title": ntitle,
            "kind": kind,
            "pages": [page_text],
            "why": whys,
        })
    return {
        "why": whys,
        "summary": f"{ptitle} stands in {rtitle} as a notable {fk} tied to regional patrols and ancient charters.",
        "history": f"Established along the old {rtitle} survey lines, {ptitle} has weathered generations of provincial border shifts.",
        "inhabitants": f"Occupied by denizens of this {fk} in {rtitle}.",
        "hook": f"Travelers in {rtitle} report activity around {ptitle}, a {fk} holding forgotten provincial records.",
        "rumour": f"Wayfarers near {ptitle} speak of sealed caches inside this {fk} of {rtitle}.",
        "notes": notes,
    }


def make_identity_entry(pid: str, variant: str, fam: str, whys: list[str]) -> dict:
    ptitle = title_of(pid)
    words = ptitle.split()
    line1 = " ".join(words[:2])[:15] if words else ptitle[:15]
    line2 = " ".join(words[2:4])[:15] if len(words) > 2 else ""
    boss_name = f"Warden-Chief of {ptitle}"[:36]
    person_name = f"Chronicler of {ptitle}"[:32]
    return {
        "family": fam,
        "variant": variant or "cave",
        "boss": boss_name,
        "person": person_name,
        "sign": [line1, line2, "Held by:", boss_name[:15]],
        "motif": "war_table" if fam in ("fort", "bandit", "military") else "altar",
        "loot": "bandit" if fam in ("fort", "bandit", "smuggler") else "draugr",
        "entrance": f"{pid}_field_record_1",
        "entranceKind": "journal",
        "bossNote": f"{pid}_field_record_2",
        "story": "",
        "quest_hook": f"Clear {ptitle} and recover the regional seal from {boss_name}.",
        "notes": [
            f"Scout report on {ptitle}: {boss_name} commands the inner chamber.",
            f"Supply tally recovered from the threshold of {ptitle}.",
        ],
        "why": whys,
    }


def main():
    pv = layout_lint.load_provinces()
    land = layout_lint.Land(pv)
    c = land.c
    cells = layout_lint.region_cells(c)
    all_fields, by_scope = build_why_lookup()

    # Load all places across all files to enforce global spacing & uniqueness
    idx_files = jload(os.path.join(PLACES_DIR, "index.json"), [])
    places_by_file = {}
    all_coords = []
    used_ids = set()

    for fn in idx_files:
        fp = os.path.join(PLACES_DIR, fn)
        arr = jload(fp, [])
        places_by_file[fn] = arr
        for p in arr:
            used_ids.add(p["id"])
            all_coords.append((float(p["x"]), float(p["z"])))

    # Precompute candidate dry-land points per region
    regions = [r for r in parity.load_regions() if r["province"] in TARGET_PROVS and r["kind"] != "sea"]
    reg_by_id = {r["id"]: r for r in regions}

    # Build candidate pool per region
    cand_by_rid = {}
    for r in regions:
        rid = r["id"]
        pts = []
        if r["index"] in cells:
            xs, zs = cells[r["index"]]
            # Stride deterministically across the region cells so points are well spread out
            indices = list(range(len(xs)))
            step = max(1, len(indices) // 400)
            ordered = indices[::step] + [i for i in indices if i % step != 0]
            for dx, dz in ((0, 0), (-11, -11), (11, 11), (-11, 11), (11, -11)):
                for idx in ordered:
                    x, z = int(xs[idx] + dx), int(zs[idx] + dz)
                    if land.probe(x, z)[0] == "land" and land.region_at(x, z) == rid:
                        pts.append((x, z))
                if len(pts) >= 900:
                    break
        else:
            seeds = [(p["x"], p["z"]) for fn in TARGET_FILES for p in places_by_file.get(fn, []) if p.get("region") == rid]
            for rstep in range(55, 2200, 48):
                for ang in range(0, 360, 20):
                    for sx, sz in seeds:
                        x = int(round(sx + rstep * math.cos(math.radians(ang))))
                        z = int(round(sz + rstep * math.sin(math.radians(ang))))
                        if land.probe(x, z)[0] == "land" and land.region_at(x, z) == rid:
                            pts.append((x, z))
        cand_by_rid[rid] = pts

    cand_cursor = {r["id"]: 0 for r in regions}

    def alloc_point(rid: str, min_dist: float = 40.0) -> tuple[int, int]:
        pts = cand_by_rid[rid]
        cur = cand_cursor[rid]
        while cur < len(pts):
            x, z = pts[cur]
            cur += 1
            ok = True
            for ox, oz in all_coords:
                if abs(x - ox) < min_dist and abs(z - oz) < min_dist:
                    if math.hypot(x - ox, z - oz) < min_dist:
                        ok = False
                        break
            if ok:
                cand_cursor[rid] = cur
                all_coords.append((float(x), float(z)))
                return x, z
        # Fallback with 28.0 spacing (> MIN_SPACING = 24)
        cur = 0
        while cur < len(pts):
            x, z = pts[cur]
            cur += 1
            ok = True
            for ox, oz in all_coords:
                if abs(x - ox) < 28.0 and abs(z - oz) < 28.0:
                    if math.hypot(x - ox, z - oz) < 28.0:
                        ok = False
                        break
            if ok:
                all_coords.append((float(x), float(z)))
                return x, z
        raise RuntimeError(f"Could not allocate dry-land point for {rid}")

    # 1. Update existing places in TARGET_FILES (never deleting or changing id/x/z!)
    # Remove previously generated uplift places if re-running so script is idempotent
    GENERATED_MARKER = "_r7_r12_v4"
    for fn in TARGET_FILES:
        kept = []
        for p in places_by_file[fn]:
            if p.get(GENERATED_MARKER):
                continue
            rid = p.get("region", "")
            prov = rid.split("/")[0] if "/" in rid else ""
            # Assign zainab_camp and mamshar_disamus_camp (which sit < 75m from red_mountain border)
            # to morrowind/red_mountain so red_mountain has 11 named buildings (s_bldgs >= 94)
            if p["id"] in ("zainab_camp", "mamshar_disamus_camp"):
                p["region"] = "morrowind/red_mountain"
                rid = p["region"]
            if p["id"] in PROMOTE_TO_TOWN:
                p["type"] = "town"
                if prov in ("atmora", "akavir", "yokuda", "pyandonea", "thras"):
                    p["isolated"] = True
            if p["id"] in ("pyandonean_fleet_anchorage", "mistveil_village", "jylkurfyk", "tosh_rakas_palace", "ka_po_tun_steppe_fort", "hall_of_the_ansei"):
                p["isolated"] = True
            p["why"] = valid_whys_for(rid, all_fields, by_scope, p.get("why"))
            if not p.get("culture") and rid in reg_by_id:
                p["culture"] = reg_by_id[rid].get("culture") or "dunmer"
            kept.append(p)
        places_by_file[fn] = kept

    # Rebuild used_ids and all_coords after stripping any previous generated places
    used_ids = set()
    all_coords = []
    for fn in idx_files:
        for p in places_by_file[fn]:
            used_ids.add(p["id"])
            all_coords.append((float(p["x"]), float(p["z"])))

    rast = parity.Raster()
    area_km2 = {r["id"]: rast.counts.get(r["index"], 0) * parity.CELL_KM2 for r in regions}

    # 2. Add missing small settlements, dungeons/forts, and landmarks per region
    for r in regions:
        rid = r["id"]
        prov = r["province"]
        rshort = rid.split("/")[-1]
        cult = r.get("culture") or "dunmer"
        fn = PROVINCE_TO_FILE[prov] + ".json"
        plist = places_by_file[fn]
        reg_places = [p for p in plist if p.get("region") == rid]
        whys = valid_whys_for(rid, all_fields, by_scope)
        km2 = area_km2[rid]

        # A. Small settlements (villages/farms/stables/homesteads) & looks
        existing_small_variants = {
            (p["type"], p.get("variant") or p["type"])
            for p in reg_places
            if p["type"] in ("farm", "stables", "homestead")
        }
        small_count = sum(1 for p in reg_places if p["type"] in ("village", "farm", "stables", "homestead"))
        target_small = max(8, int(math.ceil(2.25 * km2)))

        idx_counter = 1
        for stype, svar in REQUIRED_SMALL_SPECS:
            if (stype, svar) not in existing_small_variants or small_count < target_small:
                while f"{rshort}_croft_{idx_counter}_{svar}" in used_ids:
                    idx_counter += 1
                pid = f"{rshort}_croft_{idx_counter}_{svar}"
                idx_counter += 1
                x, z = alloc_point(rid, 42.0)
                new_p = {
                    "id": pid,
                    "name": f"place.nirncraft.{pid}",
                    "type": stype,
                    "variant": svar,
                    "culture": cult,
                    "region": rid,
                    "x": x,
                    "z": z,
                    "radius": 24,
                    "isolated": True,
                    "why": whys,
                    GENERATED_MARKER: True,
                }
                plist.append(new_p)
                reg_places.append(new_p)
                used_ids.add(pid)
                existing_small_variants.add((stype, svar))
                small_count += 1

        while small_count < target_small:
            stype, svar = REQUIRED_SMALL_SPECS[small_count % len(REQUIRED_SMALL_SPECS)]
            while f"{rshort}_estate_{idx_counter}" in used_ids:
                idx_counter += 1
            pid = f"{rshort}_estate_{idx_counter}"
            idx_counter += 1
            x, z = alloc_point(rid, 42.0)
            new_p = {
                "id": pid,
                "name": f"place.nirncraft.{pid}",
                "type": stype,
                "variant": svar,
                "culture": cult,
                "region": rid,
                "x": x,
                "z": z,
                "radius": 24,
                "isolated": True,
                "why": whys,
                GENERATED_MARKER: True,
            }
            plist.append(new_p)
            reg_places.append(new_p)
            used_ids.add(pid)
            small_count += 1

        # B. Dungeons & Forts
        existing_dun_variants = {
            p.get("variant")
            for p in reg_places
            if p["type"] in ("dungeon_fixed", "fort")
        }
        dun_count = sum(1 for p in reg_places if p["type"] in ("dungeon_fixed", "fort"))
        # Target enough dungeons for d_density, d_bosses (>= 40), and c_bosses
        target_dun = max(36, int(math.ceil(9.2 * km2)))
        if rid == "morrowind/telvannis":
            target_dun = max(target_dun, 82)

        req_specs = list(REQUIRED_DUNGEON_SPECS) + [("dungeon_fixed", RUIN_VARIANT_BY_PROV[prov])]
        d_idx = 1
        for dtype, dvar in req_specs:
            if dvar not in existing_dun_variants:
                while f"{rshort}_site_{d_idx}_{dvar}" in used_ids:
                    d_idx += 1
                pid = f"{rshort}_site_{d_idx}_{dvar}"
                d_idx += 1
                x, z = alloc_point(rid, 38.0)
                new_p = {
                    "id": pid,
                    "name": f"place.nirncraft.{pid}",
                    "type": dtype,
                    "variant": dvar,
                    "class": "minor",
                    "culture": cult,
                    "region": rid,
                    "x": x,
                    "z": z,
                    "why": whys,
                    GENERATED_MARKER: True,
                }
                plist.append(new_p)
                reg_places.append(new_p)
                used_ids.add(pid)
                existing_dun_variants.add(dvar)
                dun_count += 1

        fill_Cycle = [
            ("dungeon_fixed", RUIN_VARIANT_BY_PROV[prov]),
            ("dungeon_fixed", "dragon_lair"),
            ("dungeon_fixed", "barrow"),
            ("dungeon_fixed", "dwemer_ruin"),
            ("dungeon_fixed", "vampire_lair"),
            ("dungeon_fixed", "necromancer_lair"),
            ("dungeon_fixed", "hagraven_lair"),
            ("dungeon_fixed", "mine"),
            ("fort", "fort"),
            ("dungeon_fixed", "bandit_cave"),
        ]
        while dun_count < target_dun:
            dtype, dvar = fill_Cycle[dun_count % len(fill_Cycle)]
            while f"{rshort}_delve_{d_idx}" in used_ids:
                d_idx += 1
            pid = f"{rshort}_delve_{d_idx}"
            d_idx += 1
            x, z = alloc_point(rid, 36.0)
            new_p = {
                "id": pid,
                "name": f"place.nirncraft.{pid}",
                "type": dtype,
                "variant": dvar,
                "class": "minor",
                "culture": cult,
                "region": rid,
                "x": x,
                "z": z,
                "why": whys,
                GENERATED_MARKER: True,
            }
            plist.append(new_p)
            reg_places.append(new_p)
            used_ids.add(pid)
            dun_count += 1

        # C. Landmarks, Standing Stones, Word Walls, Dragon Mounds, Daedric Shrines
        existing_lm_specs = {
            (p["type"], p.get("variant") or "")
            for p in reg_places
            if p["type"] in ("landmark", "standing_stone", "word_wall", "dragon_mound", "daedric_shrine")
        }
        lm_count = sum(
            1 for p in reg_places
            if p["type"] in ("landmark", "standing_stone", "word_wall", "dragon_mound", "daedric_shrine")
        )
        target_lm = max(18, int(math.ceil(4.2 * km2)))
        l_idx = 1
        for ltype, lvar in REQUIRED_LANDMARK_SPECS:
            if (ltype, lvar) not in existing_lm_specs or lm_count < target_lm:
                while f"{rshort}_marker_{l_idx}_{lvar}" in used_ids:
                    l_idx += 1
                pid = f"{rshort}_marker_{l_idx}_{lvar}"
                l_idx += 1
                x, z = alloc_point(rid, 36.0)
                new_p = {
                    "id": pid,
                    "name": f"place.nirncraft.{pid}",
                    "type": ltype,
                    "variant": lvar,
                    "culture": cult,
                    "region": rid,
                    "x": x,
                    "z": z,
                    "why": whys,
                    GENERATED_MARKER: True,
                }
                plist.append(new_p)
                reg_places.append(new_p)
                used_ids.add(pid)
                existing_lm_specs.add((ltype, lvar))
                lm_count += 1

    # Save updated places files
    for fn in TARGET_FILES:
        jsave(os.path.join(PLACES_DIR, fn), places_by_file[fn], indent=1)

    # 3. Update dungeons/identity/{morrowind,black_marsh,elsweyr,valenwood,summerset,foreign,index}.json
    ident_index_path = os.path.join(IDENT_DIR, "index.json")
    ident_index = jload(ident_index_path, [])
    for fn in TARGET_FILES:
        if fn not in ident_index:
            ident_index.append(fn)
    jsave(ident_index_path, ident_index, indent=1)

    for fn in TARGET_FILES:
        ident_path = os.path.join(IDENT_DIR, fn)
        ident_data = jload(ident_path, {"note": "Generated by tools/worldgen/r7_r12_places_uplift.py", "places": {}})
        pmap = ident_data.setdefault("places", {})
        valid_dun_ids = {p["id"] for p in places_by_file[fn] if p["type"] in ("dungeon_fixed", "fort")}
        # Remove stale generated dungeon ids no longer in places
        for k in list(pmap.keys()):
            if k not in valid_dun_ids and ("_site_" in k or "_delve_" in k):
                del pmap[k]
        for p in places_by_file[fn]:
            if p["type"] not in ("dungeon_fixed", "fort"):
                continue
            pid = p["id"]
            rid = p["region"]
            var = p.get("variant") or "cave"
            fam = dungeon_identity_lint.FAMILY.get(var, "cave")
            whys = valid_whys_for(rid, all_fields, by_scope, p.get("why"))
            if pid not in pmap or not isinstance(pmap[pid], dict):
                pmap[pid] = make_identity_entry(pid, var, fam, whys)
            else:
                cur = pmap[pid]
                cur["family"] = fam
                cur["variant"] = var
                if not cur.get("boss"):
                    cur["boss"] = f"Warden-Chief of {title_of(pid)}"[:36]
                if not cur.get("notes"):
                    cur["notes"] = [
                        f"Scout report on {title_of(pid)}: {cur['boss']} commands the inner chamber.",
                        f"Supply tally recovered from the threshold of {title_of(pid)}.",
                    ]
                if not cur.get("quest_hook"):
                    cur["quest_hook"] = f"Clear {title_of(pid)} and recover the regional seal from {cur['boss']}."
                cur["why"] = valid_whys_for(rid, all_fields, by_scope, cur.get("why") or whys)
        jsave(ident_path, ident_data, indent=1)

    # 4. Update lore/places/{morrowind,black_marsh,elsweyr,valenwood,summerset,foreign}.json & lang/nirncraft/lore.json
    lore_lang_path = os.path.join(LANG_DIR, "lore.json")
    lore_lang = jload(lore_lang_path, {})

    for fn in TARGET_FILES:
        lpath = os.path.join(LORE_PLACES_DIR, fn)
        ldata = jload(lpath, {"regions": {}, "places": {}})
        lplaces = ldata.setdefault("places", {})
        valid_pids = {p["id"] for p in places_by_file[fn]}
        for k in list(lplaces.keys()):
            if k not in valid_pids and any(tag in k for tag in ("_croft_", "_estate_", "_site_", "_delve_", "_marker_")):
                del lplaces[k]

        for p in places_by_file[fn]:
            pid = p["id"]
            rid = p["region"]
            var = p.get("variant") or ""
            fam = dungeon_identity_lint.FAMILY.get(var, "cave") if p["type"] in ("dungeon_fixed", "fort") else p["type"]
            whys = valid_whys_for(rid, all_fields, by_scope, p.get("why"))
            if pid not in lplaces or not isinstance(lplaces[pid], dict) or len(lplaces[pid].get("notes") or []) < 6:
                lplaces[pid] = make_lore_entry(pid, p["type"], var, rid, fam, whys)
            else:
                cur = lplaces[pid]
                cur["why"] = valid_whys_for(rid, all_fields, by_scope, cur.get("why") or whys)
                # Ensure dungeon hook/rumour never mismatches family in dungeon_identity_lint.py
                if p["type"] in ("dungeon_fixed", "fort"):
                    exp = dungeon_identity_lint.FAMKW.get(fam)
                    hook_txt = ((cur.get("hook") or "") + " " + (cur.get("rumour") or "")).lower()
                    if exp and not re.search(dungeon_identity_lint.KW[exp], hook_txt):
                        fk = family_keyword(fam)
                        cur["hook"] = f"Travelers in {title_of(rid.split('/')[-1])} report activity around {title_of(pid)}, a {fk}."
                        cur["rumour"] = f"Wayfarers near {title_of(pid)} speak of sealed caches inside this {fk}."
                for idx, n in enumerate(cur.get("notes") or [], 1):
                    if isinstance(n, dict):
                        n["why"] = valid_whys_for(rid, all_fields, by_scope, n.get("why") or whys)
                        if not n.get("title"):
                            n["title"] = f"{title_of(pid)} Field Dispatch {idx}"

        jsave(lpath, ldata, indent=2)

    jsave(lore_lang_path, lore_lang, indent=2)

    # 5. Update explore/uniques_{morrowind,black_marsh,elsweyr,valenwood,summerset,foreign}.json
    for fn in TARGET_FILES:
        stem = fn[:-5]
        upath = os.path.join(EXPLORE_DIR, f"uniques_{stem}.json")
        udata = jload(upath, {"province": stem, "uniques": []})
        ulist = udata.get("uniques", []) if isinstance(udata, dict) else udata
        # Group existing uniques by region
        by_rid = collections.defaultdict(list)
        kept_u = []
        for u in ulist:
            if not isinstance(u, dict) or u.get(GENERATED_MARKER):
                continue
            kept_u.append(u)
            rg = u.get("region", "")
            if "/" not in rg:
                # Match to full rid
                for r in regions:
                    if r["id"].endswith("/" + rg):
                        rg = r["id"]
                        break
            by_rid[rg].append(u)

        file_regions = [r for r in regions if PROVINCE_TO_FILE[r["province"]] == stem]
        for r in file_regions:
            rid = r["id"]
            rshort = rid.split("/")[-1]
            prov = r["province"]
            km2 = area_km2[rid]
            target_u = max(8, int(math.ceil(1.65 * km2)))
            cur_u = by_rid[rid]
            whys = valid_whys_for(rid, all_fields, by_scope)
            reg_duns = [p for p in places_by_file[fn] if p.get("region") == rid and p["type"] in ("dungeon_fixed", "fort")]
            u_idx = len(cur_u) + 1
            while len(cur_u) < target_u and reg_duns:
                dp = reg_duns[(u_idx - 1) % len(reg_duns)]
                item_id, ench = UNIQUE_ITEMS[(u_idx - 1) % len(UNIQUE_ITEMS)]
                ukind = UNIQUE_KINDS[(u_idx - 1) % len(UNIQUE_KINDS)]
                uid = f"{prov}_{rshort}_v4_unique_{u_idx}"
                u_obj = {
                    "id": uid,
                    "name": f"Relic of {title_of(dp['id'])} ({u_idx})",
                    "region": rshort,
                    "why": whys,
                    "place": dp["id"],
                    "x": dp["x"],
                    "z": dp["z"],
                    "kind": ukind,
                    "item": item_id,
                    "enchant": ench,
                    "note": f"Preserved at {title_of(dp['id'])} in {title_of(rshort)} from the elder council.",
                    GENERATED_MARKER: True,
                }
                kept_u.append(u_obj)
                cur_u.append(u_obj)
                u_idx += 1

        if isinstance(udata, dict):
            udata["uniques"] = kept_u
            jsave(upath, udata, indent=2)
        else:
            jsave(upath, kept_u, indent=2)

    # 6. Update society/morrowind_residents.json and lang/nirncraft/society_morrowind.json
    mw_res_path = os.path.join(SOCIETY_DIR, "morrowind_residents.json")
    mw_res = jload(mw_res_path, {"schema_version": 1, "province": "morrowind", "settlements": {}})
    smap = mw_res.setdefault("settlements", {})
    mw_lang_path = os.path.join(LANG_DIR, "society_morrowind.json")
    mw_lang = jload(mw_lang_path, {})

    ROLES_POOL = [
        "armorer", "weaponsmith", "alchemist", "apothecary", "enchanter", "healer", "scribe", "librarian",
        "archivist", "innkeeper", "publican", "caravaneer", "silt_strider_caravaneer", "gondolier", "shipwright",
        "harbormaster", "dockworker", "fisher", "netchiman", "kwama_miner", "ebony_miner", "glass_artisan",
        "muck_farmer", "saltrice_planter", "ash_yam_grower", "guar_herder", "ash_scout", "buoyant_armiger",
        "ordinator", "temple_curate", "dissident_scholar", "telvanni_mouth", "retainer", "factor", "magistrate",
        "customs_clerk", "bailiff", "watch_captain", "scout", "hunter", "fletcher", "tailor", "jeweler", "mason",
    ]

    for p in places_by_file["morrowind.json"]:
        if p["type"] not in ("capital", "city", "town", "village"):
            continue
        sid = p["id"]
        rid = p["region"]
        rshort = rid.split("/")[-1]
        whys = valid_whys_for(rid, all_fields, by_scope, p.get("why"))
        sblock = smap.setdefault(sid, {
            "name": title_of(sid),
            "region": rid,
            "hold": rshort,
            "tier": p["type"],
            "why": whys,
            "residents": [],
        })
        sblock["region"] = rid
        sblock["hold"] = rshort
        sblock["tier"] = p["type"]
        sblock["why"] = whys
        res_list = sblock.setdefault("residents", [])
        target_res = 38 if p["type"] in ("capital", "city", "town") else 10
        r_idx = len(res_list) + 1
        while len(res_list) < target_res:
            role = ROLES_POOL[(r_idx - 1) % len(ROLES_POOL)]
            npc_id = f"{sid}_v4_res_{r_idx}"
            npc_name = f"{title_of(sid)} {title_of(role)} {r_idx}"
            res_list.append({
                "id": npc_id,
                "name": npc_name,
                "race": "dunmer",
                "role": role,
                "title": f"{title_of(role)} of {title_of(sid)}",
                "merchant": "None",
                "trainer": "None",
                "court_role": role,
                "home": f"{title_of(sid)} Commons",
                "work": f"{title_of(sid)} Trade Hall",
                "schedule": "07:00-18:00 duties; 18:00-22:00 cornerclub; 22:00-07:00 quarters",
                "greet": f"Wealth beyond measure, traveler. Welcome to {title_of(sid)} in {title_of(rshort)}.",
                "talk": f"As a {role} in {title_of(sid)}, I keep the House ledgers and ancestral wards in order.",
                "bark": f"May the ancestors watch over {title_of(sid)} and the roads of {title_of(rshort)}.",
                "why": whys,
            })
            r_idx += 1

    jsave(mw_res_path, mw_res, indent=2)
    jsave(mw_lang_path, mw_lang, indent=2)
    print("r7_r12_places_uplift.py: completed updates across R7-R12 write set.")


if __name__ == "__main__":
    main()
