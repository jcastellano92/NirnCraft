package com.skycraft.dialogue;

import com.skycraft.client.DialogueClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Network messages for the dialogue system. */
public final class DialoguePackets {
    private DialoguePackets() {}

    public static void register() {
        SkyNetwork.register(OpenDialogue.class, NetworkDirection.PLAY_TO_CLIENT, OpenDialogue::encode, OpenDialogue::decode, OpenDialogue::handle);
        SkyNetwork.register(ChooseOption.class, NetworkDirection.PLAY_TO_SERVER, ChooseOption::encode, ChooseOption::decode, ChooseOption::handle);
        SkyNetwork.register(CloseDialogue.class, NetworkDirection.PLAY_TO_SERVER, CloseDialogue::encode, CloseDialogue::decode, CloseDialogue::handle);
    }

    public record Line(String id, Component label, boolean enabled) {
        public Line(String id, Component label) {
            this(id, label, true);
        }
    }

    public record OpenDialogue(int entityId, Component npcName, Component greeting, List<Line> lines) {
        static void encode(OpenDialogue m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeComponent(m.npcName);
            buf.writeComponent(m.greeting);
            buf.writeVarInt(m.lines.size());
            for (Line l : m.lines) {
                buf.writeUtf(l.id(), 128);
                buf.writeComponent(l.label());
                buf.writeBoolean(l.enabled());
            }
        }

        static OpenDialogue decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            Component name = buf.readComponent();
            Component greeting = buf.readComponent();
            int n = buf.readVarInt();
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) lines.add(new Line(buf.readUtf(128), buf.readComponent(), buf.readBoolean()));
            return new OpenDialogue(id, name, greeting, lines);
        }

        static void handle(OpenDialogue m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DialogueClient.open(m));
            ctx.get().setPacketHandled(true);
        }
    }

    public record ChooseOption(int entityId, String optionId) {
        static void encode(ChooseOption m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeUtf(m.optionId, 128);
        }

        static ChooseOption decode(FriendlyByteBuf buf) {
            return new ChooseOption(buf.readVarInt(), buf.readUtf(128));
        }

        static void handle(ChooseOption m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Dialogue.choose(player, m.entityId, m.optionId);
            ctx.get().setPacketHandled(true);
        }
    }

    public record CloseDialogue(int entityId) {
        static void encode(CloseDialogue m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
        }

        static CloseDialogue decode(FriendlyByteBuf buf) {
            return new CloseDialogue(buf.readVarInt());
        }

        static void handle(CloseDialogue m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Dialogue.endConversation(player, m.entityId);
            ctx.get().setPacketHandled(true);
        }
    }
}
