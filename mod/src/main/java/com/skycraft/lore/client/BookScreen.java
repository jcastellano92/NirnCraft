package com.skycraft.lore.client;

import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBooks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Skyrim-style reading screen. Bound books open as a two-page parchment spread with a title page on the left;
 * letters and notes are a single loose sheet. Turn pages with the arrows, A/D, the arrow keys, the mouse wheel or by
 * clicking the left/right page.
 */
public class BookScreen extends Screen {
    private static final Identifier SPREAD = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/gui/book_spread.png");
    private static final Identifier SHEET = Identifier.fromNamespaceAndPath(Skycraft.MODID, "textures/gui/book_sheet.png");
    private static final int SPREAD_W = 380, SPREAD_H = 220;
    private static final int SHEET_W = 180, SHEET_H = 220;
    private static final int PAGE_TEXT_W = 150, PAGE_LINES = 16;
    private static final int SHEET_TEXT_W = 136, SHEET_LINES = 17;
    private static final int LINE_H = 10;
    private static final int INK = 0xFF3B2A1A;
    private static final int HEADING = 0xFF6E2414;
    private static final int FADED = 0xFF7A6448;

    private record Line(FormattedCharSequence text, boolean center, int indent, int color) {}

    /** The lore book shown (null for a story read off a lectern, sign or barrel: its text is {@link #inline}). */
    @Nullable
    private final LoreBooks.Book book;
    @Nullable
    private final BookTexts.Text inline;
    private final Component label;
    private final boolean sheet;
    @Nullable
    private final Screen returnScreen;
    private List<List<Line>> pages = new ArrayList<>();
    private String author = "";
    private int view;
    private int left, top;
    private PageButton prev, next;

    public BookScreen(LoreBooks.Book book) {
        this(book, null);
    }

    public BookScreen(LoreBooks.Book book, @Nullable Screen returnScreen) {
        super(book.title());
        this.book = book;
        this.inline = null;
        this.label = book.category().displayName();
        this.sheet = book.category().isSheet();
        this.returnScreen = returnScreen;
    }

    /**
     * A story that has no lore-book id: the text a lectern, a clue sign or a note barrel holds, sent by the server.
     * Same typography as a lore book; {@code sheet} = a single loose sheet, else a bound spread with a title page.
     */
    public BookScreen(Component title, String author, List<String> pages, boolean sheet, Component label) {
        this(title, author, pages, sheet, label, null);
    }

    public BookScreen(Component title, String author, List<String> pages, boolean sheet, Component label, @Nullable Screen returnScreen) {
        super(title);
        this.book = null;
        this.inline = new BookTexts.Text(author == null ? "" : author, pages);
        this.label = label;
        this.sheet = sheet;
        this.returnScreen = returnScreen;
    }

    @Override
    protected void init() {
        int w = sheet ? SHEET_W : SPREAD_W;
        int h = sheet ? SHEET_H : SPREAD_H;
        left = (width - w) / 2;
        top = (height - h) / 2;
        BookTexts.Text text = inline != null ? inline : book == null ? null : BookTexts.get(book.id());
        author = text == null ? "" : text.author();
        pages = paginate(text, sheet ? SHEET_TEXT_W : PAGE_TEXT_W, sheet ? SHEET_LINES : PAGE_LINES);
        prev = addRenderableWidget(new PageButton(left + 20, top + h - 24, false, b -> turn(-1, false), true));
        next = addRenderableWidget(new PageButton(left + w - 20 - 23, top + h - 24, true, b -> turn(1, false), true));
        view = Math.max(0, Math.min(view, views() - 1));
        updateButtons();
    }

    // ------------------------------------------------------------------ layout

    private List<List<Line>> paginate(BookTexts.Text text, int textWidth, int linesPerPage) {
        List<List<Line>> out = new ArrayList<>();
        List<Line> cur = new ArrayList<>();
        if (sheet) {
            for (FormattedCharSequence s : font.split(title.copy().withStyle(Style.EMPTY.withBold(true)), textWidth)) {
                cur.add(new Line(s, true, 0, HEADING));
            }
            cur.add(new Line(FormattedCharSequence.EMPTY, false, 0, INK));
        }
        List<String> sections = text == null || text.pages().isEmpty()
                ? List.of(Component.translatable("screen.skycraft.lore.faded").getString())
                : text.pages();
        for (int i = 0; i < sections.size(); i++) {
            if (i > 0 && !cur.isEmpty()) {
                out.add(cur);
                cur = new ArrayList<>();
            }
            for (String raw : sections.get(i).split("\n", -1)) {
                if (raw.isBlank()) {
                    if (!cur.isEmpty() && cur.size() < linesPerPage) cur.add(new Line(FormattedCharSequence.EMPTY, false, 0, INK));
                    continue;
                }
                boolean heading = raw.startsWith("#");
                boolean center = heading || raw.startsWith("^");
                boolean verse = raw.startsWith("~");
                String body = center || verse ? raw.substring(1).trim() : raw;
                Component c = heading ? Component.literal(body).withStyle(Style.EMPTY.withBold(true)) : Component.literal(body);
                // verse: indented, and wrapped continuation lines hang a little further in
                List<FormattedCharSequence> wrapped = font.split(c, verse ? textWidth - 16 : textWidth);
                for (int k = 0; k < wrapped.size(); k++) {
                    if (cur.size() >= linesPerPage) {
                        out.add(cur);
                        cur = new ArrayList<>();
                    }
                    int indent = verse ? (k == 0 ? 6 : 16) : 0;
                    cur.add(new Line(wrapped.get(k), center, indent, heading ? HEADING : INK));
                }
            }
        }
        if (!cur.isEmpty()) out.add(cur);
        if (out.isEmpty()) out.add(new ArrayList<>());
        return out;
    }

    /** Number of views: sheets show one page at a time, books a spread (title page + content pages). */
    private int views() {
        return sheet ? pages.size() : (pages.size() + 2) / 2;
    }

    private boolean turn(int dir, boolean sound) {
        int target = Math.max(0, Math.min(views() - 1, view + dir));
        if (target == view) return false;
        view = target;
        updateButtons();
        if (sound && minecraft != null) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
        return true;
    }

    private void updateButtons() {
        if (prev != null) prev.visible = view > 0;
        if (next != null) next.visible = view < views() - 1;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_A || key == InputConstants.KEY_PAGEUP) {
            turn(-1, true);
            return true;
        }
        if (key == InputConstants.KEY_RIGHT || key == InputConstants.KEY_D || key == InputConstants.KEY_PAGEDOWN || key == InputConstants.KEY_SPACE) {
            turn(1, true);
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
        turn(delta > 0 ? -1 : 1, true);
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        if (button != 0) return false;
        int w = sheet ? SHEET_W : SPREAD_W;
        int h = sheet ? SHEET_H : SPREAD_H;
        if (mx < left || mx > left + w || my < top || my > top + h) return false;
        turn(mx < left + w / 2.0 ? -1 : 1, true);
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

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        if (sheet) {
            com.skycraft.inventory.client.SkyUi.blit(g, SHEET, left, top, 0, 0, SHEET_W, SHEET_H, SHEET_W, SHEET_H);
            drawLines(g, pages.get(view), left + 22, top + 22, SHEET_TEXT_W);
            if (pages.size() > 1) {
                String n = (view + 1) + " / " + pages.size();
                g.text(font, n, left + SHEET_W / 2 - font.width(n) / 2, top + SHEET_H - 20, FADED, false);
            }
        } else {
            com.skycraft.inventory.client.SkyUi.blit(g, SPREAD, left, top, 0, 0, SPREAD_W, SPREAD_H, SPREAD_W, SPREAD_H);
            drawBookPage(g, view * 2, left + 22, top + 20);
            drawBookPage(g, view * 2 + 1, left + 208, top + 20);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawBookPage(GuiGraphicsExtractor g, int index, int x, int y) {
        if (index == 0) {
            drawTitlePage(g, x, y);
            return;
        }
        int content = index - 1;
        if (content >= pages.size()) return;
        drawLines(g, pages.get(content), x, y, PAGE_TEXT_W);
        String n = String.valueOf(index);
        g.text(font, n, x + PAGE_TEXT_W / 2 - font.width(n) / 2, y + PAGE_LINES * LINE_H + 8, FADED, false);
    }

    private void drawLines(GuiGraphicsExtractor g, List<Line> lines, int x, int y, int textWidth) {
        int ly = y;
        for (Line line : lines) {
            int lx = line.center ? x + (textWidth - font.width(line.text)) / 2 : x + line.indent;
            g.text(font, line.text, lx, ly, line.color, false);
            ly += LINE_H;
        }
    }

    private void drawTitlePage(GuiGraphicsExtractor g, int x, int y) {
        int cx = x + PAGE_TEXT_W / 2;
        int yy = y + 24;
        float scale = 1.25f;
        for (FormattedCharSequence line : font.split(title, (int) (PAGE_TEXT_W / scale))) {
            g.pose().pushMatrix();
            g.pose().translate(cx, yy);
            g.pose().scale(scale, scale);
            g.text(font, line, -font.width(line) / 2, 0, HEADING, false);
            g.pose().popMatrix();
            yy += 13;
        }
        // ornament: a rule with a diamond
        yy += 6;
        g.fill(cx - 34, yy, cx - 5, yy + 1, FADED);
        g.fill(cx + 6, yy, cx + 35, yy + 1, FADED);
        g.fill(cx - 1, yy - 2, cx + 2, yy + 3, HEADING);
        g.fill(cx - 2, yy - 1, cx + 3, yy + 2, HEADING);
        yy += 12;
        if (!author.isEmpty()) {
            for (FormattedCharSequence line : font.split(Component.translatable("screen.skycraft.lore.by", author)
                    .withStyle(Style.EMPTY.withItalic(true)), PAGE_TEXT_W)) {
                g.text(font, line, cx - font.width(line) / 2, yy, INK, false);
                yy += LINE_H;
            }
        }
        g.text(font, label, cx - font.width(label) / 2, y + PAGE_LINES * LINE_H - 4, FADED, false);
    }
}
