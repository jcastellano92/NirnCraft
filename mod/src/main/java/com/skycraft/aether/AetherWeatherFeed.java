package com.skycraft.aether;

import com.skycraft.atmosphere.client.WeatherFieldTexture;
import net.minecraft.client.Minecraft;

/**
 * The weather aether's cloud shell and rain shafts follow: WeatherFieldTexture's four fields (near and far coverage +
 * dynamics, the textures the old GLSL clouds sampled), copied only when they changed. WeatherFieldTexture.copyForAether
 * is added by move_in.ps1 (see its patch).
 */
final class AetherWeatherFeed implements AetherClient.WeatherFeed {
    private long version = Long.MIN_VALUE;

    @Override
    public boolean fill(int[] nearField, int[] nearDyn, int[] farField, int[] farDyn) {
        long v = WeatherFieldTexture.copyForAether(version, nearField, nearDyn, farField, farDyn);
        boolean changed = v != version;
        version = v;
        return changed;
    }

    @Override
    public float rain() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0f : level.getRainLevel(1f);
    }
}
