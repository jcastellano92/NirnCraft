# Skycraft feature guide

This is how each part of Skyrim, and of the wider Elder Scrolls world, works in Skycraft. Nearly everything here is
**Skycraft Core**, the one custom mod in the pack. The few features that come from third-party mods say so. For
keys and commands see [CONTROLS.md](CONTROLS.md), and for the world itself see [WORLD.md](WORLD.md).

Settings named below live in the pack's `config/` folder. Server-side files (`skycraft-*-common.toml`) set the rules
for everyone; `*-client.toml` files are per player.

## Starting out

* **The prisoner's cart.** New players appear on the road outside Helgen. Once you've chosen a race, the main quest
  begins with the cart arriving at Helgen. `helgenStart` in `skycraft-quest.toml` turns this off.
* **Character creator.** It opens a few seconds after you first join and returns every 30 seconds until you've
  chosen. You are invulnerable while you choose. It has a Lore tab and an Appearance tab (sub-people, sex, skin,
  hair, beard and clothes). Your race is permanent unless an operator resets it.
* **The Traveler's Handbook.** Every player gets one in their pack on first join (`giveOnFirstJoin` in
  `skycraft-guide-common.toml`). It's a parchment book in twelve chapters: Getting Started, Controls, Races, Skills
  and Perks, Magic and the Voice, Combat, Stealth and Crime, Economy and Houses, Survival, Factions and Quests, The
  World of Nirn, and Playing Together. The Controls chapter shows your actual key bindings. `/handbook` opens it even
  without the item.
* **Tutorial hints.** Small Skyrim-style text appears in the top-left corner the first time you sneak, take a hit,
  run low on health, pick a lock, pickpocket, learn a Word of Power, absorb a dragon soul, can level up, feel cold,
  hunger or fatigue, fish, discover a place, get a bounty, are over-encumbered, ride a horse, see a dragon, or enter
  Oblivion. Each hint shows once and waits while a menu is open. Turn them off with `tutorialHints` in
  `skycraft-guide-client.toml` or `/handbook hints off`. `/handbook hints reset` shows them all again.
* **Digging is earned.** You can mine ore veins (pickaxe) and fell trees (axe) from the start, but the land itself
  takes Mining perks:
  * **Excavator** (Mining 10): dirt, sand, gravel, clay and snow.
  * **Stonebreaker** (Mining 30): stone and deepslate.
  * **Deep Delver** (Mining 75): the deepest rock.

  Blocks you placed yourself always come back. `restrictDigging` in `skycraft-common.toml` turns this off.

## Races

There are **23 playable races**:

* **Skyrim's ten:** Nord, Imperial, Breton, Redguard, High Elf, Wood Elf, Dark Elf, Orc, Khajiit and Argonian.
* **The rest of Tamriel:** Maormer, Reachmen, Ohmes-raht, Lilmothiit and Imga.
* **Akavir:** Tsaesci, Ka Po' Tun and Tang Mo.
* **The lost races:** Atmorans, Falmer, Dwemer, Ayleids and Kothringi.

How races differ:

* **Skills.** Every skill starts at 15. Your race adds +10 to one skill and +5 to five others, plus +5 to one or two
  of the non-Skyrim skills.
* **One greater power,** usable once a day: Battle Cry, Voice of the Emperor, Dragonskin, Adrenaline Rush, Highborn,
  Command Animal, Ancestor's Wrath, Berserker Rage, Night Eye (it recharges in seconds), Histskin, Tiger-Dragon Roar,
  Tonal Resonance and others. Equip it in the magic menu and use it with **Z**.
* **Passives,** including:
  * resistances: fire, frost, shock, poison, magic and disease;
  * water breathing (Argonian, Maormer);
  * claws (Khajiit, Ka Po' Tun, Imga, Tsaesci);
  * wall climbing (Tsaesci, Tang Mo, Imga);
  * natural leaping and soft landings;
  * faster shouts (Atmoran) and more gold (Imperial);
  * extra Magicka, Health or Stamina.
* **Sub-peoples** change only your looks: Colovian and Nibenese Imperials, Stronghold and Wood Orcs, Suthay-raht and
  Cathay-raht Khajiit, and Saxhleel, Naga and Agaceph Argonians.

Townsfolk come from the same races, in the mix of their region. Players and NPCs share one appearance system.

## Skills, leveling and perks

* **29 skills in four groups:**
  * **Warrior:** One-Handed, Two-Handed, Archery, Block, Heavy Armor, Smithing, Unarmed, Polearms.
  * **Mage:** Destruction, Restoration, Alteration, Conjuration, Illusion, Mysticism, Enchanting.
  * **Thief:** Light Armor, Sneak, Lockpicking, Pickpocket, Speech, Alchemy, Acrobatics.
  * **Life:** Mining, Woodcutting, Fishing, Hunting, Athletics, Riding, Cooking.
* **Learning by doing.** Each skill rises as you use it. A meter appears as you gain XP, and a banner when the skill
  goes up.
* **Skyrim's formulas.**
  * XP for the next skill level is `mult × level^1.95 + offset`.
  * Each skill increase gives character XP equal to the new skill level.
  * The next character level needs `(level + 3) × 25` XP.
* **Level up.** Press **K**. Choose +10 Health, Magicka or Stamina (the pool is also refilled), and you get one perk
  point.
* **Look to the heavens.** The skills screen is a starfield of 29 constellations holding **255 perks**.
* **Legendary.** A skill at 100 can be reset to 15. You get its perk points back and it can grow again.
* **Training.** Trainers teach for `level² × 0.2 + 5 × level` gold a lesson, up to five lessons per character level.
  Each trainer can only take you to 50, 75 or 90.
* **Skill books** raise their skill the first time you read one. **Standing Stones** speed up whole groups of skills.
* **Notable trees:**
  * **Polearms:** spears, halberds and glaives.
  * **Acrobatics:** higher jumps, softer landings, Double Jump, Tumble, Aerial Strike, Cat Fall.
  * **Riding:** faster horses, mounted combat, horse archery, trample, Cavalry Charge.
  * **Cooking:** meals carry the cook's skill.
  * **Athletics:** Dodge Roll (**Left Alt**).

## Health, Magicka and Stamina

* **The bars.** Skyrim's three bars sit at the bottom of the screen, Magicka on the left, Health in the centre and
  Stamina on the right. They fade when full.
* **Stamina** is spent on sprinting, power attacks, blocking and dodging, and comes back after a short pause.
* **Magicka** pays for spells and recovers more slowly in combat.
* **Health** regenerates slowly and almost stops in combat.
* **Hunger.** Food never drains on its own. Eating heals you and restores Stamina. Survival Mode adds real hunger
  (see Survival).

## Combat and arms

Better Combat provides the directional swing animations. Everything else is Skycraft.

* **Weapon families.**
  * One-Handed: daggers, swords, war axes and maces.
  * Two-Handed: greatswords, battleaxes and warhammers.
  * Polearms: spears, halberds and glaives.
  * Archery: bows and crossbows.

  Each family has its own perks: axes bleed, maces crush through armour, and swords and greatswords score critical
  hits.
* **Power attacks.** Hold attack, or attack while sprinting. They cost Stamina and deal ×1.5 damage (×1.75
  two-handed), and they stagger. With no Stamina left, damage is halved.
* **Blocking.** Hold right mouse.
  * A weapon blocks 30% of the damage, rising to 70%.
  * A shield blocks 60%, rising to 95%. Deflect Arrows stops arrows entirely.
  * Block perks add bashes, knockback and disarms.
* **Sneak attacks** need you to be crouching and the target to be unaware of you.
  * One-handed weapons deal ×3 (×6 with Backstab, ×15 with a dagger and Assassin's Blade).
  * Bows and other weapons deal ×2 (×3 with Deadly Aim).
  * Bosses take at most ×2.
* **Archery.** Headshots deal ×1.5, and arrows stay in bodies and can be looted back.
* **Kill cams.** Slow-motion finishing shots on the last enemy of a fight, and sometimes on others
  (`skycraft-arsenal-*.toml`).
* **Armour.** Heavy and Light Armor protect more as they level. Heavy armour slows you until you take Conditioning.
* **The arsenal:**
  * **Weapons:** 191 smithed weapons (26 of them bows) in 27 materials, from Iron, Steel and Orcish to Daedric,
    Dragonbone and the provincial styles. There are also 81 polearms, 4 crossbows, 15 kinds of arrows and bolts, and
    13 staves.
  * **Armour:** 48 armour sets with 3D models (25 light, 23 heavy) and 26 shields.
  * **Artifacts:** 17, among them Auriel's Bow, the Nightingale Bow, Spellbreaker, the Wabbajack and the Staff of
    Magnus.
  * **Leveled loot:** the material of the gear you find follows your level. Rare legendary items carry special
    prefixes such as Staggering or Vampiric.
* **Leveled enemies.** Creatures scale with you (more health and damage) and earn Skyrim's rank names, such as
  Bandit Outlaw → Bandit Marauder and Restless Draugr → Draugr Scourge.

## Magic and the Voice

* **Spells.** There are 115 spells in six schools: Destruction (24), Conjuration (22), Restoration (20), Mysticism
  (19), Alteration (15) and Illusion (15). They span five tiers, Novice to Master.
  * You learn them from spell tomes, which you find in dungeons or buy from court wizards.
  * The list includes runes, cloaks, walls, the master storms, wards, the Turn Undead line, necromancy (aim at a body
    to raise that very creature), Daedra and atronachs, Clairvoyance and Mysticism's classics: Telekinesis, Detect
    Life, Soul Trap, Mark and Recall, Divine and Almsivi Intervention, Levitate, Reflect and Spell Absorption.
* **Casting.**
  * Equip spells to either hand in the magic menu (**P**). Left mouse casts the right hand and right mouse the left.
  * Click to cast a fire-and-forget spell, or hold for a concentration spell.
  * Costs follow Skyrim's formula, so skill and perks make spells cheaper.
  * A spell trains its school only when it does something real.
* **Spellmaking Altar.** Combine up to four effects you know (25 effect types) into a spell of your own. You choose
  the range, magnitude, duration and area, and pay a gold fee. You can keep up to 32 custom spells. Press **X** in
  the magic menu to forget one.
* **Staff Enchanter.** Bind a spell you know into an Unenchanted Staff with a filled soul gem. The staff needs no
  Magicka, and its charges depend on the size of the soul.
* **The Voice.**
  * There are 26 shouts with three Words of Power each, all of Skyrim's from Unrelenting Force to Battle Fury. They
    include Slow Time (the world really slows), Call Dragon, Call of Valor, Dragonrend and Soul Tear.
  * Word Walls in Nordic ruins teach a word. Each dragon soul unlocks one word you've learned.
  * Hold **Z** longer for more words.
  * Every shout has a recharge per word. The Blessing of Talos and Dragon Aspect shorten them.
* **Dragon souls.** Everyone within 64 blocks absorbs a slain dragon's soul. The first one makes you Dragonborn.
* **Standing Stones.** There are thirteen guardian stones; one blessing is active at a time. The Ritual, Serpent,
  Shadow and Tower stones grant a daily power. Solstheim's six All-Maker stones stack on top.

## Stealth and crime

* **Sneaking.** The eye above the crosshair shows how aware others are of you.
  * Darkness, Sneak skill and perks hide you. Heavy armour makes you easier to see.
  * A creature you've hit in the last ten seconds always knows where you are.
* **Lockpicking.** About a third of loot containers are locked, Novice to Master. Locks get harder deeper down and
  in Oblivion.
  * It's Skyrim's minigame: the mouse moves the pick and A/D turn the lock.
  * The server decides where the sweet spot is and when a pick breaks.
* **Pickpocketing.** Sneak and right-click a townsperson. Each item shows your chance of success.
* **Crimes** count only if someone sees them, within 20 blocks. Bounties are kept per hold or region.

  | Crime | Bounty |
  | --- | --- |
  | Trespass | 5 |
  | Pickpocketing | 25 |
  | Assault | 40 |
  | Jailbreak | 100 |
  | Murder | 1000 |
  | Theft | half the stolen value |

  Attacking another player is a crime too.
* **Stolen goods** are flagged, and only fences buy them: Khajiit caravans once you're in the Thieves Guild, or any
  merchant you've invested in once you have the Fence perk.
* **Guards** know your bounty in their hold. You can pay, go to jail, persuade them, or bribe them (perk). They
  attack if you resist, if you've just assaulted someone, or if your bounty is 1000 or more.
* **Jail.**
  * Jail is a private cell in its own dimension.
  * The sentence is 30 seconds per 100 gold of bounty, from 30 seconds to 10 minutes. Sleeping in the cell bed
    serves it at once.
  * On release, stolen goods are confiscated and some skill progress is lost.
* **Per-player loot.** Lootr gives every player their own loot from each dungeon chest.

## Economy, crafting and homes

* **Septims** go straight into a weightless purse. `/gold`, `/pay` and `/withdraw` handle the coins.
* **Barter.** There are 12 kinds of shop. Each merchant has their own goods and gold and restocks every two days.
  * Speech and its perks (Haggling, Merchant, Investor, Fence, Master Trader) set the price.
  * At Speech 15 you buy at about 1.9× an item's value and sell at about 0.37×.
* **Smithing.** Five stations: forge, smelter, tanning rack, grindstone and armour workbench. Materials include
  Skyrim's ores (corundum, orichalcum, moonstone, malachite, quicksilver, ebony, silver), ingots, hides and leather
  strips. Tempering raises gear from Fine to Legendary.
* **Enchanting.** At the Arcane Enchanter, disenchant an item to learn its enchantment, then enchant your own gear
  with a filled soul gem.
* **Alchemy.** There are 80 ingredients, each with four effects (57 effects in all). You learn an effect by eating
  the ingredient or by brewing with it.
* **Cooking.** Use a cooking pot. A meal carries its cook's skill, which strengthens its healing, regeneration,
  cures and buffs.
* **Houses for sale.** Ask the Jarl or the steward.

  | House | City | Price |
  | --- | --- | --- |
  | Breezehome | Whiterun | 5,000 |
  | Honeyside | Riften | 8,000 |
  | Vlindrel Hall | Markarth | 8,000 |
  | Hjerim | Windhelm | 12,000 |
  | Proudspire Manor | Solitude | 25,000 |

  Five furnishing upgrades cost 400–800 each.
* **Hearthfire homesteads.**
  * Buy land at Lakeview (Falkreath), Windstad (Morthal) or Heljarchen (the Pale) for 5,000, or claim your own plot.
  * Build at the drafting table: a small house, the main hall, three wings with three room choices each, a cellar,
    a garden, an animal pen and a workshop.
  * Materials are sawn logs, quarried stone, clay, nails, hinges, locks and iron fittings.
  * Hire a steward for 1,000.
* **Carry weight.** You can carry 300, plus 5 per Stamina level-up and any bonuses. Over the limit you're slowed
  and can't sprint or fast travel.
* **Inventory.**
  * The Skyrim inventory (**E**) has ten tabs: Favorites, All, Weapons, Apparel, Potions, Food, Ingredients, Books,
    Spell Tomes and Misc.
  * Containers have Take, Steal and Take All.
  * Dropped items don't despawn (up to a cap).
  * ItemPhysic Lite lets dropped items lie in the world.

## People, factions and quests

* **The people of Nirn.** Every settlement has its own townsfolk; there are no vanilla villagers. Any that appear
  become townsfolk, and wandering traders become Khajiit caravans.
  * There are 32 roles, from farmers, smiths and innkeepers to Jarls, stewards, housecarls, bards, priests,
    Thalmor, soldiers of both sides, Forsworn and vampires.
  * Race and culture follow the region. Names are original and unique, and looks are procedural.
  * They keep daily routines: sleep, work, meals at the inn, and evenings with the bard. They chat with each other
    and remark on you.
* **Dialogue.** Press **F** to talk. The topic menu has trades, training, rumours, work, faction business and Speech
  checks (persuade, intimidate, bribe).
* **Relationships.** Each NPC remembers how they feel about you, from −100 to 100. Favours and gifts raise it;
  crimes they witness lower it.
* **Followers.** Win someone over (a favour, a Speech check, or 500 gold for a hireling) and they fight beside you.
  They carry 27 slots of gear and take orders: wait, follow, trade, dismiss.
* **Marriage.** Wear an Amulet of Mara and propose to someone who likes you. The wedding is held at the temple.
  Your spouse moves in, earns a daily income and cooks a meal that grants Lover's Comfort.
* **Reputation** (**Y**). There are twelve factions, from Hated to Revered. Factions that hate you send hit squads.
* **Joinable factions.** Companions, College of Winterhold, Thieves Guild, Dark Brotherhood, Imperial Legion,
  Stormcloaks and the Bards College, each with ranks, trials and contracts.
* **98 story quests**, written as data:

  | Questline | Quests |
  | --- | --- |
  | The Dragonborn (main), from *Unbound* to *Season Unending* | 20 |
  | Civil War (Legion and Stormcloak) | 12 |
  | Daedric quests | 15 |
  | Thieves Guild | 8 |
  | College of Winterhold | 7 |
  | Dawnguard | 7 |
  | Dark Brotherhood | 6 |
  | Solstheim | 6 |
  | Companions | 5 |
  | Bards College | 4 |
  | The other provinces | 8 |

  There are also side stories (The Golden Claw, Waking Nightmare, The Forsworn Conspiracy...), the College's school
  trials, and radiant work: favours, Jarls' bounties (bandit leaders, giants, Deathlords, dragons), legendary hunts,
  faction contracts and province-flavoured errands.
* **The journal** (**J**). It has Main, Side, Misc, Factions, Reputation, Stats and Party tabs.
  * Track a quest to put it on the compass. You can also abandon quests.
  * A step that has been stuck for three days can be skipped.
* **Parties.** Up to six players share quests and rewards, see each other on the map and compass, and can fast
  travel to each other.

## Creatures and dragons

There are about 80 kinds of creatures and foes, all rig-animated, with original sounds. Regional spawn tables
(`data/skycraft/region_spawns/`) decide what lives where. Spawns stay out of sight and out of settlements, and
hostiles and wildlife have separate budgets.

* **Skyrim's foes:**
  * bandits and their chiefs, Forsworn, necromancers, vampires;
  * draugr and Deathlords;
  * Falmer, chaurus, frostbite spiders, ice wraiths, spriggans, hagravens and wispmothers;
  * skeevers, trolls, giants and their mammoths;
  * Dwemer spiders, spheres, centurions and ballistas;
  * dragon priests, gargoyles and death hounds.
* **Wildlife:** deer, elk, sabre cats, bears, wolves, horkers, mudcrabs, slaughterfish, and butterflies,
  dragonflies, torchbugs and moths.
* **Daedra:** dremora, flame, frost and storm atronachs, scamps and imps. All of them can be conjured.
* **The provinces' beasts:**
  * Morrowind: cliff racers, guar, kagouti, alit, nix-hounds, netch and silt striders.
  * Cyrodiil, Black Marsh and Elsweyr: minotaurs, ogres, wamasu, the swamp leviathan, senche-tigers and alfiq.
  * Hammerfell, High Rock, Valenwood and Summerset: sand serpents, giant scorpions, harpies and indriks.
  * Sea serpents, plus the creatures of Akavir and the endless lands.
* **Dragons:**
  * They circle, breathe fire or frost, land to bite and tail-sweep, and shout: Unrelenting Force, Disarm, and
    Become Ethereal for the older ones.
  * Ranks run Dragon, Blood, Frost, Elder, Ancient, Revered and Legendary. Morrowind has serpentine dragons, and
    there are skeletal dragons too.
  * After Dragon Rising they perch at Word Walls. Alduin raises the dead from the burial mounds.
  * Dragonrend grounds them. A slain dragon burns down to a skeleton.
  * Random dragon attacks begin at level 3 (`skycraft-creatures.toml`).
* **Bodies.** Slain creatures leave a body to loot, and arrows that hit them can be recovered.

## Survival

* **Survival Mode** is on by default. Turn it off for a world with `/gamerule skycraftSurvivalMode false`, or for
  the whole server in `skycraft-survival.toml`. Its meters sit above the health bar.
  * **Hunger** goes Peckish → Hungry → Famished → Starving and lowers your Stamina.
  * **Fatigue** goes Tired → Weary → Debilitated and lowers your Magicka. A bed or an inn room makes you Well
    Rested. A bedroll or tent only leaves you Rested.
  * **Cold** goes Chilly → Very Cold → Freezing → Numb and lowers your Health. It follows the region, weather,
    altitude and time of day.
    * Clothing shows a warmth rating. Fur, hide and Nordic gear, hoods and cloaks are warm.
    * Fires, forges, shelter and hot stews warm you. Cold water is deadly.
    * Nords and Falmer resist the cold.
  * Survival Mode also lowers carry weight by 100.
* **Fishing.**
  * There are 52 fish of Skyrim and every province. What bites depends on the water, region, hour, weather and
    depth.
  * Look for fishing spots with jumping fish. Each patch of water has a limited stock, which regenerates.
  * When a fish bites, reel in while the marker is inside the gold zone. Three rod tiers make bites come sooner and
    widen the zone.
* **Camping.** A bedroll (it never sets your respawn point), a 3×3 hide tent and campfire kits.
* **Inns.** Rent a room for 10 Septims.
* **Diseases.** There are ten, such as Ataxia, Rockjoint and Brain Rot. Cure them with a potion, by praying at a
  shrine or by paying a priest. Shrines of the Nine grant blessings.
* **Your corpse.** When you die, what you carried stays on your body where you fell, marked on the map and compass.
  It is moved out of lava and the void. Who may loot it is set by `lootAccess`: you, your party (the default) or
  anyone.
* **Ore veins** regenerate over time, and **woodcutting** fells the whole tree.
* **Wait and sleep.** Press **I** to wait. A bed opens the sleep menu at any time of day. In multiplayer, everyone
  online votes.

## World, travel and atmosphere

[WORLD.md](WORLD.md) covers the geography. In brief:

* **The map.** Tamriel is built at full scale, with Whiterun at 0, 0. Foreign lands lie beyond it, and endless
  generated lands beyond those. There are 113 biomes, 79 regions and 602 named places.
* **Discovery.** You get "DISCOVERED" and "CLEARED" banners, region and province banners, and compass markers.
* **The map screen** (**M**). A painted parchment map with fog of war, your discoveries, quest markers, your party
  and a custom marker.
* **Fast travel.** Travel to any place you've discovered, unless you're in combat or near enemies, over-encumbered,
  riding or falling. In singleplayer it advances the clock.
* **Carriages and silt striders.** Paid rides between cities, and between Vvardenfell's ports.
* **Horses.** Six breeds, sold at stables for 1,000 to 5,000 Septims. Wild horses can be tamed in a balance
  minigame. Call yours with **H**.
* **Roads.** Signposts and lanterns, with Khajiit caravans, travellers, patrols and bandit ambushes along them.
* **Oblivion and Sovngarde.** The Nether is Oblivion and the End is Sovngarde, with their own fog, music and
  discoveries. Alduin waits in Sovngarde.
* **Sky and weather.**
  * Masser and Secunda, a galaxy, the thirteen birthsign constellations, shooting stars and auroras.
  * Each region has its own weather: rain, snow, blizzards, fog, ash storms, dust and sandstorms.
  * Ambient particles: frost breath, snow, leaves, fireflies, ash, embers and marsh wisps.
  * With a shader pack active, the shader draws the sky.
* **Sound.**
  * An original soundtrack per province and situation: exploration, towns by culture, dungeons, combat, boss,
    dragon, Oblivion and Sovngarde.
  * An ambience engine, reverb and occlusion, and subtitles.
  * See [MUSIC.md](MUSIC.md), including how to import your own Skyrim audio for personal use.
* **HUD and menus.** A Skyrim compass, health bars, a target bar, a sneak eye and a crosshair. Painted loading
  screens with 48 tips and a 3D artifact to spin, plus Skyrim-styled title, world, options and pause screens.

## Multiplayer

* **Hosting with Essential.** Essential is in the pack: open your world, then use the Essential menu to host it and
  invite friends. No port forwarding is needed.
* **Dedicated servers** work the same way. See [INSTALL.md](INSTALL.md).
* **Shared play:**
  * Parties share quests, rewards, map markers and fast travel.
  * Everyone votes before the group waits or sleeps.
  * Each player gets their own loot from dungeon chests (Lootr).
  * Dragon souls go to everyone nearby.
  * PvP counts as a crime, and party friendly fire is off by default (`skycraft-quest.toml`).
* **Joining a world.** You're offered a fast travel to your party leader.

## Coming next

These are being built now and are not yet in the pack:

* **Skycraft's own vampirism and lycanthropy.** Until it lands, the pack still includes the third-party Vampirism
  and Werewolves mods. Vampirism's vampire forests, hunter camps and faction villages are switched off.
* **Sailing and ports.** Carriages already skip routes that would cross open sea.
* **Deeper economy and alchemy integration**, and **terrain polish**.

## Performance

* **Distant Horizons** renders terrain to the horizon. The pack starts it at 128 chunks with the Low Impact CPU
  preset and its auto-updater off.
* **Rendering and memory:** Embeddium, Oculus, ImmediatelyFast, Entity Culling, Dynamic FPS, ModernFix, FerriteCore,
  Saturn and Memory Leak Fix.
* **Server:** Canary (a Lithium port), Krypton, Clumps and Structure Layout Optimizer. Spark is included for
  profiling.
* **Skycraft's own systems** run on per-tick budgets: road paving, travellers, ambience and particles. The map
  sampler worldgen uses is allocation-free and thread-safe, so Distant Horizons can generate far terrain in parallel.
