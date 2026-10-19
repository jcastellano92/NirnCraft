package com.nirncraft.lore.client;

import com.nirncraft.dig.PlacedBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The crosshair hint "[F] Read  Lectern" over a lectern that holds a book (same place and style as the other [F]
 * hints). Lecterns a player placed are left to vanilla: the hint is for the world's own story lecterns.
 */
public final class ReadPrompt {
    private ReadPrompt() {}

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden() || mc.gui.screen() != null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockState state = mc.level.getBlockState(hit.getBlockPos());
        if (!(state.getBlock() instanceof LecternBlock) || !state.getValue(LecternBlock.HAS_BOOK)) return;
        if (PlacedBlocks.isPlayerPlaced(mc.level, hit.getBlockPos())) return;
        Component text = Component.translatable("hud.nirncraft.lore.read", state.getBlock().getName());
        g.pose().pushMatrix();
        g.pose().translate(width / 2f,  height / 2f + 10);
        g.pose().scale(0.75f,  0.75f);
        g.centeredText(mc.font, text, 0, 0, 0xD0E8E2D0);
        g.pose().popMatrix();
    }
}
