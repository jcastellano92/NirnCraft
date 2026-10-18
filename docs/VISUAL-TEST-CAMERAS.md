# Reusable visual tests

Use this process for every mod system, including dungeons, sky/weather, towns, spells, enemies and flora. A coordinate
guessed from a bounding box is a candidate, not an accepted test camera. Never use a buried/spectator-through-wall
frame as evidence of appearance or performance.

1. Inspect the actual plan and its routes. Identify what the test must show: a corridor sightline, stair descent,
   entrance, water feature, cave opening, large chamber, enemy encounter or exterior landmark. Keep the plan, room,
   route and feature references with the candidate. For dungeons, use the actual `air-survey` and planned connections.
2. Generate a small candidate set along those routes. `visual_cameras.py plan SURVEY PLACE` proposes positions from
   actual rooms/corridors. These are unverified. Do not substitute unrelated demonstration geometry for real content.
3. Load the real area in the disposable preview. Scout around each candidate once: `visual_cameras.py scout X Y Z
   TARGET_X TARGET_Y TARGET_Z` checks a bounded 27-pose neighbourhood, loaded geometry, body clearance and a short
   sightline. It does not generate chunks. The report is `.minecraft/world-preview/camera-candidates.json`.
4. Use `camera X Y Z TARGET_X TARGET_Y TARGET_Z`, then `settle 1800`, then `capture TAG` in a live actions file.
   Inspect the actual frame. Adjust locally using the frame as composition evidence; retain the clearest useful
   pose for that test. Good numerical clearance does not prove a useful composition. Clear Night Vision after
   dungeon QA view commands before judging lighting. Do not judge storm performance while compiling or encoding PNGs.
5. After visual review, `visual_cameras.py approve NAME TAG --plan "SURVEY / place / room or route" --purpose "..." --region skyrim/falkreath --systems caves,dungeons`
   stores the pose, aim, actual seed/dimension/generation fingerprint, evidence and intent in `tools/qa/visual-cameras.json`.
6. Subsequent runs use `visual_cameras.py run NAME`. Reuse the pose; do not repeat planning/scouting on every run.
   The cheap identity, collision, terrain, mesh and media checks remain mandatory. Captures occur after a rendered
   frame and have JSON receipts alongside their PNGs.

When the seed, dimension, baked inputs or generated geometry source/data change, saved poses are **stale**, not automatically approved.
Regenerate the affected disposable area for the new inputs (an old save can retain old chunks), revisit the plan
and route, scout locally, visually review and approve again. Do not replace the stored fingerprint just to pass.
Lighting, shader or spell-only builds that preserve generation inputs keep the same camera, enabling comparisons.
The current fingerprint is deliberately conservative: a generation change elsewhere may also request review.

`settle TIMEOUT [LOD_CHUNKS]` is a deadline, not a sleep. It checks the native render radius, the active native
renderer, clear camera geometry and camera-local synchronized media. On the installed Embeddium version, a
queue-empty flag alone is insufficient: scheduled/active mesh work, pending uploads and all rebuild queues must
be empty, and the visible-section count must stabilize across completed render frames. Client packet-light tasks
and light-engine propagation must also finish. Unsupported renderer diagnostics fail capture readiness explicitly.
For cave motion comparisons, first warm the short planned route and return to the starting pose using these
checks; retain stationary before/after views at the exact same pose. A changing, incompletely loaded cave does
not establish a fog defect. Motion captures are diagnostic, not automatically approved reference frames.
Optional LOD readiness samples 48 public DH
data columns on three rings. Missing/unsupported observations fail the capture; sampled data is **not proof of all
LOD GPU meshes**, exposure convergence or every point between probes. Status/logs identify this limit explicitly.
Retain visual horizon review for outdoor captures.

Bad frames are retained as diagnostics but labelled invalid. An accepted reference must have an actual clear-camera
receipt, a plan/route reference and human/agent visual inspection. A screenshot command completing is not acceptance.
