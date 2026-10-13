package com.skycraft.world;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import com.skycraft.registry.ModEffects;
import com.skycraft.vitals.Vitals;
import com.skycraft.world.client.WorldClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Skyrim's Wait / Sleep menu. Alone, time passes at once. With other players online it is a vote: each request is
 * remembered for {@code restVoteSeconds}; once all players (or the {@code playersSleepingPercentage} share) have a
 * pending request, the world advances by the smallest requested number of hours.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class RestManager {
    public static final int TICKS_PER_HOUR = 1000;

    private record Request(int hours, boolean sleep, BlockPos bed, int tick) {
    }

    private static final Map<UUID, Request> PENDING = new HashMap<>();

    private RestManager() {}

    // ------------------------------------------------------------------ bed interaction

    /** Right-clicking a bed opens the sleep menu at any time; sneaking falls back to vanilla. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBed(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        if (player.isShiftKeyDown() || player.isSpectator() || !WorldConfig.BED_MENU.get()) return;
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof BedBlock)) return;
        if (!BedBlock.canSetSpawn(level)) return; // Oblivion and Sovngarde: beds behave (explode) like vanilla

        BlockPos pos = event.getPos();
        if (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("block.minecraft.bed.occupied"));
            return;
        }
        if (com.skycraft.crime.Ownership.isOwnedByOther(player, level, pos)) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("world.skycraft.rest.bed_owned"));
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level.isClientSide && event.getHand() == InteractionHand.MAIN_HAND) {
            BlockPos bpos = event.getPos().immutable();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldClient.openRest(true, bpos));
        }
    }

    /** Ensure vanilla sleeping (e.g. shift-clicking a bed) also checks bed ownership and occupancy. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlayerSleepInBed(net.minecraftforge.event.entity.player.PlayerSleepInBedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlockPos pos = event.getPos();
            if (com.skycraft.crime.Ownership.isOwnedByOther(player, player.level(), pos)) {
                event.setResult(Player.BedSleepingProblem.OTHER_PROBLEM);
                Notifier.message(player, Component.translatable("world.skycraft.rest.bed_owned"));
            }
        }
    }

    // ------------------------------------------------------------------ requests

    public static void request(ServerPlayer player, int hours, boolean sleep, BlockPos bed) {
        hours = Mth.clamp(hours, 1, 24);
        Component error = validate(player, sleep, bed);
        if (error != null) {
            Notifier.message(player, error);
            return;
        }
        MinecraftServer server = player.server;
        BlockPos head = sleep ? bedHead(player.level(), bed) : null;
        PENDING.put(player.getUUID(), new Request(hours, sleep, head, server.getTickCount()));
        evaluate(server, player);
    }

    /** Returns why the player can't rest right now, or {@code null}. */
    public static Component validate(ServerPlayer player, boolean sleep, BlockPos bed) {
        String act = sleep ? "sleep" : "wait";
        if (Vitals.inCombat(player)) return Component.translatable("world.skycraft.rest.combat." + act);
        if (enemiesNear(player, 16)) return Component.translatable("world.skycraft.rest.enemies." + act);
        if (player.isInWater() || player.isInLava()) return Component.translatable("world.skycraft.rest.water." + act);
        if (player.fallDistance > 1f || player.isFallFlying()
                || (!player.onGround() && !player.isPassenger() && !player.getAbilities().flying)) {
            return Component.translatable("world.skycraft.rest.air." + act);
        }
        if (sleep) {
            if (bed == null || !(player.level().getBlockState(bed).getBlock() instanceof BedBlock)
                    || player.distanceToSqr(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5) > 6 * 6) {
                return Component.translatable("world.skycraft.rest.no_bed");
            }
            if (!BedBlock.canSetSpawn(player.level())) return Component.translatable("world.skycraft.rest.no_bed");
            BlockState state = player.level().getBlockState(bed);
            if (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED)) {
                return Component.translatable("block.minecraft.bed.occupied");
            }
            if (com.skycraft.crime.Ownership.isOwnedByOther(player, player.level(), bed)) {
                return Component.translatable("world.skycraft.rest.bed_owned");
            }
        }
        return null;
    }

    public static boolean enemiesNear(Player player, double radius) {
        return !player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(radius),
                m -> m instanceof Enemy && m.isAlive() && !m.isNoAi()).isEmpty();
    }

    private static BlockPos bedHead(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(BedBlock.FACING)).immutable();
        }
        return pos.immutable();
    }

    private static List<ServerPlayer> eligible(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!p.isSpectator()) out.add(p);
        return out;
    }

    private static void prune(MinecraftServer server) {
        int now = server.getTickCount();
        int maxAge = WorldConfig.REST_VOTE_SECONDS.get() * 20;
        PENDING.entrySet().removeIf(e -> now - e.getValue().tick > maxAge || server.getPlayerList().getPlayer(e.getKey()) == null);
    }

    /** Checks whether enough players want to rest; advances time if so. {@code trigger} is told the vote status. */
    private static void evaluate(MinecraftServer server, ServerPlayer trigger) {
        prune(server);
        if (PENDING.isEmpty()) return;
        List<ServerPlayer> players = eligible(server);
        int needed;
        if (players.size() <= 1) {
            needed = 1;
        } else {
            int pct = Mth.clamp(server.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE), 0, 100);
            needed = Math.max(1, Mth.ceil(players.size() * pct / 100f));
        }
        int have = 0;
        for (ServerPlayer p : players) if (PENDING.containsKey(p.getUUID())) have++;
        if (have >= needed) {
            execute(server, players);
            return;
        }
        if (trigger == null) return;
        Request req = PENDING.get(trigger.getUUID());
        Notifier.message(trigger, Component.translatable("world.skycraft.vote.waiting", needed - have));
        Component act = Component.translatable(req != null && req.sleep ? "world.skycraft.vote.sleep" : "world.skycraft.vote.wait");
        for (ServerPlayer p : players) {
            if (p == trigger || PENDING.containsKey(p.getUUID())) continue;
            Notifier.message(p, Component.translatable("world.skycraft.vote.wants", trigger.getDisplayName(), act,
                    req == null ? 1 : req.hours, Component.keybind("key.skycraft.wait")));
        }
    }

    private static void execute(MinecraftServer server, List<ServerPlayer> players) {
        int hours = 24;
        for (ServerPlayer p : players) {
            Request r = PENDING.get(p.getUUID());
            if (r != null) hours = Math.min(hours, r.hours);
        }
        advanceTime(server, hours * (long) TICKS_PER_HOUR);
        Map<UUID, Request> done = new HashMap<>(PENDING);
        PENDING.clear();
        for (ServerPlayer p : players) {
            Request r = done.get(p.getUUID());
            if (r != null) {
                applyRest(p, hours, r.sleep, r.bed);
                com.skycraft.survival.mode.SurvivalMode.onRest(p, hours, r.sleep, r.bed); // survival meters, Rested, Lover's Comfort
                SkyNetwork.sendToPlayer(p, new WorldPackets.RestFade(hours, r.sleep ? WorldPackets.RestFade.SLEEP : WorldPackets.RestFade.WAIT));
            } else {
                Notifier.message(p, Component.translatable("world.skycraft.vote.passed", hours));
            }
        }
    }

    /** Moves the world clock forward (the overworld's clock drives every dimension) and syncs it right away. */
    public static void advanceTime(MinecraftServer server, long ticks) {
        ServerLevel overworld = server.overworld();
        overworld.setDayTime(overworld.getDayTime() + ticks);
        for (ServerLevel level : server.getAllLevels()) {
            server.getPlayerList().broadcastAll(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)), level.dimension());
        }
    }

    /** Restores the resting player in proportion to the hours rested; sleeping also sets spawn and Well Rested. */
    private static void applyRest(ServerPlayer player, int hours, boolean sleep, BlockPos bed) {
        PlayerData data = SkyData.get(player);
        float f = sleep && hours >= 8 ? 1f : Math.min(1f, hours / 8f);
        player.heal(player.getMaxHealth() * f);
        data.setMagicka(data.getMagicka() + data.maxMagicka() * f);
        data.setStamina(data.getStamina() + data.maxStamina() * f);
        data.markVitalsDirty();
        if (!sleep) return;
        player.resetStat(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        if (bed != null && player.level().getBlockState(bed).getBlock() instanceof BedBlock) {
            boolean same = bed.equals(player.getRespawnPosition()) && player.level().dimension() == player.getRespawnDimension();
            // a home is a bed slept in and usable: not the intro cottage's provisional bed, not somebody else's (combat.RespawnPoints)
            if (!com.skycraft.combat.RespawnPoints.isProvisional(player, bed)
                    && !com.skycraft.crime.Ownership.isOwnedByOther(player, player.level(), bed)) {
                com.skycraft.combat.RespawnPoints.setOwned(player, player.level().dimension(), bed);
                if (!same) player.displayClientMessage(Component.translatable("block.minecraft.set_spawn"), true);
            }
        }
        int ticks = WorldConfig.WELL_RESTED_TICKS.get();
        if (hours >= 1 && ticks > 0) {
            player.addEffect(new MobEffectInstance(ModEffects.WELL_RESTED.get(), ticks, 0, false, false, true));
            Notifier.message(player, Component.translatable("world.skycraft.rest.well_rested"));
        }
    }

    // ------------------------------------------------------------------ housekeeping

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 20 != 0) return;
        // someone may have left, making the remaining requests sufficient
        evaluate(server, null);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }
}
