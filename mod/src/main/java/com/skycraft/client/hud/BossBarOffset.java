package com.skycraft.client.hud;

import com.skycraft.Skycraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Vanilla boss bars that Skycraft doesn't redraw itself (named quest bosses such as Alduin at Helgen, raids, other
 * mods' bosses) are drawn from y 12, right under the {@link Compass} (y 8 to 21). Move them below the compass, where
 * Skycraft's own boss bar sits (y 34). Pushed only when the overlay really renders (lowest priority, not on cancelled
 * events), so the pose stack always gets its matching pop in the Post event.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class BossBarOffset {
    private static final float OFFSET = 30.0F;
    private static boolean pushed;

    private BossBarOffset() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void pre(RenderGuiLayerEvent.Pre event) {
        if (!event.getName().equals(VanillaGuiLayers.BOSS_OVERLAY)) return;
        event.getGuiGraphics().pose().pushMatrix();
        event.getGuiGraphics().pose().translate(0.0F, OFFSET);
        pushed = true;
    }

    @SubscribeEvent
    public static void post(RenderGuiLayerEvent.Post event) {
        if (!pushed || !event.getName().equals(VanillaGuiLayers.BOSS_OVERLAY)) return;
        event.getGuiGraphics().pose().popMatrix();
        pushed = false;
    }
}
