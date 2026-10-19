package com.nirncraft.quest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Left-side HUD list of party members with health bars (updated by the server once a second). */
public final class PartyHud {
    private PartyHud() {}

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || !ClientQuestData.inParty()) return;
        UUID self = mc.player.getUUID();
        UUID leader = ClientQuestData.leader();
        List<ClientQuestData.Member> others = new ArrayList<>();
        for (ClientQuestData.Member m : ClientQuestData.members()) if (!m.id().equals(self)) others.add(m);
        if (others.isEmpty()) return;
        Font font = mc.font;
        String dim = mc.level == null ? "" : mc.level.dimension().identifier().toString();
        int x = 6;
        int y = height / 2 - others.size() * 10;
        for (ClientQuestData.Member m : others) {
            int nameColor = m.online() ? 0xFFE8E2D0 : 0xFF808080;
            String name = (m.id().equals(leader) ? "★ " : "") + m.name();
            g.text(font, name, x, y, nameColor, true);
            if (!m.online()) {
                g.text(font, Component.translatable("party.nirncraft.offline"), x + font.width(name) + 4, y, 0xFF707070, true);
            } else if (!m.dim().equals(dim)) {
                g.text(font, Component.translatable("party.nirncraft.far"), x + font.width(name) + 4, y, 0xFF909090, true);
            }
            float fill = m.online() && m.maxHealth() > 0 ? Mth.clamp(m.health() / m.maxHealth(), 0, 1) : 0;
            int bw = 64;
            int by = y + 10;
            g.fill(x - 1, by - 1, x + bw + 1, by + 4, 0xC0101010);
            g.fill(x, by, x + bw, by + 3, 0xFF3A0E10);
            int color = fill > 0.5f ? 0xFFB0262A : fill > 0.25f ? 0xFFC8501E : 0xFFE03020;
            g.fill(x, by, x + (int) (bw * fill), by + 3, color);
            g.fill(x, by, x + (int) (bw * fill), by + 1, 0xFFD85A5E);
            y += 20;
        }
    }
}
