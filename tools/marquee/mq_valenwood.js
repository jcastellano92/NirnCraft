'use strict';
// Builds quests/marquee_valenwood.json and lore/places/marquee_valenwood.json. Run: node tools/marquee/mq_valenwood.js
const fs = require('fs');
const { File, writeLore, addToIndex, LORE_DIR, QUEST_DIR } = require('./mq_lib');
const f = new File('marquee_valenwood', 'mq_val_');
require('./mq_val_a')(f);
require('./mq_val_b')(f);
require('./mq_val_c')(f);
const n = f.write('marquee_valenwood.json');
console.log('valenwood: ' + f.npcs.length + ' npcs, ' + n + ' quests');
const lore = require('./mq_val_lore');
writeLore('marquee_valenwood.json', lore);
addToIndex(LORE_DIR, 'marquee_valenwood.json');
console.log('valenwood lore: ' + Object.keys(lore).length + ' places');
addToIndex(QUEST_DIR, 'marquee_valenwood.json');
