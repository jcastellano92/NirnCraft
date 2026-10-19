package com.nirncraft.survival.camping;

import com.nirncraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * One piece of a small hide tent (placed whole by the tent kit, see {@link TentKitItem}). A tent is 3 wide and 3 deep:
 * slanted canvas on both sides meeting in a ridge, a back wall, and the middle row left free for a bedroll. {@code FACING}
 * is the side the entrance faces. Breaking any piece takes the whole tent down and gives the kit back.
 */
public class TentBlock extends HorizontalDirectionalBlock {
    public enum Part implements StringRepresentable {
        /** Side A (clockwise of the way into the tent), front to back; then side B; then the back of the middle row. */
        A_FRONT(1, 0), A_MID(1, 1), A_BACK(1, 2), B_FRONT(-1, 0), B_MID(-1, 1), B_BACK(-1, 2), BACK(0, 2);

        /** Offset sideways (+1 = side A) and inward (0 = front row) from the tent's front middle block. */
        public final int side, depth;

        Part(int side, int depth) {
            this.side = side;
            this.depth = depth;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    private static final Map<Direction, VoxelShape[]> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            // the canvas of side A rises toward side B: a low and a high step
            VoxelShape a = shape(facing, true);
            VoxelShape b = shape(facing, false);
            SHAPES.put(facing, new VoxelShape[]{a, b});
        }
    }

    public TentBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.BACK));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    /** The tent's front middle block (where the bedroll's foot lies). */
    public static BlockPos origin(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Part part = state.getValue(PART);
        Direction in = facing.getOpposite();
        Direction sideA = in.getClockWise();
        return pos.relative(in, -part.depth).relative(sideA, -part.side);
    }

    /** Where {@code part} of a tent whose front middle block is {@code origin} goes. */
    public static BlockPos partPos(BlockPos origin, Direction facing, Part part) {
        Direction in = facing.getOpposite();
        return origin.relative(in, part.depth).relative(in.getClockWise(), part.side);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockPos origin = origin(pos, state);
            Direction facing = state.getValue(FACING);
            for (Part part : Part.values()) {
                BlockPos p = partPos(origin, facing, part);
                if (!p.equals(pos) && level.getBlockState(p).getBlock() == this) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 35);
            }
            if (!player.isCreative()) Block.popResource(level, pos, new ItemStack(SurvivalRegistry.TENT_KIT.get()));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        Part part = state.getValue(PART);
        if (part == Part.BACK) return Block.box(0, 0, 0, 16, 16, 16);
        return SHAPES.get(state.getValue(FACING))[part.side > 0 ? 0 : 1];
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Block.box(0, 0, 0, 16, 16, 16);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    /** Two steps under the slanted canvas, low on the outer edge. */
    private static VoxelShape shape(Direction facing, boolean sideA) {
        Direction in = facing.getOpposite();
        Direction outer = sideA ? in.getClockWise() : in.getCounterClockWise();
        // in block coordinates: the outer half is 8 high, the inner half 16
        return switch (outer) {
            case EAST -> Shapes.or(Block.box(8, 0, 0, 16, 8, 16), Block.box(0, 0, 0, 8, 16, 16));
            case WEST -> Shapes.or(Block.box(0, 0, 0, 8, 8, 16), Block.box(8, 0, 0, 16, 16, 16));
            case SOUTH -> Shapes.or(Block.box(0, 0, 8, 16, 8, 16), Block.box(0, 0, 0, 16, 16, 8));
            default -> Shapes.or(Block.box(0, 0, 0, 16, 8, 8), Block.box(0, 0, 8, 16, 16, 16));
        };
    }
}
