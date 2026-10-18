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

    /** True if {@code pos} itself is fire, campfire, embers, magma, or lava. */
    public static boolean isDirectFire(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        var b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.BaseFireBlock
                || b instanceof net.minecraft.world.level.block.CampfireBlock
                || b instanceof net.minecraft.world.level.block.MagmaBlock
                || b instanceof com.skycraft.fire.BigFireBlock
                || (!st.getFluidState().isEmpty() && st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA));
    }

    /** True if {@code state} is a bottom half-slab. */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                && state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
    }

    /** True if {@code pos} or {@code pos.below()} is a bottom half-slab (such as a fire-pit rim). */
    public static boolean isSlabHazard(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return isBottomSlab(level.getBlockState(pos)) || isBottomSlab(level.getBlockState(pos.below()));
    }

    /**
     * True if {@code pos} is a counter, table, desk, chair, bench, stool, barrel, crate, shelf, bed, fence, or
     * crafting/work station that an NPC should stand beside rather than on top of.
     */
    public static boolean isFurnitureOrStation(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        var b = state.getBlock();
        if (b instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureContainerBlock) {
            return true;
        }
        if (b instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock) {
            var cat = state.getValue(com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock.SHAPE);
            return cat != com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.ShapeCat.THRONE;
        }
        if (b instanceof net.minecraft.world.level.block.BarrelBlock
                || b instanceof net.minecraft.world.level.block.AbstractChestBlock<?>
                || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                || b instanceof net.minecraft.world.level.block.CraftingTableBlock
                || b instanceof net.minecraft.world.level.block.SmithingTableBlock
                || b instanceof net.minecraft.world.level.block.CartographyTableBlock
                || b == net.minecraft.world.level.block.Blocks.FLETCHING_TABLE
                || b instanceof net.minecraft.world.level.block.LoomBlock
                || b instanceof net.minecraft.world.level.block.StonecutterBlock
                || b instanceof net.minecraft.world.level.block.GrindstoneBlock
                || b instanceof net.minecraft.world.level.block.AnvilBlock
                || b instanceof net.minecraft.world.level.block.LecternBlock
                || b instanceof net.minecraft.world.level.block.EnchantingTableBlock
                || b instanceof net.minecraft.world.level.block.BrewingStandBlock
                || b instanceof net.minecraft.world.level.block.AbstractCauldronBlock
                || b instanceof net.minecraft.world.level.block.AbstractFurnaceBlock
                || b instanceof net.minecraft.world.level.block.ComposterBlock
                || b instanceof net.minecraft.world.level.block.ChiseledBookShelfBlock
                || b instanceof net.minecraft.world.level.block.BedBlock
                || b instanceof net.minecraft.world.level.block.FenceBlock
                || b instanceof net.minecraft.world.level.block.WallBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock
                || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.ChainBlock
                || b instanceof net.minecraft.world.level.block.LanternBlock
                || b instanceof net.minecraft.world.level.block.CandleBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock) {
            return true;
        }
        if (b == net.minecraft.world.level.block.Blocks.BOOKSHELF) {
            return true;
        }
        // Upside-down stairs are used as market-stall counters (Works.stall) and shelves
        if (b instanceof StairBlock
                && state.hasProperty(StairBlock.HALF)
                && state.getValue(StairBlock.HALF) == net.minecraft.world.level.block.state.properties.Half.TOP) {
            return true;
        }
        // Top slabs placed directly over a solid floor block are guild-hall counters or pantry shelves
        if (b instanceof net.minecraft.world.level.block.SlabBlock
                && state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.TOP) {
            BlockPos under = pos.below();
            if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                return true;
            }
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        if (key != null && "skycraft".equals(key.getNamespace())) {
            String path = key.getPath();
            if (path.contains("forge") || path.contains("smelter") || path.contains("workbench")
                    || path.contains("grindstone") || path.contains("tanning") || path.contains("alchemy")
                    || path.contains("enchanter") || path.contains("cooking_pot") || path.contains("shrine")
                    || path.contains("cheese") || path.contains("counter") || path.contains("table")
                    || path.contains("desk") || path.contains("chair") || path.contains("stool")
                    || path.contains("bench") || path.contains("crate") || path.contains("wardrobe")
                    || path.contains("shelf") || path.contains("nightstand") || path.contains("keg")) {
                return true;
            }
        }
        return false;
    }

    /** True if standing at {@code pos} puts the entity in or on top of furniture, a counter, or a crafting station. */
    public static boolean isFurnitureHazard(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return isFurnitureOrStation(level, pos) || isFurnitureOrStation(level, pos.below());
    }

    private static boolean isRemovableWorkSeat(BlockState state) {
        if (state.getBlock() instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock) {
            var cat = state.getValue(com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock.SHAPE);
            return cat == com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.ShapeCat.SEAT;
        }
        return false;
    }

    /** True if {@code pos} or any block within 1 block is fire, campfire, embers, magma, or lava. */
    public static boolean isFireHazard(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (isDirectFire(level, pos.offset(dx, dy, dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The anchor an NPC occupies for an activity (bed, workplace, inn, plaza, home), or null if it has no home. */
    @Nullable
    public static BlockPos anchorFor(NpcEntity npc, Activity activity) {
        BlockPos home = npc.getHome();
        if (home == null) return null;
        if ("survivor".equals(npc.getPersistentData().getStringOr(Housing.JOB_KEY, ""))
                || (com.skycraft.fire.aftermath.HelgenFolk.inHelgen(npc) && !com.skycraft.fire.aftermath.HelgenFolk.isHelgenMerchant(npc) && npc.role().civilian)) {
            BlockPos l = Workplaces.leisureFor(npc);
            return l != null && (!npc.level().isLoaded(l) || !isFireHazard(npc.level(), l)) ? l : home;
        }
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
        if (at != null && npc.level().isLoaded(at) && isFireHazard(npc.level(), at)) {
            if (at.equals(npc.getWorkplace())) npc.setWorkplace(null);
            if (at.equals(npc.getBed())) npc.setBed(null);
            if (at.equals(npc.getInn())) npc.setInn(null);
            return !npc.level().isLoaded(home) || !isFireHazard(npc.level(), home) ? home : freeSpot(npc, home, 8);
        }
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
        if (npc.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5) < 4.0 && Math.abs(npc.getY() - at.getY()) < 2
                && !isFurnitureHazard(level, npc.blockPosition()) && !isSlabHazard(level, npc.blockPosition())) {
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
        if (isFireHazard(level, bed)) return;
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
        npc.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, npc.getYRot(), 0f);
        npc.setYHeadRot(npc.getYRot());
    }

    /** The nearest standable cell to {@code near} (solid floor, room for the body, no fluid, no fire, no furniture), within {@code radius}. */
    @Nullable
    public static BlockPos freeSpot(NpcEntity npc, BlockPos near, int radius) {
        Level level = npc.level();
        if (!level.isLoaded(near)) return null;
        boolean anchorIndoors = !level.canSeeSky(near) && !level.canSeeSky(near.above());
        AABB box = npc.getBoundingBox();
        double w = box.getXsize() * 0.5, h = box.getYsize();
        for (int pass = 0; pass < 2; pass++) {
            for (int dy : new int[]{0, -1, 1, -2}) {
                for (int r = 0; r <= radius; r++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                            BlockPos p = near.offset(dx, dy, dz);
                            BlockPos below = p.below();
                            if (!level.isLoaded(p)) continue;
                            if (anchorIndoors && (level.canSeeSky(p) || level.canSeeSky(p.above()))) continue;
                            if (level.canSeeSky(p) && hasRoomBelow(level, p)) continue;
                            BlockState fs = level.getBlockState(below);
                            if (!fs.isFaceSturdy(level, below, Direction.UP) || !level.getFluidState(p).isEmpty()) continue;
                            if (isFireHazard(level, p) || isSlabHazard(level, p) || isFurnitureHazard(level, p)) continue;
                            AABB at = new AABB(p.getX() + 0.5 - w, p.getY(), p.getZ() + 0.5 - w, p.getX() + 0.5 + w, p.getY() + h, p.getZ() + 0.5 + w);
                            if (!level.noCollision(npc, at)) continue;
                            if (pass == 0 && !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, at.inflate(0.35, 0.1, 0.35),
                                    e -> e != npc && e.isAlive() && (e instanceof NpcEntity || e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager)).isEmpty()) {
                                continue;
                            }
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isNaturalGround(BlockState state) {
        if (state.is(BlockTags.SUBSTRATE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA)) return true;
        var b = state.getBlock();
        return b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                || b == net.minecraft.world.level.block.Blocks.DIRT_PATH
                || b == net.minecraft.world.level.block.Blocks.FARMLAND
                || b == net.minecraft.world.level.block.Blocks.GRAVEL
                || b == net.minecraft.world.level.block.Blocks.CLAY
                || b == net.minecraft.world.level.block.Blocks.MUD
                || b == net.minecraft.world.level.block.Blocks.SNOW_BLOCK;
    }

    private static boolean isRoomSpace(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.getCollisionShape(level, pos).isEmpty()) return true;
        var b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.DoorBlock
                || b instanceof net.minecraft.world.level.block.LadderBlock
                || b instanceof net.minecraft.world.level.block.CarpetBlock
                || b instanceof net.minecraft.world.level.block.BedBlock
                || isFurnitureOrStation(level, pos);
    }

    private static boolean hasDryFloorBelow(Level level, BlockPos from) {
        for (int d = 1; d <= 7; d++) {
            BlockPos p = from.below(d);
            if (!level.getFluidState(p).isEmpty()) return false;
            BlockState s = level.getBlockState(p);
            if (s.isFaceSturdy(level, p, Direction.UP) || isFurnitureOrStation(level, p)) return true;
        }
        return false;
    }

    private static boolean columnHasRoomBelow(Level level, BlockPos pos, int maxDy) {
        for (int dy = 2; dy <= maxDy; dy++) {
            BlockPos b1 = pos.below(dy);
            BlockPos b2 = pos.below(dy + 1);
            if (!level.canSeeSky(b1) && isRoomSpace(level, b1) && isRoomSpace(level, b2) && hasDryFloorBelow(level, b2)) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code pos} sits on a building roof, eave overhang, parapet/wall top, or stall canopy above a room. */
    public static boolean hasRoomBelow(Level level, BlockPos pos) {
        BlockState under = level.getBlockState(pos.below());
        if (isNaturalGround(under)) return false;
        if (columnHasRoomBelow(level, pos, 10)) return true;
        // Roof directly over an interior partition wall (room on both opposite sides) or exterior wall/eave (room on one side and drop-off on another)
        boolean roomNorth = columnHasRoomBelow(level, pos.north(), 8);
        boolean roomSouth = columnHasRoomBelow(level, pos.south(), 8);
        if (roomNorth && roomSouth) return true;
        boolean roomEast = columnHasRoomBelow(level, pos.east(), 8);
        boolean roomWest = columnHasRoomBelow(level, pos.west(), 8);
        if (roomEast && roomWest) return true;
        if (roomNorth || roomSouth || roomEast || roomWest) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n1 = pos.relative(d).below(1);
                BlockPos n2 = pos.relative(d).below(2);
                if (level.getBlockState(n1).getCollisionShape(level, n1).isEmpty()
                        && level.getBlockState(n2).getCollisionShape(level, n2).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a safe ground-level or indoor floor position near {@code (x, z)}, never on a building roof, fire, slab,
     * or furniture. Used by surface/heightmap spawners so NPCs and guards never spawn on roofs.
     */
    public static BlockPos safeSurfaceAt(net.minecraft.server.level.ServerLevel level, int x, int z) {
        int topY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos top = new BlockPos(x, topY, z);
        if (!hasRoomBelow(level, top) && !isFireHazard(level, top) && !isSlabHazard(level, top) && !isFurnitureHazard(level, top)
                && level.getFluidState(top).isEmpty() && level.getFluidState(top.below()).isEmpty()) {
            return top;
        }
        // Step off the roof/furniture onto the nearest street or ground cell within 8 blocks
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = x + dx, cz = z + dz;
                    BlockPos probe = new BlockPos(cx, topY, cz);
                    if (!level.isLoaded(probe)) continue;
                    int cy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    if (cy > topY + 2) continue;
                    BlockPos cand = new BlockPos(cx, cy, cz);
                    BlockPos below = cand.below();
                    if (!level.getFluidState(cand).isEmpty() || !level.getFluidState(below).isEmpty()) continue;
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
                    if (!level.getBlockState(cand).getCollisionShape(level, cand).isEmpty()
                            || !level.getBlockState(cand.above()).getCollisionShape(level, cand.above()).isEmpty()) continue;
                    if (hasRoomBelow(level, cand) || isFireHazard(level, cand) || isSlabHazard(level, cand) || isFurnitureHazard(level, cand)) {
                        continue;
                    }
                    return cand;
                }
            }
        }
        // Fallback: scan down below the roof for the indoor ground floor
        for (int dy = 3; dy <= 14; dy++) {
            BlockPos p = top.below(dy);
            if (!level.isLoaded(p)) break;
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                    && level.getFluidState(p).isEmpty()
                    && !isFireHazard(level, p) && !isSlabHazard(level, p) && !isFurnitureHazard(level, p)) {
                return p;
            }
        }
        return top;
    }

    /** Finds a ground position near {@code (x, z)} around {@code refY} that is not on a building roof. */
    @Nullable
    public static BlockPos outdoorGroundAt(net.minecraft.server.level.ServerLevel level, int x, int z, int refY) {
        BlockPos top = Encounters.groundAt(level, x, z);
        if (top != null && Math.abs(top.getY() - refY) <= 4 && !hasRoomBelow(level, top)
                && !isSlabHazard(level, top) && !isFurnitureHazard(level, top)) {
            return top;
        }
        // Scan near refY for an indoor or street-level floor below any roof
        for (int dy : new int[]{0, -1, 1, -2, 2, -3, -4, -5}) {
            BlockPos p = new BlockPos(x, refY + dy, z);
            if (!level.isLoaded(p)) continue;
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                    && level.getFluidState(p).isEmpty()
                    && !isFireHazard(level, p) && !isSlabHazard(level, p) && !isFurnitureHazard(level, p)
                    && !(level.canSeeSky(p) && hasRoomBelow(level, p))) {
                return p;
            }
        }
        BlockPos safe = safeSurfaceAt(level, x, z);
        return Math.abs(safe.getY() - refY) <= 8 && !hasRoomBelow(level, safe) ? safe : null;
    }

    /** The lowest y of the anchors this person lives and works by, or Integer.MIN_VALUE when it has none. */
    private static int anchorCeiling(NpcEntity npc) {
        int y = Integer.MIN_VALUE;
        for (BlockPos p : new BlockPos[]{npc.getBed(), npc.getWorkplace(), npc.getHome(), npc.getInn()}) {
            if (p != null) {
                y = y == Integer.MIN_VALUE ? p.getY() : Math.min(y + 2, Math.max(y, p.getY()));
            }
        }
        return y;
    }

    /** Whether the cell is a roof-level place for someone whose anchors are lower: open to the sky and well above them, or above a room cavity. */
    public static boolean isRoof(NpcEntity npc, BlockPos pos) {
        if (!npc.level().canSeeSky(pos)) return false;
        if (hasRoomBelow(npc.level(), pos)) return true;
        int ceil = anchorCeiling(npc);
        if (ceil == Integer.MIN_VALUE) return false;
        return pos.getY() > ceil + 3;
    }

    /**
     * An NPC standing in a campfire/fire trench, on an indoor half-slab, or on top of a counter/table/chair/station
     * is extinguished and moved immediately to a safe adjacent floor cell. Also repairs workplace/home anchors saved
     * 1 block above a counter or chair in existing worlds.
     */
    public static boolean rescueFromFireOrSlab(NpcEntity npc) {
        if (npc.isSleeping()) return false;
        Level level = npc.level();
        BlockPos here = npc.blockPosition();
        if (!level.isLoaded(here)) return false;

        // Normalize workplace & home if an older save placed them 1 block above a chair or counter
        BlockPos work = npc.getWorkplace();
        if (work != null && level.isLoaded(work)) {
            if (isFurnitureOrStation(level, work.below())
                    && level.getBlockState(work.below(2)).isFaceSturdy(level, work.below(2), Direction.UP)) {
                work = work.below();
                npc.setWorkplace(work);
                if (npc.getPersistentData().contains(Housing.WORK_KEY)) {
                    npc.getPersistentData().putLong(Housing.WORK_KEY, work.asLong());
                }
            }
            if (npc.role() != NpcRole.JARL && isRemovableWorkSeat(level.getBlockState(work))) {
                level.removeBlock(work, false);
            }
        }
        BlockPos home = npc.getHome();
        if (home != null && level.isLoaded(home) && isFurnitureOrStation(level, home.below())
                && level.getBlockState(home.below(2)).isFaceSturdy(level, home.below(2), Direction.UP)) {
            npc.setHome(work != null ? work : home.below(), 24);
        }

        boolean inFire = npc.isOnFire() || isDirectFire(level, here) || isDirectFire(level, here.below());
        boolean onIndoorSlab = !level.canSeeSky(here) && (isSlabHazard(level, here) || isFireHazard(level, here) && npc.getY() > here.getY() + 0.35);
        boolean onFurniture = isFurnitureHazard(level, here);
        if (!inFire && !onIndoorSlab && !onFurniture) return false;

        npc.clearFire();
        // If standing on a counter/desk and there is a chair wedged in the 1-block gap behind it, clear that chair first
        if (onFurniture && npc.role() != NpcRole.JARL) {
            BlockPos counterPos = isFurnitureOrStation(level, here.below()) ? here.below() : here;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos adj = counterPos.relative(d);
                if (isRemovableWorkSeat(level.getBlockState(adj))) {
                    BlockPos behind = adj.relative(d);
                    if (!level.getBlockState(behind).getCollisionShape(level, behind).isEmpty()) {
                        level.removeBlock(adj, false);
                    }
                }
            }
        }

        for (BlockPos ref : new BlockPos[]{npc.getWorkplace(), here, npc.getInn(), npc.getHome(), npc.getBed()}) {
            if (ref == null || !level.isLoaded(ref)) continue;
            BlockPos spot = freeSpot(npc, ref, 4);
            if (spot != null) {
                moveTo(npc, spot);
                return true;
            }
        }
        return false;
    }

    /**
     * An NPC standing on a roof (open to the sky and either above an indoor room cavity or more than 3 blocks above
     * its anchors, not on a ladder) is put back down at its bed, workplace, home, or the ground/indoor floor below.
     */
    public static boolean rescueFromRoof(NpcEntity npc) {
        if (!npc.onGround() || npc.isSleeping()) return false;
        Level level = npc.level();
        BlockPos here = npc.blockPosition();
        if (!level.canSeeSky(here)) return false;
        BlockState below = level.getBlockState(here.below());
        if (below.getBlock() instanceof LadderBlock || level.getBlockState(here).getBlock() instanceof LadderBlock) {
            return false;
        }
        int ceil = anchorCeiling(npc);
        boolean onRoof = hasRoomBelow(level, here) || (ceil != Integer.MIN_VALUE && npc.getY() > ceil + 3);
        if (!onRoof) return false;
        BlockPos[] refs = {npc.getBed(), npc.getWorkplace(), npc.getHome(), npc.getInn()};
        for (BlockPos ref : refs) {
            if (ref == null || !level.isLoaded(ref)) continue;
            if (level.canSeeSky(ref) && hasRoomBelow(level, ref)) continue;
            BlockPos spot = freeSpot(npc, ref, 4);
            if (spot != null) {
                if (npc.getHome() != null && level.isLoaded(npc.getHome()) && hasRoomBelow(level, npc.getHome())) {
                    npc.setHome(spot, 20);
                }
                moveTo(npc, spot);
                return true;
            }
        }
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            BlockPos safe = safeSurfaceAt(sl, here.getX(), here.getZ());
            BlockPos spot = freeSpot(npc, safe, 4);
            if (spot == null && !hasRoomBelow(level, safe)) spot = safe;
            if (spot != null && !spot.equals(here)) {
                if (npc.getHome() == null || (level.isLoaded(npc.getHome()) && hasRoomBelow(level, npc.getHome()))) {
                    npc.setHome(spot, 20);
                }
                moveTo(npc, spot);
                return true;
            }
        }
        return false;
    }
}
