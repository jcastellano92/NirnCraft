package com.nirncraft.world.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.world.Provinces;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the world map writes the names of provinces and regions: the centre of each one's land, measured once per
 * session on a background thread by sampling {@link TamrielMap#region} over Tamriel and the foreign lands. Until
 * that finishes (or for provinces it can't place) the fixed anchors of {@link Provinces} are used.
 */
public final class MapLabels {
    private static final int STEP = 96;
    private static final int MIN_X = -24000, MAX_X = 24000, MIN_Z = -16000, MAX_Z = 24000;

    /** A name on the map, its anchor in block coordinates and the area it covers (blocks²). */
    public record Label(Component name, String id, String province, int x, int z, long area) {
    }

    private static volatile List<Label> provinces;
    private static volatile List<Label> regions;
    private static boolean started;

    private MapLabels() {}

    public static List<Label> provinces() {
        List<Label> p = provinces;
        if (p != null) return p;
        start();
        List<Label> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : Provinces.anchors().entrySet()) {
            out.add(new Label(Provinces.name(e.getKey()), e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1], 0));
        }
        return out;
    }

    public static List<Label> regions() {
        List<Label> r = regions;
        if (r != null) return r;
        start();
        return List.of();
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(MapLabels::compute, "NirnCraft map labels");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final class Acc {
        Region region;
        long sx, sz, n;
    }

    private static void compute() {
        try {
            Map<String, Acc> byRegion = new HashMap<>();
            Map<String, Acc> byProvince = new HashMap<>();
            for (int z = MIN_Z; z < MAX_Z; z += STEP) {
                for (int x = MIN_X; x < MAX_X; x += STEP) {
                    if (TamrielMap.elevation(x, z) < TamrielMap.SEA_LEVEL) continue;
                    Region r = TamrielMap.region(x, z);
                    if (r == null || Provinces.isSea(r) || "realm".equals(r.province())) continue;
                    Acc a = byRegion.computeIfAbsent(r.id(), k -> new Acc());
                    a.region = r;
                    a.sx += x;
                    a.sz += z;
                    a.n++;
                    Acc p = byProvince.computeIfAbsent(r.province(), k -> new Acc());
                    p.region = r;
                    p.sx += x;
                    p.sz += z;
                    p.n++;
                }
            }
            List<Label> reg = new ArrayList<>();
            for (Acc a : byRegion.values()) {
                if (a.n < 4) continue;
                reg.add(new Label(a.region.name(), a.region.id(), a.region.province(), (int) (a.sx / a.n), (int) (a.sz / a.n),
                        a.n * (long) STEP * STEP));
            }
            List<Label> prov = new ArrayList<>();
            for (Map.Entry<String, Acc> e : byProvince.entrySet()) {
                Acc a = e.getValue();
                int[] anchor = Provinces.anchor(e.getKey());
                // the fixed anchors read better for provinces whose land wraps around a sea (Morrowind)
                int x = anchor != null ? anchor[0] : (int) (a.sx / a.n);
                int z = anchor != null ? anchor[1] : (int) (a.sz / a.n);
                prov.add(new Label(Provinces.name(e.getKey()), e.getKey(), e.getKey(), x, z, a.n * (long) STEP * STEP));
            }
            // provinces with no land in the sampled area still get their fixed anchor
            for (Map.Entry<String, int[]> e : Provinces.anchors().entrySet()) {
                if (byProvince.containsKey(e.getKey())) continue;
                prov.add(new Label(Provinces.name(e.getKey()), e.getKey(), e.getKey(), e.getValue()[0], e.getValue()[1], 0));
            }
            regions = List.copyOf(reg);
            provinces = List.copyOf(prov);
        } catch (Throwable t) {
            NirnCraft.LOGGER.warn("Could not place map labels", t);
            regions = List.of();
        }
    }
}
