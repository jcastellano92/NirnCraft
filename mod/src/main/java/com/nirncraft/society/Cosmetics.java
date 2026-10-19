package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Notifier;
import com.nirncraft.network.SkyNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Server side of faction cosmetics. The chosen piece is stored in the player's persisted data
 * ({@code PlayerPersisted.nirncraft_cosmetic}, survives death) and sent to every client tracking the player.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Cosmetics {
    /** Forge copies this sub-compound of the persistent data across deaths. */
    private static final String PERSISTED = "PlayerPersisted";
    private static final String KEY = "nirncraft_cosmetic";

    private Cosmetics() {}

    /** The player's Forge-persisted data (kept across deaths), not synced. */
    static CompoundTag persistedTag(Player player) {
        CompoundTag root = player.getPersistentData();
        if (!com.nirncraft.platform.NbtData.hasType(root, PERSISTED, net.minecraft.nbt.Tag.TAG_COMPOUND)) root.put(PERSISTED, new CompoundTag());
        return root.getCompoundOrEmpty(PERSISTED);
    }

    public static String get(Player player) {
        return persistedTag(player).getStringOr(KEY, "");
    }

    private static SocietyPackets.CosmeticSync sync(Player player) {
        return new SocietyPackets.CosmeticSync(player.getUUID(), SocietyConfig.COSMETICS.get() ? get(player) : "");
    }

    /** Player's choice from the reputation screen ("" takes the regalia off). */
    public static void choose(ServerPlayer player, String id) {
        if (id.isEmpty()) {
            set(player, "");
            return;
        }
        Cosmetic c = Cosmetic.byId(id);
        if (c == null || !SocietyConfig.COSMETICS.get()) return;
        if (!Npcs.isMember(player, c.faction)) {
            Notifier.message(player, Component.translatable("society.nirncraft.cosmetic.not_member"));
            return;
        }
        set(player, c.id);
    }

    public static void set(ServerPlayer player, String id) {
        if (get(player).equals(id)) return;
        if (id.isEmpty()) persistedTag(player).remove(KEY);
        else persistedTag(player).putString(KEY, id);
        SkyNetwork.sendToTracking(player, sync(player));
    }

    /** Takes off regalia of a faction the player no longer belongs to. Called every few seconds. */
    static void validate(ServerPlayer player) {
        String id = get(player);
        if (id.isEmpty()) return;
        Cosmetic c = Cosmetic.byId(id);
        if (c == null || !Npcs.isMember(player, c.faction)) set(player, "");
    }

    // ------------------------------------------------------------------ syncing

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer viewer) {
            SkyNetwork.sendToPlayer(viewer, sync(target));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SkyNetwork.sendToPlayer(player, sync(player));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SkyNetwork.sendToPlayer(player, sync(player));
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) SkyNetwork.sendToPlayer(player, sync(player));
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        String id = get(event.getOriginal());
        if (!id.isEmpty()) persistedTag(event.getEntity()).putString(KEY, id);
    }
}
