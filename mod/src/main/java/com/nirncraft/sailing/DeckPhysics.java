package com.nirncraft.sailing;

import com.nirncraft.sailing.ShipDeck.Box;
import com.nirncraft.sailing.ShipDeck.Deck;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Walkable ships: people, crews, animals and dropped items stand and walk on a ship's decks while it sails (a moving
 * platform in the manner of Create's contraptions, written for NirnCraft's ships).
 *
 * <p><b>How it works.</b> Each ship remembers its pose at the start of the tick (its <i>carry frame</i>,
 * {@link ShipEntity#carryX}). During the tick everyone moves by their own physics in that frame; afterwards, on each
 * logical side, every entity near a ship is taken into the ship's local space, collided against the deck boxes
 * ({@link ShipDeck}: floors and stairs that can be stepped onto, gunwales, castles, the mast and the boarding nets that
 * can be climbed, the crow's nest floor that a climber passes up through), and the ones standing on a deck (or still
 * in the air over it after a jump) are carried along with the ship's translation and its turn about the ship's
 * origin, their facing turning with it. Then the ship's carry frame catches up with its pose.</p>
 *
 * <p><b>Who simulates whom.</b> The server runs this for mobs, items and orbs (it is authoritative for them) and sends
 * their deck positions to the clients ({@link SailingPackets.DeckSync}), which place them relative to their own view
 * of the ship, so a crew never drifts from the hull it stands on. Each client runs it for its own player (predicted,
 * no rubber-banding: the collision happens before the client sends its position) and reports its deck position
 * ({@link SailingPackets.DeckReport}); the server validates the report, keeps the player from being kicked for
 * "flying" over open water, and relays it to the others. Two ships side by side are both collided against, so you
 * can step or jump from one deck to the other; whichever deck holds you carries you.</p>
 */
public final class DeckPhysics {
    /** A rider who jumped or fell keeps the ship's way for this long while still over its deck. */
    private static final int AIRBORNE_CARRY = 40;
    /** Player deck reports older than this are ignored. */
    private static final int REPORT_TTL = 20;

    /** What we know about one entity's relation to the ships, per logical side. */
    public static final class Rider {
        @Nullable
        ShipEntity ship;
        /** Feet height in {@link #ship}'s frame last tick (NaN when unknown). */
        double prevLy = Double.NaN;
        boolean supported;
        int airborne;
        double walked;
    }

    /** A player's own report of where it stands (server side). */
    public record Report(int ship, float lx, float ly, float lz, float yaw, float headYaw, long tick) {}

    private static final class Side {
        final Set<ShipEntity> ships = Collections.newSetFromMap(new WeakHashMap<>());
        final Map<Entity, Rider> riders = new WeakHashMap<>();
    }

    private static final Side CLIENT = new Side();
    private static final Side SERVER = new Side();
    private static final Map<UUID, Report> REPORTS = new HashMap<>();

    private DeckPhysics() {}

    private static Side side(Level level) {
        return level.isClientSide() ? CLIENT : SERVER;
    }

    static void track(ShipEntity ship) {
        side(ship.level()).ships.add(ship);
    }

    static void untrack(ShipEntity ship) {
        side(ship.level()).ships.remove(ship);
    }

    /** The ships known on this side of {@code level} (a copy). */
    public static List<ShipEntity> ships(Level level) {
        List<ShipEntity> out = new ArrayList<>();
        for (ShipEntity s : side(level).ships) if (s.level() == level && !s.isRemoved()) out.add(s);
        return out;
    }

    // ------------------------------------------------------------------ frames

    /** World point to the ship's local space in its carry frame (the pose at the start of this tick). */
    public static Vec3 toLocal(ShipEntity s, double x, double y, double z) {
        double r = s.carryYaw * Mth.DEG_TO_RAD;
        double sn = Math.sin(r), cs = Math.cos(r);
        double dx = x - s.carryX, dz = z - s.carryZ;
        return new Vec3(dx * cs + dz * sn, y - s.carryY, -dx * sn + dz * cs);
    }

    /** Local point to the world in the ship's carry frame. */
    public static Vec3 fromLocal(ShipEntity s, double lx, double ly, double lz) {
        return s.toWorld(lx, ly, lz, s.carryX, s.carryY, s.carryZ, s.carryYaw);
    }

    /** Local point to the world at the ship's current pose. */
    public static Vec3 toWorldNow(ShipEntity s, double lx, double ly, double lz) {
        return s.toWorld(lx, ly, lz);
    }

    /** A ship's local point (current pose) to the world; local yaw to world yaw. */
    public static float worldYaw(ShipEntity s, float localYaw) {
        return s.getYRot() + localYaw;
    }

    /** World-space box that holds everything a ship's deck can touch. */
    public static AABB reach(ShipEntity s) {
        Deck d = ShipDeck.of(s.shipType);
        double r = Math.max(Math.max(Math.abs(d.minZ), Math.abs(d.maxZ)), Math.max(Math.abs(d.minX), Math.abs(d.maxX))) + 1.5;
        double x = s.carryReady ? s.carryX : s.getX(), y = s.carryReady ? s.carryY : s.getY(), z = s.carryReady ? s.carryZ : s.getZ();
        return new AABB(x - r, y + d.minY - 2.0, z - r, x + r, y + d.maxY + 2.5, z + r);
    }

    // ------------------------------------------------------------------ the riders

    @Nullable
    public static Rider rider(Entity e) {
        return side(e.level()).riders.get(e);
    }

    /** The ship whose deck holds (or just held) this entity on this side, or null. */
    @Nullable
    public static ShipEntity shipOf(Entity e) {
        Rider r = rider(e);
        return r == null || r.ship == null || r.ship.isRemoved() ? null : r.ship;
    }

    /** True while the entity stands on a ship's deck (this side's view). */
    public static boolean onDeck(Entity e) {
        Rider r = rider(e);
        return r != null && r.ship != null && r.supported && !r.ship.isRemoved();
    }

    /** Whether the physics on this side moves this entity on decks. */
    static boolean simulated(Entity e, boolean client) {
        if (e instanceof ShipEntity || e instanceof ShipPart || e.isPassenger() || e.isVehicle() || e.noPhysics || e.isSpectator()) return false;
        if (e instanceof Player p) {
            if (p.getAbilities().flying) return false;
            return client && p.isLocalPlayer();
        }
        if (client) return e instanceof ItemEntity || e instanceof ExperienceOrb;
        return e instanceof LivingEntity || e instanceof ItemEntity || e instanceof ExperienceOrb;
    }

    /** Whether a creature can climb the mast and the boarding nets (swimmers and fish don't). */
    private static boolean climber(Entity e) {
        if (!(e instanceof LivingEntity)) return false;
        if (e instanceof WaterAnimal) return false;
        if (e instanceof Mob m && m.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation) return false;
        return true;
    }

    private static final class Res {
        boolean supported;
        boolean climb;
        boolean nearClimb;
        @Nullable
        ShipEntity support;
        double supportLy;
    }

    /**
     * Collides one entity against every nearby ship's deck (each in its carry frame) and updates its rider state.
     * Returns the ship that should carry it this tick, or null.
     */
    @Nullable
    static ShipEntity collide(Entity e, List<ShipEntity> near, boolean wantsClimb) {
        Side side = side(e.level());
        Rider r = side.riders.computeIfAbsent(e, k -> new Rider());
        double[] p = {e.getX(), e.getY(), e.getZ()};
        Vec3 dm = e.getDeltaMovement();
        double[] v = {dm.x, dm.y, dm.z};
        Res out = new Res();
        for (ShipEntity s : near) {
            if (s.isRemoved() || !s.carryReady) continue;
            resolve(e, s, p, v, r, out, wantsClimb);
        }
        boolean moved = p[0] != e.getX() || p[1] != e.getY() || p[2] != e.getZ();
        if (moved) e.setPos(p[0], p[1], p[2]);
        if (v[0] != dm.x || v[1] != dm.y || v[2] != dm.z) e.setDeltaMovement(v[0], v[1], v[2]);

        ShipEntity carrier = out.support;
        if (out.supported) {
            e.setOnGround(true);
            if (e.fallDistance > 0) {
                if (!e.level().isClientSide() && !(e instanceof Player) && e.fallDistance > 3.0f && !r.supported) {
                    e.causeFallDamage(e.fallDistance, 1.0f, e.damageSources().fall());
                }
                e.resetFallDistance();
            }
            r.airborne = 0;
        } else if (out.nearClimb) {
            e.resetFallDistance();
        }
        if (carrier == null && r.ship != null && !r.ship.isRemoved() && r.airborne < AIRBORNE_CARRY && near.contains(r.ship)) {
            Vec3 l = toLocal(r.ship, e.getX(), e.getY(), e.getZ());
            Deck d = ShipDeck.of(r.ship.shipType);
            if (d.over(l.x, l.z, 0.6) && l.y > d.minY - 1.0 && l.y < d.maxY + 3.0 && !e.isInWater()) carrier = r.ship;
        }
        if (!out.supported) r.airborne++;
        r.supported = out.supported;
        r.ship = carrier;
        r.prevLy = carrier == null ? Double.NaN : toLocal(carrier, e.getX(), e.getY(), e.getZ()).y;
        return carrier;
    }

    private static void resolve(Entity e, ShipEntity s, double[] p, double[] v, Rider r, Res out, boolean wantsClimb) {
        Deck d = ShipDeck.of(s.shipType);
        double yaw = s.carryYaw * Mth.DEG_TO_RAD;
        double sn = Math.sin(yaw), cs = Math.cos(yaw);
        double dx = p[0] - s.carryX, dz = p[2] - s.carryZ;
        double lx = dx * cs + dz * sn, lz = -dx * sn + dz * cs, ly = p[1] - s.carryY;
        double hw = e.getBbWidth() / 2.0, h = e.getBbHeight();
        if (!d.over(lx, lz, hw + 0.6) || ly > d.maxY + 0.6 || ly + h < d.minY - 0.6) return;
        double vlx = v[0] * cs + v[2] * sn, vlz = -v[0] * sn + v[2] * cs, vly = v[1];
        double prevLy = r.ship == s ? r.prevLy : Double.NaN;
        boolean grounded = r.supported || e.onGround();
        float step = e.maxUpStep();
        boolean changed = false;

        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (Box b : d.boxes) {
                if (lx + hw <= b.x0() || lx - hw >= b.x1() || lz + hw <= b.z0() || lz - hw >= b.z1() || ly + h <= b.y0() || ly >= b.y1()) continue;
                double up = b.y1() - ly;
                boolean wasAbove = !Double.isNaN(prevLy) && prevLy >= b.y1() - 0.05;
                if (b.has(ShipDeck.PASS_UP)) {
                    if (wasAbove && vly <= 0.01 && up < 0.6) {
                        ly = b.y1();
                        if (vly < 0) vly = 0;
                        out.supported = true;
                        out.support = s;
                        moved = true;
                    }
                    continue;
                }
                if (!b.has(ShipDeck.SOLID)) continue;
                double toMinX = (lx + hw) - b.x0(), toMaxX = b.x1() - (lx - hw);
                double toMinZ = (lz + hw) - b.z0(), toMaxZ = b.z1() - (lz - hw);
                double hx = Math.min(toMinX, toMaxX), hz = Math.min(toMinZ, toMaxZ);
                double horiz = Math.min(hx, hz);
                double down = (ly + h) - b.y0();
                boolean headBelow = !Double.isNaN(prevLy) && prevLy + h <= b.y0() + 0.05;
                if (up <= 0.12 + Math.max(0, -vly) || wasAbove && up <= 1.5
                        || b.has(ShipDeck.STEP) && grounded && up <= step + 1e-3 && up < 1.0) {
                    ly = b.y1();
                    if (vly < 0) vly = 0;
                    out.supported = true;
                    out.support = s;
                } else if (headBelow && down < horiz + 0.3 && down <= 0.6 + Math.max(0, vly)) {
                    ly = b.y0() - h;
                    if (vly > 0) vly = 0;
                } else {
                    double px = 0, pz = 0;
                    if (hx <= hz) {
                        if (toMinX < toMaxX) {
                            px = -toMinX;
                            if (vlx > 0) vlx = 0;
                        } else {
                            px = toMaxX;
                            if (vlx < 0) vlx = 0;
                        }
                    } else {
                        if (toMinZ < toMaxZ) {
                            pz = -toMinZ;
                            if (vlz > 0) vlz = 0;
                        } else {
                            pz = toMaxZ;
                            if (vlz < 0) vlz = 0;
                        }
                    }
                    lx += px;
                    lz += pz;
                    // climbable from the outside only (the nets hang over the side; the mast from any side): walking
                    // into the gunwale from the deck never climbs it
                    if (b.has(ShipDeck.CLIMB) && px * lx + pz * lz > 0) out.climb = true;
                }
                moved = true;
            }
            if (!moved) break;
            changed = true;
        }
        // standing on a surface without sinking into it this tick (the deck held us up last tick)
        if (!out.supported && vly <= 0.01) {
            for (Box b : d.boxes) {
                if (!b.has(ShipDeck.SOLID) && !b.has(ShipDeck.PASS_UP)) continue;
                if (lx + hw <= b.x0() || lx - hw >= b.x1() || lz + hw <= b.z0() || lz - hw >= b.z1()) continue;
                if (Math.abs(ly - b.y1()) < 0.035) {
                    ly = b.y1();
                    if (vly < 0) vly = 0;
                    out.supported = true;
                    out.support = s;
                    changed = true;
                    break;
                }
            }
        }
        // climbing the mast and the boarding nets
        if (climber(e)) {
            boolean near = out.climb;
            if (!near && !out.supported) {
                for (Box b : d.boxes) {
                    if (!b.has(ShipDeck.CLIMB)) continue;
                    if (lx + hw + 0.12 <= b.x0() || lx - hw - 0.12 >= b.x1() || lz + hw + 0.12 <= b.z0() || lz - hw - 0.12 >= b.z1()
                            || ly + h <= b.y0() || ly >= b.y1() + 0.2) continue;
                    boolean mast = b.x1() - b.x0() < 0.6f;
                    if (!mast && Math.abs(lx) < Math.max(Math.abs(b.x0()), Math.abs(b.x1())) - 0.05) continue; // inside the hull
                    near = true;
                    break;
                }
            }
            if (near) {
                out.nearClimb = true;
                boolean up = out.climb || wantsClimb || (!(e instanceof Player) && e.isInWater());
                if (up && !out.supported || up && out.climb) {
                    vly = e.isShiftKeyDown() ? 0 : 0.2;
                    changed = true;
                } else if (vly < -0.15) {
                    vly = -0.15;
                    changed = true;
                }
            }
        }
        if (out.support == s) out.supportLy = ly;
        if (!changed) return;
        p[0] = s.carryX + lx * cs - lz * sn;
        p[1] = s.carryY + ly;
        p[2] = s.carryZ + lx * sn + lz * cs;
        v[0] = vlx * cs - vlz * sn;
        v[1] = vly;
        v[2] = vlx * sn + vlz * cs;
    }

    /**
     * Moves an entity along with its ship's motion since the carry frame: the translation plus the turn about the
     * ship's origin; its facing and momentum turn with the ship. Blocks in the world (a pier, the shore) stop it.
     */
    static void carry(Entity e, ShipEntity s) {
        float dyaw = Mth.wrapDegrees(s.getYRot() - s.carryYaw);
        Vec3 l = toLocal(s, e.getX(), e.getY(), e.getZ());
        Vec3 w = s.toWorld(l.x, l.y, l.z);
        Vec3 delta = w.subtract(e.position());
        if (delta.lengthSqr() < 1e-10 && Math.abs(dyaw) < 1e-4) return;
        if (delta.lengthSqr() > 1e-10) {
            Vec3 free = Entity.collideBoundingBox(e, delta, e.getBoundingBox(), e.level(), List.of());
            e.setPos(e.getX() + free.x, e.getY() + free.y, e.getZ() + free.z);
        }
        if (Math.abs(dyaw) > 1e-4) {
            e.setYRot(e.getYRot() + dyaw);
            if (e instanceof LivingEntity le) {
                le.yBodyRot += dyaw;
                le.yHeadRot += dyaw;
            }
            Vec3 dm = e.getDeltaMovement();
            double r = dyaw * Mth.DEG_TO_RAD, sn = Math.sin(r), cs = Math.cos(r);
            e.setDeltaMovement(dm.x * cs - dm.z * sn, dm.y, dm.x * sn + dm.z * cs);
        }
        Rider rider = rider(e);
        if (rider != null && rider.ship == s) rider.prevLy = l.y;
    }

    /** The ships whose reach overlaps the entity's box. */
    static List<ShipEntity> shipsNear(Entity e) {
        List<ShipEntity> out = new ArrayList<>(2);
        AABB box = e.getBoundingBox();
        for (ShipEntity s : side(e.level()).ships) {
            if (s.level() == e.level() && !s.isRemoved() && s.carryReady && reach(s).intersects(box)) out.add(s);
        }
        return out;
    }

    /**
     * One side's pass at the end of a tick: collide and carry everything this side simulates, then let every ship's
     * carry frame catch up. {@code bound} (client) are the remote entities placed from server deck positions instead.
     */
    public static void endTick(Level level, @Nullable Set<Entity> bound) {
        Side side = side(level);
        if (side.ships.isEmpty()) return;
        boolean client = level.isClientSide();
        Map<Entity, List<ShipEntity>> todo = new LinkedHashMap<>();
        for (ShipEntity s : List.copyOf(side.ships)) {
            if (s.level() != level || s.isRemoved()) continue;
            if (!s.carryReady) {
                s.syncCarryPose();
                continue;
            }
            for (Entity e : level.getEntities(s, reach(s), x -> simulated(x, client))) {
                if (bound != null && bound.contains(e)) continue;
                todo.computeIfAbsent(e, k -> new ArrayList<>(2)).add(s);
            }
        }
        // riders who drifted out of every reach this tick are forgotten
        for (Map.Entry<Entity, List<ShipEntity>> en : todo.entrySet()) {
            Entity e = en.getKey();
            boolean climbWish = client && e instanceof Player p && isJumping(p);
            ShipEntity carrier = collide(e, en.getValue(), climbWish);
            if (carrier != null) carry(e, carrier);
        }
        for (Entity e : List.copyOf(side.riders.keySet())) {
            if (!todo.containsKey(e) && (bound == null || !bound.contains(e))) {
                Rider r = side.riders.get(e);
                if (r != null && (e.isRemoved() || !simulated(e, client) || e.level() != level)) side.riders.remove(e);
                else if (r != null) {
                    r.ship = null;
                    r.supported = false;
                }
            }
        }
        for (ShipEntity s : side.ships) if (s.level() == level && !s.isRemoved()) s.syncCarryPose();
    }

    /** Client hook: whether the local player is pressing jump (set by the client, read through this indirection). */
    public static java.util.function.Predicate<Player> jumpInput = p -> false;

    private static boolean isJumping(Player p) {
        return jumpInput.test(p);
    }

    /** Client: the local player's collision right after its own movement (before it sends its position). */
    public static void collideLocal(Player player) {
        if (!simulated(player, true)) {
            Rider r = rider(player);
            if (r != null) {
                r.ship = null;
                r.supported = false;
            }
            return;
        }
        List<ShipEntity> near = shipsNear(player);
        if (near.isEmpty()) return;
        collide(player, near, isJumping(player));
    }

    /** Walking distance on deck since the last step sound (client), for the deck's footstep sounds. */
    public static boolean stepSound(Player player, double dist) {
        Rider r = rider(player);
        if (r == null || !r.supported) return false;
        r.walked += dist;
        if (r.walked < 1.7) return false;
        r.walked = 0;
        return true;
    }

    // ------------------------------------------------------------------ server: player reports

    /** Server side of {@link SailingPackets.DeckReport}: validated, then used for the crew, flight checks and relays. */
    static void report(ServerPlayer player, int shipId, float lx, float ly, float lz, float yaw, float headYaw) {
        if (shipId < 0) {
            REPORTS.remove(player.getUUID());
            return;
        }
        if (!(player.level().getEntity(shipId) instanceof ShipEntity ship) || player.isPassenger()) {
            REPORTS.remove(player.getUUID());
            return;
        }
        Deck d = ShipDeck.of(ship.shipType);
        if (!Float.isFinite(lx) || !Float.isFinite(ly) || !Float.isFinite(lz) || !d.over(lx, lz, 1.0)
                || ly < d.minY - 1.0 || ly > d.maxY + 2.5) {
            REPORTS.remove(player.getUUID());
            return;
        }
        // the report must agree with where the server has the player (allowing for the ship's speed and lag)
        Vec3 at = ship.toWorld(lx, ly, lz);
        double slack = 6.0 + ship.getDeltaMovement().length() * 12.0;
        if (at.distanceToSqr(player.position()) > slack * slack) {
            REPORTS.remove(player.getUUID());
            return;
        }
        REPORTS.put(player.getUUID(), new Report(shipId, lx, ly, lz, Mth.wrapDegrees(yaw), Mth.wrapDegrees(headYaw), player.level().getGameTime()));
    }

    /** A player's fresh deck report, or null. */
    @Nullable
    public static Report reportOf(Player player) {
        Report r = REPORTS.get(player.getUUID());
        if (r == null || player.level().getGameTime() - r.tick() > REPORT_TTL) return null;
        return r;
    }

    /**
     * Where the server should consider an entity to be for fighting on decks: a player's reported deck position (on
     * the server's ship), everyone else's own position.
     */
    public static Vec3 combatPos(LivingEntity e) {
        if (e instanceof Player p && !e.level().isClientSide()) {
            Report r = reportOf(p);
            if (r != null && e.level().getEntity(r.ship()) instanceof ShipEntity s) return s.toWorld(r.lx(), r.ly(), r.lz());
        }
        return e.position();
    }

    /** The ship a player (by its report) or any other entity (by the physics) stands on, server side. */
    @Nullable
    public static ShipEntity deckShip(Entity e) {
        if (e instanceof Player p && !e.level().isClientSide()) {
            Report r = reportOf(p);
            return r != null && e.level().getEntity(r.ship()) instanceof ShipEntity s ? s : null;
        }
        return shipOf(e);
    }

    /**
     * Server, every tick per player: someone standing on a deck over open water isn't flying. Vanilla counts ticks a
     * player hovers with no blocks around and kicks it after four seconds (unless the server allows flight); the deck
     * is not made of blocks, so the count is reset while the player's deck report holds.
     */
    static void serverPlayerTick(ServerPlayer player) {
        Report r = reportOf(player);
        if (r == null || player.isPassenger()) return;
        player.connection.resetFlyingTicks();
    }

    /** Server: the deck positions of everyone aboard a ship (crew from the physics, players from their reports). */
    static List<SailingPackets.DeckSync.Entry> deckEntries(ServerLevel level, ShipEntity ship) {
        List<SailingPackets.DeckSync.Entry> out = new ArrayList<>();
        Map<Entity, Boolean> seen = new IdentityHashMap<>();
        for (Map.Entry<Entity, Rider> en : SERVER.riders.entrySet()) {
            Entity e = en.getKey();
            Rider r = en.getValue();
            if (r.ship != ship || e.isRemoved() || e.level() != level || !(e instanceof LivingEntity le)) continue;
            Vec3 l = toLocal(ship, e.getX(), e.getY(), e.getZ());
            out.add(new SailingPackets.DeckSync.Entry(e.getId(), (float) l.x, (float) l.y, (float) l.z,
                    Mth.wrapDegrees(e.getYRot() - ship.getYRot()), Mth.wrapDegrees(le.yHeadRot - ship.getYRot())));
            seen.put(e, Boolean.TRUE);
        }
        for (ServerPlayer p : level.players()) {
            Report r = reportOf(p);
            if (r == null || r.ship() != ship.getId() || seen.containsKey(p)) continue;
            out.add(new SailingPackets.DeckSync.Entry(p.getId(), r.lx(), r.ly(), r.lz(), r.yaw(), r.headYaw()));
        }
        return out;
    }

    /** Server: drops a leaving player's report. */
    static void forget(Player player) {
        REPORTS.remove(player.getUUID());
    }
}
