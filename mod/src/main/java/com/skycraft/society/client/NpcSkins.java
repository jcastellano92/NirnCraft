package com.skycraft.society.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.society.NpcLook;
import com.skycraft.society.Outfit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds townsfolk skins on the client: body (race, sex, tone) + hair and beard tinted with the hair colour + outfit,
 * composited once per distinct {@link NpcLook} into a 64x64 dynamic texture and cached (LRU, released on eviction
 * and on resource reload). Source layers are read once and kept. Render thread only.
 */
public final class NpcSkins {
    private static final String ROOT = "textures/entity/society/npc/";
    private static final int MAX_CACHED = 384;
    private static final Map<Integer, ResourceLocation> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, ResourceLocation> eldest) {
            if (size() <= MAX_CACHED) return false;
            Minecraft.getInstance().getTextureManager().release(eldest.getValue());
            return true;
        }
    };
    private static final Map<String, Optional<NativeImage>> SOURCES = new HashMap<>();

    private NpcSkins() {}

    /** The composited skin for a look, or null if its layers are missing (the caller falls back to role skins). */
    @Nullable
    public static ResourceLocation get(int look) {
        if (look < 0) return null;
        ResourceLocation rl = CACHE.get(look);
        if (rl != null) return rl;
        NativeImage img = compose(look);
        if (img == null) return null;
        rl = new ResourceLocation(Skycraft.MODID, "npc_skin/" + Integer.toHexString(look));
        Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
        CACHE.put(look, rl);
        return rl;
    }

    /** Drops every composited texture and source layer (resource reload). */
    public static void clear() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation rl : CACHE.values()) tm.release(rl);
        CACHE.clear();
        for (Optional<NativeImage> img : SOURCES.values()) img.ifPresent(NativeImage::close);
        SOURCES.clear();
    }

    @Nullable
    private static NativeImage compose(int look) {
        String race = NpcLook.race(look).name().toLowerCase(Locale.ROOT);
        String sex = NpcLook.female(look) ? "f" : "m";
        NativeImage body = source(ROOT + "body/" + race + "_" + sex + "_" + NpcLook.tone(look) + ".png");
        if (body == null) return null;
        NativeImage out = new NativeImage(64, 64, true);
        out.copyFrom(body);
        int hairColor = NpcLook.HAIR_COLORS[NpcLook.hairColor(look)];
        blend(out, source(ROOT + "hair/" + NpcLook.hair(look) + ".png"), hairColor);
        if (NpcLook.beard(look) > 0) blend(out, source(ROOT + "beard/" + NpcLook.beard(look) + ".png"), hairColor);
        Outfit outfit = NpcLook.outfit(look);
        blend(out, source(ROOT + "outfit/" + outfit.id + "_" + NpcLook.variant(look) + "_" + sex + ".png"), 0xFFFFFF);
        return out;
    }

    @Nullable
    private static NativeImage source(String path) {
        Optional<NativeImage> cached = SOURCES.get(path);
        if (cached != null) return cached.orElse(null);
        NativeImage img = null;
        Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(new ResourceLocation(Skycraft.MODID, path));
        if (res.isPresent()) {
            try (InputStream in = res.get().open()) {
                img = NativeImage.read(in);
                if (img.getWidth() != 64 || img.getHeight() != 64) {
                    img.close();
                    img = null;
                }
            } catch (Exception e) {
                Skycraft.LOGGER.warn("Skycraft: could not read NPC layer {}", path, e);
            }
        }
        SOURCES.put(path, Optional.ofNullable(img));
        return img;
    }

    /** Alpha-blends {@code src} over {@code dst}, multiplying its colour by {@code tint} (RGB). Pixels are ABGR. */
    private static void blend(NativeImage dst, @Nullable NativeImage src, int tint) {
        if (src == null) return;
        int tr = tint >> 16 & 255;
        int tg = tint >> 8 & 255;
        int tb = tint & 255;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int s = src.getPixelRGBA(x, y);
                int sa = s >>> 24;
                if (sa == 0) continue;
                int sr = (s & 255) * tr / 255;
                int sg = (s >> 8 & 255) * tg / 255;
                int sb = (s >> 16 & 255) * tb / 255;
                if (sa == 255) {
                    dst.setPixelRGBA(x, y, 0xFF000000 | sb << 16 | sg << 8 | sr);
                    continue;
                }
                int d = dst.getPixelRGBA(x, y);
                int da = d >>> 24;
                int r = (sr * sa + (d & 255) * (255 - sa)) / 255;
                int g = (sg * sa + (d >> 8 & 255) * (255 - sa)) / 255;
                int b = (sb * sa + (d >> 16 & 255) * (255 - sa)) / 255;
                int a = Math.min(255, sa + da * (255 - sa) / 255);
                dst.setPixelRGBA(x, y, a << 24 | b << 16 | g << 8 | r);
            }
        }
    }
}
