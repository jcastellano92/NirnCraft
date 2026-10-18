package com.skycraft.magic.shout;

import com.skycraft.core.Notifier;
import com.skycraft.core.SkyData;
import com.skycraft.magic.MagicData;
import com.skycraft.magic.MagicFx;
import com.skycraft.magic.MagicScheduler;
import com.skycraft.magic.spell.Element;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dragon soul absorption: when a creature tagged {@code #skycraft:dragons} dies, every player within 64 blocks
 * absorbs its soul. The corpse erupts in flames and streams of light flow into each player for three seconds.
 */
public final class DragonSouls {
    private static final int ABSORB_TICKS = 60;

    private DragonSouls() {}

    public static void onDragonDeath(LivingEntity dragon) {
        if (!(dragon.level() instanceof ServerLevel level)) return;
        Vec3 corpse = dragon.position().add(0, dragon.getBbHeight() * 0.5, 0);
        List<UUID> absorbers = new ArrayList<>();
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, dragon.getBoundingBox().inflate(64),
                pl -> pl.isAlive() && !pl.isSpectator() && pl.distanceToSqr(dragon) <= 64 * 64)) {
            absorbers.add(p.getUUID());
            MagicFx.sendNear(level, corpse, 128, MagicFx.STREAM, Element.SOUL, corpse, corpse, p.getId(), ABSORB_TICKS);
        }
        if (absorbers.isEmpty()) return;

        level.playSound(null, corpse.x, corpse.y, corpse.z, com.skycraft.world.WorldSounds.MAGIC_DRAGON_SOUL.get(), SoundSource.HOSTILE, 2.5f, 1.0f);
        level.playSound(null, corpse.x, corpse.y, corpse.z, SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.4f, 1.6f);

        // The corpse burns while the soul streams out.
        for (int i = 0; i < ABSORB_TICKS; i += 5) {
            final int step = i;
            MagicScheduler.schedule(level.getServer(), i + 1, server -> {
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, corpse.x, corpse.y, corpse.z, 18,
                        dragon.getBbWidth() * 0.4, dragon.getBbHeight() * 0.3, dragon.getBbWidth() * 0.4, 0.04);
                level.sendParticles(ParticleTypes.FLAME, corpse.x, corpse.y - 0.5, corpse.z, 10,
                        dragon.getBbWidth() * 0.5, 0.3, dragon.getBbWidth() * 0.5, 0.02);
                if (step % 20 == 0) level.playSound(null, corpse.x, corpse.y, corpse.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 2f, 0.5f);
            });
        }

        MagicScheduler.schedule(level.getServer(), ABSORB_TICKS, server -> {
            for (UUID id : absorbers) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) absorb(p);
            }
        });
    }

    private static void absorb(ServerPlayer p) {
        int souls = MagicData.dragonSouls(p);
        boolean first = !MagicData.tag(p).getBooleanOr("dragonborn", false);
        MagicData.setDragonSouls(p, souls + 1);
        SkyData.get(p).addStat("dragon_souls", 1);
        if (first) {
            MagicData.tag(p).putBoolean("dragonborn", true);
            // The Greybeards' gift: the first word of Unrelenting Force.
            if (MagicData.wordsLearned(p, Shout.UNRELENTING_FORCE) == 0) {
                MagicData.setWordsLearned(p, Shout.UNRELENTING_FORCE, 1);
                MagicData.setWordsUnlocked(p, Shout.UNRELENTING_FORCE, 1);
                MagicData.setSelectedShout(p, Shout.UNRELENTING_FORCE.id());
                Notifier.message(p, Component.translatable("message.skycraft.first_word", Shout.UNRELENTING_FORCE.wordWithTranslation(0)));
            }
        }
        Notifier.title(p, Component.translatable("notify.skycraft.dragon_soul"),
                Component.translatable(first ? "notify.skycraft.dragonborn" : "notify.skycraft.dragon_souls_count", souls + 1));
        MagicFx.send(p, MagicFx.AURA, Element.SOUL, p.position(), p.position(), p.getId(), 4);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), com.skycraft.world.WorldSounds.MAGIC_DRAGON_SOUL.get(), SoundSource.PLAYERS, 1.4f, 1.0f);
        SkyData.get(p).markDirty();
    }
}
