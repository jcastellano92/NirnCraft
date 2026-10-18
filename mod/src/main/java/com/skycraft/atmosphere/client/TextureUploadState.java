package com.skycraft.atmosphere.client;

/** Profiles nested native upload batches. RenderPearl owns texture bindings; callers never borrow global GL units. */
final class TextureUploadState implements AutoCloseable {
    private static int depth;
    private static long batches, uploadNanos, maxUploadNanos;
    private final boolean outer;
    private final long start;
    private boolean closed;
    private TextureUploadState() { outer = depth++ == 0; start = outer ? System.nanoTime() : 0L; }
    static TextureUploadState preserve() { return new TextureUploadState(); }
    @Override public void close() {
        if (closed) return;
        closed = true; depth--;
        if (outer) {
            long elapsed = System.nanoTime() - start;
            batches++; uploadNanos += elapsed; maxUploadNanos = Math.max(maxUploadNanos, elapsed);
        }
    }
    static String describe() {
        return "native_upload_batches=" + batches + " native_upload_us=" + (batches == 0 ? 0 : uploadNanos / 1000.0 / batches)
                + " native_upload_max_us=" + maxUploadNanos / 1000.0;
    }
    static String probe() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        var atlas = minecraft.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTexture();
        return "block_atlas=" + atlas.getLabel() + " atlas_size=" + atlas.getWidth(0) + "x" + atlas.getHeight(0)
                + " atlas_format=" + atlas.getFormat() + " atlas_closed=" + atlas.isClosed() + " " + describe();
    }
}
