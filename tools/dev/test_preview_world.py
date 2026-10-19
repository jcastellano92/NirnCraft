import gzip
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import preview_world as preview


def name(value):
    raw=value.encode();return struct.pack('>H',len(raw))+raw


def compound(key,payload):return b'\x0a'+name(key)+payload+b'\x00'


class PreviewWorldTest(unittest.TestCase):
    def test_selection_keeps_other_flags_and_records_expected_seed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);game=root/'game';level=game/'saves/qa_test/level.dat';level.parent.mkdir(parents=True)
            level.write_bytes(gzip.compress(compound('',compound('Data',compound('WorldGenSettings',b'\x04'+name('seed')+struct.pack('>q',2428247))))))
            original='JvmArgs="-Xmx4G -Dnirncraft.autotest.load=old -Dnirncraft.autotest.live=true"\nOther=value\n'
            (root/'instance.cfg').write_text(original)
            with patch.multiple(preview.live,INSTANCE=root,GAME=game,DEV=root/'dev'),patch.object(preview,'close_launcher') as close:
                preview.select('qa_test');close.assert_called_once()
            self.assertEqual(original.replace('load=old','load=qa_test'),(root/'instance.cfg').read_text())
            self.assertEqual(2428247,json.loads((root/'dev/selected-world.json').read_text())['seed'])
            self.assertEqual(1,len(list((root/'dev/config-backups').glob('*'))))

    def test_unowned_world_and_escaping_name_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):preview.owned_source(Path(temp))
            with self.assertRaises(ValueError):preview.select('../normal-world')


if __name__=='__main__':unittest.main()
