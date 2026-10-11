# Full-pack playtest: Skycraft in the real modpack

This QA pass ran Skycraft Core inside the real client pack, launched through Prism Launcher. The pack was Forge
47.4.10 with Distant Horizons 3.3.3, Oculus 1.8.0 and Complementary Reimagined r5.9.3, Embeddium 0.3.31, Sodium
Dynamic Lights, ImmediatelyFast, ModernFix, FerriteCore, Saturn, EntityCulling, Canary, Krypton Reno, Essential,
Better Combat, Lootr, ItemPhysic Lite, Controllable, Default Options and spark. The game was driven by Skycraft's
own autotests. Date: 2026-10-10/11.

## How it was run

* **Instance.** `mod/gradlew build`, then `tools/build_prism_instance.py`. The zip was unpacked to
  `PrismLauncher/instances/Skycraft-QA` and launched with `prismlauncher.exe --launch Skycraft-QA`. Prism used
  the default Microsoft account, with no login prompt.
* **Autotest flags.** These went into the instance's `JvmArgs`:
  * `-Dskycraft.autotest=true -Dskycraft.autotest.world=QA`
  * `-Dskycraft.autotest.tour=Name:x:z,...` and `-Dskycraft.autotest.tourWait=<ticks>`
  * the new `-Dskycraft.autotest.tourFar=160` and `-Dskycraft.autotest.shaderToggle=true` (see below)
* **Visual test.** `skycraft-visualtest.txt` in the game directory runs `VisualAutotest`: night sky, the seven
  regional weathers, the HUD, banners and menus. It was run with shaders on and with shaders off.
* **QA-only overrides.** These were not shipped:
  * `config/dynamic_fps.json` with `"enabled": false`. Dynamic FPS drops to 1 fps or 0 fps when the window isn't
    focused, which ruins screenshots.
  * `options.txt`: `enableVsync:false`, `maxFps:260` and `pauseOnLostFocus:false`. One run paused itself when the
    window lost focus, and the paused integrated server also stops Distant Horizons generation.
  * DH `showGenerationProgress = "LOG"`, to log generation speed.
* **Hardware.** i9-10900K (20 threads), RTX 3090, 64 GB RAM, Windows 11. The window was 1920×1080 with GUI scale
  auto (4), render distance 10 and a 6 GB heap. Other agents' Gradle builds were running at the same time, so
  CPU-bound numbers are pessimistic.
* **Tour.** Whiterun (62,−68), the Throat of the World (579,652), Riften, Solitude, Markarth, the Imperial City
  (900,5900), Vvardenfell (7100,−400), Akavir (17600,2800) and a generated realm (−40000,40000). Each spot got a
  ground shot, an air shot (+70) and a far shot (+160, at the horizon). The last shot was repeated with shaders
  off through the Iris API.

## Results at a glance

| Check | Result |
| --- | --- |
| Crashes, mixin conflicts, exceptions on Skycraft or DH worker threads | **None.** There are no Skycraft stack traces and no exceptions on DH world-gen threads in about 75 minutes of play, including FEATURES-mode LOD generation of every province, Akavir and a generated realm. |
| Embeddium + Oculus + Skycraft rendering (rig models, armour, HUD, particles, weather) | Works. The only warnings are Embeddium's expected "tainted by Oculus/Sodium Dynamic Lights" warnings. |
| Skycraft custom sky with shaders | **Was broken, now fixed.** The sky was drawn on top of the shader pack (see Fix 1). |
| Clouds cutting through mountains | **Fixed.** Clouds now sit at y 330, for vanilla, Embeddium and Complementary (see Fix 2). |
| Vanilla boss bars (Alduin at Helgen) under the compass | **Fixed** (see Fix 3). |
| Essential + Skycraft title and pause screens | No conflict. Skycraft's pause menu has its own *Multiplayer / Invite friends* entry. |
| Better Combat, Controllable, Lootr, ItemPhysic | Loaded with no errors. Combat itself was not exercised by the autotest. |
| Prism launch without a person | Works. The first launch crashed in Forge's early window once (see Known issues). The relaunch was fine. |

## Fixes made

1. **The Skycraft sky drew over shader packs.** `atmosphere/client/ShaderCompat.java`
   * *Symptom.* Thread dumps showed `SkyRenderer.drawSecunda` running inside Oculus' `ExtendedShader` while
     Complementary was active. The aurora showed up as a slanted green quad in front of the shader sky (screenshot
     09, left).
   * *Cause.* `lastCheck` started at `Long.MIN_VALUE`, so `now - lastCheck` overflowed to a negative number. The
     "checked less than a second ago" test was therefore always true, and the cached `false` was returned
     forever. The `skipWithShaders` option never worked.
   * *Fix.* A `checked` flag replaces the sentinel.
   * *Verified.* The new autotest field `skySeesShaders` now follows Oculus: `true` with shaders on, `false` after
     toggling them off.
2. **Cloud height 330.** New `atmosphere/client/OverworldClouds.java` and a new `sky.cloudHeight` option (default
   330) in `skycraft-atmosphere-client.toml`.
   * It re-registers the `minecraft:overworld` effects as vanilla `OverworldEffects`, changing only
     `getCloudHeight()`. Vanilla, Embeddium and Oculus all read the height from there.
   * Oculus passes it to shaders as the `cloudHeight` uniform. Complementary places its clouds at
     `CLOUD_ALT1 + (cloudHeight − 192)`, so the shipped preset keeps `CLOUD_ALT1` at 192 and the clouds land at
     330 automatically. They will follow if the terrain team changes the height again; the dimension JSON is
     untouched.
   * Peaks above 330 (the Throat at about 380) now rise through the cloud deck (screenshots 04 and 07).
3. **Boss bars under the compass.** New `client/hud/BossBarOffset.java`.
   * Vanilla boss bars that `SkyBossHud` doesn't replace are drawn from y 12, right under the compass (y 8–21).
     That includes quest bosses with a literal name, such as *Alduin* during the Helgen intro, raids and other
     mods' bosses.
   * They are now shifted 26 px down, to where Skycraft's own boss bar sits (screenshots 09 → 10).
4. **Autotest additions** in `client/autotest/ClientAutotest.java`. They change nothing in normal play.
   * `-Dskycraft.autotest.tourFar=<blocks>`: an extra horizon shot per spot.
   * `-Dskycraft.autotest.shaderToggle=true`: repeat the last shot with shaders off, through the Iris API, by
     reflection.
   * A per-shot perf line in `latest.log`:
     `Autotest perf: <shot> shaders= skySeesShaders= fps_now= fps_avg= fps_min= heap=`.
   * The tour code is now an explicit per-spot state machine.

## Shipped pack settings

* **`pack/config/oculus.properties`** (new). Keys were verified against Oculus 1.8's `IrisConfig`:
  `enableShaders=true`, `shaderPack=ComplementaryReimagined_r5.9.3.zip`, `disableUpdateMessage=true`. Players
  toggle shaders with **K** or in *Video Settings → Shader Packs*.
* **`pack/shaderpacks/ComplementaryReimagined_r5.9.3.zip.txt`** (new). This is the shader option preset. Oculus
  reads `shaderpacks/<pack file>.txt`, confirmed in `Iris.class`. Options were checked against the pack's
  `shaders/lib/common.glsl` value lists.

  | Setting | Value |
  | --- | --- |
  | Clouds | Unbound volumetric clouds (`CLOUD_STYLE_DEFINE=3`), `CLOUD_UNBOUND_AMOUNT=1.10`, `CLOUD_SHADOWS=true`. Altitude follows the world (330, see Fix 2). |
  | Fog | `ATM_FOG_DISTANCE=200` and `ATM_FOG_MULT=0.80`. Run 1 used the defaults, 100 and 0.95, and a 1–2 km haze swallowed the DH horizon. `ATM_FOG_ALTITUDE=100`: mist settles into Skyrim's valleys (y 100–130) instead of at sea level. |
  | Water | Unbound style (`WATER_STYLE_DEFINE=3`). Waving water, foliage and leaves on. |
  | Night | Unbound aurora in snowy biomes (`AURORA_STYLE_DEFINE=2`, `AURORA_CONDITION=2`) and night nebulae. These replace Skycraft's own aurora and galaxy, which are skipped while shaders are on. |
  | DH compatibility | Automatic. Oculus defines `DISTANT_HORIZONS` and Complementary switches to its DH fog and depth path. The log shows "DH Iris events bound". |

* **`pack/config/DistantHorizons.toml`.**
  * `generatorPlan = "SURFACE_THEN_CHUNKS"` is now explicit and commented.
  * `[common.logging.warning] showGarbageCollectorWarning = false`. The chat told every player to switch to
    ZGC or Shenandoah, which they can't act on (screenshot `t00`).
  * The LOD radius stays at 128 and the thread preset stays at `LOW_IMPACT`. The numbers below are the reason.
* **`tools/refresh_packwiz.py`** now also indexes `shaderpacks/*.zip.txt`. Before, the preset would not have been
  shipped. `pack/index.toml` and `pack/pack.toml` were refreshed.

## Numbers

### Frame rate

1920×1080, RTX 3090, uncapped. These are run 2 averages over the dwell time at each spot. Whiterun in run 2 was
measured while `jstack` sampled the JVM every 1.5 s, so it is excluded.

| Spot | Shaders on (Complementary HIGH + preset), ground / air / far | Shaders off |
| --- | --- | --- |
| Markarth | 158 / 160 / 191 | 228 |
| Riften (forest, autumn birch) | 130 / 150 / 146 | 135 |
| Throat of the World (above the clouds) | 81 / 92 / 149 | 201 |

* The shaders-off numbers are taken 8 s after the toggle, while DH reloads its render data, so they are lower
  than steady state.
* Run 1, with vsync and a 120 cap, held 70–115 fps everywhere. It dipped to about 40 in Markarth with the default
  profile.
* `fps_min` is 0 at most spots because each teleport loads chunks synchronously; that is a teleport hitch, not a
  play hitch.

### Memory

The 6 GB heap stayed between 1.6 and 4.1 GB used during the whole tour. 6 GB is enough at DH radius 128.

### Load time

148 s from launch to in-world on a fresh instance (ModernFix measured it). 40 s of that is title screen to
in-world, creating a new world.

### Distant Horizons generation

* FEATURES mode ran at **4 → 60 chunks/s**, rising after the first minute. A full 128-chunk radius around a new
  spot is still 1–4 h of background work. Near terrain fills in within a minute.
* Thread dumps (180 samples) show DH's 10 world-gen threads **parked 89% of the time**, waiting on DH's own task
  queue. Skycraft's generator is not the bottleneck, so a heavier thread preset would not help.
* Inside the busy samples, about 13% is `TamrielHeight.height` and about 13% is `TamrielBiomeSource`.
  `TamrielHeight.height` is called uncached outside `NoiseChunk`: the `cache_2d` marker is a pass-through for
  DH's single-point contexts and the climate sampler. A small per-thread memo for the last (x, z) in
  `TamrielHeight` would cut that. It was left for the terrain owners.

### Server thread

* "Can't keep up" spikes of 2–8 s happen right after each long-distance teleport (synchronous chunk loads in
  `/tp`) and during chunk saves. One 22 s spike was seen in the first minutes of a fresh world while spawn
  generation and DH started together.
* Outside those spikes no lag warnings were logged. `NpcEntity.tick` (vanilla mob AI and collisions for town NPCs) is the largest
  Skycraft cost, at about 14% of server samples.

## Visual review

Every screenshot was looked at. These are the ones kept:

| File | What it shows |
| --- | --- |
| `screens/fp_01_whiterun_air_shaders.jpg` | Whiterun from 70 m: volumetric clouds, shadows, DH horizon. |
| `screens/fp_02_whiterun_far_shaders.jpg` | Horizon from 160 m with the new fog settings. LOD forest is continuous. |
| `screens/fp_03_whiterun_far_noshaders.jpg` | The same view without shaders: Skycraft sky and fog, DH LODs. |
| `screens/fp_04_throat_above_clouds.jpg` | The Throat of the World above the cloud deck at 330. |
| `screens/fp_05_riften_far_lods.jpg` | The Rift: autumn birch and pine LODs to the Velothi range. |
| `screens/fp_06_reach_air.jpg` | The Reach crags around Markarth. |
| `screens/fp_07_clouds_at_330_default_profile.jpg` | Run 1, default Reimagined clouds already at 330 (Fix 2), before the preset. |
| `screens/fp_08_dh_rough_surface_seam.jpg` | DH's rough surface pass sits below the real Helgen chunks, so a raised forest block with cliffs shows for the first minute. It is gone after the chunk pass (fp_03). |
| `screens/fp_09_bossbar_under_compass_before.jpg` | *Alduin* boss bar under the compass, and Skycraft's aurora drawn through Complementary (Fix 1). |
| `screens/fp_10_bossbar_below_compass_after.jpg` | Boss bar moved below the compass (Fix 3), shaders off, snow weather. |

### Other observations

* **No z-fighting or missing textures.** None were seen on terrain, settlements, rig creatures, armour, items or
  GUI.
* **DH LOD seams.**
  * During the first minute in a new area, the rough surface pass is a few blocks off the real terrain and has no
    trees (fp_08). The chunk pass replaces it, and no permanent seams were found at region borders: Whiterun and
    Falkreath, the Rift and Eastmarch, the Reach.
  * Right after a teleport, a few vanilla-range chunks show as square pits in water before they mesh (the
    Imperial City's lake). This is transient.
  * Toggling shaders makes DH drop and rebuild all its render data (about 10 s).
* **Regional weather with shaders.**
  * Snow, ash and sand particles and rain all render.
  * The fog density and tint (blizzard, fog, ash storm, sandstorm) are almost invisible under Complementary, which
    computes its own fog and ignores vanilla fog parameters. Without shaders they work.
  * Complementary's *Special Cold/Dry Weather* is biome-based, so snowy Skyrim biomes still get a snowstorm look.
* **HUD at 1080p, GUI scale auto (4).** The chat's last line overlaps the survival bars (hunger, fatigue, cold) at
  the bottom centre while chat messages are visible, for example the Helgen intro narration. Compass, target bar,
  banners, sneak eye, inventory, race screen and pause screen were all fine.
* **Oculus log warnings** are harmless: `Unknown variable BIOME_PALE_GARDEN / BIOME_SULFUR_CAVES /
  endFlashIntensity`. Complementary r5.9.3 references uniforms from a newer Iris than Oculus 1.8. DH also logs
  "Partial Oculus support enabled".

## Known issues (not fixed)

1. **Forge early-window crash on the very first launch** when Prism passes a non-default window size.
   * `FMLConfig.getConfigValue(...)` returns null: Forge corrects the freshly created `fml.toml` while the early
     window reads it.
   * It happened once, with a 1920×1080 window override. The relaunch worked.
   * Default Prism window settings don't trigger it. Shipping a complete `config/fml.toml` would avoid it.
2. **Regional fog doesn't show under shaders** (above). A fix needs shader-side support. One option is a custom
   uniform via Oculus/Iris, which Complementary doesn't read.
3. **Chat overlaps the survival bars** at GUI scale 4. Move the bars, or offset chat in the survival HUD.
4. **DH generation speed** is limited by DH's own scheduler (above). `TamrielHeight` memoisation is a cheap win
   for the terrain module.
5. **Sky with shaders.** With shaders on, Skycraft's Secunda, Masser halo and constellations are not drawn (by
   design). Complementary draws its own sun, moon, aurora and nebulae instead. Whether its moon picks up the retextured Masser texture was not checked: the night-sky shots were taken under forest canopy.
6. **The Throat of the World summit sits at y 380 with the world top at 383**, so summit builds have little
   headroom. This is expected to change with the planned 704-block height.

## Recommended settings for players

| Machine | Settings |
| --- | --- |
| Desktop GPU (RTX 2070 or better) | Shaders on, the shipped preset, Complementary profile HIGH, DH radius 128–256. If raising DH beyond 128, raise the heap to 8 GB. Render distance 10–12. |
| Mid-range or laptop | Shaders on with profile MEDIUM, or LOW (shadow distance 96). DH radius 128. |
| Steam Deck or iGPU | Shaders off (press K once; Oculus remembers). DH radius 64–128, `LOW_IMPACT`, render distance 6–8. |
| Everyone | Leave `pauseOnLostFocus` on for normal play. Expect distant LODs to keep filling in for a while in each newly visited region; trees appear in the second pass. |
