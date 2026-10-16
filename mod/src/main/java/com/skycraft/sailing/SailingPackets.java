package com.skycraft.sailing;

import com.skycraft.network.SkyNetwork;
import com.skycraft.sailing.client.SailingClientEvents;
import com.skycraft.sailing.client.VoyageOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Sailing packets: helm input, sail trim, crash reports, the shipwright's bench and the voyage screen. */
public final class SailingPackets {
    private SailingPackets() {}

    static void register() {
        SkyNetwork.register(ShipControl.class, NetworkDirection.PLAY_TO_SERVER, ShipControl::encode, ShipControl::decode, ShipControl::handle);
        SkyNetwork.register(SailTrim.class, NetworkDirection.PLAY_TO_SERVER, SailTrim::encode, SailTrim::decode, SailTrim::handle);
        SkyNetwork.register(ShipCrash.class, NetworkDirection.PLAY_TO_SERVER, ShipCrash::encode, ShipCrash::decode, ShipCrash::handle);
        SkyNetwork.register(OpenShipwright.class, NetworkDirection.PLAY_TO_CLIENT, OpenShipwright::encode, OpenShipwright::decode, OpenShipwright::handle);
        SkyNetwork.register(BuildShip.class, NetworkDirection.PLAY_TO_SERVER, BuildShip::encode, BuildShip::decode, BuildShip::handle);
        SkyNetwork.register(VoyageStart.class, NetworkDirection.PLAY_TO_CLIENT, VoyageStart::encode, VoyageStart::decode, VoyageStart::handle);
        SkyNetwork.register(DeckReport.class, NetworkDirection.PLAY_TO_SERVER, DeckReport::encode, DeckReport::decode, DeckReport::handle);
        SkyNetwork.register(DeckSync.class, NetworkDirection.PLAY_TO_CLIENT, DeckSync::encode, DeckSync::decode, DeckSync::handle);
    }

    /** The ship the sender is aboard, if it is {@code id}. */
    private static ShipEntity shipOf(ServerPlayer player, int id) {
        Entity e = player.level().getEntity(id);
        if (e instanceof ShipEntity ship && ship.hasPassenger(player)) return ship;
        return null;
    }

    /** Helm input bits (oars and rudder) from the helmsman, so everyone sees the oars and rudder move. */
    public record ShipControl(int entity, int bits) {
        static void encode(ShipControl m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeByte(m.bits);
        }

        static ShipControl decode(FriendlyByteBuf buf) {
            return new ShipControl(buf.readVarInt(), buf.readByte());
        }

        static void handle(ShipControl m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ShipEntity ship = shipOf(player, m.entity);
                if (ship != null && ship.getControllingPassenger() == player) ship.setSyncedInput(m.bits);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** Any crew member raises (+1) or lowers (-1) the sail. */
    public record SailTrim(int entity, int delta) {
        static void encode(SailTrim m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeByte(m.delta);
        }

        static SailTrim decode(FriendlyByteBuf buf) {
            return new SailTrim(buf.readVarInt(), buf.readByte());
        }

        static void handle(SailTrim m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                ShipEntity ship = shipOf(player, m.entity);
                if (ship != null && ship.shipType.hasSail() && ship.getSinking() == 0) {
                    int before = ship.getSail();
                    ship.setSail(before + Integer.signum(m.delta));
                    if (ship.getSail() != before) {
                        player.level().playSound(null, ship.getX(), ship.getY() + 4, ship.getZ(), SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL, 1f, 0.7f);
                        player.level().playSound(null, ship.getX(), ship.getY() + 2, ship.getZ(), SoundEvents.LADDER_STEP, SoundSource.NEUTRAL, 0.8f, 0.6f);
                        com.skycraft.core.Notifier.message(player, Component.translatable("sailing.skycraft.sail." + ship.getSail()));
                    }
                }
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** The helmsman's client ran the ship into something hard; the server applies the (bounded) damage. */
    public record ShipCrash(int entity, float speed) {
        static void encode(ShipCrash m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeFloat(m.speed);
        }

        static ShipCrash decode(FriendlyByteBuf buf) {
            return new ShipCrash(buf.readVarInt(), buf.readFloat());
        }

        static void handle(ShipCrash m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null && Float.isFinite(m.speed)) {
                ShipEntity ship = shipOf(player, m.entity);
                if (ship != null) ship.reportedCrash(player, Math.max(0f, m.speed));
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** Opens the shipwright's bench screen. */
    public record OpenShipwright(BlockPos pos) {
        static void encode(OpenShipwright m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
        }

        static OpenShipwright decode(FriendlyByteBuf buf) {
            return new OpenShipwright(buf.readBlockPos());
        }

        static void handle(OpenShipwright m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SailingClientEvents.openShipwright(m.pos));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Build a ship at the bench. */
    public record BuildShip(int type, BlockPos bench) {
        static void encode(BuildShip m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.type);
            buf.writeBlockPos(m.bench);
        }

        static BuildShip decode(FriendlyByteBuf buf) {
            return new BuildShip(buf.readVarInt(), buf.readBlockPos());
        }

        static void handle(BuildShip m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null && m.type >= 0 && m.type < ShipType.VALUES.length) {
                Shipwright.build(player, ShipType.VALUES[m.type], m.bench);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** Start the voyage screen: sailing from one port to another, taking {@code hours}. */
    public record VoyageStart(Component from, Component to, Component ship, int hours, boolean expedition) {
        static void encode(VoyageStart m, FriendlyByteBuf buf) {
            buf.writeComponent(m.from);
            buf.writeComponent(m.to);
            buf.writeComponent(m.ship);
            buf.writeVarInt(m.hours);
            buf.writeBoolean(m.expedition);
        }

        static VoyageStart decode(FriendlyByteBuf buf) {
            return new VoyageStart(buf.readComponent(), buf.readComponent(), buf.readComponent(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(VoyageStart m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> VoyageOverlay.start(m.from, m.to, m.ship, m.hours, m.expedition));
            ctx.get().setPacketHandled(true);
        }
    }

    /** The sender's own position on a ship's deck (ship-local), or {@code ship = -1} when it left the deck. */
    public record DeckReport(int ship, float lx, float ly, float lz, float yaw, float headYaw) {
        static void encode(DeckReport m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.ship + 1);
            if (m.ship < 0) return;
            buf.writeFloat(m.lx);
            buf.writeFloat(m.ly);
            buf.writeFloat(m.lz);
            buf.writeFloat(m.yaw);
            buf.writeFloat(m.headYaw);
        }

        static DeckReport decode(FriendlyByteBuf buf) {
            int ship = buf.readVarInt() - 1;
            if (ship < 0) return new DeckReport(-1, 0, 0, 0, 0, 0);
            return new DeckReport(ship, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }

        static void handle(DeckReport m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null && Float.isFinite(m.yaw) && Float.isFinite(m.headYaw)) {
                DeckPhysics.report(player, m.ship, m.lx, m.ly, m.lz, m.yaw, m.headYaw);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Where everyone aboard a ship stands, in ship-local coordinates (yaws relative to the ship's heading): clients
     * place them against their own view of the ship so nobody drifts off the deck it walks on.
     */
    public record DeckSync(int ship, java.util.List<Entry> entries) {
        public record Entry(int entity, float lx, float ly, float lz, float yaw, float headYaw) {}

        static void encode(DeckSync m, FriendlyByteBuf buf) {
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

        static DeckSync decode(FriendlyByteBuf buf) {
            int ship = buf.readVarInt();
            int n = Math.min(buf.readVarInt(), 256);
            java.util.List<Entry> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(new Entry(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
            }
            return new DeckSync(ship, list);
        }

        static void handle(DeckSync m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.sailing.client.DeckClient.onSync(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
