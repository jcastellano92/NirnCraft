'use strict';
// Valenwood marquee content, part A: Elden Root, Falinesti, Woodhearth, Silvenar.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  // ------------------------------------------------------------------ ELDEN ROOT
  f.npc('lirael', 'Wardress Lirael', 'steward', 'elden_root', 5, 6,
    'Lirael holds the Root Gate and has counted every foot that has crossed it for nineteen years. She will not tell you the total. She says it would only embarrass you.', true);
  f.npc('beron', 'Sap-Tapper Beron', 'lumberjack', 'elden_root', -7, 8,
    'Beron taps the lower roots for the sap that lights every lantern in the city. He talks to the tree while he works, and the tree, he swears, has begun to talk back, mostly about the weather.');
  f.npc('aeth', 'Aeth, Voice of the Silvenar', 'scholar', 'elden_root', 12, -4,
    'Aeth speaks for a king who rarely speaks for himself. Every sentence is a courtesy and every courtesy is a measure of how much you are worth to the Court.');
  f.npc('acorn', 'Fenwe of the Hollow Acorn', 'innkeeper', 'elden_root', -4, -10,
    'The Hollow Acorn is a tavern built inside a knot of the great tree itself, with tables on three tiers and no two of them level. Fenwe serves meat only, as the Pact demands, and apologises for it to the tree each night.', true);

  f.relic('blight_sap', 'Phial of Black Sap', 'A glass phial of sap the colour of old tar. It smells faintly of rot and faintly, horribly, of honey. Birds will not land near it.', 23);
  f.relic('sound_wood_shard', 'Shard of Sound Heartwood', 'A palm-sized slab of pale heartwood, warm and faintly green, cut from the healthy trunk. It hums softly when held to your ear, like a sleeper breathing.', 20);
  f.boss('blight_heart', { name: 'The Blight-Heart of Elden Root', type: 'skycraft:spriggan', hp: 4.2, dmg: 1.6, level: 30,
    drop: 'loot:major', glow: true });
  f.boss('middens_fence', { name: 'Peddler Sorwe Rat-Tail', type: 'skycraft:bandit_chief', hp: 2.8, dmg: 1.3, level: 18,
    weapon: 'skycraft:bosmer_dagger', gear: { chest: 'skycraft:bosmer_chestplate' }, drop: 'loot:standard', glow: false });

  f.scene('er_tier_one', [
    ['narrator', 'Root-light falls in green bars across the market. Where the great roots arch overhead, the sap-lanterns burn amber, and every one of them flickers in time with a slow, uneven pulse.'],
    ['@beron', 'Hear that? Beron has tapped these roots for thirty years. The pulse was a heartbeat. Now it is a stutter. The tree is not asleep, walker. The tree is feverish.'],
  ]);
  f.scene('er_crown_rot', [
    ['narrator', 'In the Crown, a single bough has gone black. Its leaves hang like wet cloth, and each one is edged with a fine pale mould that glints like sugar.'],
    ['@aeth', 'The Silvenar has not been told. The Court believes a bough has merely died, as boughs do. It has not. It is being eaten from the inside, and it is eating back.'],
  ]);

  f.q({
    id: 'the_trees_fever', title: 'The Tree\'s Fever', region: 'valenwood/grahtwood', start: 'npc:@beron', requires: ['level:16'],
    offer: 'You sound as if you are listening to something.',
    pitch: 'The roots, walker. Listen. The lanterns pulse with the tree\'s own heart, and for a month now the beat has been wrong. Beron has tapped the first tier, the second tier, and the third. At each tier the sap runs a shade darker. Whatever is wrong began at the Crown and is running down. Climb with Beron\'s sap-gauge, tier by tier. Find the sick place. And then decide what is to be done with it, because Beron cannot.',
    desc: 'Sap-Tapper Beron says the Elden Tree is feverish. The sap darkens at each tier, and whatever began in the Crown is spreading down the trunk.',
    objectives: [
      o.go('elden_hollow', 'Test the sap at the root tier, in Elden Hollow', { actions: 'scene:@er_tier_one' }),
      o.go('the_orrery', 'Climb to the second tier, where the Orrery hangs in the boughs, and test the sap again'),
      o.go('elden_tree', 'Climb to the Crown of the Elden Tree', { actions: 'scene:@er_crown_rot;relic:@blight_sap' }),
      o.kill('elden_tree', 'Cut the Blight-Heart from the Crown bough',
        [{ boss_id: '@blight_heart' }, mob('skycraft:spriggan', 2, 'Blight-Sprout')]),
      o.talk('@aeth', 'Tell the Silvenar\'s Voice what you found in the Crown',
        'A blight was eating the Crown from within. Black sap, and a heart that was not the tree\'s own. It is cut out, and the bough is dying.',
        'Aeth is quiet for the length of a long breath. "The Pact forbids us to harm the living wood. You have cut a living bough, in the Silvenar\'s own city. By the letter, you have broken the oldest of our laws. By the spirit..." He stops. "The Court asks what you did it for. Say it plainly, and the Court will decide whether to thank you or to judge you."',
        { choices: [
          choice('I did it to save the tree. The Pact was written for a healthy forest.', 'Aeth lets out a breath. "A fair answer. Not the one the Court wants. But the one Aeth would have given. The Silvenar will hear it as you said it. He may even agree."', 'flag:@fever_defended'),
          choice('I did it because the tree was dying, and nobody else would.', 'Aeth\'s shoulders ease a hair. "Then you have done what the Pact\'s wardens could not. They will not forgive you for it. The tree might."', 'flag:@fever_honest;loot:standard'),
        ] }),
    ],
    outro: 'The Crown bough was cut, and the sap runs amber again from root to leaf. A law was bent, for the sake of a tree, and Elden Root has not yet decided whether to be grateful.',
    rewards: { gold: 800, loot: 'major', items: [{ item: 'skycraft:bosmer_bow', name: 'Heartwood Remembrance', enchant: { power: 3, unbreaking: 2 } }] },
  });

  f.q({
    id: 'sound_in_the_middens', title: 'The Sound of the Middens', region: 'valenwood/grahtwood', start: 'npc:@lirael', requires: ['level:12'],
    offer: 'Your gate has a bruise under its paint.',
    pitch: 'It has, walker. Seven lamps have gone missing from the Gate in a month. Not the oil. The lamps. Cut right out of their brackets, no damage to the wood. A Bosmer may not damage the wood, so whoever did it was a Bosmer, and a good one. And every lamp that vanishes turns up for sale in the Middens, for a quarter its price. Find the buyer. Find the seller. Find whoever is clever enough to cut without cutting.',
    desc: 'Seven Root Gate lamps were stolen without damage to the wood and are being sold cheaply in the Middens. Wardress Lirael wants the clever thief.',
    objectives: [
      o.go('the_middens', 'Search the Middens for the stolen Root Gate lamps'),
      o.kill('the_middens', 'Corner the fence, Peddler Sorwe Rat-Tail',
        [{ boss_id: '@middens_fence' }, mob('npc:thief', 2, 'Middens Cutpurse', { hostile: true })]),
      o.talk('@lirael', 'Tell Lirael who was cutting her lamps free',
        'The fence was Sorwe Rat-Tail. The cutter is a boy, a gate-lantern apprentice, who has a very good hand and a very bad master.',
        'Lirael sets her jaw. "A boy. A very good hand. Hm. The Pact says no harm to the living wood, and he never harmed it. He is a better thief than the Gate is a guard. Do you want him punished, or apprenticed?"',
        { choices: [
          choice('Apprentice him to the Gate. He has the hand for it.', 'Lirael nods slowly. "A thief at the gate is a lock that knows the key. Very well. He will sweep the stair for a year, and then perhaps trim lamps for a decade."', 'flag:@lamp_apprenticed'),
          choice('Fine him and let his master pay. The master is the real thief.', 'Lirael\'s ears flick. "Then the master will pay. In coin, in lamps and in the opinion of the Gate. A good answer. A harder one."', 'flag:@lamp_master_fined'),
        ] }),
    ],
    outro: 'The seven lamps hang in their brackets again, and a boy with a very good hand has been told that it is a gift, not a licence.',
    rewards: { gold: 320, loot: 'standard' },
  });

  f.q({
    id: 'the_root_festival_arrow', title: 'The Arrow of the Root Festival', region: 'valenwood/grahtwood', start: 'npc:@acorn', requires: ['level:10'],
    offer: 'The Hollow Acorn is awfully full for a weekday.',
    pitch: 'It is the Root Festival, walker! Three days of archery and three nights of arguments about archery. The prize is the Arrow, a gold-tipped heirloom, and the cup is all to the Hollow Acorn, where the winner drinks first. Fenwe has a cousin who has won it nine times and the other tables are muttering that she cheats. Fenwe is certain she does not. But she would be very glad to see someone else take the prize, just once, so the muttering stops.',
    desc: 'Fenwe wants someone to enter the archery contest of the Root Festival and take the Arrow, if only to quiet the muttering about her champion cousin.',
    objectives: [
      o.go('elden_hollow', 'Enter the archery contest at Elden Hollow'),
      o.kill('elden_hollow', 'Defeat the nine-time champion, Archer Lewen Nine-Arrows',
        [mob('npc:hunter', 1, 'Lewen Nine-Arrows', { hostile: true })]),
      o.talk('@acorn', 'Bring the Arrow back to Fenwe',
        'I won the Arrow of the Root Festival. Your cousin shot cleanly; she did not cheat. I was simply better, once.',
        'Fenwe\'s ears go flat, then up. "She did not cheat! Ha! Then the muttering will go on, but about you, which is entirely fair. Walker, drink first tonight. The Hollow Acorn bows to the Arrow, whoever holds it."'),
    ],
    outro: 'The Arrow of the Root Festival was won by a stranger, and Lewen Nine-Arrows took the defeat with a very straight back and a very fast second round.',
    rewards: { gold: 260, loot: 'minor', items: [{ item: 'skycraft:bosmer_bow', name: 'Festival Arrow-Bow', enchant: { power: 1 } }] },
  });

  // ------------------------------------------------------------------ FALINESTI
  f.npc('maelin', 'Root-Tender Maelin', 'steward', 'falinesti', 5, -5,
    'Maelin tends the great roots that carry Falinesti across the forest, one slow step each season. He knows when it will turn, and he is never wrong, and he is never glad to say.');
  f.npc('eri', 'Lorekeeper Eri', 'scholar', 'falinesti', -6, 6,
    'Eri carries the memory of every site the city has stood on. She can tell you which spring flood took a ford and which autumn fire took a copse. She never forgets, and neither, she says, does the tree.', true);
  f.npc('hael', 'Pilgrim Hael', 'adventurer', 'falinesti', 9, 7,
    'Hael has been following the walking city for eleven years. He has never once arrived at the same time as it. He is very patient and very, very tired.');
  f.npc('bough_inn', 'Ilwen of the Four Seasons\' Bough', 'innkeeper', 'falinesti', -3, -9,
    'The Four Seasons\' Bough is a tavern in the city\'s first limb. It has been moved eleven times and never once re-hung its sign. Ilwen says that is because the city would only move it again.', true);

  f.relic('root_ring', 'Ring of the Root-Tender', 'A hoop of living wood, never withered, worn by every Root-Tender since the first. It is warm in the hand and pulses faintly, like a patient waiting.', 26);
  f.relic('autumn_acorn', 'Acorn of the Autumn Site', 'A single acorn as large as a fist, hard as iron and faintly gold. The Lorekeeper says the city planted it, once, on its first autumn, and forgot where.', 20);
  f.boss('river_wraith', { name: 'The Drowned Ford-Warden', type: 'skycraft:will_o_wisp', hp: 4.0, dmg: 1.5, level: 28, drop: 'loot:major', glow: true });
  f.scene('fal_crossing', [
    ['narrator', 'The roots go under the water one at a time, groping like fingers into dark. Each root takes its hold, then draws the city after it. Along the bank the pilgrims stand silent, afraid to breathe.'],
    ['@maelin', 'This is the crossing, walker. A hundred seasons ago it was a ford. Now it is a river with a memory. Something below remembers being a ford, and it does not wish to be crossed.'],
  ]);

  f.q({
    id: 'chasing_the_walk', title: 'Chasing the Walk', region: 'valenwood/malabal_tor', start: 'npc:@hael', requires: ['level:18'],
    offer: 'You look like someone who has been walking behind something.',
    pitch: 'Eleven years, walker. Hael has followed Falinesti from spring to summer to autumn to winter and back, and has never once been at the gate when it opened. Always a day late, always a day early. He has made a bet with himself. Today he will stand where the city will be, not where it is. He needs someone to take the crossing with him. He does not like water.',
    desc: 'Pilgrim Hael has chased Falinesti for eleven years. He wants to ride the city across a river crossing, and needs a companion for the water.',
    objectives: [
      o.go('falinesti_spring_site', 'Reach the Spring site before the city leaves it'),
      o.go('falinesti_summer_site', 'Travel to the Summer site with the walking city'),
      o.cond('night', 'Wait until night, when the roots move', 'falinesti_summer_site', { actions: 'scene:@fal_crossing' }),
      o.kill('falinesti_summer_site', 'Defend the crossing from the Drowned Ford-Warden',
        [{ boss_id: '@river_wraith' }, mob('minecraft:drowned', 3, 'Ford-Drowned')]),
      o.talk('@hael', 'Find Hael on the far bank',
        'We crossed. The city is on the other side of the river now. Hael, the road ends here. Will you settle, or keep walking?',
        'Hael sits down on the bank like a man who has put down a very heavy parcel. "Eleven years. Hael has been so busy following that he never thought what he would do on arrival. A house? A garden? He has never owned a door. What would you do, walker?"',
        { choices: [
          choice('Settle. Let the city carry you now.', 'Hael closes his eyes. "Then a door. A small one, on the second branch. Hael will plant a tomato and tell it all about the road. Thank you for the crossing."', 'flag:@hael_settled'),
          choice('Keep walking. The city is not the whole forest.', 'Hael laughs with sudden joy. "Not the whole forest! Of course. There is Reaper\'s March, and the coast, and the Orcs of Drabul, who owe Hael a song. Walker, Hael will walk to the end of the world in your honour."', 'flag:@hael_walks;loot:standard'),
        ] }),
    ],
    outro: 'A pilgrim of eleven years finally arrived. What he did next was his to choose, and the walking city, patient as ever, moved on.',
    rewards: { gold: 740, loot: 'major' },
  });

  f.q({
    id: 'the_season_the_city_forgot', title: 'The Season the City Forgot', region: 'valenwood/malabal_tor', start: 'npc:@eri', requires: ['level:14'],
    offer: 'Your memory looks heavier than your satchel.',
    pitch: 'It is, walker. Falinesti has stood on four sites, in four seasons, for a thousand years. But Eri has a fifth in her notes. A season the city stood somewhere none of the sites are, and no chronicle records. A season that was cut out of the record, by someone with a knife and a very good reason. Eri wants to know what the city did during it. Whatever it was, the roots remember.',
    desc: 'Lorekeeper Eri has found evidence of a fifth season, deliberately cut from the chronicles of the walking city. The autumn site may remember it.',
    objectives: [
      o.go('falinesti_autumn_site', 'Visit the Autumn site and search for what the chronicles cut'),
      o.retrieve('falinesti_autumn_site', 'Find the buried Acorn of the Autumn Site', '@autumn_acorn'),
      o.deliver('@eri', '@autumn_acorn', 'Show the acorn to Eri',
        'This acorn grew from a seed the city dropped on a season it did not record. It is a fifth planting.',
        'Eri turns the acorn over twice, and her breath catches. "A planting. The city dropped it deliberately. In the fifth season, it did not walk. It stopped, and grew a thing, and that thing was a grove. Walker, there is a grove somewhere that the city planted for itself. And the Tenders cut the season from the record to stop anyone from finding it."',
        { choices: [
          choice('Write it into the chronicles. Let the grove be found.', 'Eri inks the margin with a steady hand. "Then it is written, for the first time. Some will go looking. Some should. The city will not mind."', 'flag:@season_written'),
          choice('Keep the secret. The city planted it for itself.', 'Eri closes her notebook gently. "Then it stays between you, the acorn and me. A walking city is entitled to one thing it has not told. Thank you for the hard choice."', 'flag:@season_kept'),
        ] }),
    ],
    outro: 'A fifth season of Falinesti is known to two people, or to everyone, depending on whom you ask.',
    rewards: { gold: 460, loot: 'standard' },
  });

  // ------------------------------------------------------------------ WOODHEARTH
  f.npc('tilu', 'Bridge-Warden Tilu', 'steward', 'woodhearth', 6, 5,
    'Tilu walks the Great Weave every dawn with a mallet and a coil of rope, checking each plank by ear. She says a sound bridge sings a high note, and a tired one sings low. She has never once been wrong about a bridge.', true);
  f.npc('orwen', 'Net-Trader Orwen', 'merchant', 'woodhearth', -8, 6,
    'Orwen sells nets, ropes and woven baskets in Fisher\'s Hang. He is generous with prices, stingy with news, and has the fastest knots in Greenshade. He says a net is only a promise that something will not get away.');
  f.npc('hush', 'Elder Hush of the Ground-Camp', 'citizen', 'woodhearth', 3, 12,
    'Hush speaks for the families who live beneath the bridges and have never been invited above them. She is the quietest woman in Woodhearth, and the only one nobody has ever interrupted.', true);
  f.npc('slack_rope', 'Brenna of the Slack Rope', 'innkeeper', 'woodhearth', -2, -8,
    'The Slack Rope hangs from three trees and sways on windy nights. Brenna serves the soup in lidded bowls and the ale in mugs bolted to the table. She says the regulars are very steady drinkers. She says the visitors are very quick learners.', true);

  f.relic('rotted_knot', 'Rotted Node-Knot', 'A bridge-knot of black twisted fibre, cut from a failing support. Where it touched the rope, someone has packed it with a pale, damp paste that smells of wet mushrooms.', 23);
  f.relic('ground_camp_rope', 'Ground-Camp Rope', 'A long coil of oiled, hand-twisted rope, as thick as a wrist and smelling faintly of tar. The ground-camp spins it by moonlight. A tag on one end says, in a child\'s hand: \'For the Weave, with love\'.', 11);
  f.boss('weave_saboteur', { name: 'Raik Mushroom-Hand', type: 'skycraft:bandit_chief', hp: 2.9, dmg: 1.3, level: 20,
    weapon: 'skycraft:bosmer_dagger', gear: { head: 'skycraft:bosmer_helmet' }, drop: 'loot:standard', glow: false });
  f.scene('woo_node', [
    ['narrator', 'Twelve stories up the Weave, a main-support node sways in the wind. Its great knot is soft and black, and when Tilu\'s mallet strikes it the sound is a dull, sick thud.'],
    ['@tilu', 'Hear that? A sound knot sings. That one wheezes. Someone has been feeding it. A knot does not rot in a month unless it is helped.'],
  ]);

  f.q({
    id: 'the_fraying', title: 'The Fraying', region: 'valenwood/greenshade', start: 'npc:@tilu', requires: ['level:14'],
    offer: 'Your mallet looks worried.',
    pitch: 'It is, walker. Three nodes on the Weave have gone soft in a month, and none of them near water, none of them near any rot. A bridge-knot rots when it is helped. Somebody has been feeding them, with a paste that smells of mushrooms, and whoever it is climbs better than any warden. Tilu would catch them herself, but she is afraid of heights. She does not tell anyone.',
    desc: 'Bridge-Warden Tilu says three of Woodhearth\'s main bridge-knots have been rotted on purpose. Someone is climbing the Weave and feeding the nodes with a mushroom paste.',
    objectives: [
      o.go('woodhearth', 'Climb the Great Weave and inspect the soft node', { actions: 'scene:@woo_node;relic:@rotted_knot' }),
      o.cond('night', 'Wait in the shadow of the hub until the saboteur climbs', 'woodhearth'),
      o.kill('woodhearth', 'Catch the saboteur on the Weave',
        [{ boss_id: '@weave_saboteur' }]),
      o.talk('@hush', 'Ask Elder Hush why the ground-camp\'s children were seen on the Weave',
        'The saboteur, Raik, said the camp\'s children have been hauling mushrooms up the Weave. Why would they do that?',
        'Hush is silent for the length of a long breath. "Because nobody listens to those who live below. Raik told the children it would make the bridges sing, and they believed him. They are cubs, walker. They have been told for years the bridges are not for them. Now someone has made the bridges ask for them."',
        { choices: [
          choice('Have the ground-camp rebuild the Weave. Let them earn the right to cross it.', 'Hush\'s eyes shine. "A bridge built by the hands that have been told they cannot cross. Yes. The camp will weave a rope that will outlast the Warden herself. And the Warden will have to look at us."', 'flag:@weave_camp_rebuilds'),
          choice('Pay the camp to repair the nodes, and bring Tilu in to oversee.', 'Hush nods. "A fair wage and a fair eye. Not quite a victory. A beginning. Thank you."', 'flag:@weave_paid;loot:standard'),
        ] }),
    ],
    outro: 'Raik Mushroom-Hand was cut down from the Weave, and the ground-camp\'s children, to their great astonishment, were handed ropes and told they were wanted.',
    rewards: { gold: 560, loot: 'standard', items: [{ item: 'skycraft:bosmer_dagger', name: 'Warden\'s Mallet-Knife', enchant: { sharpness: 2, unbreaking: 2 } }] },
  });

  f.q({
    id: 'the_net_that_caught_nothing', title: 'The Net That Caught Nothing', region: 'valenwood/greenshade', start: 'npc:@orwen', requires: ['level:10'],
    offer: 'A net-trader who looks bothered is a rare thing.',
    pitch: 'Orwen has woven nets for forty years, walker. This one he did not weave. It arrived in a crate with no sender, wound around a single stone. It catches nothing. Fish, birds, flies, nothing. A net that catches nothing is not a net. Orwen would like to know what it is a net for.',
    desc: 'Net-Trader Orwen received an unmarked net that catches nothing. The stone it came wrapped around may tell what it was meant to catch.',
    objectives: [
      o.go('echo_pond', 'Cast the net into Echo Pond and see what it does'),
      o.cond('night', 'Wait at the pond until the net stirs', 'echo_pond'),
      o.kill('echo_pond', 'Kill what the net has caught in the still water',
        [mob('skycraft:will_o_wisp', 1, 'Netted Marsh-Light', { target: true })]),
      o.talk('@orwen', 'Tell Orwen what the net was meant to catch',
        'It was made to catch lights. A will-o-wisp, a marsh-light. Someone wanted one alive, and sent the net to the best net-trader in Greenshade.',
        'Orwen pulls at his ear. "Lights. Of course. A net for lights does not catch fish. It catches, hm, a different kind of prey. The weaver would be a Wispmother\'s apprentice, or a bored witch. Orwen will weave a second net, the same, and send it on, for the sender\'s sake. They will need two."'),
    ],
    outro: 'A net that catches nothing has caught something after all, and Orwen has begun to wonder who is knitting at the other end of the string.',
    rewards: { gold: 230, loot: 'minor' },
  });

  // ------------------------------------------------------------------ SILVENAR
  f.npc('scribe', 'The Silvenar\'s Scribe Ethelwe', 'scholar', 'silvenar', 6, -4,
    'Ethelwe writes every word the Silvenar has said in public, and a few he has not. She wears a ring of ink-stains on her left index finger, and says it is the only ring a scribe should ever wear.', true);
  f.npc('loru', 'Archivist Loru', 'scholar', 'silvenar', -7, 5,
    'Loru keeps the Root Archive, where scrolls hang from rafters in net-bags like fruit. He can find anything in it, so long as he is not asked to find it quickly.');
  f.npc('gatewarden', 'Gate-Warden Wren', 'steward', 'silvenar', 10, 6,
    'Wren keeps the Firefly Gate and is in a permanent mood about it. She says the gate is too wide, the flies too bright and the visitors too numerous. She would prefer the city were found by no one.', true);
  f.npc('firefly_lantern', 'Pip of the Firefly Lantern', 'innkeeper', 'silvenar', -3, -9,
    'The Firefly Lantern is the only tavern in Silvenar, and it is lit entirely by jars of captive fireflies that are released on the stroke of midnight, in a ceremony that irritates the fireflies enormously. Pip is sorry. Pip does it anyway.');

  f.relic('silvenar_scroll', 'The Unwritten Scroll', 'A tightly rolled scroll bound in a ribbon of silver bark. No one has ever read it; every scribe who unrolls it finds the page blank, and every scribe who rolls it up again swears it was not.', 15);
  f.relic('audience_token', 'Token of Audience', 'A small wooden disc stamped with the Silvenar\'s leaf and carved with a single word: WAIT. It is warm to the touch and, held up to the light, it flickers.', 26);
  f.boss('archive_guardian', { name: 'The Root-Wrapped Archivist', type: 'skycraft:draugr_deathlord', hp: 3.6, dmg: 1.5, level: 28,
    weapon: 'skycraft:bosmer_sword', gear: { head: 'skycraft:bosmer_helmet' }, drop: 'loot:major', glow: true });
  f.scene('sil_gate', [
    ['narrator', 'The mist parts on a wall of living root. Fireflies drift in lazy loops about a single arched gap no wider than a man, lit from within by a slow green glow.'],
    ['@gatewarden', 'You came by the east path. Nobody comes by the east path. It is not on any map. Either you are very lost or very well informed, and I do not know which is more alarming.'],
  ]);

  f.q({
    id: 'the_silent_court', title: 'The Silent Court', region: 'valenwood/malabal_tor', start: 'npc:@scribe', requires: ['level:16'],
    offer: 'You write as if in a hurry.',
    pitch: 'The Silvenar has not spoken in public for nine days, walker. Ethelwe writes down everything he says, and for nine days the page has been blank. The Court says he is meditating. Ethelwe thinks he is afraid. There is a scroll in the Root Archive that he asked for on the tenth night, and has not returned. If you can enter the Court and reach the Archive, bring it to me. Before anyone else reads what is on it.',
    desc: 'The Silvenar has been silent for nine days. Ethelwe suspects the cause is a scroll he borrowed from the Root Archive, which she wants retrieved.',
    objectives: [
      o.go('silvenar_vale', 'Find the east path to the Firefly Gate', { actions: 'scene:@sil_gate' }),
      o.talk('@gatewarden', 'Win an audience at the Firefly Gate',
        'I carry word from the Silvenar\'s Scribe. I would like to enter, quietly, and speak with the Archivist.',
        'Wren looks at you for a very long, flat moment. "The Scribe\'s word is the Scribe\'s. Mine is mine. You will walk the south stair and not the north. You will not touch the fireflies, and you will not, under any circumstances, speak to the root-wrapped thing in the bottom archive."',
        { actions: 'relic:@audience_token' }),
      o.go('roots_of_silvenar', 'Descend the south stair to the Root Archive'),
      o.kill('roots_of_silvenar', 'Defeat the Root-Wrapped Archivist that guards the lowest stacks',
        [{ boss_id: '@archive_guardian' }], { placement: 'structure', actions: 'relic:@silvenar_scroll' }),
      o.deliver('@scribe', '@silvenar_scroll', 'Bring the scroll to Ethelwe',
        'I have your scroll. The page is blank. Whoever wrote it did so for a reader who has not yet been born.',
        'Ethelwe takes the scroll with unsteady fingers and does not unroll it. "Blank. Then it is the Silent Scroll, the one that records what the Silvenar will say, not what he has. He asked for it so he would know. I think he has known, and it frightened him. Will you have me burn it, or put it back?"',
        { choices: [
          choice('Burn it. A king should not be told what he will say.', 'Ethelwe lays the scroll on a lamp and watches it curl. "The page was blank, and now it is ash, and the Silvenar will speak tomorrow, and not know what. It is a mercy. Perhaps."', 'flag:@scroll_burned'),
          choice('Put it back. Let him read it when he is ready.', 'Ethelwe ties the ribbon again with reverent care. "A kinder cruelty. Perhaps he will read it. Perhaps he will not. It will wait. Thank you."', 'flag:@scroll_returned;loot:standard'),
        ] }),
    ],
    outro: 'The Silvenar spoke the next morning, brief and clear, as if a weight had left his shoulders. Whether the scroll was ash or shelf-bound, Silvenar had decided to be unafraid.',
    rewards: { gold: 720, loot: 'major' },
  });

  f.q({
    id: 'a_debt_of_fireflies', title: 'A Debt of Fireflies', region: 'valenwood/malabal_tor', start: 'npc:@firefly_lantern', requires: ['level:10'],
    offer: 'The jars have gone dim, Pip.',
    pitch: 'They have, walker, and it is not the glass. The fireflies are sulking, and no firefly sulks for a month unless its relatives have been shut away. Someone has jarred the wild swarm that lives by the audience hall, and the tavern\'s own flies are sulking in sympathy. Pip has no idea who. Pip is going to be sick.',
    desc: 'Pip\'s tavern fireflies are sulking because someone has jarred the wild swarm near the audience hall. He wants the swarm released.',
    objectives: [
      o.go('silvenars_audience_hall', 'Search the grounds of the audience hall for the jarred swarm'),
      o.kill('silvenars_audience_hall', 'Stop the collector who jarred the swarm',
        [mob('npc:thief', 1, 'Lantern-Seller Thorn', { hostile: true })]),
      o.talk('@firefly_lantern', 'Tell Pip the swarm is free',
        'The swarm is free. The collector was a lantern-seller who did not care for the midnight ceremony.',
        'Pip claps his paws to his chest in relief. "Free! Oh, the poor things. And Pip, who thought he was the villain! I have been releasing them on the stroke of midnight and they hate it, and I have never asked. Walker, from tonight, they will go out whenever they like. The Firefly Lantern will be as dark as it pleases."'),
    ],
    outro: 'The fireflies of Silvenar were freed by a stranger and a very contrite tavern-keeper. The midnight ceremony has been quietly retired.',
    rewards: { gold: 190, loot: 'minor' },
  });
};
