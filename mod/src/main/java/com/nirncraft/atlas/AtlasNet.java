package com.nirncraft.atlas;

import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * atlas's play packets, through SkyNetwork (add {@code com.nirncraft.atlas.AtlasNet.register()} to the module
 * list in SkyNetwork.init). Every decoder bounds what it reads; the server validates every request against the
 * player's position (AtlasTileServer).
 */
public final class AtlasNet {
    private AtlasNet() {}

    static final int MAX_REQUEST = 32, MAX_ACTORS = 96, MAX_SITES = 4096, MAX_EDITS = 8192;

    /**
     * S->C on login and on entering the overworld: this server streams exact far tiles. worldId names the world for
     * the client's tile cache (a salted hash of the seed and codec version; the seed itself never leaves the server).
     */
    public record Hello(long worldId, int codec) {}

    /** C->S: tiles the client wants refined, nearest first (lods[i], txs[i], tzs[i]). */
    public record TileRequest(byte[] lods, int[] txs, int[] tzs) {}

    /** S->C: one tile's geometry (AtlasTileCodec); empty data = the server will never serve this tile. */
    public record TileData(int lod, int tx, int tz, byte[] data) {}

    /**
     * S->C, twice a second: actors beyond the client's tracking range (players, large creatures, dragons, ships), as
     * atlas impostors. Per actor: xyz (3 doubles), velocity in blocks/s (3), yaw/height/radius (3), kind/tint (2),
     * entity id/flags (2: the id the client knows the entity by once it tracks it, so the impostor cross-fades into the
     * real one instead of doubling it; flags bit 0 = on the ground).
     */
    public record FarActors(int count, double[] xyz, float[] vel, float[] yawHeightRadius, int[] kindTint, int[] idFlags) {
        public static final int ON_GROUND = 1;
    }

    /**
     * S->C when a player has moved far enough: the seeded natural landmarks around it (AtlasNatural: the world seed never
     * leaves the server), nearest first. Per site: x, z (xz), kind id, its seed (size and details), facing (2D value).
     */
    public record FarSites(int count, int[] xz, String[] kinds, long[] seeds, byte[] facing) {}

    /**
     * S->C with FarSites: the facing (2D value) of the named wild sites around the player whose facing the seed decides
     * (DungeonStructure.siteFacing via AtlasSites.facings), by place id.
     */
    public record FarFacings(String[] ids, byte[] facing) {}

    /**
     * S->C: changes to one 256-block tile the far field's prediction cannot know (docs/ATLAS-DELTAS.md), newer than what
     * this player has. cols: per column (AtlasEdits.COL ints) x, z, top (y of the ground's top block), top block id,
     * under block id, water top (MIN_VALUE: dry), span top (exclusive; MIN_VALUE: none), span base (MIN_VALUE: from the
     * ground), span wall block id (trunk), span roof block id (crown); lights: per light x, y, z, emission (0: gone).
     */
    public record FarEdits(int tx, int tz, int[] cols, int[] lights) {}

    /** S->C: real chunks' surface sections for the far field's finest levels (AtlasSections blobs, one per chunk). */
    public record FarSections(byte[][] blobs) {}

    // client side handlers, set by AtlasClient (so a dedicated server never loads client classes)
    static volatile Consumer<Hello> onHello = m -> {};
    static volatile Consumer<TileData> onTile = m -> {};
    static volatile Consumer<FarActors> onFarActors = m -> {};
    static volatile Consumer<FarSites> onFarSites = m -> {};
    static volatile Consumer<FarFacings> onFarFacings = m -> {};
    static volatile Consumer<FarEdits> onFarEdits = m -> {};
    static volatile Consumer<FarSections> onFarSections = m -> {};

    public static void register() {
        SkyNetwork.register(Hello.class, PacketFlow.CLIENTBOUND, (m, b) -> {
            b.writeLong(m.worldId);
            b.writeVarInt(m.codec);
        }, b -> new Hello(b.readLong(), b.readVarInt()), (m, ctx) -> onHello.accept(m));

        SkyNetwork.register(TileRequest.class, PacketFlow.SERVERBOUND, AtlasNet::writeRequest, AtlasNet::readRequest,
                (m, ctx) -> {
                    ServerPlayer p = SkyNetwork.sender(ctx);
                    if (p != null) AtlasGuard.run("atlas tile service", () -> AtlasTileServer.request(p, m));
                });

        SkyNetwork.register(TileData.class, PacketFlow.CLIENTBOUND, (m, b) -> {
            b.writeByte(m.lod);
            b.writeInt(m.tx);
            b.writeInt(m.tz);
            b.writeByteArray(m.data);
        }, b -> new TileData(b.readUnsignedByte(), b.readInt(), b.readInt(), b.readByteArray(AtlasTileCodec.MAX_BYTES)),
                (m, ctx) -> onTile.accept(m));

        SkyNetwork.register(FarActors.class, PacketFlow.CLIENTBOUND, AtlasNet::writeActors, AtlasNet::readActors,
                (m, ctx) -> onFarActors.accept(m));

        SkyNetwork.register(FarSites.class, PacketFlow.CLIENTBOUND, AtlasNet::writeSites, AtlasNet::readSites,
                (m, ctx) -> onFarSites.accept(m));

        SkyNetwork.register(FarFacings.class, PacketFlow.CLIENTBOUND, (m, b) -> {
            b.writeVarInt(m.ids.length);
            for (int i = 0; i < m.ids.length; i++) {
                b.writeUtf(m.ids[i], 128);
                b.writeByte(m.facing[i]);
            }
        }, b -> {
            int n = b.readVarInt();
            if (n < 0 || n > MAX_SITES) throw new IllegalArgumentException("atlas far facings: " + n);
            String[] ids = new String[n];
            byte[] facing = new byte[n];
            for (int i = 0; i < n; i++) {
                ids[i] = b.readUtf(128);
                facing[i] = (byte) (b.readByte() & 3);
            }
            return new FarFacings(ids, facing);
        }, (m, ctx) -> onFarFacings.accept(m));

        SkyNetwork.register(FarEdits.class, PacketFlow.CLIENTBOUND, (m, b) -> {
            b.writeInt(m.tx);
            b.writeInt(m.tz);
            b.writeVarInt(m.cols.length / AtlasEdits.COL);
            for (int v : m.cols) b.writeInt(v);
            b.writeVarInt(m.lights.length / 4);
            for (int v : m.lights) b.writeInt(v);
        }, b -> {
            int tx = b.readInt(), tz = b.readInt();
            int n = b.readVarInt();
            if (n < 0 || n > MAX_EDITS) throw new IllegalArgumentException("atlas far edits of " + n + " columns");
            int[] cols = new int[n * AtlasEdits.COL];
            for (int i = 0; i < cols.length; i++) cols[i] = b.readInt();
            int nl = b.readVarInt();
            if (nl < 0 || nl > MAX_EDITS) throw new IllegalArgumentException("atlas far edits of " + nl + " lights");
            int[] lights = new int[nl * 4];
            for (int i = 0; i < lights.length; i++) lights[i] = b.readInt();
            return new FarEdits(tx, tz, cols, lights);
        }, (m, ctx) -> onFarEdits.accept(m));

        SkyNetwork.register(FarSections.class, PacketFlow.CLIENTBOUND, (m, b) -> {
            b.writeVarInt(m.blobs.length);
            for (byte[] x : m.blobs) b.writeByteArray(x);
        }, b -> {
            int n = b.readVarInt();
            if (n < 0 || n > 256) throw new IllegalArgumentException("atlas far sections of " + n + " chunks");
            byte[][] blobs = new byte[n][];
            for (int i = 0; i < n; i++) blobs[i] = b.readByteArray(1 << 20);
            return new FarSections(blobs);
        }, (m, ctx) -> onFarSections.accept(m));
    }

    private static void writeRequest(TileRequest m, RegistryFriendlyByteBuf b) {
        b.writeVarInt(m.lods.length);
        for (int i = 0; i < m.lods.length; i++) {
            b.writeByte(m.lods[i]);
            b.writeInt(m.txs[i]);
            b.writeInt(m.tzs[i]);
        }
    }

    private static TileRequest readRequest(RegistryFriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_REQUEST) throw new IllegalArgumentException("atlas tile request of " + n + " tiles");
        byte[] lods = new byte[n];
        int[] txs = new int[n], tzs = new int[n];
        for (int i = 0; i < n; i++) {
            lods[i] = b.readByte();
            txs[i] = b.readInt();
            tzs[i] = b.readInt();
        }
        return new TileRequest(lods, txs, tzs);
    }

    private static void writeSites(FarSites m, RegistryFriendlyByteBuf b) {
        b.writeVarInt(m.count);
        for (int i = 0; i < m.count; i++) {
            b.writeInt(m.xz[i * 2]);
            b.writeInt(m.xz[i * 2 + 1]);
            b.writeUtf(m.kinds[i], 64);
            b.writeLong(m.seeds[i]);
            b.writeByte(m.facing[i]);
        }
    }

    private static FarSites readSites(RegistryFriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_SITES) throw new IllegalArgumentException("atlas far sites: " + n);
        int[] xz = new int[n * 2];
        String[] kinds = new String[n];
        long[] seeds = new long[n];
        byte[] facing = new byte[n];
        for (int i = 0; i < n; i++) {
            xz[i * 2] = b.readInt();
            xz[i * 2 + 1] = b.readInt();
            kinds[i] = b.readUtf(64);
            seeds[i] = b.readLong();
            facing[i] = b.readByte();
        }
        return new FarSites(n, xz, kinds, seeds, facing);
    }

    private static void writeActors(FarActors m, RegistryFriendlyByteBuf b) {
        b.writeVarInt(m.count);
        for (int i = 0; i < m.count; i++) {
            for (int k = 0; k < 3; k++) b.writeDouble(m.xyz[i * 3 + k]);
            for (int k = 0; k < 3; k++) b.writeFloat(m.vel[i * 3 + k]);
            for (int k = 0; k < 3; k++) b.writeFloat(m.yawHeightRadius[i * 3 + k]);
            b.writeInt(m.kindTint[i * 2]);
            b.writeInt(m.kindTint[i * 2 + 1]);
            b.writeVarInt(m.idFlags[i * 2]);
            b.writeByte(m.idFlags[i * 2 + 1]);
        }
    }

    private static FarActors readActors(RegistryFriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_ACTORS) throw new IllegalArgumentException("atlas far actors: " + n);
        double[] xyz = new double[n * 3];
        float[] vel = new float[n * 3], yhr = new float[n * 3];
        int[] kt = new int[n * 2], idf = new int[n * 2];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) xyz[i * 3 + k] = b.readDouble();
            for (int k = 0; k < 3; k++) vel[i * 3 + k] = b.readFloat();
            for (int k = 0; k < 3; k++) yhr[i * 3 + k] = b.readFloat();
            kt[i * 2] = b.readInt();
            kt[i * 2 + 1] = b.readInt();
            idf[i * 2] = b.readVarInt();
            idf[i * 2 + 1] = b.readUnsignedByte();
        }
        return new FarActors(n, xyz, vel, yhr, kt, idf);
    }
}
