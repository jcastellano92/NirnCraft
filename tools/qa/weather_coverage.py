"""Audit weather coverage after weatherCoverageAudit; writes read-only reports to dist/weather-coverage.

Run the runtime sweep first:
  mod/gradlew.bat -p mod -I ../tools/qa/weather_coverage.gradle weatherCoverageAudit
Then: py -3 -I tools/qa/weather_coverage.py
No production resources, world saves or weather settings are changed.
"""
import csv
import json
import pathlib
import re
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / 'mod/src/main/resources'
OUT = ROOT / 'dist/weather-coverage'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write_csv(name, rows):
    with (OUT / name).open('w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main():
    OUT.mkdir(exist_ok=True, parents=True)
    runtime = read(OUT / 'runtime.json')
    regions = read(RES / 'skycraft/tamriel/regions.json')
    root = read(RES / 'skycraft/tamriel/palettes.json')
    palettes = root['palettes']
    source = (ROOT / 'mod/src/main/java/com/skycraft/atmosphere/weather/WeatherProfile.java').read_text()
    overrides = {rid: profile for profile, body in re.findall(r'regions\((\w+),\s*(.*?)\);', source, re.S)
                 for rid in re.findall(r'"([^"]+)"', body)}
    ids = {r['id'] for r in regions}
    orphans = sorted(overrides.keys() - ids)
    compiled = [(key, rule) for key, p in palettes.items() for rule in p.get('micro', [])]
    unused = [(key, rule) for key, p in root.items() if isinstance(p, dict) and key != 'palettes'
              for rule in p.get('micro', [])]
    declarations = Counter()
    for key, rule in compiled:
        for field in ('weather', 'climate'):
            if isinstance(rule.get(field), dict):
                declarations[field] += 1
                for driver in rule[field]:
                    declarations[field + '.' + driver] += 1
    # Inventory every generated biome and every palette reference, including cave/realm entries.
    biomes = {p.stem: read(p) for p in sorted((RES / 'data/skycraft/worldgen/biome').glob('*.json'))}
    refs = Counter()
    for key, p in palettes.items():
        for choices in p.get('contexts', {}).values():
            for choice in choices:
                refs[choice['biome'].removeprefix('skycraft:')] += 1
        for rule in p.get('micro', []):
            refs[rule['biome'].removeprefix('skycraft:')] += 1
    catalogue = {x.removeprefix('skycraft:') for x in root['catalogue']}
    biome_rows = []
    for name, biome in biomes.items():
        biome_rows.append(dict(biome='skycraft:' + name, temperature=biome.get('temperature'),
                               downfall=biome.get('downfall'), precipitation=biome.get('has_precipitation'),
                               listed_in_catalogue=name in catalogue, palette_reference_count=refs[name],
                               weather_reads_downfall=False, cloud_physics_reads_biome_temperature=False,
                               client_precipitation_uses_current_biome=True))
    write_csv('biomes.csv', biome_rows)
    micro_rows = []
    for location, entries in [('runtime_palettes', compiled), ('unused_top_level', unused)]:
        for key, rule in entries:
            weather = rule.get('weather', rule.get('climate', {}))
            micro_rows.append(dict(storage=location, palette=key, rule=rule['id'], biome=rule.get('biome'),
                                   conditions=json.dumps(rule.get('when', {}), separators=(',', ':')),
                                   climate=json.dumps(weather, separators=(',', ':')),
                                   climate_consumed_by_weather=False,
                                   why=';'.join(rule.get('why', []))))
    write_csv('microbiomes.csv', micro_rows)
    region_rows = []
    extremes = {'storm', 'blizzard', 'sandstorm', 'ash_storm', 'blight'}
    for r in regions:
        observed = runtime['regions'][r['id']]
        rolls = observed['roll_distribution_20000_spells_each']['6000']
        p = palettes.get(r['id'], palettes.get(r.get('palette', ''), {}))
        ranges = observed['ranges']
        def mean(name):
            return round(ranges[name]['mean'], 3) if name in ranges else ''
        region_rows.append(dict(region=r['id'], province=r['province'], broad_climate=r.get('weather'),
                                resolved_profile=observed['profile'], tuned_override=r['id'] in overrides,
                                normal_kinds=';'.join(k for k in observed['allowed_kinds'] if k not in extremes),
                                extreme_kinds=';'.join(k for k in observed['allowed_kinds'] if k in extremes),
                                extreme_noon_time_share=round(sum(v['time_share'] for k, v in rolls.items() if k in extremes), 5),
                                snow_line=observed['snow_line'], runtime_micro_rules=len(p.get('micro', [])),
                                unused_top_level_rules=len(root.get(r['id'], {}).get('micro', [])),
                                spatial_samples=observed['spatial_points'], clear_noon_C=mean('clear_noon_C'),
                                clear_night_C=mean('clear_night_C'), clear_noon_RH=mean('clear_noon_RH'),
                                clear_night_RH=mean('clear_night_RH'),
                                diagnostic_flags=json.dumps(observed['flags'], separators=(',', ':'))))
    write_csv('regions.csv', region_rows)
    sheet_rows = []
    for path in sorted((RES / 'skycraft/tamriel/world').glob('*.json')):
        sheet = read(path)
        for scope, layers in [(sheet['province'], sheet.get('layers', {}))] + [
                (rid, r.get('layers', {})) for rid, r in sheet.get('regions', {}).items()]:
            for entry in layers.get('L2', []):
                text = entry.get('text', '')
                sheet_rows.append(dict(province=sheet['province'], scope=scope,
                                       field=scope + '.L2.' + entry['id'], requirement=text,
                                       requires_season=bool(re.search(r'season|summer|winter|spring|autumn|thaw', text, re.I)),
                                       cites=';'.join(entry.get('cites', []))))
    write_csv('climate-requirements.csv', sheet_rows)
    flags = Counter()
    for region in runtime['regions'].values():
        flags.update(region['flags'])
    summary = dict(regions=len(regions), tuned_regions=sum(r['id'] in overrides for r in regions),
                   fallback_regions=[r['id'] for r in regions if r['id'] not in overrides], orphan_overrides=orphans,
                   generated_biomes=len(biomes), catalogue_biomes=len(catalogue), runtime_micro_records=len(compiled),
                   unused_top_level_micro_records=len(unused), micro_climate_declarations=dict(declarations),
                   world_climate_requirements=len(sheet_rows), seasonal_requirements=sum(r['requires_season'] for r in sheet_rows),
                   sampled_locations=sum(v['spatial_points'] for v in runtime['regions'].values()),
                   column_evaluations=runtime['column_evaluations'], flags=dict(flags),
                   no_grid_samples=[r for r, v in runtime['regions'].items() if not v['spatial_points']],
                   production_files_changed=False)
    (OUT / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    audit_rasters(regions)
    plot_coverage(runtime)
    print(json.dumps(summary, indent=2))


def audit_rasters(regions):
    import numpy as np
    from PIL import Image
    maps = {}
    base = RES / 'skycraft/tamriel'
    for name in ['regions', 'elevation', 'coast', 'features', 'terrain', 'water', 'heat', 'rainshadow']:
        with Image.open(base / (name + '.png')) as image:
            values = np.array(image)
        maps[name] = values
    fields = {'macro_elevation': maps['elevation'] / 32.0 - 64,
              'coast_distance': maps['coast'].astype(float) - 32768,
              'heat': maps['heat'] / 255.0, 'rainshadow': maps['rainshadow'] / 255.0,
              'dunes': maps['terrain'][:, :, 2] / 255.0,
              'wetland': maps['terrain'][:, :, 3] / 255.0,
              'ridge': maps['features'][:, :, 1] / 255.0}
    per_region = {}
    for region in regions:
        mask = maps['regions'] == region['index']
        stats = {'raster_cells': int(mask.sum())}
        if mask.any():
            for key, values in fields.items():
                selected = values[mask]
                stats[key] = dict(min=float(selected.min()), max=float(selected.max()), mean=float(selected.mean()))
        per_region[region['id']] = stats
    report = {'scope': 'Every authored raster cell; macro elevation before runtime peak/detail/plan overlays. Foreign lands are procedural and covered by runtime grid probes.',
              'cells': int(maps['regions'].size),
              'rasters': {key: {'shape': list(value.shape), 'dtype': str(value.dtype)} for key, value in maps.items()},
              'regions': per_region}
    (OUT / 'raster-stats.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


def plot_coverage(runtime):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    with (OUT / 'spatial-samples.csv').open() as f:
        rows = list(csv.DictReader(f))
    x = np.array([float(r['x']) / 1000 for r in rows])
    z = np.array([float(r['z']) / 1000 for r in rows])
    fig, axes = plt.subplots(2, 2, figsize=(13, 10), constrained_layout=True)
    for ax in axes.flat:
        ax.set_facecolor('#dce7ed')
        ax.set_aspect('equal')
        ax.invert_yaxis()
        ax.set_xlabel('World X (thousand blocks)')
        ax.set_ylabel('World Z (thousand blocks; north up)')
        ax.grid(alpha=.15)
    for ax, field, title, cmap, lo, hi in [
        (axes[0, 0], 'elevation', 'Runtime macro terrain and fixed foreign lands', 'terrain', 40, 650),
        (axes[0, 1], 'clear_noon_RH', 'Clear-noon model humidity (not biome downfall)', 'YlGnBu', .25, .9),
    ]:
        values = [float(r[field]) for r in rows]
        cloud = ax.scatter(x, z, c=values, s=3, cmap=cmap, vmin=lo, vmax=hi, rasterized=True)
        fig.colorbar(cloud, ax=ax, shrink=.7, label='Blocks' if field == 'elevation' else 'Relative humidity')
        ax.set_title(title)
    ax = axes[1, 0]
    ax.scatter(x, z, c='#8499a0', s=2, alpha=.5)
    colors = ['#3274a1', '#e1812c', '#cfad19', '#a54d72', '#23886b', '#8b65ad']
    for basin, color in zip(runtime['front_basins'], colors):
        x0, z0, x1, z1 = [v / 1000 for v in basin['origin_bounds']]
        ax.add_patch(plt.Rectangle((x0, z0), x1-x0, z1-z0, fill=False, edgecolor=color, linewidth=2))
        ax.text((x0+x1)/2, z0-.2, str(basin['basin']), color=color, weight='bold', fontsize=9)
    ax.set_title('Actual spawn boxes: six front basins')
    ax.text(.02, .02, '0 polar / 1 marine / 2 sand\n3 ash / 4 "Topal" / 5 mountain', transform=ax.transAxes,
            fontsize=8, bbox=dict(facecolor='white', alpha=.8, edgecolor='none'))
    ax = axes[1, 1]
    ax.scatter(x, z, c='#8499a0', s=2, alpha=.35)
    for code, color, label in [(0.7, '#c47821', 'Rain probe uses sand category'), (.92, '#a14253', 'Rain probe uses ash category')]:
        mask = np.array([abs(float(r['storm_noon_aerosol'])-code)<.005 for r in rows])
        ax.scatter(x[mask], z[mask], s=5, c=color, label=label)
    ax.legend(loc='lower right', fontsize=8)
    ax.set_title('Diagnostic rain-storm probes: material conflicts')
    fig.suptitle('Tamriel weather coverage audit — sampled runtime data\n'
                 '256-block grid; probes are controlled conditions, not observed weather frequency', fontsize=13)
    fig.savefig(OUT / 'coverage-map.png', dpi=160)
    plt.close(fig)


if __name__ == '__main__':
    main()
