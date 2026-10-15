# T10 — Continuous Latitude Skybox & Aurora, Oculus Shader Compatibility, Distant Horizons Blending & Server-Synced Moving Weather Fronts

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **CRITICAL SHADER BYPASS (`SkyRenderer.java:66`, `CloudRenderer.java:48`, `AtmosphereConfig.java`, `pack/config/oculus.properties`):** Because the modpack enables `ComplementaryReimagined` by default in `oculus.properties` and both `SkyRenderer` (`SKIP_WITH_SHADERS = true`) and `CloudRenderer` (`CLOUDS_SKIP_SHADERS = true`) return immediately when `ShaderCompat.shadersActive()` is true, **players running the default modpack NEVER see Masser, Secunda, the 13 Birthsign constellations, auroras, procedural multi-layer clouds, valley fog banks, mountain lenticular caps, Red Mountain's ash plume, sandstorm/ashstorm walls, or `DistantStorms` lightning!**
2. **Discrete Aurora Toggle Instead of Continuous Latitude (`SkyGeometry.java:249-309`, `AtmosphereClientEvents.java:48-63`):** Aurora visibility is driven by a discrete boolean province check (`"skyrim"` / `"atmora"` / `profile.aurora()`) at fixed elevation angles (`16°..34°`) instead of shifting continuously by world-`Z` latitude from overhead coronas in Atmora/northern Skyrim down to a low northern horizon glow in Cyrodiil/High Rock/Morrowind.
3. **Static Region Weather Timers & Unwired NPC/Spawner Reactions (`RegionalWeather.java:72-145`, `Schedule.java`, `ContextualDialogue.java`, `RegionSpawner.java`):** `RegionalWeather` rolls independent static timers per region polygon instead of moving synoptic weather fronts across the map, and NPC schedules, dialogue, and creature spawners ignore `RegionalWeather.at(level, pos)`.

`T10` makes the custom skybox, moons, constellations, latitude-continuous auroras, multi-layer clouds, valley fog, Distant Horizons LOD haze, and server-synchronized moving weather fronts work seamlessly both with and without shaders.

## 2. Write Set

* `mod/src/main/java/com/skycraft/atmosphere/{AtmosphereConfig,RegionalWeather,WeatherFront,WeatherPackets,WeatherProfile}.java`
* `mod/src/main/java/com/skycraft/atmosphere/client/{SkyRenderer,SkyGeometry,CloudRenderer,CloudBuilder,CloudClimate,ClientWeather,AtmosphereClientEvents,DistantStorms,ShaderCompat}.java`
* `mod/src/main/java/com/skycraft/society/{Schedule,ContextualDialogue}.java`
* `mod/src/main/java/com/skycraft/creatures/spawn/RegionSpawner.java`
* `pack/config/{ComplementaryReimagined_r5.9.3.zip.txt,DistantHorizons.toml}`

## 3. Implementation Steps

1. **Oculus / Complementary Reimagined Shader Compatibility & Distant Horizons Fog Sync (`SkyRenderer.java`, `CloudRenderer.java`, `AtmosphereConfig.java`, `pack/config/**`):**
   * Set `SKIP_WITH_SHADERS = false` and `CLOUDS_SKIP_SHADERS = false` in `AtmosphereConfig.java`.
   * In `SkyRenderer` and `CloudRenderer`, when `ShaderCompat.shadersActive()` is true, bind shader-safe programs/states (`GameRenderer.getPositionColorShader()` / `getPositionTexColorShader()` at `AFTER_SKY` / `AFTER_WEATHER` / `AFTER_LEVEL` with additive/alpha blend and depth-clamp) and configure `pack/config/ComplementaryReimagined_r5.9.3.zip.txt` (`CLOUD_STYLE_DEFINE=0`, custom moon/sky overlay friendly settings) so Masser, Secunda, constellations, auroras, multi-deck clouds, valley fog banks, mountain caps, and distant storm walls render cleanly with Complementary Reimagined and blend smoothly into Distant Horizons LODs.
2. **Continuous World-`Z` Latitude Aurora, Star Dome Tilt & Regional Scattering (`SkyGeometry.java`, `SkyRenderer.java`, `AtmosphereClientEvents.java`, `ClientWeather.java`):**
   * Compute continuous normalized latitude from the player's world `Z` coordinate:
     * `lat = clamp((-playerZ + 4500.0) / 11000.0, 0.0, 1.0)` (`Z = -6500` in Atmora $\rightarrow$ `lat = 1.0`; `Z = -2000` in Skyrim $\rightarrow$ `lat = 0.60`; `Z = +1500` in Cyrodiil $\rightarrow$ `lat = 0.27`; `Z >= +4500` in southern Tamriel $\rightarrow$ `lat = 0.0`).
   * Drive `SkyRenderer` aurora elevation angle (`elevMin = 4° + 32° * lat`, `elevMax = 14° + 58° * lat`), curtain height, and base alpha continuously from `lat` (modulated by clear night sky and local weather), so as you walk south from Skyrim into Cyrodiil or Hammerfell, the aurora gradually sinks toward the northern `-Z` horizon before vanishing in the southern tropics—never popping in or out at region borders!
   * Blend atmospheric Rayleigh/Mie sunset/sunrise scattering, cloud under-lit colors, and Distant Horizons fog density/tint across a 9-point spatial kernel (`±96` blocks) sampling `heat`, `rainshadow`, `coastDistance`, and `WeatherFront` proximity (with distinct marine haze/sea-breeze cloud decks over oceans and beaches vs. inland mountains and deserts).
3. **Server-Synchronized Moving Weather Fronts & World/NPC Reactivity (`WeatherFront.java`, `RegionalWeather.java`, `WeatherPackets.java`, `Schedule.java`, `ContextualDialogue.java`, `RegionSpawner.java`):**
   * Maintain a server-authoritative list of active `WeatherFront` cells (`centerX`, `centerZ`, `radius`, `velX`, `velZ`, `kind`, `intensity`, `ticksRemaining`) in `RegionalWeather`, advecting each front along prevailing regional winds every tick and syncing active fronts to all clients via `WeatherPackets`.
   * Anchor `CloudBuilder.dustAndAsh()`, `curtains()`, and `cumulonimbus()` to the live `WeatherFront` boundaries so players see approaching Alik'r/Elsweyr dust storms, Morrowind ashstorms, Skyrim/Atmora blizzards, and coastal sea squalls moving across the horizon miles before arrival.
   * Wire `RegionalWeather.at(level, pos)` into:
     * `Schedule.activityAt()` (sending non-guard citizens indoors to homes/inns during severe `BLIZZARD`, `SANDSTORM`, or `ASHSTORM`),
     * `ContextualDialogue.java` (weather-reactive lines for blizzards, sandstorms, ashstorms, thunderstorms, and coastal squalls),
     * `RegionSpawner.java` (adjusting surface creature spawns during active storms).

## 4. Verification Gates

* Tier 0 / server tests verify:
  * Latitude aurora elevation and intensity vary continuously and monotonically across `Z = -6000, -2500, -500, +1500, +6000` with `0` step discontinuities at region borders,
  * `WeatherFront` cells advect across world coordinates over ticks and serialize/deserialize cleanly in `WeatherPackets`,
  * `Schedule.activityAt()` routes citizens indoors during `BLIZZARD`, `SANDSTORM`, and `ASHSTORM`,
  * `SKIP_WITH_SHADERS` and `CLOUDS_SKIP_SHADERS` are `false` and `pack/index.toml` is refreshed.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
