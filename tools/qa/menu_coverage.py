"""Menu coverage lint: every menu type the game or the mod registers has a Skyrim screen or an explicit exception.

usage: py -3 -I tools/qa/menu_coverage.py      (from the repo root; also run by tools/qa/data_checks.py)

The rule (docs/UI-COVERAGE.md): no menu a player can open may show the vanilla look by accident. The checks:

  * the menu types of the game (the 25 of Minecraft 1.20.1; when the Forge jar and javap are found, the list is read
    from the jar's `MenuType` class and compared) and of the mod (every `MENUS.register("id", ...)` in Java) each have a
    row in the "menu type map" table of docs/UI-COVERAGE.md with status `skyrim` or `vanilla-allowed`;
  * `vanilla-allowed` needs a reason (a sentence, not a word);
  * `skyrim` for a game menu type needs a `skin(MenuType.X, Kind.Y)` line in inventory/client/MenuSkins.java and names
    the screen class of that kind (CONTAINER -> SkyrimContainerScreen, MACHINE -> SkyrimMachineScreen,
    BOOK -> SkyrimLecternScreen); `skyrim` for a mod menu type needs its `MenuScreens.register(...)` and names that screen;
  * the named screen class exists in the sources; a skin line without a table row, or a row for a menu type that no
    longer exists, is stale and fails too.
"""
import glob
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA = os.path.join(ROOT, "mod", "src", "main", "java")
DOC = os.path.join(ROOT, "docs", "UI-COVERAGE.md")
SKINS = os.path.join(JAVA, "com", "skycraft", "inventory", "client", "MenuSkins.java")

# MenuType field -> registry id, Minecraft 1.20.1 (cross-checked against the jar below when it can be found)
VANILLA = {
    "GENERIC_9x1": "minecraft:generic_9x1", "GENERIC_9x2": "minecraft:generic_9x2", "GENERIC_9x3": "minecraft:generic_9x3",
    "GENERIC_9x4": "minecraft:generic_9x4", "GENERIC_9x5": "minecraft:generic_9x5", "GENERIC_9x6": "minecraft:generic_9x6",
    "GENERIC_3x3": "minecraft:generic_3x3", "ANVIL": "minecraft:anvil", "BEACON": "minecraft:beacon",
    "BLAST_FURNACE": "minecraft:blast_furnace", "BREWING_STAND": "minecraft:brewing_stand", "CRAFTING": "minecraft:crafting",
    "ENCHANTMENT": "minecraft:enchantment", "FURNACE": "minecraft:furnace", "GRINDSTONE": "minecraft:grindstone",
    "HOPPER": "minecraft:hopper", "LECTERN": "minecraft:lectern", "LOOM": "minecraft:loom", "MERCHANT": "minecraft:merchant",
    "SHULKER_BOX": "minecraft:shulker_box", "SMITHING": "minecraft:smithing", "SMOKER": "minecraft:smoker",
    "CARTOGRAPHY_TABLE": "minecraft:cartography_table", "STONECUTTER": "minecraft:stonecutter",
}
KIND_SCREEN = {"CONTAINER": "SkyrimContainerScreen", "MACHINE": "SkyrimMachineScreen", "BOOK": "SkyrimLecternScreen"}
MODID = "skycraft"


def _jar_fields():
    """MenuType fields from the Forge jar's class (None when the jar or javap cannot be found)."""
    home = os.path.expanduser("~")
    jars = glob.glob(os.path.join(home, ".gradle", "caches", "forge_gradle", "minecraft_user_repo", "net", "minecraftforge",
                                  "forge", "*", "forge-*_mapped_official_*.jar"))
    if not jars:
        return None
    javap = shutil.which("javap")
    for base in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-17"):
        if not javap and base and os.path.exists(os.path.join(base, "bin", "javap.exe")):
            javap = os.path.join(base, "bin", "javap.exe")
    if not javap:
        return None
    try:
        out = subprocess.run([javap, "-cp", jars[0], "net.minecraft.world.inventory.MenuType"], capture_output=True, text=True,
                             timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    fields = set(re.findall(r"public static final \S*MenuType<.*?>\s+(\w+);", out))
    return fields or None


def _read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def _java_files():
    return glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True)


def check(verbose=False):
    errs = []
    if not os.path.exists(DOC):
        return ["menus: docs/UI-COVERAGE.md is missing"]
    doc = _read(DOC)
    m = re.search(r"<!-- menu-map:begin -->(.*?)<!-- menu-map:end -->", doc, re.S)
    if not m:
        return ["menus: docs/UI-COVERAGE.md has no <!-- menu-map:begin --> ... <!-- menu-map:end --> table"]
    rows = {}
    for line in m.group(1).splitlines():
        r = re.match(r"^\|\s*`([a-z0-9_]+:[a-z0-9_/]+)`\s*\|\s*(skyrim|vanilla-allowed)\s*\|\s*(.+?)\s*\|\s*$", line)
        if r:
            rows[r.group(1)] = (r.group(2), r.group(3))
        elif line.strip().startswith("|") and not re.match(r"^\|\s*(menu type|-)", line.strip(), re.I) and "---" not in line:
            errs.append(f"menus: unreadable row in the menu type map: {line.strip()[:90]}")

    # --- what the game registers (table, cross-checked with the jar) and what the mod registers
    jar = _jar_fields()
    if jar is not None:
        for f in sorted(jar - set(VANILLA)):
            errs.append(f"menus: the game has MenuType.{f} which tools/qa/menu_coverage.py does not know: add it to VANILLA, "
                        f"to MenuSkins and to docs/UI-COVERAGE.md")
        for f in sorted(set(VANILLA) - jar):
            errs.append(f"menus: VANILLA lists MenuType.{f} which the game jar does not have: remove it")

    skins = {}
    if os.path.exists(SKINS):
        code = re.sub(r"/\*.*?\*/", "", _read(SKINS), flags=re.S)  # the javadoc quotes the syntax
        code = re.sub(r"//[^\n]*", "", code)
        for sm in re.finditer(r"skin\(\s*MenuType\.(\w+)\s*,\s*Kind\.(\w+)\s*\)", code):
            skins[sm.group(1)] = sm.group(2)
    else:
        errs.append("menus: inventory/client/MenuSkins.java is missing")

    consts, mod_ids = {}, {}
    screens = {}
    for f in _java_files():
        src = _read(f)
        for rm in re.finditer(r'(?:(\w+)\s*=\s*)?\b\w*MENUS\.register\(\s*"([a-z0-9_/]+)"', src):
            mod_ids[f"{MODID}:{rm.group(2)}"] = os.path.relpath(f, JAVA)
            if rm.group(1):
                consts[rm.group(1)] = f"{MODID}:{rm.group(2)}"
        for sm in re.finditer(r"MenuScreens\.register\(\s*[\w.]*?(\w+)\.get\(\)\s*,\s*([\w.]+)::new", src):
            screens[sm.group(1)] = sm.group(2).split(".")[-1]
    classes = {os.path.splitext(os.path.basename(f))[0] for f in _java_files()}

    # --- every menu type has a row
    expected = {}
    for field, mid in VANILLA.items():
        expected[mid] = ("vanilla", field)
    for mid in mod_ids:
        expected[mid] = ("mod", mid)
    for mid, (kind, ref) in sorted(expected.items()):
        if mid not in rows:
            errs.append(f"menus: {mid} has no row in the menu type map of docs/UI-COVERAGE.md (add a Skyrim screen or an "
                        f"explicit `vanilla-allowed` with the reason)")
            continue
        status, text = rows[mid]
        names = re.findall(r"`(\w+)`", text)
        if status == "vanilla-allowed":
            if len(re.sub(r"`", "", text).split()) < 4:
                errs.append(f"menus: {mid} is vanilla-allowed without a reason (a sentence saying why the vanilla look is right)")
            continue
        if not names:
            errs.append(f"menus: {mid} is `skyrim` but names no screen class in backticks")
            continue
        screen = names[0]
        if screen not in classes:
            errs.append(f"menus: {mid} names screen {screen} which does not exist in the sources")
        if kind == "vanilla":
            k = skins.get(ref)
            if k is None:
                errs.append(f"menus: {mid} is `skyrim` but MenuSkins.java has no skin(MenuType.{ref}, ...) line")
            elif KIND_SCREEN.get(k) != screen:
                errs.append(f"menus: {mid}: MenuSkins maps MenuType.{ref} to Kind.{k} ({KIND_SCREEN.get(k)}), the table names {screen}")
        else:
            const = next((c for c, i in consts.items() if i == mid), None)
            reg = screens.get(const) if const else None
            if reg is None:
                errs.append(f"menus: {mid} ({mod_ids[mid]}) has no MenuScreens.register(...) screen")
            elif reg != screen:
                errs.append(f"menus: {mid}: registered with {reg}, the table names {screen}")

    # --- stale entries
    for field in skins:
        if field not in VANILLA:
            errs.append(f"menus: MenuSkins.java skins MenuType.{field}, which is not a known menu type")
    for mid in rows:
        if mid not in expected:
            errs.append(f"menus: docs/UI-COVERAGE.md has a row for {mid}, which no longer exists")
    for field, k in skins.items():
        if k not in KIND_SCREEN:
            errs.append(f"menus: MenuSkins.java uses unknown Kind.{k}")

    if verbose:
        print(f"menus: {len(VANILLA)} game menu types ({'jar-verified' if jar else 'table only'}), {len(mod_ids)} mod menu types, "
              f"{len(rows)} rows, {len(skins)} skins, {len(errs)} problems")
    return errs


def main():
    errs = check(verbose=True)
    for e in errs:
        print("ERR", e)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
