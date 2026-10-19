package com.nirncraft.network;

import com.nirncraft.NirnCraft;
import com.nirncraft.client.ClientPacketHandlers;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.Race;
import com.nirncraft.core.SkyData;
import com.nirncraft.dig.PlacedBlocks;
import com.nirncraft.perk.Perk;
import com.nirncraft.perk.Perks;
import com.nirncraft.skills.Progression;
import com.nirncraft.vitals.ActionHandler;
import com.nirncraft.vitals.Vitals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.nirncraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Packets owned by the core (player data sync, notifications, level-up choices, perks, race, key actions). */
public final class CorePackets {
    private CorePackets() {}

    public static void register() {
        SkyNetwork.register(SyncData.class, PacketFlow.CLIENTBOUND, SyncData::encode, SyncData::decode, SyncData::handle);
        SkyNetwork.register(SyncVitals.class, PacketFlow.CLIENTBOUND, SyncVitals::encode, SyncVitals::decode, SyncVitals::handle);
        SkyNetwork.register(Notify.class, PacketFlow.CLIENTBOUND, Notify::encode, Notify::decode, Notify::handle);
        SkyNetwork.register(OpenScreen.class, PacketFlow.CLIENTBOUND, OpenScreen::encode, OpenScreen::decode, OpenScreen::handle);
        SkyNetwork.register(ChooseAttribute.class, PacketFlow.SERVERBOUND, ChooseAttribute::encode, ChooseAttribute::decode, ChooseAttribute::handle);
        SkyNetwork.register(UnlockPerk.class, PacketFlow.SERVERBOUND, UnlockPerk::encode, UnlockPerk::decode, UnlockPerk::handle);
        SkyNetwork.register(ChooseRace.class, PacketFlow.SERVERBOUND, ChooseRace::encode, ChooseRace::decode, ChooseRace::handle);
        SkyNetwork.register(Action.class, PacketFlow.SERVERBOUND, Action::encode, Action::decode, Action::handle);
        SkyNetwork.register(HarvestBlock.class, PacketFlow.SERVERBOUND, HarvestBlock::encode, HarvestBlock::decode, HarvestBlock::handle);
        SkyNetwork.register(ContainerTake.class, PacketFlow.SERVERBOUND, ContainerTake::encode, ContainerTake::decode, ContainerTake::handle);
        SkyNetwork.register(ContainerStore.class, PacketFlow.SERVERBOUND, ContainerStore::encode, ContainerStore::decode, ContainerStore::handle);
        SkyNetwork.register(SyncProgress.class, PacketFlow.CLIENTBOUND, SyncProgress::encode, SyncProgress::decode, SyncProgress::handle);
        SkyNetwork.register(SyncAppearance.class, PacketFlow.CLIENTBOUND, SyncAppearance::encode, SyncAppearance::decode, SyncAppearance::handle);
        SkyNetwork.register(PlacedBlocksSync.class, PacketFlow.CLIENTBOUND, PlacedBlocksSync::encode, PlacedBlocksSync::decode, PlacedBlocksSync::handle);
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
            CompoundTag tag = data.saveExcludingModules(List.of("pack",
                    com.nirncraft.inventory.ContainerOverflow.ENDER_MODULE,
                    com.nirncraft.mounts.Saddlebags.OVERFLOW_MODULE));
            CompoundTag modules = tag.getCompoundOrEmpty("modules");
            ListTag omitted = tag.getListOrEmpty("omitted");
            // the overflow pack (inventory.Pack) is mirrored by its own chunked packets, never by this one
            // (so is the ender chest's overflow, inventory.ContainerOverflow: server data the client never reads)
            // (and the mount saddlebags' overflow, mounts.Saddlebags: also server data)
            int size = sizeOf(tag);
            if (size <= MAX_BYTES) {
                if (!omitted.isEmpty()) tag.put("omitted", omitted);
                return new SyncData(tag);
            }
            List<String> bySize = new ArrayList<>(modules.keySet());
            Map<String, Integer> sizes = new HashMap<>();
            for (String k : bySize) sizes.put(k, sizeOf(modules.getCompoundOrEmpty(k)));
            bySize.sort((a, b) -> Integer.compare(sizes.get(b), sizes.get(a)));
            for (String k : bySize) {
                if (size <= MAX_BYTES) break;
                modules.remove(k);
                omitted.add(StringTag.valueOf(k));
                size -= sizes.get(k);
                NirnCraft.LOGGER.warn("Player data module '{}' is {} bytes; not syncing it to the client", k, sizes.get(k));
            }
            tag.put("omitted", omitted);
            return new SyncData(tag);
        }

        private static int sizeOf(CompoundTag tag) {
            ByteBuf raw = Unpooled.buffer();
            try {
                new net.minecraft.network.FriendlyByteBuf(raw).writeNbt(tag);
                return raw.readableBytes();
            } finally {
                raw.release();
            }
        }

        static void encode(SyncData m, RegistryFriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncData decode(RegistryFriendlyByteBuf buf) {
            return new SyncData(buf.readNbt());
        }

        static void handle(SyncData m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncData(m.tag));
        }
    }

    /** Small, throttled update of skills, level, attribute pools, perk points, gold and stats (owner only). */
    public record SyncProgress(CompoundTag tag) {
        static void encode(SyncProgress m, RegistryFriendlyByteBuf buf) {
            buf.writeNbt(m.tag);
        }

        static SyncProgress decode(RegistryFriendlyByteBuf buf) {
            return new SyncProgress(buf.readNbt());
        }

        static void handle(SyncProgress m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncProgress(m.tag));
        }
    }

    /** Race, sheathe state and apparel of a player, sent to everyone tracking them (see {@link PlayerData#appearance()}). */
    public record SyncAppearance(int entityId, CompoundTag tag) {
        static void encode(SyncAppearance m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeNbt(m.tag);
        }

        static SyncAppearance decode(RegistryFriendlyByteBuf buf) {
            return new SyncAppearance(buf.readVarInt(), buf.readNbt());
        }

        static void handle(SyncAppearance m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncAppearance(m.entityId, m.tag));
        }
    }

    /**
     * Player-placed block positions of one chunk ({@link PlacedBlocks}). {@code full} replaces the client's set for
     * the chunk; otherwise {@code added}/{@code removed} are deltas.
     */
    public record PlacedBlocksSync(long chunk, boolean full, long[] added, long[] removed) {
        static void encode(PlacedBlocksSync m, RegistryFriendlyByteBuf buf) {
            buf.writeLong(m.chunk);
            buf.writeBoolean(m.full);
            buf.writeLongArray(m.added);
            buf.writeLongArray(m.removed);
        }

        static PlacedBlocksSync decode(RegistryFriendlyByteBuf buf) {
            return new PlacedBlocksSync(buf.readLong(), buf.readBoolean(), buf.readLongArray(), buf.readLongArray());
        }

        static void handle(PlacedBlocksSync m, IPayloadContext ctx) {
            PlacedBlocks.applyClientSync(m.chunk, m.full, m.added, m.removed);
        }
    }

    /** Frequent, small update of the current magicka/stamina pools and stealth/combat flags. */
    public record SyncVitals(float magicka, float stamina, byte flags) {
        public static final byte DETECTED = 1;
        public static final byte IN_COMBAT = 2;
        public static final byte SNEAK_HIDDEN = 4;
        public static final byte EXHAUSTED = 8;

        static void encode(SyncVitals m, RegistryFriendlyByteBuf buf) {
            buf.writeFloat(m.magicka);
            buf.writeFloat(m.stamina);
            buf.writeByte(m.flags);
        }

        static SyncVitals decode(RegistryFriendlyByteBuf buf) {
            return new SyncVitals(buf.readFloat(), buf.readFloat(), buf.readByte());
        }

        static void handle(SyncVitals m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.syncVitals(m.magicka, m.stamina, m.flags));
        }
    }

    /**
     * A HUD notification. {@code kind} is a {@link NotifyKind}; {@code value} carries a skill ordinal for
     * skill popups and {@code progress} the 0..1 bar fill.
     */
    public record Notify(NotifyKind kind, Component title, Component subtitle, int value, float progress) {
        static void encode(Notify m, RegistryFriendlyByteBuf buf) {
            buf.writeEnum(m.kind);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.title);
            net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, m.subtitle);
            buf.writeVarInt(m.value);
            buf.writeFloat(m.progress);
        }

        static Notify decode(RegistryFriendlyByteBuf buf) {
            return new Notify(buf.readEnum(NotifyKind.class), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readFloat());
        }

        static void handle(Notify m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.notify(m));
        }
    }

    /** Asks the client to open one of the core screens. */
    public record OpenScreen(int screen) {
        public static final int RACE = 0;
        public static final int LEVEL_UP = 1;
        public static final int SKILLS = 2;

        static void encode(OpenScreen m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.screen);
        }

        static OpenScreen decode(RegistryFriendlyByteBuf buf) {
            return new OpenScreen(buf.readVarInt());
        }

        static void handle(OpenScreen m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.openScreen(m.screen));
        }
    }

    // ------------------------------------------------------------------ C2S

    /** Level-up choice: 0 health, 1 magicka, 2 stamina. */
    public record ChooseAttribute(int which) {
        static void encode(ChooseAttribute m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.which);
        }

        static ChooseAttribute decode(RegistryFriendlyByteBuf buf) {
            return new ChooseAttribute(buf.readVarInt());
        }

        static void handle(ChooseAttribute m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Progression.applyLevelUpChoice(player, m.which);
        }
    }

    public record UnlockPerk(String perkId) {
        static void encode(UnlockPerk m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.perkId, 128);
        }

        static UnlockPerk decode(RegistryFriendlyByteBuf buf) {
            return new UnlockPerk(buf.readUtf(128));
        }

        static void handle(UnlockPerk m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                Perk perk = Perks.get(m.perkId);
                PlayerData data = SkyData.get(player);
                if (perk != null && data.getPerkPoints() > 0 && Perks.canUnlock(data, perk)) {
                    data.setPerkRank(perk.id(), data.getPerkRank(perk.id()) + 1);
                    data.setPerkPoints(data.getPerkPoints() - 1);
                    Vitals.refreshAttributes(player);
                }
            }
        }
    }

    /** The character creator's choice: race and look ({@code society.NpcLook} packing, -1 = the race's default). */
    public record ChooseRace(String raceId, int look) {
        public ChooseRace(String raceId) {
            this(raceId, -1);
        }

        static void encode(ChooseRace m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.raceId, 64);
            buf.writeInt(m.look);
        }

        static ChooseRace decode(RegistryFriendlyByteBuf buf) {
            return new ChooseRace(buf.readUtf(64), buf.readInt());
        }

        static void handle(ChooseRace m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            Race race = Race.byId(m.raceId);
            // the look is validated (forced to the race, every field clamped) in Progression.chooseRace
            if (player != null && race != null) Progression.chooseRace(player, race, m.look);
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

        static void encode(Action m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeVarInt(m.arg);
        }

        static Action decode(RegistryFriendlyByteBuf buf) {
            return new Action(buf.readVarInt(), buf.readVarInt());
        }

        static void handle(Action m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) ActionHandler.handle(player, m.action, m.arg);
        }
    }

    /** Harvest wild plants, tall grass, flora and mature crops directly into bags. */
    public record HarvestBlock(net.minecraft.core.BlockPos pos) {
        static void encode(HarvestBlock m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
        }

        static HarvestBlock decode(RegistryFriendlyByteBuf buf) {
            return new HarvestBlock(buf.readBlockPos());
        }

        static void handle(HarvestBlock m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) com.nirncraft.survival.Harvesting.harvest(player, m.pos);
        }
    }

    /** Take item or all items from an open container directly into bags without equipping to hand. */
    public record ContainerTake(int containerId, int slotIndex, boolean all) {
        static void encode(ContainerTake m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.containerId);
            buf.writeVarInt(m.slotIndex);
            buf.writeBoolean(m.all);
        }

        static ContainerTake decode(RegistryFriendlyByteBuf buf) {
            return new ContainerTake(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(ContainerTake m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) ActionHandler.handleContainerTake(player, m.containerId, m.slotIndex, m.all);
        }
    }

    /** Store item or all items from player inventory into an open container. */
    public record ContainerStore(int containerId, int slotIndex, boolean all) {
        static void encode(ContainerStore m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.containerId);
            buf.writeVarInt(m.slotIndex);
            buf.writeBoolean(m.all);
        }

        static ContainerStore decode(RegistryFriendlyByteBuf buf) {
            return new ContainerStore(buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        static void handle(ContainerStore m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) ActionHandler.handleContainerStore(player, m.containerId, m.slotIndex, m.all);
        }
    }
}
