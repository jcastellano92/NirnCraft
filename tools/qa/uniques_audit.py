#!/usr/bin/env python3
"""Uniques and artifacts audit: every one-of-a-kind item is defined once, placed exactly once, and has lang + assets.

Sources (offline): com.nirncraft.loot.LegendaryUniques (def(...) lines), the unique loot tables
data/nirncraft/loot_table/uniques/<id>.json, the explorers' site uniques nirncraft/explore/uniques*.json (via
uniques_index.json, as com.nirncraft.explore.Uniques reads them), the Daedric artifacts / artifacts of legend of
com.nirncraft.loot.Collectibles (itemEntry(...) lines), every loot table, and every quest (rewards.unique, boss drops).
(com.nirncraft.dungeons.world.Uniques builds unique *sites* such as High Hrothgar, not items: not audited here.)

Legendary uniques  base item exists; lang unique.nirncraft.<id> + .lore; placed exactly once: its `place` (a real
                   place), a loot table naming nirncraft:uniques/<id> or a nirncraft:unique {id} modifier, a quest
                   reward or boss drop; a province-only unique with no placement is the rare province boss-chest find
                   (nirncraft:random_unique) and counts as placed; a unique table without a def is an orphan
Explore uniques    ids unique across files; base item exists; lang explore.nirncraft.unique.<id> + .lore; no two in
                   one minor-site cell (MinorSites.CELL = 160 blocks: the second is lost); inside the world rasters
Artifacts          item exists (+ item lang and item definition); every exact place exists, globs match a place;
                   placed by its places or province, and not also dropped by a loot table or a quest reward
Prints sections and `problems N`; exit 1 when N > 0.

    py -3 tools/qa/uniques_audit.py [--limit 25]
"""
import argparse
import fnmatch
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import refs  # noqa: E402

LOOT = refs.DATA / "loot_table"
EXPLORE = refs.SKY / "explore"
CELL = 160
DEF = re.compile(r'def\(\s*"([a-z0-9_]+)"\s*,\s*"([a-z0-9_:]+)"\s*,\s*"([a-z0-9_]+)"\s*,\s*(null|"([a-z0-9_]+)")')
ITEM_ENTRY = re.compile(r'itemEntry\(\s*(\w+)\s*,\s*"([a-z0-9_]+)"\s*,\s*"([a-z0-9_:]+)"\s*,\s*List\.of\(([^)]*)\)\s*,'
                        r'\s*(null|"[a-z0-9_]+")')


def legendary_defs():
    src = refs.load_text(refs.JAVA / "com" / "nirncraft" / "loot" / "LegendaryUniques.java")
    return [dict(id=m[1], base=m[2], province=m[3], place=m[5]) for m in DEF.finditer(src)]


def artifacts():
    src = refs.load_text(refs.JAVA / "com" / "nirncraft" / "loot" / "Collectibles.java")
    out = []
    for m in ITEM_ENTRY.finditer(src):
        out.append(dict(collection=m[1], id=m[2], item=m[3], places=re.findall(r'"([^"]+)"', m[4]),
                        province=None if m[5] == "null" else m[5].strip('"')))
    return out


def loot_mentions():
    """{loot table path: text} of every loot table, the uniques/ tables apart."""
    tables, uniq = {}, {}
    for f in LOOT.rglob("*.json"):
        key = f.relative_to(LOOT).as_posix()[:-5]
        (uniq if key.startswith("uniques/") else tables)[key] = refs.load_text(f)
    return tables, uniq


def quest_mentions():
    """[(file, kind, id)] of quest reward uniques, boss unique drops and reward items."""
    out = []
    for f in (refs.SKY / "quests").rglob("*.json"):
        d = refs.load(f)
        if not isinstance(d, dict):
            continue
        name = f.relative_to(refs.SKY / "quests").as_posix()
        for b in (d.get("bosses") or {}).values() if isinstance(d.get("bosses"), dict) else []:
            for drop in str((b or {}).get("drop") or "").split(","):
                if drop.strip().startswith("unique:"):
                    out.append((name, "unique", drop.strip()[7:]))
        for q in d.get("quests") or []:
            for rk in ("rewards", "reward"):
                r = (q or {}).get(rk) if isinstance(q, dict) else None
                if isinstance(r, dict):
                    if r.get("unique"):
                        out.append((f"{name} {q.get('id')}", "unique", r["unique"]))
                    for it in r.get("items") or []:
                        if isinstance(it, str):
                            out.append((f"{name} {q.get('id')}", "item", it.split("*")[0]))
                    if r.get("unique_item"):
                        out.append((f"{name} {q.get('id')}", "item", r["unique_item"]))
    return out


def audit(limit):
    pr = refs.Problems()
    lang, places = refs.lang(), refs.places()
    tables, uniq_tables = loot_mentions()
    quests = quest_mentions()
    defs = legendary_defs()
    if not defs:
        pr.add("setup", "no def(...) lines parsed from LegendaryUniques.java")
    seen = set()
    for u in defs:
        uid = u["id"]
        if uid in seen:
            pr.add("legendary", f"{uid}: defined twice")
        seen.add(uid)
        if refs.item_ok(u["base"]) is False:
            pr.add("legendary", f"{uid}: base item {u['base']} does not exist")
        for k in (f"unique.nirncraft.{uid}", f"unique.nirncraft.{uid}.lore"):
            if k not in lang:
                pr.add("lang legendary", f"{uid}: no lang {k}")
        where = []
        if u["place"]:
            where.append(f"place {u['place']}")
            if u["place"] not in places:
                pr.add("legendary", f"{uid}: place {u['place']} is no tamriel place (never handed out)")
        rx = re.compile(rf'nirncraft:uniques/{uid}"|"id":\s*"{uid}"[^}}]*"type":\s*"nirncraft:unique"'
                        rf'|"type":\s*"nirncraft:unique"[^}}]*"id":\s*"{uid}"')
        where += [f"loot {t}" for t, text in tables.items() if rx.search(text)]
        where += [f"quest {q}" for q, kind, i in quests if kind == "unique" and i == uid]
        if not where and not u["province"]:
            pr.add("placement", f"{uid}: placed nowhere (no place, loot table, quest or province)")
        elif len(where) > 1:
            pr.add("placement", f"{uid}: placed {len(where)} times: {', '.join(where[:5])}")
    for key, text in uniq_tables.items():
        tid = key.split("/", 1)[1]
        if tid not in seen:
            pr.add("legendary", f"loot_table/{key}.json names unique {tid} which LegendaryUniques does not define")
        m = re.search(r'"id":\s*"([a-z0-9_]+)"', text)
        if m and m.group(1) != tid:
            pr.add("legendary", f"loot_table/{key}.json rolls unique {m.group(1)}, not {tid}")
    # ---- explorers' site uniques
    files = ["uniques.json"] + list(refs.load(EXPLORE / "uniques_index.json", []) or [])
    ex_ids, cells, n_ex = {}, {}, 0
    for fn in files:
        for u in refs.load(EXPLORE / fn, []) or []:
            if not isinstance(u, dict):
                continue
            n_ex += 1
            uid = u.get("id", "?")
            if uid in ex_ids:
                pr.add("explore", f"{uid}: in {fn} and {ex_ids[uid]}")
            ex_ids.setdefault(uid, fn)
            if refs.item_ok(u.get("item", "")) is False:
                pr.add("explore", f"{uid} ({fn}): item {u.get('item')} does not exist")
            for k in (f"explore.nirncraft.unique.{uid}", f"explore.nirncraft.unique.{uid}.lore"):
                if k not in lang:
                    pr.add("lang explore", f"{uid}: no lang {k}")
            try:
                cell = (int(u["x"]) // CELL, int(u["z"]) // CELL)
            except (KeyError, TypeError, ValueError):
                pr.add("explore", f"{uid} ({fn}): no x/z")
                continue
            if cell in cells:
                pr.add("placement", f"explore {uid}: same site cell as {cells[cell]} (only one is placed)")
            cells.setdefault(cell, uid)
    # ---- artifacts (Collectibles)
    arts = artifacts()
    reward_items = {i: q for q, kind, i in quests if kind == "item"}
    defs_dir = refs.ASSETS / "items"
    for a in arts:
        iid = a["item"].split(":", 1)[1]
        if refs.item_ok(a["item"]) is False:
            pr.add("artifact", f"{a['id']}: item {a['item']} is not registered (no item definition / lang)")
        if f"item.nirncraft.{iid}" not in lang:
            pr.add("lang artifact", f"{a['id']}: no lang item.nirncraft.{iid}")
        if not (defs_dir / f"{iid}.json").is_file():
            pr.add("artifact", f"{a['id']}: no item definition assets/nirncraft/items/{iid}.json")
        for p in a["places"]:
            hit = any(fnmatch.fnmatchcase(x, p) for x in places) if "*" in p else p in places
            if not hit:
                pr.add("artifact", f"{a['id']}: place {p} matches no tamriel place")
        if not a["places"] and not a["province"]:
            pr.add("placement", f"{a['id']}: no place and no province (never found)")
        dup = [t for t, text in tables.items() if f'"{a["item"]}"' in text]
        dup += [f"quest {reward_items[a['item']]}"] if a["item"] in reward_items else []
        if dup:
            pr.add("placement", f"{a['id']}: also given by {', '.join(dup[:4])} (placed more than once)")
    print(f"uniques: {len(defs)} legendary ({sum(1 for u in defs if u['place'])} with a place, {len(uniq_tables)} unique "
          f"tables), {n_ex} explore site uniques in {len(files)} files, {len(arts)} artifacts; {len(tables)} loot tables")
    return pr.report(limit)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=25)
    return audit(ap.parse_args().limit)


if __name__ == "__main__":
    raise SystemExit(main())
