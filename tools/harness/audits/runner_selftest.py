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

print(f"problems {len(problems)}")
sys.exit(1 if problems else 0)
