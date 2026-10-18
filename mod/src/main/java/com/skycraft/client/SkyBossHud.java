package com.skycraft.client;

import com.skycraft.Skycraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Authentic Skyrim Boss Health Bar:
 * Displays a carved Nordic health gauge for active boss encounters.
 * Fades out smoothly when the player retreats beyond the encounter range (> 32-48 blocks)
 * or when the player enters a sneak HIDDEN state out of combat.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
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
        LivingEntity target = com.skycraft.client.hud.TargetHud.getActiveBoss();
        if (target != null) return target;
        return activeBoss;
    }

    /** True if the bar for this boss name is being drawn by the HUD (its vanilla boss bar is then hidden). */
    public static boolean isShowing(Component name) {
        if (com.skycraft.client.hud.TargetHud.isVisible()) {
            LivingEntity target = com.skycraft.client.hud.TargetHud.getTarget();
            if (target != null && isBossEntity(target)) {
                if (name == null) return true;
                return target.getDisplayName().getString().equals(name.getString());
            }
        }
        if (name == null) return activeBoss != null && currentAlpha > 0.05f;
        return activeBoss != null && currentAlpha > 0.01f && activeBoss.getDisplayName().getString().equals(name.getString());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
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

    public static boolean isBossEntity(LivingEntity e) {
        if (e instanceof EnderDragon || e instanceof WitherBoss) return true;
        if (e.getType().builtInRegistryHolder().is(com.skycraft.combat.CombatHandler.BOSSES)) return true;
        if (e.getMaxHealth() < 80.0f) return false;
        String name = e.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("dragonfly") || name.contains("dartwing") || name.contains("butterfly") || name.contains("insect")) return false;
        // giants, trolls and "giant" beasts are ordinary foes in Skyrim; real bosses carry the #forge:bosses tag
        return name.contains("dragon") || name.contains("deathlord") || name.contains("chief") || name.contains("centurion");
    }

    /** HUD overlay "sky_boss_bar" (registered by {@code visual.client.VisualClientSetup}). */
    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        // Boss health bars are handled uniformly by TargetHud under the compass, matching Skyrim.
        // Doing nothing here guarantees only ONE bar is ever rendered.
    }
}
