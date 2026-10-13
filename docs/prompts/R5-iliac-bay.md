# R5 High Rock and Hammerfell: the Iliac Bay (IB1-IB8), then the rest of both provinces (IB9)

> "Read `docs/prompts/_COMMON.md` and `docs/prompts/_province-steps.md`, then run `docs/prompts/R5-iliac-bay.md`
> step IB<n>." One step per session is normal; parallel steps are marked.

## Goal

Build southern High Rock and northern Hammerfell from Daggerfall's geography and political map, at Skyrim quality,
exactly as planned in `docs/world/ILIAC-BAY.md`; then bring the rest of both provinces (outside the Daggerfall window)
to the same floor. Legacy quantity is not the goal: 15,251 Daggerfall locations become about 176 curated places.

## Context to read

* `docs/world/ILIAC-BAY.md` (the plan: scale model, realms and fiefs, curation, city plans, roads, dungeons, hooks,
  terrain, phases, owner decisions at the end). Its section 11 is the authority for IB1-IB8 write sets and gates.
* `docs/world/provinces/high_rock.md` and `docs/world/provinces/hammerfell.md` (sheets, micro-biomes, settlements,
  quests per region; the design for IB-S, IB9 and every content step).
* `docs/world/SCALE-AND-GEOGRAPHY.md` 5.4, 5.5 (hooks, southern Hammerfell, Stros M'Kai), 7 (relief targets), 8.5,
  8.6 (Stros M'Kai to Redguard's layout, Gilane).
* `.reference/daggerfall_ref/v2/README.md` (what the extraction holds; gitignored, read at authoring time only).

**Owner decisions (2026-10-12) that bind every step.** (1) Keep-village density: build it, judge in the playtest after
IB5; if crowded, thin keeps by data. (2) Positions from Skyrim, Oblivion and Morrowind are never overridden by
Daggerfall or Arena; Daggerfall may move places only in High Rock, Hammerfell and the Iliac towns; Sentinel's x,
Elinhir and Gilane may move as proposed. (3) `old_orsinium`: move into Daggerfall's Orsinium area only if geography
and lore fit; otherwise keep it and say why (open item O1).

## Steps

Order: IB1 → (IB2 ∥ IB3) → IB-S (as soon as R3a is merged; it may also come first) → (IB4 ∥ IB5 ∥ IB6) → IB7 →
IB8 → IB9 → IB-G. IB1, IB2, IB5 and IB6 are globally
serial with any other V2/V3/V5 step (`_province-steps.md`). IB1-IB3 need only R1 and may run ahead while R3/R4
run (their `why` fields then cite the province documents' section 1 ids); IB-S needs R3a; IB4, IB7 and IB8 need
R3a-R3e merged and R4's pipeline proven.

| Step | = | What | Owns | Gates (beyond `_province-steps.md` for the matching V step) |
| --- | --- | --- | --- | --- |
| **IB-S Realm sheets** | V1 | The 9 realms (and the fief layer as L6/L9 fields) from `high_rock.md` / `hammerfell.md` section 1 into `tamriel/world/high_rock.json` and `hammerfell.json` (the realms are regions there) | `tamriel/world/{high_rock,hammerfell}.json` | `worldsheet_lint` 0 for both provinces |
| **IB1 Frame and vectors** | V2 | The 6.22 blocks/px mapping and the 23.3 degree wedge; trace coast, islands, realm polygons, ranges, areas and rivers with `tools/worldgen/iliac_trace.py`; move Betony; Balfiera east of Menevia; shrink the northern ESO regions; update `TAMRIEL.md` section 9 (Sentinel x, Elinhir, Gilane) | `tools/worldgen/iliac_trace.py` (new), the Iliac part of `tamriel_vectors.json`, regenerated `tamriel/*.png`, `TAMRIEL.md` 9 | ILIAC-BAY 11 IB1: `layout_lint` passes; coast agreement with the warped Daggerfall coast >= 0.90 (`.reference`-only check script); every binding anchor on land in its region; no Daggerfall raster data under `mod/` or `tools/` (size and grep check); Haiku review of `preview_places_high_rock.png` and `preview_places_hammerfell.png` |
| **IB2 Realms, fiefs, biomes** (∥ IB3) | V3 | New realm regions `high_rock/daggerfall`, `high_rock/anticlere`, `high_rock/dwynnen`, `hammerfell/sentinel`, the existing ones re-cut (see the note below the table); `fiefs.json` + `Fiefs.at(x, z)`; new biomes `iliac_scrub`, `iliac_rainforest`, `tigonus_marsh`, `haunted_wood`; palettes; `realm_banner`; MS21 micro rules per realm; lang names | `regions.json` (via `tamriel_map.py`), `tamriel/fiefs.json` (new), `com.skycraft.tamriel.Fiefs` (new), `tools/worldgen/biomes.py`, `palettes.json`, `lang/skycraft/tamriel_regions.json` | Every fief >= 0.01 km² present, area within 10% of `region_table.json`; every palette covers ocean, beach and river; `/skybiome census` shows each realm's signature biome as top lowland pick; F-MICRO per realm; `compileJava` |
| **IB3 Places** (∥ IB2) | V4 | Place draft from `selection.json` (`tools/worldgen/iliac_places.py`, reading `.reference` only at authoring time), hand review, reconcile the 274 in-window places (ILIAC-BAY 5.2), fief keep type (`village` + `variant: "fief_keep"`), `class` and `why` on every place, unmarked sites | `places/high_rock.json`, `places/hammerfell.json`, `places/provinces.json` (moved anchors), `tools/worldgen/iliac_places.py` (new), lang place names | Per realm settlements and dungeons in the 4.4/4.5 bands (documented deviation for fief keeps); spacing; 100% on land; `validate.js` (231 ids); no lore-web place dropped; `parity.py` SET does not regress; decision on `old_orsinium` recorded |
| **IB4 City plans** (∥ IB5, IB6) | V6 | `tools/worldgen/iliac_cityplans.py`: seeds to draft `cityplans/<id>.json` (zoning base, gates, quarters, service counts); hand polish Daggerfall, Sentinel, Wayrest (replace `castleCapital`), Glenpoint and the 6 realm seats (silhouettes in ILIAC-BAY 6.4); `Use.BANK` if taken; populations at the 4.4 tiers with trainers and courts | `cityplans/*.json` (new ids), `tools/worldgen/iliac_cityplans.py`, `settlement/grammar/Use.java` (additive), society data for the bay | `validate_cityplans.js` (unique `sil`); `/skysettle` metrics per tier in band; `town_checks.py`; `render_town.py` sheet passes Haiku as "not a reskin"; 0 unstaffed workplaces; `parity.py --tiers` floors |
| **IB5 Roads** (∥ IB4, IB6) | V5 | Named Iliac roads (ILIAC-BAY 7.2: North Shore, Glenumbra, South Shore with the Bjoulsae bridge, Alik'r Caravan, Abibon Coast, Jungle River, Wrothgar Pass, Dragontail Pass); ferries Daggerfall-Sentinel, Wayrest-Lainlyn, Daggerfall-Betony; Balfiera by boat only; the bay ferry ring in `travel/high_rock.json` and `hammerfell.json`; rebake `roadplan`, `sceneplan` (fief boundary stones and fief-naming crossroads signs) | `roads/named_roads.json`, `tamriel/ports.json`, `travel/{high_rock,hammerfell}.json`, baked files, `full_corridors.ps1` (two corridors) | `roadplan_lint` 0; `reach_lint` 100% of new places; corridors `daggerfall:anticlere` and `sentinel:tigonus` at 0 gaps, 0 unbridged, 0 drops without a step; `placement_lint` densities in target; F-TRAVEL |
| **IB6 Terrain relief** (∥ IB4, IB5) | V2 | Signature landforms per realm (ILIAC-BAY 3 and 10: Daggerfall's sea bluffs and river through the city, Glenpoint's headland, the Dwynnen gorge, the Bjoulsae mouth and channel islands, Wrothgar's arches and passes, Sentinel's harbour cliff, the Alik'r dune sea and wadis, Bangkorai's jungle valleys, the Dragontail black ridges); heights per ILIAC-BAY 10; hydrology rerun; seams at the window edge | ranges, areas, rivers, lakes, peaks in the window in `tamriel_vectors.json`, `hydrology*.py` inputs, regenerated rasters | `hydrology_report.py`: >= 6 named rivers reach the bay, every lake has an outlet; p90 local relief per realm (1 km windows) >= Skyrim's median hold; every realm >= 1 XL landform; Spearman rank correlation with Daggerfall's elevation at 1 km >= 0.6 (`.reference`-only check); road plan still passes after the rebake; no vector gap > 200 blocks at the window edge |
| **IB7 Dungeons** | V7 | Variants per ILIAC-BAY 8.1, marquee data plans for the 9 realms (8.2), new room kits `breton_keep_ruin` and `scorpion_den`, vampire bloodline haunts per fief, covens as faction seats; identity from L10 | `places/*.json` variants, `dungeons/marquee/**` (bay), dungeon kit data, `dungeons/world/*Rooms.java` (kits only; one owner) | `d_identity` = 1.0 for the bay; >= 5 palettes and >= 8 rosters per realm; 9 marquee plans validate; analyzer 0 blockers on one dungeon per new variant; F-LOOP |
| **IB8 Stories and factions** | V8 | Realm storylines (the three crowns interlock; a fief's banner moves with the player's choice), 10 knightly order lines, coven bargains, a bloodline line, the "golem's key" lore-web thread, region voice, encounters (knightly challenge on a bridge, caravan raid, coven circle), wonder moments, reactive lines, boards, the bay festival | `quests/**` (bay realms), `tools/marquee/loreweb.json`, region voice data, `creatures/scenes.json` bay entries, `calendar/festivals.json` entry | `novelty_lint --strict` 0; F-HOOK for every realm; the quest validators and `selftest flow` pass; F-STORY, F-STARTS, F-ENC, F-WONDER, F-REACT per realm |
| **IB9 The rest of both provinces** | V1-V8 | Every region outside the window to the floor: Glenumbra north (Camlorn), Rivenspire (Shornhelm), Bjoulsae (Evermor), Stormhaven north, northern Wrothgar and Orsinium (Farrun), Jehanna; Craglorn, Hews Bane, Stros M'Kai to Redguard's layout (lighthouse to the south point, Bthzark to the western plateau, N'Gasta's tower to an islet off the north-west, palace, observatory, catacombs, causeway), Gilane to the Abecean shore near Hegathe and Taneth (about -8650, 6850), Taneth, Rihad, Skaven, Elinhir; cross-province highways (Markarth-Jehanna or Evermor over the Druadach, Falkreath-Elinhir into Craglorn, Kvatch-Rihad over the Dragontail) | the High Rock and Hammerfell data outside the window, as `_province-steps.md` V1-V8 | every V-step gate for those regions; relief: High Rock 22→35 / 125→160 / 3.4→4.5, Hammerfell 27→35 / 98→150 / 2.5→4.5 |
| **IB-G Province gate** | V9 | DESIGN-PRINCIPLES 5.5 for every region of High Rock and Hammerfell; the playtest judgement on keep-village density (open item O2) | `provinces/{high_rock,hammerfell}.md` section 10 results, WORLD-PLAN | V9 gates for both provinces |

Note on IB2: ILIAC-BAY 4.1's text says "six keep an existing id, three are new", but its table marks four realms
new (daggerfall, anticlere, dwynnen, sentinel) and five existing (wayrest, wrothgar, alikr_desert, bangkorai,
dragontail). The table is the authority; fix the sentence in ILIAC-BAY in the IB2 commit. Ids are never renamed;
polygons may change.

## Verification while working

As `_province-steps.md` for each matching V step. Tracing and curation helpers read `.reference/daggerfall_ref/v2/`
at authoring time only; their outputs are vectors and place JSON at fan-map detail, never rasters or per-pixel values.

## Merge and report

One merge and commit per step (`_COMMON.md` section 7; `full_corridors.ps1` after IB5 and IB6). Each step's report
includes the ILIAC-BAY section 11 gate table with numbers. At IB-G write the results into both province documents'
section 10, the "Daggerfall did vs we do" table (ILIAC-BAY 3 rows), and the owner checklist (sail the bay, ride the
North Shore Road, enter one fief keep, one marquee dungeon).

**Size:** XL (IB-S, IB2, IB3, IB5 about M; IB1, IB4, IB6, IB7, IB8 M-L; IB9 L; IB-G S-M).
