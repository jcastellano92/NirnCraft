"""Startup diagnostics must not mask later successful output or hide a later failure."""
import unittest
import game
import atlas_vulkan_probe as probe


class AtlasStartupStatusTest(unittest.TestCase):
    on = "[Render/INFO] NirnCraft: Infinite ATLAS far field on (Vulkan shared device) on GPU"
    off = "[Render/WARN] NirnCraft: Infinite ATLAS far field off: no compositor"
    context = "[Render/INFO] Infinite ATLAS far field on (raster columns): atlas_core 0.1.0 (abi 0.18.0; slang far kernel; windows)"

    def test_delivered_on_supersedes_startup_off(self):
        self.assertIsNone(game.current_atlas_failure(self.off+"\n"+self.context+"\n"+self.on))

    def test_latest_off_or_stopped_still_fails(self):
        self.assertIn("no compositor", game.current_atlas_failure(self.on+"\n"+self.off))
        self.assertIn("stopped", game.current_atlas_failure(self.on+"\nInfinite ATLAS stopped: device lost"))
        self.assertIsNone(game.current_atlas_failure("Infinite ATLAS stopped: old\n"+self.on))

    def test_context_creation_is_not_delivery_recovery(self):
        self.assertIn("no compositor", game.current_atlas_failure(self.off+"\n"+self.context))
        self.assertIsNone(game.current_atlas_failure(self.context))  # still pending, not an off error

    def test_real_native_version_suffix_is_recognized(self):
        self.assertTrue(probe.abi_logged(self.context))
        self.assertTrue(probe.abi_logged("atlas_core 0.1.0 (abi 0.18.0)"))
        self.assertFalse(probe.abi_logged("atlas_core 0.1.0 (abi 0.17.0; old)"))
        self.assertFalse(probe.abi_logged("atlas_core 0.1.0 (abi 0.18.01; malformed)"))


if __name__=="__main__": unittest.main()
