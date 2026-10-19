package com.nirncraft.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nirncraft.NirnCraft;
import com.nirncraft.visual.VisualConfig;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Skyrim's small bracket crosshair in place of vanilla's cross: the brackets close in over a creature and warm to
 * red over an enemy; hidden while sneaking (the {@link SneakEye} takes its place) and during the kill cam. Vanilla's
 * crosshair stays for the debug screen and spectators, and when the option is off.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class SkyCrosshair {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/gui/hud/crosshair.png");

    private SkyCrosshair() {}

    private static boolean ours(Minecraft mc) {
        if (!VisualConfig.SKY_CROSSHAIR.get() || mc.player == null) return false;
        if (mc.getDebugOverlay().showDebugScreen() || !mc.options.getCameraType().isFirstPerson()) return false;
        return mc.gameMode == null || mc.gameMode.getPlayerMode() != GameType.SPECTATOR;
    }

    @SubscribeEvent
    public static void hideVanilla(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(VanillaGuiLayers.CROSSHAIR) && ours(Minecraft.getInstance())) {
            event.setCanceled(true);
        }
    }

    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.gui.screen() != null || !ours(mc)) return;
        if (mc.player.isCrouching() || com.nirncraft.arsenal.client.KillCamClient.active()) return;
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
        
        com.nirncraft.inventory.client.SkyUi.blit(g, TEXTURE, cx - 8 + 1, cy - 8 + 1, 16, 16, u, 0, 32, 32, 64, 32, net.minecraft.util.ARGB.colorFromFloat(0.5f, 0f, 0f, 0f));
        com.nirncraft.inventory.client.SkyUi.blit(g, TEXTURE, cx - 8, cy - 8, 16, 16, u, 0, 32, 32, 64, 32, net.minecraft.util.ARGB.colorFromFloat(0.9f, r, gr, b));

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
