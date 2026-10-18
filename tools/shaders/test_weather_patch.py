"""Offline regression checks against the cached, unmodified Complementary shader pack.

Run: py -3 -I tools/shaders/test_weather_patch.py
"""
import importlib.util
import pathlib
import re
import math
import shutil
import tempfile
import unittest
import zipfile
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("weather_patch", pathlib.Path(__file__).with_name("patch_complementary_clouds.py"))
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)


class WeatherPatchTest(unittest.TestCase):
    def test_required_upstream_drift_is_rejected_without_modifying_input(self):
        source = next((ROOT / "dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"))
        with tempfile.TemporaryDirectory() as tmp:
            target = pathlib.Path(tmp) / "unsupported.zip"
            with zipfile.ZipFile(source) as original, zipfile.ZipFile(target,"w") as changed:
                for entry in original.infolist():
                    raw = original.read(entry.filename)
                    if entry.filename == "shaders/lib/lighting/mainLighting.glsl":
                        raw = raw.replace(b"sceneLighting *= moonPhaseInfluence;", b"sceneLighting *= changedLunarContract;")
                    changed.writestr(entry,raw)
            before = target.read_bytes()
            with self.assertRaisesRegex(ValueError,"Unsupported upstream"):
                PATCH.patch_shaderpack_zip(target)
            self.assertEqual(before,target.read_bytes())

    def test_changed_patched_output_is_rejected_before_upgrade(self):
        source = next((ROOT / "dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"))
        with tempfile.TemporaryDirectory() as tmp:
            target = pathlib.Path(tmp) / "patched.zip"
            shutil.copyfile(source,target)
            PATCH.patch_shaderpack_zip(target)
            with zipfile.ZipFile(target) as previous:
                entries = {entry.filename:previous.read(entry.filename) for entry in previous.infolist()}
            entries["shaders/lib/lighting/mainLighting.glsl"] += b"\n// unexpected external edit\n"
            with zipfile.ZipFile(target,"w") as changed:
                for name,raw in entries.items(): changed.writestr(name,raw)
            before = target.read_bytes()
            with self.assertRaisesRegex(ValueError,"changed outside"):
                PATCH.patch_shaderpack_zip(target)
            self.assertEqual(before,target.read_bytes())

    def test_pinned_manifest_accepts_companion_solar_patch_then_upgrade(self):
        source = next((ROOT / "dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"))
        spec = importlib.util.spec_from_file_location("solar_patch",pathlib.Path(__file__).with_name("patch_sun_horizon.py"))
        solar = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(solar)
        with tempfile.TemporaryDirectory() as tmp:
            target = pathlib.Path(tmp) / "pipeline.zip"
            shutil.copyfile(source,target)
            PATCH.patch_shaderpack_zip(target)
            solar.patch_zip(target,target)
            PATCH.patch_shaderpack_zip(target)
            with zipfile.ZipFile(target) as archive:
                self.assertIn(b"skycraftSunHorizonTex",archive.read("shaders/lib/uniforms.glsl"))

    def test_circular_wind_keeps_wrap_neighbors_near_north(self):
        shader = (ROOT / "mod/src/main/resources/assets/skycraft/shaders/include/wind.glsl").read_text()
        self.assertIn("fract(a+(fract(b-a+0.5)-0.5)*weight+1.0)",shader)
        def mix(a,b,weight): return (a+((b-a+0.5)%1-0.5)*weight+1)%1
        for first,second in ((359,1),(355,5),(1,359),(5,355)):
            result = mix(first/360,second/360,0.5)*360
            self.assertLess(min(result,360-result),1e-10)
        self.assertAlmostEqual(90,mix(60/360,120/360,0.5)*360)

    def test_baked_volume_has_wrapping_slice_gutters(self):
        atlas = Image.open(ROOT / "mod/src/main/resources/assets/skycraft/textures/environment/cloud_noise_volume.png")
        self.assertEqual((528, 528), atlas.size)
        for layer in range(64):
            x, y = layer % 8 * 66, layer // 8 * 66
            for offset in range(1, 65):
                self.assertEqual(atlas.getpixel((x, y + offset)), atlas.getpixel((x + 64, y + offset)))
                self.assertEqual(atlas.getpixel((x + 65, y + offset)), atlas.getpixel((x + 1, y + offset)))
                self.assertEqual(atlas.getpixel((x + offset, y)), atlas.getpixel((x + offset, y + 64)))
                self.assertEqual(atlas.getpixel((x + offset, y + 65)), atlas.getpixel((x + offset, y + 1)))

    def test_cloud_motion_does_not_shear_absolute_coordinates_or_jump_with_solar_time(self):
        density = PATCH.CLOUD_DENSITY_GLSL
        self.assertNotIn("tracePosM.xz *=", density)
        self.assertNotIn("syncedTime", density)
        self.assertNotIn("windDir.x * wind,", density)
        self.assertIn("wind *= SkycraftWeatherTime()", density)

    def test_shader_upgrade_preserves_files_and_is_idempotent(self):
        source = next((ROOT / "dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"), None)
        self.assertIsNotNone(source, "Cache the pack through build_prism_instance.py before running this check")
        with tempfile.TemporaryDirectory() as tmp:
            target = pathlib.Path(tmp) / "weather.zip"
            shutil.copyfile(source, target)
            PATCH.patch_shaderpack_zip(target)
            with zipfile.ZipFile(target) as z:
                first = {n: z.read(n) for n in z.namelist()}
                self.assertEqual(len(z.namelist()), len(first), "No duplicate include entries")
            PATCH.patch_shaderpack_zip(target)
            with zipfile.ZipFile(target) as z:
                second = {n: z.read(n) for n in z.namelist()}
            self.assertEqual(first, second, "Rebuilding a patched pack must not accumulate shader edits")
            with zipfile.ZipFile(source) as z:
                self.assertTrue(set(z.namelist()).issubset(first))
            shared = "shaders/lib/atmospherics/clouds/skycraftDensity.glsl"
            self.assertIn(shared, first)
            fire = (ROOT / 'mod/src/main/java/com/skycraft/fire/client/FarFireRenderer.java').read_text()
            marker = re.search(r'zip\.getEntry\("([^"]+)"\)', fire).group(1)
            self.assertIn(marker, first, 'Fire fallback must detect the actual installed shared smoke include')
            properties = first["shaders/shaders.properties"].decode()
            for name in ("skycraftWeatherTex", "skycraftWeatherFarTex", "skycraftLightningTex"):
                self.assertIn("customTexture." + name + " = skycraft:textures/environment/", properties)
            self.assertNotRegex(properties, r"shadow\.culling\s*=\s*(?:true|reversed)")
            # The package builder chains the legacy solar cleanup after this patch.
            sun_spec = importlib.util.spec_from_file_location("sun_patch", pathlib.Path(__file__).with_name("patch_sun_horizon.py"))
            sun_patch = importlib.util.module_from_spec(sun_spec)
            sun_spec.loader.exec_module(sun_patch)
            self.assertIn("skycraftSunHorizonTex", sun_patch.patch_properties(properties))
            for n in ("shaders/lib/lighting/cloudShadows.glsl", "shaders/lib/atmospherics/clouds/unboundClouds.glsl"):
                self.assertIn(b'#include "/lib/atmospherics/clouds/skycraftDensity.glsl"', first[n])
            for n in ("shaders/lib/antialiasing/taa.glsl", "shaders/program/dh_water.glsl", "shaders/program/gbuffers_water.glsl"):
                self.assertIn(b"SKYCRAFT_CLOUD_DEPTH_RANGE", first[n], n)
            self.assertIn(b"SKYCRAFT_VOLUME_DEPTH_CLIP", first["shaders/lib/atmospherics/volumetricLight/volumetricLight.glsl"])
            self.assertNotIn("GetShadowOnCloud(tracePos", PATCH.UNBOUND_CLOUDS_GLSL)
            self.assertNotIn("volumetricClouds.a *= 0.55", PATCH.UNBOUND_CLOUDS_GLSL)
            self.assertIn(b"DoWave_Foliage(playerPos.xyz, worldPos, 1.65);", first["shaders/lib/materials/materialMethods/wavingBlocks.glsl"])
            lighting = first["shaders/lib/lighting/mainLighting.glsl"]
            self.assertNotIn(b"normalize(pow(lightColor, vec3(0.37)))", lighting)
            self.assertIn(b"SKYCRAFT_FINITE_DARK_HIGHLIGHT", lighting)
            self.assertIn(b"SKYCRAFT_SKY_OCCLUSION", lighting)
            self.assertIn(b"SKYCRAFT_PHASE_INDEPENDENT_FLASH", lighting)
            self.assertLess(lighting.index(b"sceneLighting *= moonPhaseInfluence"),
                            lighting.index(b"sceneLighting += SkycraftLightningSurface"))
            self.assertIn(b"SKYCRAFT_CHANNEL_RECEIVER", lighting)
            self.assertIn("shaders/lib/atmospherics/skycraftLightning.glsl", first)
            self.assertIn("SkycraftLightningCloud(cloudPlayerPos, cloudAltitude)", PATCH.UNBOUND_CLOUDS_GLSL)
            self.assertLess(PATCH.CLOUD_DENSITY_GLSL.index('#include "/lib/atmospherics/skycraftLightning.glsl"'),
                            PATCH.CLOUD_DENSITY_GLSL.index('vec3 SkycraftLightningCloud('))
            self.assertNotIn("skyFlash * internalGlow", PATCH.UNBOUND_CLOUDS_GLSL)
            self.assertNotIn("texture2DLod(noisetex, streakPos.xy", PATCH.UNBOUND_CLOUDS_GLSL)
            self.assertNotIn("skyAerosol == 3) ? vec3(1.", PATCH.UNBOUND_CLOUDS_GLSL)

    def test_outdoor_lightning_is_blocked_under_roofs_but_roof_faces_stay_lit(self):
        shader = (PATCH.ROOT / "mod/src/main/resources/assets/skycraft/shaders/include/lightning.glsl").read_text()
        for function in ("SkycraftLightningVolume", "SkycraftLightningSurface"):
            body = shader.split("vec3 " + function + "(", 1)[1].split("\n}", 1)[0]
            self.assertIn("SkycraftOutdoorLightExposure(playerPos)", body)
            self.assertIn("if (exposure <= 0.0) return vec3(0.0)", body)
        old = "// SKYCRAFT_SHARED_AIR\nvoid DoAtmosphericFog(vec3 playerPos) { color = mix(color, vec4(fogColorM, 0.0), fog); }\nvoid DoWaterFog() {}\nDoBorderFog(color, skyFade, max(length(playerPos.xz), abs(playerPos.y)), VdotU, VdotS, dither);"
        upgraded = PATCH.patch_main_fog_glsl(old)
        self.assertIn("fog *= SkycraftOutdoorLightExposure(playerPos)", upgraded)
        self.assertIn("if (SkycraftOutdoorLightExposure(playerPos) > 0.0)", upgraded)
        self.assertEqual(upgraded, PATCH.patch_main_fog_glsl(upgraded))
        def exposure(depth):
            t = min(1.0, max(0.0, (depth-0.75)/1.25))
            return 1.0 - t*t*(3-2*t)
        for depth in (2, 8, 40, 150):
            self.assertEqual(0, exposure(depth))
        # Outdoor ground/roof top, air above it, or a real open shaft over its floor.
        for depth in (0, -1, -100):
            self.assertEqual(1, exposure(depth))
        local = (PATCH.ROOT / "mod/src/main/resources/assets/skycraft/shaders/include/interior.glsl").read_text()
        self.assertNotIn("SkycraftOutdoorLightExposure", local.split("vec3 SkycraftLocalLight", 1)[1])

    def test_upper_wind_cannot_fold_wisps_around_the_world_origin(self):
        ice = PATCH.CLOUD_DENSITY_GLSL.split("float SkycraftHighCloudDensity", 1)[1].split("float SkycraftMiddleCloudDensity", 1)[0]
        self.assertIn("SKYCRAFT_STABLE_ICE_FRAME", ice)
        self.assertNotIn("dot(xz, wind)", ice)
        # Changing regional wind must have the same bounded displacement near
        # Whiterun and at the distant edges of Tamriel, rather than growing with XZ.
        along = (0.8, 0.6)
        for distance in (0, 1000, 12000, 32768):
            for angle in (0, 0.2, 1.7, 3.5):
                wind = (math.cos(angle), math.sin(angle))
                displacement = tuple((wind[i]-along[i])*88 for i in range(2))
                p = (distance-displacement[0], -distance-displacement[1])
                self.assertLessEqual(math.hypot(p[0]-distance, p[1]+distance), 176.00001)

    def test_cloud_budget_covers_the_full_clipped_slab_from_above_and_below(self):
        # Reference integration of a homogeneous slab must keep its optical depth
        # when the camera crosses its top or the adaptive budget changes.
        shader = PATCH.UNBOUND_CLOUDS_GLSL
        self.assertIn("SKYCRAFT_COMPLETE_CLOUD_INTERVAL", shader)
        self.assertNotIn("if (tracePos.y < lowerPlaneAltitude || tracePos.y > higherPlaneAltitude) break;", shader)
        for height in (20, 80, 650, 1020, 1200, 1536, 2048, 2048.01, 3000):
            for ray_y in (-1, -0.37, -0.03, 0.03, 0.37, 1):
                a, b = sorted(((32-height)/ray_y, (2048-height)/ray_y))
                start, end = max(0, a), min(b, 30000)
                if end <= start:
                    continue
                span = end-start
                for count in (16, 70, 105, 192):
                    growth = math.exp(math.log1p(span/96)/count)
                    step, cursor, transmittance = 96*(growth-1), start, 1.0
                    sigma = 1/span
                    for index in range(count):
                        next_pos = end if index == count-1 else min(end, cursor+step)
                        self.assertGreaterEqual(next_pos, cursor)
                        transmittance *= math.exp(-sigma*(next_pos-cursor))
                        cursor, step = next_pos, step*growth
                    self.assertEqual(end, cursor)
                    self.assertAlmostEqual(math.exp(-1), transmittance, places=10)

    def test_cloud_and_precipitation_fades_have_defined_smoothstep_ranges(self):
        for shader in (PATCH.CLOUD_DENSITY_GLSL, PATCH.UNBOUND_CLOUDS_GLSL, PATCH.CLOUD_SHADOWS_GLSL):
            for match in re.finditer(r"smoothstep\(\s*(-?[0-9.]+)\s*,\s*(-?[0-9.]+)\s*,", shader):
                self.assertLess(float(match[1]), float(match[2]), match.group())

    def test_upgrade_older_cloud_colour_patch(self):
        old = "// SKYCRAFT_CLOUD_COLORS\nvec4 skyCloudColW = vec4(0.0);\nfloat skyCloudRainF = clamp(max(rainFactor, skyCloudColW.r), 0.0, 1.0);\n"
        upgraded = PATCH.patch_cloud_colors_glsl(old)
        self.assertIn("float skyCloudRainF = 0.0;", upgraded)
        self.assertEqual(upgraded, PATCH.patch_cloud_colors_glsl(upgraded))


if __name__ == "__main__":
    unittest.main()
