"""Scatter QA (offline, no Minecraft client): prop density vs distance along transects, annotated strips and close-ups.

A transect is a straight line from a settlement centre (origin) along a bearing, `length` blocks, `width` blocks wide.
Inputs: the saved world (region files), the per-chunk CSV of `/nirncraft-scatter export` and optionally the placement log
(`-Dnirncraft.scatter.log=<file>`, lines `x,z,zone,kind,human,humanity`).

usage:
  py -3 -I tools/qa/scatter_transects.py analyze --export export_62_-68.csv [--export ...] --transects transects.json [--out out.json]
  py -3 -I tools/qa/scatter_transects.py strip <world_dir> --transects transects.json --log placements.csv --out <dir>
  py -3 -I tools/qa/scatter_transects.py close <world_dir> --transects transects.json --out <dir> [--at 150,700,1300]
  py -3 -I tools/qa/scatter_transects.py sheet --transects transects.json --dir <dir>      (strip + close-ups per transect)

transects.json: [{"name","x","z","bearing"(degrees; 0 = +x east, 90 = +z south),"length"}].
"""
import argparse
import csv
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render_dungeon as RD  # noqa: E402
from mca import World  # noqa: E402
import town_checks as TC  # noqa: E402  (road network reader)

ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
WIDTH = 96            # strip width, blocks
BIN = 100             # analysis bin along the transect, blocks
HUMAN_COL = (255, 140, 0)
NATURE_COL = (0, 230, 230)
SPECIAL_COL = (255, 0, 255)
SETTLE_COL = (255, 255, 255)


# ------------------------------------------------------------------ geometry

def frame(t):
    a = math.radians(t['bearing'])
    return t['x'], t['z'], math.cos(a), math.sin(a)


def to_ts(t, x, z):
    """World (x, z) -> (distance along the transect, signed offset across)."""
    ox, oz, c, s = frame(t)
    dx, dz = x - ox, z - oz
    return dx * c + dz * s, -dx * s + dz * c


def from_ts(t, d, off):
    ox, oz, c, s = frame(t)
    return ox + c * d - s * off, oz + s * d + c * off


# ------------------------------------------------------------------ analysis

def load_export(paths):
    rows = {}
    for p in paths:
        with open(p, newline='', encoding='utf8') as f:
            for r in csv.DictReader(f):
                rows[(int(r['cx']), int(r['cz']))] = r
    return rows


def analyze(a):
    transects = json.load(open(a.transects))
    rows = load_export(a.export)
    res = {}
    lines = []
    for t in transects:
        bins = {}
        for (cx, cz), r in rows.items():
            if r['generated'] != '1':
                continue
            d, off = to_ts(t, cx * 16 + 8, cz * 16 + 8)
            if d < 0 or d > t['length'] or abs(off) > a.half:
                continue
            b = int(d // BIN)
            e = bins.setdefault(b, {'n': 0, 'human': 0, 'nature': 0, 'items': 0, 'zones': {}, 'hum': 0.0})
            e['n'] += 1
            e['human'] += int(r['human_items'])
            e['nature'] += int(r['nature_items'])
            e['items'] += int(r['items'])
            e['hum'] += float(r['humanity'])
            e['zones'][r['zone']] = e['zones'].get(r['zone'], 0) + 1
        out = []
        for b in sorted(bins):
            e = bins[b]
            n = max(1, e['n'])
            top = max(e['zones'].items(), key=lambda kv: kv[1])[0]
            out.append({'from': b * BIN, 'chunks': e['n'], 'human_per_chunk': round(e['human'] / n, 2),
                        'nature_per_chunk': round(e['nature'] / n, 2), 'items_per_chunk': round(e['items'] / n, 2),
                        'humanity': round(e['hum'] / n, 2), 'zone': top})
        res[t['name']] = out
        lines.append('%s (bearing %.1f from %d,%d)' % (t['name'], t['bearing'], t['x'], t['z']))
        lines.append('  dist   chunks  human/chunk  nature/chunk  humanity  zone')
        for r in out:
            lines.append('  %4d   %5d   %10.2f   %11.2f   %7.2f  %s' % (r['from'], r['chunks'], r['human_per_chunk'],
                                                                        r['nature_per_chunk'], r['humanity'], r['zone']))
        # trend: Spearman-ish via simple slope on bin index
        if len(out) >= 4:
            xs = np.arange(len(out), dtype=float)
            for key in ('human_per_chunk', 'nature_per_chunk'):
                ys = np.array([r[key] for r in out])
                lines.append('  slope %-17s %+.3f per %d blocks' % (key, np.polyfit(xs, ys, 1)[0], BIN))
        near = [r for r in out if r['from'] < 400]
        far = [r for r in out if r['from'] >= 1000]
        if near and far:
            h0 = sum(r['human_per_chunk'] for r in near) / len(near)
            h1 = sum(r['human_per_chunk'] for r in far) / len(far)
            n0 = sum(r['nature_per_chunk'] for r in near) / len(near)
            n1 = sum(r['nature_per_chunk'] for r in far) / len(far)
            lines.append('  human <400: %.2f  >=1000: %.2f   nature <400: %.2f  >=1000: %.2f' % (h0, h1, n0, n1))
    print('\n'.join(lines))
    if a.out:
        json.dump(res, open(a.out, 'w'), indent=1)


# ------------------------------------------------------------------ top-down strips

class Tops:
    """Per-column top block (leaves ignored) with a name table, read lazily from the region files."""

    def __init__(self, world):
        self.w = world
        self.cache = {}
        self.skip = {}

    def _skip(self, bid):
        v = self.skip.get(bid)
        if v is None:
            n = self.w.names[bid].split(':', 1)[-1]
            v = n in ('air', 'cave_air', 'void_air', 'barrier', 'light') or n.endswith('leaves') or n.endswith('_leaves')
            self.skip[bid] = v
        return v

    def chunk(self, cx, cz):
        k = (cx, cz)
        if k in self.cache:
            return self.cache[k]
        cb = self.w.chunk_blocks(cx, cz)
        if cb is None:
            self.cache[k] = None
            return None
        y0, arr = cb
        ids = np.unique(arr)
        skipmask = np.zeros(int(ids.max()) + 1, bool)
        for i in ids:
            skipmask[i] = self._skip(int(i))
        solid = ~skipmask[arr]                       # (Y, 16, 16)
        rev = solid[::-1]
        top = arr.shape[0] - 1 - np.argmax(rev, axis=0)
        any_ = solid.any(axis=0)
        zi, xi = np.meshgrid(np.arange(16), np.arange(16), indexing='ij')
        tid = arr[top, zi, xi]
        h = np.where(any_, top + y0, -64)
        self.cache[k] = (h.astype(np.int16), tid.astype(np.int32), any_)
        return self.cache[k]

    def at(self, x, z):
        c = self.chunk(x >> 4, z >> 4)
        if c is None:
            return None
        h, tid, ok = c
        if not ok[z & 15, x & 15]:
            return None
        return int(h[z & 15, x & 15]), int(tid[z & 15, x & 15])


def read_log(path):
    pts = []
    if not path or not os.path.exists(path):
        return pts
    with open(path, encoding='utf8') as f:
        for line in f:
            p = line.strip().split(',')
            if len(p) < 6 or p[3].startswith('#'):
                continue
            try:
                pts.append((int(p[0]), int(p[1]), p[2], p[3], p[4] == '1'))
            except ValueError:
                pass
    return pts


def places():
    out = []
    d = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'nirncraft', 'tamriel', 'places')
    for f in json.load(open(os.path.join(d, 'index.json'), encoding='utf8')):
        p = os.path.join(d, f)
        if os.path.exists(p):
            out += json.load(open(p, encoding='utf8'))
    return out


def strip(a):
    world = World(a.world)
    tops = Tops(world)
    transects = json.load(open(a.transects))
    pts = read_log(a.log)
    try:
        roads = TC.Roads(a.world)
    except Exception:
        roads = None
    pl = [p for p in places() if p['type'] in ('capital', 'city', 'town', 'village', 'farm', 'fort', 'dungeon_fixed', 'landmark')]
    os.makedirs(a.out, exist_ok=True)
    SEG, PX = 500, 3
    font = RD.font(13)
    for t in transects:
        rows = []
        colcache = {}
        n_seg = int(math.ceil(t['length'] / SEG))
        for si in range(n_seg):
            W, H = SEG * PX, WIDTH * PX
            img = np.zeros((H, W, 3), np.uint8)
            hs = np.full((H, W), np.nan, np.float32)
            for u in range(SEG):
                d = si * SEG + u
                for v in range(WIDTH):
                    x, z = from_ts(t, d + 0.5, v - WIDTH / 2 + 0.5)
                    r = tops.at(int(math.floor(x)), int(math.floor(z)))
                    if r is None:
                        img[v * PX:(v + 1) * PX, u * PX:(u + 1) * PX] = (10, 10, 12)
                        continue
                    y, bid = r
                    col = colcache.get(bid)
                    if col is None:
                        col = RD.base_color(world.names[bid])
                        colcache[bid] = col
                    hs[v * PX:(v + 1) * PX, u * PX:(u + 1) * PX] = y
                    img[v * PX:(v + 1) * PX, u * PX:(u + 1) * PX] = col
            gz = np.gradient(np.nan_to_num(hs, nan=0.0), axis=0)
            gx = np.gradient(np.nan_to_num(hs, nan=0.0), axis=1)
            shade = np.clip(1.0 + 0.5 * (-gx - gz), 0.7, 1.3)
            img = np.clip(img * shade[..., None], 0, 255).astype(np.uint8)
            im = Image.fromarray(img)
            dr = ImageDraw.Draw(im, 'RGBA')
            lo, hi = si * SEG, (si + 1) * SEG
            for (x, z, zone, kind, human) in pts:
                d, off = to_ts(t, x, z)
                if d < lo or d >= hi or abs(off) > WIDTH / 2:
                    continue
                px, py = (d - lo) * PX, (off + WIDTH / 2) * PX
                col = HUMAN_COL if human else NATURE_COL
                dr.rectangle([px - 4, py - 4, px + 4, py + 4], outline=col + (255,), fill=col + (90,), width=2)
            if roads is not None:
                x0, z0 = from_ts(t, lo, 0)
                x1, z1 = from_ts(t, hi, 0)
                rb = SEG + WIDTH
                for r in roads.in_box(min(x0, x1) - rb, min(z0, z1) - rb, max(x0, x1) + rb, max(z0, z1) + rb):
                    prev = None
                    for rx, rz in zip(r['xs'], r['zs']):
                        d, off = to_ts(t, int(rx), int(rz))
                        if d < lo or d >= hi or abs(off) > WIDTH / 2:
                            prev = None
                            continue
                        px, py = (d - lo) * PX, (off + WIDTH / 2) * PX
                        dr.rectangle([px - 1, py - 1, px + 1, py + 1], fill=(255, 235, 0, 255))
                        prev = (px, py)
            for p in pl:
                d, off = to_ts(t, p['x'], p['z'])
                r = p.get('radius', 20)
                if d + r < lo or d - r >= hi or abs(off) > WIDTH / 2 + r:
                    continue
                px, py = (d - lo) * PX, (off + WIDTH / 2) * PX
                dr.ellipse([px - r * PX, py - r * PX, px + r * PX, py + r * PX], outline=SETTLE_COL + (200,), width=2)
                dr.text((px + 4, py + 4), p['id'], fill=(255, 255, 255), font=font)
            # distance ticks
            for tick in range(lo, hi, 100):
                px = (tick - lo) * PX
                dr.line([px, 0, px, 10], fill=(255, 255, 0), width=2)
                dr.text((px + 3, 10), str(tick), fill=(255, 255, 0), font=font)
            rows.append(im)
        H = sum(r.height for r in rows) + 4 * (len(rows) + 1) + 28
        sheet = Image.new('RGB', (rows[0].width, H), (24, 24, 28))
        d = ImageDraw.Draw(sheet)
        d.text((6, 4), '%s: from %d,%d bearing %.1f, %d blocks, %d wide. orange = human debris, cyan = natural, yellow = planned road, white circles = places. '
                       'north is up-left of the bearing; distance ticks every 100.' % (t['name'], t['x'], t['z'], t['bearing'], t['length'], WIDTH),
               fill=(255, 255, 255), font=font)
        y = 28
        for r in rows:
            sheet.paste(r, (0, y))
            y += r.height + 4
        path = os.path.join(a.out, 'strip_%s.png' % t['name'])
        sheet.save(path)
        print('wrote', path, sheet.size)


# ------------------------------------------------------------------ iso close-ups

def close(a):
    world = World(a.world)
    transects = json.load(open(a.transects))
    os.makedirs(a.out, exist_ok=True)
    ats = [int(v) for v in a.at.split(',')]
    R = 22
    for t in transects:
        for d in ats:
            cx, cz = from_ts(t, d, 0)
            cx, cz = int(cx), int(cz)
            tops = Tops(world)
            r = tops.at(cx, cz)
            gy = r[0] if r else 70
            m = RD.Model(world, cx - R, cz - R, cx + R, cz + R, gy - 14, gy + 22)
            im = RD.iso_render(m, view=0, ymax=gy + 7, ybottom=gy - 8, scale=a.scale)
            im = RD.compose(im, [('close-up %d blocks out' % d, (150, 150, 150))],
                            '%s at %d blocks (%d,%d)' % (t['name'], d, cx, cz))
            path = os.path.join(a.out, 'close_%s_%d.png' % (t['name'], d))
            RD.finish(im, path, maxw=1000)


def sheet(a):
    transects = json.load(open(a.transects))
    ats = [int(v) for v in a.at.split(',')]
    for t in transects:
        parts = [Image.open(os.path.join(a.dir, 'strip_%s.png' % t['name'])).convert('RGB')]
        closes = []
        for d in ats:
            p = os.path.join(a.dir, 'close_%s_%d.png' % (t['name'], d))
            if os.path.exists(p):
                closes.append(Image.open(p).convert('RGB'))
        W = max(parts[0].width, 1500)
        if closes:
            h = max(c.height * (W // len(closes)) // c.width for c in closes)
            row = Image.new('RGB', (W, h), (24, 24, 28))
            cw = W // len(closes)
            for i, c in enumerate(closes):
                c2 = c.resize((cw, c.height * cw // c.width), Image.LANCZOS)
                row.paste(c2, (i * cw, 0))
            parts.append(row)
        H = sum(p.height for p in parts)
        out = Image.new('RGB', (W, H), (24, 24, 28))
        y = 0
        for p in parts:
            out.paste(p, (0, y))
            y += p.height
        path = os.path.join(a.dir, 'sheet_%s.jpg' % t['name'])
        out.save(path, quality=85)
        print('wrote', path, out.size)


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest='cmd', required=True)
    s = sp.add_parser('analyze')
    s.add_argument('--export', action='append', required=True)
    s.add_argument('--transects', required=True)
    s.add_argument('--out')
    s.add_argument('--half', type=int, default=48)
    s = sp.add_parser('strip')
    s.add_argument('world')
    s.add_argument('--transects', required=True)
    s.add_argument('--log')
    s.add_argument('--out', required=True)
    s = sp.add_parser('close')
    s.add_argument('world')
    s.add_argument('--transects', required=True)
    s.add_argument('--out', required=True)
    s.add_argument('--at', default='150,700,1300')
    s.add_argument('--scale', type=float, default=10)
    s = sp.add_parser('sheet')
    s.add_argument('--transects', required=True)
    s.add_argument('--dir', required=True)
    s.add_argument('--at', default='150,700,1300')
    a = ap.parse_args()
    {'analyze': analyze, 'strip': strip, 'close': close, 'sheet': sheet}[a.cmd](a)


if __name__ == '__main__':
    main()
