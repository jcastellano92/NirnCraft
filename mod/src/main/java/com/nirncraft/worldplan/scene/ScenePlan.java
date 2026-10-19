package com.nirncraft.worldplan.scene;

import com.nirncraft.NirnCraft;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The baked scene plan (pipeline stages S6 + S11, see {@code docs/WORLDGEN-PIPELINE.md} section 8): every human prop
 * of the world belongs to a planned scene with an anchor (a road bend, a crossroads, a ford, a bridge end, a farm, a
 * settlement's edge, a camp site, a shrine, a battle site, a ruin's edge, a dungeon mouth or a lore-web clue) and a
 * reason. The plan depends only on the authored map, the places, the road plan and the scatter/landmark/lore data,
 * never on the world seed, so it is computed at build time ({@code tools/bake/bake.ps1}, bake name
 * {@value ScenePlanBuilder#BAKE_NAME}) and loaded at startup.
 *
 * <p>A scene is {@code {id, kind, x, z, h, facing, anchor{type, ref}, reason, props[], clue?, note?, path?}}. Props are
 * offsets from the scene centre with a scatter kind id ({@code nirncraft/scatter/kinds.json}), a landmark vignette
 * ({@code lm:<kind id>}), {@code clue_sign} or {@code clue_note}. The chunk feature
 * ({@code com.nirncraft.scatter.SceneRealiser}) asks {@link #inChunk} and realises only the props whose centre lies in
 * its chunk, and the path columns inside it: deterministic, no order dependence.</p>
 *
 * <p>Immutable after loading; every read is thread-safe and allocation-free.</p>
 */
public final class ScenePlan {
    public static final int MAGIC = 0x53434e50; // "SCNP"
    public static final int VERSION = 1;

    /** Anchor types (keep in step with tools/qa/placement_lint.py). */
    public static final byte ROAD_BEND = 0, CROSSROADS = 1, FORD = 2, BRIDGE_END = 3, FARM = 4, CAMP_SITE = 5, SHRINE_SITE = 6,
            BATTLE_SITE = 7, RUIN_EDGE = 8, DUNGEON_MOUTH = 9, LOREWEB_CLUE = 10, SETTLEMENT_EDGE = 11, TRAIL_SIDE = 12, TRAIL_END = 13;
    public static final String[] ANCHOR_NAMES = {"ROAD_BEND", "CROSSROADS", "FORD", "BRIDGE_END", "FARM", "CAMP_SITE",
            "SHRINE_SITE", "BATTLE_SITE", "RUIN_EDGE", "DUNGEON_MOUTH", "LOREWEB_CLUE", "SETTLEMENT_EDGE", "TRAIL_SIDE", "TRAIL_END"};
    /** Special prop kinds. */
    public static final String SIGN = "clue_sign", NOTE = "clue_note", LANDMARK_PREFIX = "lm:";
    /** {@link #pathX} when a scene has no worn path. */
    public static final int NO_PATH = Integer.MIN_VALUE;
    private static final int[] NONE = new int[0];

    /** Land area (km²) the plan covers and the planned road length (km) per class: the density denominators. */
    public final float landKm2;
    public final float[] roadKm;
    /** Kind ids used by scenes and props. */
    public final String[] kinds;

    // ---- scenes (index = id)
    public final int[] kind, x, z;
    public final float[] h;
    /** 0..3 = south, west, north, east: the direction the scene faces (towards its anchor). */
    public final byte[] facing;
    public final byte[] anchor;
    public final String[] ref, reason, clue, note;
    /** End of the worn path from the centre towards the road ({@link #NO_PATH} = none). */
    public final int[] pathX, pathZ;
    /** Footprint radius (blocks) around the centre: every prop's reach. */
    public final short[] radius;
    /** Props of scene {@code s}: {@code propOff[s]..propOff[s+1]}. */
    public final int[] propOff;
    public final short[] propKind;
    public final byte[] propDx, propDz;

    private final Long2ObjectOpenHashMap<int[]> byChunk = new Long2ObjectOpenHashMap<>();

    ScenePlan(float landKm2, float[] roadKm, String[] kinds, int[] kind, int[] x, int[] z, float[] h, byte[] facing, byte[] anchor,
              String[] ref, String[] reason, String[] clue, String[] note, int[] pathX, int[] pathZ, short[] radius, int[] propOff,
              short[] propKind, byte[] propDx, byte[] propDz) {
        this.landKm2 = landKm2;
        this.roadKm = roadKm;
        this.kinds = kinds;
        this.kind = kind;
        this.x = x;
        this.z = z;
        this.h = h;
        this.facing = facing;
        this.anchor = anchor;
        this.ref = ref;
        this.reason = reason;
        this.clue = clue;
        this.note = note;
        this.pathX = pathX;
        this.pathZ = pathZ;
        this.radius = radius;
        this.propOff = propOff;
        this.propKind = propKind;
        this.propDx = propDx;
        this.propDz = propDz;
        index();
    }

    private void index() {
        Long2ObjectOpenHashMap<IntArrayList> tmp = new Long2ObjectOpenHashMap<>();
        for (int s = 0; s < x.length; s++) {
            int r = radius[s] + 1;
            int x0 = x[s] - r, x1 = x[s] + r, z0 = z[s] - r, z1 = z[s] + r;
            if (pathX[s] != NO_PATH) {
                x0 = Math.min(x0, pathX[s]);
                x1 = Math.max(x1, pathX[s]);
                z0 = Math.min(z0, pathZ[s]);
                z1 = Math.max(z1, pathZ[s]);
            }
            for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                    tmp.computeIfAbsent(ChunkPos.pack(cx, cz), k -> new IntArrayList(2)).add(s);
                }
            }
        }
        for (var e : tmp.long2ObjectEntrySet()) byChunk.put(e.getLongKey(), e.getValue().toIntArray());
        byChunk.trim();
    }

    public int scenes() {
        return x.length;
    }

    public int props() {
        return propKind.length;
    }

    /** Scenes whose footprint (props and path) reaches chunk (cx, cz); an empty array when none. Do not modify. */
    public int[] inChunk(int cx, int cz) {
        int[] a = byChunk.get(ChunkPos.pack(cx, cz));
        return a == null ? NONE : a;
    }

    /** Kind id of scene {@code s} (a scatter kind, a landmark vignette {@code lm:<id>}). */
    public String kindOf(int s) {
        return kinds[kind[s]];
    }

    public String propKindOf(int p) {
        return kinds[propKind[p]];
    }

    /** The scene nearest to (bx, bz) within {@code max} blocks, or -1 (a linear scan; commands only). */
    public int nearest(int bx, int bz, int max) {
        int best = -1;
        long bestD = (long) max * max;
        for (int s = 0; s < x.length; s++) {
            long dx = x[s] - bx, dz = z[s] - bz, d = dx * dx + dz * dz;
            if (d <= bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** True when a scene's footprint covers (bx, bz) with {@code margin} blocks to spare (natural scatter keeps off). */
    public boolean covers(int bx, int bz, int margin) {
        for (int s : inChunk(bx >> 4, bz >> 4)) {
            long dx = x[s] - bx, dz = z[s] - bz, r = radius[s] + margin;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        if (this == Holder.PLAN && !com.nirncraft.tamriel.TamrielMap.inTamriel(bx, bz)) {
            ScenePlan tp = com.nirncraft.worldplan.tile.TilePlanner.scenePlanAt(bx, bz);
            if (tp != null && tp != this) return tp.covers(bx, bz, margin);
        }
        return false;
    }

    /** Returns the active {@link ScenePlan} for block {@code (bx, bz)} (baked inside Tamriel, tile plan outside). */
    public static ScenePlan planFor(int bx, int bz) {
        if (!com.nirncraft.tamriel.TamrielMap.inTamriel(bx, bz)) {
            ScenePlan tp = com.nirncraft.worldplan.tile.TilePlanner.scenePlanAt(bx, bz);
            if (tp != null) return tp;
        }
        return get();
    }

    // ------------------------------------------------------------------ serialisation

    public byte[] write() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeFloat(landKm2);
            out.writeByte(roadKm.length);
            for (float v : roadKm) out.writeFloat(v);
            out.writeInt(kinds.length);
            for (String k : kinds) out.writeUTF(k);
            out.writeInt(x.length);
            for (int s = 0; s < x.length; s++) {
                out.writeShort(kind[s]);
                out.writeInt(x[s]);
                out.writeInt(z[s]);
                out.writeShort(Math.round(h[s] * 16f));
                out.writeByte(facing[s]);
                out.writeByte(anchor[s]);
                out.writeUTF(ref[s]);
                out.writeUTF(reason[s]);
                out.writeUTF(clue[s]);
                out.writeUTF(note[s]);
                out.writeInt(pathX[s]);
                out.writeInt(pathZ[s]);
                out.writeShort(radius[s]);
                int n = propOff[s + 1] - propOff[s];
                out.writeByte(n);
                for (int p = propOff[s]; p < propOff[s + 1]; p++) {
                    out.writeShort(propKind[p]);
                    out.writeByte(propDx[p]);
                    out.writeByte(propDz[p]);
                }
            }
        }
        return bo.toByteArray();
    }

    public static ScenePlan read(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("not a scene plan");
            int v = in.readInt();
            if (v != VERSION) throw new IOException("scene plan version " + v + " (expected " + VERSION + ")");
            float land = in.readFloat();
            float[] roadKm = new float[in.readUnsignedByte()];
            for (int i = 0; i < roadKm.length; i++) roadKm[i] = in.readFloat();
            String[] kinds = new String[in.readInt()];
            for (int i = 0; i < kinds.length; i++) kinds[i] = in.readUTF();
            int n = in.readInt();
            int[] kind = new int[n], x = new int[n], z = new int[n], px = new int[n], pz = new int[n], off = new int[n + 1];
            float[] h = new float[n];
            byte[] facing = new byte[n], anchor = new byte[n];
            String[] ref = new String[n], reason = new String[n], clue = new String[n], note = new String[n];
            short[] radius = new short[n];
            IntArrayList pk = new IntArrayList(), pdx = new IntArrayList(), pdz = new IntArrayList();
            for (int s = 0; s < n; s++) {
                kind[s] = in.readUnsignedShort();
                x[s] = in.readInt();
                z[s] = in.readInt();
                h[s] = in.readShort() / 16f;
                facing[s] = in.readByte();
                anchor[s] = in.readByte();
                ref[s] = in.readUTF();
                reason[s] = in.readUTF();
                clue[s] = in.readUTF();
                note[s] = in.readUTF();
                px[s] = in.readInt();
                pz[s] = in.readInt();
                radius[s] = in.readShort();
                int m = in.readUnsignedByte();
                off[s] = pk.size();
                for (int p = 0; p < m; p++) {
                    pk.add(in.readUnsignedShort());
                    pdx.add(in.readByte());
                    pdz.add(in.readByte());
                }
            }
            off[n] = pk.size();
            short[] propKind = new short[pk.size()];
            byte[] propDx = new byte[pk.size()], propDz = new byte[pk.size()];
            for (int p = 0; p < propKind.length; p++) {
                propKind[p] = (short) pk.getInt(p);
                propDx[p] = (byte) pdx.getInt(p);
                propDz[p] = (byte) pdz.getInt(p);
            }
            return new ScenePlan(land, roadKm, kinds, kind, x, z, h, facing, anchor, ref, reason, clue, note, px, pz, radius, off,
                    propKind, propDx, propDz);
        }
    }

    static ScenePlan empty() {
        return new ScenePlan(0f, new float[4], new String[0], new int[0], new int[0], new int[0], new float[0], new byte[0],
                new byte[0], new String[0], new String[0], new String[0], new String[0], new int[0], new int[0], new short[0],
                new int[]{0}, new short[0], new byte[0], new byte[0]);
    }

    // ------------------------------------------------------------------ the shipped plan

    private static final class Holder {
        static final ScenePlan PLAN = load();

        private static ScenePlan load() {
            long t0 = System.nanoTime();
            byte[] baked = com.nirncraft.bake.Bake.load(ScenePlanBuilder.BAKE_NAME);
            if (baked != null) {
                try {
                    ScenePlan p = read(baked);
                    NirnCraft.LOGGER.info("[sceneplan] loaded the baked scene plan: {} scenes, {} props ({} ms)", p.scenes(), p.props(),
                            (System.nanoTime() - t0) / 1_000_000);
                    return p;
                } catch (IOException | RuntimeException e) {
                    NirnCraft.LOGGER.warn("[sceneplan] the baked scene plan is unreadable; planning live", e);
                }
            }
            try {
                ScenePlan p = read(ScenePlanBuilder.computeLive());
                NirnCraft.LOGGER.info("[sceneplan] planned live: {} scenes, {} props ({} ms)", p.scenes(), p.props(),
                        (System.nanoTime() - t0) / 1_000_000);
                return p;
            } catch (IOException | RuntimeException e) {
                NirnCraft.LOGGER.error("[sceneplan] planning failed; no scenes", e);
                return empty();
            }
        }
    }

    /** The plan for this build (baked, else computed live on first use: call {@link #warm} early). */
    public static ScenePlan get() {
        return Holder.PLAN;
    }

    public static void warm() {
        if (Holder.PLAN == null) throw new IllegalStateException();
    }

    /** True when the scene plan replaces the older roll-based human clutter (the road module's dressing). */
    public static boolean active() {
        return get().scenes() > 0;
    }
}
