# R4a Town overhaul: planned cities, recognisable shops, working people, real interiors

Source: owner playtest feedback, 2026-10-13 (verbatim below). It refines R4 (Skyrim uplift, settlements) and R3d (town
services). Read `docs/START-HERE.md`, `docs/AGENT-RULES.md`, `docs/WORLDGEN-PIPELINE.md` and
`docs/world/DESIGN-PRINCIPLES.md` first. World building is king: a town's plan, trades and look must follow its
region's causal world-model sheet (geology, economy, culture, history); nothing is decoration.

## The owner's words

> "In the towns there are a bunch of altars randomly placed, something in the middle of the road hovering over a candle.
> And right now it's so hard to tell where the inn is, where the shops are. The shops don't have any interiors and desks
> that people are sitting at. They are small. They don't have the equipment they should have. Outside a blacksmith a
> forge, an anvil, tanning racks and weapon improvement stuff; in an apothecary a place to brew potions, etc. All the
> shops need to look unique and match their theme but also be recognisable and have large enough interiors to have people
> at the counter when open. Signs outside designating them, and a look and feel, even with variations from the regions,
> that make them recognisable and easy to identify.
> Also all the townspeople still do not follow their tasks throughout the day. Rarely do I even see a blacksmith in the
> blacksmith shop, or an inn with an innkeeper. And some inns are so small you can't even have a desk with multiple
> rooms. We need to greatly enhance this: shop owners' behaviours follow the elements and are working their shops. And
> integrate this in all the proper cities, locations, off-the-path places like inns, smaller towns, without damaging the
> town's layout too much since these buildings need larger size.
> Also the major cities do not have a big jarl or leader home that is big and houses all the key members of the family.
> Depends on the size of the city, but the king or the mages who are for the king, the servants, etc., they don't sit in
> there that often, the king isn't sitting on his throne or talking to advisors. And the building placement is tiny and
> doesn't stand out or have a grand entrance and a road to it. In fact the general city layouts are not well designed.
> They are better. But there are many repeated homes that are tiny. And the road network cannot reach all the main parts
> of a town like the shops, the guilds, the leader, the jail, inns, and it's not built in a way that makes sense. And in
> some towns like Whiterun, the upper district doesn't connect to the lower: it's literally just raised up with no
> stairs. So if we could build much more thought-out, planned and orchestrated cities, both minor and major settlements
> and capitals, that would make a huge difference, including the unique layouts and builds for the ones we have done like
> the cool tree or other things being incorporated.
> Also some of the scatter in large towns and small towns is weird: a small crop fence in the middle of town, cracks in
> the city ground which people can't leave their homes over. And the interiors of all the houses and places are so
> repetitive and boring, very small even for the wealthy people, layouts inside the buildings that don't make sense."

Also, from earlier playtests: two posted guards per major settlement, one wandering in a minor one, none below that
(agent `guards`, see `docs/SOCIETY` once merged); jail redesign (crime agent).

## Steps (each ends QA green + commit + WORLD-PLAN section 7 row)

### TO0 Audit (read-only, first, 1 agent)
Measure the real defects with the existing tools (`/skysettle plan <id>`, `tools/qa/*audit*`, `population_lint.py`, the
flow audit): for every settlement with a plan, list (a) which key buildings (inn, each shop trade, temple/chapel, guild
halls, jail/guardhouse, leader's hall, court) are NOT reachable from the gate by a walkable street without a jump or
unclimbable step, (b) tier disconnections (Whiterun's upper district with no stairs), (c) loose scatter in the town
footprint (crop fences, stone altars on roads, candles on the road, cracks/holes that trap), (d) interior sizes per
building class against the Skyrim tier medians, (e) repeated tiny homes (same blueprint count per settlement), (f) NPC
jobs actually kept by day (sample the schedule dump). Output `docs/world/TOWN-AUDIT.md` + a JSON the later steps use as
acceptance tests.

### TO1 The planner: districts, avenues and steps
Rework the settlement planner (baked `streetplan`/`sceneplan`; see WORLDGEN-PIPELINE) so a settlement is *designed*:
tiers and districts (gate/market/craft/temple/noble/leader/garrison/residential/poor), a civic spine from every gate to
the centre, an avenue with a grand approach (steps, plaza, trees or banners) to the leader's hall, and every lot with
frontage on a street. Rules: every key building reachable by a walkable path from every gate; between terraces always
real stairs, ramps or switchback roads (never just a raised lip); no tiny repeated homes: lot size classes by wealth and
district with at least 4 house footprints per settlement; reserved lots for the large service buildings of TO2/TO3.
Keep the hand-made/unique layouts (Whiterun's tree, Dragonsreach, Windhelm's, Markarth's, etc.): they are fixed anchors
the planner builds around. Measured acceptance: reachability 100% on the audit list, stairs between all tiers.

### TO2 Shops, inns and civic buildings you can recognise
A building kit per trade with exterior identity (signs hanging outside with a trade symbol, a distinctive silhouette,
roof/colour per region) and a real interior sized for staff and customers: counter and sitting desks, stock shelves, and
the trade's equipment visible and working (smith: forge, anvil, quench barrel, grindstone, tanning racks, weapon racks,
workbench; apothecary: alchemy table, drying herbs, shelves of vials; inn: common room, bar, kitchen, hearth, stairs, 4+
rented rooms with beds and desks; general store, fletcher, tailor, jeweller, temple, guild halls, barracks, jail). Sizes
at least the Skyrim medians. Regional variants (Nordic, Imperial, Dunmer, Breton, Redguard, Khajiit, Altmer, Bosmer,
Argonian). Roadside inns and small-town versions exist at smaller scale but still with a counter, a desk and 2+ rooms.
Also remove the stray altars/candles from streets (find which generator places them).

### TO3 The leader's seat and real interiors
Each city/major town gets a hall sized to its tier: throne room, court gallery, steward's office, advisor and mage
chambers, family and servant quarters, kitchen, guard post, cellar/armoury, with grand entrance and avenue (TO1 lot).
Houses: 3+ interior layout families per size class (wealthy houses are big: hall, bedrooms, study, kitchen, cellar), room
logic (bed in bedroom, hearth in main room, storage near the door), furniture variety, lighting, per-region materials,
no identical interiors in a settlement.

### TO4 Working people (routines)
NPCs keep their day: shopkeeper at the counter by day, smith at the forge, innkeeper behind the bar until late,
guards on post/patrol, jarl on the throne for court hours then dining, advisors/stewards/mages at their desks, servants
cleaning/cooking, citizens commuting home/market/tavern/temple, everyone asleep in their bed at night. Verify with the
schedule dump over a full day; failures counted by the audit. Owners locked out of their shop at night go home. Needs
work anchors from TO2/TO3 (counter, forge, throne, desk, bed) as data.

### TO5 Scatter and ground clean-up
No farm fences or crop plots inside the dense town core (only in designated garden/farm lots), no cracks/holes in
streets (find the crack generator and its preconditions), nothing blocking doors, props only on lots, never on roads.
Lint in QA: door-to-street clearance, no 1-block pits inside footprints, no prop on a street cell.

### TO6 Roll-out
Apply to every settlement in Skyrim first (R4 floors), then Cyrodiil, Morrowind, ... with each province's sheet; roadside
inns and minor settlements included; unique hand-made layouts kept as anchors.

## Constraints
No Bethesda assets or text; layouts are original (facts such as positions and sizes may be used from owner installs
into the gitignored `.reference/` only). Re-bake after any world-generation class change (`qa.ps1 -Server -Rebake`).
Never launch the Minecraft client; the owner playtests visuals.
