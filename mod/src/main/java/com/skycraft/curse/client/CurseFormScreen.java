package com.skycraft.curse.client;

import com.skycraft.client.SkyKeys;
import com.skycraft.curse.CursePackets;
import com.skycraft.curse.CursePerks;
import com.skycraft.curse.CursePowers;
import com.skycraft.curse.Curses;
import com.skycraft.curse.Form;
import com.skycraft.magic.client.MagicUi;
import com.skycraft.network.SkyNetwork;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The magic menu of a transformed player: the werewolf's howls, or the Vampire Lord's night spells (use key while
 * levitating) and night powers (Voice key). Abilities not yet earned by a perk are shown dim with the perk they need.
 * Choosing one equips it; "Revert Form" returns to mortal form.
 */
public class CurseFormScreen extends Screen {
    private final Form form;
    private final List<Entry> entries = new ArrayList<>();
    private Entry hovered;

    private record Entry(String id, String key, int x, int y, int w) {}

    public CurseFormScreen(Form form) {
        super(Component.translatable(form == Form.BEAST ? "curse.skycraft.menu.beast" : "curse.skycraft.menu.lord"));
        this.form = form;
    }

    @Override
    protected void init() {
        entries.clear();
        int colW = Math.min(170, width / 2 - 30);
        if (form == Form.BEAST) {
            int x = width / 2 - colW / 2;
            for (int i = 0; i < CursePowers.HOWLS.size(); i++) entries.add(new Entry(CursePowers.HOWLS.get(i), "howl", x, 64 + i * 16, colW));
        } else {
            int left = width / 2 - colW - 10, right = width / 2 + 10;
            for (int i = 0; i < CursePowers.LORD_SPELLS.size(); i++) entries.add(new Entry(CursePowers.LORD_SPELLS.get(i), "lord_spell", left, 64 + i * 16, colW));
            for (int i = 0; i < CursePowers.LORD_POWERS.size(); i++) entries.add(new Entry(CursePowers.LORD_POWERS.get(i), "lord_power", right, 64 + i * 16, colW));
        }
        addRenderableWidget(new MagicUi.SkyButton(width / 2 - 60, height - 34, 120, 14, Component.translatable("curse.skycraft.menu.revert"), () -> {
            SkyNetwork.sendToServer(CursePackets.Action.of(CursePackets.Action.REVERT));
            onClose();
        }));
    }

    private String selected(String key, String fallback) {
        LocalPlayer p = minecraft.player;
        String id = p == null ? "" : Curses.tag(p).getStringOr(key, "");
        return id.isEmpty() ? fallback : id;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = minecraft.player;
        if (player == null) return;
        g.fillGradient(0, 0, width, height, 0xE0080406, 0xF0180808);
        MagicUi.capsCentered(g, font, title, width / 2, 14, form == Form.BEAST ? 0xFFE8C080 : 0xFFE07078);
        if (form == Form.BEAST) {
            g.centeredText(font, Component.translatable("curse.skycraft.menu.howls", SkyKeys.SHOUT.getTranslatedKeyMessage()), width / 2, 46, 0xFFA89F86);
        } else {
            int colW = Math.min(170, width / 2 - 30);
            g.text(font, Component.translatable("curse.skycraft.menu.spells"), width / 2 - colW - 10, 46, 0xFFA89F86, false);
            g.text(font, Component.translatable("curse.skycraft.menu.powers", SkyKeys.SHOUT.getTranslatedKeyMessage()), width / 2 + 10, 46, 0xFFA89F86, false);
        }
        String howl = selected("howl", CursePowers.HOWL_OF_TERROR);
        String spell = selected("lord_spell", CursePowers.RAISE_DEAD);
        String power = selected("lord_power", CursePowers.BATS);
        hovered = null;
        for (Entry e : entries) {
            boolean has = CursePowers.hasAbility(player, e.id);
            boolean sel = e.id.equals(e.key.equals("howl") ? howl : e.key.equals("lord_spell") ? spell : power);
            boolean hover = mouseX >= e.x && mouseX < e.x + e.w && mouseY >= e.y - 2 && mouseY < e.y + 12;
            if (hover) hovered = e;
            if (hover || sel) g.fill(e.x - 4, e.y - 3, e.x + e.w, e.y + 12, sel ? 0x50802028 : 0x30FFFFFF);
            if (sel) MagicUi.diamond(g, e.x - 1, e.y + 4, 2, 0xFFE8C060);
            g.text(font, CursePowers.name(e.id), e.x + 6, e.y, has ? (sel ? 0xFFFFF0D8 : 0xFFE8E0C8) : 0xFF6A6460, false);
        }
        if (hovered != null) {
            int y = height - 96;
            g.fill(width / 2 - 170, y - 4, width / 2 + 170, height - 42, 0xA0000000);
            List<FormattedCharSequence> lines = font.split(CursePowers.description(hovered.id), 330);
            for (FormattedCharSequence l : lines) {
                g.centeredText(font, l, width / 2, y, 0xFFD8D2C0);
                y += 10;
            }
            if (!CursePowers.hasAbility(player, hovered.id)) {
                CursePerks.CursePerk perk = CursePerks.get(hovered.id.equals(CursePowers.HOWL_ICE_BROTHERS) ? CursePerks.TOTEM_OF_ICE_BROTHERS
                        : hovered.id.equals(CursePowers.HOWL_MOON) ? CursePerks.TOTEM_OF_THE_MOON
                        : hovered.id.equals(CursePowers.HOWL_PREDATOR) ? CursePerks.TOTEM_OF_THE_PREDATOR : hovered.id);
                if (perk != null) g.centeredText(font, Component.translatable("curse.skycraft.menu.needs", perk.displayName()), width / 2, y + 2, 0xFFE07060);
            }
        }
        g.centeredText(font, Component.translatable("curse.skycraft.menu.hint"), width / 2, height - 14, 0xFF807A6A);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = com.skycraft.client.screen.UiInput.actionButton(event.button());
        if (super.mouseClicked(event, doubleClick)) return true;
        LocalPlayer player = minecraft.player;
        if (button == 0 && hovered != null && player != null && CursePowers.hasAbility(player, hovered.id)) {
            if (hovered.id.equals(CursePowers.REVERT)) {
                SkyNetwork.sendToServer(CursePackets.Action.of(CursePackets.Action.REVERT));
                onClose();
                return true;
            }
            SkyNetwork.sendToServer(new CursePackets.Select(hovered.id));
            Curses.tag(player).putString(hovered.key, hovered.id);
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 0.8f));
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
