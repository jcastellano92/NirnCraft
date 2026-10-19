package com.nirncraft.client.autotest;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Test sessions only (the `showcase ITEM` verb): one item as the UI shows it, its flat inventory icon large on the
 * left and the inventory's 3D model on the right, on a plain backdrop, named above. Captured with capture-ui so the
 * reviewer judges icons and models without hunting through menus.
 */
public final class ShowcaseScreen extends Screen {
    private final ItemStack stack;

    ShowcaseScreen(ItemStack stack) {
        super(Component.literal("showcase"));
        this.stack = stack;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF1E1E22);
        g.centeredText(font, stack.getHoverName(), width / 2, 12, 0xFFE8E0C8);
        int s = Math.max(2, Math.min(width / 3, height / 2) / 16);  // the 16 px icon, scaled up
        g.pose().pushMatrix();
        g.pose().translate(width * 0.27f - 8 * s, height * 0.52f - 8 * s);
        g.pose().scale(s, s);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
        com.nirncraft.inventory.client.SkyItemRender.extract(g, stack, width * 0.72f, height * 0.52f,
                Math.min(width, height) * 0.36f, 18f, 35f);
    }
}
