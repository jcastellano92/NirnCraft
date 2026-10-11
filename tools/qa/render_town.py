"""Offline TOWN audit renderer: plan view, isometric close-ups, clutter-density chart and a one-image sheet.
Reads a saved world folder (see town_checks.py); no Minecraft client.

usage:
  py -3 -I tools/qa/render_town.py <world_dir> <place_id> <out_dir> [--half N]
  py -3 -I tools/qa/render_town.py <world_dir> corridor <placeA> <placeB> <out_dir>

Outputs per town: 00_sheet.jpg (<=1600 px wide), 01_plan.png (2 px/block, schematic over faded terrain),
02_iso_market.png, 03_iso_poor.png, 04_iso_gate.png, 05_density.png, report.json.
"""
import argparse
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render_dungeon as RD  # noqa: E402  (read-only helpers: Model, iso_render, font, find_place, legend_strip)
import town_checks as TC  # noqa: E402
from mca import World  # noqa: E402

S = 2  # plan scale, px per block
COL = {
    'poor': (120, 80, 60), 'common': (172, 132, 92), 'wealthy': (216, 186, 122), 'noble': (245, 238, 214),
    'street': (70, 70, 74), 'street_major': (125, 125, 130), 'highway': (230, 30, 30), 'road': (255, 128, 0),
    'lane': (255, 220, 40), 'trail': (180, 120, 255), 'prop': (0, 255, 0), 'stall': (255, 0, 255),
    'garden': (30, 120, 30), 'pen': (150, 100, 30), 'gate': (255, 255, 0), 'wall': (20, 20, 20),
    'water': (40, 90, 220), 'sign': (255, 255, 255), 'shrine': (190, 80, 255), 'lamp': (255, 255, 160),
}
LEGEND = [('poor', COL['poor']), ('common', COL['common']), ('wealthy', COL['wealthy']), ('noble', COL['noble']),
          ('street', COL['street']), ('main street', COL['street_major']), ('highway', COL['highway']),
          ('road', COL['road']), ('lane', COL['lane']), ('trail', COL['trail']), ('prop/yard', COL['prop']),
          ('stall', COL['stall']), ('garden', COL['garden']), ('pen', COL['pen']), ('gate', COL['gate']),
          ('wall', COL['wall']), ('water', COL['water'])]


def terrain_rgb(area):
    n = len(area.names)
    colt = np.array([RD.base_color(nm) for nm in area.names], np.float32)
    bid = area.topblock
    col = colt[bid]
    h = area.topany.astype(np.float32)
    gx = np.gradient(h, axis=1)
    gz = np.gradient(h, axis=0)
    col *= np.clip(1.0 + 0.16 * (-gx - gz), 0.65, 1.35)[..., None]
    col[area.has_water_top] = (45, 85, 190)
    col[~area.loaded] = (12, 12, 14)
    return np.clip(col, 0, 255).astype(np.uint8)


def plan_image(area, pieces, roads, res):
    X, Z = area.x2 - area.x1 + 1, area.z2 - area.z1 + 1
    base = Image.fromarray(terrain_rgb(area)).resize((X * S, Z * S), Image.NEAREST)
    base = Image.blend(base, Image.new('RGB', base.size, (30, 30, 30)), 0.42)
    d = ImageDraw.Draw(base, 'RGBA')

    def P(x, z):
        return ((x - area.x1 + 0.5) * S, (z - area.z1 + 0.5) * S)

    # roads (network polylines) under everything
    for r in roads.in_box(area.x1 - 50, area.z1 - 50, area.x2 + 50, area.z2 + 50):
        k = TC.ROAD_KIND[min(3, r['kind'])]
        pts = [P(int(x), int(z)) for x, z in zip(r['xs'], r['zs'])]
        d.line(pts, fill=COL[k], width=max(2, TC.ROAD_WIDTH[min(3, r['kind'])] * S // 2))
    order = {'GARDEN': 0, 'PEN': 0, 'STREET': 1, 'WALL': 2}
    for p in sorted(pieces, key=lambda q: order.get(q.kind, 3)):
        cx, cz = p.centre()
        if p.kind == 'STREET':
            c = COL['street_major'] if p.flags & TC.F_MAJOR else COL['street']
            d.line([P(p.x, p.z), P(p.x2, p.z2)], fill=c, width=max(2, p.w * S))
        elif p.kind == 'WALL':
            d.line([P(p.x, p.z), P(p.x2, p.z2)], fill=COL['wall'], width=S + 1)
        elif p.kind in ('BRIDGE', 'CANAL'):
            d.line([P(p.x, p.z), P(p.x2, p.z2)], fill=(200, 160, 100) if p.kind == 'BRIDGE' else COL['water'], width=max(2, p.w * S))
        elif p.building:
            hx, hz = p.half()
            x0, y0 = P(cx - hx, cz - hz)
            x1, y1 = P(cx + hx, cz + hz)
            c = COL[TC.WEALTH[min(3, max(0, p.wealth))]]
            d.rectangle([x0, y0, x1, y1], fill=c + (255,), outline=(20, 20, 20, 255))
        elif p.kind == 'GATE':
            hx, hz = p.half()
            x0, y0 = P(cx - hx, cz - hz)
            x1, y1 = P(cx + hx, cz + hz)
            d.rectangle([x0, y0, x1, y1], fill=COL['gate'] + (255,), outline=(0, 0, 0, 255))
        elif p.kind == 'YARD':
            x, y = P(cx, cz)
            c = COL['lamp'] if p.label() == 'LAMP_POST' else COL['shrine'] if p.label() == 'SHRINE' else \
                COL['sign'] if p.label() == 'SIGNPOST' else COL['prop']
            r = 2 if p.label() in ('LAMP_POST', 'SIGNPOST') else 3
            d.rectangle([x - r, y - r, x + r, y + r], fill=c + (255,), outline=(0, 0, 0, 255))
        elif p.kind == 'STALL':
            x, y = P(cx, cz)
            d.rectangle([x - 4, y - 4, x + 4, y + 4], fill=COL['stall'] + (255,), outline=(0, 0, 0, 255))
        elif p.kind in ('GARDEN', 'PEN'):
            hx, hz = p.half()
            x0, y0 = P(cx - hx, cz - hz)
            x1, y1 = P(cx + hx, cz + hz)
            d.rectangle([x0, y0, x1, y1], fill=COL['garden' if p.kind == 'GARDEN' else 'pen'] + (200,))
    # road network features (shrines, signposts) from RoadsData
    for f in roads.features:
        if area.in_box(f['x'], f['z']):
            x, y = P(f['x'], f['z'])
            c = COL['shrine'] if f['type'] == 3 else COL['sign'] if f['type'] in (1, 2) else COL['lamp']
            rr = 3 if f['type'] in (1, 3) else 2
            d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=c + (255,), outline=(0, 0, 0, 255))
    # north arrow + scale
    d.text((6, 4), 'N^  %d px = %d blocks   %s' % (100 * S, 100, res.get('id', '')), fill=(255, 255, 255, 255), font=RD.font(14))
    return base


def focus_points(pieces, place):
    cx0, cz0 = place['x'], place['z']
    bld = [p for p in pieces if p.building]
    out = {}
    plazas = [p for p in pieces if p.kind in ('PLAZA', 'WELL')] + [p for p in pieces if p.kind == 'YARD' and p.label() in ('FOUNTAIN', 'WELL')]
    stalls = [p for p in pieces if p.kind == 'STALL']
    if stalls:
        out['market'] = (int(np.mean([p.x for p in stalls])), int(np.mean([p.z for p in stalls])))
    elif plazas:
        q = min(plazas, key=lambda p: math.hypot(p.x - cx0, p.z - cz0))
        out['market'] = (q.x, q.z)
    else:
        out['market'] = (cx0, cz0)
    # poor quarter: lowest wealth with most same-tier neighbours, away from centre
    if bld:
        tmin = min(b.wealth for b in bld)
        cand = [b for b in bld if b.wealth == tmin] if tmin <= 0 else sorted(bld, key=lambda b: -math.hypot(b.x - cx0, b.z - cz0))[:max(3, len(bld) // 5)]
        best = max(cand, key=lambda b: sum(1 for o in cand if math.hypot(o.x - b.x, o.z - b.z) < 28) + 0.002 * math.hypot(b.x - cx0, b.z - cz0))
        out['poor'] = (best.x, best.z)
    else:
        out['poor'] = (cx0 + 30, cz0 + 30)
    gates = [p for p in pieces if p.kind == 'GATE']
    if gates:
        out['gate'] = (gates[0].x, gates[0].z)
    else:
        sp = [p for p in pieces if p.kind == 'YARD' and p.label() == 'SIGNPOST']
        if sp:
            q = max(sp, key=lambda p: math.hypot(p.x - cx0, p.z - cz0))
            out['gate'] = (q.x, q.z)
        else:
            out['gate'] = (cx0 + place['radius'] // 2, cz0)
    return out


def iso_view(world, x, z, half=34, view=0, maxw=1100):
    x1, z1, x2, z2 = x - half, z - half, x + half, z + half
    a = TC.Area(world, x1, z1, x2, z2)
    if not a.loaded.any():
        return None
    ok = a.top[a.loaded]
    y1 = max(0, int(ok.min()) - 5)
    y2 = int(a.topany[a.loaded].max()) + 3
    m = RD.Model(world, x1, z1, x2, z2, y1, y2)
    return RD.iso_render(m, view=view, scale=None, maxw=maxw)


def density_chart(res, w=800, h=800):
    img = Image.new('RGB', (w, h), (24, 24, 28))
    d = ImageDraw.Draw(img)
    f, fb = RD.font(15), RD.font(18)
    d.text((14, 10), 'clutter density: props per 1000 m2, by ring from centre', fill=(255, 255, 255), font=fb)
    rings = res.get('rings', [])
    mx = max([r['per1000'] for r in rings] + [0.1])
    y = 46
    for r in rings:
        bw = int((w - 330) * r['per1000'] / mx)
        d.text((14, y), '%3d-%3d m' % (r['r0'], r['r1']), fill=(200, 200, 200), font=f)
        d.rectangle([110, y, 110 + bw, y + 20], fill=(80, 200, 90))
        d.text((118 + bw, y), '%.2f  (%d props, %d bld)' % (r['per1000'], r['props'], r['buildings']), fill=(230, 230, 230), font=f)
        y += 28
        if y > h - 230:
            break
    y = max(y + 14, 60)
    d.text((14, y), 'props near buildings, by wealth tier (within 9 blocks)', fill=(255, 255, 255), font=fb)
    y += 30
    for wname in TC.WEALTH:
        bw = res.get('by_wealth', {}).get(wname, {})
        d.rectangle([14, y + 2, 30, y + 18], fill=COL[wname])
        pb = bw.get('per_building')
        d.text((40, y), '%-8s buildings %3s   props/building %s' % (wname, bw.get('buildings', 0), '-' if pb is None else pb),
               fill=(220, 220, 220), font=f)
        y += 26
    y += 10
    for k in ('props_per_building', 'yards', 'stalls', 'gardens', 'pens', 'density_inner', 'density_outer'):
        if k in res:
            d.text((14, y), '%s: %s' % (k, res[k]), fill=(180, 180, 180), font=f)
            y += 22
    return img


def fit(im, w, h, bg=(20, 20, 22)):
    im = im.copy()
    im.thumbnail((w, h), Image.LANCZOS)
    c = Image.new('RGB', (w, h), bg)
    c.paste(im, ((w - im.width) // 2, (h - im.height) // 2))
    return c


def town(world, roads, place, half, outdir):
    os.makedirs(outdir, exist_ok=True)
    res, pieces, area = TC.analyse(world, roads, place, half)
    res['verdict'] = TC.verdict(res)
    json.dump(res, open(os.path.join(outdir, 'report.json'), 'w'), indent=1, default=str)
    if not pieces:
        print(place['id'], 'no pieces:', res.get('note'))
        return res
    plan = plan_image(area, pieces, roads, res)
    plan.save(os.path.join(outdir, '01_plan.png'))
    fp = focus_points(pieces, place)
    shots = {}
    for key, name, view in (('market', '02_iso_market', 0), ('poor', '03_iso_poor', 1), ('gate', '04_iso_gate', 2)):
        try:
            im = iso_view(world, fp[key][0], fp[key][1], view=view)
        except Exception as e:  # keep going: one bad close-up must not lose the town
            print('iso failed', place['id'], key, e)
            im = None
        if im is not None:
            im.save(os.path.join(outdir, name + '.png'))
            shots[key] = im
    dens = density_chart(res)
    dens.save(os.path.join(outdir, '05_density.png'))
    # one sheet, 1600 px wide: plan left, 2x2 right
    sheet = Image.new('RGB', (1600, 840), (20, 20, 22))
    sheet.paste(fit(plan, 800, 800), (0, 40))
    cells = [('market street', shots.get('market')), ('poor quarter', shots.get('poor')),
             ('gate / road join', shots.get('gate')), ('density', dens)]
    d = ImageDraw.Draw(sheet)
    d.text((8, 6), '%s  [%s %s]  bld=%s props=%s p/b=%s verdict=%s' % (
        place['id'], place['culture'], place['type'], res.get('buildings'), res.get('props'),
        res.get('props_per_building'), res['verdict']), fill=(255, 255, 255), font=RD.font(18))
    for i, (lab, im) in enumerate(cells):
        x, y = 800 + (i % 2) * 400, 40 + (i // 2) * 400
        if im is not None:
            sheet.paste(fit(im, 396, 380), (x, y + 18))
        d.text((x + 4, y), lab, fill=(230, 220, 160), font=RD.font(14))
    leg = RD.legend_strip(1600, LEGEND)
    full = Image.new('RGB', (1600, 840 + leg.height), (20, 20, 22))
    full.paste(sheet, (0, 0))
    full.paste(leg, (0, 840))
    full.save(os.path.join(outdir, '00_sheet.jpg'), quality=84)
    print(place['id'], res['verdict'], 'bld', res.get('buildings'), 'props', res.get('props'))
    return res


def corridor(world, roads, A, B, outdir, pad=110):
    os.makedirs(outdir, exist_ok=True)
    x1, z1 = min(A['x'], B['x']) - pad, min(A['z'], B['z']) - pad
    x2, z2 = max(A['x'], B['x']) + pad, max(A['z'], B['z']) + pad
    area = TC.Area(world, x1, z1, x2, z2)
    rs = roads.in_box(x1, z1, x2, z2)
    sc = 2 if (x2 - x1) * (z2 - z1) < 600 * 900 else 1
    X, Z = x2 - x1 + 1, z2 - z1 + 1
    base = Image.fromarray(terrain_rgb(area)).resize((X * sc, Z * sc), Image.NEAREST)
    base = Image.blend(base, Image.new('RGB', base.size, (30, 30, 30)), 0.35)
    d = ImageDraw.Draw(base)

    def P(x, z):
        return ((x - x1 + 0.5) * sc, (z - z1 + 0.5) * sc)

    ends = {}
    for r in rs:
        k = TC.ROAD_KIND[min(3, r['kind'])]
        pts = [P(int(x), int(z)) for x, z in zip(r['xs'], r['zs'])]
        d.line(pts, fill=COL[k], width=max(2, TC.ROAD_WIDTH[min(3, r['kind'])] * sc // 2 + 1))
        for i in range(len(r['xs'])):  # bridge points
            if r['flags'][i] & 1:
                x, y = P(int(r['xs'][i]), int(r['zs'][i]))
                d.rectangle([x - 2, y - 2, x + 2, y + 2], fill=(255, 255, 255))
        for i in (0, len(r['xs']) - 1):
            key = (int(r['xs'][i]) // 8, int(r['zs'][i]) // 8)
            ends[key] = ends.get(key, 0) + 1
    # junction markers: road endpoints shared by 2+ roads, and endpoints in the open
    for (kx, kz), n in ends.items():
        x, y = P(kx * 8 + 4, kz * 8 + 4)
        c = (0, 255, 255) if n >= 2 else (255, 60, 255)
        d.ellipse([x - 6, y - 6, x + 6, y + 6], outline=c, width=2)
    for f in roads.features:
        if x1 <= f['x'] <= x2 and z1 <= f['z'] <= z2:
            x, y = P(f['x'], f['z'])
            c = COL['shrine'] if f['type'] == 3 else COL['sign'] if f['type'] in (1, 2) else COL['lamp']
            rr = 4 if f['type'] in (1, 3) else 2
            d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=c, outline=(0, 0, 0))
    for s in roads.settlements.values():
        b = s['box']
        if x1 - 20 <= s['x'] <= x2 + 20 and z1 - 20 <= s['z'] <= z2 + 20:
            d.rectangle([P(b[0], b[1]), P(b[2], b[3])], outline=(255, 255, 0), width=2)
            lab = s['name'] or s['place'] or str(s['id'])
            if 'inn' in s['structure'] or 'inn' in s['place']:
                lab = 'INN ' + lab
            d.text(P(b[0], b[1] - 12), lab[:28], fill=(255, 255, 255), font=RD.font(12))
    for p in (A, B):
        d.text(P(p['x'], p['z']), p['id'].upper(), fill=(255, 255, 0), font=RD.font(16))
    leg = RD.legend_strip(max(base.width, 900), [('highway', COL['highway']), ('road', COL['road']), ('lane', COL['lane']),
                                                 ('trail', COL['trail']), ('bridge pts', (255, 255, 255)),
                                                 ('junction (2+ ends)', (0, 255, 255)), ('open end', (255, 60, 255)),
                                                 ('shrine', COL['shrine']), ('signpost/milestone', COL['sign']),
                                                 ('lantern', COL['lamp']), ('settlement box', (255, 255, 0))],
                           title='road corridor %s -> %s (%d px/block)' % (A['id'], B['id'], sc))
    full = Image.new('RGB', (base.width, base.height + leg.height), (20, 20, 22))
    full.paste(leg.crop((0, 0, base.width, leg.height)), (0, 0))
    full.paste(base, (0, leg.height))
    fn = os.path.join(outdir, 'corridor_%s_%s.png' % (A['id'], B['id']))
    full.save(fn)
    st = dict(roads=len(rs), bridge_points=int(sum(int((r['flags'] & 1).sum()) for r in rs)),
              features=sum(1 for f in roads.features if x1 <= f['x'] <= x2 and z1 <= f['z'] <= z2),
              settlements=[s['name'] or s['place'] for s in roads.settlements.values() if x1 <= s['x'] <= x2 and z1 <= s['z'] <= z2],
              size=full.size)
    json.dump(st, open(os.path.join(outdir, 'corridor.json'), 'w'), indent=1, default=str)
    print('wrote', fn, st['roads'], 'roads', st['bridge_points'], 'bridge pts', st['features'], 'features')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('world')
    ap.add_argument('target', help='place id, or "corridor"')
    ap.add_argument('rest', nargs='+')
    ap.add_argument('--half', type=int, default=None)
    a = ap.parse_args()
    world = World(a.world)
    roads = TC.Roads(a.world)
    if a.target == 'corridor':
        A, B = RD.find_place(a.rest[0]), RD.find_place(a.rest[1])
        corridor(world, roads, A, B, a.rest[2])
        return
    place = RD.find_place(a.target)
    if not place:
        sys.exit('unknown place ' + a.target)
    half = a.half or min(200, place['radius'] + 30)
    town(world, roads, place, half, a.rest[0])


if __name__ == '__main__':
    main()
