package com.skycraft.dungeons;

import com.skycraft.Skycraft;
import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.DungeonStructure;
import com.skycraft.economy.ItemValues;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Playtest telemetry ({@link DungeonsConfig#TELEMETRY}): one CSV row per player per dungeon visit in
 * {@code logs/skycraft-dungeons.csv}: when they went in, how long they stayed, how long the boss took, deaths, the
 * containers they opened and what was in them (gold value), and whether the boss died. A visit ends when the player
 * has been out of the dungeon for 30 seconds, logs out or the server stops.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class DungeonTelemetry {
    /** Persistent-data flag on a dungeon's boss ({@code Painter#spawnBoss}). */
    public static final String BOSS_TAG = "skycraft_dungeon_boss";
    private static final int LEAVE_TICKS = 600;
    private static final String HEADER = "logged_utc,player,dungeon,theme,place,x,z,entered_utc,seconds_inside,clear_seconds,deaths,"
            + "containers_opened,loot_value,boss_killed\n";

    private static final class Visit {
        String structure, theme, place;
        BoundingBox box;
        final List<BoundingBox> bossRooms = new ArrayList<>();
        long enterTick, lastSeen, clearTick = -1;
        Instant entered;
        int deaths, containers;
        long loot;
        boolean boss;
        final Set<Long> opened = new HashSet<>();
    }

    private static final Map<UUID, Visit> VISITS = new HashMap<>();

    private DungeonTelemetry() {}

    private static boolean on() {
        try {
            return DungeonsConfig.TELEMETRY.get();
        } catch (IllegalStateException e) {
            return false; // config not loaded yet
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0 || !on()) return;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        BlockPos pos = player.blockPosition();
        Visit v = VISITS.get(player.getUUID());
        if (v != null && v.box.inflatedBy(4).isInside(pos)) {
            v.lastSeen = now;
            return;
        }
        StructureStart start = dungeonAt(level, pos);
        if (start != null) {
            if (v != null) finish(player.getServer(), player, v);
            VISITS.put(player.getUUID(), begin(level, start, now));
        } else if (v != null && now - v.lastSeen > LEAVE_TICKS) {
            finish(player.getServer(), player, v);
            VISITS.remove(player.getUUID());
        }
    }

    /** The underground dungeon (or fort) with a piece at this position, if any. */
    private static StructureStart dungeonAt(ServerLevel level, BlockPos pos) {
        for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : level.structureManager().getAllStructuresAt(pos).entrySet()) {
            if (!(e.getKey() instanceof DungeonStructure ds) || !ds.theme.underground()) continue;
            StructureStart start = level.structureManager().getStructureWithPieceAt(pos, ds);
            if (start.isValid()) return start;
        }
        return null;
    }

    private static Visit begin(ServerLevel level, StructureStart start, long now) {
        Visit v = new Visit();
        DungeonStructure ds = (DungeonStructure) start.getStructure();
        ResourceLocation id = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(ds);
        v.structure = id == null ? "?" : id.toString();
        v.theme = ds.theme.id;
        v.box = start.getBoundingBox();
        for (StructurePiece sp : start.getPieces()) {
            if (sp instanceof DungeonPiece dp && dp.role == DungeonPiece.ROLE_BOSS && dp.kind == DungeonPiece.Kind.ROOM) v.bossRooms.add(dp.getBoundingBox());
        }
        v.place = "";
        for (Place p : Places.inChunk(start.getChunkPos())) v.place = p.id();
        v.enterTick = now;
        v.lastSeen = now;
        v.entered = Instant.now();
        return v;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!on() || !(event.getEntity().level() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            Visit v = VISITS.get(player.getUUID());
            if (v != null) v.deaths++;
            return;
        }
        if (!(event.getEntity() instanceof Mob mob) || !mob.getPersistentData().getBoolean(BOSS_TAG)) return;
        BlockPos pos = mob.blockPosition();
        for (Visit v : VISITS.values()) {
            if (v.boss || !v.box.inflatedBy(4).isInside(pos)) continue;
            v.boss = true;
            v.clearTick = level.getGameTime();
        }
    }

    @SubscribeEvent
    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!on() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Visit v = VISITS.get(player.getUUID());
        if (v == null || !(event.getContainer() instanceof ChestMenu menu)) return;
        Container c = menu.getContainer();
        if (!(c instanceof net.minecraft.world.level.block.entity.BlockEntity be)) return;
        if (!v.box.isInside(be.getBlockPos()) || !v.opened.add(be.getBlockPos().asLong())) return;
        v.containers++;
        for (int i = 0; i < c.getContainerSize(); i++) v.loot += ItemValues.getStack(c.getItem(i));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Visit v = VISITS.remove(player.getUUID());
            if (v != null) finish(player.getServer(), player, v);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            Visit v = VISITS.remove(p.getUUID());
            if (v != null) finish(event.getServer(), p, v);
        }
        VISITS.clear();
    }

    private static void finish(MinecraftServer server, ServerPlayer player, Visit v) {
        if (server == null) return;
        long ticks = Math.max(0, v.lastSeen - v.enterTick);
        String row = String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%d,%d,%s,%d,%s,%d,%d,%d,%s%n", Instant.now(), player.getGameProfile().getName(),
                v.structure, v.theme, v.place, v.box.getCenter().getX(), v.box.getCenter().getZ(), v.entered, ticks / 20,
                v.clearTick >= 0 ? String.valueOf((v.clearTick - v.enterTick) / 20) : "", v.deaths, v.containers, v.loot, v.boss);
        Path file = server.getServerDirectory().toPath().resolve("logs").resolve("skycraft-dungeons.csv");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) Files.writeString(file, HEADER, StandardCharsets.UTF_8);
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Skycraft.LOGGER.warn("Skycraft dungeons: could not write telemetry", e);
        }
    }
}
