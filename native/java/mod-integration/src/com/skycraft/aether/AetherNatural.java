package com.skycraft.aether;

// aether:sandbox-only (compiled against the real mod by sandbox_check.ps1, not by compile_check.ps1's stubs)

import com.skycraft.landmarks.H;
import com.skycraft.landmarks.Kind;
import com.skycraft.landmarks.Kinds;
import com.skycraft.landmarks.LandmarkSite;
import com.skycraft.landmarks.Landmarks;
import com.skycraft.landmarks.Tier;
import com.skycraft.network.SkyNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntBinaryOperator;

/**
 * The seeded natural landmarks (com.skycraft.landmarks: colossal trees, fungal and crystal spires, giant ribcages,
 * stone arches, mesas, glacier towers, menhirs, hoodoos ...) in the far field. Where they stand depends on the world
 * seed, which never leaves the server, so the server enumerates them around each player (Landmarks.inRect: XL out to
 * 16 km, the tall L kinds out to 6 km) and sends them (AetherNet.FarSites; the integrated server does the same for its
 * own player), and the client draws each from its builder's dimensions (build/Giants, Formations: the seeded size
 * factor H.between(seed, 11, 0.75, 1.3), the site's facing), standing on the far ground. A site's random details
 * (satellite spires, roots, the lean of a shaft) are left out: the silhouette is the massing.
 */
final class AetherNatural {
    private AetherNatural() {}

    static final int XL_RANGE = 16_000, L_RANGE = 6_000, RESEND = 1_500;
    /** L builders tall enough to read from afar (the rest are under about ten blocks). */
    private static final java.util.Set<String> TALL_L = java.util.Set.of("rock_arch", "menhir", "hoodoos", "split_boulder",
            "tower_ruin", "cliff_nests", "petrified_tree", "ice_fall", "columns", "sea_stacks", "spikes", "fins", "butte", "statue");

    // ---- server: who gets which sites ---------------------------------------------------------------------------

    private static final Map<UUID, long[]> sentAt = new HashMap<>();
    private static ExecutorService worker;

    /** ServerTickEvent: every 5 s, players that moved RESEND blocks since their last list get a fresh one. */
    static void tick(MinecraftServer server) {
        if (server.getTickCount() % 100 != 0) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        long seed = level.getSeed();
        for (ServerPlayer p : level.players()) {
            long[] at = sentAt.get(p.getUUID());
            int px = p.getBlockX(), pz = p.getBlockZ();
            if (at != null && Math.abs(at[0] - px) < RESEND && Math.abs(at[1] - pz) < RESEND) continue;
            sentAt.put(p.getUUID(), new long[] {px, pz});
            if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "skycraft-aether-sites");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            UUID id = p.getUUID();
            worker.execute(() -> {
                try {
                    AetherNet.FarSites m = enumerate(seed, px, pz);
                    server.execute(() -> {
                        ServerPlayer sp = server.getPlayerList().getPlayer(id);
                        if (sp != null && !sp.hasDisconnected()) SkyNetwork.sendToPlayer(sp, m);
                    });
                } catch (RuntimeException e) {
                    com.skycraft.Skycraft.LOGGER.warn("aether far sites: {}", e.toString());
                }
            });
        }
        sentAt.keySet().removeIf(u -> server.getPlayerList().getPlayer(u) == null);
    }

    static void stop() {
        sentAt.clear();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    /** The XL sites out to XL_RANGE and the tall L ones out to L_RANGE around (x, z), nearest first, capped. */
    static AetherNet.FarSites enumerate(long seed, int x, int z) {
        List<LandmarkSite> out = new ArrayList<>();
        for (LandmarkSite s : Landmarks.inRect(seed, x - XL_RANGE, z - XL_RANGE, x + XL_RANGE, z + XL_RANGE)) {
            if (s.tier() == Tier.XL) out.add(s);
            else if (s.tier() == Tier.L && TALL_L.contains(s.kind().builder())
                    && Math.abs(s.x() - x) <= L_RANGE && Math.abs(s.z() - z) <= L_RANGE) out.add(s);
        }
        out.sort((a, b) -> Long.compare((long) (a.x() - x) * (a.x() - x) + (long) (a.z() - z) * (a.z() - z),
                (long) (b.x() - x) * (b.x() - x) + (long) (b.z() - z) * (b.z() - z)));
        int n = Math.min(out.size(), AetherNet.MAX_SITES);
        int[] xz = new int[n * 2];
        long[] seeds = new long[n];
        byte[] facing = new byte[n];
        String[] kinds = new String[n];
        for (int i = 0; i < n; i++) {
            LandmarkSite s = out.get(i);
            xz[i * 2] = s.x();
            xz[i * 2 + 1] = s.z();
            seeds[i] = s.seed();
            facing[i] = (byte) s.facing();
            kinds[i] = s.kind().id();
        }
        return new AetherNet.FarSites(n, xz, kinds, seeds, facing);
    }

    // ---- client: the silhouettes ----------------------------------------------------------------------------------

    private static BlockState b(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }

    /** The silhouette of site i of a FarSites list, or null for a kind with nothing tall. */
    static AetherCapture.Shape shape(AetherNet.FarSites m, int i, IntBinaryOperator g) {
        Kind kind = Kinds.byId(m.kinds()[i]);
        if (kind == null) return null;
        int x = m.xz()[i * 2], z = m.xz()[i * 2 + 1];
        long seed = m.seeds()[i];
        double sz = H.between(seed, 11, 0.75, 1.3);
        int f = m.facing()[i];
        int base = g.applyAsInt(x, z) + 1;
        String id = kind.id(), builder = kind.builder();
        boolean ice = id.contains("ice") || id.contains("glacier") || id.contains("frost");
        BlockState rock = ice ? b(Blocks.PACKED_ICE) : id.contains("obsidian") ? b(Blocks.OBSIDIAN)
                : id.contains("crystal") ? b(Blocks.CALCITE) : id.contains("sand") || id.contains("mesa") ? b(Blocks.SANDSTONE) : b(Blocks.STONE);
        AetherCapture.Shape s = AetherCapture.Shape.around(x, z, 30);
        double cx = x + 0.5, cz = z + 0.5;
        switch (builder) {
            case "colossal_tree" -> {
                int h = 52 + (int) (18 * sz);
                double r0 = 5 + 1.5 * sz;
                boolean conifer = id.contains("tundra"), dead = id.contains("dead");
                BlockState wood = id.contains("jungle") ? b(Blocks.JUNGLE_LOG) : id.contains("savanna") ? b(Blocks.ACACIA_LOG)
                        : conifer ? b(Blocks.SPRUCE_LOG) : id.contains("marsh") ? b(Blocks.MANGROVE_LOG) : b(Blocks.DARK_OAK_LOG);
                BlockState leaves = id.contains("jungle") ? b(Blocks.JUNGLE_LEAVES) : id.contains("savanna") ? b(Blocks.ACACIA_LEAVES)
                        : conifer ? b(Blocks.SPRUCE_LEAVES) : id.contains("crystal") ? b(Blocks.CHERRY_LEAVES)
                        : id.contains("marsh") ? b(Blocks.MANGROVE_LEAVES) : b(Blocks.DARK_OAK_LEAVES);
                if (!dead) {
                    double[] radii = conifer ? new double[] {6, 10.5, 15, 19.5} : new double[] {9, 17, 15, 15};
                    for (int L = 0; L < 4; L++)
                        s.ellipsoid(cx, base + h - 6 - 6 * L, cz, Math.min(20.5, radii[L]), 4, Math.min(20.5, radii[L]), true, leaves, leaves);
                }
                s.profile(cx, cz, base - 1, h, dy -> r0 * (1 - 0.45 * dy / h) + (dy < 8 ? (8 - dy) * 0.4 : 0), wood, wood);
            }
            case "fungal_spire" -> {
                int h = 38 + (int) (12 * sz);
                double r0 = 4 + sz;
                BlockState cap = (seed & 0xFF) < 102 ? b(Blocks.RED_MUSHROOM_BLOCK) : b(Blocks.BROWN_MUSHROOM_BLOCK);
                double[][] caps = {{h * 0.35, Math.min(20, 13 + 2 * sz)}, {h * 0.62, Math.min(19, 10 + sz)}, {h - 2, 7}};
                for (double[] c : caps) {
                    double r = c[1];
                    for (int dz = (int) -Math.ceil(r); dz <= Math.ceil(r); dz++)
                        for (int dx = (int) -Math.ceil(r); dx <= Math.ceil(r); dx++) {
                            double q = 1 - (dx * dx + dz * dz) / (r * r);
                            if (q >= 0) s.span(x + dx, z + dz, (float) (base + c[0]), (float) (base + c[0] + 1 + 0.45 * r * Math.sqrt(q)), cap, cap);
                        }
                }
                s.profile(cx, cz, base - 1, h, dy -> r0 * (1 - 0.35 * dy / h) + (dy < 4 ? 3 - dy * 0.75 : 0),
                        b(Blocks.MUSHROOM_STEM), b(Blocks.MUSHROOM_STEM));
            }
            case "ribcage" -> {
                boolean lev = id.contains("leviathan");
                BlockState bone = b(Blocks.BONE_BLOCK);
                for (int lx = -19; lx <= 19; lx++) span(s, x, z, f, lx, 0, base, Float.NaN, base + 4, bone);
                for (int lx = -14; lx <= 13; lx += 3) {
                    int rh = (int) Math.round(10 + (lev ? 15 : 13) * Math.sin((lx + 19) * Math.PI / 38));
                    double top = 0.95 * rh - 2, hw = 0.75 * rh;
                    for (int lz = (int) -Math.ceil(hw); lz <= Math.ceil(hw); lz++) {
                        double u = lz / hw, q = 1 - u * u;
                        if (q < 0) continue;
                        float t = (float) (base + top * Math.sqrt(q));
                        span(s, x, z, f, lx, lz, base, Math.abs(u) > 0.8 ? Float.NaN : t - 2, t + 1, bone);
                    }
                }
            }
            case "bridge_arch", "rock_arch" -> {
                boolean xl = builder.equals("bridge_arch"), twin = id.contains("twin");
                double half = xl ? 13 + 3 * sz : (5 + 4 * sz) * (twin ? 0.85 : 1), h = xl ? 16 + 5 * sz : 6 + 5 * sz;
                double thick = xl ? 3.5 + sz : 2.2 + 1.6 * sz, depth = xl ? 8 + 2 * sz : 6 + 4 * sz;
                arch(s, x, z, f, 0, 0, half, h, thick, depth, base, rock);
                if (twin) arch(s, x, z, f, 3, 9, half * 0.6, h * 0.6, thick * 0.6, depth * 0.6, base, rock);
            }
            case "spire_stack", "obsidian_spire", "crystal_spire", "glacier_tower", "spire_twist" -> {
                int h;
                java.util.function.DoubleUnaryOperator r;
                switch (builder) {
                    case "spire_stack" -> {
                        h = 46 + (int) (14 * sz);
                        double r0 = 6.2 + sz;
                        int hh = h;
                        r = dy -> r0 + (1.1 - r0) * dy / hh;
                    }
                    case "obsidian_spire" -> {
                        h = 44 + (int) (12 * sz);
                        int hh = h;
                        r = dy -> 7.5 * Math.pow(1 - dy / (double) hh, 0.7) + 0.8;
                    }
                    case "crystal_spire" -> {
                        h = 48 + (int) (14 * sz);
                        int hh = h;
                        r = dy -> {
                            double t = dy / (double) hh;
                            return t < 0.82 ? 4.5 * (1 - 0.18 * t) : 4.5 * 0.85 * (1 - (t - 0.82) / 0.18);
                        };
                    }
                    case "glacier_tower" -> {
                        h = 42 + (int) (12 * sz);
                        int hh = h;
                        r = dy -> 9 * (1 - 0.65 * dy / hh) + 2 * Math.sin(0.35 * dy);
                    }
                    default -> {
                        h = 44 + (int) (14 * sz);
                        int hh = h;
                        r = dy -> 7.8 + (1.8 - 7.8) * dy / hh;
                    }
                }
                s.profile(cx, cz, base - 1, h, r, rock, rock);
                // the satellites / fangs / spikes ringing the main shaft
                int ring = builder.equals("glacier_tower") ? 9 : builder.equals("obsidian_spire") ? 8 : 5;
                double rr = builder.equals("glacier_tower") ? 14 : builder.equals("obsidian_spire") ? 15 : 11;
                int sh = builder.equals("spire_stack") ? 25 : builder.equals("crystal_spire") ? 22 : 12;
                for (int k = 0; k < ring; k++) {
                    double a = k * 2 * Math.PI / ring + H.between(seed, 20, 0, 1);
                    double sx = cx + rr * Math.cos(a), szz = cz + rr * Math.sin(a);
                    s.profile(sx, szz, base - 1, sh, dy -> 3.0 * (1 - dy / (double) sh) + 0.6, rock, rock);
                }
            }
            case "antler_spires" -> {
                int h = 46 + (int) (10 * sz);
                boolean broken = H.between(seed, 21, 0, 1) < 0.7;
                for (int side = -1; side <= 1; side += 2) {
                    int hh = side > 0 && broken ? (int) (0.55 * h) : h;
                    int ox = wx(x, f, side * 7, 0), oz = wz(z, f, side * 7, 0);
                    s.profile(ox + 0.5, oz + 0.5, base - 1, hh, dy -> 4.2 + (1.4 - 4.2) * dy / (double) h, rock, rock);
                }
            }
            case "crater_lake" -> {
                double r = 15 + 3 * sz;
                for (int dz = -22; dz <= 22; dz++)
                    for (int dx = -22; dx <= 22; dx++) {
                        double d = Math.hypot(dx, dz);
                        if (d < r - 1.5 || d > Math.min(22, r + 5)) continue;
                        double u = (d - (r - 1.5)) / 6.5;
                        s.span(x + dx, z + dz, Float.NaN, base + (float) Math.round(7 * Math.sin(Math.min(1, u) * 0.85 * Math.PI) + 1), rock, rock);
                    }
            }
            case "mesa_path", "butte" -> {
                boolean mesa = builder.equals("mesa_path");
                double r = mesa ? 15 + 3 * sz : 6 + 3 * sz;
                int h = mesa ? 22 + (int) (8 * sz) : 8 + (int) (6 * sz);
                double skirt = mesa ? 4 : 3;
                s.profile(cx, cz, base - 1, h + 1, dy -> dy <= h * 0.38 ? r + skirt * (1 - dy / (h * 0.38)) : r, rock, rock);
            }
            case "menhir", "petrified_tree" -> {
                boolean tree = builder.equals("petrified_tree");
                int h = tree ? 11 + (int) (6 * sz) : 12 + (int) (7 * sz);
                if (!tree && id.contains("broken")) h = (int) (h * 0.62);
                double r0 = tree ? 2.2 + 0.9 * sz : 2.4 + 0.8 * sz, k = tree ? 0.45 : 0.55;
                int hh = h;
                s.profile(cx, cz, base - 1, h, dy -> r0 * (1 - (1 - k) * dy / hh), rock, rock);
            }
            case "split_boulder" -> s.ellipsoid(cx, base + 0.7 * (5 + 3 * sz), cz, 6 + 3 * sz, 5 + 3 * sz, 5 + 2 * sz, false, rock, rock);
            case "statue" -> s.ellipsoid(cx, base + 0.55 * (4.5 + sz), cz, 4 + sz, 4.5 + sz, 4 + sz, false, rock, rock);
            case "tower_ruin" -> {
                double r = 3.8 + 1.8 * sz;
                int h = 10 + (int) (7 * sz);
                s.ring(cx, cz, r - 0.7, r + 0.7, base + (float) (0.8 * h), b(Blocks.STONE_BRICKS), b(Blocks.MOSSY_STONE_BRICKS));
            }
            case "ice_fall" -> {
                int h = 10 + (int) (5 * sz);
                for (int lx = -8; lx <= 8; lx++)
                    for (int lz = 0; lz <= 9; lz++) span(s, x, z, f, lx, lz, base, Float.NaN, base + h, b(Blocks.PACKED_ICE));
            }
            case "hoodoos", "sea_stacks", "cliff_nests", "spikes", "columns", "fins" -> {
                // a cluster of shafts (their count and spots are seeded detail: a hashed stand-in of the same size)
                int count = builder.equals("hoodoos") ? 9 : builder.equals("spikes") ? 8 : builder.equals("fins") ? 4 : builder.equals("columns") ? 7 : 4;
                double spread = builder.equals("sea_stacks") ? 12 : builder.equals("cliff_nests") ? 6 : 9;
                int h = builder.equals("spikes") ? 11 : builder.equals("columns") ? 10 : 13;
                double r = builder.equals("spikes") ? 1.6 : builder.equals("fins") ? 1.2 : 2.6;
                for (int k = 0; k < count; k++) {
                    double a = H.between(seed, 30 + k, 0, 2 * Math.PI), d = H.between(seed, 50 + k, 2, spread);
                    int hk = (int) Math.round(h * H.between(seed, 70 + k, 0.6, 1.2));
                    s.profile(cx + d * Math.cos(a), cz + d * Math.sin(a), base - 1, hk, dy -> r * (1 - 0.5 * dy / hk) + 0.4, rock, rock);
                }
            }
            default -> {
                return null;
            }
        }
        float rise = 0;
        for (float t : s.top) if (!Float.isNaN(t)) rise = Math.max(rise, t - base);
        s.rise = rise;
        return s.empty() ? null : s;
    }

    /** An arch along the site's local x (local +z = facing, SiteBuilder's frame), centred at local (ox, oz). */
    private static void arch(AetherCapture.Shape s, int x, int z, int f, int ox, int oz, double half, double h, double thick,
                             double depth, int base, BlockState rock) {
        double ho = half + thick, vo = h + thick;
        for (int lx = (int) -Math.ceil(ho); lx <= Math.ceil(ho); lx++) {
            double uo = lx / ho;
            if (Math.abs(uo) > 1) continue;
            float top = (float) (base + vo * Math.sqrt(1 - uo * uo));
            float under = Math.abs(lx) < half ? (float) (base + h * Math.sqrt(1 - (lx / half) * (lx / half))) : Float.NaN;
            for (int lz = (int) -Math.ceil(depth / 2); lz <= Math.ceil(depth / 2); lz++)
                span(s, x, z, f, lx + ox, lz + oz, base, under, top, rock);
        }
    }

    /** A span at local (lx, lz) of a site facing f (2D data value: 0 S, 1 W, 2 N, 3 E). */
    private static void span(AetherCapture.Shape s, int x, int z, int f, int lx, int lz, int base, float bottom, float top, BlockState b) {
        s.span(wx(x, f, lx, lz), wz(z, f, lx, lz), bottom, top, b, b);
    }

    /** SiteBuilder's local frame: SOUTH (cx+dx, cz+dz), WEST (cx-dz, cz+dx), NORTH (cx-dx, cz-dz), EAST (cx+dz, cz-dx). */
    private static int wx(int cx, int f, int dx, int dz) {
        return switch (f & 3) {
            case 1 -> cx - dz;
            case 2 -> cx - dx;
            case 3 -> cx + dz;
            default -> cx + dx;
        };
    }

    private static int wz(int cz, int f, int dx, int dz) {
        return switch (f & 3) {
            case 1 -> cz + dx;
            case 2 -> cz - dz;
            case 3 -> cz - dx;
            default -> cz + dz;
        };
    }
}
