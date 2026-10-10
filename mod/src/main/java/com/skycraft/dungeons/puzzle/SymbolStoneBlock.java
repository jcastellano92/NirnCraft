package com.skycraft.dungeons.puzzle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Carved stone with an animal glyph on its face. The rotating kind (puzzle pillars, claw door rings) turns to the
 * next glyph when used; the fixed kind is the hint tablet that shows the answer.
 */
public class SymbolStoneBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final IntegerProperty SYMBOL = IntegerProperty.create("symbol", 0, Symbol.COUNT - 1);

    private final boolean rotating;

    public SymbolStoneBlock(boolean rotating, Properties props) {
        super(props);
        this.rotating = rotating;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SYMBOL, 0));
    }

    public boolean rotating() {
        return rotating;
    }

    public static BlockState with(BlockState state, Direction facing, Symbol symbol) {
        return state.setValue(FACING, facing).setValue(SYMBOL, symbol.ordinal());
    }

    public static Symbol symbol(BlockState state) {
        return state.getBlock() instanceof SymbolStoneBlock ? Symbol.byIndex(state.getValue(SYMBOL)) : null;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SYMBOL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!rotating || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level.isClientSide) {
            int next = (state.getValue(SYMBOL) + 1) % Symbol.COUNT;
            level.setBlock(pos, state.setValue(SYMBOL, next), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.6f, 0.6f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
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
}
