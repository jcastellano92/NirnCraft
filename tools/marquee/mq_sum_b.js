'use strict';
// Summerset marquee content, part B: Dusk, Shimmerene, Firsthold, Artaeum, minor places.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  // ------------------------------------------------------------------ DUSK
  f.npc('sen', 'Lamp-Keeper Sen', 'citizen', 'dusk', 5, 6,
    'Sen lights the lamps of Dusk at the first purple hour and puts them out at the first grey one. He knows every lamp by name and calls the dim ones "the sleepy". He has not been seen in daylight since the winter of his wedding.');
  f.npc('eilen', 'Harbourmaster Eilen', 'steward', 'dusk', -7, 5,
    'Eilen keeps the quay of Dusk with a voice like a closing door. She speaks seldom and never repeats herself. When she does speak, ships turn.', true);
  f.npc('orla', 'Pale Merchant Orla', 'merchant', 'dusk', 9, -6,
    'Orla sells bone-white lace, lilac wax and candles that burn the colour of twilight. She only opens her stall after sunset, and no one has ever seen her eat. Customers describe her as "restful".', true);
  f.npc('lilac_quill', 'Wenlin of the Lilac Quill', 'innkeeper', 'dusk', -3, -10,
    'The Lilac Quill is a harbour tavern with lilac-paned windows through which the whole town looks bruised at dusk. Wenlin keeps a book of every ship that failed to return. He writes a new page each night, in lilac ink.', true);

  f.relic('last_lamp', 'The Unlit Lamp of Dusk', 'A tall lantern of bone-white glass and brass, its wick fresh, its oil full, never once lit. A tag in a child\'s hand is tied to its handle: \'for the one who is coming\'.', 17);
  f.relic('drowned_cap', 'Cap of the Lost Boatman', 'A small knitted cap of undyed wool with a lilac ribbon, stiff with salt. A single thread in the ribbon is a different, older wool that does not belong to any Summerset flock.', 11);
  f.boss('hollow_skiff', { name: 'The Skiff-Taker', type: 'skycraft:draugr_deathlord', hp: 3.6, dmg: 1.5, level: 28,
    weapon: 'skycraft:elven_sword', drop: 'loot:major', glow: true });
  f.scene('dus_lamps', [
    ['narrator', 'Dusk puts on her lamps one by one. The Lantern Tower flares, the quay flickers into gold, and one lamp on the south wall stays dark, no matter how often it is lit.'],
    ['@sen', 'That one. The southernmost. It was lit last by a man who has not come home. Every night Sen lights it and it goes out. It goes out because it is waiting. Not for oil. For him.'],
  ]);

  f.q({
    id: 'lamps_at_dusk', title: 'Lamps at Dusk', region: 'summerset/summerset_isle', start: 'npc:@sen', requires: ['level:14'],
    offer: 'There is one lamp on your wall that will not stay lit.',
    pitch: 'The southernmost, walker. A fisherman\'s lamp, hung when his boat went out and never taken down. It goes out each night at the same hour. Sen has changed the wick, the oil, the glass. It goes out because something is waiting for it to go out. A boat came in last week with no crew. The tide brought it. The harbourmaster has not been seen at her desk since.',
    desc: 'One lamp on Dusk\'s south wall goes out every night. Lamp-Keeper Sen believes it is waiting for a lost boatman who never came home.',
    objectives: [
      o.go('dusk', 'Light the south lamp at dusk and watch it go out', { actions: 'scene:@dus_lamps' }),
      o.go('dusk_keep', 'Follow the lamp\'s failing light to Dusk Keep, where the lost boat was moored'),
      o.kill('dusk_keep', 'Defeat the Skiff-Taker and recover the boatman\'s cap',
        [{ boss_id: '@hollow_skiff' }, mob('minecraft:drowned', 2, 'Skiff-Drowned')], { actions: 'relic:@drowned_cap', placement: 'structure' }),
      o.deliver('@sen', '@drowned_cap', 'Show the cap to Sen',
        'The boatman drowned beyond the headland, years ago. A thing took his skiff and kept it for company. It is at rest now. Here is his cap.',
        'Sen holds the cap and for a moment the whole quay seems to hold its breath. "His cap. His ribbon. Walker, Sen has lit this lamp for nine winters and told himself the man was only late. He was not late. He was waiting for someone to bring him a lamp that was already lit. Shall we keep the lamp lit, or put it out?"',
        { choices: [
          choice('Keep it lit. Let it burn for the ones still out there.', 'Sen nods, and his eyes are very bright. "Then it burns. Not for him. For everyone. Thank you, walker. Dusk will never put it out."', 'flag:@lamp_burns'),
          choice('Put it out. He is home now. Let him rest in the dark.', 'Sen takes the lamp down with great gentleness. "Then it is finished. A kindness. It will be hard to walk past that wall tomorrow, and easier the day after."', 'flag:@lamp_out;loot:standard'),
        ] }),
    ],
    outro: 'A cap with a lilac ribbon was brought home. The south wall of Dusk, whether lit or dark, is no longer waiting.',
    rewards: { gold: 560, loot: 'standard', items: [{ item: 'skycraft:elven_dagger', name: 'Lamplighter\'s Wick-Knife', enchant: { sharpness: 2, fire_aspect: 1 } }] },
  });

  f.q({
    id: 'what_orla_sells_at_night', title: 'What Orla Sells at Night', region: 'summerset/summerset_isle', start: 'npc:@lilac_quill', requires: ['level:16'],
    offer: 'Your quill is hovering rather than writing.',
    pitch: 'There is a new page, walker, and Wenlin does not like it. For three weeks, the same name has appeared in the book of ships that did not return: a Pale Merchant\'s. Every one of them bought a candle from Orla\'s stall on the night before they sailed. Every one of them was never seen again. Orla is a pleasant person. Everyone says so. Wenlin has not been able to find a single complaint. That, to him, is the most alarming thing about her.',
    desc: 'Every crew that bought a candle from the Pale Merchant Orla has vanished at sea. Wenlin cannot find a single complaint against her, which worries him more.',
    objectives: [
      o.cond('night', 'Visit Orla\'s stall after dusk and watch her sell', 'dusk'),
      o.talk('@orla', 'Ask Orla what is in the candles',
        'Your candles are very fine. What gives them their colour?',
        'Orla smiles, pale and lovely and entirely without warmth. "The colour of the hour, dear. The light before the dark. A fisherman who burns one will find his way home, though he may not like where he finds it. I do not sell death. I sell a light that guides the lost. If they do not return, it is the sea that does not release them."',
        { choices: [
          choice('Buy a candle and light it at the quay at midnight.', 'Orla\'s eyes glint, once. "Brave. Take it. Light it where the water is deepest, and say a name. We shall see which of us was right."', 'flag:@orla_candle'),
          choice('Break the stall and burn the candles.', 'Orla does not move. "A pity. But a very natural reaction. The candles will burn, of course. They always do. And what they light, I cannot say. Go carefully, walker."', 'flag:@orla_burned'),
        ] }),
      o.kill('dusk', 'Face Orla\'s true shape at the quay at midnight',
        [mob('skycraft:vampire', 1, 'Orla of the Lilac Hour', { hostile: true })]),
    ],
    outro: 'The Pale Merchant\'s stall stood empty from that night, and the book of ships has a single clean page at the back, in lilac ink, in Wenlin\'s hand: \'The sea is not hungry tonight.\'',
    rewards: { gold: 640, loot: 'standard' },
  });

  // ------------------------------------------------------------------ SHIMMERENE
  f.npc('fen', 'Dock-Master Fen', 'steward', 'shimmerene', 6, 5,
    'Fen runs the Lantern Docks like a clock. He keeps a rota for every ship, every crane and every seagull he has personally named. He does not have friends. He has a very large number of acquaintances.');
  f.npc('yeli', 'Sail-Maker Yeli', 'merchant', 'shimmerene', -8, 6,
    'Yeli stitches teal sails in the sail-loft and says a sail is a wish with a seam. She will sew your name into the hem of your sail for free, so the wind knows whom to blow.', true);
  f.npc('tor', 'Navigator Tor', 'adventurer', 'shimmerene', 10, -6,
    'Tor steers by a knot of coloured string that he refuses to explain. He is gruff, patient and has never been lost. This, he says, is because he has never admitted to it.');
  f.npc('lantern_pole', 'Miravel of the Lantern Pole', 'innkeeper', 'shimmerene', -3, -10,
    'The Lantern Pole is a dockside tavern with a single enormous lamp-pole in the middle of the common room, hung with eleven lanterns for every ship that has docked here. Miravel lights them in order. She has never once been wrong about the order.', true);

  f.relic('smuggler_manifest', 'The Second Manifest', 'A ship\'s manifest written on thin slate-paper, with a pale blue Maormer wave-mark pressed beside every third entry. The cargo it lists is not on any customs roll.', 11);
  f.relic('lamp_tag', 'Tag of the Replaced Lantern', 'A brass tag from a dock lantern pole, stamped with a number that does not match any lantern in the harbour. The paint on the lantern it hung beneath was a different blue.', 26);
  f.relic('hem_sail', 'The Sail with a Name in Its Hem', 'A small teal sail, folded in thirds and kept dry in oilcloth. Along the hem, in tiny stitches of dark thread, is a girl\'s name and a date. A seam inside has been mended three times by somebody who did not have to.', 11);
  f.boss('maormer_runner', { name: 'Sea-Captain Ssir-Vaal', type: 'skycraft:bandit_chief', hp: 3.4, dmg: 1.5, level: 26,
    weapon: 'skycraft:maormer_sword', gear: { head: 'skycraft:maormer_helmet', chest: 'skycraft:maormer_chestplate' }, drop: 'loot:standard', glow: false });
  f.scene('shi_pole', [
    ['narrator', 'Eleven lantern poles stand along the docks, each topped with a brass lamp the colour of warm honey. The twelfth, at the end of the quay, burns a faint, cold blue, and its shadow falls the wrong way.'],
    ['@fen', 'That is not one of mine. It has been there four days. It lights at the hour of the tide, and every ship that passes under it has a new crewman at the wheel in the morning. I have not been able to remove it. Nobody wants to touch it.'],
  ]);

  f.q({
    id: 'lantern_poles', title: 'Lantern Poles', region: 'summerset/summerset_isle', start: 'npc:@fen', requires: ['level:20'],
    offer: 'You are counting lamps.',
    pitch: 'Eleven, walker. There should be eleven. There are twelve. The twelfth burns blue and no one hung it. Every ship that docks beneath it is boarded that night, and the cargo changes, and nobody on the dock can say who did it. Fen has a rota for everything on this wharf and cannot find a single entry for that lantern. He is very close to being very upset.',
    desc: 'A twelfth lantern burns blue at the end of the Shimmerene quay, and ships docking beneath it have their cargo secretly swapped. Dock-Master Fen wants it explained.',
    objectives: [
      o.go('shimmerene_dockworks', 'Inspect the blue lantern on the dockworks', { actions: 'scene:@shi_pole;relic:@lamp_tag' }),
      o.cond('night', 'Hide in the sail-loft and watch the quay at the tide', 'shimmerene'),
      o.kill('shimmerene_dockworks', 'Take down Sea-Captain Ssir-Vaal and his boarding crew',
        [{ boss_id: '@maormer_runner' }, mob('skycraft:bandit', 3, 'Wave-Mark Deckhand')], { actions: 'relic:@smuggler_manifest' }),
      o.deliver('@fen', '@smuggler_manifest', 'Bring the second manifest to Fen',
        'The twelfth lantern was a Maormer runner\'s signal. The cargo they swapped was weapons for the sea-raiders of Pyandonea. The manifest names the dock-hand who took the bribe.',
        'Fen reads the manifest line by line, and the colour drains from his face. "Yeli. The sail-maker\'s brother. He has run the east crane for ten years. Walker, I have a rota. I know every entry. I did not know this. What would you have Fen do with a man who sold a harbour for a handful of silver?"',
        { choices: [
          choice('Report him to the Dominion. The harbour cannot be sold.', 'Fen nods, grim. "Then it is done properly. The Thalmor will take him. It will break Yeli\'s heart. It is still correct."', 'flag:@lantern_reported'),
          choice('Dismiss him quietly and warn the Maormer off.', 'Fen is silent for a moment. "A quiet answer. Perhaps a wise one. A harbour that cannot trust its cranes is a harbour that must be rebuilt. Fen will watch him for the rest of his life."', 'flag:@lantern_quiet;loot:standard'),
        ] }),
    ],
    outro: 'The twelfth lantern was cut down, and its blue flame went out with a sound like a held breath. Shimmerene counts eleven poles again, and counts them twice.',
    rewards: { gold: 760, loot: 'major' },
  });

  f.q({
    id: 'a_wish_with_a_seam', title: 'A Wish with a Seam', region: 'summerset/summerset_isle', start: 'npc:@yeli', requires: ['level:12'],
    offer: 'You look like someone who has been told a sail is a promise.',
    pitch: 'It is a promise, walker. And Yeli has a promise to keep. A boy came to the loft three years ago, asking for a sail with his sister\'s name in the hem. He paid in shells. He was nine. The sister was already lost at sea. Yeli made the sail. She stitched the name, and the sail was taken out, and never returned. Yeli would like someone to find where it went. The boy has grown. He has not stopped coming.',
    desc: 'Sail-Maker Yeli stitched a sail for a boy whose sister drowned, and it was never returned. She wants it found and brought home.',
    objectives: [
      o.go('shimmerene_dockworks', 'Search the old sail-store of the dockworks for the missing sail'),
      o.clear('colossal_aldmeri_grotto', 'Drive off the smugglers who store stolen goods in the Colossal Aldmeri Grotto',
        [mob('skycraft:bandit', 3, 'Grotto Smuggler')]),
      o.retrieve('colossal_aldmeri_grotto', 'Find the sail with the hem-name', '@hem_sail', { placement: 'structure' }),
      o.talk('@yeli', 'Bring Yeli the sail and tell her where it was kept',
        'The sail was hung in a smuggler\'s cave, folded with great care. Whoever kept it did not use it. They only kept it.',
        'Yeli runs her thumb along the hem and finds the stitched name by touch. Her breath catches. "Kept. Folded. Oh, walker. Someone has looked after it all this while. I will take it to the boy. He will not know what to say. Neither will I."'),
    ],
    outro: 'A sail with a name in its hem was carried back, folded very carefully, across the whole quay. The boy, now a man, did not speak for a long time.',
    rewards: { gold: 320, loot: 'minor' },
  });

  // ------------------------------------------------------------------ FIRSTHOLD
  f.npc('ro', 'Gate-Keeper Ro', 'steward', 'firsthold', 5, -5,
    'Ro keeps the Tide Gate and knows the water\'s moods the way a baker knows dough. He says the sea has three tempers: sulking, working and showing off. It is always one of the three.');
  f.npc('lyn', 'Fisher Lyn', 'fisher', 'firsthold', -6, 6,
    'Lyn fishes from the platforms at low tide and is never in a hurry. She says the fish are not afraid of nets, they are afraid of people who are in a rush.', true);
  f.npc('cael_priest', 'Priest Cael of the Rock Chapel', 'priest', 'firsthold', 8, 6,
    'Cael rings the Rock Chapel\'s bell at every tide and prays a different prayer for each one. He says the sea is a congregation and he is its very patient clerk.');
  f.npc('tide_inn', 'Naereth of the Tide-Turn Inn', 'innkeeper', 'firsthold', -3, -9,
    'The Tide-Turn Inn sits on the causeway itself, so that at high tide its front steps are underwater and at low tide its back steps are dry. Naereth keeps two doors and charges accordingly.', true);

  f.relic('gate_key', 'Key of the Tide Gate', 'An iron key green with verdigris, as long as a forearm, its bow cast as a closing wave. A salt-crust has grown over the bit, and a very small crab has made its home in the ward.', 26);
  f.boss('tide_hag', { name: 'Old Maud Saltrib', type: 'skycraft:hagraven', hp: 3.2, dmg: 1.5, level: 24, drop: 'loot:standard', glow: true });

  f.q({
    id: 'the_tide_gate', title: 'The Tide Gate', region: 'summerset/auridon', start: 'npc:@ro', requires: ['level:16'],
    offer: 'Your gate-key looks heavier than it should.',
    pitch: 'It is, walker. The key is not the one the gate was made for. Ro found the real key was taken by a hag in the shoal-caves, who left a copy of rusted iron in its place. The gate is jammed half-closed. At next high tide it will flood the lower causeway with half the town on it. The tide waits for no one. You have until the water turns. Cross at low tide, get the key, bring it back before the flood.',
    desc: 'The key to the Tide Gate of Firsthold was stolen by a hag and replaced with a copy. The gate is jammed, and the next high tide will flood the lower town.',
    objectives: [
      o.go('firsthold', 'Check the Tide Gate and confirm the jam'),
      o.go('the_veiled_keep', 'Cross the causeway at low tide to the shoal-caves beyond the Veiled Keep'),
      o.kill('the_veiled_keep', 'Defeat Old Maud Saltrib and take back the key',
        [{ boss_id: '@tide_hag' }], { actions: 'relic:@gate_key' }),
      o.wait(1, 'Race back across the causeway before the tide turns', 'firsthold'),
      o.deliver('@ro', '@gate_key', 'Bring the key to Ro',
        'The key is whole. Maud kept it as a keepsake. She said she wished to feel the sea lock behind her at least once.',
        'Ro fits the key and the gate begins to move, shuddering and grinding like a very old man standing. Water thunders against it and holds. "In time. Barely. Walker, you have saved the lower town from a drowning. Shall Ro report Maud\'s theft to the Watch, or let it lie? She was a gate-keeper before me, and she did not mean harm."',
        { choices: [
          choice('Report her. The gate cannot be trusted to anyone with a grudge.', 'Ro sighs. "Then she will be found, and brought, and judged. It is right. It will not feel right. Thank you."', 'flag:@tide_reported'),
          choice('Let it lie. She is old, and the sea has already taken everything else from her.', 'Ro looks at the water for a long time. "A mercy. Ro will say nothing. The sea will keep its own accounts."', 'flag:@tide_mercy;loot:standard'),
        ] }),
    ],
    outro: 'The Tide Gate of Firsthold holds against the flood. The lower town was spared, and an old woman in a cave goes unreported.',
    rewards: { gold: 520, loot: 'standard' },
  });

  f.q({
    id: 'the_net_that_sings', title: 'The Net That Sings', region: 'summerset/auridon', start: 'npc:@lyn', requires: ['level:12'],
    offer: 'Your net has a tune.',
    pitch: 'It does, walker. It hums. Not the wind. A low, patient melody, the same notes every low tide. Lyn pulled it from the water last month. It was snagged on a ring of old stone, deep, where there is no shipwreck in any chart. The tune is sweet. It is also getting louder. Lyn thinks something under the water would like to be answered.',
    desc: 'Fisher Lyn\'s net hums a tune it picked up from a sunken stone ring. She wants someone to find the ring and answer the song.',
    objectives: [
      o.go('greenwater_cove', 'Follow the net\'s tune along the shore to Greenwater Cove'),
      o.cond('night', 'Wait until the tide drops and the stone ring is exposed', 'greenwater_cove'),
      o.kill('greenwater_cove', 'Quiet what sings beneath the ring',
        [mob('skycraft:sea_serpent', 1, 'The Singing Eel', { target: true }), mob('minecraft:drowned', 2, 'Ring-Drowned')]),
      o.talk('@lyn', 'Tell Lyn the song is finished',
        'It was an eel, an old one, calling for a mate that died long ago. It is quiet now. Your net will hum no more.',
        'Lyn is quiet. Then, softly: "An eel. A lonely one. It is a sad thing to answer a song and silence it. But thank you. Lyn will keep the net, with its tune. A net should remember whom it has been called by."'),
    ],
    outro: 'Lyn hangs the net each night where the sea air can reach it. It hums, very softly, and she says it is more peaceful.',
    rewards: { gold: 300, loot: 'standard' },
  });

  // ------------------------------------------------------------------ ARTAEUM
  f.npc('quenel', 'Adept Quenel of the Psijic Order', 'mage', 'college_of_psijics_ruins', 4, 4,
    'Quenel is polite, patient and not entirely sure what hour it is. He tends the ruins of the old college with a small silver broom and says the stones prefer to be swept in the order they fell.');
  f.npc('thel', 'Initiate Thel', 'scholar', 'college_of_psijics_ruins', -4, 5,
    'Thel is an initiate of the Order and has not yet learned to forget what he is not allowed to say. He says half of every sentence aloud and the other half, he insists, is understood.');
  f.npc('olo', 'Ferrywoman Olo', 'fisher', 'statue_of_syrabane', 3, -3,
    'Olo rows the narrow strait to Artaeum and says she has done so for eleven years, though every year she has made exactly the same number of crossings. She is not sure how. She has stopped asking.', true);
  f.relic('psijic_token', 'Psijic Token of Passage', 'A flat disc of grey-green stone, warm as a cheek. When it is dropped, it lands at a different place on the floor each time, and none of them are where it fell.', 26);
  f.relic('mirror_shard', 'Shard of the False Door', 'A sliver of mirror from the Traitor\'s Vault. It shows not your face, but the face you would wear if you had said yes to the last thing that tempted you.', 20);
  f.boss('false_door_keeper', { name: 'The Door That Wore a Face', type: 'skycraft:dremora', hp: 3.8, dmg: 1.6, level: 36,
    weapon: 'skycraft:elven_sword', drop: 'loot:major', glow: true });
  f.scene('art_vault', [
    ['narrator', 'A hall of doors, all identical, stretches away in both directions. Each door has a handle and a mirror and a faint, patient smile. When you open one, you are back in the hall, and the other doors have changed places.'],
    ['@quenel', 'Three doors, two lies. One truth. A thousand years ago a Psijic was locked in here for a betrayal. The vault was built to hold a mind that could not be broken, only mistaken. Walker, I would not trust the door that looks the most like the way out.'],
  ]);

  f.q({
    id: 'the_traitors_door', title: 'The Traitor\'s Door', region: 'summerset/artaeum', start: 'npc:@quenel', requires: ['level:28'],
    offer: 'You have a very careful way of holding a broom.',
    pitch: 'Careful. Yes. The stones of this place do not like to be hurried. Walker, I have a difficulty. A fragment of the Traitor\'s Vault has begun to leak into the college. A mirror that shows what one would say yes to. An initiate sat before it for three hours last night, and has not spoken since. The Order has asked me to deal with it quietly. I would prefer you did, since I would very much like to be a person who has never seen the mirror. Take this token. It is how the island knows you are expected.',
    desc: 'A fragment of the Traitor\'s Vault is leaking into the ruined Psijic college on Artaeum. Adept Quenel wants a stranger to enter the vault and silence the door that wears a face.',
    objectives: [
      o.go('statue_of_syrabane', 'Cross to Artaeum by Olo\'s ferry', { actions: 'relic:@psijic_token' }),
      o.go('traitors_vault', 'Enter the Traitor\'s Vault and begin the corridor of doors', { actions: 'scene:@art_vault' }),
      o.kill('traitors_vault', 'Defeat the Door That Wore a Face',
        [{ boss_id: '@false_door_keeper' }], { placement: 'structure', actions: 'relic:@mirror_shard' }),
      o.deliver('@thel', '@mirror_shard', 'Show the mirror shard to Initiate Thel',
        'The mirror is broken. A shard remains. It shows whatever the holder would say yes to.',
        'Thel does not speak for a very long moment. "I looked into it once, for three hours. I saw myself as a teacher, an old one, with a hundred students. I said yes. I would have stayed there forever. Walker, I do not know whether to break the shard or keep it. Shall I lock it in the Order\'s vault, or return it to the world?"',
        { choices: [
          choice('Lock it in the Order\'s vault. Some temptations do not belong in the world.', 'Thel inclines his head. "Then it is bound, and I am bound to it, and neither of us will speak of it again. It is a heavy answer, and a safe one."', 'flag:@door_sealed'),
          choice('Break the shard. Let no one look into it again.', 'Thel does not hesitate. The shard shatters in his palm, and the hall seems to breathe out. "It is done. I will never know whether the old man was a vision or a vice. Thank you."', 'flag:@door_broken;loot:standard'),
        ] }),
    ],
    outro: 'The Traitor\'s Vault has one fewer door. The initiate who sat before the mirror spoke again that evening, and said only: \'Good morning.\'',
    rewards: { gold: 1200, loot: 'major', items: [{ item: 'skycraft:elven_gilded_chestplate', name: 'Mantle of the Last Refusal', enchant: { protection: 4, unbreaking: 3 } }] },
  });

  f.q({
    id: 'the_boat_that_returns', title: 'The Boat That Returns', region: 'summerset/artaeum', start: 'npc:@olo', requires: ['level:20'],
    offer: 'You row very precisely.',
    pitch: 'Eleven years, walker, and every crossing is the same number. Eleven thousand, give or take. I do not count. Something counts for me. Yesterday the number was one more than it should have been. I rowed over and rowed back, and there was a passenger in the boat who had never been in it. A tall man in a grey cloak. He thanked me, and he stepped off, and I have not been able to find the place he stepped to. I would like to know whose crossing I was rowing.',
    desc: 'Ferrywoman Olo carried a passenger she did not take aboard, and the extra crossing has thrown off the count she did not know she was keeping.',
    objectives: [
      o.go('ceporah_tower', 'Climb Ceporah Tower, where the grey-cloaked passenger vanished'),
      o.clear('ceporah_tower', 'Drive off the shades that keep the tower\'s stair',
        [mob('minecraft:vex', 3, 'Tower-Vex'), mob('skycraft:necromancer', 1, 'Staircase Wraith')], { placement: 'structure' }),
      o.talk('@quenel', 'Ask Quenel who the grey-cloaked man was',
        'Olo rowed a man who was not in her boat. He went to Ceporah Tower. Who was he?',
        'Quenel is very still. "A man in a grey cloak. Tall. Walker, there was a Psijic, long ago, who rowed the strait before Olo. He drowned with his boat. He has not stopped his crossings. He is a very patient man. Olo has been doing his work, quite without knowing. The Order has a question: shall Olo be told, or shall the work go on?"',
        { choices: [
          choice('Tell her. She has a right to know whose crossing she rows.', 'Quenel nods slowly. "She will be shaken. And then she will row, as she always has, with a little more care. A good answer."', 'flag:@boat_told'),
          choice('Let her row. The man is at peace in his work.', 'Quenel closes his eyes. "A gentle answer. The strait is quiet. He is satisfied, and Olo is content, and neither will ever be the wiser."', 'flag:@boat_unspoken;loot:standard'),
        ] }),
    ],
    outro: 'Olo rows the strait of Artaeum. Whether she knows whose boat she rows, there is one more passenger on every crossing than there is room for, and she makes a place for him.',
    rewards: { gold: 700, loot: 'standard' },
  });

  // ------------------------------------------------------------------ MINORS
  f.npc('apple_claimant', 'Orchard-Dame Syllaen', 'farmer', 'kings_haven', 3, 3,
    'Syllaen swears the great apple tree at King\'s Haven is hers by right of her grandmother\'s grandmother. Her neighbour swears otherwise. Both of them harvest the same branches on alternate days and leave a polite apple at the other\'s door, which neither will eat.', true);
  f.npc('bell_sexton', 'Sexton Aldaer of Dawnbreak', 'priest', 'dawnbreak', 3, -2,
    'Aldaer rings the dawn bell every morning. For two weeks it has been ringing at midnight, and he has not touched the rope. He sleeps in the belfry now, with a lantern, to catch it in the act.');
  f.npc('grove_watcher', 'Grove-Watcher Ilmen', 'scholar', 'shattered_grove', 2, 2,
    'Ilmen watches a single tree at Shattered Grove that has grown eleven feet in a week. He has measured it with a gold ribbon, twice a day, and the ribbon is no longer long enough.');
  f.npc('netch_pilot', 'Racer Quil of the Golden Netch', 'adventurer', 'the_golden_netch', 3, 3,
    'Quil has entered the Golden Netch race eleven times and come second in every one. The winner never returns. Quil is of two minds about it.', true);
  f.npc('matchmaker', 'Matchmaker Belenwe', 'innkeeper', 'maras_kiss_public_house', -3, 3,
    'Belenwe has made four hundred matches at Mara\'s Kiss, of which three hundred and ninety-nine have held. She talks about the one that did not as though it were an old friend.', true);
  f.npc('novice_mute', 'Novice Tevildo', 'priest', 'trebbite_monastery', 2, -3,
    'Tevildo has broken a vow of silence, and will say why only to a stranger. He writes his name in the dust and rubs it out, again and again, as if testing whether it will stay.');

  f.relic('eleventh_apple', 'The Eleventh Apple', 'A single golden apple from the disputed tree at King\'s Haven, heavy as a fist, warm to the touch. A ring of tiny script is pressed into its skin: the names of every orchard-dame who has ever claimed it.', 20);
  f.relic('midnight_rope', 'Knotted Dawn-Bell Rope', 'A short length of bell-rope, tied in a complicated knot that unties itself and re-ties whenever it is looked away from. The knot is neither Altmer nor Psijic in make.', 11);
  f.relic('race_banner', 'The Winner\'s Banner', 'A cloth banner embroidered with a golden netch and the word WINNER in a silver thread. It has been washed many times. It does not smell of balloons or of sky. It smells faintly of a place that has no weather.', 11);
  f.relic('orchard_deed', 'Deed of the Eleven Apples', 'A parchment deed in an archaic hand, leaf-stamped, granting the orchard of King\'s Haven \'to the two daughters of the house, jointly, for as long as the tree bears\'. The word jointly has been underlined by a very patient clerk.', 11);
  f.relic('groom_note', 'Drathelin\'s Note', 'A hurried note on a torn menu-card, folded in four and pinned with a lover\'s hairpin. It is neatly written and unashamed.', 11);
  f.relic('courier_letter', 'The Courier\'s Letter', 'A sealed letter in a courier\'s oilskin, the wax stamped with the Dominion\'s sun and, beneath it, a small private mark: a harbour lamp and a daughter\'s initial.', 11);
  f.boss('grove_stalker', { name: 'The Growing One', type: 'skycraft:spriggan', hp: 3.4, dmg: 1.5, level: 26, drop: 'loot:standard', glow: true });

  f.q({
    id: 'the_tree_of_two_claims', title: 'The Tree of Two Claims', region: 'summerset/summerset_isle', start: 'npc:@apple_claimant', requires: ['level:10'],
    offer: 'You are carrying an apple like an unexploded charm.',
    pitch: 'It is a gift, walker, from the other woman. She leaves one every day. Syllaen has eleven in her cellar, rotting. She cannot throw them out. She cannot eat them. The tree is theirs, both. A hundred years of lawsuits. The neighbour is old. Syllaen is old. Someone should settle it before one of them dies and the tree goes to cousins. Find the original deed. The Direnni Acropolis keeps the older registers.',
    desc: 'Two old women of King\'s Haven have fought over an apple tree for a hundred years. Syllaen wants the original deed found in the Direnni registers.',
    objectives: [
      o.go('direnni_acropolis', 'Search the old registers at the Direnni Acropolis'),
      o.retrieve('direnni_acropolis', 'Recover the original orchard deed', '@orchard_deed'),
      o.talk('@apple_claimant', 'Read the deed to Syllaen and her neighbour together',
        'The deed is clear. The tree is held jointly. It was always meant to be shared.',
        'Syllaen stares. Behind her, her neighbour sits down on the step. Neither speaks. Then, slowly, Syllaen takes the eleventh apple from your hand and offers it across the fence, and her neighbour takes it, and the two old women eat it between them, on the wall, in the sun. Neither says a word, and neither needs to.'),
    ],
    outro: 'A hundred-year quarrel ended with a single apple, divided. The tree has been well pruned since.',
    rewards: { gold: 180, loot: 'minor' },
  });

  f.q({
    id: 'the_bell_at_midnight', title: 'The Bell at Midnight', region: 'summerset/summerset_isle', start: 'npc:@bell_sexton', requires: ['level:14'],
    offer: 'You sleep with one eye on the rope.',
    pitch: 'Two weeks, walker. Every midnight, three strokes. Nobody touches the rope. The bell is a plain bronze thing, cast in the third century, and it has never done this in its life. Aldaer has sat in the belfry with a lantern, a mallet and his courage. He fell asleep. When he woke, the rope was knotted in a shape he has never seen, and the bell was still swinging.',
    desc: 'The dawn-bell of Dawnbreak rings by itself at midnight and ties its own rope in an unfamiliar knot. Sexton Aldaer wants the culprit found.',
    objectives: [
      o.cond('night', 'Keep vigil in the belfry until the bell rings', 'dawnbreak'),
      o.kill('dawnbreak', 'Strike down whatever is ringing the bell',
        [mob('skycraft:will_o_wisp', 1, 'Bell-Ringer Wisp', { target: true }), mob('minecraft:vex', 1, 'Rope-Knotter')], { actions: 'relic:@midnight_rope' }),
      o.talk('@bell_sexton', 'Tell Aldaer what rang his bell',
        'A wisp, with a vex to help. They were ringing the hour a drowned bell-ringer once kept. Here is the rope.',
        'Aldaer holds the rope and, for the first time in a fortnight, breathes out fully. "A drowned ringer. His boat was lost the night the third century ended. He is still ringing the hour he missed. Walker, shall we let him? Midnight is a quiet hour. Aldaer does not mind losing sleep, if the ringer has found his bell."',
        { choices: [
          choice('Let him ring. Midnight is his hour.', 'Aldaer smiles, tired and very sincere. "Then he rings. Aldaer will sleep through the first stroke, and wake for the third, and say a prayer. A fair bargain."', 'flag:@bell_allowed'),
          choice('Ask him to stop. The village needs its sleep.', 'Aldaer is quiet. "A kindness to the many. A cruelty to one. Still, Aldaer will ask him, politely, once. If he asks politely enough, I think the ringer will go."', 'flag:@bell_quieted;loot:standard'),
        ] }),
    ],
    outro: 'The bell at Dawnbreak still rings at midnight, in its own time, or does not. Aldaer sleeps in a bed now, and the rope is tied in the knot the ringer taught him.',
    rewards: { gold: 360, loot: 'standard' },
  });

  f.q({
    id: 'the_growing_one', title: 'The Growing One', region: 'summerset/summerset_isle', start: 'npc:@grove_watcher', requires: ['level:16'],
    offer: 'Your ribbon is not long enough.',
    pitch: 'It is not, walker. Eleven feet in a week, and it is accelerating. The tree is not a tree. It has a heartbeat, a slow one, in the second ring. Ilmen has put a stethoscope to it. Something is growing inside the trunk, and it is not a sapling. It would be wise to know what, before the tree reaches a size that makes the question academic.',
    desc: 'A tree at Shattered Grove has grown eleven feet in a week and has a heartbeat. Grove-Watcher Ilmen wants to know what is growing inside it.',
    objectives: [
      o.go('shattered_grove', 'Examine the growing tree at Shattered Grove'),
      o.kill('shattered_grove', 'Destroy the Growing One before it breaks the trunk',
        [{ boss_id: '@grove_stalker' }, mob('skycraft:spriggan', 2, 'Grove-Sprout')]),
      o.talk('@grove_watcher', 'Tell Ilmen what grew within the tree',
        'A spriggan, a very old one, was seeding itself inside the trunk to be born. It is dead now. The tree has stopped growing.',
        'Ilmen lowers his gold ribbon, which has become a very long, very useless gold line. "Stopped. Quite stopped. Walker, a tree is a gift and a danger, and I have measured one and dissected another. Shall I plant a seed in the same hollow, and see what grows? Or let it lie fallow, in case the old thing was a symptom of something older?"',
        { choices: [
          choice('Plant a seed. Let the grove begin again.', 'Ilmen takes a seed from his pocket, warm. "An acorn. Plain. A plain tree. After that, I think I could do with plain. Thank you."', 'flag:@grove_planted'),
          choice('Let it lie fallow. The grove has had enough.', 'Ilmen nods. "A fallow field. A fair season. We shall see what the earth wants, and not insist."', 'flag:@grove_fallow;loot:minor'),
        ] }),
    ],
    outro: 'The Growing One lies in the grove\'s soil, and the tree beside it has stopped counting its own height. Ilmen\'s ribbon is now a very handsome scarf.',
    rewards: { gold: 400, loot: 'standard' },
  });

  f.q({
    id: 'the_winner_never_returns', title: 'The Winner Never Returns', region: 'summerset/summerset_isle', start: 'npc:@netch_pilot', requires: ['level:14'],
    offer: 'You are second in everything.',
    pitch: 'Second in everything, walker, and proud. The Golden Netch race is a whole afternoon of balloons and cheating and sweetmeats. The winner takes the Golden Netch up, and waves, and sails over the sea, and never comes back. Not once in nine years. Quil thinks it is a secret. Quil thinks there is a prize in the sky that nobody tells about. Quil would like to know, and would like it not to be Quil who finds out. Go up in her balloon, and win. Then, please, come back.',
    desc: 'Every winner of the Golden Netch race sails off and never returns. Racer Quil wants a stranger to win, find out why, and come back alive.',
    objectives: [
      o.go('the_golden_netch', 'Enter the Golden Netch race'),
      o.cond('day', 'Win the race by crossing the line before the sun is at its height', 'the_golden_netch'),
      o.go('gryphon_aerie', 'Ride the winning balloon to the end of its course at the Gryphon Aerie'),
      o.retrieve('gryphon_aerie', 'Take the Winner\'s Banner from the nest where the previous winners landed', '@race_banner'),
      o.talk('@netch_pilot', 'Return to Quil and tell her where the winners went',
        'The winners did not vanish. They landed on a cliff nest, and were not able to climb down. They have been waiting, with a very good view, for someone to come and ask them about it.',
        'Quil laughs until she has to hold her sides. "A cliff! A very good view! Nine years! They were embarrassed. Of course they were. Walker, I have never been so pleased to be second. I shall go up there next year, and ask them how they managed to lose a balloon and keep their dignity."'),
    ],
    outro: 'Nine winners were found waiting on a cliff, none of them keen to be rescued. Quil is still second. She has never been happier.',
    rewards: { gold: 340, loot: 'standard' },
  });

  f.q({
    id: 'a_match_gone_wrong', title: 'A Match Gone Wrong', region: 'summerset/auridon', start: 'npc:@matchmaker', requires: ['level:14'],
    offer: 'You look like a person who has never been matched.',
    pitch: 'Ha! Perhaps. Walker, Belenwe has a difficulty. One of her four hundred has gone badly. Not a divorce. A disappearance. A groom of a very fine family, Drathelin, left on the wedding morning, and has not been seen. The bride is of the better house, and her family is gathering outside with torches. Belenwe would like him found, and she would like him found before anyone else finds him.',
    desc: 'A groom matched at Mara\'s Kiss has vanished on his wedding morning, and the bride\'s family is gathering with torches. Belenwe wants him found first.',
    objectives: [
      o.go('shrine_of_lamae_bal', 'Search the Shrine of Lamae Bal where the groom was last seen'),
      o.retrieve('shrine_of_lamae_bal', 'Find the note he left behind', '@groom_note'),
      o.talk('@matchmaker', 'Bring the groom\'s note to Belenwe',
        'He ran off because he loved someone else. The note says: \'I cannot lie. I would rather be shamed than false.\' He is waiting at the shrine.',
        'Belenwe reads the note and her lips tighten. "Loved someone else. And I matched him. Four hundred matches and not a single one has been wrong in this way. Walker, shall Belenwe stand between him and the bride\'s family, or tell the truth and let the torches fall where they will?"',
        { choices: [
          choice('Stand between them. The groom was honest, not cruel.', 'Belenwe straightens her back. "Then she stands. A matchmaker is not a mother, but she may be a shield. Thank you. Four hundred and one, then, and she shall do it properly."', 'flag:@match_shield'),
          choice('Tell the truth plainly. The family deserves to hear it.', 'Belenwe nods. "Plainly. A matchmaker\'s sin is to dress a thing up. Very well. The torches will come. The truth will be the only thing in the room that is not burning."', 'flag:@match_truth;loot:standard'),
        ] }),
    ],
    outro: 'A wedding that did not happen was explained to a very angry family by a very small woman who had never once been afraid of fire.',
    rewards: { gold: 380, loot: 'standard' },
  });

  f.q({
    id: 'a_vow_broken_for_a_reason', title: 'A Vow Broken for a Reason', region: 'summerset/summerset_isle', start: 'npc:@novice_mute', requires: ['level:12'],
    offer: 'You write your name in the dust.',
    pitch: 'To see whether it stays, walker. A vow of silence, and Tevildo has broken it. Not out of weakness. A traveller stopped at the gate, wounded and half-mad, and begged for a priest to hear her confession, and the Abbot was away, and Tevildo spoke. A few words. The wrong words. The Order will cast him out when the Abbot returns. He would rather be cast out for a reason than kept for a lie. He wishes only to tell it to someone who will not tell the Abbot.',
    desc: 'Novice Tevildo broke his vow of silence to comfort a dying traveller. He wants a stranger to hear why, and to carry the traveller\'s last message.',
    objectives: [
      o.talk('@novice_mute', 'Hear Novice Tevildo\'s confession',
        'I will not tell the Abbot. Speak.',
        'Tevildo speaks, hoarse from long silence. "She was a courier of the Dominion. She carried a letter. She was dying. She asked me to deliver it to a man in Dusk named Eilen. She said he would know what to do with it. I did not read it. She died before I could ask the man\'s house."',
        { actions: 'relic:@courier_letter' }),
      o.deliver('@eilen', '@courier_letter', 'Carry the courier\'s letter to Harbourmaster Eilen at Dusk',
        'A courier died at Trebbite Monastery with a letter for you. A novice broke his vow to comfort her. I have it.',
        'Eilen takes the letter and reads it. For a moment her face does not change at all, which is how you know. "My daughter. She went east three years ago. I had stopped hoping. Walker, a novice broke a vow to hold my daughter\'s hand as she died. I would like him to know that Eilen holds the harbour of Dusk, and that harbour keeps its debts."',
        { choices: [
          choice('Tell Tevildo that the harbour will stand for him before the Abbot.', 'Eilen nods once. "It will. Harbourmasters speak, in the end, only when it matters. Tell him it matters."', 'flag:@vow_defended'),
          choice('Say nothing. Let the novice\'s act stand on its own.', 'Eilen is quiet. "Then it stands. Good. A kind thing needs no witness. But I shall remember it."', 'flag:@vow_unspoken;loot:minor'),
        ] }),
    ],
    outro: 'The Abbot, returning, found a novice in the garden who had broken his vow and had a harbourmaster\'s letter of thanks tucked inside his sleeve. The vow was amended.',
    rewards: { gold: 280, loot: 'minor' },
  });
};
