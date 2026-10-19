"""Generates mod/src/main/lang/nirncraft/core.json (skills, races, perks, HUD and UI strings)."""
import json, os

L = {}
def t(k, v): L[k] = v

skills = {
 "one_handed": ("One-Handed", "The art of combat using one-handed weapons such as daggers, swords, maces and war axes."),
 "two_handed": ("Two-Handed", "The art of combat using two-handed weapons: greatswords, battleaxes and warhammers."),
 "archery": ("Archery", "An archer is skilled in the use of bows to kill from a distance."),
 "block": ("Block", "The art of deflecting blows with a shield or weapon. Hold V with a weapon, or raise a shield."),
 "heavy_armor": ("Heavy Armor", "Training in moving and fighting while wearing heavy armor: iron, steel, dwarven, ebony, daedric."),
 "smithing": ("Smithing", "The art of creating and improving weapons and armor at the forge and grindstone."),
 "destruction": ("Destruction", "The art of harnessing fire, frost and shock to destroy your enemies."),
 "restoration": ("Restoration", "The school of magic that heals, restores and protects from the undead."),
 "alteration": ("Alteration", "The school of magic that alters the physical world: armor spells, detection and paralysis."),
 "conjuration": ("Conjuration", "The school of magic that summons atronachs, familiars and bound weapons, and raises the dead."),
 "illusion": ("Illusion", "The school of magic that bends minds: calm, fear, fury, invisibility and muffle."),
 "enchanting": ("Enchanting", "The art of disenchanting items to learn their magic and binding souls into weapons and armor."),
 "light_armor": ("Light Armor", "Training in light armor: hide, leather, elven, glass. Light armor never slows you down."),
 "sneak": ("Sneak", "Moving unseen and unheard. Crouch to sneak; unaware enemies take sneak attack damage."),
 "lockpicking": ("Lockpicking", "Picking the locks of chests and doors found in dungeons and homes."),
 "pickpocket": ("Pickpocket", "Stealing from the pockets of the unaware. Crouch and use an NPC from behind."),
 "speech": ("Speech", "Persuasion, intimidation and bartering for better prices with merchants."),
 "alchemy": ("Alchemy", "Mixing ingredients into potions and poisons at an alchemy lab."),
 "mining": ("Mining", "Extracting ore from veins. A nobody can only mine ore veins; perks let you dig terrain."),
 "woodcutting": ("Woodcutting", "Chopping wood with an axe, and the carpentry of Hearthfire homesteads."),
 "fishing": ("Fishing", "Catching fish, treasure and legendary catches from Skyrim's rivers and seas."),
 "hunting": ("Hunting", "Tracking, killing and skinning beasts, and hunting the monsters of the wilds."),
 "unarmed": ("Unarmed", "Hand-to-hand combat using fists and claws."),
 "athletics": ("Athletics", "Running, climbing, swimming, and dodging agility.")
}
for k, (n, d) in skills.items():
    t(f"skill.nirncraft.{k}", n); t(f"skill.nirncraft.{k}.desc", d)
for g, n in {"warrior": "The Warrior", "mage": "The Mage", "thief": "The Thief", "life": "The Steed (Life Skills)"}.items():
    t(f"skillgroup.nirncraft.{g}", n)

races = {
 "nord": ("Nord", "Citizens of Skyrim, tall and fair-haired. Strong and hardy, Nords are famous for their resistance to cold and their talent as warriors. 50% resistance to frost.", "Battle Cry", "Nearby enemies flee in terror and are weakened for 30 seconds."),
 "imperial": ("Imperial", "Natives of Cyrodiil, well-educated and well-spoken diplomats and traders. Skilled with combat and magic. Imperial luck finds more gold.", "Voice of the Emperor", "Calms nearby enemies for 60 seconds."),
 "breton": ("Breton", "Natives of High Rock, Bretons have an affinity for magic. 25% magic resistance and +50 Magicka.", "Dragonskin", "Greatly reduces incoming damage for 60 seconds."),
 "redguard": ("Redguard", "The most naturally talented warriors in Tamriel, hardy and resistant to poison (50%).", "Adrenaline Rush", "Stamina regenerates ten times faster for 60 seconds."),
 "altmer": ("High Elf", "The Altmer of the Summerset Isles are the most strongly gifted in the arcane arts. +50 Magicka.", "Highborn", "Magicka regenerates 25 times faster for 60 seconds."),
 "bosmer": ("Wood Elf", "The clanfolk of Valenwood are the best archers in Tamriel. 50% resistance to poison and disease.", "Command Animal", "Nearby animals become your allies and heal for 30 seconds."),
 "dunmer": ("Dark Elf", "The Dunmer of Morrowind are known for skill with sword and spell alike. 50% resistance to fire.", "Ancestor's Wrath", "Surrounds you in fire for 60 seconds, burning anyone who comes close."),
 "orsimer": ("Orc", "The people of Wrothgarian and Dragontail mountains: unshakeable warriors and master smiths.", "Berserker Rage", "Deal double damage and take half damage for 60 seconds."),
 "khajiit": ("Khajiit", "Hailing from Elsweyr, Khajiit are intelligent, quick and agile thieves. Their claws make unarmed attacks deadly.", "Night Eye", "See in the dark for 60 seconds (can be used often)."),
 "argonian": ("Argonian", "Reptilian natives of Black Marsh, at home in water (they can breathe underwater) and adept at lockpicking.", "Histskin", "Health regenerates ten times faster for 60 seconds."),
}
for k, (n, d, p, pd) in races.items():
    t(f"race.nirncraft.{k}", n); t(f"race.nirncraft.{k}.desc", d)
pmap = {"nord": "battle_cry", "imperial": "voice_of_the_emperor", "breton": "dragonskin", "redguard": "adrenaline_rush",
        "altmer": "highborn", "bosmer": "command_animal", "dunmer": "ancestors_wrath", "orsimer": "berserker_rage",
        "khajiit": "night_eye", "argonian": "histskin"}
for k, (n, d, p, pd) in races.items():
    t(f"power.nirncraft.{pmap[k]}", p); t(f"power.nirncraft.{pmap[k]}.desc", pd)

def tiers(skill, suffix, school):
    names = [("novice", "Novice"), ("apprentice", "Apprentice"), ("adept", "Adept"), ("expert", "Expert"), ("master", "Master")]
    for key, title in names:
        if school == "locks":
            perk(f"{skill}.{key}_{suffix}", f"{title} Locks", f"{title}-level locks are much easier to pick.")
        else:
            perk(f"{skill}.{key}_{suffix}", f"{title} {school}", f"Cast {title}-level {school} spells for half magicka.")

def perk(pid, name, desc):
    t(f"perk.nirncraft.{pid}", name); t(f"perk.nirncraft.{pid}.desc", desc)

P = perk
P("one_handed.armsman", "Armsman", "One-handed weapons do 20% more damage per rank.")
P("one_handed.fighting_stance", "Fighting Stance", "Power attacks with one-handed weapons cost 25% less stamina.")
P("one_handed.hack_and_slash", "Hack and Slash", "Attacks with war axes cause bleeding damage, more with each rank.")
P("one_handed.bone_breaker", "Bone Breaker", "Attacks with maces deal 15% extra damage per rank, crushing through armor.")
P("one_handed.bladesman", "Bladesman", "Attacks with swords and daggers have a 10% chance per rank of a critical hit.")
P("one_handed.dual_flurry", "Dual Flurry", "Dual wielding attacks are faster.")
P("one_handed.savage_strike", "Savage Strike", "Standing power attacks do 25% bonus damage.")
P("one_handed.critical_charge", "Critical Charge", "Sprinting power attacks with one-handed weapons do 50% more damage.")
P("one_handed.dual_savagery", "Dual Savagery", "Dual wielding power attacks do 50% bonus damage.")
P("one_handed.paralyzing_strike", "Paralyzing Strike", "One-handed power attacks have a 25% chance to paralyze the target.")
P("two_handed.barbarian", "Barbarian", "Two-handed weapons do 20% more damage per rank.")
P("two_handed.champions_stance", "Champion's Stance", "Power attacks with two-handed weapons cost 25% less stamina.")
P("two_handed.limbsplitter", "Limbsplitter", "Attacks with battleaxes cause bleeding damage.")
P("two_handed.skullcrusher", "Skullcrusher", "Attacks with warhammers deal 15% extra damage per rank.")
P("two_handed.deep_wounds", "Deep Wounds", "Attacks with greatswords have a 10% chance per rank of a critical hit.")
P("two_handed.devastating_blow", "Devastating Blow", "Standing power attacks do 25% bonus damage.")
P("two_handed.great_critical_charge", "Great Critical Charge", "Sprinting power attacks with two-handed weapons do 50% more damage.")
P("two_handed.sweep", "Sweep", "Two-handed power attacks also hit enemies beside your target.")
P("two_handed.warmaster", "Warmaster", "Two-handed power attacks have a 25% chance to paralyze the target.")
P("archery.overdraw", "Overdraw", "Bows do 20% more damage per rank.")
P("archery.eagle_eye", "Eagle Eye", "Sneak while drawing a bow to zoom in.")
P("archery.critical_shot", "Critical Shot", "10% chance per rank of a critical hit that does 50% extra damage.")
P("archery.steady_hand", "Steady Hand", "Zooming in with a bow steadies your aim.")
P("archery.power_shot", "Power Shot", "Fully drawn arrows stagger most targets.")
P("archery.hunters_discipline", "Hunter's Discipline", "Recover twice as many arrows from hits.")
P("archery.ranger", "Ranger", "Move faster with a drawn bow.")
P("archery.quick_shot", "Quick Shot", "Draw bows 30% faster.")
P("archery.bullseye", "Bullseye", "15% chance of paralyzing the target for a few seconds.")
P("block.shield_wall", "Shield Wall", "Blocking is 5% more effective per rank.")
P("block.quick_reflexes", "Quick Reflexes", "Enemies who strike your raised shield are staggered.")
P("block.deflect_arrows", "Deflect Arrows", "Arrows that hit the shield do no damage.")
P("block.power_bash", "Power Bash", "Blocking a melee blow knocks the attacker back.")
P("block.elemental_protection", "Elemental Protection", "Blocking with a shield reduces incoming fire damage by 90%.")
P("block.deadly_bash", "Deadly Bash", "Attackers take a quarter of the damage you block back.")
P("block.block_runner", "Block Runner", "Able to move faster with a shield raised.")
P("block.disarming_bash", "Disarming Bash", "10% chance to disarm an enemy whose blow you block.")
P("block.shield_charge", "Shield Charge", "Sprinting with a shield raised knocks down most targets.")
P("heavy_armor.juggernaut", "Juggernaut", "Heavy armor protects 4% more per rank.")
P("heavy_armor.fists_of_steel", "Fists of Steel", "Unarmed attacks with heavy armor do extra damage based on its rating.")
P("heavy_armor.well_fitted", "Well Fitted", "+5% protection when wearing a full set of heavy armor.")
P("heavy_armor.cushioned", "Cushioned", "Half damage from falling while wearing a full set of heavy armor.")
P("heavy_armor.tower_of_strength", "Tower of Strength", "Cannot be staggered while wearing a full set of heavy armor.")
P("heavy_armor.conditioning", "Conditioning", "Heavy armor no longer slows you down.")
P("heavy_armor.matching_set", "Matching Set", "Additional protection for a full set of matching heavy armor.")
P("heavy_armor.reflect_blows", "Reflect Blows", "10% chance to reflect melee damage back to the enemy.")
P("smithing.steel_smithing", "Steel Smithing", "Can create Steel armor and weapons at forges, and improve them twice as much.")
P("smithing.elven_smithing", "Elven Smithing", "Can create Elven armor and weapons at forges.")
P("smithing.dwarven_smithing", "Dwarven Smithing", "Can create Dwarven armor and weapons at forges.")
P("smithing.advanced_armors", "Advanced Armors", "Can create Scaled and Plate armor at forges.")
P("smithing.orcish_smithing", "Orcish Smithing", "Can create Orcish armor and weapons at forges.")
P("smithing.arcane_blacksmith", "Arcane Blacksmith", "You can improve magical weapons and armor.")
P("smithing.glass_smithing", "Glass Smithing", "Can create Glass armor and weapons at forges.")
P("smithing.ebony_smithing", "Ebony Smithing", "Can create Ebony armor and weapons at forges.")
P("smithing.daedric_smithing", "Daedric Smithing", "Can create Daedric armor and weapons at forges.")
P("smithing.dragon_armor", "Dragon Armor", "Can create Dragonbone and Dragonscale armor and weapons.")
tiers("destruction", "destruction", "Destruction")
P("destruction.dual_casting", "Destruction Dual Casting", "Dual casting a Destruction spell overcharges the effects into an even more powerful version (2.2x magnitude at 2.8x cost).")
P("destruction.augmented_flames", "Augmented Flames", "Fire spells do 25% more damage per rank.")
P("destruction.augmented_frost", "Augmented Frost", "Frost spells do 25% more damage per rank.")
P("destruction.augmented_shock", "Augmented Shock", "Shock spells do 25% more damage per rank.")
P("destruction.rune_master", "Rune Master", "Can place runes five times farther away.")
P("destruction.impact", "Impact", "Most destruction spells will stagger an opponent.")
P("destruction.intense_flames", "Intense Flames", "Fire damage causes targets to flee if their health is low.")
P("destruction.deep_freeze", "Deep Freeze", "Frost damage paralyzes targets with low health.")
P("destruction.disintegrate", "Disintegrate", "Shock damage disintegrates targets with low health.")
tiers("restoration", "restoration", "Restoration")
P("restoration.dual_casting", "Restoration Dual Casting", "Dual casting a Restoration spell overcharges the effects into an even more powerful version (2.2x magnitude at 2.8x cost).")
P("restoration.regeneration", "Regeneration", "Healing spells cure 50% more.")
P("restoration.respite", "Respite", "Healing spells also restore stamina.")
P("restoration.recovery", "Recovery", "Magicka regenerates 25% faster per rank.")
P("restoration.ward_absorb", "Ward Absorb", "Wards recharge your magicka when hit with spells.")
P("restoration.necromage", "Necromage", "All spells are more effective against undead.")
P("restoration.avoid_death", "Avoid Death", "Once a day, heals 250 points automatically if you fall below 10% health.")
tiers("alteration", "alteration", "Alteration")
P("alteration.dual_casting", "Alteration Dual Casting", "Dual casting an Alteration spell overcharges the effects into an even more powerful version (2.2x magnitude at 2.8x cost).")
P("alteration.mage_armor", "Mage Armor", "Protection from armor spells is 2x/2.5x/3x greater if not wearing armor.")
P("alteration.magic_resistance", "Magic Resistance", "Blocks 10% of a spell's effects per rank.")
P("alteration.stability", "Stability", "Alteration spells have greater duration.")
P("alteration.atronach", "Atronach", "Absorb 30% of the magicka of any spells that hit you.")
tiers("conjuration", "conjuration", "Conjuration")
P("conjuration.dual_casting", "Conjuration Dual Casting", "Dual casting a Conjuration spell overcharges the effects into an even more powerful version (2.2x magnitude at 2.8x cost).")
P("conjuration.mystic_binding", "Mystic Binding", "Bound weapons do more damage.")
P("conjuration.soul_stealer", "Soul Stealer", "Bound weapons cast Soul Trap on targets.")
P("conjuration.oblivion_binding", "Oblivion Binding", "Bound weapons will banish summoned creatures.")
P("conjuration.summoner", "Summoner", "Can summon atronachs or raise undead twice as far away.")
P("conjuration.atromancy", "Atromancy", "Double duration for conjured atronachs.")
P("conjuration.necromancy", "Necromancy", "Greater duration for reanimated undead.")
P("conjuration.dark_souls", "Dark Souls", "Reanimated undead have 100 points more health.")
P("conjuration.twin_souls", "Twin Souls", "You can have two atronachs or reanimated zombies.")
tiers("illusion", "illusion", "Illusion")
P("illusion.dual_casting", "Illusion Dual Casting", "Dual casting an Illusion spell overcharges the effects into an even more powerful version (2.2x magnitude at 2.8x cost).")
P("illusion.animage", "Animage", "Illusion spells now work on higher level animals.")
P("illusion.hypnotic_gaze", "Hypnotic Gaze", "Calm spells now work on higher level opponents.")
P("illusion.kindred_mage", "Kindred Mage", "All Illusion spells work on higher level people.")
P("illusion.aspect_of_terror", "Aspect of Terror", "Fear spells work on higher level opponents.")
P("illusion.quiet_casting", "Quiet Casting", "All spells you cast from any school of magic are silent to others.")
P("illusion.rage", "Rage", "Frenzy spells work on higher level opponents.")
P("illusion.master_of_the_mind", "Master of the Mind", "Illusion spells work on undead, daedra and automatons.")
P("enchanting.enchanter", "Enchanter", "New enchantments are 20% stronger per rank.")
P("enchanting.fire_enchanter", "Fire Enchanter", "Fire enchantments on weapons and armor are 25% stronger.")
P("enchanting.soul_squeezer", "Soul Squeezer", "Soul gems provide extra magicka for recharging.")
P("enchanting.frost_enchanter", "Frost Enchanter", "Frost enchantments on weapons and armor are 25% stronger.")
P("enchanting.soul_siphon", "Soul Siphon", "Death blows to creatures, but not people, trap 5% of the victim's soul, recharging the weapon.")
P("enchanting.insightful_enchanter", "Insightful Enchanter", "Skill enchantments on armor are 25% stronger.")
P("enchanting.storm_enchanter", "Storm Enchanter", "Shock enchantments on weapons and armor are 25% stronger.")
P("enchanting.corpus_enchanter", "Corpus Enchanter", "Health, magicka and stamina enchantments on armor are 25% stronger.")
P("enchanting.extra_effect", "Extra Effect", "Can put two enchantments on the same item.")
P("light_armor.agile_defender", "Agile Defender", "Light armor protects 4% more per rank.")
P("light_armor.custom_fit", "Custom Fit", "+5% protection when wearing a full set of light armor.")
P("light_armor.unhindered", "Unhindered", "Sprinting in full light armor drains 15% less stamina.")
P("light_armor.wind_walker", "Wind Walker", "Stamina regenerates 50% faster in full light armor.")
P("light_armor.matching_set_light", "Matching Set", "Additional protection for a full set of matching light armor.")
P("light_armor.deft_movement", "Deft Movement", "10% chance of avoiding all melee damage while wearing all light armor.")
P("sneak.stealth", "Stealth", "You are 5% harder to detect per rank when sneaking.")
P("sneak.backstab", "Backstab", "Sneak attacks with one-handed weapons now do six times damage.")
P("sneak.muffled_movement", "Muffled Movement", "Noise from heavy armor no longer gives you away.")
P("sneak.deadly_aim", "Deadly Aim", "Sneak attacks with bows now do three times damage.")
P("sneak.light_foot", "Light Foot", "You won't trigger pressure plates.")
P("sneak.assassins_blade", "Assassin's Blade", "Sneak attacks with daggers now do a total of fifteen times normal damage.")
P("sneak.silent_roll", "Silent Roll", "Sprinting while sneaking executes a silent forward roll.")
P("sneak.silence", "Silence", "Walking and running does not affect detection.")
P("sneak.shadow_warrior", "Shadow Warrior", "Crouching makes you far harder to see, even in combat.")
tiers("lockpicking", "locks", "locks")
P("lockpicking.quick_hands", "Quick Hands", "Able to pick locks without being noticed.")
P("lockpicking.wax_key", "Wax Key", "Once picked, a lock stays open for you for good.")
P("lockpicking.golden_touch", "Golden Touch", "Find more gold in chests.")
P("lockpicking.treasure_hunter", "Treasure Hunter", "50% greater chance of finding special treasure.")
P("lockpicking.unbreakable", "Unbreakable", "Lockpicks never break.")
P("pickpocket.light_fingers", "Light Fingers", "Pickpocketing bonus of 20% per rank.")
P("pickpocket.night_thief", "Night Thief", "+25% chance to pickpocket at night.")
P("pickpocket.cutpurse", "Cutpurse", "Pickpocketing gold is 50% easier.")
P("pickpocket.poisoned", "Poisoned", "Silently harm enemies by placing poisons in their pockets.")
P("pickpocket.extra_pockets", "Extra Pockets", "You can steal more items at once.")
P("pickpocket.keymaster", "Keymaster", "Pickpocketing keys almost always works.")
P("pickpocket.misdirection", "Misdirection", "Can pickpocket equipped weapons.")
P("pickpocket.perfect_touch", "Perfect Touch", "Can pickpocket equipped items.")
P("speech.haggling", "Haggling", "Buying and selling prices are 10% better per rank.")
P("speech.allure", "Allure", "10% better prices with merchants who like you.")
P("speech.bribery", "Bribery", "Can bribe guards to ignore crimes.")
P("speech.merchant", "Merchant", "Can sell any type of item to any kind of merchant.")
P("speech.persuasion", "Persuasion", "Persuasion attempts are 30% easier.")
P("speech.investor", "Investor", "Can invest 500 gold with a shopkeeper to increase their available gold permanently.")
P("speech.intimidation", "Intimidation", "Intimidation is twice as successful.")
P("speech.fence", "Fence", "Can barter stolen goods with any merchant you have invested in.")
P("speech.master_trader", "Master Trader", "Every merchant in the world gains 1000 gold for bartering.")
P("alchemy.alchemist", "Alchemist", "Potions and poisons you make are 20% stronger per rank.")
P("alchemy.physician", "Physician", "Potions you mix that restore Health, Magicka or Stamina are 25% more powerful.")
P("alchemy.benefactor", "Benefactor", "Potions you mix with beneficial effects have an additional 25% greater magnitude.")
P("alchemy.poisoner", "Poisoner", "Poisons you mix are 25% more effective.")
P("alchemy.experimenter", "Experimenter", "Eating an ingredient reveals one more effect per rank.")
P("alchemy.green_thumb", "Green Thumb", "Two ingredients are gathered from plants.")
P("alchemy.concentrated_poison", "Concentrated Poison", "Poisons applied to weapons last for twice as many hits.")
P("alchemy.snakeblood", "Snakeblood", "50% resistance to all poisons.")
P("alchemy.purity", "Purity", "All negative effects are removed from created potions, and all positive effects from poisons.")
P("mining.prospector", "Prospector", "10% chance per rank to get double ore from a vein.")
P("mining.excavator", "Excavator", "You learn to dig: soft ground such as dirt, sand, gravel, clay and snow can now be dug with a shovel.")
P("mining.stonebreaker", "Stonebreaker", "You can now quarry stone, deepslate and other hard rock.")
P("mining.tunneler", "Tunneler", "Mine and dig 50% faster.")
P("mining.geologist", "Geologist", "Quarrying stone sometimes reveals gems.")
P("mining.deep_delver", "Deep Delver", "You can break the hardest rock: obsidian, basalt, blackstone and end stone.")
P("mining.vein_miner", "Vein Miner", "Sneak while mining ore to mine out the entire vein.")
P("woodcutting.lumberjack", "Lumberjack", "Chop wood 20% faster per rank.")
P("woodcutting.forager", "Forager", "Leaves sometimes drop apples and sticks.")
P("woodcutting.carpenter", "Carpenter", "Woodworking is second nature: crafted wooden blocks are cheaper to work.")
P("woodcutting.hearthfire", "Hearthfire", "You are ready to build your own homestead.")
P("woodcutting.timber", "Timber!", "Sneak while chopping a log to fell the whole tree.")
P("fishing.angler", "Angler", "10% chance per rank to catch an extra fish.")
P("fishing.patient_hands", "Patient Hands", "Fishing trains 50% faster.")
P("fishing.treasure_fisher", "Treasure Fisher", "8% chance per rank to also reel in treasure.")
P("fishing.double_catch", "Double Catch", "25% chance to catch everything twice.")
P("fishing.legendary_angler", "Legendary Angler", "A rare chance to catch legendary fish.")
P("hunting.tracker", "Tracker", "Deal 20% more damage per rank to animals.")
P("hunting.skinner", "Skinner", "Harvest more hides and pelts from animals.")
P("hunting.field_dresser", "Field Dresser", "Harvest more meat from animals.")
P("hunting.beast_lore", "Beast Lore", "Wild animals leave you alone unless you attack them.")
P("hunting.monster_hunter", "Monster Hunter", "Deal 25% more damage to monsters: trolls, werewolves, vampires, dragons.")
P("hunting.apex_predator", "Apex Predator", "Hunting trains twice as fast; +25% damage to beasts and monsters, and richer harvests.")
P("unarmed.pugilist", "Pugilist", "Unarmed strikes deal 20% more damage per rank.")
P("unarmed.iron_fist", "Iron Fist", "Unarmed strikes ignore 25% of target armor.")
P("unarmed.heavy_strikes", "Heavy Strikes", "Power attacks with fists stagger opponents.")
P("unarmed.haymaker", "Haymaker", "Charged punch deals 50% bonus critical damage.")
P("unarmed.grandmaster", "Grandmaster", "Unarmed strikes deal double damage and have a chance to knock down enemies.")
P("athletics.runner", "Runner", "Stamina regenerates 10% faster per rank.")
P("athletics.sprinter", "Sprinter", "Sprinting drains 20% less stamina.")
P("athletics.climber", "Climber", "Climbing speed increased by 30% and climb stamina drain reduced.")
P("athletics.dodge_roll", "Dodge Roll", "Tap Left Alt to roll in your movement direction, avoiding attacks.")
P("athletics.wind_walker", "Wind Walker", "Stamina regenerates 50% faster while moving.")

# keys & screens & messages
for k, n in {"skills": "Skills (Look to the Heavens)", "journal": "Journal", "magic_menu": "Magic & Shouts", "cast": "Cast Spell",
             "shout": "Shout / Power", "power_attack": "Power Attack (hold while attacking)", "block": "Block with Weapon (hold)",
             "racial_power": "Racial Power", "wait": "Wait / Sleep", "party": "Party", "map": "Map & Fast Travel",
             "sheathe": "Ready / Sheathe Weapon", "dodge": "Dodge Roll"}.items():
    t(f"key.nirncraft.{k}", n)
t("key.categories.nirncraft", "NirnCraft")
t("itemGroup.nirncraft", "NirnCraft")
t("item.nirncraft.septim", "Septim")
t("item.nirncraft.coin_purse", "Coin Purse")
t("item.nirncraft.legendary_fish", "Legendary Fish")
t("effect.nirncraft.well_rested", "Well Rested")
t("effect.nirncraft.lovers_comfort", "Lover's Comfort")
t("effect.nirncraft.paralysis", "Paralysis")
t("effect.nirncraft.stagger", "Staggered")
t("effect.nirncraft.bleeding", "Bleeding")
t("tooltip.nirncraft.gold_value", "Worth %s gold")
t("stat.nirncraft.health", "Health"); t("stat.nirncraft.magicka", "Magicka"); t("stat.nirncraft.stamina", "Stamina")
t("screen.nirncraft.skills", "Skills")
t("screen.nirncraft.level", "Level %s")
t("screen.nirncraft.perks_to_increase", "Perks to increase: %s")
t("screen.nirncraft.requires", "Requires %s %s")
t("screen.nirncraft.unlock_confirm", "Unlock %s?")
t("screen.nirncraft.level_up_title", "Level %s")
t("screen.nirncraft.level_up_choose", "Choose an attribute to increase by 10")
t("screen.nirncraft.make_legendary", "Make Legendary")
t("screen.nirncraft.legendary_count", "Legendary x%s")
t("screen.nirncraft.race", "Choose your race")
t("screen.nirncraft.race_header", "Who are you?")
t("screen.nirncraft.race_confirm", "This is who I am")
t("screen.nirncraft.hub", "Menu")
t("screen.nirncraft.hub_skills", "Skills")
t("screen.nirncraft.hub_items", "Items")
t("screen.nirncraft.hub_magic", "Magic")
t("screen.nirncraft.hub_map", "Map")
t("screen.nirncraft.hub_character", "Character")
t("screen.nirncraft.hub_hint", "W/A/S/D or Mouse to navigate | Esc or Tab to close")
t("screen.nirncraft.character_status", "Character & Status")
t("screen.nirncraft.character_status_header", "CHARACTER STATUS")
t("screen.nirncraft.power_ready", "Ready to use")
t("screen.nirncraft.active_effects", "Active Effects")
t("screen.nirncraft.no_active_effects", "None")
t("key.nirncraft.hub", "Menu Hub")
t("key.nirncraft.call_horse", "Call Horse")
t("screen.nirncraft.race_skills", "Skill bonuses")
t("screen.nirncraft.race_power", "Greater Power (H)")
t("hud.nirncraft.hidden", "HIDDEN"); t("hud.nirncraft.detected", "DETECTED")
t("hud.nirncraft.perks_available", "Perk points: %s  (K)")
t("notify.nirncraft.skill_increased", "%s increased to %s")
t("notify.nirncraft.level_up", "LEVEL UP")
t("notify.nirncraft.level_up_hint", "Press K to choose Health, Magicka or Stamina")
t("notify.nirncraft.race_chosen", "Your journey begins")
t("notify.nirncraft.legendary", "%s is now Legendary")
for k, n in {"quest_started": "QUEST STARTED", "quest_updated": "QUEST UPDATED", "quest_completed": "QUEST COMPLETED",
             "quest_failed": "QUEST FAILED", "location_discovered": "DISCOVERED", "location_cleared": "CLEARED",
             "big_title": "", "skill_up": "", "level_up_banner": "", "message": "", "crime": "", "skill_xp": ""}.items():
    t(f"notify.nirncraft.{k}", n)
t("message.nirncraft.gold_added", "%s Septims added")
t("message.nirncraft.not_enough_gold", "You don't have enough gold")
t("message.nirncraft.power_cooldown", "You can only use %s once a day (%s hours left)")
t("message.nirncraft.power_used", "%s")
t("message.nirncraft.critical", "Critical strike!")
t("message.nirncraft.sneak_attack", "Sneak attack for %sx damage!")
t("message.nirncraft.evaded", "Evaded!")
t("message.nirncraft.disarmed", "Disarmed!")
t("message.nirncraft.need_pickaxe", "You need a pickaxe to mine this vein")
t("message.nirncraft.need_axe", "You need an axe to chop wood")
t("message.nirncraft.need_excavator", "You can't dig here. (Mining perk: Excavator)")
t("message.nirncraft.need_stonebreaker", "This rock is too hard for you. (Mining perk: Stonebreaker)")
t("message.nirncraft.need_deep_delver", "This rock is beyond your skill. (Mining perk: Deep Delver)")
t("message.nirncraft.legendary_fish", "You caught a legendary fish!")
t("message.nirncraft.sheathed", "Weapons sheathed")
t("message.nirncraft.drawn", "Weapons drawn")
t("message.nirncraft.unstuck_combat", "Cannot unstuck while in combat!")
t("message.nirncraft.unstuck_cooldown", "Unstuck is on cooldown (%s seconds left)")
t("message.nirncraft.unstuck_success", "Teleported to rest point")
t("menu.nirncraft.unstuck", "Unstuck / Respawn")
t("command.nirncraft.gold", "You have %s Septims")
t("dialogue.nirncraft.chat", "Let's talk.")
t("dialogue.nirncraft.goodbye", "Goodbye.")
t("command.nirncraft.paid", "Paid %s Septims to %s")
for h, n in {"whiterun": "Whiterun Hold", "the_rift": "The Rift", "eastmarch": "Eastmarch", "the_pale": "The Pale",
             "winterhold": "Winterhold", "haafingar": "Haafingar", "hjaalmarch": "Hjaalmarch", "the_reach": "The Reach",
             "falkreath": "Falkreath Hold", "the_nether": "Oblivion", "the_end": "Sovngarde"}.items():
    t(f"hold.nirncraft.{h}", n)

out = os.path.join(os.path.dirname(__file__), "..", "mod", "src", "main", "lang", "nirncraft", "core.json")
with open(out, "w") as f:
    json.dump(L, f, indent=2, sort_keys=True)
print(len(L), "keys")
