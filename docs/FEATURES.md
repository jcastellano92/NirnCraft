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

* **22 skills.** Skyrim's 18 (One-Handed, Two-Handed, Archery, Block, Heavy Armor, Smithing, Destruction,
  Restoration, Alteration, Conjuration, Illusion, Enchanting, Light Armor, Sneak, Lockpicking, Pickpocket, Speech,
  Alchemy) plus four life skills: Mining, Woodcutting, Fishing and Hunting.
* **Skills improve by use.** Mining ore raises Mining; hitting with a sword raises One-Handed; blocking raises Block,
  and so on. A skill meter pops up as you gain XP, and "Mining increased to 23" shows when the skill levels up.
* **Skyrim's formulas.** XP per skill level is `mult × level^1.95 + offset`. Each skill increase gives character XP
  equal to the new skill level. Going from level L to L+1 takes `(L + 3) × 25` character XP.
* **Level up.** Each character level gives +10 Health, Magicka or Stamina (your choice) and one perk point.
* **Look to the heavens.** Press **K** for a starfield of 22 constellations with 196 perks; scroll or use the arrow
  keys to rotate between them.
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

## Dragons and the Voice

* **Dragons** circle, breathe fire or frost, land to bite and tail-whip, and attack the world at random once you're
  level 3 or higher.
* **Dragon souls.** Killing one lets everyone nearby absorb its soul.
* **Word Walls.** Ancient Nordic shrines in the mountains teach Words of Power. Spend dragon souls to unlock words,
  then shout with **Z**; hold longer for more words. Shouts include Unrelenting Force, Fire Breath, Frost Breath,
  Whirlwind Sprint, Become Ethereal, Dragonrend, Slow Time and more.

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

* **Named villagers** (MCA Reborn): human-looking villagers with names and relationships. Its marriage, romance traits,
  Grim Reaper, cultists and zombie infection are switched off in `config/mca.json`, and its guards attack Skycraft's
  bandits, draugr, Forsworn and other hostiles.
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
