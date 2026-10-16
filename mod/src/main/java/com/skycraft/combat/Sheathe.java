package com.skycraft.combat;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Contract 8 (docs/PLAYTEST-1.md): whether a player's weapon is sheathed. Synced, so it works on both sides.
 * Guards treat a sheathed or empty-handed player as yielding.
 */
public final class Sheathe {
    private Sheathe() {}

    public static boolean isSheathed(Player player) {
        if (player == null) return true;
        return SkyData.get(player).module("combat").getBoolean("sheathed");
    }

    /** Changes the state; the server sends it to the owner and every tracking player (appearance sync). */
    public static void setSheathed(Player player, boolean sheathed) {
        if (player == null) return;
        if (sheathed) {
            player.stopUsingItem();
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.skycraft.magic.spell.SpellCasting.stop(sp);
                CombatHandler.setBlocking(sp, false);
            }
        }
        PlayerData data = SkyData.get(player);
        if (data.module("combat").getBoolean("sheathed") == sheathed) return;
        data.module("combat").putBoolean("sheathed", sheathed);
        data.markAppearanceDirty();
    }

    public static void toggle(Player player) {
        boolean now = !isSheathed(player);
        setSheathed(player, now);
        if (!player.level().isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            Notifier.message(sp, Component.translatable(now ? "message.skycraft.sheathed" : "message.skycraft.drawn"));
            player.level().playSound(null, player.blockPosition(),
                    now ? SoundEvents.ARMOR_EQUIP_LEATHER : SoundEvents.ARMOR_EQUIP_IRON,
                    SoundSource.PLAYERS, 0.8f, 1.2f);
        }
    }
}
