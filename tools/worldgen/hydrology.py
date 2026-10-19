"""Hydrology for the Tamriel map: flow routing, tributaries, springs, lake basins, deltas, waterfalls and river sites.

Two uses (pure numpy + heapq; scipy only for labelling when installed):

* imported by tamriel_map.py, which calls the derive_* functions while it builds the rasters (so the streams are
  carved into the macro height field and written into the same rasters the Java side already reads), and writes
  `hydrology.json` (waterfalls, springs, river sites) next to them;
* `py -3 -I tools/worldgen/hydrology.py analyze [--dir DIR] [--out DIR]` analyses the BAKED rasters (flow
  accumulation with pit filling) against the authored rivers and lakes and writes the numbers (JSON) and map views.
  `py -3 -I tools/worldgen/hydrology.py render --before DIR --after DIR --out DIR` renders before/after views.

Everything is deterministic: no random numbers, only hashes of positions.

Method (see docs/HYDROLOGY.md): priority-flood depression filling with an epsilon gradient (Barnes 2014) which
yields the drainage tree directly (every land cell drains to a lower neighbour, or to the sea / map edge); D8
receivers, flow accumulation weighted by a runoff index (province climate, orographic boost, dune dryness).
"""
import heapq
import json
import math
import os
import sys

import numpy as np
from PIL import Image

try:
    from scipy import ndimage as _ndi
except ImportError:  # pragma: no cover
    _ndi = None

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
RES_DIR = os.path.join(ROOT, "mod", "src", "main", "resources", "nirncraft", "tamriel")

CELL = 32
SEA = 63
EPS = 1e-3
D8 = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]

# runoff index per province (rain and snow-melt); regions below override
PROV_RUNOFF = {"skyrim": 1.3, "cyrodiil": 1.0, "high_rock": 1.1, "morrowind": 0.8, "black_marsh": 1.4, "elsweyr": 0.35,
               "hammerfell": 0.4, "valenwood": 1.5, "summerset": 1.1, "atmora": 1.0, "akavir": 1.0, "yokuda": 0.8,
               "pyandonea": 1.0, "thras": 1.0}
REGION_RUNOFF = {"morrowind/vvardenfell_ashlands": 0.3, "morrowind/molag_amur": 0.4, "morrowind/red_mountain": 0.3,
                 "cyrodiil/colovian_highlands": 0.9, "skyrim/the_pale": 1.0}

# stream extraction
STREAM_T = 44.0        # weighted upstream cells (32-block cells) at which a brook starts
MIN_STREAM_CELLS = 3   # shortest piece worth keeping
FALL_WINDOW = 48.0     # blocks over which a level drop counts as one fall
FALL_MIN_STREAM = 6.5  # blocks
FALL_MIN_RIVER = 7.0
FALL_BIG = 9.0         # "big" waterfalls: eligible for secrets and overlooks
FALL_SPACING = 160.0
MARSH_TRUNK = 800.0    # weighted cells: bigger flows through a marsh still get a channel


def route_noise(shape, amp=1.1):
    """A fixed smooth wobble (about a block) added to the surface the flow is routed over, so streams on a planar
    slope wander instead of running as ruler-straight D8 diagonals. Seeded: the same everywhere, every run."""
    H, W = shape
    out = np.zeros(shape)
    for step, a, seed in ((6, amp, 11), (2, amp * 0.5, 23)):
        rs = np.random.RandomState(seed)
        gh, gw = H // step + 2, W // step + 2
        g = rs.standard_normal((gh, gw))
        ys = np.arange(H) / step
        xs = np.arange(W) / step
        iy, ix = ys.astype(int), xs.astype(int)
        ty, tx = (ys - iy)[:, None], (xs - ix)[None, :]
        ty, tx = ty * ty * (3 - 2 * ty), tx * tx * (3 - 2 * tx)
        a00 = g[iy][:, ix]
        a10 = g[iy][:, ix + 1]
        a01 = g[iy + 1][:, ix]
        a11 = g[iy + 1][:, ix + 1]
        out += a * ((a00 * (1 - tx) + a10 * tx) * (1 - ty) + (a01 * (1 - tx) + a11 * tx) * ty)
    return out


def smoothstep(e0, e1, x):
    t = np.clip((np.asarray(x, float) - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def hash2(ix, iz, salt=0):
    """Deterministic 0..1 hash of two integers (no RNG state anywhere)."""
    h = (int(ix) * 374761393 + int(iz) * 668265263 + salt * 1442695041) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h ^= h >> 16
    return (h & 0xFFFFFF) / float(0xFFFFFF)


class Grid:
    """Raster geometry (cell size 32, pixel (0, 0) is the cell whose north-west corner is (min_x, min_z))."""

    def __init__(self, min_x, min_z, w, h):
        self.min_x, self.min_z, self.w, self.h = min_x, min_z, w, h
        self.xs = min_x + (np.arange(w) + 0.5) * CELL
        self.zs = min_z + (np.arange(h) + 0.5) * CELL

    def cell_of(self, x, z):
        return (int(np.clip(math.floor((x - self.min_x) / CELL), 0, self.w - 1)),
                int(np.clip(math.floor((z - self.min_z) / CELL), 0, self.h - 1)))

    def bil(self, a, x, z):
        fx = np.clip((np.asarray(x, float) - self.min_x) / CELL - 0.5, 0, self.w - 1.001)
        fz = np.clip((np.asarray(z, float) - self.min_z) / CELL - 0.5, 0, self.h - 1.001)
        ix, iz = fx.astype(int), fz.astype(int)
        tx, tz = fx - ix, fz - iz
        a00, a10, a01, a11 = a[iz, ix], a[iz, ix + 1], a[iz + 1, ix], a[iz + 1, ix + 1]
        return (a00 * (1 - tx) + a10 * tx) * (1 - tz) + (a01 * (1 - tx) + a11 * tx) * tz


# ---------------------------------------------------------------------------------------------------------------------
# flow routing
def flood(h, sink):
    """Priority-flood fill with epsilon. `sink` marks cells that drain away (sea; the raster edge drains too).
    Returns (recv, order, filled): recv[i] = flat index of the downstream neighbour (-1: drains to the sea / edge,
    -2: sink cell), order = cells in the order they were flooded (ascending filled elevation), filled = surface."""
    H, W = h.shape
    PW = W + 2
    hp = np.zeros((H + 2, PW))
    hp[1:-1, 1:-1] = h
    snk = np.ones((H + 2, PW), bool)
    snk[1:-1, 1:-1] = sink
    nb = np.zeros_like(snk)
    for dz, dx in D8:
        nb |= np.roll(np.roll(snk, dz, 0), dx, 1)
    seeds = np.flatnonzero((nb & ~snk).ravel())
    hl = hp.ravel().tolist()
    fl = hl[:]
    n = (H + 2) * PW
    recv = [-2] * n
    done = bytearray(snk.ravel().astype(np.uint8).tobytes())
    heap = []
    for c in seeds.tolist():
        done[c] = 1
        recv[c] = -1
        heap.append((hl[c], c))
    heapq.heapify(heap)
    offs = [dz * PW + dx for dz, dx in D8]
    order = []
    pop, push = heapq.heappop, heapq.heappush
    while heap:
        f, c = pop(heap)
        order.append(c)
        for o in offs:
            m = c + o
            if done[m]:
                continue
            done[m] = 1
            hn = hl[m]
            if hn <= f:
                hn = f + EPS
            fl[m] = hn
            recv[m] = c
            push(heap, (hn, m))
    rec = np.array(recv, dtype=np.int64).reshape(H + 2, PW)[1:-1, 1:-1]
    filled = np.array(fl).reshape(H + 2, PW)[1:-1, 1:-1]
    ordr = np.array(order, dtype=np.int64)
    ordr = (ordr // PW - 1) * W + (ordr % PW - 1)
    out = np.where(rec >= 0, (rec // PW - 1) * W + (rec % PW - 1), rec)
    return out.ravel(), ordr, filled


def accumulate(recv, order, weight):
    """Flow accumulation: sum of `weight` over every cell that drains through a cell (itself included)."""
    acc = weight.astype(float).ravel().copy()
    r = recv
    for c in order[::-1].tolist():
        t = r[c]
        if t >= 0:
            acc[t] += acc[c]
    return acc.reshape(weight.shape)


def runoff_weight(grid, reg, regions, h, ridge, dunes, land):
    prov = np.array([PROV_RUNOFF.get(r["province"], 0.8) for r in regions], float)
    rw = np.array([REGION_RUNOFF.get(r["id"], PROV_RUNOFF.get(r["province"], 0.8)) for r in regions], float)
    w = rw[reg]
    w = w * (1.0 + 0.7 * smoothstep(120, 300, h) + 0.4 * ridge)
    w = w * (1.0 - 0.75 * np.clip(dunes, 0, 1))
    return np.where(land, w, 0.0)


# ---------------------------------------------------------------------------------------------------------------------
# polylines
def densify(pts, lev=None, hw=None, step=16.0):
    pts = np.asarray(pts, float)
    out_p, out_l, out_h = [pts[0]], [], []
    out_l = [lev[0]] if lev is not None else None
    out_h = [hw[0]] if hw is not None else None
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        n = max(1, int(math.ceil(np.hypot(*(b - a)) / step)))
        for k in range(1, n + 1):
            t = k / n
            out_p.append(a + (b - a) * t)
            if lev is not None:
                out_l.append(lev[i] + (lev[i + 1] - lev[i]) * t)
            if hw is not None:
                out_h.append(hw[i] + (hw[i + 1] - hw[i]) * t)
    return (np.array(out_p), None if lev is None else np.array(out_l), None if hw is None else np.array(out_h))


def chaikin(pts, lev, hw, iters=2):
    pts = np.asarray(pts, float)
    lev = np.asarray(lev, float)
    hw = np.asarray(hw, float)
    for _ in range(iters):
        if len(pts) < 3:
            break
        p = [pts[0]]
        l = [lev[0]]
        w = [hw[0]]
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if i > 0:
                p.append(a * 0.75 + b * 0.25)
                l.append(lev[i] * 0.75 + lev[i + 1] * 0.25)
                w.append(hw[i] * 0.75 + hw[i + 1] * 0.25)
            if i < len(pts) - 2:
                p.append(a * 0.25 + b * 0.75)
                l.append(lev[i] * 0.25 + lev[i + 1] * 0.75)
                w.append(hw[i] * 0.25 + hw[i + 1] * 0.75)
        p.append(pts[-1])
        l.append(lev[-1])
        w.append(hw[-1])
        pts, lev, hw = np.array(p), np.array(l), np.array(w)
    return pts, lev, hw


def meander(pts, amp=5.0):
    """A small deterministic sideways wander (never at the two ends, which are snapped to confluences)."""
    if len(pts) < 4:
        return pts
    out = pts.copy()
    s = np.concatenate([[0], np.cumsum(np.hypot(*(pts[1:] - pts[:-1]).T))])
    for i in range(1, len(pts) - 1):
        t = pts[i + 1] - pts[i - 1]
        n = np.hypot(*t)
        if n < 1e-6:
            continue
        nx, nz = -t[1] / n, t[0] / n
        ph = hash2(int(pts[0][0]) // 7, int(pts[0][1]) // 7, 3) * 6.28
        off = amp * (math.sin(s[i] / 38.0 + ph) * 0.65 + math.sin(s[i] / 17.0 + ph * 2.3) * 0.35)
        w = min(1.0, s[i] / 40.0, (s[-1] - s[i]) / 40.0)
        out[i] = pts[i] + np.array([nx, nz]) * off * w
    return out


def splat(grid, lines, radius=150.0):
    """Nearest line by signed distance to its edge. lines: [(pts Nx2, lev N, hw N)]. Returns (d, hw, lev, line_id)."""
    H, W = grid.h, grid.w
    d = np.full((H, W), 1e5)
    sd = np.full((H, W), 1e5)
    hwo = np.zeros((H, W))
    lvo = np.zeros((H, W))
    lid = np.full((H, W), -1, np.int32)
    for li, (pts, lev, hw) in enumerate(lines):
        for i in range(len(pts) - 1):
            x0, z0 = pts[i]
            x1, z1 = pts[i + 1]
            r = radius + max(hw[i], hw[i + 1])
            i0 = max(0, int((min(x0, x1) - r - grid.min_x) / CELL))
            i1 = min(W, int((max(x0, x1) + r - grid.min_x) / CELL) + 2)
            j0 = max(0, int((min(z0, z1) - r - grid.min_z) / CELL))
            j1 = min(H, int((max(z0, z1) + r - grid.min_z) / CELL) + 2)
            if i0 >= i1 or j0 >= j1:
                continue
            sx = grid.xs[i0:i1][None, :]
            sz = grid.zs[j0:j1][:, None]
            dx, dz = x1 - x0, z1 - z0
            l2 = dx * dx + dz * dz
            u = np.clip(((sx - x0) * dx + (sz - z0) * dz) / max(l2, 1e-6), 0, 1)
            dist = np.hypot(sx - (x0 + u * dx), sz - (z0 + u * dz))
            hwi = hw[i] + (hw[i + 1] - hw[i]) * u
            s = dist - hwi
            sub = sd[j0:j1, i0:i1]
            m = s < sub
            if not m.any():
                continue
            sub[m] = s[m]
            d[j0:j1, i0:i1][m] = dist[m]
            hwo[j0:j1, i0:i1][m] = hwi[m]
            lvo[j0:j1, i0:i1][m] = (lev[i] + (lev[i + 1] - lev[i]) * u)[m]
            lid[j0:j1, i0:i1][m] = li
    return d, hwo, lvo, lid


# ---------------------------------------------------------------------------------------------------------------------
def label(mask):
    if _ndi is not None:
        return _ndi.label(mask, structure=np.ones((3, 3), int))
    lab = np.zeros(mask.shape, np.int32)
    n = 0
    H, W = mask.shape
    for z, x in zip(*np.nonzero(mask)):
        if lab[z, x]:
            continue
        n += 1
        stack = [(z, x)]
        lab[z, x] = n
        while stack:
            cz, cx = stack.pop()
            for dz, dx in D8:
                zz, xx = cz + dz, cx + dx
                if 0 <= zz < H and 0 <= xx < W and mask[zz, xx] and not lab[zz, xx]:
                    lab[zz, xx] = n
                    stack.append((zz, xx))
    return lab, n


def find_pits(grid, h, land, D, wet, near_water, excl, max_lakes=110):
    """Closed depressions of the macro height field that deserve to be lakes: deep enough, big enough, inland,
    dry-land (not marsh), away from authored water and from places. Returns (labels H x W int32, specs list).
    A spec: dict(id, level, depth, cells, cx, cz). The level is the spill height minus 0.4."""
    sink = ~land
    recv, order, filled = flood(h, sink)
    depth = filled - h
    if _ndi is not None:
        excl = _ndi.binary_dilation(excl, iterations=4)  # lakes keep a few cells from every place
    cand = (depth > 1.2) & land & (D > 150) & (wet < 0.25) & ~excl & (h > SEA + 4)
    lab, n = label(cand)
    specs = []
    for k in range(1, n + 1):
        m = lab == k
        cells = int(m.sum())
        if cells < 5:
            continue
        dmax = float(depth[m].max())
        if dmax < 3.0:
            continue
        if near_water[m].any():
            continue
        spill = float(filled[m].max())
        level = spill - 0.4
        zz, xx = np.nonzero(m)
        vol = float(depth[m].sum())
        specs.append(dict(k=k, level=level, depth=min(dmax + 1.0, 12.0), cells=cells, vol=vol,
                          cx=float(grid.xs[xx].mean()), cz=float(grid.zs[zz].mean())))
    specs.sort(key=lambda s: -s["vol"])
    specs = [s for s in specs if s["cells"] <= 200][:max_lakes]
    out = np.zeros(h.shape, np.int32)
    for i, s in enumerate(specs):
        out[(lab == s["k"]) & (h < s["level"] + 0.5)] = i + 1
        s["id"] = i
    return out, specs


# ---------------------------------------------------------------------------------------------------------------------
class Network:
    """Everything derive_streams finds."""

    def __init__(self):
        self.lines = []      # dict(pts, lev, hw, kind, name, head, mouth)
        self.springs = []    # [x, z, y, mountain(0/1)]
        self.falls = []      # dict(x, z, ux, uz, up, down, hw, kind)
        self.stats = {}


def derive_streams(grid, h, land, D, wet, runoff, riv_d, riv_hw, riv_level, lake_sd, lake_level, excl, river_pts,
                   threshold=STREAM_T, no_stream=None, excl_hard=None):
    """Tributaries from flow accumulation. Never moves an authored river or lake: a stream ends where it reaches an
    authored channel (snapped to the nearest centre-line point `river_pts`: N x 3 x, z, level), a lake, the sea,
    or a place's exclusion zone. `lake_level` is per cell (NaN away from lakes)."""
    H, W = h.shape
    net = Network()
    sink = ~land
    route = h + np.where(excl, 40.0, 0.0) + route_noise(h.shape)
    recv, order, filled = flood(route, sink)
    acc = accumulate(recv, order, np.where(land, runoff, 0.0))
    river_cell = riv_d < (riv_hw + 20.0)
    near_river = (riv_d < (riv_hw + 56.0)) & (riv_hw >= 4.0)  # a tributary ends here instead of running beside the river
    river_cell = river_cell | near_river
    lake_cell = lake_sd > -20.0
    marsh = (wet > 0.35) & (h < SEA + 6.0)
    hard = excl if excl_hard is None else excl_hard
    blocked = river_cell | lake_cell | (excl & (acc < 3000.0)) | hard | (marsh & (acc < MARSH_TRUNK)) | ~land
    if no_stream is not None:
        blocked = blocked | no_stream
    is_s = (acc >= threshold) & ~blocked
    # lake outlets: a lake that no authored river leaves gets one (the chain from its exit is forced to be a stream)
    rf = recv.reshape(H, W)
    flat_lake = lake_cell.ravel()
    forced = 0
    exits = np.flatnonzero(flat_lake & (recv >= 0))
    outlets = []
    for c in exits.tolist():
        t = recv[c]
        if flat_lake[t]:
            continue
        outlets.append((c, t))
    # keep the strongest exit per lake component
    lab, nl = label(lake_cell)
    best = {}
    for c, t in outlets:
        k = int(lab.ravel()[c])
        a = acc.ravel()[c]
        if k not in best or a > best[k][0]:
            best[k] = (a, c, t)
    lake_outlets = []
    fs = is_s.ravel()
    rc, lc, nl_, ex_, mr_ = river_cell.ravel(), lake_cell.ravel(), land.ravel(), excl.ravel(), marsh.ravel()
    for k, (a, c, t) in best.items():
        if rc[t]:
            continue  # an authored river leaves this lake
        cur, n = t, 0
        while cur >= 0 and n < 400:
            if rc[cur] or lc[cur] or not nl_[cur] or ex_[cur] or mr_[cur]:
                break
            if not fs[cur]:
                fs[cur] = True
                forced += 1
            cur = recv[cur]
            n += 1
        lake_outlets.append((k, c, t))
    s_flat = is_s.ravel()
    n_up = np.zeros(H * W, np.int32)
    cells = np.flatnonzero(s_flat)
    for c in cells.tolist():
        t = recv[c]
        if t >= 0 and s_flat[t]:
            n_up[t] += 1
    starts = [c for c in cells.tolist() if n_up[c] != 1]
    hf = filled.ravel()
    accf = acc.ravel()
    wetf = wet.ravel()
    xs, zs = grid.xs, grid.zs

    def center(c):
        return np.array([xs[c % W], zs[c // W]])

    # river snap points
    rp = np.asarray(river_pts, float).reshape(-1, 3) if len(river_pts) else np.zeros((0, 3))
    segs = []
    lake_flat_level = lake_level.ravel()
    sea_neighbor = {}
    Df = D.ravel()
    for s0 in starts:
        path = [s0]
        c = s0
        end_kind = "sea"
        end_cell = None
        while True:
            t = recv[c]
            if t < 0:
                end_kind = "sea"
                break
            if s_flat[t]:
                if n_up[t] >= 2:
                    path.append(t)
                    end_kind = "join"
                    break
                path.append(t)
                c = t
                continue
            if river_cell.ravel()[t]:
                end_kind, end_cell = "river", t
            elif lake_cell.ravel()[t]:
                end_kind, end_cell = "lake", t
            elif not land.ravel()[t]:
                end_kind, end_cell = "sea", t
            else:
                end_kind, end_cell = "sink", t
            break
        if len(path) < MIN_STREAM_CELLS and end_kind != "join" and n_up[s0] == 0 and accf[s0] < threshold * 2.5:
            continue
        segs.append((path, end_kind, end_cell))
    # level per cell: the filled surface minus the incision, never rising downstream (cells are visited from the
    # outlet upwards by the flood order)
    inc = 2.0
    level_cell = np.full(H * W, np.nan)
    for c in order.tolist():
        if s_flat[c]:
            t = recv[c]
            base = hf[c] - inc
            if t >= 0 and s_flat[t]:
                base = max(base, level_cell[t])
            level_cell[c] = base
    for path, end_kind, end_cell in segs:
        pts = [center(c) for c in path]
        lev = [max(level_cell[c], SEA + 0.6) for c in path]
        last = path[-1]
        if end_kind == "river" and end_cell is not None:
            cp = center(last)
            if len(rp):
                dd = np.hypot(rp[:, 0] - cp[0], rp[:, 1] - cp[1])
                j = int(dd.argmin())
                pts.append(rp[j, :2].copy())
                lev.append(max(float(rp[j, 2]), SEA + 0.4))
            else:
                pts.append(center(end_cell))
                lev.append(max(lev[-1], float(riv_level.ravel()[end_cell])))
        elif end_kind == "lake" and end_cell is not None:
            cur = end_cell
            for _ in range(4):
                if lake_sd.ravel()[cur] > 0 or recv[cur] < 0:
                    break
                cur = recv[cur]
            pts.append(center(cur))
            ll = lake_flat_level[cur] if not np.isnan(lake_flat_level[cur]) else lev[-1]
            lev.append(max(float(ll), SEA + 0.5))
        elif end_kind == "sea":
            r0, c0 = divmod(last, W)
            bestd, bc = None, None
            for dz, dx in D8:
                rr, cc = r0 + dz, c0 + dx
                if 0 <= rr < H and 0 <= cc < W and D[rr, cc] <= 0:
                    if bestd is None or D[rr, cc] < bestd:
                        bestd, bc = D[rr, cc], rr * W + cc
            if bc is not None:
                pts.append(center(bc))
            else:
                pts.append(center(last) + np.array([0.0, 0.0]))
            lev.append(SEA + 0.5)
        # the water never stands above the ground (a route that detours round a town must not build a dam), and
        # never rises downstream: walk from the head and keep the running minimum
        terr = np.array([float(grid.bil(h, p[0], p[1])) for p in pts])
        lev = np.array(lev)
        lev = np.where(terr < SEA + 2.5, lev, np.minimum(lev, terr - 0.8))
        lev = np.maximum(lev, SEA + 0.55)
        lev = np.minimum.accumulate(lev)
        accs = np.array([accf[c] for c in path] + [accf[path[-1]]] * (len(pts) - len(path)))
        hw = np.clip(0.7 + 0.8 * np.log2(np.maximum(accs, threshold) / threshold), 1.0, 3.4)
        big = np.clip(np.log(np.maximum(accs, 1.0) / 1200.0) / math.log(17.0), 0.0, 1.0)
        hw = hw + (8.0 - 3.4) * big  # real trunk flows: widths up to 16 blocks
        pts_a = np.array(pts)
        pts_a, lev_a, hw_a = chaikin(pts_a, lev, hw, 2)
        pts_a = meander(pts_a, 4.0 + 1.0 * float(hw_a.mean()))
        head = path[0]
        net.lines.append(dict(pts=pts_a, lev=lev_a, hw=hw_a, kind="stream", end=end_kind,
                              head=(float(center(head)[0]), float(center(head)[1])), head_h=float(hf[head]),
                              n_cells=len(path), up=int(n_up[head])))
    big = acc >= 800
    reasons = dict(river=int((big & river_cell).sum()), lake=int((big & lake_cell).sum()),
                   excl=int((big & excl & (acc < 3000)).sum()), hard=int((big & hard).sum()),
                   marsh=int((big & marsh & (acc < MARSH_TRUNK)).sum()), sea=int((big & ~land).sum()),
                   stream=int((big & is_s).sum()), big=int(big.sum()))
    net.stats = dict(blocked_big=reasons, threshold=threshold, stream_cells=int(s_flat.sum()), segments=len(segs), forced_outlet_cells=forced,
                     lake_outlets=len(lake_outlets), acc_max=float(acc.max()))
    net.acc = acc
    net.recv = recv
    net.filled = filled
    net.lake_outlets = lake_outlets
    return net


# ---------------------------------------------------------------------------------------------------------------------
def add_falls(pts, lev, hw, kind, min_drop, existing, name=""):
    """Finds drops of at least `min_drop` blocks within FALL_WINDOW along a dense polyline (8-16 block spacing),
    squares the profile there into one step, and returns (lev, falls). `existing` = [(x, z)] of falls already placed
    (spacing is kept to them). lev must be non-increasing downstream."""
    pts = np.asarray(pts, float)
    lev = np.asarray(lev, float).copy()
    n = len(pts)
    if n < 4:
        return lev, []
    s = np.concatenate([[0], np.cumsum(np.hypot(*(pts[1:] - pts[:-1]).T))])
    falls = []
    taken = list(existing)
    # candidate windows: start index i, end index j with s[j] - s[i] <= FALL_WINDOW
    cands = []
    j = 0
    for i in range(n - 1):
        j = max(j, i + 1)
        while j + 1 < n and s[j + 1] - s[i] <= FALL_WINDOW:
            j += 1
        drop = lev[i] - lev[j]
        if drop >= min_drop:
            cands.append((drop, i, j))
    cands.sort(reverse=True)
    used = np.zeros(n, bool)
    for drop, i, j in cands:
        if used[i:j + 1].any():
            continue
        mid = (i + j) // 2
        lip = pts[mid]
        if any(math.hypot(lip[0] - x, lip[1] - z) < FALL_SPACING for x, z in taken):
            continue
        # exact window: re-measure so the step covers the whole drop
        up, down = lev[i], lev[j]
        d = pts[j] - pts[i]
        dn = math.hypot(*d)
        if dn < 1e-6 or up - down < min_drop:
            continue
        for k in range(i, j + 1):
            lev[k] = up if k <= mid else down
        used[max(0, i - 3):min(n, j + 4)] = True
        # the step must not leave the water lower upstream than it is already: enforce monotone
        lev = np.minimum.accumulate(lev)
        falls.append(dict(x=float(lip[0]), z=float(lip[1]), ux=float(d[0] / dn), uz=float(d[1] / dn), up=float(up),
                          down=float(down), hw=float(hw[mid]), kind=kind, name=name))
        taken.append((lip[0], lip[1]))
    return lev, falls


# ---------------------------------------------------------------------------------------------------------------------
def derive_deltas(grid, river_lines, D, wet, excl, land, delta_names=None):
    """Distributary channels where an authored river reaches a flat, wet coast (the Niben / Black Marsh deltas).
    river_lines: [dict(name, pts Nx2 dense, lev N, hw N)]. Returns a list of dict(pts, lev, hw, kind='delta')."""
    out = []
    for rl in river_lines:
        pts = rl["pts"]
        if len(pts) < 8:
            continue
        end = pts[-1]
        d_end = float(grid.bil(D, end[0], end[1]))
        wet_here = float(grid.bil(wet, end[0], end[1]))
        wet_mean = float(np.mean([grid.bil(wet, end[0] + dx, end[1] + dz) for dx in (-400, 0, 400) for dz in (-400, 0, 400)]))
        named = delta_names is not None and rl["name"] in delta_names
        if d_end > 260 or not (named or wet_mean > 0.3 or wet_here > 0.4):
            continue
        s = np.concatenate([[0], np.cumsum(np.hypot(*(pts[1:] - pts[:-1]).T))])
        total = s[-1]
        split_at = max(0.0, total - (650.0 + 18.0 * float(rl["hw"][-1])))
        si = int(np.searchsorted(s, split_at))
        S = pts[si]
        base = end - S
        bn = np.hypot(*base)
        if bn < 120:
            continue
        base_ang = math.atan2(base[1], base[0])
        lev_s = float(rl["lev"][si])
        hw_s = float(rl["hw"][si])
        nbr = 4 + (1 if hw_s >= 9 else 0) + (1 if wet_mean > 0.5 else 0)
        ends_seen = []
        for k in range(nbr):
            spread =math.radians(52.0)
            frac = k / (nbr - 1) - 0.5
            frac = frac + (0.17 if frac >= 0 else -0.17) * (1 - 2 * abs(frac))  # no branch hugs the river itself
            ang = base_ang + frac * 2 * spread
            p = S.copy()
            path = [S.copy()]
            a = ang
            ok = False
            for step in range(140):
                # drift towards the sea: down the coast distance gradient
                gx = float(grid.bil(D, p[0] + 24, p[1]) - grid.bil(D, p[0] - 24, p[1]))
                gz = float(grid.bil(D, p[0], p[1] + 24) - grid.bil(D, p[0], p[1] - 24))
                gn = math.hypot(gx, gz)
                tgt = math.atan2(-gz, -gx) if gn > 1e-6 else a
                da = math.atan2(math.sin(tgt - a), math.cos(tgt - a))
                a += 0.30 * da + 0.25 * (hash2(int(S[0]) // 5 + k, step + int(S[1]) // 5, 17) - 0.5)
                moved = False
                for turn in (0.0, 0.5, -0.5, 1.0, -1.0, 1.5, -1.5):
                    q = p + np.array([math.cos(a + turn), math.sin(a + turn)]) * 24.0
                    ix, iz = grid.cell_of(q[0], q[1])
                    if step <= 12 or not excl[iz, ix]:
                        a += turn * 0.5
                        p = q
                        moved = True
                        break
                if not moved:
                    break
                if float(grid.bil(D, p[0], p[1])) <= 0:
                    path.append(p.copy())
                    ok = True
                    break
                path.append(p.copy())
            if not ok or len(path) < 6:
                continue
            arr = np.array(path)
            chord = float(np.hypot(*(arr[-1] - arr[0])))
            plen = float(np.hypot(*np.diff(arr, axis=0).T).sum())
            if chord < 200 or plen > 1.7 * chord:
                continue  # a loop or a stub: not a distributary
            if any(math.hypot(arr[-1][0] - e[0], arr[-1][1] - e[1]) < 260 for e in ends_seen):
                continue
            ends_seen.append(arr[-1].copy())
            m = len(arr)
            t = np.linspace(0, 1, m)
            lev = SEA + 0.6 + (max(lev_s, SEA + 0.6) - SEA - 0.6) * (1 - t) ** 1.5
            hw_b = np.clip(hw_s * 0.55, 2.0, 6.0) * (1.0 - 0.35 * t)
            arr, lev, hwv = chaikin(arr, lev, hw_b, 1)
            out.append(dict(pts=arr, lev=lev, hw=hwv, kind="delta", name=rl["name"] + "_d%d" % k))
    return out


# ---------------------------------------------------------------------------------------------------------------------
def write_json(path, obj):
    from format_263 import prepare
    path, obj = prepare(path, obj)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, separators=(",", ":"))
        f.write("\n")

def extend_dead_ends(grid, auth_lines, recv, filled, D, land, lake_sd, max_steps=420):
    """Authored rivers that stop inland (not at the sea, a lake or another river) get a continuation: the flow
    route from the last point down to the sea (or a lake / river), as a sea-level channel in the delta group."""
    H, W = D.shape
    out = []
    for i, ln in enumerate(auth_lines):
        pts = np.asarray(ln["pts"])
        end = pts[-1]
        if float(grid.bil(D, end[0], end[1])) <= 48 or float(grid.bil(lake_sd, end[0], end[1])) > -60:
            continue
        joined = False
        for j, o in enumerate(auth_lines):
            if j == i:
                continue
            dd = np.hypot(o["pts"][:, 0] - end[0], o["pts"][:, 1] - end[1])
            k = int(dd.argmin())
            if dd[k] < o["hw"][k] + 60:
                joined = True
                break
        if joined:
            continue
        ix, iz = grid.cell_of(end[0], end[1])
        c = iz * W + ix
        path = [end.copy()]
        cur = recv[c]
        n = 0
        reached = False
        while cur >= 0 and n < max_steps:
            r, q = divmod(cur, W)
            path.append(np.array([grid.xs[q], grid.zs[r]]))
            if D[r, q] <= 0:
                reached = True
                break
            if lake_sd[r, q] > 0:
                reached = True
                break
            cur = recv[cur]
            n += 1
        if len(path) < 4:
            continue
        arr = np.array(path)
        flv = np.array([float(grid.bil(filled, p[0], p[1])) - 2.0 for p in arr])
        lev = np.minimum.accumulate(np.minimum(flv, ln["lev"][-1]))
        lev = np.maximum(lev, SEA + 0.55)
        hw0 = float(ln["hw"][-1])
        hwv = np.full(len(arr), max(hw0, 2.5))
        arr, lev, hwv = chaikin(arr, lev, hwv, 2)
        arr = meander(arr, 4.0)
        out.append(dict(pts=arr, lev=lev, hw=hwv, kind="extension", name=ln["name"] + "_ext", reached=reached))
    return out
