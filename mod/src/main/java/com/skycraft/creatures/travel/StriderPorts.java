package com.skycraft.creatures.travel;

import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.Notifier;
import com.skycraft.creatures.Bestiary;
import com.skycraft.creatures.entity.SiltStriderEntity;
import com.skycraft.dialogue.Dialogue;
import com.skycraft.dialogue.DialogueOption;
import com.skycraft.network.SkyNetwork;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.vitals.Vitals;
import com.skycraft.world.FastTravelHandler;
import com.skycraft.world.RestManager;
import com.skycraft.world.WorldPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Silt strider travel between Vvardenfell's ports, Morrowind-style: a strider waits at each port (placed when a
 * player comes near and none is there), and talking to it offers the other ports for a fare. Travelling moves the
 * player (with followers and mount), passes the hours of the journey, and fades the screen like carriage travel.
 *
 * <p>Ports are the named places below when the world has them; Balmora, Vivec, Ald'ruhn and Ebonheart fall back to
 * their anchors from the world plan.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class StriderPorts {
    /** A port: place id and fallback anchor (null = only when the place exists). */
    private record Port(String id, Integer x, Integer z) {}

    private static final List<Port> PORTS = List.of(
            new Port("seyda_neen", null, null), new Port("balmora", 6300, 500), new Port("vivec", 7400, 1300),
            new Port("ald_ruhn", 6100, -500), new Port("ebonheart", 4700, 2400), new Port("gnisis", null, null),
            new Port("maar_gan", null, null), new Port("suran", null, null), new Port("molag_mar", null, null),
            new Port("khuul", null, null));
    private static final int FARE = 30;

    static {
        Dialogue.registerProvider(StriderPorts::options);
    }

    private StriderPorts() {}

    /** Called from module init so the dialogue provider is registered. */
    public static void init() {
        // the static initialiser registers the dialogue provider
    }

    /** The strider stand of a port: just outside the settlement, east side. Null when the port doesn't exist. */
    private static int[] stand(Port port) {
        Place place = Places.byId(port.id()).orElse(null);
        if (place != null) return new int[]{place.x() + place.radius() + 10, place.z()};
        if (port.x() == null) return null;
        return new int[]{port.x() + 70, port.z()};
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
        if ((level.getGameTime() + player.getId() * 11L) % 200 != 0 || level.dimension() != Level.OVERWORLD) return;
        for (Port port : PORTS) {
            int[] at = stand(port);
            if (at == null) continue;
            long dx = player.getBlockX() - at[0];
            long dz = player.getBlockZ() - at[1];
            if (dx * dx + dz * dz > 160L * 160L || !level.hasChunk(at[0] >> 4, at[1] >> 4)) continue;
            if (!level.getEntitiesOfClass(SiltStriderEntity.class, new AABB(at[0] - 48, level.getMinBuildHeight(), at[1] - 48,
                    at[0] + 48, level.getMaxBuildHeight(), at[1] + 48)).isEmpty()) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at[0], at[1]);
            SiltStriderEntity strider = Bestiary.SILT_STRIDER.get().create(level);
            if (strider == null) continue;
            BlockPos pos = new BlockPos(at[0], y, at[1]);
            strider.moveTo(pos.getX() + 0.5, y, pos.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
            strider.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            strider.setHome(pos, port.id());
            level.addFreshEntity(strider);
        }
    }

    public static void talk(ServerPlayer player, SiltStriderEntity strider) {
        Dialogue.open(player, strider, Component.translatable("dialogue.skycraft.silt_strider.greeting"));
    }

    private static void options(ServerPlayer player, LivingEntity npc, List<DialogueOption> out) {
        if (!(npc instanceof SiltStriderEntity strider)) return;
        int order = 150;
        for (Port port : PORTS) {
            if (port.id().equals(strider.port())) continue;
            int[] at = stand(port);
            if (at == null) continue;
            Component name = Places.byId(port.id()).map(Place::name)
                    .orElse(Component.translatable("place.skycraft." + port.id()));
            out.add(new DialogueOption("creatures.strider." + port.id(),
                    Component.translatable("dialogue.skycraft.silt_strider.destination", name, FARE), order++,
                    (p, n) -> travel(p, n, at, name)));
        }
    }

    private static void travel(ServerPlayer player, LivingEntity strider, int[] at, Component name) {
        if (Vitals.inCombat(player)) {
            Dialogue.open(player, strider, Component.translatable("world.skycraft.travel.combat"));
            return;
        }
        if (!Currency.take(player, FARE)) {
            Dialogue.open(player, strider, Component.translatable("dialogue.skycraft.silt_strider.no_gold", FARE));
            return;
        }
        Dialogue.endConversation(player, strider.getId());
        ServerLevel level = player.serverLevel();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at[0] - 4, at[1]);
        double tx = at[0] - 4 + 0.5;
        double tz = at[1] + 0.5;
        double distance = Math.sqrt(player.distanceToSqr(tx, y, tz));
        Entity vehicle = player.getVehicle();
        List<Mob> followers = new ArrayList<>(level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && FastTravelHandler.isFollower(m, player)));
        player.stopRiding();
        player.teleportTo(level, tx, y, tz, player.getYRot(), player.getXRot());
        player.resetFallDistance();
        if (vehicle != null && vehicle.isAlive()) {
            vehicle.teleportTo(tx, y, tz);
            player.startRiding(vehicle, true);
        }
        int i = 0;
        for (Mob mob : followers) {
            double a = i++ * (Math.PI * 2 / Math.max(1, followers.size()));
            mob.getNavigation().stop();
            mob.teleportTo(tx + Math.cos(a) * 1.5, y, tz + Math.sin(a) * 1.5);
        }
        int hours = 0;
        int perHour = com.skycraft.world.WorldConfig.BLOCKS_PER_TRAVEL_HOUR.get();
        long online = player.server.getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
        if (perHour > 0 && online <= 1) {
            hours = Mth.clamp((int) Math.round(distance / perHour), 1, 24);
            RestManager.advanceTime(player.server, hours * (long) RestManager.TICKS_PER_HOUR);
        }
        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(hours, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, Component.translatable("dialogue.skycraft.silt_strider.arrived", name));
    }
}
