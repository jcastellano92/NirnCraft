package com.skycraft.survival;

import com.skycraft.network.SkyNetwork;
import com.skycraft.survival.cooking.Cooking;
import com.skycraft.survival.cooking.CookingMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Survival packets (registered in this order). */
public final class SurvivalPackets {
    private SurvivalPackets() {}

    public static void register() {
        SkyNetwork.register(Cook.class, NetworkDirection.PLAY_TO_SERVER, Cook::encode, Cook::decode, Cook::handle);
        SkyNetwork.register(Meters.class, NetworkDirection.PLAY_TO_CLIENT, Meters::encode, Meters::decode, Meters::handle);
        SkyNetwork.register(Bite.class, NetworkDirection.PLAY_TO_CLIENT, Bite::encode, Bite::decode, Bite::handle);
    }

    /** C2S: cook {@code times} of a cooking pot recipe at the open cooking pot. */
    public record Cook(String recipeId, int times) {
        static void encode(Cook m, FriendlyByteBuf buf) {
            buf.writeUtf(m.recipeId, 128);
            buf.writeVarInt(m.times);
        }

        static Cook decode(FriendlyByteBuf buf) {
            return new Cook(buf.readUtf(128), buf.readVarInt());
        }

        static void handle(Cook m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            if (player != null && player.containerMenu instanceof CookingMenu menu && menu.stillValid(player)) {
                Cooking.cook(player, menu, m.recipeId, m.times);
            }
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * S2C: the Survival Mode meters for the HUD ({@code survival/mode/SurvivalMode}); sent when they change.
     *
     * @param trend cold meter: 0 steady, 1 getting colder, 2 warming up
     */
    public record Meters(short hunger, short fatigue, short cold, short warmth, byte flags, byte trend) {
        public static final int ACTIVE = 1, HEAT = 2, WET = 4, WELL_FED = 8;

        static void encode(Meters m, FriendlyByteBuf buf) {
            buf.writeShort(m.hunger);
            buf.writeShort(m.fatigue);
            buf.writeShort(m.cold);
            buf.writeShort(m.warmth);
            buf.writeByte(m.flags);
            buf.writeByte(m.trend);
        }

        static Meters decode(FriendlyByteBuf buf) {
            return new Meters(buf.readShort(), buf.readShort(), buf.readShort(), buf.readShort(), buf.readByte(), buf.readByte());
        }

        static void handle(Meters m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.survival.client.ClientSurvival.receive(m));
            ctx.get().setPacketHandled(true);
        }

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    /**
     * S2C: the fishing minigame ({@code survival/fishing}). {@code phase} 1 = a fish took the bait: a marker sweeps over
     * the reel bar ({@code periodTicks} per pass, the catch zone from {@code zoneStart} to {@code zoneEnd} in 0..1000,
     * {@code windowTicks} until it gets away); 0 = the bite is over ({@code result}: 0 gone, 1 caught, 2 too early,
     * 3 too late).
     */
    public record Bite(byte phase, short zoneStart, short zoneEnd, short periodTicks, short windowTicks, byte result, String fish) {
        static void encode(Bite m, FriendlyByteBuf buf) {
            buf.writeByte(m.phase);
            buf.writeShort(m.zoneStart);
            buf.writeShort(m.zoneEnd);
            buf.writeShort(m.periodTicks);
            buf.writeShort(m.windowTicks);
            buf.writeByte(m.result);
            buf.writeUtf(m.fish, 64);
        }

        static Bite decode(FriendlyByteBuf buf) {
            return new Bite(buf.readByte(), buf.readShort(), buf.readShort(), buf.readShort(), buf.readShort(), buf.readByte(), buf.readUtf(64));
        }

        static void handle(Bite m, Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.skycraft.survival.client.FishingHud.receive(m));
            ctx.get().setPacketHandled(true);
        }
    }
}
