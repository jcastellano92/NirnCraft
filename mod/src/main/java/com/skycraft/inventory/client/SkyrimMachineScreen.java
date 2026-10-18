package com.skycraft.inventory.client;

import com.skycraft.economy.ItemValues;
import com.skycraft.inventory.InventoryPackets;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.inventory.Pack;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Skyrim look of the vanilla workstation menus: furnace, blast furnace, smoker, brewing stand, anvil, grindstone,
 * smithing table, cartography table, stonecutter, loom, enchanting table, beacon, crafting table and the trader's
 * trade screen. The vanilla menu (and its server logic) is untouched; only the drawing and the input are replaced, the
 * way {@link SkyrimContainerScreen} does for chests.
 *
 * <p>Layout (the container screen's): <b>Workstation</b> tab: the machine's slots as named rows (or a 3x3 grid for the
 * crafting table), then the choices the vanilla screen offered as buttons (stonecutter cuts, loom patterns, enchanting
 * options, trades, beacon powers); on the right the selected row's item, its weight and value, progress meters and notes
 * (anvil cost), and the carry weight. <b>My Items</b> tab: everything you carry, the pack's overflow rows included,
 * with what the machine takes first. Keys: Up/Down (W/S) move, Left/Right (A/D) move in the crafting grid,
 * F or E = act, R = take everything back, Space / 1 / 2 = tabs, N = rename (anvil), Tab or Esc = close.</p>
 *
 * <p>Moves are {@link InventoryPackets.MachineAction}s (validated on the server, executed as real vanilla menu clicks);
 * choices are the vanilla button packets.</p>
 */
public class SkyrimMachineScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final int ROW = 18;
    private static final int CELL = 26;

    private static final int K_CELL = 0, K_HEADER = 1, K_CHOICE = 2;

    private record Row(int kind, int index) {}

    /** One line of the My Items tab: every stack of an item (and NBT) in the slots and in the pack. */
    private static final class Held {
        ItemStack stack;
        int count;
        float weight;
        int value;
        final List<Integer> slots = new ArrayList<>();
        int pack = -1;
        boolean fits;

        int source() {
            return slots.isEmpty() ? Pack.VIRTUAL_BASE + pack : slots.get(0);
        }
    }

    private final MachineProfile profile;
    private int tab;
    private int sel;
    private int scroll;
    private int itemSel;
    private int itemScroll;
    /** Machine slot an empty row asked for ("Place" from My Items goes there); -1 = let the menu choose. */
    private int placeTarget = -1;
    private int hover = -1;
    private EditBox rename;
    private boolean renaming;
    private ItemRowKey renameItemKey;

    public SkyrimMachineScreen(AbstractContainerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 380, 220);
        this.profile = MachineProfile.of(menu);
        if (profile == null) throw new IllegalArgumentException("no machine profile for " + menu.getClass().getName());


    }

    /** Whether the Skyrim machine screen can show this menu. */
    public static boolean supports(AbstractContainerMenu menu) {
        return MachineProfile.of(menu) != null;
    }

    // ------------------------------------------------------------------ geometry (same as the container screen)

    private int listX() {
        return leftPos + 10;
    }

    private int listY() {
        return topPos + 26;
    }

    private int listW() {
        return imageWidth / 2 + 10;
    }

    private int detX() {
        return leftPos + listW() + 15 + 10;
    }

    private int detW() {
        return leftPos + imageWidth - detX() - 10;
    }

    private int visibleRows() {
        return (imageHeight - 56) / ROW;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        if (profile.renameable()) {
            rename = new EditBox(font, detX(), topPos + imageHeight - 54, detW(), 14, Component.translatable("container.repair"));
            rename.setCanLoseFocus(false);
            rename.setTextColor(-1);
            rename.setTextColorUneditable(-1);
            rename.setMaxLength(AnvilMenu.MAX_NAME_LENGTH);
            rename.setResponder(this::onNameChanged);
            renaming = false;
        }
    }

    // ------------------------------------------------------------------ rows

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        List<MachineProfile.Cell> cells = profile.cells();
        for (int i = 0; i < cells.size(); i++) out.add(new Row(K_CELL, i));
        if (!profile.grid()) {
            List<MachineProfile.Choice> choices = profile.choices();
            if (!choices.isEmpty() || profile.choicesTitle() != null) {
                out.add(new Row(K_HEADER, 0));
                for (int i = 0; i < choices.size(); i++) out.add(new Row(K_CHOICE, i));
            }
        }
        return out;
    }

    private static boolean selectable(Row r) {
        return r.kind() != K_HEADER;
    }

    private MachineProfile.Cell cellOf(Row r) {
        return profile.cells().get(r.index());
    }

    private ItemStack stackOf(MachineProfile.Cell c) {
        return menu.getSlot(c.slot()).getItem();
    }

    /** The player's stacks, grouped by item and NBT, slots and pack together; what the machine takes comes first. */
    private List<Held> held() {
        List<Held> out = new ArrayList<>();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return out;
        Inventory inv = player.getInventory();
        Map<ItemRowKey, Held> groups = new LinkedHashMap<>();
        for (Slot s : menu.slots) {
            if (s.container != inv || !s.hasItem()) continue;
            ItemStack st = s.getItem();
            Held h = groups.computeIfAbsent(key(st), k -> {
                Held n = new Held();
                n.stack = st.copy();
                return n;
            });
            h.slots.add(s.index);
            h.count += st.getCount();
            h.weight += ItemWeights.getStack(st);
        }
        List<ItemStack> packed = Pack.entries(player);
        for (int i = 0; i < packed.size(); i++) {
            ItemStack st = packed.get(i);
            if (st.isEmpty()) continue;
            Held h = groups.computeIfAbsent(key(st), k -> {
                Held n = new Held();
                n.stack = st.copy();
                return n;
            });
            h.pack = i;
            h.count += st.getCount();
            h.weight += ItemWeights.getStack(st);
        }
        for (Held h : groups.values()) {
            h.value = ItemValues.get(h.stack) * h.count;
            h.fits = profile.accepts(h.stack);
            out.add(h);
        }
        out.sort((a, b) -> a.fits == b.fits ? 0 : a.fits ? -1 : 1);
        return out;
    }

    private static ItemRowKey key(ItemStack st) {
        return ItemRowKey.of(st);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // the whole Skyrim panel is drawn in render()
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // no vanilla labels
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        int px = leftPos, py = topPos, pw = imageWidth, ph = imageHeight;
        SkyUi.panel(g, px, py, px + pw, py + ph);

        int headerY = py + 8;
        g.text(font, tab == 0 ? title : Component.translatable("inventory.skycraft.my_items"), px + 12, headerY, SkyUi.TEXT, false);
        String t0 = "[1] " + Component.translatable("inventory.skycraft.machine.tab.station").getString();
        String t1 = "[2] " + Component.translatable("inventory.skycraft.my_items").getString();
        int tabX = px + pw - font.width(t0) - font.width(t1) - 24;
        g.text(font, t0, tabX, headerY, tab == 0 ? SkyUi.GOLD : SkyUi.DIM, false);
        g.text(font, t1, tabX + font.width(t0) + 12, headerY, tab == 1 ? SkyUi.GOLD : SkyUi.DIM, false);
        SkyUi.hline(g, px + 8, px + pw - 8, py + 22, 0x80);

        hover = -1;
        int sepX = px + listW() + 15;
        if (tab == 0) {
            if (profile.grid()) drawGrid(g, mouseX, mouseY);
            else drawRows(g, mouseX, mouseY);
        } else {
            drawHeld(g, mouseX, mouseY);
        }
        g.fill(sepX, py + 26, sepX + 1, py + ph - 30, 0x50C8BC9A);
        drawDetails(g, mouseX, mouseY, partialTick);
        drawHints(g);

        if (renaming && rename != null) rename.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawRows(GuiGraphicsExtractor g, int mx, int my) {
        List<Row> rows = rows();
        if (sel >= rows.size()) sel = Math.max(0, rows.size() - 1);
        if (!rows.isEmpty() && !selectable(rows.get(sel))) sel = firstSelectable(rows);
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        int lx = listX(), ly = listY(), lw = listW();
        List<MachineProfile.Choice> choices = null;
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) break;
            Row r = rows.get(idx);
            int y = ly + i * ROW;
            if (r.kind() == K_HEADER) {
                Component h = profile.choicesTitle();
                if (h != null) g.text(font, SkyUi.caps(h), lx + 4, y + 5, SkyUi.HEADER, false);
                SkyUi.hline(g, lx, lx + lw, y + ROW - 2, 0x60);
                continue;
            }
            boolean hovered = SkyUi.inside(mx, my, lx, y, lx + lw, y + ROW);
            boolean selected = idx == sel;
            if (hovered) hover = idx;
            if (selected || hovered) {
                g.fill(lx, y, lx + lw, y + ROW, selected ? 0x40FFFFFF : 0x20FFFFFF);
                SkyUi.hline(g, lx, lx + lw, y, selected ? 0x90 : 0x50);
                SkyUi.hline(g, lx, lx + lw, y + ROW - 1, selected ? 0x90 : 0x50);
            }
            if (r.kind() == K_CELL) {
                MachineProfile.Cell c = cellOf(r);
                ItemStack st = stackOf(c);
                String role = SkyUi.ellipsize(font, c.label().getString(), 78);
                g.text(font, role, lx + 6, y + 5, SkyUi.HEADER, false);
                int nx = lx + 88;
                if (st.isEmpty()) {
                    g.text(font, Component.translatable("inventory.skycraft.machine.empty"), nx, y + 5, SkyUi.DIM, false);
                } else {
                    g.item(st, nx, y + 1);
                    String name = st.getHoverName().getString() + (st.getCount() > 1 ? " (" + st.getCount() + ")" : "");
                    g.text(font, SkyUi.ellipsize(font, name, lw - (nx - lx) - 26), nx + 20, y + 5,
                            selected ? SkyUi.BRIGHT : hovered ? SkyUi.TEXT : SkyUi.DIM, false);
                }
            } else {
                if (choices == null) choices = profile.choices();
                if (r.index() >= choices.size()) continue;
                MachineProfile.Choice ch = choices.get(r.index());
                g.item(ch.icon(), lx + 2, y + 1);
                int color = !ch.enabled() ? SkyUi.DIM : ch.selected() ? SkyUi.GOLD : selected ? SkyUi.BRIGHT : hovered ? SkyUi.TEXT : SkyUi.DIM;
                String detail = ch.detail().getString();
                int dw = detail.isEmpty() ? 0 : font.width(detail) + 6;
                g.text(font, SkyUi.ellipsize(font, ch.label().getString(), lw - 28 - dw), lx + 22, y + 5, color, false);
                if (!detail.isEmpty()) g.text(font, detail, lx + lw - font.width(detail) - 6, y + 5, SkyUi.GOLD, false);
                if (ch.selected()) SkyUi.diamond(g, lx + lw - 3, y + ROW / 2, 1, SkyUi.GOLD);
            }
        }
    }

    private int gridOriginX() {
        return listX() + 24;
    }

    private int gridOriginY() {
        return listY() + 20;
    }

    private void drawGrid(GuiGraphicsExtractor g, int mx, int my) {
        List<MachineProfile.Cell> cells = profile.cells();
        if (sel >= cells.size()) sel = 0;
        int ox = gridOriginX(), oy = gridOriginY();
        for (int i = 0; i < cells.size(); i++) {
            MachineProfile.Cell c = cells.get(i);
            int x = ox + c.col() * (CELL + 4) + (c.col() >= 3 ? 26 : 0);
            int y = oy + c.row() * (CELL + 4);
            boolean selected = i == sel;
            boolean hovered = SkyUi.inside(mx, my, x, y, x + CELL, y + CELL);
            if (hovered) hover = i;
            g.fill(x, y, x + CELL, y + CELL, 0x50000000);
            int line = selected ? 0xFFE8C060 : hovered ? 0xFFC8BC9A : 0xFF6A634F;
            g.fill(x, y, x + CELL, y + 1, line);
            g.fill(x, y + CELL - 1, x + CELL, y + CELL, line);
            g.fill(x, y, x + 1, y + CELL, line);
            g.fill(x + CELL - 1, y, x + CELL, y + CELL, line);
            ItemStack st = stackOf(c);
            if (!st.isEmpty()) {
                g.item(st, x + 5, y + 5);
                g.itemDecorations(font, st, x + 5, y + 5);
            }
        }
        // the arrow into the result cell
        int ax = ox + 3 * (CELL + 4) + 4;
        int ay = oy + (CELL + 4) + CELL / 2 - 4;
        g.text(font, ">", ax, ay, SkyUi.GOLD, false);
        MachineProfile.Cell cur = cells.get(sel);
        g.text(font, cur.label(), listX() + 6, listY() + 4, SkyUi.HEADER, false);
    }

    private void drawHeld(GuiGraphicsExtractor g, int mx, int my) {
        List<Held> items = held();
        if (itemSel >= items.size()) itemSel = Math.max(0, items.size() - 1);
        int visible = visibleRows();
        itemScroll = Math.max(0, Math.min(itemScroll, Math.max(0, items.size() - visible)));
        int lx = listX(), ly = listY(), lw = listW();
        if (items.isEmpty()) {
            g.text(font, Component.translatable("inventory.skycraft.empty"), lx + 8, ly + 12, SkyUi.DIM, false);
            return;
        }
        for (int i = 0; i < visible; i++) {
            int idx = itemScroll + i;
            if (idx >= items.size()) break;
            Held h = items.get(idx);
            int y = ly + i * ROW;
            boolean hovered = SkyUi.inside(mx, my, lx, y, lx + lw, y + ROW);
            boolean selected = idx == itemSel;
            if (hovered) hover = idx;
            if (selected || hovered) {
                g.fill(lx, y, lx + lw, y + ROW, selected ? 0x40FFFFFF : 0x20FFFFFF);
                SkyUi.hline(g, lx, lx + lw, y, selected ? 0x90 : 0x50);
                SkyUi.hline(g, lx, lx + lw, y + ROW - 1, selected ? 0x90 : 0x50);
            }
            g.item(h.stack, lx + 2, y + 1);
            String name = h.stack.getHoverName().getString() + (h.count > 1 ? " (" + h.count + ")" : "");
            String val = h.value + "g";
            String wt = ItemWeights.format(h.weight);
            int infoX = lx + lw - font.width(val) - 6;
            int color = !h.fits ? SkyUi.DIM : selected ? SkyUi.BRIGHT : hovered ? SkyUi.TEXT : 0xFFC8C0A8;
            g.text(font, SkyUi.ellipsize(font, name, lw - 75), lx + 22, y + 5, color, false);
            g.text(font, val, infoX, y + 5, SkyUi.GOLD, false);
            g.text(font, wt, infoX - font.width(wt) - 6, y + 5, SkyUi.DIM, false);
        }
    }

    private void drawDetails(GuiGraphicsExtractor g, int mx, int my, float pt) {
        int detX = detX(), detW = detW(), detY = listY();
        int cx = detX + detW / 2;
        ItemStack show = ItemStack.EMPTY;
        Component name = Component.empty();
        Component line2 = Component.empty();
        float weight = 0f;
        int value = 0;
        boolean stats = false;

        if (tab == 1) {
            List<Held> items = held();
            if (itemSel >= 0 && itemSel < items.size()) {
                Held h = items.get(itemSel);
                show = h.stack;
                name = h.stack.getHoverName();
                line2 = SkyrimContainerScreen.kindLine(h.stack);
                weight = h.weight;
                value = h.value;
                stats = true;
            }
        } else {
            List<Row> rows = rows();
            if (sel >= 0 && sel < rows.size()) {
                Row r = rows.get(sel);
                if (r.kind() == K_CELL) {
                    MachineProfile.Cell c = cellOf(r);
                    ItemStack st = stackOf(c);
                    if (st.isEmpty()) {
                        name = c.label();
                        line2 = Component.translatable("inventory.skycraft.machine.empty_hint");
                    } else {
                        show = st;
                        name = st.getHoverName();
                        line2 = SkyrimContainerScreen.kindLine(st);
                        weight = ItemWeights.getStack(st);
                        value = ItemValues.get(st) * st.getCount();
                        stats = true;
                    }
                } else if (r.kind() == K_CHOICE) {
                    List<MachineProfile.Choice> choices = profile.choices();
                    if (r.index() < choices.size()) {
                        MachineProfile.Choice ch = choices.get(r.index());
                        show = ch.icon();
                        name = ch.label();
                        line2 = ch.detail();
                    }
                }
            }
        }

        int previewH = 46;
        if (!show.isEmpty()) SkyrimContainerScreen.renderPreview(g, show, cx, detY + previewH / 2, previewH / 16f * 0.9f);
        int y = detY + previewH + 4;
        String n = SkyUi.ellipsize(font, name.getString(), detW);
        g.text(font, n, cx - font.width(n) / 2, y, SkyUi.BRIGHT, false);
        y += 12;
        String k = SkyUi.ellipsize(font, line2.getString(), detW);
        g.text(font, k, cx - font.width(k) / 2, y, SkyUi.DIM, false);
        y += 12;
        SkyUi.divider(g, cx, y, detW / 2 - 6);
        y += 8;
        if (stats) {
            double arm = SkyrimContainerScreen.armor(show);
            double dmg = SkyrimContainerScreen.damage(show);
            if (arm > 0) {
                g.text(font, "ARMOR", detX, y, SkyUi.HEADER, false);
                g.text(font, String.valueOf((int) arm), detX + 50, y, SkyUi.BRIGHT, false);
                y += 11;
            } else if (dmg > 1) {
                g.text(font, "DAMAGE", detX, y, SkyUi.HEADER, false);
                g.text(font, String.valueOf((int) dmg), detX + 50, y, SkyUi.BRIGHT, false);
                y += 11;
            }
            g.text(font, "WEIGHT", detX, y, SkyUi.HEADER, false);
            g.text(font, ItemWeights.format(weight), detX + 50, y, SkyUi.BRIGHT, false);
            y += 11;
            g.text(font, "VALUE", detX, y, SkyUi.HEADER, false);
            g.text(font, String.valueOf(value), detX + 50, y, SkyUi.GOLD, false);
        }

        // meters (fuel, progress, brewing) and notes (anvil cost) of the machine
        int my0 = topPos + 144;
        if (tab == 0) {
            for (MachineProfile.Meter m : profile.meters()) {
                g.text(font, SkyUi.caps(m.label()), detX, my0, SkyUi.HEADER, false);
                SkyUi.bar(g, detX + 4, my0 + 10, detW - 8, m.fill(), m.rgb());
                my0 += 17;
            }
            List<Component> notes = profile.notes();
            for (int i = 0; i < notes.size(); i++) {
                g.text(font, SkyUi.ellipsize(font, notes.get(i).getString(), detW), detX, my0, profile.noteColor(i), false);
                my0 += 11;
            }
        }
        SkyUi.carryLine(g, font, detX, topPos + imageHeight - 35, leftPos + imageWidth - 10);
    }

    private void drawHints(GuiGraphicsExtractor g) {
        int px = leftPos, pw = imageWidth;
        int y = topPos + imageHeight - 22;
        SkyUi.hline(g, px + 8, px + pw - 8, y - 4, 0x80);
        int x = px + 12;
        String act = actionHint();
        g.text(font, "[F] " + act, x, y, SkyUi.GOLD, false);
        x += font.width("[F] " + act) + 16;
        if (tab == 0) {
            String all = "[R] " + Component.translatable("inventory.skycraft.machine.hint.all").getString();
            g.text(font, all, x, y, SkyUi.GOLD, false);
            x += font.width(all) + 16;
        }
        String space = "[Space] " + Component.translatable(tab == 0 ? "inventory.skycraft.my_items" : "inventory.skycraft.machine.tab.station").getString();
        g.text(font, space, x, y, SkyUi.DIM, false);
        x += font.width(space) + 16;
        if (profile.renameable() && tab == 0) {
            String rn = "[N] " + Component.translatable("inventory.skycraft.machine.hint.rename").getString();
            g.text(font, rn, x, y, renaming ? SkyUi.GOLD : SkyUi.DIM, false);
        }
        String close = "[Tab/Esc] " + Component.translatable("inventory.skycraft.machine.hint.close").getString();
        g.text(font, close, px + pw - font.width(close) - 12, y, SkyUi.DIM, false);
    }

    private String actionHint() {
        String k;
        if (tab == 1) {
            k = placeTarget >= 0 ? "place" : "add";
        } else {
            k = "select";
            List<Row> rows = rows();
            if (sel >= 0 && sel < rows.size() && rows.get(sel).kind() == K_CELL) {
                MachineProfile.Cell c = cellOf(rows.get(sel));
                boolean has = !stackOf(c).isEmpty();
                k = has ? (c.role() == MachineProfile.Role.RESULT ? "craft" : "take") : "fill";
            }
        }
        return Component.translatable("inventory.skycraft.machine.hint." + k).getString();
    }

    // ------------------------------------------------------------------ actions

    private void send(int kind, int a, int b) {
        SkyNetwork.sendToServer(new InventoryPackets.MachineAction(menu.containerId, kind, a, b));
    }

    /** F / E / a click on the selected row. */
    private void activate() {
        if (tab == 1) {
            List<Held> items = held();
            if (itemSel < 0 || itemSel >= items.size()) return;
            Held h = items.get(itemSel);
            if (placeTarget >= 0) {
                send(profile.grid() ? InventoryPackets.MachineAction.PLACE_ONE : InventoryPackets.MachineAction.PLACE_ALL, h.source(), placeTarget);
                int cell = cellIndexOfSlot(placeTarget);
                if (cell >= 0) sel = cell;
                placeTarget = -1;
                tab = 0;
            } else {
                send(InventoryPackets.MachineAction.FEED, h.source(), -1);
            }
            return;
        }
        List<Row> rows = rows();
        if (sel < 0 || sel >= rows.size()) return;
        Row r = rows.get(sel);
        if (r.kind() == K_CELL) {
            MachineProfile.Cell c = cellOf(r);
            if (!stackOf(c).isEmpty()) {
                send(InventoryPackets.MachineAction.TAKE, c.slot(), -1);
            } else if (c.role() != MachineProfile.Role.RESULT) {
                // an empty input: pick what goes in from My Items
                placeTarget = c.slot();
                tab = 1;
                itemSel = 0;
                itemScroll = 0;
            }
        } else if (r.kind() == K_CHOICE) {
            List<MachineProfile.Choice> choices = profile.choices();
            if (r.index() < choices.size() && choices.get(r.index()).enabled()) profile.choose(r.index());
        }
    }

    private int cellIndexOfSlot(int slot) {
        List<MachineProfile.Cell> cells = profile.cells();
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).slot() == slot) return i;
        }
        return -1;
    }

    private void takeAll() {
        for (MachineProfile.Cell c : profile.cells()) {
            if (MachineProfile.takeAllSkips(c) || stackOf(c).isEmpty()) continue;
            send(InventoryPackets.MachineAction.TAKE, c.slot(), -1);
        }
    }

    private void setTab(int t) {
        tab = t;
        if (t == 0) placeTarget = -1;
        itemSel = 0;
        itemScroll = 0;
    }

    private void onNameChanged(String name) {
        Slot slot = menu.getSlot(0);
        if (!slot.hasItem() || !(menu instanceof AnvilMenu anvil)) return;
        String s = name;
        if (!slot.getItem().has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) && name.equals(slot.getItem().getHoverName().getString())) s = "";
        Minecraft mc = Minecraft.getInstance();
        if (anvil.setItemName(s) && mc.player != null && mc.player.connection != null) {
            mc.player.connection.send(new ServerboundRenameItemPacket(s));
        }
    }

    private void startRename() {
        if (rename == null || !menu.getSlot(0).hasItem()) return;
        renaming = true;
        rename.setValue(menu.getSlot(0).getItem().getHoverName().getString());
        rename.setFocused(true);
        setFocused(rename);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (rename != null) {
            // a different item in the anvil's first slot: the box follows it (vanilla does the same)
            ItemRowKey k = menu.getSlot(0).hasItem() ? key(menu.getSlot(0).getItem()) : null;
            if (!java.util.Objects.equals(k, renameItemKey)) {
                renameItemKey = k;
                if (renaming && k != null) rename.setValue(menu.getSlot(0).getItem().getHoverName().getString());
                if (k == null) renaming = false;
            }
        }
    }

    // ------------------------------------------------------------------ input

    private int firstSelectable(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) if (selectable(rows.get(i))) return i;
        return 0;
    }

    private void moveSelection(int dir) {
        if (tab == 1) {
            int n = held().size();
            itemSel = Math.max(0, Math.min(n - 1, itemSel + dir));
            int visible = visibleRows();
            if (itemSel < itemScroll) itemScroll = itemSel;
            if (itemSel >= itemScroll + visible) itemScroll = itemSel - visible + 1;
            return;
        }
        if (profile.grid()) {
            gridMove(0, dir);
            return;
        }
        List<Row> rows = rows();
        int i = sel + dir;
        while (i >= 0 && i < rows.size() && !selectable(rows.get(i))) i += dir;
        if (i < 0 || i >= rows.size()) return;
        sel = i;
        int visible = visibleRows();
        if (sel < scroll) scroll = sel;
        if (sel >= scroll + visible) scroll = sel - visible + 1;
    }

    private void gridMove(int dx, int dy) {
        List<MachineProfile.Cell> cells = profile.cells();
        MachineProfile.Cell cur = cells.get(Math.max(0, Math.min(sel, cells.size() - 1)));
        for (int i = 0; i < cells.size(); i++) {
            MachineProfile.Cell c = cells.get(i);
            if (c.col() == cur.col() + dx && c.row() == cur.row() + dy) {
                sel = i;
                return;
            }
        }
        // the result cell sits beside the middle row: reachable from the right edge of any row, and back
        if (dx > 0 && cur.col() == 2) {
            for (int i = 0; i < cells.size(); i++) if (cells.get(i).col() == 3) sel = i;
        } else if (dx < 0 && cur.col() == 3) {
            for (int i = 0; i < cells.size(); i++) if (cells.get(i).col() == 2 && cells.get(i).row() == 1) sel = i;
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key(), scanCode = event.keycode(), modifiers = event.modifiers();
        if (renaming && rename != null) {
            if (keyCode == InputConstants.KEY_ESCAPE || keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                renaming = false;
                return true;
            }
            return rename.keyPressed(event) || rename.canConsumeInput();
        }
        switch (keyCode) {
            case InputConstants.KEY_F, InputConstants.KEY_E, InputConstants.KEY_RETURN -> {
                activate();
                return true;
            }
            case InputConstants.KEY_R -> {
                if (tab == 0) takeAll();
                return true;
            }
            case InputConstants.KEY_N -> {
                if (profile.renameable() && tab == 0) startRename();
                return true;
            }
            case InputConstants.KEY_SPACE -> {
                setTab(1 - tab);
                return true;
            }
            case InputConstants.KEY_1 -> {
                setTab(0);
                return true;
            }
            case InputConstants.KEY_2 -> {
                setTab(1);
                return true;
            }
            case InputConstants.KEY_3, InputConstants.KEY_4, InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9 -> {
                return true;
            }
            case InputConstants.KEY_UP, InputConstants.KEY_W -> {
                moveSelection(-1);
                return true;
            }
            case InputConstants.KEY_DOWN, InputConstants.KEY_S -> {
                moveSelection(1);
                return true;
            }
            case InputConstants.KEY_LEFT, InputConstants.KEY_A -> {
                if (tab == 0 && profile.grid()) gridMove(-1, 0);
                return true;
            }
            case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> {
                if (tab == 0 && profile.grid()) gridMove(1, 0);
                return true;
            }
            case InputConstants.KEY_TAB, InputConstants.KEY_ESCAPE -> {
                onClose();
                return true;
            }
            default -> {
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        char c = (char) event.codepoint();
        if (renaming && rename != null) return rename.charTyped(event);
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        int mx = (int) mouseX, my = (int) mouseY;
        if (renaming && rename != null && rename.mouseClicked(event, doubleClick)) return true;
        if (button != 0) return true; // never fall through to vanilla slot clicking: the slots are not drawn
        int px = leftPos, py = topPos, pw = imageWidth;
        int headerY = py + 8;
        String t0 = "[1] " + Component.translatable("inventory.skycraft.machine.tab.station").getString();
        String t1 = "[2] " + Component.translatable("inventory.skycraft.my_items").getString();
        int tabX = px + pw - font.width(t0) - font.width(t1) - 24;
        if (SkyUi.inside(mx, my, tabX, headerY - 2, tabX + font.width(t0), headerY + 10)) {
            setTab(0);
            return true;
        }
        if (SkyUi.inside(mx, my, tabX + font.width(t0) + 12, headerY - 2, px + pw - 8, headerY + 10)) {
            setTab(1);
            return true;
        }
        if (hover >= 0) {
            if (tab == 1) itemSel = hover;
            else sel = hover;
            activate();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double delta) {
        if (delta == 0) return super.mouseScrolled(mouseX, mouseY, horizontal, delta);
        if (tab == 1) {
            int max = Math.max(0, held().size() - visibleRows());
            itemScroll = Math.max(0, Math.min(max, itemScroll - (int) Math.signum(delta)));
        } else if (!profile.grid()) {
            int max = Math.max(0, rows().size() - visibleRows());
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        }
        return true;
    }
}
