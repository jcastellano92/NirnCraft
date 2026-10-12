package com.skycraft.society;

import com.skycraft.society.entity.NpcEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where townsfolk work, eat and pray. Lookup order, all cheap and never loading chunks:
 * <ol>
 *     <li>anchors registered by settlement builders through {@link #register} (public API: a settlement generator
 *     calls it for its forge, counters, fields, inn, temple, palace...);</li>
 *     <li>vanilla job-site POIs from the POI manager (composter, smithing table, brewing stand, lectern, barrel...);</li>
 *     <li>Skycraft crafting stations among the block entities of the already loaded chunks around the home;</li>
 *     <li>for the inn: the settlement's innkeeper or its meeting bell.</li>
 * </ol>
 * The result is cached on the NPC ({@link NpcEntity#getWorkplace()}) and only looked up again when it is gone.
 */
public final class Workplaces {
    public enum Kind {
        FORGE, COUNTER, FIELD, MINE, LUMBER, INN, TEMPLE, PALACE, LIBRARY, ALCHEMY, DOCK, BUTCHER, TANNERY, MARKET, HOME;

        public final String id = name().toLowerCase(Locale.ROOT);

        @Nullable
        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    private static final int SEARCH = 64;

    private Workplaces() {}

    // ------------------------------------------------------------------ public anchor API

    /** Registers a work/meeting anchor (e.g. a settlement builder's forge or inn counter). Server thread only. */
    public static void register(ServerLevel level, BlockPos pos, Kind kind) {
        Anchors.get(level).add(kind, pos);
    }

    /** {@link #register(ServerLevel, BlockPos, Kind)} by kind id ("forge", "inn", "temple"...); unknown ids are ignored. */
    public static void register(ServerLevel level, BlockPos pos, String kind) {
        Kind k = Kind.byId(kind);
        if (k != null) register(level, pos, k);
    }

    /** Nearest anchor of a kind within {@code radius} of {@code near}, or null. */
    @Nullable
    public static BlockPos anchor(ServerLevel level, BlockPos near, Kind kind, int radius) {
        return Anchors.get(level).nearest(kind, near, radius);
    }

    /** Every anchor of a kind within {@code radius} of {@code near}, nearest first (horizontal distance). */
    public static java.util.List<BlockPos> anchors(ServerLevel level, BlockPos near, Kind kind, int radius) {
        return Anchors.get(level).within(kind, near, radius);
    }

    /** Persistent-data key of the leisure spot cached on a person (a packed block position). */
    public static final String LEISURE_KEY = "skycraft_leisure";
    /** The meeting point the housing plan assigned (packed position; {@code Long.MIN_VALUE}: none, stays indoors). */
    public static final String PLAZA_KEY = "skycraft_plaza";

    /**
     * Where this person spends free hours: a slot around one of the settlement's meeting points (the three nearest are
     * shared out by the person's id, each person gets their own offset on a ring), so nobody idles in the street in
     * front of a house and a plaza fills evenly. Cached on the NPC; null when the settlement has no meeting point.
     */
    @Nullable
    public static BlockPos leisureFor(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        return leisureFor(level, npc, npc.getHome() != null ? npc.getHome() : npc.blockPosition());
    }

    /** {@link #leisureFor(NpcEntity)} for any settler (villager shopkeepers too), cached in its persistent data. */
    @Nullable
    public static BlockPos leisureFor(ServerLevel level, net.minecraft.world.entity.Entity who, BlockPos home) {
        CompoundTag pd = who.getPersistentData();
        if (pd.contains(LEISURE_KEY)) return BlockPos.of(pd.getLong(LEISURE_KEY));
        long id = who.getUUID().getLeastSignificantBits();
        BlockPos centre;
        if (pd.contains(PLAZA_KEY)) { // the housing plan chose this person's plaza (or none: chores indoors)
            long v = pd.getLong(PLAZA_KEY);
            if (v == Long.MIN_VALUE) return null;
            centre = BlockPos.of(v);
        } else {
            java.util.List<BlockPos> spots = anchors(level, home, Kind.MARKET, 72);
            if (spots.isEmpty()) return null;
            centre = spots.get((int) Math.floorMod(id >>> 5, (long) Math.min(3, spots.size())));
        }
        int ring = 2 + (int) Math.floorMod(id >>> 11, 4L);
        int steps = 12;
        int first = (int) Math.floorMod(id >>> 17, (long) steps);
        BlockPos best = centre;
        boolean found = false;
        for (int k = 0; k < steps && !found; k++) {
            double ang = (first + k) * (Math.PI * 2 / steps);
            int x = centre.getX() + (int) Math.round(Math.cos(ang) * ring);
            int z = centre.getZ() + (int) Math.round(Math.sin(ang) * ring);
            if (!level.isLoaded(new BlockPos(x, centre.getY(), z))) continue;
            // the plaza floor: about the bell's level, open above, solid below, dry
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos q = new BlockPos(x, centre.getY() + dy, z);
                if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()
                        && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()
                        && level.getBlockState(q.below()).isFaceSturdy(level, q.below(), net.minecraft.core.Direction.UP)
                        && level.getFluidState(q).isEmpty()) {
                    best = q;
                    found = true;
                    break;
                }
            }
        }
        pd.putLong(LEISURE_KEY, best.asLong());
        return best;
    }

    // ------------------------------------------------------------------ lookups

    /** The kind of place a role works at, or null if it works wherever it is (citizens, beggars...). */
    @Nullable
    public static Kind kindFor(NpcRole role) {
        return switch (role) {
            case FARMER -> Kind.FIELD;
            case BLACKSMITH -> Kind.FORGE;
            case MERCHANT -> Kind.COUNTER;
            case APOTHECARY -> Kind.ALCHEMY;
            case SCHOLAR -> Kind.LIBRARY;
            case FISHER -> Kind.DOCK;
            case BUTCHER -> Kind.BUTCHER;
            case MINER -> Kind.MINE;
            case LUMBERJACK -> Kind.LUMBER;
            case HUNTER -> Kind.TANNERY;
            case PRIEST -> Kind.TEMPLE;
            case JARL, STEWARD -> Kind.PALACE;
            case INNKEEPER, BARD -> Kind.INN;
            default -> null;
        };
    }

    /** The NPC's workplace (cached; looked up when missing). May be null: then they work around their home. */
    @Nullable
    public static BlockPos workplaceOf(NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        if (Marriage.isSpouse(npc)) return npc.getHome();
        BlockPos cached = npc.getWorkplace();
        if (cached != null) return cached;
        Kind kind = kindFor(npc.role());
        if (kind == null) return null;
        BlockPos home = npc.getHome() != null ? npc.getHome() : npc.blockPosition();
        BlockPos found = kind == Kind.INN ? innFor(npc) : find(level, home, kind);
        // nothing better: work around the home (marked so we don't search again every routine step)
        npc.setWorkplace(found != null ? found : home);
        return npc.getWorkplace();
    }

    /** The inn of the NPC's settlement (anchor, innkeeper, meeting bell, else home). Cached on the NPC. */
    @Nullable
    public static BlockPos innFor(NpcEntity npc) {
        if (npc.getInn() != null) return npc.getInn();
        if (!(npc.level() instanceof ServerLevel level)) return null;
        BlockPos home = npc.getHome() != null ? npc.getHome() : npc.blockPosition();
        BlockPos inn = anchor(level, home, Kind.INN, SEARCH * 2);
        if (inn == null) {
            for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(home).inflate(SEARCH, 24, SEARCH),
                    x -> x != npc && x.isAlive() && com.skycraft.survival.inn.Innkeepers.isInnkeeper(x))) {
                inn = e instanceof NpcEntity n && n.getHome() != null && n.role() == NpcRole.INNKEEPER ? n.getHome() : e.blockPosition();
                break;
            }
        }
        if (inn == null && npc.role() == NpcRole.INNKEEPER) inn = home;
        if (inn == null) inn = poi(level, home, h -> h.is(PoiTypes.MEETING));
        // no inn at all: the evening is spent near home (cached too, so nobody searches every second)
        if (inn == null) inn = home;
        npc.setInn(inn);
        return inn;
    }

    @Nullable
    public static BlockPos find(ServerLevel level, BlockPos near, Kind kind) {
        BlockPos anchor = anchor(level, near, kind, SEARCH * 2);
        if (anchor != null) return anchor;
        BlockPos p = switch (kind) {
            case FORGE -> poi(level, near, h -> h.is(PoiTypes.ARMORER) || h.is(PoiTypes.WEAPONSMITH) || h.is(PoiTypes.TOOLSMITH));
            case FIELD -> poi(level, near, h -> h.is(PoiTypes.FARMER));
            case ALCHEMY -> poi(level, near, h -> h.is(PoiTypes.CLERIC));
            case LIBRARY -> poi(level, near, h -> h.is(PoiTypes.LIBRARIAN));
            case DOCK -> poi(level, near, h -> h.is(PoiTypes.FISHERMAN));
            case BUTCHER -> poi(level, near, h -> h.is(PoiTypes.BUTCHER));
            case TANNERY -> poi(level, near, h -> h.is(PoiTypes.LEATHERWORKER));
            case MINE -> poi(level, near, h -> h.is(PoiTypes.MASON));
            case COUNTER -> poi(level, near, h -> h.is(PoiTypes.CARTOGRAPHER) || h.is(PoiTypes.FLETCHER) || h.is(PoiTypes.SHEPHERD));
            case TEMPLE -> poi(level, near, h -> h.is(PoiTypes.CLERIC));
            case MARKET, INN -> poi(level, near, h -> h.is(PoiTypes.MEETING));
            default -> null;
        };
        if (p != null) return p;
        Set<String> words = switch (kind) {
            case FORGE -> Set.of("forge", "anvil", "smelter", "grindstone", "armor_workbench", "workbench");
            case ALCHEMY, TEMPLE -> Set.of("alchemy");
            case LIBRARY -> Set.of("enchanter", "lectern");
            case TANNERY -> Set.of("tanning");
            case INN, BUTCHER -> Set.of("cooking_pot");
            default -> Set.of();
        };
        return words.isEmpty() ? null : station(level, near, words);
    }

    @Nullable
    private static BlockPos poi(ServerLevel level, BlockPos near, Predicate<Holder<PoiType>> type) {
        try {
            Optional<BlockPos> p = level.getPoiManager().findClosest(type, near, SEARCH, PoiManager.Occupancy.ANY);
            return p.map(BlockPos::immutable).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Nearest Skycraft station block entity whose block id contains one of {@code words}; loaded chunks only. */
    @Nullable
    private static BlockPos station(ServerLevel level, BlockPos near, Set<String> words) {
        int cx = near.getX() >> 4;
        int cz = near.getZ() >> 4;
        int r = 3;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(e.getValue().getBlockState().getBlock());
                    if (!"skycraft".equals(id.getNamespace()) && !id.getPath().equals("lectern")) continue;
                    String path = id.getPath();
                    boolean match = false;
                    for (String w : words) {
                        if (path.contains(w)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) continue;
                    double d = e.getKey().distSqr(near);
                    if (d < bestD) {
                        bestD = d;
                        best = e.getKey().immutable();
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ anchor storage

    /** Registered anchors per kind (per dimension saved data {@code skycraft_work_anchors}). */
    static final class Anchors extends SavedData {
        private static final String NAME = "skycraft_work_anchors";
        private static final int MAX_PER_KIND = 8192;
        private final Map<Kind, LongArrayList> byKind = new EnumMap<>(Kind.class);

        static Anchors get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Anchors::load, Anchors::new, NAME);
        }

        void add(Kind kind, BlockPos pos) {
            LongArrayList list = byKind.computeIfAbsent(kind, k -> new LongArrayList());
            long l = pos.asLong();
            if (list.contains(l) || list.size() >= MAX_PER_KIND) return;
            list.add(l);
            setDirty();
        }

        @Nullable
        BlockPos nearest(Kind kind, BlockPos near, int radius) {
            LongArrayList list = byKind.get(kind);
            if (list == null) return null;
            long best = (long) radius * radius;
            long found = Long.MIN_VALUE;
            for (int i = 0; i < list.size(); i++) {
                long l = list.getLong(i);
                long dx = BlockPos.getX(l) - near.getX();
                long dz = BlockPos.getZ(l) - near.getZ();
                long d = dx * dx + dz * dz;
                if (d <= best) {
                    best = d;
                    found = l;
                }
            }
            return found == Long.MIN_VALUE ? null : BlockPos.of(found);
        }

        java.util.List<BlockPos> within(Kind kind, BlockPos near, int radius) {
            LongArrayList list = byKind.get(kind);
            java.util.List<BlockPos> out = new java.util.ArrayList<>();
            if (list == null) return out;
            long max = (long) radius * radius;
            java.util.List<long[]> hits = new java.util.ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                long l = list.getLong(i);
                long dx = BlockPos.getX(l) - near.getX();
                long dz = BlockPos.getZ(l) - near.getZ();
                long d = dx * dx + dz * dz;
                if (d <= max) hits.add(new long[]{d, l});
            }
            hits.sort(java.util.Comparator.comparingLong(h -> h[0]));
            for (long[] h : hits) out.add(BlockPos.of(h[1]));
            return out;
        }

        static Anchors load(CompoundTag tag) {
            Anchors a = new Anchors();
            for (Kind k : Kind.values()) {
                if (tag.contains(k.id)) a.byKind.put(k, new LongArrayList(tag.getLongArray(k.id)));
            }
            return a;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            byKind.forEach((k, list) -> tag.put(k.id, new LongArrayTag(list.toLongArray())));
            return tag;
        }
    }
}
