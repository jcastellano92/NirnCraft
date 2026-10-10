package com.skycraft.magic;

import com.skycraft.magic.spell.Element;
import com.skycraft.network.SkyNetwork;
import com.skycraft.perk.Perks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * Server-side helpers that ask nearby clients to draw spell visuals ({@link MagicPackets.Fx}) and play sounds.
 * Visuals are drawn client-side so a beam costs one small packet per tick instead of dozens of particle packets.
 */
public final class MagicFx {
    public static final int BEAM = 0;
    public static final int BURST = 1;
    public static final int ARC = 2;
    public static final int STREAM = 3;
    public static final int RING = 4;
    public static final int CHARGE = 5;
    public static final int HEAL = 6;
    public static final int SUMMON = 7;
    public static final int WARD = 8;
    public static final int LIGHTNING = 9;
    public static final int TOTEM = 10;
    public static final int AURA = 11;
    /** Staff and artifact visuals (arsenal): {@code extra} is one of {@code ArsenalPackets.Fx}'s kinds. */
    public static final int STAFF = 12;
    /** A glowing glyph on the ground (runes, Mark): {@code a} = center, {@code extra} = radius * 10. */
    public static final int RUNE = 13;
    /** A column of elemental energy (walls): {@code extra} = height * 10. */
    public static final int PILLAR = 14;
    /** A dotted trail of light from {@code a} to {@code b} (Clairvoyance). */
    public static final int PATH = 15;
    /** Slow Time: a shimmering field around {@code a}, {@code extra} = radius. */
    public static final int TIME_FIELD = 16;

    private MagicFx() {}

    /** Sends an effect to everyone tracking {@code around} (and to it, if it is a player). */
    public static void send(Entity around, int kind, Element element, Vec3 a, Vec3 b, int entityId, int extra) {
        SkyNetwork.sendToTracking(around, new MagicPackets.Fx(kind, element.ordinal(), a, b, entityId, extra));
    }

    /** Sends an effect to every player within {@code radius} blocks of {@code pos}. */
    public static void sendNear(ServerLevel level, Vec3 pos, double radius, int kind, Element element, Vec3 a, Vec3 b, int entityId, int extra) {
        SkyNetwork.CHANNEL.send(PacketDistributor.NEAR.with(PacketDistributor.TargetPoint.p(pos.x, pos.y, pos.z, radius, level.dimension())),
                new MagicPackets.Fx(kind, element.ordinal(), a, b, entityId, extra));
    }

    public static void burst(ServerLevel level, Vec3 pos, Element element, float radius) {
        sendNear(level, pos, 64, BURST, element, pos, pos, -1, Math.round(radius * 10));
    }

    /** Plays a spell sound. With the Quiet Casting perk only the caster hears it. */
    public static void sound(ServerPlayer caster, Vec3 pos, SoundEvent sound, float volume, float pitch) {
        if (Perks.has(caster, "illusion.quiet_casting")) {
            caster.playNotifySound(sound, SoundSource.PLAYERS, volume * 0.6f, pitch);
        } else {
            caster.level().playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
        }
    }

    public static void sound(ServerPlayer caster, SoundEvent sound, float volume, float pitch) {
        sound(caster, caster.position(), sound, volume, pitch);
    }

    /** A world sound everybody hears (impacts, explosions, shouts). */
    public static void worldSound(ServerLevel level, Vec3 pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        level.playSound(null, pos.x, pos.y, pos.z, sound, source, volume, pitch);
    }
}
