#!/usr/bin/env python3
"""Extracts Section 1 (World model sheet) of a province markdown doc into JSON (MS22).

Usage:
  py -3 -I tools/world/sheet_from_md.py docs/world/provinces/skyrim.md --out mod/src/main/resources/skycraft/tamriel/world/skyrim.json [--stamp-identity]

Rules:
- Parses the province block (1.1) and all region sub-sheets (1.2+) across layers L1..L11.
- Every L2..L11 field MUST have explicit cites in the markdown document; if any L2..L11 field
  has no explicit cites (or cites a bare layer name without a field id), this tool fails loudly
  with the offending field name instead of guessing.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft")

LAYERS = [f"L{i}" for i in range(1, 12)]

PROVINCE_ALIASES = {
    "sk": "skyrim",
    "skyrim": "skyrim",
    "hr": "high_rock",
    "high_rock": "high_rock",
    "hf": "hammerfell",
    "hammerfell": "hammerfell",
    "cy": "cyrodiil",
    "cyrodiil": "cyrodiil",
    "mw": "morrowind",
    "morrowind": "morrowind",
    "bm": "black_marsh",
    "black_marsh": "black_marsh",
    "el": "elsweyr",
    "elsweyr": "elsweyr",
    "vw": "valenwood",
    "valenwood": "valenwood",
    "si": "summerset",
    "summerset": "summerset",
    "so": "solstheim",
    "solstheim": "solstheim",
    "fl": "foreign",
    "foreign": "foreign",
}

REGION_ALIASES = {
    "pale": "the_pale",
    "the_pale": "the_pale",
    "rift": "the_rift",
    "the_rift": "the_rift",
    "reach": "the_reach",
    "the_reach": "the_reach",
}


def canon_scope(raw: str, default_province: str = "") -> str:
    s = raw.strip().lower()
    if "/" in s:
        s = s.split("/", 1)[1]
    if s in PROVINCE_ALIASES:
        return PROVINCE_ALIASES[s]
    if s in REGION_ALIASES:
        return REGION_ALIASES[s]
    return s


def extract_section_1(md_text: str) -> str:
    m = re.search(r"^##\s+1\.\s+World model sheet\b", md_text, flags=re.MULTILINE)
    if not m:
        raise ValueError("Document has no '## 1. World model sheet' section")
    start = m.start()
    m2 = re.search(r"^##\s+2\.", md_text[m.end():], flags=re.MULTILINE)
    end = m.end() + m2.start() if m2 else len(md_text)
    return md_text[start:end]


def parse_truth(block_text: str, scope: str) -> str:
    m = re.search(
        r"\*\*World truth(?:\s+paragraph)?\.?\*\*\s*(.+?)(?=\n\s*\n|\n\s*\||\Z)",
        block_text,
        flags=re.DOTALL,
    )
    if not m:
        raise ValueError(f"{scope}: missing '**World truth.**' paragraph")
    truth = " ".join(line.strip() for line in m.group(1).strip().splitlines() if line.strip())
    return truth


FIELD_RE = re.compile(r"`([a-zA-Z0-9_]+)`(?:\s*\(([^)]*)\))?")


def parse_layer_cell(cell: str, scope: str, layer: str, errors: list) -> list:
    matches = list(FIELD_RE.finditer(cell))
    fields = []
    for idx, m in enumerate(matches):
        fid = m.group(1).strip()
        raw_cites = m.group(2)
        text_start = m.end()
        text_end = matches[idx + 1].start() if idx + 1 < len(matches) else len(cell)
        raw_text = cell[text_start:text_end].strip()
        if raw_text.startswith(":"):
            raw_text = raw_text[1:].strip()
        raw_text = raw_text.rstrip(" .;").strip()
        if not raw_text:
            raw_text = fid.replace("_", " ")

        cites = []
        if raw_cites is not None:
            for part in raw_cites.split(","):
                c = part.strip()
                if c:
                    cites.append(c)

        if layer != "L1" and not cites:
            errors.append(f"{scope}.{layer}.{fid}: missing explicit cites in markdown (do not guess)")

        fields.append({
            "id": fid,
            "text": raw_text,
            "cites": cites,
        })
    return fields


def parse_block_layers(block_text: str, scope: str, errors: list) -> dict:
    layers = {}
    for line in block_text.splitlines():
        s = line.strip()
        if not s.startswith("|"):
            continue
        cols = [c.strip() for c in s.strip("|").split("|")]
        if len(cols) < 2:
            continue
        m = re.match(r"^(L(?:1[01]|[1-9]))\b", cols[0])
        if not m:
            continue
        layer = m.group(1)
        fields = parse_layer_cell(cols[1], scope, layer, errors)
        if not fields:
            errors.append(f"{scope}.{layer}: layer row has no backtick-quoted field ids")
        layers[layer] = fields

    for req in LAYERS:
        if req not in layers:
            errors.append(f"{scope}: missing layer {req}")
    return layers


def resolve_cites(sheet: dict, errors: list) -> None:
    prov = sheet["province"]
    prov_fields = {}
    for layer, flist in sheet["layers"].items():
        for f in flist:
            prov_fields[f"{layer}.{f['id']}"] = f"{prov}.{layer}.{f['id']}"

    def normalize_block(scope: str, layers: dict, is_province: bool) -> None:
        local_fields = {}
        for layer, flist in layers.items():
            for f in flist:
                local_fields[f"{layer}.{f['id']}"] = f"{scope}.{layer}.{f['id']}"

        for layer, flist in layers.items():
            for f in flist:
                fid = f["id"]
                resolved = []
                for raw in f["cites"]:
                    token = raw.strip()
                    if re.match(r"^L(?:1[01]|[1-9])$", token):
                        errors.append(
                            f"{scope}.{layer}.{fid}: bare layer cite '{token}' without field id (must cite L<n>.<id>)"
                        )
                        continue
                    parts = token.split(".")
                    if len(parts) == 2 and re.match(r"^L(?:1[01]|[1-9])$", parts[0]):
                        key = f"{parts[0]}.{parts[1]}"
                        if key in local_fields:
                            resolved.append(local_fields[key])
                        elif not is_province and key in prov_fields:
                            resolved.append(prov_fields[key])
                        else:
                            resolved.append(f"{scope}.{key}")
                    elif len(parts) == 3:
                        target_scope = canon_scope(parts[0], prov)
                        resolved.append(f"{target_scope}.{parts[1]}.{parts[2]}")
                    else:
                        errors.append(f"{scope}.{layer}.{fid}: malformed cite '{token}'")
                # Deduplicate preserving order
                seen = set()
                dedup = []
                for r in resolved:
                    if r not in seen:
                        seen.add(r)
                        dedup.append(r)
                f["cites"] = dedup

    normalize_block(prov, sheet["layers"], True)
    for rkey, rblock in sheet["regions"].items():
        normalize_block(rkey, rblock["layers"], False)


def pick_matching_fields(scope: str, layers: dict, prov: str, prov_layers: dict,
                         target_layers: tuple, keywords: tuple, fallback_layer: str = "L5") -> tuple:
    hits = []
    for lyr in target_layers:
        for f in layers.get(lyr, []):
            hay = f"{f['id']} {f['text']}".lower()
            if any(k in hay for k in keywords):
                hits.append(f"{scope}.{lyr}.{f['id']}")
    weight = max(1, min(4, len(hits) + 1)) if hits else 1
    if not hits:
        for lyr in target_layers:
            if layers.get(lyr):
                hits.append(f"{scope}.{lyr}.{layers[lyr][0]['id']}")
                break
    if not hits and layers.get(fallback_layer):
        hits.append(f"{scope}.{fallback_layer}.{layers[fallback_layer][0]['id']}")
    if not hits and prov_layers.get(fallback_layer):
        hits.append(f"{prov}.{fallback_layer}.{prov_layers[fallback_layer][0]['id']}")
    return weight, hits[:3]


JOB_SPECS = {
    "miner": (("L5", "L1"), ("mine", "mining", "ore", "silver", "iron", "quicksilver", "ebony", "corundum", "malachite", "quarry")),
    "smith": (("L5",), ("forge", "steel", "mine", "mining", "iron", "ore", "armour", "smith")),
    "jeweler": (("L5",), ("jewel", "silver", "gold", "market", "mine", "gem")),
    "farmer": (("L5", "L3"), ("farm", "wheat", "grain", "cattle", "mead", "honey", "flats", "fertile", "harvest", "orchard")),
    "farmhand": (("L5", "L3"), ("farm", "wheat", "grain", "cattle", "mead", "honey", "flats", "harvest")),
    "lumberjack": (("L5", "L4"), ("timber", "mill", "sawmill", "pine", "birch", "wood", "log", "forest")),
    "miller": (("L5",), ("mill", "sawmill", "timber", "farm", "grain")),
    "mill_hand": (("L5",), ("mill", "sawmill", "timber")),
    "woodcutter": (("L5", "L4"), ("timber", "mill", "sawmill", "pine", "birch", "forest")),
    "fisher": (("L5", "L3", "L4"), ("fish", "harbour", "docks", "sea", "lake", "cove", "coast", "crab", "horker")),
    "hunter": (("L5", "L4"), ("hunt", "pelt", "bear", "deer", "elk", "horker", "forest", "archery", "fur")),
    "butcher": (("L5", "L4"), ("hunt", "cattle", "meat", "fish", "farm", "herd")),
    "fletcher": (("L5", "L4"), ("hunt", "fletcher", "timber", "pine", "birch", "market")),
    "alchemist": (("L5", "L4"), ("herb", "alchem", "marsh", "fungus", "college", "flower", "moss")),
    "merchant": (("L5", "L6"), ("trade", "market", "docks", "road", "caravan", "harbour", "crossroads", "company", "ships")),
    "tailor": (("L5", "L7"), ("tailor", "market", "noble", "court", "trade", "cotton", "wool")),
    "bookseller": (("L5", "L6", "L8"), ("college", "bard", "scholar", "market", "temple", "trade")),
    "innkeeper": (("L5", "L6"), ("inn", "mead", "road", "crossroads", "harbour", "seat", "village")),
    "cook": (("L5", "L6"), ("inn", "mead", "farm", "fish", "honey")),
    "server": (("L5", "L6"), ("inn", "mead", "seat", "road")),
    "bard": (("L5", "L8"), ("bard", "song", "inn", "mead", "seat", "honour", "kings")),
    "priest": (("L8", "L5"), ("temple", "arkay", "mara", "dibella", "kynareth", "talos", "divines", "burial", "dead", "tree", "shrine")),
    "jarl": (("L6", "L7", "L9"), ("seat", "jarl", "capital", "court", "throne", "palace")),
    "steward": (("L6", "L7"), ("seat", "jarl", "court", "capital", "council")),
    "housecarl": (("L7", "L9"), ("jarl", "warrior", "honour", "seat", "capital", "court")),
    "court_wizard": (("L7", "L8", "L6"), ("wizard", "mage", "college", "seer", "court", "seat")),
    "mage": (("L8", "L7", "L6"), ("college", "mage", "wizard", "magic", "scholar")),
    "guard": (("L9", "L6"), ("civil_war", "seat", "jarl", "border", "siege", "imperial", "rebel", "watch", "bridge")),
    "fence": (("L10", "L5"), ("thieves", "smuggl", "shadow", "guild", "canal")),
    "prisoner": (("L7", "L9", "L10"), ("prison", "convict", "jail", "mine", "uprising", "crime")),
    "beggar": (("L7", "L6"), ("poor", "poverty", "beggar", "canal", "slum", "mourn", "failing", "remnant")),
    "citizen": (("L6", "L7", "L5"), ("seat", "district", "town", "village", "quarter", "clan")),
    "housekeeper": (("L7", "L6"), ("district", "quarter", "seat", "family", "town")),
    "child": (("L7", "L6"), ("orphan", "family", "district", "town", "seat")),
    "elder": (("L7", "L8", "L11"), ("clan", "old", "ancestor", "mourn", "history", "seat")),
    "labourer": (("L5", "L7"), ("labour", "dock", "mine", "mill", "farm", "work", "poor")),
    "worker": (("L5", "L7"), ("labour", "dock", "mine", "mill", "farm", "work")),
    "guild_leader": (("L7", "L10", "L6"), ("warrior_hall", "college", "thieves", "black_door", "sanctuary", "guild", "bards")),
    "guild_trainer": (("L7", "L10", "L6"), ("warrior_hall", "college", "thieves", "black_door", "sanctuary", "guild")),
    "guild_vendor": (("L5", "L7", "L10"), ("college", "thieves", "skyforge", "guild", "market")),
    "survivor": (("L9", "L6"), ("burned", "helgen", "dragon", "collapse", "remnant")),
}

DUNGEON_SPECS = {
    "barrow": (("L10", "L8", "L11"), ("barrow", "draugr", "mound", "tomb", "crown", "bromjunaar", "rahgot", "bleak_falls", "harbinger")),
    "ruin": (("L10", "L11", "L8"), ("ruin", "shrine", "tomb", "barrow", "saarthal", "bromjunaar", "blades")),
    "dwemer": (("L10", "L1", "L11"), ("dwemer", "lift", "steam_ruin", "depths", "automaton", "machines")),
    "falmer": (("L10", "L4"), ("falmer", "blind", "chaurus", "below", "pit")),
    "bandit": (("L10", "L5"), ("bandit", "toll", "smuggl", "mine_camps", "road")),
    "fort": (("L9", "L10", "L6"), ("siege", "fort", "border", "civil_war", "hunters_fort", "helgen", "treva_watch", "beast_blood")),
    "military": (("L9",), ("civil_war", "imperial", "rebel", "siege", "border", "naval")),
    "mage": (("L10", "L8", "L7"), ("mage", "wizard", "necromancer", "college", "fire_mage", "magic")),
    "vampire": (("L10", "L9"), ("vampire", "volkihar", "blood", "bargain_shrine")),
    "necromancer": (("L10", "L8"), ("necromancer", "wolf_queen", "dead", "grave", "conjurer")),
    "hagraven": (("L10", "L4", "L8"), ("hagraven", "coven", "witch", "crag_scrub", "old_gods", "huntsman")),
    "forsworn": (("L10", "L9", "L7"), ("forsworn", "redoubt", "uprising", "reachfolk", "conspiracy")),
    "beast": (("L4", "L10"), ("bear", "wolf", "sabre", "spider", "troll", "cave", "den")),
    "ice": (("L10", "L4", "L2"), ("troll", "ice", "glacier", "floe", "polar", "snowfield", "wreck")),
    "mine": (("L5", "L1", "L10"), ("mine", "mining", "ore", "silver", "iron", "quicksilver", "ebony")),
    "grove": (("L4", "L8"), ("spriggan", "grove", "glade", "glen", "eldergleam", "pine", "birch")),
    "smuggler": (("L10", "L5"), ("smuggl", "cove", "docks", "thieves", "harbour")),
    "cave": (("L10", "L1", "L4"), ("cave", "karst", "grotto", "bandit", "bear", "troll", "vampire")),
    "giant": (("L10", "L4"), ("giant", "mammoth", "tundra", "heath", "fumarole")),
    "dragon": (("L10", "L4", "L9"), ("dragon", "perch", "mound", "heights", "throat")),
    "tower": (("L10", "L3", "L9"), ("valtheim", "tower", "lighthouse", "crossing", "watchtower", "bandit")),
    "cabin": (("L6", "L5"), ("hunter", "camp", "mill", "farm", "hermit", "fishing")),
    "wreck": (("L5", "L10", "L1"), ("wreck", "salvage", "floe", "low_coast", "sea_cliffs")),
}


def build_derived_tables(scope: str, layers: dict, prov: str, prov_layers: dict) -> tuple:
    jobs = {}
    for role, (tlayers, kw) in JOB_SPECS.items():
        w, why = pick_matching_fields(scope, layers, prov, prov_layers, tlayers, kw, "L5")
        jobs[role] = {"weight": w, "why": why}

    themes = {}
    for fam, (tlayers, kw) in DUNGEON_SPECS.items():
        _, why = pick_matching_fields(scope, layers, prov, prov_layers, tlayers, kw, "L10")
        themes[fam] = why
    return jobs, themes


def default_forbidden(prov: str, regions: dict) -> list:
    if prov == "skyrim":
        return [
            {
                "kind": "crop_on_micro",
                "a": "warm_grain_crops",
                "b": "winterhold.L4.glacier_waste",
                "reason": "Glacier waste and pack ice cannot sustain open-field grain crops",
            },
            {
                "kind": "industry_without_resource",
                "a": "silver_smelter",
                "b": "hjaalmarch.L1.drajkmyr_flats",
                "reason": "Drajkmyr salt marsh has no silver ore veins",
            },
            {
                "kind": "shrine_without_faith",
                "a": "open_talos_shrine",
                "b": "haafingar.L8.divines_temple",
                "reason": "Haafingar's Imperial capital enforces the Concordat ban on open Talos shrines",
            },
            {
                "kind": "inhabitant_without_l10",
                "a": "riekling_tribe",
                "b": "whiterun.L10.giant_camps",
                "reason": "Rieklings belong to Solstheim's ice caves, not Whiterun's tundra",
            },
        ]
    return []


def parse_markdown_sheet(md_path: str) -> dict:
    with open(md_path, encoding="utf-8") as f:
        text = f.read()

    sec1 = extract_section_1(text)
    sub_matches = list(re.finditer(r"^###\s+1\.(\d+)\s+(.+)$", sec1, flags=re.MULTILINE))
    if not sub_matches:
        raise ValueError(f"{md_path}: no '### 1.<n>' subsections found in Section 1")

    errors = []
    prov_id = os.path.splitext(os.path.basename(md_path))[0].lower()
    prov_truth = ""
    prov_layers = {}
    regions = {}

    for i, m in enumerate(sub_matches):
        idx = int(m.group(1))
        header = m.group(2).strip()
        start = m.end()
        end = sub_matches[i + 1].start() if i + 1 < len(sub_matches) else len(sec1)
        block_text = sec1[start:end]

        code_m = re.search(r"`([^`]+)`", header)
        raw_scope = code_m.group(1) if code_m else header.split()[0]

        if idx == 1 or "province sheet" in header.lower():
            scope = canon_scope(raw_scope, prov_id)
            if scope in PROVINCE_ALIASES:
                prov_id = PROVINCE_ALIASES[scope]
                scope = prov_id
            prov_truth = parse_truth(block_text, scope)
            prov_layers = parse_block_layers(block_text, scope, errors)
        else:
            rkey = canon_scope(raw_scope, prov_id)
            rtruth = parse_truth(block_text, rkey)
            rlayers = parse_block_layers(block_text, rkey, errors)
            regions[rkey] = {
                "truth": rtruth,
                "layers": rlayers,
            }

    sheet = {
        "province": prov_id,
        "truth": prov_truth,
        "layers": prov_layers,
        "regions": regions,
        "forbidden": default_forbidden(prov_id, regions),
    }

    resolve_cites(sheet, errors)
    if errors:
        msg = "\n".join(f"  ERR {e}" for e in errors)
        raise ValueError(f"Failed to extract {md_path} ({len(errors)} errors):\n{msg}")

    p_jobs, p_themes = build_derived_tables(prov_id, prov_layers, prov_id, prov_layers)
    sheet["jobs"] = p_jobs
    sheet["dungeon_themes"] = p_themes
    for rkey, rblock in regions.items():
        r_jobs, r_themes = build_derived_tables(rkey, rblock["layers"], prov_id, prov_layers)
        rblock["jobs"] = r_jobs
        rblock["dungeon_themes"] = r_themes

    return sheet


def stamp_skyrim_dungeon_identity(sheet: dict) -> int:
    ident_path = os.path.join(RES, "dungeons", "identity", "skyrim.json")
    if not os.path.exists(ident_path):
        return 0
    with open(ident_path, encoding="utf-8") as f:
        ident = json.load(f)

    place_region = {}
    for fn in ("skyrim.json", "skyrim_landmarks.json"):
        p_path = os.path.join(RES, "tamriel", "places", fn)
        if os.path.exists(p_path):
            with open(p_path, encoding="utf-8") as pf:
                for p in json.load(pf):
                    if isinstance(p, dict) and "id" in p and "region" in p:
                        place_region[p["id"]] = p["region"]

    regions = sheet.get("regions", {})
    prov_themes = sheet.get("dungeon_themes", {})
    stamped = 0
    for pid, entry in ident.get("places", {}).items():
        reg_raw = place_region.get(pid, "skyrim/whiterun")
        rkey = canon_scope(reg_raw, "skyrim")
        fam = entry.get("family", "cave")
        if rkey in regions and fam in regions[rkey].get("dungeon_themes", {}):
            why = list(regions[rkey]["dungeon_themes"][fam])
        elif fam in prov_themes:
            why = list(prov_themes[fam])
        else:
            why = ["skyrim.L10.draugr_barrows", "skyrim.L1.barrow_ridges"]
        entry["why"] = why
        stamped += 1

    with open(ident_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(ident, indent=1, ensure_ascii=False) + "\n")
    return stamped


def main() -> int:
    ap = argparse.ArgumentParser(description="Extract Section 1 world model sheet from province markdown.")
    ap.add_argument("markdown", help="Path to docs/world/provinces/<province>.md")
    ap.add_argument("--out", help="Output JSON path (default: mod/src/main/resources/skycraft/tamriel/world/<province>.json)")
    ap.add_argument("--stamp-identity", action="store_true", help="Also stamp resolving L10 why cites onto dungeons/identity/skyrim.json")
    args = ap.parse_args()

    try:
        sheet = parse_markdown_sheet(args.markdown)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    out_path = args.out
    if not out_path:
        out_path = os.path.join(RES, "tamriel", "world", f"{sheet['province']}.json")

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(sheet, f, indent=2, ensure_ascii=False)
        f.write("\n")

    total_blocks = 1 + len(sheet["regions"])
    total_fields = sum(len(v) for v in sheet["layers"].values()) + sum(
        len(v) for r in sheet["regions"].values() for v in r["layers"].values()
    )
    msg = f"Wrote {out_path}: province={sheet['province']}, {total_blocks} blocks (1 province + {len(sheet['regions'])} regions) x 11 layers, {total_fields} fields"
    if args.stamp_identity and sheet["province"] == "skyrim":
        n_ident = stamp_skyrim_dungeon_identity(sheet)
        msg += f"; stamped why on {n_ident} dungeon identity entries"
    print(msg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
