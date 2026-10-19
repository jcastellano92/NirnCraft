import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("entity_tags_263",HERE/"entity_tags_263.py")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class EntityTagsTest(unittest.TestCase):
    def test_native_membership_is_additive_complete_and_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); path = root/"minecraft/tags/entity_type/undead.json"
            path.parent.mkdir(parents=True); path.write_text(json.dumps({"replace":False,"values":["other:ancestor",{"id":"other:optional","required":False}]}))
            self.assertEqual(7,m.upgrade(root)); before = {p:p.read_bytes() for p in root.rglob("*.json")}
            self.assertEqual(0,m.upgrade(root)); self.assertEqual(before,{p:p.read_bytes() for p in root.rglob("*.json")})
            for tag in ("undead","inverted_healing_and_harm","sensitive_to_smite"):
                data = json.loads((path.parent/(tag+".json")).read_text())
                self.assertFalse(data["replace"])
                for entity in ("keeper","boneman","mistman","wrathman"):
                    self.assertIn("nirncraft:"+entity,data["values"])
                self.assertNotIn("nirncraft:clannfear",data["values"])
            self.assertEqual("other:ancestor",json.loads(path.read_text())["values"][0])

if __name__ == "__main__": unittest.main()
