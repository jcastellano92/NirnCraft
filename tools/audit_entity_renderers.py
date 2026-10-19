#!/usr/bin/env python3
"""Every registered NirnCraft entity type has a client renderer, and every rig renderer has its model and skins.

Registration is read from the Java sources (26.3 / NeoForge DeferredRegister):
  * `Supplier<EntityType<X>> FIELD = ENTITIES.register("id", ...)` (ModEntities, ArsenalEntities, FaunaEntities, ...),
  * helpers taking the id first (`insect("butterfly", ...)`, `fish("river_betty", ...)`),
  * bestiary specs (`beast(BestiarySpecs.wolf())`, `Bestiary.register(SeaSpecs.dreugh(), ...)`, mounts'
    `register(MountKind.X, wolfSpec())`): the id is the `new CreatureSpec("id")` inside that spec method,
  * enum loops (`for (ShipType t : ShipType.VALUES) SHIPS.put(t, ENTITIES.register(t.id, ...))`).
Renderers are read from the client sources: `registerEntityRenderer(Reg.FIELD.get(), ...)`, `rig/simple/reg(event,
Reg.FIELD, "def", ...)`, any helper `name(event, Reg.FIELD...)`, and enum loops over a registry's map.

A `rig` whose model def is not in `Defs` registers a NoopRenderer at runtime (the creature is invisible), so a
missing def or skin texture (textures/entity/creatures/<name>.png) is a problem too. Exit 1 when any registered
type lacks a renderer, a def or skin is missing, or when nothing was found at all (a broken parser must not pass).

    py -3 tools/audit_entity_renderers.py [-v]
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "mod" / "src" / "main" / "java"
TEX = ROOT / "mod" / "src" / "main" / "resources" / "assets" / "nirncraft" / "textures" / "entity" / "creatures"

FIELD = re.compile(r"static\s+final\s+Supplier<\s*(?:[\w.]+\.)?EntityType<[^=;]*?>>\s+([A-Z0-9_]+)\s*=\s*([^;]+);", re.S)
SPEC_METHOD = re.compile(r"static\s+CreatureSpec\s+(\w+)\s*\(\s*\)\s*\{")
SPEC_ID = re.compile(r'new\s+CreatureSpec\(\s*"([a-z0-9_]+)"')
ENUM_LOOP = re.compile(r"for\s*\(\s*(\w+)\s+(\w+)\s*:\s*\1\.VALUES\s*\)\s*\{?\s*(\w+)\.put\(\s*\2\s*,\s*ENTITIES\.register\(\s*\2\.id")
CALL = re.compile(r"(?:(\w+)\.)?(\w+)\(\s*\)")

R_GET = re.compile(r"registerEntityRenderer\(\s*(?:[\w.]+\.)?(\w+)\.([A-Z0-9_]+)\.get\(\)")
R_RIG = re.compile(r"\b(?:rig|simple|reg)\(\s*event\s*,\s*(?:[\w.]+\.)?(\w+)\.([A-Z0-9_]+)\s*,\s*\"(\w+)\"\s*,([^;]*)", re.S)
R_HELPER = re.compile(r"\b\w+\(\s*event\s*,\s*(?:[\w.]+\.)?(\w+)\.([A-Z0-9_]+)\b")
R_LOOP = re.compile(r"for\s*\([^)]*\)\s*event\.registerEntityRenderer\(\s*(?:[\w.]+\.)?(\w+)\.\w+\(")


def java_files():
    return [(p, p.read_text(encoding="utf-8", errors="replace")) for p in JAVA.rglob("*.java")]


def spec_ids(files):
    """(class, method) -> spec id for every `static CreatureSpec m() { ... new CreatureSpec("id")`."""
    out = {}
    for p, src in files:
        for m in SPEC_METHOD.finditer(src):
            sid = SPEC_ID.search(src, m.end(), m.end() + 800)
            if sid:
                out[(p.stem, m.group(1))] = sid.group(1)
    return out


def enum_ids(files, enum):
    for p, src in files:
        if p.stem == enum and re.search(rf"\benum\s+{enum}\b", src):
            body = src.split("{", 1)[1]
            return [c.lower() for c in re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\s*\(", body, re.M)]
    return []


def registrations(files, specs):
    """{(class, FIELD): id} and {class: [ids registered in an enum loop]}."""
    fields, loops = {}, {}
    for p, src in files:
        if "EntityType" not in src:
            continue
        for m in FIELD.finditer(src):
            name, rhs = m.group(1), m.group(2)
            lit = re.search(r'^\s*(?:ENTITIES\.register|\w+)\(\s*"([a-z0-9_]+)"', rhs)
            eid = lit.group(1) if lit else None
            if eid is None:
                for c in CALL.finditer(rhs):
                    eid = specs.get((c.group(1) or p.stem, c.group(2)))
                    if eid:
                        break
            fields[(p.stem, name)] = eid or "?" + name.lower()
        for m in ENUM_LOOP.finditer(src):
            loops.setdefault(p.stem, []).extend(enum_ids(files, m.group(1)))
    return fields, loops


def renderers(files, reg_classes):
    """(set of (class, FIELD) with a renderer, {class} rendered by enum loop, [(class, FIELD, def, args, where)])."""
    seen, looped, rigs = set(), set(), []
    for p, src in files:
        if "client" not in p.parts:
            continue
        for rx in (R_GET, R_HELPER):
            for m in rx.finditer(src):
                if m.group(1) in reg_classes:
                    seen.add((m.group(1), m.group(2)))
        for m in R_RIG.finditer(src):
            if m.group(1) in reg_classes:
                seen.add((m.group(1), m.group(2)))
                line = src.count("\n", 0, m.start()) + 1
                rigs.append((m.group(1), m.group(2), m.group(3), m.group(4), f"{p.relative_to(JAVA).as_posix()}:{line}"))
        for m in R_LOOP.finditer(src):
            if m.group(1) in reg_classes:
                looped.add(m.group(1))
    return seen, looped, rigs


def skins(defname, args):
    """Texture names a rig call uses: the variants (or the def name) and the glow textures."""
    parts = [a.strip() for a in re.split(r",(?![^(){}]*[)}])", args)]
    names = []
    variants = parts[2] if len(parts) > 2 else "null"
    num = re.match(r'numbered\(\s*"(\w+)"\s*,(.*)\)$', variants, re.S)
    if num:  # numbered("base", "", "a") -> base, base_a
        names += [num.group(1) + ("_" + x if x else "") for x in re.findall(r'"(\w*)"', num.group(2))]
    else:
        names += re.findall(r'"(\w+)"', variants) or [defname]
    if len(parts) > 3:
        names += re.findall(r'"(\w+)"', parts[3])
    return names


def main():
    verbose = "-v" in sys.argv
    files = java_files()
    specs = spec_ids(files)
    fields, loops = registrations(files, specs)
    reg_classes = {c for c, _ in fields} | set(loops)
    seen, looped, rigs = renderers(files, reg_classes)
    defs = set()
    for p, src in files:  # Defs.reg in rig/defs; RealmDefs.put / SeaDefs.ALL.put are merged into Defs.ALL at runtime
        if ("rig" in p.parts and "defs" in p.parts) or p.stem.endswith("Defs"):
            defs |= set(re.findall(r'\b(?:reg|put|putIfAbsent)\(\s*"(\w+)"\s*,\s*\w+::', src))

    total = len(fields) + sum(len(v) for v in loops.values())
    print(f"Registered entity types found: {total} ({len(fields)} fields in {len(reg_classes)} registries, "
          f"{sum(len(v) for v in loops.values())} from enum loops; {len(specs)} creature specs)")
    print(f"Renderer registrations found: {len(seen)} fields + {len(looped)} enum loops; {len(rigs)} rig calls; "
          f"{len(defs)} rig model defs")
    problems = []
    if total == 0 or not seen:
        problems.append("parser found no entity registrations or no renderers: the Java layout changed, fix this audit")
    for (cls, name), eid in sorted(fields.items(), key=lambda kv: kv[1]):
        if eid.startswith("?"):
            problems.append(f"{cls}.{name}: entity id not resolved (registration form unknown to this audit)")
        if (cls, name) not in seen:
            problems.append(f"nirncraft:{eid.lstrip('?')} ({cls}.{name}): no entity renderer registered")
    for cls, ids in loops.items():
        if cls not in looped:
            problems += [f"nirncraft:{i} ({cls} enum loop): no entity renderer registered" for i in ids]
    for cls, name, d, args, where in rigs:
        if defs and d not in defs:
            problems.append(f"{cls}.{name}: rig model def '{d}' not in Defs, drawn as nothing ({where})")
        for t in skins(d, args):
            if not (TEX / f"{t}.png").is_file():
                problems.append(f"{cls}.{name}: skin textures/entity/creatures/{t}.png missing ({where})")
    if verbose:
        for (cls, name), eid in sorted(fields.items(), key=lambda kv: kv[1]):
            print(f"  {eid:28s} {cls}.{name}")
    if problems:
        print(f"ERROR: {len(problems)} renderer problems:")
        for pr in problems:
            print(f"  - {pr}")
    else:
        print("SUCCESS: every registered entity type has a renderer, every rig its model and skins")
    print(f"problems {len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
