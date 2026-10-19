package com.nirncraft.mounts.client;

import com.nirncraft.core.Currency;
import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.mounts.MountKind;
import com.nirncraft.mounts.MountProfile;
import com.nirncraft.mounts.MountStock;
import com.nirncraft.mounts.MountView;
import com.nirncraft.mounts.StablePackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * The stable master's screen, in the Skyrim menu style of the barter screen: <b>Horses</b> lists everything the stable
 * has this week and sets each animal beside your own (grades and numbers, arrows up and down, level, whether your
 * saddle and barding fit, the price and what yours is worth in trade); <b>Train</b> pays a stable hand to put your mount
 * through one growth point of a stat. Your own mount is shown from the server's record, wherever it is parked. What is
 * numbers and what only words follows your Riding perks (the server builds every {@link MountView}). A purchase asks
 * for a second click.
 */
public class StableScreen extends Screen {
    private static final int GOLD = SkyUi.GOLD;

    private StablePackets.State state;
    private int tab;
    private int selected;
    private int scroll;
    private String pending = "";
    private long pendingAt;
    private Component message = Component.empty();
    private long messageAt;

    private record Hit(int x1, int y1, int x2, int y2, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();

    public StableScreen(StablePackets.State state) {
        super(state.stableName());
        this.state = state;
        if (!state.message().getString().isEmpty()) {
            message = state.message();
            messageAt = Util.getMillis();
        }
    }

    public int npcId() {
        return state.npcId();
    }

    public void update(StablePackets.State s) {
        this.state = s;
        pending = "";
        if (!s.message().getString().isEmpty()) {
            message = s.message();
            messageAt = Util.getMillis();
        }
        if (selected >= s.offers().size()) selected = Math.max(0, s.offers().size() - 1);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        hits.clear();
        g.fillGradient(0, 0, width, height, 0xC8080808, 0xE0080808);
        int pw = Math.min(width - 16, 440);
        int x0 = (width - pw) / 2;

        g.pose().pushMatrix();
        g.pose().translate(width / 2f, 6);
        g.pose().scale(1.2f, 1.2f);
        g.centeredText(font, state.stableName(), 0, 0, 0xFFF5EBC8);
        g.pose().popMatrix();
        g.centeredText(font, Component.translatable("barter.nirncraft.your_gold", Currency.formatGold(state.gold())), width / 2, 20, GOLD);

        // tabs
        int tw = 70;
        tabButton(g, Component.translatable("mounts.nirncraft.stable.tab.horses"), width / 2 - tw - 4, 32, tw, 0, mx, my);
        tabButton(g, Component.translatable("mounts.nirncraft.stable.tab.train"), width / 2 + 4, 32, tw, 1, mx, my);
        g.fill(width / 2 - 160, 48, width / 2 + 160, 49, 0x60C8BC9A);

        int top = 54, bottom = height - 30;
        if (tab == 0) browse(g, x0, top, pw, bottom, mx, my);
        else train(g, x0, top, pw, bottom, mx, my);

        long age = Util.getMillis() - messageAt;
        if (!message.getString().isEmpty() && age < 6000) {
            g.centeredText(font, message, width / 2, height - 24, 0xFFF0E6C8);
        }
        g.centeredText(font, Component.translatable("mounts.nirncraft.stable.hint"), width / 2, height - 12, 0xFF8A8478);
        super.extractRenderState(g, mx, my, partial);
    }

    private void tabButton(GuiGraphicsExtractor g, Component label, int x, int y, int w, int index, int mx, int my) {
        boolean hov = SkyUi.inside(mx, my, x, y, x + w, y + 14);
        g.fill(x, y, x + w, y + 14, tab == index ? 0x50FFFFFF : hov ? 0x28FFFFFF : 0x18000000);
        if (tab == index) g.fill(x, y + 13, x + w, y + 14, SkyUi.LINE);
        g.centeredText(font, label, x + w / 2, y + 3, tab == index ? 0xFFFFFFFF : hov ? SkyUi.TEXT : 0xFFA8A090);
        hits.add(new Hit(x, y, x + w, y + 14, () -> {
            tab = index;
            pending = "";
        }));
    }

    // ------------------------------------------------------------------ horses

    private void browse(GuiGraphicsExtractor g, int x0, int top, int pw, int bottom, int mx, int my) {
        int lw = 156;
        // the stock
        g.fill(x0, top, x0 + lw, bottom, 0x70000000);
        SkyUi.caps(g, font, Component.translatable("mounts.nirncraft.stable.for_sale"), x0 + 6, top + 4, SkyUi.HEADER);
        List<StablePackets.Offer> offers = state.offers();
        int rowH = 22;
        int rows = Math.max(1, (bottom - top - 18) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, offers.size() - rows)));
        if (offers.isEmpty()) {
            g.text(font, Component.translatable("mounts.nirncraft.stable.nothing"), x0 + 6, top + 22, SkyUi.DIM, false);
        }
        for (int i = 0; i < rows && scroll + i < offers.size(); i++) {
            int idx = scroll + i;
            StablePackets.Offer o = offers.get(idx);
            int y = top + 16 + i * rowH;
            boolean hov = SkyUi.inside(mx, my, x0, y, x0 + lw, y + rowH);
            if (idx == selected) g.fill(x0, y, x0 + lw, y + rowH, 0x40FFFFFF);
            else if (hov) g.fill(x0, y, x0 + lw, y + rowH, 0x20FFFFFF);
            MountView v = o.view();
            String price = Currency.formatCompact(o.price()) + "g";
            g.text(font, SkyUi.ellipsize(font, MountViewText.name(v).getString(), lw - 12 - font.width(price)), x0 + 6, y + 3,
                    idx == selected ? SkyUi.BRIGHT : SkyUi.TEXT, false);
            g.text(font, price, x0 + lw - 6 - font.width(price), y + 3, state.gold() >= o.price() ? GOLD : SkyUi.BAD, false);
            g.text(font, SkyUi.ellipsize(font, summary(v), lw - 12), x0 + 6, y + 12, SkyUi.DIM, false);
            int fi = idx;
            hits.add(new Hit(x0, y, x0 + lw, y + rowH, () -> {
                selected = fi;
                pending = "";
            }));
        }
        if (offers.size() > rows) {
            int trackH = bottom - top - 18;
            int barH = Math.max(8, trackH * rows / offers.size());
            int barY = top + 16 + (trackH - barH) * scroll / Math.max(1, offers.size() - rows);
            g.fill(x0 + lw - 2, top + 16, x0 + lw, bottom, 0x40FFFFFF);
            g.fill(x0 + lw - 2, barY, x0 + lw, barY + barH, 0xC0C8BC9A);
        }

        // the comparison
        int rx = x0 + lw + 8, rw = pw - lw - 8;
        g.fill(rx, top, rx + rw, bottom, 0x70000000);
        MountView own = state.own();
        StablePackets.Offer cand = offers.isEmpty() ? null : offers.get(Math.min(selected, offers.size() - 1));
        int cw = (rw - 12 - 58) / 2;
        int cLabel = rx + 6, cOwn = rx + 64, cNew = cOwn + cw;
        int y = top + 4;
        SkyUi.caps(g, font, Component.translatable("mounts.nirncraft.stable.yours"), cOwn, y, SkyUi.HEADER);
        SkyUi.caps(g, font, Component.translatable("mounts.nirncraft.stable.candidate"), cNew, y, SkyUi.HEADER);
        y += 11;
        g.text(font, SkyUi.ellipsize(font, own == null ? Component.translatable("mounts.nirncraft.stable.none").getString()
                : MountViewText.name(own).getString(), cw - 4), cOwn, y, SkyUi.GOLD, false);
        if (cand != null) {
            g.text(font, SkyUi.ellipsize(font, MountViewText.name(cand.view()).getString(), cw - 14), cNew, y, SkyUi.GOLD, false);
        }
        y += 12;
        SkyUi.hline(g, rx + 4, rx + rw - 4, y - 2, 0x80);
        MountKind kind = cand == null ? null : cand.view().kind();
        for (int stat = 0; stat <= MountProfile.TEMPER; stat++) {
            g.text(font, MountViewText.label(kind != null ? kind : own != null ? own.kind() : null, stat), cLabel, y, SkyUi.DIM, false);
            if (own != null) cell(g, own, stat, cOwn, y, cw - 4);
            if (cand != null) {
                cell(g, cand.view(), stat, cNew, y, cw - 14);
                if (own != null) arrow(g, cNew + cw - 10, y, compare(own, cand.view(), stat));
            }
            y += 11;
        }
        // level and trust
        g.text(font, Component.translatable("mounts.nirncraft.stable.level"), cLabel, y, SkyUi.DIM, false);
        if (own != null) g.text(font, level(own), cOwn, y, SkyUi.TEXT, false);
        if (cand != null) {
            g.text(font, level(cand.view()), cNew, y, SkyUi.TEXT, false);
            if (own != null && own.detail() >= 1 && cand.view().detail() >= 1) arrow(g, cNew + cw - 10, y, Integer.compare(cand.view().level(), own.level()));
        }
        y += 11;
        g.text(font, MountViewText.trustLabel(), cLabel, y, SkyUi.DIM, false);
        if (own != null) g.text(font, own.trust() >= 0 ? Math.round(own.trust()) + "" : "?", cOwn, y, SkyUi.TEXT, false);
        if (cand != null) {
            g.text(font, cand.view().trust() >= 0 ? Math.round(cand.view().trust()) + "" : "?", cNew, y, SkyUi.TEXT, false);
            if (own != null && own.trust() >= 0 && cand.view().trust() >= 0) arrow(g, cNew + cw - 10, y, Float.compare(cand.view().trust(), own.trust()));
        }
        y += 14;

        // tack and money
        if (cand != null && own != null) {
            if (!own.saddle().isEmpty()) {
                g.text(font, Component.translatable(cand.saddleFits() ? "mounts.nirncraft.stable.saddle_fits" : "mounts.nirncraft.stable.saddle_no",
                        Component.translatable("mounts.nirncraft.saddle." + own.saddle())), cLabel, y,
                        cand.saddleFits() ? 0xFF7FD27F : SkyUi.STOLEN, false);
                y += 10;
            }
            if (own.armor() > 0) {
                g.text(font, Component.translatable(cand.bardingFits() ? "mounts.nirncraft.stable.barding_fits" : "mounts.nirncraft.stable.barding_no"),
                        cLabel, y, cand.bardingFits() ? 0xFF7FD27F : SkyUi.STOLEN, false);
                y += 10;
            }
        }
        y = Math.max(y + 2, bottom - 44);
        int net = cand == null ? 0 : cand.price() - state.tradeIn();
        if (cand != null) {
            g.text(font, Component.translatable("mounts.nirncraft.stable.price", Currency.formatCompact(cand.price())), cLabel, y, SkyUi.TEXT, false);
            if (state.tradeIn() > 0) {
                g.text(font, Component.translatable("mounts.nirncraft.stable.trade_in", Currency.formatCompact(state.tradeIn())), cOwn + 20, y, SkyUi.DIM, false);
            }
            Component total = Component.translatable(net >= 0 ? "mounts.nirncraft.stable.you_pay" : "mounts.nirncraft.stable.you_get", Currency.formatCompact(Math.abs(net)));
            g.text(font, total, rx + rw - 6 - font.width(total), y, net <= state.gold() ? GOLD : SkyUi.BAD, false);
        }
        int by = bottom - 20;
        if (cand != null) {
            String key = "buy:" + cand.id();
            boolean confirm = pending.equals(key) && Util.getMillis() - pendingAt < 5000;
            boolean can = net <= state.gold();
            Component label = confirm ? Component.translatable("mounts.nirncraft.stable.confirm", Currency.formatCompact(Math.abs(net)))
                    : Component.translatable("mounts.nirncraft.stable.buy");
            int bw = 120;
            int bx = rx + rw - bw - 6;
            SkyUi.button(g, font, label, bx, by, bw, 14, SkyUi.inside(mx, my, bx, by, bx + bw, by + 14), can, 1f);
            final int fnet = net;
            final String id = cand.id();
            if (can) hits.add(new Hit(bx, by, bx + bw, by + 14, () -> {
                if (pending.equals(key) && Util.getMillis() - pendingAt < 5000) {
                    SkyNetwork.sendToServer(new StablePackets.Action(state.npcId(), StablePackets.BUY, id, fnet));
                    pending = "";
                } else {
                    pending = key;
                    pendingAt = Util.getMillis();
                }
            }));
        }
        if (state.canSell() && own != null) {
            String key = "sell";
            boolean confirm = pending.equals(key) && Util.getMillis() - pendingAt < 5000;
            Component label = confirm ? Component.translatable("mounts.nirncraft.stable.confirm_sell", Currency.formatCompact(state.tradeIn()))
                    : Component.translatable("mounts.nirncraft.stable.sell", Currency.formatCompact(state.tradeIn()));
            int bw = 130;
            int bx = rx + 6;
            SkyUi.button(g, font, label, bx, by, bw, 14, SkyUi.inside(mx, my, bx, by, bx + bw, by + 14), state.tradeIn() > 0, 1f);
            if (state.tradeIn() > 0) hits.add(new Hit(bx, by, bx + bw, by + 14, () -> {
                if (pending.equals(key) && Util.getMillis() - pendingAt < 5000) {
                    SkyNetwork.sendToServer(new StablePackets.Action(state.npcId(), StablePackets.SELL, "", state.tradeIn()));
                    pending = "";
                } else {
                    pending = key;
                    pendingAt = Util.getMillis();
                }
            }));
        }
    }

    /** "Lv 3  swift & sturdy" or the grades of three stats. */
    private static String summary(MountView v) {
        if (v.detail() == 0) return MountViewText.levelWord(v.level()).getString() + ", " + MountViewText.vague(v).getString();
        StringBuilder sb = new StringBuilder("Lv ").append(v.level());
        for (int s : new int[]{MountProfile.SPEED, MountProfile.STAMINA, MountProfile.HEALTH}) {
            sb.append("  ").append(MountViewText.grade(v, s).getString());
        }
        return sb.toString();
    }

    private static Component level(MountView v) {
        return v.detail() >= 1 ? Component.literal(v.level() + " / 20") : MountViewText.levelWord(v.level());
    }

    /** One stat of a view: its grade (or a vague word) and, with Horse Sense, the number. */
    private void cell(GuiGraphicsExtractor g, MountView v, int stat, int x, int y, int w) {
        if (v.detail() == 0) {
            Component word = MountViewText.word(stat, v.gradeLo()[stat]);
            g.text(font, word.getString().isEmpty() ? Component.literal("-") : word, x, y, SkyUi.TEXT, false);
            return;
        }
        Component grade = MountViewText.grade(v, stat);
        g.text(font, grade, x, y, MountViewText.color(v, stat), false);
        if (v.numbers() && stat != MountProfile.TEMPER && v.value()[stat] >= 0) {
            String n = MountViewText.number(stat, v.value()[stat]);
            int nx = x + font.width(grade) + 5;
            if (nx + font.width(n) <= x + w + 10) g.text(font, n, nx, y, SkyUi.DIM, false);
        }
    }

    /** +1 when {@code c} is better than {@code own} in the stat, -1 when worse, 0 when no better or not known. */
    private static int compare(MountView own, MountView c, int stat) {
        if (own.detail() == 0 || c.detail() == 0) return 0;
        if (stat != MountProfile.TEMPER && own.numbers() && c.numbers() && own.value()[stat] >= 0 && c.value()[stat] >= 0) {
            float d = c.value()[stat] - own.value()[stat];
            return Math.abs(d) < 0.05f ? 0 : d > 0 ? 1 : -1;
        }
        return Integer.compare(c.gradeLo()[stat] + c.gradeHi()[stat], own.gradeLo()[stat] + own.gradeHi()[stat]);
    }

    private static void arrow(GuiGraphicsExtractor g, int cx, int y, int cmp) {
        if (cmp == 0) return;
        SkyUi.triangle(g, cx, y + 2, cmp > 0, cmp > 0 ? 0xFF7FD27F : 0xFFD06050);
    }

    // ------------------------------------------------------------------ training

    private void train(GuiGraphicsExtractor g, int x0, int top, int pw, int bottom, int mx, int my) {
        g.fill(x0, top, x0 + pw, bottom, 0x70000000);
        MountView own = state.own();
        if (own == null || state.train().isEmpty()) {
            g.text(font, Component.translatable("mounts.nirncraft.stable.train.none"), x0 + 8, top + 10, SkyUi.DIM, false);
            return;
        }
        MountKind kind = own.kind();
        g.text(font, MountViewText.name(own), x0 + 8, top + 6, SkyUi.GOLD, false);
        Component left = Component.translatable("mounts.nirncraft.stable.train.left", state.sessionsLeft(), MountStock.TRAINING_PER_WEEK);
        g.text(font, left, x0 + pw - 8 - font.width(left), top + 6, SkyUi.TEXT, false);
        int y = top + 22;
        int cGrade = x0 + 90, cBar = x0 + 150, cFee = x0 + pw - 170;
        SkyUi.caps(g, font, Component.translatable("mounts.nirncraft.sheet.stat"), x0 + 8, y, SkyUi.HEADER);
        SkyUi.caps(g, font, Component.translatable("mounts.nirncraft.sheet.grade"), cGrade, y, SkyUi.HEADER);
        SkyUi.caps(g, font, Component.translatable("mounts.nirncraft.sheet.growth"), cBar, y, SkyUi.HEADER);
        y += 12;
        for (StablePackets.TrainRow row : state.train()) {
            int stat = row.stat();
            boolean trust = stat == MountProfile.TEMPER;
            g.text(font, trust ? MountViewText.trustLabel() : MountViewText.label(kind, stat), x0 + 8, y + 3, SkyUi.TEXT, false);
            if (!trust) {
                if (own.detail() == 0) g.text(font, MountViewText.word(stat, own.gradeLo()[stat]).getString().isEmpty() ? Component.literal("-")
                        : MountViewText.word(stat, own.gradeLo()[stat]), cGrade, y + 3, SkyUi.TEXT, false);
                else g.text(font, MountViewText.grade(own, stat), cGrade, y + 3, MountViewText.color(own, stat), false);
            }
            int pips = trust ? 10 : row.cap();
            int filled = trust ? Math.round(row.points() / 10f) : row.points();
            for (int i = 0; i < pips; i++) g.fill(cBar + i * 5, y + 3, cBar + i * 5 + 4, y + 9, i < filled ? 0xFFC8A040 : 0xFF3A362E);
            String stateWord = switch (row.state()) {
                case 1 -> Component.translatable("mounts.nirncraft.stable.train.cap").getString();
                case 2 -> Component.translatable("mounts.nirncraft.stable.train.week_limit").getString();
                case 3 -> Component.translatable("mounts.nirncraft.stable.train.maxed").getString();
                default -> "";
            };
            boolean ok = row.state() == 0 && state.gold() >= row.fee();
            int bx = x0 + pw - 100, bw = 92;
            String key = "train:" + stat;
            boolean confirm = pending.equals(key) && Util.getMillis() - pendingAt < 5000;
            if (row.state() == 0) {
                Component label = confirm ? Component.translatable("mounts.nirncraft.stable.confirm", Currency.formatCompact(row.fee()))
                        : Component.translatable("mounts.nirncraft.stable.train.pay", Currency.formatCompact(row.fee()));
                SkyUi.button(g, font, label, bx, y, bw, 12, SkyUi.inside(mx, my, bx, y, bx + bw, y + 12), ok, 1f);
                if (ok) {
                    final int fee = row.fee();
                    hits.add(new Hit(bx, y, bx + bw, y + 12, () -> {
                        if (pending.equals(key) && Util.getMillis() - pendingAt < 5000) {
                            SkyNetwork.sendToServer(new StablePackets.Action(state.npcId(), StablePackets.TRAIN, Integer.toString(stat), fee));
                            pending = "";
                        } else {
                            pending = key;
                            pendingAt = Util.getMillis();
                        }
                    }));
                }
            } else {
                g.text(font, stateWord, bx + bw - font.width(stateWord), y + 3, SkyUi.DIM, false);
            }
            y += 16;
        }
        g.text(font, Component.translatable("mounts.nirncraft.stable.train.note"), x0 + 8, bottom - 12, SkyUi.DIM, false);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) {
            for (Hit h : hits) {
                if (SkyUi.inside(mx, my, h.x1, h.y1, h.x2, h.y2)) {
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (tab == 0) scroll = Math.max(0, scroll + (delta > 0 ? -1 : 1));
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_TAB) {
            tab = 1 - tab;
            pending = "";
            return true;
        }
        if (tab == 0 && (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) && selected > 0) {
            selected--;
            pending = "";
            return true;
        }
        if (tab == 0 && (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) && selected < state.offers().size() - 1) {
            selected++;
            pending = "";
            return true;
        }
        return super.keyPressed(event);
    }
}
