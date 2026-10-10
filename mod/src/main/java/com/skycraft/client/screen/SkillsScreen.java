package com.skycraft.client.screen;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.lore.StandingStone;
import com.skycraft.lore.client.Constellations;
import com.skycraft.magic.client.MagicUi;
import com.skycraft.network.CorePackets;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perk;
import com.skycraft.perk.Perks;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * "Look to the heavens": a starfield carousel of every skill constellation (29: Skyrim's 18, Unarmed, Polearms,
 * Mysticism, Acrobatics and the life skills). Each perk is a star; owned perks shine, reachable ones glow blue. The
 * skill's guardian sign is drawn faintly behind its perks. Click a reachable star to spend a perk point. A strip of
 * dots along the bottom (colored by group) jumps straight to any skill. Pending level-ups ask for Health / Magicka /
 * Stamina first, like Skyrim. Rotation eases by real time, so it is equally smooth at any frame rate. Fits 427x267.
 */
public class SkillsScreen extends Screen {
    private static int selected = 0;
    private float scroll = 0;
    private long lastFrame;
    private Perk hovered;
    private int hoveredDot = -1;
    private Perk pendingUnlock;
    private final long[] stars = new long[220];
    private MagicUi.SkyButton yesButton, noButton, legendaryButton;
    private final MagicUi.SkyButton[] levelButtons = new MagicUi.SkyButton[3];

    public SkillsScreen() {
        super(Component.translatable("screen.skycraft.skills"));
        Random r = new Random(1337);
        for (int i = 0; i < stars.length; i++) stars[i] = r.nextLong();
        selected = Math.floorMod(selected, Skill.VALUES.length);
        scroll = selected;
    }

    /** Opens the next Skills screen on this skill (autotest, other screens linking to a skill). */
    public static void preselect(Skill skill) {
        selected = skill.ordinal();
    }

    private PlayerData data() {
        return SkyData.get(minecraft.player);
    }

    @Override
    protected void init() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(com.skycraft.world.WorldSounds.UI_MENU_OPEN.get(), 1.0f, 0.8f));
        }
        int cx = width / 2;
        yesButton = addRenderableWidget(new MagicUi.SkyButton(cx - 62, height - 80, 60, 14, Component.translatable("gui.yes"), this::confirmUnlock));
        noButton = addRenderableWidget(new MagicUi.SkyButton(cx + 2, height - 80, 60, 14, Component.translatable("gui.no"), () -> pendingUnlock = null));
        String[] keys = {"health", "magicka", "stamina"};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            levelButtons[i] = addRenderableWidget(new MagicUi.SkyButton(cx - 135 + i * 90, height / 2 + 20, 80, 16,
                    Component.translatable("stat.skycraft." + keys[i]), () -> SkyNetwork.sendToServer(new CorePackets.ChooseAttribute(which))));
        }
        legendaryButton = addRenderableWidget(new MagicUi.SkyButton(cx - 55, 44, 110, 13, Component.translatable("screen.skycraft.make_legendary"),
                () -> SkyNetwork.sendToServer(new CorePackets.Action(CorePackets.Action.MAKE_LEGENDARY, selected))));
        lastFrame = Util.getMillis();
    }

    @Override
    public void tick() {
        PlayerData data = data();
        boolean levelUp = data.getPendingLevelUps() > 0;
        for (MagicUi.SkyButton b : levelButtons) b.visible = levelUp;
        yesButton.visible = noButton.visible = pendingUnlock != null && !levelUp;
        legendaryButton.visible = !levelUp && data.getSkill(Skill.VALUES[selected]) >= Skill.MAX_LEVEL;
    }

    private void confirmUnlock() {
        if (pendingUnlock != null) SkyNetwork.sendToServer(new CorePackets.UnlockPerk(pendingUnlock.id()));
        pendingUnlock = null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(com.skycraft.world.WorldSounds.UI_MENU_CLOSE.get(), 1.0f, 0.8f));
        }
        super.onClose();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) {
            rotate(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) {
            rotate(1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER && pendingUnlock != null) {
            confirmUnlock();
            return true;
        }
        if (com.skycraft.client.SkyKeys.SKILLS.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        rotate(delta > 0 ? -1 : 1);
        return true;
    }

    private void rotate(int dir) {
        select(selected + dir);
    }

    private void select(int index) {
        int next = Math.floorMod(index, Skill.VALUES.length);
        if (next != selected && minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 1.6f, 0.15f));
        }
        selected = next;
        pendingUnlock = null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (data().getPendingLevelUps() > 0) return false;
        if (hoveredDot >= 0) {
            select(hoveredDot);
            return true;
        }
        if (hovered != null && button == 0) {
            PlayerData data = data();
            if (data.getPerkPoints() > 0 && Perks.canUnlock(data, hovered)) pendingUnlock = hovered;
            return true;
        }
        // click on side constellations to rotate
        if (mx < width * 0.25) rotate(-1);
        else if (mx > width * 0.75) rotate(1);
        return true;
    }

    // ------------------------------------------------------------------ layout

    /** Vertical size of a constellation: 170 px, smaller on short screens (Steam Deck 427x267). */
    private float treeScale() {
        return Mth.clamp((height - 120) / 170f, 0.6f, 1f);
    }

    private int stripY() {
        return height - 42;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        PlayerData data = data();
        // Time-based easing: the carousel settles in ~0.3 s at any frame rate.
        long now = Util.getMillis();
        float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float k = 1f - (float) Math.exp(-dt * 12f);
        float delta = wrapDelta(selected - scroll);
        scroll = Math.abs(delta) < 0.002f ? selected : scroll + delta * k;
        scroll = wrapIndex(scroll);
        drawSky(g, partialTick);

        int cx = width / 2;
        int cy = height / 2 - 2;
        float ts = treeScale();
        hovered = null;
        // neighbors first (behind), then the selected constellation
        for (int off = -2; off <= 2; off++) {
            if (off == 0) continue;
            int idx = Math.floorMod(Math.round(scroll) + off, Skill.VALUES.length);
            float pos = wrapDelta(idx - scroll);
            drawConstellation(g, Skill.VALUES[idx], data, cx + (int) (pos * width * 0.36f), cy + (int) (Math.abs(pos) * 18), 0.45f * ts,
                    Mth.clamp(0.45f - Math.abs(pos) * 0.08f, 0.15f, 0.4f), mouseX, mouseY, false);
        }
        Skill skill = Skill.VALUES[selected];
        float pos = wrapDelta(selected - scroll);
        drawConstellation(g, skill, data, cx + (int) (pos * width * 0.36f), cy, ts, 1f, mouseX, mouseY, Math.abs(pos) < 0.1f);

        drawHeader(g, skill, data);
        drawStrip(g, data, mouseX, mouseY);
        drawFooter(g, data);
        if (hovered != null) drawPerkTooltip(g, hovered, data);
        else if (hoveredDot >= 0) drawDotTooltip(g, Skill.VALUES[hoveredDot], data, mouseX);
        if (pendingUnlock != null && data.getPendingLevelUps() == 0) {
            g.drawCenteredString(font, Component.translatable("screen.skycraft.unlock_confirm", pendingUnlock.displayName()), cx, height - 94, 0xFFF5EBC8);
        }
        if (data.getPendingLevelUps() > 0) drawLevelUp(g, data);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private static float wrapDelta(float d) {
        int n = Skill.VALUES.length;
        while (d > n / 2f) d -= n;
        while (d < -n / 2f) d += n;
        return d;
    }

    private static float wrapIndex(float f) {
        int n = Skill.VALUES.length;
        while (f < 0) f += n;
        while (f >= n) f -= n;
        return f;
    }

    private void drawSky(GuiGraphics g, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xFF02030A, 0xFF0D1426);
        long time = System.currentTimeMillis();
        // the starfield drifts slightly with the carousel
        int drift = (int) (scroll * 9);
        for (int i = 0; i < stars.length; i++) {
            long s = stars[i];
            int x = (int) Math.floorMod(Math.floorMod(s, (long) Math.max(1, width) * 7) / 7 - drift * ((s >> 50 & 1) + 1), Math.max(1, width));
            int y = (int) Math.floorMod(s >> 20, (long) Math.max(1, height) * 5) / 5;
            float tw = 0.55f + 0.45f * Mth.sin((time / 600f) + i * 1.7f);
            int b = (int) (90 + 120 * tw * ((s >> 40 & 3) + 1) / 4f);
            g.fill(x, y, x + 1, y + 1, 0xFF000000 | b << 16 | b << 8 | Math.min(255, b + 30));
        }
        // faint nebula band
        g.fillGradient(0, height / 3, width, height / 3 + 40, 0x00203060, 0x18304878);
        g.fillGradient(0, height / 3 + 40, width, height / 3 + 80, 0x18304878, 0x00203060);
    }

    private int starX(Perk p, int ox, float scale) {
        return ox + (int) (p.x() * 120 * scale);
    }

    private int starY(Perk p, int oy, float scale) {
        return oy + (int) ((0.5f - p.y()) * 170 * scale);
    }

    /** The skill's guardian sign (Standing Stone constellation), very faint behind its perks. */
    private void drawSign(GuiGraphics g, Skill skill, int ox, int oy, float scale, float alpha) {
        StandingStone sign;
        try {
            sign = StandingStone.valueOf(skill.sign.replace("The ", "").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        int[][] pts = Constellations.stars(sign);
        int[][] lines = Constellations.lines(sign);
        // larger than the perk tree and very faint, so it reads as a figure in the sky behind the stars
        float cell = 210 * scale / 22f;
        int gx = ox - (int) (6 * cell);
        int gy = oy - (int) (11 * cell);
        int a = Math.max(4, (int) (alpha * 26));
        int rgb = sign.color & 0xFFFFFF;
        for (int[] l : lines) {
            int[] p0 = pts[l[0]], p1 = pts[l[1]];
            dashed(g, gx + (int) (p0[0] * cell), gy + (int) (p0[1] * cell), gx + (int) (p1[0] * cell), gy + (int) (p1[1] * cell), a << 24 | rgb);
        }
        for (int[] p : pts) {
            int x = gx + (int) (p[0] * cell), y = gy + (int) (p[1] * cell);
            g.fill(x - 2, y - 2, x + 3, y + 3, (a / 2) << 24 | rgb);
            g.fill(x, y, x + 1, y + 1, Math.min(255, a * 3) << 24 | rgb);
        }
    }

    /** A dotted line (every third pixel), for the faint sign figures. */
    private static void dashed(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i += 3) {
            int x = x0 + dx * i / steps;
            int y = y0 + dy * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawConstellation(GuiGraphics g, Skill skill, PlayerData data, int ox, int oy, float scale, float alpha,
                                   int mouseX, int mouseY, boolean interactive) {
        if (ox < -150 || ox > width + 150) return;
        int a = (int) (alpha * 255);
        drawSign(g, skill, ox, oy, scale, alpha);
        List<Perk> perks = Perks.of(skill);
        // connections
        for (Perk p : perks) {
            for (String parentId : p.parents()) {
                Perk parent = Perks.get(parentId);
                if (parent == null) continue;
                boolean lit = data.hasPerk(p.id()) && data.hasPerk(parent.id());
                int col = lit ? 0xE8DCB0 : 0x5A6A8A;
                line(g, starX(parent, ox, scale), starY(parent, oy, scale), starX(p, ox, scale), starY(p, oy, scale),
                        (int) (a * (lit ? 0.9f : 0.5f)) << 24 | col);
            }
        }
        // stars
        for (Perk p : perks) {
            int x = starX(p, ox, scale);
            int y = starY(p, oy, scale);
            int rank = data.getPerkRank(p.id());
            boolean owned = rank > 0;
            boolean available = Perks.canUnlock(data, p);
            int r = (int) Math.max(1, (owned ? 4 : 3) * Math.max(0.45f, scale));
            int core = owned ? 0xFFF8E8 : available ? 0x9CC4FF : 0x6F7480;
            if (owned || available) {
                int glow = (int) (a * 0.25f) << 24 | (owned ? 0xF0D890 : 0x6090E0);
                g.fill(x - r * 2, y - r * 2, x + r * 2 + 1, y + r * 2 + 1, glow);
            }
            g.fill(x - r, y, x + r + 1, y + 1, a << 24 | core);
            g.fill(x, y - r, x + 1, y + r + 1, a << 24 | core);
            g.fill(x - r / 2, y - r / 2, x + r / 2 + 1, y + r / 2 + 1, a << 24 | core);
            if (interactive && Math.abs(mouseX - x) <= 6 && Math.abs(mouseY - y) <= 6 && mouseY < stripY() - 6) {
                hovered = p;
                g.fill(x - 7, y - 7, x + 8, y - 6, 0xFFF0E6C8);
                g.fill(x - 7, y + 7, x + 8, y + 8, 0xFFF0E6C8);
                g.fill(x - 7, y - 7, x - 6, y + 8, 0xFFF0E6C8);
                g.fill(x + 7, y - 7, x + 8, y + 8, 0xFFF0E6C8);
            }
            if (interactive && p.maxRank() > 1 && owned) {
                g.drawString(font, rank + "/" + p.maxRank(), x + 6, y - 3, 0xFFC8BC9A, false);
            }
        }
        // skill name under side constellations
        if (!interactive) {
            g.drawCenteredString(font, skill.displayName(), ox, oy + (int) (100 * scale) + 6, a << 24 | 0xA8A090);
        }
    }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = x1 - x0, dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i += 1) {
            int x = x0 + dx * i / steps;
            int y = y0 + dy * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawHeader(GuiGraphics g, Skill skill, PlayerData data) {
        int cx = width / 2;
        int level = data.getSkill(skill);
        g.pose().pushPose();
        g.pose().translate(cx, 12, 0);
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawCenteredString(font, skill.displayName().copy().append("  " + level), 0, 0, 0xFFF5EBC8);
        g.pose().popPose();
        int barW = 140;
        g.fill(cx - barW / 2 - 1, 30, cx + barW / 2 + 1, 35, 0xFF101010);
        g.fill(cx - barW / 2, 31, cx - barW / 2 + (int) (barW * data.skillProgress(skill)), 34, 0xFFD8D0B8);
        int legendary = data.getLegendary(skill);
        if (legendary > 0) {
            g.drawCenteredString(font, Component.translatable("screen.skycraft.legendary_count", legendary), cx, 37, 0xFFE0B040);
        }
        Component group = Component.translatable("skillgroup.skycraft." + skill.group.name().toLowerCase(Locale.ROOT));
        g.drawString(font, group, 10, 10, 0xFF000000 | skill.group.color, true);
        g.drawString(font, skill.sign, 10, 21, 0xFF8A8478, false);
        String count = (selected + 1) + " / " + Skill.VALUES.length;
        g.drawString(font, count, width - 10 - font.width(count), 10, 0xFF8A8478, false);
    }

    /** One dot per skill, grouped by color; the selected one is a gold diamond. Hover for the name, click to jump. */
    private void drawStrip(GuiGraphics g, PlayerData data, int mouseX, int mouseY) {
        int n = Skill.VALUES.length;
        int spacing = Mth.clamp((width - 40) / (n + 3), 8, 14);
        // a small gap between groups
        int groups = 0;
        for (int i = 1; i < n; i++) if (Skill.VALUES[i].group != Skill.VALUES[i - 1].group) groups++;
        int total = (n - 1) * spacing + groups * spacing / 2;
        int x = width / 2 - total / 2;
        int y = stripY();
        hoveredDot = -1;
        for (int i = 0; i < n; i++) {
            if (i > 0 && Skill.VALUES[i].group != Skill.VALUES[i - 1].group) x += spacing / 2;
            Skill s = Skill.VALUES[i];
            int color = 0xFF000000 | s.group.color;
            boolean hot = mouseY >= y - 6 && mouseY <= y + 6 && Math.abs(mouseX - x) <= spacing / 2 && data.getPendingLevelUps() == 0;
            if (hot) hoveredDot = i;
            if (i == selected) {
                MagicUi.diamond(g, x, y, 3, 0xFFF0D890);
            } else {
                boolean perkable = false;
                if (data.getPerkPoints() > 0) {
                    for (Perk p : Perks.of(s)) {
                        if (Perks.canUnlock(data, p)) {
                            perkable = true;
                            break;
                        }
                    }
                }
                int r = hot ? 2 : 1;
                g.fill(x - r, y - r, x + r + 1, y + r + 1, hot ? 0xFFFFFFFF : color);
                if (perkable) g.fill(x, y - 5, x + 1, y - 3, 0xFF9CC4FF);
            }
            x += spacing;
        }
    }

    private void drawDotTooltip(GuiGraphics g, Skill skill, PlayerData data, int mouseX) {
        Component t = skill.displayName().copy().append(" " + data.getSkill(skill));
        int w = font.width(t) + 8;
        int x = Mth.clamp(mouseX - w / 2, 4, width - w - 4);
        int y = stripY() - 20;
        g.fill(x, y, x + w, y + 12, 0xD0000000);
        g.drawString(font, t, x + 4, y + 2, 0xFF000000 | skill.group.color, false);
    }

    private void drawFooter(GuiGraphics g, PlayerData data) {
        int y = height - 26;
        g.fillGradient(0, y - 8, width, height, 0x00000000, 0xC0000000);
        int cx = width / 2;
        int half = Math.min(150, width / 2 - 8);
        Component lvl = Component.translatable("screen.skycraft.level", data.getLevel());
        g.drawString(font, lvl, cx - half, y, 0xFFF5EBC8, true);
        int barX = cx - half + font.width(lvl) + 6;
        g.fill(barX - 1, y + 2, barX + 81, y + 7, 0xFF101010);
        g.fill(barX, y + 3, barX + (int) (80 * data.levelProgress()), y + 6, 0xFFD8D0B8);
        Component perks = Component.translatable("screen.skycraft.perks_to_increase", data.getPerkPoints());
        g.drawString(font, perks, cx + half - font.width(perks), y, 0xFFF5EBC8, true);
        String stats = Component.translatable("stat.skycraft.health").getString() + " " + (int) (minecraft.player.getMaxHealth() * 5)
                + "    " + Component.translatable("stat.skycraft.magicka").getString() + " " + (int) data.maxMagicka()
                + "    " + Component.translatable("stat.skycraft.stamina").getString() + " " + (int) data.maxStamina();
        g.drawCenteredString(font, stats, cx, y + 12, 0xFFBDB59E);
    }

    private void drawPerkTooltip(GuiGraphics g, Perk perk, PlayerData data) {
        int x = 12;
        int w = Math.min(220, width / 3 + 20);
        int rank = data.getPerkRank(perk.id());
        List<FormattedCharSequence> desc = font.split(perk.description(), w - 8);
        int h = 36 + desc.size() * 10;
        int y = Math.max(40, stripY() - 12 - h);
        g.fill(x, y, x + w, y + h, 0xD0000000);
        g.fill(x, y, x + w, y + 1, 0xFF8A7F66);
        g.drawString(font, perk.displayName(), x + 4, y + 4, 0xFFF5EBC8, true);
        if (perk.maxRank() > 1) {
            String rk = rank + "/" + perk.maxRank();
            g.drawString(font, rk, x + w - 4 - font.width(rk), y + 4, 0xFFC8BC9A, false);
        }
        int ly = y + 16;
        for (FormattedCharSequence line : desc) {
            g.drawString(font, line, x + 4, ly, 0xFFD8D0B8, false);
            ly += 10;
        }
        if (rank < perk.maxRank()) {
            int req = perk.requiredLevel(rank + 1);
            boolean met = data.getSkill(perk.skill()) >= req;
            g.drawString(font, Component.translatable("screen.skycraft.requires", perk.skill().displayName(), req),
                    x + 4, ly + 4, met ? 0xFF90C090 : 0xFFC07070, false);
        }
    }

    private void drawLevelUp(GuiGraphics g, PlayerData data) {
        int cx = width / 2;
        int cy = height / 2;
        int half = Math.min(150, width / 2 - 6);
        g.fill(cx - half, cy - 40, cx + half, cy + 50, 0xE0000000);
        MagicUi.hline(g, cx - half, cx + half, cy - 40, 0xC0);
        MagicUi.hline(g, cx - half, cx + half, cy + 49, 0xC0);
        g.drawCenteredString(font, Component.translatable("screen.skycraft.level_up_title", data.getLevel()), cx, cy - 30, 0xFFF5EBC8);
        g.drawCenteredString(font, Component.translatable("screen.skycraft.level_up_choose"), cx, cy - 14, 0xFFBDB59E);
        if (data.getPendingLevelUps() > 1) {
            g.drawCenteredString(font, Component.translatable("screen.skycraft.level_up_pending", data.getPendingLevelUps()), cx, cy + 40, 0xFF8A8478);
        }
    }
}
