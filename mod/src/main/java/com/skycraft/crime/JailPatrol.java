package com.skycraft.crime;

import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import java.util.UUID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rounds of a jail's guards (docs/JAIL.md "Rounds"). A hold jail has two guards walking one route, the cell block
 * corridor, the gate, the guard room and the front door, a different phase each, so that there are gaps and overlaps; the
 * jail dimension's cell block has one guard walking its corridor. The schedule is a pure function of the game time
 * (a slot is {@link #SLOT_SECONDS} seconds, the round {@link #HOLD_ROUND}.length slots), so it can be learnt and
 * exploited: a guard standing far away at the end of the corridor does not see a sneaking prisoner at the cell door,
 * and a guard outside the front door behind its shut door does not see the guard room.
 *
 * <p>Detection is the crime module's own (a guard that sees a prisoner outside the cell makes {@link Jail}'s re-arrest
 * run: {@link Crimes#findGuardWitness} with its sneak and darkness rules, line of sight needed); this class only moves
 * the guards, opens the gate and the front door for them, and sends guards to a noise ({@link #noise}).</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.skycraft.Skycraft.MODID)
final class JailPatrol {
    private JailPatrol() {}

    /** A fallen guard of a jail, until another guard comes upon it. */
    private record Body(BlockPos pos, long time) {}

    /** A jail on alert: where the patrols converge, and until when the front door is locked. */
    private record Alert(long until, BlockPos at) {}

    private static final Map<UUID, Body> BODIES = new HashMap<>();
    private static final Map<String, Alert> ALERTS = new HashMap<>();
    /** Jails (by gate position) and guard indexes that have had their guards this run, and when one went missing. */
    private static final Map<String, Long> MISSING = new HashMap<>();
    /** How long a jail stays on alert (3 minutes): the patrols converge and the front door is locked. */
    static final long ALERT_TICKS = 20L * 60 * 3;
    /** A body is found when a guard comes this close with a clear line. */
    private static final double FIND_RANGE = 10;
    /** A killed guard is replaced after this long (3 minutes). */
    private static final long REPLACE_TICKS = 20L * 60 * 3;

    /** A guard of a jail died: his body waits to be found. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof Mob m) || !m.entityTags().contains(TAG)) return;
        long now = m.level().getGameTime();
        BODIES.values().removeIf(b -> now - b.time() > 20L * 60 * 10);
        BODIES.put(m.getUUID(), new Body(m.blockPosition(), now));
    }

    /** Whether the jail with this gate is on alert at {@code now}. */
    static boolean onAlert(BlockPos gate, long now) {
        Alert a = ALERTS.get(gate.toString());
        return a != null && now < a.until();
    }

    /** Entity tag of a jail's patrolling guard. */
    static final String TAG = "skycraft_jail_patrol";
    private static final String INDEX = "skycraft_patrol_index";
    /** Game time until which a guard follows a noise or a prisoner instead of his round. */
    private static final String BUSY = "skycraft_patrol_busy";
    /** Seconds a guard stands at one stop of the round before the next. */
    static final int SLOT_SECONDS = 10;

    /** The stops of a hold jail's round. */
    enum Stop { FAR, NEAR, ROOM, EXIT }

    /** One guard's round in slots (the other guard walks it two slots later). */
    static final Stop[] HOLD_ROUND = {Stop.FAR, Stop.NEAR, Stop.ROOM, Stop.EXIT, Stop.EXIT, Stop.ROOM, Stop.NEAR, Stop.FAR};
    static final int[] PHASE = {0, 2};
    /** The jail dimension's guard: along the corridor and back, long at the far end. */
    static final Stop[] DIM_ROUND = {Stop.NEAR, Stop.NEAR, Stop.ROOM, Stop.FAR, Stop.FAR, Stop.FAR, Stop.ROOM, Stop.NEAR};

    private static final Map<String, Long> LAST = new HashMap<>();

    /** The stop guard {@code guard} (0 or 1) is at, or heading for, {@code seconds} into the world's time. */
    static Stop stopOf(Stop[] round, int guard, long seconds) {
        int slot = (int) Math.floorMod(seconds / SLOT_SECONDS, (long) round.length);
        return round[(slot + PHASE[Math.floorMod(guard, PHASE.length)]) % round.length];
    }

    // ------------------------------------------------------------------ hold jails

    /** A hold jail's route, from its recorded gate and front door anchors (axis aligned whatever way the jail faces). */
    record Route(ServerLevel level, BlockPos gate, BlockPos frontDoor, BlockPos far, BlockPos near, BlockPos room,
                 BlockPos exit, int ux, int uz, AABB bounds) {
        BlockPos at(Stop s) {
            return switch (s) {
                case FAR -> far;
                case NEAR -> near;
                case ROOM -> room;
                case EXIT -> exit;
            };
        }

        /** Which side of {@code door} a position is on: positive towards the cell block. */
        int side(BlockPos door, double x, double z) {
            double d = (x - (door.getX() + 0.5)) * ux + (z - (door.getZ() + 0.5)) * uz;
            return d > 0.5 ? 1 : d < -0.5 ? -1 : 0;
        }
    }

    static Route routeOf(ServerLevel level, SettlementAnchors.Entry e) {
        Anchor g = e.first(AnchorType.JAIL_GATE), x = e.first(AnchorType.JAIL_EXIT);
        Anchor lo = e.first(AnchorType.JAIL_MIN), hi = e.first(AnchorType.JAIL_MAX);
        if (g == null || x == null || lo == null || hi == null) return null;
        BlockPos gate = g.pos();
        int ux = Integer.signum(gate.getX() - x.pos().getX()), uz = Integer.signum(gate.getZ() - x.pos().getZ());
        if (ux != 0 && uz != 0) return null; // not an axis aligned jail
        if (ux == 0 && uz == 0) return null;
        AABB bounds = new AABB(Math.min(lo.pos().getX(), hi.pos().getX()), gate.getY() - 2, Math.min(lo.pos().getZ(), hi.pos().getZ()),
                Math.max(lo.pos().getX(), hi.pos().getX()) + 1, gate.getY() + 4, Math.max(lo.pos().getZ(), hi.pos().getZ()) + 1);
        // the front door is two blocks in from the exit anchor; the guard room's middle is half way to the gate
        BlockPos front = x.pos().offset(2 * ux, 0, 2 * uz);
        int toGate = (gate.getX() - front.getX()) * ux + (gate.getZ() - front.getZ()) * uz;
        BlockPos room = front.offset(ux * (toGate / 2), 0, uz * (toGate / 2));
        // a hold jail's cell doors line the corridor (it is long, with a far end); a guardhouse's stand in a row
        // across it behind a one block corridor
        int lo2 = Integer.MAX_VALUE, hi2 = Integer.MIN_VALUE;
        for (Anchor a : e.anchors(AnchorType.JAIL_DOOR)) {
            int along = (a.pos().getX() - gate.getX()) * ux + (a.pos().getZ() - gate.getZ()) * uz;
            lo2 = Math.min(lo2, along);
            hi2 = Math.max(hi2, along);
        }
        boolean longCorridor = hi2 - lo2 >= 3;
        BlockPos far = gate.offset((longCorridor ? 10 : 1) * ux, 0, (longCorridor ? 10 : 1) * uz);
        BlockPos near = gate.offset((longCorridor ? 2 : 1) * ux, 0, (longCorridor ? 2 : 1) * uz);
        return new Route(level, gate, front, far, near, room, x.pos().offset(-2 * ux, 0, -2 * uz), ux, uz, bounds);
    }

    /** Called every second for every player: the jail they are at (if any) gets its rounds. */
    static void tick(ServerPlayer player) {
        if (player.isSpectator()) return;
        ServerLevel level = player.level();
        long now = level.getGameTime();
        if (Jail.isJailDimension(level)) {
            String key = "dim#" + player.getUUID();
            if (LAST.getOrDefault(key, -1L) == now) return;
            LAST.put(key, now);
            Jail.Site site = Jail.dimensionSite(player.level().getServer(), level, player.getUUID());
            tickDim(level, site.min(), now / 20);
            return;
        }
        if (level.dimension() != Level.OVERWORLD) return;
        var near = SettlementAnchors.near(level, player.blockPosition(), 0);
        if (near.isEmpty() || near.get().first(AnchorType.JAIL_CELL) == null) return;
        SettlementAnchors.Entry e = near.get();
        Route r = routeOf(level, e);
        if (r == null || player.distanceToSqr(r.gate().getX() + 0.5, r.gate().getY(), r.gate().getZ() + 0.5) > 80 * 80) return;
        if (LAST.getOrDefault(e.key, -1L) == now) return;
        LAST.put(e.key, now);
        if (LAST.size() > 256) LAST.clear();
        tickHold(r, now / 20);
    }

    private static void tickHold(Route r, long seconds) {
        ServerLevel level = r.level();
        level.getChunk(r.gate());
        List<Mob> guards = guardsIn(level, r.bounds().inflate(10));
        // two guards on the round, missing ones come from the jail itself (not from the settlement's posts)
        long now = level.getGameTime();
        for (int idx = 0; idx < 2; idx++) {
            boolean present = false;
            for (Mob m : guards) if (indexOf(m) == idx) present = true;
            String key = r.gate() + "#" + idx;
            if (present) {
                MISSING.put(key, -1L);
                continue;
            }
            // a dead guard is not replaced at once: his body can be found, and the jail has to be got through
            Long since = MISSING.get(key);
            if (since != null && since < 0) {
                MISSING.put(key, now);
                continue;
            }
            if (since != null && now - since < REPLACE_TICKS) continue;
            Mob spawned = spawn(level, r.at(stopOf(HOLD_ROUND, idx, seconds)), idx);
            if (spawned != null) {
                guards.add(spawned);
                MISSING.put(key, -1L);
            }
        }
        // a guard who comes upon a fallen one raises the alarm: the patrols converge and the front door is locked
        Alert alert = ALERTS.get(r.gate().toString());
        if (alert != null && now >= alert.until()) {
            ALERTS.remove(r.gate().toString());
            Locks.setLock(level, r.frontDoor(), Locks.NOT_LOCKED);
            alert = null;
        }
        if (alert == null) alert = findBody(r, guards, now);
        boolean gateOpen = false, doorOpen = false;
        for (Mob m : guards) {
            int idx = indexOf(m);
            if (idx < 0) continue;
            Stop stop = stopOf(HOLD_ROUND, idx, seconds);
            BlockPos target = alert != null ? alert.at() : r.at(stop);
            // doors: open while a guard has to cross or stands by them
            int wantSide = stop == Stop.FAR || stop == Stop.NEAR ? 1 : -1;
            if (r.side(r.gate(), m.getX(), m.getZ()) != wantSide || m.distanceToSqr(center(r.gate())) < 12) gateOpen = true;
            boolean inside = stop != Stop.EXIT;
            int doorSide = r.side(r.frontDoor(), m.getX(), m.getZ());
            if (inside != (doorSide >= 0) || m.distanceToSqr(center(r.frontDoor())) < 12) doorOpen = true;
            walk(m, target, now);
        }
        setDoor(level, r.gate(), gateOpen);
        // on alert the front door is shut and locked: nobody leaves (or comes in) until the guards have it in hand
        if (alert != null) setDoor(level, r.frontDoor(), false);
        else if (doorOpen) setDoor(level, r.frontDoor(), true);
    }

    /** A patrolling guard within sight of a fallen guard's body: the jail goes on alert. */
    private static Alert findBody(Route r, List<Mob> guards, long now) {
        if (BODIES.isEmpty()) return null;
        for (var it = BODIES.entrySet().iterator(); it.hasNext(); ) {
            Body b = it.next().getValue();
            if (!r.bounds().inflate(8).contains(Vec3.atCenterOf(b.pos()))) continue;
            for (Mob m : guards) {
                if (!m.isAlive() || m.distanceToSqr(Vec3.atCenterOf(b.pos())) > FIND_RANGE * FIND_RANGE) continue;
                var hit = r.level().clip(new net.minecraft.world.level.ClipContext(m.getEyePosition(), Vec3.atCenterOf(b.pos()).add(0, 0.5, 0),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, m));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue;
                it.remove();
                Alert a = new Alert(now + ALERT_TICKS, b.pos());
                ALERTS.put(r.gate().toString(), a);
                setDoor(r.level(), r.frontDoor(), false);
                Locks.setLock(r.level(), r.frontDoor(), Locks.EXPERT);
                for (ServerPlayer p : r.level().getEntitiesOfClass(ServerPlayer.class, r.bounds().inflate(48))) {
                    com.skycraft.core.Notifier.message(p, Component.translatable("crime.skycraft.jail.alarm", m.getDisplayName()));
                }
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the jail dimension

    private static void tickDim(ServerLevel jail, BlockPos o, long seconds) {
        AABB block = new AABB(o.getX(), o.getY(), o.getZ(), o.getX() + 12, o.getY() + 5, o.getZ() + 26);
        List<Mob> guards = guardsIn(jail, block);
        if (guards.isEmpty()) {
            Mob g = spawn(jail, dimStop(o, stopOf(DIM_ROUND, 0, seconds)), 0);
            if (g != null) guards.add(g);
        }
        for (Mob m : guards) walk(m, dimStop(o, stopOf(DIM_ROUND, 0, seconds)), jail.getGameTime());
    }

    /** NEAR (by the player's cell window), ROOM (the middle) and FAR (the end of the corridor) of a cell block. */
    static BlockPos dimStop(BlockPos o, Stop s) {
        return switch (s) {
            case NEAR -> o.offset(3, 1, 6);
            case ROOM -> o.offset(3, 1, 14);
            default -> o.offset(3, 1, 21);
        };
    }

    // ------------------------------------------------------------------ guards

    private static List<Mob> guardsIn(ServerLevel level, AABB box) {
        return new ArrayList<>(level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && m.entityTags().contains(TAG)));
    }

    private static int indexOf(Mob m) {
        return m.getPersistentData().contains(INDEX) ? m.getPersistentData().getIntOr(INDEX, 0) : -1;
    }

    private static Mob spawn(ServerLevel level, BlockPos at, int idx) {
        try {
            var guard = com.skycraft.creatures.ModEntities.GUARD.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (guard == null) return null;
            guard.setCustomName(Component.translatable("crime.skycraft.jail.npc.guard"));
            guard.setCustomNameVisible(true);
            guard.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            guard.addTag(Jail.NPC_TAG);
            guard.addTag(TAG);
            guard.getPersistentData().putInt(INDEX, idx);
            guard.setPersistenceRequired();
            Mob mob = guard;
            if (!Crimes.isGuard(mob)) return null;
            level.addFreshEntity(guard);
            return mob;
        } catch (Exception e) {
            return null; // no guard entity registered: the jail is just quieter
        }
    }

    private static net.minecraft.world.phys.Vec3 center(BlockPos p) {
        return net.minecraft.world.phys.Vec3.atCenterOf(p);
    }

    /** Walks one guard to its stop (unless he is busy with a prisoner or a noise, or fighting). */
    private static void walk(Mob m, BlockPos target, long now) {
        if (m.getTarget() != null || m.getPersistentData().getLongOr(BUSY, 0L) > now) return;
        m.setHomeTo(target, 3);
        if (m.distanceToSqr(center(target)) > 2.5) {
            m.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
    }

    private static void setDoor(ServerLevel level, BlockPos door, boolean open) {
        BlockState st = level.getBlockState(door);
        if (!(st.getBlock() instanceof DoorBlock d) || st.getValue(DoorBlock.OPEN) == open) return;
        if (!open && !level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, new AABB(door).inflate(1.5)).isEmpty()) return;
        d.setOpen(null, level, st, door, open);
    }

    // ------------------------------------------------------------------ noise

    /**
     * A noise (a prisoner running about outside the cell, a lock being picked) in a jail: guards within {@code range}
     * blocks come to look, line of sight or not. They only see what the normal detection rules let them see.
     */
    static void noise(ServerPlayer player, double range) {
        ServerLevel level = player.level();
        long until = level.getGameTime() + 100;
        for (Mob g : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(range), m -> m.isAlive() && Crimes.isGuard(m))) {
            if (g.getTarget() != null) continue;
            g.getPersistentData().putLong(BUSY, until);
            g.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 1.1);
        }
    }

    /** A guard who is dealing with a prisoner leaves his round for a few seconds. */
    static void busy(Mob guard, long ticks) {
        guard.getPersistentData().putLong(BUSY, guard.level().getGameTime() + ticks);
    }
}
