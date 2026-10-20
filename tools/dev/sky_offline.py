"""The game's sky offline: the unified sky (atlas_air.glsl + atlas_sky.glsl + atlas_night.glsl, AtlasSky's passes) on GL
without the game, into images and numbers.

Runs the mod's own GLSL files (includes expanded as glsl_check does) on a standalone GL context (moderngl): the sky-view
LUT (core/atlas_sky_view.fsh), the sky as the game shows it (core/atlas_sky.fsh), the emitter cube the water mirrors
(core/atlas_sky_emit.fsh) and the land's light from the sky's emitters (core/atlas_sky_light.fsh). Their inputs come
from the game's own code: NightSkyMath and MoonMath (javac'd from the mod's source, run per scene: NightInfo, NightSigns,
MoonInfo, the moons'
lights) and the core (atlas_air_coeffs / atlas_air_multiscatter / atlas_air_light from the built atlas_core). The
display exposure is the eye's (AtlasMeter's target for the scene's light).

    py -3 tools/dev/sky_offline.py [--out DIR] [--dll native/build/Release/atlas_core.dll] [--only NAME]
    py -3 tools/dev/sky_offline.py --moons   # the moons: full, half, crescent, high and low; eclipses (moons_sheet.png)

Writes DIR/<scene>_view.png (a perspective view through core/atlas_sky.fsh), DIR/<scene>_dome.png (the whole sky,
zenith at the centre, north up), DIR/<scene>_cube.png (the emitter cube) and DIR/numbers.json; prints a table.
"""
from __future__ import annotations

import argparse
import ctypes
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/dev"))
import glsl_check  # noqa: E402  (the include expansion the game's loader does)

import moderngl  # noqa: E402

SHADERS = ROOT / "mod/src/main/resources/assets/nirncraft/shaders"
TEX = ROOT / "mod/src/main/resources/assets/nirncraft/textures/environment"
PATH_DEG = -25.0
ECLIPSE_DEPTH = 0.9997  # AtlasLook.ECLIPSE_DEPTH
EYE_Y = 120.0

VERT = """#version 330
out vec2 texCoord;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    texCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
# the views the game does not draw (the dome, the linear numbers): the same includes, their own main
PROBE = """#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_look.glsl>
#include <nirncraft:atlas_air.glsl>
#include <nirncraft:atlas_sky.glsl>
#include <nirncraft:atlas_night.glsl>
uniform sampler2D SkyView;
uniform int Mode;        // 0 the dome displayed, 1 the dome linear (all), 2 linear night only, 3 linear air only
uniform float Exposure;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec2 q = texCoord * 2.0 - 1.0;
    float r = length(q);
    if (r > 1.0) { fragColor = vec4(0.0); return; }
    // equidistant dome: the zenith at the centre, the horizon at the rim, north (-Z) up, east (+X) right
    float zen = r * 1.5707963;
    float az = atan(q.x, q.y);
    vec3 dir = vec3(sin(zen) * sin(az), cos(zen), -sin(zen) * cos(az));
    float pix = 1.5707963 / 360.0;
    vec3 air = atlasSky(SkyView, dir, AirSun.xyz, AirMoon.w);
    vec3 night = nightSky(dir, pix, nightDither(gl_FragCoord.xy), NIGHT_ALL, 48);
    vec3 c = Mode == 2 ? night : Mode == 3 ? air : air + night;
    fragColor = Mode == 0 ? vec4(atlasDisplay(c, Exposure, 0.85), 1.0) : vec4(c, 1.0);
}
"""


def expand(text: str) -> str:
    return glsl_check.expand(text, set())


def find_up(rel: str) -> Path:
    for p in [ROOT, *ROOT.parents]:
        if (p / rel).exists():
            return p / rel
    raise FileNotFoundError(rel)


def night_math(args: dict) -> dict:
    """NightSkyMath.main for a scene (the mod's own source, compiled once into .work/sky_offline)."""
    jdk = find_up("toolchains/jdk-25.0.4.1+1/bin")
    out = ROOT / ".work/sky_offline/classes"
    compile_math(jdk, out)
    r = subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "com.nirncraft.atmosphere.NightSkyMath",
                        *[f"{k}={v}" for k, v in args.items()]], capture_output=True, text=True, check=True)
    return json.loads(r.stdout)


class Core:
    """The built core's air (the inputs AtlasSky gives atlas_air.glsl)."""

    def __init__(self, dll: Path):
        self.lib = ctypes.CDLL(str(dll))
        F = ctypes.c_float
        self.F = F
        self.air = (F * 11)()
        self.lib.atlas_air_defaults(ctypes.byref(self.air))

    def coeffs(self):
        out = (self.F * 16)()
        self.lib.atlas_air_coeffs(ctypes.byref(self.air), out)
        return list(out)

    def multiscatter(self):
        out = (self.F * (32 * 32 * 4))()
        self.lib.atlas_air_multiscatter(ctypes.byref(self.air), out)
        return np.array(out, dtype=np.float32)

    def light(self, sun, moon_dir, moon_rad, eye_y):
        F = self.F
        out = (F * 12)()
        md = (F * 3)(*moon_dir) if moon_dir is not None else None
        mr = (F * 3)(*moon_rad) if moon_rad is not None else None
        self.lib.atlas_air_light(ctypes.byref(self.air), (F * 3)(*sun), md, mr, F(eye_y), out)
        return list(out)


def compile_math(jdk, out):
    """NightSkyMath and MoonMath, javac'd into out when either source is newer than its class."""
    srcs = [ROOT / "mod/src/main/java/com/nirncraft/atmosphere" / f"{n}.java" for n in ("NightSkyMath", "MoonMath")]
    stale = False
    for src in srcs:
        cls = out / "com/nirncraft/atmosphere" / (src.stem + ".class")
        stale |= not cls.exists() or cls.stat().st_mtime < src.stat().st_mtime
    if stale:
        out.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(jdk / "javac.exe"), "-d", str(out), *map(str, srcs)], check=True)


def moon_scan(frm, to, step):
    """MoonMath's scene finder: the moons every step (days) over [frm, to)."""
    jdk = find_up("toolchains/jdk-25.0.4.1+1/bin")
    out = ROOT / ".work/sky_offline/classes"
    compile_math(jdk, out)
    r = subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "com.nirncraft.atmosphere.MoonMath", "moons",
                        str(frm), str(to), str(step)], capture_output=True, text=True, check=True)
    return [json.loads(line) for line in r.stdout.splitlines() if line.strip()]


def eclipse_scan(frm, to):
    jdk = find_up("toolchains/jdk-25.0.4.1+1/bin")
    out = ROOT / ".work/sky_offline/classes"
    compile_math(jdk, out)
    r = subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "com.nirncraft.atmosphere.MoonMath", "eclipses",
                        str(frm), str(to)], capture_output=True, text=True, check=True)
    return [json.loads(line) for line in r.stdout.splitlines() if line.strip()]


def smooth(e0, e1, x):
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def lum(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


class Sky:
    def __init__(self, dll: Path):
        self.gl = moderngl.create_standalone_context(require=330)
        self.core = Core(dll)
        gl = self.gl

        def prog(frag_src):
            return gl.program(vertex_shader=VERT, fragment_shader=expand(frag_src))
        core = SHADERS / "core"
        self.view_prog = prog((core / "atlas_sky_view.fsh").read_text(encoding="utf-8"))
        self.sky_prog = prog((core / "atlas_sky.fsh").read_text(encoding="utf-8"))
        self.emit_prog = prog((core / "atlas_sky_emit.fsh").read_text(encoding="utf-8"))
        self.light_prog = prog((core / "atlas_sky_light.fsh").read_text(encoding="utf-8"))
        self.probe_prog = prog(PROBE)
        self.vao = {p: gl.vertex_array(p, []) for p in
                    (self.view_prog, self.sky_prog, self.emit_prog, self.light_prog, self.probe_prog)}
        ms = self.core.multiscatter()
        self.ms = gl.texture((32, 32), 4, ms.tobytes(), dtype="f4")
        self.ms.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.ms.repeat_x = self.ms.repeat_y = False
        self.galaxy = self._image(TEX / "galaxy.png", repeat_x=True)
        # the moons' baked maps (tools/dev/moon_gen.py), mip-mapped and clamped as AtlasMoonTexture loads them
        self.masser = self._image(TEX / "masser_albedo.png")
        self.masser_relief = self._image(TEX / "masser_relief.png")
        self.secunda = self._image(TEX / "secunda_albedo.png")
        self.secunda_relief = self._image(TEX / "secunda_relief.png")
        self.ubo_moons = gl.buffer(reserve=16 * 4)
        self.lut = gl.texture((192, 108), 4, dtype="f2")
        self.lut.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.lut.repeat_y = False
        self.lut_fbo = gl.framebuffer([self.lut])
        self.ubo_air = gl.buffer(reserve=7 * 16)
        self.ubo_night = gl.buffer(reserve=120 * 4)
        self.ubo_signs = gl.buffer(reserve=(14 + 112 + 56) * 16)
        self.ubo_sky = gl.buffer(reserve=112)
        self.coeffs = self.core.coeffs()

    def _image(self, path: Path, repeat_x=False):
        im = Image.open(path).convert("RGBA")
        t = self.gl.texture(im.size, 4, im.tobytes())  # row 0 at v = 0, as the game uploads
        t.build_mipmaps()
        t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
        t.repeat_x = repeat_x
        t.repeat_y = False
        return t

    def _bind(self, prog):
        units = {"AirMs": self.ms, "NightGalaxy": self.galaxy, "NightMasser": self.masser,
                 "NightMasserRelief": self.masser_relief, "NightSecunda": self.secunda,
                 "NightSecundaRelief": self.secunda_relief, "SkyView": self.lut}
        for i, (name, tex) in enumerate(units.items()):
            if name in prog:
                tex.use(i)
                prog[name].value = i
        for i, (name, buf) in enumerate({"AirInfo": self.ubo_air, "NightInfo": self.ubo_night, "MoonInfo": self.ubo_moons,
                                         "NightSigns": self.ubo_signs, "SkyInfo": self.ubo_sky}.items()):
            if name in prog:
                prog[name].binding = i
                buf.bind_to_uniform_block(i)

    def scene(self, s: dict):
        """Sets a scene's inputs: NightSkyMath's blocks, the air's (the key moon as AtlasAir.moonFor gives it)."""
        args = {k: s[k] for k in ("sunAngle", "dayTime", "x", "z", "kp", "brightness", "red", "secunda", "clear",
                                  "seconds") if k in s}
        args.setdefault("path", PATH_DEG)
        args["eyeKm"] = max(EYE_Y - 63.0, 0.0) * self.coeffs[11]
        m = night_math(args)
        self.m = m
        sun = m["sun"]
        ml = m["moonlights"]  # key dir, key E, fill dir, fill E (irradiance above the air)
        fade = smooth(0.1, 0.0, sun[1])
        moons = m["moons"]  # MoonInfo (MoonMath.block): the eclipse, the Dark Moon, the Revenant
        self.ubo_moons.write(np.array(moons, dtype="f4").tobytes())
        # (AirMoonE.w: the sun's share an eclipse hides)
        self._air_tail = [*sun, args["eyeKm"], *ml[0:3], 1.0, *(e * fade for e in ml[3:6]), moons[0] * ECLIPSE_DEPTH]
        self.hidden = moons[0] * ECLIPSE_DEPTH * smooth(-0.12, 0.0, sun[1])
        self.ubo_air.write(np.array(self.coeffs + self._air_tail, dtype="f4").tobytes())
        night = np.array(m["night"], dtype="f4")
        night[-4] = 1.0  # NightTarget: pre-exposure 1 here, the cube's texel
        night[-3] = (math.pi / 2) / 96.0
        self.ubo_night.write(night.tobytes())
        self.ubo_signs.write(np.array(m["signs"], dtype="f4").tobytes())
        self.sun = sun
        self.moonlights = ml
        # the air's light at the eye (atlas_air_light: the key moon), the emitters' light (the light pass)
        # (a moonless night: a token light, else the core falls back to its old moonlight; AtlasClient does the same)
        self.air_light = self.core.light(sun, ml[0:3], [max(e / math.pi, 1e-9) for e in ml[3:6]], EYE_Y)
        self.sh = self.light()
        # the LUT, as AtlasSky draws it: pre-exposed by the eye's exposure (AirMoon.w), read back with the same
        self.pre = self.exposure(extra_up=float(lum(self.irradiance((0, 1, 0)))))
        self._air_tail[7] = self.pre
        self.ubo_air.write(np.array(self.coeffs + self._air_tail, dtype="f4").tobytes())
        self._bind(self.view_prog)
        self.lut_fbo.use()
        self.vao[self.view_prog].render(moderngl.TRIANGLES, vertices=3)

    def light(self):
        tex = self.gl.texture((4, 1), 4, dtype="f4")
        fbo = self.gl.framebuffer([tex])
        self._bind(self.light_prog)
        fbo.use()
        self.vao[self.light_prog].render(moderngl.TRIANGLES, vertices=3)
        sh = np.frombuffer(fbo.read(components=4, dtype="f4"), dtype="f4").reshape(4, 4)[:, :3]
        fbo.release()
        tex.release()
        return sh

    def irradiance(self, n):
        """The emitters' irradiance on a surface facing n (the light pass's SH, as NightSky evaluates it)."""
        sh = self.sh
        return math.pi * 0.282095 * sh[0] + (2 * math.pi / 3) * 0.488603 * (sh[1] * n[0] + sh[2] * n[1] + sh[3] * n[2])

    def exposure(self, extra_up=0.0):
        """The eye's exposure (AtlasMeter's target) for a scene lit by this sky: a grey ground's mean luminance."""
        k = self.air_light
        key = lum(k[3:6]) * max(k[1], 0.0)
        up = lum(k[6:9]) * math.pi
        e = 0.18 * ((key + up) * (1.0 - getattr(self, "hidden", 0.0)) + extra_up) / math.pi
        s = math.log2(max(e, 1e-9))
        dayness = smooth(-19.0, -6.0, s)
        target = math.log2(0.004) + (math.log2(0.18) - math.log2(0.004)) * dayness
        return 2.0 ** min(max(target - s, -4.5), 12.0)

    def view(self, w, h, yaw, pitch, fov, exposure):
        """A perspective view through the game's core/atlas_sky.fsh (displayed, sRGB 8 bit)."""
        f = 1.0 / math.tan(math.radians(fov) / 2)
        aspect = w / h
        proj = np.array([[f / aspect, 0, 0, 0], [0, f, 0, 0], [0, 0, -1.0002, -0.20002], [0, 0, -1, 0]])
        cy, sy, cp, sp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw)), math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        fwd = np.array([sy * cp, sp, -cy * cp])  # yaw 0: north (-Z), 90: east (+X)
        right = np.cross(fwd, [0, 1, 0])
        right /= np.linalg.norm(right)
        up = np.cross(right, fwd)
        view = np.eye(4)
        view[0, :3], view[1, :3], view[2, :3] = right, up, -fwd
        inv = np.linalg.inv(proj @ view).astype("f4")
        sun = self.sun
        key = self.air_light
        disc = key[3:6] if sun[1] > -0.05 else [0, 0, 0]
        data = np.concatenate([inv.T.reshape(-1), [*sun, 0], [exposure, 0.85, 0, 0], [*disc, 0]])
        self.ubo_sky.write(data.astype("f4").tobytes())
        tex = self.gl.texture((w, h), 4)
        fbo = self.gl.framebuffer([tex])
        self._bind(self.sky_prog)
        fbo.use()
        self.vao[self.sky_prog].render(moderngl.TRIANGLES, vertices=3)
        img = Image.frombytes("RGBA", (w, h), fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM)
        fbo.release()
        tex.release()
        return img.convert("RGB")

    def dome(self, size, mode, exposure=1.0):
        tex = self.gl.texture((size, size), 4, dtype="f4")
        fbo = self.gl.framebuffer([tex])
        self._bind(self.probe_prog)
        self.probe_prog["Mode"].value = mode
        self.probe_prog["Exposure"].value = exposure
        fbo.use()
        self.vao[self.probe_prog].render(moderngl.TRIANGLES, vertices=3)
        a = np.frombuffer(fbo.read(components=4, dtype="f4"), dtype="f4").reshape(size, size, 4)[::-1]
        fbo.release()
        tex.release()
        return a

    def cube(self, exposure):
        tex = self.gl.texture((96 * 6, 96), 4, dtype="f2")
        fbo = self.gl.framebuffer([tex])
        self._bind(self.emit_prog)
        fbo.use()
        self.vao[self.emit_prog].render(moderngl.TRIANGLES, vertices=3)
        a = np.frombuffer(fbo.read(components=4, dtype="f2"), dtype="f2").astype("f4").reshape(96, 96 * 6, 4)[::-1]
        fbo.release()
        tex.release()
        lin = a[..., :3] / np.maximum(a[..., 3:4], 1e-6)
        return display(lin, exposure)


def display(lin, exposure):
    """atlas_look.glsl's atlasDisplay (ACES fit, sRGB) in numpy (night vision left out: images of the radiance)."""
    inM = np.array([[0.59719, 0.35458, 0.04823], [0.07600, 0.90834, 0.01566], [0.02840, 0.13383, 0.83777]])
    outM = np.array([[1.60475, -0.53108, -0.07367], [-0.10208, 1.10813, -0.00605], [-0.00327, -0.07276, 1.07602]])
    v = (lin * exposure) @ inM.T
    a = v * (v + 0.0245786) - 0.000090537
    b = v * (0.983729 * v + 0.4329510) + 0.238081
    c = np.clip((a / b) @ outM.T, 0, 1)
    s = np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)
    return Image.fromarray((np.clip(s, 0, 1) * 255 + 0.5).astype(np.uint8))


# the scenes: midnight is sunAngle pi; the days pick the moons' phases (Masser full at n = 0 mod 8, Secunda near full
# at n = 6 mod 9: day 64 Masser full and Secunda new, day 60 Secunda full and Masser new, day 28 both new)
def midnight(day):
    return {"sunAngle": math.pi, "dayTime": day * 24000 + 18000}


def dusk(elev_deg, day=28):
    t = math.radians(PATH_DEG)
    a = math.acos(math.sin(math.radians(elev_deg)) / math.cos(t))
    # (the evening: the sun in the west; the day time near 12000 for the stars' frame)
    return {"sunAngle": a, "dayTime": day * 24000 + 12000 + (elev_deg * -60)}


SCENES = {
    # the aurora from north to south (both moons new, a strong night: kp 6)
    "aurora_atmora": {**midnight(28), "z": -6500, "kp": 6, "look": (0, 35, 100)},
    "aurora_skyrim": {**midnight(28), "z": -2000, "kp": 6, "look": (0, 20, 90)},
    "aurora_cyrodiil": {**midnight(28), "z": 3500, "kp": 6, "look": (0, 8, 90)},
    "aurora_south": {**midnight(28), "z": 6000, "kp": 6, "look": (0, 5, 90)},
    # strong vs weak in Skyrim
    "aurora_weak": {**midnight(28), "z": -2000, "kp": 1, "look": (0, 20, 90)},
    "aurora_strong": {**midnight(28), "z": -2000, "kp": 8.5, "look": (0, 30, 100)},
    "aurora_none": {**midnight(28), "z": -2000, "kp": 0, "brightness": 0, "look": (0, 20, 90)},
    # the moons
    "masser_full": {**midnight(64), "z": 3500, "kp": 0, "brightness": 0, "look": (180, 50, 90)},
    "new_moons": {**midnight(28), "z": 3500, "kp": 0, "brightness": 0, "look": (180, 50, 90)},
    "secunda_full": {**midnight(60), "z": 3500, "kp": 0, "brightness": 0, "look": (180, 50, 90)},
    "full_moon_aurora": {**midnight(64), "z": -2000, "kp": 6, "look": (0, 20, 90)},
    # both up: Masser a half moon, Secunda nearly full (the key and the fill)
    "both_moons": {**midnight(6), "z": 3500, "kp": 0, "brightness": 0, "look": (200, 40, 100)},
    # dusk: the stars coming out (south-east, away from the sunset)
    "dusk_m2": {**dusk(-2), "z": 0, "kp": 0, "brightness": 0, "look": (110, 30, 90)},
    "dusk_m6": {**dusk(-6), "z": 0, "kp": 0, "brightness": 0, "look": (110, 30, 90)},
    "dusk_m10": {**dusk(-10), "z": 0, "kp": 0, "brightness": 0, "look": (110, 30, 90)},
    "dusk_m14": {**dusk(-14), "z": 0, "kp": 0, "brightness": 0, "look": (110, 30, 90)},
    "day_moon": {"sunAngle": 0.6, "dayTime": 64 * 24000 + 3000, "z": 0, "kp": 0, "brightness": 0, "look": (80, 30, 90)},
}


def toward(d):
    """(yaw, pitch) degrees looking along world direction d (yaw 0 north, 90 east)."""
    return math.degrees(math.atan2(d[0], -d[2])), math.degrees(math.asin(max(-1.0, min(1.0, d[1]))))


def pick(rows, key, want):
    """The scan row that best fits: want(row) -> a score (lower better) or None (unfit)."""
    best, score = None, None
    for r in rows:
        v = want(r)
        if v is not None and (score is None or v < score):
            best, score = r, v
    return best


def moon_scenes():
    """The moons' sheet's scenes, found in MoonMath's scan: each moon full, half and a crescent, high and near the
    horizon (night unless a crescent needs the dusk), both moons together, and the eclipses (Masser's, Secunda's, the
    dark one). {name: (scene, aim direction, close-up field of view)}."""
    rows = moon_scan(0.0, 120.0, 1.0 / 96.0)
    el = lambda d: math.degrees(math.asin(d[1]))
    out = {}
    for body, radius_deg in (("masser", 10.5), ("secunda", 3.6)):
        for phase, lo, hi, sun_max in (("full", 0.97, 1.01, -0.25), ("half", 0.42, 0.58, -0.2),
                                       ("crescent", 0.12, 0.3, -0.08)):
            for where, e0, e1 in (("high", 30, 70), ("low", 4 + radius_deg, 10 + radius_deg)):
                r = pick(rows, body, lambda r: (abs(r[body][3] - (lo + hi) / 2)
                         if lo <= r[body][3] <= hi and r["sunY"] < sun_max and e0 <= el(r[body]) <= e1 else None))
                if r is None:
                    print(f"  (no scene for {body} {phase} {where})")
                    continue
                d = r[body][:3]
                out[f"{body}_{phase}_{where}"] = ({"dayTime": r["day"] * 24000, "z": 3500, "kp": 0, "brightness": 0},
                                                  d, radius_deg * 2.0 * 1.35)
    # both up at night: Masser low and large, Secunda higher beside it
    r = pick(rows, "both", lambda r: (abs(el(r["masser"]) - 16) + abs(el(r["secunda"]) - 35) * 0.5
             if r["sunY"] < -0.2 and r["masser"][3] > 0.6 and r["secunda"][3] > 0.5
             and math.degrees(math.acos(sum(a * b for a, b in zip(r["masser"][:3], r["secunda"][:3])))) < 45 else None))
    if r is not None:
        mid = [r["masser"][i] + r["secunda"][i] for i in range(3)]
        n = math.sqrt(sum(c * c for c in mid))
        out["both_moons_view"] = ({"dayTime": r["day"] * 24000, "z": 3500, "kp": 0, "brightness": 0},
                                  [c / n for c in mid], 75.0)
    # the eclipses: the first of each kind with the sun well up
    ecl = eclipse_scan(0.0, 1500.0)
    for name, test in (("eclipse_masser", lambda e: e["masser"] > 0.999 and e["secunda"] == 0),
                       ("eclipse_secunda", lambda e: e["secunda"] > 0.999 and e["masser"] == 0),
                       ("eclipse_partial", lambda e: 0.3 < e["hidden"] < 0.8),
                       ("eclipse_dark", lambda e: e["dark"] > 0.5)):
        e = next((e for e in ecl if test(e) and e["sunY"] > 0.3), None)
        if e is None:
            print(f"  (no scene for {name})")
            continue
        out[name] = ({"dayTime": e["day"] * 24000, "z": 3500, "kp": 0, "brightness": 0}, None, 60.0)
    return out


def moons_sheet(sky, out):
    """Renders the moon scenes (wide and close) into out/moons/, and moons_sheet.png."""
    from PIL import ImageDraw
    d = out / "moons"
    d.mkdir(parents=True, exist_ok=True)
    tiles = []
    for name, (scene, aim, fov) in moon_scenes().items():
        sky.scene(scene)
        if aim is None:
            aim = sky.sun
        yaw, pitch = toward(aim)
        expo = sky.pre
        close = sky.view(720, 720, yaw, pitch, fov, expo)
        close.save(d / f"{name}_close.png")
        wide = sky.view(1280, 720, yaw, max(pitch - 8, 12) if pitch < 30 else pitch, 80, expo)
        wide.save(d / f"{name}_wide.png")
        tile = Image.new("RGB", (720 + 1280 + 8, 740), (20, 20, 20))
        tile.paste(close, (0, 0))
        tile.paste(wide, (728, 0))
        ImageDraw.Draw(tile).text((6, 723), f"{name}  day {scene['dayTime'] / 24000:.3f}  expo {expo:.3g}"
                                  f"  hidden {sky.m['moons'][0]:.2f} dark {sky.m['moons'][1]:.2f}", fill=(230, 230, 230))
        tiles.append(tile)
        print(f"  {name}: day {scene['dayTime'] / 24000:.3f}, aim yaw {yaw:.0f} pitch {pitch:.0f}")
    if tiles:
        sheet = Image.new("RGB", (tiles[0].width, sum(t.height for t in tiles)), (0, 0, 0))
        y = 0
        for t in tiles:
            sheet.paste(t, (0, y))
            y += t.height
        sheet.save(out / "moons_sheet.png")
        print("moons sheet:", out / "moons_sheet.png")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / ".work/sky_offline"))
    ap.add_argument("--dll", default="")
    ap.add_argument("--only", default="")
    ap.add_argument("--moons", action="store_true", help="the moons' sheet (MoonMath's scenes) instead of SCENES")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    # (a worktree has no build of its own: the checkout's, further up)
    sky = Sky(Path(a.dll) if a.dll else find_up("native/build/Release/atlas_core.dll"))
    if a.moons:
        moons_sheet(sky, out)
        return
    numbers = {}
    print(f"{'scene':18} {'expo':>6} {'zenith':>9} {'N hor':>9} {'S hor':>9} {'aur peak':>9} {'nightUp':>9} "
          f"{'airUp':>9} {'key':>9} {'fill':>9} {'emitE_up':>9}")
    for name, s in SCENES.items():
        if a.only and a.only not in name:
            continue
        sky.scene(s)
        e_up = float(lum(sky.irradiance((0, 1, 0))))
        e_n = float(lum(sky.irradiance((0, 0, -1))))
        e_s = float(lum(sky.irradiance((0, 0, 1))))
        ml = sky.moonlights
        key_e = lum(ml[3:6]) * max(ml[1], 0)
        fill_e = lum(ml[9:12]) * max(ml[7], 0)
        expo = sky.pre  # (the eye's exposure: the LUT's pre-exposure, the display's)
        lin = sky.dome(361, 1)
        nightonly = sky.dome(361, 2)
        air = sky.dome(361, 3)
        L = (lin[..., :3] * [0.2126, 0.7152, 0.0722]).sum(-1)
        Ln = (nightonly[..., :3] * [0.2126, 0.7152, 0.0722]).sum(-1)
        c = 180
        zen = float(L[c, c])
        north = float(np.median(L[c - 178:c - 170, c - 3:c + 4]))  # 2..6 degrees over the northern horizon
        south = float(np.median(L[c + 170:c + 178, c - 3:c + 4]))
        # the aurora's peak: the night-only light less the stars (99.9th percentile of a median-filtered map)
        from PIL import ImageFilter
        smooth_n = np.asarray(Image.fromarray(Ln.astype("f4")).filter(ImageFilter.MedianFilter(5)))
        peak = float(np.percentile(smooth_n[np.isfinite(smooth_n)], 99.9))
        air_up = lum(sky.air_light[6:9])
        numbers[name] = dict(exposure=expo, zenith=zen, north_horizon=north, south_horizon=south,
                             night_peak=peak, emit_up=e_up, emit_north=e_n, emit_south=e_s,
                             air_sky_up_radiance=air_up, key_irradiance=key_e, fill_irradiance=fill_e,
                             gain=sky.m["gain"], sh=sky.sh.tolist(), sun=sky.sun, moonlights=ml,
                             air_zenith=float((air[c, c, :3] * [0.2126, 0.7152, 0.0722]).sum()))
        print(f"{name:18} {expo:6.2f} {zen:9.2e} {north:9.2e} {south:9.2e} {peak:9.2e} {e_up / math.pi:9.2e} "
              f"{air_up:9.2e} {key_e:9.2e} {fill_e:9.2e} {e_up:9.2e}")
        yaw, pitch, fov = s["look"]
        sky.view(960, 540, yaw, pitch, fov, expo).save(out / f"{name}_view.png")
        display(lin[..., :3], expo).save(out / f"{name}_dome.png")
        sky.cube(expo).save(out / f"{name}_cube.png")
    # (a subset run keeps the other scenes' numbers)
    old = json.loads((out / "numbers.json").read_text()) if (out / "numbers.json").exists() else {}
    (out / "numbers.json").write_text(json.dumps({**old, **numbers}, indent=1))
    print("images and numbers.json in", out)


if __name__ == "__main__":
    main()
