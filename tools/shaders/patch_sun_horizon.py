"""
Compatibility cleanup for Skycraft's Complementary patch.

Restores the physical solar elevation in older patched packs. A camera-local terrain
horizon must not shift the sun, sky palette, or all world lighting. Terrain occlusion
now lives in the shared world-space cloud/ground transmittance implementation.
The legacy texture binding is retained for idempotent upgrades but is not sampled.

Usage: py patch_sun_horizon.py <input.zip> [output.zip]
"""
import pathlib
import shutil
import sys
import tempfile
import zipfile

MARK = "skycraftSunHorizonTex"
SDOTU_OLD = "float SdotU = dot(sunVec, upVec);"
SDOTU_LEGACY = "float SdotU = dot(sunVec, upVec) - skycraftHorizonShift(dot(sunVec, upVec));"
SDOTU_NEW = SDOTU_OLD

UNIFORM_ANCHOR = "uniform sampler2D skycraftWeatherTex;"
UNIFORM_ADD = (
    "uniform sampler2D skycraftSunHorizonTex;\r\n"
    "// SKYCRAFT_SUN_HORIZON: how far terrain on the sun's side of the sky lifts the effective horizon (in sine of the sun's height)\r\n"
    "float skycraftHorizonShift(float rawSdotU) {\r\n"
    "    float skyHorizonSin = texture2DLod(skycraftSunHorizonTex, vec2(0.5), 0.0).r * 0.5;\r\n"
    "    return skyHorizonSin * clamp((0.45 - rawSdotU) / 0.25, 0.0, 1.0);\r\n"
    "}"
)

PROP_ANCHOR = "customTexture.skycraftWeatherTex = skycraft:textures/environment/weather_field.png"
PROP_ADD = "\r\n    customTexture.skycraftSunHorizonTex = skycraft:textures/environment/sun_horizon.png"


def patch_uniforms(text: str) -> str:
    if MARK in text:
        return text
    if UNIFORM_ANCHOR not in text:
        raise RuntimeError("uniforms.glsl is not Skycraft-patched yet (run patch_complementary_clouds.py first)")
    return text.replace(UNIFORM_ANCHOR, UNIFORM_ANCHOR + "\r\n" + UNIFORM_ADD, 1)


def patch_properties(text: str) -> str:
    if MARK in text:
        return text
    if PROP_ANCHOR not in text:
        raise RuntimeError("shaders.properties is not Skycraft-patched yet")
    return text.replace(PROP_ANCHOR, PROP_ANCHOR + PROP_ADD, 1)


def patch_program(text: str) -> tuple[str, int]:
    # The weather density include now traces terrain toward the actual sun/moon
    # from each receiver. A camera-wide horizon shift incorrectly changes the
    # celestial light colour and dims distant clear slopes with the near valley.
    text = text.replace(SDOTU_LEGACY, SDOTU_OLD)
    n = text.count(SDOTU_OLD)
    if n == 0:
        return text, 0
    return text.replace(SDOTU_OLD, SDOTU_NEW), n


def patch_zip(src: pathlib.Path, dst: pathlib.Path) -> None:
    replaced = 0
    files = 0
    with tempfile.NamedTemporaryFile(delete=False, suffix=".zip", dir=str(dst.parent)) as tmp:
        tmp_path = pathlib.Path(tmp.name)
    try:
        with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp_path, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                raw = zin.read(item.filename)
                name = item.filename
                if name == "shaders/lib/uniforms.glsl":
                    raw = patch_uniforms(raw.decode("utf-8")).encode("utf-8")
                elif name == "shaders/shaders.properties":
                    raw = patch_properties(raw.decode("utf-8")).encode("utf-8")
                elif name.startswith("shaders/program/") and name.endswith(".glsl"):
                    text, n = patch_program(raw.decode("utf-8"))
                    if n:
                        raw = text.encode("utf-8")
                        replaced += n
                        files += 1
                zout.writestr(item, raw)
        shutil.move(str(tmp_path), str(dst))
        print(f"Sun horizon patch: {replaced} SdotU definitions in {files} programs -> {dst}")
    finally:
        if tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    src = pathlib.Path(argv[1])
    dst = pathlib.Path(argv[2]) if len(argv) > 2 else src
    dst.parent.mkdir(parents=True, exist_ok=True)
    patch_zip(src, dst)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

