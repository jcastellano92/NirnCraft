package com.skycraft.inventory.client;

import com.google.common.collect.Multimap;
import com.mojang.datafixers.util.Pair;
import com.mojang.math.Axis;
import com.skycraft.combat.ArmorClass;
import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Currency;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.inventory.InvCategory;
import com.skycraft.inventory.InventoryActions;
import com.skycraft.inventory.InventoryPackets;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.inventory.ValueDensity;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import com.mojang.blaze3d.platform.InputConstants;

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

    static final int SORT_NAME = 0, SORT_WEIGHT = 1, SORT_VALUE = 2, SORT_VW = 3;

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
    /** Whether the V/W column fit in the list at the last draw (otherwise sorting cycles name, weight, value). */
    private boolean vwVisible = true;
    private int hoverAction = -1;
    private boolean hoverCrafting;
    private boolean hoverPreview;
    /** The detail panel's text scroll (lines): reset on a new selection, bounded by {@code detMaxScroll} while drawing. */
    private int detScroll, detMaxScroll;
    private InvEntry detScrollFor;
    private Component hoverText;
    /** Multi-line tooltip (the carry capacity sources) or null. */
    private List<Component> hoverLines;
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
            case SORT_VW -> Comparator.comparingDouble(e -> ValueDensity.sortKey(e.valuePerWeight));
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
        minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
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
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (inspectMode && selectedEntry != null) {
            renderInspectOverlay(g, mouseX, mouseY);
            return;
        }

        g.fillGradient(0, 0, width, height, 0xA8050505, 0xD8050505);
        hoverText = null;
        hoverLines = null;
        drawCategories(g, mouseX, mouseY);
        drawList(g, mouseX, mouseY);
        drawDetails(g, mouseX, mouseY);
        drawBottomBar(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        if (hoverPreview && selectedEntry != null) {
            g.setTooltipForNextFrame(font, selectedEntry.icon, mouseX, mouseY);
        } else if (hoverLines != null) {
            g.setComponentTooltipForNextFrame(font, hoverLines, mouseX, mouseY);
        } else if (hoverText != null) {
            g.setTooltipForNextFrame(font, hoverText, mouseX, mouseY);
        }
    }

    /** The capacity tooltip: where the carry capacity comes from, and how fast you move at this load. */
    private static List<Component> capacityLines(LocalPlayer player, float cur, float cap) {
        List<Component> lines = new ArrayList<>();
        CarryWeight.CapacityParts p = CarryWeight.parts(player);
        lines.add(Component.translatable("inventory.skycraft.capacity.header", ItemWeights.format(cur), ItemWeights.format(cap)));
        lines.add(Component.translatable("inventory.skycraft.capacity.base", ItemWeights.format(p.base())));
        if (p.stamina() != 0f) lines.add(Component.translatable("inventory.skycraft.capacity.stamina", signed(p.stamina())));
        if (p.race() != 0f) {
            com.skycraft.core.Race race = SkyData.get(player).getRace();
            lines.add(Component.translatable("inventory.skycraft.capacity.race", race == null ? "" : race.displayName(), signed(p.race())));
        }
        if (p.perks() != 0f) lines.add(Component.translatable("inventory.skycraft.capacity.perks", signed(p.perks())));
        if (p.bonus() != 0f) lines.add(Component.translatable("inventory.skycraft.capacity.bonus", signed(p.bonus())));
        // the mount's share is known to the server only: whatever the server's capacity has beyond the parts
        float other = cap - p.total();
        if (Math.abs(other) >= 0.5f) lines.add(Component.translatable("inventory.skycraft.capacity.mount", signed(other)));
        if (cur > cap) {
            lines.add(Component.translatable("inventory.skycraft.capacity.slow",
                    Math.round((1f - CarryWeight.speedFactor(cur, cap)) * 100f)));
        }
        return lines;
    }

    private static String signed(float v) {
        return (v >= 0 ? "+" : "") + ItemWeights.format(v);
    }

    private void renderInspectOverlay(GuiGraphicsExtractor g, int mx, int my) {
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
        g.text(font, kind, cx - font.width(kind) / 2, 32, SkyUi.DIM, false);
        SkyUi.ornament(g, cx, 46, Math.min(100, width / 4), 0xC0C8BC9A);

        String hint = "[Drag] Rotate   [Scroll] Zoom   [X / Esc] Exit Inspect";
        g.text(font, hint, cx - font.width(hint) / 2, height - 20, 0xB0A89F86, false);
    }

    private void drawCategories(GuiGraphicsExtractor g, int mx, int my) {
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
            g.text(font, name, catX + 8, y + 4, color, false);
            if (c == InvCategory.FAVORITES) SkyUi.star(g, catX + 8 + font.width(name) + 4, y + 5, sel ? SkyUi.GOLD : SkyUi.TRIM);
            if (n > 0) {
                String count = String.valueOf(n);
                g.text(font, count, catX + catW - 6 - font.width(count), y + 4, sel ? SkyUi.HEADER : 0xFF6E6A60, false);
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
            g.text(font, label, lx, by + 3, hoverCrafting ? SkyUi.BRIGHT : SkyUi.TEXT, false);
            g.text(font, key, lx + font.width(label) + 5, by + 3, SkyUi.DIM, false);
            if (hoverCrafting) hoverText = Component.translatable("inventory.skycraft.crafting.tooltip");
        }
    }

    private void drawList(GuiGraphicsExtractor g, int mx, int my) {
        SkyUi.panel(g, listX, top, listX + listW, bottom);
        // Columns are measured from their header and from the widest value they can show (4 digits), so nothing
        // overflows at any GUI scale. V/W (gold per weight) is the right-most; it gives way to the name when the list
        // is too narrow for it (then Tab/R only cycles name, weight, value).
        String vwHead = SkyUi.caps(Component.translatable("inventory.skycraft.column.vw"));
        int vwColW = Math.max(font.width(vwHead), font.width("9.9k"));
        int valColW = font.width(SkyUi.caps(Component.translatable("inventory.skycraft.column.value")));
        int wgtColW = font.width(SkyUi.caps(Component.translatable("inventory.skycraft.column.weight")));
        for (InvEntry e : shown) {
            valColW = Math.max(valColW, font.width(String.valueOf(e.value)));
            wgtColW = Math.max(wgtColW, font.width(ItemWeights.format(e.weight)));
            vwColW = Math.max(vwColW, font.width(ValueDensity.format(e.valuePerWeight)));
        }
        int nameX = listX + 30;
        boolean showVw = listW - 38 - (vwColW + valColW + wgtColW + 30) >= 70;
        vwVisible = showVw;
        int vwRight = listX + listW - 8;
        int valRight = showVw ? vwRight - vwColW - 14 : listX + listW - 8;
        int wgtRight = valRight - valColW - 14;
        int nameEnd = wgtRight - wgtColW - 8;

        // column headers (click to sort)
        hoverSort = -1;
        int hy = top + 5;
        int cols = showVw ? 4 : 3;
        Component[] headers = {Component.translatable("inventory.skycraft.column.name"),
                Component.translatable("inventory.skycraft.column.weight"), Component.translatable("inventory.skycraft.column.value"),
                Component.translatable("inventory.skycraft.column.vw")};
        int[] hx = new int[4];
        String[] hs = new String[4];
        for (int i = 0; i < 4; i++) hs[i] = SkyUi.caps(headers[i]);
        hx[0] = nameX;
        hx[1] = wgtRight - font.width(hs[1]);
        hx[2] = valRight - font.width(hs[2]);
        hx[3] = vwRight - font.width(hs[3]);
        Component vwTip = null;
        for (int i = 0; i < cols; i++) {
            int x1 = i == 0 ? listX + 2 : hx[i] - 8;
            int x2 = i == 0 ? nameEnd : hx[i] + font.width(hs[i]) + 2;
            boolean hov = SkyUi.inside(mx, my, x1, hy - 3, x2, hy + 10);
            if (hov) hoverSort = i;
            if (hov && i == SORT_VW) vwTip = Component.translatable("inventory.skycraft.column.vw.tooltip");
            boolean active = sortMode == i;
            g.text(font, hs[i], hx[i], hy, active ? SkyUi.LINE : hov ? SkyUi.TEXT : SkyUi.HEADER, false);
            if (active) SkyUi.triangle(g, hx[i] - 5, hy + 2, !sortDescending, SkyUi.LINE);
        }
        if (vwTip != null) hoverText = vwTip;
        SkyUi.hline(g, listX + 2, listX + listW - 2, listTop - 2, 0x90);

        hoverRow = -1;
        if (shown.isEmpty()) {
            Component empty = Component.translatable("inventory.skycraft.empty");
            g.centeredText(font, empty, listX + listW / 2, listTop + 10, SkyUi.DIM);
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

            // equipped marker: gold diamond = right hand / worn, hollow blue diamond = left hand, plus R / L / W badge
            int my0 = y + ROW / 2;
            if (e.equip == InvEntry.RIGHT || e.equip == InvEntry.WORN) SkyUi.diamond(g, listX + 7, my0, 2, SkyUi.GOLD);
            else if (e.equip == InvEntry.LEFT) SkyUi.diamondOutline(g, listX + 7, my0, 2, SkyUi.LEFT_HAND);

            g.item(e.icon, listX + 12, y + 1);
            g.itemDecorations(font, e.icon, listX + 12, y + 1);

            String badge = e.equip == InvEntry.RIGHT ? " [R]" : e.equip == InvEntry.LEFT ? " [L]" : e.equip == InvEntry.WORN ? " [W]" : "";
            String count = e.count() > 1 ? " (" + e.count() + ")" : "";
            int room = nameEnd - nameX - font.width(count) - font.width(badge) - (e.favorite ? 9 : 0);
            String name = SkyUi.ellipsize(font, e.name, Math.max(12, room));
            int nameColor = e.stolen ? SkyUi.STOLEN : sel ? SkyUi.BRIGHT : SkyUi.TEXT;
            g.text(font, name, nameX, y + 5, nameColor, false);
            int nx = nameX + font.width(name);
            if (!badge.isEmpty()) {
                int bCol = e.equip == InvEntry.LEFT ? SkyUi.LEFT_HAND : SkyUi.GOLD;
                g.text(font, badge, nx, y + 5, bCol, false);
                nx += font.width(badge);
            }
            if (!count.isEmpty()) {
                g.text(font, count, nx, y + 5, SkyUi.DIM, false);
                nx += font.width(count);
            }
            if (e.favorite) SkyUi.star(g, nx + 3, y + 6, SkyUi.GOLD);

            String w = ItemWeights.format(e.weight);
            g.text(font, w, wgtRight - font.width(w), y + 5, sel ? SkyUi.TEXT : 0xFFB0A898, false);
            String v = String.valueOf(e.value);
            g.text(font, v, valRight - font.width(v), y + 5, sel ? SkyUi.GOLD : 0xFFC0A050, false);
            if (showVw) {
                String r = ValueDensity.format(e.valuePerWeight);
                int c = e.valuePerWeight < 0f ? SkyUi.DIM : sel ? 0xFFB8E0A0 : 0xFF98B888;
                g.text(font, r, vwRight - font.width(r), y + 5, c, false);
            }
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

    private void drawDetails(GuiGraphicsExtractor g, int mx, int my) {
        SkyUi.panel(g, detX, top, detX + detW, bottom);
        actions.clear();
        hoverAction = -1;
        hoverPreview = false;
        detMaxScroll = 0;
        InvEntry e = selectedEntry;
        if (e == null || detW < 60) return;
        int cx = detX + detW / 2;
        ItemStack stack = e.stack;

        // ---------------------------------------------------------- the item itself, slowly turning
        int previewH = Mth.clamp(detW * 45 / 100, 40, 84);
        previewH = Math.min(previewH, (bottom - top) / 3);
        // ingredients list four effect lines: a smaller preview leaves them room
        com.skycraft.crafting.arcane.alchemy.Ingredients.Ingredient ingredient = com.skycraft.crafting.arcane.alchemy.Ingredients.get(stack);
        if (ingredient != null) previewH = Math.min(previewH, 48);
        int py = top + 6 + previewH / 2;
        glow(g, cx, py, previewH / 2 + 6);
        renderPreview(g, e.icon, cx, py, previewH / 16f * 0.8f);
        hoverPreview = SkyUi.inside(mx, my, cx - previewH / 2, top + 4, cx + previewH / 2, top + 8 + previewH);

        int y = top + 10 + previewH;
        int textW = detW - 12;

        // name and kind
        var rarityColor=net.minecraft.network.chat.TextColor.fromLegacyFormat(stack.getRarity().color());
        Integer rarity=rarityColor==null?null:rarityColor.getValue();
        int nameColor = e.stolen ? SkyUi.STOLEN : rarity != null && stack.getRarity() != net.minecraft.world.item.Rarity.COMMON
                ? 0xFF000000 | rarity : SkyUi.BRIGHT;
        String name = SkyUi.ellipsize(font, e.name, textW);
        g.text(font, name, cx - font.width(name) / 2, y, nameColor, false);
        y += 11;
        String kind = SkyUi.ellipsize(font, kindLine(e).getString(), textW);
        g.text(font, kind, cx - font.width(kind) / 2, y, SkyUi.DIM, false);
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
        // gold per weight: only when the cell stays wide enough for its label and value (narrow panels skip it)
        if ((detW - 8) / (labels.size() + 1) >= 44) {
            labels.add(Component.translatable("inventory.skycraft.column.vw"));
            values.add(ValueDensity.format(e.valuePerWeight));
            colorsList.add(e.valuePerWeight < 0f ? SkyUi.DIM : 0xFFB8E0A0);
        }

        int cellW = (detW - 8) / labels.size();
        for (int i = 0; i < labels.size(); i++) {
            int ccx = detX + 4 + cellW * i + cellW / 2;
            String l = SkyUi.ellipsize(font, SkyUi.caps(labels.get(i)), cellW - 2);
            g.text(font, l, ccx - font.width(l) / 2, y + 2, SkyUi.HEADER, false);
            String v = values.get(i);
            g.text(font, v, ccx - font.width(v) / 2, y + 13, colorsList.get(i), false);
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
        // an ingredient's four effects come first (all of them: discovered by name and power, the rest "?")
        if (ingredient != null && minecraft != null && minecraft.player != null) {
            addLine(lines, colors, Component.translatable("tooltip.skycraft.alchemy.ingredient"), SkyUi.GOLD, textW);
            for (Component c : com.skycraft.crafting.arcane.alchemy.Alchemy.effectLines(minecraft.player, ingredient)) {
                addLine(lines, colors, Component.literal(" ").append(c), SkyUi.TEXT, textW);
            }
        }
        int quality = quality(stack);
        if (quality > 0) addLine(lines, colors, Component.translatable("quality.skycraft." + quality), 0xFF8FC0FF, textW);
        if (e.equip != InvEntry.NONE) {
            String key = e.equip == InvEntry.RIGHT ? "right" : e.equip == InvEntry.LEFT ? "left" : "worn";
            addLine(lines, colors, Component.translatable("inventory.skycraft.equipped." + key), SkyUi.GOLD, textW);
        }
        if (com.skycraft.inventory.ItemData.containsCustom(stack, com.skycraft.crafting.arcane.ArcaneEvents.WEAPON_POISON)) {
            CompoundTag custom = com.skycraft.inventory.ItemData.custom(stack);
            CompoundTag pTag = custom.getCompoundOrEmpty(com.skycraft.crafting.arcane.ArcaneEvents.WEAPON_POISON);
            int hits = pTag.getIntOr(com.skycraft.crafting.arcane.ArcaneEvents.POISON_HITS, 0);
            int maxHits = Math.max(hits, pTag.getIntOr(com.skycraft.crafting.arcane.ArcaneEvents.POISON_MAX_HITS, 0));
            String pName = com.skycraft.crafting.arcane.ArcaneEvents.poisonDisplayName(pTag);
            addLine(lines, colors, Component.translatable("inventory.skycraft.poisoned", pName, hits, maxHits), 0xFF88D870, textW);
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

        // more lines than fit: the mouse wheel over the panel scrolls them instead of cutting them off
        if (detScrollFor != e) {
            detScrollFor = e;
            detScroll = 0;
        }
        int visible = Math.max(1, (actionsTop - y) / 10);
        detMaxScroll = Math.max(0, lines.size() - visible);
        detScroll = Mth.clamp(detScroll, 0, detMaxScroll);
        for (int i = detScroll; i < lines.size() && i < detScroll + visible; i++) {
            g.text(font, lines.get(i), detX + 6, y, colors.get(i), false);
            y += 10;
        }
        if (detMaxScroll > 0) {
            int trackTop = top + 8, trackBottom = actionsTop;
            int barH = Math.max(10, (trackBottom - trackTop) * visible / lines.size());
            int barY = trackTop + (trackBottom - trackTop - barH) * detScroll / detMaxScroll;
            g.fill(detX + detW - 4, trackTop, detX + detW - 3, trackBottom, 0x30FFFFFF);
            g.fill(detX + detW - 4, barY, detX + detW - 3, barY + barH, 0xC0C8BC9A);
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
            if (keyW > 0) g.text(font, b.key(), lx, b.y() + 3, SkyUi.GOLD, false);
            g.text(font, label, lx + keyW, b.y() + 3, hov ? SkyUi.BRIGHT : SkyUi.TEXT, false);
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
        if (com.skycraft.crafting.arcane.ArcaneEvents.isApplicablePoison(e.stack)) {
            labels.add(Component.translatable("inventory.skycraft.action.apply_poison"));
            keys.add("G");
            runs.add(() -> ClientInventoryHandlers.applyPoison(selectedEntry));
        } else if (e.equip == InvEntry.LEFT || (e.leftHandable() && e.equip != InvEntry.WORN)) {
            labels.add(Component.translatable(e.equip == InvEntry.LEFT ? "inventory.skycraft.action.unequip_left" : "inventory.skycraft.action.equip_left"));
            keys.add("RMB");
            runs.add(this::secondary);
        }
        labels.add(Component.translatable(e.count() > 1 && !com.skycraft.client.screen.UiInput.shiftDown() ? "inventory.skycraft.action.drop_one" : "inventory.skycraft.action.drop"));
        keys.add("Q");
        runs.add(() -> drop(com.skycraft.client.screen.UiInput.shiftDown()));
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

    private void drawBottomBar(GuiGraphicsExtractor g, int mx, int my) {
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
            g.text(font, labels[i], sx, ry, SkyUi.HEADER, false);
            sx += font.width(labels[i]) + 4;
            g.text(font, values[i], sx, ry, colors[i], false);
            if (i == 1 && SkyUi.inside(mx, my, sx - font.width(labels[i]) - 4, ry - 2, sx + font.width(values[i]), ry + 10)) {
                hoverText = Component.translatable(over ? "inventory.skycraft.overencumbered" : "inventory.skycraft.carry_tooltip");
                if (carryOn) hoverLines = capacityLines(player, cur, cap);
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
    private void drawHints(GuiGraphicsExtractor g, int y) {
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
            g.text(font, keys[i], x, y, 0xFFC8A858, false);
            x += font.width(keys[i]) + 3;
            g.text(font, labels[i], x, y, 0xFF8A857A, false);
            x += font.width(labels[i]);
        }
    }

    /** A faint round glow behind the item model. */
    private static void glow(GuiGraphicsExtractor g, int cx, int cy, int r) {
        for (int dy = -r; dy <= r; dy += 2) {
            int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
            float t = 1f - Math.abs(dy) / (float) r;
            int a = (int) (22 * t);
            if (a <= 0 || half <= 0) continue;
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 2, a << 24 | 0xFFF0D8);
        }
    }

    /** The selected item, large and gently swinging (Skyrim turns the item model in the inventory). */
    private static void renderPreview(GuiGraphicsExtractor g, ItemStack stack, int cx, int cy, float scale) {
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
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        return tag == null ? 0 : Mth.clamp(tag.getIntOr("skycraft_quality", 0), 0, 6);
    }

    /** Attack damage in the main hand like the vanilla tooltip (1 + bonuses), including tempering. 0 if no bonus. */
    private static double damage(ItemStack stack) {
        return SkyrimContainerScreen.damage(stack);
    }

    /** Armor points in the item's own armor slot, including tempering. */
    private static double armor(ItemStack stack) {
        return com.skycraft.inventory.EquipmentData.defense(stack);
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
        if (com.skycraft.inventory.EquipmentData.isArmor(stack)) {
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
            var enchantments = stack.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
                    net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
            for (var enchantment : enchantments.entrySet()) {
                out.add(net.minecraft.world.item.enchantment.Enchantment.getFullname(enchantment.getKey(), enchantment.getIntValue()));
            }
            return out;
        }
        if (minecraft == null) return out;
        // food effects (golden apples, raw chicken...): the vanilla tooltip doesn't list them
        var consumable = stack.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
        if (consumable != null) {
            for (var consumeEffect : consumable.onConsumeEffects()) {
                if (!(consumeEffect instanceof net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect applied)) continue;
                for (MobEffectInstance effect : applied.effects()) {
                    MutableComponent line = effectName(effect);
                    if (applied.probability() < 1f) line.append(" (" + Math.round(applied.probability() * 100) + "%)");
                    out.add(line.withStyle(effect.getEffect().value().isBeneficial() ? ChatFormatting.BLUE : ChatFormatting.RED));
                }
            }
        }
        List<Component> tip = Screen.getTooltipFromItem(minecraft, stack);
        String value = Component.translatable("tooltip.skycraft.economy.value", e.value).getString();
        String weight = Component.translatable("inventory.skycraft.tooltip.weight", ItemWeights.format(e.weight)).getString();
        // ingredients: the tooltip's "Alchemy effects:" block (header + 4 slots) is drawn by drawDetails instead
        boolean ingredient = com.skycraft.crafting.arcane.alchemy.Ingredients.get(stack) != null;
        String alchemyHeader = Component.translatable("tooltip.skycraft.alchemy.ingredient").getString();
        int skip = 0;
        for (int i = 1; i < tip.size(); i++) {
            Component c = tip.get(i);
            String s = c.getString();
            if (ingredient && s.equals(alchemyHeader)) {
                skip = 4;
                continue;
            }
            if (skip > 0) {
                skip--;
                continue;
            }
            if (s.isBlank() || s.equals(value) || s.equals(weight)) continue;
            out.add(c);
        }
        return out;
    }

    /** "Regeneration II (0:05)". */
    private static MutableComponent effectName(MobEffectInstance effect) {
        MutableComponent c = effect.getEffect().value().getDisplayName().copy();
        if (effect.getAmplifier() > 0) c.append(" ").append(Component.translatable("potion.potency." + effect.getAmplifier()));
        if (!effect.getEffect().value().isInstantaneous() && effect.getDuration() > 20) {
            int secs = effect.getDuration() / 20;
            c.append(String.format(Locale.ROOT, " (%d:%02d)", secs / 60, secs % 60));
        }
        return c;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (inspectMode) {
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_X || key == InputConstants.KEY_TAB) {
                inspectMode = false;
                return true;
            }
            return true;
        }

        switch (key) {
            case InputConstants.KEY_W, InputConstants.KEY_UP -> {
                move(-1);
                return true;
            }
            case InputConstants.KEY_S, InputConstants.KEY_DOWN -> {
                move(1);
                return true;
            }
            case InputConstants.KEY_PAGEUP -> {
                move(-visibleRows());
                return true;
            }
            case InputConstants.KEY_PAGEDOWN -> {
                move(visibleRows());
                return true;
            }
            case InputConstants.KEY_HOME -> {
                select(0);
                return true;
            }
            case InputConstants.KEY_END -> {
                select(shown.size() - 1);
                return true;
            }
            case InputConstants.KEY_A, InputConstants.KEY_LEFT -> {
                cycleCategory(-1);
                return true;
            }
            case InputConstants.KEY_D, InputConstants.KEY_RIGHT -> {
                cycleCategory(1);
                return true;
            }
            case InputConstants.KEY_E, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                primary();
                return true;
            }
            case InputConstants.KEY_X -> {
                if (selectedEntry != null) {
                    inspectMode = true;
                    inspectRotX = 15f;
                    inspectRotY = 0f;
                    inspectZoom = 1.0f;
                    return true;
                }
            }
            case InputConstants.KEY_Q -> {
                drop(com.skycraft.client.screen.UiInput.shiftDown());
                return true;
            }
            case InputConstants.KEY_F -> {
                favorite();
                return true;
            }
            case InputConstants.KEY_G -> {
                if (selectedEntry != null && com.skycraft.crafting.arcane.ArcaneEvents.isApplicablePoison(selectedEntry.stack)) {
                    ClientInventoryHandlers.applyPoison(selectedEntry);
                    return true;
                }
            }
            case InputConstants.KEY_C -> {
                openVanilla();
                return true;
            }
            case InputConstants.KEY_R -> {
                setSort((sortMode + 1) % (vwVisible ? 4 : 3));
                return true;
            }
            case InputConstants.KEY_TAB -> {
                onClose();
                return true;
            }
            default -> {
            }
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (inspectMode) {
            if (button == 1) {
                inspectMode = false;
                return true;
            }
            return true;
        }

        if (button == 0 || button == 1) {
            if (hoverCrafting && button == 0) {
                openVanilla();
                return true;
            }
            if (hoverPreview && selectedEntry != null && button == 0) {
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
            if (hoverSort >= 0 && button == 0) {
                setSort(hoverSort);
                return true;
            }
            if (hoverAction >= 0 && hoverAction < actions.size() && button == 0) {
                actions.get(hoverAction).run().run();
                return true;
            }
            if (hoverRow >= 0 && hoverRow < shown.size()) {
                long now = Util.getMillis();
                select(hoverRow);
                if (button == 1) {
                    secondary();
                } else {
                    primary();
                }
                lastClick = now;
                lastClickRow = hoverRow;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (inspectMode) {
            inspectRotY += (float) dragX * 1.5f;
            inspectRotX -= (float) dragY * 1.5f;
            inspectLastDrag = Util.getMillis();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (inspectMode) {
            inspectZoom = Mth.clamp(inspectZoom + (float) delta * 0.15f, 0.5f, 3.0f);
            return true;
        }
        int step = delta > 0 ? -1 : delta < 0 ? 1 : 0;
        if (step == 0) return false;
        if (detMaxScroll > 0 && SkyUi.inside(mx, my, detX, top, detX + detW, bottom)) {
            detScroll = Mth.clamp(detScroll + step, 0, detMaxScroll);
            return true;
        }
        if (SkyUi.inside(mx, my, catX, top, catX + catW, bottom)) {
            cycleCategory(step);
        } else {
            scroll += step;
            clampScroll();
        }
        return true;
    }
}
