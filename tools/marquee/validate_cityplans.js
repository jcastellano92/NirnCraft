#!/usr/bin/env node
/**
 * Validates the city plans (mod/src/main/resources/skycraft/tamriel/cityplans/*.json, format in
 * settlement/plan/CityPlan.java) and their style overlays, offline:
 *   node tools/marquee/validate_cityplans.js            check; exit 1 on errors
 *   node tools/marquee/validate_cityplans.js --lang     also merge each plan's "titles" into
 *                                                       mod/src/main/lang/skycraft/settlements.json
 * Checks: JSON parses; id is a place; index lists every file; landmark ids are registered (LandmarkRegistry.java);
 * building/queue kinds are archetypes; flags are known; overlays exist and their ids hash without collision;
 * base "none" has gates inside the radius; reach/radius do not exceed the place radius; silhouette tags unique
 * (also against tools/marquee/marquee.json: a plan's sil must match its marquee entry when it has one); every name
 * key shown on a sign has a title (or an existing lang entry). Names and geometry only; no game text.
 */
const fs = require("fs");
const path = require("path");
const root = path.resolve(__dirname, "..", "..");
const res = path.join(root, "mod", "src", "main", "resources", "skycraft", "tamriel");
const plansDir = path.join(res, "cityplans");
const overlayDir = path.join(res, "styles", "overlays");
const langFile = path.join(root, "mod", "src", "main", "lang", "skycraft", "settlements.json");
const writeLang = process.argv.includes("--lang");

let errors = 0;
const err = (m) => { errors++; console.log("ERR  " + m); };
const warn = (m) => console.log("WARN " + m);

// places
const places = {};
const pidx = JSON.parse(fs.readFileSync(path.join(res, "places", "index.json"), "utf8"));
for (const f of pidx) {
  const p = path.join(res, "places", f);
  if (!fs.existsSync(p)) continue;
  for (const e of JSON.parse(fs.readFileSync(p, "utf8"))) places[e.id] = e;
}
// registry ids and archetypes parsed from the Java sources
const reg = fs.readFileSync(path.join(root, "mod", "src", "main", "java", "com", "skycraft", "tamriel", "settlement", "build", "LandmarkRegistry.java"), "utf8");
const landmarkIds = new Set([...reg.matchAll(/reg\("([a-z_]+)"/g)].map((m) => m[1]));
const arche = fs.readFileSync(path.join(root, "mod", "src", "main", "java", "com", "skycraft", "tamriel", "settlement", "plan", "Archetype.java"), "utf8");
const archetypes = new Set([...arche.matchAll(/^\s+([A-Z_]+)\((?:true|false),/gm)].map((m) => m[1].toLowerCase()));
const FLAGS = new Set(["for_sale", "upper", "rich", "water", "no_beard", "fixed", "major", "palisade", "ruined", "sign", "seat", "ground"]);
const LINE_KINDS = new Set(["street", "wall", "bridge", "canal"]);

function overlayIndex(id) {
  let h = 0xcbf29ce484222325n;
  const mask = (1n << 64n) - 1n;
  for (const ch of id) { h ^= BigInt(ch.charCodeAt(0)); h = (h * 0x100000001b3n) & mask; }
  return 1 + Number((h ^ (h >> 29n)) & 0x3fffffn);
}

const marquee = {};
try {
  const mq = JSON.parse(fs.readFileSync(path.join(root, "tools", "marquee", "marquee.json"), "utf8"));
  for (const prov of mq.provinces) for (const s of prov.settlements || []) marquee[s.id] = s;
} catch (e) { warn("marquee.json not read: " + e.message); }

const index = JSON.parse(fs.readFileSync(path.join(plansDir, "index.json"), "utf8"));
const files = fs.readdirSync(plansDir).filter((f) => f.endsWith(".json") && f !== "index.json").map((f) => f.slice(0, -5));
for (const f of files) if (!index.plans.includes(f)) err(`${f}.json is not listed in cityplans/index.json`);
for (const p of index.plans) if (!files.includes(p)) err(`index lists ${p} but there is no file`);

const lang = JSON.parse(fs.readFileSync(langFile, "utf8"));
const sils = {}, overlayHash = {}, titles = {};
const usedOverlays = new Set(index.overlays || []);

function checkKind(where, kind) {
  if (!archetypes.has(String(kind).toLowerCase())) err(`${where}: unknown archetype ${kind}`);
}
function checkFlags(where, flags) {
  for (const f of flags || []) if (!FLAGS.has(f)) err(`${where}: unknown flag ${f}`);
}
function checkPoint(where, plan, pt, slack) {
  if (!Array.isArray(pt) || pt.length < 2) return err(`${where}: bad point`);
  const r = Math.hypot(pt[0], pt[1]);
  const limit = (plan.radius || 110) + slack;
  if (r > limit) err(`${where}: (${pt}) is ${Math.round(r)} from the anchor, beyond radius ${plan.radius || 110} + ${slack}`);
}

const featureSets = {};
const usedNames = new Map();
const use = (id, key) => { if (key) usedNames.set(key, id); };

for (const id of index.plans) {
  const fp = path.join(plansDir, id + ".json");
  if (!fs.existsSync(fp)) continue;
  let plan;
  try { plan = JSON.parse(fs.readFileSync(fp, "utf8")); } catch (e) { err(`${id}: ${e.message}`); continue; }
  if (plan.id !== id) err(`${id}: id is ${plan.id}`);
  const place = places[id];
  if (!place) { err(`${id}: no such place`); continue; }
  const base = plan.base || "none";
  if (!["none", "zoning", "java"].includes(base)) err(`${id}: base ${base}`);
  // java base keeps the hand layout's own radius (the imperial city plans on 160)
  const radius = plan.radius || (base === "java" ? place.radius : Math.min(place.radius, 110));
  const reach = plan.reach || radius;
  if (plan.radius && plan.radius > place.radius) err(`${id}: radius ${plan.radius} > place radius ${place.radius}`);
  if (plan.reach && plan.reach > place.radius + 40) err(`${id}: reach ${plan.reach} too far past place radius ${place.radius} (spacing lint)`);
  if (base === "none" && !(plan.gates && plan.gates.length)) err(`${id}: base none needs gates`);
  for (const g of plan.gates || []) if (Math.hypot(g[0], g[1]) > reach + 10) err(`${id}: gate ${g} beyond reach ${reach}`);
  if (plan.sil) {
    if (sils[plan.sil]) err(`${id}: silhouette ${plan.sil} also used by ${sils[plan.sil]}`);
    sils[plan.sil] = id;
    if (marquee[id] && marquee[id].sil !== plan.sil) err(`${id}: sil ${plan.sil} differs from marquee.json (${marquee[id].sil})`);
  } else {
    warn(`${id}: no sil tag`);
  }
  const p2 = { radius };
  const slack = 14;
  for (const t of plan.terraces || []) checkPoint(`${id} terrace`, p2, [t.rect[0], t.rect[1]], slack + 10);
  for (const l of plan.landmarks || []) {
    if (!landmarkIds.has(l.id)) err(`${id}: unknown landmark ${l.id}`);
    checkPoint(`${id} landmark ${l.id}`, p2, l.at, slack + 8);
    checkFlags(`${id} landmark ${l.id}`, l.flags);
    if (l.overlay) usedOverlays.add(l.overlay);
  }
  for (const b of plan.buildings || []) {
    checkKind(`${id} building`, b.kind);
    checkPoint(`${id} building ${b.kind}`, p2, b.at, slack + 10);
    checkFlags(`${id} building ${b.kind}`, b.flags);
    use(id, b.name);
    if (b.overlay) usedOverlays.add(b.overlay);
  }
  for (const s of [...(plan.streets || []), ...(plan.lines || [])]) {
    if (s.kind && !LINE_KINDS.has(s.kind)) err(`${id}: line kind ${s.kind}`);
    checkPoint(`${id} line from`, p2, s.from, slack + 10);
    checkPoint(`${id} line to`, p2, s.to, slack + 10);
  }
  for (const r of plan.rings || []) if (r.r > radius + slack) err(`${id}: ring ${r.r} beyond radius ${radius}`);
  for (const w of plan.walls || []) if (w.r > radius) err(`${id}: wall ring ${w.r} beyond radius ${radius}`);
  for (const z of plan.plazas || []) { checkPoint(`${id} plaza`, p2, z.at, slack); use(id, z.name); }
  for (const q of plan.queue || []) {
    checkKind(`${id} queue`, q.kind);
    checkFlags(`${id} queue ${q.kind}`, q.flags);
    use(id, q.name);
    if (q.on && !(plan.streets || []).some((s) => s.tag === q.on) && !(plan.rings || []).some((s) => s.tag === q.on)
        && !(plan.nets || []).some((s) => (s.tag || (s.type === "radial" ? "spoke" : "")) === q.on || (s.type === "radial" && (s.ring_tag || "ring") === q.on))) {
      err(`${id}: queue entry on "${q.on}" matches no street or net tag`);
    }
  }
  // organic nets
  const NETS = new Set(["path", "loop", "radial", "switchback", "contour", "canal", "wall", "terraces"]);
  for (const n of plan.nets || []) {
    if (!NETS.has(n.type)) err(`${id}: unknown net type ${n.type}`);
    for (const key of ["via", "gates"]) for (const pt of n[key] || []) if (Array.isArray(pt)) checkPoint(`${id} net ${n.type} ${key}`, p2, pt, slack + 14);
    for (const key of ["from", "to", "at"]) if (Array.isArray(n[key])) checkPoint(`${id} net ${n.type} ${key}`, p2, n[key], slack + 14);
    if (n.type === "radial" && n.r1 > radius + slack) err(`${id}: radial net r1 ${n.r1} beyond radius ${radius}`);
    if (n.type === "canal" || n.type === "wall") if ((n.via || []).length < 2) err(`${id}: ${n.type} net needs via points`);
  }
  // civic features: known types, per-tier counts (hamlet 1-2, village 2-4, town 4-8, city 8-15, capital 15+), unique sets
  const FEATURES = new Set(["great_tree", "tree", "tree_row", "fountain", "well", "pond", "pool", "basin", "aqueduct", "waterwheel",
    "statue", "shrine", "memorial", "bell_tower", "banners", "lamps", "garden", "orchard", "market", "flowers", "bench", "gatehouse"]);
  if ((plan.features || []).length) {
    let total = 0;
    const sig = [];
    for (const f of plan.features) {
      if (!FEATURES.has(f.type)) err(`${id}: unknown feature type ${f.type}`);
      total += f.count || 1;
      sig.push(`${f.type}:${f.theme || ""}x${f.count || 1}`);
      if (Array.isArray(f.at)) checkPoint(`${id} feature ${f.type}`, p2, f.at, slack + 10);
    }
    const t = place.type;
    const range = { capital: [15, 99], city: [8, 15], town: [4, 8], village: [2, 4], hamlet: [1, 2] }[t];
    if (range && (total < range[0] || total > range[1])) err(`${id}: ${total} civic features, a ${t} wants ${range[0]}-${range[1]}`);
    const key = sig.sort().join("|");
    if (featureSets[key]) err(`${id}: same feature set as ${featureSets[key]}`);
    featureSets[key] = id;
  } else if (base !== "zoning") {
    warn(`${id}: no civic features listed (a seeded set is drawn from the culture catalogue)`);
  }
  if ((plan.queue || []).length && base === "zoning") warn(`${id}: queue is ignored for base zoning`);
  for (const q of plan.quarters || []) {
    if (!q.rect && !q.ring) err(`${id}: quarter needs rect or ring`);
    if (q.overlay) usedOverlays.add(q.overlay);
  }
  if (plan.overlay) usedOverlays.add(plan.overlay);
  use(id, plan.innKey);
  for (const k of ["inn", "store", "smithy"]) use(id, (plan.names || {})[k]);
  Object.assign(titles, plan.titles || {});
}

for (const o of usedOverlays) {
  if (!fs.existsSync(path.join(overlayDir, o + ".json"))) err(`overlay ${o}: no file styles/overlays/${o}.json`);
  const h = overlayIndex(o);
  if (overlayHash[h] && overlayHash[h] !== o) err(`overlay index collision: ${o} / ${overlayHash[h]}`);
  overlayHash[h] = o;
}
for (const f of fs.existsSync(overlayDir) ? fs.readdirSync(overlayDir) : []) {
  const o = f.slice(0, -5);
  try { JSON.parse(fs.readFileSync(path.join(overlayDir, f), "utf8")); } catch (e) { err(`overlay ${o}: ${e.message}`); }
  if (!usedOverlays.has(o)) warn(`overlay ${o} is not used by any plan`);
}

// sign names
const missing = [];
for (const [key, id] of usedNames) {
  const lk = "settlement.skycraft.name." + key;
  if (!(key in titles) && !(lk in lang)) missing.push(`${id}: no title for ${key}`);
}
if (writeLang) {
  let n = 0;
  for (const [key, text] of Object.entries(titles)) {
    const lk = "settlement.skycraft.name." + key;
    if (lang[lk] !== text) { lang[lk] = text; n++; }
  }
  const sorted = {};
  for (const k of Object.keys(lang).sort()) sorted[k] = lang[k];
  fs.writeFileSync(langFile, JSON.stringify(sorted, null, 2) + "\n");
  console.log(`lang: ${n} keys written to settlements.json`);
} else {
  for (const m of missing) err(m);
}
console.log(`${index.plans.length} plans, ${Object.keys(sils).length} silhouettes, ${usedOverlays.size} overlays: ${errors} errors`);
process.exit(errors ? 1 : 0);
