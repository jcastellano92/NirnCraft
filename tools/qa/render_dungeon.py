"""Offline renderer for generated structures (dungeons, towns): reads Anvil region files directly.

usage:
  py -3 -I tools/qa/render_dungeon.py <world_dir> <place_id | x,z | x,y,z> <out_dir> [--name N]
  py -3 -I tools/qa/render_dungeon.py <world_dir> box <out_dir> --box x1 z1 x2 z2 y1 y2

Outputs (PNG, <=1600 px wide): 00_exterior_iso, 01_exterior_top, 10..1n_level_*, 20..2n_interior_*, plus a
legend strip in each cutaway.  No Minecraft client needed; stdlib + numpy + PIL.
"""
import argparse
import glob
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mca import World  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
TEX = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'assets', 'skycraft', 'textures', 'block')

# ------------------------------------------------------------------ palette
VANILLA = {
    'stone': (125, 125, 125), 'deepslate': (80, 80, 84), 'tuff': (108, 109, 102), 'dirt': (134, 96, 67),
    'grass_block': (95, 150, 60), 'gravel': (131, 127, 126), 'granite': (150, 103, 85), 'andesite': (136, 136, 136),
    'diorite': (188, 188, 188), 'bedrock': (40, 40, 40), 'packed_ice': (141, 180, 250), 'blue_ice': (116, 167, 253),
    'ice': (145, 183, 253), 'snow_block': (245, 250, 250), 'snow': (245, 250, 250), 'powder_snow': (245, 250, 250),
    'moss_block': (89, 109, 45), 'podzol': (91, 63, 24), 'sand': (219, 207, 163), 'calcite': (223, 224, 220),
    'water': (50, 90, 220), 'lava': (255, 120, 20), 'sculk': (12, 55, 65), 'sculk_vein': (20, 80, 90),
    'smooth_basalt': (72, 72, 78), 'dripstone_block': (134, 107, 92), 'amethyst_block': (133, 97, 191),
    'stone_bricks': (122, 121, 122), 'cracked_stone_bricks': (118, 117, 118), 'mossy_stone_bricks': (115, 121, 105),
    'chiseled_stone_bricks': (119, 118, 119), 'cobblestone': (127, 127, 127), 'mossy_cobblestone': (110, 118, 95),
    'deepslate_tiles': (54, 54, 55), 'deepslate_bricks': (70, 70, 71), 'cracked_deepslate_bricks': (64, 64, 65),
    'cracked_deepslate_tiles': (52, 52, 53), 'polished_deepslate': (72, 72, 73), 'chiseled_deepslate': (54, 54, 55),
    'cobweb': (235, 235, 235), 'iron_bars': (150, 152, 156), 'spruce_planks': (114, 84, 48),
    'oak_planks': (162, 130, 78), 'dark_oak_planks': (66, 43, 20), 'spruce_log': (58, 37, 16),
    'oak_log': (109, 85, 50), 'bookshelf': (140, 105, 60), 'glass': (200, 230, 240), 'obsidian': (20, 18, 30),
    'netherrack': (110, 55, 55), 'terracotta': (152, 94, 68), 'clay': (160, 166, 179),
}
KEYWORDS = [  # (substring, colour) first match wins; used when no table/texture entry
    ('spawner', (170, 40, 220)), ('chest', (230, 140, 20)), ('barrel', (170, 110, 50)), ('torch', (255, 220, 60)),
    ('lantern', (255, 200, 80)), ('campfire', (255, 150, 40)), ('glowstone', (255, 220, 120)),
    ('sea_lantern', (200, 240, 230)), ('shroomlight', (255, 170, 80)), ('candle', (250, 240, 200)),
    ('leaves', (50, 110, 40)), ('grass', (90, 150, 60)), ('fern', (70, 130, 60)), ('flower', (200, 100, 140)),
    ('log', (80, 60, 35)), ('planks', (140, 105, 60)), ('door', (130, 80, 30)), ('wool', (210, 210, 210)),
    ('carpet', (160, 40, 40)), ('brick', (120, 120, 120)), ('deepslate', (80, 80, 84)), ('stone', (125, 125, 125)),
    ('ore', (150, 140, 120)), ('ice', (150, 190, 250)), ('snow', (245, 250, 250)), ('sand', (219, 207, 163)),
    ('dirt', (134, 96, 67)), ('iron', (190, 190, 195)), ('gold', (240, 200, 60)), ('copper', (190, 110, 70)),
    ('skull', (230, 230, 220)), ('head', (230, 230, 220)), ('pot', (160, 90, 60)), ('mushroom', (180, 100, 100)),
    ('moss', (89, 109, 45)), ('vine', (60, 110, 40)), ('slab', (120, 120, 120)), ('stairs', (120, 120, 120)),
    ('water', (50, 90, 220)), ('lava', (255, 120, 20)),
]
_texcache = {}


def tex_avg(name):
    ns, _, n = name.partition(':')
    if ns != 'skycraft':
        return None
    base = n.replace('_slab', '').replace('_stairs', '').replace('_wall', '').replace('_fence', '')
    for cand in (n, base, n + '_top', base + '_top', n + '_side', base + '_side'):
        if cand in _texcache:
            c = _texcache[cand]
            if c:
                return c
            continue
        p = os.path.join(TEX, cand + '.png')
        c = None
        if os.path.exists(p):
            im = np.asarray(Image.open(p).convert('RGBA')).astype(float)
            m = im[..., 3] > 40
            if m.any():
                c = tuple(int(v) for v in im[..., :3][m].mean(0))
        _texcache[cand] = c
        if c:
            return c
    return None


def base_color(name):
    n = name.split(':', 1)[-1]
    if n in VANILLA:
        return VANILLA[n]
    t = tex_avg(name)
    if t:
        return t
    for suf in ('_slab', '_stairs', '_wall', '_button', '_pressure_plate', '_fence', '_trapdoor'):
        if n.endswith(suf) and n[:-len(suf)] in VANILLA:
            return VANILLA[n[:-len(suf)]]
    for k, c in KEYWORDS:
        if k in n:
            return c
    h = abs(hash(n)) % 997
    return (90 + h % 100, 90 + (h // 7) % 100, 90 + (h // 13) % 100)


# ------------------------------------------------------------------ classification
NATURAL_EXACT = {
    'stone', 'deepslate', 'tuff', 'dirt', 'coarse_dirt', 'rooted_dirt', 'grass_block', 'gravel', 'granite', 'andesite',
    'diorite', 'bedrock', 'packed_ice', 'blue_ice', 'ice', 'snow_block', 'snow', 'powder_snow', 'moss_block', 'podzol',
    'sand', 'sandstone', 'red_sand', 'calcite', 'smooth_basalt', 'dripstone_block', 'pointed_dripstone',
    'amethyst_block', 'budding_amethyst', 'amethyst_cluster', 'large_amethyst_bud', 'medium_amethyst_bud',
    'small_amethyst_bud', 'clay', 'mud', 'mycelium', 'basalt', 'obsidian', 'netherrack', 'cobbled_deepslate',
    'infested_stone', 'infested_deepslate', 'water', 'lava', 'mossy_cobblestone_natural', 'cave_vines',
    'cave_vines_plant', 'glow_lichen', 'sculk', 'sculk_vein', 'azalea', 'flowering_azalea', 'spore_blossom',
    'big_dripleaf', 'small_dripleaf', 'hanging_roots', 'moss_carpet',
}
NATURAL_SUB = ('_ore', 'leaves', 'grass', 'fern', 'flower', 'tulip', 'orchid', 'azure_bluet', 'dandelion', 'poppy',
               'mushroom', 'vine', 'sapling', 'bush', 'berry', 'frosted', 'ice', 'snow', 'lichen', 'kelp', 'seagrass',
               'dead_', 'lily', 'rose', 'peony', 'lilac', 'cornflower', 'allium', 'oxeye', 'tundra', 'moss')
NATURAL_PREFIX_SKY = ('skycraft:frosted', 'skycraft:glowing_mushroom', 'skycraft:nirnroot', 'skycraft:tundra')
NONSOLID_SUB = ('air', 'torch', 'lantern', 'candle', 'carpet', 'skull', 'head', 'sign', 'button', 'pressure_plate',
                'grass', 'fern', 'flower', 'tulip', 'sapling', 'vine', 'cobweb', 'rail', 'lever', 'tripwire',
                'redstone_wire', 'banner', 'bluet', 'dandelion', 'poppy', 'orchid', 'allium', 'rose', 'peony',
                'lilac', 'cornflower', 'berry', 'lichen', 'roots', 'azalea_bush_', 'snow', 'fire', 'door',
                'bush', 'mushroom', 'kelp', 'seagrass', 'lily', 'chain', 'iron_bars', 'ladder', 'sensor', 'pot')
FULL_SOLID_OVERRIDE = ('snow_block', 'mushroom_block', 'glowing_mushroom_cap', 'door_frame')


def classify(names):
    """-> dict of per-id arrays: air(bool) solid(bool) natural(bool) cat(str)"""
    n = len(names)
    air = np.zeros(n, bool)
    solid = np.zeros(n, bool)
    natural = np.zeros(n, bool)
    fluid = np.zeros(n, bool)
    cat = ['block'] * n
    for i, full in enumerate(names):
        s = full.split(':', 1)[-1]
        is_air = s in ('air', 'cave_air', 'void_air')
        air[i] = is_air
        fl = s in ('water', 'lava')
        fluid[i] = fl
        ns = any(k in s for k in NONSOLID_SUB) and not any(k in s for k in FULL_SOLID_OVERRIDE) and s != 'snow_block'
        solid[i] = (not ns) and (not fl) and (not is_air)
        nat = s in NATURAL_EXACT or any(k in s for k in NATURAL_SUB) or full.startswith(NATURAL_PREFIX_SKY)
        if 'brick' in s or 'plank' in s or 'door' in s or 'chiseled' in s or 'polished' in s:
            nat = False
        if ((s.endswith('_log') or s.endswith('_wood') or 'parasol' in s or 'leaves' in s) and 'stripped' not in s):
            nat = True
        if s in ('moss_carpet', 'snow'):
            nat = True
        natural[i] = nat
        if 'spawner' in s:
            cat[i] = 'spawner'
        elif s in ('chest', 'trapped_chest', 'barrel', 'ender_chest') or s.endswith('chest'):
            cat[i] = 'chest'
        elif 'door' in s and 'trap' not in s:
            cat[i] = 'door'
        elif any(k in s for k in ('torch', 'lantern', 'campfire', 'glowstone', 'shroomlight', 'candle',
                                  'sea_lantern', 'fire', 'jack_o', 'froglight', 'magma')):
            cat[i] = 'light'
        elif 'iron_bars' in s or 'portcullis' in s or 'gate' in s:
            cat[i] = 'bars'
        elif 'skull' in s or 'head' in s:
            cat[i] = 'skull'
        elif 'cobweb' in s:
            cat[i] = 'web'
        elif 'pot' in s or 'jar' in s or 'urn' in s:
            cat[i] = 'pot'
        elif s in ('lever',) or 'button' in s or 'pressure_plate' in s or 'tripwire' in s or s in (
                'dispenser', 'dropper', 'tnt', 'redstone_wire', 'observer', 'piston', 'sticky_piston',
                'sculk_sensor', 'sculk_shrieker', 'sculk_catalyst'):
            cat[i] = 'trap'
        elif 'lectern' in s or 'word_wall' in s or 'bookshelf' in s or 'enchanting' in s or 'altar' in s:
            cat[i] = 'special'
        elif s == 'water':
            cat[i] = 'water'
        elif s == 'lava':
            cat[i] = 'lava'
    return dict(air=air, solid=solid, natural=natural, fluid=fluid, cat=np.array(cat))


MARK_COL = {
    'spawner': (190, 40, 255), 'chest': (255, 150, 10), 'door': (150, 85, 25), 'light': (255, 235, 70),
    'bars': (170, 200, 230), 'skull': (255, 255, 255), 'web': (240, 240, 240), 'pot': (200, 110, 70),
    'trap': (255, 50, 50), 'special': (60, 220, 160),
}
MARK_PRI = ['spawner', 'chest', 'special', 'trap', 'door', 'skull', 'pot', 'bars', 'web', 'light']
MARK_LABEL = {
    'spawner': 'mob spawner', 'chest': 'chest / barrel', 'special': 'lectern / word wall / altar',
    'trap': 'trap / redstone / sculk sensor', 'door': 'door', 'skull': 'skull', 'pot': 'pot / urn',
    'bars': 'bars / gate', 'web': 'cobweb', 'light': 'torch / lantern / fire',
}


def font(sz):
    for p in (r'C:\Windows\Fonts\arial.ttf', r'C:\Windows\Fonts\segoeui.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'):
        try:
            return ImageFont.truetype(p, sz)
        except OSError:
            pass
    return ImageFont.load_default()


# ------------------------------------------------------------------ place lookup
def find_place(pid):
    for f in glob.glob(os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel', 'places', '*.json')):
        try:
            d = json.load(open(f, encoding='utf8'))
        except Exception:
            continue
        lst = d if isinstance(d, list) else d.get('places', [])
        for p in lst:
            if isinstance(p, dict) and p.get('id') == pid:
                return p
    return None


# ------------------------------------------------------------------ model
class Model:
    def __init__(self, world, x1, z1, x2, z2, y1, y2):
        self.w = world
        self.x1, self.z1, self.x2, self.z2, self.y1, self.y2 = x1, z1, x2, z2, y1, y2
        self.A = world.box(x1, z1, x2, z2, y1, y2)
        self.cl = classify(world.names)
        self.names = list(world.names)
        names = self.names
        self.col = np.array([base_color(n) for n in names], np.uint8)
        self.cat = self.cl['cat']
        self.built = (~self.cl['air']) & (~self.cl['natural']) & (~self.cl['fluid'])
        self.be = world.block_entities(x1, z1, x2, z2)

    def solid_mask(self):
        return self.cl['solid'][self.A]

    def built_mask(self):
        return self.built[self.A]


def detect_levels(m, min_cells=60):
    """walkable cells: solid built block below, two non-solid cells above. Returns list of (lo, hi, count)."""
    A = m.A
    solid = m.cl['solid'][A]
    built_floor = m.built[A] & solid
    free = ~solid & ~m.cl['fluid'][A]
    walk = np.zeros_like(solid)
    walk[1:-2] = built_floor[:-3] & free[1:-2] & free[2:-1]
    cnt = walk.sum(axis=(1, 2))
    return walk, cnt


def group_levels(cnt, y1, min_cells=70):
    ys = [i for i, c in enumerate(cnt) if c >= 12]
    groups = []
    for i in ys:
        if groups and i - groups[-1][1] <= 2:
            groups[-1][1] = i
        else:
            groups.append([i, i])
    out = []
    for lo, hi in groups:
        tot = int(cnt[lo:hi + 1].sum())
        if tot >= min_cells:
            out.append((lo + y1, hi + y1, tot))
    return out


# ------------------------------------------------------------------ drawing helpers
def finish(img, path, maxw=1600):
    if img.width > maxw:
        img = img.resize((maxw, int(img.height * maxw / img.width)), Image.LANCZOS)
    img.save(path)
    print('wrote', path, img.size)


def legend_strip(width, items, title=None, extra=None):
    f = font(13)
    h = 26 + (18 if title else 0)
    img = Image.new('RGB', (width, h), (24, 24, 28))
    d = ImageDraw.Draw(img)
    x = 8
    y = 5
    if title:
        d.text((x, y), title, fill=(255, 255, 255), font=font(14))
        y += 18
    for lab, col in items:
        d.rectangle([x, y + 2, x + 11, y + 13], fill=col, outline=(255, 255, 255))
        d.text((x + 16, y), lab, fill=(220, 220, 220), font=f)
        x += 16 + int(d.textlength(lab, font=f)) + 14
        if x > width - 120:
            break
    return img


CUT_LEGEND = [('floor (walkable)', (150, 140, 120)), ('wall / built solid', (95, 95, 100)), ('natural rock', (38, 38, 42)),
              ('open void / pit', (8, 8, 10)), ('water', (50, 90, 220)), ('lava', (255, 120, 20))] + \
             [(MARK_LABEL[k], MARK_COL[k]) for k in MARK_PRI]


def level_cutaway(m, lo, hi, walk, scale):
    A = m.A
    Y, Z, X = A.shape
    y1 = m.y1
    ylo, yhi = lo - y1, hi - y1
    band = walk[ylo:yhi + 1]  # [by,Z,X]
    has = band.any(0)
    # highest walkable y in band per column
    top = np.where(has, yhi - np.argmax(band[::-1], axis=0), 0)
    zz, xx = np.mgrid[0:Z, 0:X]
    floor_id = A[np.clip(top - 1, 0, Y - 1), zz, xx]
    rgb = np.zeros((Z, X, 3), np.float32)
    rgb[:] = (8, 8, 10)
    solid = m.cl['solid'][A]
    # headroom slab: blocks from lo+1 .. hi+3
    s0, s1 = max(ylo + 1, 0), min(yhi + 4, Y)
    slab = A[s0:s1]
    sol_slab = solid[s0:s1]
    built_slab = m.built[slab] & sol_slab
    nat_slab = (~m.built[slab]) & sol_slab & ~m.cl['air'][slab]
    wall_any = built_slab.any(0)
    nat_any = nat_slab.any(0)
    # wall colour: first built solid block at lowest y in slab
    first = np.argmax(built_slab, axis=0)
    wall_id = slab[first, zz, xx]
    below = A[max(ylo - 1, 0)]
    # open columns with no walkable: show what is below the band (ground) if solid, else void
    rgb[nat_any] = (38, 38, 42)
    rgb[wall_any] = m.col[wall_id][wall_any].astype(np.float32) * 0.5
    # floors
    shade = 0.78 + 0.22 * ((top - ylo) / max(1, yhi - ylo))
    fl = m.col[floor_id].astype(np.float32) * shade[..., None] * 1.25 + 15
    rgb[has] = fl[has]
    # walkable columns with a wall block inside head slab at standing height are rare; fluids
    wat = np.isin(slab, np.where(m.cl['fluid'] & (np.array([n.endswith('water') for n in m.names])))[0]).any(0) & ~has
    lav = np.isin(slab, np.where(np.array([n.endswith('lava') for n in m.names]))[0]).any(0) & ~has
    rgb[wat] = (50, 90, 220)
    rgb[lav] = (255, 120, 20)
    # per-cell texture variance so blocks read as blocks
    rnd = ((xx * 73856093) ^ (zz * 19349663)) % 11 / 11.0
    rgb *= (0.94 + 0.12 * rnd)[..., None]
    img = np.clip(rgb, 0, 255).astype(np.uint8)
    im = Image.fromarray(img).resize((X * scale, Z * scale), Image.NEAREST)
    d = ImageDraw.Draw(im)
    # markers within lo .. hi+3
    mk_slab = m.cat[A[ylo:min(yhi + 4, Y)]]
    pri_map = {k: i for i, k in enumerate(MARK_PRI)}
    best = np.full((Z, X), 99, np.int32)
    for k in MARK_PRI:
        hit = (mk_slab == k).any(0)
        best = np.where(hit & (best > pri_map[k]), pri_map[k], best)
    pad = max(1, scale // 5)
    ys_, xs_ = np.nonzero(best < 99)
    for z, x in zip(ys_, xs_):
        k = MARK_PRI[best[z, x]]
        c = MARK_COL[k]
        x0, y0 = x * scale, z * scale
        if k == 'door':
            d.rectangle([x0, y0 + scale // 3, x0 + scale - 1, y0 + 2 * scale // 3], fill=c)
        elif k == 'light':
            d.ellipse([x0 + pad + 1, y0 + pad + 1, x0 + scale - pad - 2, y0 + scale - pad - 2], fill=c)
        else:
            d.rectangle([x0 + pad, y0 + pad, x0 + scale - pad - 1, y0 + scale - pad - 1], fill=c,
                        outline=(0, 0, 0))
    # axis ticks every 16 blocks
    f = font(11)
    for gx in range((m.x1 + 15) // 16 * 16, m.x2 + 1, 16):
        px = (gx - m.x1) * scale
        d.line([px, 0, px, 8], fill=(255, 255, 255))
        d.text((px + 2, 8), str(gx), fill=(255, 255, 255), font=f)
    for gz in range((m.z1 + 15) // 16 * 16, m.z2 + 1, 16):
        pz = (gz - m.z1) * scale
        d.line([0, pz, 8, pz], fill=(255, 255, 255))
        d.text((10, pz - 5), str(gz), fill=(255, 255, 255), font=f)
    return im, int(has.sum())


def surface_topdown(m, scale, built_hilite=True):
    A = m.A
    Y, Z, X = A.shape
    vis = m.cl['solid'][A] | m.cl['fluid'][A]
    # ignore leaves-only canopy? keep: shows trees
    topidx = Y - 1 - np.argmax(vis[::-1], axis=0)
    anyv = vis.any(0)
    zz, xx = np.mgrid[0:Z, 0:X]
    bid = A[topidx, zz, xx]
    col = m.col[bid].astype(np.float32)
    h = np.where(anyv, topidx, 0).astype(np.float32)
    # hillshade from NW
    gx = np.gradient(h, axis=1)
    gz = np.gradient(h, axis=0)
    sh = np.clip(1.0 + 0.18 * (-gx - gz), 0.6, 1.4)
    col *= sh[..., None]
    isb = m.built[bid] & anyv
    if built_hilite:
        col[isb] = np.clip(col[isb] * 1.15 + 12, 0, 255)
    col[~anyv] = (10, 10, 12)
    im = Image.fromarray(np.clip(col, 0, 255).astype(np.uint8)).resize((X * scale, Z * scale), Image.NEAREST)
    d = ImageDraw.Draw(im)
    # chests / spawners from block entities
    for be in m.be:
        k = be['id'].split(':')[-1]
        if k in ('mob_spawner', 'chest'):
            x, z = (be['x'] - m.x1) * scale, (be['z'] - m.z1) * scale
            c = MARK_COL['spawner' if k == 'mob_spawner' else 'chest']
            r = max(3, scale)
            d.ellipse([x - r, z - r, x + r, z + r], outline=c, width=2)
    return im


# ------------------------------------------------------------------ isometric ray marcher
def iso_render(m, view=0, ymax=None, hide_natural=False, scale=None, maxw=1500, sub=None, ybottom=None, bg=(24, 28, 40)):
    A = m.A.copy()
    if sub:  # (x1,z1,x2,z2) world coords to crop
        x1, z1, x2, z2 = sub
        A = A[:, z1 - m.z1:z2 - m.z1 + 1, x1 - m.x1:x2 - m.x1 + 1]
    if ymax is not None:
        A = A[:min(A.shape[0], ymax - m.y1 + 1)]
    if ybottom is not None:
        A = A[max(0, ybottom - m.y1):]
    A = np.rot90(A, k=view, axes=(1, 2))
    Y, Z, X = A.shape
    draw_id = ~(m.cl['air'])  # candidates
    # transparent: plants & other thin blocks; draw as solid except pure vegetation
    thin = np.array([any(k in n for k in ('grass', 'fern', 'flower', 'sapling', 'tulip', 'bluet', 'lichen',
                                          'vine', 'dandelion', 'poppy', 'cobweb', 'rail', 'redstone_wire',
                                          'snow', 'carpet')) and 'snow_block' not in n and 'carpet' not in n
                     for n in m.names])
    vis = draw_id & ~thin
    if hide_natural:
        vis = vis & ~m.cl['natural'] | (vis & m.built)
        vis = vis & m.built
    vis_arr = vis[A]
    # screen basis
    d = np.array([1, 1, 1], float) / np.sqrt(3)         # direction pointing towards the camera
    r = np.array([1, 0, -1], float) / np.sqrt(2)
    u = np.array([-1, 2, -1], float) / np.sqrt(6)
    corners = np.array([[x, y, z] for x in (0, X) for y in (0, Y) for z in (0, Z)], float)
    cen0 = np.array([X / 2, Y / 2, Z / 2])
    ca = (corners - cen0) @ r
    cb = (corners - cen0) @ u
    amin, amax, bmin, bmax = ca.min(), ca.max(), cb.min(), cb.max()
    s = scale or min(7.0, maxw / (amax - amin + 2))
    W = int((amax - amin + 2) * s)
    H = int((bmax - bmin + 2) * s)
    aa = amin - 1 + (np.arange(W) + 0.5) / s
    bb = bmax + 1 - (np.arange(H) + 0.5) / s
    AA, BB = np.meshgrid(aa, bb)
    AA = AA.ravel()
    BB = BB.ravel()
    c = np.array([X / 2, Y / 2, Z / 2])
    L = np.sqrt(X * X + Y * Y + Z * Z)
    # image-plane point
    P0 = c[None, :] + AA[:, None] * r[None, :] + BB[:, None] * u[None, :] + (L * d)[None, :]
    # reduce to rays that can intersect the box (cheap AABB slab test)
    dirv = -d
    with np.errstate(divide='ignore', invalid='ignore'):
        t1 = (0 - P0) / dirv
        t2 = (np.array([X, Y, Z]) - P0) / dirv
    tmin = np.minimum(t1, t2).max(1)
    tmax = np.maximum(t1, t2).min(1)
    ok = tmax > np.maximum(tmin, 0)
    idx_pix = np.nonzero(ok)[0]
    P0 = P0[idx_pix]
    tmin = np.maximum(tmin[idx_pix], 0)
    tmax = tmax[idx_pix]
    out = np.zeros((W * H, 3), np.float32)
    out[:] = bg
    done = np.zeros(len(idx_pix), bool)
    color = np.zeros((len(idx_pix), 3), np.float32)
    step = 0.25
    t = tmin + 1e-3
    prev = np.floor(P0 + t[:, None] * dirv).astype(np.int32)
    active = np.arange(len(idx_pix))
    shades = {0: 1.0, 1: 0.8, 2: 0.62}
    colf = m.col.astype(np.float32)
    n_steps = int((tmax - tmin).max() / step) + 2
    for _ in range(n_steps):
        if active.size == 0:
            break
        t[active] += step
        P = P0[active] + t[active, None] * dirv
        inb_t = t[active] <= tmax[active]
        idx = np.floor(P).astype(np.int32)
        ix, iy, iz = idx[:, 0], idx[:, 1], idx[:, 2]
        inb = (ix >= 0) & (ix < X) & (iy >= 0) & (iy < Y) & (iz >= 0) & (iz < Z) & inb_t
        hitm = np.zeros(active.size, bool)
        if inb.any():
            sel = np.nonzero(inb)[0]
            v = vis_arr[iy[sel], iz[sel], ix[sel]]
            hitm[sel[v]] = True
        if hitm.any():
            hsel = np.nonzero(hitm)[0]
            gi = active[hsel]
            bid = A[iy[hsel], iz[hsel], ix[hsel]]
            pv = prev[gi]
            face_top = pv[:, 1] != iy[hsel]
            face_x = (pv[:, 0] != ix[hsel]) & ~face_top
            sh = np.where(face_top, 1.0, np.where(face_x, 0.78, 0.6))
            rnd = ((ix[hsel] * 73856093) ^ (iy[hsel] * 19349663) ^ (iz[hsel] * 83492791)) % 13 / 13.0
            color[gi] = colf[bid] * (sh * (0.92 + 0.14 * rnd))[:, None]
            done[gi] = True
        stop = hitm | ~inb_t
        prev[active] = idx
        active = active[~stop]
    out[idx_pix[done]] = color[done]
    arr = np.clip(out, 0, 255).astype(np.uint8).reshape(H, W, 3)
    nz = np.nonzero((arr != np.array(bg, np.uint8)).any(2))
    if len(nz[0]):
        y0, y1c = max(0, nz[0].min() - 12), min(H, nz[0].max() + 12)
        x0, x1c = max(0, nz[1].min() - 12), min(W, nz[1].max() + 12)
        arr = arr[y0:y1c, x0:x1c]
    return Image.fromarray(arr)


# ------------------------------------------------------------------ main
def compose(img, items, title):
    leg = legend_strip(img.width, items, title)
    out = Image.new('RGB', (img.width, img.height + leg.height), (24, 24, 28))
    out.paste(img, (0, 0))
    out.paste(leg, (0, img.height))
    return out


ISO_LEGEND = [('chests / barrels / spawners are plain cubes in iso views (see cutaways for markers)', (0, 0, 0))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('world')
    ap.add_argument('target')
    ap.add_argument('out')
    ap.add_argument('--box', nargs=6, type=int, metavar=('X1', 'Z1', 'X2', 'Z2', 'Y1', 'Y2'))
    ap.add_argument('--name', default=None)
    ap.add_argument('--pad', type=int, default=14)
    ap.add_argument('--search', type=int, default=0, help='half-size of area scanned for built blocks (default 2*radius)')
    ap.add_argument('--max-levels', type=int, default=6)
    ap.add_argument('--foci', default='', help='extra interior close-ups "name:x:y:z;..." (world coords)')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    w = World(a.world)
    cx = cz = None
    radius = 40
    name = a.name
    if a.box:
        x1, z1, x2, z2, y1, y2 = a.box
        name = name or 'box'
    else:
        t = a.target
        if ',' in t:
            parts = [int(float(v)) for v in t.split(',')]
            cx, cz = (parts[0], parts[-1]) if len(parts) >= 2 else (parts[0], 0)
        else:
            p = find_place(t)
            if not p:
                sys.exit('place not found: ' + t)
            cx, cz, radius = p['x'], p['z'], p.get('radius', 40)
            name = name or t
        R = a.search or max(40, radius * 2)
        # coarse scan to locate built blocks
        m0 = Model(w, cx - R, cz - R, cx + R, cz + R, -64, 319)
        bm = m0.built_mask() & m0.cl['solid'][m0.A]
        # drop blocks that are only 'built' by classification of grass etc: require brick/plank-like names handled in classify
        ys, zs, xs = np.nonzero(bm)
        if len(xs) < 50:
            sys.exit('no built blocks found near %s,%s (chunks generated?)' % (cx, cz))
        # robust bbox: trim 1% outliers
        lo = lambda v: int(np.percentile(v, 1))
        hi = lambda v: int(np.percentile(v, 99))
        x1, x2 = lo(xs) + m0.x1, hi(xs) + m0.x1
        z1, z2 = lo(zs) + m0.z1, hi(zs) + m0.z1
        y1, y2 = lo(ys) + m0.y1, hi(ys) + m0.y1
        print('built bbox x %d..%d z %d..%d y %d..%d (%d built solid blocks)' % (x1, x2, z1, z2, y1, y2, len(xs)))
        del m0
    name = name or 'structure'
    pad = a.pad
    m = Model(w, x1 - pad, z1 - pad, x2 + pad, z2 + pad, max(-64, y1 - 6), min(319, y2 + 30))
    print('model', m.A.shape, 'block types', len(m.names))
    # levels
    walk, cnt = detect_levels(m)
    levels = group_levels(cnt, m.y1)
    levels = sorted(levels, key=lambda l: -l[2])[:a.max_levels]
    levels.sort()
    print('levels (y lo..hi, walkable cells):', levels)
    # exterior
    scale = max(2, min(8, 1500 // (m.A.shape[2])))
    top = surface_topdown(m, scale)
    finish(compose(top, [('built solid (brightened)', (200, 200, 190)), ('chest', MARK_COL['chest']),
                         ('spawner', MARK_COL['spawner'])], name + ': exterior top-down (north up)'),
           os.path.join(a.out, '01_exterior_top.png'))
    ymax_ext = m.y2
    iso = iso_render(m, view=0, ymax=ymax_ext, ybottom=max(m.y1, (levels[0][0] - 4) if levels else m.y1))
    finish(compose(iso, [('isometric from SE; natural terrain + structure, cut at structure base', (120, 120, 120))],
                   name + ': exterior isometric'), os.path.join(a.out, '00_exterior_iso.png'))
    iso2 = iso_render(m, view=2, ymax=ymax_ext, ybottom=max(m.y1, (levels[0][0] - 4) if levels else m.y1))
    finish(compose(iso2, [('isometric from NW', (120, 120, 120))], name + ': exterior isometric (reverse)'),
           os.path.join(a.out, '00_exterior_iso_b.png'))
    # structure only (natural rock hidden): shows how ruin and barrow connect
    for vi, vname in ((0, 'SE'), (2, 'NW')):
        so = iso_render(m, view=vi, ymax=m.y2, hide_natural=True,
                        ybottom=max(m.y1, (levels[0][0] - 4) if levels else m.y1))
        finish(compose(so, [('built blocks only (terrain hidden), viewed from ' + vname, (120, 120, 120))],
                       name + ': structure only'), os.path.join(a.out, '02_structure_only_%s.png' % vname))    # level cutaways
    for i, (lo_, hi_, tot) in enumerate(levels):
        im, n = level_cutaway(m, lo_, hi_, walk, scale if scale >= 4 else 4)
        title = '%s: level %d  y=%d..%d  (%d walkable cells)' % (name, i + 1, lo_, hi_, n)
        finish(compose(im, CUT_LEGEND, title), os.path.join(a.out, '1%d_level_y%d.png' % (i, lo_)))
    # interior close-ups
    foci = []
    for f in a.foci.split(';'):
        if f.strip():
            nm, fx, fy, fz = f.split(':')
            foci.append((nm, int(fx), int(fy), int(fz)))
    if not foci:
        foci = auto_foci(m, walk, levels, cx, cz)
    for j, (nm, fx, fy, fz) in enumerate(foci):
        R = 20
        sub = (max(m.x1, fx - R), max(m.z1, fz - R), min(m.x2, fx + R), min(m.z2, fz + R))
        im = iso_render(m, view=0, ymax=fy + 3, ybottom=fy - 3, hide_natural=True, sub=sub, scale=13)
        finish(compose(im, [('room cut open at floor+3 (natural rock hidden)', (120, 120, 120))],
                       '%s: %s around (%d,%d,%d)' % (name, nm, fx, fy, fz)),
               os.path.join(a.out, '2%d_interior_%s.png' % (j, nm)))
    # stats
    stats(m, levels, a.out, name)


def auto_foci(m, walk, levels, cx=None, cz=None):
    foci = []
    # entrance: walkable cell of the highest-ish level nearest the bbox edge (min distance to horizontal border)
    allw = np.argwhere(walk)  # y,z,x
    ww = m.w
    # boss / word wall
    for be in m.be:
        if 'word_wall' in be['id']:
            foci.append(('boss_wordwall', be['x'], be['y'] - 1, be['z']))
    if not levels:
        return foci
    top_lvl = max(levels, key=lambda l: l[0])
    # entrance = walkable cell on the topmost sizable level closest to the xz border of the dungeon
    ys = top_lvl
    sel = allw[(allw[:, 0] + m.y1 >= ys[0]) & (allw[:, 0] + m.y1 <= ys[1])]
    if len(sel):
        if cx is None:
            cx, cz = (m.x1 + m.x2) // 2, (m.z1 + m.z2) // 2
        k = int(np.argmin((sel[:, 2] + m.x1 - cx) ** 2 + (sel[:, 1] + m.z1 - cz) ** 2))
        y, z, x = sel[k]
        foci.insert(0, ('entrance', x + m.x1, y + m.y1, z + m.z1))
    # mid room: largest level's centroid-nearest walkable cell, plus a chest room
    big = max(levels, key=lambda l: l[2])
    sel = allw[(allw[:, 0] + m.y1 >= big[0]) & (allw[:, 0] + m.y1 <= big[1])]
    if len(sel):
        cen = sel.mean(0)
        k = int(np.argmin(((sel - cen) ** 2).sum(1)))
        y, z, x = sel[k]
        foci.append(('mid_room', x + m.x1, y + m.y1, z + m.z1))
    for be in m.be:
        pass
    sp = [be for be in m.be if be['id'].endswith('mob_spawner')]
    if sp and cx is not None:
        be = min(sp, key=lambda b: (b['x'] - cx) ** 2 + (b['z'] - cz) ** 2)
        foci.append(('spawner_room', be['x'], be['y'] - 1, be['z']))
    return foci[:4]


def stats(m, levels, out, name):
    ids, cnt = np.unique(m.A[m.built[m.A]], return_counts=True)
    order = np.argsort(-cnt)[:25]
    lines = ['# %s: top built block types' % name]
    tot = cnt.sum()
    for i in order:
        lines.append('%6d %5.1f%% %s' % (cnt[i], 100.0 * cnt[i] / tot, m.names[ids[i]]))
    import collections
    c = collections.Counter(be['id'] for be in m.be)
    lines.append('# block entities: ' + ', '.join('%s=%d' % kv for kv in c.most_common()))
    lines.append('# levels: ' + str(levels))
    open(os.path.join(out, 'stats.txt'), 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines[:14]))


if __name__ == '__main__':
    main()





