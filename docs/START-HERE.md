# Start here

**If you are a new session, read this first.** It takes five minutes and tells you what Skycraft is, where everything
lives, where the work stands, what to run next and the rules you must keep.

## What the project is

Skycraft is a Skyrim total conversion for Minecraft 1.20.1 Forge (Java 17, official Mojang mappings), being grown into
the whole of Tamriel and the lands around it: its own terrain, biomes, roads, towns, NPCs, dungeons, creatures, quests,
magic, music and UI, with no third-party content mods (performance and library mods are fine). It ships as a Prism
Launcher instance. The owner's goal: every province as full, unique and purposeful as Skyrim, **measured** against
the real game, built from the ground up so that everything in a place is explained by its geography, history and
people ("world building is king").

## Where things are

| What | Where |
| --- | --- |
| The mod (Java, resources, lang) | `mod/` (`mod/src/main/java/com/skycraft/<module>`, `mod/src/main/resources/skycraft/`) |
| Generators and QA tools | `tools/` (`tools/qa/qa.ps1` is the harness; `tools/worldgen/`, `tools/marquee/`, `tools/bake/`, `tools/dev/`) |
| **The master plan and roadmap** | [`docs/world/WORLD-PLAN.md`](world/WORLD-PLAN.md) (state table in section 7) |
| **The rulebook** | [`docs/world/DESIGN-PRINCIPLES.md`](world/DESIGN-PRINCIPLES.md) (world building first, the 11-layer sheet, the measured Skyrim floor) |
| Per-province designs | `docs/world/provinces/<province>.md` (ten sections each), `docs/world/ILIAC-BAY.md`, `docs/world/SCALE-AND-GEOGRAPHY.md` |
| **Ready-to-run phase prompts** | [`docs/prompts/`](prompts/README.md) (`_COMMON.md` first) |
| Agent rules (build, code, legal, world building, fast loop) | [`docs/AGENT-RULES.md`](AGENT-RULES.md) |
| QA harness and verification strategy | [`docs/FINISH-PLAN.md`](FINISH-PLAN.md) sections 0, 2, 3 |
| Generation pipeline (plan-first, P1-P7, fast loop) | [`docs/WORLDGEN-PIPELINE.md`](WORLDGEN-PIPELINE.md) |
| Scorecard against Skyrim | [`docs/PARITY.md`](PARITY.md) (`py -3 -I tools/qa/parity.py`) |
| Places that must say "wow", mysteries | [`docs/MARQUEE.md`](MARQUEE.md), [`docs/LORE-WEB.md`](LORE-WEB.md) |
| Audits | `docs/SKYRIM-SWEEP.md`, `docs/DUNGEON-AUDIT.md`, `docs/ECOLOGY.md`, `docs/REGION-AUDIT.md`, `docs/TOWN-AUDIT.md` |
| Player docs | `README.md`, `docs/FEATURES.md`, `docs/WORLD.md`, `docs/INSTALL.md`, `docs/CONTROLS.md` |
| Architecture and harnesses | `docs/DEVELOPING.md`, `docs/TAMRIEL.md` (world contracts, binding anchors in section 9) |
| Reference data from the owner's game installs | `.reference/` (gitignored; facts only; never committed) |
| Release output | `dist/` (built by `tools/build_release.ps1`; not committed) |

## Current state (2026-10-14)

* Branch `tamriel-overhaul`, local only. The whole continent exists: 71 land regions, 4,914 places, 113+ biomes,
  settlements with a building grammar, dungeons with identity in Skyrim, 1,072 quests, NPCs with homes, jobs and
  schedules, creatures, magic, sailing, realms, music. `qa.ps1` passes (`OVERALL PASS`).
* The plan-first generation pipeline is complete: P1 road plan, P2 terrain obeys it, P3 roads and crossings painted
  from it, P4 vegetation masks, **P5 streets to every door (`e9a5ebe`, 0.38% unreachable doors)**, P6 planned scenes.
  Since then: fire system and the destroyed-Helgen aftermath (`4ba41bb`), playtest rounds 4 and 5 (`15e46d7`,
  `b738492`), landmark grounding and story lecterns (`7af4469`), and **playtest round 6 & multiplayer/gameplay
  overhaul** (jail/party fast-travel & release grace, Sanies Lupinus & level-gated werewolves, crouch trap disarm &
  filled dispensers, vanilla spawner strip, NPC busy cleanup, L/R hand dual-wield/spell/bound/favorite equip, Skyrim
  XP curve & party XP/HUD bar, Helgen aftermath & contextual dialogue, weapon poisons & HUD meter, ally/companion
  healing spells & staves, party inn booking & bed ownership, book-to-inventory state return & readable Journey book,
  5-min per-item evidence decay, 48h merchant rotation & buyback decay). Release builds are deployed to Prism and
  `dist/Skycraft-client-instance.zip`.
* **All design and province planning documents are 100% complete:** the rulebook (`DESIGN-PRINCIPLES.md`), the scale
  model (`SCALE-AND-GEOGRAPHY.md`), the Iliac Bay plan (`ILIAC-BAY.md`), the master roadmap (`WORLD-PLAN.md`), the
  prompt library (`docs/prompts/`, including `R4a-town-overhaul.md`), and **all 11 province/island/expedition documents
  in `docs/world/provinces/*.md` (`skyrim.md`, `high_rock.md`, `hammerfell.md`, `cyrodiil.md`, `morrowind.md`,
  `solstheim.md`, `black_marsh.md`, `elsweyr.md`, `valenwood.md`, `summerset.md`, `foreign_lands.md`), each with all
  ten sections (§1–§10) written**.
* **Skyrim Uplift (`R4`) is complete:** Skyrim now scores `108` mean across all 11 parity categories (`SET = 119`,
  `NPC = 143`, `QST = 110`, `DUN = 100`, `CRE = 100`, `FLO = 100`, `STY = 115`, `LND = 100`, `LOR = 100`, `MUS = 100`,
  `ITM = 100`, with 0 hold cells below 80 and `SET >= 103` in every hold) and passes 27/27 FLW metrics across all 9
  holds (`flow_audit.py --province skyrim`). **R4a (`docs/prompts/R4a-town-overhaul.md`)**, **R1
  (`docs/prompts/R1-loose-ends.md`)**, **R2 (`docs/prompts/R2-testing-tiers.md`)**, **R3a
  (`docs/prompts/R3a-world-sheets.md`)**, **R3b (`docs/prompts/R3b-micro-biomes.md`)**, **R3c
  (`docs/prompts/R3c-measurement.md`)**, **R3d (`docs/prompts/R3d-town-services.md`)**, **R3e
  (`docs/prompts/R3e-world-systems.md`)**, and **R4 (`docs/prompts/R4-skyrim-uplift.md`)** are all complete, rebaked,
  and verified (`qa.ps1 -Server -Rebake` + `qa.ps1 -Unit -Flow` OVERALL PASS). Next up is R5 Iliac Bay (`IB-S`,
  `IB1-IB9`, `IB-G`).

## Next

**Next: R5 Iliac Bay (`docs/prompts/R5-iliac-bay.md`, steps `IB-S`, `IB1-IB9`, `IB-G` in `docs/world/ILIAC-BAY.md`,
`docs/world/provinces/high_rock.md`, and `docs/world/provinces/hammerfell.md`), followed by R6 Cyrodiil
(`docs/prompts/R6-cyrodiil.md`).**
All 11 province design documents are finished and ready for V1–V9 execution.

To run the next phase, say: "Read `docs/START-HERE.md` and `docs/prompts/_COMMON.md`, then run
`docs/prompts/R5-iliac-bay.md`" (or whichever phase WORLD-PLAN section 7 shows as `next`).

## The rules (non-negotiable)

1. **Legal line.** Facts only (names, relative positions, counts, which features existed, why a design works).
   Nothing from any game's files (text, meshes, textures, sounds, music, voice, dialogue, rasters) enters the repo, raw
   or converted. Terrain is hand-traced vectors with our own heights. Every line of text is our own. Reference data
   stays in the gitignored `.reference/`.
2. **Never push.** Commit locally on `tamriel-overhaul` only; worktree agents do not commit at all; the orchestrating
   session merges and commits. Never force anything; never touch another worktree; never a bare `git stash`.
3. **World building first.** Every element of a place expresses and agrees with its history, culture, conflict,
   economy, faith and ecology. Every new content record carries `why: [world-model sheet field ids]` that resolve.
4. **The floor is Skyrim, measured.** Per region `max(absolute floor, round(median density x km²))`; per settlement
   the tier medians (DESIGN-PRINCIPLES 4.1-4.9). ESO ideas are an optional layer on top, never the bar.
5. **Keep / amplify / replace** legacy sources; **source precedence**: Skyrim, Oblivion and Morrowind positions are
   canonical over Daggerfall and Arena.
6. **The fast loop.** Iterate on the persistent dev server (`tools\dev\devserver.ps1 run`, `/skycraft-dev regen`,
   `tools\qa\quick_corridor.ps1`); run the full gates once per phase. **Never launch the Minecraft client**; the owner
   does visual checks himself. Visual review goes to a Haiku agent over offline renders. At most 3 agents at once.
7. **Gates are measurable and never weakened.** A phase is done when every gate in its prompt passes, measured by the
   named tool, with the numbers in the report.

## After every phase (or step) you merge

1. Merge per `docs/prompts/_COMMON.md` section 7 (`bash tools/dev/merge_worktree.sh <worktree> <NAME>`,
   `tools\qa\qa.ps1 -Server -Rebake`, commit only the merged paths).
2. **Update the state table in `docs/world/WORLD-PLAN.md` section 7** (status, commit, gate numbers, date) and add any
   new open items to section 8. This is required on every merge; the next session trusts it.
3. Update the **Next** paragraph above (one line: the next phase or step and its prompt file).
4. If the phase changed the plan (a new step, a changed gate, a decision from the owner), change WORLD-PLAN section 5
   and the prompt in the same commit, and record owner decisions where the plan keeps them (the province document or
   ILIAC-BAY's decision list).
5. Report to the owner in the `_COMMON.md` section 8 format.
