package com.nirncraft.quest.client;

import com.nirncraft.client.SkyKeys;
import com.nirncraft.core.Holds;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.Faction;
import com.nirncraft.quest.Factions;
import com.nirncraft.quest.MainQuest;
import com.nirncraft.quest.Objective;
import com.nirncraft.quest.Quest;
import com.nirncraft.quest.QuestPackets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The Skyrim journal: a parchment page with tabs for Quests (active / completed, objectives, rewards, compass
 * tracking), Factions (membership, rank, reputation), Stats, Party and Collections (the loot module's
 * {@link com.nirncraft.loot.client.CollectionsPage}).
 */
public class JournalScreen extends Screen {
    private static final int MAIN = 0, SIDE = 1, MISC = 2, FACTIONS = 3, REPUTATION = 4, STATS = 5, PARTY = 6, COLLECTIONS = 7;
    private static final String[] TABS = {"main", "side", "misc", "factions", "reputation", "stats", "party", "collections"};
    private static final int ROW = 12;

    private static int tab = MAIN;
    private static String selectedQuest = "";
    private static int selectedFaction = 0;

    private int x0, y0, x1, y1, listX1;
    private int tabPadding = 16, tabScroll;
    private int listScroll, detailScroll, listHeight, detailHeight;
    private boolean abandonArmed;
    private final List<Row> rows = new ArrayList<>();
    private final List<ObjRow> objRows = new ArrayList<>();
    private Button trackButton, shareButton, abandonButton, partyButton, skipButton, mapButton;
    private Button[] questActions;
    private int questActionsTop;

    private record Row(@Nullable Quest quest, int y) {
    }

    private record ObjRow(net.minecraft.core.BlockPos pos, int y0, int y1) {
    }

    public JournalScreen() {
        super(Component.translatable("screen.nirncraft.journal"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int w = Math.min(width - 24, 470);
        int h = Math.min(height - 24, 290);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        x1 = x0 + w;
        y1 = y0 + h;
        listX1 = x0 + w * 36 / 100;
        int labels = 0;
        for (String name : TABS) labels += font.width(Component.translatable("journal.nirncraft.tab." + name));
        tabPadding = Mth.clamp((w - 16 - labels - 4 * (TABS.length - 1)) / TABS.length, 4, 16);
        keepTabVisible();
        mapButton = addRenderableWidget(Button.builder(Component.translatable("journal.nirncraft.show_on_map"),
                b -> openMapAt(targetPosOf(selected(), null))).bounds(listX1 + 10, y1 - 22, 76, 16).build());
        shareButton = addRenderableWidget(Button.builder(Component.translatable("journal.nirncraft.share"), b -> shareWithParty())
                .bounds(x1 - 244, y1 - 22, 74, 16).build());
        trackButton = addRenderableWidget(Button.builder(Component.translatable("journal.nirncraft.track"), b -> toggleTrack())
                .bounds(x1 - 166, y1 - 22, 76, 16).build());
        abandonButton = addRenderableWidget(Button.builder(Component.translatable("journal.nirncraft.abandon"), b -> abandon())
                .bounds(x1 - 86, y1 - 22, 78, 16).build());
        partyButton = addRenderableWidget(Button.builder(Component.translatable("journal.nirncraft.open_party"),
                b -> minecraft.gui.setScreen(new PartyScreen())).bounds((x0 + x1) / 2 - 60, y1 - 26, 120, 18).build());
        skipButton = addRenderableWidget(Button.builder(Component.translatable("journal.nirncraft.skip"), b -> skipStep())
                .bounds(x1 - 180, y1 - 40, 172, 16).build());
        skipButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("journal.nirncraft.skip_hint")));
        questActions = new Button[]{mapButton, shareButton, trackButton, abandonButton, skipButton};
        tick();
    }

    /** The current step has been stuck for the configured number of days (the server checks it again). */
    private boolean canSkip(@Nullable Quest q) {
        if (q == null || !q.isActive() || minecraft == null || minecraft.level == null) return false;
        Objective o = q.current();
        if (o == null) return false;
        long at = o.data.getLongOr("skip_at", 0L);
        return at > 0 && minecraft.level.getGameTime() >= at;
    }

    private void skipStep() {
        Quest q = selected();
        if (!canSkip(q)) return;
        SkyNetwork.sendToServer(new QuestPackets.QuestAction(QuestPackets.QuestAction.SKIP, q.id));
    }

    // ------------------------------------------------------------------ state

    private List<Quest> activeQuests() {
        List<Quest> out = new ArrayList<>();
        for (Quest q : ClientQuestData.quests()) {
            if (!q.isActive()) continue;
            if (tab == MAIN && q.category == Quest.Category.MAIN) out.add(q);
            else if (tab == SIDE && q.category == Quest.Category.SIDE) out.add(q);
            else if (tab == MISC && q.category != Quest.Category.MAIN && q.category != Quest.Category.SIDE) out.add(q);
        }
        out.sort(Comparator.comparingInt((Quest q) -> q.category.ordinal()).thenComparingLong(q -> -q.started));
        return out;
    }

    private List<Quest> finishedQuests() {
        List<Quest> out = new ArrayList<>();
        for (Quest q : ClientQuestData.quests()) {
            if (q.isActive()) continue;
            if (tab == MAIN && q.category == Quest.Category.MAIN) out.add(q);
            else if (tab == SIDE && q.category == Quest.Category.SIDE) out.add(q);
            else if (tab == MISC && q.category != Quest.Category.MAIN && q.category != Quest.Category.SIDE) out.add(q);
        }
        out.sort(Comparator.comparingLong((Quest q) -> -q.finished));
        return out;
    }

    @Nullable
    private Quest selected() {
        for (Quest q : ClientQuestData.quests()) if (q.id.equals(selectedQuest)) return q;
        List<Quest> active = activeQuests();
        Quest fallback = null;
        for (Quest q : active) {
            if (ClientQuestData.isTracked(q)) return q;
            if (fallback == null) fallback = q;
        }
        return fallback;
    }

    @Override
    public void tick() {
        Quest q = selected();
        boolean isQuestTab = tab == MAIN || tab == SIDE || tab == MISC;
        boolean show = isQuestTab && q != null && q.isActive();
        trackButton.visible = show;
        abandonButton.visible = show && !q.isMain();
        shareButton.visible = show && !q.isMain() && !q.shared && ClientQuestData.inParty();
        mapButton.visible = isQuestTab && q != null && targetPosOf(q, null) != null;
        if (show) {
            boolean tracked = q.id.equals(ClientQuestData.tracked());
            trackButton.setMessage(Component.translatable(tracked ? "journal.nirncraft.untrack" : "journal.nirncraft.track"));
            abandonButton.setMessage(Component.translatable(abandonArmed ? "journal.nirncraft.abandon_confirm" : "journal.nirncraft.abandon"));
        }
        partyButton.visible = tab == PARTY;
        skipButton.visible = show && canSkip(q);
        layoutQuestActions();
    }

    /** Keep full action labels and wrap visible controls above the detail viewport when they need another row. */
    private void layoutQuestActions() {
        int left = listX1 + 10, right = x1 - 8, available = Math.max(1, right - left);
        int x = left, row = 0, visible = 0;
        for (Button action : questActions) {
            if (!action.visible) continue;
            int wanted = font.width(action.getMessage()) + 12;
            int width = Math.min(available, Math.max(40, wanted));
            if (x > left && x + width > right) { x = left; row++; }
            action.setX(x); action.setY(row * 20); action.setWidth(width);
            if (action != skipButton)
                action.setTooltip(wanted > available ? net.minecraft.client.gui.components.Tooltip.create(action.getMessage()) : null);
            x += width + 4; visible++;
        }
        questActionsTop = visible == 0 ? y1 - 8 : y1 - 8 - (row * 20 + 16);
        for (Button action : questActions) if (action.visible) action.setY(questActionsTop + action.getY());
    }

    @Nullable
    private static net.minecraft.core.BlockPos targetPosOf(@Nullable Quest q, @Nullable Objective o) {
        if (o != null && o.hasPos) return o.pos;
        if (q == null) return null;
        Objective cur = q.current();
        if (cur != null && cur.hasPos) return cur.pos;
        if (q.extra.contains("target_place")) {
            com.nirncraft.tamriel.Place p = com.nirncraft.tamriel.Places.byId(q.extra.getStringOr("target_place", "")).orElse(null);
            if (p != null) return new net.minecraft.core.BlockPos(p.x(), 72, p.z());
        }
        for (Objective obj : q.objectives) {
            if (obj.hasPos) return obj.pos;
        }
        if (q.giverPos != null && !q.giverPos.equals(net.minecraft.core.BlockPos.ZERO)) return q.giverPos;
        return null;
    }

    private void openMapAt(@Nullable net.minecraft.core.BlockPos pos) {
        if (pos == null || minecraft == null) return;
        net.minecraft.core.BlockPos prevWp = com.nirncraft.world.client.MapScreen.customWaypoint;
        com.nirncraft.world.client.MapScreen map = new com.nirncraft.world.client.MapScreen();
        minecraft.gui.setScreen(map);
        com.nirncraft.world.client.MapScreen.customWaypoint = pos;
        map.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_F,InputConstants.KEYCODE_F,0));
        com.nirncraft.world.client.MapScreen.customWaypoint = prevWp != null ? prevWp : pos;
    }

    private void toggleTrack() {
        Quest q = selected();
        if (q == null) return;
        boolean tracked = q.id.equals(ClientQuestData.tracked());
        SkyNetwork.sendToServer(new QuestPackets.QuestAction(tracked ? QuestPackets.QuestAction.UNTRACK : QuestPackets.QuestAction.TRACK, q.id));
    }

    private void shareWithParty() {
        Quest q = selected();
        if (q == null || q.isMain() || q.shared || !ClientQuestData.inParty()) return;
        SkyNetwork.sendToServer(new QuestPackets.QuestAction(QuestPackets.QuestAction.SHARE, q.id));
    }

    private void abandon() {
        Quest q = selected();
        if (q == null || q.isMain()) return;
        if (!abandonArmed) {
            abandonArmed = true;
            return;
        }
        abandonArmed = false;
        SkyNetwork.sendToServer(new QuestPackets.QuestAction(QuestPackets.QuestAction.ABANDON, q.id));
    }

    // ------------------------------------------------------------------ input

    private int tabX(int i) {
        int x = x0 + 8 - tabScroll;
        for (int j = 0; j < i; j++) x += tabWidth(j) + 4;
        return x;
    }

    private int tabWidth(int i) {
        return font.width(Component.translatable("journal.nirncraft.tab." + TABS[i])) + tabPadding;
    }

    private int maxTabScroll() {
        int total = 4 * (TABS.length - 1);
        for (int i = 0; i < TABS.length; i++) total += tabWidth(i);
        return Math.max(0, total - (x1 - x0 - 16));
    }

    private void keepTabVisible() {
        int left = tabX(tab), right = left + tabWidth(tab);
        if (left < x0 + 8) tabScroll -= x0 + 8 - left;
        else if (right > x1 - 8) tabScroll += right - (x1 - 8);
        tabScroll = Mth.clamp(tabScroll, 0, maxTabScroll());
    }

    private void setTab(int t) {
        tab = Math.floorMod(t, TABS.length);
        keepTabVisible();
        listScroll = detailScroll = 0;
        abandonArmed = false;
        tick();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        if (my >= y0 + 6 && my < y0 + 21) {
            if (mx >= x0 && mx < x0 + 8 && tabScroll > 0) { setTab(tab - 1); return true; }
            if (mx >= x1 - 8 && mx < x1 && tabScroll < maxTabScroll()) { setTab(tab + 1); return true; }
        }
        for (int i = 0; i < TABS.length; i++) {
            int tx = tabX(i);
            if (mx >= x0 + 8 && mx < x1 - 8 && mx >= tx && mx < tx + tabWidth(i) && my >= y0 + 6 && my < y0 + 21) {
                setTab(i);
                return true;
            }
        }
        int top = y0 + 30;
        if (mx >= x0 + 6 && mx < listX1 && my >= top && my < y1 - 8) {
            if (tab == MAIN || tab == SIDE || tab == MISC) {
                for (Row r : rows) {
                    if (r.quest() != null && my >= r.y() - 1 && my < r.y() + ROW - 1) {
                        selectedQuest = r.quest().id;
                        detailScroll = 0;
                        abandonArmed = false;
                        tick();
                        return true;
                    }
                }
            } else if (tab == COLLECTIONS) {
                if (com.nirncraft.loot.client.CollectionsPage.click(mx, my, x0 + 6, top, listX1, listScroll)) {
                    detailScroll = 0;
                    return true;
                }
            } else if (tab == FACTIONS) {
                int idx = (int) ((my - top + listScroll) / 22);
                if (idx >= 0 && idx < Faction.VALUES.length) {
                    selectedFaction = idx;
                    detailScroll = 0;
                    return true;
                }
            }
        } else if ((tab == MAIN || tab == SIDE || tab == MISC) && mx >= listX1 + 6 && mx < x1 - 4 && my >= top && my < questActionsTop - 4) {
            for (ObjRow r : objRows) {
                if (my >= r.y0() && my < r.y1()) {
                    openMapAt(r.pos());
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (mx >= x0 && mx < x1 && my >= y0 + 6 && my < y0 + 21 && maxTabScroll() > 0) {
            tabScroll = Mth.clamp(tabScroll - (int) (delta * 24), 0, maxTabScroll());
            return true;
        }
        int top = y0 + 30;
        int view = y1 - 8 - top;
        if (mx < listX1 && tab != STATS && tab != REPUTATION) {
            listScroll = Mth.clamp(listScroll - (int) (delta * 14), 0, Math.max(0, listHeight - view));
        } else {
            int detailView = tab == MAIN || tab == SIDE || tab == MISC ? Math.max(1, questActionsTop - 4 - top) : view - 24;
            detailScroll = Mth.clamp(detailScroll - (int) (delta * 14), 0, Math.max(0, detailHeight - detailView));
        }
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (SkyKeys.JOURNAL.matches(event)) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_Q) {
            setTab(tab - 1);
            return true;
        }
        if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_E) {
            setTab(tab + 1);
            return true;
        }
        return super.keyPressed(event);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        Parchment.page(g, x0, y0, x1, y1);
        drawTabs(g, mouseX, mouseY);
        switch (tab) {
            case MAIN, SIDE, MISC -> renderQuests(g, mouseX, mouseY);
            case FACTIONS -> renderFactions(g, mouseX, mouseY);
            case REPUTATION -> renderReputation(g);
            case STATS -> renderStats(g);
            case COLLECTIONS -> {
                int[] h = com.nirncraft.loot.client.CollectionsPage.render(g, font, x0, y0, x1, y1, listX1, listScroll, detailScroll);
                listHeight = h[0];
                detailHeight = h[1];
            }
            default -> renderParty(g);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawTabs(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.enableScissor(x0 + 8, y0 + 6, x1 - 8, y0 + 21);
        for (int i = 0; i < TABS.length; i++) {
            int tx = tabX(i);
            int tw = tabWidth(i);
            boolean sel = i == tab;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= y0 + 6 && mouseY < y0 + 21;
            if (sel) g.fill(tx, y0 + 6, tx + tw, y0 + 21, 0x50A07A3A);
            else if (hover) g.fill(tx, y0 + 6, tx + tw, y0 + 21, 0x25A07A3A);
            if (sel) g.fill(tx, y0 + 20, tx + tw, y0 + 21, Parchment.INK);
            g.text(font, Component.translatable("journal.nirncraft.tab." + TABS[i]), tx + tabPadding / 2, y0 + 9,
                    sel ? Parchment.INK : Parchment.INK_LIGHT, false);
        }
        g.disableScissor();
        if (tabScroll > 0) g.text(font, "<", x0 + 1, y0 + 9, Parchment.INK, false);
        if (tabScroll < maxTabScroll()) g.text(font, ">", x1 - 6, y0 + 9, Parchment.INK, false);
        Parchment.rule(g, x0 + 8, x1 - 8, y0 + 24);
    }

    private void heading(GuiGraphicsExtractor g, Component text, int x, int y) {
        g.text(font, text, x, y, Parchment.RED, false);
    }

    private int drawWrapped(GuiGraphicsExtractor g, Component text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(20, w))) {
            g.text(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    // ------------------------------------------------------------------ quests tab

    private void renderQuests(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = y0 + 30;
        int bottom = y1 - 8;
        Parchment.vrule(g, listX1 + 3, top, bottom);
        Quest sel = selected();

        rows.clear();
        List<Quest> active = activeQuests();
        List<Quest> finished = finishedQuests();
        int lx = x0 + 8;
        int lw = listX1 - lx - 4;
        g.enableScissor(x0 + 4, top, listX1, bottom);
        int y = top - listScroll;
        heading(g, Component.translatable("journal.nirncraft.active", active.size()), lx, y);
        y += ROW + 2;
        if (active.isEmpty()) {
            g.text(font, Component.translatable("journal.nirncraft.no_quests"), lx + 4, y, Parchment.INK_FADED, false);
            y += ROW;
        }
        for (Quest q : active) y = questRow(g, q, lx, y, lw, sel, mouseX, mouseY);
        y += 6;
        heading(g, Component.translatable("journal.nirncraft.completed", finished.size()), lx, y);
        y += ROW + 2;
        for (Quest q : finished) y = questRow(g, q, lx, y, lw, sel, mouseX, mouseY);
        g.disableScissor();
        listHeight = y + listScroll - top;

        if (sel == null) {
            drawWrapped(g, Component.translatable("journal.nirncraft.empty_hint"), listX1 + 12, top + 10, x1 - listX1 - 24, Parchment.INK_FADED);
            detailHeight = 0;
            return;
        }
        int dBottom = Math.min(bottom, questActionsTop - 4);
        g.enableScissor(listX1 + 6, top, x1 - 4, dBottom);
        detailHeight = renderQuestDetail(g, sel, listX1 + 12, top - detailScroll, x1 - listX1 - 24) - (top - detailScroll);
        g.disableScissor();
    }

    private int questRow(GuiGraphicsExtractor g, Quest q, int x, int y, int w, @Nullable Quest sel, int mouseX, int mouseY) {
        boolean selected = sel != null && sel.id.equals(q.id);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + ROW - 1 && mouseY >= y0 + 30 && mouseY < y1 - 8;
        if (selected) g.fill(x - 2, y - 2, x + w, y + ROW - 2, Parchment.HIGHLIGHT);
        else if (hover) g.fill(x - 2, y - 2, x + w, y + ROW - 2, 0x20A07A3A);
        Faction f = Faction.byId(q.faction);
        Parchment.icon(g, x, y, q.category, f == null ? 0x6E5638 : f.color);
        int color = q.isActive() ? Parchment.INK : q.status == Quest.Status.FAILED ? 0xFF9A5A4A : Parchment.INK_FADED;
        int textW = w - 14 - (q.isActive() && ClientQuestData.isTracked(q) ? 8 : 0);
        String title = font.plainSubstrByWidth(q.title.getString(), textW);
        g.text(font, title, x + 11, y, color, false);
        if (!q.isActive()) g.fill(x + 11, y + 4, x + 11 + font.width(title), y + 5, 0x806E5638);
        if (q.isActive() && ClientQuestData.isTracked(q)) {
            int ax = x + w - 8;
            for (int i = 0; i < 4; i++) g.fill(ax + i, y + 1 + i, ax + 7 - i, y + 2 + i, Parchment.GOLD);
        }
        rows.add(new Row(q, y));
        return y + ROW;
    }

    /** Draws the selected quest; returns the y after the last line. */
    private int renderQuestDetail(GuiGraphicsExtractor g, Quest q, int x, int y, int w) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.3f, 1.3f);
        g.text(font, q.title, 0, 0, Parchment.INK, false);
        g.pose().popMatrix();
        y += 16;
        Component cat = Component.translatable("journal.nirncraft.category." + q.category.name().toLowerCase(Locale.ROOT));
        Faction f = Faction.byId(q.faction);
        if (f != null) cat = Component.translatable("journal.nirncraft.faction_quest", f.displayName());
        if (q.shared) cat = cat.copy().append(Component.translatable("journal.nirncraft.party_quest"));
        g.text(font, cat, x, y, Parchment.INK_LIGHT, false);
        y += 11;
        if (!q.giverName.getString().isEmpty()) {
            Component giver = q.hold.isEmpty() ? Component.translatable("journal.nirncraft.given_by", q.giverName)
                    : Component.translatable("journal.nirncraft.given_by_in", q.giverName, Holds.displayName(q.hold));
            y = drawWrapped(g, giver, x, y, w, Parchment.INK_LIGHT);
        }
        y += 3;
        Parchment.rule(g, x, x + w, y);
        y += 6;
        y = drawWrapped(g, q.description, x, y, w, Parchment.INK);
        y += 8;
        // story quests keep a journal: what happened at each step, oldest first (the first entry is the description)
        var journal = q.extra.getListOrEmpty("journal");
        if (journal.size() > 1) {
            heading(g, Component.translatable("journal.nirncraft.journal"), x, y);
            y += 12;
            for (int i = 1; i < journal.size(); i++) {
                y = drawWrapped(g, Quest.readComponent(journal.getStringOr(i, "")), x + 4, y, w - 4, Parchment.INK_LIGHT) + 4;
            }
            y += 4;
        }

        heading(g, Component.translatable("journal.nirncraft.objectives"), x, y);
        y += 12;
        objRows.clear();
        Objective current = q.current();
        for (Objective o : q.objectives) {
            boolean shown = o.done || o == current;
            if (!shown) continue;
            int rowStart = y;
            boolean failed = q.status == Quest.Status.FAILED && !o.done;
            Parchment.checkbox(g, x, y, o.done, failed);
            Component text = o.text;
            if (o.required > 1 && !o.done) text = Component.translatable("journal.nirncraft.objective_progress", o.text, o.progress, o.required);
            int color = o.done ? Parchment.INK_FADED : Parchment.INK;
            y = drawWrapped(g, text, x + 11, y, w - 12, color) + 3;
            net.minecraft.core.BlockPos tPos = targetPosOf(q, o);
            if (tPos != null) {
                objRows.add(new ObjRow(tPos, rowStart - 1, y));
            }
        }
        y += 6;

        heading(g, Component.translatable("journal.nirncraft.rewards"), x, y);
        y += 12;
        if (q.rewardGold > 0) {
            g.text(font, Component.translatable("journal.nirncraft.reward_gold", q.rewardGold), x + 4, y, Parchment.GOLD, false);
            y += 10;
        }
        for (ItemStack s : q.rewardItems) {
            Component name = s.getCount() > 1 ? Component.literal(s.getCount() + "x ").append(s.getHoverName()) : s.getHoverName();
            g.text(font, Component.literal("• ").append(name), x + 4, y, Parchment.INK, false);
            y += 10;
        }
        if (f != null) {
            Component rep = q.factionJoin ? Component.translatable("journal.nirncraft.reward_join", f.displayName())
                    : Component.translatable("journal.nirncraft.reward_reputation", q.factionPoints, f.displayName());
            g.text(font, rep, x + 4, y, Parchment.INK_LIGHT, false);
            y += 10;
        }
        if (q.category == Quest.Category.FACTION && Faction.DARK_BROTHERHOOD.id.equals(q.faction)) {
            y = drawWrapped(g, Component.translatable("journal.nirncraft.sneak_bonus_hint"), x + 4, y, w - 4, Parchment.INK_FADED);
        }
        y += 6;
        if (q.status == Quest.Status.COMPLETED) {
            g.text(font, Component.translatable("journal.nirncraft.status_completed"), x, y, Parchment.GREEN, false);
            y += 10;
        } else if (q.status == Quest.Status.FAILED) {
            Component reason = Quest.readComponent(q.extra.getStringOr("failReason", ""));
            y = drawWrapped(g, Component.translatable("journal.nirncraft.status_failed", reason), x, y, w, Parchment.RED);
        }
        return y;
    }

    // ------------------------------------------------------------------ factions tab

    private void renderFactions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = y0 + 30;
        int bottom = y1 - 8;
        Parchment.vrule(g, listX1 + 3, top, bottom);
        PlayerData data = SkyData.get(minecraft.player);
        int lx = x0 + 8;
        g.enableScissor(x0 + 4, top, listX1, bottom);
        int y = top - listScroll;
        for (int i = 0; i < Faction.VALUES.length; i++) {
            Faction f = Faction.VALUES[i];
            int rank = Factions.rank(data, f);
            boolean sel = i == selectedFaction;
            if (sel) g.fill(lx - 2, y - 2, listX1 - 2, y + 20, Parchment.HIGHLIGHT);
            g.fill(lx, y + 1, lx + 6, y + 7, 0xFF000000 | f.color);
            g.text(font, font.plainSubstrByWidth(f.displayName().getString(), listX1 - lx - 16), lx + 10, y, Parchment.INK, false);
            Component sub = rank >= 0 ? f.rankName(rank) : Component.translatable("journal.nirncraft.not_member");
            g.text(font, sub, lx + 10, y + 10, rank >= 0 ? Parchment.INK_LIGHT : Parchment.INK_FADED, false);
            y += 22;
        }
        g.disableScissor();
        listHeight = Faction.VALUES.length * 22;

        Faction f = Faction.VALUES[Mth.clamp(selectedFaction, 0, Faction.VALUES.length - 1)];
        int x = listX1 + 12;
        int w = x1 - listX1 - 24;
        g.enableScissor(listX1 + 6, top, x1 - 4, bottom);
        y = top - detailScroll;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.3f, 1.3f);
        g.text(font, f.displayName(), 0, 0, Parchment.INK, false);
        g.pose().popMatrix();
        y += 18;
        int rank = Factions.rank(data, f);
        if (rank >= 0) {
            g.text(font, Component.translatable("journal.nirncraft.rank", f.rankName(rank), rank + 1, f.ranks), x, y, Parchment.INK, false);
            y += 12;
            int pts = Factions.points(data, f);
            if (rank < f.maxRank()) {
                int from = Faction.pointsFor(rank);
                int to = Faction.pointsFor(rank + 1);
                Parchment.bar(g, x, y + 1, Math.min(140, w), (pts - from) / (float) Math.max(1, to - from), 0xFF000000 | f.color);
                g.text(font, Component.translatable("journal.nirncraft.next_rank", f.rankName(rank + 1), pts, to),
                        x + Math.min(140, w) + 6, y - 1, Parchment.INK_LIGHT, false);
            } else {
                g.text(font, Component.translatable("journal.nirncraft.max_rank"), x, y, Parchment.GOLD, false);
            }
            y += 12;
            if (f == Faction.COMPANIONS && data.module("quest").getBooleanOr("beast_blood", false)) {
                y = drawWrapped(g, Component.translatable("journal.nirncraft.beast_blood"), x, y, w, Parchment.RED);
            }
        } else {
            Faction rival = f.rival();
            if (rival != null && Factions.isMember(data, rival)) {
                y = drawWrapped(g, Component.translatable("journal.nirncraft.enemy_of", rival.displayName()), x, y, w, Parchment.RED);
            } else {
                g.text(font, Component.translatable("journal.nirncraft.not_member"), x, y, Parchment.INK_FADED, false);
                y += 12;
            }
        }
        y += 4;
        Parchment.rule(g, x, x + w, y);
        y += 7;
        y = drawWrapped(g, f.description(), x, y, w, Parchment.INK);
        y += 6;
        if (rank < 0) {
            heading(g, Component.translatable("journal.nirncraft.how_to_join"), x, y);
            y += 12;
            y = drawWrapped(g, f.howToJoin(), x, y, w, Parchment.INK_LIGHT);
        }
        y += 6;
        heading(g, Component.translatable("journal.nirncraft.benefits"), x, y);
        y += 12;
        y = drawWrapped(g, f.benefits(), x, y, w, Parchment.INK_LIGHT);
        y += 6;
        heading(g, Component.translatable("journal.nirncraft.halls"), x, y);
        y += 12;
        y = drawWrapped(g, f.halls(), x, y, w, Parchment.INK_LIGHT);
        y += 6;
        heading(g, Component.translatable("journal.nirncraft.ranks"), x, y);
        y += 12;
        for (int r = 0; r < f.ranks; r++) {
            int color = r == rank ? Parchment.INK : r < rank ? Parchment.INK_LIGHT : Parchment.INK_FADED;
            g.text(font, Component.literal((r + 1) + ". ").append(f.rankName(r)), x + 4, y, color, false);
            y += 10;
        }
        g.disableScissor();
        detailHeight = y + detailScroll - top;
    }

    // ------------------------------------------------------------------ stats tab

    private void renderStats(GuiGraphicsExtractor g) {
        PlayerData data = SkyData.get(minecraft.player);
        Map<String, String> general = new java.util.LinkedHashMap<>();
        general.put("journal.nirncraft.stat_level", String.valueOf(data.getLevel()));
        general.put("journal.nirncraft.stat_race", data.getRace() == null ? "-" : data.getRace().displayName().getString());
        String title = data.module("quest").getStringOr("title", "");
        general.put("journal.nirncraft.stat_title", title.isEmpty() ? "-" : I18n.get("journal.nirncraft.title." + title));
        general.put("journal.nirncraft.stat_gold", String.valueOf(data.getGold()));
        int stage = MainQuest.stage(data);
        general.put("journal.nirncraft.stat_main", stage > MainQuest.FINAL_STAGE ? I18n.get("journal.nirncraft.main_done")
                : stage <= 0 ? "-" : stage + "/" + MainQuest.FINAL_STAGE);
        int completed = 0;
        for (Quest q : ClientQuestData.quests()) if (q.status == Quest.Status.COMPLETED) completed++;
        general.put("journal.nirncraft.stat_quests_journal", String.valueOf(completed));

        CompoundTag crime = data.module("crime");
        int bounty = 0;
        CompoundTag b = crime.getCompoundOrEmpty("bounty");
        for (String k : b.keySet()) bounty += b.getIntOr(k, 0);
        general.put("journal.nirncraft.stat_murders", String.valueOf(crime.getIntOr("murders", 0)));
        general.put("journal.nirncraft.stat_stolen", String.valueOf(crime.getIntOr("items_stolen", 0)));
        general.put("journal.nirncraft.stat_pickpockets", String.valueOf(crime.getIntOr("pickpockets", 0)));
        general.put("journal.nirncraft.stat_assaults", String.valueOf(crime.getIntOr("assaults", 0)));
        general.put("journal.nirncraft.stat_bounty", String.valueOf(bounty));

        CompoundTag magic = data.module("magic");
        int words = 0;
        CompoundTag w = magic.getCompoundOrEmpty("words");
        for (String k : w.keySet()) words += w.getIntOr(k, 0);
        general.put("journal.nirncraft.stat_spells", String.valueOf(magic.getListOrEmpty("spells").size()));
        general.put("journal.nirncraft.stat_words", String.valueOf(words));
        general.put("journal.nirncraft.stat_souls", String.valueOf(magic.getIntOr("dragon_souls", 0)));

        Map<String, Integer> tracked = new TreeMap<>(data.stats());

        int top = y0 + 30;
        int bottom = y1 - 8;
        int colW = (x1 - x0 - 32) / 2;
        int lx = x0 + 12;
        int rx = lx + colW + 8;
        Parchment.vrule(g, rx - 5, top, bottom);
        g.enableScissor(x0 + 4, top, x1 - 4, bottom);
        int y = top - detailScroll;
        heading(g, Component.translatable("journal.nirncraft.character"), lx, y);
        int ly = y + 13;
        for (Map.Entry<String, String> e : general.entrySet()) {
            statLine(g, Component.translatable(e.getKey()), e.getValue(), lx, ly, colW - 6);
            ly += 11;
        }
        heading(g, Component.translatable("journal.nirncraft.statistics"), rx, y);
        int ry = y + 13;
        if (tracked.isEmpty()) {
            g.text(font, Component.translatable("journal.nirncraft.no_stats"), rx, ry, Parchment.INK_FADED, false);
            ry += 11;
        }
        for (Map.Entry<String, Integer> e : tracked.entrySet()) {
            String key = "journal.nirncraft.stat." + e.getKey();
            Component label = net.minecraft.locale.Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(prettify(e.getKey()));
            statLine(g, label, String.valueOf(e.getValue()), rx, ry, colW - 6);
            ry += 11;
        }
        g.disableScissor();
        detailHeight = Math.max(ly, ry) + detailScroll - top;
    }

    private void statLine(GuiGraphicsExtractor g, Component label, String value, int x, int y, int w) {
        String l = font.plainSubstrByWidth(label.getString(), w - font.width(value) - 8);
        g.text(font, l, x, y, Parchment.INK_LIGHT, false);
        g.text(font, value, x + w - font.width(value), y, Parchment.INK, false);
        int dotsFrom = x + font.width(l) + 3;
        int dotsTo = x + w - font.width(value) - 3;
        for (int dx = dotsFrom; dx < dotsTo; dx += 3) g.fill(dx, y + 7, dx + 1, y + 8, 0x606E5638);
    }

    private static String prettify(String key) {
        String s = key.replace('_', ' ').replace('.', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ reputation tab

    private void renderReputation(GuiGraphicsExtractor g) {
        PlayerData data = SkyData.get(minecraft.player);
        CompoundTag crime = data.module("crime");
        CompoundTag bountyTag = crime.getCompoundOrEmpty("bounty");

        int top = y0 + 30;
        int bottom = y1 - 8;
        int colW = (x1 - x0 - 32) / 2;
        int lx = x0 + 12;
        int rx = lx + colW + 8;
        Parchment.vrule(g, rx - 5, top, bottom);
        g.enableScissor(x0 + 4, top, x1 - 4, bottom);
        int y = top - detailScroll;

        heading(g, Component.translatable("journal.nirncraft.reputation_holds"), lx, y);
        int ly = y + 14;
        // Skyrim's nine holds always, then every other region where the player has a bounty
        List<String> holds = new ArrayList<>(List.of(Holds.IDS));
        for (String k : bountyTag.keySet()) {
            String id = Holds.migrate(k);
            if (!holds.contains(id) && bountyTag.getIntOr(k, 0) > 0) holds.add(id);
        }
        for (String hold : holds) {
            Component hName = Holds.displayName(hold);
            int bounty = bountyTag.getIntOr(hold, 0);
            g.text(font, hName, lx + 4, ly, Parchment.INK, false);
            if (bounty > 0) {
                g.text(font, Component.translatable("journal.nirncraft.bounty_val", bounty), lx + colW - 70, ly, Parchment.RED, false);
            } else {
                g.text(font, Component.translatable("journal.nirncraft.law_abiding"), lx + colW - 70, ly, Parchment.GREEN, false);
            }
            ly += 14;
        }

        heading(g, Component.translatable("journal.nirncraft.reputation_standing"), rx, y);
        int ry = y + 14;
        int completed = 0;
        for (Quest q : ClientQuestData.quests()) if (q.status == Quest.Status.COMPLETED) completed++;
        statLine(g, Component.translatable("journal.nirncraft.standing_title"),
                completed >= 10 ? I18n.get("journal.nirncraft.title_hero") : I18n.get("journal.nirncraft.title_traveler"), rx, ry, colW - 6);
        ry += 13;
        statLine(g, Component.translatable("journal.nirncraft.standing_quests"), String.valueOf(completed), rx, ry, colW - 6);
        ry += 13;
        statLine(g, Component.translatable("journal.nirncraft.total_bounty"), String.valueOf(totalBounty(data)), rx, ry, colW - 6);
        ry += 13;
        statLine(g, Component.translatable("journal.nirncraft.stolen_goods"), String.valueOf(crime.getIntOr("items_stolen", 0)), rx, ry, colW - 6);
        ry += 13;

        g.disableScissor();
        detailHeight = Math.max(ly, ry) + detailScroll - top;
    }

    private int totalBounty(PlayerData data) {
        int total = 0;
        CompoundTag b = data.module("crime").getCompoundOrEmpty("bounty");
        for (String k : b.keySet()) total += b.getIntOr(k, 0);
        return total;
    }

    // ------------------------------------------------------------------ party tab

    private void renderParty(GuiGraphicsExtractor g) {
        int top = y0 + 32;
        int x = x0 + 16;
        int w = x1 - x0 - 32;
        if (!ClientQuestData.inParty()) {
            drawWrapped(g, Component.translatable("journal.nirncraft.party_none"), x, top, w, Parchment.INK_LIGHT);
        } else {
            heading(g, Component.translatable("journal.nirncraft.party_members", ClientQuestData.members().size(), ClientQuestData.maxSize()), x, top);
            int y = top + 14;
            UUID leader = ClientQuestData.leader();
            for (ClientQuestData.Member m : ClientQuestData.members()) {
                String name = (m.id().equals(leader) ? "★ " : "") + m.name();
                g.text(font, name, x, y, m.online() ? Parchment.INK : Parchment.INK_FADED, false);
                if (m.online()) {
                    float fill = m.maxHealth() > 0 ? m.health() / m.maxHealth() : 0;
                    Parchment.bar(g, x + 120, y + 2, 100, fill, 0xFFB0262A);
                    g.text(font, (int) (m.health() * 5) + " / " + (int) (m.maxHealth() * 5), x + 228, y, Parchment.INK_LIGHT, false);
                } else {
                    g.text(font, Component.translatable("party.nirncraft.offline"), x + 120, y, Parchment.INK_FADED, false);
                }
                y += 14;
            }
            y += 6;
            drawWrapped(g, Component.translatable("journal.nirncraft.party_hint"), x, y, w, Parchment.INK_FADED);
        }
        List<ClientQuestData.Invite> invites = ClientQuestData.invites();
        if (!invites.isEmpty()) {
            int y = y1 - 60;
            for (ClientQuestData.Invite i : invites) {
                g.text(font, Component.translatable("journal.nirncraft.party_invite", i.from()), x, y, Parchment.RED, false);
                y += 11;
            }
        }
    }
}
