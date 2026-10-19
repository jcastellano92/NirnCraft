"""Minimal Anvil (.mca) reader for Minecraft 1.18+ worlds: stdlib + numpy only. Finds the region folder of 26.3
(world/dimensions/minecraft/overworld/region) and of 1.20.1 (world/region).
Use: w = World(world_dir); arr = w.box(x1,z1,x2,z2,y1,y2) -> uint16 [y,z,x] of block ids,
w.names[id] = 'minecraft:stone'. Also w.block_entities(x1,z1,x2,z2)."""
import glob
import os
import struct
import sys
import zlib

import numpy as np


def _nbt(buf):
    pos = 0

    def rd(t):
        nonlocal pos
        if t == 1:
            v = struct.unpack_from('>b', buf, pos)[0]; pos += 1; return v
        if t == 2:
            v = struct.unpack_from('>h', buf, pos)[0]; pos += 2; return v
        if t == 3:
            v = struct.unpack_from('>i', buf, pos)[0]; pos += 4; return v
        if t == 4:
            v = struct.unpack_from('>q', buf, pos)[0]; pos += 8; return v
        if t == 5:
            v = struct.unpack_from('>f', buf, pos)[0]; pos += 4; return v
        if t == 6:
            v = struct.unpack_from('>d', buf, pos)[0]; pos += 8; return v
        if t == 7:
            n = struct.unpack_from('>i', buf, pos)[0]; pos += 4
            v = buf[pos:pos + n]; pos += n; return v
        if t == 8:
            n = struct.unpack_from('>H', buf, pos)[0]; pos += 2
            v = buf[pos:pos + n].decode('utf8', 'replace'); pos += n; return v
        if t == 9:
            it = buf[pos]; n = struct.unpack_from('>i', buf, pos + 1)[0]; pos += 5
            return [rd(it) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = buf[pos]; pos += 1
                if tt == 0:
                    return d
                n = struct.unpack_from('>H', buf, pos)[0]; pos += 2
                k = buf[pos:pos + n].decode('utf8', 'replace'); pos += n
                d[k] = rd(tt)
        if t == 11:
            n = struct.unpack_from('>i', buf, pos)[0]; pos += 4
            v = np.frombuffer(buf, '>i4', n, pos); pos += 4 * n; return v
        if t == 12:
            n = struct.unpack_from('>i', buf, pos)[0]; pos += 4
            v = np.frombuffer(buf, '>i8', n, pos).astype(np.int64); pos += 8 * n; return v
        raise ValueError(t)
    t = buf[pos]; pos += 1
    n = struct.unpack_from('>H', buf, pos)[0]; pos += 2 + n
    return rd(t)


def _unpack(longs, count, bits):
    per = 64 // bits
    a = np.asarray(longs).astype(np.int64).view(np.uint64)
    shifts = np.arange(per, dtype=np.uint64) * np.uint64(bits)
    vals = (a[:, None] >> shifts[None, :]) & np.uint64((1 << bits) - 1)
    return vals.reshape(-1)[:count].astype(np.int32)


def region_dir(d, dimension='minecraft/overworld'):
    """The region folder of a world: 26.3 stores world/dimensions/<ns>/<dim>/region, 1.20.1 world/region (overworld),
    and a region folder itself is accepted too. None when there is none."""
    cands = [os.path.join(d, 'dimensions', *dimension.split('/'), 'region')]
    if dimension == 'minecraft/overworld':
        cands.append(os.path.join(d, 'region'))
    for r in cands:
        if os.path.isdir(r):
            return r
    if glob.glob(os.path.join(d, 'r.*.mca')):
        return d
    return None


class World:
    def __init__(self, d, dimension='minecraft/overworld'):
        r = region_dir(d, dimension)
        if r is None:
            print('mca: no region files in %s (looked for dimensions/%s/region, region/ and r.*.mca)' % (d, dimension),
                  file=sys.stderr)
        self.rdir = r or d
        self.names = ['minecraft:air']
        self.idx = {'minecraft:air': 0}
        self._reg = {}
        self._chunks = {}

    def _bid(self, name):
        i = self.idx.get(name)
        if i is None:
            i = len(self.names); self.idx[name] = i; self.names.append(name)
        return i

    def _region(self, rx, rz):
        k = (rx, rz)
        if k not in self._reg:
            p = os.path.join(self.rdir, 'r.%d.%d.mca' % (rx, rz))
            try:
                with open(p, 'rb') as f:
                    self._reg[k] = f.read()
            except OSError:
                self._reg[k] = None
        return self._reg[k]

    def chunk_nbt(self, cx, cz):
        k = (cx, cz)
        if k in self._chunks:
            return self._chunks[k]
        r = self._region(cx >> 5, cz >> 5)
        out = None
        if r and len(r) >= 8192:
            off = struct.unpack_from('>I', r, 4 * ((cx & 31) + (cz & 31) * 32))[0]
            if off:
                o = (off >> 8) * 4096
                ln = struct.unpack_from('>I', r, o)[0]
                comp = r[o + 4]
                data = r[o + 5:o + 4 + ln]
                try:
                    if comp == 2:
                        data = zlib.decompress(data)
                    out = _nbt(data)
                except Exception:
                    out = None
        self._chunks[k] = out
        return out

    def chunk_blocks(self, cx, cz):
        c = self.chunk_nbt(cx, cz)
        if not c or not c.get('sections'):
            return None
        secs = c['sections']
        ys = [s['Y'] for s in secs]
        y0, y1 = min(ys), max(ys)
        arr = np.zeros(((y1 - y0 + 1) * 16, 16, 16), np.uint16)
        for s in secs:
            bs = s.get('block_states')
            if not bs:
                continue
            pal = [self._bid(p['Name']) for p in bs['palette']]
            if len(pal) == 1 or 'data' not in bs:
                vals = np.full(4096, pal[0], np.uint16)
            else:
                bits = max(4, (len(pal) - 1).bit_length())
                vals = np.array(pal, np.uint16)[_unpack(bs['data'], 4096, bits)]
            yy = (s['Y'] - y0) * 16
            arr[yy:yy + 16] = vals.reshape(16, 16, 16)
        return y0 * 16, arr

    def box(self, x1, z1, x2, z2, y1, y2):
        out = np.zeros((y2 - y1 + 1, z2 - z1 + 1, x2 - x1 + 1), np.uint16)
        for cz in range(z1 >> 4, (z2 >> 4) + 1):
            for cx in range(x1 >> 4, (x2 >> 4) + 1):
                cb = self.chunk_blocks(cx, cz)
                if cb is None:
                    continue
                cy0, a = cb
                bx0, bz0 = cx * 16, cz * 16
                xa, xb = max(x1, bx0), min(x2, bx0 + 15)
                za, zb = max(z1, bz0), min(z2, bz0 + 15)
                ya, yb = max(y1, cy0), min(y2, cy0 + a.shape[0] - 1)
                if xa > xb or za > zb or ya > yb:
                    continue
                out[ya - y1:yb - y1 + 1, za - z1:zb - z1 + 1, xa - x1:xb - x1 + 1] = \
                    a[ya - cy0:yb - cy0 + 1, za - bz0:zb - bz0 + 1, xa - bx0:xb - bx0 + 1]
        return out

    def block_entities(self, x1, z1, x2, z2):
        res = []
        for cz in range(z1 >> 4, (z2 >> 4) + 1):
            for cx in range(x1 >> 4, (x2 >> 4) + 1):
                c = self.chunk_nbt(cx, cz)
                if not c:
                    continue
                for be in c.get('block_entities', []) or []:
                    if x1 <= be['x'] <= x2 and z1 <= be['z'] <= z2:
                        res.append(be)
        return res
