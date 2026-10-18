"""Accept Prism's actual native dependency resolution without allowing a platform change."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('live_world', Path(__file__).parent / 'dev/live_world.py')
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


class NativeComponents(unittest.TestCase):
    def test_instance_selector_rejects_normal_and_legacy_instances(self):
        for name in ('Skycraft', 'Skycraft-autotest', '../Skycraft', 'Other'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                live.selected_instance(name)
        self.assertEqual(live.TARGET['instanceName'], live.selected_instance(live.TARGET['instanceName']))
        self.assertEqual(live.DIAGNOSTIC_NAME, live.selected_instance(live.DIAGNOSTIC_NAME))

    def test_exact_platform_before_and_after_prism_resolution(self):
        components = [{'uid': 'net.minecraft', 'version': '26.3'}, {'uid': 'net.neoforged', 'version': '26.3.0.90'}]
        live.validate_components(components)
        live.validate_components(components + [{'uid': 'org.lwjgl3', 'version': '3.4.3', 'dependencyOnly': True}])
        for extra in ({'uid': 'org.lwjgl3', 'version': '3.4.2', 'dependencyOnly': True},
                      {'uid': 'org.lwjgl3', 'version': '3.4.3'},
                      {'uid': 'net.minecraftforge', 'version': '47.3.0'},
                      {'uid': 'net.minecraft', 'version': '26.2'}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                live.validate_components(components + [extra])


if __name__ == '__main__':
    unittest.main()
