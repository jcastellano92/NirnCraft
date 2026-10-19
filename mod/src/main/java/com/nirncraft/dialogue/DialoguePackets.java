package com.nirncraft.dialogue;

import com.nirncraft.client.DialogueClient;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Network messages for the dialogue system. */
public final class DialoguePackets {
    private DialoguePackets() {}

    public static void register() {
        SkyNetwork.register(OpenDialogue.class, PacketFlow.CLIENTBOUND, OpenDialogue::encode, OpenDialogue::decode, OpenDialogue::handle);
        SkyNetwork.register(ChooseOption.class, PacketFlow.SERVERBOUND, ChooseOption::encode, ChooseOption::decode, ChooseOption::handle);
        SkyNetwork.register(CloseDialogue.class, PacketFlow.SERVERBOUND, CloseDialogue::encode, CloseDialogue::decode, CloseDialogue::handle);
    }

    public record Line(String id, Component label, boolean enabled) {
        public Line(String id, Component label) {
            this(id, label, true);
        }
    }

    public record OpenDialogue(int entityId, Component npcName, Component greeting, List<Line> lines) {
        static void encode(OpenDialogue m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.npcName);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.greeting);
            buf.writeVarInt(m.lines.size());
            for (Line l : m.lines) {
                buf.writeUtf(l.id(), 128);
                net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, l.label());
                buf.writeBoolean(l.enabled());
            }
        }

        static OpenDialogue decode(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            Component name = net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf);
            Component greeting = net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf);
            int n = buf.readVarInt();
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) lines.add(new Line(buf.readUtf(128), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readBoolean()));
            return new OpenDialogue(id, name, greeting, lines);
        }

        static void handle(OpenDialogue m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> DialogueClient.open(m));
        }
    }

    public record ChooseOption(int entityId, String optionId) {
        static void encode(ChooseOption m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeUtf(m.optionId, 128);
        }

        static ChooseOption decode(RegistryFriendlyByteBuf buf) {
            return new ChooseOption(buf.readVarInt(), buf.readUtf(128));
        }

        static void handle(ChooseOption m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Dialogue.choose(player, m.entityId, m.optionId);
        }
    }

    public record CloseDialogue(int entityId) {
        static void encode(CloseDialogue m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
        }

        static CloseDialogue decode(RegistryFriendlyByteBuf buf) {
            return new CloseDialogue(buf.readVarInt());
        }

        static void handle(CloseDialogue m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Dialogue.endConversation(player, m.entityId);
        }
    }
}
