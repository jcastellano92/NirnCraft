# R6 Cyrodiil

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R6-cyrodiil.md` step
> V<n>." Steps V1-V9 as defined in `_province-steps.md`, with the Cyrodiil specifics below.

## Goal

The heart of the Empire at Skyrim quality: Oblivion's city sites, roads and counties kept, the flat green hills
replaced by distinct county characters, every region at the measured Skyrim floor. Cyrodiil is the largest province
(61.2 km²): plan large regions as two hold-sized halves, each with its own seat or town cluster (DESIGN-PRINCIPLES 4.1).

## Context to read

* `docs/world/provinces/cyrodiil.md` (the design; sections 1-10).
* `docs/world/SCALE-AND-GEOGRAPHY.md` 2.2, 3, 5.2 (anchor moves table, relief features, modernisation), 6.2, 6.3, 7, 8.1,
  8.2. `docs/MARQUEE.md` Cyrodiil, `docs/LORE-WEB.md` Cyrodiil arcs.
* `.reference/oblivion_ref` (markers, POIs, road graph, named routes, transform; gitignored, authoring only).

Province `<P>` = `cyrodiil`; regions: jerall_mountains, colovian_highlands, heartlands, great_forest, nibenay_basin,
west_weald, gold_coast, nibenay_valley, blackwood. **Oblivion's positions are canonical** (source precedence).

## Specifics per step

| Step | Cyrodiil specifics | Extra gates |
| --- | --- | --- |
| V1 | Sheet from `cyrodiil.md` 1; the province layer carries the Empire's politics, the Nine Divines, the Ayleid past (L11), the Oblivion Crisis aftermath | as V1 |
| V2 | **Uniform scale** (SCALE 8.1): switch the ingest from the thin-plate warp to the least-squares similarity (1.754 blocks/m, -3.24 degrees); move the 9 anchors per the SCALE 5.2 table (Imperial City 1270, 5540 with Lake Rumare and the isle; Bravil 2350, 7930 with the Niben's middle course; Leyawiin snapped to 3250, 10150); re-ingest every Cyrodiil place through the same similarity; **Topal Bay** (SCALE 8.2: north-west lobe to the Niben mouth, eastern arm into Black Marsh to about 5700, 9900, and in the same edit Soulrest (and Chasepoint) to the new eastern shore at about 6100, 9700 so `layout_lint` stays green; R8 reviews it); relief 34→40 / 114→150 / 2.2→4 (Jerall wall with the Pale Pass notch, the Valus ridge, Colovian plateau with deep stream valleys, Great Forest rolling hills, flat Nibenay basin, braided lower Niben, Blackwood coastal swamp, Gold Coast low cliffs and coves, the Strid valley) | city-to-city distance ratios vs Oblivion within 1.4-1.7x for all pairs (today 1.4-2.4x); Skyrim's Pale Pass road and Helgen unchanged; `TAMRIEL.md` 9 updated |
| V3 | County characters: vineyard terraces (West Weald), open heath (Heartlands), oak-and-beech (Great Forest), mangroves (Blackwood), cliff scrub (Gold Coast), alpine (Jerall); micro-biomes from `cyrodiil.md` 2 | as V3 |
| V4 | Reconcile places after the re-ingest (165/183 Oblivion markers matched today; Daedric shrines live in `tamriel_shrines.json`); Ayleid ruins as the elder-race class; forts per county; unmarked Ayleid wells and sunken ruins | as V4 |
| V5 | Named roads: keep Red Ring, Gold, Black, Silver, Orange, Blue, Green, Yellow, Talos Bridge; add Chorrol-Skingrad (Colovian road) and Bruma-Cheydinhal (Jerall foothill road); the Bruma-Chorrol mountain path as a trail; the Gold Road's inns (Inn of Ill Omen, Brina Cross, Gottshaw) as stops; travel: carriages at a stable outside each of the 8 cities plus the IC waterfront, Niben river boats (IC waterfront - Bravil - Leyawiin), wayshrines as free nodes; cross-province highways to Skyrim (Pale Pass, exists), Morrowind (Cheydinhal-Kragenmoor over the Valus), Black Marsh (Leyawiin-Stormhold through Blackwood), Elsweyr (Bravil or Leyawiin-Rimmen), Valenwood (Skingrad-Arenthia along the Strid), Hammerfell (Kvatch-Rihad over the Dragontail; coordinate with IB9) | a corridor `imperial_city:skingrad` and `bravil:leyawiin` in `full_corridors.ps1`; every Gold Road gap <= 1,000 blocks between stops |
| V6 | The Imperial City as the province's capital at capital-tier floors and beyond (districts: Elven Gardens, Market, Talos Plaza, Temple, Arboretum, Arena, Waterfront; the class gradient from the palace to the Waterfront shacks); the eight county seats as unique plans; Fighters and Mages Guild halls in different cities | F-HUBS >= 2 joinable hubs in different cities |
| V7 | Ayleid ruin kit (wells, varla stones, welkynd glow), Oblivion gate sites (as places; the MS4 event version belongs to R-OPT), forts, the marquee dungeons of `MARQUEE.md` Cyrodiil | one Ayleid marquee per Ayleid-heavy region |
| V8 | Province story crossing the counties; county conflicts (succession, Kvatch rebuilt, Bravil's poverty, Leyawiin's border); the Knights of the Nine and Dark Brotherhood (Cheydinhal) as faction lines; encounters (Imperial Legion patrol, Ayleid treasure hunters, Daedric cultists after the Crisis, a Blackwood ambush); festival on the calendar | as V8 |
| V9 | DESIGN-PRINCIPLES 5.5 for all 9 regions; modernisation table "Oblivion did vs we do" (SCALE 5.2 modernisation lines) | as V9 |

## Merge and report

One merge and commit per step (`_COMMON.md` section 7). V2 and V5 are globally serial; V2 moves anchors, so update
`docs/TAMRIEL.md` section 9 and every reference (places, `quests/places.json`) in the same commit.

**Size:** XL.
