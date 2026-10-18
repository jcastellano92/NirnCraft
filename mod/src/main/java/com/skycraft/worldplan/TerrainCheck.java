package com.skycraft.worldplan;

import com.skycraft.tamriel.TamrielMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Dev harness for P2 (autorun task {@code roadterrain[:samples]}): compares the generator's real column heights with
 * the plan shaping off (this thread only, see {@link PlanGround#setMode}) and on. Reports the micro benchmark of the
 * plan lookups, the share of changed columns and how far from a road / pad they are, the terrain error against the
 * profile along the roads, and the histograms of the old ground against the bed (the cut/fill depth).
 */
final class TerrainCheck {
    private TerrainCheck() {}

    static String run(ServerLevel level, int samples) {
        RoadPlan plan = RoadPlan.get();
        PlanGround ground = PlanGround.get();
        PlanGround.Bed bed = PlanGround.scratch();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        SplittableRandom r = new SplittableRandom(20260);
        StringBuilder out = new StringBuilder();

        // ---- 1. micro benchmark of the plan lookups (bed + pad), random columns and columns near roads
        int n = 1_000_000;
        int[] xs = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = TamrielMap.MIN_X + r.nextInt(TamrielMap.MAX_X - TamrielMap.MIN_X);
            zs[i] = TamrielMap.MIN_Z + r.nextInt(TamrielMap.MAX_Z - TamrielMap.MIN_Z);
        }
        double sink = 0;
        for (int i = 0; i < 200_000; i++) sink += ground.bed(xs[i], zs[i], bed) ? bed.weight : 0;
        long t0 = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < n; i++) {
            if (ground.bed(xs[i], zs[i], bed)) hits++;
            if (ground.pad(xs[i], zs[i], bed)) sink += bed.padWeight;
        }
        double nsRandom = (System.nanoTime() - t0) / (double) n;
        for (int i = 0; i < n; i++) {
            int v = r.nextInt(plan.vertices());
            xs[i] = (int) plan.vx[v] + r.nextInt(41) - 20;
            zs[i] = (int) plan.vz[v] + r.nextInt(41) - 20;
        }
        t0 = System.nanoTime();
        int near = 0;
        for (int i = 0; i < n; i++) {
            if (ground.bed(xs[i], zs[i], bed)) near++;
            if (ground.pad(xs[i], zs[i], bed)) sink += bed.padWeight;
        }
        double nsNear = (System.nanoTime() - t0) / (double) n;
        out.append(String.format(Locale.ROOT, "plan lookup (bed+pad): %.0f ns/column over random Tamriel columns (%.1f%% shaped), %.0f ns/column within 20 blocks of a road (%.0f%% shaped)%s\n",
                nsRandom, 100.0 * hits / n, nsNear, 100.0 * near / n, sink == 42 ? " " : ""));

        // ---- 2. changed columns: random columns, columns near road vertices, columns near padded sites
        int[] cx = new int[samples], cz = new int[samples];
        for (int i = 0; i < samples; i++) {
            int kind = i % 3;
            if (kind == 0) {
                cx[i] = TamrielMap.MIN_X + r.nextInt(TamrielMap.MAX_X - TamrielMap.MIN_X);
                cz[i] = TamrielMap.MIN_Z + r.nextInt(TamrielMap.MAX_Z - TamrielMap.MIN_Z);
            } else if (kind == 1) {
                int v = r.nextInt(plan.vertices());
                cx[i] = (int) plan.vx[v] + r.nextInt(81) - 40;
                cz[i] = (int) plan.vz[v] + r.nextInt(81) - 40;
            } else {
                int s = r.nextInt(plan.sites());
                int reach = plan.siteRadius[s] + 40;
                cx[i] = plan.siteX[s] + r.nextInt(2 * reach + 1) - reach;
                cz[i] = plan.siteZ[s] + r.nextInt(2 * reach + 1) - reach;
            }
        }
        int[] before = new int[samples], after = new int[samples];
        long tOff, tOn;
        PlanGround.setMode(PlanGround.MODE_OLD);
        try {
            long a = System.nanoTime();
            for (int i = 0; i < samples; i++) before[i] = gen.getBaseHeight(cx[i], cz[i], Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            tOff = System.nanoTime() - a;
        } finally {
            PlanGround.setMode(PlanGround.MODE_PLAN);
        }
        long a = System.nanoTime();
        for (int i = 0; i < samples; i++) after[i] = gen.getBaseHeight(cx[i], cz[i], Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        tOn = System.nanoTime() - a;
        int changed = 0, far = 0, far22 = 0, near20 = 0, uniform = 0, uniformChanged = 0;
        double maxFar = 0;
        StringBuilder farList = new StringBuilder();
        RoadPlan.Hit hit = new RoadPlan.Hit();
        for (int i = 0; i < samples; i++) {
            double dRoad = plan.at(cx[i], cz[i], hit) ? hit.distance : 1e9;
            double dPad = ground.padDistance(cx[i], cz[i]);
            double d = Math.min(dRoad, dPad);
            if (d <= 20) near20++;
            if (i % 3 == 0) {
                uniform++;
                if (before[i] != after[i]) uniformChanged++;
            }
            if (before[i] == after[i]) continue;
            changed++;
            if (d > 20) far++;
            if (d > 22) {
                far22++;
                if (far22 <= 6) farList.append(String.format(Locale.ROOT, "(%d, %d) d %.0f %d->%d; ", cx[i], cz[i], d, before[i] - 1, after[i] - 1));
            }
            if (d > maxFar) maxFar = d;
        }
        out.append(String.format(Locale.ROOT, "uniformly random Tamriel columns: %d, changed %d (%.2f%%)\n", uniform, uniformChanged,
                100.0 * uniformChanged / Math.max(1, uniform)));
        // determinism: the same columns again, both modes (any difference is generation noise, not the plan)
        int redo = 0, redoOldDiff = 0, redoNewDiff = 0;
        for (int i = 0; i < samples && redo < 3000; i += 3) {
            redo++;
            if (top(gen, level, rs, cx[i], cz[i], PlanGround.MODE_OLD) != before[i] - 1) redoOldDiff++;
            if (top(gen, level, rs, cx[i], cz[i], PlanGround.MODE_PLAN) != after[i] - 1) redoNewDiff++;
        }
        out.append(String.format(Locale.ROOT, "repeat of %d uniform columns: old terrain differs %d, plan terrain differs %d\n",
                redo, redoOldDiff, redoNewDiff));
        out.append(String.format(Locale.ROOT, "columns: %d sampled (1/3 random, 1/3 within 40 of a road vertex, 1/3 around pads), %d changed (%.1f%%); of those %d farther than 20 blocks from every road centre line / pad edge (%d beyond 22; max %.1f: %s); %d sampled columns lie within 20 blocks of a road or pad; getBaseHeight %.0f us old, %.0f us new per column\n",
                samples, changed, 100.0 * changed / samples, far, far22, maxFar, farList, near20, tOff / 1e3 / samples, tOn / 1e3 / samples));

        // ---- 3. terrain vs profile along the roads and the cut/fill histograms
        int m = Math.min(samples, 20000);
        // buckets of |ground top - bed| : 0, 1, 2, 3-4, 5-8, 9-12, >12
        int[][] histOld = new int[RoadPlan.SPAN_NAMES.length][7], histCalm = new int[RoadPlan.SPAN_NAMES.length][7];
        int[] err = new int[6]; // |top - floor(profile)|: 0, 1, 2, 3-4, 5+ ; [5] = columns checked
        int[][] errByKind = new int[4][3]; // exact, off by 1, worse
        int worstShown = 0;
        for (int i = 0; i < m; i++) {
            int v = r.nextInt(plan.vertices());
            int s = plan.vseg[v];
            byte span = plan.vspan[v];
            if (span == RoadPlan.BRIDGE || span == RoadPlan.TUNNEL || span == RoadPlan.CULVERT) continue;
            float hw = plan.segHalfWidth[s];
            int x = (int) Math.floor(plan.vx[v] + (r.nextDouble() * 2 - 1) * hw);
            int z = (int) Math.floor(plan.vz[v] + (r.nextDouble() * 2 - 1) * hw);
            if (!ground.bed(x, z, bed) || !bed.inBed || bed.span == RoadPlan.BRIDGE || bed.span == RoadPlan.TUNNEL
                    || bed.span == RoadPlan.CULVERT) continue;
            int want = (int) Math.floor(bed.profile);
            byte bs = bed.span;
            int kd = Math.min(3, bed.kind);
            float bProfile = bed.profile, bDist = bed.distance, bW = bed.weight;
            int top = top(gen, level, rs, x, z, PlanGround.MODE_PLAN);
            int old = top(gen, level, rs, x, z, PlanGround.MODE_OLD);
            int calm = top(gen, level, rs, x, z, PlanGround.MODE_CALM_ONLY);
            int e = Math.abs(top - want);
            err[5]++;
            err[e == 0 ? 0 : e == 1 ? 1 : e == 2 ? 2 : e <= 4 ? 3 : 4]++;
            errByKind[kd][e == 0 ? 0 : e == 1 ? 1 : 2]++;
            if (e >= 3 && worstShown++ < 8) {
                com.skycraft.tamriel.MapSample ms = new com.skycraft.tamriel.MapSample();
                TamrielMap.sample(x, z, ms);
                StringBuilder col = new StringBuilder();
                net.minecraft.world.level.NoiseColumn nc = gen.getBaseColumn(x, z, level, rs);
                for (int y = Math.min(70, (int) bProfile + 3); y >= Math.max(40, top - 6); y--) {
                    col.append(y).append(':').append(nc.getBlock(y).getBlock().builtInRegistryHolder().key().identifier().getPath()).append(' ');
                }
                StringBuilder cor = new StringBuilder();
                int lx = x & ~3, lz = z & ~3;
                for (int oz = 0; oz <= 4; oz += 4) {
                    for (int ox = 0; ox <= 4; ox += 4) {
                        boolean hb = ground.bed(lx + ox, lz + oz, bed);
                        float cw = hb ? bed.weight : 0f, cdist = hb ? bed.distance : -1f;
                        String cs = hb ? RoadPlan.SPAN_NAMES[bed.span] : "-";
                        int ct = top(gen, level, rs, lx + ox, lz + oz, PlanGround.MODE_PLAN);
                        cor.append(String.format(Locale.ROOT, "[%d,%d top %d w %.2f d %.1f %s] ", lx + ox - x, lz + oz - z, ct, cw, cdist, cs));
                    }
                }
                out.append("    corners ").append(cor).append('\n');
                out.append(String.format(Locale.ROOT, "  off by %d at (%d, %d): top %d, old %d, want %.2f, %s %s, %.1f from the line, weight %.2f; map elev %.1f cd %.0f water %.1f river %.1f/%.1f lake %.1f wet %.2f road %.2f; %s\n",
                        e, x, z, top, old, bProfile, RoadPlan.CLASS_NAMES[kd], RoadPlan.SPAN_NAMES[bs], bDist, bW, ms.elevation,
                        ms.coastDistance, ms.waterLevel, ms.riverDistance, ms.riverHalfWidth, ms.lakeDistance, ms.wetland, ms.road, col));
            }
            int o = Math.abs(old - want), c = Math.abs(calm - want);
            histOld[bs][o == 0 ? 0 : o == 1 ? 1 : o == 2 ? 2 : o <= 4 ? 3 : o <= 8 ? 4 : o <= 12 ? 5 : 6]++;
            histCalm[bs][c == 0 ? 0 : c == 1 ? 1 : c == 2 ? 2 : c <= 4 ? 3 : c <= 8 ? 4 : c <= 12 ? 5 : 6]++;
        }
        // consecutive centre-line vertices (4 blocks apart): the step the road would have to climb
        int pairs = 0, step2 = 0, step3 = 0;
        for (int i = 0; i < Math.min(samples, 6000); i++) {
            int v = r.nextInt(plan.vertices() - 1);
            int s = plan.vseg[v];
            if (plan.vseg[v + 1] != s) continue;
            byte a0 = plan.vspan[v], a1 = plan.vspan[v + 1];
            if (a0 == RoadPlan.BRIDGE || a0 == RoadPlan.TUNNEL || a0 == RoadPlan.CULVERT || a0 == RoadPlan.STAIRS
                    || a1 == RoadPlan.BRIDGE || a1 == RoadPlan.TUNNEL || a1 == RoadPlan.CULVERT || a1 == RoadPlan.STAIRS) continue;
            int ta = top(gen, level, rs, (int) Math.floor(plan.vx[v]), (int) Math.floor(plan.vz[v]), PlanGround.MODE_PLAN);
            int tb = top(gen, level, rs, (int) Math.floor(plan.vx[v + 1]), (int) Math.floor(plan.vz[v + 1]), PlanGround.MODE_PLAN);
            pairs++;
            if (Math.abs(tb - ta) > 1) step2++;
            if (Math.abs(tb - ta) > 3) step3++;
        }
        out.append(String.format(Locale.ROOT, "consecutive centre-line vertices (4 blocks apart, no bridge/tunnel/culvert/stairs): %d pairs, terrain differs by more than 1 block: %d (%.1f%%), by more than 3: %d\n",
                pairs, step2, 100.0 * step2 / Math.max(1, pairs), step3));
        out.append(String.format(Locale.ROOT, "terrain vs profile on %d bed columns (top solid block vs floor(profile)): exact %d, off 1: %d, off 2: %d, off 3-4: %d, off 5+: %d\n",
                err[5], err[0], err[1], err[2], err[3], err[4]));
        for (int k = 0; k < 4; k++) {
            out.append(String.format(Locale.ROOT, "  %s: exact %d, off 1: %d, worse %d\n", RoadPlan.CLASS_NAMES[k],
                    errByKind[k][0], errByKind[k][1], errByKind[k][2]));
        }
        out.append("cut/fill depth |ground - bed| per span (0 / 1 / 2 / 3-4 / 5-8 / 9-12 / >12 blocks):\n");
        for (int k = 0; k < histOld.length; k++) {
            int tot = 0;
            for (int c : histOld[k]) tot += c;
            if (tot == 0) continue;
            out.append(String.format(Locale.ROOT, "  %-10s n=%d  old terrain: %d / %d / %d / %d / %d / %d / %d   detail-calmed: %d / %d / %d / %d / %d / %d / %d\n",
                    RoadPlan.SPAN_NAMES[k], tot, histOld[k][0], histOld[k][1], histOld[k][2], histOld[k][3], histOld[k][4],
                    histOld[k][5], histOld[k][6], histCalm[k][0], histCalm[k][1], histCalm[k][2], histCalm[k][3],
                    histCalm[k][4], histCalm[k][5], histCalm[k][6]));
        }
        return out.toString();
    }

    /** The top solid block of the column in a harness mode. */
    private static int top(ChunkGenerator gen, ServerLevel level, RandomState rs, int x, int z, int mode) {
        PlanGround.setMode(mode);
        try {
            return gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
        } finally {
            PlanGround.setMode(PlanGround.MODE_PLAN);
        }
    }
}
