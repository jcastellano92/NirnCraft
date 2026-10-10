package com.skycraft.inventory.client;

import com.google.common.collect.Multimap;
import com.mojang.datafixers.util.Pair;
import com.mojang.math.Axis;
import com.skycraft.combat.ArmorClass;
import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Currency;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.inventory.InvCategory;
import com.skycraft.inventory.InventoryActions;
import com.skycraft.inventory.InventoryPackets;
import com.skycraft.inventory.ItemWeights;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Skyrim inventory (replaces the vanilla survival inventory): categories on the left, the item list in the
 * middle, a large slowly turning model of the selected item with its stats on the right, and armor rating, carry
 * weight, gold and vitals along the bottom. Every action is a request the server validates.
 *
 * <p>Keys: W/S select, A/D category, E equip/use/read, right-click equip left, Q drop (Shift: all), F favorite,
 * R sort, C vanilla inventory (crafting grid, armor slots), Tab/Esc close.</p>
 */
public class SkyrimInventoryScreen extends Screen {
    private static final int ROW = 18;
    private static final int CAT_ROW = 15;
    private static final int BOTTOM_BAR = 30;
    private static final int MARGIN = 8;

    static final int SORT_NAME = 0, SORT_WEIGHT = 1, SORT_VALUE = 2;

    // remembered between openings, like Skyrim keeps your last tab
    private static InvCategory lastCategory = InvCategory.ALL;
    private static int sortMode = SORT_NAME;
    private static boolean sortDescending = false;

    private InvCategory category = lastCategory;
    private List<InvEntry> shown = new ArrayList<>();
    private final int[] counts = new int[InvCategory.VALUES.length];
    private int selected = -1;
    private int scroll;
    private InvEntry selectedEntry;

    // 3D inspect view
    private boolean inspectMode = false;
    private float inspectRotX = 15f;
    private float inspectRotY = 0f;
    private float inspectZoom = 1.0f;
    private long inspectLastFrame;
    private long inspectLastDrag;

    // layout
    private int x0, contentW, catX, catW, listX, listW, detX, detW, top, bottom, listTop;

    // hover state, computed while rendering
    private int hoverCat = -1;
    private int hoverRow = -1;
    private int hoverSort = -1;
    private int hoverAction = -1;
    private boolean hoverCrafting;
    private boolean hoverPreview;
    private Component hoverText;
    private final List<ActionButton> actions = new ArrayList<>();
    private long lastClick;
    private int lastClickRow = -1;

    private record ActionButton(int x, int y, int w, int h, Component label, String key, Runnable run) {}

    public SkyrimInventoryScreen() {
        super(Component.translatable("inventory.skycraft.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ layout & data

    @Override
    protected void init() {
        contentW = Math.min(width - 2 * MARGIN, 760);
        x0 = (width - contentW) / 2;
        top = 10;
        bottom = height - BOTTOM_BAR - 6;
        int labelW = 0;
        for (InvCategory c : InvCategory.VALUES) labelW = Math.max(labelW, font.width(c.displayName()));
        catW = Mth.clamp(labelW + 34, 70, Math.max(70, contentW / 4));
        catX = x0;
        int rest = contentW - catW - 12;
        listW = Math.max(120, (int) (rest * 0.56f));
        listX = catX + catW + 6;
        detX = listX + listW + 6;
        detW = x0 + contentW - detX;
        listTop = top + 16;
        rebuild();
    }

    private int visibleRows() {
        return Math.max(1, (bottom - listTop - 2) / ROW);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, shown.size() - visibleRows()));
    }

    private void ensureVisible() {
        if (selected < 0) return;
        int rows = visibleRows();
        if (selected < scroll) scroll = selected;
        else if (selected >= scroll + rows) scroll = selected - rows + 1;
        clampScroll();
    }

    @Override
    public void tick() {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null || !player.isAlive()) {
            onClose();
            return;
        }
        rebuild();
    }

    /** Re-reads the inventory (every tick: the server may have changed it), keeping the selected row. */
    private void rebuild() {
        if (minecraft == null || minecraft.player == null) return;
        InvEntry previous = selectedEntry;
        List<InvEntry> all = InvEntry.build(minecraft.player);
        Arrays.fill(counts, 0);
        for (InvEntry e : all) {
            counts[InvCategory.ALL.ordinal()]++;
            counts[e.category.ordinal()]++;
            if (e.favorite) counts[InvCategory.FAVORITES.ordinal()]++;
        }
        List<InvEntry> list = new ArrayList<>();
        for (InvEntry e : all) if (category.matches(e.stack)) list.add(e);
        list.sort(comparator());
        shown = list;

        int idx = -1;
        if (previous != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).sameAs(previous)) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) idx = Math.min(Math.max(selected, 0), shown.size() - 1);
        selected = shown.isEmpty() ? -1 : idx;
        selectedEntry = selected >= 0 ? shown.get(selected) : null;
        clampScroll();
    }

    private static Comparator<InvEntry> comparator() {
        Comparator<InvEntry> c = switch (sortMode) {
            case SORT_WEIGHT -> Comparator.comparingDouble(e -> e.weight);
            case SORT_VALUE -> Comparator.comparingInt(e -> e.value);
            default -> Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER);
        };
        if (sortDescending) c = c.reversed();
        return c.thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(e -> e.equip)
                .thenComparingInt(InvEntry::primarySlot);
    }

    private void select(int index) {
        if (shown.isEmpty()) return;
        selected = Mth.clamp(index, 0, shown.size() - 1);
        selectedEntry = shown.get(selected);
        ensureVisible();
    }

    private void move(int delta) {
        if (shown.isEmpty()) return;
        if (selected < 0) select(0);
        else select(selected + delta);
    }

    private void setCategory(InvCategory c) {
        if (c == category) return;
        category = c;
        lastCategory = c;
        selected = 0;
        selectedEntry = null;
        scroll = 0;
        rebuild();
        if (!shown.isEmpty()) select(0);
    }

    private void cycleCategory(int dir) {
        int n = InvCategory.VALUES.length;
        setCategory(InvCategory.VALUES[Math.floorMod(category.ordinal() + dir, n)]);
    }

    private void setSort(int mode) {
        if (sortMode == mode) {
            sortDescending = !sortDescending;
        } else {
            sortMode = mode;
            sortDescending = mode != SORT_NAME; // heaviest / most valuable first
        }
        rebuild();
        ensureVisible();
    }

    private void openVanilla() {
        if (minecraft == null || minecraft.player == null) return;
        InventoryClientEvents.allowVanillaOnce();
        minecraft.setScreen(new InventoryScreen(minecraft.player));
    }

    @Override
    public void onClose() {
        lastCategory = category;
        super.onClose();
    }

    // ------------------------------------------------------------------ actions

    private void primary() {
        if (selectedEntry != null) ClientInventoryHandlers.primary(selectedEntry);
    }

    private void secondary() {
        if (selectedEntry != null) ClientInventoryHandlers.secondary(selectedEntry);
    }

    private void drop(boolean all) {
        if (selectedEntry == null) return;
        boolean whole = all || selectedEntry.count() == 1;
        ClientInventoryHandlers.send(whole ? InventoryPackets.InvAction.DROP_ALL : InventoryPackets.InvAction.DROP_ONE, selectedEntry);
    }

    private void favorite() {
        if (selectedEntry != null) ClientInventoryHandlers.send(InventoryPackets.InvAction.FAVORITE, selectedEntry);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (inspectMode && selectedEntry != null) {
            renderInspectOverlay(g, mouseX, mouseY);
            return;
        }

        g.fillGradient(0, 0, width, height, 0xA8050505, 0xD8050505);
        hoverText = null;
        drawCategories(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);
        drawDetails(g, mouseX, mouseY);
        drawBottomBar(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);

        if (hoverPreview && selectedEntry != null) {
            g.renderTooltip(font, selectedEntry.icon, mouseX, mouseY);
        } else if (hoverText != null) {
            g.renderTooltip(font, hoverText, mouseX, mouseY);
        }
    }

    private void renderInspectOverlay(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, height, 0xEE050505);
        long now = Util.getMillis();
        SkyUi.smoke(g, width, height, (now % 1_000_000L) / 1000f, 0.8f);
        int cx = width / 2;
        int cy = height / 2 + 6;

        // like Skyrim, the item keeps turning slowly until you take hold of it
        float dt = Mth.clamp((now - inspectLastFrame) / 1000f, 0f, 0.1f);
        inspectLastFrame = now;
        if (now - inspectLastDrag > 1500) inspectRotY += dt * 20f;

        // the item-frame display draws an item one block wide: this fills ~55% of the shorter screen side
        float scale = Math.min(width, height) * 0.55f * inspectZoom;
        int poolY = cy + (int) (Math.min(width, height) * 0.32f);
        for (int i = 5; i > 0; i--) {
            int w = (int) (Math.min(width, height) * 0.05f * i);
            g.fill(cx - w, poolY, cx + w, poolY + 2, 0x0CE8D8B0);
        }
        SkyUi.item3d(g, selectedEntry.stack, cx, cy, scale, inspectRotX, inspectRotY);

        String title = SkyUi.caps(Component.literal(selectedEntry.name));
        SkyUi.scaledCentered(g, font, title, cx, 16, 1.3f, SkyUi.BRIGHT, true);
        String kind = kindLine(selectedEntry).getString();
        g.drawString(font, kind, cx - font.width(kind) / 2, 32, SkyUi.DIM, false);
        SkyUi.ornament(g, cx, 46, Math.min(100, width / 4), 0xC0C8BC9A);

        String hint = "[Drag] Rotate   [Scroll] Zoom   [X / Esc] Exit Inspect";
        g.drawString(font, hint, cx - font.width(hint) / 2, height - 20, 0xB0A89F86, false);
    }

    private void drawCategories(GuiGraphics g, int mx, int my) {
        SkyUi.panel(g, catX, top, catX + catW, bottom);
        hoverCat = -1;
        int y = top + 5;
        for (InvCategory c : InvCategory.VALUES) {
            int n = counts[c.ordinal()];
            boolean sel = c == category;
            boolean hov = SkyUi.inside(mx, my, catX, y, catX + catW, y + CAT_ROW);
            if (hov) hoverCat = c.ordinal();
            if (sel) {
                g.fill(catX + 1, y, catX + catW - 1, y + CAT_ROW, 0x30FFFFFF);
                g.fill(catX + 1, y, catX + 3, y + CAT_ROW, SkyUi.LINE);
            } else if (hov) {
                g.fill(catX + 1, y, catX + catW - 1, y + CAT_ROW, 0x18FFFFFF);
            }
            int color = sel ? SkyUi.BRIGHT : n == 0 ? 0xFF5E5A52 : hov ? SkyUi.TEXT : 0xFFB8B0A0;
            Component name = c.displayName();
            g.drawString(font, name, catX + 8, y + 4, color, false);
            if (c == InvCategory.FAVORITES) SkyUi.star(g, catX + 8 + font.width(name) + 4, y + 5, sel ? SkyUi.GOLD : SkyUi.TRIM);
            if (n > 0) {
                String count = String.valueOf(n);
                g.drawString(font, count, catX + catW - 6 - font.width(count), y + 4, sel ? SkyUi.HEADER : 0xFF6E6A60, false);
            }
            y += CAT_ROW;
            if (c == InvCategory.ALL) {
                SkyUi.hline(g, catX + 4, catX + catW - 4, y + 1, 0x60);
                y += 3;
            }
        }

        // "Crafting" opens the vanilla inventory (crafting grid, armor slots, modded slots)
        int by = Math.max(y + 6, bottom - 19);
        int bx1 = catX + 4, bx2 = catX + catW - 4;
        hoverCrafting = SkyUi.inside(mx, my, bx1, by, bx2, by + 14) && by + 14 <= bottom;
        if (by + 14 <= bottom) {
            g.fill(bx1, by, bx2, by + 14, hoverCrafting ? 0x40FFFFFF : 0x1EFFFFFF);
            SkyUi.hline(g, bx1, bx2, by, hoverCrafting ? 0xC0 : 0x70);
            SkyUi.hline(g, bx1, bx2, by + 13, hoverCrafting ? 0xC0 : 0x70);
            String label = SkyUi.caps(Component.translatable("inventory.skycraft.crafting"));
            String key = "C";
            int total = font.width(label) + 5 + font.width(key);
            int lx = (bx1 + bx2) / 2 - total / 2;
            g.drawString(font, label, lx, by + 3, hoverCrafting ? SkyUi.BRIGHT : SkyUi.TEXT, false);
            g.drawString(font, key, lx + font.width(label) + 5, by + 3, SkyUi.DIM, false);
            if (hoverCrafting) hoverText = Component.translatable("inventory.skycraft.crafting.tooltip");
        }
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        SkyUi.panel(g, listX, top, listX + listW, bottom);
        int valRight = listX + listW - 8;
        int wgtRight = valRight - 36;
        int nameX = listX + 30;
        int nameEnd = wgtRight - 30;

        // column headers (click to sort)
        hoverSort = -1;
        int hy = top + 5;
        Component[] headers = {Component.translatable("inventory.skycraft.column.name"),
                Component.translatable("inventory.skycraft.column.weight"), Component.translatable("inventory.skycraft.column.value")};
        int[] hx = new int[3];
        String[] hs = new String[3];
        for (int i = 0; i < 3; i++) hs[i] = SkyUi.caps(headers[i]);
        hx[0] = nameX;
        hx[1] = wgtRight - font.width(hs[1]);
        hx[2] = valRight - font.width(hs[2]);
        for (int i = 0; i < 3; i++) {
            int x1 = i == 0 ? listX + 2 : hx[i] - 8;
            int x2 = i == 0 ? nameEnd : hx[i] + font.width(hs[i]) + 2;
            boolean hov = SkyUi.inside(mx, my, x1, hy - 3, x2, hy + 10);
            if (hov) hoverSort = i;
            boolean active = sortMode == i;
            g.drawString(font, hs[i], hx[i], hy, active ? SkyUi.LINE : hov ? SkyUi.TEXT : SkyUi.HEADER, false);
            if (active) SkyUi.triangle(g, hx[i] - 5, hy + 2, !sortDescending, SkyUi.LINE);
        }
        SkyUi.hline(g, listX + 2, listX + listW - 2, listTop - 2, 0x90);

        hoverRow = -1;
        if (shown.isEmpty()) {
            Component empty = Component.translatable("inventory.skycraft.empty");
            g.drawCenteredString(font, empty, listX + listW / 2, listTop + 10, SkyUi.DIM);
            return;
        }
        int rows = visibleRows();
        for (int i = 0; i < rows && scroll + i < shown.size(); i++) {
            int idx = scroll + i;
            InvEntry e = shown.get(idx);
            int y = listTop + i * ROW;
            boolean sel = idx == selected;
            boolean hov = SkyUi.inside(mx, my, listX, y, listX + listW - 4, y + ROW);
            if (hov) hoverRow = idx;
            if (sel) {
                g.fill(listX + 1, y, listX + listW - 1, y + ROW, 0x34FFFFFF);
                SkyUi.hline(g, listX + 1, listX + listW - 1, y, 0x80);
                SkyUi.hline(g, listX + 1, listX + listW - 1, y + ROW - 1, 0x80);
            } else if (hov) {
                g.fill(listX + 1, y, listX + listW - 1, y + ROW, 0x16FFFFFF);
            }

            // equipped marker: gold diamond = right hand / worn, hollow blue diamond = left hand
            int my0 = y + ROW / 2;
            if (e.equip == InvEntry.RIGHT || e.equip == InvEntry.WORN) SkyUi.diamond(g, listX + 7, my0, 2, SkyUi.GOLD);
            else if (e.equip == InvEntry.LEFT) SkyUi.diamondOutline(g, listX + 7, my0, 2, SkyUi.LEFT_HAND);

            g.renderItem(e.icon, listX + 12, y + 1);
            g.renderItemDecorations(font, e.icon, listX + 12, y + 1);

            String count = e.count() > 1 ? " (" + e.count() + ")" : "";
            int room = nameEnd - nameX - font.width(count) - (e.favorite ? 9 : 0);
            String name = SkyUi.ellipsize(font, e.name, Math.max(12, room));
            int nameColor = e.stolen ? SkyUi.STOLEN : sel ? SkyUi.BRIGHT : SkyUi.TEXT;
            g.drawString(font, name, nameX, y + 5, nameColor, false);
            int nx = nameX + font.width(name);
            if (!count.isEmpty()) {
                g.drawString(font, count, nx, y + 5, SkyUi.DIM, false);
                nx += font.width(count);
            }
            if (e.favorite) SkyUi.star(g, nx + 3, y + 6, SkyUi.GOLD);

            String w = ItemWeights.format(e.weight);
            g.drawString(font, w, wgtRight - font.width(w), y + 5, sel ? SkyUi.TEXT : 0xFFB0A898, false);
            String v = String.valueOf(e.value);
            g.drawString(font, v, valRight - font.width(v), y + 5, sel ? SkyUi.GOLD : 0xFFC0A050, false);
        }

        // scrollbar
        if (shown.size() > rows) {
            int trackTop = listTop, trackBottom = listTop + rows * ROW;
            int trackH = trackBottom - trackTop;
            int barH = Math.max(10, trackH * rows / shown.size());
            int barY = trackTop + (trackH - barH) * scroll / Math.max(1, shown.size() - rows);
            g.fill(listX + listW - 3, trackTop, listX + listW - 1, trackBottom, 0x30FFFFFF);
            g.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0xC0C8BC9A);
        }
    }

    private void drawDetails(GuiGraphics g, int mx, int my) {
        SkyUi.panel(g, detX, top, detX + detW, bottom);
        actions.clear();
        hoverAction = -1;
        hoverPreview = false;
        InvEntry e = selectedEntry;
        if (e == null || detW < 60) return;
        int cx = detX + detW / 2;
        ItemStack stack = e.stack;

        // ---------------------------------------------------------- the item itself, slowly turning
        int previewH = Mth.clamp(detW * 45 / 100, 40, 84);
        previewH = Math.min(previewH, (bottom - top) / 3);
        int py = top + 6 + previewH / 2;
        glow(g, cx, py, previewH / 2 + 6);
        renderPreview(g, e.icon, cx, py, previewH / 16f * 0.8f);
        hoverPreview = SkyUi.inside(mx, my, cx - previewH / 2, top + 4, cx + previewH / 2, top + 8 + previewH);

        int y = top + 10 + previewH;
        int textW = detW - 12;

        // name and kind
        Integer rarity = stack.getRarity().color.getColor();
        int nameColor = e.stolen ? SkyUi.STOLEN : rarity != null && stack.getRarity() != net.minecraft.world.item.Rarity.COMMON
                ? 0xFF000000 | rarity : SkyUi.BRIGHT;
        String name = SkyUi.ellipsize(font, e.name, textW);
        g.drawString(font, name, cx - font.width(name) / 2, y, nameColor, false);
        y += 11;
        String kind = SkyUi.ellipsize(font, kindLine(e).getString(), textW);
        g.drawString(font, kind, cx - font.width(kind) / 2, y, SkyUi.DIM, false);
        y += 11;
        SkyUi.divider(g, cx, y + 1, detW / 2 - 8);
        y += 6;

        // ---------------------------------------------------------- stat cells: Damage/Armor | Weight | Value
        List<Component> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<Integer> colorsList = new ArrayList<>();
        double damage = damage(stack);
        double armor = armor(stack);
        if (armor > 0) {
            labels.add(Component.translatable("inventory.skycraft.stat.armor"));
            EquipmentSlot slot = InventoryActions.equipSlotFor(stack);
            double curArmor = slot != null && minecraft != null && minecraft.player != null ? armor(minecraft.player.getItemBySlot(slot)) : 0;
            double diff = armor - curArmor;
            String val = fmt(armor);
            int col = SkyUi.BRIGHT;
            if (curArmor > 0 && Math.abs(diff) >= 0.1) {
                val += (diff > 0 ? " (+" : " (") + fmt(diff) + ")";
                col = diff > 0 ? 0xFF70D070 : 0xFFD07070;
            }
            values.add(val);
            colorsList.add(col);
        } else if (damage > 1) {
            labels.add(Component.translatable("inventory.skycraft.stat.damage"));
            double curDamage = minecraft != null && minecraft.player != null ? damage(minecraft.player.getMainHandItem()) : 0;
            double diff = damage - curDamage;
            String val = fmt(damage);
            int col = SkyUi.BRIGHT;
            if (curDamage > 1 && Math.abs(diff) >= 0.1) {
                val += (diff > 0 ? " (+" : " (") + fmt(diff) + ")";
                col = diff > 0 ? 0xFF70D070 : 0xFFD07070;
            }
            values.add(val);
            colorsList.add(col);
        }
        labels.add(Component.translatable("inventory.skycraft.stat.weight"));
        values.add(ItemWeights.format(e.weight));
        colorsList.add(SkyUi.BRIGHT);
        labels.add(Component.translatable("inventory.skycraft.stat.value"));
        values.add(String.valueOf(e.value));
        colorsList.add(SkyUi.GOLD);

        int cellW = (detW - 8) / labels.size();
        for (int i = 0; i < labels.size(); i++) {
            int ccx = detX + 4 + cellW * i + cellW / 2;
            String l = SkyUi.ellipsize(font, SkyUi.caps(labels.get(i)), cellW - 2);
            g.drawString(font, l, ccx - font.width(l) / 2, y + 2, SkyUi.HEADER, false);
            String v = values.get(i);
            g.drawString(font, v, ccx - font.width(v) / 2, y + 13, colorsList.get(i), false);
            if (i > 0) g.fill(detX + 4 + cellW * i, y + 2, detX + 5 + cellW * i, y + 21, 0x40C8BC9A);
        }
        y += 25;
        SkyUi.hline(g, detX + 6, detX + detW - 6, y, 0x60);
        y += 5;

        // ---------------------------------------------------------- action buttons (bottom of the panel)
        layoutActions(e, mx, my);
        int actionsTop = bottom - 4;
        for (ActionButton b : actions) actionsTop = Math.min(actionsTop, b.y());
        actionsTop -= 4;

        // ---------------------------------------------------------- status & description lines
        List<FormattedCharSequence> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int quality = quality(stack);
        if (quality > 0) addLine(lines, colors, Component.translatable("quality.skycraft." + quality), 0xFF8FC0FF, textW);
        if (e.equip != InvEntry.NONE) {
            String key = e.equip == InvEntry.RIGHT ? "right" : e.equip == InvEntry.LEFT ? "left" : "worn";
            addLine(lines, colors, Component.translatable("inventory.skycraft.equipped." + key), SkyUi.GOLD, textW);
        }
        if (e.stolen) addLine(lines, colors, Component.translatable("inventory.skycraft.stolen"), SkyUi.STOLEN, textW);
        if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
            int pct = Math.round(100f * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage());
            addLine(lines, colors, Component.translatable("inventory.skycraft.condition", pct), pct < 25 ? SkyUi.BAD : SkyUi.DIM, textW);
        }
        if (e.count() > 1 && e.weight > 0) {
            addLine(lines, colors, Component.translatable("inventory.skycraft.total_weight", ItemWeights.format(e.weight * e.count())), SkyUi.DIM, textW);
        }
        for (Component c : description(e)) addLine(lines, colors, c, 0xFFCFC7B2, textW);

        for (int i = 0; i < lines.size(); i++) {
            if (y + 9 > actionsTop) {
                g.drawString(font, "...", detX + 6, y - 2, SkyUi.DIM, false);
                break;
            }
            g.drawString(font, lines.get(i), detX + 6, y, colors.get(i), false);
            y += 10;
        }

        for (int i = 0; i < actions.size(); i++) {
            ActionButton b = actions.get(i);
            boolean hov = i == hoverAction;
            g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), hov ? 0x48FFFFFF : 0x20FFFFFF);
            SkyUi.hline(g, b.x(), b.x() + b.w(), b.y(), hov ? 0xD0 : 0x60);
            SkyUi.hline(g, b.x(), b.x() + b.w(), b.y() + b.h() - 1, hov ? 0xD0 : 0x60);
            int keyW = b.key().isEmpty() ? 0 : font.width(b.key()) + 4;
            String label = SkyUi.ellipsize(font, b.label().getString(), b.w() - 6 - keyW);
            int total = font.width(label) + keyW;
            int lx = b.x() + (b.w() - total) / 2;
            if (keyW > 0) g.drawString(font, b.key(), lx, b.y() + 3, SkyUi.GOLD, false);
            g.drawString(font, label, lx + keyW, b.y() + 3, hov ? SkyUi.BRIGHT : SkyUi.TEXT, false);
        }
    }

    private void addLine(List<FormattedCharSequence> lines, List<Integer> colors, Component c, int color, int width) {
        for (FormattedCharSequence s : font.split(c, width)) {
            lines.add(s);
            colors.add(color);
        }
    }

    private void layoutActions(InvEntry e, int mx, int my) {
        List<Component> labels = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        String primary = ClientInventoryHandlers.primaryLabelKey(e);
        if (primary != null) {
            labels.add(Component.translatable("inventory.skycraft.action." + primary));
            keys.add("E");
            runs.add(this::primary);
        }
        if (e.equip == InvEntry.LEFT || (e.leftHandable() && e.equip != InvEntry.WORN)) {
            labels.add(Component.translatable(e.equip == InvEntry.LEFT ? "inventory.skycraft.action.unequip_left" : "inventory.skycraft.action.equip_left"));
            keys.add("");
            runs.add(this::secondary);
        }
        labels.add(Component.translatable(e.count() > 1 && !hasShiftDown() ? "inventory.skycraft.action.drop_one" : "inventory.skycraft.action.drop"));
        keys.add("Q");
        runs.add(() -> drop(hasShiftDown()));
        labels.add(Component.translatable(e.favorite ? "inventory.skycraft.action.unfavorite" : "inventory.skycraft.action.favorite"));
        keys.add("F");
        runs.add(this::favorite);

        int n = labels.size();
        int cols = 2;
        int rowsN = (n + cols - 1) / cols;
        int bh = 14, gap = 3;
        int bw = (detW - 12 - gap) / cols;
        int startY = bottom - 5 - rowsN * bh - (rowsN - 1) * gap;
        for (int i = 0; i < n; i++) {
            int col = i % cols, row = i / cols;
            int bx = detX + 6 + col * (bw + gap);
            int byy = startY + row * (bh + gap);
            // a lone last button spans the row
            int w = (i == n - 1 && col == 0) ? bw * 2 + gap : bw;
            ActionButton b = new ActionButton(bx, byy, w, bh, labels.get(i), keys.get(i), runs.get(i));
            if (SkyUi.inside(mx, my, bx, byy, bx + w, byy + bh)) hoverAction = i;
            actions.add(b);
        }
    }

    private void drawBottomBar(GuiGraphics g, int mx, int my) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;
        int by = height - BOTTOM_BAR;
        g.fill(0, by, width, height, 0xB0000000);
        SkyUi.hline(g, 0, width, by, 0x90);
        int ry = by + 6;

        // right: Armor | Weight | Gold
        float cur = ClientInventoryHandlers.liveCurrent();
        float cap = ClientInventoryHandlers.liveCapacity();
        boolean carryOn = ClientInventoryHandlers.enabled;
        boolean over = carryOn && !player.isCreative() && cur > cap;
        String[] labels = {SkyUi.caps(Component.translatable("inventory.skycraft.stat.armor")),
                SkyUi.caps(Component.translatable("inventory.skycraft.stat.weight")),
                SkyUi.caps(Component.translatable("inventory.skycraft.stat.gold"))};
        String[] values = {String.valueOf(player.getArmorValue()),
                carryOn ? ItemWeights.format(cur) + "/" + ItemWeights.format(cap) : ItemWeights.format(cur),
                String.valueOf(Currency.balance(player))};
        int[] colors = {SkyUi.BRIGHT, over ? SkyUi.BAD : SkyUi.BRIGHT, SkyUi.GOLD};
        int statsW = 0;
        for (int i = 0; i < 3; i++) statsW += font.width(labels[i]) + 4 + font.width(values[i]) + (i < 2 ? 14 : 0);
        int sx = x0 + contentW - statsW;
        for (int i = 0; i < 3; i++) {
            g.drawString(font, labels[i], sx, ry, SkyUi.HEADER, false);
            sx += font.width(labels[i]) + 4;
            g.drawString(font, values[i], sx, ry, colors[i], false);
            if (i == 1 && SkyUi.inside(mx, my, sx - font.width(labels[i]) - 4, ry - 2, sx + font.width(values[i]), ry + 10)) {
                hoverText = Component.translatable(over ? "inventory.skycraft.overencumbered" : "inventory.skycraft.carry_tooltip");
            }
            sx += font.width(values[i]);
            if (i < 2) {
                SkyUi.diamond(g, sx + 7, ry + 3, 1, SkyUi.TRIM);
                sx += 14;
            }
        }

        // left: Health / Magicka / Stamina bars
        PlayerData data = SkyData.get(player);
        int barsRoom = contentW - statsW - 24;
        int barW = Mth.clamp(barsRoom / 3 - 10, 20, 64);
        int bx = x0 + 4;
        float[] fills = {player.getHealth() / Math.max(1f, player.getMaxHealth()),
                data.getMagicka() / Math.max(1f, data.maxMagicka()), data.getStamina() / Math.max(1f, data.maxStamina())};
        int[] barColors = {0xB0262A, 0x2D63C8, 0x3F9A3A};
        String[] names = {"health", "magicka", "stamina"};
        int[] now = {Math.round(player.getHealth() * 5f), Math.round(data.getMagicka()), Math.round(data.getStamina())};
        int[] max = {data.maxHealth(), Math.round(data.maxMagicka()), Math.round(data.maxStamina())};
        if (barsRoom >= 3 * 30) {
            for (int i = 0; i < 3; i++) {
                SkyUi.bar(g, bx, ry + 2, barW, fills[i], barColors[i]);
                if (SkyUi.inside(mx, my, bx - 3, ry - 2, bx + barW + 3, ry + 8)) {
                    hoverText = Component.translatable("inventory.skycraft.vital." + names[i], now[i], max[i]);
                }
                bx += barW + 10;
            }
        }

        drawHints(g, by + 18);
    }

    /** Key hints ("E Use  Q Drop ..."), keeping the most important ones when the screen is narrow. */
    private void drawHints(GuiGraphics g, int y) {
        String[] keys = {"E", "RMB", "Q", "Shift+Q", "F", "R", "X", "Tab"};
        String[] names = {"use", "left", "drop", "drop_all", "favorite", "sort", "inspect", "close"};
        int[] priority = {0, 5, 1, 7, 2, 6, 4, 3};
        int n = keys.length;
        String[] labels = new String[n];
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            labels[i] = Component.translatable("inventory.skycraft.hint." + names[i]).getString();
            widths[i] = font.width(keys[i]) + 3 + font.width(labels[i]);
        }
        boolean[] shownHint = new boolean[n];
        int total = 0;
        int room = width - 16;
        for (int p = 0; p < n; p++) {
            for (int i = 0; i < n; i++) {
                if (priority[i] != p) continue;
                int add = widths[i] + (total > 0 ? 12 : 0);
                if (total + add <= room) {
                    shownHint[i] = true;
                    total += add;
                }
            }
        }
        int x = width / 2 - total / 2;
        boolean first = true;
        for (int i = 0; i < n; i++) {
            if (!shownHint[i]) continue;
            if (!first) x += 12;
            first = false;
            g.drawString(font, keys[i], x, y, 0xFFC8A858, false);
            x += font.width(keys[i]) + 3;
            g.drawString(font, labels[i], x, y, 0xFF8A857A, false);
            x += font.width(labels[i]);
        }
    }

    /** A faint round glow behind the item model. */
    private static void glow(GuiGraphics g, int cx, int cy, int r) {
        for (int dy = -r; dy <= r; dy += 2) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            float t = 1f - Math.abs(dy) / (float) r;
            int a = (int) (22 * t);
            if (a <= 0 || half <= 0) continue;
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 2, a << 24 | 0xFFF0D8);
        }
    }

    /** The selected item, large and gently swinging (Skyrim turns the item model in the inventory). */
    private static void renderPreview(GuiGraphics g, ItemStack stack, int cx, int cy, float scale) {
        float t = (Util.getMillis() % 1_000_000L) / 1000f;
        float angle = (float) Math.sin(t * 0.8) * 30f;
        // a solid 3D model (the item-frame display is one block wide; ~0.9 of the old 16 px icon size)
        SkyUi.item3d(g, stack, cx, cy, scale * 14f, 8f, angle);
    }

    // ------------------------------------------------------------------ item facts

    private static String fmt(double v) {
        double r = Math.round(v * 10) / 10.0;
        if (Math.abs(r - Math.rint(r)) < 0.001) return String.valueOf((long) Math.rint(r));
        return String.valueOf(r);
    }

    private static int quality(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : Mth.clamp(tag.getInt("skycraft_quality"), 0, 6);
    }

    /** Attack damage in the main hand like the vanilla tooltip (1 + bonuses), including tempering. 0 if no bonus. */
    private static double damage(ItemStack stack) {
        try {
            Multimap<Attribute, AttributeModifier> mods = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
            double sum = 0;
            boolean any = false;
            for (AttributeModifier m : mods.get(Attributes.ATTACK_DAMAGE)) {
                if (m.getOperation() == AttributeModifier.Operation.ADDITION) {
                    sum += m.getAmount();
                    any = true;
                }
            }
            return any ? 1 + sum : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    /** Armor points in the item's own armor slot (including tempering). */
    private static double armor(ItemStack stack) {
        EquipmentSlot slot = InventoryActions.equipSlotFor(stack);
        if (slot == null || slot.getType() != EquipmentSlot.Type.ARMOR) return 0;
        try {
            double sum = 0;
            for (AttributeModifier m : stack.getAttributeModifiers(slot).get(Attributes.ARMOR)) {
                if (m.getOperation() == AttributeModifier.Operation.ADDITION) sum += m.getAmount();
            }
            return sum;
        } catch (Exception ex) {
            return 0;
        }
    }

    /** "Sword, One-Handed", "Heavy Armor", "Potion"... */
    private static Component kindLine(InvEntry e) {
        ItemStack stack = e.stack;
        if (e.category == InvCategory.WEAPONS) {
            WeaponClass wc = WeaponClass.of(stack);
            if (wc != WeaponClass.OTHER && wc != WeaponClass.UNARMED) {
                Component type = Component.translatable("inventory.skycraft.weapon." + wc.name().toLowerCase(Locale.ROOT));
                return wc.skill != null ? Component.translatable("inventory.skycraft.kind_skill", type, wc.skill.displayName()) : type;
            }
        }
        if (stack.getItem() instanceof ArmorItem) {
            return Component.translatable(ArmorClass.isHeavy(stack) ? "inventory.skycraft.heavy_armor" : "inventory.skycraft.light_armor");
        }
        if (stack.getItem() instanceof ShieldItem) return Component.translatable("inventory.skycraft.shield");
        return e.category.displayName();
    }

    /** Enchantments for gear; the item's own tooltip (effects, lore, spell descriptions...) for everything else. */
    private List<Component> description(InvEntry e) {
        List<Component> out = new ArrayList<>();
        ItemStack stack = e.icon;
        if (e.category == InvCategory.WEAPONS || e.category == InvCategory.APPAREL) {
            for (Map.Entry<Enchantment, Integer> en : EnchantmentHelper.getEnchantments(stack).entrySet()) {
                out.add(en.getKey().getFullname(en.getValue()));
            }
            return out;
        }
        if (minecraft == null) return out;
        // food effects (golden apples, raw chicken...): the vanilla tooltip doesn't list them
        FoodProperties food = stack.getItem().getFoodProperties(stack, minecraft.player);
        if (food != null) {
            for (Pair<MobEffectInstance, Float> p : food.getEffects()) {
                MobEffectInstance effect = p.getFirst();
                if (effect == null) continue;
                MutableComponent line = effectName(effect);
                float chance = p.getSecond() == null ? 1f : p.getSecond();
                if (chance < 1f) line.append(" (" + Math.round(chance * 100) + "%)");
                out.add(line.withStyle(effect.getEffect().isBeneficial() ? ChatFormatting.BLUE : ChatFormatting.RED));
            }
        }
        List<Component> tip = Screen.getTooltipFromItem(minecraft, stack);
        String value = Component.translatable("tooltip.skycraft.economy.value", e.value).getString();
        String weight = Component.translatable("inventory.skycraft.tooltip.weight", ItemWeights.format(e.weight)).getString();
        for (int i = 1; i < tip.size(); i++) {
            Component c = tip.get(i);
            String s = c.getString();
            if (s.isBlank() || s.equals(value) || s.equals(weight)) continue;
            out.add(c);
        }
        return out;
    }

    /** "Regeneration II (0:05)". */
    private static MutableComponent effectName(MobEffectInstance effect) {
        MutableComponent c = effect.getEffect().getDisplayName().copy();
        if (effect.getAmplifier() > 0) c.append(" ").append(Component.translatable("potion.potency." + effect.getAmplifier()));
        if (!effect.getEffect().isInstantenous() && effect.getDuration() > 20) {
            int secs = effect.getDuration() / 20;
            c.append(String.format(Locale.ROOT, " (%d:%02d)", secs / 60, secs % 60));
        }
        return c;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (inspectMode) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_X || key == GLFW.GLFW_KEY_TAB) {
                inspectMode = false;
                return true;
            }
            return true;
        }

        switch (key) {
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> {
                move(-1);
                return true;
            }
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> {
                move(1);
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_UP -> {
                move(-visibleRows());
                return true;
            }
            case GLFW.GLFW_KEY_PAGE_DOWN -> {
                move(visibleRows());
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                select(0);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                select(shown.size() - 1);
                return true;
            }
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> {
                cycleCategory(-1);
                return true;
            }
            case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> {
                cycleCategory(1);
                return true;
            }
            case GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                primary();
                return true;
            }
            case GLFW.GLFW_KEY_X -> {
                if (selectedEntry != null) {
                    inspectMode = true;
                    inspectRotX = 15f;
                    inspectRotY = 0f;
                    inspectZoom = 1.0f;
                    return true;
                }
            }
            case GLFW.GLFW_KEY_Q -> {
                drop(hasShiftDown());
                return true;
            }
            case GLFW.GLFW_KEY_F -> {
                favorite();
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                setSort((sortMode + 1) % 3);
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                onClose();
                return true;
            }
            default -> {
            }
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (inspectMode) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                inspectMode = false;
                return true;
            }
            return true;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (hoverPreview && selectedEntry != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                inspectMode = true;
                inspectRotX = 15f;
                inspectRotY = 0f;
                inspectZoom = 1.0f;
                return true;
            }
            if (hoverCat >= 0) {
                setCategory(InvCategory.VALUES[hoverCat]);
                return true;
            }
            if (hoverSort >= 0 && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                setSort(hoverSort);
                return true;
            }
            if (hoverAction >= 0 && hoverAction < actions.size() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                actions.get(hoverAction).run().run();
                return true;
            }
            if (hoverRow >= 0 && hoverRow < shown.size()) {
                long now = Util.getMillis();
                boolean again = hoverRow == selected && hoverRow == lastClickRow && now - lastClick < 350;
                select(hoverRow);
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    secondary();
                } else if (again) {
                    primary();
                    lastClick = 0;
                    return true;
                }
                lastClick = now;
                lastClickRow = hoverRow;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (inspectMode) {
            inspectRotY += (float) dragX * 1.5f;
            inspectRotX -= (float) dragY * 1.5f;
            inspectLastDrag = Util.getMillis();
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inspectMode) {
            inspectZoom = Mth.clamp(inspectZoom + (float) delta * 0.15f, 0.5f, 3.0f);
            return true;
        }
        int step = delta > 0 ? -1 : delta < 0 ? 1 : 0;
        if (step == 0) return false;
        if (SkyUi.inside(mx, my, catX, top, catX + catW, bottom)) {
            cycleCategory(step);
        } else {
            scroll += step;
            clampScroll();
        }
        return true;
    }
}
