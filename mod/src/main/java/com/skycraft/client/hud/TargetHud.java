package com.skycraft.client.hud;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyBossHud;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.visual.VisualConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Skyrim's enemy health bar at the top centre: the creature you are fighting or aiming at (up to 32 blocks, not
 * through walls), its name in small capitals and a red bar that drains toward the centre with a pale trail showing
 * the last hit. Name and trim are coloured by how dangerous it is next to you (weak, even, strong, deadly). Fades
 * out a few seconds after you look away; bosses use {@link SkyBossHud} instead.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class TargetHud {
    private static final double RANGE = 32.0;
    private static final long LINGER_MS = 3500;

    private static LivingEntity target;
    private static long lastSeen;
    private static float shownHealth = 1f;
    private static float trailHealth = 1f;
    private static float alpha;
    private static long lastFrame = Util.getMillis();

    private TargetHud() {}

    /** The enemy bar is on screen (other HUD elements make room for it). */
    public static boolean isVisible() {
        return target != null && alpha > 0.1f;
    }

    /** The local player hit something: show it even if it isn't under the crosshair any more. */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide && event.getEntity() == Minecraft.getInstance().player
                && event.getTarget() instanceof LivingEntity le && worthShowing(le)) {
            select(le);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            target = null;
            return;
        }
        if (target != null && (!target.isAlive() && target.deathTime > 15 || target.level() != mc.level || target.distanceTo(player) > RANGE * 1.5)) {
            target = null;
        }
        if (mc.isPaused() || player.tickCount % 2 != 0) return;
        LivingEntity aimed = mc.crosshairPickEntity instanceof LivingEntity le ? le : farPick(mc, player);
        if (aimed != null && worthShowing(aimed)) select(aimed);
    }

    private static void select(LivingEntity e) {
        if (e != target) {
            target = e;
            shownHealth = trailHealth = health(e);
        }
        lastSeen = Util.getMillis();
    }

    /** A cheap long-range pick along the view (every other tick), stopped by blocks. */
    private static LivingEntity farPick(Minecraft mc, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        Vec3 end = eye.add(look.x * RANGE, look.y * RANGE, look.z * RANGE);
        HitResult block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        double maxSqr = block.getType() == HitResult.Type.MISS ? RANGE * RANGE : block.getLocation().distanceToSqr(eye);
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        AABB box = player.getBoundingBox().expandTowards(stop.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, stop, box,
                e -> e instanceof LivingEntity && !e.isSpectator() && e.isPickable() && e != player, maxSqr);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    private static boolean worthShowing(LivingEntity e) {
        if (e instanceof ArmorStand || e instanceof Player && !e.isAlive()) return false;
        if (e.isInvisible()) return false;
        // creatures that matter in a fight: monsters, anything angry at us, anything we've hurt
        if (e instanceof Enemy || e instanceof Player) return true;
        if (e instanceof Mob mob && mob.getTarget() == Minecraft.getInstance().player) return true;
        return e.getHealth() < e.getMaxHealth() || e == target;
    }

    private static float health(LivingEntity e) {
        return e.getMaxHealth() <= 0 ? 0f : Mth.clamp(e.getHealth() / e.getMaxHealth(), 0f, 1f);
    }

    /** 0 weak, 1 even, 2 strong, 3 deadly: the target's toughness next to the player's. */
    private static int danger(LivingEntity e, Player player) {
        double armor = e.getAttributes().hasAttribute(Attributes.ARMOR) ? e.getAttributeValue(Attributes.ARMOR) : 0;
        double ratio = e.getMaxHealth() * (1.0 + armor / 25.0) / Math.max(1.0, player.getMaxHealth());
        if (ratio < 0.45) return 0;
        if (ratio < 1.6) return 1;
        if (ratio < 3.5) return 2;
        return 3;
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        float dt = Mth.clamp((now - lastFrame) / 1000f, 0f, 0.25f);
        lastFrame = now;
        if (!VisualConfig.TARGET_BAR.get() || mc.options.hideGui || mc.player == null) return;
        LivingEntity e = target;
        boolean visible = e != null && now - lastSeen < LINGER_MS && !(e.isAlive() && SkyBossHud.isShowing(e.getDisplayName()));
        alpha += ((visible ? 1f : 0f) - alpha) * (1f - (float) Math.exp((visible ? -10.0 : -4.0) * dt));
        if (e == null || alpha < 0.02f) return;

        float hp = e.isAlive() ? health(e) : 0f;
        shownHealth += (hp - shownHealth) * (1f - (float) Math.exp(-12.0 * dt));
        // the pale "damage taken" trail waits a moment, then catches up
        if (trailHealth < shownHealth) trailHealth = shownHealth;
        else trailHealth += (shownHealth - trailHealth) * (1f - (float) Math.exp(-2.2 * dt));

        Font font = mc.font;
        int danger = danger(e, mc.player);
        int nameColor = switch (danger) {
            case 0 -> 0xFFB8B4AC;
            case 2 -> 0xFFF0C878;
            case 3 -> 0xFFFF7A6A;
            default -> 0xFFF0EAD6;
        };
        int barColor = switch (danger) {
            case 0 -> 0x9A3A32;
            case 2 -> 0xC0281E;
            case 3 -> 0xA0102A;
            default -> 0xB0262A;
        };
        int w = Math.min(150, width / 3);
        int x = (width - w) / 2;
        int y = SkyBossHud.isShowing(null) ? 76 : 46;

        String name = SkyUi.ellipsize(font, SkyUi.caps(e.getDisplayName()), w + 40);
        SkyUi.scaledCentered(g, font, name, width / 2f, y - 10, 0.75f, SkyUi.fade(nameColor, alpha), true);

        SkyUi.ornateBar(g, x, y, w, 4, 0f, barColor, alpha, true, false);
        int trailW = Math.round(w * Mth.clamp(trailHealth, 0f, 1f));
        int a = (int) (alpha * 255) << 24;
        if (trailW > 0) g.fill(x + (w - trailW) / 2, y, x + (w + trailW) / 2, y + 4, (int) (alpha * 0xB0) << 24 | 0xE8D8C0);
        int fillW = Math.round(w * Mth.clamp(shownHealth, 0f, 1f));
        if (fillW > 0) {
            int left = x + (w - fillW) / 2;
            g.fill(left, y, left + fillW, y + 4, a | barColor);
            g.fill(left, y, left + fillW, y + 1, a | SkyUi.brighten(barColor, 70));
        }
        if (danger == 3) {
            // deadly: a small skull-like mark beside the name
            int sx = width / 2 + font.width(name) * 3 / 8 + 6;
            int sy = y - 10;
            g.fill(sx, sy, sx + 5, sy + 4, SkyUi.fade(0xFFFF7A6A, alpha));
            g.fill(sx + 1, sy + 4, sx + 4, sy + 6, SkyUi.fade(0xFFFF7A6A, alpha));
            g.fill(sx + 1, sy + 1, sx + 2, sy + 2, SkyUi.fade(0xFF200808, alpha));
            g.fill(sx + 3, sy + 1, sx + 4, sy + 2, SkyUi.fade(0xFF200808, alpha));
        }
    }
}
