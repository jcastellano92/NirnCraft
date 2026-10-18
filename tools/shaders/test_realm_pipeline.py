"""Pinned-pack realm/local-media contracts. These tests do not claim GPU compiler or visual acceptance."""
import importlib.util
import pathlib
import re
import unittest
from PIL import Image

ROOT=pathlib.Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location("realm_pipeline",pathlib.Path(__file__).with_name("realm_pipeline.py"))
REALM=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REALM)


class RealmShaderContractTest(unittest.TestCase):
    maxDiff=1200

    @classmethod
    def setUpClass(cls):
        cls.files=REALM.build(next((ROOT/"dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip")))

    def test_all_six_realms_have_explicit_shader_routes(self):
        properties=self.files["shaders/dimension.properties"].decode()
        for dimension in ("minecraft:the_nether","minecraft:the_end","skycraft:shivering_isles",
                          "skycraft:apocrypha","skycraft:soul_cairn","skycraft:coldharbour"):
            mapping=[line.split("=",1) for line in properties.splitlines() if line.strip().startswith("dimension.")]
            matched=[key for key,values in mapping if dimension in values.split()]
            self.assertEqual(1,len(matched),dimension)

    def test_custom_folders_inherit_conditional_program_policies(self):
        import zipfile
        cached=next((ROOT/"dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"))
        with zipfile.ZipFile(cached) as pack: original=pack.read("shaders/shaders.properties").decode()
        patched=REALM.program_properties(original) if hasattr(REALM,"program_properties") else original
        for folder,(base,dimension,daynight) in REALM.REALMS.items():
            for program in ("shadow","composite7","shadowcomp"):
                self.assertIn(f"program.{folder}/{program}.enabled=false",patched)
        self.assertEqual(patched,REALM.program_properties(patched))
        # The clone must remain inside the original quality/color conditional,
        # so enabled colored-light propagation still runs when selected.
        block=patched.split("#if COLORED_LIGHTING == 0",1)[1].split("#endif",1)[0]
        for folder in REALM.REALMS:
            self.assertIn(f"program.{folder}/shadowcomp.enabled=false",block)

    def test_realm_tree_does_not_sample_overworld_weather_or_moon_packets(self):
        combined="\n".join(raw.decode() for name,raw in self.files.items() if name.startswith(REALM.PREFIX))
        for sampler in ("skycraftWeatherTex","skycraftWeatherFarTex","skycraftSmokeTex","skycraftDynamicsTex","skycraftLightningTex"):
            self.assertNotIn(sampler,combined)
        for include in ("local_media","interior","interior_density","fog_light","volume_noise","local_occlusion"):
            text=(ROOT/f"mod/src/main/resources/assets/skycraft/shaders/include/{include}.glsl").read_text()
            self.assertNotIn("skycraftSmokeTex",text)
            self.assertNotIn("SampleSkycraftWeather",text)

    def test_isles_keep_daynight_and_other_realms_keep_authored_sky_materials(self):
        isle=self.files["shaders/skycraftIsles/gbuffers_skybasic.fsh"].decode()
        self.assertIn("#define OVERWORLD",isle)
        self.assertIn("#define SKYCRAFT_REALM_DAYNIGHT",isle)
        for folder in ("world-1","world1","skycraftApocrypha","skycraftColdharbour","skycraftSoulCairn"):
            entry=self.files[f"shaders/{folder}/gbuffers_skybasic.fsh"].decode()
            self.assertNotIn("#define SKYCRAFT_REALM_DAYNIGHT",entry)
            self.assertIn('/skycraftRealm/program/',entry)
        self.assertIn("SKYCRAFT_AUTHORED_REALM_SKY",self.files[REALM.PREFIX+"program/gbuffers_skytextured.glsl"].decode())

    def test_surface_and_volumetric_emitters_share_the_local_contract(self):
        receiver=self.files[REALM.PREFIX+"lib/lighting/mainLighting.glsl"].decode()
        integral=self.files[REALM.PREFIX+"program/composite1.glsl"].decode()
        self.assertIn("SKYCRAFT_REALM_LOCAL_LIGHT",receiver)
        self.assertIn("SkycraftLocalLight(playerPos",receiver)
        self.assertIn("SKYCRAFT_REALM_LOCAL_INTEGRAL",integral)
        self.assertIn("min(lViewPos,lViewPos1)",integral)
        self.assertIn("mat3(gbufferModelViewInverse)*nViewPos",integral)
        media=(ROOT/"mod/src/main/resources/assets/skycraft/shaders/include/local_media.glsl").read_text()
        self.assertIn("SkycraftInteriorMedium(receiver)",media)
        self.assertIn("background*transmission+scattering",media)
        self.assertNotIn("SkycraftOutdoorLightExposure",media)

    def test_local_collision_probe_is_opt_in_and_unsupported_shapes_fail_readiness(self):
        java=(ROOT/"mod/src/main/java/com/skycraft/atmosphere/client/LocalOcclusionTexture.java").read_text()
        self.assertIn('Boolean.getBoolean("skycraft.localLightOcclusion")',java)
        self.assertIn("grid.overflow()==0",java)
        image=Image.open(ROOT/"mod/src/main/resources/assets/skycraft/textures/environment/local_occlusion.png")
        self.assertEqual((256,294),image.size)
        self.assertEqual((0,0,0,0),image.getpixel((3,260)))

    def test_realm_include_rewrite_is_closed_and_preserves_global_local_hooks(self):
        import zipfile
        cached=next((ROOT/"dist/.cache").glob("*ComplementaryReimagined_r5.9.3.zip"))
        with zipfile.ZipFile(cached) as upstream: original=set(upstream.namelist())
        custom={"shaders/lib/atmospherics/skycraftInterior.glsl","shaders/lib/atmospherics/skycraftLocalMedia.glsl"}
        for name,raw in self.files.items():
            for include in re.findall(r'#include\s+"(/[^\"]+)"',raw.decode()):
                target="shaders"+include
                self.assertTrue(target in self.files or target in original or target in custom,(name,include))


if __name__=="__main__": unittest.main()
