package com.skycraft.protect;

import com.skycraft.Skycraft;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Breaking a grown amethyst cluster by hand sometimes shakes loose a petty soul gem (an empty one). */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class GeodeDrops {
    private static final float PETTY_GEM_CHANCE = 0.15f;

    private GeodeDrops() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.isCreative() || event.getState().getBlock() != Blocks.AMETHYST_CLUSTER) return;
        if (level.random.nextFloat() >= PETTY_GEM_CHANCE) return;
        var pos = event.getPos();
        ItemEntity gem = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, new ItemStack(ArcaneRegistry.SOUL_GEM_PETTY.get()));
        gem.setDefaultPickUpDelay();
        level.addFreshEntity(gem);
    }
}
