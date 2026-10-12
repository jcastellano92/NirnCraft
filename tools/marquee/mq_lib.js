'use strict';
// Authoring helpers for the marquee quest files (quests/marquee_<province>.json). A province script
// (tools/marquee/mq_<province>.js) builds one File with npc/relic/boss/scene/quest calls and writes it to
// mod/src/main/resources/skycraft/quests/. Output is the ordinary story-engine format (docs/QUESTS.md); run
// `node tools/quest_lang.js` afterwards. Texts are original; names are lore facts or invented.
const fs = require('fs');
const path = require('path');

const QUEST_DIR = path.join(__dirname, '..', '..', 'mod', 'src', 'main', 'resources', 'skycraft', 'quests');
const LORE_DIR = path.join(__dirname, '..', '..', 'mod', 'src', 'main', 'resources', 'skycraft', 'lore', 'places');

class File {
  constructor(line, prefix, category = 'side') {
    this.line = line;
    this.prefix = prefix;
    this.category = category;
    this.speakers = {};
    this.npcs = [];
    this.relics = [];
    this.bosses = {};
    this.scenes = {};
    this.quests = [];
    this.lore = {};
  }

  // npc(id, name, role, place, dx, dz, greet, female?) - id is given without the prefix
  npc(id, name, role, place, dx, dz, greet, female = false) {
    const n = { id: this.prefix + id, name, role, place, offset: [dx, dz] };
    if (female) n.female = true;
    n.greet = greet;
    this.npcs.push(n);
    return n.id;
  }

  relic(id, name, desc, icon = 11) {
    const r = { id: this.prefix + id, name, desc, icon };
    this.relics.push(r);
    return r.id;
  }

  boss(id, spec) {
    this.bosses[this.prefix + id] = spec;
    return this.prefix + id;
  }

  scene(id, lines) {
    this.scenes[this.prefix + id] = lines.map(l => [l[0].startsWith('@') ? this.prefix + l[0].slice(1) : l[0], l[1]]);
    return this.prefix + id;
  }

  q(def) {
    const q = Object.assign({}, def);
    q.id = this.prefix + def.id;
    const fix = s => (typeof s === 'string' ? s.replace(/(^|[;|,:!])@(?=[a-z])/g, (m, a) => a + this.prefix) : s);
    if (q.start && q.start.startsWith('npc:@')) q.start = 'npc:' + this.prefix + q.start.slice(5);
    if (q.requires) q.requires = q.requires.map(r => fix(r.replace(/^(!?)(quest:|flag:)@/, '$1$2@')));
    if (q.on_complete) q.on_complete = fix(q.on_complete);
    if (q.on_start) q.on_start = fix(q.on_start);
    q.objectives = (q.objectives || []).map(o => {
      const x = Object.assign({}, o);
      if (x.npc) x.npc = x.npc.split('|').map(n => (n.startsWith('@') ? this.prefix + n.slice(1) : n)).join('|');
      if (x.relic && x.relic.startsWith('@')) x.relic = this.prefix + x.relic.slice(1);
      if (x.actions) x.actions = fix(x.actions);
      if (x.cond) x.cond = fix(x.cond);
      if (x.flag) x.flag = fix(x.flag);
      if (x.spawn) x.spawn = x.spawn.map(s => (s.boss_id && s.boss_id.startsWith('@') ? Object.assign({}, s, { boss_id: this.prefix + s.boss_id.slice(1) }) : s));
      if (x.choices) x.choices = x.choices.map(c => Object.assign({}, c, { actions: fix(c.actions) }));
      return x;
    });
    if (q.rewards && q.rewards.flags) q.rewards.flags = q.rewards.flags.map(f => f.replace(/^@/, this.prefix));
    this.quests.push(q);
    return q.id;
  }

  // lore(placeId, {hook, rumour, notes:[{id,kind,title,author,at,reveals,pages:[]}]})
  loreEntry(placeId, e) {
    this.lore[placeId] = e;
  }

  json() {
    const o = { line: this.line, category: this.category };
    if (Object.keys(this.speakers).length) o.speakers = this.speakers;
    o.npcs = this.npcs;
    o.relics = this.relics;
    if (Object.keys(this.bosses).length) o.bosses = this.bosses;
    if (Object.keys(this.scenes).length) o.scenes = this.scenes;
    o.quests = this.quests;
    return o;
  }

  write(name) {
    // one object per line for npcs/relics/quest-level, readable diffs
    const out = JSON.stringify(this.json(), null, 1) + '\n';
    fs.writeFileSync(path.join(QUEST_DIR, name), out);
    return this.quests.length;
  }
}

// objective builders (short names; every one takes the text players read in the journal)
const o = {
  go: (place, text, extra = {}) => Object.assign({ type: 'go', place, text }, extra),
  clear: (place, text, spawn, extra = {}) => Object.assign({ type: 'clear', place, text, spawn }, extra),
  kill: (place, text, spawn, extra = {}) => Object.assign({ type: 'kill', place, text, spawn }, extra),
  retrieve: (place, text, relic, extra = {}) => Object.assign({ type: 'retrieve', place, text, relic }, extra),
  retrieveItem: (place, text, item, count, extra = {}) => Object.assign({ type: 'retrieve', place, text, item, count }, extra),
  talk: (npc, text, topic, reply, extra = {}) => Object.assign({ type: 'talk', npc, text, topic, reply }, extra),
  deliver: (npc, relic, text, topic, reply, extra = {}) => Object.assign({ type: 'deliver', npc, relic, text, topic, reply }, extra),
  cond: (cond, text, place, extra = {}) => Object.assign({ type: 'cond', cond, text, place }, extra),
  wait: (hours, text, place, extra = {}) => Object.assign({ type: 'wait', hours, text, place }, extra),
  escort: (npc, place, text, extra = {}) => Object.assign({ type: 'escort', npc, place, text }, extra),
  discover: (place, text, extra = {}) => Object.assign({ type: 'discover', place, text }, extra),
};
const choice = (label, reply, actions) => ({ label, reply, actions });
const mob = (type, count, name, extra = {}) => Object.assign({ type, count, name }, extra);

// write a lore file (lore/places/<name>.json); the caller adds the name to lore/places/index.json via addToIndex
function writeLore(name, places) {
  fs.writeFileSync(path.join(LORE_DIR, name), JSON.stringify({ places }, null, 1) + '\n');
}
function addToIndex(dir, file) {
  const idx = path.join(dir, 'index.json');
  const list = JSON.parse(fs.readFileSync(idx, 'utf8'));
  if (!list.includes(file)) {
    list.push(file);
    fs.writeFileSync(idx, JSON.stringify(list, null, 2) + '\n');
  }
}

module.exports = { File, o, choice, mob, writeLore, addToIndex, QUEST_DIR, LORE_DIR };
