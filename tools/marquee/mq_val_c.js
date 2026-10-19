'use strict';
// Valenwood marquee content, part C: a further side quest for each hub that had one.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  f.relic('cut_staff', 'The Cut Staff', 'A straight young staff of green ash, still weeping sap at the end where it was cut. It was taken from a living tree, and the tree has been bleeding quietly ever since.', 20);
  f.relic('toll_ribbon', 'Toll-Ribbon of the Mist', 'A strip of silver ribbon the Silvenar\'s court ties round a passer\'s wrist when they have paid the mist-toll. Both ends are knotted. One is knotted wrongly, with a Baandari hand.', 11);
  f.relic('hidden_sugar', 'Moon-Sugar in a Wagon-Wheel', 'A hollow wagon-wheel hub packed with grey-white crystals of moon-sugar. A Bosmer tongue would find it strange; a Khajiit would weep. It is worth a very large sum, and a very small sentence.', 11);
  f.relic('three_dyes', 'Three Dyes of Arenthia', 'Three stoppered vials: moss-green, falls-silver and dawn-pink. Each was gathered where the water meets the light, and each shifts hue when you look at it from the side.', 20);
  f.boss('hound_stealer', { name: 'Gorrun Hound-Taker', type: 'nirncraft:bandit_chief', hp: 2.8, dmg: 1.3, level: 18,
    weapon: 'nirncraft:bosmer_sword', gear: { chest: 'nirncraft:bosmer_chestplate' }, drop: 'loot:standard', glow: false });
  f.boss('sugar_runner', { name: 'Zar\'ha the Wheel-Wright', type: 'nirncraft:bandit_chief', hp: 3.0, dmg: 1.4, level: 22,
    weapon: 'nirncraft:khajiit_dagger', drop: 'loot:standard', glow: false });

  // Falinesti: a Green Pact dilemma
  f.q({
    id: 'a_staff_from_a_living_tree', title: 'A Staff from a Living Tree', region: 'valenwood/malabal_tor', start: 'npc:@maelin', requires: ['level:14'],
    offer: 'Your roots look uneasy, Root-Tender.',
    pitch: 'They are, walker. A pilgrim cut a young ash on the climb to the Winter site, to make a walking staff. A living tree. In sight of the city. The Pact is not a suggestion. He is old, and lame, and he did it so that he could walk the last mile to see the city before he dies. The Wardens will want his hands. Maelin wants to know what justice looks like, and cannot see it from here.',
    desc: 'An old, lame pilgrim cut a living ash for a walking staff, breaking the Green Pact. Root-Tender Maelin wants a stranger to find him and decide what is to be done.',
    objectives: [
      o.go('falinesti_winter_site', 'Climb to the Winter site and find the lame pilgrim'),
      o.retrieve('falinesti_winter_site', 'Take the cut staff from the pilgrim', '@cut_staff'),
      o.talk('@maelin', 'Bring the staff to Maelin and speak for the pilgrim',
        'The pilgrim is very old and very sorry. He cut one young ash to be able to reach the city. The tree is bleeding. What will you ask of him?',
        'Maelin sets the staff upright against his shoulder. The sap runs slowly down it. "A young ash. Bleeding. A very old man. Walker, the Pact does not say how. It says only: do not cut. And a tree is a person, to us. A very quiet one. What shall be the penance? A year of tending? Or the Wardens, and his hands?"',
        { choices: [
          choice('A year of tending the grove where the ash grew.', 'Maelin nods, and his shoulders ease. "A year of tending. The ash will heal. The pilgrim will heal. And the city will not lose a man to a law that did not wish to be cruel."', 'flag:@staff_tended'),
          choice('Report him to the Wardens. The Pact is the Pact.', 'Maelin lowers the staff very slowly. "The Pact is the Pact. Yes. It is also the only law that has never failed us. It is a hard answer, walker, and an honest one. The Wardens will come."', 'flag:@staff_reported;loot:standard'),
        ] }),
    ],
    outro: 'A walking staff of green ash stands in the Root-Tender\'s hall, either as a reminder or a warning. The tree it came from has stopped bleeding.',
    rewards: { gold: 420, loot: 'standard' },
  });

  // Woodhearth: rescue
  f.q({
    id: 'the_child_on_the_high_node', title: 'The Child on the High Node', region: 'valenwood/greenshade', start: 'npc:@hush', requires: ['level:12'],
    offer: 'Your camp is quieter than usual, Elder.',
    pitch: 'Because the youngest cub of the camp has not come down, walker. Twelve stories up the Weave, on a node, since last night. She went up to see whether the bridge-people were really as tall as everyone says. She is stuck. The Warden has a rope and a dislike of heights. The camp has a ladder that is much too short. Hush does not wish to ask the Weave for help. She is asking you.',
    desc: 'A cub from the Woodhearth ground-camp is stuck on a high node of the Weave. Elder Hush asks a stranger to bring her down.',
    objectives: [
      o.go('woodhearth', 'Climb the Great Weave to the high node'),
      o.clear('woodhearth', 'Drive off the nesting hawks that guard the high node',
        [mob('nirncraft:harpy', 2, 'Node-Hawk')]),
      o.escort('@hush', 'woodhearth', 'Escort the cub, and Hush, down to the ground-camp'),
    ],
    outro: 'The cub came down the Weave on a stranger\'s back, and said only that the bridge-people were not nearly as tall as they let on.',
    rewards: { gold: 260, loot: 'minor' },
  });

  // Silvenar: diplomacy
  f.q({
    id: 'the_mist_toll_dispute', title: 'The Mist-Toll Dispute', region: 'valenwood/malabal_tor', start: 'npc:@gatewarden', requires: ['level:14'],
    offer: 'You are the first person I have not turned back today.',
    pitch: 'Because you did not argue, walker. A Baandari trader is arguing with the Pact\'s Warden at the south stair about the toll. The Baandari says the toll is a gift. The Warden says it is a tax. Wren does not care which. She wants them to stop. If you can carry an offer from one to the other, and persuade them both, she will pretend that she did not hear a word of it.',
    desc: 'A Baandari trader and a Pact-Warden quarrel over the mist-toll at Silvenar. Gate-Warden Wren wants a stranger to broker peace.',
    objectives: [
      o.talk('@scribe', 'Ask the Silvenar\'s Scribe for the court\'s own wording of the toll',
        'The Gate-Warden would like a clear ruling. Is the toll a gift or a tax?',
        'Ethelwe taps the ink-stain on her finger. "Both. The court calls it a gift in the first clause and a tax in the third. It has been this way for four hundred years. Whoever reads one clause is right. Whoever reads both is a politician. Tell them to read the whole page, and sign it together."',
        { actions: 'relic:@toll_ribbon' }),
      o.talk('@gatewarden', 'Bring the ribbon to the quarrelling pair at the south stair',
        'Here is the court\'s wording, both clauses. The toll is a gift and a tax. Sign the ribbon together.',
        'Wren watches the two of them read, grumble and, at last, tie the ribbon between them with a grim kind of courtesy. "Together. Huh. Well. Walker, that is the first thing anyone has done at that stair in a year that did not end in blows. Take a seat at the Firefly Lantern. Pip owes me a favour, and I have decided to spend it on you."',
        { choices: [
          choice('Suggest the Baandari pay half in coin and half in song.', 'Wren snorts. "Song. For a toll. It will not work, and the Silvenar will adore it. Done."', 'flag:@toll_song'),
          choice('Suggest the Warden waive the toll for traders who carry court post.', 'Wren nods slowly. "A trade, then. Fair. The trader gets his road. The court gets its letters. And the Warden is spared the quarrel."', 'flag:@toll_post;loot:standard'),
        ] }),
    ],
    outro: 'The mist-toll of Silvenar is both a gift and a tax, and a silver ribbon with one wrong knot hangs at the south stair for everyone to see.',
    rewards: { gold: 380, loot: 'standard' },
  });

  // Rawlkha: Pact dilemma with contraband
  f.q({
    id: 'moon_sugar_in_the_wheel', title: 'Moon-Sugar in the Wheel', region: 'valenwood/reapers_march', start: 'npc:@mohr', requires: ['level:16'],
    offer: 'You have the look of a guard who has found something.',
    pitch: 'I have, walker, and I wish I had not. A wagon-wheel at the Caravan Gate was too heavy for its size. Mohr rolled it into the guardhouse and cracked the hub. Moon-sugar. A fortune of it. The wagon\'s owner is a Wheel-Wright called Zar\'ha who runs a very honest stall by day. Mohr could report him. The Pact\'s Wardens would burn the lot and break his hands. Mohr could pour it in the river. Or sell it. He has thought about all three.',
    desc: 'Guard Mohr found a hoard of moon-sugar hidden in a wagon-wheel hub. The owner, a stall-keeper, is nearby. He asks a stranger to decide what to do with it.',
    objectives: [
      o.go('rawlkha', 'Take the hub to the Caravan Gate and confront the wheel-wright'),
      o.kill('rawlkha', 'Face Zar\'ha the Wheel-Wright, who will not surrender his hoard quietly',
        [{ boss_id: '@sugar_runner' }], { actions: 'relic:@hidden_sugar' }),
      o.talk('@mohr', 'Decide the fate of the moon-sugar with Mohr',
        'Zar\'ha is dealt with. The sugar is here. Report it, pour it out, or sell it?',
        'Mohr turns the hub over, slowly, and the grey crystals glint. "A fortune, walker. A fortune that would buy Mohr a year of sleep. It would also bring the Wardens down on a hundred Khajiit honest traders who did nothing. What do you say?"',
        { choices: [
          choice('Pour it into the river. Let the sugar be forgotten.', 'Mohr nods, and carries the hub to the bridge. The crystals dissolve in a long grey cloud that tastes, faintly, of honey. "Gone. A pity. But a clean one. Thank you."', 'flag:@sugar_poured'),
          choice('Sell it quietly and give the coin to the Caravan Gate\'s widows.', 'Mohr hesitates, then, very slowly, smiles. "Widows. Yes. A fair use for a foul thing. Mohr will see it done."', 'flag:@sugar_widows;loot:major'),
          choice('Report it to the Wardens. The Pact is the Pact.', 'Mohr straightens. "Reported, then. It is correct. And it will be burned. Mohr will not enjoy it. Thank you for being brave enough to say it."', 'flag:@sugar_reported'),
        ] }),
    ],
    outro: 'A hub of moon-sugar was found in a wagon-wheel at Rawlkha, and was poured, spent or burned, depending on the voice that spoke last.',
    rewards: { gold: 480, loot: 'standard' },
  });

  // Arenthia: fetch with a twist
  f.q({
    id: 'the_weavers_three_dyes', title: 'The Weaver\'s Three Dyes', region: 'valenwood/reapers_march', start: 'npc:@wynn', requires: ['level:10'],
    offer: 'You have the green of a good weaver on your sleeve.',
    pitch: 'It is a bad weaver\'s green, walker, and I am ashamed of it. Wynn has an order from the Silvenar himself, for a banner of three colours: moss-green, falls-silver and dawn-pink. She has only ever managed one. The other two come only where water meets light, and there is a place for each: the falls\' mist, the dawn pool and the gardens\' lowest terrace. The twist is: if you take them in anger, they run. They must be gathered gladly.',
    desc: 'Weaver Wynn needs three dyes gathered where water meets light, each of which turns if collected in anger.',
    objectives: [
      o.go('waterdancer_falls', 'Gather falls-silver from the mist at Waterdancer Falls'),
      o.go('lorion_falls', 'Gather dawn-pink at the pool of Lorion Falls', { actions: 'relic:@three_dyes' }),
      o.talk('@wynn', 'Bring the three dyes to Wynn',
        'Three dyes: moss, silver, dawn. Gathered gladly. Will they hold?',
        'Wynn uncorks the silver and watches it settle. Then, slowly, she begins to laugh. "They hold! Gladly gathered, and they hold. Walker, you have a gift. Few can gather gladly, in a forest that taxes a smile. Wynn will weave your name into the border, in a thread that only shows at dawn."'),
    ],
    outro: 'A banner of three colours hangs in the Silvenar\'s hall, with a border thread that shows only at dawn. It is said to spell a stranger\'s name.',
    rewards: { gold: 240, loot: 'minor' },
  });

  // Marbruk: rescue / hunt
  f.q({
    id: 'the_wardens_missing_hound', title: 'The Warden\'s Missing Hound', region: 'valenwood/greenshade', start: 'npc:@huln', requires: ['level:10'],
    offer: 'You are Huln, and you are not speaking.',
    pitch: 'Seven words. Spent. The Warden\'s hound. Gone. Vale. Three days. Please. Find. That is eight words, and Huln is very sorry. He has the look of a man who has run out of gold and is now spending silver. Take the hound\'s collar from the hook. The vale is the Shrouded Vale, east of the wall. Bring her home.',
    desc: 'The Warden\'s hound was lost in the Shrouded Vale three days ago. Smith Huln, who rarely speaks, asks a stranger to find her.',
    objectives: [
      o.go('shrouded_vale', 'Search the Shrouded Vale for the Warden\'s hound'),
      o.kill('shrouded_vale', 'Stop Gorrun Hound-Taker, who has her in a snare',
        [{ boss_id: '@hound_stealer' }, mob('nirncraft:wolf', 2, 'Hound-Trap Wolf')]),
      o.talk('@huln', 'Return the hound to Huln',
        'The hound is safe. She was snared by a poacher. She wishes to be fed.',
        'Huln looks at the hound. The hound looks at Huln. He kneels, and something very large moves in his throat. "Thank you." That is two words, and he looks as though they have cost him a week of coin. Then he takes your hand, and does not say anything at all, for a long and perfectly fluent time.'),
    ],
    outro: 'The Warden\'s hound is home, and Smith Huln has a rare and treasured habit of nodding to a stranger in the street.',
    rewards: { gold: 230, loot: 'minor' },
  });
};
