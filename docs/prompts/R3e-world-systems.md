# R3e World systems: travel networks, encounter library, festival calendar, level policy

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R3e-world-systems.md`." Up to two agents: R3e-1 (travel
> networks) and R3e-2 (encounters, festivals, level policy). Disjoint files.

## Goal

The world-level frameworks every province fills with its own content: how people travel (MS11), what happens on the
road (MS14), the calendar (MS16) and how quests scale (MS17). R3e builds the frameworks and proves them on Skyrim;
the province phases (V5, V8) write each province's data.

## Context to read

* `docs/world/DESIGN-PRINCIPLES.md` 0 (7, 8), 3.1 E and F, 3.3 P-J2, P-J3, 4.7, 4.9, 4.11 MS11, MS14, MS16, MS17,
  5.4 F-TRAVEL, F-ENC, F-WONDER, F-QUIET, F-LEVEL.
* `docs/world/SCALE-AND-GEOGRAPHY.md` section 6 (travel per province), `docs/ECOLOGY.md` (quiet roads, encounters,
  road300), `docs/FIRST-PLAYTEST.md` section 4 (fast-travel prewarm).
* Code: `roads/Carriages.java`, `creatures/travel/StriderPorts.java`, `sailing/**` (ports and ferries),
  `creatures/spawn/WildScenes.java`, `society/Encounters.java`, `creatures/Leveling.java`,
  `resources/skycraft/creatures/scenes.json`, `resources/skycraft/tamriel/ports.json`.

## Write sets

* **R3e-1 travel:** a new `mod/src/main/java/com/skycraft/travel/**` package, `roads/Carriages.java`,
  `creatures/travel/**`, `sailing/**` (route offers only), `resources/skycraft/travel/**` (new),
  `resources/skycraft/tamriel/ports.json` (Skyrim routes), `lang/skycraft/travel.json` (new keys).
* **R3e-2 encounters, calendar, levels:** `creatures/spawn/WildScenes.java`, `society/Encounters.java`,
  `resources/skycraft/creatures/scenes.json`, a new `calendar` package and `resources/skycraft/calendar/**`,
  `creatures/Leveling.java` (story clamp only), `quest/**` (level read for story quests only), `lang` own keys,
  `tools/qa/ecology_report.py` (encounter counts).

## R3e-1 steps (MS11)

1. **Format.** `travel/<province>.json`: `modes` (carriage, strider, boat, river_boat, barge, caravan, guild_guide,
   lift, propylon, intervention, wayshrine), `nodes` (place id or position, mode, discovered-on-arrival flag), `routes`
   (from, to, mode, fee, duration, the road or water path it follows). Routes are restricted to real links; no
   all-to-all unless the mode is all-to-all in lore (guild guides).
2. **Engine.** One `TravelNetworks` service that drives the existing carriages, striders and ferries from the data
   (keep their visuals and the "Travelling to..." prewarm); new modes reuse the nearest existing mechanic (river boat
   and barge reuse ferries on a river route; caravan reuses carriages with traders; guild guide and propylon are
   paid teleports with a short scene; lift is a short ride). Server-authoritative, multiplayer safe.
3. **Wilderness nodes.** Shrines and standing stones become free nodes (discovered on visit, refresh a minor blessing),
   plus a hashed fill so no land point is more than 800 blocks from a node (p90 <= 500).
4. **Skyrim data.** Carriages between the hold capitals (exists, move to data), ships from Solitude, Dawnstar,
   Winterhold, Windhelm and the Raven Rock ferry, standing stones and shrines as nodes.

## R3e-2 steps (MS14, MS16, MS17)

1. **Encounter library.** Grow `scenes.json` from 6 kinds to >= 40, region-keyed (`regions`, `provinces`, `cultures`),
   with a `wonder` tag for wonder moments; one roll per player every 15-25 minutes, daylight-biased, on roads and wild
   spots; some kinds start a side quest (one-shot). Skyrim gets >= 8 kinds per hold and >= 3 wonders per hold (aurora
   night, mammoth migration at dawn, dragon on a distant peak, storm over the Sea of Ghosts, a lantern procession...).
   Every kind has `purpose`, `needs` and `why` (sheet cites).
2. **Festival calendar.** `calendar/festivals.json` (Tamriel's calendar dates, province, regions, decorations overlay
   kinds from the scene plan, stalls, festival quests); on the day the overlay appears, a market adds stalls and
   festival quests are offered. One per Skyrim hold.
3. **Level policy.** Story quests read the player level clamped to the region band +/- 8 (`Leveling.regionLevel`);
   delves follow the band; the policy is one function the quest spawner calls.

## Verification while working

Dev server (`/skycraft-travel list <node>`, `/skycraft-scenes roll <kind>` or equivalents you add), `ecology_report.py`,
`flow_audit.py` (F-TRAVEL, F-ENC, F-WONDER once R3c is merged), `qa.ps1 -Server` selftests.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Travel coverage (Skyrim) | F-TRAVEL | p90 <= 500, max <= 800 blocks to a node |
| Travel modes (Skyrim) | travel data | >= 2 beyond walking and horse |
| Routes real | `travel` selftest | every route's ends exist; no unintended all-to-all |
| Encounters | F-ENC | >= 40 kinds total, >= 8 per Skyrim hold |
| Wonder | F-WONDER | >= 3 per Skyrim hold |
| Quiet kept | `ecology_report.py` road300 | <= 5% day, <= 20% night |
| Festivals | calendar data | 1 per Skyrim hold, overlay renders on the dev server |
| Levels | F-LEVEL | band <= 20; story quests within +/- 8 |
| QA | `qa.ps1 -Server -Rebake` | PASS |

## Merge and report

Merge R3e-1 then R3e-2. Update WORLD-PLAN. Report per `_COMMON.md` section 8.

**Size:** L.
