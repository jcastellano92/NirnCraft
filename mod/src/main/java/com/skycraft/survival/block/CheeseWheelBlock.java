package com.skycraft.survival.block;

import com.skycraft.survival.SurvivalRegistry;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A placed cheese wheel: interactive table food on inn counters and dining tables.
 * Right-clicking slices off a cheese wedge (up to 4 wedges per wheel) or eats a slice when sneaking and hungry.
 */
public class CheeseWheelBlock extends Block {
    public static final IntegerProperty WEDGES = IntegerProperty.create("wedges", 1, 4);

    private static final VoxelShape[] SHAPES = new VoxelShape[]{
            Block.box(2, 0, 8, 8, 6, 14),
            Block.box(2, 0, 8, 14, 6, 14),
            Block.box(2, 0, 2, 14, 6, 14),
            Block.box(2, 0, 2, 14, 6, 14)
    };

    public CheeseWheelBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any().setValue(WEDGES, 4));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return useItemOn(net.minecraft.world.item.ItemStack.EMPTY, state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WEDGES);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        int w = state.getValue(WEDGES);
        return SHAPES[Math.max(0, Math.min(3, w - 1))];
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useItemOn(net.minecraft.world.item.ItemStack held263, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        int wedges = state.getValue(WEDGES);
        if (player.isShiftKeyDown() && player.canEat(false)) {
            player.getFoodData().eat(2, 0.3f);
            level.playSound(null, pos, SoundEvents.GENERIC_EAT.value(), SoundSource.PLAYERS, 0.8f, 1.0f);
            level.gameEvent(player, GameEvent.EAT, pos);
        } else {
            ItemStack wedge = new ItemStack(SurvivalRegistry.GOAT_CHEESE_WEDGE.get());
            if (player instanceof ServerPlayer sp) {
                if (!ActionHandler.addToBags(sp, wedge)) {
                    sp.drop(wedge, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
            } else {
                Block.popResource(level, pos, wedge);
            }
            level.playSound(null, pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 0.8f, 1.2f);
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.3f);
        }
        if (wedges > 1) {
            level.setBlock(pos, state.setValue(WEDGES, wedges - 1), 3);
        } else {
            level.removeBlock(pos, false);
            level.gameEvent(player, GameEvent.BLOCK_DESTROY, pos);
        }
        return InteractionResult.CONSUME;
    }
}
