package com.skycraft.client.hud;

import com.skycraft.Skycraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Vanilla boss bars that Skycraft doesn't redraw itself (named quest bosses such as Alduin at Helgen, raids, other
 * mods' bosses) are drawn from y 12, right under the {@link Compass} (y 8 to 21). Move them below the compass, where
 * Skycraft's own boss bar sits (y 34). Pushed only when the overlay really renders (lowest priority, not on cancelled
 * events), so the pose stack always gets its matching pop in the Post event.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class BossBarOffset {
    private static final float OFFSET = 26.0F;
    private static boolean pushed;

    private BossBarOffset() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void pre(RenderGuiOverlayEvent.Pre event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id())) return;
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0.0F, OFFSET, 0.0F);
        pushed = true;
    }

    @SubscribeEvent
    public static void post(RenderGuiOverlayEvent.Post event) {
        if (!pushed || !event.getOverlay().id().equals(VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id())) return;
        event.getGuiGraphics().pose().popPose();
        pushed = false;
    }
}
