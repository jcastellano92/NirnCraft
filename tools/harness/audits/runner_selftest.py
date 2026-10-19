"""Self-test of the harness's own verdicts: the rules that once let a gate pass on tests nobody ran.

* reusable(): only real outcomes (pass, flag) of the same fingerprint stand in for a run; a skip never does (a cached
  skip kept 170 tests "skipped" gate after gate, and the gate passed).
* gate_verdict(): a test that did not run blocks the gate (quarantined ones are listed, not blocking); no result blocks.
* heartbeat_age(): a torn status read (no `updated`) is judged by the file's time, never as a 56-year-old heartbeat
  (that closed healthy clients as "crashed").
* review.parse_pages(): the reviewer's findings and its "OK n" count are both read, so the gate can tell a review
  that looked at everything from one that said nothing.

Prints "problems N" (the check's metric); exit 1 when any rule is broken.
"""
import json
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parents[1] / "dev"))

import harness  # noqa: E402
import live_world  # noqa: E402
import review  # noqa: E402

problems = []


def expect(cond, what):
    print(("ok    " if cond else "FAIL  ") + what)
    if not cond:
        problems.append(what)


# --- reusable ---------------------------------------------------------------------------------------------------
fp = "abc"
expect(harness.reusable({"fingerprint": fp, "status": "pass"}, fp), "a pass of the same fingerprint is reused")
expect(harness.reusable({"fingerprint": fp, "status": "flag"}, fp), "a flag of the same fingerprint is reused")
expect(not harness.reusable({"fingerprint": fp, "status": "skip", "error": "skipped: 3 client crashes"}, fp),
       "a skip is never reused (the test never ran)")
expect(not harness.reusable({"fingerprint": fp, "status": "fail"}, fp), "a failure is run again")
expect(not harness.reusable({"fingerprint": "old", "status": "pass"}, fp), "another fingerprint is run again")
expect(not harness.reusable(None, fp), "no record: run")

# --- gate_verdict -----------------------------------------------------------------------------------------------
recs = [("a|shaders", {"status": "pass"}),
        ("b|shaders", {"status": "skip", "error": "skipped: 3 client crashes this run"}),
        ("c|shaders", {"status": "skip", "error": "quarantined after 4 failing runs (x)"}),
        ("d|shaders", None),
        ("e|shaders", {"status": "fail", "old": True}),
        ("f|shaders", {"status": "fail", "new": True})]
bad, known, unrun = harness.gate_verdict(recs, lambda r: bool(r.get("new")))
expect(any(b.startswith("b|shaders") for b in bad), "a test skipped for crashes blocks the gate")
expect(not any(b.startswith("c|shaders") for b in bad), "a quarantined test does not block")
expect(len(unrun) == 2, "both skipped tests are listed as not run")
expect(any(b.startswith("d|shaders") for b in bad), "a test without a result blocks")
expect("e|shaders" in known and "f|shaders" in bad, "old failures are known, new ones block")
expect(not any(b.startswith("a|shaders") for b in bad), "a pass does not block")

# --- heartbeat_age ----------------------------------------------------------------------------------------------
with tempfile.TemporaryDirectory() as d:
    f = Path(d) / "status.json"
    f.write_text("{}", encoding="utf-8")
    now = time.time()
    expect(live_world.heartbeat_age({}, f, now) < 60, "a torn read is aged by the file's time (seconds, not decades)")
    expect(abs(live_world.heartbeat_age({"updated": (now - 5) * 1000}, f, now) - 5) < 0.5,
           "a written heartbeat is aged by its own time")
    expect(live_world.heartbeat_age({"updated": (now - 120) * 1000}, f, now) > live_world.HEARTBEAT_STALE,
           "an old heartbeat is still stale")
    expect(live_world.heartbeat_age({}, Path(d) / "missing.json", now) < 1, "no file: not called stale on a guess")

# --- review.parse_pages -----------------------------------------------------------------------------------------
found, ok = review.parse_pages("BROKEN dist-a [shaders]: black voids\nREGRESSION fid-b: darker\nCHECK x: look\n"
                               "OK 59 tests look right")
expect(len(found) == 3 and ok == 59, "findings and the OK count are both read")
found, ok = review.parse_pages("I could not open the folder.")
expect(found == [] and ok is None, "an answer with no verdicts reads as unaccounted (ok None), not as clean")

# --- batching by settings ---------------------------------------------------------------------------------------
def scene(i, opts=None, group="live"):
    return ({"id": i, "tier": "live", "group": group, "pose": {}, "pack_options": opts}, "shaders")


order = [x[0]["id"] for x in harness.order([scene("a"), scene("b", {"TAA": False}), scene("c"), scene("d", {"TAA": False}),
                                             scene("e", None, "other")])]
expect(order.index("c") < order.index("b") and order.index("d") == order.index("b") + 1,
       "scenes with the same pack options run together (one shader reload per run of them)")
expect(order[-1] == "e", "another group (JVM settings: a restart) comes after the running group's scenes")
expect(harness.options_key({"TAA": False, "A": 1}) == "A=1;TAA=false" and harness.options_key(None) == "",
       "pack options compare as one stable key")

# --- dependencies: a lab scene that reads an in-game export ---------------------------------------------------
lab = {"id": "lab-x", "tier": "lab", "group": "lab", "after": ["exp"], "lab": {"tool": "atlas_lab", "blocks": "@game/no-such-dir/none.bin"}}
exp = ({"id": "exp", "tier": "live", "group": "live", "pose": {}}, "shaders")
order = [x[0]["id"] for x in harness.order([(lab, "lab"), exp])]
expect(order == ["exp", "lab-x"], "a lab scene runs after the live scene it comes after (cheap tiers do not jump it)")
order = [x[0]["id"] for x in harness.order([(lab, "lab"), scene("z")])]
expect(order[0] == "lab-x", "an `after` scene not in the run does not hold the lab back")
expect(not harness.ready(lab, {"exp", "lab-x"}) and harness.ready(lab, {"lab-x"}), "ready: waits only for pending deps")
real = harness.catalog.load
harness.catalog.load = lambda: [lab, exp[0]]
try:
    got = [s["id"] for s in harness.with_deps([lab])]
finally:
    harness.catalog.load = real
expect(got == ["lab-x", "exp"], "a lab scene whose exports are missing pulls its `after` scene into the run")

# --- lab reports are kept and summarised ------------------------------------------------------------------------
summ = harness.lab_summary({"runs": [{"dist": 10, "variant": "groups", "iou": 1.0, "dE": 0.1, "steps": 20},
                                     {"dist": 600, "variant": "groups", "iou": 0.97, "dE": 3.0, "steps": 4}]})
expect(summ == {"groups": {"iouMin": 0.97, "dEMax": 3.0, "steps": 12.0}}, "atlas_lab runs summarise per representation")
expect(harness.lab_summary({"runs": [{"variant": "boxes", "pop": 0.0, "pop_max": 0.1, "dE": 1.3, "steps": 5.3}]})
       == {"boxes": {"pop": 0.0, "popMax": 0.1, "dE": 1.3, "steps": 5.3}}, "a sweep's pop numbers are kept")
expect(harness.lab_summary({"runs": [{"dist": 60, "variant": "groups", "motion_dE": 2.0, "flicker": 2.1, "steps": 6},
                                     {"dist": 600, "variant": "groups", "motion_dE": 6.5, "flicker": 3.2, "steps": 2}]})
       == {"groups": {"motionDEMax": 6.5, "flickerMax": 3.2, "steps": 4.0}}, "a moving-camera run keeps its worst distance")
expect(harness.lab_summary({"runs": [{"variant": "ref", "ms": 65}]}) == {"ref": {}}, "a camera frame summarises to nothing")

expect(harness.pack_opt_args({"TAA": False, "FOG_LAYERS": 3, "SSR": True}) == ["opt=FOG_LAYERS=3", "opt=SSR", "opt=!TAA"],
       "a scene's pack options reach pack_lab as the same switches the live run sets")

# --- every scene declares its weather ---------------------------------------------------------------------------
import catalog  # noqa: E402

def settled(sc):
    sc = dict(sc, tier="live")
    catalog.settle_weather(sc)
    return sc.get("weather")


expect(settled({"weather": "clear", "tags": []}) == "off", "a world scene with 'clear' shows the world with weather off")
expect(settled({"tags": []}) == "off", "a world scene with no weather gets weather off")
expect(settled({"weather": "rain", "tags": []}) == "rain", "an explicit weather is kept")
expect(settled({"weather": "clear", "tags": ["suite:weather-audit"]}) == "clear", "a weather-tagged clear sky keeps its clouds")
expect(settled({"tags": [], "pose": {"dungeon": "x"}}) is None, "indoors has no weather")

lines = catalog.capture_actions({"id": "x", "settle": "static", "pose": {}, "setup": ["settle-visual 600"], "wait": 10},
                                "t")
expect(all(l.endswith(" static") for l in lines if l.startswith("settle-visual ")) and any(l.startswith("settle-visual") for l in lines),
       "a static scene settles every time with the world frozen (harness lines and its own)")

real_latest = harness.results.latest
harness.results.latest = lambda scene, v: {"status": "error", "error": "E-SETTLE-TIMEOUT", "game": {"eye": [0, 0, 0]}}
try:
    cam, why = harness.camera_from({"scene": "no-such-scene", "variant": "vanilla"})
finally:
    harness.results.latest = real_latest
expect(cam is None and "did not succeed" in why, "camera_from refuses a live capture whose last run failed (no stale screenshot)")

# --- a busy client whose progress stops fails fast; one that advances never does ---------------------------------
w = live_world.ProgressWatch(0.0)
expect(w.stalled_for("a|3|0", 0.0) == 0 and w.stalled_for("a|3|0", 100.0) == 100.0 and w.stalled_for("a|3|7", 120.0) == 0,
       "progress that changes (an export's chunk count) resets the stall clock; unchanged progress counts up")
expect(live_world.ProgressWatch(0.0).stalled_for(None, 500.0) == 0, "an older client without progress is never 'stalled'")
expect(live_world.STALL_S <= 180, "a silent hang fails within 3 minutes, not at the step's long deadline")

# --- a lab scene matching a live capture is a new result when that capture's run changes (even a failed run) -------
r = harness.Runner.__new__(harness.Runner)
r._inputs, r.seed = None, 0
cam_scene = {"id": "x", "tier": "lab", "lab": {"tool": "atlas_lab"}, "camera_from": {"scene": "s", "variant": "vanilla"}}
real_latest = harness.results.latest
try:
    harness.results.latest = lambda scene, v: {"status": "pass", "capturedAt": "t1"}
    a = r.inputs(cam_scene, "lab")
    harness.results.latest = lambda scene, v: {"status": "error", "capturedAt": "t2"}
    b = r.inputs(cam_scene, "lab")
finally:
    harness.results.latest = real_latest
expect(a.get("live") != b.get("live"), "a failed live capture changes the lab's fingerprint (no reused old pass)")

# --- a native change reaches the game: stale sources are detected against the built core --------------------------
import tempfile, os as _os, time as _time
import game as _game  # noqa: E402
with tempfile.TemporaryDirectory() as td:
    src, built = Path(td) / "src", Path(td) / "atlas_core.dll"
    src.mkdir()
    (src / "a.cpp").write_text("x")
    built.write_text("dll")
    _os.utime(built, (_time.time() - 100, _time.time() - 100))
    expect(_game.native_stale(built, [src]), "native sources newer than the built core: rebuild before tests")
    _os.utime(built, (_time.time() + 100, _time.time() + 100))
    expect(not _game.native_stale(built, [src]), "a core built after its sources is current")
    _os.utime(built, (_time.time() - 100, _time.time() - 100))
    built.with_suffix(".sources").write_text("abc")
    expect(not _game.native_stale(built, [src], digest="abc"),
           "a core whose build stamp names today's sources is current, however old its file time (no relink)")
    expect(_game.native_stale(built, [src], digest="abd"), "a stamp from other sources does not excuse an old core")

# --- a failed scene's recovery never runs its exports (a long one blocked the next scene) --------------------------
cl = _game.tolerant(["command kill @e", "atlaslab view x-view 1024 light", "fog-off off"])
expect(cl == ["try kill @e", "fog-off off"], "recovery keeps the cleanup's undo steps and drops its exports")

lines = catalog.pose_actions({"id": "d", "pose": {"dungeon": "bleak_falls_barrow", "view": "mouth"}, "tags": []})
expect(any("execute as @p" in l and "nirncraft-dungeon-qa view" in l for l in lines),
       "a dungeon pose runs its view command as the player (it needs one to move)")

class _A: pass
oa = _A(); oa.variant = None; oa.offline = True
live_scene = {"id": "L", "tier": "live", "variants": ["vanilla"]}
lab_scene = {"id": "B", "tier": "lab", "after": ["L"], "camera_from": {"scene": "L"}, "lab": {"tool": "atlas_truth"}}
got = [s["id"] for s, _ in harness.items_of([live_scene, lab_scene], oa)]
expect(got == ["B"], "--offline leaves live scenes out and never pulls one in as another scene's inputs")

pp = review.parse_pose("\n".join(["VERDICT: WEAK", "SEES: a wall", "PROBLEMS: too close", "FIX: move back 40 blocks"]))
expect(pp["verdict"] == "WEAK" and pp["fix"] == "move back 40 blocks", "the pose review's answer is read field by field")
expect(review.parse_pose("I could not open the image.")["verdict"] is None, "no verdict line: unvetted, never a pass")

# --- a check can say it cannot judge a case (an unlit element): a skip, never a pass or a fail --------------------
expect(harness.check_skipped(harness.CHECK_SKIP_EXIT) and not harness.check_skipped(0) and not harness.check_skipped(1),
       "a check's skip exit is recorded as a skip")
import re as _re
import elements as _elements
_line = json.dumps({"worst": {"dist": 32, "dE": 3.7, "iou": 0.912}, "fails": []})
_els = _elements.scenes()  # none on a machine without exports: nothing to read then
if _els:
    _m = _re.search(_els[0]["metrics"]["worst_iou"], _line)
    expect(bool(_m) and _m.group(1) == "0.912", "the elements suite reads the worst IoU")

# --- an element is underground by its faces' sky light, not its brightness (a night tree is dark, under the sky) ----
import struct as _struct
import numpy as _np
import element_lab as _el
with tempfile.TemporaryDirectory() as _td:
    _ids = _np.ones((3, 3, 3), dtype="<u4")  # (y, z, x): stone, the top layer air
    _ids[2] = 0
    _saved = (_el.read_alv, _el.block_names, _el.export_dir)
    _el.read_alv = lambda p: ((0, 0, 0), _ids)
    _el.block_names = lambda: {0: "Block{minecraft:air}", 1: "Block{minecraft:stone}"}
    _el.export_dir = lambda: Path(_td)
    def _alc(sky, extra_edge=False):
        cards = [(1, 1, 1, 1, sky)] + ([(1, 2, 1, 1, 240)] if extra_edge else [])  # up faces; the second faces y 3
        out = b"ALC1" + _struct.pack("<i", len(cards))
        for x, y, z, f, s in cards:
            out += _struct.pack("<4i", x, y, z, f) + _struct.pack("<256I", *([0xFF808080] * 256)) + bytes([s] * 256) + bytes(256)
        path = Path(_td) / f"c{sky}{extra_edge}.alc"
        path.write_bytes(out)
        return path
    _box = {"volume": "v", "box": [0, 0, 0, 2, 1, 2]}
    expect(_el.outside_light(_box, _alc(0)) < _el.DARK_LIGHT, "a face with no sky light in front of it: underground")
    expect(_el.outside_light(_box, _alc(240)) == 15.0, "full sky light (day or night alike): not underground")
    expect(_el.outside_light(_box, _alc(0, True)) < _el.DARK_LIGHT,
           "a face looking beyond the export (the reference's open-sky guess) does not count")
    _el.read_alv, _el.block_names, _el.export_dir = _saved

# --- a renderer that stops following the camera is caught, not scored as a perfect fly-in -------------------------
from PIL import Image as _Image
with tempfile.TemporaryDirectory() as _td:
    _tf, _of = [], []
    for _i in range(5):
        _a = _np.zeros((8, 8, 3), dtype=_np.uint8)
        _a[:, :_i + 1] = 200  # the reference: the element grows as the camera nears
        _tp, _op = Path(_td) / f"t{_i}.png", Path(_td) / f"o{_i}.png"
        _Image.fromarray(_a).save(_tp)
        _Image.fromarray(_np.zeros((8, 8, 3), dtype=_np.uint8)).save(_op)  # ours: the same empty frame every step
        _tf.append((_tp, None)), _of.append((_op, None))
    _p = _el.pop("x", _tf, _of)
    expect(_p["pop"] == 0.0 and _p["frozen"] == 4, "a frozen renderer has no pop and is counted frozen (a fail)")

# --- the bench's frame is scored only when atlas_lab rendered it with the representation asked for ---------------
_log = ("atlas_lab: camera frame ref 160x160 in 64 ms -> C:/t/a.png\n"
        "atlas_lab: camera frame groups 160x160 in 26 ms -> C:/t/a_groups.png\n")
expect(not _el.wrote_variant(_log, Path("C:/t/a.png"), "groups"), "ref at out= is not the groups frame (it was scored as one)")
expect(_el.wrote_variant(_log, Path("C:/t/a_groups.png"), "groups"), "the groups frame is found by its path")

# --- truth subjects offset from their place look back at it (five faced away: yaw 0 is south in Minecraft) -----------
import math as _math
import truth as _truth
for _sub in _truth.SUBJECTS:
    _pose = _sub[2]
    _ox, _oz = (_pose.get("offset") or [0, 0])[:2]
    if (_ox or _oz) and _sub[0] not in _truth.LOOKS_AWAY:
        _y = _math.radians(_pose.get("yaw", 0))
        expect(-_math.sin(_y) * -_ox + _math.cos(_y) * -_oz > 0, f"truth subject {_sub[0]} faces its place")

print(f"problems {len(problems)}")
sys.exit(1 if problems else 0)
