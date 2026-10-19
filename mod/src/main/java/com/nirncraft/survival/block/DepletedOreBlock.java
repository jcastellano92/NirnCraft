package com.nirncraft.survival.block;

import com.nirncraft.survival.OreVeins;
import com.nirncraft.survival.SurvivalConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A mined-out ore vein (Skyrim: "Depleted"). Looks like its host rock with a dull, picked-over vein, cannot be mined
 * in survival and drops nothing. Its block entity remembers the original ore, which grows back once the configured
 * time has passed (scheduled ticks, with random ticks as a fallback for chunks that were unloaded meanwhile).
 */
public class DepletedOreBlock extends BaseEntityBlock {
    public enum Host implements StringRepresentable {
        STONE, DEEPSLATE, NETHERRACK;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Plain rock used if the original ore was somehow lost. */
        public BlockState rock() {
            return switch (this) {
                case DEEPSLATE -> Blocks.DEEPSLATE.defaultBlockState();
                case NETHERRACK -> Blocks.NETHERRACK.defaultBlockState();
                default -> Blocks.STONE.defaultBlockState();
            };
        }
    }

    public static final EnumProperty<Host> HOST = EnumProperty.create("host", Host.class);
    /** Longest single scheduled-tick wait; sleeping advances the clock, so we re-check at least this often. */
    private static final int MAX_WAIT = 6000;

    public DepletedOreBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(HOST, Host.STONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HOST);
    }

    @Override
    @SuppressWarnings("deprecation")
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DepletedOreBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        check(level, pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        check(level, pos, state);
    }

    /** Restores the ore if its time has come, otherwise schedules the next check. */
    public static void check(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DepletedOreBlock block)) return;
        if (!(level.getBlockEntity(pos) instanceof DepletedOreBlockEntity ore)) {
            level.setBlock(pos, state.getValue(HOST).rock(), 3);
            return;
        }
        long now = OreVeins.clock(level);
        long left = ore.regenAt() - now;
        long max = SurvivalConfig.ORE_REGEN_TICKS.get();
        if (left > max * 2L) {
            // the clock was turned back (/time set): don't wait for ages
            ore.setup(ore.original(), now + max);
            left = max;
        }
        if (left <= 0) {
            BlockState original = ore.original();
            level.setBlock(pos, original != null && !original.isAir() ? original : state.getValue(HOST).rock(), 3);
        } else {
            level.scheduleTick(pos, block, (int) Math.max(1, Math.min(MAX_WAIT, left)));
        }
    }

    /** Ticks until the next check of a depleted vein that is due in {@code left} ticks. */
    static int waitFor(long left) {
        return (int) Math.max(1, Math.min(MAX_WAIT, left));
    }
}
