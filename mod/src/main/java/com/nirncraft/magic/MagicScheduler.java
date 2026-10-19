package com.nirncraft.magic;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server-side delayed tasks and lingering magical areas (Blizzard, Guardian Circle, Storm Call...).
 * Ticked from {@link MagicEvents#onServerTick}. Nothing here is saved: areas simply end on restart.
 */
public final class MagicScheduler {
    private static final List<Task> TASKS = new ArrayList<>();
    private static final List<Area> AREAS = new ArrayList<>();

    private MagicScheduler() {}

    private record Task(long runAt, Consumer<MinecraftServer> action) {}

    /** Runs {@code action} on the server thread after {@code delay} ticks. */
    public static void schedule(MinecraftServer server, int delay, Consumer<MinecraftServer> action) {
        TASKS.add(new Task(server.getTickCount() + Math.max(1, delay), action));
    }

    @FunctionalInterface
    public interface AreaAction {
        /** {@code owner} may be null if the caster logged out (areas that follow their owner end instead). */
        void tick(ServerLevel level, @Nullable ServerPlayer owner, Area area);
    }

    /** A lingering spell area. If {@code follow} is set it moves with its owner. */
    public static final class Area {
        public final UUID owner;
        public final ResourceKey<Level> dimension;
        public final boolean follow;
        public final double radius;
        public final int interval;
        public final String kind;
        private final AreaAction action;
        public Vec3 center;
        public int age;
        public int ticksLeft;

        Area(UUID owner, ResourceKey<Level> dimension, Vec3 center, boolean follow, double radius, int duration, int interval, String kind, AreaAction action) {
            this.owner = owner;
            this.dimension = dimension;
            this.center = center;
            this.follow = follow;
            this.radius = radius;
            this.ticksLeft = duration;
            this.interval = Math.max(1, interval);
            this.kind = kind;
            this.action = action;
        }
    }

    /**
     * Starts an area. Starting a second area of the same {@code kind} for the same owner replaces the first
     * (re-casting Blizzard doesn't stack two storms).
     */
    public static Area area(ServerPlayer owner, Vec3 center, boolean follow, double radius, int duration, int interval, String kind, AreaAction action) {
        AREAS.removeIf(a -> a.owner.equals(owner.getUUID()) && a.kind.equals(kind));
        Area area = new Area(owner.getUUID(), owner.level().dimension(), center, follow, radius, duration, interval, kind, action);
        AREAS.add(area);
        return area;
    }

    /** The owner's areas whose kind starts with {@code kindPrefix}, oldest first (runes). */
    public static List<Area> areas(UUID owner, String kindPrefix) {
        List<Area> out = new ArrayList<>();
        for (Area a : AREAS) {
            if (a.owner.equals(owner) && a.kind.startsWith(kindPrefix)) out.add(a);
        }
        return out;
    }

    /** Ends an area now. */
    public static void remove(Area area) {
        AREAS.remove(area);
    }

    public static boolean hasArea(UUID owner, String kind) {
        for (Area a : AREAS) {
            if (a.owner.equals(owner) && a.kind.equals(kind)) return true;
        }
        return false;
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!TASKS.isEmpty()) {
            List<Task> due = new ArrayList<>();
            for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
                Task t = it.next();
                if (t.runAt <= now) {
                    due.add(t);
                    it.remove();
                }
            }
            for (Task t : due) {
                try {
                    t.action.accept(server);
                } catch (Exception e) {
                    com.nirncraft.NirnCraft.LOGGER.error("NirnCraft magic task failed", e);
                }
            }
        }
        if (!AREAS.isEmpty()) {
            List<Area> snapshot = new ArrayList<>(AREAS);
            for (Area area : snapshot) {
                ServerLevel level = server.getLevel(area.dimension);
                ServerPlayer owner = server.getPlayerList().getPlayer(area.owner);
                boolean ownerHere = owner != null && owner.isAlive() && owner.level() == level;
                if (level == null || area.ticksLeft <= 0 || area.follow && !ownerHere) {
                    AREAS.remove(area);
                    continue;
                }
                if (area.follow) area.center = owner.position();
                if (area.age % area.interval == 0) {
                    try {
                        area.action.tick(level, ownerHere ? owner : null, area);
                    } catch (Exception e) {
                        com.nirncraft.NirnCraft.LOGGER.error("NirnCraft magic area failed", e);
                        AREAS.remove(area);
                        continue;
                    }
                }
                area.age++;
                area.ticksLeft--;
            }
        }
    }

    public static void clear() {
        TASKS.clear();
        AREAS.clear();
    }
}
