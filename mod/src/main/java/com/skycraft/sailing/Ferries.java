package com.skycraft.sailing;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.party.Parties;
import com.skycraft.quest.party.Party;
import com.skycraft.vitals.Vitals;
import com.skycraft.world.FastTravelHandler;
import com.skycraft.world.LocationKind;
import com.skycraft.world.RestManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harbormasters' topics and the voyages they sell. "Book passage" lists every sea route from the port (with the named
 * ship that sails it, e.g. the Northern Maiden between Windhelm and Raven Rock) priced by the charted distance
 * ({@link SeaRoutes}); expeditions to the foreign lands cost more. "Buy a ship" sells rowboats, longships and cogs.
 *
 * <p>A voyage fades to the voyage screen ({@code client.VoyageOverlay}), loads and if needed builds the destination
 * pier, then moves the buyer, party members standing nearby (they sail free), mounts and followers onto the pier
 * head, and advances the clock by the time at sea (single player only, like carriages).</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Ferries {
    /** Ticks between buying passage and stepping ashore at the earliest (the screen is dark meanwhile). */
    private static final int MIN_DELAY = 50;
    /** Give up waiting for the destination pier after this long and land on the nearest safe shore instead. */
    private static final int MAX_WAIT = 600;
    private static final int PARTY_RANGE = 24;

    private record Voyage(UUID buyer, List<UUID> travellers, String to, int hours, long start) {
    }

    private static final List<Voyage> PENDING = new ArrayList<>();
    /** Open sub-menu per player: npc id and "passage" / "ships". */
    private static final Map<UUID, String> MENU = new HashMap<>();

    private Ferries() {}

    static void register() {
        com.skycraft.travel.TravelSelftest.init();
        Dialogue.registerProvider(Ferries::addOptions);
    }

    public static boolean isHarbormaster(LivingEntity npc) {
        return npc != null && npc.isAlive() && !npc.getPersistentData().getString(Harbors.KEEPER_TAG).isEmpty();
    }

    /** The fare of a route: fixed in ports.json / travel network, or base + distance (expeditions start higher). */
    public static int fare(Route route) {
        if (route.fare() >= 0) return route.fare();
        var offer = com.skycraft.travel.TravelNetworks.findOffer(route.a(), route.b(), com.skycraft.travel.TravelMode.BOAT);
        if (offer.isPresent() && offer.get().fee() > 0) return offer.get().fee();
        float dist = SeaRoutes.length(route);
        int base = route.expedition() ? SailingConfig.EXPEDITION_BASE_FARE.get() : SailingConfig.FERRY_BASE_FARE.get();
        return base + Math.round(dist / SailingConfig.FERRY_BLOCKS_PER_SEPTIM.get());
    }

    public static int hours(Route route) {
        int per = SailingConfig.SEA_BLOCKS_PER_HOUR.get();
        if (per <= 0) return 0;
        return Mth.clamp(Math.round(SeaRoutes.length(route) / per), 2, route.expedition() ? 240 : 96);
    }

    // ------------------------------------------------------------------ dialogue

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!isHarbormaster(npc)) return;
        String portId = npc.getPersistentData().getString(Harbors.KEEPER_TAG);
        Port port = Ports.byId(portId).orElse(null);
        if (port == null) return;
        Harbors.discover(player, port);
        String menu = MENU.getOrDefault(player.getUUID(), "");
        String prefix = npc.getId() + ":";
        if (menu.equals(prefix + "passage")) {
            int order = 150;
            java.util.Set<String> offeredIds = new java.util.HashSet<>();
            for (Route r : Ports.routesFrom(portId)) {
                Port dest = Ports.byId(r.other(portId)).orElse(null);
                if (dest == null || !offeredIds.add(dest.id())) continue;
                int fare = fare(r);
                Component label = r.ship() != null
                        ? Component.translatable(r.expedition() ? "sailing.skycraft.dialogue.expedition_ship" : "sailing.skycraft.dialogue.passage_ship",
                        dest.name(), r.ship(), fare)
                        : Component.translatable(r.expedition() ? "sailing.skycraft.dialogue.expedition" : "sailing.skycraft.dialogue.passage", dest.name(), fare);
                out.add(new DialogueOption("sailing.passage." + dest.id(), label, order++, (p, n) -> buyPassage(p, n, port, r)));
            }
            for (com.skycraft.travel.TravelNetworks.Offer offer : com.skycraft.travel.TravelNetworks.offersFrom(portId)) {
                if (!offer.mode().isWater()) continue;
                String destId = offer.destination().id();
                if (!offeredIds.add(destId)) continue;
                Component label = Component.translatable("travel.skycraft.offer." + offer.mode().id(),
                        offer.destination().displayName(), offer.fee());
                out.add(new DialogueOption("sailing.passage." + destId, label, order++, (p, n) -> {
                    MENU.remove(p.getUUID());
                    Dialogue.endConversation(p, n.getId());
                    com.skycraft.travel.TravelNetworks.travel(p, portId, destId, offer.mode());
                }));
            }
            out.add(new DialogueOption("sailing.back", Component.translatable("sailing.skycraft.dialogue.back"), 990, (p, n) -> reopen(p, n, "")));
            return;
        }
        if (menu.equals(prefix + "ships")) {
            int order = 150;
            for (ShipType t : ShipType.VALUES) {
                int price = SailingConfig.price(t);
                out.add(new DialogueOption("sailing.buy." + t.id, Component.translatable("sailing.skycraft.dialogue.buy",
                        Component.translatable("item.skycraft." + t.id), price), order++, (p, n) -> buyShip(p, n, t, price)));
            }
            out.add(new DialogueOption("sailing.back", Component.translatable("sailing.skycraft.dialogue.back"), 990, (p, n) -> reopen(p, n, "")));
            return;
        }
        if (SailingConfig.FERRIES.get() && !Ports.routesFrom(portId).isEmpty()) {
            out.add(new DialogueOption("sailing.passage", Component.translatable(port.isLanding()
                    ? "sailing.skycraft.dialogue.topic_return" : "sailing.skycraft.dialogue.topic_passage"), 140,
                    (p, n) -> reopen(p, n, "passage")));
        }
        if (!port.isLanding()) {
            out.add(new DialogueOption("sailing.ships", Component.translatable("sailing.skycraft.dialogue.topic_ships"), 141,
                    (p, n) -> reopen(p, n, "ships")));
        }
    }

    private static void reopen(ServerPlayer player, LivingEntity npc, String menu) {
        if (menu.isEmpty()) MENU.remove(player.getUUID());
        else MENU.put(player.getUUID(), npc.getId() + ":" + menu);
        Component line = Component.translatable(menu.equals("passage") ? "sailing.skycraft.dialogue.where_to"
                : menu.equals("ships") ? "sailing.skycraft.dialogue.ships_line" : "sailing.skycraft.dialogue.greeting");
        Dialogue.open(player, npc, line);
    }

    private static void buyShip(ServerPlayer player, LivingEntity npc, ShipType type, int price) {
        MENU.remove(player.getUUID());
        if (player.distanceToSqr(npc) > 8 * 8) return;
        if (!Currency.take(player, price)) {
            Dialogue.open(player, npc, Component.translatable("sailing.skycraft.dialogue.no_gold", price));
            return;
        }
        ItemStack ship = new ItemStack(SailingRegistry.SHIP_ITEMS.get(type).get());
        com.skycraft.inventory.Pack.giveOrDrop(player, ship);
        Dialogue.open(player, npc, Component.translatable("sailing.skycraft.dialogue.sold", ship.getHoverName()));
    }

    private static void buyPassage(ServerPlayer player, LivingEntity npc, Port from, Route route) {
        MENU.remove(player.getUUID());
        if (player.distanceToSqr(npc) > 8 * 8 || isTravelling(player.getUUID())) return;
        if (Vitals.inCombat(player)) {
            Dialogue.open(player, npc, Component.translatable("world.skycraft.travel.combat"));
            return;
        }
        if (CarryWeight.isOverencumbered(player)) {
            Dialogue.open(player, npc, Component.translatable("world.skycraft.travel.encumbered"));
            return;
        }
        Port dest = Ports.byId(route.other(from.id())).orElse(null);
        if (dest == null) return;
        int fare = fare(route);
        if (!Currency.take(player, fare)) {
            Dialogue.open(player, npc, Component.translatable("sailing.skycraft.dialogue.no_gold", fare));
            return;
        }
        Dialogue.endConversation(player, npc.getId());
        start(player, from, dest, route);
    }

    // ------------------------------------------------------------------ voyages

    public static boolean isTravelling(UUID player) {
        for (Voyage v : PENDING) if (v.travellers().contains(player)) return true;
        return false;
    }

    /** Starts a voyage for the buyer and the party members near them. Also used by {@code /skycraft-sailing}. */
    public static void start(ServerPlayer buyer, Port from, Port dest, Route route) {
        List<UUID> travellers = new ArrayList<>();
        travellers.add(buyer.getUUID());
        Party party = Parties.partyOf(buyer);
        if (party != null) {
            for (ServerPlayer m : Parties.onlineMembers(buyer.server, party)) {
                if (m == buyer || m.level() != buyer.level() || m.distanceToSqr(buyer) > PARTY_RANGE * PARTY_RANGE) continue;
                if (Vitals.inCombat(m) || isTravelling(m.getUUID())) continue;
                travellers.add(m.getUUID());
            }
        }
        int hours = hours(route);
        Component ship = route.ship() != null ? route.ship() : Component.translatable("sailing.skycraft.ship.generic");
        for (UUID id : travellers) {
            ServerPlayer p = buyer.server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            SkyNetwork.sendToPlayer(p, new SailingPackets.VoyageStart(from.name(), dest.name(), ship, hours, route.expedition()));
            if (p != buyer) Notifier.message(p, Component.translatable("sailing.skycraft.party_sails", buyer.getDisplayName(), dest.name()));
        }
        buyer.level().playSound(null, buyer.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 0.6f, 0.6f);
        PENDING.add(new Voyage(buyer.getUUID(), travellers, dest.id(), hours, buyer.level().getGameTime()));
        Harbors.requestChunks(buyer.server.overworld(), dest);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        for (Iterator<Voyage> it = PENDING.iterator(); it.hasNext(); ) {
            Voyage v = it.next();
            if (now - v.start() < MIN_DELAY) continue;
            Port dest = Ports.byId(v.to()).orElse(null);
            if (dest == null) {
                it.remove();
                continue;
            }
            boolean loaded = Harbors.requestChunks(level, dest);
            SailingData.Dock dock = loaded ? Harbors.ensure(level, dest) : null;
            if (dock == null && now - v.start() < MAX_WAIT) continue;
            it.remove();
            BlockPos arrival = dock != null ? dock.arrival : fallbackArrival(level, dest);
            arrive(server, level, v, dest, arrival);
        }
    }

    private static BlockPos fallbackArrival(ServerLevel level, Port dest) {
        PortSites.Site site = PortSites.of(dest);
        int x = site.shoreX(), z = site.shoreZ();
        level.getChunk(x >> 4, z >> 4);
        BlockPos anchor = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        BlockPos safe = FastTravelHandler.findArrival(level, anchor, LocationKind.TOWN);
        return safe != null ? safe : anchor;
    }

    private static void arrive(MinecraftServer server, ServerLevel level, Voyage v, Port dest, BlockPos arrival) {
        int i = 0;
        boolean anyone = false;
        float yaw = DockBuilder.yawOf(PortSites.of(dest).toLand());
        for (UUID id : v.travellers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive()) continue;
            if (p.level().dimension() != Level.OVERWORLD) continue;
            anyone = true;
            Entity vehicle = p.getVehicle();
            if (vehicle instanceof ShipEntity) vehicle = null; // nobody tows their own ship along
            List<Mob> followers = p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16),
                    m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, p));
            double ox = arrival.getX() + 0.5 + (i % 3 - 1) * 0.9, oz = arrival.getZ() + 0.5 + (i / 3) * 0.9;
            i++;
            p.stopRiding();
            p.teleportTo(level, ox, arrival.getY(), oz, yaw, 0f);
            p.resetFallDistance();
            if (vehicle != null && vehicle.isAlive() && vehicle.level() == level) {
                vehicle.teleportTo(ox, arrival.getY(), oz);
                vehicle.resetFallDistance();
                p.startRiding(vehicle, true);
            }
            int k = 0;
            for (Mob mob : followers) {
                double a = k++ * (Math.PI * 2 / Math.max(1, followers.size()));
                mob.getNavigation().stop();
                mob.teleportTo(ox + Math.cos(a) * 1.2, arrival.getY(), oz + Math.sin(a) * 1.2);
                mob.resetFallDistance();
            }
            Harbors.discover(p, dest);
        }
        if (!anyone) return;
        long online = server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
        boolean advanced = v.hours() > 0 && online <= v.travellers().size() && online <= 1;
        if (advanced) RestManager.advanceTime(server, v.hours() * (long) RestManager.TICKS_PER_HOUR);
        for (UUID id : v.travellers()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            Notifier.message(p, advanced
                    ? Component.translatable("sailing.skycraft.arrived_hours", dest.name(), v.hours())
                    : Component.translatable("sailing.skycraft.arrived", dest.name()));
        }
        level.playSound(null, arrival, SoundEvents.BOAT_PADDLE_WATER, SoundSource.NEUTRAL, 1f, 0.8f);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        MENU.clear();
    }
}
