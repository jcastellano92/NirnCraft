"""Offline regression contracts; numerical checks do not replace live visual acceptance."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def source(path):
    return (ROOT / path).read_text(encoding="utf-8")


class RenderingRegressionTest(unittest.TestCase):
    maxDiff = 2000

    def test_air_packet_rebases_its_explicit_world_anchor(self):
        writer = source("mod/src/main/java/com/nirncraft/atmosphere/client/InteriorAtmosphere.java")
        reader = source("mod/src/main/resources/assets/nirncraft/shaders/include/interior.glsl")
        self.assertIn("NirnCraftAirOrigin", reader)
        self.assertIn("LocalAirCoordinates.anchor", writer)
        self.assertNotIn("subtract(camera)", writer)

    def test_fire_medium_records_depth_and_only_stops_at_zero_remaining_weight(self):
        patch = source("tools/shaders/patch_complementary_clouds.py")
        self.assertIn("inout float firstHitPos", patch)
        self.assertIn("if (volumetricClouds.a >= 1.0)", patch)
        self.assertNotIn("if (volumetricClouds.a > 0.995)", patch)

    def test_wind_interpolation_is_circular_at_both_grid_scales(self):
        patch = source("tools/shaders/patch_complementary_clouds.py")
        self.assertIn("NirnCraftMixWindAngle", patch)
        self.assertIn("NirnCraftSampleDynamics", patch)

    def test_cloud_queries_are_nonblocking_and_have_explicit_stop(self):
        cloud = source("mod/src/main/java/com/nirncraft/atmosphere/client/CloudRenderer.java")
        self.assertIn("GL_QUERY_RESULT_AVAILABLE", cloud)
        self.assertIn("stopStats()", cloud)
        tour = source("mod/src/main/java/com/nirncraft/visual/client/CloudTour.java")
        self.assertIn("CloudRenderer.stopStats()", tour)

    def test_cloud_result_carries_session_identity(self):
        cloud = source("mod/src/main/java/com/nirncraft/atmosphere/client/CloudRenderer.java")
        self.assertIn("BuildResult(long epoch", cloud)
        self.assertIn("pending.epoch() != generation", cloud)
        self.assertIn("buffer.release()", cloud)

    def test_weather_reload_discards_pending_work_before_acceptance(self):
        weather=source("mod/src/main/java/com/nirncraft/atmosphere/client/WeatherFieldTexture.java")
        cloud=source("mod/src/main/java/com/nirncraft/atmosphere/client/CloudRenderer.java")
        reset=weather.index("if(trackedLevel!=level || staticInputEpoch!=epoch)")
        accept=weather.index("Grid grid=pendingGrid.join()")
        self.assertLess(reset,accept)
        self.assertIn("pendingGrid.cancel(false)",weather[reset:accept])
        self.assertIn("pendingGrid=null",weather[reset:accept])
        self.assertIn("new Grid(identity,",weather)
        self.assertIn("if(grid.identity==samplingIdentity)",weather)
        self.assertIn("WeatherFieldTexture.invalidateStaticInputs()",cloud)

    def test_temporary_fire_filters_level_identity(self):
        fire = source("mod/src/main/java/com/nirncraft/fire/client/ClientFire.java")
        self.assertIn("temporaryLevel", fire)
        self.assertIn("ensureTemporaryLevel()", fire)

    def test_first_person_size_updates_the_tick_growth_base(self):
        particle = source("mod/src/main/java/com/nirncraft/visual/client/AtmosParticle.java")
        self.assertIn("baseSize *= scale", particle)

    def test_all_media_texture_uploads_preserve_gl_binding_state(self):
        for name in ("FogRadianceTexture","SmokeSourceTexture","LightningFieldTexture",
                     "WeatherFieldTexture","SunHorizonTexture","CloudTexture","LocalOcclusionTexture"):
            text=source("mod/src/main/java/com/nirncraft/atmosphere/client/"+name+".java")
            self.assertIn("TextureUploadState.preserve()",text,name)
        uploader=source("mod/src/main/java/com/nirncraft/atmosphere/client/TextureUploadState.java")
        self.assertIn("TextureBindingState(BINDINGS)",uploader)
        self.assertIn("depth++==0",uploader)
        cloud=source("mod/src/main/java/com/nirncraft/atmosphere/client/CloudRenderer.java")
        self.assertIn("requestTextureProbe()",cloud)
        self.assertIn("AFTER_SOLID_BLOCKS",cloud)

    def test_header_uploads_reject_resource_replacement_ids(self):
        for name in ("FogRadianceTexture","SmokeSourceTexture"):
            text=source("mod/src/main/java/com/nirncraft/atmosphere/client/"+name+".java")
            self.assertIn("!currentTexture()",text,name)
            self.assertIn("texture.getId()==uploadedId",text,name)

    def test_deep_render_probe_is_requested_and_preserves_read_target(self):
        cloud=source("mod/src/main/java/com/nirncraft/atmosphere/client/CloudRenderer.java")
        self.assertIn("if(!textureProbe) return;",cloud)
        self.assertIn("Stage.AFTER_SKY",cloud)
        probe=source("mod/src/main/java/com/nirncraft/atmosphere/client/RenderStateProbe.java")
        self.assertIn("finally { GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,old); }",probe)
        self.assertIn("GL_PIXEL_PACK_BUFFER_BINDING",probe)
        self.assertNotIn("setAccessible",probe)
        self.assertIn("shouldUseExtendedVertexFormat",probe)

    def test_native_meshes_only_build_for_native_path(self):
        sky = source("mod/src/main/java/com/nirncraft/atmosphere/client/SkyRenderer.java")
        self.assertIn("if (!shadersActive) SkyGeometry.ensureBuilt();", sky)


if __name__ == "__main__":
    unittest.main()
