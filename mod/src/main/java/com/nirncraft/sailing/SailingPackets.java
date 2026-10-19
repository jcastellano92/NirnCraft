package com.nirncraft.sailing;

import com.nirncraft.network.SkyNetwork;
import com.nirncraft.sailing.client.SailingClientEvents;
import com.nirncraft.sailing.client.VoyageOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Sailing packets: helm input, sail trim, crash reports, the shipwright's bench and the voyage screen. */
public final class SailingPackets {
    private SailingPackets() {}

    static void register() {
        SkyNetwork.register(ShipControl.class, PacketFlow.SERVERBOUND, ShipControl::encode, ShipControl::decode, ShipControl::handle);
        SkyNetwork.register(SailTrim.class, PacketFlow.SERVERBOUND, SailTrim::encode, SailTrim::decode, SailTrim::handle);
        SkyNetwork.register(ShipCrash.class, PacketFlow.SERVERBOUND, ShipCrash::encode, ShipCrash::decode, ShipCrash::handle);
        SkyNetwork.register(OpenShipwright.class, PacketFlow.CLIENTBOUND, OpenShipwright::encode, OpenShipwright::decode, OpenShipwright::handle);
        SkyNetwork.register(BuildShip.class, PacketFlow.SERVERBOUND, BuildShip::encode, BuildShip::decode, BuildShip::handle);
        SkyNetwork.register(VoyageStart.class, PacketFlow.CLIENTBOUND, VoyageStart::encode, VoyageStart::decode, VoyageStart::handle);
        SkyNetwork.register(DeckReport.class, PacketFlow.SERVERBOUND, DeckReport::encode, DeckReport::decode, DeckReport::handle);
        SkyNetwork.register(DeckSync.class, PacketFlow.CLIENTBOUND, DeckSync::encode, DeckSync::decode, DeckSync::handle);
    }

    /** The ship the sender is aboard, if it is {@code id}. */
    private static ShipEntity shipOf(ServerPlayer player, int id) {
        Entity e = player.level().getEntity(id);
        if (e instanceof ShipEntity ship && ship.hasPassenger(player)) return ship;
        return null;
    }

    /** Helm input bits (oars and rudder) from the helmsman, so everyone sees the oars and rudder move. */
    public record ShipControl(int entity, int bits) {
        static void encode(ShipControl m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeByte(m.bits);
        }

        static ShipControl decode(RegistryFriendlyByteBuf buf) {
            return new ShipControl(buf.readVarInt(), buf.readByte());
        }

        static void handle(ShipControl m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                ShipEntity ship = shipOf(player, m.entity);
                if (ship != null && ship.getControllingPassenger() == player) ship.setSyncedInput(m.bits);
            }
        }
    }

    /** Any crew member raises (+1) or lowers (-1) the sail. */
    public record SailTrim(int entity, int delta) {
        static void encode(SailTrim m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeByte(m.delta);
        }

        static SailTrim decode(RegistryFriendlyByteBuf buf) {
            return new SailTrim(buf.readVarInt(), buf.readByte());
        }

        static void handle(SailTrim m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                ShipEntity ship = shipOf(player, m.entity);
                if (ship != null && ship.shipType.hasSail() && ship.getSinking() == 0) {
                    int before = ship.getSail();
                    ship.setSail(before + Integer.signum(m.delta));
                    if (ship.getSail() != before) {
                        player.level().playSound(null, ship.getX(), ship.getY() + 4, ship.getZ(), SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 1f, 0.7f);
                        player.level().playSound(null, ship.getX(), ship.getY() + 2, ship.getZ(), SoundEvents.LADDER_STEP, SoundSource.NEUTRAL, 0.8f, 0.6f);
                        com.nirncraft.core.Notifier.message(player, Component.translatable("sailing.nirncraft.sail." + ship.getSail()));
                    }
                }
            }
        }
    }

    /** The helmsman's client ran the ship into something hard; the server applies the (bounded) damage. */
    public record ShipCrash(int entity, float speed) {
        static void encode(ShipCrash m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeFloat(m.speed);
        }

        static ShipCrash decode(RegistryFriendlyByteBuf buf) {
            return new ShipCrash(buf.readVarInt(), buf.readFloat());
        }

        static void handle(ShipCrash m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null && Float.isFinite(m.speed)) {
                ShipEntity ship = shipOf(player, m.entity);
                if (ship != null) ship.reportedCrash(player, Math.max(0f, m.speed));
            }
        }
    }

    /** Opens the shipwright's bench screen. */
    public record OpenShipwright(BlockPos pos) {
        static void encode(OpenShipwright m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
        }

        static OpenShipwright decode(RegistryFriendlyByteBuf buf) {
            return new OpenShipwright(buf.readBlockPos());
        }

        static void handle(OpenShipwright m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> SailingClientEvents.openShipwright(m.pos));
        }
    }

    /** Build a ship at the bench. */
    public record BuildShip(int type, BlockPos bench) {
        static void encode(BuildShip m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.type);
            buf.writeBlockPos(m.bench);
        }

        static BuildShip decode(RegistryFriendlyByteBuf buf) {
            return new BuildShip(buf.readVarInt(), buf.readBlockPos());
        }

        static void handle(BuildShip m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null && m.type >= 0 && m.type < ShipType.VALUES.length) {
                Shipwright.build(player, ShipType.VALUES[m.type], m.bench);
            }
        }
    }

    /** Start the voyage screen: sailing from one port to another, taking {@code hours}. */
    public record VoyageStart(Component from, Component to, Component ship, int hours, boolean expedition) {
        static void encode(VoyageStart m, RegistryFriendlyByteBuf buf) {
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.from);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.to);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.ship);
            buf.writeVarInt(m.hours);
            buf.writeBoolean(m.expedition);
        }

        static VoyageStart decode(RegistryFriendlyByteBuf buf) {
            return new VoyageStart(net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(VoyageStart m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> VoyageOverlay.start(m.from, m.to, m.ship, m.hours, m.expedition));
        }
    }

    /** The sender's own position on a ship's deck (ship-local), or {@code ship = -1} when it left the deck. */
    public record DeckReport(int ship, float lx, float ly, float lz, float yaw, float headYaw) {
        static void encode(DeckReport m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.ship + 1);
            if (m.ship < 0) return;
            buf.writeFloat(m.lx);
            buf.writeFloat(m.ly);
            buf.writeFloat(m.lz);
            buf.writeFloat(m.yaw);
            buf.writeFloat(m.headYaw);
        }

        static DeckReport decode(RegistryFriendlyByteBuf buf) {
            int ship = buf.readVarInt() - 1;
            if (ship < 0) return new DeckReport(-1, 0, 0, 0, 0, 0);
            return new DeckReport(ship, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        static void handle(DeckReport m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null && Float.isFinite(m.yaw) && Float.isFinite(m.headYaw)) {
                DeckPhysics.report(player, m.ship, m.lx, m.ly, m.lz, m.yaw, m.headYaw);
            }
        }
    }

    /**
     * Where everyone aboard a ship stands, in ship-local coordinates (yaws relative to the ship's heading): clients
     * place them against their own view of the ship so nobody drifts off the deck it walks on.
     */
    public record DeckSync(int ship, java.util.List<Entry> entries) {
        public record Entry(int entity, float lx, float ly, float lz, float yaw, float headYaw) {}

        static void encode(DeckSync m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.ship);
            buf.writeVarInt(m.entries.size());
            for (Entry e : m.entries) {
                buf.writeVarInt(e.entity);
                buf.writeFloat(e.lx);
                buf.writeFloat(e.ly);
                buf.writeFloat(e.lz);
                buf.writeFloat(e.yaw);
                buf.writeFloat(e.headYaw);
            }
        }

        static DeckSync decode(RegistryFriendlyByteBuf buf) {
            int ship = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), 256);
            java.util.List<Entry> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new Entry(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
            }
            return new DeckSync(ship, list);
        }

        static void handle(DeckSync m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.sailing.client.DeckClient.onSync(m));
        }
    }
}
