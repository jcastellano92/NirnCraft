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
harness.py list --tag feature:water            # what exists
harness.py gate                                # the merge / push gate (below);  gate --install-hook
harness.py open                                # the latest report in your browser (also .work/harness/latest/ in VS Code)
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
