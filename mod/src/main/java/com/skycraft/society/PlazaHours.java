package com.skycraft.society;

import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/**
 * When a townsperson is at the plaza (owner, playtest 4: "why so many people just standing in the square?"). The
 * square is for market hours and for gatherings, not a place to stand all afternoon: each person has one two-hour
 * window a day (a different one each day) in which their free time is spent at the meeting point, and a plaza that
 * already holds {@link #CAPACITY} people sends the next one home to their chores.
 */
public final class PlazaHours {
    /** Most townsfolk standing about a meeting point at once (children and beggars included). */
    public static final int CAPACITY = 5;
    private static final int[] WINDOWS = {9, 11, 14, 16};

    private PlazaHours() {}

    /** Whether it is this person's hour at the plaza (Skyrim clock hour 0-23). */
    public static boolean theirHour(NpcEntity npc, int hour) {
        long day = npc.level().getOverworldClockTime() / 24000L;
        int slot = (int) Math.floorMod((npc.getUUID().getLeastSignificantBits() >>> 9) + day * 7919L, (long) WINDOWS.length);
        int start = WINDOWS[slot];
        return hour >= start && hour < start + 2;
    }

    /** Whether the square around {@code spot} is already as full as it is allowed to be (not counting {@code npc}). */
    public static boolean crowded(NpcEntity npc, BlockPos spot) {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        int n = level.getEntitiesOfClass(NpcEntity.class, new AABB(spot).inflate(7, 4, 7),
                o -> o != npc && o.isAlive() && !o.isSleeping() && o.role().townsfolk()).size();
        return n >= CAPACITY;
    }

    /** Whether {@code npc} should go and stand at the plaza spot right now. */
    public static boolean mayGo(NpcEntity npc, BlockPos spot, int hour) {
        return theirHour(npc, hour) && !crowded(npc, spot);
    }
}
