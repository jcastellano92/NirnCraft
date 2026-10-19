package com.nirncraft.worldplan.street;

import com.nirncraft.world.PlacementMask;

/**
 * The street plan as a {@link PlacementMask.Source} (P5): every plot (buildings, walls, gatehouses, yards, fields,
 * plazas) and every street, gate lane and doorstep path of the named settlements, so trees, ground cover, rocks and
 * scatter never grow where a house, a yard or a walk will be, whether or not the region already holds the settlement's
 * structure start. Allocation-free, no neighbour chunk needed.
 */
final class StreetMask implements PlacementMask.Source {
    /** Verge kept clear round a plot or beside a walk (blocks). */
    static final float VERGE = 1.0f;

    @Override
    public void contribute(long worldSeed, int chunkX, int chunkZ, byte[] out) {
        StreetPlan plan = StreetPlan.get();
        int k = plan.bucket(chunkX, chunkZ);
        if (k < 0) return;
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        for (int j = plan.plotFrom(k), end = plan.plotTo(k); j < end; j++) {
            int p = plan.plotAt(j);
            int ax = plan.pMinX[p], bx = plan.pMaxX[p], az = plan.pMinZ[p], bz = plan.pMaxZ[p];
            if (ax - x0 > 15 + PlacementMask.CAP || x0 - bx > PlacementMask.CAP || az - z0 > 15 + PlacementMask.CAP || z0 - bz > PlacementMask.CAP) continue;
            for (int dz = 0; dz < 16; dz++) {
                int z = z0 + dz;
                int oz = z < az ? az - z : z > bz ? z - bz : 0;
                for (int dx = 0; dx < 16; dx++) {
                    int x = x0 + dx;
                    int ox = x < ax ? ax - x : x > bx ? x - bx : 0;
                    PlacementMask.lower(out, dz << 4 | dx, Math.max(ox, oz) - VERGE);
                }
            }
        }
        for (int j = plan.edgeFrom(k), end = plan.edgeTo(k); j < end; j++) {
            int e = plan.edgeAt(j);
            float ax = plan.nx[plan.ea[e]], az = plan.nz[plan.ea[e]];
            float ddx = plan.nx[plan.eb[e]] - ax, ddz = plan.nz[plan.eb[e]] - az;
            float l2 = ddx * ddx + ddz * ddz, half = plan.ew[e] / 2f;
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    float px = x0 + dx + 0.5f - ax, pz = z0 + dz + 0.5f - az;
                    float t = l2 < 1e-6f ? 0f : Math.max(0f, Math.min(1f, (px * ddx + pz * ddz) / l2));
                    float qx = px - t * ddx, qz = pz - t * ddz;
                    PlacementMask.lower(out, dz << 4 | dx, (float) Math.sqrt(qx * qx + qz * qz) - half - VERGE);
                }
            }
        }
    }
}
