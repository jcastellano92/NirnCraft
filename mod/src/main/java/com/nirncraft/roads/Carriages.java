package com.nirncraft.roads;

import com.nirncraft.core.Currency;
import com.nirncraft.core.Holds;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.dialogue.Dialogue;
import com.nirncraft.dialogue.DialogueOption;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.Regions;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.vitals.Vitals;
import com.nirncraft.world.FastTravelHandler;
import com.nirncraft.world.LocationKind;
import com.nirncraft.world.RestManager;
import com.nirncraft.world.WorldData;
import com.nirncraft.world.WorldPackets;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Skyrim's paid carriages. Carriage drivers outside a capital or city take you to the other capitals and cities of
 * Tamriel ({@link Places}): every one in the driver's own province (in Skyrim, the nine hold capitals, like the
 * game), plus those of other provinces you have discovered, as long as the road there doesn't cross open sea (ships
 * come later). Inside Skyrim a ride costs 20-50 Septims by distance; longer roads in other provinces cost more.
 * Travel moves the player, their horse and followers, advances time, and plays the travel fade.
 */
public final class Carriages {
    /** Maximum single-stage overland carriage distance in blocks (~2.8 km). */
    public static final int MAX_CARRIAGE_DISTANCE = 2800;

    /** At most this many destinations in the dialogue besides the capitals (nearest first). */
    private static final int MAX_DESTINATIONS = 18;

    private static final java.util.Set<String> ISLAND_PROVINCES = java.util.Set.of(
            "solstheim", "summerset", "atmora", "yokuda", "akavir", "pyandonea", "thras"
    );

    private Carriages() {}

    /** A destination offered by a driver, with distance-scaled fare and stage distance in blocks. */
    public record Destination(Place place, int fare, double distance) {
        public int hours() {
            return estimateHours(distance);
        }

        public String formattedDistance() {
            return formatDistance(distance);
        }

        public String formattedDuration() {
            return formatDuration(distance);
        }

        public Component label() {
            return Component.translatable(
                    "travel.nirncraft.offer.carriage",
                    place.name(),
                    formattedDistance(),
                    formattedDuration(),
                    fare
            );
        }
    }

    public static void register() {
        com.nirncraft.travel.TravelSelftest.init();
        Dialogue.registerProvider(Carriages::addOptions);
    }

    /**
     * Distance-scaled carriage fare in gold:
     * {@code base = 20 + round(distanceBlocks * 0.045f)}, with a 25% discount (min 15g) if already discovered.
     */
    public static int fareForDistance(int distanceBlocks, boolean discovered) {
        int d = Math.max(0, distanceBlocks);
        int base = 20 + Math.round(d * 0.045f);
        return discovered ? Math.max(15, Math.round(base * 0.75f)) : base;
    }

    public static int fareForDistance(double distanceBlocks, boolean discovered) {
        return fareForDistance((int) Math.round(distanceBlocks), discovered);
    }

    public static String formatDistance(int distanceBlocks) {
        int d = Math.max(0, distanceBlocks);
        if (d >= 1000) {
            return String.format(java.util.Locale.ROOT, "%.1f km", d / 1000.0);
        }
        return d + "m";
    }

    public static String formatDistance(double distanceBlocks) {
        return formatDistance((int) Math.round(distanceBlocks));
    }

    public static int estimateHours(int distanceBlocks) {
        return Math.max(1, Math.round(Math.max(0, distanceBlocks) / 350.0f));
    }

    public static int estimateHours(double distanceBlocks) {
        return estimateHours((int) Math.round(distanceBlocks));
    }

    public static String formatDuration(int distanceBlocks) {
        return estimateHours(distanceBlocks) + "h";
    }

    public static String formatDuration(double distanceBlocks) {
        return formatDuration((int) Math.round(distanceBlocks));
    }

    public static String formatOffer(Destination dest) {
        return String.format(
                java.util.Locale.ROOT,
                "%s (%s, %s) \u2014 %dg",
                dest.place().id(),
                dest.formattedDistance(),
                dest.formattedDuration(),
                dest.fare()
        );
    }

    public static boolean isCarriageDriver(LivingEntity npc) {
        if (npc == null || !npc.isAlive()) return false;
        if (npc.getPersistentData().getBooleanOr("nirncraft_carriage_driver", false)) return true;
        if (npc.getPersistentData().getBooleanOr("nirncraft_stable_master", false)) return false;
        String desc = npc.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (desc.contains("carriage") || desc.contains("driver")) return true;
        if (npc.hasCustomName()) {
            String name = npc.getCustomName().getString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("carriage") || name.contains("driver")) return true;
        }
        return false;
    }

    /** Offline/testable overload returning carriage destinations from a given {@link Place}. */
    public static List<Destination> destinations(Place from) {
        return destinations(from, null);
    }

    /** Returns carriage destinations from a {@link Place}, optionally applying discovery discounts for {@code player}. */
    public static List<Destination> destinations(Place from, @org.jetbrains.annotations.Nullable ServerPlayer player) {
        if (from == null) return List.of();
        return computeDestinations(from.x(), from.z(), from.id(), provinceOf(from), from.regionId(), player);
    }

    /** The destinations a driver at {@code from} offers this player, nearest first. */
    public static List<Destination> destinations(ServerPlayer player, BlockPos from) {
        if (player != null && player.level().dimension() != Level.OVERWORLD) return List.of();
        Place nearest = Places.nearestSettlement(from.getX(), from.getZ(), 320);
        String fromId = nearest != null ? nearest.id() : "";
        String fromRegion = nearest != null ? nearest.regionId() : TamrielMap.region(from.getX(), from.getZ()).id();
        String province = player != null
                ? Holds.province(Holds.holdAt(player.level(), from))
                : (nearest != null ? provinceOf(nearest) : TamrielMap.region(from.getX(), from.getZ()).province());
        return computeDestinations(from.getX(), from.getZ(), fromId, province, fromRegion, player);
    }

    private static List<Destination> computeDestinations(
            int fromX,
            int fromZ,
            String fromPlaceId,
            String province,
            String fromRegionId,
            @org.jetbrains.annotations.Nullable ServerPlayer player
    ) {
        List<Destination> out = new ArrayList<>();
        com.nirncraft.core.PlayerData data = player != null ? SkyData.get(player) : null;
        java.util.Set<String> addedIds = new java.util.HashSet<>();
        if (fromPlaceId != null && !fromPlaceId.isEmpty()) {
            addedIds.add(fromPlaceId);
        }

        com.nirncraft.travel.TravelNode originNode = null;
        if (fromPlaceId != null && !fromPlaceId.isEmpty()) {
            originNode = com.nirncraft.travel.TravelNetworks.nodeForPlace(fromPlaceId)
                    .filter(n -> n.supports(com.nirncraft.travel.TravelMode.CARRIAGE))
                    .orElse(null);
        }
        if (originNode == null) {
            originNode = com.nirncraft.travel.TravelNetworks
                    .nearestNode(fromX, fromZ, com.nirncraft.travel.TravelMode.CARRIAGE, 320.0)
                    .orElse(null);
        }

        if (originNode != null) {
            // 1. Direct carriage routes from the data-driven provincial travel network (<= MAX_CARRIAGE_DISTANCE)
            List<com.nirncraft.travel.TravelNetworks.Offer> directOffers =
                    com.nirncraft.travel.TravelNetworks.offersFrom(originNode.id(), com.nirncraft.travel.TravelMode.CARRIAGE);
            for (com.nirncraft.travel.TravelNetworks.Offer offer : directOffers) {
                String placeId = offer.destination().place() != null ? offer.destination().place() : offer.destination().id();
                Place p = Places.byId(placeId).orElse(null);
                if (p == null || addedIds.contains(p.id())) continue;
                if (!canReachByOverlandCarriage(fromX, fromZ, province, fromRegionId, p)) continue;
                double dist = Math.hypot(p.x() - fromX, p.z() - fromZ);
                if (dist > MAX_CARRIAGE_DISTANCE) continue;
                addedIds.add(p.id());
                boolean discovered = data != null && WorldData.discoveredPlace(data, p.id());
                int fare = fareForDistance(dist, discovered);
                out.add(new Destination(p, fare, dist));
            }

            // 2. Two-hop relay to same-province capitals when each stage is <= MAX_CARRIAGE_DISTANCE
            //    (e.g. Whiterun -> Rorikstead -> Markarth along the Reach Road).
            for (com.nirncraft.travel.TravelNetworks.Offer firstLeg : directOffers) {
                double leg1 = Math.hypot(firstLeg.destination().x() - fromX, firstLeg.destination().z() - fromZ);
                if (leg1 > MAX_CARRIAGE_DISTANCE) continue;
                for (com.nirncraft.travel.TravelNetworks.Offer secondLeg : com.nirncraft.travel.TravelNetworks.offersFrom(
                        firstLeg.destination().id(), com.nirncraft.travel.TravelMode.CARRIAGE)) {
                    String placeId = secondLeg.destination().place() != null
                            ? secondLeg.destination().place()
                            : secondLeg.destination().id();
                    Place p = Places.byId(placeId).orElse(null);
                    if (p == null || addedIds.contains(p.id())) continue;
                    if (p.type() != PlaceType.CAPITAL || !provinceOf(p).equals(province)) continue;
                    if (!canReachByOverlandCarriage(fromX, fromZ, province, fromRegionId, p)) continue;
                    double leg2 = Math.hypot(
                            secondLeg.destination().x() - firstLeg.destination().x(),
                            secondLeg.destination().z() - firstLeg.destination().z()
                    );
                    if (leg2 > MAX_CARRIAGE_DISTANCE) continue;
                    double effectiveStageDist = Math.min(MAX_CARRIAGE_DISTANCE, Math.hypot(p.x() - fromX, p.z() - fromZ));
                    addedIds.add(p.id());
                    boolean discovered = data != null && WorldData.discoveredPlace(data, p.id());
                    out.add(new Destination(p, fareForDistance(effectiveStageDist, discovered), effectiveStageDist));
                }
            }
        }

        // 3. Nearby overland capitals/cities within MAX_CARRIAGE_DISTANCE on the same connected landmass
        List<Destination> cities = new ArrayList<>();
        boolean hasDataNetwork = !out.isEmpty();
        for (Place p : Places.all()) {
            if (p.type() != PlaceType.CAPITAL && p.type() != PlaceType.CITY) continue;
            if (addedIds.contains(p.id())) continue;
            double dist = Math.hypot(p.x() - fromX, p.z() - fromZ);
            if (dist < p.radius() + 200 || dist > MAX_CARRIAGE_DISTANCE) continue;
            if (!canReachByOverlandCarriage(fromX, fromZ, province, fromRegionId, p)) continue;
            String destProvince = provinceOf(p);
            boolean sameProvince = destProvince.equals(province);
            if (hasDataNetwork && sameProvince && p.type() != PlaceType.CAPITAL) continue;
            boolean discovered = data != null && WorldData.discoveredPlace(data, p.id());
            Destination d = new Destination(p, fareForDistance(dist, discovered), dist);
            if (p.type() == PlaceType.CAPITAL) {
                addedIds.add(p.id());
                out.add(d);
            } else if (sameProvince || discovered || data == null) {
                addedIds.add(p.id());
                cities.add(d);
            }
        }
        cities.sort(Comparator.comparingDouble(Destination::distance));
        for (Destination d : cities) {
            if (out.size() >= MAX_DESTINATIONS) break;
            out.add(d);
        }
        out.sort(Comparator.comparingDouble(Destination::distance));
        return out;
    }

    private static boolean canReachByOverlandCarriage(
            int fromX, int fromZ, String fromProvince, String fromRegionId, Place dest
    ) {
        String destProvince = provinceOf(dest);
        String destRegion = dest.regionId();
        boolean fromSolstheim = "solstheim".equals(fromProvince) || (fromRegionId != null && fromRegionId.contains("solstheim"));
        boolean destSolstheim = "solstheim".equals(destProvince) || (destRegion != null && destRegion.contains("solstheim"));
        if (fromSolstheim || destSolstheim) return false; // Solstheim uses Silt Striders, Boats, and Foot trails
        if ("stros_mkai".equals(dest.id()) || (destRegion != null && destRegion.contains("stros_mkai"))
                || (fromRegionId != null && fromRegionId.contains("stros_mkai"))) {
            return false; // Island port of Stros M'Kai requires a ship
        }
        if ("isle_of_balfiera".equals(dest.id()) || "balfiera".equals(dest.id())) {
            return false; // Balfiera in Iliac Bay requires a packet ship
        }
        if (ISLAND_PROVINCES.contains(fromProvince) || ISLAND_PROVINCES.contains(destProvince)) {
            if (!fromProvince.equals(destProvince)) return false;
        }
        return Landmass.connected(fromX, fromZ, dest.x(), dest.z());
    }

    /** Legacy fare helper delegating to {@link #fareForDistance(double, boolean)}. */
    static int fare(int baseFare, double dist, boolean withinSkyrim) {
        return fareForDistance(dist, false);
    }

    private static String provinceOf(Place p) {
        if (!p.regionId().isEmpty()) {
            if (p.regionId().contains("solstheim")) return "solstheim";
            return Regions.byId(p.regionId()).map(r -> r.province())
                    .orElse(p.regionId().contains("/") ? p.regionId().substring(0, p.regionId().indexOf('/')) : p.regionId());
        }
        return TamrielMap.region(p.x(), p.z()).province();
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!RoadsConfig.CARRIAGES.get()) return;
        if (!isCarriageDriver(npc)) return;
        int order = 170;
        for (Destination dest : destinations(player, npc.blockPosition())) {
            out.add(new DialogueOption(
                    "roads.carriage.dest." + dest.place().id(),
                    dest.label(),
                    order++,
                    (p, n) -> travelToDestination(p, n, dest.place(), dest.fare())
            ));
        }
    }

    public static void ride(ServerPlayer player, Place dest, int fare) {
        ride(player, null, dest, fare);
    }

    public static void ride(ServerPlayer player, @org.jetbrains.annotations.Nullable LivingEntity npc, Place dest, int fare) {
        travelToDestination(player, npc, dest, fare);
    }

    private static void travelToDestination(ServerPlayer player, @org.jetbrains.annotations.Nullable LivingEntity npc, Place dest, int fare) {
        if (Vitals.inCombat(player)) {
            if (npc != null) Dialogue.open(player, npc, Component.translatable("world.nirncraft.travel.combat"));
            else Notifier.message(player, Component.translatable("world.nirncraft.travel.combat"));
            return;
        }
        if (com.nirncraft.inventory.CarryWeight.isOverencumbered(player)) {
            if (npc != null) Dialogue.open(player, npc, Component.translatable("world.nirncraft.travel.encumbered"));
            else Notifier.message(player, Component.translatable("world.nirncraft.travel.encumbered"));
            return;
        }
        if (npc != null && player.distanceToSqr(npc) > 8 * 8) return;

        if (com.nirncraft.perf.Prewarm.busy(player.getUUID())) return;
        if (fare < 0 || !Currency.canAfford(player, fare)) {
            if (npc != null) Dialogue.open(player, npc, Component.translatable("dialogue.nirncraft.carriage.no_gold", fare));
            else Notifier.message(player, Component.translatable("dialogue.nirncraft.carriage.no_gold", fare));
            return;
        }
        com.nirncraft.travel.TravelNetworks.prewarmTo(player, dest.x(), dest.z(), dest.name(), () -> {
            // Charge only the accepted journey, after terrain is ready. Cancellation leaves gold untouched.
            if (!Currency.take(player, fare)) {
                if (npc != null) Dialogue.open(player, npc, Component.translatable("dialogue.nirncraft.carriage.no_gold", fare));
                else Notifier.message(player, Component.translatable("dialogue.nirncraft.carriage.no_gold", fare));
                return;
            }
            finishTravel(player, npc, dest, fare);
        });
    }

    private static void finishTravel(ServerPlayer player, LivingEntity npc, Place dest, int fare) {
        if (!player.isAlive()) return;
        ServerLevel level = player.level();
        BlockPos targetPos = stopFor(level, dest);

        // Find mounted horse if riding, or nearby followers
        Entity vehicle = player.getVehicle();
        List<Mob> followers = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, player));

        double distance = Math.sqrt(player.blockPosition().distSqr(targetPos));

        player.stopRiding();
        player.teleportTo(level, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, java.util.Set.of(),  player.getYRot(), player.getXRot(), true);
        player.resetFallDistance();

        if (vehicle != null && vehicle.isAlive()) {
            vehicle.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
            vehicle.resetFallDistance();
            player.startRiding(vehicle, true, true);
        }

        int i = 0;
        for (Mob mob : followers) {
            double angle = i++ * (Math.PI * 2 / Math.max(1, followers.size()));
            double fx = targetPos.getX() + 0.5 + Math.cos(angle) * 1.5;
            double fz = targetPos.getZ() + 0.5 + Math.sin(angle) * 1.5;
            mob.getNavigation().stop();
            mob.teleportTo(fx, targetPos.getY(), fz);
            mob.resetFallDistance();
        }

        // Advance time for carriage journey (carriages are slower than walking straight there)
        int hours = 0;
        int perHour = com.nirncraft.world.WorldConfig.BLOCKS_PER_TRAVEL_HOUR.get();
        long online = player.level().getServer().getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
        if (perHour > 0 && online <= 1) {
            hours = Mth.clamp((int) Math.round(distance / perHour), 1, 72);
            RestManager.advanceTime(player.level().getServer(), hours * (long) RestManager.TICKS_PER_HOUR);
        }

        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(hours, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, hours > 0
                ? Component.translatable("dialogue.nirncraft.carriage.arrived_hours", dest.name(), hours)
                : Component.translatable("dialogue.nirncraft.carriage.arrived", dest.name()));
        com.nirncraft.travel.TravelNetworks.node(dest.id())
                .ifPresent(n -> com.nirncraft.travel.TravelNetworks.onArrival(player, n, com.nirncraft.travel.TravelMode.CARRIAGE));
    }

    /**
     * Where a carriage stops at a destination: a safe standing spot just outside the place, on the road that leads in
     * when the road network has one, otherwise on the side facing the nearest other settlement.
     */
    public static BlockPos stopFor(ServerLevel level, Place place) {
        // the settlement's own carriage stop or stables, once they have been generated
        java.util.Optional<BlockPos> stop = com.nirncraft.tamriel.settlement.SettlementAnchors.stables(level, place.id());
        if (stop.isPresent()) {
            BlockPos safe = FastTravelHandler.findArrival(level, stop.get(), LocationKind.TOWN);
            return safe != null ? safe : stop.get();
        }
        BlockPos target = roadPointOutside(level, place);
        if (target == null) target = sideTowardsNeighbour(place);
        int y = level.getChunk(target.getX() >> 4, target.getZ() >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX() & 15, target.getZ() & 15) + 1;
        BlockPos anchor = new BlockPos(target.getX(), y, target.getZ());
        BlockPos safe = FastTravelHandler.findArrival(level, anchor, LocationKind.TOWN);
        return safe != null ? safe : anchor;
    }

    /** A paved road point just outside the place's radius (the nearest to radius + 12), or null. */
    @org.jetbrains.annotations.Nullable
    private static BlockPos roadPointOutside(ServerLevel level, Place place) {
        RoadsData data = RoadsData.get(level);
        int inner = place.radius() + 4;
        int outer = place.radius() + 96;
        int want = place.radius() + 12;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int c0x = (place.x() - outer) >> 4, c1x = (place.x() + outer) >> 4;
        int c0z = (place.z() - outer) >> 4, c1z = (place.z() + outer) >> 4;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                LongArrayList entries = data.chunkIndex.get(ChunkPos.pack(cx, cz));
                if (entries == null) continue;
                for (int k = 0; k < entries.size(); k++) {
                    long e = entries.getLong(k);
                    Road road = data.road(RoadsData.entryRoad(e));
                    if (road == null) continue;
                    int idx = RoadsData.entryIndex(e);
                    if (idx < 0 || idx >= road.size() || road.longWater(idx)) continue;
                    double d = Math.sqrt(place.distSqr(road.xs[idx], road.zs[idx]));
                    if (d < inner || d > outer) continue;
                    double score = Math.abs(d - want);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(road.xs[idx], 64, road.zs[idx]);
                    }
                }
            }
        }
        return best;
    }

    /** Just outside the place, on the side facing the nearest other settlement (where its road most likely runs). */
    private static BlockPos sideTowardsNeighbour(Place place) {
        Place other = null;
        long best = Long.MAX_VALUE;
        for (Place p : Places.all()) {
            if (p == place || !p.type().isSettlement()) continue;
            long d = p.distSqr(place.x(), place.z());
            if (d > (long) (place.radius() + 50) * (place.radius() + 50) && d < best) {
                best = d;
                other = p;
            }
        }
        double angle = other == null ? Math.PI / 2 : Math.atan2(other.z() - place.z(), other.x() - place.x());
        int r = place.radius() + 12;
        return new BlockPos(place.x() + (int) Math.round(Math.cos(angle) * r), 64, place.z() + (int) Math.round(Math.sin(angle) * r));
    }
}
