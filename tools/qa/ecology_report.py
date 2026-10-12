"""Ecology report: what the region spawn tables put in each region and biome family.

Reads mod/src/main/resources/data/skycraft/region_spawns/*.json (the rules the RegionSpawner runs) and, for
Skyrim, which biomes each hold's palette places (tools/worldgen/biomes.py), and prints per region x ecology family:

  kinds   distinct creature kinds by category: passive / predator / vermin (spiders, skeever, chaurus) /
          humanoid / undead / rare (bosses, giants, daedra, wisps); the category comes from a group's "tag",
          else from the entity id
  hostile share of the region's hostile-attempt weight that becomes a hostile group (the rest is "quiet"
          entries: attempts that end with nothing), by day and by night, and the share of the *attempts* that are
          humanoid, undead
  levels  the level band the family's hostile groups cover, and the region's own level range
  roads   the expected chance that a 300-block walk along a road meets a hostile group (analytic, from the
          shipped config defaults and the family's day / night hostility)

usage: py -3 -I tools/qa/ecology_report.py [--province skyrim] [--all] [--json] [--md]
  --all   every province, one row per BiomeFamily name-class (no palette data outside Skyrim)
  --md    markdown tables (docs/ECOLOGY.md)
  --road-player-factor X   override the config's roadPlayerFactor (1.0 reproduces the pre-ecology behaviour)
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SPAWNS = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'data', 'skycraft', 'region_spawns')
REGIONS = os.path.join(ROOT, 'mod', 'src', 'main', 'resources', 'skycraft', 'tamriel', 'regions.json')
BIOMES_PY = os.path.join(ROOT, 'tools', 'worldgen', 'biomes.py')
CONFIG = os.path.join(ROOT, 'mod', 'src', 'main', 'java', 'com', 'skycraft', 'creatures', 'CreaturesConfig.java')

# ecology families of Skyrim's biome ids (the owner's vocabulary)
ECO_FAMILIES = {
    'tundra': ['whiterun_tundra', 'whiterun_plains', 'alpine_meadow', 'pale_heath'],
    'pine_forest': ['pine_forest', 'falkreath_forest'],
    'snowy_pine': ['snowy_pine_forest'],
    'autumn_forest': ['rift_autumn_forest'],
    'reach_crags': ['reach_crags', 'reach_valley'],
    'marsh': ['hjaalmarch_marsh'],
    'volcanic_tundra': ['eastmarch_volcanic_tundra'],
    'hot_springs': ['eastmarch_hot_springs'],
    'glacier': ['winterhold_glacier', 'pale_snowfield'],
    'mountain': ['skyrim_highlands', 'snowy_slopes', 'throat_of_the_world'],
    'coast': ['skyrim_coast', 'rocky_shore', 'frozen_shore'],
    'water': ['skyrim_river', 'skyrim_lake', 'frozen_river'],
}
BIOME_ECO = {b: f for f, bs in ECO_FAMILIES.items() for b in bs}

# BiomeFamily.resolve by name (the tag checks cannot be replayed here): first match wins, same order as the Java
NAME_FAMILY = [
    ('ocean', ('ocean', 'sea_of', 'deep_sea')), ('river', ('river',)), ('beach', ('beach', 'coast', 'shore')),
    ('ash', ('ash', 'volcan', 'red_mountain', 'molag', 'caldera', 'lava')),
    ('swamp', ('swamp', 'marsh', 'mire', 'bog', 'mangrove', 'fen')),
    ('desert', ('desert', 'dune', 'badland', 'canyon', 'alikr', 'sand')),
    ('jungle', ('jungle', 'rainforest', 'hist', 'tropical', 'pellitine')),
    ('frozen', ('snow', 'glacier', 'ice', 'frozen', 'tundra', 'frost', 'pale')),
    ('mountain', ('peak', 'mountain', 'crag', 'highland', 'cliff')),
    ('forest', ('forest', 'wood', 'grove', 'taiga', 'pine', 'birch', 'weald', 'graht')),
]

PASSIVE = {'deer', 'elk', 'rabbit', 'goat', 'horse', 'cow', 'sheep', 'chicken', 'fox', 'mudcrab', 'horker', 'mammoth',
           'bat', 'pig', 'guar', 'kagouti', 'alit', 'netch', 'durzog', 'indrik', 'kamal', 'realm_grazer', 'wandering_soul'}
PREDATOR = {'wolf', 'bear', 'sabre_cat', 'troll', 'ice_wraith', 'slaughterfish', 'death_hound', 'senche_tiger',
            'cliff_racer', 'giant_scorpion', 'sand_serpent', 'realm_stalker', 'polar_bear'}
VERMIN = {'frostbite_spider', 'skeever', 'chaurus', 'chaurus_hunter', 'dwarven_spider', 'spider_daedra', 'lurker'}
HUMANOID = {'bandit', 'bandit_chief', 'forsworn', 'hagraven', 'necromancer', 'vampire', 'falmer', 'riekling', 'dremora',
            'tsaesci', 'minotaur', 'ogre', 'grummite', 'keeper', 'harpy', 'imp', 'scamp', 'thug'}
UNDEAD = {'draugr', 'draugr_deathlord', 'boneman', 'wandering_soul', 'harvester', 'skeleton', 'zombie'}
RARE = {'giant', 'frost_giant', 'dragon', 'dragon_priest', 'wispmother', 'wisp', 'will_o_wisp', 'gargoyle',
        'spriggan', 'ash_spawn', 'dwarven_ballista', 'dwarven_centurion', 'vampire_lord', 'swamp_leviathan'}
CATS = ['passive', 'predator', 'vermin', 'humanoid', 'undead', 'rare']


def category(entity, tag):
    if tag in CATS:
        return tag
    short = entity.split(':')[-1]
    for name, s in (('passive', PASSIVE), ('predator', PREDATOR), ('vermin', VERMIN), ('humanoid', HUMANOID),
                    ('undead', UNDEAD), ('rare', RARE)):
        if short in s:
            return name
    return 'rare'


def name_family(biome):
    n = biome.split(':')[-1]
    for fam, words in NAME_FAMILY:
        if any(w in n for w in words):
            return fam
    return 'plains'


def load_regions():
    return {r['id']: r for r in json.load(open(REGIONS, encoding='utf8'))}


def load_rules(province=None):
    out = []
    for f in sorted(glob.glob(os.path.join(SPAWNS, '*.json'))):
        data = json.load(open(f, encoding='utf8'))
        if isinstance(data, dict):
            data = [data]
        for r in data:
            if province and r.get('provinces') and province not in r['provinces']:
                continue
            out.append((os.path.basename(f), r))
    return out


def hold_biomes():
    """region id -> set of biome ids its palette places (Skyrim holds), parsed from tools/worldgen/biomes.py."""
    src = open(BIOMES_PY, encoding='utf8').read()
    pals = {}
    for m in re.finditer(r'^pal\("([a-z_/]+)",(.*?)^(?=pal\(|#|\S)', src, re.S | re.M):
        pals[m.group(1)] = m.group(2)

    def keys(text):
        d = {}
        # key=<"id" | [("id", w), ...]>  (balanced one level)
        for km in re.finditer(r'(\w+)=("[^"]+"|\[[^\]]*\]|[A-Z_]+)', text):
            ids = re.findall(r'"([a-z_]+)"', km.group(2))
            d[km.group(1)] = ids
        return d
    base = keys(pals.get('province/skyrim', ''))
    out = {}
    for pid, text in pals.items():
        if not pid.startswith('skyrim/'):
            continue
        k = dict(base)
        k.update(keys(text))
        ids = set()
        for key, vals in k.items():
            if key in ('upland_y', 'peak_y', 'summit_y', 'bias', 'fallback'):
                continue
            ids.update(v for v in vals if v not in ('SURFACE',))
        out[pid] = ids
    return out


def rule_matches(rule, region_id, province, biome):
    if rule.get('regions') and region_id not in rule['regions']:
        return False
    if rule.get('provinces') and province not in rule['provinces']:
        return False
    fam = name_family(biome)
    if rule.get('families') and fam not in rule['families']:
        return False
    if rule.get('biomes'):
        bid = biome if ':' in biome else 'skycraft:' + biome
        if not any(b == bid for b in rule['biomes'] if not b.startswith('#')):
            return False
    return True


def members(g):
    return g.get('members') or ([g] if 'entity' in g else [])


def time_bucket(g):
    t = g.get('time', 'any')
    return {'any': 'both', 'day': 'day', 'dawn': 'day', 'dusk': 'day', 'night': 'night'}[t]


def analyse(rules, region_id, region, biomes, place='surface'):
    """Stats for one region x set of biome ids."""
    cats = {c: set() for c in CATS}
    w = {'day': {'hostile': 0.0, 'quiet': 0.0}, 'night': {'hostile': 0.0, 'quiet': 0.0}}
    share_cat = {'day': {c: 0.0 for c in CATS}, 'night': {c: 0.0 for c in CATS}}
    wild = {'day': 0.0, 'night': 0.0}
    lvmin, lvmax = 999, 0
    seen = {}
    for fname, rule in rules:
        # a rule applies once per (rule, region), however many of the family's biomes it matches
        if not any(rule_matches(rule, region_id, region['province'], b) for b in biomes):
            continue
        for g in rule.get('groups', []):
            if g.get('place', 'surface') != place:
                continue
            lo, hi = (g.get('levels') or [1, 999])[0], (g.get('levels') or [1, 999])[-1]
            if hi < region['levels'][0] or lo > region['levels'][1]:
                continue                     # outside the hold's own level range: never appears
            wt = g.get('weight', 10)
            buckets = ['day', 'night'] if time_bucket(g) == 'both' else [time_bucket(g)]
            # dawn/dusk are slices of day: count at a third of their weight in the day pool
            if g.get('time') in ('dawn', 'dusk'):
                wt = wt / 3.0
            if g.get('quiet'):
                for b in buckets:
                    w[b]['quiet'] += wt
                continue
            ms = members(g)
            if not ms:
                continue
            main = category(ms[0]['entity'], g.get('tag', ''))
            for m in ms:
                cats[category(m['entity'], g.get('tag', '') if m is ms[0] else '')].add(m['entity'].split(':')[-1])
            if g.get('kind', 'hostile') == 'wildlife':
                for b in buckets:
                    wild[b] += wt
                continue
            lvmin = min(lvmin, max(lo, region['levels'][0]))
            lvmax = max(lvmax, min(hi, region['levels'][1]))
            for b in buckets:
                w[b]['hostile'] += wt
                share_cat[b][main] += wt
    return cats, w, share_cat, wild, (lvmin, lvmax)


def road_walk_risk(hostile_share, night, cfg):
    """Chance of at least one hostile group during a 300-block walk on a road (analytic)."""
    attempts = 300 / 4.3 / 2.0                       # one hostile attempt every 2 s at walking speed
    p = hostile_share * cfg['roadPlayerFactor'] * (1.0 if night else cfg['dayHostileFactor'])
    return 1.0 - (1.0 - p) ** attempts


def read_cfg():
    cfg = {'roadPlayerFactor': 1.0, 'dayHostileFactor': 0.5}
    try:
        src = open(CONFIG, encoding='utf8').read()
        for k in cfg:
            m = re.search(r'"%s",\s*([0-9.]+)' % k, src)
            if m:
                cfg[k] = float(m.group(1))
    except OSError:
        pass
    return cfg


def main(argv):
    province = 'skyrim'
    md = '--md' in argv
    as_json = '--json' in argv
    if '--province' in argv:
        province = argv[argv.index('--province') + 1]
    regions = load_regions()
    rules = load_rules(province)
    cfg = read_cfg()
    if '--road-player-factor' in argv:
        cfg['roadPlayerFactor'] = float(argv[argv.index('--road-player-factor') + 1])
    rows = []
    if province == 'skyrim':
        hb = hold_biomes()
        for rid, r in regions.items():
            if r['province'] != 'skyrim' or rid not in hb:
                continue
            for fam, bs in ECO_FAMILIES.items():
                have = [b for b in bs if b in hb[rid]]
                if not have:
                    continue
                rows.append((rid, fam, r, have))
    else:
        for rid, r in regions.items():
            if r['province'] != province:
                continue
            fams = sorted({name_family(b) for _, rule in rules for b in (rule.get('biomes') or [])} | {
                f for _, rule in rules for f in rule.get('families', [])})
            for fam in fams or ['plains']:
                rows.append((rid, fam, r, [fam]))
    out = []
    for rid, fam, r, biomes in rows:
        rr = rules
        cats, w, share, wild, (lo, hi) = analyse(rr, rid, r, biomes)
        ccats = analyse(rr, rid, r, ['cave'], 'cave')[0]
        row = {'region': rid, 'family': fam, 'caveKinds': len(set().union(*ccats.values())), 'regionLevels': r['levels'],
               'kinds': {c: len(cats[c]) for c in CATS}, 'kindNames': {c: sorted(cats[c]) for c in CATS},
               'wildlifeKinds': len(cats['passive']), 'levels': [lo, hi] if hi else None}
        for b in ('day', 'night'):
            tot = w[b]['hostile'] + w[b]['quiet']
            row['hostile_' + b] = round(w[b]['hostile'] / tot, 2) if tot else 0.0
            row['humanoid_' + b] = round(share[b]['humanoid'] / tot, 2) if tot else 0.0
            row['undead_' + b] = round(share[b]['undead'] / tot, 2) if tot else 0.0
            row['road300_' + b] = round(road_walk_risk(row['hostile_' + b], b == 'night', cfg), 2)
        out.append(row)
    if as_json:
        print(json.dumps(out, indent=1))
        return 0
    total_cats = {c: 0 for c in CATS}
    head = ['region', 'family', 'lv region', 'passive', 'predator', 'vermin', 'humanoid', 'undead', 'rare',
            'hostile day', 'hostile night', 'hum%d', 'dead%n', 'hostile lv', 'road300 d/n', 'cave kinds']
    lines = []
    for row in out:
        k = row['kinds']
        lines.append([row['region'].split('/')[-1], row['family'], '%d-%d' % tuple(row['regionLevels']),
                      k['passive'], k['predator'], k['vermin'], k['humanoid'], k['undead'], k['rare'],
                      '%d%%' % round(row['hostile_day'] * 100), '%d%%' % round(row['hostile_night'] * 100),
                      '%d%%' % round(row['humanoid_day'] * 100), '%d%%' % round(row['undead_night'] * 100),
                      '%d-%d' % tuple(row['levels']) if row['levels'] else '-',
                      '%d%%/%d%%' % (round(row['road300_day'] * 100), round(row['road300_night'] * 100)),
                      row['caveKinds']])
        for c in CATS:
            total_cats[c] += k[c]
    if md:
        print('| ' + ' | '.join(head) + ' |')
        print('|' + '---|' * len(head))
        for l in lines:
            print('| ' + ' | '.join(str(x) for x in l) + ' |')
    else:
        widths = [max(len(str(r[i])) for r in [head] + lines) for i in range(len(head))]
        for r in [head] + lines:
            print('  '.join(str(x).ljust(widths[i]) for i, x in enumerate(r)))
    # summary
    n = len(out) or 1
    hd = sum(r['hostile_day'] for r in out) / n
    hn = sum(r['hostile_night'] for r in out) / n
    rd = sum(r['road300_day'] for r in out) / n
    rn = sum(r['road300_night'] for r in out) / n
    kinds = set()
    for r in out:
        for c in CATS:
            kinds.update(r['kindNames'][c])
    print()
    print('rows %d | distinct kinds %d | mean hostile share day %.0f%% night %.0f%% | 300-block road walk day %.0f%% '
          'night %.0f%% | min wildlife kinds %d' % (
              len(out), len(kinds), hd * 100, hn * 100, rd * 100, rn * 100,
              min((r['wildlifeKinds'] for r in out), default=0)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
