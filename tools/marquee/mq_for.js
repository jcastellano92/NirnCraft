'use strict';
// Builds quests/marquee_foreign.json and lore/places/marquee_foreign.json for Atmora, Akavir, Yokuda, Pyandonea
// and Thras. Run: node tools/marquee/mq_for.js
const { File, o, choice, mob, writeLore, addToIndex, LORE_DIR, QUEST_DIR } = require('./mq_lib');
const f = new File('marquee_foreign', 'mq_for_');

// ================================================================== ATMORA: Kjolvik
f.npc('hrafn', 'Jarl Hrafn the Last', 'jarl', 'kjolvik', 4, 4,
  'Hrafn rules the last longhall on the ice. He has no steward, no housecarl and no heir. He has a very large axe and ten dozen stories, and he gives all of them away to strangers, for stories are lighter to carry than sorrow.');
f.npc('rolla', 'Skald Rolla Whale-Voice', 'bard', 'kjolvik', -5, 5,
  'Rolla sings the Migration Song, which is four hundred verses long and has been sung without pause, in shifts, since the first Nords sailed. She is on verse three hundred and sixty, and her voice has begun to sound like the sea itself.', true);
f.npc('grim', 'Hunter Grim Ice-Scar', 'hunter', 'kjolvik', 8, -4,
  'Grim hunts on the shelf where the ice cracks. He has seen a dragon there, once, asleep under a skin of ice with its eye open. He says it winked. Nobody else believes him, and he does not mind.');
f.npc('ysa', 'Mead-Wife Ysa of the Last Hearth', 'innkeeper', 'kjolvik', -2, -7,
  'The Last Hearth is a half-buried longhall where the fire has not been out in nine hundred years. Ysa keeps it fed with driftwood and whale-oil and a quiet, stubborn refusal to be the one who lets it die.', true);
f.npc('hjoldis', 'Factor Hjoldis Seaborn', 'merchant', 'dawnstar', 8, 6,
  'Hjoldis buys, sells and sponsors anything that has ever been rumoured to come from Atmora. She keeps a map with a very big blank in the north, and a very small ship in her ledger that has not yet returned.', true);

f.relic('migration_verse', 'The Unsung Verse', 'A strip of whale-hide inscribed in ancient runes with a single stanza of the Migration Song, longer than any living skald can hold in a breath. Whoever sings it is said to remember a country that has not existed for a thousand years.', 15);
f.relic('feast_horn', 'The Feast-Horn of Kjolvik', 'A great curved horn of white bone, bound in iron and carved with ships. It has been blown at every feast since the settlement was founded, and has never once been blown in anger.', 22);
f.relic('barnacled_chart', 'Saarvik\'s Barnacled Chart', 'A sea-chart of waxed hide, stiff with salt and crusted with barnacles. It shows a coast no living Nord has named, and a harbour marked in a red that is not ink, with a single word beside it in the old tongue: HOME.', 11);
f.boss('winter_wight', { name: 'The Wight of the Ninth Winter', type: 'skycraft:draugr_deathlord', hp: 4.2, dmg: 1.7, level: 36,
  weapon: 'skycraft:ancient_nord_greatsword', gear: { head: 'skycraft:ancient_nord_helmet', chest: 'skycraft:ancient_nord_chestplate' }, drop: 'loot:major', glow: true });
f.boss('ice_warden', { name: 'Skjaldborg\'s Last Shieldman', type: 'skycraft:draugr_deathlord', hp: 3.8, dmg: 1.6, level: 34,
  weapon: 'skycraft:ancient_nord_sword', gear: { head: 'skycraft:ancient_nord_helmet' }, drop: 'loot:major', glow: true });
f.scene('atm_feast', [
  ['narrator', 'Ten long halls lie half-buried in drifts, their roofs furred with ice. In the one at the centre, a fire the width of a cartwheel burns. Nobody tends it. Everybody is watching.'],
  ['@hrafn', 'It is the last feast, stranger. The ninth winter in a row without a whale. We have meat for one more night. Tomorrow we choose: the ships, or the ice. Tonight we sing.'],
]);
f.scene('atm_wight', [
  ['narrator', 'The wind drops. Across the ice, in a line, stand eleven figures wrapped in frost, each carrying a shield, each with a candle where a heart should be. The candles gutter, one by one.'],
  ['@rolla', 'They are the ones the ninth winter took. They have come to the feast, as the old law says they may. Do not strike the first. They have not yet decided if we are guests, or the meal.'],
]);

f.q({
  id: 'the_last_winter_feast', title: 'The Last Winter Feast', region: 'atmora/frozen_forest', start: 'npc:@hrafn', requires: ['level:32'],
  offer: 'You have the look of a jarl who is counting his stores.',
  pitch: 'Counting, stranger, and finding a long road at the end of the number. Kjolvik has ten halls and a hundred and four mouths. Nine winters ago the whales stopped coming. Seven winters ago the seals. Hrafn will hold the last feast on the solstice. Bring meat for it, or ships will carry us out of the ice. Which, is your decision, and the hall\'s. Grim has seen things on the shelf that may help.',
  desc: 'Jarl Hrafn of Kjolvik will hold a last feast on the winter solstice. The settlement must decide whether to leave the ice. He needs meat and a witness to the choice.',
  objectives: [
    o.go('kjolvik', 'Attend the gathering at the Elder Longhall', { actions: 'scene:@atm_feast' }),
    o.go('atmoran_giants_cairn', 'Hunt on the shelf near the Giant\'s Cairn, as Grim advises'),
    o.clear('atmoran_giants_cairn', 'Drive off the ice-beasts that guard the herd',
      [mob('skycraft:frost_giant', 1, 'Cairn-Giant'), mob('skycraft:wolf', 3, 'Rime-Wolf')]),
    o.retrieveItem('atmoran_giants_cairn', 'Bring back meat for the feast', 'minecraft:cooked_beef', 8),
    o.cond('night', 'Return to Kjolvik before the solstice night falls', 'kjolvik', { actions: 'scene:@atm_wight' }),
    o.kill('kjolvik', 'Face the Wight of the Ninth Winter at the feast-table',
      [{ boss_id: '@winter_wight' }, mob('skycraft:draugr', 3, 'Frost-Shield')]),
    o.talk('@hrafn', 'Tell Hrafn what the wight asked before it fell',
      'The wight said: the ninth winter does not end. It asks for a hundred and four. Or the hall\'s fire, and a promise to leave.',
      'Hrafn is silent for a long moment. The great fire cracks. "The old law says the ninth winter takes a tithe. I will pay it. But I will not pay it in lives. Stranger, what do you say? The ships are in the harbour. The fire is old. The song has not been finished. Choose, and Kjolvik will follow."',
      { choices: [
        choice('Go. Take the ships. The song can be sung anywhere.', 'Hrafn nods slowly. "Then the fire goes out, tonight, with a song. And a hundred and four will see a green country they have never known. It is a hard kindness, and I thank you for it."', 'flag:@feast_leave'),
        choice('Stay. Feed the fire. The hall is the people.', 'Hrafn laughs, a great hoarse bark. "Stay! Then we shall have a feast every night, and no whale, and a spite for the winter that will keep us warm. Stranger, you are a fool. Kjolvik loves you for it."', 'flag:@feast_stay;loot:major'),
      ] }),
  ],
  outro: 'The last feast of Kjolvik was held under a sky of green fire. Whether the ships sailed at dawn or the hearth burned on, the Migration Song was sung that night to its four-hundredth verse.',
  rewards: { gold: 1800, loot: 'legendary', items: [{ item: 'skycraft:ancient_nord_greatsword', name: 'Hrafn\'s Last Oath', enchant: { sharpness: 5, smite: 3, unbreaking: 3 } }] },
});

f.q({
  id: 'the_unsung_verse', title: 'The Unsung Verse', region: 'atmora/frozen_forest', start: 'npc:@rolla', requires: ['level:30'],
  offer: 'Your voice is as old as the ice.',
  pitch: 'The Migration Song has four hundred verses, stranger, and Rolla sings three hundred and sixty of them. The other forty were lost on the crossing. There is a stone at the Moot of the Five Hundred where the skalds of the first ships carved a record. If any verse survives, it is there. If Rolla sings them all, the Song may be finished, and she does not know what a finished song is for.',
  desc: 'Skald Rolla wants the lost verses of the Migration Song, said to be carved on a stone at the Moot of the Five Hundred.',
  objectives: [
    o.go('moot_of_the_five_hundred', 'Travel to the Moot of the Five Hundred'),
    o.clear('moot_of_the_five_hundred', 'Drive off the frost-drakes that roost on the moot-stone',
      [mob('skycraft:ice_wraith', 3, 'Moot-Wraith'), mob('skycraft:frost_giant', 1, 'Stone-Guard Giant')]),
    o.retrieve('moot_of_the_five_hundred', 'Take a rubbing of the carved verse', '@migration_verse'),
    o.deliver('@rolla', '@migration_verse', 'Bring the rubbing to Rolla',
      'Here are the lost verses. There are forty. They tell of the first ships, and of a country that was behind them.',
      'Rolla reads, lips moving. Her hands shake. "The last verse. \'And when the song is done, the singer shall go home.\' Stranger, I do not know where home is. It is not here. It is not Skyrim. It is... the song. It was always the song. Shall I finish it, and see what it is for? Or let it run on, and never know?"',
      { choices: [
        choice('Sing it to the end. A song is for finishing.', 'Rolla straightens. Her voice rises, vast and ancient, and the walls of the Elder Longhall hum. When it ends there is a silence that is not empty. It is rest. "Thank you. The song is done. The singer is home."', 'flag:@verse_finished'),
        choice('Leave the last verse unsung. Keep the song alive.', 'Rolla closes her eyes and smiles. "A song that is never finished is a song that is never lost. Very well. I shall go on, at three hundred and ninety-nine, and the last verse shall be yours."', 'flag:@verse_kept;loot:major'),
      ] }),
  ],
  outro: 'The Migration Song has its forty lost verses back. Whether the singer sang the last, or kept it, the hall of Kjolvik has never been so quiet, nor so full.',
  rewards: { gold: 1400, loot: 'major' },
});

f.q({
  id: 'saarviks_barnacled_map', title: 'The Barnacled Map', region: 'atmora/frozen_forest', start: 'npc:@hjoldis', requires: ['level:30'],
  offer: 'Your map has a very large blank.',
  pitch: 'It does, and Hjoldis would like it filled. A captain of the old ships, Saarvik, left a chart under the hull of his wreck at Saarvik Landing. A barnacled thing, in oilskin. It records a coast no Nord has seen since the First Era. Hjoldis does not ask for the treasure it may lead to. She only wants the map, intact, and your word that you will not use it before she does. The ice is cold, the wind is vile, and the ships are very old.',
  desc: 'Factor Hjoldis of Dawnstar wants a captain\'s chart from under the hull of a wreck at Saarvik Landing on the Atmoran coast.',
  objectives: [
    o.go('saarvik_landing', 'Sail to Saarvik Landing on the Atmoran coast'),
    o.clear('saarvik_landing', 'Clear the draugr crew that still guard the wreck',
      [mob('skycraft:draugr', 4, 'Wreck-Crewman'), mob('skycraft:draugr_deathlord', 1, 'First Mate')], { placement: 'structure' }),
    o.retrieve('saarvik_landing', 'Take the barnacled chart from under the hull', '@barnacled_chart', { placement: 'structure' }),
    o.talk('@hjoldis', 'Bring the chart back to Hjoldis',
      'The chart was where you said. It shows a coast I do not know, with a mark in a red that is not ink.',
      'Hjoldis spreads the chart and for a moment says nothing at all. "A coast. A harbour. A mountain with a name. Stranger, there is a country on this map that no one has named since the world was young. And at the mark in red there is a word: HOME. Shall I sail for it, or shall I hang it on my wall, and let it keep being a mystery?"',
      { choices: [
        choice('Sail for it. A map is for following.', 'Hjoldis laughs aloud. "Then we sail. Hjoldis has not been out of Dawnstar in thirty years. She will need a coat. She will need two."', 'flag:@map_sailed'),
        choice('Hang it. Let the mystery be the reward.', 'Hjoldis smiles slowly. "Wise. A map you do not follow is a map you can keep. It will hang above my desk, and I will look at it every morning, and be very happy."', 'flag:@map_hung;loot:major'),
      ] }),
  ],
  outro: 'A chart from the oldest ship in the world hangs in a counting-house in Dawnstar, or leads a ship north. Either way it has finally been read.',
  rewards: { gold: 1500, loot: 'major' },
});

f.q({
  id: 'the_siege_that_never_ended', title: 'The Siege That Never Ended', region: 'atmora/frozen_forest', start: 'npc:@grim', requires: ['level:30'],
  offer: 'Your eyes are on the south.',
  pitch: 'The fort, stranger. Skjaldborg. A shield-wall of stone with a siege-ring of ghosts around it. A thousand years ago an enemy came and left, and the fort has been under siege since, because nobody told the garrison. They man the walls. They shoot arrows at nothing. They have never run out of arrows. Grim has hunted past it a hundred times. He wishes someone would go and tell them the war is over.',
  desc: 'The shield-fort of Skjaldborg is still under siege by an enemy that left a thousand years ago. Hunter Grim wants someone to tell the garrison it is over.',
  objectives: [
    o.go('skjaldborg', 'Approach the walls of Skjaldborg under a flag of truce'),
    o.kill('skjaldborg', 'Fight through the ghostly garrison to the gate-captain',
      [{ boss_id: '@ice_warden' }, mob('skycraft:draugr', 4, 'Wall-Sentinel')], { placement: 'structure' }),
    o.talk('@grim', 'Tell Grim the war at Skjaldborg is over',
      'The garrison would not hear it. I told their captain. The enemy left a thousand years ago. He said: then who are we guarding?',
      'Grim\'s mouth works. "Then who are we guarding. Stranger, that is a good question. Maybe the one who holds the gate is only a gate. Maybe there is nothing behind it. Maybe the fort is the only one left in the world who remembers there was a war. Grim does not know. But he thinks, tonight, the arrows will stop."'),
  ],
  outro: 'The arrows from the walls of Skjaldborg stopped that night. The garrison stands to this day on the ramparts, looking south, and no one knows if they are waiting or resting.',
  rewards: { gold: 1100, loot: 'major' },
});

// ================================================================== AKAVIR: Tiger Plains and Tang Mo
f.npc('hiro', 'Master Hiro of the Tiger Shrine', 'mage', 'tiger_plains_village', 4, 4,
  'Hiro teaches blades by never touching one. He says a sword is a letter, and a swordsman is a person who has learned to read. His students are very quiet, and extremely well-read.');
f.npc('mei', 'Lady Mei the Weaver', 'merchant', 'tiger_plains_village', -6, 5,
  'Mei weaves cloth in white and vermillion that is sold as far as Tamriel, and no one there has ever worn it correctly. She has a pleasant, patient way of correcting strangers by smiling at them.', true);
f.npc('ghost_monk', 'The Ghost Tiger-Monk', 'priest', 'tiger_plains_village', 8, -3,
  'The tiger-monk stands at the shrine gate and does not move, and does not breathe, and does not speak. Villagers say he has been there for four hundred years. They greet him anyway. It is only polite.');
f.npc('nine_kettles', 'Host Aoi of the Nine Kettles', 'innkeeper', 'tiger_plains_village', -2, -7,
  'The Teahouse of the Nine Kettles has, as promised, nine kettles. Each one boils a different tea, each one has a different name and each one, Aoi says, has a mood. She does not take sides in their disputes.', true);
f.npc('wen', 'Captain Wen of the Silent Junk', 'adventurer', 'tang_mo_harbor_village', 4, 4,
  'Wen sails a dragon-prowed junk and a very small grudge. The grudge is against a ship. The ship has not been seen in nine years. He thinks about it every morning, and says this is the secret of his good health.');
f.npc('li', 'Tea-Master Li', 'apothecary', 'tang_mo_harbor_village', -5, 5,
  'Li serves tea to sailors and says what each one needs to hear. Most of it is tea. Some of it is not. She has a very good memory and a very short temper for lies.', true);
f.npc('jin', 'Pirate Jin the Wary', 'thief', 'tang_mo_harbor_village', 8, -4,
  'Jin was a pirate once, and now keeps a stall of knotted cords. He says each cord is a good memory he has bottled, and sells them at a fair price. He does not say what he does with the bad ones.');

f.relic('tiger_seal', 'Seal of the Third Trial', 'A palm-sized jade seal carved with a tiger asleep, paw over its nose. When held to the light, a very small second tiger can be seen in its depths, which is awake, and watching the first.', 26);
f.relic('lantern_logbook', 'The Silent Junk\'s Logbook', 'A salt-stiff logbook written in the careful characters of an Akaviri captain. The last entry is a single line: \'The lantern on the mast is lit. No one lit it. We are not afraid.\'', 11);
f.boss('tiger_guardian', { name: 'The Guardian of the Third Trial', type: 'skycraft:tsaesci', hp: 4.0, dmg: 1.7, level: 38,
  weapon: 'skycraft:akaviri_sword', gear: { head: 'skycraft:akaviri_helmet', chest: 'skycraft:akaviri_chestplate' }, drop: 'loot:major', glow: true });
f.boss('mast_lantern', { name: 'The Lantern-That-Burns', type: 'skycraft:wispmother', hp: 4.0, dmg: 1.6, level: 36, drop: 'loot:major', glow: true });
f.scene('aka_terrace', [
  ['narrator', 'Terrace after terrace of rice-fields climbs the mountain, each one watered by the one above it. At the top stands a vermilion gate, and beyond it, a pagoda with a roof like a lifted eyebrow.'],
  ['@hiro', 'Three trials: the climb, the cut and the choice. The tiger sleeps at the top. If it wakes for you, it is because you are worth waking. If it does not, you are only a person with a sword.'],
]);
f.scene('aka_junk', [
  ['narrator', 'The junk drifts into the harbour at dawn, sails furled, decks bare. Every hatch is shut. On the mast, a single lantern burns with a pale green flame, and its light does not fall on the water.'],
  ['@wen', 'That lantern was not on the mast when she left. It is not any lantern I have ever seen. Do not look at it more than you must.'],
]);

f.q({
  id: 'the_tigers_test', title: 'The Tiger\'s Test', region: 'akavir/ka_po_tun', start: 'npc:@hiro', requires: ['level:34'],
  offer: 'You have a way of standing that is almost correct.',
  pitch: 'Almost. Hiro does not say that lightly. Stranger, the shrine on the mountain has been asleep for sixty years. A tiger is asleep inside it. Every student of the village has tried the three trials and failed the third. Hiro has no student left. If you wish to try, he will not stop you. If you fail, he will carry you down. If you succeed, he will ask a very small question. Be ready to answer it.',
  desc: 'Master Hiro of Tiger Plains invites a stranger to attempt the three trials of the Tiger Shrine, which no student has passed in sixty years.',
  objectives: [
    o.go('tiger_plains_village', 'Climb the terraces to the vermilion gate', { actions: 'scene:@aka_terrace' }),
    o.go('hall_of_the_dragon_tigers', 'Pass the first trial, the climb, at the Hall of the Dragon Tigers'),
    o.clear('hall_of_the_dragon_tigers', 'Pass the second trial, the cut, against the hall\'s defenders',
      [mob('skycraft:tsaesci', 3, 'Trial-Swordsman'), mob('skycraft:kamal', 1, 'Trial-Brute')], { placement: 'structure' }),
    o.kill('hall_of_the_dragon_tigers', 'Pass the third trial, the choice, against the Guardian',
      [{ boss_id: '@tiger_guardian' }], { placement: 'structure', actions: 'relic:@tiger_seal' }),
    o.talk('@hiro', 'Bring the seal to Hiro and answer his question',
      'The tiger woke. It asked nothing. It only looked. Then it closed its eyes again. Here is the seal.',
      'Hiro lays the seal on the low table and for the first time in your acquaintance, he bows. "It woke for you. In sixty years it has woken for no one. The question is small. What will you do with what the tiger gave you? Teach, or keep?"',
      { choices: [
        choice('Teach. A tiger is for the village.', 'Hiro closes his eyes. "Then it is the village\'s. You shall have a class of one, and a tiger for every student after. Thank you. The shrine has not been so awake in sixty years."', 'flag:@tiger_taught'),
        choice('Keep it. Some things are not meant to be shared.', 'Hiro nods slowly. "A hard answer. A fair one. The tiger is yours. It will wait for you wherever you go. And Hiro will teach his own class with his own blade."', 'flag:@tiger_kept;loot:legendary'),
      ] }),
  ],
  outro: 'A tiger that had slept for sixty years woke, looked, and slept again. The village of Tiger Plains has a seal, a student and a very careful teacher.',
  rewards: { gold: 2000, loot: 'legendary', items: [{ item: 'skycraft:akaviri_sword', name: 'Letter of the Waking Tiger', enchant: { sharpness: 5, looting: 3, unbreaking: 3 } }] },
});

f.q({
  id: 'ladys_unwearable_cloth', title: 'The Cloth That Cannot Be Worn', region: 'akavir/ka_po_tun', start: 'npc:@mei', requires: ['level:30'],
  offer: 'You are looking at my cloth as if it were a riddle.',
  pitch: 'It is, stranger. Mei weaves cloth that can be worn only one way, and in three hundred years, no foreigner has worn it correctly. She has begun to wonder if the cloth wishes to be worn differently. She has a bolt of white that she cannot sell, because it will not hold a fold. It refuses. She would like you to take it to the old weaver at the black lacquer pagoda, who may know why.',
  desc: 'Lady Mei has a bolt of white cloth that will not hold a fold. She asks a stranger to carry it to an old weaver at the Black Lacquer Pagoda.',
  objectives: [
    o.go('black_lacquer_pagoda', 'Carry the bolt to the old weaver at the Black Lacquer Pagoda'),
    o.clear('black_lacquer_pagoda', 'Drive off the Tsaesci who have taken the weaver\'s loft',
      [mob('skycraft:tsaesci', 3, 'Loft-Guard')]),
    o.talk('@mei', 'Return to Mei and tell her what the weaver said',
      'The weaver said: the cloth is not wrong. It was woven for a person who has not yet come. It will not fold until it is worn by them.',
      'Mei sits very still. "A person who has not yet come. Three hundred years. And I have been trying to sell it to people who already have. Stranger, I shall hang it in the window, and let it wait. If it was meant for you, you will know. Try it on. It will not hold a fold. But it will not fall off, either."'),
  ],
  outro: 'A bolt of white cloth hangs in a window in Tiger Plains, unfolded and unworn. The weaver at the pagoda has begun to expect a visitor.',
  rewards: { gold: 700, loot: 'standard' },
});

f.q({
  id: 'the_silent_junk', title: 'The Silent Junk', region: 'akavir/tang_mo', start: 'npc:@wen', requires: ['level:34'],
  offer: 'You are looking at the harbour as if it owed you something.',
  pitch: 'A junk drifted in at dawn, stranger. Not mine. Not any I know. Every hatch shut, every sail furled, and a lantern on the mast burning green. Nobody will go aboard. Nobody will go within a rope\'s length. A junk that sails itself home is either a miracle or a mistake, and in Tang Mo we are very fond of miracles and very wary of mistakes. Go aboard. Read the log. Tell me whose ship she is.',
  desc: 'A silent junk drifted into Tang Mo harbour at dawn with a pale green lantern burning on the mast. Captain Wen wants a stranger to board and read its log.',
  objectives: [
    o.go('tang_mo_harbor_village', 'Board the silent junk at the quay', { actions: 'scene:@aka_junk;relic:@lantern_logbook' }),
    o.clear('tang_mo_watch', 'Search the signal-fire at Tang Mo Watch, where the junk\'s light first appeared',
      [mob('minecraft:drowned', 3, 'Junk-Drowned'), mob('skycraft:sea_serpent', 1, 'Mast-Coil')], { placement: 'structure' }),
    o.kill('tang_mo_watch', 'Put out the Lantern-That-Burns',
      [{ boss_id: '@mast_lantern' }], { placement: 'structure' }),
    o.deliver('@wen', '@lantern_logbook', 'Bring the log to Wen',
      'The junk was the Nine Winds, lost nine years ago. The lantern on her mast was the keeper\'s last light. The keeper is at rest. Here is her log.',
      'Wen takes the log, and his hands are very still. "The Nine Winds. My grudge. Nine years. I thought she had been taken by a rival, and instead she was lost. Stranger, I have been angry at the wrong person for nine years. Shall I sink her, and be done? Or sail her home, and see her afloat?"',
      { choices: [
        choice('Sail her home. A ship is for the harbour.', 'Wen draws a long breath. "Then I sail her. And I shall be very polite to every ship I meet from now on, in case it is one that came home late."', 'flag:@junk_sailed'),
        choice('Sink her. Let the keeper rest beneath the waves.', 'Wen closes his eyes. "A good answer. A cold one. She will rest. And I will put a lantern on my own mast, lit by my own hand, so that the sea knows who I am."', 'flag:@junk_sunk;loot:major'),
      ] }),
  ],
  outro: 'The Nine Winds came home at last, or went down with her keeper. A very old grudge in Tang Mo lay down and was quiet.',
  rewards: { gold: 1700, loot: 'major' },
});

f.q({
  id: 'the_monkey_tree_wager', title: 'A Wager with a Thousand Monkeys', region: 'akavir/tang_mo', start: 'npc:@jin', requires: ['level:34'],
  offer: 'You have a cord to sell.',
  pitch: 'Not to sell, stranger. To wager. Jin has been trying for ten years to steal a single fruit from the Thousand Monkey Tree. He has failed every time. The monkeys are clever. They are clever in a way that a man who calls himself Wary cannot match. Go to the tree. Take a fruit. Do not hurt a monkey. If you do, Jin will pay you double. If you do not, Jin will pay you half, and the fruit.',
  desc: 'Pirate Jin has failed for ten years to steal a fruit from the Thousand Monkey Tree. He wagers that a stranger can do it without harming a monkey.',
  objectives: [
    o.go('thousand_monkey_tree', 'Reach the Thousand Monkey Tree'),
    o.retrieveItem('thousand_monkey_tree', 'Take a single fruit from the tree without harming a monkey', 'minecraft:apple', 1),
    o.talk('@jin', 'Bring the fruit to Jin',
      'The fruit, whole. A monkey handed it to me. I do not know why.',
      'Jin stares at the fruit for a very long time. "Handed it. Handed. Ten years I have been stealing, and they would have given it to a stranger for the asking? Stranger, I am an old, wary fool. Take it. It is yours. It is a good fruit, and it is the first one I ever wanted that I did not try to hold."'),
  ],
  outro: 'A fruit from the Thousand Monkey Tree was brought out of the branches by a monkey\'s own hand. Jin has stopped stealing. He now sells cords of kindness instead, at a very small price.',
  rewards: { gold: 600, loot: 'standard' },
});

// ================================================================== YOKUDA: Hunding's Dojo
f.npc('sahar', 'Master Sahar of the Sword Courtyard', 'mage', 'hundings_dojo', 4, 4,
  'Sahar teaches the Way of the Sword in a sunken courtyard of sand, with no walls, no weapons rack and no rules. He says the first rule is that you may walk away. The second is that you never will.');
f.npc('jai', 'Student Jai', 'adventurer', 'hundings_dojo', -5, 5,
  'Jai is the finest of Sahar\'s students and the angriest. He has not won a bout against his master in nine years. He does not intend to lose the tenth.');
f.npc('witness', 'The Ghost Who Witnesses', 'priest', 'hundings_dojo', 7, -4,
  'A pale figure sits on the highest bench and has never been seen to leave it. Students say the ghost keeps the score. The ghost says nothing at all. Nobody has ever won an argument with a ghost who is simply watching.');
f.npc('anwar', 'Scholar-Captain Anwar', 'scholar', 'sentinel', 8, 6,
  'Anwar has spent forty years in Sentinel\'s archives chasing a single rumour: that the Yokudan Way of the Sword is still taught, on an island no one has charted. He is eighty. He is very impatient.');

f.relic('sahars_stance', 'The Fourth Stance, in Chalk', 'A flat stone with a chalk diagram of a swordsman\'s feet, drawn by the master himself. The fourth stance has never been taught. It is, he says, the one a student invents.', 11);
f.boss('jai_boss', { name: 'Jai, the Angriest Student', type: 'skycraft:bandit_chief', hp: 3.6, dmg: 1.6, level: 36,
  weapon: 'skycraft:yokudan_sword', gear: { head: 'skycraft:ancient_nord_helmet' }, drop: 'loot:major', glow: false });
f.scene('yok_court', [
  ['narrator', 'The courtyard is a bowl of white sand, ringed with columns and tiered benches, with a single bright pavilion at the north. Nobody is practising. Everyone is waiting.'],
  ['@sahar', 'The lesson is not the sword. The lesson is the stillness before it. Do not lift your blade until you can say what it is for. Many have drawn. A few have known.'],
]);

f.q({
  id: 'the_final_lesson', title: 'The Final Lesson', region: 'yokuda/yokudan_desert', start: 'npc:@sahar', requires: ['level:36'],
  offer: 'You have the stillness of someone who has had to wait.',
  pitch: 'A little. Stranger, the Way is passed from master to student, and from student to the next. Sahar has taught for sixty years. Jai is his best, and the angriest, and the Way will die with them unless one of them learns what the other knows. Sahar is old. He wishes to be beaten, and to know it was fair. He cannot ask Jai. Jai will take it as an insult. You are a stranger. Spar with Jai. Lose, if you must. Win, if you can. But make him use the fourth stance.',
  desc: 'Master Sahar wishes to be beaten fairly by his best student, Jai, but cannot ask. He wants a stranger to spar with Jai and draw out the fourth stance.',
  objectives: [
    o.go('hundings_dojo', 'Enter the Sword Courtyard', { actions: 'scene:@yok_court' }),
    o.talk('@sahar', 'Receive the three stances from Sahar',
      'Teach me. I will carry your Way to the student who needs it.',
      'Sahar sets down his cup. "The first stance is waiting. The second is cutting. The third is listening, which most never learn. The fourth is the one you invent. Take this chalk. When you spar with Jai, he will use all four. Do not be afraid of his rage. It is the only honest thing about him."',
      { actions: 'relic:@sahars_stance' }),
    o.kill('hundings_dojo', 'Spar with Jai until he draws the fourth stance',
      [{ boss_id: '@jai_boss' }], { actions: 'flag:@jai_sparred' }),
    o.talk('@sahar', 'Tell Sahar what the fourth stance was',
      'Jai drew the fourth. It was not the stance of any master I know. It was his own. He won. Then he sat down, and wept.',
      'Sahar is silent for a very long time. Then, slowly, he bows to you, and then to the empty bench where the ghost sits, and then, at last, to the sand. "Then the Way is passed. Not to Jai. Not to you. To the sand. Stranger, it will be there for the next student. Shall I stay, and teach, or go with the ghost, and rest?"',
      { choices: [
        choice('Stay. A teacher is never finished.', 'Sahar laughs, a very small, very old laugh. "Never finished. No. Very well. I shall teach until the sand tells me otherwise. And Jai will teach beside me. And the courtyard will have two masters, and neither will be sure which is the student."', 'flag:@dojo_stay'),
        choice('Rest. The Way has a new keeper.', 'Sahar nods and closes his eyes. The pale figure on the bench rises, and for the first time, speaks one word: \'Good.\' Sahar takes his hand. They sit together on the highest bench, and are, quite gently, no longer there.', 'flag:@dojo_rest;loot:legendary'),
      ] }),
  ],
  outro: 'The sword-courtyard of Yokuda has two masters now, or none. The sand bears no print of the fourth stance. It will be invented again.',
  rewards: { gold: 2200, loot: 'legendary', items: [{ item: 'skycraft:yokudan_sword', name: 'Stillness Before the Cut', enchant: { sharpness: 5, sweeping: 3, unbreaking: 3 } }] },
});

f.q({
  id: 'the_ansei_hall_remembered', title: 'What the Hall of the Ansei Remembers', region: 'yokuda/yokudan_desert', start: 'npc:@anwar', requires: ['level:34'],
  offer: 'You are looking for a very old thing.',
  pitch: 'Forty years, stranger. A rumour in an archive, and a shred of a chart. The Hall of the Ansei, on an island in a desert sea. It is said to hold the last true record of the Sword-Singers. Anwar is too old for the voyage. He is not too old for the questions. Go. See what is there. Tell him whether the stories are true. If they are, he will die happy. If they are not, he will die informed.',
  desc: 'Scholar-Captain Anwar of Sentinel wants someone to reach the Hall of the Ansei in Yokuda and report whether the rumours of the Sword-Singers\' record are true.',
  objectives: [
    o.go('hall_of_the_ansei', 'Reach the Hall of the Ansei across the Yokudan desert'),
    o.clear('hall_of_the_ansei', 'Drive off the sand-wraiths that guard the hall',
      [mob('skycraft:sand_serpent', 2, 'Hall-Serpent'), mob('minecraft:husk', 3, 'Sand-Wraith')], { placement: 'structure' }),
    o.talk('@anwar', 'Tell Anwar what the Hall holds',
      'The Hall holds a library of one scroll. It is blank. Beneath it is a note: \'The Sword-Singers took the record with them. Ask the ones who still sing.\'',
      'Anwar takes it with a shaking hand. He is old, so very old. Then he laughs, the laugh of a man who has been unexpectedly released. "Blank! A whole life for a blank scroll. Stranger, it is the best answer I could have had. The Singers still sing. Somewhere. Anwar will put down his pen. For the first time in forty years, he will sit in the sun."'),
  ],
  outro: 'A scholar who spent forty years chasing a rumour has put down his pen. The Sword-Singers sing on, somewhere past the edge of every chart.',
  rewards: { gold: 1200, loot: 'major' },
});

// ================================================================== PYANDONEA: the Fleet Anchorage
f.npc('nehra', 'Admiral Nehra of the Coral Flagship', 'steward', 'pyandonean_fleet_anchorage', 4, 4,
  'Nehra commands the fleet from a throne of fused coral and says nothing that has not already been decided. Her officers are terrified of her. Her cabin-boy is not, and has been told never to say so.', true);
f.npc('ilu', 'Shell-Smith Ilu', 'blacksmith', 'pyandonean_fleet_anchorage', -5, 5,
  'Ilu grinds shells into armour and ships\' fittings in a workshop that smells of rain and old teeth. She is the only artisan the Admiral has never once overruled, and she is very careful to keep it that way.', true);
f.npc('captive', 'The Captive of the Brine Tank', 'citizen', 'pyandonean_fleet_anchorage', 8, -4,
  'A thin man in a ragged Altmer cloak is held in a tank of brine, up to his chin. He says he is a scholar. He says it quickly, and often, and with great sincerity.');
f.npc('caelir', 'Coast-Watcher Caelir', 'hunter', 'firsthold', -8, 6,
  'Caelir has watched the Maormer fleet from a rock in the strait for twelve years, and has learned their lamps. She says she knows each ship by its smell. She does not say which smells she prefers.', true);

f.relic('admirals_key', 'Key of the Coral Flagship', 'A key of curling white coral, as long as a hand, that will open any lock on the flagship and, the Admiral says, any lock in the sea. It is cold, and very faintly wet, at all times.', 26);
f.boss('flagship_warden', { name: 'Warden-of-the-Hold Ssiruun', type: 'skycraft:dremora', hp: 3.8, dmg: 1.6, level: 38,
  weapon: 'skycraft:maormer_sword', gear: { head: 'skycraft:maormer_helmet', chest: 'skycraft:maormer_chestplate' }, drop: 'loot:major', glow: true });
f.scene('pya_raft', [
  ['narrator', 'A city of shell-hulled ships lashed together bobs on a calm black sea, each hull a different shade of pale. Lanterns of green glass hang from every mast. Somewhere below, the sound of a very large, very slow chain.'],
  ['@caelir', 'Do not breathe loudly. Do not step on a line you cannot see the end of. The Maormer are polite to a degree that frightens me. They will thank you for a mistake, and then they will keep you.'],
]);

f.q({
  id: 'the_flagship', title: 'The Flagship', region: 'pyandonea/maormer_isles', start: 'npc:@caelir', requires: ['level:36'],
  offer: 'You have been looking at the sea for some time.',
  pitch: 'Twelve years, and tonight is the first night the Admiral\'s fleet has been alone. Her flagship has a hold. In the hold, in a brine tank, there is a captive. An Altmer scholar, they say, who knew too much about the Queen\'s own island. Caelir wants him out. Not for the Dominion. For her own sake. She is tired of knowing what she knows, and doing nothing. A small boat. A dark night. A key, if you can find one. The Admiral sleeps lightly.',
  desc: 'Coast-Watcher Caelir asks a stranger to infiltrate the Maormer flagship at night and free an Altmer scholar held in a brine tank.',
  objectives: [
    o.go('pyandonean_fleet_anchorage', 'Row to the raft-city by night', { actions: 'scene:@pya_raft' }),
    o.cond('night', 'Wait until the fleet is dark and the watch changes', 'pyandonean_fleet_anchorage'),
    o.kill('pyandonean_fleet_anchorage', 'Slip past the Warden-of-the-Hold and take the coral key',
      [{ boss_id: '@flagship_warden' }, mob('skycraft:bandit', 2, 'Hold-Guard')], { actions: 'relic:@admirals_key' }),
    o.escort('@captive', 'firsthold', 'Row the freed scholar back to Firsthold before dawn'),
    o.talk('@caelir', 'Tell Caelir the captive is free',
      'The scholar is free. He talked the whole way. He knows where the Queen\'s hidden island is.',
      'Caelir listens, and for a long moment does not move. "The Queen\'s hidden island. He knows where it is. Stranger, that is a dangerous thing. The Dominion would pay for it. The Maormer would kill for it. And Caelir would very much like to keep him alive until he forgets. What shall we do?"',
      { choices: [
        choice('Tell the Dominion. The island should be known.', 'Caelir nods slowly. "Then it is known. A thing that is known can be defended, or conquered. It is out of our hands. Thank you for the honesty."', 'flag:@flagship_dominion'),
        choice('Keep it between you. A secret kept is a life saved.', 'Caelir breathes out. "A secret. Yes. Between us two, and one scholar who forgets. It will be the loneliest knowledge in Tamriel. But it will be ours."', 'flag:@flagship_secret;loot:major'),
      ] }),
  ],
  outro: 'A scholar was carried out of the Maormer fleet in a rowing boat, talking all the way. Whether the Queen\'s island is known to the Dominion or only to two, it is no longer safe.',
  rewards: { gold: 1900, loot: 'major' },
});

f.q({
  id: 'the_shell_that_listens', title: 'The Shell That Listens', region: 'pyandonea/maormer_isles', start: 'npc:@ilu', requires: ['level:34'],
  offer: 'Your shells are very quiet.',
  pitch: 'They are, stranger. They should not be. A good shell hums the sea it was grown in. The ones in Ilu\'s workshop have been quiet for a week. All of them. Not a note. She believes someone is listening to them, and the listening has stopped the singing. She would very much like to know who, and why, and whether to be flattered.',
  desc: 'All the shells in Ilu\'s workshop have gone silent. She believes someone is eavesdropping on them and wants the listener found.',
  objectives: [
    o.go('serpent_pens', 'Follow the silence to the Serpent Pens'),
    o.clear('serpent_pens', 'Drive off the pen-keepers who are using the shells as ears',
      [mob('skycraft:sea_serpent', 2, 'Pen-Serpent'), mob('skycraft:bandit', 2, 'Shell-Listener')], { placement: 'structure' }),
    o.talk('@ilu', 'Tell Ilu who was listening',
      'The pen-keepers were listening for the Admiral\'s orders. The shells gave them away. They are quiet no longer.',
      'Ilu pulls a shell from the rack and holds it to her ear. After a moment, her whole face changes. "There. The sea. It was only waiting for permission. Stranger, I shall hang a shell for you in the workshop. It will hum a different note from all the others. It will hum the sound of someone who listened to the listeners."'),
  ],
  outro: 'The shells of the fleet hum again. In a rack of silent white, one sings a single, odd note, and Ilu says it is for you.',
  rewards: { gold: 900, loot: 'standard' },
});

// ================================================================== THRAS: the Coral Kingdom
f.npc('ooma', 'Plague-Diver Ooma', 'adventurer', 'sload_spawning_pools', 4, 4,
  'Ooma dives the Sload pools for a living, and is the only person in Thras who will tell you it is a living. She wears a mask of waxed leather and a very large amount of gold. The gold, she says, is not for decoration. It is for the debt she owes the pools.', true);
f.relic('reef_heart', 'Heart of the Reef', 'A fist-sized lump of living coral that pulses, slowly, in bright pink and poison green. When held to the ear, it sounds like a drowned bell. It is warm. It is, very faintly, afraid.', 20);
f.boss('reef_tyrant', { name: 'The Reef-Tyrant', type: 'skycraft:swamp_leviathan', hp: 4.6, dmg: 1.8, level: 40, drop: 'loot:major', glow: true });
f.q({
  id: 'the_heart_of_the_reef', title: 'The Heart of the Reef', region: 'thras/coral_kingdom', start: 'npc:@ooma', requires: ['level:38'],
  offer: 'You have the look of someone about to dive.',
  pitch: 'The Plague, stranger. It started in the Tower. The coral is infected, pink and poison-green, and it spreads a handspan a day. Ooma has dived to the heart of the reef three times, and three times she came up without it. The heart is the only thing that can be carried out. Without it, the Plague spreads until Thras is a sea of pulsing polyps. With it, a cure might be made. Dive. Take it. Come back alive, and Ooma will tell you what she owes the pools.',
  desc: 'The Plague of Thras spreads from the Coral Tower. Plague-Diver Ooma wants a stranger to dive to the heart of the reef and carry it out.',
  objectives: [
    o.go('coral_tower_of_thras', 'Reach the Coral Tower of Thras'),
    o.clear('coral_tower_of_thras', 'Fight through the infected coral to the heart chamber',
      [mob('minecraft:drowned', 4, 'Reef-Drowned'), mob('skycraft:sea_serpent', 1, 'Plague-Serpent')], { placement: 'structure' }),
    o.kill('coral_tower_of_thras', 'Defeat the Reef-Tyrant that guards the heart',
      [{ boss_id: '@reef_tyrant' }], { placement: 'structure', actions: 'relic:@reef_heart' }),
    o.deliver('@ooma', '@reef_heart', 'Bring the heart to Ooma',
      'The heart. It pulses. It is afraid. It does not wish to be carried further.',
      'Ooma presses it to her chest, and for a moment, she is quiet. "Afraid. Of course. A heart that has been in a tower for a thousand years does not wish to be carried out. Stranger, shall I make the cure, and kill it? Or shall I return it, and let the Plague take what it will?"',
      { choices: [
        choice('Make the cure. The living should come first.', 'Ooma bows her head. "Then it will be done. It will be painful. It will be necessary. And I shall say a word for the heart, in the language of the pools. Thank you."', 'flag:@reef_cured'),
        choice('Return it. Let the reef take its own course.', 'Ooma nods slowly. "A hard answer. A kind one. The Plague will go on, and the heart will rest. Perhaps that is what the pools wanted all along."', 'flag:@reef_returned;loot:major'),
      ] }),
  ],
  outro: 'The heart of the reef was carried out of the Coral Tower, and whether it was used or returned, the polyps of Thras have turned, a little, toward the light.',
  rewards: { gold: 2100, loot: 'legendary' },
});

// ================================================================== lore for the foreign settlements
const lore = {};
lore.kjolvik = {
  hook: 'The last Atmoran hold on an ice shelf: ten long halls half-buried in drifts, whale-bone arches, a stone-lined harbour where the ice cracks, and the aurora overhead. Older and harsher than any Nord town.',
  rumour: 'Kjolvik has never been conquered. It has, in nine winters, been very nearly forgotten, which a Nord will tell you is far worse.',
  notes: [
    { id: 'hearth_log', kind: 'journal', title: 'Log of the Last Hearth', author: 'Ysa', at: 'chest',
      pages: ['#Ninth winter\nWe burn the driftwood, then the hull-planks, then the benches. I have kept the fire for nine hundred nights. There is a rule: the hearth must never be out when the sun is not up. I have broken it twice, and been forgiven both times, by the hearth.',
        'We had seal on the solstice. The skald sang. The jarl wept, and said it was the smoke. There is no smoke in the Last Hearth. We have a very good chimney. We have always had it.'] },
  ],
};
lore.jylkurfyk = {
  hook: 'A fort of black basalt and whale-bone on the Atmoran coast, built by the first ships\' captains and still held, in the long dark, by a garrison no one has relieved.',
  rumour: 'The fort of Jylkurfyk has never been given a name by its builders. They called it only The Door, and left, and did not say which side of it they stood on.',
  notes: [
    { id: 'garrison_roll', kind: 'orders', title: 'Roll of the Jylkurfyk Garrison', author: 'The Door-Captain', at: 'chest',
      pages: ['#GARRISON, THIS WINTER\nOne hundred and four shields, thirty-one spears, and nine lamps that burn without oil. We hold the Door. We do not know what is behind it. The first captain\'s order was to hold it. He did not say against what.',
        'We have held it for nine hundred winters. It has never been opened from either side. In the spring the knock comes, very soft, and in the summer it is gone. We do not answer. That is the order.'] },
  ],
};
lore.moot_of_the_five_hundred = {
  hook: 'A ring of tall standing stones on the Atmoran ice where the five hundred skalds of the first ships met, once, to decide whether to cross the sea.',
  rumour: 'The Moot stones are said to hold their last argument still. On a still night you can hear the vote. It was close.',
  notes: [
    { id: 'moot_stone', kind: 'inscription', title: 'The Stone of the Vote', at: 'site',
      pages: ['^FIVE HUNDRED SPOKE. TWO HUNDRED AND FORTY-NINE SAID STAY.\n^TWO HUNDRED AND FIFTY-ONE SAID GO.\n^TWO SKALDS ABSTAINED, AND ARE NOT NAMED.',
        'Beneath, much later, in a very fine hand: \'The two who abstained went last, and wept the most, and never said which way they would have voted. It is the only secret the First Ships kept.\''] },
  ],
};
lore.tiger_plains_village = {
  hook: 'A mountain village of curved-roof pagodas on rice terraces, a vermilion shrine gate, bamboo and paper lanterns, the most alien place in the pack, with a roofline that is long, low and level.',
  rumour: 'The village of Tiger Plains does not lock its doors. It has no need. The tiger sees to that. It has been asleep for sixty years, and the thieves still check, very carefully, before they try.',
  notes: [
    { id: 'terrace_poem', kind: 'poem', title: 'Poem of the Terraces', author: 'Lady Mei', at: 'chest',
      pages: ['~The water falls from step to step,\n~each field a mirror, each a sky;\n~the farmer bows, and does not know\n~she plants a thousand moons in rows.',
        'Mei has copied it, she says, from the lid of a very old rice-bowl. The poet was a farmer. He never wrote anything else. He did not need to.'] },
  ],
};
lore.tang_mo_harbor_village = {
  hook: 'A harbour of lacquered junks and floating lantern-masts, houses raised on bamboo piles with curved roofs, a dragon-prowed ship at the quay and a tea-house for every tide.',
  rumour: 'Tang Mo has more lanterns than people. They are hung for the dead, for the lost and for those who have not yet been born. The harbour at night is the brightest sea in the world.',
  notes: [
    { id: 'tea_house_slate', kind: 'list', title: 'Slate of the Tea-House', author: 'Tea-Master Li', at: 'chest',
      pages: ['#THIS TIDE\nGreen: for the nervous. Black: for the grieving. White: for those who wish to be forgiven. Grey: for those who do not know. Red, which is not tea, and is not for sale, and is for the ones who have already decided.',
        'In the margin, in Li\'s impatient brush: \'The lantern on the mast is not a tea. I do not know what it is for. I have brewed all five colours at it and it has accepted none.\''] },
  ],
};
lore.adasooms_gate = {
  hook: 'A great iron gate in a mountain pass of Kamal, held by a garrison of Ka Po\' Tun veterans who have forgotten which side they were guarding.',
  rumour: 'Adasoom\'s Gate has never been breached. It has also never been opened, which a keen scholar will tell you is the same thing.',
  notes: [
    { id: 'gate_edict', kind: 'orders', title: 'Edict of the Gate', author: 'The Gate-General', at: 'chest',
      pages: ['#BY ORDER OF THE GATE\nNo one passes. No one is expected. No one has been sent. If a messenger is seen, he is to be asked politely for his name, and then, politely, to turn around.',
        'A later soldier has pencilled: \'We have been here eleven hundred years. I am not sure who is expecting us to still be here. I am not sure who has stopped. We are very good at it.\''] },
  ],
};
lore.tosh_rakas_palace = {
  hook: 'A palace of vermilion and black lacquer on a spur of the Ka Po\' Tun, its roofs curved like drawn bows, built by an emperor who was never seen to leave it.',
  rumour: 'The palace has nine hundred rooms, and the emperor used one. The rest were built for the people who he believed would come and did not.',
  notes: [
    { id: 'emperor_order', kind: 'notes', title: 'A Torn Imperial Decree', at: 'site',
      pages: ['#BY THE HAND THAT HOLDS THE HEAVENS\nLet every room be swept. Let every lamp be lit. Let every table be laid. The guests will come in their time, and I will not have it said that Ka Po\' Tun was caught unready.',
        'A servant has scrawled beneath: \'We have swept for eleven hundred years. The table is laid. Nobody has come. I am beginning to suspect that we were the guests.\''] },
  ],
};
lore.ka_po_tun_steppe_fort = {
  hook: 'A steppe fort of grey tile and black timber on the edge of the Ka Po\' Tun, held by a garrison that has marched out every spring for a thousand years and found nothing to fight.',
  rumour: 'The fort sends out a patrol every dawn. The patrol returns every dusk. In a thousand years, they have never met an enemy, and never once admitted to being disappointed.',
  notes: [
    { id: 'patrol_log', kind: 'report', title: 'Log of the Dawn Patrol', author: 'Patrol-Captain Hu', at: 'chest',
      pages: ['#THE DAWN PATROL\nOut at the first bell. Steppe: grass. Sky: sky. Enemy: none. Returned at the last bell. I have walked the same ten miles for forty years. I have begun to name the grasses. There is one, near the stream, that I call Patience.',
        'Today, at the stream, a horse stood. It was not ours. It was grey, and thin, and had no rider. It looked at me and did not run. I gave it water. In the morning it was gone, and the stream had a new bank.'] },
  ],
};
lore.black_lacquer_pagoda = {
  hook: 'A seven-tiered pagoda of black lacquer on a bluff of the Tsaesci coast, whose eaves are hung with bells that ring only when no one is near to hear.',
  rumour: 'The Black Lacquer Pagoda is said to have an eighth tier, above the roof, that is only visible at dusk, and which is used by someone who has never come down.',
  notes: [
    { id: 'bell_register', kind: 'ledger', title: 'Register of the Pagoda Bells', author: 'The Bell-Warden', at: 'chest',
      pages: ['#THE BELLS\nFirst tier: Patience, which rings at dawn. Second: Doubt, which rings at noon. Third: Resolve, which has not rung in a hundred years. Fourth through sixth: lost. Seventh: Return, which rings only for the one who returns.',
        'Pencilled below: \'The seventh rang last night. Nobody had returned. I went up with a lamp. The bell was still swinging. On the floor, a single wet footprint, small and bare, pointing inward.\''] },
  ],
};
lore.hundings_dojo = {
  hook: 'A sunken courtyard dojo on a Yokudan island, a bowl of white sand ringed with columns, tiers of benches and a single bright pavilion where the Way of the Sword is still, quietly, taught.',
  rumour: 'No one in Hunding\'s Dojo has ever been seen to eat. They are said to live on discipline and hot tea. The tea, at least, has been confirmed.',
  notes: [
    { id: 'dojo_rule', kind: 'inscription', title: 'The Rule of the Courtyard', at: 'site',
      pages: ['^THE FIRST RULE: YOU MAY WALK AWAY.\n^THE SECOND RULE: YOU NEVER WILL.\n^THE THIRD RULE IS FOR THE STUDENT TO INVENT.',
        'A student has added in the sand with a stick, which has been raked over and rewritten countless times: \'The third rule is not a rule. It is a question. I have been asking it for nine years. I think the answer is a sword. I think the answer is not.\''] },
  ],
};
lore.hall_of_the_ansei = {
  hook: 'A white hall of arches and long cloisters on a Yokudan island, where the Ansei, the sword-saints of the old continent, once sat in silence to listen to their blades.',
  rumour: 'The Hall has no roof. The Ansei said they preferred the sky. The sky has returned the compliment, and has not rained on it in a thousand years.',
  notes: [
    { id: 'ansei_scroll', kind: 'book', title: 'A Page of the Ansei Rule', author: 'The Last Ansei', at: 'chest',
      pages: ['#ON LISTENING TO A BLADE\nA sword is a voice, and a voice has a meaning. Do not draw it to be heard. Draw it to hear. When the edge sings, it is not for you. It is for the one who held it before.',
        'Many have asked what the blade says. I tell them: it says nothing, and that is the lesson. A voice that says nothing is a voice that is waiting for you to speak.'] },
  ],
};
lore.isle_of_the_drowned_ansei = {
  hook: 'A low green island in the Yokudan sunken isles, where a company of Ansei is said to have gone down together, sword by sword, rather than abandon their trust.',
  rumour: 'On the isle, at low tide, you can see swords standing in the sand, upright, in a ring. They are not planted. They are not anyone\'s. They are, the fishermen say, keeping watch.',
  notes: [
    { id: 'drowned_oath', kind: 'inscription', title: 'Oath Carved on a Rock of the Isle', at: 'site',
      pages: ['^WE STAYED. THE SEA CAME. WE DID NOT MOVE.\n^LET THE SWORDS STAND WHERE WE STOOD.\n^LET THE TIDE KNOW WHAT IT HAS TAKEN.',
        'Beneath, a fisherman\'s scratch: \'I once tried to pull one out. It was very heavy. It was very cold. It said, quite distinctly, in a voice I do not wish to describe: not yet.\''] },
  ],
};
lore.tall_papas_spire = {
  hook: 'A tall, thin spire of white stone on the Yokudan desert, said to have been raised by a single mason who could not stop climbing, and who is still, in some tellings, at the top.',
  rumour: 'Tall Papa\'s Spire has no door. A visitor must climb the outside, which is smooth as glass, and which has been the downfall of a great many confident people.',
  notes: [
    { id: 'spire_graffiti', kind: 'notes', title: 'Graffiti on the Fortieth Course', at: 'site',
      pages: ['#I WAS HERE\nAt the fortieth course. My hands are bleeding. I regret nothing. The wind up here tastes of cold stone. I can see a grey line of sea on the west. I am told there is more, higher. I do not believe it. I am going to find out.',
        'Another hand, much higher, in a different ink: \'I was here, too. It was not worth it. It was exactly worth it. Come up.\''] },
  ],
};
lore.yokudan_wellspring = {
  hook: 'A deep, cool spring in a bowl of the Yokudan desert, ringed with tamarisks and old cairns, where every caravan of the lost continent once stopped to drink and to give thanks.',
  rumour: 'The wellspring has never run dry, even in the great drought. The herders say it is because it is not fed by rain, but by something beneath the sand that has not yet decided to stop.',
  notes: [
    { id: 'cairn_tags', kind: 'list', title: 'Tags Hung on the Cairns', at: 'site',
      pages: ['#THANKS LEFT AT THE WELL\nFrom a salt-carrier: thanks, and a pinch of salt. From a mother with a sick child: thanks, and a ribbon, for the child lived. From a sword-singer, who did not leave his name: thanks, and a very small, very sharp knife, point downward.'] },
  ],
};
lore.sand_drowned_village = {
  hook: 'A village of white-walled houses sunk to the eaves in the Yokudan dunes, its streets still readable beneath a skin of sand, and its well-head still faintly damp.',
  rumour: 'The village of Sand-Drowned was buried in a single night, they say, and its people simply walked out of their front doors into the sand and did not come back. The doors are still open.',
  notes: [
    { id: 'door_chalk', kind: 'notes', title: 'Chalk on a Doorframe', at: 'site',
      pages: ['#WE HAVE GONE TO THE WELL\nBack by dusk. Do not wait. Dinner is in the pot. The goat has been fed. If the sand rises, leave it. It is not unkind. It is only slow.',
        'The sand has risen. The goat has been fed. The pot is still on the fire, and the fire is still faintly warm. I did not stay. I do not think you would have either.'] },
  ],
};
lore.pyandonean_fleet_anchorage = {
  hook: 'A fleet at anchor: a raft-city of shell-hulled ships lashed together under green glass lanterns, a coral flagship at the centre, serpent-prowed boats alongside, everything gleaming wet.',
  rumour: 'The Maormer say the fleet has never been in port. It is the port. The ships are the streets, and the streets are the ships, and the only place a Maormer sets foot on land is to bury his dead.',
  notes: [
    { id: 'fleet_orders', kind: 'orders', title: 'Orders of the Day, Fleet Anchorage', author: 'Admiral Nehra', at: 'chest',
      pages: ['#ORDERS\nThe watch shall change at the third bell. Lanterns shall be trimmed. The prisoner in the hold shall be fed and not spoken to. If the sea is calm, it is a trick. If the sea is rough, it is a courtesy.',
        'Pencilled on the back, small and very steady: \'The prisoner has asked three times to speak to the Admiral. I have told him she is busy. She is not busy. She is afraid of what he knows. I have decided, for the sake of my own sleep, not to ask what it is.\''] },
  ],
};
lore.mistveil_village = {
  hook: 'A fishing village of stilt-houses and pearl-rakes on a misted Pyandonean inlet, where the Maormer poor live with the tide, the gulls and the endless threat of a fleet that does not care.',
  rumour: 'The people of Mistveil say they are quiet because the mist takes the sound. The mist, for its part, says nothing.',
  notes: [
    { id: 'pearl_tally', kind: 'ledger', title: 'Pearl-Raker\'s Tally', author: 'A raker of Mistveil', at: 'chest',
      pages: ['#THIS SEASON\nForty pearls, of which nine were round, three were black and one was a very small, very bright thing that I did not give the fleet. I sewed it into the hem of my daughter\'s dress. It is the only wealth of this house.',
        'The fleet-tax is thirty. I told them forty-one. I told them I had given the fleet its due. They believed me. It is the first lie I have told in ten years, and the best.'] },
  ],
};
lore.sload_spawning_pools = {
  hook: 'A sprawl of warm, sluggish pools in the Thrassian reefs where the Sload breed their strange young, and where a plague-diver goes in at dawn and comes out, sometimes, at dusk.',
  rumour: 'The pools are never still. At night they glow a very faint green. The Sload say it is the young, dreaming. The plague-divers say it is the dreaming that is the problem.',
  notes: [
    { id: 'diver_log', kind: 'journal', title: 'Log of a Plague-Diver', author: 'Ooma', at: 'chest',
      pages: ['#Dive forty-one\nDown to the fourth pool. The coral is the colour of a bruise. It sings. I have never heard coral sing before. I have a wax in my ears. It does not help. I am humming, and I do not know the tune.',
        'Dive forty-two. I have been humming the tune for a month. It is the tune of a drowned bell. I think the heart is calling for me. I think it is lonely. I think I am the only one who can hear it, and I am not sure I want to be.'] },
  ],
};

const n = f.write('marquee_foreign.json');
console.log('foreign: ' + f.npcs.length + ' npcs, ' + n + ' quests');
writeLore('marquee_foreign.json', lore);
addToIndex(LORE_DIR, 'marquee_foreign.json');
console.log('foreign lore: ' + Object.keys(lore).length + ' places');
addToIndex(QUEST_DIR, 'marquee_foreign.json');
