# NirnCraft audio

NirnCraft has its own audio: a situational soundtrack with music for every province of Tamriel, an ambience engine,
environmental reverb and occlusion, and sound effects for creatures, dragons, shouts, spells and menus. Every sound
that ships with the mod is **original**. Small Python programs in `tools/music/` compose and synthesise all of it. None
of it is sampled from, transcribed from or derived from Skyrim's audio.

| Part | Code | Generator |
| --- | --- | --- |
| Soundtrack | `world/client/MusicController.java` | `tools/music/compose.py` |
| Ambience beds and calls | `audio/client/AmbienceEngine.java` | `tools/music/synth_ambience.py` |
| Reverb and occlusion | `audio/client/Acoustics.java`, `ListenerEnvironment.java` | none |
| Creature, shout, spell and UI sounds | `audio/AudioSounds.java` | `tools/music/synth_skyrim_sfx.py` |
| Sound events, files and subtitles | `assets/nirncraft/sounds.json`, `lang/nirncraft/audio.json` | `tools/music/sound_catalog.py` |
| Your own Skyrim audio (personal use) | none | `tools/music/import_skyrim_audio.py` |

Client settings live in `config/nirncraft-audio-client.toml`. The soundtrack's master switch is `world/atmosphere.music`
in `nirncraft-world-client.toml`.

## The soundtrack

The `MusicController` checks the situation once a second and plays a `nirncraft:music.*` event.

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
  * Deep glowing caverns (the Falmer glow caves, mushroom grottos and lush caves, 20 blocks below sea level) play
    `music.blackreach`.
  * The Dwemer caverns biome plays `music.dungeon.dwemer` anywhere underground in it.
* **Combat** only starts in a real fight: you hit something or were hit recently (the server's in-combat flag), or a
  hostile mob in attack mode is facing you within 20 blocks and can see you. Being "detected" doesn't count. Combat
  music holds for 8 seconds after the last sign of a fight.
* **Bosses:** dragons that are attacking, or close by, play the dragon battle theme (`music.boss`). Other bosses
  shown by the boss bar, such as dragon priests, deathlords and giants, play `music.boss.battle`.
* **Oblivion** (the Nether) and **Sovngarde** (the End) have their own music.
* **Place zones:** the footprint of a place that was just attacked has a piece of its own, chosen before the town and
  exploration music and crossfaded like any change of mood (`fire/client/MusicZones`, data
  `assets/nirncraft/fire/music_zones.json`: place id, margin, event). Helgen plays `music.helgen_aftermath`
  (`helgen_aftermath` in `compose.py`: Phrygian, 46 bpm, low strings that never resolve, a double reed over a low
  choir, a toll of bells, a muffled heartbeat; config `nirncraft-fire-client.toml` `ambience.aftermathMusic`). Its
  ambience (`ambient.fire_crackle`, `fire_roar`, `timber_creak`, `timber_collapse`, `sobbing`, `mourning`) is played
  by the fire module, positional and quiet, with per-site and per-person cooldowns.
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

* the biome under you and in a ring 24 blocks around you. `Soundscapes` maps every one of NirnCraft's 113 biomes
  (`data/nirncraft/worldgen/biome`, from `tools/worldgen/biomes.py`) onto a weighted mix of soundscape families:
  forest, pine, plains (tundra and meadows), cold marsh, warm swamp, jungle, desert, savanna, ash, volcanic, snow,
  glacier, mountain, mushroom, crystal, hot springs, ocean, river and beach. Biomes it doesn't know (vanilla, other
  mods) are classified by their vanilla and common (`c:`) tags and by keywords in their names;
* the kind of cave biome you are in: Dwemer caverns, Falmer glow caves, ice caves, lava caves, mushroom grottos, and
  vanilla's lush caves and deep dark;
* the province (Skyrim's wolves and dragons, Vvardenfell's cliff racers and netch);
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
| `savanna_day` | Elsweyr's savanna, the Grazelands, Ka Po' Tun's steppe: dry grass and swelling cicadas |
| `marsh_day`, `marsh_night` | Hjaalmarch's cold marsh (and warm swamps by day): insects, water, frogs |
| `swamp_night` | warm swamps after dark (Black Marsh, the Bitter Coast, Blackwood, mangroves): a dense frog chorus over insects |
| `jungle_day`, `jungle_night` | jungles and rainforests (Valenwood, Pellitine, Tsaesci jungle, Pyandonea) |
| `desert_wind`, `ash_storm` | deserts and badlands; ashlands (stronger in storms) |
| `volcanic_rumble` | Red Mountain, Molag Amur, Stonefalls, lava caves: the ground's surging rumble, distant booms, venting gas |
| `hot_springs` | Eastmarch's hot springs: bubbling pools and steam |
| `crystal_shimmer` | Summerset's crystal coast and spires, Artaeum, Thras: glassy chimes and singing air |
| `coast_waves`, `open_ocean`, `river` | at the shore and on beach biomes, out on the water, near rivers and lakes |
| `cave`, `cave_deep` | ordinary caves, by depth; the deep dark |
| `dwemer_machinery` | the Dwemer caverns: the old machines' hum, pistons, ratchets and venting steam |
| `glow_cave` | the Falmer glow caves (and faintly in grottos and lush caves): Blackreach's crystalline hum |
| `ice_cave` | ice caves, and faintly on glaciers (Winterhold, Atmora): moaning wind, groaning ice, cold drips |
| `lava_cave` | lava caves: slow heavy bubbling and hissing rock |
| `mushroom_grotto` | mushroom grottos and lush caves, faintly in Telvanni fungus forests: drips, spore puffs, a soft hum |
| `oblivion`, `sovngarde` | the other realms |

Indoors and underground, the outdoor beds drop and are low-passed (see Acoustics). Rain silences birds and insects.

**Calls** are mono one-shots placed in 3D at random spots around you:

* by day: birds, crows and hawks;
* at night: owls, crickets and Skyrim's wolves howling far away;
* in marshes and by the water: frogs, insects, loons, gulls and crashing waves;
* out at sea: whales;
* in jungles: exotic birds; on savannas and in jungles: cicadas;
* in deserts and ashlands: sand hiss, wind gusts and a distant volcano; lava bubbling on volcanic land and in hot
  springs; ice creaking on glaciers; crystals chiming in crystal land;
* on Vvardenfell: cliff racers screeching overhead by day and netch humming far off (their own creature voices);
* in caves: drips, rumbles, falling pebbles; crystal chimes in glowing caves; distant screeches in the Falmer glow
  caves; machinery clanking and steam venting in the Dwemer caverns; creaking ice in ice caves; lava in lava caves;
* in the realms: daedric wails in Oblivion and a distant horn in Sovngarde;
* very rarely in Skyrim's wilds, a far-away dragon roar (`distantDragons`).

Vanilla wolves use NirnCraft's howl and growl.

`synth_ambience.py` synthesises everything:

* noise shaping, wind-whistle resonator banks, granular insects and frogs, FM bird calls and modal drops;
* beds are 24–40 s seamless loops (exactly periodic, checked at the seam), stereo at 24 kHz, Vorbis q1;
* calls are mono at 32 kHz.

```
py -3 tools/music/synth_ambience.py            # everything
py -3 tools/music/synth_ambience.py wind cave  # only matching stems
```

## Acoustics: reverb and occlusion

`Acoustics` is NirnCraft's own "sound physics". It uses OpenAL EFX:

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
* **How the filters get attached.** NeoForge's `PlaySoundSourceEvent` and `PlayStreamingSourceEvent` hand over the
  `Channel`, and the OpenAL source id is read from its private field `Channel.source` (the one reflective read; no
  mixin or transformer).
* **Failure handling.** If EFX is missing or anything fails, it logs once and switches itself off.
* **Settings:** `acoustics.reverb`, `reverbAmount` and `occlusion` in `nirncraft-audio-client.toml`.

## Sound effects

`synth_skyrim_sfx.py` is a seeded procedural SFX synthesiser. Its toolkit:

* a glottal-pulse source with jitter and shimmer through cascaded formant resonators, for voices;
* sub-harmonic roughness for growls;
* noise sweeps, granular crackle, electric arcs, modal metal, wood and stone, booms, and short reverbs.

All of its output is mono (so it can be positioned) at 32 kHz. The bestiary voices and the later shouts (`LOFI` in the
catalogue) are encoded at 24 kHz, Vorbis q1.5: short creature clips lose nothing audible and the jar stays lean (the
bestiary pass added about 4.4 MB: 2.3 MB of creature voices, 0.5 MB of shouts, 1.6 MB of beds and calls).

Loudness is set per category: UI is quiet (about −24 dBFS) and shouts are loud (−10). It renders:

* **Creatures:**
  * Dragons: roars, growls, wingbeats, fire and frost breath, landing thud, hurt and death. A dragon also shouts its
    breath (Yol Toor Shul / Fo Krah Diin) in a deeper voice.
  * Alduin has his own voice: the dragon voice slowed and deepened, with a vast reverb and an echo. Paarthurnax's is
    old and calm: slow, breathy rumbles and a gentler roar. Dragons shout Fus Ro Dah, Disarm and Become Ethereal with
    the Thu'um chant (Alduin deeper still), breathe with the spell streams, and rise from their mounds with a rattle of
    bones and a ghostly swell (`entity.dragon.rise`).
  * Draugr, skeevers, trolls, giants (with stomps), mammoths, frostbite spiders (giants deeper), wolves, bears, sabre
    cats, Falmer, Dwarven automatons (steam, clank), wisps, spriggans, hagravens, horkers, mudcrabs (giants deeper) and
    ice wraiths.
  * **The bestiary of Nirn** (`BESTIARY` in the catalogue, `entity.<creature>.<kind>`): ambient, hurt and death for
    every bestiary creature, plus cries and specials. A vocal profile table (tract size, pitch, breathiness,
    roughness, rattle) gives every beast a generic grunt, snap, roar, hurt and death; creatures with a character of
    their own have their own generators:
    * chaurus clicks, hisses and spit, and the hunter's buzzing wings; giant scorpions' chitin and sting; land
      dreugh clicks and gurgles; nix-hounds' whistling chirr;
    * rieklings' gabbling chitter and war cry; scamps' cackle; imps' giggling chitter and wings;
    * ash spawn's ashy rasp and rising from the ash; gargoyles' stone grind, waking from a statue, and crumbling;
    * dragon priests' whispered syllables, chanted spells, hiss and dying wail; dremora's double (octave-down) voice;
    * flame atronachs' roaring fire and breathy hiss; frost atronachs' grinding ice, groans, footsteps and shattering;
      storm atronachs' thunder crackle and whirling stone;
    * wisps' and will-o'-the-wisps' chimes (flickering when hurt, fading when slain); the death hound's dark howl;
    * the cliff racer's infamous screech; hackwings' and harpies' shrieks; senche-tigers' chuff and roar; alfiqs' trill;
    * the netch's deep, whale-like hum (and its deflating death); the silt strider's low, booming horn call;
    * wamasu growls over an electric hum and their lightning breath; sand serpents' hiss, tail rattle and eruption;
      sea serpents' and swamp leviathans' deep moans; frost giants' rumbles; minotaurs', echatere's and realm
      grazers' lowing; tsaesci and kamal hisses; the Dwemer ballista's ratcheting winch and twang.

    Bestiary specs pick their voice up by name (`CreatureSpec.voice(id, pitch)`). The attack goal plays a creature's
    cry when a move starts: `attack` (bites and blows, now and then), `roar` (roars, war cries, charges), `cast`
    (spells and bolts) and `breath`. Creature bolts and breath use the spell sounds; summons use the conjuring portal;
    ground slams the giant's stomp; a dragon priest's Unrelenting Force the Thu'um chant.
* **Shouts:** every shout of `com.nirncraft.magic.shout.Shout` (all 26) has a one-, two- and three-word chant (`shout.<shout>.<words>`). A phoneme parser builds
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

Every event has a subtitle. The keys are `subtitles.nirncraft.<event>` in `lang/nirncraft/audio.json`.

```
py -3 tools/music/synth_skyrim_sfx.py               # everything
py -3 tools/music/synth_skyrim_sfx.py dragon shout  # only matching stems
```

## The catalogue

`tools/music/sound_catalog.py` lists every event, its files, its subtitle, its attenuation distance and whether it
streams. After changing it:

```
py -3 tools/music/sound_catalog.py --write   # regenerate sounds.json and lang/nirncraft/audio.json
py -3 tools/music/sound_catalog.py --check   # missing or unused files, Java registration both ways, sizes per category
```

## Replacing sounds with your own resource pack

Any resource pack can override the events in `assets/nirncraft/sounds.json`. For example:

```json
{ "music.explore.cyrodiil": { "replace": true, "sounds": [ { "name": "mypack:music/heartlands", "stream": true } ] } }
```

## Using your own copy of Skyrim's audio (personal use only)

If you own *The Elder Scrolls V: Skyrim*, you can hear its real music, creature sounds, spells, menus and your
character's actual Thu'um in NirnCraft. NirnCraft can't ship any of that because it is copyrighted.
`tools/music/import_skyrim_audio.py` reads **your own installation** and builds a **private** resource pack. Nothing
is downloaded or uploaded.

How the script works:

* **Reading the game.** It reads the BSA archives (SSE v105 with LZ4, LE v104 with zlib) and the loose `Data/Music`
  and `Data/Sound` files. It decodes `.xwm`, `.wav` and `.fuz` voice files with ffmpeg (for `.fuz`, the lip-sync data
  is stripped) and converts them to Ogg Vorbis: music in stereo, effects and voices in mono so they can be
  positioned.
* **Mapping.** A rule table of regular expressions on archive paths maps files onto NirnCraft's events. You can add
  your own rules with `--map 'REGEX=EVENT'`.
* **Shouts.** The Thu'um comes from the player's voice lines in `Skyrim - Voices_en0.bsa`. The script finds out which
  line says which word by reading the dialogue records and strings. It then joins them into one, two and three words,
  and mixes the shout's effect in at the last word.
* **Unmapped events.** Events Skyrim has no equivalent for, such as the other provinces' music, keep NirnCraft's own
  sounds. `--palettes` maps some regional fragments onto province sets.
* **Output.** The script refuses to write inside the NirnCraft repository. By default it writes to
  `.minecraft/resourcepacks`.

```
py -3 tools/music/import_skyrim_audio.py --dry-run                     # show the mapping table, convert nothing
py -3 tools/music/import_skyrim_audio.py --out "%APPDATA%/.minecraft/resourcepacks"
py -3 tools/music/import_skyrim_audio.py --only music --out <resourcepacks folder>
py -3 tools/music/import_skyrim_audio.py --list-archive "npc/dragon/"  # browse the archive paths
```

In Minecraft, open *Options → Resource Packs* and enable **"NirnCraft – Personal Skyrim audio"**.

`tools/music/import_skyrim_ost.py` is the older music-only tool, for music you already extracted to a folder. It keeps
its command line and now runs the new script in music-only mode.

**Legal note.** The Skyrim soundtrack, sound effects and voices are © Bethesda Softworks and their composer and
performers. The pack you build holds a copy of audio you own, for your personal use only.

* Don't share, upload or redistribute it, and don't put it in modpacks.
* Never commit it.

NirnCraft does not include or link to any of that audio.
