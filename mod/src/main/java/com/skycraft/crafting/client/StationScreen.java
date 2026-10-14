package com.skycraft.crafting.client;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.CraftingPackets;
import com.skycraft.crafting.StationCrafting;
import com.skycraft.crafting.StationType;
import com.skycraft.crafting.Tempering;
import com.skycraft.crafting.menu.StationMenu;
import com.skycraft.crafting.recipe.Cost;
import com.skycraft.crafting.recipe.SmithingRecipe;
import com.skycraft.crafting.recipe.SmithingRecipes;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Skyrim-style crafting menu shared by every station. Forge / smelter / tanning rack: categories on the left,
 * recipes in the middle, the selected recipe's materials (have/need) and required perk on the right. Grindstone /
 * armor workbench: improvable items from the inventory with their quality, the tempering material and the quality cap.
 */
public class StationScreen extends AbstractContainerScreen<StationMenu> {
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
    private static final Map<StationType, Integer> LAST_CATEGORY = new EnumMap<>(StationType.class);

    private final StationType type;
    private final List<String> categories;
    private int category;
    private int selected;
    private int scroll;
    /** First visible category (the forge lists more materials than fit). */
    private int catScroll;
    private int selectedSlot = -1;
    private List<SmithingRecipe> recipes = List.of();
    private final List<Integer> temperSlots = new ArrayList<>();
    private Button action;
    private ItemStack hoveredStack = ItemStack.EMPTY;

    private int catX, catW, listX, listY, listW, listH, detX, detW;

    public StationScreen(StationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.type = menu.type;
        this.categories = new ArrayList<>(SmithingRecipes.forStation(type).keySet());
        this.category = Mth.clamp(LAST_CATEGORY.getOrDefault(type, 0), 0, Math.max(0, categories.size() - 1));
    }

    // ------------------------------------------------------------------ layout & state

    @Override
    protected void init() {
        imageWidth = Math.min(width - 16, 430);
        imageHeight = Math.min(height - 16, 250);
        super.init();
        boolean temper = type.tempering();
        catX = leftPos + 6;
        catW = temper ? 0 : 78;
        listX = leftPos + 6 + (temper ? 0 : catW + 4);
        listY = topPos + 24;
        listH = imageHeight - 30;
        listW = temper ? Math.min(190, imageWidth / 2 - 6) : Math.min(150, (imageWidth - catW) / 2 - 6);
        detX = listX + listW + 6;
        detW = leftPos + imageWidth - 6 - detX;
        Component label = Component.translatable(temper ? "screen.skycraft.smithing.improve" : "screen.skycraft.smithing.action." + type.name().toLowerCase(java.util.Locale.ROOT));
        action = addRenderableWidget(Button.builder(label, b -> act())
                .bounds(detX + (detW - 96) / 2, topPos + imageHeight - 28, 96, 20).build());
        refresh();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refresh();
    }

    private PlayerData data() {
        return SkyData.get(minecraft.player);
    }

    private void refresh() {
        if (minecraft == null || minecraft.player == null) return;
        Inventory inv = minecraft.player.getInventory();
        if (type.tempering()) {
            temperSlots.clear();
            for (int slot = 0; slot < inv.getContainerSize(); slot++) {
                if (Tempering.accepts(type, inv.getItem(slot))) temperSlots.add(slot);
            }
            // the overflow pack (inventory.Pack): virtual slot ids after the real ones
            List<ItemStack> packed = com.skycraft.inventory.Pack.entries(minecraft.player);
            for (int i = 0; i < packed.size(); i++) {
                if (!packed.get(i).isEmpty() && Tempering.accepts(type, packed.get(i))) temperSlots.add(com.skycraft.inventory.Pack.VIRTUAL_BASE + i);
            }
            if (!temperSlots.contains(selectedSlot)) selectedSlot = temperSlots.isEmpty() ? -1 : temperSlots.get(0);
            scroll = Mth.clamp(scroll, 0, Math.max(0, temperSlots.size() - visibleRows()));
            action.active = selectedSlot >= 0 && Tempering.check(minecraft.player, type, com.skycraft.inventory.Pack.stackAt(minecraft.player, selectedSlot), selectedSlot).ok();
        } else {
            recipes = categories.isEmpty() ? List.of() : SmithingRecipes.forStation(type).getOrDefault(categories.get(category), List.of());
            selected = Mth.clamp(selected, 0, Math.max(0, recipes.size() - 1));
            scroll = Mth.clamp(scroll, 0, Math.max(0, recipes.size() - visibleRows()));
            SmithingRecipe r = selectedRecipe();
            action.active = r != null && perkOk(r) && r.hasMaterials(inv);
        }
    }

    private int visibleRows() {
        return Math.max(1, listH / ROW);
    }

    /** Keeps the selected category in view and returns the first visible one. */
    private int catFirst() {
        int rows = Math.max(1, listH / CAT_ROW);
        if (category < catScroll) catScroll = category;
        if (category >= catScroll + rows) catScroll = category - rows + 1;
        catScroll = Mth.clamp(catScroll, 0, Math.max(0, categories.size() - rows));
        return catScroll;
    }

    private SmithingRecipe selectedRecipe() {
        return selected >= 0 && selected < recipes.size() ? recipes.get(selected) : null;
    }

    private boolean perkOk(SmithingRecipe r) {
        return r.perk() == null || data().hasPerk(r.perk());
    }

    private void act() {
        if (type.tempering()) {
            if (selectedSlot >= 0) SkyNetwork.sendToServer(new CraftingPackets.Temper(selectedSlot));
        } else {
            SmithingRecipe r = selectedRecipe();
            if (r != null) SkyNetwork.sendToServer(new CraftingPackets.Craft(r.id(), Screen.hasShiftDown() ? StationCrafting.MAX_BATCH : 1));
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (!type.tempering()) {
                int first = catFirst();
                for (int i = first; i < categories.size(); i++) {
                    int y = listY + (i - first) * CAT_ROW;
                    if (y + CAT_ROW > listY + listH) break;
                    if (inside(mx, my, catX, y, catW, CAT_ROW - 1)) {
                        if (category != i) {
                            category = i;
                            selected = 0;
                            scroll = 0;
                            LAST_CATEGORY.put(type, i);
                            refresh();
                        }
                        return true;
                    }
                }
            }
            if (inside(mx, my, listX, listY, listW, listH)) {
                int idx = scroll + (int) ((my - listY) / ROW);
                if (type.tempering()) {
                    if (idx >= 0 && idx < temperSlots.size()) selectedSlot = temperSlots.get(idx);
                } else if (idx >= 0 && idx < recipes.size()) {
                    selected = idx;
                }
                refresh();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int size = type.tempering() ? temperSlots.size() : recipes.size();
        if (inside(mx, my, listX, listY, listW, listH)) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, size - visibleRows()));
            return true;
        }
        if (!type.tempering() && inside(mx, my, catX, listY, catW, listH) && !categories.isEmpty()) {
            category = Math.floorMod(category - (int) Math.signum(delta), categories.size());
            selected = 0;
            scroll = 0;
            LAST_CATEGORY.put(type, category);
            refresh();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        int size = type.tempering() ? temperSlots.size() : recipes.size();
        if (size > 0 && (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN)) {
            int dir = key == GLFW.GLFW_KEY_UP ? -1 : 1;
            if (type.tempering()) {
                int idx = Mth.clamp(temperSlots.indexOf(selectedSlot) + dir, 0, size - 1);
                selectedSlot = temperSlots.get(idx);
                ensureVisible(idx);
            } else {
                selected = Mth.clamp(selected + dir, 0, size - 1);
                ensureVisible(selected);
            }
            refresh();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER && action.active) {
            act();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void ensureVisible(int idx) {
        if (idx < scroll) scroll = idx;
        if (idx >= scroll + visibleRows()) scroll = idx - visibleRows() + 1;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        hoveredStack = ItemStack.EMPTY;
        super.render(g, mouseX, mouseY, partialTick);
        if (!hoveredStack.isEmpty()) g.renderTooltip(font, hoveredStack, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // everything is drawn in renderBg
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x0 = leftPos, y0 = topPos, x1 = leftPos + imageWidth, y1 = topPos + imageHeight;
        g.fill(x0, y0, x1, y1, PANEL);
        frame(g, x0, y0, x1, y1, BORDER);
        frame(g, x0 + 2, y0 + 2, x1 - 2, y1 - 2, 0x408A7F66);

        int accentColor = switch (type) {
            case FORGE -> 0xFFD97724; // fiery ember orange
            case SMELTER -> 0xFFE6A23C; // molten bronze
            case TANNING_RACK -> 0xFFA67C52; // tanned hide brown
            case GRINDSTONE -> 0xFF7CA0C0; // whetstone steel blue
            case ARMOR_WORKBENCH -> 0xFF969EA6; // plate iron slate
        };
        String subtitle = switch (type) {
            case FORGE -> "FORGE - WEAPONS & ARMOR";
            case SMELTER -> "SMELTER - INGOTS & ORES";
            case TANNING_RACK -> "TANNING RACK - LEATHERCRAFT";
            case GRINDSTONE -> "GRINDSTONE - WEAPON HONING";
            case ARMOR_WORKBENCH -> "WORKBENCH - ARMOR PLATING";
        };

        // Title and station subtitle
        g.drawCenteredString(font, title, (x0 + x1) / 2, y0 + 6, TEXT);
        g.drawString(font, subtitle, x0 + 10, y0 + 6, accentColor, false);

        // Smithing skill badge on the top right
        String skillText = "Smithing " + data().getSkill(Skill.SMITHING);
        g.drawString(font, skillText, x1 - 10 - font.width(skillText), y0 + 6, GOLD, false);
        // what you carry (crafted gear and bought materials weigh something)
        com.skycraft.inventory.client.SkyUi.carryLine(g, font, (x0 + x1) / 2 + font.width(title) / 2 + 10, y0 + 6,
                x1 - 10 - font.width(skillText) - 14);

        // Station-specific accent trim divider
        g.fill(x0 + 6, y0 + 17, x1 - 6, y0 + 18, accentColor);
        g.fill(x0 + 6, y0 + 18, x1 - 6, y0 + 19, 0x808A7F66);

        // Station motif decorative accents on inner frame
        g.fill(x0 + 2, y0 + 2, x0 + 6, y0 + 4, accentColor);
        g.fill(x1 - 6, y0 + 2, x1 - 2, y0 + 4, accentColor);

        g.fill(listX, listY, listX + listW, listY + listH, INSET);
        if (type.tempering()) {
            renderTemperList(g, mouseX, mouseY);
            renderTemperDetails(g, mouseX, mouseY);
        } else {
            renderCategories(g, mouseX, mouseY);
            renderRecipeList(g, mouseX, mouseY);
            renderRecipeDetails(g, mouseX, mouseY);
        }
    }

    private void renderCategories(GuiGraphics g, int mx, int my) {
        int first = catFirst();
        for (int i = first; i < categories.size(); i++) {
            int y = listY + (i - first) * CAT_ROW;
            if (y + CAT_ROW > listY + listH) break;
            boolean sel = i == category;
            boolean hover = inside(mx, my, catX, y, catW, CAT_ROW - 1);
            if (sel) g.fill(catX, y, catX + catW, y + CAT_ROW - 1, 0x50C8A050);
            else if (hover) g.fill(catX, y, catX + catW, y + CAT_ROW - 1, 0x28F0E6C8);
            if (sel) g.fill(catX, y, catX + 2, y + CAT_ROW - 1, GOLD);
            Component name = Component.translatable("category.skycraft.smithing." + categories.get(i));
            g.drawString(font, fit(name.getString(), catW - 8), catX + 5, y + 2, sel ? TEXT : hover ? TEXT : DIM, false);
        }
    }

    private void renderRecipeList(GuiGraphics g, int mx, int my) {
        Inventory inv = minecraft.player.getInventory();
        if (recipes.isEmpty()) {
            g.drawString(font, Component.translatable("screen.skycraft.smithing.no_recipes"), listX + 4, listY + 4, GREY, false);
            return;
        }
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        int rows = visibleRows() + 1;
        for (int i = scroll; i < Math.min(recipes.size(), scroll + rows); i++) {
            SmithingRecipe r = recipes.get(i);
            int y = listY + (i - scroll) * ROW;
            boolean hover = inside(mx, my, listX, y, listW, ROW) && my < listY + listH;
            if (i == selected) g.fill(listX, y, listX + listW, y + ROW, 0x50C8A050);
            else if (hover) g.fill(listX, y, listX + listW, y + ROW, 0x28F0E6C8);
            ItemStack stack = r.resultStack();
            g.renderItem(stack, listX + 2, y + 1);
            g.renderItemDecorations(font, stack, listX + 2, y + 1);
            int color = !perkOk(r) ? GREY : r.hasMaterials(inv) ? TEXT : DIM;
            g.drawString(font, fit(stack.getHoverName().getString(), listW - 26), listX + 22, y + 5, color, false);
            if (hover && mx < listX + 19) hoveredStack = stack;
        }
        g.disableScissor();
        scrollbar(g, recipes.size());
    }

    private void renderRecipeDetails(GuiGraphics g, int mx, int my) {
        SmithingRecipe r = selectedRecipe();
        if (r == null) return;
        Inventory inv = minecraft.player.getInventory();
        ItemStack result = r.resultStack();
        int y = listY + 2;
        bigItem(g, result, detX + 2, y, mx, my);
        int textX = detX + 40;
        int textW = detW - 42;
        List<FormattedCharSequence> nameLines = font.split(result.getHoverName(), textW);
        int ly = y + (nameLines.size() > 1 ? 4 : 8);
        for (FormattedCharSequence line : nameLines.subList(0, Math.min(2, nameLines.size()))) {
            g.drawString(font, line, textX, ly, TEXT, false);
            ly += 10;
        }
        if (r.count() > 1) g.drawString(font, "x" + r.count(), textX, ly, DIM, false);
        y += 38;
        if (r.perk() != null) {
            boolean ok = perkOk(r);
            Component line = Component.translatable("screen.skycraft.smithing.requires", Tempering.perkName(r.perk()));
            for (FormattedCharSequence seq : font.split(line, detW - 4)) {
                g.drawString(font, seq, detX + 2, y, ok ? GOOD : BAD, false);
                y += 10;
            }
            y += 2;
        }
        g.drawString(font, Component.translatable("screen.skycraft.smithing.materials"), detX + 2, y, GOLD, false);
        y += 11;
        for (Cost cost : r.costs()) {
            int have = cost.countIn(inv, -1);
            y = costLine(g, cost, have, detX + 2, y, mx, my);
        }
        int hintY = topPos + imageHeight - 40;
        if (y < hintY && r.station() != StationType.GRINDSTONE) {
            g.drawCenteredString(font, Component.translatable("screen.skycraft.smithing.shift_hint", StationCrafting.MAX_BATCH), detX + detW / 2, hintY, GREY);
        }
    }

    private int costLine(GuiGraphics g, Cost cost, int have, int x, int y, int mx, int my) {
        ItemStack icon = cost.display().get();
        g.renderItem(icon, x, y);
        if (inside(mx, my, x, y, 16, 16)) hoveredStack = icon;
        String count = have + "/" + cost.count();
        int countW = font.width(count);
        g.drawString(font, fit(icon.getHoverName().getString(), detW - countW - 30), x + 20, y + 4, DIM, false);
        g.drawString(font, count, detX + detW - 4 - countW, y + 4, have >= cost.count() ? GOOD : BAD, false);
        return y + 18;
    }

    private void renderTemperList(GuiGraphics g, int mx, int my) {
        Inventory inv = minecraft.player.getInventory();
        if (temperSlots.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.translatable(type == StationType.GRINDSTONE
                    ? "screen.skycraft.smithing.no_weapons" : "screen.skycraft.smithing.no_armor"), listW - 8);
            int y = listY + 4;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, listX + 4, y, GREY, false);
                y += 10;
            }
            return;
        }
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        int rows = visibleRows() + 1;
        for (int i = scroll; i < Math.min(temperSlots.size(), scroll + rows); i++) {
            int slot = temperSlots.get(i);
            ItemStack stack = com.skycraft.inventory.Pack.stackAt(minecraft.player, slot);
            int y = listY + (i - scroll) * ROW;
            boolean hover = inside(mx, my, listX, y, listW, ROW) && my < listY + listH;
            if (slot == selectedSlot) g.fill(listX, y, listX + listW, y + ROW, 0x50C8A050);
            else if (hover) g.fill(listX, y, listX + listW, y + ROW, 0x28F0E6C8);
            g.renderItem(stack, listX + 2, y + 1);
            int quality = Tempering.quality(stack);
            String q = quality > 0 ? Tempering.qualityName(quality).getString() : "";
            int qW = font.width(q);
            if (slot >= 36 && slot < com.skycraft.inventory.Pack.VIRTUAL_BASE) g.fill(listX + 1, y + 1, listX + 3, y + 3, GOLD); // equipped marker
            g.drawString(font, fit(stack.getHoverName().getString(), listW - 28 - qW), listX + 22, y + 5, TEXT, false);
            if (quality > 0) g.drawString(font, q, listX + listW - 4 - qW, y + 5, GOLD, false);
            if (hover && mx < listX + 19) hoveredStack = stack;
        }
        g.disableScissor();
        scrollbar(g, temperSlots.size());
    }

    private void renderTemperDetails(GuiGraphics g, int mx, int my) {
        if (selectedSlot < 0) return;
        Inventory inv = minecraft.player.getInventory();
        ItemStack stack = com.skycraft.inventory.Pack.stackAt(minecraft.player, selectedSlot);
        if (stack.isEmpty()) return;
        int y = listY + 2;
        bigItem(g, stack, detX + 2, y, mx, my);
        int textX = detX + 40;
        List<FormattedCharSequence> nameLines = font.split(stack.getHoverName(), detW - 42);
        int ly = y + 4;
        for (FormattedCharSequence line : nameLines.subList(0, Math.min(2, nameLines.size()))) {
            g.drawString(font, line, textX, ly, TEXT, false);
            ly += 10;
        }
        int quality = Tempering.quality(stack);
        g.drawString(font, quality > 0 ? Tempering.qualityName(quality) : Component.translatable("quality.skycraft.0"), textX, ly, GOLD, false);
        y += 40;

        Tempering.Check check = Tempering.check(minecraft.player, type, stack, selectedSlot);
        int max = Tempering.maxQuality(minecraft.player, stack);
        g.drawString(font, Component.translatable("screen.skycraft.smithing.skill_cap",
                max > 0 ? Tempering.qualityName(max) : Component.translatable("quality.skycraft.0"),
                data().getSkill(Skill.SMITHING)), detX + 2, y, DIM, false);
        y += 12;
        if (check.ok()) {
            Component next = Tempering.qualityName(quality + check.gain());
            g.drawString(font, Component.translatable("screen.skycraft.smithing.next_quality", next), detX + 2, y, GOOD, false);
            y += 12;
        }
        String perk = Tempering.temperPerk(stack);
        boolean hasPerk = data().hasPerk(perk);
        for (FormattedCharSequence seq : font.split(Component.translatable(hasPerk ? "screen.skycraft.smithing.perk_double" : "screen.skycraft.smithing.perk_hint",
                Tempering.perkName(perk)), detW - 4)) {
            g.drawString(font, seq, detX + 2, y, hasPerk ? GOOD : GREY, false);
            y += 10;
        }
        y += 4;
        Cost cost = Tempering.material(stack);
        if (cost != null) {
            g.drawString(font, Component.translatable("screen.skycraft.smithing.materials"), detX + 2, y, GOLD, false);
            y += 11;
            y = costLine(g, cost, cost.countIn(inv, selectedSlot), detX + 2, y, mx, my);
        }
        if (!check.ok() && check.reason() != null) {
            for (FormattedCharSequence seq : font.split(check.reason(), detW - 4)) {
                if (y > topPos + imageHeight - 40) break;
                g.drawString(font, seq, detX + 2, y + 2, BAD, false);
                y += 10;
            }
        }
    }

    private void bigItem(GuiGraphics g, ItemStack stack, int x, int y, int mx, int my) {
        g.fill(x, y, x + 34, y + 34, INSET);
        frame(g, x, y, x + 34, y + 34, 0x808A7F66);
        g.pose().pushPose();
        g.pose().translate(x + 1, y + 1, 0);
        g.pose().scale(2f, 2f, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
        if (inside(mx, my, x, y, 34, 34)) hoveredStack = stack;
    }

    private void scrollbar(GuiGraphics g, int size) {
        int rows = visibleRows();
        if (size <= rows) return;
        int x = listX + listW - 3;
        g.fill(x, listY, x + 2, listY + listH, 0x40FFFFFF);
        int barH = Math.max(10, listH * rows / size);
        int barY = listY + (listH - barH) * scroll / Math.max(1, size - rows);
        g.fill(x, barY, x + 2, barY + barH, BORDER);
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
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
