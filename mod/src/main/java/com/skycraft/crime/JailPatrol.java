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
final class JailPatrol {
    private JailPatrol() {}

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
        return new Route(level, gate, gate.offset(-7 * ux, 0, -7 * uz), gate.offset(10 * ux, 0, 10 * uz), gate.offset(2 * ux, 0, 2 * uz),
                gate.offset(-4 * ux, 0, -4 * uz), x.pos().offset(-2 * ux, 0, -2 * uz), ux, uz, bounds);
    }

    /** Called every second for every player: the jail they are at (if any) gets its rounds. */
    static void tick(ServerPlayer player) {
        if (player.isSpectator()) return;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        if (Jail.isJailDimension(level)) {
            String key = "dim#" + player.getUUID();
            if (LAST.getOrDefault(key, -1L) == now) return;
            LAST.put(key, now);
            Jail.Site site = Jail.dimensionSite(player.server, level, player.getUUID());
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
        for (int idx = 0; idx < 2; idx++) {
            boolean present = false;
            for (Mob m : guards) if (indexOf(m) == idx) present = true;
            if (present) continue;
            Mob spawned = spawn(level, r.at(stopOf(HOLD_ROUND, idx, seconds)), idx);
            if (spawned != null) guards.add(spawned);
        }
        long now = level.getGameTime();
        boolean gateOpen = false, doorOpen = false;
        for (Mob m : guards) {
            int idx = indexOf(m);
            if (idx < 0) continue;
            Stop stop = stopOf(HOLD_ROUND, idx, seconds);
            BlockPos target = r.at(stop);
            // doors: open while a guard has to cross or stands by them
            int wantSide = stop == Stop.FAR || stop == Stop.NEAR ? 1 : -1;
            if (r.side(r.gate(), m.getX(), m.getZ()) != wantSide || m.distanceToSqr(center(r.gate())) < 12) gateOpen = true;
            boolean inside = stop != Stop.EXIT;
            int doorSide = r.side(r.frontDoor(), m.getX(), m.getZ());
            if (inside != (doorSide >= 0) || m.distanceToSqr(center(r.frontDoor())) < 12) doorOpen = true;
            walk(m, target, now);
        }
        setDoor(level, r.gate(), gateOpen);
        setDoor(level, r.frontDoor(), doorOpen);
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
        return new ArrayList<>(level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && m.getTags().contains(TAG)));
    }

    private static int indexOf(Mob m) {
        return m.getPersistentData().contains(INDEX) ? m.getPersistentData().getInt(INDEX) : -1;
    }

    private static Mob spawn(ServerLevel level, BlockPos at, int idx) {
        try {
            var guard = com.skycraft.creatures.ModEntities.GUARD.get().create(level);
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
        if (m.getTarget() != null || m.getPersistentData().getLong(BUSY) > now) return;
        m.restrictTo(target, 3);
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
        ServerLevel level = player.serverLevel();
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
