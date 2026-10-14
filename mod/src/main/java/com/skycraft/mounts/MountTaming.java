package com.skycraft.mounts;

import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.entity.GuarEntity;
import com.skycraft.fauna.FaunaEntities;
import com.skycraft.fauna.HorseBreed;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import org.jetbrains.annotations.Nullable;

/**
 * Which wild creatures can be broken in as mounts, and what a tamed one becomes. Horses, camels and guar keep their
 * entity; the bestiary's senche-tigers, indriks and realm grazers and the fauna's mammoths are replaced by their
 * {@link MountBeast} (same spot, same rider) once the bucking minigame ({@code fauna.WildHorseTaming}) is won.
 */
public final class MountTaming {
    private MountTaming() {}

    /** The mount kind a wild creature can be tamed into, or null. */
    @Nullable
    public static MountKind wildKind(Mob mob) {
        if (Mounts.ownerOf(mob) != null) return null;
        if (mob instanceof Horse h) {
            if (h.isTamed()) return null;
            if (h.getPersistentData().contains("skycraft_breed")) {
                MountKind k = MountKind.forBreed(HorseBreed.byId(h.getPersistentData().getString("skycraft_breed")));
                if (k != null && k.tameDifficulty > 0) return k;
            }
            return regionalHorse(mob);
        }
        if (mob instanceof Camel c) return c.isTamed() ? null : MountKind.CAMEL;
        if (mob instanceof AbstractHorse) return null;
        if (mob instanceof GuarEntity) return MountKind.GUAR;
        if (mob instanceof MountBeast) return null;
        var t = mob.getType();
        if (t == Bestiary.SENCHE_TIGER.get()) return MountKind.SENCHE;
        if (t == Bestiary.INDRIK.get()) return MountKind.INDRIK;
        if (t == Bestiary.REALM_GRAZER.get()) return MountKind.REALM_GRAZER;
        if (t == FaunaEntities.MAMMOTH.get()) return MountKind.MAMMOTH;
        return null;
    }

    /** A wild horse of the region's breed (Imperial bays in Cyrodiil, Alik'r steeds in Hammerfell...). */
    static MountKind regionalHorse(Mob mob) {
        if (mob.level() instanceof ServerLevel sl) {
            Region r = Regions.at(sl, mob.blockPosition());
            for (MountKind k : MountKind.forSale(r.id(), r.province())) {
                if (k.backing == MountKind.Backing.HORSE && k.tameDifficulty > 0) return k;
            }
        }
        return MountKind.WHITERUN_BAY;
    }

    /**
     * The minigame was won: the creature becomes the player's mount (a wild beast is swapped for its mount entity,
     * still carrying the rider). Returns the mount.
     */
    @Nullable
    public static Mob complete(ServerPlayer player, Mob wild, MountKind kind, SaddleType saddle) {
        Mob mount = wild;
        if (wild instanceof AbstractHorse h) {
            h.tameWithName(player);
            if (kind.breed != null && wild instanceof Horse horse && !horse.getPersistentData().contains("skycraft_breed")) {
                kind.breed.applyStats(horse);
            }
        } else if (wild instanceof GuarEntity g) {
            g.setVariant(1);
        } else if (kind.backing == MountKind.Backing.BEAST && wild.level() instanceof ServerLevel level) {
            MountStats born = MountStats.of(wild, kind); // the animal the rider has been breaking in, not a new roll
            Mob beast = Mounts.create(level, kind, wild.getX(), wild.getY(), wild.getZ(), wild.getYRot(), player);
            if (beast == null) return null;
            MountStats.store(beast, born);
            MountGear.refresh(beast);
            float health = wild.getHealth() / Math.max(1f, wild.getMaxHealth());
            player.stopRiding();
            wild.discard();
            beast.setHealth(Math.max(1f, beast.getMaxHealth() * health));
            level.addFreshEntity(beast);
            player.startRiding(beast, true);
            mount = beast;
        }
        // it wears the saddle it was broken in with (adopt records it as this kind's saddle)
        MountGear.setSaddle(mount, saddle);
        Mounts.adopt(player, mount, kind);
        if (player.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.HEART, mount.getX(), mount.getY() + 1.0, mount.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
            sl.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
        }
        return mount;
    }
}
