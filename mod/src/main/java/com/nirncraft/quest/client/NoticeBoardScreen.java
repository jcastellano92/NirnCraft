package com.nirncraft.quest.client;

import com.nirncraft.inventory.client.SkyUi;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.QuestPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Settlement Notice Board screen: displays 3–6 pinned parchment contracts on a weathered timber board.
 * Selecting a notice shows the full in-character narrative letter, 3-stage Witcher-style progression,
 * poster signature, reward breakdown, and an [Accept Contract] button.
 */
public final class NoticeBoardScreen extends Screen {
    // Timber board palette
    private static final int WOOD_OUTER = 0xFF1E140C;
    private static final int WOOD_FRAME = 0xFF2E1F14;
    private static final int WOOD_PLANK_A = 0xFF24180F;
    private static final int WOOD_PLANK_B = 0xFF281B11;
    private static final int WOOD_SEAM = 0xFF160E08;
    private static final int IRON_BRACKET = 0xFF4A4E54;
    private static final int IRON_RIVET = 0xFF8C929A;

    // Aged parchment palette
    private static final int PARCHMENT_BG = 0xFFEDE0C4;
    private static final int PARCHMENT_CARD = 0xFFE5D5B5;
    private static final int PARCHMENT_CARD_HOVER = 0xFFF2E6CC;
    private static final int PARCHMENT_CARD_SEL = 0xFFF7EED8;
    private static final int PARCHMENT_TAKEN = 0xFFC8BCA4;
    private static final int PARCHMENT_BORDER = 0xFF8A6E48;
    private static final int PARCHMENT_BORDER_SEL = 0xFFD4A64A;
    private static final int INK_TITLE = 0xFF26170A;
    private static final int INK_BODY = 0xFF3B2816;
    private static final int INK_MUTED = 0xFF6B533B;
    private static final int INK_STAGE = 0xFF2F2012;
    private static final int INK_GOLD = 0xFF8C5808;
    private static final int STAMP_TAKEN = 0xFF8A2E22;

    public record NoticeEntry(
            int index,
            String id,
            String archetype,
            String title,
            String targetPlace,
            String sizeTier,
            long gold,
            int xp,
            int rep,
            String poster,
            String letter,
            List<String> stages,
            boolean taken
    ) {
        public NoticeEntry withTaken(boolean newTaken) {
            return new NoticeEntry(index, id, archetype, title, targetPlace, sizeTier, gold, xp, rep, poster, letter, stages, newTaken);
        }
    }

    private final String settlementId;
    private final String settlementName;
    private final List<NoticeEntry> notices = new ArrayList<>();
    private int selectedIndex = 0;

    private int boardX;
    private int boardY;
    private int boardW;
    private int boardH;
    private int btnX;
    private int btnY;
    private int btnW;
    private int btnH;

    public NoticeBoardScreen(String settlementId, String settlementName, List<NoticeEntry> entries) {
        super(Component.literal(settlementName + " Notice Board"));
        this.settlementId = settlementId;
        this.settlementName = settlementName;
        this.notices.addAll(entries);
        for (int i = 0; i < notices.size(); i++) {
            if (!notices.get(i).taken()) {
                this.selectedIndex = i;
                break;
            }
        }
    }

    public static void open(CompoundTag tag) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String settlementId = tag.getStringOr("settlementId", "");
        String settlementName = tag.getStringOr("settlementName", "");
        ListTag list = tag.getListOrEmpty("jobs");
        List<NoticeEntry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag j = list.getCompoundOrEmpty(i);
            List<String> stages = new ArrayList<>(3);
            ListTag stageTags = j.getListOrEmpty("stages");
            for (int s = 0; s < stageTags.size(); s++) {
                stages.add(stageTags.getCompoundOrEmpty(s).getStringOr("text", ""));
            }
            entries.add(new NoticeEntry(
                    j.getIntOr("index", 0),
                    j.getStringOr("id", ""),
                    j.getStringOr("archetype", ""),
                    j.getStringOr("title", ""),
                    j.getStringOr("targetPlace", ""),
                    j.getStringOr("size", ""),
                    j.getLongOr("gold", 0L),
                    j.getIntOr("xp", 0),
                    j.getIntOr("rep", 0),
                    j.getStringOr("poster", ""),
                    j.getStringOr("letter", ""),
                    stages,
                    j.getBooleanOr("taken", false)
            ));
        }
        mc.gui.setScreen(new NoticeBoardScreen(settlementId, settlementName, entries));
    }

    @Override
    protected void init() {
        this.boardW = Math.min(448, this.width - 20);
        this.boardH = Math.min(278, this.height - 20);
        this.boardX = (this.width - this.boardW) / 2;
        this.boardY = (this.height - this.boardH) / 2;
        this.btnW = 118;
        this.btnH = 20;
        this.btnX = this.boardX + this.boardW - 18 - this.btnW;
        this.btnY = this.boardY + this.boardH - 36;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        drawTimberBoard(g);
        drawPinnedCards(g, mouseX, mouseY);
        drawSelectedParchment(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawTimberBoard(GuiGraphicsExtractor g) {
        // Outer drop shadow & heavy timber frame
        g.fill(boardX - 4, boardY - 4, boardX + boardW + 4, boardY + boardH + 4, 0xA0000000);
        g.fill(boardX, boardY, boardX + boardW, boardY + boardH, WOOD_OUTER);
        g.fill(boardX + 2, boardY + 2, boardX + boardW - 2, boardY + boardH - 2, WOOD_FRAME);

        // Inner recessed plank backing
        int innerX = boardX + 8;
        int innerY = boardY + 32;
        int innerW = boardW - 16;
        int innerH = boardH - 40;
        g.fill(innerX, innerY, innerX + innerW, innerY + innerH, WOOD_PLANK_A);

        // Horizontal weathered wood planks
        int plankH = 28;
        for (int py = innerY; py < innerY + innerH; py += plankH) {
            int h = Math.min(plankH - 1, innerY + innerH - py);
            int tone = ((py / plankH) & 1) == 0 ? WOOD_PLANK_A : WOOD_PLANK_B;
            g.fill(innerX, py, innerX + innerW, py + h, tone);
            g.fill(innerX, py + h, innerX + innerW, py + h + 1, WOOD_SEAM);
        }

        // Iron corner brackets
        drawCornerBracket(g, boardX + 2, boardY + 2, true, true);
        drawCornerBracket(g, boardX + boardW - 14, boardY + 2, false, true);
        drawCornerBracket(g, boardX + 2, boardY + boardH - 14, true, false);
        drawCornerBracket(g, boardX + boardW - 14, boardY + boardH - 14, false, false);

        // Header beam
        String header = (settlementName + " — Notice Board").toUpperCase();
        g.centeredText(this.font, header, boardX + boardW / 2, boardY + 8, SkyUi.GOLD);
        g.centeredText(this.font, "Pinned Contracts, Bounties & Petitions of the Hold",
                boardX + boardW / 2, boardY + 19, SkyUi.DIM);
        SkyUi.divider(g, innerX + innerW / 2, boardY + 30, innerW / 2);
    }

    private void drawCornerBracket(GuiGraphicsExtractor g, int x, int y, boolean left, boolean top) {
        g.fill(x, y, x + 12, y + 3, IRON_BRACKET);
        g.fill(x, y, x + 3, y + 12, IRON_BRACKET);
        int rx = left ? x + 4 : x + 7;
        int ry = top ? y + 4 : y + 7;
        g.fill(rx, ry, rx + 2, ry + 2, 0xFF9EA4AE);
    }

    private void drawPinnedCards(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int listX = boardX + 14;
        int listY = boardY + 38;
        int listW = 172;
        int availH = boardH - 48;

        if (notices.isEmpty()) {
            g.centeredText(this.font, "No notices pinned today.", listX + listW / 2, listY + availH / 2 - 4, SkyUi.DIM);
            return;
        }

        int count = notices.size();
        int gap = 4;
        int cardH = Math.min(40, (availH - gap * (count - 1)) / Math.max(1, count));

        for (int i = 0; i < count; i++) {
            NoticeEntry n = notices.get(i);
            int cy = listY + i * (cardH + gap);
            boolean sel = i == selectedIndex;
            boolean hov = mouseX >= listX && mouseX < listX + listW && mouseY >= cy && mouseY < cy + cardH;

            // Drop shadow under pinned note
            g.fill(listX + 2, cy + 2, listX + listW + 2, cy + cardH + 2, 0x55000000);

            int bg = n.taken() ? PARCHMENT_TAKEN : (sel ? PARCHMENT_CARD_SEL : (hov ? PARCHMENT_CARD_HOVER : PARCHMENT_CARD));
            int border = sel ? PARCHMENT_BORDER_SEL : PARCHMENT_BORDER;
            g.fill(listX, cy, listX + listW, cy + cardH, bg);
            g.outline(listX, cy, listW, cardH, border);

            // Colored wax seal & brass pin at top-left of card
            int sealColor = sealColorFor(n.archetype());
            g.fill(listX + 5, cy + 5, listX + 13, cy + 13, sealColor);
            g.fill(listX + 8, cy + 7, listX + 10, cy + 9, 0xFFE8D49A);

            // Contract title
            String title = SkyUi.ellipsize(this.font, n.title(), listW - 24);
            g.text(this.font, title, listX + 17, cy + 5, INK_TITLE, false);

            // Poster & gold reward on bottom line of card
            String rewardText = n.taken() ? "TAKEN" : (n.gold() + "g");
            int rewardW = this.font.width(rewardText);
            String poster = SkyUi.ellipsize(this.font, "— " + n.poster(), listW - 26 - rewardW);
            g.text(this.font, poster, listX + 17, cy + cardH - 12, INK_MUTED, false);
            g.text(this.font, rewardText, listX + listW - 6 - rewardW, cy + cardH - 12,
                    n.taken() ? STAMP_TAKEN : INK_GOLD, false);
        }
    }

    private void drawSelectedParchment(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (selectedIndex < 0 || selectedIndex >= notices.size()) return;
        NoticeEntry n = notices.get(selectedIndex);

        int sheetX = boardX + 194;
        int sheetY = boardY + 36;
        int sheetW = boardW - 208;
        int sheetH = boardH - 46;

        // Shadow & aged parchment sheet
        g.fill(sheetX + 3, sheetY + 3, sheetX + sheetW + 3, sheetY + sheetH + 3, 0x70000000);
        g.fill(sheetX, sheetY, sheetX + sheetW, sheetY + sheetH, PARCHMENT_BG);
        g.outline(sheetX, sheetY, sheetW, sheetH, PARCHMENT_BORDER);
        g.outline(sheetX + 2, sheetY + 2, sheetW - 4, sheetH - 4, 0xFFCBB894);

        // Top iron pin + wax seal emblem
        int pinX = sheetX + sheetW / 2;
        int sealColor = sealColorFor(n.archetype());
        g.fill(pinX - 6, sheetY + 4, pinX + 6, sheetY + 12, sealColor);
        g.fill(pinX - 2, sheetY + 6, pinX + 2, sheetY + 9, 0xFFEEDCA8);

        int padX = sheetX + 10;
        int textW = sheetW - 20;
        int y = sheetY + 15;

        // Contract title & target location subtitle
        String title = SkyUi.ellipsize(this.font, n.title(), textW);
        g.text(this.font, title, padX, y, INK_TITLE, false);
        y += 11;

        String meta = categoryBadge(n.archetype()) + "  •  " + n.targetPlace();
        g.text(this.font, SkyUi.ellipsize(this.font, meta, textW), padX, y, INK_MUTED, false);
        y += 10;

        // Ink divider rule
        g.fill(padX, y, padX + textW, y + 1, 0x668A6E48);
        y += 5;

        // In-character narrative contract letter
        List<FormattedCharSequence> lines = this.font.split(Component.literal(n.letter()), textW);
        int maxLetterLines = 6;
        for (int i = 0; i < Math.min(lines.size(), maxLetterLines); i++) {
            g.text(this.font, lines.get(i), padX, y, INK_BODY, false);
            y += 9;
        }

        // Poster signature right-aligned
        String sig = "— " + n.poster();
        int sigW = this.font.width(sig);
        g.text(this.font, sig, sheetX + sheetW - 10 - sigW, y + 1, INK_MUTED, false);
        y += 12;

        // 3-Stage Witcher-style contract steps box
        int stageBoxY = Math.max(y, sheetY + sheetH - 86);
        int stageBoxH = 42;
        g.fill(padX, stageBoxY, padX + textW, stageBoxY + stageBoxH, 0x163A2610);
        g.outline(padX, stageBoxY, textW, stageBoxH, 0x448A6E48);

        int sy = stageBoxY + 4;
        String[] numerals = {"I.", "II.", "III."};
        for (int i = 0; i < Math.min(3, n.stages().size()); i++) {
            String stepLine = numerals[i] + " " + n.stages().get(i);
            g.text(this.font, SkyUi.ellipsize(this.font, stepLine, textW - 8), padX + 4, sy, INK_STAGE, false);
            sy += 12;
        }

        // Bottom reward breakdown & [Accept Contract] button
        this.btnX = sheetX + sheetW - 10 - btnW;
        this.btnY = sheetY + sheetH - 28;

        int rewardY = this.btnY + 2;
        g.text(this.font, "Reward: " + n.gold() + " Septims", padX, rewardY, INK_GOLD, false);
        g.text(this.font, "+" + n.xp() + " XP  •  +" + n.rep() + " Hold Standing", padX, rewardY + 10, INK_MUTED, false);

        if (n.taken()) {
            g.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0x338A2E22);
            g.outline(btnX, btnY, btnW, btnH, STAMP_TAKEN);
            g.centeredText(this.font, "Contract Taken", btnX + btnW / 2, btnY + 6, STAMP_TAKEN);
        } else {
            boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;
            SkyUi.button(g, this.font, Component.literal("Accept Contract"), btnX, btnY, btnW, btnH, btnHover, true, 1.0f);
        }
    }

    private static int sealColorFor(String archetype) {
        return switch (archetype) {
            case "bandit_bounty", "beast_hunt" -> 0xFFB03028; // Crimson wax seal
            case "missing_person" -> 0xFF2E5B88; // Sapphire wax seal
            default -> 0xFFC49234; // Gold wax seal
        };
    }

    private static String categoryBadge(String archetype) {
        return switch (archetype) {
            case "bandit_bounty" -> "Outlaw Bounty";
            case "beast_hunt" -> "Witcher / Monster Hunt";
            case "lost_heirloom" -> "Relic & Heirloom";
            case "missing_person" -> "Missing Person Investigation";
            default -> "Trade & Supply Petition";
        };
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button == 0) {
            int listX = boardX + 14;
            int listY = boardY + 38;
            int listW = 172;
            int availH = boardH - 48;
            int count = notices.size();
            int gap = 4;
            int cardH = Math.min(40, (availH - gap * (count - 1)) / Math.max(1, count));

            for (int i = 0; i < count; i++) {
                int cy = listY + i * (cardH + gap);
                if (mouseX >= listX && mouseX < listX + listW && mouseY >= cy && mouseY < cy + cardH) {
                    this.selectedIndex = i;
                    if (this.minecraft != null && this.minecraft.player != null) {
                        this.minecraft.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.7f, 1.1f);
                    }
                    return true;
                }
            }

            if (selectedIndex >= 0 && selectedIndex < notices.size()) {
                NoticeEntry sel = notices.get(selectedIndex);
                if (!sel.taken() && mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
                    acceptSelected();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key(), scanCode = event.keycode(), modifiers = event.modifiers();
        if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
            if (selectedIndex >= 0 && selectedIndex < notices.size() && !notices.get(selectedIndex).taken()) {
                acceptSelected();
                return true;
            }
        } else if (keyCode == 265 && selectedIndex > 0) { // Up arrow
            selectedIndex--;
            return true;
        } else if (keyCode == 264 && selectedIndex < notices.size() - 1) { // Down arrow
            selectedIndex++;
            return true;
        }
        return super.keyPressed(event);
    }

    private void acceptSelected() {
        if (selectedIndex < 0 || selectedIndex >= notices.size()) return;
        NoticeEntry sel = notices.get(selectedIndex);
        if (sel.taken()) return;
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.playSound(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.95f);
        }
        SkyNetwork.sendToServer(new QuestPackets.AcceptBoardJob(settlementId, sel.index()));
        notices.set(selectedIndex, sel.withTaken(true));
    }
}
