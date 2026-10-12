'use strict';
// Builds quests/marquee_elsweyr.json (and lore/places/marquee_elsweyr.json). Run: node tools/marquee/mq_elsweyr.js
const fs = require('fs');
const { File, writeLore, addToIndex, LORE_DIR, QUEST_DIR } = require('./mq_lib');
const f = new File('marquee_elsweyr', 'mq_els_');
require('./mq_els_a')(f);
if (fs.existsSync(__dirname + '/mq_els_b.js')) require('./mq_els_b')(f);
if (fs.existsSync(__dirname + '/mq_els_c.js')) require('./mq_els_c')(f);
const n = f.write('marquee_elsweyr.json');
console.log('elsweyr: ' + f.npcs.length + ' npcs, ' + n + ' quests');
if (fs.existsSync(__dirname + '/mq_els_lore.js')) {
  const lore = require('./mq_els_lore');
  writeLore('marquee_elsweyr.json', lore);
  addToIndex(LORE_DIR, 'marquee_elsweyr.json');
  console.log('elsweyr lore: ' + Object.keys(lore).length + ' places');
}
addToIndex(QUEST_DIR, 'marquee_elsweyr.json');
