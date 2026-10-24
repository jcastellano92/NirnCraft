#!/usr/bin/env python3
"""NirnCraft test harness: one entry point for every visual and non-visual test, for people and agents.

    py -3 tools/harness/harness.py list    [selection]              what exists (counts; --ids for the ids)
    py -3 tools/harness/harness.py plan    [selection]              what would run, in order, and what is cached
    py -3 tools/harness/harness.py run     [selection] [--force] [--keep] [--rebuild]
    py -3 tools/harness/harness.py watch   [selection] [--rebuild]  live reload loop: edit, see before|after in seconds
    py -3 tools/harness/harness.py serve                            stay up as the owner and run queued jobs
    py -3 tools/harness/harness.py status | report [selection] | cameras [selection] | export-tour NAME [selection]
    py -3 tools/harness/harness.py features [--seed N] [--force]        the generated-feature index (feature_scenes.py)
    py -3 tools/harness/harness.py seed-legacy | clean

selection: [ID-or-glob ...] [--suite smoke,weather] [--tag region:skyrim/whiterun --tag weather:storm,weather:rain]
           [--exclude tag] [--tier live|lab|check] [--group G] [--variant shaders|vanilla] [--changed [REF]] [--limit N]
           (no selection = --suite smoke). Tags match by prefix: region:skyrim matches region:skyrim/falkreath.

Tiers, fastest first: check (no rendering), lab (Infinite ATLAS offline, no Minecraft), live (the one preview client,
NirnCraft-26.3-autotest; hot reloads keep it open). One process owns the client at a time: another `run` queues its
job and the owner merges it into the work in progress (shared scenes run once, fresh cached results return at once).
Results land in .work/harness (not git): every test's after/before record, images for visual ones, and the latest
run's report (report.html, sheet*.png, combos.png). See tools/harness/README.md.
"""
import argparse
import ctypes
import fnmatch
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import cameras  # noqa: E402
import features  # noqa: E402
import trace as tr  # noqa: E402
import catalog  # noqa: E402
import posecheck
import results  # noqa: E402

STORE = results.STORE
QUEUE = STORE / "queue"
JOBS = STORE / "jobs"
OWNER = STORE / "owner.json"
IMPACT = HERE / "impact.json"
DEFAULT_SEED = int(os.environ.get("NIRNCRAFT_HARNESS_SEED", "1234"))  # the preview world's seed (ClientAutotest)
TIER_RANK = {"check": 0, "lab": 1, "live": 2}
VERBOSE = False


def say(msg):
    print(msg, flush=True)


def alive(pid):
    if not pid:
        return False
    if os.name == "nt":  # never os.kill on Windows: signal 0 there is TerminateProcess
        h = ctypes.windll.kernel32.OpenProcess(0x1000, False, int(pid))
        if not h:
            return False
        code = ctypes.c_ulong()
        ok = ctypes.windll.kernel32.GetExitCodeProcess(h, ctypes.byref(code))
        ctypes.windll.kernel32.CloseHandle(h)
        return bool(ok) and code.value == 259
    try:
        os.kill(int(pid), 0)
        return True
    except OSError:
        return False


# ================================================================================================ selection
CHECK_SKIP_EXIT = 3  # a check command's "I cannot judge this case" (its last line says why), e.g. an unlit element


def check_skipped(returncode):
    return returncode == CHECK_SKIP_EXIT


def tag_match(q, tags):
    q = q.lower()
    return any(t.lower() == q or t.lower().startswith(q + "/") or t.lower().startswith(q + ":") for t in tags)


def changed_files(ref):
    out = subprocess.run(["git", "diff", "--name-only", ref], cwd=ROOT, capture_output=True, text=True).stdout.split()
    out += subprocess.run(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, capture_output=True,
                          text=True).stdout.split()
    return sorted(set(out))


def impact(paths):
    """Changed files -> (tags to test, reload kinds to apply), from tools/harness/impact.json."""
    rules = json.loads(IMPACT.read_text(encoding="utf-8"))["rules"]
    tags, reloads = set(), []
    for p in paths:
        for r in rules:
            if any(fnmatch.fnmatch(p, g) for g in r["paths"]):
                tags.update(r.get("tags", []))
                if r.get("reload") and r["reload"] not in reloads:
                    reloads.append(r["reload"])
                if r.get("final", True):
                    break
    return sorted(tags), reloads


def select(scenes, a):
    specified = a.ids or a.suite or a.tag or a.tier or a.group or a.changed is not None
    suites = (a.suite or ("" if specified else "smoke")).split(",") if (a.suite or not specified) else []
    change_tags = None
    if a.changed is not None:
        change_tags, a.reloads = impact(changed_files(a.changed or "HEAD"))
        if not change_tags:
            return []
    # ids alone pick exactly those tests; ids next to a suite / tag / tier filter are added to what it selects
    filters = bool(a.suite or a.tag or a.tier or a.group or a.changed is not None)
    named = [s for s in scenes if a.ids and any(fnmatch.fnmatch(s["id"], p) for p in a.ids)]
    if a.ids and not filters:
        return named[: a.limit] if a.limit else named
    out = []
    for s in scenes:
        tags = s["tags"]
        if suites and not any(tag_match("suite:" + x, tags) for x in suites if x):
            continue
        if any(not any(tag_match(x, tags) for x in q.split(",")) for q in (a.tag or [])):
            continue
        if a.exclude and any(tag_match(x, tags) for x in a.exclude.split(",")):
            continue
        if a.tier and s["tier"] not in a.tier.split(","):
            continue
        if a.group and s["group"] not in a.group.split(","):
            continue
        if change_tags is not None and not any(tag_match(x, tags) for x in change_tags):
            continue
        out.append(s)
    seen = {s["id"] for s in out}
    out += [s for s in named if s["id"] not in seen]
    if a.limit:
        out = out[: a.limit]
    return out


def with_deps(scenes):
    """A scene's `after` scenes join the selection while what they make is missing (a lab scene's exported inputs):
    a lab never skips for want of an export the same run could have made."""
    ids = {s["id"] for s in scenes}
    def missing(s):
        if any(not f.exists() for f in lab_files(s.get("lab", {}))):
            return True
        return bool(s.get("camera_from")) and camera_from(s["camera_from"])[1] != ""
    need = [d for s in scenes if s["tier"] == "lab" and missing(s) for d in s.get("after", []) if d not in ids]
    if not need:
        return scenes
    by_id = {s["id"]: s for s in catalog.load()}
    return list(scenes) + [by_id[d] for d in dict.fromkeys(need) if d in by_id]


def ready(s, pending):
    """Every scene it comes `after` has run (or is not in this run)."""
    return not any(d in pending and d != s["id"] for d in s.get("after", []))


def items_of(scenes, a):
    out = []
    offline = getattr(a, "offline", False)
    for s in (scenes if offline else with_deps(scenes)):
        if offline and s["tier"] == "live":  # --offline: what needs the game waits for a run that allows it
            continue
        for v in s["variants"] if s["tier"] == "live" else [s["tier"]]:  # measure scenes: variant "measure"
            if a.variant and v not in a.variant.split(","):
                continue
            out.append((s, v))
    return out


def key(s, v):
    return f"{s['id']}|{v}"


# ================================================================================================ ordering
def options_key(opts):
    """A scene's pack options as one comparable string ('' = the pack's defaults); game.options_key's twin (game is
    imported only when something live runs)."""
    if not opts:
        return ""
    return ";".join(f"{k}={str(v).lower() if isinstance(v, bool) else v}" for k, v in sorted(opts.items()))


def priority(s, v, pos, group, shaders, opts=""):
    """Greedy next-item key: cheap tiers first; then stay in the running client's group (its JVM settings: a change is
    a restart), then in its shader state, then in its pack options (a change is a shader reload), then the nearest
    pose (chunks already loaded); realms last (they leave the overworld). Runtime settings (time, weather, weather-off)
    are set per scene and cost nothing to change."""
    tier = TIER_RANK.get(s["tier"], 3)
    if s["tier"] != "live":
        return (tier, 0, 0, 0, 0, s["id"])
    xz = s.get("_xz")
    p = s.get("pose") or {}
    dist = 0 if xz is None or pos is None else math.hypot(xz[0] - pos[0], xz[1] - pos[1])
    if xz is None and "spawn" not in p:
        dist = 1e7
    return (tier, s["group"] != group, s["group"] != "live", (v == "vanilla") == shaders,
            options_key(s.get("pack_options")) != opts, "realm" in p, dist, s["id"])


def order(items, pos=(0, 0), group="live", shaders=True, opts=""):
    left, out = list(items), []
    while left:
        pending = {x["id"] for x, _ in left}
        cands = [k for k in range(len(left)) if ready(left[k][0], pending)] or range(len(left))
        i = min(cands, key=lambda k: priority(*left[k], pos, group, shaders, opts))
        s, v = left.pop(i)
        out.append((s, v))
        if s["tier"] == "live":
            pos = s.get("_xz") or pos
            group, shaders, opts = s["group"], v != "vanilla", options_key(s.get("pack_options"))
    return out


# ================================================================================================ events, hooks, quarantine
EVENTS = STORE / "events.jsonl"
STOP = STORE / "stop.flag"
QUARANTINE = STORE / "quarantine.json"
QUARANTINE_AFTER = 3  # runs in a row a test errors / crashes (same fingerprint) before it is skipped by itself


def event(kind, **data):
    """Append to .work/harness/events.jsonl and run the hooks for this event: tools/harness/hooks.json (shared) and
    .work/harness/hooks.json (this machine): {"test_failed": ["cmd ..."], "*": [...]}; the event JSON is in
    HARNESS_EVENT. Events: test_failed, test_crashed, client_crashed, client_restarted, test_quarantined,
    group_skipped, run_finished."""
    rec = {"event": kind, "at": time.strftime("%Y-%m-%dT%H:%M:%S"), **data}
    STORE.mkdir(parents=True, exist_ok=True)
    if EVENTS.exists() and EVENTS.stat().st_size > 2_000_000:  # rotate: this file and one previous, like results
        EVENTS.replace(EVENTS.with_name("events.previous.jsonl"))
    with open(EVENTS, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")
    for hooks_file in (HERE / "hooks.json", STORE / "hooks.json"):
        try:
            hooks = json.loads(hooks_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for cmd in hooks.get(kind, []) + hooks.get("*", []):
            try:
                subprocess.run(cmd, shell=True, cwd=ROOT, timeout=60, env=dict(os.environ, HARNESS_EVENT=json.dumps(rec)))
            except (OSError, subprocess.SubprocessError) as e:
                say(f"  ! hook failed ({cmd}): {e}")


def quarantine():
    try:
        return json.loads(QUARANTINE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def note_outcome(k, fp, status, reason=""):
    """Track errors / crashes per test across runs; QUARANTINE_AFTER in a row with an unchanged fingerprint quarantine
    it (content failures, status fail, are real results and never quarantine)."""
    q = quarantine()
    e = q.get(k, {})
    if status in ("error", "crash"):
        e = e if e.get("fingerprint") == fp else {}
        e.update(fingerprint=fp, count=e.get("count", 0) + 1, reason=(reason or "")[:300], last=time.time())
        if e["count"] >= QUARANTINE_AFTER and not e.get("quarantined"):
            e["quarantined"] = True
            e["since"] = time.time()
            event("test_quarantined", test=k, count=e["count"], reason=e["reason"])
            say(f"  ! quarantined {k} after {e['count']} failing runs: {e['reason'][:120]}")
        q[k] = e
    elif status in ("pass", "flag", "fail") and k in q:
        del q[k]
    else:
        return
    STORE.mkdir(parents=True, exist_ok=True)
    QUARANTINE.write_text(json.dumps(q, indent=1), encoding="utf-8")


def quarantined(k, fp):
    e = quarantine().get(k)
    return e if e and e.get("quarantined") and e.get("fingerprint") == fp else None


# ================================================================================================ runners
class Runner:
    def __init__(self, a):
        self.a = a
        self.seed = a.seed
        self.groups = catalog.groups()
        self.plan = None
        self.launched = False
        self._game = None
        self._inputs = None
        self.pos = None
        self.streak = ("", 0)      # (error kind, consecutive live failures with it)
        self.restarted = set()     # groups already restarted once for a failure streak
        self.disabled = {}         # group -> reason: its remaining live tests are skipped
        self.crashes = 0           # client crashes this run (3: the group stops)
        self.closes = 0            # normal closes (someone closed the window): relaunched, not counted as crashes

    @property
    def game(self):
        if self._game is None:
            import game  # imports live_world (instance checks); only when something live runs
            self._game = game
        return self._game

    def inputs(self, s, v):
        base = {"variant": v, "seed": self.seed}
        if s["tier"] == "live":
            if self._inputs is None:
                self._inputs = self.game.inputs()
            return {**base, **self._inputs}
        if s["tier"] == "lab":
            exe = ROOT / "native/build/Release" / f"{s['lab'].get('tool', 'atlas_render')}.exe"
            files = lab_files(s["lab"])  # exported inputs (atlas_lab): a new export is a new result
            live = ""
            if s.get("camera_from"):  # and a new live capture to match is too, or its run failing (its old picture
                cf = s["camera_from"]  # stays on disk: the file alone would let an old pass be reused)
                files = files + [Path(f"{results.stem(cf['scene'], cf.get('variant', 'vanilla'))}.after.png")]
                lr = results.latest(cf["scene"], cf.get("variant", "vanilla")) or {}
                live = f"{lr.get('status')}|{lr.get('capturedAt')}"
            return {**base, "exe": results_hash([exe, ROOT / "native/build/Release/atlas_core.dll"]),
                    "pack": tree_hash(ROOT / "shaderpack/InfiniteATLAS"),
                    **({"files": results_hash([f for f in files if f.exists()])} if files else {}),
                    **({"live": live} if live else {})}
        return {**base, "inputs": tree_hash(*[ROOT / g for g in s.get("inputs", [])])}

    def invalidate(self):
        self._inputs = None

    def fingerprint(self, s, v):
        return catalog.fingerprint(s, self.inputs(s, v))

    def cached(self, s, v, fp):
        if s.get("nocache"):  # a measurement (the atlas gates): every run measures again
            return None
        rec = results.latest(s["id"], v)
        return rec if reusable(rec, fp) else None

    def run(self, s, v, fp):
        """Any test, any failure: record why, clean up, move on. Live failures feed a circuit breaker: three in a row
        with the same error restart the client once; if that does not help, the group's remaining tests are skipped
        with the reason instead of each burning its timeout."""
        k = key(s, v)
        if s["tier"] == "live" and s["group"] in self.disabled:
            return results.record(s["id"], v, s["kind"], "skip", {}, fp, extra={"error": self.disabled[s["group"]]})
        asked = s["id"] in (getattr(self.a, "ids", None) or [])
        q = None if getattr(self.a, "include_quarantined", False) or asked else quarantined(k, fp)
        if q:
            return results.record(s["id"], v, s["kind"], "skip", {}, fp, extra={
                "error": f"quarantined after {q['count']} failing runs ({q['reason'][:120]}); "
                         f"run it by id or with --include-quarantined to try again"})
        tr.test(k)
        tr.step("test_start", tier=s["tier"], group=s.get("group"), fingerprint=fp)
        t0 = time.monotonic()
        mark = self.game.log_mark() if s["tier"] == "live" and self._game is not None and self.game.running() else None
        rec = self._run(s, v, fp)
        secs = round(time.monotonic() - t0, 1)
        self.identify(s, v, rec, mark)
        tr.step("test_end", status=rec.get("status"), seconds=secs, error=(rec.get("error") or "")[:300])
        if rec.get("status") not in ("skip",) and not rec.get("_cached"):
            note_duration(k, secs)
        if rec.get("status") in ("error", "crash"):  # what led up to it travels with the result
            rec["trace"] = tr.tail(25)
            Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1, default=str),
                                                                       encoding="utf-8")
        tr.test(None)
        if rec.get("status") in ("error", "crash", "fail"):
            event("test_crashed" if rec["status"] == "crash" else "test_failed", test=k, status=rec["status"],
                  reason=(rec.get("error") or "; ".join(rec.get("fails", [])))[:300])
        note_outcome(k, fp, rec.get("status"), rec.get("error", ""))
        if not rec.get("_cached"):
            tally(s, v, rec)
        return rec

    def _run(self, s, v, fp):
        try:
            if s["tier"] == "check":
                return self.run_check(s, v, fp)
            if s["tier"] == "lab":
                return self.run_lab(s, v, fp)
            return self.run_live_restarting(s, v, fp)
        except Exception as e:  # one broken test never stops the run
            msg = str(e)[-1500:]
            tr.error("test_exception", e)
            if s["tier"] == "live":
                self.trip(s, msg)
            return results.record(s["id"], v, s["kind"], "error", {}, fp, extra={"error": msg})

    def identify(self, s, v, rec, mark):
        """Every failure gets a stable id and a hint (errors.py); the game's own complaints during the test (a setup
        command that quietly did nothing) become flagged ids too, so a scene not built as designed never passes."""
        import errors
        text = rec.get("error") or "; ".join((rec.get("fails") or []) + (rec.get("metrics", {}).get("flags") or []))
        if rec.get("status") in ("error", "crash", "fail", "flag") and text:
            code, hint = errors.classify(text)
            rec["errorCode"], rec["errorHint"] = code, hint
            if rec.get("error") and not rec["error"].startswith("["):
                rec["error"] = f"[{code}] {rec['error']}"
        if mark is not None and self._game is not None:
            try:
                fb = errors.feedback(self.game.log_since(mark))
            except Exception:  # noqa: BLE001  the log is evidence, never a reason to fail the run
                fb = []
            if fb:
                rec["feedback"] = [{"code": c, "message": m, "count": n} for c, m, n in fb]
                if rec.get("status") == "pass":
                    rec["status"] = "flag"
                    m = rec.setdefault("metrics", {})
                    m["flags"] = (m.get("flags") or []) + [f"[{c}] setup: {msg} (x{n})" for c, msg, n in fb]
                    rec.setdefault("errorCode", fb[0][0])
        if rec.get("errorCode") or rec.get("feedback"):
            Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1, default=str),
                                                                       encoding="utf-8")

    def run_live_restarting(self, s, v, fp):
        """A client crash during a test: keep the evidence next to the result, relaunch, and run the same test again
        once (it goes back to its own location); a second crash records it as `crash` and the run moves on."""
        for attempt in (1, 2):
            started = time.time()
            try:
                rec = self.run_live(s, v, fp)
                self.streak = ("", 0)
                return rec
            except Exception as e:
                # a client that is still running but lost (disconnected, or its heartbeat stopped) is a crash too
                lost = any(w in str(e) for w in ("client disconnected", "client stopped responding"))
                if self._game is None or not (lost or self.game.crashed()):
                    raise
                if lost and self.game.running():
                    try:
                        self.game.close()
                    except RuntimeError as ce:
                        say(f"  ! could not close the lost client: {str(ce)[:120]}")
                if not lost and self.game.closed_normally(started) and attempt == 1 and self.closes < 5:
                    # closed by someone (the window's X), not a crash: relaunch and run this test again
                    self.closes += 1
                    self.game.STATE.unlink(missing_ok=True)
                    event("client_closed", test=key(s, v))
                    tr.step("client_closed_by_user")
                    say("  the test client was closed (not a crash): relaunching and running this test again")
                    continue
                self.crashes += 1
                tr.step("client_crashed", attempt=attempt)
                path, cause = self.game.crash_evidence(results.stem(s["id"], v).with_name(f"{v}.crash.txt"), started)
                if lost:  # the harness closed it: its own reason, not whatever error line the game log ended on
                    cause = f"closed by the harness: {str(e)[:200]}"
                event("client_crashed", test=key(s, v), attempt=attempt, cause=cause, evidence=str(path))
                say(f"  ! client crashed during {s['id']} ({cause[:120]}); evidence {path}")
                if self.crashes >= 3:
                    self.disabled[s["group"]] = f"skipped: {self.crashes} client crashes this run"
                    event("group_skipped", group=s["group"], reason=self.disabled[s["group"]])
                    return results.record(s["id"], v, s["kind"], "crash", {}, fp,
                                          extra={"error": cause, "crashLog": str(path)})
                if attempt == 2:
                    return results.record(s["id"], v, s["kind"], "crash", {}, fp,
                                          extra={"error": f"crashed twice: {cause}", "crashLog": str(path)})
                event("client_restarted", test=key(s, v))
                say("  restarting the client and trying the same test again")

    # failures a client restart can cure (the client's state); a bad pose, an unknown id or a failed command is the
    # scene's own problem and must not take the whole group down with it
    RESTARTABLE = ("E-SETTLE-TIMEOUT", "E-TIMEOUT", "E-STUCK-SCREEN", "E-HEARTBEAT", "E-POS-UNLOADED", "E-OTHER")

    def trip(self, s, msg):
        import errors
        if errors.classify(msg)[0] not in self.RESTARTABLE:
            return
        kind = re.sub(r"[0-9]+", "#", msg.split(";")[0])[:80]
        n = self.streak[1] + 1 if self.streak[0] == kind else 1
        self.streak = (kind, n)
        if n < 3:
            return
        g = s["group"]
        if g not in self.restarted:
            say(f"  ! 3 failures in a row ({kind}); restarting the client once")
            self.restarted.add(g)
            self.streak = ("", 0)
            try:
                self.game.close()
            except Exception as e:  # noqa: BLE001
                say(f"  ! close failed: {e}")
        else:
            self.disabled[g] = f"skipped: group {g} kept failing after a restart ({kind})"
            event("group_skipped", group=g, reason=self.disabled[g])
            say(f"  ! {self.disabled[g]}")

    # ---------------------------------------------------------------------------------------- check
    def run_check(self, s, v, fp):
        cmd = [c.replace("{core}", str(self.game.GAME / "mods/nirncraft-core.jar")) for c in s["run"]]
        t0 = time.monotonic()
        r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=s.get("timeout", 900))
        out = (r.stdout + r.stderr)
        tr.step("check_command", cmd=cmd, exit=r.returncode, seconds=round(time.monotonic() - t0, 1),
                tail=out.strip().splitlines()[-3:] if out.strip() else [])
        m = {"seconds": round(time.monotonic() - t0, 1), "exit": r.returncode}
        for name, rx in (s.get("metrics") or {}).items():
            hit = re.findall(rx, out)
            if hit:
                h = hit[-1] if isinstance(hit[-1], str) else hit[-1][0]
                try:
                    m[name] = float(h)
                except ValueError:
                    m[name] = h
        d = results.stem(s["id"], v).parent
        d.mkdir(parents=True, exist_ok=True)
        log_file = d / f"{v}.output.txt"
        if log_file.exists():  # like images: the newest output and the one before it
            log_file.replace(d / f"{v}.output.before.txt")
        log_file.write_text(out[-20000:], encoding="utf-8")
        if check_skipped(r.returncode):  # the check cannot judge this case (says why): neither a pass nor a fail
            return results.record(s["id"], v, "check", "skip", m, fp, extra={"error": out.strip().splitlines()[-1][:300] if out.strip() else ""})
        failed = r.returncode != 0
        if s.get("fail_regex"):  # tools that report problems as lines but exit 0 (e.g. "^ERR")
            hits = [l for l in out.splitlines() if re.search(s["fail_regex"], l)]
            if hits:
                failed = True
                m["problems"] = max(m.get("problems", 0) or 0, len(hits))
                out = out + "\n" + hits[0]  # the first offending line becomes the reason
        return results.record(s["id"], v, "check", "fail" if failed else "pass", m, fp,
                              extra={"error": out.strip().splitlines()[-1][:300] if failed and out.strip() else ""})

    # ---------------------------------------------------------------------------------------- lab
    def run_lab(self, s, v, fp):
        lab = dict(s["lab"])
        tool = lab.pop("tool", "atlas_render")
        exe = ROOT / "native/build/Release" / f"{tool}.exe"
        if not exe.exists():
            return results.record(s["id"], v, "visual", "skip", {}, fp, extra={"error": f"{exe.name} not built (native/build.ps1)"})
        gpu = str(lab.get("backend", "")).lower() in ("vulkan", "gpu") or str(lab.get("gpu", "0")) not in ("0", "")
        if gpu and gpu_busy() > 40:  # machine safety: GPU work only when the GPU is mostly idle
            return results.record(s["id"], v, "visual", "skip", {}, fp, extra={"error": "GPU busy"})
        files = lab_files(lab)
        missing = [f for f in files if not f.exists()]
        if missing:
            return results.record(s["id"], v, "visual", "skip", {}, fp,
                                  extra={"error": f"no {missing[0].name} yet: run atlaslab-export (the in-game export) first"})
        lab = {k: (str(lab_path(val)) if isinstance(val, str) and val.startswith("@game/") else val) for k, val in lab.items()}
        live_png, near_limit = None, None
        if s.get("camera_from"):  # a live capture's own camera: the offline render of exactly that view
            cam, why = camera_from(s["camera_from"])
            if why:
                return results.record(s["id"], v, "visual", "skip", {}, fp, extra={"error": why})
            live_png = cam.pop("_png")
            near_limit = cam.pop("_near", None)
            lab.update(cam)
        if tool != "atlas_lab":
            lab.setdefault("w", 960)
            lab.setdefault("h", 540)
        with tempfile.TemporaryDirectory() as tmp:
            png = Path(tmp) / "lab.png"
            lab = dict(lab)
            if s.get("weather") == "off":  # the live weather-off switch's offline twin: same scene, same sky
                lab.setdefault("weather", 0 if tool == "pack_lab" else "off")
            kv = [f"{k}={val}" for k, val in lab.items()]
            if tool == "pack_lab":  # the scene's pack options, as the live run sets them: one switch for both
                kv += pack_opt_args(s.get("pack_options"))
            cmd = [str(exe), str(png)] + kv if tool == "pack_lab" else [str(exe), f"out={png.as_posix()}"] + kv
            r = subprocess.run(cmd, cwd=ROOT / "native" if tool == "pack_lab" else ROOT, capture_output=True, text=True,
                               timeout=900,
                               creationflags=0x00004000 if os.name == "nt" else 0)
            tr.step("lab_command", cmd=cmd, exit=r.returncode)
            if r.returncode or not png.exists():
                raise RuntimeError(f"{tool} failed: " + (r.stdout + r.stderr)[-800:])
            report = next((json.loads(l) for l in reversed(r.stdout.strip().splitlines()) if l.startswith("{")), {})
            # the tool's report at the top of the record ("game" keeps it too: posecheck reads it there), and for
            # atlas_lab a per-representation summary (worst silhouette and colour, mean steps, worst pop) to read at
            # a glance and to check against
            twin = {}
            if live_png:  # offline next to online: live | offline | difference, measured where the exported blocks are
                d = results.stem(s["id"], v).parent
                d.mkdir(parents=True, exist_ok=True)
                twin = results.offline_vs_live(live_png, png, png.with_name(png.stem + "_mask.png"),
                                               Path(f"{results.stem(s['id'], v)}.twin.png"), near_limit)
            rec = results.store(s["id"], v, png, meta={"tool": tool, "args": kv, "atlas": report}, fingerprint=fp,
                                extra={"report": report, **({"labSummary": lab_summary(report)} if tool == "atlas_lab" else {}),
                                       **({"vsLive": twin} if twin else {})})
            if twin:
                rec["metrics"].update(twin)
                flags = rec["metrics"].setdefault("flags", [])
                if twin.get("liveFlat", 0) > 0.05 and not any(f.startswith("live view is flat") for f in flags):
                    flags.append(f"live view is flat (fog-coloured or empty) over {twin['liveFlat']:.1%} of the real "
                                 "blocks' textured parts: a fogged wall or a hole at the seam")
                if twin.get("liveMissing", 0) > 0.02 and not any(f.startswith("live view shows sky") for f in flags):
                    flags.append(
                        f"live view shows sky over {twin['liveMissing']:.1%} of the real blocks: the far field (or the "
                        "near field) did not draw there")
        return self.judge(s, v, rec)

    # ---------------------------------------------------------------------------------------- live
    def run_live(self, s, v, fp):
        g = self.game
        if s.get("seed") and int(s["seed"]) != self.seed:
            return results.record(s["id"], v, "visual", "skip", {}, fp,
                                  extra={"error": f"absolute pose made on seed {s['seed']}; this world is {self.seed}"})
        if not g.running():
            self.launched = True
        try:
            g.ensure(s["group"], self.groups)
        except Exception as e:  # noqa: BLE001  the client cannot start: skip the group, do not hang on it
            self.disabled[s["group"]] = f"skipped: the client did not start ({str(e)[:160]})"
            raise
        if s.get("atlas_tool"):  # the in-game gates for Infinite ATLAS (atlas_tools.py): they drive the client themselves
            import atlas_tools
            return atlas_tools.run(self, s, v, fp)
        g.pack_options(s.get("pack_options"))  # the scene's pack options (a reload only when they change)
        if s.get("kind") == "behaviour":
            return self.run_behaviour(s, v, fp)
        if s.get("measure_command"):
            return self.run_measure(s, v, fp)
        p = s.get("pose") or {}
        # next planned pose / higher (place poses too: a place pose inside a gate or a roof had no second try)
        tries = 3 if p.get("shot") or "visit" in p or "camera" in p or "place" in p else 1
        err = None
        for cand in range(tries):
            try:
                png, receipt, perf, popm = g.capture(s, v, catalog.pose_actions(s, self.plan, cand),
                                                     lambda tag: catalog.capture_actions(s, tag, self.plan))
                break
            except ValueError as e:  # no (more) planned cameras
                err = e
                break
            except RuntimeError as e:  # the camera refuses poses inside blocks: next planned pose / higher visit
                err = e
                if not g.running() or "intersects" not in str(e):
                    raise
        else:
            raise err
        if err is not None and "png" not in locals():
            raise err
        # a vetted pose the reviewer finds useless (inside a building, facing a wall) tries the next candidate before
        # the result is filed; its verdict goes on to judge() (one review a capture)
        vet = None
        if s.get("vet_pose") and s["tier"] == "live":
            import review
            subject = s.get("vet_pose") if isinstance(s.get("vet_pose"), str) else s.get("title", s["id"])
            while True:
                vet = review.pose(png, subject)
                if vet.get("verdict") != "USELESS" or cand + 1 >= tries:
                    break
                say(f"  {s['id']} [{v}]: pose useless ({str(vet.get('sees', ''))[:80]}); trying pose #{cand + 2}")
                tr.step("repose_useless", test=key(s, v), sees=vet.get("sees"))
                try:
                    png, receipt, perf, popm = g.capture(s, v, catalog.pose_actions(s, self.plan, cand + 1),
                                                         lambda tag: catalog.capture_actions(s, tag, self.plan))
                    cand += 1
                except (ValueError, RuntimeError):  # no further candidate, or it is inside blocks: keep the verdict
                    break
        heavy = catalog.deferred_exports(s)
        if heavy and (vet or {}).get("verdict") != "USELESS":  # the exports, once the pose is worth them
            g.actions(heavy, timeout=s.get("timeout", 1800))
        elif heavy:
            say(f"  {s['id']} [{v}]: no useful pose: its exports skipped")
        self.pos = s.get("_xz") or self.pos
        extra = {"camera": cand} if p.get("shot") or cand else {}
        if perf:
            extra["perf"] = {k: perf[k] for k in ("median", "p95", "p99", "stalls", "frames") if k in perf}
        # a framed scene: the game placed the camera from the subject's size; check it framed the right subject
        moving = any(k in (s.get("pose") or {}) for k in ("sweep", "ladder"))  # the camera moves on purpose
        staged = s.get("frame") or s.get("frameBox") or s.get("camera_verb") or s.get("capture") == "ui" or moving
        want = None if staged else posecheck.requested(catalog.pose_actions(s, self.plan, cand) + list(s.get("setup", [])))
        pflags, pm = posecheck.pose(want, receipt)
        if s.get("frame"):
            fr = (receipt or {}).get("frame") or {}
            pm.update(framedSubject=fr.get("subject"), framedBox=fr.get("box"), framedDistance=fr.get("distance"))
            if s.get("subject") and fr.get("subject") != s["subject"]:
                pflags.append(f"camera framed {fr.get('subject') or 'nothing'}, not {s['subject']}")
        if pflags:  # the camera is not where the scene put it (a bumped mouse, a pose that did not apply): pose again
            say(f"  {s['id']} [{v}]: {pflags[0]}; posing again and capturing once more")
            tr.step("repose", test=key(s, v), why=pflags)
            png, receipt, perf, popm = g.capture(s, v, catalog.pose_actions(s, self.plan, cand),
                                                 lambda tag: catalog.capture_actions(s, tag, self.plan))
            pflags, pm = posecheck.pose(want, receipt)
            pm["reposed"] = True
        rec = results.store(s["id"], v, png, meta=receipt, fingerprint=fp, extra=extra)
        rec["metrics"].update(pm)
        if vet is not None:
            rec["_poseReview"] = vet
        if s.get("calibrate"):  # projection calibration: markers projected vs found in the picture
            import calibrate
            cal = calibrate.check({**rec, "calibrate": s["calibrate"]}, Path(f"{results.stem(s['id'], v)}.after.png"))
            rec["metrics"]["calibration"] = cal
            if cal.get("error") or cal.get("max") is None or cal["max"] > calibrate.TOL_PX:
                rec["metrics"].setdefault("flags", []).append(
                    f"projection off: {cal.get('error') or cal}" + (f" (scale {cal['scale']}: field of view?)"
                                                                    if cal.get("scale") else ""))
        if s.get("annotate") == "roads":  # the road plan drawn onto a copy of the picture through its own camera
            try:
                import towns
                after = Path(f"{results.stem(s['id'], v)}.after.png")
                rec["metrics"]["roadsDrawn"] = towns.annotate({**rec, "town": s.get("town")}, after,
                                                              after.with_name(f"{v}.roads.png"))
            except Exception as e:  # noqa: BLE001  an overlay is a convenience, never a failure
                rec["metrics"]["roadsDrawn"] = f"overlay failed: {str(e)[:80]}"
        rec["metrics"]["flags"] = rec["metrics"].get("flags", []) + pflags
        if popm:  # ladder / sweep numbers, the game log of the test: they join the image's numbers and flags
            flags = popm.pop("popFlags", [])
            game_log = popm.pop("gameLog", [])
            rec["metrics"].update(popm)
            rec["metrics"]["flags"] = rec["metrics"].get("flags", []) + flags
            if game_log:
                lf = results.stem(s["id"], v).with_name(f"{v}.log.txt")
                if lf.exists():  # like images: this test's log and the one before it
                    lf.replace(lf.with_name(f"{v}.log.before.txt"))
                lf.write_text("\n".join(game_log) + "\n", encoding="utf-8")
                rec["gameLogFile"] = str(lf)
        if receipt.get("seed") not in (None, self.seed):
            rec["metrics"]["flags"].append(f"world seed {receipt.get('seed')} != {self.seed}")
        if "shaders" in receipt and bool(receipt["shaders"]) != (v != "vanilla"):  # the picture is not the variant
            rec["metrics"]["flags"].append(f"captured with shaders {'on' if receipt['shaders'] else 'off'}, "
                                           f"but this is the {v} variant")
            rec["status"] = "fail"
        self.confirm_online(s, rec)
        return self.judge(s, v, rec)

    def confirm_online(self, s, rec):
        """Generated-feature tests: does the real world have what the offline index predicted here? (the online
        feature index: final confirmation of the picture, and validation of the index itself)"""
        biome = next((t[6:] for t in s["tags"] if t.startswith("biome:")), None)
        mark = next((t[9:] for t in s["tags"] if t.startswith("landmark:")), None)
        p = (s.get("pose") or {}).get("visit")
        if not (biome or mark) or not p:
            return
        g = self.game
        out = g.GAME / "nirncraft-qa/sample.json"
        out.unlink(missing_ok=True)
        radius = 0 if biome else 64
        try:
            g.actions([f"try nirncraft-qa sample {int(p[0])} {int(p[1])} {radius} {max(1, radius)}"], timeout=120)
            data = json.loads(out.read_text(encoding="utf-8"))
        except (RuntimeError, OSError, ValueError) as e:
            rec["metrics"]["flags"].append(f"online confirmation failed: {str(e)[:80]}")
            return
        m = rec["metrics"]
        if biome:
            online = (data.get("points") or [{}])[0].get("biome", "?")
            m["onlineBiome"] = online
            m["biomeConfirmed"] = online.split(":")[-1] == biome
            if not m["biomeConfirmed"]:
                m["flags"].append(f"offline index said {biome}, the world has {online}")
        if mark:
            kinds = {l["kind"] for l in data.get("landmarks", [])}
            m["landmarkConfirmed"] = mark.lower() in kinds
            if not m["landmarkConfirmed"]:
                m["flags"].append(f"no {mark} within 64 blocks online (found: {', '.join(sorted(kinds)) or 'none'})")

    def run_behaviour(self, s, v, fp):
        """A behaviour scenario: every trial rebuilds the arena, spawns the actors, records them, and each expectation
        is judged on the recording; it passes when every expectation holds in at least its `rate` of the trials."""
        import behaviour
        g = self.game
        g.ensure_visible()
        sc = s["scenario"]
        g.actions(["ui close"] + catalog.pose_actions(s, self.plan) + ["settle-visual 900"], timeout=300)
        expect = sc.get("expect", [])
        n_trials = int(sc.get("trials", 5))
        hits = [0] * len(expect)
        trials = []
        keep = results.stem(s["id"], v).parent
        keep.mkdir(parents=True, exist_ok=True)
        for n in range(n_trials):
            name, lines = behaviour.trial_actions(sc, n)
            out = g.GAME / f"nirncraft-qa/record-{name}.json"
            out.unlink(missing_ok=True)
            g.actions(lines, timeout=int(sc.get("duration", 600)) // 20 + 180)
            if not out.exists():
                raise RuntimeError(f"trial {n}: the recorder wrote no {out.name}")
            rec = json.loads(out.read_text(encoding="utf-8"))
            (keep / f"{v}.trial{n}.json").write_text(json.dumps(rec), encoding="utf-8")
            row = []
            for i, e in enumerate(expect):
                ok, info = behaviour.judge(e, rec)
                hits[i] += 1 if ok else 0
                row.append({"expect": behaviour.describe(e), "ok": ok, "info": info})
            trials.append(row)
            say(f"    trial {n + 1}/{n_trials}: " + ", ".join(("ok " if r["ok"] else "NO ") + r["expect"] for r in row))
        metrics, fails = {"trials": n_trials}, []
        shots = [(f"b-{sc['id']}-t0-{w}", w) for w in ("start", "middle", "end")]
        found = [(g.SHOTS / f"{tag}.png", w) for tag, w in shots if (g.SHOTS / f"{tag}.png").exists()]
        for i, e in enumerate(expect):
            rate = hits[i] / max(1, n_trials)
            metrics[behaviour.describe(e)] = round(rate, 2)
            if rate < e.get("rate", 1.0):
                fails.append(f"{behaviour.describe(e)}: {hits[i]}/{n_trials} trials (needs {e.get('rate', 1.0):.0%})")
        status = "fail" if fails else "pass"
        if found:  # the first trial's start / middle / end as one strip: before / after across runs, sheets, review
            from PIL import Image
            strip = results.strip([Image.open(f) for f, _ in found], [w for _, w in found])
            tmp = g.SHOTS / f"b-{sc['id']}-strip.png"
            strip.save(tmp)
            rec = results.store(s["id"], v, tmp, fingerprint=fp, extra={"fails": fails, "trials": trials,
                                                                        "scenario": sc.get("id")})
            rec["metrics"].update(metrics)
            rec["status"] = status
            Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1, default=str),
                                                                       encoding="utf-8")
            return rec
        return results.record(s["id"], v, "behaviour", status, metrics, fp,
                              extra={"fails": fails, "trials": trials, "scenario": sc.get("id")})

    def run_measure(self, s, v, fp):
        """A live test without a picture: go there, settle, run a command that writes a JSON report, keep its numbers."""
        g = self.game
        g.ensure_visible()
        mark = g.log_mark()
        out = g.GAME / s["measure_file"]
        out.unlink(missing_ok=True)
        try:
            g.actions(["ui close"] + catalog.pose_actions(s, self.plan) + ([] if s.get("settle") is False else ["settle 1800"])
                      + list(s.get("setup", []))
                      + [s["measure_command"]] + list(s.get("cleanup", [])), timeout=s.get("timeout", g.DEFAULT_TIMEOUT))
        except RuntimeError:
            g.recover(s)
            raise
        t0 = time.monotonic()
        while not out.exists() and time.monotonic() - t0 < 60:
            time.sleep(0.5)
        if not out.exists():
            raise RuntimeError(f"{s['measure_command']} wrote no {s['measure_file']}")
        data = json.loads(out.read_text(encoding="utf-8"))
        if s.get("export"):  # the game's own index (e.g. entities.json): kept for the catalog and other tools
            (STORE / s["export"]).write_text(json.dumps(data, indent=1), encoding="utf-8")
        if s.get("validate") == "world":  # the offline tools' prediction for the same square, compared
            import validation
            req = {"id": s["id"], "x": data["x"], "z": data["z"], "radius": data["radius"], "step": data["step"]}
            pred = validation.offline([req], self.seed, log=say).get(s["id"], {})
            vm, vflags = validation.compare_world(data, pred)
            if data.get("seed") not in (None, self.seed):
                vflags.append(f"world seed {data.get('seed')} != {self.seed}")
            log_counts, serious = g.log_summary(g.log_since(mark))
            vm.update(log_counts)
            self.pos = s.get("_xz") or self.pos
            return results.record(s["id"], v, "measure", "fail" if vflags else "pass", vm, fp,
                                  extra={"fails": vflags})
        m = {name: json_sum(data, path) for name, path in (s.get("measure_sum") or {}).items()}
        log_counts, serious = g.log_summary(g.log_since(mark))
        m.update(log_counts)
        if "floating" in m and "control" in m:
            m["floating"] -= m["control"]  # the plain-terrain control is not the place's fault
            m["problems"] = m["floating"]
        c = s.get("checks") or {}
        fails = [f"{k} {m.get(k)} > {lim}" for k, lim in (c.get("max") or {}).items() if (m.get(k) or 0) > lim]
        self.pos = s.get("_xz") or self.pos
        status = "fail" if fails else ("flag" if m.get("problems") else "pass")
        img = g.GAME / s["image"] if s.get("image") else None
        for name, extra_img in (s.get("extra_images") or {}).items():  # more pictures the command drew
            src = g.GAME / extra_img
            if src.exists():
                keep = results.stem(s["id"], v).with_name(f"{v}.{name}.png")
                keep.parent.mkdir(parents=True, exist_ok=True)
                keep.write_bytes(src.read_bytes())
        if img is not None and img.exists() and s.get("autocrop"):  # a drawing on an empty canvas: crop, enlarge
            img = results.autocrop(img, img.with_name(img.stem + "_view.png"))
        if img is not None and img.exists():  # a picture the command drew (a town plan): kept like a capture
            small = data if len(json.dumps(data)) < 20000 else {k: data[k] for k in s.get("keep", []) if k in data}
            rec = results.store(s["id"], v, img, meta={"report": small},
                                fingerprint=fp, extra={"fails": fails})
            rec["metrics"].update(m)
            rec["status"] = status if status != "pass" else rec.get("status", "pass")
            Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1), encoding="utf-8")
            return rec
        return results.record(s["id"], v, "measure", status,
                              m, fp, extra={"fails": fails, "report": data if len(json.dumps(data)) < 20000 else {}})

    def judge(self, s, v, rec):
        m, c = rec["metrics"], s.get("checks") or {}
        twin = next((t[5:] for t in s.get("tags", []) if t.startswith("twin:")), None)
        if twin and rec.get("game"):  # the twin's camera against the lab camera, in world space
            lab_rec = results.latest(twin, "lab") or {}
            tf, tm = posecheck.twin(lab_rec.get("game"), rec["game"])
            m.update(tm)
            m["flags"] = m.get("flags", []) + tf
        before = Path(f"{results.stem(s['id'], v)}.before.json")
        if before.exists():  # only the same place, direction, time, weather, world and settings make a before/after
            try:
                why = posecheck.comparable(json.loads(before.read_text(encoding="utf-8")), rec)
            except ValueError:
                why = []
            if why:
                m["notComparable"] = why
                m["diff"] = m["changed"] = None  # nothing may be judged on a pair from two different views
        # a wrong shader state or a wrong camera: the picture is not the test, never a pass
        fails = [f for f in m.get("flags", []) if "but this is the" in f or f.startswith("camera ")
                 or f.startswith("live view shows sky")
                 or f.startswith("twin looks")]
        if "maxBlackTiles" in c and m.get("blackBoxes", 0) > c["maxBlackTiles"]:  # boxes: the NaN signature
            fails.append(f"black boxes {m['blackBoxes']} > {c['maxBlackTiles']}")
        if "lumaMin" in c and m.get("luma", 0) < c["lumaMin"]:
            fails.append(f"luma {m['luma']} < {c['lumaMin']}")
        if "lumaMax" in c and m.get("luma", 0) > c["lumaMax"]:
            fails.append(f"luma {m['luma']} > {c['lumaMax']}")
        lab_png = Path(f"{results.stem(twin, 'lab')}.after.png") if twin else None
        live_png = Path(f"{results.stem(s['id'], v)}.after.png")
        if twin and lab_png.exists() and live_png.exists():  # a twin is measured against its lab render, not eyeballed
            tm = results.twin_compare(lab_png, live_png, Path(f"{results.stem(s['id'], v)}.twin.png"))
            m.update(tm)
            if tm["twinStructure"] > 20 or tm["twinSkyline"] > 6:
                m.setdefault("flags", []).append(f"lab and live differ (structure {tm['twinStructure']}, skyline "
                                                 f"{tm['twinSkyline']}%, dE {tm['twinDE']}): not the same view or terrain")
        if s.get("vet_pose") and s["tier"] == "live" and live_png.exists():  # does the capture show what it is for
            import review
            pv = rec.pop("_poseReview", None) or review.pose(
                live_png, s.get("vet_pose") if isinstance(s.get("vet_pose"), str) else s.get("title", s["id"]))
            m["pose"] = pv.get("verdict") or "unvetted"
            if pv.get("verdict") == "USELESS":
                fails.append(f"pose useless (the reviewer sees: {pv.get('sees', '')[:90]}); fix: {pv.get('fix', '')[:120]}")
            elif pv.get("verdict") == "WEAK":
                m.setdefault("flags", []).append(f"pose weak: {pv.get('problems', '')[:80]}; fix: {pv.get('fix', '')[:120]}")
        status = "fail" if fails else ("flag" if m.get("flags") else "pass")
        rec.update(status=status, fails=fails)
        results.stem(s["id"], v).with_suffix("")
        Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1), encoding="utf-8")
        return rec

    def close(self, summary=""):
        """A run leaves nothing running: the client closes at the end (also after errors), whoever launched it; only
        --keep / watch / serve keep it for the next job. Whoever watches the game sees why it goes away."""
        if self._game is not None and not getattr(self.a, "keep", False) and self.game.running():
            try:
                sx, sy, sz = catalog.STAGE  # saved there, so the next boot loads at the stage, not the spawn cabin
                self.game.actions([f"try tp @p {sx} {sy + 2} {sz}",
                                   f"message Tests finished{': ' + summary if summary else ''}. Closing the test client."],
                                  timeout=30)
                time.sleep(4)
            except Exception:  # noqa: BLE001
                pass
            say("closing the test client")
            self.game.close()


def json_sum(data, path):
    """Sum the numbers at a dotted path; * matches every key (categories.*.floating)."""
    nodes = [data]
    for part in path.split("."):
        nxt = []
        for n in nodes:
            if isinstance(n, dict):
                nxt += list(n.values()) if part == "*" else ([n[part]] if part in n else [])
        nodes = nxt
    return sum(n for n in nodes if isinstance(n, (int, float)))


def results_hash(paths):
    import hashlib
    h = hashlib.sha256()
    for p in paths:
        try:
            st = Path(p).stat()
            h.update(f"{p}:{st.st_size}:{int(st.st_mtime)}".encode())
        except OSError:
            h.update(f"{p}:-".encode())
    return h.hexdigest()[:12]


def tree_hash(*roots):
    files = []
    for r in roots:
        if r.is_file():
            files.append(r)
        elif r.is_dir():
            files += sorted(f for f in r.rglob("*") if f.is_file())
    return results_hash(files)


def pack_opt_args(opts):
    """A scene's pack_options as pack_lab's opt= arguments (true: on, false: off, else a value)."""
    out = []
    for k, v in sorted((opts or {}).items()):
        out.append(f"opt={k}" if v is True else f"opt=!{k}" if v is False else f"opt={k}={v}")
    return out


def camera_from(ref):
    """A lab scene's `camera_from` {"scene", "variant"}: that live record's camera as atlas_lab arguments (Minecraft
    conventions) plus its screenshot ("_png"), or why not: no capture yet, or one taken before the view had settled
    (a ground truth must be the fully resolved picture)."""
    rec = results.latest(ref["scene"], ref.get("variant", "vanilla"))
    png = Path(f"{results.stem(ref['scene'], ref.get('variant', 'vanilla'))}.after.png")
    g = (rec or {}).get("game") or {}
    if rec and rec.get("status") not in ("pass", "flag"):  # its own run failed: the screenshot left there is an older one
        return None, (f"the live capture of {ref['scene']} [{ref.get('variant', 'vanilla')}] did not succeed in its "
                      f"last run ({rec.get('status')}: {str(rec.get('error', ''))[:80]}): capture it again")
    if not rec or not g.get("eye") or not png.exists():
        return None, f"no live capture of {ref['scene']} [{ref.get('variant', 'vanilla')}] yet: run it first"
    # the picture must be the record's own, not one left from an earlier run: the game's receipt says when it took it
    # (the record itself is written when the scene ends, minutes later when its cleanup is long)
    shot_at = g.get("capturedAt")
    if isinstance(shot_at, (int, float)) and abs(png.stat().st_mtime - shot_at / 1000.0) > 300:
        return None, f"the screenshot of {ref['scene']} is not the one its record describes: capture it again"
    if (rec.get("metrics") or {}).get("settlePartial") or (rec.get("settlePartial")):
        return None, (f"the live capture of {ref['scene']} was taken before the view settled (not fully resolved): "
                      "capture it again")
    from PIL import Image
    w, h = Image.open(png).size
    cam = {"camera": "mc", "eye": ",".join(f"{c:.3f}" for c in g["eye"]), "yaw": g.get("yaw", 0),
           "pitch": g.get("pitch", 0), "fov": g.get("fov", 70), "w": w, "h": h, "_png": png}
    if isinstance(g.get("renderDistance"), (int, float)):  # ground truth only where Minecraft itself renders: its chunks
        cam["_near"] = max(16, (int(g["renderDistance"]) - 1) * 16)  # (one chunk inside its render distance)
    if isinstance(g.get("dayTime"), (int, float)):  # night captures are rendered at night (sky light, the lightmap)
        cam["time"] = int(g["dayTime"]) % 24000
    if ref.get("pinned"):  # a pinned copy (pinned.py): rendered from its equivalent eye, its box cut out, ground truth
        p = g.get("pinned") or {}  # at any distance (the stage camera's render distance says nothing about it)
        if not p.get("eye") or not p.get("box"):
            return None, f"the capture of {ref['scene']} carries no pinned view (receipt 'pinned'): capture it again"
        cam.update(eye=",".join(f"{c:.4f}" for c in p["eye"]), box=p["box"])
        cam.pop("_near", None)
    return cam, ""


def lab_summary(report):
    """atlas_lab's runs per representation: worst iou, worst dE, mean steps (table mode), or its pop numbers (sweep)."""
    out = {}
    for r in report.get("runs", []):
        v = out.setdefault(r["variant"], {})
        if "pop" in r:  # sweep
            v.update(pop=r["pop"], popMax=r["pop_max"], dE=r["dE"], steps=r["steps"])
            continue
        if "motion_dE" in r:  # moving camera with TAA: the worst distance
            v["motionDEMax"] = max(v.get("motionDEMax", 0.0), r["motion_dE"])
            v["flickerMax"] = max(v.get("flickerMax", 0.0), r["flicker"])
            v.setdefault("_steps", []).append(r["steps"])
            continue
        if "iou" not in r:  # a camera frame: nothing to summarise
            continue
        v["iouMin"] = min(v.get("iouMin", 1.0), r["iou"])
        v["dEMax"] = max(v.get("dEMax", 0.0), r["dE"])
        v.setdefault("_steps", []).append(r["steps"])
    for v in out.values():
        st = v.pop("_steps", None)
        if st:
            v["steps"] = round(sum(st) / len(st), 2)
    return out


def lab_path(val):
    """A lab argument's file: "@game/..." is under the test instance's game directory (where in-game exports land)."""
    if val.startswith("@game/"):
        sys.path.insert(0, str(ROOT / "tools/dev"))
        import live_world as lw  # noqa: E402
        return lw.GAME / val[len("@game/"):]
    return ROOT / val


def lab_files(lab):
    """The exported input files a lab scene reads (its "@game/..." arguments)."""
    return [lab_path(v) for v in lab.values() if isinstance(v, str) and v.startswith("@game/")]


def gpu_busy():
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"],
                             capture_output=True, text=True, timeout=10).stdout
        return max(int(x) for x in out.split())
    except (OSError, ValueError, subprocess.SubprocessError):
        return 0


# ================================================================================================ owner + queue
def acquire():
    STORE.mkdir(parents=True, exist_ok=True)
    for _ in range(2):
        try:
            fd = os.open(OWNER, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, json.dumps({"pid": os.getpid(), "since": time.time()}).encode())
            os.close(fd)
            return True
        except FileExistsError:
            try:
                held = json.loads(OWNER.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                held = {}
            if alive(held.get("pid")):
                return False
            OWNER.unlink(missing_ok=True)  # the previous owner died
    return False


def release():
    try:
        if json.loads(OWNER.read_text(encoding="utf-8")).get("pid") == os.getpid():
            OWNER.unlink()
    except (OSError, ValueError):
        pass


def submit(keys, force=False, who="cli"):
    QUEUE.mkdir(parents=True, exist_ok=True)
    JOBS.mkdir(parents=True, exist_ok=True)
    jid = time.strftime("%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:4]
    job = {"id": jid, "items": keys, "force": force, "who": who, "pid": os.getpid(), "created": time.time()}
    (QUEUE / f"{jid}.json").write_text(json.dumps(job), encoding="utf-8")
    write_status(jid, {"id": jid, "state": "queued", "items": keys, "done": {}})
    return jid


def write_status(jid, st):
    st["updated"] = time.time()
    tmp = JOBS / f"{jid}.json.tmp"
    tmp.write_text(json.dumps(st), encoding="utf-8")
    os.replace(tmp, JOBS / f"{jid}.json")


def read_status(jid):
    try:
        return json.loads((JOBS / f"{jid}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def pending_jobs():
    jobs = []
    for f in sorted(QUEUE.glob("*.json")) if QUEUE.exists() else []:
        try:
            j = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not alive(j.get("pid")) and j.get("who") != "serve":  # its requester gave up: drop it
            f.unlink(missing_ok=True)
            continue
        jobs.append(j)
    return jobs


def summary(rec):
    m = rec.get("metrics", {})
    return {"status": rec.get("status"), "luma": m.get("luma"), "diff": m.get("diff"), "changed": m.get("changed"),
            "flags": m.get("flags", []) + rec.get("fails", []), "error": rec.get("error", ""),
            "perf": rec.get("perf"), "cached": rec.get("_cached", False), "crashLog": rec.get("crashLog", "")}


def process_queue(runner, serve=False, on_item=None):
    """Owner loop: merge all queued jobs, run each needed (test, variant) once, in the cheapest order, re-planning after
    every item so jobs that arrive mid-run join it."""
    by_id = {s["id"]: s for s in catalog.load()}
    done_now = {}
    while True:
        try:  # the live lease's heartbeat, every item: other checkouts see this run is alive
            import game as _g
            _g.refresh_lease()
        except (OSError, SystemExit):
            pass
        if STOP.exists():  # harness.py stop: finish here, mark the rest stopped, close the client
            STOP.unlink(missing_ok=True)
            for j in pending_jobs():
                st = read_status(j["id"]) or {"id": j["id"], "items": j["items"], "done": {}}
                for k in j["items"]:
                    st["done"].setdefault(k, {"status": "skip", "error": "stopped by request"})
                st["state"] = "done"
                write_status(j["id"], st)
                (QUEUE / f"{j['id']}.json").unlink(missing_ok=True)
            say("stopped by request")
            event("run_stopped")
            return done_now
        jobs = pending_jobs()
        if not jobs:
            if serve:
                time.sleep(2)
                continue
            return done_now
        statuses = {j["id"]: read_status(j["id"]) or {"id": j["id"], "items": j["items"], "done": {}} for j in jobs}
        todo = {}
        for j in jobs:
            for k in j["items"]:
                if k not in statuses[j["id"]]["done"]:
                    t = todo.setdefault(k, {"jobs": [], "force": False})
                    t["jobs"].append(j["id"])
                    t["force"] |= j.get("force", False)
        if not todo:
            for j in jobs:
                st = statuses[j["id"]]
                st["state"] = "done"
                write_status(j["id"], st)
                (QUEUE / f"{j['id']}.json").unlink(missing_ok=True)
            continue
        items, missing = [], []
        if any(k.rsplit("|", 1)[0] not in by_id for k in todo):  # a job queued after this owner started: newer tests
            by_id = {s["id"]: s for s in catalog.load()}
        for k in todo:
            sid, v = k.rsplit("|", 1)
            if sid in by_id:
                items.append((by_id[sid], v))
            else:
                missing.append(k)
        for k in missing:
            for jid in todo[k]["jobs"]:
                statuses[jid]["done"][k] = {"status": "error", "error": "unknown test id"}
        # cheap first: fresh cached results answer immediately
        live_items = []
        for s, v in items:
            fp = runner.fingerprint(s, v)
            rec = None if todo[key(s, v)]["force"] else runner.cached(s, v, fp)
            if rec:
                rec["_cached"] = True
                for jid in todo[key(s, v)]["jobs"]:
                    statuses[jid]["done"][key(s, v)] = summary(rec)
            else:
                live_items.append((s, v))
        if live_items:
            place_ids = [s["pose"]["place"] for s, _ in live_items if (s.get("pose") or {}).get("shot")]
            place_ids += [s["pose"][k]["place"] for s, _ in live_items for k in ("ladder", "sweep")
                          if k in (s.get("pose") or {}) and s["pose"][k].get("place")]
            if place_ids:
                runner.plan = cameras.ensure(place_ids, runner.seed, catalog.places(), log=say)
            gstate = runner.game.client_state() if any(s["tier"] == "live" for s, _ in live_items) else {}
            s, v = order(live_items, runner.pos or (0, 0), gstate.get("group", "live"), gstate.get("shaders", True))[0]
            if not any(x["tier"] != "live" for x, _ in live_items) or s["tier"] != "live":
                pass
            # labs in parallel (CPU, low priority): when the next item is a lab, run every pending lab together
            if s["tier"] == "lab":
                pending = {x["id"] for x, _ in live_items}
                labs = [(x, y) for x, y in live_items if x["tier"] == "lab" and ready(x, pending)]
                with ThreadPoolExecutor(max_workers=2) as pool:
                    recs = list(pool.map(lambda it: runner.run(it[0], it[1], runner.fingerprint(*it)), labs))
                ran = list(zip(labs, recs))
            else:
                ran = [((s, v), runner.run(s, v, runner.fingerprint(s, v)))]
            for (x, y), rec in ran:
                done_now[key(x, y)] = rec
                for jid in todo.get(key(x, y), {"jobs": []})["jobs"]:
                    statuses[jid]["done"][key(x, y)] = summary(rec)
                if on_item:
                    on_item(x, y, rec)
        for j in jobs:
            st = statuses[j["id"]]
            st["state"] = "done" if all(k in st["done"] for k in j["items"]) else "running"
            write_status(j["id"], st)
            if st["state"] == "done":
                (QUEUE / f"{j['id']}.json").unlink(missing_ok=True)


def line(k, d):
    sid, v = k.rsplit("|", 1)
    bits = [f"{d.get('status', '?'):5}", f"{sid} [{v}]"]
    if d.get("cached"):
        bits.append("(cached)")
    if d.get("diff") is not None:
        bits.append(f"diff {d['diff']} changed {d.get('changed')}%")
    if d.get("perf"):
        bits.append("p99 {} ms".format(d["perf"].get("p99")))
    if d.get("flags"):
        bits.append("! " + "; ".join(d["flags"]))
    if d.get("error"):
        bits.append("! " + d["error"].splitlines()[-1][:160])
    if d.get("crashLog"):
        bits.append(f"(crash log {d['crashLog']})")
    return "  " + "  ".join(bits)


def print_summary(done, all_lines=False, lines=True):
    counts = {}
    for d in done.values():
        counts[d.get("status")] = counts.get(d.get("status"), 0) + 1
    say("result: " + ", ".join(f"{n} {s}" for s, n in sorted(counts.items(), key=lambda x: str(x[0]))))
    if not lines:  # verbose runs already printed every line as it finished
        return
    bad = [(k, d) for k, d in done.items() if d.get("status") not in ("pass",) or all_lines]
    for k, d in bad[:25]:
        say(line(k, d))
    if len(bad) > 25:
        say(f"  ... {len(bad) - 25} more (see report)")


def scope_of(a, watch=False):
    """The report folder a run writes to: one per kind of run, so a focused run never replaces another's report."""
    if watch:
        return "watch"
    if getattr(a, "cmd", "") == "gate":
        return "gate"
    if getattr(a, "cmd", "") == "probe":
        return "probes"
    if getattr(a, "audit_scope", None):
        return a.audit_scope
    if a.changed is not None:
        return "changed"
    slug = lambda x: re.sub(r"[^a-z0-9]+", "-", x.lower()).strip("-")[:60]  # noqa: E731
    if a.suite:
        return "suite-" + slug(a.suite)
    if a.tag:
        return "tag-" + slug("+".join(a.tag))
    if a.ids and len(a.ids) == 1:
        return "test-" + slug(a.ids[0])
    if a.ids:
        import hashlib
        return "tests-" + hashlib.sha1(",".join(sorted(a.ids)).encode()).hexdigest()[:8]
    if a.tier:
        return "tier-" + slug(a.tier)
    return "suite-smoke"


def build_report(keys, title, scope="custom"):
    by_id = {s["id"]: s for s in catalog.load()}
    rows = []
    for k in keys:
        sid, v = k.rsplit("|", 1)
        rec = results.latest(sid, v)
        if rec:
            s = by_id.get(sid, {})
            rows.append({"scene": sid, "variant": v, "metrics": {**rec.get("metrics", {}),
                         "flags": rec.get("metrics", {}).get("flags", []) + rec.get("fails", []) +
                         ([rec["error"][-120:]] if rec.get("error") else [])},
                         "tags": s.get("tags", []), "title": s.get("title", sid),
                         "crashLog": rec.get("crashLog", ""), "status": rec.get("status")})
    run = results.report(time.strftime("%Y%m%d-%H%M%S"), rows, title, scope)
    try:
        results.overview(catalog.load())
    except Exception as e:  # noqa: BLE001
        say(f"  ! overview not updated: {e}")
    return run


# ================================================================================================ reloads
def apply_reloads(kinds, runner, paths=None, rebuild=False):
    """Hot where possible: shaders (pack zip + Iris reload), resources (overlay pack + resource reload), data
    (live_world reload <group>); jar needs a rebuild and a restart (only with --rebuild)."""
    g = runner.game
    for kind in kinds:
        if kind == "shaders":
            changed, errs = g.reload_shaders()
            say(f"reload shaders: {'changed' if changed else 'unchanged'}; {len(errs)} shader error line(s)")
            for e in errs[:20]:
                say("  " + e)
        elif kind == "resources":
            n = g.reload_resources([ROOT / p for p in paths] if paths else None)
            say(f"reload resources: {n} file(s)")
        elif kind.startswith("data:"):
            if g.running():
                g.reload_data(kind[5:])
                say(f"reload data {kind[5:]}")
        elif kind == "jar":
            if rebuild:
                say("rebuild jar (gradle build + tests) and restart the client")
                g.rebuild_jar()
            else:
                say("! Java changed: the running client still has the old jar (use --rebuild)")
        runner.invalidate()


# ================================================================================================ commands
def cmd_list(a):
    sel = select(catalog.load(), a)
    by = {}
    for s in sel:
        for t in s["tags"]:
            if t.startswith(("suite:", "feature:", "gallery:")) and not t.startswith("gallery:entity:"):
                by[t] = by.get(t, 0) + 1
    say(f"{len(sel)} test(s)")
    for t, n in sorted(by.items()):
        say(f"  {t:32} {n}")
    if a.ids_only or len(sel) <= 40:
        for s in sel:
            say(f"  {s['id']:44} {s['tier']:5} {s['group']:12} {s.get('title', '')[:70]}")


def cmd_plan(a):
    runner = Runner(a)
    items = items_of(select(catalog.load(), a), a)
    ordered = order(items)
    fresh = 0
    for s, v in ordered:
        fp = runner.fingerprint(s, v) if s["tier"] != "live" or a.exact else None
        c = runner.cached(s, v, fp) if fp else None
        fresh += bool(c)
        say(f"  {'cached' if c else 'run   '} {s['tier']:5} {s['group']:10} {v:8} {s['id']}")
    say(f"{len(ordered)} item(s){f', {fresh} cached' if a.exact else ''}")


def cmd_run(a, watch=False):
    if getattr(a, "relaunch", False):  # atlas-route --relaunch is the scene atlas-route-relaunch
        a.ids = [i + "-relaunch" if i == "atlas-route" else i for i in (a.ids or [])]
    scenes = select(catalog.load(), a)
    items = items_of(scenes, a)
    if not items:
        say("nothing selected")
        return 0
    keys = [key(s, v) for s, v in items]
    runner = Runner(a)
    if getattr(a, "fresh_world", False):
        import game
        game.FRESH_WORLD["on"] = True
    if getattr(a, "reloads", None):
        apply_reloads(a.reloads, runner, rebuild=a.rebuild)
    jid = submit(keys, force=a.force, who="watch" if watch else "cli")
    if acquire():
        owned = True
        tr.start_run(items=len(keys), argv=sys.argv[1:], job=jid)
        try:
            if any(s["tier"] == "live" for s, _ in items):
                runner.game.take_lease()
            t0 = time.monotonic()
            on = (lambda s, v, rec: say(line(key(s, v), summary(rec)))) if (VERBOSE or watch) else None
            process_queue(runner, on_item=on)
            st = read_status(jid) or {"done": {}}
            print_summary(st["done"], lines=not (VERBOSE or watch))
            counts = {}
            for d in st["done"].values():
                counts[d.get("status")] = counts.get(d.get("status"), 0) + 1
            runner.summary = ", ".join(f"{n} {k}" for k, n in sorted(counts.items(), key=lambda x: str(x[0])))
            event("run_finished", items=len(keys), counts=counts, scope=scope_of(a, watch),
                  report=str(results.REPORTS / scope_of(a, watch) / "report.html"))
            run = build_report(keys, f"NirnCraft harness: {scope_of(a, watch)}", scope_of(a, watch))
            pages = run / "agent/index.txt"
            n = len(pages.read_text(encoding="utf-8").splitlines()) if pages.exists() else 0
            say(f"report: {run / 'report.html'}  ({time.monotonic() - t0:.0f} s); overview: {STORE / 'index.html'}"
                + (f"; agent pages: {run / 'agent'} ({n} capture(s) worth a look)" if n else ""))
            prune()
            return runner
        finally:
            if not watch:
                try:
                    runner.close(getattr(runner, "summary", ""))
                except Exception as e:  # noqa: BLE001
                    say(f"! could not close the client cleanly: {e}")
                release()
                if runner._game is not None and not runner.game.running():
                    runner.game.drop_lease()
            del owned
    held = json.loads(OWNER.read_text(encoding="utf-8")) if OWNER.exists() else {}
    say(f"joined the running harness (owner pid {held.get('pid')}); waiting for {len(keys)} item(s)")
    while True:
        st = read_status(jid)
        if st and st.get("state") == "done":
            print_summary(st["done"])
            return None
        if not alive(held.get("pid")) and acquire():  # the owner went away: carry on ourselves
            release()
            return cmd_run(a, watch)
        time.sleep(1.5)


WATCH = {"shaderpack/InfiniteATLAS": "shaders", "mod/src/main/resources/assets": "resources",
         "mod/src/main/resources/nirncraft/tamriel/places": "data:places",
         "mod/src/main/resources/nirncraft/tamriel/cityplans": "data:cityplans",
         "mod/src/main/resources/nirncraft/tamriel/styles": "data:cityplans",
         "mod/src/main/resources/nirncraft/scatter": "data:scenes", "mod/src/main/resources/nirncraft/quests": "data:quests",
         "mod/src/main/java": "jar", "tools/harness/scenes": "catalog"}


def snapshot():
    snap = {}
    for root in WATCH:
        for f in (ROOT / root).rglob("*"):
            if f.is_file():
                try:
                    snap[f.relative_to(ROOT).as_posix()] = f.stat().st_mtime_ns
                except OSError:
                    pass
    return snap


def cmd_watch(a):
    a.keep = True
    runner = cmd_run(a, watch=True)
    if runner is None:
        say("another harness owns the client; run watch when it is idle")
        return
    say("watching for edits (Ctrl+C to stop; the client stays open; `harness.py run` from elsewhere joins in)")
    snap = snapshot()
    alive_at = time.monotonic()
    try:
        while True:
            time.sleep(1.0)
            if time.monotonic() - alive_at > 600 and runner.game.running():  # the client closes itself after 20 idle min
                try:
                    runner.game.actions(["# harness watch keepalive"], timeout=60)
                except RuntimeError:
                    pass
                alive_at = time.monotonic()
            new = snapshot()
            changed = sorted(k for k in set(snap) | set(new) if snap.get(k) != new.get(k))
            if not changed:
                if pending_jobs():  # serve other requesters between edits
                    process_queue(runner)
                continue
            time.sleep(0.8)  # let an editor finish writing
            new = snapshot()
            changed = sorted(k for k in set(snap) | set(new) if snap.get(k) != new.get(k))
            snap = new
            kinds = []
            for root, kind in WATCH.items():
                if any(c.startswith(root + "/") for c in changed) and kind not in kinds:
                    kinds.append(kind)
            say(f"\n{len(changed)} file(s) changed: {', '.join(kinds)}")
            res_paths = [c for c in changed if c.startswith("mod/src/main/resources/assets/")]
            try:
                apply_reloads([k for k in kinds if k != "catalog"], runner, res_paths, a.rebuild)
            except RuntimeError as e:
                say(f"! reload failed: {e}")
                continue
            a.force = True
            scenes = select(catalog.load(), a)
            keys = [key(s, v) for s, v in items_of(scenes, a)]
            submit(keys, force=True, who="watch")
            t0 = time.monotonic()
            done = process_queue(runner, on_item=lambda s, v, rec: say(line(key(s, v), summary(rec))))
            run = build_report(keys, "NirnCraft harness (watch)", "watch")
            say(f"{len(done)} item(s) in {time.monotonic() - t0:.0f} s; report: {run / 'report.html'}")
    except KeyboardInterrupt:
        say("watch stopped")
    finally:
        a.keep = False
        try:
            runner.close()
        finally:
            release()


def cmd_serve(a):
    if not acquire():
        say("another harness already owns the client")
        return
    say("serving harness jobs (Ctrl+C to stop)")
    runner = Runner(a)
    a.keep = True
    try:
        process_queue(runner, serve=True, on_item=lambda s, v, rec: say(line(key(s, v), summary(rec))))
    except KeyboardInterrupt:
        pass
    finally:
        release()


def cmd_status(a):
    held = json.loads(OWNER.read_text(encoding="utf-8")) if OWNER.exists() else {}
    say(f"owner: {'pid ' + str(held.get('pid')) if alive(held.get('pid')) else 'none'}")
    jobs = pending_jobs()
    say(f"queue: {len(jobs)} job(s)" + "".join(f"\n  {j['id']} ({j['who']}): {len(j['items'])} item(s)" for j in jobs))
    try:
        import game
        st = game.client_state()
        say(f"client: {'running' if game.running() else 'closed'}; group {st.get('group', '-')}; "
            f"shaders {'on' if st.get('shaders', True) else 'off'}")
    except Exception as e:  # noqa: BLE001
        say(f"client: unknown ({e})")


def cmd_report(a):
    items = items_of(select(catalog.load(), a), a)
    run = build_report([key(s, v) for s, v in items], f"NirnCraft harness: {scope_of(a)}", scope_of(a))
    say(f"report: {run / 'report.html'}; overview: {STORE / 'index.html'}")


def cmd_cameras(a):
    sel = [s for s in select(catalog.load(), a) if (s.get("pose") or {}).get("place")]
    ids = [s["pose"]["place"] for s in sel]
    plan = cameras.ensure(ids, a.seed, catalog.places(), log=say)
    say(f"{len(set(ids))} place(s) planned for seed {a.seed}: {cameras.plan_path(a.seed)}")
    for pid in list(dict.fromkeys(ids))[:10]:
        e = plan.get(pid, {})
        for k in ("aerial", "approach"):
            if e.get(k):
                say(f"  {pid:28} {k:8} camera {e[k][0]['camera']} visible {e[k][0]['visible']}")


def cmd_features(a):
    """The generated-feature index for the seed (made when missing or stale): spots per biome / landmark / site kind."""
    idx = features.ensure(a.seed, threads=a.threads, per=a.per, force=a.force, log=say)
    found = idx.get("_found", {})
    for g in features.GROUPS:
        kinds = idx.get(g, {})
        say(f"{g}: {len(kinds)} kind(s), {sum(len(v) for v in kinds.values())} spot(s)")
        for k, spots in sorted(kinds.items()):
            say(f"  {k:44} {len(spots)} spot(s) of {found.get(g, {}).get(k, '?')} found")
    say(f"index: {features.index_path(a.seed)}")


def cmd_export_tour(a):
    """A stop list for /nirncraft-tour start file:NAME (the host's in-game tour), from the selected scenes."""
    stops = []
    for s in select(catalog.load(), a):
        p = s.get("pose") or {}
        if "place" in p and p.get("shot"):
            stops.append({"name": s.get("title", s["id"]), "place": p["place"], "shot": p["shot"]})
        elif "place" in p:
            pl = catalog.places().get(p["place"])
            if pl:
                dx, dz = p.get("offset", [0, 0])
                stops.append({"name": s.get("title", s["id"]), "x": pl["x"] + dx, "z": pl["z"] + dz,
                              "above": p.get("height", 40), "yaw": p.get("yaw", 0), "pitch": p.get("pitch", -15)})
        elif "visit" in p:
            x, z, h, yaw, pitch = p["visit"]
            stops.append({"name": s.get("title", s["id"]), "x": x, "z": z, "above": h, "yaw": yaw, "pitch": pitch})
        elif "camera" in p and int(s.get("seed", a.seed)) == a.seed:
            x, y, z, lx, ly, lz = p["camera"]
            yaw = math.degrees(math.atan2(-(lx - x), lz - z))
            pitch = -math.degrees(math.atan2(ly - y, math.hypot(lx - x, lz - z)))
            stops.append({"name": s.get("title", s["id"]), "x": x, "y": y, "z": z, "yaw": round(yaw, 1),
                          "pitch": round(pitch, 1)})
    targets = [Path(t) for t in (a.to or [])]
    if not targets:
        import game
        targets = [game.GAME]
    for t in targets:
        out = t / "nirncraft-tours" / f"{a.name}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(stops, indent=1), encoding="utf-8")
        say(f"{len(stops)} stop(s) -> {out}   (in game: /nirncraft-tour start file:{a.name})")


def cmd_seed_legacy(a):
    seeds = catalog.legacy_seed()
    n = 0
    for sid, png in seeds.items():
        if (ROOT / png).exists() and not Path(f"{results.stem(sid, 'shaders')}.before.png").exists():
            results.seed_before(sid, "shaders", ROOT / png)
            n += 1
    say(f"seeded {n} legacy capture(s) as `before`")


def cmd_open(a):
    """Open the overview (default), a scope's report (`open suite-smoke`) or a file inside it."""
    if not a.what:
        target = STORE / "index.html"
    elif (results.REPORTS / a.what).is_dir():
        target = results.REPORTS / a.what / "report.html"
    else:
        target = results.REPORTS / a.what
    if not target.exists():
        say(f"nothing yet at {target}; run something first")
        return 1
    say(f"opening {target}")
    os.startfile(str(target)) if os.name == "nt" else subprocess.run(["xdg-open", str(target)])


def cmd_stop(a):
    """Stop the running harness: by default at once (the run ends, the client's queue is cancelled and the client closes
    cleanly, the rest is marked stopped); --after-test lets the current test finish first."""
    held = json.loads(OWNER.read_text(encoding="utf-8")) if OWNER.exists() else {}
    pid = held.get("pid")
    if not alive(pid):
        say("no harness is running")
        OWNER.unlink(missing_ok=True)
        return 0
    STOP.parent.mkdir(parents=True, exist_ok=True)
    STOP.write_text(str(time.time()), encoding="utf-8")
    if a.after_test:
        say(f"stop requested: the harness (pid {pid}) finishes its current test, then closes the client")
        return 0
    if os.name == "nt":  # the owner and its launcher wrappers (py.exe): ours to end
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        os.kill(int(pid), 15)
    OWNER.unlink(missing_ok=True)
    STOP.unlink(missing_ok=True)
    for j in pending_jobs() + [json.loads(f.read_text(encoding="utf-8")) for f in QUEUE.glob("*.json")] if QUEUE.exists() else []:
        st = read_status(j["id"]) or {"id": j["id"], "items": j["items"], "done": {}}
        for k in j["items"]:
            st["done"].setdefault(k, {"status": "skip", "error": "stopped by request"})
        st["state"] = "done"
        write_status(j["id"], st)
        (QUEUE / f"{j['id']}.json").unlink(missing_ok=True)
    try:
        import game
        if game.running():
            try:
                game.lw.live_actions(["# stopped by harness.py stop"], timeout=30, cancel=True)
            except Exception:  # noqa: BLE001
                pass
            game.close()
        game.drop_lease()
    except Exception as e:  # noqa: BLE001
        say(f"  ! client close: {e}")
    event("run_stopped", now=True)
    say(f"stopped the harness (pid {pid}) and closed the test client")


def cmd_quarantine(a):
    q = quarantine()
    if a.clear is not None:
        keys = a.clear or list(q)
        for k in keys:
            q.pop(k, None)
        QUARANTINE.parent.mkdir(parents=True, exist_ok=True)
        QUARANTINE.write_text(json.dumps(q, indent=1), encoding="utf-8")
        say(f"cleared {len(keys)} quarantine entries")
        return
    live = {k: e for k, e in q.items() if e.get("quarantined")}
    say(f"{len(live)} quarantined, {len(q) - len(live)} on watch (failing, not yet quarantined)")
    for k, e in sorted(q.items(), key=lambda kv: -kv[1].get("count", 0)):
        say(f"  {'Q' if e.get('quarantined') else ' '} {e.get('count')}x {k}: {e.get('reason', '')[:120]}")


def cmd_trace(a):
    """What led up to a test's failure (or the whole run): harness steps interleaved with the game's log lines of the
    same seconds. --previous reads the run before."""
    steps = tr.load(previous=a.previous)
    if not steps:
        say("no trace yet")
        return 1
    if a.test:
        mine = [i for i, s in enumerate(steps) if s.get("test") and (s["test"] == a.test or s["test"].startswith(a.test + "|"))]
        if not mine:
            say(f"no steps for {a.test} in this trace")
            return 1
        lo, hi = max(0, mine[0] - a.before), mine[-1] + 1
        steps = steps[lo:hi]
    else:
        steps = steps[-a.last:]
    t_lo, t_hi = steps[0]["t"] - 2, steps[-1]["t"] + 2
    game_lines = []
    log_file = Path(os.environ.get("APPDATA", "")) / "PrismLauncher/instances/NirnCraft-26.3-autotest/.minecraft/logs/latest.log"
    if log_file.exists() and not a.no_game_log:
        day = time.strftime("%Y-%m-%d", time.localtime(t_lo))
        for line in log_file.read_text(encoding="utf-8", errors="replace").splitlines():
            m = re.match(r"^\[\d+\w+\d+ (\d\d:\d\d:\d\d)\.\d+\]", line)
            if not m:
                continue
            try:
                ts = time.mktime(time.strptime(f"{day} {m.group(1)}", "%Y-%m-%d %H:%M:%S"))
            except ValueError:
                continue
            if t_lo <= ts <= t_hi and ("ERROR" in line or "WARN" in line or "Autotest" in line or "Exception" in line):
                game_lines.append((ts, "game  " + line[line.find("]", 1) + 1:].strip()[:220]))
    rows = [(s["t"], fmt_step(s)) for s in steps] + game_lines
    for _, text in sorted(rows, key=lambda r: r[0]):
        say(text)
    tb = [s for s in steps if s.get("traceback") and "NoneType: None" not in s["traceback"]]
    if tb:
        say("\nlast harness traceback:\n" + tb[-1]["traceback"])


def fmt_step(s):
    head = f"{s.get('at')} {s.get('step'):15} {s.get('test') or '':42}"
    if s["step"] == "actions":
        verbs = "; ".join(s.get("lines", []))[:200]
        out = "ok" if s.get("ok") else "FAILED " + (s.get("error") or "")[:160]
        st = s.get("status") or {}
        return f"{head} {s.get('seconds')}s {out} | {verbs} | screen={st.get('screen')} last={st.get('lastAction')}"
    extra = {k: v for k, v in s.items() if k not in ("t", "at", "step", "test", "traceback")}
    return f"{head} {json.dumps(extra, default=str)[:260]}"


def cmd_clean(a):
    import shutil
    for d in ("results", "runs", "latest", "reports", "queue", "jobs", "trace"):
        shutil.rmtree(STORE / d, ignore_errors=True)
    say("harness results cleared")


# ================================================================================================ probes, zoom, prune
PROBE_TTL, PROBE_KEEP, STALE_DAYS = 24 * 3600, 40, 7


def prune(log=True):
    """Keep the store lean: probes older than a day or beyond the newest 40 (with their results), results of tests
    that no longer exist after a week, old zoom crops."""
    import shutil
    removed = 0
    try:
        probes = json.loads(catalog.PROBES.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        probes = {}
    now = time.time()
    keep = sorted(probes.values(), key=lambda s: -s.get("created", 0))[:PROBE_KEEP]
    keep = {s["id"]: s for s in keep if now - s.get("created", 0) < PROBE_TTL}
    for sid in set(probes) - set(keep):
        shutil.rmtree(results.SCENES / sid, ignore_errors=True)
        removed += 1
    if probes and len(keep) != len(probes):
        catalog.PROBES.write_text(json.dumps(keep, indent=1), encoding="utf-8")
    if results.SCENES.exists():
        known = {s["id"] for s in catalog.load()}
        for d in results.SCENES.iterdir():
            if d.is_dir() and d.name not in known and now - d.stat().st_mtime > STALE_DAYS * 86400:
                shutil.rmtree(d, ignore_errors=True)
                removed += 1
    try:
        import game
        game.trim_instance()
    except Exception as e:  # noqa: BLE001
        say(f"  ! could not trim the instance's logs: {e}")
    z = STORE / "zoom"
    if z.exists():
        for f in z.iterdir():
            if now - f.stat().st_mtime > PROBE_TTL:
                f.unlink(missing_ok=True)
    if log and removed:
        say(f"pruned {removed} stale result folder(s)")


def cmd_probe(a):
    """One ad-hoc visual test without editing the catalog (agents: look at something specific, safely; the preview
    world is disposable). It runs through the owner like any test and lands in the same before/after store."""
    pose = {}
    if a.visit:
        pose["visit"] = [int(float(v)) for v in a.visit.split(",")]
    elif a.camera:
        pose["camera"] = [float(v) for v in a.camera.split(",")]
    elif a.place:
        pose["place"] = a.place
        if a.shot:
            pose["shot"] = a.shot
    elif a.stage:
        pose["stage"] = list(catalog.STAGE) + [float(a.stage)]
    else:
        pose["spawn"] = True
    sid = "probe-" + re.sub(r"[^a-z0-9]+", "-", a.name.lower()).strip("-")
    scene = {"id": sid, "title": a.title or a.name, "tags": ["suite:probe"] + (a.probe_tag or []), "pose": pose,
             "setup": a.setup or [], "cleanup": a.cleanup or [], "variants": (a.variants or "shaders").split(","),
             "created": time.time(), "source": "harness.py probe"}
    if a.camera:
        scene["seed"] = a.seed
    for k in ("time", "weather"):
        if getattr(a, k) is not None:
            scene[k] = getattr(a, k)
    if a.capture_ui:
        scene["capture"] = "ui"
    if a.frames:
        scene["frames"] = [int(v) for v in a.frames.split(",")]
    try:
        probes = json.loads(catalog.PROBES.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        probes = {}
    probes[sid] = scene
    catalog.PROBES.parent.mkdir(parents=True, exist_ok=True)
    catalog.PROBES.write_text(json.dumps(probes, indent=1), encoding="utf-8")
    a.ids, a.suite, a.tag, a.tier, a.group, a.changed, a.exclude, a.variant = [sid], None, None, None, None, None, None, None
    a.force = True
    return cmd_run(a)


def cmd_zoom(a):
    out = results.zoom(a.id, a.variant or "shaders", crop=[int(v) for v in a.crop.split(",")] if a.crop else None,
                       cell=a.cell, which=a.which)
    say(f"zoom: {out}")


# ================================================================================================ gate (merge / push)
def merge_base(ref):
    r = subprocess.run(["git", "merge-base", "HEAD", ref], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip() or ref


def problems(rec):
    m = (rec or {}).get("metrics", {})
    for k in ("problems", "errors", "failing"):
        if isinstance(m.get(k), (int, float)):
            return m[k]
    return None


def blocking(rec):
    """A result blocks a push when it fails and is new or worse than before (known failures, e.g. a lint that already
    reported 1416 placement problems, don't block unrelated pushes; making them worse does)."""
    if not rec or rec.get("status") not in ("fail", "error"):
        return False
    prev = rec.get("previous") or {}
    if prev.get("status") not in ("fail", "error"):
        return True
    now, before = problems(rec), problems(prev)
    return now is not None and before is not None and now > before


def reusable(rec, fp):
    """Whether a stored result stands for this test now (same fingerprint) without running it again. Only real
    outcomes: a skip is not a result (the test never ran), and reusing one kept 170 tests "skipped" gate after gate,
    passing on tests nobody ran (2026-10-19). audits/runner_selftest.py holds it to that."""
    return bool(rec) and rec.get("fingerprint") == fp and rec.get("status") in ("pass", "flag")


def gate_verdict(recs, is_blocking):
    """recs: [(key, latest record or None)] -> (blocking, known failures, not run). A test that did not run blocks the
    gate (a quarantined one is listed, it has its own record and reason); no result blocks; a new or worse failure
    blocks; an old failure is known."""
    bad, known, unrun = [], [], []
    for k, rec in recs:
        if not rec:
            bad.append(f"{k} (no result)")
        elif rec.get("status") == "skip":
            why = rec.get("error", "")
            unrun.append(f"{k}: {why[:100]}")
            if "quarantined" not in why:
                bad.append(f"{k} (not run: {why[:80]})")
        elif is_blocking(rec):
            bad.append(k)
        elif rec.get("status") in ("fail", "error"):
            known.append(k)
    return bad, known, unrun


def cmd_gate(a):
    """Runs exactly the tests the changes since BASE can affect (impact.json), reusing fresh results, and fails when
    one of them newly fails (or fails worse). `--install-hook` makes it the git pre-push check
    (git push --no-verify skips it knowingly)."""
    if a.install_hook:
        hook = ROOT / ".git/hooks/pre-push"
        hook.write_text("#!/bin/sh\n# NirnCraft harness gate: the tests touched by what is being pushed\n"
                        "py -3 tools/harness/harness.py gate --base \"${NIRNCRAFT_GATE_BASE:-origin/main}\" || {\n"
                        "  echo 'harness gate failed: fix it, or push with --no-verify knowingly'; exit 1; }\n",
                        encoding="utf-8", newline="\n")
        say(f"pre-push hook installed: {hook}")
        return 0
    base = merge_base(a.base)
    files = changed_files(base)
    tags, reloads = impact(files)
    say(f"gate: {len(files)} changed file(s) since {base[:10]} -> {len(tags)} test tag(s)")
    if not tags:
        say("gate: nothing testable changed: PASS")
        return 0
    a.ids, a.suite, a.group, a.variant, a.changed = [], None, None, None, None
    a.exclude = "suite:places-planned,suite:animation"
    a.tag = [",".join(tags)]
    sel = select(catalog.load(), a)
    if not a.live:
        sel = [s for s in sel if s["tier"] != "live"]
    items = items_of(sel, a)
    say(f"gate: {len(items)} test item(s) (" + ", ".join(f"{t} {sum(1 for s, _ in items if s['tier'] == t)}"
                                                       for t in ("check", "lab", "live")) + ")")
    if a.dry:
        for s, v in order(items):
            say(f"  {s['tier']:5} {v:8} {s['id']}")
        return 0
    a.reloads = reloads if a.live else []
    a.ids, a.tag, a.exclude = [s["id"] for s in sel], None, None
    cmd_run(a)
    bad, known, unrun = gate_verdict([(key(s, v), results.latest(s["id"], v)) for s, v in items], blocking)
    reviewed, review_state = [], "off"
    agent_dir = results.REPORTS / scope_of(a, False) / "agent"
    index = agent_dir / "index.txt"
    if not a.ai_review:
        say("gate: independent visual review: off (--no-ai-review): the pictures are UNVERIFIED")
    elif not index.exists():
        # nothing worth a look is a result too: say it, so a skipped review never reads like a passed one
        review_state = "nothing to review"
        say(f"gate: independent visual review: no captures worth a look ({index} absent)")
    else:
        import review
        captures = [l for l in index.read_text(encoding="utf-8").splitlines() if l.strip()]
        pages_n = len({l.split(" row ")[0] for l in captures})
        if not review.available():
            review_state = "unverified (no claude CLI)"
            say(f"gate: independent visual review: no claude CLI: {len(captures)} capture(s) NOT reviewed (UNVERIFIED)")
        else:
            say(f"gate: independent visual review ({a.model}): {len(captures)} capture(s) on {pages_n} page(s) ...")
            before_open = {k for k, e in load_issues().items() if e.get("state") in ACTIVE}
            gated = {key(s, v) for s, v in items}
            try:
                found = review_pages(agent_dir, f"changes since {base[:10]}", a.model)
            except Exception as e:  # a reviewer that failed must not look like one that found nothing
                found = None
                review_state = f"unverified (reviewer failed: {e})"
                say(f"gate: independent visual review FAILED: {str(e)[:300]} (the pictures are UNVERIFIED)")
            if found is not None:
                last = review.LAST_PAGES
                ok = last.get("ok")
                seen = (ok or 0) + len(found)
                review_state = "reviewed"
                for kind, test, v, what in found:
                    k = f"{test}|{v or 'shaders'}"
                    if kind == "BROKEN" and k in gated and k not in before_open:
                        bad.append(f"{k} (independent reviewer: {what[:100]})")
                        fate = "BLOCKS"
                    elif kind == "BROKEN":
                        fate = "already open" if k in before_open else "not in this gate's tests"
                    elif kind == "REGRESSION":
                        fate = "issue (does not block)"
                    else:
                        fate = "note"
                    reviewed.append(f"{kind} {k}: {what} [{fate}]")
                    say(f"  reviewer {kind:10s} {k}: {what[:140]}  [{fate}]")
                say(f"gate: independent visual review: {len(found)} finding(s), {ok if ok is not None else '?'} said "
                    f"to look right, of {len(captures)} capture(s)")
                if ok is None or seen < len(captures) * 0.9:
                    review_state = f"incomplete ({seen} of {len(captures)} accounted for)"
                    say(f"gate: independent visual review INCOMPLETE: the reviewer accounted for {seen} of "
                        f"{len(captures)} capture(s); the rest are UNVERIFIED (answer: {last.get('answer', '')[:200]!r})")
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    receipt = {"head": head, "base": base, "files": len(files), "tags": tags, "items": len(items), "blocking": bad,
               "review": reviewed, "reviewState": review_state,
               "knownFailures": known, "notRun": unrun, "at": time.time()}
    (STORE / "gate").mkdir(parents=True, exist_ok=True)
    (STORE / "gate" / "latest.json").write_text(json.dumps(receipt, indent=1), encoding="utf-8")
    if unrun:
        say(f"gate: {len(unrun)} test item(s) did not run:")
        for u in unrun[:10]:
            say(f"  {u}")
    say(f"gate: {'FAIL' if bad else 'PASS'} ({len(bad)} new failure(s), {len(known)} known, {len(unrun)} not run, "
        f"{len(items)} test item(s); visual review: {review_state})")
    for b in bad[:20]:
        say(f"  {b}")
    sys.exit(1 if bad else 0)


# ================================================================================================ budgeted audits
DURATIONS = STORE / "durations.json"
BUDGETS = {"5m": 300, "30m": 1800, "1h": 3600, "2h": 7200}


def note_duration(k, secs):
    try:
        d = json.loads(DURATIONS.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        d = {}
    old = d.get(k)
    d[k] = round(secs if old is None else 0.6 * old + 0.4 * secs, 1)  # smoothed: one slow run does not dominate
    STORE.mkdir(parents=True, exist_ok=True)
    DURATIONS.write_text(json.dumps(d), encoding="utf-8")


def estimate(s, v, ledger):
    """Seconds a test will take: its own history, else a default by kind."""
    k = key(s, v)
    if k in ledger:
        return ledger[k]
    p = s.get("pose") or {}
    if s["tier"] == "check":
        return {"check-unit": 240, "check-native": 600, "check-camera-planner": 120}.get(s["id"], 30)
    if s["tier"] == "lab":
        return 15
    base = 25 + (10 if v == "vanilla" else 0)
    if "ladder" in p:
        n = len(p["ladder"]["distances"])
        return base + n * (16 if s.get("fidelity") else 8)
    if "sweep" in p:
        return base + 55
    if s.get("measure_command"):
        return 35
    if s.get("frames"):
        return base + 10
    if "stage" in p:
        return 12
    return base


def stratum(s):
    tags = s["tags"]
    suite = next((t[6:] for t in tags if t.startswith("suite:") and t not in ("suite:smoke", "suite:seam-core")), "-")
    sub = next((t for t in tags if t.startswith(("subject:", "gallery:monster", "gallery:creature", "gallery:npc",
                                                  "gallery:armor", "gallery:weapons", "gallery:blocks", "type:"))), "")
    return (s["tier"], suite, sub)


def cmd_audit(a):
    """A random but well-spread sample of everything that fits a time budget: failing / flagged tests first, then
    never-run, then the stalest, rotating across strata (tier x suite x category) so even 5 minutes touches many
    areas, and repeated audits cover the whole world over time."""
    import random
    budget = BUDGETS.get(a.budget) or int(float(a.budget.rstrip("m")) * 60)
    seed = a.seed_audit if a.seed_audit is not None else int(time.time()) % 100000
    rng = random.Random(seed)
    scenes = [s for s in catalog.load() if s["_file"] != "(probe)"]
    themes = json.loads((HERE / "themes.json").read_text(encoding="utf-8"))
    if a.theme == "list" or a.theme == "?":
        for name, th in themes.items():
            if not name.startswith("_"):
                n = sum(1 for s in scenes if any(tag_match(x, s["tags"]) or any(t.startswith(x) for t in s["tags"])
                                                 for x in th["tags"]))
                say(f"  {name:10} {n:5} tests  {th['description']}")
        return 0
    if a.theme:
        if a.theme not in themes:
            say(f"unknown theme {a.theme}; harness.py audit --theme list")
            return 1
        want = themes[a.theme]["tags"]
        scenes = [s for s in scenes if any(tag_match(x, s["tags"]) or any(t.startswith(x) for t in s["tags"]) for x in want)]
        a.audit_scope_theme = a.theme
    if a.focus:
        scenes = [s for s in scenes if any(tag_match(f, s["tags"]) for f in a.focus.split(","))]
    if budget < 3600:  # restart groups cost a relaunch: only in the longer audits
        scenes = [s for s in scenes if s["tier"] != "live" or s["group"] == "live"]
    try:
        ledger = json.loads(DURATIONS.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        ledger = {}
    now = time.time()
    strata = {}
    for s in scenes:
        for v in (s["variants"] if s["tier"] == "live" else [s["tier"]]):
            rec = results.latest(s["id"], v) or {}
            status = rec.get("status")
            try:
                age = now - time.mktime(time.strptime(rec["capturedAt"], "%Y-%m-%dT%H:%M:%S"))
            except (KeyError, ValueError):
                age = None
            # priority: re-check problems, then never run, then stalest; random tie-breaks spread the sample
            weight = (0 if status in ("fail", "error", "crash", "flag") else 1 if age is None else 2,
                      -(age or 0), rng.random())
            strata.setdefault(stratum(s), []).append((weight, s, v))
    for items in strata.values():
        items.sort(key=lambda x: x[0])
    total = sum(len(v) for v in strata.values())
    keys = list(strata)
    rng.shuffle(keys)
    keys.sort(key=lambda k: 0 if k[0] == "check" else 1 if k[0] == "lab" else 2)  # cheap tiers lead
    picked, spent, live = [], 0.0, False
    while keys and spent < budget:
        progressed = False
        for k in list(keys):
            if not strata[k]:
                keys.remove(k)
                continue
            _, s, v = strata[k].pop(0)
            cost = estimate(s, v, ledger) + (0 if live or s["tier"] != "live" else 70)  # first live test: launch
            if spent + cost > budget:
                continue
            picked.append((s, v))
            spent += cost
            live = live or s["tier"] == "live"
            progressed = True
        if not progressed:
            break
    known = total
    covered = sum(1 for s in scenes for v in (s["variants"] if s["tier"] == "live" else [s["tier"]])
                  if (results.latest(s["id"], v) or {}).get("capturedAt"))
    by_tier = {}
    for s, v in picked:
        by_tier[s["tier"]] = by_tier.get(s["tier"], 0) + 1
    say(f"audit {a.theme + ' ' if a.theme else ''}{a.budget} (seed {seed}): {len(picked)} test(s), ~{spent / 60:.0f} min estimated, "
        f"{len({stratum(s) for s, _ in picked})} strata; " + ", ".join(f"{k} {n}" for k, n in sorted(by_tier.items()))
        + f"; coverage so far {covered}/{known} ({100 * covered / max(1, known):.0f}%)")
    if a.dry:
        for s, v in order(picked):
            say(f"  {s['tier']:5} {v:8} {s['id']}")
        return 0
    a.ids, a.suite, a.tag, a.tier, a.group, a.changed, a.exclude, a.variant = (
        sorted({s["id"] for s, _ in picked}), None, None, None, None, None, None, None)
    a.force = False
    a.audit_scope = f"audit-{a.theme + '-' if a.theme else ''}{a.budget}"
    return cmd_run(a)


# ================================================================================================ subscriptions + issue tally
ISSUES = STORE / "issues.json"


def git_head():
    r = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip()


ACTIVE = ("open", "in-progress", "testing", "needs-review")


def load_issues():
    try:
        led = json.loads(ISSUES.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    led.pop("_stats", None)
    return led


def issue_stats():
    try:
        return json.loads(ISSUES.read_text(encoding="utf-8")).get("_stats", {})
    except (OSError, ValueError):
        return {}


FIXED_KEEP_DAYS = 7  # a fixed issue leaves the list after a week (the totals keep counting it)
SEVERITY = {"crash": 0, "error": 1, "fail": 2, "review": 2, "flag": 3}


def save_issues(led):
    """Fixed issues older than FIXED_KEEP_DAYS drop off (counted in _stats), so the list stays what still matters."""
    stats = led.pop("_stats", {"fixedTotal": 0})
    cutoff = time.time() - FIXED_KEEP_DAYS * 86400
    for k in [k for k, e in led.items() if e.get("state") == "fixed"]:
        try:
            when = time.mktime(time.strptime(led[k].get("fixed", ""), "%Y-%m-%d %H:%M"))
        except ValueError:
            when = time.time()
        if when < cutoff:
            del led[k]
            stats["fixedTotal"] = stats.get("fixedTotal", 0) + 1
    led["_stats"] = stats
    STORE.mkdir(parents=True, exist_ok=True)
    ISSUES.write_text(json.dumps(led, indent=1), encoding="utf-8")


def cause(e):
    """Group key: issues with the same cause (numbers and ids stripped from the reason) belong together."""
    if e.get("group"):
        return e["group"]
    why = re.sub(r"[-+]?\d+(\.\d+)?", "#", e.get("why", "")).split(";")[0]
    return re.sub(r"\s+", " ", why)[:70].strip() or e.get("area", "-")


def issue_priority(e, groups):
    """P1..P4: a manual priority wins; else severity, how often it recurs, how many tests share its cause."""
    if e.get("priority"):
        return e["priority"]
    score = 3 - SEVERITY.get(e.get("status"), 3)          # crash 3 .. flag 0
    score += min(2, e.get("count", 1) // 3)                 # keeps coming back
    score += min(2, len(groups.get(cause(e), [])) // 3)     # one cause, many tests
    return "P1" if score >= 5 else "P2" if score >= 3 else "P3" if score >= 1 else "P4"


def tally(s, v, rec):
    """The running tally and its workflow: open -> in-progress (claimed) -> testing (an agent says fixed) -> verified by
    the test passing (needs-review for visual tests, until a reviewer confirms the picture) -> fixed. A failure reopens
    anything not fixed for good, and a fix that does not hold goes back to open with a note."""
    k = key(s, v)
    led = load_issues()
    st = rec.get("status")
    now = time.strftime("%Y-%m-%d %H:%M")
    area = next((t[6:] for t in s.get("tags", []) if t.startswith("suite:") and t not in ("suite:smoke", "suite:seam-core")),
                s.get("tier", "-"))
    why = rec.get("error") or "; ".join((rec.get("fails") or []) + (rec.get("metrics", {}).get("flags") or []))
    e = led.get(k)
    if st in ("fail", "error", "crash", "flag"):
        if not e or e.get("state") == "fixed":
            e = {"test": k, "area": area, "opened": now, "count": 0, "state": "open", "history": (e or {}).get("history", []),
                 "visual": s.get("kind") == "visual"}
        elif e.get("state") in ("testing", "needs-review"):
            e["state"] = "open"
            e.setdefault("notes", []).append(f"{now}: the fix did not hold ({st})")
        e.update(lastSeen=now, count=e["count"] + 1, status=st, why=why[:300], head=git_head())
        led[k] = e
    elif st == "pass" and e and e.get("state") in ACTIVE and not (e.get("status") == "review" and e.get("state") != "testing"):
        # (a reviewer's finding: the test never failed on it, so only a fix marked `issues fixed` moves it on)
        if e.get("visual") is None:  # entries from before the flag existed
            e["visual"] = s.get("kind") == "visual"
        if e.get("visual") and e.get("state") != "needs-review":
            e.update(state="needs-review", passedAt=now)  # the numbers pass; a reviewer confirms the picture
        elif not e.get("visual"):
            e.update(state="fixed", fixed=now, fixedAt=git_head())
            e.setdefault("history", []).append({"opened": e["opened"], "fixed": now, "why": e.get("why", "")[:160]})
    else:
        return
    save_issues(led)


def cmd_issues(a):
    """list | claim | fixed | verify | confirm | reopen | note: the issue workflow (see tally)."""
    led = load_issues()
    now = time.strftime("%Y-%m-%d %H:%M")

    def find(t):
        hits = [k for k in led if k == t or k.split("|")[0] == t]
        if not hits:
            say(f"no issue for {t}; harness.py issues")
        return hits

    act = a.action or "list"
    if act == "add":  # a finding no test fails on (e.g. the visual reviewer's BROKEN lines) becomes a tracked issue
        if not a.test or not a.note:
            say('issues add <test> --note "what is wrong" [--variant V] [--priority P2] [--name group]')
            return 1
        s = {x["id"]: x for x in catalog.load()}.get(a.test, {})
        k = f"{a.test}|{a.variant or (s.get('variants') or ['-'])[0]}"
        area = next((t[6:] for t in s.get("tags", []) if t.startswith("suite:") and t not in ("suite:smoke",
                                                                                            "suite:seam-core")), "review")
        e = led.get(k) if led.get(k, {}).get("state") in ACTIVE else None
        e = e or {"test": k, "area": area, "opened": now, "count": 0, "state": "open", "visual": True,
                  "history": led.get(k, {}).get("history", [])}
        e.update(lastSeen=now, count=e["count"] + 1, status=e.get("status") or "review", why=a.note[:300],
                 head=git_head(), reportedBy=a.by or "reviewer")
        if a.priority:
            e["priority"] = a.priority.upper()
        if a.name:
            e["group"] = a.name
        led[k] = e
        save_issues(led)
        say(f"{k}: open ({e['why'][:80]})")
        return 0
    if act in ("prioritize", "group"):
        if not a.test:
            say(f"issues {act} needs a test id")
            return 1
        for k in find(a.test):
            if act == "prioritize":
                led[k]["priority"] = (a.priority or "").upper() or None
            else:
                led[k]["group"] = a.name or None
            say(f"{k}: priority {led[k].get('priority') or 'auto'}, group {led[k].get('group') or 'auto'}")
        save_issues(led)
        return 0
    if act in ("claim", "fixed", "confirm", "reopen", "note"):
        if not a.test:
            say(f"issues {act} needs a test id")
            return 1
        for k in find(a.test):
            e = led[k]
            if act == "claim":
                e.update(state="in-progress", by=a.by or os.environ.get("USERNAME", "agent"), claimed=now)
            elif act == "fixed":
                e.update(state="testing", fixNote=a.note or "", fixHead=git_head(), fixClaimed=now,
                         fixedBy=a.by or e.get("by") or os.environ.get("USERNAME", "agent"))
            elif act == "confirm":
                # a confirmation is a claim about pictures: it names them, and the one who fixed it does not grade it
                if not a.evidence or not Path(a.evidence).exists():
                    say(f"{k}: confirm needs --evidence <image the claim is about> (e.g. the run's agent page or a "
                        f"zoom / .twin.png side-by-side showing exactly the two pictures compared)")
                    return 1
                if not a.by or a.by == e.get("by") or a.by == e.get("fixedBy"):
                    say(f"{k}: confirm needs --by REVIEWER other than who fixed it (the visual-reviewer or the owner)")
                    return 1
                e.update(state="fixed", fixed=now, fixedAt=git_head(), confirmedBy=a.by, evidence=str(a.evidence))
                e.setdefault("history", []).append({"opened": e["opened"], "fixed": now, "why": e.get("why", "")[:160]})
            elif act == "reopen":
                e.update(state="open")
            if a.note:
                e.setdefault("notes", []).append(f"{now} {act}: {a.note}")
            say(f"{k}: {e['state']}")
        save_issues(led)
        return 0
    if act == "verify":  # re-run every test an agent marked fixed; the results move the issues on
        keys = [k for k, e in led.items() if e.get("state") == "testing"]
        if not keys:
            say("nothing waiting for verification (issues fixed <test> marks one)")
            return 0
        a.ids = sorted({k.split("|")[0] for k in keys})
        a.suite, a.tag, a.tier, a.group, a.changed, a.exclude, a.variant, a.limit = (None,) * 7 + (0,)
        a.force, a.keep, a.rebuild, a.include_quarantined, a.fresh_world, a.seed = True, False, False, True, False, DEFAULT_SEED
        a.reloads = []
        say(f"verifying {len(keys)} fix(es): " + ", ".join(keys))
        cmd_run(a)
        led = load_issues()
        for k in keys:
            say(f"  {k}: {led.get(k, {}).get('state')}")
        return 0
    states = {}
    for e in led.values():
        states.setdefault(e.get("state"), []).append(e)
    groups = {}
    for e in led.values():
        if e.get("state") in ACTIVE:
            groups.setdefault(cause(e), []).append(e)
    lines = [f"# Issues: " + ", ".join(f"{len(states.get(s, []))} {s}" for s in ACTIVE + ("fixed",))
             + f" ({issue_stats().get('fixedTotal', 0)} older fixes cleared)", ""]
    for state in ("needs-review", "testing", "in-progress"):
        es = states.get(state, [])
        if es:
            lines.append(f"## {state} ({len(es)})")
            for e in es:
                extra = f" by {e['by']}" if e.get("by") else ""
                extra += f" (fix: {e.get('fixNote', '')[:60]})" if e.get("fixNote") else ""
                lines.append(f"- {e['test']}{extra}: {e.get('why', '')[:120]}")
            lines.append("")
    opened = states.get("open", [])
    if opened:
        lines.append(f"## open ({len(opened)}), by priority and cause")
        by_cause = {}
        for e in opened:
            by_cause.setdefault(cause(e), []).append(e)
        rows = {(min(issue_priority(e, groups) for e in es), why): es for why, es in by_cause.items()}  # a group
        for (prio, why), es in sorted(rows.items(), key=lambda kv: (kv[0][0], -len(kv[1]))):     # at its top priority
            es.sort(key=lambda e: SEVERITY.get(e.get("status"), 9))
            head = f"- {prio} [{es[0].get('area')}] {why}"
            if len(es) == 1:
                lines.append(f"{head}  ({es[0]['test']}, x{es[0]['count']}, since {es[0]['opened']})")
            else:
                lines.append(f"{head}  ({len(es)} tests)")
                for e in es[:8]:
                    lines.append(f"    - {e['test']} (x{e['count']}, since {e['opened']})")
                if len(es) > 8:
                    lines.append(f"    - ... {len(es) - 8} more")
        lines.append("")
    fixed = sorted(states.get("fixed", []), key=lambda e: e.get("fixed", ""), reverse=True)
    lines.append(f"## Recently fixed ({len(fixed)})")
    for e in fixed[:a.limit_fixed]:
        lines.append(f"- {e['test']}: fixed {e.get('fixed')} at {e.get('fixedAt')} (opened {e.get('opened')}): "
                     f"{e.get('why', '')[:120]}")
    text = "\n".join(lines) + "\n"
    if a.md:
        out = STORE / "ISSUES.md"
        out.write_text(text, encoding="utf-8")
        say(f"written {out}")
    else:
        say(text)


def cmd_wait(a):
    """Block until one of the given events happens (agents: run this in the background and get woken by it).
    Events: run_finished, test_failed, test_crashed, client_crashed, client_closed, client_restarted, test_quarantined,
    group_skipped, run_stopped."""
    kinds = set(a.events.split(","))
    start = EVENTS.stat().st_size if EVENTS.exists() and not a.past else 0
    t0 = time.monotonic()
    while a.timeout <= 0 or time.monotonic() - t0 < a.timeout:
        if EVENTS.exists() and EVENTS.stat().st_size > start:
            with open(EVENTS, encoding="utf-8") as f:
                f.seek(start)
                chunk = f.read()
            start += len(chunk.encode("utf-8"))
            for line in chunk.splitlines():
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                if ev.get("event") in kinds:
                    say(json.dumps(ev))
                    return 0
        time.sleep(2)
    say(f"no {a.events} within {a.timeout} s")
    return 1


# ================================================================================================ independent review
def review_claims(keys=None, model="haiku"):
    """Hand every needs-review issue (or the given keys) to the independent reviewer; its verdicts move the issues:
    pass -> fixed (confirmed by the reviewer, the evidence kept), fail -> open again, unsure -> stays for the owner."""
    import review
    led = load_issues()
    keys = keys or [k for k, e in led.items() if e.get("state") == "needs-review"]
    items = []
    out = STORE / "review" / "evidence"
    out.mkdir(parents=True, exist_ok=True)
    for k in keys:
        e = led.get(k)
        if not e:
            continue
        sid, v = k.rsplit("|", 1)
        imgs = []
        sheet = results.compare(sid, v)
        if sheet is not None:
            f = out / f"{sid}--{v}.png"
            sheet.save(f)
            imgs.append(f)
        tw = Path(f"{results.stem(sid, v)}.twin.png")
        if tw.exists():
            imgs.append(tw)
        if not imgs:
            continue
        claim = (f"the problem '{e.get('why', '')[:200]}' is gone in AFTER"
                 + (f" (claimed fix: {e['fixNote'][:160]})" if e.get("fixNote") else ""))
        items.append({"key": k, "title": sid, "claim": claim, "images": [str(i) for i in imgs]})
    if not items:
        say("review: nothing to review")
        return {}
    say(f"review: {len(items)} claim(s) to the independent reviewer ({model})")
    verdicts = review.claims(items, model)
    led = load_issues()
    now = time.strftime("%Y-%m-%d %H:%M")
    by = f"ai-reviewer:{model}"
    for it in items:
        k, vd = it["key"], verdicts.get(it["key"], {})
        e = led.get(k)
        if not e:
            continue
        verdict, why = vd.get("verdict", "unsure"), vd.get("reason", "")[:240]
        e.setdefault("reviews", []).append({"at": now, "by": by, "verdict": verdict, "reason": why,
                                            "evidence": it["images"]})
        if verdict == "pass" and by not in (e.get("by"), e.get("fixedBy")):
            e.update(state="fixed", fixed=now, fixedAt=git_head(), confirmedBy=by, evidence=it["images"][0])
            e.setdefault("history", []).append({"opened": e["opened"], "fixed": now, "why": e.get("why", "")[:160]})
        elif verdict == "fail":
            e["state"] = "open"
            e.setdefault("notes", []).append(f"{now}: {by} rejected the fix: {why}")
        else:
            e.setdefault("notes", []).append(f"{now}: {by} unsure ({why}); the owner should look")
        say(f"  {k}: {verdict}: {why[:100]}")
    save_issues(led)
    event("review_finished", items=len(items), verdicts={k: v.get("verdict") for k, v in verdicts.items()})
    return verdicts


def review_pages(agent_dir, context="", model="haiku"):
    """The independent visual pass over a run's agent pages: BROKEN / REGRESSION become tracked issues."""
    import review
    found = review.pages(agent_dir, model, context)
    # a second, independent look before a finding counts: the test's own before | after sheet, asked only whether the
    # named defect is really in the picture (a page-level glance misreads layouts; one reviewer alone never blocks)
    out = STORE / "review" / "evidence"
    out.mkdir(parents=True, exist_ok=True)
    asks = []
    for kind, test, v, what in found:
        sheet = results.compare(test, v or "shaders") if kind != "CHECK" else None
        if sheet is not None:
            f = out / f"{test}--{v or 'shaders'}.png"
            sheet.save(f)
            asks.append({"key": f"{test}|{v or 'shaders'}", "title": test, "images": [str(f)],
                         "claim": f"this defect is really visible in AFTER: {what[:200]}"})
    second = review.claims(asks, model) if asks else {}
    confirmed = []
    for kind, test, v, what in found:
        k = f"{test}|{v or 'shaders'}"
        if kind != "CHECK" and second.get(k, {}).get("verdict") != "pass":
            kind = "CHECK"  # not confirmed by the second look: a note to look at, never an issue or a block
            what = f"(unconfirmed) {what}"
        confirmed.append((kind, test, v, what))
    found = confirmed
    led = load_issues()
    now = time.strftime("%Y-%m-%d %H:%M")
    for kind, test, v, what in found:
        if kind == "CHECK":
            continue
        k = f"{test}|{v or 'shaders'}"
        e = led.get(k) if led.get(k, {}).get("state") in ACTIVE else None
        e = e or {"test": k, "area": "review", "opened": now, "count": 0, "state": "open", "visual": True,
                  "status": "review", "history": led.get(k, {}).get("history", [])}
        e.update(lastSeen=now, count=e["count"] + 1, why=f"{kind}: {what}"[:300], reportedBy=f"ai-reviewer:{model}")
        led[k] = e
    save_issues(led)
    return found


def cmd_review(a):
    """The independent reviewer by hand: claims (default: every needs-review issue) or --pages DIR."""
    import review
    if not review.available():
        say("review: the claude CLI is not on PATH")
        return 1
    if a.pages:
        found = review_pages(a.pages, a.note or "", a.model)
        for f in found:
            say(f"  {f[0]} {f[1]} [{f[2]}]: {f[3]}")
        say(f"review: {len(found)} finding(s); BROKEN / REGRESSION are in harness.py issues")
        return 0
    keys = [k for k in load_issues() if any(k == t or k.split("|")[0] == t for t in a.ids)] if a.ids else None
    review_claims(keys, a.model)
    return 0


# ================================================================================================ autonomous loop
def cmd_auto(a):
    """The harness on its own: verify fixes agents marked done, let the independent reviewer grade them, audit a
    budgeted sample, review its pictures, write ISSUES.md, notify. Waits while the GPU is busy (the owner may be
    playing) or another run owns the client. --once: one cycle (for a scheduled task)."""
    import review
    while True:
        if gpu_busy() > 40 or OWNER.exists():
            say(f"auto: busy (GPU {gpu_busy()}% or a run owns the client); next look in 10 min")
            if a.once:
                return 0
            time.sleep(600)
            continue
        cycle = time.strftime("%Y-%m-%d %H:%M")
        say(f"auto: cycle {cycle}")
        if any(e.get("state") == "testing" for e in load_issues().values()):
            sub = argparse.Namespace(**vars(a))
            sub.action, sub.test, sub.md = "verify", None, False
            cmd_issues(sub)
        if review.available():
            review_claims(model=a.model)
        sub = argparse.Namespace(**vars(a))
        sub.theme, sub.focus, sub.dry, sub.seed_audit = None, None, False, None
        cmd_audit(sub)
        agent_dir = results.REPORTS / f"audit-{a.budget}" / "agent"
        if review.available() and (agent_dir / "index.txt").exists():
            review_pages(agent_dir, "scheduled audit", a.model)
        sub = argparse.Namespace(**vars(a))
        sub.action, sub.test, sub.md = "list", None, True
        cmd_issues(sub)
        led = load_issues()
        event("auto_cycle", at=cycle, open=sum(1 for e in led.values() if e.get("state") == "open"),
              review=sum(1 for e in led.values() if e.get("state") == "needs-review"))
        if a.once:
            return 0
        time.sleep(a.every * 60)


# ================================================================================================ subject sheets
SUBJECT_TAGS = ("gallery:entity:", "gallery:block:", "gallery:material:", "flora:", "place:", "subject:", "boss:")


def subject_roles(subject):
    """Every captured view of one subject: front / side / animation / other (effects, distance, places)."""
    sid = subject.replace("_", "-")
    roles = []
    for s in catalog.load():
        tags = s.get("tags", [])
        if not (any(t.startswith(p) and t[len(p):] == subject for p in SUBJECT_TAGS for t in tags)
                or s["id"] in (f"gallery-{sid}", f"gallery-{sid}-side", f"anim-{sid}")
                or s["id"].startswith((f"dist-actor-{sid}-", f"pop-actor-{sid}-"))):
            continue
        role = s.get("role") or ("front" if s["id"] == f"gallery-{sid}" else "side" if s["id"] == f"gallery-{sid}-side"
                                 else "animation" if s["id"] == f"anim-{sid}" or "suite:animation" in tags else "other")
        roles.append((role, s["id"], s.get("title", s["id"])))
    return roles


def cmd_sheet(a):
    """A subject's sheet (front, side, animation, effects, distance) at a level of detail for people and reviewers."""
    roles = subject_roles(a.subject)
    if not roles:
        say(f"sheet: no scenes for {a.subject} (an entity / block / material / flora / place id)")
        return 1
    paths = results.subject_sheet(a.subject, roles, a.level, a.view, a.top)
    if not paths:
        missing = " ".join(sorted({sid for _, sid, _ in roles})[:8])
        say(f"sheet: nothing captured yet for {a.subject}; run: harness.py run {missing}")
        return 1
    for f in paths:
        say(str(f))
    return 0


def cmd_card(a):
    """A subject's card (basic / mid / full); with --kind, the cards of every subject of that kind."""
    import cards
    subjects_ = [a.subject] if a.subject else []
    if a.kind:
        idx = catalog.subject_index()
        for section in ("entities", "items", "blocks", "spells"):
            subjects_ += [r["id"] for r in idx.get(section, []) if r.get("kind") == a.kind or section == a.kind]
        subjects_ += idx.get(a.kind, []) if isinstance(idx.get(a.kind), list) and idx.get(a.kind) and \
            isinstance(idx[a.kind][0], str) else []
    if not subjects_:
        say("card: a subject id, or --kind (e.g. weapon, flora, monster, npcRaces, roles)")
        return 1
    for sub in subjects_[:a.limit or None]:
        roles = subject_roles(sub)
        paths = cards.card(sub, roles, a.level)
        say(f"{sub}: " + ", ".join(p.name for p in paths))
    say(f"cards in {cards.CARDS}")
    return 0


def cmd_gaps(a):
    """What is not tested: subjects whose views were never captured / fail / flag, screens no scene opens."""
    import gaps
    cov = gaps.coverage(a.kind)
    never = {k: e for k, e in cov.items() if e["never"]}
    failing = {k: e for k, e in cov.items() if e["failing"]}
    by_kind = {}
    for k, e in cov.items():
        b = by_kind.setdefault(e["kind"], [0, 0, 0])
        b[0] += 1
        b[1] += 1 if not e["never"] else 0
        b[2] += 1 if e["failing"] else 0
    lines = ["# Test gaps", "", f"{len(cov)} subject(s); {len(never)} with views never captured; {len(failing)} failing", "",
             "| kind | subjects | fully captured | failing |", "| --- | --- | --- | --- |"]
    lines += [f"| {k} | {n} | {c} | {f} |" for k, (n, c, f) in sorted(by_kind.items())]
    scr = gaps.screens()
    unc = [s for s in scr if not s[2]]
    lines += ["", f"## screens: {len(scr) - len(unc)}/{len(scr)} opened by a scene or listed in docs/UI-COVERAGE.md", ""]
    lines += [f"- {n} ({f})" for n, f, _ in unc[:80]]
    if a.verbose:
        lines += ["", "## never captured"] + [f"- {k} ({e['kind']}): {len(e['never'])} view(s)" for k, e in
                                               sorted(never.items())[:300]]
    stale = not catalog.SUBJECT_INDEX.exists()
    lines += ["", "the subject index is missing: harness.py run subject-index" if stale else
              "run a kind: harness.py run --tag kind:<kind>; one subject: --tag subject:<id>"]
    text = "\n".join(lines) + "\n"
    if a.md:
        (STORE / "GAPS.md").write_text(text, encoding="utf-8")
        say(f"written {STORE / 'GAPS.md'}")
    else:
        say(text)
    return 0


def cmd_recommend(a):
    """Before a commit: the tests this change affects, the new ids it brings and what they need, the screens it
    touches, and whether the subject index must be refreshed. --install-hook: an advisory pre-commit hook."""
    import gaps
    if a.install_hook:
        hook = ROOT / ".git/hooks/pre-commit"
        hook.write_text("#!/bin/sh\n# NirnCraft harness: what this commit needs tested (advisory, never blocks)\n"
                        "py -3 tools/harness/harness.py recommend --staged || true\n", encoding="utf-8", newline="\n")
        say(f"pre-commit hook installed (advisory): {hook}")
        return 0
    if a.staged:
        files = subprocess.run(["git", "diff", "--name-only", "--cached"], cwd=ROOT, capture_output=True,
                               text=True).stdout.split()
    else:
        files = changed_files(a.base)
    tags, _ = impact(files)
    tests = []
    if tags:
        sel = argparse.Namespace(ids=[], suite=None, tag=[",".join(sorted(tags))], tier=None, group=None, variant=None,
                                 changed=None, exclude="suite:places-planned,suite:animation,suite:subjects", limit=0,
                                 seed=DEFAULT_SEED)
        tests = [s["id"] for s in select(catalog.load(), sel)]
    r = gaps.recommend(files, tests, a.base, a.staged)
    say(f"recommend: {len(files)} changed file(s) -> {len(tests)} test(s) affected")
    if tests:
        tiers = {}
        for s in catalog.load():
            if s["id"] in set(tests):
                tiers[s["tier"]] = tiers.get(s["tier"], 0) + 1
        say("  " + ", ".join(f"{n} {t}" for t, n in tiers.items()) + "  ->  harness.py run --changed "
            + ("(or: harness.py gate)" if a.staged else ""))
    if r["newIds"]:
        say(f"  new ids the game index does not know yet ({len(r['newIds'])}): "
            + ", ".join(i for i, _ in r["newIds"][:12]))
        say("    -> harness.py run subject-index, then harness.py run --tag subject:<id> (scenes and a card appear "
            "by themselves); check the registration contract (check-contracts)")
    elif r["indexStale"]:
        say("  Java changed since the subject index was built: harness.py run subject-index")
    for n, f, covered in r["screens"]:
        say(f"  screen {n} ({f}): " + ("covered" if covered else "NO scene opens it: add a ui scene or a "
                                                                 "docs/UI-COVERAGE.md row"))
    if not (tests or r["newIds"] or r["screens"]):
        say("  nothing testable changed")
    return 0


def cmd_scenario(a):
    """`scenario new NAME`: a behaviour scenario template to fill in; `scenario list`: the scenarios there are."""
    import behaviour
    if a.action == "new":
        if not a.name:
            say("scenario new NAME")
            return 1
        f = behaviour.SCENARIOS / f"{a.name}.json"
        if f.exists():
            say(f"{f} exists")
            return 1
        behaviour.SCENARIOS.mkdir(parents=True, exist_ok=True)
        tpl = dict(behaviour.TEMPLATE, id=a.name)
        f.write_text(json.dumps(tpl, indent=2) + "\n", encoding="utf-8")
        say(f"written {f}: set the actors and expectations, then: harness.py run behaviour-{a.name}")
        return 0
    for sc in behaviour.load():
        say(f"  behaviour-{sc['id']:30} {sc.get('title', '')[:70]}" + (f"  BROKEN: {sc['_broken']}" if sc.get("_broken") else ""))
    return 0


def cmd_library(a):
    """The camera library: import cameras saved in game (/nirncraft-tour save NAME), list them, suggest places
    that still need one."""
    import camlib
    if a.action == "import":
        files = camlib.saved_files()
        if not files:
            say("no nirncraft-cameras.jsonl in any Prism instance yet: in game, /nirncraft-tour save NAME [note]")
            return 0
        added, skipped = camlib.import_saved(a.replace)
        say(f"library: {len(added)} added ({', '.join(added[:10])}), {len(skipped)} already there "
            f"({camlib.LIBRARY}); run them: harness.py run --suite camera-library")
    elif a.action == "list":
        for e in camlib.load():
            say(f"  cam-{e['name']:28} {e.get('place') or '-':24} {e.get('biome', '-'):28} {e.get('note', '')[:50]}")
    else:
        rows = camlib.suggest(a.limit or 40)
        say(f"{len(rows)} place(s) without a good camera (no library camera, planned views see < 60%):")
        for radius, pid, kind, best in rows:
            say(f"  {pid:32} {kind:14} radius {radius:4}  best planned view "
                + ("not planned yet (harness.py cameras)" if best is None else f"{best:.0%}"))
        say("go there in game and /nirncraft-tour save <name> <note>, then harness.py library import")
    return 0


def main():
    global VERBOSE
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def selection(p):
        p.add_argument("ids", nargs="*", help="test ids or globs")
        p.add_argument("--suite")
        p.add_argument("--tag", action="append")
        p.add_argument("--exclude")
        p.add_argument("--tier")
        p.add_argument("--group")
        p.add_argument("--variant")
        p.add_argument("--offline", action="store_true",
                       help="never the game: live scenes are left out and never pulled in as another scene's inputs")
        p.add_argument("--changed", nargs="?", const="HEAD", default=None, help="tests touched by changes since REF")
        p.add_argument("--limit", type=int, default=0)
        p.add_argument("--seed", type=int, default=DEFAULT_SEED)
        p.add_argument("-v", "--verbose", action="store_true")
        return p

    selection(sub.add_parser("list")).add_argument("--ids-only", action="store_true")
    selection(sub.add_parser("plan")).add_argument("--exact", action="store_true", help="fingerprint live items too (reads the instance)")
    for name in ("run", "watch"):
        p = selection(sub.add_parser(name))
        p.add_argument("--force", action="store_true", help="ignore cached results")
        p.add_argument("--keep", action="store_true", help="leave a client this run launched open")
        p.add_argument("--rebuild", action="store_true", help="rebuild + install the jar when Java changed")
        p.add_argument("--include-quarantined", action="store_true", help="also run tests quarantined for failing")
        p.add_argument("--fresh-world", action="store_true", help="make the kept test world again (else reused)")
        p.add_argument("--relaunch", action="store_true", help="atlas-route: twice across a client restart on one cache")
        p.add_argument("--cold", action="store_true", help="atlas gates: wipe atlas's caches first (a first visit)")
        p.add_argument("--gallery-filter", help="atlas-gallery: block ids / group ids (globs, comma = or), e.g. 'minecraft:grass_block,minecraft:oak_*'")
        p.add_argument("--gallery-limit", type=int, default=0, help="atlas-gallery: test this many groups, spread through the registry")
        p.add_argument("--gallery-settle", type=int, default=0, help="atlas-gallery: rendered frames at the new camera before each shot (default 3)")
        p.add_argument("--gallery-all", action="store_true", help="atlas-gallery-sweep: every group (default: 96 spread through the registry)")
    selection(sub.add_parser("serve"))
    sub.add_parser("status")
    selection(sub.add_parser("report"))
    selection(sub.add_parser("cameras"))
    p = sub.add_parser("features")
    p.add_argument("--seed", type=int, default=DEFAULT_SEED)
    p.add_argument("--threads", type=int, default=8)
    p.add_argument("--per", type=int, default=6, help="spots per kind")
    p.add_argument("--force", action="store_true", help="make the index again")
    p = selection(sub.add_parser("export-tour"))
    p.add_argument("name")
    p.add_argument("--to", action="append", help="a .minecraft folder (default: the preview instance)")
    p = selection(sub.add_parser("probe"))
    p.add_argument("name")
    p.add_argument("--title")
    p.add_argument("--visit", help="x,z,heightAboveSurface,yaw,pitch")
    p.add_argument("--camera", help="x,y,z,lookX,lookY,lookZ")
    p.add_argument("--place")
    p.add_argument("--shot", choices=["aerial", "approach"])
    p.add_argument("--stage", help="camera distance on the gallery sky stage")
    p.add_argument("--time", type=int)
    p.add_argument("--weather")
    p.add_argument("--setup", action="append", help="live verb before capture (repeatable)")
    p.add_argument("--cleanup", action="append")
    p.add_argument("--variants")
    p.add_argument("--frames", help="count,ticks: an animation filmstrip")
    p.add_argument("--capture-ui", action="store_true")
    p.add_argument("--probe-tag", action="append")
    p.add_argument("--keep", action="store_true")
    p.add_argument("--rebuild", action="store_true")
    p = sub.add_parser("zoom")
    p.add_argument("id")
    p.add_argument("--variant")
    p.add_argument("--crop", help="x,y,w,h in pixels of the after image")
    p.add_argument("--cell", help="c,r of a 3x3 grid (0..2)")
    p.add_argument("--which", default="both", choices=["both", "before", "after"])
    p = selection(sub.add_parser("gate"))
    p.add_argument("--base", default="origin/main")
    p.add_argument("--live", action="store_true", default=True)
    p.add_argument("--no-live", dest="live", action="store_false", help="checks + lab only (fast; live unverified)")
    p.add_argument("--no-ai-review", dest="ai_review", action="store_false", default=True,
                   help="skip the independent reviewer's pass over the pictures")
    p.add_argument("--model", default="haiku", help="the independent reviewer's model")
    p.add_argument("--dry", action="store_true")
    p.add_argument("--install-hook", action="store_true")
    p.add_argument("--keep", action="store_true")
    p.add_argument("--rebuild", action="store_true")
    p.add_argument("--force", action="store_true")
    sub.add_parser("open").add_argument("what", nargs="?", help="nothing: the overview; suite-smoke: that report; suite-smoke/agent/page-01.png")
    p = selection(sub.add_parser("audit"))
    p.add_argument("--budget", default="30m", help="5m, 30m, 1h, 2h or minutes (e.g. 45m)")
    p.add_argument("--audit-seed", dest="seed_audit", type=int, default=None, help="repeat a sample")
    p.add_argument("--focus", help="only tests with these tags (comma = or), e.g. feature:farfield")
    p.add_argument("--theme", help="a named slice (tools/harness/themes.json): dungeons, landscape, flora, towns, "
                                   "weather, renderer, lights, creatures, gear, fx, ui, content, realms; list")
    p.add_argument("--dry", action="store_true", help="show the sample, run nothing")
    p.add_argument("--keep", action="store_true")
    p.add_argument("--rebuild", action="store_true")
    p.add_argument("--force", action="store_true")
    p.add_argument("--include-quarantined", action="store_true")
    p = sub.add_parser("trace")
    p.add_argument("test", nargs="?", help="test id or test|variant (default: the end of the run)")
    p.add_argument("--before", type=int, default=15, help="steps before the test's first step")
    p.add_argument("--last", type=int, default=40)
    p.add_argument("--previous", action="store_true", help="the run before this one")
    p.add_argument("--no-game-log", action="store_true")
    sub.add_parser("stop").add_argument("--after-test", action="store_true", help="let the current test finish")
    p = sub.add_parser("sheet")
    p.add_argument("subject", help="entity / block / material / flora / place id, e.g. dragon, draugr, iron")
    p.add_argument("--level", choices=["full", "top", "pair", "single"], default="top")
    p.add_argument("--view", choices=["front", "side"], default="front", help="single: which view")
    p.add_argument("--top", type=int, default=2, help="top: how many other views (flagged first)")
    p = sub.add_parser("library")
    p.add_argument("action", choices=["import", "list", "suggest"])
    p.add_argument("--replace", action="store_true", help="import: replace cameras with the same name")
    p.add_argument("--limit", type=int, default=40)
    p = sub.add_parser("scenario")
    p.add_argument("action", choices=["new", "list"])
    p.add_argument("name", nargs="?")
    p = sub.add_parser("gaps")
    p.add_argument("--kind", help="one kind of subject")
    p.add_argument("--md", action="store_true", help="write .work/harness/GAPS.md")
    p.add_argument("-v", "--verbose", action="store_true")
    p = sub.add_parser("recommend")
    p.add_argument("--staged", action="store_true", help="what is staged for the next commit")
    p.add_argument("--base", default="HEAD", help="changes since this ref (default: uncommitted)")
    p.add_argument("--install-hook", action="store_true", help="advisory git pre-commit hook")
    p = sub.add_parser("card")
    p.add_argument("subject", nargs="?")
    p.add_argument("--kind", help="every subject of a kind (weapon, armour, flora, monster, roles, npcRaces, ...)")
    p.add_argument("--level", choices=["basic", "mid", "full"], default="mid")
    p.add_argument("--limit", type=int, default=0)
    p = sub.add_parser("review")
    p.add_argument("ids", nargs="*", help="issues to review (default: every needs-review issue)")
    p.add_argument("--pages", help="review a run's agent folder instead (BROKEN / REGRESSION become issues)")
    p.add_argument("--note", help="pages: what changed")
    p.add_argument("--model", default="haiku")
    p = selection(sub.add_parser("auto"))
    p.add_argument("--once", action="store_true", help="one cycle (a scheduled task)")
    p.add_argument("--every", type=int, default=120, help="minutes between cycles")
    p.add_argument("--budget", default="30m")
    p.add_argument("--model", default="haiku")
    p.add_argument("--keep", action="store_true")
    p.add_argument("--rebuild", action="store_true")
    p.add_argument("--force", action="store_true")
    p = sub.add_parser("wait")
    p.add_argument("--events", default="run_finished,client_crashed,run_stopped")
    p.add_argument("--timeout", type=int, default=0, help="seconds (0: no limit)")
    p.add_argument("--past", action="store_true", help="also match events already in the log")
    p = sub.add_parser("issues")
    p.add_argument("action", nargs="?", choices=["list", "claim", "fixed", "verify", "confirm", "reopen", "note",
                                                 "prioritize", "group", "add"])
    p.add_argument("--variant", help="add: the variant (default: the test's first)")
    p.add_argument("--evidence", help="confirm: the image the confirmation is about (required)")
    p.add_argument("--priority", help="prioritize: P1..P4 (empty: automatic)")
    p.add_argument("--name", help="group: a cause name to group under (empty: automatic)")
    p.add_argument("test", nargs="?", help="test id (or test|variant)")
    p.add_argument("--by", help="who (claim / confirm)")
    p.add_argument("--note")
    p.add_argument("--md", action="store_true", help="write .work/harness/ISSUES.md")
    p.add_argument("--limit-fixed", type=int, default=30)
    p.add_argument("-v", "--verbose", action="store_true")
    p = sub.add_parser("quarantine")
    p.add_argument("--clear", nargs="*", help="test|variant keys (none: all)")
    sub.add_parser("seed-legacy")
    sub.add_parser("clean")
    a = ap.parse_args()
    if a.cmd in ("export-tour", "probe"):  # the name is positional too: keep it out of the id globs
        a.ids = [i for i in a.ids if i != a.name]
    VERBOSE = getattr(a, "verbose", False)
    a.reloads = []
    {"list": cmd_list, "plan": cmd_plan, "run": cmd_run, "watch": cmd_watch, "serve": cmd_serve,
     "status": cmd_status, "report": cmd_report, "cameras": cmd_cameras, "features": cmd_features, "export-tour": cmd_export_tour,
     "seed-legacy": cmd_seed_legacy, "clean": cmd_clean, "probe": cmd_probe, "zoom": cmd_zoom,
     "gate": cmd_gate, "open": cmd_open, "quarantine": cmd_quarantine, "trace": cmd_trace,
     "audit": cmd_audit, "stop": cmd_stop, "wait": cmd_wait, "issues": cmd_issues,
     "review": cmd_review, "auto": cmd_auto, "sheet": cmd_sheet, "card": cmd_card,
     "gaps": cmd_gaps, "recommend": cmd_recommend, "scenario": cmd_scenario,
     "library": cmd_library}[a.cmd](a)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except BaseException as e:  # the harness itself failed: the traceback goes into the trace for the next agent
        tr.error("harness_exception", e, argv=sys.argv[1:])
        raise
