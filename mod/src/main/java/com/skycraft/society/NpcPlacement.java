package com.skycraft.society;

import com.skycraft.society.Schedule.Activity;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Where townsfolk should be right now (Skyrim keeps track of what people are doing, so a town you walk into already
 * has its sleepers in bed and its smiths at the forge), and the rescue moves for the ones the pathfinder lets down:
 * stuck at a door, wedged against a wall, or up on a roof. Server side only.
 */
public final class NpcPlacement {
    private NpcPlacement() {}

    /** Whether this townsperson is one of the handful allowed outdoors at night (deterministic per person). */
    public static boolean outAtNight(NpcEntity npc) {
        long h = npc.getUUID().getLeastSignificantBits() >>> 5;
        int n = (int) Math.floorMod(h, 14L);
        NpcRole r = npc.role();
        if (r == NpcRole.BEGGAR || r == NpcRole.BARD) return n < 3;
        if (r == NpcRole.MERCHANT || r == NpcRole.CITIZEN) return n == 0;
        return false;
    }

    /** The anchor an NPC occupies for an activity (bed, workplace, inn, plaza, home), or null if it has no home. */
    @Nullable
    public static BlockPos anchorFor(NpcEntity npc, Activity activity) {
        BlockPos home = npc.getHome();
        if (home == null) return null;
        BlockPos bed = npc.getBed();
        int hour = Schedule.hour(npc.level());
        BlockPos at = switch (activity) {
            case SLEEP -> {
                BlockPos b = bed;
                if (b == null) {
                    try {
                        b = Settlers.claimBed(npc, home);
                    } catch (RuntimeException e) {
                        b = null;
                    }
                }
                if (b != null) yield b;
                BlockPos inn = Workplaces.innFor(npc);
                yield inn != null ? inn : home;
            }
            case EAT, HOME -> bed != null ? bed : home;
            case WORK -> {
                BlockPos w = Workplaces.workplaceOf(npc);
                yield w != null ? w : bed != null ? bed : home;
            }
            case LUNCH, INN -> {
                BlockPos inn = Workplaces.innFor(npc);
                yield inn != null ? inn : home;
            }
            case WANDER, PLAY -> {
                BlockPos l = hour < 12 && activity == Activity.WANDER ? null : Workplaces.leisureFor(npc);
                yield l != null ? l : bed != null ? bed : home;
            }
            default -> null;
        };
        return at;
    }

    /**
     * Puts a freshly loaded townsperson where the schedule says they are now: in bed at night, at the workplace by
     * day, at the inn in the evening. Returns true once settled (or nothing to do), false to try again later (the
     * target chunk is not loaded yet).
     */
    public static boolean snapToSchedule(NpcEntity npc) {
        Level level = npc.level();
        Activity a = Schedule.activity(npc);
        if (a == Activity.IDLE) return true;
        // at night only the few who are meant to be out stay in the street: the rest go indoors
        if (Npcs.isNight(level) && outAtNight(npc) && a != Activity.SLEEP) return true;
        BlockPos at = anchorFor(npc, a);
        if (at == null) return true;
        if (!level.isLoaded(at)) return false;
        npc.activity = a;
        if (npc.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5) < 4.0 && Math.abs(npc.getY() - at.getY()) < 2) {
            if (a == Activity.SLEEP) lieDown(npc, at);
            return true;
        }
        if (a == Activity.SLEEP && level.getBlockState(at).is(BlockTags.BEDS)) {
            lieDown(npc, at);
            return true;
        }
        BlockPos spot = freeSpot(npc, at, 3);
        if (spot == null) return false;
        moveTo(npc, spot);
        return true;
    }

    /**
     * Puts the person in their bed now (a closed door, a stair they cannot take: the night must not find them awake
     * on the wrong side of a wall). True once they lie there; false if the bed is gone, taken or not loaded.
     */
    public static boolean putToBed(NpcEntity npc, BlockPos bed) {
        if (!npc.level().isLoaded(bed)) return false;
        lieDown(npc, bed);
        return npc.isSleeping();
    }

    private static void lieDown(NpcEntity npc, BlockPos bed) {
        Level level = npc.level();
        BlockState s = level.getBlockState(bed);
        if (!s.is(BlockTags.BEDS)) return;
        if (s.hasProperty(net.minecraft.world.level.block.BedBlock.OCCUPIED) && s.getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) return;
        try {
            npc.getNavigation().stop();
            npc.setPos(bed.getX() + 0.5, bed.getY() + 0.6, bed.getZ() + 0.5);
            npc.startSleeping(bed);
        } catch (RuntimeException ignored) {
            // never worth a failed tick
        }
    }

    public static void moveTo(NpcEntity npc, BlockPos spot) {
        npc.getNavigation().stop();
        npc.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        npc.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, npc.getYRot(), 0f);
        npc.setYHeadRot(npc.getYRot());
    }

    /** The nearest standable cell to {@code near} (solid floor, room for the body, no fluid), within {@code radius}. */
    @Nullable
    public static BlockPos freeSpot(NpcEntity npc, BlockPos near, int radius) {
        Level level = npc.level();
        if (!level.isLoaded(near)) return null;
        AABB box = npc.getBoundingBox();
        double w = box.getXsize() * 0.5, h = box.getYsize();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : new int[]{0, 1, -1, 2, -2}) {
                        BlockPos p = near.offset(dx, dy, dz);
                        BlockPos below = p.below();
                        if (!level.isLoaded(p)) continue;
                        BlockState fs = level.getBlockState(below);
                        if (!fs.isFaceSturdy(level, below, Direction.UP) || !level.getFluidState(p).isEmpty()) continue;
                        AABB at = new AABB(p.getX() + 0.5 - w, p.getY(), p.getZ() + 0.5 - w, p.getX() + 0.5 + w, p.getY() + h, p.getZ() + 0.5 + w);
                        if (level.noCollision(npc, at)) return p;
                    }
                }
            }
        }
        return null;
    }

    /** The highest y of the anchors this person lives and works by, or Integer.MIN_VALUE when it has none. */
    private static int anchorCeiling(NpcEntity npc) {
        int y = Integer.MIN_VALUE;
        for (BlockPos p : new BlockPos[]{npc.getHome(), npc.getBed(), npc.getWorkplace(), npc.getInn(), npc.getDestination()}) {
            if (p != null) y = Math.max(y, p.getY());
        }
        return y;
    }

    /** Whether the cell is a roof-level place for someone whose anchors are lower: open to the sky and well above them. */
    public static boolean isRoof(NpcEntity npc, BlockPos pos) {
        int ceil = anchorCeiling(npc);
        if (ceil == Integer.MIN_VALUE) return false;
        return pos.getY() > ceil + 3 && npc.level().canSeeSky(pos);
    }

    /**
     * An NPC standing on a roof (open to the sky, more than four blocks above everything it is anchored to, not on a
     * stair or ladder) is put back down at its bed, workplace or home. Returns true if it moved.
     */
    public static boolean rescueFromRoof(NpcEntity npc) {
        int ceil = anchorCeiling(npc);
        if (ceil == Integer.MIN_VALUE || npc.getY() <= ceil + 4 || !npc.onGround()) return false;
        Level level = npc.level();
        BlockPos here = npc.blockPosition();
        if (!level.canSeeSky(here)) return false;
        BlockState below = level.getBlockState(here.below());
        if (below.getBlock() instanceof StairBlock || below.getBlock() instanceof LadderBlock || level.getBlockState(here).getBlock() instanceof LadderBlock) {
            return false;
        }
        BlockPos[] refs = {npc.getBed(), npc.getWorkplace(), npc.getHome(), npc.getInn()};
        for (BlockPos ref : refs) {
            if (ref == null) continue;
            BlockPos spot = freeSpot(npc, ref, 3);
            if (spot != null) {
                moveTo(npc, spot);
                return true;
            }
        }
        return false;
    }
}
