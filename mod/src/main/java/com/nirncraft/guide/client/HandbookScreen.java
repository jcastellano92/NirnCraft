package com.nirncraft.guide.client;

import com.nirncraft.NirnCraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * The Traveler's Handbook: a two-page parchment spread (the lore module's book art) with a title page and a table of
 * contents, then every chapter. Chapter text is lang ({@code guide.nirncraft.chapter.<id>.text}) with a light markup:
 * {@code # heading}, {@code * bullet}, {@code ---} soft page break, {@code **bold**} and {@code {key:<mapping>}} for the
 * player's current key binding.
 *
 * <p>The spread is 380x220 and fits a Steam Deck's 427x267 GUI unscaled; on anything smaller the whole book is
 * scaled down. Turn pages with the arrows, A/D, the arrow keys, the mouse wheel or by clicking a page; Home or
 * Backspace returns to the contents.</p>
 */
public class HandbookScreen extends Screen {
    private static final Identifier SPREAD = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/book_spread.png");
    private static final Identifier ARROWS = Identifier.parse("textures/gui/book.png");
    private static final int W = 380, H = 220;
    private static final int TEXT_W = 150, LINES = 16, LINE_H = 10;
    private static final int LEFT_X = 22, RIGHT_X = 208, TEXT_Y = 20;
    private static final int INK = 0xFF3B2A1A;
    private static final int HEADING = 0xFF6E2414;
    private static final int FADED = 0xFF7A6448;
    private static final int HOVER = 0xFFA0521E;
    private static final Style KEY_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(0x6E2414));

    /** Chapter ids, in order; each has {@code guide.nirncraft.chapter.<id>} and {@code ...<id>.text}. */
    public static final List<String> CHAPTERS = List.of("getting_started", "controls", "races", "skills", "magic",
            "combat", "stealth", "economy", "survival", "factions", "world", "multiplayer");

    private record Line(FormattedCharSequence text, boolean center, int indent, int color, boolean bullet) {}

    private static int lastView;
    private static String preselect;

    private final List<List<Line>> pages = new ArrayList<>();
    private final int[] chapterStart = new int[CHAPTERS.size()];
    @org.jetbrains.annotations.Nullable
    private final Screen returnScreen;
    private int view;
    private float scale = 1f;
    private int left, top;

    public HandbookScreen() {
        this(null);
    }

    public HandbookScreen(@org.jetbrains.annotations.Nullable Screen returnScreen) {
        super(Component.translatable("item.nirncraft.travelers_handbook"));
        this.returnScreen = returnScreen;
    }

    /** Opens at this chapter next time ({@code /handbook chapter <id>}, autotest); any other value opens the contents. */
    public static void preselect(String chapter) {
        preselect = chapter;
    }

    @Override
    protected void init() {
        scale = Math.min(1f, Math.min((width - 8) / (float) W, (height - 8) / (float) H));
        left = Math.round((width - W * scale) / 2);
        top = Math.round((height - H * scale) / 2);
        paginate();
        view = lastView;
        if (preselect != null) {
            int i = CHAPTERS.indexOf(preselect);
            view = i >= 0 ? viewOfPage(chapterStart[i]) : 0; // anything else ("contents") opens at the contents
            lastView = view;
            preselect = null;
        }
        view = Math.max(0, Math.min(view, views() - 1));
    }

    // ------------------------------------------------------------------ layout

    private void paginate() {
        pages.clear();
        List<Line> cur = new ArrayList<>();
        for (int c = 0; c < CHAPTERS.size(); c++) {
            String id = CHAPTERS.get(c);
            if (!cur.isEmpty()) {
                pages.add(cur);
                cur = new ArrayList<>();
            }
            chapterStart[c] = pages.size();
            MutableComponent title = Component.translatable("guide.nirncraft.chapter." + id).withStyle(Style.EMPTY.withBold(true));
            for (FormattedCharSequence s : font.split(title, TEXT_W)) cur.add(new Line(s, true, 0, HEADING, false));
            cur.add(new Line(FormattedCharSequence.EMPTY, false, 0, INK, false));
            String text = I18n.get("guide.nirncraft.chapter." + id + ".text");
            for (String raw : text.split("\n", -1)) {
                if (raw.strip().equals("---")) {
                    // a soft break: a new page only when this one is nearly full, else a paragraph gap
                    if (cur.size() < LINES - 4) {
                        if (!cur.isEmpty()) cur.add(new Line(FormattedCharSequence.EMPTY, false, 0, INK, false));
                    } else if (!cur.isEmpty()) {
                        pages.add(cur);
                        cur = new ArrayList<>();
                    }
                    continue;
                }
                if (raw.isBlank()) {
                    if (!cur.isEmpty() && cur.size() < LINES) cur.add(new Line(FormattedCharSequence.EMPTY, false, 0, INK, false));
                    continue;
                }
                boolean heading = raw.startsWith("# ");
                boolean bullet = raw.startsWith("* ");
                String body = heading || bullet ? raw.substring(2).trim() : raw;
                MutableComponent comp = GuideText.format(body, KEY_STYLE);
                if (heading) comp = comp.withStyle(Style.EMPTY.withBold(true));
                List<FormattedCharSequence> wrapped = font.split(comp, bullet ? TEXT_W - 8 : TEXT_W);
                // keep a heading with at least two lines of its section
                if (heading && cur.size() > LINES - 3) {
                    pages.add(cur);
                    cur = new ArrayList<>();
                }
                for (int k = 0; k < wrapped.size(); k++) {
                    if (cur.size() >= LINES) {
                        pages.add(cur);
                        cur = new ArrayList<>();
                    }
                    cur.add(new Line(wrapped.get(k), false, bullet ? 8 : 0, heading ? HEADING : INK, bullet && k == 0));
                }
            }
        }
        if (!cur.isEmpty()) pages.add(cur);
        // trim blank lines a page break left at the top of a page
        for (List<Line> page : pages) {
            while (!page.isEmpty() && page.get(0).text == FormattedCharSequence.EMPTY) page.remove(0);
        }
    }

    /** View 0 is the title page and the contents; view v shows content pages 2(v-1) and 2(v-1)+1. */
    private int views() {
        return 1 + (pages.size() + 1) / 2;
    }

    private static int viewOfPage(int page) {
        return 1 + page / 2;
    }

    private boolean turn(int dir) {
        return goTo(view + dir);
    }

    private boolean goTo(int target) {
        target = Math.max(0, Math.min(views() - 1, target));
        if (target == view) return false;
        view = target;
        lastView = view;
        if (minecraft != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
        return true;
    }

    // ------------------------------------------------------------------ input

    private double bx(double mx) {
        return (mx - left) / scale;
    }

    private double by(double my) {
        return (my - top) / scale;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A || key == InputConstants.KEY_PAGEUP) {
            turn(-1);
            return true;
        }
        if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D || key == InputConstants.KEY_PAGEDOWN || key == InputConstants.KEY_SPACE) {
            turn(1);
            return true;
        }
        if (key == InputConstants.KEY_HOME || key == InputConstants.KEY_BACKSPACE) {
            goTo(0);
            return true;
        }
        if (key == InputConstants.KEY_END) {
            goTo(views() - 1);
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        turn(delta > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (button != 0) return super.mouseClicked(event, doubleClick);
        double x = bx(mx), y = by(my);
        if (x < 0 || x > W || y < 0 || y > H) return super.mouseClicked(event, doubleClick);
        if (view == 0) {
            int entry = contentsEntryAt(x, y);
            if (entry >= 0) {
                goTo(viewOfPage(chapterStart[entry]));
                return true;
            }
        } else if (overContentsLink(x, y)) {
            goTo(0);
            return true;
        }
        // the arrows sit on their own halves, so a click anywhere on a page turns that way
        turn(x < W / 2.0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null && returnScreen != null) {
            minecraft.gui.setScreen(returnScreen);
            return;
        }
        super.onClose();
    }

    // ------------------------------------------------------------------ geometry

    private static final int CONTENTS_Y = TEXT_Y + 2 * LINE_H + 4;
    private static final int ARROW_Y = H - 24;

    private int contentsEntryAt(double x, double y) {
        if (x < RIGHT_X - 2 || x > RIGHT_X + TEXT_W + 2) return -1;
        int i = (int) Math.floor((y - CONTENTS_Y + 1) / (LINE_H + 1));
        return y >= CONTENTS_Y - 1 && i >= 0 && i < CHAPTERS.size() ? i : -1;
    }

    private boolean overContentsLink(double x, double y) {
        int w = font.width(Component.translatable("guide.nirncraft.contents_link"));
        return x >= 52 && x <= 52 + w && y >= ARROW_Y + 1 && y <= ARROW_Y + 12;
    }

    private boolean overArrow(double x, double y, boolean forward) {
        int ax = forward ? W - 20 - 23 : 20;
        if (forward ? view >= views() - 1 : view <= 0) return false;
        return x >= ax && x < ax + 23 && y >= ARROW_Y && y < ARROW_Y + 13;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        double mx = bx(mouseX), my = by(mouseY);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        com.nirncraft.inventory.client.SkyUi.blit(g, SPREAD, 0, 0, 0, 0, W, H, W, H);
        if (view == 0) {
            drawTitlePage(g);
            drawContents(g, contentsEntryAt(mx, my));
        } else {
            drawPage(g, 2 * (view - 1), LEFT_X);
            drawPage(g, 2 * (view - 1) + 1, RIGHT_X);
            Component link = Component.translatable("guide.nirncraft.contents_link");
            g.text(font, link, 52, ARROW_Y + 3, overContentsLink(mx, my) ? HOVER : FADED, false);
        }
        drawArrow(g, false, overArrow(mx, my, false));
        drawArrow(g, true, overArrow(mx, my, true));
        g.pose().popMatrix();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawArrow(GuiGraphicsExtractor g, boolean forward, boolean hover) {
        if (forward ? view >= views() - 1 : view <= 0) return;
        int ax = forward ? W - 20 - 23 : 20;
        com.nirncraft.inventory.client.SkyUi.blit(g, ARROWS, ax, ARROW_Y, hover ? 23 : 0, forward ? 192 : 205, 23, 13,256,256);
    }

    private void drawPage(GuiGraphicsExtractor g, int index, int x) {
        if (index >= pages.size()) return;
        int y = TEXT_Y;
        for (Line line : pages.get(index)) {
            int lx = line.center ? x + (TEXT_W - font.width(line.text)) / 2 : x + line.indent;
            if (line.bullet) g.text(font, "•", x + 1, y, FADED, false);
            g.text(font, line.text, lx, y, line.color, false);
            y += LINE_H;
        }
        String n = String.valueOf(index + 1);
        g.text(font, n, x + TEXT_W / 2 - font.width(n) / 2, TEXT_Y + LINES * LINE_H + 8, FADED, false);
    }

    private void drawTitlePage(GuiGraphicsExtractor g) {
        int cx = LEFT_X + TEXT_W / 2;
        int y = TEXT_Y + 22;
        float s = 1.5f;
        for (FormattedCharSequence line : font.split(Component.translatable("guide.nirncraft.title"), (int) (TEXT_W / s))) {
            g.pose().pushMatrix();
            g.pose().translate(cx, y);
            g.pose().scale(s, s);
            g.text(font, line, -font.width(line) / 2, 0, HEADING, false);
            g.pose().popMatrix();
            y += 15;
        }
        y += 6;
        ornament(g, cx, y);
        y += 12;
        for (FormattedCharSequence line : font.split(Component.translatable("guide.nirncraft.subtitle")
                .withStyle(Style.EMPTY.withItalic(true)), TEXT_W)) {
            g.text(font, line, cx - font.width(line) / 2, y, INK, false);
            y += LINE_H;
        }
        int by = TEXT_Y + LINES * LINE_H - 22;
        for (FormattedCharSequence line : font.split(Component.translatable("guide.nirncraft.how_to_read"), TEXT_W)) {
            g.text(font, line, cx - font.width(line) / 2, by, FADED, false);
            by += LINE_H;
        }
    }

    private void ornament(GuiGraphicsExtractor g, int cx, int y) {
        g.fill(cx - 34, y, cx - 5, y + 1, FADED);
        g.fill(cx + 6, y, cx + 35, y + 1, FADED);
        g.fill(cx - 1, y - 2, cx + 2, y + 3, HEADING);
        g.fill(cx - 2, y - 1, cx + 3, y + 2, HEADING);
    }

    private void drawContents(GuiGraphicsExtractor g, int hovered) {
        Component heading = Component.translatable("guide.nirncraft.contents").withStyle(Style.EMPTY.withBold(true));
        g.text(font, heading, RIGHT_X + (TEXT_W - font.width(heading)) / 2, TEXT_Y, HEADING, false);
        ornament(g, RIGHT_X + TEXT_W / 2, TEXT_Y + LINE_H + 3);
        for (int i = 0; i < CHAPTERS.size(); i++) {
            int y = CONTENTS_Y + i * (LINE_H + 1);
            int color = i == hovered ? HOVER : INK;
            String num = String.valueOf(chapterStart[i] + 1);
            int numW = font.width(num);
            String title = font.plainSubstrByWidth(I18n.get("guide.nirncraft.chapter." + CHAPTERS.get(i)), TEXT_W - numW - 14);
            g.text(font, title, RIGHT_X, y, color, false);
            g.text(font, num, RIGHT_X + TEXT_W - numW, y, color, false);
            // dotted leader between the title and the page number
            for (int dx = RIGHT_X + font.width(title) + 3; dx < RIGHT_X + TEXT_W - numW - 3; dx += 3) {
                g.fill(dx, y + 6, dx + 1, y + 7, FADED);
            }
        }
    }
}
