package com.nirncraft.homestead;

import com.nirncraft.core.Notifier;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;


/**
 * Drafting table packets. Every client request is accepted only while the player has the table's menu open and in
 * reach, for the claim of that very table, and (except reading) only from its owner.
 */
public final class HomesteadPackets {
    private HomesteadPackets() {}

    public static void register() {
        SkyNetwork.register(UpdatePerms.class, PacketFlow.SERVERBOUND, UpdatePerms::encode, UpdatePerms::decode, UpdatePerms::handle);
        SkyNetwork.register(CraftBlueprint.class, PacketFlow.SERVERBOUND, CraftBlueprint::encode, CraftBlueprint::decode, CraftBlueprint::handle);
        SkyNetwork.register(Action.class, PacketFlow.SERVERBOUND, Action::encode, Action::decode, Action::handle);
        SkyNetwork.register(State.class, PacketFlow.CLIENTBOUND, State::encode, State::decode, State::handle);
        HomesteadDialogue.register(); // the builder's conversation options
    }

    /** The claim of the open drafting table if {@code player} owns it, else null. */
    @Nullable
    private static HomesteadClaim ownedClaim(ServerPlayer player) {
        if (player == null || !(player.containerMenu instanceof HomesteadMenu menu) || !menu.stillValid(player)) return null;
        HomesteadClaim claim = HomesteadData.get(player.level().getServer()).getClaimAt(player.level(), menu.pos);
        if (claim == null || !claim.getCenter().equals(menu.pos)) return null;
        if (!claim.isOwner(player)) {
            Notifier.message(player, Component.translatable("message.nirncraft.homestead.owned_by", claim.getOwnerName()));
            return null;
        }
        return claim;
    }

    /** C2S: the claim-wide levels: 0 only me, 1 my party, 2 everyone (building: 0 or 1). */
    public record UpdatePerms(BlockPos pos, int doorPerm, int containerPerm, int stationPerm, int bedPerm, int buildPerm) {
        static void encode(UpdatePerms m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeVarInt(m.doorPerm);
            buf.writeVarInt(m.containerPerm);
            buf.writeVarInt(m.stationPerm);
            buf.writeVarInt(m.bedPerm);
            buf.writeVarInt(m.buildPerm);
        }

        static UpdatePerms decode(RegistryFriendlyByteBuf buf) {
            return new UpdatePerms(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt());
        }

        static void handle(UpdatePerms m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            HomesteadClaim claim = ownedClaim(player);
            if (claim != null && claim.getCenter().equals(m.pos)) {
                claim.setDoorPerm(Math.floorMod(m.doorPerm, 3));
                claim.setContainerPerm(Math.floorMod(m.containerPerm, 3));
                claim.setStationPerm(m.stationPerm);
                claim.setBedPerm(m.bedPerm);
                claim.setBuildPerm(Math.floorMod(m.buildPerm, 2));
                HomesteadData.get(player.level().getServer()).setDirty();
                Notifier.message(player, Component.translatable("message.nirncraft.homestead.perms_updated"));
                Hearthfire.sync(player, claim);
            }
        }
    }

    /** C2S: make building material {@code index} of {@link Hearthfire#MATERIALS}, {@code times} times. */
    public record CraftBlueprint(int index, int times) {
        static void encode(CraftBlueprint m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.index);
            buf.writeVarInt(m.times);
        }

        static CraftBlueprint decode(RegistryFriendlyByteBuf buf) {
            return new CraftBlueprint(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(CraftBlueprint m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            HomesteadClaim claim = ownedClaim(player);
            if (claim != null) Hearthfire.craft(player, claim, m.index, m.times);
        }
    }

    /**
     * C2S: a drafting table action: {@code build} (arg: plan id), {@code buy_plot}, {@code hire_steward},
     * {@code steward_buy} (arg: offer index), {@code rename} (arg: the new name), {@code build_mode}, {@code expand}.
     */
    public record Action(String action, String arg) {
        static void encode(Action m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.action, 32);
            buf.writeUtf(m.arg, 64);
        }

        static Action decode(RegistryFriendlyByteBuf buf) {
            return new Action(buf.readUtf(32), buf.readUtf(64));
        }

        static void handle(Action m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            HomesteadClaim claim = ownedClaim(player);
            if (claim != null) {
                switch (m.action) {
                    case "build" -> Hearthfire.build(player, claim, m.arg);
                    case "buy_plot" -> Hearthfire.buyPlot(player, claim);
                    case "hire_steward" -> Hearthfire.hireSteward(player, claim);
                    case "rename" -> Hearthfire.rename(player, claim, m.arg);
                    case "build_mode" -> Hearthfire.toggleBuildMode(player, claim);
                    case "expand" -> Hearthfire.expand(player, claim);
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
        }
    }

    /** S2C: the open drafting table's state changed. */
    public record State(CompoundTag state) {
        static void encode(State m, RegistryFriendlyByteBuf buf) {
            buf.writeNbt(m.state);
        }

        static State decode(RegistryFriendlyByteBuf buf) {
            CompoundTag t = buf.readNbt();
            return new State(t == null ? new CompoundTag() : t);
        }

        static void handle(State m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.homestead.client.HomesteadScreen.receive(m.state));
        }
    }
}
