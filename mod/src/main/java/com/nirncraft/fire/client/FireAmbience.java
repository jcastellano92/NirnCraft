package com.nirncraft.fire.client;

import com.nirncraft.fire.FireConfig;
import com.nirncraft.fire.FireSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Map;

/**
 * The sound of fire sites close to the player: a positional crackle (and, for the big ones, a low roar), creaking and
 * now and then collapsing timber. Quiet by design (the owner's complaint was harsh rustling): every sound is a single
 * positional one-shot with a per-site cooldown of several seconds, volumes are capped, and at most two start per
 * second across all sites.
 */
final class FireAmbience {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<Integer, Long> NEXT = new HashMap<>();
    private static long globalNext;
    private static long nextCollapse;

    private FireAmbience() {}

    static void clear() {
        NEXT.clear();
        globalNext = 0;
        nextCollapse = 0;
    }

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        long now = level.getGameTime();
        if (now % 10 != 3 || now < globalNext) return;
        float master = FireConfig.AMBIENT_VOLUME.get().floatValue();
        if (master <= 0.01f) return;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        ClientFire.View best = null;
        double bestD = 56.0 * 56.0;
        synchronized (ClientFire.SERVER) {
            if (ClientFire.serverCurrent()) {
                for (ClientFire.View v : ClientFire.SERVER) {
                    double dx = v.x - px, dy = v.y - py, dz = v.z - pz;
                    double d = dx * dx + dy * dy * 0.5 + dz * dz;
                    if (d >= bestD) continue;
                    Long next = NEXT.get(v.id);
                    if (next != null && next > now) continue;
                    best = v;
                    bestD = d;
                }
            }
        }
        if (best == null) return;
        double dist = Math.sqrt(bestD);
        // crackle, with the roar of a really big fire now and then
        SoundEvent ev = FireSounds.FIRE_CRACKLE.get();
        float vol = 0.30f + 0.18f * Math.min(1.6f, best.intensity);
        float pitch = 0.88f + RANDOM.nextFloat() * 0.26f;
        if (best.intensity > 1.1f && RANDOM.nextInt(6) == 0) {
            ev = FireSounds.FIRE_ROAR.get();
            vol = 0.35f;
            pitch = 0.9f + RANDOM.nextFloat() * 0.15f;
        } else if (best.smoke >= 2 && RANDOM.nextInt(5) == 0) {
            ev = FireSounds.TIMBER_CREAK.get();
            vol = 0.35f;
        }
        level.playLocalSound(best.x, best.y + 1.0, best.z, ev, SoundSource.AMBIENT, Math.min(0.7f, vol * master), pitch, false);
        NEXT.put(best.id, now + 60 + RANDOM.nextInt(140));
        globalNext = now + 12;
        // a sudden collapse near a smoking ruin, rarely: sound, a burst of sparks and dust
        if (now >= nextCollapse && dist < 44 && best.smoke >= 2 && RANDOM.nextInt(4) == 0) {
            nextCollapse = now + 20 * (70 + RANDOM.nextInt(150));
            level.playLocalSound(best.x, best.y + 2.0, best.z, FireSounds.TIMBER_COLLAPSE.get(), SoundSource.AMBIENT,
                    Math.min(0.6f, 0.5f * master), 0.85f + RANDOM.nextFloat() * 0.2f, false);
            BlockPos p = BlockPos.containing(best.x, best.y + 2.0, best.z);
            for (int i = 0; i < 16; i++) {
                level.addParticle(ParticleTypes.LAVA, p.getX() + RANDOM.nextDouble() * 2 - 1, p.getY() + RANDOM.nextDouble() * 2,
                        p.getZ() + RANDOM.nextDouble() * 2 - 1, 0, 0.05, 0);
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.getX() + RANDOM.nextDouble() * 2 - 1, p.getY() + 0.5,
                        p.getZ() + RANDOM.nextDouble() * 2 - 1, 0, 0.04, 0);
            }
        }
    }
}
