package com.nirncraft.world;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server side of fast travel from the world map. */
public final class FastTravelHandler {
    public static final Identifier JAIL = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "jail");
    private static final Map<Class<?>, Optional<Method>> OWNER_METHODS = new ConcurrentHashMap<>();

    private FastTravelHandler() {}

    public static void travel(ServerPlayer player, String id) {
        travel(player, id, false);
    }

    private static void travel(ServerPlayer player, String id, boolean prewarmed) {
        if (!WorldConfig.FAST_TRAVEL.get()) {
            fail(player, "disabled");
            return;
        }
        if (com.nirncraft.survival.mode.SurvivalMode.blocksFastTravel(player)) {
            fail(player, "survival");
            return;
        }
        if (com.nirncraft.inventory.CarryWeight.isOverencumbered(player)) {
            fail(player, "encumbered");
            return;
        }
        CompoundTag loc = WorldData.find(SkyData.get(player), id);
        if (loc == null || loc.getBooleanOr("known", false)) {
            fail(player, "unknown");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (level.dimension().identifier().equals(JAIL)
                || com.nirncraft.crime.Jail.isJailed(player)
                || com.nirncraft.crime.Jail.isLoadingJail(player)) {
            fail(player, "jail");
            return;
        }
        if (!loc.getStringOr("dim", "").equals(level.dimension().identifier().toString())) {
            fail(player, "dimension");
            return;
        }
        if (Vitals.inCombat(player)) {
            fail(player, "combat");
            return;
        }
        if (RestManager.enemiesNear(player, 16)) {
            fail(player, "enemies");
            return;
        }
        if (player.isPassenger()) {
            fail(player, "riding");
            return;
        }
        boolean supported = player.onGround()
                || !player.level().noCollision(player, player.getBoundingBox().expandTowards(0, -0.35, 0));
        if (player.fallDistance > 1f || player.isFallFlying()
                || (!supported && !player.getAbilities().flying && !player.isInWater())) {
            fail(player, "falling");
            return;
        }

        LocationKind kind = LocationKind.byId(loc.getStringOr("type", ""));
        BlockPos arrival = new BlockPos(loc.getIntOr("ax", 0), loc.getIntOr("ay", 0), loc.getIntOr("az", 0));
        if (!prewarmed && !com.nirncraft.perf.Prewarm.isReady(level, arrival.getX(), arrival.getZ())) {
            // load/generate the destination first (progress bar on the client), then run the whole request again
            if (!com.nirncraft.perf.Prewarm.start(player, arrival.getX(), arrival.getZ(), WorldData.displayName(loc),
                    () -> travel(player, id, true))) {
                fail(player, "blocked");
            }
            return;
        }
        BlockPos target = findArrival(level, arrival, kind);
        if (target == null) {
            fail(player, "blocked");
            return;
        }

        List<Mob> followers = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                m -> m.isAlive() && !m.isPassenger() && isFollower(m, player));
        double distance = Math.sqrt(player.blockPosition().distSqr(target));

        player.stopRiding();
        player.teleportTo(level, target.getX() + 0.5, target.getY(), target.getZ() + 0.5, java.util.Set.of(),  player.getYRot(), player.getXRot(), true);
        player.resetFallDistance();
        int i = 0;
        for (Mob mob : followers) {
            double angle = i++ * (Math.PI * 2 / Math.max(1, followers.size()));
            double fx = target.getX() + 0.5 + Math.cos(angle) * 1.5;
            double fz = target.getZ() + 0.5 + Math.sin(angle) * 1.5;
            mob.getNavigation().stop();
            mob.teleportTo(fx, target.getY(), fz);
            mob.resetFallDistance();
        }

        // Travel takes time (also with others online, unless the server turned travelTimeMultiplayer off).
        int hours = 0;
        int perHour = WorldConfig.BLOCKS_PER_TRAVEL_HOUR.get();
        long online = player.level().getServer().getPlayerList().getPlayers().stream().filter(p -> !p.isSpectator()).count();
        if (perHour > 0 && (online <= 1 || WorldConfig.TRAVEL_TIME_MULTIPLAYER.get())) {
            hours = Mth.clamp((int) Math.round(distance / perHour), 1, 72);
            RestManager.advanceTime(player.level().getServer(), hours * (long) RestManager.TICKS_PER_HOUR);
        }
        SkyNetwork.sendToPlayer(player, new WorldPackets.RestFade(hours, WorldPackets.RestFade.TRAVEL));
        Notifier.message(player, hours > 0
                ? Component.translatable("world.nirncraft.travel.arrived_hours", WorldData.displayName(loc), hours)
                : Component.translatable("world.nirncraft.travel.arrived", WorldData.displayName(loc)));
    }

    private static void fail(ServerPlayer player, String reason) {
        Notifier.message(player, Component.translatable("world.nirncraft.travel." + reason));
    }

    // ------------------------------------------------------------------ followers

    /** Tamed pets that aren't told to sit, leashed animals, horses and modded followers (any {@code getOwnerUUID()}). */
    public static boolean isFollower(Mob mob, ServerPlayer player) {
        if (mob.isLeashed() && mob.getLeashHolder() == player) return true;
        if (mob instanceof TamableAnimal pet) return pet.isTame() && pet.isOwnedBy(player) && !pet.isOrderedToSit();
        if (mob instanceof OwnableEntity owned) {
            if (owned.getOwnerReference() != null && owned.getOwnerReference().matches(player)) return true;
        }
        // e.g. Recruits: soldiers expose a public UUID getOwnerUUID() without implementing OwnableEntity
        Optional<Method> m = OWNER_METHODS.computeIfAbsent(mob.getClass(), c -> {
            try {
                Method found = c.getMethod("getOwnerUUID");
                return found.getReturnType() == UUID.class ? Optional.of(found) : Optional.empty();
            } catch (NoSuchMethodException | SecurityException e) {
                return Optional.empty();
            }
        });
        if (m.isEmpty()) return false;
        try {
            return player.getUUID().equals(m.get().invoke(mob));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ arrival spot

    /**
     * Finds a safe standing spot near the location's arrival point. Surface places use the heightmap (no leaves,
     * no fluids); underground places in the overworld put you on the surface above the entrance; in realms with a
     * ceiling (Oblivion) the search stays around the original height.
     */
    public static BlockPos findArrival(ServerLevel level, BlockPos anchor, LocationKind kind) {
        boolean ceiling = level.dimensionType().hasCeiling();
        if (!kind.underground() || ceiling) {
            BlockPos p = searchVertical(level, anchor, 4);
            if (p != null) return p;
        }
        for (int r = 0; r <= 24; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    int x = anchor.getX() + dx;
                    int z = anchor.getZ() + dz;
                    level.getChunk(x >> 4, z >> 4);
                    BlockPos p;
                    if (ceiling) {
                        p = searchVertical(level, new BlockPos(x, anchor.getY(), z), 12);
                    } else {
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        p = new BlockPos(x, y, z);
                        if (!isSafe(level, p)) p = null;
                    }
                    if (p != null) return p;
                }
            }
        }
        return null;
    }

    private static BlockPos searchVertical(ServerLevel level, BlockPos start, int range) {
        level.getChunk(start.getX() >> 4, start.getZ() >> 4);
        for (int d = 0; d <= range; d++) {
            BlockPos up = start.above(d);
            if (isSafe(level, up)) return up;
            BlockPos down = start.below(d);
            if (d > 0 && isSafe(level, down)) return down;
        }
        return null;
    }

    public static boolean isSafe(ServerLevel level, BlockPos pos) {
        if (pos.getY() <= level.getMinY() || pos.getY() >= (level.getMaxY() + 1) - 2) return false;
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP)) return false;
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(BlockTags.CAMPFIRES) || floor.is(Blocks.CACTUS) || floor.is(BlockTags.FIRE)) return false;
        if (!level.getFluidState(below).isEmpty() || !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) return false;
        return passable(level, pos) && passable(level, pos.above());
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && !s.is(BlockTags.FIRE) && !s.is(Blocks.SWEET_BERRY_BUSH)
                && !s.is(Blocks.POWDER_SNOW);
    }
}
