package com.nirncraft.quest;

import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.quest.client.ClientQuestData;
import com.nirncraft.quest.party.Parties;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Quest & party packets: journal sync, party state (HUD), party actions and quest actions (track, abandon). */
public final class QuestPackets {
    /** "No target" UUID for party actions. */
    public static final UUID NONE = new UUID(0L, 0L);

    private QuestPackets() {}

    public static void register() {
        SkyNetwork.register(SyncQuests.class, PacketFlow.CLIENTBOUND, SyncQuests::encode, SyncQuests::decode, SyncQuests::handle);
        SkyNetwork.register(SyncParty.class, PacketFlow.CLIENTBOUND, SyncParty::encode, SyncParty::decode, SyncParty::handle);
        SkyNetwork.register(PartyAction.class, PacketFlow.SERVERBOUND, PartyAction::encode, PartyAction::decode, PartyAction::handle);
        SkyNetwork.register(QuestAction.class, PacketFlow.SERVERBOUND, QuestAction::encode, QuestAction::decode, QuestAction::handle);
        SkyNetwork.register(PartyTravelPrompt.class, PacketFlow.CLIENTBOUND, PartyTravelPrompt::encode, PartyTravelPrompt::decode, PartyTravelPrompt::handle);
        SkyNetwork.register(PartyTravel.class, PacketFlow.SERVERBOUND, PartyTravel::encode, PartyTravel::decode, PartyTravel::handle);
        SkyNetwork.register(NarrationCard.class, PacketFlow.CLIENTBOUND, NarrationCard::encode, NarrationCard::decode, NarrationCard::handle);
        SkyNetwork.register(OpenNoticeBoard.class, PacketFlow.CLIENTBOUND, OpenNoticeBoard::encode, OpenNoticeBoard::decode, OpenNoticeBoard::handle);
        SkyNetwork.register(AcceptBoardJob.class, PacketFlow.SERVERBOUND, AcceptBoardJob::encode, AcceptBoardJob::decode, AcceptBoardJob::handle);
        com.nirncraft.quest.story.IntroPackets.register();
    }

    // ------------------------------------------------------------------ S2C

    /** Every quest (active and finished) of the player and their party, as {@code {quests: [Quest NBT + shared]}}. */
    public record SyncQuests(CompoundTag tag) {
        static void encode(SyncQuests m, RegistryFriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncQuests decode(RegistryFriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new SyncQuests(tag == null ? new CompoundTag() : tag);
        }

        static void handle(SyncQuests m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientQuestData.onQuests(m.tag));
        }
    }

    /** A narration card (the opening): a short text shown wrapped in a band on screen for {@code stay} ticks. */
    public record NarrationCard(net.minecraft.network.chat.Component text, int stay) {
        static void encode(NarrationCard m, RegistryFriendlyByteBuf buf) {
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.text);
            buf.writeVarInt(m.stay);
        }

        static NarrationCard decode(RegistryFriendlyByteBuf buf) {
            return new NarrationCard(net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt());
        }

        static void handle(NarrationCard m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.quest.client.NarrationOverlay.show(m.text, m.stay));
        }
    }

    /** Party state for the HUD and party screen (members with health, pending invites). Empty tag = no party. */
    public record SyncParty(CompoundTag tag) {
        static void encode(SyncParty m, RegistryFriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncParty decode(RegistryFriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new SyncParty(tag == null ? new CompoundTag() : tag);
        }

        static void handle(SyncParty m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientQuestData.onParty(m.tag));
        }
    }

    /** "Fast travel to {@code name} (party leader)?" shown on join / when joining a party. */
    public record PartyTravelPrompt(UUID target, String name, boolean leader) {
        static void encode(PartyTravelPrompt m, RegistryFriendlyByteBuf buf) {
            buf.writeUUID(m.target);
            buf.writeUtf(m.name, 64);
            buf.writeBoolean(m.leader);
        }

        static PartyTravelPrompt decode(RegistryFriendlyByteBuf buf) {
            return new PartyTravelPrompt(buf.readUUID(), buf.readUtf(64), buf.readBoolean());
        }

        static void handle(PartyTravelPrompt m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientQuestData.onTravelPrompt(m.target, m.name, m.leader));
        }
    }

    // ------------------------------------------------------------------ C2S

    /** Fast travel to a party member (validated on the server). */
    public record PartyTravel(UUID target) {
        static void encode(PartyTravel m, RegistryFriendlyByteBuf buf) {
            buf.writeUUID(m.target);
        }

        static PartyTravel decode(RegistryFriendlyByteBuf buf) {
            return new PartyTravel(buf.readUUID());
        }

        static void handle(PartyTravel m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) com.nirncraft.quest.party.PartyTravel.travel(player, m.target);
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

        static void encode(PartyAction m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeUUID(m.target);
        }

        static PartyAction decode(RegistryFriendlyByteBuf buf) {
            return new PartyAction(buf.readVarInt(), buf.readUUID());
        }

        static void handle(PartyAction m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                UUID target = NONE.equals(m.target) ? null : m.target;
                switch (m.action) {
                    case CREATE -> Parties.create(player);
                    case INVITE -> {
                        ServerPlayer other = target == null ? null : player.level().getServer().getPlayerList().getPlayer(target);
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
        }
    }

    public record QuestAction(int action, String questId) {
        public static final int TRACK = 0;
        public static final int UNTRACK = 1;
        public static final int ABANDON = 2;
        public static final int SHARE = 3;
        /** Skip the current step of a quest stuck for longer than the configured days (validated on the server). */
        public static final int SKIP = 4;

        static void encode(QuestAction m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeUtf(m.questId, 64);
        }

        static QuestAction decode(RegistryFriendlyByteBuf buf) {
            return new QuestAction(buf.readVarInt(), buf.readUtf(64));
        }

        static void handle(QuestAction m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                PlayerData data = SkyData.get(player);
                switch (m.action) {
                    case TRACK -> {
                        if (Quests.find(player.level().getServer(), player.getUUID(), m.questId) != null) {
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
                    case SKIP -> com.nirncraft.quest.story.Story.skip(player, m.questId);
                    default -> {
                    }
                }
            }
        }
    }

    /** Opens the interactive Notice Board parchment UI for a settlement (S2C). */
    public record OpenNoticeBoard(CompoundTag tag) {
        static void encode(OpenNoticeBoard m, RegistryFriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static OpenNoticeBoard decode(RegistryFriendlyByteBuf buf) {
            CompoundTag tag = buf.readNbt();
            return new OpenNoticeBoard(tag == null ? new CompoundTag() : tag);
        }

        static void handle(OpenNoticeBoard m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.nirncraft.quest.client.NoticeBoardScreen.open(m.tag));
        }
    }

    /** Accepts a pinned contract from the Notice Board screen (C2S). */
    public record AcceptBoardJob(String settlementId, int jobIndex) {
        static void encode(AcceptBoardJob m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.settlementId, 64);
            buf.writeVarInt(m.jobIndex);
        }

        static AcceptBoardJob decode(RegistryFriendlyByteBuf buf) {
            return new AcceptBoardJob(buf.readUtf(64), buf.readVarInt());
        }

        static void handle(AcceptBoardJob m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                NoticeBoards.resolveSettlement(m.settlementId, player)
                        .ifPresent(place -> NoticeBoards.accept(player, place, m.jobIndex));
            }
        }
    }
}
