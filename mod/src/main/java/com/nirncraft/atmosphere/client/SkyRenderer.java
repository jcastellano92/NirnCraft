package com.nirncraft.atmosphere.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.nirncraft.NirnCraft;
import com.nirncraft.atmosphere.AtmosphereConfig;
import com.nirncraft.atmosphere.AtmosphereMath;
import com.nirncraft.render.client.CpuMesh;
import com.nirncraft.render.client.WorldDraw;
import com.nirncraft.render.client.WorldMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Authored night sky. World reads happen during extraction; the native draw pass consumes a frozen frame. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class SkyRenderer {
    private static final Identifier MASSER = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/masser_phases.png");
    private static final Identifier SECUNDA = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/secunda_phases.png");
    private static final Identifier MASSER_HALO = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/masser_halo.png");
    private static final Identifier GALAXY = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/galaxy.png");
    private static final Identifier STAR = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "textures/environment/star.png");

    /** Secunda's orbit: inclined to Masser's and running a little ahead of it. */
    private static final float SECUNDA_TILT = 24.0F;
    private static final float SECUNDA_LEAD = 31.0F;
    private static final float SECUNDA_SIZE = 7.0F;
    private static final float MASSER_SIZE = 20.0F;
    private static final float HALO_SIZE = 36.0F;
    private static final double AURORA_KEY_SECONDS = 6.0;
    /** The Serpent drifts once around the sky in this many days, out of step with the other stars. */
    private static final double SERPENT_PERIOD_DAYS = 27.0;

    // ---- the current shooting star (all primitives: nothing is allocated per frame)
    private static final java.util.Random METEOR_RNG = new java.util.Random();
    private static double nextMeteorAt = -1.0;
    private static double meteorStart = -10.0;
    private static double meteorDuration;
    private static double meteorSpeed;      // radians per second
    private static double meteorTail;       // radians
    private static double mSx, mSy, mSz;     // start direction
    private static double mTx, mTy, mTz;     // travel direction (tangent at the start)

    /** 0..1, continuous latitude/climate factor for the aurora (smoothed by {@link AtmosphereClientEvents}). */
    static float coldness;
    static float coldnessPrev;

    private SkyRenderer() {}

    public static float latitudeFactor(double blockZ) {
        return AtmosphereMath.latitudeFactor(blockZ);
    }

    public static float auroraElevationMinDeg(double blockZ) {
        return AtmosphereMath.auroraElevationMinDeg(blockZ);
    }

    public static float auroraElevationMaxDeg(double blockZ) {
        return AtmosphereMath.auroraElevationMaxDeg(blockZ);
    }

    enum Kind { VEIL, STARS, GALAXY, CONSTELLATIONS, SERPENT, MOON, MOON_DAY, HALO, AURORA, METEOR }
    // Matrices, tints and meteor vertices are private immutable frame snapshots; GPU buffers are resolved at draw time.
    record Draw(Kind kind, int index, int key, Matrix4f local, Vector4f tint,
                        Identifier texture, float[] vertices) {}
    private record Frame(List<Draw> draws) {}
    private static final ContextKey<Frame> FRAME = new ContextKey<>(id("sky_frame"));
    private static final WorldDraw.Material COLOR = WorldDraw.material(id("sky_color"),
            DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ADDITIVE, true, false, true);
    private static final WorldDraw.Material TEXTURED = WorldDraw.material(id("sky_texture"),
            DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ADDITIVE, true, false, true);
    private static final WorldDraw.Material SILHOUETTE = WorldDraw.material(id("sky_silhouette"),
            DefaultVertexFormat.POSITION_TEX_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ALPHA, true, false, true);
    private static final WorldDraw.Material VEIL = WorldDraw.material(id("sky_veil"),
            DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.QUADS, WorldDraw.Blend.ALPHA, true, false, true);
    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath(NirnCraft.MODID, name); }

    @SubscribeEvent public static void pipelines(RegisterRenderPipelinesEvent event) {
        for (var material : new WorldDraw.Material[]{COLOR, TEXTURED, SILHOUETTE, VEIL}) material.register(event);
    }

    @SubscribeEvent public static void extract(ExtractLevelRenderStateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = event.getLevel();
        if (mc.player == null || level.dimension() != Level.OVERWORLD
                || level.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD || !AtmosphereConfig.SKY.get()) return;
        boolean shadersActive = ShaderCompat.shadersActive();
        if (AtmosphereConfig.SKIP_WITH_SHADERS.get() && shadersActive) return;
        var camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE || mc.player.hasEffect(com.nirncraft.platform.Effects.holder(MobEffects.BLINDNESS))
                || mc.player.hasEffect(com.nirncraft.platform.Effects.holder(MobEffects.DARKNESS)) || mc.gui.hud.getBossOverlay().shouldCreateWorldFog()) return;
        float pt = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float sunCycle = camera.attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, pt) / 360f;
        float night = Mth.clamp(camera.attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.STAR_BRIGHTNESS, pt) * 2f, 0f, 1f);
        float overcast = ClientWeather.active() && AtmosphereConfig.WEATHER_SKY.get() ? ClientWeather.clouds() : 0f;
        float clear = shadersActive ? 1f : 1f - overcast * .72f;
        double camZ = camera.position().z;
        double seconds = (level.getGameTime() + pt) / 20.0;
        double dayTime = level.getOverworldClockTime() + pt;
        List<Draw> draws = new ArrayList<>(48);
        PoseStack pose = new PoseStack();
        float veil = overcast * (1f - .5f * level.getRainLevel(pt));
        if (veil > .01f && !shadersActive) {
            float day = Mth.clamp(Mth.cos(sunCycle * Mth.TWO_PI) * 2f + .5f, 0f, 1f);
            float lift = .85f + .15f * day;
            draws.add(new Draw(Kind.VEIL, 0, 0, new Matrix4f(),
                    new Vector4f(ClientWeather.fogR * lift, ClientWeather.fogG * lift, ClientWeather.fogB * lift, Math.min(.94f, veil)), null, null));
        }
        if (clear > .01f) {
            float starAlpha = NightSkyState.visibility * clear;
            if (starAlpha > .003f && !shadersActive) {
                pose.pushPose();
                pose.rotate(Axis.YP.rotationDegrees(-90f));
                pose.rotate(Axis.ZP.rotationDegrees(AtmosphereMath.starDomeTiltDeg(camZ)));
                pose.rotate(Axis.XP.rotationDegrees(sunCycle * 360f));
                if (AtmosphereConfig.GALAXY.get()) add(draws, Kind.GALAXY, 0, 0, pose.last().pose(), GALAXY, starAlpha * .24f);
                if (AtmosphereConfig.STARS.get()) for (int i = 0; i < SkyGeometry.starLayers.length; i++) {
                    float twinkle = .86f + .14f * (float)Math.sin(seconds * (.75 + i * .137) + i * 2.41);
                    add(draws, Kind.STARS, i, 0, pose.last().pose(), STAR, starAlpha * twinkle);
                }
                if (AtmosphereConfig.CONSTELLATIONS.get()) {
                    add(draws, Kind.CONSTELLATIONS, 0, 0, pose.last().pose(), STAR, starAlpha * .55f);
                    double days = dayTime / 24000.0;
                    pose.pushPose();
                    pose.rotate(Axis.XP.rotationDegrees((float)(days / SERPENT_PERIOD_DAYS * 360.0 % 360.0)));
                    pose.rotate(Axis.ZP.rotationDegrees((float)Math.sin(days * .7) * 18f));
                    add(draws, Kind.SERPENT, 0, 0, pose.last().pose(), STAR, starAlpha * .6f);
                    pose.popPose();
                }
                pose.popPose();
            }
            pose.pushPose();
            pose.rotate(Axis.YP.rotationDegrees(-90f));
            pose.rotate(Axis.XP.rotationDegrees(sunCycle * 360f + (float)AtmosphereMath.moonCycle(dayTime, false) * 360f));
            if (!shadersActive) {
                moon(draws, pose.last().pose(), MASSER, MASSER_SIZE, clear, night, AtmosphereMath.moonCycle(dayTime, false));
                if (AtmosphereConfig.MASSER_HALO.get() && night > .003f) {
                    Matrix4f halo = new Matrix4f(pose.last().pose()).scale(HALO_SIZE, 1f, HALO_SIZE);
                    add(draws, Kind.HALO, 0, 0, halo, MASSER_HALO, night * clear * NightSkyState.masserLight * .025f);
                }
            }
            pose.popPose();
            if (AtmosphereConfig.SHOOTING_STARS.get()) drawShootingStar(draws, pose.last().pose(), starAlpha, seconds);
            if (AtmosphereConfig.SECUNDA.get() && !shadersActive) {
                pose.pushPose();
                pose.rotate(Axis.YP.rotationDegrees(-90f));
                pose.rotate(Axis.ZP.rotationDegrees(SECUNDA_TILT));
                pose.rotate(Axis.XP.rotationDegrees(sunCycle * 360f + (float)AtmosphereMath.moonCycle(dayTime, true) * 360f));
                moon(draws, pose.last().pose(), SECUNDA, SECUNDA_SIZE, clear, night, AtmosphereMath.moonCycle(dayTime, true));
                pose.popPose();
            }
            if (AtmosphereConfig.AURORA.get() && !shadersActive) {
                float latitude = AtmosphereMath.auroraAlphaFactor(camZ);
                float cold = AtmosphereConfig.AURORA_EVERYWHERE.get() ? 1f
                        : Mth.lerp(pt, coldnessPrev, coldness) * (AtmosphereConfig.AURORA_BY_REGION.get() ? (latitude > 0f ? 1f : 0f) : 1f);
                float alpha = night * night * clear * cold * activity(level) * AtmosphereConfig.AURORA_BRIGHTNESS.get().floatValue();
                if (alpha > .003f) aurora(draws, pose, alpha, seconds, camZ);
            }
        }
        event.getRenderState().setRenderData(FRAME, new Frame(List.copyOf(draws)));
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent.AfterSky event) {
        Frame frame = event.getLevelRenderState().getRenderData(FRAME);
        if (frame == null || frame.draws.isEmpty()) return;
        SkyGeometry.ensureBuilt();
        Minecraft mc = Minecraft.getInstance();
        // A first lookup uploads its resource. Native encoders cannot upload while a draw pass is open.
        var textures = new HashMap<Identifier, net.minecraft.client.renderer.texture.AbstractTexture>();
        for (Draw draw : frame.draws) if (draw.texture != null && !textures.containsKey(draw.texture))
            textures.put(draw.texture, mc.getTextureManager().getTexture(draw.texture));
        // Meteor data has no native allocation until its actual draw. Upload before opening the pass.
        WorldMesh meteor = null;
        try {
            for (Draw draw : frame.draws) if (draw.kind == Kind.METEOR) {
                try (var builder = CpuMesh.builder(PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR)) {
                    var bb = builder.vertices();
                    for (int i = 0; i < draw.vertices.length; i += 7) bb.addVertex(draw.vertices[i], draw.vertices[i+1], draw.vertices[i+2])
                            .setColor(draw.vertices[i+3], draw.vertices[i+4], draw.vertices[i+5], draw.vertices[i+6]);
                    meteor = new WorldMesh(builder.build());
                }
            }
            try (var pass = WorldDraw.open()) {
                drawAll(pass, frame.draws, new Matrix4f(event.getModelViewMatrix()), null, textures, meteor);
            }
            // the same sky into a small cube (the water's reflections of the sky off screen: aurora, stars, moons)
            if (com.nirncraft.atlas.AtlasClient.wantsSkyCube()) SkyCube.render(frame.draws, textures, meteor);
        } finally { if (meteor != null) meteor.close(); }
    }

    /** The frame's sky draws into pass, seen through view (camera rotation), projection null: the frame's own. */
    static void drawAll(WorldDraw.Pass pass, List<Draw> draws, Matrix4f view,
                        com.mojang.renderpearl.api.buffers.GpuBufferSlice projection,
                        java.util.Map<Identifier, net.minecraft.client.renderer.texture.AbstractTexture> textures, WorldMesh meteor) {
                for (Draw draw : draws) {
                    WorldMesh mesh = switch (draw.kind) {
                        case VEIL -> SkyGeometry.veil;
                        case STARS -> SkyGeometry.starLayers[draw.index];
                        case GALAXY -> SkyGeometry.galaxy;
                        case CONSTELLATIONS -> SkyGeometry.constellations;
                        case SERPENT -> SkyGeometry.serpent;
                        case MOON, MOON_DAY -> SkyGeometry.moonPhases[draw.index];
                        case HALO -> SkyGeometry.halo;
                        case AURORA -> SkyGeometry.AURORA[draw.index][draw.key];
                        case METEOR -> meteor;
                    };
                    if (mesh == null) continue;
                    var material = switch (draw.kind) {
                        case VEIL -> VEIL;
                        case MOON -> SILHOUETTE;
                        case AURORA, METEOR -> COLOR;
                        default -> TEXTURED;
                    };
                    pass.draw(mesh, material, new Matrix4f(view).mul(draw.local), draw.tint,
                            draw.texture == null ? null : textures.get(draw.texture), projection);
                }
    }
    private static final float AURORA_GAIN = 2.5f;

    private static void addBright(List<Draw> draws, Kind kind, int index, int key, Matrix4f local, float strength) {
        if (strength <= .002f) return;
        float s = Math.max(1f, strength);
        draws.add(new Draw(kind, index, key, new Matrix4f(local), new Vector4f(s, s, s, Math.min(1f, strength)), null, null));
    }

    private static void add(List<Draw> draws, Kind kind, int index, int key, Matrix4f local, Identifier texture, float alpha) {
        if (alpha <= .002f) return;
        draws.add(new Draw(kind, index, key, new Matrix4f(local), new Vector4f(1f, 1f, 1f, Math.min(1f, alpha)), texture, null));
    }
    /** How much of a moon's lit face shows over the daylit sky (added to it: faint, bright on blue). */
    private static final float DAY_MOON = .3f;

    // By night the disc hides the stars behind it (its unlit side dark, alpha-blended: a silhouette). By day the sky's
    // light lies in front of the moon: only the lit face adds to the sky, faintly, and the unlit side is the sky's own
    // colour (the silhouette there drew a dark disc on the blue). night (the stars' visibility) crosses one into the
    // other.
    private static void moon(List<Draw> draws, Matrix4f local, Identifier texture, float size, float alpha, float night, double cycle) {
        double phase = cycle * 16.0;
        int first = (int)phase;
        Matrix4f scaled = new Matrix4f(local).scale(size, 1f, size);
        float fraction = (float)(phase - first);
        float hide = alpha * night, glow = alpha * (1f - night) * DAY_MOON;
        add(draws, Kind.MOON, first & 15, 0, scaled, texture, hide);
        if (fraction > .001f) add(draws, Kind.MOON, (first + 1) & 15, 0, scaled, texture, hide * fraction);
        add(draws, Kind.MOON_DAY, first & 15, 0, scaled, texture, glow);
        if (fraction > .001f) add(draws, Kind.MOON_DAY, (first + 1) & 15, 0, scaled, texture, glow * fraction);
    }
    private static void aurora(List<Draw> draws, PoseStack pose, float alpha, double seconds, double camZ) {
        double phase = seconds / AURORA_KEY_SECONDS;
        int key = Math.floorMod((long)Math.floor(phase), SkyGeometry.AURORA_KEYS);
        float w = (float)(phase - Math.floor(phase));
        w = w * w * (3f - 2f * w);
        int next = (key + 1) % SkyGeometry.AURORA_KEYS;
        float pitchOffset = AtmosphereMath.auroraPitchOffsetDeg(camZ);
        float heightScale = AtmosphereMath.auroraHeightScale(camZ);
        for (int b = 0; b < SkyGeometry.AURORA_BANDS; b++) {
            float band = alpha * (.55f + .45f * (float)Math.sin(seconds * .045 + b * 1.7));
            pose.pushPose();
            pose.rotate(Axis.YP.rotationDegrees((float)Math.sin(seconds * .011 + b * 2.3) * 4f));
            pose.rotate(Axis.XP.rotationDegrees(pitchOffset));
            pose.scale(1f, heightScale, 1f);
            // (brighter than an alpha of 1 allows: a strong display outshines the night; additive, so as colour)
            addBright(draws, Kind.AURORA, b, key, pose.last().pose(), band * (1f - w) * AURORA_GAIN);
            addBright(draws, Kind.AURORA, b, next, pose.last().pose(), band * w * AURORA_GAIN);
            pose.popPose();
        }
    }

    private static void drawShootingStar(java.util.List<Draw> draws, Matrix4f m, float starAlpha, double seconds) {
        if (starAlpha < 0.25F) {
            nextMeteorAt = -1.0;
            return;
        }
        if (nextMeteorAt < 0.0 || nextMeteorAt > seconds + 120.0) nextMeteorAt = seconds + 10.0 + METEOR_RNG.nextDouble() * 40.0;
        if (seconds >= nextMeteorAt) {
            nextMeteorAt = seconds + 15.0 + METEOR_RNG.nextDouble() * 45.0;
            meteorStart = seconds;
            meteorDuration = 0.45 + METEOR_RNG.nextDouble() * 0.6;
            meteorSpeed = Math.toRadians(22.0 + METEOR_RNG.nextDouble() * 26.0);
            meteorTail = Math.toRadians(6.0 + METEOR_RNG.nextDouble() * 8.0);
            double az = METEOR_RNG.nextDouble() * Math.PI * 2.0;
            double el = Math.toRadians(28.0 + METEOR_RNG.nextDouble() * 45.0);
            mSx = Math.sin(az) * Math.cos(el);
            mSy = Math.sin(el);
            mSz = Math.cos(az) * Math.cos(el);
            // a downward-slanting tangent: mostly sideways, a little toward the horizon
            double side = METEOR_RNG.nextBoolean() ? 1.0 : -1.0;
            double hx = Math.cos(az) * side, hz = -Math.sin(az) * side;            // horizontal tangent
            double dx = -Math.sin(az) * Math.sin(el), dy = Math.cos(el), dz = -Math.cos(az) * Math.sin(el); // "up" tangent
            double drop = 0.35 + METEOR_RNG.nextDouble() * 0.4;
            mTx = hx - dx * drop;
            mTy = -dy * drop;
            mTz = hz - dz * drop;
            double l = Math.sqrt(mTx * mTx + mTy * mTy + mTz * mTz);
            mTx /= l;
            mTy /= l;
            mTz /= l;
        }
        double t = seconds - meteorStart;
        if (t < 0.0 || t > meteorDuration) return;
        float fade = (float) Math.sin(Math.PI * t / meteorDuration) * starAlpha;
        double head = meteorSpeed * t;
        double tail = Math.max(0.0, head - meteorTail * Math.min(1.0, t / (meteorDuration * 0.4)));
        float r = SkyGeometry.R * 0.95F;
        double hc = Math.cos(head), hs = Math.sin(head), tc = Math.cos(tail), ts = Math.sin(tail);
        float hx = (float) ((mSx * hc + mTx * hs) * r), hy = (float) ((mSy * hc + mTy * hs) * r), hz = (float) ((mSz * hc + mTz * hs) * r);
        float tx = (float) ((mSx * tc + mTx * ts) * r), ty = (float) ((mSy * tc + mTy * ts) * r), tz = (float) ((mSz * tc + mTz * ts) * r);
        // width vector: perpendicular to both the streak and the view direction to the head
        float ax = tx - hx, ay = ty - hy, az = tz - hz;
        float wx = ay * hz - az * hy, wy = az * hx - ax * hz, wz = ax * hy - ay * hx;
        float wl = Mth.sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1.0E-4F) return;
        float w = 0.16F / wl;
        wx *= w;
        wy *= w;
        wz *= w;
        draws.add(new Draw(Kind.METEOR, 0, 0, new Matrix4f(m), new Vector4f(1f), null,
                new float[]{hx + wx, hy + wy, hz + wz, 1f, .97f, .88f, fade,
                            hx - wx, hy - wy, hz - wz, 1f, .97f, .88f, fade,
                            tx, ty, tz, .55f, .70f, 1f, 0f,
                            tx, ty, tz, .55f, .70f, 1f, 0f}));
    }

    /** A rare, gradually varying high-altitude red component; unrelated to volcanic heat. */
    static float auroraRedMix(ClientLevel level) {
        double days = level.getOverworldClockTime() / 24000.0;
        double storm = 0.5 + 0.5 * Math.sin(days * 0.17 + Math.sin(days * 0.043));
        return (float) Math.pow(Math.max(0.0, (storm - 0.95) / 0.05), 2.0);
    }

    /** Some nights the aurora is faint, some nights it blazes: a stable pseudo-random per in-game day, plus slow surges. */
    static float activity(ClientLevel level) {
        long day = Math.floorDiv(level.getOverworldClockTime() + 12000L, 24000L);
        long h = day * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        float r = (h & 0xFFFF) / 65535.0F;
        return 0.5F + 0.5F * r;
    }
}
