package com.skycraft.mounts.client;

import com.skycraft.inventory.client.SkyUi;
import com.skycraft.mounts.MountKind;
import com.skycraft.mounts.MountProfile;
import com.skycraft.mounts.MountView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The full stat sheet of your own mount, drawn over the body of the mount menu ({@code SkyrimContainerScreen}, key 3 or
 * the empty-handed sneak-use): name, level and its experience bar, trust, the five stats with their grades, numbers,
 * the growth they have had out of the room they have, carry capacity and what the tack does. Without the Riding perk
 * Horse Sense it shows only what you can tell by eye, and says so.
 */
public final class MountSheetPanel {
    private MountSheetPanel() {}

    public static boolean available() {
        return MountClientState.sheet != null && MountClientState.sheet.known();
    }

    public static void draw(GuiGraphics g, Font font, int x, int y, int w, int h) {
        MountView v = MountClientState.sheet;
        g.fill(x, y, x + w, y + h, 0xF0101010);
        SkyUi.hline(g, x, x + w, y, 0x80);
        if (v == null || !v.known()) {
            g.drawString(font, Component.translatable("mounts.skycraft.sheet.none"), x + 12, y + 12, SkyUi.DIM, false);
            return;
        }
        MountKind kind = v.kind();
        int pad = 12;
        int lx = x + pad, ty = y + 8;
        g.drawString(font, MountViewText.name(v), lx, ty, SkyUi.GOLD, false);
        if (!v.name().isEmpty()) {
            g.drawString(font, kind.displayName(), lx + font.width(v.name()) + 8, ty, SkyUi.DIM, false);
        }
        ty += 14;

        // level and experience
        boolean numbers = v.detail() >= 1;
        Component lvl = numbers
                ? Component.translatable("mounts.skycraft.sheet.level", (int) v.level(), 20)
                : MountViewText.levelWord(v.level());
        g.drawString(font, lvl, lx, ty, SkyUi.TEXT, false);
        int barX = lx + 90, barW = Math.min(110, w / 2 - 110);
        SkyUi.bar(g, barX, ty + 2, barW, v.level() >= 20 ? 1f : v.levelProgress(), 0xC8A040);
        ty += 12;

        // trust
        g.drawString(font, MountViewText.trustLabel(), lx, ty, SkyUi.DIM, false);
        if (v.trust() >= 0) {
            SkyUi.bar(g, barX, ty + 2, barW, v.trust() / 100f, 0x70B070);
            g.drawString(font, Math.round(v.trust()) + "", barX + barW + 8, ty, SkyUi.TEXT, false);
        } else {
            g.drawString(font, Component.translatable("mounts.skycraft.sheet.unread"), barX, ty, SkyUi.DIM, false);
        }
        ty += 16;

        // the stats
        int colLabel = lx, colGrade = lx + 62, colNum = lx + 118, colGrow = lx + 178;
        SkyUi.caps(g, font, Component.translatable("mounts.skycraft.sheet.stat"), colLabel, ty, SkyUi.HEADER);
        SkyUi.caps(g, font, Component.translatable("mounts.skycraft.sheet.grade"), colGrade, ty, SkyUi.HEADER);
        if (v.numbers()) {
            SkyUi.caps(g, font, Component.translatable("mounts.skycraft.sheet.value"), colNum, ty, SkyUi.HEADER);
            SkyUi.caps(g, font, Component.translatable("mounts.skycraft.sheet.growth"), colGrow, ty, SkyUi.HEADER);
        }
        ty += 11;
        for (int stat = 0; stat < MountProfile.N; stat++) {
            g.drawString(font, MountViewText.label(kind, stat), colLabel, ty, SkyUi.TEXT, false);
            if (v.detail() == 0) {
                g.drawString(font, MountViewText.word(stat, v.gradeLo()[stat]).getString().isEmpty() ? Component.literal("-")
                        : MountViewText.word(stat, v.gradeLo()[stat]), colGrade, ty, SkyUi.TEXT, false);
            } else {
                g.drawString(font, MountViewText.grade(v, stat), colGrade, ty, MountViewText.color(v, stat), false);
            }
            if (v.numbers()) {
                g.drawString(font, MountViewText.number(stat, v.value()[stat]), colNum, ty, SkyUi.BRIGHT, false);
                // growth points against the room the kind and the grade leave (a little pip bar)
                int cap = v.caps()[stat], pts = v.growth()[stat];
                for (int i = 0; i < cap; i++) {
                    g.fill(colGrow + i * 5, ty + 1, colGrow + i * 5 + 4, ty + 7, i < pts ? 0xFFC8A040 : 0xFF3A362E);
                }
            }
            ty += 11;
        }
        // temperament is a grade, not a number
        g.drawString(font, MountViewText.label(kind, MountProfile.TEMPER), colLabel, ty, SkyUi.TEXT, false);
        if (v.detail() == 0) {
            g.drawString(font, MountViewText.word(MountProfile.TEMPER, v.gradeLo()[MountProfile.TEMPER]), colGrade, ty, SkyUi.TEXT, false);
        } else {
            g.drawString(font, MountViewText.grade(v, MountProfile.TEMPER), colGrade, ty, MountViewText.color(v, MountProfile.TEMPER), false);
        }
        ty += 16;

        // right column: tack, capacity, health
        int rx = x + w / 2 + 20;
        int ry = y + 22;
        g.drawString(font, Component.translatable("mounts.skycraft.sheet.tack"), rx, ry, SkyUi.HEADER, false);
        ry += 11;
        String saddle = v.saddle().isEmpty()
                ? Component.translatable("mounts.skycraft.card.unsaddled").getString()
                : Component.translatable("mounts.skycraft.saddle." + v.saddle()).getString();
        g.drawString(font, saddle, rx, ry, SkyUi.TEXT, false);
        ry += 11;
        if (!v.saddle().isEmpty()) {
            g.drawString(font, Component.translatable("mounts.skycraft.sheet.bags", Math.round(v.bags())), rx, ry, SkyUi.TEXT, false);
            ry += 11;
        }
        if (v.numbers()) {
            g.drawString(font, Component.translatable("mounts.skycraft.sheet.carry", Math.round(v.value()[MountProfile.CARRY])), rx, ry, SkyUi.TEXT, false);
            ry += 11;
        }
        g.drawString(font, v.armor() > 0 ? Component.translatable("mounts.skycraft.sheet.barding", v.armor())
                : Component.translatable("mounts.skycraft.sheet.no_barding"), rx, ry, SkyUi.TEXT, false);
        ry += 11;
        g.drawString(font, Component.translatable("mounts.skycraft.sheet.health", Math.round(v.health()), Math.round(v.maxHealth())), rx, ry,
                v.maxHealth() > 0 && v.health() < v.maxHealth() * 0.35f ? SkyUi.BAD : SkyUi.TEXT, false);
        ry += 16;
        MountKind k = kind;
        g.drawString(font, Component.translatable("mounts.skycraft.nature." + MountProfile.of(k).nature), rx, ry, SkyUi.DIM, false);
        if (v.detail() == 0) {
            g.drawString(font, Component.translatable("mounts.skycraft.sheet.need_sense"), x + pad, y + h - 12, SkyUi.DIM, false);
        } else {
            g.drawString(font, Component.translatable("mounts.skycraft.sheet.hint"), x + pad, y + h - 12, SkyUi.DIM, false);
        }
    }
}
