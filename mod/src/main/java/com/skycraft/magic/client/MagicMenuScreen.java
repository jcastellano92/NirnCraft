package com.skycraft.magic.client;

import com.skycraft.client.SkyKeys;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicPackets;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.School;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellMath;
import com.skycraft.magic.spell.Spells;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Skyrim-style magic menu: categories on the left (Favorites, the five schools, Shouts, Powers), the known spells or
 * shouts of the category in the middle, details on the right. Click to equip, right-click to (un)favorite. The
 * Shouts tab spends dragon souls to unlock learned Words of Power.
 */
public class MagicMenuScreen extends Screen {
    private enum Tab {
        FAVORITES(null, 0xE8D9A0),
        DESTRUCTION(School.DESTRUCTION, School.DESTRUCTION.color),
        RESTORATION(School.RESTORATION, School.RESTORATION.color),
        ALTERATION(School.ALTERATION, School.ALTERATION.color),
        CONJURATION(School.CONJURATION, School.CONJURATION.color),
        ILLUSION(School.ILLUSION, School.ILLUSION.color),
        MYSTICISM(School.MYSTICISM, School.MYSTICISM.color),
        SHOUTS(null, 0xBFD8F0),
        POWERS(null, 0xD0A060);

        final School school;
        final int color;

        Tab(@Nullable School school, int color) {
            this.school = school;
            this.color = color;
        }

        Component label() {
            return school != null ? school.displayName() : Component.translatable("magic.skycraft.tab." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private enum Kind { SPELL, SHOUT, POWER }

    private record Entry(Kind kind, String id, Component name, int color) {}

    private static Tab tab = Tab.DESTRUCTION;
    private static boolean opened;
    private static final int ROW = 14;

    private final List<Entry> entries = new ArrayList<>();
    private int scroll;
    private int cursor = -1;
    @Nullable
    private Entry hovered;
    private Button unlockButton;

    private int tabX, tabW, listX, listW, detailX, detailW, top, bottom;

    public MagicMenuScreen() {
        super(Component.translatable("screen.skycraft.magic"));
    }

    /** Opens the next magic menu on a school's tab. */
    public static void preselect(School school) {
        for (Tab t : Tab.values()) {
            if (t.school == school) tab = t;
        }
        opened = true;
    }

    private LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    @Override
    protected void init() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(com.skycraft.world.WorldSounds.UI_MENU_OPEN.get(), 1.0f, 0.8f));
        }
        tabW = Mth.clamp(width / 5, 80, 120);
        listW = Mth.clamp(width * 32 / 100, 130, 220);
        tabX = 14;
        listX = tabX + tabW + 10;
        detailX = listX + listW + 14;
        detailW = Math.max(110, width - detailX - 14);
        top = 36;
        // the key hint wraps to two lines on narrow screens
        bottom = height - (font.width(Component.translatable("magic.skycraft.menu_hint")) > width - 16 ? 38 : 28);
        unlockButton = addRenderableWidget(Button.builder(Component.translatable("magic.skycraft.unlock_word"), b -> {
            Entry e = detailEntry();
            if (e != null && e.kind == Kind.SHOUT) SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.UNLOCK_WORD, e.id));
        }).bounds(detailX, height - 52, Math.min(detailW, 160), 20).build());
        unlockButton.visible = false;
        // hands: unequip each one (spell, else the held item) or go unarmed (fists)
        int bw = Mth.clamp((width - 40) / 4, 60, 96);
        int bx = width - 14;
        bx -= bw;
        addRenderableWidget(Button.builder(Component.translatable("magic.skycraft.unarmed"), b ->
                handsAction(MagicPackets.MenuAction.UNARMED)).bounds(bx, 8, bw, 18).build());
        bx -= bw + 4;
        addRenderableWidget(Button.builder(Component.translatable("magic.skycraft.unequip_left"), b ->
                handsAction(MagicPackets.MenuAction.UNEQUIP_LEFT)).bounds(bx, 8, bw, 18).build());
        bx -= bw + 4;
        addRenderableWidget(Button.builder(Component.translatable("magic.skycraft.unequip_right"), b ->
                handsAction(MagicPackets.MenuAction.UNEQUIP_RIGHT)).bounds(bx, 8, bw, 18).build());
        if (!opened) {
            // First time: start on the school of the equipped spell.
            opened = true;
            Spell equipped = Spells.byId(MagicData.selectedSpell(player()));
            if (equipped != null) {
                for (Tab t : Tab.values()) {
                    if (t.school == equipped.school) tab = t;
                }
            }
        }
        rebuild();
        if (cursor < 0) selectEquipped();
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

    // ------------------------------------------------------------------ data

    private void rebuild() {
        entries.clear();
        LocalPlayer p = player();
        if (p == null) return;
        PlayerData data = SkyData.get(p);
        List<String> known = MagicData.knownSpells(p);
        switch (tab) {
            case FAVORITES -> {
                for (String id : MagicData.favorites(p)) {
                    Spell s = Spells.byId(id);
                    if (s != null && known.contains(id)) entries.add(spellEntry(s));
                    Shout sh = Shout.byId(id);
                    if (sh != null && MagicData.wordsLearned(p, sh) > 0) entries.add(new Entry(Kind.SHOUT, id, sh.displayName(), 0xBFD8F0));
                    if ("power:racial".equals(id)) {
                        Race race = data.getRace();
                        if (race != null) entries.add(new Entry(Kind.POWER, "power:racial", race.powerName(), 0xD0A060));
                    } else if (id.startsWith("power:stone:")) {
                        com.skycraft.lore.StandingStone stone = com.skycraft.lore.StandingStone.byId(id.substring("power:stone:".length()));
                        if (stone != null && stone.hasPower) entries.add(new Entry(Kind.POWER, id, stone.powerName(), stone.color));
                    } else if (id.startsWith(com.skycraft.curse.CursePowers.PREFIX) && MagicData.hasPower(p, id)) {
                        entries.add(new Entry(Kind.POWER, id, com.skycraft.curse.CursePowers.name(id.substring(com.skycraft.curse.CursePowers.PREFIX.length())), 0xC8303A));
                    }
                }
            }
            case SHOUTS -> {
                for (Shout sh : Shout.VALUES) {
                    if (MagicData.wordsLearned(p, sh) > 0 || p.isCreative()) entries.add(new Entry(Kind.SHOUT, sh.id(), sh.displayName(), 0xBFD8F0));
                }
            }
            case POWERS -> {
                Race race = data.getRace();
                if (race != null) entries.add(new Entry(Kind.POWER, "power:racial", race.powerName(), 0xD0A060));
                com.skycraft.lore.StandingStone stone = com.skycraft.lore.StandingStones.current(p);
                if (stone != null && stone.hasPower) {
                    entries.add(new Entry(Kind.POWER, "power:stone:" + stone.id(), stone.powerName(), stone.color));
                }
                // curse module: Beast Form, the Vampire Lord, the vampire's powers
                for (String id : com.skycraft.curse.CursePowers.powers(p)) {
                    entries.add(new Entry(Kind.POWER, com.skycraft.curse.CursePowers.PREFIX + id, com.skycraft.curse.CursePowers.name(id), 0xC8303A));
                }
            }
            default -> {
                for (Spell s : Spells.of(tab.school)) {
                    if (known.contains(s.id) || p.isCreative()) entries.add(spellEntry(s));
                }
                // spells made at a Spellmaking Altar, under their school
                for (String id : known) {
                    if (!com.skycraft.magic.spell.spellmaking.CustomSpells.isCustom(id)) continue;
                    Spell s = Spells.byId(id);
                    if (s != null && s.school == tab.school) entries.add(spellEntry(s));
                }
            }
        }
        scroll = Mth.clamp(scroll, 0, Math.max(0, entries.size() - visibleRows()));
        if (cursor >= entries.size()) cursor = entries.size() - 1;
    }

    private Entry spellEntry(Spell s) {
        return new Entry(Kind.SPELL, s.id, s.displayName(), s.school.color);
    }

    private int visibleRows() {
        return Math.max(1, (bottom - top - 16) / ROW);
    }

    private void selectEquipped() {
        LocalPlayer p = player();
        if (p == null) return;
        String equipped = MagicData.selectedSpell(p);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(equipped)) {
                cursor = i;
                return;
            }
        }
    }

    @Nullable
    private Entry detailEntry() {
        if (hovered != null) return hovered;
        if (cursor >= 0 && cursor < entries.size()) return entries.get(cursor);
        return null;
    }

    private boolean isEquipped(Entry e) {
        LocalPlayer p = player();
        String voice = MagicData.selectedVoice(p);
        return switch (e.kind) {
            case SPELL -> e.id.equals(MagicData.selectedSpell(p)) || e.id.equals(MagicData.leftSpell(p));
            case SHOUT -> ("shout:" + e.id).equals(voice) || e.id.equals(MagicData.selectedShout(p));
            case POWER -> e.id.equals(voice);
        };
    }

    /** Skyrim style: left click equips the right hand / voice slot, right click the left hand. */
    private void equip(Entry e, boolean leftHand) {
        switch (e.kind) {
            case SPELL -> SkyNetwork.sendToServer(new MagicPackets.MenuAction(
                    leftHand ? MagicPackets.MenuAction.SELECT_LEFT : MagicPackets.MenuAction.SELECT_SPELL, e.id));
            case SHOUT -> SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.SELECT_SHOUT, e.id));
            case POWER -> SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.SELECT_POWER, e.id));
        }
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.4f, 0.6f));
    }

    private void handsAction(int action) {
        SkyNetwork.sendToServer(new MagicPackets.MenuAction(action, ""));
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.5f));
    }

    private void favorite(Entry e) {
        SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.TOGGLE_FAVORITE, e.id));
    }

    @Override
    public void tick() {
        // The server's answer (equip, unlock, favorite) arrives as a data sync; keep the list current.
        Entry keep = cursor >= 0 && cursor < entries.size() ? entries.get(cursor) : null;
        rebuild();
        if (keep != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).id.equals(keep.id)) cursor = i;
            }
        }
    }

    // ------------------------------------------------------------------ input

    private void setTab(Tab t) {
        if (tab == t) return;
        tab = t;
        scroll = 0;
        cursor = -1;
        rebuild();
        if (!entries.isEmpty()) cursor = 0;
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.8f, 0.35f));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int y = top + 4 + i * 18;
            if (mx >= tabX && mx < tabX + tabW && my >= y - 3 && my < y + 13) {
                setTab(tabs[i]);
                return true;
            }
        }
        int row = rowAt(mx, my);
        if (row >= 0) {
            cursor = row;
            Entry e = entries.get(row);
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) favorite(e);
            else equip(e, button == GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            return true;
        }
        return false;
    }

    private int rowAt(double mx, double my) {
        if (mx < listX || mx >= listX + listW) return -1;
        int y0 = top + 4;
        if (my < y0) return -1;
        int idx = (int) ((my - y0) / ROW);
        if (idx >= visibleRows()) return -1;
        idx += scroll;
        return idx < entries.size() ? idx : -1;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= listX && mx < listX + listW) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, entries.size() - visibleRows()));
        } else {
            Tab[] tabs = Tab.values();
            setTab(tabs[Math.floorMod(tab.ordinal() - (int) Math.signum(delta), tabs.length)]);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (SkyKeys.MAGIC_MENU.matches(key, scan)) {
            onClose();
            return true;
        }
        Tab[] tabs = Tab.values();
        switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                setTab(tabs[Math.floorMod(tab.ordinal() - 1, tabs.length)]);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                setTab(tabs[Math.floorMod(tab.ordinal() + 1, tabs.length)]);
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                moveCursor(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                moveCursor(1);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_E -> {
                if (cursor >= 0 && cursor < entries.size()) equip(entries.get(cursor), false);
                return true;
            }
            case GLFW.GLFW_KEY_Q -> {
                if (cursor >= 0 && cursor < entries.size()) equip(entries.get(cursor), true);
                return true;
            }
            case GLFW.GLFW_KEY_F -> {
                if (cursor >= 0 && cursor < entries.size()) favorite(entries.get(cursor));
                return true;
            }
            case GLFW.GLFW_KEY_Z -> {
                handsAction(MagicPackets.MenuAction.UNEQUIP_RIGHT);
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                handsAction(MagicPackets.MenuAction.UNEQUIP_LEFT);
                return true;
            }
            case GLFW.GLFW_KEY_V -> {
                handsAction(MagicPackets.MenuAction.UNARMED);
                return true;
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_DELETE -> {
                // forget a spell you made (tome spells are never forgotten)
                if (cursor >= 0 && cursor < entries.size() && com.skycraft.magic.spell.spellmaking.CustomSpells.isCustom(entries.get(cursor).id)) {
                    SkyNetwork.sendToServer(new MagicPackets.MenuAction(MagicPackets.MenuAction.FORGET_CUSTOM, entries.get(cursor).id));
                }
                return true;
            }
            default -> {
                return super.keyPressed(key, scan, mods);
            }
        }
    }

    private void moveCursor(int dir) {
        if (entries.isEmpty()) return;
        cursor = Mth.clamp(cursor + dir, 0, entries.size() - 1);
        hovered = null;
        if (cursor < scroll) scroll = cursor;
        if (cursor >= scroll + visibleRows()) scroll = cursor - visibleRows() + 1;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        LocalPlayer p = player();
        if (p == null) return;
        Font font = this.font;
        PlayerData data = SkyData.get(p);

        // Frame
        g.fill(0, 0, width, height, 0x90000000);
        g.fill(tabX - 4, top - 4, tabX + tabW + 4, bottom + 4, 0x70101010);
        g.fill(listX - 4, top - 4, listX + listW + 4, bottom + 4, 0x70101010);
        g.fill(detailX - 4, top - 4, detailX + detailW + 4, bottom + 4, 0x70101010);
        g.drawCenteredString(font, Component.translatable("screen.skycraft.magic").withStyle(s -> s.withBold(true)), width / 2, 14, 0xFFF0E8D0);

        // Magicka & souls summary
        String mag = Component.translatable("magic.skycraft.magicka", Math.round(data.getMagicka()), Math.round(data.maxMagicka())).getString();
        g.drawString(font, mag, tabX, 14, 0xFF7FB2FF, true);
        Component souls = Component.translatable("magic.skycraft.dragon_souls", MagicData.dragonSouls(p));
        g.drawString(font, souls, width - 14 - font.width(souls), 14, 0xFFE8C080, true);

        // Tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            int y = top + 4 + i * 18;
            boolean sel = t == tab;
            boolean hover = mouseX >= tabX && mouseX < tabX + tabW && mouseY >= y - 3 && mouseY < y + 13;
            if (sel) g.fill(tabX - 2, y - 3, tabX + tabW + 2, y + 12, 0x30FFFFFF);
            if (sel || hover) g.fill(tabX - 2, y - 3, tabX, y + 12, 0xFF000000 | t.color);
            g.drawString(font, t.label(), tabX + 4, y, sel ? 0xFF000000 | t.color : hover ? 0xFFE8E2D0 : 0xFFA09A8A, true);
        }

        // List
        hovered = null;
        int rows = visibleRows();
        if (entries.isEmpty()) {
            Component empty = Component.translatable(tab == Tab.FAVORITES ? "magic.skycraft.no_favorites"
                    : tab == Tab.SHOUTS ? "magic.skycraft.no_shouts" : tab == Tab.POWERS ? "magic.skycraft.no_powers" : "magic.skycraft.no_spells");
            List<FormattedCharSequence> lines = font.split(empty, listW - 8);
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), listX + 4, top + 6 + i * 10, 0xFF807A6A, false);
        }
        List<String> favorites = MagicData.favorites(p);
        for (int i = 0; i < rows && i + scroll < entries.size(); i++) {
            int idx = i + scroll;
            Entry e = entries.get(idx);
            int y = top + 4 + i * ROW;
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW;
            if (hover) hovered = e;
            if (idx == cursor) g.fill(listX - 2, y - 1, listX + listW + 2, y + ROW - 2, 0x40FFFFFF);
            else if (hover) g.fill(listX - 2, y - 1, listX + listW + 2, y + ROW - 2, 0x20FFFFFF);
            boolean equipped = isEquipped(e) && e.kind != Kind.POWER;
            if (equipped) g.fill(listX, y + 3, listX + 4, y + 7, 0xFF000000 | e.color);
            int nameColor = equipped ? 0xFFFFFFFF : 0xFFD8D2C0;
            // leave room for the cost / word markers on the right
            int nameW = listW - 8 - (e.kind == Kind.SPELL ? 46 : e.kind == Kind.SHOUT ? 40 : 14);
            String name = e.name.getString();
            if (font.width(name) > nameW) name = font.plainSubstrByWidth(name, Math.max(0, nameW - font.width("..."))) + "...";
            g.drawString(font, name, listX + 8, y + 2, nameColor, false);
            if (favorites.contains(e.id)) g.drawString(font, "★", listX + listW - 10, y + 2, 0xFFE8C860, false);
            if (e.kind == Kind.SPELL) {
                Spell s = Spells.byId(e.id);
                if (s != null) {
                    String cost = costText(p, s);
                    int cx = listX + listW - 14 - font.width(cost);
                    g.drawString(font, cost, cx, y + 2, 0xFF7FB2FF, false);
                    // Hand markers: L / R
                    String hands = (e.id.equals(MagicData.leftSpell(p)) ? "L" : "") + (e.id.equals(MagicData.selectedSpell(p)) ? "R" : "");
                    if (!hands.isEmpty()) g.drawString(font, hands, cx - 6 - font.width(hands), y + 2, 0xFF000000 | e.color, false);
                }
            } else if (e.kind == Kind.SHOUT) {
                Shout sh = Shout.byId(e.id);
                if (sh != null) {
                    int learned = MagicData.wordsLearned(p, sh);
                    int unlocked = MagicData.wordsUnlocked(p, sh);
                    for (int w = 0; w < 3; w++) {
                        int cx = listX + listW - 34 + w * 7;
                        int col = w < Math.min(learned, unlocked) ? 0xFFE8F4FF : w < learned ? 0xFF6A7A8A : 0xFF303030;
                        g.fill(cx, y + 4, cx + 4, y + 8, col);
                    }
                }
            }
        }
        if (entries.size() > rows) {
            int barH = Math.max(10, (bottom - top) * rows / entries.size());
            int barY = top + (bottom - top - barH) * scroll / Math.max(1, entries.size() - rows);
            g.fill(listX + listW + 1, barY, listX + listW + 3, barY + barH, 0x80FFFFFF);
        }

        // Details
        unlockButton.visible = false;
        Entry d = detailEntry();
        if (d != null) {
            switch (d.kind) {
                case SPELL -> spellDetails(g, font, p, Spells.byId(d.id));
                case SHOUT -> shoutDetails(g, font, p, Shout.byId(d.id));
                case POWER -> powerDetails(g, font, p, data, d);
            }
        }

        Component hint = Component.translatable("magic.skycraft.menu_hint");
        List<FormattedCharSequence> hintLines = font.split(hint, width - 16);
        int hy = height - 8 - 10 * Math.min(2, hintLines.size());
        for (int i = 0; i < Math.min(2, hintLines.size()); i++) {
            g.drawCenteredString(font, hintLines.get(i), width / 2, hy + i * 10, 0xFF807A6A);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private static String costText(LocalPlayer p, Spell s) {
        int c = Math.round(SpellMath.cost(p, s));
        return s.isConcentration() ? c + "/s" : String.valueOf(c);
    }

    private int heading(GuiGraphics g, Font font, Component name, int color) {
        g.pose().pushPose();
        g.pose().translate(detailX, top + 2, 0);
        g.pose().scale(1.5f, 1.5f, 1f);
        g.drawString(font, name, 0, 0, 0xFF000000 | color, true);
        g.pose().popPose();
        g.fill(detailX, top + 18, detailX + detailW, top + 19, 0x60C8BC9A);
        return top + 24;
    }

    private int paragraph(GuiGraphics g, Font font, Component text, int y, int color) {
        for (FormattedCharSequence line : font.split(text, detailW)) {
            g.drawString(font, line, detailX, y, color, false);
            y += 10;
        }
        return y;
    }

    private void spellDetails(GuiGraphics g, Font font, LocalPlayer p, @Nullable Spell s) {
        if (s == null) return;
        int y = heading(g, font, s.displayName(), 0xF0EAD6);
        g.drawString(font, Component.translatable("tooltip.skycraft.spell_school", s.school.displayName(), s.tier.displayName()), detailX, y, 0xFF000000 | s.school.color, false);
        y += 12;
        String key = s.isConcentration() ? "magic.skycraft.cost_per_second" : "magic.skycraft.cost";
        g.drawString(font, Component.translatable(key, Math.round(SpellMath.cost(p, s)), Math.round(s.cost)), detailX, y, 0xFF7FB2FF, false);
        y += 12;
        Component cast = Component.translatable(s.isConcentration() ? "magic.skycraft.cast_concentration"
                : s.chargeTicks > 0 ? "magic.skycraft.cast_charged" : "magic.skycraft.cast_fire_and_forget");
        // wrapped: these lines are wider than the detail panel on small (Steam Deck) screens
        y = paragraph(g, font, cast, y, 0xFFA09A8A) + 2;
        boolean inLeft = s.id.equals(MagicData.leftSpell(p));
        boolean inRight = s.id.equals(MagicData.selectedSpell(p));
        Component hands = Component.translatable(inLeft && inRight ? "magic.skycraft.equipped_both" : inLeft ? "magic.skycraft.equipped_left"
                : inRight ? "magic.skycraft.equipped_right" : "magic.skycraft.equip_hint");
        y = paragraph(g, font, hands, y, inLeft || inRight ? 0xFF90D090 : 0xFF807A6A) + 2;
        if (s.dualCastable) {
            y = paragraph(g, font, Component.translatable("magic.skycraft.dual_hint", Math.round(SpellMath.cost(p, s) * Spell.DUAL_COST)), y, 0xFF8A9AAA) + 2;
        }
        y += 4;
        y = paragraph(g, font, s.description(), y, 0xFFD8D2C0);
        if (s.isCustom()) paragraph(g, font, Component.translatable("magic.skycraft.made_spell_hint"), y + 6, 0xFF807A6A);
    }

    private void shoutDetails(GuiGraphics g, Font font, LocalPlayer p, @Nullable Shout s) {
        if (s == null) return;
        int y = heading(g, font, s.displayName(), 0xDCEBFA);
        int learned = MagicData.wordsLearned(p, s);
        int unlocked = MagicData.wordsUnlocked(p, s);
        for (int i = 0; i < 3; i++) {
            boolean known = i < learned;
            boolean open = i < Math.min(learned, unlocked);
            String word = known ? s.word(i) : "???";
            g.pose().pushPose();
            g.pose().translate(detailX, y, 0);
            g.pose().scale(1.3f, 1.3f, 1f);
            g.drawString(font, word, 0, 0, open ? 0xFFF2F6FF : known ? 0xFF8A9AAA : 0xFF505050, true);
            g.pose().popPose();
            Component tr = known ? s.translation(i) : Component.literal("");
            g.drawString(font, tr, detailX + 52, y + 2, open ? 0xFFD8D2C0 : 0xFF807A6A, false);
            Component state = Component.translatable(open ? "magic.skycraft.word_unlocked" : known ? "magic.skycraft.word_locked" : "magic.skycraft.word_unknown");
            g.drawString(font, state, detailX + detailW - font.width(state), y + 2, open ? 0xFF90D090 : known ? 0xFFE8C080 : 0xFF606060, false);
            y += 15;
        }
        y += 2;
        g.drawString(font, Component.translatable("magic.skycraft.recharge", s.cooldownSeconds(1), s.cooldownSeconds(2), s.cooldownSeconds(3)),
                detailX, y, 0xFF7FB2FF, false);
        y += 14;
        y = paragraph(g, font, s.description(), y, 0xFFD8D2C0);

        if (learned > unlocked) {
            unlockButton.visible = true;
            unlockButton.active = MagicData.dragonSouls(p) > 0;
            unlockButton.setX(detailX);
            unlockButton.setY(Math.min(bottom - 22, y + 6));
            unlockButton.setWidth(Math.min(detailW, 170));
        }
    }

    private void powerDetails(GuiGraphics g, Font font, LocalPlayer p, PlayerData data, Entry entry) {
        if (entry.id.startsWith(com.skycraft.curse.CursePowers.PREFIX)) {
            String id = entry.id.substring(com.skycraft.curse.CursePowers.PREFIX.length());
            int y = heading(g, font, com.skycraft.curse.CursePowers.name(id), 0xC8303A);
            g.drawString(font, Component.translatable(com.skycraft.curse.CursePowers.greater(id) ? "curse.skycraft.power.greater" : "curse.skycraft.power.lesser"),
                    detailX, y, 0xFFB04048, false);
            y += 12;
            long now = p.level().getGameTime();
            long ready = com.skycraft.curse.CursePowers.readyAt(data, id);
            Component status = ready <= now ? Component.translatable("magic.skycraft.power_ready")
                    : Component.translatable("magic.skycraft.power_recharging", (ready - now) / 1000 + 1);
            g.drawString(font, status, detailX, y, ready <= now ? 0xFF90D090 : 0xFFE8C080, false);
            y += 16;
            y = paragraph(g, font, com.skycraft.curse.CursePowers.description(id), y, 0xFFD8D2C0);
            paragraph(g, font, Component.translatable("magic.skycraft.power_hint", SkyKeys.SHOUT.getTranslatedKeyMessage()), y + 6, 0xFF807A6A);
            return;
        }
        if (entry.id.startsWith("power:stone:")) {
            String stoneId = entry.id.substring("power:stone:".length());
            com.skycraft.lore.StandingStone stone = com.skycraft.lore.StandingStone.byId(stoneId);
            if (stone == null) return;
            int y = heading(g, font, stone.powerName(), stone.color);
            g.drawString(font, stone.stoneName(), detailX, y, 0xFF000000 | stone.color, false);
            y += 12;
            long now = p.level().getGameTime();
            long ready = data.module("lore").getLong("power_" + stone.id());
            Component status = ready <= now ? Component.translatable("magic.skycraft.power_ready")
                    : Component.translatable("magic.skycraft.power_recharging", (ready - now) / 1000 + 1);
            g.drawString(font, status, detailX, y, ready <= now ? 0xFF90D090 : 0xFFE8C080, false);
            y += 16;
            y = paragraph(g, font, stone.description(), y, 0xFFD8D2C0);
            paragraph(g, font, Component.translatable("magic.skycraft.power_hint", SkyKeys.SHOUT.getTranslatedKeyMessage()), y + 6, 0xFF807A6A);
            return;
        }
        Race race = data.getRace();
        if (race == null) return;
        int y = heading(g, font, race.powerName(), 0xE8C080);
        g.drawString(font, Component.translatable("magic.skycraft.greater_power", race.displayName()), detailX, y, 0xFFD0A060, false);
        y += 12;
        long now = p.level().getGameTime();
        long ready = data.getPowerReadyAt();
        Component status = ready <= now ? Component.translatable("magic.skycraft.power_ready")
                : Component.translatable("magic.skycraft.power_recharging", (ready - now) / 1000 + 1);
        g.drawString(font, status, detailX, y, ready <= now ? 0xFF90D090 : 0xFFE8C080, false);
        y += 16;
        y = paragraph(g, font, Component.translatable("power.skycraft." + race.power + ".desc"), y, 0xFFD8D2C0);
        paragraph(g, font, Component.translatable("magic.skycraft.power_hint", SkyKeys.SHOUT.getTranslatedKeyMessage()), y + 6, 0xFF807A6A);
    }
}
