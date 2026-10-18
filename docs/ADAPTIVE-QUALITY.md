# Adaptive quality v2

One controller keeps the whole frame (the SkycraftAether pack and aether's far field) inside a GPU budget on any
machine, from an RTX 3090 to a Steam Deck, by giving each effect what it is worth: the most visual value per
millisecond, measured on this machine, far detail first, nothing popping. Code: `mod/.../render/quality/`
(controller, pure Java, unit-tested), `aether/AetherQuality` (aether's side), `shaders/lib/quality.glsl` (the pack's
side), `native/tools/pack_lab_quality.py` (how the visual weights were measured).

## The knobs

Every knob is continuous, 0..1. For the pack, 1 is the profile's `#define` (now a loop *ceiling*: loops run to the
`#define` and `break` at the runtime count); 0 is the feature's floor. Counts are rounded with a spatially stable
per-pixel dither, so a fractional count blends between neighbours instead of stepping over the whole screen.
Features that switch off fade: between 0 and their first useful level ("on") the feature runs its minimum count and
its contribution is scaled by `q / on`, so a feature turning on or off eases in over the slew (about a second).

| Knob | Texel.ch | Drives | Floor / on | Weight | Shape α | Distance-scaled |
|------|----------|--------|-----------|-------:|--------:|:---:|
| SHADOW_TAPS | 1.r (+ penumbra 1.g) | soft-shadow taps 1..SHADOW_TAPS, penumbra 0.6..1 | 1 tap | 1.5 | 3 | yes |
| SSAO | 2.r | SSAO samples; the parallax (POM) steps go with it (contact detail) | off / 0.2 | 1.0 | 2.5 | yes |
| SSR | 3.r | reflection steps (beyond: aether / sky mirror) | off / 0.2 | 1.5 | 4 | yes |
| LIGHT_SHAFTS | 4.r | light-shaft steps: the real-occlusion march (SHAFT_MARCH), or the screen-space samples on LOW | off / 0.15 | 0.6 | 7 | |
| WAVES | 5.r, 5.g | waves drawn 4..WAVE_COUNT (shortest first, spectrum fixed) and wave LOD (1..2x sooner) | 4 waves | 1.9 | 0.3 | (own LOD) |
| WATER_EXTRAS | 5.b, 5.a | caustics, falls spray | off | 0.3 | 1 | |
| AIR_MARCH | 6.r | the near air march (closed-form fog always stays) | off / 0.1 | 1.5 | 10 | yes |
| HALOS | 6.g | lamp / torch / spell halos | off / 0.1 | 3.2 | 12 | |
| SMOKE_COLUMNS | 6.b | smoke-column steps (fewer for far columns) and how many fire columns (SMOKE_FIRE_VOLUMES x knob, the last one thinning) | 2 steps, the volcano only | 1.0 | 5 | yes |
| FALLBACK_CLOUDS | 7.r | the pack's own deck's octaves (no aether only) | 2 octaves | 0.8 | 3 | |
| BLOOM | 8.r | bloom strength | off | 0.3 | 0.3 | |
| FAR_DETAIL | 9.r, 9.g | where counts start to fall with distance (16..192 blocks) and how far (to 0.3..1) | 16 / 0.3 | 0.3 | 4 | (it is the scaler) |
| AETHER_SCALE | - | aether's render scale 0.4..1 (0.05 steps, at most every 2 s) | 0.4 | 3.0 | 2.5 | |
| AETHER_LOD | - | aether's LOD bias 3..1 (coarser levels sooner far away) | bias 3 | 1.0 | 3 | |
| AETHER_CLOUDS | - | aether's cloud pass half / quarter resolution | quarter | 0.8 | - | |
| AETHER_SHADOWS | - | far-terrain sun shadows + sun map off / 256 / 512 / 1024 | off (256 map) | 2.5 | 2 | |

Fixed costs outside the knobs (every profile, too small to steer): the eye's exposure (one pixel reduces 54 mip taps; every pixel writes 4 bytes of colortex8), night vision and the regional grade in final.fsh (4 map texels and 8 LUT texels per grade in reach).

Value of a knob: `V(q) = weight * (1 - e^(-αq)) / (1 - e^(-α))` (0 below "on"). α near 0 is linear (every wave
shows); a large α means the first samples give nearly all of the look (halos, the air march, light shafts).
**Far detail first:** FAR_DETAIL is cheap to lose (weight 0.3) and lowers the work of every distance-scaled knob at
once, so it is the first thing the allocator gives up; aether's LOD bias likewise coarsens only far terrain.

### How the weights were chosen

`py -3 native/tools/pack_lab_quality.py` (run from `native/`): pack_lab renders five scenes (noon land, a lake,
golden hour with ground fog, night lamps in fog, the volcano) once each and the pack's passes again for every knob at
0 / 0.25 / 0.5 / 0.75 with the others at 1. Each image is compared with the all-1 reference:

* convert to Oklab (perceptually uniform), blur both by a 1-pixel Gaussian (single-pixel sampling noise counts less
  than lost structure, as at play distance), dE = 100 x |difference| per pixel;
* loss = mean dE + 0.25 x the 95th percentile (a lost reflection on one lake counts even when the lake is small).

Per knob, averaged over the scenes (lab, 640 x 360; `native/build/quality-lab/weights.json`):

| Knob | Loss off | at .25 / .5 / .75 | Fitted α | Off in which scenes | Saved (lab, 3090) |
|------|---------:|------|------:|------|------:|
| halos | 0.80 | 0.02 / 0.02 / 0.01 | 12 | lamps 3.98 (a dungeon-air scene: 7.2) | 0.07 ms |
| waves | 0.48 | 0.41 / 0.32 / 0.22 | ~0 | lake 2.41 | 0.01 ms |
| air march | 0.27 | 0.02 / 0.01 / 0.01 | 9.8 | golden 0.89, lamps 0.44 (dungeon air 0.41) | 0.24 ms |
| light shafts | 0.11 | 0.02 / 0.01 / 0.00 | 7.4 | golden 0.53 | 0.03 ms |
| bloom | 0.04 | 0.03 / 0.02 / 0.01 | ~0 | all | 0.01 ms |
| far detail | 0.02 | 0.01 / 0.00 / 0.00 | 3.9 | golden 0.08 | 0.08 ms |
| SSAO | 0.02 | | | (the lab's G-buffer has little contact detail) | 0.02 ms |
| SSR | 0.01 | | | (the lab's lake mirrors mostly aether / sky) | 0.02 ms |

Final weights = 4 x the lab loss where the lab sees the effect (halos 3.2, waves 1.9, light shafts 0.6 rounded,
far detail 0.3), raised by judgment where the lab cannot show it or under-shows it: shadow taps 1.5 (the lab has no
shadow map), SSAO 1.0 and SSR 1.5 (in game, contact shading and shore reflections of near terrain), smoke columns
1.0 (the lab scene missed the column), the air march 1.5 (dungeon air and fog sealed out of houses); aether's knobs
by judgment (render scale 3.0: the pack upsamples and distant terrain is soft, so it scales well; far shadows 2.5:
mountain shadows at sunset). The shapes α come from the fitted curves.

**Costs, from the same lab (GL timestamps around the passes):** what a feature costs is mostly paid as soon as it is
on: the air march's setup (the room / light lists and weather reads per pixel) is ~65-70% of its full cost, halos
~50%, SSR ~50%, light shafts ~30%. The cost model has these entry shares, so turning a feature off is priced right
(the biggest saving for the least look: the air march goes first on mid-range budgets; its closed-form fog stays).
`--smooth` sweeps every knob together in tenths: the look changes evenly (the lake linearly with the waves; fog and
halos within the first tenth, which the fades spread over the slew) and the cost rises evenly once features are on.

## The budget

* **Target frame rate:** the GPU class's (HIGH 72, MID 60, LOW 50, IGPU / DECK 40) or
  `-Dskycraft.quality.targetFps=N`, capped by the game's frame limit and the monitor's refresh rate.
* **Budget:** 90% of the target frame time, for the whole frame's GPU time (timestamps written on the shared encoder
  at RenderFrameEvent Pre / Post, outside passes; read back a few frames later, tagged with the knobs that frame
  used). The allocation aims at 92% of the budget, corrected by the measured-minus-predicted residual.
* **Allocation** every 0.5 s (0.1 s when 25% over): greedy marginal value per predicted ms from every knob's floor,
  every rung priced by the full model (non-separable: far detail scales others); rungs that cost nothing measurable
  are always taken, so a CPU-bound frame keeps its looks.
* **Hysteresis:** a new target is taken only when the measured frame is over 102% of the budget (and the new
  target is cheaper) or under 85% (and the new one is better); probes never trigger decisions. Knobs slew to the
  target: up 0.12/s, down 0.6/s (2/s when 20% over). Discrete knobs dwell 4 s; aether's scale moves in 0.05 steps,
  at most every 2 s (each step reallocates its targets).
* **CPU-bound / host-busy:** wall time over the target while the GPU is idle part of the frame, or an integrated
  server hosting guests with ticks over 40 ms: quality never drops for it (GPU knobs would not help).

## The cost model and calibration

`frame(q) = base + Σ c_k u_k(q) + κ aether(q)`; `aether(q)` is fitted from aether's own GPU timestamps
(`aether_get_stats` frameMs), the rest from the whole frame's. u_k = megapixels x (entry share + rest x count
fraction, x the far-detail factor). Fitting: recursive least squares from priors (`HardwareTier.speed` x per-megapixel
costs of a GTX 1080 class GPU, from the lab), forgetting 0.998, non-negative, outliers rejected (eight in a row are a
new scene and go in), covariance clamped, and a weak pull to the prior every 50 samples so terms the data cannot
tell apart do not trade cost between them.

* **First guess (no profile):** the GPU class from its name (`HardwareTier`: RTX 30/40/50, GTX 9/10/16, RX 400..9000,
  Arc, Intel UHD / Iris Xe, 680M / 780M, the Deck's Van Gogh or any AMD APU on a 1280 x 800 panel; Linux / Mesa names
  too) sets the priors and the target.
* **Calibration while a loading cover is up** (Skycraft's intro "the valley wakes" or LoadingCover; the world renders
  behind it): about 16 settings x 12 frames (all max, all floor, each pack cost alone, far detail off, each aether
  knob off, all max), ~200 frames.
* **Uncovered first boot:** a quick pass nudges each knob 0.3 down for 10 frames over the first seconds (while chunks
  load in), then live play.
* **Live:** every 1.5 s one knob is probed 0.15 lower for 20 frames; an *off* pack feature gets a "ghost" probe at
  q = 0.01, which pays its whole entry cost while the pack fades it to a tenth or less (only when its predicted cost
  fits the headroom), so a feature that was turned off on a pessimistic guess comes back when the machine can afford
  it.
* **Profile:** `config/skycraft-quality.properties`, keyed by GPU name + driver + window size (the nearest saved size
  of the same GPU seeds a new size), saved a minute into live play, every 5 minutes and on leaving a world; with a
  profile the session starts tuned (no calibration). Delete the file to recalibrate.

## Fallbacks below the floor

1. All knobs at their floor and still over 115% of the budget for 5 s: **tier 1**, the far field at its lowest
   (render scale 0.33, LOD bias 3, quarter-resolution clouds, no far shadows, 16 km).
2. 8 s more, with aether at least a quarter of the frame: **tier 2**, the far field off (its targets shrink to 1 x 1,
   so the pack's own sky, cloud deck and terrain-horizon sunsets take over). It is retried after 3 minutes (then 7.5,
   ... at most 30) since its cost cannot be measured while it is off.
3. Under 60% of the budget for 15 s (leaving room for aether's predicted cost) steps back up.

**What the minimal tier keeps:** the eye's adaptation, night vision and the regional grade; the deferred lighting itself (sun / moon, sky ambient, block light, coloured light,
emissives, labPBR), one-tap sun shadows, the far-field sun map's mountain and cloud shadows (tier 0 / 1), the
closed-form ground fog and aerial perspective (same as aether's), the sky, stars, moons and aurora, water with four
waves, refraction, absorption, Fresnel and the sky / far-field mirror, smoke columns at two steps, and the near/far
hand-off. Off: SSAO, SSR marching, light shafts, the air march, halos, caustics, spray, bloom. Below that, only the
near field's base (G-buffer, the shadow pass at the profile's map size, vanilla passes) remains: on hardware where
that alone misses the target (an Intel UHD at 1080p), pick the LOW profile (smaller shadow map and distance) or a
lower render distance; the controller says so in its log line (`tier 1`, level ~0.07).

## Server / LAN host

`HostLoad`: hosting LAN guests (integrated server published with someone connected), the client's exact far-field
columns and the tile server's (which compute for the guests; the tile cache is shared) get an eighth of the cores
each instead of a quarter, one thread each while ticks run over 40 ms, and the client's far-field worker rests
40-200 ms between passes. Dedicated servers keep a quarter (one thread under load). Pregeneration for guests runs
every second under 12 ms ticks, every 3 s under 25 ms, never above.

## Properties and diagnostics

* `-Dskycraft.quality.adaptive=false`: everything at the profile's maximum (the pack's knob texture stays blank).
* `-Dskycraft.quality.targetFps=N`, `-Dskycraft.quality.log=true` (a status line every 10 s:
  phase, class, target, budget, GPU / aether ms, level, tier, every knob).
* Pack options: `ADAPTIVE_QUALITY` (off: fixed counts) and `QUALITY_FALLBACK` (share of the counts without the mod:
  0.65 on ADAPTIVE, about HIGH; 1.0 on the fixed profiles). The ADAPTIVE profile (the defaults) gives ULTRA's loop
  ceilings with HIGH's shadow map; the fixed profiles are ceilings the controller scales down from.

## Verification

* Unit tests (`mod/src/test/.../render/quality/QualityControllerTest`): knob curves and ladders; the allocator stays
  in budget and beats uniform scaling; simulated machines (GTX 1080 with mis-guessed costs, RTX 3090, a machine 9x
  slower than a 1080, CPU-bound frames) settle inside the budget without popping, hold steady (hysteresis), fall back
  to tiers 1 and 2 and come back, never degrade a CPU-bound frame; calibration behind a cover and the quick pass go
  live; profile round trip; GPU classification of the target machines; host-load policy.
* `py -3 native/tools/pack_lab_validate.py --profile ALL`: every program of every profile compiles.
* `pack_lab ... q=<v> | q* keys | sweep=FILE | gputime=N` and `pack_lab_quality.py [--smooth]`: the knobs offline.
