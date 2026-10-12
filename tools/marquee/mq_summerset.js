'use strict';
// Builds quests/marquee_summerset.json and lore/places/marquee_summerset.json. Run: node tools/marquee/mq_summerset.js
const { File, writeLore, addToIndex, LORE_DIR, QUEST_DIR } = require('./mq_lib');
const f = new File('marquee_summerset', 'mq_sum_');
require('./mq_sum_a')(f);
require('./mq_sum_b')(f);
require('./mq_sum_c')(f);
const n = f.write('marquee_summerset.json');
console.log('summerset: ' + f.npcs.length + ' npcs, ' + n + ' quests');
const lore = require('./mq_sum_lore');
writeLore('marquee_summerset.json', lore);
addToIndex(LORE_DIR, 'marquee_summerset.json');
console.log('summerset lore: ' + Object.keys(lore).length + ' places');
addToIndex(QUEST_DIR, 'marquee_summerset.json');
