package com.skycraft.aether;

// aether:sandbox-only (compiled against the real mod by sandbox_check.ps1, not by compile_check.ps1's stubs)

import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.build.Builders;
import com.skycraft.tamriel.settlement.build.LandmarkRegistry;
import com.skycraft.tamriel.settlement.grammar.Blueprint;
import com.skycraft.tamriel.settlement.kit.Kits;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Spec;
import com.skycraft.worldplan.street.StreetPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Far-field buildings: Skycraft's planned settlements (StreetPlan: every named capital, city, town, village and farm,
 * baked and seed-free, so clients draw them themselves) rasterised into aether's per-tile structure layers. Each
 * planned piece becomes massing: grammar buildings their blueprint's parts with wall top, roof rise and roof shape;
 * city walls lines from floor to floor2; gates and towers boxes; landmarks a tower profile. Wall and roof colours
 * are the kit's blocks' map colours, registered as extra palette entries. Detail below a column's size is sampled
 * away (houses fade with distance), but walls stay at least one column thick so a city's outline carries to the
 * horizon.
 */
final class AetherSettlements implements AetherFarField.Overlay {
    /** First palette id for wall and roof colours (the preview palette ends far below). */
    static final int FIRST_MATERIAL = 64;
    private static final int N = AetherNative.TILE * AetherNative.TILE;

    enum Roof { FLAT, GABLE_X, GABLE_Z, HIP, DOME, SPIRE }

    /** One building part or box in world blocks: [x0, x1] x [z0, z1], walls to wallTop, roof rising `rise` above. */
    record Mass(int x0, int z0, int x1, int z1, float wallTop, float rise, Roof roof, short wall, short roofMat) {}

    /** A wall segment: centre line (ax, az) - (bx, bz), half width, top interpolated from topA to topB. */
    record Line(float ax, float az, float bx, float bz, float half, float topA, float topB, short mat) {}

    private final StreetPlan plan;
    private final Map<Integer, List<Object>> bySettlement = new HashMap<>();  // settlement index -> masses and lines
    private final Map<BlockState, Short> ids = new HashMap<>();
    private final List<float[]> newMaterials = new ArrayList<>();
    private final Set<Long> uploaded = new HashSet<>();
    private final float[] wallTop = new float[N], roofTop = new float[N];
    private final short[] wallMat = new short[N], roofMat = new short[N];

    AetherSettlements(StreetPlan plan) {
        this.plan = plan;
    }

    /** Over the mod's street plan (loads or plans it on first use: call off the render thread). */
    static AetherSettlements create() {
        return new AetherSettlements(StreetPlan.get());
    }

    /**
     * Buildings for every resident tile of LOD `lod` in the window that has not had them yet (tiles evicted since are
     * redone when they come back). Far-field worker thread.
     */
    @Override
    public void cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
        int span = AetherNative.TILE << lod;
        for (int tz = Math.floorDiv(z0, span); tz <= Math.floorDiv(z1 - 1, span); tz++)
            for (int tx = Math.floorDiv(x0, span); tx <= Math.floorDiv(x1 - 1, span); tx++) {
                long k = AetherRefiningSource.key(lod, tx, tz);
                if (!ctx.hasTile(lod, tx, tz)) {
                    uploaded.remove(k);  // evicted (the buildings went with it): again when it is back
                    continue;
                }
                if (!uploaded.add(k)) continue;
                if (!fill(lod, tx, tz)) continue;  // nothing planned here
                if (!newMaterials.isEmpty()) {
                    ctx.setMaterials(FIRST_MATERIAL + ids.size() - newMaterials.size(), newMaterials.toArray(new float[0][]));
                    newMaterials.clear();
                }
                ctx.uploadColumnStructures(lod, tx, tz, wallTop, roofTop, wallMat, roofMat);
            }
    }

    /** Rasterises tile (lod, tx, tz) into the arrays; false when no settlement reaches it. */
    boolean fill(int lod, int tx, int tz) {
        int s = 1 << lod, bx = tx * (AetherNative.TILE << lod), bz = tz * (AetherNative.TILE << lod);
        int ex = bx + (AetherNative.TILE << lod), ez = bz + (AetherNative.TILE << lod);
        boolean any = false;
        for (int i = 0; i < plan.settlements(); i++) {
            int r = plan.sReach[i] + 64;
            if (plan.sX[i] + r < bx || plan.sX[i] - r >= ex || plan.sZ[i] + r < bz || plan.sZ[i] - r >= ez) continue;
            if (!any) {
                Arrays.fill(wallTop, Float.NaN);
                Arrays.fill(roofTop, Float.NaN);
                Arrays.fill(wallMat, (short) 0);
                Arrays.fill(roofMat, (short) 0);
                any = true;
            }
            for (Object o : pieces(i)) {
                if (o instanceof Mass m) stamp(m, s, bx, bz);
                else stamp((Line) o, s, bx, bz);
            }
        }
        return any;
    }

    private void stamp(Mass m, int s, int bx, int bz) {
        // columns whose centre lies in the footprint
        int i0 = Math.max(0, ceilDiv(m.x0 - bx - s / 2, s)), i1 = Math.min(AetherNative.TILE - 1, Math.floorDiv(m.x1 - bx - s / 2, s));
        int j0 = Math.max(0, ceilDiv(m.z0 - bz - s / 2, s)), j1 = Math.min(AetherNative.TILE - 1, Math.floorDiv(m.z1 - bz - s / 2, s));
        if (i0 > i1 || j0 > j1) {
            // smaller than a column: only big pieces (towers, keeps, landmarks) keep their column
            if (m.x1 - m.x0 + 1 < 9 && m.z1 - m.z0 + 1 < 9) return;
            int ci = Math.floorDiv((m.x0 + m.x1) / 2 - bx, s), cj = Math.floorDiv((m.z0 + m.z1) / 2 - bz, s);
            if (ci < 0 || cj < 0 || ci >= AetherNative.TILE || cj >= AetherNative.TILE) return;
            i0 = i1 = ci;
            j0 = j1 = cj;
        }
        float cx = (m.x0 + m.x1 + 1) * 0.5f, cz = (m.z0 + m.z1 + 1) * 0.5f;
        float hx = (m.x1 - m.x0 + 1) * 0.5f, hz = (m.z1 - m.z0 + 1) * 0.5f;
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                float wx = bx + (i + 0.5f) * s, wz = bz + (j + 0.5f) * s;
                float ux = Math.min(1f, Math.abs(wx - cx) / hx), uz = Math.min(1f, Math.abs(wz - cz) / hz);
                float up = switch (m.roof) {
                    case FLAT -> 1f;
                    case GABLE_X -> 1f - uz;  // ridge along x
                    case GABLE_Z -> 1f - ux;
                    case HIP -> 1f - Math.max(ux, uz);
                    case DOME -> (float) Math.sqrt(Math.max(0f, 1f - ux * ux - uz * uz));
                    case SPIRE -> Math.max(0f, 1f - (float) Math.sqrt(ux * ux + uz * uz));
                };
                put(i, j, m.wallTop, m.wallTop + m.rise * up, m.wall, m.roofMat);
            }
    }

    private void stamp(Line l, int s, int bx, int bz) {
        float half = Math.max(l.half, s * 0.5f);  // at least one column thick at every LOD
        float minX = Math.min(l.ax, l.bx) - half, maxX = Math.max(l.ax, l.bx) + half;
        float minZ = Math.min(l.az, l.bz) - half, maxZ = Math.max(l.az, l.bz) + half;
        int i0 = Math.max(0, (int) Math.floor((minX - bx) / s)), i1 = Math.min(AetherNative.TILE - 1, (int) Math.floor((maxX - bx) / s));
        int j0 = Math.max(0, (int) Math.floor((minZ - bz) / s)), j1 = Math.min(AetherNative.TILE - 1, (int) Math.floor((maxZ - bz) / s));
        float dx = l.bx - l.ax, dz = l.bz - l.az, len2 = Math.max(1e-6f, dx * dx + dz * dz);
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                float wx = bx + (i + 0.5f) * s, wz = bz + (j + 0.5f) * s;
                float t = Math.max(0f, Math.min(1f, ((wx - l.ax) * dx + (wz - l.az) * dz) / len2));
                float px = l.ax + dx * t - wx, pz = l.az + dz * t - wz;
                if (px * px + pz * pz > half * half) continue;
                float top = l.topA + (l.topB - l.topA) * t;
                put(i, j, top, top, l.mat, l.mat);
            }
    }

    private void put(int i, int j, float wTop, float rTop, short wMat, short rMat) {
        int c = j * AetherNative.TILE + i;
        if (!(rTop > roofTop[c]) && !Float.isNaN(roofTop[c])) return;  // the taller piece wins
        wallTop[c] = wTop;
        roofTop[c] = rTop;
        wallMat[c] = wMat;
        roofMat[c] = rMat;
    }

    /** Massing of settlement i, built once. */
    private List<Object> pieces(int i) {
        return bySettlement.computeIfAbsent(i, k -> {
            List<Object> out = new ArrayList<>();
            for (Spec sp : plan.layout(plan.sId[k])) {
                try {
                    piece(sp, out);
                } catch (RuntimeException e) {
                    // one odd piece must not cost the town: skip it
                }
            }
            return out;
        });
    }

    private void piece(Spec s, List<Object> out) {
        if (s.has(Spec.OMIT) || s.floor == Spec.UNSET || s.kind == null) return;
        Archetype k = s.kind;
        float floor = s.floor + 1;  // floor is the y of the floor block: walls stand on top of it
        if (k == Archetype.WALL) {
            Palette p = palette(s);
            float f2 = (s.floor2 == Spec.UNSET ? s.floor : s.floor2) + 1;
            BlockState b = s.has(Spec.PALISADE) ? p.post : p.cityWall;
            out.add(new Line(s.x + 0.5f, s.z + 0.5f, s.x2 + 0.5f, s.z2 + 0.5f, Math.max(1, s.w) * 0.5f, floor + s.h, f2 + s.h, id(b)));
            return;
        }
        if (k == Archetype.GATE) {
            Palette p = palette(s);
            box(s, out, floor + s.h, 0, Roof.FLAT, p.cityWall, p.cityWall);
            return;
        }
        if (k == Archetype.LANDMARK) {
            LandmarkRegistry.Def d = LandmarkRegistry.ofVariant(s.variant);
            float h = s.h > 0 ? s.h : d != null ? d.h() : 20;
            Palette p = palette(s);
            int w = Math.max(s.w, d != null ? d.w() : s.w), dd = Math.max(s.d, d != null ? d.d() : s.d);
            int x0 = s.x - w / 2, z0 = s.z - dd / 2;
            // a tall thin landmark is a tower with a pointed top; a broad one a block with a domed top
            boolean spire = h > 3 * Math.max(w, dd);
            float rise = spire ? Math.max(w, dd) * 1.5f : Math.min(w, dd) * 0.25f;
            out.add(new Mass(x0, z0, x0 + w - 1, z0 + dd - 1, floor + h - rise, rise, spire ? Roof.SPIRE : Roof.DOME,
                    id(p.cityWall), id(p.roofFull)));
            return;
        }
        if (!k.building) return;  // streets, plazas, fields, docks, stalls ...: ground detail
        if (s.grammar()) {
            Blueprint bp = Blueprint.of(s);
            float wall = floor + bp.wallTop() + 1;
            if (bp.body != Blueprint.Body.RECT) {
                box(s, out, floor + bp.height() - 4, 0, Roof.DOME, bp.pal.wall, bp.pal.roofFull);
                return;
            }
            int[] o = com.skycraft.tamriel.settlement.SettlementPiece.origin(s);
            for (Blueprint.Part part : bp.parts) {
                int ax = o[0] + mapX(s.facing, part.x0(), part.z0()), az = o[1] + mapZ(s.facing, part.x0(), part.z0());
                int cx = o[0] + mapX(s.facing, part.x1(), part.z1()), cz = o[1] + mapZ(s.facing, part.x1(), part.z1());
                // a ridge along the part's local z runs along world z when the building faces north or south
                boolean alongWorldZ = part.alongZ() == (s.facing.getAxis() == Direction.Axis.Z);
                Roof roof = switch (bp.roof) {
                    case FLAT, CRENEL -> Roof.FLAT;
                    case DOME -> Roof.DOME;
                    case SPIRE -> Roof.SPIRE;
                    case HIP -> Roof.HIP;
                    default -> alongWorldZ ? Roof.GABLE_Z : Roof.GABLE_X;
                };
                out.add(new Mass(Math.min(ax, cx), Math.min(az, cz), Math.max(ax, cx), Math.max(az, cz), wall, bp.rise(part),
                        roof, id(bp.pal.wall), id(bp.pal.roofFull)));
            }
            return;
        }
        // the older archetype builders: their height, two thirds walls
        Palette p = palette(s);
        float h = Builders.height(s) - 4;
        boolean tower = k == Archetype.TOWER;
        box(s, out, floor + (tower ? h : h * 0.62f), tower ? 2 : h * 0.38f, tower ? Roof.FLAT : Roof.HIP,
                tower ? p.cityWall : p.wall, p.roofFull);
    }

    /** The piece's rotated footprint as one mass. */
    private void box(Spec s, List<Object> out, float wallTop, float rise, Roof roof, BlockState wall, BlockState roofB) {
        int[] o = com.skycraft.tamriel.settlement.SettlementPiece.origin(s);
        int ax = o[0] + mapX(s.facing, 0, 0), az = o[1] + mapZ(s.facing, 0, 0);
        int cx = o[0] + mapX(s.facing, s.w - 1, s.d - 1), cz = o[1] + mapZ(s.facing, s.w - 1, s.d - 1);
        out.add(new Mass(Math.min(ax, cx), Math.min(az, cz), Math.max(ax, cx), Math.max(az, cz), wallTop, rise, roof,
                id(wall), id(roofB)));
    }

    private static Palette palette(Spec s) {
        return Kits.palette(Culture.byOrdinal(Math.max(0, s.culture)), s.style);
    }

    /** SettlementPiece.mapX / mapZ: a building's local (lx, lz) to a world offset from its origin. */
    static int mapX(Direction f, int lx, int lz) {
        return switch (f) {
            case EAST -> -lz;
            case SOUTH -> -lx;
            case WEST -> lz;
            default -> lx;
        };
    }

    static int mapZ(Direction f, int lx, int lz) {
        return switch (f) {
            case EAST -> lx;
            case SOUTH -> -lz;
            case WEST -> -lx;
            default -> lz;
        };
    }

    /** Palette id of a block's far colour (its map colour, linear), registered on first use. */
    private short id(BlockState b) {
        Short v = ids.get(b);
        if (v != null) return v;
        int rgb = b.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        float r = lin((rgb >> 16) & 255), g = lin((rgb >> 8) & 255), bl = lin(rgb & 255);
        newMaterials.add(new float[] {r, g, bl, 0.85f, 0f, AetherNative.CLASS_TERRAIN});
        short id = (short) (FIRST_MATERIAL + ids.size());
        ids.put(b, id);
        return id;
    }

    private static float lin(int c) {
        return (float) Math.pow(c / 255.0, 2.2);
    }

    private static int ceilDiv(int a, int b) {
        return -Math.floorDiv(-a, b);
    }
}
