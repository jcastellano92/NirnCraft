package com.skycraft.crafting.client.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;

/**
 * The worn model of one armour slot of one style: the vanilla humanoid parts carry only the cubes of that slot (the
 * gauntlets model has forearm cubes on the arms, the helmet model only head cubes...), so every part is drawn whatever
 * visibility the armour layer copied from the vanilla slot rules (which would hide the gauntlets' arms).
 */
public class SkyArmorModel extends HumanoidModel<net.minecraft.client.renderer.entity.state.HumanoidRenderState> {
    public SkyArmorModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(net.minecraft.client.renderer.entity.state.HumanoidRenderState state) {
        super.setupAnim(state);
        head.visible = true;
        hat.visible = true;
        body.visible = true;
        rightArm.visible = true;
        leftArm.visible = true;
        rightLeg.visible = true;
        leftLeg.visible = true;
    }
}
