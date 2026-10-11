package com.skycraft.explore;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An Ayleid well (Oblivion's wells of the Heartland elves): a welkynd stone on a white pillar. Touching it restores
 * all your magicka, once per day for each well; it glows bright enough to be seen across the Heartlands at night.
 */
public class AyleidWellBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 14, 13);
    private static final long DAY = 24000L;

    public AyleidWellBlock(Properties props) {
        super(props);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        PlayerData data = SkyData.get(sp);
        CompoundTag mod = data.module("explore");
        CompoundTag wells = mod.getCompound("wells");
        String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        long now = level.getDayTime();
        long last = wells.contains(key) ? wells.getLong(key) : Long.MIN_VALUE / 2;
        if (now - last < DAY) {
            Notifier.message(sp, Component.translatable("explore.skycraft.ayleid_well.spent"));
            return InteractionResult.CONSUME;
        }
        wells.putLong(key, now);
        mod.put("wells", wells);
        data.setMagicka(data.maxMagicka());
        data.markDirty();
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1f, 1.4f);
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 24, 0.4, 0.6, 0.4, 0.02);
        }
        Notifier.message(sp, Component.translatable("explore.skycraft.ayleid_well.restored"));
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.3 + random.nextDouble() * 0.4, pos.getY() + 0.9,
                    pos.getZ() + 0.3 + random.nextDouble() * 0.4, 0, 0.03, 0);
        }
    }
}
