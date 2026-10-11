package com.skycraft.tamriel.settlement.kit;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The blocks of one culture style. Fields are filled once by {@link Kits} and never changed afterwards.
 * Stairs/slab fields must hold stair/slab blocks; log fields hold pillars (the builder sets the axis).
 */
public final class Palette {
    public String name = "nord";

    // ---- structure
    public BlockState foundation = Blocks.COBBLESTONE.defaultBlockState();
    public BlockState floor = Blocks.SPRUCE_PLANKS.defaultBlockState();
    public BlockState floor2 = Blocks.OAK_PLANKS.defaultBlockState();
    /** Lower wall course (the first one or two rows). */
    public BlockState wallLow = Blocks.COBBLESTONE.defaultBlockState();
    public BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState();
    /** Occasional variation mixed into {@link #wall}. */
    public BlockState wall2 = Blocks.SPRUCE_PLANKS.defaultBlockState();
    /** Corner posts and frame (a pillar block). */
    public BlockState post = Blocks.SPRUCE_LOG.defaultBlockState();
    /** Horizontal beams (a pillar block). */
    public BlockState beam = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
    public BlockState roof = Blocks.SPRUCE_STAIRS.defaultBlockState();
    public BlockState roofSlab = Blocks.SPRUCE_SLAB.defaultBlockState();
    public BlockState roofFull = Blocks.SPRUCE_PLANKS.defaultBlockState();
    /** Stairs for eaves, arches and steps. */
    public BlockState trim = Blocks.COBBLESTONE_STAIRS.defaultBlockState();
    public BlockState trimSlab = Blocks.COBBLESTONE_SLAB.defaultBlockState();
    public BlockState window = Blocks.SPRUCE_FENCE.defaultBlockState();
    public BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState();
    public BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
    public BlockState gate = Blocks.SPRUCE_FENCE_GATE.defaultBlockState();
    public BlockState trapdoor = Blocks.SPRUCE_TRAPDOOR.defaultBlockState();
    public BlockState sign = Blocks.SPRUCE_WALL_SIGN.defaultBlockState();
    public BlockState light = Blocks.LANTERN.defaultBlockState();

    // ---- furnishing
    public BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
    public BlockState slab = Blocks.SPRUCE_SLAB.defaultBlockState();
    public BlockState stairs = Blocks.SPRUCE_STAIRS.defaultBlockState();
    public BlockState carpet = Blocks.BROWN_CARPET.defaultBlockState();
    public BlockState carpet2 = Blocks.WHITE_CARPET.defaultBlockState();
    /** Banner/awning/accent colour block (wool or terracotta). */
    public BlockState accent = Blocks.BLUE_WOOL.defaultBlockState();
    public BlockState bed = Blocks.BROWN_BED.defaultBlockState();
    public BlockState hearth = Blocks.CAMPFIRE.defaultBlockState();

    // ---- town
    public BlockState path = Blocks.DIRT_PATH.defaultBlockState();
    public BlockState path2 = Blocks.COARSE_DIRT.defaultBlockState();
    public BlockState plaza = Blocks.COBBLESTONE.defaultBlockState();
    public BlockState plaza2 = Blocks.STONE_BRICKS.defaultBlockState();
    public BlockState cityWall = Blocks.STONE_BRICKS.defaultBlockState();
    public BlockState cityWall2 = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    public BlockState cityWallStairs = Blocks.STONE_BRICK_STAIRS.defaultBlockState();
    public BlockState cityWallSlab = Blocks.STONE_BRICK_SLAB.defaultBlockState();
    public BlockState cityWallWall = Blocks.STONE_BRICK_WALL.defaultBlockState();
    /** Decorative column / pillar block (any). */
    public BlockState column = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
    /** Glass (full block) for domes, spires and greenhouses. */
    public BlockState glass = Blocks.GLASS.defaultBlockState();

    // ---- shapes
    /** House shell, big hall shell, and tower shell. */
    public Shell shell = Shell.GABLE;
    public Shell hallShell = Shell.GABLE;
    public Shell towerShell = Shell.TOWER;
    /** Timber frame visible on the walls. */
    public boolean timber = true;
    /** Roof steepness: 1 = one block up per block across (45 degrees), 2 = steep (two up per block). */
    public int pitch = 1;
    /** Raise ordinary houses on posts over the ground (marsh and water towns). */
    public boolean stilts = false;
    /** Nordic crossed ridge-beam horns on halls. */
    public boolean horns = false;
    /** Thatch (hay) on poorer roofs. */
    public boolean thatch = false;

    public Palette copy() {
        Palette p = new Palette();
        p.name = name;
        p.foundation = foundation;
        p.floor = floor;
        p.floor2 = floor2;
        p.wallLow = wallLow;
        p.wall = wall;
        p.wall2 = wall2;
        p.post = post;
        p.beam = beam;
        p.roof = roof;
        p.roofSlab = roofSlab;
        p.roofFull = roofFull;
        p.trim = trim;
        p.trimSlab = trimSlab;
        p.window = window;
        p.door = door;
        p.fence = fence;
        p.gate = gate;
        p.trapdoor = trapdoor;
        p.sign = sign;
        p.light = light;
        p.planks = planks;
        p.slab = slab;
        p.stairs = stairs;
        p.carpet = carpet;
        p.carpet2 = carpet2;
        p.accent = accent;
        p.bed = bed;
        p.hearth = hearth;
        p.path = path;
        p.path2 = path2;
        p.plaza = plaza;
        p.plaza2 = plaza2;
        p.cityWall = cityWall;
        p.cityWall2 = cityWall2;
        p.cityWallStairs = cityWallStairs;
        p.cityWallSlab = cityWallSlab;
        p.cityWallWall = cityWallWall;
        p.column = column;
        p.glass = glass;
        p.shell = shell;
        p.hallShell = hallShell;
        p.towerShell = towerShell;
        p.timber = timber;
        p.pitch = pitch;
        p.stilts = stilts;
        p.horns = horns;
        p.thatch = thatch;
        return p;
    }
}
