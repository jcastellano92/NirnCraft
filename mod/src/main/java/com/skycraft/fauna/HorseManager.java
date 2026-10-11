package com.skycraft.fauna;

import com.skycraft.core.SkyData;
import com.skycraft.mounts.MountKind;
import com.skycraft.mounts.Mounts;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.party.Party;
import com.skycraft.quest.party.PartyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.player.Player;

/**
 * The horse API of the fauna module, now a facade over the mount system ({@link Mounts}): calling with H, ownership
 * (only the owner steers; same-faction or party members ride behind), death cooldowns. The old single-horse record
 * ({@link #HORSE_UUID} in {@code module("fauna")}) is migrated into {@code module("mounts")} on first use.
 */
public final class HorseManager {
    public static final String MODULE = "fauna";
    public static final String HORSE_UUID = "owned_horse_uuid";
    public static final String HORSE_BREED = "owned_horse_breed";
    public static final String HORSE_RESPAWN_COOLDOWN = "horse_respawn_ready";
    public static final long DEATH_COOLDOWN_TICKS = Mounts.DEATH_COOLDOWN_TICKS;

    private HorseManager() {}

    public static void setPlayerHorse(ServerPlayer player, Horse horse, HorseBreed breed) {
        MountKind kind = MountKind.forBreed(breed);
        if (kind != null) Mounts.adopt(player, horse, kind);
    }

    public static boolean hasOwnedHorse(Player player) {
        return Mounts.active(player) != null;
    }

    /** Called when player presses H (Call Horse). */
    public static void callHorse(ServerPlayer player) {
        Mounts.call(player);
    }

    public static void onHorseDeath(AbstractHorse horse) {
        Mounts.onDeath(horse);
    }

    /**
     * Checks if a player can mount or ride this horse.
     * Owner can always ride and steer.
     * Non-owner can only ride as a passenger (behind owner) if in same faction or party.
     * Nobody can mount an owned horse without permission.
     */
    public static boolean canMount(Player player, AbstractHorse horse) {
        if (player.isCreative()) return true;
        if (Mounts.ownerOf(horse) == null) return true;
        if (Mounts.isOwner(player, horse)) return true;
        Entity firstPassenger = horse.getFirstPassenger();
        return firstPassenger instanceof Player owner && isSameFactionOrParty(player, owner);
    }

    public static boolean isSameFactionOrParty(Player a, Player b) {
        // Party check
        if (a.getServer() != null) {
            PartyManager pm = PartyManager.get(a.getServer());
            Party pa = pm.partyOf(a.getUUID());
            if (pa != null && pa.has(b.getUUID())) return true;
        }

        // Faction check
        var dataA = SkyData.get(a);
        var dataB = SkyData.get(b);
        for (Faction f : Faction.VALUES) {
            if (Factions.isMember(dataA, f) && Factions.isMember(dataB, f)) {
                return true;
            }
        }

        return false;
    }
}
