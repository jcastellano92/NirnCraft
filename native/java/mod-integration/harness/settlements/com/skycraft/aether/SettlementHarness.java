package com.skycraft.aether;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.TamrielHeight;
import com.skycraft.worldplan.street.StreetPlan;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Far-field settlements, offline, on the real mod classes (sandbox_check's build): exact terrain around a city
 * (AetherExactSource) plus its planned buildings (AetherSettlements over the StreetPlan), rendered by aether_core
 * from far and from mid distance, and the mid view again without buildings for comparison.
 * <pre>  .\native\java\mod-integration\harness\run_settlement_harness.ps1 [-Place whiterun]</pre>
 */
public final class SettlementHarness {
    private SettlementHarness() {}

    static final PrintStream OUT = System.out;  // vanilla's bootstrap later silences System.out/err

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace(OUT);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        Path dll = Path.of(a[0]).toAbsolutePath(), data = Path.of(a[1]), out = Path.of(a[2]);
        String place = a[3];
        long seed = Long.parseLong(a[4]);
        long t0 = System.nanoTime();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        TamrielHeight height = HarnessSeed.height(seed);
        StreetPlan plan = StreetPlan.get();
        int px, pz;
        if (place.startsWith("xl:")) {  // the n-th XL natural landmark nearest the given spot: xl:n,x,z
            String[] q = place.substring(3).split(",");
            int n = Integer.parseInt(q[0]);
            AetherNet.FarSites fs = AetherNatural.enumerate(seed, Integer.parseInt(q[1]), Integer.parseInt(q[2]));
            int found = -1;
            for (int i = 0, seen = 0; i < fs.count(); i++)
                if (fs.kinds()[i].startsWith("xl_") && seen++ == n) {
                    found = i;
                    break;
                }
            if (found < 0) throw new IllegalArgumentException("no XL landmark there");
            px = fs.xz()[found * 2];
            pz = fs.xz()[found * 2 + 1];
            OUT.printf("XL landmark %s at %d, %d%n", fs.kinds()[found], px, pz);
        } else if (place.startsWith("at:")) {  // any spot: at:x,z
            String[] xz = place.substring(3).split(",");
            px = Integer.parseInt(xz[0]);
            pz = Integer.parseInt(xz[1]);
        } else if (place.startsWith("place:")) {  // any place by id (forts, landmarks ...)
            var p = com.skycraft.tamriel.Places.byId(place.substring(6)).orElseThrow(() -> new IllegalArgumentException("no place " + place));
            px = p.x();
            pz = p.z();
        } else {
            int si = plan.settlement(place);
            if (si < 0) throw new IllegalArgumentException("no settlement " + place);
            px = plan.sX[si];
            pz = plan.sZ[si];
        }
        OUT.printf("bootstrap + plans: %.0f ms; %d settlements; %s at %d, %d%n", (System.nanoTime() - t0) / 1e6,
                plan.settlements(), place, px, pz);
        com.skycraft.tamriel.Place fort = null;
        for (var p : com.skycraft.tamriel.Places.all())
            if (p.type() == com.skycraft.tamriel.PlaceType.FORT && (fort == null || p.distSqr(px, pz) < fort.distSqr(px, pz))) fort = p;
        if (fort != null) OUT.printf("nearest fort: place:%s at %d, %d%n", fort.id(), fort.x(), fort.z());

        AetherNative n = AetherNative.load(dll);
        Path raster = data.resolve("tamriel_raster.bin");
        int[] noise = null;
        Path noisePath = data.resolve("cloud_noise.bin");
        if (Files.exists(noisePath)) {
            noise = new int[64 * 64 * 64];
            ByteBuffer.wrap(Files.readAllBytes(noisePath)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(noise);
        }
        double mid = a.length > 5 ? Double.parseDouble(a[5]) : 45;
        double[][] views = {{2400, 70}, {800, mid}};  // distance from the centre, height over the ground
        int w = 1600, h = 900;
        int[][] images = new int[4][];
        int[] buildingPx = new int[1], counters = new int[4];
        double[] ms = new double[3];
        try (AetherNative.Context ctx = n.create(AetherNative.BACKEND_VULKAN);
             AetherNative.Context bare = n.create(AetherNative.BACKEND_VULKAN)) {
            AetherExactSource src = new AetherExactSource(height, () -> raster, 8);
            AetherExactSource src2 = new AetherExactSource(height, () -> raster, 8);
            src.prepare(ctx);
            src2.prepare(bare);
            AetherSettlements towns = new AetherSettlements(plan, src::groundAt, new AetherMaterials());
            // the natural landmarks the server would send this player (AetherNatural over the harness's seed)
            long n0 = System.nanoTime();
            AetherNet.FarSites sites = AetherNatural.enumerate(seed, px, pz);
            int xl = 0;
            for (int i = 0; i < sites.count(); i++) if (sites.kinds()[i].startsWith("xl_")) xl++;
            OUT.printf("natural landmarks around: %d (%d XL) in %.0f ms%n", sites.count(), xl, (System.nanoTime() - n0) / 1e6);
            AetherSettlements.farSites = () -> sites;
            ctx.setTransitions(0f);
            for (int v = 0; v < views.length; v++) {
                double dist = views[v][0];
                // -Dharness.overhead=true: the mid view straight down from above the target instead
                boolean overhead = v == 1 && Boolean.getBoolean("harness.overhead");
                double cx = overhead ? px + 0.5 : px + 0.8 * dist, cz = overhead ? pz + 0.5 : pz + 0.6 * dist;
                MapSample m = TamrielMap.sampleScratch((int) cx, (int) cz);
                double cy = Math.max(height.height((int) cx, (int) cz, m), 63) + views[v][1];
                double ty = height.height(px, pz, TamrielMap.sampleScratch(px, pz)) + 25;
                double pitch = Math.toDegrees(Math.atan2(ty - cy, dist));
                if (overhead) {
                    cy = ty + 220;
                    pitch = -89.5;
                }
                long c0 = System.nanoTime();
                for (AetherNative.Context c : v == 1 ? new AetherNative.Context[] {ctx, bare} : new AetherNative.Context[] {ctx}) {
                    AetherExactSource s = c == ctx ? src : src2;
                    for (int lod = 0; lod < AetherFarField.LODS; lod++) {
                        int span = 256 << lod;
                        int tx = Math.floorDiv((int) Math.floor(cx), span), tz = Math.floorDiv((int) Math.floor(cz), span);
                        int x0 = (tx - AetherFarField.WINDOW) * span, z0 = (tz - AetherFarField.WINDOW) * span;
                        int x1 = (tx + AetherFarField.WINDOW + 1) * span, z1 = (tz + AetherFarField.WINDOW + 1) * span;
                        while (s.cover(c, lod, x0, z0, x1, z1)) { }
                        if (c == ctx) towns.cover(c, lod, x0, z0, x1, z1);
                    }
                    if (noise != null) c.setCloudNoise(noise);
                    c.update(cx, cy, cz, 0);
                }
                OUT.printf("view %d: %.0f m out, camera %.0f over the ground, pitch %.1f; tiles + buildings %.0f ms%n", v, dist,
                        views[v][1], pitch, (System.nanoTime() - c0) / 1e6);
                AetherNative.Frame f = new AetherNative.Frame();
                f.x = cx;
                f.y = cy;
                f.z = cz;
                f.width = w;
                f.height = h;
                f.maxDistance = 40000;
                f.planetRadius = 6_371_000f;
                f.lodBias = 1;
                f.flags = AetherNative.FRAME_SHADOWS | AetherNative.FRAME_ATMOS | AetherNative.FRAME_SKY;
                AetherProbe.cameraMatrix(Math.toDegrees(Math.atan2(-0.8, 0.6)), pitch, 50, w / (double) h, f.invViewProj);
                double se = Math.toRadians(30), sa = Math.toRadians(150);
                f.sunX = (float) (Math.sin(sa) * Math.cos(se));
                f.sunY = (float) Math.sin(se);
                f.sunZ = (float) (-Math.cos(sa) * Math.cos(se));
                int[] color = new int[w * h];
                ctx.render(f, color, null, null);
                ctx.render(f, color, null, null);
                images[v] = color;
                if (v == 0) {
                    // the same far view at night: the town's windows, lamps and fires
                    double ne = Math.toRadians(-18);
                    f.sunX = (float) (Math.sin(sa) * Math.cos(ne));
                    f.sunY = (float) Math.sin(ne);
                    f.sunZ = (float) (-Math.cos(sa) * Math.cos(ne));
                    int[] night = new int[w * h];
                    ctx.render(f, night, null, null);
                    ctx.render(f, night, null, null);
                    images[3] = night;
                    f.sunX = (float) (Math.sin(sa) * Math.cos(se));
                    f.sunY = (float) Math.sin(se);
                    f.sunZ = (float) (-Math.cos(sa) * Math.cos(se));
                }
                if (v == 1) {
                    int[] c2 = new int[w * h];
                    bare.render(f, c2, null, null);
                    bare.render(f, c2, null, null);
                    images[2] = c2;
                    for (int p = 0; p < w * h; p++) if (color[p] != c2[p]) buildingPx[0]++;
                }
            }
            counters[0] = towns.captured;
            counters[1] = towns.profiled;
            counters[2] = towns.siteShapes;
            counters[3] = towns.lightCount;
            ms[0] = towns.captureMs;
            ms[1] = towns.siteMs;
            ms[2] = towns.massingMs;
            for (String why : towns.fallbacks) OUT.println("  profile: " + why);
            src.close();
            src2.close();
        }
        OUT.printf("buildings change %.1f%% of the mid view's pixels%n", 100.0 * buildingPx[0] / (w * h));
        OUT.printf("landmarks: %d captured from their builders, %d as profiles; %d wild site silhouettes; %d far lights%n",
                counters[0], counters[1], counters[2], counters[3]);
        OUT.printf("cost: landmark captures %.0f ms, site silhouettes %.0f ms, settlement massing in all %.0f ms%n", ms[0], ms[1], ms[2]);
        if (buildingPx[0] < w * h / 200 && !place.contains(":")) throw new IllegalStateException("no buildings visible in the mid view");
        BufferedImage img = new BufferedImage(w, h * 4 + 24, BufferedImage.TYPE_INT_RGB);
        for (int k = 0; k < 4; k++)
            for (int p = 0; p < w * h; p++) {
                int c = images[k][p];
                img.setRGB(p % w, k * (h + 8) + p / w, (c & 0xFF) << 16 | (c & 0xFF00) | (c >>> 16 & 0xFF));
            }
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(img, "png", out.toFile());
        OUT.println("wrote " + out + " (far, mid, mid without buildings, far at night)");
    }
}
