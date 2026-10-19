package com.nirncraft.worldplan;

import com.nirncraft.bake.Bake;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.TamrielMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Audit of the shipped road plan: along every road, every block of the centre line (and both edges of the bed) that
 * the map puts in water must be a bridge, a culvert or a ford span. Anything else is a road that runs into a river and
 * stops (the painter lays no deck there). Writes {@code tools/qa/out/road_water_audit.txt} and fails when any stretch
 * is left.
 */
class RoadWaterAuditTest {
    private static RoadPlan plan;

    @BeforeAll
    static void load() throws Exception {
        if ("true".equals(System.getenv("NIRNCRAFT_ROAD_AUDIT_LIVE"))) {
            plan = RoadPlan.read(RoadPlanBuilder.computeLive());
            return;
        }
        byte[] bytes;
        try (InputStream raw = Bake.class.getResourceAsStream("/nirncraft/baked/roadplan.bin")) {
            assertNotNull(raw, "Missing /nirncraft/baked/roadplan.bin on classpath");
            try (DataInputStream in = new DataInputStream(raw)) {
                String recordedHash = in.readUTF();
                Field f = Bake.class.getDeclaredField("inputs");
                f.setAccessible(true);
                if (f.get(null) == null) f.set(null, recordedHash);
                try (InflaterInputStream z = new InflaterInputStream(in)) {
                    bytes = z.readAllBytes();
                }
            }
        }
        plan = RoadPlan.read(bytes);
    }

    private static boolean waterSpan(byte span) {
        return span == RoadPlan.BRIDGE || span == RoadPlan.CULVERT || span == RoadPlan.FORD;
    }

    /**
     * True when a wet block of segment {@code sg} is carried by another road: a trail that joins a road on its bridge
     * stands on that bridge's deck, so the joined segment's own water span covers the block.
     */
    private static boolean carriedByJoined(int sg, double cx, double cz, RoadPlan.Hit hit) {
        for (int join : new int[]{plan.segJoinA[sg], plan.segJoinB[sg]}) {
            if (join < 0 || join >= plan.segments()) continue;
            if (plan.atSegment(cx, cz, join, hit) && waterSpan(hit.span) && hit.distance <= hit.halfWidth + 1.0f) return true;
        }
        return false;
    }

    /** True within 12 blocks of a segment end that stops at a site pad (a pad's own water is not a crossing). */
    private static boolean atSitePad(int sg, int i, int a, int b, double t) {
        boolean first = i == a && t < 12 && plan.segJoinA[sg] == RoadPlan.JOIN_SITE;
        boolean last = i == b - 1 && plan.varc[b] - plan.varc[i] - t < 12 && plan.segJoinB[sg] == RoadPlan.JOIN_SITE;
        return first || last;
    }

    private record Hit(int seg, int link, double x, double z, String span, String where) {
    }

    @Test
    void everyWetStretchOfRoadIsBridgedCulvertedOrForded() throws Exception {
        MapSample s = new MapSample();
        RoadPlan.Hit joined = new RoadPlan.Hit();
        List<Hit> hits = new ArrayList<>();
        TreeMap<String, Integer> bySpan = new TreeMap<>();
        java.util.Set<Integer> segs = new java.util.TreeSet<>();
        java.util.Set<Integer> centreSegs = new java.util.TreeSet<>();
        long samples = 0;
        for (int sg = 0; sg < plan.segments(); sg++) {
            int a = plan.segStart[sg], b = plan.segEnd[sg] - 1;
            float hw = plan.segHalfWidth[sg];
            for (int i = a; i < b; i++) {
                double dx = plan.vx[i + 1] - plan.vx[i], dz = plan.vz[i + 1] - plan.vz[i];
                double len = Math.hypot(dx, dz);
                if (len < 1e-3) continue;
                double ux = dx / len, uz = dz / len, nx = -uz, nz = ux;
                for (double t = 0; t < len; t += 1.0) {
                    double cx = plan.vx[i] + ux * t, cz = plan.vz[i] + uz * t;
                    byte span = plan.vspan[t < len / 2 ? i : i + 1];
                    if (waterSpan(span)) continue;
                    // the centre line and both edges of the bed
                    double[] off = {0, hw, -hw};
                    for (int k = 0; k < off.length; k++) {
                        samples++;
                        TamrielMap.sample((int) Math.floor(cx + nx * off[k]), (int) Math.floor(cz + nz * off[k]), s);
                        if (!s.inWater()) continue;
                        if (k == 0 && (carriedByJoined(sg, cx, cz, joined) || atSitePad(sg, i, a, b, t))) break;
                        String sn = RoadPlan.SPAN_NAMES[span];
                        bySpan.merge(sn, 1, Integer::sum);
                        boolean first = segs.add(sg);
                        boolean firstCentre = k == 0 && centreSegs.add(sg);
                        if (first || firstCentre) hits.add(new Hit(sg, plan.segLink[sg], cx, cz, sn, k == 0 ? "centre" : "edge"));
                        break;
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "road water audit: %d samples, %d wet-but-unbridged blocks on %d segments%n", samples,
                bySpan.values().stream().mapToInt(Integer::intValue).sum(), segs.size()));
        sb.append("by span type of the dry-spanned wet blocks: ").append(bySpan).append('\n');
        for (Hit h : hits) {
            String link = h.link() >= 0 && h.link() < plan.links() ? plan.linkA[h.link()] + " -> " + plan.linkB[h.link()] : "?";
            sb.append(String.format(Locale.ROOT, "seg %d (%s) first wet block at %.0f %.0f: span %s, %s%n", h.seg(), link, h.x(), h.z(), h.span(), h.where()));
        }
        Path out = Path.of("..", "tools", "qa", "out", "road_water_audit.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println(sb.length() > 6000 ? sb.substring(0, 6000) : sb);
        long centre = centreSegs.size();
        assertEquals(0, centre, "roads whose centre line runs into water without a bridge, culvert or ford: see " + out);
    }
}
