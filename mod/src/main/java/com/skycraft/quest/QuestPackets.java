package com.skycraft.quest;

import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.client.ClientQuestData;
import com.skycraft.quest.party.Parties;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Quest & party packets: journal sync, party state (HUD), party actions and quest actions (track, abandon). */
public final class QuestPackets {
    /** "No target" UUID for party actions. */
    public static final UUID NONE = new UUID(0L, 0L);

    private QuestPackets() {}

    public static void register() {
        SkyNetwork.register(SyncQuests.class, NetworkDirection.PLAY_TO_CLIENT, SyncQuests::encode, SyncQuests::decode, SyncQuests::handle);
        SkyNetwork.register(SyncParty.class, NetworkDirection.PLAY_TO_CLIENT, SyncParty::encode, SyncParty::decode, SyncParty::handle);
        SkyNetwork.register(PartyAction.class, NetworkDirection.PLAY_TO_SERVER, PartyAction::encode, PartyAction::decode, PartyAction::handle);
        SkyNetwork.register(QuestAction.class, NetworkDirection.PLAY_TO_SERVER, QuestAction::encode, QuestAction::decode, QuestAction::handle);
        SkyNetwork.register(PartyTravelPrompt.class, NetworkDirection.PLAY_TO_CLIENT, PartyTravelPrompt::encode, PartyTravelPrompt::decode, PartyTravelPrompt::handle);
        SkyNetwork.register(PartyTravel.class, NetworkDirection.PLAY_TO_SERVER, PartyTravel::encode, PartyTravel::decode, PartyTravel::handle);
        SkyNetwork.register(NarrationCard.class, NetworkDirection.PLAY_TO_CLIENT, NarrationCard::encode, NarrationCard::decode, NarrationCard::handle);
        com.skycraft.quest.story.IntroPackets.register();
    }

    // ------------------------------------------------------------------ S2C

    /** Every quest (active and finished) of the player and their party, as {@code {quests: [Quest NBT + shared]}}. */
    public record SyncQuests(CompoundTag tag) {
        static void encode(SyncQuests m, FriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncQuests decode(FriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new SyncQuests(tag == null ? new CompoundTag() : tag);
        }

        static void handle(SyncQuests m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientQuestData.onQuests(m.tag));
            ctx.get().setPacketHandled(true);
        }
    }

    /** A narration card (the opening): a short text shown wrapped in a band on screen for {@code stay} ticks. */
    public record NarrationCard(net.minecraft.network.chat.Component text, int stay) {
        static void encode(NarrationCard m, FriendlyByteBuf buf) {
            buf.writeComponent(m.text);
            buf.writeVarInt(m.stay);
        }

        static NarrationCard decode(FriendlyByteBuf buf) {
            return new NarrationCard(buf.readComponent(), buf.readVarInt());
        }

        static void handle(NarrationCard m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.quest.client.NarrationOverlay.show(m.text, m.stay));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Party state for the HUD and party screen (members with health, pending invites). Empty tag = no party. */
    public record SyncParty(CompoundTag tag) {
        static void encode(SyncParty m, FriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncParty decode(FriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new SyncParty(tag == null ? new CompoundTag() : tag);
        }

        static void handle(SyncParty m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientQuestData.onParty(m.tag));
            ctx.get().setPacketHandled(true);
        }
    }

    /** "Fast travel to {@code name} (party leader)?" shown on join / when joining a party. */
    public record PartyTravelPrompt(UUID target, String name, boolean leader) {
        static void encode(PartyTravelPrompt m, FriendlyByteBuf buf) {
            buf.writeUUID(m.target);
            buf.writeUtf(m.name, 64);
            buf.writeBoolean(m.leader);
        }

        static PartyTravelPrompt decode(FriendlyByteBuf buf) {
            return new PartyTravelPrompt(buf.readUUID(), buf.readUtf(64), buf.readBoolean());
        }

        static void handle(PartyTravelPrompt m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientQuestData.onTravelPrompt(m.target, m.name, m.leader));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ C2S

    /** Fast travel to a party member (validated on the server). */
    public record PartyTravel(UUID target) {
        static void encode(PartyTravel m, FriendlyByteBuf buf) {
            buf.writeUUID(m.target);
        }

        static PartyTravel decode(FriendlyByteBuf buf) {
            return new PartyTravel(buf.readUUID());
        }

        static void handle(PartyTravel m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) com.skycraft.quest.party.PartyTravel.travel(player, m.target);
            ctx.get().setPacketHandled(true);
        }
    }

    public record PartyAction(int action, UUID target) {
        public static final int CREATE = 0;
        public static final int INVITE = 1;
        public static final int ACCEPT = 2;
        public static final int DECLINE = 3;
        public static final int LEAVE = 4;
        public static final int KICK = 5;
        public static final int PROMOTE = 6;

        static void encode(PartyAction m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeUUID(m.target);
        }

        static PartyAction decode(FriendlyByteBuf buf) {
            return new PartyAction(buf.readVarInt(), buf.readUUID());
        }

        static void handle(PartyAction m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                UUID target = NONE.equals(m.target) ? null : m.target;
                switch (m.action) {
                    case CREATE -> Parties.create(player);
                    case INVITE -> {
                        ServerPlayer other = target == null ? null : player.server.getPlayerList().getPlayer(target);
                        if (other != null) Parties.invite(player, other);
                    }
                    case ACCEPT -> Parties.accept(player, target);
                    case DECLINE -> Parties.decline(player, target);
                    case LEAVE -> Parties.leave(player);
                    case KICK -> {
                        if (target != null) Parties.kick(player, target);
                    }
                    case PROMOTE -> {
                        if (target != null) Parties.promote(player, target);
                    }
                    default -> {
                    }
                }
            }
            ctx.get().setPacketHandled(true);
        }
    }

    public record QuestAction(int action, String questId) {
        public static final int TRACK = 0;
        public static final int UNTRACK = 1;
        public static final int ABANDON = 2;
        public static final int SHARE = 3;
        /** Skip the current step of a quest stuck for longer than the configured days (validated on the server). */
        public static final int SKIP = 4;

        static void encode(QuestAction m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeUtf(m.questId, 64);
        }

        static QuestAction decode(FriendlyByteBuf buf) {
            return new QuestAction(buf.readVarInt(), buf.readUtf(64));
        }

        static void handle(QuestAction m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                PlayerData data = SkyData.get(player);
                switch (m.action) {
                    case TRACK -> {
                        if (Quests.find(player.server, player.getUUID(), m.questId) != null) {
                            data.module("quest").putString("tracked", m.questId);
                            data.markDirty();
                        }
                    }
                    case UNTRACK -> {
                        data.module("quest").remove("tracked");
                        data.markDirty();
                    }
                    case ABANDON -> Quests.abandon(player, m.questId);
                    case SHARE -> Quests.shareWithParty(player, m.questId);
                    case SKIP -> com.skycraft.quest.story.Story.skip(player, m.questId);
                    default -> {
                    }
                }
            }
            ctx.get().setPacketHandled(true);
        }
    }
}
