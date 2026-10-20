package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.nirncraft.render.client.DataTexture;
import com.nirncraft.NirnCraft;
import com.nirncraft.atmosphere.WeatherFront;
import com.nirncraft.atmosphere.WeatherPhysics;
import com.nirncraft.atmosphere.weather.WeatherKind;
import com.nirncraft.tamriel.TamrielMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Toroidal world-space {@code 128x128} resource texture bound at
 * {@code nirncraft:textures/environment/weather_field.png} ({@code uniform sampler2D nirncraftWeatherTex} in
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
public final class WeatherFieldTexture implements AutoCloseable {
    public static final Identifier LOCATION = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/weather_field.png");
    public static final Identifier FAR_LOCATION = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/weather_field_far.png");
    public static final Identifier DYNAMICS_LOCATION = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/weather_dynamics.png");
    public static final Identifier FAR_DYNAMICS_LOCATION = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/weather_dynamics_far.png");
    private final int size;
    private final int cell;
    private final Identifier location;

    private static WeatherFieldTexture instance;
    private static WeatherFieldTexture farInstance;
    /** Bumped on every upload of the four fields: atlas copies them when it changed (copyForAtlas). */
    private static final java.util.concurrent.atomic.AtomicLong ATLAS_VERSION = new java.util.concurrent.atomic.AtomicLong();
    private final NativeImage image;
    private final NativeImage dynamicsImage;
    private final Identifier dynamicsLocation;
    private final float[] baseR;
    private final float[] baseG;
    private final float[] baseB;
    private final int[] baseBand;
    private final boolean[] smokeColumns;
    private final float[] displayR, displayG, displayB;
    private final float[] targetHeight, targetSurface, targetWindX, targetWindZ, targetFog;
    private final float[] displayHeight, displaySurface, displayWindX, displayWindZ, displayFog;
    private boolean initialized;
    private int lastCenterCellX = Integer.MIN_VALUE;
    private int lastCenterCellZ = Integer.MIN_VALUE;
    private static final java.util.concurrent.ExecutorService SAMPLER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"NirnCraft weather grids"); t.setDaemon(true); return t;
    });
    private static final java.util.concurrent.atomic.AtomicLong STATIC_INPUT_EPOCH=new java.util.concurrent.atomic.AtomicLong();
    private record Grid(Object identity,int x,int z,WeatherPhysics.ColumnState[] columns,double millis,long stencilHits,long stencilMisses) {}
    private final com.nirncraft.atmosphere.WeatherRegionStencil regionStencil;
    private Object samplingIdentity=new Object();
    private long staticInputEpoch=Long.MIN_VALUE,stencilHits,stencilMisses;
    private java.util.concurrent.CompletableFuture<Grid> pendingGrid;
    private final int[] sampledX=new int[128*128], sampledZ=new int[128*128];
    private ClientLevel trackedLevel;
    private long nextGrid;
    private double workerMillis, presentationMillis;

    private WeatherFieldTexture(Identifier location, int size, int cell) {
        this.location = location;
        this.size = size;
        this.cell = cell;
        regionStencil=new com.nirncraft.atmosphere.WeatherRegionStencil(size*size,(x,z)->TamrielMap.region(x,z).id());
        image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        dynamicsLocation = location.equals(LOCATION) ? DYNAMICS_LOCATION : FAR_DYNAMICS_LOCATION;
        dynamicsImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
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

    /**
     * Weather off (visual tests of the world itself): no cloud cover, storm towers, rain curtains or fog banks in the
     * fields the pack and Infinite ATLAS read, at once; the aerosol band stays, so distance still hazes as the air
     * there does. Off by default; the autotest verb `weather-off on|off` switches it.
     */
    private static volatile Boolean testOff;  // the autotest verb's choice, else the client option

    public static void off(boolean on) {
        testOff = on;
    }

    /** Weather drawn nowhere: a visual test's `weather-off on`, or the `weather.visuals` option off. */
    public static boolean isOff() {
        Boolean t = testOff;
        if (t != null) return t;
        try {
            return !com.nirncraft.atmosphere.AtmosphereConfig.WEATHER_VISUALS.get();
        } catch (IllegalStateException e) {
            return false;  // config not loaded yet
        }
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

    private static void register(Minecraft mc, WeatherFieldTexture field) {
        // Keep the original resource objects so Iris and normal renderers observe the same GPU textures.
        mc.getTextureManager().getTexture(field.location);
        mc.getTextureManager().getTexture(field.dynamicsLocation);
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
                image.setPixelABGR(x, z, defaultPixel);
                dynamicsImage.setPixelABGR(x, z, (26 << 16) | (16 << 8) | 58);
            }
        }
    }

    /**
     * Updates a bounded slice of the toroidal weather field on client tick, including first join and teleports,
     * with lightning transported separately by {@link LightningFieldTexture}.
     */
    public static void tickUpdate(ClientLevel level, Vec3 cam, float partialTick) {
        if (isOff() && level != null) {  // no rain or thunder either (the pack's wetness and rain follow these)
            level.setRainLevel(0f);
            level.setThunderLevel(0f);
        }
        try(var uploadState=TextureUploadState.preserve()) {
        ensureRegistered();
        get().update(level, cam, partialTick);
        farInstance.update(level, cam, partialTick);
        }
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
        long epoch=STATIC_INPUT_EPOCH.get();
        if(trackedLevel!=level || staticInputEpoch!=epoch) {
            trackedLevel=level;staticInputEpoch=epoch;samplingIdentity=new Object();
            initialized=false; nextGrid=0;
            if(pendingGrid!=null) pendingGrid.cancel(false);
            pendingGrid=null; java.util.Arrays.fill(sampledX,Integer.MIN_VALUE);
        }
        if(pendingGrid!=null && pendingGrid.isDone()) {
            nextGrid=System.currentTimeMillis()+750;
            try {
                Grid grid=pendingGrid.join();
                if(grid.identity==samplingIdentity) {
                workerMillis=grid.millis;
                stencilHits=grid.stencilHits;stencilMisses=grid.stencilMisses;
                for(int row=0;row<size;row++) for(int col=0;col<size;col++) {
                    int i=row*size+col;
                    int gx=nearestWorldCell(grid.x,col), gz=nearestWorldCell(grid.z,row);
                    if(gx!=nearestWorldCell(centerCellX,col) || gz!=nearestWorldCell(centerCellZ,row)) continue;
                    var state=grid.columns[i];
                    if(this==instance && gx==centerCellX && gz==centerCellZ) eyeColumn=state;  // the air at the eye (atlas's sky)
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
                } else nextGrid=0;
            } catch(java.util.concurrent.CompletionException e) {
                NirnCraft.LOGGER.error("Weather grid sampling failed",e);
            }
            pendingGrid=null;
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
            long dayTime=level.getOverworldClockTime();
            Object identity=samplingIdentity;
            // Immutable inputs only: no ClientLevel, entity, chunk, GL or mutable table reads.
            pendingGrid=java.util.concurrent.CompletableFuture.supplyAsync(()->{
                long started=System.nanoTime();
                var columns=new WeatherPhysics.ColumnState[size*size];
                for(int row=0;row<size;row++) for(int col=0;col<size;col++) {
                    double x=(nearestWorldCell(centerCellX,col)+0.5)*cell;
                    double z=(nearestWorldCell(centerCellZ,row)+0.5)*cell;
                    int slot=row*size+col;
                    String region=regional?regionStencil.region(identity,slot,(int)x,(int)z,0):null;
                    float[] params=regional?regionStencil.blend(identity,slot,(int)x,(int)z,climate,blendScratch):null;
                    columns[row*size+col]=WeatherPhysics.evaluate(x,z,seconds,params,
                            regional?kinds.get(region):fallback,wx,wz,fronts,dayTime);
                }
                return new Grid(identity,centerCellX,centerCellZ,columns,(System.nanoTime()-started)*1e-6,
                        regionStencil.hits(),regionStencil.misses());
            },SAMPLER);
        }

        // Complete physics snapshots arrive atomically; presentation advances every tick.
        boolean off = isOff();  // once per tick, not per cell: a config read each
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
                if (off) {  // world-gen views: the air's haze band only
                    displayR[i] = displayG[i] = displayB[i] = displayFog[i] = 0f;
                }
                // Lightning has its own 512-byte 3D packet; never rescan both weather grids per flash.
                int rgba = packAbgr(displayR[i], displayG[i], displayB[i], baseBand[i]);
                int alpha = ((rgba >>> 24) & 254) | (smokeColumns[i] ? 1 : 0);
                image.setPixelABGR(col, row, (rgba & 0x00FFFFFF) | (alpha << 24));
                int windAngle = clampByte((float) (Mth.atan2(displayWindZ[i], displayWindX[i]) / (Math.PI * 2.0) + 1.0) % 1f);
                dynamicsImage.setPixelABGR(col, row, (clampByte(displayFog[i]) << 24) | (windAngle << 16)
                        | (clampByte(displaySurface[i] / 1024f) << 8) | clampByte(displayHeight[i] / 1024f));
            }
        }
        uploadAllTargets();
        presentationMillis=(System.nanoTime()-presentationStart)*1e-6;
    }

    public static String diagnostics() {
        return instance==null?"weather grids pending":"weather_grid_worker_ms="+instance.workerMillis
                +" weather_grid_present_ms="+instance.presentationMillis
                +" far_worker_ms="+(farInstance==null?0:farInstance.workerMillis)
                +" weather_stencil_hits="+instance.stencilHits+" weather_stencil_misses="+instance.stencilMisses
                +" far_stencil_hits="+(farInstance==null?0:farInstance.stencilHits)
                +" far_stencil_misses="+(farInstance==null?0:farInstance.stencilMisses);
    }

    public static boolean ready() {
        return instance!=null && farInstance!=null && instance.initialized && farInstance.initialized
                && instance.cellReady() && farInstance.cellReady();
    }

    private boolean cellReady() {
        int x=Math.floorMod(lastCenterCellX,size),z=Math.floorMod(lastCenterCellZ,size),i=z*size+x;
        return sampledX[i]==lastCenterCellX && sampledZ[i]==lastCenterCellZ;
    }

    private final float[] blendScratch = new float[CloudClimate.COUNT];

    /** Static region stencils belong to a world/resource epoch; no dynamic weather values are cached. */
    public static void invalidateStaticInputs() { STATIC_INPUT_EPOCH.incrementAndGet(); }

    /**
     * Maps a toroidal texel index {@code texel in [0, 127]} to the world cell index closest to {@code centerCell}
     * satisfying {@code Math.floorMod(worldCell, 128) == texel}.
     */
    private int nearestWorldCell(int centerCell, int texel) {
        int base = centerCell - (size / 2);
        int offset = Math.floorMod(texel - base, size);
        return base + offset;
    }

    /** The column the camera stands in, as last sampled (temperature, humidity, aerosol code: Infinite ATLAS's air), or null. */
    public static WeatherPhysics.ColumnState eyeColumn() {
        return eyeColumn;
    }

    private static volatile WeatherPhysics.ColumnState eyeColumn;

    public static int aerosolBand(float code) {
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

    /**
     * atlas's weather feed (com.nirncraft.atlas.AtlasWeatherFeed): copies the four fields (RGBA8, r in the low byte,
     * which is NativeImage's ABGR int) into the arrays when they changed since version `since`. Returns the version
     * now held (`since` when nothing was copied). Render thread.
     */
    public static long copyForAtlas(long since, int[] nearField, int[] nearDyn, int[] farField, int[] farDyn) {
        long v = ATLAS_VERSION.get();
        WeatherFieldTexture n = instance, f = farInstance;
        if (v == since || n == null || f == null) return since;
        int[][] src = {n.image.getPixelsABGR(), n.dynamicsImage.getPixelsABGR(), f.image.getPixelsABGR(), f.dynamicsImage.getPixelsABGR()};
        int[][] dst = {nearField, nearDyn, farField, farDyn};
        for (int k = 0; k < 4; k++) System.arraycopy(src[k], 0, dst[k], 0, Math.min(src[k].length, dst[k].length));
        return v;
    }
    private static int clampByte(float v) {
        int i = Math.round(Mth.clamp(v, 0.0f, 1.0f) * 255.0f);
        return Math.max(0, Math.min(255, i));
    }

    private void uploadAllTargets() {
        try (var uploadState = TextureUploadState.preserve()) {
            DataTexture.write(location, image);
            DataTexture.write(dynamicsLocation, dynamicsImage);
            ATLAS_VERSION.incrementAndGet();
        }
    }

    @Override
    public void close() {
        image.close();
        dynamicsImage.close();
        if (instance == this) instance = null;
        if (farInstance == this) farInstance = null;
    }
}

