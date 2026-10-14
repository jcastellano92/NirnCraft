package com.skycraft.society;

import com.skycraft.core.Holds;
import com.skycraft.creatures.GuardProfiles;
import com.skycraft.creatures.ModEntities;
import com.skycraft.creatures.entity.GuardEntity;
import com.skycraft.crime.Bounty;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who keeps the watch where (owner, playtest 5): no guards in the wilderness, one wandering guard in a minor
 * settlement, two posted guards in a major one, nothing in anything smaller. This class is the rule and the keeper of
 * everything that is not a gate post ({@link GatePosts} stands the two at the main gate):
 * <table>
 *   <tr><td>{@link Watch#MAJOR}</td><td>capitals, cities and the provincial seats (towns of radius 90+): exactly two
 *       posted guards, one on each side of the main gate</td></tr>
 *   <tr><td>{@link Watch#MINOR}</td><td>towns (Riverwood-class): exactly one guard who walks a route through the
 *       settlement (market, gate, stables, forge), posted nowhere</td></tr>
 *   <tr><td>{@link Watch#NONE}</td><td>villages, farms, hamlets, camps, homesteads, forts, dungeons and the open
 *       country; also Helgen (scripted soldiers), the College (its own wards) and the Skaal village</td></tr>
 * </table>
 * The guards' look is the settlement's own ({@link GuardProfiles}). A guard of no post, patrol or scripted scene left
 * inside a settlement by an older save is removed. When a crime is witnessed the guards within {@link #SHOUT_RANGE} of
 * the player come ({@link #shout}): with one or two guards a settlement has, a shout is what makes the response work.
 */
public final class GuardWatch {
    public enum Watch { MAJOR, MINOR, NONE }

    /** Persistent-data key on the one patrolling guard of a minor settlement: {@code "<settlement>|patrol"}. */
    public static final String PATROL_KEY = "skycraft_patrol_post";
    /** Persistent-data flag on guards a scene scripted (Helgen's soldiers): never culled, never counted as the watch. */
    public static final String SCRIPTED = "skycraft_scripted_guard";
    /** Guards of a major settlement. */
    public static final int POSTED = 2;
    /** Guards of a minor settlement. */
    public static final int PATROLS = 1;
    /** A town this large is a provincial seat with walls and a court of its own: a major settlement. */
    public static final int SEAT_RADIUS = 90;
    /** Blocks within which a shout for the guard is heard. */
    public static final double SHOUT_RANGE = 80;
    /** Ticks after a witnessed crime during which the guards keep coming to the player. */
    private static final long ALARM_TICKS = 400;
    /** Ticks a vacated patrol stays empty before its replacement walks in. */
    private static final long REPLACE_DELAY = 1200;

    /** Places that never keep a watch: the soldiers of Helgen are the intro's, the College has its own wards. */
    private static final Set<String> NO_WATCH = Set.of("helgen", "college_of_winterhold", "skaal_village");

    /** Game time each patrol was last seen filled. */
    private static final Map<String, Long> LAST_FILLED = new HashMap<>();
    /** The bounty each player had in the hold they stood in at the last look (a rise is a fresh crime). */
    private static final Map<UUID, int[]> BOUNTY = new HashMap<>();
    private static final Map<UUID, Long> ALARM_UNTIL = new HashMap<>();

    private GuardWatch() {}

    // ------------------------------------------------------------------ the rule

    /** The watch a place keeps, from its kind and size (a pure rule: the tests call it on the places file). */
    public static Watch of(PlaceType type, int radius, String variant, String id) {
        if ("homestead".equals(variant) || NO_WATCH.contains(id)) return Watch.NONE;
        return switch (type) {
            case CAPITAL, CITY -> Watch.MAJOR;
            case TOWN -> radius >= SEAT_RADIUS ? Watch.MAJOR : Watch.MINOR;
            default -> Watch.NONE;
        };
    }

    public static Watch of(Place p) {
        return of(p.type(), p.radius(), p.variant(), p.id());
    }

    /** The watch of a recorded settlement (a generated one with no place keeps none). */
    public static Watch of(SettlementAnchors.Entry e) {
        return e.place().map(GuardWatch::of).orElse(Watch.NONE);
    }

    /** How many guards a watch has. */
    public static int count(Watch w) {
        return switch (w) {
            case MAJOR -> POSTED;
            case MINOR -> PATROLS;
            case NONE -> 0;
        };
    }

    /** The posts of a major settlement as {@code {gate index, side}}: both sides of the main gate. */
    public static int[][] posts(Watch w) {
        return w == Watch.MAJOR ? new int[][]{{0, 0}, {0, 1}} : new int[0][];
    }

    // ------------------------------------------------------------------ per player, every couple of seconds

    /** Whether the guard is a settlement's watch or somebody's scripted guard (anything else in a settlement is legacy). */
    private static boolean belongs(GuardEntity g) {
        CompoundTag pd = g.getPersistentData();
        if (pd.contains(GatePosts.KEY) || pd.contains(PATROL_KEY) || pd.getBoolean(SCRIPTED)) return true;
        if (pd.contains(com.skycraft.quest.QuestSpawner.TAG_QUEST) || pd.contains(com.skycraft.quest.story.QuestNpcs.TAG)
                || pd.contains(com.skycraft.quest.faction.FactionStaff.TAG) || Encounters.isTagged(g)) return true;
        return g.getTags().contains(com.skycraft.crime.Jail.NPC_TAG) || g.getTags().contains("skycraft_keep");
    }

    /** What the settlement tick does for {@code e} (a player is near): remove legacy guards, keep the patrol. */
    static void keep(ServerLevel level, SettlementAnchors.Entry e, Watch watch) {
        int r = e.radius + 24;
        AABB box = new AABB(e.x - r, level.getMinBuildHeight(), e.z - r, e.x + r, level.getMaxBuildHeight(), e.z + r);
        for (GuardEntity g : level.getEntitiesOfClass(GuardEntity.class, box, x -> x.isAlive() && !belongs(x))) {
            if (g.distanceToSqr(e.x, g.getY(), e.z) <= (double) r * r) g.discard();
        }
        if (watch == Watch.MINOR) keepPatrol(level, e);
    }

    /** Road patrols of older saves (guards walking the roads) are gone: nobody keeps the watch in the wilderness. */
    static void cullRoadPatrols(ServerLevel level, ServerPlayer player) {
        for (GuardEntity g : level.getEntitiesOfClass(GuardEntity.class, player.getBoundingBox().inflate(96, 64, 96), GuardEntity::isAlive)) {
            CompoundTag pd = g.getPersistentData();
            if (pd.contains(com.skycraft.roads.Travelers.TAG)
                    && com.skycraft.roads.Travelers.PATROL.equals(pd.getCompound(com.skycraft.roads.Travelers.TAG).getString("kind"))) {
                g.discard();
            }
        }
    }

    // ------------------------------------------------------------------ the one wandering guard of a minor settlement

    private static void keepPatrol(ServerLevel level, SettlementAnchors.Entry e) {
        String key = e.key + "|patrol";
        int r = e.radius + 16;
        AABB box = new AABB(e.x - r, level.getMinBuildHeight(), e.z - r, e.x + r, level.getMaxBuildHeight(), e.z + r);
        long now = level.getGameTime();
        GuardEntity holder = null;
        for (GuardEntity g : level.getEntitiesOfClass(GuardEntity.class, box, x -> x.isAlive())) {
            if (!key.equals(g.getPersistentData().getString(PATROL_KEY))) continue;
            if (holder == null) holder = g;
            else g.discard(); // two guards on one patrol: the later one goes
        }
        if (holder != null) {
            LAST_FILLED.put(key, now);
            return;
        }
        Long last = LAST_FILLED.get(key);
        if (last != null && now - last < REPLACE_DELAY) return;
        List<BlockPos> route = route(level, e);
        if (route.isEmpty()) return;
        BlockPos spot = GatePosts.standable(level, route.get(0), route.get(0));
        if (spot == null || !level.isLoaded(spot) || !level.isPositionEntityTicking(spot)) return;
        GuardEntity guard = ModEntities.GUARD.get().create(level);
        if (guard == null) return;
        guard.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0f);
        guard.getPersistentData().putString(PATROL_KEY, key);
        guard.setProfile(GuardProfiles.of(e).profile());
        guard.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), MobSpawnType.EVENT, null, null);
        guard.setPatrol(new BlockPos(e.x, spot.getY(), e.z), Math.min(56, Math.max(24, e.radius)), route);
        guard.setPersistenceRequired();
        if (level.addFreshEntity(guard)) LAST_FILLED.put(key, now);
    }

    /**
     * The stops of a patrol: the square, the gate, the stables and carriage stop, the forge (the outdoor anchors the
     * builders recorded), and four points on a ring round the middle when the settlement recorded too few.
     */
    static List<BlockPos> route(ServerLevel level, SettlementAnchors.Entry e) {
        List<BlockPos> out = new ArrayList<>();
        AnchorType[] order = {AnchorType.MEETING, AnchorType.GATE, AnchorType.CARRIAGE, AnchorType.STABLES, AnchorType.SMITH};
        for (AnchorType t : order) {
            for (Anchor a : e.anchors(t)) {
                if (out.size() >= 5) break;
                BlockPos p = a.pos();
                if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
                boolean near = false;
                for (BlockPos q : out) if (q.distSqr(p) < 100) near = true;
                if (!near) out.add(p);
            }
        }
        if (out.size() < 3) {
            int d = Math.max(10, e.radius * 2 / 5);
            int[][] ring = {{d, 0}, {0, d}, {-d, 0}, {0, -d}};
            for (int[] o : ring) {
                int x = e.x + o[0], z = e.z + o[1];
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                out.add(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ a shout for the guard

    /**
     * The player has just been seen committing a crime (their bounty here went up): every guard of the watch within
     * {@link #SHOUT_RANGE} drops its post or its route and comes to them, and keeps coming for a while. The crime
     * module's own tick takes over when the guards are within sight (confront, arrest, fight).
     */
    static void shout(ServerLevel level, ServerPlayer player) {
        if (player.isCreative() || player.isSpectator() || com.skycraft.crime.Jail.isJailed(player)
                || com.skycraft.crime.Jail.isJailDimension(level)) return;
        String hold = Holds.holdAt(level, player.blockPosition());
        int bounty = Bounty.get(player, hold);
        int[] prior = BOUNTY.get(player.getUUID());
        long now = level.getGameTime();
        boolean sameHold = prior != null && prior[1] == hold.hashCode();
        if (bounty > 0 && (!sameHold || bounty > prior[0])) ALARM_UNTIL.put(player.getUUID(), now + ALARM_TICKS);
        BOUNTY.put(player.getUUID(), new int[]{bounty, hold.hashCode()});
        if (bounty <= 0) {
            ALARM_UNTIL.remove(player.getUUID());
            return;
        }
        Long until = ALARM_UNTIL.get(player.getUUID());
        if (until == null || now >= until) return;
        BlockPos at = player.blockPosition();
        for (GuardEntity g : level.getEntitiesOfClass(GuardEntity.class, player.getBoundingBox().inflate(SHOUT_RANGE, 24, SHOUT_RANGE),
                x -> x.isAlive() && x.getTarget() == null)) {
            if (g.distanceToSqr(player) <= 25) continue; // already beside them: the crime tick handles it
            g.summon(at, GuardEntity.SUMMON_TICKS);
        }
    }

    /** Forgets the clocks (server stop; the self-test lets a killed guard be replaced at once). */
    static void clear() {
        LAST_FILLED.clear();
        BOUNTY.clear();
        ALARM_UNTIL.clear();
    }
}
