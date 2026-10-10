package com.skycraft.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.skycraft.Skycraft;
import com.skycraft.visual.VisualConfig;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Skyrim's small bracket crosshair in place of vanilla's cross: the brackets close in over a creature and warm to
 * red over an enemy; hidden while sneaking (the {@link SneakEye} takes its place) and during the kill cam. Vanilla's
 * crosshair stays for the debug screen and spectators, and when the option is off.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SkyCrosshair {
    private static final ResourceLocation TEXTURE = new ResourceLocation(Skycraft.MODID, "textures/gui/hud/crosshair.png");

    private SkyCrosshair() {}

    private static boolean ours(Minecraft mc) {
        if (!VisualConfig.SKY_CROSSHAIR.get() || mc.player == null) return false;
        if (mc.options.renderDebug || !mc.options.getCameraType().isFirstPerson()) return false;
        return mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.SPECTATOR;
    }

    @SubscribeEvent
    public static void hideVanilla(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id()) && ours(Minecraft.getInstance())) {
            event.setCanceled(true);
        }
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen != null || !ours(mc)) return;
        if (mc.player.isCrouching() || com.skycraft.arsenal.client.KillCamClient.active()) return;
        int cx = width / 2;
        int cy = height / 2;
        boolean onCreature = mc.crosshairPickEntity instanceof LivingEntity le && le.isAlive();
        boolean hostile = onCreature && mc.crosshairPickEntity instanceof Enemy;
        float r = 1f, gr = 1f, b = 1f;
        if (hostile) {
            gr = 0.72f;
            b = 0.62f;
        }
        int u = onCreature ? 32 : 0;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0f, 0f, 0f, 0.5f);
        g.blit(TEXTURE, cx - 8 + 1, cy - 8 + 1, 16, 16, u, 0, 32, 32, 64, 32);
        RenderSystem.setShaderColor(r, gr, b, 0.9f);
        g.blit(TEXTURE, cx - 8, cy - 8, 16, 16, u, 0, 32, 32, 64, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // vanilla's attack-strength indicator, as a thin bar under the brackets
        if (mc.options.attackIndicator().get() == AttackIndicatorStatus.CROSSHAIR) {
            float strength = mc.player.getAttackStrengthScale(0f);
            if (strength < 1f) {
                int w = 14;
                g.fill(cx - w / 2 - 1, cy + 9, cx + w / 2 + 1, cy + 12, 0x90000000);
                g.fill(cx - w / 2, cy + 10, cx - w / 2 + (int) (w * Mth.clamp(strength, 0f, 1f)), cy + 11, 0xE0E8E0C8);
            }
        }
    }
}
