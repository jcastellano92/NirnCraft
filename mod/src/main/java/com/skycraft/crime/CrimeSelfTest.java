package com.skycraft.crime;

import com.skycraft.economy.Shop;
import com.skycraft.quest.QuestItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless check of the crime module's decisions ({@code /skycraft-crime selftest}): one night for locks, shops and
 * ownership; looking at a door never decides a lock; the never-lock marker; quest items are never stolen goods; a
 * chest holding the player's own things is not restocked; the jail's sentence clock, the sleep rule, the cell test
 * and where a prisoner wakes (outside the jail, never at the crime). Uses a door high above the spawn; restores the
 * time of day.
 */
final class CrimeSelfTest {
    private CrimeSelfTest() {}

    private static final class Result {
        int checks, problems;
        final List<String> lines = new ArrayList<>();

        void check(boolean ok, String what) {
            checks++;
            if (!ok) {
                problems++;
                lines.add("FAIL " + what);
            }
        }
    }

    record Report(int checks, int problems, List<String> lines) {
        String summary() {
            return "Crime selftest: " + checks + " checks, " + problems + " problems";
        }
    }

    static Report run(MinecraftServer server) {
        Result r = new Result();
        ServerLevel level = server.overworld();
        nights(r, level);
        doors(r, server, level);
        stolenGoods(r);
        restock(r);
        jail(r, server);
        rest(r);
        return new Report(r.checks, r.problems, r.lines);
    }

    /** Locks, ownership and the shop hours share one night. */
    private static void nights(Result r, ServerLevel level) {
        long saved = level.getDayTime();
        try {
            for (long t : new long[]{0, 1999, 2000, 6000, 13000, 13500, 13999, 14000, 18000, 23000, 23999}) {
                level.setDayTime(t);
                r.check(Locks.isNight(level) == !Shop.isOpen(level), "night at " + t + " is the shop's closed time");
            }
            level.setDayTime(13500);
            r.check(!Locks.isNight(level), "13500 (19:30) is still shop hours, so homes are not shut yet");
            level.setDayTime(14000);
            r.check(Locks.isNight(level), "14000 (20:00) is night");
            level.setDayTime(1000);
            r.check(Locks.isNight(level), "1000 (07:00) is night");
        } finally {
            level.setDayTime(saved);
        }
    }

    /** A door is never decided by looking at it, and the never-lock marker wins. */
    private static void doors(Result r, MinecraftServer server, ServerLevel level) {
        BlockPos pos = level.getSharedSpawnPos().atY(level.getMaxBuildHeight() - 10).east(7);
        level.getChunk(pos);
        BlockState oak = Blocks.OAK_DOOR.defaultBlockState();
        BlockState old = level.getBlockState(pos), oldTop = level.getBlockState(pos.above());
        long saved = level.getDayTime();
        Locks.LockData data = Locks.LockData.get(server);
        String key = Locks.key(level, pos);
        boolean hadNever = data.never.contains(key);
        Integer hadLock = data.locks.get(key);
        try {
            level.setBlock(pos, oak.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
            level.setBlock(pos.above(), oak.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
            data.locks.remove(key);
            level.setDayTime(18000);
            int first = Locks.lockLevel(level, pos);
            int again = Locks.lockLevel(level, pos.above());
            r.check(first == again, "asking about a door twice gives the same answer (" + first + " / " + again + ")");
            r.check(!data.locks.containsKey(key), "looking at a door does not store a lock for it");
            Locks.markNeverLock(level, pos.above());
            r.check(data.never.contains(key), "the never-lock marker is stored on the lower half");
            r.check(Locks.lockLevel(level, pos) == Locks.NOT_LOCKED, "a never-lock door is not locked");
            r.check(!Locks.nightLocked(level, pos), "a never-lock door is not shut for the night");
            Locks.setLock(level, pos, Locks.APPRENTICE);
            r.check(!data.never.contains(key) && Locks.lockLevel(level, pos) == Locks.APPRENTICE, "an explicit lock replaces the marker");
        } finally {
            data.locks.remove(key);
            if (hadLock != null) data.locks.put(key, hadLock);
            data.never.remove(key);
            if (hadNever) data.never.add(key);
            data.setDirty();
            level.setBlock(pos.above(), oldTop, 2);
            level.setBlock(pos, old, 2);
            level.setDayTime(saved);
        }
    }

    private static void stolenGoods(Result r) {
        ItemStack letter = QuestItems.markQuest(new ItemStack(Items.PAPER));
        Bounty.markStolen(letter);
        r.check(!Bounty.isStolen(letter), "a quest item is never tagged as stolen goods");
        letter.getOrCreateTag().putBoolean(Bounty.STOLEN_TAG, true);
        r.check(!Bounty.isStolen(letter), "a quest item that carries the tag is not confiscated either");
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        Bounty.markStolen(sword);
        r.check(Bounty.isStolen(sword), "an ordinary item is tagged stolen");
    }

    private static void restock(Result r) {
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        CompoundTag entry = new CompoundTag();
        ListTag gen = new ListTag();
        CompoundTag one = new CompoundTag();
        one.putString("id", "minecraft:iron_sword");
        one.putInt("n", 2);
        gen.add(one);
        entry.put("gen", gen);
        chest.setItem(0, new ItemStack(Items.IRON_SWORD));
        r.check(!com.skycraft.loot.Restock.holdsForeignItems(entry, chest), "a looted chest still holding part of its loot restocks");
        chest.setItem(1, new ItemStack(Items.DIAMOND));
        r.check(com.skycraft.loot.Restock.holdsForeignItems(entry, chest), "a chest holding something the table did not roll is left alone");
        chest.setItem(1, ItemStack.EMPTY);
        chest.setItem(2, new ItemStack(Items.IRON_SWORD, 2));
        r.check(com.skycraft.loot.Restock.holdsForeignItems(entry, chest), "more of an item than the table rolled counts as the player's");
    }

    /** The rest system's pure state machine (group requirement, handover, cancel, switches, AFK, jail, recovery): docs/REST.md. */
    private static void rest(Result r) {
        int[] checks = new int[1];
        List<String> fails = com.skycraft.world.RestLogic.selfTest(checks);
        r.checks += checks[0];
        r.problems += fails.size();
        r.lines.addAll(fails);
        // a prisoner is never part of the group the others wait for
        r.check(com.skycraft.world.RestLogic.tally(List.of(new com.skycraft.world.RestLogic.Participant(
                new java.util.UUID(1, 1), false, true))).required() == 0, "a jailed player is not required to rest");
    }

    private static void jail(Result r, MinecraftServer server) {
        r.check(Jail.evidenceTier(50) == Locks.NOVICE && Jail.evidenceTier(500) == Locks.ADEPT && Jail.evidenceTier(9000) == Locks.MASTER,
                "the evidence chest's lock grows with the bounty");

        // the sentence clock: it runs out once, stays at 0, and the bed is open when it is out (or after a minute)
        CompoundTag j = new CompoundTag();
        j.putInt("remaining", 3);
        r.check(!Jail.serveSecond(j) && !Jail.serveSecond(j), "a 3 second sentence is not over after 2 seconds");
        r.check(Jail.serveSecond(j) && j.getInt("remaining") == 0, "it runs out on the third second");
        r.check(!Jail.serveSecond(j) && j.getInt("remaining") == 0, "a served sentence stays served: the clock does not release");
        r.check(Jail.maySleep(j), "a served sentence may sleep at once");
        CompoundTag fresh = new CompoundTag();
        fresh.putInt("remaining", 300);
        fresh.putInt("served", 59);
        r.check(!Jail.maySleep(fresh), "a long sentence may not sleep before the first minute is over");
        fresh.putInt("served", 60);
        r.check(Jail.maySleep(fresh), "...and may after it");

        // the cell: on the cell side of the door's plane is in, the doorway and corridor are out
        BlockPos doorL = new BlockPos(5, 65, 8), standL = new BlockPos(3, 65, 9);
        BlockPos doorR = new BlockPos(9, 65, 8), standR = new BlockPos(11, 65, 9);
        r.check(!Jail.outOfCell(doorL, standL, 3.5, 9.5) && !Jail.outOfCell(doorL, standL, 1.5, 8.5), "inside a left cell is in the cell");
        r.check(Jail.outOfCell(doorL, standL, 5.5, 8.5) && Jail.outOfCell(doorL, standL, 6.5, 8.5), "the doorway and corridor are out of a left cell");
        r.check(!Jail.outOfCell(doorR, standR, 10.5, 9.5) && Jail.outOfCell(doorR, standR, 8.5, 8.5), "the right cells work the same way");
        r.check(!Jail.outOfCell(null, standL, 0, 0), "a cell with no known door is never 'out'");

        // where they wake: never the crime
        CompoundTag crime = new CompoundTag();
        crime.putString("hold", "whiterun");
        crime.putString("place", "");
        crime.putDouble("rx", 123456);
        crime.putDouble("ry", 90);
        crime.putDouble("rz", 654321);
        Jail.Exit exit = Jail.outside(server, crime, null);
        r.check(exit.level() == server.overworld(), "a prisoner of the jail dimension wakes in the overworld");
        r.check(Math.abs(exit.pos().getX() - 123456) > 8 || Math.abs(exit.pos().getZ() - 654321) > 8, "...and not where the crime was");
    }
}
