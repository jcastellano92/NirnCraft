package com.skycraft.realms.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.skycraft.realms.Realm;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Dimension effects of a realm ({@code effects: "skycraft:<realm>"} in its dimension type): its own sky
 * ({@link RealmSky}), no clouds or weather except in the Shivering Isles (which keep a sun, moons and rain), and a
 * light tint (embers in the Deadlands, ink-green in Apocrypha, soul-violet in the Soul Cairn, cold blue in
 * Coldharbour, gold in Sovngarde).
 */
public class RealmEffects extends DimensionSpecialEffects {
    public final Realm realm;
    /** Multipliers for the lightmap (sky and block light) colours. */
    private final float tr, tg, tb;

    public RealmEffects(Realm realm) {
        super(realm == Realm.SHIVERING_ISLES ? 192f : Float.NaN, true,
                realm == Realm.SHIVERING_ISLES ? SkyType.NORMAL : SkyType.NONE, false, false);
        this.realm = realm;
        switch (realm) {
            case DEADLANDS -> { tr = 1.10f; tg = 0.82f; tb = 0.70f; }
            case SOVNGARDE -> { tr = 1.06f; tg = 1.02f; tb = 0.92f; }
            case APOCRYPHA -> { tr = 0.80f; tg = 1.05f; tb = 0.85f; }
            case SOUL_CAIRN -> { tr = 0.95f; tg = 0.85f; tb = 1.12f; }
            case COLDHARBOUR -> { tr = 0.82f; tg = 0.88f; tb = 1.10f; }
            default -> { tr = 1f; tg = 1f; tb = 1f; }
        }
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
        if (realm == Realm.SHIVERING_ISLES) {
            // day and night like Nirn
            return color.multiply(brightness * 0.94f + 0.06f, brightness * 0.94f + 0.06f, brightness * 0.91f + 0.09f);
        }
        return color;
    }

    @Override
    public boolean isFoggyAt(int x, int z) {
        return false;
    }

    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        return realm == Realm.SHIVERING_ISLES ? super.getSunriseColor(timeOfDay, partialTicks) : null;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        if (realm == Realm.SHIVERING_ISLES) return false;
        RealmSky.render(realm, level, ticks, partialTick, poseStack, camera, projectionMatrix, setupFog);
        return true;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX, double camY,
                                double camZ, Matrix4f projectionMatrix) {
        return realm != Realm.SHIVERING_ISLES;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture, double camX,
                                     double camY, double camZ) {
        return realm != Realm.SHIVERING_ISLES;
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return realm != Realm.SHIVERING_ISLES;
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker,
                                     float skyLight, int pixelX, int pixelY, Vector3f colors) {
        if (realm == Realm.SHIVERING_ISLES) return;
        colors.set(Mth.clamp(colors.x() * tr, 0f, 1f), Mth.clamp(colors.y() * tg, 0f, 1f), Mth.clamp(colors.z() * tb, 0f, 1f));
    }
}
