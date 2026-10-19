package com.nirncraft.scatter;

import com.nirncraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * Anchor block placed inside wild animal and monster dens ({@link Builders#animalDen()}).
 *
 * <p>Ticks on the server via {@link DenBlockEntity} to populate the den with 1–3 region-appropriate residents
 * (bears, wolves, sabre cats, trolls, giant spiders, spriggans, kagouti, nix-hounds, senche-tigers, wamasu, etc.)
 * tethered to the den territory via {@code mob.setHomeTo(worldPosition, 24)}, and manages their nocturnal/diurnal
 * rest cycles.</p>
 */
public class DenBlock extends BaseEntityBlock {

    public DenBlock(BlockBehaviour.Properties properties) {
        super(properties
                .mapColor(MapColor.DIRT)
                .strength(1.5f, 6.0f)
                .sound(SoundType.ROOTED_DIRT));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DenBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, BiomeBlocks.CREATURE_DEN_BE.get(), DenBlockEntity::serverTick);
    }
}
