package com.skycraft.inventory.client;

import com.skycraft.core.SkyData;
import com.skycraft.inventory.InvCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.skycraft.magic.client.MagicFavorites;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Skyrim favorites quick menu: a small list of the items AND spells, shouts and powers marked as favorites (plus the equipped
 * spell and shout, read-only). Click or press E to equip / use, right-click for the left hand; the menu closes after
 * choosing so the game keeps flowing (it doesn't pause, like every Skycraft menu in multiplayer).
 */
public class FavoritesScreen extends Screen {
    private static final int ROW = 18;
    private static final int PANEL_W = 210;
    /** Read-only magic module keys shown in the header: right-hand spell, left-hand spell, shout. */
    private static final String[] MAGIC_KEYS = {"selected_spell", "left_spell", "selected_shout"};
    private static final String[] MAGIC_LABELS = {"spell_right", "spell_left", "shout"};

    /** One row of the quick menu: an inventory item, or a favorite spell, shout or power of the magic module. */
    public record Row(@Nullable InvEntry item, @Nullable MagicFavorites.Fav magic) {
        boolean sameAs(Row o) {
            if (o == null) return false;
            if (item != null) return o.item != null && item.sameAs(o.item);
            return o.magic != null && magic.id().equals(o.magic.id());
        }

        int equip() {
            if (item != null) return item.equip;
            int e = MagicFavorites.equipped(net.minecraft.client.Minecraft.getInstance().player, magic);
            return (e & 1) != 0 ? InvEntry.RIGHT : (e & 2) != 0 ? InvEntry.LEFT : InvEntry.NONE;
        }
    }

    private List<Row> entries = new ArrayList<>();
    private int selected;
    private int scroll;
    private int hoverRow = -1;
    private Row selectedEntry;

    // layout
    private int px, py, pw, ph, listTop, rows;

    public FavoritesScreen() {
        super(Component.translatable("inventory.skycraft.favorites"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rebuild();
        layout();
    }

    private int headerLines() {
        if (minecraft == null || minecraft.player == null) return 0;
        CompoundTag magic = SkyData.get(minecraft.player).module("magic");
        int n = 0;
        for (String key : MAGIC_KEYS) if (!magic.getString(key).isEmpty() && !"none".equals(magic.getString(key))) n++;
        return n;
    }

    private void layout() {
        pw = Math.min(PANEL_W, width - 16);
        int header = 20 + headerLines() * 10 + (headerLines() > 0 ? 4 : 0);
        int maxRows = Math.max(3, (height - 40 - header - 18) / ROW);
        rows = Mth.clamp(entries.size(), 3, Math.min(10, maxRows));
        ph = header + rows * ROW + 18;
        px = width / 2 - pw / 2;
        py = Math.max(8, height / 2 - ph / 2);
        listTop = py + header;
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, entries.size() - rows));
    }

    private void rebuild() {
        if (minecraft == null || minecraft.player == null) return;
        Row previous = selectedEntry;
        entries = rows(minecraft.player);
        int idx = -1;
        if (previous != null) {
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).sameAs(previous)) idx = i;
        }
        if (idx < 0) idx = Math.min(selected, entries.size() - 1);
        selected = Math.max(0, idx);
        selectedEntry = entries.isEmpty() ? null : entries.get(selected);
    }

    @Override
    public void tick() {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null || !player.isAlive()) {
            onClose();
            return;
        }
        int before = entries.size();
        rebuild();
        if (entries.size() != before) layout();
    }

    private void select(int i) {
        if (entries.isEmpty()) return;
        selected = Mth.clamp(i, 0, entries.size() - 1);
        selectedEntry = entries.get(selected);
        if (selected < scroll) scroll = selected;
        else if (selected >= scroll + rows) scroll = selected - rows + 1;
        clampScroll();
    }

    private void choose(boolean left) {
        if (selectedEntry == null) return;
        activate(selectedEntry, left);
    }

    /**
     * Every favorite, items first (category, name) then spells, shouts and powers (the order they were marked): the list
     * of this menu, of the number-key hotkeys and of the scroll wheel.
     */
    public static List<Row> rows(LocalPlayer player) {
        List<InvEntry> items = new ArrayList<>();
        for (InvEntry e : InvEntry.build(player)) if (e.favorite) items.add(e);
        items.sort(Comparator.comparingInt((InvEntry e) -> e.category.ordinal())
                .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(e -> e.equip));
        List<Row> out = new ArrayList<>(items.size());
        for (InvEntry e : items) out.add(new Row(e, null));
        for (MagicFavorites.Fav f : MagicFavorites.list(player)) out.add(new Row(null, f));
        return out;
    }

    /**
     * Equips / uses a row ({@code left}: the left hand). An item goes through the inventory actions, a spell, shout or
     * power through the magic menu action; both end in the server's one hand system (magic.Hands). Returns true if the
     * caller must not close a screen (an item action that already closed or kept it open).
     */
    public static boolean activate(Row row, boolean left) {
        if (row.item != null) return left ? ClientInventoryHandlers.secondary(row.item) : ClientInventoryHandlers.primary(row.item);
        MagicFavorites.activate(row.magic, left);
        return false;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0x60000000, 0x90000000);
        SkyUi.panel(g, px, py, px + pw, py + ph);
        int cx = px + pw / 2;
        SkyUi.capsCentered(g, font, title, cx, py + 6, SkyUi.LINE);
        SkyUi.divider(g, cx, py + 17, pw / 2 - 12);

        // equipped spell & shout (read-only, owned by the magic module)
        int y = py + 22;
        if (minecraft != null && minecraft.player != null) {
            CompoundTag magic = SkyData.get(minecraft.player).module("magic");
            for (int i = 0; i < MAGIC_KEYS.length; i++) {
                String id = magic.getString(MAGIC_KEYS[i]);
                if (id.isEmpty() || "none".equals(id)) continue; // "none": a hand deliberately left empty
                boolean shout = i == 2;
                drawMagicLine(g, Component.translatable("inventory.skycraft.favorites." + MAGIC_LABELS[i]),
                        shout ? Component.translatable("shout.skycraft." + id) : spellName(id), shout ? 0xFFD8E4F0 : 0xFF7FB2FF, y);
                y += 10;
            }
        }

        hoverRow = -1;
        if (entries.isEmpty()) {
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable("magic.skycraft.favorites_empty"), pw - 20);
            int ly = listTop + 6;
            for (var line : lines) {
                g.drawString(font, line, cx - font.width(line) / 2, ly, SkyUi.DIM, false);
                ly += 10;
            }
        } else {
            for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
                int idx = scroll + i;
                Row row = entries.get(idx);
                int ry = listTop + i * ROW;
                boolean hov = SkyUi.inside(mouseX, mouseY, px, ry, px + pw, ry + ROW);
                if (hov) hoverRow = idx;
                boolean sel = idx == selected;
                if (sel) {
                    g.fill(px + 1, ry, px + pw - 1, ry + ROW, 0x34FFFFFF);
                    SkyUi.hline(g, px + 1, px + pw - 1, ry, 0x80);
                    SkyUi.hline(g, px + 1, px + pw - 1, ry + ROW - 1, 0x80);
                } else if (hov) {
                    g.fill(px + 1, ry, px + pw - 1, ry + ROW, 0x16FFFFFF);
                }
                int mid = ry + ROW / 2;
                int equip = row.equip();
                if (equip == InvEntry.RIGHT || equip == InvEntry.WORN) SkyUi.diamond(g, px + 7, mid, 2, SkyUi.GOLD);
                else if (equip == InvEntry.LEFT) SkyUi.diamondOutline(g, px + 7, mid, 2, SkyUi.LEFT_HAND);
                if (row.item != null) {
                    InvEntry e = row.item;
                    g.renderItem(e.icon, px + 12, ry + 1);
                    g.renderItemDecorations(font, e.icon, px + 12, ry + 1);
                    String badge = e.equip == InvEntry.RIGHT ? " [R]" : e.equip == InvEntry.LEFT ? " [L]" : e.equip == InvEntry.WORN ? " [W]" : "";
                    String count = e.count() > 1 ? " (" + e.count() + ")" : "";
                    String name = SkyUi.ellipsize(font, e.name, pw - 40 - font.width(count) - font.width(badge));
                    g.drawString(font, name, px + 32, ry + 5, e.stolen ? SkyUi.STOLEN : sel ? SkyUi.BRIGHT : SkyUi.TEXT, false);
                    int nx = px + 32 + font.width(name);
                    if (!badge.isEmpty()) {
                        g.drawString(font, badge, nx, ry + 5, e.equip == InvEntry.LEFT ? SkyUi.LEFT_HAND : SkyUi.GOLD, false);
                        nx += font.width(badge);
                    }
                    if (!count.isEmpty()) g.drawString(font, count, nx, ry + 5, SkyUi.DIM, false);
                } else {
                    // a favorite spell / shout / power: its school-colored gem, name, L/R/LR/Z badge, and (spells) the magicka cost
                    MagicFavorites.Fav f = row.magic;
                    SkyUi.diamond(g, px + 20, mid, 4, 0xFF000000 | f.color());
                    int eq = minecraft != null && minecraft.player != null ? MagicFavorites.equipped(minecraft.player, f) : 0;
                    String badge = f.kind() == MagicFavorites.Kind.SPELL
                            ? (eq == 3 ? " [LR]" : eq == 1 ? " [R]" : eq == 2 ? " [L]" : "")
                            : (eq != 0 ? " [Z]" : "");
                    String cost = f.spell() != null && minecraft != null && minecraft.player != null
                            ? "-" + Math.round(com.skycraft.magic.spell.SpellMath.cost(minecraft.player, f.spell()))
                            + (f.spell().isConcentration() ? "/s" : "") : "";
                    String name = SkyUi.ellipsize(font, f.name().getString(),
                            pw - 40 - font.width(badge) - (cost.isEmpty() ? 0 : font.width(cost) + 8));
                    g.drawString(font, name, px + 32, ry + 5, sel ? SkyUi.BRIGHT : SkyUi.TEXT, false);
                    if (!badge.isEmpty()) {
                        int bCol = eq == 2 ? SkyUi.LEFT_HAND : SkyUi.GOLD;
                        g.drawString(font, badge, px + 32 + font.width(name), ry + 5, bCol, false);
                    }
                    if (!cost.isEmpty()) g.drawString(font, cost, px + pw - 8 - font.width(cost), ry + 5, 0xFF7FB2FF, false);
                }
            }
            if (entries.size() > rows) {
                int trackTop = listTop, trackH = rows * ROW;
                int barH = Math.max(8, trackH * rows / entries.size());
                int barY = trackTop + (trackH - barH) * scroll / Math.max(1, entries.size() - rows);
                g.fill(px + pw - 3, trackTop, px + pw - 1, trackTop + trackH, 0x30FFFFFF);
                g.fill(px + pw - 3, barY, px + pw - 1, barY + barH, 0xC0C8BC9A);
            }
        }

        String hint = SkyUi.ellipsize(font, Component.translatable("inventory.skycraft.favorites.hint").getString(), pw - 8);
        g.drawString(font, hint, cx - font.width(hint) / 2, py + ph - 12, 0xFF7A766C, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** A spell's name; spells made at a Spellmaking Altar carry their own name in their id. */
    private static Component spellName(String id) {
        com.skycraft.magic.spell.Spell spell = com.skycraft.magic.spell.Spells.byId(id);
        return spell != null ? spell.displayName() : Component.translatable("spell.skycraft." + id);
    }

    private void drawMagicLine(GuiGraphics g, Component label, Component value, int color, int y) {
        String l = SkyUi.caps(label) + "  ";
        String v = SkyUi.ellipsize(font, value.getString(), pw - 16 - font.width(l));
        int total = font.width(l) + font.width(v);
        int x = px + pw / 2 - total / 2;
        g.drawString(font, l, x, y, SkyUi.HEADER, false);
        g.drawString(font, v, x + font.width(l), y, color, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_UP) {
            select(selected - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_DOWN) {
            select(selected + 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_E || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            choose(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_Q) {
            choose(true);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_8) {
            int idx = key - GLFW.GLFW_KEY_1;
            if (idx >= 0 && idx < entries.size()) {
                select(idx);
                choose(false);
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_TAB || InventoryKeys.FAVORITES.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hoverRow >= 0 && hoverRow < entries.size()
                && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            select(hoverRow);
            choose(button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            return true;
        }
        if (!SkyUi.inside(mx, my, px, py, px + pw, py + ph)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (delta > 0) select(selected - 1);
        else if (delta < 0) select(selected + 1);
        return true;
    }
}
