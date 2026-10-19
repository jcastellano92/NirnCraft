package com.nirncraft.creatures.client;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;

/**
 * Player-shaped model (full 64x64 skin layout with hat/jacket/sleeve/pants overlays) for bandits, draugr, guards and
 * giants. Raises the weapon arm when holding an item and draws the bow when an archer is aggressive.
 */
public class SkyHumanoidModel<T extends Mob> extends net.minecraft.client.model.HumanoidModel<net.minecraft.client.renderer.entity.state.HumanoidRenderState> {
    public SkyHumanoidModel(ModelPart root) {
        super(root, net.minecraft.client.renderer.rendertype.RenderTypes::entityTranslucent);
    }

    public static LayerDefinition createLayer() {
        return LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64);
    }

    @Override
    public void setupAnim(net.minecraft.client.renderer.entity.state.HumanoidRenderState state) {
        super.setupAnim(state);
        SkyHumanoidRenderer.Appearance appearance=state.getRenderData(SkyHumanoidRenderer.APPEARANCE);
        if (appearance==null) return;
        if (state.swingAnimation > 0.0F) {
            float swing = net.minecraft.util.Mth.sin(state.swingAnimation * (float) Math.PI);
            boolean twoHanded = appearance.twoHanded();
            this.rightArm.xRot -= swing * (twoHanded ? 0.85F : 0.45F);
            if (twoHanded) {
                this.leftArm.xRot -= swing * 0.65F;
                this.leftArm.yRot += swing * 0.25F;
            }
            this.body.yRot += swing * 0.20F;
        } else if (appearance.shieldRaised()) {
            this.leftArm.xRot = -0.85F;
            this.leftArm.yRot = 0.35F;
        }
    }
}

