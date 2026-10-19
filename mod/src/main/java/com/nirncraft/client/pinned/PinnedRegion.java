package com.nirncraft.client.pinned;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import net.neoforged.neoforge.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * A pin's blocks as the section compiler reads a region: Minecraft's own SectionCompiler meshes the pin from this
 * instead of the live level (the public, deprecated RenderSectionRegion constructor with no section copies; every
 * reader of them is answered here). Block states, light and biome tints come from the snapshot only, so the copy
 * looks the same wherever and whenever it is drawn and long after its chunks unloaded.
 *
 * <p>Light: the compiler's lighters (vanilla's BlockModelLighter, NeoForge's EnhancedBlockModelLighter, the fluid
 * renderer) read it through getBrightness via LightCoordsUtil's brightness getter; getRawBrightness and canSeeSky
 * follow. The live level is only kept for its height range.
 */
final class PinnedRegion extends RenderSectionRegion {
    private final PinSnapshot pin;
    private final BlockState air = Blocks.AIR.defaultBlockState();

    @SuppressWarnings("deprecation")
    PinnedRegion(ClientLevel level, PinSnapshot pin) {
        super(level, 0, 0, 0, new SectionCopy[0]);
        this.pin = pin;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int id = pin.volume.state(pos.getX(), pos.getY(), pos.getZ());
        return id == PinVolume.OUTSIDE ? air : Block.stateById(id);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return pin.cardinalLighting;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY ? pin.volume.skyLight(pos.getX(), pos.getY(), pos.getZ())
                : pin.volume.blockLight(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {  // LevelLightEngine.getRawBrightness's rule
        return Math.max(pin.volume.blockLight(pos.getX(), pos.getY(), pos.getZ()),
                pin.volume.skyLight(pos.getX(), pos.getY(), pos.getZ()) - darkening);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        int kind = resolver == BiomeColors.GRASS_COLOR_RESOLVER ? PinVolume.TINT_GRASS
                : resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER ? PinVolume.TINT_FOLIAGE
                : resolver == BiomeColors.WATER_COLOR_RESOLVER ? PinVolume.TINT_WATER
                : resolver == BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER ? PinVolume.TINT_DRY_FOLIAGE : -1;
        if (kind >= 0) return pin.volume.tint(kind, pos.getX(), pos.getY(), pos.getZ());
        return pin.otherTint(pos, resolver);  // a mod's own resolver: what the level answered while the pin was taken
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;  // block entities are drawn from the render states the pin extracted (PinSnapshot.blockEntities)
    }

    @Override
    public ModelData getModelData(BlockPos pos) {
        return pin.modelData.getOrDefault(pos.asLong(), ModelData.EMPTY);
    }

    @Override
    public @Nullable AuxiliaryLightManager getAuxLightManager(ChunkPos pos) {
        return null;
    }

    @Override
    public int getMinY() {
        return pin.minY;
    }

    @Override
    public int getHeight() {
        return pin.height;
    }
}
