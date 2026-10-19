#!/usr/bin/env node
// Verifies the lore web (tools/marquee/loreweb.json and the data built from it):
//  * every situation names an existing place, has 3 clues and 2 follow-up links, and every link target exists;
//  * no orphan: every marquee place has an arc, an inbound and an outbound link, and the web is one connected graph;
//  * every arc has a start discoverable from the open world (a hidden 'find' quest at its first place, plus a rumour at
//    that place or a map hint: a note of another place that reveals it) and an end (a boss objective, outro and payoff);
//  * quest ids are lw_ prefixed and refer to existing places; every note's 'reveals' target exists.
// Usage: node tools/marquee/loreweb_check.js      Exit code 1 on any problem. See docs/LORE-WEB.md.
'use strict';
const fs = require('fs');
const path = require('path');

const repo = path.join(__dirname, '..', '..');
const res = path.join(repo, 'mod', 'src', 'main', 'resources', 'nirncraft');
const readJson = f => JSON.parse(fs.readFileSync(f, 'utf8'));
const problems = [];
const bad = m => problems.push(m);

const web = readJson(path.join(__dirname, 'loreweb.json'));
const marquee = readJson(path.join(__dirname, 'marquee.json'));
const marqueeIds = new Set();
for (const P of marquee.provinces) for (const k of ['settlements', 'dungeons', 'landmarks', 'minors']) for (const e of P[k]) marqueeIds.add(e.id);

const places = {};
const pdir = path.join(res, 'tamriel', 'places');
for (const f of readJson(path.join(pdir, 'index.json'))) for (const p of readJson(path.join(pdir, f))) places[p.id] = p;

// the data the web was built into
const lore = readJson(path.join(res, 'lore', 'places', 'loreweb.json')).places;
const loreIndex = readJson(path.join(res, 'lore', 'places', 'index.json'));
if (!loreIndex.includes('loreweb.json')) bad('lore/places/index.json does not list loreweb.json');
const questIndex = readJson(path.join(res, 'quests', 'index.json'));
const quests = {};
for (const f of questIndex.filter(f => f.startsWith('loreweb_'))) {
  let j;
  try { j = readJson(path.join(res, 'quests', f)); } catch (e) { bad(`quests/${f}: ${e.message}`); continue; }
  for (const q of j.quests || []) {
    if (!q.id.startsWith('lw_')) bad(`${f}: quest ${q.id} must start with lw_`);
    if (quests[q.id]) bad(`${f}: duplicate quest ${q.id}`);
    quests[q.id] = Object.assign({ file: f, bosses: j.bosses || {} }, q);
  }
}
for (const f of fs.readdirSync(path.join(res, 'quests'))) {
  if (f.startsWith('loreweb_') && !questIndex.includes(f)) bad(`quests/index.json does not list ${f}`);
}

// ---- situations and links
const sit = web.situations;
const ids = Object.keys(sit);
if (ids.length !== marqueeIds.size) bad(`${ids.length} situations for ${marqueeIds.size} marquee places`);
for (const id of marqueeIds) if (!sit[id]) bad(`marquee place ${id} has no situation`);
const inbound = {}, outbound = {};
for (const id of ids) {
  const s = sit[id];
  if (!places[id]) bad(`${id}: not a world place`);
  if (!marqueeIds.has(id)) bad(`${id}: not a marquee place`);
  for (const f of ['site', 'mystery', 'payoff']) if (!s[f] || s[f].length < 20) bad(`${id}: ${f} missing or too short`);
  if (!Array.isArray(s.clues) || s.clues.length !== 3) bad(`${id}: needs exactly 3 clues`);
  if (!Array.isArray(s.follow) || s.follow.length !== 2) bad(`${id}: needs exactly 2 follow-ups`);
  for (const t of s.follow || []) {
    if (!sit[t]) bad(`${id}: follow-up ${t} does not exist in the web`);
    if (t === id) bad(`${id}: links to itself`);
    (inbound[t] = inbound[t] || new Set()).add(id);
    (outbound[id] = outbound[id] || new Set()).add(t);
  }
  if (!s.arc) bad(`${id}: in no arc`);
}
for (const id of ids) {
  if (!inbound[id]) bad(`${id}: orphan, nothing links to it`);
  if (!outbound[id]) bad(`${id}: dead end, it links to nothing`);
}
// one connected component (links read as undirected)
{
  const adj = {};
  for (const id of ids) adj[id] = new Set();
  for (const id of ids) for (const t of sit[id].follow || []) if (adj[t]) { adj[id].add(t); adj[t].add(id); }
  const seen = new Set();
  const comps = [];
  for (const id of ids) {
    if (seen.has(id)) continue;
    const stack = [id], comp = [];
    seen.add(id);
    while (stack.length) {
      const x = stack.pop();
      comp.push(x);
      for (const y of adj[x]) if (!seen.has(y)) { seen.add(y); stack.push(y); }
    }
    comps.push(comp);
  }
  if (comps.length > 1) bad(`the web is not connected: ${comps.length} components (sizes ${comps.map(c => c.length).join(', ')})`);
}

// ---- notes: reveals targets, and who reveals what (map hints)
const revealedBy = {};
for (const [pid, e] of Object.entries(lore)) {
  if (!places[pid]) bad(`lore ${pid}: unknown place`);
  for (const n of e.notes || []) {
    if (n.reveals) {
      if (!places[n.reveals]) bad(`note ${pid}.${n.id}: reveals unknown place ${n.reveals}`);
      (revealedBy[n.reveals] = revealedBy[n.reveals] || []).push(`${pid}.${n.id}`);
    }
  }
}

// ---- arcs and threads: starts and ends
function checkChain(kind, c) {
  const first = c.places[0], last = c.places[c.places.length - 1];
  const where = `${kind} ${c.id}`;
  if (kind === 'arc' && (c.places.length < 4 || c.places.length > 8)) bad(`${where}: ${c.places.length} places, expected 4-8`);
  if (kind === 'thread' && c.provinces.length < 3) bad(`${where}: touches only ${c.provinces.length} provinces`);
  for (const id of c.places) if (!sit[id]) bad(`${where}: unknown place ${id}`);
  const [qa, qb] = c.quests.map(id => quests[id]);
  if (!qa || !qb) { bad(`${where}: quests ${c.quests.join(', ')} not found`); return; }
  // start: a hidden find quest in the open world
  if (qa.start !== 'find' || !qa.find) bad(`${where}: first quest ${qa.id} is not a hidden find quest`);
  else {
    if (qa.find.place !== first) bad(`${where}: find clue at ${qa.find.place}, expected ${first}`);
    if (!places[qa.find.place]) bad(`${where}: find place ${qa.find.place} unknown`);
    if (!qa.find.text || qa.find.text.length < 40) bad(`${where}: find text too short`);
  }
  // ... plus a rumour at the place or a map hint from another place
  const hasRumour = !!sit[first].rumour || !!sit[first].lore_elsewhere;
  const hinted = (revealedBy[first] || []).some(r => r.split('.')[0] !== first);
  if (!hasRumour && !hinted) bad(`${where}: start ${first} has neither a rumour nor a map hint`);
  if (kind === 'arc' && !hinted && !hasRumour) bad(`${where}: start is not discoverable`);
  // chain: quest b follows quest a
  if (qb.start !== 'auto' || !(qb.requires || []).includes('quest:' + qa.id)) bad(`${where}: ${qb.id} does not follow ${qa.id}`);
  // end: a boss at the last place, a reward and an outro
  const lastObj = qb.objectives[qb.objectives.length - 1];
  if (!lastObj || lastObj.place !== last || lastObj.type !== 'kill') bad(`${where}: last objective is not a boss at ${last}`);
  else if (!(lastObj.spawn || []).some(s => qb.bosses[s.boss_id])) bad(`${where}: boss of ${qb.id} is not defined`);
  if (!qb.outro || !qb.rewards || !(qb.rewards.loot || qb.rewards.unique)) bad(`${where}: ${qb.id} lacks an outro or a reward`);
  // every objective place exists and belongs to the chain
  for (const q of [qa, qb]) for (const o of q.objectives) {
    if (!places[o.place]) bad(`${where}: ${q.id} objective at unknown place ${o.place}`);
    else if (!c.places.includes(o.place)) bad(`${where}: ${q.id} objective at ${o.place}, which is not in the chain`);
  }
  const visited = new Set([first, ...qa.objectives.map(o => o.place), ...qb.objectives.map(o => o.place)]);
  for (const id of c.places) if (!visited.has(id)) bad(`${where}: quests never visit ${id}`);
}
for (const a of web.arcs) checkChain('arc', a);
for (const t of web.threads) checkChain('thread', t);

// every place is in exactly one arc; thread members are marked on the situation
const inArc = {};
for (const a of web.arcs) for (const id of a.places) { if (inArc[id]) bad(`${id} in arcs ${inArc[id]} and ${a.id}`); inArc[id] = a.id; }
for (const id of ids) if (!inArc[id]) bad(`${id}: in no arc`);
for (const t of web.threads) for (const id of t.places) if (sit[id] && sit[id].thread !== t.id) bad(`${id}: thread mark ${sit[id].thread} != ${t.id}`);

// places with new lore have a rumour and a hook
for (const [id, e] of Object.entries(lore)) {
  if (!e.hook || !e.rumour) bad(`lore ${id}: hook and rumour are required`);
}

const n = k => web[k].length;
console.log(`lore web: ${ids.length} situations, ${n('arcs')} arcs, ${n('threads')} threads, ${Object.keys(quests).length} quests, `
  + `${Object.values(lore).reduce((s, e) => s + e.notes.length, 0)} notes at ${Object.keys(lore).length} places, `
  + `${Object.values(outbound).reduce((s, x) => s + x.size, 0)} links`);
if (problems.length) {
  console.error(problems.length + ' problems:');
  for (const p of problems) console.error('  ' + p);
  process.exitCode = 1;
} else console.log('No problems.');
