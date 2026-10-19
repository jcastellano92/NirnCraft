package com.nirncraft.roads;

import net.minecraft.resources.ResourceKey;
import com.nirncraft.NirnCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;

/**
 * The lights of the roads, set by how much a road matters (see {@link RoadPaint}):
 * <ul>
 *     <li>{@link #LANTERN} / {@link #SOUL_LANTERN}: the lamps of maintained roads and tunnels. They light at dusk and
 *     go out by day (a random tick looks at the sky, so a road's lamps come on one by one as the light goes, like a
 *     lamplighter's round); under rock, where there is no day, they burn all the time.</li>
 *     <li>{@link #TORCH} / {@link #WALL_TORCH}: the torches of lesser tunnels and paths. Nobody tends a remote one, so
 *     some are found burnt out; anyone can light one or put it out with a use (bare hand, flint, another torch).</li>
 * </ul>
 * Broken, they drop the vanilla lantern or torch. No items of their own: worldgen places them.
 */
public final class RoadLights {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, NirnCraft.MODID);

    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public static final Supplier<Block> LANTERN = BLOCKS.register("road_lantern",
            key -> new DuskLantern(BlockBehaviour.Properties.ofFullCopy(Blocks.LANTERN).setId(ResourceKey.create(Registries.BLOCK, key)).randomTicks()
                    .lightLevel(s -> s.getValue(LIT) ? 15 : 0)));
    public static final Supplier<Block> SOUL_LANTERN = BLOCKS.register("road_soul_lantern",
            key -> new DuskLantern(BlockBehaviour.Properties.ofFullCopy(Blocks.SOUL_LANTERN).setId(ResourceKey.create(Registries.BLOCK, key)).randomTicks()
                    .lightLevel(s -> s.getValue(LIT) ? 10 : 0)));
    public static final Supplier<Block> TORCH = BLOCKS.register("trail_torch",
            key -> new Torch(BlockBehaviour.Properties.ofFullCopy(Blocks.TORCH).setId(ResourceKey.create(Registries.BLOCK, key)).lightLevel(s -> s.getValue(LIT) ? 14 : 0)));
    public static final Supplier<Block> WALL_TORCH = BLOCKS.register("trail_wall_torch",
            key -> new WallTorch(BlockBehaviour.Properties.ofFullCopy(Blocks.WALL_TORCH).setId(ResourceKey.create(Registries.BLOCK, key)).lightLevel(s -> s.getValue(LIT) ? 14 : 0)
                    .overrideLootTable(TORCH.get().getLootTable())));

    private RoadLights() {}

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
    }

    // ------------------------------------------------------------------ placement helpers for worldgen

    /** A road lamp (standing or hanging), soul-fire in Dunmer lands. Starts lit; the first random tick sets it right. */
    public static BlockState lamp(boolean soul, boolean hanging) {
        Block b = soul ? SOUL_LANTERN.get() : LANTERN.get();
        return b.defaultBlockState().setValue(LanternBlock.HANGING, hanging).setValue(LIT, true);
    }

    public static BlockState torch(boolean lit) {
        return TORCH.get().defaultBlockState().setValue(LIT, lit);
    }

    /** A wall torch on the wall behind it, looking out towards {@code facing}. */
    public static BlockState wallTorch(Direction facing, boolean lit) {
        return WALL_TORCH.get().defaultBlockState().setValue(WallTorchBlock.FACING, facing).setValue(LIT, lit);
    }

    /** True when it is dark enough at {@code pos} for a lamp: night, a storm's gloom, or under rock. */
    static boolean dark(Level level, BlockPos pos) {
        int sky = level.getBrightness(LightLayer.SKY, pos) - level.getSkyDarken();
        return sky < 10 || level.isThundering();
    }

    // ------------------------------------------------------------------ the lamp

    /** A lantern that burns from dusk to dawn (and always where no daylight reaches). */
    public static final class DuskLantern extends LanternBlock {
        DuskLantern(Properties p) {
            super(p);
            registerDefaultState(defaultBlockState().setValue(LIT, true));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            super.createBlockStateDefinition(b);
            b.add(LIT);
        }

        @Override
        public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            boolean want = dark(level, pos);
            if (want != state.getValue(LIT)) level.setBlock(pos, state.setValue(LIT, want), Block.UPDATE_ALL);
        }

        @Override
        public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
            super.onPlace(state, level, pos, old, moved);
            if (!level.isClientSide() && !old.is(this)) {
                boolean want = dark(level, pos);
                if (want != state.getValue(LIT)) level.setBlock(pos, state.setValue(LIT, want), Block.UPDATE_ALL);
            }
        }
    }

    // ------------------------------------------------------------------ the torches

    /** Lights or snuffs a torch on use; the shared behaviour of the standing and wall torch. */
    static InteractionResult toggle(BlockState state, Level level, BlockPos pos, Player player) {
        boolean lit = state.getValue(LIT);
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(LIT, !lit), Block.UPDATE_ALL);
            if (lit) {
                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5f, 1.6f);
            } else {
                level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.8f, 1.0f);
            }
        }
        return InteractionResult.SUCCESS;
    }

    public static final class Torch extends TorchBlock {
        Torch(Properties p) {
            super(ParticleTypes.FLAME, p);
            registerDefaultState(defaultBlockState().setValue(LIT, true));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            super.createBlockStateDefinition(b);
            b.add(LIT);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (state.getValue(LIT)) super.animateTick(state, level, pos, random);
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
            return toggle(state, level, pos, player);
        }
    }

    public static final class WallTorch extends WallTorchBlock {
        WallTorch(Properties p) {
            super(ParticleTypes.FLAME, p);
            registerDefaultState(defaultBlockState().setValue(LIT, true));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
            super.createBlockStateDefinition(b);
            b.add(LIT);
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (state.getValue(LIT)) super.animateTick(state, level, pos, random);
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
            return toggle(state, level, pos, player);
        }
    }
}
