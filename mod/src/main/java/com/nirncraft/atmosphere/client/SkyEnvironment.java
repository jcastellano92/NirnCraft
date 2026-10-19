package com.nirncraft.atmosphere.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;

/** Camera-interpolated native timelines, including dimension clocks and environmental overrides. */
public final class SkyEnvironment {
    private static ClientLevel baselineLevel;
    private static net.minecraft.world.attribute.EnvironmentAttributeSystem baseline;
    private static long baselineTick = Long.MIN_VALUE;
    private SkyEnvironment() {}
    public static float sunCycle(ClientLevel level, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level) throw new IllegalArgumentException("Sky sample belongs to an inactive client level");
        return minecraft.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) / 360f;
    }
    public static float starBrightness(ClientLevel level, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level) throw new IllegalArgumentException("Sky sample belongs to an inactive client level");
        return minecraft.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partialTick);
    }
    /** Native flash is a client-only environmental layer; compare with the same public default-layer composition. */
    public static boolean lightningFlash(ClientLevel level, float partialTick) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level) throw new IllegalArgumentException("Sky sample belongs to an inactive client level");
        if (baselineLevel != level) {
            baselineLevel = level;
            baseline = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().addDefaultLayers(level).build();
            baselineTick = Long.MIN_VALUE;
        }
        if (baselineTick != level.getGameTime()) {
            baseline.invalidateTickCache(); baselineTick = level.getGameTime();
        }
        var position = minecraft.gameRenderer.mainCamera().position();
        return !level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, position)
                .equals(baseline.getValue(EnvironmentAttributes.SKY_COLOR, position));
    }
    public static void clear() { baselineLevel = null; baseline = null; baselineTick = Long.MIN_VALUE; }
}
