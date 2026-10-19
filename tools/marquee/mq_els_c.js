'use strict';
// Elsweyr marquee content, part C: more side quests for Orcrest and Hakoshae, Senchal and Torval.
const { o, choice, mob } = require('./mq_lib');

module.exports = function (f) {
  f.relic('stolen_banner', 'The Claw Gate Banner', 'A great saffron banner embroidered with a pair of black claws, folded over a rope. It is fraying at the edge where it was carried by someone in a hurry, and smells of rival-village woodsmoke.', 11);
  f.relic('cub_charm', 'Moon-Cub\'s Charm', 'A little charm of two silver crescents on a thread, to be tied round a newborn\'s wrist at the first night when both moons show. This one has not yet been blessed.', 20);
  f.relic('moon_sugar_cask', 'Cask of Blessed Moon-Sugar', 'A small oak cask sealed with the Moon-Priests\' pawprint. It is faintly warm, and smells of honey and of something older. The seal has been pried and mended with wax.', 11);
  f.boss('banner_taker', { name: 'Kasha Hill-Banner', type: 'nirncraft:bandit_chief', hp: 2.8, dmg: 1.3, level: 18,
    weapon: 'nirncraft:khajiit_war_axe', drop: 'loot:standard', glow: false });
  f.boss('cask_watcher', { name: 'The Cask-Warden of Senchal', type: 'nirncraft:bandit_chief', hp: 3.0, dmg: 1.4, level: 22,
    weapon: 'nirncraft:khajiit_sword', gear: { chest: 'nirncraft:khajiit_chestplate' }, drop: 'loot:standard', glow: false });

  f.q({
    id: 'the_borrowed_banner', title: 'The Borrowed Banner', region: 'elsweyr/anequina', start: 'npc:@raz', requires: ['level:12'],
    offer: 'Your flagpole is bare, Captain.',
    pitch: 'Bare, walker, and Raz will not discuss it in the open air. The Claw Gate banner was lifted in the night by the lads of a hill-village that has never forgiven Orcrest for a lost game of knucklebones. They call it borrowing. Raz calls it a war. He cannot send guards: that makes it a war. A stranger may simply walk in, at night, and walk out again. If the stranger is seen, it was a misunderstanding. If not, it was a miracle.',
    desc: 'Rivals from a hill-village stole Orcrest\'s Claw Gate banner as a prank. Gate-Captain Raz wants a stranger to take it back in the night, quietly.',
    objectives: [
      o.go('talon_gulch', 'Scout the hill-village camp in Talon Gulch'),
      o.cond('night', 'Wait for night, when the prank-makers sleep', 'talon_gulch'),
      o.retrieve('talon_gulch', 'Take the Claw Gate banner from the rival camp', '@stolen_banner'),
      o.kill('talon_gulch', 'Slip away from Kasha Hill-Banner, who wakes',
        [{ boss_id: '@banner_taker' }], { placement: 'structure' }),
      o.deliver('@raz', '@stolen_banner', 'Raise the banner over the Claw Gate',
        'Your banner, whole. The rivals did not do it for malice. They were hoping you would climb the hill to fetch it, and say sorry about the knucklebones.',
        'Raz looks at the banner a long moment, then at the gate, then, for the first time in your acquaintance, at his own feet. "Knucklebones. Thirty years. Raz cheated, you see. He knew. He has always known. Walker, shall Raz walk up that hill with a cask and an apology, or raise the banner and pretend he has not heard?"',
        { choices: [
          choice('Walk up the hill. An old debt is lighter shared.', 'Raz straightens, almost smiling. "A cask and an apology. And a rematch, at knucklebones, honestly played. Thank you, walker. The gate will be braver for it."', 'flag:@banner_apology'),
          choice('Raise the banner and say nothing. Let the prank be the end of it.', 'Raz nods, and hoists the banner himself. "Then it flies. And the lads on the hill will know they won, quietly, and so will Raz. That is the nicest kind of war."', 'flag:@banner_raised;loot:standard'),
        ] }),
    ],
    outro: 'The Claw Gate banner flies again. A cask went up the hill at dawn with a very polite note, and an old game of knucklebones was, at long last, replayed.',
    rewards: { gold: 330, loot: 'standard' },
  });

  f.q({
    id: 'the_naming_under_two_moons', title: 'The Naming Under Two Moons', region: 'elsweyr/rimmen', start: 'npc:@suri', requires: ['level:14'],
    offer: 'Your spear is garlanded with ribbon.',
    pitch: 'It is a night for it, walker. A Hakoshae cub is to be named under both moons, and the rite wants a witness who is no kin and has no stake. The mother is ill. The charm has not been blessed. The blessing must be done at the Temple of the Hidden Moon, at the hour both moons stand over its door, and it must be done by a pair of hands that have not held a weapon since dusk. Su\'ri cannot lay down her spear. Will you carry the charm?',
    desc: 'A Hakoshae cub must be named under both moons. Guardian Su\'ri asks a stranger to carry the charm to the Temple of the Hidden Moon for its blessing.',
    objectives: [
      o.talk('@suri', 'Take the unblessed charm from Su\'ri',
        'I will carry it. Tell me the words of the blessing.',
        'Su\'ri sets the charm in your palm with great care, and looks away, as one does from a thing that is not hers to see. "Two moons, two crescents, one thread. Say the name of the cub when both stand over the door. Do not say it before, and do not say it after. A name said at the wrong hour goes looking for someone else."',
        { actions: 'relic:@cub_charm' }),
      o.go('temple_of_the_hidden_moon', 'Carry the charm to the Temple of the Hidden Moon'),
      o.wait(2, 'Wait in the temple for both moons to stand over the door', 'temple_of_the_hidden_moon'),
      o.deliver('@suri', '@cub_charm', 'Return the blessed charm to Su\'ri',
        'The moons stood over the door. I said the cub\'s name. The charm is warm. Tie it to her wrist.',
        'Su\'ri takes the charm, and her voice is very low. "Warm. It is warm. The name took. Walker, the mother asks one thing: that the cub\'s second name be given by you. A cub with a stranger\'s second name is never lost on a road. What shall it be?"',
        { choices: [
          choice('Name her for the road: Ja\'hira, \'she who returns\'.', 'Su\'ri closes her eyes and says it over, very softly, three times. "Ja\'hira. She who returns. It is a good name, and a brave one. Walker, Hakoshae will say it at every doorway."', 'flag:@naming_road'),
          choice('Name her for the stone: Ri\'sha, \'she who waits\'.', 'Su\'ri smiles into her collar. "Ri\'sha. The ring keeps those who wait. It is a gentle name. The mother will weep, and the cub will sleep."', 'flag:@naming_stone;loot:standard'),
        ] }),
    ],
    outro: 'A cub of Hakoshae bears two names, one from her mother and one from a stranger who held a charm and said nothing at the wrong hour.',
    rewards: { gold: 360, loot: 'standard' },
  });

  f.q({
    id: 'the_resealed_cask', title: 'The Resealed Cask', region: 'elsweyr/senchal', start: 'npc:dar_jazad', requires: ['level:16'],
    offer: 'Dar\'jazad has a cask that should not be here.',
    pitch: 'Not here, no, and not like this, walker. The Moon-Priests\' seal is on it, and the seal has been pried and mended with wax. A blessed cask is not to be opened except at the Temple. Someone opened it, took a share, and resealed it badly. Dar\'jazad cannot return it to the temple unless the thief is found. A priest\'s wrath is a gentle thing, until it is not.',
    desc: 'A cask of blessed moon-sugar was opened and badly resealed in Senchal. Dar\'jazad wants the culprit found before the Moon-Priests notice.',
    objectives: [
      o.talk('@jakhar', 'Ask Customs-Master Ja\'khar which cart carried the cask',
        'A blessed cask passed your quay with a broken seal. Whose cart?',
        'Ja\'khar sets down his stamp. "A cask with a pried seal. Stamped by the Warden of the cask-store, one night, Tuesday. A cat with a scar and a very good alibi. Ja\'khar has no evidence. He has a strong suspicion, and a tidy mind."'),
      o.go('the_forgotten_mane', 'Search the Forgotten Mane, where the cask-store keeps its overflow'),
      o.kill('the_forgotten_mane', 'Take down the Cask-Warden and recover the stolen share',
        [{ boss_id: '@cask_watcher' }], { actions: 'relic:@moon_sugar_cask' }),
      o.deliver('dar_jazad', '@moon_sugar_cask', 'Return the cask to Dar\'jazad',
        'Your cask, whole. The Warden took a share to sell to a skooma lord. It is returned.',
        'Dar\'jazad turns the cask once and sets it in the lamplight. "The seal is mended. The share is returned. Walker, the Warden is dead, and the Moon-Priests will ask who. Shall Dar\'jazad say a thief, or tell the whole tale of the skooma lord, and let the temple deal with it?"',
        { choices: [
          choice('Say a thief. The skooma lord can wait.', 'Dar\'jazad nods. "A thief, then. A small lie. It will keep the temple calm, and the lord complacent. We shall see which of us is cleverer."', 'flag:@cask_thief'),
          choice('Tell the whole tale. The temple deserves to know.', 'Dar\'jazad exhales. "The whole tale. Then there will be a reckoning, and it will be loud. But the moons love a plain truth, and Senchal has been quiet too long."', 'flag:@cask_truth;loot:standard'),
        ] }),
    ],
    outro: 'A blessed cask was returned to its temple with the seal repaired, and a very quiet skooma lord in Senchal now sleeps rather badly.',
    rewards: { gold: 450, loot: 'standard' },
  });

  f.q({
    id: 'the_cub_who_read_the_ledger', title: 'The Cub Who Read the Ledger', region: 'elsweyr/torval', start: 'npc:@tazhi', requires: ['level:10'],
    offer: 'You are a runner of messages.',
    pitch: 'Runner, yes! Tazhi has read none of them. All of them. A very long one, today, from the Court to the Exchange, sealed with wax. Tazhi did not break it. The seal broke by itself, when Tazhi sneezed. The note inside says: move the cask at midnight, and the warden will not look. Tazhi did not understand it. Then Tazhi did. Tazhi does not wish to be a cub who knew. Please carry it back, sealed, and say nothing.',
    desc: 'Cub-runner Tazhi accidentally read a sealed note ordering a cask moved past a bribed warden. She asks a stranger to return it unread.',
    objectives: [
      o.talk('@jaro', 'Return the note to Mane\'s Scribe Jaro, resealed',
        'The cub dropped this. The seal has been mended. I did not read it.',
        'Jaro takes the note and for a moment says nothing. Then, under his breath: "A cub. A cub, who read it. She is a very honest child, to return it. Walker, the note is not a thing the Court would like to have been read. Tazhi has done right. Jaro will see she is looked after, and a very good, very quiet apprenticeship found."'),
      o.talk('@tazhi', 'Tell Tazhi she is safe',
        'You did right. The scribe is looking after you. Say nothing, and you will be a scribe yourself one day.',
        'Tazhi\'s ears flatten with relief, and then rise slowly, slowly. "A scribe! Tazhi has always wanted to be a scribe! But Tazhi will not read any more notes. Not even by accident. Tazhi will sneeze into a handkerchief, like a lady."'),
    ],
    outro: 'A cub of Torval has an apprenticeship at the Lunar Ledger, and a handkerchief, and a great deal of respect for wax.',
    rewards: { gold: 160, loot: 'minor' },
  });
};
