package com.nirncraft.fire;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fire sites: server-defined places that are on fire "in the distance". A site is a position with an intensity, a
 * flame height and a smoke level. The server keeps the list (saved with each dimension) and tells players about the
 * sites near them ({@link FireSync}); the client draws them with the far fire renderer
 * ({@link com.nirncraft.fire.client.FarFireRenderer}), flames, glow and a smoke column, out to hundreds of blocks and
 * independent of particle culling, so a burning town is a beacon from the road.
 *
 * <p><b>API for other systems</b> (server side; all of them are safe to call from the server thread):</p>
 * <pre>{@code
 * FireSites.add(level, pos, 1.0f, 8);                  // a persistent fire: intensity 1.0, 8 blocks of flame
 * FireSites.add(level, pos, 0.6f, 5, 2, 20 * 60);      // smoke level 2, burns out after a minute
 * FireSites.remove(level, pos);
 * FireSites.burst(level, new Vec3(x, y, z), 1.5f, 6);  // a one-off blast (dragon fire impact)
 * }</pre>
 * Sites never touch blocks: they are drawn, not simulated. (The big fire blocks of {@link FireBlocks} are the near
 * field; a site can sit on top of them.) Client-only code (a cutscene) uses {@code ClientFire.addTemporary}.
 */
public final class FireSites {
    /** Flag: the site stands on real big fire blocks (the renderer fades its flames out up close, the blocks take over). */
    public static final int FLAG_BLOCKS = 1;
    /** Flag: smoke only (no flame sprites): a smouldering ruin. */
    public static final int FLAG_SMOKE_ONLY = 2;

    /**
     * One fire site.
     *
     * @param intensity 0..2: brightness and size of the flames (1 = a burning house)
     * @param height    blocks of flame above {@code pos}
     * @param smoke     0 none .. 3 heavy: the length and density of the smoke column
     * @param expires   game time at which a temporary site ends, 0 = persistent
     */
    public record Site(int id, int x, int y, int z, float intensity, int height, int smoke, int flags, long expires) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public boolean persistent() {
            return expires == 0L;
        }
    }

    /** The sites of one level. */
    public static final class Data extends SavedData {
        public static final String NAME = "nirncraft_fire_sites";
        final Map<Long, Site> sites = new LinkedHashMap<>();
        int nextId = 1;
        /** Bumped on every change; {@link FireSync} re-sends when a player's copy is older. */
        int version;

        public static Data load(CompoundTag tag) {
            Data d = new Data();
            d.nextId = Math.max(1, tag.getIntOr("next",0));
            ListTag list = tag.getListOrEmpty("sites");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompoundOrEmpty(i);
                Site s = new Site(t.getIntOr("id",0), t.getIntOr("x",0), t.getIntOr("y",0), t.getIntOr("z",0), t.getFloatOr("i",0F), t.getIntOr("h",0),
                        t.getIntOr("s",0), t.getIntOr("f",0), 0L);
                d.sites.put(BlockPos.asLong(s.x, s.y, s.z), s);
            }
            return d;
        }

        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Site s : sites.values()) {
                if (!s.persistent()) continue;
                CompoundTag t = new CompoundTag();
                t.putInt("id", s.id);
                t.putInt("x", s.x);
                t.putInt("y", s.y);
                t.putInt("z", s.z);
                t.putFloat("i", s.intensity);
                t.putInt("h", s.height);
                t.putInt("s", s.smoke);
                t.putInt("f", s.flags);
                list.add(t);
            }
            tag.put("sites", list);
            tag.putInt("next", nextId);
            return tag;
        }

        public int version() {
            return version;
        }
    }

    private static final Map<ServerLevel, Data> CACHE = new WeakHashMap<>();

    private FireSites() {}

    public static Data data(ServerLevel level) {
        return CACHE.computeIfAbsent(level, l -> com.nirncraft.platform.SavedDataTypes.get(l, Data.NAME, Data::load, Data::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag())));
    }

    // ------------------------------------------------------------------ API

    /** Adds (or updates) a persistent fire site at {@code pos}; returns its id. */
    public static int add(ServerLevel level, BlockPos pos, float intensity, int height) {
        return add(level, pos, intensity, height, 2, 0);
    }

    /**
     * Adds (or updates) a fire site. {@code lifeTicks} 0 = persistent (saved), otherwise it burns out after that many
     * ticks (not saved). Returns the site id.
     */
    public static int add(ServerLevel level, BlockPos pos, float intensity, int height, int smoke, int lifeTicks) {
        return add(level, pos, intensity, height, smoke, 0, lifeTicks);
    }

    public static int add(ServerLevel level, BlockPos pos, float intensity, int height, int smoke, int flags, int lifeTicks) {
        Data d = data(level);
        long key = pos.asLong();
        Site old = d.sites.get(key);
        int id = old != null ? old.id : d.nextId++;
        long expires = lifeTicks <= 0 ? 0L : level.getGameTime() + lifeTicks;
        Site s = new Site(id, pos.getX(), pos.getY(), pos.getZ(), Math.max(0.05f, Math.min(2.5f, intensity)),
                Math.max(1, Math.min(48, height)), Math.max(0, Math.min(3, smoke)), flags, expires);
        if (old != null && same(old, s)) return id;
        d.sites.put(key, s);
        d.version++;
        d.setDirty();
        return id;
    }

    private static boolean same(Site a, Site b) {
        return a.intensity == b.intensity && a.height == b.height && a.smoke == b.smoke && a.flags == b.flags && a.expires == b.expires;
    }

    /**
     * Sets a real fire alight: a column of big fire blocks {@code height} tall standing on {@code pos} (the near
     * field: light, animation, heat) plus a persistent site for the far renderer. The blocks never spread and are
     * never extinguished by the world protection. Returns the site id.
     */
    public static int ignite(ServerLevel level, BlockPos pos, float intensity, int height) {
        BigFireBlock.column(level, pos, Math.max(1, Math.min(12, height)));
        return add(level, pos, intensity, height, Math.min(3, 1 + height / 4), FLAG_BLOCKS, 0);
    }

    /** Puts out what {@link #ignite} lit: the site and the big fire blocks in the column above it. */
    public static void douse(ServerLevel level, BlockPos pos) {
        Site s = at(level, pos);
        int h = s == null ? 8 : Math.min(48, s.height() + 2);
        remove(level, pos);
        for (int i = 0; i < h; i++) {
            BlockPos p = pos.above(i);
            if (level.getBlockState(p).getBlock() instanceof BigFireBlock) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** Removes the site at {@code pos}; true if there was one. */
    public static boolean remove(ServerLevel level, BlockPos pos) {
        Data d = data(level);
        Site s = d.sites.remove(pos.asLong());
        if (s == null) return false;
        d.version++;
        d.setDirty();
        return true;
    }

    /** Removes every site within {@code radius} blocks (horizontal) of {@code pos}; returns how many. */
    public static int removeNear(ServerLevel level, BlockPos pos, int radius) {
        Data d = data(level);
        int n = 0;
        long r2 = (long) radius * radius;
        for (Iterator<Site> it = d.sites.values().iterator(); it.hasNext(); ) {
            Site s = it.next();
            long dx = s.x - pos.getX(), dz = s.z - pos.getZ();
            if (dx * dx + dz * dz <= r2) {
                it.remove();
                n++;
            }
        }
        if (n > 0) {
            d.version++;
            d.setDirty();
        }
        return n;
    }

    /** All sites of a level (persistent and temporary). */
    public static List<Site> all(ServerLevel level) {
        return new ArrayList<>(data(level).sites.values());
    }

    /** Sites within {@code radius} blocks (horizontal) of {@code pos}. */
    public static List<Site> near(ServerLevel level, BlockPos pos, int radius) {
        List<Site> out = new ArrayList<>();
        long r2 = (long) radius * radius;
        for (Site s : data(level).sites.values()) {
            long dx = s.x - pos.getX(), dz = s.z - pos.getZ();
            if (dx * dx + dz * dz <= r2) out.add(s);
        }
        return out;
    }

    @Nullable
    public static Site at(ServerLevel level, BlockPos pos) {
        return data(level).sites.get(pos.asLong());
    }

    /**
     * A one-off blast: every player within the sync radius sees a flash of fire and a puff of smoke at {@code pos} that
     * dies away in a few seconds (a dragon's fire hitting the ground). Nothing is saved and no block is touched.
     */
    public static void burst(ServerLevel level, Vec3 pos, float intensity, int radius) {
        double r = FireConfig.SYNC_RADIUS.get();
        FirePackets.Burst msg = new FirePackets.Burst(pos.x, pos.y, pos.z, intensity, Math.max(1, radius));
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos) <= r * r) com.nirncraft.network.SkyNetwork.sendToPlayer(p, msg);
        }
    }

    /** Drops temporary sites that burnt out; returns true if any went. Called by {@link FireSync}. */
    static boolean expire(ServerLevel level) {
        Data d = data(level);
        long now = level.getGameTime();
        boolean any = false;
        for (Iterator<Site> it = d.sites.values().iterator(); it.hasNext(); ) {
            Site s = it.next();
            if (s.expires != 0L && s.expires <= now) {
                it.remove();
                any = true;
            }
        }
        if (any) d.version++;
        return any;
    }
}
