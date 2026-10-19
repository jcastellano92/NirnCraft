#!/usr/bin/env node
// Writes docs/LORE-WEB.md from tools/marquee/loreweb.json (tables) plus fixed prose.
// Run after loreweb_build.js:  node tools/marquee/loreweb_doc.js
'use strict';
const fs = require('fs');
const path = require('path');

const repo = path.join(__dirname, '..', '..');
const web = JSON.parse(fs.readFileSync(path.join(__dirname, 'loreweb.json'), 'utf8'));
const sit = web.situations;
const ids = Object.keys(sit);
const nm = id => '`' + id + '`';
const prov = { cyrodiil: 'Cyrodiil', morrowind: 'Morrowind', solstheim: 'Solstheim', high_rock: 'High Rock', hammerfell: 'Hammerfell', elsweyr: 'Elsweyr', valenwood: 'Valenwood', summerset: 'Summerset', black_marsh: 'Black Marsh', foreign: 'Foreign lands' };

let links = 0, crossProv = 0;
for (const id of ids) for (const t of sit[id].follow) { links++; if (sit[t].province !== sit[id].province) crossProv++; }
const elsewhere = ids.filter(id => sit[id].lore_elsewhere).length;
const withLore = ids.length - elsewhere;

const arcRows = web.arcs.map(a => `| ${a.id} | ${prov[a.province]} | ${a.title} | ${a.tier} | ${a.places.map(nm).join(' > ')} | ${a.finale.boss} |`).join('\n');
const thRows = web.threads.map(t => `| ${t.id} | ${t.title} | ${t.provinces.map(p => prov[p]).join(', ')} | ${t.places.map(nm).join(' > ')} |`).join('\n');
const secrets = web.arcs.map(a => `* **${a.title}** (${a.id}): ${a.secret}`).join('\n');
const thSecrets = web.threads.map(t => `* **${t.title}** (${t.id}): ${t.secret}`).join('\n');
const byTier = web.arcs.reduce((m, a) => (m[a.tier] = (m[a.tier] || 0) + 1, m), {});

const doc = `# The lore web: a connected web of mysteries

Every marquee settlement, dungeon, landmark and minor site of \`tools/marquee/marquee.json\` (all ${ids.length}) has a
**situation**: a gimmick of its landscape, a mystery you learn slowly, three environmental clues a builder can place,
two follow-up links to other places and a payoff. The situations are organised into **${web.arcs.length} regional arcs**
(4-8 places each, a trail of clues with a finale) and **${web.threads.length} continent threads** (7-9 places across several
provinces). Together they form one graph (${links} links, ${crossProv} of them across provinces) in which no place is an orphan.

Legal: facts and themes only; every name of an invented person, every note and every line is original text.

## The idea

The best places have a reason to be where they are (a cliff over the sea, a caldera, a sunken court) and something
strange that makes you stop on arrival: a cult, a curse, a spreading blight, a strange trade, a sealed vault, a lost
expedition. Each place here states that strangeness once, then leaves **three clues** in the environment (sigils,
failing crops, mourning flags), a **readable note** and a **rumour** that point at the next place. Following the trail
is the reward; the finale of each arc is a named foe and a real payoff.

## What the player sees

| What | How it is delivered |
| --- | --- |
| rumour | the place's lore \`rumour\` (townsfolk "Any news?", innkeepers) |
| hook | the place's lore \`hook\` (map tooltip, discovery line): the site gimmick |
| note | a readable in the place's chests, boss chest or lectern (\`web\` = the arc's note, \`thread\` = the thread's note). Reading it marks the next place on the map (\`reveals\`) |
| hidden quest | the first place of every arc and thread holds a clue ("Something here catches your eye"): a stone, lectern, urn, grave, skeleton, lever or totem that starts quest \`lw_<arc>_a\` |
| follow-on | quest \`lw_<arc>_b\` starts by itself when part a is done and ends with a named boss at the finale |

The last place of an arc links to the first place of the next arc of the same province (and its note reveals it), so
the arcs of a province also chain into a ring.

## Data and files

| File | Role |
| --- | --- |
| \`tools/marquee/loreweb_src/<province>.json\`, \`threads.json\`, \`extra.json\` | authored source |
| \`tools/marquee/loreweb_build.js\` | builds everything below from the source |
| \`tools/marquee/loreweb_doc.js\` | rewrites the tables of this file |
| \`tools/marquee/loreweb.json\` | machine-readable web for builders (city plans, landmarks, scatter) |
| \`mod/src/main/resources/nirncraft/lore/places/loreweb.json\` | hooks, rumours and notes (listed in that folder's \`index.json\`) |
| \`mod/src/main/resources/nirncraft/quests/loreweb_<province>.json\`, \`loreweb_threads.json\` | quests, ids \`lw_*\` (listed in \`quests/index.json\`) |
| \`tools/marquee/loreweb_check.js\` | verifies links, orphans, starts and ends |

Workflow: edit \`loreweb_src\`, then \`node tools/marquee/loreweb_build.js\`, \`node tools/place_lore.js\`,
\`node tools/quest_lang.js\`, \`node tools/marquee/loreweb_check.js\`, \`node tools/marquee/loreweb_doc.js\`.

A place's lore lives in exactly one file. ${elsewhere} of the ${ids.length} marquee places already had lore in another file
(capitals, the Skyrim-adjacent writers' work, Solstheim); for those the web adds no note or rumour (\`lore_elsewhere\`
is set), but their situation, clues and quests are still part of the web, and builders may place their clues as well.
The other ${withLore} places get a hook, a rumour and one or two notes from the web.

## Schema of \`tools/marquee/loreweb.json\`

\`\`\`json
{
  "version": 1,
  "arcs": [{"id": "cyr_1", "title": "...", "province": "cyrodiil", "tier": "S|A|B", "secret": "the truth of the arc",
            "places": ["ordered place ids"],
            "entry": {"place", "clue": "stone|lectern|urn|grave|skeleton|lever|totem", "offset": [dx, dz], "text": "the clue as the player reads it"},
            "finale": {"place", "boss": "name", "reward": {"gold", "loot", "unique"}},
            "quests": ["lw_cyr_1_a", "lw_cyr_1_b"]}],
  "threads": [{"id": "t_welkynd", "title": "...", "tier": "...", "secret": "...", "places": ["..."], "entry": {}, "finale": {},
               "provinces": ["..."], "quests": ["lw_t_welkynd_a", "lw_t_welkynd_b"]}],
  "situations": {
    "<place id>": {
      "id": "...", "name": "...", "province": "...", "kind": "settlement|dungeon|landmark|minor", "tier": "S|A|B",
      "place_type": "...", "region": "...",
      "arc": "cyr_1", "arc_step": 1, "thread": "t_welkynd (optional)", "thread_step": 1,
      "site":    "(a) the gimmick: the landscape or placement that defines it (also the lore hook)",
      "mystery": "(b) what you slowly learn",
      "clues":   ["(c) three environmental clues a builder can place"],
      "follow":  ["(d) two place ids: the next step, and a thread or neighbour link"],
      "payoff":  "(e) unique loot, spell, relic or location",
      "rumour":  "the rumour line (absent when the place's lore is written elsewhere)",
      "thread_clue": "the thread's extra clue, if the place is on a thread",
      "lore_note": ["place.<id>.web", "place.<id>.thread"], "lore_elsewhere": "file holding its lore, if any",
      "quests": ["lw_..._a"]
    }
  }
}
\`\`\`

**For builders.** \`clues\` and \`thread_clue\` are short, placeable descriptions ("a scaffold post tallied with a thousand
chalk strokes", "ash seats facing an empty stage"). A city plan or landmark builder looks the place id up in
\`loreweb.json\` and sets the clues as signs, props, banners or decals near the described spot; \`site\` is the
landscape gimmick the layout should honour (cliff, caldera, causeway, hanging gardens). \`arcs[].entry.offset\` is where
the hidden clue of the arc waits, relative to the place centre.

## Regional arcs (${web.arcs.length}: ${Object.entries(byTier).map(([k, v]) => v + ' tier ' + k).join(', ')})

| id | Province | Arc | Tier | Trail (first > ... > finale) | Finale foe |
| --- | --- | --- | --- | --- | --- |
${arcRows}

### The secrets of the arcs

${secrets}

## Continent threads (${web.threads.length})

| id | Thread | Provinces | Trail |
| --- | --- | --- | --- |
${thRows}

${thSecrets}

## The graph

* **Nodes**: the ${ids.length} marquee places. **Edges**: \`follow\` (two per place, ${links} in all).
* **Arc edge**: every place points to the next place in its arc. The finale of an arc points to the first place of the
  next arc of the same province and, if it belongs to a thread, on along the thread.
* **Thread edge**: a place on a continent thread points to the next place on the thread, which may lie in another
  province. Threads cross each other and the arcs: the Welkynd Census runs from Cyrodiil through Valenwood to Summerset, the
  Ash Remembers from Red Mountain through Cyrodiil and Solstheim to the Shadowfen, and the Tide-Ledger from Yokuda through
  Hammerfell, Solstheim, Summerset and the Maormer isles.
* **Neighbour edge**: when a place has no thread, its second link is its nearest marquee neighbour in another arc
  (preferring the same province), so following one trail keeps surfacing the next.
* The web is one connected component; \`loreweb_check.js\` fails on any orphan, dead end, missing target or unstarted arc.

## Writing guidelines (for later additions)

1. A place's mystery is about **this** place and its region (Dunmer house politics and ash blight, Skyrim barrows and
   dragon cults, Cyrodiil's Ayleid and Mythic Dawn echoes, Hammerfell's Forebears and Na-Totambu, Elsweyr's moons and sugar,
   Valenwood's Green Pact, Summerset's Psijic time, Black Marsh's Hist).
2. Do not repeat a mystery; vary the shape (a cult, a debt, a counting, a missing hour, a forged tomb, a tapped root...).
3. The three clues are things a builder can place without scripting. The note holds a concrete lead to the next place.
4. Notes are 150+ characters in the voice of whoever wrote them; the hook is 60-320 characters in narrator voice.
5. Only existing place, creature, item and unique ids; the validators tell.
`;
fs.writeFileSync(path.join(repo, 'docs', 'LORE-WEB.md'), doc);
console.log('docs/LORE-WEB.md written');
