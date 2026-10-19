package com.nirncraft.world;

import com.nirncraft.society.NpcPlacement;
import com.nirncraft.society.Schedule;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.SpreadingSnowyBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the townsfolk consistent with a time skip ({@link RestManager}). While the clock races, the loaded townspeople
 * near the resting players take big steps along their real navigation path towards where their schedule says they
 * belong now (so they visibly walk to their stations, open the shops, go home and go to bed at accelerated speed);
 * when the skip ends or is cancelled, every loaded scheduled person who is not at the place the schedule gives for the
 * time that actually passed is put there ({@link NpcPlacement#snapToSchedule}: a standable cell beside the anchor, in
 * the bed for sleepers). Server side only, throttled by the caller.
 */
final class RestSim {
    /** Townspeople are only stepped within this many blocks of a resting player. */
    private static final double HOP_RADIUS = 64;
    /** Townspeople this far from a resting player (and loaded) are settled at the end. */
    private static final double SETTLE_RADIUS = 160;
    /** Nodes of the path moved per hop (big steps; a node is a walkable cell). */
    private static final int HOP_NODES = 14;

    private RestSim() {}

    /** A person who keeps a daily routine (same conditions as {@code NpcEntity#placeBySchedule}). */
    private static boolean scheduled(NpcEntity npc) {
        return npc.isAlive() && !npc.isRemoved() && com.nirncraft.society.SocietyConfig.ROUTINES.get() && npc.getHome() != null
                && !npc.isBusy() && npc.getTarget() == null && !npc.isPrisoner() && !npc.isConversing()
                && (npc.role().townsfolk() || com.nirncraft.society.Marriage.isSpouse(npc))
                && !npc.social().read("engaged", net.minecraft.core.UUIDUtil.CODEC).isPresent()
                && !npc.getPersistentData().contains("nirncraft_traveler");
    }

    /** The activity the schedule gives this person now, with the night rule of {@link NpcPlacement#snapToSchedule}. */
    @Nullable
    private static Schedule.Activity activityNow(NpcEntity npc) {
        Schedule.Activity a = Schedule.activity(npc);
        if (a == Schedule.Activity.IDLE) return null;
        if (com.nirncraft.society.Npcs.isNight(npc.level()) && NpcPlacement.outAtNight(npc) && a != Schedule.Activity.SLEEP) return null;
        return a;
    }

    /**
     * One accelerated step for up to {@code cap} loaded townspeople around {@code player}: whoever is away from the
     * anchor of the current activity hops up to {@link #HOP_NODES} nodes along the path to it.
     * Returns how many were handled (so the caller can share a budget between players).
     */
    static int hop(ServerPlayer player, int cap, int offset) {
        if (cap <= 0) return 0;
        ServerLevel level = player.level();
        AABB box = player.getBoundingBox().inflate(HOP_RADIUS, 32, HOP_RADIUS);
        List<NpcEntity> npcs = level.getEntitiesOfClass(NpcEntity.class, box, RestSim::scheduled);
        if (npcs.isEmpty()) return 0;
        int handled = 0;
        int n = npcs.size();
        for (int i = 0; i < n && handled < cap; i++) {
            NpcEntity npc = npcs.get(Math.floorMod(i + offset, n));
            Schedule.Activity a = activityNow(npc);
            if (a == null) continue;
            if (a != Schedule.Activity.SLEEP && npc.isSleeping()) npc.stopSleeping();
            if (npc.isSleeping()) continue;
            BlockPos anchor = NpcPlacement.anchorFor(npc, a);
            if (anchor == null || !level.isLoaded(anchor)) continue;
            npc.activity = a;
            double dx = anchor.getX() + 0.5 - npc.getX(), dz = anchor.getZ() + 0.5 - npc.getZ();
            if (dx * dx + dz * dz <= 3.0 * 3.0 && Math.abs(npc.getY() - anchor.getY()) < 4) continue;
            handled++;
            Path path = npc.getNavigation().createPath(anchor, 2);
            if (path == null || path.getNodeCount() < 2) continue;
            int idx = Math.min(path.getNodeCount() - 1, HOP_NODES);
            BlockPos step = path.getNode(idx).asBlockPos();
            if (!level.isLoaded(step)) continue;
            NpcPlacement.moveTo(npc, step);
        }
        return handled;
    }

    /**
     * The skip is over (or was cancelled): every loaded scheduled townsperson near the given players who is not
     * where the schedule puts them for the time that really passed is moved there. Returns how many were moved.
     */
    static int settle(List<ServerPlayer> players) {
        Set<UUID> seen = new HashSet<>();
        int moved = 0;
        for (ServerPlayer p : players) {
            ServerLevel level = p.level();
            AABB box = p.getBoundingBox().inflate(SETTLE_RADIUS, 96, SETTLE_RADIUS);
            for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, box, RestSim::scheduled)) {
                if (!seen.add(npc.getUUID())) continue;
                try {
                    Schedule.Activity a = activityNow(npc);
                    if (a == null) continue;
                    if (a != Schedule.Activity.SLEEP && npc.isSleeping()) npc.stopSleeping();
                    double before = npc.getX() * 31 + npc.getZ();
                    NpcPlacement.snapToSchedule(npc);
                    if (npc.getX() * 31 + npc.getZ() != before) moved++;
                } catch (RuntimeException ignored) {
                    // one odd NPC must not break the wake-up of the others
                }
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------ the rest of the world catches up

    /** Chunks around a resting player that are brought forward (their loaded, ticking neighbourhood). */
    private static final int CATCHUP_RADIUS = 3;
    private static final int GROWTH_CAP = 384;
    private static final int MACHINE_CAP = 400;

    /** Plants and farmland: random-ticking, growing blocks (not the spreading grass and dirt, not leaves). */
    private static boolean growing(BlockState s) {
        if (!s.isRandomlyTicking()) return false;
        Block b = s.getBlock();
        if (b instanceof SpreadingSnowyBlock) return false;
        return b instanceof BonemealableBlock || b instanceof FarmlandBlock || b instanceof CactusBlock || b instanceof SugarCaneBlock
                || b instanceof BambooStalkBlock || b instanceof BambooSaplingBlock;
    }

    /**
     * One skip tick that moved the clock {@code step} ticks: bounded extra random ticks for the plants around the
     * players (as many as the skipped ticks would have given, capped), and furnaces, brewing stands and campfires
     * advanced by the same ticks. Only already loaded chunks, never forcing loads.
     */
    static void catchUp(ServerLevel level, List<ServerPlayer> players, long step, int skipTick) {
        if (players.isEmpty() || step <= 1) return;
        Set<Long> chunks = new HashSet<>();
        for (ServerPlayer p : players) {
            ChunkPos c = p.chunkPosition();
            for (int dx = -CATCHUP_RADIUS; dx <= CATCHUP_RADIUS; dx++) {
                for (int dz = -CATCHUP_RADIUS; dz <= CATCHUP_RADIUS; dz++) chunks.add(ChunkPos.pack(c.x() + dx, c.z() + dz));
            }
        }
        int samples = RestLogic.growthSamples(step, level.getGameRules().get(GameRules.RANDOM_TICK_SPEED), GROWTH_CAP);
        int machineTicks = (int) Math.min(MACHINE_CAP, step * 2);
        for (long key : chunks) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            if (samples > 0) growth(level, chunk, samples);
            if (skipTick % 2 == 0) machines(level, chunk, machineTicks);
        }
    }

    private static void growth(ServerLevel level, LevelChunk chunk, int samples) {
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos cp = chunk.getPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.isRandomlyTickingBlocks() || !sec.getStates().maybeHas(RestSim::growing)) continue;
            int y0 = (chunk.getMinSectionY() + i) << 4;
            for (int n = 0; n < samples; n++) {
                int x = level.getRandom().nextInt(16), y = level.getRandom().nextInt(16), z = level.getRandom().nextInt(16);
                BlockState bs = sec.getBlockState(x, y, z);
                if (growing(bs)) bs.randomTick(level, new BlockPos(cp.getMinBlockX() + x, y0 + y, cp.getMinBlockZ() + z), level.getRandom());
            }
        }
    }

    /** Smelting, brewing and cooking go on for the skipped ticks (fuel burns, items finish). */
    private static void machines(ServerLevel level, LevelChunk chunk, int ticks) {
        for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
            if (be.isRemoved()) continue;
            if (be instanceof AbstractFurnaceBlockEntity f) {
                if (f.getItem(0).isEmpty() && !f.getBlockState().getOptionalValue(AbstractFurnaceBlock.LIT).orElse(false)) continue;
                for (int i = 0; i < ticks && !f.isRemoved(); i++) {
                    AbstractFurnaceBlockEntity.serverTick(level, f.getBlockPos(), f.getBlockState(), f);
                }
            } else if (be instanceof BrewingStandBlockEntity b) {
                if (b.getItem(3).isEmpty()) continue;
                for (int i = 0; i < ticks && !b.isRemoved(); i++) {
                    BrewingStandBlockEntity.serverTick(level, b.getBlockPos(), b.getBlockState(), b);
                }
            } else if (be instanceof CampfireBlockEntity c && c.getBlockState().getOptionalValue(CampfireBlock.LIT).orElse(false)) {
                var recipes = net.minecraft.world.item.crafting.RecipeManager.createCheck(net.minecraft.world.item.crafting.RecipeType.CAMPFIRE_COOKING);
                for (int i = 0; i < ticks && !c.isRemoved(); i++) {
                    CampfireBlockEntity.cookTick(level, c.getBlockPos(), c.getBlockState(), c, recipes);
                }
            }
        }
    }

    /** Baby animals grow and breeding cooldowns run down by the ticks skipped (every few skip ticks, accumulated). */
    static void animals(ServerLevel level, List<ServerPlayer> players, long ticks) {
        if (ticks <= 0) return;
        for (ServerPlayer p : players) {
            AABB box = p.getBoundingBox().inflate(CATCHUP_RADIUS * 16, 48, CATCHUP_RADIUS * 16);
            for (AgeableMob m : level.getEntitiesOfClass(AgeableMob.class, box, e -> e.isAlive() && e.getAge() != 0)) {
                m.setAge(RestLogic.ageAfter(m.getAge(), ticks));
            }
        }
    }

    /**
     * Status effects run down by the skipped ticks: the ones that are spent expire (no retroactive damage or
     * healing), the rest keep their remaining time. Effects the player gets after this call (Well Rested) are fresh.
     */
    static void shortenEffects(ServerPlayer player, long ticks) {
        if (ticks <= 0) return;
        for (MobEffectInstance e : new ArrayList<>(player.getActiveEffects())) {
            if (e.isInfiniteDuration()) continue;
            int left = RestLogic.effectLeft(e.getDuration(), ticks);
            player.removeEffect(com.nirncraft.platform.Effects.holder(e.getEffect()));
            if (left > 0) {
                player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(e.getEffect()), left, e.getAmplifier(), e.isAmbient(), e.isVisible(), e.showIcon()));
            }
        }
    }
}
