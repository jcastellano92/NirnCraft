package com.skycraft.survival;

import com.skycraft.core.Buffs;
import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;

/** Praying at a shrine of the Divines: cures every disease and grants that Divine's blessing (one at a time). */
public final class Shrines {
    private Shrines() {}

    public static void pray(ServerPlayer player, Divine divine, BlockPos pos) {
        Diseases.cureAll(player);
        int ticks = SurvivalConfig.BLESSING_TICKS.get();
        for (Divine other : Divine.VALUES) {
            if (other == divine) continue;
            player.removeEffect(com.skycraft.platform.Effects.holder(SurvivalEffects.BLESSINGS.get(other).get()));
            if (Buffs.active(player, other.buffFlag())) Buffs.clear(player, other.buffFlag());
        }
        player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(SurvivalEffects.BLESSINGS.get(divine).get()), ticks, 0, false, false, true));
        Buffs.apply(player, divine.buffFlag(), ticks);
        SurvivalEvents.reconcileBonuses(player);
        SkyData.get(player).addStat("shrines_prayed", 1);

        Notifier.message(player, Component.translatable("message.skycraft.survival.blessed", divine.displayName()));
        Notifier.message(player, Component.translatable("survival.skycraft.blessing." + divine.id() + ".desc"));
        ServerLevel level = player.level();
        level.playSound(null, pos, com.skycraft.world.WorldSounds.MAGIC_SHRINE_PRAY.get(), SoundSource.BLOCKS, 1.4f, 1.0f);
        level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 16, 0.3, 0.4, 0.3, 0.02);
    }
}
