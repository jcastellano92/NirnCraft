package com.skycraft.guide;

import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Gives each player one Traveler's Handbook the first time they join a world. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class GuideEvents {
    public static final String MODULE = "guide";

    private GuideEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !GuideConfig.GIVE_HANDBOOK.get()) return;
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module(MODULE);
        if (tag.getBoolean("handbook_given")) return;
        tag.putBoolean("handbook_given", true);
        data.markDirty();
        ItemStack book = new ItemStack(GuideModule.HANDBOOK.get());
        // into the backpack, never the hand the player is holding (an empty main hand would otherwise take it)
        var inv = player.getInventory();
        for (int i = 9; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.setItem(i, book);
                return;
            }
        }
        if (!inv.add(book)) player.drop(book, false);
    }
}
