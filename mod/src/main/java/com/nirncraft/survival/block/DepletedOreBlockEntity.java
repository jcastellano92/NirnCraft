package com.nirncraft.survival.block;

import com.nirncraft.survival.OreVeins;
import com.nirncraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Remembers the ore a {@link DepletedOreBlock} was and when it grows back (in {@link OreVeins#clock} time). */
public class DepletedOreBlockEntity extends BlockEntity {
    @Nullable
    private BlockState original;
    private long regenAt;

    public DepletedOreBlockEntity(BlockPos pos, BlockState state) {
        super(SurvivalRegistry.DEPLETED_ORE_ENTITY.get(), pos, state);
    }

    public void setup(@Nullable BlockState original, long regenAt) {
        this.original = original;
        this.regenAt = regenAt;
        setChanged();
    }

    @Nullable
    public BlockState original() {
        return original;
    }

    public long regenAt() {
        return regenAt;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // scheduled ticks only count down while the chunk is loaded: re-arm with the real remaining time
        if (level instanceof ServerLevel server && regenAt > 0) {
            server.scheduleTick(worldPosition, getBlockState().getBlock(), DepletedOreBlock.waitFor(regenAt - OreVeins.clock(server)));
        }
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        CompoundTag tag = com.nirncraft.platform.ValueTags.read(input, "original", "regen_at");
        original = com.nirncraft.platform.NbtData.hasType(tag, "original", Tag.TAG_COMPOUND)
                ? NbtUtils.readBlockState(BuiltInRegistries.BLOCK, tag.getCompoundOrEmpty("original")) : null;
        regenAt = tag.getLongOr("regen_at",0L);
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        CompoundTag tag = new CompoundTag();
        if (original != null) tag.put("original", NbtUtils.writeBlockState(original));
        tag.putLong("regen_at", regenAt);
        output.store(tag);
    }
}
