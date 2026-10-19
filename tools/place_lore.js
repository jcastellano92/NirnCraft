#!/usr/bin/env node
// Validates the place lore (mod/src/main/resources/nirncraft/lore/places/*.json, listed in its index.json) and the
// place mini-quests (mod/src/main/resources/nirncraft/quests/place_*.json), exports every text into
// mod/src/main/lang/nirncraft/place_lore.json with the same keys com.nirncraft.lore.place.PlaceLore derives, and prints
// the coverage. Run after editing the lore:  node tools/place_lore.js [--file <lore file>] [--quiet]
// (then node tools/quest_lang.js for the quest texts). Exit code 1 on any problem. See docs/LORE.md.
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', 'mod', 'src', 'main');
const res = path.join(root, 'resources', 'nirncraft');
const loreDir = path.join(res, 'lore', 'places');
const questDir = path.join(res, 'quests');
const out = path.join(root, 'lang', 'nirncraft', 'place_lore.json');

const args = process.argv.slice(2);
const only = args.includes('--file') ? args[args.indexOf('--file') + 1] : null;
const quiet = args.includes('--quiet');

const problems = [];
const warnings = [];
const lang = {};
function put(key, text) {
  if (text === undefined || text === null || text === '') return;
  if (lang[key] !== undefined && lang[key] !== text) problems.push(`duplicate lang key ${key}`);
  lang[key] = text;
}
const readJson = f => JSON.parse(fs.readFileSync(f, 'utf8'));

// the module's fixed lines (com.nirncraft.lore.place)
const STATIC = {
  'place_lore.nirncraft.revealed': 'Marked on your map: %s',
  'place_lore.nirncraft.too_busy': 'You have too much on your plate to take up "%s" now.',
  'place_lore.nirncraft.option.trouble': 'Heard of any trouble hereabouts?',
  'place_lore.nirncraft.option.tales': 'Know any local tales?',
  'place_lore.nirncraft.say.busy': '%s But you look like you have enough troubles of your own already.',
  'place_lore.nirncraft.say.quiet.0': 'Quiet, for once. I\'ll not complain about that.',
  'place_lore.nirncraft.say.quiet.1': 'Nothing I\'d trouble a traveler with. Ask again in a few days.',
  'place_lore.nirncraft.say.quiet.2': 'All I hear about is the price of bread and the weather.',
  'place_lore.nirncraft.say.song': 'There\'s an old one they sing around here, "%s": %s',
  'place_lore.nirncraft.say.news': '%1$s You\'ll find %2$s %3$s of here.',
};
for (const [k, v] of Object.entries(STATIC)) lang[k] = v;

// ------------------------------------------------------------------ the world: places, regions, uniques
const places = {};
const pdir = path.join(res, 'tamriel', 'places');
for (const f of readJson(path.join(pdir, 'index.json'))) {
  try {
    for (const p of readJson(path.join(pdir, f))) places[p.id] = p;
  } catch (e) { problems.push(`places/${f}: ${e.message}`); }
}
const regions = new Set();
const provinces = new Set();
try {
  const r = readJson(path.join(res, 'tamriel', 'regions.json'));
  for (const x of (Array.isArray(r) ? r : r.regions || Object.values(r))) {
    if (!x || !x.id) continue;
    regions.add(x.id);
    provinces.add(x.id.split('/')[0]);
  }
} catch (e) { problems.push(`regions.json: ${e.message}`); }
const uniquesSrc = fs.readFileSync(path.join(root, 'java', 'com', 'nirncraft', 'loot', 'LegendaryUniques.java'), 'utf8');
const uniques = new Set([...uniquesSrc.matchAll(/def\("([a-z_]+)"/g)].map(m => m[1]));

// ------------------------------------------------------------------ quests: ids, relics, NPCs; what the others use
const quests = {}, relics = {}, npcs = {};
const knownItems = new Set(), knownSpawns = new Set();
const placeQuestFiles = [];
for (const f of readJson(path.join(questDir, 'index.json'))) {
  let j;
  try { j = readJson(path.join(questDir, f)); } catch (e) { problems.push(`quests/${f}: ${e.message}`); continue; }
  const mine = f.startsWith('place_');
  if (mine) placeQuestFiles.push([f, j]);
  for (const r of j.relics || []) relics[r.id] = f;
  for (const n of j.npcs || []) npcs[n.id] = f;
  for (const q of j.quests || []) {
    quests[q.id] = { q, f, mine };
    if (mine) continue;
    for (const it of (q.rewards || {}).items || []) knownItems.add((typeof it === 'string' ? it : it.item).split('*')[0]);
    for (const o of q.objectives || []) for (const s of o.spawn || []) knownSpawns.add(s.type);
  }
}
// creatures the mod registers (com.nirncraft.creatures), besides those the other questlines already spawn
for (const id of ['alit', 'ash_spawn', 'chaurus', 'chaurus_hunter', 'cliff_racer', 'death_hound', 'dragon_priest', 'dremora',
  'durzog', 'dwarven_ballista', 'flame_atronach', 'frost_atronach', 'gargoyle', 'giant_scorpion', 'harpy', 'imp', 'kagouti',
  'land_dreugh', 'minotaur', 'netch', 'nix_hound', 'ogre', 'riekling', 'sand_serpent', 'scamp', 'senche_tiger', 'storm_atronach',
  'wamasu', 'will_o_wisp', 'wisp', 'wolf', 'bandit', 'bandit_chief', 'bear', 'draugr', 'draugr_deathlord', 'dwarven_centurion',
  'dwarven_sphere', 'dwarven_spider', 'falmer', 'forsworn', 'frostbite_spider', 'giant', 'hagraven', 'ice_wraith', 'mudcrab',
  'necromancer', 'sabre_cat', 'skeever', 'slaughterfish', 'spriggan', 'troll', 'vampire', 'vampire_lord', 'werewolf',
  'wispmother', 'dragon', 'horker']) knownSpawns.add('nirncraft:' + id);
for (const id of ['skeleton', 'zombie', 'husk', 'vex', 'wither_skeleton', 'witch', 'spider', 'drowned', 'stray']) knownSpawns.add('minecraft:' + id);
const npcRolesSrc = fs.readFileSync(path.join(root, 'java', 'com', 'nirncraft', 'society', 'NpcRole.java'), 'utf8');
const npcRoles = new Set([...npcRolesSrc.matchAll(/^\s{4}([A-Z_]+)\(/gm)].map(m => m[1].toLowerCase()));

// ------------------------------------------------------------------ the lore files
const KINDS = new Set(['journal', 'letter', 'orders', 'notes', 'report', 'ledger', 'treasure', 'poem', 'inscription', 'book', 'diary', 'list', 'contract', 'prayer', 'warning']);
const AT = new Set(['chest', 'boss', 'site']);
const ID = /^[a-z0-9_]+$/;
const lore = {};           // place id -> {file, entry}
const regionLore = {};     // region key -> first file
const regionCount = {};    // region key -> {rumour, legend, song} written so far (files merge)
const startedBy = {};      // quest id -> how it starts (place/note)
let noteCount = 0, pageCount = 0, words = 0;
const files = [...new Set(readJson(path.join(loreDir, 'index.json')))]; // a union: a file listed twice loads once
const regionKey = r => r.replace(/\//g, '.');
for (const f of files) {
  let j;
  try { j = readJson(path.join(loreDir, f)); } catch (e) { problems.push(`lore/${f}: ${e.message}`); continue; }
  const P = (msg) => problems.push(`${f}: ${msg}`);
  for (const [rid, t] of Object.entries(j.regions || {})) {
    if (!regions.has(rid) && !provinces.has(rid)) P(`unknown region or province ${rid}`);
    // several lore files may write the same region: their rumours, legends and songs are appended (indices continue)
    const base = regionLore[rid] ? regionCount[rid] : { rumour: 0, legend: 0, song: 0 };
    regionCount[rid] = base;
    const first = !regionLore[rid];
    if (first) regionLore[rid] = f;
    const k = `place_lore.nirncraft.region.${regionKey(rid)}`;
    if (first && (!Array.isArray(t.rumours) || t.rumours.length < 3)) P(`region ${rid}: needs at least 3 rumours`);
    if (first && (!Array.isArray(t.legends) || t.legends.length < 1)) P(`region ${rid}: needs at least 1 legend`);
    (t.rumours || []).forEach((s, i) => { if (typeof s !== 'string' || s.length < 20) P(`region ${rid}: rumour ${i} is too short`); put(`${k}.rumour.${base.rumour + i}`, s); });
    (t.legends || []).forEach((s, i) => { if (typeof s !== 'string' || s.length < 40) P(`region ${rid}: legend ${i} is too short`); put(`${k}.legend.${base.legend + i}`, s); });
    (t.songs || []).forEach((s, i) => {
      if (!s.title || !Array.isArray(s.lines) || s.lines.length < 2) P(`region ${rid}: song ${i} needs a title and lines`);
      put(`${k}.song.${base.song + i}.title`, s.title);
      (s.lines || []).forEach((l, li) => put(`${k}.song.${base.song + i}.line.${li}`, l.replace(/^[~^#]\s*/, '')));
    });
    base.rumour += (t.rumours || []).length;
    base.legend += (t.legends || []).length;
    base.song += (t.songs || []).length;
  }
  for (const [pid, e] of Object.entries(j.places || {})) {
    if (!ID.test(pid)) P(`bad place id ${pid}`);
    if (!places[pid] && !e.future) P(`unknown place ${pid} (mark it "future": true if a later pass adds it)`);
    if (lore[pid]) P(`place ${pid} also written in ${lore[pid].file}`);
    lore[pid] = { file: f, e };
    const k = `place_lore.nirncraft.${pid}`;
    if (typeof e.hook !== 'string' || e.hook.length < 30) P(`${pid}: hook missing or too short`);
    else if (e.hook.length > 320) P(`${pid}: hook longer than 320 characters`);
    if (typeof e.rumour !== 'string' || e.rumour.length < 30) P(`${pid}: rumour missing or too short`);
    put(`${k}.hook`, e.hook);
    put(`${k}.rumour`, e.rumour);
    if (e.quest) {
      if (!quests[e.quest]) P(`${pid}: unknown quest ${e.quest}`);
      else (startedBy[e.quest] = startedBy[e.quest] || []).push(pid);
    }
    const notes = e.notes || [];
    if (notes.length < 1 || notes.length > 3) P(`${pid}: needs 1-3 notes (has ${notes.length})`);
    const seen = new Set();
    for (const n of notes) {
      const nid = n.id || '';
      if (!ID.test(nid)) P(`${pid}: bad note id "${nid}"`);
      if (seen.has(nid)) P(`${pid}: duplicate note ${nid}`);
      seen.add(nid);
      if (!KINDS.has(n.kind)) P(`${pid}.${nid}: unknown kind ${n.kind}`);
      if (!n.title) P(`${pid}.${nid}: no title`);
      if (!Array.isArray(n.pages) || n.pages.length === 0) P(`${pid}.${nid}: no pages`);
      const text = (n.pages || []).join(' ');
      if (text.length < 150) P(`${pid}.${nid}: text shorter than 150 characters`);
      if (n.at !== undefined && !AT.has(n.at)) P(`${pid}.${nid}: "at" must be chest, boss or site`);
      if (n.reveals && !places[n.reveals]) P(`${pid}.${nid}: reveals unknown place ${n.reveals}`);
      if (n.starts) {
        if (!quests[n.starts]) P(`${pid}.${nid}: starts unknown quest ${n.starts}`);
        else (startedBy[n.starts] = startedBy[n.starts] || []).push(`${pid}.${nid}`);
      }
      noteCount++;
      pageCount += (n.pages || []).length;
      words += text.split(/\s+/).length;
      const nk = `place_lore.nirncraft.${pid}.${nid}`;
      put(`book.nirncraft.place.${pid}.${nid}`, n.title);
      put(`${nk}.author`, n.author);
      (n.pages || []).forEach((p, i) => put(`${nk}.p${i}`, p));
    }
  }
}

// ------------------------------------------------------------------ the place mini-quests
const OBJ = new Set(['go', 'retrieve', 'kill', 'kill_type', 'clear', 'deliver', 'talk', 'discover', 'wait', 'escort']);
let placeQuests = 0;
for (const [f, j] of placeQuestFiles) {
  const P = (msg) => problems.push(`quests/${f}: ${msg}`);
  for (const n of j.npcs || []) {
    if (!n.id.startsWith('pln_')) P(`npc ${n.id} should start with pln_`);
    if (!npcRoles.has(n.role || 'adventurer')) P(`npc ${n.id}: unknown role ${n.role}`);
    if (!places[n.place]) P(`npc ${n.id}: unknown place ${n.place}`);
  }
  for (const r of j.relics || []) if (!r.id.startsWith('plr_')) P(`relic ${r.id} should start with plr_`);
  for (const q of j.quests || []) {
    placeQuests++;
    const Q = (msg) => P(`${q.id}: ${msg}`);
    if (!q.id.startsWith('pl_')) Q('quest ids start with pl_');
    if (!q.region || !regions.has(q.region)) Q(`region missing or unknown (${q.region})`);
    if (!q.title || !q.desc) Q('needs a title and desc');
    const start = q.start || 'manual';
    if (start === 'manual' && !startedBy[q.id]) Q('nothing starts it: name it in a place\'s "quest" or a note\'s "starts"');
    if (start.startsWith('npc:') && !npcs[start.slice(4)]) Q(`offered by unknown npc ${start}`);
    const objs = q.objectives || [];
    if (objs.length < 1 || objs.length > 4) Q(`needs 1-4 objectives (has ${objs.length})`);
    objs.forEach((o, i) => {
      if (!OBJ.has(o.type)) Q(`objective ${i}: type ${o.type} not used by place quests`);
      if (!o.text) Q(`objective ${i}: no text`);
      if (o.place && !places[o.place]) Q(`objective ${i}: unknown place ${o.place}`);
      if (['go', 'retrieve', 'kill', 'clear', 'discover'].includes(o.type) && !o.place && !o.at_player) Q(`objective ${i}: ${o.type} needs a place`);
      if (o.relic && !relics[o.relic]) Q(`objective ${i}: unknown relic ${o.relic}`);
      if (o.item) {
        const it = o.item.split('*')[0];
        if (it.startsWith('unique:') ? !uniques.has(it.slice(7)) : !knownItems.has(it)) Q(`objective ${i}: item ${o.item} isn't a known item`);
      }
      if (o.type === 'retrieve' && !o.relic && !o.item) Q(`objective ${i}: retrieve needs a relic or item`);
      if (o.type === 'deliver' && o.item && (o.item.includes('*') || o.item.startsWith('unique:'))) Q(`objective ${i}: deliver takes a plain item id (use "count")`);
      if ((o.type === 'deliver' || o.type === 'talk') && o.npc) for (const n of o.npc.split('|')) if (!npcs[n]) Q(`objective ${i}: unknown npc ${n}`);
      if (o.type === 'kill' && !(o.spawn || []).some(s => s.target)) Q(`objective ${i}: kill needs a spawn with "target": true`);
      for (const s of o.spawn || []) {
        if (s.type.startsWith('npc:')) { if (!npcRoles.has(s.type.slice(4))) Q(`objective ${i}: unknown npc role ${s.type}`); }
        else if (!knownSpawns.has(s.type)) Q(`objective ${i}: spawn ${s.type} isn't a known creature`);
      }
    });
    for (const it of (q.rewards || {}).items || []) {
      const id = (typeof it === 'string' ? it : it.item).split('*')[0];
      if (id.startsWith('unique:') ? !uniques.has(id.slice(7)) : !knownItems.has(id)) Q(`reward item ${id} isn't a known item`);
    }
    const g = (q.rewards || {}).gold || 0;
    if (g > 1500) Q(`reward gold ${g} is too generous for a mini-quest`);
  }
}

// ------------------------------------------------------------------ write and report
const sorted = {};
for (const k of Object.keys(lang).sort()) sorted[k] = lang[k];
fs.writeFileSync(out, JSON.stringify(sorted, null, 2) + '\n');

const all = Object.values(places);
const covered = all.filter(p => lore[p.id]);
const byType = {};
for (const p of all) {
  const t = byType[p.type] = byType[p.type] || { places: 0, lore: 0, quests: 0 };
  t.places++;
  if (lore[p.id]) t.lore++;
  if (lore[p.id] && (lore[p.id].e.quest || (lore[p.id].e.notes || []).some(n => n.starts))) t.quests++;
}
const questPlaces = new Set(Object.values(startedBy).flat().map(s => s.split('.')[0]));
if (!quiet) {
  console.log(`Place lore: ${covered.length}/${all.length} places, ${noteCount} notes (${pageCount} pages, ~${words} words), `
    + `${placeQuests} mini-quests at ${questPlaces.size} places, ${Object.keys(regionLore).length} regions with rumours/legends/songs, `
    + `${Object.keys(lang).length} lang keys -> ${path.relative(process.cwd(), out)}`);
  for (const [t, c] of Object.entries(byType)) console.log(`  ${t.padEnd(15)} ${c.lore}/${c.places} with lore, ${c.quests} with a mini-quest`);
  const missing = all.filter(p => !lore[p.id]).map(p => p.id);
  if (missing.length) console.log(`  without lore (${missing.length}): ${missing.slice(0, 40).join(', ')}${missing.length > 40 ? ' ...' : ''}`);
}
const shown = only ? problems.filter(p => p.includes(only)) : problems;
for (const w of warnings) console.log('warning: ' + w);
if (shown.length) {
  console.error(`${shown.length} problems:`);
  for (const p of shown) console.error('  ' + p);
  process.exitCode = 1;
} else if (!quiet) {
  console.log(only ? `${only}: no problems` : 'No problems.');
}
