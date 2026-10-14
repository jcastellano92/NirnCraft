"""Fast static data checks that catch the integration bugs merges keep producing.

usage: py -3 -I tools/qa/data_checks.py      (from the repo root)
Prints 'ERR ...' lines and a final summary line.
  - every JSON file under mod/src/main/resources and mod/src/main/lang parses
  - places: no duplicate ids across files listed in places/index.json; every file in the index exists
  - quests: index.json lists existing files
  - Java: no item/block/entity id registered twice in the same kind of registry (ITEMS / BLOCKS / ENTITIES)
  - guards: skycraft/creatures/guard_profiles.json (every profile has its skin, name and item models, every row names
    a profile, no Skyrim hold livery outside Skyrim; see docs/world/GUARDS.md)
  - menus: every menu type of the game and the mod has a Skyrim screen or an explicit `vanilla-allowed` row in
    docs/UI-COVERAGE.md (tools/qa/menu_coverage.py)
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "mod", "src", "main", "resources")
JAVA = os.path.join(ROOT, "mod", "src", "main", "java")
errs = []


def err(msg):
    errs.append(msg)
    print("ERR", msg)


# --- inventory discipline (docs/INVENTORY.md "The facade") ---------------------------------------------------------
# Gameplay code reads and spends what a player carries through com.skycraft.inventory.Inv (slots + offhand + the
# overflow pack). A rule that reads only the 36 slots misses pack items. This lint fails when a Java file outside the
# whitelist reads the player's inventory slots directly. Every whitelist entry says why it is allowed; a new entry
# needs the same: slot-addressed screens (a slot id names the item, virtual pack ids go through Pack.resolveSlot),
# code that transfers or mutates whole inventories (death, jail, theft), and containers that are not the player's.
INV_WHITELIST = {
    "inventory/": "the inventory module itself (Pack, Inv, screens, actions, selftests)",
    "economy/": "barter, shops and house keys are slot-addressed (virtual slot ids) and keep their own pack accounting",
    "vitals/ActionHandler.java": "pick-up (addToBags) and container take/store",
    "combat/DeathHandler.java": "death keep rules work per slot and per pack entry",
    "crime/Theft.java": "tags stolen items in place (slots and pack)",
    "crime/Jail.java": "confiscation moves whole inventories into the evidence chest",
    "crime/Bounty.java": "confiscates stolen items from slots and pack",
    "crafting/recipe/Cost.java": "material costs with an exceptSlot rule; already counts slots + offhand + pack",
    "crafting/arcane/alchemy/Alchemy.java": "slot-addressed: the screen names slots (Pack.resolveAll for pack rows)",
    "crafting/arcane/enchant/Enchanting.java": "slot-addressed: the screen names slots (Pack.resolveAll for pack rows)",
    "crafting/Tempering.java": "slot-addressed: the screen names a slot (Pack.resolveSlot for a pack row)",
    "crafting/SoulGems.java": "slot-addressed best-gem choice (Pack.resolveSlot for a pack row)",
    "arsenal/StaffEnchanting.java": "slot-addressed: the screen names slots (Pack.resolveAll for pack rows)",
    "society/NpcDialogue.java": "slot-addressed stolen-blade handover (Pack.resolveSlot)",
    "crafting/menu/StationMenu.java": "lays the player's slots out in a station menu (slot widgets, not a gameplay read)",
    "homestead/HomesteadMenu.java": "lays the player's slots out in a menu (slot widgets, not a gameplay read)",
    "survival/cooking/CookingMenu.java": "lays the player's slots out in a menu (slot widgets, not a gameplay read)",
    "society/Followers.java": "a follower's own inventory, not the player's",
    "survival/corpse/": "the player corpse's own containers",
    "creatures/entity/CorpseEntity.java": "the creature corpse's own container",
    "magic/": "conjured weapons live only in slots; hands and hotbar management (owned by the magic stream)",
    "mounts/": "saddlebags are the mount's own container (owned by the mounts stream)",
}
# direct reads of the player's slots (after stripping // comments)
INV_PATTERNS = [
    re.compile(r"getInventory\(\)\s*\.\s*(items|armor|offhand|compartments)\b"),
    re.compile(r"\.inventory\s*\.\s*(items|armor|offhand)\b"),
    re.compile(r"\.countItem\("),
    re.compile(r"getInventory\(\)\s*\.\s*(contains|getItem|getContainerSize|removeItem|add|clearOrCountMatchingItems|"
               r"findSlotMatchingItem|hasAnyMatching|hasAnyOf|getFreeSlot)\("),
]
# a local named inv/inventory (type Inventory or var) that is then read slot by slot
INV_LOCAL = re.compile(r"\b(inv|inventory)\s*\.\s*(items|armor|offhand|getContainerSize\(\)|getItem\(|removeItem\(|contains\(|"
                       r"clearOrCountMatchingItems\(|findSlotMatchingItem\(|add\(|getFreeSlot\()")
INV_DECL = re.compile(r"getInventory\(\)|\bInventory\s+(inv|inventory)\b")


def inventory_lint():
    bad = 0
    for f in glob.glob(os.path.join(JAVA, "com", "skycraft", "**", "*.java"), recursive=True):
        rel = os.path.relpath(f, os.path.join(JAVA, "com", "skycraft")).replace(os.sep, "/")
        if "/client/" in "/" + rel or rel.startswith("client/"):
            continue  # screens read the client's own mirror
        if any(rel == k or (k.endswith("/") and rel.startswith(k)) for k in INV_WHITELIST):
            continue
        if re.search(r"(SelfTest|Selftest|Autotest|AutoTest)\.java$", rel) or "/qa/" in "/" + rel:
            continue  # tests build and inspect inventories on purpose
        src = open(f, encoding="utf-8", errors="replace").read()
        has_decl = bool(INV_DECL.search(src))
        for i, line in enumerate(src.splitlines(), 1):
            code = line.split("//")[0]
            if not code.strip() or code.lstrip().startswith(("*", "/*")):
                continue
            hit = any(p.search(code) for p in INV_PATTERNS) or (has_decl and INV_LOCAL.search(code))
            if hit and "inventory-lint: ok" not in line:
                bad += 1
                err(f"inventory: {rel}:{i} reads the player's slots directly; use com.skycraft.inventory.Inv "
                    f"(count/has/take/give/find/forEach) so pack items count (docs/INVENTORY.md)")
    return bad
def check_guards(place_ids):
    """The guard table (skycraft/creatures/guard_profiles.json, docs/world/GUARDS.md) against what it points at.

    Every profile has its skin and its name; every item it carries has a model; every settlement/region/culture/
    province row names a profile; every place of a major or minor settlement resolves to a guard whose skin exists, and
    nobody outside Skyrim (and not Nord) wears a Skyrim hold's colours. Mirrors com.skycraft.society.GuardWatch (the
    rule: capital/city and towns of radius 90+ are major, other towns minor, nothing else keeps a watch) and
    com.skycraft.creatures.GuardProfiles (place > region > culture > province > default).
    """
    path = os.path.join(RES, "skycraft", "creatures", "guard_profiles.json")
    table = json.load(open(path, encoding="utf-8"))
    profiles = table["profiles"]
    tex = os.path.join(RES, "assets", "skycraft", "textures", "entity", "guard")
    models = os.path.join(RES, "assets", "skycraft", "models", "item")
    lang = json.load(open(os.path.join(ROOT, "mod", "src", "main", "lang", "skycraft", "creatures.json"), encoding="utf-8"))
    if table.get("default") not in profiles:
        err(f"guards: default profile {table.get('default')} is not a profile")
    for pid, prof in profiles.items():
        if not os.path.exists(os.path.join(tex, f"guard_{pid}.png")):
            err(f"guards: profile {pid} has no skin guard_{pid}.png")
        if f"entity.skycraft.guard.{pid}" not in lang:
            err(f"guards: profile {pid} has no name (entity.skycraft.guard.{pid} in creatures.json)")
        for item in (prof.get("weapon"), prof.get("shield")):
            if item and item.startswith("skycraft:") and not os.path.exists(os.path.join(models, item.split(":")[1] + ".json")):
                err(f"guards: profile {pid} carries {item}, which has no item model")
        if "art" not in prof or "tunic" not in prof["art"]:
            err(f"guards: profile {pid} has no art.tunic (the skin generator needs it)")
    regions = {r["id"] for r in json.load(open(os.path.join(RES, "skycraft", "tamriel", "regions.json"), encoding="utf-8"))}
    provinces = {r.split("/")[0] for r in regions}
    for key, rows, valid in (("settlements", table["settlements"], place_ids), ("regions", table["regions"], regions),
                             ("provinces", table["provinces"], provinces)):
        for k, v in rows.items():
            if k not in valid:
                err(f"guards: {key} row {k} is not a known {key[:-1] if key != 'settlements' else 'place'}")
            if v not in profiles:
                err(f"guards: {key} row {k} names unknown profile {v}")
    for k, v in table["cultures"].items():
        if v not in profiles:
            err(f"guards: cultures row {k} names unknown profile {v}")

    holds = {"whiterun", "the_rift", "eastmarch", "the_pale", "winterhold", "haafingar", "hjaalmarch", "the_reach", "falkreath"}

    def resolve(p):
        if p["id"] in table["settlements"]:
            return table["settlements"][p["id"]]
        if p.get("region") in table["regions"]:
            return table["regions"][p["region"]]
        if p.get("culture") in table["cultures"]:
            return table["cultures"][p["culture"]]
        prov = p.get("region", "").split("/")[0]
        return table["provinces"].get(prov, table["default"])

    pdir = os.path.join(RES, "skycraft", "tamriel", "places")
    major = minor = 0
    for f in json.load(open(os.path.join(pdir, "index.json"), encoding="utf-8")):
        for p in json.load(open(os.path.join(pdir, f), encoding="utf-8")):
            t, rad, var = p.get("type"), p.get("radius", 32), p.get("variant", "")
            if var == "homestead" or p["id"] in ("helgen", "college_of_winterhold", "skaal_village"):
                continue
            if t in ("capital", "city") or (t == "town" and rad >= 90):
                major += 1
            elif t == "town":
                minor += 1
            else:
                continue
            pid = resolve(p)
            skyrim = p.get("region", "").startswith("skyrim/")
            if pid in holds and not skyrim and p.get("culture") not in ("nord", "atmoran"):
                err(f"guards: {p['id']} ({p.get('region')}) would have the {pid} hold's guard outside Skyrim")
            if skyrim and pid not in holds:
                err(f"guards: Skyrim settlement {p['id']} has {pid}, not its hold's livery")
    print(f"guards: {len(profiles)} profiles, {major} major and {minor} minor settlements resolved")


def main():
    n = 0
    for pattern in ("mod/src/main/resources/**/*.json", "mod/src/main/lang/**/*.json"):
        for f in glob.glob(os.path.join(ROOT, pattern), recursive=True):
            n += 1
            try:
                with open(f, encoding="utf-8") as fh:
                    json.load(fh)
            except Exception as e:  # noqa: BLE001
                err(f"bad json {os.path.relpath(f, ROOT)}: {str(e)[:100]}")

    pdir = os.path.join(RES, "skycraft", "tamriel", "places")
    idx = json.load(open(os.path.join(pdir, "index.json"), encoding="utf-8"))
    seen = {}
    places = 0
    for f in idx:
        p = os.path.join(pdir, f)
        if not os.path.exists(p):
            err(f"places index lists missing file {f}")
            continue
        for pl in json.load(open(p, encoding="utf-8")):
            places += 1
            if pl["id"] in seen:
                err(f"duplicate place id {pl['id']} in {f} and {seen[pl['id']]}")
            else:
                seen[pl["id"]] = f

    qdir = os.path.join(RES, "skycraft", "quests")
    qi = os.path.join(qdir, "index.json")
    if os.path.exists(qi):
        for f in json.load(open(qi, encoding="utf-8")):
            name = f if isinstance(f, str) else f.get("file", "")
            if name and not os.path.exists(os.path.join(qdir, name)):
                err(f"quests index lists missing file {name}")

    # duplicate registrations: classify each register("id") call by the DeferredRegister field it is called on
    kinds = defaultdict(lambda: defaultdict(list))
    reg = re.compile(r'\b([A-Z_]*(ITEMS|BLOCKS|ENTITIES|ENTITY_TYPES|BLOCK_ENTITIES|SOUNDS|EFFECTS|MENUS))\.register\(\s*"([a-z0-9_/]+)"')
    for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        src = open(f, encoding="utf-8", errors="replace").read()
        for m in reg.finditer(src):
            kinds[m.group(2)][m.group(3)].append(os.path.relpath(f, JAVA))
    for kind, ids in kinds.items():
        for i, where in ids.items():
            files = sorted(set(where))
            if len(where) > 1 and len(files) > 1:
                err(f"{kind} id '{i}' registered in {', '.join(files)}")

    # natural landmarks: kinds.json must match the generator's table and builders (tools/landmarks/gen_kinds.py)
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "landmarks"))
        import gen_kinds
        for e in gen_kinds.check():
            err("landmarks: " + e)
        on_disk = json.load(open(gen_kinds.OUT, encoding="utf-8"))["kinds"]
        if [k["id"] for k in on_disk] != [k["id"] for k in gen_kinds.KINDS]:
            err("landmarks: kinds.json is stale, run tools/landmarks/gen_kinds.py")
    except Exception as ex:  # a broken generator must show up as a check failure, not a crash
        err(f"landmarks: {ex}")


    try:
        check_guards(seen)
    except Exception as ex:  # a broken table must show up as a check failure, not a crash
        err(f"guards: {ex}")

    # Forge's EventBus generates handler classes named __<SimpleClass>_<method>_<Event> in the class's package:
    # two nested classes with the same simple name and handler method in one package collide at runtime.
    seen_h = defaultdict(set)
    hre = re.compile(r'@SubscribeEvent\s*(?:\([^)]*\))?\s*public\s+(?:static\s+)?void\s+(\w+)\s*\(\s*([\w.]+)')
    for f in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        src = open(f, encoding="utf-8", errors="replace").read()
        pm = re.search(r'^package ([\w.]+);', src, re.M)
        pkg = pm.group(1) if pm else ""
        for m in hre.finditer(src):
            cls = re.findall(r'class\s+(\w+)', src[:m.start()])
            if cls:
                seen_h[(pkg, cls[-1], m.group(1), m.group(2).split('.')[-1])].add(os.path.relpath(f, JAVA))
    for k, files in seen_h.items():
        if len(files) > 1:
            err(f"event handler class name collision {k[1]}.{k[2]}({k[3]}) in package {k[0]}: {', '.join(sorted(files))}")
    inventory_lint()

    # menus: every menu type (game and mod) has a Skyrim screen or an explicit `vanilla-allowed` row in
    # docs/UI-COVERAGE.md (tools/qa/menu_coverage.py)
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import menu_coverage
        for e in menu_coverage.check(verbose=True):
            err(e)
    except Exception as ex:  # a broken table must show up as a check failure, not a crash
        err(f"menus: {ex}")
    print(f"{n} json files, {places} places, {sum(len(v) for v in kinds.values())} registry ids checked, {len(errs)} errors")


if __name__ == "__main__":
    main()
