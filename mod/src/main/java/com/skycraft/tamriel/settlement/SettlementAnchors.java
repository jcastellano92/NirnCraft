package com.skycraft.tamriel.settlement;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the people of each Skycraft settlement belong: throne, counters, forges, altars, beds, guard posts, stables,
 * owned containers and the house for sale. Recorded on the server thread from the settlement's structure pieces the
 * first time each of its starts is loaded ({@link SettlementEvents}), keyed by place id (named places) or
 * {@code gen:<chunkX>,<chunkZ>} (generic settlements). Stored with the overworld.
 *
 * <p>Other modules read it through the static accessors: {@link #stables} (carriages), {@link #find}/{@link #near}
 * (NPC population), {@link #houseSite} (houses for sale).</p>
 */
public final class SettlementAnchors extends SavedData {
    public static final String NAME = "skycraft_settlement_anchors";

    /** One settlement's anchors and bookkeeping. */
    public static final class Entry {
        public final String key;
        public final int x, z, radius;
        final List<Anchor> anchors = new ArrayList<>();
        /** Start chunks whose pieces were recorded (a big city has several). */
        final LongOpenHashSet starts = new LongOpenHashSet();
        /** Indices of anchors that already got their person. */
        final IntOpenHashSet spawned = new IntOpenHashSet();

        Entry(String key, int x, int z, int radius) {
            this.key = key;
            this.x = x;
            this.z = z;
            this.radius = radius;
        }

        public List<Anchor> anchors() {
            return java.util.Collections.unmodifiableList(anchors);
        }

        public List<Anchor> anchors(AnchorType type) {
            List<Anchor> out = new ArrayList<>();
            for (Anchor a : anchors) if (a.type() == type) out.add(a);
            return out;
        }

        @Nullable
        public Anchor first(AnchorType type) {
            for (Anchor a : anchors) if (a.type() == type) return a;
            return null;
        }

        /** The named place this settlement is, if any. */
        public Optional<Place> place() {
            return key.startsWith("gen:") ? Optional.empty() : Places.byId(key);
        }

        /** A homestead plot: open land for the player to build on, not a town. */
        public boolean homestead() {
            return place().map(p -> p.variant().equals("homestead")).orElse(false);
        }

        public boolean contains(int bx, int bz, int margin) {
            long dx = bx - x, dz = bz - z;
            long r = radius + margin;
            return dx * dx + dz * dz <= r * r;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public static SettlementAnchors get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(SettlementAnchors::load, SettlementAnchors::new, NAME);
    }

    // ------------------------------------------------------------------ public API

    public Collection<Entry> entries() {
        return entries.values();
    }

    @Nullable
    public Entry entry(String key) {
        return entries.get(key);
    }

    /** The settlement recorded for a place id. */
    public static Optional<Entry> find(ServerLevel level, String key) {
        return Optional.ofNullable(get(level).entries.get(key));
    }

    /** The settlement whose area (plus {@code margin}) contains the position, if any. */
    public static Optional<Entry> near(ServerLevel level, BlockPos pos, int margin) {
        if (level.dimension() != Level.OVERWORLD) return Optional.empty();
        Entry best = null;
        long bestD = Long.MAX_VALUE;
        for (Entry e : get(level).entries.values()) {
            if (!e.contains(pos.getX(), pos.getZ(), margin)) continue;
            long dx = pos.getX() - e.x, dz = pos.getZ() - e.z;
            long d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return Optional.ofNullable(best);
    }

    /** True if the position lies inside a recorded Skycraft settlement. */
    public static boolean inSettlement(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) return false;
        for (Entry e : get(level).entries.values()) {
            if (e.contains(pos.getX(), pos.getZ(), 0) && !e.homestead()) return true;
        }
        return false;
    }

    /**
     * Where carriages wait for a settlement: the stables anchor of the place itself, else of its stables place
     * ({@code <id>_stables}, Katla's Farm for Solitude), else of the nearest recorded stables within 400 blocks.
     * Empty until the stables have been generated.
     */
    public static Optional<BlockPos> stables(ServerLevel level, String placeId) {
        SettlementAnchors data = get(level);
        Entry own = data.entries.get(placeId);
        Optional<BlockPos> carriage = stop(own);
        if (carriage.isPresent()) return carriage;
        String alias = placeId.equals("solitude") ? "katlas_farm" : placeId + "_stables";
        carriage = stop(data.entries.get(alias));
        if (carriage.isPresent()) return carriage;
        Optional<Place> place = Places.byId(placeId);
        if (place.isEmpty()) return Optional.empty();
        Entry best = null;
        long bestD = 400L * 400L;
        for (Entry e : data.entries.values()) {
            if (e.first(AnchorType.CARRIAGE) == null && e.first(AnchorType.STABLES) == null) continue;
            long d = place.get().distSqr(e.x, e.z);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return stop(best);
    }

    private static Optional<BlockPos> stop(@Nullable Entry e) {
        if (e == null) return Optional.empty();
        Anchor a = e.first(AnchorType.CARRIAGE);
        if (a == null) a = e.first(AnchorType.STABLES);
        return a == null ? Optional.empty() : Optional.of(a.pos());
    }

    /** A house for sale as built: its bounds, front door and the owner's bed. */
    public record HouseSite(String houseId, String settlement, BlockPos min, BlockPos max, @Nullable BlockPos door, @Nullable BlockPos bed) {
        public boolean contains(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }

        public BlockPos center() {
            return new BlockPos((min.getX() + max.getX()) / 2, bed != null ? bed.getY() : min.getY() + 1, (min.getZ() + max.getZ()) / 2);
        }
    }

    public static Optional<HouseSite> houseSite(ServerLevel level, String houseId) {
        for (Entry e : get(level).entries.values()) {
            BlockPos a = null, b = null, door = null, bed = null;
            for (Anchor an : e.anchors) {
                if (!an.owner().equals(houseId)) continue;
                switch (an.type()) {
                    case HOUSE_MIN -> a = an.pos();
                    case HOUSE_MAX -> b = an.pos();
                    case HOUSE_DOOR -> door = an.pos();
                    case HOUSE_BED -> {
                        if (bed == null) bed = an.pos();
                    }
                    default -> {
                    }
                }
            }
            if (a != null && b != null) {
                BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
                BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
                return Optional.of(new HouseSite(houseId, e.key, min, max, door, bed));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ recording

    Entry entryFor(String key, int x, int z, int radius) {
        return entries.computeIfAbsent(key, k -> new Entry(k, x, z, radius));
    }

    boolean recorded(String key, long start) {
        Entry e = entries.get(key);
        return e != null && e.starts.contains(start);
    }

    // ------------------------------------------------------------------ persistence

    public static SettlementAnchors load(CompoundTag tag) {
        SettlementAnchors d = new SettlementAnchors();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Entry e = new Entry(t.getString("key"), t.getInt("x"), t.getInt("z"), t.getInt("r"));
            for (long s : t.getLongArray("starts")) e.starts.add(s);
            for (int s : t.getIntArray("spawned")) e.spawned.add(s);
            ListTag owners = t.getList("owners", Tag.TAG_STRING);
            byte[] types = t.getByteArray("types");
            long[] pos = t.getLongArray("pos");
            int[] own = t.getIntArray("own");
            for (int k = 0; k < Math.min(types.length, Math.min(pos.length, own.length)); k++) {
                int ti = types[k] & 0xFF;
                if (ti >= AnchorType.VALUES.length) continue;
                String owner = own[k] >= 0 && own[k] < owners.size() ? owners.getString(own[k]) : "";
                e.anchors.add(new Anchor(AnchorType.VALUES[ti], BlockPos.of(pos[k]), owner));
            }
            d.entries.put(e.key, e);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("key", e.key);
            t.putInt("x", e.x);
            t.putInt("z", e.z);
            t.putInt("r", e.radius);
            t.put("starts", new LongArrayTag(e.starts.toLongArray()));
            t.putIntArray("spawned", e.spawned.toIntArray());
            Map<String, Integer> index = new LinkedHashMap<>();
            ListTag owners = new ListTag();
            byte[] types = new byte[e.anchors.size()];
            long[] pos = new long[e.anchors.size()];
            int[] own = new int[e.anchors.size()];
            for (int k = 0; k < e.anchors.size(); k++) {
                Anchor a = e.anchors.get(k);
                types[k] = (byte) a.type().ordinal();
                pos[k] = a.pos().asLong();
                Integer idx = index.get(a.owner());
                if (idx == null) {
                    idx = owners.size();
                    index.put(a.owner(), idx);
                    owners.add(net.minecraft.nbt.StringTag.valueOf(a.owner()));
                }
                own[k] = idx;
            }
            t.put("owners", owners);
            t.putByteArray("types", types);
            t.put("pos", new LongArrayTag(pos));
            t.putIntArray("own", own);
            list.add(t);
        }
        tag.put("entries", list);
        return tag;
    }
}
