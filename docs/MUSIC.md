# Skycraft audio

Skycraft has its own audio: a situational soundtrack with music for every province of Tamriel, an ambience engine,
environmental reverb and occlusion, and sound effects for creatures, dragons, shouts, spells and menus. Every sound
that ships with the mod is **original**. Small Python programs in `tools/music/` compose and synthesise all of it. None
of it is sampled from, transcribed from or derived from Skyrim's audio.

| Part | Code | Generator |
| --- | --- | --- |
| Soundtrack | `world/client/MusicController.java` | `tools/music/compose.py` |
| Ambience beds and calls | `audio/client/AmbienceEngine.java` | `tools/music/synth_ambience.py` |
| Reverb and occlusion | `audio/client/Acoustics.java`, `ListenerEnvironment.java` | none |
| Creature, shout, spell and UI sounds | `audio/AudioSounds.java` | `tools/music/synth_skyrim_sfx.py` |
| Sound events, files and subtitles | `assets/skycraft/sounds.json`, `lang/skycraft/audio.json` | `tools/music/sound_catalog.py` |
| Your own Skyrim audio (personal use) | none | `tools/music/import_skyrim_audio.py` |

Client settings live in `config/skycraft-audio-client.toml`. The soundtrack's master switch is `world/atmosphere.music`
in `skycraft-world-client.toml`.

## The soundtrack

The `MusicController` checks the situation once a second and plays a `skycraft:music.*` event.

* **Exploration** plays by day and by night, using the music set of the region you are in (`Region.music()`).
  * Each set has its own day and night piece:
    * `skyrim`, which uses the historical `music.explore` and `music.explore_night`;
    * `cyrodiil`, `morrowind`, `black_marsh`, `elsweyr`, `valenwood`, `summerset`, `hammerfell`, `high_rock`, `atmora`
      and `akavir`.
  * Some lands borrow another set: Yokuda uses Hammerfell's, Pyandonea uses Summerset's, and Thras and the open
    sea use the `sea` set.
  * Generated realms pick a set from their palette: frozen lands get Atmora's, ash lands Morrowind's, desert
    Hammerfell's, jungle Valenwood's, and so on.
  * Anything unknown falls back to Skyrim's set.
* **Sailing:** out on open water, the `sea` set plays (`music.explore.sea` / `music.explore_night.sea`).
* **Towns** use the culture of the settlement, taken from the Tamriel place or else the region:
  * Nord and Orc towns play `music.town`.
  * Imperial and Breton towns play `music.town.imperial`.
  * Dunmer towns play `music.town.dunmer`.
  * Khajiit, Redguard and Yokudan towns play `music.town.desert`.
  * Altmer, Bosmer and Maormer towns play `music.town.elven`.
  * Argonian and Akaviri towns play their province's exploration music.
* **Dungeons:**
  * Nordic barrows and generic dungeons play `music.dungeon`.
  * Dwemer ruins play `music.dungeon.dwemer`.
  * Ayleid ruins play `music.dungeon.ayleid`.
  * Deep glowing caverns play `music.blackreach`.
* **Combat** only starts in a real fight: you hit something or were hit recently (the server's in-combat flag), or a
  hostile mob in attack mode is facing you within 20 blocks and can see you. Being "detected" doesn't count. Combat
  music holds for 8 seconds after the last sign of a fight.
* **Bosses:** dragons that are attacking, or close by, play the dragon battle theme (`music.boss`). Other bosses
  shown by the boss bar, such as dragon priests, deathlords and giants, play `music.boss.battle`.
* **Oblivion** (the Nether) and **Sovngarde** (the End) have their own music.
* **Stings:**
  * The discovery sting plays on "DISCOVERED".
  * The level-up fanfare is played by the atmosphere module.
  * The soundtrack ducks under both.

How pieces start and stop:

* **Same mood:** day versus night, or one province versus the next, waits for the current piece to end.
* **New mood:** other changes crossfade, and combat cuts in at once.
* **Silence:**
  * After an exploration piece there are 20–70 seconds of silence (configurable).
  * Towns and dungeons pause for less time.
  * Combat music loops straight away.
* **Repeats:** a piece is not repeated back to back when its event has others.
* **Title music:** it isn't cut when you load a world. It fades out under the first seconds of play.
* **Vanilla music** is muted in worlds. Vanilla menu music doesn't play over the title theme.
* **Jukebox records and performing bards** within 64 blocks (24 for bards) fade the soundtrack out until they stop.

### The original score

`compose.py` is a seeded composition engine and synthesiser.

* **Composition:**
  * It writes modal chord progressions and voice-leads them.
  * It develops random motifs into melodies and builds drum and ostinato patterns.
* **Performance:**
  * Phrases follow a velocity arc and strong beats are accented.
  * Short notes are detached and long ones held.
  * Timing follows a humanising tempo map, which slows into every section's final cadence.
* **Instruments:** every instrument is rendered from scratch:
  * layered string sections (violins, violas, cellos, basses) with bow noise, a timing spread between players, and
    brightness that follows velocity;
  * choirs of glottal sources through five-formant vowel filters, where each group of singers has its own vocal-tract
    length and long notes drift between vowels;
  * additive flute, recorder, whistle, ney, shakuhachi, reed (duduk-like), horn, low brass and fiddle;
  * Karplus-Strong harp, lute, oud (with tremolo), koto and pizzicato strings;
  * modal timpani, taiko, frame drum, bodhrán, darbuka (dum, tek, ka), water drums, clockwork, steam, crystalline bells
    and throat-singing drones (a formant gliding over the overtones above a sub-harmonic growl).
* **Space:** early reflections plus a Freeverb tail (computed in closed form), a decorrelated stereo return, and a
  gentle M/S widening that keeps the bass centred.

| Set | Character |
| --- | --- |
| Skyrim (`explore_day_1..3`, `explore_night_1..2`) | Dorian, Aeolian and Mixolydian; flute, horn and fiddle over drones, harp and choir |
| Cyrodiil | Lydian; layered strings, harp and choir pads, horn and flute; heroic and pastoral |
| Morrowind | Phrygian dominant (hijaz); drones, ney and reed, frame drum, darbuka, metal and bells |
| Black Marsh | low drones, water drums, a dark reed and ney |
| Elsweyr | hijaz; oud accompaniment and tremolo melodies, frame drums, ney |
| Valenwood | Mixolydian 6/8; flute, recorder and reed over harp and pizzicato |
| Summerset | Lydian; crystalline bells, harp, a high choir |
| Hammerfell | maqam (Phrygian with a half-flat second in the melody; the harmony avoids that degree); oud, ney, reed, darbuka |
| High Rock | Dorian jig; lute, bodhrán, recorder and fiddle |
| Atmora | throat-singing drones, horns, a low male chant, wind |
| Akavir | yo scale (day) and in scale (night) on koto and shakuhachi over a drone, taiko |
| Sea | rolling 6/8 strings and harp, horn calls, choir |
| Towns | `town_1..2` (Nord folk), `town_imperial` (minuet), `town_dunmer`, `town_desert` (double-harmonic bazaar dance), `town_elven` |
| Dungeons | `dungeon_1..2` (Nordic), `dungeon_dwemer` (clockwork, steam, metal), `dungeon_ayleid` (glass and choir), `blackreach` |
| Danger | `combat_1..2`, `boss_dragon`, `boss_battle` |
| Realms and stings | `oblivion_1`, `sovngarde_1` (also the title theme), `discovery`, `level_up` |

**Size budget.**
* Pieces are 1:45–2:45 long and are synthesised at 32 kHz.
* Long pieces are encoded at 24 kHz, Vorbis q1 (about 40 kb/s stereo). Combat pieces use q1.5 and stings q3.
* The 46 tracks total about 28 MB in the jar.

To regenerate (about 10 minutes with `-j 5`), you need Python 3 with numpy and an `ffmpeg` with libvorbis. The tools
find ffmpeg through the `FFMPEG` environment variable, `PATH` or a few known install locations (see `audiolib.py`).

```
py -3 tools/music/compose.py -j 5                   # all tracks
py -3 tools/music/compose.py morrowind_day --wav out  # one track, and keep a WAV copy
py -3 tools/music/compose.py --list
```

The seeds are fixed, so a run reproduces the same pieces. To get a new piece, change a track's seed or its sections.

## Ambience

The `AmbienceEngine` mixes looping **beds** (stereo, streamed, on the *Ambient/Environment* slider). It reads the
surroundings twice a second:

* the biome under you and in a ring 24 blocks around you, using vanilla tags (`#minecraft:is_forest`, `is_ocean`,
  `is_river`...), Forge tags (`#forge:is_swamp`, `is_snowy`, `is_desert`, `is_lush`...) and keywords in Skycraft biome
  names (marsh, ash, glacier, dune, jungle, glow...);
* the time of day, with smooth dusk and dawn;
* rain, snow and thunder;
* your altitude;
* caves and how deep you are;
* whether you are inside a building or underwater.

At most five beds play at once, each fading towards its target level.

| Bed | Plays |
| --- | --- |
| `wind_light`, `wind_strong`, `blizzard` | open land; stronger with altitude, on peaks and in storms; blizzards in snowy biomes when it snows |
| `forest_day`, `forest_night` | forests: leaves and birds by day, crickets at night |
| `plains_day`, `plains_night` | grassland and tundra |
| `marsh_day`, `marsh_night` | swamps: insects, water, frogs |
| `jungle_day`, `jungle_night` | jungles and rainforests |
| `desert_wind`, `ash_storm` | deserts and badlands; ashlands and volcanic land (stronger in storms) |
| `coast_waves`, `open_ocean`, `river` | at the shore, out on the water, near rivers |
| `cave`, `cave_deep`, `glow_cave` | underground, by depth; Blackreach-like glowing caves |
| `oblivion`, `sovngarde` | the other realms |

Indoors and underground, the outdoor beds drop and are low-passed (see Acoustics). Rain silences birds and insects.

**Calls** are mono one-shots placed in 3D at random spots around you:

* by day: birds, crows and hawks;
* at night: owls, crickets and Skyrim's wolves howling far away;
* in marshes and by the water: frogs, insects, loons, gulls and crashing waves;
* out at sea: whales;
* in jungles: exotic birds;
* in deserts and ashlands: sand hiss, wind gusts and a distant volcano;
* in caves: drips, rumbles, falling pebbles and crystal chimes;
* in the realms: daedric wails in Oblivion and a distant horn in Sovngarde;
* very rarely in Skyrim's wilds, a far-away dragon roar (`distantDragons`).

Vanilla wolves use Skycraft's howl and growl.

`synth_ambience.py` synthesises everything:

* noise shaping, wind-whistle resonator banks, granular insects and frogs, FM bird calls and modal drops;
* beds are 24–40 s seamless loops (equal-power crossfaded seams), stereo at 24 kHz, Vorbis q1;
* calls are mono at 32 kHz.

```
py -3 tools/music/synth_ambience.py            # everything
py -3 tools/music/synth_ambience.py wind cave  # only matching stems
```

## Acoustics: reverb and occlusion

`Acoustics` is Skycraft's own "sound physics". It uses OpenAL EFX:

* **Listener space.** Once a second, `ListenerEnvironment` casts 14 rays from the camera. It works out how enclosed
  the space is, how large it is, whether there is a roof, and whether the walls are hard (stone, metal) or soft (wood,
  earth, cloth). It also checks your depth under the surface and whether you are underwater.
* **Reverb.** Every world sound gets a reverb send while you are in an enclosed space: a short warm room in a house,
  long and dark in caves, great halls and Dwemer ruins. The single reverb effect is re-tuned (decay, density,
  reflections, HF damping) as the space changes.
* **Occlusion.** When a sound starts, a block march from you to the sound counts the solid blocks in between. The
  sound is then low-passed and slightly attenuated. Sounds outside while you are inside (rain, wind, mobs) are
  muffled, and everything is muffled underwater. The ambience beds' muffling follows you live.
* **Not affected:** music, UI sounds and stings.
* **How the filters get attached.** Forge's `PlaySoundSourceEvent` and `PlayStreamingSourceEvent` hand over the
  `Channel`, and the OpenAL source id is read from its private field `source` (SRG `f_83642_`) through
  `ObfuscationReflectionHelper`.
* **Failure handling.** If EFX is missing or anything fails, it logs once and switches itself off.
* **Settings:** `acoustics.reverb`, `reverbAmount` and `occlusion` in `skycraft-audio-client.toml`.

## Sound effects

`synth_skyrim_sfx.py` is a seeded procedural SFX synthesiser. Its toolkit:

* a glottal-pulse source with jitter and shimmer through cascaded formant resonators, for voices;
* sub-harmonic roughness for growls;
* noise sweeps, granular crackle, electric arcs, modal metal, wood and stone, booms, and short reverbs.

All of its output is mono (so it can be positioned) at 32 kHz.

Loudness is set per category: UI is quiet (about −24 dBFS) and shouts are loud (−10). It renders:

* **Creatures:**
  * Dragons: roars, growls, wingbeats, fire and frost breath, landing thud, hurt and death. A dragon also shouts its
    breath (Yol Toor Shul / Fo Krah Diin) in a deeper voice.
  * Draugr, skeevers, trolls, giants (with stomps), mammoths, frostbite spiders, wolves, bears, sabre cats, Falmer,
    Dwarven automatons (steam, clank), wisps, spriggans, hagravens, horkers, mudcrabs and ice wraiths.
* **Shouts:** every shout has a one-, two- and three-word chant (`shout.<shout>.<words>`). A phoneme parser builds
  each dragon word (plosives, sibilants, trilled R, vowels) as a deep chanted voice with a sub-octave and doubled
  voices. A concussive impact and an echoing tail follow, both scaling with the word count. Draugr deathlords use the
  same "Fus Ro Dah". The old `shout.fus_ro_dah` events still work.
* **Word walls:** the chant and the learn swell.
* **Magic:**
  * spells per school: fire whoosh and stream, frost crackle and stream, shock zap and stream, healing hum, conjuring
    portal, illusion shimmer and alteration chime;
  * a charge, a fizzle when magicka runs out, and fire, frost and shock impacts;
  * the dragon soul, shrines, standing stones and the nirnroot.
* **UI:** menu open, close, select and back; quest updated and completed; skill up; level up; location discovered;
  and lockpicking.

Every event has a subtitle. The keys are `subtitles.skycraft.<event>` in `lang/skycraft/audio.json`.

```
py -3 tools/music/synth_skyrim_sfx.py               # everything
py -3 tools/music/synth_skyrim_sfx.py dragon shout  # only matching stems
```

## The catalogue

`tools/music/sound_catalog.py` lists every event, its files, its subtitle, its attenuation distance and whether it
streams. After changing it:

```
py -3 tools/music/sound_catalog.py --write   # regenerate sounds.json and lang/skycraft/audio.json
py -3 tools/music/sound_catalog.py --check   # missing or unused files, Java registration, sizes per category
```

## Replacing sounds with your own resource pack

Any resource pack can override the events in `assets/skycraft/sounds.json`. For example:

```json
{ "music.explore.cyrodiil": { "replace": true, "sounds": [ { "name": "mypack:music/heartlands", "stream": true } ] } }
```

## Using your own copy of Skyrim's audio (personal use only)

If you own *The Elder Scrolls V: Skyrim*, you can hear its real music, creature sounds, spells, menus and your
character's actual Thu'um in Skycraft. Skycraft can't ship any of that because it is copyrighted.
`tools/music/import_skyrim_audio.py` reads **your own installation** and builds a **private** resource pack. Nothing
is downloaded or uploaded.

How the script works:

* **Reading the game.** It reads the BSA archives (SSE v105 with LZ4, LE v104 with zlib) and the loose `Data/Music`
  and `Data/Sound` files. It decodes `.xwm`, `.wav` and `.fuz` voice files with ffmpeg (for `.fuz`, the lip-sync data
  is stripped) and converts them to Ogg Vorbis: music in stereo, effects and voices in mono so they can be
  positioned.
* **Mapping.** A rule table of regular expressions on archive paths maps files onto Skycraft's events. You can add
  your own rules with `--map 'REGEX=EVENT'`.
* **Shouts.** The Thu'um comes from the player's voice lines in `Skyrim - Voices_en0.bsa`. The script finds out which
  line says which word by reading the dialogue records and strings. It then joins them into one, two and three words,
  and mixes the shout's effect in at the last word.
* **Unmapped events.** Events Skyrim has no equivalent for, such as the other provinces' music, keep Skycraft's own
  sounds. `--palettes` maps some regional fragments onto province sets.
* **Output.** The script refuses to write inside the Skycraft repository. By default it writes to
  `.minecraft/resourcepacks`.

```
py -3 tools/music/import_skyrim_audio.py --dry-run                     # show the mapping table, convert nothing
py -3 tools/music/import_skyrim_audio.py --out "%APPDATA%/.minecraft/resourcepacks"
py -3 tools/music/import_skyrim_audio.py --only music --out <resourcepacks folder>
py -3 tools/music/import_skyrim_audio.py --list-archive "npc/dragon/"  # browse the archive paths
```

In Minecraft, open *Options → Resource Packs* and enable **"Skycraft – Personal Skyrim audio"**.

`tools/music/import_skyrim_ost.py` is the older music-only tool, for music you already extracted to a folder. It keeps
its command line and now runs the new script in music-only mode.

**Legal note.** The Skyrim soundtrack, sound effects and voices are © Bethesda Softworks and their composer and
performers. The pack you build holds a copy of audio you own, for your personal use only.

* Don't share, upload or redistribute it, and don't put it in modpacks.
* Never commit it.

Skycraft does not include or link to any of that audio.
