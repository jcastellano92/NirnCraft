package com.skycraft.magic;

import com.skycraft.magic.client.ClientFx;
import com.skycraft.magic.shout.Shout;
import com.skycraft.magic.shout.Shouting;
import com.skycraft.magic.spell.SpellCasting;
import com.skycraft.magic.spell.Spells;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Packets of the magic module. Registered in this fixed order by {@link MagicModule#registerPackets()}. */
public final class MagicPackets {
    private MagicPackets() {}

    public static void register() {
        SkyNetwork.register(Cast.class, NetworkDirection.PLAY_TO_SERVER, Cast::encode, Cast::decode, Cast::handle);
        SkyNetwork.register(ShoutPacket.class, NetworkDirection.PLAY_TO_SERVER, ShoutPacket::encode, ShoutPacket::decode, ShoutPacket::handle);
        SkyNetwork.register(MenuAction.class, NetworkDirection.PLAY_TO_SERVER, MenuAction::encode, MenuAction::decode, MenuAction::handle);
        SkyNetwork.register(Fx.class, NetworkDirection.PLAY_TO_CLIENT, Fx::encode, Fx::decode, Fx::handle);
        SkyNetwork.register(ShoutFx.class, NetworkDirection.PLAY_TO_CLIENT, ShoutFx::encode, ShoutFx::decode, ShoutFx::handle);
        SkyNetwork.register(MakeSpell.class, NetworkDirection.PLAY_TO_SERVER, MakeSpell::encode, MakeSpell::decode, MakeSpell::handle);
        SkyNetwork.register(StaffEnchant.class, NetworkDirection.PLAY_TO_SERVER, StaffEnchant::encode, StaffEnchant::decode, StaffEnchant::handle);
        SkyNetwork.register(com.skycraft.skills.Acrobatics.DoubleJump.class, NetworkDirection.PLAY_TO_SERVER,
                com.skycraft.skills.Acrobatics.DoubleJump::encode, com.skycraft.skills.Acrobatics.DoubleJump::decode,
                com.skycraft.skills.Acrobatics.DoubleJump::handle);
    }

    /** Spellmaking Altar: make the spell encoded in {@code id} (see {@link com.skycraft.magic.spell.spellmaking.CustomSpells}). */
    public record MakeSpell(net.minecraft.core.BlockPos pos, String id) {
        static void encode(MakeSpell m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeUtf(m.id, 256);
        }

        static MakeSpell decode(FriendlyByteBuf buf) {
            return new MakeSpell(buf.readBlockPos(), buf.readUtf(256));
        }

        static void handle(MakeSpell m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) com.skycraft.magic.spell.spellmaking.SpellmakingAltarBlock.make(player, m.pos, m.id);
            ctx.get().setPacketHandled(true);
        }
    }

    /** Staff Enchanter: bind a known spell into the unenchanted staff in the player's inventory. */
    public record StaffEnchant(net.minecraft.core.BlockPos pos, String spellId) {
        static void encode(StaffEnchant m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeUtf(m.spellId, 256);
        }

        static StaffEnchant decode(FriendlyByteBuf buf) {
            return new StaffEnchant(buf.readBlockPos(), buf.readUtf(256));
        }

        static void handle(StaffEnchant m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) com.skycraft.arsenal.StaffEnchanting.enchant(player, m.pos, m.spellId);
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ C2S

    /**
     * A hand's cast key went down ({@code start}) or up. {@code hand}: {@link SpellCasting#RIGHT} (R) or
     * {@link SpellCasting#LEFT} (use key with an empty off hand).
     */
    public record Cast(int hand, boolean start) {
        static void encode(Cast m, FriendlyByteBuf buf) {
            buf.writeByte(m.hand);
            buf.writeBoolean(m.start);
        }

        static Cast decode(FriendlyByteBuf buf) {
            return new Cast(buf.readByte(), buf.readBoolean());
        }

        static void handle(Cast m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null && (m.hand == SpellCasting.RIGHT || m.hand == SpellCasting.LEFT)) {
                // curse module: no spells in Beast Form or Vampire Lord form (they have their own)
                if (m.start) {
                    if (!com.skycraft.curse.Curses.transformed(player)) SpellCasting.start(player, m.hand);
                } else SpellCasting.stop(player, m.hand);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /** Shout key released after charging {@code words} (1..3) words. */
    public record ShoutPacket(int words) {
        static void encode(ShoutPacket m, FriendlyByteBuf buf) {
            buf.writeByte(m.words);
        }

        static ShoutPacket decode(FriendlyByteBuf buf) {
            return new ShoutPacket(buf.readByte());
        }

        static void handle(ShoutPacket m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) Shouting.executeVoice(player, Math.max(1, Math.min(3, m.words)));
            ctx.get().setPacketHandled(true);
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

        static void encode(MenuAction m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.action);
            buf.writeUtf(m.id, 256);
        }

        static MenuAction decode(FriendlyByteBuf buf) {
            return new MenuAction(buf.readVarInt(), buf.readUtf(256));
        }

        static void handle(MenuAction m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                switch (m.action) {
                    case SELECT_SPELL, SELECT_LEFT -> {
                        if (Spells.byId(m.id) != null && (MagicData.knows(player, m.id) || player.isCreative())) {
                            if (m.action == SELECT_LEFT) {
                                SpellCasting.stop(player, SpellCasting.LEFT);
                                MagicData.setLeftSpell(player, m.id);
                            } else {
                                SpellCasting.stop(player, SpellCasting.RIGHT);
                                MagicData.setSelectedSpell(player, m.id);
                            }
                        }
                    }
                    case SELECT_SHOUT -> {
                        Shout shout = Shout.byId(m.id);
                        if (shout != null && (MagicData.wordsLearned(player, shout) > 0 || player.isCreative())) {
                            MagicData.setSelectedShout(player, m.id);
                            MagicData.setSelectedVoice(player, "shout:" + m.id);
                        }
                    }
                    case SELECT_POWER -> {
                        if (MagicData.hasPower(player, m.id)) MagicData.setSelectedVoice(player, m.id);
                    }
                    case FORGET_CUSTOM -> com.skycraft.magic.spell.spellmaking.SpellmakingAltarBlock.forget(player, m.id);
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
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ S2C

    /** A spell visual, see {@link MagicFx} for kinds. */
    public record Fx(int kind, int element, Vec3 a, Vec3 b, int entity, int extra) {
        static void encode(Fx m, FriendlyByteBuf buf) {
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

        static Fx decode(FriendlyByteBuf buf) {
            int kind = buf.readByte();
            int element = buf.readByte();
            Vec3 a = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            Vec3 b = new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
            return new Fx(kind, element, a, b, buf.readVarInt() - 1, buf.readVarInt());
        }

        static void handle(Fx m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientFx.handle(m));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Somebody shouted: show the words and the shout's particles. */
    public record ShoutFx(int entity, int shout, int words) {
        static void encode(ShoutFx m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entity);
            buf.writeVarInt(m.shout);
            buf.writeByte(m.words);
        }

        static ShoutFx decode(FriendlyByteBuf buf) {
            return new ShoutFx(buf.readVarInt(), buf.readVarInt(), buf.readByte());
        }

        static void handle(ShoutFx m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientFx.handleShout(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
