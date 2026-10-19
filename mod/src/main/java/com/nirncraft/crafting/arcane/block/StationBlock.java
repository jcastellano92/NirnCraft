package com.nirncraft.crafting.arcane.block;

import com.nirncraft.crafting.arcane.ArcanePackets;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Arcane Enchanter and the Alchemy Lab: crafting stations whose screens are opened by the server (so all
 * actions are validated against the station's position).
 */
public class StationBlock extends Block {
    public static final EnumProperty<net.minecraft.core.Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public enum Kind {ENCHANTER, ALCHEMY}

    private static final VoxelShape ENCHANTER_SHAPE = Shapes.or(Block.box(0, 0, 0, 16, 12, 16), Block.box(3, 12, 4, 13, 14, 12));
    private static final VoxelShape ALCHEMY_SHAPE = Shapes.or(Block.box(0, 0, 0, 16, 13, 16), Block.box(2, 13, 2, 14, 18, 14));

    private final Kind kind;

    public StationBlock(Kind kind, Properties props) {
        super(props);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public Kind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
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

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return kind == Kind.ENCHANTER ? ENCHANTER_SHAPE : ALCHEMY_SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            if (com.nirncraft.crime.Ownership.isOwnedByOther(sp, level, pos) && !com.nirncraft.economy.Shop.isOpen(level)) {
                com.nirncraft.core.Notifier.message(sp, net.minecraft.network.chat.Component.translatable("dialogue.nirncraft.economy.closed"));
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.CHEST_LOCKED, net.minecraft.sounds.SoundSource.BLOCKS, 0.8f, 1.0f);
                return InteractionResult.CONSUME;
            }
            SkyNetwork.sendToPlayer(sp, new ArcanePackets.OpenStation(kind.ordinal(), pos));
        }
        return com.nirncraft.platform.Interactions.sidedSuccess(level.isClientSide());
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5, y = pos.getY() + 1.0, z = pos.getZ() + 0.5;
        if (kind == Kind.ENCHANTER) {
            if (random.nextInt(3) == 0) {
                level.addParticle(ParticleTypes.ENCHANT, x + (random.nextDouble() - 0.5) * 1.6, y + random.nextDouble() * 0.8,
                        z + (random.nextDouble() - 0.5) * 1.6, (random.nextDouble() - 0.5) * 0.3, -0.3, (random.nextDouble() - 0.5) * 0.3);
            }
        } else if (random.nextInt(6) == 0) {
            level.addParticle(net.minecraft.core.particles.SpellParticleOption.create(ParticleTypes.EFFECT, 0xFFFFFF, 1f), x + (random.nextDouble() - 0.5) * 0.6, y + 0.15, z + (random.nextDouble() - 0.5) * 0.6, 0, 0.02, 0);
        }
    }
}
