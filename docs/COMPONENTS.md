# Generator and world components: what to run for what you touched

One row per component: its fast check (no game, seconds to minutes), its visual tool, and the one command. Everything
runs through `py -3 tools/harness/harness.py` (tools/harness/README.md). `harness.py run --changed` already picks the
tests a change can affect (tools/harness/impact.json). Never run a whole suite for a one-component change.

| Component | Fast check | Visual tool | One command |
|---|---|---|---|
| Terrain, regions | `check-regions`, `check-worldsheets`, `check-placement` | `element_lab.py <element>` (one element at every distance, against the exact reference, in seconds) | `audit --theme landscape` |
| Rivers, lakes, water | `check-hydrology` | `lab-pack-water`, far-field lab scenes | `audit --theme landscape` |
| Roads, connections | `check-roadplan`, `check-road-headroom`, `check-reach` | route audit (each road spot from above and the side) | `run --tag feature:road` |
| City and settlement planning | `check-cityplans`, `check-population`, `check-layout` | `towns.py` street-plan maps, city cutaways | `audit --theme towns` |
| Interiors, exteriors, objects | inside cityplans / layout | city cutaways, town scenes | `audit --theme towns` |
| Dungeons | `check-dungeon-stats`, `-identity`, `-reach` | dungeon cutaways | `audit --theme dungeons` |
| Flora, scatter | `check-ecology`, `check-placement` | the atlas gallery, element lab, flora scenes | `audit --theme flora` |
| GPU generator (all stages) | AtlasGenParityTest (`NIRNCRAFT_GEN_PARITY=1`), features parity (atlas_feat_test vs the Java dump) | gen_bake_tool views (saved scene) | `run --changed` |
| Blocks and looks | — | `atlas-gallery` (`--class`), `atlas-gallery-sweep` | `run atlas-gallery --class <c>` |
| Lights, effects | — | lab scenes (`lab-pack-lamps` and others) | `audit --theme lights` / `fx` |
| Loading, cache | `atlas_loadwatch.py --both` | the loading screen | `run atlas-route --relaunch` (milestones only) |

**Gaps (ATLAS-PLAN §4b), each filled only when its component is actively worked on:**
- most component checks test the Java generator; each needs a quick GPU-vs-Java exactness check;
- terrain, rivers and settlement placement need a seconds-long offline visual like towns' plan maps;
- rows above that say "—" have no fast check yet.
