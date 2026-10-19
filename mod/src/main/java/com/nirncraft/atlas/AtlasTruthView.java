package com.nirncraft.atlas;

import com.nirncraft.NirnCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Ground-truth views (the autotest verb {@code fog-off on|off}): no vanilla distance fog and no Infinite ATLAS aerial
 * haze, so a capture shows the blocks and the far field's materials as they are, the way the offline reference of the
 * same view draws them (tools/harness lab scenes with camera_from). Runs after every other fog handler.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class AtlasTruthView {
    private AtlasTruthView() {}

    /** Fog and haze off (test views only). */
    public static volatile boolean noFog;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!noFog) return;
        float far = 1.0e7f;
        event.setNearPlaneDistance(far);
        event.setFarPlaneDistance(far);
        var fog = event.getFogData();
        fog.skyEnd = Math.max(fog.skyEnd, far);
    }

    /** The autotest verb: "on" | "off" (fog is set every frame: nothing to rebuild). */
    public static void set(boolean on) {
        noFog = on;
    }
}
