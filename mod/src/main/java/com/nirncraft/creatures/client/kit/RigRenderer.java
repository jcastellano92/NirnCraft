package com.nirncraft.creatures.client.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.entity.anim.AnimatedCreature;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.nirncraft.client.PoseRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renderer for every rig-model creature: texture per entity (variants), uniform scale, optional full-bright glow
 * layers (eyes, embers, runes), translucency (wraiths, wisps), a colour tint (procedurally tinted beasts), and
 * flight banking.
 */
public class RigRenderer<T extends Mob> extends MobRenderer<T, PoseRenderState, RigModel<T>> {
    private final Function<T, Identifier> texture;
    private float scale = 1f;
    private Function<T, Identifier> glowTexture;
    private boolean translucent;
    private boolean bank;
    private float flip = 90f;
    @Nullable
    private ToIntFunction<T> tint;
    @Nullable
    private Function<T, Float> scaleFn;

    public RigRenderer(EntityRendererProvider.Context ctx, ModelLayerLocation layer, CreatureDef def, float shadow,
                       Function<T, Identifier> texture) {
        super(ctx, new RigModel<>(ctx.bakeLayer(layer), def), shadow);
        this.texture = texture;
    }

    public RigRenderer<T> scale(float scale) {
        this.scale = scale;
        this.shadowRadius *= scale;
        return this;
    }

    /** Per-entity scale (e.g. giant variants), multiplied with the base scale. */
    public RigRenderer<T> scaleBy(Function<T, Float> fn) {
        this.scaleFn = fn;
        return this;
    }

    /** Drawn with alpha blending (ghostly creatures; the texture's alpha is used). */
    public RigRenderer<T> translucent() {
        this.translucent = true;
        return this;
    }

    /** Pitches with the flight path and banks into turns while flying. */
    public RigRenderer<T> bank() {
        this.bank = true;
        return this;
    }

    /** Degrees the body tips over when dying (vanilla 90; 0 when the death animation lays it down itself). */
    public RigRenderer<T> flip(float degrees) {
        this.flip = degrees;
        return this;
    }

    /** RGB tint per entity (0xRRGGBB), e.g. the seeded colours of the endless lands' beasts. */
    public RigRenderer<T> tint(ToIntFunction<T> tint) {
        this.tint = tint;
        return this;
    }

    /** Adds a full-bright layer (glowing eyes, embers, runes); variant-dependent through the function. */
    public RigRenderer<T> glow(Function<T, Identifier> glowTexture) {
        this.glowTexture = glowTexture;
        this.addLayer(new GlowLayer<>(this));
        return this;
    }

    public RigRenderer<T> withExtra(RigModel.Extra<T> extra) {
        this.model.withExtra(extra);
        return this;
    }

    @Override public PoseRenderState createRenderState() { return new PoseRenderState(); }
    @Override public Identifier getTextureLocation(PoseRenderState state) { return state.texture; }
    @Override public void extractRenderState(T entity, PoseRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.texture = texture.apply(entity);
        state.scale *= scale * (scaleFn == null ? 1f : scaleFn.apply(entity));
        state.modelColor = tint == null ? -1 : 0xff000000 | tint.applyAsInt(entity);
        state.glowTexture = glowTexture == null ? null : glowTexture.apply(entity);
        state.bankFlying = bank && entity instanceof AnimatedCreature ac && ac.animFlying() && entity.deathTime <= 0;
        state.bankPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        state.bankRoll = Mth.clamp(Mth.wrapDegrees(entity.yBodyRot - entity.yBodyRotO) * 3f, -30f, 30f);
        state.bankHeight = entity.getBbHeight() * .5f;
        model.extractPose(entity, state, partialTick);
    }
    @Override protected int getModelTint(PoseRenderState state) { return state.modelColor; }

    @Nullable
    @Override
    protected RenderType getRenderType(PoseRenderState entity, boolean bodyVisible, boolean translucentVisible, boolean glowing) {
        if (translucent && bodyVisible) return RenderTypes.entityTranslucent(getTextureLocation(entity));
        return super.getRenderType(entity, bodyVisible, translucentVisible, glowing);
    }

    @Override protected float getFlipDegrees() { return flip; }
    @Override protected void setupRotations(PoseRenderState state, PoseStack pose, float bodyRot, float entityScale) {
        super.setupRotations(state, pose, bodyRot, entityScale);
        if (state.bankFlying) {
            pose.translate(0, state.bankHeight, 0);
            pose.rotateDegrees(Axis.XP, -state.bankPitch);
            pose.rotateDegrees(Axis.ZP, state.bankRoll);
            pose.translate(0, -state.bankHeight, 0);
        }
    }

    /** Full-bright overlay: eyes, embers, glowing runes. */
    public static class GlowLayer<T extends Mob> extends EyesLayer<PoseRenderState, RigModel<T>> {
        public GlowLayer(RenderLayerParent<PoseRenderState, RigModel<T>> parent) { super(parent); }
        @Override public void submit(PoseStack pose, SubmitNodeCollector collector, int light, PoseRenderState state, float yaw, float pitch) {
            if (!state.isInvisible && state.glowTexture != null)
                collector.order(1).submitModel(getParentModel(), state, pose, RenderTypes.eyes(state.glowTexture), 15728640,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, -1, null, state.outlineColor);
        }
        @Override public RenderType renderType() { return RenderTypes.eyes(Identifier.withDefaultNamespace("textures/misc/white.png")); }
    }
}
