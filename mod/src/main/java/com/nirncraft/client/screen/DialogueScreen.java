package com.nirncraft.client.screen;

import com.nirncraft.dialogue.DialoguePackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;

/** Skyrim conversation menu: NPC name and line on top, the player's topics listed on the right. */
public class DialogueScreen extends Screen {
    private final DialoguePackets.OpenDialogue dialogue;
    private int hovered = -1;
    private int selected = 0;
    private int textScroll = 0;
    private int optScroll = 0;
    private boolean ensureVisible = false;
    private int[] plateBounds = new int[]{0, 0, 0, 0};

    public DialogueScreen(DialoguePackets.OpenDialogue dialogue) {
        super(dialogue.npcName());
        this.dialogue = dialogue;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int listX() {
        return width / 2 + 30;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Player's Current Gold in top-right
        if (minecraft != null && minecraft.player != null) {
            long playerGold = com.nirncraft.core.Currency.balance(minecraft.player);
            String goldLabel = "Gold: ";
            String goldCompact = com.nirncraft.core.Currency.formatCompact(playerGold) + "g";
            String goldFull = playerGold >= 1000 ? " (" + com.nirncraft.core.Currency.formatFull(playerGold) + ")" : "";
            int totalW = font.width(goldLabel) + font.width(goldCompact) + font.width(goldFull);
            int gx = width - totalW - 20;
            int gBoxY = 14;

            g.fill(gx - 8, gBoxY - 4, width - 12, gBoxY + 14, 0x90000000);
            g.fill(gx - 8, gBoxY - 4, gx - 6, gBoxY + 14, 0xFFE0B020);
            g.text(font, goldLabel, gx, gBoxY, 0xFFD8D0B8, true);
            g.text(font, goldCompact, gx + font.width(goldLabel), gBoxY, 0xFFE0B020, true);
            if (!goldFull.isEmpty()) {
                g.text(font, goldFull, gx + font.width(goldLabel) + font.width(goldCompact), gBoxY, 0xFFA09880, false);
            }
        }

        hovered = -1;
        List<DialoguePackets.Line> lines = dialogue.lines();

        // ---- layout: the speech plate owns the bottom of the screen, the topics own the right column above it
        int plateW = Math.min(560, width - 40);
        int plateX = (width - plateW) / 2;
        int textW = plateW - 40;
        List<FormattedCharSequence> greetingLines = font.split(dialogue.greeting(), textW);
        int maxTextH = Math.max(36, (int) (height * 0.38f) - 30);
        int maxTextLines = Math.max(3, maxTextH / 12);
        int shownLines = Math.min(greetingLines.size(), maxTextLines);
        int maxScroll = Math.max(0, greetingLines.size() - shownLines);
        textScroll = Math.max(0, Math.min(textScroll, maxScroll));
        int plateH = 16 + Math.max(12, shownLines * 12) + 22;
        int plateY = height - plateH - 14;

        int colW = Math.max(150, Math.min(300, width * 2 / 5));
        int x = width - colW - 14;
        int maxW = colW - 14;
        int areaTop = 34;
        int areaBottom = Math.max(areaTop + 30, plateY - 10);

        g.fillGradient(Math.max(0, x - 40), 0, width, areaBottom + 6, 0x00000000, 0x80000000);

        List<List<FormattedCharSequence>> wrappedList = new java.util.ArrayList<>();
        int[] entryY = new int[lines.size() + 1];
        int totalH = 0;
        int gap = 4;
        for (int i = 0; i < lines.size(); i++) {
            List<FormattedCharSequence> wrapped = font.split(lines.get(i).label(), maxW - 8);
            wrappedList.add(wrapped);
            entryY[i] = totalH;
            totalH += wrapped.size() * 11 + gap;
        }
        entryY[lines.size()] = totalH;
        int viewH = areaBottom - areaTop;
        int maxOptScroll = Math.max(0, totalH - viewH);
        if (ensureVisible && selected >= 0 && selected < lines.size()) {
            if (entryY[selected] < optScroll) optScroll = entryY[selected];
            else if (entryY[selected + 1] > optScroll + viewH) optScroll = entryY[selected + 1] - viewH;
            ensureVisible = false;
        }
        optScroll = Math.max(0, Math.min(optScroll, maxOptScroll));
        // short lists sit at the bottom of the column, just above the plate, like Skyrim's
        int startY = totalH < viewH ? areaBottom - totalH : areaTop;

        g.enableScissor(x - 14, areaTop, width, areaBottom);
        for (int i = 0; i < lines.size(); i++) {
            List<FormattedCharSequence> wrapped = wrappedList.get(i);
            int boxY1 = startY + entryY[i] - optScroll - 2;
            int boxY2 = startY + entryY[i + 1] - optScroll - 2;
            if (boxY2 < areaTop || boxY1 > areaBottom) continue;
            if (mouseX >= x - 12 && mouseX <= x + maxW + 4 && mouseY >= Math.max(boxY1, areaTop) && mouseY <= Math.min(boxY2, areaBottom)) {
                hovered = i;
            }
            boolean off = !lines.get(i).enabled();
            boolean hi = !off && (hovered == i || (hovered < 0 && selected == i));
            int curY = boxY1 + 2;
            if (hi) {
                g.fillGradient(x - 12, boxY1, x + maxW + 4, boxY2, 0x40FFFFFF, 0x10FFFFFF);
                g.fill(x - 12, boxY1, x - 10, boxY2, 0xFFE5B83B);
                g.text(font, "▶", x - 7, curY, 0xFFE5B83B, true);
            }
            int textX = hi ? x + 4 : x;
            int textY = curY;
            int textColor = off ? 0xFF5C5850 : hi ? 0xFFFFFFFF : 0xFFA8A090;
            for (FormattedCharSequence lineSeq : wrapped) {
                g.text(font, lineSeq, textX, textY, textColor, true);
                textY += 11;
            }
        }
        g.disableScissor();
        if (optScroll > 0) g.text(font, "▲", width - 22, areaTop - 9, 0xFFE5B83B, true);
        if (optScroll < maxOptScroll) g.text(font, "▼", width - 22, areaBottom + 1, 0xFFE5B83B, true);

        // ---- speech plate (drawn last so nothing covers it): name tag, divider, wrapped line
        g.fill(plateX, plateY, plateX + plateW, plateY + plateH, 0xEE0E0D0B);
        g.fillGradient(plateX, plateY, plateX + plateW, plateY + plateH, 0x15FFFFFF, 0x00000000);
        g.fill(plateX - 1, plateY - 1, plateX + plateW + 1, plateY, 0xFF4A3E2D);
        g.fill(plateX - 1, plateY + plateH, plateX + plateW + 1, plateY + plateH + 1, 0xFF4A3E2D);
        g.fill(plateX - 1, plateY - 1, plateX, plateY + plateH + 1, 0xFF4A3E2D);
        g.fill(plateX + plateW, plateY - 1, plateX + plateW + 1, plateY + plateH + 1, 0xFF4A3E2D);
        g.fill(plateX - 2, plateY - 2, plateX + 2, plateY + 2, 0xFFE5B83B);
        g.fill(plateX + plateW - 2, plateY - 2, plateX + plateW + 2, plateY + 2, 0xFFE5B83B);
        g.fill(plateX - 2, plateY + plateH - 2, plateX + 2, plateY + plateH + 2, 0xFFE5B83B);
        g.fill(plateX + plateW - 2, plateY + plateH - 2, plateX + plateW + 2, plateY + plateH + 2, 0xFFE5B83B);

        int nameY = plateY + 6;
        List<FormattedCharSequence> nameLines = font.split(dialogue.npcName(), plateW - 24);
        if (!nameLines.isEmpty()) g.centeredText(font, nameLines.get(0), plateX + plateW / 2, nameY, 0xFFF5EBC8);
        int divW = Math.min(180, plateW / 2);
        int divX = plateX + (plateW - divW) / 2;
        int divY = nameY + 11;
        g.fill(divX, divY, divX + divW, divY + 1, 0x80E5B83B);
        g.fill(divX + divW / 2 - 3, divY - 1, divX + divW / 2 + 3, divY + 2, 0xFFE5B83B);

        int curGy = divY + 6;
        for (int i = textScroll; i < textScroll + shownLines; i++) {
            g.centeredText(font, greetingLines.get(i), plateX + plateW / 2, curGy, 0xFFE6DEC8);
            curGy += 12;
        }
        if (textScroll > 0) g.text(font, "▲", plateX + plateW - 14, divY + 4, 0xFFE5B83B, true);
        if (textScroll < maxScroll) g.text(font, "▼", plateX + plateW - 14, plateY + plateH - 12, 0xFFE5B83B, true);
        plateBounds = new int[]{plateX, plateY, plateX + plateW, plateY + plateH};
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (hovered >= 0) {
            if (dialogue.lines().get(hovered).enabled()) choose(hovered);
            return true; // a greyed-out line swallows the click
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        int n = dialogue.lines().size();
        if (key == InputConstants.KEY_UP || key == InputConstants.KEY_W) selected = step(selected, -1, n);
        else if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_S) selected = step(selected, 1, n);
        else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_E) {
            if (selected >= 0 && selected < n && dialogue.lines().get(selected).enabled()) choose(selected);
        } else return super.keyPressed(event);
        ensureVisible = true;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        int step = delta > 0 ? -1 : 1;
        if (mx >= plateBounds[0] && mx <= plateBounds[2] && my >= plateBounds[1] && my <= plateBounds[3]) textScroll += step;
        else optScroll += step * 22;
        return true;
    }

    @Override
    public void onClose() {
        com.nirncraft.client.DialogueCamera.stop();
        SkyNetwork.sendToServer(new DialoguePackets.CloseDialogue(dialogue.entityId()));
        super.onClose();
    }

    /** The next line in a direction that can be chosen (greyed-out lines are skipped). */
    private int step(int from, int dir, int n) {
        int i = from;
        for (int k = 0; k < n; k++) {
            i = (i + dir + n) % n;
            if (dialogue.lines().get(i).enabled()) return i;
        }
        return from;
    }

    private void choose(int index) {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(com.nirncraft.world.WorldSounds.UI_MENU_CLICK.get(), 1.0f, 1.0f));
        }
        DialoguePackets.Line line = dialogue.lines().get(index);
        super.onClose();
        SkyNetwork.sendToServer(new DialoguePackets.ChooseOption(dialogue.entityId(), line.id()));
    }
}
