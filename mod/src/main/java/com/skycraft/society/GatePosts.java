package com.skycraft.society;

import com.skycraft.creatures.GuardProfiles;
import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The permanent gate watch of a major settlement (owner, playtest 4, narrowed in playtest 5: see {@link GuardWatch}):
 * two guards at all times, one on each side of the main gate's passage, standing and facing outward, whatever the
 * hour. They are not residents (no bed, no schedule, no housing slot): a post is a (settlement, gate, side) key stored
 * on the guard, kept filled while the gate's chunk is entity-ticking and a player is within {@link #RANGE}; a guard
 * that dies is replaced after {@link #REPLACE_DELAY} ticks, and a second guard claiming the same post is discarded.
 * Minor settlements have one patrolling guard instead and everything smaller has none ({@link GuardWatch}).
 *
 * <p>The gates are the settlement's real ones: its recorded {@link AnchorType#GATE} anchors (gatehouses, road-end
 * signposts) and the places where the road plan joins it ({@link SettlementAnchors#gates(Place)}), main gate first;
 * the posts are at the main gate.</p>
 */
public final class GatePosts {
    /** Persistent-data key on a gate guard: {@code "<settlement>|<gate id>|<side>"}. */
    public static final String KEY = "skycraft_gate_post";
    /** A player this close (blocks) keeps the gates they can see staffed. */
    private static final double RANGE = 160;
    /** Ticks a vacated post stays empty before its replacement walks in (a killed guard is not back the same second). */
    static final long REPLACE_DELAY = 600;
    /** Blocks beside the passage centre a guard stands. */
    private static final int FLANK = 2;

    /** Game time of the last time each post was seen filled (so a death starts the replacement clock). */
    private static final Map<String, Long> LAST_FILLED = new HashMap<>();
    /** The map's gates of a place never change: computed once per settlement key. */
    private static final Map<String, List<int[]>> PREDICTED = new HashMap<>();

    private GatePosts() {}

    /** One gate: a stable id (its map coordinates) and the block a guard's flank is measured from. */
    private record Gate(String id, BlockPos pos) {}

    /**
     * The watch of every settlement near {@code player}, about every two seconds: two posted guards at a major
     * settlement's main gate, one patrol in a minor one, none elsewhere (and the guards an older save left in a
     * settlement that has no place for them are removed). Also answers a shout for the guard ({@link GuardWatch#shout}).
     */
    public static void tick(ServerLevel level, ServerPlayer player) {
        if (level.dimension() != Level.OVERWORLD || !com.skycraft.creatures.CreaturesConfig.GUARD_SPAWNING.get()) return;
        SettlementAnchors data = SettlementAnchors.get(level);
        GuardWatch.shout(level, player);
        GuardWatch.cullRoadPatrols(level, player);
        for (SettlementAnchors.Entry e : data.entries()) {
            if (e.homestead() || e.anchors().isEmpty()) continue;
            int margin = Math.max((int) RANGE, e.radius + 32);
            if (!e.contains(player.getBlockX(), player.getBlockZ(), margin)) continue;
            // the dragon has been: Helgen's gates are a ruin, its soldiers are the intro's to place
            if ("helgen".equals(e.key) && com.skycraft.quest.QuestConfig.RUINED_HELGEN.get()) continue;
            GuardWatch.Watch watch = GuardWatch.of(e);
            GuardWatch.keep(level, e, watch);
            if (watch != GuardWatch.Watch.MAJOR) continue;
            double r2 = (double) margin * margin;
            List<Gate> gates = gatesOf(level, e);
            for (int[] post : GuardWatch.posts(watch)) {
                if (post[0] >= gates.size()) continue;
                Gate gate = gates.get(post[0]);
                BlockPos g = gate.pos();
                if (player.distanceToSqr(g.getX() + 0.5, g.getY(), g.getZ() + 0.5) > r2) continue;
                if (!level.isLoaded(g) || !level.isPositionEntityTicking(g)) continue;
                keep(level, e, gate, post[1]);
            }
        }
    }

    /** The settlement's gates, main gate first: the map's road joins, matched to recorded anchors where there are some. */
    private static List<Gate> gatesOf(ServerLevel level, SettlementAnchors.Entry e) {
        List<Gate> out = new ArrayList<>();
        List<Anchor> recorded = e.anchors(AnchorType.GATE);
        List<int[]> predicted = PREDICTED.get(e.key);
        if (predicted == null) {
            predicted = new ArrayList<>();
            Place place = e.place().orElse(null);
            if (place != null) {
                try {
                    predicted.addAll(SettlementAnchors.gates(place));
                } catch (RuntimeException ex) {
                    // a place without a layout: only its recorded anchors
                }
            }
            PREDICTED.put(e.key, predicted);
        }
        for (int[] g : predicted) {
            BlockPos at = null;
            for (Anchor a : recorded) {
                long dx = a.pos().getX() - g[0], dz = a.pos().getZ() - g[1];
                if (dx * dx + dz * dz <= 14 * 14) {
                    at = a.pos();
                    break;
                }
            }
            if (at == null) {
                if (!level.hasChunk(g[0] >> 4, g[1] >> 4)) continue;
                at = NpcPlacement.safeSurfaceAt(level, g[0], g[1]);
            }
            out.add(new Gate(g[0] + "," + g[1], at));
        }
        for (Anchor a : recorded) {
            boolean listed = false;
            for (Gate g : out) {
                long dx = a.pos().getX() - g.pos().getX(), dz = a.pos().getZ() - g.pos().getZ();
                if (dx * dx + dz * dz <= 14 * 14) {
                    listed = true;
                    break;
                }
            }
            if (!listed) out.add(new Gate(a.pos().getX() + "," + a.pos().getZ(), a.pos()));
        }
        return out;
    }

    /** Outward direction of a gate: from the settlement centre through the gate, snapped to a compass axis. */
    static int[] outward(SettlementAnchors.Entry e, BlockPos gate) {
        int dx = gate.getX() - e.x, dz = gate.getZ() - e.z;
        if (Math.abs(dx) >= Math.abs(dz)) return new int[]{Integer.signum(dx) == 0 ? 1 : Integer.signum(dx), 0};
        return new int[]{0, Integer.signum(dz) == 0 ? 1 : Integer.signum(dz)};
    }

    /** Keeps one post ({@code side} 0 or 1 of the gate) filled. */
    private static void keep(ServerLevel level, SettlementAnchors.Entry e, Gate gate, int side) {
        BlockPos at = gate.pos();
        int[] out = outward(e, at);
        // the passage runs along the outward axis, so the flanks are to either side of it
        int px = -out[1], pz = out[0];
        // the whole settlement: a guard answering a shout is away from its post, and must not be replaced meanwhile
        int reach = e.radius + 24;
        AABB box = new AABB(e.x - reach, level.getMinY(), e.z - reach, e.x + reach, (level.getMaxY()+1), e.z + reach);
        List<GuardEntity> near = level.getEntitiesOfClass(GuardEntity.class, box, g -> g.isAlive());
        long now = level.getGameTime();
        float yaw = (float) (Math.atan2(-out[0], out[1]) * (180.0 / Math.PI));
        {
            String key = e.key + "|" + gate.id() + "|" + side;
            GuardEntity holder = null;
            for (GuardEntity g : near) {
                if (!key.equals(g.getPersistentData().getStringOr(KEY, ""))) continue;
                if (holder == null) holder = g;
                else g.discard(); // two guards on one post: the later one goes
            }
            if (holder != null) {
                LAST_FILLED.put(key, now);
                return;
            }
            Long last = LAST_FILLED.get(key);
            if (last != null && now - last < REPLACE_DELAY) return;
            int sign = side == 0 ? 1 : -1;
            BlockPos want = new BlockPos(at.getX() + px * FLANK * sign, at.getY(), at.getZ() + pz * FLANK * sign);
            BlockPos spot = standable(level, want, at);
            if (spot == null) return;
            GuardEntity guard = ModEntities.GUARD.get().create(level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            if (guard == null) return;
            guard.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, 0f);
            guard.setYHeadRot(yaw);
            guard.yBodyRot = yaw;
            guard.getPersistentData().putString(KEY, key);
            guard.setProfile(GuardProfiles.of(e).profile());
            guard.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null);
            guard.setPost(spot, yaw);
            guard.setPersistenceRequired();
            if (level.addFreshEntity(guard)) LAST_FILLED.put(key, now);
        }
    }

    /** The cell nearest {@code want} (within three blocks, never the passage itself or a roof/furniture) a person can stand in. */
    static BlockPos standable(ServerLevel level, BlockPos want, BlockPos gate) {
        for (int dy : new int[]{0, -1, 1, -2, 2}) {
            for (int r = 0; r <= 3; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = want.offset(dx, dy, dz);
                        if (p.getX() == gate.getX() && p.getZ() == gate.getZ()) continue;
                        if (!level.isLoaded(p)) continue;
                        if (level.canSeeSky(p) && NpcPlacement.hasRoomBelow(level, p)) continue;
                        if (NpcPlacement.isFireHazard(level, p) || NpcPlacement.isSlabHazard(level, p)
                                || NpcPlacement.isFurnitureHazard(level, p)) continue;
                        BlockState feet = level.getBlockState(p), head = level.getBlockState(p.above()), floor = level.getBlockState(p.below());
                        if (!feet.getCollisionShape(level, p).isEmpty() || !head.getCollisionShape(level, p.above()).isEmpty()) continue;
                        if (!floor.isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP) || !level.getFluidState(p).isEmpty()) continue;
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** Forgets cached timers (server stop; the self-test uses it to let a killed guard be replaced at once). */
    public static void clear() {
        LAST_FILLED.clear();
        GuardWatch.clear();
    }

    /** Test hook: the posts' keys of a settlement as the census and the self-test read them (empty unless it is major). */
    static List<String> keysOf(ServerLevel level, SettlementAnchors.Entry e) {
        List<String> keys = new ArrayList<>();
        List<Gate> gates = gatesOf(level, e);
        for (int[] post : GuardWatch.posts(GuardWatch.of(e))) {
            if (post[0] < gates.size()) keys.add(e.key + "|" + gates.get(post[0]).id() + "|" + post[1]);
        }
        return keys;
    }

    /** Test hook: the position of the gate of each post, in the order of {@link #keysOf}. */
    static List<BlockPos> postGates(ServerLevel level, SettlementAnchors.Entry e) {
        List<BlockPos> out = new ArrayList<>();
        List<Gate> gates = gatesOf(level, e);
        for (int[] post : GuardWatch.posts(GuardWatch.of(e))) {
            if (post[0] < gates.size()) out.add(gates.get(post[0]).pos());
        }
        return out;
    }
}
