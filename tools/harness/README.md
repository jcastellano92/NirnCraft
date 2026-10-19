# NirnCraft test harness

One entry point for every visual and non-visual test of NirnCraft and Infinite ATLAS, for people and agents:
`py -3 tools/harness/harness.py <command>`. Results land in `.work/harness` (never in git).

## Tiers (fastest first; the harness runs each test at its own tier)

| tier | what runs | typical cost | examples |
|---|---|---|---|
| `check` | commands with an exit code and parsed numbers, no rendering | seconds to minutes | contracts, hydrology, roads, dungeons, quests, PBR audit, JUnit, native suites |
| `lab` | Infinite ATLAS offline renderers (`atlas_render`, `pack_lab`), no Minecraft | seconds | far field, sky, water, lamps at any place / time |
| `live` | the one disposable preview client (`NirnCraft-26.3-autotest`), hot reloads keep it open | 5-30 s a scene | places, weather, gallery, UI, animation, grounding census |

## Everyday commands

```
harness.py run                                 # the smoke suite (no selection = --suite smoke)
harness.py run --suite weather                 # a suite;  --tag region:skyrim/whiterun --tag weather:storm (AND; commas = OR)
harness.py run gallery-draugr anim-draugr      # ids or globs;  --tag gallery:armor  --tag gallery:material:daedric
harness.py run --changed                       # only what your uncommitted / since-REF changes can affect (impact.json)
harness.py watch --suite smoke                 # live reload: edit shaders / textures / data, see before|after in seconds
harness.py plan --suite dungeons               # what would run, in what order, what is cached
harness.py audit --budget 30m                  # a spread random sample of everything that fits 5m / 30m / 1h / 2h
                                               #   (problems first, then never-run, then stalest; --focus tag, --dry)
harness.py audit --theme dungeons --budget 30m # one subject end to end: dungeons, landscape, flora, towns, weather,
                                               #   renderer, lights, creatures, gear, fx, ui, content, realms (themes.json)
harness.py list --tag feature:water            # what exists
harness.py gate                                # the merge / push gate (below);  gate --install-hook
harness.py open                                # the overview in your browser (.work/harness/index.html); open suite-smoke: a report
```

Selection: `--suite --tag --exclude --tier --group --variant --changed [REF] --limit N --seed N`.
Tags match by prefix (`region:skyrim` matches `region:skyrim/falkreath`).

## Results: before / after, automatically

Every test (visual or not) keeps two records: `results/<test>/<variant>.after.json` (+ `.png`) is the newest,
`.before` the one before it; a new result shifts after to before and drops the older one. Each run writes one
report per kind of run, `.work/harness/reports/<scope>/report.html` (scope = `suite-smoke`, `tag-...`, `test-<id>`, `watch`, `gate`, `probes`, `changed`; each keeps its latest run, the newest 15 scopes stay, smoke and gate always), and one overview of every stored result, `.work/harness/index.html` (`harness.py open`; `open suite-smoke` for a report), `sheet*.png` (before | after | change),
`combos.png` (every variant of a scene side by side) and `agent/` (below). Metrics on every image: luma, black tiles
(the NaN / black-box signature), clipping, and how much changed since the previous capture.

## For agents

* **Claude agents: hand visual review to the `visual-reviewer` subagent** (`.claude/agents/visual-reviewer.md`, runs on
  Haiku): it reads `agent/index.txt` and the pages and answers in a fixed BROKEN / REGRESSION / CHECK / OK format, so
  screenshots never fill the main conversation. Other AIs use whatever model they like on the same pages.
* **Read the run's `agent/index.txt` (its path is printed at the end of the run), then only the pages it lists.** Agent pages hold at most 3 before|after rows of 760 px
  per image (problems first, unchanged captures left out), about what one image read can actually resolve. Do not
  shrink more onto a page; ask for detail instead:
* `harness.py zoom <test> --cell 1,1` (a 3x3 grid cell at full resolution) or `--crop x,y,w,h`.
* `harness.py probe NAME --visit x,z,height,yaw,pitch [--weather storm --time 18000 --setup "fx fire"]`,
  `--place whiterun --shot aerial`, `--stage 6` (the gallery stage), `--frames 6,7` (animation): a one-off test without
  editing the catalog. Probes expire after a day (and beyond the newest 40) with their results.
* Safe by design: live tests only ever touch the disposable preview instance (a fresh seed-1234 world each launch);
  `check` and `lab` never touch a game. The default `run` (smoke) is a safe audit to start from.
* One owner: a second `run` while another is running queues its job; the owner merges it into the work in progress
  (shared tests run once, fresh cached results return at once) and the second command prints its own results.
* Output is short on purpose: a result line, one line per problem, and the report path.

## Live client lifecycle

The harness launches the client when a live test needs it, keeps it between tests, and **closes it at the end of
every run** (also after errors); only `watch`, `serve` and `--keep` keep it. As a backstop the game closes itself
after 20 minutes without a harness action (`-Dnirncraft.autotest.idleExitMinutes`). Test boots never stop on the
character screen or the opening's covers (`ClientAutotest.automated()`; `-Dnirncraft.autotest.intro=true` keeps the
opening, to test it). The harness refuses to wait behind a blocking screen and says which one, and warns when the
installed core is older than the latest build (`--rebuild` builds, installs and restarts).

## Scene settings and batching

A scene declares what it needs; the runner orders scenes so that settings which cost a restart or a reload change as
rarely as possible, and applies the cheap ones per scene:

| setting | in the scene | cost | how |
|---|---|---|---|
| JVM flags (`-D...`) | `group` (scenes/groups.json `jvm`) | client restart | scenes of one group run together |
| shaders on / off | variant `shaders` / `vanilla` | live toggle | batched after the group |
| pack options | `pack_options: {"TAA": false}` | shader reload | batched; Iris' option file, then `reload` (names checked against lib/settings.glsl); lab scenes pass the same options to pack_lab as `opt=` |
| static settle | `settle: "static"` | none | every settle in the scene runs with `tick freeze` (no fire, crops, mobs or random ticks feeding the far field), then `tick unfreeze` and 3 s for dynamics before the capture |
| order | `after: ["scene-id"]` | none | runs after those scenes; a lab scene whose inputs are missing pulls them into the run |
| live camera | `camera_from: {"scene", "variant"}` | none | a lab render of that live capture's own view (refused when the capture had not settled); writes `<id>/lab.twin.png`: live, offline, dE heat over the exported blocks |
| time, weather | `time`, `weather` | none | every scene pins them |
| weather off | `weather: "off"` | none | the client's weather-off switch: no clouds, rain, fog banks; distant haze kept |

Every live scene declares its weather when the catalog loads (catalog.settle_weather): a scene about weather (an
explicit weather other than clear, or a weather / clouds / storm / fog / rain / lightning tag) keeps it; every other
one (the world itself: galleries, towns, ladders, flights, fidelity pairs, planned places) gets `weather: "off"`;
indoors has none. The validator refuses a weather-tagged scene with weather off. In particular world-gen scenes
run with `weather: "off"`; weather has
its own ladders (`suite:weather-distance`: overcast, rain, snow) and the storm-front scenes. Lab scenes with
`weather: "off"` pass the same switch to the offline renderers (`pack_lab weather=0`, `atlas_render weather=off`), so
an offline render and its live twin show the same sky.

## Speed: kept world, visual settle

The live test world is kept between runs and launches (`saves/harness_<worldgen hash>`): a place is generated once,
later visits load it from disk; a world-generation change makes a new one (`--fresh-world` forces it). Captures
settle on what the camera sees (`settle-visual`), not on fixed timers: the chunks in the view cone (plus a ring
around the camera) loaded, lit and built by Sodium; Infinite ATLAS has no tiles pending in the view cone; and the
picture is steady (structure change under 3 %, exposure drift under 0.8, three samples, at least 3 s after the move).
Animation (water, leaves, particles) does not hold it up. At the deadline it continues and the result is flagged
`partial` with what was still missing. Measure tests keep the strict chunk settle. The HUD and every NirnCraft overlay
are hidden for the whole session (UI tests turn the HUD back on); the character screen is skipped.

## Running while you work

The test client opens behind your windows and never pauses when you click elsewhere. Bring it forward to watch;
minimize it and a watchdog restores it behind your windows within 2 s (a minimized Minecraft cannot render);
close it with its X and the harness relaunches it and runs the interrupted test again (a normal close is not
counted as a crash). To end a run: `harness.py stop` (at once: the run ends, the client closes cleanly, the rest is marked stopped;
`--after-test` lets the current test finish).

## When a test fails: clean up, record, move on

Every test has a time limit (`timeout`, default 240 s; checks and lab commands are killed when they overrun). Any
failure or timeout is recorded (`error` / `fail` with the reason) and the run continues. For live tests the harness
first cancels what is still queued, runs the scene's own cleanup (made tolerant: it never ran) and a standard reset
(close screens, first person, peaceful, clear effects, home from a realm), so the next test starts clean. A circuit
breaker watches live failures: three in a row with the same error restart the client once; if the group still
fails, its remaining tests are skipped with the reason instead of each burning its time limit. A client that cannot
start skips its group the same way.

## Crashes, quarantine, hooks, trace

* **Crash:** the client dying during a test is detected; the crash report, any JVM `hs_err` file and the last 400
  log lines are saved as `results/<test>/<variant>.crash.txt` (the previous one as `.crash.before.txt`, nothing older),
  linked from the report. The client is relaunched and the same test runs once more at its own pose; a second crash
  records `crash` and the run moves on; 3 crashes in a run skip the group. The instance's own crash reports and logs
  are trimmed to the newest few after each run.
* **Quarantine:** a test that errors or crashes in 3 runs in a row (same fingerprint) is skipped by itself with the
  reason until its inputs change; `harness.py quarantine` lists, `--clear [keys]` releases, naming a test by id or
  `--include-quarantined` runs it anyway. Content failures (`fail`) are real results and never quarantine.
* **Hooks:** every failure, crash, restart, quarantine, skipped group and finished run is appended to
  `.work/harness/events.jsonl` (rotated at 2 MB) and runs the commands in `tools/harness/hooks.json` /
  `.work/harness/hooks.json` (event JSON in `HARNESS_EVENT`); `hooks.example.json` + `hooks/notify.py` give desktop
  notifications.
* **Trace:** every step (action batches with their verbs, duration, outcome and the client status after; launches,
  closes, reloads, check commands, crashes, harness tracebacks) goes to `.work/harness/trace/run.jsonl` (previous run
  kept). A failed test's record carries its last 25 steps. `harness.py trace <test>` prints what led up to it,
  interleaved with the game's log lines of the same seconds; `--previous` for the run before.
* Checks that print problems but exit 0 use `fail_regex` (e.g. `^ERR`) so they cannot pass falsely.
* **Game log per test:** every WARN / ERROR / exception the game logs during a live test is kept with its result
  (`results/<test>/<variant>.log.txt`, previous as `.log.before.txt`), counted (`logWarnings`, `logErrors`) and
  serious ones (a command that did not parse, an exception) flagged, so a skipped `try` never goes unseen.

## Subscribe, issues tally

* `harness.py wait --events run_finished,client_crashed` blocks until such an event happens: an agent runs it in the
  background and is woken by its own tooling (no polling). Agents that start a run should start it as a tracked
  background task (or follow it with `wait`) so they learn when it ends.
* `harness.py issues [--md]`: the running tally and its workflow. A failing / flagged test opens an issue (first and
  last seen, how often, why). An agent working on one: `issues claim <test> --by NAME` (in-progress, so two agents do
  not fix the same thing) -> `issues fixed <test> --note "what changed"` (testing) -> `issues verify` re-runs every test
  marked fixed: a pass moves it to fixed (with date and commit), or for visual tests to needs-review until the
  reviewer (visual-reviewer / a person) confirms the picture with `issues confirm <test>`; a failure reopens it with
  "the fix did not hold". `issues reopen`, `issues note`. The overview shows the counts per state.
  Open issues are listed by priority (P1..P4: severity, recurrence, and how many tests share the cause; `issues
  prioritize <test> --priority P1` overrides) and grouped by cause (same reason with numbers stripped; `issues group
  <test> --name X` regroups). Fixed issues drop off after 7 days (the totals keep counting them).
  A finding no test fails on (the visual reviewer's BROKEN / REGRESSION lines) is filed with `issues add <test> --note
  "what, where" [--priority P2] [--name group]`; it follows the same workflow (a visual issue ends with `confirm`).
  `issues confirm <test> --by REVIEWER --evidence IMAGE` is refused without the image the claim is about, or when the
  reviewer is the one who fixed it. Twins (`twin-*`) are measured against their lab render (`<variant>.twin.png`
  side-by-side; structure, skyline, dE): "lab and live match" is a number, not a glance.

## Independent review, autonomous runs

* **Independent reviewer** (`tools/harness/review.py`): a fresh headless Claude session (Haiku by default, `--model`)
  with read-only tools, run in an empty folder: it sees only the evidence images and the claim, never the
  conversation of the agent that made the change. `harness.py review` grades every needs-review issue (pass ->
  fixed with the reviewer and the evidence recorded, fail -> open again, unsure -> stays for the owner);
  `review --pages DIR` reviews a run's agent pages. A BROKEN / REGRESSION only counts when a second look at that
  test's own before | after sheet confirms it; otherwise it is an "(unconfirmed)" note. Every answer is kept in
  `.work/harness/review/log.jsonl`.
* **Gate review:** after its tests the gate runs the reviewer over its pictures; a confirmed BROKEN on a gated test
  that was not already a known issue blocks (`--no-ai-review` skips it; the gate then says the pictures are
  unverified). The gate logs the review: how many captures on how many pages, every finding with what it does
  (BLOCKS / already open / not in this gate / issue / note), and the reviewer's own count; a reviewer that fails or
  accounts for under 90% of the captures leaves the gate saying the pictures are UNVERIFIED (`reviewState` in
  `.work/harness/gate/latest.json`).
* **The gate never passes on tests that did not run:** a skip (a group stopped after crashes, a disabled group) is
  not a result: it is never reused from the cache, the gate lists every test that did not run, and those block
  (quarantined tests are listed, not blocking). `check-harness-runner` (audits/runner_selftest.py) holds the harness
  to these rules, plus torn status reads (a client is never declared lost on a half-written heartbeat).
* **Autonomous:** `harness.py auto` (or `auto --once` from a scheduled task) waits while the GPU is busy or another run
  owns the client, then verifies fixes marked done, has the reviewer grade them, audits a `--budget` sample, reviews
  its pictures, writes ISSUES.md and sends an `auto_cycle` event (hooks notify). `--every 120` minutes.

## Where the picture was taken: hard checks

* **Pose:** every live capture's receipt (position, yaw, pitch) is checked against the pose the scene asked for
  (`posecheck.py`): more than 1.5 blocks or ~3.6 degrees off fails the test. When the camera moved (a bumped mouse,
  a pose that did not apply) the harness poses again and captures once more before anything is stored.
* **Comparable before / after:** a pair is only diffed when both were taken at the same place, looking the same way,
  at the same time of day and moon phase, in the same weather, world, shader state and render distance. Otherwise the
  sheet shows "before: NOT COMPARABLE (why)" and no diff numbers exist for it.
* **Twins:** the lab camera (atlas_render: yaw 0 = north, pitch < 0 down) and the live camera (Minecraft: yaw 0 =
  south, pitch > 0 down) are compared in world space (a convention error is a 180 degree fail), plus the picture
  metrics (structure, skyline, dE) and the `.twin.png` side-by-side.
* Not yet deterministic: ATLAS clouds move with world game time, weather fronts simulate, mobs and particles move.
  Pinned capture clock, tick freeze and input lock are queued (`issues`: deterministic before/after).

## Subjects: everything the game defines, with views, sheets and cards

The game lists what can be tested itself: `harness.py run subject-index` (once per build that adds things) runs
`/nirncraft-qa subjects` and stores `.work/harness/subjects.json`: entities by kind (boss, monster, creature, mount, npc,
fish, water, vehicle), races, NPC races and roles, blocks by kind (flora, crop, sapling, light, door, furniture, block),
items by kind (weapon, armour with its slot, tool, food, ingredient, fish), ingredients with effects, spells, trees,
furniture kinds, building uses x cultures x wealth, landmarks. `subjects.py` turns every subject into scenes with the
views its kind gets (`VIEWS`): icon (the item as the UI shows it), dropped, held, worn (by several races), source
(an ingredient's plant), placed (and at night for lights), portraits (races, roles), spells cast in the dark studio,
buildings outside / side / inside (+ at night). **A new entity, item, block, spell, race, role or building type gets
its scenes by itself.** Only lights and buildings get a night view; everything else is shot at noon.

* Framing is done by the game from the subject's real size (`frame`, `frame-box`, `frame-file`), so a mudcrab and a
  dragon both fill the picture; the receipt records what was framed (a wrong subject fails).
* Select: `--tag kind:weapon`, `--tag subject:iron_sword`, `--tag view:icon`, `--tag race:khajiit`, `--tag suite:buildings`.
* `harness.py sheet SUBJECT --level full|top|pair|single [--view front|side] [--top N]`: one subject's pictures on a
  sheet (several pages when needed); `single` is the one view an agent asks for.
* `harness.py card SUBJECT --level basic|mid|full` (or `--kind weapon`): the subject's card: game facts (size, slot,
  effects, cost ...), coverage (views captured / passing), open issues, pictures; PNG for reviewers, card.json and
  card.md for agents, in `.work/harness/cards/<subject>/`.

## Behaviour scenarios

`tools/harness/scenarios/*.json`, one per behaviour (`harness.py scenario new NAME` writes a template; `scenario list`).
An arena in the sky (floor, walls, props such as doors), actors (entity, position, NBT; tagged `qa_actor_<name>`),
duration, sampling and trials. The game records every actor's position, health, target and the watched blocks
(`/nirncraft-qa record`); expectations are judged on the recordings with statistics: `targets`, `never_targets`,
`health_drops`, `alive_at_end`, `reaches`, `block_changes`, each with `within` (ticks) and `rate` (share of trials).
Run: `harness.py run behaviour-wolf-vs-sheep` or `--tag suite:behaviour`; every trial's recording is kept next to the
result.

## Gaps and what a change needs

* `harness.py gaps [--kind K] [--md] [-v]`: per kind, how many subjects are fully captured / failing; screens no scene
  opens (or that docs/UI-COVERAGE.md does not list).
* `harness.py recommend [--staged | --base REF]`: before a commit: the tests the change affects (impact.json), ids it
  adds that the subject index does not know yet (refresh it, the new subject's scenes and card follow), screens it
  touches and whether a scene covers them. `recommend --install-hook`: an advisory pre-commit hook (never blocks).

## Error ids

Every failure carries a stable id and a hint (`errors.py`), in the result (`errorCode`, `errorHint`, `[E-...]` in the
error), the summary, events and the issue tally: E-SETTLE-TIMEOUT, E-CAMERA-IN-TERRAIN, E-POS-UNLOADED, E-DISCONNECT,
E-HEARTBEAT, E-CRASH, E-CLIENT-CLOSED, E-STUCK-SCREEN, E-CMD-FAILED, E-UNKNOWN-ID, E-NO-SUBJECT, E-WRONG-POSE,
E-SHADER-STATE, E-TIMEOUT, E-NO-REPORT, E-VALIDATE, E-CHECK-FAILED, E-METRIC. The game's own complaints during a test
(a setup command that quietly did nothing: "That position is not loaded") are found in its log and flag the test with
their id, so a scene that was not built as designed never passes. New kind: one line in `errors.py`.

## Testing standards

* **Adding something to the game** (entity, item, block, spell, race, role, building type): register it, run
  `harness.py recommend`, then `run subject-index` and `run --tag subject:<id>`; look at `card <id>`. Nothing to write.
* **A new test**: a scene in `scenes/<domain>.json` (kebab-case id, one pose, tags `suite:` / `feature:` / `region:`,
  pinned `time` and `weather` or the defaults noon / clear), or a generated set in `catalog.py` / `subjects.py`, or a
  scenario JSON for behaviour, or a check in `scenes/checks.json` with `fail_regex` when the tool exits 0 on errors.
  `check-scene-catalog` validates them all.
* **Pictures**: framed by the game, pinned time / weather / moon, before / after only when comparable; no claim that a
  picture is right without a side-by-side or a metric; visual fixes are confirmed by the independent reviewer
  (`issues confirm --evidence`, `harness.py review`), never by whoever fixed them.
* **Numbers**: every check states what it measures and its threshold; failures carry an error id.
* **Before a commit**: `recommend --staged`; before a merge / push: `gate`.

## Hot reload kinds (watch mode and `--changed`)

| change | reload | restart? |
|---|---|---|
| `shaderpack/InfiniteATLAS/**` | pack zip rebuilt (glslang) + Iris reload | no |
| `mod/src/main/resources/assets/**` | copied into the dev overlay resource pack + `resources` reload | no |
| places / cityplans / styles / scatter / quests data | `live_world.py reload <group>` | no |
| `mod/src/main/java/**` | `--rebuild`: Gradle build + install + restart | yes |
| `tools/harness/scenes/**` | catalog reloaded | no |

## Catalog

`tools/harness/scenes/*.json` (one file per domain) plus generated sets:

* **gallery** (`suite:gallery`): every entity (`gallery:monster|creature|npc|water|misc`, `gallery:entity:<id>`), every
  armour set (`gallery:armor`, front and back stands with set, shield and weapon), every weapon by material
  (`gallery:weapons`), block / furniture lineups (`gallery:blocks`, `gallery:furniture`), on a sky stage with fixed
  light, so any one part can be asked for. New registry entries get scenes by themselves.
* **animation** (`suite:animation`): every entity moving in an invisible pen, a 6-frame filmstrip.
* **planned places** (`suite:places-planned`): an aerial and an approach shot for every named place, cameras planned
  offline on the exact terrain of the world's seed (below).
* **grounding** (`suite:grounding`): the floating-parts census (`/nirncraft-landmarks groundcheck`) for every place,
  numbers with before/after; getting worse fails the gate.
* **checks** (`scenes/checks.json`): every offline audit, each with tags (`check:roads`, `check:dungeons`, ...).
* **distance** (`suite:distance`): per place and time of day, a ladder of heights across the near/far hand-off
  (`dist-*`, one sheet), a pop sweep flying through it (`pop-*`, hand-off change and pop ratio), fidelity pairs of the
  same view near vs far (`fid-*`, colour dE, brightness, structure), PBR / block ladders on the stage
  (`pbr-ladder-*`, `blocks-ladder-*`), clouds, actors, lights and player edits at distance. `suite:seam-core` is the
  short set the gate runs for renderer changes.
* **horizon** (`scenes/horizon.json`): horizon fade, mountain shadows and the far edge at several times and weathers.
* **tool validation** (`suite:validate-tools`): `validate-world-*` samples the real heights in game
  (`/nirncraft-qa sample x z r step`) and compares them with the offline generator; `twin-*` renders the same view
  in the offline lab and live; `check-detectors` proves the black / magenta / box detectors still fire. The
  overview shows where the offline tools and the real game disagree.

Scene fields: `id, title, tags, pose (visit | camera | place [+shot] | dungeon | realm | stage | spawn), time,
weather, front, setup, cleanup, wait, variants (shaders, vanilla), frames, measure, checks (maxBlackTiles, lumaMin,
lumaMax), capture (world | ui), group, seed, source`. `validate_scenes.py` checks the whole catalog
(`check-scene-catalog`). Live verbs: see `validate_scenes.py VERBS`; UI flow: `screen title|pause|options|<class>`,
`click <label>`, `key <code>`, `expect-screen <name>`; third person: `perspective back|front|first`; idempotent
setup: `try <command>` (a zero result is not a failure).

## Cameras: planned, never stale, per seed

`cameras.py` plans shots for any named place outside Minecraft (`com.nirncraft.qa.CameraPlanner` on the seeded
terrain, via `native/java/mod-integration/harness CameraPlanHarness`; ~0.1 s a place), cached per seed and
world-generation fingerprint, so changing terrain code or data re-plans them by themselves. Absolute `camera` poses
carry the seed they were made in and are skipped in another world; surface-relative and planned poses work in any.
The in-game `camera` verb still refuses a pose inside trees or buildings; the harness then tries the next candidate.

## Generated features: every biome, landmark and site kind

`harness.py features [--seed N] [--force]` builds the generated-feature index offline (`features.py` runs
`FeatureIndexHarness` on the seeded terrain, about 10 s; cached in `.work/harness/features/<seed>-<hash>.json` and made
again when world generation, landmark, explore or scatter code / data change): the world's own biome source with the
seeded climate on a 32-block grid over Tamriel and the foreign lands (per biome up to 6 interior spots, spread across
regions, on land unless the biome is aquatic), every seeded natural landmark kind and Discoveries formation, and every
minor site, stash, scene-plan kind and anchor. `feature_scenes.py` turns the cached index into scenes (`suite:features`;
nothing runs Java at catalog load, no index = no scenes): `biome-<id>-<n>-ground` / `-aerial` (`suite:biomes`,
`biome:<id>`), `nat-<kind>-<n>-approach` / `-aerial` (`suite:natural-landmarks`, `landmark:<kind>`) and
`site-<kind>-<n>` (`suite:sites`, `site:minor/...`, `site:scene/...`, `site:anchor/...`, `site:stash`), with `region:`,
`shot:` and `feature:flora|foliage|water|snow|ash|sand` tags; all visit poses for the index's seed. Audit theme
`features`; vegetated ones join `flora`, natural landmarks `landscape`. Not covered: underground and realm biomes
(not on the overworld surface), the per-chunk natural micro-scatter (rolled during chunk generation), fidelity
ladders (they need a named place).

## In game: the host's tour

`/nirncraft-tour start towns|cities|landmarks|forts|dungeons|all|<ids>|file:<name> [region:<prefix>] [near:<blocks>]
[limit:<n>] [shot:aerial|approach|both] [dwell:<s>] [settle:<s>]`, then `next | back | goto <n> | pause | resume |
dwell <s> | status | stop` (clickable in chat). Host only (the owner of a single-player / LAN world, an operator on a
dedicated server); any world, any game mode, the world's own seed: the same planner picks the views, the tour waits
for the chunks, settles, holds the view, and gives back your position and game mode at the end, on stop, on logout.
`harness.py export-tour NAME [selection]` writes any harness selection as a tour file.

## Gate (merge / push)

`harness.py gate [--base origin/main]` maps the files changed since the merge base to the tests they can affect
(`impact.json`), runs them (fresh results are reused), and fails on a **new or worse** failure: a check that already
failed at the base (for example 1416 placement problems) does not block an unrelated push, making it worse does.
`gate --dry` lists the tests; `--no-live` skips the game (checks + lab only); `gate --install-hook` makes it the
pre-push hook (`git push --no-verify` skips it knowingly). Receipt: `.work/harness/gate/latest.json`.

## Registration contracts

`audits/contracts.py`: every item / weapon / armour piece / block / entity must be wired everywhere its kind needs
(item definition, model, textures, value, weight, weapon attributes, tags, a recipe or loot, equipment assets,
blockstates, block loot ...). Existing gaps are in `audits/contracts-baseline.json`; **a new gap fails**
(`check-contracts`, in the gate for every resource / Java change). `--kind weapon --id daedric_sword` explains one;
`--update-baseline` drops fixed gaps. The visual side needs no wiring: the gallery picks up new entries.
