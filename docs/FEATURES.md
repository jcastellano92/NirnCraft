# Skycraft feature guide

How each part of Skyrim is reproduced. **[Core]** means the custom Skycraft Core mod; anything else names the
third-party mod that provides it.

## You start as a nobody

* **Race selection** [Core]: on first join, pick one of the ten races (Nord, Imperial, Breton, Redguard, High Elf,
  Wood Elf, Dark Elf, Orc, Khajiit, Argonian). Each comes with Skyrim's starting skill bonuses (+10 to one skill,
  +5 to five others), passives (frost, fire, poison or magic resistance, extra Magicka, claws, water breathing) and a
  once-a-day greater power: equip it in the magic menu (**P**) and use it with **Z**.
* **Digging is earned** [Core]: you can mine ore veins (pickaxe only) and chop trees (axe only), but terrain can't be
  dug until you unlock Mining perks:
  * **Excavator** (Mining 10): dirt, sand, gravel, clay and snow.
  * **Stonebreaker** (Mining 30): stone and deepslate.
  * **Deep Delver** (Mining 75): obsidian and end stone.

  Blocks you placed yourself are always breakable. `restrictDigging` in `config/skycraft-common.toml` turns this off.

## Skills, leveling and perks [Core]

* **29 skills.** Skyrim's 18 (One-Handed, Two-Handed, Archery, Block, Heavy Armor, Smithing, Destruction,
  Restoration, Alteration, Conjuration, Illusion, Enchanting, Light Armor, Sneak, Lockpicking, Pickpocket, Speech,
  Alchemy), Unarmed, **Polearms**, the Elder Scrolls classics **Mysticism** and **Acrobatics**, and the life skills
  Mining, Woodcutting, Fishing, Hunting, Athletics, **Riding** and **Cooking**.
  * **Polearms:** spears, halberds and glaives in every smithing tier (forge them at the forge; Better Combat
    movesets). Perks: Lancer, Long Reach, Impale, Set Against Charge, Sweeping Arc, Phalanx, Pinning Strike, Skewer.
  * **Acrobatics:** trained by running jumps and surviving long falls. Higher jumps, softer landings, Double Jump,
    Tumble (sneak as you land), Aerial Strike and Cat Fall.
  * **Riding:** trained by real distance in the saddle and mounted combat. Faster horses, Stable Hand, Mounted Combat,
    Horse Archer, Trample, Bond and Cavalry Charge.
  * **Cooking:** every dish from a cooking pot trains it and carries its cook's skill: meals regenerate health, and
    perks add healing, stamina, cures, Health Boost, extra portions and more.
* **Skills improve by use.** Mining ore raises Mining; hitting with a sword raises One-Handed; blocking raises Block,
  and so on. A skill meter pops up as you gain XP, and "Mining increased to 23" shows when the skill levels up.
* **Skyrim's formulas.** XP per skill level is `mult × level^1.95 + offset`. Each skill increase gives character XP
  equal to the new skill level. Going from level L to L+1 takes `(L + 3) × 25` character XP.
* **Level up.** Each character level gives +10 Health, Magicka or Stamina (your choice) and one perk point.
* **Look to the heavens.** Press **K** for a starfield of 29 constellations with 255 perks, each drawn over its
  guardian sign; scroll, use the arrow keys, or click a dot in the strip along the bottom to jump to any skill.
* **Legendary skills.** A skill at 100 can be reset to 15 to refund its perks.
* **Training.** Pay trainers (villagers by profession) to raise a skill, up to 5 times per level.
* **Skill books.** Reading one for the first time raises its skill by 1.

## Health, Magicka, Stamina [Core]

* Skyrim pools and HUD bars: Magicka on the left, Health in the center, Stamina on the right. The bars fade out when
  full.
* **Stamina:**
  * Sprinting drains it; at 0 you can't sprint until it recovers.
  * Power attacks and blocking cost stamina.
  * It regenerates after a short pause.
* **Magicka** pays for spells and regenerates more slowly in combat.
* **Health:** vanilla regeneration is replaced by Skyrim-style regeneration, which almost stops in combat.
* **Hunger:** with Skyrim hunger on, food never drains; eating heals and restores stamina.

## Combat [Core + Better Combat]

* **Weapon families.** Daggers, swords, war axes and maces (One-Handed); greatswords, battleaxes and warhammers
  (Two-Handed); bows (Archery). Each family has its own perks:
  * axes cause bleeding
  * maces crush through armor
  * swords and greatswords score critical hits
* **Power attacks.** Hold the **left mouse button**, or attack while sprinting. They cost stamina, deal 1.5×
  damage (1.75× two-handed) and stagger.
* **Blocking.**
  * Hold the **right mouse button** to block with a weapon (30–70% less damage, costs stamina).
  * Shields block Skyrim-style: they reduce damage rather than nullify it, and Block perks add knockback, bash
    damage, arrow deflection and disarming.
* **Sneak attacks** against unaware targets:
  * 3× damage (6× with Backstab)
  * bows 2× (3× with Deadly Aim)
  * daggers up to 15× with Assassin's Blade

  Crouching makes you harder to detect, and the HUD shows the sneak eye (HIDDEN / DETECTED).
* **Armor skills.** Heavy and Light Armor add protection as they level. Heavy armor slows you down until you take
  Conditioning.

## Magic

* **Spells are learned from spell tomes.** You find them in dungeons and buy them from court wizards (librarians).
  Read a tome to learn its spell for good.
* **Schools and casting.** Destruction, Restoration, Alteration, Conjuration and Illusion, in Novice through Master
  tiers.
  * Fire-and-forget spells: click the mouse button of the hand the spell is in (left mouse for the right hand, right
    mouse for the left hand).
  * Concentration spells: hold that button.
* **Costs** follow Skyrim's formula, so higher skill and school perks make spells cheaper.
* **Magic menu** on **P**: equip spells, shouts and powers.
* **Soul Trap** fills soul gems for enchanting.
* **115 spells in six schools.** Every Skyrim spell with its level, base cost and behaviour, including runes,
  cloaks, walls, the master storms (Lightning Storm is a charged beam), the three wards, the whole Turn/Repel Undead
  line with Sun Fire, Stendarr's Aura and Vampire's Bane, Magelight, Equilibrium, Ash Shell, Dragonhide, Bound
  Dagger, the necromancy line from Raise Zombie to Dead Thrall (aim at a body to raise that very creature), the
  thralls, Flaming Familiar, Seeker, Banish and Command Daedra, Clairvoyance, Rout, Call to Arms and Hysteria.
* **Mysticism**, the sixth school: Telekinesis, Detect Life, Soul Trap, Slowfall, Dispel, Water Walking, Absorb
  Health/Stamina/Magicka, Mark and Recall, Divine Intervention (nearest temple of the Nine), Almsivi Intervention
  (nearest Tribunal temple), Reflect Spell, Spell Absorption, Levitate, Telekinetic Hurl, Spell Turning, Mass Dispel.
* **Skill only from real effects.** Spells train their school only when they do something: buffs only near enemies
  and not when refreshed, lights only in the dark, wards by what they absorb, Clairvoyance once per 30 seconds.
* **Spellmaking Altar** (forge it, or find one): combine up to four effects you know (from your spells) into your
  own spell, with range, magnitude, duration and area. Oblivion's cost formula, a gold fee, and a school skill
  requirement for powerful spells. Forget a made spell with **X** in the magic menu.
* **Staff Enchanter** (Dragonborn): bind any spell you know into an Unenchanted Staff with a filled soul gem. The
  staff casts it with no magicka; the soul's size sets its charges.

## Dragons and the Voice

* **Dragons** circle, breathe fire or frost, land to bite and tail-whip, and attack the world at random once you're
  level 3 or higher.
* **Dragon souls.** Killing one lets everyone nearby absorb its soul.
* **Word Walls.** Ancient Nordic shrines in the mountains teach Words of Power. Spend dragon souls to unlock words,
  then shout with **Z**; hold longer for more words. All 26 of Skyrim's shouts, each with its three words and
  per-word recharge. Slow Time really slows time around you (creatures and projectiles run at 1/2, 1/3 or 1/5 speed),
  Call Dragon brings a dragon to your side, Call of Valor a hero of Sovngarde, and Soul Tear raises its victim as
  your thrall. The Blessing of Talos shortens every recharge by 20%; Dragon Aspect by a further quarter.

### Canonical shout ids

Word Walls, quests and commands name shouts by these ids (saved in player data and on walls, so they never change).
`Shout.byId` also accepts the three dragon words (`fus_ro_dah`) or the first word (`fus`); unknown ids never crash:
a wall with an unknown id keeps it and teaches a stand-in shout. Placement code assigns a wall with
`WordWalls.assign(level, pos, id)`.

| Id | Words | Recharge (s) |
| --- | --- | --- |
| `unrelenting_force` | FUS RO DAH | 20 / 25 / 45 |
| `fire_breath` | YOL TOOR SHUL | 30 / 50 / 100 |
| `frost_breath` | FO KRAH DIIN | 30 / 50 / 100 |
| `whirlwind_sprint` | WULD NAH KEST | 20 / 25 / 35 |
| `become_ethereal` | FEIM ZII GRON | 20 / 30 / 40 |
| `clear_skies` | LOK VAH KOOR | 5 / 10 / 15 |
| `aura_whisper` | LAAS YAH NIR | 30 / 40 / 50 |
| `slow_time` | TIID KLO UL | 30 / 45 / 60 |
| `marked_for_death` | KRII LUN AUS | 20 / 30 / 40 |
| `disarm` | ZUN HAAL VIIK | 30 / 35 / 40 |
| `elemental_fury` | SU GRAH DUN | 30 / 40 / 50 |
| `kynes_peace` | KAAN DREM OV | 40 / 50 / 60 |
| `animal_allegiance` | RAAN MIR TAH | 50 / 60 / 70 |
| `storm_call` | STRUN BAH QO | 300 / 480 / 600 |
| `dragonrend` | JOOR ZAH FRUL | 10 / 12 / 15 |
| `ice_form` | IIZ SLEN NUS | 60 / 90 / 120 |
| `throw_voice` | ZUL MEY GUT | 30 / 15 / 5 |
| `dismay` | FAAS RU MAAR | 40 / 45 / 50 |
| `call_dragon` | OD AH VIING | 5 / 5 / 300 |
| `call_of_valor` | HUN KAAL ZOOR | 180 / 180 / 180 |
| `cyclone` | VEN GAAR NOS | 30 / 45 / 60 |
| `drain_vitality` | GAAN LAH HAAS | 30 / 60 / 90 |
| `soul_tear` | RII VAAZ ZOL | 5 / 5 / 90 |
| `bend_will` | GOL HAH DOV | 10 / 90 / 120 |
| `dragon_aspect` | MUL QAH DIIV | 5 / 5 / 5 |
| `battle_fury` | MID VUR SHAAN | 20 / 30 / 40 |

Weather hooks: `ShoutHooks.onClearSkies` / `onStormCall` let the weather and visuals code react to those shouts.
Spell sounds all go through `SpellSounds` (slots the audio module can override), and summoned creature types through
`Summons.entityType` (e.g. a registered `skycraft:flame_atronach` replaces the blaze stand-in automatically).

## Enemies and spawning

* **Skyrim enemies:**
  * bandits (melee and archers), bandit chiefs and bandit camps
  * draugr and Draugr Deathlords (bosses that shout)
  * skeevers, trolls and frost trolls
  * giants, which launch you into the sky
  * dragons
  * vanilla mobs reskinned by name as Skyrim creatures (Frostbite Spiders, Hagravens, Forsworn, Cliff Racers…)
* **Leveled enemies.** Enemies scale with your level and earn Skyrim rank names (Bandit Outlaw → Bandit Marauder,
  Restless Draugr → Draugr Scourge…).
* **Day and night spawning.** Bandits roam by day; the undead rise at night and lurk in the dark. Creepers and
  phantoms are gone.
* **Loot bodies.** Slain creatures leave a body you open to loot.

## Biomes of Nirn [Core]

* **Over 110 biomes, one per corner of Tamriel.** Each region of the map has its own palette of biomes, picked by
  terrain (coast, river, lowland, upland, peak, cave) and local climate, and region borders blend organically:
  * Skyrim: Whiterun's golden tundra, tall pine forests, misty Falkreath, the Rift's autumn aspens, the Reach's red
    crags, Hjaalmarch's marshes, Eastmarch's steaming hot springs, the Pale's snowfields, Winterhold's glacier, the
    Throat of the World, the iceberg-strewn Sea of Ghosts, and Solstheim's ash.
  * Morrowind: ash wastes with ash storms and lava vents, Red Mountain, the Bitter Coast, the Grazelands, the
    Ascadian Isles and the Telvanni forests of giant mushrooms.
  * Black Marsh's swamps and Hist groves, Elsweyr's badlands, dunes, savanna and moon-sugar fields, Valenwood's
    walkable Graht-oaks, Summerset's crystal coasts, Hammerfell's Alik'r desert, High Rock's moors and mountains, and
    the foreign lands beyond the seas (Atmora, Akavir, Yokuda, Pyandonea, Thras).
  * Underground: Dwemer caverns with old pipework, Blackreach-like Falmer glow caves, ice caves, lava caves and
    mushroom grottos, alongside vanilla's dripstone, lush and deep dark caves.
* **New blocks:** ash and ash layers, volcanic rock, autumn aspen leaves, emperor parasol and Telvanni mushrooms,
  Graht-oak, palm and Hist wood, Hist sap, crystal, coral stone, dune sand, swamp reeds, scathecraw and ash grass.
* `/skybiome [pos]` (operators) shows the region, palette and terrain context behind the biome at a position.

## Towns, cities, dungeons, roads and secrets

* **Towns and cities:** ChoiceTheorem's Overhauled Village, Towns & Towers, Dungeons & Taverns (inns).
* **Dungeons and landmarks** [Core]: every named site of Skyrim's map at its real place (about 370: barrows, caves,
  Dwemer ruins, forts, camps, towers, shipwrecks, groves, the 13 Standing Stones, Word Walls and dragon mounds), with
  High Hrothgar and the 7,000 Steps, Labyrinthian, Sky Haven Temple, Blackreach, Bleak Falls Barrow's claw door and
  the shrines of all sixteen Daedric Princes in every province. Beyond Skyrim, culture ruins of each province (Ayleid
  ruins, Velothi tombs, xanmeer, moon temples, tree tombs, crystal vaults, Yokudan tombs, Atmoran ice tombs...) and
  the endless lands' caves, forts and camps.
* **Roads** [Core] connect settlements, with signposts and lantern posts. Travelers, Khajiit caravans, guard patrols
  and the occasional bandit ambush walk them.
* **Location discovery** [Core]: "DISCOVERED: Bleakwind Barrow" banners with Skyrim-style names, shown on the
  compass and on the Skyrim map.
* **Per-player loot chests** (Lootr): every player gets their own loot from each dungeon chest.
* **Locked chests** [Core]: Novice to Master locks, opened with the lockpicking minigame.

## People, dialogue, factions and quests

* **The people of Nirn** [Core]: every town has its own townsfolk (no vanilla villagers; any that appear become
  townsfolk, wandering traders become Khajiit caravan traders):
  * Race and culture follow the region: mostly Nords in Skyrim, with Imperials, Bretons, Redguards, a Dunmer quarter in
    Eastmarch, Reachfolk in the Reach and Argonians at the Rift's docks; each province has its own majority race.
  * Original names per race (Nordic, Latin-like Imperial, Breton, Redguard, Altmer, Bosmer, Dunmer, Orc gro-/gra-,
    Khajiit Ri'/J'/Dar names, Argonian deed and Jel names), with family names where the culture uses them. Names are
    unique across the world.
  * Procedural looks: skin tones, hair styles and colours, beards, Khajiit fur, ears and tails, Argonian scales,
    crests, horns and tails, elf ears, Orc tusks, and 40 outfits (farm and fine clothes, smith aprons, priest and mage
    robes, rags, Jarl regalia, children's clothes and each culture's dress).
  * Trades: farmers, blacksmiths, merchants, apothecaries, fishers, butchers, scholars, stewards, priests, bards and
    more. Merchants barter through the economy; children are small and can't be harmed.
  * Daily routines: sleep in their own bed, breakfast at home, work at the forge, field, counter or temple during
    shop hours, lunch and evenings at the inn while the bard sings, strolls, and chats with each other overhead.
  * Relationships: gifts, favours and quests raise an NPC's opinion of you; crimes they witness lower it. Friends
    share rumours that put nearby places on your map.
  * Marriage: buy an Amulet of Mara from a priest, wear it, and propose to someone who likes you. The wedding is held
    at the temple; your spouse moves into your house, shares the day's earnings and cooks you a meal.
* **Conversation menu** [Core]: talk to any villager (**F**) to trade, train, ask for work, join factions or chat.
* **Followers** [Core]: earn an NPC's trust (a favor, gold for hirelings, a Speech check or a quest) and they follow,
  travel and fight with you. Tell them to wait, follow, trade items or leave.
* **Quests** [Core]:
  * Radiant villager quests and Jarl's bounties (bandit leaders, giants, dragons, legendary monster hunts).
  * The main quest, "The Dragonborn".
  * Quests are shared by your **party**: everyone gets the reward.
  * Press **J** for the journal; your party is managed there too.
* **Factions** [Core]: the Companions, the College of Winterhold, the Thieves Guild, the Dark Brotherhood, the
  Imperial Legion and the Stormcloaks, with ranks and contracts.

## Crime and punishment [Core]

* **Witnessed crimes** add a bounty in the hold where they happen: theft, pickpocketing, assault and murder.
* **Stolen goods** are flagged. Only fences will buy them.
* **Guards** confront you: pay the bounty, go to jail, persuade, bribe, or resist arrest. A bounty of 1000 or more
  means they kill on sight.
* **Jail** is a cell in a separate dimension. Sleeping in the cell bed serves your sentence; serving time costs you
  skill progress.

## Economy [Core]

* **Septims** (gold coins and purses) go straight to your wallet.
* **Barter menu.** Merchants by profession have their own stock and gold, and restock every two days.
* **Prices** depend on your Speech skill and perks: Haggling, Merchant, Investor, Fence and Master Trader.
* **Ways to make money:** quests, bounties, contracts, selling loot, ore, pelts, firewood and fish, and crafting.

## Smithing, enchanting, alchemy [Core]

* **Skyrim materials.** Ores (corundum, orichalcum, moonstone, malachite, quicksilver, ebony, silver), ingots, hides
  and leather strips.
* **Weapons and armor.** Iron, Steel, Orcish, Dwarven, Elven, Glass, Ebony, Daedric and Dragonbone weapons; heavy
  and light armor sets.
* **Forging.** The blacksmith forge, smelter and tanning rack, gated by smithing perks.
* **Tempering.** The grindstone and armor workbench improve gear from Fine to Legendary.
* **Enchanting.** The Arcane Enchanter: disenchant items to learn their enchantments, then enchant using filled soul
  gems.
* **Alchemy.** Ingredients have four effects each, which you discover by eating them or brewing.

## Survival and the world

* **Fishing** feeds the Fishing skill and its perks; Aquaculture 2 adds fish and gear.
* **Hunting:** pelts, meat and monster hunting.
* **Wait and sleep.** Pick how many hours to wait or sleep (beds work any time). In multiplayer, everyone votes.
  Sleeping grants Well Rested.
* **Music** changes with the situation: exploration, night, town, dungeon, combat, Oblivion, Sovngarde. Replace the
  tracks with a resource pack (see `docs/MUSIC.md`).
* **Oblivion.** The Nether is Oblivion: Deadlands, Daedra and Oblivion Gates.
* **Sovngarde.** The End is Sovngarde, where Alduin awaits.
* **Vampires and werewolves** (Vampirism, Werewolves: Become a Beast) let you transform, with their own skill
  trees. As in Skyrim, vampires keep to caves and lairs: Vampirism's vampire forest biome, hunter tent camps and
  faction-controlled villages are switched off (`config/vampirism-common.toml`).
* **Fast travel & Carriages.** The Skyrim map on **M** takes you to discovered locations and party members. Horse carriages outside hold capitals provide paid travel (20–50 Septims) across all 9 Skyrim holds with follower and horse teleportation.
* **Terrain map.** The integrated Skyrim map on **M** replaces third-party maps, featuring explored terrain and fog of war.
* **Woodcutting** [Core]: chop a trunk with an axe and the whole tree comes down into harvestable logs; a sapling is
  replanted at the stump.
* **Player corpses** (Corpse): when you die, your gear stays in your body where you fell. Bodies don't despawn while
  they hold items, never fall into the void or burn in lava, and other players may loot them.
* **Controllers & Steam Deck.** Full gamepad and Steam Deck support out-of-the-box via Controllable, preconfigured
  with Steam button icons and the virtual cursor.

## Performance

* **Distant Horizons** renders terrain to the horizon cheaply. The pack starts it at 128 chunks with the Low Impact
  CPU preset, and its auto-updater is off.
* **Rendering and memory:** Embeddium, Oculus, ImmediatelyFast, Entity Culling, Dynamic FPS, ModernFix, FerriteCore,
  Saturn and Memory Leak Fix.
* **Server and world generation:** Canary (Lithium port), Krypton (networking), Clumps (merges XP orbs) and
  Structure Layout Optimizer (faster town and dungeon generation).
* **Distant terrain generation:** Distant Horizons builds far-off terrain in the background on its own worker threads,
  so no separate pre-generation mod is needed. (Chunky was dropped: it crashes new worlds alongside Distant Horizons 3.)
* **Skycraft's own systems** stay cheap: road paving and NPC traffic run on a per-tick budget and never force chunks
  to load.
