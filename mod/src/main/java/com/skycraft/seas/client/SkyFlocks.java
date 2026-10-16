package com.skycraft.seas.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
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
 * coasts, harbours and ships at sea; songbirds darting in tight murmurations across meadows and forest edges; crows
 * flocking across fields and perching on treetops and roof ridges; hawks thermal-soaring high over the plains and crags;
 * ravens gathering over barrows and pines at dusk; bats darting at night; and in the rivers of the north, salmon leaping
 * upstream.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class SkyFlocks {
    private static final ResourceLocation WHITE = new ResourceLocation("minecraft", "textures/misc/white.png");

    enum Kind {
        // Back RGB, Belly RGB, Wing RGB, Wingtip/Underwing RGB, Beak RGB, flapRate, size, altMin, altSpan, maxSpeed, canPerch
        GULL(
                0.94f, 0.94f, 0.92f,
                0.96f, 0.96f, 0.95f,
                0.66f, 0.70f, 0.75f,
                0.18f, 0.20f, 0.24f,
                0.90f, 0.72f, 0.20f,
                0.055f, 2.0f, 10, 12, 0.36, false
        ),
        CROW(
                0.22f, 0.22f, 0.25f,
                0.26f, 0.26f, 0.30f,
                0.20f, 0.21f, 0.24f,
                0.34f, 0.35f, 0.39f,
                0.30f, 0.30f, 0.33f,
                0.095f, 1.55f, 12, 12, 0.42, true
        ),
        RAVEN(
                0.18f, 0.20f, 0.26f,
                0.22f, 0.24f, 0.30f,
                0.16f, 0.18f, 0.24f,
                0.26f, 0.28f, 0.34f,
                0.28f, 0.30f, 0.35f,
                0.085f, 1.85f, 14, 12, 0.38, true
        ),
        HAWK(
                0.48f, 0.32f, 0.20f,
                0.76f, 0.68f, 0.56f,
                0.44f, 0.29f, 0.18f,
                0.22f, 0.16f, 0.12f,
                0.88f, 0.72f, 0.22f,
                0.032f, 2.25f, 26, 22, 0.34, false
        ),
        SONGBIRD(
                0.24f, 0.48f, 0.78f,
                0.82f, 0.45f, 0.26f,
                0.22f, 0.42f, 0.70f,
                0.86f, 0.88f, 0.92f,
                0.78f, 0.66f, 0.24f,
                0.14f, 1.15f, 8, 9, 0.46, true
        ),
        BAT(
                0.34f, 0.25f, 0.20f,
                0.45f, 0.35f, 0.28f,
                0.26f, 0.20f, 0.18f,
                0.32f, 0.24f, 0.22f,
                0.30f, 0.22f, 0.20f,
                0.18f, 1.25f, 7, 8, 0.44, false
        );

        final float r, g, b;
        final float br, bg, bb;
        final float wr, wg, wb;
        final float tr, tg, tb;
        final float beakR, beakG, beakB;
        final float flapRate, size;
        final int altMin, altSpan;
        final double maxSpeed;
        final boolean canPerch;

        Kind(float r, float g, float b,
             float br, float bg, float bb,
             float wr, float wg, float wb,
             float tr, float tg, float tb,
             float beakR, float beakG, float beakB,
             float flapRate, float size, int altMin, int altSpan, double maxSpeed, boolean canPerch) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.br = br;
            this.bg = bg;
            this.bb = bb;
            this.wr = wr;
            this.wg = wg;
            this.wb = wb;
            this.tr = tr;
            this.tg = tg;
            this.tb = tb;
            this.beakR = beakR;
            this.beakG = beakG;
            this.beakB = beakB;
            this.flapRate = flapRate;
            this.size = size;
            this.altMin = altMin;
            this.altSpan = altSpan;
            this.maxSpeed = maxSpeed;
            this.canPerch = canPerch;
        }
    }

    private static final class Bird {
        double x, y, z, xo, yo, zo;
        double vx, vy, vz;
        float yaw, yawO, pitch, pitchO, roll, rollO, flap, flapO, headYaw;
        final float orbit, speed, phase, height;
        final int plumage;
        int perchTicks;
        int perchCooldown;
        double perchX, perchY, perchZ;
        boolean seekingPerch;

        Bird(RandomSource r) {
            orbit = 4.5f + r.nextFloat() * 10.5f;
            speed = 0.022f + r.nextFloat() * 0.022f;
            phase = r.nextFloat() * Mth.TWO_PI;
            height = r.nextFloat() * 6f - 3f;
            plumage = r.nextInt(3);
            perchCooldown = 80 + r.nextInt(160);
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
        if (ticks % 70 == 0) populate(level, player, r);
        for (Iterator<Flock> it = FLOCKS.iterator(); it.hasNext(); ) {
            Flock f = it.next();
            if (--f.life <= 0 || Double.isNaN(f.cx) || Math.hypot(f.cx - player.getX(), f.cz - player.getZ()) > 165) {
                it.remove();
                continue;
            }
            step(f, level, player, r);
        }
        leaps(level, player, r);
    }

    private static void populate(ClientLevel level, LocalPlayer player, RandomSource r) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || FLOCKS.size() >= 6) return;
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
        boolean lush = path.contains("forest") || path.contains("meadow") || path.contains("grove") || path.contains("valley")
                || path.contains("reach") || path.contains("rift") || path.contains("falkreath");
        boolean crag = path.contains("crag") || path.contains("peak") || path.contains("mountain") || path.contains("alpine")
                || player.getY() > 120;
        boolean grave = false;
        Optional<Place> barrow = Places.nearest(PlaceType.DUNGEON_FIXED, at.getX(), at.getZ(), 220);
        if (barrow.isPresent()) grave = true;

        Kind kind = null;
        if (sea && day && count(Kind.GULL) < 2) kind = Kind.GULL;
        else if ((grave || path.contains("snow") || path.contains("pine")) && (!day || dusk) && count(Kind.RAVEN) < 1) kind = Kind.RAVEN;
        else if (!day && !dusk && count(Kind.BAT) < 1 && r.nextInt(2) == 0) kind = Kind.BAT;
        else if (crag && day && count(Kind.HAWK) < 1) kind = Kind.HAWK;
        else if (lush && day && count(Kind.SONGBIRD) < 1 && r.nextInt(2) == 0) kind = Kind.SONGBIRD;
        else if ((open || lush) && day && count(Kind.CROW) < 1 && r.nextInt(2) == 0) kind = Kind.CROW;
        else if (open && day && count(Kind.HAWK) < 1 && r.nextInt(3) == 0) kind = Kind.HAWK;
        if (kind == null) return;

        Flock f = new Flock(kind);
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = (kind == Kind.GULL || kind == Kind.SONGBIRD || kind == Kind.BAT)
                ? 18 + r.nextDouble() * 26
                : 34 + r.nextDouble() * 38;
        if (kind == Kind.RAVEN && barrow.isPresent()) {
            f.cx = barrow.get().x();
            f.cz = barrow.get().z();
        } else {
            f.cx = player.getX() + Math.cos(ang) * dist;
            f.cz = player.getZ() + Math.sin(ang) * dist;
        }
        int ground = sampleGround(level, f.cx, f.cz, (int) player.getY());
        f.cy = Math.max(ground, level.getSeaLevel()) + kind.altMin + r.nextInt(kind.altSpan);
        double heading = r.nextDouble() * Math.PI * 2;
        double speed = kind == Kind.CROW || kind == Kind.SONGBIRD ? 0.16 : kind == Kind.GULL ? 0.06 : 0.025;
        f.vx = Math.cos(heading) * speed;
        f.vz = Math.sin(heading) * speed;
        f.life = 1200 + r.nextInt(1800);
        int n = kind == Kind.GULL ? 4 + r.nextInt(5)
                : kind == Kind.CROW ? 5 + r.nextInt(7)
                : kind == Kind.RAVEN ? 3 + r.nextInt(4)
                : kind == Kind.SONGBIRD ? 7 + r.nextInt(8)
                : kind == Kind.BAT ? 4 + r.nextInt(5)
                : 1 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            Bird b = new Bird(r);
            b.x = b.xo = f.cx + (r.nextDouble() - 0.5) * 9;
            int bg = sampleGround(level, b.x, f.cz, ground);
            b.y = b.yo = Math.max(f.cy + b.height, bg + kind.altMin);
            b.z = b.zo = f.cz + (r.nextDouble() - 0.5) * 9;
            b.vx = f.vx + (r.nextDouble() - 0.5) * 0.08;
            b.vz = f.vz + (r.nextDouble() - 0.5) * 0.08;
            b.yaw = b.yawO = (float) Math.toDegrees(Math.atan2(-b.vx, b.vz));
            f.birds.add(b);
        }
        FLOCKS.add(f);
    }

    private static int count(Kind k) {
        int n = 0;
        for (Flock f : FLOCKS) if (f.kind == k) n++;
        return n;
    }

    private static int sampleGround(ClientLevel level, double x, double z, int fallback) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return fallback;
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        return g <= level.getMinBuildHeight() ? fallback : g;
    }

    private static boolean isPerchSurface(ClientLevel level, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !level.getBlockState(pos.above()).isAir()) return false;
        return state.is(BlockTags.LEAVES) || state.getBlock() instanceof StairBlock || state.getBlock() instanceof SlabBlock
                || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS);
    }

    private static BlockPos findNearbyPerch(ClientLevel level, double cx, double cz, RandomSource r) {
        for (int i = 0; i < 10; i++) {
            int x = Mth.floor(cx) + r.nextInt(17) - 8;
            int z = Mth.floor(cz) + r.nextInt(17) - 8;
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            if (top <= level.getSeaLevel() + 2) continue;
            BlockPos p = new BlockPos(x, top, z);
            if (isPerchSurface(level, p)) return p;
        }
        return null;
    }

    private static void step(Flock f, ClientLevel level, LocalPlayer player, RandomSource r) {
        Kind k = f.kind;
        f.cx += f.vx;
        f.cz += f.vz;
        if (r.nextInt(160) == 0) {
            double a = Math.atan2(f.vz, f.vx) + (r.nextDouble() - 0.5) * 0.85;
            double s = Math.max(0.02, Math.hypot(f.vx, f.vz));
            f.vx = Math.cos(a) * s;
            f.vz = Math.sin(a) * s;
        }
        // Keep flock center safely above live terrain heightmap
        int centerGround = sampleGround(level, f.cx, f.cz, (int) player.getY());
        double targetCenterY = Math.max(centerGround, level.getSeaLevel()) + k.altMin + k.altSpan * 0.45;
        f.cy += (targetCenterY - f.cy) * 0.08;

        float t = ticks;
        int n = f.birds.size();
        for (int i = 0; i < n; i++) {
            Bird b = f.birds.get(i);
            b.xo = b.x;
            b.yo = b.y;
            b.zo = b.z;
            b.yawO = b.yaw;
            b.pitchO = b.pitch;
            b.rollO = b.roll;
            b.flapO = b.flap;
            if (b.perchCooldown > 0) b.perchCooldown--;

            // Handle active perching on treetops / roof ridges for Crows, Ravens, and Songbirds
            if (b.perchTicks > 0) {
                b.perchTicks--;
                double playerDistSq = player.distanceToSqr(b.x, b.y, b.z);
                double flockDist = Math.hypot(f.cx - b.x, f.cz - b.z);
                BlockPos support = BlockPos.containing(b.perchX, b.perchY - 0.6, b.perchZ);
                if (playerDistSq < 8.0 * 8.0 || flockDist > 42.0 || b.perchTicks <= 0 || !isPerchSurface(level, support)) {
                    // Burst takeoff upward!
                    b.perchTicks = 0;
                    b.seekingPerch = false;
                    b.perchCooldown = 160 + r.nextInt(200);
                    double awayAng = playerDistSq < 64.0
                            ? Math.atan2(b.z - player.getZ(), b.x - player.getX()) + (r.nextDouble() - 0.5) * 0.5
                            : r.nextDouble() * Math.PI * 2;
                    b.vx = Math.cos(awayAng) * k.maxSpeed * 0.85;
                    b.vz = Math.sin(awayAng) * k.maxSpeed * 0.85;
                    b.vy = 0.22 + r.nextDouble() * 0.08;
                } else {
                    b.x += (b.perchX - b.x) * 0.25;
                    b.y += (b.perchY - b.y) * 0.25;
                    b.z += (b.perchZ - b.z) * 0.25;
                    b.vx = b.vy = b.vz = 0;
                    b.pitch += (0f - b.pitch) * 0.25f;
                    b.roll += (0f - b.roll) * 0.25f;
                    b.flap += (0f - b.flap) * 0.3f;
                    if (r.nextInt(35) == 0) {
                        b.headYaw = (r.nextFloat() - 0.5f) * 50f;
                    }
                    b.yaw += b.headYaw * 0.05f;
                    b.headYaw *= 0.85f;
                    continue;
                }
            }

            // Periodically seek a treetop or roof ridge perch
            if (k.canPerch && !b.seekingPerch && b.perchCooldown == 0 && r.nextInt(90) == 0) {
                BlockPos perch = findNearbyPerch(level, b.x, b.z, r);
                if (perch != null && player.distanceToSqr(perch.getX() + 0.5, perch.getY() + 1.0, perch.getZ() + 0.5) > 100.0) {
                    b.seekingPerch = true;
                    b.perchX = perch.getX() + 0.5;
                    b.perchY = perch.getY() + 1.08;
                    b.perchZ = perch.getZ() + 0.5;
                } else {
                    b.perchCooldown = 60;
                }
            }

            if (b.seekingPerch) {
                double pdx = b.perchX - b.x;
                double pdy = b.perchY - b.y;
                double pdz = b.perchZ - b.z;
                double pdist = Math.sqrt(pdx * pdx + pdy * pdy + pdz * pdz);
                if (pdist > 28.0 || player.distanceToSqr(b.perchX, b.perchY, b.perchZ) < 64.0) {
                    b.seekingPerch = false;
                    b.perchCooldown = 100;
                } else if (pdist < 0.75) {
                    b.seekingPerch = false;
                    b.perchTicks = 120 + r.nextInt(180); // 6-15 seconds
                    b.vx = b.vy = b.vz = 0;
                    continue;
                } else {
                    double descentSpeed = Math.min(k.maxSpeed * 0.85, Math.max(0.10, pdist * 0.08));
                    b.vx += (pdx / pdist * descentSpeed - b.vx) * 0.18;
                    b.vy += (pdy / pdist * descentSpeed - b.vy) * 0.18;
                    b.vz += (pdz / pdist * descentSpeed - b.vz) * 0.18;
                }
            } else {
                // Target orbit point around flock center
                float orbitScale = k == Kind.HAWK ? 0.55f : (k == Kind.SONGBIRD ? 1.35f : 1.0f);
                float ang = b.phase + t * b.speed * orbitScale;
                double radius = k == Kind.SONGBIRD
                        ? b.orbit * (0.55 + 0.35 * Math.sin(t * 0.04 + b.phase))
                        : b.orbit;
                double tx = f.cx + Math.cos(ang) * radius;
                double tz = f.cz + Math.sin(ang) * radius;
                double waveY = k == Kind.HAWK ? Math.sin(t * 0.018 + b.phase) * 2.0
                        : k == Kind.GULL ? Math.sin(t * 0.045 + b.phase) * 2.2
                        : k == Kind.BAT ? Math.sin(t * 0.22 + b.phase * 2f) * 1.8
                        : Math.sin(t * 0.04 + b.phase) * 1.4;
                double ty = f.cy + b.height + waveY;

                // 3D Boid forces: Separation, Alignment, Cohesion
                double sepX = 0, sepY = 0, sepZ = 0;
                double aliX = 0, aliY = 0, aliZ = 0;
                double cohX = 0, cohY = 0, cohZ = 0;
                int neighbors = 0;
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    Bird o = f.birds.get(j);
                    if (o.perchTicks > 0) continue;
                    double ox = b.x - o.x, oy = b.y - o.y, oz = b.z - o.z;
                    double d2 = ox * ox + oy * oy + oz * oz;
                    if (d2 < 2.6 * 2.6 && d2 > 1e-4) {
                        double inv = 1.0 / Math.sqrt(d2);
                        sepX += ox * inv / d2;
                        sepY += oy * inv / d2;
                        sepZ += oz * inv / d2;
                    }
                    if (d2 < 14.0 * 14.0) {
                        aliX += o.vx;
                        aliY += o.vy;
                        aliZ += o.vz;
                        cohX += o.x;
                        cohY += o.y;
                        cohZ += o.z;
                        neighbors++;
                    }
                }

                double steerX = (tx - b.x) * 0.012;
                double steerY = (ty - b.y) * 0.014;
                double steerZ = (tz - b.z) * 0.012;

                if (neighbors > 0) {
                    aliX /= neighbors;
                    aliY /= neighbors;
                    aliZ /= neighbors;
                    cohX = (cohX / neighbors - b.x) * 0.006;
                    cohY = (cohY / neighbors - b.y) * 0.006;
                    cohZ = (cohZ / neighbors - b.z) * 0.006;
                    double aliWeight = k == Kind.SONGBIRD ? 0.14 : 0.08;
                    steerX += (aliX - b.vx) * aliWeight + cohX;
                    steerY += (aliY - b.vy) * aliWeight + cohY;
                    steerZ += (aliZ - b.vz) * aliWeight + cohZ;
                }
                steerX += sepX * 0.09;
                steerY += sepY * 0.09;
                steerZ += sepZ * 0.09;

                if (k == Kind.BAT) {
                    steerX += (r.nextDouble() - 0.5) * 0.04;
                    steerY += (r.nextDouble() - 0.5) * 0.03;
                    steerZ += (r.nextDouble() - 0.5) * 0.04;
                }

                b.vx += steerX;
                b.vy += steerY;
                b.vz += steerZ;
            }

            // Live Terrain, Cliff, Tree & Wall Avoidance (current position + 4 blocks ahead along velocity!)
            int localGround = sampleGround(level, b.x, b.z, centerGround);
            double horizSpeed = Math.hypot(b.vx, b.vz);
            double lookDist = 4.0;
            double dirX = horizSpeed > 1e-3 ? b.vx / horizSpeed : 0.0;
            double dirZ = horizSpeed > 1e-3 ? b.vz / horizSpeed : 0.0;
            int aheadGround = sampleGround(level, b.x + dirX * lookDist, b.z + dirZ * lookDist, localGround);
            int maxGround = Math.max(Math.max(localGround, aheadGround), level.getSeaLevel());
            double minSafeY = b.seekingPerch ? maxGround + 1.2 : maxGround + Math.max(6, k.altMin - 2);

            if (b.y < minSafeY) {
                double deficit = minSafeY - b.y;
                b.vy = Math.max(b.vy + 0.045 * Math.min(4.0, deficit), 0.12);
                if (b.y < maxGround + 2.0) {
                    b.y = maxGround + 2.0;
                }
            }

            // Check block ahead at bird altitude for walls / cliffs / tree trunks
            BlockPos aheadPos = BlockPos.containing(b.x + dirX * 2.5, b.y, b.z + dirZ * 2.5);
            if (level.hasChunk(aheadPos.getX() >> 4, aheadPos.getZ() >> 4) && !level.getBlockState(aheadPos).isAir() && !b.seekingPerch) {
                // Bank laterally and climb over the obstacle
                double perpX = -dirZ;
                double perpZ = dirX;
                b.vx = b.vx * 0.5 + perpX * 0.18;
                b.vz = b.vz * 0.5 + perpZ * 0.18;
                b.vy = Math.max(b.vy, 0.16);
            }

            // Clamp 3D speed
            double maxSpd = k.maxSpeed;
            double minSpd = k == Kind.HAWK ? 0.18 : 0.12;
            double hLen = Math.hypot(b.vx, b.vz);
            if (hLen > maxSpd) {
                b.vx *= maxSpd / hLen;
                b.vz *= maxSpd / hLen;
            } else if (hLen < minSpd && !b.seekingPerch) {
                double a = hLen > 1e-4 ? Math.atan2(b.vz, b.vx) : Math.toRadians(b.yaw);
                b.vx = Math.cos(a) * minSpd;
                b.vz = Math.sin(a) * minSpd;
            }
            b.vy = Mth.clamp(b.vy, -0.22, 0.26);

            b.x += b.vx;
            b.y += b.vy;
            b.z += b.vz;

            if (hLen > 1e-3) {
                float targetYaw = (float) Math.toDegrees(Math.atan2(-b.vx, b.vz));
                float deltaYaw = Mth.wrapDegrees(targetYaw - b.yaw);
                b.yaw += deltaYaw * 0.25f;
                float targetRoll = Mth.clamp(-deltaYaw * 1.6f, -32f, 32f);
                b.roll += (targetRoll - b.roll) * 0.2f;
                float targetPitch = Mth.clamp((float) (-b.vy * 95.0), -28f, 28f);
                b.pitch += (targetPitch - b.pitch) * 0.2f;
            }

            // Wingbeat vs thermal glide cadence
            boolean gliding = (k == Kind.HAWK && Math.sin(t * 0.025 + b.phase) > -0.65 && b.vy <= 0.05)
                    || (k == Kind.GULL && Math.sin(t * 0.03 + b.phase) > -0.2 && b.vy <= 0.04)
                    || (k == Kind.RAVEN && Math.sin(t * 0.04 + b.phase) > 0.35 && b.vy <= 0.02);
            if (gliding) {
                b.flap += (0.12f - b.flap) * 0.14f;
            } else {
                float climbBoost = b.vy > 0.04 ? 1.35f : 1.0f;
                b.flap += k.flapRate * climbBoost * Mth.TWO_PI;
            }
        }

        if (r.nextInt(650) == 0 && !f.birds.isEmpty()) {
            Bird b = f.birds.get(r.nextInt(f.birds.size()));
            String soundId = k == Kind.GULL ? "ambient.gull"
                    : k == Kind.HAWK ? "ambient.hawk"
                    : k == Kind.SONGBIRD ? "ambient.bird_song"
                    : "ambient.crow";
            if (k != Kind.BAT) {
                level.playLocalSound(b.x, b.y, b.z, com.skycraft.audio.AudioSounds.get(soundId),
                        SoundSource.AMBIENT, 0.48f, k == Kind.RAVEN ? 0.72f : 1.0f, false);
            }
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
                if ((x - c.x) * (x - c.x) + (z - c.z) * (z - c.z) > 155 * 155) continue;
                int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
                pose.pushPose();
                pose.translate(x - c.x, y - c.y, z - c.z);
                pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partial, b.yawO, b.yaw)));
                pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(partial, b.pitchO, b.pitch)));
                pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partial, b.rollO, b.roll)));
                drawBird(pose, vc, f.kind, b, Mth.lerp(partial, b.flapO, b.flap), light);
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
            pose.mulPose(Axis.YP.rotationDegrees((float) -Math.toDegrees(Math.atan2(-l.dx, l.dz))));
            pose.mulPose(Axis.XP.rotationDegrees(-60f + 120f * t));
            box(pose, vc, -0.06f, -0.08f, -0.3f, 0.06f, 0.08f, 0.25f, 0.62f, 0.5f, 0.5f, light);
            box(pose, vc, -0.07f, -0.04f, -0.24f, 0.07f, 0.06f, 0.18f, 0.78f, 0.36f, 0.32f, light);
            box(pose, vc, -0.01f, -0.1f, 0.25f, 0.01f, 0.1f, 0.4f, 0.4f, 0.32f, 0.3f, light);
            pose.popPose();
        }
        buffers.endBatch(RenderType.entityCutoutNoCull(WHITE));
    }

    /**
     * Sculpted bird geometry: dorsal mantle, lighter breast/belly, distinct head and pointed beak, fanned tail
     * feathers, and two-segment articulated wings (inner arm + outer primary feathers with top/bottom normals) that
     * fold neatly against the body when perched.
     */
    private static void drawBird(PoseStack pose, VertexConsumer vc, Kind k, Bird b, float flap, int light) {
        float s = k.size;
        boolean perched = b.perchTicks > 0;

        float r = k.r, g = k.g, bl = k.b;
        float br = k.br, bg = k.bg, bb = k.bb;
        float wr = k.wr, wg = k.wg, wb = k.wb;
        float tr = k.tr, tg = k.tg, tb = k.tb;
        if (k == Kind.SONGBIRD) {
            if (b.plumage == 1) {
                // Goldfinch plumage: warm golden yellow body with dark slate-charcoal wings & pale tips
                r = 0.86f; g = 0.74f; bl = 0.20f;
                br = 0.92f; bg = 0.84f; bb = 0.34f;
                wr = 0.24f; wg = 0.24f; wb = 0.28f;
                tr = 0.90f; tg = 0.90f; tb = 0.86f;
            } else if (b.plumage == 2) {
                // Robin plumage: warm olive-slate mantle with terracotta-orange breast
                r = 0.38f; g = 0.36f; bl = 0.34f;
                br = 0.84f; bg = 0.42f; bb = 0.22f;
                wr = 0.34f; wg = 0.32f; wb = 0.30f;
                tr = 0.56f; tg = 0.52f; tb = 0.48f;
            }
        }

        if (perched) {
            pose.mulPose(Axis.XP.rotationDegrees(-24f));
        }

        // Sculpted upper torso/mantle & lower breast/belly
        box(pose, vc, -0.075f * s, 0.0f, -0.19f * s, 0.075f * s, 0.068f * s, 0.17f * s, r, g, bl, light);
        box(pose, vc, -0.068f * s, -0.058f * s, -0.17f * s, 0.068f * s, 0.005f * s, 0.14f * s, br, bg, bb, light);

        // Distinct sculpted head + crown + pointed beak for all species
        box(pose, vc, -0.055f * s, -0.015f * s, -0.29f * s, 0.055f * s, 0.075f * s, -0.18f * s, r, g, bl, light);
        box(pose, vc, -0.02f * s, 0.0f, -0.36f * s, 0.02f * s, 0.032f * s, -0.29f * s, k.beakR, k.beakG, k.beakB, light);

        // Fanned tail feathers behind the body
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        float tailSpread = (k == Kind.HAWK ? 0.13f : k == Kind.RAVEN ? 0.11f : 0.09f) * s;
        float tailLen = (k == Kind.HAWK || k == Kind.RAVEN ? 0.38f : 0.33f) * s;
        doubleSidedQuad(vc, m, n,
                -0.05f * s, 0.015f * s, 0.15f * s,
                0.05f * s, 0.015f * s, 0.15f * s,
                tailSpread, 0.005f * s, tailLen,
                -tailSpread, 0.005f * s, tailLen,
                wr, wg, wb, tr, tg, tb, light);

        if (perched) {
            // Folded wings resting neatly along the sides of the body
            for (int side = -1; side <= 1; side += 2) {
                float xIn = side * 0.072f * s;
                float xOut = side * 0.092f * s;
                box(pose, vc, Math.min(xIn, xOut), -0.035f * s, -0.14f * s, Math.max(xIn, xOut), 0.055f * s, 0.22f * s, wr, wg, wb, light);
            }
            return;
        }

        // Two-segment articulated wings (inner wing + outer primary feathers with subtle camber)
        float innerAngle = Mth.sin(flap) * 30f + 6f;
        float outerAngle = Mth.sin(flap - 0.55f) * 26f - 4f;
        float fullSpan = (k == Kind.HAWK ? 0.56f : k == Kind.GULL ? 0.52f : k == Kind.RAVEN ? 0.44f : 0.38f) * s;
        float innerSpan = fullSpan * 0.52f;
        float outerSpan = fullSpan * 0.52f;

        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.065f * s, 0.035f * s, -0.04f * s);
            pose.mulPose(Axis.ZP.rotationDegrees(side * innerAngle));

            Matrix4f m1 = pose.last().pose();
            Matrix3f n1 = pose.last().normal();
            float xInner = side * innerSpan;
            float camber = 0.018f * s;
            // Inner wing segment
            doubleSidedQuad(vc, m1, n1,
                    0, 0, -0.10f * s,
                    xInner, camber, -0.08f * s,
                    xInner, 0, 0.11f * s,
                    0, 0, 0.13f * s,
                    wr, wg, wb, br * 0.9f, bg * 0.9f, bb * 0.9f, light);

            // Outer primary wing segment articulated at the wrist joint
            pose.translate(xInner, camber * 0.5f, -0.01f * s);
            pose.mulPose(Axis.ZP.rotationDegrees(side * outerAngle));
            Matrix4f m2 = pose.last().pose();
            Matrix3f n2 = pose.last().normal();
            float xOuter = side * outerSpan;
            doubleSidedQuad(vc, m2, n2,
                    0, camber * 0.5f, -0.07f * s,
                    xOuter, 0, -0.03f * s,
                    xOuter * 0.88f, 0, 0.07f * s,
                    0, 0, 0.11f * s,
                    tr, tg, tb, tr * 0.92f, tg * 0.92f, tb * 0.92f, light);
            pose.popPose();
        }
    }

    private static void doubleSidedQuad(VertexConsumer vc, Matrix4f m, Matrix3f n,
                                        float x0, float y0, float z0, float x1, float y1, float z1,
                                        float x2, float y2, float z2, float x3, float y3, float z3,
                                        float tr, float tg, float tb, float br, float bg, float bb, int light) {
        // Dorsal upward-facing quad
        vc.vertex(m, x0, y0, z0).color(tr, tg, tb, 1f).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        vc.vertex(m, x1, y1, z1).color(tr, tg, tb, 1f).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        vc.vertex(m, x2, y2, z2).color(tr, tg, tb, 1f).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        vc.vertex(m, x3, y3, z3).color(tr, tg, tb, 1f).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, 1, 0).endVertex();
        // Ventral downward-facing quad (slightly offset & lit for shader visibility from below)
        vc.vertex(m, x3, y3 - 0.002f, z3).color(br, bg, bb, 1f).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, -1, 0).endVertex();
        vc.vertex(m, x2, y2 - 0.002f, z2).color(br, bg, bb, 1f).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, -1, 0).endVertex();
        vc.vertex(m, x1, y1 - 0.002f, z1).color(br, bg, bb, 1f).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, -1, 0).endVertex();
        vc.vertex(m, x0, y0 - 0.002f, z0).color(br, bg, bb, 1f).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0, -1, 0).endVertex();
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
        float rd = r * 0.82f, gd = g * 0.82f, bd = b * 0.82f;
        quad(vc, m, n, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, light);
        quad(vc, m, n, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, rd, gd, bd, light);
        quad(vc, m, n, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r * 0.92f, g * 0.92f, b * 0.92f, light);
        quad(vc, m, n, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r * 0.92f, g * 0.92f, b * 0.92f, light);
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
            b.vx = (r.nextDouble() - 0.5) * 0.16;
            b.vz = (r.nextDouble() - 0.5) * 0.16;
            f.birds.add(b);
        }
        FLOCKS.add(f);
    }
}
