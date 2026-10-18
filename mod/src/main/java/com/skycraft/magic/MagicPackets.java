package com.skycraft.magic;

import com.skycraft.magic.client.ClientFx;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.shout.Shouting;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.Spells;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/** Packets of the magic module. Registered in this fixed order by {@link MagicModule#registerPackets()}. */
public final class MagicPackets {
    private MagicPackets() {}

    public static void register() {
        SkyNetwork.register(Cast.class, PacketFlow.SERVERBOUND, Cast::encode, Cast::decode, Cast::handle);
        SkyNetwork.register(ShoutPacket.class, PacketFlow.SERVERBOUND, ShoutPacket::encode, ShoutPacket::decode, ShoutPacket::handle);
        SkyNetwork.register(MenuAction.class, PacketFlow.SERVERBOUND, MenuAction::encode, MenuAction::decode, MenuAction::handle);
        SkyNetwork.register(Fx.class, PacketFlow.CLIENTBOUND, Fx::encode, Fx::decode, Fx::handle);
        SkyNetwork.register(ShoutFx.class, PacketFlow.CLIENTBOUND, ShoutFx::encode, ShoutFx::decode, ShoutFx::handle);
        SkyNetwork.register(MakeSpell.class, PacketFlow.SERVERBOUND, MakeSpell::encode, MakeSpell::decode, MakeSpell::handle);
        SkyNetwork.register(StaffEnchant.class, PacketFlow.SERVERBOUND, StaffEnchant::encode, StaffEnchant::decode, StaffEnchant::handle);
        SkyNetwork.register(com.skycraft.skills.Acrobatics.DoubleJump.class, PacketFlow.SERVERBOUND,
                com.skycraft.skills.Acrobatics.DoubleJump::encode, com.skycraft.skills.Acrobatics.DoubleJump::decode,
                com.skycraft.skills.Acrobatics.DoubleJump::handle);
        SkyNetwork.register(Timers.class, PacketFlow.CLIENTBOUND, Timers::encode, Timers::decode, Timers::handle);
    }

    /**
     * The player's active spell effects that are not vanilla mob effects (bound weapons, detect spells, lights, summons,
     * lingering areas), with the ticks left, for the HUD countdown strip. Sent when the set changes and once a second.
     */
    public record Timers(java.util.List<Entry> list) {
        /** {@code ticks} left of {@code total} (0 if unknown), shown in {@code rgb}. */
        public record Entry(net.minecraft.network.chat.Component name, int ticks, int total, int rgb) {}

        static void encode(Timers m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.list.size());
            for (Entry e : m.list) {
                net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.encode(buf, e.name);
                buf.writeVarInt(e.ticks);
                buf.writeVarInt(e.total);
                buf.writeInt(e.rgb);
            }
        }

        static Timers decode(RegistryFriendlyByteBuf buf) {
            int n = Math.min(32, buf.readVarInt());
            java.util.List<Entry> out = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(new Entry(net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt(), buf.readInt()));
            return new Timers(out);
        }

        static void handle(Timers m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.magic.client.ActiveEffectsHud.set(m.list));
        }
    }

    /** Spellmaking Altar: make the spell encoded in {@code id} (see {@link com.skycraft.magic.spell.spellmaking.CustomSpells}). */
    public record MakeSpell(net.minecraft.core.BlockPos pos, String id) {
        static void encode(MakeSpell m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeUtf(m.id, 256);
        }

        static MakeSpell decode(RegistryFriendlyByteBuf buf) {
            return new MakeSpell(buf.readBlockPos(), buf.readUtf(256));
        }

        static void handle(MakeSpell m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) com.skycraft.magic.spell.spellmaking.SpellmakingAltarBlock.make(player, m.pos, m.id);
        }
    }

    /** Staff Enchanter: bind a known spell into the unenchanted staff in the player's inventory. */
    public record StaffEnchant(net.minecraft.core.BlockPos pos, String spellId) {
        static void encode(StaffEnchant m, RegistryFriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeUtf(m.spellId, 256);
        }

        static StaffEnchant decode(RegistryFriendlyByteBuf buf) {
            return new StaffEnchant(buf.readBlockPos(), buf.readUtf(256));
        }

        static void handle(StaffEnchant m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) com.skycraft.arsenal.StaffEnchanting.enchant(player, m.pos, m.spellId);
        }
    }

    // ------------------------------------------------------------------ C2S

    /**
     * A hand's cast key went down ({@code start}) or up. {@code hand}: {@link SpellCasting#RIGHT} (R) or
     * {@link SpellCasting#LEFT} (use key with an empty off hand).
     */
    public record Cast(int hand, boolean start) {
        static void encode(Cast m, RegistryFriendlyByteBuf buf) {
            buf.writeByte(m.hand);
            buf.writeBoolean(m.start);
        }

        static Cast decode(RegistryFriendlyByteBuf buf) {
            return new Cast(buf.readByte(), buf.readBoolean());
        }

        static void handle(Cast m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null && (m.hand == SpellCasting.RIGHT || m.hand == SpellCasting.LEFT)) {
                // curse module: no spells in Beast Form or Vampire Lord form (they have their own)
                if (m.start) {
                    if (!com.skycraft.curse.Curses.transformed(player)) SpellCasting.start(player, m.hand);
                } else SpellCasting.stop(player, m.hand);
            }
        }
    }

    /** Shout key released after charging {@code words} (1..3) words. */
    public record ShoutPacket(int words) {
        static void encode(ShoutPacket m, RegistryFriendlyByteBuf buf) {
            buf.writeByte(m.words);
        }

        static ShoutPacket decode(RegistryFriendlyByteBuf buf) {
            return new ShoutPacket(buf.readByte());
        }

        static void handle(ShoutPacket m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) Shouting.executeVoice(player, Math.max(1, Math.min(3, m.words)));
        }
    }

    /** Magic menu actions. */
    public record MenuAction(int action, String id) {
        public static final int SELECT_SPELL = 0;
        public static final int SELECT_SHOUT = 1;
        public static final int UNLOCK_WORD = 2;
        public static final int TOGGLE_FAVORITE = 3;
        /** Equip a spell in the left hand ({@link #SELECT_SPELL} is the right hand). */
        public static final int SELECT_LEFT = 4;
        /** Equip a power to the Voice slot ({@link #SELECT_SHOUT} is for shouts). */
        public static final int SELECT_POWER = 5;
        /** Forget a spell made at a Spellmaking Altar. */
        public static final int FORGET_CUSTOM = 6;
        /** Hands: unequip the right hand (spell, else held item), unequip the left, go unarmed, toggle unarmed. */
        public static final int UNEQUIP_RIGHT = 7;
        public static final int UNEQUIP_LEFT = 8;
        public static final int UNARMED = 9;
        public static final int CYCLE_HANDS = 10;

        static void encode(MenuAction m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeUtf(m.id, 256);
        }

        static MenuAction decode(RegistryFriendlyByteBuf buf) {
            return new MenuAction(buf.readVarInt(), buf.readUtf(256));
        }

        static void handle(MenuAction m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) {
                switch (m.action) {
                    case SELECT_SPELL -> {
                        // Clicking the already-equipped right-hand spell unequips the right hand
                        if (Hands.right(player) == Hands.Held.SPELL && m.id.equals(MagicData.selectedSpell(player))) {
                            Hands.unequipRight(player);
                        } else {
                            Hands.equipRightSpell(player, m.id);
                        }
                    }
                    case SELECT_LEFT -> {
                        // Clicking the already-equipped left-hand spell unequips the left hand
                        if (Hands.left(player) == Hands.Held.SPELL && m.id.equals(MagicData.leftSpell(player))) {
                            Hands.unequipLeft(player);
                        } else {
                            Hands.equipLeftSpell(player, m.id);
                        }
                    }
                    case SELECT_SHOUT -> {
                        Shout shout = Shout.byId(m.id);
                        if (shout != null && (MagicData.wordsLearned(player, shout) > 0 || player.isCreative())) {
                            if (("shout:" + m.id).equals(MagicData.selectedVoice(player)) || m.id.equals(MagicData.selectedShout(player))) {
                                MagicData.setSelectedShout(player, "");
                                MagicData.setSelectedVoice(player, "");
                            } else {
                                MagicData.setSelectedShout(player, m.id);
                                MagicData.setSelectedVoice(player, "shout:" + m.id);
                            }
                        }
                    }
                    case SELECT_POWER -> {
                        if (MagicData.hasPower(player, m.id)) {
                            if (m.id.equals(MagicData.selectedVoice(player))) MagicData.setSelectedVoice(player, "");
                            else MagicData.setSelectedVoice(player, m.id);
                        }
                    }
                    case FORGET_CUSTOM -> com.skycraft.magic.spell.spellmaking.SpellmakingAltarBlock.forget(player, m.id);
                    case UNEQUIP_RIGHT -> Hands.unequipRight(player);
                    case UNEQUIP_LEFT -> Hands.unequipLeft(player);
                    case UNARMED -> Hands.goUnarmed(player);
                    case CYCLE_HANDS -> Hands.cycle(player);
                    case UNLOCK_WORD -> {
                        Shout shout = Shout.byId(m.id);
                        if (shout != null) Shouting.unlockWord(player, shout);
                    }
                    case TOGGLE_FAVORITE -> {
                        // Removing always works; adding only spells, shouts and powers the player actually has.
                        if (MagicData.favorites(player).contains(m.id) || MagicData.canFavorite(player, m.id)) {
                            MagicData.toggleFavorite(player, m.id);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ S2C

    /** A spell visual, see {@link MagicFx} for kinds. */
    public record Fx(int kind, int element, Vec3 a, Vec3 b, int entity, int extra) {
        static void encode(Fx m, RegistryFriendlyByteBuf buf) {
            buf.writeByte(m.kind);
            buf.writeByte(m.element);
            buf.writeFloat((float) m.a.x);
            buf.writeFloat((float) m.a.y);
            buf.writeFloat((float) m.a.z);
            buf.writeFloat((float) m.b.x);
            buf.writeFloat((float) m.b.y);
            buf.writeFloat((float) m.b.z);
            buf.writeVarInt(m.entity + 1);
            buf.writeVarInt(m.extra);
        }

        static Fx decode(RegistryFriendlyByteBuf buf) {
            int kind = buf.readByte();
            int element = buf.readByte();
            Vec3 a = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            Vec3 b = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            return new Fx(kind, element, a, b, buf.readVarInt() - 1, buf.readVarInt());
        }

        static void handle(Fx m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientFx.handle(m));
        }
    }

    /** Somebody shouted: show the words and the shout's particles. */
    public record ShoutFx(int entity, int shout, int words) {
        static void encode(ShoutFx m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeVarInt(m.shout);
            buf.writeByte(m.words);
        }

        static ShoutFx decode(RegistryFriendlyByteBuf buf) {
            return new ShoutFx(buf.readVarInt(), buf.readVarInt(), buf.readByte());
        }

        static void handle(ShoutFx m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> ClientFx.handleShout(m));
        }
    }
}
