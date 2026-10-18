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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/** Blocks of the arsenal: the Staff Enchanter (Dragonborn). */
public final class ArsenalBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, Skycraft.MODID);

    public static final Supplier<Block> STAFF_ENCHANTER = BLOCKS.register("staff_enchanter", () -> new StaffEnchanterBlock(
            com.skycraft.platform.SkyProperties.block("staff_enchanter").mapColor(MapColor.COLOR_GRAY).strength(3.5f, 9f).requiresCorrectToolForDrops()
                    .lightLevel(state -> 5).noOcclusion().sound(SoundType.STONE)));
    public static final Supplier<Item> STAFF_ENCHANTER_ITEM = ArsenalItems.ITEMS.register("staff_enchanter",
            () -> new BlockItem(STAFF_ENCHANTER.get(), com.skycraft.platform.SkyProperties.item("staff_enchanter").rarity(Rarity.UNCOMMON)));

    private ArsenalBlocks() {}

    /** A carved Dwemer-and-stone pedestal with a socket for a staff; use it to open the enchanting screen. */
    public static class StaffEnchanterBlock extends Block {
        public static final EnumProperty<net.minecraft.core.Direction> FACING = HorizontalDirectionalBlock.FACING;
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
        public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level.isClientSide()) {
                Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.arsenal.client.StaffEnchanterScreen.open(pos));
            }
            return com.skycraft.platform.Interactions.sidedSuccess(level.isClientSide());
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (random.nextInt(4) != 0) return;
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3, pos.getY() + 1.05,
                    pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3, 0, 0.01, 0);
        }
    }
}
