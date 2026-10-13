# R2 Testing tiers 0 and 1

> "Read `docs/prompts/_COMMON.md`, then run `docs/prompts/R2-testing-tiers.md`." One agent (two if split 0/1).

## Goal

Make the fast loop faster and safer before the big content phases.

* **Tier 0: plan-layer unit tests.** JUnit 5 tests that run without booting Minecraft, under 90 s for the whole suite,
  covering the pure, seed-independent plan layer that every province phase changes.
* **Tier 1: a hot-swap dev server.** Method-body changes go into the running dev server in seconds instead of a
  40-second restart; data files reload by command; structural changes still restart.

## Context to read

* `docs/FINISH-PLAN.md` section 2 (QA harness, fast loop), `docs/AGENT-RULES.md` (fast loop), `docs/DEVELOPING.md`
  (architecture, test harnesses), `tools/dev/devserver.ps1` (how it starts the server, its inbox and restart rule).
* `docs/WORLDGEN-PIPELINE.md` sections 6-9 (the plan APIs to test: `RoadPlan`, `ScenePlan`, the P5 `StreetPlan`).

## Write set

`mod/src/test/**` (empty today), `mod/build.gradle` (test dependencies, the `test` task and its JVM args only),
`tools/dev/devserver.ps1`, `tools/qa/qa.ps1` (a `-Unit` switch), `mod/src/main/java/com/skycraft/dev/**` (the reload
command), `docs/DEVELOPING.md` (a "Testing tiers" section), `docs/FINISH-PLAN.md` section 2 and `docs/AGENT-RULES.md`
fast-loop bullets (two or three lines each).

## Tier 0 steps

1. Add JUnit 5 (Jupiter) to the test classpath; make `gradlew test` run without the Forge run configs (no
   `runServer`). Classes that touch registries must not be loaded; if a plan class drags one in, test through a seam
   (a pure helper), do not mock Minecraft.
2. Tests, at least 40, grouped by package:
   * `worldplan`: `RoadPlan` binary round-trip (write, read, equal arrays); `at()` contract on a hand-built tiny plan
     (distance, half width, span, height interpolation); `Profile` keeps every class under its grade cap on synthetic
     ground; spans tile each segment without overlap; `RoadPlanBuilder` determinism on a small synthetic map.
   * `worldplan.scene`, `worldplan.street`: build twice under different seeds, byte-identical; every door links to a
     node; every GATE node carries a join.
   * `tamriel`: `Places` id uniqueness and index lookup; `Regions.at` on known anchors (Whiterun 0,0 is
     `skyrim/whiterun`); `TamrielMap.sample` deterministic for fixed inputs.
   * `bake`: the input hash changes when a hashed data file changes and not otherwise.
   * Once R3a lands, R3a adds its loader tests here (leave a `world` package placeholder test that is skipped).
3. `qa.ps1 -Unit` runs `gradlew test --console=plain` and prints one PASS/FAIL line with the count and time.
4. Prove it bites: temporarily break `Profile`'s grade cap, see a test fail, revert.

## Tier 1 steps

1. Start the dev server JVM with a JDWP agent on a local port (`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,
   address=127.0.0.1:<port>`); keep `-Xmx4G` and the one-server rule.
2. `devserver.ps1 hotswap`: compile (`compileJava`), find classes changed since boot, redefine them over JDWP (a small
   Java or Python JDWP client in `tools/dev/`; standard HotSwap only: method bodies). On a schema change (fields,
   methods, signatures) or a redefine failure, fall back to the existing restart and say so. `devserver.ps1 run`
   should prefer hotswap over restart when only method bodies changed.
3. `/skycraft-dev reload <scenes|places|cityplans|quests|palettes|all>`: re-read data files that have loaders, without
   a restart (only where the module supports it; list what does not).
4. Document both tiers in `docs/DEVELOPING.md` and update the fast-loop text in FINISH-PLAN section 2 and AGENT-RULES.

## Gates

| Gate | Tool | Floor |
| --- | --- | --- |
| Unit tests | `gradlew test` | >= 40 tests, all pass, < 90 s wall |
| Wired | `qa.ps1 -Unit` | one PASS line with count and time |
| Tests bite | deliberate break of `Profile` | fails, then passes after revert |
| Hot swap | `devserver.ps1 hotswap` after a method-body edit | applied without restart in <= 15 s (measured, reported) |
| Fallback | a field added | restarts, says why |
| Data reload | `/skycraft-dev reload scenes` (and the others listed) | reloads; output confirms counts |
| Docs | DEVELOPING, FINISH-PLAN 2, AGENT-RULES | updated |

## Merge and report

`_COMMON.md` section 7. Update WORLD-PLAN (R2 `done`, test count, hot-swap time). Report per section 8.

**Size:** M.
