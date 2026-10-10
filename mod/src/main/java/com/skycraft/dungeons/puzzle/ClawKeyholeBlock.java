package com.skycraft.dungeons.puzzle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The keyhole of a Nordic claw door. The door fills a 3x3 doorway: the middle column holds the three rotating rings
 * (top to bottom), the keyhole sits right of the middle ring (seen from the approach), the rest is sealed stone.
 * Turning the matching claw while the rings show its palm opens the whole door; the claw is kept.
 */
public class ClawKeyholeBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final IntegerProperty CLAW = IntegerProperty.create("claw", 0, Claw.VALUES.length - 1);

    public ClawKeyholeBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(CLAW, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, CLAW);
    }

    /** The bottom ring of the door this keyhole belongs to (facing = towards the approach). */
    public static BlockPos ringBase(BlockPos keyhole, Direction facing) {
        return keyhole.relative(facing.getCounterClockWise(), -1).below();
    }

    /** Lateral step from the ring column towards the keyhole. */
    public static Direction keyholeSide(Direction facing) {
        return facing.getCounterClockWise();
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Claw claw = Claw.byIndex(state.getValue(CLAW));
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof DragonClawItem item)) {
            player.displayClientMessage(Component.translatable("message.skycraft.claw_door.locked", Component.translatable("item.skycraft." + claw.itemId())), true);
            return InteractionResult.CONSUME;
        }
        if (item.claw != claw) {
            player.displayClientMessage(Component.translatable("message.skycraft.claw_door.wrong_claw"), true);
            level.playSound(null, pos, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 0.8f, 0.7f);
            return InteractionResult.CONSUME;
        }
        Direction facing = state.getValue(FACING);
        BlockPos base = ringBase(pos, facing);
        for (int i = 0; i < 3; i++) {
            Symbol s = SymbolStoneBlock.symbol(level.getBlockState(base.above(2 - i)));
            if (s != claw.symbol(i)) {
                player.displayClientMessage(Component.translatable("message.skycraft.claw_door.rings"), true);
                level.playSound(null, pos, SoundEvents.CHAIN_HIT, SoundSource.BLOCKS, 0.8f, 0.5f);
                return InteractionResult.CONSUME;
            }
        }
        open(level, base, facing);
        return InteractionResult.CONSUME;
    }

    /** Sinks the 3x3 door into the floor (removes it) with dust and a rumble. */
    private static void open(Level level, BlockPos base, Direction facing) {
        Direction side = keyholeSide(facing);
        for (int lat = -1; lat <= 1; lat++) {
            for (int dy = 0; dy <= 2; dy++) {
                BlockPos p = base.relative(side, lat).above(dy);
                BlockState s = level.getBlockState(p);
                if (s.getBlock() instanceof SymbolStoneBlock || s.getBlock() instanceof ClawKeyholeBlock || s.is(Blocks.CHISELED_STONE_BRICKS)
                        || s.is(Blocks.CHISELED_DEEPSLATE) || s.is(Blocks.POLISHED_DEEPSLATE)) {
                    if (level instanceof ServerLevel server) {
                        server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5,
                                12, 0.3, 0.3, 0.3, 0.05);
                    }
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        level.playSound(null, base, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.2f, 0.45f);
        level.playSound(null, base, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0f, 0.5f);
    }
}
