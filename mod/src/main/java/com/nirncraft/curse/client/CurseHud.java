package com.nirncraft.curse.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nirncraft.NirnCraft;
import com.nirncraft.client.SkyKeys;
import com.nirncraft.core.HudStack;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.creatures.entity.CorpseEntity;
import com.nirncraft.curse.CurseCombat;
import com.nirncraft.curse.CursePerks;
import com.nirncraft.curse.CursePowers;
import com.nirncraft.curse.Curses;
import com.nirncraft.curse.Form;
import com.nirncraft.curse.Lycanthropy;
import com.nirncraft.curse.Vampirism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The curses on the HUD, kept Skyrim-subtle: a blood drop with four pips for the vampire's stage beside the health
 * bar (glowing hot in sunlight), a moon for beast blood (bright when Beast Form is ready), a thin amber bar over the
 * health bar counting down Beast Form, the form's chosen ability where the Voice meter sits, a "Feed" prompt over a
 * sleeper or a corpse, and the screen's edge tint while transformed or burning in the sun.
 */
public final class CurseHud {
    private static final Identifier VIGNETTE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/curse_vignette.png");
    private static final Identifier ICONS = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/curse_icons.png");

    private CurseHud() {}

    /** Screen-edge tint: amber-red in Beast Form, deep crimson as the Vampire Lord, a hot glare in sunlight. */
    public static void renderTint(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.hud.isHidden()) return;
        Form form = Curses.form(player);
        float r, gr, b, a;
        if (form == Form.BEAST) {
            r = 0.55f; gr = 0.12f; b = 0.02f; a = 0.32f;
        } else if (form == Form.LORD) {
            r = 0.45f; gr = 0.0f; b = 0.06f; a = 0.36f;
        } else if (Curses.isVampire(player) && Vampirism.inSun(player)) {
            float pulse = 0.5f + 0.5f * Mth.sin(player.tickCount * 0.15f);
            r = 1f; gr = 0.75f; b = 0.35f; a = (0.18f + 0.06f * Curses.stage(player)) * (0.7f + 0.3f * pulse);
        } else if ((Curses.ClientView.flags(player) & com.nirncraft.curse.CursePackets.Sync.SIGHT) != 0) {
            r = 0.35f; gr = 0.02f; b = 0.05f; a = 0.3f;
        } else {
            return;
        }
        com.nirncraft.inventory.client.SkyUi.blit(g,VIGNETTE,0,0,0f,0f,width,height,width,height,((int)(a*255)<<24)|((int)(r*255)<<16)|((int)(gr*255)<<8)|(int)(b*255));
    }

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || !(mc.gameMode!=null&&mc.gameMode.hasExperience())) return;
        Font font = mc.font;
        Form form = Curses.form(player);
        long now = mc.level.getGameTime();
        int hx = width / 2 - 70;
        int hy = height - 31;

        // ---------------------------------------------------------- the curse beside the health bar
        int stage = Curses.stage(player);
        if (stage > 0) {
            boolean sun = Vampirism.inSun(player);
            float alpha = sun ? 1f : 0.75f;
            com.nirncraft.inventory.client.SkyUi.blit(g,ICONS,hx-17,hy-4,0f,0f,9,12,64,16,((int)(alpha*255)<<24)|(sun?0xffd999:0xffffff));
                for (int i = 0; i < 4; i++) {
                int col = i < stage ? (stage >= 4 ? 0xFFE02020 : 0xFFA01820) : 0x60302020;
                g.fill(hx - 7, hy + 6 - i * 3, hx - 5, hy + 8 - i * 3, col);
            }
            if(sun) com.nirncraft.inventory.client.SkyUi.blit(g,ICONS,hx-30,hy-3,32f,0f,11,11,64,16);
        } else if (Curses.isWerewolf(player) && form == Form.NONE) {
            boolean ready = CursePowers.readyAt(player, CursePowers.BEAST_FORM) <= now;
            com.nirncraft.inventory.client.SkyUi.blit(g,ICONS,hx-18,hy-3,16f,0f,11,11,64,16,ready?0xe6ffffff:0x59ffffff);
            }

        // ---------------------------------------------------------- Beast Form countdown
        if (form == Form.BEAST) {
            long until = Curses.tag(player).getLongOr("form_until",0L);
            float left = Math.max(0, until - now);
            float total = Math.max(Lycanthropy.DURATION, left);
            float frac = Mth.clamp(left / total, 0, 1);
            int w = 140;
            int y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_FORM_BAR, 5, height) + 1;
            g.fill(hx - 2, y - 1, hx + w + 2, y + 3, 0xA0101010);
            int filled = (int) (w * frac);
            int col = frac < 0.15f && (now / 5) % 2 == 0 ? 0xFFE0C040 : 0xFFC07A28;
            g.fill(hx + (w - filled) / 2, y, hx + (w + filled) / 2, y + 2, col);
        }

        // ---------------------------------------------------------- the form's ability (where the Voice meter is)
        if (form != Form.NONE) {
            String key = form == Form.BEAST ? "howl" : "lord_power";
            String id = Curses.tag(player).getStringOr(key, "");
            if (id.isEmpty() || !CursePowers.hasAbility(player, id)) id = form == Form.BEAST ? CursePowers.HOWL_OF_TERROR : CursePowers.BATS;
            int abilityY = HudStack.reserve(HudStack.Zone.BOTTOM_RIGHT, HudStack.BR_VOICE, 14, height);
            drawAbility(g, font, CursePowers.name(id), CursePowers.readyAt(player, id), CursePowers.cooldown(id), now, width - 12, abilityY, true);
            if (form == Form.LORD) {
                String spell = Curses.tag(player).getStringOr("lord_spell","");
                if (spell.isEmpty() || !CursePowers.hasAbility(player, spell)) spell = CursePowers.RAISE_DEAD;
                Component label = player.getAbilities().flying ? CursePowers.name(spell)
                        : Component.translatable("curse.nirncraft.hud.levitate");
                g.text(font, label, 12, HudStack.reserve(HudStack.Zone.BOTTOM_LEFT, HudStack.BL_LEFT_SPELL, 10, height), 0xFFD06070, true);
            }
            CursePerks.Tree tree = form == Form.BEAST ? CursePerks.Tree.WEREWOLF : CursePerks.Tree.VAMPIRE_LORD;
            if (CursePerks.available(player, tree) > 0) {
                Component hint = Component.translatable("curse.nirncraft.hud.perk", SkyKeys.SKILLS.getTranslatedKeyMessage());
                g.text(font, hint, width - font.width(hint) - 6,
                        HudStack.reserve(HudStack.Zone.TOP_RIGHT, HudStack.TR_CURSE_PERKS, 10, height), 0xFFE8C080, true);
            }
        }

        // ---------------------------------------------------------- feeding prompt
        Component feed = feedPrompt(mc, player, form);
        if (feed != null) g.centeredText(font, feed, width / 2, height / 2 + 14, 0xFFE07070);
    }

    private static void drawAbility(GuiGraphicsExtractor g, Font font, Component name, long ready, long total, long now, int rightEdge, int y, boolean meter) {
        boolean isReady = ready <= now;
        int nameW = font.width(name);
        g.text(font, name, rightEdge - nameW, y, isReady ? 0xFFD04850 : 0xFF8A7A7A, true);
        if (!meter) return;
        int meterW = Math.max(40, nameW);
        int mx = rightEdge - meterW, my = y + 10;
        float remaining = !isReady && total > 0 ? Mth.clamp((ready - now) / (float) total, 0, 1) : 0;
        g.fill(mx - 1, my - 1, rightEdge + 1, my + 3, 0xB0101010);
        g.fill(mx, my, mx + (int) (meterW * (1 - remaining)), my + 2, isReady ? 0xFFC8303A : 0xFF6A3038);
    }

    /** "Feed" when looking at something the curse lets you feed on. */
    private static Component feedPrompt(Minecraft mc, LocalPlayer player, Form form) {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult eh)) return null;
        Entity e = eh.getEntity();
        if (player.distanceToSqr(e) > 4 * 4) return null;
        boolean ok = switch (form) {
            case BEAST -> e instanceof CorpseEntity && !e.getPersistentData().getBooleanOr(Lycanthropy.FED_TAG, false);
            case LORD -> e instanceof LivingEntity l && CurseCombat.isPerson(l) && !player.getAbilities().flying;
            default -> Curses.isVampire(player) && !player.isShiftKeyDown() && e instanceof LivingEntity l && l.isSleeping() && CurseCombat.isPerson(l);
        };
        if (!ok) return null;
        return Component.translatable(form == Form.LORD ? "curse.nirncraft.hud.bite" : "curse.nirncraft.hud.feed", mc.options.keyUse.getTranslatedKeyMessage());
    }

    static PlayerData data(LocalPlayer p) {
        return SkyData.get(p);
    }
}
