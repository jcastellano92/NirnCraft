package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.render.client.DataTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.nirncraft.NirnCraft;
import com.nirncraft.fire.client.ClientFire;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Bounded atmosphere sources: the authored volcano and the strongest
 * nearby synced fire sites, sharing their clocks and positions with visible flames. */
public final class SmokeSourceTexture {
    private static final Identifier LOCATION = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/smoke_sources.png");
    private static final int SOURCES=24;
    private static final float[] FIRES = new float[(SOURCES-1) * 6];
    private static final MapSample SAMPLE = new MapSample();
    private static GpuTexture uploaded;
    private static NativeImage image;
    private static float volcanoY = -1;
    private static final float[] BOUNDS = new float[SOURCES * 3];
    private static int activeSources;
    private static ClientLevel tracked;
    private static final float[] AURORA = new float[com.nirncraft.atmosphere.AuroraMath.VALUES];

    private SmokeSourceTexture() {}

    static void tick(ClientLevel level, Vec3 camera) {
        try(var uploadState=TextureUploadState.preserve()) {
        if (image == null) image = new NativeImage(32, 4, false);
        var manager = Minecraft.getInstance().getTextureManager();
        uploaded = DataTexture.target(LOCATION, image);
        boolean changed=tracked!=level; tracked=level;
        if (changed || level.getGameTime() % 10 == 0) {
            java.util.Arrays.fill(BOUNDS, 0f);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 16; x++) image.setPixelABGR(x, y, 0);
            for(int y=2;y<4;y++) for(int x=0;x<32;x++) image.setPixelABGR(x,y,0);
            if(level.dimension()==net.minecraft.world.level.Level.OVERWORLD) Places.byId("red_mountain").ifPresent(place -> {
                if (volcanoY < 0) {
                    TamrielMap.sample(place.x(), place.z(), SAMPLE);
                    volcanoY = SAMPLE.elevation;
                }
                source(0, place.x(), volcanoY, place.z(), 1.0f, 1000, 190, 3);
            });
            int count = ClientFire.smokeSources(FIRES, SOURCES-1, camera.x, camera.z);
            for (int i = 0; i < count; i++) {
                int p = i * 6;
                int flame=(int)FIRES[p+4], smoke=(int)FIRES[p+5];
                source(i + 1, FIRES[p], FIRES[p + 1]+com.nirncraft.fire.FirePlume.base(flame), FIRES[p + 2], FIRES[p + 3],
                        com.nirncraft.fire.FirePlume.height(flame,smoke),
                        com.nirncraft.fire.FirePlume.radius(flame,FIRES[p+3]), smoke);
            }
            activeSources=count+1;
            image.setPixelABGR(31,3,activeSources);
            float night = Mth.clamp(com.nirncraft.atmosphere.client.SkyEnvironment.starBrightness(level, 1f) * 2f, 0f, 1f);
            float aurora = com.nirncraft.atmosphere.AtmosphereConfig.AURORA.get()
                    ? night * night * SkyRenderer.coldness * SkyRenderer.activity(level)
                        * com.nirncraft.atmosphere.AtmosphereConfig.AURORA_BRIGHTNESS.get().floatValue() : 0f;
            int a = Mth.clamp(Math.round(aurora * 255), 0, 255);
            image.setPixelABGR(15, 1, (a << 24) | (Math.round(aurora * 3) << 16)
                    | (Math.round(aurora * 7) << 8) | Math.round(aurora * 2));
        }
        // Odd row-one texels are a shared celestial state; even texels remain smoke shapes.
        image.setPixelABGR(1, 1, (int) Math.floorMod(level.getOverworldClockTime(), 1_728_000L));
        // the far field's near / far hand-off band (blocks / 64): the pack's crossfade uses the same one
        image.setPixelABGR(17, 1, rgba(Math.min(1f, com.nirncraft.atlas.AtlasClient.handoffBand / 64f), 0f, 0f, 1f));
        image.setPixelABGR(3, 1, rgba(NightSkyState.visibility, NightSkyState.pollution,
                NightSkyState.masserLight, NightSkyState.secundaLight));
        double solarAngle = com.nirncraft.atmosphere.client.SkyEnvironment.sunCycle(level, 1f) * Math.PI * 2;
        int masserElevation = Mth.clamp(Math.round((com.nirncraft.atmosphere.AtmosphereMath.moonElevation(solarAngle,
                com.nirncraft.atmosphere.AtmosphereMath.moonCycle(level.getOverworldClockTime(), false), false) * 0.5f + 0.5f) * 65535), 0, 65535);
        int secundaElevation = Mth.clamp(Math.round((com.nirncraft.atmosphere.AtmosphereMath.moonElevation(solarAngle,
                com.nirncraft.atmosphere.AtmosphereMath.moonCycle(level.getOverworldClockTime(), true), true) * 0.5f + 0.5f) * 65535), 0, 65535);
        image.setPixelABGR(16, 0, masserElevation | (secundaElevation << 16));
        image.setPixelABGR(5, 1, rgba(SkyRenderer.latitudeFactor(camera.z), SkyRenderer.activity(level),
                SkyRenderer.auroraRedMix(level), 1f));
        image.setPixelABGR(7, 1, rgba(com.nirncraft.atmosphere.AtmosphereConfig.STARS.get() ? 1 : 0,
                com.nirncraft.atmosphere.AtmosphereConfig.GALAXY.get() ? 1 : 0,
                com.nirncraft.atmosphere.AtmosphereConfig.SECUNDA.get() ? 1 : 0,
                com.nirncraft.atmosphere.AtmosphereConfig.AURORA.get() ? 1 : 0));
        float activity = (image.getPixel(15, 1) >>> 24) / 255f;
        com.nirncraft.atmosphere.AuroraMath.sample(level.getGameTime() / 20.0,
                SkyRenderer.latitudeFactor(camera.z), activity, SkyRenderer.auroraRedMix(level), AURORA);
        for (int i = 0; i < 6; i++) {
            int p = i * 8;
            image.setPixelABGR(16 + i * 2, 1, rgba((AURORA[p] + (float) Math.PI) / ((float) Math.PI * 2),
                    AURORA[p+1] / 1.5f, AURORA[p+2] / 1.5f, AURORA[p+3]));
            int amplitude = Mth.clamp(Math.round(AURORA[p+4] * 65535), 0, 65535);
            image.setPixelABGR(17 + i * 2, 1, amplitude | (Math.round(AURORA[p+5] * 255) << 16)
                    | (Math.round(AURORA[p+6] * 255) << 24));
        }
        for (int coefficient = 0; coefficient < 4; coefficient++) {
            int p = 48 + coefficient * 3;
            float scale = coefficient == 0 ? 1f : 0.5f, bias = coefficient == 0 ? 0f : 0.5f;
            image.setPixelABGR(28 + coefficient, 1,
                    rgba(AURORA[p] * scale + bias, AURORA[p+1] * scale + bias, AURORA[p+2] * scale + bias, activity));
        }
        image.setPixelABGR(13, 1, (int) level.getGameTime());

        DataTexture.write(LOCATION, image);


        }
    }

    private static int rgba(float r, float g, float b, float a) {
        return Mth.clamp(Math.round(r * 255), 0, 255) | (Mth.clamp(Math.round(g * 255), 0, 255) << 8)
                | (Mth.clamp(Math.round(b * 255), 0, 255) << 16) | (Mth.clamp(Math.round(a * 255), 0, 255) << 24);
    }

    /** Two header texels per rendered frame; source discovery remains tick-budgeted. */
    static void renderClock(ClientLevel level, float partialTick) {
        if (image == null || !currentTexture()) return;
        try(var uploadState=TextureUploadState.preserve()) {
        image.setPixelABGR(13, 1, (int) level.getGameTime());
        // Odd texels in this row are headers; even texels hold source shapes.
        image.setPixelABGR(11, 1, Mth.clamp(Math.round(partialTick * 255), 0, 255)
                | (Math.round(AtmosphereBudget.scale() * 255) << 8)
                | (Mth.clamp(Math.round(ClientWeather.wind() * 255), 0, 255) << 16));

        DataTexture.writeRect(LOCATION, image, 11, 1, 3, 1);
        }
    }

    private static boolean currentTexture() {
        var texture=Minecraft.getInstance().getTextureManager().getTexture(LOCATION);
        return DataTexture.current(LOCATION, uploaded);
    }

    private static void source(int index, float x, float y, float z, float intensity, float height, float radius, float smoke) {
        BOUNDS[index * 3] = x; BOUNDS[index * 3 + 1] = z;
        BOUNDS[index * 3 + 2] = height * 0.75f + radius * 2f;
        int ix = Mth.clamp(Math.round(x) + 32768, 0, 65535), iz = Mth.clamp(Math.round(z) + 32768, 0, 65535);
        int iy = Mth.clamp(Math.round(y) + 64, 0, 65535);
        int ih = Mth.clamp(Math.round(height), 0, 65535);
        int row=index<8?0:2, slot=index<8?index:index-8;
        image.setPixelABGR(slot * 2, row, (ix & 255) | ((ix >> 8) << 8) | ((iz & 255) << 16) | ((iz >> 8) << 24));
        image.setPixelABGR(slot * 2 + 1, row, (iy & 255) | ((iy >> 8) << 8)
                | (Mth.clamp(Math.round(intensity * 100), 0, 255) << 16) | (Math.round(smoke * 70) << 24));
        image.setPixelABGR(slot * 2, row+1, (ih & 255) | ((ih >> 8) << 8) | (Mth.clamp(Math.round(radius), 0, 255) << 16));
    }

    static boolean mayContainSmoke(double x, double z, int cell) {
        for (int i = 0; i < activeSources; i++) {
            float radius = BOUNDS[i * 3 + 2];
            if (radius <= 0) continue;
            double dx = x - BOUNDS[i * 3], dz = z - BOUNDS[i * 3 + 1];
            if (dx * dx + dz * dz <= (radius + cell) * (radius + cell)) return true;
        }
        return false;
    }

    public static boolean containsFire(double x,double z) {
        for(int i=1;i<activeSources;i++) if(BOUNDS[i*3+2]>0 && Math.abs(BOUNDS[i*3]-x)<1 && Math.abs(BOUNDS[i*3+1]-z)<1) return true;
        return false;
    }

    public static int activeSources() { return activeSources; }
}
