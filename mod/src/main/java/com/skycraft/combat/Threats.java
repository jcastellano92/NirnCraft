package com.skycraft.combat;

import com.mojang.authlib.GameProfile;
import com.skycraft.Skycraft;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hostile awareness for the compass: the enemies that are coming after the player ({@link #COMBAT}: they target
 * them) or looking for them ({@link #SEARCH}: they lost them and are walking to the last place they saw them,
 * {@link Stealth#isSearchingFor}). Per player, every 10 ticks, the nearest {@link #MAX} within {@link #ENTER}
 * blocks are tracked and sent as compact deltas ({@link ThreatPackets}); a tracked hostile stays until it is
 * {@link #EXIT} blocks away, dead, or has been neither hunting nor searching for {@link #GRACE} ticks, so a mob
 * that flips its target for a moment does not make its marker flicker.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Threats {
    public static final int COMBAT = 1;
    public static final int SEARCH = 2;

    /** A hostile is picked up inside this many blocks... */
    public static final double ENTER = 64;
    /** ...and only dropped beyond this many (hysteresis). */
    public static final double EXIT = 80;
    public static final int MAX = 12;
    /** Ticks a tracked hostile may be in neither state before it is dropped. */
    public static final int GRACE = 40;
    public static final int INTERVAL = 10;
    /** A tracked hostile is re-sent once it moved this far from the last sent spot. */
    private static final double MOVE = 3.0;

    private static final class Entry {
        int kind;
        long lastMatch;
        double x, y, z;
        boolean sent;
        int sentKind, sx, sy, sz;
    }

    private static final class State {
        ResourceKey<Level> dim;
        final Map<Integer, Entry> entries = new LinkedHashMap<>();
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private Threats() {}

    public static void registerPackets() {
        ThreatPackets.register();
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player instanceof FakePlayer || player.connection == null) return;
        if ((player.tickCount + player.getId()) % INTERVAL != 0) return;
        ThreatPackets.Sync delta = update(player, player.level().getGameTime());
        if (delta != null) SkyNetwork.sendToPlayer(player, delta);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ the model

    /** Why this mob is shown for this player: {@link #COMBAT}, {@link #SEARCH}, or 0 (not at all). */
    public static int kindOf(Mob mob, ServerPlayer player) {
        if (!mob.isAlive() || mob.isRemoved()) return 0;
        if (mob.getTarget() == player) return COMBAT;
        if (Stealth.isSearchingFor(mob, player)) return SEARCH;
        return 0;
    }

    private record Candidate(Mob mob, int kind, double score, double dist) {}

    /**
     * Advances the player's threat list to {@code now} (game time) and returns what changed since the last call, or
     * null when nothing did. Pure server logic: sending is the caller's business.
     */
    public static ThreatPackets.Sync update(ServerPlayer player, long now) {
        State st = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        ServerLevel level = player.serverLevel();
        boolean reset = false;
        if (st.dim != null && st.dim != level.dimension()) {
            st.entries.clear();
            reset = true;
        }
        st.dim = level.dimension();

        List<Integer> removed = new ArrayList<>();
        boolean active = player.isAlive() && !player.isSpectator();
        if (!active) {
            for (Map.Entry<Integer, Entry> e : st.entries.entrySet()) if (e.getValue().sent) removed.add(e.getKey());
            st.entries.clear();
            return finish(reset, List.of(), removed);
        }

        // who qualifies now: hunting or searching, inside ENTER (inside EXIT if already tracked)
        List<Candidate> cands = new ArrayList<>();
        AABB box = player.getBoundingBox().inflate(EXIT, 48, EXIT);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && !m.isRemoved())) {
            int kind = kindOf(mob, player);
            if (kind == 0) continue;
            Entry known = st.entries.get(mob.getId());
            double dist = Math.sqrt(mob.distanceToSqr(player));
            if (dist > (known != null ? EXIT : ENTER)) continue;
            // combat before searching, then nearest; the ones already shown get a head start so the cap never swaps
            double score = dist - (kind == COMBAT ? 24 : 0) - (known != null ? 8 : 0);
            cands.add(new Candidate(mob, kind, score, dist));
        }
        cands.sort(Comparator.comparingDouble(Candidate::score));
        java.util.Set<Integer> matched = new java.util.HashSet<>();
        for (int i = 0; i < cands.size() && i < MAX; i++) {
            Candidate c = cands.get(i);
            Entry e = st.entries.computeIfAbsent(c.mob.getId(), k -> new Entry());
            e.kind = c.kind;
            e.lastMatch = now;
            e.x = c.mob.getX();
            e.y = c.mob.getY();
            e.z = c.mob.getZ();
            matched.add(c.mob.getId());
        }

        // everything else that is tracked: dead or far -> gone; recently lost -> kept for the grace period
        for (Iterator<Map.Entry<Integer, Entry>> it = st.entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Entry> me = it.next();
            if (matched.contains(me.getKey())) continue;
            Entry e = me.getValue();
            net.minecraft.world.entity.Entity live = level.getEntity(me.getKey());
            boolean gone = live == null || !live.isAlive() || now - e.lastMatch > GRACE
                    || live.distanceToSqr(player) > EXIT * EXIT;
            if (gone) {
                if (e.sent) removed.add(me.getKey());
                it.remove();
            } else {
                e.x = live.getX();
                e.y = live.getY();
                e.z = live.getZ();
            }
        }

        // the delta: new, changed kind, or moved noticeably since the last send
        List<ThreatPackets.Entry> upserts = new ArrayList<>();
        for (Map.Entry<Integer, Entry> me : st.entries.entrySet()) {
            Entry e = me.getValue();
            int x = (int) Math.floor(e.x), y = (int) Math.floor(e.y), z = (int) Math.floor(e.z);
            double dx = x - e.sx, dy = y - e.sy, dz = z - e.sz;
            if (e.sent && e.sentKind == e.kind && dx * dx + dy * dy + dz * dz < MOVE * MOVE) continue;
            e.sent = true;
            e.sentKind = e.kind;
            e.sx = x;
            e.sy = y;
            e.sz = z;
            upserts.add(new ThreatPackets.Entry(me.getKey(), e.kind, x, y, z));
        }
        return finish(reset, upserts, removed);
    }

    private static ThreatPackets.Sync finish(boolean reset, List<ThreatPackets.Entry> upserts, List<Integer> removed) {
        if (!reset && upserts.isEmpty() && removed.isEmpty()) return null;
        return new ThreatPackets.Sync(reset, List.copyOf(upserts), removed.stream().mapToInt(Integer::intValue).toArray());
    }

    /** What is currently tracked for a player (entity id -> kind); for the self-test. */
    public static Map<Integer, Integer> tracked(ServerPlayer player) {
        Map<Integer, Integer> out = new HashMap<>();
        State st = STATES.get(player.getUUID());
        if (st != null) st.entries.forEach((id, e) -> out.put(id, e.kind));
        return out;
    }

    // ------------------------------------------------------------------ self-test

    /**
     * Drives {@link #update} for a stand-in player with real mobs around them and checks: a hunter appears, a
     * resend of an unchanged scene sends nothing, a mob that lost its target keeps its marker for the grace period
     * and then loses it, a searching mob shows as searching and becomes a hunter again, the enter/exit hysteresis,
     * the cap, and a dead hunter vanishing at once. Problems are appended to {@code problems}.
     */
    public static void selfTest(ServerLevel level, List<String> info, List<String> problems) {
        ServerPlayer fake = FakePlayerFactory.get(level, new GameProfile(
                UUID.nameUUIDFromBytes("skycraft-threat-selftest".getBytes()), "[ThreatSelftest]"));
        BlockPos base = level.getSharedSpawnPos();
        level.getChunk(base.getX() >> 4, base.getZ() >> 4);
        double px = base.getX() + 0.5, pz = base.getZ() + 0.5;
        double py = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, base.getX(), base.getZ()) + 1;
        fake.moveTo(px, py, pz);
        STATES.remove(fake.getUUID());
        List<Mob> spawned = new ArrayList<>();
        long t = 10_000;
        try {
            Mob a = spawn(level, spawned, px + 10, py, pz);
            a.setTarget(fake);
            ThreatPackets.Sync s = update(fake, t);
            if (s == null || s.upserts().size() != 1 || s.upserts().get(0).kind() != COMBAT || s.upserts().get(0).id() != a.getId()) {
                problems.add("threats: a hostile targeting the player is not tracked as COMBAT: " + describe(s));
            }
            t += INTERVAL;
            if (update(fake, t) != null) problems.add("threats: an unchanged scene produced a delta (markers would be resent every second)");

            // loses the target: stays through the grace period, then is removed
            a.setTarget(null);
            t += INTERVAL;
            s = update(fake, t);
            if (s != null && s.removed().length > 0) problems.add("threats: the marker vanished the moment the target flipped (flicker)");
            t += GRACE + INTERVAL;
            s = update(fake, t);
            if (s == null || s.removed().length != 1 || s.removed()[0] != a.getId()) problems.add("threats: a hostile that stopped hunting was not removed after the grace period: " + describe(s));

            // searching
            Mob b = spawn(level, spawned, px - 15, py, pz);
            Stealth.beginSearch(b, new Vec3(px - 5, py, pz), fake.getUUID());
            s = update(fake, t += INTERVAL);
            if (s == null || s.upserts().size() != 1 || s.upserts().get(0).kind() != SEARCH) problems.add("threats: a searching hostile is not tracked as SEARCH: " + describe(s));
            b.setTarget(fake);
            s = update(fake, t += INTERVAL);
            if (s == null || s.upserts().size() != 1 || s.upserts().get(0).kind() != COMBAT) problems.add("threats: a searching hostile that found the player is not upgraded to COMBAT: " + describe(s));
            b.discard();
            s = update(fake, t += INTERVAL);
            if (s == null || s.removed().length != 1) problems.add("threats: a removed hostile's marker was not dropped at once: " + describe(s));

            // hysteresis: not picked up beyond ENTER, kept until EXIT
            Mob far = spawn(level, spawned, px + ENTER + 6, py, pz);
            far.setTarget(fake);
            s = update(fake, t += INTERVAL);
            if (s != null && !s.upserts().isEmpty()) problems.add("threats: a hostile beyond " + ENTER + " blocks was picked up");
            far.setPos(px + 50, py, pz);
            s = update(fake, t += INTERVAL);
            if (s == null || s.upserts().size() != 1) problems.add("threats: a hostile inside " + ENTER + " blocks was not picked up: " + describe(s));
            far.setPos(px + ENTER + 8, py, pz);
            s = update(fake, t += INTERVAL);
            if (s != null && s.removed().length > 0) problems.add("threats: a tracked hostile was dropped inside the exit range (hysteresis broken)");
            far.setPos(px + EXIT + 6, py, pz);
            s = update(fake, t += INTERVAL);
            if (s == null || s.removed().length != 1) problems.add("threats: a hostile beyond " + EXIT + " blocks was not dropped: " + describe(s));
            far.setTarget(null);
            update(fake, t += INTERVAL + GRACE + INTERVAL);

            // the cap
            for (int i = 0; i < MAX + 4; i++) spawn(level, spawned, px + 12 + i, py, pz + (i % 2 == 0 ? 3 : -3)).setTarget(fake);
            update(fake, t += INTERVAL);
            int tracked = tracked(fake).size();
            if (tracked != MAX) problems.add("threats: " + (MAX + 4) + " hunters tracked as " + tracked + ", expected the nearest " + MAX);
            info.add("threat compass: combat, search, grace, hysteresis, cap and removal checked on " + spawned.size() + " mobs");
        } finally {
            for (Mob m : spawned) m.discard();
            STATES.remove(fake.getUUID());
        }
    }

    private static Mob spawn(ServerLevel level, List<Mob> out, double x, double y, double z) {
        Mob m = (Mob) EntityType.ZOMBIE.create(level);
        m.moveTo(x, y, z, 0f, 0f);
        m.setNoAi(true);
        m.setSilent(true);
        m.setPersistenceRequired();
        level.addFreshEntity(m);
        out.add(m);
        return m;
    }

    private static String describe(ThreatPackets.Sync s) {
        if (s == null) return "no delta";
        return "reset=" + s.reset() + " upserts=" + s.upserts() + " removed=" + java.util.Arrays.toString(s.removed());
    }
}
