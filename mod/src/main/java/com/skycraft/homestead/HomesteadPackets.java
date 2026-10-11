package com.skycraft.homestead;

import com.skycraft.core.Notifier;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Drafting table packets. Every client request is accepted only while the player has the table's menu open and in
 * reach, for the claim of that very table, and (except reading) only from its owner.
 */
public final class HomesteadPackets {
    private HomesteadPackets() {}

    public static void register() {
        SkyNetwork.register(UpdatePerms.class, NetworkDirection.PLAY_TO_SERVER, UpdatePerms::encode, UpdatePerms::decode, UpdatePerms::handle);
        SkyNetwork.register(CraftBlueprint.class, NetworkDirection.PLAY_TO_SERVER, CraftBlueprint::encode, CraftBlueprint::decode, CraftBlueprint::handle);
        SkyNetwork.register(Action.class, NetworkDirection.PLAY_TO_SERVER, Action::encode, Action::decode, Action::handle);
        SkyNetwork.register(State.class, NetworkDirection.PLAY_TO_CLIENT, State::encode, State::decode, State::handle);
    }

    /** The claim of the open drafting table if {@code player} owns it, else null. */
    @Nullable
    private static HomesteadClaim ownedClaim(ServerPlayer player) {
        if (player == null || !(player.containerMenu instanceof HomesteadMenu menu) || !menu.stillValid(player)) return null;
        HomesteadClaim claim = HomesteadData.get(player.server).getClaimAt(player.level(), menu.pos);
        if (claim == null || !claim.getCenter().equals(menu.pos)) return null;
        if (!claim.isOwner(player)) {
            Notifier.message(player, Component.translatable("message.skycraft.homestead.owned_by", claim.getOwnerName()));
            return null;
        }
        return claim;
    }

    public record UpdatePerms(BlockPos pos, int doorPerm, int containerPerm, int buildPerm) {
        static void encode(UpdatePerms m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.doorPerm);
            buf.writeVarInt(m.containerPerm);
            buf.writeVarInt(m.buildPerm);
        }

        static UpdatePerms decode(FriendlyByteBuf buf) {
            return new UpdatePerms(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(UpdatePerms m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            HomesteadClaim claim = ownedClaim(player);
            if (claim != null && claim.getCenter().equals(m.pos)) {
                claim.setDoorPerm(Math.floorMod(m.doorPerm, 3));
                claim.setContainerPerm(Math.floorMod(m.containerPerm, 2));
                claim.setBuildPerm(Math.floorMod(m.buildPerm, 2));
                HomesteadData.get(player.server).setDirty();
                Notifier.message(player, Component.translatable("message.skycraft.homestead.perms_updated"));
                Hearthfire.sync(player, claim);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S: make building material {@code index} of {@link Hearthfire#MATERIALS}, {@code times} times. */
    public record CraftBlueprint(int index, int times) {
        static void encode(CraftBlueprint m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.index);
            buf.writeVarInt(m.times);
        }

        static CraftBlueprint decode(FriendlyByteBuf buf) {
            return new CraftBlueprint(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(CraftBlueprint m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            HomesteadClaim claim = ownedClaim(player);
            if (claim != null) Hearthfire.craft(player, claim, m.index, m.times);
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * C2S: a drafting table action: {@code build} (arg: plan id), {@code buy_plot}, {@code hire_steward},
     * {@code steward_buy} (arg: offer index).
     */
    public record Action(String action, String arg) {
        static void encode(Action m, FriendlyByteBuf buf) {
            buf.writeUtf(m.action, 32);
            buf.writeUtf(m.arg, 64);
        }

        static Action decode(FriendlyByteBuf buf) {
            return new Action(buf.readUtf(32), buf.readUtf(64));
        }

        static void handle(Action m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            HomesteadClaim claim = ownedClaim(player);
            if (claim != null) {
                switch (m.action) {
                    case "build" -> Hearthfire.build(player, claim, m.arg);
                    case "buy_plot" -> Hearthfire.buyPlot(player, claim);
                    case "hire_steward" -> Hearthfire.hireSteward(player, claim);
                    case "steward_buy" -> {
                        try {
                            Hearthfire.stewardBuy(player, claim, Integer.parseInt(m.arg));
                        } catch (NumberFormatException ignored) {
                            // malformed request
                        }
                    }
                    default -> { }
                }
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: the open drafting table's state changed. */
    public record State(CompoundTag state) {
        static void encode(State m, FriendlyByteBuf buf) {
            buf.writeNbt(m.state);
        }

        static State decode(FriendlyByteBuf buf) {
            CompoundTag t = buf.readNbt();
            return new State(t == null ? new CompoundTag() : t);
        }

        static void handle(State m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.homestead.client.HomesteadScreen.receive(m.state));
            ctx.get().setPacketHandled(true);
        }
    }
}
