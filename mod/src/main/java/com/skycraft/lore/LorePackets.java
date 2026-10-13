package com.skycraft.lore;

import com.skycraft.lore.client.LoreClient;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Lore module packets: the Standing Stone power key (C2S), "open this book" and "open this story" (S2C). */
public final class LorePackets {
    private LorePackets() {}

    /** Fixed order; called from {@link LoreModule#registerPackets()}. */
    public static void register() {
        SkyNetwork.register(UsePower.class, NetworkDirection.PLAY_TO_SERVER, UsePower::encode, UsePower::decode, UsePower::handle);
        SkyNetwork.register(OpenBook.class, NetworkDirection.PLAY_TO_CLIENT, OpenBook::encode, OpenBook::decode, OpenBook::handle);
        SkyNetwork.register(OpenStory.class, NetworkDirection.PLAY_TO_CLIENT, OpenStory::encode, OpenStory::decode, OpenStory::handle);
    }

    /** The player pressed the Standing Stone power key. */
    public record UsePower() {
        static void encode(UsePower m, FriendlyByteBuf buf) {
        }

        static UsePower decode(FriendlyByteBuf buf) {
            return new UsePower();
        }

        static void handle(UsePower m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) StandingStones.usePower(player);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Opens the reading screen for a book id. */
    public record OpenBook(String id) {
        static void encode(OpenBook m, FriendlyByteBuf buf) {
            buf.writeUtf(m.id, 128);
        }

        static OpenBook decode(FriendlyByteBuf buf) {
            return new OpenBook(buf.readUtf(128));
        }

        static void handle(OpenBook m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> LoreClient.openBook(m.id));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Opens the reading screen on a story that has no book id: what a lectern, a clue sign or a note barrel holds.
     * {@code pagesJson} are chat-component JSON (so translation keys resolve in the reader's language);
     * {@code kind} is {@code lectern}, {@code sign} or {@code note} (the label under the title).
     */
    public record OpenStory(String title, String author, List<String> pagesJson, boolean sheet, String kind) {
        public static final int MAX_PAGES = 48, MAX_PAGE = 8000;

        static void encode(OpenStory m, FriendlyByteBuf buf) {
            buf.writeUtf(m.title, 256);
            buf.writeUtf(m.author, 128);
            int n = Math.min(MAX_PAGES, m.pagesJson.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) buf.writeUtf(m.pagesJson.get(i), MAX_PAGE);
            buf.writeBoolean(m.sheet);
            buf.writeUtf(m.kind, 32);
        }

        static OpenStory decode(FriendlyByteBuf buf) {
            String title = buf.readUtf(256), author = buf.readUtf(128);
            int n = Math.min(MAX_PAGES, buf.readVarInt());
            List<String> pages = new ArrayList<>(n);
            for (int i = 0; i < n; i++) pages.add(buf.readUtf(MAX_PAGE));
            return new OpenStory(title, author, pages, buf.readBoolean(), buf.readUtf(32));
        }

        static void handle(OpenStory m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> LoreClient.openStory(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
