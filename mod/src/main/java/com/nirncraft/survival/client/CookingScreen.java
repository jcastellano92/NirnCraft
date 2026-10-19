package com.nirncraft.survival.client;

import com.nirncraft.crafting.recipe.Cost;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.survival.SurvivalPackets;
import com.nirncraft.survival.cooking.Cooking;
import com.nirncraft.survival.cooking.CookingMenu;
import com.nirncraft.survival.cooking.CookingRecipe;
import com.nirncraft.survival.cooking.CookingRecipes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/**
 * Skyrim's cooking menu: categories (soups & stews, meat & fish, baking, drinks) on the left, dishes in the middle
 * (bright when you have the ingredients), the selected dish's ingredients with have/need counts on the right.
 */
public class CookingScreen extends AbstractContainerScreen<CookingMenu> {
    private static final int TEXT = 0xFFF5EBC8;
    private static final int DIM = 0xFFBDB59E;
    private static final int GREY = 0xFF7E786C;
    private static final int GOOD = 0xFF90C090;
    private static final int BAD = 0xFFD07070;
    private static final int GOLD = 0xFFE0B040;
    private static final int BORDER = 0xFF8A7F66;
    private static final int PANEL = 0xE8100E0B;
    private static final int INSET = 0x60000000;
    private static final int ROW = 18;
    private static final int CAT_ROW = 13;
    private static int lastCategory;

    private final List<String> categories = CookingRecipes.CATEGORIES;
    private int category;
    private int selected;
    private int scroll;
    private List<CookingRecipe> recipes = List.of();
    private Button action;
    private ItemStack hoveredStack = ItemStack.EMPTY;
    private int catX, catW, listX, listY, listW, listH, detX, detW;

    public CookingScreen(CookingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Math.min(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth() - 16, 420), Math.min(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight() - 16, 240));
        this.category = Mth.clamp(lastCategory, 0, categories.size() - 1);
    }

    @Override
    protected void init() {


        super.init();
        catX = leftPos + 6;
        catW = 84;
        listX = catX + catW + 4;
        listY = topPos + 24;
        listH = imageHeight - 30;
        listW = Math.min(150, (imageWidth - catW) / 2 - 6);
        detX = listX + listW + 6;
        detW = leftPos + imageWidth - 6 - detX;
        action = addRenderableWidget(Button.builder(Component.translatable("screen.nirncraft.cooking.cook"), b -> act())
                .bounds(detX + (detW - 96) / 2, topPos + imageHeight - 28, 96, 20).build());
        refresh();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refresh();
    }

    private void refresh() {
        if (minecraft == null || minecraft.player == null) return;
        recipes = CookingRecipes.byCategory().getOrDefault(categories.get(category), List.of());
        selected = Mth.clamp(selected, 0, Math.max(0, recipes.size() - 1));
        scroll = Mth.clamp(scroll, 0, Math.max(0, recipes.size() - visibleRows()));
        CookingRecipe r = selectedRecipe();
        action.active = r != null && r.hasMaterials(minecraft.player.getInventory());
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW);
    }

    private CookingRecipe selectedRecipe() {
        return selected >= 0 && selected < recipes.size() ? recipes.get(selected) : null;
    }

    private void act() {
        CookingRecipe r = selectedRecipe();
        if (r != null) SkyNetwork.sendToServer(new SurvivalPackets.Cook(r.id(), com.nirncraft.client.screen.UiInput.shiftDown() ? Cooking.MAX_BATCH : 1));
    }

    private void selectCategory(int i) {
        if (category == i) return;
        category = i;
        selected = 0;
        scroll = 0;
        lastCategory = i;
        refresh();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) {
            for (int i = 0; i < categories.size(); i++) {
                if (inside(mx, my, catX, listY + i * CAT_ROW, catW, CAT_ROW - 1)) {
                    selectCategory(i);
                    return true;
                }
            }
            if (inside(mx, my, listX, listY, listW, listH)) {
                int idx = scroll + (int) ((my - listY) / ROW);
                if (idx >= 0 && idx < recipes.size()) selected = idx;
                refresh();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (inside(mx, my, listX, listY, listW, listH)) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, recipes.size() - visibleRows()));
            return true;
        }
        if (inside(mx, my, catX, listY, catW, listH)) {
            selectCategory(Math.floorMod(category - (int) Math.signum(delta), categories.size()));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, delta);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (!recipes.isEmpty() && (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN)) {
            selected = Mth.clamp(selected + (key == InputConstants.KEY_UP ? -1 : 1), 0, recipes.size() - 1);
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + visibleRows()) scroll = selected - visibleRows() + 1;
            refresh();
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
            selectCategory(Math.floorMod(category + (key == InputConstants.KEY_LEFT ? -1 : 1), categories.size()));
            return true;
        }
        if (key == InputConstants.KEY_RETURN && action.active) {
            act();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        hoveredStack = ItemStack.EMPTY;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (!hoveredStack.isEmpty()) g.setTooltipForNextFrame(font, hoveredStack, mouseX, mouseY);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // everything is drawn in renderBg
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x0 = leftPos, y0 = topPos, x1 = leftPos + imageWidth, y1 = topPos + imageHeight;
        g.fill(x0, y0, x1, y1, PANEL);
        frame(g, x0, y0, x1, y1, BORDER);
        frame(g, x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0x408A7F66);
        g.centeredText(font, title, (x0 + x1) / 2, y0 + 7, TEXT);
        com.nirncraft.inventory.client.SkyUi.carryLine(g, font, (x0 + x1) / 2 + font.width(title) / 2 + 10, y0 + 7, x1 - 10);
        g.fill(x0 + 6, y0 + 18, x1 - 6, y0 + 19, 0x808A7F66);
        g.fill(listX, listY, listX + listW, listY + listH, INSET);
        renderCategories(g, mouseX, mouseY);
        renderList(g, mouseX, mouseY);
        renderDetails(g, mouseX, mouseY);
    }

    private void renderCategories(GuiGraphicsExtractor g, int mx, int my) {
        for (int i = 0; i < categories.size(); i++) {
            int y = listY + i * CAT_ROW;
            boolean sel = i == category;
            boolean hover = inside(mx, my, catX, y, catW, CAT_ROW - 1);
            if (sel) g.fill(catX, y, catX + catW, y + CAT_ROW - 1, 0x50C8A050);
            else if (hover) g.fill(catX, y, catX + catW, y + CAT_ROW - 1, 0x28F0E6C8);
            if (sel) g.fill(catX, y, catX + 2, y + CAT_ROW - 1, GOLD);
            Component name = Component.translatable("category.nirncraft.cooking." + categories.get(i));
            g.text(font, fit(name.getString(), catW - 8), catX + 5, y + 2, sel || hover ? TEXT : DIM, false);
        }
    }

    private void renderList(GuiGraphicsExtractor g, int mx, int my) {
        Inventory inv = minecraft.player.getInventory();
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        int rows = visibleRows() + 1;
        for (int i = scroll; i < Math.min(recipes.size(), scroll + rows); i++) {
            CookingRecipe r = recipes.get(i);
            int y = listY + (i - scroll) * ROW;
            boolean hover = inside(mx, my, listX, y, listW, ROW) && my < listY + listH;
            if (i == selected) g.fill(listX, y, listX + listW, y + ROW, 0x50C8A050);
            else if (hover) g.fill(listX, y, listX + listW, y + ROW, 0x28F0E6C8);
            ItemStack stack = r.resultStack();
            g.item(stack, listX + 2, y + 1);
            g.itemDecorations(font, stack, listX + 2, y + 1);
            g.text(font, fit(stack.getHoverName().getString(), listW - 26), listX + 22, y + 5, r.hasMaterials(inv) ? TEXT : GREY, false);
            if (hover && mx < listX + 19) hoveredStack = stack;
        }
        g.disableScissor();
        int visible = visibleRows();
        if (recipes.size() > visible) {
            int x = listX + listW - 3;
            g.fill(x, listY, x + 2, listY + listH, 0x40FFFFFF);
            int barH = Math.max(10, listH * visible / recipes.size());
            int barY = listY + (listH - barH) * scroll / Math.max(1, recipes.size() - visible);
            g.fill(x, barY, x + 2, barY + barH, BORDER);
        }
    }

    private void renderDetails(GuiGraphicsExtractor g, int mx, int my) {
        CookingRecipe r = selectedRecipe();
        if (r == null) return;
        Inventory inv = minecraft.player.getInventory();
        ItemStack result = r.resultStack();
        int y = listY + 2;
        int bx = detX + 2;
        g.fill(bx, y, bx + 34, y + 34, INSET);
        frame(g, bx, y, bx + 34, y + 34, 0x808A7F66);
        g.pose().pushMatrix();
        g.pose().translate(bx + 1, y + 1);
        g.pose().scale(2f, 2f);
        g.item(result, 0, 0);
        g.pose().popMatrix();
        if (inside(mx, my, bx, y, 34, 34)) hoveredStack = result;
        int textX = detX + 40;
        List<FormattedCharSequence> nameLines = font.split(result.getHoverName(), detW - 42);
        int ly = y + (nameLines.size() > 1 ? 4 : 8);
        for (FormattedCharSequence line : nameLines.subList(0, Math.min(2, nameLines.size()))) {
            g.text(font, line, textX, ly, TEXT, false);
            ly += 10;
        }
        if (r.count() > 1) g.text(font, "x" + r.count(), textX, ly, DIM, false);
        y += 40;
        g.text(font, Component.translatable("screen.nirncraft.cooking.ingredients"), detX + 2, y, GOLD, false);
        y += 11;
        for (Cost cost : r.costs()) {
            ItemStack icon = cost.display().get();
            int have = cost.countIn(inv, -1);
            g.item(icon, detX + 2, y);
            if (inside(mx, my, detX + 2, y, 16, 16)) hoveredStack = icon;
            String count = have + "/" + cost.count();
            int countW = font.width(count);
            g.text(font, fit(icon.getHoverName().getString(), detW - countW - 30), detX + 22, y + 4, DIM, false);
            g.text(font, count, detX + detW - 4 - countW, y + 4, have >= cost.count() ? GOOD : BAD, false);
            y += 18;
        }
        int hintY = topPos + imageHeight - 40;
        if (y < hintY) {
            g.centeredText(font, Component.translatable("screen.nirncraft.cooking.shift_hint", Cooking.MAX_BATCH), detX + detW / 2, hintY, GREY);
        }
    }

    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    private String fit(String text, int width) {
        if (width <= 0) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }
}
