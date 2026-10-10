#!/usr/bin/env node
// Exports every text of the story quests (mod/src/main/resources/skycraft/quests/*.json) into
// mod/src/main/lang/skycraft/quest_story.json, with the same keys com.skycraft.quest.story.StoryData derives,
// so translations and resource packs can replace any line. Also validates the files (JSON syntax, ids, NPC and
// relic references). Run from anywhere after editing the quest data:  node tools/quest_lang.js
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', 'mod', 'src', 'main');
const dir = path.join(root, 'resources', 'skycraft', 'quests');
const out = path.join(root, 'lang', 'skycraft', 'quest_story.json');

const lang = {};
const problems = [];
function put(key, text) {
  if (text === undefined || text === null || text === '') return;
  if (lang[key] !== undefined && lang[key] !== text) problems.push(`duplicate key ${key}`);
  lang[key] = text;
}

const files = JSON.parse(fs.readFileSync(path.join(dir, 'index.json'), 'utf8'));
const quests = {}, npcs = {}, relics = {}, scenes = {};
const data = [];
for (const f of files) {
  let j;
  try {
    j = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
  } catch (e) {
    console.error(`${f}: ${e.message}`);
    process.exitCode = 1;
    continue;
  }
  data.push([f, j]);
  for (const [id, name] of Object.entries(j.speakers || {})) put(`story.skycraft.speaker.${id}`, name);
  for (const n of j.npcs || []) {
    if (npcs[n.id]) problems.push(`${f}: duplicate npc ${n.id}`);
    npcs[n.id] = n;
    put(`story.skycraft.npc.${n.id}`, n.name);
    put(`story.skycraft.npc.${n.id}.greet`, n.greet);
  }
  for (const r of j.relics || []) {
    relics[r.id] = r;
    put(`item.skycraft.relic.${r.id}`, r.name);
    put(`item.skycraft.relic.${r.id}.desc`, r.desc);
  }
  for (const [sid, lines] of Object.entries(j.scenes || {})) {
    scenes[sid] = true;
    lines.forEach((l, i) => put(`story.skycraft.scene.${sid}.${i}`, l[1]));
  }
  for (const q of j.quests || []) {
    if (quests[q.id]) problems.push(`${f}: duplicate quest ${q.id}`);
    quests[q.id] = q;
    const k = `story.skycraft.q.${q.id}`;
    for (const field of ['title', 'desc', 'offer', 'pitch', 'outro']) put(`${k}.${field}`, q[field]);
    (q.objectives || []).forEach((o, i) => {
      for (const field of ['text', 'topic', 'reply', 'journal']) put(`${k}.o${i}.${field}`, o[field]);
      (o.choices || []).forEach((c, ci) => {
        put(`${k}.o${i}.c${ci}.label`, c.label);
        put(`${k}.o${i}.c${ci}.reply`, c.reply);
      });
      (o.spawn || []).forEach((s, si) => put(`${k}.o${i}.s${si}`, s.name));
    });
    ((q.rewards || {}).items || []).forEach((it, ri) => {
      if (typeof it === 'object' && it.name) put(`${k}.r${ri}`, it.name);
    });
  }
}

// references
const places = JSON.parse(fs.readFileSync(path.join(dir, 'places.json'), 'utf8'));
const worldPlaces = new Set();
const pdir = path.join(root, 'resources', 'skycraft', 'tamriel', 'places');
for (const f of JSON.parse(fs.readFileSync(path.join(pdir, 'index.json'), 'utf8'))) {
  try {
    for (const p of JSON.parse(fs.readFileSync(path.join(pdir, f), 'utf8'))) worldPlaces.add(p.id);
  } catch (e) { /* not ours */ }
}
const known = id => !id || id.startsWith('@') || places[id] || worldPlaces.has(id);
for (const [f, j] of data) {
  for (const n of j.npcs || []) if (!known(n.place)) problems.push(`${f}: npc ${n.id} lives at unknown place ${n.place}`);
  for (const q of j.quests || []) {
    const start = q.start || 'manual';
    if (start.startsWith('npc:') && !npcs[start.slice(4)]) problems.push(`${f}: ${q.id} offered by unknown npc ${start}`);
    if (q.giver && !npcs[q.giver]) problems.push(`${f}: ${q.id} giver ${q.giver} unknown`);
    for (const r of q.requires || []) {
      for (const part of r.split('|')) {
        const c = part.replace(/^!/, '');
        if (c.startsWith('quest:') && !quests[c.slice(6)]) problems.push(`${f}: ${q.id} requires unknown ${c}`);
      }
    }
    (q.objectives || []).forEach((o, i) => {
      if (!o.type) problems.push(`${f}: ${q.id} objective ${i} has no type`);
      if (!known(o.place)) problems.push(`${f}: ${q.id} objective ${i} unknown place ${o.place}`);
      for (const n of (o.npc || '').split('|')) if (n && !npcs[n]) problems.push(`${f}: ${q.id} objective ${i} unknown npc ${n}`);
      if (o.relic && !relics[o.relic]) problems.push(`${f}: ${q.id} objective ${i} unknown relic ${o.relic}`);
      if ((o.type === 'talk' || o.type === 'deliver' || o.type === 'escort') && !o.npc) problems.push(`${f}: ${q.id} objective ${i} (${o.type}) has no npc`);
      if (o.type === 'deliver' && !o.relic && !o.item) problems.push(`${f}: ${q.id} objective ${i} delivers nothing`);
      const acts = [o.actions || '', ...(o.choices || []).map(c => c.actions || '')].join(';');
      for (const a of acts.split(';').map(s => s.trim()).filter(Boolean)) {
        if (a.startsWith('scene:') && !scenes[a.slice(6)]) problems.push(`${f}: ${q.id} unknown scene ${a}`);
        if (a.startsWith('relic:') && !relics[a.slice(6)]) problems.push(`${f}: ${q.id} unknown relic ${a}`);
        if (a.startsWith('start:') && !quests[a.slice(6)]) problems.push(`${f}: ${q.id} starts unknown ${a}`);
      }
    });
    for (const a of [q.on_start || '', q.on_complete || ''].join(';').split(';').map(s => s.trim()).filter(Boolean)) {
      if (a.startsWith('scene:') && !scenes[a.slice(6)]) problems.push(`${f}: ${q.id} unknown scene ${a}`);
      if (a.startsWith('start:') && !quests[a.slice(6)]) problems.push(`${f}: ${q.id} starts unknown ${a}`);
    }
  }
}

const sorted = {};
for (const k of Object.keys(lang).sort()) sorted[k] = lang[k];
fs.writeFileSync(out, JSON.stringify(sorted, null, 2) + '\n');
console.log(`${Object.keys(quests).length} quests, ${Object.keys(npcs).length} NPCs, ${Object.keys(relics).length} relics, `
  + `${Object.keys(scenes).length} scenes, ${Object.keys(lang).length} lang keys -> ${path.relative(process.cwd(), out)}`);
if (problems.length) {
  console.log(problems.join('\n'));
  process.exitCode = 1;
}
