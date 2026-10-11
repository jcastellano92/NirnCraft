package com.skycraft.realms.block;

import com.skycraft.realms.Realm;
import com.skycraft.realms.RealmTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Door to the Shivering Isles: a carved two-block door that is never opened, only passed through. Using either
 * half takes the user (and party members right beside them) into Sheogorath's realm, or with {@code return=true}
 * home again. Placed by structures only.
 */
public class RealmDoorBlock extends HorizontalDirectionalBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty RETURN = RealmPortalBlock.RETURN;
    private static final VoxelShape NS = Block.box(0, 0, 5, 16, 16, 11);
    private static final VoxelShape EW = Block.box(5, 0, 0, 11, 16, 16);

    private final Realm realm;

    public RealmDoorBlock(Properties properties, Realm realm) {
        super(properties);
        this.realm = realm;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(RETURN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, RETURN);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? NS : EW;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) RealmTravel.useDoor(sp, realm, state.getValue(RETURN), pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) != DoubleBlockHalf.UPPER || random.nextInt(4) != 0) return;
        level.addParticle(random.nextBoolean() ? ParticleTypes.WITCH : ParticleTypes.ENCHANT, pos.getX() + random.nextDouble(),
                pos.getY() + random.nextDouble(), pos.getZ() + random.nextDouble(), 0, 0.02, 0);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }
}
