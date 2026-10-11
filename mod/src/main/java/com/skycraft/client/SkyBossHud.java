package com.skycraft.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Authentic Skyrim Boss Health Bar:
 * Displays a carved Nordic health gauge for active boss encounters.
 * Fades out smoothly when the player retreats beyond the encounter range (> 32-48 blocks)
 * or when the player enters a sneak HIDDEN state out of combat.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SkyBossHud {
    private static final int COLOR_GOLD = 0xFFE8C060;
    private static final int COLOR_HEALTH = 0xFFB22222; // Skyrim deep crimson
    private static final int COLOR_BG = 0xFF141210;

    private static LivingEntity activeBoss = null;
    private static float currentDisplayHealth = 1.0f;
    private static float currentAlpha = 0.0f;
    private static float targetAlpha = 0.0f;
    private static final int SEARCH_INTERVAL = 10;
    private static int searchCooldown = SEARCH_INTERVAL;

    private SkyBossHud() {}

    /** The boss whose bar is shown (or fading), if any. Used by the soundtrack for boss music. */
    @org.jetbrains.annotations.Nullable
    public static LivingEntity currentBoss() {
        return activeBoss;
    }

    /** True if the bar for this boss name is the one this HUD is drawing (its vanilla boss bar is then hidden). */
    public static boolean isShowing(Component name) {
        if (name == null) return activeBoss != null && currentAlpha > 0.05f;
        return activeBoss != null && currentAlpha > 0.01f && activeBoss.getDisplayName().getString().equals(name.getString());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            activeBoss = null;
            currentAlpha = 0.0f;
            return;
        }

        // Find closest boss entity within 50 blocks (the entity search runs at most every 10 ticks)
        boolean lost = activeBoss == null || !activeBoss.isAlive() || activeBoss.isRemoved() || activeBoss.level() != mc.level
                || activeBoss.distanceTo(mc.player) > 52.0f;
        if (lost) activeBoss = null;
        if (lost && ++searchCooldown >= SEARCH_INTERVAL) {
            searchCooldown = 0;
            activeBoss = findNearestBoss(mc);
        }

        if (activeBoss != null && activeBoss.isAlive()) {
            float dist = mc.player.distanceTo(activeBoss);

            // Distance falloff: fully visible at <= 32 blocks, fading to 0 at 48 blocks
            float distAlpha = 1.0f - Mth.clamp((dist - 32.0f) / 16.0f, 0.0f, 1.0f);

            // Stealth falloff: if player is sneaking and HIDDEN, fade bar out
            boolean hidden = mc.player.isCrouching() && (activeBoss instanceof Mob mob ? mob.getTarget() != mc.player : true);
            if (hidden) {
                distAlpha *= 0.35f;
            }

            targetAlpha = distAlpha;
            float targetHealth = activeBoss.getHealth() / activeBoss.getMaxHealth();
            currentDisplayHealth = Mth.lerp(0.12f, currentDisplayHealth, targetHealth);
        } else {
            targetAlpha = 0.0f;
        }

        // Smooth alpha easing
        currentAlpha = Mth.lerp(0.15f, currentAlpha, targetAlpha);
    }

    private static LivingEntity findNearestBoss(Minecraft mc) {
        AABB search = mc.player.getBoundingBox().inflate(48.0);
        LivingEntity closest = null;
        double closestDist = 48.0 * 48.0;

        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, search, LivingEntity::isAlive)) {
            if (e == mc.player) continue;
            if (isBossEntity(e)) {
                double d = mc.player.distanceToSqr(e);
                if (d < closestDist) {
                    closest = e;
                    closestDist = d;
                }
            }
        }
        return closest;
    }

    private static boolean isBossEntity(LivingEntity e) {
        if (e instanceof EnderDragon || e instanceof WitherBoss) return true;
        if (e.getType().is(com.skycraft.combat.CombatHandler.BOSSES)) return true;
        if (e.getMaxHealth() < 80.0f) return false;
        String name = e.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("dragonfly") || name.contains("dartwing") || name.contains("butterfly") || name.contains("insect")) return false;
        // giants, trolls and "giant" beasts are ordinary foes in Skyrim; real bosses carry the #forge:bosses tag
        return name.contains("dragon") || name.contains("deathlord") || name.contains("chief") || name.contains("centurion");
    }

    /** HUD overlay "sky_boss_bar" (registered by {@code visual.client.VisualClientSetup}). */
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        if (currentAlpha <= 0.01f || activeBoss == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;

        if (com.skycraft.visual.VisualConfig.STYLED_BOSS_BAR.get()) {
            renderStyled(g, font, width);
            return;
        }

        int barW = 220;
        int barH = 5;
        int barX = (width - barW) / 2;
        int barY = 34;

        int alphaInt = (int) (currentAlpha * 255.0f);
        int borderCol = (alphaInt << 24) | 0x2A241C;
        int bgCol = (alphaInt << 24) | 0x0A0908;
        int healthCol = (alphaInt << 24) | (COLOR_HEALTH & 0x00FFFFFF);
        int textCol = (alphaInt << 24) | 0xE0E0E0;
        int goldCol = (alphaInt << 24) | (COLOR_GOLD & 0x00FFFFFF);

        // Nordic carved dragon wing ends
        g.drawString(font, "◄══", barX - 22, barY - 2, goldCol, false);
        g.drawString(font, "══►", barX + barW + 4, barY - 2, goldCol, false);

        // Frame and Background
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, borderCol);
        g.fill(barX, barY, barX + barW, barY + barH, bgCol);

        // Health Fill
        int fillW = (int) (barW * Mth.clamp(currentDisplayHealth, 0.0f, 1.0f));
        if (fillW > 0) {
            g.fill(barX, barY, barX + fillW, barY + barH, healthCol);
            // Highlight specular line
            g.fill(barX, barY, barX + fillW, barY + 1, (alphaInt << 24) | 0xFFE08080);
        }

        // Center diamond marker
        g.fill(barX + barW / 2 - 1, barY - 1, barX + barW / 2 + 1, barY + barH + 1, goldCol);

        // Boss Name label
        Component bossName = activeBoss.getDisplayName();
        int nameW = font.width(bossName);
        g.drawString(font, bossName, (width - nameW) / 2, barY - 10, textCol, true);
    }

    /**
     * The Skyrim boss bar: the name in spaced small capitals over an ornament, then a long crimson bar between
     * winged end caps that drains toward the centre, with a pale trail of the last hit.
     */
    private static void renderStyled(GuiGraphics g, Font font, int width) {
        float a = currentAlpha;
        int barW = Math.min(220, width - 90);
        int barX = (width - barW) / 2;
        int barY = 52;
        String name = com.skycraft.inventory.client.SkyUi.caps(activeBoss.getDisplayName());
        com.skycraft.inventory.client.SkyUi.scaledCentered(g, font, name, width / 2f, barY - 15, 0.85f,
                com.skycraft.inventory.client.SkyUi.fade(0xFFF0EAD6, a), true);
        com.skycraft.inventory.client.SkyUi.ornament(g, width / 2, barY - 4, Math.min(80, barW / 2 - 10),
                com.skycraft.inventory.client.SkyUi.fade(0xFFC8BC9A, a * 0.8f));
        com.skycraft.inventory.client.SkyUi.ornateBar(g, barX, barY, barW, 5, 0f, COLOR_HEALTH & 0xFFFFFF, a, true, true);
        float hp = activeBoss.isAlive() ? Mth.clamp(activeBoss.getHealth() / activeBoss.getMaxHealth(), 0f, 1f) : 0f;
        if (trailHealth < currentDisplayHealth) trailHealth = currentDisplayHealth;
        else trailHealth += (currentDisplayHealth - trailHealth) * 0.02f;
        int alphaInt = (int) (a * 255) << 24;
        int trailW = Math.round(barW * trailHealth);
        if (trailW > 0) g.fill(barX + (barW - trailW) / 2, barY, barX + (barW + trailW) / 2, barY + 5, (int) (a * 0xA0) << 24 | 0xE8D8C0);
        int fillW = Math.round(barW * Mth.clamp(Math.min(currentDisplayHealth, hp + 0.02f), 0f, 1f));
        if (fillW > 0) {
            int left = barX + (barW - fillW) / 2;
            g.fill(left, barY, left + fillW, barY + 5, alphaInt | (COLOR_HEALTH & 0xFFFFFF));
            g.fill(left, barY, left + fillW, barY + 1, alphaInt | 0xE07060);
            g.fill(left, barY + 4, left + fillW, barY + 5, alphaInt | 0x5A0E10);
        }
    }

    private static float trailHealth = 1.0f;
}
