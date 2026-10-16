package com.skycraft.atmosphere.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.skycraft.Skycraft;
import com.skycraft.atmosphere.WeatherFront;
import com.skycraft.atmosphere.WeatherPhysics;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Toroidal world-space {@code 128x128} {@link AbstractTexture} bound at
 * {@code skycraft:textures/environment/weather_field.png} ({@code uniform sampler2D skycraftWeatherTex} in
 * Complementary Reimagined).
 *
 * <p>Each texel covers a {@code 64x64}-block world cell ({@code 8,192 x 8,192} blocks total span) addressed via
 * toroidal wrapping {@code fract(worldXZ / 8192.0)}. Because texel coordinates are anchored to fixed world-space
 * multiples of 64 blocks rather than the camera position, moving or rotating the camera produces <b>zero UV shift and
 * zero cloud jitter</b>.</p>
 *
 * <p>Channel encoding:
 * <ul>
 *   <li><b>R</b>: 3D volumetric cloud coverage ({@code 0..1}), driven by regional weather, marine/lake moisture,
 *       windward orographic lift, leeward rainshadows, and synoptic fronts.</li>
 *   <li><b>G</b>: Vertical convective / cumulonimbus anvil growth &amp; storm-base darkening ({@code 0..1}), including
 *       mesocyclone wall clouds and overshooting tops.</li>
 *   <li><b>B</b>: Mesoscale curved rain-band &amp; sub-cloud precipitation curtain optical density ({@code 0..1}).</li>
 *   <li><b>A</b>: Packed aerosol regime (4 bands: {@code 0..0.25} water rain, {@code 0.25..0.50} snow/blizzard,
 *       {@code 0.50..0.75} desert sand/haboob, {@code 0.75..1.00} volcanic ash/blight) plus live internal cloud
 *       lightning illumination ({@code 0..1} within each 0.25 band).</li>
 * </ul>
 */
public final class WeatherFieldTexture extends AbstractTexture {
    public static final ResourceLocation LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/weather_field.png");
    private static final int SIZE = WeatherPhysics.FIELD_GRID_SIZE; // 128
    private static final int CELL = WeatherPhysics.FIELD_CELL_BLOCKS; // 64
    private static final int ROWS_PER_TICK = 16; // Full 128x128 background sweep every 8 ticks (0.4s)

    private static WeatherFieldTexture instance;
    private final NativeImage image = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
    private final float[] baseR = new float[SIZE * SIZE];
    private final float[] baseG = new float[SIZE * SIZE];
    private final float[] baseB = new float[SIZE * SIZE];
    private final int[] baseBand = new int[SIZE * SIZE];
    private boolean initialized;
    private int sweepRow;
    private int lastCenterCellX = Integer.MIN_VALUE;
    private int lastCenterCellZ = Integer.MIN_VALUE;
    private boolean hadActiveFlash;

    private WeatherFieldTexture() {
        fillDefault();
    }

    public static WeatherFieldTexture get() {
        if (instance == null) {
            instance = new WeatherFieldTexture();
        }
        return instance;
    }

    public static void ensureRegistered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) return;
        WeatherFieldTexture tex = get();
        AbstractTexture existing = mc.getTextureManager().getTexture(LOCATION, null);
        if (existing != tex) {
            mc.getTextureManager().register(LOCATION, tex);
        }
    }

    private void fillDefault() {
        int defaultPixel = packAbgr(0.32f, 0.08f, 0.0f, 0, 0.0f);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int idx = z * SIZE + x;
                baseR[idx] = 0.32f;
                baseG[idx] = 0.08f;
                baseB[idx] = 0.0f;
                baseBand[idx] = 0;
                image.setPixelRGBA(x, z, defaultPixel);
            }
        }
    }

    @Override
    public void load(ResourceManager manager) throws IOException {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            uploadToGl(getId());
        } else {
            RenderSystem.recordRenderCall(() -> uploadToGl(getId()));
        }
    }

    /**
     * Updates a slice of the toroidal weather field on client tick (or the entire field on first join / teleport),
     * plus live lightning illumination around any active {@link DistantStorms} flashes.
     */
    public static void tickUpdate(ClientLevel level, Vec3 cam, float partialTick) {
        ensureRegistered();
        get().update(level, cam, partialTick);
    }

    private void update(ClientLevel level, Vec3 cam, float partialTick) {
        int centerCellX = Mth.floor(cam.x / CELL);
        int centerCellZ = Mth.floor(cam.z / CELL);
        boolean teleported = !initialized
                || Math.abs(centerCellX - lastCenterCellX) > 24
                || Math.abs(centerCellZ - lastCenterCellZ) > 24;

        lastCenterCellX = centerCellX;
        lastCenterCellZ = centerCellZ;

        double timeSec = (level.getGameTime() + partialTick) / 20.0;
        Map<String, float[]> climate = CloudClimate.snapshot();
        List<WeatherFront> fronts = ClientWeather.fronts();
        float wx = ClientWeather.windX();
        float wz = ClientWeather.windZ();
        boolean regionalActive = ClientWeather.active();
        float vanillaRain = level.getRainLevel(partialTick);
        float vanillaThunder = level.getThunderLevel(partialTick);

        int rowsToUpdate = teleported ? SIZE : ROWS_PER_TICK;
        boolean flashNow = DistantStorms.hasActiveFlash();

        for (int r = 0; r < rowsToUpdate; r++) {
            int row = (sweepRow + r) & (SIZE - 1);
            int worldCellZ = nearestWorldCell(centerCellZ, row);
            double sampleZ = (worldCellZ + 0.5) * CELL;

            for (int col = 0; col < SIZE; col++) {
                int worldCellX = nearestWorldCell(centerCellX, col);
                double sampleX = (worldCellX + 0.5) * CELL;

                String regId = TamrielMap.region((int) sampleX, (int) sampleZ).id();
                float[] regParams = regionalActive ? climate.get(regId) : null;
                WeatherKind regKind = regionalActive ? ClientWeather.kindOf(regId) : (vanillaThunder > 0.2f ? WeatherKind.STORM : (vanillaRain > 0.2f ? WeatherKind.RAIN : WeatherKind.CLEAR));

                WeatherPhysics.ColumnState state = WeatherPhysics.evaluate(
                        sampleX, sampleZ, timeSec, regParams, regKind, wx, wz, fronts);

                int band = aerosolBand(state.aerosolCode());
                int idx = row * SIZE + col;
                if (teleported) {
                    baseR[idx] = state.coverage();
                    baseG[idx] = state.stormTower();
                    baseB[idx] = state.curtainDensity();
                } else {
                    // Temporal smoothing across sweeps so growing cumulus, storm towers, and rain bands evolve organically
                    baseR[idx] = Mth.lerp(0.35f, baseR[idx], state.coverage());
                    baseG[idx] = Mth.lerp(0.35f, baseG[idx], state.stormTower());
                    baseB[idx] = Mth.lerp(0.40f, baseB[idx], state.curtainDensity());
                }
                baseBand[idx] = band;

                float flash = flashNow ? DistantStorms.flashGlowAt(sampleX, sampleZ, partialTick) : 0f;
                image.setPixelRGBA(col, row, packAbgr(baseR[idx], baseG[idx], baseB[idx], band, flash));
            }
        }
        sweepRow = (sweepRow + rowsToUpdate) & (SIZE - 1);
        initialized = true;

        // If a lightning flash is active or just ended, refresh all texels with current flash glow so C2C/IC/CG flashes are crisp at 20 Hz
        if (flashNow || hadActiveFlash) {
            for (int row = 0; row < SIZE; row++) {
                int worldCellZ = nearestWorldCell(centerCellZ, row);
                double sampleZ = (worldCellZ + 0.5) * CELL;
                for (int col = 0; col < SIZE; col++) {
                    int worldCellX = nearestWorldCell(centerCellX, col);
                    double sampleX = (worldCellX + 0.5) * CELL;
                    int idx = row * SIZE + col;
                    float flash = flashNow ? DistantStorms.flashGlowAt(sampleX, sampleZ, partialTick) : 0f;
                    image.setPixelRGBA(col, row, packAbgr(baseR[idx], baseG[idx], baseB[idx], baseBand[idx], flash));
                }
            }
            hadActiveFlash = flashNow;
        }

        uploadAllTargets();
    }

    /**
     * Maps a toroidal texel index {@code texel in [0, 127]} to the world cell index closest to {@code centerCell}
     * satisfying {@code Math.floorMod(worldCell, 128) == texel}.
     */
    private static int nearestWorldCell(int centerCell, int texel) {
        int base = centerCell - (SIZE / 2);
        int offset = Math.floorMod(texel - base, SIZE);
        return base + offset;
    }

    private static int aerosolBand(float code) {
        if (code >= 0.78f) return 3; // Volcanic ash / blight
        if (code >= 0.55f) return 2; // Desert sand / haboob
        if (code >= 0.26f) return 1; // Snow / blizzard
        return 0;                    // Temperate / marine water cloud & rain
    }

    private static int packAbgr(float cov, float tower, float curtain, int band, float flash) {
        int r = clampByte(cov);
        int g = clampByte(Math.min(1.0f, tower + flash * 0.25f));
        int b = clampByte(curtain);
        float encodedA = (band * 0.25f) + 0.02f + 0.21f * Mth.clamp(flash, 0.0f, 1.0f);
        int a = clampByte(encodedA);
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int clampByte(float v) {
        int i = Math.round(Mth.clamp(v, 0.0f, 1.0f) * 255.0f);
        return Math.max(0, Math.min(255, i));
    }

    private void uploadAllTargets() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::uploadAllTargets);
            return;
        }
        uploadToGl(getId());
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            AbstractTexture registered = mc.getTextureManager().getTexture(LOCATION, null);
            if (registered != null && registered != this && registered.getId() != getId()) {
                uploadToGl(registered.getId());
            }
        }
    }

    private void uploadToGl(int glId) {
        TextureUtil.prepareImage(glId, 0, SIZE, SIZE);
        GlStateManager._bindTexture(glId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        image.upload(0, 0, 0, 0, 0, SIZE, SIZE, true, false, false, false);
    }

    @Override
    public void close() {
        image.close();
    }
}

