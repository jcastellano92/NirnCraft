package com.skycraft.mounts;

import com.mojang.authlib.GameProfile;
import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.fauna.HorseBreed;
import com.skycraft.fauna.WildHorseTaming;
import com.skycraft.inventory.CarryWeight;
import com.skycraft.inventory.ItemWeights;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless self-test of the mount system with a fake rider and real mount entities: the breed-by-saddle matrix,
 * saddles and barding changing the numbers, weight-limited saddlebags that never destroy an item when the saddle
 * shrinks, the stamina bar swap on mount and dismount (no leak, no duplication, relog and death paths), the carry
 * weight a mount takes, taming only wild mounts from a sneak with a saddle in hand, and the vanilla horse screen
 * being closed. Runs as the autorun task {@code mounts} and as {@code /skycraft-mounts selftest}; the result is one log
 * line, {@code Mounts selftest: n/m checks passed, k problems}.
 */
public final class MountsSelfTest {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("skycraft-mounts-selftest".getBytes()), "[MountsTest]");

    private MountsSelfTest() {}

    public record Report(int passed, int total, List<String> lines, List<String> failed) {
        public int problems() {
            return total - passed;
        }

        public String summary() {
            return "Mounts selftest: " + passed + "/" + total + " checks passed, " + problems() + " problems"
                    + (failed.isEmpty() ? "" : " (" + String.join("; ", failed) + ")");
        }
    }

    public static void registerAutorun() {
        PerfAutorun.register("mounts", (t, done) -> {
            Report r;
            try {
                r = run(t.server());
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Mounts selftest crashed", e);
                r = new Report(0, 1, List.of(), List.of("crashed: " + e));
            }
            for (String l : r.lines()) Skycraft.LOGGER.info("[mounts] {}", l);
            Skycraft.LOGGER.info("{}", r.summary());
            done.run();
        });
    }

    private static final class Checks {
        final List<String> lines = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        int passed, total;

        void check(String name, boolean ok, String detail) {
            total++;
            if (ok) passed++;
            else failed.add(name);
            lines.add((ok ? "ok   " : "FAIL ") + name + ": " + detail);
        }
    }

    public static Report run(MinecraftServer server) {
        Checks c = new Checks();
        ServerLevel level = server.overworld();
        FakePlayer fp = FakePlayerFactory.get(level, PROFILE);
        List<Mob> spawned = new ArrayList<>();
        try {
            checks(level, fp, c, spawned);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Mounts selftest threw", e);
            c.check("selftest ran to the end", false, e.toString());
        } finally {
            fp.stopRiding();
            WildHorseTaming.abort(fp);
            for (Mob m : spawned) m.discard();
            fp.getInventory().clearContent();
            fp.setShiftKeyDown(false);
            fp.setPose(Pose.STANDING);
            MountStamina.discard(fp);
            MountStamina.forget(fp);
            var rec = Mounts.data(fp);
            rec.remove("bags");
            rec.remove("saddles");
            rec.remove("barding");
            rec.remove("uuid");
            rec.remove("active");
        }
        return new Report(c.passed, c.total, c.lines, c.failed);
    }

    private static Horse horse(ServerLevel level, FakePlayer owner, MountKind kind, BlockPos at, List<Mob> spawned) {
        Mob m = Mounts.create(level, kind, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, owner);
        level.addFreshEntity(m);
        spawned.add(m);
        return m instanceof Horse h ? h : null;
    }

    private static int count(Iterable<ItemStack> stacks, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : stacks) if (s.is(item)) n += s.getCount();
        return n;
    }

    private static void checks(ServerLevel level, FakePlayer fp, Checks c, List<Mob> spawned) {
        // ---- the matrix
        int bare = 0;
        for (MountKind k : MountKind.VALUES) {
            if (!MountGear.fixedTack(k) && !MountGear.accepts(k, SaddleType.PLAIN)) bare++;
            if (MountGear.baseCarry(k) <= 0) bare++;
        }
        c.check("every breed takes a plain saddle and has a carry weight", bare == 0, bare + " kinds without");
        c.check("ponies take no war saddle, the destrier takes all four",
                !MountGear.accepts(MountKind.WINDHELM_PINTO, SaddleType.WAR) && !MountGear.accepts(MountKind.MARKARTH_BLACK, SaddleType.WAR)
                        && MountGear.accepts(MountKind.BRETON_DESTRIER, SaddleType.WAR) && MountGear.accepts(MountKind.BRETON_DESTRIER, SaddleType.NOBLE), "");
        c.check("the legends keep their own tack", !MountGear.accepts(MountKind.SHADOWMERE, SaddleType.PLAIN) && MountGear.fixedTack(MountKind.ARVAK), "");
        c.check("draught breeds carry more than ponies", MountGear.baseCarry(MountKind.COLOVIAN_PAINT) > MountGear.baseCarry(MountKind.WINDHELM_PINTO), "");

        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos at = Mounts.safeSpot(level, new BlockPos(spawn.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()), spawn.getZ()));
        fp.moveTo(at.getX() + 3.5, at.getY(), at.getZ() + 0.5);
        fp.getInventory().clearContent();

        // ---- a mount in a plain saddle, then a pack saddle
        MountKind kind = MountKind.WHITERUN_BAY;
        Horse bay = horse(level, fp, kind, at, spawned);
        c.check("a mount is created saddled and owned", bay != null && Mounts.kindOf(bay) == kind && MountGear.saddle(bay) == SaddleType.PLAIN
                && bay.isSaddled() && Mounts.isOwner(fp, bay), "");
        double baseSpeed = bay.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        fp.getInventory().add(SaddleType.PACK.stack());
        ItemStack packStack = fp.getInventory().getItem(0);
        MountGear.Result r = MountGear.equipSaddle(fp, bay, kind, packStack);
        c.check("a pack saddle replaces the plain one", r == MountGear.Result.OK && MountGear.saddle(bay) == SaddleType.PACK && bay.isSaddled(), r.name());
        c.check("the old saddle came back to the bags and the new one was used up",
                count(fp.getInventory().items, Items.SADDLE) == 1 && count(fp.getInventory().items, SaddleType.PACK.item()) == 0, "");
        c.check("the pack saddle sets speed, bags, carry and stamina",
                Math.abs(bay.getAttributeValue(Attributes.MOVEMENT_SPEED) - baseSpeed * (1 + SaddleType.PACK.speedMod)) < 1e-6
                        && MountGear.bagKg(kind, SaddleType.PACK) > MountGear.bagKg(kind, SaddleType.PLAIN)
                        && MountGear.carryKg(bay, kind) == MountGear.baseCarry(kind) + SaddleType.PACK.carryKg
                        && MountGear.maxStamina(bay, kind) < kind.stamina,
                "speed " + bay.getAttributeValue(Attributes.MOVEMENT_SPEED) + " bags " + MountGear.bagKg(kind, SaddleType.PACK) + " kg");
        ItemStack war = SaddleType.WAR.stack();
        Horse pony = horse(level, fp, MountKind.WINDHELM_PINTO, at.east(4), spawned);
        c.check("a pony refuses a war saddle and keeps what it wears", MountGear.equipSaddle(fp, pony, MountKind.WINDHELM_PINTO, war) == MountGear.Result.WRONG_BREED
                && MountGear.saddle(pony) == SaddleType.PLAIN && war.getCount() == 1, "");

        // ---- barding
        double armor0 = bay.getAttributeValue(Attributes.ARMOR);
        double hp0 = bay.getMaxHealth();
        fp.getInventory().add(new ItemStack(MountItems.LEATHER_BARDING.get()));
        fp.getInventory().add(new ItemStack(MountItems.EBONY_BARDING.get()));
        ItemStack leather = find(fp, MountItems.LEATHER_BARDING.get());
        c.check("leather barding adds armour and health", MountGear.equipBarding(fp, bay, kind, leather) == MountGear.Result.OK
                && bay.getAttributeValue(Attributes.ARMOR) > armor0 && bay.getMaxHealth() > hp0, "armour " + bay.getAttributeValue(Attributes.ARMOR) + " health " + bay.getMaxHealth());
        double armorLeather = bay.getAttributeValue(Attributes.ARMOR);
        ItemStack ebony = find(fp, MountItems.EBONY_BARDING.get());
        MountGear.equipBarding(fp, bay, kind, ebony);
        c.check("ebony barding is stronger and the leather came back",
                bay.getAttributeValue(Attributes.ARMOR) > armorLeather && count(fp.getInventory().items, MountItems.LEATHER_BARDING.get()) == 1, "armour " + bay.getAttributeValue(Attributes.ARMOR));
        MountGear.unequipBarding(fp, bay, kind, true);
        c.check("taking the barding off restores the mount", Math.abs(bay.getAttributeValue(Attributes.ARMOR) - armor0) < 1e-6
                && Math.abs(bay.getMaxHealth() - hp0) < 1e-6 && count(fp.getInventory().items, MountItems.EBONY_BARDING.get()) == 1, "");

        // ---- weight-limited saddlebags: fill to the brim, shrink the saddle, nothing is lost
        fp.getInventory().clearContent();
        Mounts.data(fp).remove("bags");
        Saddlebags.Bag bag = new Saddlebags.Bag(fp, bay, kind);
        SaddlebagMenu menu = new SaddlebagMenu(77, fp.getInventory(), bag);
        float cap = bag.capacity();
        ItemStack probe = new ItemStack(Items.COBBLESTONE, 64);
        float unit = ItemWeights.get(probe);
        c.check("the gear slots take nothing but gear", !menu.getSlot(0).mayPlace(SaddleType.PLAIN.stack()) && !menu.getSlot(1).mayPlace(new ItemStack(Items.IRON_INGOT)), "");
        if (unit > 0) {
            ItemStack feed = new ItemStack(Items.COBBLESTONE, 4000);
            for (int i = Saddlebags.GEAR; i < 54 && !feed.isEmpty(); i++) menu.getSlot(i).safeInsert(feed, Math.min(64, feed.getCount()));
            float load = bag.cargoWeight();
            c.check("cargo fills to the capacity of the saddle and no further", load <= cap + 0.01f && load > cap - unit - 0.01f,
                    load + " of " + cap + " kg");
            int before = count(java.util.stream.IntStream.range(Saddlebags.GEAR, 54).mapToObj(bag::getItem).toList(), Items.COBBLESTONE);
            // a plain saddle from the bags swaps in, the pack saddle comes out, what no longer fits spills
            fp.getInventory().setItem(0, SaddleType.PLAIN.stack());
            boolean ok = menu.equipFrom(fp, menu.getSlot(54 + 27));
            float cap2 = bag.capacity();
            int inBag = count(java.util.stream.IntStream.range(Saddlebags.GEAR, 54).mapToObj(bag::getItem).toList(), Items.COBBLESTONE);
            int inPack = count(fp.getInventory().items, Items.COBBLESTONE);
            c.check("a smaller saddle spills the surplus into the bags, nothing is destroyed",
                    ok && cap2 < cap && bag.cargoWeight() <= cap2 + 0.01f && inBag + inPack == before && inPack > 0
                            && count(fp.getInventory().items, SaddleType.PACK.item()) == 1,
                    "cap " + cap + "->" + cap2 + ", bag " + inBag + " + pack " + inPack + " of " + before);
            // unequipping through the menu slot (what the Skyrim screen's Take does)
            Slot saddleSlot = menu.getSlot(0);
            ItemStack taken = saddleSlot.safeTake(1, Integer.MAX_VALUE, fp);
            int inBag3 = count(java.util.stream.IntStream.range(Saddlebags.GEAR, 54).mapToObj(bag::getItem).toList(), Items.COBBLESTONE);
            c.check("taking the saddle off the mount empties the bags into yours, items kept",
                    taken.getItem() == Items.SADDLE && MountGear.saddle(bay) == null && inBag3 == 0
                            && count(fp.getInventory().items, Items.COBBLESTONE) == before, "bag " + inBag3 + ", pack " + count(fp.getInventory().items, Items.COBBLESTONE));
        } else {
            c.check("cobblestone has a weight to test the bags with", false, "unit weight 0");
        }
        menu.removed(fp);
        MountGear.equipSaddle(fp, bay, kind, SaddleType.PLAIN.stack());

        // ---- the stamina bar swap
        fp.getInventory().clearContent();
        PlayerData d = SkyData.get(fp);
        float own = d.maxStamina() * 0.5f;
        d.setStamina(own);
        bay.getPersistentData().remove(MountStamina.TAG);
        float carry0 = CarryWeight.capacity(fp);
        fp.startRiding(bay, true);
        c.check("the rider steers the mount", bay.getControllingPassenger() == fp, "");
        MountStamina.tick(fp);
        c.check("taking the reins parks the rider's stamina and shows the mount's full bar",
                MountStamina.swapped(fp) && Math.abs(d.getStamina() - d.maxStamina()) < 0.5f && Mounts.data(fp).getFloat(MountStamina.PARKED) == own,
                "bar " + d.getStamina() + "/" + d.maxStamina() + " parked " + Mounts.data(fp).getFloat(MountStamina.PARKED));
        c.check("the mount takes weight off its rider", Math.abs(CarryWeight.capacity(fp) - carry0 - MountGear.carryKg(bay, kind)) < 0.01f,
                carry0 + " -> " + CarryWeight.capacity(fp));
        MountStamina.setGallop(fp, true);
        double x = bay.getX();
        for (int i = 1; i <= 25; i++) {
            bay.setPos(x + 0.3 * i, bay.getY(), bay.getZ());
            MountStamina.tick(fp);
        }
        float pts = bay.getPersistentData().getFloat(MountStamina.TAG);
        int max = MountGear.maxStamina(bay, kind);
        c.check("galloping drains the bar, which is the mount's", pts < max - 10 && Math.abs(d.getStamina() / d.maxStamina() - pts / max) < 0.02f,
                "mount " + pts + "/" + max + ", bar " + d.getStamina() + "/" + d.maxStamina());
        MountStamina.setGallop(fp, false);
        fp.stopRiding();
        MountStamina.tick(fp);
        c.check("getting off gives the rider's own value back exactly and the mount keeps its own",
                !MountStamina.swapped(fp) && d.getStamina() == own && Math.abs(bay.getPersistentData().getFloat(MountStamina.TAG) - pts) < 0.001f,
                "bar " + d.getStamina() + " (was " + own + "), mount " + bay.getPersistentData().getFloat(MountStamina.TAG));
        // again, leaving the way a logout does, and a death
        fp.startRiding(bay, true);
        MountStamina.tick(fp);
        float shown = d.getStamina();
        MountStamina.leave(fp);
        c.check("a logout while riding restores the bar, nothing leaks", d.getStamina() == own && !MountStamina.swapped(fp)
                && Math.abs(shown / d.maxStamina() - pts / max) < 0.03f, "bar " + d.getStamina());
        MountStamina.tick(fp);
        c.check("it takes the reins again from the same place", MountStamina.swapped(fp) && Math.abs(d.getStamina() / d.maxStamina() - pts / max) < 0.03f, "");
        fp.stopRiding();
        MountStamina.tick(fp);
        c.check("three cycles later the rider has exactly what they had", d.getStamina() == own, "bar " + d.getStamina() + " vs " + own);

        // ---- taming: only wild, only from a sneak, with a saddle the breed takes
        fp.getInventory().clearContent();
        Horse wild = EntityType.HORSE.create(level);
        HorseBreed.WHITERUN_BAY.apply(wild);
        wild.moveTo(at.getX() + 6.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(wild);
        spawned.add(wild);
        fp.getInventory().add(SaddleType.WAR.stack());
        ItemStack held = fp.getInventory().getItem(0);
        boolean handled = Mounts.interact(fp, wild, held);
        c.check("standing up, the saddle spooks a wild mount: no session, saddle kept", handled && !WildHorseTaming.isTaming(fp) && !wild.isSaddled() && held.getCount() == 1, "");
        fp.setPose(Pose.CROUCHING);
        fp.setShiftKeyDown(true);
        handled = Mounts.interact(fp, wild, held);
        c.check("sneaking, a war saddle is thrown on and the breaking begins", handled && WildHorseTaming.isTaming(fp) && wild.isSaddled()
                && MountGear.saddle(wild) == SaddleType.WAR && held.isEmpty() && fp.getVehicle() == wild, "");
        WildHorseTaming.abort(fp);
        fp.stopRiding();
        c.check("an interrupted breaking returns the saddle and leaves the beast bare",
                count(fp.getInventory().items, SaddleType.WAR.item()) == 1 && !wild.isSaddled() && MountGear.saddle(wild) == null, "");
        Horse wildPony = EntityType.HORSE.create(level);
        HorseBreed.WINDHELM_PINTO.apply(wildPony);
        wildPony.moveTo(at.getX() + 8.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(wildPony);
        spawned.add(wildPony);
        ItemStack again = find(fp, SaddleType.WAR.item());
        handled = Mounts.interact(fp, wildPony, again);
        c.check("a wild pony will not take a war saddle", handled && !WildHorseTaming.isTaming(fp) && !wildPony.isSaddled() && again.getCount() == 1, "");
        fp.setPose(Pose.STANDING);
        fp.setShiftKeyDown(false);
        handled = Mounts.interact(fp, bay, again);
        c.check("an owned, saddled mount is not saddled again from the hand", handled && MountGear.saddle(bay) == SaddleType.PLAIN && again.getCount() == 1, "");

        // ---- no vanilla horse screen
        fp.openHorseInventory(bay, new SimpleContainer(2));
        c.check("the vanilla horse screen is closed the moment it opens", fp.containerMenu == fp.inventoryMenu, "");

        companionChecks(level, fp, c, spawned, at);
    }

    private static Mob zombie(ServerLevel level, BlockPos at, List<Mob> spawned) {
        Mob z = EntityType.ZOMBIE.create(level);
        z.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(z);
        spawned.add(z);
        return z;
    }

    private static Mob mount(ServerLevel level, FakePlayer fp, MountKind kind, BlockPos at, List<Mob> spawned) {
        Mob m = Mounts.create(level, kind, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, fp);
        if (m == null) return null;
        level.addFreshEntity(m);
        spawned.add(m);
        return m;
    }

    /** Downed instead of dead and companion combat ({@link MountCompanion}). */
    private static void companionChecks(ServerLevel level, FakePlayer fp, Checks c, List<Mob> spawned, BlockPos at) {
        PlayerData pd = SkyData.get(fp);
        MountCompanion.TEST_OWNER = fp;
        try {
            long now = level.getGameTime();
            pd.setLastCombatTick(-100000L);

            // ---- every kind lies down instead of dying
            int made = 0;
            List<String> bad = new ArrayList<>();
            int i = 0;
            for (MountKind k : MountKind.VALUES) {
                Mob m = mount(level, fp, k, at.east(i % 6 * 2 - 5).north(12 + i / 6 * 3), spawned);
                i++;
                if (m == null) {
                    bad.add(k.id + " not created");
                    continue;
                }
                made++;
                m.hurt(m.damageSources().generic(), m.getHealth() * 10 + 100);
                boolean ok = m.isAlive() && MountCompanion.isDowned(m) && m.getHealth() > 0 && m.getHealth() <= 1.01f && m.isInvulnerable() && m.isNoAi();
                if (!ok) bad.add(k.id);
            }
            c.check("every mount kind that would die lies down alive with a sliver of health, invulnerable and still",
                    made == MountKind.VALUES.length && bad.isEmpty(), made + " created, problems: " + bad);

            // ---- one horse, step by step
            Mob horse = mount(level, fp, MountKind.WHITERUN_BAY, at.west(6), spawned);
            Mob zomb = zombie(level, at.west(9), spawned);
            zomb.setTarget(horse);
            c.check("a hostile takes an owned standing mount as a target (control)", zomb.getTarget() == horse, "");
            fp.startRiding(horse, true);
            float max = horse.getMaxHealth();
            horse.hurt(horse.damageSources().generic(), max * 4);
            c.check("damage past its health: downed, not dead, health a sliver", horse.isAlive() && MountCompanion.isDowned(horse)
                    && horse.getHealth() > 0 && horse.getHealth() <= 1.01f, "health " + horse.getHealth() + " of " + max);
            c.check("the rider is thrown off and nobody can mount it", fp.getVehicle() != horse && !fp.startRiding(horse, true) && fp.getVehicle() != horse, "");
            c.check("hostiles that were after it drop it", zomb.getTarget() != horse, "target " + zomb.getTarget());
            zomb.setTarget(horse);
            c.check("a downed mount cannot be taken as a target again", zomb.getTarget() != horse, "");
            float hp = horse.getHealth();
            boolean hit = horse.hurt(horse.damageSources().generic(), 6f);
            boolean voidHit = horse.hurt(horse.damageSources().fellOutOfWorld(), 4f);
            boolean mobHit = horse.hurt(zomb.damageSources().mobAttack(zomb), 6f);
            c.check("aimed blows, void and mobs do nothing to it", !hit && !voidHit && !mobHit && horse.getHealth() == hp && horse.isAlive(), "health " + horse.getHealth());
            horse.die(horse.damageSources().generic());
            c.check("a death called on it is refused too", horse.isAlive() && MountCompanion.isDowned(horse), "");
            c.check("the mounts record it as down: no death cooldown was set", !Mounts.data(fp).contains("cooldown"), "");

            // ---- no revival while the fight goes on, revival after calm and a rest
            long t0 = horse.getPersistentData().getLong(MountCompanion.DOWNED);
            Vitals.markInCombat(fp);
            boolean riseInCombat = MountCompanion.tryRise(horse, fp, t0 + 1000);
            c.check("it does not rise while its owner is in combat", !riseInCombat && MountCompanion.isDowned(horse), "");
            pd.setLastCombatTick(-100000L);
            zomb.setTarget(fp);
            c.check("it does not rise while a hostile near is after its owner", !MountCompanion.tryRise(horse, fp, t0 + 1000) && MountCompanion.isDowned(horse), "");
            zomb.setTarget(null);
            c.check("it does not rise the moment it fell", !MountCompanion.tryRise(horse, fp, t0 + 5) && MountCompanion.isDowned(horse), "");
            boolean rose = MountCompanion.tryRise(horse, fp, t0 + 1000);
            c.check("calm for 8 s and nobody after it: it rises at about 35% health, as it was",
                    rose && !MountCompanion.isDowned(horse) && horse.getHealth() >= max * 0.34f && !horse.isInvulnerable() && !horse.isNoAi() && !horse.isSilent(),
                    "health " + horse.getHealth() + "/" + max);
            c.check("it must rest before it can be ridden again", MountCompanion.resting(horse, t0 + 1000) && !fp.startRiding(horse, true), "");
            c.check("an owner who has left (none, offline) does not hold it down", MountCompanion.calm(horse, null), "");

            // ---- the void and persistence
            Mob second = mount(level, fp, MountKind.COLOVIAN_PAINT, at.west(3).south(6), spawned);
            second.hurt(second.damageSources().generic(), 1000f);
            second.setPos(second.getX(), level.getMinBuildHeight() - 20, second.getZ());
            MountCompanion.tickDowned(second, fp, level.getGameTime());
            c.check("a downed mount that falls out of the world is put back beside its owner, still down",
                    second.getY() >= level.getMinBuildHeight() && MountCompanion.isDowned(second) && second.isAlive(), "y " + second.getY());
            net.minecraft.nbt.CompoundTag nbt = new net.minecraft.nbt.CompoundTag();
            second.saveWithoutId(nbt);
            Horse copy = EntityType.HORSE.create(level);
            copy.load(nbt);
            c.check("downed state survives a save and load", MountCompanion.isDowned(copy) && copy.isInvulnerable() && copy.isNoAi() && copy.isSilent()
                    && copy.getPersistentData().getLong(MountCompanion.DOWNED) == second.getPersistentData().getLong(MountCompanion.DOWNED), "");
            Mounts.data(fp).putUUID("uuid", copy.getUUID());
            MountCompanion.resolveLoaded(copy, fp);
            boolean keptWithOwner = MountCompanion.isDowned(copy);
            MountCompanion.resolveLoaded(copy, null);
            c.check("loaded beside its online owner it stays down for the tick to rise; with no owner around it comes up quietly",
                    keptWithOwner && !MountCompanion.isDowned(copy) && !copy.isInvulnerable() && !copy.isNoAi() && !copy.isSilent()
                            && copy.getHealth() >= copy.getMaxHealth() * 0.34f, "");
            Mounts.data(fp).remove("uuid");
            Mob third = mount(level, fp, MountKind.CYRODILIC_BAY, at.west(3).south(9), spawned);
            third.hurt(third.damageSources().generic(), 1000f);
            Mounts.data(fp).putUUID("uuid", third.getUUID());
            MountCompanion.onLogout(fp);
            Mounts.data(fp).remove("uuid");
            c.check("a logout while it is down gets it up again, standing and normal", !MountCompanion.isDowned(third) && !third.isInvulnerable() && !third.isNoAi(), "");

            // ---- never permanently dead by damage; only an explicit kill removes one
            Mob fourth = mount(level, fp, MountKind.ALIKR_STEED, at.east(3).south(6), spawned);
            fourth.die(fourth.damageSources().generic());
            c.check("a death event on a standing mount lays it down instead", fourth.isAlive() && MountCompanion.isDowned(fourth), "");
            Mob fifth = mount(level, fp, MountKind.AURIDON_WHITE, at.east(3).south(9), spawned);
            fifth.hurt(fifth.damageSources().genericKill(), Float.MAX_VALUE);
            c.check("an explicit kill (the /kill source) still removes a mount", !fifth.isAlive(), "");

            // ---- companion combat
            Mob tiger = mount(level, fp, MountKind.SENCHE, at.west(4).north(4), spawned);
            Mob attacker = zombie(level, at.west(2).north(7), spawned);
            attacker.setTarget(fp);
            Vitals.markInCombat(fp);
            net.minecraft.world.entity.LivingEntity foe = MountCompanion.pickFoe(tiger, fp);
            c.check("a fighter takes up the mob that is after its owner", foe == attacker, "foe " + foe);
            net.minecraft.world.entity.ai.goal.Goal foeGoal = null;
            for (var g : MountCompanion.companionGoals(tiger)) if (g instanceof MountCompanion.FoeGoal) foeGoal = g;
            tiger.tickCount = Math.floorMod(-tiger.getId(), 10);
            c.check("its target goal sets the mob as its target", foeGoal != null && foeGoal.canUse() && trigger(foeGoal) && tiger.getTarget() == attacker,
                    "target " + tiger.getTarget());
            c.check("a mount that is ridden does not fight beside its owner (its goals stay idle)", tigerRidden(tiger, fp), "");
            MountCompanion.setStance(fp, true);
            c.check("stay is a flag in the mount record", MountCompanion.stays(fp), "");
            MountCompanion.setStance(fp, false);
            c.check("follow clears it", !MountCompanion.stays(fp) && !Mounts.data(fp).contains(MountCompanion.STAY), "");
            c.check("a fighter's kick is its attack scaled by its stat power",
                    MountCompanion.kickDamage(horse, MountKind.WHITERUN_BAY.combat()) > 0 && scaled(horse, 2f, MountKind.WHITERUN_BAY), "");

            Mob pony = mount(level, fp, MountKind.WINDHELM_PINTO, at.east(5).north(4), spawned);
            Mob near = zombie(level, at.east(5).north(7), spawned);
            near.setTarget(fp);
            Vitals.markInCombat(fp);
            net.minecraft.world.entity.ai.goal.Goal move = null;
            boolean fightsGoal = false;
            for (var g : MountCompanion.companionGoals(pony)) {
                if (g instanceof MountCompanion.MoveGoal) move = g;
                if (g instanceof MountCompanion.FoeGoal || g instanceof MountCompanion.KickGoal) fightsGoal = true;
            }
            pony.tickCount = Math.floorMod(-pony.getId(), 5);
            c.check("a non-fighter has no fighting goals and never takes a target", !fightsGoal && !MountKind.WINDHELM_PINTO.combat().fights()
                    && pony.getTarget() == null, "");
            c.check("a non-fighter near a hostile moves away to keep its distance", move != null && MountCompanion.threatNear(pony, fp) == near && move.canUse()
                    && pony.getTarget() == null, "threat " + MountCompanion.threatNear(pony, fp));
            int fighters = 0, peaceful = 0;
            for (MountKind k : MountKind.VALUES) {
                if (k.combat().fights()) fighters++;
                else peaceful++;
            }
            c.check("the kinds split into fighters and peaceful mounts (guar, camel, ponies and the indrik keep clear)",
                    fighters >= 8 && peaceful >= 6 && !MountKind.GUAR.combat().fights() && !MountKind.CAMEL.combat().fights()
                            && MountKind.SENCHE.combat().fights() && MountKind.MAMMOTH.combat().fights() && MountKind.ARVAK.combat().fights(),
                    fighters + " fighters, " + peaceful + " peaceful");
        } finally {
            MountCompanion.TEST_OWNER = null;
            pd.setLastCombatTick(-100000L);
            fp.stopRiding();
            Mounts.data(fp).remove(MountCompanion.STAY);
            Mounts.data(fp).remove("cooldown");
        }
    }

    private static boolean trigger(net.minecraft.world.entity.ai.goal.Goal g) {
        g.start();
        return true;
    }

    private static boolean tigerRidden(Mob tiger, FakePlayer fp) {
        // pick-foe is about the owner's fight; a ridden mount's own goals do not run (helping() is null)
        net.minecraft.world.entity.ai.goal.Goal foeGoal = null;
        for (var g : MountCompanion.companionGoals(tiger)) if (g instanceof MountCompanion.FoeGoal) foeGoal = g;
        fp.startRiding(tiger, true);
        boolean idle = true;
        if (foeGoal != null) {
            tiger.setTarget(null);
            tiger.tickCount = Math.floorMod(-tiger.getId(), 10);
            idle = !foeGoal.canUse();
        }
        fp.stopRiding();
        return idle;
    }

    private static boolean scaled(Mob mob, float factor, MountKind kind) {
        float base = MountCompanion.kickDamage(mob, kind.combat());
        mob.getPersistentData().putFloat(MountCompanion.POWER, factor);
        float scaled = MountCompanion.kickDamage(mob, kind.combat());
        mob.getPersistentData().remove(MountCompanion.POWER);
        return Math.abs(scaled - base * factor) < 1e-4;
    }

    private static ItemStack find(FakePlayer fp, net.minecraft.world.item.Item item) {
        for (ItemStack s : fp.getInventory().items) if (s.is(item)) return s;
        return ItemStack.EMPTY;
    }
}
