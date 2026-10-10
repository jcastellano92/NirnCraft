package com.skycraft.quest.client;

import com.skycraft.client.SkyKeys;
import com.skycraft.core.Holds;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.MainQuest;
import com.skycraft.quest.Objective;
import com.skycraft.quest.Quest;
import com.skycraft.quest.QuestPackets;
import net.minecraft.client.gui.GuiGraphics;
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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The Skyrim journal: a parchment page with tabs for Quests (active / completed, objectives, rewards, compass
 * tracking), Factions (membership, rank, reputation), Stats and Party.
 */
public class JournalScreen extends Screen {
    private static final int MAIN = 0, SIDE = 1, MISC = 2, FACTIONS = 3, REPUTATION = 4, STATS = 5, PARTY = 6;
    private static final String[] TABS = {"main", "side", "misc", "factions", "reputation", "stats", "party"};
    private static final int ROW = 12;

    private static int tab = MAIN;
    private static String selectedQuest = "";
    private static int selectedFaction = 0;

    private int x0, y0, x1, y1, listX1;
    private int listScroll, detailScroll, listHeight, detailHeight;
    private boolean abandonArmed;
    private final List<Row> rows = new ArrayList<>();
    private Button trackButton, shareButton, abandonButton, partyButton, skipButton;

    private record Row(@Nullable Quest quest, int y) {
    }

    public JournalScreen() {
        super(Component.translatable("screen.skycraft.journal"));
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
        trackButton = addRenderableWidget(Button.builder(Component.translatable("journal.skycraft.track"), b -> toggleTrack())
                .bounds(x1 - 180, y1 - 22, 90, 16).build());
        shareButton = addRenderableWidget(Button.builder(Component.translatable("journal.skycraft.share"), b -> shareWithParty())
                .bounds(x1 - 280, y1 - 22, 96, 16).build());
        abandonButton = addRenderableWidget(Button.builder(Component.translatable("journal.skycraft.abandon"), b -> abandon())
                .bounds(x1 - 86, y1 - 22, 78, 16).build());
        partyButton = addRenderableWidget(Button.builder(Component.translatable("journal.skycraft.open_party"),
                b -> minecraft.setScreen(new PartyScreen())).bounds((x0 + x1) / 2 - 60, y1 - 26, 120, 18).build());
        skipButton = addRenderableWidget(Button.builder(Component.translatable("journal.skycraft.skip"), b -> skipStep())
                .bounds(x1 - 180, y1 - 40, 172, 16).build());
        skipButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("journal.skycraft.skip_hint")));
        tick();
    }

    /** The current step has been stuck for the configured number of days (the server checks it again). */
    private boolean canSkip(@Nullable Quest q) {
        if (q == null || !q.isActive() || minecraft == null || minecraft.level == null) return false;
        Objective o = q.current();
        if (o == null) return false;
        long at = o.data.getLong("skip_at");
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
        if (show) {
            boolean tracked = q.id.equals(ClientQuestData.tracked());
            trackButton.setMessage(Component.translatable(tracked ? "journal.skycraft.untrack" : "journal.skycraft.track"));
            abandonButton.setMessage(Component.translatable(abandonArmed ? "journal.skycraft.abandon_confirm" : "journal.skycraft.abandon"));
        }
        partyButton.visible = tab == PARTY;
        skipButton.visible = show && canSkip(q);
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
        int x = x0 + 10;
        for (int j = 0; j < i; j++) x += font.width(Component.translatable("journal.skycraft.tab." + TABS[j])) + 20;
        return x;
    }

    private int tabWidth(int i) {
        return font.width(Component.translatable("journal.skycraft.tab." + TABS[i])) + 16;
    }

    private void setTab(int t) {
        tab = Math.floorMod(t, TABS.length);
        listScroll = detailScroll = 0;
        abandonArmed = false;
        tick();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        for (int i = 0; i < TABS.length; i++) {
            int tx = tabX(i);
            if (mx >= tx && mx < tx + tabWidth(i) && my >= y0 + 6 && my < y0 + 21) {
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
            } else if (tab == FACTIONS) {
                int idx = (int) ((my - top + listScroll) / 22);
                if (idx >= 0 && idx < Faction.VALUES.length) {
                    selectedFaction = idx;
                    detailScroll = 0;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int top = y0 + 30;
        int view = y1 - 8 - top;
        if (mx < listX1 && tab != STATS && tab != REPUTATION) {
            listScroll = Mth.clamp(listScroll - (int) (delta * 14), 0, Math.max(0, listHeight - view));
        } else {
            detailScroll = Mth.clamp(detailScroll - (int) (delta * 14), 0, Math.max(0, detailHeight - view + 24));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (SkyKeys.JOURNAL.matches(key, scan)) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_Q) {
            setTab(tab - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_E) {
            setTab(tab + 1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Parchment.page(g, x0, y0, x1, y1);
        drawTabs(g, mouseX, mouseY);
        switch (tab) {
            case MAIN, SIDE, MISC -> renderQuests(g, mouseX, mouseY);
            case FACTIONS -> renderFactions(g, mouseX, mouseY);
            case REPUTATION -> renderReputation(g);
            case STATS -> renderStats(g);
            default -> renderParty(g);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawTabs(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < TABS.length; i++) {
            int tx = tabX(i);
            int tw = tabWidth(i);
            boolean sel = i == tab;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= y0 + 6 && mouseY < y0 + 21;
            if (sel) g.fill(tx, y0 + 6, tx + tw, y0 + 21, 0x50A07A3A);
            else if (hover) g.fill(tx, y0 + 6, tx + tw, y0 + 21, 0x25A07A3A);
            if (sel) g.fill(tx, y0 + 20, tx + tw, y0 + 21, Parchment.INK);
            g.drawString(font, Component.translatable("journal.skycraft.tab." + TABS[i]), tx + 8, y0 + 9,
                    sel ? Parchment.INK : Parchment.INK_LIGHT, false);
        }
        Parchment.rule(g, x0 + 8, x1 - 8, y0 + 24);
    }

    private void heading(GuiGraphics g, Component text, int x, int y) {
        g.drawString(font, text, x, y, Parchment.RED, false);
    }

    private int drawWrapped(GuiGraphics g, Component text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(20, w))) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }

    // ------------------------------------------------------------------ quests tab

    private void renderQuests(GuiGraphics g, int mouseX, int mouseY) {
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
        heading(g, Component.translatable("journal.skycraft.active", active.size()), lx, y);
        y += ROW + 2;
        if (active.isEmpty()) {
            g.drawString(font, Component.translatable("journal.skycraft.no_quests"), lx + 4, y, Parchment.INK_FADED, false);
            y += ROW;
        }
        for (Quest q : active) y = questRow(g, q, lx, y, lw, sel, mouseX, mouseY);
        y += 6;
        heading(g, Component.translatable("journal.skycraft.completed", finished.size()), lx, y);
        y += ROW + 2;
        for (Quest q : finished) y = questRow(g, q, lx, y, lw, sel, mouseX, mouseY);
        g.disableScissor();
        listHeight = y + listScroll - top;

        if (sel == null) {
            drawWrapped(g, Component.translatable("journal.skycraft.empty_hint"), listX1 + 12, top + 10, x1 - listX1 - 24, Parchment.INK_FADED);
            detailHeight = 0;
            return;
        }
        int dBottom = sel.isActive() ? bottom - (skipButton.visible ? 38 : 20) : bottom;
        g.enableScissor(listX1 + 6, top, x1 - 4, dBottom);
        detailHeight = renderQuestDetail(g, sel, listX1 + 12, top - detailScroll, x1 - listX1 - 24) - (top - detailScroll);
        g.disableScissor();
    }

    private int questRow(GuiGraphics g, Quest q, int x, int y, int w, @Nullable Quest sel, int mouseX, int mouseY) {
        boolean selected = sel != null && sel.id.equals(q.id);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + ROW - 1 && mouseY >= y0 + 30 && mouseY < y1 - 8;
        if (selected) g.fill(x - 2, y - 2, x + w, y + ROW - 2, Parchment.HIGHLIGHT);
        else if (hover) g.fill(x - 2, y - 2, x + w, y + ROW - 2, 0x20A07A3A);
        Faction f = Faction.byId(q.faction);
        Parchment.icon(g, x, y, q.category, f == null ? 0x6E5638 : f.color);
        int color = q.isActive() ? Parchment.INK : q.status == Quest.Status.FAILED ? 0xFF9A5A4A : Parchment.INK_FADED;
        int textW = w - 14 - (q.isActive() && ClientQuestData.isTracked(q) ? 8 : 0);
        String title = font.plainSubstrByWidth(q.title.getString(), textW);
        g.drawString(font, title, x + 11, y, color, false);
        if (!q.isActive()) g.fill(x + 11, y + 4, x + 11 + font.width(title), y + 5, 0x806E5638);
        if (q.isActive() && ClientQuestData.isTracked(q)) {
            int ax = x + w - 8;
            for (int i = 0; i < 4; i++) g.fill(ax + i, y + 1 + i, ax + 7 - i, y + 2 + i, Parchment.GOLD);
        }
        rows.add(new Row(q, y));
        return y + ROW;
    }

    /** Draws the selected quest; returns the y after the last line. */
    private int renderQuestDetail(GuiGraphics g, Quest q, int x, int y, int w) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(1.3f, 1.3f, 1f);
        g.drawString(font, q.title, 0, 0, Parchment.INK, false);
        g.pose().popPose();
        y += 16;
        Component cat = Component.translatable("journal.skycraft.category." + q.category.name().toLowerCase(Locale.ROOT));
        Faction f = Faction.byId(q.faction);
        if (f != null) cat = Component.translatable("journal.skycraft.faction_quest", f.displayName());
        if (q.shared) cat = cat.copy().append(Component.translatable("journal.skycraft.party_quest"));
        g.drawString(font, cat, x, y, Parchment.INK_LIGHT, false);
        y += 11;
        if (!q.giverName.getString().isEmpty()) {
            Component giver = q.hold.isEmpty() ? Component.translatable("journal.skycraft.given_by", q.giverName)
                    : Component.translatable("journal.skycraft.given_by_in", q.giverName, Holds.displayName(q.hold));
            y = drawWrapped(g, giver, x, y, w, Parchment.INK_LIGHT);
        }
        y += 3;
        Parchment.rule(g, x, x + w, y);
        y += 6;
        y = drawWrapped(g, q.description, x, y, w, Parchment.INK);
        y += 8;
        // story quests keep a journal: what happened at each step, oldest first (the first entry is the description)
        var journal = q.extra.getList("journal", Tag.TAG_STRING);
        if (journal.size() > 1) {
            heading(g, Component.translatable("journal.skycraft.journal"), x, y);
            y += 12;
            for (int i = 1; i < journal.size(); i++) {
                y = drawWrapped(g, Quest.readComponent(journal.getString(i)), x + 4, y, w - 4, Parchment.INK_LIGHT) + 4;
            }
            y += 4;
        }

        heading(g, Component.translatable("journal.skycraft.objectives"), x, y);
        y += 12;
        Objective current = q.current();
        for (Objective o : q.objectives) {
            boolean shown = o.done || o == current;
            if (!shown) continue;
            boolean failed = q.status == Quest.Status.FAILED && !o.done;
            Parchment.checkbox(g, x, y, o.done, failed);
            Component text = o.text;
            if (o.required > 1 && !o.done) text = Component.translatable("journal.skycraft.objective_progress", o.text, o.progress, o.required);
            int color = o.done ? Parchment.INK_FADED : Parchment.INK;
            y = drawWrapped(g, text, x + 11, y, w - 12, color) + 3;
        }
        y += 6;

        heading(g, Component.translatable("journal.skycraft.rewards"), x, y);
        y += 12;
        if (q.rewardGold > 0) {
            g.drawString(font, Component.translatable("journal.skycraft.reward_gold", q.rewardGold), x + 4, y, Parchment.GOLD, false);
            y += 10;
        }
        for (ItemStack s : q.rewardItems) {
            Component name = s.getCount() > 1 ? Component.literal(s.getCount() + "x ").append(s.getHoverName()) : s.getHoverName();
            g.drawString(font, Component.literal("• ").append(name), x + 4, y, Parchment.INK, false);
            y += 10;
        }
        if (f != null) {
            Component rep = q.factionJoin ? Component.translatable("journal.skycraft.reward_join", f.displayName())
                    : Component.translatable("journal.skycraft.reward_reputation", q.factionPoints, f.displayName());
            g.drawString(font, rep, x + 4, y, Parchment.INK_LIGHT, false);
            y += 10;
        }
        if (q.category == Quest.Category.FACTION && Faction.DARK_BROTHERHOOD.id.equals(q.faction)) {
            y = drawWrapped(g, Component.translatable("journal.skycraft.sneak_bonus_hint"), x + 4, y, w - 4, Parchment.INK_FADED);
        }
        y += 6;
        if (q.status == Quest.Status.COMPLETED) {
            g.drawString(font, Component.translatable("journal.skycraft.status_completed"), x, y, Parchment.GREEN, false);
            y += 10;
        } else if (q.status == Quest.Status.FAILED) {
            Component reason = Quest.readComponent(q.extra.getString("failReason"));
            y = drawWrapped(g, Component.translatable("journal.skycraft.status_failed", reason), x, y, w, Parchment.RED);
        }
        return y;
    }

    // ------------------------------------------------------------------ factions tab

    private void renderFactions(GuiGraphics g, int mouseX, int mouseY) {
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
            g.drawString(font, font.plainSubstrByWidth(f.displayName().getString(), listX1 - lx - 16), lx + 10, y, Parchment.INK, false);
            Component sub = rank >= 0 ? f.rankName(rank) : Component.translatable("journal.skycraft.not_member");
            g.drawString(font, sub, lx + 10, y + 10, rank >= 0 ? Parchment.INK_LIGHT : Parchment.INK_FADED, false);
            y += 22;
        }
        g.disableScissor();
        listHeight = Faction.VALUES.length * 22;

        Faction f = Faction.VALUES[Mth.clamp(selectedFaction, 0, Faction.VALUES.length - 1)];
        int x = listX1 + 12;
        int w = x1 - listX1 - 24;
        g.enableScissor(listX1 + 6, top, x1 - 4, bottom);
        y = top - detailScroll;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(1.3f, 1.3f, 1f);
        g.drawString(font, f.displayName(), 0, 0, Parchment.INK, false);
        g.pose().popPose();
        y += 18;
        int rank = Factions.rank(data, f);
        if (rank >= 0) {
            g.drawString(font, Component.translatable("journal.skycraft.rank", f.rankName(rank), rank + 1, f.ranks), x, y, Parchment.INK, false);
            y += 12;
            int pts = Factions.points(data, f);
            if (rank < f.maxRank()) {
                int from = Faction.pointsFor(rank);
                int to = Faction.pointsFor(rank + 1);
                Parchment.bar(g, x, y + 1, Math.min(140, w), (pts - from) / (float) Math.max(1, to - from), 0xFF000000 | f.color);
                g.drawString(font, Component.translatable("journal.skycraft.next_rank", f.rankName(rank + 1), pts, to),
                        x + Math.min(140, w) + 6, y - 1, Parchment.INK_LIGHT, false);
            } else {
                g.drawString(font, Component.translatable("journal.skycraft.max_rank"), x, y, Parchment.GOLD, false);
            }
            y += 12;
            if (f == Faction.COMPANIONS && data.module("quest").getBoolean("beast_blood")) {
                y = drawWrapped(g, Component.translatable("journal.skycraft.beast_blood"), x, y, w, Parchment.RED);
            }
        } else {
            Faction rival = f.rival();
            if (rival != null && Factions.isMember(data, rival)) {
                y = drawWrapped(g, Component.translatable("journal.skycraft.enemy_of", rival.displayName()), x, y, w, Parchment.RED);
            } else {
                g.drawString(font, Component.translatable("journal.skycraft.not_member"), x, y, Parchment.INK_FADED, false);
                y += 12;
            }
        }
        y += 4;
        Parchment.rule(g, x, x + w, y);
        y += 7;
        y = drawWrapped(g, f.description(), x, y, w, Parchment.INK);
        y += 6;
        if (rank < 0) {
            heading(g, Component.translatable("journal.skycraft.how_to_join"), x, y);
            y += 12;
            y = drawWrapped(g, f.howToJoin(), x, y, w, Parchment.INK_LIGHT);
        }
        y += 6;
        heading(g, Component.translatable("journal.skycraft.ranks"), x, y);
        y += 12;
        for (int r = 0; r < f.ranks; r++) {
            int color = r == rank ? Parchment.INK : r < rank ? Parchment.INK_LIGHT : Parchment.INK_FADED;
            g.drawString(font, Component.literal((r + 1) + ". ").append(f.rankName(r)), x + 4, y, color, false);
            y += 10;
        }
        g.disableScissor();
        detailHeight = y + detailScroll - top;
    }

    // ------------------------------------------------------------------ stats tab

    private void renderStats(GuiGraphics g) {
        PlayerData data = SkyData.get(minecraft.player);
        Map<String, String> general = new java.util.LinkedHashMap<>();
        general.put("journal.skycraft.stat_level", String.valueOf(data.getLevel()));
        general.put("journal.skycraft.stat_race", data.getRace() == null ? "-" : data.getRace().displayName().getString());
        String title = data.module("quest").getString("title");
        general.put("journal.skycraft.stat_title", title.isEmpty() ? "-" : I18n.get("journal.skycraft.title." + title));
        general.put("journal.skycraft.stat_gold", String.valueOf(data.getGold()));
        int stage = MainQuest.stage(data);
        general.put("journal.skycraft.stat_main", stage > MainQuest.FINAL_STAGE ? I18n.get("journal.skycraft.main_done")
                : stage <= 0 ? "-" : stage + "/" + MainQuest.FINAL_STAGE);
        int completed = 0;
        for (Quest q : ClientQuestData.quests()) if (q.status == Quest.Status.COMPLETED) completed++;
        general.put("journal.skycraft.stat_quests_journal", String.valueOf(completed));

        CompoundTag crime = data.module("crime");
        int bounty = 0;
        CompoundTag b = crime.getCompound("bounty");
        for (String k : b.getAllKeys()) bounty += b.getInt(k);
        general.put("journal.skycraft.stat_murders", String.valueOf(crime.getInt("murders")));
        general.put("journal.skycraft.stat_stolen", String.valueOf(crime.getInt("items_stolen")));
        general.put("journal.skycraft.stat_pickpockets", String.valueOf(crime.getInt("pickpockets")));
        general.put("journal.skycraft.stat_assaults", String.valueOf(crime.getInt("assaults")));
        general.put("journal.skycraft.stat_bounty", String.valueOf(bounty));

        CompoundTag magic = data.module("magic");
        int words = 0;
        CompoundTag w = magic.getCompound("words");
        for (String k : w.getAllKeys()) words += w.getInt(k);
        general.put("journal.skycraft.stat_spells", String.valueOf(magic.getList("spells", Tag.TAG_STRING).size()));
        general.put("journal.skycraft.stat_words", String.valueOf(words));
        general.put("journal.skycraft.stat_souls", String.valueOf(magic.getInt("dragon_souls")));

        Map<String, Integer> tracked = new TreeMap<>(data.stats());

        int top = y0 + 30;
        int bottom = y1 - 8;
        int colW = (x1 - x0 - 32) / 2;
        int lx = x0 + 12;
        int rx = lx + colW + 8;
        Parchment.vrule(g, rx - 5, top, bottom);
        g.enableScissor(x0 + 4, top, x1 - 4, bottom);
        int y = top - detailScroll;
        heading(g, Component.translatable("journal.skycraft.character"), lx, y);
        int ly = y + 13;
        for (Map.Entry<String, String> e : general.entrySet()) {
            statLine(g, Component.translatable(e.getKey()), e.getValue(), lx, ly, colW - 6);
            ly += 11;
        }
        heading(g, Component.translatable("journal.skycraft.statistics"), rx, y);
        int ry = y + 13;
        if (tracked.isEmpty()) {
            g.drawString(font, Component.translatable("journal.skycraft.no_stats"), rx, ry, Parchment.INK_FADED, false);
            ry += 11;
        }
        for (Map.Entry<String, Integer> e : tracked.entrySet()) {
            String key = "journal.skycraft.stat." + e.getKey();
            Component label = I18n.exists(key) ? Component.translatable(key) : Component.literal(prettify(e.getKey()));
            statLine(g, label, String.valueOf(e.getValue()), rx, ry, colW - 6);
            ry += 11;
        }
        g.disableScissor();
        detailHeight = Math.max(ly, ry) + detailScroll - top;
    }

    private void statLine(GuiGraphics g, Component label, String value, int x, int y, int w) {
        String l = font.plainSubstrByWidth(label.getString(), w - font.width(value) - 8);
        g.drawString(font, l, x, y, Parchment.INK_LIGHT, false);
        g.drawString(font, value, x + w - font.width(value), y, Parchment.INK, false);
        int dotsFrom = x + font.width(l) + 3;
        int dotsTo = x + w - font.width(value) - 3;
        for (int dx = dotsFrom; dx < dotsTo; dx += 3) g.fill(dx, y + 7, dx + 1, y + 8, 0x606E5638);
    }

    private static String prettify(String key) {
        String s = key.replace('_', ' ').replace('.', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ reputation tab

    private void renderReputation(GuiGraphics g) {
        PlayerData data = SkyData.get(minecraft.player);
        CompoundTag crime = data.module("crime");
        CompoundTag bountyTag = crime.getCompound("bounty");

        int top = y0 + 30;
        int bottom = y1 - 8;
        int colW = (x1 - x0 - 32) / 2;
        int lx = x0 + 12;
        int rx = lx + colW + 8;
        Parchment.vrule(g, rx - 5, top, bottom);
        g.enableScissor(x0 + 4, top, x1 - 4, bottom);
        int y = top - detailScroll;

        heading(g, Component.translatable("journal.skycraft.reputation_holds"), lx, y);
        int ly = y + 14;
        // Skyrim's nine holds always, then every other region where the player has a bounty
        List<String> holds = new ArrayList<>(List.of(Holds.IDS));
        for (String k : bountyTag.getAllKeys()) {
            String id = Holds.migrate(k);
            if (!holds.contains(id) && bountyTag.getInt(k) > 0) holds.add(id);
        }
        for (String hold : holds) {
            Component hName = Holds.displayName(hold);
            int bounty = bountyTag.getInt(hold);
            g.drawString(font, hName, lx + 4, ly, Parchment.INK, false);
            if (bounty > 0) {
                g.drawString(font, Component.translatable("journal.skycraft.bounty_val", bounty), lx + colW - 70, ly, Parchment.RED, false);
            } else {
                g.drawString(font, Component.translatable("journal.skycraft.law_abiding"), lx + colW - 70, ly, Parchment.GREEN, false);
            }
            ly += 14;
        }

        heading(g, Component.translatable("journal.skycraft.reputation_standing"), rx, y);
        int ry = y + 14;
        int completed = 0;
        for (Quest q : ClientQuestData.quests()) if (q.status == Quest.Status.COMPLETED) completed++;
        statLine(g, Component.translatable("journal.skycraft.standing_title"),
                completed >= 10 ? I18n.get("journal.skycraft.title_hero") : I18n.get("journal.skycraft.title_traveler"), rx, ry, colW - 6);
        ry += 13;
        statLine(g, Component.translatable("journal.skycraft.standing_quests"), String.valueOf(completed), rx, ry, colW - 6);
        ry += 13;
        statLine(g, Component.translatable("journal.skycraft.total_bounty"), String.valueOf(totalBounty(data)), rx, ry, colW - 6);
        ry += 13;
        statLine(g, Component.translatable("journal.skycraft.stolen_goods"), String.valueOf(crime.getInt("items_stolen")), rx, ry, colW - 6);
        ry += 13;

        g.disableScissor();
        detailHeight = Math.max(ly, ry) + detailScroll - top;
    }

    private int totalBounty(PlayerData data) {
        int total = 0;
        CompoundTag b = data.module("crime").getCompound("bounty");
        for (String k : b.getAllKeys()) total += b.getInt(k);
        return total;
    }

    // ------------------------------------------------------------------ party tab

    private void renderParty(GuiGraphics g) {
        int top = y0 + 32;
        int x = x0 + 16;
        int w = x1 - x0 - 32;
        if (!ClientQuestData.inParty()) {
            drawWrapped(g, Component.translatable("journal.skycraft.party_none"), x, top, w, Parchment.INK_LIGHT);
        } else {
            heading(g, Component.translatable("journal.skycraft.party_members", ClientQuestData.members().size(), ClientQuestData.maxSize()), x, top);
            int y = top + 14;
            UUID leader = ClientQuestData.leader();
            for (ClientQuestData.Member m : ClientQuestData.members()) {
                String name = (m.id().equals(leader) ? "★ " : "") + m.name();
                g.drawString(font, name, x, y, m.online() ? Parchment.INK : Parchment.INK_FADED, false);
                if (m.online()) {
                    float fill = m.maxHealth() > 0 ? m.health() / m.maxHealth() : 0;
                    Parchment.bar(g, x + 120, y + 2, 100, fill, 0xFFB0262A);
                    g.drawString(font, (int) (m.health() * 5) + " / " + (int) (m.maxHealth() * 5), x + 228, y, Parchment.INK_LIGHT, false);
                } else {
                    g.drawString(font, Component.translatable("party.skycraft.offline"), x + 120, y, Parchment.INK_FADED, false);
                }
                y += 14;
            }
            y += 6;
            drawWrapped(g, Component.translatable("journal.skycraft.party_hint"), x, y, w, Parchment.INK_FADED);
        }
        List<ClientQuestData.Invite> invites = ClientQuestData.invites();
        if (!invites.isEmpty()) {
            int y = y1 - 60;
            for (ClientQuestData.Invite i : invites) {
                g.drawString(font, Component.translatable("journal.skycraft.party_invite", i.from()), x, y, Parchment.RED, false);
                y += 11;
            }
        }
    }
}
