package com.nirncraft.arsenal;

import com.nirncraft.arsenal.client.KillCamClient;
import com.nirncraft.magic.MagicFx;
import com.nirncraft.magic.spell.Element;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/**
 * Arsenal packets: kill cams (S2C), Bloodskal swings (C2S). Registered in a fixed order. Staff visuals go through the
 * magic module's effect packet ({@link Fx}).
 */
public final class ArsenalPackets {
    private ArsenalPackets() {}

    public static void register() {
        SkyNetwork.register(KillCam.class, PacketFlow.CLIENTBOUND, KillCam::encode, KillCam::decode, KillCam::handle);
        SkyNetwork.register(BladeSwing.class, PacketFlow.SERVERBOUND, BladeSwing::encode, BladeSwing::decode, BladeSwing::handle);
        SkyNetwork.register(CollectArrow.class, PacketFlow.SERVERBOUND, CollectArrow::encode, CollectArrow::decode, CollectArrow::handle);
    }

    // ------------------------------------------------------------------ S2C

    /**
     * Plays a kill cam on the killer's client, sent on the killing blow. {@code kind} is {@link #MELEE} or
     * {@link #ARROW}; for arrows {@code arrowId} is the arrow entity (may already be gone) and {@code dir} its flight
     * direction. {@code (vx, vy, vz)} is the middle of the victim's body, so the cam works even when the victim is
     * already gone on the client (its body replaced by a corpse).
     */
    public record KillCam(int victimId, byte kind, int arrowId, float dx, float dy, float dz, float vx, float vy, float vz) {
        public static final byte MELEE = 0;
        public static final byte ARROW = 1;

        static void encode(KillCam m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.victimId);
            buf.writeByte(m.kind);
            buf.writeVarInt(m.arrowId);
            buf.writeFloat(m.dx);
            buf.writeFloat(m.dy);
            buf.writeFloat(m.dz);
            buf.writeFloat(m.vx);
            buf.writeFloat(m.vy);
            buf.writeFloat(m.vz);
        }

        static KillCam decode(RegistryFriendlyByteBuf buf) {
            return new KillCam(buf.readVarInt(), buf.readByte(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        static void handle(KillCam m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> KillCamClient.start(m.victimId, m.kind, m.arrowId,
                    new Vec3(m.dx, m.dy, m.dz), new Vec3(m.vx, m.vy, m.vz)));
        }
    }

    /**
     * Staff/artifact visuals between two points (streams, beams, lightning arcs, bursts, wards). Sent as the magic
     * module's {@link com.nirncraft.magic.MagicPackets.Fx} ({@link MagicFx#STAFF}, the visual kind in {@code extra}).
     */
    public static final class Fx {
        public static final byte FIRE_STREAM = 0;
        public static final byte FROST_STREAM = 1;
        public static final byte SHOCK_STREAM = 2;
        public static final byte HEAL_BEAM = 3;
        public static final byte MAGNUS_BEAM = 4;
        public static final byte LIGHTNING_ARC = 5;
        public static final byte FIRE_BURST = 6;
        public static final byte SUN_BURST = 7;
        public static final byte FROST_BURST = 8;
        public static final byte WARD = 9;
        public static final byte SOUL_BURST = 10;

        private Fx() {}

        /** Sends the effect to every player within 64 blocks of {@code a}. */
        public static void send(ServerLevel level, byte kind, Vec3 a, Vec3 b) {
            MagicFx.sendNear(level, a, 64, MagicFx.STAFF, Element.NONE, a, b, -1, kind);
        }
    }

    // ------------------------------------------------------------------ C2S

    /** The client swung Bloodskal at full strength without hitting a creature (hits are handled server-side). */
    public record BladeSwing() {
        static void encode(BladeSwing m, RegistryFriendlyByteBuf buf) {
        }

        static BladeSwing decode(RegistryFriendlyByteBuf buf) {
            return new BladeSwing();
        }

        static void handle(BladeSwing m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) ArtifactEvents.bloodskalSwing(player);
        }
    }

    /** Collect a stuck or floating arrow (or pull an arrow from a creature's body) via [F] or right-click. */
    public record CollectArrow(int entityId) {
        static void encode(CollectArrow m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
        }

        static CollectArrow decode(RegistryFriendlyByteBuf buf) {
            return new CollectArrow(buf.readVarInt());
        }

        static void handle(CollectArrow m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                net.minecraft.world.entity.Entity target = player.level().getEntity(m.entityId);
                if (target instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
                    ArsenalEvents.tryPickupArrow(player, arrow);
                } else if (target instanceof net.minecraft.world.entity.LivingEntity living) {
                    ArsenalEvents.tryPullArrowFromBody(player, living);
                }
            }
        }
    }
}
