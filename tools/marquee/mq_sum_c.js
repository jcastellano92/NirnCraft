'use strict';
// Summerset marquee content, part C: a further side quest for each hub with one.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  f.relic('lost_signet', 'Signet of the Sea-Lift Heir', 'A small gold signet of a rising sun over a wave, worn smooth at the edge by a nervous thumb. The heir of a very minor house of Cloudrest dropped it in the lift and has not been seen since.', 26);
  f.relic('eclipse_table', 'The Contested Eclipse Table', 'A folded table of predicted eclipses, written in two hands that disagree on the third line. Each hand has initialled its own correction, and each has crossed out the other\'s.', 15);
  f.relic('knot_string', 'Tor\'s Knotted String', 'A length of coloured twine, knotted in a code of seventeen colours and a hundred and four knots. It is a navigator\'s memory, and it is wrong by exactly one knot in the middle.', 11);
  f.relic('drowned_bell_clapper', 'Clapper of the Drowned Bell', 'A bronze bell-clapper green with weed, still faintly ringing a note too low to hear. When held to the ear it sounds like a church on the sea-floor.', 22);
  f.relic('dune_lantern', 'Dune-Ghost\'s Lantern', 'A very small lantern of pale glass, its wick burnt black but never consumed. It gives off no light at all in the dark, and a very faint pearly glow in sunlight.', 17);
  f.boss('dune_wraith', { name: 'The Pale Dune-Wraith', type: 'nirncraft:draugr_deathlord', hp: 3.4, dmg: 1.5, level: 26,
    weapon: 'nirncraft:elven_sword', drop: 'loot:standard', glow: true });
  f.boss('knot_cutter', { name: 'Sethis Knot-Cutter', type: 'nirncraft:bandit_chief', hp: 3.0, dmg: 1.4, level: 24,
    weapon: 'nirncraft:maormer_sword', gear: { chest: 'nirncraft:maormer_chestplate' }, drop: 'loot:standard', glow: false });
  f.boss('sinking_bell_keeper', { name: 'The Bell-Warden Below', type: 'nirncraft:draugr_deathlord', hp: 3.2, dmg: 1.5, level: 24,
    weapon: 'nirncraft:elven_mace', drop: 'loot:standard', glow: true });

  // Cloudrest: escort/rescue
  f.q({
    id: 'the_heir_in_the_sea_lift', title: 'The Heir in the Sea-Lift', region: 'summerset/summerset_isle', start: 'npc:@ewen', requires: ['level:16'],
    offer: 'Your lift is empty, Ewen. Why is your hand shaking?',
    pitch: 'Because it was not, walker. A young lord of a very minor house stepped in at dusk, said he wished to see the sea at its closest, and Ewen lowered him. When the lift came up he was gone. No splash. A signet on the floor. Ewen has not been able to look at the water since. If he fell, the sea has him. If he did not, someone took him. Ewen is a coward. He needs someone who is not.',
    desc: 'A young lord vanished from Cloudrest\'s sea-lift at dusk, leaving only his signet. Ewen wants him found.',
    objectives: [
      o.go('cloudrest', 'Examine the sea-lift and take the lord\'s signet', { actions: 'relic:@lost_signet' }),
      o.go('wreck_of_the_bent_stag', 'Search the sea-caves and wreck below Cloudrest'),
      o.clear('wreck_of_the_bent_stag', 'Drive off the smugglers holding the young lord',
        [mob('nirncraft:bandit', 3, 'Cave Smuggler'), mob('npc:thug', 1, 'Hostage-Keeper', { hostile: true })], { placement: 'structure' }),
      o.escort('@ewen', 'cloudrest', 'Bring the young lord home through the sea-lift'),
    ],
    outro: 'A young lord of a very minor house rose by sea-lift at dawn, pale, damp and apologetic. Ewen looked at the water again that day, for the first time in a week.',
    rewards: { gold: 480, loot: 'standard' },
  });

  // Skywatch: diplomacy/contest
  f.q({
    id: 'the_eclipse_debate', title: 'The Eclipse Debate', region: 'summerset/auridon', start: 'npc:@ond', requires: ['level:16'],
    offer: 'You are sweeping a very clean ring.',
    pitch: 'I am, walker, and I am not looking up. Two astronomers in the ring-walk have been shouting for three days about the next eclipse. One says Thursday, one says Saturday. Both have tables. Both are certain. A stranger could fetch a third reading from the old observer at the Ring Observatory and settle it. Ond has a broom and a very strong feeling of not being his department.',
    desc: 'Two Skywatch astronomers disagree on the date of the next eclipse. Ring-Keeper Ond wants a stranger to fetch a third reading from the old observer.',
    objectives: [
      o.talk('@taleth', 'Ask Taleth for her table of the eclipse',
        'Two tables, two dates. Give me yours and I will carry it to the observatory.',
        'Taleth hands over a folded sheet with the briefest sniff. "Thursday. Obviously. My rival will claim Saturday, as he claims everything. He has not the patience to measure. Tell the observer to be exact, and to say so loudly."',
        { actions: 'relic:@eclipse_table' }),
      o.go('the_veiled_keep', 'Carry the contested table to the old observer in the Veiled Keep'),
      o.deliver('@maril', '@eclipse_table', 'Bring the observer\'s reading back to the Ring-Walk',
        'The observer says: Friday. Neither of you is right. You each made the same error in the third line, from opposite directions.',
        'Maril reads the third line, and her face does something complicated. "Friday. Friday! Neither, and both! Walker, please do not tell Taleth I laughed. Or the rival. They will each hate me. It is, I think, the first time they have ever agreed on being wrong."',
        { choices: [
          choice('Announce it jointly, so neither feels bested.', 'Maril claps. "Jointly! A diplomat! The pair of them will sulk for an hour, and then they will share a pot of tea and a table, and neither will say what they did to deserve it."', 'flag:@eclipse_joint'),
          choice('Let each find the error on their own.', 'Maril grins. "A cruelty with a bow on it. Very well. They will find it in a week. Each will say they found it first. Ond will keep sweeping."', 'flag:@eclipse_alone;loot:standard'),
        ] }),
    ],
    outro: 'The eclipse was on Friday. Two astronomers shared a ring-walk and a sulk, and the broom of Ring-Keeper Ond swept on, undisturbed.',
    rewards: { gold: 330, loot: 'standard' },
  });

  // Dusk: haunting
  f.q({
    id: 'the_lantern_of_the_pale_dunes', title: 'The Lantern of the Pale Dunes', region: 'summerset/summerset_isle', start: 'npc:@eilen', requires: ['level:18'],
    offer: 'You walk the quay like someone who has been told not to.',
    pitch: 'I have, walker, and I am going to say it, once. The dunes behind Dusk are haunted by a lantern. A small, pale light, low on the sand, seen at the edge of the evening by those who work late. It does not move. It waits. Three sailors who followed it did not return. Eilen is not a woman who believes in lights. She has seen her own daughter\'s name written in the sand beneath it. Please put it out.',
    desc: 'A pale lantern haunts the dunes behind Dusk, and three sailors who followed it did not return. Harbourmaster Eilen asks a stranger to put it out.',
    objectives: [
      o.go('dusk_keep', 'Walk the dunes at dusk to Dusk Keep'),
      o.cond('night', 'Wait for the pale lantern to appear', 'dusk_keep'),
      o.kill('dusk_keep', 'Face the Pale Dune-Wraith that carries the lantern',
        [{ boss_id: '@dune_wraith' }], { placement: 'structure', actions: 'relic:@dune_lantern' }),
      o.talk('@eilen', 'Tell Eilen what the lantern was for',
        'The wraith was a keeper of the old keep, waiting for ships that never returned. It lit its lantern for them. It is at rest. The lantern is here.',
        'Eilen takes the small glass lantern, and for a long moment she says nothing at all. "For ships that did not return. And my daughter\'s name. It had been waiting for her. Walker, I shall hang it in the Lantern Tower, under the first lamp. A lantern that has waited that long is owed a place."',
        { choices: [
          choice('Hang it in the tower. Let it light the sea for the lost.', 'Eilen nods, and her eyes shine. "Under the first lamp, then. It will give no light, but it will give company. Thank you."', 'flag:@dune_lantern_tower'),
          choice('Bury it in the dunes where it was lit.', 'Eilen is quiet. "Where it waited. Yes. The sand will keep it. The dunes will be quiet. It is a kindness. It will be a hard walk to the place, and I will make it."', 'flag:@dune_lantern_buried;loot:standard'),
        ] }),
    ],
    outro: 'The pale lantern of the dunes no longer burns. Whether hung or buried, it is no longer waiting, and nobody has followed a light off the quay since.',
    rewards: { gold: 560, loot: 'standard' },
  });

  // Shimmerene: Maormer intrigue / investigation
  f.q({
    id: 'the_wrong_knot', title: 'The Wrong Knot', region: 'summerset/summerset_isle', start: 'npc:@tor', requires: ['level:18'],
    offer: 'You are staring at a bit of string.',
    pitch: 'I am, walker, because it is wrong. A hundred and four knots, seventeen colours. Tor has steered by it for thirty years and never once been lost. Last week, near Galorne Obelisk, the string said port and the coast said starboard. One knot in the middle. Wrong. Not slipped. Re-tied, by a hand that knew the pattern. Somebody has been in his cabin. Somebody wants him on the rocks.',
    desc: 'One knot in Navigator Tor\'s memory-string was re-tied by a stranger, steering him toward the rocks. He wants the tamperer found.',
    objectives: [
      o.talk('@yeli', 'Ask Yeli which sailor was seen in Tor\'s sail-locker',
        'Someone re-tied a knot in Tor\'s string. You see everyone who goes into the loft. Who was there?',
        'Yeli puts down her needle and her face goes thoughtful. "A man in a very good cloak, who said he was a ship\'s broker. Spoke Altmer like a Maormer, if you follow. He bought a bolt of teal and asked after Tor\'s cabin. Yeli thought nothing of it. She thinks something of it now."',
        { actions: 'relic:@knot_string' }),
      o.go('galorne_obelisk', 'Follow the broker\'s trail to Galorne Obelisk'),
      o.kill('galorne_obelisk', 'Take down Sethis Knot-Cutter, the Maormer agent',
        [{ boss_id: '@knot_cutter' }, mob('nirncraft:bandit', 2, 'Wave-Mark Guard')]),
      o.deliver('@tor', '@knot_string', 'Return the string and tell Tor who tied it',
        'The knot was tied by a Maormer agent who hoped to wreck your ship on the Galorne rocks. He is dead. The string is whole.',
        'Tor holds the string and does not speak for a long time. He runs his thumb along the wrong knot, and then, very deliberately, ties it right. "Thirty years. A Maormer in my cabin. Walker, shall Tor tell the Dock-Master, and have every cabin searched? Or keep it, and use the string to lead them somewhere the Maormer wish they had not gone?"',
        { choices: [
          choice('Tell the Dock-Master. Every cabin should be searched.', 'Tor nods. "Then it will be done, loudly, with a ledger. A good answer. A dull one. Tor is very fond of dull."', 'flag:@knot_reported'),
          choice('Leave the wrong knot. Let it lead the Maormer astray.', 'Tor smiles, slow and cold. "A false string for a false sailor. Tor will keep the wrong knot in the chart, and see who follows it. It will be a very interesting season."', 'flag:@knot_trap;loot:major'),
        ] }),
    ],
    outro: 'A navigator\'s string has one wrong knot, either put right or kept as bait. Somewhere off Galorne, a Maormer ship has begun to follow a very old, very trusted lie.',
    rewards: { gold: 640, loot: 'standard' },
  });

  // Firsthold: haunting/sunken
  f.q({
    id: 'the_bell_beneath_the_water', title: 'The Bell Beneath the Water', region: 'summerset/auridon', start: 'npc:@cael_priest', requires: ['level:16'],
    offer: 'Your bell rings, Priest. But it rings late.',
    pitch: 'Late, walker, because it is not the only one. At the turn of the tide, a second bell rings, under the water, a half-beat behind mine. It is slow and green with weed. A drowned bell, from a drowned chapel, that has not been rung in four hundred years. Cael would not mind a ghost. He minds that it is out of tune. He would like to hear it hushed, or tuned, or at least introduced.',
    desc: 'A second bell rings under the water at Firsthold at every tide-turn. Priest Cael wants it found, tuned or quieted.',
    objectives: [
      o.go('greenwater_cove', 'Search the tide-pools of Greenwater Cove at the turn of the tide'),
      o.clear('greenwater_cove', 'Drive off the drowned that guard the sunken chapel',
        [mob('minecraft:drowned', 3, 'Chapel-Drowned')], { placement: 'structure' }),
      o.kill('greenwater_cove', 'Face the Bell-Warden Below',
        [{ boss_id: '@sinking_bell_keeper' }], { placement: 'structure', actions: 'relic:@drowned_bell_clapper' }),
      o.deliver('@cael_priest', '@drowned_bell_clapper', 'Bring the clapper to Cael',
        'The drowned bell\'s clapper, weed and all. The warden rang it every tide to call the congregation he lost. He is at rest.',
        'Cael takes the clapper and holds it gently, as though it were a sleeping bird. "Four hundred years. Calling a congregation that was already under the water. Walker, shall I hang the clapper in the Rock Chapel, and let its voice join mine? Or return it to the sea, and let him ring his own congregation?"',
        { choices: [
          choice('Hang it in the Rock Chapel. Let the two bells sing together.', 'Cael\'s voice is thick. "Two bells. One above, one below. And a priest between. Yes. It will be out of tune, and it will be the finest sound in Auridon."', 'flag:@bell_joined'),
          choice('Return it to the sea. Let the warden have his chapel.', 'Cael bows. "Then it goes home. Quietly, on the tide. A very good answer, and a very sad one. The chapel will have its bell. I shall ring mine alone, and listen."', 'flag:@bell_returned;loot:standard'),
        ] }),
    ],
    outro: 'The drowned bell of Firsthold rings no more at the turn of the tide, or rings in harmony with the chapel\'s own. A priest has stopped being annoyed.',
    rewards: { gold: 480, loot: 'standard' },
  });
};
