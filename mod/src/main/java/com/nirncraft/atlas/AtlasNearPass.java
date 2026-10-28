package com.nirncraft.atlas;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.GpuFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.pipeline.PipelineModifier;
import net.neoforged.neoforge.client.pipeline.RegisterPipelineModifiersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Infinite ATLAS's near pass: vanilla no longer draws terrain; we do, and light it ourselves.
 *
 * <p>Vanilla still builds its section meshes (exact models, mod blocks, fluids, smooth light, biome tint: what a
 * section compile produces), so entities (they need the section at their feet compiled), block entities (listed by
 * compiled sections), cave culling and the loading screen keep working (docs/ATLAS-RENDER-INVENTORY.md section 3).
 * Each frame: at {@code RenderFrameEvent.Pre} the visible sections are taken from vanilla (its own draw lists come out
 * empty, water and lava included); after the sky they are put back for our own {@code prepareChunkRenders}, whose
 * opaque layers are drawn into our G-buffer (albedo with vanilla's tint and ambient occlusion, block and sky light
 * levels, reverse-Z depth) through a pipeline modifier that swaps vanilla's terrain shaders for ours; in the main pass
 * after opaque terrain the resolve lights them and composites atlas's far frame behind, writing the frame's colour
 * and depth, so everything drawn after depth-tests against our terrain; translucent layers are drawn by us where
 * vanilla would have drawn them.</p>
 *
 * <p>Stage A (this): the resolve lights with vanilla's own lightmap (the picture should match vanilla's). Not owned
 * (vanilla draws terrain as before for that frame): a shader pack in use, improved transparency (OIT), frames without
 * a sky pass (lava, powder snow, blindness, darkness: the stage we draw in does not fire). On by default
 * where it can own the frame (Sodium, which replaces the section renderer, absent); {@code -Dnirncraft.atlas.near=false}
 * or the autotest action {@code atlas-near off} turns it off.</p>
 */
public final class AtlasNearPass {
    static final String FEATURE = "atlas near pass";
    /** Owning terrain on or off (-Dnirncraft.atlas.near, the autotest action atlas-near on | off: side by sides). */
    public static volatile boolean active = !"false".equals(System.getProperty("nirncraft.atlas.near"))
            && !net.neoforged.fml.ModList.get().isLoaded("sodium");

    static final ResourceKey<PipelineModifier> GBUFFER = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_gbuffer"));
    static final ResourceKey<PipelineModifier> FORWARD = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_forward"));
    static final ResourceKey<PipelineModifier> ENTITY = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_entity"));
    static final ResourceKey<PipelineModifier> SHADOW = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_shadow"));
    private static final BindGroupLayout SHADOW_MAP = BindGroupLayout.builder()
            .withUniform("ShadowMap", UniformType.COMBINED_IMAGE_SAMPLER).build();
    /** Atlas's near light for entities and particles: its alpha the water's surface in front (atlas_water.glsl atlasBehindWater). */
    private static final BindGroupLayout ATLAS_WATER = BindGroupLayout.builder()
            .withUniform("AtlasWater", UniformType.COMBINED_IMAGE_SAMPLER).build();
    static final int SHADOW_SIZE = 2048;
    static final float SHADOW_HALF = 96f;    // blocks each side of the camera
    static final float SHADOW_DEPTH = 256f;  // blocks before and behind it along the light
    private static TextureTarget shadow;
    private static final Matrix4f shadowMat = new Matrix4f().zero();
    private static boolean shadowDrawn;
    private static net.minecraft.client.renderer.MappableRingBuffer shadowProjUbo;
    private static final Identifier VANILLA_ENTITY = Identifier.withDefaultNamespace("core/entity");
    private static final Identifier VANILLA_PARTICLE = Identifier.withDefaultNamespace("core/particle");
    // items (dropped, in frames, held by mobs, on campfires): vanilla's item shader is its entity shader on the same
    // vertex format without the per-face light, so they take near_entity too (our light, not the lightmap alone)
    private static final Identifier VANILLA_ITEM = Identifier.withDefaultNamespace("core/item");
    // falling blocks, moving pistons (vanilla's block shader: no normal in its format; near_block takes the face's)
    private static final Identifier VANILLA_BLOCK = Identifier.withDefaultNamespace("core/block");
    private static boolean entityPushed;  // the entity modifier is on the stack (from the resolve to AfterLevel)
    private static final Set<Identifier> TRANSLUCENT = Set.of(RenderPipelines.TRANSLUCENT_TERRAIN.getLocation(),
            RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW.getLocation());
    private static final BindGroupLayout NEAR_INFO = BindGroupLayout.builder()
            .withUniform("NearInfo", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout SCENE = BindGroupLayout.builder()
            .withUniform("SceneColor", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SkyView", UniformType.COMBINED_IMAGE_SAMPLER)  // the air's sky (AtlasSky)
            .withUniform("SkyFrame", UniformType.COMBINED_IMAGE_SAMPLER)  // the drawn sky (aurora, stars, moons)
            .withUniform("SkyCube", UniformType.COMBINED_IMAGE_SAMPLER).build();  // its layers in every direction

    private static final Set<Identifier> OPAQUE = Set.of(RenderPipelines.SOLID_TERRAIN.getLocation(),
            RenderPipelines.SOLID_TERRAIN_MULTIDRAW.getLocation(), RenderPipelines.CUTOUT_TERRAIN.getLocation(),
            RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW.getLocation());

    private static final BindGroupLayout RESOLVE_SAMPLERS = BindGroupLayout.builder()
            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler3", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler4", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler5", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler6", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("SunMap", UniformType.COMBINED_IMAGE_SAMPLER)  // the far field's sun map (crepuscular rays)
            .withUniform("NearInfo", UniformType.UNIFORM_BUFFER)
            .build();

    static final RenderPipeline RESOLVE = RenderPipeline.builder()
            .withLocation(id("pipeline/near_resolve"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(id("core/near_resolve"))
            .withBindGroupLayout(RESOLVE_SAMPLERS)
            .withBindGroupLayout(SHADOW_MAP)  // the sun's shadow map: the light in the air (near_resolve's beams)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();

    static final RenderPipeline COMPOSITE = RenderPipeline.builder()
            .withLocation(id("pipeline/near_composite"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(id("core/near_composite"))
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();

    private static final BindGroupLayout TAA_LAYOUT = BindGroupLayout.builder()
            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Sampler2", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("TaaInfo", UniformType.UNIFORM_BUFFER)
            .build();
    static final RenderPipeline TAA = RenderPipeline.builder()
            .withLocation(id("pipeline/near_taa"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(id("core/near_taa"))
            .withBindGroupLayout(TAA_LAYOUT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();

    private static final BindGroupLayout NEAR_LIGHT = BindGroupLayout.builder()
            .withUniform("Sampler6", UniformType.COMBINED_IMAGE_SAMPLER).build();
    static final RenderPipeline LIGHTMAP = RenderPipeline.builder()
            .withLocation(id("pipeline/near_lightmap"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(id("core/near_lightmap"))
            .withBindGroupLayout(NEAR_INFO)
            .withBindGroupLayout(NEAR_LIGHT)
            .withBindGroupLayout(SHADOW_MAP)  // (atlas_light.glsl declares it; unused here)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState.DEFAULT)
            .build();
    private static com.mojang.renderpearl.api.textures.GpuTexture lightmapScratch, lightmapBlank;
    private static com.mojang.renderpearl.api.textures.GpuTextureView lightmapBlankView;
    private static com.mojang.renderpearl.api.textures.GpuTextureView lightmapScratchView;

    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = new ObjectArrayList<>();
    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby = new ObjectArrayList<>();
    private static boolean owned;          // this frame's terrain is ours (vanilla's lists were taken)
    private static boolean drawn;          // our G-buffer holds this frame
    private static boolean lit;            // and atlas lit it (the resolve's mode 1): translucents take our light too
    private static ChunkSectionsToRender chunks;
    private static TextureTarget gbuffer;  // albedo + depth
    private static TextureTarget light;    // light levels
    private static TextureTarget frame;    // the resolved frame (displayed colour, premultiplied; depth), this frame's
    private static final TextureTarget[] history = new TextureTarget[2];  // the temporal resolve's results, ping-pong
    private static int historyIndex;       // the one written this frame
    private static boolean historyValid;
    private static final Matrix4f prevViewProj = new Matrix4f();
    private static double prevX, prevY, prevZ;
    private static int jitterIndex;
    private static float jitterX, jitterY;  // this frame's sub-pixel offset, window pixels (atlas's convention: ndc y up)
    private static net.minecraft.client.renderer.MappableRingBuffer projUbo, taaUbo;
    private static net.minecraft.client.renderer.MappableRingBuffer info;  // the resolve's NearInfo (one vec4)

    private AtlasNearPass() {}

    /** Final client shutdown only; world changes and model reloads retain their reusable targets. */
    static void closeAtShutdown() {
        active = false;
        owned = drawn = lit = false;
        for (TextureTarget target : new TextureTarget[] {shadow, gbuffer, light, frame, skyFrame, history[0], history[1]})
            if (target != null) target.destroyBuffers();
        shadow = gbuffer = light = frame = skyFrame = history[0] = history[1] = null;
        for (var buffer : new net.minecraft.client.renderer.MappableRingBuffer[] {shadowProjUbo, projUbo, taaUbo, info})
            if (buffer != null) buffer.close();
        shadowProjUbo = projUbo = taaUbo = info = null;
        if (lightmapScratchView != null) { lightmapScratchView.close(); lightmapScratchView = null; }
        if (lightmapBlankView != null) { lightmapBlankView.close(); lightmapBlankView = null; }
        if (lightmapScratch != null) { lightmapScratch.close(); lightmapScratch = null; }
        if (lightmapBlank != null) { lightmapBlank.close(); lightmapBlank = null; }
        if (terrainSampler != null) { terrainSampler.close(); terrainSampler = null; }
        terrainSamplerAniso = -1;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nirncraft", path);
    }

    // flight recorder zones (AtlasRecorder)
    private static final AtlasRecorder.Zone Z_TAKE = AtlasRecorder.zone("nearPass:takeSections"),
            Z_GBUFFER = AtlasRecorder.zone("nearPass:drawGbuffer (with atlas frame)"), Z_COPY_SKY = AtlasRecorder.zone("nearPass:copySky"),
            Z_TRANSLUCENT = AtlasRecorder.zone("nearPass:drawTranslucent"), Z_END_FRAME = AtlasRecorder.zone("nearPass:endFrame"),
            Z_RESOLVE = AtlasRecorder.zone("nearPass:resolve"), Z_TEMPORAL = AtlasRecorder.zone("nearPass:temporal");

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterRenderPipelinesEvent e) -> {
            e.registerPipeline(RESOLVE);
            e.registerPipeline(AtlasMeter.PIPELINE);
            e.registerPipeline(COMPOSITE);
            e.registerPipeline(TAA);
            e.registerPipeline(LIGHTMAP);
        });
        modBus.addListener((RegisterPipelineModifiersEvent e) -> {
            e.register(GBUFFER, AtlasNearPass::gbufferPipeline);
            e.register(FORWARD, AtlasNearPass::forwardPipeline);
            e.register(ENTITY, AtlasNearPass::entityPipeline);
            e.register(SHADOW, AtlasNearPass::shadowPipeline);
        });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> AtlasGuard.run(FEATURE, Z_TAKE, AtlasNearPass::takeSections));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterSky e) -> AtlasGuard.run(FEATURE, Z_GBUFFER,
                () -> drawGbuffer(e)));
        // the finished sky (after every sky listener: NirnCraft's stars, moons and aurora), copied for the reflections
        // inside a block: our volume (atlasInside) instead of vanilla's flat block texture over the screen
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ExtractBlockScreenEffectEvent e) -> {
            if (e.getOverlayType() == net.neoforged.neoforge.client.event.ExtractBlockScreenEffectEvent.OverlayType.BLOCK && lit)
                e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, (RenderLevelStageEvent.AfterSky e) ->
                AtlasGuard.run(FEATURE, Z_COPY_SKY, AtlasNearPass::copySky));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentFeatures e) -> AtlasGuard.run(FEATURE, Z_TRANSLUCENT,
                () -> drawTranslucent(e.getRenderPass())));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel e) -> {
            popEntity();  // (outside the guard: a near pass switched off mid-frame must not leave its modifier pushed)
            AtlasGuard.run(FEATURE, Z_END_FRAME, AtlasNearPass::endFrame);
        });
    }

    /** Vanilla's opaque terrain pipelines with our G-buffer shaders and two colour targets; anything else as is. */
    private static RenderPipeline gbufferPipeline(RenderPipeline p, Identifier name) {
        if (!OPAQUE.contains(p.getLocation())) return p;
        return p.toBuilder()
                .withLocation(name)
                .withVertexShader(id("core/near_gbuffer"))
                .withFragmentShader(id("core/near_gbuffer"))
                .withColorTargetState(0, ColorTargetState.DEFAULT)
                .withColorTargetState(1, ColorTargetState.DEFAULT)
                .build();
    }

    /** Vanilla's translucent terrain pipelines with our forward shader (our light and look); anything else as is. */
    private static RenderPipeline forwardPipeline(RenderPipeline p, Identifier name) {
        if (!TRANSLUCENT.contains(p.getLocation())) return p;
        return p.toBuilder()
                .withLocation(name)
                .withVertexShader(id("core/near_gbuffer"))
                .withFragmentShader(id("core/near_forward"))
                .withBindGroupLayout(NEAR_INFO)
                .withBindGroupLayout(SCENE)
                .withBindGroupLayout(SHADOW_MAP)
                .build();
    }

    /**
     * Every pipeline drawn with vanilla's entity shader (mobs, players, block entities, items, armour) or its particle
     * shader with ours: the same defines, our light and look (near_entity, near_particle), so they stand in the frame
     * lit as the terrain is.
     */
    private static RenderPipeline entityPipeline(RenderPipeline p, Identifier name) {
        Identifier vs = p.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX);
        String ours = VANILLA_ENTITY.equals(vs) || VANILLA_ITEM.equals(vs) ? "core/near_entity"
                : VANILLA_PARTICLE.equals(vs) ? "core/near_particle" : VANILLA_BLOCK.equals(vs) ? "core/near_block" : null;
        if (ours == null) return p;
        return p.toBuilder()
                .withLocation(name)
                .withVertexShader(id(ours))
                .withFragmentShader(id(ours))
                .withBindGroupLayout(NEAR_INFO)
                .withBindGroupLayout(SHADOW_MAP)
                .withBindGroupLayout(ATLAS_WATER)
                .build();
    }

    /** A block-atlas sprite's rectangle (u0, v0, u1, v1), or an empty one (the forward shader then finds no water). */
    private static float[] sprite(Minecraft mc, String path) {
        try {
            var tex = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (tex instanceof TextureAtlas atlas) {
                var sp = atlas.getSprite(Identifier.withDefaultNamespace(path));
                return new float[] {sp.getU0(), sp.getV0(), sp.getU1(), sp.getV1()};
            }
        } catch (RuntimeException e) {
            // no sprite: no water shading
        }
        return new float[] {2f, 2f, -1f, -1f};
    }

    /** Atlas lit this frame (one light near and far): what draws after in our look (NirnCraft's clouds give way). */
    public static boolean litThisFrame() {
        return lit;
    }

    /** Vanilla's opaque terrain pipelines as the shadow map's: our vertex shader, depth only (cutout keeps its holes). */
    private static RenderPipeline shadowPipeline(RenderPipeline p, Identifier name) {
        if (!OPAQUE.contains(p.getLocation())) return p;
        return p.toBuilder()
                .withLocation(name)
                .withVertexShader(id("core/near_gbuffer"))
                .withFragmentShader(id("core/near_shadow"))  // (vanilla's colour state kept: the dummy target matches it)
                .build();
    }

    /**
     * The key light's shadow map of the near terrain (what entities, water and particles are shaded by: the opaque
     * terrain has atlas's traced shadows): vanilla's section meshes once more, seen from the light in an orthographic
     * box SHADOW_HALF around the camera, snapped to whole texels so it does not shimmer as the camera moves; reverse-Z
     * as the terrain pipelines test depth. The sections are the camera's visible ones (a caster behind the camera
     * casts nothing yet).
     */
    private static void drawShadow(LevelRenderer lr, float[] keyDir) {
        shadowDrawn = false;
        org.joml.Vector3f L = new org.joml.Vector3f(keyDir[0], keyDir[1], keyDir[2]);
        if (L.lengthSquared() < 1e-6f || L.y < 0.05f) return;  // the light near or under the horizon: no map
        L.normalize();
        org.joml.Vector3f up = Math.abs(L.y) > 0.99f ? new org.joml.Vector3f(0, 0, 1) : new org.joml.Vector3f(0, 1, 0);
        Matrix4f view = new Matrix4f().lookAlong(-L.x, -L.y, -L.z, up.x, up.y, up.z);
        // snap: the camera's position in light space to whole texels (the coordinates are camera-relative)
        var cam = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        org.joml.Vector3f c = view.transformDirection(new org.joml.Vector3f((float) cam.pos.x, (float) cam.pos.y, (float) cam.pos.z));
        float texel = 2f * SHADOW_HALF / SHADOW_SIZE;
        float ox = c.x - (float) Math.floor(c.x / texel) * texel, oy = c.y - (float) Math.floor(c.y / texel) * texel;
        Matrix4f snapped = new Matrix4f().translation(ox, oy, 0).mul(view);
        // reverse-Z orthographic: nearest the light -> 1, farthest -> 0
        Matrix4f ortho = new Matrix4f().setOrtho(-SHADOW_HALF, SHADOW_HALF, -SHADOW_HALF, SHADOW_HALF,
                -SHADOW_DEPTH, SHADOW_DEPTH, true);
        Matrix4f proj = new Matrix4f().m22(-1f).m32(1f).mul(ortho);
        // (an RGBA8 colour target, the format vanilla's pipeline declares, which the pipeline's one colour state writes nothing to: the GL backend wants an attachment)
        if (shadow == null) shadow = new TextureTarget("atlas near shadow", SHADOW_SIZE, SHADOW_SIZE, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        if (shadowProjUbo == null) shadowProjUbo = new net.minecraft.client.renderer.MappableRingBuffer(() -> "atlas shadow projection", 130, 64);
        try (var v = shadowProjUbo.currentBuffer().map(false, true)) {
            com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(v.data()).putMat4f(proj);
        }
        ChunkSectionsToRender casters = prepareChunks(lr, snapped, false);
        var mc = Minecraft.getInstance();
        var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var desc = RenderPassDescriptor.builder(() -> "Infinite ATLAS sun shadow")
                .withColorAttachment(shadow.getColorTextureView(), Optional.empty())
                .withDepthAttachment(shadow.getDepthTextureView(), OptionalDouble.of(0.0))
                .build();
        RenderSystem.pushPipelineModifier(SHADOW);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(desc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", shadowProjUbo.currentBuffer());
            casters.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, terrainSampler(mc), atlas, false);
        } finally {
            RenderSystem.popPipelineModifier();
        }
        shadowProjUbo.rotate();
        shadowMat.set(proj).mul(snapped);
        shadowDrawn = true;
    }

    /**
     * Atlas's near light for what is drawn after the frame (entities, particles): in the unified field its alpha is the
     * water's surface in front of the pixel (atlas_publish.slang), so a fish, a swimmer or a boat's hull under it is
     * seen through the water. A stand-in (alpha never negative: no water) without atlas's frame.
     */
    private static void bindAtlasWater(RenderPass pass) {
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        AtlasTargets far = AtlasClient.farTargetsThisFrame();
        if (far != null) pass.setUniform("AtlasWater", far.nearLightTarget().getTextureView(), nearest);
        else if (light != null) pass.setUniform("AtlasWater", light.getColorTextureView(), nearest);
    }

    /** The shadow map (or a stand-in when there is none this frame: ShadowMat zero means lit everywhere). */
    private static void bindShadow(RenderPass pass) {
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        if (shadow != null) pass.setUniform("ShadowMap", shadow.getDepthTextureView(), nearest);
        else if (gbuffer != null) pass.setUniform("ShadowMap", gbuffer.getDepthTextureView(), nearest);
    }

    /** True when the frame's terrain is ours (AtlasClient: read our depth, skip the fallback, resolve instead). */
    /**
     * The unified field (the owner's direction, dev flag nirncraft.atlas.unified=true while it is proven): atlas draws all
     * terrain, near and far, from the real blocks (the GPU grid), one renderer and no line; vanilla's chunk meshes then
     * only cast the near shadow map and carry the translucents (water) and the depth entities test against comes from
     * atlas.
     */
    static boolean unified() {
        return "true".equalsIgnoreCase(System.getProperty("nirncraft.atlas.unified")) && owned;
    }

    static boolean owns() {
        return owned && drawn;
    }

    /** Whether the near compositor can take terrain, independent of this frame's draw state. */
    static boolean capable(Minecraft mc) {
        return active && !AtlasGuard.off(FEATURE) && !AtlasClient.irisPackInUse()
                && !mc.gameRenderer.useImprovedTransparency();
    }

    /** This frame's sub-pixel jitter, window pixels (AtlasClient hands atlas the same, in its pixels). */
    static float jitterX() {
        return jitterX;
    }

    static float jitterY() {
        return jitterY;
    }

    private static float halton(int i, int b) {
        float r = 0, f = 1f / b;
        for (; i > 0; i /= b, f /= b) r += f * (i % b);
        return r;
    }

    private static void takeSections() {
        AtlasLook.beginFrame(Minecraft.getInstance());
        owned = false;
        drawn = false;
        int ji = (jitterIndex++ & 7) + 1;  // 8 Halton (2, 3) offsets
        jitterX = halton(ji, 2) - 0.5f;
        jitterY = halton(ji, 3) - 0.5f;
        Minecraft mc = Minecraft.getInstance();
        // the sections vanilla re-meshes this frame (blocks or light changed): their chunks to atlas again. Whether or not
        // atlas draws this frame (the gallery and a safe mode switch it off for a while): a block changed then is atlas's
        // world all the same, and the notification is not repeated when atlas draws again
        if (mc.level != null)
            for (var u : mc.gameRenderer.gameRenderState().levelRenderState.sectionUpdateRenderStates)
                AtlasClientSections.changed(net.minecraft.world.level.ChunkPos.pack(
                        net.minecraft.core.SectionPos.x(u.sectionNode()), net.minecraft.core.SectionPos.z(u.sectionNode())));
        if (mc.level == null || !capable(mc)) return;
        if (!skyPassRuns(mc)) return;
        LevelRenderer lr = mc.levelRenderer;
        visible.clear();
        visible.addAll(lr.visibleSections());
        nearby.clear();
        nearby.addAll(lr.nearbyVisibleSections());
        lr.visibleSections().clear();
        lr.nearbyVisibleSections().clear();
        owned = true;
    }

    /** The sky pass runs (so AfterSky fires): LevelRenderer.addSkyPass's own condition, on the extracted state. */
    private static boolean skyPassRuns(Minecraft mc) {
        var state = mc.gameRenderer.gameRenderState().levelRenderState;
        var cam = state.cameraRenderState;
        var fog = cam.fogType;
        return state.skyRenderState.skybox != net.minecraft.world.level.dimension.DimensionType.Skybox.NONE
                && fog != net.minecraft.world.level.material.FogType.POWDER_SNOW
                && fog != net.minecraft.world.level.material.FogType.LAVA
                && !cam.entityRenderState.doesMobEffectBlockSky;
    }

    /**
     * Our draw lists by the path vanilla took this frame: its section data lives in one storage that is either a uniform
     * buffer (prepareChunkRenders) or instanced (prepareChunkRendersIndirect, where the device draws multi-indirect),
     * and asking for the other kind closes it and starts a new one at two entries. The plain path on a multi-indirect
     * device did that three times a frame (vanilla's lists, ours, the shadow's): the buffer vanilla's lists point into
     * closed under them, the new one grown from 2 to 1024 every frame, a log line each time (48 MB in 40 minutes).
     */
    private static ChunkSectionsToRender prepareChunks(LevelRenderer lr, Matrix4f modelView, boolean sortTranslucent) {
        boolean indirect = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState
                .shouldUseMultiDrawIndirectForTerrain;
        return indirect ? lr.prepareChunkRendersIndirect(modelView, sortTranslucent)
                : lr.prepareChunkRenders(modelView, sortTranslucent);
    }

    private static TextureTarget skyFrame;  // the sky as the sky pass drew it (aurora, stars, moons): the water mirrors it

    private static void copySky() {
        if (!owned) return;
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main == null || main.getColorTexture() == null) return;
        if (skyFrame == null || skyFrame.width != main.width || skyFrame.height != main.height) {
            if (skyFrame != null) skyFrame.destroyBuffers();
            skyFrame = new TextureTarget("atlas sky frame", main.width, main.height, main.getColorTexture().getFormat(), null);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(),
                skyFrame.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
    }

    private static void drawGbuffer(RenderLevelStageEvent.AfterSky e) {
        if (!owned) return;
        Minecraft mc = Minecraft.getInstance();

        LevelRenderer lr = mc.levelRenderer;
        // our own draw lists from the sections vanilla would have drawn (the lists are emptied again at once)
        lr.visibleSections().addAll(visible);
        lr.nearbyVisibleSections().addAll(nearby);
        try {
            chunks = prepareChunks(lr, new Matrix4f(e.getModelViewMatrix()), true);
            drawShadow(lr, AtlasLook.light(mc));
        } finally {
            lr.visibleSections().clear();
            lr.nearbyVisibleSections().clear();
        }
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        if (gbuffer == null || gbuffer.width != w || gbuffer.height != h) {
            if (gbuffer != null) gbuffer.destroyBuffers();
            if (light != null) light.destroyBuffers();
            gbuffer = new TextureTarget("atlas near albedo", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
            light = new TextureTarget("atlas near light", w, h, GpuFormat.RGBA8_UNORM, null);
        }
        var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var sampler = terrainSampler(mc);
        var desc = RenderPassDescriptor.builder(() -> "Infinite ATLAS near G-buffer")
                .withColorAttachment(gbuffer.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))
                .withColorAttachment(light.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))
                .withDepthAttachment(gbuffer.getDepthTextureView(), OptionalDouble.of(0.0))  // reverse-Z: far = 0
                .build();
        // vanilla's projection (bob included) moved by this frame's jitter: atlas's rays sample the pixel at centre + j
        // (ndc y up), so the geometry moves by -j for the same sample point
        var cam = e.getLevelRenderState().cameraRenderState;
        Matrix4f jittered = new Matrix4f().translation(-2f * jitterX / w, -2f * jitterY / h, 0f).mul(AtlasLook.levelProjection(cam));
        if (projUbo == null) projUbo = new net.minecraft.client.renderer.MappableRingBuffer(() -> "atlas near projection", 130, 64);
        try (var view = projUbo.currentBuffer().map(false, true)) {
            com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(view.data()).putMat4f(jittered);
        }
        RenderSystem.pushPipelineModifier(GBUFFER);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(desc)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projUbo.currentBuffer());
            if (!unified()) chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);  // (unified: atlas's)
        } finally {
            RenderSystem.popPipelineModifier();
        }
        drawn = true;
        // atlas's frame now, on our depth (outside vanilla's main pass), then our resolve into our own frame
        AtlasClient.renderFar(e.getLevelRenderState(), null);
        long rz = AtlasRecorder.begin();
        resolve(AtlasClient.farTargetsThisFrame(), AtlasClient.nearLitThisFrame());
        AtlasRecorder.end(Z_RESOLVE, rz);
        rz = AtlasRecorder.begin();
        temporal(cam);
        AtlasRecorder.end(Z_TEMPORAL, rz);
        projUbo.rotate();
    }

    /** The temporal resolve: this frame blended with the last result reprojected (near_taa), into the history. */
    private static void temporal(net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        if (frame == null) return;
        int w = frame.width, h = frame.height;
        for (int i = 0; i < 2; i++)
            if (history[i] == null || history[i].width != w || history[i].height != h) {
                if (history[i] != null) history[i].destroyBuffers();
                history[i] = new TextureTarget("atlas near history " + i, w, h, GpuFormat.RGBA8_UNORM, null);
                historyValid = false;
            }
        Matrix4f vp = AtlasLook.levelProjection(cam).mul(cam.viewRotationMatrix);
        double dx = cam.pos.x - prevX, dy = cam.pos.y - prevY, dz = cam.pos.z - prevZ;
        boolean valid = historyValid && dx * dx + dy * dy + dz * dz < 32 * 32;  // a teleport starts afresh
        if (taaUbo == null) taaUbo = new net.minecraft.client.renderer.MappableRingBuffer(() -> "atlas near taa", 130, 160);
        try (var view = taaUbo.currentBuffer().map(false, true)) {
            com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(view.data())
                    .putMat4f(new Matrix4f(vp).invert()).putMat4f(prevViewProj)
                    .putVec4((float) dx, (float) dy, (float) dz, 0f).putVec4(0.12f, valid ? 1f : 0f, 0f, 0f);
        }
        int out = historyIndex ^ 1;
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Infinite ATLAS temporal",
                history[out].getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(TAA));
            pass.setUniform("Sampler0", frame.getColorTextureView(), nearest);
            pass.setUniform("Sampler1", frame.getDepthTextureView(), nearest);
            pass.setUniform("Sampler2", history[historyIndex].getColorTextureView(), linear);
            pass.setUniform("TaaInfo", taaUbo.currentBuffer());
            pass.draw(3, 1, 0, 0);
        }
        taaUbo.rotate();
        historyIndex = out;
        historyValid = true;
        prevViewProj.set(vp);
        prevX = cam.pos.x;
        prevY = cam.pos.y;
        prevZ = cam.pos.z;
    }

    private static com.mojang.renderpearl.api.textures.GpuSampler terrainSampler;
    private static int terrainSamplerAniso = -1;

    /**
     * The sampler vanilla draws terrain with (LevelRenderer's chunkLayerSampler: linear, mipmapped, the anisotropy the
     * options ask for): the terrain shader's own texel snapping (sampleNearest / RGSS) assumes it. Nearest filtering
     * picked the wrong texels at a distance (trees and grass turned grey and brown).
     */
    private static com.mojang.renderpearl.api.textures.GpuSampler terrainSampler(Minecraft mc) {
        int aniso = mc.options.textureFiltering().get() == net.minecraft.client.TextureFilteringMethod.ANISOTROPIC
                ? mc.options.maxAnisotropyValue() : 1;
        if (terrainSampler == null || aniso != terrainSamplerAniso) {
            if (terrainSampler != null) terrainSampler.close();
            terrainSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, aniso, OptionalDouble.empty());
            terrainSamplerAniso = aniso;
        }
        return terrainSampler;
    }

    /**
     * In the main pass after opaque terrain (AtlasClient, after atlas's frame): our terrain into the frame. far: atlas's
     * targets when it drew this frame; nearLight: it lit our pixels too (ATLAS_FRAME_NEAR_LIGHT: one light near and far,
     * the pack's look for both), else stage A's vanilla light.
     */
    static void resolve(AtlasTargets far, boolean nearLight) {
        if (!owns()) return;
        Minecraft mc = Minecraft.getInstance();
        if (frame == null || frame.width != gbuffer.width || frame.height != gbuffer.height) {
            if (frame != null) frame.destroyBuffers();
            frame = new TextureTarget("atlas near frame", gbuffer.width, gbuffer.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }
        // our light whenever the core can light the near pass (atlas may skip a frame while streaming a new place:
        // then mode 2, the same light untraced, instead of vanilla's look for that frame: a flicker on arrival)
        boolean traced = nearLight && far != null;
        lit = AtlasClient.nearLightSupported();
        if (info == null) info = new net.minecraft.client.renderer.MappableRingBuffer(() -> "atlas near info", 130, 368);
        var camState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        float[] medium = medium(mc, camState);
        float[] inside = inside(mc, camState);
        insideSolid = inside[3] > 0f && inside[7] < 0.5f;
        float eyeWater = eyeWaterDepth(mc, camState);
        float[] sun = AtlasClient.sunDirection(mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState.sunAngle);
        float[] still = sprite(mc, "block/water_still"), flow = sprite(mc, "block/water_flow");
        float seconds = mc.level == null ? 0f : (float) ((mc.level.getGameTime() % 1_000_000L) / 20.0);
        float[] look = AtlasLook.frame(mc), k = AtlasLook.light(mc);
        try (var view = info.currentBuffer().map(false, true)) {
            com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(view.data())
                    .putVec4(look[0], look[1], !lit ? 0f : traced ? 1f : 2f, AtlasClient.farLags() ? 1f : 0f)
                    .putVec4(k[0], k[1], k[2], lit ? (unified() ? 2f : 1f) : 0f)  // w: 1 the near light, 2 the unified field
                    // (w: the eye is in water: the resolve and the water take the medium's absorption)
                    .putVec4(k[3], k[4], k[5], eyeWater)  // w: 0 in air, else 1 + the eye's depth in water
                    .putVec4(k[6], k[7], k[8], 0f)
                    // w: 1 where a special medium blinds the eye (lava, powder snow, blindness, darkness: vanilla's fog
                    // values stand in for its density), 0 otherwise: the distance haze is the air's alone
                    .putVec4(k[9], k[10], k[11], AtlasClient.preExposure())  // w: the exposure atlas's images carry
                    .putVec4(sun[0], sun[1], sun[2], seconds)
                    .putVec4(still[0], still[1], still[2], still[3])
                    .putVec4(flow[0], flow[1], flow[2], flow[3])
                    .putMat4f(shadowDrawn ? shadowMat : new Matrix4f().zero())
                    .putMat4f(new Matrix4f(camState.viewRotationMatrix).invert())  // InvViewRot: view -> camera-relative world
                    .putVec4((float) Math.floorMod((long) Math.floor(camState.pos.x), 6144L) + (float) (camState.pos.x - Math.floor(camState.pos.x)),
                            (float) camState.pos.y,
                            (float) Math.floorMod((long) Math.floor(camState.pos.z), 6144L) + (float) (camState.pos.z - Math.floor(camState.pos.z)), 0f)
                    .putVec4(waterTint(mc, camState)[0], waterTint[1], waterTint[2], waterTint[3])  // WaterTint: colour, w ocean share
                    // CamAbs: the camera's world position (the sun map's world frame), w: the sun map's size (0: none)
                    .putVec4((float) camState.pos.x, (float) camState.pos.y, (float) camState.pos.z,
                            far != null ? far.sunMapSize() : 0f)
                    // Medium: extinction a block, its own light (glow, rgb before display)
                    .putVec4(medium[0], medium[1], medium[2], medium[3])
                    // Inside: the block the eye is clipping into (its shape's box, extinction, kind, colour)
                    .putVec4(inside[0], inside[1], inside[2], inside[3])
                    .putVec4(inside[4], inside[5], inside[6], inside[7])
                    .putVec4(inside[8], inside[9], inside[10], 0f);
        }
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        var desc = RenderPassDescriptor.builder(() -> "Infinite ATLAS resolve")
                .withColorAttachment(frame.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))
                .withDepthAttachment(frame.getDepthTextureView(), OptionalDouble.of(0.0))
                .build();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(desc)) {
        pass.setPipeline(RenderSystem.getCompiledPipeline(RESOLVE));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("Sampler0", gbuffer.getColorTextureView(), nearest);
        pass.setUniform("Sampler1", light.getColorTextureView(), nearest);
        pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), linear);
        pass.setUniform("Sampler3", gbuffer.getDepthTextureView(), nearest);
        if (far != null) {
            pass.setUniform("Sampler4", far.colorTarget().getTextureView(), linear);
            pass.setUniform("Sampler5", far.depthTarget().getTextureView(), nearest);
            pass.setUniform("Sampler6", far.nearLightTarget().getTextureView(), nearest);
            pass.setUniform("SunMap", far.sunTarget().getTextureView(), nearest);
        } else {  // no far frame this time: the G-buffer's own (empty) colour stands in, nothing far is composited
            pass.setUniform("Sampler4", light.getColorTextureView(), nearest);
            pass.setUniform("Sampler5", gbuffer.getDepthTextureView(), nearest);
            pass.setUniform("Sampler6", light.getColorTextureView(), nearest);
            pass.setUniform("SunMap", light.getColorTextureView(), nearest);  // (CamAbs.w 0: not read)
        }
        pass.setUniform("NearInfo", info.currentBuffer());
        bindShadow(pass);
        pass.draw(3, 1, 0, 0);
        }
    }

    private static final float[] waterTint = {0.25f, 0.46f, 0.89f, 0f};

    /**
     * The medium around the eye, ours (atlas_water.glsl atlasMedium): {extinction a block, glow r, g, b}. Lava: about a
     * block of sight (a few with fire resistance), glowing from within; powder snow: thick white scattering (its light the
     * sky's and the sun's, added in the shader); blindness: closing in over its fade (sight a few blocks), black; darkness:
     * pulsing with the effect's own blend (the warden's), black. 0 extinction: the air (its haze is atlas's).
     */
    private static float[] medium(Minecraft mc, net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        var t = cam.fogType;
        var p = mc.player;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (t == net.minecraft.world.level.material.FogType.LAVA) {
            boolean resist = p != null && p.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
            return new float[] {resist ? 0.6f : 2.5f, 3.2f, 1.0f, 0.18f};
        }
        if (t == net.minecraft.world.level.material.FogType.POWDER_SNOW) return new float[] {1.6f, -1f, 0f, 0f};  // (-1: lit white)
        if (p != null) {
            var blind = p.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
            if (blind != null) return new float[] {0.65f * blind.getBlendFactor(p, pt) + 0.001f, 0f, 0f, 0f};
            var dark = p.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
            if (dark != null) {
                float pulse = 0.55f + 0.45f * (float) Math.sin((mc.level != null ? mc.level.getGameTime() + pt : 0) * 0.08);
                return new float[] {0.35f * dark.getBlendFactor(p, pt) * pulse + 0.001f, 0f, 0f, 0f};
            }
        }
        return new float[] {0f, 0f, 0f, 0f};
    }

    private static final float[] NOT_INSIDE = new float[11];
    /** The eye is inside rock or earth this frame (AtlasMeter holds the eye's adaptation: it must not open the dark up). */
    static volatile boolean insideSolid;
    /** What the eye is inside, for atlas-info: the block and the kind (or "-"). */
    static volatile String insideWhat = "-";

    /**
     * The block the eye is clipping into (atlas_water.glsl atlasInside): {min xyz camera-relative, extinction a block,
     * max xyz, kind (0 solid, 1 foliage, 2 glass and the like), colour rgb}, or all 0. Only the shape's box the eye is
     * actually in (a slab's half, a fence's post): the world is never hidden otherwise. Fluids are the media's.
     */
    private static float[] inside(Minecraft mc, net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        if (mc.level == null) return NOT_INSIDE;
        net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(cam.pos);
        var st = mc.level.getBlockState(bp);
        insideWhat = st.toString();
        if (st.isAir() || st.liquid()) return NOT_INSIDE;
        double lx = cam.pos.x - bp.getX(), ly = cam.pos.y - bp.getY(), lz = cam.pos.z - bp.getZ();
        for (var box : st.getShape(mc.level, bp).toAabbs()) {
            if (lx < box.minX || lx > box.maxX || ly < box.minY || ly > box.maxY || lz < box.minZ || lz > box.maxZ) continue;
            boolean leaves = st.is(net.minecraft.tags.BlockTags.LEAVES) || st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
            float kind = leaves ? 1f : st.canOcclude() ? 0f : 2f;
            insideWhat = st + " kind=" + kind + " box=" + box;
            float sigma = kind == 0f ? 7f : kind == 1f ? 2.2f : 0.25f;
            int c = st.getMapColor(mc.level, bp).col;
            float r = (float) Math.pow(((c >> 16) & 255) / 255f, 2.2), g = (float) Math.pow(((c >> 8) & 255) / 255f, 2.2),
                    b = (float) Math.pow((c & 255) / 255f, 2.2);
            return new float[] {(float) (box.minX - lx), (float) (box.minY - ly), (float) (box.minZ - lz), sigma,
                    (float) (box.maxX - lx), (float) (box.maxY - ly), (float) (box.maxZ - lz), kind, r, g, b};
        }
        return NOT_INSIDE;
    }

    /** The medium around the eye (atlas_water.glsl atlasMedium): 0 the air, 1 lava, 2 powder snow, 3 blindness, 4 darkness. */
    private static float specialFog(Minecraft mc, net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        var t = cam.fogType;
        if (t == net.minecraft.world.level.material.FogType.LAVA) return 1f;
        if (t == net.minecraft.world.level.material.FogType.POWDER_SNOW) return 2f;
        var p = mc.player;
        if (p != null && p.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)) return 3f;
        if (p != null && p.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)) return 4f;
        return 0f;
    }
    private static boolean waterTintSet;
    private static long waterTintFrame;

    /**
     * The water's colour around the camera (linear-ish sRGB 0..1): the biomes' water colours averaged over a 7 x 7 grid
     * 24 blocks apart (about 150 blocks: a lake reads as one water, a biome edge as a long blend), and eased over a few
     * seconds as the camera moves (no snap at a border). Sampled every 8 frames.
     */
    private static float[] waterTint(Minecraft mc, net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        if (mc.level == null || (waterTintFrame++ & 7) != 0) return waterTint;
        float r = 0, g = 0, b = 0, ocean = 0;
        net.minecraft.core.BlockPos.MutableBlockPos bp = new net.minecraft.core.BlockPos.MutableBlockPos();
        int n = 0;
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                bp.set(cam.pos.x + dx * 24, cam.pos.y, cam.pos.z + dz * 24);
                var biome = mc.level.getBiome(bp);
                if (biome.is(net.minecraft.tags.BiomeTags.IS_OCEAN)) ocean += 1f;
                int c = biome.value().getWaterColor();
                r += ((c >> 16) & 255) / 255f;
                g += ((c >> 8) & 255) / 255f;
                b += (c & 255) / 255f;
                n++;
            }
        r /= n;
        g /= n;
        b /= n;
        ocean /= n;
        float k = waterTintSet ? 0.08f : 1f;  // (every 8 frames: about 2 s to settle at 60 fps)
        waterTint[0] += (r - waterTint[0]) * k;
        waterTint[1] += (g - waterTint[1]) * k;
        waterTint[2] += (b - waterTint[2]) * k;
        waterTint[3] += (ocean - waterTint[3]) * k;
        waterTintSet = true;
        return waterTint;
    }

    /**
     * 0 when the eye is not in water, else 1 + how deep under the water's surface it is (blocks): the water column over
     * the eye block, up to 64 (atlas_water.glsl: the light the water lets down, caustics, shafts).
     */
    private static float eyeWaterDepth(Minecraft mc, net.minecraft.client.renderer.state.level.CameraRenderState cam) {
        if (cam.fogType != net.minecraft.world.level.material.FogType.WATER || mc.level == null) return 0f;
        net.minecraft.core.BlockPos.MutableBlockPos bp = net.minecraft.core.BlockPos.containing(cam.pos).mutable();
        float top = bp.getY() + 1f;
        for (int i = 0; i < 64; i++) {
            var fs = mc.level.getFluidState(bp);
            if (!fs.is(net.minecraft.tags.FluidTags.WATER)) break;
            top = bp.getY() + fs.getHeight(mc.level, bp);
            bp.move(net.minecraft.core.Direction.UP);
        }
        return 1f + Math.max(0f, top - (float) cam.pos.y);
    }

    /** In vanilla's main pass after opaque terrain: our resolved frame into it, colour and depth. */
    static void composite(RenderPass pass) {
        if (!owns() || frame == null) return;
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        pass.setPipeline(RenderSystem.getCompiledPipeline(COMPOSITE));
        TextureTarget color = history[historyIndex] != null && historyValid ? history[historyIndex] : frame;
        pass.setUniform("Sampler0", color.getColorTextureView(), nearest);
        pass.setUniform("Sampler1", frame.getDepthTextureView(), nearest);
        pass.draw(3, 1, 0, 0);
        // what vanilla draws next in this pass (entities, block entities, items) takes our light while we own the frame
        if (lit) {
            pass.setUniform("NearInfo", info.currentBuffer());
            bindShadow(pass);
            bindAtlasWater(pass);
            RenderSystem.pushPipelineModifier(ENTITY);
            entityPushed = true;
        }
    }

    private static void drawTranslucent(RenderPass pass) {
        if (!owns() || chunks == null) return;
        Minecraft mc = Minecraft.getInstance();
        var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var sampler = terrainSampler(mc);
        if (!lit) {  // stage A: vanilla's own shader, as the opaque terrain has vanilla's light
            chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
            return;
        }
        pass.setUniform("NearInfo", info.currentBuffer());
        // the water's reflections read our resolved frame (terrain near and far) and its depth
        TextureTarget scene = history[historyIndex] != null && historyValid ? history[historyIndex] : frame;
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        if (scene != null && frame != null) {
            pass.setUniform("SceneColor", scene.getColorTextureView(), linear);
            pass.setUniform("SceneDepth", frame.getDepthTextureView(), nearest);
            // the sky the water mirrors where no reflected ray finds the frame: this frame's sky-view LUT (bearing repeats)
            var sky = AtlasSky.skyView();
            pass.setUniform("SkyView", sky != null ? sky : scene.getColorTextureView(), RenderSystem.getSamplerCache()
                    .getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false));
            pass.setUniform("SkyFrame", skyFrame != null ? skyFrame.getColorTextureView() : scene.getColorTextureView(), linear);
            var cubeView = com.nirncraft.atmosphere.client.SkyCube.view();
            pass.setUniform("SkyCube", cubeView != null ? cubeView : scene.getColorTextureView(), linear);
        }
        bindShadow(pass);
        bindAtlasWater(pass);  // (entities and particles drawn in this pass)
        RenderSystem.pushPipelineModifier(FORWARD);
        try {
            chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
        } finally {
            RenderSystem.popPipelineModifier();
        }
    }

    /** Vanilla's lists back: it recomputes them only when the camera turns or the graph changes, not every frame. */
    /**
     * Vanilla's lightmap in our look (near_lightmap): what is lit by it after the level (the first-person hand and held
     * items) matches the frame. Vanilla rewrites it whenever its light changes; we rewrite it after each owned frame.
     */
    private static void lightmapInOurLook() {
        var lightmap = Minecraft.getInstance().gameRenderer.levelLightmap();
        if (lightmap == null || lightmap.isClosed() || info == null) return;
        var device = RenderSystem.getDevice();
        if (lightmapScratch == null || lightmapScratch.isClosed()) {
            lightmapScratch = device.createTexture("atlas near lightmap", com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                    | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
            lightmapScratchView = device.createTextureView(lightmapScratch);
            // Sampler6 needs a sampled image distinct from the active lightmap render target.
            lightmapBlank = device.createTexture("atlas near lightmap blank", com.mojang.renderpearl.api.textures.GpuTexture.USAGE_TEXTURE_BINDING
                    | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                    | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_FLOAT, 1, 1, 1, 1);
            device.createCommandEncoder().clearColorTexture(lightmapBlank, new Vector4f(0f, 0f, 0f, 0f));
            lightmapBlankView = device.createTextureView(lightmapBlank);
        }
        var encoder = device.createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Infinite ATLAS lightmap", lightmapScratchView, Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(LIGHTMAP));
            pass.setUniform("NearInfo", info.currentBuffer());
            AtlasTargets far = AtlasClient.farTargetsThisFrame();
            var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, false);
            pass.setUniform("Sampler6", far != null ? far.nearLightTarget().getTextureView() : lightmapBlankView, linear);
            bindShadow(pass);  // (declared by atlas_light.glsl: Minecraft's Vulkan backend refuses a draw with it unbound)
            pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToTexture(lightmapScratch, lightmap.texture(), 0, 0, 0, 0, 0, 16, 16);
    }

    private static void popEntity() {
        if (entityPushed) {
            entityPushed = false;
            RenderSystem.popPipelineModifier();
        }
    }

    private static void endFrame() {
        chunks = null;
        // the entity modifier off the stack first: anything below may throw, and a modifier left on the stack fails
        // Minecraft's frame-end check (a crash, not just the near pass off)
        popEntity();
        if (drawn && lit) AtlasMeter.meter(AtlasLook.lastLog2());  // the eye meters the finished frame
        if (lit) lightmapInOurLook();
        if (info != null && drawn) info.rotate();
        lit = false;
        if (!owned) return;
        LevelRenderer lr = Minecraft.getInstance().levelRenderer;
        lr.visibleSections().clear();
        lr.visibleSections().addAll(visible);
        lr.nearbyVisibleSections().clear();
        lr.nearbyVisibleSections().addAll(nearby);
    }
}
