package com.skycraft.sailing;

import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.perf.Prewarm;
import com.skycraft.roads.Carriages;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.travel.TravelMode;
import com.skycraft.travel.TravelNetworks;
import com.skycraft.travel.TravelNode;
import com.skycraft.vitals.Vitals;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Multi-modal water transport helper (Phase T7) bridging coastal packet ships, river skiffs,
 * reed-barges, and long-haul outer-realm expeditions between {@link TravelNetworks}, {@link Ports},
 * and {@link Ferries}.
 */
public final class FerryRoutes {

    private FerryRoutes() {}

    /** A water-borne route offer with distance, duration, and distance-scaled fare. */
    public record FerryOffer(
            String fromId,
            String toId,
            int x,
            int z,
            int fare,
            double distance,
            TravelMode mode,
            String path
    ) {
        public int hours() {
            return Carriages.estimateHours(distance);
        }

        public String formattedDistance() {
            return Carriages.formatDistance(distance);
        }

        public String formattedDuration() {
            return Carriages.formatDuration(distance);
        }

        public Component displayName() {
            Optional<TravelNode> node = TravelNetworks.node(toId);
            if (node.isPresent()) {
                return node.get().displayName();
            }
            return Places.byId(toId).map(Place::name)
                    .orElseGet(() -> Component.translatable("place.skycraft." + toId));
        }

        public Component label() {
            return Component.translatable(
                    "travel.skycraft.offer.ferry",
                    displayName(),
                    formattedDistance(),
                    formattedDuration(),
                    fare
            );
        }

        public String formatSummary() {
            return String.format(
                    Locale.ROOT,
                    "%s (%s, %s) \u2014 %dg",
                    toId,
                    formattedDistance(),
                    formattedDuration(),
                    fare
            );
        }
    }

    /**
     * Distance-scaled ferry/boat fare in gold:
     * {@code 15 + round(distanceBlocks * (oceanCrossing ? 0.055f : 0.025f))}, capped at 250g.
     */
    public static int fareForDistance(int distanceBlocks, boolean oceanCrossing) {
        int d = Math.max(0, distanceBlocks);
        float rate = oceanCrossing ? 0.055f : 0.025f;
        return Math.min(250, 15 + Math.round(d * rate));
    }

    public static int fareForDistance(double distanceBlocks, boolean oceanCrossing) {
        return fareForDistance((int) Math.round(distanceBlocks), oceanCrossing);
    }

    public static int fareForDistance(int distanceBlocks) {
        return fareForDistance(distanceBlocks, distanceBlocks >= 1800);
    }

    /**
     * Lists all water-transport offers ({@link TravelMode#BOAT}, {@link TravelMode#RIVER_BOAT},
     * {@link TravelMode#BARGE}, and {@link Ports} sea routes) from {@code fromPortOrPlaceId}.
     */
    public static List<FerryOffer> destinations(String fromPortOrPlaceId) {
        if (fromPortOrPlaceId == null || fromPortOrPlaceId.isBlank()) return List.of();
        String norm = fromPortOrPlaceId.trim().toLowerCase(Locale.ROOT);
        List<FerryOffer> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (TravelMode waterMode : List.of(TravelMode.BOAT, TravelMode.RIVER_BOAT, TravelMode.BARGE)) {
            for (TravelNetworks.Offer offer : TravelNetworks.offersFrom(norm, waterMode)) {
                TravelNode dest = offer.destination();
                String key = dest.place() != null ? dest.place() : dest.id();
                if (!seen.add(key)) continue;
                boolean ocean = waterMode == TravelMode.BOAT && offer.distance() >= 1800.0;
                int fare = offer.fee() > 0 ? offer.fee() : fareForDistance(offer.distance(), ocean);
                out.add(new FerryOffer(
                        norm,
                        dest.id(),
                        dest.x(),
                        dest.z(),
                        fare,
                        offer.distance(),
                        waterMode,
                        offer.route().path()
                ));
            }
        }

        Port port = Ports.byId(norm).orElse(null);
        if (port != null) {
            for (Route r : Ports.routesFrom(port.id())) {
                String targetId = r.other(port.id());
                Port target = Ports.byId(targetId).orElse(null);
                if (target == null) continue;
                String key = target.place() != null && !target.place().isBlank() ? target.place() : target.id();
                if (!seen.add(key)) continue;
                double dist = Math.hypot(target.x() - port.x(), target.z() - port.z());
                int fare = r.fare() > 0 ? r.fare() : fareForDistance(dist, r.expedition() || dist >= 1800.0);
                out.add(new FerryOffer(
                        port.id(),
                        target.id(),
                        target.x(),
                        target.z(),
                        fare,
                        dist,
                        TravelMode.BOAT,
                        r.key()
                ));
            }
        }

        out.sort(Comparator.comparingDouble(FerryOffer::distance));
        return out;
    }

    /**
     * Initiates a ferry or boat voyage for {@code player}, always running {@link Prewarm} on the
     * destination chunk before teleporting or completing the crossing.
     */
    public static boolean sail(ServerPlayer player, String fromId, String toId) {
        if (Vitals.inCombat(player)) {
            Notifier.message(player, Component.translatable("world.skycraft.travel.combat"));
            return false;
        }
        if (CarryWeight.isOverencumbered(player)) {
            Notifier.message(player, Component.translatable("world.skycraft.travel.encumbered"));
            return false;
        }
        for (FerryOffer offer : destinations(fromId)) {
            if (offer.toId().equalsIgnoreCase(toId)) {
                Port fromPort = Ports.byId(fromId).orElse(null);
                Port toPort = Ports.byId(toId).orElse(null);
                Route ferryRoute = null;
                if (fromPort != null && toPort != null) {
                    for (Route r : Ports.routesFrom(fromPort.id())) {
                        if (r.other(fromPort.id()).equalsIgnoreCase(toPort.id())) {
                            ferryRoute = r;
                            break;
                        }
                    }
                }
                final Port fPort = fromPort;
                final Port tPort = toPort;
                final Route fRoute = ferryRoute;
                Component destName = offer.displayName();
                Prewarm.Result result = Prewarm.request(player, offer.x(), offer.z(), destName,
                        () -> completeSail(player, fPort, tPort, fRoute, offer));
                if (result == Prewarm.Result.READY) return completeSail(player, fPort, tPort, fRoute, offer);
                return result == Prewarm.Result.STARTED;
            }
        }
        return TravelNetworks.travel(player, fromId, toId, TravelMode.BOAT);
    }

    private static boolean completeSail(
            ServerPlayer player,
            @org.jetbrains.annotations.Nullable Port fromPort,
            @org.jetbrains.annotations.Nullable Port toPort,
            @org.jetbrains.annotations.Nullable Route ferryRoute,
            FerryOffer offer
    ) {
        if (fromPort != null && toPort != null && ferryRoute != null) {
            if (offer.fare() > 0 && !Currency.take(player, offer.fare())) {
                Notifier.message(player, Component.translatable("travel.skycraft.no_gold", offer.fare()));
                return false;
            }
            Ferries.start(player, fromPort, toPort, ferryRoute);
            return true;
        }
        return TravelNetworks.travel(player, offer.fromId(), offer.toId(), offer.mode());
    }
}
