package com.skycraft.curse;

import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Network messages of the curse module. S2C: {@link Sync} (form, vampire stage and flags of a player, to everyone
 * tracking them) and {@link Anim} (a one-shot animation on a transformed player). C2S: {@link Action} (Drain Life,
 * night spells, feeding, bites), {@link Select} (a form ability) and {@link Unlock} (a curse perk). Every C2S message
 * is validated on the server (form, distance, cooldowns, perk points).
 */
public final class CursePackets {
    private CursePackets() {}

    static void register() {
        SkyNetwork.register(Sync.class, NetworkDirection.PLAY_TO_CLIENT, Sync::encode, Sync::decode, Sync::handle);
        SkyNetwork.register(Anim.class, NetworkDirection.PLAY_TO_CLIENT, Anim::encode, Anim::decode, Anim::handle);
        SkyNetwork.register(Action.class, NetworkDirection.PLAY_TO_SERVER, Action::encode, Action::decode, Action::handle);
        SkyNetwork.register(Select.class, NetworkDirection.PLAY_TO_SERVER, Select::encode, Select::decode, Select::handle);
        SkyNetwork.register(Unlock.class, NetworkDirection.PLAY_TO_SERVER, Unlock::encode, Unlock::decode, Unlock::handle);
    }

    // ------------------------------------------------------------------ helpers (server)

    /** Tells the player and everyone tracking them about the player's form and stage. */
    public static void sync(ServerPlayer player) {
        SkyNetwork.sendToTracking(player, Sync.of(player));
    }

    /** Sends one player's state to one viewer (start tracking, login). */
    static void syncTo(ServerPlayer viewer, ServerPlayer target) {
        SkyNetwork.sendToPlayer(viewer, Sync.of(target));
    }

    static void anim(ServerPlayer player, CurseAnims.Anim anim) {
        SkyNetwork.sendToTracking(player, new Anim(player.getId(), (byte) anim.ordinal()));
    }

    // ------------------------------------------------------------------ S2C

    public record Sync(int entityId, byte form, byte stage, byte flags) {
        public static final int WEREWOLF = 1;
        public static final int IN_SUN = 2;
        public static final int LORD = 4;
        public static final int SIGHT = 8;
        public static final int SANGUINARE = 16;

        static Sync of(ServerPlayer p) {
            var t = Curses.tag(p);
            int flags = 0;
            if (t.getBoolean(Curses.WEREWOLF)) flags |= WEREWOLF;
            if (Vampirism.inSun(p)) flags |= IN_SUN;
            if (t.getBoolean(Curses.LORD)) flags |= LORD;
            if (t.getBoolean(Curses.SIGHT)) flags |= SIGHT;
            if (t.getLong(Curses.SANGUINARE) > 0) flags |= SANGUINARE;
            return new Sync(p.getId(), (byte) Curses.form(p).ordinal(), (byte) Curses.stage(p), (byte) flags);
        }

        static void encode(Sync m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeByte(m.form);
            buf.writeByte(m.stage);
            buf.writeByte(m.flags);
        }

        static Sync decode(FriendlyByteBuf buf) {
            return new Sync(buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readByte());
        }

        static void handle(Sync m, Supplier<NetworkEvent.Context> ctx) {
            Curses.ClientView.put(m.entityId, m.form, m.stage, m.flags);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.curse.client.CurseClient.onSync(m.entityId, m.form));
            ctx.get().setPacketHandled(true);
        }
    }

    public record Anim(int entityId, byte anim) {
        static void encode(Anim m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeByte(m.anim);
        }

        static Anim decode(FriendlyByteBuf buf) {
            return new Anim(buf.readVarInt(), buf.readByte());
        }

        static void handle(Anim m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.curse.client.CurseClient.onAnim(m.entityId, m.anim));
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ C2S

    public record Action(byte action, int target) {
        /** Drain Life (Vampire Lord, levitating, attack key held). */
        public static final byte PRIMARY = 0;
        /** The chosen night spell (Vampire Lord, levitating, use key). */
        public static final byte SECONDARY = 1;
        /** Feed on the target: a sleeping person (vampire), a corpse (Beast Form) or a bite (Vampire Lord). */
        public static final byte FEED = 2;
        /** Back to mortal form. */
        public static final byte REVERT = 3;

        public static Action of(byte action) {
            return new Action(action, -1);
        }

        static void encode(Action m, FriendlyByteBuf buf) {
            buf.writeByte(m.action);
            buf.writeVarInt(m.target);
        }

        static Action decode(FriendlyByteBuf buf) {
            return new Action(buf.readByte(), buf.readVarInt());
        }

        static void handle(Action m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null && player.isAlive() && !player.isSpectator()) {
                switch (m.action) {
                    case PRIMARY -> {
                        if (CurseEvents.throttle(player, "primary", 8)) VampireLord.drainLife(player);
                    }
                    case SECONDARY -> {
                        if (CurseEvents.throttle(player, "secondary", 15)) VampireLord.castSpell(player);
                    }
                    case FEED -> {
                        Entity e = player.level().getEntity(m.target);
                        if (e != null && player.distanceToSqr(e) < 6 * 6 && CurseEvents.throttle(player, "feed", 10)) {
                            if (e instanceof CorpseEntity corpse) Lycanthropy.feed(player, corpse);
                            else if (e instanceof LivingEntity living) {
                                if (Curses.form(player) == Form.LORD) VampireLord.bite(player, living);
                                else Vampirism.feedOnSleeper(player, living);
                            }
                        }
                    }
                    case REVERT -> {
                        if (Curses.form(player) == Form.BEAST) Lycanthropy.revert(player, true);
                        else if (Curses.form(player) == Form.LORD) VampireLord.revert(player, true);
                    }
                    default -> {
                    }
                }
            }
            ctx.get().setPacketHandled(true);
        }
    }

    public record Select(String id) {
        static void encode(Select m, FriendlyByteBuf buf) {
            buf.writeUtf(m.id, 64);
        }

        static Select decode(FriendlyByteBuf buf) {
            return new Select(buf.readUtf(64));
        }

        static void handle(Select m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) CursePowers.select(player, m.id);
            ctx.get().setPacketHandled(true);
        }
    }

    public record Unlock(String id) {
        static void encode(Unlock m, FriendlyByteBuf buf) {
            buf.writeUtf(m.id, 64);
        }

        static Unlock decode(FriendlyByteBuf buf) {
            return new Unlock(buf.readUtf(64));
        }

        static void handle(Unlock m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) CursePerks.unlock(player, m.id);
            ctx.get().setPacketHandled(true);
        }
    }
}
