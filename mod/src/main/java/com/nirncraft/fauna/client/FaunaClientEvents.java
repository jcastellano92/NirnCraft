package com.nirncraft.fauna.client;

import com.nirncraft.client.SkyKeys;
import com.nirncraft.core.HudStack;
import com.nirncraft.fauna.FaunaPackets;
import com.nirncraft.fauna.TameBalance;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Client events for Fauna:
 * <ul>
 *     <li>H key to call the horse.</li>
 *     <li>The horse-breaking balance minigame ({@link com.nirncraft.fauna.WildHorseTaming}): the held A / D steer is sent
 *     to the server when it changes (and every few ticks while held); the bar at the bottom centre of the screen shows
 *     the server's marker, safe zone and progress, predicted forward between updates with the same arithmetic; the
 *     camera rolls and yaws with the animal's bucking and eases back when it ends.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.nirncraft.NirnCraft.MODID, value = Dist.CLIENT)
public final class FaunaClientEvents {
    private static boolean active;
    /** Last state from the server (percent values as sent). */
    private static float marker, vel, zone = 0.3f, progress, amp, edge;
    private static int sentSteer;
    private static int sinceSend;
    /** Camera sway shown (eased toward the marker). */
    private static float sway;
    private static long tick;

    private FaunaClientEvents() {}

    public static void handleTameState(FaunaPackets.TameState p) {
        if (!p.active()) {
            active = false;
            progress = p.progress() / 100f;
            sentSteer = 0;
            return;
        }
        active = true;
        marker = p.marker() / 100f;
        vel = p.vel() / 100f * 0.2f;
        zone = p.zone() / 100f;
        progress = p.progress() / 100f;
        amp = p.amp() / 100f;
        edge = p.edge() / 100f;
    }

    private static int steer(Minecraft mc) {
        if (mc.gui.screen() != null) return 0;
        int s = 0;
        if (mc.options.keyLeft.isDown()) s--;
        if (mc.options.keyRight.isDown()) s++;
        return s;
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        tick++;

        // Check Call Horse key (H)
        while (SkyKeys.CALL_HORSE.consumeClick()) {
            SkyNetwork.sendToServer(new FaunaPackets.CallHorse());
        }

        if (active && !mc.player.isPassenger()) active = false; // thrown or got off: the server's end packet may be a tick behind
        if (active) {
            int s = steer(mc);
            sinceSend++;
            if (s != sentSteer || s != 0 && sinceSend >= 4) {
                sentSteer = s;
                sinceSend = 0;
                SkyNetwork.sendToServer(new FaunaPackets.SteerBalance(s));
            }
            // between the server's updates move the marker with the same rule (the bucking is the server's to add)
            vel = vel * TameBalance.DAMPING + s * TameBalance.PUSH;
            marker = Mth.clamp(marker + vel, -1f, 1f);
        } else if (sentSteer != 0) {
            sentSteer = 0;
            SkyNetwork.sendToServer(new FaunaPackets.SteerBalance(0));
        }
        float target = active ? marker : 0f;
        sway += (target - sway) * (active ? 0.35f : 0.15f);
    }

    @SubscribeEvent
    public static void onMount(net.neoforged.neoforge.event.entity.EntityMountEvent event) {
        if (event.isDismounting() && active && event.getEntityMounting() == Minecraft.getInstance().player) {
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!active && Math.abs(sway) < 0.005f) return;
        // the animal's bucking rolls the view and swings it a little: more as the bucking builds
        float strength = 0.7f + 0.5f * Mth.clamp(amp, 0f, 1.2f);
        event.setRoll(event.getRoll() + sway * 9f * strength);
        event.setYaw(event.getYaw() + sway * 3.5f * strength);
        event.setPitch(event.getPitch() + Mth.sin(tick * 0.9f) * amp * (active ? 1.2f : 0f));
    }

    @SubscribeEvent
    public static void renderTameBar(RenderGuiEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden()) return;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        Font font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        int barW = 180, barH = 9;
        int y = HudStack.reserve(HudStack.Zone.BOTTOM_CENTER, HudStack.BC_TAME, 30, h);
        int x = w / 2 - barW / 2;
        // progress (above the bar)
        g.fill(x - 1, y - 1, x + barW + 1, y + 4, 0xA0000000);
        g.fill(x, y, x + Math.round(barW * Mth.clamp(progress, 0f, 1f)), y + 3, 0xFF40C040);
        // the bar: edges in red (the grace burns there), the safe zone in green, the marker in gold
        int by = y + 8;
        g.fill(x - 1, by - 1, x + barW + 1, by + barH + 1, 0xA0000000);
        g.fill(x, by, x + barW, by + barH, 0xFF2A2724);
        int edgeW = Math.round(barW * 0.04f);
        int danger = 0x60 + Math.round(0x9F * Mth.clamp(edge, 0f, 1f));
        g.fill(x, by, x + edgeW, by + barH, danger << 24 | 0xD03030);
        g.fill(x + barW - edgeW, by, x + barW, by + barH, danger << 24 | 0xD03030);
        int zw = Math.round(barW / 2f * Mth.clamp(zone, 0.05f, 1f));
        g.fill(x + barW / 2 - zw, by, x + barW / 2 + zw, by + barH, 0xC03F8F3F);
        int mx = x + barW / 2 + Math.round(Mth.clamp(marker, -1f, 1f) * (barW / 2f - 3));
        g.fill(mx - 2, by - 3, mx + 3, by + barH + 3, 0xFFE8C060);
        g.fill(mx - 1, by - 3, mx + 2, by + barH + 3, 0xFFFFF0B0);
        Component hint = Component.translatable("mounts.nirncraft.tame.hint");
        g.text(font, hint, w / 2 - font.width(hint) / 2, by + barH + 5, 0xFFE8E0C8, true);
    }
}
