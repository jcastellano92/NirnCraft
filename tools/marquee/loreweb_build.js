#!/usr/bin/env node
// Builds the lore web (docs/LORE-WEB.md) from the authored sources in tools/marquee/loreweb_src/*.json:
//   tools/marquee/loreweb.json                                        machine-readable situations, arcs, threads
//   mod/src/main/resources/skycraft/lore/places/loreweb.json          hooks, rumours and readable notes at places
//   mod/src/main/resources/skycraft/quests/loreweb_<province>.json    hidden 'find' arc quests (ids lw_<arc>_a/_b)
//   mod/src/main/resources/skycraft/quests/loreweb_threads.json       continent-scale thread quests (lw_t_*)
// It also registers the two data files in the lore and quest index.json files.
// Run:  node tools/marquee/loreweb_build.js   then   node tools/place_lore.js; node tools/quest_lang.js;
//       node tools/marquee/loreweb_check.js
// Places that already have lore in another file keep it: the web only adds its notes to places without lore, and
// states their situation in loreweb.json and the quests.
'use strict';
const fs = require('fs');
const path = require('path');

const repo = path.join(__dirname, '..', '..');
const res = path.join(repo, 'mod', 'src', 'main', 'resources', 'skycraft');
const srcDir = path.join(__dirname, 'loreweb_src');
const readJson = f => JSON.parse(fs.readFileSync(f, 'utf8'));
const problems = [];
const bad = m => problems.push(m);

// ---- the world
const marquee = readJson(path.join(__dirname, 'marquee.json'));
const entries = {};
for (const P of marquee.provinces) {
  for (const [key, kind] of [['settlements', 'settlement'], ['dungeons', 'dungeon'], ['landmarks', 'landmark'], ['minors', 'minor']]) {
    for (const e of P[key]) entries[e.id] = { id: e.id, province: P.province, kind, tier: e.tier };
  }
}
const places = {};
const pdir = path.join(res, 'tamriel', 'places');
for (const f of readJson(path.join(pdir, 'index.json'))) for (const p of readJson(path.join(pdir, f))) places[p.id] = p;
const regionIds = new Set();
{
  const r = readJson(path.join(res, 'tamriel', 'regions.json'));
  for (const x of (Array.isArray(r) ? r : r.regions || Object.values(r))) if (x && x.id) regionIds.add(x.id);
}
// lore that other files already hold (our own file is rebuilt from scratch)
const loreOwner = {};
const loreDir = path.join(res, 'lore', 'places');
for (const f of readJson(path.join(loreDir, 'index.json'))) {
  if (f === 'loreweb.json') continue;
  for (const id of Object.keys(readJson(path.join(loreDir, f)).places || {})) loreOwner[id] = f;
}

// ---- the sources
const provinces = ['cyrodiil', 'morrowind', 'solstheim', 'high_rock', 'hammerfell', 'elsweyr', 'valenwood', 'summerset', 'black_marsh', 'foreign'];
const arcs = [];
const sit = {};
for (const p of provinces) {
  const j = readJson(path.join(srcDir, p + '.json'));
  for (const a of j.arcs) { a.province = p; arcs.push(a); }
  for (const [id, s] of Object.entries(j.sit)) {
    if (sit[id]) bad(`situation ${id} written twice`);
    sit[id] = s;
  }
}
const threads = readJson(path.join(srcDir, 'threads.json')).threads;
// late additions: rumours and notes for places that turned out to have no lore elsewhere
for (const [id, x] of Object.entries(readJson(path.join(srcDir, 'extra.json')))) {
  if (!sit[id]) { problems.push('extra.json: unknown situation ' + id); continue; }
  if (!sit[id].r && x.r) sit[id].r = x.r;
  if (!sit[id].n && x.n) sit[id].n = x.n;
}

// ---- coverage and links
const arcOf = {};
for (const a of arcs) {
  if (a.order.length < 4 || a.order.length > 8) bad(`arc ${a.id}: needs 4-8 places (${a.order.length})`);
  a.order.forEach((id, i) => {
    if (!entries[id]) bad(`arc ${a.id}: ${id} is not a marquee place`);
    else if (entries[id].province !== a.province && !(a.province === 'solstheim' && entries[id].province === 'solstheim')) bad(`arc ${a.id}: ${id} belongs to ${entries[id].province}`);
    if (arcOf[id]) bad(`${id} is in arcs ${arcOf[id].arc.id} and ${a.id}`);
    arcOf[id] = { arc: a, i };
  });
}
const threadOf = {};
for (const t of threads) {
  t.order.forEach((id, i) => {
    if (!entries[id]) bad(`thread ${t.id}: ${id} is not a marquee place`);
    if (threadOf[id]) bad(`${id} is in threads ${threadOf[id].thread.id} and ${t.id}`);
    threadOf[id] = { thread: t, i };
    if (!t.facets[id] || !t.facets[id].lead || !t.facets[id].clue) bad(`thread ${t.id}: facet for ${id} needs lead and clue`);
  });
}
for (const id of Object.keys(entries)) {
  if (!sit[id]) bad(`no situation for ${id}`);
  if (!arcOf[id]) bad(`${id} is in no arc`);
}
for (const id of Object.keys(sit)) if (!entries[id]) bad(`situation ${id} is not a marquee place`);

const provArcs = {};
for (const a of arcs) (provArcs[a.province] = provArcs[a.province] || []).push(a);

function follow(id) {
  const { arc, i } = arcOf[id];
  const th = threadOf[id];
  const out = [];
  const add = x => { if (x && x !== id && !out.includes(x) && entries[x]) out.push(x); };
  if (i < arc.order.length - 1) {
    add(arc.order[i + 1]);
    if (th && th.i < th.thread.order.length - 1) add(th.thread.order[th.i + 1]);
  } else {
    const list = provArcs[arc.province];
    const nextArc = list[(list.indexOf(arc) + 1) % list.length];
    add(nextArc.order[0]);
    if (th && th.i < th.thread.order.length - 1) add(th.thread.order[th.i + 1]);
  }
  for (const x of sit[id].x || []) add(x);
  if (out.length < 2) {
    // neighbour link: the nearest marquee place that belongs to another arc and is not yet linked
    const me = places[id];
    let best = null, bd = Infinity;
    for (const o of Object.keys(entries)) {
      if (o === id || out.includes(o) || arcOf[o].arc === arc) continue;
      const q = places[o];
      let d = Math.hypot(q.x - me.x, q.z - me.z);
      if (entries[o].province !== entries[id].province) d += 4000;
      if (threadOf[o] && th && threadOf[o].thread === th.thread) d += 2000;
      if (d < bd) { bd = d; best = o; }
    }
    add(best);
  }
  if (out.length < 2) bad(`${id}: needs two follow-up links (has ${out.length})`);
  return out.slice(0, 2);
}

// ---- notes and the lore file
const at = (id, last) => {
  const t = places[id] && places[id].type;
  if (t === 'landmark' || t === 'standing_stone') return 'site';
  return last ? 'boss' : 'chest';
};
const pagesOf = x => x.split('||').map(s => s.trim());
const lore = {};
const noteIdx = {};
for (const id of Object.keys(sit)) {
  const s = sit[id];
  if (loreOwner[id]) { s.has_foreign_lore = loreOwner[id]; continue; }
  const e = { hook: s.g, rumour: s.r, notes: [] };
  if (!s.r) bad(`${id}: has no lore elsewhere, so it needs a rumour (r)`);
  const f = follow(id);
  const { arc, i } = arcOf[id];
  const last = i === arc.order.length - 1;
  if (s.n) {
    e.notes.push({ id: 'web', kind: s.n.k, title: s.n.t, author: s.n.a || undefined, at: at(id, last), reveals: f[0], pages: pagesOf(s.n.x) });
  } else bad(`${id}: needs a note (n)`);
  const th = threadOf[id];
  if (th && th.thread.facets[id].note) {
    const n = th.thread.facets[id].note;
    const tl = th.i === th.thread.order.length - 1;
    const nextT = th.thread.order[(th.i + 1) % th.thread.order.length];
    e.notes.push({ id: 'thread', kind: n.k, title: n.t, author: n.a || undefined, at: at(id, tl), reveals: nextT, pages: pagesOf(n.x) });
  }
  lore[id] = e;
  noteIdx[id] = e.notes.map(n => n.id);
}
const loreOut = { places: lore };
fs.writeFileSync(path.join(loreDir, 'loreweb.json'), JSON.stringify(loreOut, null, 1) + '\n');

// ---- quests
const levelOf = { S: 20, A: 12, B: 8 };
function questFiles() {
  const files = {};
  const mk = (file, line) => files[file] = files[file] || { line, category: 'side', bosses: {}, quests: [] };
  const goStep = (id, text, last, bossId) => {
    const o = { type: 'go', place: id, text };
    if (last) {
      o.type = 'kill';
      o.text = text;
      o.spawn = [{ boss_id: bossId }];
      const t = places[id].type;
      if (t === 'dungeon_fixed' || t === 'fort') o.placement = 'structure';
    }
    return o;
  };
  const build = (f, q, order, leads, tier, finaleId, find, region) => {
    const n = order.length;
    const aCount = Math.floor((n - 1) / 2);
    const bossId = q.boss_id;
    const A = {
      id: q.id + '_a', title: q.title, region,
      start: 'find',
      find: { place: order[0], offset: find.offset, radius: 4, clue: find.clue, text: find.text },
      requires: ['level:' + levelOf[tier]],
      desc: q.desc, pitch: q.pitch,
      objectives: [],
      rewards: { gold: Math.round(q.reward.gold * 0.4 / 10) * 10, loot: 'minor' },
      outro: 'One thread of the trail ends here and the next begins. ' + q.desc2
    };
    for (let i = 1; i <= aCount; i++) A.objectives.push(goStep(order[i], leads[i], false));
    const B = {
      id: q.id + '_b', title: q.title + ' (II)', region,
      start: 'auto', requires: ['quest:' + A.id],
      desc: q.desc2, pitch: q.pitch,
      objectives: [],
      rewards: { gold: q.reward.gold, loot: q.reward.loot, flags: [q.id + '_done'] },
      outro: q.outro
    };
    if (q.reward.unique) B.rewards.unique = q.reward.unique;
    for (let i = aCount + 1; i < n; i++) B.objectives.push(goStep(order[i], leads[i], i === n - 1, bossId));
    f.bosses[bossId] = { name: q.boss.name, type: q.boss.type, hp: q.boss.hp, dmg: q.boss.dmg, level: q.boss.level, drop: 'loot:' + (q.reward.loot === 'legendary' ? 'major' : 'standard'), glow: false };
    f.quests.push(A, B);
  };
  for (const a of arcs) {
    const f = mk(`loreweb_${a.province}.json`, `loreweb_${a.province}`);
    const leads = a.order.map(id => sit[id].lead);
    const region = places[a.order[0]].region;
    if (!regionIds.has(region)) bad(`arc ${a.id}: unknown region ${region}`);
    build(f, Object.assign({ id: 'lw_' + a.id, boss_id: `lw_${a.id}_boss` }, a.quest), a.order, leads, a.tier, a.order[a.order.length - 1], a.entry, region);
  }
  const ft = mk('loreweb_threads.json', 'loreweb_threads');
  for (const t of threads) {
    const leads = t.order.map(id => t.facets[id].lead);
    const region = places[t.order[0]].region;
    if (!regionIds.has(region)) bad(`thread ${t.id}: unknown region ${region}`);
    build(ft, Object.assign({ id: 'lw_' + t.id, boss_id: `lw_${t.id}_boss` }, t.quest), t.order, leads, t.tier, t.order[t.order.length - 1], t.entry, region);
  }
  return files;
}
const qfiles = questFiles();
const qdir = path.join(res, 'quests');
for (const [name, j] of Object.entries(qfiles)) fs.writeFileSync(path.join(qdir, name), JSON.stringify(j, null, 1) + '\n');

// ---- indexes
function addIndex(file, names) {
  const p = path.join(file);
  const list = readJson(p);
  for (const n of names) if (!list.includes(n)) list.push(n);
  fs.writeFileSync(p, JSON.stringify(list, null, 1) + '\n');
}
addIndex(path.join(loreDir, 'index.json'), ['loreweb.json']);
addIndex(path.join(qdir, 'index.json'), Object.keys(qfiles));

// ---- the machine-readable web
const outSit = {};
for (const id of Object.keys(sit)) {
  const s = sit[id];
  const a = arcOf[id];
  const th = threadOf[id];
  outSit[id] = {
    id, name: s.name, province: entries[id].province, kind: entries[id].kind, tier: entries[id].tier,
    place_type: places[id].type, region: places[id].region,
    arc: a.arc.id, arc_step: a.i + 1,
    thread: th ? th.thread.id : undefined, thread_step: th ? th.i + 1 : undefined,
    site: s.g, mystery: s.m, clues: s.c, follow: follow(id), payoff: s.p,
    rumour: s.r || undefined,
    thread_clue: th ? th.thread.facets[id].clue : undefined,
    lore_note: noteIdx[id] ? noteIdx[id].map(n => `place.${id}.${n}`) : [],
    lore_elsewhere: s.has_foreign_lore || undefined,
    quests: [`lw_${a.arc.id}_${a.i === 0 ? 'a' : a.i <= Math.floor((a.arc.order.length - 1) / 2) ? 'a' : 'b'}`]
  };
  if (th) outSit[id].quests.push(`lw_${th.thread.id}_${th.i === 0 ? 'a' : th.i <= Math.floor((th.thread.order.length - 1) / 2) ? 'a' : 'b'}`);
}
const out = {
  _doc: 'The lore web: one situation per marquee place, regional arcs and continent threads. Generated by tools/marquee/loreweb_build.js from tools/marquee/loreweb_src/*.json; schema in docs/LORE-WEB.md. Facts and themes only, all text original.',
  version: 1,
  arcs: arcs.map(a => ({
    id: a.id, title: a.title, province: a.province, tier: a.tier, secret: a.secret,
    places: a.order, entry: { place: a.order[0], clue: a.entry.clue, offset: a.entry.offset, text: a.entry.text },
    finale: { place: a.order[a.order.length - 1], boss: a.quest.boss.name, reward: a.quest.reward },
    quests: [`lw_${a.id}_a`, `lw_${a.id}_b`]
  })),
  threads: threads.map(t => ({
    id: t.id, title: t.title, tier: t.tier, secret: t.secret,
    places: t.order, entry: { place: t.order[0], clue: t.entry.clue, offset: t.entry.offset, text: t.entry.text },
    finale: { place: t.order[t.order.length - 1], boss: t.quest.boss.name, reward: t.quest.reward },
    provinces: [...new Set(t.order.map(id => entries[id].province))],
    quests: [`lw_${t.id}_a`, `lw_${t.id}_b`]
  })),
  situations: outSit
};
fs.writeFileSync(path.join(__dirname, 'loreweb.json'), JSON.stringify(out, null, 1) + '\n');

const withNote = Object.keys(lore).length;
const noteCount = Object.values(lore).reduce((n, e) => n + e.notes.length, 0);
const questCount = Object.values(qfiles).reduce((n, f) => n + f.quests.length, 0);
console.log(`situations ${Object.keys(sit).length}, arcs ${arcs.length}, threads ${threads.length}, places with new lore ${withNote}, notes ${noteCount}, quests ${questCount}`);
if (problems.length) {
  console.error(problems.length + ' problems:\n  ' + problems.join('\n  '));
  process.exitCode = 1;
}
