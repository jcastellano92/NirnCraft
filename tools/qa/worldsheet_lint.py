#!/usr/bin/env python3
"""MS22 World-model sheet & F-WHY coherence linter.

Usage:
  py -3 -I tools/qa/worldsheet_lint.py [--province <id>]

Checks:
1. Every loaded sheet (tamriel/world/*.json) has province truth + layers L1..L11
   and every region block has truth + layers L1..L11 (all non-empty).
2. Every L2..L11 field cites at least one field from a strictly higher causal
   layer (L1..L<n-1>) in the same region or province sheet.
3. Every cite resolves to an existing field id, and the citation graph has 0 cycles.
4. Forbidden pairs listed in the sheet are valid and absent from content records.
5. Scans content records (places, quests, scatter/kinds.json, dungeons/identity,
   lore/places, lore/books, region_spawns, palettes micro rules, settlement_jobs)
   for `why`: verifies every present `why` entry resolves to a valid sheet field id,
   and prints F-WHY coverage plus the legacy back-fill counter per content type.
Exits 1 on any error.
"""
import argparse
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
SKY = os.path.join(RES, "skycraft")
WORLD_DIR = os.path.join(SKY, "tamriel", "world")

LAYERS = [f"L{i}" for i in range(1, 12)]
LAYER_NUM = {f"L{i}": i for i in range(1, 12)}

SCOPE_ALIASES = {
    "sk": "skyrim",
    "skyrim": "skyrim",
    "pale": "the_pale",
    "the_pale": "the_pale",
    "rift": "the_rift",
    "the_rift": "the_rift",
    "reach": "the_reach",
    "the_reach": "the_reach",
    "hr": "high_rock",
    "hf": "hammerfell",
    "cy": "cyrodiil",
    "mw": "morrowind",
    "bm": "black_marsh",
    "el": "elsweyr",
    "vw": "valenwood",
    "si": "summerset",
    "so": "solstheim",
    "fl": "foreign",
}


def canon_scope(scope: str) -> str:
    s = scope.strip().lower()
    if "/" in s:
        s = s.split("/", 1)[1]
    return SCOPE_ALIASES.get(s, s)


def canon_field_id(fid: str) -> str:
    parts = fid.strip().split(".")
    if len(parts) == 3:
        return f"{canon_scope(parts[0])}.{parts[1]}.{parts[2]}"
    return fid.strip()


def load_json(path: str):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def validate_sheets(province_filter: str | None, errors: list[str]) -> tuple[dict, dict]:
    if not os.path.isdir(WORLD_DIR):
        errors.append(f"ERR missing world sheets directory: {WORLD_DIR}")
        return {}, {}

    pattern = os.path.join(WORLD_DIR, f"{province_filter}.json" if province_filter else "*.json")
    files = sorted(glob.glob(pattern))
    if not files:
        errors.append(f"ERR no world sheet files found matching {pattern}")
        return {}, {}

    sheets = {}
    all_fields = {}  # canonical_id -> {"scope", "layer", "num", "id", "text", "cites", "province"}

    for path in files:
        data = load_json(path)
        prov = data.get("province", "")
        if not prov:
            errors.append(f"ERR {path}: missing 'province'")
            continue
        sheets[prov] = data

        def register_block(scope: str, block: dict, is_prov: bool) -> None:
            truth = block.get("truth", "")
            if not isinstance(truth, str) or len(truth.split()) < 20:
                errors.append(f"ERR {prov}:{scope}: missing or too-short 'truth' paragraph")
            layers = block.get("layers", {})
            for lyr in LAYERS:
                if lyr not in layers:
                    errors.append(f"ERR {prov}:{scope}: missing layer {lyr}")
                    continue
                flist = layers[lyr]
                if not isinstance(flist, list) or len(flist) == 0:
                    errors.append(f"ERR {prov}:{scope}.{lyr}: layer is empty")
                    continue
                for f in flist:
                    fid = f.get("id", "").strip()
                    txt = f.get("text", "").strip()
                    cites = f.get("cites", [])
                    if not fid:
                        errors.append(f"ERR {prov}:{scope}.{lyr}: field with empty id")
                        continue
                    if not txt:
                        errors.append(f"ERR {prov}:{scope}.{lyr}.{fid}: field with empty text")
                    if not isinstance(cites, list):
                        errors.append(f"ERR {prov}:{scope}.{lyr}.{fid}: 'cites' must be a list")
                        cites = []
                    gid = f"{scope}.{lyr}.{fid}"
                    if gid in all_fields:
                        errors.append(f"ERR duplicate field id {gid}")
                    all_fields[gid] = {
                        "scope": scope,
                        "layer": lyr,
                        "num": LAYER_NUM[lyr],
                        "id": fid,
                        "text": txt,
                        "cites": [canon_field_id(c) for c in cites],
                        "province": prov,
                        "is_province": is_prov,
                    }

        register_block(prov, data, True)
        regions = data.get("regions", {})
        if not isinstance(regions, dict) or len(regions) == 0:
            errors.append(f"ERR {prov}: 'regions' map is empty")
        else:
            for rkey, rblock in regions.items():
                register_block(rkey, rblock, False)

    # Check causal citations and build graph
    for gid, info in all_fields.items():
        num = info["num"]
        cites = info["cites"]
        if num >= 2 and not cites:
            errors.append(f"ERR {gid}: L2-L11 field has no cites")
            continue
        has_higher = False
        for c in cites:
            target = all_fields.get(c)
            if target is None:
                errors.append(f"ERR {gid}: cite '{c}' does not resolve to any field")
                continue
            # Check scope visibility: region field may cite its own block or its province block
            if target["scope"] != info["scope"] and target["scope"] != info["province"]:
                errors.append(
                    f"ERR {gid}: cite '{c}' is outside region '{info['scope']}' and province '{info['province']}'"
                )
            if target["num"] < num:
                has_higher = True
        if num >= 2 and not has_higher:
            errors.append(
                f"ERR {gid}: must cite at least one field in a higher layer (L1..L{num - 1}), got {cites}"
            )

    # Cycle detection (3-color DFS)
    state = {}  # 0/unvisited, 1/visiting, 2/visited
    path_stack = []

    def dfs(node: str) -> None:
        state[node] = 1
        path_stack.append(node)
        for nxt in all_fields[node]["cites"]:
            if nxt not in all_fields:
                continue
            st = state.get(nxt, 0)
            if st == 1:
                idx = path_stack.index(nxt)
                cycle_str = " -> ".join(path_stack[idx:] + [nxt])
                errors.append(f"ERR cycle detected in sheet cites: {cycle_str}")
            elif st == 0:
                dfs(nxt)
        path_stack.pop()
        state[node] = 2

    for gid in all_fields:
        if state.get(gid, 0) == 0:
            dfs(gid)

    # Check forbidden definitions
    for prov, data in sheets.items():
        forbidden = data.get("forbidden", [])
        if not isinstance(forbidden, list):
            errors.append(f"ERR {prov}: 'forbidden' must be a list")
            continue
        for idx, fb in enumerate(forbidden):
            if not isinstance(fb, dict) or not fb.get("kind") or not fb.get("a") or not fb.get("b"):
                errors.append(f"ERR {prov}: forbidden[{idx}] must have 'kind', 'a', and 'b'")

    return sheets, all_fields


def check_why_list(record_label: str, why_val, all_fields: dict, forbidden_pairs: list, errors: list[str]) -> bool:
    if why_val is None:
        return False
    if not isinstance(why_val, list) or len(why_val) == 0:
        errors.append(f"ERR {record_label}: 'why' must be a non-empty list of field ids")
        return False
    ok = True
    canon_set = set()
    for raw in why_val:
        if not isinstance(raw, str) or not raw.strip():
            errors.append(f"ERR {record_label}: invalid empty entry in 'why'")
            ok = False
            continue
        cid = canon_field_id(raw)
        canon_set.add(cid)
        if cid not in all_fields:
            errors.append(f"ERR {record_label}: why '{raw}' (canonical '{cid}') does not resolve")
            ok = False
    for fb in forbidden_pairs:
        a = canon_field_id(fb["a"])
        b = canon_field_id(fb["b"])
        if a in canon_set and b in canon_set:
            errors.append(f"ERR {record_label}: violates forbidden pair ({fb['kind']}): {a} + {b}")
            ok = False
    return ok


def scan_content(province_filter: str | None, sheets: dict, all_fields: dict, errors: list[str]) -> dict:
    forbidden_pairs = []
    for s in sheets.values():
        forbidden_pairs.extend(s.get("forbidden", []))

    stats = {}

    def record_stat(cat: str, total: int, with_why: int) -> None:
        stats[cat] = {
            "total": total,
            "with_why": with_why,
            "legacy_backfill": total - with_why,
            "f_why": (100.0 * with_why / total) if total > 0 else 100.0,
        }

    # 1. Settlement jobs (from sheet L5/L7/L8 job tables consumed by SettlementPopulation)
    jobs_total = 0
    jobs_why = 0
    for prov, s in sheets.items():
        for rkey, rblock in s.get("regions", {}).items():
            for role, jinfo in rblock.get("jobs", {}).items():
                jobs_total += 1
                wlist = jinfo.get("why") if isinstance(jinfo, dict) else None
                if check_why_list(f"settlement_jobs:{prov}/{rkey}:{role}", wlist, all_fields, forbidden_pairs, errors):
                    jobs_why += 1
    record_stat("settlement_jobs", jobs_total, jobs_why)

    # 2. Dungeon identity (mod/src/main/resources/skycraft/dungeons/identity/*.json)
    di_total = 0
    di_why = 0
    ident_dir = os.path.join(SKY, "dungeons", "identity")
    if os.path.isdir(ident_dir):
        for path in sorted(glob.glob(os.path.join(ident_dir, "*.json"))):
            bn = os.path.basename(path)
            if bn == "index.json":
                continue
            prov_key = os.path.splitext(bn)[0]
            if province_filter and prov_key != province_filter:
                continue
            prov_themes = sheets.get(prov_key, {}).get("dungeon_themes", {})
            data = load_json(path)
            for pid, entry in data.get("places", {}).items():
                di_total += 1
                wlist = entry.get("why")
                if wlist is None and isinstance(prov_themes, dict):
                    wlist = prov_themes.get(entry.get("family", ""))
                if check_why_list(f"dungeon_identity:{bn}:{pid}", wlist, all_fields, forbidden_pairs, errors):
                    di_why += 1
    record_stat("dungeon_identity", di_total, di_why)

    # 3. Places (mod/src/main/resources/skycraft/tamriel/places/*.json)
    pl_total = 0
    pl_why = 0
    places_dir = os.path.join(SKY, "tamriel", "places")
    idx_path = os.path.join(places_dir, "index.json")
    if os.path.exists(idx_path):
        for fn in load_json(idx_path):
            if province_filter and not fn.startswith(province_filter):
                continue
            p_path = os.path.join(places_dir, fn)
            if not os.path.exists(p_path):
                continue
            for p in load_json(p_path):
                if not isinstance(p, dict):
                    continue
                if province_filter and not str(p.get("region", "")).startswith(f"{province_filter}/"):
                    continue
                pl_total += 1
                if check_why_list(f"places:{fn}:{p.get('id', '?')}", p.get("why"), all_fields, forbidden_pairs, errors):
                    pl_why += 1
    record_stat("places", pl_total, pl_why)

    # 4. Quests (mod/src/main/resources/skycraft/quests/*.json)
    q_total = 0
    q_why = 0
    quests_dir = os.path.join(SKY, "quests")
    q_idx = os.path.join(quests_dir, "index.json")
    if os.path.exists(q_idx):
        for fn in load_json(q_idx):
            q_path = os.path.join(quests_dir, fn)
            if not os.path.exists(q_path):
                continue
            qdata = load_json(q_path)
            qlist = qdata.get("quests", []) if isinstance(qdata, dict) else (qdata if isinstance(qdata, list) else [])
            for q in qlist:
                if not isinstance(q, dict):
                    continue
                reg = str(q.get("region", q.get("province", "")))
                if province_filter and province_filter not in fn and not reg.startswith(province_filter):
                    continue
                q_total += 1
                if check_why_list(f"quests:{fn}:{q.get('id', '?')}", q.get("why"), all_fields, forbidden_pairs, errors):
                    q_why += 1
    record_stat("quests", q_total, q_why)

    # 5. Scatter scene kinds (mod/src/main/resources/skycraft/scatter/kinds.json)
    sk_total = 0
    sk_why = 0
    kinds_path = os.path.join(SKY, "scatter", "kinds.json")
    if os.path.exists(kinds_path):
        kdata = load_json(kinds_path)
        for kid, kval in kdata.items():
            if kid.startswith("_") or not isinstance(kval, dict):
                continue
            sk_total += 1
            if check_why_list(f"scatter_kinds:{kid}", kval.get("why"), all_fields, forbidden_pairs, errors):
                sk_why += 1
    record_stat("scatter_kinds", sk_total, sk_why)

    # 6. Lore places & books (skycraft/lore/places/*.json and skycraft/realms/books.json)
    lore_total = 0
    lore_why = 0
    lore_dir = os.path.join(SKY, "lore", "places")
    if os.path.isdir(lore_dir):
        for path in sorted(glob.glob(os.path.join(lore_dir, "*.json"))):
            bn = os.path.basename(path)
            if bn == "index.json":
                continue
            if province_filter and province_filter not in bn and bn != "loreweb.json":
                continue
            ldata = load_json(path)
            entries = ldata.get("places", ldata) if isinstance(ldata, dict) else {}
            if isinstance(entries, dict):
                for pid, pval in entries.items():
                    if not isinstance(pval, dict):
                        continue
                    lore_total += 1
                    if check_why_list(f"lore:{bn}:{pid}", pval.get("why"), all_fields, forbidden_pairs, errors):
                        lore_why += 1
    books_path = os.path.join(SKY, "realms", "books.json")
    if os.path.exists(books_path) and not province_filter:
        bdata = load_json(books_path)
        blist = bdata.get("books", bdata) if isinstance(bdata, dict) else bdata
        if isinstance(blist, list):
            for b in blist:
                if isinstance(b, dict):
                    lore_total += 1
                    if check_why_list(f"books:{b.get('id', '?')}", b.get("why"), all_fields, forbidden_pairs, errors):
                        lore_why += 1
    record_stat("lore", lore_total, lore_why)

    # 7. Region spawns (data/skycraft/region_spawns/*.json)
    sp_total = 0
    sp_why = 0
    sp_dir = os.path.join(RES, "data", "skycraft", "region_spawns")
    if os.path.isdir(sp_dir):
        for path in sorted(glob.glob(os.path.join(sp_dir, "*.json"))):
            bn = os.path.basename(path)
            if province_filter and not bn.startswith(province_filter):
                continue
            sdata = load_json(path)
            slist = sdata if isinstance(sdata, list) else ([sdata] if isinstance(sdata, dict) else [])
            for idx, entry in enumerate(slist):
                if isinstance(entry, dict):
                    sp_total += 1
                    if check_why_list(f"region_spawns:{bn}[{idx}]", entry.get("why"), all_fields, forbidden_pairs, errors):
                        sp_why += 1
    record_stat("region_spawns", sp_total, sp_why)

    # 8. Palettes micro-biome rules (skycraft/tamriel/palettes.json once R3b lands)
    pm_total = 0
    pm_why = 0
    pal_path = os.path.join(SKY, "tamriel", "palettes.json")
    if os.path.exists(pal_path):
        pdata = load_json(pal_path)
        if isinstance(pdata, dict):
            for rkey, pval in pdata.items():
                if province_filter and not rkey.startswith(f"{province_filter}/"):
                    continue
                if isinstance(pval, dict) and isinstance(pval.get("micro"), list):
                    for idx, rule in enumerate(pval["micro"]):
                        if isinstance(rule, dict):
                            pm_total += 1
                            if check_why_list(f"palettes_micro:{rkey}[{idx}]", rule.get("why"), all_fields, forbidden_pairs, errors):
                                pm_why += 1
    record_stat("palettes_micro", pm_total, pm_why)

    return stats


def main() -> int:
    ap = argparse.ArgumentParser(description="Validate MS22 world-model sheets and F-WHY links.")
    ap.add_argument("--province", help="Filter validation to a specific province (e.g. skyrim)")
    ap.add_argument("--json", help="Optional path to write JSON report")
    args = ap.parse_args()

    errors: list[str] = []
    sheets, all_fields = validate_sheets(args.province, errors)
    stats = scan_content(args.province, sheets, all_fields, errors)

    total_blocks = sum(1 + len(s.get("regions", {})) for s in sheets.values())
    total_fields = len(all_fields)
    consumers_with_why = sum(1 for v in stats.values() if v["with_why"] > 0)

    print(
        f"Worldsheets: {len(sheets)} province(s), {total_blocks} blocks x 11 layers, "
        f"{total_fields} fields, {consumers_with_why} active why-consumers"
    )
    print(f"{'Content type':<20} {'With why':>10} {'Total':>10} {'F-WHY':>10} {'Legacy back-fill':>18}")
    print("-" * 72)
    grand_why = 0
    grand_total = 0
    for cat, st in stats.items():
        grand_why += st["with_why"]
        grand_total += st["total"]
        f_str = f"{st['f_why']:.1f}%" if st["total"] > 0 else "n/a (0)"
        print(f"{cat:<20} {st['with_why']:>10} {st['total']:>10} {f_str:>10} {st['legacy_backfill']:>18}")
    print("-" * 72)
    overall_f = (100.0 * grand_why / grand_total) if grand_total > 0 else 100.0
    print(
        f"{'TOTAL':<20} {grand_why:>10} {grand_total:>10} {overall_f:>9.1f}% {grand_total - grand_why:>18}"
    )

    if args.json:
        os.makedirs(os.path.dirname(os.path.abspath(args.json)), exist_ok=True)
        with open(args.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(
                {
                    "provinces": list(sheets.keys()),
                    "blocks": total_blocks,
                    "fields": total_fields,
                    "consumers_with_why": consumers_with_why,
                    "errors": errors,
                    "stats": stats,
                },
                f,
                indent=2,
            )

    for e in errors[:25]:
        print(e)
    if len(errors) > 25:
        print(f"... and {len(errors) - 25} more errors")

    print(
        f"worldsheet_lint: {len(errors)} errors, {total_blocks} blocks x 11 layers, "
        f"F-WHY {grand_why}/{grand_total} ({overall_f:.1f}%), {consumers_with_why} consumers"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
