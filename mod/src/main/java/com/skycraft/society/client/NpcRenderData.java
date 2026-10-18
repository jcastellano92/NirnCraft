package com.skycraft.society.client;

import com.skycraft.magic.spell.Spell;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/** NPC pose and appearance data captured on extraction, independent of later world/entity changes. */
record NpcRenderData(int getId, int look, int role, int skin, boolean sheathed, boolean isAggressive,
                     boolean isChild, boolean isHuddled, boolean isKneeling, boolean isTending,
                     boolean isCarrying, boolean isWeeping, boolean isPrisoner, boolean isCasting,
                     boolean isPlaying, boolean showsCrouchPose, HumanoidArm getMainArm,
                     ItemStack getMainHandItem, ItemStack getOffhandItem, Spell getCastingSpell) {
    static final ContextKey<NpcRenderData> KEY = new ContextKey<>(Identifier.fromNamespaceAndPath("skycraft", "npc_pose"));

    static NpcRenderData extract(NpcEntity entity) {
        return new NpcRenderData(entity.getId(), entity.getLook(), entity.role().ordinal(), entity.getSkin(),
                com.skycraft.arsenal.Sheathe.isSheathedOrHolstered(entity), entity.isAggressive(), entity.isChild(),
                entity.isHuddled(), entity.isKneeling(), entity.isTending(), entity.isCarrying(), entity.isWeeping(),
                entity.isPrisoner(), entity.isCasting(), entity.isPlaying(), entity.showsCrouchPose(), entity.getMainArm(),
                entity.getMainHandItem().copy(), entity.getOffhandItem().copy(), entity.getCastingSpell());
    }

    static NpcRenderData get(AvatarRenderState state) {
        return state.getRenderData(KEY);
    }
}
