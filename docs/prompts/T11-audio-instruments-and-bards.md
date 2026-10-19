# T11 — Full Biome Soundscapes, 3D Cultural Instruments, Travelling Bards & Regional Song Repertoires

Read [`_COMMON.md`](_COMMON.md) and [`docs/world/WORLD-PLAN.md`](../world/WORLD-PLAN.md) (Sections 8 & 9) first.

## 1. Goal

Currently:
1. **`AmbienceEngine.java` (`lines 204–205`) explicitly blocklists 10 of the 30 biome ambient beds (`forest_day`, `forest_night`, `plains_day`, `plains_night`, `marsh_day`, `marsh_night`, `jungle_day`, `jungle_night`, `swamp_night`, `savanna_day`) and 3 insect calls (`cricket`, `insect`, `cicada`)** because their procedural white-noise synthesis in `tools/music/synth_ambience.py` produced harsh static/crackle.
2. **Zero musical instrument items exist anywhere in the mod**: `Npcs.equip()` gives `NpcRole.BARD` nothing, and `NpcModel.java` (`line 79`) literally comments `// holding an invisible lute: left hand on the neck, right hand strumming`.
3. **Every bard in every province plays a random vanilla note-block walk (`NpcGoals.Work.composeSong()`, `lines 774–809`) and sings the same 5 Skyrim/Nord song lines (`Chatter.SONGS`, `lines 87–127`)** even in Morrowind, Elsweyr, Summerset, or Black Marsh.

`T11` re-synthesizes clean, natural audio for all 10 biome beds and 3 insect calls, removes the blocklist in `AmbienceEngine.java`, adds 10 3D cultural musical instrument items equipped by bards and playable by players, and gives each culture its own modal melodies, regional song lyrics, and travelling road bards.

## 2. Write Set

* `tools/music/synth_ambience.py`
* `mod/src/main/resources/assets/nirncraft/sounds/ambience/**/*.ogg`
* `mod/src/main/java/com/nirncraft/audio/{AudioSounds,InstrumentItem,AudioItems}.java`
* `mod/src/main/java/com/nirncraft/audio/client/AmbienceEngine.java`
* `mod/src/main/java/com/nirncraft/society/{Npcs,Chatter,SocietySelftest}.java`
* `mod/src/main/java/com/nirncraft/society/entity/NpcGoals.java`
* `mod/src/main/java/com/nirncraft/society/client/NpcModel.java`
* `mod/src/main/resources/assets/nirncraft/{models,textures}/item/**`
* `mod/src/main/lang/nirncraft/society*.json`

## 3. Implementation Steps

1. **Re-Synthesize & Un-Blocklist All 10 Biome Ambient Beds & 3 Insect Calls (`tools/music/synth_ambience.py`, `AmbienceEngine.java`):**
   * In `tools/music/synth_ambience.py`, replace unmodulated white-noise bursts in `forest_day/night`, `plains_day/night`, `marsh_day/night`, `jungle_day/night`, `swamp_night`, `savanna_day`, `cricket`, `insect`, and `cicada` with warm low-passed pink/brown breeze envelopes (`180–650 Hz`), gentle leaf/reed chorus modulation, and pure sine/triangle harmonic bird/frog/cricket chirps with smooth cosine fade-in/out envelopes (zero clipping or high-frequency static).
   * Re-run `py -3 -I tools/music/synth_ambience.py` and clear `SILENCED_BEDS` and `SILENCED_CALLS` in `AmbienceEngine.java` (`lines 204–205`).
2. **Register 10 3D Cultural Musical Instrument Items (`InstrumentItem.java`, `Npcs.java`, `NpcModel.java`):**
   * Register 10 cultural instruments:
     1. `nirncraft:lute` (Nord / Imperial / Breton wooden lute)
     2. `nirncraft:wooden_flute` (Pastoral flute)
     3. `nirncraft:frame_drum` (Tundra / caravan hand drum)
     4. `nirncraft:nord_war_horn` (Carved horn of Skyrim/Atmora)
     5. `nirncraft:velothi_ash_flute` (Dunmer hollow chitin/reed flute)
     6. `nirncraft:redguard_oud` (Hammerfell/Yokudan pear-bodied oud)
     7. `nirncraft:altmer_crystal_harp` (Summerset harmonic glass-strung lyre)
     8. `nirncraft:bosmer_bone_pipe` (Valenwood antler panpipe)
     9. `nirncraft:khajiit_sitar` (Elsweyr long-necked gourd lute)
     10. `nirncraft:argonian_reed_marimba` (Black Marsh resonant bog-reed chime)
   * Equip `NpcRole.BARD` with their culture's instrument in `Npcs.equip()` (`Npcs.java:179-244`), render the held item in `NpcModel.java` (`lines 78–85`), and allow players to play a short cultural melody Phrase when right-clicking an `InstrumentItem`.
3. **Cultural Melodies, Regional Song Lyrics & Travelling Bards (`NpcGoals.java`, `Chatter.java`, `scenes.json`):**
   * In `NpcGoals.Work.composeSong()`, select a structured cultural scale, motif progression, rhythm cadence, and timbre matching the bard's culture/instrument.
   * Expand `Chatter.SONGS` into a culture/province-keyed repertoire (`nord`, `imperial`, `breton`, `redguard`, `dunmer`, `altmer`, `bosmer`, `khajiit`, `argonian`, `orc`, `foreign`) with `>= 4` original multi-line songs per culture in `lang/nirncraft/society*.json`.
   * Add travelling bard road encounters in `scenes.json` / `Encounters.java` who walk between inns along `RoadPlan` highways/roads, performing songs and sharing regional rumours.

## 4. Verification Gates

* `SILENCED_BEDS` and `SILENCED_CALLS` in `AmbienceEngine.java` are empty (`0` silenced soundscapes).
* `py -3 -I tools/qa/asset_audit.py` and `py -3 -I tools/audit_items.py` -> `0 problems`, `0 gaps` on all 10 instrument items.
* `SocietySelftest` verifies bards spawn holding a cultural `InstrumentItem` and sing province-matched song keys.
* `tools\qa\qa.ps1 -Server -Rebake` -> `OVERALL PASS`.
