package com.skycraft.seas.traffic;

import com.skycraft.Skycraft;
import com.skycraft.sailing.DeckPhysics;
import com.skycraft.sailing.ShipEntity;
import com.skycraft.seas.SeasConfig;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The captain of a ship of the sea traffic ({@link ShipEntity.Autopilot}): sails it along its course (a charted sea
 * route, or open water), trims the sail to the wind and takes to the oars in irons, steers round shoals and coasts it
 * can see (never into unloaded water), and keeps the crew aboard.
 *
 * <p>Hostile ships (pirates, Maormer, Sload, ghosts) give chase to a player's ship, come alongside and lash on
 * (grappled: the two hulls move together), and their crews leap across to board; a player can cut free by sailing hard
 * away. When the whole crew is dead the ship is a prize: the helm is free and it is the player's to sail. Ships leave
 * when nobody has been near for half a minute; ghost ships fade at dawn.</p>
 */
public class NpcCaptain implements ShipEntity.Autopilot {
    private enum Mode { COURSE, PURSUE, GRAPPLED }

    private final ShipKind kind;
    private final List<UUID> crew = new ArrayList<>();
    private final float[] xs, zs;
    private int next;
    private Mode mode = Mode.COURSE;
    @Nullable
    private UUID quarry;
    @Nullable
    private ShipEntity lashedTo;
    private int lashSide = 1;
    private int escapeTicks;
    private float avoidYaw;
    private int avoidTicks;
    private int lonelyTicks;
    private int helm;
    private boolean prize;
    private int lastHail;

    public NpcCaptain(ShipKind kind, float[] xs, float[] zs, int next) {
        this.kind = kind;
        this.xs = xs;
        this.zs = zs;
        this.next = Math.min(next, xs.length - 1);
    }

    public ShipKind kind() {
        return kind;
    }

    public void addCrew(Entity e) {
        crew.add(e.getUUID());
    }

    public List<UUID> crew() {
        return crew;
    }

    @Override
    public int helm(ShipEntity ship) {
        return helm;
    }

    // ------------------------------------------------------------------ every tick

    @Override
    public void tick(ShipEntity ship) {
        if (!(ship.level() instanceof ServerLevel level)) return;
        long t = level.getGameTime();
        if (mode == Mode.GRAPPLED) {
            holdAlongside(ship);
        }
        if ((t + ship.getId()) % 5 != 0) return;
        if ((t + ship.getId()) % 20 == 0 && checkLeave(level, ship)) return;
        if ((t + ship.getId()) % 20 == 0) checkCrew(level, ship);
        if (prize || ship.isRemoved()) return;
        if (kind.hostile && SeasConfig.PIRATES_ATTACK.get() && (t + ship.getId()) % 20 == 0) lookForQuarry(level, ship);
        switch (mode) {
            case COURSE -> steerCourse(level, ship);
            case PURSUE -> steerPursuit(level, ship);
            case GRAPPLED -> helm = 0;
        }
    }

    /** Despawning: nobody near for 30 s, the course run out into unloaded water, or a ghost ship at sunrise. */
    private boolean checkLeave(ServerLevel level, ShipEntity ship) {
        Player near = level.getNearestPlayer(ship, 200);
        boolean ghostDawn = kind == ShipKind.GHOST && level.isDay() && near != null && ship.tickCount > 200;
        if (near == null) lonelyTicks += 20;
        else lonelyTicks = 0;
        boolean aboard = false;
        for (ServerPlayer p : level.players()) {
            if (DeckPhysics.deckShip(p) == ship || p.getVehicle() == ship) aboard = true;
        }
        if (!aboard && (lonelyTicks > 600 || ghostDawn || !level.isPositionEntityTicking(ship.blockPosition()))) {
            if (ghostDawn) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, ship.getX(), ship.getY() + 3, ship.getZ(), 80, 4, 2, 4, 0.02);
                level.playSound(null, ship.getX(), ship.getY(), ship.getZ(), SoundEvents.SOUL_ESCAPE, SoundSource.NEUTRAL, 2f, 0.6f);
            }
            leave(level, ship);
            return true;
        }
        return false;
    }

    /** The ship and its crew go (they are never saved either). */
    public void leave(ServerLevel level, ShipEntity ship) {
        for (UUID id : crew) {
            Entity e = level.getEntity(id);
            if (e != null && e.isAlive()) e.discard();
        }
        crew.clear();
        ship.discard();
    }

    /** Drops the dead; a ship whose crew is all dead becomes a prize the player may sail away. */
    private void checkCrew(ServerLevel level, ShipEntity ship) {
        if (prize || crew.isEmpty()) return;
        boolean any = false;
        for (UUID id : crew) {
            Entity e = level.getEntity(id);
            if (e != null && e.isAlive()) {
                any = true;
                break;
            }
        }
        if (any || ship.tickCount < 100) return;
        prize = true;
        helm = 0;
        ship.setSail(ShipEntity.SAIL_FURLED);
        ship.setAutopilot(null);
        ship.setTransient(false);
        Player p = level.getNearestPlayer(ship, 32);
        if (p instanceof ServerPlayer sp) {
            com.skycraft.core.Notifier.message(sp, Component.translatable("seas.skycraft.prize", Component.translatable("seas.skycraft.ship." + kind.id)));
        }
        Skycraft.LOGGER.debug("Seas: {} at {} taken as a prize", kind.id, ship.blockPosition());
    }

    // ------------------------------------------------------------------ the course

    private void steerCourse(ServerLevel level, ShipEntity ship) {
        if (next >= xs.length) {
            helm = 0;
            ship.setSail(ShipEntity.SAIL_HALF);
            return;
        }
        double dx = xs[next] - ship.getX(), dz = zs[next] - ship.getZ();
        if (dx * dx + dz * dz < 24 * 24) {
            next++;
            return;
        }
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        steerTo(level, ship, want, true);
    }

    /** Sets the helm bits for a wanted heading: rudder, sail trim to the wind, oars when the sail won't draw. */
    private void steerTo(ServerLevel level, ShipEntity ship, float want, boolean cruise) {
        if (avoidTicks > 0) {
            avoidTicks -= 5;
            want = avoidYaw;
        } else if (blocked(level, ship, ship.getYRot())) {
            // something ahead: pick the clearer side
            for (float off : new float[]{40, -40, 80, -80, 130, -130, 180}) {
                if (!blocked(level, ship, ship.getYRot() + off)) {
                    avoidYaw = ship.getYRot() + off;
                    avoidTicks = 60;
                    want = avoidYaw;
                    break;
                }
            }
        }
        float delta = Mth.wrapDegrees(want - ship.getYRot());
        int bits = 0;
        if (delta > 6) bits |= ShipEntity.IN_RIGHT;
        else if (delta < -6) bits |= ShipEntity.IN_LEFT;
        float off = Mth.wrapDegrees(ship.getWindDir() - ship.getYRot());
        float eff = ship.shipType.sailEfficiency(off);
        if (ship.shipType.hasSail()) ship.setSail(eff > 0.15f ? (cruise ? ShipEntity.SAIL_HALF + (eff > 0.6f ? 1 : 0) : ShipEntity.SAIL_FULL) : ShipEntity.SAIL_FURLED);
        if (ship.shipType.hasOars() && (eff < 0.35f || !cruise || Math.abs(delta) > 60)) bits |= ShipEntity.IN_FORWARD;
        helm = bits;
    }

    /** Land, shoals or unloaded water within 20 blocks along a heading. */
    private static boolean blocked(ServerLevel level, ShipEntity ship, float yaw) {
        float r = yaw * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(r), fz = Mth.cos(r);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 6; d <= 20; d += 7) {
            int x = Mth.floor(ship.getX() + fx * d), z = Mth.floor(ship.getZ() + fz * d);
            p.set(x, Mth.floor(ship.getY() + ship.shipType.draft), z);
            if (!level.isLoaded(p) || !level.isPositionEntityTicking(p)) return true;
            if (!level.getFluidState(p).is(FluidTags.WATER)) return true;
            if (!level.getFluidState(p.move(0, -2, 0)).is(FluidTags.WATER)) return true; // shoal
        }
        return false;
    }

    // ------------------------------------------------------------------ the chase

    private void lookForQuarry(ServerLevel level, ShipEntity ship) {
        if (mode != Mode.COURSE) return;
        Player best = null;
        double bestD = 72 * 72;
        for (ServerPlayer p : level.players()) {
            if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
            double d = p.distanceToSqr(ship);
            if (d < bestD && (p.getVehicle() instanceof ShipEntity || DeckPhysics.deckShip(p) != null || p.isInWater())) {
                bestD = d;
                best = p;
            }
        }
        if (best == null) return;
        quarry = best.getUUID();
        mode = Mode.PURSUE;
        for (UUID id : crew) {
            if (level.getEntity(id) instanceof NpcEntity npc) npc.setHuntTarget(quarry);
            if (level.getEntity(id) instanceof Mob m && !(m instanceof NpcEntity)) m.setTarget(best);
        }
        if (best instanceof ServerPlayer sp && level.getGameTime() - lastHail > 1200) {
            lastHail = (int) level.getGameTime();
            com.skycraft.core.Notifier.message(sp, Component.translatable("seas.skycraft.hail." + kind.id));
            level.playSound(null, ship.getX(), ship.getY() + 2, ship.getZ(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 3f, 1.3f);
        }
    }

    @Nullable
    private ShipEntity quarryShip(ServerLevel level) {
        Player p = quarry == null ? null : level.getPlayerByUUID(quarry);
        if (p == null || !p.isAlive()) return null;
        if (p.getVehicle() instanceof ShipEntity s) return s;
        return DeckPhysics.deckShip(p);
    }

    private void steerPursuit(ServerLevel level, ShipEntity ship) {
        Player p = quarry == null ? null : level.getPlayerByUUID(quarry);
        if (p == null || !p.isAlive() || p.distanceToSqr(ship) > 140 * 140) {
            mode = Mode.COURSE;
            quarry = null;
            return;
        }
        ShipEntity target = quarryShip(level);
        Vec3 aim;
        if (target != null && target != ship) {
            // come alongside, on the side we approach from
            Vec3 local = target.toLocal(ship.getX(), ship.getY(), ship.getZ());
            lashSide = local.x >= 0 ? 1 : -1;
            double off = (target.shipType.beam + ship.shipType.beam) / 2.0 + 0.6;
            aim = target.toWorld(off * lashSide, 0, 0);
            if (aim.distanceToSqr(ship.position()) < 6.5 * 6.5 && Math.abs(Mth.wrapDegrees(target.getYRot() - ship.getYRot())) < 50) {
                lash(level, ship, target);
                return;
            }
        } else {
            aim = p.position();
        }
        double dx = aim.x - ship.getX(), dz = aim.z - ship.getZ();
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        steerTo(level, ship, want, false);
    }

    private void lash(ServerLevel level, ShipEntity ship, ShipEntity target) {
        mode = Mode.GRAPPLED;
        lashedTo = target;
        escapeTicks = 0;
        helm = 0;
        ship.setSail(ShipEntity.SAIL_FURLED);
        level.playSound(null, ship.getX(), ship.getY() + 1, ship.getZ(), SoundEvents.CHAIN_PLACE, SoundSource.NEUTRAL, 2f, 0.7f);
        level.playSound(null, ship.getX(), ship.getY() + 1, ship.getZ(), SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 2f, 0.6f);
        Player p = quarry == null ? null : level.getPlayerByUUID(quarry);
        if (p instanceof ServerPlayer sp) com.skycraft.core.Notifier.message(sp, Component.translatable("seas.skycraft.grappled"));
    }

    /** Lashed alongside: the hull keeps station on the quarry's beam until the lines are cut by sailing hard away. */
    private void holdAlongside(ShipEntity ship) {
        ShipEntity target = lashedTo;
        if (target == null || target.isRemoved() || target.getSinking() > 0) {
            mode = Mode.COURSE;
            lashedTo = null;
            return;
        }
        double speed = target.getDeltaMovement().horizontalDistance();
        if (speed < 1e-3 && target.getControllingPassenger() != null) {
            speed = Math.hypot(target.getX() - target.xo, target.getZ() - target.zo);
        }
        escapeTicks = speed > 0.33 ? escapeTicks + 1 : Math.max(0, escapeTicks - 2);
        if (escapeTicks > 80 || prize) {
            mode = Mode.COURSE;
            lashedTo = null;
            ship.level().playSound(null, ship.getX(), ship.getY() + 1, ship.getZ(), SoundEvents.LEASH_KNOT_BREAK, SoundSource.NEUTRAL, 2f, 0.6f);
            return;
        }
        double off = (target.shipType.beam + ship.shipType.beam) / 2.0 + 0.6;
        Vec3 at = target.toWorld(off * lashSide, 0, 0);
        double y = target.getY() + target.shipType.draft - ship.shipType.draft;
        ship.setDeltaMovement(Vec3.ZERO);
        ship.setPos(at.x, y, at.z);
        ship.setYRot(target.getYRot());
    }

    /** The quarry's ship when grappled (crew goals board it). */
    @Nullable
    public ShipEntity lashedTo() {
        return mode == Mode.GRAPPLED ? lashedTo : null;
    }

    public boolean isPrize() {
        return prize;
    }

    /** Called when a player strikes a member of this ship's crew: peaceful ships turn on them. */
    public void provoked(ServerLevel level, ShipEntity ship, LivingEntity attacker) {
        if (!(attacker instanceof Player p)) return;
        for (UUID id : crew) {
            if (level.getEntity(id) instanceof NpcEntity npc) npc.setHuntTarget(p.getUUID());
        }
    }

    /** Rough check used by the spawner: is this level's dimension the overworld. */
    static boolean overworld(Level level) {
        return level.dimension() == Level.OVERWORLD;
    }
}
