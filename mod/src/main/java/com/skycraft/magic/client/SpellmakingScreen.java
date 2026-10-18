package com.skycraft.magic.client;

import com.skycraft.core.Currency;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.spell.School;
import com.skycraft.magic.spell.Tier;
import com.skycraft.magic.spell.spellmaking.CustomSpells;
import com.skycraft.magic.spell.spellmaking.SpellEffectType;
import com.skycraft.magic.spell.spellmaking.SpellEffectType.Range;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Spellmaking Altar screen (Oblivion's spellmaking in Skyrim's menu style). Left: the magic effects you know
 * (click to add). Right: the new spell: its name, up to four effect lines (range, magnitude, duration and area
 * steppers; shift-click steps by ten), and the resulting school, level, magicka cost and gold fee. Fits 427x267.
 */
public class SpellmakingScreen extends Screen {
    private static final int ROW = 13;
    private static final int LINE_H = 30;

    private final BlockPos altar;
    private final List<Line> lines = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private List<SpellEffectType> known = List.of();
    private EditBox nameBox;
    private MagicUi.SkyButton makeButton;
    private int scroll;
    private int listX, listW, specX, specW, top, bottom;

    private static final class Line {
        final SpellEffectType type;
        Range range;
        int mag, dur, area;

        Line(SpellEffectType type) {
            this.type = type;
            this.range = type.defaultRange;
            this.mag = type.hasMagnitude() ? Mth.clamp(25, type.minMag, type.maxMag) : 0;
            this.dur = type.hasDuration() ? Mth.clamp(30, type.minDur, type.maxDur) : 0;
            this.area = 0;
        }

        CustomSpells.Effect effect() {
            return new CustomSpells.Effect(type, range, mag, dur, range == Range.SELF ? 0 : area);
        }
    }

    private record Hit(int x1, int y1, int x2, int y2, Runnable click, Runnable shiftClick) {}

    public SpellmakingScreen(BlockPos altar) {
        super(Component.translatable("screen.skycraft.spellmaking"));
        this.altar = altar;
    }

    /** Starts the screen with these effect lines (autotest previews). */
    public SpellmakingScreen withEffects(SpellEffectType... types) {
        for (SpellEffectType t : types) add(t);
        return this;
    }

    private LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    @Override
    protected void init() {
        listW = Mth.clamp(width * 30 / 100, 110, 170);
        listX = 12;
        specX = listX + listW + 14;
        specW = width - specX - 12;
        top = 30;
        bottom = height - 30;
        LocalPlayer p = player();
        if (p != null) known = CustomSpells.knownEffects(p);
        String oldName = nameBox == null ? "" : nameBox.getValue();
        nameBox = new EditBox(font, specX + 4, top + 4, Math.min(specW - 8, 160), 12, Component.translatable("spellmaking.skycraft.name"));
        nameBox.setMaxLength(CustomSpells.MAX_NAME);
        nameBox.setBordered(false);
        nameBox.setValue(oldName);
        nameBox.setHint(Component.translatable("spellmaking.skycraft.name_hint"));
        addRenderableWidget(nameBox);
        makeButton = addRenderableWidget(new MagicUi.SkyButton(specX + specW - 110, height - 26, 110, 16,
                Component.translatable("spellmaking.skycraft.make"), this::make));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Nullable
    private CustomSpells.Def def() {
        if (lines.isEmpty()) return null;
        List<CustomSpells.Effect> effects = new ArrayList<>();
        for (Line l : lines) effects.add(l.effect());
        return new CustomSpells.Def(nameBox.getValue().trim(), effects);
    }

    private void make() {
        CustomSpells.Def def = def();
        if (def == null || CustomSpells.problem(player(), def) != null) return;
        SkyNetwork.sendToServer(new MagicPackets.MakeSpell(altar, def.id()));
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.ENCHANTMENT_TABLE_USE, 1.2f, 0.8f));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        for (Hit h : hits) {
            if (MagicUi.inside(mx, my, h.x1, h.y1, h.x2, h.y2)) {
                if (com.skycraft.client.screen.UiInput.shiftDown() && h.shiftClick != null) h.shiftClick.run();
                else h.click.run();
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        if (mx < listX + listW) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, known.size() - visibleRows()));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, delta);
    }

    private int visibleRows() {
        return Math.max(1, (bottom - top - 20) / ROW);
    }

    private void add(SpellEffectType type) {
        if (lines.size() >= CustomSpells.MAX_EFFECTS) return;
        for (Line l : lines) {
            if (l.type == type) return;
        }
        lines.add(new Line(type));
    }

    private static Range nextRange(SpellEffectType type, Range r) {
        Range[] all = Range.values();
        for (int i = 1; i <= all.length; i++) {
            Range n = all[(r.ordinal() + i) % all.length];
            if (type.allows(n)) return n;
        }
        return r;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        LocalPlayer p = player();
        if (p == null) return;
        hits.clear();
        g.fill(0, 0, width, height, 0x80000000);
        MagicUi.capsCentered(g, font, title, width / 2, 10, MagicUi.TEXT);
        Component gold = Component.translatable("spellmaking.skycraft.gold", Currency.formatGold(Currency.balance(p)));
        g.text(font, gold, width - 12 - font.width(gold), 10, MagicUi.GOLD, false);

        // ---------------------------------------------------------------- known effects
        MagicUi.panel(g, listX - 4, top - 4, listX + listW + 4, bottom + 4);
        g.text(font, MagicUi.caps(Component.translatable("spellmaking.skycraft.known_effects")), listX, top, MagicUi.HEADER, false);
        int y0 = top + 14;
        if (known.isEmpty()) {
            int y = y0;
            for (var line : font.split(Component.translatable("spellmaking.skycraft.no_effects"), listW)) {
                g.text(font, line, listX, y, MagicUi.DIM, false);
                y += 10;
            }
        }
        int rows = visibleRows();
        for (int i = 0; i < rows && i + scroll < known.size(); i++) {
            SpellEffectType t = known.get(i + scroll);
            int y = y0 + i * ROW;
            boolean used = lines.stream().anyMatch(l -> l.type == t);
            boolean hover = MagicUi.inside(mouseX, mouseY, listX - 2, y - 1, listX + listW + 2, y + ROW - 1);
            if (hover && !used) g.fill(listX - 2, y - 1, listX + listW + 2, y + ROW - 1, 0x30FFFFFF);
            g.fill(listX, y + 3, listX + 3, y + 6, 0xFF000000 | t.school.color);
            String name = MagicUi.ellipsize(font, t.displayName().getString(), listW - 10);
            g.text(font, name, listX + 6, y + 2, used ? MagicUi.DIM : hover ? MagicUi.BRIGHT : MagicUi.TEXT, false);
            if (!used) hits.add(new Hit(listX - 2, y - 1, listX + listW + 2, y + ROW - 1, () -> add(t), null));
        }
        if (known.size() > rows) {
            int barH = Math.max(10, (bottom - y0) * rows / known.size());
            int barY = y0 + (bottom - y0 - barH) * scroll / Math.max(1, known.size() - rows);
            g.fill(listX + listW + 1, barY, listX + listW + 3, barY + barH, 0x80FFFFFF);
        }

        // ---------------------------------------------------------------- the new spell
        MagicUi.panel(g, specX - 4, top - 4, specX + specW + 4, bottom + 4);
        MagicUi.hline(g, specX, specX + Math.min(specW - 8, 160) + 4, top + 16, 0xA0);
        if (nameBox.getValue().isEmpty() && !nameBox.isFocused()) {
            g.text(font, Component.translatable("spellmaking.skycraft.name_hint"), specX + 4, top + 4, MagicUi.DIM, false);
        }
        int y = top + 22;
        if (lines.isEmpty()) {
            for (var line : font.split(Component.translatable("spellmaking.skycraft.help"), specW - 4)) {
                g.text(font, line, specX + 2, y, MagicUi.DIM, false);
                y += 10;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            drawLine(g, l, i, specX, y, specW, mouseX, mouseY);
            y += LINE_H;
        }

        // ---------------------------------------------------------------- summary
        CustomSpells.Def def = def();
        int sy = Math.max(y + 4, bottom - 46);
        if (def != null) {
            float base = CustomSpells.baseCost(def);
            Tier tier = CustomSpells.tierFor(base);
            School school = CustomSpells.mostExpensive(def).type().school;
            int skill = SkyData.get(p).getSkill(school.skill);
            float f = 1f - Math.min(100, skill) / 400f;
            float cost = base * f * f;
            if (Perks.has(p, tier.perkFor(school))) cost *= 0.5f;
            Component sum = Component.translatable("spellmaking.skycraft.summary", school.displayName(), tier.displayName(),
                    Math.round(cost), Math.round(base));
            g.text(font, sum, specX + 2, sy, 0xFF000000 | school.color, false);
            Component fee = Component.translatable("spellmaking.skycraft.fee", CustomSpells.goldCost(def));
            g.text(font, fee, specX + 2, sy + 11, MagicUi.GOLD, false);
            String problem = CustomSpells.problem(p, def);
            if (problem != null) {
                Component pc = problem.endsWith(".skill")
                        ? Component.translatable(problem, school.displayName(), CustomSpells.skillFor(tier)) : Component.translatable(problem);
                int py = sy + 22;
                for (var line : font.split(pc, specW - 4)) {
                    g.text(font, line, specX + 2, py, MagicUi.BAD, false);
                    py += 10;
                }
            }
            makeButton.active = problem == null;
        } else {
            makeButton.active = false;
        }
        Component hint = Component.translatable("spellmaking.skycraft.hint");
        g.text(font, MagicUi.ellipsize(font, hint.getString(), specX + specW - 120 - listX), listX, height - 22, MagicUi.DIM, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawLine(GuiGraphicsExtractor g, Line l, int index, int x, int y, int w, int mouseX, int mouseY) {
        Font font = this.font;
        g.fill(x, y + 2, x + 3, y + 5, 0xFF000000 | l.type.school.color);
        g.text(font, l.type.displayName(), x + 6, y, MagicUi.TEXT, false);
        // remove
        int rx = x + w - 10;
        boolean hotX = MagicUi.inside(mouseX, mouseY, rx - 3, y - 2, rx + 9, y + 10);
        g.text(font, "x", rx, y, hotX ? MagicUi.BAD : MagicUi.DIM, false);
        hits.add(new Hit(rx - 3, y - 2, rx + 9, y + 10, () -> lines.remove(l), null));
        // range toggle
        String range = MagicUi.caps(l.range.displayName());
        int gx = rx - 10 - font.width(range);
        boolean hotR = MagicUi.inside(mouseX, mouseY, gx - 3, y - 2, gx + font.width(range) + 3, y + 10);
        g.text(font, range, gx, y, hotR ? MagicUi.BRIGHT : MagicUi.GOLD, false);
        hits.add(new Hit(gx - 3, y - 2, gx + font.width(range) + 3, y + 10, () -> l.range = nextRange(l.type, l.range), null));
        // steppers
        int sx = x + 6;
        int sy = y + 13;
        if (l.type.hasMagnitude()) {
            sx = stepper(g, sx, sy, Component.translatable("spellmaking.skycraft.magnitude"), l.mag, mouseX, mouseY,
                    d -> l.mag = Mth.clamp(l.mag + d, l.type.minMag, l.type.maxMag), 1, 10);
        }
        if (l.type.hasDuration()) {
            sx = stepper(g, sx, sy, Component.translatable("spellmaking.skycraft.duration"), l.dur, mouseX, mouseY,
                    d -> l.dur = Mth.clamp(l.dur + d, l.type.minDur, l.type.maxDur), 5, 30);
        }
        if (l.type.hasArea() && l.range != Range.SELF) {
            stepper(g, sx, sy, Component.translatable("spellmaking.skycraft.area"), l.area, mouseX, mouseY,
                    d -> l.area = Mth.clamp(l.area + d, 0, SpellEffectType.MAX_AREA), 1, 5);
        }
        String cost = String.valueOf(Math.round(CustomSpells.effectCost(l.effect())));
        g.text(font, cost, x + w - 4 - font.width(cost), sy, MagicUi.MAGICKA, false);
        MagicUi.hline(g, x, x + w, y + LINE_H - 4, 0x40);
    }

    /** "Label ‹ 25 ›": returns the x after it. */
    private int stepper(GuiGraphicsExtractor g, int x, int y, Component label, int value, int mouseX, int mouseY,
                        java.util.function.IntConsumer change, int step, int bigStep) {
        Font font = this.font;
        String lab = label.getString();
        g.text(font, lab, x, y, MagicUi.HEADER, false);
        int ax = x + font.width(lab) + 3;
        boolean hotL = MagicUi.inside(mouseX, mouseY, ax - 2, y - 3, ax + 7, y + 10);
        g.text(font, "<", ax, y, hotL ? MagicUi.BRIGHT : MagicUi.GOLD, false);
        hits.add(new Hit(ax - 2, y - 3, ax + 7, y + 10, () -> change.accept(-step), () -> change.accept(-bigStep)));
        String v = String.valueOf(value);
        int vx = ax + 9;
        g.text(font, v, vx, y, MagicUi.BRIGHT, false);
        int bx = vx + Math.max(font.width("000"), font.width(v)) + 3;
        boolean hotR = MagicUi.inside(mouseX, mouseY, bx - 2, y - 3, bx + 7, y + 10);
        g.text(font, ">", bx, y, hotR ? MagicUi.BRIGHT : MagicUi.GOLD, false);
        hits.add(new Hit(bx - 2, y - 3, bx + 7, y + 10, () -> change.accept(step), () -> change.accept(bigStep)));
        return bx + 14;
    }
}
