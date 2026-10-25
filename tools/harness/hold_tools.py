"""Live tests of the player hold (docs/PLAYER-HOLD.md, docs/ATLAS-PLAN.md section 0.9), on the real client and the
integrated server, judged from the game's own log lines and heartbeat:

    harness.py run hold-returning        a returning player: held at login, released by the client's ready at the SAME place,
                                         the return to the last position only then; then a hold in the middle of a session
                                         (nirncraft-hold hold @p) is released again at the same place
    harness.py run hold-never-ready      a client that never reports ready (-Dnirncraft.hold.neverReady): still held after
                                         90 s, a husk beside the real player does nothing (Hold probe: PASS: no target, no
                                         damage, not sent to another player, not moved), and leaving restores the real state
    harness.py run hold-first-join       a new character (the opening kept: -Dnirncraft.autotest.intro): the character select
                                         starts only after the release, never while held

The harness's own autotest skips the character screen (ClientAutotest.automated), so the first-join test runs with the
opening kept. The server-side rules (targeting, damage, tracking, pin, release) are also checked headless on stand-in players
by `nirncraft-hold selftest` (tools/qa/qa.ps1).
"""
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = {"returning": "hold-returning", "never": "hold-never-ready", "first": "hold-first-join"}
POS = r"(-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?)"


def scenes():
    common = {"tier": "live", "variants": ["hold"], "kind": "hold", "nocache": True, "settle": False, "checks": {},
              "weather": "off", "capture": "world", "time": 6000, "seed": None, "_file": "(hold_tools.py)",
              "tags": ["suite:hold", "feature:hold"], "pose": {"visit": [-150, 260, 30, -90.0, 0]}, "timeout": 900}
    return [
        {**common, "id": "hold-returning", "group": "hold", "hold_tool": "returning",
         "title": "A returning player is held at login and released by the client's ready at the same place; a mid-session hold likewise"},
        {**common, "id": "hold-never-ready", "group": "hold-never", "hold_tool": "never",
         "title": "A client that never reports ready stays held: a mob beside the player does nothing, nobody else sees them"},
        {**common, "id": "hold-first-join", "group": "hold-intro", "hold_tool": "first",
         "title": "First join: the character select starts only after the client is ready (opening kept)"},
    ]


def run(runner, s, v, fp):
    import results
    fn = {"returning": returning, "never": never_ready, "first": first_join}[s["hold_tool"]]
    try:
        fails, metrics = fn(runner, s)
    except RuntimeError as e:
        fails, metrics = [str(e)[:400]], {}
    for f in fails:
        print(f"  FAIL {s['id']}: {f}", flush=True)
    if fails:  # the hold's own log lines of this client, for the diagnosis
        try:
            tail = [l for l in fresh_log(runner.game).splitlines() if re.search(r"hold|Hold|atlas loading|Autotest live|RaceScreen|Intro", l)][-25:]
            print("  log: " + chr(10).join("       " + x[:230] for x in tail).lstrip(), flush=True)
        except OSError:
            pass
    return results.record(s["id"], v, "hold", "fail" if fails else "pass", metrics, fp, extra={"fails": fails})


# ---------------------------------------------------------------------------------------------------------------- helpers
def fresh_log(g):
    return g.LOG_FILE.read_text(encoding="utf-8", errors="replace") if g.LOG_FILE.exists() else ""


def launch_nowait(runner, group):
    """Starts the client for `group` without waiting for the world to be ready (a held client never becomes ready)."""
    g = runner.game
    g.ensure_current()  # the current build installed (once per run), as ensure() does
    if g.running():
        safe_close(g)
        time.sleep(3)
    g._iris_enable()
    cfg = runner.groups.get(group, {})
    jvm = dict(cfg.get("jvm", {}))
    if "nirncraft.autotest.world" not in jvm and (group == "live" or cfg.get("keptWorld")):
        jvm.update({"nirncraft.autotest.world": g.kept_world(), "nirncraft.autotest.keepWorld": "true"})
    g._set_group_flags(jvm)
    g._enable_overlay()
    r = subprocess.run([sys.executable, str(ROOT / "tools/dev/live_world.py"), "launch"], capture_output=True, text=True)
    if r.returncode:
        raise RuntimeError("launch failed: " + (r.stdout + r.stderr)[-400:])
    g.STATE.parent.mkdir(parents=True, exist_ok=True)
    g.STATE.write_text('{"group": "%s", "since": %f}' % (group, time.time()), encoding="utf-8")


def client_status():
    import live_world as lw
    return (lw.status() or {}).get("client", {}) or {}


def wait_for(pattern, g, limit, samples=None):
    """Polls the game log (and the client heartbeat into `samples`) for a regex; returns its last match text or None."""
    t0 = time.monotonic()
    rx = re.compile(pattern)
    gone = 0
    while time.monotonic() - t0 < limit:
        txt = fresh_log(g)
        m = rx.search(txt)
        if samples is not None:
            c = client_status()
            samples.append((round(time.monotonic() - t0, 1), c.get("screen"), c.get("held")))
        if m:
            return m
        gone = 0 if g.running() else gone + 1  # (the window list can flicker: three misses in a row)
        if gone >= 3:
            raise RuntimeError("the client exited while waiting for: " + pattern)
        time.sleep(0.5)
    return None


def safe_close(g, limit=420):
    """Closes the client; a slow shutdown (a fresh world's server saving, the opening's cottage preparation on the server
    thread) is waited for, not a test failure: the checks have run by now."""
    try:
        g.close()
    except RuntimeError:
        t0 = time.monotonic()
        while g.running() and time.monotonic() - t0 < limit:
            time.sleep(3)
        if g.running():
            raise


def dist(a, b):
    return sum((float(x) - float(y)) ** 2 for x, y in zip(a, b)) ** 0.5


# ---------------------------------------------------------------------------------------------------------------- returning
def returning(runner, s):
    g = runner.game
    fails, m = [], {}
    launch_nowait(runner, s["group"])
    rel = wait_for(r"hold: (\S+) released after (\d+) ticks \((\d+) ms\) at " + POS, g, 600)
    if not rel:
        return ["the player was never released: the client never reported ready (or the hold never began)"], m
    txt = fresh_log(g)
    held = re.search(r"hold: (\S+) is held at " + POS, txt)
    if not held:
        fails.append("no 'is held' line: the player was not held at login")
        return fails, m
    p0, p1 = held.groups()[1:4], rel.groups()[3:6]
    m.update({"heldTicks": int(rel.group(2)), "heldMs": int(rel.group(3)), "driftBlocks": round(dist(p0, p1), 2)})
    if dist(p0, p1) > 0.5:
        fails.append(f"released {dist(p0, p1):.1f} blocks from where they were held: {p0} -> {p1}")
    if "atlas loading phase: ready after" not in txt and "hold: the server holds" not in txt:
        fails.append("the client logged neither its ready nor the server's hold")
    if txt.find("released after") > txt.find("is in play"):
        fails.append("the return to the last position / opening ran BEFORE the release")
    play = re.search(r"hold: \S+ is in play at .*: (.*)", txt)
    if not play:
        fails.append("the release action (return to last position / first join) never ran")
    else:
        m["inPlay"] = play.group(1)[:60]
    if "Autotest live: ready" not in txt:
        wait_for(r"Autotest live: ready", g, 60)
    import atlas_tools
    atlas_tools.wait_alive(g)  # atlas's first frames compile on the render thread: the heartbeat can be stale for a while
    # a hold in the middle of a session: released again, at the same place
    n0 = len(re.findall(r"hold: \S+ released after", fresh_log(g)))
    g.actions(["ui close", "command nirncraft-hold hold @p"], timeout=120)
    t0 = time.monotonic()
    while time.monotonic() - t0 < 180:
        t = fresh_log(g)
        rels = re.findall(r"hold: \S+ released after \d+ ticks \(\d+ ms\) at " + POS, t)
        if len(rels) > n0:
            break
        time.sleep(1)
    else:
        fails.append("the mid-session hold was never released")
        return fails, m
    t = fresh_log(g)
    holds = re.findall(r"hold: \S+ is held at " + POS, t)
    d = dist(holds[-1], rels[n0])
    m["midSessionDriftBlocks"] = round(d, 2)
    if d > 0.5:
        fails.append(f"the mid-session hold released {d:.1f} blocks from where it held")
    # the server-side rules on stand-in players, through this (integrated) server: hidden, untargetable, no damage, pinned ...
    g.actions(["try nirncraft-hold selftest"], timeout=180)
    sm = wait_for(r"Hold selftest: (\d+) problems", g, 120)
    if not sm:
        fails.append("the hold selftest printed no result")
    else:
        m["selftestProblems"] = int(sm.group(1))
        if int(sm.group(1)):
            fails += re.findall(r"Hold selftest problem: (.*)", fresh_log(g))[:6] or ["the hold selftest reported problems"]
    safe_close(g)
    return fails, m


# ---------------------------------------------------------------------------------------------------------------- never
def never_ready(runner, s):
    g = runner.game
    fails, m = [], {}
    launch_nowait(runner, s["group"])
    samples = []
    if not wait_for(r"hold: \S+ is held at", g, 600, samples):
        return ["the player was never held"], m
    probe = wait_for(r"Hold probe: (PASS|FAIL)[^\n]*", g, 120, samples)
    if not probe:
        fails.append("the hold probe never judged (the server did not tick the held player for 240 ticks?)")
    elif probe.group(1) != "PASS":
        fails.append(probe.group(0)[:300])
    else:
        m["probe"] = probe.group(0)[:160]
    wait_for(r"is still held \(\d+ s\)", g, 100, samples)  # a client that never reports stays held (logged every 30 s)
    time.sleep(10)
    txt = fresh_log(g)
    c = client_status()
    m["screen"], m["held"] = c.get("screen"), c.get("held")
    if "released after" in txt:
        fails.append("the player was released although the client never reported ready")
    if c.get("held") is not True:
        fails.append(f"the client does not think it is held (held={c.get('held')})")
    if c.get("screen") != "AtlasLoadingScreen":
        fails.append(f"the client shows {c.get('screen')}, not the hold's loading screen")
    if "still held" not in txt:
        fails.append("the server never logged 'still held'")
    if "Autotest live: ready" in txt:
        fails.append("the autotest began its setup while the player was held")
    safe_close(g)
    time.sleep(3)
    txt = fresh_log(g)
    if "left while held" not in txt or "real state was restored" not in txt:
        fails.append("leaving while held did not restore the player's real state (no 'left while held ... restored' line)")
    return fails, m


# ---------------------------------------------------------------------------------------------------------------- first join
def first_join(runner, s):
    g = runner.game
    fails, m = [], {}
    launch_nowait(runner, s["group"])
    samples = []
    play = wait_for(r"hold: \S+ is in play at [^\n]*first join", g, 900, samples)
    if not play:
        return ["the first-join placement / character select never started (never released?)"], m
    end = time.monotonic() + 15  # the character screen opens on the client a moment after the server's state
    while time.monotonic() < end:
        c = client_status()
        samples.append((round(time.monotonic(), 1), c.get("screen"), c.get("held")))
        time.sleep(0.5)
    txt = fresh_log(g)
    held_i, rel_i, play_i = txt.find("is held at"), txt.find("released after"), txt.find("is in play at")
    m["order"] = [held_i, rel_i, play_i]
    if not (0 <= held_i < rel_i < play_i):
        fails.append(f"the log order is not held < released < in play: {held_i} {rel_i} {play_i}")
    bad = [x for x in samples if x[1] == "RaceScreen" and x[2]]
    if bad:
        fails.append(f"the character select was shown while the player was held: {bad[:3]}")
    seen = [x for x in samples if x[1] == "RaceScreen" and not x[2]]
    m["raceScreenSamplesAfterRelease"] = len(seen)
    if not seen:
        fails.append("the character select never appeared after the release")
    safe_close(g)
    return fails, m
