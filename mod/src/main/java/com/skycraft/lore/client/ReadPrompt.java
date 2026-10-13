package com.skycraft.lore.client;

import com.skycraft.dig.PlacedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * The crosshair hint "[F] Read  Lectern" over a lectern that holds a book (same place and style as the other [F]
 * hints). Lecterns a player placed are left to vanilla: the hint is for the world's own story lecterns.
 */
public final class ReadPrompt {
    private ReadPrompt() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.screen != null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        if (!(state.getBlock() instanceof LecternBlock) || !state.getValue(LecternBlock.HAS_BOOK)) return;
        if (PlacedBlocks.isPlayerPlaced(mc.level, hit.getBlockPos())) return;
        Component text = Component.translatable("hud.skycraft.lore.read", state.getBlock().getName());
        g.pose().pushPose();
        g.pose().translate(width / 2f, height / 2f + 10, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.drawCenteredString(mc.font, text, 0, 0, 0xD0E8E2D0);
        g.pose().popPose();
    }
}
