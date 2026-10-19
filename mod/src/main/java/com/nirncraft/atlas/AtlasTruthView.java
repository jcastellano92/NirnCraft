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
        // the far field on screen (vanilla path): no render-distance fog. It only hid the edge of the loaded chunks,
        // which the far field now continues; left on it fogged the near field's edge and the fallback compositor
        // continued that fog into the far field, a pale ring at the seam with clear land beyond it. Environmental fog
        // (weather, water, lava, blindness) stays, at its real distance, near and far alike.
        if (!noFog && AtlasClient.active() && !AtlasClient.irisPackInUse()) {
            // the fog starts where it ends, at the terrain's edge: the near field is clear right up to it, and the
            // edge stays known (the compositor caps environmental fog there, and fogHidesFarField compares with it)
            var fog = event.getFogData();
            fog.renderDistanceStart = fog.renderDistanceEnd;
            return;
        }
        if (!noFog) return;
        float far = 1.0e7f;
        event.setNearPlaneDistance(far);
        event.setFarPlaneDistance(far);
        // every range, not only the one the event's setters move: the fallback compositor's seam band
        // (atlas_fallback.fsh "fog continuity") reads the render-distance range, and that was the pale band left
        var fog = event.getFogData();
        fog.environmentalStart = fog.environmentalEnd = far;
        fog.renderDistanceStart = fog.renderDistanceEnd = far;
        fog.skyEnd = fog.cloudEnd = far;
    }

    /** The autotest verb: "on" | "off" (fog is set every frame: nothing to rebuild). */
    public static void set(boolean on) {
        noFog = on;
    }
}
