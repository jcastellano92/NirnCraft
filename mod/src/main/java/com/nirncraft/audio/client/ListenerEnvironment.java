package com.nirncraft.audio.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the listener is, acoustically: how enclosed the space around the camera is, how big it is, whether there is
 * a roof (inside a building) or rock overhead (underground), what the walls are made of. Sampled once a second with
 * fourteen short rays from the camera and smoothed, so it costs nothing per sound. The fields are read by the sound
 * thread ({@link Acoustics}), hence volatile.
 */
public final class ListenerEnvironment {
    private static final double RANGE = 32.0;
    private static final Vec3[] RAYS;

    static {
        Vec3[] axes = {new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(0, 1, 0), new Vec3(0, -1, 0)};
        RAYS = new Vec3[14];
        System.arraycopy(axes, 0, RAYS, 0, 6);
        int i = 6;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sy = -1; sy <= 1; sy += 2)
                for (int sz = -1; sz <= 1; sz += 2) RAYS[i++] = new Vec3(sx, sy * 0.7, sz).normalize();
    }

    /** 0 = open air .. 1 = every ray hit a wall. */
    public static volatile float enclosure;
    /** Mean distance to the walls in blocks (open rays count as {@link #RANGE}). */
    public static volatile float roomSize = (float) RANGE;
    /** Fraction of hard surfaces (stone, metal, ice) among the walls; the rest is wood, earth, cloth. */
    public static volatile float hardness = 0.5f;
    /** Reverb wet level 0..1 and decay time in seconds for the current space. */
    public static volatile float wet;
    public static volatile float decay = 1.0f;
    /** Under a roof with walls around, but not deep underground: a house, an inn, a hall. */
    public static volatile boolean interior;
    /** Deep enough under the surface with no sky light: caves, mines, barrows. */
    public static volatile boolean underground;
    public static volatile boolean underwater;
    /** Blocks between the camera and the surface above (0 when outside). */
    public static volatile float depth;
    /** 0..1: how much outdoor sounds are muffled for the listener (interior / underground). */
    public static volatile float muffle;

    private static int ticks;

    private ListenerEnvironment() {}

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        if (++ticks % 20 != 0) return;
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 eye = camera.isInitialized() ? camera.position() : mc.player.getEyePosition();
        Level level = mc.level;
        BlockPos at = BlockPos.containing(eye);

        int hits = 0;
        int hard = 0;
        double dist = 0;
        boolean roof = false;
        for (int i = 0; i < RAYS.length; i++) {
            Vec3 to = eye.add(RAYS[i].scale(RANGE));
            BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) {
                dist += RANGE;
                continue;
            }
            hits++;
            dist += hit.getLocation().distanceTo(eye);
            if (i == 4) roof = true;
            BlockState s = level.getBlockState(hit.getBlockPos());
            if (!(s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL) || s.is(BlockTags.SUBSTRATE_OVERWORLD)
                    || s.is(BlockTags.LEAVES) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS))) hard++;
        }
        float enc = hits / (float) RAYS.length;
        float size = (float) (dist / RAYS.length);
        float hardFrac = hits == 0 ? 0.5f : hard / (float) hits;

        int sky = level.getBrightness(LightLayer.SKY, at);
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        float dep = Math.max(0f, (float) (surface - eye.y));
        boolean under = dep > 6 && sky < 5 && roof;
        boolean inside = !under && roof && enc >= 0.55f && sky < 14;

        enclosure = Mth.lerp(0.6f, enclosure, enc);
        roomSize = Mth.lerp(0.6f, roomSize, size);
        hardness = Mth.lerp(0.6f, hardness, hardFrac);
        depth = dep;
        underground = under;
        interior = inside;
        underwater = mc.player.isEyeInFluid(FluidTags.WATER);

        // reverb: nothing in the open, a short warm room in houses, long and dark in caves and great halls
        float wetTarget = enclosure < 0.4f ? 0f : Mth.clamp((enclosure - 0.4f) / 0.45f, 0f, 1f);
        if (under) wetTarget = Math.max(wetTarget, 0.55f);
        if (inside) wetTarget *= 0.55f + 0.45f * Mth.clamp((roomSize - 4f) / 12f, 0f, 1f);
        wet = Mth.lerp(0.5f, wet, wetTarget);
        float d = 0.35f + roomSize * 0.12f;
        d *= 0.7f + 0.6f * hardness;
        if (under) d = Math.max(d, 1.6f);
        decay = Mth.clamp(Mth.lerp(0.5f, decay, d), 0.3f, 7.0f);
        float muffleTarget = under ? 0.85f : (inside ? 0.6f : 0f);
        muffle = Mth.lerp(0.5f, muffle, muffleTarget);
    }

    public static void reset() {
        enclosure = 0f;
        wet = 0f;
        muffle = 0f;
        interior = false;
        underground = false;
        underwater = false;
        depth = 0f;
    }

    /** Whether a position counts as "outside" (open to the sky). */
    public static boolean outdoors(Level level, BlockPos pos) {
        return level.canSeeSky(pos) || level.getBrightness(LightLayer.SKY, pos) >= 14;
    }
}
