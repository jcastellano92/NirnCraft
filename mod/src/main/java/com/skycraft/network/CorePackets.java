package com.skycraft.network;

import com.skycraft.Skycraft;
import com.skycraft.client.ClientPacketHandlers;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.perk.Perk;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.ActionHandler;
import com.skycraft.vitals.Vitals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Packets owned by the core (player data sync, notifications, level-up choices, perks, race, key actions). */
public final class CorePackets {
    private CorePackets() {}

    public static void register() {
        SkyNetwork.register(SyncData.class, NetworkDirection.PLAY_TO_CLIENT, SyncData::encode, SyncData::decode, SyncData::handle);
        SkyNetwork.register(SyncVitals.class, NetworkDirection.PLAY_TO_CLIENT, SyncVitals::encode, SyncVitals::decode, SyncVitals::handle);
        SkyNetwork.register(Notify.class, NetworkDirection.PLAY_TO_CLIENT, Notify::encode, Notify::decode, Notify::handle);
        SkyNetwork.register(OpenScreen.class, NetworkDirection.PLAY_TO_CLIENT, OpenScreen::encode, OpenScreen::decode, OpenScreen::handle);
        SkyNetwork.register(ChooseAttribute.class, NetworkDirection.PLAY_TO_SERVER, ChooseAttribute::encode, ChooseAttribute::decode, ChooseAttribute::handle);
        SkyNetwork.register(UnlockPerk.class, NetworkDirection.PLAY_TO_SERVER, UnlockPerk::encode, UnlockPerk::decode, UnlockPerk::handle);
        SkyNetwork.register(ChooseRace.class, NetworkDirection.PLAY_TO_SERVER, ChooseRace::encode, ChooseRace::decode, ChooseRace::handle);
        SkyNetwork.register(Action.class, NetworkDirection.PLAY_TO_SERVER, Action::encode, Action::decode, Action::handle);
        SkyNetwork.register(HarvestBlock.class, NetworkDirection.PLAY_TO_SERVER, HarvestBlock::encode, HarvestBlock::decode, HarvestBlock::handle);
        SkyNetwork.register(ContainerTake.class, NetworkDirection.PLAY_TO_SERVER, ContainerTake::encode, ContainerTake::decode, ContainerTake::handle);
        SkyNetwork.register(ContainerStore.class, NetworkDirection.PLAY_TO_SERVER, ContainerStore::encode, ContainerStore::decode, ContainerStore::handle);
        SkyNetwork.register(SyncProgress.class, NetworkDirection.PLAY_TO_CLIENT, SyncProgress::encode, SyncProgress::decode, SyncProgress::handle);
        SkyNetwork.register(SyncAppearance.class, NetworkDirection.PLAY_TO_CLIENT, SyncAppearance::encode, SyncAppearance::decode, SyncAppearance::handle);
        SkyNetwork.register(PlacedBlocksSync.class, NetworkDirection.PLAY_TO_CLIENT, PlacedBlocksSync::encode, PlacedBlocksSync::decode, PlacedBlocksSync::handle);
    }

    // ------------------------------------------------------------------ S2C

    /** Full player data sync (owner only). Built through {@link #of(PlayerData)} so it always fits in one packet. */
    public record SyncData(CompoundTag tag) {
        /** Vanilla's client-bound custom payload limit is 1 MiB; stay well below it. */
        private static final int MAX_BYTES = 900_000;

        /**
         * Serializes the player's data. If it would not fit in a packet, the largest module tags are left out (and
         * listed in {@code "omitted"}, so the client keeps its previous copy) instead of disconnecting the player.
         */
        public static SyncData of(PlayerData data) {
            CompoundTag tag = data.save();
            int size = sizeOf(tag);
            if (size <= MAX_BYTES) return new SyncData(tag);
            CompoundTag modules = tag.getCompound("modules");
            List<String> bySize = new ArrayList<>(modules.getAllKeys());
            Map<String, Integer> sizes = new HashMap<>();
            for (String k : bySize) sizes.put(k, sizeOf(modules.getCompound(k)));
            bySize.sort((a, b) -> Integer.compare(sizes.get(b), sizes.get(a)));
            ListTag omitted = new ListTag();
            for (String k : bySize) {
                if (size <= MAX_BYTES) break;
                modules.remove(k);
                omitted.add(StringTag.valueOf(k));
                size -= sizes.get(k);
                Skycraft.LOGGER.warn("Player data module '{}' is {} bytes; not syncing it to the client", k, sizes.get(k));
            }
            tag.put("omitted", omitted);
            return new SyncData(tag);
        }

        private static int sizeOf(CompoundTag tag) {
            ByteBuf raw = Unpooled.buffer();
            try {
                new FriendlyByteBuf(raw).writeNbt(tag);
                return raw.readableBytes();
            } finally {
                raw.release();
            }
        }

        static void encode(SyncData m, FriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncData decode(FriendlyByteBuf buf) {
            return new SyncData(buf.readNbt());
        }

        static void handle(SyncData m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncData(m.tag));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Small, throttled update of skills, level, attribute pools, perk points, gold and stats (owner only). */
    public record SyncProgress(CompoundTag tag) {
        static void encode(SyncProgress m, FriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncProgress decode(FriendlyByteBuf buf) {
            return new SyncProgress(buf.readNbt());
        }

        static void handle(SyncProgress m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncProgress(m.tag));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Race, sheathe state and apparel of a player, sent to everyone tracking them (see {@link PlayerData#appearance()}). */
    public record SyncAppearance(int entityId, CompoundTag tag) {
        static void encode(SyncAppearance m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeNbt(m.tag);
        }

        static SyncAppearance decode(FriendlyByteBuf buf) {
            return new SyncAppearance(buf.readVarInt(), buf.readNbt());
        }

        static void handle(SyncAppearance m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncAppearance(m.entityId, m.tag));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Player-placed block positions of one chunk ({@link PlacedBlocks}). {@code full} replaces the client's set for
     * the chunk; otherwise {@code added}/{@code removed} are deltas.
     */
    public record PlacedBlocksSync(long chunk, boolean full, long[] added, long[] removed) {
        static void encode(PlacedBlocksSync m, FriendlyByteBuf buf) {
            buf.writeLong(m.chunk);
            buf.writeBoolean(m.full);
            buf.writeLongArray(m.added);
            buf.writeLongArray(m.removed);
        }

        static PlacedBlocksSync decode(FriendlyByteBuf buf) {
            return new PlacedBlocksSync(buf.readLong(), buf.readBoolean(), buf.readLongArray(), buf.readLongArray());
        }

        static void handle(PlacedBlocksSync m, Supplier<NetworkEvent.Context> ctx) {
            PlacedBlocks.applyClientSync(m.chunk, m.full, m.added, m.removed);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Frequent, small update of the current magicka/stamina pools and stealth/combat flags. */
    public record SyncVitals(float magicka, float stamina, byte flags) {
        public static final byte DETECTED = 1;
        public static final byte IN_COMBAT = 2;
        public static final byte SNEAK_HIDDEN = 4;
        public static final byte EXHAUSTED = 8;

        static void encode(SyncVitals m, FriendlyByteBuf buf) {
            buf.writeFloat(m.magicka);
            buf.writeFloat(m.stamina);
            buf.writeByte(m.flags);
        }

        static SyncVitals decode(FriendlyByteBuf buf) {
            return new SyncVitals(buf.readFloat(), buf.readFloat(), buf.readByte());
        }

        static void handle(SyncVitals m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncVitals(m.magicka, m.stamina, m.flags));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * A HUD notification. {@code kind} is a {@link NotifyKind}; {@code value} carries a skill ordinal for
     * skill popups and {@code progress} the 0..1 bar fill.
     */
    public record Notify(NotifyKind kind, Component title, Component subtitle, int value, float progress) {
        static void encode(Notify m, FriendlyByteBuf buf) {
            buf.writeEnum(m.kind);
            buf.writeComponent(m.title);
            buf.writeComponent(m.subtitle);
            buf.writeVarInt(m.value);
            buf.writeFloat(m.progress);
        }

        static Notify decode(FriendlyByteBuf buf) {
            return new Notify(buf.readEnum(NotifyKind.class), buf.readComponent(), buf.readComponent(), buf.readVarInt(), buf.readFloat());
        }

        static void handle(Notify m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.notify(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Asks the client to open one of the core screens. */
    public record OpenScreen(int screen) {
        public static final int RACE = 0;
        public static final int LEVEL_UP = 1;
        public static final int SKILLS = 2;

        static void encode(OpenScreen m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.screen);
        }

        static OpenScreen decode(FriendlyByteBuf buf) {
            return new OpenScreen(buf.readVarInt());
        }

        static void handle(OpenScreen m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.openScreen(m.screen));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ C2S

    /** Level-up choice: 0 health, 1 magicka, 2 stamina. */
    public record ChooseAttribute(int which) {
        static void encode(ChooseAttribute m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.which);
        }

        static ChooseAttribute decode(FriendlyByteBuf buf) {
            return new ChooseAttribute(buf.readVarInt());
        }

        static void handle(ChooseAttribute m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Progression.applyLevelUpChoice(player, m.which);
            ctx.get().setPacketHandled(true);
        }
    }

    public record UnlockPerk(String perkId) {
        static void encode(UnlockPerk m, FriendlyByteBuf buf) {
            buf.writeUtf(m.perkId, 128);
        }

        static UnlockPerk decode(FriendlyByteBuf buf) {
            return new UnlockPerk(buf.readUtf(128));
        }

        static void handle(UnlockPerk m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                Perk perk = Perks.get(m.perkId);
                PlayerData data = SkyData.get(player);
                if (perk != null && data.getPerkPoints() > 0 && Perks.canUnlock(data, perk)) {
                    data.setPerkRank(perk.id(), data.getPerkRank(perk.id()) + 1);
                    data.setPerkPoints(data.getPerkPoints() - 1);
                    Vitals.refreshAttributes(player);
                }
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** The character creator's choice: race and look ({@code society.NpcLook} packing, -1 = the race's default). */
    public record ChooseRace(String raceId, int look) {
        public ChooseRace(String raceId) {
            this(raceId, -1);
        }

        static void encode(ChooseRace m, FriendlyByteBuf buf) {
            buf.writeUtf(m.raceId, 64);
            buf.writeInt(m.look);
        }

        static ChooseRace decode(FriendlyByteBuf buf) {
            return new ChooseRace(buf.readUtf(64), buf.readInt());
        }

        static void handle(ChooseRace m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            Race race = Race.byId(m.raceId);
            // the look is validated (forced to the race, every field clamped) in Progression.chooseRace
            if (player != null && race != null) Progression.chooseRace(player, race, m.look);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Generic keybind actions handled by the core. See {@link ActionHandler}. */
    public record Action(int action, int arg) {
        public static final int POWER_ATTACK = 0;
        public static final int BLOCK_START = 1;
        public static final int BLOCK_STOP = 2;
        public static final int USE_POWER = 3;
        public static final int MAKE_LEGENDARY = 4;
        public static final int SPRINT_EXHAUSTED = 5;
        public static final int SHEATHE_TOGGLE = 6;
        public static final int DODGE_ROLL = 7;
        public static final int CLIMB_TICK = 8;
        public static final int UNSTUCK = 9;
        public static final int TAKE_WORLD_ITEM = 10;
        public static final int SHIELD_BASH = 11;

        static void encode(Action m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeVarInt(m.arg);
        }

        static Action decode(FriendlyByteBuf buf) {
            return new Action(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(Action m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) ActionHandler.handle(player, m.action, m.arg);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Harvest wild plants, tall grass, flora and mature crops directly into bags. */
    public record HarvestBlock(net.minecraft.core.BlockPos pos) {
        static void encode(HarvestBlock m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
        }

        static HarvestBlock decode(FriendlyByteBuf buf) {
            return new HarvestBlock(buf.readBlockPos());
        }

        static void handle(HarvestBlock m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) com.skycraft.survival.Harvesting.harvest(player, m.pos);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Take item or all items from an open container directly into bags without equipping to hand. */
    public record ContainerTake(int containerId, int slotIndex, boolean all) {
        static void encode(ContainerTake m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.containerId);
            buf.writeVarInt(m.slotIndex);
            buf.writeBoolean(m.all);
        }

        static ContainerTake decode(FriendlyByteBuf buf) {
            return new ContainerTake(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(ContainerTake m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) ActionHandler.handleContainerTake(player, m.containerId, m.slotIndex, m.all);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Store item or all items from player inventory into an open container. */
    public record ContainerStore(int containerId, int slotIndex, boolean all) {
        static void encode(ContainerStore m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.containerId);
            buf.writeVarInt(m.slotIndex);
            buf.writeBoolean(m.all);
        }

        static ContainerStore decode(FriendlyByteBuf buf) {
            return new ContainerStore(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(ContainerStore m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) ActionHandler.handleContainerStore(player, m.containerId, m.slotIndex, m.all);
            ctx.get().setPacketHandled(true);
        }
    }
}
