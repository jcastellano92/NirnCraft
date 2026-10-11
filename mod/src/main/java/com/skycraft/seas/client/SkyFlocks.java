package com.skycraft.seas.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.Skycraft;
import com.skycraft.sailing.DeckPhysics;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Ambient life of the skies and rivers, drawn on the client only (no entities, no server cost): gulls wheeling over
 * coasts, harbours and ships at sea; crows flocking across fields and tundra; hawks circling high over the plains and
 * crags; ravens gathering over barrows and graves at dusk and night; and in the rivers of the north, salmon leaping on
 * their run upstream. Flocks come and go with where the player is and the hour; at most a few dozen birds.
 * (Real, huntable hawks are entities: {@code skycraft:hawk}.)
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SkyFlocks {
    private static final ResourceLocation WHITE = new ResourceLocation("minecraft", "textures/misc/white.png");

    enum Kind {
        GULL(0.92f, 0.92f, 0.90f, 0.55f, 0.6f, 0.9f, 0.05f, 2.0f),
        CROW(0.07f, 0.07f, 0.08f, 0.07f, 0.07f, 0.08f, 0.10f, 1.5f),
        RAVEN(0.04f, 0.04f, 0.07f, 0.05f, 0.05f, 0.08f, 0.12f, 1.8f),
        HAWK(0.42f, 0.28f, 0.16f, 0.30f, 0.20f, 0.12f, 0.03f, 2.2f);

        final float r, g, b, wr, wg, wb;
        /** Wing beats per tick (radians fraction) and size. */
        final float flapRate, size;

        Kind(float r, float g, float b, float wr, float wg, float wb, float flapRate, float size) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.wr = wr;
            this.wg = wg;
            this.wb = wb;
            this.flapRate = flapRate;
            this.size = size;
        }
    }

    private static final class Bird {
        double x, y, z, xo, yo, zo;
        float yaw, yawO, flap, flapO;
        final float orbit, speed, phase, height;

        Bird(RandomSource r) {
            orbit = 4 + r.nextFloat() * 10;
            speed = 0.02f + r.nextFloat() * 0.02f;
            phase = r.nextFloat() * Mth.TWO_PI;
            height = r.nextFloat() * 6 - 3;
        }
    }

    private static final class Flock {
        final Kind kind;
        double cx, cy, cz;
        double vx, vz;
        int life;
        final List<Bird> birds = new ArrayList<>();

        Flock(Kind kind) {
            this.kind = kind;
        }
    }

    private static final class Leap {
        double x, y, z, dx, dz;
        int age;
        final int len;

        Leap(double x, double y, double z, double dx, double dz, int len) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dz = dz;
            this.len = len;
        }
    }

    private static final List<Flock> FLOCKS = new ArrayList<>();
    private static final List<Leap> LEAPS = new ArrayList<>();
    private static int ticks;

    private SkyFlocks() {}

    // ------------------------------------------------------------------ simulation

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || mc.isPaused()) return;
        ticks++;
        RandomSource r = level.getRandom();
        if (ticks % 80 == 0) populate(level, player, r);
        for (Iterator<Flock> it = FLOCKS.iterator(); it.hasNext(); ) {
            Flock f = it.next();
            if (--f.life <= 0 || Double.isNaN(f.cx) || Math.hypot(f.cx - player.getX(), f.cz - player.getZ()) > 160) {
                it.remove();
                continue;
            }
            step(f, level, r);
        }
        leaps(level, player, r);
    }

    private static void populate(ClientLevel level, LocalPlayer player, RandomSource r) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || FLOCKS.size() >= 5) return;
        BlockPos at = player.blockPosition();
        var biome = level.getBiome(at);
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        long time = level.getDayTime() % 24000L;
        boolean day = time < 12500 || time > 23200;
        boolean dusk = time > 11500 && time < 14000;
        if (level.isRaining() && r.nextBoolean()) return;
        float coast = TamrielMap.sampleScratch(at.getX(), at.getZ()).coastDistance;
        boolean sea = coast < 40 || path.contains("ocean") || path.contains("sea") || path.contains("coast") || path.contains("beach")
                || path.contains("shore") || DeckPhysics.shipOf(player) != null;
        boolean open = path.contains("plains") || path.contains("tundra") || path.contains("meadow") || path.contains("steppe")
                || path.contains("farm") || path.contains("savanna") || path.contains("highland") || path.contains("moor");
        boolean crag = path.contains("crag") || path.contains("peak") || path.contains("mountain") || path.contains("alpine")
                || player.getY() > 120;
        boolean grave = false;
        Optional<Place> barrow = Places.nearest(PlaceType.DUNGEON_FIXED, at.getX(), at.getZ(), 220);
        if (barrow.isPresent()) grave = true;
        Kind kind = null;
        if (sea && day && count(Kind.GULL) < 2) kind = Kind.GULL;
        else if ((grave || path.contains("snow") || path.contains("pine")) && (!day || dusk) && count(Kind.RAVEN) < 1) kind = Kind.RAVEN;
        else if (crag && day && count(Kind.HAWK) < 1) kind = Kind.HAWK;
        else if ((open || path.contains("forest")) && day && count(Kind.CROW) < 1 && r.nextInt(3) == 0) kind = Kind.CROW;
        else if (open && day && count(Kind.HAWK) < 1 && r.nextInt(3) == 0) kind = Kind.HAWK;
        if (kind == null) return;
        Flock f = new Flock(kind);
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = kind == Kind.GULL ? 20 + r.nextDouble() * 30 : 40 + r.nextDouble() * 40;
        if (kind == Kind.RAVEN && barrow.isPresent()) {
            f.cx = barrow.get().x();
            f.cz = barrow.get().z();
        } else {
            f.cx = player.getX() + Math.cos(ang) * dist;
            f.cz = player.getZ() + Math.sin(ang) * dist;
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(f.cx), Mth.floor(f.cz));
        if (ground <= level.getMinBuildHeight()) ground = (int) player.getY();
        f.cy = Math.max(ground, 63) + switch (kind) {
            case GULL -> 8 + r.nextInt(10);
            case CROW -> 14 + r.nextInt(10);
            case RAVEN -> 12 + r.nextInt(8);
            case HAWK -> 30 + r.nextInt(20);
        };
        double heading = r.nextDouble() * Math.PI * 2;
        double speed = kind == Kind.CROW ? 0.18 : kind == Kind.GULL ? 0.04 : 0.01;
        f.vx = Math.cos(heading) * speed;
        f.vz = Math.sin(heading) * speed;
        f.life = 1200 + r.nextInt(1800);
        int n = switch (kind) {
            case GULL -> 3 + r.nextInt(5);
            case CROW -> 5 + r.nextInt(8);
            case RAVEN -> 3 + r.nextInt(4);
            case HAWK -> 1 + r.nextInt(2);
        };
        for (int i = 0; i < n; i++) {
            Bird b = new Bird(r);
            b.x = b.xo = f.cx + (r.nextDouble() - 0.5) * 8;
            b.y = b.yo = f.cy + b.height;
            b.z = b.zo = f.cz + (r.nextDouble() - 0.5) * 8;
            f.birds.add(b);
        }
        FLOCKS.add(f);
    }

    private static int count(Kind k) {
        int n = 0;
        for (Flock f : FLOCKS) if (f.kind == k) n++;
        return n;
    }

    private static void step(Flock f, ClientLevel level, RandomSource r) {
        f.cx += f.vx;
        f.cz += f.vz;
        if (r.nextInt(200) == 0) {
            double a = Math.atan2(f.vz, f.vx) + (r.nextDouble() - 0.5);
            double s = Math.hypot(f.vx, f.vz);
            f.vx = Math.cos(a) * s;
            f.vz = Math.sin(a) * s;
        }
        float t = ticks;
        for (Bird b : f.birds) {
            b.xo = b.x;
            b.yo = b.y;
            b.zo = b.z;
            b.yawO = b.yaw;
            b.flapO = b.flap;
            double tx, ty, tz;
            float ang = b.phase + t * b.speed * (f.kind == Kind.HAWK ? 0.6f : 1f);
            tx = f.cx + Math.cos(ang) * b.orbit;
            tz = f.cz + Math.sin(ang) * b.orbit;
            ty = f.cy + b.height + Math.sin(t * 0.03 + b.phase) * 1.5;
            double dx = (tx - b.x) * 0.06, dy = (ty - b.y) * 0.05, dz = (tz - b.z) * 0.06;
            double max = f.kind == Kind.CROW ? 0.45 : 0.35;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > max) {
                dx *= max / len;
                dz *= max / len;
            }
            b.x += dx;
            b.y += dy;
            b.z += dz;
            if (len > 1e-3) b.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            // beat, then glide (hawks and gulls glide most of the time)
            boolean gliding = f.kind == Kind.HAWK || f.kind == Kind.GULL && Math.sin(t * 0.02 + b.phase) > -0.2;
            b.flap += gliding ? (0 - b.flap) * 0.1f : f.kind.flapRate * Mth.TWO_PI;
        }
        if (r.nextInt(600) == 0) {
            Bird b = f.birds.get(r.nextInt(f.birds.size()));
            var call = switch (f.kind) {
                case GULL -> com.skycraft.audio.AudioSounds.get("ambient.gull");
                case HAWK -> com.skycraft.audio.AudioSounds.get("ambient.hawk");
                default -> com.skycraft.audio.AudioSounds.get("ambient.crow");
            };
            level.playLocalSound(b.x, b.y, b.z, call, SoundSource.AMBIENT, 0.6f, f.kind == Kind.RAVEN ? 0.7f : 1f, false);
        }
    }

    /** Salmon leaping in the northern rivers by day. */
    private static void leaps(ClientLevel level, LocalPlayer player, RandomSource r) {
        for (Iterator<Leap> it = LEAPS.iterator(); it.hasNext(); ) {
            Leap l = it.next();
            if (++l.age > l.len) {
                level.addParticle(ParticleTypes.SPLASH, l.x + l.dx * l.len, l.y, l.z + l.dz * l.len, 0, 0.1, 0);
                it.remove();
            }
        }
        if (ticks % 7 != 0 || LEAPS.size() >= 6) return;
        long time = level.getDayTime() % 24000L;
        if (time > 12500) return;
        for (int i = 0; i < 2; i++) {
            int x = Mth.floor(player.getX()) + r.nextInt(41) - 20, z = Mth.floor(player.getZ()) + r.nextInt(41) - 20;
            BlockPos col = new BlockPos(x, 0, z);
            if (!level.hasChunkAt(col)) continue;
            String path = level.getBiome(new BlockPos(x, 62, z)).unwrapKey().map(k -> k.location().getPath()).orElse("");
            if (!path.contains("river")) continue;
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos w = new BlockPos(x, top - 1, z);
            if (!level.getFluidState(w).is(FluidTags.WATER) || !level.getFluidState(w.below()).is(FluidTags.WATER)) continue;
            double ang = r.nextDouble() * Math.PI * 2;
            Leap l = new Leap(x + 0.5, top - 0.1, z + 0.5, Math.cos(ang) * 0.08, Math.sin(ang) * 0.08, 12 + r.nextInt(6));
            LEAPS.add(l);
            level.addParticle(ParticleTypes.SPLASH, l.x, l.y + 0.1, l.z, 0, 0.15, 0);
            if (r.nextInt(3) == 0) level.playLocalSound(l.x, l.y, l.z, SoundEvents.FISH_SWIM, SoundSource.AMBIENT, 0.4f, 1.2f, false);
            break;
        }
    }

    // ------------------------------------------------------------------ drawing

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || FLOCKS.isEmpty() && LEAPS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        float partial = event.getPartialTick();
        Camera cam = event.getCamera();
        Vec3 c = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
        for (Flock f : FLOCKS) {
            for (Bird b : f.birds) {
                double x = Mth.lerp(partial, b.xo, b.x), y = Mth.lerp(partial, b.yo, b.y), z = Mth.lerp(partial, b.zo, b.z);
                if ((x - c.x) * (x - c.x) + (z - c.z) * (z - c.z) > 150 * 150) continue;
                int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
                pose.pushPose();
                pose.translate(x - c.x, y - c.y, z - c.z);
                pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-Mth.rotLerp(partial, b.yawO, b.yaw)));
                bird(pose, vc, f.kind, Mth.lerp(partial, b.flapO, b.flap), light);
                pose.popPose();
            }
        }
        for (Leap l : LEAPS) {
            float t = (l.age + partial) / l.len;
            double x = l.x + l.dx * l.age, z = l.z + l.dz * l.age;
            double y = l.y + Math.sin(t * Math.PI) * 1.1;
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
            pose.pushPose();
            pose.translate(x - c.x, y - c.y, z - c.z);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) -Math.toDegrees(Math.atan2(-l.dx, l.dz))));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-60f + 120f * t));
            box(pose, vc, -0.06f, -0.08f, -0.3f, 0.06f, 0.08f, 0.25f, 0.62f, 0.5f, 0.5f, light);
            box(pose, vc, -0.07f, -0.04f, -0.24f, 0.07f, 0.06f, 0.18f, 0.78f, 0.36f, 0.32f, light);
            box(pose, vc, -0.01f, -0.1f, 0.25f, 0.01f, 0.1f, 0.4f, 0.4f, 0.32f, 0.3f, light);
            pose.popPose();
        }
        buffers.endBatch(RenderType.entityCutoutNoCull(WHITE));
    }

    /** A bird: body, head, tail and two wings beating about the shoulders. */
    private static void bird(PoseStack pose, VertexConsumer vc, Kind k, float flap, int light) {
        float s = k.size;
        box(pose, vc, -0.07f * s, -0.06f * s, -0.18f * s, 0.07f * s, 0.06f * s, 0.18f * s, k.r, k.g, k.b, light);
        box(pose, vc, -0.05f * s, -0.02f * s, -0.28f * s, 0.05f * s, 0.07f * s, -0.18f * s, k.r, k.g, k.b, light);
        if (k == Kind.GULL || k == Kind.HAWK) {
            box(pose, vc, -0.015f * s, -0.01f * s, -0.33f * s, 0.015f * s, 0.02f * s, -0.28f * s, 0.85f, 0.7f, 0.2f, light);
        }
        float wingAngle = Mth.sin(flap) * 35f + 5f;
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.06f * s, 0.03f * s, -0.04f * s);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(side * wingAngle));
            float span = (k == Kind.HAWK ? 0.5f : k == Kind.GULL ? 0.48f : 0.36f) * s;
            Matrix4f m = pose.last().pose();
            Matrix3f n = pose.last().normal();
            float x1 = side * span;
            quad(vc, m, n, 0, 0, -0.08f * s, x1, 0, -0.03f * s, x1, 0, 0.06f * s, 0, 0, 0.1f * s, k.wr, k.wg, k.wb, light);
            if (k == Kind.GULL) quad(vc, m, n, x1 * 0.8f, 0.001f, -0.04f * s, x1, 0.001f, -0.03f * s, x1, 0.001f, 0.06f * s, x1 * 0.8f, 0.001f, 0.07f * s,
                    0.1f, 0.1f, 0.1f, light);
            pose.popPose();
        }
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        quad(vc, m, n, -0.05f * s, 0, 0.16f * s, 0.05f * s, 0, 0.16f * s, 0.08f * s, 0, 0.34f * s, -0.08f * s, 0, 0.34f * s, k.wr, k.wg, k.wb, light);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Matrix3f n, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float r, float g, float b, int light) {
        vc.vertex(m, x0, y0, z0).color(r, g, b, 1f).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        vc.vertex(m, x1, y1, z1).color(r, g, b, 1f).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        vc.vertex(m, x2, y2, z2).color(r, g, b, 1f).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        vc.vertex(m, x3, y3, z3).color(r, g, b, 1f).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
    }

    private static void box(PoseStack pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1,
                            float r, float g, float b, int light) {
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        float rd = r * 0.75f, gd = g * 0.75f, bd = b * 0.75f;
        quad(vc, m, n, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, light);
        quad(vc, m, n, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, rd, gd, bd, light);
        quad(vc, m, n, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r * 0.9f, g * 0.9f, b * 0.9f, light);
        quad(vc, m, n, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r * 0.9f, g * 0.9f, b * 0.9f, light);
        quad(vc, m, n, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, rd, gd, bd, light);
        quad(vc, m, n, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, rd, gd, bd, light);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        FLOCKS.clear();
        LEAPS.clear();
    }

    /** For the autotest: put a flock of a kind around a point. */
    public static void debugFlock(String kind, double x, double y, double z) {
        Flock f = new Flock(Kind.valueOf(kind));
        f.cx = x;
        f.cy = y;
        f.cz = z;
        f.life = 2400;
        RandomSource r = RandomSource.create();
        for (int i = 0; i < 8; i++) {
            Bird b = new Bird(r);
            b.x = b.xo = x + (r.nextDouble() - 0.5) * 8;
            b.y = b.yo = y + b.height;
            b.z = b.zo = z + (r.nextDouble() - 0.5) * 8;
            f.birds.add(b);
        }
        FLOCKS.add(f);
    }
}
