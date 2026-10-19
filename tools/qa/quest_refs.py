#!/usr/bin/env python3
"""Quest reference audit: every id a quest names must exist (offline, no game).

Covers every JSON under mod/src/main/resources/nirncraft/quests (the questline files of index.json, the regional story
chains in story/, the notice-board templates) and checks, per quest:
  places    objective / find / stage / hook / npc places resolve like QuestPlaces.find: a tamriel place (places index),
            a fallback anchor (quests/places.json), a place a questline file declares, or land_<x>_<z>
  npcs      objective / choice npcs, givers, `start: npc:<id>`, relocate / summon / retire actions name a declared npc
  entities  spawn types, boss types, `entity` lists and npc entities are registered (nirncraft Java registrations,
            vanilla 26.3 EntityTypes when the reference jar is present)
  items     reward items, objective items, give: actions, unique_item and boss gear are items (item definitions / lang,
            vanilla 26.3 items)
  relics, bosses, scenes   relic / take actions, boss_id spawns and boss targets, scene actions and scene_refs exist
  quests    requires `quest:<id>`, prereq and start: actions name existing quests; quest ids are unique
  uniques   rewards.unique names a legendary unique (data/nirncraft/loot_table/uniques/<id>.json)
  lang      string values that are lang keys (`story.nirncraft.<...>.title` ...) exist in mod/src/main/lang
Variables ({var}, @var) are skipped. Prints one section per kind (worst first) and `problems N`; exit 1 when N > 0.

    py -3 tools/qa/quest_refs.py [--limit 25]
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import refs  # noqa: E402

QDIR = refs.SKY / "quests"
UNIQUES = refs.DATA / "loot_table" / "uniques"


def spawn_aliases():
    """Story creature ids QuestSpawner maps onto a registered stand-in (nirncraft:alduin -> nirncraft:dragon)."""
    src = refs.load_text(refs.JAVA / "com" / "nirncraft" / "quest" / "QuestSpawner.java")
    m = re.search(r"FALLBACKS\s*=\s*Map\.ofEntries\((.*?)\);", src, re.S)
    return set(re.findall(r'Map\.entry\(\s*"([a-z0-9_]+:[a-z0-9_/]+)"', m.group(1))) if m else set()


def is_var(s):
    return not isinstance(s, str) or not s or "{" in s or s.startswith("@") or "$" in s


def item_id(spec):
    s = spec.strip().split("*")[0].split("{")[0].split("[")[0].strip()
    return s if ":" in s else ("minecraft:" + s if s else "")


def actions(text):
    for raw in (text or "").split(";"):
        a = raw.strip()
        if a:
            k, _, arg = a.partition(":")
            yield k, arg


class Index:
    """Everything the questline files declare (StoryData merges all files: ids are global)."""

    def __init__(self, files):
        self.npcs, self.relics, self.scenes, self.bosses, self.places, self.quests = set(), set(), set(), set(), set(), {}
        self.dupes = []
        fallback = refs.load(QDIR / "places.json", {}) or {}
        self.places |= {k for k in fallback if not k.startswith("_")}
        for f, d in files:
            if not isinstance(d, dict):
                continue
            self.npcs |= {n.get("id") for n in d.get("npcs") or [] if isinstance(n, dict)}
            self.relics |= {r.get("id") for r in d.get("relics") or [] if isinstance(r, dict)}
            for k in ("scenes", "bosses", "places"):
                v = d.get(k)
                if isinstance(v, dict):
                    getattr(self, k).update(v.keys())
            for q in d.get("quests") or []:
                if isinstance(q, dict) and q.get("id"):
                    if q["id"] in self.quests:
                        self.dupes.append(f"{q['id']} in {f} and {self.quests[q['id']]}")
                    self.quests.setdefault(q["id"], f)
        self.tamriel = refs.places()

    def place_ok(self, pid):
        return pid in self.tamriel or pid in self.places or bool(re.fullmatch(r"land_-?\d+_-?\d+", pid))


def audit(limit):
    files = []
    for p in sorted(QDIR.rglob("*.json")):
        d = refs.load(p)
        if d is None:
            print(f"cannot parse {refs.rel(p)}")
        files.append((p.relative_to(QDIR).as_posix(), d))
    ix = Index(files)
    lang = refs.lang()
    pr = refs.Problems()
    aliases = spawn_aliases()

    def spawnable(t):  # a registered type, a QuestSpawner stand-in alias, or None (npc:<role>, other namespaces)
        ok = refs.entity_ok(t)
        return True if ok is False and t in aliases else ok
    for d in ix.dupes:
        pr.add("duplicate quest id", d)
    n_quests = 0

    checked = {}

    def ref(kind, ok, value, where):
        if is_var(value):
            return
        checked[kind] = checked.get(kind, 0) + (ok is not None)
        if ok is None or ok:
            return
        pr.add(kind, f"{where}: {value}")

    for fname, d in files:
        if not isinstance(d, dict):
            continue
        hook = d.get("hook") if isinstance(d.get("hook"), dict) else {}
        for p in [hook.get("place")] + list(hook.get("places") or []):
            if p:
                ref("place", ix.place_ok(p), p, f"{fname} hook")
        for n in d.get("npcs") or []:
            if isinstance(n, dict):
                if n.get("place"):
                    ref("place", ix.place_ok(n["place"]), n["place"], f"{fname} npc {n.get('id')}")
                if n.get("entity"):
                    ref("entity", refs.entity_ok(n["entity"]), n["entity"], f"{fname} npc {n.get('id')}")
        for bid, b in (d.get("bosses") or {}).items() if isinstance(d.get("bosses"), dict) else []:
            if isinstance(b, dict):
                if b.get("type"):
                    ref("entity", spawnable(b["type"]), b["type"], f"{fname} boss {bid}")
                gear = [b.get("weapon"), b.get("armor")] + list((b.get("gear") or {}).values())
                for drop in str(b.get("drop") or "").split(","):  # "unique:umbra,loot:legendary"
                    drop = drop.strip()
                    if ":" in drop and not drop.startswith(("loot:", "unique:")):
                        gear.append(drop)
                    if drop.startswith("unique:"):
                        ref("unique", (UNIQUES / (drop[7:] + ".json")).is_file(), drop[7:], f"{fname} boss {bid}")
                for g in gear:
                    if isinstance(g, str) and ":" in g:
                        ref("item", refs.item_ok(item_id(g)), item_id(g), f"{fname} boss {bid}")
        for q in d.get("quests") or []:
            if not isinstance(q, dict):
                continue
            n_quests += 1
            qid = q.get("id", "?")
            w = f"{fname} {qid}"
            for k in ("title", "desc", "summary", "offer", "pitch", "outro"):
                v = q.get(k)
                if refs.looks_like_lang_key(v) and v not in lang:
                    pr.add("lang key", f"{w}: {k} {v}")
            npcs = [q.get("giver")] + ([q["start"][4:]] if str(q.get("start", "")).startswith("npc:") else [])
            places = list(q.get("places") or []) + [s.get("place") for s in q.get("stages") or [] if isinstance(s, dict)]
            if isinstance(q.get("find"), dict):
                places.append(q["find"].get("place"))
            acts = [q.get("on_start"), q.get("on_complete")]
            for o in q.get("objectives") or []:
                if not isinstance(o, dict):
                    continue
                places.append(o.get("place"))
                npcs.append(o.get("npc"))
                acts.append(o.get("actions"))
                for c in o.get("choices") or []:
                    if isinstance(c, dict):
                        npcs.append(c.get("npc"))
                        acts.append(c.get("actions"))
                if o.get("item"):
                    ref("item", refs.item_ok(item_id(o["item"])), item_id(o["item"]), w)
                for e in str(o.get("entity") or "").split(","):
                    if e.strip():
                        ref("entity", refs.entity_ok(e.strip()), e.strip(), w)
                if o.get("relic"):
                    ref("relic", o["relic"] in ix.relics, o["relic"], w)
                if o.get("scene"):
                    ref("scene", o["scene"] in ix.scenes, o["scene"], w)
                if o.get("boss"):
                    ref("boss", o["boss"] in ix.bosses, o["boss"], w)
                t = o.get("target")
                if isinstance(t, str) and t:
                    ref("scene" if t.startswith("scene:") else "boss",
                        t[6:] in ix.scenes if t.startswith("scene:") else t in ix.bosses, t, w)
                for s in o.get("spawn") or []:
                    if isinstance(s, dict):
                        if s.get("type"):
                            ref("entity", spawnable(s["type"]), s["type"], w)
                        if s.get("boss_id"):
                            ref("boss", s["boss_id"] in ix.bosses, s["boss_id"], w)
            for s in q.get("scene_refs") or []:
                ref("scene", s.split(":", 1)[-1] in ix.scenes, s, w)
            for a in acts:
                for k, arg in actions(a):
                    if k == "give":
                        for part in arg.split(","):
                            ref("item", refs.item_ok(item_id(part)), item_id(part), w + " give")
                    elif k in ("relic", "take"):
                        ref("relic", arg in ix.relics, arg, f"{w} {k}")
                    elif k == "scene":
                        ref("scene", arg in ix.scenes, arg, w)
                    elif k == "start":
                        ref("quest", arg in ix.quests, arg, w + " start")
                    elif k in ("relocate", "summon", "retire"):
                        bits = arg.split(":")
                        npcs.append(bits[0])
                        if k == "relocate" and len(bits) > 1:
                            places.append(bits[1])
            for p in places:
                if p:
                    ref("place", ix.place_ok(p), p, w)
            for n in npcs:
                for one in str(n or "").split("|"):  # choices may name alternatives: "gerdur|alvor"
                    if one:
                        ref("npc", one in ix.npcs, one, w)
            for r in q.get("requires") or []:
                for alt in str(r).split("|"):  # Story.cond: "quest:a|faction:b" is an OR
                    alt = alt.strip().lstrip("!")
                    if alt.startswith("quest:"):
                        ref("quest", alt[6:] in ix.quests, alt, w)
            if q.get("prereq"):
                ref("quest", q["prereq"] in ix.quests, q["prereq"], w + " prereq")
            for rk in ("rewards", "reward"):
                r = q.get(rk)
                if not isinstance(r, dict):
                    continue
                for it in r.get("items") or []:
                    iid = item_id(it if isinstance(it, str) else str(it.get("id") or it.get("item") or ""))
                    ref("item", refs.item_ok(iid), iid, w + " reward")
                if r.get("unique_item"):
                    ref("item", refs.item_ok(item_id(r["unique_item"])), r["unique_item"], w + " reward")
                if r.get("unique"):
                    ref("unique", (UNIQUES / (r["unique"] + ".json")).is_file(), r["unique"], w + " reward")
    print(f"quest refs: {len(files)} files, {n_quests} quests; {len(ix.npcs)} npcs, {len(ix.relics)} relics, "
          f"{len(ix.scenes)} scenes, {len(ix.bosses)} bosses declared; {len(ix.tamriel)} tamriel places + "
          f"{len(ix.places)} quest places; {len(refs.nirn_entities())} nirncraft entities; {refs.jar_note()}")
    print("references checked: " + ", ".join(f"{k} {v}" for k, v in sorted(checked.items())))
    return pr.report(limit)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=25)
    return audit(ap.parse_args().limit)


if __name__ == "__main__":
    raise SystemExit(main())
