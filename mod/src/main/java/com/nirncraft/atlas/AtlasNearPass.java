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
 * a sky pass (lava, powder snow, blindness, darkness: the stage we draw in does not fire). Off unless
 * {@code -Dnirncraft.atlas.near=true} or the autotest action {@code atlas-near on}.</p>
 */
public final class AtlasNearPass {
    static final String FEATURE = "atlas near pass";
    /** Owning terrain on or off (-Dnirncraft.atlas.near, the autotest action atlas-near on | off: side by sides). */
    public static volatile boolean active = Boolean.getBoolean("nirncraft.atlas.near");

    static final ResourceKey<PipelineModifier> GBUFFER = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_gbuffer"));
    static final ResourceKey<PipelineModifier> FORWARD = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_forward"));
    static final ResourceKey<PipelineModifier> ENTITY = ResourceKey.create(PipelineModifier.MODIFIERS_KEY,
            id("near_entity"));
    private static final Identifier VANILLA_ENTITY = Identifier.withDefaultNamespace("core/entity");
    private static boolean entityPushed;  // the entity modifier is on the stack (from the resolve to AfterLevel)
    private static final Set<Identifier> TRANSLUCENT = Set.of(RenderPipelines.TRANSLUCENT_TERRAIN.getLocation(),
            RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW.getLocation());
    private static final BindGroupLayout NEAR_INFO = BindGroupLayout.builder()
            .withUniform("NearInfo", UniformType.UNIFORM_BUFFER).build();

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
            .withUniform("NearInfo", UniformType.UNIFORM_BUFFER)
            .build();

    static final RenderPipeline RESOLVE = RenderPipeline.builder()
            .withLocation(id("pipeline/near_resolve"))
            .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
            .withFragmentShader(id("core/near_resolve"))
            .withBindGroupLayout(RESOLVE_SAMPLERS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
            .build();

    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> visible = new ObjectArrayList<>();
    private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearby = new ObjectArrayList<>();
    private static boolean owned;          // this frame's terrain is ours (vanilla's lists were taken)
    private static boolean drawn;          // our G-buffer holds this frame
    private static boolean lit;            // and atlas lit it (the resolve's mode 1): translucents take our light too
    private static ChunkSectionsToRender chunks;
    private static TextureTarget gbuffer;  // albedo + depth
    private static TextureTarget light;    // light levels
    private static net.minecraft.client.renderer.MappableRingBuffer info;  // the resolve's NearInfo (one vec4)

    private AtlasNearPass() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nirncraft", path);
    }

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterRenderPipelinesEvent e) -> e.registerPipeline(RESOLVE));
        modBus.addListener((RegisterPipelineModifiersEvent e) -> {
            e.register(GBUFFER, AtlasNearPass::gbufferPipeline);
            e.register(FORWARD, AtlasNearPass::forwardPipeline);
            e.register(ENTITY, AtlasNearPass::entityPipeline);
        });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> AtlasGuard.run(FEATURE, AtlasNearPass::takeSections));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterSky e) -> AtlasGuard.run(FEATURE,
                () -> drawGbuffer(e)));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentFeatures e) -> AtlasGuard.run(FEATURE,
                () -> drawTranslucent(e.getRenderPass())));
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel e) -> AtlasGuard.run(FEATURE,
                AtlasNearPass::endFrame));
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
                .build();
    }

    /**
     * Every pipeline drawn with vanilla's entity shader (mobs, players, block entities, items, armour) with ours: the
     * same defines, our light and look (near_entity), so entities stand in the frame lit as the terrain is.
     */
    private static RenderPipeline entityPipeline(RenderPipeline p, Identifier name) {
        if (!VANILLA_ENTITY.equals(p.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))) return p;
        return p.toBuilder()
                .withLocation(name)
                .withVertexShader(id("core/near_entity"))
                .withFragmentShader(id("core/near_entity"))
                .withBindGroupLayout(NEAR_INFO)
                .build();
    }

    /** True when the frame's terrain is ours (AtlasClient: read our depth, skip the fallback, resolve instead). */
    static boolean owns() {
        return owned && drawn;
    }

    /** The G-buffer's light target (block, sky, normal): atlas's near light reads it (AtlasGlIO.nearInfoTex). */
    static int infoGlId() {
        return light == null ? 0 : AtlasTargets.glIdOf(light.getColorTexture());
    }

    static int depthGlId() {
        return gbuffer == null ? 0 : AtlasTargets.glIdOf(gbuffer.getDepthTexture());
    }

    private static void takeSections() {
        owned = false;
        drawn = false;
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.level == null || AtlasClient.irisPackInUse() || mc.gameRenderer.useImprovedTransparency()) return;
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

    private static void drawGbuffer(RenderLevelStageEvent.AfterSky e) {
        if (!owned) return;
        Minecraft mc = Minecraft.getInstance();
        LevelRenderer lr = mc.levelRenderer;
        // our own draw lists from the sections vanilla would have drawn (the lists are emptied again at once)
        lr.visibleSections().addAll(visible);
        lr.nearbyVisibleSections().addAll(nearby);
        try {
            chunks = lr.prepareChunkRenders(new Matrix4f(e.getModelViewMatrix()), true);
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
        var sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, true);
        var desc = RenderPassDescriptor.builder(() -> "Infinite ATLAS near G-buffer")
                .withColorAttachment(gbuffer.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))
                .withColorAttachment(light.getColorTextureView(), Optional.of(new Vector4f(0, 0, 0, 0)))
                .withDepthAttachment(gbuffer.getDepthTextureView(), OptionalDouble.of(0.0))  // reverse-Z: far = 0
                .build();
        RenderSystem.pushPipelineModifier(GBUFFER);
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(desc)) {
            RenderSystem.bindDefaultUniforms(pass);
            chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
        } finally {
            RenderSystem.popPipelineModifier();
        }
        drawn = true;
    }

    /**
     * In the main pass after opaque terrain (AtlasClient, after atlas's frame): our terrain into the frame. far: atlas's
     * targets when it drew this frame; nearLight: it lit our pixels too (ATLAS_FRAME_NEAR_LIGHT: one light near and far,
     * the pack's look for both), else stage A's vanilla light.
     */
    static void resolve(RenderPass pass, AtlasTargets far, boolean nearLight) {
        if (!owns()) return;
        Minecraft mc = Minecraft.getInstance();
        lit = nearLight && far != null;
        if (info == null) info = new net.minecraft.client.renderer.MappableRingBuffer(() -> "atlas near info", 130, 80);
        float[] look = AtlasLook.frame(mc), k = AtlasLook.light(mc);
        try (var view = info.currentBuffer().map(false, true)) {
            com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(view.data())
                    .putVec4(look[0], look[1], lit ? 1f : 0f, 0f)
                    .putVec4(k[0], k[1], k[2], lit ? 1f : 0f)
                    .putVec4(k[3], k[4], k[5], 0f)
                    .putVec4(k[6], k[7], k[8], 0f)
                    .putVec4(k[9], k[10], k[11], 0f);
        }
        var nearest = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, false);
        var linear = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
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
        } else {  // no far frame this time: the G-buffer's own (empty) colour stands in, nothing far is composited
            pass.setUniform("Sampler4", light.getColorTextureView(), nearest);
            pass.setUniform("Sampler5", gbuffer.getDepthTextureView(), nearest);
            pass.setUniform("Sampler6", light.getColorTextureView(), nearest);
        }
        pass.setUniform("NearInfo", info.currentBuffer());
        pass.draw(3, 1, 0, 0);
        // what vanilla draws next in this pass (entities, block entities, items) takes our light while we own the frame
        if (lit) {
            RenderSystem.pushPipelineModifier(ENTITY);
            entityPushed = true;
        }
    }

    private static void drawTranslucent(RenderPass pass) {
        if (!owns() || chunks == null) return;
        Minecraft mc = Minecraft.getInstance();
        var atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        var sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, true);
        if (!lit) {  // stage A: vanilla's own shader, as the opaque terrain has vanilla's light
            chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
            return;
        }
        pass.setUniform("NearInfo", info.currentBuffer());
        RenderSystem.pushPipelineModifier(FORWARD);
        try {
            chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
        } finally {
            RenderSystem.popPipelineModifier();
        }
    }

    /** Vanilla's lists back: it recomputes them only when the camera turns or the graph changes, not every frame. */
    private static void endFrame() {
        chunks = null;
        if (entityPushed) {
            RenderSystem.popPipelineModifier();
            entityPushed = false;
        }
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
