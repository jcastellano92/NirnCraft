package com.skycraft.arsenal.client;

import com.skycraft.arsenal.StaffEnchanting;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.client.MagicUi;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.Spells;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Staff Enchanter (Dragonborn): pick a spell you know; with an Unenchanted Staff and a filled soul gem in your pack,
 * the staff learns to cast it. Left: your spells; right: the selected spell, the requirements and the charges you'd
 * get. Fits 427x267; mouse wheel scrolls; arrows/W/S move, Enter enchants.
 */
public class StaffEnchanterScreen extends Screen {
    private static final int ROW = 13;
    private final BlockPos pos;
    private final List<Spell> spells = new ArrayList<>();
    private int scroll;
    private int cursor;
    private MagicUi.SkyButton enchantButton;
    private int listX, listW, detailX, detailW, top, bottom;

    public StaffEnchanterScreen(BlockPos pos) {
        super(Component.translatable("screen.skycraft.staff_enchanter"));
        this.pos = pos;
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().gui.setScreen(new StaffEnchanterScreen(pos));
    }

    private LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    @Override
    protected void init() {
        listW = Mth.clamp(width * 38 / 100, 120, 200);
        listX = 12;
        detailX = listX + listW + 14;
        detailW = width - detailX - 12;
        top = 30;
        bottom = height - 30;
        spells.clear();
        LocalPlayer p = player();
        if (p != null) {
            for (Spell s : Spells.all()) {
                if (StaffEnchanting.allowed(s) && (MagicData.knows(p, s.id) || p.isCreative())) spells.add(s);
            }
        }
        cursor = Mth.clamp(cursor, 0, Math.max(0, spells.size() - 1));
        enchantButton = addRenderableWidget(new MagicUi.SkyButton(detailX + detailW - 110, height - 26, 110, 16,
                Component.translatable("staff_enchanter.skycraft.enchant"), this::enchant));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Nullable
    private Spell selected() {
        return cursor >= 0 && cursor < spells.size() ? spells.get(cursor) : null;
    }

    private void enchant() {
        Spell s = selected();
        if (s == null || StaffEnchanting.problem(player(), s) != null) return;
        SkyNetwork.sendToServer(new MagicPackets.StaffEnchant(pos, s.id));
    }

    private int visibleRows() {
        return Math.max(1, (bottom - top - 16) / ROW);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        if (mx >= listX && mx < listX + listW && my >= top + 14) {
            int idx = (int) ((my - top - 14) / ROW) + scroll;
            if (idx >= 0 && idx < spells.size() && idx - scroll < visibleRows()) {
                cursor = idx;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double delta) {
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, spells.size() - visibleRows()));
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        switch (key) {
            case InputConstants.KEY_UP, InputConstants.KEY_W -> {
                move(-1);
                return true;
            }
            case InputConstants.KEY_DOWN, InputConstants.KEY_S -> {
                move(1);
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_E -> {
                enchant();
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    private void move(int d) {
        if (spells.isEmpty()) return;
        cursor = Mth.clamp(cursor + d, 0, spells.size() - 1);
        if (cursor < scroll) scroll = cursor;
        if (cursor >= scroll + visibleRows()) scroll = cursor - visibleRows() + 1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        LocalPlayer p = player();
        if (p == null) return;
        g.fill(0, 0, width, height, 0x80000000);
        MagicUi.capsCentered(g, font, title, width / 2, 10, MagicUi.TEXT);

        MagicUi.panel(g, listX - 4, top - 4, listX + listW + 4, bottom + 4);
        g.text(font, MagicUi.caps(Component.translatable("staff_enchanter.skycraft.spells")), listX, top, MagicUi.HEADER, false);
        int y0 = top + 14;
        if (spells.isEmpty()) {
            int y = y0;
            for (FormattedCharSequence line : font.split(Component.translatable("staff_enchanter.skycraft.no_spells"), listW)) {
                g.text(font, line, listX, y, MagicUi.DIM, false);
                y += 10;
            }
        }
        int rows = visibleRows();
        for (int i = 0; i < rows && i + scroll < spells.size(); i++) {
            int idx = i + scroll;
            Spell s = spells.get(idx);
            int y = y0 + i * ROW;
            boolean hover = MagicUi.inside(mouseX, mouseY, listX - 2, y - 1, listX + listW + 2, y + ROW - 1);
            if (idx == cursor) g.fill(listX - 2, y - 1, listX + listW + 2, y + ROW - 1, 0x40FFFFFF);
            else if (hover) g.fill(listX - 2, y - 1, listX + listW + 2, y + ROW - 1, 0x20FFFFFF);
            g.fill(listX, y + 3, listX + 3, y + 6, 0xFF000000 | s.school.color);
            g.text(font, MagicUi.ellipsize(font, s.displayName().getString(), listW - 10), listX + 6, y + 2,
                    idx == cursor ? MagicUi.BRIGHT : MagicUi.TEXT, false);
        }
        if (spells.size() > rows) {
            int barH = Math.max(10, (bottom - y0) * rows / spells.size());
            int barY = y0 + (bottom - y0 - barH) * scroll / Math.max(1, spells.size() - rows);
            g.fill(listX + listW + 1, barY, listX + listW + 3, barY + barH, 0x80FFFFFF);
        }

        MagicUi.panel(g, detailX - 4, top - 4, detailX + detailW + 4, bottom + 4);
        Spell s = selected();
        if (s != null) {
            int y = top;
            g.text(font, s.displayName(), detailX, y, 0xFF000000 | s.school.color, true);
            y += 12;
            g.text(font, Component.translatable("tooltip.skycraft.spell_school", s.school.displayName(), s.tier.displayName()),
                    detailX, y, MagicUi.HEADER, false);
            y += 12;
            for (FormattedCharSequence line : font.split(s.description(), detailW)) {
                g.text(font, line, detailX, y, MagicUi.TEXT, false);
                y += 10;
            }
            y += 6;
            MagicUi.hline(g, detailX, detailX + detailW, y, 0x60);
            y += 6;
            boolean staff = StaffEnchanting.findStaff(p) >= 0;
            int gem = StaffEnchanting.findGem(p);
            g.text(font, Component.translatable("staff_enchanter.skycraft.need_staff"), detailX, y, staff ? MagicUi.GOOD : MagicUi.BAD, false);
            y += 11;
            g.text(font, Component.translatable("staff_enchanter.skycraft.need_gem"), detailX, y, gem >= 0 ? MagicUi.GOOD : MagicUi.BAD, false);
            y += 11;
            if (gem >= 0) {
                int charges = StaffEnchanting.chargesFor(p, StaffEnchanting.soulAt(p, gem));
                g.text(font, Component.translatable("staff_enchanter.skycraft.charges", charges), detailX, y, MagicUi.MAGICKA, false);
            }
            enchantButton.active = StaffEnchanting.problem(p, s) == null;
        } else {
            enchantButton.active = false;
        }
        String hint = MagicUi.ellipsize(font, Component.translatable("staff_enchanter.skycraft.hint").getString(), detailX + detailW - 120 - listX);
        g.text(font, hint, listX, height - 22, MagicUi.DIM, false);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
