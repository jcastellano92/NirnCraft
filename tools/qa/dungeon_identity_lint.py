"""Dungeon identity lint: does each named Skyrim dungeon feel authored, or generic? (offline, from the exports)

  1. tools\\qa\\qa.ps1 -Server -ServerCmds "skycraft-dungeon-qa export <ids, at most 12 per command>|..."
     (the export paints each dungeon in memory; one command is one tick, so more than ~12 ids trip the 60 s watchdog)
  2. py -3 -I tools/qa/dungeon_identity_lint.py [--exports mod/run/qa-exports] [--top 25] [--json out.json] [--static]

Per Skyrim dungeon or fort (places of skyrim_landmarks.json) it flags what is missing:

  no_sign        no sign at the entrance naming the place and its person
  no_lectern     no lectern with a book anywhere (journal)
  no_lore_note   no lectern holds one of the place's own lore notes (a named person's words)
  no_boss        the dungeon has no boss-tagged enemy (surface camps and ruins without a master are exempt)
  unnamed_boss   a boss without a name
  roster         the enemies do not match what the place's lore and structure say lives there
  no_motif       no set piece of its own (a blueprint motif for its name); theme set pieces alone do not count
  generic_loot   no chest holds the place's own story table (chests/story_*), and under a quarter hold a themed table
  no_trail       the place is isolated: no road or trail reaches it (tools/qa/reach_lint.py)
  lore_mismatch  the lore's hook speaks of a different kind of inhabitant than the structure builds (data bug)

--static skips the exports: only the data checks run (identity entry, lore, trail, lore mismatch).
Exit code 1 when --assert-max N is given and a flag count exceeds it. Writes tools/qa/out/dungeon_identity.json
(per place flags) for before/after comparison (--compare old.json).
"""
import argparse
import collections
import glob
import gzip
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SK = os.path.join(ROOT, "mod", "src", "main", "resources", "skycraft")
OUT = os.path.join(HERE, "out")

FLAGS = ["no_sign", "no_lectern", "no_lore_note", "no_boss", "unnamed_boss", "roster", "no_motif", "generic_loot", "no_trail",
         "lore_mismatch"]

# variant -> expected inhabitants (entity-id substrings). Anything in the list counts as story-matching.
FAMILY = {
    "barrow": "barrow", "bleak_falls_barrow": "barrow", "forelhost": "barrow", "ysgramors_tomb": "barrow", "ustengrav": "barrow",
    "saarthal": "barrow", "labyrinthian": "barrow", "atmoran_tomb": "barrow", "dragon_mound": "barrow", "word_wall": "barrow",
    "nordic_ruin": "ruin", "breton_crypt": "ruin", "yokudan_tomb": "ruin", "orc_ruin": "ruin",
    "daedric_ruin": "ruin", "ayleid_ruin": "ruin", "crystal_vault": "ruin", "imperial_crypt": "ruin",
    "velothi_tomb": "ruin", "xanmeer": "ruin", "moon_temple": "ruin", "tree_tomb": "ruin",
    "sea_vault": "ruin", "akaviri_ruin": "ruin", "swamp_ruin": "ruin", "kothringi_barrow": "ruin",
    "sunken_shrine": "ruin", "adeptorium": "ruin", "sandstone_tomb": "ruin", "khajiit_shrine": "ruin",
    "bosmer_ossuary": "ruin", "canopy_shrine": "ruin", "treethane_seat": "ruin", "elven_ruin": "ruin",
    "wayshrine": "ruin", "ash_shrine": "ruin", "ka_po_tun_shrine": "ruin", "orichalc_ruin": "ruin",
    "dwemer_ruin": "dwemer", "great_lift": "dwemer", "mzark": "dwemer",
    "bandit_cave": "bandit", "bandit_camp": "bandit", "ashlander_camp": "bandit", "naga_camp": "bandit",
    "baandari_camp": "bandit", "wood_orc_camp": "bandit", "hunter_camp": "bandit",
    "fort": "fort", "keep": "fort", "akaviri_fort": "fort", "breton_keep_ruin": "fort", "orc_stronghold": "fort",
    "dunmer_stronghold": "fort", "imperial_prison_fort": "fort", "canyon_redoubt": "fort", "tsaesci_fortress": "fort",
    "fort_mage": "mage", "fort_vampire": "vampire", "fort_forsworn": "forsworn", "fort_necromancer": "necromancer",
    "mushroom_tower": "mage", "altmer_sky_spire": "mage", "arcane_sanctum": "mage",
    "vampire_lair": "vampire", "necromancer_lair": "necromancer", "sixth_house_base": "necromancer",
    "cultist_lair": "necromancer", "sload_grotto": "necromancer", "sload_coral_spire": "necromancer",
    "hagraven_lair": "hagraven", "falmer_cave": "falmer", "den": "beast", "skeever_cave": "beast", "spider_cave": "beast",
    "troll_cave": "beast", "scorpion_den": "beast", "hound_den": "beast", "ice_cave": "ice", "kamal_ice_vault": "ice",
    "mine": "mine", "kolskeggr_mine": "mine", "egg_mine": "mine", "kwama_egg_mine": "mine",
    "ebony_mine": "mine", "glass_mine": "mine", "crystal_quarry": "mine",
    "forsworn_camp": "forsworn", "forsworn_cave": "forsworn", "forsworn_tower": "forsworn",
    "spriggan_cave": "grove", "grove": "grove", "hist_grove": "grove", "desert_oasis": "grove",
    "graht_grove": "grove", "root_hollow": "grove",
    "smuggler_cave": "smuggler", "grotto": "smuggler", "smuggler_cove": "smuggler", "sugar_smuggler_den": "smuggler",
    "coral_cove": "smuggler", "sea_elf_anchorage": "smuggler", "coastal_grotto": "smuggler",
    "maormer_anchorage": "smuggler", "sea_serpent_cove": "smuggler", "sea_cave": "smuggler", "island": "smuggler", "atoll": "smuggler",
    "cave": "cave", "coral_pit": "cave", "mud_cavern": "cave", "marsh_cavern": "cave",
    "badlands_cave": "cave", "desert_cave": "cave", "giant_camp": "giant", "dragon_lair": "dragon", "dragon_roost": "dragon",
    "watchtower": "tower", "nordic_tower": "tower", "tower": "tower", "velothi_tower": "tower",
    "tang_mo_pagoda": "tower", "lighthouse": "tower", "mage_tower": "mage",
    "imperial_camp": "military", "stormcloak_camp": "military", "cabin": "cabin",
    "shipwreck": "wreck", "wreck": "wreck", "reef": "wreck", "abyssal_reef": "wreck",
}
EXPECT = {
    "barrow": ["draugr", "skeleton", "wight", "zombie", "ice_wraith", "skeever", "troll", "bat", "ash_spawn", "netch"],
    "ruin": ["draugr", "skeleton", "zombie", "wight", "bandit", "forsworn", "soldier", "guardian", "necromancer", "vampire"],
    "dwemer": ["dwarven", "falmer", "chaurus", "spider", "sphere", "centurion", "automaton", "falmer"],
    "bandit": ["bandit", "vindicator", "pillager", "forsworn", "soldier", "guard", "skeever", "wolf", "bear", "mercenary", "chief"],
    "fort": ["bandit", "vindicator", "forsworn", "soldier", "guard", "vampire", "necromancer", "draugr", "skeleton", "evoker", "witch"],
    "military": ["soldier", "guard", "villager", "legion", "stormcloak", "vindicator", "bandit", "wolf"],
    "mage": ["necromancer", "evoker", "witch", "mage", "vindicator", "skeleton", "zombie", "draugr", "vex", "atronach", "conjurer"],
    "vampire": ["vampire", "gargoyle", "death_hound", "thrall", "bat", "witch", "spider", "skeleton"],
    "necromancer": ["necromancer", "draugr", "skeleton", "zombie", "evoker", "witch", "wraith", "vex", "ghost", "husk"],
    "hagraven": ["hagraven", "forsworn", "witch", "spider", "wolf", "bear", "vindicator", "briarheart"],
    "forsworn": ["forsworn", "hagraven", "vindicator", "witch", "wolf", "bear", "briarheart", "spider"],
    "falmer": ["falmer", "chaurus", "spider", "zombie", "troll"],
    "beast": ["bear", "wolf", "spider", "skeever", "troll", "sabre", "frostbite", "ravager", "cat", "bat"],
    "ice": ["ice", "wraith", "frost", "spider", "troll", "stray", "wolf", "bear", "falmer", "draugr", "skeleton"],
    "mine": ["forsworn", "hagraven", "bandit", "spider", "skeever", "troll", "falmer", "dwarven", "vindicator", "silverfish", "chaurus", "zombie", "skeleton"],
    "grove": ["spriggan", "wisp", "vex", "wolf", "bear", "spider", "witch", "forest"],
    "smuggler": ["bandit", "vindicator", "pillager", "skeever", "mudcrab", "soldier", "guard", "drowned", "spider"],
    "cave": [""],
    "giant": ["giant", "mammoth", "wolf", "ravager", "bear", "iron_golem", "troll"],
    "dragon": ["dragon", "draugr", "skeleton", "zombie", "wight", "bandit", "wolf"],
    "tower": [""],
    "cabin": [""],
    "wreck": [""],
}
# families that have no master of their own to find
NO_BOSS_OK = {"cabin", "wreck", "tower", "dragon"}
KW = {"forsworn": r"forsworn|hagraven|briarheart", "vampire": r"vampire|thrall|volkihar", "necromancer": r"necromanc|reanimat|ritualist",
      "bandit": r"bandit|outlaw|thieves|brigand|smuggler", "falmer": r"falmer|chaurus", "dwemer": r"dwemer|dwarven|centurion|animuncul",
      "draugr": r"draugr|barrow|sarcoph|entomb|crypt|tomb", "giant": r"\bgiants?\b|mammoth", "spriggan": r"spriggan"}
# families whose built inhabitants are a fixed kind (forts, camps and the like staff themselves by flavour, so they are not compared)
FAMKW = {"barrow": "draugr", "bandit": "bandit", "vampire": "vampire", "necromancer": "necromancer",
         "hagraven": "forsworn", "forsworn": "forsworn", "falmer": "falmer", "grove": "spriggan", "giant": "giant"}
THEMED_LOOT = re.compile(r"story_|relic_|containers/dungeon/theme|uniques/|containers/camp|containers/site|marquee_|dwemer_scrap|lair_reagents|"
                         r"word_wall|dragon_lair|giant_camp|bandit_camp|sea_wreck|daedric_offerings|corpse/")


def jload(p, default=None):
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def places(province=None):
    prov_files = [
        ("skyrim", "skyrim_landmarks.json"),
        ("high_rock", "high_rock.json"),
        ("hammerfell", "hammerfell.json"),
        ("cyrodiil", "cyrodiil.json"),
        ("morrowind", "morrowind.json"),
        ("black_marsh", "black_marsh.json"),
        ("elsweyr", "elsweyr.json"),
        ("valenwood", "valenwood.json"),
        ("summerset", "summerset.json"),
        ("foreign", "foreign.json"),
    ]
    out = []
    for pkey, fn in prov_files:
        if province and province != "all" and pkey != province:
            continue
        pl = jload(os.path.join(SK, "tamriel", "places", fn), [])
        out.extend(p for p in pl if p["type"] in ("dungeon_fixed", "fort"))
    return out


def lore():
    out = {}
    for f in jload(os.path.join(SK, "lore", "places", "index.json"), []):
        out.update((jload(os.path.join(SK, "lore", "places", f), {}) or {}).get("places", {}))
    return out


def names():
    out = {}
    for f in glob.glob(os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft", "*.json")):
        for k, v in (jload(f, {}) or {}).items():
            if k.startswith("place.skycraft."):
                out[k[len("place.skycraft."):]] = v
    return out


def isolated(area="skyrim"):
    tmp = os.path.join(OUT, "_reach.json")
    os.makedirs(OUT, exist_ok=True)
    try:
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "reach_lint.py"), "--area", area, "--json", tmp],
                       capture_output=True, timeout=300)
        return set(jload(tmp, {}).get("isolated", []))
    except (OSError, subprocess.SubprocessError):
        return set()


def load_export(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        d = json.load(f)
    d.pop("rle", None)
    d.pop("palette", None)
    d.pop("coll", None)
    return d


def check(pid, variant, fam, lore_p, ex, ident, iso, nm):
    """Flags (a list) for one dungeon; ex is None in --static mode."""
    f = []
    # data checks
    if pid in iso:
        f.append("no_trail")
    hook = ((lore_p or {}).get("hook", "") + " " + (lore_p or {}).get("rumour", "")).lower()
    exp = FAMKW.get(fam)
    if exp:
        hits = {k: len(re.findall(v, hook)) for k, v in KW.items()}
        top = max(hits, key=hits.get)
        if hits[top] > 0 and top != exp and hits[exp] == 0 and not re.search(KW[top], pid):
            if not (fam == "grove" and top == "giant"):
                f.append("lore_mismatch")
    if ex is None:
        return f
    lecterns = [l for l in ex.get("lecterns", []) if l.get("book")]
    if not [s for s in ex.get("signs", []) if len([x for x in s.get("lines", []) if x.strip()]) >= 2]:
        f.append("no_sign")
    if not lecterns:
        f.append("no_lectern")
    if not [l for l in lecterns if str(l.get("note", "")).startswith("place." + pid + ".")]:
        f.append("no_lore_note")
    ents = ex.get("entities", [])
    bosses = [e for e in ents if e.get("boss")]
    if fam not in NO_BOSS_OK:
        if not bosses:
            f.append("no_boss")
        elif not all(b.get("name") for b in bosses):
            f.append("unnamed_boss")
    # roster: the share of enemies (entities + spawners) whose id says what the place is about
    toks = EXPECT.get(fam, [""])
    mobs = [e["type"].split(":")[-1] for e in ents if "dormant" in e] + [s.get("entity", "?").split(":")[-1] for s in ex.get("spawners", [])]
    if mobs and toks != [""]:
        ok = sum(1 for m in mobs if any(t in m for t in toks))
        if ok / len(mobs) < 0.6:
            f.append("roster")
    feats = set()
    for p in ex.get("pieces", []):
        feats.update(p.get("features", []))
    if not any(x.startswith("blueprint:motif_") for x in feats):
        f.append("no_motif")
    loots = [c.get("loot", "") for c in ex.get("containers", []) if c.get("loot")]
    story = [t for t in loots if "story_" in t]
    themed = [t for t in loots if THEMED_LOOT.search(t)]
    if not story and (not loots or len(themed) / len(loots) < 0.25):
        f.append("generic_loot")
    return f


def load_identities():
    ident_dir = os.path.join(SK, "dungeons", "identity")
    idx = jload(os.path.join(ident_dir, "index.json"), ["skyrim.json"])
    out = {}
    for fn in idx:
        out.update((jload(os.path.join(ident_dir, fn), {}) or {}).get("places", {}))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--exports", default=os.path.join(ROOT, "mod", "run", "qa-exports"))
    ap.add_argument("--province", default="all", help="Province filter: skyrim, high_rock, hammerfell, or all")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--json", default=os.path.join(OUT, "dungeon_identity.json"))
    ap.add_argument("--compare", help="an earlier --json output: print its counts beside this run's")
    ap.add_argument("--static", action="store_true", help="data checks only, no exports")
    ap.add_argument("--assert-max", type=int, default=None, help="exit 1 if any single flag count exceeds N")
    ap.add_argument("--ids", nargs="*", help="only these place ids")
    a = ap.parse_args()
    pl = places(a.province)
    if a.ids:
        pl = [p for p in pl if p["id"] in set(a.ids)]
    lr = lore()
    nm = names()
    iso = set() if a.static else isolated()
    ident = load_identities()
    rows = []
    missing = 0
    for p in pl:
        ex = None
        if not a.static:
            path = os.path.join(a.exports, p["id"] + ".json.gz")
            if not os.path.exists(path):
                missing += 1
                continue
            ex = load_export(path)
        fam = ident.get(p["id"], {}).get("family") or (
            "forsworn" if p.get("variant") == "barrow" and p.get("culture") == "forsworn" else FAMILY.get(p.get("variant"), "cave"))
        fl = check(p["id"], p.get("variant", ""), fam, lr.get(p["id"]), ex, ident, iso, nm)
        rows.append({"id": p["id"], "name": nm.get(p["id"], p["id"]), "variant": p.get("variant"), "family": fam, "flags": fl,
                     "mobs": len([e for e in (ex or {}).get("entities", []) if "dormant" in e]),
                     "containers": len((ex or {}).get("containers", []))})
    cnt = collections.Counter()
    for r in rows:
        cnt.update(r["flags"])
    total = sum(len(r["flags"]) for r in rows)
    clean = sum(1 for r in rows if not r["flags"])
    os.makedirs(os.path.dirname(a.json), exist_ok=True)
    with open(a.json, "w", encoding="utf-8") as fh:
        json.dump({"places": len(rows), "flags": dict(cnt), "total": total, "clean": clean, "rows": rows}, fh, indent=1)
    old = (jload(a.compare, {}) or {}) if a.compare else {}
    oc = old.get("flags", {})
    mode = "static" if a.static else "exports"
    print("dungeon identity lint (%s): %d dungeons%s, %d flags, %d clean" % (
        mode, len(rows), (", %d without export" % missing) if missing else "", total, clean))
    print("%-14s %6s%s" % ("flag", "count", "   before" if oc else ""))
    for k in FLAGS:
        if a.static and k not in ("no_trail", "lore_mismatch"):
            continue
        print("%-14s %6d%s" % (k, cnt.get(k, 0), "   %6d" % oc.get(k, 0) if oc else ""))
    if oc:
        print("%-14s %6d   %6d" % ("TOTAL", total, old.get("total", 0)))
    fams = collections.defaultdict(lambda: [0, 0])
    for r in rows:
        fams[r["family"]][0] += 1
        fams[r["family"]][1] += len(r["flags"])
    print("by family (places, flags/place): " + ", ".join(
        "%s %d (%.1f)" % (k, v[0], v[1] / v[0]) for k, v in sorted(fams.items(), key=lambda kv: -kv[1][1] / kv[1][0])[:12]))
    worst = sorted(rows, key=lambda r: (-len(r["flags"]), r["id"]))[:a.top]
    print("worst offenders:")
    for r in worst:
        if r["flags"]:
            print("  %-26s %-16s %d  %s" % (r["id"], r["variant"], len(r["flags"]), " ".join(r["flags"])))
    if a.assert_max is not None and any(v > a.assert_max for v in cnt.values()):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
