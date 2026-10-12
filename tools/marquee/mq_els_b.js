'use strict';
// Elsweyr marquee content, part B: Dune, Corinthe, Orcrest, Hakoshae and the minor places.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  // ------------------------------------------------------------------ DUNE
  f.npc('khir', 'Wheel-Master Khir', 'steward', 'dune', 6, 5,
    'Khir collects the toll at the hub of the Great Market Wheel. Every lane pays him a copper a cart, and every cart tries to look as if it has paid already. He has never once been fooled, and he is still annoyed about it.');
  f.npc('mizra', 'Camel-Driver Mizra', 'caravan_trader', 'dune', -9, 6,
    'Mizra knows the roads of Anequina by the smell of their dust. She can tell you which oasis has gone bad by the way the camels refuse to look at it.', true);
  f.npc('nuri', 'Fortune-Teller Nuri', 'mage', 'dune', 11, -7,
    'Nuri tells fortunes in a striped tent and has never been wrong, which is exactly why she is not very popular. She always tells you more than you paid for. She says it is a flaw in her scales.', true);
  f.npc('eighth_spoke', 'Shakhra of the Eighth Spoke', 'innkeeper', 'dune', -3, -10,
    'The Eighth Spoke stands on the eighth lane of the Wheel, which most visitors will tell you does not exist. It does. It is just always hidden behind the seventh. Shakhra pours from a skin on a hook and never repeats a drink twice the same way.', true);
  f.npc('hajir', 'Water-Seller Hajir', 'merchant', 'dune', 3, 12,
    'Hajir sells cups of water from a rolling barrel and a very small bell. He sells the same cup four times a day. He swears the water is fresher each time. It probably is.');

  f.relic('caravan_bell', 'Bell of the Seventh Wagon', 'A brass caravan bell with a cracked lip, still warm. Its clapper has been tied with a strip of blue cloth, as if someone wished it to be quiet.', 22);
  f.relic('wheel_tally', 'Khir\'s Toll-Tally', 'A leather tally strap with a hundred knots. Each knot is a cart. One knot is a different colour, tied by someone who was not Khir.', 11);
  f.boss('sand_warden', { name: 'The Sand-Sworn Warden', type: 'skycraft:draugr_deathlord', hp: 3.4, dmg: 1.5, level: 26,
    weapon: 'skycraft:khajiit_sword', gear: { head: 'skycraft:khajiit_helmet' }, drop: 'loot:standard', glow: true });
  f.boss('oasis_snatcher', { name: 'Zhirr the Oasis-Snatcher', type: 'skycraft:bandit_chief', hp: 3.1, dmg: 1.4, level: 24,
    weapon: 'skycraft:khajiit_bow', gear: { chest: 'skycraft:khajiit_chestplate' }, drop: 'loot:standard', glow: false });

  f.scene('dune_wreck', [
    ['narrator', 'Seven wagons stand in a circle on a salt-crust flat. The sand has drifted to the axles, and the draft-cats lie curled in their harness as if asleep. They are not asleep.'],
    ['@mizra', 'Seven wagons, and the eighth driver was Mizra\'s own cousin. Look. No claw-marks, no fire. Just the water barrels split along the staves, all at once, as if a hand had squeezed them.'],
  ]);

  f.q({
    id: 'the_missing_caravan', title: 'The Missing Caravan', region: 'elsweyr/anequina', start: 'npc:@mizra', requires: ['level:14'],
    offer: 'You are a driver who has lost wagons.',
    pitch: 'Seven, walker. Left the Wheel ten days ago for the Temar oasis, and the oasis never saw them. Mizra drove that road for twenty years. Between here and there the only things that kill a caravan are thirst, bandits and a very bad mood, and none of those leave no bodies. Find them. Find the eighth driver, who is Mizra\'s cousin, and who always carried a bell.',
    desc: 'Seven wagons of a Dune caravan never reached the Temar oasis. Camel-Driver Mizra wants to know what happened to them, and to her cousin, the eighth driver.',
    objectives: [
      o.go('nevoe_oasis', 'Check Nevoe Oasis, the first water on the Temar road'),
      o.go('teal_campsite', 'Follow the wheel-ruts to Teal Campsite', { actions: 'scene:@dune_wreck' }),
      o.retrieve('teal_campsite', 'Find the eighth driver\'s bell among the wagons', '@caravan_bell'),
      o.clear('temar_oasis', 'Clear the poachers off Temar Oasis',
        [mob('skycraft:bandit', 3, 'Oasis Poacher')]),
      o.kill('temar_oasis', 'Face Zhirr the Oasis-Snatcher, who dammed the well',
        [{ boss_id: '@oasis_snatcher' }, mob('npc:thug', 2, 'Zhirr\'s Digger', { hostile: true })]),
      o.deliver('@mizra', '@caravan_bell', 'Bring the bell back to Mizra',
        'Your cousin\'s bell. Zhirr dammed the well and let the caravan die of thirst. Your cousin tied the clapper so the bell wouldn\'t call him back.',
        'Mizra holds the bell to her ear and nothing rings. "He tied it. The fool tied it so the wagons would not wake the thieves. It cost him his life and saved the camels. Mizra will carry it to the Wheel and hang it at the eighth lane. The Eighth Spoke is named for him, you know. Nobody remembers."',
        { choices: [
          choice('Tell the Wheel what Zhirr did. Let Khir hunt his gang.', 'Mizra nods. "Then every cart on the Wheel will remember. The Wheel-Master will send riders. There will be a day of drums for the eight."', 'flag:@caravan_remembered'),
          choice('Keep the story quiet. The families have grieved enough.', 'Mizra wipes her eyes with her wrist. "Then it stays with the bell. A kindness, and a hard one. Take the road-pass; no toll on any lane, for as long as Mizra drives."', 'flag:@caravan_quiet;loot:standard'),
        ] }),
    ],
    outro: 'Seven wagons and an eighth driver went to their rest at last, and a bell with a tied clapper hangs where only camel-drivers look.',
    rewards: { gold: 560, loot: 'standard', items: [{ item: 'skycraft:khajiit_bow', name: 'Bell-Silent Bow', enchant: { power: 2, unbreaking: 2 } }] },
  });

  f.q({
    id: 'the_knot_that_is_not_khirs', title: 'The Knot That Is Not Khir\'s', region: 'elsweyr/anequina', start: 'npc:@khir', requires: ['level:12'],
    offer: 'Something is wrong with your toll-tally.',
    pitch: 'You see it too! One knot, walker. Blue thread, tied right-over-left. Khir ties left-over-right, always, since he was a cub. Someone has tied a toll that Khir did not take, for a cart Khir did not see. A hundred knots, and one of them is not his. Find the cart that paid a toll that was never collected.',
    desc: 'One knot in Wheel-Master Khir\'s toll-tally is tied by a stranger\'s hand. He wants the cart behind it found.',
    objectives: [
      o.talk('@hajir', 'Ask Water-Seller Hajir who passes the hub at night',
        'A blue-thread knot on Khir\'s tally. Who passes the hub after the Wheel-Master has gone to bed?',
        'Hajir looks left, right, and then at his barrel. "A cart. With a cloth over it. Every ninth night, gone by dawn. Hajir sold its driver three cups, and he paid with a coin so old it had a prince on it who is not a prince any more."'),
      o.cond('night', 'Stand watch at the hub of the Wheel until the cloth-covered cart appears', 'dune'),
      o.kill('dune', 'Stop the cloth-covered cart at the Sugar Stack Mart',
        [mob('npc:thief', 1, 'Blue-Knot Hauler', { hostile: true })]),
      o.talk('@khir', 'Tell Khir who tied the blue knot',
        'The knot was tied by a hauler who pays you in old coin to keep the hub quiet. He is stopped.',
        'Khir stares at the coin you give him and for once says nothing. "Old Prince\'s coin. Khir\'s own father took those. The knot was Khir\'s father\'s way of tying. He taught it to a nephew. The nephew... Walker, it seems the Wheel has been paying a debt that Khir did not know he owed."'),
    ],
    outro: 'The blue knot is still in Khir\'s tally. He says he will leave it there to remember by.',
    rewards: { gold: 240, loot: 'minor' },
  });

  f.q({
    id: 'nuri_tells_too_much', title: 'Nuri Tells Too Much', region: 'elsweyr/anequina', start: 'npc:@nuri', requires: ['level:10'],
    offer: 'Your cards look very tired.',
    pitch: 'They are, walker. Nuri told a customer yesterday exactly how he would die, and he paid in a very large bag and left, and the bag was full of sand. Now there is a rider outside the tent who says he knows which of her cards is true. Nuri would prefer to hear it from a better liar than herself.',
    desc: 'Nuri the fortune-teller has been paid in sand and is being watched by a rider who claims to know which of her cards is true.',
    objectives: [
      o.kill('dune', 'Confront the rider who watches Nuri\'s tent',
        [mob('npc:adventurer', 1, 'Dust-Cloaked Rider', { hostile: true })]),
      o.talk('@nuri', 'Tell Nuri what the rider carried',
        'The rider carried a sealed letter. He was paid to see whether your cards ever lie.',
        'Nuri reads the letter and folds it in four with delicate paws. "Ah. A rival. Someone in Rimmen sells fortunes and has lost a customer. Nuri is flattered. Take this card, walker. The Tower. It will not mean what you think. It will mean exactly what you do next."'),
    ],
    outro: 'The rider\'s letter is now a bookmark in Nuri\'s book of true cards, and a Rimmen fortune-teller has an enemy for life.',
    rewards: { gold: 180, loot: 'minor' },
  });

  f.q({
    id: 'the_sand_sworn_wheel', title: 'The Wheel That Would Not Turn', region: 'elsweyr/anequina', start: 'auto',
    requires: ['quest:@the_missing_caravan', 'level:20'],
    desc: 'The eighth driver\'s bell started something. A bone-pale warden has risen in the dunes north of Dune to demand the toll that his own prince promised him a thousand years ago.',
    objectives: [
      o.go('hill_of_shattered_swords', 'Cross the Hill of Shattered Swords where the warden is said to walk'),
      o.cond('night', 'Wait for night. The warden walks only by moonlight', 'hill_of_shattered_swords'),
      o.kill('hill_of_shattered_swords', 'Put the Sand-Sworn Warden to rest',
        [{ boss_id: '@sand_warden' }, mob('minecraft:skeleton', 3, 'Toll-Guard')]),
      o.talk('@khir', 'Tell Khir the warden is at rest',
        'A warden walked the dunes demanding a toll the prince promised. I paid it in the only coin he understood.',
        'Khir presses the tally to his chest. "A toll. A thousand years. And you paid it. The Wheel has never been so quiet. Take this coin. It is the first copper ever struck for the Great Market. Khir has no right to keep it."'),
    ],
    outro: 'The warden\'s toll is settled. The Great Market Wheel turns, and the eight lanes are quiet at night.',
    rewards: { gold: 700, loot: 'major' },
  });

  // ------------------------------------------------------------------ CORINTHE
  f.npc('mura', 'Gardener-Priest Mura', 'priest', 'corinthe', 5, -6,
    'Mura tends the Garden Cathedral and its three hundred hives. She wears a veil of fine mesh and answers to the hum of her bees before she answers to her name.', true);
  f.npc('ajh', 'Boatman Ajh', 'fisher', 'corinthe', -8, 4,
    'Ajh poles a long canal-boat between the arching bridges and hums off-key. He knows every sluice, every bridge-ghost and which canal-side window to avoid after midnight.');
  f.npc('zhel', 'Matron Zhel of the Orchard Hall', 'steward', 'corinthe', 9, 8,
    'Zhel is matron of the orchard guild and keeps the grafting-book that no one else is allowed to read. She is courteous, firm, and in thirty years has never once been caught telling the truth about a pear.', true);
  f.npc('green_barge', 'Skipper Rha\'ni of the Green Barge', 'innkeeper', 'corinthe', -2, -9,
    'The Green Barge is a moored river-barge that has been a tavern for eleven years and has not left its moorings in nine. Rha\'ni swears it is still ready to sail. The barge, privately, is not so sure.', true);
  f.npc('hive_cub', 'Bee-Cub Ji\'sha', 'child', 'corinthe', 4, 11,
    'Ji\'sha has been stung more than anyone in Corinthe and counts it as a rank. She will tell you how many stings she has, which hive, and what the bee was thinking.', true);

  f.relic('queen_cell', 'Queen Cell in a Wax Cradle', 'A sealed cradle of cream wax cut from the oldest hive of the Garden Cathedral. Inside, something very small is dreaming of flowers.', 20);
  f.relic('colossus_cog', 'Dwarven Honeycomb Cog', 'A palm-sized bronze cog, hexagonal, ringed with the faintest etched hive-pattern. It was made by people who clearly thought bees were showing off.', 18);
  f.boss('hornet_queen', { name: 'The Hornet-Queen of Khenarthi\'s Arch', type: 'skycraft:giant_scorpion', hp: 3.4, dmg: 1.5, level: 24, drop: 'loot:standard', glow: false });
  f.boss('graft_thief', { name: 'Orlin Quick-Shears', type: 'skycraft:bandit_chief', hp: 2.8, dmg: 1.3, level: 20,
    weapon: 'skycraft:khajiit_dagger', gear: { chest: 'skycraft:khajiit_chestplate' }, drop: 'loot:standard', glow: false });

  f.q({
    id: 'the_gardens_bees', title: 'The Garden\'s Bees', region: 'elsweyr/pellitine', start: 'npc:@mura', requires: ['level:12'],
    offer: 'Your hives sound unhappy.',
    pitch: 'They are not unhappy, walker. They are quiet. Three hundred hives of the Garden Cathedral, and eleven of them have fallen silent in a week. A quiet hive is a hive without a queen. The Cathedral\'s oldest queen was carried out by a stranger in the night, and Mura thinks the stranger took her to the old arch above the canals, where the wild hornets nest. Bring her back, or at least bring back what she was carrying.',
    desc: 'Gardener-Priest Mura says the Garden Cathedral\'s oldest queen bee has been carried off to the hornet-haunted arch above the canals.',
    objectives: [
      o.go('khenarthis_arch', 'Climb to Khenarthi\'s Arch above the canals'),
      o.kill('khenarthis_arch', 'Kill the Hornet-Queen that has taken the old queen\'s hive',
        [{ boss_id: '@hornet_queen' }]),
      o.retrieve('khenarthis_arch', 'Take the queen cell from the hornet nest', '@queen_cell'),
      o.deliver('@mura', '@queen_cell', 'Carry the queen cell to Mura without jostling it',
        'The old queen\'s cell, whole. Where would you like it to wake?',
        'Mura\'s veil trembles. "Wake? She will wake where she is welcomed. Put her in the hive nearest the bell, and bless her with the first honey of the morning. There are eleven hives that need her more than I need to say thank you."',
        { choices: [
          choice('Put her in the Cathedral\'s hive, where she was born.', 'Mura nods. "The old way. The hive will remember her, and she will remember it. The eleven quiet ones will sing again by Fredas."', 'flag:@queen_home'),
          choice('Split the cell. Let the eleven quiet hives each have a share.', 'Mura laughs, startled. "Eleven queens from one! The old priests would have fainted. It is a risk, and a good one. Walker, you think like a beekeeper."', 'flag:@queen_split;loot:standard'),
        ] }),
    ],
    outro: 'The Garden Cathedral hums again. The first honey of the season has a faint taste of something that no flower in Corinthe has ever worn.',
    rewards: { gold: 460, loot: 'standard', items: [{ item: 'skycraft:khajiit_shield', name: 'Beekeeper\'s Ward', enchant: { protection: 2, thorns: 1 } }] },
  });

  f.q({
    id: 'the_canal_regatta', title: 'The Canal Regatta', region: 'elsweyr/pellitine', start: 'npc:@ajh', requires: ['level:8'],
    offer: 'You hum off-key as a duty, Boatman?',
    pitch: 'Ajh is the best boatman in Corinthe and the worst singer. At midsummer there is a regatta, and Ajh has entered, and someone has sawn through his pole three nights in a row. Ajh would like to win, but more than winning, Ajh would like to know who is so afraid of a man who hums off-key. Sit in the boat. Watch the bridges. Bring a very sharp eye.',
    desc: 'Someone has been sabotaging Boatman Ajh\'s pole before the midsummer regatta. He needs a watcher in the boat and an eye on the bridges.',
    objectives: [
      o.cond('night', 'Hide in the boat shed and wait for the saboteur', 'corinthe'),
      o.kill('corinthe', 'Catch the pole-saboteur at the canal stairs',
        [mob('npc:thief', 1, 'Pole-Sawyer', { hostile: true })]),
      o.talk('@ajh', 'Tell Ajh who wanted him to lose',
        'The saboteur was paid by a rival boatman who cannot stand your singing. He is caught.',
        'Ajh bursts out laughing so hard the boat rocks. "My singing! He sawed my pole because of my singing! Walker, I will win the regatta now just to sing the victory song louder at him."'),
    ],
    outro: 'Corinthe\'s regatta was the loudest in years. A boatman sang off-key through all of it, and the sawyer sat in the stands and was forced to listen.',
    rewards: { gold: 200, loot: 'minor' },
  });

  f.q({
    id: 'the_pear_that_wasnt', title: 'The Pear That Wasn\'t There', region: 'elsweyr/pellitine', start: 'npc:@zhel', requires: ['level:14'],
    offer: 'Your grafting-book is locked. Is something missing from it?',
    pitch: 'A pear, walker. A white pear that does not exist in any orchard in Pellitine. Twenty years ago Zhel grafted it from a branch a Khajiit pilgrim brought down from the Halls of Colossus, and it fruited once, and tasted of every season at once. Then the tree died. Someone has been selling cuttings in Valenwood as "Corinthe white". Zhel did not sell them. Zhel did not keep a cutting.',
    desc: 'Matron Zhel\'s extinct white pear is being sold as cuttings in Valenwood, though she never kept one. Someone stole it from her grafting-book.',
    objectives: [
      o.go('halls_of_colossus', 'Ask after the pear at the Halls of Colossus, where the branch was found'),
      o.clear('halls_of_colossus', 'Clear the machines that guard the colossus hall',
        [mob('skycraft:dwarven_sphere', 2, 'Hive-Cog Sphere'), mob('skycraft:dwarven_spider', 3, 'Cog-Scuttler')], { placement: 'structure' }),
      o.retrieve('halls_of_colossus', 'Recover the pilgrim\'s cog, which hides the grafting notes', '@colossus_cog', { placement: 'structure' }),
      o.kill('corinthe', 'Catch Orlin Quick-Shears, the grafting-book thief',
        [{ boss_id: '@graft_thief' }]),
      o.talk('@zhel', 'Bring Zhel the cog and the news',
        'The pear was copied from your grafting-book by Orlin. I found the pilgrim\'s original notes, too.',
        'Zhel unfolds the notes with trembling fingers. "Every season at once. Zhel did not know the pilgrim wrote it down. Walker, you have brought back a recipe, not a pear. It is the better gift. Do you want your name on the first branch, or should Zhel hide it, as hiding has been her way?"',
        { choices: [
          choice('Put my name on it. Corinthe should know the pear\'s true grafter.', 'Zhel sniffs, moved. "A small tag, then. \'Gift of a stranger\'. It will be printed on every crate."', 'flag:@pear_credited'),
          choice('Hide it in your book again. Some pears are meant to be rare.', 'Zhel closes the book with great gentleness. "Rare. Yes. That is the word the pilgrim used, too."', 'flag:@pear_hidden;loot:standard'),
        ] }),
    ],
    outro: 'A pear that tastes of every season is a secret of Corinthe again, or a gift for all of Pellitine. Zhel\'s book, at least, is safe.',
    rewards: { gold: 480, loot: 'standard' },
  });

  // ------------------------------------------------------------------ ORCREST
  f.npc('raz', 'Gate-Captain Raz', 'steward', 'orcrest', 7, 5,
    'Raz commands the Claw Gate and is not a cat who smiles. He has a scar from ear to jaw and will explain it, in detail, if you ask nicely. No one has asked nicely.');
  f.npc('mala', 'Cistern-Keeper Mala', 'citizen', 'orcrest', -9, -4,
    'Mala keeps the rooftop cisterns and has counted every drop that has fallen on Orcrest since she was a cub. She keeps the number in her head and tells it to no one.', true);
  f.npc('sihe', 'Scout Sihe', 'hunter', 'orcrest', 3, -11,
    'Sihe watches the plateau from the highest watchtower and says she can tell a bandit from a trader at four miles by how the dust sits. She is right more than seems fair.', true);
  f.npc('claw_inn', 'Ruzhi of the Claw-and-Cistern', 'innkeeper', 'orcrest', -4, 9,
    'The Claw-and-Cistern keeps a rain barrel in the middle of the taproom that is always full, even in drought. Ruzhi will not say why. She pours the best cold water in Anequina and charges for it like wine.', true);

  f.relic('cistern_seal', 'Cracked Cistern Seal', 'A fired-clay seal that once bound the lid of the Third Cistern. A fingernail-wide crack runs across the face, and the crack is clean-edged: cut, not broken.', 21);
  f.relic('water_ledger', 'Mala\'s Water-Ledger', 'A thick slate-bound ledger of every cistern\'s measure. The last four entries are written in a hand that is not Mala\'s.', 11);
  f.boss('water_thief', { name: 'Thirst-Lord Zadhir', type: 'skycraft:bandit_chief', hp: 3.0, dmg: 1.4, level: 22,
    weapon: 'skycraft:khajiit_sword', gear: { head: 'skycraft:khajiit_helmet', chest: 'skycraft:khajiit_chestplate' }, drop: 'loot:standard', glow: false });

  f.q({
    id: 'the_dry_season', title: 'The Dry Season', region: 'elsweyr/anequina', start: 'npc:@mala', requires: ['level:10'],
    offer: 'Your cisterns look full to me.',
    pitch: 'They look full, walker. But Mala has counted every drop for thirty years, and the Third Cistern is lower by a barrel every fourth night. There is no leak. Mala has been down the shaft with a torch and a rope. Something is taking it, cleanly, the way a cat takes cream. Raz will not believe it. Raz believes only what he can hit.',
    desc: 'Cistern-Keeper Mala says a barrel is stolen from Orcrest\'s Third Cistern every fourth night, with no leak and no mark.',
    objectives: [
      o.go('orcrest', 'Inspect the rooftop cisterns of Orcrest'),
      o.cond('night', 'Hide in the Third Cistern\'s shaft until the water-thief comes', 'orcrest'),
      o.retrieve('the_scar', 'Follow the wet rope-marks to the Scar and recover the cracked seal', '@cistern_seal'),
      o.kill('talon_gulch', 'Corner Thirst-Lord Zadhir in Talon Gulch',
        [{ boss_id: '@water_thief' }, mob('skycraft:bandit', 2, 'Water-Hauler')]),
      o.talk('@raz', 'Show Raz the cracked seal and tell him of the thief',
        'The water was being hauled out through a cut in the seal by a thief who sells it back to you. Zadhir is dead.',
        'Raz looks at the seal for a long time. "Sold back. To us. Water we owned." He does not smile, but his jaw does something close. "Mala was right. Raz will say it once. Mala was right. The Gate will open a morning early for you, any time, any day."'),
    ],
    outro: 'Orcrest\'s Third Cistern stays full. Mala keeps counting. Raz, grudgingly, keeps a chair for her at the Gate.',
    rewards: { gold: 420, loot: 'standard' },
  });

  f.q({
    id: 'the_gate_that_hums', title: 'The Gate That Hums', region: 'elsweyr/anequina', start: 'npc:@sihe', requires: ['level:14'],
    offer: 'You are watching something, Scout.',
    pitch: 'The Gate, walker. Hear it? On the first night of the dry wind, the Claw Gate hums. Raz says it is the stone cooling. Sihe was born in the watchtower. It is not the stone. It is a note, and the note is under the plateau, and every time it sounds the sand on the south road shivers, as if there is a drum beneath it. Go to the shrine to Lorkhaj on the edge of the mesa. It is the oldest hearing-place Sihe knows.',
    desc: 'Scout Sihe hears a deep note under Orcrest every dry-wind night. She wants someone to listen from the old shrine to Lorkhaj.',
    objectives: [
      o.go('shrine_to_lorkhaj', 'Listen for the hum at the shrine to Lorkhaj'),
      o.cond('night', 'Wait for the dry wind to rise after dusk', 'shrine_to_lorkhaj'),
      o.clear('the_bone_pit', 'Silence what drums beneath the plateau in the Bone Pit',
        [mob('minecraft:skeleton', 4, 'Drum-Bone'), mob('skycraft:necromancer', 1, 'Drum-Priest')], { placement: 'structure' }),
      o.talk('@sihe', 'Tell Sihe the hum has stopped',
        'A drum-priest was beating the dead beneath the plateau. The note is gone. Listen.',
        'Sihe tilts her ears and stays very still for a long moment. "Nothing. The Gate is quiet. Sihe has not heard the plateau be quiet since she was a cub. It is... lonely. Thank you, walker. Sihe will miss it, which is not at all what she expected."'),
    ],
    outro: 'The Claw Gate no longer hums at night, and Sihe, who could hear it from the top of the world, has begun to sleep in the afternoons.',
    rewards: { gold: 360, loot: 'standard' },
  });

  // ------------------------------------------------------------------ HAKOSHAE
  f.npc('dar', 'Ring-Keeper Dar', 'priest', 'hakoshae', 4, -5,
    'Dar keeps the ring of moonstones in the centre of Hakoshae and sweeps it every dawn. He says the stones have never once been dirty, and he sweeps them anyway, because it is a courtesy.');
  f.npc('suri', 'Guardian Su\'ri', 'steward', 'hakoshae', -7, 5,
    'Su\'ri is the tomb-guardian of the east side and carries a spear that has not once been used in anger. She says that is the whole point of a good spear.', true);
  f.npc('ke', 'Pilgrim Ke', 'adventurer', 'hakoshae', 8, 8,
    'Ke has walked to every moon-temple of Elsweyr and is on his second lap. He has no goal. He says that is the pilgrimage.');
  f.npc('rest_house', 'Hamza of the Pilgrim\'s Rest', 'innkeeper', 'hakoshae', -3, -9,
    'Hamza runs the Pilgrim\'s Rest, a long low house with seventeen beds and no locks. He says the moonstones watch the door. Nobody has stolen anything from there in living memory, which is a long time among cats.');

  f.relic('alignment_lens', 'Alignment Lens of Hakoshae', 'A palm-sized disc of clear moonstone, polished flat on one side and curved on the other. Held up at the right hour, it throws both moons\' light as one thin silver line across the ground.', 20);
  f.boss('tomb_tenant', { name: 'The Tenant Beneath the Third Stone', type: 'skycraft:draugr_deathlord', hp: 3.8, dmg: 1.6, level: 32,
    weapon: 'skycraft:khajiit_war_axe', gear: { head: 'skycraft:khajiit_helmet', chest: 'skycraft:khajiit_chestplate' }, drop: 'loot:major', glow: true });
  f.scene('hak_align', [
    ['narrator', 'The ring of moonstones stands silent. Then, between one breath and the next, both moons rise at once above the same stone, and a thin silver line runs from the third stone to the ground at your feet.'],
    ['@dar', 'There. Hold the lens still. Do not breathe. The line points to a door. The door has not been seen in eleven hundred years, and it will not be seen again until the moons come together.'],
  ]);

  f.q({
    id: 'moon_alignment', title: 'Moon Alignment', region: 'elsweyr/rimmen', start: 'npc:@dar', requires: ['level:18'],
    offer: 'Your ring of stones seems to be watching me.',
    pitch: 'It is watching all of us, walker. Once in a long time the moons stand over the same stone, and a tomb below Hakoshae opens for a night. The Guardians keep the key; the Ring-Keeper keeps the hour. This is that hour. Su\'ri cannot go alone, for the Guardians do not leave the ring. Take the lens. Hold the line. Carry the light to the tomb.',
    desc: 'Ring-Keeper Dar says the moons will align over the third stone of Hakoshae tonight. A lens carried to the tomb below may open it.',
    objectives: [
      o.talk('@suri', 'Ask Guardian Su\'ri for the Alignment Lens',
        'Dar says the hour is tonight. He says you keep the lens. May I take it?',
        'Su\'ri weighs the lens in her palm and then lets it fall into yours, like trusting a bird. "A hard thing to give. Guardians do not leave the ring, and the lens has never left the ring either. It is your hour now. Mind the line. Mind your breath."',
        { actions: 'relic:@alignment_lens' }),
      o.cond('night', 'Wait in the ring until both moons stand over the third stone', 'hakoshae', { actions: 'scene:@hak_align' }),
      o.go('hakoshae_tombs', 'Carry the lens\'s line to the Hakoshae Tombs'),
      o.kill('hakoshae_tombs', 'Face the Tenant Beneath the Third Stone',
        [{ boss_id: '@tomb_tenant' }, mob('minecraft:skeleton', 3, 'Ring-Sworn Bone')], { placement: 'structure' }),
      o.deliver('@dar', '@alignment_lens', 'Return the lens to Dar',
        'The tomb is closed again. The tenant is at rest. Here is the lens.',
        'Dar takes the lens, holds it up to the dawn, and sees nothing. "Good. Empty. The line is spent. The moons will not stand over that stone again for a century. Do you wish the lens placed in the ring, to point the way for the next Keeper? Or taken on your road, in case another door opens?"',
        { choices: [
          choice('Place it in the ring. The next Keeper will need it.', 'Dar settles the lens into a hollow in the third stone as if it were always meant to go there. "Then it waits. The ring thanks you. So do we all."', 'flag:@lens_ring'),
          choice('Keep it. Another door may open somewhere.', 'Dar bows his head. "Then you are the Keeper now, a little. Go gently. The moons are not always kind to those who carry their light."', 'flag:@lens_kept;loot:major'),
        ] }),
    ],
    outro: 'The ring of moonstones is quiet. The tomb below it has closed its door for a hundred years, and its tenant, at last, rests on the other side of the stone.',
    rewards: { gold: 640, loot: 'major' },
  });

  f.q({
    id: 'the_lost_pilgrim', title: 'The Pilgrim Who Would Not Finish', region: 'elsweyr/rimmen', start: 'npc:@rest_house', requires: ['level:10'],
    offer: 'Your seventeenth bed is empty tonight.',
    pitch: 'It is, walker, and Hamza does not like it. Ke the pilgrim left four days ago for the Shrine of the Crescent. He always returns the same night, because he walks in loops. He has not. Ke is a gentle fool, and the road to the shrine is not gentle.',
    desc: 'Pilgrim Ke has not returned from the Shrine of the Crescent, and Hamza fears the worst.',
    objectives: [
      o.go('shrine_of_the_crescent', 'Search the Shrine of the Crescent for Pilgrim Ke'),
      o.clear('shrine_of_the_crescent', 'Drive off the bandits who have made camp at the shrine',
        [mob('skycraft:bandit', 3, 'Shrine-Robber')], { placement: 'structure' }),
      o.escort('@ke', 'hakoshae', 'Escort Pilgrim Ke back to Hakoshae'),
    ],
    outro: 'Ke was found asleep in a bandit\'s bedroll, quite content, with a bandit\'s hat on. He says he has begun a third lap. Hamza says that is a lie.',
    rewards: { gold: 190, loot: 'minor' },
  });

  // ------------------------------------------------------------------ MINORS (villages, farms, landmarks)
  f.npc('netch_balloonist', 'Balloonist Q\'ri', 'adventurer', 'the_prancing_netch', 3, 3,
    'Q\'ri pilots a balloon made from a grown netch and always has one story too many. She trades rides for tales. She trades tales for rides. She is rumoured to have no home on the ground at all.', true);
  f.npc('twin_keeper', 'Keeper Ma\'tir of the Twin Moons', 'innkeeper', 'roadside_twin_moons', -3, 4,
    'Ma\'tir runs the Twin Moons roadhouse where travellers wager on the moon-phase. She has lost every wager she ever made against the sky, and says it keeps the house honest.');
  f.npc('cane_widow', 'Tathara the Cane-Widow', 'farmer', 'tathara_sugar_farm', 2, -4,
    'Tathara grew sugar for thirty years. This season her fields burned in a night. She stands where the cane was and says nothing, and the neighbours know better than to ask.', true);
  f.npc('star_tutor', 'Adept Zhazir', 'scholar', 'star_haven_adeptorium', -2, 3,
    'Zhazir tutors three cubs in the stars beneath the adeptorium dome. A chart has gone missing, and he has been counting ceiling-tiles ever since to avoid saying so.');
  f.npc('route_trader', 'Route-Master Ha\'bir', 'caravan_trader', 'baandari_caravan_stop', 4, -2,
    'Ha\'bir carries the Baandari route book and counts sorry the day it was taken from his wagon. He is polite to everyone, and he counts their fingers.');

  f.relic('route_book', 'The Baandari Route Book', 'A leather-bound book of caravan routes drawn on goatskin, with wells, wolves and bribes marked in three colours of ink. Half the pages are written in a code only Baandari clans know.', 11);
  f.relic('star_chart', 'The Missing Star-Chart', 'A vellum star-chart of the winter sky, hand-lettered in silver ink. Someone has traced a seventh moon in faint pencil near the southern horizon.', 15);

  f.q({
    id: 'the_netch_balloon_wager', title: 'A Story for a Ride', region: 'elsweyr/anequina', start: 'npc:@netch_balloonist', requires: ['level:8'],
    offer: 'Is that a netch you fly?',
    pitch: 'A balloon, walker. Grown from a netch\'s sac. Q\'ri trades rides for stories, and she is out of stories she has not told. So: Q\'ri will give you a ride to anywhere you like, if you bring her one story that she has never heard. Something that happened. Not something you made up. She can tell.',
    desc: 'Balloonist Q\'ri will trade a ride for a true story she has never heard. The Hill of Shattered Swords might provide one.',
    objectives: [
      o.go('hill_of_shattered_swords', 'Walk the Hill of Shattered Swords and see what it says'),
      o.discover('shrine_of_the_cats_divine', 'Find the Shrine of the Cats Divine'),
      o.talk('@netch_balloonist', 'Tell Q\'ri what you saw',
        'There is a hill planted with broken swords, and a shrine of giant cats watching it. The swords hum when the wind passes.',
        'Q\'ri puts down her cup and stares. "Hums? She has been to the hill, and never once heard it hum. It hums for those who walk it. She will give you a ride, walker. And Q\'ri will go walk the hill herself. That is the best thing you could have given her: a reason to go."',
        {}),
    ],
    outro: 'Q\'ri took the long way round to the hill, and did not come back for a week. She says it hums quite beautifully.',
    rewards: { gold: 140, loot: 'minor' },
  });

  f.q({
    id: 'the_burned_cane', title: 'Who Burned the Cane', region: 'elsweyr/rimmen', start: 'npc:@cane_widow', requires: ['level:12'],
    offer: 'The ash is cold. Whose fire was it?',
    pitch: 'Not lightning. Not lamp. The fire began in four corners at once, in the dark of the new moon, and the wind that night was from the west, which would have pushed the fire back on itself. Four hands set it. Four people who know Tathara\'s fields. She does not wish to name them. She would be very happy for you to.',
    desc: 'Tathara\'s sugar fields were set alight from four corners at once. She does not want to name the neighbours she suspects.',
    objectives: [
      o.go('tathara_sugar_farm', 'Search the burned fields for the fire-setters\' tracks'),
      o.kill('hanikhi_outpost', 'Catch the arsonists at Hanikhi Outpost',
        [mob('skycraft:bandit', 3, 'Torch-Hired Hand'), mob('skycraft:bandit_chief', 1, 'Cane-Burner Zhasa')]),
      o.talk('@cane_widow', 'Tell Tathara who hired the fire',
        'Four hired hands, paid by a buyer who wanted your land for his own cane. He is a neighbour. You may guess which.',
        'Tathara closes her eyes. "Guessed. Always guessed. The one with the new gate. The one who brought pies at the funeral of the crop. Walker, there will be a reckoning, and it will not be with fire. Tathara will plant again. She will plant it twice as wide, right up to his gate."'),
    ],
    outro: 'Tathara\'s new cane stands close to a neighbour\'s fence, uncomfortably tall, and sweet as anything.',
    rewards: { gold: 280, loot: 'minor' },
  });

  f.q({
    id: 'the_missing_star_chart', title: 'The Star-Chart That Left Early', region: 'elsweyr/anequina', start: 'npc:@star_tutor', requires: ['level:10'],
    offer: 'Your ceiling-tiles seem well counted.',
    pitch: 'They are. Three hundred and nine. Walker, a star-chart of the winter sky has left the lectern, and with it the only record Zhazir has of the seventh moon, which his old tutor swore appeared once in a century above the Starlight Adeptorium. The chart did not walk. It was taken. Someone with a very good hand for silver ink.',
    desc: 'A star-chart showing a rumoured seventh moon has vanished from the star-haven lectern. The Starlight Adeptorium may know who wanted it.',
    objectives: [
      o.go('starlight_adeptorium', 'Ask at the Starlight Adeptorium who copies star-charts in silver ink'),
      o.kill('saimisil_steppes', 'Catch the copyist on the Saimisil Steppes',
        [mob('npc:thief', 1, 'Silver-Ink Copyist', { hostile: true })], { actions: 'relic:@star_chart' }),
      o.deliver('@star_tutor', '@star_chart', 'Return the star-chart to Zhazir',
        'Your chart. The copyist sold copies to three rival adeptoria. The seventh moon is the same in each. It seems not to be a joke.',
        'Zhazir unfolds the chart and stares at the faint pencil moon for a long time. "Seven. Not six. Not two plus a ghost. Seven. His tutor was not mad. Zhazir will not say this to the cubs. He will only look at the sky and count."'),
    ],
    outro: 'A seventh moon waits in pencil under the winter stars. Zhazir counts it every night, though no one else will say they see it.',
    rewards: { gold: 220, loot: 'minor' },
  });

  f.q({
    id: 'the_stolen_route_book', title: 'The Route Book of Ha\'bir', region: 'elsweyr/rimmen', start: 'npc:@route_trader', requires: ['level:12'],
    offer: 'A route book is a heavy loss for a trader.',
    pitch: 'It is worth a clan, walker. It lists every well, every ambush and every price. Ha\'bir does not ask for the thief\'s head. He asks that you take back the book before someone sells a Baandari route to a rival clan. There is a camp west of here, Nekha, where bad men buy and good men look the other way.',
    desc: 'Route-Master Ha\'bir\'s Baandari route book was stolen. A fence at Nekha Camp is the likeliest buyer.',
    objectives: [
      o.go('nekha_camp', 'Find the fence at Nekha Camp'),
      o.kill('nekha_camp', 'Take the route book from the fence',
        [mob('skycraft:bandit_chief', 1, 'Fence Zhil-ra'), mob('skycraft:bandit', 2, 'Camp Guard')], { actions: 'relic:@route_book', placement: 'structure' }),
      o.deliver('@route_trader', '@route_book', 'Return the book to Ha\'bir',
        'Your book, whole. The fence had not yet sold it. He had copied three pages.',
        'Ha\'bir counts the pages with his thumb and breathes out slowly. "Three copied. Three pages. Ha\'bir will change those wells by morning. A route book is only a gift to the person who does not know the road has changed. Walker, the Baandari clans owe you a road-name."'),
    ],
    outro: 'Three pages of the route book had to be made false, and a Baandari clan now has a name, in a code that only they can read, for the person who brought the book home.',
    rewards: { gold: 300, loot: 'standard' },
  });

  f.q({
    id: 'the_hand_you_were_dealt', title: 'The Hand You Were Dealt', region: 'elsweyr/rimmen', start: 'npc:@twin_keeper', requires: ['level:8'],
    offer: 'You keep losing to the sky, Keeper.',
    pitch: 'Every time, walker! Every wager. Tonight is the full of Masser and the new of Secunda, and Ma\'tir has put her whole cellar on it being the other way round. She is a very bad gambler and a very honest one. A rider from Rimmen has been telling the house a different phase every night. Ma\'tir thinks he has been bribing the almanac. Please follow him.',
    desc: 'Ma\'tir of the Twin Moons keeps losing moon-phase wagers because a Rimmen rider feeds her false almanac phases.',
    objectives: [
      o.cond('night', 'Wait at the Twin Moons until the almanac-rider arrives', 'roadside_twin_moons'),
      o.kill('roadside_twin_moons', 'Stop the rider and take his bribed almanac',
        [mob('npc:thief', 1, 'Almanac-Rider Pho\'ra', { hostile: true })]),
      o.talk('@twin_keeper', 'Tell Ma\'tir the almanac was bribed',
        'The rider\'s almanac was pencilled wrong on purpose. Here is a true one.',
        'Ma\'tir snatches the almanac and checks it against the sky out the window. "WRONG! By a whole phase! Ha! Ha! He has been fleecing me for a year, and I have been blaming the sky!"'),
    ],
    outro: 'Ma\'tir now wins her moon-phase wagers about one night in three, and says it is a disgrace to the sky.',
    rewards: { gold: 170, loot: 'minor' },
  });
};
