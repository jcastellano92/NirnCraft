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

// --dry: check only (no lang output); --with <file.json>: also check a quest file not yet listed in index.json
const args = process.argv.slice(2);
const dry = args.includes('--dry');
const files = JSON.parse(fs.readFileSync(path.join(dir, 'index.json'), 'utf8'));
for (let i = 0; i < args.length; i++) if (args[i] === '--with' && args[i + 1] && !files.includes(args[i + 1])) files.push(args[++i]);
const quests = {}, npcs = {}, relics = {}, scenes = {}, filePlaces = {}, bosses = {};
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
  // fallback anchors a questline brings along for lore sites the world doesn't list (same format as places.json)
  for (const [id, p] of Object.entries(j.places || {})) {
    if (typeof p.x !== 'number' || typeof p.z !== 'number') problems.push(`${f}: place ${id} needs numeric x and z`);
    if (filePlaces[id]) problems.push(`${f}: place ${id} is also declared in ${filePlaces[id]}`);
    filePlaces[id] = f;
  }
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
  for (const [bid, b] of Object.entries(j.bosses || {})) {
    if (bosses[bid]) problems.push(`${f}: duplicate boss ${bid}`);
    bosses[bid] = b;
    put(`story.skycraft.boss.${bid}`, b.name);
    if (!b.name || !b.type) problems.push(`${f}: boss ${bid} needs a name and a type`);
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
    if (q.find) put(`${k}.find`, q.find.text);
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

// radiant archetype templates (com.skycraft.quest.story.RadiantTemplates)
const radiantFile = path.join(dir, 'radiant_templates.json');
let radiantCount = 0;
if (fs.existsSync(radiantFile)) {
  try {
    const seen = new Set();
    for (const t of JSON.parse(fs.readFileSync(radiantFile, 'utf8')).templates || []) {
      radiantCount++;
      if (seen.has(t.id)) problems.push(`radiant_templates.json: duplicate template ${t.id}`);
      seen.add(t.id);
      const k = `story.skycraft.radiant.${t.id}`;
      for (const field of ['title', 'desc', 'pitch']) {
        if (!t[field]) problems.push(`radiant_templates.json: ${t.id} has no ${field}`);
        put(`${k}.${field}`, t[field]);
      }
      (t.names || []).forEach((n, i) => put(`${k}.n${i}`, n));
      (t.steps || []).forEach((s, i) => {
        put(`${k}.s${i}`, s.text);
        if (s.relic && !relics[s.relic]) problems.push(`radiant_templates.json: ${t.id} step ${i} unknown relic ${s.relic}`);
      });
    }
  } catch (e) {
    console.error(`radiant_templates.json: ${e.message}`);
    process.exitCode = 1;
  }
}

// references
const places =JSON.parse(fs.readFileSync(path.join(dir, 'places.json'), 'utf8'));
const worldPlaces = new Set();
const pdir = path.join(root, 'resources', 'skycraft', 'tamriel', 'places');
for (const f of JSON.parse(fs.readFileSync(path.join(pdir, 'index.json'), 'utf8'))) {
  try {
    for (const p of JSON.parse(fs.readFileSync(path.join(pdir, f), 'utf8'))) worldPlaces.add(p.id);
  } catch (e) { /* not ours */ }
}
const known = id => !id || id.startsWith('@') || places[id] || worldPlaces.has(id) || filePlaces[id];
// side quests: legendary unique ids (com.skycraft.loot.LegendaryUniques), loot tiers, bosses, hidden clues
const uniqueSrc = fs.readFileSync(path.join(root, 'java', 'com', 'skycraft', 'loot', 'LegendaryUniques.java'), 'utf8');
const uniques = new Set([...uniqueSrc.matchAll(/^\s+def\("([a-z_]+)"/gm)].map(m => m[1]));
const tiers = new Set(['minor', 'standard', 'major', 'legendary']);
const clueKinds = new Set(['skeleton', 'stone', 'lever', 'altar', 'grave', 'lectern', 'urn', 'totem']);
function checkDrop(where, drop) {
  for (const d of String(drop).split(',').map(s => s.trim())) {
    if (d.startsWith('unique:') && !uniques.has(d.slice(7))) problems.push(`${where}: unknown unique ${d}`);
    if (d.startsWith('loot:') && !tiers.has(d.slice(5))) problems.push(`${where}: unknown loot tier ${d}`);
  }
}
// skycraft item ids: everything with an item/block name in the merged lang files
const skyItems = new Set();
for (const lf of fs.readdirSync(path.join(root, 'lang', 'skycraft'))) {
  try {
    for (const k of Object.keys(JSON.parse(fs.readFileSync(path.join(root, 'lang', 'skycraft', lf), 'utf8')))) {
      const m = k.match(/^(?:item|block)\.skycraft\.([a-z0-9_]+)$/);
      if (m) skyItems.add(m[1]);
    }
  } catch (e) { /* not ours */ }
}
function checkItem(where, spec) {
  if (typeof spec !== 'string' || !spec.startsWith('skycraft:')) return;
  const id = spec.slice(9).replace(/\*\d+$/, '');
  if (!skyItems.has(id)) problems.push(`${where}: unknown item ${spec}`);
}
for (const [bid, b] of Object.entries(bosses)) {
  if (b.drop) {
    checkDrop(`boss ${bid}`, b.drop);
    for (const d of String(b.drop).split(',')) checkItem(`boss ${bid}`, d.trim());
  }
  checkItem(`boss ${bid}`, b.weapon);
  for (const g of Object.values(b.gear || {})) checkItem(`boss ${bid}`, g);
}
for (const [f, j] of data) {
  for (const q of j.quests || []) {
    for (const it of (q.rewards || {}).items || []) checkItem(`${f}: ${q.id}`, typeof it === 'object' ? it.item : it);
    (q.objectives || []).forEach(o => {
      if (o.item) checkItem(`${f}: ${q.id}`, o.item);
      for (const s of o.spawn || []) checkItem(`${f}: ${q.id}`, s.weapon);
      for (const a of (o.actions || '').split(';').map(s => s.trim()).filter(s => s.startsWith('give:'))) {
        for (const g of a.slice(5).split(',')) checkItem(`${f}: ${q.id}`, g.trim());
      }
    });
  }
}
for (const [f, j] of data) {
  for (const q of j.quests || []) {
    const r = q.rewards || {};
    if (r.loot && !tiers.has(r.loot)) problems.push(`${f}: ${q.id} unknown loot tier ${r.loot}`);
    if (r.unique && !uniques.has(r.unique)) problems.push(`${f}: ${q.id} unknown unique ${r.unique}`);
    if ((q.start === 'find') !== !!q.find) problems.push(`${f}: ${q.id} needs both "start": "find" and a find block`);
    if (q.find) {
      if (!q.find.place || !known(q.find.place)) problems.push(`${f}: ${q.id} hidden clue at unknown place ${q.find.place}`);
      if (q.find.clue && !clueKinds.has(q.find.clue)) problems.push(`${f}: ${q.id} unknown clue kind ${q.find.clue}`);
    }
    (q.objectives || []).forEach((o, i) => {
      for (const s of o.spawn || []) if (s.boss_id && !bosses[s.boss_id]) problems.push(`${f}: ${q.id} objective ${i} unknown boss ${s.boss_id}`);
    });
    const acts = [q.on_start || '', q.on_complete || '', ...(q.objectives || []).flatMap(o => [o.actions || '', ...(o.choices || []).map(c => c.actions || '')])];
    for (const a of acts.join(';').split(';').map(s => s.trim()).filter(Boolean)) {
      if (a.startsWith('boss:') && !bosses[a.slice(5)]) problems.push(`${f}: ${q.id} action ${a} names an unknown boss`);
      if (a.startsWith('unique:') && !uniques.has(a.slice(7))) problems.push(`${f}: ${q.id} action ${a} names an unknown unique`);
      if (a.startsWith('loot:') && !tiers.has(a.slice(5))) problems.push(`${f}: ${q.id} action ${a} names an unknown loot tier`);
    }
  }
}
// faction ids (enum com.skycraft.quest.Faction): file/quest factions, NPC halls and faction actions must name one
const factionSrc = fs.readFileSync(path.join(root, 'java', 'com', 'skycraft', 'quest', 'Faction.java'), 'utf8');
const factions = new Set([...factionSrc.matchAll(/^\s+[A-Z_]+\("([a-z_]+)", \d+/gm)].map(m => m[1]));
for (const [f, j] of data) {
  if (j.faction && !factions.has(j.faction)) problems.push(`${f}: unknown faction ${j.faction}`);
  for (const n of j.npcs || []) if (n.hall && !factions.has(n.hall)) problems.push(`${f}: npc ${n.id} lives in the hall of unknown faction ${n.hall}`);
  for (const q of j.quests || []) {
    if (q.faction && !factions.has(q.faction)) problems.push(`${f}: ${q.id} unknown faction ${q.faction}`);
    const acts = [q.on_start || '', q.on_complete || '', ...(q.objectives || []).flatMap(o => [o.actions || '', ...(o.choices || []).map(c => c.actions || '')])];
    for (const a of acts.join(';').split(';').map(s => s.trim()).filter(Boolean)) {
      const m = a.match(/^(join|expel|points):([a-z_]+)/);
      if (m && !factions.has(m[2])) problems.push(`${f}: ${q.id} action ${a} names an unknown faction`);
    }
    for (const r of q.requires || []) {
      for (const part of r.split('|')) {
        const m = part.replace(/^!/, '').match(/^faction:([a-z_]+)/);
        if (m && !factions.has(m[1])) problems.push(`${f}: ${q.id} requires unknown faction ${m[1]}`);
      }
    }
  }
}

// a questline's own anchor for a site the world now lists is harmless (the world's place wins), just noted
for (const id of Object.keys(filePlaces)) {
  if (places[id] || worldPlaces.has(id)) console.log(`note: ${filePlaces[id]} declares place ${id}, which the world already lists`);
}
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
if (!dry) fs.writeFileSync(out, JSON.stringify(sorted, null, 2) + '\n');
console.log(`${Object.keys(quests).length} quests, ${Object.keys(npcs).length} NPCs, ${Object.keys(relics).length} relics, `
  + `${Object.keys(scenes).length} scenes, ${Object.keys(bosses).length} bosses, ${radiantCount} radiant templates, `
  + `${Object.keys(lang).length} lang keys -> ${path.relative(process.cwd(), out)}`);
if (problems.length) {
  console.log(problems.join('\n'));
  process.exitCode = 1;
}
