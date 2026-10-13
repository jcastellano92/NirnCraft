# R15 Final polish and release

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R15-final-polish.md`." Run last, after every province
> phase's V9 has passed (or the owner decides to ship with some provinces open).

## Goal

Ship. Every number in the player docs is true, the realms meet the template where they fall short, the whole world
performs, every gate passes in one final run, and the owner plays the real pack and signs off.

## Context to read

* `docs/FINISH-PLAN.md` T9 and section 6 (definition of done), `docs/PLAYTEST-FULLPACK.md` (the Prism flow the owner
  runs), `docs/INSTALL.md`, `README.md`, `docs/FEATURES.md`, `docs/WORLD.md`.
* WORLD-PLAN section 7 (every row must be `done` or explicitly deferred by the owner) and section 8 (open items).

## Write set

`README.md`, `docs/{FEATURES,INSTALL,WORLD,CONTROLS}.md` (counts and features), `docs/FIRST-PLAYTEST.md` (final
checklist), realm data under `resources/skycraft/realms/**` and `tools/worldgen/realms*.py` (template top-ups only),
`perf/**` (hotspot fixes only, output-identical), `tools/build_release.ps1` and `tools/build_prism_instance.py` (fixes
only).

## Steps

1. **Open items.** Close or explicitly defer (owner's word) every WORLD-PLAN section 8 item.
2. **Realms.** Deadlands, Sovngarde, Shivering Isles, Apocrypha, Soul Cairn, Coldharbour: check each against the
   parts of the template that apply (identity, a marquee, encounters, a reason to go, roads or paths to every site);
   top up by data.
3. **Performance.** JFR on a headless run over a long ride across three provinces; fix hotspots without changing
   output (block-hash of generated chunks before and after); pregen and fast-travel prewarm still smooth.
4. **Docs.** Refresh every count (places, quests, NPCs, dungeons, biomes, creatures, regions) from the tools, not by
   hand; FEATURES lists what ships; INSTALL is current.
5. **Release build.** `tools\build_release.ps1`; `tools/build_prism_instance.py`; `dist/Skycraft-client-instance.zip`
   from the final commit.
6. **Owner run.** Hand the owner the final checklist (one place per province, one marquee dungeon, one long ride, DH
   and shader check). No agent launches the client. Fix blockers he reports; then a local release tag (never pushed by
   an agent).

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Static and server QA | `qa.ps1 -Server -Bake -Layout -Unit` | PASS |
| Parity | `parity.py --assert-floor SET,NPC,QST,DUN,CRE,FLO,STY,LND,LOR,MUS,ITM` | exit 0 for every region |
| Flow | `flow_audit.py` | every FLW floor for every region (optional-layer metrics excluded) |
| Coherence | `worldsheet_lint.py` | 0 errors; F-WHY 100% for new content, back-fill counter reported |
| Text | `novelty_lint --strict`, `verbatim_check` | 0, 0 |
| Roads | `full_corridors.ps1` (all corridors added by the province phases) | all zeros |
| Visual | Haiku review of the per-province contact sheets | no blocking issue |
| Owner | Prism run | signed off |

## Merge and report

`_COMMON.md` section 7. Set every WORLD-PLAN row to its final state. Report per section 8 with the release commit.

**Size:** M.
