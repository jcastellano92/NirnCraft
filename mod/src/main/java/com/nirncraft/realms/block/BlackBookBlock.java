package com.nirncraft.realms.block;

import com.nirncraft.realms.RealmTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/**
 * A Black Book lying open on a pedestal of pages. Reading it ({@code use}):
 * <ul>
 *     <li>{@link Mode#ENTER} (Solstheim's altars): pulls the reader into Apocrypha, at the chapter the book belongs to;</li>
 *     <li>{@link Mode#NEXT} (end of a chapter): to the next chapter, the last one to the Summit;</li>
 *     <li>{@link Mode#EXIT}: back out to Nirn ("the book closes").</li>
 * </ul>
 */
public class BlackBookBlock extends HorizontalDirectionalBlock {
    public enum Mode implements StringRepresentable {
        ENTER, NEXT, EXIT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Mode> MODE = EnumProperty.create("mode", Mode.class);
    private static final VoxelShape SHAPE = Shapes.or(Block.box(3, 0, 3, 13, 11, 13), Block.box(1, 11, 2, 15, 14, 14));

    public BlackBookBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MODE, Mode.ENTER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MODE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) RealmTravel.readBlackBook(sp, state.getValue(MODE), pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        for (int i = 0; i < 2; i++) {
            level.addParticle(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5,
                    (random.nextDouble() - 0.5) * 2, random.nextDouble() * 0.6 - 1.0, (random.nextDouble() - 0.5) * 2);
        }
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.SQUID_INK, pos.getX() + random.nextDouble(), pos.getY() + 1.0,
                    pos.getZ() + random.nextDouble(), 0, 0.02, 0);
        }
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return ItemStack.EMPTY;
    }
}
