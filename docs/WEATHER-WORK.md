# Weather work: the hooks the renderer gives it

The owner's order (2026-10-20): the renderer first, then weather, then the world and biome rework. The renderer owns the
sky and the light: it builds every capability now (layered and regional air, clouds lit as the low sun's screen, overcast
diffusion, crepuscular rays, fog as a medium, wind-driven water, wetness, snow) with inputs that default to clear,
calm weather. Weather and clouds only feed those inputs, through the hooks below. This file is that interface: what
each hook is, what the renderer does with it, and what weather must supply. Keep it current: when a renderer change
adds an input weather will drive, list it here.

## The air (sky, sun, haze, the light): the inputs it waits for

The physical atmosphere (native/src/air.hpp, native/shaders/atlas_air.slang, shaders/include/atlas_air.glsl; Rayleigh,
Mie, ozone, multiple scattering) takes a climate description (atlas_air_from_climate, ABI 0.16). Today it is fed from
the eye's weather column only.

- **Regional air.** Only the eye's air is used: distant haze, sky and light do not vary by region. Needed: the far
  field's aerial perspective reading the air per region (the weather grid's columns), so a dusty valley ahead and a
  clear ridge behind look different.
- **Layering.** Night cooling and inversions trap haze low (milky inside the layer, clear above it, a lit layer seen from
  above); daytime mixing lifts and spreads it; rain washes aerosols out. The model takes one homogeneous mix per eye:
  it needs a boundary-layer height and a mix below and above it.
- **Morning versus evening.** Same optics, different air columns: the weather model's diurnal cycle (humidity, mixing,
  emissions) sets the mix; nothing in the renderer chooses sunrise or sunset colours.
- **Pressure.** Fixed at standard; molecule density follows temperature. Weather systems (highs, lows) feed it.
- **Inputs not yet verified live:** ClientWeather's fog, sand, ash and snow values as the air model reads them; the humid
  and dusty presets were tuned by eye offline.
- **Cost:** a change of air rebuilds the multiple-scattering LUT on the render thread (~10 ms, at most every 3 s): move
  it to a worker or the GPU when weather changes the air often.

## Sun and moon under weather (the owner's notes, 2026-10-20)

Four mechanisms, the same for the sun and the moons (moonlight is reflected sunlight): particles filter, dim and redden;
clouds block or act as a screen; ice crystals refract into halos and arcs; rain cleans the air.

Renderer capabilities (built in the sky now, inputs default off or clear; weather sets them):
- **Ice-crystal optics:** the 22 degree halo (white, faint colour), sundogs 22 degrees either side (red nearest the
  sun), sun and light pillars with a low sun; for the moons too. Input: ice crystals aloft (cirrus, cirrostratus) and
  near the ground (diamond dust in the cold).
- **Aerosol layers aloft:** a smoke or dust layer at altitude (e.g. smoke 3-6 km up reddening sunsets far downwind while
  the air below is clean). Input: layer height, depth, kind, amount.
- **Already in the air model:** dust, smoke, sea salt, volcanic, rain-washed mixes (the climate input), the reddened
  dim disc through heavy extinction.

Weather supplies: where cirrus lies (halos often precede warm fronts), smoke plumes and their drift over long
distances, dust events (desert dust crossing seas), fog and storm cover (the disc hidden, a grey sky), clearing after
rain (vivid, clean sunsets), cloud breaks near the horizon letting the low light under the clouds, diamond dust in
the cold. Regional tendencies (dusty, smoky, polluted, maritime, cirrus-prone) are what is in the air, overridden by
the day's weather.

## Clouds: the screen for the low sun's colours, and the light's diffuser

- Weather visuals are **off** for the renderer work (WeatherFieldTexture.isOff, the `weather.visuals` option): no
  cloud cover, storm towers, rain curtains or fog banks reach the frame. Turning them on is the start of this work.
- **Clouds as the screen:** thin and broken clouds lit by the reddened low beam, pink and orange undersides after
  sunset, grey where the light is blocked (the owner's sunset photos). Atlas clouds (atlas_clouds.slang, FRAME_CLOUDS)
  must take the air's transmitted sun colour per cloud height.
- **Cover sets the light's character:** overcast turns the direct sun into diffuse sky light: softer, weaker shadows,
  cooler colour, lower contrast, shorter visibility. Today the key light only knows the air, not the cloud cover.
- **Cloud shadows** exist in atlas (cloudShadow) on land, water and the near light; the water's caustics drop the cloud
  factor when they encode the water chord (atlas_far.slang nearLightAt): carry it.
- **Two cloud renderers:** NirnCraft's cloud deck (CloudRenderer, OverworldClouds) steps aside when atlas lights the
  near pass; decide on one.

## Light in the air and in the water

- **Beams** (near_resolve.fsh airBeams): the air's scattering is a constant (0.004 a block). Humidity, dust, mist,
  smoke and the boundary layer should set it: misty forest mornings full of shafts, dry noon air almost none.
- **Fog banks and ground mist:** the weather grid has a ground fog field (WeatherFieldTexture displayFog); render it as
  a medium (lit by the sun and sky, shadowed, with the beams through it).
- **Underwater shafts and caustics:** their strength follows the light above (overcast: none; clear noon: sharp). The
  ray-marched shafts are off until atlas gives sun visibility in the water (docs/ATLAS-RENDERER-PLAN.md water list).
- **Aurora:** its strength should follow the sky's clarity and cloud cover; the drawn sky (aurora, stars, moon glow)
  is to be integrated into the ambient light (planned in the renderer roadmap), so an aurora night lights the snow.

## Water and surfaces under weather

- **Wind waves:** atlas's far water already reads a wind field (farWaterWind); the near water's waves (near_forward
  waveNormal) are a fixed set of octaves: drive both from the weather's wind (calm mornings mirror-flat, storms rough).
- **Rain on water:** ripple rings, a rougher surface, the reflection broken up.
- **Wetness:** darker, glossier surfaces in and after rain, puddles in hollows, drying over time.
- **Snow:** atlas's snow cover is statistical (AtlasSnowCover); accumulation and melt with the weather; ice on still
  water in the cold.
- **Rain and snow themselves:** vanilla's weather rendering is not yet owned by our pipeline (lit by our light, through
  our haze, far rain curtains in atlas).
- **Lightning:** the flash lighting land, clouds and water (the weather grid has a lightning packet).

## Performance found during the renderer work

- WeatherFieldTexture.update runs on the render thread every tick over 2 x 128 x 128 cells (the presentation loop:
  lerps, packing, an atan2 per cell). The per-cell config read is hoisted; the loop itself belongs on the GPU or a
  worker.
- The weather grid's sampling (WeatherPhysics.evaluate per column) runs on a worker (~27 ms a grid): fine, but it
  should feed the GPU directly.

## Not weather, recorded elsewhere

- Per-biome water character (clarity, colour, underwater look) and long blends at biome borders: started (the biomes'
  water colour averaged around the camera, eased over seconds); the biome rework owns the data.
- The world generated on the client and GPU (the client owns the world; the server sends coalesced operations): the
  world rework (docs/ATLAS-RENDERER-PLAN.md, WORLDGEN-REVIEW.md).
