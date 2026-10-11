package com.skycraft.sailing.client;

import com.skycraft.Skycraft;
import com.skycraft.network.SkyNetwork;
import com.skycraft.sailing.DeckPhysics;
import com.skycraft.sailing.SailingPackets;
import com.skycraft.sailing.ShipEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Client side of the walkable decks ({@link DeckPhysics}): the local player's deck collision right after its own
 * movement (so the position it sends is already on the deck), the end-of-tick carry, its deck report to the server,
 * deck footsteps; and everyone else aboard (crews, other players) placed from the server's ship-local positions
 * against this client's view of the ship, with their walking animated from their motion on deck rather than the
 * ship's.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class DeckClient {
    /** Someone aboard, placed from {@link SailingPackets.DeckSync}. */
    private static final class Binding {
        int ship;
        float tx, ty, tz, tyaw, thead;
        double cx, cy, cz;
        float cyaw, chead;
        boolean init;
        long seen;
        // last placed pose (for render interpolation)
        double px, py, pz;
        float pyaw, pbody, phead;
        boolean placed;
    }

    private static final Map<Integer, Binding> BOUND = new HashMap<>();
    private static long ticks;
    private static int reportedShip = -1;

    static {
        DeckPhysics.jumpInput = p -> p instanceof LocalPlayer lp && lp.input != null && lp.input.jumping;
    }

    private DeckClient() {}

    /** Network thread hand-off (already on the main thread: SkyNetwork consumes on the main thread). */
    public static void onSync(SailingPackets.DeckSync m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (SailingPackets.DeckSync.Entry e : m.entries()) {
            if (mc.player != null && e.entity() == mc.player.getId()) continue; // we place ourselves
            Binding b = BOUND.computeIfAbsent(e.entity(), k -> new Binding());
            if (b.ship != m.ship()) {
                b.init = false;
                b.placed = false;
            }
            b.ship = m.ship();
            b.tx = e.lx();
            b.ty = e.ly();
            b.tz = e.lz();
            b.tyaw = e.yaw();
            b.thead = e.headYaw();
            b.seen = ticks;
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!(event.player instanceof LocalPlayer player) || !player.level().isClientSide) return;
        if (event.phase == TickEvent.Phase.START) {
            // sneaking on a deck: vanilla's edge guard looks for world blocks under the feet and would freeze the walker
            if (player.isShiftKeyDown() && DeckPhysics.onDeck(player)) player.setOnGround(false);
            return;
        }
        DeckPhysics.collideLocal(player);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            BOUND.clear();
            reportedShip = -1;
            return;
        }
        if (mc.isPaused()) return;
        if (event.phase == TickEvent.Phase.START) {
            preShift(level);
            return;
        }
        ticks++;
        Set<Entity> bound = new HashSet<>();
        for (Iterator<Map.Entry<Integer, Binding>> it = BOUND.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Binding> en = it.next();
            Binding b = en.getValue();
            Entity e = level.getEntity(en.getKey());
            if (e == null || e.isRemoved() || e.isPassenger() || ticks - b.seen > 12 || !(level.getEntity(b.ship) instanceof ShipEntity)) {
                it.remove();
                continue;
            }
            bound.add(e);
        }
        DeckPhysics.endTick(level, bound);
        for (Map.Entry<Integer, Binding> en : BOUND.entrySet()) {
            Entity e = level.getEntity(en.getKey());
            if (e != null && level.getEntity(en.getValue().ship) instanceof ShipEntity ship) place(e, ship, en.getValue());
        }
        LocalPlayer player = mc.player;
        if (player != null) {
            report(player);
            footsteps(player);
        }
    }

    /**
     * Before entities tick: shift each bound entity by the ship's motion of the last tick and aim its interpolation at
     * its next deck position in that shifted frame, so vanilla's walk animation sees only its steps on deck.
     */
    private static void preShift(ClientLevel level) {
        for (Map.Entry<Integer, Binding> en : BOUND.entrySet()) {
            Binding b = en.getValue();
            if (!b.placed) continue;
            Entity e = level.getEntity(en.getKey());
            if (e == null || !(level.getEntity(b.ship) instanceof ShipEntity ship)) continue;
            double dx = ship.getX() - ship.xo, dy = ship.getY() - ship.yo, dz = ship.getZ() - ship.zo;
            float dyaw = Mth.wrapDegrees(ship.getYRot() - ship.yRotO);
            Vec3 predicted = ship.toWorld(b.cx + (b.tx - b.cx) * 0.5, b.cy + (b.ty - b.cy) * 0.5, b.cz + (b.tz - b.cz) * 0.5,
                    ship.getX() + dx, ship.getY() + dy, ship.getZ() + dz, ship.getYRot() + dyaw);
            e.setPos(e.getX() + dx, e.getY() + dy, e.getZ() + dz);
            e.lerpTo(predicted.x, predicted.y, predicted.z, ship.getYRot() + dyaw + b.cyaw, e.getXRot(), 1, false);
        }
    }

    private static void place(Entity e, ShipEntity ship, Binding b) {
        if (!b.init) {
            b.cx = b.tx;
            b.cy = b.ty;
            b.cz = b.tz;
            b.cyaw = b.tyaw;
            b.chead = b.thead;
            b.init = true;
        } else {
            double ddx = b.tx - b.cx, ddy = b.ty - b.cy, ddz = b.tz - b.cz;
            if (ddx * ddx + ddy * ddy + ddz * ddz > 16) {
                b.cx = b.tx;
                b.cy = b.ty;
                b.cz = b.tz;
            } else {
                b.cx += ddx * 0.5;
                b.cy += ddy * 0.5;
                b.cz += ddz * 0.5;
            }
            b.cyaw += Mth.wrapDegrees(b.tyaw - b.cyaw) * 0.5f;
            b.chead += Mth.wrapDegrees(b.thead - b.chead) * 0.6f;
        }
        Vec3 w = ship.toWorld(b.cx, b.cy, b.cz);
        float yaw = ship.getYRot() + b.cyaw;
        float head = ship.getYRot() + b.chead;
        e.setPos(w.x, w.y, w.z);
        e.setYRot(yaw);
        if (b.placed) {
            // interpolate from where we drew it last tick, not from the vanilla in-between position
            e.xo = e.xOld = b.px;
            e.yo = e.yOld = b.py;
            e.zo = e.zOld = b.pz;
            e.yRotO = b.pyaw;
        }
        if (e instanceof LivingEntity le) {
            le.yHeadRot = head;
            float body = le.yBodyRot;
            // keep the body within the usual 75 degrees of the head, like vanilla's head turn
            float off = Mth.wrapDegrees(head - body);
            if (Math.abs(off) > 75f) body = head - Math.signum(off) * 75f;
            le.yBodyRot = body;
            if (b.placed) {
                le.yHeadRotO = b.phead;
                le.yBodyRotO = b.pbody;
            }
            b.pbody = body;
            b.phead = head;
        }
        e.setOnGround(true);
        b.px = w.x;
        b.py = w.y;
        b.pz = w.z;
        b.pyaw = yaw;
        b.placed = true;
    }

    /** Tells the server where we stand on a deck (every tick while aboard), or that we left it. */
    private static void report(LocalPlayer player) {
        ShipEntity ship = player.isPassenger() ? null : DeckPhysics.shipOf(player);
        if (ship == null) {
            if (reportedShip >= 0) SkyNetwork.sendToServer(new SailingPackets.DeckReport(-1, 0, 0, 0, 0, 0));
            reportedShip = -1;
            return;
        }
        Vec3 l = ship.toLocal(player.getX(), player.getY(), player.getZ());
        SkyNetwork.sendToServer(new SailingPackets.DeckReport(ship.getId(), (float) l.x, (float) l.y, (float) l.z,
                Mth.wrapDegrees(player.getYRot() - ship.getYRot()), Mth.wrapDegrees(player.getYHeadRot() - ship.getYRot())));
        reportedShip = ship.getId();
    }

    private static void footsteps(LocalPlayer player) {
        double d = player.walkDist - player.walkDistO;
        if (d <= 0 || !DeckPhysics.stepSound(player, d * 1.6)) return;
        player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), SoundEvents.WOOD_STEP, SoundSource.PLAYERS,
                0.35f, 0.9f + player.getRandom().nextFloat() * 0.2f, false);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BOUND.clear();
        reportedShip = -1;
    }
}
