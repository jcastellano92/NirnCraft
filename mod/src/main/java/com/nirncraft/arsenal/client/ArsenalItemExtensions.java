package com.nirncraft.arsenal.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/** Client item extensions: NirnCraft crossbows are held like the vanilla crossbow when loaded. */
public final class ArsenalItemExtensions {
    public static final IClientItemExtensions CROSSBOW = new IClientItemExtensions() {
        @Override
        public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
            if (entity.getSwingAnimation(0)<=0 && CrossbowItem.isCharged(stack)) return HumanoidModel.ArmPose.CROSSBOW_HOLD;
            return null;
        }
    };

    private ArsenalItemExtensions() {}
}
