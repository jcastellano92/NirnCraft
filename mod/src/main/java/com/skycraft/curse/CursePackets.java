package com.skycraft.curse;

import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.network.SkyNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import com.skycraft.platform.Sides;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;


/**
 * Network messages of the curse module. S2C: {@link Sync} (form, vampire stage and flags of a player, to everyone
 * tracking them) and {@link Anim} (a one-shot animation on a transformed player). C2S: {@link Action} (Drain Life,
 * night spells, feeding, bites), {@link Select} (a form ability) and {@link Unlock} (a curse perk). Every C2S message
 * is validated on the server (form, distance, cooldowns, perk points).
 */
public final class CursePackets {
    private CursePackets() {}

    static void register() {
        SkyNetwork.register(Sync.class, PacketFlow.CLIENTBOUND, Sync::encode, Sync::decode, Sync::handle);
        SkyNetwork.register(Anim.class, PacketFlow.CLIENTBOUND, Anim::encode, Anim::decode, Anim::handle);
        SkyNetwork.register(Action.class, PacketFlow.SERVERBOUND, Action::encode, Action::decode, Action::handle);
        SkyNetwork.register(Select.class, PacketFlow.SERVERBOUND, Select::encode, Select::decode, Select::handle);
        SkyNetwork.register(Unlock.class, PacketFlow.SERVERBOUND, Unlock::encode, Unlock::decode, Unlock::handle);
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
            if (t.getBooleanOr(Curses.WEREWOLF, false)) flags |= WEREWOLF;
            if (Vampirism.inSun(p)) flags |= IN_SUN;
            if (t.getBooleanOr(Curses.LORD, false)) flags |= LORD;
            if (t.getBooleanOr(Curses.SIGHT, false)) flags |= SIGHT;
            if (t.getLongOr(Curses.SANGUINARE, 0L) > 0) flags |= SANGUINARE;
            return new Sync(p.getId(), (byte) Curses.form(p).ordinal(), (byte) Curses.stage(p), (byte) flags);
        }

        static void encode(Sync m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeByte(m.form);
            buf.writeByte(m.stage);
            buf.writeByte(m.flags);
        }

        static Sync decode(RegistryFriendlyByteBuf buf) {
            return new Sync(buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readByte());
        }

        static void handle(Sync m, IPayloadContext ctx) {
            Curses.ClientView.put(m.entityId, m.form, m.stage, m.flags);
            Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.curse.client.CurseClient.onSync(m.entityId, m.form));
        }
    }

    public record Anim(int entityId, byte anim) {
        static void encode(Anim m, RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(m.entityId);
            buf.writeByte(m.anim);
        }

        static Anim decode(RegistryFriendlyByteBuf buf) {
            return new Anim(buf.readVarInt(), buf.readByte());
        }

        static void handle(Anim m, IPayloadContext ctx) {
            Sides.runOn(Dist.CLIENT, () -> () -> com.skycraft.curse.client.CurseClient.onAnim(m.entityId, m.anim));
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

        static void encode(Action m, RegistryFriendlyByteBuf buf) {
            buf.writeByte(m.action);
            buf.writeVarInt(m.target);
        }

        static Action decode(RegistryFriendlyByteBuf buf) {
            return new Action(buf.readByte(), buf.readVarInt());
        }

        static void handle(Action m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
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
        }
    }

    public record Select(String id) {
        static void encode(Select m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.id, 64);
        }

        static Select decode(RegistryFriendlyByteBuf buf) {
            return new Select(buf.readUtf(64));
        }

        static void handle(Select m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) CursePowers.select(player, m.id);
        }
    }

    public record Unlock(String id) {
        static void encode(Unlock m, RegistryFriendlyByteBuf buf) {
            buf.writeUtf(m.id, 64);
        }

        static Unlock decode(RegistryFriendlyByteBuf buf) {
            return new Unlock(buf.readUtf(64));
        }

        static void handle(Unlock m, IPayloadContext ctx) {
            ServerPlayer player = SkyNetwork.sender(ctx);
            if (player != null) CursePerks.unlock(player, m.id);
        }
    }
}
