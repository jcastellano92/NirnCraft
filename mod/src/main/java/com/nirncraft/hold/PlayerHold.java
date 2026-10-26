package com.nirncraft.hold;

import com.nirncraft.NirnCraft;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The player hold (docs/ATLAS-PLAN.md section 0.9; docs/PLAYER-HOLD.md): from login until the player's client reports
 * that its loading is complete, the player is not in play. Server-authoritative, public APIs and events only (no mixins,
 * no access transformers). What holds, and by which mechanism:
 * <ul>
 *   <li><b>Invisible to other players, not tracked:</b> spectator game mode. Vanilla's own
 *       {@code ServerPlayer.broadcastToPlayer} refuses to send a spectator to non-spectators, so nobody is sent the
 *       player's entity (the tracker is refreshed with {@code ServerChunkCache.move}). The entity's invisible flag is
 *       set as well.</li>
 *   <li><b>Not in the tab list:</b> a {@code ClientboundPlayerInfoRemovePacket} to every other player (and to anyone who
 *       joins meanwhile); the entry is sent again on release.</li>
 *   <li><b>Untargetable:</b> vanilla's targeting never selects a spectator ({@code canBeSeenAsEnemy}); {@code
 *       LivingChangeTargetEvent} is cancelled besides, for any mod's mobs.</li>
 *   <li><b>Invulnerable:</b> abilities.invulnerable, and {@code LivingIncomingDamageEvent} cancelled (also the held
 *       player's own attacks).</li>
 *   <li><b>Frozen:</b> abilities {@code flying} and {@code mayfly} off, no gravity, position pinned every tick with a
 *       teleport back, velocity, fall distance, air and fire reset (no falling, drowning or burning); teleports and
 *       dimension travel cancelled.</li>
 *   <li><b>No interaction:</b> abilities {@code mayBuild} off; right/left click on blocks, items and entities, attacks,
 *       block breaking and placing, and commands cancelled.</li>
 *   <li><b>Triggers nothing:</b> spectators are skipped by pressure plates ({@code EntitySelector.NO_SPECTATORS}), mob
 *       spawning ({@code playerIsCloseEnoughForSpawning}) and aggro; the entity does not collide ({@code noPhysics}).</li>
 * </ul>
 * The player's real game mode, gravity and invisibility are kept in the player's persistent data while held, so a crash
 * or a disconnect never leaves a spectator behind; they are restored on release and on logout.
 */
@EventBusSubscriber(modid = NirnCraft.MODID)
public final class PlayerHold {
    /** Off with {@code -Dnirncraft.hold=false} (the server's JVM; clients then simply never see a hold). */
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("nirncraft.hold"));
    /**
     * Hold on arriving somewhere (teleport, dimension, respawn, jump). On by default; off by default in a harness run
     * (its scenes teleport the camera all the time and time the loading themselves); the hold's own tests switch it on
     * with {@code -Dnirncraft.hold.arrival=true}.
     */
    public static final boolean ARRIVAL = Boolean.parseBoolean(System.getProperty("nirncraft.hold.arrival",
            System.getProperty("nirncraft.harness") == null ? "true" : "false"));
    private static final String TAG = "nirncraft_hold";
    private static final long LOG_EVERY_TICKS = 20L * 30;

    /** One held player. */
    private static final class Held {
        final long token;
        final String reason;
        final long sinceTick;
        final long sinceMs = System.currentTimeMillis();
        final ResourceKey<Level> dim;
        Vec3 pos;           // (where the player is pinned: their place, or below it where they will land: HoldLanding)
        final Vec3 origin;  // (where the hold began: the release puts them back there)
        final GameType prevMode;
        final boolean prevNoGravity, prevInvisible, prevMayfly, prevFlying, prevMayBuild, prevInvulnerable, prevInstabuild;
        @Nullable Runnable onRelease;
        long lastPin = -1_000_000L, lastLog;
        int pins;

        Held(long token, String reason, ServerPlayer p, GameType prevMode, boolean noGravity, boolean invisible, @Nullable Runnable onRelease) {
            this.token = token;
            this.reason = reason;
            this.sinceTick = p.level().getGameTime();
            this.dim = p.level().dimension();
            this.pos = p.position();
            this.origin = pos;
            this.prevMode = prevMode;
            this.prevNoGravity = noGravity;
            this.prevInvisible = invisible;
            Abilities a = p.getAbilities();
            this.prevMayfly = a.mayfly;
            this.prevFlying = a.flying;
            this.prevMayBuild = a.mayBuild;
            this.prevInvulnerable = a.invulnerable;
            this.prevInstabuild = a.instabuild;
            this.onRelease = onRelease;
            this.lastLog = sinceTick;
        }
    }

    /** A move of more than this many blocks in one server tick is an arrival somewhere new: the player is held there. */
    public static final double JUMP_BLOCKS = Double.parseDouble(System.getProperty("nirncraft.hold.jump", "64"));
    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();
    /** Where each player was on the previous tick, for the jump check. */
    private static final Map<UUID, Vec3> LAST = new ConcurrentHashMap<>();
    /** Why the teleport that is about to be seen as a jump happened (from EntityTeleportEvent), for the log and the screen. */
    private static final Map<UUID, String> WHY = new ConcurrentHashMap<>();
    private static final AtomicLong TOKENS = new AtomicLong(System.currentTimeMillis() & 0xFFFFFFFFL);

    private PlayerHold() {}

    // ------------------------------------------------------------------ queries

    /** True while this entity is a held player. Safe from any thread. */
    public static boolean isHeld(@Nullable Entity e) {
        return e != null && !HELD.isEmpty() && HELD.containsKey(e.getUUID());
    }

    public static int count() {
        return HELD.size();
    }

    /** Server ticks this player has been held (-1 when not held). */
    public static long heldTicks(ServerPlayer p) {
        Held h = HELD.get(p.getUUID());
        return h == null ? -1 : p.level().getGameTime() - h.sinceTick;
    }

    /** One line for logs and the operator command. */
    public static String describe(ServerPlayer p) {
        Held h = HELD.get(p.getUUID());
        if (h == null) return p.getGameProfile().name() + ": not held";
        return String.format("%s: held %d ticks at %.1f %.1f %.1f%s (real mode %s, pinned %d times, token %d)", p.getGameProfile().name(),
                p.level().getGameTime() - h.sinceTick, h.origin.x, h.origin.y, h.origin.z, landing(h), h.prevMode.getName(), h.pins, h.token);
    }

    // ------------------------------------------------------------------ begin

    /**
     * Holds a player who just logged in. {@code onRelease} runs when the client reports ready (or an operator releases
     * them): the first-join character select and the return to the last position. Returns false when no hold applies
     * (disabled, a stand-in without a connection): the caller then runs {@code onRelease} itself.
     */
    public static boolean beginOnLogin(ServerPlayer p, @Nullable Runnable onRelease) {
        if (!ENABLED || p.connection == null || p instanceof FakePlayer) return false;
        begin(p, onRelease, "login");
        return true;
    }

    /** Holds the player now (an operator, a test). */
    public static void begin(ServerPlayer p, @Nullable Runnable onRelease) {
        begin(p, onRelease, "operator");
    }

    /**
     * Holds a player in play who has just arrived somewhere (a teleport, a dimension change, a respawn, a jump of more
     * than {@link #JUMP_BLOCKS} blocks): the same hold as at login, at the destination, released there when the client
     * reports the arrival ready. Does nothing when the hold is off, for a stand-in, or when they are already held.
     */
    public static boolean arrive(ServerPlayer p, String reason) {
        if (!ENABLED || !ARRIVAL || p.connection == null || p instanceof FakePlayer || isHeld(p)) return false;
        begin(p, null, reason);
        return true;
    }

    /** As {@link #arrive} for a stand-in (the selftest): the same hold, without the connection checks. */
    static boolean arriveForTests(ServerPlayer p, String reason) {
        if (isHeld(p)) return false;
        begin(p, null, reason);
        return true;
    }

    /** Holds the player now. A player already held keeps their hold and their release action. */
    static void begin(ServerPlayer p, @Nullable Runnable onRelease, String reason) {
        Held old = HELD.get(p.getUUID());
        if (old != null) {
            if (onRelease != null) old.onRelease = onRelease;
            send(p, new HoldPackets.Held(true, old.token, old.reason));
            return;
        }
        // the real state: from the persisted record when a crash or disconnect left the hold on this player's data
        CompoundTag saved = p.getPersistentData().getCompoundOrEmpty(TAG);
        GameType mode = p.gameMode();
        boolean noGravity = p.isNoGravity(), invisible = p.isInvisible();
        if (!saved.isEmpty()) {
            mode = GameType.byName(saved.getStringOr("mode", mode.getName()), mode);
            noGravity = saved.getBooleanOr("gravity", noGravity);
            invisible = saved.getBooleanOr("invisible", invisible);
        } else {
            CompoundTag t = new CompoundTag();
            t.putString("mode", mode.getName());
            t.putBoolean("gravity", noGravity);
            t.putBoolean("invisible", invisible);
            p.getPersistentData().put(TAG, t);
        }
        Held h = new Held(TOKENS.incrementAndGet(), reason, p, mode, noGravity, invisible, onRelease);
        // (in the air, about to fall once released: the camera waits where they will land, so that the loading there is
        // what the client waits for; the release puts them back here)
        Vec3 land = HoldLanding.of(p, mode, h.prevMayfly, h.prevFlying, noGravity);
        if (land != null) h.pos = land;
        HELD.put(p.getUUID(), h);
        apply(p);
        if (land != null) {
            p.setPos(land.x, land.y, land.z);
            if (p.connection != null) p.connection.teleport(land.x, land.y, land.z, p.getYRot(), p.getXRot());
        }
        // not in the tab list of anyone, and not in the world of anyone
        hideFromOthers(p);
        send(p, new HoldPackets.Held(true, h.token, reason));
        LAST.put(p.getUUID(), h.pos);
        NirnCraft.LOGGER.info("hold: {} is held at {} {} {}{} until their client reports its loading complete (real mode {}) [{}]",
                p.getGameProfile().name(), fmt(h.origin.x), fmt(h.origin.y), fmt(h.origin.z), landing(h), mode.getName(), reason);
    }

    /** The hold's state on the player: everything but the tracking and the tab list. Idempotent; also the per-login re-apply. */
    private static void apply(ServerPlayer p) {
        p.setGameMode(GameType.SPECTATOR);  // not tracked by non-spectators (ServerPlayer.broadcastToPlayer), not targeted, no triggers
        Abilities a = p.getAbilities();
        a.invulnerable = true;
        a.flying = false;
        a.mayfly = false;
        a.mayBuild = false;
        a.instabuild = false;
        p.onUpdateAbilities();
        p.setNoGravity(true);
        p.setInvisible(true);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        p.setCamera(p);
    }

    private static void hideFromOthers(ServerPlayer p) {
        ServerLevel level = p.level();
        if (!(p instanceof FakePlayer)) level.getChunkSource().move(p);  // refresh the tracker: broadcastToPlayer is false for every non-spectator now
        var remove = new ClientboundPlayerInfoRemovePacket(List.of(p.getUUID()));
        for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
            if (other != p && other.connection != null) other.connection.send(remove);
        }
    }

    // ------------------------------------------------------------------ ready, release

    /** The client says its loading is complete (or the player chose to enter without it). */
    static void onReady(ServerPlayer p, long token, boolean fallback) {
        Held h = HELD.get(p.getUUID());
        if (h == null || h.token != token) {
            NirnCraft.LOGGER.debug("hold: ignoring a ready from {} for hold {} ({})", p.getGameProfile().name(), token, h == null ? "not held" : "now " + h.token);
            return;
        }
        if (fallback) NirnCraft.LOGGER.warn("hold: {} entered WITHOUT a complete loading (the player chose to continue after a failure or timeout)", p.getGameProfile().name());
        release(p, fallback ? "the player continued without a complete loading" : "the client reported ready");
    }

    /** Releases a player: the real state back, visible again, then the release action (character select / last position). */
    public static boolean release(ServerPlayer p, String why) {
        Held h = HELD.remove(p.getUUID());
        if (h == null) return false;
        if (p.level().dimension() == h.dim && p.position().distanceToSqr(h.origin) > 1.0E-4) {  // (back exactly where the hold began: the pin runs every other tick; above the landing)
            p.setPos(h.origin.x, h.origin.y, h.origin.z);
            if (p.connection != null) p.connection.teleport(h.origin.x, h.origin.y, h.origin.z, p.getYRot(), p.getXRot());
            LAST.put(p.getUUID(), h.origin);  // (back up from the landing: not a jump)
        }
        restore(p, h);
        p.getPersistentData().remove(TAG);
        ServerLevel level = p.level();
        if (!(p instanceof FakePlayer)) level.getChunkSource().move(p);  // tracked by the others again
        var info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p));
        for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
            if (other != p && other.connection != null) other.connection.send(info);
        }
        send(p, new HoldPackets.Held(false, h.token, h.reason));
        NirnCraft.LOGGER.info("hold: {} released after {} ticks ({} ms) at {} {} {} (pinned {} times): {}", p.getGameProfile().name(),
                p.level().getGameTime() - h.sinceTick, System.currentTimeMillis() - h.sinceMs, fmt(p.getX()), fmt(p.getY()), fmt(p.getZ()), h.pins, why);
        Runnable after = h.onRelease;
        if (after != null) {
            try {
                after.run();
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.error("hold: the release action failed for {}", p.getGameProfile().name(), e);
            }
        }
        return true;
    }

    private static void restore(ServerPlayer p, Held h) {
        p.setGameMode(h.prevMode);
        Abilities a = p.getAbilities();
        a.mayfly = h.prevMayfly;
        a.flying = h.prevFlying && h.prevMayfly;
        a.mayBuild = h.prevMayBuild;
        a.invulnerable = h.prevInvulnerable;
        a.instabuild = h.prevInstabuild;
        p.onUpdateAbilities();
        p.setNoGravity(h.prevNoGravity);
        p.setInvisible(h.prevInvisible);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
    }

    // ------------------------------------------------------------------ events: lifecycle

    /** A player who joins while others are held must not see them in the tab list. */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (HELD.isEmpty() || !(event.getEntity() instanceof ServerPlayer joiner) || joiner.connection == null) return;
        List<UUID> ids = new ArrayList<>();
        for (UUID id : HELD.keySet()) if (!id.equals(joiner.getUUID())) ids.add(id);
        if (!ids.isEmpty()) joiner.connection.send(new ClientboundPlayerInfoRemovePacket(ids));
    }

    /** A held player who leaves goes back to their real state before their data is saved (never a spectator on disk). */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        LAST.remove(p.getUUID());
        Held h = HELD.remove(p.getUUID());
        if (h == null) return;
        try {
            restore(p, h);
            p.getPersistentData().remove(TAG);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.warn("hold: could not restore {} on logout (the persisted record restores them at the next login)", p.getGameProfile().name(), e);
        }
        NirnCraft.LOGGER.info("hold: {} left while held ({} ticks); their real state was restored", p.getGameProfile().name(), p.level().getGameTime() - h.sinceTick);
    }

    /** Each server tick of a held player: pinned, still, breathing, not burning; the hold is logged now and then. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        watchJump(p, p.connection != null && !(p instanceof FakePlayer));
        if (HELD.isEmpty()) return;
        Held h = HELD.get(p.getUUID());
        if (h == null) return;
        long now = p.level().getGameTime();
        if (p.level().dimension() != h.dim) {  // moved by something that got past the guards: hold where they are
            h.pos = p.position();
        } else if (p.position().distanceToSqr(h.pos) > 1.0E-4 && now - h.lastPin >= 2) {
            h.lastPin = now;
            h.pins++;
            p.setPos(h.pos.x, h.pos.y, h.pos.z);  // (the server's own position at once; the teleport tells the client)
            if (p.connection != null) p.connection.teleport(h.pos.x, h.pos.y, h.pos.z, p.getYRot(), p.getXRot());
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        p.setAirSupply(p.getMaxAirSupply());
        if (p.getRemainingFireTicks() > 0) p.clearFire();
        if (p.getCamera() != p) p.setCamera(p);
        HoldProbe.tick(p, now - h.sinceTick);
        if (now - h.lastLog >= LOG_EVERY_TICKS) {
            h.lastLog = now;
            NirnCraft.LOGGER.info("hold: {} is still held ({} s): the client has not reported ready", p.getGameProfile().name(), (now - h.sinceTick) / 20);
        }
    }

    /**
     * The jump check: a player who moved more than {@link #JUMP_BLOCKS} since the previous tick, in the same dimension,
     * has been teleported (a command, a pearl, a mod's travel ...) and is held at the destination. Package-private with
     * {@code eligible} so the selftest can drive it with stand-ins.
     */
    static boolean watchJump(ServerPlayer p, boolean eligible) {
        Vec3 pos = p.position();
        Vec3 last = LAST.put(p.getUUID(), pos);
        if (last == null || !eligible || isHeld(p) || !ARRIVAL) return false;
        double d2 = last.distanceToSqr(pos);
        if (d2 <= JUMP_BLOCKS * JUMP_BLOCKS) {
            WHY.remove(p.getUUID());
            return false;
        }
        String why = WHY.remove(p.getUUID());
        String reason = (why == null ? "jump" : why) + " " + (int) Math.sqrt(d2) + " blocks";
        return p instanceof FakePlayer ? arriveForTests(p, reason) : arrive(p, reason);
    }

    /** The kind of teleport, remembered for the hold the jump check is about to make. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTeleportReason(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || isHeld(p)) return;
        String why = event instanceof EntityTeleportEvent.EnderPearl ? "ender pearl"
                : event instanceof EntityTeleportEvent.ItemConsumption ? "chorus fruit"
                : event instanceof EntityTeleportEvent.TeleportCommand ? "teleport command"
                : event instanceof EntityTeleportEvent.SpreadPlayersCommand ? "spreadplayers"
                : "teleport";
        WHY.put(p.getUUID(), why);
    }

    /** Another dimension: the destination may not be loaded at all. Held there. */
    @SubscribeEvent
    public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        LAST.remove(p.getUUID());
        arrive(p, "dimension " + event.getTo().identifier());
    }

    /**
     * A respawn (after death, or the return from the End): the mod's own respawn placement (nearest town, bed) runs first
     * at normal priority, so the hold is made where they end up.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        LAST.remove(p.getUUID());
        arrive(p, event.isEndConquered() ? "return from the End" : "respawn");
    }

    // ------------------------------------------------------------------ events: what a held player cannot do or be

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTarget(LivingChangeTargetEvent event) {
        if (isHeld(event.getNewAboutToBeSetTarget())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (isHeld(event.getEntity()) || isHeld(event.getSource().getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (isHeld(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isHeld(event.getEntity())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isHeld(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (isHeld(event.getEntity())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (isHeld(event.getEntity())) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BreakBlockEvent event) {
        if (isHeld(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (isHeld(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTeleport(EntityTeleportEvent event) {
        if (isHeld(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDimension(EntityTravelToDimensionEvent event) {
        if (isHeld(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (source.getEntity() instanceof ServerPlayer p && isHeld(p)) {
            event.setCanceled(true);
            source.sendFailure(Component.literal("You are held until your game has finished loading."));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void send(ServerPlayer p, Object msg) {
        try {
            if (p.connection != null) SkyNetwork.sendToPlayer(p, msg);
        } catch (RuntimeException e) {
            // (a stand-in without a listener)
        }
    }

    /** ", the camera at the landing (y ...)" when the hold waits below where it began; else "". */
    private static String landing(Held h) {
        return h.pos.distanceToSqr(h.origin) > 1.0E-4 ? ", the camera at the landing (y " + fmt(h.pos.y) + ")" : "";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Test hook: forget every hold (no restore); the selftest cleans its stand-ins itself. */
    static void clearForTests() {
        HELD.clear();
    }
}
