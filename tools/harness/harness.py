#!/usr/bin/env python3
"""NirnCraft test harness: one entry point for every visual and non-visual test, for people and agents.

    py -3 tools/harness/harness.py list    [selection]              what exists (counts; --ids for the ids)
    py -3 tools/harness/harness.py plan    [selection]              what would run, in order, and what is cached
    py -3 tools/harness/harness.py run     [selection] [--force] [--keep] [--rebuild]
    py -3 tools/harness/harness.py watch   [selection] [--rebuild]  live reload loop: edit, see before|after in seconds
    py -3 tools/harness/harness.py serve                            stay up as the owner and run queued jobs
    py -3 tools/harness/harness.py status | report [selection] | cameras [selection] | export-tour NAME [selection]
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
import trace as tr  # noqa: E402
import catalog  # noqa: E402
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
    out = []
    for s in scenes:
        tags = s["tags"]
        if a.ids and not any(fnmatch.fnmatch(s["id"], p) for p in a.ids):
            continue
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
    if a.limit:
        out = out[: a.limit]
    return out


def items_of(scenes, a):
    out = []
    for s in scenes:
        for v in s["variants"] if s["tier"] == "live" else [s["tier"]]:  # measure scenes: variant "measure"
            if a.variant and v not in a.variant.split(","):
                continue
            out.append((s, v))
    return out


def key(s, v):
    return f"{s['id']}|{v}"


# ================================================================================================ ordering
def priority(s, v, pos, group, shaders):
    """Greedy next-item key: cheap tiers first; then stay in the running client's group, then in its shader state,
    then the nearest pose (chunks already loaded); realms last (they leave the overworld)."""
    tier = TIER_RANK.get(s["tier"], 3)
    if s["tier"] != "live":
        return (tier, 0, 0, 0, 0, s["id"])
    xz = s.get("_xz")
    p = s.get("pose") or {}
    dist = 0 if xz is None or pos is None else math.hypot(xz[0] - pos[0], xz[1] - pos[1])
    if xz is None and "spawn" not in p:
        dist = 1e7
    return (tier, s["group"] != group, s["group"] != "live", (v == "vanilla") == shaders, "realm" in p, dist, s["id"])


def order(items, pos=(0, 0), group="live", shaders=True):
    left, out = list(items), []
    while left:
        i = min(range(len(left)), key=lambda k: priority(*left[k], pos, group, shaders))
        s, v = left.pop(i)
        out.append((s, v))
        if s["tier"] == "live":
            pos = s.get("_xz") or pos
            group, shaders = s["group"], v != "vanilla"
    return out


# ================================================================================================ events, hooks, quarantine
EVENTS = STORE / "events.jsonl"
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
            return {**base, "exe": results_hash([exe, ROOT / "native/build/Release/atlas_core.dll"]),
                    "pack": tree_hash(ROOT / "shaderpack/InfiniteATLAS")}
        return {**base, "inputs": tree_hash(*[ROOT / g for g in s.get("inputs", [])])}

    def invalidate(self):
        self._inputs = None

    def fingerprint(self, s, v):
        return catalog.fingerprint(s, self.inputs(s, v))

    def cached(self, s, v, fp):
        rec = results.latest(s["id"], v)
        if rec and rec.get("fingerprint") == fp and rec.get("status") in ("pass", "flag", "skip"):
            return rec
        return None

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
        rec = self._run(s, v, fp)
        tr.step("test_end", status=rec.get("status"), seconds=round(time.monotonic() - t0, 1),
                error=(rec.get("error") or "")[:300])
        if rec.get("status") in ("error", "crash"):  # what led up to it travels with the result
            rec["trace"] = tr.tail(25)
            Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1, default=str),
                                                                       encoding="utf-8")
        tr.test(None)
        if rec.get("status") in ("error", "crash", "fail"):
            event("test_crashed" if rec["status"] == "crash" else "test_failed", test=k, status=rec["status"],
                  reason=(rec.get("error") or "; ".join(rec.get("fails", [])))[:300])
        note_outcome(k, fp, rec.get("status"), rec.get("error", ""))
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

    def run_live_restarting(self, s, v, fp):
        """A client crash during a test: keep the evidence next to the result, relaunch, and run the same test again
        once (it goes back to its own location); a second crash records it as `crash` and the run moves on."""
        for attempt in (1, 2):
            started = time.time()
            try:
                rec = self.run_live(s, v, fp)
                self.streak = ("", 0)
                return rec
            except Exception:
                if self._game is None or not self.game.crashed():
                    raise
                self.crashes += 1
                tr.step("client_crashed", attempt=attempt)
                path, cause = self.game.crash_evidence(results.stem(s["id"], v).with_name(f"{v}.crash.txt"), started)
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

    def trip(self, s, msg):
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
        lab.setdefault("w", 960)
        lab.setdefault("h", 540)
        with tempfile.TemporaryDirectory() as tmp:
            png = Path(tmp) / "lab.png"
            kv = [f"{k}={val}" for k, val in lab.items()]
            cmd = [str(exe), str(png)] + kv if tool == "pack_lab" else [str(exe), f"out={png}"] + kv
            r = subprocess.run(cmd, cwd=ROOT / "native", capture_output=True, text=True, timeout=900,
                               creationflags=0x00004000 if os.name == "nt" else 0)
            tr.step("lab_command", cmd=cmd, exit=r.returncode)
            if r.returncode or not png.exists():
                raise RuntimeError(f"{tool} failed: " + (r.stdout + r.stderr)[-800:])
            rec = results.store(s["id"], v, png, meta={"tool": tool, "args": kv}, fingerprint=fp)
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
        if s.get("measure_command"):
            return self.run_measure(s, v, fp)
        p = s.get("pose") or {}
        tries = 3 if p.get("shot") or "visit" in p or "camera" in p else 1  # next planned pose / higher
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
        self.pos = s.get("_xz") or self.pos
        extra = {"camera": cand} if p.get("shot") else {}
        if perf:
            extra["perf"] = {k: perf[k] for k in ("median", "p95", "p99", "stalls", "frames") if k in perf}
        rec = results.store(s["id"], v, png, meta=receipt, fingerprint=fp, extra=extra)
        if popm:  # distance ladders / pop sweeps: their numbers and flags join the image's
            flags = popm.pop("popFlags", [])
            rec["metrics"].update(popm)
            rec["metrics"]["flags"] = rec["metrics"].get("flags", []) + flags
        if receipt.get("seed") not in (None, self.seed):
            rec["metrics"]["flags"].append(f"world seed {receipt.get('seed')} != {self.seed}")
        return self.judge(s, v, rec)

    def run_measure(self, s, v, fp):
        """A live test without a picture: go there, settle, run a command that writes a JSON report, keep its numbers."""
        g = self.game
        out = g.GAME / s["measure_file"]
        out.unlink(missing_ok=True)
        try:
            g.actions(["ui close"] + catalog.pose_actions(s, self.plan) + ["settle 1800"] + list(s.get("setup", []))
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
        m = {name: json_sum(data, path) for name, path in (s.get("measure_sum") or {}).items()}
        if "floating" in m and "control" in m:
            m["floating"] -= m["control"]  # the plain-terrain control is not the place's fault
            m["problems"] = m["floating"]
        c = s.get("checks") or {}
        fails = [f"{k} {m.get(k)} > {lim}" for k, lim in (c.get("max") or {}).items() if (m.get(k) or 0) > lim]
        self.pos = s.get("_xz") or self.pos
        return results.record(s["id"], v, "measure", "fail" if fails else ("flag" if m.get("problems") else "pass"),
                              m, fp, extra={"fails": fails, "report": data if len(json.dumps(data)) < 20000 else {}})

    def judge(self, s, v, rec):
        m, c = rec["metrics"], s.get("checks") or {}
        fails = []
        if "maxBlackTiles" in c and m.get("blackBoxes", 0) > c["maxBlackTiles"]:  # boxes: the NaN signature
            fails.append(f"black boxes {m['blackBoxes']} > {c['maxBlackTiles']}")
        if "lumaMin" in c and m.get("luma", 0) < c["lumaMin"]:
            fails.append(f"luma {m['luma']} < {c['lumaMin']}")
        if "lumaMax" in c and m.get("luma", 0) > c["lumaMax"]:
            fails.append(f"luma {m['luma']} > {c['lumaMax']}")
        status = "fail" if fails else ("flag" if m.get("flags") else "pass")
        rec.update(status=status, fails=fails)
        results.stem(s["id"], v).with_suffix("")
        Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1), encoding="utf-8")
        return rec

    def close(self):
        """A run leaves nothing running: the client closes at the end (also after errors), whoever launched it; only
        --keep / watch / serve keep it for the next job."""
        if self._game is not None and not getattr(self.a, "keep", False) and self.game.running():
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
                labs = [(x, y) for x, y in live_items if x["tier"] == "lab"]
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
    scenes = select(catalog.load(), a)
    items = items_of(scenes, a)
    if not items:
        say("nothing selected")
        return 0
    keys = [key(s, v) for s, v in items]
    runner = Runner(a)
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
                    runner.close()
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
    bad, known = [], []
    for s, v in items:
        rec = results.latest(s["id"], v)
        if not rec:
            bad.append(f"{key(s, v)} (no result)")
        elif blocking(rec):
            bad.append(key(s, v))
        elif rec.get("status") in ("fail", "error"):
            known.append(key(s, v))
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    receipt = {"head": head, "base": base, "files": len(files), "tags": tags, "items": len(items), "blocking": bad,
               "knownFailures": known, "at": time.time()}
    (STORE / "gate").mkdir(parents=True, exist_ok=True)
    (STORE / "gate" / "latest.json").write_text(json.dumps(receipt, indent=1), encoding="utf-8")
    say(f"gate: {'FAIL' if bad else 'PASS'} ({len(bad)} new failure(s), {len(known)} known, {len(items)} test item(s))")
    for b in bad[:20]:
        say(f"  {b}")
    sys.exit(1 if bad else 0)


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
    selection(sub.add_parser("serve"))
    sub.add_parser("status")
    selection(sub.add_parser("report"))
    selection(sub.add_parser("cameras"))
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
    p.add_argument("--dry", action="store_true")
    p.add_argument("--install-hook", action="store_true")
    p.add_argument("--keep", action="store_true")
    p.add_argument("--rebuild", action="store_true")
    p.add_argument("--force", action="store_true")
    sub.add_parser("open").add_argument("what", nargs="?", help="nothing: the overview; suite-smoke: that report; suite-smoke/agent/page-01.png")
    p = sub.add_parser("trace")
    p.add_argument("test", nargs="?", help="test id or test|variant (default: the end of the run)")
    p.add_argument("--before", type=int, default=15, help="steps before the test's first step")
    p.add_argument("--last", type=int, default=40)
    p.add_argument("--previous", action="store_true", help="the run before this one")
    p.add_argument("--no-game-log", action="store_true")
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
     "status": cmd_status, "report": cmd_report, "cameras": cmd_cameras, "export-tour": cmd_export_tour,
     "seed-legacy": cmd_seed_legacy, "clean": cmd_clean, "probe": cmd_probe, "zoom": cmd_zoom,
     "gate": cmd_gate, "open": cmd_open, "quarantine": cmd_quarantine, "trace": cmd_trace}[a.cmd](a)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except BaseException as e:  # the harness itself failed: the traceback goes into the trace for the next agent
        tr.error("harness_exception", e, argv=sys.argv[1:])
        raise
