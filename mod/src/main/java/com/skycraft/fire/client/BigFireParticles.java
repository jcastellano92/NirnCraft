package com.skycraft.fire.client;

import com.skycraft.fire.BigFireBlock.Kind;
import com.skycraft.fire.FireConfig;
import com.skycraft.fire.FireSounds;
import com.skycraft.visual.VisualParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

/** Client fire ambience, linked only through the physical-side handoff. */
public final class BigFireParticles {
    private BigFireParticles() {}

    public static void animate(Kind kind, Level level, BlockPos pos, RandomSource r) {
        boolean outdoor = level.dimension() == Level.OVERWORLD && level.canSeeSky(pos.above());
        float wind = outdoor ? com.skycraft.atmosphere.client.ClientWeather.wind() : 0f;
        double wx = outdoor ? com.skycraft.atmosphere.client.ClientWeather.windX() * wind * 0.065 : 0.0;
        double wz = outdoor ? com.skycraft.atmosphere.client.ClientWeather.windZ() * wind * 0.065 : 0.0;
        if (kind == Kind.EMBERS) {
            if (r.nextInt(18) == 0) {
                level.addParticle(ParticleTypes.SMOKE, pos.getX() + r.nextDouble(), pos.getY() + 0.2, pos.getZ() + r.nextDouble(), wx * 0.7, 0.03, wz * 0.7);
            }
            return;
        }
        double x = pos.getX() + 0.2 + r.nextDouble() * 0.6, z = pos.getZ() + 0.2 + r.nextDouble() * 0.6;
        if (r.nextInt(kind == Kind.BASE ? 5 : 9) == 0) {
            level.addParticle(ParticleTypes.LARGE_SMOKE, x, pos.getY() + 0.9, z, wx, 0.06, wz);
        }
        if (outdoor && wind > 0.28f && r.nextInt(7) == 0) {
            level.addParticle(ParticleTypes.FLAME, x, pos.getY() + 0.5 + r.nextDouble() * 0.4, z, wx * 0.85, 0.025, wz * 0.85);
        }
        if (r.nextInt(10) == 0) {
            level.addParticle(VisualParticles.EMBER.get(), x, pos.getY() + 0.6, z,
                    (r.nextDouble() - 0.5) * 0.04 + wx * 1.3, 0.05, (r.nextDouble() - 0.5) * 0.04 + wz * 1.3);
        }
        if (kind == Kind.BASE && r.nextInt(150) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, FireSounds.FIRE_CRACKLE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.35f * FireConfig.AMBIENT_VOLUME.get().floatValue(),
                    0.9f + r.nextFloat() * 0.25f, false);
        }
    }
}
