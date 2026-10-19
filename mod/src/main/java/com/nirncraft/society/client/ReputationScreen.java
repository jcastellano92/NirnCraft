package com.nirncraft.society.client;

import com.nirncraft.core.SkyData;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.society.Cosmetic;
import com.nirncraft.society.Reputation;
import com.nirncraft.society.SocietyPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Standing with every faction (Hated ... Revered), drawn as a bar from -100 to +100, and the regalia toggle for
 * factions the player belongs to.
 */
public class ReputationScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int INK = 0xFF3A2814;
    private static final int INK_LIGHT = 0xFF6E5638;

    private final List<Cosmetic> available = new ArrayList<>();
    private String chosen = "";
    private Button regalia;
    private int rowH = 15;
    private int x0, y0, x1, y1;

    public ReputationScreen() {
        super(Component.translatable("society.nirncraft.screen.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        available.clear();
        if (mc.player != null) {
            CompoundTag factions = SkyData.get(mc.player).module("quest").getCompoundOrEmpty("factions");
            for (Cosmetic c : Cosmetic.VALUES) if (factions.contains(c.faction)) available.add(c);
            chosen = ClientCosmetics.get(mc.player.getUUID());
        }
        int rows = Reputation.FACTIONS.length;
        rowH = Math.max(11, Math.min(15, (height - 100) / rows));
        int panelH = 34 + rows * rowH + 40;
        x0 = (width - PANEL_W) / 2;
        x1 = x0 + PANEL_W;
        y0 = Math.max(4, (height - panelH) / 2);
        y1 = y0 + panelH;
        regalia = Button.builder(regaliaLabel(), b -> cycle())
                .bounds(x0 + 20, y1 - 30, PANEL_W - 40, 20).build();
        regalia.active = !available.isEmpty();
        addRenderableWidget(regalia);
    }

    private Component regaliaLabel() {
        Cosmetic c = Cosmetic.byId(chosen);
        Component name = c == null ? Component.translatable("society.nirncraft.cosmetic.none") : c.displayName();
        if (available.isEmpty()) return Component.translatable("society.nirncraft.screen.no_regalia");
        return Component.translatable("society.nirncraft.screen.regalia", name);
    }

    private void cycle() {
        if (available.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < available.size(); i++) if (available.get(i).id.equals(chosen)) idx = i;
        idx++;
        chosen = idx >= available.size() ? "" : available.get(idx).id;
        regalia.setMessage(regaliaLabel());
        SkyNetwork.sendToServer(new SocietyPackets.ChooseCosmetic(chosen));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {

        // parchment panel
        g.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, 0xC0000000);
        g.fill(x0 - 3, y0 - 3, x1 + 3, y1 + 3, 0xFF2A1E10);
        g.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF7A5E36);
        g.fillGradient(x0, y0, x1, y1, 0xFFEEE0BC, 0xFFDCC796);
        g.fillGradient(x0, y0, x1, y0 + 6, 0x40704C20, 0x00704C20);

        g.pose().pushMatrix();
        g.pose().translate(width / 2f, y0 + 8);
        g.pose().scale(1.3f, 1.3f);
        Component title = this.title;
        g.text(font, title, -font.width(title) / 2, 0, INK, false);
        g.pose().popMatrix();
        g.fill(x0 + 20, y0 + 24, x1 - 20, y0 + 25, 0x906E5638);

        Minecraft mc = Minecraft.getInstance();
        int barX = x0 + 112;
        int barW = 100;
        Component hover = null;
        for (int i = 0; i < Reputation.FACTIONS.length; i++) {
            String f = Reputation.FACTIONS[i];
            int value = mc.player == null ? 0 : Reputation.get(mc.player, f);
            int y = y0 + 32 + i * rowH;
            g.text(font, Reputation.factionName(f), x0 + 12, y + (rowH - 8) / 2, INK, false);
            // bar: -100 .. +100 with a mark at neutral
            int by = y + rowH / 2 - 3;
            g.fill(barX - 1, by - 1, barX + barW + 1, by + 7, 0xFF5A4630);
            g.fill(barX, by, barX + barW, by + 6, 0xFFBFAE88);
            int mid = barX + barW / 2;
            int pos = barX + Math.round(Reputation.fraction(value) * barW);
            Reputation.Tier tier = Reputation.Tier.of(value);
            int color = 0xFF000000 | tier.color;
            if (pos > mid) g.fill(mid, by, pos, by + 6, color);
            else if (pos < mid) g.fill(pos, by, mid, by + 6, color);
            g.fill(mid, by - 2, mid + 1, by + 8, INK);
            int hostileAt = barX + Math.round(Reputation.fraction(-40) * barW);
            g.fill(hostileAt, by, hostileAt + 1, by + 6, 0x80802020);
            g.text(font, tier.displayName(), barX + barW + 8, y + (rowH - 8) / 2, darker(tier.color), false);
            if (mouseX >= x0 + 8 && mouseX <= x1 - 8 && mouseY >= y && mouseY < y + rowH) {
                hover = Component.translatable("society.nirncraft.screen.value", Reputation.factionName(f), value);
                g.fill(x0 + 8, y, x1 - 8, y + rowH, 0x20A07A3A);
            }
        }
        g.fill(x0 + 20, y1 - 36, x1 - 20, y1 - 35, 0x906E5638);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (hover != null) g.setTooltipForNextFrame(font, hover, mouseX, mouseY);
        if (regalia != null && regalia.isHovered()) {
            g.setTooltipForNextFrame(font, Component.translatable("society.nirncraft.screen.regalia_hint"), mouseX, mouseY);
        }
    }

    private static int darker(int rgb) {
        int r = (int) (((rgb >> 16) & 0xFF) * 0.6f);
        int gr = (int) (((rgb >> 8) & 0xFF) * 0.6f);
        int b = (int) ((rgb & 0xFF) * 0.6f);
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key(), scan = event.keycode(), mods = event.modifiers();
        if (SocietyClient.REPUTATION.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
