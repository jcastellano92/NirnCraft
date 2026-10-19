#!/usr/bin/env node
// Validates tools/marquee/marquee.json against the place data.   usage: node tools/marquee/validate.js
// Checks: every id exists in places/*.json (or carries an "add" block), brief <= 60 words, counts per province,
// settlement fields complete, silhouette tags unique, no repeated silhouette+landmark+palette.
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..', '..');
const pdir = path.join(root, 'mod/src/main/resources/nirncraft/tamriel/places');
const m = JSON.parse(fs.readFileSync(path.join(__dirname, 'marquee.json'), 'utf8'));
const byId = {};
for (const f of fs.readdirSync(pdir)) {
  if (!f.endsWith('.json') || f === 'index.json') continue;
  let j = JSON.parse(fs.readFileSync(path.join(pdir, f), 'utf8'));
  if (!Array.isArray(j)) j = j.places || [];
  for (const p of j) byId[p.id] = p;
}
const lim = {settlements: [5, 8], dungeons: [4, 6], landmarks: [3, 5], minors: [4, 8]};
const probs = [];
const sil = {}, trip = {}, seen = {};
for (const P of m.provinces) {
  for (const k of Object.keys(lim)) {
    const n = P[k].length;
    const small = P.province === 'foreign' || P.province === 'solstheim';
    const lo = small ? 4 : lim[k][0];
    if (n < lo || n > lim[k][1]) probs.push(`${P.province}.${k}: ${n} entries`);
    for (const e of P[k]) {
      if (seen[e.id]) probs.push('duplicate id ' + e.id);
      seen[e.id] = 1;
      if (!byId[e.id] && !e.add) probs.push(`${P.province}.${k}: unknown place id ${e.id}`);
      if (e.brief && e.brief.split(/\s+/).length > 60) probs.push(`${e.id}: brief over 60 words`);
      if (!['S', 'A', 'B'].includes(e.tier) || !['S', 'M', 'L'].includes(e.effort)) probs.push(`${e.id}: tier/effort`);
      if (k === 'settlements') {
        if (e.bld.length !== 3 || e.npc.length !== 3) probs.push(`${e.id}: needs 3 buildings and 3 NPCs`);
        if (e.q.s.length < 3 || e.q.s.length > 5) probs.push(`${e.id}: quest stages`);
        if (sil[e.sil]) probs.push(`${e.id}: silhouette ${e.sil} also used by ${sil[e.sil]}`);
        sil[e.sil] = e.id;
        const t = [e.sil, e.lm, e.pal].join('|');
        if (trip[t]) probs.push(`${e.id}: same silhouette+landmark+palette as ${trip[t]}`);
        trip[t] = e.id;
      }
    }
  }
}
if (probs.length) { console.log(probs.join('\n')); process.exit(1); }
console.log('marquee.json OK:', JSON.stringify(m.totals));
