package com.skycraft.arsenal;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Blocks of the arsenal: the Staff Enchanter (Dragonborn). */
public final class ArsenalBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);

    public static final RegistryObject<Block> STAFF_ENCHANTER = BLOCKS.register("staff_enchanter", () -> new StaffEnchanterBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(3.5f, 9f).requiresCorrectToolForDrops()
                    .lightLevel(state -> 5).noOcclusion().sound(SoundType.STONE)));
    public static final RegistryObject<Item> STAFF_ENCHANTER_ITEM = ArsenalItems.ITEMS.register("staff_enchanter",
            () -> new BlockItem(STAFF_ENCHANTER.get(), new Item.Properties().rarity(Rarity.UNCOMMON)));

    private ArsenalBlocks() {}

    /** A carved Dwemer-and-stone pedestal with a socket for a staff; use it to open the enchanting screen. */
    public static class StaffEnchanterBlock extends Block {
        public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
        private static final VoxelShape SHAPE = Shapes.or(Block.box(2, 0, 2, 14, 4, 14), Block.box(5, 4, 5, 11, 12, 11), Block.box(3, 12, 3, 13, 15, 13));

        public StaffEnchanterBlock(Properties props) {
            super(props);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
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
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }

        @Override
        @SuppressWarnings("deprecation")
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (level.isClientSide) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.arsenal.client.StaffEnchanterScreen.open(pos));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (random.nextInt(4) != 0) return;
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3, pos.getY() + 1.05,
                    pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3, 0, 0.01, 0);
        }
    }
}
