package com.nirncraft.core;

import com.nirncraft.NirnCraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Persistent NeoForge attachment for RPG data; explicit packets preserve owner-only and appearance sync. */
public final class SkyData {
    public static final Identifier KEY = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "player_data");
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, NirnCraft.MODID);
    public static final Supplier<AttachmentType<PlayerData>> DATA = TYPES.register("player_data", () ->
            AttachmentType.builder(PlayerData::new)
                    .serialize(PlayerData.CODEC)
                    .copyHandler((old, holder, registries) -> {
                        PlayerData copy = new PlayerData();
                        copy.copyFrom(old);
                        return copy;
                    })
                    .build());

    private SkyData() {}

    public static void init(IEventBus modBus) {
        TYPES.register(modBus);
    }

    /** A null player receives an independent temporary instance, never shared between players or sides. */
    public static PlayerData get(Player player) {
        return player == null ? new PlayerData() : player.getData(DATA.get());
    }

    public static boolean has(Player player) {
        return player != null && player.hasData(DATA.get());
    }
}
