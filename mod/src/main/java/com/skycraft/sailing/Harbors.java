package com.skycraft.sailing;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import com.skycraft.society.NpcRole;
import com.skycraft.society.Npcs;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the harbours of Nirn: builds a port's pier ({@link DockBuilder}) once a player comes near and its chunks are
 * loaded (or on demand for an arriving ferry, loading the chunks with tickets first), puts a harbormaster on it
 * (a society merchant tagged {@value #KEEPER_TAG}) and replaces them if they go missing, moors a ferry alongside,
 * and records the ports a player has visited (player data module {@code "sailing"}, list {@code "ports"}).
 *
 * <p>Other modules may register their own dock for a port with {@link #registerDock}: e.g. a coastal settlement that
 * builds its own harbour. A pier is then never built there; the harbormaster stands on the registered spot.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Harbors {
    public static final String KEEPER_TAG = "skycraft_harbormaster";
    public static final String MODULE = "sailing";
    private static final net.neoforged.neoforge.registries.DeferredRegister<TicketType> TICKETS =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.TICKET_TYPE, Skycraft.MODID);
    private static final java.util.function.Supplier<TicketType> TICKET = TICKETS.register("port", () -> new TicketType(400, TicketType.FLAG_LOADING));
    public static void register(net.neoforged.bus.api.IEventBus bus) { TICKETS.register(bus); }
    private static final int BUILD_RANGE = 176;
    private static final int DISCOVER_RANGE = 40;

    private static int ticks;
    /** Consecutive checks a port's keeper was missing while a player stood nearby. */
    private static final Map<String, Integer> MISSING = new HashMap<>();
    /** Game time a pier could not be built (no shore found), so the search is not repeated every few seconds. */
    private static final Map<String, Long> FAILED = new HashMap<>();

    private Harbors() {}

    /**
     * Public hook: a settlement (or anything else) built its own dock for {@code portId}; its pier head is
     * {@code pierHead} (the block passengers step onto). Call it on the server before or after the port is visited.
     */
    public static void registerDock(MinecraftServer server, String portId, BlockPos pierHead) {
        SailingData data = SailingData.get(server);
        data.external.put(portId, pierHead.immutable());
        SailingData.Dock d = data.dock(portId);
        if (d == null || !d.adopted) {
            d = d == null ? new SailingData.Dock() : d;
            d.arrival = pierHead.immutable();
            d.keeper = pierHead.immutable();
            d.adopted = true;
            data.put(portId, d);
        }
        data.setDirty();
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (event.getServer().overworld() == null) return;
        SeaRoutes.warmUp();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MISSING.clear();
        FAILED.clear();
        ticks = 0;
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || ++ticks % 40 != 0) return;
        if (!SailingConfig.PORTS.get()) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null || level.players().isEmpty() || !SeaRoutes.sitesReady()) return;
        SailingData data = SailingData.get(event.getServer());
        for (Port port : Ports.all()) {
            PortSites.Site site = PortSites.of(port);
            ServerPlayer near = null;
            double best = (double) BUILD_RANGE * BUILD_RANGE;
            for (ServerPlayer p : level.players()) {
                double dx = p.getX() - site.pierX(), dz = p.getZ() - site.pierZ();
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    near = p;
                }
            }
            if (near == null) continue;
            SailingData.Dock dock = data.dock(port.id());
            if (dock == null) {
                Long failed = FAILED.get(port.id());
                if (failed != null && level.getGameTime() - failed < 6000) continue;
                if (chunksLoaded(level, site)) dock = build(level, port, site);
                if (dock == null) continue;
            }
            maintain(level, port, dock);
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().distSqr(dock.arrival) < DISCOVER_RANGE * DISCOVER_RANGE) discover(p, port);
            }
        }
    }

    // ------------------------------------------------------------------ building

    private static boolean chunksLoaded(ServerLevel level, PortSites.Site site) {
        int[] fp = DockBuilder.footprint(site);
        for (int cx = fp[0] >> 4; cx <= fp[2] >> 4; cx++) {
            for (int cz = fp[1] >> 4; cz <= fp[3] >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    /** Asks for a port's chunks to be loaded (they arrive over the next ticks); true once they all are. */
    static boolean requestChunks(ServerLevel level, Port port) {
        PortSites.Site site = PortSites.of(port);
        int[] fp = DockBuilder.footprint(site);
        boolean all = true;
        for (int cx = fp[0] >> 4; cx <= fp[2] >> 4; cx++) {
            for (int cz = fp[1] >> 4; cz <= fp[3] >> 4; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                level.getChunkSource().addTicketWithRadius(TICKET.get(), cp, 1);
                if (!level.hasChunk(cx, cz)) all = false;
            }
        }
        return all;
    }

    /** The port's dock, built now if its chunks are loaded; null if it can't be yet. */
    @Nullable
    static SailingData.Dock ensure(ServerLevel level, Port port) {
        SailingData data = SailingData.get(level.getServer());
        SailingData.Dock dock = data.dock(port.id());
        if (dock != null) return dock;
        PortSites.Site site = PortSites.of(port);
        if (!chunksLoaded(level, site)) return null;
        return build(level, port, site);
    }

    @Nullable
    private static SailingData.Dock build(ServerLevel level, Port port, PortSites.Site site) {
        SailingData data = SailingData.get(level.getServer());
        BlockPos external = data.external.get(port.id());
        SailingData.Dock dock = new SailingData.Dock();
        DockBuilder.Result result = null;
        if (external != null) {
            dock.arrival = external;
            dock.keeper = external;
            dock.adopted = true;
        } else {
            long t0 = System.nanoTime();
            try {
                result = DockBuilder.build(level, port, site);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.warn("Could not build the pier of {}", port.id(), e);
            }
            if (result == null) {
                FAILED.put(port.id(), level.getGameTime());
                return null;
            }
            dock.arrival = result.arrival();
            dock.keeper = result.keeper();
            dock.adopted = result.adopted();
            Skycraft.LOGGER.info("Port {}: {} pier at {} in {} ms", port.id(), result.adopted() ? "adopted a" : "built a",
                    dock.arrival.toShortString(), (System.nanoTime() - t0) / 1_000_000);
        }
        data.put(port.id(), dock);
        spawnKeeper(level, port, dock);
        if (result != null && result.berth() != null && SailingConfig.MOORED_SHIPS.get()) moorFerry(level, port, dock, result);
        return dock;
    }

    // ------------------------------------------------------------------ keeper and ferry

    private static void maintain(ServerLevel level, Port port, SailingData.Dock dock) {
        if (!level.isPositionEntityTicking(dock.keeper)) return;
        Entity keeper = dock.harbormaster != null ? level.getEntity(dock.harbormaster) : null;
        if (keeper == null || !keeper.isAlive()) {
            List<Mob> found = level.getEntitiesOfClass(Mob.class, new AABB(dock.keeper).inflate(32),
                    m -> m.isAlive() && port.id().equals(m.getPersistentData().getStringOr(KEEPER_TAG, "")));
            keeper = found.isEmpty() ? null : found.get(0);
            if (keeper != null) {
                dock.harbormaster = keeper.getUUID();
                SailingData.get(level.getServer()).setDirty();
            }
        }
        if (keeper != null) {
            MISSING.remove(port.id());
            dock.keeperSeen = level.getGameTime();
            return;
        }
        int missing = MISSING.merge(port.id(), 1, Integer::sum);
        // wait a while (entities load after chunks) and at most once per in-game day
        if (missing >= 4 && level.getGameTime() - dock.keeperSeen > 24000) {
            MISSING.remove(port.id());
            spawnKeeper(level, port, dock);
        }
    }

    private static void spawnKeeper(ServerLevel level, Port port, SailingData.Dock dock) {
        NpcEntity npc = Npcs.create(level, dock.keeper, NpcRole.MERCHANT);
        if (npc == null) return;
        npc.getPersistentData().putString(KEEPER_TAG, port.id());
        Component name = npc.getCustomName() != null ? npc.getCustomName() : Component.literal("?");
        npc.setCustomName(Component.translatable(port.isLanding() ? "sailing.skycraft.npc.captain" : "sailing.skycraft.npc.harbormaster", name));
        npc.setHome(dock.keeper, 6);
        npc.setPersistenceRequired();
        if (level.addFreshEntity(npc)) {
            dock.harbormaster = npc.getUUID();
            dock.keeperSeen = level.getGameTime();
            SailingData.get(level.getServer()).setDirty();
        }
    }

    private static void moorFerry(ServerLevel level, Port port, SailingData.Dock dock, DockBuilder.Result result) {
        ShipEntity ship = SailingRegistry.type(port.ferry()).create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (ship == null || result.berth() == null) return;
        ship.snapTo(result.berth().x, result.berth().y, result.berth().z, result.berthYaw(), 0f);
        ship.initNew(null);
        ship.setMoored(true, port.id());
        ship.setSail(ShipEntity.SAIL_FURLED);
        Ports.routesFrom(port.id()).stream().filter(r -> r.ship() != null).findFirst().ifPresent(r -> ship.setCustomName(r.ship()));
        if (level.addFreshEntity(ship)) {
            dock.ferry = ship.getUUID();
            SailingData.get(level.getServer()).setDirty();
        }
    }

    // ------------------------------------------------------------------ discovery

    public static boolean knows(PlayerData data, String port) {
        ListTag list = data.module(MODULE).getListOrEmpty("ports");
        for (int i = 0; i < list.size(); i++) if (list.getStringOr(i, "").equals(port)) return true;
        return false;
    }

    static void discover(ServerPlayer player, Port port) {
        PlayerData data = SkyData.get(player);
        if (knows(data, port.id())) return;
        ListTag list = data.module(MODULE).getListOrEmpty("ports");
        list.add(StringTag.valueOf(port.id()));
        data.module(MODULE).put("ports", list);
        data.markDirty();
        Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, port.name(),
                Component.translatable(port.isLanding() ? "sailing.skycraft.landing_discovered" : "sailing.skycraft.port_discovered"));
    }

    /** The port whose harbour is at most {@code range} blocks from (x, z). */
    public static Optional<Port> portNear(int x, int z, int range) {
        Port best = null;
        double bestD = (double) range * range;
        for (Port p : Ports.all()) {
            PortSites.Site s = PortSites.of(p);
            double dx = s.pierX() - x, dz = s.pierZ() - z;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }
}
