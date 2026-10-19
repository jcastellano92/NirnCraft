package com.nirncraft.creatures.travel;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Currency;
import com.nirncraft.core.Notifier;
import com.nirncraft.creatures.Bestiary;
import com.nirncraft.creatures.entity.SiltStriderEntity;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.dialogue.DialogueOption;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.vitals.Vitals;
import com.nirncraft.world.FastTravelHandler;
import com.nirncraft.world.RestManager;
import com.nirncraft.world.WorldPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Silt strider travel between Vvardenfell's ports, Morrowind-style: a strider waits at each port (placed when a
 * player comes near and none is there), and talking to it offers the other ports for a fare. Travelling moves the
 * player (with followers and mount), passes the hours of the journey, and fades the screen like carriage travel.
 *
 * <p>Ports are the named places below when the world has them; Balmora, Vivec, Ald'ruhn and Ebonheart fall back to
 * their anchors from the world plan.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class StriderPorts {
    /** A port: place id and fallback anchor (null = only when the place exists). */
    private record Port(String id, Integer x, Integer z) {}

    private static final List<Port> PORTS = List.of(
            new Port("seyda_neen", null, null),
            new Port("balmora", 6300, 500),
            new Port("vivec", 7400, 1300),
            new Port("ald_ruhn", 6100, -500),
            new Port("aldruhn", 6100, -500),
            new Port("ebonheart", 4700, 2400),
            new Port("gnisis", null, null),
            new Port("maar_gan", null, null),
            new Port("suran", null, null),
            new Port("molag_mar", null, null),
            new Port("khuul", null, null),
            new Port("sadrith_mora", null, null),
            new Port("vos", null, null),
            new Port("tel_mora", null, null),
            new Port("raven_rock", 4300, -2900),
            new Port("fort_frostmoth", null, null),
            new Port("tel_mithryn", 5007, -2722),
            new Port("blacklight", 3600, -300),
            new Port("mournhold", null, null),
            new Port("kragenmoor", null, null),
            new Port("narsis", null, null),
            new Port("tear", 7300, 3850),
            new Port("necrom", 8900, 3600)
    );
    private static final int FARE = 30;

    private static boolean dialogueRegistered = false;

    private StriderPorts() {}

    /** Called from module init so the dialogue provider is registered. */
    public static synchronized void init() {
        if (!dialogueRegistered) {
            Dialogue.registerProvider(StriderPorts::options);
            dialogueRegistered = true;
        }
        com.nirncraft.travel.TravelSelftest.init();
    }

    /**
     * Distance-scaled Silt Strider fare in gold: {@code 15 + round(distanceBlocks * 0.035f)}.
     */
    public static int fareForDistance(int distanceBlocks) {
        return 15 + Math.round(Math.max(0, distanceBlocks) * 0.035f);
    }

    public static int fareForDistance(double distanceBlocks) {
        return fareForDistance((int) Math.round(distanceBlocks));
    }

    /** Returns the network Silt Strider offers from {@code portId}. */
    public static List<com.nirncraft.travel.TravelNetworks.Offer> destinations(String portId) {
        if (portId == null || portId.isBlank()) return List.of();
        List<com.nirncraft.travel.TravelNetworks.Offer> direct =
                com.nirncraft.travel.TravelNetworks.offersFrom(portId, com.nirncraft.travel.TravelMode.STRIDER);
        if (!direct.isEmpty()) return direct;
        if ("ald_ruhn".equals(portId)) {
            return com.nirncraft.travel.TravelNetworks.offersFrom("aldruhn", com.nirncraft.travel.TravelMode.STRIDER);
        }
        if ("aldruhn".equals(portId)) {
            return com.nirncraft.travel.TravelNetworks.offersFrom("ald_ruhn", com.nirncraft.travel.TravelMode.STRIDER);
        }
        return List.of();
    }

    /** The strider stand of a port: just outside the settlement, east side. Null when the port doesn't exist. */
    private static int[] stand(Port port) {
        Place place = Places.byId(port.id())
                .or(() -> "ald_ruhn".equals(port.id()) ? Places.byId("aldruhn") : java.util.Optional.empty())
                .orElse(null);
        if (place != null) return new int[]{place.x() + place.radius() + 10, place.z()};
        if (port.x() == null) return null;
        return new int[]{port.x() + 70, port.z()};
    }

    private static int[] standById(String portId) {
        for (Port p : PORTS) {
            if (p.id().equals(portId)) return stand(p);
        }
        Place place = Places.byId(portId)
                .or(() -> "ald_ruhn".equals(portId) ? Places.byId("aldruhn") : java.util.Optional.empty())
                .orElse(null);
        if (place != null) return new int[]{place.x() + place.radius() + 10, place.z()};
        return com.nirncraft.travel.TravelNetworks.node(portId)
                .map(n -> new int[]{n.x() + 40, n.z()})
                .orElse(null);
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        if ((level.getGameTime() + player.getId() * 11L) % 200 != 0 || level.dimension() != Level.OVERWORLD) return;
        for (Port port : PORTS) {
            if ("aldruhn".equals(port.id())) continue; // avoid duplicate spawn with ald_ruhn
            int[] at = stand(port);
            if (at == null) continue;
            long dx = player.getBlockX() - at[0];
            long dz = player.getBlockZ() - at[1];
            if (dx * dx + dz * dz > 160L * 160L || !level.hasChunk(at[0] >> 4, at[1] >> 4)) continue;
            if (!level.getEntitiesOfClass(SiltStriderEntity.class, new AABB(at[0] - 48, level.getMinY(), at[1] - 48,
                    at[0] + 48, (level.getMaxY()+1), at[1] + 48)).isEmpty()) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at[0], at[1]);
            SiltStriderEntity strider = Bestiary.SILT_STRIDER.get().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (strider == null) continue;
            BlockPos pos = new BlockPos(at[0], y, at[1]);
            strider.snapTo(pos.getX() + 0.5, y, pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
            strider.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
            strider.setHome(pos, port.id());
            level.addFreshEntity(strider);
        }
    }

    public static void talk(ServerPlayer player, SiltStriderEntity strider) {
        Dialogue.open(player, strider, Component.translatable("dialogue.nirncraft.silt_strider.greeting"));
    }

    private static void options(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!(npc instanceof SiltStriderEntity strider)) return;
        int order = 150;
        List<com.nirncraft.travel.TravelNetworks.Offer> offers = destinations(strider.port());
        if (!offers.isEmpty()) {
            java.util.Set<String> seenPlaces = new java.util.HashSet<>();
            for (com.nirncraft.travel.TravelNetworks.Offer offer : offers) {
                String destId = offer.destination().id();
                String placeKey = offer.destination().place() != null ? offer.destination().place() : destId;
                if (!seenPlaces.add(placeKey)) continue;
                int[] at = standById(destId);
                if (at == null) continue;
                int fare = offer.fee() > 0 ? offer.fee() : fareForDistance(offer.distance());
                Component name = offer.destination().displayName();
                Component label = Component.translatable(
                        "travel.nirncraft.offer.strider",
                        name,
                        com.nirncraft.roads.Carriages.formatDistance(offer.distance()),
                        com.nirncraft.roads.Carriages.formatDuration(offer.distance()),
                        fare
                );
                out.add(new DialogueOption("creatures.strider." + destId,
                        label, order++,
                        (p, n) -> travel(p, n, at, name, fare, destId)));
            }
            return;
        }
        int[] originAt = standById(strider.port());
        for (Port port : PORTS) {
            if ("aldruhn".equals(port.id()) || port.id().equals(strider.port())) continue;
            int[] at = stand(port);
            if (at == null) continue;
            double dist = originAt != null ? Math.hypot(at[0] - originAt[0], at[1] - originAt[1]) : 800.0;
            int fare = fareForDistance(dist);
            Component name = Places.byId(port.id()).map(Place::name)
                    .orElse(Component.translatable("place.nirncraft." + port.id()));
            Component label = Component.translatable(
                    "travel.nirncraft.offer.strider",
                    name,
                    com.nirncraft.roads.Carriages.formatDistance(dist),
                    com.nirncraft.roads.Carriages.formatDuration(dist),
                    fare
            );
            out.add(new DialogueOption("creatures.strider." + port.id(),
                    label, order++,
                    (p, n) -> travel(p, n, at, name, fare, port.id())));
        }
    }

    public static void ride(ServerPlayer player, LivingEntity strider, int[] at, Component name, int fare, String destId) {
        travel(player, strider, at, name, fare, destId);
    }

    private static void travel(ServerPlayer player, LivingEntity strider, int[] at, Component name, int fare, String destId) {
        if (Vitals.inCombat(player)) {
            Dialogue.open(player, strider, Component.translatable("world.nirncraft.travel.combat"));
            return;
        }
        if (com.nirncraft.perf.Prewarm.busy(player.getUUID())) return;
        if (fare < 0 || !Currency.canAfford(player, fare)) {
            Dialogue.open(player, strider, Component.translatable("dialogue.nirncraft.silt_strider.no_gold", fare));
            return;
        }
        com.nirncraft.travel.TravelNetworks.prewarmTo(player, at[0], at[1], name, () -> {
            if (!Currency.take(player, fare)) {
                Dialogue.open(player, strider, Component.translatable("dialogue.nirncraft.silt_strider.no_gold", fare));
                return;
            }
            Dialogue.endConversation(player, strider.getId());
            finishTravel(player, at, name, destId);
        });
    }

    private static void finishTravel(ServerPlayer player, int[] at, Component name, String destId) {
        if (!player.isAlive()) return;
        ServerLevel level = player.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at[0] - 4, at[1]);
        double tx = at[0] - 4 + 0.5;
        double tz = at[1] + 0.5;
        double distance = Math.sqrt(player.distanceToSqr(tx, y, tz));
        Entity vehicle = player.getVehicle();
        List<Mob> followers = new ArrayList<>(level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, player)));
        player.stopRiding();
        player.teleportTo(level, tx, y, tz, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
        player.resetFallDistance();
        if (vehicle != null && vehicle.isAlive()) {
            vehicle.teleportTo(tx, y, tz);
            player.startRiding(vehicle, true, true);
        }
        int i = 0;
        for (Mob mob : followers) {
            double a = i++ * (Math.PI * 2 / Math.max(1, followers.size()));
            mob.getNavigation().stop();
            mob.teleportTo(tx + Math.cos(a) * 1.5, y, tz + Math.sin(a) * 1.5);
        }
        int hours = 0;
        int perHour = com.nirncraft.world.WorldConfig.BLOCKS_PER_TRAVEL_HOUR.get();
        long online = player.level().getServer().getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
        if (perHour > 0 && online <= 1) {
            hours = Mth.clamp((int) Math.round(distance / perHour), 1, 24);
            RestManager.advanceTime(player.level().getServer(), hours * (long) RestManager.TICKS_PER_HOUR);
        }
        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(hours, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, Component.translatable("dialogue.nirncraft.silt_strider.arrived", name));
        com.nirncraft.travel.TravelNetworks.node(destId)
                .ifPresent(n -> com.nirncraft.travel.TravelNetworks.onArrival(player, n, com.nirncraft.travel.TravelMode.STRIDER));
    }
}
