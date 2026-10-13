package com.skycraft.fire.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.skycraft.Skycraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render types of the far fire renderer. They are built on vanilla's own entity shaders ({@code rendertype_eyes} for
 * the additive flames and glow, {@code rendertype_entity_translucent} for smoke), so Oculus/Iris maps them to the
 * shader pack's {@code gbuffers_spidereyes} / {@code gbuffers_entities_translucent} programs and they sit in the pack's
 * lighting and tone mapping instead of bypassing it. No face culling (the quads are camera-facing billboards) and no
 * depth writes (they are sorted, and must not occlude one another).
 *
 * <p>Extends {@link RenderType} only to reach its protected state shards; never instantiated.</p>
 */
public final class FireRenderTypes extends RenderType {
    static final ResourceLocation FLAME_TEX = new ResourceLocation(Skycraft.MODID, "textures/fire/far_flame.png");
    static final ResourceLocation SMOKE_TEX = new ResourceLocation(Skycraft.MODID, "textures/fire/far_smoke.png");
    static final ResourceLocation GLOW_TEX = new ResourceLocation(Skycraft.MODID, "textures/fire/far_glow.png");

    /** Additive flame sprites. */
    public static final RenderType FLAME = create("skycraft_far_flame", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 4096,
            false, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(FLAME_TEX, false, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** Additive soft glow. */
    public static final RenderType GLOW = create("skycraft_far_glow", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 2048,
            false, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_EYES_SHADER)
                    .setTextureState(new TextureStateShard(GLOW_TEX, false, false))
                    .setTransparencyState(ADDITIVE_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** Alpha-blended, lit smoke puffs. */
    public static final RenderType SMOKE = create("skycraft_far_smoke", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 8192,
            false, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(SMOKE_TEX, true, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(false));

    private FireRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort,
                            Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
        throw new IllegalStateException("not instantiable");
    }
}
