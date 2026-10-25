"""In-game gates for Infinite ATLAS (docs/ATLAS-PLAN.md stage 0, items 3 and 4), run through the harness:

    harness.py run atlas-route                  the scripted route: walk, sprint, fly 2 km, turn, stand still
    harness.py run atlas-route --relaunch       twice across a client restart on one cache (chunks generated on the second)
    harness.py run atlas-route --cold           wipe atlas's cache first (a first visit)
    harness.py run atlas-gallery                every block state group: vanilla raster vs atlas, 2 m and 16 m (atlas_gallery.py)

The route is one Java verb (AtlasRoute: `route SPEC.json`) that moves the player tick by tick and keeps the client's own
frame intervals and atlas's counters; this module writes the spec, runs it, judges it against the bar and writes the
one-page report. Results: .work/harness/atlas/route/<stamp>/ (report.html, report.json, charts, captures sheet) and
.../latest/; the harness result is atlas-route|atlas.
"""
import json
import math
import shutil
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / ".work/harness/atlas"

# the bar (docs/ATLAS-PLAN.md section 1): smooth and no holes, 1440p on the RTX 3090
REQUIRED = ["atlasMs", "loadedChunks", "genChunks", "farTiles", "farCached", "budgetFrameMs", "holes", "partial", "farReach",
            "stageCpuMs", "coldHits", "coldWrites", "ready"]  # AtlasProbe.REQUIRED: a gate never reports a counter it did not read
BAR = {"p99": 20.0, "max": 50.0, "holes": 0, "relaunchGenerated": 0, "fullRadiusMs": 2000}
START = (-150, 260, 30)          # x, z, blocks above the surface: open ground south of Whiterun (catalog spawn pose)
HEADING = -90.0                  # Minecraft yaw: -90 looks +x (east)
WINDOW = (2560, 1440)
TAG = "atlasroute"

PHASES = [
    {"name": "walk", "ticks": 600, "speed": 4.317, "mode": "ground", "pitch": 6},
    {"name": "sprint", "ticks": 400, "speed": 5.612, "mode": "ground", "pitch": 6},
    {"name": "fly", "ticks": 400, "speed": 100.0, "mode": "air", "climb": 90, "climbTicks": 40, "pitch": 8},
    {"name": "turn", "ticks": 240, "speed": 0.0, "mode": "hold", "yawTurn": 360, "pitch": 0},
    {"name": "still", "ticks": 200, "speed": 0.0, "mode": "hold", "pitch": 0},
]
CAPTURES = [560, 800, 990, 1060, 1200, 1390, 1460, 1520, 1640, 1830]
# the collision walk (ATLAS-GPU-LOOP step 12): a walk and a sprint, once a second atlas's level 0 under the player is read back from
# the GPU and compared with the client level's blocks (the server's chunks, which collisions read); bar: 0 mismatches
COLLIDE_PHASES = [
    {"name": "walk", "ticks": 600, "speed": 4.317, "mode": "ground", "pitch": 6, "collide": True},
    {"name": "sprint", "ticks": 400, "speed": 5.612, "mode": "ground", "pitch": 6, "collide": True},
]


def scenes():
    """The gate scenes (catalog.load adds them): live, in the atlas group, never served from a cached result."""
    common = {"tier": "live", "group": "atlas", "variants": ["atlas"], "atlas_tool": "route", "nocache": True,
              "tags": ["suite:atlas-gates", "feature:perf", "feature:atlasroute"], "timeout": 1500, "time": 6000,
              "weather": "off", "settle": False, "checks": {}, "kind": "atlas-route", "_file": "(atlas_tools.py)",
              "pose": {"visit": [START[0], START[1], START[2], HEADING, 0]}, "seed": None}
    out = [
        {**common, "id": "atlas-route",
         "title": "Scripted route in the real game: walk 30 s, sprint, fly 2 km, turn 360, stand still; frame times, holes, "
                  "generation and cache per second against the bar (p99 <= 20 ms, max <= 50 ms, holes 0)"},
        {**common, "id": "atlas-collide", "collide": True, "timeout": 900, "time": 6000,
         "title": "The walk and the sprint with atlas's level 0 under the player read back from the GPU against the client level's "
                  "blocks, once a second (bar: 0 mismatches)"},
        {**common, "id": "atlas-route-relaunch", "relaunch": True,
         "title": "The route twice across a client restart on one cache: chunks generated on the second run (bar 0) and "
                  "time to full radius (bar < 2 s)", "timeout": 3000},
    ]
    from atlas_gallery import scenes as gallery_scenes
    return out + gallery_scenes(common)


def run(runner, s, v, fp):
    if s["atlas_tool"] == "route":
        return run_route_scene(runner, s, v, fp)
    import atlas_gallery
    return atlas_gallery.run(runner, s, v, fp)


# ------------------------------------------------------------------------------------------------------------ the route
def route_spec(scene=None):
    if scene and scene.get("collide"):
        return {"tag": TAG, "heading": HEADING, "phases": COLLIDE_PHASES, "captures": [], "uncap": False, "collide": True}
    return {"tag": TAG, "heading": HEADING, "phases": PHASES, "captures": CAPTURES, "uncap": True,
            "window": list(WINDOW)}


def wipe_cache(g):
    """A first visit: atlas's caches emptied (client closed). The pipeline cache stays (the driver's, valid across builds)."""
    root = g.GAME / "atlas-cache"
    n = 0
    if root.exists():
        for p in sorted(root.rglob("*"), key=lambda q: len(q.parts), reverse=True):
            if p.name.startswith("pipelines.bin"):
                continue
            try:
                if p.is_dir():
                    p.rmdir()
                else:
                    p.unlink()
                    n += 1
            except OSError:
                pass
    for w in (g.GAME / "saves").glob("*/data/nirncraft_atlas"):
        shutil.rmtree(w, ignore_errors=True)
    return n


def wait_alive(g, limit=420):
    """Atlas's first frames after a new build compile its pipelines on the render thread (a cold driver cache: 90 s with
    no heartbeat). The harness' ready check passes before that and its next action then fails on a stale heartbeat;
    wait here until the client answers again and its world is up."""
    import live_world as lw
    t0, fresh = time.monotonic(), 0
    while time.monotonic() - t0 < limit:
        c = (lw.status() or {}).get("client", {})
        fresh = fresh + 1 if c.get("fresh") and c.get("worldLoaded") and not c.get("loading") else 0
        if fresh >= 4:
            return time.monotonic() - t0
        time.sleep(1.5)
    raise RuntimeError(f"the client did not answer within {limit} s (a pipeline compile that never ends?)")


def fly_route(runner, s, tag=TAG, say=print):
    """One run of the route in the running client; returns the parsed report (or raises)."""
    g = runner.game
    out_dir = g.GAME / "build/autotest-route"
    out_dir.mkdir(parents=True, exist_ok=True)
    report = out_dir / f"{tag}.json"
    settle = out_dir / "settle.json"
    report.unlink(missing_ok=True)
    settle.unlink(missing_ok=True)
    for f in g.SHOTS.glob(f"{tag}_c*"):
        f.unlink(missing_ok=True)
    spec = dict(route_spec(s), tag=tag)
    spec_file = out_dir / f"spec-{tag}.json"
    spec_file.write_text(json.dumps(spec), encoding="utf-8")
    x, z, up = START
    mark = g.log_mark()
    g.ensure_visible()
    wait_alive(g)
    lines = ["ui close", "perspective first", "hud off", "try time set 6000", "try nirncraft-weather fronts clear",
             "weather-off on", "try nirncraft-weather set-all clear 120",
             f"visit {x} {z} {up} {HEADING} 0 clear", "settle-atlas 3600 100",
             f"route {spec_file.as_posix()}"]
    import live_world as lw
    from osthreads import OsThreads
    pids = [p["pid"] for p in lw.processes()]
    osr = OsThreads(pids[0], 1.0).start() if pids else None  # every native thread's CPU, from outside the game
    try:
        g.actions(lines, timeout=s.get("timeout", 1500))
    except RuntimeError:
        g.recover(s)
        raise
    finally:
        if osr:
            osr.stop()
    if not report.exists():
        raise RuntimeError("the route wrote no report (" + str(report) + ")")
    data = json.loads(report.read_text(encoding="utf-8"))
    data["settle"] = json.loads(settle.read_text(encoding="utf-8")) if settle.exists() else {}
    if osr:
        marks = sorted((r["epochMs"], r["phase"]) for r in data["seconds"] if "epochMs" in r)

        def phase_of(t):
            if not marks or t < marks[0][0]:
                return "before the route (load, settle)"
            if t > marks[-1][0] + 21000:
                return "after the route"
            return [ph for e, ph in marks if e <= t][-1]
        data["osCpu"] = osr.report(phase_of)
    data["shots"] = []
    for c in data.get("captures", []):
        f = g.SHOTS / f"{c['tag']}.png"
        t0 = time.monotonic()
        last = -1
        while time.monotonic() - t0 < 30:  # screenshots encode off-thread
            size = f.stat().st_size if f.exists() else -1
            if size > 0 and size == last:
                break
            last = size
            time.sleep(0.4)
        if f.exists():
            data["shots"].append(str(f))
    log = g.log_since(mark)
    counts, serious = g.log_summary(log)
    data["log"] = {**counts, "serious": serious[:5], "partial": [l[-200:] for l in log if "(partial)" in l][:3]}
    return data


def delta(secs, key, sub="atlas"):
    """Per-second increments of a cumulative counter (None where a reading is missing)."""
    out, prev = [], None
    for r in secs:
        v = (r.get(sub) or {}).get(key)
        out.append(None if v is None or prev is None else max(0, v - prev))
        prev = v if v is not None else prev
    return out


def summarize(data):
    """The numbers of one run, judged against the bar."""
    secs = data["seconds"]
    tot = data["total"]["frame"]
    atlas = [(r.get("atlas") or {}) for r in secs]
    # a counter no reading had is not a number: the route fails on it ("metric X absent"), nothing is reported as -1 or 0
    absent = [k for k in REQUIRED if not any(k in a for a in atlas)] if any(atlas) else list(REQUIRED)
    absent = sorted(set(absent) | set(data.get("absentMetrics") or []))
    holes = [int(sum(a["holes"])) for a in atlas if a.get("holes") is not None]
    partial = [int(sum(a["partial"])) for a in atlas if a.get("partial") is not None]
    per_level = [max(a["holes"][k] for a in atlas if a.get("holes") and k < len(a["holes"])) for k in range(7)] if holes else None
    def series(key):
        return [a[key] for a in atlas if key in a]

    def last_of(key):
        v = series(key)
        return v[-1] if v else None

    def first_of(key):
        v = series(key)
        return v[0] if v else None

    def delta_of(key):
        return None if key in absent or not series(key) else max(0, last_of(key) - first_of(key))

    def top(key, nd=2):
        v = series(key)
        return round(max(v), nd) if v else None

    def num(x, nd=2):
        return None if x is None else round(x, nd)

    tiles_made = None
    if "farTiles" not in absent and "farCached" not in absent:
        tiles_made = int(last_of("farTiles") - last_of("farCached"))
    settle = data.get("settle") or {}
    s0 = settle.get("sample") or {}
    m = {
        "frames": tot.get("n"), "p50": round(tot.get("p50", 0), 2), "p95": round(tot.get("p95", 0), 2),
        "p99": round(tot.get("p99", 0), 2), "max": round(tot.get("max", 0), 2), "over50": tot.get("over50", 0),
        "absentMetrics": absent,
        "holesMax": max(holes) if holes else None, "holesAtEnd": holes[-1] if holes else None,
        "holesPerLevelMax": per_level, "partialMax": max(partial) if partial else None,
        "farReachMin": min(series("farReach")) if series("farReach") else None,
        "genChunks": delta_of("genChunks"), "genChunksTotal": last_of("genChunks"),
        "farTilesMadeTotal": tiles_made,
        "farTilesMade": None if tiles_made is None else max(0, tiles_made - int(first_of("farTiles") - first_of("farCached"))),
        "cachedChunks": last_of("cachedChunks"), "farCached": last_of("farCached"),
        "coldHits": last_of("coldHits"), "coldWrites": last_of("coldWrites"),
        "genCacheAbsent": bool(atlas and all(a.get("gcAbsent") for a in atlas if a)),
        "genCacheChunkHits": last_of("gcChunkHits"), "genCacheSectionWrites": last_of("gcSectionWrites"),
        "genCacheFarWrites": last_of("gcFarTileWrites"), "genCacheFarHits": last_of("gcFarTileHits"),
        "budgetLongestMs": top("budgetLongestMs"), "budgetLatelyMax": top("budgetLatelyMs"),
        "stageCpuMaxMs": top("stageCpuMs"), "stageCpuRunMaxMs": num(last_of("stageCpuMaxMs")),
        "atlasMsMax": top("atlasMs"),
        "atlasMsMean": num(sum(series("atlasMs")) / len(series("atlasMs"))) if series("atlasMs") else None,
        "infoMsMax": top("infoMs"),
        "settledMs": settle.get("ms"), "settledOk": settle.get("settled"),
        "timeToNoHolesMs": settle.get("timeToNoHolesMs"), "windowUpMs": s0.get("jumpWindowUpMs"),
        "firstUpMs": s0.get("jumpFirstUpMs"),
        "display": f"{data['display'].get('framebufferW')}x{data['display'].get('framebufferH')}",
        "phases": {k: {x: round(v.get("frame", {}).get(x, 0), 2) for x in ("p50", "p99", "max")}
                   for k, v in (data.get("phases") or {}).items()},
    }
    m["cpu"] = cpu_summary(data)
    cl = [r["collide"] for r in secs if isinstance(r.get("collide"), dict)]
    if cl:
        m.update({"collideSeconds": len(cl), "collideChecked": sum(c.get("checked", 0) for c in cl),
                  "collideMismatches": sum(c.get("mismatches", 0) for c in cl), "collideUnloaded": sum(c.get("unloaded", 0) for c in cl),
                  "collideErrors": [c["error"] for c in cl if "error" in c][:3],
                  "collideSamples": [x for c in cl for x in c.get("samples", [])][:12]})
    # the integrated server over the route (AtlasServerStats): ticks, worker CPU, chunks made by Java and by the GPU
    srv = data.get("server") or {}
    st = srv.get("tick") or {}
    m.update({
        "serverTickP50": round(st.get("p50", 0), 2), "serverTickP99": round(st.get("p99", 0), 2),
        "serverTickMax": round(st.get("max", 0), 2), "serverTickOver50": st.get("over50", 0), "serverTicks": st.get("n", 0),
        "serverWorkerCpuMsPerSec": round(srv.get("workerCpuMsPerSec", 0), 1),
        "serverThreadCpuMsPerSec": round(srv.get("serverThreadCpuMsPerSec", 0), 1),
        "processCpuMsPerSec": round(srv.get("processCpuMsPerSec", 0), 1),
        "serverNewChunks": srv.get("newChunks", 0), "serverLoadedChunks": srv.get("loadedChunksAtEnd", 0),
        "serverView": srv.get("viewDistance"), "serverListView": srv.get("playerListViewDistance"),
        "serverSim": srv.get("simulationDistance"), "serverCapped": srv.get("capped"),
        "serverGenerator": srv.get("generator"), "serverGpuChunks": srv.get("gpuChunks", 0),
        "serverJavaChunks": srv.get("javaChunks", 0), "serverJavaTerrainCalls": srv.get("javaTerrainCalls", 0),
        "serverJavaDecorationCalls": srv.get("javaDecorationCalls", 0), "serverGpuWaitMs": round(srv.get("gpuWaitMs", 0), 1),
        "serverGpuTimeouts": srv.get("gpuTimeouts", 0),
    })
    fails = [f"metric {k} absent" for k in absent]
    if cl:
        if m["collideErrors"] or m["collideChecked"] == 0:
            fails.append(f"collision check did not run: {m['collideErrors'] or 'nothing compared'}")
        if m["collideMismatches"]:
            fails.append(f"{m['collideMismatches']} of {m['collideChecked']} blocks differ between atlas level 0 and the client level")
        return m, fails
    if m["p99"] > BAR["p99"]:
        fails.append(f"p99 frame {m['p99']} ms > {BAR['p99']:g}")
    if m["max"] > BAR["max"]:
        fails.append(f"worst frame {m['max']} ms > {BAR['max']:g} ({m['over50']} over 50 ms)")
    if m["holesMax"] is not None and m["holesMax"] > BAR["holes"]:
        fails.append(f"holes up to {m['holesMax']} (per level max {m['holesPerLevelMax']}), {m['holesAtEnd']} at the end; "
                     f"far reach down to {m['farReachMin']} m")
    return m, fails


def cpu_summary(data):
    """CPU per phase from both views (the OS's: every native thread, from outside; the JVM's ThreadMXBean), with the top
    consumers named: {phase: {cores, osTop, jvmCores, jvmTop}} and the whole route's top consumers."""
    os_, jvm = data.get("osCpu") or {}, data.get("cpu") or {}
    out = {}
    for ph in list(os_) + [p for p in jvm if p not in os_]:
        o, j = os_.get(ph) or {}, jvm.get(ph) or {}
        out[ph] = {"cores": o.get("cores"), "wallMs": o.get("wallMs"),
                   "osTop": [f"{t['name']} {t['ms']} ms ({t['share'] * 100:.0f}%)" for t in (o.get("top") or [])[:5]],
                   "jvmCores": None if not j else round(j.get("cores", 0), 2),
                   "jvmTop": [f"{t['thread']} {t['ms']:.0f} ms" for t in (j.get("jvmThreads") or [])[:5]]}
    tot = {}
    for ph, o in os_.items():
        for t in o.get("top") or []:
            tot[t["name"]] = tot.get(t["name"], 0) + t["ms"]
    out["_top"] = [f"{n} {ms} ms" for n, ms in sorted(tot.items(), key=lambda kv: -kv[1])[:8]]
    return out


def charts(data, m, folder):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    secs = data["seconds"]
    t = [r["sec"] for r in secs]
    atlas = [(r.get("atlas") or {}) for r in secs]
    fig, ax = plt.subplots(5, 1, figsize=(12, 13), sharex=True)
    fr = [r.get("frame", {}) for r in secs]
    ax[0].plot(t, [f.get("p50") for f in fr], label="p50", color="#4c9")
    ax[0].plot(t, [f.get("p99") for f in fr], label="p99", color="#e90")
    ax[0].plot(t, [f.get("max") for f in fr], label="max", color="#d33")
    ax[0].axhline(BAR["p99"], color="#e90", ls="--", lw=.8)
    ax[0].axhline(BAR["max"], color="#d33", ls="--", lw=.8)
    ax[0].set_ylabel("frame ms (client's own)")
    ax[0].set_yscale("log")
    ax[0].legend(loc="upper right", ncol=3)
    for k in range(7):
        ax[1].plot(t, [(a.get("holes") or [0] * 7)[k] if a else None for a in atlas], label=f"L{k}")
    ax[1].set_ylabel("holes (columns with no data)")
    ax[1].set_yscale("symlog")
    ax[1].legend(loc="upper right", ncol=7)
    ax[2].bar(t, [x or 0 for x in delta(secs, "genChunks")], label="chunks generated /s", color="#59c")
    ax[2].plot(t, [x or 0 for x in delta(secs, "coldHits")], label="cold hits /s", color="#4a4")
    ax[2].plot(t, [x or 0 for x in delta(secs, "coldWrites")], label="cold writes /s", color="#a44")
    ax[2].plot(t, [x or 0 for x in delta(secs, "gcSectionWrites")], label="gen-cache section writes /s", color="#a8a")
    ax[2].legend(loc="upper right", ncol=2)
    ax[2].set_ylabel("generation and cache")
    ax[3].plot(t, [a.get("stageCpuMs") if a else None for a in atlas], label="staging CPU ms (render thread)", color="#c60")
    ax[3].plot(t, [a.get("atlasMs") if a else None for a in atlas], label="atlas GPU ms", color="#06c")
    ax[3].legend(loc="upper right")
    ax[3].set_ylabel("ms")
    srows = (data.get("server") or {}).get("seconds") or []
    if srows:
        ax[4].plot([r["sec"] for r in srows], [r.get("workerCpuMs", 0) for r in srows], label="server worker CPU ms/s", color="#a60")
        ax[4].plot([r["sec"] for r in srows], [r.get("serverCpuMs", 0) for r in srows], label="server thread CPU ms/s", color="#08a")
        ax[4].plot([r["sec"] for r in srows], [r.get("tickMaxMs", 0) for r in srows], label="slowest server tick ms", color="#a0a")
        ax[4].bar([r["sec"] for r in srows], [r.get("newChunks", 0) * 10 for r in srows], label="new server chunks /s (x10)", color="#9c9", alpha=.5)
        ax[4].legend(loc="upper right", ncol=2)
    ax[4].set_ylabel("integrated server")
    ax[4].set_xlabel("route second")
    # phase shading
    ends, name = 0, None
    for p in data["spec"]["phases"]:
        a, b = ends / 20, (ends + p["ticks"]) / 20
        for axis in ax:
            axis.axvspan(a, b, color="#888", alpha=0.06 if p["name"] in ("sprint", "turn") else 0.0)
        ax[0].text((a + b) / 2, ax[0].get_ylim()[1], p["name"], ha="center", va="top", fontsize=8)
        ends += p["ticks"]
    fig.tight_layout()
    f = folder / "charts.png"
    fig.savefig(f, dpi=80)
    plt.close(fig)
    return f


def sheet(shots, folder):
    from PIL import Image, ImageDraw
    imgs = [Image.open(p).convert("RGB") for p in shots]
    if not imgs:
        return None
    w = 640
    h = round(imgs[0].height * w / imgs[0].width)
    cols = 5
    rows = (len(imgs) + cols - 1) // cols
    out = Image.new("RGB", (cols * w, rows * (h + 18)), (12, 12, 14))
    d = ImageDraw.Draw(out)
    for i, im in enumerate(imgs):
        x, y = (i % cols) * w, (i // cols) * (h + 18)
        out.paste(im.resize((w, h)), (x, y + 18))
        d.text((x + 6, y + 3), f"capture {i + 1}", fill=(235, 235, 235))
    f = folder / "captures.png"
    out.save(f)
    return f


def html(folder, title, runs, verdict, extra=""):
    rows = []
    for label, m, fails, data in runs:
        rows.append(f"<h3>{label}</h3><p><b>{'FAIL' if fails else 'PASS'}</b> {'; '.join(fails)}</p>")
        rows.append("<table><tr>" + "".join(f"<th>{k}</th>" for k in ("frames", "p50", "p95", "p99", "max", "over50", "holesMax",
                                                                     "holesAtEnd", "farReachMin", "genChunks", "farTilesMade", "cachedChunks",
                                                                     "coldHits", "coldWrites", "stageCpuMaxMs", "atlasMsMax", "display"))
                    + "</tr><tr>" + "".join(f"<td>{m.get(k)}</td>" for k in ("frames", "p50", "p95", "p99", "max", "over50", "holesMax",
                                                                           "holesAtEnd", "farReachMin", "genChunks", "farTilesMade",
                                                                           "cachedChunks", "coldHits", "coldWrites", "stageCpuMaxMs",
                                                                           "atlasMsMax", "display")) + "</tr></table>")
        cpu = [(k, v) for k, v in (m.get("cpu") or {}).items() if not k.startswith("_")]
        if cpu:
            rows.append("<h4>CPU by phase (the process's cores used; top consumers: OS threads, then the JVM's)</h4><table><tr><th>phase</th>"
                        "<th>cores</th><th>top OS threads</th><th>JVM cores</th><th>top JVM threads</th></tr>" + "".join(
                f"<tr><td>{k}</td><td>{v['cores']}</td><td style='text-align:left'>{'; '.join(v['osTop'])}</td><td>{v['jvmCores']}</td>"
                f"<td style='text-align:left'>{'; '.join(v['jvmTop'])}</td></tr>" for k, v in cpu) + "</table>")
        sv = ("serverGenerator", "serverView", "serverSim", "serverLoadedChunks", "serverNewChunks", "serverTickP50", "serverTickP99",
              "serverTickMax", "serverTickOver50", "serverWorkerCpuMsPerSec", "serverThreadCpuMsPerSec", "processCpuMsPerSec",
              "serverGpuChunks", "serverJavaChunks", "serverJavaTerrainCalls", "serverGpuWaitMs")
        rows.append("<table><tr>" + "".join(f"<th>{k}</th>" for k in sv) + "</tr><tr>" + "".join(f"<td>{m.get(k)}</td>" for k in sv) + "</tr></table>")
        rows.append("<table><tr><th>phase</th><th>p50</th><th>p99</th><th>max</th></tr>" + "".join(
            f"<tr><td>{k}</td><td>{v['p50']}</td><td>{v['p99']}</td><td>{v['max']}</td></tr>" for k, v in m["phases"].items())
            + "</table>")
    page = f"""<!doctype html><meta charset=utf-8><title>{title}</title>
<style>body{{font:13px system-ui;background:#111;color:#ddd;margin:16px;max-width:1300px}}table{{border-collapse:collapse;margin:6px 0}}
td,th{{border:1px solid #444;padding:3px 8px;text-align:right}}h1{{font-size:18px}}h3{{margin:12px 0 2px}}img{{max-width:100%}}</style>
<h1>{title}</h1><p>{verdict}</p>{''.join(rows)}{extra}
<img src="charts.png"><img src="captures.png"><p>report.json holds every second; bar: p99 &lt;= {BAR['p99']:g} ms, max &lt;= {BAR['max']:g} ms, holes 0 over the route.</p>"""
    (folder / "report.html").write_text(page, encoding="utf-8")


def publish(stamp_dir, name):
    latest = OUT / name / "latest"
    if latest.exists():
        shutil.rmtree(latest, ignore_errors=True)
    shutil.copytree(stamp_dir, latest)


def run_route_scene(runner, s, v, fp):
    import results
    g = runner.game
    relaunch = bool(s.get("relaunch"))
    cold = relaunch or bool(getattr(runner.a, "cold", False))
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name = "route-relaunch" if relaunch else ("collide" if s.get("collide") else "route")
    folder = OUT / name / stamp
    folder.mkdir(parents=True, exist_ok=True)
    runs = []
    try:
        if cold:
            if g.running():
                g.close()
            n = wipe_cache(g)
            print(f"  atlas cache wiped ({n} files): a first visit", flush=True)
            g.ensure(s["group"], runner.groups)
        for i in range(2 if relaunch else 1):
            if i:  # the client restarts on the same cache
                time.sleep(3)
                g.close()
                time.sleep(2)
                g.ensure(s["group"], runner.groups)
            label = "run 2 (relaunch, same cache)" if i else ("run 1 (cold cache)" if relaunch else "run")
            try:
                data = fly_route(runner, s, tag=TAG)
            except RuntimeError as e:
                if "not ready:" not in str(e):
                    raise
                why = str(e)[str(e).index("not ready:"):].splitlines()[0][:300]
                print(f"  {label}: {why}", flush=True)
                return results.record(s["id"], v, "atlas-route", "fail", {"notReady": why}, fp,
                                      extra={"fails": [f"{label}: {why}"], "error": why})
            data["label"] = label
            runs.append(data)
    finally:
        pass
    summaries = [summarize(d) for d in runs]
    fails = []
    for d, (m, f) in zip(runs, summaries):
        fails += [f"{d['label']}: {x}" for x in f]
    extra = ""
    metrics = dict(summaries[-1][0])
    if relaunch:
        m2 = summaries[1][0]
        if m2["genChunksTotal"] is None or m2["farTilesMadeTotal"] is None:
            raise RuntimeError("relaunch: metric genChunks / farTiles absent in the second run")
        gen2 = m2["genChunksTotal"] + m2["farTilesMadeTotal"]  # since the second launch began (counters restart with the JVM)
        full = m2.get("windowUpMs")
        metrics.update({"run1GenChunks": summaries[0][0]["genChunksTotal"], "run1FarTilesMade": summaries[0][0]["farTilesMadeTotal"],
                        "relaunchGenerated": gen2, "relaunchNearChunks": m2["genChunksTotal"], "relaunchFarTiles": m2["farTilesMadeTotal"],
                        "relaunchCachedChunks": m2["cachedChunks"], "relaunchTimeToFullRadiusMs": full,
                        "relaunchTimeToNoHolesMs": m2.get("timeToNoHolesMs")})
        if gen2 > BAR["relaunchGenerated"]:
            fails.append(f"relaunch generated {m2['genChunksTotal']} near chunks and {m2['farTilesMadeTotal']} far tiles (bar 0); "
                         f"{m2['cachedChunks']} chunks came from the cache")
        if full is None or full < 0 or full > BAR["fullRadiusMs"]:
            fails.append(f"relaunch time to full radius {full} ms (bar {BAR['fullRadiusMs']}); no holes after "
                         f"{m2.get('timeToNoHolesMs')} ms")
        extra = (f"<h3>Relaunch</h3><p>second run generated <b>{m2['genChunksTotal']}</b> near chunks and <b>{m2['farTilesMadeTotal']}</b> "
                 f"far tiles (first run: {summaries[0][0]['genChunksTotal']} / {summaries[0][0]['farTilesMadeTotal']}); "
                 f"time to full radius {full} ms (window up after the jump), no holes after {m2.get('timeToNoHolesMs')} ms.</p>")
    last = runs[-1]
    ch = charts(last, summaries[-1][0], folder)
    sh = sheet(last["shots"], folder)
    for d in runs:
        d.pop("shots", None)
    (folder / "report.json").write_text(json.dumps({"runs": [{"label": d["label"], "summary": sm[0], "fails": sm[1], "raw": d}
                                                           for d, sm in zip(runs, summaries)], "bar": BAR, "fails": fails},
                                                  indent=1), encoding="utf-8")
    html(folder, "Infinite ATLAS scripted route" + (" (relaunch)" if relaunch else ""),
         [(d["label"], sm[0], sm[1], d) for d, sm in zip(runs, summaries)],
         "FAIL: " + "; ".join(fails) if fails else "PASS: the route meets the bar", extra)
    publish(folder, name)
    metrics["report"] = str(folder / "report.html")
    metrics.pop("phases", None)
    status = "fail" if fails else "pass"
    rec_extra = {"fails": fails, "report": str(OUT / name / "latest" / "report.html")}
    if sh and ch:
        # the captures sheet is the result's picture (so the overview and the reviewer see the route)
        tmp = Path(g.SHOTS) / f"h-{s['id']}-{v}.png"
        shutil.copy2(sh, tmp)
        rec = results.store(s["id"], v, tmp, fingerprint=fp, extra=rec_extra)
        rec["metrics"].update(metrics)
        rec["status"] = status
        Path(f"{results.stem(s['id'], v)}.after.json").write_text(json.dumps(rec, indent=1, default=str), encoding="utf-8")
        return rec
    return results.record(s["id"], v, "atlas-route", status, metrics, fp, extra=rec_extra)
