package com.skycraft.fire.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.fire.FireConfig;
import com.skycraft.fire.FireSites;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws fire sites far away: a stack of animated flame sprites, an additive glow and a rising smoke column per site.
 *
 * <ul>
 *   <li><b>Independent of particles.</b> Everything is camera-facing quads built here, so particle culling distance,
 *   particle limits and the "particles: minimal" setting do not matter. Cost: a few hundred quads per frame, three draw
 *   calls (one per render type), no allocation in steady state.</li>
 *   <li><b>Shader packs.</b> The render types are vanilla's own entity shaders ({@link FireRenderTypes}), which
 *   Oculus/Iris map to the pack's {@code gbuffers_spidereyes} and {@code gbuffers_entities_translucent} programs; the
 *   quads are submitted through a vertex consumer like any entity, after the particle stage and after translucent
 *   terrain, with depth testing on against the terrain (and Distant Horizons' LODs) and no depth writes. With no
 *   shader pack the fog is switched off for the draw (flames are beacons) and restored afterwards.</li>
 *   <li><b>Distance.</b> Sprites are enlarged so they never shrink below about twelve pixels, and are kept inside the
 *   projection's far plane. Beyond {@code distance} (config) nothing is drawn.</li>
 *   <li><b>Near field.</b> Sites that stand on real big fire blocks fade their flame sprites out within 20-60 blocks
 *   (the blocks take over); the smoke column stays.</li>
 * </ul>
 */
public final class FarFireRenderer {
    private record Frame(com.skycraft.client.CapturedGeometry smoke,com.skycraft.client.CapturedGeometry glow,com.skycraft.client.CapturedGeometry flame) {}
    private static final net.minecraft.util.context.ContextKey<Frame> FRAME=new net.minecraft.util.context.ContextKey<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft","far_fire"));

    private static final int MAX_PUFFS = 2400;
    private static final float[] PUFF = new float[MAX_PUFFS * 8];
    private static final int[] ORDER = new int[MAX_PUFFS];
    private static final int[] BUCKET = new int[258];
    private static int puffs;

    private static final List<ClientFire.View> CANDIDATES = new ArrayList<>();
    private static final List<Double> DIST = new ArrayList<>();

    // diagnostics (read by /skycraft-fire stats on the client side and the autotest)
    static int lastSites, lastQuads;
    static long lastNanos;

    private FarFireRenderer() {}

    public static String stats() {
        return "far fire: " + lastSites + " sites, " + lastQuads + " quads, " + String.format(java.util.Locale.ROOT, "%.3f", lastNanos / 1e6) + " ms"
                +" sharedSmoke="+ShaderCheck.sharedSmoke()+" volumeSources="+com.skycraft.atmosphere.client.SmokeSourceTexture.activeSources();
    }

    public static void extract(net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = event.getLevel();
        event.getRenderState().setRenderData(FRAME,null);
        if (level == null || !FireConfig.FAR_FIRE.get() || mc.player == null) return;
        Camera cam = event.getCamera();
        if (cam.getFluidInCamera() != FogType.NONE) return;
        if (cam.entity() instanceof net.minecraft.world.entity.LivingEntity le
                && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS))) return;
        long t0 = System.nanoTime();

        Vec3 cp = cam.position();
        float pt = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double time = level.getGameTime() + pt;
        double far = event.getRenderState().cameraRenderState.depthFar - 24.0;
        double maxDist = Math.min(FireConfig.FAR_FIRE_DISTANCE.get(), far);
        Vector3f look = new Vector3f(cam.forwardVector());
        Vector3f left = new Vector3f(cam.leftVector());
        Vector3f up = new Vector3f(cam.upVector());
        // vertical billboards turn about y only: their right vector is the camera's left, flattened
        float hx = left.x(), hz = left.z();
        float hl = Mth.sqrt(hx * hx + hz * hz);
        if (hl < 1.0e-3f) {
            hx = 1f;
            hz = 0f;
        } else {
            hx /= hl;
            hz /= hl;
        }
        float angle = cam.attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE,pt) * Mth.DEG_TO_RAD;
        float daylight = Mth.clamp(Mth.cos(angle) * 2f + 0.3f, 0f, 1f);
        float rain = level.getRainLevel(pt);
        daylight *= 1f - 0.5f * rain;

        collect(cp, look, maxDist, FireConfig.FAR_FIRE_MAX_SITES.get(), time);
        if (CANDIDATES.isEmpty()) {
            lastSites = 0;
            lastQuads = 0;
            return;
        }
        Matrix4f m=new Matrix4f();
        var smokeVertices=new com.skycraft.client.CapturedGeometry.Builder();
        var glowVertices=new com.skycraft.client.CapturedGeometry.Builder();
        var flameVertices=new com.skycraft.client.CapturedGeometry.Builder();
        int quads=0;
            puffs = 0;
            int n = CANDIDATES.size();
            // ---- smoke (sorted far to near over all sites), then glow and flames (additive, order does not matter)
            boolean smoke = FireConfig.FAR_SMOKE.get();
            if (smoke) {
                boolean shared=ShaderCheck.sharedSmoke();
                for (int i = 0; i < n; i++) {
                    var site=CANDIDATES.get(i);
                    if(!shared || site.blast || !com.skycraft.atmosphere.client.SmokeSourceTexture.containsFire(site.x,site.z))
                        smokeOf(site, DIST.get(i), cp, time, daylight, maxDist);
                }
                quads += emitPuffs(smokeVertices,m, cp, left, up, maxDist);
            }
            for(int i=0;i<n;i++)quads+=glowOf(glowVertices,m,cp,left,up,CANDIDATES.get(i),DIST.get(i),time,daylight);
            for(int i=0;i<n;i++)quads+=flamesOf(flameVertices,m,cp,hx,hz,CANDIDATES.get(i),DIST.get(i),time);
        event.getRenderState().setRenderData(FRAME,new Frame(smokeVertices.build(),glowVertices.build(),flameVertices.build()));
        lastSites = CANDIDATES.size();
        lastQuads = quads;
        lastNanos = System.nanoTime() - t0;
    }

    public static void submit(net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent event){
        var frame=event.getLevelRenderState().getRenderData(FRAME);if(frame==null)return;
        var collector=event.getSubmitNodeCollector();var pose=event.getPoseStack();
        frame.smoke().submit(pose,collector,FireRenderTypes.SMOKE);
        frame.glow().submit(pose,collector,FireRenderTypes.GLOW);
        frame.flame().submit(pose,collector,FireRenderTypes.FLAME);
    }

    // ------------------------------------------------------------------ candidates

    private static void collect(Vec3 cp, Vector3f look, double maxDist, int maxSites, double time) {
        ClientFire.ensureTemporaryLevel();
        CANDIDATES.clear();
        DIST.clear();
        if (ClientFire.serverCurrent()) {
            synchronized (ClientFire.SERVER) {
                for (ClientFire.View v : ClientFire.SERVER) consider(v, cp, look, maxDist, time);
            }
        }
        synchronized (ClientFire.TEMP) {
            for (ClientFire.View v : ClientFire.TEMP) consider(v, cp, look, maxDist, time);
        }
        if (CANDIDATES.size() > maxSites) {
            // keep the nearest
            Integer[] idx = new Integer[CANDIDATES.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(DIST.get(a), DIST.get(b)));
            List<ClientFire.View> keep = new ArrayList<>(maxSites);
            List<Double> keepD = new ArrayList<>(maxSites);
            for (int i = 0; i < maxSites; i++) {
                keep.add(CANDIDATES.get(idx[i]));
                keepD.add(DIST.get(idx[i]));
            }
            CANDIDATES.clear();
            CANDIDATES.addAll(keep);
            DIST.clear();
            DIST.addAll(keepD);
        }
    }

    private static void consider(ClientFire.View v, Vec3 cp, Vector3f look, double maxDist, double time) {
        double dx = v.x - cp.x, dy = v.y - cp.y, dz = v.z - cp.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > maxDist) return;
        if (d > 24) {
            double dot = (dx * look.x() + dy * look.y() + dz * look.z()) / d;
            // a smoke column leans over its site, so allow sites a bit outside the view
            if (dot < -0.25) return;
        }
        if (envelope(v, time) <= 0.01f) return;
        CANDIDATES.add(v);
        DIST.add(d);
    }

    static float envelope(ClientFire.View v, double time) {
        if (!v.temporary) return (float) Math.max(0.0, Math.min(1.0, (time - v.born) / 20.0));
        double age = time - v.born, left = v.ends - time;
        float in = (float) Math.min(1.0, age / Math.max(1, v.fade));
        float out = (float) Math.min(1.0, left / Math.max(1, v.fade));
        return Math.max(0f, Math.min(in, out));
    }

    // ------------------------------------------------------------------ flames and glow

    private static int flamesOf(VertexConsumer c, Matrix4f m, Vec3 cp, float hx, float hz, ClientFire.View v, double d, double time) {
        if ((v.flags & FireSites.FLAG_SMOKE_ONLY) != 0) return 0;
        float env = envelope(v, time);
        float near = (v.flags & FireSites.FLAG_BLOCKS) != 0 ? Mth.clamp((float) (d - 20.0) / 40f, 0f, 1f) : 1f;
        float a = env * near;
        if (a <= 0.01f) return 0;
        float k = (float) Mth.clamp(0.55 + 0.45 * v.intensity, 0.4, 1.7);
        float h = v.height * k;
        if (v.blast) {
            float t = (float) ((time - v.born) / Math.max(1.0, v.ends - v.born));
            h = v.radius * (0.8f + 1.4f * Mth.clamp(t * 1.6f, 0f, 1f));
        }
        h = (float) Math.max(h, d * 0.011);
        float w = h * 0.62f;
        float alpha = a * Mth.clamp(0.6f + 0.4f * v.intensity, 0.35f, 1.0f);
        double bx = v.x - cp.x, by = v.y - cp.y, bz = v.z - cp.z;
        int quads = 0;
        // three tongues: the main one and two smaller ones either side, each on its own frame of the animation
        for (int i = 0; i < 3; i++) {
            float side = i == 0 ? 0f : (i == 1 ? -0.34f : 0.31f);
            float scale = i == 0 ? 1.0f : (i == 1 ? 0.74f : 0.66f);
            int frame = (int) ((time * 0.45) + v.id * 3 + i * 3) & 7;
            double cx = bx + hx * w * side, cz = bz + hz * w * side;
            // a little sway
            double sway = Math.sin(time * 0.11 + v.id + i * 2.0) * w * 0.05;
            standing(c, m, cx + hx * sway, by, cz + hz * sway, hx * w * scale * 0.5f, hz * w * scale * 0.5f, h * scale,
                    frame / 8f, (frame + 1) / 8f, 1f, 1f, 1f, alpha, LightCoordsUtil.FULL_BRIGHT);
            quads++;
        }
        return quads;
    }

    private static int glowOf(VertexConsumer c, Matrix4f m, Vec3 cp, Vector3f left, Vector3f up, ClientFire.View v, double d,
                              double time, float daylight) {
        if ((v.flags & FireSites.FLAG_SMOKE_ONLY) != 0) return 0;
        float env = envelope(v, time);
        if (env <= 0.01f) return 0;
        float k = (float) Mth.clamp(0.55 + 0.45 * v.intensity, 0.4, 1.7);
        float h = v.blast ? v.radius * 1.6f : v.height * k;
        float size = (float) (h * 2.4 + Math.max(0.0, d * 0.012));
        // brighter at night; a steady pulse
        float pulse = 0.88f + 0.12f * (float) Math.sin(time * 0.17 + v.id * 1.3);
        float a = env * pulse * Mth.clamp(0.20f * v.intensity + 0.07f, 0.06f, 0.5f) * (1.15f - 0.55f * daylight);
        float near = (v.flags & FireSites.FLAG_BLOCKS) != 0 ? Mth.clamp((float) (d - 10.0) / 40f, 0.25f, 1f) : 1f;
        a *= near;
        if (a <= 0.005f) return 0;
        double x = v.x - cp.x, y = v.y - cp.y + h * 0.45, z = v.z - cp.z;
        quad(c, m, x, y, z, left.x() * size * 0.5f, left.y() * size * 0.5f, left.z() * size * 0.5f,
                up.x() * size * 0.5f, up.y() * size * 0.5f, up.z() * size * 0.5f, 0f, 0f, 1f, 1f, 1f, 0.85f, 0.55f, a, LightCoordsUtil.FULL_BRIGHT);
        return 1;
    }

    // ------------------------------------------------------------------ smoke

    private static void smokeOf(ClientFire.View v, double d, Vec3 cp, double time, float daylight, double maxDist) {
        if (v.smoke <= 0 && !v.blast) return;
        float env = envelope(v, time);
        if (env <= 0.01f) return;
        int smoke = Math.max(1, v.smoke);
        int n = 12 + 10 * smoke;
        // the column: starts above the flames, rises 20 / 40 / 60 blocks and leans downwind
        float base = com.skycraft.fire.FirePlume.base(v.height);
        float rise = com.skycraft.fire.FirePlume.height(v.height,smoke);
        if (v.blast) rise = 10f + v.radius * 3f;
        double speed = v.blast ? 0.020 : 0.0045 + 0.0012 * smoke;
        float plumeRadius=com.skycraft.fire.FirePlume.radius(v.height,v.intensity);
        float windX=com.skycraft.atmosphere.client.ClientWeather.windX(),windZ=com.skycraft.atmosphere.client.ClientWeather.windZ();
        double tNear = Math.max(1.0, d);
        for (int i = 0; i < n && puffs < MAX_PUFFS; i++) {
            double u = (time * speed + (double) i / n + (v.id * 0.37)) % 1.0;
            if (v.blast) u = Math.min(0.999, (time - v.born) * speed * 1.6 + (double) i / n * 0.35);
            float uf = (float) u;
            float size = v.blast?v.radius*(0.7f+1.9f*uf):plumeRadius*2f*(0.25f+1.6f*Mth.sqrt(uf));
            size = (float) Math.max(size, tNear * 0.011);
            float drift=uf*rise*(0.18f+0.45f*uf);
            double x = v.x + windX*drift + Math.sin(time * 0.021 + i * 1.7 + v.id) * size * 0.12;
            double y = v.y + base + uf * rise;
            double z = v.z + windZ*drift + Math.cos(time * 0.017 + i * 2.3) * size * 0.12;
            float fadeIn = Mth.clamp(uf / 0.10f, 0f, 1f);
            // Optical alpha scales with slice length. More overlapping slices
            // form a connected soft column without turning it into a black wall.
            float sliceAlpha=1f-(float)Math.exp(-0.03f*(0.5f+0.5f*smoke)*Math.min(2f,v.intensity)*rise/n);
            float a = env * fadeIn * (float) Math.pow(1.0 - uf, 0.9) * sliceAlpha;
            if (a <= 0.0005f) continue;
            double dx = x - cp.x, dy = y - cp.y, dz = z - cp.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int p = puffs++ * 8;
            PUFF[p] = (float) dist;
            PUFF[p + 1] = (float) dx;
            PUFF[p + 2] = (float) dy;
            PUFF[p + 3] = (float) dz;
            PUFF[p + 4] = size;
            PUFF[p + 5] = (float) (i * 1.9 + v.id + time * 0.004 * (i % 2 == 0 ? 1 : -1));
            PUFF[p + 6] = a;
            PUFF[p + 7] = uf + (i % 4) * 8f;   // variant in the integer part / 8, height fraction in the remainder
        }
    }

    private static int emitPuffs(VertexConsumer c,Matrix4f m, Vec3 cp, Vector3f left, Vector3f up, double maxDist) {
        int n = puffs;
        if (n == 0) return 0;
        // counting sort by distance, far first
        java.util.Arrays.fill(BUCKET, 0);
        double span = maxDist + 120.0;
        for (int i = 0; i < n; i++) {
            int b = Mth.clamp((int) (PUFF[i * 8] / span * 255.0), 0, 255);
            BUCKET[b + 1]++;
        }
        for (int i = 1; i < BUCKET.length; i++) BUCKET[i] += BUCKET[i - 1];
        for (int i = 0; i < n; i++) {
            int b = Mth.clamp((int) (PUFF[i * 8] / span * 255.0), 0, 255);
            ORDER[BUCKET[b]++] = i;
        }
        int quads = 0;
        for (int k = n - 1; k >= 0; k--) {
            int p = ORDER[k] * 8;
            float size = PUFF[p + 4], rot = PUFF[p + 5], a = PUFF[p + 6];
            float uf = PUFF[p + 7] % 8f;
            int variant = (int) (PUFF[p + 7] / 8f) & 3;
            float cs = Mth.cos(rot) * size * 0.5f, sn = Mth.sin(rot) * size * 0.5f;
            // rotate the camera's left/up pair by rot
            float rx = left.x() * cs - up.x() * sn, ry = left.y() * cs - up.y() * sn, rz = left.z() * cs - up.z() * sn;
            float ux = left.x() * sn + up.x() * cs, uy = left.y() * sn + up.y() * cs, uz = left.z() * sn + up.z() * cs;
            // hot near the flames, cool grey above; lit by the sky light and, low down, by the fire itself
            float heat = Mth.clamp(1f - uf * 3.2f, 0f, 1f);
            float r = Mth.lerp(heat, 0.46f, 1.0f), g = Mth.lerp(heat, 0.44f, 0.62f), b = Mth.lerp(heat, 0.43f, 0.34f);
            int block = Math.round(Mth.lerp(Mth.clamp(uf * 2.0f, 0f, 1f), 13f, 1f));
            int light = LightCoordsUtil.pack(block, 15);
            quad(c, m, PUFF[p + 1], PUFF[p + 2], PUFF[p + 3], rx, ry, rz, ux, uy, uz, variant / 4f, 0f, (variant + 1) / 4f, 1f, r, g, b, a, light);
            quads++;
        }
        return quads;
    }

    // ------------------------------------------------------------------ geometry

    /** A camera-facing quad centred at (cx, cy, cz) (relative to the camera); (r) and (u) are the half extents. */
    private static void quad(VertexConsumer c, Matrix4f m, double cx, double cy, double cz, float rx, float ry, float rz,
                             float ux, float uy, float uz, float u0, float v0, float u1, float v1, float r, float g, float b, float a,
                             int light) {
        float x = (float) cx, y = (float) cy, z = (float) cz;
        vertex(c, m, x - rx - ux, y - ry - uy, z - rz - uz, u0, v1, r, g, b, a, light);
        vertex(c, m, x + rx - ux, y + ry - uy, z + rz - uz, u1, v1, r, g, b, a, light);
        vertex(c, m, x + rx + ux, y + ry + uy, z + rz + uz, u1, v0, r, g, b, a, light);
        vertex(c, m, x - rx + ux, y - ry + uy, z - rz + uz, u0, v0, r, g, b, a, light);
    }

    /** A vertical sprite standing on (cx, cy, cz) with half width (rx, rz) along the ground and the given height. */
    private static void standing(VertexConsumer c, Matrix4f m, double cx, double cy, double cz, float rx, float rz, float height,
                                 float u0, float u1, float r, float g, float b, float a, int light) {
        float x = (float) cx, y = (float) cy, z = (float) cz;
        vertex(c, m, x - rx, y, z - rz, u0, 1f, r, g, b, a, light);
        vertex(c, m, x + rx, y, z + rz, u1, 1f, r, g, b, a, light);
        vertex(c, m, x + rx, y + height, z + rz, u1, 0f, r, g, b, a, light);
        vertex(c, m, x - rx, y + height, z - rz, u0, 0f, r, g, b, a, light);
    }

    private static void vertex(VertexConsumer c, Matrix4f m, float x, float y, float z, float u, float v, float r, float g, float b,
                               float a, int light) {
        c.addVertex(m, x, y, z).setColor(r, g, b, a).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(0f, 1f, 0f);
    }

    /** Whether a shader pack is in use (Oculus / Iris API by reflection; any failure means no). */
    static final class ShaderCheck {
        private static boolean unavailable;
        private static Object api;
        private static java.lang.reflect.Method inUse;
        private static long last;
        private static boolean cached;
        private static boolean checked;
        private static String packName="";
        private static long packModified=Long.MIN_VALUE;
        private static boolean shared;
        private static long nextSharedCheck;

        private ShaderCheck() {}

        static boolean sharedSmoke() {
            if(!shadersActive()) return false;
            long now=net.minecraft.util.Util.getMillis();
            if(now<nextSharedCheck) return shared;
            nextSharedCheck=now+1000;
            try {
                Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
                String name=(String)iris.getMethod("getCurrentPackName").invoke(null);
                var path=Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks").resolve(name);
                long modified=java.nio.file.Files.getLastModifiedTime(path).toMillis();
                if(!name.equals(packName) || modified!=packModified) {
                    packName=name; packModified=modified; shared=false;
                    if(java.nio.file.Files.isRegularFile(path)) try(var zip=new java.util.zip.ZipFile(path.toFile())) {
                        shared=zip.getEntry("shaders/lib/atmospherics/clouds/skycraftDensity.glsl")!=null;
                    }
                }
                return shared;
            } catch(ReflectiveOperationException | java.io.IOException e) { return false; }
        }

        static boolean shadersActive() {
            if (unavailable) return false;
            long now = net.minecraft.util.Util.getMillis();
            if (checked && now - last < 1000L) return cached;
            checked = true;
            last = now;
            try {
                if (api == null) {
                    Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = c.getMethod("getInstance").invoke(null);
                    inUse = c.getMethod("isShaderPackInUse");
                    if (api == null) {
                        unavailable = true;
                        return false;
                    }
                }
                cached = Boolean.TRUE.equals(inUse.invoke(api));
            } catch (Throwable t) {
                unavailable = true;
                cached = false;
            }
            return cached;
        }
    }
}
