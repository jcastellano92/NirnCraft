'use strict';
// Summerset marquee content, part A: Alinor, Lillandril, Cloudrest, Skywatch.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  // ------------------------------------------------------------------ ALINOR
  f.npc('aelwen', 'Chancellor Aelwen', 'steward', 'alinor', 5, 6,
    'Aelwen guards the Ledger of Precedent, in which is written every ruling the Royal Court has made since the Dawn. She has never once been seen to consult it. She simply knows. That, she says, is the whole point of a ledger.', true);
  f.npc('ildren', 'Prism-Smith Ildren', 'blacksmith', 'alinor', -8, 7,
    'Ildren cuts light for a living. He keeps a rack of quartz wedges by the window and says he can make a ray bend, a beam split or a sunrise last four minutes longer. He says no one has ever asked for the fourth.');
  f.npc('valia', 'Seneschal Valia', 'steward', 'alinor', 10, -6,
    'Valia tests the loyalty of Alinor\'s lesser officials by leaving gold in unlocked rooms and noting who takes what. She considers it a courtesy. They do not.', true);
  f.npc('prism_plover', 'Ondolemar of the Prism and Plover', 'innkeeper', 'alinor', -3, -10,
    'The Prism and Plover is a glass-walled tavern whose roof is a ceiling of faceted quartz that splits the noon sun into forty separate rainbows. Ondolemar charges extra for the rainbows. He has never explained why they are optional.');

  f.relic('sixteenth_prism', 'The Sixteenth Prism', 'A prism of rose quartz no larger than a thumb, cut with sixteen faces. One face is blank. Held to the light, it throws a seventeenth colour that no one has a name for.', 20);
  f.relic('precedent_leaf', 'Leaf of the Ledger of Precedent', 'A single unbound page, gilded at the edges, written in the Chancellery\'s hand. It records a ruling nobody remembers and a judgment nobody has overturned. The ink has not faded in three thousand years.', 11);
  f.boss('gate_warden', { name: 'The Warden of the Sixteenth Gate', type: 'nirncraft:dwarven_centurion', hp: 3.8, dmg: 1.6, level: 34,
    drop: 'loot:major', glow: true });
  f.scene('ali_avenue', [
    ['narrator', 'Fifteen avenues of white garden-lined stone radiate from the Crystal Tower. Between the fourteenth and the fifteenth the pavement forms a seam you would take for decoration, unless you held a prism to it.'],
    ['@ildren', 'There. Look through the sixteenth face. The seam is a door. It has been drawn over by every Court mason since the first, with such care that no one has noticed they were drawing around it.'],
  ]);

  f.q({
    id: 'the_sixteenth_avenue', title: 'The Sixteenth Avenue', region: 'summerset/summerset_isle', start: 'npc:@ildren', requires: ['level:22'],
    offer: 'You are looking at the city the way I look at a stone.',
    pitch: 'Walker, count the avenues with me. Fifteen from the Crystal Tower, I was taught, and the plan on the Chancellery wall says sixteen. The sixteenth was struck out in the third century, by an order that has been lost. I have cut a prism that shows what a mason\'s eye is trained not to see. Walk each avenue with it. Tell me where the city is lying.',
    desc: 'Prism-Smith Ildren believes Alinor\'s plan hides a sixteenth avenue, struck from the map by a lost order. He has cut a prism to reveal it and wants it walked.',
    objectives: [
      o.go('alinor_royal_palace', 'Walk the avenue that leads from the Royal Palace, prism in hand'),
      o.go('golden_gryphon_garret', 'Walk the avenue that leads past the Golden Gryphon Garret'),
      o.go('alinor_docks', 'Walk the avenue that ends at the Alinor Docks', { actions: 'scene:@ali_avenue' }),
      o.go('crystal_tower_summerset', 'Open the sealed seam beneath the Crystal Tower'),
      o.kill('crystal_tower_summerset', 'Defeat the Warden of the Sixteenth Gate',
        [{ boss_id: '@gate_warden' }], { actions: 'relic:@precedent_leaf' }),
      o.deliver('@aelwen', '@precedent_leaf', 'Bring the leaf from the sixteenth avenue to Chancellor Aelwen',
        'The sixteenth avenue is real. Behind the gate lies a single page of the Ledger. It records why the avenue was struck.',
        'Aelwen reads the leaf in total stillness. When she is done she folds her hands. "It was struck because it led to the mourning garden of the first Queen\'s sister, who was exiled. The Court wished the avenue forgotten, and the sister with it. The Chancellery has kept this leaf for three thousand years, in the one place no one would think to look. Walker, shall it be published, or sealed?"',
        { choices: [
          choice('Publish it. The city should know what it forgot.', 'Aelwen bows, very slightly. "Then the avenue will be walked again, and the exile\'s name read at the Hall of Law. Alinor will remember. It will be uncomfortable. Perhaps it should be."', 'flag:@avenue_published'),
          choice('Seal it. Some gardens are better left in mourning.', 'Aelwen takes the leaf and sets it, gently, back in the Ledger. "Then it returns to its place, and the city remains as it believes itself. A mercy. You have the Chancellor\'s gratitude, and her silence."', 'flag:@avenue_sealed;loot:standard'),
        ] }),
    ],
    outro: 'Alinor has sixteen avenues now, or has always had, depending on who is asked. The mourning garden is quiet, and the prism is back in its smith\'s window.',
    rewards: { gold: 1100, loot: 'major', items: [{ item: 'nirncraft:elven_gilded_helmet', name: 'Circlet of the Sixteenth Face', enchant: { protection: 3, unbreaking: 3 } }] },
  });

  f.q({
    id: 'the_seneschals_gold', title: 'The Seneschal\'s Gold', region: 'summerset/summerset_isle', start: 'npc:@valia', requires: ['level:20'],
    offer: 'You seem to have been waiting for someone honest.',
    pitch: 'Honest is a very expensive thing in Alinor, walker. Valia leaves gold in unlocked rooms, and notes who takes what. Last week, thirty crowns vanished from the third room, and the only person who entered was Valia herself. She is very sure she did not take it. She is slightly less sure she did not forget. Valia has never been a suspect in her own tests before. It is a most unsettling experience.',
    desc: 'Seneschal Valia\'s loyalty test went wrong: thirty crowns vanished from a room only she entered. She wants an outsider to find the thief, even if it is herself.',
    objectives: [
      o.go('alinor_royal_palace', 'Search the third room of the Palace test-wing'),
      o.cond('night', 'Hide in the room and watch the coins at night', 'alinor_royal_palace'),
      o.kill('alinor_royal_palace', 'Catch the one who takes the gold',
        [mob('npc:thief', 1, 'Veiled Courtier', { hostile: true })]),
      o.talk('@valia', 'Tell Valia who took her gold',
        'It was a courtier of the Chancellery, veiled, who was trying to learn who else was tested. She wanted to know your list.',
        'Valia looks at the coin you place in her palm and for the first time, a smile flickers. "She was after my list. Of course. The Chancellery has been trying to learn who is trusted and who is tested for years. Walker, you have found a thief, and you have also found that Valia is not the only tester in Alinor. That is a very useful piece of knowledge, and a very unwelcome one."'),
    ],
    outro: 'Thirty crowns returned to their room. Valia now leaves a second set, in a second room, just in case.',
    rewards: { gold: 520, loot: 'standard' },
  });

  f.q({
    id: 'forty_rainbows', title: 'Forty Rainbows', region: 'summerset/summerset_isle', start: 'npc:@prism_plover', requires: ['level:14'],
    offer: 'Your ceiling has stopped sparkling, Ondolemar.',
    pitch: 'It has, walker, and it was not a cloud. At noon the Prism and Plover makes forty rainbows. For three days, thirty-nine. One has simply left. Ondolemar has measured the ceiling. Every pane is whole. The rainbow went somewhere else. He is not sure if it is a theft or a vacation, and he would like it back.',
    desc: 'One of the forty rainbows of the Prism and Plover has gone missing. Ondolemar thinks it may have been taken, or wandered off.',
    objectives: [
      o.go('direnni_acropolis', 'Follow the missing rainbow\'s colour to the Direnni Acropolis'),
      o.cond('day', 'Wait for noon, when the forty-first colour appears', 'direnni_acropolis'),
      o.retrieveItem('direnni_acropolis', 'Gather a shard of lens-quartz from the Acropolis columns', 'nirncraft:refined_moonstone', 1),
      o.talk('@prism_plover', 'Return the shard to Ondolemar',
        'The rainbow was caught on a column of the Acropolis, which hung it like a banner. Here is the shard that kept it there.',
        'Ondolemar holds the shard to the roof and a fortieth rainbow blooms across the ceiling, bright and slightly smug. "Ha! Home. Walker, you have cost me no more than a small shard and a half-day\'s work. The house owes you, and the house always pays."'),
    ],
    outro: 'The Prism and Plover has forty rainbows again. Ondolemar insists the fortieth is a little brighter than the others, and a little prouder.',
    rewards: { gold: 260, loot: 'minor' },
  });

  // ------------------------------------------------------------------ LILLANDRIL
  f.npc('maelia', 'Chief Librarian Maelia', 'scholar', 'lillandril', 5, 5,
    'Maelia keeps the Great Library\'s catalogue and has not misplaced a book in forty years. She has misplaced one this week. She is holding herself together very precisely.', true);
  f.npc('thalor', 'Orrery-Keeper Thalor', 'mage', 'lillandril', -8, 6,
    'Thalor tends the bronze rings of the Orrery Court. He winds them every dawn with a golden key and a brief apology to each. The planets, he says, have feelings, and the feelings are mostly regret.');
  f.npc('rilen', 'Student Rilen', 'scholar', 'lillandril', 10, -5,
    'Rilen is nineteen, brilliant and very sure of it. He is hoping for a particular forbidden volume. He will tell you that it is "for research." He has been saying that for a month.');
  f.npc('dog_ear', 'Ellanwe of the Dog-Eared Page', 'innkeeper', 'lillandril', -3, -9,
    'The Dog-Eared Page is a quiet tavern where conversation is held at a whisper and every drink comes with a small slip of paper bearing a line from a poem. Ellanwe selects the poem. She says it is a diagnosis.', true);

  f.relic('missing_folio', 'The Folio of Unbound Years', 'A single folio bound in grey buckram, its pages written in a hand that is somehow always slightly ahead of the reader. It lists dates that have not yet happened and names that do not yet exist.', 15);
  f.relic('catalogue_slip', 'Catalogue Slip, Third Hand', 'A card-slip filed by a librarian who is not on the staff roll. The shelf-mark is correct. The library it refers to is not this one.', 11);
  f.boss('stack_keeper', { name: 'The Unlent Reader', type: 'nirncraft:draugr_deathlord', hp: 3.4, dmg: 1.5, level: 30,
    weapon: 'nirncraft:elven_sword', gear: { head: 'nirncraft:elven_gilded_helmet' }, drop: 'loot:major', glow: true });

  f.scene('lil_stacks', [
    ['narrator', 'The terraced stacks climb the rock in rings, open to the sky, each shelf labelled in a silver hand. Wind turns pages somewhere you cannot see.'],
    ['@maelia', 'The catalogue says the folio was on Shelf Nine of the Ninth Ring on Tuesday. It was not. Someone has been moving the shelf-marks, and whoever it is knows the system better than I do.'],
  ]);

  f.q({
    id: 'the_missing_folio', title: 'The Missing Folio', region: 'summerset/summerset_isle', start: 'npc:@maelia', requires: ['level:22'],
    offer: 'Your ledger is the only thing in this library that looks angry.',
    pitch: 'The Folio of Unbound Years, walker. A single volume in the Restricted Ring, absent from its shelf for seven days. Not borrowed: the catalogue has no record of anyone touching it. The library has, in forty years, never lost a book. It has been mislaid, and the shelf-marks changed behind it, by someone who knew the cataloguing system in more detail than I do. I would like to ask you very quietly to find out who.',
    desc: 'The Great Library\'s Folio of Unbound Years has vanished with no record. Chief Librarian Maelia wants it found, quietly, by someone outside the staff.',
    objectives: [
      o.go('lillandril', 'Search the stacks by catalogue number', { actions: 'scene:@lil_stacks' }),
      o.talk('@rilen', 'Ask Student Rilen what he knows of the folio',
        'The folio is missing, and the shelf-marks were changed by someone who knew the system. You study here every night. What did you see?',
        'Rilen\'s ears go pink. "Nothing! I mean, a person. A tall one, in a grey hood. I did not see her face. She went east of the Orrery Court, carrying something flat. Walker, I would like to say I was reading a different book entirely."',
        { actions: 'relic:@catalogue_slip' }),
      o.go('direnni_acropolis', 'Follow the grey-hooded librarian to the Direnni Acropolis'),
      o.kill('direnni_acropolis', 'Confront the Unlent Reader who has taken the folio',
        [{ boss_id: '@stack_keeper' }], { actions: 'relic:@missing_folio' }),
      o.deliver('@maelia', '@missing_folio', 'Return the folio to Maelia',
        'Your folio. It was taken by a former librarian, long dead, who wished to finish reading it. It is safe.',
        'Maelia holds the folio to her chest, and for a moment looks very much younger. "Finish reading it. Oh. Of course. She was the Chief Librarian before me, and she never had the chance. Walker, the folio is a book of years not yet lived. Shall I return it to its shelf and let the library lend it, or shall I make a copy for the scholars and place the original under lock?"',
        { choices: [
          choice('Return it to the shelf. Books are for readers.', 'Maelia nods slowly. "A librarian\'s answer. It will be on Shelf Nine, and I will move no shelf-marks. Thank you, walker. You have brought a book home."', 'flag:@folio_shelved'),
          choice('Lock the original. Copies for the scholars.', 'Maelia pauses. "Prudent. A cold answer, but a safe one. The copy will be on Shelf Nine, and the original in my desk. Thank you."', 'flag:@folio_locked;loot:standard'),
        ] }),
    ],
    outro: 'The Great Library lost a book and found a ghost, and the ghost, having finished, rests. The shelf-marks have been put back, and no one has changed them since.',
    rewards: { gold: 1000, loot: 'major' },
  });

  f.q({
    id: 'rilens_forbidden_volume', title: 'The Student\'s Forbidden Volume', region: 'summerset/summerset_isle', start: 'npc:@rilen', requires: ['level:16'],
    offer: 'You look like a student about to confess.',
    pitch: 'Confess! No. Rilen is not confessing. He is hypothesising. There is a volume in the Restricted Ring, "On the Seventh Colour of Light", and he has applied for access four times. The librarians say no. But Rilen has a theory: it is not in the Library. It was moved, long ago, to a private collection in the Direnni Acropolis. He would be very grateful if someone went and looked. Not removed. Looked.',
    desc: 'Student Rilen believes a banned volume on the seventh colour of light sits in a private collection at the Direnni Acropolis. He wants someone to look, and only look.',
    objectives: [
      o.go('direnni_acropolis', 'Search the private collection at the Direnni Acropolis'),
      o.clear('direnni_acropolis', 'Drive off the collection\'s guardians',
        [mob('nirncraft:gargoyle', 2, 'Reading-Room Gargoyle'), mob('minecraft:skeleton', 2, 'Silent Attendant')]),
      o.talk('@rilen', 'Tell Rilen what the volume says',
        'The volume was there. I read the first page. It describes a colour the eye cannot hold, and ends: \'if you have read this far, you have already seen it.\'',
        'Rilen sits down hard on a step. "Already seen it. Walker, did you... did you feel anything? No. Of course not. It was only a joke. It was only a very good joke, from a very old scholar who wished to be remembered for one clever sentence. Rilen is nineteen. He will remember it for a very long time."'),
    ],
    outro: 'Rilen has stopped applying for the forbidden volume, and has started writing his own. The first page is a single sentence, and it is very good.',
    rewards: { gold: 360, loot: 'standard' },
  });

  f.q({
    id: 'the_ring_that_sighs', title: 'The Ring That Sighs', region: 'summerset/summerset_isle', start: 'npc:@thalor', requires: ['level:18'],
    offer: 'You are very still for someone beside a machine.',
    pitch: 'The ring sighs, walker. The fourth. Every night at the hour of Secunda\'s zenith, a tiny, human sigh, as if the ring were bored. Thalor has oiled it, tightened it, pulled it apart. It sighs. Thalor believes there is a person inside. He does not wish to know how. He would like you to ask it what it wants.',
    desc: 'The fourth ring of the Orrery Court sighs every night. Orrery-Keeper Thalor believes something is trapped inside.',
    objectives: [
      o.cond('night', 'Wait in the Orrery Court until the fourth ring sighs', 'lillandril'),
      o.clear('lillandril', 'Drive off the bronze things that crawl from the fourth ring',
        [mob('nirncraft:dwarven_spider', 3, 'Ring-Mite'), mob('nirncraft:dwarven_sphere', 1, 'Sighing Sphere')]),
      o.talk('@thalor', 'Tell Thalor what the ring wanted',
        'It was not a person. It was the ring itself. It is tired of turning the same orbit and wishes to turn one other way.',
        'Thalor listens in profound silence. "Tired. Of turning. Walker, a ring may wish to rest, may it not? May it rest in a different place? It is a heavy thing, to ask the heavens to change a little, to please a machine. But we have asked greater things of heaven for worse reasons. Thalor will turn the ring. Slowly. And see."'),
    ],
    outro: 'The fourth ring now turns in a gentle reverse for one hour a night. It no longer sighs, and the astronomers say the sky is a little more interesting for it.',
    rewards: { gold: 400, loot: 'standard' },
  });

  // ------------------------------------------------------------------ CLOUDREST
  f.npc('calantir', 'Lord Calantir', 'steward', 'cloudrest', 6, 5,
    'Lord Calantir has inherited a palace with no walls and a title with no duties. He is bored in a very expensive way. He will pay almost anything to be entertained, and almost nothing to be amused.');
  f.npc('ewen', 'Sea-Lift Pilot Ewen', 'citizen', 'cloudrest', -7, 6,
    'Ewen pilots the sea-lift, a counterweighted cage that carries visitors from the palace balconies to the water. He is afraid of heights, which he considers a professional qualification.');
  f.npc('aviel', 'Steward Aviel', 'steward', 'cloudrest', 9, -7,
    'Aviel keeps the palace running and the lord from noticing. She has been loyal for twenty years and mildly insane for fifteen. She counts the glass panes of the Balcony Walk every morning, and has not yet arrived at the same number twice.', true);
  f.npc('wall_less', 'Ariadwe of the Wall-Less Inn', 'innkeeper', 'cloudrest', -3, -10,
    'The Wall-Less Inn has a roof and a floor and no walls whatever. It is therefore always in a draught. Ariadwe serves hot drinks in cups with lids, and warns every patron, kindly, not to lean back.', true);

  f.relic('floor_crack_shard', 'Shard of the Cracked Pane', 'A thumb-wide sliver of balcony glass, thick as a coin, its edge as sharp as a razor. A line of fine, grey sediment runs through it that is not frost, not dust and not salt.', 20);
  f.boss('glass_stalker', { name: 'The Glass-Walker', type: 'nirncraft:wispmother', hp: 3.8, dmg: 1.6, level: 30, drop: 'loot:major', glow: true });
  f.scene('clo_crack', [
    ['narrator', 'The Balcony Walk is a pane of clear glass three hundred feet above a restless sea. Halfway across, a hair-thin line runs from rail to rail, and below it, in the dark water, something pale has turned over and is looking up.'],
    ['@aviel', 'Seventeen thousand, four hundred and eleven panes. Seventeen thousand, four hundred and ten. Walker, the count has changed. I have counted this walk for fifteen years. A pane is missing, and the sea has begun to notice.'],
  ]);

  f.q({
    id: 'the_glass_floor', title: 'The Glass Floor', region: 'summerset/summerset_isle', start: 'npc:@aviel', requires: ['level:20'],
    offer: 'You are counting something under your breath.',
    pitch: 'Panes, walker. The Balcony Walk. Seventeen thousand four hundred and eleven, and then, this morning, ten. One has gone, and no one has seen it fall, and no fragments have been found on the rocks. Lord Calantir will not hear of it. He says a palace with no walls cannot have a missing floor. I would like someone to walk the length of the Walk and find the gap. And then descend by the sea-lift and tell me what is looking back up.',
    desc: 'A pane of the Cloudrest Balcony Walk has vanished without trace. Steward Aviel wants the gap found and what lies beneath it identified.',
    objectives: [
      o.go('cloudrest', 'Walk the Balcony Walk and find the gap', { actions: 'scene:@clo_crack;relic:@floor_crack_shard' }),
      o.talk('@ewen', 'Ask Pilot Ewen to lower the sea-lift beneath the gap',
        'I need the lift lowered. Beneath the missing pane. Something is looking up from the water.',
        'Ewen grips the lever until his knuckles whiten. "Beneath. Walker, Ewen has taken the lift to the water ten thousand times and has never once looked down. He will look down now. Hold the rail. If the thing in the water speaks, please do not answer in Ewen\'s hearing."'),
      o.kill('cloudrest', 'Defeat the Glass-Walker that haunts the water below the walk',
        [{ boss_id: '@glass_stalker' }]),
      o.talk('@calantir', 'Tell Lord Calantir what was beneath his floor',
        'There was a thing in the water that wanted to be walked upon. It took a pane to see if it was allowed. It is gone now.',
        'Calantir leans back in his chair and for the first time in years looks properly awake. "Wanted to be walked upon. A creature that wished to be a floor. Walker, this is the most interesting thing that has happened in my palace since the roof collapsed. May I ask: shall I replace the pane, or leave the gap, and have it be the most famous gap in Summerset?"',
        { choices: [
          choice('Replace the pane. A floor must be whole.', 'Calantir sighs. "Duty. How dull. Very well. The Walk will be whole, and I shall tell visitors a ghost lives beneath it. They will love it."', 'flag:@glass_replaced'),
          choice('Leave the gap. Let the thing see up, now and then.', 'Calantir\'s grin is slow and delighted. "A gap! A famous gap! Walker, you are very bad for the dignity of my house, and I adore you."', 'flag:@glass_gap;loot:standard'),
        ] }),
    ],
    outro: 'The Balcony Walk of Cloudrest has a gap in it, or does not, depending on who is asked. The thing in the water has stopped looking up.',
    rewards: { gold: 880, loot: 'major' },
  });

  f.q({
    id: 'a_bored_lords_wager', title: 'A Bored Lord\'s Wager', region: 'summerset/summerset_isle', start: 'npc:@calantir', requires: ['level:16'],
    offer: 'You have not yet been shown the chair.',
    pitch: 'The chair, walker. Calantir has a chair in the middle of the Balcony Walk, directly above the sea. It is an excellent chair. But a visitor from Skywatch has boasted that she can sit in it for an hour without looking down. Calantir has bet a pearl that she cannot. He would very much like to win. He would like someone to tamper with the chair. Gently. Imperceptibly. He does not want to know how.',
    desc: 'Lord Calantir has wagered a pearl that a visiting astronomer cannot sit an hour without looking down. He wants the chair tampered with.',
    objectives: [
      o.talk('@ewen', 'Ask Ewen what might unsettle a chair above the sea',
        'Lord Calantir wishes the astronomer to look down. What does a cautious man do, who is paid not to?',
        'Ewen looks at his hands. "Ewen could loosen a screw. Ewen could. But the sea-lift pilot does not tamper with seats on the Walk. If a lord wants a thing done, a lord should do it. Ewen suggests you tell Calantir that the best distraction is a very good story, told at exactly the right moment. And the best storyteller is the one who has never been bored."',
        { choices: [
          choice('Tamper with the chair, as Lord Calantir asked.', 'Ewen closes his eyes. "Then Ewen did not see it. A loose screw, a slight tilt. She will look down. The pearl will be won. And Calantir will be exactly as bored as before."', 'flag:@wager_tampered'),
          choice('Tell Calantir to tell her a story instead.', 'Ewen almost smiles. "A story! Ha! The lord has never told one in his life. Walker, that is either the best idea Cloudrest has had in a century, or the worst."', 'flag:@wager_story;loot:standard'),
        ] }),
      o.talk('@calantir', 'Report back to Lord Calantir',
        'It is done. You will see how the wager falls.',
        'Calantir rubs his hands. "Splendid. The pearl will be won, or I will have learned a great deal about the character of astronomers. Either way, walker, I shall not be bored today. Take this. It is a very small pearl. It is not the pearl."'),
    ],
    outro: 'The wager was won, or lost, or never settled, and the chair stood empty for a week. Calantir was not bored for three days, which in Cloudrest is a record.',
    rewards: { gold: 420, loot: 'standard' },
  });

  // ------------------------------------------------------------------ SKYWATCH
  f.npc('taleth', 'Astronomer Taleth', 'mage', 'skywatch', 5, 6,
    'Taleth measures the heavens to the fourth decimal and has never once been caught guessing. She wears three pairs of spectacles at once, each ground for a different star.', true);
  f.npc('maril', 'Student Maril', 'scholar', 'skywatch', -7, 5,
    'Maril is the brightest student at Skywatch and the quickest to say so. She has found a planet that is not on the charts. She has not yet decided whether to tell the Astronomer.', true);
  f.npc('ond', 'Ring-Keeper Ond', 'citizen', 'skywatch', 10, -5,
    'Ond has swept the outer ring of the Great Orrery for sixty years. He has not looked up in all that time, because "it is not my department."');
  f.npc('seventh_ring', 'Eilis of the Seventh Ring', 'innkeeper', 'skywatch', -3, -10,
    'The Seventh Ring is a round tavern at the top of the Orrery, where the tables turn very slowly on a hidden gear. Eilis says it makes one full turn per night, so everyone sees every view. She has never explained who winds the gear.', true);

  f.relic('orrery_gear', 'The Seventh Gear', 'A palm-sized gear of bronze with seventeen teeth, one of them filed down and replaced by a stranger\'s metal. It still spins freely, though no axle is anywhere near it.', 18);
  f.boss('ring_saboteur', { name: 'Valdis the Unwinder', type: 'nirncraft:necromancer', hp: 3.2, dmg: 1.5, level: 28,
    weapon: 'nirncraft:staff_of_sparks', gear: { head: 'nirncraft:elven_helmet' }, drop: 'loot:standard', glow: true });
  f.scene('sky_slip', [
    ['narrator', 'The Great Orrery is a lattice of nine bronze rings turning in a stone well, each a degree behind where the last one ought to be. The shadows on the ring-walks do not agree with the sun.'],
    ['@taleth', 'Seven minutes behind. Every day for a week. It should be impossible: the rings are wound by gravity and a single weight. Walker, someone has put a hand inside the sky.'],
  ]);

  f.q({
    id: 'the_slipped_ring', title: 'The Slipped Ring', region: 'summerset/auridon', start: 'npc:@taleth', requires: ['level:18'],
    offer: 'Your Orrery is running late.',
    pitch: 'Late, walker, by seven minutes a day. A week of it. The outer rings keep slipping behind the inner, and the Orrery now shows a sky that is a week out of date. A week! Navigators will steer by it. Farmers will sow by it. I have measured every gear. All are whole. One alone is unaccounted for: the seventh. It is missing from the log. It should not exist, and I cannot take the Orrery apart to find it. Climb up. Find what is hiding in the machine.',
    desc: 'The Great Orrery of Skywatch is slipping by seven minutes a day. Astronomer Taleth suspects a hidden gear and wants someone to climb the rings and find it.',
    objectives: [
      o.go('skywatch', 'Measure the slip on the ring-walks', { actions: 'scene:@sky_slip' }),
      o.go('the_veiled_keep', 'Climb to the Veiled Keep above the outer ring, where the hidden gear is said to be'),
      o.kill('the_veiled_keep', 'Stop Valdis the Unwinder, who has been slowing the rings',
        [{ boss_id: '@ring_saboteur' }], { actions: 'relic:@orrery_gear' }),
      o.cond('night', 'Return to Skywatch and wait for midnight, the moment of true alignment', 'skywatch'),
      o.deliver('@taleth', '@orrery_gear', 'Set the seventh gear into its place at midnight',
        'The gear is whole. Valdis was unwinding it, a degree each day, to hide a theft in the star-charts. Let us set it back.',
        'Taleth takes the gear with both hands. "Set it. Hold it. Count to seven. Now release. The rings align. Listen, walker. Hear that? Nine rings, one sky, at last. Valdis stole a star-chart of the southern sky. It is still missing. Will you search for it, or shall I report the theft to the Dominion?"',
        { choices: [
          choice('Report the theft. The Dominion should know the southern sky is missing.', 'Taleth pauses. "The Dominion will want to know why we kept it. They will send a Justiciar. A grim answer. But the rings are aligned, so perhaps a grim answer will do."', 'flag:@ring_reported'),
          choice('Search for it ourselves. The southern sky is ours.', 'Taleth sets her three spectacles very straight. "Then we do. Quietly. Walker, you are the first outsider the Orrery has ever trusted. Do not make me regret it."', 'flag:@ring_searched;loot:standard'),
        ] }),
    ],
    outro: 'The Great Orrery of Skywatch keeps true time again. Nine rings, one sky, and one very grateful astronomer in three pairs of spectacles.',
    rewards: { gold: 840, loot: 'major' },
  });

  f.q({
    id: 'marils_other_planet', title: 'Maril\'s Other Planet', region: 'summerset/auridon', start: 'npc:@maril', requires: ['level:14'],
    offer: 'You look like someone who has a secret.',
    pitch: 'I do! Maril has a planet. A new one. It is very small and very dim and orbits Masser at an angle nobody expected. The charts say it does not exist. But Maril has seen it, four nights running, from the Star-Chart Terrace. She would like someone to confirm it. Someone who is not Maril, who is not Taleth, who will not tell Taleth. Taleth would take the credit. Taleth would be very gracious about it.',
    desc: 'Student Maril claims to have found an unrecorded planet and wants a secret witness to confirm it from the Star-Chart Terrace.',
    objectives: [
      o.cond('night', 'Climb to the Star-Chart Terrace after dusk and look where Maril points', 'skywatch'),
      o.wait(2, 'Watch the southern sky for two hours', 'skywatch'),
      o.talk('@maril', 'Tell Maril what you saw',
        'There is a small, faint light south of Masser. It moves. It is not on any chart I have seen.',
        'Maril claps both hands over her mouth, then lowers them very slowly. "You saw it. Walker, you saw it. A planet. Mine. Maril\'s. She will call it... no, she does not know. Something bright. Something stupid. She will think of it at midnight, and regret it at dawn. Thank you. You are the only other person in Tamriel who knows."'),
    ],
    outro: 'A small, dim planet circles Masser in the margins of one student\'s notebook. When she names it, she will tell you first.',
    rewards: { gold: 240, loot: 'minor' },
  });
};
