package com.skycraft.tamriel.settlement.build;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Block state helpers for the settlement builders: property setters that silently skip blocks without the property,
 * and lookups of other modules' blocks by id (with a vanilla fallback), so worldgen never hard-depends on another
 * module's registration order.
 */
public final class B {
    private static final ConcurrentHashMap<String, BlockState> BY_ID = new ConcurrentHashMap<>();

    private B() {}

    public static BlockState s(Block b) {
        BlockState st = b.defaultBlockState();
        if (b == Blocks.CHISELED_BOOKSHELF) {
            for (int slot : new int[]{0, 1, 2, 4, 5}) {
                var prop = net.minecraft.world.level.block.ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot);
                if (st.hasProperty(prop)) st = st.setValue(prop, true);
            }
        }
        return st;
    }

    /** A block by registry id, or {@code fallback} when it isn't registered. */
    public static BlockState id(String id, Block fallback) {
        BlockState cached = BY_ID.get(id);
        if (cached != null) return cached;
        Identifier rl = Identifier.tryParse(id);
        Block b = rl != null && BuiltInRegistries.BLOCK.containsKey(rl) ? BuiltInRegistries.BLOCK.getValue(rl) : null;
        Block target = (b == null || b == Blocks.AIR) ? fallback : b;
        BlockState st = s(target);
        BY_ID.put(id, st);
        return st;
    }

    public static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, T v) {
        return s.hasProperty(p) ? s.setValue(p, v) : s;
    }

    /** Horizontal facing (stairs, doors, beds, stations, signs...), whichever facing property the block has. */
    public static BlockState face(BlockState s, Direction d) {
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
        if (s.hasProperty(BlockStateProperties.FACING)) return s.setValue(BlockStateProperties.FACING, d);
        Property<?> p = s.getBlock().getStateDefinition().getProperty("facing");
        if (p != null && p.getValueClass() == Direction.class && p.getPossibleValues().contains(d)) {
            return set(s, p, d);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState set(BlockState s, Property<T> p, Object v) {
        return s.setValue(p, (T) v);
    }

    /** A stair facing {@code d} (its tall back on the {@code d} side), upside down if {@code top}. */
    public static BlockState stair(BlockState s, Direction d, boolean top) {
        if (!(s.getBlock() instanceof StairBlock)) return s;
        return s.setValue(StairBlock.FACING, d).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
    }

    public static BlockState slab(BlockState s, boolean top) {
        if (!(s.getBlock() instanceof SlabBlock)) return s;
        return s.setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    public static BlockState doubleSlab(BlockState s) {
        if (!(s.getBlock() instanceof SlabBlock)) return s;
        return s.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
    }

    /** A log (or any pillar) along {@code axis}. */
    public static BlockState axis(BlockState s, Direction.Axis axis) {
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
    }

    public static BlockState lit(BlockState s, boolean lit) {
        return with(s, BlockStateProperties.LIT, lit);
    }

    public static BlockState hanging(BlockState s) {
        return with(s, BlockStateProperties.HANGING, true);
    }

    public static BlockState waterlogged(BlockState s, boolean w) {
        return with(s, BlockStateProperties.WATERLOGGED, w);
    }

    public static boolean isStair(BlockState s) {
        return s.getBlock() instanceof StairBlock;
    }
}
