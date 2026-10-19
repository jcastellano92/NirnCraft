# NirnCraft feature guide

This is how each part of Skyrim, and of the wider Elder Scrolls world, works in NirnCraft. Nearly everything here is
**NirnCraft Core**, the one custom mod in the pack. The few features that come from third-party mods say so. For
keys and commands see [CONTROLS.md](CONTROLS.md), and for the world itself see [WORLD.md](WORLD.md).

Settings named below live in the pack's `config/` folder. Server-side files (`nirncraft-*-common.toml`) set the rules
for everyone; `*-client.toml` files are per player.

## Starting out

* **The prisoner's cart.** New players appear on the road outside Helgen. Once you've chosen a race, the main quest
  begins with the cart arriving at Helgen. `helgenStart` in `nirncraft-quest.toml` turns this off.
* **Character creator.** It opens a few seconds after you first join and returns every 30 seconds until you've
  chosen. You are invulnerable while you choose. It has a Lore tab and an Appearance tab (sub-people, sex, skin,
  hair, beard and clothes). Your race is permanent unless an operator resets it.
* **The Traveler's Handbook.** Every player gets one in their pack on first join (`giveOnFirstJoin` in
  `nirncraft-guide-common.toml`). It's a parchment book in twelve chapters: Getting Started, Controls, Races, Skills
  and Perks, Magic and the Voice, Combat, Stealth and Crime, Economy and Houses, Survival, Factions and Quests, The
  World of Nirn, and Playing Together. The Controls chapter shows your actual key bindings. `/handbook` opens it even
  without the item.
* **Tutorial hints.** Small Skyrim-style text appears in the top-left corner the first time you sneak, take a hit,
  run low on health, pick a lock, pickpocket, learn a Word of Power, absorb a dragon soul, can level up, feel cold,
  hunger or fatigue, fish, discover a place, get a bounty, are over-encumbered, ride a horse, see a dragon, or enter
  Oblivion. Each hint shows once and waits while a menu is open. Turn them off with `tutorialHints` in
  `nirncraft-guide-client.toml` or `/handbook hints off`. `/handbook hints reset` shows them all again.
* **Digging is earned.** You can mine ore veins (pickaxe) and fell trees (axe) from the start, but the land itself
  takes Mining perks:
  * **Excavator** (Mining 10): dirt, sand, gravel, clay and snow.
  * **Stonebreaker** (Mining 30): stone and deepslate.
  * **Deep Delver** (Mining 75): the deepest rock.

  Blocks you placed yourself always come back. `restrictDigging` in `nirncraft-common.toml` turns this off.

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

Better Combat provides the directional swing animations. Everything else is NirnCraft.

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
  (`nirncraft-arsenal-*.toml`).
* **Armour.** Heavy and Light Armor protect more as they level. Heavy armour slows you until you take Conditioning.
* **The arsenal:**
  * **Weapons:** 191 smithed weapons (26 of them bows) in 27 materials, from Iron, Steel and Orcish to Daedric,
    Dragonbone and the provincial styles. There are also 81 polearms, 4 crossbows, 15 kinds of arrows and bolts, and
    13 staves.
  * **Armour:** 48 armour sets with 3D models (25 light, 23 heavy) and 26 shields.
  * **Artifacts and unique relics:** 17 Daedric/legendary artifacts (Auriel's Bow, Nightingale Bow, Spellbreaker,
    Wabbajack, Staff of Magnus...) plus **512 unique placed relics** (`uniques_*.json`) across all 11 provinces.
  * **Leveled loot and item affixes (`ItemAffixes`):** the material and enchantment tier of gear follow your level across
    six bands (`1–5`, `6–12`, `13–20`, `21–30`, `31–42`, `43+`). Weapons and armour roll Skyrim-style suffixes (`of Embers`
    .. `of the Inferno`, `of Frost` .. `of the Blizzard`, `of Sparks` .. `of the Tempest`, `of Minor Health` .. `of Peerless
    Health`) and rare prefixes (`Staggering`, `Vampiric`, `Scorching`, `Soul-Snaring`, `Spellbreaking`), all of which can be
    disenchanted at the Arcane Enchanter to learn the family enchantment.
* **Leveled enemies.** Creatures scale with you (more health and damage), follow regional level bands (`max - min <= 20`,
  with story quests clamped within `±8` via `Leveling.storyQuestLevel`), and earn Skyrim's rank names, such as Bandit
  Outlaw → Bandit Marauder and Restless Draugr → Draugr Scourge.

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
* **5,442 authored quests**, written as data (`3,817` multi-stage story quests + `1,625` regional side/contract quests):

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
  | Regional MS1 story arcs (`quests/story/*.json`, all 75 land regions) & provincial chains | 3,727 |
  | Regional side stories, guild contracts & favours (`quests/*.json`) | 1,625 |

  Every town, city and capital also has a **Notice Board** (`NoticeBoards`) posting 3–6 live jobs that refresh every two
  in-game days and target real uncleared dungeons `300–1,500` blocks away.
* **The journal** (**J**). It has Main, Side, Misc, Factions, Reputation, Stats and Party tabs.
  * Track a quest to put it on the compass. You can also abandon quests.
  * A step that has been stuck for three days can be skipped.
* **Parties.** Up to six players share quests and rewards, see each other on the map and compass, and can fast
  travel to each other.

## Creatures and dragons

There are about 80 kinds of creatures and foes, all rig-animated, with original sounds. Regional spawn tables
(`data/nirncraft/region_spawns/`) decide what lives where. Spawns stay out of sight and out of settlements, and
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
  * Random dragon attacks begin at level 3 (`nirncraft-creatures.toml`).
* **Bodies.** Slain creatures leave a body to loot, and arrows that hit them can be recovered.

## Survival

* **Survival Mode** is on by default. Turn it off for a world with `/gamerule nirncraftSurvivalMode false`, or for
  the whole server in `nirncraft-survival.toml`. Its meters sit above the health bar.
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

* **The map.** Tamriel is built with Whiterun at 0,0, plus its fixed foreign lands. Beyond them is ocean,
  with far-ocean crossings wrapping to the opposite world edge. The former procedural expansion planner
  is dormant.
* **Discovery.** You get "DISCOVERED" and "CLEARED" banners, region arrival banners with local rumours and music stings,
  and compass markers (including subtle hints for undiscovered places within 150 blocks).
* **The map screen** (**M**). A painted parchment map with fog of war, your discoveries, quest markers, your party
  and a custom marker.
* **Fast travel.** Travel to any place you've discovered, unless you're in combat or near enemies, over-encumbered,
  riding or falling. In singleplayer it advances the clock.
* **Multi-modal travel (`TravelNetworks`).** Carriages, caravans, ships and ferries, river boats, silt striders, Mages
  Guild guides, Dunmer propylon chambers, Divine/Almsivi Intervention, Great Lifts, and wilderness wayshrines.
* **Horses.** Six breeds, sold at stables for 1,000 to 5,000 Septims. Wild horses can be tamed in a balance
  minigame. Call yours with **H**.
* **Roads.** Signposts (naming the next settlement, inn, and nearby dungeons) and lanterns, with Khajiit caravans,
  travellers, patrols and 125+ regional encounter scenes along them.
* **Festivals (`FestivalCalendar`).** 83 regional and Tamrielic festivals across the 12 months of the year, dressing
  town plazas with banners, lanterns, market stalls and festival quests.
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

* **Hosting.** Use Minecraft's **Open to LAN** for players on the same network, or a dedicated server and
  **Multiplayer** for everyone else.
* **Dedicated servers** work the same way. See [INSTALL.md](INSTALL.md).
* **Shared play:**
  * Parties share quests, rewards, map markers and fast travel.
  * Everyone votes before the group waits or sleeps.
  * Each player gets their own loot from dungeon chests (Lootr).
  * Dragon souls go to everyone nearby.
  * PvP counts as a crime, and party friendly fire is off by default (`nirncraft-quest.toml`).
* **Joining a world.** You're offered a fast travel to your party leader.

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
`Summons.entityType` (e.g. a registered `nirncraft:flame_atronach` replaces the blaze stand-in automatically).

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
* **Named places of every province** [Core]: Tamriel beyond Skyrim is as built-out as Skyrim (about one named place
  per 60,000 square blocks). Each province has its lore places at their positions relative to its cities: towns,
  villages, inns, farms and estates, forts and keeps, Ayleid ruins, ancestral tombs, Daedric ruins, Dwemer ruins,
  xanmeer, moon temples, tree tombs, crystal vaults, Yokudan tombs, Breton crypts, caves, mines, camps, towers,
  lighthouses, wrecks, groves, springs, passes and peaks. Each comes with a one-line description on the map. Cyrodiil
  follows Oblivion's own map (warped onto this one), including its wayshrines, Ayleid wells and doomstones;
  Vvardenfell follows Morrowind's, with kwama egg mines and the Sixth House's caves as sites of their own. Minor
  sites named in each people's idiom fill the gaps between lore places. The table counts all of them:

  | Province | Lore places | Minor sites | Total |
  | --- | --- | --- | --- |
  | Cyrodiil | 532 (+95 Oblivion gate pins) | 379 | 1006 |
  | Morrowind (without Solstheim) | 453 | 119 | 572 |
  | Black Marsh | 177 | 424 | 601 |
  | Elsweyr | 135 | 273 | 408 |
  | Valenwood | 239 | 144 | 383 |
  | Summerset | 144 | 63 | 207 |
  | Hammerfell | 109 | 289 | 398 |
  | High Rock (with Wrothgar) | 131 | 526 | 657 |
  | Atmora, Akavir, Yokuda, Pyandonea, Thras | 47 | — | 47 |

  These add to the 46 capitals and cities and the 128 province shrines that were already there. Sources and tools:
  `tools/provinces.py` and `tools/provinces_data/`. Place pins inside cities (the Arcane University, Vivec's cantons)
  appear on the map only. The Oblivion gate pins are recorded for the realms workstream, which builds the gates.
* **Roads** [Core] connect settlements, with signposts and lantern posts. Travelers, Khajiit caravans, guard patrols
  and the occasional bandit ambush walk them.
* **Roads** [Core]: one pre-planned network across Tamriel, so you can walk from any town to any other.
  * Planned when a world starts (and on the first load of an older save), on background threads from the world's
    noise heights, without loading chunks. Every named settlement of every province is linked (named roads, then a
    spanner over each landmass, farms as leaves), preferring the map's old road corridors, valleys and passes. Roads
    go round walled cities to their main gate, bridge rivers and narrow straits (up to 48 blocks) and never cross open
    sea: islands (Solstheim, Vvardenfell, Summerset, Stros M'Kai) keep their own networks.
  * Historic roads by name, on the signposts and the map: the Red Ring Road round Lake Rumare and the Talos Bridge to
    the Imperial City, the Gold, Black, Silver, Orange, Blue, Green and Yellow Roads of Cyrodiil (with Weye, Aleswell,
    Pell's Gate, Bleaker's Way and the roadside inns of Oblivion's map), and Skyrim's roads between the holds (the
    White River Road, Pale Pass Road, Reach Road, Karth Road, Rift Road and more).
  * Highways between capitals and cities, lesser roads to towns and villages, lanes to farms, and signposted trails to
    named dungeons, forts, standing stones and shrines within 300 blocks of a road. Generic towns, villages, farmsteads
    and inns are predicted from the world seed as players approach (within 3,000 blocks) and joined to the network;
    generic dungeons near a road get trails too.
  * Paved as their chunks generate or load (natural ground only, nothing built over), in each culture's style: Nord
    cobble, gravel and dirt paths, Imperial stone-brick highways, Dunmer ash paths and dark stone, Argonian mud tracks
    and mangrove boardwalks, Khajiit and Redguard sandstone, Bosmer earth, Altmer white stone, Breton cobble.
    Bridges stand a block above the water with slab ramps, piers and lanterns: stone with parapets for the
    stone-building cultures, planks with rails for the rest.
  * Signposts at town exits and junctions with distances, milestones every 640 blocks, wayside shrines of the Divines
    on the highways, lantern posts. The map screen draws the whole planned network and the historic roads' names.
  * Travelers, Khajiit caravans, guard patrols and the occasional bandit ambush walk the roads, going on from town to
    town along the network. Carriages reach every capital the roads reach.
  * `/nirncraft-roads stats | render | inspect | export-inns` (operators) report and picture the network.
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

* **Survival Mode** [Core]: Skyrim's hunger (Peckish to Starving), fatigue (Tired to Debilitated) and cold (Chilly to
  Numb), with stat penalties and a lower carry weight. Warmth from fur, hide and Nordic gear, hoods and cloaks; fires,
  forges and hot stews warm you; cold water is deadly; Nords feel the cold less. Small meters sit above the health
  bar. Switch it off in `config/nirncraft-survival.toml` or per world with `/gamerule nirncraftSurvivalMode false`.
* **Fishing** [Core]: NirnCraft rods, more than fifty fish of Skyrim and every province (salmon, char, pike, histcarp,
  glassfish, Abecean longfin, Niben eel, sugar koi...) by water, region, time, weather and depth; fishing spots with
  jumping fish; Skyrim's reel-in timing bar. Fish are cooking and alchemy ingredients and feed the Fishing skill.
* **Camping** [Core]: bedrolls (sleep anywhere, never a respawn point), hide tents and campfire kits.
* **Hunting:** pelts, meat and monster hunting.
* **Wait and sleep.** Pick how many hours to wait or sleep (beds work any time). In multiplayer, everyone votes.
  Sleeping grants Well Rested.
* **Music** changes with the situation: exploration, night, town, dungeon, combat, Oblivion, Sovngarde. Replace the
  tracks with a resource pack (see `docs/MUSIC.md`).
* **Oblivion.** The Nether is Oblivion: Deadlands, Daedra and Oblivion Gates.
* **Sovngarde.** The End is Sovngarde, where Alduin awaits.
* **Vampires and werewolves** are NirnCraft's own (curse module), as in Skyrim and Dawnguard:
  * **Vampirism.** Vampires and death hounds can pass on Sanguinare Vampiris; cure it (potion, shrine, priest) within
    three days or you turn. Every day without feeding raises your stage (Sated to Blood-Starved): more Health, Magicka
    and Stamina lost in sunlight (where nothing regenerates), more weakness to fire, more resistance to frost, and at
    stage 4 townsfolk attack on sight. Vampires are immune to disease and poison. Feed on sleeping people (use key; a
    crime if seen). Powers: Vampire's Sight, Vampire's Servant, Embrace of Shadows (stage 3+). Falion in Morthal cures
    it ("Rising at Dawn").
  * **Vampire Lord** (Harkon's gift in "Bloodline"): a winged form with its own model and animations. It levitates
    (jump twice), casts Drain Life (attack) and a night spell (use), uses night powers on the Voice key (Bats, Mist
    Form, Supernatural Reflexes, Detect Life, Summon Gargoyle, Night Cloak), bites people on the ground, and grows
    its own 11-perk tree by feeding. Sneak + Voice reverts.
  * **Lycanthropy** (the Companions' beast blood): Beast Form once a day from the Powers tab (Voice key), 150 s
    extended by feeding on corpses, claws, howls (Howl of Terror and the Totems), immune to disease, no Well Rested,
    its own 11-perk tree. Cured by burning a Glenmoril witch head (Companions).
  * The magic menu (P) and skills (K) show the form's abilities and perk tree while transformed. Being seen changing
    is a 1000 gold crime. Vampire lairs have Vampire Lords, death hounds and gargoyles that wait as statues; rare
    werewolves hunt the wilds at night. `/nirncraft-curse` (operators) tests it all.
* **Oblivion and Sovngarde** are real realms now: see *Realms of Oblivion and the afterlife* below.
* **Vampires and werewolves** (Vampirism, Werewolves: Become a Beast) let you transform, with their own skill
  trees. As in Skyrim, vampires keep to caves and lairs: Vampirism's vampire forest biome, hunter tent camps and
  faction-controlled villages are switched off (`config/vampirism-common.toml`).
* **Fast travel & Carriages.** The Skyrim map on **M** takes you to discovered locations and party members. Horse carriages outside hold capitals provide paid travel (20–50 Septims) across all 9 Skyrim holds with follower and horse teleportation.
* **Terrain map.** The integrated Skyrim map on **M** replaces third-party maps, featuring explored terrain and fog of war.
* **Woodcutting** [Core]: chop a trunk with an axe and the whole tree comes down into harvestable logs; a sapling is
  replanted at the stump.
* **Player corpses** [Core]: when you die, what you drop stays in your body where you fell, marked "Your Corpse" on
  the map and compass. Bodies don't despawn while they hold items and are moved out of lava and the void; you and
  your party may loot them (configurable).
* **Hearthfire homesteads** [Core]: buy Lakeview Manor, Windstad Manor or Heljarchen Hall (or claim your own land)
  with a drafting table, then build a small house, a main hall, three wings, a cellar, a garden, an animal pen and a
  workshop from sawn logs, quarried stone, clay, nails, hinges, locks and iron fittings. Hire a steward.
  Builders in cities buy you a drafting table and show open land; name the homestead, widen its grounds, set who may use
  doors, chests, workstations and beds (owner, party, everyone, per block in build mode), and carry stations and chests in
  build mode. Strangers see a compass point, then discover it by name and can fast travel to it. Details and the
  audit: `docs/HOMESTEAD.md`.
* **Controllers & Steam Deck.** Full gamepad and Steam Deck support out-of-the-box via Controllable, preconfigured
  with Steam button icons and the virtual cursor.

## World protection [Core]

As in Skyrim, you can't pick up the world. Outside a homestead you may not break or place blocks, save for the
designated harvests. Server-authoritative and multiplayer-safe (`com.nirncraft.protect`, `config/nirncraft-protect.toml`).

* **Where you may build and break:** inside your own homestead claim, and a party member's when it is shared
  (`HomesteadClaim.canBuild`). The existing homestead rules (block whitelist, depth and height limits) still apply, and
  in your own homestead terrain can be dug without the Mining perks. Blocks you placed yourself (PlacedBlocks, also your
  tent, bedroll and campfire kit) can always be taken back.
* **Harvests, anywhere:** wild flowers, mushrooms, berries, grass, vines, crops (the [F] Harvest rules and theft
  checks apply); ore and mineral veins with a pickaxe (not in a settlement or on a road; they regrow); trees with an
  axe, only away from settlements, roads, structures and landmarks.
* **Never breakable outside a homestead:** glass and panes, beds, doors, trapdoors, chests and barrels, furniture and
  decor, signs, banners, lanterns, torches, walls, floors, stairs, fences, anything in a settlement or structure.
  Terrain (dirt, sand, stone) is never dug inside a settlement, road, structure or landmark; in the wilderness it
  stays a Mining-perk privilege (`terrainDigging = perks`; `homestead` is the strict Skyrim setting where only your
  homestead can be dug). A red "This isn't yours to break" hint explains a refusal (once every two seconds).
* **Never blocked:** opening doors and containers (locks and crime rules still apply), picking things up ([F] Take),
  moving items between containers and bags. Camp gear and the drafting table can be placed anywhere
  (`#nirncraft:placeable_anywhere`).
* **Griefing is off:** creeper, TNT, ghast and dragon blasts destroy no blocks and no paintings, item frames or armor
  stands (only a player's own TNT inside a homestead they may build in does); endermen, withers, ravagers,
  silverfish, snow golems and door-breaking zombies do no block work (townsfolk keep their farm work); fire that
  appears outside a homestead goes out at once; no buckets of water or lava outside a homestead and no stone
  generated by lava and water; hoes, axes, shovels and bone meal do nothing there and farmland is not trampled;
  pistons inside a homestead cannot reach across its edge.
* **Operators:** `/nirncraft-protect status | bypass | check [x y z] | selftest`. `bypass` toggles the rules for
  yourself; creative mode bypasses them too (`creativeBypass`). `check` prints the homestead, the footprint
  (settlement, road, structure, site), the block class and the verdicts, with the reason.
* **Self-test:** `/nirncraft-protect selftest` (also the autorun task `protect` and a check of `tools\qa\qa.ps1
  -Server`): a fake survival player in Whiterun and in the wilderness breaks glass, a bed and a chest (cancelled),
  mines a vein, chops a tree (allowed away from towns, cancelled in them), builds in and out of a homestead, and a
  real explosion goes off next to a block cluster (nothing changes). Every block is restored afterwards.

## Realms of Oblivion and the afterlife [Core]

Six realms are fully built worlds of their own: terrain, biomes, sky and fog, creatures, structures with loot, and a
way in and a way out. Generated by `tools/worldgen/realms.py` (data) and `com.nirncraft.realms` (terrain, structures).

* **The Deadlands** (Mehrunes Dagon; the Nether dimension). Jagged red-black rock islands and thin spires over a
  sea of lava under a blood-red sky with a burning vortex; ash wastes, blood fields of bloodgrass, harrada thickets
  that cut whoever pushes through. A **Sigil Keep** stands in every 640-block cell: the arrival gate, a thorned
  causeway, two flanking spires and the **Great Sigil Tower**: a spiral ramp (or the blood-fire updraft in its shaft)
  up to the Sigil chamber, where the **Sigil Keeper** guards the **Sigil Stone**. Daedric citadels, burning spires
  and bridges of thorns across the lava. Daedra: scamps, clannfear, dremora, daedroths, spider daedra, flame
  atronachs.
  * **Oblivion gates** in Nirn: rare dark, horned arches of fire on scorched ground outside Skyrim (Cyrodiil most of
    all), plus the gate above **Kvatch**. Every gate opens onto its own keep (party members who step through the
    same gate arrive together).
  * **Taking the Sigil Stone** closes the gate: it collapses into rubble in Nirn, everyone on the island is thrown
    home, and you keep the stone. **Sigil Stones** are enchanting gems: hold the stone in your main hand and the
    weapon, armour or tool in your off hand, then use the stone to bind its enchantment.
  * Vanilla obsidian portals still work and lead to the Deadlands too.
* **Sovngarde** (the End dimension). Golden fields and groves under a pale sun and an aurora, ringed by mountains;
  you arrive in **the misty vale** at a ring of carved stones (the golden **way home** stands behind you). The old
  road leads past the **heroes' stone circle** (where Gormlaith, Hakon and Felldir wait, and where Alduin lands) to
  the **Whalebone Bridge** over the misty chasm, guarded by **Tsun**, and up to **the Hall of Valor**, where
  Ysgramor feasts with the honoured dead. Hero spirits and wandering souls roam the fields. While Alduin lives,
  his **soul-mist** gathers and swirls around you. Speak with Tsun (sneaking) to be sent back to Nirn. The End's own
  dragon fight is Alduin's battle; its leftover gateways are tidied away.
* **The Shivering Isles** (Sheogorath). One great isle in a calm sea: **Mania** in the north (giant glowing
  mushrooms, crystal trees, rainbow blooms), **Dementia** in the south (fog, mire, gnarled black trees), **New
  Sheoth** on the central hill with the Palace and Sheogorath's shrine, and **the Fringe** with the **Gates of
  Madness** on the eastern shore, where you arrive through the **Door to the Shivering Isles** (an islet off
  Leyawiin, at the mouth of the Niben). Grummites, baliwogs, gnarls, elytra, flesh atronachs.
* **Apocrypha** (Hermaeus Mora). An endless ink sea under a watching eye, towering stacks of pages, tentacles,
  floating libraries; Seekers and Lurkers. **Black Books** (on altars on Solstheim, or carried as items) pull you into
  a **chapter**; at each chapter's end one book leads to the next chapter (the last to **the Summit of Apocrypha**,
  Miraak's sanctum) and another closes the book and sends you home.
* **The Soul Cairn** (the Ideal Masters). Violet soul-ash wastes, bone pillars and ribcages, glowing soul-gem spires,
  a great gem hanging in a bruised sky; Keepers, Bonemen, Mistmen, Wrathmen and wandering souls; **Valerica's
  refuge** and **the Boneyard**, Durnehviir's perch. The soul-stone portal beside Castle Volkihar asks a toll the
  first time: a filled soul gem.
* **Coldharbour** (Molag Bal). A dark, burning mirror of Nirn: black crags, rifts of fire, chains hanging from the
  sky, **the Hollow City** in ruins. Reached through **the Dark Anchor** in the Jerall Mountains or Molag Bal's
  summons.
* **Ways home.** Every realm has one (the return gate, door, golden way, a Black Book, a portal); dying in a realm
  without a bed to wake in puts you back where you left Nirn. Lock tiers are higher in the realms; the realms'
  structures hold their own loot and lore books.
* **Quests** use them: the Kvatch gate (climb the tower, kill the Sigil Keeper, take the stone), Chasing Echoes (the
  Soul Cairn), the Path of Knowledge and the Summit of Apocrypha (Apocrypha), The Mind of Madness (the Isles), The
  House of Horrors (Coldharbour) and the main quest's Sovngarde.
* Operators: `/nirncraft-realm go <realm> [chapter]`, `/nirncraft-realm home`, `/nirncraft-realm where`,
  `/nirncraft-realm map <realm> <x> <z> <radius>` (writes a relief map to `realm-maps/`).

## Fire and aftermath [Fire]

Package `com.nirncraft.fire` (blocks, fire sites, far renderer, aftermath scenes), generators `tools/textures/fire.py`
and the fire entries of `tools/music/sound_catalog.py` / `synth_ambience.py` / `compose.py`. Operators:
`/nirncraft-fire stats|list|add|ignite|douse|remove|burst|scan|census [place] [generate]|respawn <place>|clear <place>|selftest`.

* **Big fire blocks** (`big_fire`, `flame_column`, `flame_tip`, `rubble_fire`, `smouldering_embers`): animated,
  emissive (light 15; each flame texture ships a labPBR `_s` emission map for shader packs), persistent and
  non-spreading: they are not vanilla fire (no random or scheduled ticks, never ignite a neighbour, never burn a block)
  and only hurt what stands in them (`fire.bigFireHurts`: flames and rubble fire set you alight, embers burn like a
  magma block unless you sneak). Stack base, column and tip for a tall flame
  (`BigFireBlock.column`). All are in the block tag `nirncraft:persistent_fire`, which the world protection skips.
* **Burnt block set:** `charred_planks` (+ stairs, slab), `charred_log`, `charred_beam`, `scorched_stone`,
  `scorched_stone_bricks`; decals `soot_stain`, `blood_stain`; debris `timber_debris`, `splintered_wreckage`,
  `stuck_arrows`. The ash layer is the realms' `nirncraft:ash_layer`.
* **Fire sites** (`FireSites`, server): positions with intensity (0..2.5), flame height (1..48) and smoke (0..3) that
  clients near them draw as a far fire (animated flame sprites, additive glow, rising smoke column) out to
  `farFire.distance` blocks (default 640), independent of particle culling. Saved per dimension, synced within
  `fire.syncRadius` (1100). The renderer (`FarFireRenderer`) draws in `RenderLevelStageEvent.AFTER_PARTICLES` through
  vanilla's eyes / entity-translucent shaders (so Iris maps them), restores all state, and is switched off with
  `farFire.farFire = false` (`nirncraft-fire-client.toml`).
* **API for other systems**
  * Server: `FireSites.add(level, pos, intensity, height)` / `add(level, pos, intensity, height, smoke, lifeTicks)` (a
    temporary site when `lifeTicks > 0`), `FireSites.remove(level, pos)`, `FireSites.burst(level, vec3, intensity,
    radius)` (a one-off blast every player within the sync radius sees), `FireSites.ignite(level, pos, intensity,
    height)` / `douse` (real big fire blocks plus the site).
  * Client only (the intro cutscene's dragon-breath impacts): `ClientFire.addTemporary(x, y, z, intensity, height,
    smoke, lifeTicks)` and `ClientFire.burst(x, y, z, intensity, radius)`; nothing goes to the server and no block is
    touched; `ClientFire.clearTemporary()` ends them.
  * Scenes: `AftermathBodies.spawn(level, pos, role, yaw, lootable, roll)` lays a persistent body;
    `AftermathPopulation` spawns the people a painter recorded as `TABLEAU` anchors.
* **Burnt ruins** (`Spec.BURNT`, plan flag `"burnt"`): every block a burnt piece writes passes through `Charring`
  (wood charred or gone, cloth and glass gone, stone scorched); with `ruined` the building is gutted, a quarter may
  have fallen in (`variant` bit 1), and bit 0 sets it burning: fire columns inside and a `FIRE_SITE` anchor.
* **Helgen** (`cityplans/helgen.json`, painters in `AftermathPainters`): the burnt keep and tower, ten gutted homes
  (five still burning), and scenes that each have a reason: an aid camp of real field tents (cloth over a ridge, the
  wounded lying on bedrolls inside, a healer tending between them, a cook's fire in front); rows of covered dead, each
  under a sheet laid on the ground with a candle at its head and mourners kneeling beside it; graves (mound,
  headstone, flowers, mourners kneeling at the side); a small shrine (stone platform, altar with candles, stele, braziers)
  where people kneel in prayer and a priest stands; a vigil of three covered dead; a funeral pyre with kneeling
  mourners; rubble fields with diggers, haulers and a looter; soldiers at a barricaded gate with their fallen; craters
  (the fire sits on the floor of the bowl); a salvage cart whose merchant still sells; scattered belongings. There is no
  bucket chain and no huddled groups any more. Aftermath people are ordinary `NpcEntity`s with pose flags (kneel,
  tend, carry, weep), frozen in place and turned towards what they attend (`@x,z` in the anchor option), with soft
  sobbing. **Nothing floats:** painters read the ground once before painting, and a final `Supports` pass removes any
  fire, decal, carpet, bed or candle left without a floor (burnt buildings run the same pass); the census prints the
  number of floating blocks (0).
* **Bodies:** every body is a persistent `CorpseEntity` that can be searched with the same prompt: a few coins, what
  they wore (a soldier's weapon and armour) and one personal thing (`AftermathBodies`).
* **One-liners only:** survivors, the wounded, healers, mourners, soldiers and every other person of the ruined town
  (`HelgenFolk`) show one short line (by what they do, the hour, a drawn weapon) with only Continue and Goodbye: no
  topics, no trade, no work. The exceptions: the named story people (Hadvar, Ralof...: only their story choices) and the
  salvage cart's merchant (Barter only).
* **Audio:** `music.helgen_aftermath` plays inside Helgen's footprint (`assets/nirncraft/fire/music_zones.json`, any
  place can get one) with the soundtrack's normal crossfade; `ambient.fire_crackle`, `fire_roar`, `timber_creak`,
  `timber_collapse`, `sobbing`, `mourning` are quiet, positional, one per site every few seconds at most
  (`ambience.volume`).

## Performance

* **Infinite ATLAS** renders terrain, clouds and far actors to the horizon on a curved planet (`atlas_core`, see
  [ATLAS.md](ATLAS.md)); an adaptive quality controller keeps the frame inside a GPU budget
  ([ADAPTIVE-QUALITY.md](ADAPTIVE-QUALITY.md)).
* **Rendering and memory:** Sodium, Iris (with the InfiniteATLAS pack), ImmediatelyFast, Entity Culling, Dynamic FPS
  and FerriteCore.
* **Server:** Lithium, Clumps and Structure Layout Optimizer. Spark is included for profiling.
* **NirnCraft's own systems** run on per-tick budgets: road paving, travellers, ambience and particles. The map
  sampler worldgen uses is allocation-free and thread-safe, so terrain can be sampled in parallel.
