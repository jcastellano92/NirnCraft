package com.skycraft.economy.client;

import com.skycraft.economy.Barter;
import com.skycraft.economy.EconomyPackets;
import com.skycraft.economy.ItemCategory;
import com.skycraft.network.SkyNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Skyrim barter menu: Buy and Sell tabs at top, category tabs below, active catalog centered,
 * merchant and player gold in the header. Click trades one item, shift-click trades the stack.
 */
public class BarterScreen extends Screen {
    private static final int ROW = 20;
    private static final int TOP = 80;
    private static final int GOLD = 0xFFE8C060;
    private static final int TEXT = 0xFFE8E0C8;
    private static final int DIM = 0xFF77736A;
    private static final int STOLEN = 0xFFD06050;
    private static final int LINE = 0xFFC8BC9A;
    private static final int LEFT_HAND = 0xFF9FC4E8;

    private EconomyPackets.BarterState state;
    private boolean sellMode = false;
    private ItemCategory tab = ItemCategory.ALL;
    private List<EconomyPackets.Entry> left = List.of();
    private List<EconomyPackets.Entry> right = List.of();
    private List<EconomyPackets.Entry> back = List.of();
    private boolean buybackMode = false;
    /** The equipped sell row that was clicked once; a second click within 4 s confirms the sale (server re-checks). */
    private int armedIndex = -1;
    private long armedAt;
    private int scrollLeft;
    private int scrollRight;
    private int scrollBack;
    private Component message = Component.empty();
    private long messageAt;

    // hover state computed during render
    private EconomyPackets.Entry hoveredEntry;
    private boolean hoveredBuy;
    private int hoveredTab = -1;
    private int hoveredMode = -1; // 0 = buy, 1 = sell

    public BarterScreen(EconomyPackets.BarterState state) {
        super(state.merchantName());
        update(state);
    }

    public int entityId() {
        return state.entityId();
    }

    public void update(EconomyPackets.BarterState state) {
        this.state = state;
        if (!state.message().getString().isEmpty()) {
            message = state.message();
            messageAt = Util.getMillis();
        }
        refilter();
    }

    private void refilter() {
        left = filter(state.merchant());
        right = filter(state.player());
        back = filter(state.buyback());
        scrollLeft = clampScroll(scrollLeft, left.size());
        scrollRight = clampScroll(scrollRight, right.size());
        scrollBack = clampScroll(scrollBack, back.size());
    }

    private List<EconomyPackets.Entry> filter(List<EconomyPackets.Entry> entries) {
        List<EconomyPackets.Entry> out = new ArrayList<>();
        for (EconomyPackets.Entry e : entries) {
            if (tab == ItemCategory.ALL || e.category() == tab.ordinal()) out.add(e);
        }
        // equipped rows sink to the bottom, so what you can sell freely is together at the top
        out.sort(Comparator.<EconomyPackets.Entry>comparingInt(e -> e.equip() != 0 ? 1 : 0)
                .thenComparingInt(e -> e.category())
                .thenComparing(e -> e.stack().getHoverName().getString())
                .thenComparingInt(EconomyPackets.Entry::index));
        return out;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ layout

    private int tableWidth() {
        return Math.max(260, Math.min(380, width - 48));
    }

    private int tableX() {
        return width / 2 - tableWidth() / 2;
    }

    private int listBottom() {
        return height - 36;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - TOP) / ROW);
    }

    private int clampScroll(int scroll, int size) {
        return Math.max(0, Math.min(scroll, size - visibleRows()));
    }

    private int tabsWidth() {
        int w = 0;
        for (ItemCategory c : ItemCategory.VALUES) w += font.width(c.displayName()) + 14;
        return w;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xC8080808, 0xE0080808);

        // header: merchant name and gold
        g.pose().pushPose();
        g.pose().translate(width / 2f, 6, 0);
        g.pose().scale(1.2f, 1.2f, 1f);
        g.drawCenteredString(font, state.merchantName(), 0, 0, 0xFFF5EBC8);
        g.pose().popPose();

        Component gold = Component.translatable("barter.skycraft.your_gold", com.skycraft.core.Currency.formatGold(state.playerGold()))
                .append(Component.literal("   |   "))
                .append(Component.translatable("barter.skycraft.merchant_gold", com.skycraft.core.Currency.formatGold(state.merchantGold())));
        g.drawCenteredString(font, gold, width / 2, 22, GOLD);

        // BUY / SELL / BUY BACK mode buttons
        hoveredMode = -1;
        int modeW = 70;
        int modeH = 14;
        int modeY = 36;
        int modeX0 = width / 2 - (3 * modeW + 12) / 2;
        String[] modeKeys = {"barter.skycraft.mode.buy", "barter.skycraft.mode.sell", "barter.skycraft.mode.buyback"};
        int active = buybackMode ? 2 : sellMode ? 1 : 0;
        for (int m = 0; m < 3; m++) {
            int bx = modeX0 + m * (modeW + 6);
            boolean hov = mouseX >= bx && mouseX < bx + modeW && mouseY >= modeY && mouseY < modeY + modeH;
            if (hov) hoveredMode = m;
            boolean on = m == active;
            g.fill(bx, modeY, bx + modeW, modeY + modeH, on ? 0x50FFFFFF : hov ? 0x28FFFFFF : 0x18000000);
            if (on) g.fill(bx, modeY + modeH - 1, bx + modeW, modeY + modeH, LINE);
            g.drawCenteredString(font, Component.translatable(modeKeys[m]), bx + modeW / 2, modeY + 3,
                    on ? 0xFFFFFFFF : hov ? TEXT : 0xFFA8A090);
        }

        g.fill(width / 2 - 160, 52, width / 2 + 160, 53, 0x60C8BC9A);

        // category tabs
        hoveredTab = -1;
        int tx = width / 2 - tabsWidth() / 2;
        for (ItemCategory c : ItemCategory.VALUES) {
            int w = font.width(c.displayName()) + 14;
            boolean sel = c == tab;
            boolean hov = mouseX >= tx && mouseX < tx + w && mouseY >= 56 && mouseY < 70;
            if (hov) hoveredTab = c.ordinal();
            if (sel) {
                g.fill(tx, 56, tx + w, 70, 0x50FFFFFF);
                g.fill(tx, 69, tx + w, 70, LINE);
            } else if (hov) {
                g.fill(tx, 56, tx + w, 70, 0x28FFFFFF);
            }
            g.drawString(font, c.displayName(), tx + 7, 59, sel ? 0xFFFFFFFF : hov ? TEXT : 0xFFA8A090, false);
            tx += w;
        }

        // Active table (BUY: merchant goods, SELL: player inventory)
        hoveredEntry = null;
        int txPos = tableX(), tw = tableWidth();
        if (buybackMode) {
            drawCatalog(g, txPos, tw, Component.translatable("barter.skycraft.mode.buyback"), back, scrollBack, true, mouseX, mouseY);
        } else if (!sellMode) {
            drawCatalog(g, txPos, tw, state.merchantName(), left, scrollLeft, true, mouseX, mouseY);
        } else {
            drawCatalog(g, txPos, tw, Component.translatable("barter.skycraft.inventory"), right, scrollRight, false, mouseX, mouseY);
        }

        // footer: last message (fades) and controls hint
        long age = Util.getMillis() - messageAt;
        if (!message.getString().isEmpty() && age < 5000) {
            int alpha = age < 4000 ? 0xFF : (int) (0xFF * (5000 - age) / 1000f);
            g.drawCenteredString(font, message, width / 2, height - 26, (Math.max(8, alpha) << 24) | 0xF0E6C8);
        }
        Component pGold = Component.literal("Your Gold: " + com.skycraft.core.Currency.formatGold(state.playerGold()));
        Component mGold = Component.literal("Merchant Gold: " + com.skycraft.core.Currency.formatGold(state.merchantGold()));
        Component hint = Component.translatable("barter.skycraft.hint");
        int hintW = font.width(hint);
        // the corner readouts repeat the header: on narrow screens (427 px at GUI scale 3) they make room for the hint
        int between = width - 24 - font.width(pGold) - font.width(mGold) - 16;
        if (hintW <= between) {
            g.drawString(font, pGold, 12, height - 14, GOLD, true);
            g.drawString(font, mGold, width - font.width(mGold) - 12, height - 14, GOLD, true);
        }
        float hintScale = Math.min(1f, (width - 16) / (float) Math.max(1, hintW));
        g.pose().pushPose();
        g.pose().translate(width / 2f, height - 13, 0);
        g.pose().scale(hintScale, hintScale, 1f);
        g.drawCenteredString(font, hint, 0, 0, 0xFF8A8478);
        g.pose().popPose();

        super.render(g, mouseX, mouseY, partialTick);

        if (hoveredEntry != null) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, hoveredEntry.stack()));
            lines.add(Component.translatable(buybackMode ? "barter.skycraft.buyback.tooltip"
                            : hoveredBuy ? "barter.skycraft.tooltip.buy" : "barter.skycraft.tooltip.sell",
                    com.skycraft.core.Currency.formatCompact(hoveredEntry.price())).withStyle(ChatFormatting.GOLD));
            if (hoveredEntry.equip() != 0) {
                String key = hoveredEntry.equip() == 1 ? "right" : hoveredEntry.equip() == 2 ? "left" : "worn";
                lines.add(Component.translatable("inventory.skycraft.equipped." + key).withStyle(ChatFormatting.YELLOW));
            }
            if (hoveredBuy) {
                int carrying = countCarrying(hoveredEntry.stack());
                lines.add(Component.literal("Carrying: " + carrying).withStyle(ChatFormatting.GRAY));
                lines.add(Component.literal("Your Gold: " + com.skycraft.core.Currency.formatGold(state.playerGold())).withStyle(ChatFormatting.YELLOW));
            }
            if (hoveredEntry.status() == Barter.STATUS_NOT_DEALT) {
                lines.add(Component.translatable("barter.skycraft.status.not_dealt").withStyle(ChatFormatting.RED));
            } else if (hoveredEntry.status() == Barter.STATUS_STOLEN) {
                lines.add(Component.translatable("barter.skycraft.status.stolen").withStyle(ChatFormatting.RED));
            }
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private int countCarrying(ItemStack target) {
        if (minecraft == null || minecraft.player == null) return 0;
        int count = 0;
        for (ItemStack s : minecraft.player.getInventory().items) {
            if (!s.isEmpty() && s.getItem() == target.getItem()) count += s.getCount();
        }
        return count + com.skycraft.inventory.Pack.count(minecraft.player, target.getItem()); // overflow pack too
    }

    private void drawCatalog(GuiGraphics g, int x, int w, Component title, List<EconomyPackets.Entry> entries, int scroll,
                             boolean buy, int mouseX, int mouseY) {
        int bottom = listBottom();
        g.fill(x, TOP - 10, x + w, bottom + 2, 0x70000000);
        g.drawString(font, font.plainSubstrByWidth(title.getString(), w - 70), x + 6, TOP - 8, LINE, false);
        Component priceHeader = Component.translatable("barter.skycraft.price");
        g.drawString(font, priceHeader, x + w - 8 - font.width(priceHeader), TOP - 8, LINE, false);
        g.fill(x, TOP - 1, x + w, TOP, 0x80C8BC9A);

        if (entries.isEmpty()) {
            g.drawCenteredString(font, Component.translatable(buybackMode ? "barter.skycraft.buyback.empty" : "barter.skycraft.empty"), x + w / 2, TOP + 12, DIM);
            return;
        }
        int rows = visibleRows();
        for (int i = 0; i < rows && scroll + i < entries.size(); i++) {
            EconomyPackets.Entry e = entries.get(scroll + i);
            int y = TOP + 2 + i * ROW;
            boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW;
            if (hov) {
                hoveredEntry = e;
                hoveredBuy = buy;
                g.fill(x, y, x + w, y + ROW, 0x38FFFFFF);
                g.fill(x, y, x + 2, y + ROW, LINE);
            }
            ItemStack stack = e.stack();
            g.renderItem(stack, x + 4, y + 2);

            String price = com.skycraft.core.Currency.formatCompact(e.price()) + "g";
            int priceW = font.width(price);
            boolean ok = e.status() == Barter.STATUS_OK;
            boolean affordable = !buy || state.playerGold() >= e.price();
            int nameColor = e.status() == Barter.STATUS_STOLEN ? STOLEN : ok ? (hov ? 0xFFFFFFFF : TEXT) : DIM;

            // equipped rows (worn, held, left hand): "E" badge and the inventory screen's equipped colours
            int badgeW = 0;
            if (e.equip() != 0 && !buy) {
                int tint = e.equip() == 2 ? LEFT_HAND : GOLD;
                String badge = Component.translatable("barter.skycraft.equipped.badge").getString();
                badgeW = font.width(badge) + 8;
                int bx = x + w - 8 - priceW - badgeW - 4;
                g.fill(x, y, x + w, y + ROW, 0x20000000 | (tint & 0xFFFFFF));
                g.fill(bx, y + 4, bx + badgeW, y + ROW - 4, 0xC0000000 | (tint & 0xFFFFFF));
                g.drawString(font, badge, bx + 4, y + 6, 0xFF1A1710, false);
                nameColor = tint;
                badgeW += 4;
            }

            String count = stack.getCount() > 1 ? " (" + stack.getCount() + ")" : "";
            int nameSpace = w - 30 - priceW - 12 - font.width(count) - badgeW;
            String name = font.plainSubstrByWidth(stack.getHoverName().getString(), Math.max(10, nameSpace));
            g.drawString(font, name, x + 26, y + 6, nameColor, false);
            if (!count.isEmpty()) g.drawString(font, count, x + 26 + font.width(name), y + 6, DIM, false);
            g.drawString(font, price, x + w - 8 - priceW, y + 6, !ok ? DIM : affordable ? GOLD : 0xFFC04040, false);
        }

        // scrollbar
        if (entries.size() > rows) {
            int trackH = bottom - TOP;
            int barH = Math.max(10, trackH * rows / entries.size());
            int barY = TOP + (trackH - barH) * scroll / Math.max(1, entries.size() - rows);
            g.fill(x + w - 2, TOP, x + w, bottom, 0x40FFFFFF);
            g.fill(x + w - 2, barY, x + w, barY + barH, 0xC0C8BC9A);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hoveredMode >= 0) {
            setMode(hoveredMode);
            return true;
        }
        if (hoveredTab >= 0) {
            setTab(ItemCategory.byOrdinal(hoveredTab));
            return true;
        }
        if (hoveredEntry != null && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            ItemStack stack = hoveredEntry.stack();
            int op = buybackMode ? Barter.OP_BUYBACK : sellMode ? Barter.OP_SELL : Barter.OP_BUY;
            // an equipped row needs a second click on the same row within 4 seconds (the server asks for it too)
            boolean confirm = false;
            if (op == Barter.OP_SELL && hoveredEntry.equip() != 0) {
                long now = Util.getMillis();
                confirm = armedIndex == hoveredEntry.index() && now - armedAt < 4000;
                armedIndex = confirm ? -1 : hoveredEntry.index();
                armedAt = now;
            }
            SkyNetwork.sendToServer(new EconomyPackets.BarterAction(state.entityId(), op, hoveredEntry.index(),
                    hasShiftDown(), BuiltInRegistries.ITEM.getKey(stack.getItem()), confirm));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int step = delta > 0 ? -1 : delta < 0 ? 1 : 0;
        if (buybackMode) {
            scrollBack = clampScroll(scrollBack + step, back.size());
        } else if (!sellMode) {
            scrollLeft = clampScroll(scrollLeft + step, left.size());
        } else {
            scrollRight = clampScroll(scrollRight + step, right.size());
        }
        return true;
    }

    private void setMode(int mode) {
        buybackMode = mode == 2;
        sellMode = mode == 1;
        armedIndex = -1;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_TAB) {
            setMode(((buybackMode ? 2 : sellMode ? 1 : 0) + 1) % 3);
            return true;
        }
        int n = ItemCategory.VALUES.length;
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) {
            setTab(ItemCategory.byOrdinal((tab.ordinal() + n - 1) % n));
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) {
            setTab(ItemCategory.byOrdinal((tab.ordinal() + 1) % n));
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void setTab(ItemCategory c) {
        if (c == tab) return;
        tab = c;
        scrollLeft = 0;
        scrollRight = 0;
        scrollBack = 0;
        refilter();
    }
}
