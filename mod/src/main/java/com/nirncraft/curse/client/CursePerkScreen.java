package com.nirncraft.curse.client;

import com.nirncraft.curse.CursePackets;
import com.nirncraft.curse.CursePerks;
import com.nirncraft.magic.client.MagicUi;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

/**
 * The Werewolf or Vampire Lord constellation, Dawnguard style: a blood-red (or moonlit amber) sky with the tree's
 * eleven stars. Perks are earned by feeding, not by leveling; the header shows how many feedings the next one needs.
 * Click a reachable star to take it.
 */
public class CursePerkScreen extends Screen {
    private final CursePerks.Tree tree;
    @Nullable
    private final Screen parent;
    private final long[] stars = new long[180];
    private CursePerks.CursePerk hovered;
    private CursePerks.CursePerk pending;
    private MagicUi.SkyButton yes, no;

    public CursePerkScreen(CursePerks.Tree tree, @Nullable Screen parent) {
        super(tree.displayName());
        this.tree = tree;
        this.parent = parent;
        Random r = new Random(tree.ordinal() * 7919L + 11);
        for (int i = 0; i < stars.length; i++) stars[i] = r.nextLong();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        yes = addRenderableWidget(new MagicUi.SkyButton(cx - 62, height - 46, 60, 14, Component.translatable("gui.yes"), this::confirm));
        no = addRenderableWidget(new MagicUi.SkyButton(cx + 2, height - 46, 60, 14, Component.translatable("gui.no"), () -> pending = null));
    }

    private void confirm() {
        if (pending != null) SkyNetwork.sendToServer(new CursePackets.Unlock(pending.id()));
        pending = null;
    }

    @Override
    public void tick() {
        yes.visible = no.visible = pending != null;
    }

    private float scale() {
        return Mth.clamp(Math.min(width / 300f, (height - 70) / 190f), 0.6f, 1.6f);
    }

    private int sx(CursePerks.CursePerk p) {
        return width / 2 + (int) (p.x() * 120 * scale());
    }

    private int sy(CursePerks.CursePerk p) {
        return height / 2 + 6 + (int) ((0.5f - p.y()) * 170 * scale());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;
        boolean lord = tree == CursePerks.Tree.VAMPIRE_LORD;
        g.fillGradient(0, 0, width, height, lord ? 0xFF0A0204 : 0xFF04050A, lord ? 0xFF2A060C : 0xFF1A1408);
        long time = System.currentTimeMillis();
        for (int i = 0; i < stars.length; i++) {
            long s = stars[i];
            int x = (int) Math.floorMod(s, (long) Math.max(1, width));
            int y = (int) Math.floorMod(s >> 20, (long) Math.max(1, height));
            float tw = 0.55f + 0.45f * Mth.sin(time / 700f + i * 1.3f);
            int b = (int) (70 + 110 * tw * ((s >> 40 & 3) + 1) / 4f);
            int rgb = lord ? (Math.min(255, b + 60) << 16 | (b / 2) << 8 | b / 2) : (Math.min(255, b + 40) << 16 | Math.min(255, b + 20) << 8 | b);
            g.fill(x, y, x + 1, y + 1, 0xFF000000 | rgb);
        }
        // a blood moon / the hunter's moon behind the tree
        int mr = (int) (52 * scale());
        int mcx = width / 2, mcy = height / 2 + 6;
        int moon = lord ? 0x801018 : 0x908050;
        // a soft disc: three nested circles, brighter towards the middle
        for (int band = 0; band < 3; band++) {
            int r = mr * (3 - band) / 3;
            int a = 6 + band * 5;
            for (int dy = -r; dy <= r; dy++) {
                int half = (int) Math.sqrt((double) r * r - (double) dy * dy);
                g.fill(mcx - half, mcy + dy, mcx + half, mcy + dy + 1, a << 24 | moon);
            }
        }

        List<CursePerks.CursePerk> perks = CursePerks.of(tree);
        for (CursePerks.CursePerk p : perks) {
            for (String parentId : p.parents()) {
                CursePerks.CursePerk par = CursePerks.get(parentId);
                if (par == null) continue;
                boolean lit = CursePerks.has(player, p.id()) && CursePerks.has(player, par.id());
                line(g, sx(par), sy(par), sx(p), sy(p), lit ? 0xE0E8DCB0 : 0x80705A5A);
            }
        }
        hovered = null;
        int glowOwned = lord ? 0xF06070 : 0xF0D890;
        for (CursePerks.CursePerk p : perks) {
            int x = sx(p), y = sy(p);
            int rank = CursePerks.rank(player, p.id());
            boolean owned = rank > 0;
            boolean reachable = CursePerks.canUnlock(player, p);
            int r = (int) Math.max(2, (owned ? 4 : 3) * scale());
            int core = owned ? 0xFFF8E8 : reachable ? (lord ? 0xFF9CA8 : 0xFFE0A0) : 0x707480;
            if (owned || reachable) g.fill(x - r * 2, y - r * 2, x + r * 2 + 1, y + r * 2 + 1, 0x40000000 | (owned ? glowOwned : 0x806060));
            g.fill(x - r, y, x + r + 1, y + 1, 0xFF000000 | core);
            g.fill(x, y - r, x + 1, y + r + 1, 0xFF000000 | core);
            g.fill(x - r / 2, y - r / 2, x + r / 2 + 1, y + r / 2 + 1, 0xFF000000 | core);
            if (p.maxRank() > 1 && owned) g.text(font, rank + "/" + p.maxRank(), x + 6, y - 3, 0xFFC8BC9A, false);
            if (Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7) hovered = p;
        }

        // header: the tree, feedings and points
        MagicUi.capsCentered(g, font, title, width / 2, 10, lord ? 0xFFE07078 : 0xFFE8C080);
        int feeds = CursePerks.feeds(player, tree);
        int earned = CursePerks.earned(player, tree);
        int next = CursePerks.feedsFor(earned + 1);
        Component status = Component.translatable("curse.nirncraft.tree.status", CursePerks.available(player, tree), feeds, next);
        g.centeredText(font, status, width / 2, 24, 0xFFA89F86);
        // progress to the next perk
        int bw = 120, bx = width / 2 - bw / 2, by = 36;
        int prev = CursePerks.feedsFor(earned);
        float frac = next > prev ? Mth.clamp((feeds - prev) / (float) (next - prev), 0, 1) : 1;
        g.fill(bx - 1, by - 1, bx + bw + 1, by + 3, 0xC0101010);
        g.fill(bx, by, bx + (int) (bw * frac), by + 2, lord ? 0xFFC8303A : 0xFFC89A5A);

        if (hovered != null) tooltip(g, hovered, player);
        if (pending != null) {
            g.fill(width / 2 - 120, height - 66, width / 2 + 120, height - 28, 0xD0101010);
            g.centeredText(font, Component.translatable("curse.nirncraft.tree.confirm", pending.displayName()), width / 2, height - 60, 0xFFE8E0C8);
        } else {
            g.centeredText(font, Component.translatable("curse.nirncraft.tree.hint"), width / 2, height - 14, 0xFF807A6A);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void tooltip(GuiGraphicsExtractor g, CursePerks.CursePerk p, LocalPlayer player) {
        int w = 200;
        int x = Mth.clamp(sx(p) + 12, 4, width - w - 4);
        int y = Mth.clamp(sy(p) - 10, 50, height - 80);
        List<FormattedCharSequence> lines = font.split(p.description(), w - 10);
        int h = 26 + lines.size() * 10;
        g.fill(x, y, x + w, y + h, 0xE0100808);
        g.fill(x, y, x + w, y + 1, 0xFF8A7F66);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF8A7F66);
        Component name = p.maxRank() > 1 ? p.displayName().copy().append(" (" + CursePerks.rank(player, p.id()) + "/" + p.maxRank() + ")") : p.displayName();
        g.text(font, name, x + 5, y + 5, 0xFFF0E6C8, false);
        int ly = y + 18;
        for (FormattedCharSequence l : lines) {
            g.text(font, l, x + 5, ly, 0xFFC8C0A8, false);
            ly += 10;
        }
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (steps == 0 ? 0 : dx * i / steps);
            int y = y0 + (steps == 0 ? 0 : dy * i / steps);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.nirncraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        LocalPlayer player = minecraft.player;
        if (button == 0 && hovered != null && player != null && pending == null
                && (CursePerks.available(player, tree) > 0 || player.isCreative()) && CursePerks.canUnlock(player, hovered)) {
            pending = hovered;
            return true;
        }
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
