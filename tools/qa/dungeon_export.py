"""Loader for the dungeon exports written by `/nirncraft-dungeon-qa export <place id>...` (QaExport.java).

An export (`mod/run/qa-exports/<id>.json.gz`) holds the dungeon exactly as the painter lays it into the in-memory
level: a block palette + run-length blocks over `box`, plus semantic markers (entities, containers, lecterns,
spawners, mechanisms, pieces, entrances). stdlib + numpy only.

    ex = load('mod/run/qa-exports/alftand.json.gz')
    ex.A            uint16 [y, z, x] of palette indexes (0 = minecraft:air)
    ex.names[i]     base block id of palette entry i ('minecraft:stone')
    ex.coll[i]      collision class char: a none, l low, s stairs, f full, t tall, d hand door, m locked, c climb, w water, v lava
"""
import base64
import gzip
import json
import re

import numpy as np


class Export:
    pass


def _varints(buf):
    out = []
    cur = 0
    shift = 0
    for b in buf:
        cur |= (b & 0x7F) << shift
        if b & 0x80:
            shift += 7
        else:
            out.append(cur)
            cur = 0
            shift = 0
    return out


def load(path):
    with gzip.open(path, 'rt', encoding='utf8') as f:
        d = json.load(f)
    ex = Export()
    ex.meta = {k: d[k] for k in ('format', 'id', 'label', 'structure', 'theme', 'kit', 'special', 'surfaceY') if k in d}
    ex.id = d['id']
    x1, y1, z1, x2, y2, z2 = d['box']
    ex.box = (x1, y1, z1, x2, y2, z2)
    X, Y, Z = x2 - x1 + 1, y2 - y1 + 1, z2 - z1 + 1
    pal = ['minecraft:air'] + d['palette']       # index 0 reserved for "outside the export"
    coll = 'a' + d['coll']
    vals = _varints(base64.b64decode(d['rle']))
    idx = np.array(vals[0::2], np.int64) + 1
    runs = np.array(vals[1::2], np.int64)
    flat = np.repeat(idx.astype(np.uint16), runs)
    ex.A = flat.reshape(Y, Z, X)
    ex.palette = pal
    ex.names = [re.sub(r'\[.*$', '', p) for p in pal]
    ex.coll = coll
    ex.pieces = d.get('pieces', [])
    ex.entrances = d.get('entrances', [])
    ex.entities = d.get('entities', [])
    ex.containers = d.get('containers', [])
    ex.lecterns = d.get('lecterns', [])
    ex.spawners = d.get('spawners', [])
    ex.mechanisms = d.get('mechanisms', [])
    return ex


def rooms(ex):
    return [p for p in ex.pieces if p['kind'] == 'room']


def is_boss(e):
    return bool(e.get('boss'))
