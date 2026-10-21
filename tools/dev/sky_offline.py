"""The game's sky offline: the unified sky (atlas_air.glsl + atlas_sky.glsl + atlas_night.glsl, AtlasSky's passes) on GL
without the game, into images and numbers.

Runs the mod's own GLSL files (includes expanded as glsl_check does) on a standalone GL context (moderngl): the sky-view
LUT (core/atlas_sky_view.fsh), the sky as the game shows it (core/atlas_sky.fsh), the emitter cube the water mirrors
(core/atlas_sky_emit.fsh) and the land's light from the sky's emitters (core/atlas_sky_light.fsh). Their inputs come
from the game's own code: NightSkyMath, MoonMath and NirnCelestial (javac'd from the mod's source, run per scene:
NightInfo, MoonInfo, the moons' lights; CelestialInfo, NightSigns: the stars, the planets, a comet, from the scene's
place and date) and the core (atlas_air_coeffs / atlas_air_multiscatter / atlas_air_light from the built atlas_core). The
display exposure is the eye's (AtlasMeter's target for the scene's light).

    py -3 tools/dev/sky_offline.py [--out DIR] [--dll native/build/Release/atlas_core.dll] [--only NAME]
    py -3 tools/dev/sky_offline.py --moons   # the moons: full, half, crescent, high and low; eclipses (moons_sheet.png)

Writes DIR/<scene>_view.png (a perspective view through core/atlas_sky.fsh), DIR/<scene>_dome.png (the whole sky,
zenith at the centre, north up), DIR/<scene>_cube.png (the emitter cube) and DIR/numbers.json; prints a table.

A scene's "boost" multiplies the eye's exposure for its images (a darker-adapted eye than the game's clamp: to read the
faint stars' placement); its "look" may name what to face ("comet", "arkay", "julianos", ...: its place this night).
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
ECLIPSE_ADAPT = 6.0  # stops the eye opens in an eclipse's totality (minutes, not a night's adaptation)
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
# A moon's disc alone through atlas_moons.glsl's moonDisc (its real shading: phases, relief, shadows, the detail
# layer), lit by a chosen sun: the phase sheets and the close crops. The disc stands high (no horizon illusion); the
# window ProbeCenter +- ProbeScale in disc radii (x right, y the moon's north).
MOON_PROBE = """#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_look.glsl>
#include <nirncraft:atlas_air.glsl>
#include <nirncraft:atlas_sky.glsl>
#include <nirncraft:atlas_night.glsl>
uniform vec3 ProbeSun;     // the sun in the disc's frame: x right, y north, z towards the eye
uniform int ProbeMoon;     // 0 Masser, 1 Secunda
uniform vec2 ProbeCenter;
uniform float ProbeScale;
uniform float ProbeExposure;
uniform float ProbeRes;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 pos = normalize(vec3(0.0, 0.5, -1.0));
    vec3 U = normalize(vec3(0.0, 1.0, 0.0) - pos * pos.y), R = normalize(cross(pos, U));
    vec4 P = ProbeMoon == 0 ? MasserPos : SecundaPos;
    vec4 up = ProbeMoon == 0 ? MasserUp : SecundaUp;
    vec4 tint = ProbeMoon == 0 ? MasserTint : SecundaTint;
    vec4 body = ProbeMoon == 0 ? MasserBody : SecundaBody;
    float rad = P.w;
    vec2 q = ProbeCenter + (texCoord * 2.0 - 1.0) * ProbeScale;
    vec3 dir = normalize(pos + (q.x * R + q.y * U) * tan(rad));
    float pix = 2.0 * ProbeScale * tan(rad) / ProbeRes;
    vec3 sun = normalize(ProbeSun.x * R + ProbeSun.y * U - ProbeSun.z * pos);
    vec4 b = vec4(pos * length(body.xyz), body.w);
    vec4 none = vec4(0.0);
    vec4 c = ProbeMoon == 0
        ? moonDisc(dir, vec4(pos, rad), vec4(U, up.w), vec4(tint.rgb, 1.0), NightMasser, NightMasserRelief,
                   NightMasserDetail, MASSER_RELIEF, MASSER_DETAIL_TILE, MASSER_PHOTO, MASSER_ALB_LUM, b, none, sun, pix)
        : moonDisc(dir, vec4(pos, rad), vec4(U, up.w), vec4(tint.rgb, 1.0), NightSecunda, NightSecundaRelief,
                   NightSecundaDetail, SECUNDA_RELIEF, SECUNDA_DETAIL_TILE, SECUNDA_PHOTO, SECUNDA_ALB_LUM, b, none, sun,
                   pix);
    fragColor = vec4(atlasDisplay(c.rgb, ProbeExposure, 0.0), 1.0);
}
"""

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


def night_math(args: dict, cls_name: str = "NightSkyMath") -> dict:
    """NightSkyMath.main (or NirnCelestial.main) for a scene (the mod's own source, compiled into .work/sky_offline)."""
    jdk = find_up("toolchains/jdk-25.0.4.1+1/bin")
    out = ROOT / ".work/sky_offline/classes"
    compile_math(jdk, out)
    r = subprocess.run([str(jdk / "java.exe"), "-cp", str(out), f"com.nirncraft.atmosphere.{cls_name}",
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
    """NightSkyMath, MoonMath and NirnCelestial, javac'd into out when a source is newer than its class."""
    srcs = [ROOT / "mod/src/main/java/com/nirncraft/atmosphere" / f"{n}.java"
            for n in ("NightSkyMath", "MoonMath", "NirnCelestial")]
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


def meter_range():
    """AtlasMeter's MIN, MAX (log2 exposure: the eye's range), from its source."""
    import re
    src = (ROOT / "mod/src/main/java/com/nirncraft/atlas/AtlasMeter.java").read_text(encoding="utf-8")
    m = re.search(r"MIN = (-?[\d.]+)f, MAX = (-?[\d.]+)f", src)
    return float(m.group(1)), float(m.group(2))


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
        self.moon_prog = prog(MOON_PROBE)
        self.vao = {p: gl.vertex_array(p, []) for p in
                    (self.view_prog, self.sky_prog, self.emit_prog, self.light_prog, self.probe_prog, self.moon_prog)}
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
        self.masser_detail = self._image(TEX / "masser_detail.png", repeat_x=True, repeat_y=True)
        self.secunda_detail = self._image(TEX / "secunda_detail.png", repeat_x=True, repeat_y=True)
        self.ubo_moons = gl.buffer(reserve=32 * 4)  # MoonMath.BLOCK_FLOATS
        self.lut = gl.texture((192, 108), 4, dtype="f2")
        self.lut.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.lut.repeat_y = False
        self.lut_fbo = gl.framebuffer([self.lut])
        self.ubo_air = gl.buffer(reserve=9 * 16)  # (AirInfo: 7 vec4 + AirOcculter[2])
        self.ubo_night = gl.buffer(reserve=120 * 4)
        self.ubo_signs = gl.buffer(reserve=(13 + 364 + 40) * 16)  # NirnCelestial.CATALOGUE_FLOATS
        self.ubo_cel = gl.buffer(reserve=228 * 4)  # NirnCelestial.BLOCK_FLOATS
        self.ubo_sky = gl.buffer(reserve=112)
        self.coeffs = self.core.coeffs()

    def _image(self, path: Path, repeat_x=False, repeat_y=False):
        im = Image.open(path).convert("RGBA")
        t = self.gl.texture(im.size, 4, im.tobytes())  # row 0 at v = 0, as the game uploads
        t.build_mipmaps()
        t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
        t.repeat_x = repeat_x
        t.repeat_y = repeat_y
        return t

    def _bind(self, prog):
        units = {"AirMs": self.ms, "NightGalaxy": self.galaxy, "NightMasser": self.masser,
                 "NightMasserRelief": self.masser_relief, "NightSecunda": self.secunda,
                 "NightSecundaRelief": self.secunda_relief, "NightMasserDetail": self.masser_detail,
                 "NightSecundaDetail": self.secunda_detail, "SkyView": self.lut}
        for i, (name, tex) in enumerate(units.items()):
            if name in prog:
                tex.use(i)
                prog[name].value = i
        for i, (name, buf) in enumerate({"AirInfo": self.ubo_air, "NightInfo": self.ubo_night, "MoonInfo": self.ubo_moons,
                                         "NightSigns": self.ubo_signs, "SkyInfo": self.ubo_sky,
                                         "CelestialInfo": self.ubo_cel}.items()):
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
        # (AirMoonE.w unused; AirOcculter[2]: the eclipse's moons, per point along each ray)
        self._air_tail = [*sun, args["eyeKm"], *ml[0:3], 1.0, *(e * fade for e in ml[3:6]), 0.0, *m["occulters"]]
        self.hidden = moons[0] * ECLIPSE_DEPTH * smooth(-0.12, 0.0, sun[1])
        self.ubo_air.write(np.array(self.coeffs + self._air_tail, dtype="f4").tobytes())
        night = np.array(m["night"], dtype="f4")
        night[-4] = 1.0  # NightTarget: pre-exposure 1 here, the cube's texel
        night[-3] = (math.pi / 2) / 96.0
        self.ubo_night.write(night.tobytes())
        cargs = {k: s[k] for k in ("sunAngle", "dayTime", "x", "z", "comet", "world") if k in s}
        c = night_math(cargs, "NirnCelestial")
        self.cel = c
        self.ubo_signs.write(np.array(c["catalogue"], dtype="f4").tobytes())
        self.ubo_cel.write(np.array(c["celestial"], dtype="f4").tobytes())
        self.sun = sun
        self.moonlights = ml
        # the air's light at the eye (atlas_air_light: the key moon), the emitters' light (the light pass)
        # (a moonless night: a token light, else the core falls back to its old moonlight; AtlasClient does the same)
        self.air_light = self.core.light(sun, ml[0:3], [max(e / math.pi, 1e-9) for e in ml[3:6]], EYE_Y)
        self.sh = self.light()
        # the LUT, as AtlasSky draws it: pre-exposed by the eye's exposure (AirMoon.w), read back with the same
        self.pre = self.exposure(extra_up=float(lum(self.irradiance((0, 1, 0)))), town=s.get("town", 0.0))
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

    def exposure(self, extra_up=0.0, town=0.0):
        """The eye's exposure (AtlasMeter's target, its range read from AtlasMeter.java) for a scene lit by this sky: a
        grey ground's mean luminance, plus town (a lit town's own mean luminance: its torches and windows)."""
        k = self.air_light
        key = lum(k[3:6]) * max(k[1], 0.0)
        up = lum(k[6:9]) * math.pi
        lo, hi = meter_range()

        def eye(e):
            s = math.log2(max(e, 1e-12))
            dayness = smooth(-19.0, -6.0, s)
            target = math.log2(0.004) + (math.log2(0.18) - math.log2(0.004)) * dayness
            return 2.0 ** min(max(target - s, lo), hi)
        hidden = getattr(self, "hidden", 0.0)
        full = eye(0.18 * ((key + up) * (1.0 - hidden) + extra_up) / math.pi + town)
        if hidden > 0.3:
            # (an eclipse's minutes: the eye opens to the dark, but not all the way a night's long adaptation goes,
            # ECLIPSE_ADAPT stops at most over the day's)
            full = min(full, eye(0.18 * (key + up + extra_up) / math.pi + town) * 2.0 ** ECLIPSE_ADAPT)
        return full

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

    def moon(self, which, sun, size=512, center=(0.0, 0.0), scale=1.05, exposure=None, shadows_off=True):
        """A moon's disc through the game's shader (MOON_PROBE): sun in the disc's frame (x right, y north, z towards
        the eye), the window centre +- scale (disc radii). The scene's NightInfo / MoonInfo give its radiance; the
        exposure by default sets the full disc's mean a little under mid-grey."""
        night = self.m["night"]
        upw = night[47] if which == 0 else night[59]
        alb = 0.1404 if which == 0 else 0.1100
        if exposure is None:
            exposure = 0.5 / max(upw * alb, 1e-12)
        if shadows_off:  # (no Nirn shadow nor transit in the probe's own geometry)
            moons = list(self.m["moons"])
            moons[17] = 0.0
            self.ubo_moons.write(np.array(moons, dtype="f4").tobytes())
        tail = list(self._air_tail)
        tail[7] = exposure  # (the eye's exposure: the disc's adaptation knee reads it)
        self.ubo_air.write(np.array(self.coeffs + tail, dtype="f4").tobytes())
        prog = self.moon_prog
        prog["ProbeSun"].value = tuple(float(v) for v in sun)
        prog["ProbeMoon"].value = int(which)
        prog["ProbeCenter"].value = tuple(float(v) for v in center)
        prog["ProbeScale"].value = float(scale)
        prog["ProbeExposure"].value = float(exposure)
        prog["ProbeRes"].value = float(size)
        tex = self.gl.texture((size, size), 4)
        fbo = self.gl.framebuffer([tex])
        self._bind(prog)
        fbo.use()
        self.vao[prog].render(moderngl.TRIANGLES, vertices=3)
        img = Image.frombytes("RGBA", (size, size), fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM)
        fbo.release()
        tex.release()
        self.ubo_moons.write(np.array(self.m["moons"], dtype="f4").tobytes())
        self.ubo_air.write(np.array(self.coeffs + self._air_tail, dtype="f4").tobytes())
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


def chart(img, yaw, pitch, fov, labels):
    """A view with its sky named (NirnCelestial's labels: the poles with their height, the named stars, the figures,
    the planets) and the horizon drawn: what to look for in the clean view beside it."""
    from PIL import ImageDraw
    im = img.copy()
    w, h = im.size
    dr = ImageDraw.Draw(im)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    fwd = np.array([sy * cp, sp, -cy * cp])
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    f = 1.0 / math.tan(math.radians(fov) / 2)

    def px(v):
        z = float(np.dot(v, fwd))
        if z <= 0.05:
            return None
        return ((float(np.dot(v, right)) / z * f / (w / h) + 1) / 2 * w, (1 - float(np.dot(v, up)) / z * f) / 2 * h)
    horizon = [px(np.array([math.sin(a), 0.0, -math.cos(a)])) for a in np.linspace(0, 2 * math.pi, 721)]
    for a, b in zip(horizon, horizon[1:]):
        if a and b and abs(a[0] - b[0]) < w:
            dr.line([a, b], fill=(90, 70, 40))
    for lab in labels:
        p = px(np.array(lab["dir"]))
        name = lab["name"]
        if not p or not (0 <= p[0] < w and 0 <= p[1] < h) or (lab["dir"][1] < 0 and "pole" not in name):
            continue
        sign = name.isupper()
        if "pole" in name:
            name += f" {math.degrees(math.asin(lab['dir'][1])):.0f} deg"
        col = (150, 150, 230) if sign else (230, 190, 110) if "pole" not in name else (240, 120, 120)
        if not sign:
            dr.ellipse((p[0] - 7, p[1] - 7, p[0] + 7, p[1] + 7), outline=col)
        dr.text((p[0] + 9, p[1] - 6), name, fill=col)
    return im


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


HD = (1920, 1080)
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
    # the stars, the planets, a comet (NirnCelestial), at the game's resolution (a star's light is held in its pixel:
    # a coarse view dims them): as the game shows them, the dark-adapted eye's lift included ("_x4": two stops more,
    # to read the faint placement). One moment from Skyrim (Winterhold's latitude) and from the far south (the Black
    # Marsh coast): the north pole's height; the Southron Pole Star low in the south from there
    "stars_skyrim": {**midnight(28), "z": -2400, "kp": 0, "brightness": 0, "look": (0, 35, 100), "res": HD},
    "stars_south": {**midnight(28), "z": 11000, "kp": 0, "brightness": 0, "look": (0, 35, 100), "res": HD},
    "stars_south_s": {**midnight(28), "z": 11000, "kp": 0, "brightness": 0, "look": (180, 15, 70), "res": HD},
    # a birthsign without lines, high in a dark sky (the Warrior, from Whiterun)
    "warrior": {**midnight(28), "z": 0, "kp": 0, "brightness": 0, "look": (170, 45, 60), "res": HD},
    "warrior_x4": {**midnight(28), "z": 0, "kp": 0, "brightness": 0, "look": (170, 45, 60), "boost": 4, "res": HD},
    # Arkay in the Thief's eye (Mid Year 16th of year 0; every second Mid Year), and close up (its disc)
    "thief_eye": {**midnight(165), "z": 0, "kp": 0, "brightness": 0, "look": ("arkay", -2, 50), "res": HD},
    "arkay_close": {**midnight(165), "z": 0, "kp": 0, "brightness": 0, "look": ("arkay", 0, 2), "res": HD},
    # one exposure for everything (AtlasMeter's range to the scotopic): a moonless night (the land near black, the stars
    # points to the fifth magnitude and past, the galaxy, a kp 6 aurora green), a full Masser (the land dim, fewer
    # stars), a torch-lit town (its lights, ~2^-13 of the air's units on average, wash the stars out)
    "eye_moonless": {**midnight(28), "z": -2000, "kp": 6, "look": (0, 25, 90), "res": HD},
    "eye_moonless_s": {**midnight(28), "z": -2000, "kp": 6, "look": (170, 40, 70), "res": HD},
    "eye_full_masser": {**midnight(64), "z": -2000, "kp": 0, "brightness": 0, "look": (170, 40, 70), "res": HD},
    "eye_town": {**midnight(28), "z": -2000, "kp": 6, "town": 2.0 ** -13, "look": (170, 40, 70), "res": HD},
    # Akatosh, the evening star, a crescent (Rain's Hand 16th): in the dusk, and its disc and phase close up
    "akatosh_dusk": {**dusk(-10, 105), "z": 0, "kp": 0, "brightness": 0, "look": ("akatosh", 12, 70), "res": HD},
    "akatosh_close": {**dusk(-10, 105), "z": 0, "kp": 0, "brightness": 0, "look": ("akatosh", 0, 1.5), "res": HD},
    # Xero-Lyg, the black Coruscation, on the galaxy's band (close: it hides what lies behind it)
    "xero_lyg": {**midnight(28), "z": 0, "kp": 0, "brightness": 0, "look": ("xero-lyg", 0, 6), "res": HD},
    # Julianos in the Mage's eye (Frostfall 16th of year 1)
    "mage_eye": {**midnight(645), "z": 0, "kp": 0, "brightness": 0, "look": ("julianos", -2, 50), "res": HD},
    # a great comet (the dev hook's, perihelion on day 28) from Skyrim, moonless nights: a week before its peak, the
    # peak night, a week after; the coma close up
    "comet_before": {**midnight(21), "z": -2000, "kp": 0, "brightness": 0, "comet": 28, "look": ("comet", 18, 100),
                     "res": HD},
    "comet_peak": {**midnight(28), "z": -2000, "kp": 0, "brightness": 0, "comet": 28, "look": ("comet", 18, 100),
                   "res": HD},
    "comet_after": {**midnight(35), "z": -2000, "kp": 0, "brightness": 0, "comet": 28, "look": ("comet", 18, 100),
                    "res": HD},
    "comet_coma": {**midnight(28), "z": -2000, "kp": 0, "brightness": 0, "comet": 28, "look": ("comet", 0.8, 4),
                   "res": HD},
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
        if a.only and not any(o and o in name for o in a.only.split(",")):  # (names containing any of these)
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
        # the planets' and a comet's light on an upward face (NirnCelestial's SH, before the night's lift)
        cl = sky.cel["light"]
        cel_up = math.pi * 0.282095 * lum(cl[0:3]) + (2 * math.pi / 3) * 0.488603 * lum(cl[8:11])
        numbers[name]["celestial_up"] = cel_up
        numbers[name]["latitude"] = sky.cel["latitude"]
        numbers[name]["planets"] = sky.cel["planets"]
        if "comet" in sky.cel:
            numbers[name]["comet"] = sky.cel["comet"]
        print(f"{name:18} {expo:6.2f} {zen:9.2e} {north:9.2e} {south:9.2e} {peak:9.2e} {e_up / math.pi:9.2e} "
              f"{air_up:9.2e} {key_e:9.2e} {fill_e:9.2e} {e_up:9.2e}  lat {sky.cel['latitude']:5.1f} celUp {cel_up:8.2e}"
              + (f"  comet m {sky.cel['comet']['mag']:.1f} alt {sky.cel['comet']['alt']:.0f}" if "comet" in sky.cel else ""))
        yaw, pitch, fov = s["look"]
        if isinstance(yaw, str):  # face a body: its place tonight (pitch: degrees above it)
            body = sky.cel["comet"] if yaw == "comet" else sky.cel["planets"].get(yaw)
            if body is None:  # a named star or a figure (its label)
                d = next(lab["dir"] for lab in sky.cel["labels"] if lab["name"] == yaw)
                body = {"az": math.degrees(math.atan2(d[0], -d[2])) % 360, "alt": math.degrees(math.asin(d[1]))}
            yaw, pitch = body["az"], body["alt"] + pitch
        boost = s.get("boost", 1.0)
        img = sky.view(*s.get("res", (960, 540)), yaw, pitch, fov, expo * boost)
        img.save(out / f"{name}_view.png")
        if "labels" in sky.cel:
            chart(img, yaw, pitch, fov, sky.cel["labels"]).save(out / f"{name}_chart.png")
        display(lin[..., :3], expo * boost).save(out / f"{name}_dome.png")
        sky.cube(expo * boost).save(out / f"{name}_cube.png")
    # (a subset run keeps the other scenes' numbers)
    old = json.loads((out / "numbers.json").read_text()) if (out / "numbers.json").exists() else {}
    (out / "numbers.json").write_text(json.dumps({**old, **numbers}, indent=1))
    print("images and numbers.json in", out)


if __name__ == "__main__":
    main()
