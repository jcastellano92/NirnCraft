'use strict';
// Elsweyr marquee content, part A: Torval, Rimmen, Senchal.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  // ------------------------------------------------------------------ TORVAL
  f.npc('shahra', 'Court Singer Shahra', 'bard', 'torval', 4, -3,
    'Shahra sings the hours of the Mane\'s Court: Masser\'s hour, Secunda\'s hour, and the held-breath hour when neither moon is up. People forget a singer is in the room. Shahra remembers everything they said.', true);
  f.npc('jaro', 'Mane\'s Scribe Jaro', 'scholar', 'torval', -5, 6,
    'Jaro keeps the lunar ledger and has ink to the elbow. He rises at moonset, eats at moonrise, and has not seen a noon since the harvest tax of three years ago.');
  f.npc('ridar', 'Sugar-Duty Warden Ri\'dar', 'steward', 'torval', 9, 4,
    'Ri\'dar weighs every sack that enters the Exchange. He can tell a short measure by the sound it makes in the scale pan, and he is not shy about letting the seller hear it.');
  f.npc('matisha', 'Ma\'tisha of the Purring Ledger', 'innkeeper', 'torval', -9, -5,
    'Welcome to the Purring Ledger. Ma\'tisha keeps every tab in pawprints, not numbers, so no one can say she cheated them. She only has to remember whose paw is whose. She is very good at paws.', true);
  f.npc('tazhi', 'Cub-Runner Tazhi', 'child', 'torval', 2, 8,
    'Tazhi runs notes between the Exchange, the Court and the Observatory for a copper each. Tazhi has read none of them. Tazhi has read all of them.');

  f.relic('tally_stone', 'Tally-Stone of the Purifying Moons', 'A palm-sized disc of white clay scored with two columns of notches. One column counts casks sent out under the temple\'s seal, the other counts casks that came back. The columns have not matched in a year.', 21);
  f.relic('pawprint_tab', 'Pawprint Tab-Book', 'Ma\'tisha\'s oldest tab-book, thick with inked paw-pads. One long page of identical prints in pale grey ink records a moon-sugar run that no honest tavern should know about.', 11);
  f.boss('marish', { name: 'Ma\'rish Half-Tail', type: 'skycraft:bandit_chief', hp: 3.0, dmg: 1.4, level: 24,
    weapon: 'skycraft:khajiit_sword', gear: { chest: 'skycraft:khajiit_chestplate', head: 'skycraft:khajiit_helmet' },
    drop: 'loot:standard', glow: false });
  f.boss('hollow_choir', { name: 'The Hollow Choirmaster', type: 'skycraft:necromancer', hp: 3.2, dmg: 1.5, level: 26,
    weapon: 'skycraft:staff_of_sparks', drop: 'loot:standard', glow: true });

  f.scene('led_stones', [
    ['narrator', 'Nine tally-stones sit in a ring on the shrine floor, each scored with notches for the moon-sugar casks blessed that season. Moonlight lies across them like a ruler.'],
    ['@jaro', 'Count them twice, walker. The temple says it blessed four hundred casks. The Exchange says it received three hundred and ninety-nine. One cask does not simply walk away on four legs.'],
  ]);
  f.scene('led_den', [
    ['narrator', 'Barrels stamped with the temple seal are stacked behind a false wall of sugar-sacks. A cat with half a tail looks up from counting them and does not look worried.'],
    ['@marish', 'A walker! Good. Ma\'rish has been wanting someone to explain to the Mane why this is not stealing. It is... lending. To strangers. With interest.'],
  ]);

  f.q({
    id: 'lunar_ledger', title: 'The Lunar Ledger', region: 'elsweyr/torval', start: 'npc:@jaro', requires: ['level:12'],
    offer: 'Why does a scribe look so unhappy at a column of numbers?',
    pitch: 'Because the column is wrong by one, walker. One cask in every moon-cycle, for a year. The temple blesses four hundred, the Exchange receives three hundred ninety-nine. Jaro has checked the scales, the ink, his own eyes. The cask is going somewhere between the shrine and the Exchange, and the road is not long enough to hide a cask.',
    desc: 'Mane\'s Scribe Jaro of Torval cannot make the moon-sugar ledger balance: one blessed cask disappears every lunar cycle between the temple and the Exchange.',
    objectives: [
      o.go('shrine_of_the_twin_moons', 'Read the tally-stones at the Shrine of the Twin Moons', { actions: 'scene:@led_stones' }),
      o.cond('night', 'Wait for night, when the stones record both moons\' marks', 'shrine_of_the_twin_moons'),
      o.clear('temple_of_the_purifying_moons', 'Drive off the squatters at the Temple of the Purifying Moons',
        [mob('skycraft:bandit', 3, 'Temple Squatter'), mob('npc:thug', 1, 'Cask-Hand', { hostile: true })]),
      o.retrieve('temple_of_the_purifying_moons', 'Take the temple\'s tally-stone', '@tally_stone', { placement: 'structure' }),
      o.talk('@ridar', 'Show Ri\'dar the two columns of notches',
        'The temple counts four hundred out. Your scales count three ninety-nine in. Look at the stone.',
        'Ri\'dar turns the disc over twice and sets it down very gently, the way one handles a sleeping wasp. "Then it is not the scales. Then it is the road. Or someone who knows the road. Ri\'dar will say nothing in the Exchange. Do you want the Mane told, or do you want the cask found first?"',
        { choices: [
          choice('Tell the Mane\'s Court at once. A theft is a theft.', 'Ri\'dar nods slowly. "Then Shahra will sing it at the hour of Secunda. Loudly. The thief will know before you do. Go with your claws out."', 'flag:@ledger_public'),
          choice('Find the cask first. A quiet thief makes a mistake.', 'Ri\'dar almost smiles. "Good. Quiet. Walk the sugar roads at night, where the casks go. Ri\'dar will keep the stone and an empty face."', 'flag:@ledger_quiet'),
        ] }),
    ],
    outro: 'The stone was clear: the road itself had a mouth in it. The only question was who fed it, and where they kept what they swallowed.',
    rewards: { gold: 380, loot: 'standard' },
  });

  f.q({
    id: 'the_cask_that_walks', title: 'The Cask That Walks', region: 'elsweyr/torval', start: 'auto',
    requires: ['quest:@lunar_ledger', 'level:16'],
    desc: 'The missing cask leaves a trail of sugar-dust along the caravan road to the Sugar-Slingers\' Den. Ma\'rish Half-Tail has been lending blessed sugar to strangers.',
    objectives: [
      o.go('red_hands_run', 'Follow the sugar-dust trail along Red Hand\'s Run'),
      o.cond('night', 'Watch the caravan road at night, where the casks change carts', 'red_hands_run'),
      o.go('sugar_slingers_den', 'Find the Sugar-Slingers\' Den', { actions: 'scene:@led_den' }),
      o.kill('sugar_slingers_den', 'Settle accounts with Ma\'rish Half-Tail', [{ boss_id: '@marish' }, mob('skycraft:bandit', 2, 'Den Lender')],
        { placement: 'structure', actions: 'relic:@pawprint_tab' }),
      o.deliver('@jaro', '@pawprint_tab', 'Bring Ma\'rish\'s tab-book to Jaro',
        'Here is where the cask went. All four hundred of them, one a moon.',
        'Jaro reads three lines and goes grey to the ears. "The Purring Ledger\'s tab-book. Ma\'tisha\'s own hand. But the pawprints here are not hers, walker; they are Ri\'dar\'s. Or they are very cleverly Ri\'dar\'s. What does one do with a thief who is also the man who counted for him?"',
        { choices: [
          choice('Declare it. The Mane should know a warden was bought.', 'Jaro unrolls a clean sheet. "Then it is written. Shahra will have a new verse by moonrise, and Ri\'dar a long night. Torval will remember you kindly, and him less so."', 'flag:@warden_exposed;loot:standard'),
          choice('Burn the page. Ma\'rish is dead, and the road is honest again.', 'Jaro feeds the page to the lamp. "Then the ledger balances at last, and nobody will know why. Ri\'dar will weigh honest for the rest of his life, out of fright. That is not nothing."', 'flag:@warden_spared'),
        ] }),
    ],
    outro: 'One cask per moon had walked all the way from the temple to a den in the dunes. The ledger now balances, and what that cost is for the chooser to carry.',
    rewards: { gold: 800, loot: 'major', items: [{ item: 'skycraft:khajiit_dagger', name: 'Scribe\'s Quill-Claw', enchant: { sharpness: 3, looting: 1 } }] },
  });

  f.q({
    id: 'the_unsung_hour', title: 'The Unsung Hour', region: 'elsweyr/torval', start: 'npc:@shahra', requires: ['level:14'],
    offer: 'You sing the hours. Is one of them giving you trouble?',
    pitch: 'One hour, walker. The hour when neither moon is up. Shahra has sung it every night for nine years, and last week she opened her mouth and nothing came. Not stage-fright. Nothing. The old song-keepers say the hour has to be paid for, with a verse, to the dead who first sang it.',
    desc: 'Court Singer Shahra has lost her voice for the hour when neither moon is up. She must find the first singers of that hour, buried in the ossuary east of Torval.',
    objectives: [
      o.go('tenarr_zalviit_ossuary', 'Travel to the Tenarr Zalviit Ossuary'),
      o.clear('tenarr_zalviit_ossuary', 'Quiet the restless dead of the ossuary',
        [mob('minecraft:skeleton', 4, 'Hourless Bone'), mob('skycraft:necromancer', 1, 'Choir-Binder')], { placement: 'structure' }),
      o.kill('tenarr_zalviit_ossuary', 'Silence the Hollow Choirmaster who sings the hour wrongly',
        [{ boss_id: '@hollow_choir' }], { placement: 'structure' }),
      o.talk('@shahra', 'Tell Shahra what you found in the ossuary',
        'The first singers of the dark hour are not at rest. Someone has been conducting them.',
        'Shahra\'s hand goes to her throat, and then she laughs once, low. "A conductor. Of course. Someone made the hour sing for them. Then the hour had nothing left for me. Will you teach it back its own song? Or shall Shahra take a new hour and let that one lie?"',
        { choices: [
          choice('Sing it the old verse yourself, Shahra. It remembers you.', 'Shahra stands. Her voice is rough, then clear, then terrible and lovely. The lamps in the Court gutter and steady. "There. It sang back. You are in the song now, walker, in the second line."', 'flag:@hour_restored'),
          choice('Let it rest. Choose a new hour, one nobody owns.', 'Shahra bows her head. "A sad answer. A good one. There is an hour at dawn that no one has ever sung. Shahra will have it, and give you the first verse."', 'flag:@hour_new'),
        ] }),
    ],
    outro: 'The ossuary\'s dead were conducting themselves, in the end, by habit. Shahra has her voice, and the hour of the held breath has a verse for you.',
    rewards: { gold: 340, loot: 'standard' },
  });

  f.q({
    id: 'the_purring_ledger_wager', title: 'The Wager of the Purring Ledger', region: 'elsweyr/torval', start: 'npc:@matisha', requires: ['level:10'],
    offer: 'Your tab-book looks heavier than it should be.',
    pitch: 'Ah, a clever one. Ma\'tisha took a wager, you see. With a caravan-master from Rimmen. Whoever carries a full cup of moon-sugar-wine from the Purring Ledger to the Hall of the Lunar Champion in Rimmen, without spilling a drop and without a drop of help, wins eleven tabs wiped clean. Ma\'tisha is too round to run. You are not.',
    desc: 'Ma\'tisha of the Purring Ledger wants a cup of moon-sugar-wine carried unspilled from Torval to the Hall of the Lunar Champion in Rimmen, across a road that does not like cups.',
    objectives: [
      o.go('the_sugar_bowl', 'Carry the cup across the sandy road to the Sugar Bowl at Rimmen'),
      o.cond('night', 'Do not rest. Arrive after dusk, when the cup is coldest', 'the_sugar_bowl'),
      o.go('hall_of_the_lunar_champion', 'Set the cup at the feet of the Lunar Champion\'s statue'),
      o.talk('@matisha', 'Tell Ma\'tisha the cup arrived whole',
        'The cup arrived. Not a drop spilled. A statue drank some, though.',
        'Ma\'tisha laughs until she has to hold the bar. "A statue drank some! Then the caravan-master owes ME. Eleven tabs wiped, and one drink on the house for as long as you live, walker. Ma\'tisha means it, which is the terrible part."',
        {}),
    ],
    outro: 'Eleven tabs were wiped clean in Torval that evening, and one stranger was entered in the Purring Ledger with no paw-print at all, which is the highest honour the house gives.',
    rewards: { gold: 220, loot: 'minor' },
  });

  // ------------------------------------------------------------------ RIMMEN
  f.npc('kahlil', 'Master Artisan Kahlil', 'blacksmith', 'rimmen', 6, 6,
    'Kahlil has made rings for three Princes and a spoon for a cat who outlived all of them. He is the finest hand in Rimmen and he will tell you so while you are in the middle of a sentence.');
  f.npc('sira', 'Gatewarden Sira', 'priest', 'rimmen', -7, -9,
    'Sira holds the key to the necropolis door and has never once lent it. The dead of Rimmen are her family. She is very particular about visitors, and she does not care how they are dressed.', true);
  f.npc('lek', 'Prince-Advisor Lek', 'steward', 'rimmen', 12, -3,
    'Lek advises the Prince on matters of trade, war and marriage. He advises on the weather, too, but only as a courtesy to the weather.');
  f.npc('tamhir', 'Tamhir of the Sugar Bowl', 'innkeeper', 'rimmen', -3, 11,
    'The Sugar Bowl is a bowl-shaped courtyard tavern and Tamhir is its famous single employee. He pours, cooks, sweeps and sings. Ask for the house wine only if you are prepared to ask what is in it.');

  f.relic('mural_rubbing', 'Rubbing of the Tomb Mural', 'A charcoal rubbing on silk of a mural: a loom of seven strands, each strand a different Rimmen guild\'s sign, all seven woven into one pattern that no living artisan knows how to repeat.', 15);
  f.relic('sira_key_loan', 'Sira\'s Loaned Key', 'A black iron key as long as a hand, warm from being carried against a priestess\'s heart. It opens the necropolis door, once.', 26);
  f.boss('weaver_shade', { name: 'The Weaver Who Would Not Stop', type: 'skycraft:draugr_deathlord', hp: 3.6, dmg: 1.6, level: 30,
    weapon: 'skycraft:khajiit_war_axe', gear: { head: 'skycraft:khajiit_helmet' }, drop: 'loot:major', glow: true });

  f.scene('rim_mural', [
    ['narrator', 'The necropolis mural is a loom the length of a hall. Seven bands of thread-pattern run into one another until they make a tapestry that your eye cannot hold.'],
    ['@sira', 'The guilds of Rimmen are seven because the Weaver was seven weavers. They argued and quit, and the dead have kept the pattern since. Do not copy it hastily, walker. It does not like hurry.'],
  ]);

  f.q({
    id: 'pattern_from_below', title: 'A Pattern from Below', region: 'elsweyr/rimmen', start: 'npc:@kahlil', requires: ['level:18'],
    offer: 'You have the look of an artisan who has lost something.',
    pitch: 'Not lost, walker. Mislaid by my teacher\'s teacher, who took it into the necropolis with him in a funeral shroud. A pattern. A way of weaving seven strands into one cloth that does not tear, and Kahlil has a Prince\'s commission he cannot fill without it. The pattern is on the necropolis wall, painted by the dead. Only Sira can open the door, and Sira does not like Kahlil. She might like you.',
    desc: 'Master Artisan Kahlil needs a lost seven-strand weaving pattern that is painted on a mural in the Rimmen necropolis. Gatewarden Sira holds the key.',
    objectives: [
      o.talk('@sira', 'Ask Gatewarden Sira for the key to the necropolis',
        'Kahlil wants a pattern from the mural. I would like to look at it, with your leave.',
        'Sira weighs you for a long, quiet moment. "He wants it for a commission. The dead have no commission. But the pattern was woven by one of them, and I think she would like to be seen again. Take the key. Bring it back before the second moon sets, and keep your lamp covered."',
        { actions: 'relic:@sira_key_loan' }),
      o.go('rimmen_necropolis', 'Open the necropolis gate and descend'),
      o.clear('rimmen_necropolis', 'Fight your way through the lower galleries to the mural hall',
        [mob('minecraft:skeleton', 4, 'Gallery Bone'), mob('minecraft:zombie', 2, 'Shroud-Walker')], { placement: 'structure' }),
      o.kill('rimmen_necropolis', 'Face the Weaver Who Would Not Stop at the mural',
        [{ boss_id: '@weaver_shade' }], { placement: 'structure', actions: 'scene:@rim_mural;relic:@mural_rubbing' }),
      o.talk('@kahlil', 'Take the rubbing to Kahlil, or to whoever deserves it',
        'I have your pattern. Seven strands, one cloth. Before I give it, who is it for?',
        'Kahlil reaches for the silk and stops himself, which costs him visibly. "For the Prince\'s commission. For the guild. For Rimmen, even. Kahlil would like to say for himself, and does not, which you may count as a small virtue. What will you do with what the dead made?"',
        { choices: [
          choice('Give it to Kahlil. A master should wear the cloth of his craft.', 'Kahlil bows lower than a man of his rank ought. "Then the Prince will have his commission, and every apprentice in Rimmen will learn to weave seven strands as one. You have done Rimmen a great kindness."', 'flag:@pattern_kahlil;take:@mural_rubbing'),
          choice('Give it to Prince-Advisor Lek, for all seven guilds to share.', 'Kahlil\'s mouth tightens, and then he laughs. "Seven guilds will argue over it for a generation. That is how the Weaver died. But it is yours to give, and Kahlil will learn it from the copies like everyone else."', 'flag:@pattern_guilds'),
          choice('Return it. Hang it back in the dark where the Weaver can see it.', 'Kahlil closes his eyes. "Foolish. Noble. The sort of thing the dead understand. Very well. Rimmen will weave what it can without it. Kahlil owes you a debt, though he would rather not."', 'flag:@pattern_returned;loot:standard'),
        ] }),
    ],
    outro: 'The seven strands of the Weaver\'s pattern are known again, or buried again. Either way Rimmen has an artisan in its debt and a gatewarden who has stopped scowling at you.',
    rewards: { gold: 700, loot: 'major', items: [{ item: 'skycraft:khajiit_shield', name: 'Seven-Strand Aegis', enchant: { protection: 3, unbreaking: 2 } }] },
  });

  f.q({
    id: 'the_dead_pay_no_rent', title: 'The Dead Pay No Rent', region: 'elsweyr/rimmen', start: 'npc:@tamhir', requires: ['level:14'],
    offer: 'Is something wrong with the wine again, Tamhir?',
    pitch: 'No, walker, worse. The wine is fine. It is the cellar. Every night something sits at the end of the cellar and does not drink, just stares at the barrels. Tamhir has been to the Masterworks on the cliff, where the tomb-masons come up for air, and they say it is a prior tenant. The cellar was a tomb-vestibule once. The tenant has been complaining of damp.',
    desc: 'Tamhir\'s cellar has a silent visitor: a long-dead tenant of the old tomb-vestibule under the Sugar Bowl. The Masterworks may know what it wants.',
    objectives: [
      o.talk('@kahlil', 'Ask Kahlil in the Masterworks who the tomb-vestibule was built for',
        'The cellar under the Sugar Bowl has a dead tenant. Do the masons keep a roll of who is buried there?',
        'Kahlil rolls his eyes and finds a ledger anyway. "A tomb-mason\'s wife, Mirra-jo. The mason ran off with a Baandari caravan and left her under the floor of a half-built vault. The roll says she wanted a roof over her. Not her grave. A roof. Her grave is fine. It is the roof that leaks."'),
      o.go('rimmen_masterworks', 'Fetch the lead-and-tile roofing the Masterworks keeps for the dead'),
      o.go('the_sugar_bowl', 'Return to the Sugar Bowl cellar after dark', { actions: 'scene:@rim_cellar' }),
      o.talk('@tamhir', 'Tell Tamhir the cellar will be dry by morning',
        'The tenant is satisfied. The roof is mended. She asks that you do not water the wine.',
        'Tamhir covers his heart with both paws. "Water the wine! After this? She will be the first guest to leave a tip. Walker, the Sugar Bowl owes you a night, a bed, and a cup of the good stuff, which is the stuff that is not the house wine."'),
    ],
    outro: 'A tomb-mason\'s wife has her roof at last, and the Sugar Bowl\'s cellar has never been so well kept. Tamhir swears the wine tastes better, and has never again been seen to water it.',
    rewards: { gold: 260, loot: 'minor' },
  });
  f.scene('rim_cellar', [
    ['narrator', 'The cellar is cold and silent. At the far end, between two barrels, a grey figure sits with its hands folded and its face turned up to the dripping ceiling.'],
    ['narrator', 'When the first tile slides into place the dripping stops, and the figure nods once, slowly, and fades like breath on a mirror.'],
  ]);

  f.q({
    id: 'the_lunar_champions_challenge', title: 'The Champion\'s Challenge', region: 'elsweyr/rimmen', start: 'npc:@lek', requires: ['level:20'],
    offer: 'The Prince\'s advisor wants a champion of his own, I hear.',
    pitch: 'Not for himself, walker. For the Hall of the Lunar Champion. Every Midyear the hall sets a challenge, and the winner\'s name is cut into the pillar. This year the other three challengers are the Prince\'s nephew, a Baandari duellist who has never lost, and a very large cat from Torval. Lek would very much like none of them to win, for reasons of diplomacy. You are a stranger, which is to say, unbought.',
    desc: 'Prince-Advisor Lek wants an unbought stranger to win this year\'s challenge at the Hall of the Lunar Champion, so that none of the rival factions gains the pillar.',
    objectives: [
      o.go('hall_of_the_lunar_champion', 'Enter the challenge at the Hall of the Lunar Champion'),
      o.kill('hall_of_the_lunar_champion', 'Defeat the Baandari duellist Ri\'zhar Never-Lost',
        [mob('npc:adventurer', 1, 'Ri\'zhar Never-Lost', { hostile: true, target: true })]),
      o.talk('@lek', 'Tell Lek the pillar bears your name',
        'It is done. The pillar says what I am. Whose banner do I carry in this?',
        'Lek tilts his head, calculating. "None. That is exactly the thing. The nephew will sulk, the Baandari will bow, and Torval will send a very large cat to be offended. Walker, name one thing Rimmen can do for you that a banner would have bought, and we shall pretend we are friends."',
        { choices: [
          choice('A road-pass. Rimmen\'s caravan roads, unquestioned, for a year.', 'Lek writes it in a clean hand and seals it with a ring. "A road is a good thing to want. It is nearly the same as a friend."', 'flag:@champion_road'),
          choice('Nothing. Let the pillar be what it is.', 'Lek stares. Then, slowly, bows with all the grace his office allows. "That is the rarest answer in this city. The pillar is yours, and the Prince shall hear of you."', 'flag:@champion_honour;loot:standard'),
        ] }),
    ],
    outro: 'The pillar bears a stranger\'s name for a year. Three factions grumbled, and one advisor slept very well.',
    rewards: { gold: 520, loot: 'standard' },
  });

  // ------------------------------------------------------------------ SENCHAL
  f.npc('jakhar', 'Customs-Master Ja\'khar', 'steward', 'senchal', 8, -6,
    'Ja\'khar inspects every crate, barrel and bucket that crosses the Senchal quay. He once confiscated a sack of flour because it smelled "ambitious".');
  f.npc('tema', 'Boiler Tema', 'blacksmith', 'senchal', -10, 4,
    'Tema runs the third boiling-floor of the Sugar Boil House. Her forearms are burn-scarred to the elbow and she calls each vat by a name. They are all named after exes.', true);
  f.npc('rhal', 'Prince\'s Cousin Rhal', 'merchant', 'senchal', 14, 8,
    'Rhal is the Prince\'s cousin, which he mentions in the first sentence, the second, and the invoice. He is generous, never on time, and always about to do you a favour that costs you money.');
  f.npc('breezy', 'Zhaleh of the Sweet Breezes', 'innkeeper', 'senchal', -4, -11,
    'The Sweet Breezes is a three-storey inn where every room faces the sea and every window has been broken at least once by a wind that insists on coming in. Zhaleh swears the wind pays for its own glass.', true);

  f.relic('batch_sample', 'Phial of the Bitter Batch', 'A phial of cloudy sugar-syrup that tastes of sweet, then of iron, then of nothing at all. Three days of it make a dock-hand forget his own name.', 23);
  f.relic('boiler_token', 'Tema\'s Boiler Token', 'A brass token stamped with a vat\'s number. Tema gives one to every hand she trusts to open the sluice. This one was found in the wrong boiler, three floors down.', 26);
  f.boss('bitter_dealer', { name: 'Zahra the Bitter-Hand', type: 'skycraft:bandit_chief', hp: 3.0, dmg: 1.4, level: 22,
    weapon: 'skycraft:khajiit_dagger', gear: { chest: 'skycraft:khajiit_chestplate' }, drop: 'loot:standard', glow: false });

  f.q({
    id: 'the_bitter_batch', title: 'The Bitter Batch', region: 'elsweyr/senchal', start: 'npc:@tema', requires: ['level:12'],
    offer: 'Your arms are burned, but your face looks sicker.',
    pitch: 'The batch, walker. Third floor, vat Four-Fingers. Every sack she brews tastes right and sits wrong. Dockhands who eat it get slow and sweet-eyed, then they forget where their boats are. It is not my sugar. Tema would taste it blind. Someone is putting something in after the boil, and Tema cannot prove it. She can only make you taste three batches and tell her which one tastes honest.',
    desc: 'Boiler Tema says someone is tainting finished sugar after the boil. She needs a stranger\'s tongue to judge three batches and trace the bad one.',
    objectives: [
      o.talk('@tema', 'Taste the three batches Tema sets before you',
        'Three plates, three sugars. I taste them in order. Which one is wrong?',
        'Tema watches you chew with the intensity of a cat at a mousehole. "The second. Yes. You see? Sweet, then iron, then a long cold nothing. That batch left Sugar Boil House yesterday on cart-nine for the quay. Find cart-nine."',
        { actions: 'relic:@batch_sample' }),
      o.go('senchal_merchant_square', 'Search Senchal Merchant Square for the nine-wheeled sugar cart'),
      o.talk('@jakhar', 'Ask Ja\'khar about cart-nine\'s customs stamp',
        'Customs-Master, a sugar cart stamped nine passed your quay yesterday. Who paid its toll?',
        'Ja\'khar pulls a ledger and reads without hurry. "Nine. Stamped by... Rhal, the Prince\'s cousin, for a private lading to the black-hulled ship at the Black Kiergo. Ja\'khar does not ask what a Prince\'s cousin carries. It is bad for the lungs."'),
      o.go('black_kiergo', 'Climb down to the Black Kiergo and watch the black-hulled ship'),
      o.kill('black_kiergo', 'Confront Zahra the Bitter-Hand, the dealer who taints sugar',
        [{ boss_id: '@bitter_dealer' }, mob('npc:thug', 2, 'Dock Enforcer', { hostile: true })], { actions: 'relic:@boiler_token' }),
      o.deliver('@tema', '@boiler_token', 'Return Tema\'s token and tell her what you found',
        'The token was in a vat that was not yours. Zahra paid a boiler to tip a powder in after the boil.',
        'Tema holds the brass token as if it were still hot. "Pali. Fourth floor. Tema trained her. Fed her. She will do it again if no one speaks to her. What should Tema do with a girl who poisoned a thousand sacks for a handful of silver?"',
        { choices: [
          choice('Give her the sluice again. She did it because she was afraid.', 'Tema is quiet. "Then Tema will be afraid for her. A boiler who remembers is a better boiler. You are not a gentle soul, walker, but you are kind."', 'flag:@boiler_spared'),
          choice('Turn her in. She sold her vat, and cost a hundred their wits.', 'Tema nods once, hard. "Then it is done properly. The Customs-Master will be there at dawn. Tema will stir her vats and not look at the fourth floor."', 'flag:@boiler_judged;loot:standard'),
        ] }),
    ],
    outro: 'The Bitter Batch was a poison with a name, a face and a price. Senchal\'s sugar is clean again, and its boilers are warier of strangers who offer silver.',
    rewards: { gold: 560, loot: 'standard', items: [{ item: 'skycraft:khajiit_war_axe', name: 'Tema\'s Stirring-Paddle', enchant: { sharpness: 2, fire_aspect: 1 } }] },
  });

  f.q({
    id: 'a_favour_for_rhal', title: 'A Favour for the Prince\'s Cousin', region: 'elsweyr/senchal', start: 'npc:@rhal', requires: ['level:14'],
    offer: 'You have the look of a person who might help the Prince\'s family.',
    pitch: 'The Prince\'s cousin! Yes! You are so sharp. Rhal has a very small favour. A rival of his, a wool factor, is about to sail to Rimmen with a cargo that would, shall we say, embarrass Rhal at a banquet. If the ship were simply... slow. A day. Two. Nothing criminal. Just the kind of delay that happens to ships. Rhal would pay.',
    desc: 'The Prince\'s cousin Rhal wants a rival\'s ship delayed in the harbour, without anyone knowing it was done on purpose.',
    objectives: [
      o.go('senchal_customs_house', 'Visit the Senchal Customs House and read the sailing roll'),
      o.talk('@jakhar', 'Ask Ja\'khar when the wool factor\'s ship will sail',
        'The wool factor\'s ship. Rhal wants her... slow. Is there a customs reason she might be?',
        'Ja\'khar takes off his spectacles slowly. "There is always a customs reason, walker. Ja\'khar will find one. And he will find, in the same breath, that the cargo of the cousin\'s own ship is carrying more than its manifest. Which of the two inspections would you like to see happen first?"',
        { choices: [
          choice('Delay the wool factor, as Rhal asks. A favour is a favour.', 'Ja\'khar scratches a note. "Then the wool will sit three days for a stamp that has been lost. Ja\'khar has done worse for less, and not enjoyed it."', 'flag:@rhal_delayed'),
          choice('Inspect the cousin\'s ship instead. Let the manifest speak.', 'Ja\'khar\'s ears go forward with something like joy. "Ah! Ja\'khar has wanted to open those barrels for a year. Tell the cousin nothing. Let him find out in public."', 'flag:@rhal_exposed'),
        ] }),
      o.talk('@rhal', 'Report to Rhal',
        'It is done. You will hear about it by evening.',
        'Rhal beams like a man who has already forgotten he asked. "Marvellous! You are an ornament to Senchal. The Prince shall hear of you. Or perhaps he shall hear of me. Either way, you are paid, and I am delighted, and the wool is somebody\'s problem."'),
    ],
    outro: 'Some favours leave a taste, and some leave a ledger entry. In Senchal it is generally both.',
    rewards: { gold: 420, loot: 'standard' },
  });

  f.q({
    id: 'the_wind_pays_for_glass', title: 'The Wind That Pays for Glass', region: 'elsweyr/senchal', start: 'npc:@breezy', requires: ['level:10'],
    offer: 'Another broken window, Zhaleh?',
    pitch: 'The ninth this winter, walker, and every one of them on the third floor, and every one of them at the same hour. The wind does not do that. Wind is a coward. It comes in whichever hole it finds. This one comes in at the same bay window, in the same shape, with a smell of old rope. I would wager my best pillow someone is coming through that window, and someone has been, for nine nights.',
    desc: 'Zhaleh\'s inn, the Sweet Breezes, loses a window every night at the same hour. She suspects the culprit is no wind at all.',
    objectives: [
      o.cond('night', 'Keep watch in the third-floor room until the window breaks', 'the_sweet_breezes_inn'),
      o.kill('the_sweet_breezes_inn', 'Catch the intruder who climbs in through the bay window',
        [mob('npc:thief', 1, 'Rope-Scent Burglar', { hostile: true, target: true })]),
      o.talk('@breezy', 'Tell Zhaleh who was breaking the glass',
        'It was a rope-climbing burglar in a bay-window-sized hurry. The wind is innocent.',
        'Zhaleh blinks and then bursts into delighted laughter. "The wind is innocent! I have been scolding it for a month! I have been leaving it apologies on the sill! Walker, you have saved a poor old sea-breeze its good name."'),
    ],
    outro: 'The Sweet Breezes\' bay window is whole, and a wind that had never done anything wrong now gets a saucer of milk on the sill each evening.',
    rewards: { gold: 190, loot: 'minor' },
  });
};
