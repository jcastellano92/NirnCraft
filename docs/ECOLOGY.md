# Skyrim wilderness ecology

Goal (from the first playtest, "necromancer then spider within two seconds"): enemies that make sense for where you
are, quiet stretches, moments of life, and scaling by hold and distance, instead of a constant stream of attacks.
Pacing (calm zones, cooldowns, grace period) already existed; this pass makes the *tables* ecological and adds the
missing hooks. Everything is data except the hooks listed at the end.

## What a player should meet

| Where | Passive life | Predators | People | The dead | Rare |
| --- | --- | --- | --- | --- | --- |
| Tundra (Whiterun, Pale heath, Hjaalmarch edges) | deer herds (most at dawn), rabbits, elk, foxes, wild horses, mammoths; cows, sheep and chickens only near farms and towns | wolves (a pair by day, packs at night), the odd bear or sabre cat at level 6+ | bandit ambushes only on roads far from any town | draugr and necromancers at night, only near barrows, forts and ruins | giant with mammoths, level 12+, far from towns |
| Pine forest (Falkreath, Haafingar) | deer, rabbits, elk, foxes at dusk, goats on the ridges | bears by day, wolves at night, sabre cats | road bandits (rare) | Falkreath's graveyard overlay: more draugr, necromancers, vampires near ruins at night | spriggans in rain, wisps at night (16+) |
| Snowy pine (Pale, Eastmarch, Hjaalmarch) | deer, elk, snow rabbits, foxes | wolves, bears, sabre cats, trolls above y 140 (14+) | road bandits | as above | wisps |
| Autumn forest (Rift) | the richest herds: deer, elk, rabbits, horses, cows and sheep near farms | gentle wolves, bears, sabre cats | the Rift's road bandits and a chief at 12+ | as above | spriggans in rain |
| Reach crags | goats (the hills' main herd), deer, rabbits | wolves, bears, sabre cats, trolls above y 110 | Forsworn wanderers by day and dusk (camps hold the rest), hagraven only at lairs and landmarks, above y 120 | as above | spriggans in rain |
| Marsh (Hjaalmarch) | mudcrabs, deer, rabbits, horkers | frostbite spiders (heavy at night), skeevers, chaurus (14+) | road bandits | as above | wisps, hagravens near landmarks |
| Volcanic tundra (Eastmarch) | rabbits, goats, deer, mammoths | wolves, sabre cats, trolls (14+, y 120+) | road bandits | as above | gargoyles near ruins (22+) |
| Hot springs (Eastmarch) | the quietest place in the north: deer, rabbits, elk, foxes, goats, horses | almost none | none | none | none |
| Glacier and snowfield (Winterhold, Pale) | horkers, mudcrabs, snow rabbits, foxes, mammoths | wolves, ice wraiths (12+), trolls, sabre cats | road bandits | draugr near ruins | frost giants (18+), wisps |
| Mountains and highlands | goats above y 105, elk, mammoths | trolls above y 145, ice wraiths above y 150 (230 for the peaks), bears, sabre cats | none | draugr near ruins | giant with mammoths on the shoulders (y 110-200) |
| Coast, rivers, lakes | mudcrabs, horkers, slaughterfish, deer at dawn | bear fishing the rapids (day), wolves at dusk | smugglers (6+) on empty shores | draugr near forts | none |
| Caves, tombs, mines | bats | skeevers, spiders, draugr, falmer, trolls, bears, necromancers, vampires, dwarven machines | bandits | draugr, deathlords (30+) | dragon priest (32+) |

Hold overlays: **Whiterun** carries extra quiet weight (gentle) and its level band never reaches trolls or giants.
**The Pale, Winterhold and the Reach** gain trolls and ice wraiths from level 10 and frost giants from 16 (harder).
Level bands come from `Leveling.regionLevel` (hold range x distance from the capital, capped near the world spawn),
so the same table is gentle in Whiterun (1-10) and fierce in Winterhold (10-30) without hold-specific copies.

## Peace and wonder

* **Quiet entries.** A group with `"quiet": true` is a "nothing happens" slot in the weighted pick. Tables carry
  the quiet weight by time of day (day 25-80, dawn, dusk, night 8-30). An attempt that lands on it ends silently and
  does not start the hostile cooldown. This is the data knob for "mostly nothing happens by day".
* **Quiet roads.** Walking on or within 10 blocks of a road multiplies hostile attempts by `roadPlayerFactor` (0.01);
  the spot itself on a road keeps the old `roadHostileFactor` (0.08). The report's "road300" column is the modelled
  chance of meeting any hostile group on a 300-block walk of road.
* **Ambient life.** Herds (1-4 animals, more at dawn and dusk), farm animals near towns, horses, goat herds on the
  crags, foxes at the edges of the day, bats in caves; none of it counts against the hostile budget.
* **Wolves are a night thing.** One pair by day, packs at night, a few at dusk. Spiders are weather-driven (rain) in
  forests and constant in the marsh. The dead only appear at night and only near `dungeon_fixed`, `fort` and
  `landmark` places. Humanoid threats are placed at camps (`Camps`, `BanditCampFeature`); random spawns of them are
  rare: bandits only on roads far from any settlement (`road: on`, `townMin: 300`), Forsworn only far from towns.

## Encounters (`skycraft/creatures/scenes.json`, `WildScenes`)

Small, low-frequency, daylight-biased scenes built from existing NPC roles and creatures. One roll per player every
15-25 minutes (first one after 4-8), skipped 70% of the time at night, never while another scene or a society
encounter is near; hostile scenes use the shared hostile cooldown, calm zones and start-area ban.

| Scene | What happens |
| --- | --- |
| `caravan_under_attack` | on a road, far from towns: a caravan trader and mule held by 2-3 wolves, a hired sword fights back |
| `hunters_tracking_elk` | one or two hunters stalk an elk (forest, plains, tundra) |
| `lone_pilgrim` | a priest walks past you |
| `bandits_arguing` | two thugs fight each other for 25 seconds, then turn on whoever is there |
| `hunters_camp` | a campfire (removed when the scene ends), one or two hunters and a horse |
| `wolves_on_a_kill` | a wolf pack on a deer, away from roads |

Faction interplay (`relations.json`): NPC hunters now hunt prey (0.4) and predators (0.3) on sight, predators may
turn on hunters (0.2); the existing bandit/Forsworn/necromancer/Legion/Stormcloak wars apply to everything the
scenes and camps place.

## Metrics

`py -3 -I tools/qa/ecology_report.py [--province skyrim] [--md] [--json]` reads the spawn rules and, for Skyrim,
each hold's biome palette from `tools/worldgen/biomes.py`, and prints per hold x ecology family: distinct kinds per
category (passive, predator, vermin, humanoid, undead, rare), the share of hostile attempts that become a hostile
group by day and by night, the humanoid share by day and undead share at night, the level band the hostile groups
cover, the modelled 300-block road walk risk, and cave kinds. "Before" was run with
`--road-player-factor 1.0` (the old behaviour had no player-on-road rule) against the pre-change `skyrim.json`.

Reading the tables: *hostile day/night* is the weight of hostile groups divided by hostile plus quiet weight in that
bucket (before: no quiet entries, so every attempt that found a candidate spawned). Actual encounter rate is that
share times the config gates (daylight factor 0.5, 100 s cooldown, one group near a player, grace period).
Dawn and dusk slices are weighted at a third in the day bucket.

Summary:

| | Before | After |
| --- | --- | --- |
| Distinct kinds in Skyrim surface tables | 26 | 34 |
| Mean hostile share, day | 98% | 16% |
| Mean hostile share, night | 98% | 47% |
| 300-block road walk meets a hostile group, day / night | 98% / 98% | 3% / 15% |
| Fewest passive kinds in any hold x family | 0 (Eastmarch volcanic tundra had no table at all) | 3 |
| Families with no spawn table | Eastmarch volcanic tundra | none |
| Hostile humanoids by day (mean share of attempts) | 17-33% | 0-7% |

## Hooks added (Java)

All optional JSON fields; existing tables are unaffected.

* `SpawnTables.Where` on every group: `minY`/`maxY` (surface height band), `weather` (clear, rain, storm),
  `road` (on, off), `townMin`/`townMax` (blocks from the nearest settlement), `nearPlace`/`nearDist` (within reach of
  a place type), `tag` (label for tools). `time` also takes `dawn` and `dusk`. `quiet: true` groups.
* `RegionSpawner`: applies the above; hostile attempts are also scaled by `CreaturesConfig.ROAD_PLAYER_FACTOR`
  (`roadPlayerFactor`) when the player stands on a road.
* `SpawnPacing.settlementWithin`.
* `WildScenes` + `skycraft/creatures/scenes.json` (new): the scene table above.

Not done / for later: no bird species exist (no flocks); hunters' camps place only a campfire (no tents); the
per-player scene cooldown is in the data file, not a config option; the other provinces still use the old tables.

## Before and after tables

Columns: passive/predator/vermin/humanoid/undead/rare are distinct kinds; hostile day/night is the hostile share;
hum%d is the humanoid share of day attempts, dead%n the undead share of night attempts; hostile lv the level band
reached; road300 the modelled chance of a hostile group on a 300-block road walk (day/night).

### Before

| region | family | lv region | passive | predator | vermin | humanoid | undead | rare | hostile day | hostile night | hum%d | dead%n | hostile lv | road300 d/n | cave kinds |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whiterun | tundra | 1-10 | 9 | 3 | 1 | 2 | 1 | 0 | 8% | 37% | 2% | 11% | 1-10 | 76%/100% | 10 |
| whiterun | pine_forest | 1-10 | 6 | 3 | 1 | 2 | 1 | 1 | 17% | 48% | 1% | 10% | 1-10 | 95%/100% | 10 |
| whiterun | mountain | 1-10 | 4 | 3 | 1 | 1 | 1 | 0 | 12% | 47% | 0% | 9% | 1-10 | 88%/100% | 10 |
| whiterun | coast | 1-10 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 11% | 2% | 7% | 3-10 | 30%/98% | 10 |
| whiterun | water | 1-10 | 3 | 2 | 0 | 0 | 0 | 0 | 2% | 0% | 0% | 0% | 3-10 | 30%/0% | 10 |
| falkreath | pine_forest | 5-22 | 6 | 4 | 1 | 2 | 2 | 3 | 24% | 73% | 2% | 27% | 5-22 | 99%/100% | 17 |
| falkreath | mountain | 5-22 | 4 | 5 | 1 | 1 | 1 | 3 | 32% | 77% | 0% | 9% | 5-22 | 100%/100% | 17 |
| falkreath | coast | 5-22 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 5-22 | 30%/100% | 17 |
| falkreath | water | 5-22 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 5-22 | 41%/0% | 17 |
| the_rift | tundra | 6-26 | 9 | 4 | 1 | 2 | 2 | 1 | 11% | 52% | 2% | 17% | 6-26 | 86%/100% | 17 |
| the_rift | pine_forest | 6-26 | 6 | 4 | 1 | 2 | 2 | 3 | 22% | 65% | 2% | 15% | 6-26 | 98%/100% | 17 |
| the_rift | autumn_forest | 6-26 | 8 | 4 | 1 | 3 | 2 | 1 | 18% | 56% | 6% | 19% | 6-26 | 96%/100% | 17 |
| the_rift | mountain | 6-26 | 4 | 5 | 1 | 1 | 1 | 3 | 29% | 77% | 0% | 9% | 6-26 | 100%/100% | 17 |
| the_rift | coast | 6-26 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 6-26 | 30%/100% | 17 |
| the_rift | water | 6-26 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 6-26 | 41%/0% | 17 |
| eastmarch | snowy_pine | 8-28 | 5 | 5 | 1 | 2 | 2 | 2 | 22% | 66% | 2% | 14% | 8-28 | 98%/100% | 17 |
| eastmarch | volcanic_tundra | 8-28 | 5 | 5 | 1 | 2 | 2 | 1 | 17% | 68% | 3% | 16% | 8-28 | 95%/100% | 17 |
| eastmarch | hot_springs | 8-28 | 7 | 2 | 1 | 0 | 0 | 0 | 1% | 17% | 0% | 0% | 8-28 | 16%/100% | 17 |
| eastmarch | mountain | 8-28 | 4 | 5 | 1 | 1 | 1 | 3 | 29% | 77% | 0% | 9% | 8-28 | 100%/100% | 17 |
| eastmarch | coast | 8-28 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 8-28 | 30%/100% | 17 |
| eastmarch | water | 8-28 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 8-28 | 41%/0% | 17 |
| the_pale | snowy_pine | 6-26 | 5 | 6 | 1 | 2 | 2 | 3 | 26% | 70% | 2% | 12% | 6-26 | 99%/100% | 17 |
| the_pale | glacier | 6-26 | 6 | 6 | 0 | 2 | 2 | 3 | 30% | 76% | 3% | 11% | 6-26 | 100%/100% | 17 |
| the_pale | mountain | 6-26 | 4 | 5 | 1 | 1 | 1 | 4 | 34% | 80% | 0% | 7% | 6-26 | 100%/100% | 17 |
| the_pale | coast | 6-26 | 4 | 3 | 0 | 1 | 1 | 1 | 8% | 36% | 2% | 9% | 6-26 | 76%/100% | 17 |
| the_pale | water | 6-26 | 3 | 4 | 0 | 0 | 0 | 1 | 9% | 25% | 0% | 0% | 6-26 | 80%/100% | 17 |
| winterhold | glacier | 10-30 | 6 | 6 | 0 | 2 | 2 | 3 | 30% | 76% | 3% | 11% | 10-30 | 100%/100% | 18 |
| winterhold | mountain | 10-30 | 4 | 5 | 1 | 1 | 1 | 4 | 34% | 80% | 0% | 7% | 10-30 | 100%/100% | 18 |
| winterhold | coast | 10-30 | 4 | 3 | 0 | 1 | 1 | 1 | 8% | 36% | 2% | 9% | 10-30 | 76%/100% | 18 |
| winterhold | water | 10-30 | 3 | 4 | 0 | 0 | 0 | 1 | 9% | 25% | 0% | 0% | 10-30 | 80%/100% | 18 |
| hjaalmarch | tundra | 5-24 | 9 | 4 | 1 | 2 | 2 | 1 | 13% | 52% | 2% | 17% | 5-24 | 90%/100% | 17 |
| hjaalmarch | snowy_pine | 5-24 | 5 | 5 | 1 | 2 | 2 | 2 | 25% | 66% | 2% | 14% | 5-24 | 99%/100% | 17 |
| hjaalmarch | marsh | 5-24 | 4 | 3 | 3 | 3 | 2 | 2 | 22% | 78% | 3% | 14% | 5-24 | 98%/100% | 17 |
| hjaalmarch | mountain | 5-24 | 4 | 5 | 1 | 1 | 1 | 3 | 32% | 77% | 0% | 9% | 5-24 | 100%/100% | 17 |
| hjaalmarch | coast | 5-24 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 5-24 | 30%/100% | 17 |
| hjaalmarch | water | 5-24 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 5-24 | 41%/0% | 17 |
| haafingar | pine_forest | 5-24 | 6 | 4 | 1 | 2 | 2 | 3 | 18% | 65% | 2% | 15% | 5-24 | 96%/100% | 17 |
| haafingar | snowy_pine | 5-24 | 5 | 5 | 1 | 2 | 2 | 2 | 19% | 66% | 2% | 14% | 5-24 | 97%/100% | 17 |
| haafingar | marsh | 5-24 | 4 | 3 | 3 | 3 | 2 | 2 | 22% | 78% | 3% | 14% | 5-24 | 98%/100% | 17 |
| haafingar | mountain | 5-24 | 4 | 5 | 1 | 1 | 1 | 3 | 22% | 77% | 0% | 9% | 5-24 | 98%/100% | 17 |
| haafingar | coast | 5-24 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 5-24 | 30%/100% | 17 |
| haafingar | water | 5-24 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 5-24 | 41%/0% | 17 |
| the_reach | reach_crags | 8-30 | 5 | 6 | 1 | 3 | 2 | 2 | 36% | 77% | 7% | 14% | 8-30 | 100%/100% | 18 |
| the_reach | mountain | 8-30 | 4 | 5 | 1 | 1 | 1 | 4 | 34% | 80% | 0% | 7% | 8-30 | 100%/100% | 18 |
| the_reach | coast | 8-30 | 4 | 3 | 0 | 1 | 1 | 1 | 8% | 36% | 2% | 9% | 8-30 | 76%/100% | 18 |
| the_reach | water | 8-30 | 3 | 4 | 0 | 0 | 0 | 1 | 9% | 25% | 0% | 0% | 8-30 | 80%/100% | 18 |

rows 46 | distinct kinds 34 | mean hostile share day 16% night 47% | 300-block road walk day 76% night 87% | min wildlife kinds 3

### After

| region | family | lv region | passive | predator | vermin | humanoid | undead | rare | hostile day | hostile night | hum%d | dead%n | hostile lv | road300 d/n | cave kinds |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whiterun | tundra | 1-10 | 9 | 3 | 1 | 2 | 1 | 0 | 8% | 37% | 2% | 11% | 1-10 | 1%/12% | 10 |
| whiterun | pine_forest | 1-10 | 6 | 3 | 1 | 2 | 1 | 1 | 17% | 48% | 1% | 10% | 1-10 | 3%/15% | 10 |
| whiterun | mountain | 1-10 | 4 | 3 | 1 | 1 | 1 | 0 | 12% | 47% | 0% | 9% | 1-10 | 2%/15% | 10 |
| whiterun | coast | 1-10 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 11% | 2% | 7% | 3-10 | 0%/4% | 10 |
| whiterun | water | 1-10 | 3 | 2 | 0 | 0 | 0 | 0 | 2% | 0% | 0% | 0% | 3-10 | 0%/0% | 10 |
| falkreath | pine_forest | 5-22 | 6 | 4 | 1 | 2 | 2 | 3 | 24% | 73% | 2% | 27% | 5-22 | 4%/23% | 17 |
| falkreath | mountain | 5-22 | 4 | 5 | 1 | 1 | 1 | 3 | 32% | 77% | 0% | 9% | 5-22 | 5%/24% | 17 |
| falkreath | coast | 5-22 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 5-22 | 0%/6% | 17 |
| falkreath | water | 5-22 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 5-22 | 1%/0% | 17 |
| the_rift | tundra | 6-26 | 9 | 4 | 1 | 2 | 2 | 1 | 11% | 52% | 2% | 17% | 6-26 | 2%/17% | 17 |
| the_rift | pine_forest | 6-26 | 6 | 4 | 1 | 2 | 2 | 3 | 22% | 65% | 2% | 15% | 6-26 | 4%/20% | 17 |
| the_rift | autumn_forest | 6-26 | 8 | 4 | 1 | 3 | 2 | 1 | 18% | 56% | 6% | 19% | 6-26 | 3%/18% | 17 |
| the_rift | mountain | 6-26 | 4 | 5 | 1 | 1 | 1 | 3 | 29% | 77% | 0% | 9% | 6-26 | 5%/24% | 17 |
| the_rift | coast | 6-26 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 6-26 | 0%/6% | 17 |
| the_rift | water | 6-26 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 6-26 | 1%/0% | 17 |
| eastmarch | snowy_pine | 8-28 | 5 | 5 | 1 | 2 | 2 | 2 | 22% | 66% | 2% | 14% | 8-28 | 4%/21% | 17 |
| eastmarch | volcanic_tundra | 8-28 | 5 | 5 | 1 | 2 | 2 | 1 | 17% | 68% | 3% | 16% | 8-28 | 3%/21% | 17 |
| eastmarch | hot_springs | 8-28 | 7 | 2 | 1 | 0 | 0 | 0 | 1% | 17% | 0% | 0% | 8-28 | 0%/6% | 17 |
| eastmarch | mountain | 8-28 | 4 | 5 | 1 | 1 | 1 | 3 | 29% | 77% | 0% | 9% | 8-28 | 5%/24% | 17 |
| eastmarch | coast | 8-28 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 8-28 | 0%/6% | 17 |
| eastmarch | water | 8-28 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 8-28 | 1%/0% | 17 |
| the_pale | snowy_pine | 6-26 | 5 | 6 | 1 | 2 | 2 | 3 | 26% | 70% | 2% | 12% | 6-26 | 4%/22% | 17 |
| the_pale | glacier | 6-26 | 6 | 6 | 0 | 2 | 2 | 3 | 30% | 76% | 3% | 11% | 6-26 | 5%/23% | 17 |
| the_pale | mountain | 6-26 | 4 | 5 | 1 | 1 | 1 | 4 | 34% | 80% | 0% | 7% | 6-26 | 6%/24% | 17 |
| the_pale | coast | 6-26 | 4 | 3 | 0 | 1 | 1 | 1 | 8% | 36% | 2% | 9% | 6-26 | 1%/12% | 17 |
| the_pale | water | 6-26 | 3 | 4 | 0 | 0 | 0 | 1 | 9% | 25% | 0% | 0% | 6-26 | 2%/8% | 17 |
| winterhold | glacier | 10-30 | 6 | 6 | 0 | 2 | 2 | 3 | 30% | 76% | 3% | 11% | 10-30 | 5%/23% | 18 |
| winterhold | mountain | 10-30 | 4 | 5 | 1 | 1 | 1 | 4 | 34% | 80% | 0% | 7% | 10-30 | 6%/24% | 18 |
| winterhold | coast | 10-30 | 4 | 3 | 0 | 1 | 1 | 1 | 8% | 36% | 2% | 9% | 10-30 | 1%/12% | 18 |
| winterhold | water | 10-30 | 3 | 4 | 0 | 0 | 0 | 1 | 9% | 25% | 0% | 0% | 10-30 | 2%/8% | 18 |
| hjaalmarch | tundra | 5-24 | 9 | 4 | 1 | 2 | 2 | 1 | 13% | 52% | 2% | 17% | 5-24 | 2%/17% | 17 |
| hjaalmarch | snowy_pine | 5-24 | 5 | 5 | 1 | 2 | 2 | 2 | 25% | 66% | 2% | 14% | 5-24 | 4%/21% | 17 |
| hjaalmarch | marsh | 5-24 | 4 | 3 | 3 | 3 | 2 | 2 | 22% | 78% | 3% | 14% | 5-24 | 4%/24% | 17 |
| hjaalmarch | mountain | 5-24 | 4 | 5 | 1 | 1 | 1 | 3 | 32% | 77% | 0% | 9% | 5-24 | 5%/24% | 17 |
| hjaalmarch | coast | 5-24 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 5-24 | 0%/6% | 17 |
| hjaalmarch | water | 5-24 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 5-24 | 1%/0% | 17 |
| haafingar | pine_forest | 5-24 | 6 | 4 | 1 | 2 | 2 | 3 | 18% | 65% | 2% | 15% | 5-24 | 3%/20% | 17 |
| haafingar | snowy_pine | 5-24 | 5 | 5 | 1 | 2 | 2 | 2 | 19% | 66% | 2% | 14% | 5-24 | 3%/21% | 17 |
| haafingar | marsh | 5-24 | 4 | 3 | 3 | 3 | 2 | 2 | 22% | 78% | 3% | 14% | 5-24 | 4%/24% | 17 |
| haafingar | mountain | 5-24 | 4 | 5 | 1 | 1 | 1 | 3 | 22% | 77% | 0% | 9% | 5-24 | 4%/24% | 17 |
| haafingar | coast | 5-24 | 4 | 1 | 0 | 1 | 1 | 0 | 2% | 18% | 2% | 12% | 5-24 | 0%/6% | 17 |
| haafingar | water | 5-24 | 3 | 2 | 0 | 0 | 0 | 0 | 3% | 0% | 0% | 0% | 5-24 | 1%/0% | 17 |
| the_reach | reach_crags | 8-30 | 5 | 6 | 1 | 3 | 2 | 2 | 36% | 77% | 7% | 14% | 8-30 | 6%/24% | 18 |
| the_reach | mountain | 8-30 | 4 | 5 | 1 | 1 | 1 | 4 | 34% | 80% | 0% | 7% | 8-30 | 6%/24% | 18 |
| the_reach | coast | 8-30 | 4 | 3 | 0 | 1 | 1 | 1 | 8% | 36% | 2% | 9% | 8-30 | 1%/12% | 18 |
| the_reach | water | 8-30 | 3 | 4 | 0 | 0 | 0 | 1 | 9% | 25% | 0% | 0% | 8-30 | 2%/8% | 18 |

rows 46 | distinct kinds 34 | mean hostile share day 16% night 47% | 300-block road walk day 3% night 15% | min wildlife kinds 3
