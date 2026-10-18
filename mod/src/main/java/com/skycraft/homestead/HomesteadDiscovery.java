package com.skycraft.homestead;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.NotifyKind;
import com.skycraft.network.SkyNetwork;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.world.LocationKind;
import com.skycraft.world.WorldData;
import com.skycraft.world.WorldPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Homesteads on the map. Each player's discovered-locations list (the world module's {@code discovered}: what the map,
 * the compass and fast travel read) carries an entry {@code homestead|<owner>} for every homestead they know of:
 * <ul>
 *   <li>the owner always has it: a discovered location with the name they chose (rename at the drafting table), on the
 *   map, the compass and the fast-travel list;</li>
 *   <li>a stranger who comes within {@code homesteadHintRange} blocks gets a <b>hidden</b> entry: a nameless point on
 *   the compass (like an undiscovered named place) and nothing on the map, nothing to travel to;</li>
 *   <li>approaching it (inside the grounds plus a small margin) discovers it: the same "DISCOVERED: name" banner and
 *   sting as any location, the name the owner set, and from then on it is on their map and fast-travel list;</li>
 *   <li>renames and removals reach everyone who knows it the next time they are checked.</li>
 * </ul>
 * Nothing here is persisted besides the player's own data; the claims live in {@link HomesteadData}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class HomesteadDiscovery {
    public static final String PREFIX = "homestead|";
    private static final int INTERVAL = 40;

    private HomesteadDiscovery() {}

    public static String id(UUID owner) {
        return PREFIX + owner;
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player) || player.isSpectator()) return;
        if ((player.tickCount + (player.getId() & 31) + 11) % INTERVAL != 0) return;
        try {
            sync(player);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Skycraft homestead discovery failed for {}", player.getName().getString(), e);
        }
    }

    /** Brings this player's homestead entries in line with the claims (adds, reveals, renames, removes). */
    public static void sync(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        HomesteadData claims = HomesteadData.get(server);
        PlayerData data = SkyData.get(player);
        ListTag list = WorldData.discovered(data);
        String dim = player.level().dimension().identifier().toString();
        int hint = SurvivalConfig.HOMESTEAD_HINT_RANGE.get();
        int margin = SurvivalConfig.HOMESTEAD_DISCOVER_MARGIN.get();
        boolean changed = false;
        Set<String> live = new HashSet<>();

        for (HomesteadClaim c : claims.allClaims()) {
            String id = id(c.getOwnerId());
            boolean mine = c.getOwnerId().equals(player.getUUID());
            CompoundTag loc = WorldData.find(data, id);
            long dx = c.getCenter().getX() - player.getBlockX(), dz = c.getCenter().getZ() - player.getBlockZ();
            long d2 = dx * dx + dz * dz;
            boolean sameDim = dim.equals(c.getDimension());
            long reveal = (long) (c.getRadius() + margin) * (c.getRadius() + margin);

            if (mine) {
                if (c.getOwnerName() != null && !c.getOwnerName().equals(player.getName().getString())) {
                    c.setOwnerName(player.getName().getString());
                    claims.setDirty();
                }
                live.add(id);
                if (loc == null) {
                    list.add(entry(c, false));
                    changed = true;
                } else {
                    changed |= refresh(loc, c, false);
                }
                continue;
            }
            if (loc == null) {
                if (!sameDim) continue;
                if (d2 <= reveal) {
                    list.add(entry(c, false));
                    live.add(id);
                    announce(player, data, c);
                    changed = true;
                } else if (d2 <= (long) hint * hint) {
                    list.add(entry(c, true));
                    live.add(id);
                    changed = true;
                }
                continue;
            }
            live.add(id);
            if (loc.getBooleanOr("hidden",false)) {
                if (sameDim && d2 <= reveal) {
                    loc.remove("hidden");
                    loc.remove("known");
                    refresh(loc, c, false);
                    announce(player, data, c);
                    changed = true;
                }
            } else {
                changed |= refresh(loc, c, false);
            }
        }

        // homesteads that are gone
        for (int i = list.size() - 1; i >= 0; i--) {
            String id = list.getCompoundOrEmpty(i).getStringOr("id","");
            if (id.startsWith(PREFIX) && !live.contains(id)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) data.markDirty();
    }

    /** Removes a dismantled homestead from the lists of everyone online (offline players are cleaned when they next tick). */
    public static void forget(MinecraftServer server, HomesteadClaim claim) {
        String id = id(claim.getOwnerId());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerData data = SkyData.get(p);
            ListTag list = WorldData.discovered(data);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.getCompoundOrEmpty(i).getStringOr("id","").equals(id)) {
                    list.remove(i);
                    data.markDirty();
                }
            }
        }
    }

    private static void announce(ServerPlayer player, PlayerData data, HomesteadClaim c) {
        data.addStat("locations_discovered", 1);
        Notifier.send(player, NotifyKind.LOCATION_DISCOVERED, Component.literal(c.plainName()), LocationKind.MANOR.displayName());
        SkyNetwork.sendToPlayer(player, new WorldPackets.Cue(WorldPackets.Cue.DISCOVERY));
        com.skycraft.explore.ExploreEvents.onDiscovered(player);
    }

    /** Where fast travel puts you: in front of the house door, or just behind the drafting table. */
    static BlockPos arrival(HomesteadClaim c) {
        if (c.getBuilt().contains("small_house")) return HomesteadBuilder.frame(c).at(0, 0, -1);
        return c.getCenter().relative(c.getFacing().getOpposite(), 2);
    }

    private static CompoundTag entry(HomesteadClaim c, boolean hidden) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id(c.getOwnerId()));
        t.putString("type", LocationKind.MANOR.id);
        t.putString("dim", c.getDimension());
        t.putString("homestead", c.getOwnerId().toString());
        if (hidden) {
            t.putBoolean("known", true);
            t.putBoolean("hidden", true);
        }
        place(t, c, hidden);
        return t;
    }

    /** Updates a discovered entry from the claim; returns true if anything changed. */
    private static boolean refresh(CompoundTag t, HomesteadClaim c, boolean hidden) {
        CompoundTag copy = t.copy();
        place(t, c, hidden);
        return !copy.equals(t);
    }

    private static void place(CompoundTag t, HomesteadClaim c, boolean hidden) {
        BlockPos at = c.getCenter();
        BlockPos in = arrival(c);
        t.putString("name", hidden ? "" : c.plainName());
        t.putInt("x", at.getX());
        t.putInt("y", at.getY());
        t.putInt("z", at.getZ());
        t.putInt("ax", in.getX());
        t.putInt("ay", at.getY());
        t.putInt("az", in.getZ());
    }
}
