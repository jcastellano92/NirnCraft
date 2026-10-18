#!/usr/bin/env python3
"""Progression simulator for Skycraft's loot economy (docs/LOOT.md).

Rolls the generated loot tables (mod/src/main/resources/data/skycraft/loot_tables) with Python models of the Java
loot functions (scaled_gold, leveled/regional gear on the LootTiers ladders, the level-scaled enchant chance, tempering,
potions, tomes...) and values every item with the skycraft_values data, like ItemValues does. For each level band it
simulates a character clearing N dungeons (1 boss chest, 3 chests, 6 urns/barrels, 2 picked locks, 7 enemies and a
boss creature) and looting N houses of each wealth tier, then compares with the Skyrim reference curve:

    coins per dungeon ~ 110 + 8.5 L,  total value per dungeon ~ 500 + 140 L (+70 L past 46),  enchanted gear ~ 5% + 0.5% per level,
    best material = the leveled-list unlock (Iron 1, Steel 6, Orcish 12, Dwarven 18, Elven 22, Glass 27, Ebony 32,
    Daedric 46, Dragon 50); never above the level.

    py -3 tools/loot_sim.py [--dungeons 400] [--seed 1] [--table skycraft:containers/dungeon/boss]

Checks (exit 1 on failure): value and coins within 0.6-1.6x of the reference at every level, the curves rising from
band to band (no drought), no single container (bar quest rewards) whose 99th percentile beats 1.5x a whole dungeon's
reference value at that level (no jackpot), and no gear above the level. The in-game counterpart is /skyloot curve <table> (and skycraft-loot-sample.txt).
"""
import argparse
import json
import random
import statistics
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "mod" / "src" / "main" / "resources" / "data"
AFFIX_DIR = ROOT / "mod" / "src" / "main" / "resources" / "skycraft" / "loot" / "affixes"
LOOT = DATA / "skycraft" / "loot_table"
SK = "skycraft:"
LEVELS = [1, 3, 6, 9, 12, 15, 18, 22, 27, 32, 36, 40, 46, 50, 55, 60]

# ---------------------------------------------------------------------------------------------- data

VALUES = {}
for f in (DATA / "skycraft" / "skycraft_values").glob("*.json"):
    for k, v in json.loads(f.read_text(encoding="utf-8")).get("values", {}).items():
        if not k.startswith("#"):
            VALUES[k] = v

TABLES = {}
for f in LOOT.rglob("*.json"):
    TABLES[SK + f.relative_to(LOOT).as_posix()[:-5]] = json.loads(f.read_text(encoding="utf-8"))

QUALITIES_DATA = json.loads((AFFIX_DIR / "qualities.json").read_text(encoding="utf-8"))["qualities"]
MAKERS_DATA = json.loads((AFFIX_DIR / "makers.json").read_text(encoding="utf-8"))
STORIED_DATA = json.loads((AFFIX_DIR / "storied.json").read_text(encoding="utf-8"))


def roll_affix_mult(source_kind, rnd):
    """Mirrors com.skycraft.loot.affix.ItemAffixes value multiplier for a gear drop."""
    pairs = [(q, max(0, q.get("weights", {}).get(source_kind, 0))) for q in QUALITIES_DATA]
    q = weighted(rnd, [p for p in pairs if p[1] > 0]) if any(w > 0 for _, w in pairs) else QUALITIES_DATA[3]
    mult = float(q.get("value_mult", 1.0))
    if q.get("tier", 0) >= 0 and rnd.random() < MAKERS_DATA.get("source_chances", {}).get(source_kind, 0.15):
        m = rnd.choice(MAKERS_DATA["makers"])
        mult *= float(m.get("value_mult", 1.0))
    if source_kind == "boss" and rnd.random() < float(STORIED_DATA.get("boss_chance", 0.05)):
        st = rnd.choice(STORIED_DATA["entries"])
        mult *= float(st.get("value_mult", 1.0))
    return mult, q["id"]


def value_of(item):
    return VALUES.get(item, 2)


# LootTiers ladders (mirror of com.skycraft.loot.LootTiers): (material, unlock, weight)
WEAPONS = [("iron", 1, 1), ("steel", 6, 1), ("silver", 6, .25), ("orcish", 12, 1), ("dwarven", 18, 1), ("elven", 22, 1),
           ("glass", 27, 1), ("nordic", 27, .4), ("ebony", 32, 1), ("stalhrim", 40, .35), ("daedric", 46, 1), ("dragonbone", 50, .6)]
HEAVY = [("iron", 1, 1), ("banded_iron", 3, .6), ("steel", 6, 1), ("orcish", 12, 1), ("dwarven", 18, 1), ("steel_plate", 22, 1),
         ("nordic", 27, .6), ("ebony", 32, 1), ("stalhrim_heavy", 40, .35), ("daedric", 46, 1), ("dragonplate", 50, .6)]
LIGHT = [("hide", 1, 1), ("fur", 1, .7), ("leather", 6, 1), ("studded", 6, .6), ("elven", 12, 1), ("scaled", 18, 1),
         ("elven_gilded", 22, .7), ("glass", 27, 1), ("stalhrim_light", 40, .35), ("dragonscale", 50, .6)]
MELEE = [("dagger", 15), ("sword", 25), ("war_axe", 16), ("mace", 12), ("greatsword", 12), ("battleaxe", 10), ("warhammer", 10)]
PIECES = ["helmet", "chestplate", "leggings", "boots"]


def ladder_pick(ladder, level, rnd):
    unlocks = sorted({u for _, u, _ in ladder if u <= level}, reverse=True)
    f = rnd.random()
    want = min(0 if f < .6 else 1 if f < .9 else 2, len(unlocks) - 1)
    hi = unlocks[want]
    lo = unlocks[want] if want < 2 else -1
    pool = [(m, u, w) for m, u, w in ladder if lo <= u <= hi]
    x = rnd.random() * sum(w for _, _, w in pool)
    for m, u, w in pool:
        x -= w
        if x < 0:
            return m, u
    return pool[-1][0], pool[-1][1]


def weighted(rnd, pairs):
    x = rnd.random() * sum(w for _, w in pairs)
    for v, w in pairs:
        x -= w
        if x < 0:
            return v
    return pairs[-1][0]


def roll_gear(kind, level, rnd):
    """(item id, unlock level) of a leveled drop."""
    if kind == "any":
        kind = weighted(rnd, [("weapon", 4), ("armor", 4), ("shield", 1)])
    for _ in range(6):
        if kind in ("weapon", "bow"):
            mat, u = ladder_pick(WEAPONS, level, rnd)
            iid = f"{SK}{mat}_{'bow' if kind == 'bow' else weighted(rnd, MELEE)}"
        elif kind == "armor":
            mat, u = ladder_pick(HEAVY if rnd.random() < .5 else LIGHT, level, rnd)
            iid = f"{SK}{mat}_{rnd.choice(PIECES)}"
        else:  # shield
            mat, u = ladder_pick(HEAVY if rnd.random() < .67 else LIGHT, level, rnd)
            iid = f"{SK}{mat}_shield"
        if iid in VALUES:
            return iid, u
    return f"{SK}iron_sword", 1


def enchant_chance(base, level):
    return min(.85, base * min(2.2, max(.35, .35 + level / 32))) if base > 0 else 0


# ---------------------------------------------------------------------------------------------- rolling

class Drop:
    __slots__ = ("item", "count", "value", "gold", "gear", "tier", "enchant", "special")

    def __init__(self, item, count=1, value=None):
        self.item, self.count = item, count
        self.value = value_of(item) if value is None else value
        self.gold = 0
        self.gear = False
        self.tier = 0
        self.enchant = 0
        self.special = None


def rolls_of(r, rnd):
    if isinstance(r, dict):
        return rnd.randint(int(r["min"]), int(r["max"]))
    return int(r)


def test(conds, ctx, rnd):
    for c in conds or []:
        t = c["condition"]
        if t == "minecraft:random_chance" and rnd.random() >= c["chance"]:
            return False
        if t == "minecraft:random_chance_with_looting" and rnd.random() >= c["chance"]:
            return False
        if t == SK + "loot_level" and not (c.get("min", 1) <= ctx["level"] <= c.get("max", 999)):
            return False
        if t == SK + "collection_complete":
            return False
    return True


def roll_table(tid, ctx, rnd, out):
    t = TABLES.get(tid)
    if t is None:
        return
    for p in t.get("pools", []):
        if not test(p.get("conditions"), ctx, rnd):
            continue
        for _ in range(rolls_of(p.get("rolls", 1), rnd)):
            entries = [e for e in p["entries"] if test(e.get("conditions"), ctx, rnd)]
            if not entries:
                continue
            e = weighted(rnd, [(e, e.get("weight", 1)) for e in entries])
            if e["type"] == "minecraft:empty":
                continue
            if e["type"] == "minecraft:loot_table":
                roll_table(e["name"], ctx, rnd, out)
                continue
            if e["type"] != "minecraft:item":
                out.append(Drop("tag", 1, 5))
                continue
            d = apply(Drop(e["name"]), e.get("functions", []), ctx, rnd)
            if d is not None:
                out.append(d)


TOME_VALUE = [(1, 50), (6, 120), (14, 300), (26, 600), (40, 1200)]


def apply(d, fns, ctx, rnd):
    L = ctx["level"]
    for f in fns:
        fn = f["function"]
        if fn == "minecraft:set_count":
            c = f["count"]
            d.count = rnd.randint(int(c["min"]), int(c["max"])) if isinstance(c, dict) else int(c)
        elif fn == "minecraft:set_nbt":
            tag = f["tag"]
            if "skycraft_value:" in tag:
                d.value = int(tag.split("skycraft_value:")[1].split("}")[0].split(",")[0])
            if "gold:" in tag:
                d.gold = int(tag.split("gold:")[1].split("L")[0])
                d.value = 0
        elif fn == SK + "scaled_gold":
            amount = (f["min"] + rnd.random() * (f["max"] - f["min"])) * (1 + f.get("growth", .04) * (L - 1)) * ctx.get("danger", 1)
            d.item, d.count, d.value, d.gold = SK + "septim", 1, 0, max(1, round(amount))
        elif fn in (SK + "leveled_gear", SK + "regional_gear"):
            kind = f.get("kind", "weapon")
            if kind == "arrow":
                d.item, d.value = "arrows", 1 + L // 8
                continue
            d.item, d.tier = roll_gear(kind, L, rnd)
            q_chance = f.get("quality_chance", 0)
            tid = ctx.get("table", "")
            src_kind = "corpse" if "/corpse/" in tid else ("boss" if q_chance >= 0.32 or "/boss" in tid else ("merchant" if "/shop/" in tid else "container"))
            affix_mult, _ = roll_affix_mult(src_kind, rnd)
            d.value, d.gear = max(1, round(value_of(d.item) * affix_mult)), True
            if rnd.random() < enchant_chance(f.get("enchant_chance", 0), L):
                n = 2 if L >= 36 and rnd.random() < .15 else 1
                for _ in range(n):
                    lv = max(1, min(4, 1 + L // 15 + (rnd.random() < .25) - (rnd.random() < .25)))
                    d.enchant = max(d.enchant, lv)
                    d.value += 50 * lv * 1.4
            if rnd.random() < q_chance:
                q = 1 + rnd.randrange(max(1, min(5, 1 + L // 12)))
                d.value *= 1 + .15 * q
        elif fn == SK + "skyrim_potion":
            d.item, d.value = "potion", 20 + 8 * min(3, max(0, L - 1) / 15)
        elif fn == SK + "leveled_tome":
            vals = [v for lv, v in TOME_VALUE if lv <= L]
            d.item, d.value = "tome", rnd.choice(vals) if rnd.random() < .5 else vals[0]
        elif fn == SK + "regional_tag":
            d.item, d.value = ("trinket", rnd.choice([30, 75, 60, 120, 180, 230])) if "trinkets" in f["tag"] else ("ingredient", 5)
        elif fn == SK + "regional_book":
            d.item, d.value = "lore_book", 25
        elif fn == SK + "skill_book":
            d.item, d.value = "skill_book", 50
        elif fn == SK + "regional_treasure":
            d.item, d.value, d.special = "treasure", 430, "treasure"
        elif fn in (SK + "place_collectibles", SK + "collectible"):
            return None  # fixed, one per character: not part of the curve
        elif fn in (SK + "random_unique", SK + "unique", SK + "missing_collectible"):
            if fn == SK + "random_unique" and rnd.random() > ctx.get("unique_left", .3):
                return None  # most provinces' uniques are long found by then
            d.item, d.value, d.special = "unique", 1500 + 60 * L, "unique"
    return d


def roll(tid, level, rnd, danger=1.0):
    out = []
    roll_table(tid, {"level": level, "danger": danger, "table": tid}, rnd, out)
    return out


def summarize(drops):
    gold = sum(d.gold for d in drops)
    value = gold + sum(d.value * d.count for d in drops if not d.gold)
    gear = [d for d in drops if d.gear]
    return gold, value, gear


# ---------------------------------------------------------------------------------------------- scenarios

LOCK_BY_LEVEL = [(1, "novice"), (8, "apprentice"), (18, "adept"), (30, "expert"), (42, "master")]


def lock_tier(level, rnd):
    tiers = [t for lv, t in LOCK_BY_LEVEL if lv <= level]
    return rnd.choice(tiers[-2:])


def dungeon(level, rnd):
    drops = []
    drops += roll(SK + "containers/dungeon/boss", level, rnd)
    for _ in range(3):
        drops += roll(SK + "containers/dungeon/standard", level, rnd)
    for _ in range(6):
        drops += roll(SK + "containers/dungeon/minor", level, rnd)
    for _ in range(2):
        drops += roll(SK + "containers/lock_bonus/" + lock_tier(level, rnd), level, rnd)
    for _ in range(4):
        drops += roll(SK + "containers/corpse/bandit", level, rnd)
    for _ in range(3):
        drops += roll(SK + "containers/corpse/draugr", level, rnd)
    drops += roll(SK + rnd.choice(["containers/corpse/bandit_boss", "containers/corpse/draugr_boss"]), level, rnd)
    return drops


def ref_gold(level):
    return 110 + 8.5 * level


def ref_value(level):
    # Skyrim's lists stop adding materials after Daedric/Dragon: past 46 only enchantments and gold still grow
    return 500 + 140 * min(level, 46) + 70 * max(0, level - 46)


def ref_enchant(level):
    return .05 + .005 * level


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dungeons", type=int, default=400)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--table")
    args = ap.parse_args()
    rnd = random.Random(args.seed)
    problems = []

    if args.table:
        print(f"{args.table}:")
        for L in LEVELS:
            vals = [summarize(roll(args.table, L, rnd))[1] for _ in range(args.dungeons)]
            print(f"  L{L:<3} mean {statistics.mean(vals):8.1f}  p95 {sorted(vals)[int(.95 * len(vals))]:8.1f}  max {max(vals):8.1f}")
        return 0

    print("Dungeon clear (1 boss chest, 3 chests, 6 urns, 2 picked locks, 7 enemies + their leader), per level:")
    print("  L    coins (ref)        value (ref)   ratio  gear  tier  best  ench% (ref)  ench lvl  p95/mean")
    prev_value = prev_gold = 0
    for L in LEVELS:
        golds, values, gear_n, tiers, best, ench, elv = [], [], [], [], 0, [], []
        for _ in range(args.dungeons):
            g, v, gear = summarize(dungeon(L, rnd))
            golds.append(g)
            values.append(v)
            gear_n.append(len(gear))
            for d in gear:
                tiers.append(d.tier)
                best = max(best, d.tier)
                ench.append(1 if d.enchant else 0)
                if d.enchant:
                    elv.append(d.enchant)
                if d.tier > L:
                    problems.append(f"L{L}: {d.item} (unlock {d.tier}) above the level")
        mg, mv = statistics.mean(golds), statistics.mean(values)
        p95 = sorted(values)[int(.95 * len(values))]
        e = statistics.mean(ench) if ench else 0
        print(f"  {L:<3} {mg:7.0f} ({ref_gold(L):4.0f})  {mv:9.0f} ({ref_value(L):5d})  {mv / ref_value(L):5.2f}  {statistics.mean(gear_n):4.1f}"
              f"  {statistics.mean(tiers) if tiers else 0:4.1f}  {best:4d}  {100 * e:4.1f} ({100 * ref_enchant(L):4.1f})"
              f"  {statistics.mean(elv) if elv else 0:8.2f}  {p95 / mv:8.2f}")
        if not .6 <= mv / ref_value(L) <= 1.6:
            problems.append(f"L{L}: dungeon value {mv:.0f} is {mv / ref_value(L):.2f}x the reference")
        if not .6 <= mg / ref_gold(L) <= 1.6:
            problems.append(f"L{L}: dungeon coins {mg:.0f} is {mg / ref_gold(L):.2f}x the reference")
        # past the last material (Dragon, 50) the item value plateaus: allow sampling noise there
        slack = .97 if L <= 46 else .93
        if mv < prev_value * slack or mg < prev_gold * .97:
            problems.append(f"L{L}: the curve drops (value {prev_value:.0f} -> {mv:.0f}, coins {prev_gold:.0f} -> {mg:.0f})")
        prev_value, prev_gold = mv, mg

    print("\nSingle containers (mean value per level; jackpot = worst 99th percentile / a whole dungeon's reference, max 1.5):")
    tables = ["dungeon/minor", "dungeon/standard", "dungeon/boss", "household/poor", "household/common", "household/wealthy",
              "household/noble", "household_stores/common", "shop/general", "shop/jarl", "misc/satchel", "misc/urn",
              "strongbox/adept", "strongbox/master", "lock_bonus/novice", "lock_bonus/master", "corpse/bandit", "corpse/bandit_boss",
              "corpse/draugr_boss", "corpse/dragon_priest", "corpse/dragon", "camp/bandit", "site/shipwreck", "reward/standard"]
    print("  " + "table".ljust(26) + "".join(f"L{L:<7}" for L in (1, 12, 27, 46, 60)) + "jackpot")
    for t in tables:
        row, worst = [], 0
        for L in (1, 12, 27, 46, 60):
            vals = sorted(summarize(roll(SK + "containers/" + t, L, rnd))[1] for _ in range(max(600, args.dungeons)))
            row.append(statistics.mean(vals))
            worst = max(worst, vals[int(.99 * len(vals))] / ref_value(L))
        print("  " + t.ljust(26) + "".join(f"{v:<8.0f}" for v in row) + f"{worst:.2f}")
        if worst > 1.5 and not t.startswith(("reward/", "corpse/dragon")):
            problems.append(f"{t}: its 99th percentile is {worst:.2f}x a whole dungeon (jackpot)")
        for a, b in zip(row, row[1:]):
            if b < a * .9:
                problems.append(f"{t}: value drops with the level ({a:.0f} -> {b:.0f})")

    print("\n" + ("OK: curves smooth and on the reference" if not problems else "PROBLEMS:"))
    for p in problems[:40]:
        print("  " + p)
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
