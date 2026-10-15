package com.skycraft.worldplan.tile;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.worldplan.RoadPlan;
import com.skycraft.worldplan.scene.ScenePlan;
import com.skycraft.worldplan.street.StreetPlan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The seed-dependent 4,096 x 4,096 block tile plan for Endless Lands beyond the authored Tamriel map
 * (pipeline stage P7, see {@code docs/WORLDGEN-PIPELINE.md} section 7).
 *
 * <p>Every tile {@code (tileX, tileZ)} covers world block coordinates
 * {@code [tileX << 12, (tileX + 1) << 12) x [tileZ << 12, (tileZ + 1) << 12)} and bundles:
 * <ul>
 *   <li>a deterministic 11-layer {@link TileSheetGenerator.TileSheet} explaining the tile's geology, climate,
 *       hydrology, flora/fauna, crafts, routes, rule, settlements, threats, atmosphere, and history,</li>
 *   <li>symmetric boundary {@link EdgePoint}s shared with its 4 cardinal neighbour tiles (computed solely from the
 *       shared border coordinate and {@code worldSeed} so adjacent tiles agree without order dependence), plus any
 *       authored {@link RoadPlan} seam points where Tamriel's road network meets the tile,</li>
 *   <li>tile-scale {@link RiverSegment}s from upland basins to coast or shared boundary crossings,</li>
 *   <li>planned {@link TilePlace}s (settlements, camps, delves/ruins, shrines, landmarks) with resolving {@code why}
 *       citations into the tile's 11-layer world sheet,</li>
 *   <li>a full {@link RoadPlan}, {@link StreetPlan}, and {@link ScenePlan} covering the tile using the exact binary
 *       formats and query contracts of stages P1–P6.</li>
 * </ul>
 *
 * <p>Immutable and thread-safe once constructed.</p>
 */
public final class TilePlan {
    public static final int MAGIC = 0x54504C4E; // "TPLN"
    public static final int VERSION = 1;
    public static final int TILE_SHIFT = 12;
    public static final int TILE_SIZE = 1 << TILE_SHIFT; // 4096 blocks

    /** Cardinal boundary of a 4,096 x 4,096 block tile. */
    public enum Edge {
        NORTH, SOUTH, WEST, EAST;

        public Edge opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }
    }

    /**
     * A road or river crossing point on a shared tile boundary.
     * Adjacent tiles compute the exact same {@link EdgePoint}s on their shared border.
     */
    public record EdgePoint(int x, int z, float height, byte kind, boolean river) {
    }

    /**
     * A planned settlement, dungeon, camp, shrine, or landmark inside a procedural tile.
     */
    public record TilePlace(
            String id,
            String name,
            PlaceType type,
            int x,
            int z,
            int radius,
            float padHeight,
            List<String> why
    ) {
    }

    /**
     * A tile-scale drainage channel segment flowing from upland basin to coast or tile edge.
     */
    public record RiverSegment(
            int x0,
            int z0,
            float h0,
            int x1,
            int z1,
            float h1,
            float halfWidth
    ) {
    }

    private final long worldSeed;
    private final int tileX;
    private final int tileZ;
    private final TileSheetGenerator.TileSheet sheet;
    private final Map<Edge, List<EdgePoint>> edgePoints;
    private final List<EdgePoint> authoredSeamPoints;
    private final List<TilePlace> places;
    private final List<RiverSegment> rivers;
    private final RoadPlan roadPlan;
    private final StreetPlan streetPlan;
    private final ScenePlan scenePlan;

    public TilePlan(
            long worldSeed,
            int tileX,
            int tileZ,
            TileSheetGenerator.TileSheet sheet,
            Map<Edge, List<EdgePoint>> edgePoints,
            List<EdgePoint> authoredSeamPoints,
            List<TilePlace> places,
            List<RiverSegment> rivers,
            RoadPlan roadPlan,
            StreetPlan streetPlan,
            ScenePlan scenePlan
    ) {
        this.worldSeed = worldSeed;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.sheet = sheet;
        EnumMap<Edge, List<EdgePoint>> ep = new EnumMap<>(Edge.class);
        for (Edge e : Edge.values()) {
            ep.put(e, List.copyOf(edgePoints.getOrDefault(e, List.of())));
        }
        this.edgePoints = Collections.unmodifiableMap(ep);
        this.authoredSeamPoints = List.copyOf(authoredSeamPoints);
        this.places = List.copyOf(places);
        this.rivers = List.copyOf(rivers);
        this.roadPlan = roadPlan;
        this.streetPlan = streetPlan;
        this.scenePlan = scenePlan;
    }

    public long worldSeed() {
        return worldSeed;
    }

    public int tileX() {
        return tileX;
    }

    public int tileZ() {
        return tileZ;
    }

    public int minX() {
        return tileX << TILE_SHIFT;
    }

    public int minZ() {
        return tileZ << TILE_SHIFT;
    }

    public int maxX() {
        return (tileX + 1) << TILE_SHIFT;
    }

    public int maxZ() {
        return (tileZ + 1) << TILE_SHIFT;
    }

    public TileSheetGenerator.TileSheet sheet() {
        return sheet;
    }

    public List<EdgePoint> edgePoints(Edge edge) {
        return edgePoints.getOrDefault(edge, List.of());
    }

    public Map<Edge, List<EdgePoint>> allEdgePoints() {
        return edgePoints;
    }

    public List<EdgePoint> authoredSeamPoints() {
        return authoredSeamPoints;
    }

    public List<TilePlace> places() {
        return places;
    }

    public List<RiverSegment> rivers() {
        return rivers;
    }

    public RoadPlan roadPlan() {
        return roadPlan;
    }

    public StreetPlan streetPlan() {
        return streetPlan;
    }

    public ScenePlan scenePlan() {
        return scenePlan;
    }

    /** Packs {@code (tileX, tileZ)} into a single {@code long} map key. */
    public static long key(int tileX, int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xffffffffL);
    }

    public static int tileCoord(int blockCoord) {
        return blockCoord >> TILE_SHIFT;
    }

    /**
     * Samples tile-scale river distance, half-width, and water surface elevation at {@code (x, z)}.
     * Populates {@code out.riverDistance}, {@code out.riverHalfWidth}, and {@code out.waterLevel} when closer than
     * any existing channel on {@code out}.
     */
    public void sampleHydrology(int x, int z, MapSample out) {
        if (rivers.isEmpty()) return;
        float bestDist = out.riverDistance;
        float bestHw = out.riverHalfWidth;
        float bestWater = out.waterLevel;
        for (int i = 0; i < rivers.size(); i++) {
            RiverSegment r = rivers.get(i);
            float dx = r.x1() - r.x0();
            float dz = r.z1() - r.z0();
            float len2 = dx * dx + dz * dz;
            float t = len2 < 1e-6f ? 0f : ((x - r.x0()) * dx + (z - r.z0()) * dz) / len2;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;
            float qx = r.x0() + dx * t - x;
            float qz = r.z0() + dz * t - z;
            float dist = (float) Math.sqrt(qx * qx + qz * qz);
            if (dist < bestDist) {
                bestDist = dist;
                bestHw = r.halfWidth();
                float w = r.h0() + (r.h1() - r.h0()) * t;
                if (dist <= r.halfWidth() + 2f) {
                    bestWater = Math.max( (float) TamrielMap.SEA_LEVEL, w);
                }
            }
        }
        out.riverDistance = bestDist;
        out.riverHalfWidth = bestHw;
        out.waterLevel = bestWater;
    }

    // ------------------------------------------------------------------ binary serialisation

    public byte[] write() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(worldSeed);
            out.writeInt(tileX);
            out.writeInt(tileZ);

            for (Edge e : Edge.values()) {
                List<EdgePoint> pts = edgePoints.getOrDefault(e, List.of());
                out.writeShort(pts.size());
                for (EdgePoint p : pts) {
                    out.writeInt(p.x());
                    out.writeInt(p.z());
                    out.writeFloat(p.height());
                    out.writeByte(p.kind());
                    out.writeBoolean(p.river());
                }
            }

            out.writeShort(authoredSeamPoints.size());
            for (EdgePoint p : authoredSeamPoints) {
                out.writeInt(p.x());
                out.writeInt(p.z());
                out.writeFloat(p.height());
                out.writeByte(p.kind());
                out.writeBoolean(p.river());
            }

            out.writeShort(places.size());
            for (TilePlace p : places) {
                out.writeUTF(p.id());
                out.writeUTF(p.name());
                out.writeByte(p.type().ordinal());
                out.writeInt(p.x());
                out.writeInt(p.z());
                out.writeShort(p.radius());
                out.writeFloat(p.padHeight());
                out.writeByte(p.why().size());
                for (String w : p.why()) {
                    out.writeUTF(w);
                }
            }

            out.writeShort(rivers.size());
            for (RiverSegment r : rivers) {
                out.writeInt(r.x0());
                out.writeInt(r.z0());
                out.writeFloat(r.h0());
                out.writeInt(r.x1());
                out.writeInt(r.z1());
                out.writeFloat(r.h1());
                out.writeFloat(r.halfWidth());
            }

            byte[] roadBytes = roadPlan.write();
            out.writeInt(roadBytes.length);
            out.write(roadBytes);

            byte[] streetBytes = streetPlan.write();
            out.writeInt(streetBytes.length);
            out.write(streetBytes);

            byte[] sceneBytes = scenePlan.write();
            out.writeInt(sceneBytes.length);
            out.write(sceneBytes);
        }
        return bo.toByteArray();
    }

    public static TilePlan read(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("not a tile plan (magic=" + Integer.toHexString(magic) + ")");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("tile plan version " + version + " (expected " + VERSION + ")");
            }
            long worldSeed = in.readLong();
            int tileX = in.readInt();
            int tileZ = in.readInt();

            EnumMap<Edge, List<EdgePoint>> edgePoints = new EnumMap<>(Edge.class);
            for (Edge e : Edge.values()) {
                int n = in.readUnsignedShort();
                List<EdgePoint> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(new EdgePoint(
                            in.readInt(),
                            in.readInt(),
                            in.readFloat(),
                            in.readByte(),
                            in.readBoolean()
                    ));
                }
                edgePoints.put(e, list);
            }

            int nSeam = in.readUnsignedShort();
            List<EdgePoint> seam = new ArrayList<>(nSeam);
            for (int i = 0; i < nSeam; i++) {
                seam.add(new EdgePoint(
                        in.readInt(),
                        in.readInt(),
                        in.readFloat(),
                        in.readByte(),
                        in.readBoolean()
                ));
            }

            PlaceType[] placeTypes = PlaceType.values();
            int nPlaces = in.readUnsignedShort();
            List<TilePlace> places = new ArrayList<>(nPlaces);
            for (int i = 0; i < nPlaces; i++) {
                String id = in.readUTF();
                String name = in.readUTF();
                PlaceType type = placeTypes[in.readUnsignedByte()];
                int x = in.readInt();
                int z = in.readInt();
                int radius = in.readUnsignedShort();
                float pad = in.readFloat();
                int nWhy = in.readUnsignedByte();
                List<String> why = new ArrayList<>(nWhy);
                for (int w = 0; w < nWhy; w++) {
                    why.add(in.readUTF());
                }
                places.add(new TilePlace(id, name, type, x, z, radius, pad, why));
            }

            int nRivers = in.readUnsignedShort();
            List<RiverSegment> rivers = new ArrayList<>(nRivers);
            for (int i = 0; i < nRivers; i++) {
                rivers.add(new RiverSegment(
                        in.readInt(),
                        in.readInt(),
                        in.readFloat(),
                        in.readInt(),
                        in.readInt(),
                        in.readFloat(),
                        in.readFloat()
                ));
            }

            byte[] roadBytes = new byte[in.readInt()];
            in.readFully(roadBytes);
            RoadPlan roadPlan = RoadPlan.read(roadBytes);

            byte[] streetBytes = new byte[in.readInt()];
            in.readFully(streetBytes);
            StreetPlan streetPlan = StreetPlan.read(streetBytes);

            byte[] sceneBytes = new byte[in.readInt()];
            in.readFully(sceneBytes);
            ScenePlan scenePlan = ScenePlan.read(sceneBytes);

            TileSheetGenerator.TileSheet sheet = TileSheetGenerator.generate(worldSeed, tileX, tileZ);
            return new TilePlan(
                    worldSeed,
                    tileX,
                    tileZ,
                    sheet,
                    edgePoints,
                    seam,
                    places,
                    rivers,
                    roadPlan,
                    streetPlan,
                    scenePlan
            );
        }
    }
}
