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
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Toroidal world-space {@code 128x128} {@link AbstractTexture} bound at
 * {@code skycraft:textures/environment/weather_field.png} ({@code uniform sampler2D skycraftWeatherTex} in
 * Complementary Reimagined). A second 512-block grid spans 65,536 blocks for DH;
 * local detail blends into that field before the local toroidal window ends.
 * Matching dynamics textures contain condensation altitude, surface altitude,
 * wind angle and dew-point ground fog. A single worker evaluates complete grids
 * from immutable snapshots; display values ease every tick independently.
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
 *       lightning illumination ({@code 0..1} within each 0.25 band). The low bit
 *       conservatively marks columns that may contain an actual smoke source.</li>
 * </ul>
 */
public final class WeatherFieldTexture extends AbstractTexture {
    public static final ResourceLocation LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/weather_field.png");
    public static final ResourceLocation FAR_LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/weather_field_far.png");
    public static final ResourceLocation DYNAMICS_LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/weather_dynamics.png");
    public static final ResourceLocation FAR_DYNAMICS_LOCATION = new ResourceLocation(Skycraft.MODID, "textures/environment/weather_dynamics_far.png");
    private final int size;
    private final int cell;
    private final ResourceLocation location;

    private static WeatherFieldTexture instance;
    private static WeatherFieldTexture farInstance;
    private final NativeImage image;
    private final NativeImage dynamicsImage;
    private final DynamicTexture dynamicsTexture;
    private final ResourceLocation dynamicsLocation;
    private final float[] baseR;
    private final float[] baseG;
    private final float[] baseB;
    private final int[] baseBand;
    private final boolean[] smokeColumns;
    private final float[] displayR, displayG, displayB;
    private final float[] targetHeight, targetSurface, targetWindX, targetWindZ, targetFog;
    private final float[] displayHeight, displaySurface, displayWindX, displayWindZ, displayFog;
    private final Set<Integer> preparedTextures = new HashSet<>();
    private boolean initialized;
    private int lastCenterCellX = Integer.MIN_VALUE;
    private int lastCenterCellZ = Integer.MIN_VALUE;
    private static final java.util.concurrent.ExecutorService SAMPLER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"Skycraft weather grids"); t.setDaemon(true); return t;
    });
    private record Grid(int x,int z,WeatherPhysics.ColumnState[] columns,double millis) {}
    private java.util.concurrent.CompletableFuture<Grid> pendingGrid;
    private final int[] sampledX=new int[128*128], sampledZ=new int[128*128];
    private ClientLevel trackedLevel;
    private long nextGrid;
    private double workerMillis, presentationMillis;

    private WeatherFieldTexture(ResourceLocation location, int size, int cell) {
        this.location = location;
        this.size = size;
        this.cell = cell;
        image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        dynamicsLocation = location.equals(LOCATION) ? DYNAMICS_LOCATION : FAR_DYNAMICS_LOCATION;
        dynamicsImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        dynamicsTexture = new DynamicTexture(dynamicsImage);
        baseR = new float[size * size];
        baseG = new float[size * size];
        baseB = new float[size * size];
        baseBand = new int[size * size];
        java.util.Arrays.fill(sampledX,Integer.MIN_VALUE);
        smokeColumns = new boolean[size * size];
        displayR = new float[size * size]; displayG = new float[size * size]; displayB = new float[size * size];
        targetHeight = new float[size * size]; targetSurface = new float[size * size];
        targetWindX = new float[size * size]; targetWindZ = new float[size * size]; targetFog = new float[size * size];
        displayHeight = new float[size * size]; displaySurface = new float[size * size];
        displayWindX = new float[size * size]; displayWindZ = new float[size * size]; displayFog = new float[size * size];
        fillDefault();
    }

    public static WeatherFieldTexture get() {
        if (instance == null) {
            instance = new WeatherFieldTexture(LOCATION, WeatherPhysics.FIELD_GRID_SIZE, WeatherPhysics.FIELD_CELL_BLOCKS);
        }
        return instance;
    }

    public static void ensureRegistered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) return;
        if (farInstance == null) farInstance = new WeatherFieldTexture(FAR_LOCATION, WeatherPhysics.FAR_FIELD_GRID_SIZE, WeatherPhysics.FAR_FIELD_CELL_BLOCKS);
        register(mc, get());
        register(mc, farInstance);
    }

    private static void register(Minecraft mc, WeatherFieldTexture tex) {
        AbstractTexture existing = mc.getTextureManager().getTexture(tex.location, null);
        if (existing != tex) mc.getTextureManager().register(tex.location, tex);
        if (mc.getTextureManager().getTexture(tex.dynamicsLocation, null) != tex.dynamicsTexture)
            mc.getTextureManager().register(tex.dynamicsLocation, tex.dynamicsTexture);
    }

    private void fillDefault() {
        int defaultPixel = packAbgr(0.32f, 0.08f, 0.0f, 0);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int idx = z * size + x;
                baseR[idx] = 0.32f;
                baseG[idx] = 0.08f;
                baseB[idx] = 0.0f;
                baseBand[idx] = 0;
                displayR[idx] = baseR[idx]; displayG[idx] = baseG[idx];
                displayHeight[idx] = targetHeight[idx] = 232f;
                displaySurface[idx] = targetSurface[idx] = 63f;
                displayWindX[idx] = targetWindX[idx] = 0.8f;
                displayWindZ[idx] = targetWindZ[idx] = 0.6f;
                image.setPixelRGBA(x, z, defaultPixel);
                dynamicsImage.setPixelRGBA(x, z, (26 << 16) | (16 << 8) | 58);
            }
        }
    }

    @Override
    public void load(ResourceManager manager) throws IOException {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            preparedTextures.clear();
            uploadToGl(getId());
        } else {
            RenderSystem.recordRenderCall(() -> {
                preparedTextures.clear();
                uploadToGl(getId());
            });
        }
    }

    /**
     * Updates a bounded slice of the toroidal weather field on client tick, including first join and teleports,
     * with lightning transported separately by {@link LightningFieldTexture}.
     */
    public static void tickUpdate(ClientLevel level, Vec3 cam, float partialTick) {
        ensureRegistered();
        get().update(level, cam, partialTick);
        farInstance.update(level, cam, partialTick);
    }

    private void update(ClientLevel level, Vec3 cam, float partialTick) {
        int centerCellX = Mth.floor(cam.x / cell);
        int centerCellZ = Mth.floor(cam.z / cell);
        boolean teleported = !initialized
                || Math.abs(centerCellX - lastCenterCellX) > 24
                || Math.abs(centerCellZ - lastCenterCellZ) > 24;

        lastCenterCellX = centerCellX;
        lastCenterCellZ = centerCellZ;

        long presentationStart=System.nanoTime();
        if(trackedLevel!=level) {
            trackedLevel=level; initialized=false; nextGrid=0;
            if(pendingGrid!=null) pendingGrid.cancel(false);
            pendingGrid=null; java.util.Arrays.fill(sampledX,Integer.MIN_VALUE);
        }
        if(pendingGrid!=null && pendingGrid.isDone()) {
            try {
                Grid grid=pendingGrid.join(); workerMillis=grid.millis;
                for(int row=0;row<size;row++) for(int col=0;col<size;col++) {
                    int i=row*size+col;
                    int gx=nearestWorldCell(grid.x,col), gz=nearestWorldCell(grid.z,row);
                    if(gx!=nearestWorldCell(centerCellX,col) || gz!=nearestWorldCell(centerCellZ,row)) continue;
                    var state=grid.columns[i];
                    boolean fresh=sampledX[i]!=gx || sampledZ[i]!=gz;
                    sampledX[i]=gx; sampledZ[i]=gz;
                    int band=aerosolBand(state.aerosolCode());
                    float curtain=state.curtainDensity();
                    if(band==0 || band==1) curtain*=Mth.clamp((state.coverage()-0.28f)/0.38f,0,1);
                    baseR[i]=state.coverage(); baseG[i]=state.stormTower(); baseB[i]=curtain; baseBand[i]=band;
                    targetHeight[i]=232f+state.cloudBaseOffset(); targetSurface[i]=state.surfaceHeight();
                    targetWindX[i]=state.windX(); targetWindZ[i]=state.windZ(); targetFog[i]=state.groundFog();
                    smokeColumns[i]=SmokeSourceTexture.mayContainSmoke((gx+0.5)*cell,(gz+0.5)*cell,cell);
                    // Recycled cells represent different geography, not evolving weather.
                    if(fresh) {
                        displayR[i]=baseR[i]; displayG[i]=baseG[i]; displayB[i]=baseB[i];
                        displayHeight[i]=targetHeight[i]; displaySurface[i]=targetSurface[i];
                        displayWindX[i]=targetWindX[i]; displayWindZ[i]=targetWindZ[i]; displayFog[i]=targetFog[i];
                    }
                }
                initialized=true;
            } catch(java.util.concurrent.CompletionException e) {
                Skycraft.LOGGER.error("Weather grid sampling failed",e);
            }
            pendingGrid=null; nextGrid=System.currentTimeMillis()+750;
        }
        if(pendingGrid==null && (System.currentTimeMillis()>=nextGrid || teleported)) {
            var climate=CloudClimate.snapshot();
            var kinds=new java.util.HashMap<String,WeatherKind>();
            for(String region:ClientWeather.regionIds()) {
                var kind=ClientWeather.kindOf(region); if(kind!=null) kinds.put(region,kind);
            }
            var fronts=ClientWeather.fronts();
            float wx=ClientWeather.windX(), wz=ClientWeather.windZ();
            boolean regional=ClientWeather.active();
            WeatherKind fallback=level.getThunderLevel(partialTick)>0.2f?WeatherKind.STORM
                    :level.getRainLevel(partialTick)>0.2f?WeatherKind.RAIN:WeatherKind.CLEAR;
            double seconds=(level.getGameTime()+partialTick)/20.0;
            long dayTime=level.getDayTime();
            // Immutable inputs only: no ClientLevel, entity, chunk, GL or mutable table reads.
            pendingGrid=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                long started=System.nanoTime();
                var columns=new WeatherPhysics.ColumnState[size*size];
                for(int row=0;row<size;row++) for(int col=0;col<size;col++) {
                    double x=(nearestWorldCell(centerCellX,col)+0.5)*cell;
                    double z=(nearestWorldCell(centerCellZ,row)+0.5)*cell;
                    String region=TamrielMap.region((int)x,(int)z).id();
                    float[] params=regional?blendedParams(climate,region,x,z):null;
                    columns[row*size+col]=WeatherPhysics.evaluate(x,z,seconds,params,
                            regional?kinds.get(region):fallback,wx,wz,fronts,dayTime);
                }
                return new Grid(centerCellX,centerCellZ,columns,(System.nanoTime()-started)*1e-6);
            },SAMPLER);
        }

        // Complete physics snapshots arrive atomically; presentation advances every tick.
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int i = row * size + col;
                displayR[i] = Mth.lerp(0.06f, displayR[i], baseR[i]);
                displayG[i] = Mth.lerp(0.06f, displayG[i], baseG[i]);
                displayB[i] = Mth.lerp(0.06f, displayB[i], baseB[i]);
                displayHeight[i] = Mth.lerp(0.035f, displayHeight[i], targetHeight[i]);
                displaySurface[i] = Mth.lerp(0.15f, displaySurface[i], targetSurface[i]);
                displayWindX[i] = Mth.lerp(0.025f, displayWindX[i], targetWindX[i]);
                displayWindZ[i] = Mth.lerp(0.025f, displayWindZ[i], targetWindZ[i]);
                displayFog[i] = Mth.lerp(0.035f, displayFog[i], targetFog[i]);
                // Lightning has its own 512-byte 3D packet; never rescan both weather grids per flash.
                int rgba = packAbgr(displayR[i], displayG[i], displayB[i], baseBand[i]);
                int alpha = ((rgba >>> 24) & 254) | (smokeColumns[i] ? 1 : 0);
                image.setPixelRGBA(col, row, (rgba & 0x00FFFFFF) | (alpha << 24));
                int windAngle = clampByte((float) (Math.atan2(displayWindZ[i], displayWindX[i]) / (Math.PI * 2.0) + 1.0) % 1f);
                dynamicsImage.setPixelRGBA(col, row, (clampByte(displayFog[i]) << 24) | (windAngle << 16)
                        | (clampByte(displaySurface[i] / 1024f) << 8) | clampByte(displayHeight[i] / 1024f));
            }
        }
        uploadAllTargets();
        presentationMillis=(System.nanoTime()-presentationStart)*1e-6;
    }

    public static String diagnostics() {
        return instance==null?"weather grids pending":"weather_grid_worker_ms="+instance.workerMillis
                +" weather_grid_present_ms="+instance.presentationMillis
                +" far_worker_ms="+(farInstance==null?0:farInstance.workerMillis);
    }

    public static boolean ready() {
        return instance!=null && farInstance!=null && instance.initialized && farInstance.initialized
                && instance.cellReady() && farInstance.cellReady();
    }

    private boolean cellReady() {
        int x=Math.floorMod(lastCenterCellX,size),z=Math.floorMod(lastCenterCellZ,size),i=z*size+x;
        return sampledX[i]==lastCenterCellX && sampledZ[i]==lastCenterCellZ;
    }

    private static final int BLEND = 384;
    private static final int[][] BLEND_AT = {{0, 0}, {BLEND, 0}, {-BLEND, 0}, {0, BLEND}, {0, -BLEND}, {BLEND, BLEND}, {-BLEND, BLEND}, {BLEND, -BLEND}, {-BLEND, -BLEND}};
    private static final float[] BLEND_W = {4f, 2f, 2f, 2f, 2f, 1f, 1f, 1f, 1f};
    private final float[] blendScratch = new float[CloudClimate.COUNT];

    /**
     * The cloud parameters of the region at (x, z) mixed with those of the regions {@link #BLEND} blocks around it, so
     * a region border is a soft gradient rather than a line. Falls back to the plain region (or null) when unknown.
     */
    private float[] blendedParams(Map<String, float[]> climate, String centreId, double x, double z) {
        float[] centre = climate.get(centreId);
        if (centre == null) return null;
        float[] out = blendScratch;
        java.util.Arrays.fill(out, 0f);
        float total = 0f;
        for (int i = 0; i < BLEND_AT.length; i++) {
            float[] p = i == 0 ? centre : climate.get(TamrielMap.region((int) x + BLEND_AT[i][0], (int) z + BLEND_AT[i][1]).id());
            if (p == null) p = centre;
            float w = BLEND_W[i];
            for (int k = 0; k < out.length; k++) out[k] += p[k] * w;
            total += w;
        }
        for (int k = 0; k < out.length; k++) out[k] /= total;
        return out;
    }

    /**
     * Maps a toroidal texel index {@code texel in [0, 127]} to the world cell index closest to {@code centerCell}
     * satisfying {@code Math.floorMod(worldCell, 128) == texel}.
     */
    private int nearestWorldCell(int centerCell, int texel) {
        int base = centerCell - (size / 2);
        int offset = Math.floorMod(texel - base, size);
        return base + offset;
    }

    private static int aerosolBand(float code) {
        if (code >= 0.78f) return 3; // Volcanic ash / blight
        if (code >= 0.55f) return 2; // Desert sand / haboob
        if (code >= 0.26f) return 1; // Snow / blizzard
        return 0;                    // Temperate / marine water cloud & rain
    }

    private static int packAbgr(float cov, float tower, float curtain, int band) {
        int r = clampByte(cov);
        int g = clampByte(tower); // A flash changes radiance, never the cloud's height or density.
        int b = clampByte(curtain);
        float encodedA = (band * 0.25f) + 0.02f;
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
        dynamicsTexture.upload();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getTextureManager() != null) {
            AbstractTexture registered = mc.getTextureManager().getTexture(location, null);
            if (registered != null && registered != this && registered.getId() != getId()) {
                uploadToGl(registered.getId());
            }
        }
    }

    private void uploadToGl(int glId) {
        // Allocate storage once per target, then update texels. Reallocating it every client tick
        // forces unnecessary driver work and can stall the frame on integrated graphics.
        if (preparedTextures.add(glId)) TextureUtil.prepareImage(glId, 0, size, size);
        GlStateManager._bindTexture(glId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        image.upload(0, 0, 0, 0, 0, size, size, true, false, false, false);
    }

    @Override
    public void close() {
        image.close();
        dynamicsTexture.close();
        preparedTextures.clear();
        if (instance == this) instance = null;
        if (farInstance == this) farInstance = null;
    }
}

