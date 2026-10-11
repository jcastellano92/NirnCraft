package com.skycraft.creatures.client.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.resources.ResourceLocation;
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
public class RigRenderer<T extends Mob> extends MobRenderer<T, RigModel<T>> {
    private final Function<T, ResourceLocation> texture;
    private float scale = 1f;
    private boolean translucent;
    private boolean bank;
    private float flip = 90f;
    @Nullable
    private ToIntFunction<T> tint;
    @Nullable
    private Function<T, Float> scaleFn;

    public RigRenderer(EntityRendererProvider.Context ctx, ModelLayerLocation layer, CreatureDef def, float shadow,
                       Function<T, ResourceLocation> texture) {
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
    public RigRenderer<T> glow(Function<T, ResourceLocation> glowTexture) {
        this.addLayer(new GlowLayer<>(this, glowTexture));
        return this;
    }

    public RigRenderer<T> withExtra(RigModel.Extra<T> extra) {
        this.model.withExtra(extra);
        return this;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return texture.apply(entity);
    }

    @Override
    public void render(T entity, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        if (tint != null) {
            int c = tint.applyAsInt(entity);
            model.tintR = ((c >> 16) & 255) / 255f;
            model.tintG = ((c >> 8) & 255) / 255f;
            model.tintB = (c & 255) / 255f;
        }
        super.render(entity, yaw, partialTicks, pose, buffers, light);
    }

    @Nullable
    @Override
    protected RenderType getRenderType(T entity, boolean bodyVisible, boolean translucentVisible, boolean glowing) {
        if (translucent && bodyVisible) return RenderType.entityTranslucent(getTextureLocation(entity));
        return super.getRenderType(entity, bodyVisible, translucentVisible, glowing);
    }

    @Override
    protected void scale(T entity, PoseStack pose, float partialTick) {
        float s = scale * (scaleFn == null ? 1f : scaleFn.apply(entity));
        if (s != 1f) pose.scale(s, s, s);
    }

    @Override
    protected float getFlipDegrees(T entity) {
        return flip;
    }

    @Override
    protected void setupRotations(T entity, PoseStack pose, float ageInTicks, float rotationYaw, float partialTicks) {
        super.setupRotations(entity, pose, ageInTicks, rotationYaw, partialTicks);
        if (bank && entity instanceof AnimatedCreature ac && ac.animFlying() && entity.deathTime <= 0) {
            float pitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
            float turn = Mth.wrapDegrees(entity.yBodyRot - entity.yBodyRotO);
            float roll = Mth.clamp(turn * 3.0f, -30.0f, 30.0f);
            float h = entity.getBbHeight() * 0.5f;
            pose.translate(0.0, h, 0.0);
            pose.mulPose(Axis.XP.rotationDegrees(-pitch));
            pose.mulPose(Axis.ZP.rotationDegrees(roll));
            pose.translate(0.0, -h, 0.0);
        }
    }

    /** Full-bright overlay: eyes, embers, glowing runes. */
    public static class GlowLayer<T extends Mob> extends EyesLayer<T, RigModel<T>> {
        private final Function<T, ResourceLocation> texture;

        public GlowLayer(RenderLayerParent<T, RigModel<T>> parent, Function<T, ResourceLocation> texture) {
            super(parent);
            this.texture = texture;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (entity.isInvisible()) return;
            ResourceLocation tex = texture.apply(entity);
            if (tex == null) return;
            this.getParentModel().renderToBuffer(pose, buffers.getBuffer(RenderType.eyes(tex)), 15728640,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
        }

        @Override
        public RenderType renderType() {
            return RenderType.eyes(new ResourceLocation("minecraft", "textures/misc/white.png"));
        }
    }
}
