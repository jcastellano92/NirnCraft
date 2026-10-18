package com.skycraft.creatures.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skycraft.creatures.client.kit.RigModel;
import com.skycraft.creatures.client.kit.RigRenderer;
import com.skycraft.creatures.client.rig.defs.Defs;
import com.skycraft.creatures.entity.DragonEntity;
import com.skycraft.creatures.entity.anim.AnimSlot;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.skycraft.client.PoseRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/**
 * Dragons on the animated rig model, at twice the model size: variant skins (Alduin, Paarthurnax and Odahviing have
 * their own), glowing eyes, a translucent shimmer while Become Ethereal holds, flight banking, and the corpse pose.
 * A dead dragon's body burns away to its skeleton after its soul is absorbed: the flesh fades over the bones.
 */
public class DragonRenderer extends RigRenderer<DragonEntity> {
    private static final Identifier[] TEXTURES = new Identifier[DragonEntity.VARIANTS.length];
    private static final Identifier[] GLOW = new Identifier[DragonEntity.VARIANTS.length];
    private static final Identifier SKELETON = BestiaryClient.tex("dragon_skeletal");

    static {
        for (int i = 0; i < TEXTURES.length; i++) {
            String v = DragonEntity.VARIANTS[i];
            String name = v.equals("dragon") ? "dragon" : "dragon_" + v;
            TEXTURES[i] = BestiaryClient.tex(name);
            GLOW[i] = BestiaryClient.tex(name + "_glow");
        }
    }

    public DragonRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, BestiaryClient.layer("dragon"), Defs.get("dragon"), 3.0f, DragonRenderer::texture);
        this.scale(2.0f).flip(0f);
        this.glow(d -> burn(d, 0f) >= 1f ? null : GLOW[d.getVariant()]);
        this.addLayer(new SkeletonLayer(this));
        this.withExtra((model, dragon, limbSwing, amount, age, yaw, pitch) -> {
            if (dragon.isCorpsePose()) {
                model.resetAll();
                model.applySlot(AnimSlot.DEATH, 10f, 1f);
            }
        });
    }

    private static Identifier texture(DragonEntity dragon) {
        return TEXTURES[dragon.getVariant()];
    }

    @Override public void extractRenderState(DragonEntity dragon, PoseRenderState state, float partialTicks) {
        super.extractRenderState(dragon, state, partialTicks);
        state.burn = burn(dragon, partialTicks);
        state.skeletalVariant = dragon.getVariant() == DragonEntity.SKELETAL;
        float alpha=1, r=1, g=1, b=1;
        if (dragon.isEthereal()) {
            alpha=.35f+.1f*Mth.sin(state.ageInTicks*.3f); r=.75f; g=.9f;
        } else if (state.burn>0) {
            alpha=Mth.clamp(1-state.burn,0,1);
            float ch=Mth.clamp(state.burn*1.6f,0,1);
            r=1-.55f*ch; g=1-.65f*ch; b=1-.7f*ch;
        }
        state.modelColor=((int)(alpha*255)<<24)|((int)(r*255)<<16)|((int)(g*255)<<8)|(int)(b*255);
        state.translucentBody=dragon.isEthereal()||state.burn>0;
        state.hideBody=state.burn>=1;
        state.bankFlying=dragon.isFlying()&&dragon.deathTime<=0&&!dragon.isCorpsePose();
        state.bankHeight=1.75f;
        state.bankRoll=Mth.clamp(Mth.wrapDegrees(dragon.yBodyRot-dragon.yBodyRotO)*4f,-35f,35f);
    }

    @Override protected boolean affectedByCulling(DragonEntity dragon){return false;}
    @Override protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(DragonEntity dragon,float partialTicks){return dragon.getBoundingBoxForCulling();}

    /** How much of the flesh is gone: burning corpses (soul absorbed) and dragons rising from their mounds. */
    static float burn(DragonEntity dragon, float partialTicks) {
        if (dragon.isCorpsePose()) return dragon.burnProgress();
        int rising = dragon.getRising();
        if (rising <= 0) return 0f;
        // bones only for the first third of the rise, then the flesh grows back
        return Mth.clamp((rising - partialTicks) / (DragonEntity.RISE_TICKS * 0.66f), 0f, 1f);
    }

    @Nullable @Override
    protected RenderType getRenderType(PoseRenderState state, boolean bodyVisible, boolean translucentVisible, boolean glowing) {
        if (bodyVisible && state.translucentBody) {
            if (state.hideBody) return null;
            return RenderTypes.entityTranslucent(getTextureLocation(state));
        }
        return super.getRenderType(state, bodyVisible, translucentVisible, glowing);
    }

    /** The bones under a burning corpse's fading flesh (and all that remains afterwards). */
    static final class SkeletonLayer extends RenderLayer<PoseRenderState, RigModel<DragonEntity>> {
        SkeletonLayer(RenderLayerParent<PoseRenderState, RigModel<DragonEntity>> parent) { super(parent); }
        @Override public void submit(PoseStack pose, SubmitNodeCollector collector, int light, PoseRenderState state, float yaw, float pitch) {
            if (!state.skeletalVariant && state.burn>0)
                collector.submitModel(getParentModel(),state,pose,RenderTypes.entityCutout(SKELETON),light,OverlayTexture.NO_OVERLAY,-1,null,state.outlineColor);
        }
    }
}
