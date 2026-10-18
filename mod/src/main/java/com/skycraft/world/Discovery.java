package com.skycraft.world;

import com.skycraft.Skycraft;
import com.skycraft.core.Holds;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of exploration: named places of Tamriel ("DISCOVERED: Bleak Falls Barrow" when you come within the
 * place's radius), generic structures (named in their region's culture), "CLEARED" when the last hostile inside a
 * discovered location dies, first entry into each region of Nirn, and the first entry into Oblivion/Sovngarde.
 * The "Entering Whiterun Hold" / "CYRODIIL" banners at region and province borders are drawn by the client
 * ({@code world.client.RegionBanner}). Every player discovers individually (multiplayer-first).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Discovery {
    private static final int CHECK_INTERVAL = 40;
    private static final Map<UUID, String> LAST_HOLD = new HashMap<>();

    private Discovery() {}

    /**
     * Contract 6 (docs/PLAYTEST-1.md): marks the nearest location this player hasn't discovered as known (shown
     * with its name on the map and compass, but not discovered). Named places come first; otherwise the nearest
     * generic structure in loaded chunks. Returns its name, or null if there is none.
     */
    @javax.annotation.Nullable
    public static Component revealNear(ServerPlayer player, BlockPos center, int radius) {
        ServerLevel level = player.level();
        PlayerData data = SkyData.get(player);

        if (level.dimension() == Level.OVERWORLD) {
            Place best = null;
            long bestD = (long) radius * radius;
            for (Place p : Places.overlapping(center.getX() - radius, center.getZ() - radius, center.getX() + radius, center.getZ() + radius)) {
                if (p.type().isSettlement() && p.type() != com.skycraft.tamriel.PlaceType.FARM) continue; // rumors are of dungeons and wonders
                if (WorldData.find(data, WorldData.placeLocId(p.id())) != null) continue;
                long d = p.distSqr(center.getX(), center.getZ());
                if (d <= bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best != null) {
                CompoundTag loc = placeEntry(level, best, null);
                loc.putBoolean("known", true);
                WorldData.discovered(data).add(loc);
                data.markDirty();
                return best.name();
            }
        }

        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        int chunkRadius = Math.max(1, radius / 16);
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;

        StructureStart nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        Identifier nearestKey = null;
        LocationKind nearestKind = null;

        for (int dx = -chunkRadius; dx <= chunkRadius; dx += 2) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz += 2) {
                var chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (chunk == null) continue;
                for (var entry : chunk.getAllStarts().entrySet()) {
                    Structure s = entry.getKey();
                    StructureStart start = entry.getValue();
                    if (start == null || !start.isValid()) continue;
                    Identifier key = registry.getKey(s);
                    if (key == null) continue;
                    if (placeOf(level, key, start) != null) continue; // named places are revealed above
                    LocationKind kind = LocationTags.classify(level, s);
                    if (kind == null) continue;
                    String id = level.dimension().identifier() + "|" + key + "|" + start.getChunkPos().x() + "," + start.getChunkPos().z();
                    if (WorldData.find(data, id) != null) continue;

                    BlockPos sCenter = start.getBoundingBox().getCenter();
                    double distSq = sCenter.distSqr(center);
                    if (distSq < nearestDistSq && distSq <= (double) radius * radius) {
                        nearestDistSq = distSq;
                        nearest = start;
                        nearestKey = key;
                        nearestKind = kind;
                    }
                }
            }
        }

        if (nearest != null && nearestKey != null && nearestKind != null) {
            String id = level.dimension().identifier() + "|" + nearestKey + "|" + nearest.getChunkPos().x() + "," + nearest.getChunkPos().z();
            String name = LocationNames.generate(id, nearestKind, nearestKey.getPath());
            BlockPos sCenter = nearest.getBoundingBox().getCenter();
            BoundingBox box = nearest.getBoundingBox();

            CompoundTag loc = new CompoundTag();
            loc.putString("id", id);
            loc.putString("name", name);
            loc.putString("type", nearestKind.id);
            loc.putInt("x", sCenter.getX());
            loc.putInt("y", sCenter.getY());
            loc.putInt("z", sCenter.getZ());
            loc.putString("dim", level.dimension().identifier().toString());
            loc.putInt("ax", sCenter.getX());
            loc.putInt("ay", sCenter.getY());
            loc.putInt("az", sCenter.getZ());
            loc.putIntArray("box", new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
            loc.putBoolean("known", true);

            ListTag list = WorldData.discovered(data);
            list.add(loc);
            data.markDirty();
            return Component.literal(name);
        }
        return null;
    }

    /**
     * Marks a named place as known (rumored: on the map and compass, not yet discovered), e.g. when a quest points
     * at it. Returns false if the player already knows or discovered it.
     */
    public static boolean revealPlace(ServerPlayer player, Place place) {
        PlayerData data = SkyData.get(player);
        if (WorldData.find(data, WorldData.placeLocId(place.id())) != null) return false;
        CompoundTag loc = placeEntry(player.level(), place, null);
        loc.putBoolean("known", true);
        WorldData.discovered(data).add(loc);
        data.markDirty();
        return true;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isSpectator()) return;
        // spread the work of many players over different ticks
        if ((player.tickCount + (player.getId() & 31)) % CHECK_INTERVAL != 0) return;
        try {
            compactOnce(player);
            if (WorldConfig.DISCOVERY.get()) {
                checkPlaces(player);
                checkStructures(player);
            }
            checkRegion(player);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft discovery check failed for {}", player.getName().getString(), e);
        }
    }

    /**
     * Once per player: drops discoveries of structures that are no longer locations ({@code #skycraft:ignored_landmarks}
     * such as decorative trees and benches found before the tag existed) and fields nothing reads any more, so the
     * synced list stays small (version 1); maps entered holds of the old 2048-block grid to region ids (version 2).
     */
    private static void compactOnce(ServerPlayer player) {
        PlayerData data = SkyData.get(player);
        CompoundTag root = WorldData.root(data);
        int version = root.getIntOr("compacted", 0);
        if (version >= 2) return;
        root.putInt("compacted", 2);
        if (version < 1 && WorldConfig.DISCOVERY.get()) {
            Registry<Structure> registry = player.level().registryAccess().lookupOrThrow(Registries.STRUCTURE);
            ListTag list = WorldData.discovered(data);
            for (int i = list.size() - 1; i >= 0; i--) {
                CompoundTag loc = list.getCompoundOrEmpty(i);
                String structure = loc.getStringOr("structure", "");
                loc.remove("structure");
                loc.remove("t");
                Identifier id = Identifier.tryParse(structure);
                if (id == null) continue;
                boolean ignored = registry.get(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE, id))
                        .map(h -> h.is(LocationTags.IGNORED_LANDMARKS)).orElse(false);
                if (ignored) list.remove(i);
            }
        }
        ListTag holds = root.getListOrEmpty("holds");
        ListTag migrated = new ListTag();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < holds.size(); i++) {
            String id = Holds.migrate(holds.getStringOr(i, ""));
            if (seen.add(id)) migrated.add(StringTag.valueOf(id));
        }
        root.put("holds", migrated);
        data.markDirty();
        com.skycraft.core.PlayerDataEvents.fullSync(player);
    }

    // ------------------------------------------------------------------ named places

    private static void checkPlaces(ServerPlayer player) {
        ServerLevel level = player.level();
        if (level.dimension() != Level.OVERWORLD) return;
        int x = player.getBlockX();
        int z = player.getBlockZ();
        PlayerData data = SkyData.get(player);
        for (Place p : Places.overlapping(x, z, x, z)) {
            long r = p.radius();
            if (p.distSqr(x, z) > r * r) continue;
            CompoundTag existing = WorldData.find(data, WorldData.placeLocId(p.id()));
            if (existing != null) {
                if (!existing.getBooleanOr("known", false)) continue;
                existing.remove("known");
                existing.putInt("ax", player.getBlockX());
                existing.putInt("ay", player.getBlockY());
                existing.putInt("az", player.getBlockZ());
                announce(player, data, p);
                continue;
            }
            WorldData.discovered(data).add(placeEntry(level, p, player));
            announce(player, data, p);
        }
    }

    private static void announce(ServerPlayer player, PlayerData data, Place p) {
        data.addStat("locations_discovered", 1);
        data.markDirty();
        Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, p.name(), LocationKind.forPlace(p).displayName());
        SkyNetwork.sendToPlayer(player, new WorldPackets.Cue(WorldPackets.Cue.DISCOVERY));
        // the place's story in a line under the banner (and its lore lectern, the first time anyone comes)
        com.skycraft.lore.place.PlaceLoreEvents.onDiscovered(player, p);
        com.skycraft.explore.ExploreEvents.onDiscovered(player);
    }

    /**
     * A discovered-list entry for a named place. The fast-travel arrival point is where the discoverer stood when
     * they reached the place's edge (usually on the road in), or the anchor for places only heard of.
     */
    static CompoundTag placeEntry(ServerLevel level, Place p, @javax.annotation.Nullable ServerPlayer discoverer) {
        LocationKind kind = LocationKind.forPlace(p);
        int y = level.hasChunk(p.x() >> 4, p.z() >> 4)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x(), p.z())
                : Math.max(level.getSeaLevel() + 1, (int) com.skycraft.tamriel.TamrielMap.elevation(p.x(), p.z()));
        CompoundTag loc = new CompoundTag();
        loc.putString("id", WorldData.placeLocId(p.id()));
        loc.putString("place", p.id());
        loc.putString("name", PlaceText.plain(p));
        loc.putString("type", kind.id);
        loc.putInt("x", p.x());
        loc.putInt("y", y);
        loc.putInt("z", p.z());
        loc.putString("dim", level.dimension().identifier().toString());
        BlockPos at = discoverer != null ? discoverer.blockPosition() : new BlockPos(p.x(), y, p.z());
        loc.putInt("ax", at.getX());
        loc.putInt("ay", at.getY());
        loc.putInt("az", at.getZ());
        return loc;
    }

    /** The named place a {@code skycraft:*} structure start builds (its start chunk holds the place's anchor), if any. */
    @javax.annotation.Nullable
    private static Place placeOf(ServerLevel level, Identifier key, StructureStart start) {
        if (level.dimension() != Level.OVERWORLD || !Skycraft.MODID.equals(key.getNamespace())) return null;
        List<Place> places = Places.inChunk(start.getChunkPos());
        return places.isEmpty() ? null : places.get(0);
    }

    // ------------------------------------------------------------------ structures

    private static void checkStructures(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = player.blockPosition();
        if (!level.isLoaded(pos)) return;
        StructureManager structures = level.structureManager();
        Map<Structure, LongSet> refs = structures.getAllStructuresAt(pos);
        if (refs.isEmpty()) return;
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        PlayerData data = SkyData.get(player);
        for (Structure structure : refs.keySet()) {
            Identifier key = registry.getKey(structure);
            if (key == null) continue;
            LocationKind kind = LocationTags.classify(level, structure);
            if (kind == null) continue;
            StructureStart start = kind.insidePieces
                    ? structures.getStructureWithPieceAt(pos, h -> h.value() == structure)
                    : structures.getStructureAt(pos, structure);
            if (start == null || !start.isValid()) continue;
            Place place = placeOf(level, key, start);
            if (place != null) {
                // a named place's own structure: the place entry gets the real bounds (for CLEARED)
                CompoundTag loc = WorldData.find(data, WorldData.placeLocId(place.id()));
                if (loc == null) {
                    loc = placeEntry(level, place, player);
                    WorldData.discovered(data).add(loc);
                    announce(player, data, place);
                } else if (loc.getBooleanOr("known", false)) {
                    loc.remove("known");
                    announce(player, data, place);
                }
                BoundingBox box = start.getBoundingBox();
                int[] b = {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()};
                if (!java.util.Arrays.equals(loc.getIntArray("box").orElseGet(() -> new int[0]), b)) {
                    loc.putIntArray("box", b);
                    data.markDirty();
                }
                continue;
            }
            String id = level.dimension().identifier() + "|" + key + "|" + start.getChunkPos().x() + "," + start.getChunkPos().z();
            CompoundTag existing = WorldData.find(data, id);
            if (existing != null) {
                if (existing.getBooleanOr("known", false)) {
                    existing.remove("known");
                    data.addStat("locations_discovered", 1);
                    data.markDirty();
                    String name = existing.getStringOr("name", "");
                    Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, Component.literal(name), kind.displayName());
                    SkyNetwork.sendToPlayer(player, new WorldPackets.Cue(WorldPackets.Cue.DISCOVERY));
                    com.skycraft.explore.ExploreEvents.onDiscovered(player);
                }
                continue;
            }
            // the same place under another structure id (overlapping starts): only right on top of a discovered one
            BlockPos center = start.getBoundingBox().getCenter();
            if (WorldData.hasDiscoveredNear(data, level.dimension().identifier().toString(), center.getX(), center.getZ(), 16)) {
                continue;
            }
            discover(player, data, id, kind, key, start.getBoundingBox());
        }
    }

    private static void discover(ServerPlayer player, PlayerData data, String id, LocationKind kind, Identifier key, BoundingBox box) {
        String name = LocationNames.generate(id, kind, key.getPath());
        BlockPos center = box.getCenter();
        CompoundTag loc = new CompoundTag();
        loc.putString("id", id);
        loc.putString("name", name);
        loc.putString("type", kind.id);
        loc.putInt("x", center.getX());
        loc.putInt("y", kind.underground() ? player.getBlockY() : center.getY());
        loc.putInt("z", center.getZ());
        loc.putString("dim", player.level().dimension().identifier().toString());
        loc.putInt("ax", player.getBlockX());
        loc.putInt("ay", player.getBlockY());
        loc.putInt("az", player.getBlockZ());
        loc.putIntArray("box", new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
        ListTag list = WorldData.discovered(data);
        list.add(loc);
        data.addStat("locations_discovered", 1);
        data.markDirty();
        Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, Component.literal(name), kind.displayName());
        SkyNetwork.sendToPlayer(player, new WorldPackets.Cue(WorldPackets.Cue.DISCOVERY));
        com.skycraft.explore.ExploreEvents.onDiscovered(player);
    }

    // ------------------------------------------------------------------ cleared

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !(victim instanceof Enemy)) return;
        ServerLevel level = (ServerLevel) victim.level();
        String dim = level.dimension().identifier().toString();
        // everyone nearby who discovered the place shares the clear (party play)
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(victim) > 64 * 64) continue;
            PlayerData data = SkyData.get(player);
            ListTag list = WorldData.discovered(data);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag loc = list.getCompoundOrEmpty(i);
                if (loc.getBooleanOr("cleared", false) || loc.getBooleanOr("known", false) || !loc.getStringOr("dim", "").equals(dim)) continue;
                LocationKind kind = LocationKind.byId(loc.getStringOr("type", ""));
                if (!kind.clearable || !WorldData.inBox(loc, victim.getX(), victim.getY(), victim.getZ(), 6)) continue;
                if (hostilesRemain(level, loc, victim)) continue;
                loc.putBoolean("cleared", true);
                data.addStat("locations_cleared", 1);
                data.markDirty();
                Notifier.send(player, NotifyKind.LOCATION_CLEARED, WorldData.displayName(loc), kind.displayName());
            }
        }
    }

    private static boolean hostilesRemain(ServerLevel level, CompoundTag loc, LivingEntity victim) {
        int[] b = loc.getIntArray("box").orElseGet(() -> new int[0]);
        AABB box = new AABB(b[0] - 4, b[1] - 4, b[2] - 4, b[3] + 5, b[4] + 5, b[5] + 5);
        AABB near = victim.getBoundingBox().inflate(48);
        AABB search = box.intersect(near);
        return !level.getEntitiesOfClass(Mob.class, search, m -> m != victim && m.isAlive() && m instanceof Enemy).isEmpty();
    }

    // ------------------------------------------------------------------ regions

    /**
     * First entry into a region of Nirn ("DISCOVERED: The Rift / Skyrim"). Crossing into a known region shows the
     * client's "Entering" banner instead. Seas and dimensions are not regions you discover.
     */
    private static void checkRegion(ServerPlayer player) {
        if (player.level().dimension() != Level.OVERWORLD) {
            LAST_HOLD.remove(player.getUUID());
            return;
        }
        Region region = Regions.at(player.level(), player.blockPosition());
        String hold = region.id();
        String last = LAST_HOLD.put(player.getUUID(), hold);
        if (hold.equals(last)) return;
        PlayerData data = SkyData.get(player);
        if (WorldData.hasVisitedHold(data, hold)) return;
        WorldData.addVisitedHold(data, hold);
        if ("sea".equals(region.kind()) || "sea".equals(region.province()) || "dimension".equals(region.kind())) return;
        data.addStat("regions_discovered", 1);
        Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, region.name(), Provinces.name(region.province()));
    }

    // ------------------------------------------------------------------ realms

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LAST_HOLD.remove(player.getUUID());
        PlayerData data = SkyData.get(player);
        CompoundTag root = WorldData.root(data);
        if (event.getTo() == Level.NETHER) {
            realm(player, data, root, "seen_nether", "oblivion");
        } else if (event.getTo() == Level.END) {
            realm(player, data, root, "seen_end", "sovngarde");
        }
    }

    private static void realm(ServerPlayer player, PlayerData data, CompoundTag root, String flag, String name) {
        if (!root.getBooleanOr(flag, false)) {
            root.putBoolean(flag, true);
            data.markDirty();
            Notifier.title(player, Component.translatable("world.skycraft.realm." + name), Component.translatable("world.skycraft.realm." + name + ".sub"));
        } else {
            Notifier.message(player, Component.translatable("world.skycraft.realm.entering", Component.translatable("world.skycraft.realm." + name + ".name")));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_HOLD.remove(event.getEntity().getUUID());
    }
}
