package com.skycraft.guide;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.quest.NoticeBoards;
import com.skycraft.quest.QuestPackets;
import com.skycraft.quest.story.IntroFlow;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.world.Provinces;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Server-side events for the guide module:
 * <ul>
 *   <li>Gives each player one Traveler's Handbook the first time they join a world.</li>
 *   <li>Triggers the Region First-Entry Arrival Moment (MS19 — {@code F-ARRIVAL}, R3d-2.3) when a player enters any
 *   major {@link Region} for the first time: dispatches the 1-sentence evocative narrator card
 *   ({@code skycraft.guide.arrive.<region>}), queues the region's marquee questline rumour
 *   ({@code skycraft.guide.rumour.<region>}), marks the regional capital as known on the map, and syncs the arrival
 *   state to {@link com.skycraft.guide.client.GuideClient} for the region banner subtitle and musical sting.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class GuideEvents {
    public static final String MODULE = "guide";

    private GuideEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !GuideConfig.GIVE_HANDBOOK.get()) return;
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module(MODULE);
        if (tag.getBooleanOr("handbook_given",false)) return;
        tag.putBoolean("handbook_given", true);
        data.markDirty();
        ItemStack book = new ItemStack(GuideModule.HANDBOOK.get());
        // into the backpack (storage slots first), never the hand the player is holding: Inv.give is the pick-up path
        com.skycraft.inventory.Inv.giveOrDrop(player, book);
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if ((player.tickCount + player.getId()) % 20 != 0) return;
        if (player.tickCount < 60 || player.level().dimension() != Level.OVERWORLD) return;
        if (IntroFlow.inIntro(player)) return;

        Region region = Regions.at(player.level(), player.blockPosition());
        if (region == null || Provinces.isSea(region)) return;

        PlayerData data = SkyData.get(player);
        if (hasArrived(data, region.id())) return;

        triggerArrival(player, data, region);
    }

    /** Returns the short region key used in {@code skycraft.guide.arrive.<region>} and {@code skycraft.guide.rumour.<region>}. */
    public static String shortKey(String regionId) {
        int slash = regionId.indexOf('/');
        return slash >= 0 ? regionId.substring(slash + 1) : regionId;
    }

    public static boolean hasArrived(PlayerData data, String regionId) {
        ListTag list = data.module(MODULE).getListOrEmpty("arrived_regions");
        for (int i = 0; i < list.size(); i++) {
            if (list.getStringOr(i, "").equals(regionId)) return true;
        }
        return false;
    }

    /** Triggers the MS19 arrival moment for {@code region}: narrator card, rumour hook, capital map pin, and client banner sync. */
    public static void triggerArrival(ServerPlayer player, PlayerData data, Region region) {
        CompoundTag mod = data.module(MODULE);
        ListTag arrived = mod.getListOrEmpty("arrived_regions");
        boolean already = false;
        for (int i = 0; i < arrived.size(); i++) {
            if (arrived.getStringOr(i, "").equals(region.id())) {
                already = true;
                break;
            }
        }
        if (!already) arrived.add(StringTag.valueOf(region.id()));
        mod.put("arrived_regions", arrived);

        String key = shortKey(region.id());
        String arriveKey = "skycraft.guide.arrive." + key;
        String rumourKey = "skycraft.guide.rumour." + key;

        // Record arrival state for GuideClient (region banner subtitle + music sting)
        mod.putString("last_arrival_region", region.id());
        mod.putString("last_arrival_key", key);
        mod.putInt("last_arrival_seq", mod.getIntOr("last_arrival_seq",0) + 1);

        // Queue rumour entry in the player's guide data
        ListTag rumours = mod.getListOrEmpty("rumours");
        boolean hasRumour = false;
        for (int i = 0; i < rumours.size(); i++) {
            if (rumours.getStringOr(i, "").equals(rumourKey)) {
                hasRumour = true;
                break;
            }
        }
        if (!hasRumour) rumours.add(StringTag.valueOf(rumourKey));
        mod.put("rumours", rumours);

        // Mark the region's capital / marquee settlement as known on the player's world map
        if (region.capital() != null && !region.capital().isEmpty()) {
            Place cap = Places.byId(region.capital()).orElse(null);
            if (cap != null) NoticeBoards.markPlaceKnown(player, cap);
        }

        data.markDirty();

        // 1-sentence evocative Narrator Card (MS19); the rumour subtitle is shown on the arrival banner by GuideClient
        SkyNetwork.sendToPlayer(player, new QuestPackets.NarrationCard(Component.translatable(arriveKey), 140));
    }
}
