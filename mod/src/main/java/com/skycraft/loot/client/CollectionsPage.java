package com.skycraft.loot.client;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.economy.SkillBookItem;
import com.skycraft.loot.Collectibles;
import com.skycraft.quest.client.Parchment;
import com.skycraft.tamriel.Places;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The Journal's Collections tab: every collection with its progress on the left; on the right the selected one's
 * story, reward and entries (found ones named, missing ones with the place or province to search). Skill books are
 * shown per skill (volumes read of five).
 */
public final class CollectionsPage {
    private static final int ROW = 22;
    private static int selected = 0;

    private CollectionsPage() {}

    private static List<Collectibles.Collection> list() {
        return new ArrayList<>(Collectibles.all());
    }

    /** Selects the clicked collection; true when the click hit a row. */
    public static boolean click(double mx, double my, int x0, int top, int listX1, int listScroll) {
        int idx = (int) ((my - top + listScroll) / ROW);
        List<Collectibles.Collection> all = list();
        if (mx < x0 || mx >= listX1 || idx < 0 || idx >= all.size()) return false;
        selected = idx;
        return true;
    }

    /** Draws the page; returns {list height, detail height} for scrolling. */
    public static int[] render(GuiGraphics g, Font font, int x0, int y0, int x1, int y1, int listX1, int listScroll, int detailScroll) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return new int[]{0, 0};
        PlayerData data = SkyData.get(mc.player);
        List<Collectibles.Collection> all = list();
        if (all.isEmpty()) return new int[]{0, 0};
        selected = Math.max(0, Math.min(selected, all.size() - 1));
        int top = y0 + 30;
        int bottom = y1 - 8;

        // ---- list
        g.enableScissor(x0 + 4, top, listX1, bottom);
        int y = top - listScroll;
        for (int i = 0; i < all.size(); i++) {
            Collectibles.Collection c = all.get(i);
            int have = Collectibles.progress(data, c);
            int total = c.entries.size();
            boolean done = Collectibles.rewarded(data, c.id) || have >= total;
            if (i == selected) g.fill(x0 + 6, y, listX1 - 4, y + ROW - 2, Parchment.HIGHLIGHT);
            g.drawString(font, font.plainSubstrByWidth(c.name().getString(), listX1 - x0 - 50), x0 + 10, y + 2,
                    done ? Parchment.GREEN : Parchment.INK, false);
            String count = have + "/" + total;
            g.drawString(font, count, listX1 - 8 - font.width(count), y + 2, Parchment.INK_LIGHT, false);
            Parchment.bar(g, x0 + 10, y + 13, listX1 - x0 - 22, total == 0 ? 0 : have / (float) total, done ? Parchment.GREEN : Parchment.GOLD);
            y += ROW;
        }
        g.disableScissor();
        int listHeight = all.size() * ROW;
        Parchment.vrule(g, listX1 + 2, top, bottom);

        // ---- detail
        Collectibles.Collection c = all.get(selected);
        int dx = listX1 + 12;
        int w = x1 - dx - 12;
        g.enableScissor(listX1 + 4, top, x1 - 4, bottom);
        y = top - detailScroll;
        g.drawString(font, c.name(), dx, y, Parchment.RED, false);
        y += 13;
        y = wrap(g, font, Component.translatable("collection.skycraft." + c.id + ".desc"), dx, y, w, Parchment.INK_LIGHT) + 4;
        boolean rewarded = Collectibles.rewarded(data, c.id);
        Component reward = Component.translatable(rewarded ? "journal.skycraft.collections.rewarded" : "journal.skycraft.collections.reward",
                Component.translatable("collection.skycraft." + c.id + ".reward"));
        y = wrap(g, font, reward, dx, y, w, rewarded ? Parchment.GREEN : Parchment.GOLD) + 6;
        Parchment.rule(g, dx, x1 - 12, y - 3);

        if (c.id.equals(Collectibles.SKILL_BOOKS)) {
            for (Skill skill : Skill.VALUES) {
                int read = 0;
                for (int v = 1; v <= SkillBookItem.VOLUMES; v++) if (SkillBookItem.hasRead(data, skill, v)) read++;
                Parchment.checkbox(g, dx, y, read >= SkillBookItem.VOLUMES, false);
                g.drawString(font, skill.displayName(), dx + 12, y, read > 0 ? Parchment.INK : Parchment.INK_FADED, false);
                String n = read + "/" + SkillBookItem.VOLUMES;
                g.drawString(font, n, x1 - 16 - font.width(n), y, Parchment.INK_LIGHT, false);
                y += 11;
            }
        } else {
            for (Collectibles.Entry e : c.entries) {
                boolean have = Collectibles.has(data, e);
                Parchment.checkbox(g, dx, y, have, false);
                Component name = e.name();
                if (c.id.equals(Collectibles.BARENZIAH)) {
                    Component at = where(e);
                    name = Component.translatable("journal.skycraft.collections.stone_at", at == null ? Component.literal(e.id()) : at);
                }
                g.drawString(font, font.plainSubstrByWidth(name.getString(), w / 2 + 20), dx + 12, y, have ? Parchment.INK : Parchment.INK_FADED, false);
                Component where = where(e);
                if (where != null && !have && !c.id.equals(Collectibles.BARENZIAH)) {
                    String s = font.plainSubstrByWidth(where.getString(), w / 2 - 30);
                    g.drawString(font, s, x1 - 16 - font.width(s), y, Parchment.INK_FADED, false);
                }
                y += 11;
            }
        }
        g.disableScissor();
        return new int[]{listHeight, y + detailScroll - top};
    }

    /** Where to look: the named place (first one), else the province. */
    private static Component where(Collectibles.Entry e) {
        for (String p : e.places()) {
            if (p.endsWith("*")) return Component.translatable("journal.skycraft.collections.at_shrines");
            var place = Places.byId(p);
            if (place.isPresent()) return place.get().name();
        }
        if (e.province() != null) return Component.translatable("journal.skycraft.collections.province." + e.province());
        return null;
    }

    private static int wrap(GuiGraphics g, Font font, Component text, int x, int y, int w, int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(20, w))) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }
}
