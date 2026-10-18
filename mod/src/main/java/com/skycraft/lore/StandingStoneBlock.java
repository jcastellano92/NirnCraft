package com.skycraft.lore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * A Standing Stone: a two-block-tall carved monolith with a glowing constellation. Right-click it to receive the
 * sign's blessing. Unbreakable in survival; generated in the world by {@link StandingStoneShrineFeature}.
 */
public class StandingStoneBlock extends Block {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<net.minecraft.core.Direction> FACING = HorizontalDirectionalBlock.FACING;

    // Front faces north/south (stone is wide along X).
    private static final VoxelShape LOWER_X = Shapes.or(Block.box(1, 0, 4, 15, 3, 12), Block.box(2, 3, 5, 14, 16, 11));
    private static final VoxelShape UPPER_X = Shapes.or(Block.box(2, 0, 5, 14, 10, 11), Block.box(3, 10, 5, 13, 13, 11), Block.box(5, 13, 5, 11, 15, 11));
    // Front faces east/west (stone is wide along Z).
    private static final VoxelShape LOWER_Z = Shapes.or(Block.box(4, 0, 1, 12, 3, 15), Block.box(5, 3, 2, 11, 16, 14));
    private static final VoxelShape UPPER_Z = Shapes.or(Block.box(5, 0, 2, 11, 10, 14), Block.box(5, 10, 3, 11, 13, 13), Block.box(5, 13, 5, 11, 15, 11));

    private final StandingStone sign;
    private final int color;

    public StandingStoneBlock(StandingStone sign, Properties props) {
        super(props);
        this.sign = sign;
        this.color = sign.color;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    /** For other kinds of sacred stone (Solstheim's All-Maker stones): their own glow, their own blessing. */
    protected StandingStoneBlock(int color, Properties props) {
        super(props);
        this.sign = null;
        this.color = color;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    /** The birthsign, or null for a stone that isn't one of the thirteen. */
    public StandingStone sign() {
        return sign;
    }

    /** What touching the stone does (the base block at {@code base}). */
    protected void activate(ServerPlayer player, BlockPos base) {
        if (sign != null) StandingStones.activate(player, sign, base);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        boolean alongX = state.getValue(FACING).getAxis() == Direction.Axis.Z;
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        if (alongX) return lower ? LOWER_X : UPPER_X;
        return lower ? LOWER_Z : UPPER_Z;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        if (pos.getY() >= (level.getMaxY()+1) - 1 || !level.getBlockState(pos.above()).canBeReplaced(ctx)) return null;
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(HALF, DoubleBlockHalf.LOWER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    /** Each half needs the other: if one goes, so does the other (creative breaking, explosions in creative...). */
    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks263, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighbor, net.minecraft.util.RandomSource random263) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
            return neighbor.is(this) && neighbor.getValue(HALF) != half ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks263, pos, direction, neighborPos, neighbor, random263);
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @SuppressWarnings("deprecation")
        @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            BlockPos base = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
            activate(sp, base);
        }
        return com.skycraft.platform.Interactions.sidedSuccess(level.isClientSide());
    }

    /** Motes of the sign's color rise from the sigil; now and then a spark drifts up into the sky. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) != DoubleBlockHalf.UPPER) return;
        float r = (color >> 16 & 255) / 255f;
        float g = (color >> 8 & 255) / 255f;
        float b = (color & 255) / 255f;
        Direction facing = state.getValue(FACING);
        for (int i = 0; i < 2; i++) {
            if (random.nextInt(3) != 0) continue;
            double side = random.nextBoolean() ? 0.4 : -0.4;
            double x = pos.getX() + 0.5 + facing.getStepX() * side + (random.nextDouble() - 0.5) * Math.abs(facing.getStepZ()) * 0.7;
            double z = pos.getZ() + 0.5 + facing.getStepZ() * side + (random.nextDouble() - 0.5) * Math.abs(facing.getStepX()) * 0.7;
            double y = pos.getY() - 0.6 + random.nextDouble() * 1.4;
            level.addParticle(new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, r, g, b), 0.8f), x, y, z, 0, 0.02, 0);
        }
        if (random.nextInt(10) == 0) {
            level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    (random.nextDouble() - 0.5) * 0.02, 0.06 + random.nextDouble() * 0.04, (random.nextDouble() - 0.5) * 0.02);
        }
    }
}
