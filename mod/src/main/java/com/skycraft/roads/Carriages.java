package com.skycraft.roads;

import com.skycraft.core.Currency;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.vitals.Vitals;
import com.skycraft.world.FastTravelHandler;
import com.skycraft.world.LocationKind;
import com.skycraft.world.RestManager;
import com.skycraft.world.WorldData;
import com.skycraft.world.WorldPackets;
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
    /** At most this many destinations in the dialogue (nearest first). */
    private static final int MAX_DESTINATIONS = 14;
    /** A destination farther than this over open water needs a ship. */
    private static final int MAX_WATER_RUN = 384;

    private Carriages() {}

    /** A destination offered by a driver. */
    public record Destination(Place place, int fare, double distance) {
    }

    public static void register() {
        Dialogue.registerProvider(Carriages::addOptions);
    }

    public static boolean isCarriageDriver(LivingEntity npc) {
        if (npc == null || !npc.isAlive()) return false;
        if (npc.getPersistentData().getBoolean("skycraft_carriage_driver")) return true;
        if (npc.getPersistentData().getBoolean("skycraft_stable_master")) return false;
        String desc = npc.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (desc.contains("carriage") || desc.contains("driver")) return true;
        if (npc.hasCustomName()) {
            String name = npc.getCustomName().getString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("carriage") || name.contains("driver")) return true;
        }
        return false;
    }

    /** The destinations a driver at {@code from} offers this player, nearest first. */
    public static List<Destination> destinations(ServerPlayer player, BlockPos from) {
        List<Destination> out = new ArrayList<>();
        if (player.level().dimension() != Level.OVERWORLD) return out;
        String province = Holds.province(Holds.holdAt(player.level(), from));
        int baseFare = RoadsConfig.CARRIAGE_COST.get();
        var data = SkyData.get(player);
        for (Place p : Places.all()) {
            if (p.type() != PlaceType.CAPITAL && p.type() != PlaceType.CITY) continue;
            double dist = Math.sqrt(p.distSqr(from.getX(), from.getZ()));
            if (dist < p.radius() + 200) continue; // the town you are at
            String destProvince = provinceOf(p);
            boolean sameProvince = destProvince.equals(province);
            if (!sameProvince && !WorldData.discoveredPlace(data, p.id())) continue;
            if (crossesSea(from.getX(), from.getZ(), p.x(), p.z())) continue;
            out.add(new Destination(p, fare(baseFare, dist, sameProvince && "skyrim".equals(province)), dist));
        }
        out.sort(Comparator.comparingDouble(Destination::distance));
        return out.size() > MAX_DESTINATIONS ? out.subList(0, MAX_DESTINATIONS) : out;
    }

    /** Skyrim: 20 Septims for a neighbouring hold up to 50 across the province; elsewhere it grows with the road. */
    static int fare(int baseFare, double dist, boolean withinSkyrim) {
        if (withinSkyrim) return Mth.clamp((int) Math.round(baseFare + (dist - 1200) / 100.0), baseFare, (int) Math.round(baseFare * 2.5));
        return Math.max(baseFare, (int) Math.round(baseFare * (1 + dist / 1500.0)));
    }

    private static String provinceOf(Place p) {
        if (!p.regionId().isEmpty()) {
            return Regions.byId(p.regionId()).map(r -> r.province())
                    .orElse(p.regionId().contains("/") ? p.regionId().substring(0, p.regionId().indexOf('/')) : p.regionId());
        }
        return TamrielMap.region(p.x(), p.z()).province();
    }

    /** True if the straight road between two points runs over more than {@link #MAX_WATER_RUN} blocks of sea. */
    static boolean crossesSea(int x0, int z0, int x1, int z1) {
        double len = Math.sqrt((double) (x1 - x0) * (x1 - x0) + (double) (z1 - z0) * (z1 - z0));
        int steps = Math.max(1, (int) (len / 48));
        int run = 0;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(x0 + (x1 - x0) * t);
            int z = (int) Math.round(z0 + (z1 - z0) * t);
            if (TamrielMap.elevation(x, z) < TamrielMap.SEA_LEVEL) {
                run += 48;
                if (run > MAX_WATER_RUN) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }

    private static void addOptions(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!RoadsConfig.CARRIAGES.get()) return;
        if (!isCarriageDriver(npc)) return;
        int order = 170;
        for (Destination dest : destinations(player, npc.blockPosition())) {
            out.add(new DialogueOption(
                    "roads.carriage.dest." + dest.place().id(),
                    Component.translatable("dialogue.skycraft.carriage.destination", dest.place().name(), dest.fare()),
                    order++,
                    (p, n) -> travelToDestination(p, n, dest.place(), dest.fare())
            ));
        }
    }

    private static void travelToDestination(ServerPlayer player, LivingEntity npc, Place dest, int fare) {
        if (Vitals.inCombat(player)) {
            Dialogue.open(player, npc, Component.translatable("world.skycraft.travel.combat"));
            return;
        }
        if (com.skycraft.inventory.CarryWeight.isOverencumbered(player)) {
            Dialogue.open(player, npc, Component.translatable("world.skycraft.travel.encumbered"));
            return;
        }
        if (player.distanceToSqr(npc) > 8 * 8) return;

        if (!Currency.take(player, fare)) {
            Dialogue.open(player, npc, Component.translatable("dialogue.skycraft.carriage.no_gold", fare));
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockPos targetPos = stopFor(level, dest);

        // Find mounted horse if riding, or nearby followers
        Entity vehicle = player.getVehicle();
        List<Mob> followers = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, player));

        double distance = Math.sqrt(player.blockPosition().distSqr(targetPos));

        player.stopRiding();
        player.teleportTo(level, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.resetFallDistance();

        if (vehicle != null && vehicle.isAlive()) {
            vehicle.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);
            vehicle.resetFallDistance();
            player.startRiding(vehicle, true);
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
        int perHour = com.skycraft.world.WorldConfig.BLOCKS_PER_TRAVEL_HOUR.get();
        long online = player.server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
        if (perHour > 0 && online <= 1) {
            hours = Mth.clamp((int) Math.round(distance / perHour), 1, 72);
            RestManager.advanceTime(player.server, hours * (long) RestManager.TICKS_PER_HOUR);
        }

        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(hours, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, hours > 0
                ? Component.translatable("dialogue.skycraft.carriage.arrived_hours", dest.name(), hours)
                : Component.translatable("dialogue.skycraft.carriage.arrived", dest.name()));
    }

    /**
     * Where a carriage stops at a destination: a safe standing spot just outside the place, on the road that leads in
     * when the road network has one, otherwise on the side facing the nearest other settlement.
     */
    public static BlockPos stopFor(ServerLevel level, Place place) {
        // TODO(S1): settlements build stables and a carriage stop beside capitals and cities; once their anchors are
        // published (e.g. a "stables" anchor per place), return that anchor's safe spot here first.
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
                LongArrayList entries = data.chunkIndex.get(ChunkPos.asLong(cx, cz));
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
