#!/usr/bin/env python3
"""Static asset audit: every block, item, model and texture the mod ships or registers must resolve.

Why it exists: tools/check_assets.py only looked at ITEM models of ids that have an `item.nirncraft.*` lang key and
treated every un-namespaced reference as nirncraft's. It never opened a blockstate, never followed a model parent chain
to find the texture variables a face uses, never checked a state against the blockstate's variant keys, and never
looked at blocks that have no lang entry. A block whose model is missing renders as the magenta/black "missing"
cube in the world; this audit checks the whole chain.

Checks (ERR fails the run, WARN is informational):
  blockstates   every registered block has assets/nirncraft/blockstates/<id>.json (unless it renders INVISIBLE or is an
                entity-only render); the JSON parses; every "model" it names exists; with the registry dump every
                variant key names real properties with valid values and EVERY state of the block matches a variant
                (an unmatched state renders as the missing model); multipart parts name models that exist.
  models        every model file parses; the parent chain resolves (nirncraft, minecraft from the vanilla client jar,
                builtin/*, other mods are listed as external); every texture a face uses (#var) is defined somewhere in
                the chain and points at an existing PNG; blocks and block items have a particle texture.
  items         every registered item has assets/nirncraft/models/item/<id>.json (a BlockItem without one is a missing-
                model cube in the hand and the inventory); generated item models have layer0.
  textures      "textures/....png" literals in the Java sources, particle definitions and animation metas exist.
  ids           'nirncraft:<name>' strings in resources (city plans, loot, recipes, quests...) and in the Java sources
                that name no registered block/item/entity/registry entry and no file of the data pack: those become
                air or a vanilla fallback at runtime.

The registry comes from the dump the game writes (com.nirncraft.dev.AssetDump: mod/run/nirncraft-asset-audit.json, written
by every harness server start or `/nirncraft-assets dump`). Without a dump the registry is approximated from the lang
files and block/item model files (then the property and unregistered-id checks that need the real registry are skipped).

    py -3 -I tools/qa/asset_audit.py [--dump FILE] [--strict] [--list N] [--json]
Exit code 1 when there are ERR lines (--strict also fails on WARN).
"""
from __future__ import annotations

import argparse
import itertools
import json
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
MAIN = ROOT / "mod" / "src" / "main"
RES = MAIN / "resources"
ASSETS = RES / "assets"
SKY = ASSETS / "nirncraft"
JAVA = MAIN / "java"
LANG = MAIN / "lang" / "nirncraft"
DUMP_NAME = "nirncraft-asset-audit.json"
DUMP_CANDIDATES = [ROOT / "mod" / "run" / DUMP_NAME, ROOT / "mod" / "run" / "devworld" / DUMP_NAME]
VANILLA_JAR_GLOBS = [
    Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar",
    Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client.jar",
]
NS = "nirncraft"
RID = re.compile(r"(?<![A-Za-z0-9_.:/-])#?nirncraft:([a-z0-9_./-]+)")


class Report:
    def __init__(self):
        self.err = []
        self.warn = []

    def e(self, kind, msg):
        if (kind, msg) not in self.err:
            self.err.append((kind, msg))

    def w(self, kind, msg):
        if (kind, msg) not in self.warn:
            self.warn.append((kind, msg))


# ---------------------------------------------------------------------------------------------- vanilla assets

class Vanilla:
    """The vanilla client jar's assets/minecraft (model/blockstate/texture names and model JSON)."""

    def __init__(self):
        self.zip = None
        self.names = set()
        for p in VANILLA_JAR_GLOBS:
            if p.exists():
                try:
                    z = zipfile.ZipFile(p)
                    names = {n for n in z.namelist() if n.startswith("assets/minecraft/")}
                    if any(n.endswith("models/block/cube_all.json") for n in names):
                        self.zip, self.names = z, names
                        break
                except Exception:  # noqa: BLE001
                    pass
        self._cache = {}

    @property
    def ok(self):
        return self.zip is not None

    def has(self, rel):
        return ("assets/minecraft/" + rel) in self.names

    def model(self, path):
        if path in self._cache:
            return self._cache[path]
        out = None
        n = f"assets/minecraft/models/{path}.json"
        if n in self.names:
            try:
                out = json.loads(self.zip.read(n).decode("utf-8"))
            except Exception:  # noqa: BLE001
                out = None
        self._cache[path] = out
        return out


# ---------------------------------------------------------------------------------------------- reference parsing

def split(ref, default_ns="minecraft"):
    if ":" in ref:
        ns, _, p = ref.partition(":")
        return ns, p
    return default_ns, ref


class Assets:
    def __init__(self, vanilla, rep):
        self.v = vanilla
        self.rep = rep
        self.models = {}      # path (under models/) -> parsed json | None (unparseable)
        self.model_files = {}
        for f in sorted((SKY / "models").rglob("*.json")):
            rel = f.relative_to(SKY / "models").with_suffix("").as_posix()
            self.model_files[rel] = f
            try:
                self.models[rel] = json.loads(f.read_text(encoding="utf-8"))
            except Exception as ex:  # noqa: BLE001
                self.models[rel] = None
                rep.e("model", f"models/{rel}.json: bad JSON ({ex})")
        self.tex = {p.relative_to(SKY / "textures").with_suffix("").as_posix()
                    for p in (SKY / "textures").rglob("*.png")}
        self.mc_tex_override = set()
        mc = ASSETS / "minecraft" / "textures"
        if mc.exists():
            self.mc_tex_override = {p.relative_to(mc).with_suffix("").as_posix() for p in mc.rglob("*.png")}
        self.external_refs = defaultdict(set)
        self.syntax_done = set()

    # ---- existence
    def texture_exists(self, ref):
        ns, p = split(ref)
        if ns == NS:
            return p in self.tex
        if ns == "minecraft":
            if p in self.mc_tex_override:
                return True
            return self.v.has(f"textures/{p}.png") if self.v.ok else True
        self.external_refs[ns].add(ref)
        return True

    def model_exists(self, ref):
        ns, p = split(ref)
        if ns == NS:
            return p in self.models
        if ns == "minecraft":
            if p.startswith("builtin/"):
                return True
            return self.v.has(f"models/{p}.json") if self.v.ok else True
        self.external_refs[ns].add(ref)
        return True

    def model_json(self, ref):
        ns, p = split(ref)
        if ns == NS:
            return self.models.get(p)
        if ns == "minecraft":
            return self.v.model(p) if self.v.ok else None
        return None

    # ---- model chain
    def chain(self, ref, seen=None):
        """[(ref, json)...] from the model up through its parents (ends at a missing parent or a builtin)."""
        out = []
        seen = seen or set()
        cur = ref
        while cur and cur not in seen:
            seen.add(cur)
            ns, p = split(cur)
            if ns == "minecraft" and p.startswith("builtin/"):
                out.append((cur, {"__builtin__": p}))
                break
            data = self.model_json(cur)
            out.append((cur, data))
            if not isinstance(data, dict):
                break
            parent = data.get("parent")
            if not parent:
                break
            pns, pp = split(parent)
            cur = f"{pns}:{pp}"
        return out

    def analyse_model(self, ref):
        """Return (problems, info) for a model reference as seen by the game: merged textures, used variables."""
        probs = []
        chain = self.chain(ref)
        merged = {}
        used = set()
        builtin = None
        has_elements = False
        for cref, data in reversed(chain):
            if not isinstance(data, dict):
                continue
            if "__builtin__" in data:
                builtin = data["__builtin__"]
                continue
            for k, v in (data.get("textures") or {}).items():
                merged[k] = v
        for cref, data in chain:
            if not isinstance(data, dict):
                continue
            if data.get("elements"):
                has_elements = True
            for el in data.get("elements") or []:
                for face in (el.get("faces") or {}).values():
                    t = face.get("texture")
                    if isinstance(t, str) and t.startswith("#"):
                        used.add(t[1:])
                    elif isinstance(t, str):
                        merged.setdefault("__lit_" + t, t)
        return merged, used, builtin, has_elements, chain

    def resolve_var(self, merged, var, depth=0):
        v = merged.get(var)
        while isinstance(v, str) and v.startswith("#") and depth < 12:
            v = merged.get(v[1:])
            depth += 1
        if isinstance(v, str) and not v.startswith("#"):
            return v
        return None


# ---------------------------------------------------------------------------------------------- the audit

FACES = {"down", "up", "north", "south", "west", "east"}
ROT_OK = (-45.0, -22.5, 0.0, 22.5, 45.0)
DISPLAY_KEYS = {"thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand",
                "gui", "head", "ground", "fixed"}


def _vec(v, n):
    return isinstance(v, list) and len(v) == n and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v)


def model_syntax(data):
    """Problems the vanilla model loader rejects: such a model is not baked at all and renders as the missing cube.
    The loader (BlockElement/BlockElementRotation/BlockElementFace/ItemTransform deserializers) is strict: an element
    rotation angle must be one of -45, -22.5, 0, 22.5, 45, positions lie in -16..32, every face names a texture."""
    out = []
    if not isinstance(data, dict):
        return ["not a JSON object"]
    els = data.get("elements")
    if els is not None and not isinstance(els, list):
        out.append("'elements' is not a list")
        els = []
    for i, el in enumerate(els or []):
        tag = f"element {i}"
        if not isinstance(el, dict):
            out.append(f"{tag}: not an object")
            continue
        for k in ("from", "to"):
            v = el.get(k)
            if not _vec(v, 3):
                out.append(f"{tag}: '{k}' must be 3 numbers")
            elif any(x < -16 or x > 32 for x in v):
                out.append(f"{tag}: '{k}' {v} outside -16..32")
        rot = el.get("rotation")
        if rot is not None:
            if not isinstance(rot, dict):
                out.append(f"{tag}: rotation is not an object")
            else:
                ang = rot.get("angle")
                if not isinstance(ang, (int, float)) or float(ang) not in ROT_OK:
                    out.append(f"{tag}: invalid rotation angle {ang} (only -45/-22.5/0/22.5/45 are allowed: the whole model fails to load)")
                if rot.get("axis") not in ("x", "y", "z"):
                    out.append(f"{tag}: rotation axis {rot.get('axis')!r} must be x, y or z")
                if not _vec(rot.get("origin"), 3):
                    out.append(f"{tag}: rotation without a 3-number origin")
        faces = el.get("faces")
        if not isinstance(faces, dict) or not faces:
            out.append(f"{tag}: no faces")
            continue
        for fn, face in faces.items():
            if fn not in FACES:
                out.append(f"{tag}: unknown face '{fn}'")
                continue
            if not isinstance(face, dict) or not isinstance(face.get("texture"), str):
                out.append(f"{tag}: face {fn} has no texture")
                continue
            if "uv" in face and not _vec(face["uv"], 4):
                out.append(f"{tag}: face {fn} uv must be 4 numbers")
            if face.get("rotation", 0) not in (0, 90, 180, 270):
                out.append(f"{tag}: face {fn} rotation {face.get('rotation')} must be 0/90/180/270")
            if "cullface" in face and face["cullface"] not in FACES:
                out.append(f"{tag}: face {fn} cullface {face['cullface']!r}")
    tex = data.get("textures")
    if tex is not None and (not isinstance(tex, dict) or not all(isinstance(v, str) for v in tex.values())):
        out.append("'textures' must map names to strings")
    disp = data.get("display")
    if isinstance(disp, dict):
        for k, v in disp.items():
            if k not in DISPLAY_KEYS:
                out.append(f"display '{k}' is not a known slot")
            elif isinstance(v, dict):
                for kk in ("rotation", "translation", "scale"):
                    if kk in v and not _vec(v[kk], 3):
                        out.append(f"display {k}.{kk} must be 3 numbers")
    ov = data.get("overrides")
    if ov is not None and not isinstance(ov, list):
        out.append("'overrides' is not a list")
    return out


def lang_ids(prefix):
    out = set()
    if not LANG.exists():
        return out
    for f in LANG.glob("*.json"):
        try:
            for k in json.loads(f.read_text(encoding="utf-8")):
                if k.startswith(prefix):
                    n = k[len(prefix):]
                    if re.fullmatch(r"[a-z0-9_]+", n):
                        out.add(n)
        except Exception:  # noqa: BLE001
            pass
    return out


def check_model_ref(A, rep, ref, owner, kind, *, particle_needed, item=False, seen=None, check_vars=True):
    """Validate one model as used by `owner` (a blockstate or an item): parents, element textures, particle."""
    if seen is not None and ref in seen:
        return
    if seen is not None:
        seen.add(ref)
    ns, p = split(ref)
    if ns == NS and p not in A.models:
        rep.e(kind, f"{owner}: model {ref} does not exist (models/{p}.json)")
        return
    if ns == "minecraft" and not A.model_exists(ref):
        rep.e(kind, f"{owner}: vanilla model {ref} does not exist")
        return
    merged, used, builtin, has_elements, chain = A.analyse_model(ref)
    for cref, data in chain:
        if split(cref)[0] == NS and isinstance(data, dict) and cref not in A.syntax_done:
            A.syntax_done.add(cref)
            for prob in model_syntax(data):
                rep.e(kind, f"model {split(cref)[1]}: {prob}")
            for ovr in data.get("overrides") or []:
                om = ovr.get("model") if isinstance(ovr, dict) else None
                if not om or not A.model_exists(om if ":" in om else om):
                    rep.e(kind, f"model {split(cref)[1]}: override model {om!r} does not exist")
    for cref, data in chain:
        if data is None:
            # chain ended at a missing file
            if A.model_exists(cref) is False or (split(cref)[0] == NS and split(cref)[1] not in A.models):
                rep.e(kind, f"{owner}: model {ref}: parent {cref} does not exist")
            break
        if isinstance(data, dict) and "parent" in data:
            pr = data["parent"]
            if isinstance(pr, str) and not A.model_exists(pr):
                rep.e(kind, f"{owner}: model {cref}: parent {pr} does not exist")
    # literal texture refs anywhere in the chain
    for k, v in merged.items():
        if k.startswith("__lit_"):
            v = v
        if isinstance(v, str) and not v.startswith("#"):
            if not A.texture_exists(v):
                rep.e(kind, f"{owner}: model {ref}: texture {v} ({k.replace('__lit_', 'face ')}) has no PNG")
    # variables the faces use
    if check_vars and builtin != "builtin/generated" and builtin != "builtin/entity":
        for var in sorted(used):
            val = A.resolve_var(merged, var)
            if val is None:
                rep.e(kind, f"{owner}: model {ref}: face texture #{var} is never defined (missing-texture face)")
    if builtin == "builtin/generated" or any(isinstance(d, dict) and split(c)[1] in ("item/generated", "item/handheld")
                                             for c, d in chain):
        if A.resolve_var(merged, "layer0") is None:
            rep.e(kind, f"{owner}: item model {ref}: generated model without layer0 texture")
    if particle_needed and builtin != "builtin/entity" and has_elements:
        if A.resolve_var(merged, "particle") is None:
            rep.w(kind, f"{owner}: model {ref}: no particle texture (breaking it shows magenta particles)")


def variant_syntax(rep, owner, key, var):
    """x/y rotations of a blockstate variant must be multiples of 90 (anything else throws: the blockstate is missing)."""
    for axis in ("x", "y"):
        if axis in var and var[axis] not in (0, 90, 180, 270):
            rep.e("blockstate", f"{owner}: variant '{key}': {axis}={var[axis]} is not a quarter turn (the blockstate fails to load)")
    if "weight" in var and not (isinstance(var["weight"], int) and var["weight"] >= 1):
        rep.e("blockstate", f"{owner}: variant '{key}': weight {var['weight']!r} must be an integer >= 1")


def state_matches(key_props, state):
    return all(state.get(k) == v for k, v in key_props.items())


def parse_key(key):
    if key == "":
        return {}
    out = {}
    for part in key.split(","):
        k, _, v = part.partition("=")
        out[k] = v
    return out


def audit(args):
    rep = Report()
    vanilla = Vanilla()
    A = Assets(vanilla, rep)
    if not vanilla.ok:
        rep.w("setup", "vanilla client jar not found: minecraft: references are not verified")

    # ---- the registry
    dump = None
    if args.dump:
        dump_path = Path(args.dump)
    else:
        found = [p for p in DUMP_CANDIDATES if p.exists()]
        dump_path = max(found, key=lambda p: p.stat().st_mtime) if found else DUMP_CANDIDATES[0]
    if dump_path.exists():
        try:
            dump = json.loads(dump_path.read_text(encoding="utf-8"))
        except Exception as ex:  # noqa: BLE001
            rep.w("setup", f"registry dump unreadable ({ex}); falling back to lang and model files")
    blocks = {}
    items = {}
    registries = {}
    if dump:
        blocks = dump["blocks"]
        items = {k: v for k, v in dump["items"].items()}
        registries = dump.get("registries", {})
    else:
        names = lang_ids("block.nirncraft.")
        for n in names:
            blocks[n] = {"render": "MODEL", "props": None, "item": True}
        for n in lang_ids("item.nirncraft."):
            if n not in blocks:
                items[n] = {"block": False}
        for n in blocks:
            items.setdefault(n, {"block": True})
        # blocks that have a blockstate are blocks
        for f in (SKY / "blockstates").glob("*.json"):
            blocks.setdefault(f.stem, {"render": "MODEL", "props": None, "item": True})
        rep.w("setup", "no registry dump (run the server once: qa.ps1 -Server, or /nirncraft-assets dump); "
                       "block properties and unregistered ids are checked only approximately")

    # ---- blockstates
    states_dir = SKY / "blockstates"
    state_files = {f.stem: f for f in states_dir.glob("*.json")}
    referenced_models = set()
    for bid, info in sorted(blocks.items()):
        f = state_files.get(bid)
        render = info.get("render", "MODEL")
        if f is None:
            if render == "MODEL":
                rep.e("blockstate", f"block nirncraft:{bid} has no blockstate JSON (renders as the magenta/black missing cube)")
            continue
    for bid, f in sorted(state_files.items()):
        owner = f"blockstate {bid}"
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except Exception as ex:  # noqa: BLE001
            rep.e("blockstate", f"{owner}: bad JSON ({ex})")
            continue
        if bid not in blocks:
            rep.w("blockstate", f"{owner}: no registered block nirncraft:{bid} (orphan file)")
        props = blocks.get(bid, {}).get("props")
        models_in = []
        variants = data.get("variants")
        multipart = data.get("multipart")
        if variants is None and multipart is None:
            rep.e("blockstate", f"{owner}: neither variants nor multipart")
            continue
        keys = []
        for key, v in (variants or {}).items():
            for var in (v if isinstance(v, list) else [v]):
                m = var.get("model") if isinstance(var, dict) else None
                if not m:
                    rep.e("blockstate", f"{owner}: variant '{key}' has no model")
                else:
                    models_in.append(split(m)[0] + ":" + split(m)[1])
                    variant_syntax(rep, owner, key, var)
            keys.append(key)
        for part in multipart or []:
            ap = part.get("apply")
            for var in (ap if isinstance(ap, list) else [ap]):
                m = var.get("model") if isinstance(var, dict) else None
                if not m:
                    rep.e("blockstate", f"{owner}: multipart apply without a model")
                else:
                    models_in.append(split(m)[0] + ":" + split(m)[1])
                    variant_syntax(rep, owner, "multipart", var)
        seen = set()
        for m in models_in:
            referenced_models.add(m)
            check_model_ref(A, rep, m, owner, "blockstate", particle_needed=True, seen=seen)
        # variant keys vs the block's real properties: every state needs a variant
        if variants is not None and props is not None and bid in blocks:
            for key in keys:
                for k, val in parse_key(key).items():
                    if k not in props:
                        rep.e("blockstate", f"{owner}: variant '{key}' names unknown property '{k}' "
                                            f"(block has {sorted(props) or 'none'}): the whole blockstate fails to load")
                    elif val not in props[k]:
                        rep.e("blockstate", f"{owner}: variant '{key}': '{val}' is not a value of {k} {props[k]}")
            names = sorted(props)
            total = 1
            for n in names:
                total *= max(1, len(props[n]))
            parsed = [parse_key(k) for k in keys]
            if total <= 20000:
                miss = 0
                example = None
                for combo in itertools.product(*[props[n] for n in names]):
                    st = dict(zip(names, combo))
                    if not any(state_matches(p, st) for p in parsed):
                        miss += 1
                        example = example or ",".join(f"{k}={v}" for k, v in st.items())
                if miss:
                    rep.e("blockstate", f"{owner}: {miss}/{total} states match no variant (e.g. '{example}'): "
                                        f"those render as the missing-model cube")
            elif parsed and not any(p == {} for p in parsed):
                rep.w("blockstate", f"{owner}: {total} states, not enumerated")

    # ---- item models
    item_dir = SKY / "models" / "item"
    for iid, info in sorted(items.items()):
        owner = f"item {iid}"
        mp = f"item/{iid}"
        if mp not in A.models:
            is_block = info.get("block", False)
            rep.e("item", f"{owner}: no item model models/item/{iid}.json"
                          + (" (block item: shows the missing-model cube in inventory and hand)" if is_block else ""))
            continue
        check_model_ref(A, rep, f"{NS}:{mp}", owner, "item", particle_needed=False)
    # models of every other kind: parents and textures even when nothing references them
    parents = set()
    for d in A.models.values():
        if isinstance(d, dict) and isinstance(d.get("parent"), str):
            pns, pp = split(d["parent"])
            if pns == NS:
                parents.add(pp)
    for rel in sorted(A.models):
        if rel in {split(m)[1] for m in referenced_models} or rel.startswith("item/"):
            continue
        data = A.models[rel]
        if not isinstance(data, dict):
            continue
        # a template (somebody's parent) leaves its texture variables to its children
        check_model_ref(A, rep, f"{NS}:{rel}", f"model {rel}", "model", particle_needed=False, check_vars=rel not in parents)
        # an unreferenced template parent is fine (its children define the textures); only literals and parents matter
    # item models not tied to a registered item
    reg_items = set(items)
    via_override = set()
    for d in A.models.values():
        for ovr in (d.get("overrides") or []) if isinstance(d, dict) else []:
            om = ovr.get("model") if isinstance(ovr, dict) else None
            if isinstance(om, str):
                ons, op = split(om)
                if ons == NS:
                    via_override.add(op)
    for rel in sorted(A.models):
        if rel.startswith("item/"):
            n = rel[5:]
            if rel in via_override or rel in parents:
                continue
            if n not in reg_items and "/" not in n and dump:
                rep.w("item", f"model item/{n}.json: no registered item nirncraft:{n} (orphan)")

    # ---- entities: a registered entity whose texture file cannot be found anywhere under textures/entity (heuristic;
    # renderers that tint a vanilla texture, draw item models or build the name at runtime are listed by name only)
    if dump:
        ent_files = [p.relative_to(SKY / "textures" / "entity").with_suffix("").as_posix()
                     for p in (SKY / "textures" / "entity").rglob("*.png")]
        stems = {e.rsplit("/", 1)[-1] for e in ent_files}
        no_tex = [e for e in dump.get("entities", [])
                  if e not in stems and not any(st.startswith(e + "_") or st.startswith(e) for st in stems)]
        if no_tex:
            rep.w("entity", f"{len(no_tex)} registered entities have no textures/entity/**/<id>*.png "
                            f"(check their renderers: {', '.join(sorted(no_tex)[:14])}{' ...' if len(no_tex) > 14 else ''})")

    # ---- NPC role skins: NpcRenderer maps every role to textures/entity/society/<id>_<skin>.png (fallback skins when the
    # layered NpcSkins composition is not available); a role without them is a missing-texture humanoid
    role_src = JAVA / "com" / "nirncraft" / "society" / "NpcRole.java"
    rend_src = JAVA / "com" / "nirncraft" / "society" / "client" / "NpcRenderer.java"
    if role_src.exists() and rend_src.exists():
        rs = role_src.read_text(encoding="utf-8")
        body = rs[rs.index("public enum NpcRole"):]
        body = body[:body.index(";")]
        roles = re.findall(r"^\s+([A-Z][A-Z_]+)\(", body, re.M)
        rt = rend_src.read_text(encoding="utf-8")
        mapping = {}
        m = re.search(r"String id = switch \(role\) \{(.*?)\};", rt, re.S)
        if m:
            for names, target in re.findall(r"case ([A-Z_, ]+) -> \"([a-z_]+)\"", m.group(1)):
                for n in names.split(","):
                    mapping[n.strip()] = target
        sk = re.search(r"SKINS = (\d+)", rs)
        skins = int(sk.group(1)) if sk else 2
        for r in roles:
            tid = mapping.get(r, r.lower())
            for i in range(skins):
                if f"entity/society/{tid}_{i}" not in A.tex:
                    rep.e("entity", f"NPC role {r.lower()}: skin textures/entity/society/{tid}_{i}.png is missing "
                                    f"(NpcRenderer shows the missing-texture humanoid)")

    # ---- the particle definitions and animation metas
    for f in sorted((SKY / "particles").glob("*.json")):
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception as ex:  # noqa: BLE001
            rep.e("particle", f"particles/{f.name}: bad JSON ({ex})")
            continue
        for t in d.get("textures", []):
            ns, p = split(t)
            if ns == NS and f"particle/{p}" not in A.tex:
                rep.e("particle", f"particles/{f.name}: texture {t} has no textures/particle/{p}.png")
    for m in sorted((SKY / "textures").rglob("*.png.mcmeta")):
        if not m.with_suffix("").exists():
            rep.e("texture", f"{m.relative_to(SKY).as_posix()}: animation meta without its PNG")
        try:
            json.loads(m.read_text(encoding="utf-8"))
        except Exception as ex:  # noqa: BLE001
            rep.e("texture", f"{m.relative_to(SKY).as_posix()}: bad JSON ({ex})")

    # ---- texture literals in the Java sources
    lit = re.compile(r'"((?:[a-z0-9_]+:)?textures/[a-z0-9_/.-]+\.png)"')
    for jf in sorted(JAVA.rglob("*.java")):
        txt = jf.read_text(encoding="utf-8", errors="replace")
        for m in lit.finditer(txt):
            ref = m.group(1)
            ns, p = split(ref, NS)
            p = p[len("textures/"):-len(".png")]
            ok = (p in A.tex) if ns == NS else (A.texture_exists(f"{ns}:{p}"))
            if ns == NS and not ok:
                # many classes build a minecraft: location from an un-namespaced literal
                ok = ((vanilla.has(f"textures/{p}.png") if vanilla.ok else True) or p in A.mc_tex_override)
            if not ok:
                line = txt.count("\n", 0, m.start()) + 1
                rep.e("texture", f"{jf.relative_to(MAIN).as_posix()}:{line}: texture literal {ref} does not exist")

    # ---- nirncraft:<id> strings that nothing registered
    known = set()
    if dump:
        known |= set(blocks) | set(items) | set(dump.get("entities", []))
        for reg in registries.values():
            known |= set(reg)
    else:
        known |= set(blocks) | set(items)
        known |= lang_ids("entity.nirncraft.") | lang_ids("effect.nirncraft.") | lang_ids("enchantment.nirncraft.")
        for jf in JAVA.rglob("*.java"):
            for m in re.finditer(r'"([a-z0-9_]{3,})"', jf.read_text(encoding="utf-8", errors="replace")):
                known.add(m.group(1))
    # every file of the data pack and the resource pack counts as a defined id (loot tables, tags, recipes, models...),
    # and so does every directory (a "nirncraft:loot/ingredients" prefix names the tag files below it)
    for base in (RES / "data" / NS, SKY, RES / NS):
        if not base.exists():
            continue
        for f in base.rglob("*"):
            if f.is_file():
                rel = f.relative_to(base).as_posix()
                parts = rel.rsplit(".", 1)[0].split("/")
                for i in range(len(parts)):
                    for j in range(i + 1, len(parts) + 1):
                        known.add("/".join(parts[i:j]))
    sj = SKY / "sounds.json"
    if sj.exists():
        try:
            known |= set(json.loads(sj.read_text(encoding="utf-8")))
        except Exception:  # noqa: BLE001
            pass
    # declared aliases: QuestSpawner.FALLBACKS (named creatures resolved to a real type at spawn time) and the
    # "NON_ITEMS" prefixes of the weights tables (poison_of_<effect> ...), which are built at runtime
    qs = JAVA / "com" / "nirncraft" / "quest" / "QuestSpawner.java"
    if qs.exists():
        known |= set(re.findall(r'Map\.entry\("nirncraft:([a-z0-9_]+)"', qs.read_text(encoding="utf-8")))
    known |= {"poison_of", "potion_of", "legendary_fish"}
    unknown = defaultdict(lambda: defaultdict(int))

    def note(path, rid):
        r = rid.rstrip("/")
        if r in known or "*" in rid or rid.endswith(("_", "/", "-", ".")):
            return
        unknown[rid][path] += 1

    def walk(node, path):
        """Strings of a JSON tree; an object {"id": ..., "required": false} (an optional tag entry) is skipped."""
        if isinstance(node, str):
            for m in RID.finditer(node):
                note(path, m.group(1))
        elif isinstance(node, list):
            for v in node:
                walk(v, path)
        elif isinstance(node, dict):
            optional = node.get("required") is False
            for k, v in node.items():
                if (optional and k == "id") or k in ("random_name", "random_sequence"):
                    continue
                walk(v, path)

    for base in (RES / "data", RES / NS, ASSETS / NS / "lore", ASSETS / NS / "fire"):
        if not base.exists():
            continue
        for f in base.rglob("*.json"):
            try:
                txt = f.read_text(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                continue
            if "nirncraft:" not in txt:
                continue
            rel = f.relative_to(RES).as_posix()
            try:
                walk(json.loads(txt), rel)
            except Exception:  # noqa: BLE001
                for m in RID.finditer(txt):
                    note(rel, m.group(1))
    # Java literals: used by builders through ForgeRegistries lookups or B.id(..., fallback). A literal on a line that
    # names its own fallbacks (Mobs.orElse, types(...), a FALLBACKS entry) is declared, not a gap.
    declared = re.compile(r"Mobs\.orElse\(|\btypes\(|Map\.entry\(|FALLBACKS|introType")
    for jf in sorted(JAVA.rglob("*.java")):
        txt = jf.read_text(encoding="utf-8", errors="replace")
        if "nirncraft:" not in txt:
            continue
        rel = jf.relative_to(MAIN).as_posix()
        for line in txt.splitlines():
            if "nirncraft:" not in line or declared.search(line) or line.lstrip().startswith(("*", "//")):
                continue
            for m in re.finditer(r'"#?nirncraft:([a-z0-9_./-]+)"', line):
                note(rel, m.group(1))
    for rid, where in sorted(unknown.items()):
        files = sorted(where)
        in_data = [w for w in files if w.startswith(("data/", "nirncraft/"))]
        msg = f"nirncraft:{rid} is not registered or defined ({sum(where.values())}x in {len(files)} file(s): " \
              f"{', '.join(files[:3])}{' ...' if len(files) > 3 else ''})"
        (rep.e if in_data else rep.w)("id", msg)

    stats = {
        "blocks": len(blocks), "items": len(items), "blockstates": len(state_files), "models": len(A.models),
        "textures": len(A.tex), "dump": bool(dump), "vanilla": vanilla.ok, "external": {k: len(v) for k, v in A.external_refs.items()},
    }
    return rep, stats


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dump", help="registry dump (default mod/run/nirncraft-asset-audit.json)")
    ap.add_argument("--strict", action="store_true", help="WARN also fails")
    ap.add_argument("--list", type=int, default=60, help="max ERR/WARN lines to print (default 60)")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    rep, st = audit(args)
    if args.json:
        print(json.dumps({"err": rep.err, "warn": rep.warn, "stats": st}, indent=1))
        return 1 if rep.err else 0
    for kind, msg in rep.err[:args.list]:
        print(f"ERR  [{kind}] {msg}")
    if len(rep.err) > args.list:
        print(f"ERR  ... {len(rep.err) - args.list} more (use --list N)")
    for kind, msg in rep.warn[:max(5, args.list // 3)]:
        print(f"WARN [{kind}] {msg}")
    if len(rep.warn) > max(5, args.list // 3):
        print(f"WARN ... {len(rep.warn) - max(5, args.list // 3)} more")
    mode = "registry dump" if st["dump"] else "no registry dump (approximate)"
    print(f"asset audit: {st['blocks']} blocks, {st['items']} items, {st['blockstates']} blockstates, {st['models']} models, "
          f"{st['textures']} textures ({mode}): {len(rep.err)} problems, {len(rep.warn)} warnings")
    bad = rep.err or (args.strict and rep.warn)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
