# Playtest 1: work plan and contracts

The first real playtest produced a long list of fixes and features. This file splits that list into
workstreams. For each one it says who owns which code, and it defines the exact APIs where workstreams meet
("contracts"). Every workstream agent reads the **Rules** section and its own section, plus any contract it
implements or calls.

## Rules for every workstream

* **Build target:** Forge 1.20.1 (compiled against 47.3.0, the pack runs 47.4.10), official Mojang mappings,
  Java 17.
* **Compile locally before reporting.** Each workstream works in its own git worktree
  (`F:\Projects\Personal\Skycraft\wt\<letter>`, branch `pt1/<letter>`). From `<worktree>\mod` run
  `.\gradlew.bat compileJava --console=plain -Dorg.gradle.jvmargs=-Xmx2G` (PowerShell flags Gradle's stderr as
  an error; trust the `BUILD SUCCESSFUL` / `BUILD FAILED` line). Fourteen workstreams share one PC, so compile
  when a chunk of work is done, not after every edit. Finish with `.\gradlew.bat build` passing.
  * To check real 1.20.1 Mojang-named signatures, run `javap -cp <jar> <class>` against
    `C:\Users\Jeremy\.gradle\caches\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.3.0_mapped_official_1.20.1\forge-1.20.1-47.3.0_mapped_official_1.20.1.jar`
    (e.g. `javap -cp <jar> net.minecraft.world.entity.player.Player`), or just compile.
  * Contract classes already exist as stubs with fixed signatures (`crime/Ownership`, `combat/Sheathe`,
    `combat/RespawnPoints`, `society/Followers`, `Leveling.regionLevel`, `Discovery.revealNear`,
    `Shop.isOpen`, the contract-7 `*Client` classes). Call them freely; only the owner changes the bodies.
* **No mixins, no access transformers.** Use Forge events, public API, and
  replacing screens through `ScreenEvent.Opening`. Reflection is only allowed by name on non-Minecraft
  classes.
* **Never reference client classes from common code.** Client classes include `Minecraft`, `Screen`,
  `KeyMapping`, renderers and `LocalPlayer`. A packet or common class hands off with
  `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SomeClientClass.method(commonTypedArgs))`. Breaking
  this crashed the dedicated server before.
* **Worldgen code must never read or write blocks outside the region being generated.** Use
  `level.hasChunk` guards. Breaking this crashed worldgen before.
* **Multiplayer:**
  * The server is authoritative and state is synced to clients.
  * Everything must work both for a host on Essential (integrated server, other players joining) and on a
    dedicated server.
  * Data that belongs to a player lives in the player capability: `SkyData.get(player).module("<name>")`.
    It survives death through the existing clone handling.
* **Packets:** each module registers its packets through its own `registerPackets()` hook, the way existing
  modules do (see `network/SkyNetwork.java`).
* **Lang:** add keys only to your module's file `mod/src/main/lang/skycraft/<module>.json`. Create it if it is
  missing. The `mergeLang` task merges all of them.
* **Textures:**
  * Generate them with Python and PIL from a script in `tools/textures/<module>.py`.
  * Commit both the script and the PNGs.
  * Pixel art at 16×16 for items and blocks; whatever size fits for GUI pieces.
* **Shared files:**
  * Shared files are `Skycraft.java`, `network/*`, `core/*`, `skills/*`, `perk/*`, `registry/*`, `vitals/*`,
    `SkyConfig`, and other modules' files.
  * Make only small, surgical `Edit` insertions to them, and re-read the file right before editing because
    other agents edit concurrently.
  * Never rewrite a shared file wholesale, and never reformat it.
* **Git:** commit to your own branch `pt1/<letter>` in your own worktree, in logical commits. Never push, merge,
  rebase, reset, or touch other branches or the main checkout. The lead merges the branches.
* **Shared files:** because branches are merged later, keep edits to shared files small and additive (new
  lines in registration lists, new methods) so merges stay clean.
* **Steam Deck and controller support:**
  * Every screen must fit and work at **427×267 scaled pixels** (1280×800 at GUI scale 3).
  * Long content scrolls with the mouse wheel and with click-drag on a scrollbar.
  * No fixed panels may overflow, and text must wrap.
  * Hit targets must be at least 12 px tall so the Controllable virtual cursor can use them.
* **Skyrim look:**
  * Reuse `inventory/client/SkyUi.java` (dark translucent panels, thin light borders, small caps headers) and
    `quest/client/Parchment.java` for parchment pages.
  * No vanilla gray buttons in final UI. If no Skyrim-styled button helper exists, add one to `SkyUi`.
* **Final report:** when you finish, write a concise report covering:
  * features done;
  * every file you created, and every shared file you edited;
  * contracts you implemented;
  * keybinds you registered;
  * anything not done, or risky;
  * 3–8 lines of player-facing notes for `docs/FEATURES.md`.
* **Mod list:** the lead edits `tools/modlist.txt`. Third-party mod config files go under `pack/config/...`.

## Mod list changes (done by the lead)

* **Removed:**
  * JEI: shows every item in the game.
  * Jade: block name overlay that overlaps the compass.
  * Villager Recruits: the R-key troop menu and emerald hiring. Replaced by Skycraft followers.
  * Xaero's World Map: replaced by the Skyrim map with explored and unexplored terrain.
  * Waystones: not Skyrim. Fast travel uses discovered locations and carriages.
* **Added:**
  * Controllable: controller support for the Steam Deck and gamepads.
* **Unchanged:**
  * (Phase 3: the third-party villager mod was later removed; Skycraft's society module provides the townsfolk.)

## Default key plan (authoritative; owned by workstream A, followed by everyone)

| Action | Default key | Owner |
| --- | --- | --- |
| Skyrim inventory | **E** (the vanilla inventory key, intercepted) | B |
| Hub menu (Skills ↑, Items ←, Magic →, Map ↓, Journal) | **Tab**. The vanilla player list moves to **`** | A |
| Magic menu | **P** | L |
| Map | **M** | C |
| Journal (quests, factions, party, reputation, stats) | **J** | K |
| Skills | **K** | A |
| Wait | **I** | C (it lives in `world/`) |
| Shout or equipped power (voice slot) | **Z** | L |
| Favorites quick menu | **Q** and the **mouse wheel**. Vanilla drop is unbound; drop from the inventory | B |
| Favorite hotkeys | **1–8** (they replace hotbar slot selection) | B |
| Right hand: attack, or cast the right-hand spell | **LMB**. Hold for a power attack | D (L for spells) |
| Left hand: block with a weapon or shield, or cast the left-hand spell | **RMB** (hold) | D (L for spells) |
| Ready or sheathe weapon | **R** | D |
| Dodge roll (perk-unlocked) | **Left Alt** | D |
| Call your horse | **H** | M |
| Climb (hold against a wall) | **Space** | D |
| Swap hands (vanilla F) | unbound; hands are set in the inventory and favorites | B |

These old keys go away: N (map), G (magic), H/O (powers; now Z), X (favorites; now Q), U and Y (now in the
Journal), V (block; now RMB) and Left Alt as power attack (now hold LMB). Each owner changes its own key
registration. A ships the vanilla defaults through Default Options and updates `KeyConflictResolver`.

## Contracts (exact names; the implementer must provide them exactly)

1. **Ownership** (implemented by G, `com.skycraft.crime.Ownership`):
   * Static methods:
     * `boolean isOwnedByOther(Player player, Level level, BlockPos pos)`: true if using or taking from this
       block is a crime (or not allowed) for this player. It must work on both the client and the server; the
       client reads synced data.
     * `boolean isOwnedByOther(Player player, Entity entity)`: the same check for owned world items, i.e. an
       `ItemEntity` placed as clutter.
     * `void setOwner(ServerLevel level, BlockPos pos, String ownerId, Component ownerName)`: `ownerId` is one
       of `npc:<uuid>`, `faction:<id>`, `player:<uuid>` or `public`.
     * `void setPlayerOwner(ServerLevel level, BlockPos pos, UUID player)`.
     * `@Nullable Component ownerName(Level level, BlockPos pos)`.
   * A placed `ItemEntity` is owned when its persistent data holds the string `skycraft_owner` (same id format).
   * Blocks in enemy camps and dungeons are never "owned": looting them is not a crime.
2. **Quest items**: an `ItemStack` is a quest item when its tag has the boolean `skycraft_quest = true`.
   * Quest items can't be dropped, sold or stolen from you.
   * They stay with you when you die.
3. **Region level** (implemented by E): `com.skycraft.creatures.Leveling.regionLevel(ServerLevel level,
   BlockPos pos)` returns an `int` from 1 to 60.
   * Low near the world spawn, rising with distance and in dangerous places.
   * Loot (I), quests (K) and legendary items (D) use it to scale.
4. **Legendary items** (implemented by D): the loot table `skycraft:legendary` yields exactly one randomized
   named legendary item, scaled by the loot context's position and `regionLevel`.
5. **Lock bonus loot** (tables by I, rolled by G): `skycraft:chests/lock_bonus/novice` … `/master`.
   * G rolls the matching table once, the first time a locked container is picked open by each player.
   * Combine this with Lootr per-player loot where present.
6. **Discovery API** (implemented by C, `com.skycraft.world.Discovery`):
   * `@Nullable Component revealNear(ServerPlayer player, BlockPos center, int radius)`: marks the nearest
     location this player has not discovered as *known*. It shows on the map and compass with its name, but
     isn't discovered yet. Returns its name, or `null` if there is none.
   * Used by NPC rumors (F) and quests (K).
7. **Screen entry points**: static no-arg client methods that the Tab hub (A) calls:
   * `com.skycraft.inventory.client.InventoryClient.open()` (B).
   * `com.skycraft.magic.client.MagicClient.openMagicMenu()` (L).
   * `com.skycraft.world.client.MapClient.openMap()` (C).
   * `com.skycraft.quest.client.JournalClient.open()` (K).
   * `com.skycraft.client.screen.SkillsScreen` is opened by A itself.
   * `com.skycraft.client.CharacterClient.open()` (A) opens the character and status page, which B can also
     link to.
8. **Sheathe state** (implemented by D): `com.skycraft.combat.Sheathe.isSheathed(Player player)`, synced so it
   works on both sides. Guards (G) treat a sheathed or empty-handed player as yielding.
9. **Respawn point** (implemented by D): `com.skycraft.combat.RespawnPoints.set(ServerPlayer player,
   ResourceKey<Level> dim, BlockPos pos)`. D sets it on any sleep; houses (H) and inns may also call it.
10. **Shop hours** (implemented by H): `com.skycraft.economy.Shop.isOpen(Level level)` (day time), so NPC
    routines (F) and signs agree.
11. **Followers** (implemented by F): `com.skycraft.society.Followers.isFollowerOf(Entity npc, Player player)`.

## Workstreams

### A: UI shell and character (`client/` except `client/hud`, `core/Race`, `skills/`, `perk/`, `vitals/`)
* **Character creator replaces "Who are you?"**:
  * It opens on the first join only. You can't skip it, and "Decide later" goes.
  * It shows a live, rotatable player model that visibly changes per race. Draw race-specific render layers
    on the player model: skin tint, Khajiit fur, ears and tail, Argonian scales and frills, elf ears, Orc
    tusks and green skin, and a slightly different scale or height per race. Other players see the same.
  * Race descriptions and bonuses sit in a scrolling panel; every list scrolls.
  * Racial traits must really work: verify every resistance, skill bonus and power in code, fix any that
    don't apply, and show the effects in the character page.
* **Villager mod config:** obsolete (Phase 3 removed the third-party villager mod).
* **Respawn bug:** respawning must never reopen race selection or reset the character.
* **Screens:**
  * A custom death screen (Skyrim style: "You died", with Load/Respawn wording).
  * A custom title screen and main menu with Skyrim-style art, generated textures and the Skycraft logo.
* **No Minecraft tutorial:**
  * Set `tutorialStep:none`.
  * Hide every vanilla toast (recipe unlocks, advancements, tutorial hints): call
    `Minecraft.getInstance().getToasts().clear()` in `RenderGuiEvent.Post` and `ScreenEvent.Render.Post`.
* **Tab hub menu:** Skyrim's four-way menu (Skills, Items, Magic, Map) plus Journal, with mouse and keyboard
  navigation. It uses the contract-7 entry points.
* **Character and status page:** a paper doll of the player, equipped gear, active effects with time left,
  the voice slot, power cooldowns ("once a day" powers with a recharge timer), level, attributes, race,
  Standing Stone, bounty summary and carry weight.
* **Skills screen** (and level-up): must fit the Steam Deck rules and show 24 skills; D adds UNARMED and
  ATHLETICS.
* **Default key plan:**
  * Ship `pack/config/defaultoptions/` with `options.txt` and `keybindings.txt`. Include `tutorialStep:none`,
    the vanilla player list on `` ` ``, drop unbound and swap-hands unbound.
  * Set sensible video defaults for Steam Deck-class hardware.
  * Update `KeyConflictResolver` so it respects the plan.

### B: Inventory, equipment, favorites (`inventory/`)
* **The Skyrim inventory is the only inventory.**
  * E opens it.
  * The vanilla inventory and its 2×2 crafting are never reachable: replace `InventoryScreen` in
    `ScreenEvent.Opening`, and remove the "C opens vanilla inventory" path.
  * Armor, clothing and jewelry are equipped from the inventory (head, chest, hands, feet, plus extra slots
    for amulet, ring and cloak).
* **Layered clothing:**
  * Basic clothing items such as roughspun tunics, linen wraps, boots, hoods and a mage robe.
  * Accessory slots for amulet, ring and cloak, kept in the player capability and rendered on the player with
    a render layer.
  * New characters start in basic clothes.
* **No hotbar:**
  * Hide the vanilla hotbar overlay (`RenderGuiOverlayEvent.Pre` with `VanillaGuiOverlay.HOTBAR`).
  * The right hand is the main hand; the left hand is the off hand.
  * Equipping only happens through the inventory and favorites.
* **Favorites:**
  * Q or the mouse wheel opens a quick-select popup of favorites: weapons, spells, shouts and powers, and
    saved combos such as dagger plus Flames.
  * 1–8 are hotkeys for favorites. Consume the vanilla `keyHotbarSlots` clicks at `ClientTickEvent` START.
* **Equip rules:**
  * Only usable items can be equipped: weapons, shields, tools such as the pickaxe and woodcutter's axe,
    torches, spells (L), armor and clothing.
  * Everything else is Use (eat or drink, read), Drop or Favorite.
  * Hand assignment happens only here and in favorites.
  * Unbind and ignore vanilla swap-hands.
* **Inspect and compare:** the item details panel shows the difference against what you have equipped. Also
  provide a 3D inspect view that rotates the item.
* **Container menu:**
  * One Skyrim-style transfer menu for every container: chests, barrels, Lootr chests, corpses (E's
    `CorpseEntity`) and player corpses.
  * Show **Take** or **Steal** using contract 1.
  * Take All (R), and the weight and value of each item.
* **Gold:**
  * Coin purses become septims as soon as you pick them up; they never sit in the inventory.
  * Septims are stored as currency, not as items in a slot.
  * Septim sprites appear in the inventory, barter and HUD.

### C: HUD, compass, discovery and map (`client/hud/`, `world/` except roads)
* **Compass:**
  * Fix duplicated markers.
  * Undiscovered locations show only their icon, with no name; discovered ones show the name.
  * Nothing may draw over the compass.
* **Notifications:** messages such as "You can't dig here" go to Skyrim's top-left notification stack, never
  over the compass.
* **Crosshair prompts** (replacing Jade): when looking at something you can interact with, show a small
  Skyrim prompt under the crosshair, e.g. "E  Talk  Lydia", "Open  Chest", "Steal  Apple" in red when owned,
  "Search  Draugr", "Sleep  Bed (owned)", "Locked (Adept)". There is no block name overlay for ordinary
  blocks.
* **Discovery:**
  * The first time you find a location or city, a full-screen Skyrim popup ("BLEAK FALLS BARROW DISCOVERED")
    appears with a sound. It never appears again for that location.
  * From then on the location is on the map and compass.
  * Implement contract 6.
* **Map** (M, and the hub's "down"):
  * A Skyrim-like world map with terrain colours drawn from explored chunks.
  * Unexplored areas are covered in cloud or fog; explored areas show terrain.
  * Location icons by kind; names for discovered locations only.
  * Party members, quest markers and the player arrow; zoom and pan; a custom marker.
  * Fast travel to discovered locations and party members.
  * Exploration data per player, saved client-side and kept in sync.
  * Replaces Xaero's World Map; maps found in the world are removed by I.
* **Wait screen** (I), and **sleep rules:** you can't sleep in owned beds or occupied beds; use contract 1.
* **Powers and daily abilities** recharge indicator on the HUD.

### D: Combat and the player body (`combat/`, `arsenal/`, `vitals/`, player death and respawn)
* **Stamina:**
  * Every swing costs stamina, and power attacks cost more.
  * At zero stamina you can't power attack and normal swings are weak.
  * No spam: respect the weapon cooldown.
* **Unarmed:**
  * A new UNARMED skill and perks.
  * Left and right punch animations that alternate with an empty right hand.
  * A charged punch.
* **ATHLETICS skill:** trained by sprinting, climbing and swimming. It improves stamina regen and climb speed.
  Add it to `core/Skill` and the perks.
* **Trade-offs:**
  * Dual-wielding is faster but has no block.
  * Two-handed weapons are slower but stagger.
  * Heavy, light and cloth armor: armor rating against noise, speed and stamina regen. Make these explicit.
* **Power attacks** use hold LMB. **Block** uses hold RMB. **R** sheathes and readies (contract 8).
* **Dodge roll:** on Left Alt, unlocked by a perk, and costs stamina.
* **Climbing:**
  * Holding Space against a climbable wall climbs it, Breath of the Wild style, with stamina drain.
  * At zero stamina you slide or fall.
  * Server-validated, so it can't be used to fly. The server already has `allow-flight=true`.
* **Kill cams:** they must trigger reliably on the killing blow of the last nearby enemy, with a chance
  setting, in both melee and archery.
* **Legendary items:** named, randomized weapons and armor in the style of Fallout 4 legendaries (a named
  prefix plus 1–2 special effects). They drop from bosses, hard places and master locks. Implement contract 4.
* **Player death:**
  * Your body stays as a lootable corpse (the Corpse mod; configure it in `pack/config`). Other players can
    open it and protect it, and it ragdolls with Physics Mod on clients.
  * You keep what you are wearing, your equipped weapons and spells, quest items (contract 2), skills, perks,
    spells, shouts and quests. Everything else goes to the corpse.
* **Respawn:**
  * You respawn at your last rest point (bed, inn or house), otherwise at the world spawn (contract 9).
  * Character creation is never reopened.
* **Pause-menu "Unstuck / Respawn":** only available when no enemy is fighting you, with a 5-minute cooldown.
  It teleports you to your rest point.

### E: Creatures and NPC bodies (`creatures/`, `dungeons/`)
* **Invisible enemies:**
  * A skeleton was invisible. Audit every entity type the mod registers (and vanilla ones we replace or
    retexture) and make sure each has a registered renderer, model and texture.
  * Add a check in `tools/` that fails CI if a registered entity has no renderer registration.
* **Bodies:**
  * Killing an enemy must leave **one** lootable body. Fix the duplicate bodies.
  * The body ragdolls or lies down with the dead creature's **equipped armor and weapons visible**.
  * Taking gear from the body updates how it looks.
  * Applies to every humanoid kill, including kills by NPCs or guards, not only player kills. Check the
    Physics Mod ragdoll interplay.
* **Spawning like Skyrim:**
  * Turn off vanilla hostile natural spawning at night in the overworld: no zombies, creepers, skeletons or
    spiders appearing in the dark.
  * Hostiles come from Skycraft sources: camps, dungeons, wildlife packs, night-time wolves or skeevers in
    the wild, and encounters.
  * Nothing spawns inside houses or settlements.
* **Difficulty by region:**
  * Easy near the world spawn, getting harder with distance and in dangerous terrain (contract 3).
  * Creature levels and gear scale with it.
* **Named bosses:** dungeon bosses with names and boss bars that drop legendaries (contract 4).
* **More enemy types:** Falmer, Forsworn, spriggans, frostbite spiders, ice wraiths, hagravens, wispmothers,
  Dwarven spheres and spiders, necromancers, vampires, and bandit chiefs, using existing model patterns.
* **Losing enemies:**
  * Break line of sight and stay hidden (sneak), and enemies search, then give up and walk back to what they
    were doing.
  * Use the Sneak skill and the detection data from G if it exists.

### F: Society and dialogue (`society/`, `dialogue/`, `client/screen/DialogueScreen`)
* **Talking:**
  * The NPC stops, turns to face you and holds still while you talk.
  * The camera eases in toward the NPC's face, client side.
  * Other players see the NPC as "busy" and can't start a second conversation.
* **One dialogue menu style for every NPC interaction:** trade, train, follow, rent a room, bounty. No third-party
  interaction menu may appear.
* **Roles:** only the right NPCs trade, train, rent rooms or offer to follow. Each NPC has a role; topics
  check it.
* **Followers** (contract 11; replaces Villager Recruits):
  * An NPC follows you only after a relationship is built: a favor quest, enough gold for hirelings, a
    successful Speech check, or a quest reward.
  * Followers have commands (wait, follow, trade items, dismiss), use their gear, and level with the region.
* **Speech checks:** Persuade, Intimidate and Bribe in conversations, with a chance from the Speech skill,
  level and perks. They train Speech.
* **Passive dialogue:**
  * NPCs make ambient remarks when you pass, and share rumors that reveal places on the map using
    contract 6 ("I hear bandits took over Halted Stream Camp…").
  * Guards have Skyrim lines, including the arrow in the knee.
* **Routines:**
  * NPCs work during the day, eat, go home at night and sleep.
  * Shops follow contract 10.
  * Doors to private homes lock at night (with G).
* **Reactions:** commoners attacked by you, or by monsters, sometimes flee and sometimes fight back.

### G: Crime, ownership, locks and stealth (`crime/`)
* **Ownership:** contract 1.
  * Containers, beds, doors and placed clutter in settlements are owned by the household or shop.
  * Show ownership in the container menu and crosshair prompts.
  * Taking owned things is theft only if someone sees it; the items become stolen.
  * Enemy camps and dungeons are never owned.
* **Beds:** you can't sleep in owned or occupied beds; only beds you rent, own, or that are free.
* **Locks:**
  * Lock levels (Novice, Apprentice, Adept, Expert, Master) on chests and doors.
  * Harder locks guard better loot (contract 5).
  * Make sure the lockpicking minigame works; it needs lockpicks.
  * House doors lock at night; owners and the household can always open them.
* **Stealth:** a Skyrim sneak eye that shows hidden, caution and detected, using light, armor weight,
  movement and the Sneak skill.
* **Witnessed crime only:**
  * Bounty is per hold and per player, never shared with the party.
  * Farm animals belong to their farm, so killing them is a crime.
* **Yield:**
  * When guards come for you, sheathing your weapon (contract 8) makes them stop and talk: pay the bounty,
    go to jail, or resist.
  * Attacking a guard while yielding is resisting arrest.

### H: Economy, shops and houses (`economy/`)
* **Barter screen:**
  * Buy and Sell tabs, in the shared Skyrim style.
  * The Sell tab lists only the categories this merchant buys.
  * The merchant's gold is visible and limited, restocks every 48 in-game hours, and is shared by all players
    (server state).
  * Stock is synced to every player, and selling raises the merchant's gold.
* **Merchant gold:** Speech perks raise it, and you can **invest** 500 gold in a shop for more gold and new
  stock (Master Trader / Investor).
* **Shop hours** (contract 10): Open and Closed signs on the shop, and NPCs refuse to trade when closed.
* **Houses:**
  * For Sale signs on some town houses; the steward or Jarl sells them, and you can buy them from the sign
    too.
  * Buying one makes the house blocks player-owned (contract 1) and sets the respawn point (contract 9).
  * Furnishing upgrades come from a furnishing merchant (steward): a bed, chests, an alchemy lab, an
    enchanter, mannequins and so on. Apply them as block placement presets inside the house.
* **Selling scope:** not every NPC buys or sells; only roles that trade (with F).

### I: Crafting, items and loot (`crafting/`, `survival/`, `loot/`, `lore/` items)
* **No vanilla crafting:**
  * The vanilla crafting table and the 2×2 grid are unusable. The vanilla crafting table block becomes a
    Skyrim **workbench** or is removed from loot and recipes.
  * Everything is made at stations, each with a **custom Skyrim menu**: forge, smelter, tanning rack,
    workbench (armor tempering), grindstone (weapon tempering), arcane enchanter, alchemy lab, cooking pot
    and spit.
  * Category lists on the left, item details and requirements on the right, and a "Craft" button.
* **Station placement:** smithies, alchemy shops and inns in settlements get the matching stations.
* **Wood:**
  * Trees are harvested like ore veins with the woodcutter's axe: wood types and tiers (pine, birch, oak,
    and rarer woods).
  * Better wood makes better bows, staves and furniture.
  * Felled trees regrow.
* **Ores:**
  * Remove pointless vanilla ores from worldgen: redstone, lapis and emerald. Diamond becomes a gem found in
    loot only.
  * Skyrim ores stay.
  * Every remaining item has a purpose.
* **Item classes** (tags `skycraft:class/<name>`) and **location and level-aware loot**:
  * Farmhouses have food, clothes and common tools.
  * Bandit camps have iron and steel gear and gold.
  * Dungeons scale with contract 3.
  * No out-of-place loot: no elven greatsword in a farm hut, no vanilla maps or compasses, no redstone junk.
  * Replace or filter the vanilla structure loot that third-party structures use.
* **World clutter:** items placed in the world (on tables, shelves and counters) that can be picked up, and
  are owned (contract 1) in friendly settlements.
* **Ingredients:** every alchemy ingredient can be eaten, revealing its first effect, including vanilla ones
  such as the wither rose.
* **Assets:** audit every item and block for its model and texture. Add `tools/check_assets.py`, which fails
  CI on a missing model or texture.
* **Lock bonus tables** (contract 5).

### J: World and roads (`roads/`, worldgen data and configs)
* **Roads:**
  * Roads connect every settlement and major location to the network; there are no orphan towns.
  * Roads are wider and cleaner (cobble and path mix, edge stones).
  * **Lanterns and lamp posts** along the way, more in towns.
  * Bridges where needed.
  * Snow never covers the road surface, and road blocks resist snow layers.
* **Worldgen:**
  * Smoother transitions, broad flat valleys, and some truly large natural mountains (Tectonic and Terralith
    config in `pack/config`).
  * Tune structure frequency and spacing so towns and exploration points feel deliberate.
* **Snow and roads in all biomes:** the road surface is always visible.

### K: Quests and lore (`quest/` except the Dragonborn and College lines, `lore/`)
* **Quest state:**
  * Quests belong to the character, survive death, and are never restarted on death.
  * Quests can be shared with the party.
  * You can abandon a quest from the journal (except main-story steps).
* **Starter quest near the world spawn:**
  * The difficulty there is lowest.
  * A guided quest gives the basic gear (a weapon, clothing, a shield or spell tome, food) through steps
    rather than a chest.
  * It ends by pointing you to the nearest town.
* **Questlines and side stories:**
  * At least three multi-step side questlines with real stories and choices.
  * One dedicated side-story mission arc.
  * Randomized "unique challenge" radiant quests (timed, stealth-only, no-magic, escort, and so on), with gold
    rewards shared with the party.
* **Journal** (J): quests, misc objectives, factions, party, reputation and stats tabs. It exposes contract 7.
* **More lore:** books and notes placed in locations.

### L: Magic, the College, dragons and shouts (`magic/`, plus the Dragonborn and College questlines in `quest/`)
* **Magic colleges:**
  * A College of Winterhold-style questline per school or set that unlocks spell sets: learning advanced
    tomes requires college progress.
  * Destruction, Restoration, Alteration, Conjuration and Illusion each get a short storyline.
* **Dual-casting:** both hands with the same spell gives a stronger effect (×2.2 power at 2.8× cost with the
  Dual Casting perk) and a visible two-handed animation and particles. Spells in either hand cast with
  LMB/RMB (contract 7, the key plan).
* **Voice slot (Z):** shouts and powers (racial, Standing Stone) are equipped into one voice slot from the
  magic menu, with cooldown UI.
* **Dragons:**
  * Almost none early on. The dragon rate scales with player level and Dragonborn quest progress.
  * The first dragon is a scripted questline encounter.
* **Dragonborn questline:** a full arc that introduces word walls, absorbing dragon souls and shouting, with
  custom UI for learning words, unlocking them with souls, and dragon-soul absorption.
* **Movement abilities:** quick-boost shouts and abilities such as Whirlwind Sprint. Work with D's roll.
* **Magic menu** (P): Skyrim parity (schools, then spells, details and effects, favoriting) plus contract 7.

### M: Horses, animals and pets (`fauna/`)
* **Horses:**
  * Stables near cities sell horses of different breeds and stats (speed, stamina, health, jump).
  * H calls your horse. If it dies, it returns after a cooldown.
  * Nobody can ride a horse they don't own.
  * A faction-mate (factions compound) can ride behind you as a passenger without control.
  * Carriages between cities are a stretch goal.
* **Taming wild horses:**
  * Sneak up on a wild horse undetected; it flees if it notices you.
  * Throw a saddle or lasso, then mount before it bolts.
  * A balance minigame follows: the horse bucks and you counter-steer, with difficulty by breed.
  * Taming trains a skill: HUNTING, or ATHLETICS if D adds it.
  * Harder breeds are better.
* **Animals:** wild animals flee from players, except predators that hunt you and farm, owned or tamed
  animals.
* **Pets:** dogs and cats in towns.

### N: CI client smoke test (`tools/`, `.github/workflows/`)
* **Client boot test:**
  * Launch the real client on the CI runner under Xvfb with software OpenGL (Mesa llvmpipe) and the full
    pack. portablemc can install and launch Forge offline.
  * Join the CI test server (offline mode) or a quick-play singleplayer world.
* **Scripted autotest:** only when the JVM property `-Dskycraft.autotest=true` is set, a client-side script in
  `com.skycraft.client.autotest`:
  * waits for the world;
  * opens each major screen (creator, inventory, magic, map, journal, skills, barter if possible);
  * summons each Skycraft entity in view with commands (the player is op);
  * takes screenshots;
  * then quits.
* **Outputs:**
  * Fail on crash or a missing renderer.
  * List texture and model errors from the client log.
  * Print every screenshot as a downscaled JPEG in base64 between markers at the end of the job log, so the
    lead can view them with `get_job_logs`.
