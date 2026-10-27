import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from PIL import Image
import probe_visuals as v


class ProbeVisualsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        self.evidence = self.root / 'evidence'; self.evidence.mkdir()
        self.shots = self.root / 'shots'; self.shots.mkdir()
        self.now = time.time()
    def tearDown(self): self.tmp.cleanup()
    def pair(self, tag, captured=None):
        Image.new('RGB', (24, 16), 'red').save(self.shots / (tag + '.png'))
        (self.shots / (tag + '.json')).write_text(json.dumps({'capturedAt': (self.now + 1 if captured is None else captured) * 1000}))
        for suffix in ('.png', '.json'): os.utime(self.shots / (tag + suffix), (self.now + 1, self.now + 1))
    def test_aborted_worker_recovery_and_preserved_original(self):
        v.journal(self.evidence, ['fresh'], self.now); self.pair('fresh')
        r = v.recover(self.evidence, self.shots, self.now + 2)
        self.assertEqual(r['captures'][0]['state'], 'RECOVERED')
        self.assertEqual((self.shots / 'fresh.png').read_bytes(), (self.evidence / 'visual-originals/fresh.png').read_bytes())
        (self.shots / 'fresh.png').unlink(); (self.shots / 'fresh.json').unlink()
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 0)
        self.assertTrue((self.evidence / 'visual-sheet.jpg').exists())
    def test_stale_and_partial_pairs_are_not_accepted(self):
        v.journal(self.evidence, ['stale', 'partial', 'badmeta'], self.now)
        self.pair('stale', self.now - 1); self.pair('partial'); self.pair('badmeta')
        (self.shots / 'partial.png').write_bytes(b'\x89PNG\r\n')
        (self.shots / 'badmeta.json').write_text('{')
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 3)
    def test_route_progress_marks_missing_without_pass(self):
        tags = ['route_c%02d' % i for i in range(1, 11)]
        v.journal(self.evidence, tags, self.now)
        for tag in tags[:3]: self.pair(tag)
        r = v.recover(self.evidence, self.shots, self.now + 3)
        self.assertEqual(r['missing'], 7); self.assertEqual(r['state'], 'INCREMENTAL_UNVERIFIED')
        for tag in tags[3:]: self.pair(tag)
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 0)
    def test_retained_hash_mismatch_is_repaired_or_missing(self):
        v.journal(self.evidence, ['a'], self.now); self.pair('a')
        v.recover(self.evidence, self.shots, self.now + 3)
        saved = self.evidence / 'visual-originals/a.png'; saved.write_bytes(b'corrupt')
        repaired = v.recover(self.evidence, self.shots, self.now + 3)
        self.assertTrue(repaired['captures'][0]['repaired'])
        self.assertEqual(saved.read_bytes(), (self.shots / 'a.png').read_bytes())
        (self.evidence / 'visual-originals/a.json').write_bytes(b'corrupt')
        (self.shots / 'a.png').unlink(); (self.shots / 'a.json').unlink()
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 1)

    def test_sheet_write_failure_and_deletion_retry(self):
        v.journal(self.evidence, ['a'], self.now); self.pair('a')
        with patch.object(Image.Image, 'save', side_effect=OSError('disk failure')):
            with self.assertRaises(OSError): v.recover(self.evidence, self.shots, self.now + 3)
        self.assertFalse((self.evidence / 'visual-index.json').exists())
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 0)
        (self.evidence / 'visual-sheet.jpg').unlink()
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 0)
        self.assertTrue((self.evidence / 'visual-sheet.jpg').is_file())

    def test_valid_crc_but_corrupt_compressed_png_rejected(self):
        import struct, zlib
        v.journal(self.evidence, ['a'], self.now); self.pair('a')
        path = self.shots / 'a.png'; data = bytearray(path.read_bytes()); at = data.index(b'IDAT')
        size = struct.unpack('>I', data[at-4:at])[0]
        data[at+4] ^= 255
        data[at+4+size:at+8+size] = struct.pack('>I', zlib.crc32(data[at:at+4+size]))
        path.write_bytes(data); os.utime(path, (self.now + 1, self.now + 1))
        self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 1)

    def test_unchanged_retained_pair_rehashes_without_decode(self):
        v.journal(self.evidence, ['a'], self.now); self.pair('a')
        v.recover(self.evidence, self.shots, self.now + 3)
        with patch.object(v, '_thumbnail', side_effect=AssertionError('unexpected repeated decode')):
            self.assertEqual(v.recover(self.evidence, self.shots, self.now + 3)['missing'], 0)

    def test_refuses_duplicate_and_unsafe_tags(self):
        v.journal(self.evidence, ['a'], self.now)
        with self.assertRaises(ValueError): v.journal(self.evidence, ['a'])
        with self.assertRaises(ValueError): v.journal(self.evidence, ['../escape'])
