package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.core.Holds;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perf.PerfPackets;
import com.skycraft.perf.Prewarm;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.DistrictPlacement;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.tamriel.settlement.plan.Halls;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An arrest whose jail has not been generated yet (docs/JAIL.md "Loading the jail"). The prisoner is not sent to the
 * jail dimension: the settlement that serves the crime's hold (its capital, a major town, or the guardhouse of a
 * minor town nearby) is generated around its jail, behind the same "Travelling to ..." cover and chunk tickets a fast
 * travel uses ({@link Prewarm}), and the prisoner is put in the cell once its anchors exist and its chunks are
 * built. The jail dimension is only the fallback when that fails within {@link #TIMEOUT_TICKS}.
 *
 * <p>While the jail loads the prisoner stays where they are, hidden and safe: spectator mode (nobody sees them, nothing
 * collides with or targets them, nothing hurts them), held in place every tick, rides and screens dropped. The state
 * is saved with the player ({@code crime.jail_pending}): a restart or a relog resumes the load.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
final class JailLoading {
    private JailLoading() {}

    /** Ticks after which a jail that is not there is given up for the jail dimension (60 s). */
    static final int TIMEOUT_TICKS = 20 * 60;
    /** The cover is up at least this long, like the opening's (so it is read, and the far chunks arrive). */
    static final int MIN_TICKS = 20 * 6;
    /** Key under the crime state of the player: the arrest that waits for its jail. */
    static final String PENDING = "jail_pending";

    private static final class Load {
        UUID player;
        String hold;
        int bounty;
        @Nullable String forced;
        String place;
        long start;
        long deadline;
        boolean secondPass;
        double x, y, z;
        float yaw, pitch;
    }

    private static final Map<UUID, Load> LOADS = new HashMap<>();

    /** What a load does this tick. */
    enum Step { WAIT, ENTER, FALLBACK }

    /**
     * The whole decision: the cell is ready (anchors and chunks) and the cover has been up its minimum time, else wait
     * until the deadline, then fall back.
     */
    static Step step(boolean cellReady, long now, long start, long deadline) {
        if (cellReady && now - start >= MIN_TICKS) return Step.ENTER;
        return now >= deadline ? Step.FALLBACK : Step.WAIT;
    }

    // ------------------------------------------------------------------ state

    /** Whether the player is being held while their jail loads (works on both sides: the state is synced data). */
    static boolean isLoading(Player player) {
        return com.skycraft.platform.NbtData.hasType(Bounty.state(player), PENDING, net.minecraft.nbt.Tag.TAG_COMPOUND);
    }

    // ------------------------------------------------------------------ which jail

    private static volatile List<Place> jails;

    private static List<Place> jailPlaces() {
        List<Place> m = jails;
        if (m == null) {
            List<Place> out = new ArrayList<>();
            for (Place p : Places.all()) if (Halls.hasJail(p)) out.add(p);
            jails = m = out;
        }
        return m;
    }

    /**
     * The settlement whose jail serves a crime at {@code near} in {@code hold}: the guardhouse of a minor town of
     * that hold within 300 blocks, else the hold's capital, else the nearest major jail of the region, else the
     * nearest jail within 1500 blocks.
     */
    @Nullable
    static Place placeFor(String hold, BlockPos near) {
        List<Place> all = jailPlaces();
        if (all.isEmpty()) return null;
        String migrated = Holds.migrate(hold);
        Place best = null;
        long bestD = 300L * 300L;
        for (Place p : all) {
            if (!Halls.guardhouse(p) || !(p.regionId().equals(hold) || p.regionId().equals(migrated))) continue;
            long d = dist2(p, near);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) return best;
        Optional<Region> region = Regions.byId(migrated);
        if (region.isPresent() && region.get().capital() != null) {
            Optional<Place> cap = Places.byId(region.get().capital());
            if (cap.isPresent() && Halls.hasJail(cap.get())) return cap.get();
        }
        bestD = Long.MAX_VALUE;
        for (Place p : all) {
            if (Halls.guardhouse(p) || !(p.regionId().equals(hold) || p.regionId().equals(migrated))) continue;
            long d = dist2(p, near);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) return best;
        bestD = 1500L * 1500L;
        for (Place p : all) {
            long d = dist2(p, near);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static long dist2(Place p, BlockPos near) {
        long dx = p.x() - near.getX(), dz = p.z() - near.getZ();
        return dx * dx + dz * dz;
    }

    /** Whether the place's jail is recorded (its start chunk has been generated). */
    static boolean generated(ServerLevel overworld, String placeId) {
        return SettlementAnchors.find(overworld, placeId).map(e -> e.first(AnchorType.JAIL_CELL) != null).orElse(false);
    }

    /**
     * What must be loaded for the jail of {@code p}: the place's start chunk (which records the anchors of the pieces
     * it holds), the start chunk of the district that holds the jail piece, and the jail's own chunks (the baked
     * street plan knows where it stands; without it the middle of the place).
     */
    static List<int[]> pointsFor(Place p) {
        List<int[]> pts = new ArrayList<>();
        pts.add(new int[]{p.x(), p.z(), 1});
        Spec jail = null;
        try {
            List<Spec> layout = com.skycraft.worldplan.street.StreetPlan.get().layout(p.id());
            if (layout != null) {
                for (Spec s : layout) {
                    if (s.kind == Archetype.JAIL) {
                        jail = s;
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("[jail] no baked layout for {}", p.id());
        }
        if (jail == null) {
            pts.add(new int[]{p.x(), p.z(), 3});
            return pts;
        }
        pts.add(new int[]{jail.centerX(), jail.centerZ(), 3});
        if (DistrictPlacement.cells(Cities.reach(p)) > 0) {
            int acx = p.x() >> 4, acz = p.z() >> 4;
            int dx = (jail.centerX() >> 4) - acx, dz = (jail.centerZ() >> 4) - acz;
            int ix = Math.abs(dx) <= 6 ? 0 : Math.round(dx / 12f), iz = Math.abs(dz) <= 6 ? 0 : Math.round(dz / 12f);
            pts.add(new int[]{(acx + ix * DistrictPlacement.STEP) << 4, (acz + iz * DistrictPlacement.STEP) << 4, 1});
        }
        return pts;
    }

    // ------------------------------------------------------------------ begin

    /**
     * Starts loading the jail for an arrest. Returns false (the caller uses the jail dimension) when there is no
     * place to build a jail for, when the jail is already generated (then it is simply full) or the load cannot start.
     */
    static boolean begin(ServerPlayer player, String hold, int bounty, @Nullable String forced) {
        if (isLoading(player)) return true;
        MinecraftServer server = player.level().getServer();
        ServerLevel overworld = server.overworld();
        Place target = forced != null ? Places.byId(forced).filter(Halls::hasJail).orElse(null) : placeFor(hold, player.blockPosition());
        if (target == null || generated(overworld, target.id())) return false;
        Load l = new Load();
        l.player = player.getUUID();
        l.hold = hold;
        l.bounty = bounty;
        l.forced = forced;
        l.place = target.id();
        l.x = player.getX();
        l.y = player.getY();
        l.z = player.getZ();
        l.yaw = player.getYRot();
        l.pitch = player.getXRot();
        CompoundTag tag = new CompoundTag();
        tag.putString("hold", hold);
        tag.putInt("bounty", bounty);
        tag.putString("forced", forced == null ? "" : forced);
        tag.putString("place", l.place);
        tag.putInt("mode", player.gameMode.getGameModeForPlayer().getId());
        Bounty.state(player).put(PENDING, tag);
        Bounty.state(player).remove("hostile_until");
        SkyData.get(player).markDirty();
        hide(player);
        return arm(player, l, server);
    }

    private static boolean arm(ServerPlayer player, Load l, MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        Place target = Places.byId(l.place).orElse(null);
        if (target == null) {
            release(player);
            return false;
        }
        l.start = server.getTickCount();
        l.deadline = l.start + TIMEOUT_TICKS;
        LOADS.put(l.player, l);
        Component label = Component.translatable("crime.skycraft.jail.loading_label", target.name());
        // the prewarm is for the player's level; the settlement is in the overworld
        if (player.level() == overworld) {
            Prewarm.start(player, pointsFor(target), label, TIMEOUT_TICKS, () -> { });
        } else {
            // a crime in another dimension: tickets in the overworld are placed through a silent hold
            for (int[] pt : pointsFor(target)) Prewarm.hold(overworld, pt[0], pt[1], pt[2], TIMEOUT_TICKS + 200);
        }
        progress(player, l, overworld, l.start); // the cover goes up at once
        return true;
    }

    /** Resumes an arrest that was waiting for its jail when the player left (or the server stopped). */
    static void resume(ServerPlayer player) {
        CompoundTag tag = Bounty.state(player).getCompoundOrEmpty(PENDING);
        if (tag.isEmpty() || LOADS.containsKey(player.getUUID())) return;
        Load l = new Load();
        l.player = player.getUUID();
        l.hold = tag.getStringOr("hold","");
        l.bounty = tag.getIntOr("bounty",0);
        l.forced = tag.getStringOr("forced", "").isEmpty() ? null : tag.getStringOr("forced","");
        l.place = tag.getStringOr("place","");
        l.x = player.getX();
        l.y = player.getY();
        l.z = player.getZ();
        l.yaw = player.getYRot();
        l.pitch = player.getXRot();
        hide(player);
        if (!arm(player, l, player.level().getServer())) release(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LOADS.remove(event.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ safe and hidden

    /** Spectator mode: unseen, untouchable, nothing collides with or targets the player; the tick holds them in place. */
    static void hide(ServerPlayer player) {
        player.stopRiding();
        player.closeContainer();
        player.setGameMode(GameType.SPECTATOR);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        Guards.pacify(player);
    }

    /** Puts the game mode back and forgets the pending arrest (the caller starts the sentence). */
    static void release(ServerPlayer player) {
        CompoundTag tag = Bounty.state(player).getCompoundOrEmpty(PENDING);
        GameType mode = GameType.byId(tag.contains("mode") ? tag.getIntOr("mode",0) : GameType.SURVIVAL.getId());
        if (mode == GameType.SPECTATOR) mode = GameType.SURVIVAL;
        player.setGameMode(mode);
        Bounty.state(player).remove(PENDING);
        SkyData.get(player).markDirty();
        LOADS.remove(player.getUUID());
        SkyNetwork.sendToPlayer(player, new PerfPackets.PrewarmProgress(0, 0, Component.empty()));
        SkyNetwork.sendToPlayer(player, new CrimePackets.JailCover(false, Component.empty(), Component.empty(), 0, 0));
    }

    // ------------------------------------------------------------------ the loop

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || LOADS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        for (Iterator<Load> it = LOADS.values().iterator(); it.hasNext(); ) {
            Load l = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(l.player);
            if (player == null) {
                it.remove();
                continue;
            }
            if (!player.isAlive()) continue;
            // held in place: nothing can carry the player off while they are hidden
            if (player.level().dimension() == playerDimension(player) && player.distanceToSqr(l.x, l.y, l.z) > 0.25) {
                player.connection.teleport(l.x, l.y, l.z, l.yaw, l.pitch);
            }
            if (now % 5 != 0) continue;
            ServerLevel overworld = server.overworld();
            Jail.Site site = Jail.claimCell(overworld, l.place, player);
            boolean cell = site != null && Prewarm.isReady(overworld, site.stand().getX(), site.stand().getZ());
            if (site != null && !cell && !Prewarm.busy(l.player) && !l.secondPass) {
                // the anchors are there but the building's chunks are not (the plan did not say where it is)
                l.secondPass = true;
                Prewarm.start(player, List.of(new int[]{site.stand().getX(), site.stand().getZ(), 2}),
                        Component.translatable("crime.skycraft.jail.loading_label", Places.byId(l.place).map(Place::name).orElse(Component.empty())),
                        TIMEOUT_TICKS, () -> { });
            }
            Step step = step(cell, now, l.start, l.deadline);
            if (step == Step.WAIT) {
                if (now % 10 == 0 || now - l.start < 10) progress(player, l, overworld, now);
                continue;
            }
            it.remove();
            release(player);
            if (step == Step.ENTER) {
                Jail.startSentence(player, l.hold, l.bounty, l.forced, site);
            } else {
                Skycraft.LOGGER.warn("[jail] the jail of {} was not ready after {} s: the jail dimension serves {}", l.place,
                        TIMEOUT_TICKS / 20, player.getGameProfile().name());
                Jail.sendToDimension(player, l.hold, l.bounty, l.forced);
            }
        }
    }

    private static net.minecraft.resources.ResourceKey<Level> playerDimension(ServerPlayer player) {
        return player.level().dimension();
    }

    /** Chunks ready of the needed ones, for the cover; keeps the bar alive between the prewarm's own updates. */
    private static void progress(ServerPlayer player, Load l, ServerLevel overworld, long now) {
        Place p = Places.byId(l.place).orElse(null);
        if (p == null) return;
        int ready = 0, total = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] pt : pointsFor(p)) {
            for (int dx = -pt[2]; dx <= pt[2]; dx++) {
                for (int dz = -pt[2]; dz <= pt[2]; dz++) {
                    int cx = (pt[0] >> 4) + dx, cz = (pt[1] >> 4) + dz;
                    if (!seen.add(net.minecraft.world.level.ChunkPos.pack(cx, cz))) continue;
                    total++;
                    if (overworld.getChunkSource().getChunkNow(cx, cz) != null) ready++;
                }
            }
        }
        // the opening's cover ("The valley wakes") with the jail's title: the world is never seen while it builds
        SkyNetwork.sendToPlayer(player, new CrimePackets.JailCover(true, Component.translatable("crime.skycraft.jail.loading_title"),
                Component.translatable("crime.skycraft.jail.loading_sub", p.name()), ready, Math.max(1, total)));
    }
}
