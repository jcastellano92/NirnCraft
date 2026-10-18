package com.skycraft.aether;

import net.minecraft.client.Minecraft;

import java.io.IOException;

/**
 * Streams far-field column tiles around the camera on its own thread and rebuilds aether's clipmap there, so the
 * render thread only ever flips to finished GPU copies (aether stages each scene revision in aether_update).
 */
final class AetherFarField implements AutoCloseable {
    /** Where far-field columns come from. */
    interface ColumnSource {
        /** True when tiles carry block palette ids (AetherPalette.blocks), false for aether's preview palette. */
        boolean usesBlockPalette();

        void prepare(AetherNative.Context ctx) throws IOException;

        /** Makes sure every tile of LOD `lod` covering [x0,x1) x [z0,z1) is uploaded. */
        void cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1);
    }

    /**
     * Seed-free source: the bundled Tamriel raster (exact macro shape, rivers, lakes, coasts, climates) with
     * aether's stand-in for the seeded detail. Works the same on multiplayer clients, which do not know the seed.
     * An exact source evaluating the server's tamriel_height density function replaces it in single player once
     * the 26.3 conversion of TamrielHeight lands.
     */
    static ColumnSource defaultSource() {
        return new ColumnSource() {
            @Override
            public boolean usesBlockPalette() {
                return false;
            }

            @Override
            public void prepare(AetherNative.Context ctx) throws IOException {
                String version = AetherFarField.class.getPackage().getImplementationVersion();
                ctx.previewLoadRaster(AetherRasterCache.ensure(Minecraft.getInstance().gameDirectory.toPath(),
                        version != null ? version : "dev"));
            }

            @Override
            public void cover(AetherNative.Context ctx, int lod, int x0, int z0, int x1, int z1) {
                ctx.previewGenerate(lod, x0, z0, x1, z1);  // skips tiles it already has
            }
        };
    }

    static final int LODS = 7, WINDOW = 2;

    private final AetherNative.Context ctx;
    private final ColumnSource source;
    private final Thread worker;
    private volatile boolean running = true;
    private volatile double camX, camY, camZ;
    private volatile boolean haveCamera;

    AetherFarField(AetherNative.Context ctx, ColumnSource source) throws IOException {
        this.ctx = ctx;
        this.source = source;
        source.prepare(ctx);
        worker = new Thread(this::run, "skycraft-aether-farfield");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    ColumnSource source() {
        return source;
    }

    void follow(double x, double y, double z) {
        camX = x;
        camY = y;
        camZ = z;
        haveCamera = true;
    }

    private void run() {
        double lastX = Double.NaN, lastZ = Double.NaN;
        while (running) {
            try {
                if (!haveCamera) {
                    Thread.sleep(50);
                    continue;
                }
                double x = camX, y = camY, z = camZ;
                for (int lod = 0; lod < LODS; lod++) {
                    int span = 256 << lod;
                    int cx = Math.floorDiv((int) Math.floor(x), span), cz = Math.floorDiv((int) Math.floor(z), span);
                    source.cover(ctx, lod, (cx - WINDOW) * span, (cz - WINDOW) * span, (cx + WINDOW + 1) * span,
                            (cz + WINDOW + 1) * span);
                }
                ctx.update(x, y, z, 0);  // builds what changed and stages it for the next frame
                // idle while the camera stays inside the same finest-LOD region
                boolean moved = Double.isNaN(lastX) || Math.abs(x - lastX) > 32 || Math.abs(z - lastZ) > 32;
                lastX = x;
                lastZ = z;
                Thread.sleep(moved ? 5 : 100);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                com.skycraft.Skycraft.LOGGER.warn("aether far field worker: {}", t.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
