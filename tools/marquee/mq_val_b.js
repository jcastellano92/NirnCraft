'use strict';
// Valenwood marquee content, part B: Rawlkha, Arenthia, Marbruk and the minor places.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  // ------------------------------------------------------------------ RAWLKHA
  f.npc('shaka', 'Trader Sha\'ka', 'caravan_trader', 'rawlkha', 5, 5,
    'Sha\'ka sells silk, spice and moon-sugar from a bamboo stall strung above the street and will not take a coin that does not ring. She knows every caravan road in Reaper\'s March and every Pact patrol that watches it.', true);
  f.npc('pura', 'Fortune-Weaver Pura', 'mage', 'rawlkha', -8, 6,
    'Pura weaves your fortune into a bracelet while you wait and then, very gently, ties it onto your wrist. She says a fortune you can see is a fortune you can argue with. Most people argue.', true);
  f.npc('mohr', 'Guard Mohr', 'steward', 'rawlkha', 9, -6,
    'Mohr stands at the Caravan Gate and has not been relieved since the autumn. He is not complaining. He is simply sitting, with great dignity, on his shield.');
  f.npc('rope_den', 'Zhan\'ra of the Rope Market Den', 'innkeeper', 'rawlkha', -3, -9,
    'The Rope Market Den is an upstairs room with cushions and a hookah and a very long waiting list. Zhan\'ra serves moon-sugar tea and gossip in equal measure and will tell you which one is stronger.', true);

  f.relic('pact_waiver', 'Waiver of the Green Pact', 'A strip of green silk stamped with three leaf-seals, granting one wagon of foreign goods passage through Valenwood without felling or lighting a living tree. The ink is still faintly damp.', 11);
  f.relic('wagon_deed', 'Deed to the Sha\'ka Wagon', 'A folded deed of ownership for a small caravan wagon, the wheels of which were designed by a Dwemer and the axle by a Khajiit and the rest by whichever fool offered the lowest price.', 11);
  f.boss('wood_witch', { name: 'Hagraven Nelloth the Sap-Spinner', type: 'nirncraft:hagraven', hp: 3.6, dmg: 1.5, level: 26, drop: 'loot:standard', glow: true });
  f.scene('raw_ritual', [
    ['narrator', 'The trees on either side of the road are hung with ribbons, bone chimes and small dripping cups. A procession of Bosmer in green paint wades through the mist, singing very low.'],
    ['@shaka', 'Do not break step, and do not look at the lead singer. It is the Pact\'s tithe-walk. If a wagon crosses their path, they will assume the wagon is the tithe.'],
  ]);

  f.q({
    id: 'a_wagon_through_the_green_pact', title: 'A Wagon Through the Green Pact', region: 'valenwood/reapers_march', start: 'npc:@shaka', requires: ['level:14'],
    offer: 'A wagon full of canvas and a face full of worry.',
    pitch: 'Wagon, walker. Silk and sugar for Marbruk, three days away, through Pact country. A wagon is made of wood. The Pact forbids a felled tree or a cart wheel that bruises a root. The Pact has patrols. Sha\'ka has a waiver, signed by someone who may or may not exist. If the wagon is stopped, say you are with me. If it is not, say nothing, and stay off the rootways.',
    desc: 'Trader Sha\'ka needs her wagon of silk and sugar escorted through Green Pact country to Marbruk without breaking a single root.',
    objectives: [
      o.talk('@shaka', 'Take the Waiver of the Green Pact from Sha\'ka',
        'I will see the wagon through. What is in it, truly, besides silk and sugar?',
        'Sha\'ka\'s ears twitch. "Silk. Sugar. Eleven rolls of Pact-green cloth the Wardens do not know I own. Walker, take the waiver, and if a patrol asks to look, show them the silk, and talk about the weather."',
        { actions: 'relic:@pact_waiver' }),
      o.go('reapers_henge', 'Cross the Pact\'s tithe-walk at Reaper\'s Henge', { actions: 'scene:@raw_ritual' }),
      o.kill('thibauts_cairn', 'Drive off the Hagraven that haunts the cairn road',
        [{ boss_id: '@wood_witch' }]),
      o.go('marbruk', 'Bring the wagon into Marbruk without breaking a root'),
      o.talk('@mohr', 'Return to Mohr at the Caravan Gate and report the wagon safe',
        'The wagon went through. Nobody was stopped. The Pact was not offended, as far as I know.',
        'Mohr stretches, which takes a surprisingly long time. "Through the Pact and out the other side. Sha\'ka owes you a bolt of silk and she will not say it. Mohr says it for her. Take this. It is an honest shield, borrowed, and it has never been struck."',
        { choices: [
          choice('Tell the Pact\'s Wardens about the eleven rolls of green cloth.', 'Mohr nods grimly. "It is the Pact\'s cloth by right. A Warden will visit Sha\'ka. She will be furious, and she will, I think, forgive you."', 'flag:@wagon_reported'),
          choice('Keep the secret. Sha\'ka\'s cloth is her own.', 'Mohr almost smiles. "Good. A secret is a better cargo than silk. She will remember it."', 'flag:@wagon_secret;loot:standard'),
        ] }),
    ],
    outro: 'A wagon passed through Green Pact country without bruising a root, and a cloth merchant of Rawlkha now owes a stranger more than she is willing to say aloud.',
    rewards: { gold: 540, loot: 'standard' },
  });

  f.q({
    id: 'fortunes_in_a_bracelet', title: 'Fortunes in a Bracelet', region: 'valenwood/reapers_march', start: 'npc:@pura', requires: ['level:10'],
    offer: 'Your bracelet is pulling at its thread.',
    pitch: 'It is, walker, and it should not. Pura weaves the fortunes. Pura does not choose them. But three customers have returned with the same fortune, in different threads, the same shape, the same knot at the end. Someone has been tampering with her loom. A fortune is not a thing a tailor should be tampering with.',
    desc: 'Pura\'s loom has produced the same fortune for three different customers. Someone is tampering with it, and she wants the tamperer found.',
    objectives: [
      o.cond('night', 'Hide among the silk bolts at Pura\'s loom and wait for the tamperer', 'rawlkha'),
      o.kill('rawlkha', 'Catch the tamperer at the loom',
        [mob('npc:thief', 1, 'Midnight Weaver', { hostile: true })]),
      o.talk('@pura', 'Tell Pura what the tamperer was weaving',
        'A rival weaver from Arenthia wanted the same fortune woven for three customers, to make them all travel to the Hanging Gardens. A commercial fortune.',
        'Pura laughs behind her paw until she has to wipe her eyes. "A commercial fortune! Three customers sent to Arenthia for the same pretty garden. Walker, that is the first honest fortune Pura\'s loom has ever told. She will weave it herself next time, and charge a commission."'),
    ],
    outro: 'The Hanging Gardens had a very busy summer, and Pura\'s loom, for once, was satisfied.',
    rewards: { gold: 200, loot: 'minor' },
  });

  // ------------------------------------------------------------------ ARENTHIA
  f.npc('eld', 'Gardener Eld', 'farmer', 'arenthia', 4, -6,
    'Eld tends the hanging terraces in Arenthia and has never been seen to stand on level ground. She talks to the roses in the third person and listens to their replies.', true);
  f.npc('sel', 'Priestess Sel of the Falls', 'priest', 'arenthia', -7, 5,
    'Sel keeps the waterfall shrine and prays from behind the curtain of water, so the faithful have never seen her face. She says the water prays better when she is not in the way.', true);
  f.npc('wynn', 'Weaver Wynn', 'merchant', 'arenthia', 8, 7,
    'Wynn weaves moss into cloth and cloth into banners. Her work smells of rain and wears like leather. She sells to Bosmer, to Orcs and, on one occasion, to a very polite wraith.');

  f.relic('garden_seeds', 'Pouch of Hanging Seeds', 'A small pouch of silver-green seeds, each as small as a dewdrop, each wrapped in a scrap of moss. They are said to bloom only when planted while the sun is exactly half over the horizon.', 20);
  f.relic('dawn_trowel', 'Dawn-Trowel of Arenthia', 'A trowel of bone-white wood, its edge gilded in the waterfall\'s spray. The handle is worn into a shape that no human hand quite fits, as if the gardener who used it was not human.', 26);
  f.boss('falls_stalker', { name: 'The Fall-Stalker', type: 'nirncraft:sabre_cat', hp: 3.4, dmg: 1.5, level: 22, drop: 'loot:standard', glow: false });
  f.scene('are_dawn', [
    ['narrator', 'The first light strikes the falls, and every drop of spray becomes a fleck of gold. On the terrace, in the exact point where the light lands, a single patch of bare ground steams faintly.'],
    ['@eld', 'Here. This is where Eld\'s grandmother planted. The seeds will wake in the light, if they are wanted, and they will be wanted if you say so out loud.'],
  ]);

  f.q({
    id: 'a_garden_lost', title: 'A Garden Lost', region: 'valenwood/reapers_march', start: 'npc:@eld', requires: ['level:12'],
    offer: 'You have the dirt of a hundred terraces under your claws.',
    pitch: 'The seeds, walker. The old seeds of the Hanging Gardens, kept in a pouch by Eld\'s grandmother and stolen out of the temple on the last night of winter. A garden may be replanted, but not from nothing. Without the seeds, the oldest terrace stays bare. They went east, over the falls, with a thief who knew a path that Eld has never walked. Waterdancer Falls, the old stairs. Bring the pouch back before dawn, and Eld will tell you where the garden grows best.',
    desc: 'The seeds of Arenthia\'s oldest terrace were stolen and carried east across Waterdancer Falls. Gardener Eld wants them replanted at dawn.',
    objectives: [
      o.go('waterdancer_falls', 'Cross Waterdancer Falls by the old stair'),
      o.kill('waterdancer_falls', 'Stop the seed-thief and the Fall-Stalker that guards the stair',
        [{ boss_id: '@falls_stalker' }, mob('npc:thief', 1, 'Seed-Thief Whin', { hostile: true })], { actions: 'relic:@garden_seeds' }),
      o.cond('day', 'Return to Arenthia by dawn', 'arenthia'),
      o.deliver('@eld', '@garden_seeds', 'Plant the seeds on the bare terrace at dawn',
        'The seeds, whole. Let us put them in the earth at the half-sun hour.',
        'Eld\'s hands tremble as she lifts the pouch. "The half-sun. Quick. Quick. Walker, you plant with me. The garden is half yours."',
        { actions: 'scene:@are_dawn', choices: [
          choice('Plant them on the old terrace, as the grandmother did.', 'Eld bows and digs. "The same hole. The same depth. It will be a wonder."', 'flag:@garden_old'),
          choice('Plant half on the old terrace, and half on the poor-quarter\'s bare wall.', 'Eld looks at you, startled, then grins. "A garden for the ones who have none. Walker, the old gardeners would have hated it. Eld loves it."', 'flag:@garden_shared;loot:standard'),
        ] }),
    ],
    outro: 'The oldest terrace of Arenthia is green again. Within a season there will be roses where there was only wall, and the waterfall sings above them.',
    rewards: { gold: 420, loot: 'standard', items: [{ item: 'nirncraft:bosmer_helmet', name: 'Gardener\'s Sun-Hood', enchant: { protection: 2, respiration: 1 } }] },
  });

  f.q({
    id: 'the_prayer_behind_the_water', title: 'The Prayer Behind the Water', region: 'valenwood/reapers_march', start: 'npc:@sel', requires: ['level:10'],
    offer: 'You pray from behind a curtain of water.',
    pitch: 'It is easier, walker. Sel has prayed behind the falls for eleven years. Last month, the water changed. It carries an echo now. Sel says a prayer and hears it answered, not in her voice. She does not know if it is Y\'ffre or something older. She would like someone to stand where she stands and listen.',
    desc: 'Priestess Sel hears her prayers answered in someone else\'s voice from behind the waterfall. She wants a witness.',
    objectives: [
      o.go('lorion_falls', 'Stand behind the falls at Lorion Falls and listen'),
      o.cond('night', 'Wait until night for the voice to answer', 'lorion_falls'),
      o.talk('@sel', 'Tell Sel what you heard',
        'It answered. In your own voice, Sel, but a year older. It said: do not stop.',
        'Sel is still. Then, very quietly: "My own voice. A year older. Walker, it is not Y\'ffre. It is Sel, the next year\'s Sel, calling back. She says do not stop. Then Sel will not. Thank you. You have given me a very great gift: a reason to continue."'),
    ],
    outro: 'Sel prays every morning behind the falls, and every morning the water answers in a voice a year older, with something very like a smile.',
    rewards: { gold: 180, loot: 'minor' },
  });

  // ------------------------------------------------------------------ MARBRUK
  f.npc('cael', 'Ranger Cael', 'hunter', 'marbruk', 6, 5,
    'Cael walks the Thorn Wall at dusk and again at dawn and, once, in the middle of the night, because he felt a draught. The wall is very proud of him. He is not sure the wall understands.');
  f.npc('teva', 'Cook Teva', 'butcher', 'marbruk', -7, 6,
    'Teva cooks for the Watch Hall and listens to everyone. She never repeats a thing, except at the next table, in a different order. She calls it "tasting".', true);
  f.npc('huln', 'Smith Huln', 'blacksmith', 'marbruk', 9, -6,
    'Huln shoes horses, forges spearheads and mends pots. He says seven words a day, and spends all of them on the pot.');
  f.npc('thorn_inn', 'Aldis of the Thorn-and-Thistle', 'innkeeper', 'marbruk', -3, -10,
    'The Thorn-and-Thistle is a squat inn set into the Thorn Wall itself, its back room a hollow in the living hedge. Aldis says it is the safest tavern in Valenwood, because the wall itself will bite a brawler, and has.', true);

  f.relic('gap_stone', 'Gap-Stone', 'A hand-sized stone, smooth as an egg, wedged into a thorn-wall gap for years and worked loose only this week. The stone is cool, even in sunlight, and bears a chisel-mark no Bosmer smith would own.', 21);
  f.boss('wall_breaker', { name: 'Gurzun Wall-Breaker', type: 'nirncraft:troll', hp: 3.0, dmg: 1.5, level: 24, drop: 'loot:standard', glow: false });
  f.scene('mar_gap', [
    ['narrator', 'The Thorn Wall rises in a green-black tangle, each thorn as long as a finger. Near its base a hole has been worked through, neat as a doorway, and the hedge on either side has been bent back, not cut.'],
    ['@cael', 'Not cut. Bent. The Pact forbids a cut. Whoever made this knew the Pact, and used it. They did not break the law. They simply read it more carefully than we did.'],
  ]);

  f.q({
    id: 'the_thorn_wall', title: 'The Thorn Wall', region: 'valenwood/greenshade', start: 'npc:@cael', requires: ['level:12'],
    offer: 'Your wall looks sound. Your shoulders do not.',
    pitch: 'The wall is bent, walker. Three places this month. Not cut. Never cut. The Pact forbids it, and the wall would grow back in a week anyway. Someone bends it, and goes through, and goes out again, and takes something each time. A sheep. A sack of salt. Last week, a child\'s shoes. Cael does not know what they are practising for. He is afraid it is the real thing.',
    desc: 'The Thorn Wall of Marbruk has been bent in three places by someone who respects the Pact\'s laws and robs the town regardless. Ranger Cael wants the gap closed and the intruder caught.',
    objectives: [
      o.go('marbruk', 'Inspect the bent places in the Thorn Wall', { actions: 'scene:@mar_gap;relic:@gap_stone' }),
      o.cond('night', 'Hold the gap at night until the intruder returns', 'marbruk'),
      o.kill('marbruk', 'Stop Gurzun Wall-Breaker at the gap',
        [{ boss_id: '@wall_breaker' }]),
      o.talk('@huln', 'Take the gap-stone to Huln and ask what can seal it',
        'The wall has been bent in three places and the thief is dead. Can you seal them?',
        'Huln looks at the stone for a long while. Then, with the visible effort of a man who spends words like coins: "No iron. Thorn. Plant a thorn. Let it grow. Let the Pact do the work." He nods. That is all seven words. He folds his arms and says nothing more.',
        { choices: [
          choice('Seal it with new thorn, as the Pact asks.', 'Huln nods once. The thorn will take a season to grow, but it will be the wall\'s own, and no one will ever bend it.', 'flag:@wall_thorn'),
          choice('Weave an iron cage over it. The Pact can forgive iron in a crisis.', 'Huln looks pained. He weaves the cage anyway, and the thorn will grow around it. A compromise, and an ugly one, but strong.', 'flag:@wall_iron;loot:standard'),
        ] }),
    ],
    outro: 'The Thorn Wall stands whole. A season from now the new thorn will have grown over the gap, and no one will remember where it was bent.',
    rewards: { gold: 460, loot: 'standard' },
  });

  f.q({
    id: 'tevas_tasting_menu', title: 'Teva\'s Tasting Menu', region: 'valenwood/greenshade', start: 'npc:@teva', requires: ['level:8'],
    offer: 'You taste like someone who is very hungry.',
    pitch: 'Hungry is a good start, walker. Teva has been asked to cook a feast for a visiting Warden, and the Warden is a very fussy eater who will only eat meat, which is the Pact, and who will only eat meat that was properly named, which is a thing Teva has never heard of. Find the three beasts she has asked for. A boar, a stag, a hare. Properly named.',
    desc: 'Cook Teva must serve a fussy Pact-Warden three properly-named beasts. She needs a boar, a stag and a hare from the wilds near Marbruk.',
    objectives: [
      o.go('shrouded_vale', 'Hunt a boar in the Shrouded Vale'),
      o.retrieveItem('shrouded_vale', 'Bring back three portions of game', 'minecraft:porkchop', 3),
      o.talk('@teva', 'Deliver the game to Teva',
        'Three portions. A boar, a stag, a hare. Named by the hunter, as the Pact demands.',
        'Teva looks them over and tastes, very carefully, a single crumb. "Named. Properly. You have done this before. Walker, you will eat first tonight, and no one at the Warden\'s table shall be told. It is the best feast Marbruk has had in a decade, and you are not in the book."'),
    ],
    outro: 'The Warden ate in silence, then asked for the cook. Teva was never seen to blush before or since.',
    rewards: { gold: 160, loot: 'minor' },
  });

  // ------------------------------------------------------------------ MINORS
  f.npc('bough_host', 'Host Tamlin of the Green Bough', 'innkeeper', 'roadside_green_bough', 2, 3,
    'Tamlin runs the Green Bough, an inn in a tree whose bough grows another floor every year. He has stopped adding rooms and started adding apologies. Nobody knows why the tree does it.');
  f.npc('drabul_chief', 'Chieftain Borgakh of Drabul', 'steward', 'drabul', 3, 3,
    'Borgakh holds the Orc outpost in the forest, in an uneasy truce with the Bosmer neighbours. He has never once raised his voice. He has never once needed to.');
  f.npc('jath_champion', 'Champion Mauhulakh', 'jarl', 'jathsogur', 2, 2,
    'Mauhulakh leads Jathsogur\'s stronghold and wants a trial of strength witnessed by someone who owes the clan nothing. He is fair. He is also very, very large.');
  f.npc('monk_singer', 'Brother Hael of Dokrin', 'priest', 'dokrin_monastery', 3, -3,
    'Brother Hael has sung the same hymn for three days and nights and cannot stop. His voice has gone to a croak. His brothers sing in shifts. All of them look horrified.');
  f.npc('prophecy_hermit', 'Hermit Orlen', 'scholar', 'supplication_house', -3, 2,
    'Orlen carves one line of a prophecy per year on his doorframe and has done so for forty years. He says he has not decided what it will say. The line is not up to him.');
  f.npc('bridge_feuder', 'Feuding Wife Maral', 'citizen', 'sren_ja', 2, -3,
    'Maral says the shared bridge-plank is hers. Her neighbour says it is his. They have each been sweeping the plank every dawn for six years, in opposite directions.', true);

  f.relic('boughs_acorn', 'Acorn of the Growing Bough', 'An acorn the size of a plum, from the bough that adds a floor every year. It is warm, and in your palm it twitches faintly, as if wanting to be planted.', 20);
  f.relic('plank_deed', 'Deed to the Disputed Plank', 'A neatly-worded deed dated three generations ago, giving the shared plank of Sren Ja to \'whoever sweeps it first\'. A smudge on the bottom corner could be a stamp, or a thumb.', 11);

  f.q({
    id: 'the_bough_that_builds', title: 'The Bough That Builds', region: 'valenwood/reapers_march', start: 'npc:@bough_host', requires: ['level:10'],
    offer: 'Your inn seems to be growing without a builder.',
    pitch: 'Every year, walker. A new floor. No hands. No axes. The bough simply puts out another storey, always on the first night of Rain\'s Hand. Tamlin has stopped counting rooms. Last night, for the first time, the new floor grew a door facing the sky. A door in the ceiling, with no stair. Tamlin does not wish to open it. He would like you to.',
    desc: 'The Green Bough inn has grown a door in its new ceiling, with no stair leading up to it. Host Tamlin would like it opened by someone else.',
    objectives: [
      o.go('roadside_green_bough', 'Climb to the top floor of the Green Bough'),
      o.cond('night', 'Wait until night, when the door is said to open on its own', 'roadside_green_bough'),
      o.retrieve('roadside_green_bough', 'Take what waits behind the ceiling door', '@boughs_acorn'),
      o.talk('@bough_host', 'Show Tamlin the acorn',
        'There was an acorn behind the door. It twitched. The bough wants it planted.',
        'Tamlin turns the acorn in the lamp-light. "An acorn. That is the whole reason. The bough has been building a floor a year to say: plant me. Plant me nearby, so the bough can have a child to look at. Walker, Tamlin will plant it at the edge of the yard. And the inn will stop growing, I think, when the child is tall."'),
    ],
    outro: 'An acorn was planted by the yard-gate at the Green Bough, and the inn has not grown a floor since. The bough, they say, hums in the evenings.',
    rewards: { gold: 240, loot: 'minor' },
  });

  f.q({
    id: 'the_truce_of_drabul', title: 'A Truce at Drabul', region: 'valenwood/reapers_march', start: 'npc:@drabul_chief', requires: ['level:16'],
    offer: 'Your hall is quiet for an Orc outpost.',
    pitch: 'It is, walker, because Borgakh has told his warriors not to touch a thing for three days. A Bosmer hunting-party has gone missing between Drabul and the village across the stream, and both sides think the other has taken them. Borgakh has the truce to think of, and no wish to start a war over a lost sheep-boy. Find the party. Quickly. Quietly.',
    desc: 'A Bosmer hunting-party has vanished between Drabul and a nearby village, and each side blames the other. Chieftain Borgakh wants the truth found before blood is spilled.',
    objectives: [
      o.go('arorin_ruins', 'Search the Arorin Ruins where the hunters were last seen'),
      o.clear('arorin_ruins', 'Drive off the wolves that have scattered the hunters',
        [mob('nirncraft:wolf', 4, 'Pack Wolf'), mob('nirncraft:werewolf', 1, 'Pale Pack-Mother')]),
      o.escort('@drabul_chief', 'drabul', 'Walk Borgakh to the village to meet the hunters and settle the matter'),
    ],
    outro: 'The hunting-party was found alive and half-eaten by embarrassment. Orc and Bosmer shared one very awkward feast, and the truce of Drabul has never been stronger.',
    rewards: { gold: 460, loot: 'standard' },
  });

  f.q({
    id: 'trial_by_a_stranger', title: 'Trial by a Stranger', region: 'valenwood/reapers_march', start: 'npc:@jath_champion', requires: ['level:18'],
    offer: 'You wish a witness, not a challenger.',
    pitch: 'A witness. You owe the clan nothing, and so you may see it without a bias. Jathsogur has two sons who each claim the right to lead after Mauhulakh. They will duel at dawn. The loser lives, the winner leads. Mauhulakh has agreed to abide by the stranger\'s word on whether the duel was fair. He will not say which son he hopes will win. He will say only that he hopes it is fair.',
    desc: 'Champion Mauhulakh wants a stranger to witness a duel between his two sons and judge whether it was fought fairly.',
    objectives: [
      o.cond('night', 'Wait for dawn at the Jathsogur dueling-ground', 'jathsogur'),
      o.kill('jathsogur', 'Stop the duel when the younger son breaks the rules',
        [mob('npc:thug', 1, 'Younger Son Grakhul', { hostile: true })]),
      o.talk('@jath_champion', 'Tell Mauhulakh what you saw',
        'The younger son struck from behind. The elder gave him a chance to yield. I stopped it before blood.',
        'Mauhulakh listens without moving. "Struck from behind. A thing a son of mine should not do. And the elder gave a chance. That is all a father needs to hear. Walker, the clan owes you nothing, and has never been so grateful."',
        { choices: [
          choice('Name the elder the rightful heir.', 'Mauhulakh bows his head. "It is as I hoped, and I am sorry for the younger. He will have the forge, and a lesson."', 'flag:@duel_elder'),
          choice('Name neither. Let the clan decide in a month.', 'Mauhulakh grunts, then slowly smiles. "A month. A cooler head. You are wiser than a hundred witnesses."', 'flag:@duel_wait;loot:standard'),
        ] }),
    ],
    outro: 'The stronghold of Jathsogur has a new heir, or a new month. The stranger who witnessed it was thanked with a full cup and an empty promise.',
    rewards: { gold: 540, loot: 'standard' },
  });

  f.q({
    id: 'the_hymn_that_wouldnt_end', title: 'The Hymn That Would Not End', region: 'valenwood/reapers_march', start: 'npc:@monk_singer', requires: ['level:14'],
    offer: 'Your voice is a croak.',
    pitch: 'Three days, walker. The hymn of the third hour. It began at dawn and none of us has been able to stop. We do not know why. If one monk falls silent, the others weep. We sing in shifts and still cannot rest. We think it is the harp in the vault. It began to play on the first morning, and has not been silent since. Silence it. Please.',
    desc: 'The monks of Dokrin cannot stop singing the same hymn. A harp in the monastery vault seems to be the cause.',
    objectives: [
      o.go('dokrin_monastery', 'Descend to the monastery vault where the harp plays'),
      o.clear('dokrin_monastery', 'Drive off the shades that dance to the harp',
        [mob('nirncraft:will_o_wisp', 2, 'Hymn-Dancer'), mob('minecraft:skeleton', 2, 'Harp-Bone')], { placement: 'structure' }),
      o.talk('@monk_singer', 'Tell Brother Hael the harp is still',
        'The harp is silent. I unstrung it. It will not be heard again.',
        'Brother Hael tries to speak and nothing comes. He stands, mute, for a very long time. Then he bows low, and the other monks bow, and a silence falls on the monastery for the first time in three days that is not a silence of fear. It is a silence of rest.'),
    ],
    outro: 'The monks of Dokrin slept for a day and a night. When they woke, the first thing they sang was a different hymn: very short, and very grateful.',
    rewards: { gold: 330, loot: 'standard' },
  });

  f.q({
    id: 'the_line_a_year', title: 'One Line a Year', region: 'valenwood/reapers_march', start: 'npc:@prophecy_hermit', requires: ['level:12'],
    offer: 'Your doorframe is a very long sentence.',
    pitch: 'Forty years, walker. A line a year. It began as a joke, a hermit\'s scribble. Then the lines started coming true. A flood in the ninth year. A king\'s death in the twelfth. A lost brother returned in the twenty-sixth. Orlen has a line for this winter, and he does not want to carve it. He wants you to go and see if it is already true.',
    desc: 'Hermit Orlen has a line of prophecy he does not wish to carve. He asks a stranger to see whether it has already come true.',
    objectives: [
      o.talk('@prophecy_hermit', 'Read the unfinished line on the doorframe',
        'What does the line say?',
        'Orlen\'s paws tremble. "\'The stranger at the door will carry the burden of the last line.\' Walker, you are the stranger. You are at the door. And Orlen does not know what the last line is, only that it is you who will carve it. Take the chisel. Please. Say what you will."',
        { choices: [
          choice('Carve: \'The hermit rests.\'', 'Orlen reads it and sags with relief. "The hermit rests. Yes. After forty years. Thank you. I will go and see the sea."', 'flag:@line_rest'),
          choice('Carve: \'The doorframe is just wood.\'', 'Orlen stares, then laughs until he has to sit. "Just wood! Yes! Forty years I carved, and was afraid of wood. Walker, you are a very rude and very wise stranger."', 'flag:@line_wood;loot:minor'),
        ] }),
      o.go('supplication_house', 'Return to the supplication house at dawn to see what the prophecy has done'),
    ],
    outro: 'A prophecy of forty years ended with a stranger at a door. Whatever the last line said, the hermit was free.',
    rewards: { gold: 220, loot: 'minor' },
  });

  f.q({
    id: 'the_plank_of_sren_ja', title: 'The Plank of Sren Ja', region: 'valenwood/reapers_march', start: 'npc:@bridge_feuder', requires: ['level:8'],
    offer: 'You look very tired of sweeping.',
    pitch: 'Six years, walker. Six years. He sweeps from the east, she from the west, and every dawn they meet in the middle and say nothing, and sweep the dust back where it came from. Each of them knows the other is wrong. The deed says whoever sweeps first. The deed is not clear who that is. A third party might read the smudge at the bottom, and see what is really written there.',
    desc: 'Two neighbours of Sren Ja have each swept the same bridge-plank for six years. A deed with a smudged stamp might settle it, if someone neutral reads it.',
    objectives: [
      o.go('old_sren_ja_docks', 'Find the old deed in the flooded record-house at Old Sren Ja Docks'),
      o.retrieve('old_sren_ja_docks', 'Recover the Deed to the Disputed Plank', '@plank_deed'),
      o.deliver('@bridge_feuder', '@plank_deed', 'Read the deed aloud to both neighbours',
        'The deed says the plank belongs to whoever sweeps first. The smudge, held to the light, is the stamp of a former reeve: it says: both. At the same time. Together.',
        'Maral stares at the deed. Across the bridge, her neighbour stares back. A very long silence follows. Then, haltingly, he lifts his broom. She lifts hers. They sweep the plank from opposite ends, and when they meet in the middle, they stop, and look at each other, and neither says a word. But both of them are smiling.'),
    ],
    outro: 'The plank of Sren Ja has been swept by two brooms at once for a month, and it has never been cleaner. They do not speak. They do not need to.',
    rewards: { gold: 210, loot: 'minor' },
  });
};
