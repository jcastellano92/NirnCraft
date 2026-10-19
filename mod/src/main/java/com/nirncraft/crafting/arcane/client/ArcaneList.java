package com.nirncraft.crafting.arcane.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A small scrollable list of icon rows used by the arcane screens. Entries are matched across rebuilds by key. */
public class ArcaneList<T> {
    public static final int ROW = 20;

    public record Entry<V>(String key, ItemStack icon, Component label, Component sub, V value) {}

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Component title;
    public int x, y, w, h;
    private int scroll;
    private String selectedKey;
    private int hovered = -1;

    public ArcaneList(Component title) {
        this.title = title;
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void setEntries(List<Entry<T>> list) {
        entries.clear();
        entries.addAll(list);
        if (selectedKey != null && entries.stream().noneMatch(e -> e.key().equals(selectedKey))) selectedKey = null;
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    public List<Entry<T>> entries() {
        return entries;
    }

    public Entry<T> selected() {
        for (Entry<T> e : entries) if (e.key().equals(selectedKey)) return e;
        return null;
    }

    public T selectedValue() {
        Entry<T> e = selected();
        return e == null ? null : e.value();
    }

    public void select(String key) {
        selectedKey = key;
    }

    public void clearSelection() {
        selectedKey = null;
    }

    public Entry<T> hoveredEntry() {
        return hovered >= 0 && hovered < entries.size() ? entries.get(hovered) : null;
    }

    private int listTop() {
        return y + 12;
    }

    private int visibleRows() {
        return Math.max(1, (h - 12) / ROW);
    }

    private int maxScroll() {
        return Math.max(0, entries.size() - visibleRows());
    }

    /** Screen y of the row showing entry {@code index}, or -1 if it is scrolled out of view. */
    public int rowY(int index) {
        int row = index - scroll;
        if (row < 0 || row >= visibleRows()) return -1;
        return listTop() + row * ROW;
    }

    public boolean isMouseOver(double mx, double my) {
        return mx >= x && mx < x + w && my >= listTop() && my < y + h;
    }

    /** Returns the clicked entry (and selects it), or null. */
    public Entry<T> click(double mx, double my) {
        if (!isMouseOver(mx, my)) return null;
        int idx = scroll + (int) ((my - listTop()) / ROW);
        if (idx < 0 || idx >= entries.size()) return null;
        Entry<T> e = entries.get(idx);
        selectedKey = e.key();
        return e;
    }

    public boolean scroll(double mx, double my, double delta) {
        if (!isMouseOver(mx, my)) return false;
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, maxScroll());
        return true;
    }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, Component emptyText) {
        g.text(font, title, x + 2, y, ArcaneUi.GOLD, false);
        int top = listTop();
        g.fill(x, top, x + w, y + h, 0x60000000);
        ArcaneUi.frame(g, x, top, w, y + h - top, 0x50B89A50);
        hovered = -1;
        if (entries.isEmpty()) {
            g.text(font, emptyText, x + 5, top + 6, ArcaneUi.MUTED, false);
            return;
        }
        g.enableScissor(x + 1, top + 1, x + w - 1, y + h - 1);
        int rows = visibleRows();
        for (int i = 0; i < rows + 1; i++) {
            int idx = scroll + i;
            if (idx >= entries.size()) break;
            Entry<T> e = entries.get(idx);
            int ry = top + i * ROW;
            boolean over = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW && mouseY < y + h;
            boolean sel = Objects.equals(e.key(), selectedKey);
            if (over) hovered = idx;
            if (sel) g.fill(x + 1, ry, x + w - 1, ry + ROW, 0x704A3A80);
            else if (over) g.fill(x + 1, ry, x + w - 1, ry + ROW, 0x30FFFFFF);
            if (sel) g.fill(x + 1, ry, x + 3, ry + ROW, 0xFFE8C060);
            if (!e.icon().isEmpty()) {
                g.item(e.icon(), x + 4, ry + 2);
                g.itemDecorations(font, e.icon(), x + 4, ry + 2);
            }
            int tx = e.icon().isEmpty() ? x + 6 : x + 24;
            if (e.sub() == null || e.sub().getString().isEmpty()) {
                g.text(font, e.label(), tx, ry + 6, sel ? 0xFFFFF4D0 : ArcaneUi.TEXT, false);
            } else {
                g.text(font, e.label(), tx, ry + 1, sel ? 0xFFFFF4D0 : ArcaneUi.TEXT, false);
                g.text(font, e.sub(), tx, ry + 11, ArcaneUi.MUTED, false);
            }
        }
        g.disableScissor();
        // scrollbar
        if (maxScroll() > 0) {
            int trackH = y + h - top;
            int barH = Math.max(10, trackH * rows / entries.size());
            int barY = top + (trackH - barH) * scroll / maxScroll();
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xC0B89A50);
        }
    }
}
