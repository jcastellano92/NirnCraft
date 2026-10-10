package com.skycraft.world.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.world.Provinces;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;


import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * The painted map of Nirn as a pyramid of 128-pixel tiles, rendered on a background thread from
 * {@link TamrielMap#sample} (which reads the bundled Tamriel rasters, and answers for the foreign and endless lands
 * beyond them) and uploaded as {@link DynamicTexture}s. Shaded relief on parchment, ink coastlines, rivers, lakes,
 * mountain hatching, and hold and province borders. Nothing reads blocks, so the map costs the same anywhere and
 * never touches the client world.
 *
 * <p>Levels of detail are blocks per tile pixel: 64, 16, 4 and 1. Tiles are cached (LRU) for the whole session.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MapTiles {
    public static final int SIZE = 128;
    /** Blocks per tile pixel of each level of detail, coarsest first. */
    public static final int[] LOD_BPP = {64, 16, 4, 1};
    private static final int MAX_TEXTURES = 192;
    private static final int MAX_PENDING = 160;
    private static final int UPLOADS_PER_FRAME = 6;

    private record Key(int lod, int tx, int tz) {
    }

    private record Done(Key key, NativeImage image) {
    }

    private static final LinkedHashMap<Key, ResourceLocation> TEXTURES = new LinkedHashMap<>(256, 0.75f, true);
    private static final Map<Key, Boolean> PENDING = new ConcurrentHashMap<>();
    private static final LinkedBlockingDeque<Key> QUEUE = new LinkedBlockingDeque<>();
    private static final ConcurrentLinkedQueue<Done> DONE = new ConcurrentLinkedQueue<>();
    private static Thread worker;
    private static int counter;

    private MapTiles() {}

    /** World blocks covered by one tile at {@code lod}. */
    public static int tileBlocks(int lod) {
        return LOD_BPP[lod] * SIZE;
    }

    /** The finest level whose texels are not smaller than {@code blocksPerPixel} (one GUI pixel). */
    public static int lodFor(double blocksPerPixel) {
        for (int lod = LOD_BPP.length - 1; lod >= 0; lod--) {
            if (LOD_BPP[lod] >= blocksPerPixel * 0.75) return lod;
        }
        return 0;
    }

    /** The texture of a tile if ready; otherwise queues it (newest requests first) and returns null. */
    public static ResourceLocation get(int lod, int tx, int tz) {
        Key key = new Key(lod, tx, tz);
        ResourceLocation tex = TEXTURES.get(key);
        if (tex != null) return tex;
        if (PENDING.putIfAbsent(key, Boolean.TRUE) == null) {
            QUEUE.offerFirst(key);
            while (QUEUE.size() > MAX_PENDING) {
                Key stale = QUEUE.pollLast();
                if (stale == null) break;
                PENDING.remove(stale);
            }
            ensureWorker();
        }
        return null;
    }

    /** Whether a tile is already uploaded (no request). */
    public static boolean ready(int lod, int tx, int tz) {
        return TEXTURES.containsKey(new Key(lod, tx, tz));
    }

    /** Render thread: turns finished images into textures, a few per frame. */
    public static void uploadFinished() {
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Done d = DONE.poll();
            if (d == null) break;
            PENDING.remove(d.key());
            DynamicTexture texture = new DynamicTexture(d.image());
            texture.setFilter(true, false);
            ResourceLocation rl = new ResourceLocation(Skycraft.MODID, "map_tile/" + (counter++));
            mc.getTextureManager().register(rl, texture);
            TEXTURES.put(d.key(), rl);
        }
        if (TEXTURES.size() > MAX_TEXTURES) {
            Iterator<Map.Entry<Key, ResourceLocation>> it = TEXTURES.entrySet().iterator();
            while (TEXTURES.size() > MAX_TEXTURES && it.hasNext()) {
                Map.Entry<Key, ResourceLocation> e = it.next();
                if (e.getKey().lod() == 0) continue; // the overview stays
                mc.getTextureManager().release(e.getValue());
                it.remove();
            }
        }
    }

    /** Drops every texture (logout / resource reload). */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation rl : TEXTURES.values()) mc.getTextureManager().release(rl);
        TEXTURES.clear();
        QUEUE.clear();
        PENDING.clear();
        Done d;
        while ((d = DONE.poll()) != null) d.image().close();
    }

    private static synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(MapTiles::run, "Skycraft map tiles");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    private static void run() {
        Painter painter = new Painter();
        while (true) {
            Key key;
            try {
                key = QUEUE.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            if (!PENDING.containsKey(key)) continue;
            try {
                DONE.add(new Done(key, painter.paint(key.lod(), key.tx(), key.tz())));
            } catch (Throwable t) {
                Skycraft.LOGGER.warn("Map tile {} failed", key, t);
                PENDING.remove(key);
            }
        }
    }

    // ------------------------------------------------------------------ painting

    /** Paints tiles; one instance per worker thread (reuses its buffers). */
    static final class Painter {
        private static final int N = SIZE + 2;
        private final MapSample sample = new MapSample();
        private final float[] elev = new float[N * N];
        private final int[] region = new int[N * N];
        private final float[] river = new float[N * N];
        private final float[] lake = new float[N * N];
        private final float[] ridge = new float[N * N];
        private final float[] coast = new float[N * N];
        private final boolean[] water = new boolean[N * N];
        /** Province per region index, looked up once per index. */
        private final Int2ObjectOpenHashMap<String> provinceOf = new Int2ObjectOpenHashMap<>();
        private final Int2IntOpenHashMap tintOf = new Int2IntOpenHashMap();

        NativeImage paint(int lod, int tx, int tz) {
            int bpp = LOD_BPP[lod];
            int x0 = tx * SIZE * bpp;
            int z0 = tz * SIZE * bpp;
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    int wx = x0 + (i - 1) * bpp + bpp / 2;
                    int wz = z0 + (j - 1) * bpp + bpp / 2;
                    TamrielMap.sample(wx, wz, sample);
                    int k = j * N + i;
                    elev[k] = sample.elevation;
                    region[k] = sample.region;
                    river[k] = sample.river;
                    lake[k] = sample.lake;
                    ridge[k] = sample.ridge;
                    coast[k] = sample.coastDistance;
                    water[k] = sample.elevation < TamrielMap.SEA_LEVEL || sample.lake > 0.5f;
                    if (!provinceOf.containsKey(sample.region)) {
                        String province;
                        try {
                            province = TamrielMap.region(wx, wz).province();
                        } catch (RuntimeException e) {
                            province = "realm";
                        }
                        provinceOf.put(sample.region, province);
                        tintOf.put(sample.region, Provinces.tint(province));
                    }
                }
            }
            NativeImage img = new NativeImage(SIZE, SIZE, false);
            float slopeScale = 2.2f / bpp;
            for (int py = 0; py < SIZE; py++) {
                for (int px = 0; px < SIZE; px++) {
                    int k = (py + 1) * N + (px + 1);
                    int wx = x0 + px * bpp;
                    int wz = z0 + py * bpp;
                    int rgb = water[k] ? waterColor(k, bpp) : landColor(k, slopeScale, px, py, wx, wz);
                    // paper grain in world space so neighbouring tiles match
                    float grain = 0.95f + 0.05f * noise(wx >> 4, wz >> 4) + 0.025f * noise((wx >> 1) + 7919, (wz >> 1) - 7919);
                    img.setPixelRGBA(px, py, abgr(rgb, grain));
                }
            }
            return img;
        }

        private int landColor(int k, float slopeScale, int px, int py, int wx, int wz) {
            float e = elev[k];
            // relief colour: warm lowland parchment, ochre hills, grey-brown mountains, snowy peaks
            int c;
            if (e < 90) c = lerp(0xDCC9A0, 0xD2BE92, (e - 63) / 27f);
            else if (e < 150) c = lerp(0xD2BE92, 0xBCA882, (e - 90) / 60f);
            else if (e < 250) c = lerp(0xBCA882, 0xA49A8C, (e - 150) / 100f);
            else c = lerp(0xA49A8C, 0xEEEBE4, Math.min(1f, (e - 250) / 70f));
            c = lerp(c, tintOf.get(region[k]), 0.10f);

            // hillshade, light from the north-west
            float dx = (elev[k + 1] - elev[k - 1]) * slopeScale;
            float dz = (elev[k + N] - elev[k - N]) * slopeScale;
            float shade = clamp(1f + (-dx - dz) * 0.35f, 0.62f, 1.25f);
            c = scale(c, shade);

            // mountain hatching: short ink strokes along the ridges
            if (ridge[k] > 0.25f && ((px + py) % 4 == 0 || (px - py) % 7 == 0 && ridge[k] > 0.6f)) {
                c = lerp(c, 0x5A4630, Math.min(0.45f, ridge[k] * 0.45f));
            }
            // rivers
            if (river[k] > 0.35f) c = lerp(c, 0x557891, Math.min(1f, (river[k] - 0.35f) * 2.2f));

            // coastline: land touching water
            if (water[k - 1] || water[k + 1] || water[k - N] || water[k + N]) return 0x4A3A28;

            // borders: region changes to the right or below (both sides land)
            int r = region[k];
            int other = r != region[k + 1] && !water[k + 1] ? region[k + 1]
                    : r != region[k + N] && !water[k + N] ? region[k + N] : r;
            if (other != r) {
                boolean province = !provinceOf.get(r).equals(provinceOf.get(other));
                if (province) return 0x7A2A1A;
                if (((wx + wz) >> 3 & 1) == 0) return lerp(c, 0x6A3A24, 0.75f);
            }
            return c;
        }

        private int waterColor(int k, int bpp) {
            float depth = clamp((TamrielMap.SEA_LEVEL - elev[k]) / 40f, 0f, 1f);
            int c = lerp(0xA9BDB5, 0x7C98A2, depth);
            if (lake[k] > 0.5f) c = 0x9FB6B4;
            // a ripple line off the coast, like an engraved sea chart
            float cd = -coast[k];
            if (cd > 0 && cd < 9000 && Math.abs(cd - 3.2f * bpp) < 0.6f * bpp) c = lerp(c, 0x4A6070, 0.35f);
            return c;
        }

        private static int abgr(int rgb, float mul) {
            int r = Math.min(255, (int) (((rgb >> 16) & 255) * mul));
            int g = Math.min(255, (int) (((rgb >> 8) & 255) * mul));
            int b = Math.min(255, (int) ((rgb & 255) * mul));
            return 0xFF000000 | b << 16 | g << 8 | r;
        }

        private static float noise(int x, int z) {
            int h = x * 0x27d4eb2d ^ z * 0x165667b1;
            h ^= h >>> 15;
            h *= 0x85ebca6b;
            h ^= h >>> 13;
            return (h & 0xFFFF) / 65535f;
        }
    }

    static int lerp(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        int r = (int) (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * t));
        int g = (int) (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * t));
        int bl = (int) ((a & 255) + (((b & 255) - (a & 255)) * t));
        return r << 16 | g << 8 | bl;
    }

    static int scale(int c, float s) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * s));
        int g = Math.min(255, (int) (((c >> 8) & 255) * s));
        int b = Math.min(255, (int) ((c & 255) * s));
        return r << 16 | g << 8 | b;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
