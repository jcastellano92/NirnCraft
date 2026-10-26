package com.nirncraft.hold;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.function.IntPredicate;

/**
 * Where a held player will land. A player held in the air who will fall once released (not a spectator, not flying, with
 * gravity) is held at the landing place instead: the camera, and with it the client's loading (level 0's reach is about
 * 150 blocks from the eye: from 380 blocks up it loaded nothing where the player was about to land), waits there, under
 * the loading screen. The release puts the player back where the hold began; the fall is the game's as before, into a
 * near field that is loaded.
 */
final class HoldLanding {
    /** A drop of this many blocks or less: held where they are (standing, a step, a slab). */
    static final double MIN_DROP = 4.0;

    private HoldLanding() {}

    /** Whether a player in this real state falls once released. */
    static boolean falls(boolean spectator, boolean mayfly, boolean flying, boolean noGravity) {
        return !spectator && !noGravity && !(mayfly && flying);
    }

    /**
     * The feet's landing height below {@code feetY}: on top of the first block at or below the feet that stops a fall
     * ({@code stops}: a collision shape or a fluid), scanning down to {@code minY}; NaN when nothing below (the void) or
     * the drop is {@link #MIN_DROP} or less.
     */
    static double landingY(double feetY, int minY, IntPredicate stops) {
        for (int y = (int) Math.floor(feetY); y >= minY; y--) {
            if (!stops.test(y)) continue;
            double land = y + 1.0;
            return feetY - land > MIN_DROP ? land : Double.NaN;
        }
        return Double.NaN;
    }

    /** Where {@code p} (in the real state the hold keeps) lands from where they are; null when they stay where they are. */
    @Nullable
    static Vec3 of(ServerPlayer p, GameType realMode, boolean mayfly, boolean flying, boolean noGravity) {
        if (!falls(realMode == GameType.SPECTATOR, mayfly, flying, noGravity)) return null;
        Vec3 at = p.position();
        ServerLevel level = p.level();
        int x = (int) Math.floor(at.x), z = (int) Math.floor(at.z);
        double land;
        try {
            if (level.hasChunk(x >> 4, z >> 4)) {
                BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                land = landingY(at.y, level.getMinY(), y -> {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    return !s.getFluidState().isEmpty() || !s.getCollisionShape(level, m).isEmpty();
                });
            } else {  // (not loaded: the generator's surface at that column)
                int top = level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING, level,
                        level.getChunkSource().randomState());
                land = at.y - top > MIN_DROP ? top : Double.NaN;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return Double.isNaN(land) ? null : new Vec3(at.x, land, at.z);
    }
}
