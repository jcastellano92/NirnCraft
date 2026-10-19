'use strict';
// Builds quests/marquee_skyrim.json: NPCs and quests for the Skyrim towns, villages and farms that had few or none.
// Run: node tools/marquee/mq_sky.js
const { File, o, choice, mob, addToIndex, QUEST_DIR } = require('./mq_lib');
const f = new File('marquee_skyrim', 'mq_sky_');

// ================================================================== HELGEN
f.npc('helgen_mason', 'Mason Orvar Stonehand', 'blacksmith', 'helgen', 5, 4,
  'Orvar is rebuilding the Helgen road-gate one block at a time, mostly alone. He says a gate is a promise, and that Helgen has made several it could not keep.');
f.npc('helgen_widow', 'Widow Sigrun', 'citizen', 'helgen', -6, 5,
  'Sigrun lost a husband and a house on the same bad morning. She now keeps a candle in the window of the second, and will tell you, kindly, which of the neighbours were not at home when it happened.', true);
f.relic('helgen_ledger', 'Burnt Toll-Ledger of Helgen', 'A half-burned toll ledger of the Helgen gate, scorched at the edges. The last legible page lists a caravan that never passed.', 11);
f.q({
  id: 'the_ledger_of_helgen', title: 'The Gate-Ledger of Helgen', region: 'skyrim/falkreath', start: 'npc:@helgen_mason', requires: ['level:8'],
  offer: 'You are repairing a gate no one uses.',
  pitch: 'Someone will. A ledger went missing from the tollhouse the night the keep burned, and with it the names of every caravan that paid to pass. There are heirs asking. There are debts to be settled. Orvar thinks a scavenger took it from the ashes. The ruins of the tollhouse are picked clean, but the bandits of Bonechill Passage have a habit of burning paper to keep warm.',
  desc: 'Mason Orvar wants the toll-ledger of Helgen, lost in the burning of the keep, recovered from scavengers in Bonechill Passage.',
  objectives: [
    o.go('bonechill_passage', 'Search the scavengers\' camp in Bonechill Passage'),
    o.clear('bonechill_passage', 'Drive off the bandits who have been burning Helgen\'s papers',
      [mob('nirncraft:bandit', 3, 'Paper-Burner')], { placement: 'structure' }),
    o.retrieve('bonechill_passage', 'Take the half-burnt toll-ledger', '@helgen_ledger', { placement: 'structure' }),
    o.talk('@helgen_widow', 'Show Sigrun the last page of the ledger',
      'Your husband\'s caravan is on the last page. It is not burnt. It lists a toll paid in full, and a name signed on the line below.',
      'Sigrun reads and her hand goes to her mouth. "Paid in full. He paid. He was not a smuggler, they said. They were wrong. They were all so very wrong. Thank you, stranger. Take what the Widow has: a cup of the good ale, and the window candle for the night."'),
  ],
  outro: 'A scorched ledger told a widow that her husband had paid every toll he owed. The gate at Helgen has been reset a little straighter since.',
  rewards: { gold: 220, loot: 'minor' },
});

// ================================================================== SHOR'S STONE
f.npc('shors_prospector', 'Prospector Hakon Four-Fingers', 'miner', 'shors_stone', 4, 4,
  'Hakon lost a finger to the Northwind mine and says it was a fair trade for what he found. He has not yet told anyone what he found. He is a little drunk, and very close to telling.');
f.npc('shors_watcher', 'Watchwoman Ragna', 'hunter', 'shors_stone', -5, -4,
  'Ragna keeps the old watch-tower above Shor\'s Stone and has seen three armies pass and a fourth start. She considers herself a person of settled opinions.', true);
f.q({
  id: 'what_the_miner_found', title: 'What Hakon Found', region: 'skyrim/the_rift', start: 'npc:@shors_prospector', requires: ['level:10'],
  offer: 'You are a man with a secret and one finger short.',
  pitch: 'A vein, friend. In the deep of Northwind mine, past a fall of rock the others gave up on. A black vein, wet, that glints like a wet beetle and does not behave like ore. Hakon took out one chunk and it nearly took his hand. He would not go back. He would pay for the chunk\'s fellows. He would pay more to be told what it is.',
  desc: 'Prospector Hakon found a strange black vein in Northwind Mine and wants it surveyed and a sample brought out.',
  objectives: [
    o.go('northwind_mine', 'Descend to the rock-fall at the bottom of Northwind Mine'),
    o.clear('northwind_mine', 'Clear the creatures that nest where the vein lies',
      [mob('nirncraft:frostbite_spider', 3, 'Vein-Spider'), mob('nirncraft:skeever', 2, 'Tunnel-Skeever')], { placement: 'structure' }),
    o.retrieveItem('northwind_mine', 'Chip a sample from the black vein', 'minecraft:coal', 3),
    o.talk('@shors_prospector', 'Show Hakon what the vein is',
      'It is just coal, Hakon. Very old, very hard, very black coal. It nearly took your hand because the fall was unstable.',
      'Hakon stares at the lump for a long time, and then he begins to laugh. "Coal! Four fingers. Coal! Friend, I have lost a finger, a week\'s wages and a good night\'s sleep for coal. It is the best coal Hakon has ever seen. Sell it to the smith at Riften. He will thank you, and Hakon will buy you an ale."'),
  ],
  outro: 'The strange vein in Northwind mine was coal. Hakon sold it, drank to it and, in time, forgave the missing finger.',
  rewards: { gold: 180, loot: 'minor' },
});

// ================================================================== DARKWATER CROSSING
f.npc('dark_ferryman', 'Ferryman Bjarnhild', 'fisher', 'darkwater_crossing', 4, 4,
  'Bjarnhild ferries travellers across the Darkwater on a flat boat pulled by a rope and her own stubborn will. She says the river is only as deep as you believe it is.', true);
f.q({
  id: 'the_rope_that_was_cut', title: 'The Rope That Was Cut', region: 'skyrim/eastmarch', start: 'npc:@dark_ferryman', requires: ['level:8'],
  offer: 'Your ferry-rope has been cut.',
  pitch: 'Cut, friend. Not frayed. Cut clean on the far side, in the night. Three times this month. A good rope is dear, and the guild-trader at the crossing says it is a rival. Bjarnhild thinks it is the wolves, as wolves chew things. But wolves do not carry a knife. Watch the far bank tonight.',
  desc: 'Ferryman Bjarnhild\'s rope has been cut three times in a month. She wants the culprit caught on the far bank.',
  objectives: [
    o.cond('night', 'Watch the far bank of the Darkwater at night', 'darkwater_crossing'),
    o.kill('darkwater_crossing', 'Catch whoever is cutting the ferry-rope',
      [mob('npc:thief', 1, 'Rope-Cutter', { hostile: true })]),
    o.talk('@dark_ferryman', 'Tell Bjarnhild who cut the rope',
      'It was a cart-driver from the road, paid by a rival ferry to cripple you. I convinced him of his mistake.',
      'Bjarnhild leans on the rope-post, and for a moment looks very tired. "A rival ferry. Upriver. At two coins cheaper. And a man who cut my rope to take my trade. Stranger, I would have given him a loaf and told him to ask. Thank you. The first ride across is on the house, as long as I am alive."'),
  ],
  outro: 'The Darkwater ferry runs on a new rope, and its owner sleeps lightly with a knife of her own under the pillow.',
  rewards: { gold: 160, loot: 'minor' },
});

// ================================================================== DRAGON BRIDGE
f.npc('db_toll', 'Tollkeeper Aldis Ferr', 'steward', 'dragon_bridge', 5, 4,
  'Aldis takes the toll at the bridge and has opinions on everyone who passes. He says a bridge sees more of a person than their mother ever will, and about as much as their banker.');
f.npc('db_lamp', 'Hilde the Lamp-Wife', 'innkeeper', 'dragon_bridge', -5, 5,
  'Hilde runs the Lamp-Wife\'s, a tiny tavern just off the bridge, and keeps a lantern hung out all night so no one drowns in the dark. She has never lost a guest. She says that is the secret of good business.', true);
f.q({
  id: 'the_bridge_that_hums', title: 'The Bridge That Hums', region: 'skyrim/haafingar', start: 'npc:@db_toll', requires: ['level:12'],
  offer: 'You have been listening to your bridge.',
  pitch: 'Humming, friend. A low note, under the stone, only at midnight. It was a bridge, a dumb thing of rock and mortar, and now it hums. Aldis put his ear to the ground. It is not the bridge. It is under the bridge. Under the river. A thing that does not belong, singing in its sleep. Pinemoon Cave is the nearest place a hole to the deep might come out.',
  desc: 'The bridge at Dragon Bridge hums at midnight. Aldis believes something under the river is singing, and the likeliest cave mouth is Pinemoon Cave.',
  objectives: [
    o.go('pinemoon_cave', 'Enter Pinemoon Cave and follow the sound downward'),
    o.clear('pinemoon_cave', 'Clear the cave of what has been singing',
      [mob('nirncraft:frostbite_spider', 2, 'Hum-Spinner'), mob('nirncraft:wisp', 2, 'Singing Wisp')], { placement: 'structure' }),
    o.talk('@db_toll', 'Tell Aldis the bridge is quiet',
      'The singers were wisps in the cave, resonating with the stone. They are gone. The bridge should be quiet.',
      'Aldis stands very still with his ear to the stone. After a long moment, he straightens. "Quiet. Gods. I had grown used to it. A bridge ought to be quiet. It is the only thing it is good for. But, stranger, tonight I will miss it, a little. Hilde\'s will give you a bed, and I will pay for it."'),
  ],
  outro: 'The bridge at Dragon Bridge is quiet again. The tollkeeper says he does not miss the humming, and has taken to whistling at midnight, to fill the silence.',
  rewards: { gold: 280, loot: 'standard' },
});
f.q({
  id: 'the_lamp_that_never_goes_out', title: 'The Lamp That Never Goes Out', region: 'skyrim/haafingar', start: 'npc:@db_lamp', requires: ['level:8'],
  offer: 'You are the one with the lantern.',
  pitch: 'Every night for nineteen years, friend. Hilde has not let it go out once. Last night it did. Not oil. Not wind. A hand, in the dark, closed over the flame. Hilde saw nothing. A guest, a good man, walked out into the dark and has not come back. She would very much like to know what put out her lamp, and where it put the man.',
  desc: 'The lantern of the Lamp-Wife\'s inn went out for the first time in nineteen years, and a guest has not returned from the dark.',
  objectives: [
    o.cond('night', 'Keep watch at the lantern until it is touched again', 'dragon_bridge'),
    o.kill('dragon_bridge', 'Stop the thing that snuffs the lamp',
      [mob('nirncraft:wisp', 1, 'Dark-Hand Wisp', { target: true }), mob('minecraft:skeleton', 1, 'Lamp-Smothered Man')]),
    o.talk('@db_lamp', 'Tell Hilde what put out the lamp',
      'It was a wisp, drawn by the flame, that smothered it and carried your guest into the dark. I put it down, and he is at rest.',
      'Hilde lowers her head, and for a time says nothing at all. "At rest. Well. It is something. He paid for three nights and stayed for one. Stranger, the lamp will burn again tonight. And I shall leave a second one lit, by the door, for him."'),
  ],
  outro: 'The lamp of the Lamp-Wife\'s burns every night, and a second, smaller one burns beside it, for the one who did not return.',
  rewards: { gold: 220, loot: 'minor' },
});

// ================================================================== KYNESGROVE
f.npc('kyn_miner', 'Foreman Dorlag Ironbrow', 'miner', 'kynesgrove', 5, 4,
  'Dorlag runs the Kynesgrove steel-mine and says he has never lost a miner to anything but old age. This is technically true, as he has chosen not to count the ones who died in their thirties.');
f.q({
  id: 'the_miners_wages', title: 'Short Wages at Kynesgrove', region: 'skyrim/eastmarch', start: 'npc:@kyn_miner', requires: ['level:10'],
  offer: 'Your men look underpaid.',
  pitch: 'They are, friend. Not by me. By the carter who brings the pay from Windhelm. Three weeks running, the purse has been light by a tenth. Dorlag does not wish to accuse a Windhelm man of theft without proof. The cart goes by Narzulbur and Gloombound Mine. A road of bad places and worse company. Ride the road. Watch what comes off the cart.',
  desc: 'The Kynesgrove miners\' wage-purse has been short by a tenth three weeks running. Foreman Dorlag wants the carter\'s route watched.',
  objectives: [
    o.go('gloombound_mine', 'Watch the pay-cart\'s road near Gloombound Mine'),
    o.kill('gloombound_mine', 'Stop the ambusher who skims the pay-cart',
      [mob('nirncraft:bandit_chief', 1, 'Skim-Hand Brynolf'), mob('nirncraft:bandit', 2, 'Road-Cutter')]),
    o.talk('@kyn_miner', 'Tell Dorlag who was skimming the wages',
      'Brynolf, a former carter. He kept a tenth of every purse and said it was a toll. The tenth is here.',
      'Dorlag takes the purse and counts it, slowly, three times. "A tenth. A tenth of every wage. For months. My men have been feeding their families on nine-tenths of what they earned. Stranger, I will make it right, one tenth at a time. I will tell them it was you who found it. They will want to buy you a drink, and then another."'),
  ],
  outro: 'The wages of Kynesgrove come whole again. Dorlag has made a ledger, in which a tenth is set aside, every week, for the carter\'s widow, and every man in the mine has signed.',
  rewards: { gold: 300, loot: 'standard' },
});

// ================================================================== KARTHWASTEN
f.npc('karth_mine', 'Overseer Ulfhild Rockjaw', 'miner', 'karthwasten', 5, 4,
  'Ulfhild oversees the Sanuarach mine under a pressing sense that the Forsworn are watching, and the other mine-owners are watching the Forsworn, and the Forsworn are watching her, a little more kindly than the rest.', true);
f.q({
  id: 'the_forsworn_in_the_ore_cart', title: 'The Forsworn in the Ore Cart', region: 'skyrim/the_reach', start: 'npc:@karth_mine', requires: ['level:14'],
  offer: 'You have the look of someone who doesn\'t trust the quiet.',
  pitch: 'I don\'t, friend. A cart left the mine on Fredas, full of ore, and arrived at the smelter at Markarth with a hidden passenger. A Forsworn scout, the Markarth guards say. Ulfhild did not put him there. Someone did. Someone in the mine is passing messages out in the ore. Find them, before the guards of Markarth shut Karthwasten out of the trade.',
  desc: 'A Forsworn scout was found in an ore cart from the Karthwasten mine. Overseer Ulfhild wants the traitor in her mine found before Markarth closes the trade.',
  objectives: [
    o.go('karthwasten', 'Question the miners at the ore-carts'),
    o.go('dragontooth_crater', 'Follow the scout\'s trail to Dragontooth Crater'),
    o.kill('dragontooth_crater', 'Stop the Forsworn handler who passes messages',
      [mob('nirncraft:forsworn', 2, 'Crater-Watcher'), mob('nirncraft:hagraven', 1, 'Ore-Crone', { target: true })]),
    o.talk('@karth_mine', 'Tell Ulfhild what you found',
      'It was a hagraven who hid messages in ore and used the scout to carry them. She has no hand in your mine. No one inside it did.',
      'Ulfhild\'s shoulders drop. "No one inside. No one of mine. Thank the Divines. Stranger, the Markarth guards will hear it from my own mouth before the evening. And Ulfhild will keep a pick handy for the next hagraven."'),
  ],
  outro: 'The Karthwasten mine was cleared of suspicion, and the ore carts roll to Markarth again, each one with a small bell tied to the axle, so everyone hears it pass.',
  rewards: { gold: 400, loot: 'standard' },
});

// ================================================================== IVARSTEAD
f.npc('iva_lodge', 'Wulfgar of the Vilemyr Inn', 'innkeeper', 'ivarstead', 5, 4,
  'Wulfgar keeps the Vilemyr and says he has been told, very often, that he runs the quietest tavern in Skyrim. He considers this high praise. The other regulars consider it a warning.');
f.npc('iva_stair', 'Pilgrim Dagny of the Seven Thousand', 'priest', 'ivarstead', -5, -4,
  'Dagny has climbed the Seven Thousand Steps eleven times. She never reaches the top. She says that is not the point. It would be a very long walk to prove it.', true);
f.q({
  id: 'the_pilgrim_who_stopped', title: 'The Pilgrim Who Stopped', region: 'skyrim/the_rift', start: 'npc:@iva_stair', requires: ['level:12'],
  offer: 'You are staring up a very long stair.',
  pitch: 'I am, friend. A pilgrim stopped on the Steps three days ago. Sat down at the one thousand, three hundred and ninth, and has not moved. Not hungry, not cold. Dagny took him a blanket. He thanked her and said he was waiting. For what, he would not say. She has climbed past him eleven times and cannot stop thinking of it. Go up. Ask him. Please.',
  desc: 'A pilgrim has stopped on the Seven Thousand Steps and will not move. Dagny asks a stranger to climb and ask him why.',
  objectives: [
    o.go('throat_of_the_world', 'Climb the Seven Thousand Steps to the stopped pilgrim'),
    o.talk('@iva_stair', 'Report the pilgrim\'s answer to Dagny',
      'He said he is waiting for the wind to change. He is waiting for the mountain to say he may continue. He is an old man, and very content.',
      'Dagny smiles, a thing she does rarely. "Waiting for the mountain. Yes. That is exactly the right thing. Stranger, I have been climbing and he has been waiting, and he is nearer the top than I. Let him be. I will climb past and nod, and he will nod back, and that will be a conversation."'),
  ],
  outro: 'A pilgrim waits for the mountain on the thousandth step, and Dagny nods to him every time she passes. The mountain has not yet said.',
  rewards: { gold: 150, loot: 'minor' },
});
f.q({
  id: 'the_barrow_that_snored', title: 'The Barrow That Snored', region: 'skyrim/the_rift', start: 'npc:@iva_lodge', requires: ['level:10'],
  offer: 'You are not sleeping well, Wulfgar.',
  pitch: 'The barrow, friend. Shroud Hearth. Right up the road. Every night, a sound. Not a moan. A snore. A great, slow, comfortable snore. The kind a man makes after a very long dinner. Wulfgar has lived beside it all his life and never heard it before. A draugr who snores is either very content or very new. Please look.',
  desc: 'Shroud Hearth Barrow snores at night. Wulfgar of the Vilemyr wants to know what is sleeping there so contentedly.',
  objectives: [
    o.go('shroud_hearth_barrow', 'Enter Shroud Hearth Barrow at night'),
    o.clear('shroud_hearth_barrow', 'Silence the sleeper and its guards',
      [mob('nirncraft:draugr', 3, 'Barrow-Dozer'), mob('nirncraft:draugr_deathlord', 1, 'The Snorer')], { placement: 'structure' }),
    o.talk('@iva_lodge', 'Tell Wulfgar the barrow is quiet',
      'There was a draugr on a bed of stone, snoring. It had been dreaming of a great feast. It is quiet now.',
      'Wulfgar sits down heavily. "A feast. Nine hundred years of dreaming about dinner. The poor soul. Stranger, I will make you a plate. The best the Vilemyr has. And I will leave a plate at the barrow gate, on the first night of every month, in his honour."'),
  ],
  outro: 'The snoring at Shroud Hearth is no more. A plate of roast goat is left at its gate each month, and it is always empty by morning.',
  rewards: { gold: 240, loot: 'standard' },
});

// ================================================================== MILLS, FARMS, STABLES
f.npc('heartwood_foreman', 'Foreman Hrolf Cutbeard', 'lumberjack', 'heartwood_mill', 4, 3,
  'Hrolf runs the Heartwood Mill and says a sawmill is the only honest industry in Skyrim, because the saw does not lie about what it has done.');
f.q({
  id: 'the_blade_in_the_log', title: 'The Blade in the Log', region: 'skyrim/the_rift', start: 'npc:@heartwood_foreman', requires: ['level:10'],
  offer: 'Your saw has been ruined.',
  pitch: 'Third time this season, friend. Something hard in a log. Last week it was a sword-blade, buried deep, grown into the heartwood, a very old kind of steel. The log came from the Autumnshade grove. A tree that swallowed a sword, and the sword has teeth. Hrolf would like to know what else is in that grove, before another saw is lost.',
  desc: 'Foreman Hrolf\'s sawmill keeps ruining blades on swords buried in logs from Autumnshade Clearing. He wants the grove searched.',
  objectives: [
    o.go('autumnshade_clearing', 'Search Autumnshade Clearing for the source of the swords'),
    o.clear('autumnshade_clearing', 'Drive off the spriggans that have been feeding trees iron',
      [mob('nirncraft:spriggan', 2, 'Iron-Fed Spriggan'), mob('nirncraft:wolf', 2, 'Grove-Wolf')]),
    o.talk('@heartwood_foreman', 'Tell Hrolf why the trees hold swords',
      'The spriggans were taking iron from a long-lost battle and pressing it into the trunks, to keep it from the world. They are done now.',
      'Hrolf rubs his jaw. "To keep it from the world. That is a generous thing, for a tree. Well, the saws will thank you. Take a plank, friend. It is Autumnshade oak, with a sword in it. It will make a handsome door."'),
  ],
  outro: 'The saws of Heartwood Mill run clean. A single door of Autumnshade oak, with a sword-shaped scar, hangs at the mill\'s gate.',
  rewards: { gold: 200, loot: 'minor' },
});

f.npc('sawmill_widow', 'Dagmar Oakhand', 'lumberjack', 'solitude_sawmill', 4, 3,
  'Dagmar runs the Solitude Sawmill since her husband was lost to a falling oak, and curses the forest roundly every morning, and then asks it, politely, for a little more timber.', true);
f.q({
  id: 'the_oak_that_fell_wrong', title: 'The Oak That Fell Wrong', region: 'skyrim/haafingar', start: 'npc:@sawmill_widow', requires: ['level:10'],
  offer: 'You look at that oak as if it had a name.',
  pitch: 'It does, friend. Her husband\'s. The oak that killed him fell the wrong way, against the wind, against the grain, as if it chose. Dagmar has never been a fanciful woman. She has been trying for three years to believe it was only an oak. A woodsman from Haafingar swears it was cut with an axe too clean to be a man\'s. Go to the stump. Tell her what you see.',
  desc: 'Dagmar\'s husband was killed by an oak that fell against the wind. She asks a stranger to examine the stump and the clearing and tell her the truth.',
  objectives: [
    o.go('meekos_shack', 'Search the stump and the clearing near Meeko\'s Shack'),
    o.kill('meekos_shack', 'Confront the hunter who set the felling-cut',
      [mob('npc:hunter', 1, 'Wind-Cut Hunter', { hostile: true })]),
    o.talk('@sawmill_widow', 'Tell Dagmar what happened to her husband',
      'A poacher, hiding in the oak\'s shade, was spotted by your husband. He cut the tree to bury the witness. He is dead now, and he told me.',
      'Dagmar is silent for a long time. Then she picks up her axe, and carries it to the stump, and sets it there with the handle pointing up. "Thank you, stranger. Three years. It was never the tree. It was never the forest. I owe the oak an apology, and I shall give it, and plant a sapling in its place."'),
  ],
  outro: 'A widow planted a sapling beside an old stump, and, for the first time in three years, stopped cursing the forest.',
  rewards: { gold: 240, loot: 'minor' },
});

f.npc('bb_farmer', 'Erlend Battle-Born', 'farmer', 'battle_born_farm', 3, 3,
  'Erlend tends the Battle-Born farm and has a great many opinions about his neighbours, the Whiterun Guard and the proper depth of a turnip furrow. He is rarely asked for any of them.');
f.q({
  id: 'the_scarecrow_that_walked', title: 'The Scarecrow That Walked', region: 'skyrim/whiterun', start: 'npc:@bb_farmer', requires: ['level:6'],
  offer: 'Your scarecrow has moved again.',
  pitch: 'Four steps, friend, every night, towards the house. Erlend has put a stake through it. He has put a bell on it. He has asked it, very politely, to stop. It does not. Tonight it is at the door. He would prefer it were not there tomorrow. He would not like to say what he suspects is inside.',
  desc: 'A scarecrow on the Battle-Born farm has been walking towards the house. Erlend wants it dealt with.',
  objectives: [
    o.cond('night', 'Wait at the farmhouse door for the scarecrow', 'battle_born_farm'),
    o.kill('battle_born_farm', 'Break the scarecrow and what inhabits it',
      [mob('nirncraft:wisp', 1, 'Straw-Haunting Wisp', { target: true })]),
    o.talk('@bb_farmer', 'Tell Erlend what was in the scarecrow',
      'A wisp, bored, that had taken up residence in the straw. It is gone. The scarecrow is just straw again.',
      'Erlend takes the straw in both hands, shakes it out, and looks vaguely embarrassed. "A wisp. Bored. Well. I may have talked to it a good deal, the past month. It seemed lonely. Stranger, I shall make a new one, and put a hat on it, and a name. The wisp can have it, if it comes back."'),
  ],
  outro: 'A new scarecrow stands on the Battle-Born farm, with a hat, a name and a lantern, and does not walk.',
  rewards: { gold: 120, loot: 'minor' },
});

f.npc('ss_farmer', 'Aldis Snow-Shod', 'farmer', 'snow_shod_farm', 3, 3,
  'Aldis keeps the Snow-Shod farm and swears the name is a joke by an ancestor. The horses are shod in snow, he says, in winter. In summer they do not need shoes. Visitors have not been able to find the joke.');
f.q({
  id: 'the_horse_that_came_back', title: 'The Horse That Came Back', region: 'skyrim/the_rift', start: 'npc:@ss_farmer', requires: ['level:8'],
  offer: 'You have a stable with an empty stall.',
  pitch: 'Two, friend. A mare and a gelding, taken in the night by a man who left a coin for each. Aldis took the coins to the smith. They are not Skyrim coin. They are old, very old, stamped with a face that has been dead a thousand years. The horses came back this morning, thin and wet, with sand in their hooves. There is no sand within a month\'s ride. Someone has been riding them far.',
  desc: 'Two horses were taken from the Snow-Shod farm and returned with sand in their hooves and ancient coins left behind.',
  objectives: [
    o.go('nightingale_hall', 'Follow the hoof-tracks toward Nightingale Hall'),
    o.clear('nightingale_hall', 'Search the hall\'s stable-yard for the horse-thieves',
      [mob('nirncraft:bandit', 3, 'Night-Rider')]),
    o.talk('@ss_farmer', 'Tell Aldis who rode his horses',
      'A band of riders used them to move something across the Rift at night. They paid in coin from a hoard they found. They have gone.',
      'Aldis turns one of the coins over, slowly. "A hoard. Old as dust. Well, stranger, I shall keep the coins. A horse is a horse, and a coin is a coin, and a thousand-year-old king is a very handsome thing to hang above the stable door."'),
  ],
  outro: 'Two horses came back thin and wet, and a thousand-year-old king hangs above the stable at the Snow-Shod farm, as a mark of respect.',
  rewards: { gold: 200, loot: 'minor' },
});

f.npc('hl_farmer', 'Ysolda Hlaalu-Wife', 'farmer', 'hlaalu_farm', 3, 3,
  'Ysolda married into the Hlaalu farm from Windhelm\'s Gray Quarter and has never let anyone forget how much better the soup was there. She makes a surprisingly good ash-yam stew nonetheless.', true);
f.q({
  id: 'the_missing_ash_yams', title: 'The Missing Ash-Yams', region: 'skyrim/eastmarch', start: 'npc:@hl_farmer', requires: ['level:8'],
  offer: 'Your yams look short.',
  pitch: 'They are, friend. An entire row, taken in the night. Not stolen. Dug up, with a spoon, and the earth patted back down. Ysolda has no idea who would be so tidy. It would have to be a Dunmer, and a very particular one. Ash-yams are a thing a Dunmer misses when they are far from home. Find them, and find out what they need.',
  desc: 'A row of ash-yams was carefully dug up at the Hlaalu farm in the night. Ysolda suspects a Dunmer who misses home.',
  objectives: [
    o.go('refugees_rest', 'Search Refugees\' Rest, where the Dunmer homeless gather'),
    o.talk('@hl_farmer', 'Bring Ysolda what you found at Refugees\' Rest',
      'An old Dunmer woman had dug them. She has no home, and no yams, and a granddaughter who is sick. She asked me to say sorry.',
      'Ysolda\'s face softens, and she wipes her hands on her apron. "A granddaughter who is sick. Stranger, tell her to come to the farm. Not for the yams. For the whole row. Ysolda will give her the rest of the field, and the recipe, and a corner by the fire. I should have grown more in the first place."'),
  ],
  outro: 'An old Dunmer woman has a field, a stew-pot and a corner by the fire at the Hlaalu farm. Her granddaughter is mending.',
  rewards: { gold: 180, loot: 'minor' },
});

f.npc('bm_brewer', 'Hjorth Brandy-Mug', 'farmer', 'brandy_mug_farm', 3, 3,
  'Hjorth brews a very fine brandy in the cellar and a very poor apology in the kitchen. He is a gifted distiller and an ungifted husband, and everyone, including the husband, knows it.');
f.q({
  id: 'the_brandy_that_aged_overnight', title: 'The Brandy That Aged Overnight', region: 'skyrim/eastmarch', start: 'npc:@bm_brewer', requires: ['level:8'],
  offer: 'Your brandy is the colour of old money.',
  pitch: 'It is, friend, and it should not be. A barrel Hjorth laid down on Sunday is the colour of forty years by Tuesday. Smooth, deep, very fine. Quite wrong. It is not the cellar. It is not the barrel. It is something in the cellar, a thing that ages. He would like it out before it ages the cellar, the farm and himself.',
  desc: 'A barrel of brandy aged forty years in two days in Hjorth\'s cellar. He wants the cause removed before it ages him too.',
  objectives: [
    o.go('brandy_mug_farm', 'Search the cellar of the Brandy-Mug farm'),
    o.kill('brandy_mug_farm', 'Destroy the thing that hastens time in the cellar',
      [mob('nirncraft:wisp', 1, 'Cellar-Aging Wisp', { target: true }), mob('nirncraft:skeever', 2, 'Cellar Skeever')]),
    o.talk('@bm_brewer', 'Tell Hjorth the cellar is safe',
      'A wisp had been feeding on the barrels, and aged them as it ate. It is gone. The brandy is only brandy again.',
      'Hjorth uncorks the nearest barrel, sniffs and, with deep reluctance, sighs. "Just brandy. Ordinary brandy. Not even a year old. Stranger, I have never regretted a thing so quickly. The wisp\'s brandy was divine. But Hjorth will pour you a glass of the other, and it will be young, and honest."'),
  ],
  outro: 'The cellar of the Brandy-Mug farm holds ordinary brandy, aged the ordinary way, and its owner has been heard to sigh at the barrels from time to time.',
  rewards: { gold: 200, loot: 'minor' },
});

f.npc('sa_farmer', 'Rannveig of Sarethi', 'farmer', 'sarethi_farm', 3, 3,
  'Rannveig runs the Sarethi farm and its famous nirnroot-and-snowberry salve. She speaks to her herbs, her salve and the bees, in that order, and lets her customers wait.', true);
f.q({
  id: 'the_herbs_that_wilted_at_dusk', title: 'The Herbs That Wilted at Dusk', region: 'skyrim/the_rift', start: 'npc:@sa_farmer', requires: ['level:10'],
  offer: 'Your herb-beds look sad.',
  pitch: 'They wilt at dusk, friend, every one. And by dawn they are green again, as if nothing happened. Rannveig has watched all night. Nothing. Not a shadow, not a rustle. It is as though the garden has begun to remember something sad and then forget it. She does not like it. A garden should not have bad dreams.',
  desc: 'The herb-beds at the Sarethi farm wilt every dusk and recover at dawn. Rannveig believes something is sapping them each evening.',
  objectives: [
    o.cond('night', 'Watch the beds from dusk until the wilting starts', 'sarethi_farm'),
    o.kill('sarethi_farm', 'Destroy the thing that drinks the garden at dusk',
      [mob('nirncraft:spriggan', 1, 'Dusk-Drinker', { target: true })]),
    o.talk('@sa_farmer', 'Tell Rannveig why her herbs wilted',
      'A young spriggan had taken root at the edge of the garden, and drank from the beds each dusk to grow. It is dead now.',
      'Rannveig sinks to a stool. "A young one. Drinking my herbs to grow. Well. It is not the worst thing a child might do. Stranger, I shall plant a tree at that spot, for it, and say a word. And a bed of nightshade, in case it comes back."'),
  ],
  outro: 'The herb-beds of Sarethi bloom through the dusk. A single young tree grows at the edge of the garden, with a ring of nightshade at its foot.',
  rewards: { gold: 230, loot: 'minor' },
});

f.npc('bb_lodge_steward', 'Steward Ravenna Black-Hand', 'steward', 'black_briar_lodge', 4, 3,
  'Ravenna keeps the Black-Briar Lodge, which is not a lodge so much as a family with a mead-hall attached. She says every lodge has a secret. This one has three, and she is only willing to say two.', true);
f.q({
  id: 'the_silver_in_the_mead', title: 'The Silver in the Mead', region: 'skyrim/the_rift', start: 'npc:@bb_lodge_steward', requires: ['level:14'],
  offer: 'You have been watching the casks.',
  pitch: 'I have, friend. The Black-Briar mead goes out under a seal every week. Three barrels per cart. Last month, one was a quarter lighter. Lost, the carter said. Spilled. Nothing spills from a Black-Briar barrel. Someone is skimming. The lodge has no wish to find out it was family, so it would like you to find out for it, and tell no one.',
  desc: 'The Black-Briar Lodge\'s mead barrels are going out lighter. Steward Ravenna asks a stranger to find the skimmer, quietly.',
  objectives: [
    o.go('lost_prospect_mine', 'Search the road to Lost Prospect Mine where the carts go'),
    o.kill('lost_prospect_mine', 'Catch the skimmer who has been tapping the barrels',
      [mob('nirncraft:bandit', 2, 'Cart-Tapper'), mob('nirncraft:bandit_chief', 1, 'Tap-Master Orrin')]),
    o.talk('@bb_lodge_steward', 'Tell Ravenna who was tapping the casks',
      'A small ring, Orrin and two hands, selling the skimmed mead in the mine. No family. Just men with a long thirst.',
      'Ravenna lets out a slow breath. "Not family. Good. Not family. The lodge may be a sinner, friend, but it does not eat its own. Orrin will be dealt with, quietly, in a way that leaves no ripples. Take this. It is silver. It is the only silver the lodge has ever paid for the truth, and it is the only truth it has ever wanted."'),
  ],
  outro: 'The Black-Briar Lodge\'s mead goes out whole again. Whether the skimmers were dealt with quietly or loudly, the stewards of the lodge sleep a little easier.',
  rewards: { gold: 380, loot: 'standard' },
});

f.npc('wh_stable', 'Stablehand Brenuin', 'citizen', 'windhelm_stables', 3, 3,
  'Brenuin tends the Windhelm stables and has never been known to say a harsh word to a horse. He saves them all for the riders, in a low voice, very precisely, when they are not listening.');
f.q({
  id: 'the_horse_that_would_not_leave', title: 'The Horse That Would Not Leave', region: 'skyrim/eastmarch', start: 'npc:@wh_stable', requires: ['level:8'],
  offer: 'You are looking at an old horse.',
  pitch: 'The grey, friend. Her rider was killed on the road a month ago. A wolf, they said. The grey has not left the stall since. She does not eat. She does not drink. Brenuin has sat with her at night. She looks at the door as if waiting. She wants her rider. Brenuin cannot give her that. But he thinks there is a thing the rider carried, that the grey would know.',
  desc: 'A grey mare at the Windhelm stables waits for a rider who was killed a month ago on the road. Brenuin wants the rider\'s last possession recovered.',
  objectives: [
    o.go('riverside_shack', 'Search the roadside near Riverside Shack where the rider fell'),
    o.retrieveItem('riverside_shack', 'Recover the dead rider\'s saddle-bag', 'minecraft:leather', 1),
    o.talk('@wh_stable', 'Bring the saddle-bag to the grey',
      'The bag was by the road, torn. A scarf was in it, and a letter. The horse should smell it.',
      'Brenuin takes the scarf and carries it to the stall. The grey lowers her head into the cloth, and breathes in, long and slow. Then, for the first time in a month, she lifts her head and eats. "Thank you, stranger," Brenuin whispers. "She has her rider back, in the only way she can."'),
  ],
  outro: 'A grey mare in the Windhelm stables eats her oats with a scarf draped over the stall-door, and has begun, slowly, to look at the road again.',
  rewards: { gold: 140, loot: 'minor' },
});

f.npc('rs_stable', 'Stablemaster Gunnar Redhoof', 'citizen', 'riften_stables', 3, 3,
  'Gunnar keeps the Riften stables with an air of lingering disappointment, as if the horses were a pale reflection of horses he once knew. He has never named which.');
f.q({
  id: 'the_thief_who_returned_everything', title: 'The Thief Who Returned Everything', region: 'skyrim/the_rift', start: 'npc:@rs_stable', requires: ['level:12'],
  offer: 'You look at the empty hooks.',
  pitch: 'A thief, friend. Every saddle gone, every bridle, every blanket. Wiped clean in a night. And then, in the morning, back. Every one, polished and oiled, hung on its hook. It is the cleverest, strangest theft Gunnar has ever heard of. He does not want to catch the thief. He wants to know what a thief wants with a clean saddle for a night. He suspects a very odd sort of person is being very odd indeed.',
  desc: 'Every saddle at the Riften stables vanished in a night and returned oiled and polished. Stablemaster Gunnar wants to know why.',
  objectives: [
    o.cond('night', 'Hide in the tack-room and wait for the thief to return', 'riften_stables'),
    o.kill('riften_stables', 'Catch the polishing thief in the act',
      [mob('npc:thief', 1, 'Saddle-Polisher', { hostile: true })]),
    o.talk('@rs_stable', 'Tell Gunnar why the thief took the saddles',
      'The thief is a former saddler who lost his shop, and cannot sleep unless leather is clean. He took them to polish and brought them back. He meant no harm.',
      'Gunnar scratches his head, and then laughs for the first time in years. "A saddler who cannot sleep! Well. Stranger, I shall give him a post. Every stable could do with a man who polishes at night. And the leather has never looked so good."'),
  ],
  outro: 'The Riften stables now have a night-polisher on the payroll. The saddles have never been so well oiled, and the thief has never slept so well.',
  rewards: { gold: 260, loot: 'standard' },
});

f.npc('hj_farmer', 'Old Ketil of Heljarchen', 'farmer', 'heljarchen', 3, 3,
  'Ketil keeps the Heljarchen farm in the shadow of a ruined tower and refuses, on principle, to be impressed by it. He says a ruin is only a very old farm that has stopped trying.');
f.q({
  id: 'the_tower_at_the_end_of_the_field', title: 'The Tower at the End of the Field', region: 'skyrim/the_pale', start: 'npc:@hj_farmer', requires: ['level:12'],
  offer: 'Your plough keeps turning up things.',
  pitch: 'It does, friend. Nails, a spur, a bit of chain. And last week, a dwarven gear the size of a plate. The Tower of Mzark is a good half-day off, but its old foundations run under my field, and something down there is waking. Ketil can hear it at night. A tick, tick, tick, like a very large clock. He would like it stopped.',
  desc: 'Ketil\'s plough keeps turning up Dwemer parts, and a ticking sound rises from the earth at night. He wants it silenced.',
  objectives: [
    o.go('tower_of_mzark', 'Descend into the Tower of Mzark from the field'),
    o.clear('tower_of_mzark', 'Silence the ticking machine and its guardians',
      [mob('nirncraft:dwarven_spider', 3, 'Field-Spider'), mob('nirncraft:dwarven_sphere', 1, 'Tick-Sphere')], { placement: 'structure' }),
    o.talk('@hj_farmer', 'Tell Ketil the ticking is stopped',
      'A Dwemer clock, still running. The sphere guarded its key. It is stopped. Your field is safe.',
      'Ketil listens for a long time. "Silence. Honest silence. Stranger, I did not know how loud it was until it stopped. I may sleep for a week. Take this spur. It was the first thing the plough turned up. It is a very old, very good spur, and it should go with someone who rides."'),
  ],
  outro: 'The clock beneath the Heljarchen field stopped, and with it a month of sleeplessness. The farmer keeps a Dwemer gear on the mantel, and says it is for luck.',
  rewards: { gold: 300, loot: 'standard' },
});

f.npc('wi_farmer', 'Torvald of Windstad', 'fisher', 'windstad', 3, 3,
  'Torvald runs the Windstad Manor farm with a fisherman\'s patience and an invalid\'s opinions of the weather. He lost his taste for the sea after a wreck, and has never lost his taste for complaint.');
f.q({
  id: 'the_wreck_that_sang', title: 'The Wreck That Sang', region: 'skyrim/hjaalmarch', start: 'npc:@wi_farmer', requires: ['level:14'],
  offer: 'You are looking out to sea.',
  pitch: 'At the Icerunner, friend. The wreck off the point. It has been a rotting hull for twenty years. Last month it began to sing. A woman\'s voice, high and thin, a song Torvald does not know. He is not a man who minds a ghost, but he minds a song he cannot place. There is a feeling, in the second verse, as if it were about him.',
  desc: 'The wreck of the Icerunner sings at night with a woman\'s voice. Torvald wants it quieted and the song explained.',
  objectives: [
    o.go('wreck_of_the_icerunner', 'Row out to the Wreck of the Icerunner at dusk'),
    o.clear('wreck_of_the_icerunner', 'Quiet the drowned crew of the Icerunner',
      [mob('minecraft:drowned', 3, 'Icerunner Crewman'), mob('nirncraft:draugr', 1, 'Singing Mate')], { placement: 'structure' }),
    o.talk('@wi_farmer', 'Tell Torvald whose voice was singing',
      'It was the ship\'s cook, drowned with the crew. She was singing a lullaby, the one you used to hum, when you were a child.',
      'Torvald is quiet for a very long moment. "My mother. She sang it. She was a cook, on a ship, before I was born. Stranger, I never knew her ship. I never knew where she was lost. I have been trying to place that song for forty years. And you say she is quiet now?"'),
  ],
  outro: 'The Icerunner sang once more, a single verse, as a man stood on the shore and hummed the second. Then it was quiet, and the tide went out.',
  rewards: { gold: 360, loot: 'standard' },
});

// ================================================================== SMALL VILLAGES
f.npc('sj_digger', 'Delver Orm of Soljund', 'miner', 'soljunds_sinkhole', 3, 3,
  'Orm delves the sinkhole for the Sanuarach and says the hole has been getting deeper by a hand-span a month. He uses it as a calendar, and a worry, and an excuse to have an opinion.');
f.q({
  id: 'the_hole_that_grows', title: 'The Hole That Grows', region: 'skyrim/the_reach', start: 'npc:@sj_digger', requires: ['level:12'],
  offer: 'Your hole is larger than yesterday\'s.',
  pitch: 'It is, friend, by a hand-span. Every month. At this rate, in a hundred years, Soljund\'s will be a lake. Orm does not mind a lake. He does mind what is making it. There is a thing in the bottom, moving in the dark, patient as a root. It grows the hole a little each night, like a snail grows its shell. He would like it gone before the village falls in.',
  desc: 'The Soljund sinkhole deepens a hand-span each month. Orm believes something in the depths is digging, and wants it stopped.',
  objectives: [
    o.go('soljunds_sinkhole', 'Descend into the deepest part of Soljund\'s Sinkhole'),
    o.kill('soljunds_sinkhole', 'Slay the thing that digs in the dark',
      [mob('nirncraft:giant_scorpion', 1, 'The Patient Digger', { target: true }), mob('nirncraft:skeever', 2, 'Hole-Skeever')], { placement: 'structure' }),
    o.talk('@sj_digger', 'Tell Orm the hole will grow no more',
      'A giant, blind scorpion had been digging for years. It is dead now. The sinkhole is as deep as it is going to be.',
      'Orm stands at the edge and looks down for a long time. "As deep as it is going to be. Stranger, I had grown fond of the worry. A man needs a thing to worry about. I will find another. But thank you. The village will not fall in."'),
  ],
  outro: 'The sinkhole at Soljund\'s stopped growing, and its delver, having lost his calendar, took to marking the month on a notched stick instead.',
  rewards: { gold: 280, loot: 'standard' },
});

f.npc('sh_goatherd', 'Goatherd Brunwulf', 'farmer', 'stonehills', 3, 3,
  'Brunwulf herds goats over the Stonehills crags and says a goat is the only creature in Skyrim with the sense to be suspicious of everything, including you.');
f.q({
  id: 'the_goat_that_knew_too_much', title: 'The Goat That Knew Too Much', region: 'skyrim/hjaalmarch', start: 'npc:@sh_goatherd', requires: ['level:8'],
  offer: 'One of your goats is looking at me.',
  pitch: 'The grey one, friend. She has been to the same cave every day for a week and comes back smelling of old spice. Not a goat\'s habit. Brunwulf followed her once and lost her at a rock the size of a house. She came out the other side an hour later, chewing something. Something not grass. Go and see what it is. A goat does not go to the same place seven days running for no reason.',
  desc: 'A grey goat at Stonehills visits the same cave every day and comes back smelling of spice. Brunwulf wants someone to follow her.',
  objectives: [
    o.go('dimhollow_crypt', 'Follow the grey goat to Dimhollow Crypt'),
    o.talk('@sh_goatherd', 'Tell Brunwulf what the goat is chewing',
      'There is a patch of rare mountain herb growing at the cave mouth. The goat has been eating it. It is a thing the apothecary in Dawnstar pays well for.',
      'Brunwulf smacks his forehead. "A herb! A patch of it! Seven days, and she has been eating my fortune. Stranger, I shall fence it in, and sell the leaves, and give you the first twenty. And the grey one shall have a ribbon. A goat of that wisdom deserves a ribbon."'),
  ],
  outro: 'A grey goat at Stonehills wears a red ribbon and walks to the cave every day, with the air of a small, deliberate landowner.',
  rewards: { gold: 170, loot: 'minor' },
});

f.npc('oh_shepherd', 'Elder Guthlaf of Old Hroldan', 'citizen', 'old_hroldan', 3, 3,
  'Guthlaf has watched Old Hroldan fall into ruin for sixty years, and has begun to suspect that he is the last thing holding it up. He may be right.');
f.q({
  id: 'the_inn_that_would_not_fall', title: 'The Inn That Would Not Fall', region: 'skyrim/the_reach', start: 'npc:@oh_shepherd', requires: ['level:12'],
  offer: 'Your inn is held up by rope.',
  pitch: 'It is, friend. Rope, prayer and the occasional kick. The old Hroldan inn has been leaning for fifty years, and every winter Guthlaf expects it to go. It does not. He has examined the beams. They are rotten. He has examined the foundations. They are sand. It stands on something else. Something under the floor, quietly holding it. He would like to know what, before he thanks it.',
  desc: 'The ruined inn of Old Hroldan stands on rotten beams and sand foundations. Elder Guthlaf wants to know what is holding it up.',
  objectives: [
    o.go('old_hroldan', 'Search the cellar of the Old Hroldan inn'),
    o.clear('old_hroldan', 'Clear the cellar of the draugr-bound pillar',
      [mob('nirncraft:draugr', 2, 'Pillar-Warden'), mob('nirncraft:skeever', 2, 'Cellar Skeever')]),
    o.talk('@oh_shepherd', 'Tell Guthlaf what holds the inn up',
      'A stone pillar, set by an old Nord king, and a draugr guarding it. It has been holding the inn up for a thousand years. I left the pillar. I put the draugr to rest.',
      'Guthlaf nods, slowly, with great gravity. "A pillar. A guard. A thousand years. And it has held, quietly, without thanks. Stranger, I shall paint a name on that pillar, and burn a candle at its foot. Old Hroldan has stood because someone, once, did not let it fall."'),
  ],
  outro: 'The inn of Old Hroldan leans on, held up by a pillar that has a name painted on it now, and a candle.',
  rewards: { gold: 280, loot: 'standard' },
});

f.npc('am_miller', 'Miller Gudrun of Anga', 'lumberjack', 'angas_mill', 3, 3,
  'Gudrun runs Anga\'s Mill with a firm hand and a firmer opinion of the river, which she believes is working against her. She has been proved right three times, which she counts as an average.', true);
f.q({
  id: 'the_millstone_that_turned_backwards', title: 'The Millstone That Turned Backwards', region: 'skyrim/eastmarch', start: 'npc:@am_miller', requires: ['level:10'],
  offer: 'Your wheel is turning the wrong way.',
  pitch: 'Backwards, friend. Against the current, against the wind, against all reason. The great wheel turns the wrong way, and the grain comes out as dust, or as seed, depending on the day. Gudrun is not a woman who minds a miracle. She minds a miracle that ruins her flour. The mill-race runs from the high spring at Uttering Hills. Something up there is turning the water.',
  desc: 'The great wheel at Anga\'s Mill is turning backwards against the current. Gudrun suspects something in the spring at Uttering Hills.',
  objectives: [
    o.go('uttering_hills_cave', 'Follow the mill-race upstream to Uttering Hills Cave'),
    o.clear('uttering_hills_cave', 'Drive off whatever is turning the water',
      [mob('nirncraft:slaughterfish', 3, 'Eddy-Fish'), mob('nirncraft:wisp', 1, 'Whirlpool Wisp')], { placement: 'structure' }),
    o.talk('@am_miller', 'Tell Gudrun the wheel will turn true',
      'A wisp had set up a whirlpool in the spring, and fish were swimming in circles. It is gone. The mill-race runs straight.',
      'Gudrun watches the great wheel slow, turn once, and then, with a groan of wood, begin to move the right way. "True. Honest. As it should be. Stranger, the first flour out of this mill is yours. I will bake the bread myself. It will not be good bread, but it will be correct."'),
  ],
  outro: 'The great wheel of Anga\'s Mill turns true. The first loaf was dense, round and entirely correct, and was delivered with a certain pride.',
  rewards: { gold: 220, loot: 'minor' },
});

f.npc('nz_orc', 'Smith-Wife Grazbar', 'blacksmith', 'narzulbur', 3, 3,
  'Grazbar keeps the forge at Narzulbur and says an Orc stronghold needs two things: a good axe, and a better argument. She supplies both, for a small fee.', true);
f.q({
  id: 'the_forge_that_would_not_light', title: 'The Forge That Would Not Light', region: 'skyrim/eastmarch', start: 'npc:@nz_orc', requires: ['level:14'],
  offer: 'Your forge is cold.',
  pitch: 'It is, friend, and it has never been cold in forty years. A fire that does not go out, stone-lit, from the root of the stronghold itself. Then three nights ago, out. No ash, no smoke. The coals are good, the bellows are good. The fire simply will not take. Grazbar suspects a debt. The old Orsimer forges are fed from a deeper fire, and someone has tampered with the feed.',
  desc: 'The eternal forge of Narzulbur has gone cold for the first time in forty years. Grazbar suspects its deeper fire has been tampered with.',
  objectives: [
    o.go('gloombound_mine', 'Follow the forge\'s fire-vein to Gloombound Mine'),
    o.clear('gloombound_mine', 'Clear the miners-turned-thieves who have blocked the fire-vein',
      [mob('nirncraft:bandit', 3, 'Vein-Blocker'), mob('nirncraft:flame_atronach', 1, 'Starved Fire-Spirit')], { placement: 'structure' }),
    o.talk('@nz_orc', 'Tell Grazbar the forge-fire can be relit',
      'Bandits had blocked the vein with a boulder to steal the coal. I cleared it. The fire-spirit is free. The forge should take.',
      'Grazbar lights the forge with a taper and, with a roar, it takes. She laughs, huge and rough. "Light! Honest light! Stranger, the stronghold owes you a feast and a blade. Take an axe off the rack. It is my best work, and I have never been prouder to give it away."'),
  ],
  outro: 'The forge of Narzulbur roars once more. A stranger carries an axe from the rack, and the smith-wife swears she will make a better one by spring.',
  rewards: { gold: 360, loot: 'standard', items: [{ item: 'nirncraft:orcish_sword', name: 'Grazbar\'s Gift', enchant: { sharpness: 2, fire_aspect: 1 } }] },
});

const n = f.write('marquee_skyrim.json');
console.log('skyrim: ' + f.npcs.length + ' npcs, ' + n + ' quests');
addToIndex(QUEST_DIR, 'marquee_skyrim.json');
