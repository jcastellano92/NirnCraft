# Marquee places

The places that must make a player say "wow, that place is cool", chosen per province, with an identity brief, named
buildings, named NPCs, a signature quest chain and what it takes to build. Generated from `tools/marquee/marquee.json`
(`node tools/marquee/render_md.js`); edit the JSON, not this file. Gap analysis: `docs/MARQUEE-GAP.md`. Work plan:
`docs/MARQUEE-PLAN.md`.

Legal: names, positions and layout facts only (from the owner's own installs and lore); every description, name of an
invented NPC and quest text is original.

**Reading the tables.** Tier: S build first, A next, B when the infrastructure is cheap. Effort: S = data and existing
kits only; M = a new layout plan plus a small kit or builder change; L = new landmark builders, a new kit or new
structure code. `now` = what the place gets today. Silhouette tags are unique across all settlements; no two share
silhouette + landmark + palette.

## Summary

| Province | Settlements | Dungeons | Landmarks | Minor | S tier | L effort |
| --- | --- | --- | --- | --- | --- | --- |
| Cyrodiil | 8 | 5 | 4 | 6 | 6 | 4 |
| Morrowind (Vvardenfell and mainland) | 8 | 6 | 5 | 8 | 8 | 12 |
| Solstheim | 5 | 6 | 4 | 6 | 4 | 3 |
| High Rock | 8 | 6 | 4 | 6 | 4 | 6 |
| Hammerfell | 7 | 6 | 5 | 6 | 4 | 7 |
| Elsweyr | 7 | 6 | 4 | 6 | 4 | 4 |
| Valenwood | 7 | 6 | 4 | 6 | 4 | 5 |
| Summerset Isles | 7 | 6 | 5 | 6 | 4 | 4 |
| Black Marsh | 7 | 6 | 4 | 6 | 3 | 4 |
| Foreign lands (Atmora, Akavir, Yokuda, Thras, Pyandonea) | 5 | 5 | 4 | 5 | 3 | 4 |
| **Total** | **69** | **58** | **43** | **61** | **44** | **53** |

All 231 ids exist in `places/*.json` today (verified by `tools/marquee/validate.js`); no new place ids are required.

## Cyrodiil

The heart of the Empire: the Rumare as a silver spine, the Nibenay's ayleid ruins and vineyards, snow on the Jerall rim. Nine cities that must each read differently from far away.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `imperial_city` | S | L | bespoke: Cities.imperialCity | ring-island | White-Gold Tower | white marble, gold trim, green lawn |
| `bruma` | S | M | generic zoning output | switchback-alpine | Great Chapel of Talos | whitewash, spruce beams, slate, snow caps |
| `bravil` | S | L | generic zoning output | stacked-shanty-hill | Lucky Old Lady statue | weathered grey-green plank, reed thatch, rope lantern |
| `skingrad` | A | M | generic zoning output | vineyard-hill-castle | Castle Skingrad on its rock crown | warm limestone, terracotta tile, grapevine green |
| `anvil` | A | M | generic zoning output | harbour-crescent | Anvil Lighthouse | white plaster, blue glazed tile, sea-rope |
| `kvatch` | A | M | generic zoning output | crater-slope-scar | Kvatch Castle half-rebuilt, gate-scar crater | scorched basalt, new pale timber scaffolds, ash |
| `chorrol` | A | M | generic zoning output | great-oak-green | The Great Oak | oak timber, green slate, honey stone |
| `cheydinhal` | B | M | generic zoning output | canal-twin-bank | Great Chapel and the stone bridge | white stucco, blue tile, ash-grey Dunmer quarter |

**Imperial City** (`imperial_city`, S/L). Concentric rings of districts on a lake island, one bridge, the Tower punching 210 blocks above everything. Each district gets its own street-width, roof colour and trees; the Market, Arena and Temple districts are separate walled wards you pass through, not one blended grid.

* Buildings: Arboretum conservatory (glasshouse dome); Arena Bloodworks and public galleries; Temple of the One (white nave, gold ribs).
* NPCs: Tullia Vesk (Ayleid glyph copyist who needs a bodyguard into old ruins); Prefect Aurex (runs the Elven Gardens night watch, owes a smuggler a favour); Brother Calvus (Arkay priest in the Temple who hears confessions that matter).
* Quest "The Ring Beneath the Rings": 1. Find the sealed sewer grate under the Waterfront District 2. Map three Ayleid cisterns that predate the city 3. Retrieve the Alessian seal from a flooded vault 4. Choose: return it to the Palace or the Arboretum librarian Unlike the rest: the only Cyrodiil chain that is a multi-district urban delve with a real choice.
* Needs: Existing bespoke layout; add district colour/roof variance per ring (kit overrides per street rank), 6 named wards as sign-bearing plazas. Needs a district-tint option in the plan format.

**Bruma** (`bruma`, S/M). A mountain town climbing a switchback valley under the Jerall ridge: terraces stacked above a fast stream, chimney smoke everywhere, a tall Talos chapel spire as the crown. The Cyrodiil town that feels half-Nord, with steep steps and snow-load roofs.

* Buildings: Great Chapel of Talos (tallest spire in Cyrodiil); The Jerall View Inn on the switchback; Mountain Smithy with water-wheel bellows.
* NPCs: Captain Ilsa Varro (Legion captain watching the pass, wants a courier she can trust); Old Hrodgar the Chandler (candles sold with a rumour of what lies behind the Cloud Ruler); Sister Matina (chapel keeper, copies fragments of the Dragon-Blood prayers).
* Quest "The Snow-Load Wager": 1. Win a stair-race up the town terraces 2. Escort a sledge of relics through a blizzard pass 3. Defend the chapel bell tower from raiders 4. Ring the old bell: wakes something under the ridge Unlike the rest: a winter-weather endurance chain with the town's verticality as the course.
* Needs: New cityplan: valley-terraces form (zoning HILLTOP with a stream axis), nordStyle-like kit variant 'imperial_alpine' (spruce beams, snow-slate roofs). Landmark: chapel spire (reuse TALOS builder scaled up).

**Bravil** (`bravil`, S/L). A drowned-looking city piled up a steep river-bay hill: stairs and rope bridges between leaning plank houses, laundry lines, a statue of the Lucky Old Lady at the top. Grimy, vertical, smoke and mist; the reference city that looks poor, not pretty.

* Buildings: The Lonely Suitor Lodge (leaning, ten storeys of balconies); Bridgeside fishmarket on stilts; Ratcatcher's Shrine to Arkay.
* NPCs: Mother Nettle (fishwife and information broker, trades secrets for eels); Ulrich the Quiet (mute skooma-runner who wants out); Captain Pellia Vorn (one honest guard, stonewalled by the Count's clerk).
* Quest "The Lady's Lucky Day": 1. Learn the local legend of the statue from three tellers 2. Follow a skooma barge up the river at night 3. Catch the Count's clerk at the counting-house 4. Decide whether the statue's secret stays secret Unlike the rest: a slum-detective chain, vertical stealth through rope bridges; none of the other cities are poor.
* Needs: New plan: SLOPE form with stacked plank houses, many rope BRIDGE spans, stairs (needs STAIRS street archetype or use terraces); kit 'imperial_slum' (plank/reed/spruce). Statue landmark new.

**Skingrad** (`skingrad`, A/M). A hill town ringed by terraced vineyards and cypress lines, a walled castle on the rock crown, and a long viaduct road. Colours are ochre and green, not grey: the Cyrodiil of wine, mild weather and old family feuds.

* Buildings: Colovian vintner's press-house with open cask yard; The Two Sisters Lodge (arbour courtyard inn); Count's Garden Chapel (open colonnade).
* NPCs: Aldo Castellus (vintner with forty-year-old casks and a Merovane problem); Ysmene Merovane (rival vintner, offers a poisoned-bottle commission); Steward Lucan Rilus (keeps the Count's ledger and a missing page).
* Quest "Two Vintages, One Cask": 1. Taste-test both estates' reserves 2. Trace a spoiled shipment back to a cooper 3. Catch the saboteur during the harvest festival 4. Judge the feud: seal one cellar or merge them Unlike the rest: the only light, comic/social chain; a harvest-festival set piece.
* Needs: Kit 'imperial_warm' (limestone/terracotta); a VINEYARD yard-prop set (rows, press, casks) in zoning outskirts; hill plan with castle crown (reuse CASTLE landmark scaled).

**Anvil** (`anvil`, A/M). A crescent harbour: a white mole curling out to a tall lighthouse, fish-market quays, blue-roofed houses on two terraces, and a castle block at the seaward point. The sea-facing, bright and breezy contrast to inland Cyrodiil, with boats you can actually board.

* Buildings: Lighthouse keeper's tower with beacon stair; Wharfside chandler and net-loft; Harbour customs house (arched warehouse).
* NPCs: Harbourmaster Teodor Arenius (keeps the manifests, hides a name); Marga the Net-Mender (knows which ships don't come back); Cap'n Dufrane (retired smuggler, will sell you a map).
* Quest "The Dark Beacon": 1. Climb the lighthouse: the lamp was dimmed on purpose 2. Tail the night ferry to a sea cave 3. Sink or capture a wrecker's skiff 4. Relight the beacon with a ceremony the town hasn't seen Unlike the rest: a coastal wreck-and-smuggle chain, built around a ship you can ride.
* Needs: Harbour form in zoning with mole and lighthouse landmark (reuse lighthouse blueprint); kit 'imperial_coast' (white/blue). Boats from the sailing module.

**Kvatch** (`kvatch`, A/M). A slope city half-ruined by an Oblivion Crisis: rebuilt wards in pale new timber against black scorched stone, a hole-in-the-ground where the Gate opened, and a castle with one wall still down. A city mid-recovery, readable at a glance.

* Buildings: Chapel of Arkay as a field hospital; Temporary stone-and-canvas market under the arena stands; Count's half-restored hall with scaffolds.
* NPCs: Savlian Matius (captain of the Guard, hates ceremonies, loves the Count); Sister Ilvara (tends the refugees, keeps a roll of the lost); Dorrik the Bricklayer (rebuilder who found something in the rubble).
* Quest "What the Ash Remembers": 1. Help rebuild a ward by hauling stone 2. Find the list of the lost in the chapel crypt 3. Follow a survivor's trail into the crater-edge ruins 4. Seal a minor Gate remnant before the wall is rebuilt Unlike the rest: a recovery/rebuilding chain, where progress physically changes the town.
* Needs: Plan with 'damaged' flag: partial-ruin shells + scaffold props (Props); add REBUILD variant state (data: per-stage building list swapped on quest stage - needs a world-state hook). Gate crater landmark exists (kvatch_gate).

**Chorrol** (`chorrol`, A/M). A town gathered round an ancient oak so large that its canopy roofs the central green, houses of honey stone and timber under it, a timber-gate wall and a tiled-roof guildhall. The only Cyrodiil town with a living centre rather than a tower.

* Buildings: The Oak Hall (guild hall built into the root buttresses); Weynon Priory road shrine; The Hungry Heron Inn under the canopy.
* NPCs: Lady Cassia Vallarius (steward trying not to be her grandfather); Arden the Woodwright (maintains the Oak, knows it is sick); Brother Hesk (priory scribe with a letter that should not exist).
* Quest "A Sickness in the Heartwood": 1. Examine the Oak and take bark samples 2. Brew a cure from three roadside herbs 3. Hold the Oak against beetle swarms for a night 4. Choose: preserve the Oak or fell it to save the town's wells Unlike the rest: nature-health chain for a single living landmark.
* Needs: Reuse GIANT_TREE builder at 'great_oak' scale 30; plan with green and radial lanes. Kit 'imperial_oak'. Low risk.

**Cheydinhal** (`cheydinhal`, B/M). A canal-split town: the river Corbolo bisects it, with two bridges and a mixed quarter where Dunmer refugees built flat-roofed ash-grey houses beside Nibenese blue tile. Identity is the visible cultural seam down the middle.

* Buildings: Great Chapel with Dunmer shrine annex; The Refugee Hall (Dunmer long-house); Corbolo Bridge toll-house.
* NPCs: Ilvesa Dren (hall warden, keeps the roll of arrivals); Brenius Marcus (old Cheydinhal merchant, unwilling to share the market); Captain Sarel (guard captain who plays both sides fair).
* Quest "The Empty House by the Wall": 1. Hear both sides of the market quarrel 2. Search the empty house the refugees refuse to enter 3. Find the Ordinator hiding in the cellar 4. Broker the peace or expose the landlord Unlike the rest: a two-communities mystery chain; only quest with a culture split in one town.
* Needs: Zoning CANAL form with cultural kit split by street side (needs per-side style: plan 'quarter_style' field). Dunmer kit already exists.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `miscarcand` | S | L | ruin+ayleid | A buried Ayleid city-vault of lit welkynd stone: tiered cisterns, bridges over drowned galleries, glowing crystal-lamp halls and a cold vault of sealed names. Vertical, blue-white, no torches. | The Last Warden of Miscarcand (an unlit Ayleid lich) | Tullia Vesk: scholar, will pay for glyph rubbings; Maelkoth the Bound: shade who speaks Ayleid, wants his name restored | **The Name-Vault**: Reach the cisterns > Open three lamp-gates by solving a light puzzle > Recover the sealed name-plate > Choose to free or bind the shade. Unlike: light-and-water puzzle dungeon using lit lamps as the door mechanic | Data-driven marquee plan: ayleid theme rooms (cistern, lamp-gate, nave); needs welkynd-lamp block (lit/unlit states) and a water-level room variant. Existing Theme RUIN + CultureRooms. |
| `sancre_tor` | S | L | fort+barrow | A mountain-top Akaviri fortress with a barrow of dragon-hunters beneath: pagoda-curved halls above, long tomb galleries below. A climb up exposed ramparts, then a descent. | The Hundred-Blade Revenant | Cirdan the Dragonblood: old Blade, hints at what is below; Captain Ranvyr: Legion deserter holding the gate | **The Hundred Names**: Climb the ramparts > Open the barrow with the three Dragonguard sigils > Defeat the Revenant > Carry the oath to a Blades keeper. Unlike: only Akaviri-themed mountain climb; surface-to-underground double structure | Special entry 'sancre_tor' (data plan): FORT exterior + BARROW interior stitched; curved-roof Akaviri kit already in Cultures. |
| `frostcrag_spire` | A | M | mage_tower | A single obsidian spire on a rock in a cold sea, no stairs: levitation or boat to the door, inside a spiral of study floors, a storm-room at the top and a flooded basement. | The Spire's Archmage (Telvanni exile) | Valtyn the Gale-Caller: apprentice trapped inside; Mirna: ferrywoman, wants her father's journal | **No Stairs Up**: Cross the water to the spire > Find the lever-less floors by magic > Defeat the Archmage's storm > Descend into the basement and free the apprentice. Unlike: vertical tower quest that is mostly about movement spells, not combat | Use SurfaceBuilder mage tower; add a 'no ground entrance' flag, ferry NPC; levitation puzzle uses magic module. |
| `deepscorn_hollow` | A | M | vampire_lair | A coastal cave with a drowned lower level, a moon-lit sink-hole and a bone-white hall of the Hollow Court. Lower tides expose different rooms. | Countess Vaelia, the Hollow Court's Matron | Brother Calcus: hunter of vampires, injured; Vaelia's Thrall: pleading for a cure | **Low Tide at Midnight**: Wait for the low tide > Slip through the drowned halls > Find the Matron's coffin > Spare or slay the thrall. Unlike: time-of-day/tide-gated dungeon | Cave theme with tide state (world time check on a 'tide gate' block); new vampire court room set. |
| `dark_brotherhood_sanctuary_cyrodiil` | A | M | crypt+culture | A hidden cellar-sanctuary under a nondescript house: a small dark crypt-warren, a shrine to the Night Mother, a communal hall and a locked inner coffin-room. | The Listener's Shadow (no boss: a choice) | Whisper: initiate who wants out; Speaker Dessia: old assassin, keeps the Black Door | **The Black Door**: Find the sanctuary > Pass the oath trial > Choose a contract > Resolve by killing, sparing or defecting. Unlike: moral-choice chain; no boss fight required | Existing 'dark_brotherhood' faction hooks; data plan for a small socials-dungeon (no enemies unless provoked). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `kvatch_gate` | S | M | The burning crater edge where the Gate first opened: ash plume, wrecked siege-tower, a ring of lava-glass. | A timed Oblivion Gate assault: three waves and a closing ritual; the plume visible 300 blocks away. | Reuse oblivion_gate structure; add crater/ash scatter and plume particle emitter. |
| `cloud_ruler_temple` | A | M | The Blades' keep on a cliff over cloud: banners, wind, a rope bridge, a view of the whole Jerall range. | Climb to the Blades' roof at dawn; the whole province is visible in one panoramic sweep. | Fort kit with cliff mount; use the existing FORT structure with a viewing balcony. |
| `the_sentinel` | A | S | A lone statue guardian on a ridge, hand raised, facing the Valus road. | Standing at the statue's feet marks the Cyrodiil/Morrowind border; the sky changes tone. | Existing monument variant; add a larger statue builder variant. |
| `gate_of_alma_ruma` | B | S | One of the six great Alessian gate-ruins with its scroll-temple annex, worn. | Visiting all six unlocks a pilgrim's cache. | Existing monument + altar; a visit counter in discovery data. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `weynon_priory` | B | S | A cloister of grey stone with a bell and an orchard. | A cloister of grey stone whose scribe, Brother Hesk, has lost a letter that should not exist. |
| `imperial_bridge_inn` | B | S | An inn half on the bridge, half on the bank; lanterns on the span. | An inn half on the bridge; a card-sharp there loses on purpose to hide something. |
| `lunar_fang_docks` | B | S | Rickety lake docks lit by moonlamp. | Rickety lake docks lit by moon-lamps; the dock ledger hints at a werewolf among the crew. |
| `shardiuss_excavation` | B | S | A half-dug Ayleid cut-away with scaffolds. | A half-dug Ayleid cut-away; scholars have been vanishing at the dig face. |
| `mist_mirror_refuge` | B | S | A hidden waterfall pool that mirrors the sky. | A hidden waterfall pool that mirrors the sky; the hermit sells directions to it. |
| `thalaras_winery` | B | S | A single-family cellar estate with a stone press. | A cellar estate whose latest vintage has poisoned a guest; the owner blames a rival. |

## Morrowind (Vvardenfell and mainland)

Ash, mushrooms, ziggurats and volcano glow. Four Great House kits and a Tribunal temple exist, but every Dunmer town currently picks one at random. Each major place needs a unique skyline: Vivec's cantons, Ald'ruhn's crab shell, Sadrith Mora's spore towers, Necrom's tomb city.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `vivec` | S | L | bespoke: Cities.vivec | canton-water | Palace of Vivec ziggurat | dark basalt ashlar, saffron gold, bronze lantern |
| `mournhold` | S | L | bespoke: Cities.mournhold | dome-citadel-plaza | Almalexia's temple dome and Royal Palace | white marble, gold, deep blue glass |
| `balmora` | S | L | generic zoning output | twin-bank-river-terrace | Silt-strider port and river bridge | clay-yellow plaster, dark-oak balconies, saltrice sack colour |
| `aldruhn` | S | L | generic zoning output | shell-spine-fort | Skar: a gigantic upturned emperor-crab shell | bone-white chitin, brown lacquer, red banners |
| `sadrith_mora` | S | L | generic zoning output | mushroom-spore-cluster | The Wizards' Tower and the Telvanni Council spire | violet cap, teal glow, bone-white stem, glowshroom lanterns |
| `necrom` | A | L | generic zoning output | necropolis-tomb-grid | The great tomb temple of Necrom | pale ashlar, bone-white, deep green moss, purple banners |
| `molag_mar` | A | L | generic zoning output | cliff-grotto-lavafall | The Underworks canton in a cliff cavern | basalt black, red lava glow, rust-orange |
| `ebonheart` | A | M | generic zoning output | star-fort-bridge | Castle Ebonheart on its island | imperial white stone, red banners, dark Dunmer canton quarter |

**Vivec** (`vivec`, S/L). Six pyramidal cantons standing in water, joined by long arched bridges, with the Palace as a stepped ziggurat at the centre. Each canton is a different job (Foreign Quarter, Arena, Hlaalu, Redoran, two temples), and the great ring of water must read as one city from the shore.

* Buildings: Arena Canton fighting pit with hanging banners; Foreign Quarter Plaza (tented bazaar over the water); Temple of the Tribunal canton chapel (stepped, lamp-lit).
* NPCs: Warden Telyne (Temple warden of the Hlaalu canton, secrets and stairs); Tosa the Cantonkeeper (maintains the water-gates, knows what's beneath); Brother Fendil (pilgrim guide who sells the wrong ending).
* Quest "Water Under the Cantons": 1. Locate the flooded canton foundations by boat 2. Descend the maintenance shafts 3. Seal a leak that is also a theft 4. Face the Warden's choice: hide the leak or call the Temple Unlike the rest: the only chain that uses boat travel between districts as the central mechanic.
* Needs: Existing bespoke layout; give each canton a distinct style/roof via per-canton kit overrides, named canton signs, and add the Palace stepped-tier landmark upgrade. Water-level bridge arches exist.

**Mournhold** (`mournhold`, S/L). A walled white-and-gold citadel: a huge domed Temple, a palace behind a great plaza, and the Great Bazaar as an enclosed arcade street. Beneath the city, 'Old Mournhold' is its ruined twin: the same plan, dark, sunken, and hostile.

* Buildings: Great Bazaar (arcaded covered street, 40 stalls); Almalexia's Temple (blue-glass dome); Godsreach Quarter terrace houses.
* NPCs: Archcanon Teris Ulvani (temple politics, wants a quiet inquiry); Mistress Hlaren (bazaar guildmaster, smuggling in plain sight); Old Sedyn (guide to Old Mournhold, half-blind).
* Quest "Beneath the Twin City": 1. Take a bazaar contract that hides a map 2. Find an entrance to Old Mournhold 3. Cross the twin-plaza ruin to the vault 4. Re-seal or rob the vault Unlike the rest: the only chain with a mirrored 'twin city' underworld.
* Needs: Existing bespoke; add the Bazaar arcade archetype (long covered street spec) and a mirrored undercity dungeon plan (old_mournhold) using the same Cities.mournhold coordinates.

**Balmora** (`balmora`, S/L). A river town on both banks of the Odai: low, spreading Hlaalu houses on terraces, a high stone bridge between, a giant silt strider docked at the south side. Warm yellow clay and orange tile, not black basalt: the commercial city.

* Buildings: Guild row on the bridge (Mages, Fighters, Thieves fronts); Hlaalu Council Manor with stepped terrace garden; Silt-strider port tower and caravan yard.
* NPCs: Hlaalu Factor Dron (merchant prince, wants a rival's ledger); Nilena the Smuggler (river-bank fence); Port-Master Orrel (silt-strider wrangler, drunk and brilliant).
* Quest "Three Ledgers, One River": 1. Fence a ledger for Dron on the south bank 2. Steal the second from the Council Manor 3. Lure the third into a river bridge ambush 4. Choose: sell all three to the Temple or burn them Unlike the rest: a mercantile heist chain across two banks and a bridge, the only heist with a bridge fight.
* Needs: Zoning RIVERSIDE plan with a bridge spine and silt-strider dock landmark (SiltStriderEntity exists); Hlaalu kit exists, add warmer palette entries.

**Aldruhn** (`aldruhn`, S/L). A fortress-town built inside and along a monstrous crab shell: the Skar, ribs forming gate arches and long halls, houses clustered along the ridge, Redoran banners on every post. A silhouette you can't mistake: one long curved hump on the horizon.

* Buildings: Skar Hall (the great shell chamber, Redoran council); Redoran Guild-Hall barracks with chitin ribs; The Rat-Walk Inn on the shell's outer ridge.
* NPCs: Councilor Brara Redoran (sharp-eyed, wants a duel witnessed); Brarmin the Egg-Miner (knows mine tunnels beneath the town); Rolfa Skar-Guard (captain, rule-bound, honest).
* Quest "The Honour of the Shell": 1. Witness a Redoran duel at the shell's gate 2. Investigate a poisoned blade 3. Track the poisoner into the egg-mine tunnels 4. Present proof to the Council and accept a duel Unlike the rest: honour-duel chain; the climax is a formal duel in front of a council, not a dungeon boss.
* Needs: New landmark: SHELL (ribbed curved hull from Canvas curves) + cityplan with the shell as the spine; Redoran kit exists. Needs a chitin-block palette; use bone_block/ivory + dark_oak.

**Sadrith Mora** (`sadrith_mora`, S/L). A cluster of giant fungal towers rising from an ash-flat on a lagoon: each noble's tower a different cap colour and height, with narrow rope footbridges and glowing spore-lamps at night. No streets, only stems.

* Buildings: Council Spire (tallest cap, open meeting terrace); Spore-gardens greenhouse with fumigation chambers; Rope-bridge tavern (a cap-hall hung between two stems).
* NPCs: Magister Gaenor (levitating Telvanni, bored, offers a petty bet); Skeema the Sporekeeper (tends the glowing gardens, afraid of a new strain); Apprentice Veya (wants to leave her master's tower).
* Quest "A Bet Between Towers": 1. Accept Gaenor's wager: an item stolen from another tower 2. Cross four rope bridges and one levitation gap 3. Face the tower's resident spell-guard 4. Choose to return it or bring it to the other Magister Unlike the rest: a tower-to-tower infiltration with magic gimmicks; Telvanni humour.
* Needs: Telvanni kit exists (mushroom towers). New plan: TOWER_CLUSTER with rope bridges, per-tower cap colours (wool/terracotta glazed), glow at night via sea-lanterns. Landmark MUSHROOM_SPIRE builder.

**Necrom** (`necrom`, A/L). A city of the dead and the living: ancestral tomb-houses in terraced rows climbing a hillside, with a central temple, processional avenues lined by lanterns, and a harbour. Narrow, quiet, solemn: a procession is the street plan.

* Buildings: Ancestor Tomb Row (twelve family mausoleums); Temple Procession Gate and stair; Pilgrims' hostel with candle-lit alcoves.
* NPCs: Keeper Salvu (tomb-keeper, hears the dead); Elder Nerevi (a widow who lets no outlanders enter); Aldrin the Restless (gravedigger unwilling to dig one grave).
* Quest "A Grave Not Dug": 1. Learn whose grave Aldrin will not dig 2. Track the heir to a farm 3. Retrieve a family relic from a sealed tomb 4. Perform the burial or let the line lapse Unlike the rest: a family-ghost mystery with a funerary ritual finale.
* Needs: New plan: TERRACED rows with a processional avenue; velothi kit exists; MAUSOLEUM archetype (HALL_OF_DEAD variant) in numbers.

**Molag Mar** (`molag_mar`, A/L). A town cut into a sea cliff with a lava-lit grotto at its heart: the Temple canton reached by long cut stairs, houses balanced on ledges above a glowing channel, ash spray from the surf. Red light and black stone from far off.

* Buildings: Underworks Canton (grotto temple with lava channels); Redoran ledge-houses on stairs; Harbour warehouse tunnels.
* NPCs: Warden Ilu (controls the canton lift); Dren the Lavacutter (miner, afraid of the glow); Sister Mavia (Temple healer, collects ashes).
* Quest "The Channel Runs Hot": 1. Examine a failed heat-vent 2. Descend the lift to the Underworks 3. Lure a magma elemental into a quenching pool 4. Seal the vent: costing you something Unlike the rest: environmental-hazard chain: lava and heat as the puzzle.
* Needs: New plan: CLIFF form (like Markarth's CLIFF landmark) with a lava-channel yard feature (lava blocks with obsidian edging, safe). Redoran kit exists.

**Ebonheart** (`ebonheart`, A/M). A bastion fortress and harbour town: a star-shaped castle on a river island, a long bridge to a grid of Imperial streets, a Dunmer quarter jammed outside the wall. The rigid Imperial plan against organic Dunmer clutter.

* Buildings: Castle Ebonheart (star bastion, keep tower); Imperial Cult chapel (Nine Divines); Dockmaster's customs yard.
* NPCs: Legate Corvus Pello (wants a dissident quietly arrested); Aryn the Informer (Dunmer quarter fixer); Chaplain Loris (hears the Garrison's confessions).
* Quest "The Bastion and the Quarter": 1. Infiltrate the Dunmer quarter 2. Learn who is selling gate keys 3. Turn a guard 4. Confront Corvus or disarm the plot Unlike the rest: political/intrigue chain between two communities at the gate.
* Needs: Zoning GRID + a 'star bastion' wall spec (wallRing with corner bastions); Dunmer quarter via mixed-style option (shared with cheydinhal).

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `kogoruhn` | S | L | daedric_ruin+fort | A blasted ash-fortress at the foot of the Red Mountain: broken keeps half-buried in drifts, cracked red-lit halls and a collapsed amphitheatre; ash storm in the courtyard. Ruin, not tomb: wind and glass. | Dagoth Fovon the Burned | Ordinator Felis: last of a lost patrol; Ashkhan Sel: pilgrim seeking a lost relic | **The Burned Court**: Cross the ash waste > Light the three braziers > Enter the burned court > Choose: burn the throne or preserve it. Unlike: weather-driven dungeon: ash storms damage and hide enemies, braziers pace the fight | Data plan: ruin + ash weather volume; needs ashfall particle and visibility fog in this structure box (visual module hook). |
| `ald_sotha` | S | L | daedric_ruin | A huge ancient Velothi shrine fortress with a sunken lower temple of a long-silent god: dusky green stone, great doors, cold wind. A sacred place, not an enemy den; halls designed for pilgrims. | The Tide-Warden (an imprisoned guardian) | Pilgrim Maras: won't go further; Voice of Ald Sotha: a presence in the stone | **The Pilgrim's Tolls**: Pass three trials of the ancient path > Bear a prayer to each of three shrines > Open the sealed sanctuary > Choose: take the relic or leave it sealed. Unlike: a sacred pilgrimage dungeon, mostly traps and rituals | Data plan with long processional hall rooms; needs a 'pilgrim trial' room kind (trap + prayer lectern). |
| `arkngthand` | A | L | dwemer | A Dwemer city-hall of cranking bronze: giant gear-halls, steam vents, a centrifuge chamber at the core and a high catwalk looking over a fabrication floor. Strong vertical and mechanical. | The Centrifuge Warden (Dwemer colossus) | Engineer Bruntha: insists the machines are alive; Rolf the Lost: looted something and can't leave | **The Centrifuge Chorus**: Restore power at the cistern > Align three gear-banks > Reach the centrifuge > Stop or restart the engine. Unlike: engine-puzzle chain; each solved room visibly changes the level | Existing DwemerRooms; add gear-bank puzzle room (redstone-free: lever sequences, existing Puzzles). |
| `halls_of_fabrication` | A | M | dwemer | A conveyor-and-forge hall: long assembly lines of half-built animunculi and a forge-pit finale; foundry glow, hammer sounds. | The First Automaton | Forgemaster Telm: wants a blueprint; Echo of Vanhar: a recording that guides | **Assembly Required**: Find the three blueprint fragments > Defend the forge from rogue constructs > Assemble a helper automaton > Rebuild it or break the line. Unlike: build-a-companion chain; uses existing animunculi_forge blueprint | Use animunculi_forge blueprint at scale; data plan chain. |
| `old_mournhold` | A | L | ruin+city | The sunken twin of Mournhold: the same plazas lying one level down, lightless, with collapsed arcades and a flooded bazaar. You pass under streets whose upper siblings you walked. | The Sealed Chancellor | Old Sedyn: guide, half-blind; The Lamplighter: a ghost keeping a single light | **The Lamplighter's Round**: Light the first lamp > Follow the lamps along the old arcade > Reach the chancellor's vault > Choose to douse the last lamp or relight the city. Unlike: a mirror-city dungeon; the geometry matches the surface city | Reuse Cities.mournhold coordinates in an underground plan; needs a 'mirror plan' option that offsets the city plan 20 blocks down. |
| `missamsi` | B | M | sixth_house_base | A corrupted ashland sanctum of blighted stone, red crystal growths and corprus-ridden chambers; a descent through ash-choked halls to a pulsing chamber. | A Corprus Priest | Brother Alvyn: tracking a missing patrol; Corprus Child: tragic | **The Blighted Stair**: Find the missing patrol's trail > Cleanse three blight-shrines > Defeat the priest > Burn the sanctum. Unlike: blight-themed with disease mechanic; rooms contaminate | Existing 'sixth_house_base' variant; add blight status effect rooms (disease module). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `red_mountain` | S | L | A bruised volcano whose ash plume shades the sky, with a glowing crown and a ring of broken wall. | The Ghostfence line glows red as you cross; the distance fog turns ember-orange. | Terrain exists; add the ashen cloud layer + glow wall at the Ghostfence. |
| `ghostfence_morrowind` | A | M | A long wall of shimmering ghost-light, broken at gate-posts, enclosing the mountain. | Walking through a gate-post gives a brief ward-shimmer shader; guards at each. | New wall-line landmark and gate post set. |
| `vivecs_antlers` | A | M | Two giant antlered peaks over the coast, one broken, with a pilgrims' ladder. | A shrine at the top has a view of cantons; one-way glide drop. | Peak ridge variant. |
| `fields_of_kummu` | B | S | A green flat of old standing shrines in ash grass. | A ceremonial circle where the sky is oddly clear. | Altar variant with grass patch. |
| `rkuldumz_spire` | B | M | A tall brass-pipe tower on a rock island whose top vents steam. | At dawn the spire sings; use as a navigation beacon. | New DWEMER_SPIRE landmark. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `seyda_neen` | A | S | A tiny customs-house port at a lagoon edge with a silt-strider stop and a pier. | The customs-house ledger does not add up, and the clerk has gone to bed ill. |
| `pelagiad` | B | S | A fortified hill village with a half-built fort and a granary. | A hired guard who vanished from a half-built fort with his wages. |
| `ald_velothi` | B | S | A harbour village of one temple and many boat-houses. | A boatman sells ash-glass cheap and insists it is not from the temple. |
| `khuul` | B | S | A single street on a cliff with a long wooden stair to the water. | Seal-hunters bring up a catch that glows, and will not eat it. |
| `gnaar_mok` | B | S | A muddy-swamp town raised on stilts with a mangrove market. | A mudcrab smuggler who pays in pearls and will not say where from. |
| `urshilaku_camp` | A | S | An Ashlander camp of yurts under hide, a wise woman's fire in the centre. | A young Ashlander must complete a coming-of-age trial and needs a witness. |
| `tel_branora` | A | M | A single huge mushroom tower on a lagoon, a master and his servants. | A servant who wants to flee the tower has hidden a forbidden book in her bedding. |
| `hla_oad` | B | S | A tidal fishing village of smoke-racks. | A net-boat has gone missing and the whole village has stopped talking about it. |

## Solstheim

A half-Nord, half-Dunmer island: ash-fall in the south, blizzards in the north, Skaal and Raven Rock between. Already has 40 lore places and 22 quests; what it lacks is settlements that look different from each other and a few unforgettable set pieces.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `raven_rock` | S | M | bespoke: Zoning.generic + Bulwark wall ring | bulwark-mining-colony | The Bulwark wall and the ebony shaft head | ebony grey, Redoran green, ash-streaked timber |
| `skaal_village` | S | M | generic zoning output | yurt-ring-totem | The All-Maker stone circle and bonfire | whale-bone white, pelt brown, snow, blue ice |
| `thirsk_mead_hall` | A | M | generic zoning output | single-long-hall-fort | The great hall with rib arches | spruce beam, bone ribs, rune-carved stone |
| `tel_mithryn` | A | S | generic zoning output | lone-fungal-tower | A single giant mushroom tower | teal, orange-gill, sea-lantern glow |
| `fort_frostmoth` | B | S | generic zoning output | snow-bastion-fort | The frozen drawbridge | imperial stone, ice-blue banners, snowdrift |

**Raven Rock** (`raven_rock`, S/M). A mining colony inside a hastily-built stone bulwark: grey ebony-spoil heaps, a headframe over the shaft, Redoran long-houses and a ring of crude gate towers. Ash drifts on the windward side; a harbour wharf of rough planks below.

* Buildings: The Retching Netch (mining-camp inn); Ebony mine headframe and ore-sorting shed; Temple of the Tribunal chapel, shrunk to a single hall.
* NPCs: Captain Veleth (Redoran guard captain, rationing everything); Foreman Gorm (ebony miner, hides a fatality count); Ashlander Tuvi (trades at the gate, wary).
* Quest "The Spoil Heap Question": 1. Count the missing miners with the foreman 2. Descend a collapsed gallery 3. Find what the digging woke 4. Decide: seal the shaft or break the mine's curse Unlike the rest: a mine-collapse horror chain; uses the shaft head as the town's centre.
* Needs: Existing wall ring; add ebony spoil heap props and a headframe landmark (MINE archetype has one). Kit Redoran.

**Skaal Village** (`skaal_village`, S/M). A ring of low hide-and-bone houses around a central stone circle and fire: no streets, only a trodden snow circle, drying racks, hanging totems. Not a Nord village: smaller, more communal, with a sacred hearth at the exact centre.

* Buildings: The Elder's long fire-house; Totem Hall (carved posts); Hunters' skinning-shed with drying racks.
* NPCs: Elder Storn (wise, tired, keeps the stories); Aslfur the Hunter (brings a trophy that's wrong); Wren the Shaman (hears the wind).
* Quest "Telling at the Fire": 1. Gather three tellers' stories 2. Find the missing verse in a distant cave 3. Return and complete the telling 4. Choose: sing it as written or as it happened Unlike the rest: an oral-history chain that ends in a ritual, not a fight.
* Needs: New cityplan RING_SACRED form (like zoning RING) with a central stone circle yard; Skaal culture kit (bone/hide/pelt blocks).

**Thirsk Mead Hall** (`thirsk_mead_hall`, A/M). One immense hall: a walled compound around a single enormous mead-hall with whale-rib arches, a trophy yard and a fenced boar run. The only Skyrim-region location where the whole 'town' is a single building.

* Buildings: The Mead Hall (60-block timbered nave); Trophy Yard with kill stones; Rib-arch gatehouse.
* NPCs: Skald Hrolf (chief who wants one more kill); Ingrid Bone-Singer (bard, reciting a fight); Odd the Brewer (cheats at everything).
* Quest "One More Beast": 1. Accept the chief's hunt challenge 2. Track the great beast across the snow 3. Bring it back alive or dead 4. Decide the hall's rule for the next season Unlike the rest: a trophy-hunt chain, a boss hunt on open ground.
* Needs: Existing mead_hall variant; add rib-arch (WHALE_RIB landmark) over the hall door, trophy yard props.

**Tel Mithryn** (`tel_mithryn`, A/S). One massive mushroom tower on an ash-flat, wrapped in spore-stairs, with a small garden of glowing fungi and a boat-shaped stable; otherwise nothing. Reads from a mile.

* Buildings: The Tower Cap (study floors); Fungal Garden (open glass house); Sporeling's Stable.
* NPCs: Magister Neloth the Younger (petty, brilliant); Sovor (apprentice, scolded often); Yvara (fishergirl who sells good rumours).
* Quest "A Spore for a Spore": 1. Gather three rare fungi 2. Stabilise a test chamber 3. Return and pass a level of the study 4. Choose between a safe or a reckless tutor Unlike the rest: a gardening-and-lab chain; no combat except hazard.
* Needs: Existing telvanni variant; reuse TELVANNI kit (it is a single tower).

**Fort Frostmoth** (`fort_frostmoth`, B/S). An Imperial fort in a snowfield: a square curtain wall, a drawbridge over a frozen moat, tents of the garrison and a single watch-tower with a blue lantern. Cold and uniform.

* Buildings: Gatehouse with frozen portcullis; Officers' quarters; Garrison armoury.
* NPCs: Commander Hale (garrison commander); Signaller Tisa (night-watch); Chef Orsk (cook, rumourmonger).
* Quest "Thaw": 1. Resupply the fort over ice 2. Defend it from a thaw-raid 3. Find who opened the gate 4. Take the blame or place it Unlike the rest: a siege-endurance chain.
* Needs: Existing fort; add frozen-moat decor.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `temple_of_miraak` | S | L | barrow+daedric | A Dragon Priest's mountain temple, frost-sealed: a long stair to a gate, grand chambers with black-stone ribs, and a sunken inner sanctum where the walls move like ink. | Miraak's Shade | Hermaeus' Whisper: a voice; Brother Tavi: last priest | **The Black Page**: Read the first page at the altar > Follow the page-fragments through the temple > Resist the whispering > Close the book or take it. Unlike: book-lore dungeon, where reading alters the rooms | Special data plan; needs 'shifting walls' ink effect (block-state animation) and the Hermaeus book lectern. |
| `nchardak` | A | L | dwemer | A Dwemer city half sunk in ice: crystalline roof, flooded lower levels, a steam-lift; icy cold bright halls. | The Warden of Nchardak | Engineer Tova: expedition leader; Ghost of Vahl: tell | **A Cold Engine**: Restart the lift > Defrost the pump hall > Cross the flooded galleries > Shut down the engine. Unlike: ice-and-steam hybrid puzzle | Existing DWEMER theme; add ice-sheet rooms. |
| `castle_karstaag_ruins` | A | M | ice_cave+fort | A giant's frozen castle: big-scale rooms (3x scale doors), a throne of ice, frozen warriors. | Karstaag's Winter-Wight | Storm-Rider Vala: explorer; The Frost Maid: ghost | **The Giant's Hall**: Climb the great stair > Pass the frozen guards > Face the throne > Thaw or shatter. Unlike: scale-trick dungeon where everything is oversized | Scale-x2 room plan for ice theme. |
| `kagrumez` | A | L | dwemer | A Dwemer observatory-ruin on a ridge: an open sky-dome room with star-maps, a long gantry, and a pit with a fall. | The Star-Warden | Astronomer Pell: wants constellations mapped; Echo: voice | **The Stars Misaligned**: Climb to the dome > Align star-maps > Descend the gantry > Open the vault. Unlike: astronomy puzzle with real orientation by time of night | Time-of-night puzzle; existing Puzzles module. |
| `gyldenhul_barrow` | B | M | barrow | A large barrow with a tiered nave and a glowing mural of a long-dead king. | King Gyldenhul the Dead | Priest Orn: scout; Spirit Sigrun: mourner | **The King's Last Feast**: Open the barrow > Serve the banquet in the hall > Face the king > Lay him to rest. Unlike: a feast-ritual barrow | Existing barrow; add banquet room. |
| `ashfallow_citadel` | B | M | fort_mage | A ruined Imperial mage-fort in ash, with crumbled wings and a ward-ring. | The Citadel's Warden | Archivist Bel: wants survey; Specter Hallis: soldier ghost | **A Ward Unbroken**: Break the outer ward > Tour the four wings > Reach the tower > Close the ward. Unlike: a ward-dungeon | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `stalhrim_source` | S | M | A frozen crack of blue-white glow in a basin, the ice itself humming. | Approaching it you hear the crystal sing; mining it needs a special pick. | New STALHRIM_SOURCE builder (cyan + packed ice + light). |
| `altar_of_thrond` | A | M | A hilltop altar with a ring of carved posts and a view of the island. | Dawn light through the post ring marks the day. | Altar variant w/ posts. |
| `hvitkald_peak` | A | S | A conical peak with a crown of ice and an eagle's view. | A long climb ending in a storm. | Peak variant. |
| `allmaker_water_stone` | B | S | Six standing stones across the island, each a different element. | Visiting all six unlocks a blessing. | Standing-stone group quest-counter. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `haknirs_shoal` | B | S | A wrecked ship's ribs in surf. | A drowned captain's log sits in a waterproof case among the ribs of the wreck. |
| `bujolds_retreat` | B | S | A bandit camp in a former lodge. | A bandit camp in a former lodge holding a hostage who is more than he seems. |
| `ramshackle_trading_post` | B | S | Shacks around a stove. | A trader who knows everything and says it only for a trade of equal worth. |
| `abandoned_lodge` | B | S | A snow-covered hunter's lodge. | A snow-covered hunter's lodge; footprints lead in and none lead out. |
| `brodir_grove` | A | S | A holy grove of ash-trees. | A holy grove of ash-trees where a druid answers only in questions. |
| `old_attius_farm` | B | S | A farm in the ash. | A farm in the ash where a scarecrow has been moving a field-width each night. |

## High Rock

Gothic spires and feuding court-kingdoms: Wayrest, Daggerfall, Camlorn, Shornhelm and the Orc city of Orsinium, all built from the same kit now. Needs seven distinct castle-towns and a Breton crypt/Orsimer ruin tradition.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `wayrest` | S | L | bespoke: Cities.castleCapital(31) | bay-castle-wheel | Castle Wayrest and the great harbour chain | grey granite, blue slate, silver pennants |
| `orsinium` | S | L | bespoke: Cities.castleCapital(35) (copy of Wayrest) | stepped-orc-bastions | The Great Gate-Hammer | dark iron, rust red, slate, bone trophies |
| `daggerfall` | A | M | generic zoning output | river-castle-twin-bank | Castle Daggerfall on the island | honey sandstone, red tile, wine banners |
| `camlorn` | A | M | generic zoning output | spire-hill-gothic | Castle Camlorn's black-slate spires | cream limestone, black slate, gold finials |
| `shornhelm` | B | M | generic zoning output | walled-keep-grid-red | Castle Shornhelm's red keep | red sandstone, grey tower caps, banner-red |
| `northpoint` | A | M | generic zoning output | cliff-port-moor | Northpoint Lighthouse and keep | slate grey, pine, wet black, white surf |
| `evermor` | B | M | generic zoning output | lake-causeway-island | Evermor Cathedral on an island | blue-white stone, green copper, lake mist |
| `glenpoint` | B | S | generic zoning output | headland-fort-lighthouse | The headland keep | yellow stone, thatch, salt white |

**Wayrest** (`wayrest`, S/L). A harbour capital in a bay: the castle on a rock spur at one horn, a chain-boom across the harbour, long quays and tight guild streets rising in a bowl. Nine gates, a ring of sea-walls, blue pennants. Bright and orderly, maritime.

* Buildings: Castle Wayrest (twin-tower keep); Merchant Guildhall on the quay (long hall, arcaded); Harbour Chain Tower (lever house).
* NPCs: Duchess Marguerite's Chamberlain (court intrigue, a delicate task); Quartermaster Jules (knows what ships carry); Sister Odelle (keeps the harbour shrine of Mara).
* Quest "The Boom Chain": 1. Spot a ship that should not be here 2. Raise the chain by hand 3. Search the hold under the Chamberlain's eyes 4. Name the traitor or their master Unlike the rest: harbour-control chain with a bay-wide set piece.
* Needs: Replace castleCapital with cityplan: HARBOUR + CASTLE_SPUR; add chain-boom prop. Kit 'breton'.

**Orsinium** (`orsinium`, S/L). A stepped fortress-city on a mountain flank: each ring a separate bastion of black stone, trophy poles, forge pits that roar, and a vast gate with a giant hammer. Utterly different from Wayrest: no gardens, no pennants, only mass and fire.

* Buildings: The Great Forge (open pit of fire); Chieftain's Hall (bone-and-iron throne); Trophy Gate with hanging relics.
* NPCs: Chief Gulmak (proud, dislikes soft talk); Smith-Mother Ushra (forges everything, owes no one); Skald Torog (bard of old victories).
* Quest "Hammer on the Gate": 1. Prove yourself at the forge 2. Fight in the Chieftain's trial 3. Defend the gate with the garrison 4. Take the name of the clan Unlike the rest: honour-and-trial chain, no stealth, no mercy.
* Needs: New cityplan: STEPPED_BASTIONS (3 rings, each with palisade); 'orc' kit exists. New landmark GATE_HAMMER; forge pit prop (lava, safe).

**Daggerfall** (`daggerfall`, A/M). A fortified river-mouth town: the castle on a central island linked by two stone bridges to the banks, tall half-timbered houses leaning over the water, tanneries and docks along the bank. Warm sandstone against green water.

* Buildings: Castle Daggerfall on its island; Guild bridge-houses on the span; Tannery Row with drying frames.
* NPCs: Knight Sir Alaric (wants a letter delivered without a seal); Madam Pelline (bridge-toll collector); Tanner Odo (knows smells and secrets).
* Quest "A Toll on the Bridges": 1. Buy the right to cross 2. Smuggle a letter past two toll-houses 3. Foil the toll collector's cheat 4. Choose: raise or ruin the tolls Unlike the rest: a bridge-economy chain with toll-houses as set pieces.
* Needs: CANAL/RIVERSIDE zoning with island castle; breton kit.

**Camlorn** (`camlorn`, A/M). A hill-top city of cream stone and spiky black slate roofs: many thin spires, flying buttresses on the cathedral, long cobbled stair-streets. Dense verticals, bells.

* Buildings: Cathedral of Mara (flying buttresses); Camlorn Castle spire group; Bell-ringers' tower.
* NPCs: Archpriest Elric (bells, penance, grudges); Lady Hvarine (court gossip); Bellwright Tam (sells a story).
* Quest "The Bell That Rang Alone": 1. Climb the bell tower 2. Examine the bell's clapper 3. Discover who has been silencing it 4. Ring it again at dusk Unlike the rest: a tower-mystery chain climaxing with a city-wide bell.
* Needs: HILLTOP form with spire roofs (roof style 'spire'); roof builder: add 'needle' roof type.

**Shornhelm** (`shornhelm`, B/M). A grid-planned walled town clustered round a heavy red keep: tight rows of house plots with tiny gardens and a market square. A planned, military town, dull by design, the foil to Camlorn.

* Buildings: The Red Keep; Armoury hall; Muster-ground with banner poles.
* NPCs: Marshal Ysolde (wants every gate guarded); Sergeant Brom (drunk, wise); Alder the Fletcher (trades secrets for arrows).
* Quest "Night Muster": 1. Run the muster list 2. Find the missing sergeant 3. Catch the deserters in the outer wall 4. Bring them home or let them go Unlike the rest: a military-procedural chain.
* Needs: GRID form; red sandstone kit.

**Northpoint** (`northpoint`, A/M). A rock-bound northern port on a moor: houses clinging to a sea cliff with stairs cut down to the quay, a squat lighthouse, peat-smoke and a stone keep half-lost in the mist. Cold, wet, ringed by moor.

* Buildings: Lighthouse Watch and fog horn; Fishers' Smokehouse; Moor Chapel (a half-roofed ring).
* NPCs: Reeve Maude (stubborn, honest); Peat-Cutter Gill (claims the moor is moving); Keeper Sorley (lighthouse keeper with a second lamp).
* Quest "The Second Lamp": 1. Investigate a second light on the moor 2. Search the peat cuttings 3. Find a drowned ship's lantern 4. Douse or keep the lamp Unlike the rest: a fog-and-moor ghost chain.
* Needs: CLIFF form with moor outskirts props; kit 'breton_moor'.

**Evermor** (`evermor`, B/M). A city on islands in a lake linked by long stone causeways: a cathedral on the central island, houses along the causeway arms, boat-lanes. At dawn it appears to float.

* Buildings: Evermor Cathedral; Causeway toll arch; Fisher's quay hall.
* NPCs: Bishop Mallory (reluctant, sharp); Lamplighter Vey (knows the water); Ferrywoman Iselle (hears all).
* Quest "The Water Rose": 1. Take a boat to the cathedral 2. Find the missing lakebed relic 3. Dive into the flooded undercroft 4. Bring it up or keep it down Unlike the rest: a diving/water chain.
* Needs: Island causeway form (like Vivec's bridges), breton kit.

**Glenpoint** (`glenpoint`, B/S). A small walled port on a headland: a single gate, a keep with a beacon, houses crowded to the end of the quay. Compact, bracing, salt on everything.

* Buildings: Headland Keep; Pilot's House; Salt Pan yard.
* NPCs: Reeve Dunstan (hates smugglers); Pilot Edda (can read any tide); Old Corwin (tells of a wreck).
* Quest "The Salt Run": 1. Watch the tides 2. Spot a ship 3. Catch the smugglers at the pans 4. Seal their cove Unlike the rest: a coast patrol chain.
* Needs: Existing zoning HARBOUR; names/signs only.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `old_orsinium` | S | L | orc_ruin | The first Orc city, buried: huge sloping halls in blackened stone, tilted skeleton-of-a-city streets, a trophy-ringed throne and a collapsed forge. Heavy, brutal geometry. | Old Chief Uzgash the Unburied | Lorekeeper Bagrat: wants the old name found; Grunt: carries a map | **The Name Beneath the City**: Open the ruined gate > Cross the buried streets > Reach the throne > Carry the chief's name back to the new city. Unlike: a lost-city dungeon sharing geometry with the living Orsinium | Data plan: ORC_RUIN rooms (slanted), reuse orsinium kit; marquee entry hooks into the Orsinium cityplan. |
| `cath_bedraud` | A | M | breton_crypt | A knight's crypt under a ruined chapel: long vaulted aisles of effigies, a bone-lined crypt, a hollow font. | The Knight of Ash | Priest Auren: hears whispers; Squire Tomas: won't enter | **Vigil of the Effigies**: Take the knight's vigil > Break three effigies > Face the knight > Lay him to rest. Unlike: a vigil/trial crypt | breton_crypt rooms; effigy prop set. |
| `mthartzel` | A | L | dwemer | A Dwemer cliff-bastion with a bridge-and-gear gate; high steam halls. | The Gate-Warden | Scholar Rue: maps ruins; Mechanic: trapped | **The Bridge That Isn't**: Reach the cliff-gate > Raise the bridge > Clear the gear-halls > Seal the steam vents. Unlike: a bridge-raising chain | Existing; add a bridge-raise puzzle. |
| `sanguines_demesne` | A | L | daedric_ruin | A realm-pocket of red velvet: banqueting halls, endless corridors of mirrors, a throne of bones; the architecture is a party that has gone on too long. | The Revel-Master | Gurgle the Reveler: host; Prisoner: guest of honour | **The Long Party**: Get an invitation > Win three games of chance > Survive the Revel > Choose: join or leave. Unlike: a social-gauntlet daedric dungeon with minigames | Realm-ish structure (like realms module); data plan with game rooms. |
| `doomcrag` | B | M | tower+necromancer | A black spire on a crag; inside, spiral wards, a library of bound books and a roof with a storm engine. | The Doom-Scribe | Apprentice Willa: captured; Archivist: bound | **Bound Pages**: Break in at night > Find the bound books > Free the apprentice > Burn or keep the library. Unlike: a library-theft chain | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `tomb_of_lost_kings` | B | M | breton_crypt | A dynasty's necropolis with named sarcophagi and a family tree carved on the wall; each room is one king. | The Last of the Line | Genealogist Fenn: wants names; Ghost of Idris: a king | **The Line**: Read the first king's stone > Reconcile three claims > Open the final tomb > Name an heir. Unlike: a genealogy puzzle | Data plan with named sarcophagus props. |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `direnni_tower` | S | L | A tall white elven tower on an island, the old Direnni seat. | A bright beacon at dusk, a pilgrimage by boat. | Use CRYSTAL_TOWER builder on an island; Boat trip. |
| `isle_of_balfiera` | A | M | A hill-island with a ring of ruined towers. | A ferry shows a sunken tower at low tide. | Tide-state decor. |
| `wrothgarian_mountains` | A | M | A broken ridge where the sky is permanent dusk. | The Orc road runs under a natural arch. | Arch rock variant. |
| `paragons_remembrance` | B | S | A tall memorial column with names. | Read a name, hear a verse. | Monument variant + lore books. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `weeping_giant` | A | S | An inn built under a toppled stone giant. | A toppled stone giant shelters an inn; its eye-socket well gives water that tastes of tears. |
| `the_lonely_lantern` | B | S | A one-lamp inn on a moor. | A one-lamp inn on a moor: the lamp is lit by a guest who never seems to leave. |
| `koeglin_village` | B | S | A cliff-side village under a lighthouse. | Lamp oil keeps vanishing from the lighthouse store; the keeper blames the tide. |
| `aldhaven_harbor` | B | S | A small harbour with a chain. | A pilot refuses night passage, will only say 'the bell tolls the wrong side'. |
| `morkul_stronghold_high_rock` | A | S | A small Orc palisade, forges roaring. | A blood-oath trial on the forge floor, open to outsiders who ask politely. |
| `moiras_hope` | B | S | A tiny shrine hamlet. | A hamlet shrine where a miracle is claimed, and a pilgrim is missing. |

## Hammerfell

Desert cities of sandstone and domes, Yokudan crypt-halls under dunes, oases, coastal ports and a mountain spine. Sentinel is bespoke; the other 27 towns share one redguard kit with no visible difference.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `sentinel` | S | L | bespoke: Cities.domedCapital(DOME_TEMPLE) | domed-oasis-harbour | The Dome Palace and docks | sandstone, cobalt tile, gold domes |
| `rihad` | S | L | generic zoning output | walled-bazaar-maze | Rihad Minaret and the spice-gate | terracotta rose, saffron, white lime |
| `taneth` | A | M | generic zoning output | cliff-palace-terraces | The Palace of Taneth over the sea | white lime, turquoise tile, gold |
| `elinhir` | A | M | generic zoning output | highland-tier-spire | The Elinhir spire | grey granite, white, blue pennants |
| `dragonstar` | A | M | generic zoning output | arena-ringed-fort | Dragonstar Arena | red-gold sandstone, black iron, banners |
| `abahs_landing` | B | M | generic zoning output | stilt-pier-harbour | The Landing's lighthouse | driftwood, tar black, salt-bleached white |
| `stros_mkai` | B | M | generic zoning output | island-lighthouse-citadel | The Stros M'Kai lighthouse and harbour fort | yellow stone, white plaster, sea blue |

**Sentinel** (`sentinel`, S/L). A harbour capital of domes: a vast palace dome at the back, bazaar streets under striped awnings, an oasis garden square and a long sea-wall. Cobalt and gold against sand: unmistakable from the sea.

* Buildings: Palace of the Crown (great dome); Bazaar of Winds (covered awning street); Harbour Light Mosque-like Temple.
* NPCs: Vizier Samir al-Hadid (cunning, wants a rumour confirmed); Captain Layla Ashraf (harbour guard, impatient); Zareen the Dye-Seller (every colour tells a rumour).
* Quest "The Cobalt Ledger": 1. Follow a dye-merchant's trail 2. Spot a smuggler under a bazaar awning 3. Raid a harbour warehouse 4. Decide who the Vizier's ledger names Unlike the rest: a bazaar-and-harbour intrigue chain.
* Needs: Existing domedCapital; add awning-street archetype and per-quarter dome colours.

**Rihad** (`rihad`, S/L). A walled bazaar-city: narrow lanes under rope-and-cloth awnings, a maze of courtyards, a single tall minaret for orientation and a scented spice gate. Compact, labyrinthine, hot.

* Buildings: Minaret of the Hour; Spice Gate and customs; Courtyard Caravanserai.
* NPCs: Spice-Master Rasul (wants a bag moved); Lila the Guide (guides through the maze); Imam Tahir (teaches a lost verse).
* Quest "A Bag Through the Maze": 1. Take the bag 2. Evade the watch 3. Navigate the maze by minaret shadow 4. Hand it to the intended or open it Unlike the rest: a maze-navigation chain using shadow-time.
* Needs: CLUSTER form with covered lanes; awning-street archetype (shared); new minaret landmark (reuse tower).

**Taneth** (`taneth`, A/M). A seaside city on stepped cliff terraces: a white palace overlooking the water, gardens in turquoise pools, and long stair-lanes to a small harbour. Gleaming, formal, cool.

* Buildings: Turquoise Palace; Pool-garden terraces; Harbour stair-house.
* NPCs: Emir Jalil (wants a poem delivered); Najwa the Gardener (pool-keeper); Harbour-Reeve Ben (keeps a ship book).
* Quest "A Verse for the Sea": 1. Learn the verse from three keepers 2. Climb to the roof 3. Deliver it at dusk 4. Receive a gift Unlike the rest: a poetic/ceremonial chain, no combat.
* Needs: CLIFF form like Markarth's; kit 'redguard_coast'.

**Elinhir** (`elinhir`, A/M). A mountain-city of tiers on a granite spur: long stairs, windy ramparts and one blue-pennanted spire. The cool, grey, windy Hammerfell.

* Buildings: The Spire; Wind-wall ramparts; Tiered granary.
* NPCs: Warden Kaia (hates ceremony); Stairs-Keeper Jul (counts the steps); Brother Amir (scribe).
* Quest "The Hundred Steps": 1. Climb the hundred steps 2. Carry water to the spire 3. Defend the ramparts 4. Light the beacon Unlike the rest: a vertical endurance chain.
* Needs: HILLTOP; granite kit.

**Dragonstar** (`dragonstar`, A/M). A fortress-city with a huge circular arena at its heart, spokes of streets running out to a ringed wall, and a stepped palace beyond.

* Buildings: The Arena; Palace of the Star; Gladiators' quarter.
* NPCs: Arena-Master Dahab (patron of fights); Gladiator Safiya (wants out); Bookmaker Rim (fixes odds).
* Quest "Seven Fights": 1. Enter the arena 2. Win seven bouts across three tiers 3. Uncover a fixed bout 4. Claim victory or expose it Unlike the rest: a sport/arena chain with a corruption twist.
* Needs: New plan ARENA_HUB (reuse ARENA landmark from Imperial City).

**Abahs Landing** (`abahs_landing`, B/M). A pier-and-stilt harbour on a bay: houses on stilts, rope walkways, nets, a lighthouse on a mole. A town built on wood and water rather than stone.

* Buildings: The Landing Light; Pier market; Net-loft guild.
* NPCs: Captain Hadiya (pirates); Old Karim (net-mender); Salwa (tavern-keeper).
* Quest "The Moving Pier": 1. Hear of a pier that moves 2. Row to find it 3. Cut the false moorings 4. Free the captured ship Unlike the rest: a pier-and-pirate chain.
* Needs: HARBOUR with stilt houses; reuse argonian stilt-kit.

**Stros Mkai** (`stros_mkai`, B/M). A small harbour island city: a huge lighthouse and fort at the harbour mouth, tight alleys going up the central hill, boats everywhere.

* Buildings: Harbour Lighthouse; Pirate's Rest tavern; Fort at the mouth.
* NPCs: Captain Makki (pirate chief); Zahra the Harbourmaster (bureaucrat); Old Salim (map-seller).
* Quest "The Island's Price": 1. Arrive by boat 2. Pay the harbour toll 3. Find the hidden dock 4. Steal or defend the map Unlike the rest: an island-pirate chain.
* Needs: Island layout; lighthouse reuse.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `lost_city_of_the_na_totambu` | S | L | yokudan_tomb | A buried city under the dunes: sand-choked streets below ground, sunken temple, glass-roofed houses lit by a thin shaft of light. | The Sand King | Guide Hakim: knows the sand; Spirit of Amina: lost | **A Street Under the Sand**: Find the shaft > Walk the buried street > Open the temple > Free or bind the king. Unlike: an inverted-city dungeon with open sky shafts | Data plan: yokudan_tomb rooms with sand-fall hazards and light shafts. |
| `tuwhaccas_throne` | A | L | yokudan_tomb | A ceremonial throne-hall of an ancient king with a procession of statues; colossal doors and a long colonnade. | The Throne's Warden | Priestess Layla: maps the glyphs; Echo: a king | **The Procession**: Follow the statue procession > Answer three riddles > Reach the throne > Sit or leave. Unlike: a riddle-procession chain | Colonnade room; riddle lectern. |
| `motalion_necropolis` | A | L | yokudan_tomb | A tiered necropolis cut into a cliff, crowded with sarcophagus niches; a lightless stair descent. | The Cliff Warden | Digger Rami: lost his crew; Whisper: dead | **Niches**: Descend the stair > Find the lost crew > Open the central crypt > Close it forever. Unlike: a vertical necropolis | Cliff-cut tomb plan. |
| `volenfell` | A | L | dwemer | A Dwemer ruin under a dune, half-flooded with sand; mechanical halls with drifts. | The Sand Engine | Engineer Mal: trapped; Voice: automaton | **Drift Mechanics**: Clear sand from the lifts > Restart the engine > Escape the flood > Seal the vault. Unlike: sand-and-engine hybrid | DWEMER with sand-drift props. |
| `ahaleb_necropolis` | B | M | yokudan_tomb | A mid-size necropolis with a long bridge hall over a pit, a hidden oasis chamber of palms under skylights, and a final stair of golden doors; sandstone, blue glaze and cool water. | The Oasis Shade | Scholar Imran: copies epitaphs; The Ghost-Child Noor: asks you to carry her home | **The Oasis Below**: Cross the bridge hall > Find the oasis chamber > Carry the ghost-child's token home > Close the tomb or leave the oasis open. Unlike: a tomb where the reward is a hidden garden | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `sanctum_ophidia` | B | M | yokudan_tomb | A serpent-cult sanctum of coiled-column rooms: spiral ramps, scale-patterned floors and a mirror-pool; the final room is a ring of coils around a dais. | The Coil (a many-headed serpent construct) | Snake-Charmer Ali: knows the cult songs; Cultist Ruqa: wants out | **Songs for the Coil**: Learn the first song from Ali > Charm three guardians with the song > Cross the mirror-pool > Destroy the Coil or tame it. Unlike: a music-charming dungeon | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `skyreach_pinnacle` | S | M | A spire of rock standing alone in dunes, ringed by stairs. | A day-long climb to a sunrise view. | Spire rock variant. |
| `aetherian_archive` | A | L | A giant sealed library sphere half out of the sand. | Opens only at midnight. | Dome sphere builder (reuse dome). |
| `onsis_breath` | B | S | A ring of wind-carved arches. | A howling wind that turns musical. | Rock variant with sound. |
| `goats_head_oasis` | A | S | A well-oasis with palms and a half-buried statue. | A mirage that is real once a night. | Oasis variant. |
| `valley_of_scars` | B | M | A canyon whose walls are carved with battle-reliefs. | Echo-voices narrate. | Relief wall builder. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `port_hunding` | B | S | A small port gate and pier. | A cargo manifest lists crates that are heavier than the ship's draught allows. |
| `draggin_tale_inn` | A | S | An inn with a giant draconian tail over the door. | A legend. |
| `thrall_cove` | B | S | A slaver's former cove. | A slavers' cove, now empty; a bell still rings at the tide mark. |
| `saintsport` | B | S | A shrine harbour. | A harbour shrine whose relic was swapped for a fake; the pilgrims have not noticed. |
| `hubalajad_palace` | A | S | A walled estate of a lord. | A noble house prepares a wedding; the bride has hired you to be a guest. |
| `the_silver_pilgrim` | B | S | A roadhouse. | A roadhouse where a pilgrim's wager can be won by walking a hundred steps blindfolded. |

## Elsweyr

Moon-sugar savanna, sandstone cities and moon-temple crypts. Torval is bespoke as a copy of Sentinel's dome layout; the 17 other towns are one kit. The moons themselves (Masser and Secunda) are an untouched identity source.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `torval` | S | L | bespoke: Cities.domedCapital(DOME_TEMPLE) (copy of Sentinel) | twin-moon-concentric | The Mane's Court: twin moon domes | ivory, moon-silver, sugar cream, lapis |
| `rimmen` | S | L | generic zoning output | cliff-palace-over-necropolis | Rimmen Palace and its necropolis | yellow sandstone, rose granite, dark door-stone |
| `senchal` | A | M | generic zoning output | terraced-sugar-port | Senchal Palace and warehouses | whitewash, golden thatch, bright awnings |
| `dune` | A | M | generic zoning output | caravan-wheel | The Great Market Wheel | clay red, striped tent, brass |
| `corinthe` | A | M | generic zoning output | river-garden-valley | Corinthe's Garden Cathedral | green plaster, cream stone, river blue |
| `orcrest` | B | M | generic zoning output | mesa-claw-gate | The Claw Gate | ochre rock, dark iron, red cloth |
| `hakoshae` | B | M | generic zoning output | moonstone-circle-village | A ring of moonstones | pale stone, silver, night-blue cloth |

**Torval** (`torval`, S/L). A capital of concentric rings: a palace of two linked domes (Masser and Secunda) at the centre, ring streets of terraced houses, a processional road aligned to moonrise. Pale cream and silver in the day, lit blue by moon-lamps at night; nothing like Sentinel.

* Buildings: The Mane's Court (twin domes); Moon-sugar Exchange (round trading floor); The Lunar Observatory (open roof).
* NPCs: Court Singer Shahra (chants the moon-hours, hears secrets); Mane's Scribe Jaro (keeps the lunar ledger); Sugar-Duty Warden Ri'dar (knows exactly who evades tax).
* Quest "The Lunar Ledger": 1. Observe both moons from the Observatory 2. Reconcile a price entry 3. Follow the sugar caravans at moonrise 4. Declare the truth at the next moon-full Unlike the rest: a moon-phase-gated chain; quests advance only at specific moonrises.
* Needs: Replace domedCapital for Torval: concentric RING plan with twin-dome landmark and an 'observatory' prop. Needs a moon-phase check helper for quests.

**Rimmen** (`rimmen`, S/L). A cliff city of artisans: a palace on the high cliff, craft districts stepped below, and a necropolis door cut into the base. The city literally sits atop its dead; the dead are never far from the street.

* Buildings: Rimmen Palace (on the cliff crown); Artisan Masterworks Hall (workshops under a long roof); Necropolis Gate Shrine.
* NPCs: Master Artisan Kahlil (wants a lost pattern); Gatewarden Sira (keeps the necropolis); Prince-Advisor Lek (political).
* Quest "A Pattern from Below": 1. Take the Master's commission 2. Open the necropolis gate 3. Copy a pattern from a tomb mural 4. Choose whom to give it to Unlike the rest: an artisan-and-tomb chain.
* Needs: CLIFF plan with tomb gate; link to rimmen_necropolis dungeon.

**Senchal** (`senchal`, A/M). A port of terraced sugar warehouses: stepped white roofs down to a busy quay, striped awnings, sugar-boil chimneys and a hill palace. Bright, sweet-smelling, noisy.

* Buildings: Senchal Palace; Sugar Boil House; Customs Hall.
* NPCs: Harbourmaster Ja'khar (strict); Boiler Tema (burnt hands); Prince's Cousin Rhal (needs a favour).
* Quest "The Bitter Batch": 1. Taste three batches 2. Trace a bad batch to a boiler 3. Confront the dealer 4. Fix the supply or bury it Unlike the rest: an industrial-poison chain.
* Needs: HARBOUR terraces; sugar-yard props (cauldrons, stacks).

**Dune** (`dune`, A/M). A caravan crossroads: a wheel-shaped market with eight radial lanes, tents of striped cloth between low clay houses, camel pens at the rim. A city that feels temporary even where it is not.

* Buildings: The Wheel (central round market); Caravanserai with camel pens; Sugar Stack Mart.
* NPCs: Wheel-Master Khir (tolls); Camel-Driver Mizra (knows roads); Fortune-teller Nuri (tells too much).
* Quest "The Missing Caravan": 1. Take a caravan job 2. Reach the oasis 3. Find the caravan's remains 4. Return with the survivor Unlike the rest: a travel/survival chain across the dunes.
* Needs: RING/spoke plan with tent archetype; kit 'khajiit_caravan'.

**Corinthe** (`corinthe`, A/M). A green river valley city: terraced gardens, canals with arching bridges, a garden-roofed cathedral at the heart. The lush, wet Elsweyr that people forget.

* Buildings: Garden Cathedral; Canal bridge-market; Orchard guildhall.
* NPCs: Gardener-Priest Mura (bees); Boatman Ajh (fishing); Matron Zhel (orchards).
* Quest "The Garden's Bees": 1. Find the dying hives 2. Carry a queen across the canals 3. Hold the hive from raiders 4. Bless the new colony Unlike the rest: a bee-keeping chain.
* Needs: CANAL form; green kit.

**Orcrest** (`orcrest`, B/M). A mesa-top fort-town with a gate shaped like a pair of claws, defensive walls in orange rock, cisterns and rooftop banners.

* Buildings: The Claw Gate; Rooftop Cistern; Watchtower row.
* NPCs: Gate-Captain Raz (tough); Cistern-Keeper Mala (water); Scout Sihe (sees far).
* Quest "The Dry Season": 1. Check the cisterns 2. Find a leak 3. Catch the thief 4. Raise the gate Unlike the rest: water-scarcity chain.
* Needs: HILLTOP; mesa.

**Hakoshae** (`hakoshae`, B/M). A hamlet around a small ring of upright moonstones where tomb-guardians live; low houses in the circle's shadow.

* Buildings: Moonstone Ring; Guardian's Hall; Pilgrim's rest.
* NPCs: Ring-Keeper Dar (ritual); Guardian Su'ri (watch); Pilgrim Ke (lost).
* Quest "Moon Alignment": 1. Find the ring 2. Wait for the alignment 3. Carry the light 4. Open the tomb Unlike the rest: an astronomical chain.
* Needs: Reuse stones structure with village around.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `rimmen_necropolis` | S | L | moon_temple | A vast city of the dead in layers: crypt avenues with moon-lit atria, a lunar clock chamber and a descent that follows the moons' cycle. | The Necropolis Regent | Guide Naro: knows the layers; Shade of an architect: wants his plan completed | **Layers of the Dead**: Descend the first avenue > Align the lunar clock > Open the third level > Face the regent. Unlike: a layered-city dungeon with a clock mechanic | moon_temple rooms; lunar clock puzzle. |
| `halls_of_colossus` | A | L | dwemer | A Dwemer hall of colossal machines; too-large doors, a giant half-assembled construct. | The Colossus | Engineer Bez: trapped; Voice: pre-recorded | **The Half-Built Giant**: Power the hall > Climb the scaffold > Finish or break the colossus > Escape the steam. Unlike: a giant-machine chain | DWEMER scale room. |
| `tomb_of_the_moon_singers` | A | M | moon_temple | A choir-tomb of echoing chambers; each room tuned. | The Last Singer | Chorister Anu: wants a chant; Echo: dead | **The Chant**: Learn the first verse > Sing at the second > Reach the choir > Complete the chant. Unlike: a sound-puzzle chain | Note-block puzzle. |
| `vault_of_the_heavenly_scourge` | A | M | moon_temple | A sealed vault of lunar weapons: a long hall of racked blades that wake when moonlight enters the skylights, a locked antechamber of three keyed moon-gates, and a final chamber with the scourge on a stone altar. | The Scourge (a living moon-blade) | Warden Ta'ish: will only speak at night; Thief Zhaa: locked in a vault cell, wants the blade gone | **Moonlight Through the Skylights**: Enter at night so the blades stay asleep > Align the three moon-gates by raising shutters > Win a duel against the racked blades > Seal the scourge or carry it out. Unlike: a light-and-shutter dungeon where timing, not combat, opens the doors | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `temple_of_agamanus` | B | M | moon_temple | A ridge-top temple whose sanctum is aligned to a single moonrise; terraces climb in four stages with prayer-wheels and a processional stair, then a hollow dome holds the sealed font. | The Temple Warden (a robed guardian) | Priest Ma'ro: caretaker of the stair; Pilgrim Ziri: fell on the third terrace | **The Four Terraces**: Climb the first terrace and turn its prayer wheel > Climb the second and third while the moon rises > Break the seal at the dome > Pour the font or leave it sealed. Unlike: a timed ascent through four terraces | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `prowls_edge_dragonscour` | B | M | dragon_lair | A ridge of colossal bones leading to a lair on a mesa; the open-air scour is a long skeleton you can walk inside, ribs forming arches, and the lair a bowl of bone-white sand. | The Scour-Wyrm | Scout Rhi: wounded, knows the approach; Hunter Khar: proud, wants a trophy | **Walking the Ribs**: Cross the rib arches without being seen > Test the bones for fresh scorch marks > Face the wyrm in the sand bowl > Take the trophy or the egg. Unlike: an open-air dragon hunt with a skeleton for a corridor | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `hill_of_shattered_swords` | S | M | A hill planted with thousands of broken sword-blades. | In wind the hill rings like chimes. | New SWORD_FIELD scatter. |
| `shrine_of_the_cats_divine` | A | S | A stepped shrine with giant cat statues. | A night offering turns the eyes green. | Statue variant. |
| `ne_quin_al` | A | M | An ancient ruined city on a plateau. | A wide dusk vista. | Ruin variant. |
| `anequina_aqueduct` | B | M | A long arched aqueduct across a gorge. | A bridge-top walk. | Aqueduct builder (bridge). |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `moon_sugar_meadow` | A | S | A field of glowing moon-sugar flower. | A field of glowing moon-sugar flower that is only ripe on a night of two full moons. |
| `tathara_sugar_farm` | B | S | A sugar farm with a boil-house. | A burned sugar crop and an owner who will not say who set the fire. |
| `the_prancing_netch` | B | S | An inn of a hanging netch balloon. | An inn under a balloon-netch; a drunk balloonist offers rides for a story. |
| `roadside_twin_moons` | B | S | A roadhouse with two lanterns. | A roadhouse where travellers wager on the moon-phase; the keeper keeps losing. |
| `star_haven_adeptorium` | B | S | A tiny academy under a dome. | A tiny academy under a dome; a star-chart has gone missing from the lectern. |
| `baandari_caravan_stop` | B | S | A tent camp. | A caravan camp whose route book was stolen; the trader will pay for its return. |

## Valenwood

Living-wood cities, the Green Pact, the walking city of Falinesti and canopy bridges. Only Elden Root is bespoke; the 40+ others look like generic Bosmer huts. The identity opportunity is verticality: ground, trunk, canopy.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `elden_root` | S | L | bespoke: Cities.eldenRoot | great-trunk-canopy-tiers | The Elden Tree, 110 blocks | bark brown, living leaf, amber lantern, pale wood |
| `falinesti` | S | L | generic zoning output | walking-tree-moves | The walking tree-city, in four seasonal sites | seasonal leaf: green, gold, bare, blossom |
| `woodhearth` | A | M | generic zoning output | branch-bridge-web | The Great Weave bridge hub | grey-green wood, rope, gold-lit |
| `silvenar` | A | M | generic zoning output | root-hall-hidden-court | The Silvenar's Audience Hall | deep green, silver bark, firefly glow |
| `rawlkha` | B | M | generic zoning output | rope-caravan-camp | The Rope Market | striped canvas, bamboo, saffron |
| `arenthia` | B | M | generic zoning output | cliff-hanging-gardens | The Hanging Gardens | mossy stone, pink blossom, rope |
| `marbruk` | B | S | generic zoning output | stone-fort-glade-ring | The Glade Wall | grey-brown stone, ivy, thatch |

**Elden Root** (`elden_root`, S/L). A city built into and around one colossal tree: ground-level root markets, spiral stairs inside the trunk, and three canopy tiers of platforms and bridges, the palace in the crown. Not one street on the ground: the city is the tree.

* Buildings: Root Market (arched roots as arcades); The Crown Hall (palace at the canopy); Trunk Stair-house (inside the tree).
* NPCs: Wardress Lirael (guards the root gate); Sap-Tapper Beron (knows what flows); Silvenar's Voice Aeth (speaks for a king).
* Quest "The Tree's Fever": 1. Climb the trunk stair by tier 2. Test the sap at each tier 3. Find a blight in the crown 4. Cut it out or let it bloom Unlike the rest: a three-tier vertical ascent chain: each tier a different quest hub.
* Needs: Existing GIANT_TREE; add inner stair, tier platforms, bridging rope-bridge set; per-tier names/signs.

**Falinesti** (`falinesti`, S/L). A tree-city that is in a different place each season: four sites (spring, summer, autumn, winter) with the same tree-plan in different foliage. On the day a season turns the city is simply gone and appears elsewhere.

* Buildings: The Wandering Court; The Root-Tender's Stage (the engine of the walk); Pilgrim bridge-camp.
* NPCs: Root-Tender Maelin (keeps the city's pace); Lorekeeper Eri (remembers each season); Pilgrim Hael (trailing for years).
* Quest "Chasing the Walk": 1. Find the current site 2. Travel with the city for a season 3. Defend it as it crosses a river 4. Settle or free it Unlike the rest: a travelling-city chain tied to the season clock.
* Needs: 4 place ids exist (falinesti_*_site). Needs a seasonal visibility rule (data) and a shared city plan at 4 sites. Hardest of the set.

**Woodhearth** (`woodhearth`, A/M). A city of rope-and-plank bridges strung among tall trees: small tree-houses at each node, long swaying walkways between, dyed banners marking districts. The ground is the 'outside' where the poor live.

* Buildings: The Weave (hub platform); Fisher's Hang (net market); Ground-level Camp.
* NPCs: Bridge-Warden Tilu (repairs); Net-Trader Orwen (sells); Ground-Camp Elder Hush (outsider).
* Quest "The Fraying": 1. Inspect a failing bridge 2. Climb the rotted node 3. Catch the saboteur 4. Rebuild with the ground-camp's help Unlike the rest: a maintenance-and-class chain across the bridges.
* Needs: Plan: TREE_GRID of platforms (like Elden Root canopy) at lower scale; reuse tree builder with branches.

**Silvenar** (`silvenar`, A/M). A hidden court under hollowed root arches: the city is a sheltered amphitheatre of roots with a single audience hall; lantern-glow and fireflies at dusk, mist.

* Buildings: Audience Hall; Root Archive; Firefly Gate.
* NPCs: Silvenar's Scribe (politics); Archivist Loru (lost scrolls); Gate-warden (moody).
* Quest "The Silent Court": 1. Reach the hidden gate 2. Win an audience 3. Find a missing scroll 4. Return it or burn it Unlike the rest: a diplomatic-stealth chain.
* Needs: CLUSTER with root arch props.

**Rawlkha** (`rawlkha`, B/M). A Khajiiti trade-town of canvas and bamboo stalls strung between trees: bazaar tents over bridges, a lantern forest. A different culture inside the forest.

* Buildings: The Rope Market; Moon-sugar Den; Caravan Gate.
* NPCs: Trader Sha'ka (wants a wagon); Fortune-Weaver Pura (predicts); Guard Mohr (tired).
* Quest "A Wagon Through the Green Pact": 1. Take the wagon 2. Cross the forest without cutting trees 3. Avoid a Pact ritual 4. Deliver Unlike the rest: a diplomatic-cultural chain.
* Needs: Mixed-culture plan; Khajiit kit exists.

**Arenthia** (`arenthia`, B/M). A river-cliff city of gardens hung on rope terraces over a waterfall: houses in the cliff face, stairs and flower-lined walks.

* Buildings: Hanging Gardens; Waterfall Temple; Terrace Market.
* NPCs: Gardener Eld (flowers); Priestess Sel (waterfall prayers); Weaver Wynn (moss).
* Quest "A Garden Lost": 1. Find the missing seeds 2. Cross the falls 3. Plant at dawn 4. Defend it Unlike the rest: a gardening chain.
* Needs: CLIFF.

**Marbruk** (`marbruk`, B/S). A fort-town in a glade, ringed by a living wall of thorn and stone, with a central fire pit and watch posts; defensive and tidy.

* Buildings: Thorn-Wall Gate; Fire-Pit Green; Watch Hall.
* NPCs: Ranger Cael (duty); Cook Teva (gossip); Smith Huln (stoic).
* Quest "The Thorn Wall": 1. Inspect the wall 2. Find a gap 3. Hold it 4. Seal it Unlike the rest: a defence chain.
* Needs: RING with thorn-wall prop.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `city_of_ash` | S | L | ayleid_ruin | A burned Ayleid city: ash-choked terraces, melted crystal lamps, a scorched grand cistern. All grey, glints of glass. | The Cinder Magister | Ash-Walker: guide; Voice: a drowned singer | **What Burned**: Cross the ash terraces > Re-light three lamps > Find the cause of the fire > Choose to rebuild or bury it. Unlike: a post-catastrophe ruin chain | ayleid theme + ash overlay; lamp puzzle. |
| `ne_salas` | A | L | ayleid_ruin | A tower-city underground: a spiral of ring galleries around a central crystal pillar, bright and cold. | The Pillar's Warden | Scholar Ona: glyphs; Echo: guide | **The Pillar**: Reach the first gallery > Climb the spiral > Open the pillar > Seal it. Unlike: a spiral-climb chain | Spiral ring plan. |
| `tomb_of_the_apostates` | A | M | tree_tomb | A hollow great tree whose heartwood is carved with tombs; stairs spiral inside the trunk. | The Last Apostate | Keeper: hermit; Seeker: lost | **Heartwood**: Enter the trunk > Read the grain > Face the apostate > Plant a seed. Unlike: a tree-tomb chain | tree_tomb theme with inner spiral. |
| `reliquary_of_stars` | A | M | ayleid_ruin | A domed observatory-vault whose ceiling is a map of the night sky in inlaid crystal; floor rings rotate by lever, and each star lit opens a different archive niche. | The Star-Keeper (a lens-eyed construct) | Astronomer Phael: wants the northern chart copied; The Child-Ghost Ilya: points to the wrong stars on purpose | **A Sky Copied Wrong**: Find the dome's missing lens in an ante-vault > Rotate the floor rings to the sky of a specific night > Open the archive niche that was meant to stay shut > Copy the chart faithfully or alter one star. Unlike: a rotating-floor astronomy puzzle with a forgery choice | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `ossuary_of_telacar` | B | M | ayleid_ruin | A bone-walled ossuary built in terraced galleries; skulls arranged by family line, each tier's door answering to a name read aloud from a plaque. The lowest tier is flooded with bone-meal silt. | The Bone-Warden of Telacar | Caretaker Brun: has not left in a year; Thief Orwin: locked in a niche, bargains loudly | **The Roll of the Nameless**: Read the first plaque and open the door > Match three skulls to their family stones > Retrieve a missing name-plate from the silt tier > Restore the name or take the vault's treasure. Unlike: a name-matching dungeon where lore reading opens the doors | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `labyrinth` | B | M | ayleid_ruin | A hedge-and-root maze that rearranges each time you leave a junction; carved arrows lie, and the exit is only visible from above on a ridge. Wisp-lights are false guides. | The Maze-Warden (a spriggan matriarch) | Wanderer Edda: has been inside four days; The Voice of the Hedge: gives honest directions at a price | **The Wrong Arrows**: Mark the first junction and test the arrows > Climb to the ridge and map the centre > Defeat the matriarch at the heart > Burn the maze or let it regrow. Unlike: a navigation-and-ridge-scouting dungeon | Maze generator (reuse Labyrinthian). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `elden_tree` | S | L | The great tree seen from the whole province, with a glow at its crown. | A distant view at sunrise. | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `reapers_henge` | A | S | A stone circle with a bone-white altar. | A ritual hunt at moonrise. | Standing-circle variant. |
| `thizzrini_arena` | A | M | A ruined arena in the jungle. | A jungle-beast fight. | Arena builder reuse. |
| `waterdancer_falls` | B | S | A tall waterfall with spray rainbow. | A dance at midday. | Waterfall variant. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `roadside_green_bough` | B | S | An inn in a boughed tree. | An inn in a boughed tree: the bough grows a floor each year and nobody knows why. |
| `drabul` | B | S | An Orc outpost in the forest. | An Orc outpost in the forest, in a tense truce with the Bosmer next door. |
| `jathsogur` | B | S | An Orc stronghold. | An Orc stronghold whose chief wants a trial of strength witnessed by a stranger. |
| `dokrin_monastery` | B | S | A forest monastery. | A forest monastery singing the same hymn for three days; the monks are afraid to stop. |
| `supplication_house` | B | S | A hermit hut. | A hermit's hut where a prophecy is carved on the doorframe, one line a year. |
| `sren_ja` | B | S | A tree village. | A treehouse feud between two families over a shared bridge-plank. |

## Summerset Isles

Crystal towers, white gardens, sea-cliff palaces and the Psijic isle. Alinor is bespoke; the other 15 settlements share one altmer kit. Summerset should be the most elegant and the most strange: spires, light, geometry, prisms and sea.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `alinor` | S | L | bespoke: Cities.domedCapital(CRYSTAL_TOWER) | crystal-spire-radial-garden | The Crystal Tower and Royal Palace | white quartz, gold leaf, prismarine, rose glass |
| `lillandril` | S | L | generic zoning output | terraced-library-isle | The Great Library Terraces | white marble, bookcase brown, bronze globes |
| `cloudrest` | A | M | generic zoning output | sea-cliff-balcony-palace | The Cloudrest Palace | white stone, sky blue glass, gold finials |
| `skywatch` | A | M | generic zoning output | orrery-ring-hill | The great Skywatch orrery | blue-white, bronze, night glass |
| `dusk` | B | M | generic zoning output | pale-dune-crescent | The Dusk Lantern Tower | bone white, lilac shadow, lantern gold |
| `firsthold` | B | M | generic zoning output | tidal-island-causeway | The Tide Gate | grey-blue, white foam, wet slate |
| `shimmerene` | B | S | generic zoning output | port-lantern-docks | The Lantern Docks | white wood, teal sail, lantern gold |

**Alinor** (`alinor`, S/L). A radial city of quartz: a great Crystal Tower at the hub, sixteen avenues of white garden-lined streets, a palace on a terrace to one side and a harbour of white quays. Sharp, bright, symmetrical, with prismatic light through glass at noon.

* Buildings: Royal Palace of Alinor; Crystal Library (prism-lit reading room); The Hall of Aldmeri Law.
* NPCs: Chancellor Aelwen (guards the ledger of precedent); Prism-Smith Ildren (cuts light); Seneschal Valia (tests loyalty).
* Quest "The Sixteenth Avenue": 1. Walk all sixteen avenues 2. Find the one that does not appear on the map 3. Open its gate with a prism 4. Choose to publish or seal what lies there Unlike the rest: a geometric/urban puzzle chain where the city plan is the puzzle.
* Needs: Existing bespoke; add the 16-avenue radial plan, prism glass lines and quartz kit, fix Crystal Tower to hub.

**Lillandril** (`lillandril`, S/L). A city of terraced libraries and observatories on a rock: ring galleries of book-stacks open to the sky, bronze orreries, long stair-gardens. Quiet, scholarly, and vertical.

* Buildings: Great Library (open-air stacks); The Orrery Court; Scholars' Hostel.
* NPCs: Chief Librarian Maelia (lost a book); Orrery-Keeper Thalor (wants it recalibrated); Student Rilen (wants a forbidden volume).
* Quest "The Missing Folio": 1. Search the stacks by catalogue 2. Track the folio through three hands 3. Retrieve it from an orrery vault 4. Return or copy it Unlike the rest: a library-detective chain.
* Needs: Terrace plan with bookshelf-rich interiors; LIBRARY archetype (use existing 'lexicon' interiors).

**Cloudrest** (`cloudrest`, A/M). A palace-city on a cliff: balconies cantilevered over the sea, glass floors, a long causeway, and no walls. The sky is the wall.

* Buildings: Cloudrest Palace; Glass Balcony Walk; Sea-lift.
* NPCs: Lord Calantir (bored); Pilot Sea-Lift Ewen (scared); Steward Aviel (loyal).
* Quest "The Glass Floor": 1. Walk the balcony 2. Find the crack 3. Descend by sea-lift 4. Repair or flood Unlike the rest: a vertical-height chain.
* Needs: CLIFF with glass floors; cantilever builder.

**Skywatch** (`skywatch`, A/M). A hill-city topped by a vast ring-orrery, with concentric stone ring-walks, a star-chart terrace and observatories on each ring.

* Buildings: The Great Orrery; Star-Chart Terrace; Ring Observatory.
* NPCs: Astronomer Taleth (precise); Student Maril (brash); Ring-Keeper Ond (old).
* Quest "The Slipped Ring": 1. Measure the slip 2. Climb the orrery 3. Replace a gear 4. Align at midnight Unlike the rest: an astronomy chain with a real-time alignment.
* Needs: HILLTOP with ring-orrery landmark.

**Dusk** (`dusk`, B/M). A crescent harbour city under pale dunes, lilac in the evening, with a tall lantern tower and low pastel houses.

* Buildings: Lantern Tower; Dune Wall; Harbour Court.
* NPCs: Lamp-Keeper Sen (dusk); Harbourmaster Eilen (calm); Pale Merchant Orla (odd).
* Quest "Lamps at Dusk": 1. Light the lamps 2. Find the unlit one 3. Follow a lost boat 4. Guide it in Unlike the rest: a lantern chain.
* Needs: HARBOUR with pastel kit.

**Firsthold** (`firsthold`, B/M). A tidal island town reached by a causeway that floods at high tide; houses on a rock with a tide gate and fisher platforms.

* Buildings: Tide Gate; Fisher Platforms; Rock Chapel.
* NPCs: Gate-Keeper Ro (tide); Fisher Lyn (net); Priest Cael (bell).
* Quest "The Tide Gate": 1. Check the gate 2. Cross at low tide 3. Wait out the flood 4. Open the gate Unlike the rest: a tide-timed chain.
* Needs: Tidal causeway (time-of-day water level not available: use two visible states or a long bridge).

**Shimmerene** (`shimmerene`, B/S). A busy port on a bay with dockside lanterns on high poles, long wharves, crane-masts and sail-lofts.

* Buildings: Lantern Docks; Sail-loft; Master's Hall.
* NPCs: Dock-Master Fen (brisk); Sail-Maker Yeli (artist); Navigator Tor (gruff).
* Quest "Lantern Poles": 1. Replace a lantern 2. Spot a smuggler 3. Catch them 4. Report Unlike the rest: a dock chain.
* Needs: HARBOUR.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `psijic_relic_vaults` | S | L | crystal_vault | A vault of sliding walls and phase-doors: rooms rearrange as you move; time-eddies freeze a room's torches. | The Warden of Hours | Initiate Faen: wants relics returned; The Fold: an echo of a Psijic | **The Hour Between**: Pass the first sliding wall > Solve the phase-door > Take a relic from a frozen room > Choose which relic to return. Unlike: a dungeon where geometry changes, a movement-puzzle dungeon | Data plan with moving-wall rooms (pistons/block swaps) and a time-freeze room (entity freeze). |
| `cathedral_of_webs` | A | M | crystal_vault | A crystal vault wrapped in giant spider-silk, shafts of light through white webs. | The Silk-Mother | Entomologist Veth: studies spiders; Cocooned Scout: trapped | **Silk and Glass**: Cut through the first web > Rescue the scout > Find the egg-chamber > Burn or spare it. Unlike: a spider-silk chain with a light motif | Cobweb scatter + crystal rooms. |
| `the_banished_cells` | A | M | daedric_ruin | A Daedric prison under a spire: cell-blocks of obsidian and chains, windows on nothing. | The Gaoler | Prisoner 7: knows the layout; Warden's Shadow: taunts | **Cell Seven**: Find cell 7 > Open it with three keys > Escape the Gaoler > Choose to free the prisoner. Unlike: a prison-break chain | Cell room set. |
| `traitors_vault` | B | M | crystal_vault | A vault of false doors: each room offers three exits, two of which loop back through mirrors; the gold is real but the true vault is behind a wall you must break. | The Mirror (a duplicate of the player) | Treasure-hunter Sevan: greedy, will sell a bad map; Echo of the Traitor: bitter, hints at the wall | **Three Doors**: Pick the first of three doors > Break the false wall > Fight your reflection > Take the gold or the confession. Unlike: a mirror-and-false-door dungeon | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `tor_hame_khard` | B | M | crystal_vault | A crystal fortress carved into a sea cliff: sea-gates at wave level, a waterfall court, a spine of stairs up the cliff and a lightning-rod tower. | The Sea-Warden | Scout Tevi: cold and soaked; Ghost Soldier Arrin: still on duty | **The Sea-Wall**: Reach the cliff base by boat > Open the sea gate with the tide > Hold the corridor against the swell wraiths > Seal the fortress or leave it open. Unlike: a tide-and-cliff fort with boat access | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `mehrunes_spite` | B | M | daedric_ruin | A red-spired fortress of Dagon's cult: lava channels around a central court, spike gardens, bladed gates and a ritual hall under a brazier-crown. | The Red Magister | Defector Corvin: wants to tell what he saw; Priest Hael: furious, loyal | **The Breaking of the Brazier**: Slip in under cover of a ritual > Break the lava channel locks > Reach the brazier-crown > Burn the cult's book or carry it. Unlike: a cult-fort infiltration with lava as hazard | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `crystal_tower_summerset` | S | L | A needle of crystal, many times taller than anything, glowing at night. | A beam of light across the sea. | Existing CRYSTAL_TOWER; scale to 180. |
| `direnni_acropolis` | A | M | A ruined hilltop acropolis of white columns. | A view at sunrise. | Colonnade builder. |
| `colosseum_of_the_old_ways` | A | M | A ruined ring arena. | A ghost match. | Arena builder. |
| `gryphon_aerie` | B | M | A cliff nest of enormous gryphons. | A gryphon flies past. | Eyrie variant + gryphon entity (exists?). |
| `isle_of_contemplation` | B | S | A tiny isle with a single meditation platform. | A silence zone. | Island. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `kings_haven` | B | S | A small keep and orchard. | A keep and orchard, a dispute over whose apple tree this is. |
| `dawnbreak` | B | S | A hamlet on a bluff. | A hamlet on a bluff where a dawn bell rings at night; no one is ringing it. |
| `shattered_grove` | A | S | A grove of splintered crystal trees. | A growing tree. |
| `the_golden_netch` | B | S | An inn under a balloon. | An inn under a balloon; the yearly balloon race starts here and the winner never returns. |
| `maras_kiss_public_house` | B | S | A bright tavern. | A bright tavern famed for its matchmaking; one match has gone badly wrong. |
| `trebbite_monastery` | B | S | A monastery. | A monastery of a silent vow; a novice has broken it and will say only to you why. |

## Black Marsh

Delta, hist trees, xanmeer ziggurats, stilt huts and mangrove canals. 97 xanmeers and 80 villages exist, but all share one argonian kit. Needs a handful of unforgettable places: the delta capital, a ziggurat city, a Hist grove, a sunken ruin.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `lilmoth` | S | L | bespoke: Cities.lilmoth | stilt-delta-canal | The Great Xanmeer | mangrove green, wet timber, lantern amber, clay red |
| `stormhold` | A | L | generic zoning output | twin-ziggurat-bridge-fort | Two ziggurats joined by a stone bridge | grey-green stone, dark moss, gold hist-sap |
| `gideon` | A | M | generic zoning output | plateau-ring-walls | The Plateau Walls | dry grass tan, warm clay, bone |
| `helstrom` | A | M | generic zoning output | ruin-rim-reborn | A ring of broken xanmeers | cracked black stone, new green, rope |
| `thorn` | B | M | generic zoning output | hist-root-hub | The Hist Root Hub | deep green, sap gold, wet wood |
| `soulrest` | B | M | generic zoning output | tomb-island-necropolis | The Isle of Tombs | pale stone, white lilies, black water |
| `murkmire` | B | S | generic zoning output | hut-spiral-mire | The Spiral Path | mud brown, grey reed, fire-glow |

**Lilmoth** (`lilmoth`, S/L). A delta city on stilts: canals instead of streets, hundreds of boats, a xanmeer ziggurat looming over a market of woven mats, rope bridges between huts and a sea of reed. Flat, wet, green; the sky is the roof.

* Buildings: The Great Xanmeer (ziggurat temple); Canal Market (boats as stalls); Hist Tender's Pool.
* NPCs: Keeper Sees-Many-Moons (hist-tender); Boatwife Mudtoe (knows each canal); Trader Ocheeva (imports).
* Quest "A Boat for the Hist": 1. Accept a boat contract 2. Paddle a canal route 3. Avoid a swamp-beast 4. Deliver a Hist sapling Unlike the rest: a boat-based chain; the whole city is the waterway.
* Needs: Existing lilmoth(); add more canals, stilt bridges; rowboat entities (sailing module).

**Stormhold** (`stormhold`, A/L). A fortress city of two stepped ziggurats on facing hills, joined by a long bridge across a gorge; Argonian guard-houses and a river gate below. A skyline of two flat-topped pyramids and a line between.

* Buildings: North Ziggurat; South Ziggurat; Bridge Gatehouse.
* NPCs: Warden Scales-Her-Chains (stern); Stone-Carver Nook (artist); Scout Fast-Eyes (wary).
* Quest "The Bridge Between": 1. Cross the bridge 2. Learn of a stolen idol 3. Chase the thief down the gorge 4. Restore it Unlike the rest: a two-hill heist chain.
* Needs: New plan: TWIN_LANDMARK + BRIDGE; XANMEER builder at 2 sites.

**Gideon** (`gideon`, A/M). A walled plateau town in the drier east of the delta: rings of terraced clay houses, a high watchtower and a basin well; the only arid Argonian city.

* Buildings: The Basin Well; Watchtower; Clay Granary.
* NPCs: Well-Keeper Dusk (water); Watcher Tamtam (sees); Granary-Mother Seshi (food).
* Quest "The Dry Year": 1. Measure the well 2. Find the leak 3. Escort water 4. Choose Unlike the rest: a drought chain.
* Needs: HILLTOP; clay kit.

**Helstrom** (`helstrom`, A/M). A city reborn amid a ring of ruined temple-stumps: new stilt-houses and rope walkways grown among broken monoliths, old stone steps leading nowhere.

* Buildings: The Broken Ring; Rebuilders' Yard; Rope Walk.
* NPCs: Rebuilder Pa-Dar (stubborn); Mason Noo (careful); Elder Teeba (remembers).
* Quest "Stones That Lead Nowhere": 1. Find a stair 2. Follow it 3. Open the gate 4. Close it Unlike the rest: a reclaim-the-ruin chain.
* Needs: RING plan with ruin props.

**Thorn** (`thorn`, B/M). A hub of a single enormous Hist tree whose roots carry walkways: huts hang from the roots, a pool at the base, sap-lanterns.

* Buildings: The Hist Pool; Root Walk; Sap House.
* NPCs: Hist-Keeper Eeka (chants); Walker Yill (guide); Sap-Cutter Horu (cuts).
* Quest "The Sap Rite": 1. Accept the keeper's blade for the rite 2. Cut a sap-line without waking the tree 3. Carry it across rope-roots in the dark 4. Drink it at the pool and see a vision Unlike the rest: a ritual chain with a vision at the end.
* Needs: GIANT_TREE variant 'hist'.

**Soulrest** (`soulrest`, B/M). A necropolis island city: stilt-house rows separated by narrow canals and white lily beds, bone-barges, quiet.

* Buildings: The Tomb Isle; Barge Dock; Lily Shrine.
* NPCs: Barge-Mother Seeth (carries the dead); Lily-Tender Pi (wist); Priest Eesh (pray).
* Quest "The Last Barge": 1. Board a funeral barge at dusk 2. Row through the lily canals 3. Defend the barge from drowned dead 4. Bury the passenger at the isle Unlike the rest: a funerary boat chain.
* Needs: Canal + tomb props.

**Murkmire** (`murkmire`, B/S). A remote hut spiral in the deep mire, a path winding to the centre fire; mist and croaks.

* Buildings: The Spiral Fire; Hunters' Hut; Rice Paddy.
* NPCs: Elder Nulaa (old); Hunter Teek (silent); Child Pip (curious).
* Quest "The Spiral Path": 1. Walk the spiral and hold your tongue 2. Answer three questions from the elder at the centre 3. Carry a coal back out along the path 4. Light the fire at your own door Unlike the rest: a ritual walk with a quiet ending.
* Needs: RING hut spiral.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ruins_of_mazzatun` | S | L | xanmeer | A xanmeer of stepped chambers where the floor is a flooded city; glowing sap-vein walls, a central shaft; a vertical descent with water rising as you go. | The Mazzatun Lich | Guide Quill: knows the stair; Ghost of Xul: cursed | **The Rising Water**: Enter the top level > Descend while the water rises > Open the sump gates > Reach the lich and seal it. Unlike: a rising-water timer dungeon | Water-level mechanism (Create via water fill) and xanmeer room set; marquee data plan. |
| `shrine_of_the_black_maw` | A | L | xanmeer | A sacred shrine built into a gaping rock mouth; processional halls and a great black pool. | The Maw's Priest | Pilgrim Ush: shy; Voice: the Maw | **The Black Offering**: Enter the maw > Offer at the first altar > Pass the pool > Choose. Unlike: an offering/ritual chain | Special entrance shape; existing xanmeer. |
| `bogmother` | A | M | xanmeer | A fetid root-cave and hag-lair where a mother-of-bog spawns; walls of knotted root. | The Bogmother | Hunter: limb lost; Hag-tender: afraid | **The Mother**: Find the lair > Free the captives > Fight > Burn. Unlike: a creature-lair chain | Natural cave theme + root dressing. |
| `temple_of_sul` | A | M | xanmeer | A sun-temple with bright shafts and a gold altar. | The Sun-Priest | Priestess: kind; Heretic: shouting | **The Sun's Hour**: Climb > Wait for noon > Light the altar > Choose. Unlike: a time-of-day chain | Time gate. |
| `the_vile_pavilion` | B | M | daedric_ruin | A rotting banquet pavilion on the mire; floating platforms joined by plank walks, a long table with plates that still steam, and a lord who thinks you are late for dinner. | The Pavilion Lord | Late Guest Nesa: won't leave her seat; Cook Okun: dead for years, still cooking | **A Dinner Long Cold**: Take a seat at the long table > Serve three courses without spilling > Find the poisoned dish > Leave politely or end the feast. Unlike: a banquet-etiquette dungeon | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |
| `teeth_of_sithis` | B | M | xanmeer | A ring of black teeth rising from the mire around a sunken hollow; paths of root between each tooth, each tooth a different trial. | The Sithis-Born | Cultist Vara: fervent, whispering; Argonian Hunter Nek: quiet, wary | **Counting the Teeth**: Count the teeth and find the odd one > Pass each tooth's trial > Enter the hollow > Close the ring or become part of it. Unlike: a ring-of-trials dungeon | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `weeping_wamasu_falls` | S | M | A high waterfall with the shape of a giant weeping creature in the rock. | The falls echo a lament at dusk. | Waterfall variant with sound. |
| `hatchlings_crown` | A | S | A hill crowned with a ring of egg-shaped stones. | Eggs hum. | Monument variant. |
| `xal_haj_ei_shrine` | A | M | A shrine in a hollow reed-forest. | A sacrifice of reeds. | Altar variant. |
| `plateau_of_the_traveler` | B | S | A lone rock plateau with a view. | A view of the delta. | Rock. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `hatching_pools` | A | S | A ring of warm pools. | A ring of warm pools where a clutch is late hatching; the tenders think something is wrong. |
| `hutan_tzel` | B | S | A fishing hamlet. | A fishing hamlet whose nets bring up carved bone instead of fish. |
| `root_whisper_village` | B | S | An abandoned hist-village. | An abandoned Hist village where the roots still whisper when someone enters. |
| `stay_moist_mansion` | B | S | A merchant's stilt mansion. | A merchant's stilt mansion with a hidden room of ledgers he cannot show. |
| `grave_singers_lodge` | B | S | A lodge of singers. | A lodge of singers who perform for the dead; a new voice has joined that no one hired. |
| `jeevu_rice_paddy` | B | S | A rice paddy. | A rice paddy flooded out of season; the farmers think it was done on purpose. |

## Foreign lands (Atmora, Akavir, Yokuda, Thras, Pyandonea)

Atmora, Akavir, Yokuda, Thras and Pyandonea: 42 places in total, almost all dungeons or lone ruins, no cities, no quests beyond a 10-quest side file. They are the 'wow, another world' places and need three or four showpiece sites each rather than breadth.

### Marquee settlements

| id | tier | effort | now | silhouette | landmark | palette |
| --- | --- | --- | --- | --- | --- | --- |
| `kjolvik` | A | M | generic zoning output | ice-shelf-long-halls | The Ancient Longhall under glacier | blue ice, black timber, whale-bone, aurora green |
| `tiger_plains_village` | A | M | generic zoning output | pagoda-terrace-valley | The Tiger Shrine pagoda | vermillion, black lacquer, white paper, grey tile |
| `tang_mo_harbor_village` | B | M | generic zoning output | junk-harbour-lanterns | The Lantern Mast | red lacquer, indigo, rope, bamboo |
| `pyandonean_fleet_anchorage` | B | M | generic zoning output | shell-raft-fleet | The Coral Flagship | shell white, coral pink, abyss blue |
| `hundings_dojo` | B | S | generic zoning output | sunken-courtyard-dojo | The Sword Courtyard | sand, blue tile, white sail cloth |

**Kjolvik** (`kjolvik`, A/M). A last Atmoran hold on an ice shelf: ten long halls half-buried in drifts, whale-bone arches, a stone-lined harbour where ice cracks, the aurora overhead. Primeval Nord, older and harsher than Skyrim's.

* Buildings: The Elder Longhall; Whale-Bone Arch; Ice-Harbour.
* NPCs: Jarl Hrafn (last jarl); Skald Rolla (sings the migration); Hunter Grim (has seen a dragon).
* Quest "The Last Winter Feast": 1. Hunt for the feast 2. Defend the hall from an ice-wight 3. Hear the migration song 4. Choose to go or stay Unlike the rest: a last-stand chain at the end of the world.
* Needs: New kit 'atmoran_ice'; zoning RING with long halls; reuse Nord kit variant with blue-ice palette.

**Tiger Plains Village** (`tiger_plains_village`, A/M). A mountain village of curved-roof pagodas on rice terraces, a vermilion shrine gate, bamboo, paper lanterns. The most alien place in the pack, with a strong horizontal roofline instead of Nordic gables.

* Buildings: Tiger Shrine Pagoda; Terraced Rice Hall; Bamboo Bridge-house.
* NPCs: Master Hiro (teaches blades); Lady Mei (weaver); Ghost Tiger-Monk (guard).
* Quest "The Tiger's Test": 1. Climb the terraces 2. Pass three trials 3. Defeat the guardian 4. Choose to teach or keep Unlike the rest: a martial-trial chain.
* Needs: Akaviri kit exists; add pagoda roof builder (curved eave) as a Roofs option.

**Tang Mo Harbor Village** (`tang_mo_harbor_village`, B/M). A harbour of lacquered junks and floating lantern-masts, houses raised on bamboo piles with curved roofs, a dragon-prowed ship at the quay.

* Buildings: Lantern Mast; Junk Wharf; Tea-house.
* NPCs: Captain Wen (sails); Tea-Master Li (knows); Pirate Jin (wary).
* Quest "The Silent Junk": 1. Board a junk found adrift at dawn 2. Search its holds 3. Fight what is hiding in the lantern mast 4. Sail it home or sink it Unlike the rest: a derelict-ship chain.
* Needs: HARBOUR with Akaviri kit.

**Pyandonean Fleet Anchorage** (`pyandonean_fleet_anchorage`, B/M). A fleet at anchor: a raft-city of shell-hulled ships lashed together, a coral flagship, and serpent-prowed boats, gleaming wet.

* Buildings: Coral Flagship; Raft Market; Serpent Hall.
* NPCs: Admiral Nehra (cold); Shell-Smith Ilu (artist); Captive (desperate).
* Quest "The Flagship": 1. Row to the raft-city by night 2. Find the admiral's cabin 3. Break the coral chain 4. Escape with a captive Unlike the rest: a ship infiltration chain.
* Needs: Sailing-module platform; shell kit.

**Hundings Dojo** (`hundings_dojo`, B/S). A sunken courtyard dojo on a Yokudan island: a bowl of sand, a ring of columns, tiers of benches and a single bright pavilion.

* Buildings: Sword Courtyard; Pavilion; Bench Tiers.
* NPCs: Master Sahar (sword); Student Jai (angry); Ghost (witness).
* Quest "The Final Lesson": 1. Train with the master's stances 2. Spar the student 3. Win by a legal move you were taught 4. Teach the next student or leave the dojo Unlike the rest: a teaching chain with duels.
* Needs: Existing estate; arena shape.

### Marquee dungeons

| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `wolf_jarls_howe` | S | L | atmoran_tomb | A great Atmoran barrow under a hill of snow with a long ship buried whole; the burial ship is the dungeon's central room. | The Wolf Jarl | Thane Eirik: guide; Spirit of the Pack: wolf | **The Ship Under the Hill**: Dig the entrance > Cross the ship deck > Face the jarl > Choose. Unlike: a buried-ship dungeon | Special data plan; ship-hull room. |
| `frost_throne_of_kamal` | S | L | akaviri_ruin | A throne-hall of ice and iron; a giant ice throne in a hall of ice pagodas. | The Frost Emperor | Spirit: wise; Tiger-Monk: guard | **The Cold Throne**: Climb > Cross > Fight > Sit. Unlike: a throne chain | Ice-pagoda rooms. |
| `sunken_temple_of_the_yokudan_kings` | A | L | yokudan_tomb | A sunken sea-temple of flooded halls, coral shelves and air pockets; a king's funeral barge fills the central hall and the walls are carved with a voyage. | The Drowned King | Diver Sahl: low on breath, knows the pockets; Queen's Spirit: guides by sound | **The Barge in the Dark**: Dive and find the first air pocket > Cross the coral shelves > Reach the funeral barge > Free the king's spirit or take the crown. Unlike: an underwater breath-management dungeon | Water-filled structure; air-pocket rooms; existing underwater mechanics. |
| `coral_tower_of_thras` | A | L | coral_pit | A tower of living coral in a sea of plague: rooms of pulsing polyps, bright pink and poison green, a spiral reef stair and a heart-chamber at the top. | The Plague-Coral | Survivor Mirel: coughing, knows the cure; Diver Oen: lost his team | **The Plague Reef**: Climb the first spiral > Clear polyp-nests with fire > Find the cure's ingredient > Burn the heart or cure the reef. Unlike: a disease-and-fire dungeon | Coral kit; poison status. |
| `orgnums_citadel` | B | M | sea_vault | A sea-serpent citadel on a crag: boat-lifts, brine tanks, serpent pens and a throne hall with a window onto the deep. | Orgnum | Spy Hela: wary, needs extraction; Prisoner Kai: enslaved fisherman | **The Serpent's Window**: Approach by sea > Climb the crag > Free the captives from the pens > Face the lord or seal the window. Unlike: a citadel assault with a sea approach | Existing theme rooms and set pieces; a marquee dungeon data plan only (no new blocks). |

### Marquee landmarks and moments

| id | tier | effort | what it is | the moment | needs |
| --- | --- | --- | --- | --- | --- |
| `dragon_graveyard_of_akavir` | S | M | A valley floor of enormous ribcages. | The wind sings through ribs. | Giant-rib scatter. |
| `the_frozen_dragon_mound` | A | S | A mound of ice with a dragon's head frozen. | A faint breath. | Dragon mound variant. |
| `temple_of_the_serpent_emperor` | A | M | A tall serpent-roofed temple. | A bell. | Pagoda builder. |
| `temple_of_satakal` | B | M | A temple to the world-eater snake on an island. | A ring of fire. | Snake-ring builder. |

### Distinctive minor places

| id | tier | effort | what it is | hook |
| --- | --- | --- | --- | --- |
| `saarvik_landing` | B | S | A landing of broken ships. | A landing of broken ships; a captain's map is tucked under a barnacled hull. |
| `skjaldborg` | B | S | A shield-fort. | A shield-fort still under siege by an enemy that left a thousand years ago. |
| `tang_mo_watch` | B | S | A watchtower. | A watchtower with a signal fire that lights itself at dusk. |
| `ra_gada_encampment` | B | S | A camp. | A camp of an army that never knew the war had ended. |
| `serpent_pens` | B | S | A serpent pen. | A pen of captive serpents that escape each tide and return by morning. |
