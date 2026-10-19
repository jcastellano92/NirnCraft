"""Is the bake packaged in the newest built core jar the one its inputs call for?

The jar carries nirncraft/baked/{landmass,roadplan,streetplan,sceneplan}.bin and a manifest naming the inputs hash they
were baked from; the inputs are the files Bake.java declares (live_world.jar_bake_inputs mirrors that hash without
booting Java). A stale bake does not break the game (it computes the plans at start, slower), so installs go on with a
warning; this check makes it a visible failure with its remedy instead. Exit 1 when stale or incomplete.

    py -3 tools/dev/check_bake.py [JAR]
"""
from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/dev"))
import live_world as lw  # noqa: E402

NAMES = {n + ".bin" for n in ("landmass", "roadplan", "streetplan", "sceneplan")}


def newest_jar():
    jars = sorted(Path(r"E:\NirnCraft-scratch\migration-26.3\build\libs").glob("nirncraft-*.jar"),
                  key=lambda p: p.stat().st_mtime, reverse=True)
    jars = [j for j in jars if not j.name.endswith(("-sources.jar", "-javadoc.jar"))]
    return jars[0] if jars else None


def main():
    jar = Path(sys.argv[1]) if len(sys.argv) > 1 else newest_jar()
    if not jar or not jar.exists():
        print("check_bake: no built core jar (py -3 tools/dev/build_263.py --offline jar)")
        return 1
    with zipfile.ZipFile(jar) as z:
        want = lw.jar_bake_inputs(z)
        try:
            manifest = json.loads(z.read("nirncraft/baked/manifest.json"))
        except KeyError:
            print(f"check_bake: {jar.name} has no packaged bake: stale (rebake: tools/bake/bake.ps1)")
            return 1
    have = manifest.get("inputs", "")
    files_ok = set(manifest.get("files", [])) == NAMES and manifest.get("format") == 1
    print(f"check_bake: {jar.name}: packaged bake inputs {have[:12]}, the jar's inputs {want[:12]}, files "
          f"{'complete' if files_ok else 'incomplete'}")
    if have != want or not files_ok:
        print("check_bake: stale: the game computes the plans at start until it is rebaked (tools/bake/bake.ps1)")
        return 1
    print("check_bake: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
