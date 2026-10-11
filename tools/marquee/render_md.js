#!/usr/bin/env node
// Renders docs/MARQUEE.md from tools/marquee/marquee.json.   usage: node tools/marquee/render_md.js
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..', '..');
const m = JSON.parse(fs.readFileSync(path.join(__dirname, 'marquee.json'), 'utf8'));
const names = {cyrodiil: 'Cyrodiil', morrowind: 'Morrowind (Vvardenfell and mainland)', solstheim: 'Solstheim', high_rock: 'High Rock',
  hammerfell: 'Hammerfell', elsweyr: 'Elsweyr', valenwood: 'Valenwood', summerset: 'Summerset Isles', black_marsh: 'Black Marsh',
  foreign: 'Foreign lands (Atmora, Akavir, Yokuda, Thras, Pyandonea)'};
const esc = s => String(s).replace(/\|/g, '/').replace(/\n/g, ' ');
const title = id => id.split('_').map(w => w[0].toUpperCase() + w.slice(1)).join(' ');
const L = [];
L.push('# Marquee places', '');
L.push('The places that must make a player say "wow, that place is cool", chosen per province, with an identity brief, named');
L.push('buildings, named NPCs, a signature quest chain and what it takes to build. Generated from `tools/marquee/marquee.json`');
L.push('(`node tools/marquee/render_md.js`); edit the JSON, not this file. Gap analysis: `docs/MARQUEE-GAP.md`. Work plan:');
L.push('`docs/MARQUEE-PLAN.md`.', '');
L.push('Legal: names, positions and layout facts only (from the owner\'s own installs and lore); every description, name of an');
L.push('invented NPC and quest text is original.', '');
L.push('**Reading the tables.** Tier: S build first, A next, B when the infrastructure is cheap. Effort: S = data and existing');
L.push('kits only; M = a new layout plan plus a small kit or builder change; L = new landmark builders, a new kit or new');
L.push('structure code. `now` = what the place gets today. Silhouette tags are unique across all settlements; no two share');
L.push('silhouette + landmark + palette.', '');
// summary
L.push('## Summary', '');
L.push('| Province | Settlements | Dungeons | Landmarks | Minor | S tier | L effort |', '| --- | --- | --- | --- | --- | --- | --- |');
let t = [0, 0, 0, 0, 0, 0];
for (const P of m.provinces) {
  const all = [...P.settlements, ...P.dungeons, ...P.landmarks, ...P.minors];
  const s = all.filter(e => e.tier === 'S').length, l = all.filter(e => e.effort === 'L').length;
  L.push(`| ${names[P.province]} | ${P.settlements.length} | ${P.dungeons.length} | ${P.landmarks.length} | ${P.minors.length} | ${s} | ${l} |`);
  t[0] += P.settlements.length; t[1] += P.dungeons.length; t[2] += P.landmarks.length; t[3] += P.minors.length; t[4] += s; t[5] += l;
}
L.push(`| **Total** | **${t[0]}** | **${t[1]}** | **${t[2]}** | **${t[3]}** | **${t[4]}** | **${t[5]}** |`, '');
L.push(`All ${t[0] + t[1] + t[2] + t[3]} ids exist in \`places/*.json\` today (verified by \`tools/marquee/validate.js\`); no new place ids are required.`, '');
for (const P of m.provinces) {
  L.push(`## ${names[P.province]}`, '', P.pitch, '');
  L.push('### Marquee settlements', '');
  L.push('| id | tier | effort | now | silhouette | landmark | palette |', '| --- | --- | --- | --- | --- | --- | --- |');
  for (const e of P.settlements) L.push(`| \`${e.id}\` | ${e.tier} | ${e.effort} | ${esc(e.now)} | ${esc(e.sil)} | ${esc(e.lm)} | ${esc(e.pal)} |`);
  L.push('');
  for (const e of P.settlements) {
    L.push(`**${title(e.id)}** (\`${e.id}\`, ${e.tier}/${e.effort}). ${e.brief}`, '');
    L.push(`* Buildings: ${e.bld.join('; ')}.`);
    L.push(`* NPCs: ${e.npc.map(n => `${n[0]} (${n[1]})`).join('; ')}.`);
    L.push(`* Quest "${e.q.t}": ${e.q.s.map((s, i) => `${i + 1}. ${s}`).join(' ')} Unlike the rest: ${e.q.u}.`);
    L.push(`* Needs: ${e.need}`, '');
  }
  L.push('### Marquee dungeons', '');
  L.push('| id | tier | effort | theme | identity | boss | NPCs | signature quest | needs |', '| --- | --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const e of P.dungeons) {
    L.push(`| \`${e.id}\` | ${e.tier} | ${e.effort} | ${esc(e.theme)} | ${esc(e.brief)} | ${esc(e.boss)} | ${esc(e.npc.map(n => n[0] + ': ' + n[1]).join('; '))} | **${esc(e.q.t)}**: ${esc(e.q.s.join(' > '))}. Unlike: ${esc(e.q.u)} | ${esc(e.need)} |`);
  }
  L.push('', '### Marquee landmarks and moments', '');
  L.push('| id | tier | effort | what it is | the moment | needs |', '| --- | --- | --- | --- | --- | --- |');
  for (const e of P.landmarks) L.push(`| \`${e.id}\` | ${e.tier} | ${e.effort} | ${esc(e.brief)} | ${esc(e.moment)} | ${esc(e.need)} |`);
  L.push('', '### Distinctive minor places', '');
  L.push('| id | tier | effort | what it is | hook |', '| --- | --- | --- | --- | --- |');
  for (const e of P.minors) L.push(`| \`${e.id}\` | ${e.tier} | ${e.effort} | ${esc(e.brief)} | ${esc(e.hook)} |`);
  L.push('');
}
fs.writeFileSync(path.join(root, 'docs', 'MARQUEE.md'), L.join('\n'));
console.log('wrote docs/MARQUEE.md', L.length, 'lines');
