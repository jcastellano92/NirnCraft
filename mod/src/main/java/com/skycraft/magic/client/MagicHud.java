package com.skycraft.magic.client;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.spell.Spell;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.SpellMath;
import com.skycraft.magic.spell.Spells;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * Magic HUD: the equipped spell and its magicka cost above the magicka bar (bottom left), the equipped shout's
 * Words of Power and the voice meter above the stamina bar (bottom right), the shout charge indicator, and the
 * words of the last shout heard ("FUS RO DAH").
 */
public final class MagicHud {
    private MagicHud() {}

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options.hideGui || player.isSpectator()) return;
        // curse module: a transformed player's abilities are drawn by CurseHud instead
        if (com.skycraft.curse.Curses.transformed(player)) return;
        Font font = mc.font;
        PlayerData data = SkyData.get(player);

        // ---------------------------------------------------------- hand spells
        // Left hand above the magicka bar (bottom left), right hand above the voice meter (bottom right).
        Spell left = Spells.byId(MagicData.leftSpell(player));
        Spell right = Spells.byId(MagicData.selectedSpell(player));
        Spell leftCast = MagicClientEvents.spellIn(player, SpellCasting.LEFT);
        boolean dual = MagicClientEvents.isCasting(SpellCasting.LEFT) && MagicClientEvents.isCasting(SpellCasting.RIGHT)
                && leftCast != null && leftCast == right && right.dualCastable;
        if (left != null) drawSpell(g, font, player, data, left, 12, height - 40, false, MagicClientEvents.isCasting(SpellCasting.LEFT), dual);
        if (right != null) drawSpell(g, font, player, data, right, width - 12, height - 46, true, MagicClientEvents.isCasting(SpellCasting.RIGHT), dual);
        // empty hands are shown too, so "what do I have equipped" is never a guess
        if (left == null && right == null && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()) {
            g.drawString(font, Component.translatable("magic.skycraft.hud_unarmed", MagicClientEvents.HANDS_CYCLE.getTranslatedKeyMessage()),
                    12, height - 40, 0xFFB8B2A0, true);
        }

        // ---------------------------------------------------------- equipped voice / shout / power (bottom right)
        String voice = MagicData.selectedVoice(player);
        String shoutId = voice.startsWith("shout:") ? voice.substring("shout:".length()) : MagicData.selectedShout(player);
        Shout shout = Shout.byId(shoutId);

        if (voice.startsWith("power:")) {
            Component powerName;
            int powerColor = 0xFFE8C080;
            long ready;
            long total = 24000;
            if (voice.equals("power:racial")) {
                com.skycraft.core.Race race = data.getRace();
                powerName = race != null ? race.powerName() : Component.literal("Power");
                ready = data.getPowerReadyAt();
                if (race != null) total = race.powerCooldown(data.getLevel());
            } else if (voice.startsWith(com.skycraft.curse.CursePowers.PREFIX)) {
                // curse module powers
                String id = voice.substring(com.skycraft.curse.CursePowers.PREFIX.length());
                powerName = com.skycraft.curse.CursePowers.name(id);
                ready = com.skycraft.curse.CursePowers.readyAt(data, id);
                total = com.skycraft.curse.CursePowers.cooldown(id);
                powerColor = 0xFFD04850;
            } else {
                String stoneId = voice.substring("power:stone:".length());
                com.skycraft.lore.StandingStone stone = com.skycraft.lore.StandingStone.byId(stoneId);
                powerName = stone != null ? stone.powerName() : Component.literal("Stone Power");
                ready = data.module("lore").getLong("power_" + stoneId);
                if (stone != null) powerColor = 0xFF000000 | stone.color;
            }
            long now = mc.level.getGameTime();
            boolean isReady = ready <= now;
            float remaining = !isReady && total > 0 ? Mth.clamp((ready - now) / (float) total, 0, 1) : 0;
            int rightEdge = width - 12;
            int y = height - 29;
            int nameW = font.width(powerName);
            int x = rightEdge - nameW;
            g.drawString(font, powerName, x, y, isReady ? powerColor : 0xFF8A9AAA, true);
            // Voice meter
            int meterW = Math.max(40, nameW);
            int mx = rightEdge - meterW;
            int my = y + 10;
            g.fill(mx - 1, my - 1, rightEdge + 1, my + 3, 0xB0101010);
            int fill = (int) (meterW * (1 - remaining));
            g.fill(mx, my, mx + fill, my + 2, isReady ? 0xFFE8C080 : 0xFF6A88A8);
        } else {
            if (shout != null) {
                int learned = MagicData.wordsLearned(player, shout);
                int usable = player.isCreative() ? 3 : MagicData.usableWords(player, shout);
                int charging = MagicClientEvents.shoutHeld >= 0 ? Math.min(Math.max(1, usable), MagicClientEvents.wordsFor(MagicClientEvents.shoutHeld)) : 0;
                long now = mc.level.getGameTime();
                long ready = MagicData.shoutReadyAt(player);
                long total = MagicData.shoutCooldownTotal(player);
                float remaining = ready > now && total > 0 ? Mth.clamp((ready - now) / (float) total, 0, 1) : 0;

                int rightEdge = width - 12;
                int y = height - 29;
                int shown = Math.max(1, Math.max(learned, usable));
                int[] widths = new int[shown];
                int totalWidth = 0;
                for (int i = 0; i < shown; i++) {
                    widths[i] = font.width(shout.word(i));
                    totalWidth += widths[i] + (i > 0 ? 5 : 0);
                }
                int x = rightEdge - totalWidth;
                for (int i = 0; i < shown; i++) {
                    int color;
                    if (i < charging) color = 0xFFFFFFFF;
                    else if (i < usable) color = remaining > 0 ? 0xFF8A9AAA : 0xFFD8E4F0;
                    else color = 0xFF505860;
                    g.drawString(font, shout.word(i), x, y, color, true);
                    x += widths[i] + 5;
                }
                // Voice meter: refills while the shout recharges.
                int meterW = Math.max(40, totalWidth);
                int mx = rightEdge - meterW;
                int my = y + 10;
                g.fill(mx - 1, my - 1, rightEdge + 1, my + 3, 0xB0101010);
                int fill = (int) (meterW * (1 - remaining));
                g.fill(mx, my, mx + fill, my + 2, remaining > 0 ? 0xFF6A88A8 : 0xFFE8F4FF);
            }
        }

        // ---------------------------------------------------------- shout charge (under the crosshair)
        if (MagicClientEvents.shoutHeld >= 0 && shout != null) {
            int usable = player.isCreative() ? 3 : MagicData.usableWords(player, shout);
            int charging = Math.min(Math.max(1, usable), MagicClientEvents.wordsFor(MagicClientEvents.shoutHeld));
            int cy = height / 2 + 14;
            for (int i = 0; i < 3; i++) {
                int cx = width / 2 - 12 + i * 12;
                int col = i < charging ? 0xFFF0F6FF : i < usable ? 0x80A0B0C0 : 0x40404040;
                g.fill(cx - 2, cy, cx + 3, cy + 2, col);
            }
        }

        // ---------------------------------------------------------- the words of the last shout
        if (!ClientFx.shoutText.isEmpty()) {
            float age = (Util.getMillis() - ClientFx.shoutTextStart) / 1800f;
            if (age >= 1f) {
                ClientFx.shoutText = "";
            } else {
                float alpha = Mth.clamp(Math.min(age * 10, (1 - age) * 3), 0, 1);
                int a = (int) (alpha * 255);
                if (a >= 8) {
                    float scale = ClientFx.shoutTextSelf ? 2.6f + age * 0.6f : 1.6f;
                    g.pose().pushPose();
                    g.pose().translate(width / 2f, height * (ClientFx.shoutTextSelf ? 0.28f : 0.2f), 0);
                    g.pose().scale(scale, scale, 1f);
                    g.drawCenteredString(font, ClientFx.shoutText, 0, 0, a << 24 | 0xF2F6FF);
                    g.pose().popPose();
                }
            }
        }
    }

    /** One hand's spell: gem, name and magicka cost; right-aligned for the right hand. */
    private static void drawSpell(GuiGraphics g, Font font, LocalPlayer player, PlayerData data, Spell spell, int anchor, int y,
                                  boolean alignRight, boolean casting, boolean dual) {
        float cost = SpellMath.cost(player, spell) * (dual ? Spell.DUAL_COST : 1f);
        boolean affordable = player.isCreative() || data.getMagicka() >= (spell.isConcentration() ? cost / 5f : cost);
        Component name = spell.displayName();
        String costText = (spell.isConcentration() ? Math.round(cost) + "/s" : String.valueOf(Math.round(cost))) + (dual ? " x2" : "");
        int width = 9 + font.width(name) + 4 + font.width(costText);
        int x = alignRight ? anchor - width : anchor;
        gem(g, x + 3, y + 3, spell.school.color, casting);
        g.drawString(font, name, x + 9, y, casting ? 0xFFFFFFFF : 0xFFE8E2D0, true);
        g.drawString(font, costText, x + 13 + font.width(name), y, affordable ? 0xFF7FB2FF : 0xFFD06060, true);
    }

    /** A small diamond "spell gem" in the school color. */
    private static void gem(GuiGraphics g, int cx, int cy, int rgb, boolean bright) {
        int c = 0xFF000000 | (bright ? lighten(rgb) : rgb);
        g.fill(cx - 1, cy - 3, cx + 1, cy + 3, c);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, c);
        g.fill(cx - 3, cy - 1, cx + 3, cy + 1, c);
        g.fill(cx - 1, cy - 1, cx, cy, 0xFFFFFFFF);
    }

    private static int lighten(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 60);
        int gr = Math.min(255, ((rgb >> 8) & 0xFF) + 60);
        int b = Math.min(255, (rgb & 0xFF) + 60);
        return r << 16 | gr << 8 | b;
    }
}
