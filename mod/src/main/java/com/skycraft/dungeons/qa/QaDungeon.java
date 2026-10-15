package com.skycraft.dungeons.qa;

import com.skycraft.dungeons.puzzle.ClawKeyholeBlock;
import com.skycraft.dungeons.puzzle.DragonClawItem;
import com.skycraft.dungeons.puzzle.PuzzleLeverBlock;
import com.skycraft.dungeons.puzzle.Symbol;
import com.skycraft.dungeons.puzzle.SymbolStoneBlock;
import com.skycraft.dungeons.world.DormantMobs;
import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.Layout;
import com.skycraft.dungeons.world.Painter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One dungeon under analysis: its pieces painted into a {@link QaLevel}, its walk graph, and every number the
 * report wants. {@link #run} plays the dungeon like a careful bot (walks in from outside, takes the claw from the
 * dead adventurer's pack, turns the glyph stones to match the tablets, pulls the levers) and records what blocks it.
 */
public final class QaDungeon {
    // ------------------------------------------------------------------ inputs
    public final QaSample sample;
    public final List<StructurePiece> pieces;
    public final QaLevel lvl;
    /** Set pieces the builders announced, by piece box. */
    public final Map<BoundingBox, Set<String>> features;
    public final int surfaceY;

    // ------------------------------------------------------------------ results
    public final Map<String, Object> m = new LinkedHashMap<>();
    public final Set<String> blockers = new LinkedHashSet<>();
    public final Set<String> warnings = new LinkedHashSet<>();
    /** Layout signature (normalised room cells and links) and content signature (room purposes), for similarity. */
    public final Set<String> layoutSig = new HashSet<>();
    public final Set<String> contentSig = new HashSet<>();

    private QaGraph g;
    private final List<RoomInfo> rooms = new ArrayList<>();
    private final List<Mobile> mobs = new ArrayList<>();

    static final class RoomInfo {
        DungeonPiece piece;
        BoundingBox box;
        int idx;
        int role;
        boolean reached, onPath;
        int mobs, dormant;
        double threat;
        double lootEv;
        int chests, notes, traps, mechanisms;
        Set<String> features = new LinkedHashSet<>();
        final List<Integer> links = new ArrayList<>();
    }

    record Mobile(String type, BlockPos pos, double threat, boolean dormant, String name, boolean boss, boolean elite) {
    }

    QaDungeon(QaSample sample, List<StructurePiece> pieces, QaLevel lvl, Map<BoundingBox, Set<String>> features, int surfaceY) {
        this.sample = sample;
        this.pieces = pieces;
        this.lvl = lvl;
        this.features = features;
        this.surfaceY = surfaceY;
    }

    // ------------------------------------------------------------------ analysis

    void run(QaLoot loot) {
        long t0 = System.nanoTime();
        ServerLevel level = lvl.real;
        // rooms
        for (StructurePiece sp : pieces) {
            if (sp instanceof DungeonPiece dp && dp.kind == DungeonPiece.Kind.ROOM) {
                RoomInfo r = new RoomInfo();
                r.piece = dp;
                r.box = dp.getBoundingBox();
                r.idx = rooms.size();
                r.role = dp.role;
                Set<String> f = features.get(r.box);
                if (f != null) r.features.addAll(f);
                rooms.add(r);
            }
        }
        linkRooms();
        // entities
        for (Entity e : lvl.spawned) {
            if (e instanceof Mob mob && mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
                double hp = mob.getAttributeBaseValue(Attributes.MAX_HEALTH);
                double dmg = mob.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
                double threat = hp * (1 + dmg / 3.0);
                String name = mob.hasCustomName() ? mob.getCustomName().getString() : "";
                mobs.add(new Mobile(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString(), mob.blockPosition(), threat,
                        DormantMobs.isDormant(mob), name, mob.getPersistentData().getBoolean(com.skycraft.dungeons.DungeonTelemetry.BOSS_TAG),
                        mob.getPersistentData().getBoolean("skycraft_dungeon_elite")));
            }
        }
        for (Mobile mo : mobs) {
            RoomInfo r = roomAt(mo.pos.getX(), mo.pos.getY(), mo.pos.getZ());
            if (r == null) continue;
            r.mobs++;
            if (mo.dormant) r.dormant++;
            r.threat += mo.threat;
        }

        // blocks of interest
        List<BlockPos> containers = new ArrayList<>();
        int pressurePlates = 0, dispensers = 0, tripwires = 0, wordWalls = 0, lecterns = 0;
        for (Map.Entry<BlockPos, BlockEntity> e : lvl.blockEntities().entrySet()) {
            BlockPos pos = e.getKey();
            BlockEntity be = e.getValue();
            if (be instanceof LecternBlockEntity lec && (lec.hasBook() || !lec.getBook().isEmpty())) {
                lecterns++;
                RoomInfo r = roomAt(pos.getX(), pos.getY(), pos.getZ());
                if (r != null) r.notes++;
            }
            if (be instanceof Container) containers.add(pos);
        }
        BoundingBox a = lvl.area;
        List<BlockPos> plates = new ArrayList<>();
        for (StructurePiece sp : pieces) {
            BoundingBox b = sp.getBoundingBox();
            for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++) for (int y = b.minY(); y <= b.maxY(); y++) {
                if (!lvl.painted(x, y, z)) continue;
                BlockState s = lvl.get(x, y, z);
                if (s.getBlock() instanceof BasePressurePlateBlock) {
                    pressurePlates++;
                    plates.add(new BlockPos(x, y, z));
                } else if (s.getBlock() instanceof DispenserBlock) dispensers++;
                else if (s.getBlock() instanceof TripWireHookBlock) tripwires++;
                else if (BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().equals("word_wall")) wordWalls++;
            }
        }

        // ---- the bot: walk in, gather keys, work the mechanisms, repeat
        g = new QaGraph(lvl);
        boolean[] outside = outsideNodes();
        int[] seeds = seeds(outside);
        if (seeds.length == 0) blockers.add("no outside ground to start from");
        Set<BlockPos> inventoryClaws = new HashSet<>();
        Set<String> clawsHeld = new HashSet<>();
        Set<BlockPos> used = new HashSet<>();
        int mechanismsUsed = 0;
        float[] dist = g.distances(seeds, 12f, null);
        for (int round = 0; round < 12; round++) {
            boolean changed = false;
            // keys lying in reachable containers (the claw in the adventurer's pack)
            for (BlockPos pos : containers) {
                if (!reachable(dist, pos, 4.5)) continue;
                for (ItemStack st : items(lvl.getBlockEntity(pos))) {
                    if (st.getItem() instanceof DragonClawItem claw && inventoryClaws.add(pos)) clawsHeld.add(claw.claw.id());
                }
            }
            // mechanisms in reach
            for (BlockPos pos : mechanismPositions()) {
                if (used.contains(pos) || !reachable(dist, pos, 4.5)) continue;
                // the boss chamber's back door is opened after the way in has been measured
                if (inBossRoom(pos)) continue;
                BlockState s = lvl.getBlockState(pos);
                if (s.getBlock() instanceof ClawKeyholeBlock) {
                    String want = ClawKeyholeBlock.claw(s).id();
                    if (!clawsHeld.contains(want)) continue;
                    Direction facing = s.getValue(ClawKeyholeBlock.FACING);
                    for (int i = 0; i < 3; i++) {
                        BlockPos ring = ClawKeyholeBlock.ring(pos, facing, i);
                        BlockState rs = lvl.getBlockState(ring);
                        if (rs.getBlock() instanceof SymbolStoneBlock b && b.rotating() && reachable(dist, ring, 5.0)) {
                            lvl.put(ring, rs.setValue(SymbolStoneBlock.SYMBOL, com.skycraft.dungeons.puzzle.Claw.byIndex(s.getValue(ClawKeyholeBlock.CLAW)).symbol(i).ordinal()));
                        }
                    }
                    if (!ClawKeyholeBlock.ringsMatch(lvl, pos, s)) {
                        blockers.add("claw door rings cannot be set at " + pos.toShortString());
                        used.add(pos);
                        continue;
                    }
                    for (BlockPos p : ClawKeyholeBlock.doorBlocks(lvl, pos, facing)) lvl.put(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    used.add(pos);
                    mechanismsUsed++;
                    changed = true;
                } else if (s.getBlock() instanceof PuzzleLeverBlock) {
                    Direction inward = s.getValue(LeverBlock.FACING);
                    for (Map.Entry<BlockPos, Symbol> e : PuzzleLeverBlock.answer(lvl, pos, inward).entrySet()) {
                        BlockState ps = lvl.getBlockState(e.getKey());
                        if (ps.getBlock() instanceof SymbolStoneBlock b && b.rotating() && reachable(dist, e.getKey(), 5.0)) {
                            lvl.put(e.getKey(), ps.setValue(SymbolStoneBlock.SYMBOL, e.getValue().ordinal()));
                        }
                    }
                    if (!PuzzleLeverBlock.solved(lvl, pos, inward)) {
                        blockers.add("glyph puzzle cannot be solved at " + pos.toShortString());
                        used.add(pos);
                        continue;
                    }
                    if (pull(pos, s)) changed = true;
                    used.add(pos);
                    mechanismsUsed++;
                } else if (s.getBlock() instanceof LeverBlock) {
                    if (pull(pos, s)) changed = true;
                    used.add(pos);
                    mechanismsUsed++;
                }
            }
            if (!changed) break;
            g.rebuild();
            outside = outsideNodes();
            seeds = seeds(outside);
            dist = g.distances(seeds, 12f, null);
        }
        m.put("mechanisms_used", mechanismsUsed);

        // ---- reachability
        Map<Integer, Integer> nodeRoom = new HashMap<>();
        for (int n = 0; n < g.count; n++) {
            RoomInfo r = roomAt(g.nx[n], g.feetY(n), g.nz[n]);
            if (r != null) nodeRoom.put(n, r.idx);
        }
        int insideNodes = 0, insideReached = 0, dark = 0, litSum = 0, dim = 0;
        for (int n = 0; n < g.count; n++) {
            if (outside[n] || !insidePiece(n)) continue;
            insideNodes++;
            if (Float.isInfinite(dist[n])) continue;
            insideReached++;
            Integer ri = nodeRoom.get(n);
            if (ri != null) rooms.get(ri).reached = true;
            int l = g.nodeLight(n);
            litSum += l;
            if (l == 0) dark++;
            if (l < 4) dim++;
        }
        RoomInfo boss = null, start = null;
        for (RoomInfo r : rooms) {
            if (r.role == DungeonPiece.ROLE_BOSS) boss = r;
            if (r.role == DungeonPiece.ROLE_START) start = r;
        }
        int reachedRooms = 0;
        for (RoomInfo r : rooms) if (r.reached) reachedRooms++;
        m.put("rooms", rooms.size());
        m.put("rooms_reached", reachedRooms);
        if (rooms.stream().anyMatch(r -> r.role == DungeonPiece.ROLE_START && !r.reached)) {
            // how far down the entrance tunnel the bot got
            int low = -1;
            for (int n = 0; n < g.count; n++) {
                if (Float.isInfinite(dist[n]) || !"tunnel".equals(pieceAt(n))) continue;
                if (low < 0 || g.ns[n] < g.ns[low]) low = n;
            }
            warnings.add(low < 0 ? "entrance tunnel never reached" : "entrance tunnel reached down to " + g.nx[low] + " " + g.feetY(low) + " " + g.nz[low]);
        }
        for (RoomInfo r : rooms) if (!r.reached) blockers.add("room " + r.idx + " (" + roleName(r.role) + ") unreachable at "
                + r.box.getCenter().toShortString() + stuckNear(dist, r.box.getCenter()));

        // ---- critical path: outside to the boss room
        int[] prev = new int[g.count];
        float[] pathDist = g.distances(seeds, 12f, prev);
        int bossNode = -1;
        if (boss != null) {
            float best = Float.POSITIVE_INFINITY;
            for (Map.Entry<Integer, Integer> e : nodeRoom.entrySet()) {
                if (e.getValue() == boss.idx && pathDist[e.getKey()] < best) {
                    best = pathDist[e.getKey()];
                    bossNode = e.getKey();
                }
            }
            if (bossNode < 0) {
                // a dangerous route?
                float[] risky = g.distances(seeds, QaGraph.LETHAL_FALL, null);
                boolean riskyOk = false;
                for (Map.Entry<Integer, Integer> e : nodeRoom.entrySet()) if (e.getValue() == boss.idx && !Float.isInfinite(risky[e.getKey()])) riskyOk = true;
                blockers.add(riskyOk ? "boss room only reachable by a damaging drop" : "boss room unreachable");
            }
        } else if (sample.underground()) {
            blockers.add("no boss room");
        }
        List<Integer> path = new ArrayList<>();
        if (bossNode >= 0) {
            for (int n = bossNode; n >= 0; n = prev[n]) path.add(0, n);
        }
        double pathLen = bossNode >= 0 ? pathDist[bossNode] : -1;
        int pathBlocks = 0;
        double pathVertical = 0;
        int pathDark = 0, pathHazard = 0;
        List<Integer> pathRooms = new ArrayList<>();
        Set<Long> pathFeet = new HashSet<>();
        for (int i = 0; i < path.size(); i++) {
            int n = path.get(i);
            if (i > 0) {
                int p = path.get(i - 1);
                pathBlocks += (g.nx[n] != g.nx[p] || g.nz[n] != g.nz[p]) ? 1 : 0;
                pathVertical += Math.abs(g.ns[n] - g.ns[p]);
            }
            if (g.nodeLight(n) == 0 && insidePiece(n)) pathDark++;
            if ((g.flags[n] & QaGraph.HAZARD) != 0) pathHazard++;
            pathFeet.add(BlockPos.asLong(g.nx[n], g.feetY(n), g.nz[n]));
            Integer ri = nodeRoom.get(n);
            if (ri != null && (pathRooms.isEmpty() || !pathRooms.get(pathRooms.size() - 1).equals(ri))) pathRooms.add(ri);
        }
        for (int ri : pathRooms) rooms.get(ri).onPath = true;
        m.put("path_blocks", pathBlocks);
        m.put("path_cost", round1(pathLen));
        m.put("path_vertical", round1(pathVertical));
        // walking 4.3 b/s, plus climbing, swimming, cobwebs and mechanisms
        m.put("walk_seconds", bossNode >= 0 ? round1(pathLen / 4.3 + mechanismsUsed * 2.5) : -1);
        m.put("path_rooms", pathRooms.size());
        m.put("path_dark_pct", path.isEmpty() ? -1 : round1(100.0 * pathDark / path.size()));
        m.put("path_hazard_steps", pathHazard);

        // ---- the boss is down: open the chamber's back door (the loop-back exit) and look again
        boolean opened = false;
        for (BlockPos pos : mechanismPositions()) {
            BlockState ls = lvl.getBlockState(pos);
            if (inBossRoom(pos) && ls.getBlock() instanceof LeverBlock && !used.contains(pos) && reachable(dist, pos, 4.5)) {
                opened |= pull(pos, ls);
                used.add(pos);
            }
        }
        if (opened) {
            g.rebuild();
            outside = outsideNodes();
            seeds = seeds(outside);
            dist = g.distances(seeds, 12f, null);
            nodeRoom.clear();
            for (int n = 0; n < g.count; n++) {
                RoomInfo r = roomAt(g.nx[n], g.feetY(n), g.nz[n]);
                if (r != null) nodeRoom.put(n, r.idx);
            }
            if (bossNode >= 0) {
                int bn = -1;
                for (Map.Entry<Integer, Integer> e : nodeRoom.entrySet()) if (boss != null && e.getValue() == boss.idx && !Float.isInfinite(dist[e.getKey()])) bn = e.getKey();
                bossNode = bn;
            }
        }

        // ---- return trip and pockets
        boolean[] canExit = g.canReach(outside, 12f);
        int pockets = 0;
        Set<Integer> pocketRooms = new LinkedHashSet<>();
        for (int n = 0; n < g.count; n++) {
            if (Float.isInfinite(dist[n]) || canExit[n]) continue;
            // the open land around the dungeon (sea cliffs, gullies) is the world's business, not the dungeon's
            if (!insideBuried(n)) continue;
            pockets++;
            Integer ri = nodeRoom.get(n);
            if (ri != null) pocketRooms.add(ri);
        }
        m.put("trap_pockets", pockets);
        if (pockets > 0) {
            String where = (pocketRooms.isEmpty() ? "corridors" : "rooms " + pocketRooms) + oneWay(dist, canExit);
            (pockets >= 6 ? blockers : warnings).add(pockets + " reachable spots with no way back out (" + where + ")");
        }
        if (bossNode >= 0 && !canExit[bossNode]) blockers.add("no way out of the boss room");
        // loop-back: the shortest way out from the boss room, and whether it avoids retracing the way in (it never
        // passes back through the entrance hall) or is much shorter than it
        double back = -1;
        boolean fresh = false;
        if (bossNode >= 0) {
            List<Integer> bn = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : nodeRoom.entrySet()) if (boss != null && e.getValue() == boss.idx) bn.add(e.getKey());
            int[] prevOut = new int[g.count];
            float[] fromBoss = g.distances(bn.stream().mapToInt(Integer::intValue).toArray(), 12f, prevOut);
            double bestOut = Double.POSITIVE_INFINITY;
            int exitNode = -1;
            for (int n = 0; n < g.count; n++) if (outside[n] && fromBoss[n] < bestOut) {
                bestOut = fromBoss[n];
                exitNode = n;
            }
            back = Double.isInfinite(bestOut) ? -1 : bestOut;
            if (exitNode >= 0) {
                fresh = true;
                for (int n = exitNode; n >= 0; n = prevOut[n]) {
                    Integer ri = nodeRoom.get(n);
                    if (ri != null && start != null && ri == start.idx) fresh = false;
                }
            }
        }
        m.put("exit_cost", round1(back));
        boolean loopBack = back >= 0 && pathLen > 0 && (fresh || back <= 0.6 * pathLen);
        m.put("loop_back", loopBack ? 1 : 0);
        m.put("exit_ratio", back >= 0 && pathLen > 0 ? round2(back / pathLen) : -1);

        // ---- room roles
        Map<BlockPos, String> tables = new HashMap<>();
        double lootTotal = 0, lootPath = 0, lootSide = 0, lootBoss = 0;
        int lootChests = 0, notes = lecterns;
        for (BlockPos pos : containers) {
            BlockEntity be = lvl.getBlockEntity(pos);
            String table = lootTable(be);
            RoomInfo r = roomAt(pos.getX(), pos.getY(), pos.getZ());
            for (ItemStack st : items(be)) {
                if (st.is(Items.WRITTEN_BOOK) || st.is(Items.WRITABLE_BOOK) || com.skycraft.lore.LoreBookItem.bookOf(st) != null) {
                    notes++;
                    if (r != null) r.notes++;
                }
            }
            if (table == null) continue;
            if (table.contains("lore") || table.contains("note") || table.contains("skill_book")) {
                notes++;
                if (r != null) r.notes++;
            }
            boolean reach = reachable(dist, pos, 4.5);
            if (!reach) {
                warnings.add("unreachable container " + table + " at " + pos.toShortString());
                continue;
            }
            double ev = loot.expected(level, new ResourceLocation(table), pos);
            tables.put(pos, table);
            lootTotal += ev;
            lootChests++;
            if (r != null) {
                r.lootEv += ev;
                r.chests++;
                if (r.role == DungeonPiece.ROLE_BOSS && table.endsWith("dungeon_boss")) lootBoss += ev;
                if (r.onPath) lootPath += ev;
                else lootSide += ev;
            }
        }
        for (Entity e : lvl.spawned) {
            if (e instanceof MinecartChest cart) {
                CompoundTag tag = new CompoundTag();
                cart.saveWithoutId(tag);
                if (tag.contains("LootTable")) {
                    double ev = loot.expected(level, new ResourceLocation(tag.getString("LootTable")), cart.blockPosition());
                    lootTotal += ev;
                    lootChests++;
                }
            }
        }
        for (BlockPos pp : plates) {
            RoomInfo r = roomAt(pp.getX(), pp.getY(), pp.getZ());
            if (r != null) r.traps++;
        }
        for (BlockPos mp : mechanismPositions()) {
            RoomInfo r = roomAt(mp.getX(), mp.getY(), mp.getZ());
            if (r != null) r.mechanisms++;
        }
        int combat = 0, puzzle = 0, treasure = 0, story = 0, bossRooms = 0, setPieces = 0;
        Set<String> allFeatures = new LinkedHashSet<>();
        for (Set<String> fs : features.values()) allFeatures.addAll(fs);
        for (RoomInfo r : rooms) {
            if (r.role == DungeonPiece.ROLE_BOSS) bossRooms++;
            if (r.mobs > 0) combat++;
            if ((r.mechanisms > 0 && r.role != DungeonPiece.ROLE_BOSS) || r.role == DungeonPiece.ROLE_PUZZLE) puzzle++;
            if (r.chests > 0 && r.lootEv > 0) treasure++;
            if (r.notes > 0 || r.features.stream().anyMatch(f -> f.startsWith("story"))) story++;
            if (r.features.stream().anyMatch(f -> f.startsWith("setpiece"))) setPieces++;
        }
        m.put("combat_rooms", combat);
        m.put("puzzle_rooms", puzzle);
        m.put("treasure_rooms", treasure);
        m.put("story_rooms", story);
        m.put("boss_rooms", bossRooms);
        m.put("setpiece_rooms", setPieces);
        m.put("set_pieces", String.join("|", allFeatures.stream().filter(f -> f.startsWith("setpiece")).map(f -> f.substring(9)).distinct().toList()));

        // dead ends and side branches
        int deadEnds = 0, sideRooms = 0, sideReward = 0;
        for (RoomInfo r : rooms) {
            if (r.role != DungeonPiece.ROLE_START && r.role != DungeonPiece.ROLE_BOSS && r.links.size() == 1) deadEnds++;
            if (!r.onPath && r.role != DungeonPiece.ROLE_START) {
                sideRooms++;
                if (r.lootEv >= 30) sideReward++;
            }
        }
        m.put("dead_ends", deadEnds);
        m.put("side_rooms", sideRooms);
        m.put("side_rewards", sideReward);

        // enemies and the ramp to the boss
        int enemies = mobs.size(), dormant = 0;
        double maxThreat = 0;
        Mobile strongest = null;
        for (Mobile mo : mobs) {
            if (mo.dormant) dormant++;
            if (mo.threat > maxThreat) {
                maxThreat = mo.threat;
                strongest = mo;
            }
        }
        m.put("enemies", enemies);
        m.put("enemies_dormant", dormant);
        int pathEnemies = 0;
        List<Double> curve = new ArrayList<>();
        for (int ri : pathRooms) {
            RoomInfo r = rooms.get(ri);
            pathEnemies += r.mobs;
            curve.add(r.threat);
        }
        m.put("enemies_on_path", pathEnemies);
        double bossThreat = boss != null ? boss.threat : 0;
        double otherMax = 0;
        for (RoomInfo r : rooms) if (r != boss) otherMax = Math.max(otherMax, r.threat);
        m.put("boss_threat", round1(bossThreat));
        m.put("boss_ratio", otherMax > 0 ? round1(bossThreat / otherMax) : (bossThreat > 0 ? 99 : 0));
        m.put("ramp", round2(spearman(curve)));
        m.put("threat_curve", String.join(">", curve.stream().map(t -> String.valueOf(Math.round(t))).toList()));
        boolean bossPresent = boss != null && boss.mobs > 0;
        int tagged = 0, elites = 0;
        for (Mobile mo : mobs) {
            if (mo.boss) tagged++;
            if (mo.elite) elites++;
        }
        m.put("boss_tagged", tagged);
        m.put("elites", elites);
        boolean strongestInBoss = strongest != null && boss != null && roomAt(strongest.pos.getX(), strongest.pos.getY(), strongest.pos.getZ()) == boss;
        m.put("boss_present", bossPresent ? 1 : 0);
        m.put("boss_strongest", strongestInBoss ? 1 : 0);
        m.put("boss", strongest != null ? (strongest.name.isEmpty() ? strongest.type : strongest.type + " \"" + strongest.name + "\"") : "");
        if (sample.underground() && !bossPresent) blockers.add("boss room has no boss");
        if (boss != null) {
            int area = (boss.box.getXSpan() - 2) * (boss.box.getZSpan() - 2);
            m.put("boss_arena", area);
        }

        // traps
        int pathTraps = 0;
        for (BlockPos pp : plates) if (pathFeet.contains(pp.asLong())) pathTraps++;
        long trapFeatures = allFeatures.stream().filter(f -> f.startsWith("trap")).count();
        m.put("traps", pressurePlates + tripwires + (int) trapFeatures);
        m.put("trap_kinds", String.join("|", allFeatures.stream().filter(f -> f.startsWith("trap")).map(f -> f.substring(5)).distinct().toList()));
        m.put("traps_on_path", pathTraps);
        m.put("dispensers", dispensers);
        int darkTraps = 0;
        for (BlockPos pp : plates) if (g.lightAt(pp.getX(), pp.getY(), pp.getZ()) < 3) darkTraps++;
        m.put("traps_unlit", darkTraps);

        // light
        m.put("walkable", insideNodes);
        m.put("walkable_reached", insideReached);
        m.put("dark_pct", insideReached > 0 ? round1(100.0 * dark / insideReached) : -1);
        m.put("dim_pct", insideReached > 0 ? round1(100.0 * dim / insideReached) : -1);
        m.put("mean_light", insideReached > 0 ? round1((double) litSum / insideReached) : -1);

        // loot
        com.skycraft.tamriel.Region region = com.skycraft.tamriel.TamrielMap.region(sample.x(), sample.z());
        m.put("region", region.id());
        m.put("region_levels", region.minLevel() + "-" + region.maxLevel());
        m.put("loot_containers", lootChests);
        m.put("loot_total", Math.round(lootTotal));
        m.put("loot_path", Math.round(lootPath));
        m.put("loot_side", Math.round(lootSide));
        m.put("loot_boss", Math.round(lootBoss));

        // capstone & lore
        boolean bossChest = false;
        for (Map.Entry<BlockPos, String> e : tables.entrySet()) {
            RoomInfo r = roomAt(e.getKey().getX(), e.getKey().getY(), e.getKey().getZ());
            if (r != null && r.role == DungeonPiece.ROLE_BOSS && e.getValue().endsWith("dungeon_boss")) bossChest = true;
        }
        m.put("word_walls", wordWalls);
        m.put("boss_chest", bossChest ? 1 : 0);
        boolean capstone = bossChest || wordWalls > 0 || allFeatures.stream().anyMatch(f -> f.startsWith("capstone"));
        m.put("capstone", capstone ? 1 : 0);
        if (sample.underground() && !capstone) warnings.add("no capstone (word wall, boss chest or relic)");
        m.put("notes", notes);

        // cycles: corridors beyond a tree (loops, not counting barred windows or the back exit)
        int corridors = 0;
        for (StructurePiece sp : pieces) {
            if (sp instanceof DungeonPiece dp && dp.kind == DungeonPiece.Kind.CORRIDOR && !dp.has(DungeonPiece.FLAG_BARRED)) corridors++;
        }
        boolean hasShaft = pieces.stream().anyMatch(sp -> sp instanceof DungeonPiece dp && dp.kind == DungeonPiece.Kind.SHAFT);
        m.put("cycles", Math.max(0, corridors - (hasShaft ? 1 : 0) - (rooms.size() - 1)));
        m.put("foreshadow", allFeatures.contains("foreshadow:window") ? 1 : 0);
        m.put("ambush", allFeatures.contains("beat:ambush") ? 1 : 0);
        int unrewarded = 0;
        for (RoomInfo r : rooms) {
            if (r.role == DungeonPiece.ROLE_NORMAL && r.links.size() == 1 && r.lootEv < 30 && r.notes == 0) unrewarded++;
        }
        m.put("dead_ends_empty", unrewarded);

        // vertical variety
        int minF = Integer.MAX_VALUE, maxF = Integer.MIN_VALUE;
        Set<Integer> floors = new HashSet<>();
        for (RoomInfo r : rooms) {
            minF = Math.min(minF, r.piece.floor);
            maxF = Math.max(maxF, r.piece.floor);
            floors.add(r.piece.floor);
        }
        m.put("floor_range", rooms.isEmpty() ? 0 : maxF - minF);
        m.put("floor_levels", floors.size());
        int exposed = 0;
        for (RoomInfo r : rooms) {
            int cx = (r.box.minX() + r.box.maxX()) / 2, cz = (r.box.minZ() + r.box.maxZ()) / 2;
            if (r.box.maxY() > lvl.groundTop(cx, cz)) exposed++;
        }
        m.put("rooms_exposed", exposed);
        // the niche (MAP-Elites style bins): path length x verticality x puzzle
        int pr = pathRooms.size(), fr = rooms.isEmpty() ? 0 : maxF - minF;
        m.put("niche", (pr <= 4 ? "short" : pr <= 6 ? "mid" : "long") + "/" + (fr < 4 ? "flat" : fr <= 8 ? "rolling" : "steep")
                + "/" + (puzzle > 0 ? "puzzle" : "fight"));
        if (exposed > 0) warnings.add(exposed + " rooms rise above the ground");

        // design checklist (Skyrim dungeon principles)
        boolean medium = rooms.size() >= 5;
        boolean vista = allFeatures.contains("setpiece:vista") || allFeatures.contains("vista");
        boolean story1 = story > 0 || notes > 0;
        boolean trap1 = pressurePlates + tripwires + trapFeatures > 0;
        boolean side1 = sideReward > 0;
        boolean arena = boss != null && (boss.box.getXSpan() - 2) * (boss.box.getZSpan() - 2) >= 100;
        boolean setp = allFeatures.stream().anyMatch(f -> f.startsWith("setpiece"));
        int score = (vista ? 1 : 0) + (setp ? 1 : 0) + (story1 ? 1 : 0) + (trap1 ? 1 : 0) + (side1 ? 1 : 0) + (arena ? 1 : 0)
                + (capstone ? 1 : 0) + (loopBack ? 1 : 0) + (bossPresent ? 1 : 0);
        m.put("design_score", score);
        StringBuilder miss = new StringBuilder();
        if (medium) {
            if (!vista) miss.append("vista ");
            if (!setp) miss.append("setpiece ");
            if (!story1) miss.append("story ");
            if (!trap1) miss.append("trap ");
            if (!side1) miss.append("side-reward ");
            if (!arena) miss.append("arena ");
            if (!capstone) miss.append("capstone ");
            if (!loopBack) miss.append("loop-back ");
        }
        m.put("design_missing", miss.toString().trim());

        // signatures for similarity
        signatures(start);
        m.put("analysis_ms", (System.nanoTime() - t0) / 1_000_000);
    }

    // ------------------------------------------------------------------ helpers

    /** Where the reachable part ends closest to a target: the stuck point, for unreachable rooms. */
    private String stuckNear(float[] dist, BlockPos target) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int n = 0; n < g.count; n++) {
            if (Float.isInfinite(dist[n])) continue;
            double dx = g.nx[n] - target.getX(), dy = g.ns[n] - target.getY(), dz = g.nz[n] - target.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best < 0 ? " (nothing reached)" : " (stuck at " + g.nx[best] + " " + g.feetY(best) + " " + g.nz[best] + " in " + pieceAt(best) + ")";
    }

    /** The first one-way moves into places with no way back: where a player falls in. */
    private String oneWay(float[] dist, boolean[] canExit) {
        List<String> out = new ArrayList<>();
        for (int a = 0; a < g.count && out.size() < 3; a++) {
            if (Float.isInfinite(dist[a]) || !canExit[a]) continue;
            final int from = a;
            g.edges(a, (to, cost, dmg) -> {
                if (out.size() < 3 && !canExit[to] && !Float.isInfinite(dist[to]) && !"terrain".equals(pieceAt(to))) {
                    out.add(g.nx[from] + " " + g.feetY(from) + " " + g.nz[from] + " -> " + g.nx[to] + " " + g.feetY(to) + " " + g.nz[to]
                            + " in " + pieceAt(to));
                }
            });
        }
        return out.isEmpty() ? "" : "; one-way at " + String.join(", ", out);
    }

    private String pieceAt(int n) {
        int x = g.nx[n], y = g.feetY(n), z = g.nz[n];
        for (StructurePiece sp : pieces) {
            if (sp instanceof DungeonPiece dp && dp.getBoundingBox().isInside(x, y, z)) return dp.kind.name().toLowerCase(java.util.Locale.ROOT);
        }
        return "terrain";
    }

    private boolean pull(BlockPos pos, BlockState s) {
        lvl.put(pos, s.setValue(LeverBlock.POWERED, true));
        Direction attachedTo = switch (s.getValue(LeverBlock.FACE)) {
            case FLOOR -> Direction.DOWN;
            case CEILING -> Direction.UP;
            default -> s.getValue(LeverBlock.FACING).getOpposite();
        };
        Set<BlockPos> powered = new HashSet<>();
        for (Direction d : Direction.values()) powered.add(pos.relative(d));
        BlockPos attached = pos.relative(attachedTo);
        if (lvl.getBlockState(attached).isRedstoneConductor(lvl, attached)) {
            for (Direction d : Direction.values()) powered.add(attached.relative(d));
        }
        boolean opened = false;
        for (BlockPos p : powered) {
            BlockState d = lvl.getBlockState(p);
            if (d.getBlock() instanceof DoorBlock && !d.getValue(DoorBlock.OPEN)) {
                BlockPos other = d.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? p.above() : p.below();
                lvl.put(p, d.setValue(DoorBlock.OPEN, true));
                BlockState o = lvl.getBlockState(other);
                if (o.getBlock() instanceof DoorBlock) lvl.put(other, o.setValue(DoorBlock.OPEN, true));
                opened = true;
            }
        }
        return opened;
    }

    private List<BlockPos> mechanismCache;

    private List<BlockPos> mechanismPositions() {
        if (mechanismCache != null) return mechanismCache;
        List<BlockPos> out = new ArrayList<>();
        for (StructurePiece sp : pieces) {
            BoundingBox b = sp.getBoundingBox();
            for (int x = b.minX(); x <= b.maxX(); x++) for (int z = b.minZ(); z <= b.maxZ(); z++) for (int y = b.minY(); y <= b.maxY(); y++) {
                BlockState s = lvl.get(x, y, z);
                if (s.getBlock() instanceof LeverBlock || s.getBlock() instanceof ClawKeyholeBlock) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!out.contains(p)) out.add(p);
                }
            }
        }
        mechanismCache = out;
        return out;
    }

    /** True if a reached node's eyes are within {@code reach} blocks of the block's centre. */
    private boolean reachable(float[] dist, BlockPos pos, double reach) {
        int r = (int) Math.ceil(reach);
        for (int x = pos.getX() - r; x <= pos.getX() + r; x++) {
            for (int z = pos.getZ() - r; z <= pos.getZ() + r; z++) {
                for (int n = g.first(x, z); n >= 0; n = g.next(n)) {
                    if (Float.isInfinite(dist[n])) continue;
                    double dx = x + 0.5 - (pos.getX() + 0.5), dy = g.ns[n] + 1.62 - (pos.getY() + 0.5), dz = z + 0.5 - (pos.getZ() + 0.5);
                    if (dx * dx + dy * dy + dz * dz <= reach * reach && sees(x + 0.5, g.ns[n] + 1.62, z + 0.5, pos)) return true;
                }
            }
        }
        return false;
    }

    private boolean inBossRoom(BlockPos pos) {
        for (RoomInfo r : rooms) if (r.role == DungeonPiece.ROLE_BOSS && r.box.isInside(pos)) return true;
        return false;
    }

    /** True if nothing solid stands between the eye and the block (no pulling levers through doors or walls). */
    private boolean sees(double ex, double ey, double ez, BlockPos target) {
        double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
        double len = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
        int steps = (int) Math.ceil(len / 0.2);
        for (int i = 1; i < steps; i++) {
            double f = i / (double) steps;
            double px = ex + (tx - ex) * f, py = ey + (ty - ey) * f, pz = ez + (tz - ez) * f;
            int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            if (bx == target.getX() && by == target.getY() && bz == target.getZ()) return true;
            QaGraph.Props pr = g.propsAt(bx, by, bz);
            if (pr.body || pr.hi < 0) continue;
            double ly = py - by;
            if (ly >= pr.lo && ly <= pr.hi && pr.hi - pr.lo > 0.4) return false;
        }
        return true;
    }

    /** Nodes standing on the open surface: outside every buried piece, at or above the ground. */
    private boolean[] outsideNodes() {
        boolean[] out = new boolean[g.count];
        for (int n = 0; n < g.count; n++) {
            if (insideBuried(n)) continue;
            int top = lvl.groundTop(g.nx[n], g.nz[n]);
            if (g.ns[n] >= top + 0.5f && g.ns[n] >= surfaceY - 24) out[n] = true;
        }
        return out;
    }

    private int[] seeds(boolean[] outside) {
        List<Integer> s = new ArrayList<>();
        for (int n = 0; n < outside.length; n++) if (outside[n]) s.add(n);
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Inside a room, corridor or the entrance tunnel (not the portal or a surface site). */
    private boolean insideBuried(int n) {
        int x = g.nx[n], y = g.feetY(n), z = g.nz[n];
        for (StructurePiece sp : pieces) {
            if (!(sp instanceof DungeonPiece dp)) continue;
            if (dp.kind == DungeonPiece.Kind.ROOM || dp.kind == DungeonPiece.Kind.CORRIDOR
                    || (dp.kind == DungeonPiece.Kind.SHAFT && y < dp.aux - 1)) {
                if (dp.getBoundingBox().isInside(x, y, z)) return true;
            } else if (dp.kind == DungeonPiece.Kind.TUNNEL && y < dp.aux - 1 && dp.getBoundingBox().isInside(x, y, z)) {
                // the stairwell itself, not the ground over it
                if (Math.abs(dp.frameLat(x, z)) <= 1 && y <= dp.tunnelFloor(dp.frameT(x, z)) + 2) return true;
            }
        }
        return false;
    }

    private boolean insidePiece(int n) {
        return insideBuried(n);
    }

    private RoomInfo roomAt(int x, int y, int z) {
        for (RoomInfo r : rooms) if (r.box.isInside(x, y, z) || (r.box.isInside(x, y - 1, z) && y - 1 == r.box.minY())) return r;
        return null;
    }

    private void linkRooms() {
        for (RoomInfo a : rooms) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if ((a.piece.doors & (1 << d.get2DDataValue())) == 0) continue;
                int cx = (a.box.minX() + a.box.maxX()) / 2 + d.getStepX() * Layout.CELL;
                int cz = (a.box.minZ() + a.box.maxZ()) / 2 + d.getStepZ() * Layout.CELL;
                for (RoomInfo b : rooms) {
                    if (b == a) continue;
                    int bx = (b.box.minX() + b.box.maxX()) / 2, bz = (b.box.minZ() + b.box.maxZ()) / 2;
                    if (Math.abs(bx - cx) <= 1 && Math.abs(bz - cz) <= 1 && !a.links.contains(b.idx)) a.links.add(b.idx);
                }
            }
        }
    }

    private void signatures(RoomInfo start) {
        if (start == null) {
            for (RoomInfo r : rooms) contentSig.addAll(r.features);
            return;
        }
        int sx = (start.box.minX() + start.box.maxX()) / 2, sz = (start.box.minZ() + start.box.maxZ()) / 2;
        // rotate so the entrance is north of the start room
        Direction out = Direction.NORTH;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if ((start.piece.doors & (1 << d.get2DDataValue())) == 0) continue;
            int cx = sx + d.getStepX() * Layout.CELL, cz = sz + d.getStepZ() * Layout.CELL;
            boolean room = false;
            for (RoomInfo b : rooms) if (Math.abs((b.box.minX() + b.box.maxX()) / 2 - cx) <= 1 && Math.abs((b.box.minZ() + b.box.maxZ()) / 2 - cz) <= 1) room = true;
            if (!room) out = d;
        }
        Map<Integer, String> cell = new HashMap<>();
        for (RoomInfo r : rooms) {
            int dx = Math.round(((r.box.minX() + r.box.maxX()) / 2 - sx) / (float) Layout.CELL);
            int dz = Math.round(((r.box.minZ() + r.box.maxZ()) / 2 - sz) / (float) Layout.CELL);
            int[] rot = rotate(dx, dz, out);
            String c = rot[0] + "," + rot[1];
            cell.put(r.idx, c);
            layoutSig.add("c" + c + ":" + roleName(r.role));
            String purpose = r.features.isEmpty() ? "plain" : String.join("+", r.features.stream().filter(f -> !f.startsWith("trap")).sorted().toList());
            contentSig.add(c + "=" + purpose);
        }
        for (RoomInfo r : rooms) for (int l : r.links) if (l > r.idx) layoutSig.add("l" + cell.get(r.idx) + "-" + cell.get(l));
    }

    private static int[] rotate(int dx, int dz, Direction out) {
        return switch (out) {
            case SOUTH -> new int[]{-dx, -dz};
            case EAST -> new int[]{dz, -dx};
            case WEST -> new int[]{-dz, dx};
            default -> new int[]{dx, dz};
        };
    }

    static String roleName(int role) {
        return switch (role) {
            case DungeonPiece.ROLE_START -> "start";
            case DungeonPiece.ROLE_BOSS -> "boss";
            case DungeonPiece.ROLE_PUZZLE -> "puzzle";
            default -> "room";
        };
    }

    /** A container's items as saved (never unpacks its loot table, which needs a real level). */
    static List<ItemStack> items(BlockEntity be) {
        List<ItemStack> out = new ArrayList<>();
        if (be == null) return out;
        CompoundTag tag = be.saveWithoutMetadata();
        net.minecraft.nbt.ListTag list = tag.getList("Items", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) out.add(ItemStack.of(list.getCompound(i)));
        return out;
    }

    private static String lootTable(BlockEntity be) {
        if (!(be instanceof RandomizableContainerBlockEntity)) return null;
        CompoundTag tag = be.saveWithoutMetadata();
        return tag.contains("LootTable") ? tag.getString("LootTable") : null;
    }

    /** Spearman rank correlation of the curve against its index (+1: threat rises steadily to the end). */
    static double spearman(List<Double> v) {
        int n = v.size();
        if (n < 3) return 0;
        double[] rank = ranks(v);
        double mean = (n + 1) / 2.0, num = 0, d1 = 0, d2 = 0;
        for (int i = 0; i < n; i++) {
            double a = (i + 1) - mean, b = rank[i] - mean;
            num += a * b;
            d1 += a * a;
            d2 += b * b;
        }
        return d1 == 0 || d2 == 0 ? 0 : num / Math.sqrt(d1 * d2);
    }

    private static double[] ranks(List<Double> v) {
        int n = v.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(v.get(p), v.get(q)));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && v.get(idx[j + 1]).equals(v.get(idx[i]))) j++;
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) r[idx[k]] = avg;
            i = j + 1;
        }
        return r;
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** A spot to show this dungeon from (the boss room's entrance side), for screenshots. */
    public Vec3 bossView() {
        for (RoomInfo r : rooms) {
            if (r.role != DungeonPiece.ROLE_BOSS) continue;
            Direction e = r.piece.dir >= 0 ? Direction.from2DDataValue(r.piece.dir) : Direction.NORTH;
            int cx = (r.box.minX() + r.box.maxX()) / 2, cz = (r.box.minZ() + r.box.maxZ()) / 2;
            int half = e.getAxis() == Direction.Axis.X ? (r.box.getXSpan() / 2) : (r.box.getZSpan() / 2);
            return new Vec3(cx + e.getStepX() * (half - 1) + 0.5, r.piece.floor + 1, cz + e.getStepZ() * (half - 1) + 0.5);
        }
        return null;
    }

}
