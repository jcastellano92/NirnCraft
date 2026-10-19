package com.nirncraft.loot.container;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A NirnCraft loot container block (docs/CONTAINERS.md): a chest, hoard, strongbox, sack, urn or remains. Rendered from
 * plain block models only ({@link RenderShape#MODEL}); the lid is the {@link #LID} property, one model per value, so
 * the far-field renderer that reads block models sees every container and every lid position. The contents live in
 * the {@link LootContainerBlockEntity}, one copy per player for world loot.
 *
 * <p>Subclasses add the {@link #STYLE} property ({@link Styled}), the six-way facing of a barrel ({@link Barrel}) or a
 * shelf's book count instead of a lid ({@link Bookshelf}).</p>
 */
public class LootContainerBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<LidState> LID = EnumProperty.create("lid", LidState.class);
    public static final EnumProperty<ContainerStyle> STYLE = EnumProperty.create("style", ContainerStyle.class);
    /** Ticks between two lid models while opening or closing. */
    static final int LID_STEP = 2;
    /** Ticks between two checks that the players with the container open are still there. */
    static final int RECHECK = 5;

    private final ContainerKind kind;

    public LootContainerBlock(ContainerKind kind, Properties props) {
        super(props);
        this.kind = kind;
        registerDefaultState(defaults(stateDefinition.any()));
    }

    protected BlockState defaults(BlockState s) {
        return s.setValue(FACING, Direction.NORTH).setValue(LID, LidState.CLOSED);
    }

    public ContainerKind kind() {
        return kind;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LID);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return kind.shape;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LootContainerBlockEntity(pos, state);
    }

    /** Opens the player's copy (the lock and theft checks ran before, in {@code crime.Locks} and {@code crime.Theft}). */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof LootContainerBlockEntity be) {
            player.openMenu(be);
        }
        return InteractionResult.SUCCESS;
    }

    /** World loot is fixed in place for survival players (Skyrim's chests are not furniture you carry off). */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!player.isCreative() && level.getBlockEntity(pos) instanceof LootContainerBlockEntity be && be.isWorldLoot()) return 0f;
        return super.getDestroyProgress(state, player, level, pos);
    }

    /** The lid steps toward open while someone looks inside and back to closed after; also rechecks the viewers. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof LootContainerBlockEntity be) be.tickLid(state);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return false;
    }

    // ------------------------------------------------------------------ variants

    /** A container with a {@link #STYLE}: chest, hoard chest, urn. */
    public static class Styled extends LootContainerBlock {
        public Styled(ContainerKind kind, Properties props) {
            super(kind, props);
        }

        @Override
        protected BlockState defaults(BlockState s) {
            return super.defaults(s).setValue(STYLE, ContainerStyle.NORDIC);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, LID, STYLE);
        }
    }

    /** A barrel faces any of six directions (standing, lying, upside down), like the vanilla one. */
    public static class Barrel extends LootContainerBlock {
        public static final EnumProperty<Direction> FACING6 = BlockStateProperties.FACING;

        public Barrel(ContainerKind kind, Properties props) {
            super(kind, props);
        }

        @Override
        protected BlockState defaults(BlockState s) {
            return s.setValue(FACING6, Direction.UP).setValue(LID, LidState.CLOSED);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING6, LID);
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING6, ctx.getNearestLookingDirection().getOpposite());
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING6, rotation.rotate(state.getValue(FACING6)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING6)));
        }
    }

    /**
     * A bookshelf: no lid, a {@link #BOOKS} count instead (0 empty .. 3 full), set when it is placed from its table and,
     * on a player's own shelf, from what is on it. Styled like the furniture of its culture.
     */
    public static class Bookshelf extends LootContainerBlock {
        public static final net.minecraft.world.level.block.state.properties.IntegerProperty BOOKS =
                net.minecraft.world.level.block.state.properties.IntegerProperty.create("books", 0, 3);

        public Bookshelf(ContainerKind kind, Properties props) {
            super(kind, props);
        }

        @Override
        protected BlockState defaults(BlockState s) {
            return s.setValue(FACING, Direction.NORTH).setValue(STYLE, ContainerStyle.NORDIC).setValue(BOOKS, 3);
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING, STYLE, BOOKS);
        }

        @Override
        public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(BOOKS, 0);
        }
    }
}
