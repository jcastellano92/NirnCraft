"""Hard checks on where a picture was taken, so a wrong camera fails by numbers instead of by eye.

* pose: the camera the game reports (capture receipt) against the pose the scene asked for: position and forward vector.
* comparable: a before and an after are only compared when they were taken at the same place, looking the same way, at
  the same time of day, in the same weather, world and settings; otherwise the pair is "not comparable".
* twin: a lab render (atlas_render conventions) against its live twin (Minecraft conventions) in world space.

Conventions: Minecraft yaw 0 = south (+Z), 90 = west (-X), pitch > 0 looks down. atlas_render yaw 0 = north (-Z),
90 = east (+X), pitch < 0 looks down.
"""
import math

EYE = 1.62           # the `camera` verb places the feet at its position and aims from the eyes (VisualCamera.angles)
POS_TOL = 1.5        # blocks (visit snaps to the block centre)
DOT_TOL = 0.998      # forward vectors within ~3.6 degrees
DAYTIME_TOL = 200    # ticks (10 s of game time)


def mc_forward(yaw, pitch):
    y, p = math.radians(float(yaw)), math.radians(float(pitch))
    return (-math.sin(y) * math.cos(p), -math.sin(p), math.cos(y) * math.cos(p))


def atlas_forward(yaw, pitch):
    y, p = math.radians(float(yaw)), math.radians(float(pitch))
    return (math.sin(y) * math.cos(p), math.sin(p), -math.cos(y) * math.cos(p))


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


def _dot(a, b):
    return sum(x * y for x, y in zip(_norm(a), _norm(b)))


def _deg(dot):
    return round(math.degrees(math.acos(max(-1.0, min(1.0, dot)))), 1)


def requested(pose_lines):
    """What the scene asked for, from the pose verbs it sent (visit / camera): {"xz", "y" (camera only), "forward"}."""
    for line in pose_lines:
        w = line.split()
        if not w:
            continue
        if w[0] == "visit" and len(w) >= 6:
            return {"xz": (float(w[1]) + 0.5, float(w[2]) + 0.5), "forward": mc_forward(w[4], w[5])}
        if w[0] == "camera" and len(w) >= 7:  # x y z are the player's feet; it aims from the eyes, EYE above them
            c, l = [float(v) for v in w[1:4]], [float(v) for v in w[4:7]]
            return {"xz": (c[0], c[2]), "y": c[1], "forward": (l[0] - c[0], l[1] - c[1] - EYE, l[2] - c[2])}
    return None


def actual(receipt):
    pos = receipt.get("position")
    if not pos or "yaw" not in receipt:
        return None
    return {"pos": pos, "forward": mc_forward(receipt["yaw"], receipt["pitch"])}


def pose(req, receipt):
    """Flags when the camera is not where the scene asked (empty: it is)."""
    got = actual(receipt or {})
    if not req or not got:
        return [], {}
    d = math.hypot(got["pos"][0] - req["xz"][0], got["pos"][2] - req["xz"][1])
    dot = _dot(req["forward"], got["forward"])
    m = {"poseOffset": round(d, 2), "poseAngle": _deg(dot)}
    flags = []
    if d > POS_TOL:
        flags.append(f"camera {d:.1f} blocks from the requested place")
    if dot < DOT_TOL:
        flags.append(f"camera looks {_deg(dot)} deg away from the requested direction")
    return flags, m


def comparable(before, after):
    """Why a before / after pair cannot be compared (empty list: it can). Uses the stored capture receipts."""
    b, a = (before or {}).get("game") or {}, (after or {}).get("game") or {}
    if not b or not a:
        return ["no capture receipt"] if b or a else []
    why = []
    for k in ("seed", "generationInputs", "dimension", "shaders", "renderDistance", "weather"):
        if k in b and k in a and b[k] != a[k]:
            why.append(f"{k} {b[k]} -> {a[k]}")
    gb, ga = actual(b), actual(a)
    if gb and ga:
        d = math.dist(gb["pos"], ga["pos"])
        if d > POS_TOL:
            why.append(f"camera moved {d:.1f} blocks")
        dot = _dot(gb["forward"], ga["forward"])
        if dot < DOT_TOL:
            why.append(f"camera turned {_deg(dot)} deg")
    if "dayTime" in b and "dayTime" in a:
        dt = abs((a["dayTime"] - b["dayTime"] + 12000) % 24000 - 12000)
        if dt > DAYTIME_TOL:
            why.append(f"time of day {b['dayTime']} -> {a['dayTime']}")
        db, da = int(b["dayTime"]) // 24000, int(a["dayTime"]) // 24000
        if db % 8 != da % 8 or db % 9 != da % 9:  # Masser's 8-day and Secunda's 9-day cycles: another night sky
            why.append(f"moon phase (day {db} -> day {da})")
    return why


def twin(lab_meta, receipt):
    """Lab render vs live twin in world space: flags and numbers (position from atlas_render's own report)."""
    args = dict(kv.split("=", 1) for kv in (lab_meta or {}).get("args", []) if "=" in kv)
    got = actual(receipt or {})
    if not args or not got:
        return [], {}
    cam = ((lab_meta or {}).get("atlas") or {}).get("camera")
    lab_pos = cam or [float(args.get("x", 0)), None, float(args.get("z", 0))]
    lab_fwd = atlas_forward(args.get("yaw", 0), args.get("pitch", -4))
    dot = _dot(lab_fwd, got["forward"])
    d = math.hypot(got["pos"][0] - lab_pos[0], got["pos"][2] - lab_pos[2])
    m = {"twinOffset": round(d, 2), "twinAngle": _deg(dot)}
    flags = []
    if d > POS_TOL + 1:
        flags.append(f"twin camera {d:.1f} blocks from the lab camera")
    if dot < DOT_TOL:
        flags.append(f"twin looks {_deg(dot)} deg away from the lab camera (convention error?)")
    if lab_pos[1] is not None:
        dy = got["pos"][1] - lab_pos[1]
        m["twinHeightDiff"] = round(dy, 1)
        if abs(dy) > 3:
            flags.append(f"twin camera {dy:+.1f} blocks above the lab camera (the two terrains differ under it)")
    return flags, m
