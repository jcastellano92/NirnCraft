package com.skycraft.world;

import com.skycraft.Skycraft;
import com.skycraft.combat.Stealth;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.registry.ModEffects;
import com.skycraft.survival.camping.CampingEvents;
import com.skycraft.survival.mode.SurvivalMode;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Skyrim's Wait and Sleep, as a state machine per player and a group rule per dimension (the pure decisions are in
 * {@link RestLogic}; the full description is {@code docs/REST.md}).
 *
 * <ul>
 *     <li><b>Rest</b>: a player lies down in a bed within reach (SLEEP) or sits on the ground (WAIT). Lying or sitting
 *     refills the rest need slowly in a bed only (a drained bar in {@code rest.fullRechargeSeconds}); waiting rests
 *     nothing. Sleeping is the vanilla sleeping state (every client draws the lying pose, low hitbox, damage wakes);
 *     sitting is a rider on an invisible marker.</li>
 *     <li><b>Skip</b>: when every player of the dimension rests (spectators, prisoners and AFK players excepted) the
 *     initiator picks the hours and starts a gradual fast-forward: the world clock is advanced in steps every tick for
 *     a few seconds, the meters hour by hour, the townsfolk step along their routines, and at the end everybody is where
 *     the schedule says. Any cancel stops it as {@link RestLogic#cancel} decides.</li>
 *     <li><b>Jail</b>: sleeping off a sentence is a personal fast-forward of the same kind ({@link #beginJail}).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RestManager {
    public static final int TICKS_PER_HOUR = RestLogic.TICKS_PER_HOUR;
    public static final String SEAT_TAG = "skycraft_rest_seat";
    /** Reach for starting a rest with the key / for right-clicking a bed. */
    private static final double KEY_REACH = 3.5, CLICK_REACH = 6.0;

    private static final class Session {
        final int mode;
        @Nullable
        final BlockPos bed; // head block of the bed
        final ResourceKey<Level> dim;
        final int startTick;
        final boolean jail;
        Vec3 anchor;
        Vec3 stand;
        boolean bedroll;
        @Nullable
        UUID seat;

        Session(int mode, @Nullable BlockPos bed, ResourceKey<Level> dim, int startTick, boolean jail) {
            this.mode = mode;
            this.bed = bed;
            this.dim = dim;
            this.startTick = startTick;
            this.jail = jail;
        }
    }

    private static final class Skip {
        UUID initiator;
        @Nullable
        final ResourceKey<Level> dim;
        final List<UUID> members;
        final int hours;
        final long total;
        final int duration;
        /** The world clock moves (false: a personal fast-forward). */
        final boolean world;
        /** Needs are fed hour by hour and the rest effects applied at the end. */
        final boolean meters;
        @Nullable
        final Runnable onDone;
        int elapsed;
        long done;
        /** Skipped ticks not yet given to the animals' age counters. */
        long animalTicks;
        /** Hostile mob -> the member it was hunting when the skip began (targets are put back when it ends). */
        final Map<UUID, UUID> paused = new HashMap<>();

        Skip(UUID initiator, @Nullable ResourceKey<Level> dim, List<UUID> members, int hours, long total, int duration,
             boolean world, boolean meters, @Nullable Runnable onDone) {
            this.initiator = initiator;
            this.dim = dim;
            this.members = members;
            this.hours = hours;
            this.total = total;
            this.duration = duration;
            this.world = world;
            this.meters = meters;
            this.onDone = onDone;
        }
    }

    private record Activity(double x, double y, double z, float yaw, float pitch, long tick) {}

    /** Insertion order is start order: the longest-resting player is first (initiator handover). */
    private static final Map<UUID, Session> SESSIONS = new LinkedHashMap<>();
    private static final List<Skip> SKIPS = new ArrayList<>();
    private static final Map<ResourceKey<Level>, UUID> INITIATOR = new HashMap<>();
    private static final Map<UUID, Activity> ACTIVITY = new HashMap<>();
    private static final Map<UUID, WorldPackets.RestHint> HINTS = new HashMap<>();

    private RestManager() {}

    // ------------------------------------------------------------------ bed interaction

    /** Right-clicking a bed lies the player down in it at any time; sneaking falls back to vanilla. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBed(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        if (player.isShiftKeyDown() || player.isSpectator() || !WorldConfig.BED_MENU.get()) return;
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof BedBlock)) return;
        if (!BedBlock.canSetSpawn(level)) return; // Oblivion and Sovngarde: beds behave (explode) like vanilla

        BlockPos pos = event.getPos();
        if (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("block.minecraft.bed.occupied"));
            return;
        }
        if (com.skycraft.crime.Ownership.isOwnedByOther(player, level, pos)) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("world.skycraft.rest.bed_owned"));
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player instanceof ServerPlayer sp && event.getHand() == InteractionHand.MAIN_HAND) {
            enter(sp, RestLogic.SLEEP, pos.immutable(), CLICK_REACH);
        }
    }

    /** Ensure vanilla sleeping (e.g. shift-clicking a bed) also checks bed ownership and occupancy. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlayerSleepInBed(net.minecraftforge.event.entity.player.PlayerSleepInBedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlockPos pos = event.getPos();
            if (com.skycraft.crime.Ownership.isOwnedByOther(player, player.level(), pos)) {
                event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
                Notifier.message(player, Component.translatable("world.skycraft.rest.bed_owned"));
            }
        }
    }

    // ------------------------------------------------------------------ requests

    /** The one client request ({@link WorldPackets.Rest}); everything is validated here. */
    public static void handle(ServerPlayer player, int action, int mode, int hours) {
        switch (action) {
            case WorldPackets.Rest.ENTER -> {
                if (mode == RestLogic.SLEEP || mode == RestLogic.WAIT) enter(player, mode, null, KEY_REACH);
            }
            case WorldPackets.Rest.START -> startSkip(player, Mth.clamp(hours, 1, 24));
            case WorldPackets.Rest.CANCEL -> cancel(player.server, player.getUUID(), null);
            default -> {
            }
        }
    }

    /** The nearest bed block within {@code reach} of the player's body (also used by the client to offer sleeping). */
    @Nullable
    public static BlockPos findBed(Level level, Player player, double reach) {
        int r = (int) Math.ceil(reach);
        BlockPos base = player.blockPosition();
        double cx = player.getX(), cy = player.getY() + 0.5, cz = player.getZ();
        BlockPos best = null;
        double bestD = reach * reach;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (!level.isLoaded(p) || !(level.getBlockState(p).getBlock() instanceof BedBlock)) continue;
                    double d = (p.getX() + 0.5 - cx) * (p.getX() + 0.5 - cx) + (p.getY() + 0.3 - cy) * (p.getY() + 0.3 - cy)
                            + (p.getZ() + 0.5 - cz) * (p.getZ() + 0.5 - cz);
                    if (d < bestD) {
                        bestD = d;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** Whether the player is in the rest system now (a session, not necessarily a skip). */
    public static boolean isResting(UUID id) {
        return SESSIONS.containsKey(id);
    }

    /**
     * Lies or seats the player. {@code clicked} is a bed block the player used (right-click), else the nearest bed
     * within {@code reach} is chosen for SLEEP. Returns true if the player is now resting in that mode.
     */
    public static boolean enter(ServerPlayer player, int mode, @Nullable BlockPos clicked, double reach) {
        Session old = SESSIONS.get(player.getUUID());
        if (old != null && old.jail) return false;
        BlockPos bed = null;
        if (mode == RestLogic.SLEEP) bed = clicked != null ? clicked : findBed(player.level(), player, reach);
        Component error = validate(player, mode, bed, reach, old != null);
        if (error != null) {
            Notifier.message(player, error);
            return false;
        }
        BlockPos head = bed == null ? null : bedHead(player.level(), bed);
        if (old != null) {
            if (old.mode == mode && java.util.Objects.equals(old.bed, head)) return true;
            if (skipOf(player.getUUID()) != null) return false; // no switching while time passes
            endSession(player.server, player.getUUID());
        }
        if (!begin(player, mode, head, false)) {
            Notifier.message(player, Component.translatable("world.skycraft.rest.busy"));
            return false;
        }
        syncGroup(player.server, player.level().dimension());
        return true;
    }

    /** Returns why the player can't rest right now, or {@code null}. {@code alreadyResting}: switching mode. */
    public static Component validate(ServerPlayer player, int mode, @Nullable BlockPos bed, double reach, boolean alreadyResting) {
        boolean sleep = mode == RestLogic.SLEEP;
        String act = sleep ? "sleep" : "wait";
        if (!player.isAlive() || player.isSpectator()) return Component.translatable("world.skycraft.rest.busy");
        if (RestGate.blocked(player)) return Component.translatable("world.skycraft.rest.gated"); // opening, loading, dialogue, scene
        if (com.skycraft.crime.Jail.isJailed(player)) return Component.translatable("world.skycraft.rest.jailed");
        if (!sleep && !WorldConfig.REST_ALLOW_WAIT.get()) return Component.translatable("world.skycraft.rest.wait_off");
        if (player.isSleeping()) return Component.translatable("world.skycraft.rest.busy");
        if (player.isPassenger() && !alreadyResting) return Component.translatable("world.skycraft.rest.mounted");
        if (player.containerMenu != player.inventoryMenu) return Component.translatable("world.skycraft.rest.busy");
        if (Vitals.inCombat(player)) return Component.translatable("world.skycraft.rest.combat." + act);
        if (enemiesNear(player, 16)) return Component.translatable("world.skycraft.rest.enemies." + act);
        if (player.isInWater() || player.isInLava()) return Component.translatable("world.skycraft.rest.water." + act);
        if (player.isOnFire()) return Component.translatable("world.skycraft.rest.fire." + act);
        boolean supported = player.onGround()
                || !player.level().noCollision(player, player.getBoundingBox().expandTowards(0, -0.35, 0));
        if (!alreadyResting && (player.fallDistance > 1f || player.isFallFlying()
                || (!supported && !player.isPassenger() && !player.getAbilities().flying))) {
            return Component.translatable("world.skycraft.rest.air." + act);
        }
        if (sleep) {
            // range and loaded checks come before any getBlockState (no forced chunk loads)
            if (bed == null || player.distanceToSqr(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5) > reach * reach
                    || !player.level().isLoaded(bed)
                    || !(player.level().getBlockState(bed).getBlock() instanceof BedBlock)) {
                return Component.translatable("world.skycraft.rest.no_bed");
            }
            if (!BedBlock.canSetSpawn(player.level())) return Component.translatable("world.skycraft.rest.no_bed");
            BlockState state = player.level().getBlockState(bed);
            if (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
                if (com.skycraft.survival.inn.Innkeepers.roomHere(player) != null
                        && com.skycraft.crime.Ownership.isInnBed(player.serverLevel(), bed)) {
                    for (net.minecraft.world.entity.LivingEntity sleeper : player.level().getEntitiesOfClass(
                            net.minecraft.world.entity.LivingEntity.class,
                            new net.minecraft.world.phys.AABB(bed).inflate(3.0),
                            e -> !(e instanceof Player) && e.isSleeping())) {
                        sleeper.stopSleeping();
                    }
                    state = player.level().getBlockState(bed);
                }
                if (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
                    return Component.translatable("block.minecraft.bed.occupied");
                }
            }
            if (com.skycraft.crime.Ownership.isOwnedByOther(player, player.level(), bed)) {
                return Component.translatable("world.skycraft.rest.bed_owned");
            }
            BlockPos head = bedHead(player.level(), bed);
            for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
                if (head.equals(e.getValue().bed) && !e.getKey().equals(player.getUUID())) {
                    return Component.translatable("block.minecraft.bed.occupied");
                }
            }
        }
        return null;
    }

    /**
     * Hostiles close enough to stop a rest from starting: enemies within {@code radius} that have line of sight
     * to the player or are targeting them, and anything within 24 blocks that is actively hunting or searching
     * for this player. Mobs sealed in underground caverns or behind walls with no awareness do not block rest.
     */
    public static boolean enemiesNear(Player player, double radius) {
        Level level = player.level();
        // 1. Actively hunting or searching mobs within 24 blocks (including bandits, wolves, etc.)
        double huntRadius = Math.max(radius, 24);
        List<Mob> hunters = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(huntRadius),
                m -> m.isAlive() && (m.getTarget() == player || Stealth.isSearchingFor(m, player)));
        if (!hunters.isEmpty()) {
            return true;
        }

        // 2. Enemies within radius (and vertical +/- 8 blocks) that are targeting the player or have line of sight
        List<Mob> nearby = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius, 8.0, radius),
                m -> m instanceof Enemy && m.isAlive() && !m.isNoAi());
        for (Mob m : nearby) {
            if (m.getTarget() == player || Stealth.isSearchingFor(m, player)) {
                return true;
            }
            if (m.hasLineOfSight(player)) {
                if (player.isCrouching() && Stealth.hiddenFrom(m, player)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static BlockPos bedHead(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(BedBlock.FACING)).immutable();
        }
        return pos.immutable();
    }

    // ------------------------------------------------------------------ sessions

    private static boolean begin(ServerPlayer player, int mode, @Nullable BlockPos head, boolean jail) {
        ServerLevel level = player.serverLevel();
        Session s = new Session(mode, head, level.dimension(), player.server.getTickCount(), jail);
        s.stand = new Vec3(player.getX(), player.getY(), player.getZ());
        s.anchor = s.stand;
        if (mode != RestLogic.WAIT && head != null && level.getBlockState(head).getBlock() instanceof BedBlock) {
            BlockState st = level.getBlockState(head);
            Direction facing = st.getValue(BedBlock.FACING); // from the foot to the head
            s.bedroll = CampingEvents.isCampBed(level, head);
            // the real vanilla sleeping state: every client draws the lying pose, the hitbox is low (0.2), damage wakes
            player.startSleeping(head);
            double y = player.getY();
            if (s.bedroll) {
                VoxelShape shape = st.getCollisionShape(level, head);
                double top = shape.isEmpty() ? 0.1875 : shape.max(Direction.Axis.Y);
                y = head.getY() + top + 0.125; // vanilla lies 0.6875 above a bed block; a bedroll is much lower
            }
            // the camera looks from the side of the bed (head pointing along the bed's facing)
            float lyingYaw = (float) Math.toDegrees(Math.atan2(facing.getStepZ(), -facing.getStepX()));
            player.connection.teleport(player.getX(), y, player.getZ(), Mth.wrapDegrees(-lyingYaw), 35f);
            s.anchor = new Vec3(player.getX(), y, player.getZ());
            s.stand = s.anchor;
        } else {
            Marker seat = new Marker(EntityType.MARKER, level);
            // the rider sits 0.35 below the marker; the hips end up at about ground level
            seat.setPos(player.getX(), player.getY() - 0.25, player.getZ());
            seat.addTag(SEAT_TAG);
            level.addFreshEntity(seat);
            if (!player.startRiding(seat, true)) {
                seat.discard();
                return false;
            }
            s.seat = seat.getUUID();
        }
        SESSIONS.put(player.getUUID(), s);
        SurvivalMode.setRestState(player.getUUID(), restLevel(mode));
        ACTIVITY.put(player.getUUID(), new Activity(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.server.getTickCount()));
        return true;
    }

    /** The survival meters' state while resting: sleeping stops the rest bar growing; waiting changes nothing. */
    private static int restLevel(int mode) {
        return mode == RestLogic.WAIT ? 0 : 1;
    }

    /** Takes the player out of the rest (stands up, camera back). Does not touch skips or others. */
    private static void endSession(MinecraftServer server, UUID id) {
        Session s = SESSIONS.remove(id);
        if (s == null) return;
        SurvivalMode.setRestState(id, 0);
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null && p.isSleeping()) p.stopSleepInBed(true, true); // stands up beside the bed, no fade-out
        if (s.seat != null) {
            ServerLevel lvl = server.getLevel(s.dim);
            Entity seat = lvl == null ? null : lvl.getEntity(s.seat);
            if (p != null && seat != null && p.getVehicle() == seat) p.stopRiding();
            if (seat != null) seat.discard();
        }
        if (p == null) return;
        if (s.seat != null && p.isAlive() && p.level().dimension() == s.dim) {
            p.connection.teleport(s.stand.x, s.stand.y, s.stand.z, p.getYRot(), p.getXRot());
        }
        SkyNetwork.sendToPlayer(p, new WorldPackets.RestState(0, 0, 0, 0, 0, 0, 0, ""));
    }

    @Nullable
    private static Skip skipOf(UUID id) {
        for (Skip k : SKIPS) if (k.members.contains(id)) return k;
        return null;
    }

    // ------------------------------------------------------------------ the group rule

    private static boolean afk(MinecraftServer server, ServerPlayer p) {
        Activity a = ACTIVITY.get(p.getUUID());
        return a != null && RestLogic.afk(server.getTickCount(), a.tick(), WorldConfig.REST_AFK_SECONDS.get());
    }

    /** The dimension's players as the group rule sees them: resting ones in start order first. */
    private static List<RestLogic.Participant> participants(MinecraftServer server, ResourceKey<Level> dim) {
        List<RestLogic.Participant> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            Session s = e.getValue();
            if (s.jail || s.dim != dim) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            seen.add(e.getKey());
            out.add(new RestLogic.Participant(e.getKey(), true, p.isSpectator() || !p.isAlive()));
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != dim || seen.contains(p.getUUID())) continue;
            boolean excluded = p.isSpectator() || !p.isAlive() || com.skycraft.crime.Jail.isJailed(p) || afk(server, p);
            out.add(new RestLogic.Participant(p.getUUID(), false, excluded));
        }
        return out;
    }

    private static String nameOf(MinecraftServer server, @Nullable UUID id) {
        ServerPlayer p = id == null ? null : server.getPlayerList().getPlayer(id);
        return p == null ? "" : p.getGameProfile().getName();
    }

    /** Recomputes the dimension's group and tells every resting player their state. */
    private static void syncGroup(MinecraftServer server, ResourceKey<Level> dim) {
        List<RestLogic.Participant> order = participants(server, dim);
        RestLogic.Tally tally = RestLogic.tally(order);
        UUID ini = RestLogic.initiator(INITIATOR.get(dim), order);
        if (ini == null) INITIATOR.remove(dim);
        else INITIATOR.put(dim, ini);
        boolean sync = WorldConfig.REST_SYNC_MULTIPLAYER.get();
        int online = server.getPlayerList().getPlayerCount();
        boolean personal = RestLogic.personal(online, sync);
        String iniName = nameOf(server, ini);
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            Session s = e.getValue();
            if (s.dim != dim) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            Skip k = skipOf(e.getKey());
            int flags = 0;
            int mode = s.mode;
            int resting = tally.resting(), required = tally.required();
            if (s.jail) {
                flags |= WorldPackets.RestState.F_SKIP_OFF | WorldPackets.RestState.F_JAIL;
                resting = required = 1;
            } else {
                boolean isIni = personal || e.getKey().equals(ini);
                RestLogic.Block block = RestLogic.startBlock(mode, isIni, tally, online, sync, WorldConfig.REST_ALLOW_WAIT.get(),
                        WorldConfig.REST_ALLOW_SLEEP_SKIP.get());
                if (isIni) flags |= WorldPackets.RestState.F_INITIATOR;
                if (block == RestLogic.Block.NONE) flags |= WorldPackets.RestState.F_CAN_START;
                if (block == RestLogic.Block.MODE_DISABLED) flags |= WorldPackets.RestState.F_SKIP_OFF;
                if (personal) {
                    flags |= WorldPackets.RestState.F_PERSONAL;
                    resting = required = 1;
                }
            }
            int progress = k == null || k.total <= 0 ? 0 : (int) (k.done * 1000 / k.total);
            SkyNetwork.sendToPlayer(p, new WorldPackets.RestState(k != null ? 2 : 1, mode, flags, k != null ? k.hours : 0,
                    progress, resting, required, personal || s.jail ? p.getGameProfile().getName() : iniName));
        }
    }

    // ------------------------------------------------------------------ the skip

    private static void startSkip(ServerPlayer player, int hours) {
        UUID id = player.getUUID();
        Session s = SESSIONS.get(id);
        MinecraftServer server = player.server;
        if (s == null || s.jail || skipOf(id) != null) return;
        // nobody's timers jump under a cutscene, a loading cover or a conversation
        if (RestGate.blocked(player) || anyBlocked(server, s.dim)) {
            Notifier.message(player, Component.translatable("world.skycraft.rest.gated_group"));
            return;
        }
        List<RestLogic.Participant> order = participants(server, s.dim);
        RestLogic.Tally tally = RestLogic.tally(order);
        UUID ini = RestLogic.initiator(INITIATOR.get(s.dim), order);
        boolean sync = WorldConfig.REST_SYNC_MULTIPLAYER.get();
        int online = server.getPlayerList().getPlayerCount();
        boolean personal = RestLogic.personal(online, sync);
        RestLogic.Block block = RestLogic.startBlock(s.mode, personal || id.equals(ini), tally, online, sync,
                WorldConfig.REST_ALLOW_WAIT.get(), WorldConfig.REST_ALLOW_SLEEP_SKIP.get());
        if (block != RestLogic.Block.NONE) {
            String key = switch (block) {
                case NOT_ALL_RESTING -> "world.skycraft.rest.not_all";
                case NOT_INITIATOR -> "world.skycraft.rest.not_initiator";
                default -> "world.skycraft.rest.skip_off";
            };
            Notifier.message(player, Component.translatable(key));
            syncGroup(server, s.dim);
            return;
        }
        List<UUID> members = new ArrayList<>();
        if (personal) {
            members.add(id);
        } else {
            for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
                Session o = e.getValue();
                if (!o.jail && o.dim == s.dim && skipOf(e.getKey()) == null) members.add(e.getKey());
            }
        }
        Skip k = new Skip(id, s.dim, members, hours, hours * (long) TICKS_PER_HOUR,
                RestLogic.durationTicks(hours, WorldConfig.REST_MAX_SKIP_SECONDS.get()), !personal, true, null);
        SKIPS.add(k);
        pauseHunters(server, k);
        for (UUID m : members) SurvivalMode.setRestState(m, 2);
        for (UUID m : members) {
            ServerPlayer p = server.getPlayerList().getPlayer(m);
            if (p != null && !m.equals(id)) {
                Notifier.message(p, Component.translatable("world.skycraft.rest.started", player.getDisplayName(), hours));
            }
        }
        syncGroup(server, s.dim);
    }

    /**
     * Jail: the prisoner lies down in the cell bed and the sentence is slept off as a personal fast-forward (no hours,
     * no other player involved, the world clock is not touched). {@code done} runs when it completes (the release);
     * cancelling leaves the prisoner in the cell. Returns false if the cinematic could not start (the caller releases
     * at once as before).
     */
    public static boolean beginJail(ServerPlayer player, BlockPos clicked, Runnable done) {
        UUID id = player.getUUID();
        if (SESSIONS.containsKey(id)) return true; // already sleeping it off
        if (RestGate.blocked(player)) { // a loading cover or a scene owns the player: not now (and not an instant release)
            Notifier.message(player, Component.translatable("world.skycraft.rest.gated"));
            return true;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(clicked) || !(level.getBlockState(clicked).getBlock() instanceof BedBlock)
                || player.isPassenger() || player.isSleeping()
                || player.distanceToSqr(clicked.getX() + 0.5, clicked.getY() + 0.5, clicked.getZ() + 0.5) > CLICK_REACH * CLICK_REACH) {
            return false;
        }
        if (!begin(player, RestLogic.JAIL, bedHead(level, clicked), true)) return false;
        List<UUID> members = new ArrayList<>();
        members.add(id);
        SKIPS.add(new Skip(id, null, members, 0, 1000L, 140, false, false, done));
        SurvivalMode.setRestState(id, 2);
        syncGroup(player.server, level.dimension());
        return true;
    }

    /** Somebody in the dimension is in the opening, a loading cover, a conversation or a scene. */
    private static boolean anyBlocked(MinecraftServer server, ResourceKey<Level> dim) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() == dim && !p.isSpectator() && RestGate.blocked(p)) return true;
        }
        return false;
    }

    private static void tickSkips(MinecraftServer server) {
        for (Skip k : new ArrayList<>(SKIPS)) {
            k.members.removeIf(m -> !SESSIONS.containsKey(m));
            if (k.members.isEmpty()) {
                SKIPS.remove(k);
                continue;
            }
            // a cutscene or loading cover appeared somewhere in the dimension: the skip stops (everybody keeps resting)
            if (k.world && k.dim != null && k.elapsed % 10 == 0 && anyBlocked(server, k.dim)) {
                for (UUID m : k.members) {
                    ServerPlayer p = server.getPlayerList().getPlayer(m);
                    if (p != null) Notifier.message(p, Component.translatable("world.skycraft.rest.gated_group"));
                }
                finish(server, k, false, false);
                continue;
            }
            k.elapsed++;
            long target = RestLogic.worldTicksAt(k.total, k.duration, k.elapsed);
            long step = target - k.done;
            if (step > 0) {
                if (k.world) advanceTime(server, step);
                long before = k.done;
                k.done = target;
                int hrs = RestLogic.hoursCrossed(before, target);
                if (hrs > 0 && k.meters) {
                    for (UUID m : k.members) {
                        ServerPlayer p = server.getPlayerList().getPlayer(m);
                        Session s = SESSIONS.get(m);
                        if (p != null && s != null) SurvivalMode.restStep(p, hrs, s.mode == RestLogic.SLEEP);
                    }
                }
            }
            if (k.world && step > 1) {
                // plants, furnaces and brewing stands around everybody in the dimension, animals every few ticks
                ServerLevel lvl = k.dim == null ? null : server.getLevel(k.dim);
                if (lvl != null) {
                    List<ServerPlayer> near = new ArrayList<>(lvl.players());
                    RestSim.catchUp(lvl, near, step, k.elapsed);
                    k.animalTicks += step;
                    if (k.elapsed % 10 == 0) {
                        RestSim.animals(lvl, near, k.animalTicks);
                        k.animalTicks = 0;
                    }
                }
            }
            if (k.world && k.elapsed % 10 == 0) {
                int budget = 48;
                for (UUID m : k.members) {
                    ServerPlayer p = server.getPlayerList().getPlayer(m);
                    if (p != null) budget -= RestSim.hop(p, Math.max(8, budget), k.elapsed);
                }
            }
            if (k.elapsed >= k.duration && k.done >= k.total) {
                finish(server, k, true, true);
            } else if (k.elapsed % 4 == 0 && k.dim != null) {
                syncGroup(server, k.dim);
            } else if (k.elapsed % 4 == 0) {
                // jail: the prisoner's own dimension
                ServerPlayer p = server.getPlayerList().getPlayer(k.initiator);
                if (p != null) syncGroup(server, p.level().dimension());
            }
        }
    }

    /**
     * Ends a skip. {@code completed}: it ran to the end. {@code wakeAll}: the members stand up (else they keep
     * resting, with the time that did pass applied). Applies the rest of the hours that really passed, settles the
     * townsfolk to the real clock and runs the completion callback (jail release) after everybody stands.
     */
    private static void finish(MinecraftServer server, Skip k, boolean completed, boolean wakeAll) {
        SKIPS.remove(k);
        int passed = k.world || k.meters ? (int) (k.done / TICKS_PER_HOUR) : 0;
        List<ServerPlayer> players = new ArrayList<>();
        // the time that passed runs down every status effect (before the rest below gives a fresh Well Rested), and
        // counts off the sentence of every prisoner (a personal jail sleep moves no clock)
        if (k.meters && k.done > 0) {
            ServerLevel dimLevel = k.dim == null ? null : server.getLevel(k.dim);
            if (k.world && dimLevel != null) {
                for (ServerPlayer p : dimLevel.players()) RestSim.shortenEffects(p, k.done);
            } else {
                for (UUID m : k.members) {
                    ServerPlayer p = server.getPlayerList().getPlayer(m);
                    if (p != null) RestSim.shortenEffects(p, k.done);
                }
            }
            if (k.world) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) com.skycraft.crime.Jail.passTime(p, (int) Math.min(Integer.MAX_VALUE, k.done / 20));
            }
        }
        for (UUID m : k.members) {
            ServerPlayer p = server.getPlayerList().getPlayer(m);
            Session s = SESSIONS.get(m);
            if (s == null) continue;
            SurvivalMode.setRestState(m, restLevel(s.mode));
            if (p == null) continue;
            players.add(p);
            if (k.meters && passed >= 1) {
                applyRest(p, passed, s.mode == RestLogic.SLEEP, s.bed);
                if (s.mode == RestLogic.SLEEP) SurvivalMode.restDone(p, s.bed);
            }
        }
        if (k.world && !players.isEmpty()) RestSim.settle(players);
        for (ServerPlayer p : players) {
            if (k.meters && passed >= 1) {
                Session s = SESSIONS.get(p.getUUID());
                SkyNetwork.sendToPlayer(p, new WorldPackets.RestFade(passed,
                        s != null && s.mode == RestLogic.SLEEP ? WorldPackets.RestFade.SLEEP : WorldPackets.RestFade.WAIT));
            } else if (!k.meters && completed) {
                SkyNetwork.sendToPlayer(p, new WorldPackets.RestFade(0, WorldPackets.RestFade.TRAVEL));
            }
        }
        if (wakeAll) for (UUID m : new ArrayList<>(k.members)) endSession(server, m);
        resumeHunters(server, k); // whoever was mid-attack goes back to hunting (nothing was removed)
        if (completed && k.onDone != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(k.initiator);
            if (p != null && p.isAlive()) k.onDone.run();
        }
        if (k.dim != null) syncGroup(server, k.dim);
    }

    /**
     * A player stops resting (key, movement, ESC, damage, logout, death, dimension change...). During a skip the
     * initiator's cancel stops it for everybody and everybody gets up; anybody else's cancel makes them leave and
     * stops the skip (not everyone rests any more) while the others keep resting.
     */
    static void cancel(MinecraftServer server, UUID id, @Nullable Component why) {
        Session s = SESSIONS.get(id);
        if (s == null) return;
        ServerPlayer who = server.getPlayerList().getPlayer(id);
        Skip k = skipOf(id);
        if (why != null && who != null) Notifier.message(who, why);
        if (k == null) {
            endSession(server, id);
            if (!s.jail) syncGroup(server, s.dim);
            return;
        }
        RestLogic.Cancel c = RestLogic.cancel(true, k.initiator.equals(id));
        if (c == RestLogic.Cancel.STOP_ALL_AND_WAKE) {
            for (UUID m : k.members) {
                ServerPlayer p = server.getPlayerList().getPlayer(m);
                if (p != null && !m.equals(id) && who != null) {
                    Notifier.message(p, Component.translatable("world.skycraft.rest.stopped", who.getDisplayName()));
                }
            }
            finish(server, k, false, true);
        } else {
            k.members.remove(id);
            finalizeLeaver(server, k, id, s);
            endSession(server, id);
            for (UUID m : k.members) {
                ServerPlayer p = server.getPlayerList().getPlayer(m);
                if (p != null && who != null) Notifier.message(p, Component.translatable("world.skycraft.rest.left", who.getDisplayName()));
            }
            finish(server, k, false, false);
        }
        if (!s.jail) syncGroup(server, s.dim);
    }

    /**
     * A player disappears from the group (logout, death, dimension change). Unlike a cancel this does not stop a
     * running skip: the rest of the group still rests, and if the leaver was the initiator the next longest-resting
     * member of the skip becomes the initiator.
     */
    private static void depart(MinecraftServer server, UUID id) {
        Session s = SESSIONS.get(id);
        if (s == null) return;
        Skip k = skipOf(id);
        if (k != null && !s.jail) {
            k.members.remove(id);
            if (id.equals(k.initiator) && !k.members.isEmpty()) k.initiator = k.members.get(0);
            if (k.members.isEmpty()) SKIPS.remove(k);
        }
        cancel(server, id, null);
        if (!s.jail) syncGroup(server, s.dim);
    }

    /** The one who got up keeps what the time that passed gave. */
    private static void finalizeLeaver(MinecraftServer server, Skip k, UUID id, Session s) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        int passed = (int) (k.done / TICKS_PER_HOUR);
        if (p == null || !k.meters || passed < 1) return;
        applyRest(p, passed, s.mode == RestLogic.SLEEP, s.bed);
        if (s.mode == RestLogic.SLEEP) SurvivalMode.restDone(p, s.bed);
        SkyNetwork.sendToPlayer(p, new WorldPackets.RestFade(passed,
                s.mode == RestLogic.SLEEP ? WorldPackets.RestFade.SLEEP : WorldPackets.RestFade.WAIT));
        if (k.world) RestSim.settle(List.of(p));
        SurvivalMode.setRestState(id, restLevel(s.mode));
    }

    /** Moves the world clock forward (the overworld's clock drives every dimension) and syncs it right away. */
    public static void advanceTime(MinecraftServer server, long ticks) {
        ServerLevel overworld = server.overworld();
        overworld.setDayTime(overworld.getDayTime() + ticks);
        // game time too: every deadline stored as "game time + n" (cooldowns, rooms, hostile pacing, scheduled ticks and
        // commands) then runs out exactly as if the skipped time had been waited
        var data = server.getWorldData().overworldData();
        data.setGameTime(data.getGameTime() + ticks);
        skippedTicks += ticks; // timers that remove creatures count without it (liveTime)
        if (ruleData != null) {
            ruleData.skipped = skippedTicks;
            ruleData.setDirty();
        }
        for (ServerLevel level : server.getAllLevels()) {
            server.getPlayerList().broadcastAll(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)), level.dimension());
        }
    }

    /** Restores the resting player in proportion to the hours rested; sleeping also sets spawn and Well Rested. */
    private static void applyRest(ServerPlayer player, int hours, boolean sleep, @Nullable BlockPos bed) {
        PlayerData data = SkyData.get(player);
        float f = RestLogic.restFraction(sleep, hours);
        player.heal(player.getMaxHealth() * f);
        data.setMagicka(data.getMagicka() + data.maxMagicka() * f);
        data.setStamina(data.getStamina() + data.maxStamina() * f);
        data.markVitalsDirty();
        if (!sleep) return;
        player.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        if (bed != null && player.level().getBlockState(bed).getBlock() instanceof BedBlock) {
            boolean same = bed.equals(player.getRespawnPosition()) && player.level().dimension() == player.getRespawnDimension();
            // a home is a bed slept in and usable: not the intro cottage's provisional bed, not somebody else's (combat.RespawnPoints)
            if (!com.skycraft.combat.RespawnPoints.isProvisional(player, bed)
                    && !com.skycraft.crime.Ownership.isOwnedByOther(player, player.level(), bed)) {
                com.skycraft.combat.RespawnPoints.setOwned(player, player.level().dimension(), bed);
                if (!same) player.displayClientMessage(Component.translatable("block.minecraft.set_spawn"), true);
            }
        }
        int ticks = WorldConfig.WELL_RESTED_TICKS.get();
        if (hours >= 1 && ticks > 0) {
            player.addEffect(new MobEffectInstance(ModEffects.WELL_RESTED.get(), ticks, 0, false, false, true));
            Notifier.message(player, Component.translatable("world.skycraft.rest.well_rested"));
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int tick = server.getTickCount();
        if (tick % 10 == 1) guardSleepRule(server);
        if (!SESSIONS.isEmpty()) {
            if (tick % 5 == 0) validateSessions(server, tick);
            if (tick % 20 == 3) recover(server);
            tickSkips(server);
            if (tick % 10 == 7 && SKIPS.isEmpty()) {
                Set<ResourceKey<Level>> dims = new HashSet<>();
                for (Session s : SESSIONS.values()) if (!s.jail) dims.add(s.dim);
                for (ResourceKey<Level> d : dims) syncGroup(server, d);
            }
        }
        if (tick % 20 == 0) trackActivity(server, tick);
        if (tick % 20 == 10) hints(server);
    }

    /** Each session must still be a person lying or sitting quietly. */
    private static void validateSessions(MinecraftServer server, int tick) {
        for (UUID id : new ArrayList<>(SESSIONS.keySet())) {
            Session s = SESSIONS.get(id);
            if (s == null) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || p.isSpectator() || p.level().dimension() != s.dim) {
                depart(server, id);
                continue;
            }
            if (RestGate.blocked(p)) { // an opening, a loading cover, a conversation or a scene took the player
                cancel(server, id, null);
                continue;
            }
            boolean settled = tick - s.startTick > 5;
            Component why = null;
            boolean disturbed = true;
            // anything that hurts or hunts the player wakes them to defend themselves and ends the rest for everybody
            if (settled && p.hurtTime > 0) why = Component.translatable("world.skycraft.rest.hurt");
            else if (hunted(p)) why = Component.translatable("world.skycraft.rest.hunted");
            else if (settled && Vitals.inCombat(p)) why = Component.translatable("world.skycraft.rest.interrupted");
            else if (p.isInWater() || p.isInLava() || p.isOnFire()) why = Component.translatable("world.skycraft.rest.interrupted");
            else {
                disturbed = false;
                double dx = p.getX() - s.anchor.x, dz = p.getZ() - s.anchor.z, dy = p.getY() - s.anchor.y;
                if (dx * dx + dz * dz > 1.5 * 1.5 || Math.abs(dy) > 1.5) {
                    disturbed = true; // knocked or pushed away
                    why = Component.translatable("world.skycraft.rest.interrupted");
                } else if (s.mode == RestLogic.WAIT) {
                    if (s.seat == null || !p.isPassenger()) why = Component.empty(); // stood up (sneak) or dismounted
                } else if (!p.isSleeping() || p.isShiftKeyDown() || p.isPassenger()) {
                    why = Component.empty(); // left the bed
                }
            }
            if (why == null) continue;
            if (disturbed) disturb(server, id, why);
            else cancel(server, id, why.getString().isEmpty() ? null : why);
        }
    }

    /**
     * The player was hurt, hunted or pushed: they stand up and can fight at once. A running skip stops for everybody
     * whoever it was, everybody gets up, and each is told who was disturbed ({@link RestLogic#disturb}).
     */
    private static void disturb(MinecraftServer server, UUID id, Component why) {
        Session s = SESSIONS.get(id);
        if (s == null) return;
        Skip k = skipOf(id);
        ServerPlayer who = server.getPlayerList().getPlayer(id);
        if (k != null && RestLogic.disturb(!s.jail) == RestLogic.Cancel.STOP_ALL_AND_WAKE) {
            for (UUID m : k.members) {
                ServerPlayer p = server.getPlayerList().getPlayer(m);
                if (p == null) continue;
                if (m.equals(id)) Notifier.message(p, why);
                else if (who != null) Notifier.message(p, Component.translatable("world.skycraft.rest.disturbed", who.getDisplayName()));
            }
            finish(server, k, false, true);
            if (!s.jail) syncGroup(server, s.dim);
            return;
        }
        // not skipping: only they get up (the others who rest are told who was disturbed so they can help)
        cancel(server, id, why);
        if (who != null && !s.jail) {
            for (Map.Entry<UUID, Session> e : new ArrayList<>(SESSIONS.entrySet())) {
                if (e.getValue().dim != s.dim || e.getKey().equals(id)) continue;
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) Notifier.message(p, Component.translatable("world.skycraft.rest.disturbed_other", who.getDisplayName()));
            }
        }
    }

    private static boolean hunted(ServerPlayer p) {
        return !p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16),
                m -> m instanceof Enemy && m.isAlive() && m.getTarget() == p).isEmpty();
    }

    /** Lying or sitting slowly refills the rest bar (a bed: a drained bar in {@code fullRechargeSeconds}). */
    private static void recover(MinecraftServer server) {
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            if (skipOf(e.getKey()) != null) continue; // the fast-forward feeds the meters hour by hour
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            float perSecond = RestLogic.recoveryPerSecond(e.getValue().mode, e.getValue().bedroll, SurvivalMode.MAX_FATIGUE,
                    WorldConfig.REST_FULL_RECHARGE_SECONDS.get(), WorldConfig.REST_BEDROLL_PERCENT.get());
            if (perSecond > 0f) SurvivalMode.restRecover(p, perSecond);
        }
    }

    /** A player who moves or looks around is active; the others fall out of the group requirement after the timeout. */
    private static void trackActivity(MinecraftServer server, int tick) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.add(p.getUUID());
            Activity a = ACTIVITY.get(p.getUUID());
            boolean moved = a == null || Math.abs(a.x() - p.getX()) > 0.05 || Math.abs(a.y() - p.getY()) > 0.05
                    || Math.abs(a.z() - p.getZ()) > 0.05 || Math.abs(a.yaw() - p.getYRot()) > 1f || Math.abs(a.pitch() - p.getXRot()) > 1f;
            if (moved || SESSIONS.containsKey(p.getUUID())) {
                ACTIVITY.put(p.getUUID(), new Activity(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), tick));
            }
        }
        ACTIVITY.keySet().retainAll(online);
    }

    /** The non-blocking "X is resting. Press [key] to rest and join (n/m)" line for players who are not resting. */
    private static void hints(MinecraftServer server) {
        Map<UUID, WorldPackets.RestHint> wanted = new HashMap<>();
        boolean multi = server.getPlayerList().getPlayerCount() > 1;
        if (multi && WorldConfig.REST_SYNC_MULTIPLAYER.get() && !SESSIONS.isEmpty()
                && (WorldConfig.REST_ALLOW_WAIT.get() || WorldConfig.REST_ALLOW_SLEEP_SKIP.get())) {
            Set<ResourceKey<Level>> dims = new HashSet<>();
            for (Session s : SESSIONS.values()) if (!s.jail) dims.add(s.dim);
            for (ResourceKey<Level> dim : dims) {
                boolean running = false;
                for (Skip k : SKIPS) if (dim.equals(k.dim)) running = true;
                if (running) continue;
                List<RestLogic.Participant> order = participants(server, dim);
                RestLogic.Tally t = RestLogic.tally(order);
                if (t.resting() == 0 || t.allResting()) continue;
                UUID ini = RestLogic.initiator(INITIATOR.get(dim), order);
                String name = nameOf(server, ini);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.level().dimension() != dim || SESSIONS.containsKey(p.getUUID()) || p.isSpectator() || !p.isAlive()
                            || com.skycraft.crime.Jail.isJailed(p)) {
                        continue;
                    }
                    wanted.put(p.getUUID(), new WorldPackets.RestHint(name, t.resting(), t.required()));
                }
            }
        }
        for (Map.Entry<UUID, WorldPackets.RestHint> e : wanted.entrySet()) {
            if (e.getValue().equals(HINTS.get(e.getKey()))) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) SkyNetwork.sendToPlayer(p, e.getValue());
            HINTS.put(e.getKey(), e.getValue());
        }
        for (UUID id : new ArrayList<>(HINTS.keySet())) {
            if (wanted.containsKey(id)) continue;
            HINTS.remove(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) SkyNetwork.sendToPlayer(p, new WorldPackets.RestHint("", 0, 0));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        depart(p.server, p.getUUID());
        ACTIVITY.remove(p.getUUID());
        HINTS.remove(p.getUUID());
    }

    /** A seat saved with the player (server stopped mid-rest) must not keep them on an invisible marker. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Entity v = p.getVehicle();
        if (v instanceof Marker && v.getTags().contains(SEAT_TAG)) {
            p.stopRiding();
            v.discard();
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) depart(p.server, p.getUUID());
    }

    // ------------------------------------------------------------------ vanilla sleeping state

    /** Resting players sleep in the day too: the vanilla "you can only sleep at night" stop does not apply to them. */
    @SubscribeEvent
    public static void onSleepingTimeCheck(SleepingTimeCheckEvent event) {
        Session s = SESSIONS.get(event.getEntity().getUUID());
        if (s != null && s.mode != RestLogic.WAIT) event.setResult(Event.Result.ALLOW);
    }

    /**
     * Vanilla wakes everybody and skips to morning once every active player has slept 100 ticks (and the sleeping
     * percentage is met). Our rest system owns time passing, so while anybody lies in a bed the percentage is raised
     * out of reach and put back afterwards; a marker in the world data restores it after a crash.
     */
    private static void guardSleepRule(MinecraftServer server) {
        boolean need = false;
        for (Session s : SESSIONS.values()) if (s.mode != RestLogic.WAIT) need = true;
        if (!need && !ruleRaised) return;
        RuleData data = RuleData.get(server);
        GameRules.IntegerValue rule = server.getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
        if (need && data.original < 0) {
            data.original = rule.get();
            data.setDirty();
            rule.set(1000, server);
            ruleRaised = true;
        } else if (!need && data.original >= 0) {
            rule.set(data.original, server);
            data.original = -1;
            data.setDirty();
            ruleRaised = false;
        } else if (!need) {
            ruleRaised = false;
        }
    }

    private static boolean ruleRaised;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        RuleData data = RuleData.get(event.getServer());
        ruleData = data;
        skippedTicks = data.skipped;
        if (data.original >= 0) { // the server died while somebody lay in a bed
            event.getServer().getGameRules().getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(data.original, event.getServer());
            data.original = -1;
            data.setDirty();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (UUID id : new ArrayList<>(SESSIONS.keySet())) endSession(event.getServer(), id);
        SKIPS.clear();
        INITIATOR.clear();
        ACTIVITY.clear();
        HINTS.clear();
        guardSleepRule(event.getServer());
    }

    /** The sleeping percentage the world had before a rest raised it (-1: not raised). */
    private static final class RuleData extends SavedData {
        int original = -1;
        /** Total ticks rest has added to the game time (see {@link #liveTime}). */
        long skipped;

        static RuleData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(RuleData::load, RuleData::new, "skycraft_rest_rule");
        }

        static RuleData load(CompoundTag tag) {
            RuleData d = new RuleData();
            d.original = tag.contains("original") ? tag.getInt("original") : -1;
            d.skipped = tag.getLong("skipped");
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("original", original);
            tag.putLong("skipped", skipped);
            return tag;
        }
    }

    // ------------------------------------------------------------------ no hostile spawns while the world rests

    /** Pure data of the sessions: is a hostile spawn at this place refused (a skip runs here, or somebody rests near)? */
    public static boolean spawnsBlocked(ServerLevel level, double x, double y, double z) {
        if (SESSIONS.isEmpty()) return false;
        ResourceKey<Level> dim = level.dimension();
        for (Skip k : SKIPS) if (dim.equals(k.dim)) return true;
        double nearest = -1;
        for (Session s : SESSIONS.values()) {
            if (s.dim != dim || s.jail) continue;
            double d = s.anchor.distanceToSqr(x, y, z);
            if (nearest < 0 || d < nearest) nearest = d;
        }
        return RestLogic.spawnBlocked(false, nearest, SPAWN_BLOCK_RADIUS);
    }

    /** Blocks around a resting player in which no hostile appears (a whole dimension while a skip runs). */
    private static final double SPAWN_BLOCK_RADIUS = 96;

    private static boolean blockedType(MobSpawnType t) {
        return switch (t) {
            // never SPAWNER, CHUNK_GENERATION or STRUCTURE: dungeon spawners and world generation are world content
            case NATURAL, PATROL, EVENT, REINFORCEMENT, TRIGGERED, JOCKEY -> true;
            default -> false;
        };
    }

    @SubscribeEvent
    public static void onSpawnPosition(MobSpawnEvent.PositionCheck event) {
        if (SESSIONS.isEmpty() || !(event.getEntity() instanceof Enemy) || !blockedType(event.getSpawnType())) return;
        if (spawnsBlocked(event.getLevel().getLevel(), event.getX(), event.getY(), event.getZ())) event.setResult(Event.Result.DENY);
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        if (SESSIONS.isEmpty() || !(event.getEntity() instanceof Enemy) || !blockedType(event.getSpawnType())) return;
        if (spawnsBlocked(event.getLevel().getLevel(), event.getX(), event.getY(), event.getZ())) event.setSpawnCancelled(true);
    }

    // Deliberately NO EntityJoinLevelEvent net: a join event cannot tell a fresh spawn from a worldgen chunk entity,
    // a dungeon population or a quest actor (world-gen entities arrive with loadedFromDisk=false), and cancelling one
    // of those loses it for good. Skycraft's spawners are gated at their entry points instead
    // (SpawnPacing.hostileReady, the dragon attack roll) and vanilla spawns at the spawn events above; nothing that
    // already exists is ever cancelled or removed by the rest system.

    // ------------------------------------------------------------------ a skip never costs a hostile its target

    /** Hostiles that were hunting a resting player when a skip started: their target is put back when it ends. */
    private static void pauseHunters(MinecraftServer server, Skip k) {
        for (UUID m : k.members) {
            ServerPlayer p = server.getPlayerList().getPlayer(m);
            if (p == null) continue;
            for (Mob mob : p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(64), x -> x.isAlive() && x.getTarget() == p)) {
                k.paused.put(mob.getUUID(), m);
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
        }
    }

    /** The skip is over: whoever was mid-attack goes back to hunting the same player (the mob is never removed). */
    private static void resumeHunters(MinecraftServer server, Skip k) {
        if (k.paused.isEmpty()) return;
        for (Map.Entry<UUID, UUID> e : k.paused.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getValue());
            if (p == null || !p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            Entity ent = p.serverLevel().getEntity(e.getKey());
            if (ent instanceof Mob mob && mob.isAlive() && mob.getTarget() == null && mob.distanceToSqr(p) < 96 * 96) mob.setTarget(p);
        }
        k.paused.clear();
    }

    /** While a skip runs, the hunters it paused do not pick the resting player up again. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (SKIPS.isEmpty() || event.getNewTarget() == null) return;
        UUID mob = event.getEntity().getUUID();
        for (Skip k : SKIPS) {
            UUID who = k.paused.get(mob);
            if (who != null && event.getNewTarget().getUUID().equals(who)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ the world's real clock

    /**
     * The game time without the time rest skips added: the clock for anything that despawns, expires or ends a
     * creature on a timer (encounters, scenes, summons, wild dragons, ambushes). Skipped hours must never be what
     * removes a mob, so those systems count real ticks only. Read and write deadlines with the same clock.
     */
    public static long liveTime(Level level) {
        return level.getGameTime() - skippedTicks;
    }

    /** Ticks rest has added to the game time so far (live time + this = game time). */
    public static long skippedTicks() {
        return skippedTicks;
    }

    private static long skippedTicks;
    private static RuleData ruleData;

    // ------------------------------------------------------------------ headless check

    /**
     * Selftest of the "nothing existing is lost" rule: a hostile mob that already exists in a loaded chunk, hunting a
     * stand-in player, goes through a simulated skip (hunters paused, clock and game time jumped, plants/machines
     * caught up, effects shortened, hunters resumed) and must still exist, once, with its target back. The clocks
     * are put back afterwards. Returns one line per failed check.
     */
    public static List<String> selfTestWorld(MinecraftServer server, int[] checks) {
        List<String> fails = new ArrayList<>();
        ServerLevel level = server.overworld();
        BlockPos at = level.getSharedSpawnPos();
        if (!level.isLoaded(at)) return fails; // nothing loaded to test with
        net.minecraft.world.entity.monster.Zombie z = EntityType.ZOMBIE.create(level);
        ServerPlayer fake = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        if (z == null || fake == null) return fails;
        z.moveTo(at.getX() + 0.5, at.getY() + 2, at.getZ() + 0.5, 0f, 0f);
        z.setNoAi(true); // stays where it is during the check
        level.addFreshEntity(z);
        z.setTarget(fake);
        long game0 = level.getGameTime(), day0 = level.getDayTime(), skipped0 = skippedTicks;
        try {
            checks[0] += 6;
            long zombies = level.getEntities(EntityType.ZOMBIE, e -> true).size();
            List<UUID> members = new ArrayList<>();
            members.add(fake.getUUID());
            Skip k = new Skip(fake.getUUID(), level.dimension(), members, 8, 8000L, 140, true, true, null);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, z.getBoundingBox().inflate(8), x -> x.getTarget() == fake)) {
                k.paused.put(mob.getUUID(), fake.getUUID());
                mob.setTarget(null);
            }
            if (z.getTarget() != null) fails.add("rest: a hunter's target is paused during the skip");
            advanceTime(server, 8000);
            RestSim.catchUp(level, List.of(fake), 120, 0);
            RestSim.shortenEffects(fake, 8000);
            z.checkDespawn(); // what the time jump might have triggered
            if (z.isRemoved() || level.getEntity(z.getUUID()) == null) fails.add("rest: an existing hostile survives a simulated skip");
            long after = level.getEntities(EntityType.ZOMBIE, e -> true).size();
            if (after != zombies) fails.add("rest: hostile count unchanged by a skip (" + zombies + " -> " + after + ")");
            if (liveTime(level) != game0 - skipped0) fails.add("rest: live time does not include skipped ticks");
            for (Map.Entry<UUID, UUID> e : k.paused.entrySet()) {
                Entity ent = level.getEntity(e.getKey());
                if (ent instanceof Mob mob && mob.isAlive() && mob.getTarget() == null) mob.setTarget(fake);
            }
            if (z.getTarget() != fake) fails.add("rest: the hunter's target is restored after the skip");
        } finally {
            z.discard();
            var data = server.getWorldData().overworldData();
            data.setGameTime(game0);
            level.setDayTime(day0);
            skippedTicks = skipped0;
            if (ruleData != null) {
                ruleData.skipped = skipped0;
                ruleData.setDirty();
            }
        }
        return fails;
    }
}
