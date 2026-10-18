package com.skycraft.client.hud;

import com.skycraft.Skycraft;
import com.skycraft.client.SkyBossHud;
import com.skycraft.inventory.client.SkyUi;
import com.skycraft.visual.VisualConfig;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Locale;

/**
 * Skyrim's authentic enemy & boss health bar, positioned directly beneath the compass.
 * <p>
 * Displays the single entity the player is actively fighting or aiming at within their forward combat cone.
 * Both standard hostiles and bosses use this identical, unified bar. Features a carved Nordic trapezoidal
 * frame, symmetrical health depletion from both outer ends towards the center, fading damage trails on both sides,
 * Title-Cased names, and dynamic Celtic knot ornamental brackets that adapt to name lengths.
 * </p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class TargetHud {
    private static final double RANGE = 36.0;
    private static final long LINGER_MS = 3200;

    private static LivingEntity target;
    private static long lastSeen;
    private static LivingEntity lastAttacked;
    private static long lastAttackedTime;
    private static float shownHealth = 1f;
    private static float trailHealth = 1f;
    private static float alpha;
    private static long lastFrame = Util.getMillis();

    private TargetHud() {}

    /** Whether the target bar is visible on screen. */
    public static boolean isVisible() {
        return target != null && alpha > 0.05f;
    }

    /** The currently focused combat target. */
    public static LivingEntity getTarget() {
        return isVisible() ? target : null;
    }

    /** Returns the active target if it is a boss, or null. Used by soundtrack/boss events. */
    public static LivingEntity getActiveBoss() {
        LivingEntity t = getTarget();
        return (t != null && isBoss(t)) ? t : null;
    }

    /** Whether an entity counts as a boss. */
    public static boolean isBoss(LivingEntity e) {
        return SkyBossHud.isBossEntity(e);
    }

    /** When the local player attacks an entity, immediately engage it. */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide() && event.getEntity() == Minecraft.getInstance().player
                && event.getTarget() instanceof LivingEntity le && !(le instanceof ArmorStand)) {
            lastAttacked = le;
            lastAttackedTime = Util.getMillis();
            if (worthShowing(le)) {
                select(le);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            target = null;
            return;
        }

        long now = Util.getMillis();

        // Target death / invalidation check
        if (target != null) {
            if (!target.isAlive() || target.isDeadOrDying()) {
                shownHealth = 0f;
                if (now - lastSeen > 1200) {
                    target = null;
                }
            } else if (target.level() != mc.level || target.distanceTo(player) > (isBoss(target) ? 52.0 : RANGE * 1.5)) {
                target = null;
            }
        }

        if (mc.isPaused() || player.tickCount % 2 != 0) return;

        // Scan for candidates in the player's forward combat area
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);
        LivingEntity aimed = mc.crosshairPickEntity instanceof LivingEntity le ? le : farPick(mc, player);

        LivingEntity bestCandidate = null;
        double bestScore = -999.0;

        AABB searchBox = player.getBoundingBox().inflate(RANGE);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, searchBox, LivingEntity::isAlive)) {
            if (e == player || e instanceof ArmorStand || e.isSpectator() || e.isInvisible()) continue;
            if (!worthShowing(e)) continue;

            Vec3 toTarget = e.getEyePosition().subtract(eye);
            double dist = toTarget.length();
            if (dist < 0.2 || dist > (isBoss(e) ? 48.0 : RANGE)) continue;

            Vec3 dir = toTarget.normalize();
            double dot = look.dot(dir);

            // Forward combat cone (~140° area in front of player); recently attacked foes get wider leeway
            boolean recentAttack = (e == lastAttacked && (now - lastAttackedTime < 3500));
            if (dot < 0.35 && !recentAttack) continue;

            // Line of sight check (blocks obscure targets)
            if (!player.hasLineOfSight(e)) continue;

            // Alignment score: closest to look direction has highest dot; aim & combat engagement add priority
            double score = dot * 4.0 - (dist / 24.0);
            if (e == aimed) score += 5.0;
            if (recentAttack) score += 4.0;
            if (e instanceof Mob mob && (mob.getTarget() == player || mob.isAggressive())) score += 2.5;
            if (isBoss(e)) score += 1.5;
            if (e == target) score += 1.5; // Hysteresis bonus prevents jitter between nearby foes

            if (score > bestScore) {
                bestScore = score;
                bestCandidate = e;
            }
        }

        if (bestCandidate != null) {
            select(bestCandidate);
        } else if (target != null && now - lastSeen > LINGER_MS) {
            target = null;
        }
    }

    private static void select(LivingEntity e) {
        if (e != target) {
            target = e;
            shownHealth = trailHealth = health(e);
        }
        lastSeen = Util.getMillis();
    }

    /** Cheaper visual-line pick along the view vector. */
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
        if (e instanceof ArmorStand || (e instanceof Player && !e.isAlive())) return false;
        if (e.isInvisible()) return false;
        if (e instanceof Enemy || isBoss(e)) return true;
        LocalPlayer player = Minecraft.getInstance().player;
        if (e instanceof Mob mob && (mob.getTarget() == player || mob.isAggressive())) return true;
        if (player != null && (e.getLastHurtByMob() == player || player.getLastHurtByMob() == e)) return true;
        if (e == lastAttacked && (Util.getMillis() - lastAttackedTime < 6000)) return true;
        if (e instanceof com.skycraft.fauna.entity.FaunaAnimal && e.getHealth() < e.getMaxHealth()) return true;
        if (e instanceof com.skycraft.fauna.entity.FaunaAnimal && Minecraft.getInstance().crosshairPickEntity == e) return true;
        return e == target;
    }

    private static float health(LivingEntity e) {
        return e.getMaxHealth() <= 0 ? 0f : Mth.clamp(e.getHealth() / e.getMaxHealth(), 0f, 1f);
    }

    /**
     * Formats an entity's name into Skyrim's Title Case (e.g. "Bandit", "Bandit Chief", "Draugr Deathlord").
     */
    public static String formatTitleCase(Component component) {
        String raw = component.getString().trim();
        if (raw.isEmpty()) return "";
        String[] words = raw.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;
            if (i > 0) sb.append(' ');
            String lower = word.toLowerCase(Locale.ROOT);
            if (i > 0 && (lower.equals("of") || lower.equals("the") || lower.equals("and") || lower.equals("in") || lower.equals("a"))) {
                sb.append(lower);
            } else {
                sb.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) {
                    sb.append(lower.substring(1));
                }
            }
        }
        return sb.toString();
    }

    /** 10x6 Celtic knot pixel pattern framing enemy names. */
    private static final int[][] KNOT_PIXELS = {
            {0, 0, 1, 1, 0, 0, 1, 1, 0, 0},
            {0, 1, 1, 1, 1, 1, 1, 1, 1, 0},
            {1, 1, 0, 0, 1, 1, 0, 0, 1, 1},
            {1, 1, 0, 0, 1, 1, 0, 0, 1, 1},
            {0, 1, 1, 1, 1, 1, 1, 1, 1, 0},
            {0, 0, 1, 1, 0, 0, 1, 1, 0, 0}
    };

    /**
     * Renders the authentic Skyrim target health bar under the compass.
     */
    public static void render(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int width = g.guiWidth(), height = g.guiHeight();
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        float dt = Mth.clamp((now - lastFrame) / 1000f, 0f, 0.25f);
        lastFrame = now;

        if (!VisualConfig.TARGET_BAR.get() || mc.gui.hud.isHidden() || mc.player == null) return;
        LivingEntity e = target;
        boolean visible = e != null && (now - lastSeen < LINGER_MS);
        alpha += ((visible ? 1f : 0f) - alpha) * (1f - (float) Math.exp((visible ? -10.0 : -4.0) * dt));
        if (e == null || alpha < 0.02f) return;

        float hp = e.isAlive() ? health(e) : 0f;
        shownHealth += (hp - shownHealth) * (1f - (float) Math.exp(-12.0 * dt));
        if (trailHealth < shownHealth) {
            trailHealth = shownHealth;
        } else {
            trailHealth += (shownHealth - trailHealth) * (1f - (float) Math.exp(-2.5 * dt));
        }

        Font font = mc.font;
        int cx = width / 2;
        int barW = Math.min(160, width - 80);
        int barH = 6;
        int barY = 26; // Placed directly under the compass frame (compass frame y=11..25)
        int tSlope = 5; // Inset slope in pixels across bar height

        // 1. Trapezoidal Health Bar & Symmetrical Depletion / Trails
        for (int dy = 0; dy < barH; dy++) {
            int yLine = barY + dy;
            int slopeInset = (dy * tSlope) / barH;
            int rLeft = (cx - barW / 2) + slopeInset;
            int rRight = (cx + barW / 2) - slopeInset;

            // Dark trough background
            g.fill(rLeft, yLine, rRight, yLine + 1, SkyUi.fade(0xE0121010, alpha));
            g.fill(rLeft, yLine, rRight, yLine + 1, SkyUi.fade(0x403A1212, alpha));

            // Symmetrical health depletion on both sides
            int trailWidth = Math.round(barW * Mth.clamp(trailHealth, 0f, 1f));
            int fillWidth = Math.round(barW * Mth.clamp(shownHealth, 0f, 1f));

            int tLeft = Math.max(rLeft, cx - trailWidth / 2);
            int tRight = Math.min(rRight, cx + trailWidth / 2);
            int fLeft = Math.max(rLeft, cx - fillWidth / 2);
            int fRight = Math.min(rRight, cx + fillWidth / 2);

            // Fading damage trail on both sides
            if (fLeft > tLeft) {
                g.fill(tLeft, yLine, fLeft, yLine + 1, SkyUi.fade(0x999E342A, alpha));
            }
            if (tRight > fRight) {
                g.fill(fRight, yLine, tRight, yLine + 1, SkyUi.fade(0x999E342A, alpha));
            }

            // Symmetrical red health fill (centered, draining from both sides)
            if (fRight > fLeft) {
                int red = switch (dy) {
                    case 0 -> 0xFFE64646; // Specular top highlight
                    case 1 -> 0xFFD22828; // Upper bright crimson
                    case 2, 3 -> 0xFFB21818; // Rich Skyrim crimson
                    default -> 0xFF650A0A; // Shaded bottom
                };
                g.fill(fLeft, yLine, fRight, yLine + 1, SkyUi.fade(red, alpha));
            }
        }

        // 2. Trapezoid Frame Outlines
        int borderColor = SkyUi.fade(0xFF383232, alpha);
        // Top line
        g.fill(cx - barW / 2 - 1, barY - 1, cx + barW / 2 + 1, barY, borderColor);
        // Bottom line
        g.fill(cx - barW / 2 + tSlope, barY + barH, cx + barW / 2 - tSlope, barY + barH + 1, borderColor);
        // Sloped ends
        for (int dy = 0; dy < barH; dy++) {
            int slope = (dy * tSlope) / barH;
            int xL = (cx - barW / 2) + slope;
            int xR = (cx + barW / 2) - slope;
            g.fill(xL - 1, barY + dy, xL, barY + dy + 1, borderColor);
            g.fill(xR, barY + dy, xR + 1, barY + dy + 1, borderColor);
        }

        // 3. Enemy Name & Dynamic Nordic Ornamental Brackets
        int nameY = barY + barH + 3;
        String name = formatTitleCase(e.getDisplayName());
        int rawW = font.width(name);

        // Dynamically scale/condense font for long names
        float scale = 0.82f;
        if (rawW * scale > 85f) {
            scale = Math.max(0.65f, 85f / rawW);
        }
        float scaledW = rawW * scale;

        // Celtic knot positions & wing spans
        int knotW = 10;
        int knotH = 6;
        int knotY = nameY + (int) (9 * scale) / 2 - 3;
        int leftKnotX = (int) (cx - (scaledW / 2f) - 4 - knotW);
        int rightKnotX = (int) (cx + (scaledW / 2f) + 4);

        int maxWing = (int) ((barW - scaledW) / 2 - 16);
        int wingLen = Math.max(12, Math.min(42, maxWing));
        int wingColor = SkyUi.fade(0xFF8A847C, alpha);

        // Left wing (double horizontal lines with angled tip)
        g.fill(leftKnotX - wingLen + 3, knotY + 1, leftKnotX, knotY + 2, wingColor);
        g.fill(leftKnotX - wingLen, knotY + 4, leftKnotX, knotY + 5, wingColor);
        drawAngledCap(g, leftKnotX - wingLen, knotY + 1, true, wingColor);

        // Right wing (double horizontal lines with angled tip)
        g.fill(rightKnotX + knotW, knotY + 1, rightKnotX + knotW + wingLen, knotY + 2, wingColor);
        g.fill(rightKnotX + knotW, knotY + 4, rightKnotX + knotW + wingLen - 3, knotY + 5, wingColor);
        drawAngledCap(g, rightKnotX + knotW + wingLen - 3, knotY + 1, false, wingColor);

        // Celtic knots
        drawKnot(g, leftKnotX, knotY, alpha);
        drawKnot(g, rightKnotX, knotY, alpha);

        // Centered Title-Cased Name with drop shadow
        SkyUi.scaledCentered(g, font, name, cx, nameY, scale, SkyUi.fade(0xFFECE6D8, alpha), true);
    }

    private static void drawKnot(GuiGraphicsExtractor g, int kx, int ky, float alpha) {
        int ribbonColor = SkyUi.fade(0xFFC4BEB0, alpha);
        int shadowColor = SkyUi.fade(0xFF282420, alpha);
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 10; x++) {
                if (KNOT_PIXELS[y][x] == 1) {
                    g.fill(kx + x, ky + y + 1, kx + x + 1, ky + y + 2, shadowColor);
                    g.fill(kx + x, ky + y, kx + x + 1, ky + y + 1, ribbonColor);
                }
            }
        }
    }

    private static void drawAngledCap(GuiGraphicsExtractor g, int x, int y, boolean left, int color) {
        if (left) {
            g.fill(x + 2, y, x + 3, y + 1, color);
            g.fill(x + 1, y + 1, x + 2, y + 2, color);
            g.fill(x + 1, y + 2, x + 2, y + 3, color);
            g.fill(x, y + 3, x + 1, y + 4, color);
        } else {
            g.fill(x + 2, y, x + 3, y + 1, color);
            g.fill(x + 1, y + 1, x + 2, y + 2, color);
            g.fill(x + 1, y + 2, x + 2, y + 3, color);
            g.fill(x, y + 3, x + 1, y + 4, color);
        }
    }
}
