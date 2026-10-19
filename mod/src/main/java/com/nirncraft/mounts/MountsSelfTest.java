package com.nirncraft.mounts;

import com.mojang.authlib.GameProfile;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.fauna.HorseBreed;
import com.nirncraft.fauna.WildHorseTaming;
import com.nirncraft.inventory.CarryWeight;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.perf.PerfAutorun;
import com.nirncraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless self-test of the mount system with a fake rider and real mount entities: the breed-by-saddle matrix,
 * saddles and barding changing the numbers, weight-limited saddlebags that never destroy an item when the saddle
 * shrinks, the stamina bar swap on mount and dismount (no leak, no duplication, relog and death paths), the carry
 * weight a mount takes, taming only wild mounts from a sneak with a saddle in hand, and the vanilla horse screen
 * being closed. Runs as the autorun task {@code mounts} and as {@code /nirncraft-mounts selftest}; the result is one log
 * line, {@code Mounts selftest: n/m checks passed, k problems}.
 */
public final class MountsSelfTest {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-mounts-selftest".getBytes()), "[MountsTest]");

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
                NirnCraft.LOGGER.error("Mounts selftest crashed", e);
                r = new Report(0, 1, List.of(), List.of("crashed: " + e));
            }
            for (String l : r.lines()) NirnCraft.LOGGER.info("[mounts] {}", l);
            NirnCraft.LOGGER.info("{}", r.summary());
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
            NirnCraft.LOGGER.error("Mounts selftest threw", e);
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
            SkyData.get(fp).module(Saddlebags.OVERFLOW_MODULE).remove(com.nirncraft.inventory.BulkStore.LIST);
            com.nirncraft.inventory.Pack.clear(fp);
            rec.remove("saddles");
            rec.remove("barding");
            rec.remove("uuid");
            rec.remove("active");
            rec.remove("stable");
            rec.remove("origin");
            rec.remove("mstats");
            rec.remove("tokens");
            rec.remove("stock_sold");
            rec.remove("cooldown");
            for (String perk : new String[]{"horse_sense", "wild_eye", "steady_seat", "pack_master", "endurance", "trick_rider"}) {
                SkyData.get(fp).setPerkRank("riding." + perk, 0);
            }
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

        BlockPos spawn = level.getRespawnData().pos();
        BlockPos at = Mounts.safeSpot(level, new BlockPos(spawn.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ()), spawn.getZ()));
        fp.snapTo(at.getX() + 3.5, at.getY(), at.getZ() + 0.5);
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
                count(fp.getInventory().getNonEquipmentItems(), Items.SADDLE) == 1 && count(fp.getInventory().getNonEquipmentItems(), SaddleType.PACK.item()) == 0, "");
        MountStats born = MountStats.of(bay, kind);
        c.check("the pack saddle sets speed, bags, carry and stamina (on top of the individual's own)",
                Math.abs(bay.getAttributeValue(Attributes.MOVEMENT_SPEED) - baseSpeed * (1 + SaddleType.PACK.speedMod) * born.speedMult()) < 1e-5
                        && MountGear.bagKg(kind, SaddleType.PACK) > MountGear.bagKg(kind, SaddleType.PLAIN)
                        && Math.abs(MountGear.carryKg(bay, kind) - (MountGear.baseCarry(kind) + SaddleType.PACK.carryKg) * born.mult(MountProfile.CARRY)) < 0.01f
                        && MountGear.maxStamina(bay, kind) == Math.round(kind.stamina * SaddleType.PACK.staminaMult * born.mult(MountProfile.STAMINA)),
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
                bay.getAttributeValue(Attributes.ARMOR) > armorLeather && count(fp.getInventory().getNonEquipmentItems(), MountItems.LEATHER_BARDING.get()) == 1, "armour " + bay.getAttributeValue(Attributes.ARMOR));
        MountGear.unequipBarding(fp, bay, kind, true);
        c.check("taking the barding off restores the mount", Math.abs(bay.getAttributeValue(Attributes.ARMOR) - armor0) < 1e-6
                && Math.abs(bay.getMaxHealth() - hp0) < 1e-6 && count(fp.getInventory().getNonEquipmentItems(), MountItems.EBONY_BARDING.get()) == 1, "");

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
            int inPack = count(fp.getInventory().getNonEquipmentItems(), Items.COBBLESTONE);
            c.check("a smaller saddle spills the surplus into the bags, nothing is destroyed",
                    ok && cap2 < cap && bag.cargoWeight() <= cap2 + 0.01f && inBag + inPack == before && inPack > 0
                            && count(fp.getInventory().getNonEquipmentItems(), SaddleType.PACK.item()) == 1,
                    "cap " + cap + "->" + cap2 + ", bag " + inBag + " + pack " + inPack + " of " + before);
            // unequipping through the menu slot (what the Skyrim screen's Take does)
            Slot saddleSlot = menu.getSlot(0);
            ItemStack taken = saddleSlot.safeTake(1, Integer.MAX_VALUE, fp);
            int inBag3 = count(java.util.stream.IntStream.range(Saddlebags.GEAR, 54).mapToObj(bag::getItem).toList(), Items.COBBLESTONE);
            c.check("taking the saddle off the mount empties the bags into yours, items kept",
                    taken.getItem() == Items.SADDLE && MountGear.saddle(bay) == null && inBag3 == 0
                            && count(fp.getInventory().getNonEquipmentItems(), Items.COBBLESTONE) == before, "bag " + inBag3 + ", pack " + count(fp.getInventory().getNonEquipmentItems(), Items.COBBLESTONE));
        } else {
            c.check("cobblestone has a weight to test the bags with", false, "unit weight 0");
        }
        menu.removed(fp);
        MountGear.equipSaddle(fp, bay, kind, SaddleType.PLAIN.stack());

        // ---- the bags' overflow: weight is the only limit, and it is persistent, grouped and never lost
        bagOverflow(fp, c, bay, kind);

        // ---- the stamina bar swap
        fp.getInventory().clearContent();
        PlayerData d = SkyData.get(fp);
        float own = d.maxStamina() * 0.5f;
        d.setStamina(own);
        bay.getPersistentData().remove(MountStamina.TAG);
        float carry0 = CarryWeight.capacity(fp);
        fp.startRiding(bay, true, true);
        c.check("the rider steers the mount", bay.getControllingPassenger() == fp, "");
        MountStamina.tick(fp);
        c.check("taking the reins parks the rider's stamina and shows the mount's full bar",
                MountStamina.swapped(fp) && Math.abs(d.getStamina() - d.maxStamina()) < 0.5f && Mounts.data(fp).getFloatOr(MountStamina.PARKED, 0f) == own,
                "bar " + d.getStamina() + "/" + d.maxStamina() + " parked " + Mounts.data(fp).getFloatOr(MountStamina.PARKED, 0f));
        c.check("the mount takes weight off its rider", Math.abs(CarryWeight.capacity(fp) - carry0 - MountGear.carryKg(bay, kind)) < 0.01f,
                carry0 + " -> " + CarryWeight.capacity(fp));
        MountStamina.setGallop(fp, true);
        double x = bay.getX();
        for (int i = 1; i <= 25; i++) {
            bay.setPos(x + 0.3 * i, bay.getY(), bay.getZ());
            MountStamina.tick(fp);
        }
        float pts = bay.getPersistentData().getFloatOr(MountStamina.TAG, 0f);
        int max = MountGear.maxStamina(bay, kind);
        c.check("galloping drains the bar, which is the mount's", pts < max - 10 && Math.abs(d.getStamina() / d.maxStamina() - pts / max) < 0.02f,
                "mount " + pts + "/" + max + ", bar " + d.getStamina() + "/" + d.maxStamina());
        MountStamina.setGallop(fp, false);
        fp.stopRiding();
        MountStamina.tick(fp);
        c.check("getting off gives the rider's own value back exactly and the mount keeps its own",
                !MountStamina.swapped(fp) && d.getStamina() == own && Math.abs(bay.getPersistentData().getFloatOr(MountStamina.TAG, 0f) - pts) < 0.001f,
                "bar " + d.getStamina() + " (was " + own + "), mount " + bay.getPersistentData().getFloatOr(MountStamina.TAG, 0f));
        // again, leaving the way a logout does, and a death
        fp.startRiding(bay, true, true);
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
        Horse wild = net.minecraft.world.entity.EntityTypes.HORSE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        HorseBreed.WHITERUN_BAY.apply(wild);
        wild.snapTo(at.getX() + 6.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(wild);
        spawned.add(wild);
        fp.getInventory().add(SaddleType.WAR.stack());
        ItemStack held = fp.getInventory().getItem(0);
        MountStats wary = MountStats.of(wild, MountKind.WHITERUN_BAY);
        wary.trust = 20f; // a wild animal that has never been fed: it does not let you walk up
        MountStats.store(wild, wary);
        boolean handled = Mounts.interact(fp, wild, held);
        c.check("standing up, the saddle spooks a wild mount: no session, saddle kept", handled && !WildHorseTaming.isTaming(fp) && !wild.isSaddled() && held.getCount() == 1, "");
        fp.setPose(Pose.CROUCHING);
        fp.setShiftKeyDown(true);
        handled = Mounts.interact(fp, wild, held);
        c.check("sneaking, a war saddle is used up and the breaking begins (AI off, no saddle yet, rider aboard)",
                handled && WildHorseTaming.isTaming(fp) && !wild.isSaddled() && wild.isNoAi() && held.isEmpty() && fp.getVehicle() == wild, "");
        WildHorseTaming.abort(fp);
        fp.stopRiding();
        c.check("an interrupted breaking returns the saddle, leaves the beast bare and gives it its mind back",
                count(fp.getInventory().getNonEquipmentItems(), SaddleType.WAR.item()) == 1 && !wild.isSaddled() && MountGear.saddle(wild) == null && !wild.isNoAi(), "");
        Horse wildPony = net.minecraft.world.entity.EntityTypes.HORSE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        HorseBreed.WINDHELM_PINTO.apply(wildPony);
        wildPony.snapTo(at.getX() + 8.5, at.getY(), at.getZ() + 0.5);
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
        statChecks(level, fp, c, spawned, at);
    }

    private static Mob zombie(ServerLevel level, BlockPos at, List<Mob> spawned) {
        Mob z = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        z.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
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
                com.nirncraft.platform.Effects.hurt(m, m.damageSources().generic(), m.getHealth() * 10 + 100);
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
            fp.startRiding(horse, true, true);
            float max = horse.getMaxHealth();
            com.nirncraft.platform.Effects.hurt(horse, horse.damageSources().generic(), max * 4);
            c.check("damage past its health: downed, not dead, health a sliver", horse.isAlive() && MountCompanion.isDowned(horse)
                    && horse.getHealth() > 0 && horse.getHealth() <= 1.01f, "health " + horse.getHealth() + " of " + max);
            c.check("the rider is thrown off and nobody can mount it", fp.getVehicle() != horse && !fp.startRiding(horse, true, true) && fp.getVehicle() != horse, "");
            c.check("hostiles that were after it drop it", zomb.getTarget() != horse, "target " + zomb.getTarget());
            zomb.setTarget(horse);
            c.check("a downed mount cannot be taken as a target again", zomb.getTarget() != horse, "");
            float hp = horse.getHealth();
            boolean hit = com.nirncraft.platform.Effects.hurt(horse, horse.damageSources().generic(), 6f);
            boolean voidHit = com.nirncraft.platform.Effects.hurt(horse, horse.damageSources().fellOutOfWorld(), 4f);
            boolean mobHit = com.nirncraft.platform.Effects.hurt(horse, zomb.damageSources().mobAttack(zomb), 6f);
            c.check("aimed blows, void and mobs do nothing to it", !hit && !voidHit && !mobHit && horse.getHealth() == hp && horse.isAlive(), "health " + horse.getHealth());
            horse.die(horse.damageSources().generic());
            c.check("a death called on it is refused too", horse.isAlive() && MountCompanion.isDowned(horse), "");
            c.check("the mounts record it as down: no death cooldown was set", !Mounts.data(fp).contains("cooldown"), "");

            // ---- no revival while the fight goes on, revival after calm and a rest
            long t0 = horse.getPersistentData().getLongOr(MountCompanion.DOWNED, 0L);
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
            c.check("it must rest before it can be ridden again", MountCompanion.resting(horse, t0 + 1000) && !fp.startRiding(horse, true, true), "");
            c.check("an owner who has left (none, offline) does not hold it down", MountCompanion.calm(horse, null), "");

            // ---- the void and persistence
            Mob second = mount(level, fp, MountKind.COLOVIAN_PAINT, at.west(3).south(6), spawned);
            com.nirncraft.platform.Effects.hurt(second, second.damageSources().generic(), 1000f);
            second.setPos(second.getX(), level.getMinY() - 20, second.getZ());
            MountCompanion.tickDowned(second, fp, level.getGameTime());
            c.check("a downed mount that falls out of the world is put back beside its owner, still down",
                    second.getY() >= level.getMinY() && MountCompanion.isDowned(second) && second.isAlive(), "y " + second.getY());
            net.minecraft.nbt.CompoundTag nbt = new net.minecraft.nbt.CompoundTag();
            nbt = com.nirncraft.platform.EntityData.saveWithoutId(second);
            Horse copy = net.minecraft.world.entity.EntityTypes.HORSE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            com.nirncraft.platform.EntityData.load(copy, nbt);
            c.check("downed state survives a save and load", MountCompanion.isDowned(copy) && copy.isInvulnerable() && copy.isNoAi() && copy.isSilent()
                    && copy.getPersistentData().getLongOr(MountCompanion.DOWNED, 0L) == second.getPersistentData().getLongOr(MountCompanion.DOWNED, 0L), "");
            Mounts.data(fp).store("uuid", net.minecraft.core.UUIDUtil.CODEC,  copy.getUUID());
            MountCompanion.resolveLoaded(copy, fp);
            boolean keptWithOwner = MountCompanion.isDowned(copy);
            MountCompanion.resolveLoaded(copy, null);
            c.check("loaded beside its online owner it stays down for the tick to rise; with no owner around it comes up quietly",
                    keptWithOwner && !MountCompanion.isDowned(copy) && !copy.isInvulnerable() && !copy.isNoAi() && !copy.isSilent()
                            && copy.getHealth() >= copy.getMaxHealth() * 0.34f, "");
            Mounts.data(fp).remove("uuid");
            Mob third = mount(level, fp, MountKind.CYRODILIC_BAY, at.west(3).south(9), spawned);
            com.nirncraft.platform.Effects.hurt(third, third.damageSources().generic(), 1000f);
            Mounts.data(fp).store("uuid", net.minecraft.core.UUIDUtil.CODEC,  third.getUUID());
            MountCompanion.onLogout(fp);
            Mounts.data(fp).remove("uuid");
            c.check("a logout while it is down gets it up again, standing and normal", !MountCompanion.isDowned(third) && !third.isInvulnerable() && !third.isNoAi(), "");

            // ---- never permanently dead by damage; only an explicit kill removes one
            Mob fourth = mount(level, fp, MountKind.ALIKR_STEED, at.east(3).south(6), spawned);
            fourth.die(fourth.damageSources().generic());
            c.check("a death event on a standing mount lays it down instead", fourth.isAlive() && MountCompanion.isDowned(fourth), "");
            Mob fifth = mount(level, fp, MountKind.AURIDON_WHITE, at.east(3).south(9), spawned);
            com.nirncraft.platform.Effects.hurt(fifth, fifth.damageSources().genericKill(), Float.MAX_VALUE);
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
        fp.startRiding(tiger, true, true);
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

    // ------------------------------------------------------------------ individuals, growth, inspection, stable, one active mount

    private static boolean sameStats(MountStats a, MountStats b) {
        return java.util.Arrays.equals(a.q, b.q) && java.util.Arrays.equals(a.grow, b.grow) && a.xp == b.xp && Math.abs(a.trust - b.trust) < 1e-4f
                && a.trainWeek == b.trainWeek && a.trainCount == b.trainCount;
    }

    private static Horse wildHorse(ServerLevel level, HorseBreed breed, BlockPos at, List<Mob> spawned) {
        Horse h = net.minecraft.world.entity.EntityTypes.HORSE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        breed.apply(h);
        h.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(h);
        spawned.add(h);
        return h;
    }

    /** Breaks a wild horse in with a bot that counter-steers (the minigame played perfectly). */
    private static boolean breakIn(FakePlayer fp, Horse wild, SaddleType saddle) {
        fp.getInventory().add(saddle.stack());
        ItemStack held = find(fp, saddle.item());
        fp.setPose(Pose.CROUCHING);
        fp.setShiftKeyDown(true);
        Mounts.interact(fp, wild, held);
        WildHorseTaming.TamingSession s = WildHorseTaming.session(fp);
        for (int i = 0; s != null && i < 20 * 130 && WildHorseTaming.isTaming(fp); i++) {
            double want = -(s.balance.marker + s.balance.vel / 0.1);
            s.steer = want > 0.12 ? 1 : want < -0.12 ? -1 : 0;
            WildHorseTaming.tickServer(fp);
        }
        fp.setPose(Pose.STANDING);
        fp.setShiftKeyDown(false);
        return Mounts.isOwner(fp, wild);
    }

    private static void statChecks(ServerLevel level, FakePlayer fp, Checks c, List<Mob> spawned, BlockPos at) {
        MountKind k = MountKind.CYRODILIC_BAY;
        PlayerData pd = SkyData.get(fp);

        // ---- individual rolls: deterministic, graded, bounded; legends fixed high
        MountStats a = MountStats.roll(k, 12345L, 0f, false), b = MountStats.roll(k, 12345L, 0f, false), d = MountStats.roll(k, 54321L, 0f, false);
        c.check("a roll is deterministic from its seed and differs between seeds", sameStats(a, b) && !java.util.Arrays.equals(a.q, d.q), "");
        c.check("grades map from the roll: Poor, Fair, Good, Fine, Superb",
                MountStats.gradeOf(-100) == 0 && MountStats.gradeOf(-56) == 0 && MountStats.gradeOf(-55) == 1 && MountStats.gradeOf(-21) == 1
                        && MountStats.gradeOf(-20) == 2 && MountStats.gradeOf(30) == 2 && MountStats.gradeOf(31) == 3 && MountStats.gradeOf(70) == 3
                        && MountStats.gradeOf(71) == 4 && MountStats.gradeOf(100) == 4, "");
        int[] hist = new int[5];
        boolean bounded = true;
        for (int i = 0; i < 4000; i++) {
            MountStats s = MountStats.roll(k, i * 7919L + 1, 0f, false);
            hist[s.grade(MountProfile.SPEED)]++;
            for (int st = 0; st < MountProfile.N; st++) {
                float m = s.rollMult(st);
                bounded &= m >= 1f - MountProfile.VARIANCE[st] - 1e-4f && m <= 1f + MountProfile.VARIANCE[st] + 1e-4f;
            }
        }
        c.check("grades are spread like a bell: Superb is rare, Good is common", hist[4] > 40 && hist[4] < 360 && hist[0] > 200 && hist[2] > 1400,
                java.util.Arrays.toString(hist));
        c.check("every roll stays within the kind's variance", bounded, "");
        MountStats shadow = MountStats.roll(MountKind.SHADOWMERE, 1L, 0f, false), shadow2 = MountStats.roll(MountKind.SHADOWMERE, 2L, 0f, false);
        c.check("the legends are fixed and fine", java.util.Arrays.equals(shadow.q, shadow2.q) && shadow.grade(MountProfile.SPEED) >= 4
                && shadow.grade(MountProfile.TEMPER) >= 3, "");
        boolean allKinds = true;
        for (MountKind kk : MountKind.VALUES) {
            MountProfile p = MountProfile.of(kk);
            allKinds &= p != null && p.bonus.length == MountProfile.N && kk.stamina >= 600 && MountProfile.baseHealth(kk) > 0 && MountProfile.baseJump(kk) > 0
                    && MountProfile.speedAttr(kk) > 0;
        }
        c.check("every kind has a profile, and out-endures a runner (600+ gallop ticks against about 280)", allKinds, "");
        Horse seen = wildHorse(level, HorseBreed.CYRODILIC_BAY, at.south(14), spawned);
        c.check("a wild animal is the same animal on every look", sameStats(MountStats.of(seen, k), MountStats.of(seen, k)), "");

        // ---- levels and growth from simulated riding
        c.check("levels: thresholds rise and cap at 20", MountStats.levelFor(0) == 1 && MountStats.levelFor(MountStats.xpFor(2) - 0.01f) == 1
                && MountStats.levelFor(MountStats.xpFor(2)) == 2 && MountStats.levelFor(1e9f) == 20 && MountStats.xpFor(20) == 5890f, "");
        Horse mount = horse(level, fp, k, at.south(4), spawned);
        var rec = Mounts.data(fp);
        rec.putString("active", k.id);
        rec.store("uuid", net.minecraft.core.UUIDUtil.CODEC,  mount.getUUID());
        MountStats before = MountStats.of(mount, k);
        for (int i = 0; i < 40; i++) MountGrowth.ride(fp, mount, k, 0.0, false, true);
        for (int i = 0; i < 3000; i++) MountGrowth.ride(fp, mount, k, 0.5, true, false);
        MountStats grown = MountStats.of(mount, k);
        c.check("a long gallop and some jumps earn experience, levels and growth in the stats they trained",
                grown.xp > before.xp + 100 && grown.level() >= 3 && grown.growthPoints() >= 2 && grown.grow[MountProfile.JUMP] >= 1
                        && grown.grow[MountProfile.SPEED] + grown.grow[MountProfile.STAMINA] >= 1,
                "xp " + grown.xp + " level " + grown.level() + " grow " + java.util.Arrays.toString(grown.grow));
        MountStats recorded = MountGrowth.recorded(fp, k);
        c.check("the player's record mirrors the active mount", recorded != null && sameStats(recorded, grown), "");
        c.check("growth raises what it should: jump attribute, stamina and speed follow the stats",
                Math.abs(mount.getAttributeValue(Attributes.JUMP_STRENGTH) - HorseBreed.CYRODILIC_BAY.jumpStrength * grown.mult(MountProfile.JUMP)) < 1e-4
                        && MountGear.maxStamina(mount, k) == Math.round(k.stamina * grown.mult(MountProfile.STAMINA)), "");
        boolean capped = true;
        MountStats maxed = MountStats.roll(k, 99L, 0f, false);
        maxed.addXp(1e9f);
        for (int s = 0; s < MountProfile.N; s++) capped &= maxed.grow[s] <= maxed.cap(s);
        c.check("level 20 is the cap and no stat grows past its soft cap", maxed.level() == 20 && capped && maxed.growthPoints() <= 19, "points " + maxed.growthPoints());
        MountStats trustTest = MountStats.roll(k, 5L, 0f, false);
        trustTest.addTrust(500f);
        float hi = trustTest.trust;
        trustTest.addTrust(-500f);
        c.check("trust stays within 0 and 100 and moves speed and wind a little",
                hi == 100f && trustTest.trust == 0f && MountStats.trustSpeed(100f) > MountStats.trustSpeed(0f) && MountStats.restMult(100f) > MountStats.restMult(0f), "");

        // ---- care: feeding raises trust (and not twice in a row), abuse lowers it
        ItemStack apples = new ItemStack(Items.APPLE, 3);
        float t0 = MountStats.of(mount, k).trust;
        MountGrowth.feed(fp, mount, k, apples, true);
        float t1 = MountStats.of(mount, k).trust;
        MountGrowth.feed(fp, mount, k, apples, true);
        c.check("an apple from the hand raises trust once; a second one straight after is refused", t1 > t0 && apples.getCount() == 2
                && MountStats.of(mount, k).trust == t1, t0 + " -> " + t1);
        MountGrowth.abuse(fp, mount);
        c.check("a swing at your own mount costs trust", MountStats.of(mount, k).trust < t1, "");
        Horse stray = wildHorse(level, HorseBreed.WHITERUN_BAY, at.south(16), spawned);
        float w0 = MountStats.of(stray, MountKind.WHITERUN_BAY).trust;
        ItemStack carrots = new ItemStack(Items.CARROT, 2);
        MountGrowth.feed(fp, stray, MountKind.WHITERUN_BAY, carrots, false);
        c.check("feeding a wild one calms it, and the calm is stored on it", MountStats.of(stray, MountKind.WHITERUN_BAY).trust > w0 && MountStats.stored(stray)
                && carrots.getCount() == 1, "");

        // ---- persistence: entity NBT and the record
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        saved = com.nirncraft.platform.EntityData.saveWithoutId(mount);
        Horse copy = net.minecraft.world.entity.EntityTypes.HORSE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        com.nirncraft.platform.EntityData.load(copy, saved);
        c.check("the stats survive the entity's save and load", Mounts.kindOf(copy) == k && sameStats(MountStats.of(copy, k), MountStats.of(mount, k)), "");
        MountStats via = MountStats.fromTag(k, MountStats.of(mount, k).toTag());
        c.check("the stats survive their own tag round trip", sameStats(via, MountStats.of(mount, k)), "");

        // ---- inspection: what a viewer may know, and who may ask
        pd.setPerkRank("riding.horse_sense", 0);
        pd.setPerkRank("riding.wild_eye", 0);
        fp.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 4.5);
        MountView own = MountInspect.viewOf(fp, mount, k);
        boolean hidden = true;
        for (float v : own.value()) hidden &= v == MountView.HIDDEN;
        c.check("your own mount without Horse Sense: words and no numbers", own.owner() == 1 && own.detail() == 0 && hidden && own.known()
                && own.trust() == MountView.HIDDEN && own.level() == MountStats.of(mount, k).level(), "");
        pd.setPerkRank("riding.horse_sense", 1);
        own = MountInspect.viewOf(fp, mount, k);
        MountStats ms = MountStats.of(mount, k);
        boolean exact = true;
        for (int i = 0; i < MountView.STATS; i++) exact &= own.gradeLo()[i] == ms.grade(i) && own.gradeHi()[i] == ms.grade(i);
        c.check("with Horse Sense your own mount reads exactly: grades, numbers, growth, trust",
                own.detail() == 2 && exact && own.value()[MountProfile.SPEED] > 0 && own.value()[MountProfile.STAMINA] > 30
                        && own.growth()[MountProfile.JUMP] == ms.grow[MountProfile.JUMP] && own.trust() >= 0, "");
        MountView stranger = MountInspect.viewOf(fp, stray, MountKind.WHITERUN_BAY);
        MountStats ss = MountStats.of(stray, MountKind.WHITERUN_BAY);
        boolean estimate = stranger.owner() == 0 && stranger.detail() == 1;
        for (int i = 0; i < MountView.STATS; i++) {
            estimate &= stranger.gradeLo()[i] <= ss.grade(i) && ss.grade(i) <= stranger.gradeHi()[i] && stranger.gradeHi()[i] - stranger.gradeLo()[i] == 1;
        }
        boolean noNumbers = true;
        for (float v : stranger.value()) noNumbers &= v == MountView.HIDDEN;
        c.check("a wild animal's grades are an estimate of two neighbouring grades, with no numbers", estimate && noNumbers, "");
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.FriendlyByteBuf fb = new net.minecraft.network.FriendlyByteBuf(raw);
        own.write(fb);
        MountView back = MountView.read(fb);
        c.check("the card payload survives the wire", back.kindId().equals(own.kindId()) && back.level() == own.level()
                && java.util.Arrays.equals(back.gradeLo(), own.gradeLo()) && java.util.Arrays.equals(back.value(), own.value())
                && java.util.Arrays.equals(back.caps(), own.caps()) && back.trust() == own.trust() && back.saddle().equals(own.saddle()), "");
        // who may ask
        fp.snapTo(mount.getX() + 3, mount.getY(), mount.getZ());
        boolean near = MountInspect.allowed(fp, mount);
        fp.snapTo(mount.getX() + 12, mount.getY(), mount.getZ());
        boolean mid = MountInspect.allowed(fp, mount);
        pd.setPerkRank("riding.wild_eye", 1);
        boolean midEye = MountInspect.allowed(fp, mount);
        fp.snapTo(mount.getX() + 40, mount.getY(), mount.getZ());
        boolean far = MountInspect.allowed(fp, mount);
        pd.setPerkRank("riding.wild_eye", 0);
        c.check("the server answers a look only in range (6 blocks, 16 with Wild Eye)", near && !mid && midEye && !far, near + " " + mid + " " + midEye + " " + far);
        fp.snapTo(at.getX() + 3.5, at.getY(), at.getZ() + 0.5);

        // ---- the stable: price by grade and level, stock by week, trade-in, training
        MountStats poor = MountStats.roll(k, 1L, 0f, false), fine = MountStats.roll(k, 1L, 0f, false);
        for (int i = 0; i < poor.q.length; i++) {
            poor.q[i] = -80;
            fine.q[i] = 80;
        }
        MountStats trained = fine.copy();
        trained.addXp(MountStats.xpFor(10));
        int pPoor = MountStock.value(k, poor), pFine = MountStock.value(k, fine), pTrained = MountStock.value(k, trained);
        c.check("price rises with grade and with level; the stable pays less than it asks",
                pPoor < pFine && pFine < pTrained && MountStock.tradeIn(k, fine) < pFine && MountStock.tradeIn(MountKind.SHADOWMERE, shadow) == 0,
                pPoor + " < " + pFine + " < " + pTrained);
        java.util.List<MountKind> sale = java.util.List.of(MountKind.WHITERUN_BAY, MountKind.CYRODILIC_BAY);
        var w5 = MountStock.offers("skyrim/whiterun:1:2", 5, sale, java.util.Set.of());
        var w5b = MountStock.offers("skyrim/whiterun:1:2", 5, sale, java.util.Set.of());
        var w6 = MountStock.offers("skyrim/whiterun:1:2", 6, sale, java.util.Set.of());
        var other = MountStock.offers("skyrim/riften:9:9", 5, sale, java.util.Set.of());
        boolean same = w5.size() == w5b.size();
        for (int i = 0; same && i < w5.size(); i++) same = w5.get(i).id().equals(w5b.get(i).id()) && sameStats(w5.get(i).stats(), w5b.get(i).stats());
        boolean weekDiffers = false, stableDiffers = false;
        for (int i = 0; i < Math.min(w5.size(), w6.size()); i++) weekDiffers |= !java.util.Arrays.equals(w5.get(i).stats().q, w6.get(i).stats().q);
        for (int i = 0; i < Math.min(w5.size(), other.size()); i++) stableDiffers |= !java.util.Arrays.equals(w5.get(i).stats().q, other.get(i).stats().q);
        var w5sold = MountStock.offers("skyrim/whiterun:1:2", 5, sale, java.util.Set.of(w5.get(0).id()));
        c.check("a stable's stock is the same all week, changes with the week and between stables, and a sold animal is gone",
                same && w5.size() == 3 && weekDiffers && stableDiffers && w5sold.size() == w5.size() - 1, "");
        MountStats tr = MountStats.roll(k, 3L, 0f, false);
        long wk = 7;
        int fee0 = MountStock.trainFee(tr, MountProfile.SPEED);
        MountStock.applyTraining(tr, MountProfile.SPEED, wk);
        int fee1 = MountStock.trainFee(tr, MountProfile.SPEED);
        MountStock.applyTraining(tr, MountProfile.STAMINA, wk);
        c.check("training: the fee climbs with the stat's growth, two sessions a week, then the stable hand is booked",
                fee1 > fee0 && tr.grow[MountProfile.SPEED] == 1 && MountStock.canTrain(tr, MountProfile.HEALTH, wk) == MountStock.Train.WEEK_LIMIT
                        && MountStock.canTrain(tr, MountProfile.HEALTH, wk + 1) == MountStock.Train.OK, fee0 + " -> " + fee1);
        MountStats capTest = MountStats.roll(k, 4L, 0f, false);
        capTest.grow[MountProfile.JUMP] = (byte) capTest.cap(MountProfile.JUMP);
        c.check("training stops at the soft cap of a stat", MountStock.canTrain(capTest, MountProfile.JUMP, 1) == MountStock.Train.CAP, "");

        // ---- Riding perks exist and do something
        pd.setPerkRank("riding.pack_master", 2);
        pd.setPerkRank("riding.endurance", 2);
        pd.setPerkRank("riding.trick_rider", 1);
        c.check("the Riding perks are defined and scale carry, burn and jumps",
                com.nirncraft.perk.Perks.get("riding.horse_sense") != null && com.nirncraft.perk.Perks.get("riding.wild_eye") != null
                        && com.nirncraft.perk.Perks.get("riding.steady_seat") != null && com.nirncraft.perk.Perks.get("riding.pack_master") != null
                        && com.nirncraft.perk.Perks.get("riding.endurance") != null && com.nirncraft.perk.Perks.get("riding.trick_rider") != null
                        && Math.abs(MountGrowth.packMult(fp) - 1.2f) < 1e-4 && MountGrowth.drainMult(fp) < 0.8f && MountGrowth.trickJumpCost(fp) < 1f, "");
        pd.setPerkRank("riding.pack_master", 0);
        pd.setPerkRank("riding.endurance", 0);
        pd.setPerkRank("riding.trick_rider", 0);
        pd.setPerkRank("riding.horse_sense", 0);

        // ---- the breaking minigame's physics: nobody wins by standing still, a steady hand does
        boolean idleNeverWins = true, botWins = true;
        for (int dif = 1; dif <= 5; dif++) {
            for (int seed = 0; seed < 12; seed++) {
                com.nirncraft.fauna.TameBalance idle = new com.nirncraft.fauna.TameBalance(dif, 0.4f, 0, seed * 31L + dif);
                com.nirncraft.fauna.TameBalance.Result r = com.nirncraft.fauna.TameBalance.Result.RUNNING;
                for (int i = 0; i < 20 * 130 && r == com.nirncraft.fauna.TameBalance.Result.RUNNING; i++) r = idle.step(0);
                idleNeverWins &= r != com.nirncraft.fauna.TameBalance.Result.WON;
                if (dif <= 3) {
                    com.nirncraft.fauna.TameBalance bot = new com.nirncraft.fauna.TameBalance(dif, 0.4f, 0, seed * 17L + dif);
                    r = com.nirncraft.fauna.TameBalance.Result.RUNNING;
                    for (int i = 0; i < 20 * 130 && r == com.nirncraft.fauna.TameBalance.Result.RUNNING; i++) {
                        double want = -(bot.marker + bot.vel / 0.1);
                        r = bot.step(want > 0.12 ? 1 : want < -0.12 ? -1 : 0);
                    }
                    botWins &= r == com.nirncraft.fauna.TameBalance.Result.WON;
                }
            }
        }
        com.nirncraft.fauna.TameBalance easy = new com.nirncraft.fauna.TameBalance(1, 0.5f, 0, 1L), hard = new com.nirncraft.fauna.TameBalance(5, 0.5f, 0, 1L);
        com.nirncraft.fauna.TameBalance seated = new com.nirncraft.fauna.TameBalance(5, 0.5f, 2, 1L);
        c.check("breaking in: idling never wins, a steady counter-steer wins, 20-40 s by difficulty, Steady Seat widens the zone",
                idleNeverWins && botWins && easy.duration >= 17 * 20 && hard.duration <= 40 * 20 && hard.duration > easy.duration
                        && seated.zone > hard.zone && seated.grace > hard.grace && hard.amplitude() > easy.amplitude(), "");

        // ---- one active mount: a new one retires the old one, and nothing is lost
        fp.getInventory().clearContent();
        rec.remove("uuid");
        rec.remove("active");
        long gold0 = pd.getGold();
        MountKind first = MountKind.WHITERUN_BAY;
        Mob m1 = Mounts.grant(fp, first, at.west(6), null, true);
        spawned.add(m1);
        fp.getInventory().add(SaddleType.PACK.stack());
        MountGear.equipSaddle(fp, m1, first, find(fp, SaddleType.PACK.item()));
        fp.getInventory().add(new ItemStack(MountItems.LEATHER_BARDING.get()));
        MountGear.equipBarding(fp, m1, first, find(fp, MountItems.LEATHER_BARDING.get()));
        net.minecraft.nbt.ListTag bags = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag slot = new net.minecraft.nbt.CompoundTag();
        slot.putByte("Slot", (byte) 0);
        com.nirncraft.platform.ItemTags.saveInto(new ItemStack(Items.COBBLESTONE, 10), slot);
        bags.add(slot);
        rec.put("bags", bags);
        c.check("a bought mount is the active one, with its own stats in the record", Mounts.active(fp) == first && MountGrowth.recorded(fp, first) != null
                && MountStats.stored(m1) && Mounts.stable(fp).size() == 1, "");
        int cobble0 = count(fp.getInventory().getNonEquipmentItems(), Items.COBBLESTONE);
        com.nirncraft.inventory.BulkStore ovRec = Saddlebags.overflowOf(fp);
        ovRec.add(new ItemStack(Items.IRON_INGOT, 100));
        ovRec.add(bagItem(950, 0.01f, 40));
        Mob m2 = Mounts.grant(fp, MountKind.CYRODILIC_BAY, at.west(9), null, true);
        spawned.add(m2);
        c.check("buying another retires the first: it is gone, the stable holds one regular mount",
                m1.isRemoved() && Mounts.active(fp) == MountKind.CYRODILIC_BAY && Mounts.stable(fp).size() == 1 && Mounts.stable(fp).get(0) == MountKind.CYRODILIC_BAY
                        && MountGrowth.recorded(fp, first) == null, "");
        c.check("its saddlebag items went to the player's inventory, and the record's bags are empty",
                count(fp.getInventory().getNonEquipmentItems(), Items.COBBLESTONE) == cobble0 + 10 && rec.getListOrEmpty("bags").isEmpty(), "");
        c.check("its saddlebag overflow (rows beyond the slots) went to the player too, nothing lost, the record's overflow is empty",
                com.nirncraft.inventory.Inv.count(fp, Items.IRON_INGOT) == 100 && bagKind(fp, 950) == 40 && Saddlebags.overflowOf(fp).isEmpty(),
                com.nirncraft.inventory.Inv.count(fp, Items.IRON_INGOT) + " ingots, " + bagKind(fp, 950) + " sticks");
        c.check("the pack saddle and the barding moved to the new mount (its breed takes them)",
                MountGear.saddle(m2) == SaddleType.PACK && MountGear.barding((Mob) m2).is(MountItems.LEATHER_BARDING.get()), "");
        MountGear.equipSaddle(fp, m2, MountKind.CYRODILIC_BAY, withItem(fp, SaddleType.WAR));
        Mob m3 = Mounts.grant(fp, MountKind.WINDHELM_PINTO, at.west(12), null, true);
        spawned.add(m3);
        c.check("a breed that will not take the war saddle gets a plain one, and the war saddle comes back to the bags",
                MountGear.saddle(m3) == SaddleType.PLAIN && count(fp.getInventory().getNonEquipmentItems(), SaddleType.WAR.item()) == 1, "");

        // ---- taming a wild one retires the bought one the same way
        Horse wild = wildHorse(level, HorseBreed.RIFTEN_CHESTNUT, at.east(10), spawned);
        boolean won = breakIn(fp, wild, SaddleType.PLAIN);
        fp.stopRiding();
        c.check("breaking a wild horse in makes it the mount and retires the old one",
                won && Mounts.active(fp) == MountKind.RIFTEN_CHESTNUT && m3.isRemoved() && Mounts.stable(fp).size() == 1 && !wild.isNoAi()
                        && Mounts.activeEntity(fp) == wild && MountStats.stored(wild), "");
        // and a tamed one is let go when the next one comes: it wanders free, still alive
        MountStats wildStats = MountStats.of(wild, MountKind.RIFTEN_CHESTNUT);
        java.util.UUID wildId = MountToken.idOf(wild);
        Horse wild2 = wildHorse(level, HorseBreed.WHITERUN_BAY, at.east(14), spawned);
        boolean won2 = breakIn(fp, wild2, SaddleType.PLAIN);
        fp.stopRiding();
        c.check("the tamed one it replaced is released: alive, ownerless, untamed",
                won2 && !wild.isRemoved() && Mounts.ownerOf(wild) == null && !wild.isTamed() && Mounts.active(fp) == MountKind.WHITERUN_BAY, "");
        c.check("a released animal keeps its token on it: same stats, same animal id, and anyone can break it in again",
                sameStats(MountStats.of(wild, MountKind.RIFTEN_CHESTNUT), wildStats) && MountToken.idOf(wild).equals(wildId)
                        && MountStats.stored(wild) && MountTaming.wildKind(wild) == MountKind.RIFTEN_CHESTNUT
                        && MountGrowth.tokenOf(fp, MountKind.RIFTEN_CHESTNUT) == null, "");

        // ---- training and selling the active mount
        MountStats act = MountGrowth.active(fp);
        pd.setGold(pd.getGold() + 5000);
        int p0 = act == null ? 0 : act.grow[MountProfile.SPEED];
        boolean t1ok = MountGrowth.train(fp, MountProfile.SPEED), t2ok = MountGrowth.train(fp, MountProfile.STAMINA), t3ok = MountGrowth.train(fp, MountProfile.HEALTH);
        MountStats after = MountGrowth.active(fp);
        c.check("two paid sessions a week train the active mount's record; a third is refused",
                act != null && t1ok && t2ok && !t3ok && after.grow[MountProfile.SPEED] == p0 + 1 && after.trainCount == 2, "");
        int worth = Mounts.tradeInValue(fp);
        long goldBefore = pd.getGold();
        fp.getInventory().clearContent();
        com.nirncraft.inventory.Pack.clear(fp);
        rec.put("bags", bags.copy());
        Saddlebags.overflowOf(fp).add(new ItemStack(Items.IRON_INGOT, 100));
        Saddlebags.overflowOf(fp).add(bagItem(951, 0.01f, 12));
        int sold = Mounts.sell(fp, "skyrim/selftest:0:0");
        c.check("selling returns the bags and tack first, then pays by grade and level; the stable is empty",
                sold == worth && worth > 0 && pd.getGold() == goldBefore + worth && Mounts.active(fp) == null && Mounts.stable(fp).isEmpty()
                        && count(fp.getInventory().getNonEquipmentItems(), Items.COBBLESTONE) == 10 && count(fp.getInventory().getNonEquipmentItems(), Items.SADDLE) >= 1, "paid " + sold);
        c.check("selling returns the overflow rows to the player as well, the record's overflow is empty",
                com.nirncraft.inventory.Inv.count(fp, Items.IRON_INGOT) == 100 && bagKind(fp, 951) == 12 && Saddlebags.overflowOf(fp).isEmpty(),
                com.nirncraft.inventory.Inv.count(fp, Items.IRON_INGOT) + " ingots, " + bagKind(fp, 951) + " sticks");
        tokenChecks(level, fp, c, spawned, at);
        pd.setGold(gold0);
    }

    /**
     * The animal and its stats travel together: a mount sold to a stable is offered there as that very individual and
     * bought by another player with its stats, level, trust, name and coat; the seller's record no longer holds it; the
     * token survives NBT and cannot be had twice.
     */
    private static void tokenChecks(ServerLevel level, FakePlayer fp, Checks c, List<Mob> spawned, BlockPos at) {
        var rec = Mounts.data(fp);
        MountKind pk = MountKind.NIBENESE_GREY;
        String stableKey = "skyrim/selftest:7:7";
        MountStable store = MountStable.get(level.getServer());
        fp.getInventory().clearContent();

        // an animal with a life behind it: levels, trust, a name, a coat, a wound
        Mob animal = Mounts.grant(fp, pk, at.north(6), null, true);
        spawned.add(animal);
        MountStats lived = MountStats.of(animal, pk);
        lived.addXp(900f);
        lived.addTrust(25f);
        lived.trainWeek = 3;
        lived.trainCount = 1;
        MountGrowth.commit(animal, pk, lived, fp, true);
        animal.setCustomName(net.minecraft.network.chat.Component.literal("Biscuit"));
        animal.setHealth(animal.getMaxHealth() * 0.75f);
        net.minecraft.nbt.CompoundTag live = MountToken.capture(animal, pk);
        java.util.UUID animalId = MountToken.id(live);
        MountStats expected = MountToken.stats(live);
        float hp = live.getFloatOr("hp",0F);
        c.check("the animal carries its own id and stats; the record's token is a copy of what the entity is",
                MountStats.stored(animal) && MountToken.idOf(animal).equals(animalId) && expected.level() >= 5
                        && sameStats(MountToken.stats(MountGrowth.tokenOf(fp, pk)), MountStats.of(animal, pk)), "level " + expected.level());

        // the token survives NBT (as it would in the record or the stable's SavedData)
        net.minecraft.nbt.CompoundTag wrapped = new net.minecraft.nbt.CompoundTag();
        wrapped.put("t", live.copy());
        net.minecraft.nbt.CompoundTag back;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(wrapped, out);
            back = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(out.toByteArray()), net.minecraft.nbt.NbtAccounter.create(256L * 1024 * 1024));
        } catch (java.io.IOException e) {
            back = new net.minecraft.nbt.CompoundTag();
        }
        net.minecraft.nbt.CompoundTag revived = back.getCompoundOrEmpty("t");
        c.check("a token survives an NBT round trip: id, kind, stats, level, trust, name, coat, health",
                MountToken.id(revived).equals(animalId) && MountToken.kind(revived) == pk && sameStats(MountToken.stats(revived), expected)
                        && MountToken.name(revived).equals("Biscuit") && revived.getIntOr("coat",0) == live.getIntOr("coat",0)
                        && Math.abs(revived.getFloatOr("hp",0F) - hp) < 1e-4f, "");

        // sold to a stable: the token leaves the seller and the entity, and is in that stable's stock
        int worth = Mounts.tradeInValue(fp);
        fp.getInventory().add(SaddleType.PLAIN.stack());
        long gold = SkyData.get(fp).getGold();
        int paid = Mounts.sell(fp, stableKey);
        java.util.List<net.minecraft.nbt.CompoundTag> held = store.stockOf(stableKey);
        net.minecraft.nbt.CompoundTag sold = held.size() == 1 ? held.get(0) : null;
        c.check("selling removes the token from the seller's record and the entity, and the stable holds that individual",
                paid == worth && worth > 0 && animal.isRemoved() && MountGrowth.tokenOf(fp, pk) == null && Mounts.active(fp) == null
                        && !rec.read("uuid", net.minecraft.core.UUIDUtil.CODEC).isPresent() && !rec.getCompoundOrEmpty(MountGrowth.RECORDS).contains(pk.id)
                        && sold != null && MountToken.id(sold).equals(animalId) && sameStats(MountToken.stats(sold), expected)
                        && MountToken.name(sold).equals("Biscuit") && SkyData.get(fp).getGold() == gold + worth, "paid " + paid);
        c.check("the sold animal's tack is not sold with it: the saddle it wore is back with the seller",
                count(fp.getInventory().getNonEquipmentItems(), Items.SADDLE) >= 1 && sold != null && !sold.contains("saddle"), "");

        // another player buys it from that stable's master
        GameProfile otherProfile = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-mounts-selftest-2".getBytes()), "[MountsTest2]");
        FakePlayer buyer = FakePlayerFactory.get(level, otherProfile);
        buyer.snapTo(at.getX() + 0.5, at.getY(), at.getZ() - 14.5);
        net.minecraft.world.entity.npc.villager.Villager npc = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        npc.snapTo(buyer.getX() + 1, buyer.getY(), buyer.getZ());
        npc.getPersistentData().putBoolean("nirncraft_stable_master", true);
        npc.getPersistentData().putString(StableUi.KEY, stableKey);
        level.addFreshEntity(npc);
        SkyData.get(buyer).setGold(100000L);
        var buyerRec = Mounts.data(buyer);
        buyerRec.remove("stable");
        buyerRec.remove("active");
        buyerRec.remove("uuid");
        int price = MountStock.value(pk, expected);
        String offerId = "t:" + animalId;
        boolean offered = false;
        for (MountStock.Offer o : StableUi.offers(buyer, npc)) offered |= o.id().equals(offerId) && o.price() == price && o.token() != null;
        c.check("the stable offers that individual in its stock list, priced by its grades and level", offered && price > MountStock.value(pk, MountStats.roll(pk, 1L, 0f, false)) / 2, "price " + price);
        StablePackets.Action buy = new StablePackets.Action(npc.getId(), StablePackets.BUY, offerId, price);
        StableUi.handle(buyer, buy);
        Entity bought = Mounts.activeEntity(buyer);
        if (bought != null) spawned.add((Mob) bought);
        MountStats got = bought instanceof Mob bm ? MountStats.of(bm, pk) : null;
        c.check("the buyer gets that very animal: id, stats, level, trust, name, coat, health",
                bought instanceof Mob bm2 && Mounts.active(buyer) == pk && got != null && sameStats(got, expected) && MountToken.idOf(bought).equals(animalId)
                        && bought.hasCustomName() && "Biscuit".equals(bought.getCustomName().getString())
                        && MountToken.capture(bm2, pk).getIntOr("coat",0) == live.getIntOr("coat",0)
                        && Math.abs(bm2.getHealth() / bm2.getMaxHealth() - Math.max(0.5f, hp)) < 0.06f, got == null ? "no mount" : "level " + got.level());
        c.check("it is gone from the stable, and the buyer paid its price", store.stockOf(stableKey).isEmpty() && !store.holds(animalId)
                && SkyData.get(buyer).getGold() == 100000L - price, "gold " + SkyData.get(buyer).getGold());
        // a second click (or a second buyer) finds nothing: no duplicate
        UUID firstEntity = bought == null ? null : bought.getUUID();
        StableUi.handle(buyer, buy);
        FakePlayer third = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("nirncraft-mounts-selftest-3".getBytes()), "[MountsTest3]"));
        third.snapTo(buyer.getX(), buyer.getY(), buyer.getZ() + 1);
        SkyData.get(third).setGold(100000L);
        StableUi.handle(third, buy);
        c.check("a second click or a second buyer cannot get it again: one animal, one holder",
                Mounts.activeEntity(buyer) != null && Mounts.activeEntity(buyer).getUUID().equals(firstEntity) && Mounts.active(third) == null
                        && SkyData.get(buyer).getGold() == 100000L - price && SkyData.get(third).getGold() == 100000L, "");

        // the buyer's record holds the token of the active mount only
        c.check("the buyer's record holds the token of the active mount and nothing else",
                MountGrowth.tokenOf(buyer, pk) != null && MountToken.id(MountGrowth.tokenOf(buyer, pk)).equals(animalId)
                        && buyerRec.getCompoundOrEmpty(MountGrowth.RECORDS).keySet().size() == 1, "");

        // a stable keeps twelve; the oldest overflow quietly leaves for the pool, and every token is in one place
        java.util.List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < MountStable.PER_STABLE + 2; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            store.consign("skyrim/selftest:overflow", MountToken.create(MountKind.WHITERUN_BAY, MountStats.roll(MountKind.WHITERUN_BAY, i, 0f, false), null, id));
        }
        boolean bounded = store.stockOf("skyrim/selftest:overflow").size() == MountStable.PER_STABLE;
        boolean oldestInPool = store.pool().stream().anyMatch(t -> MountToken.id(t).equals(ids.get(0)))
                && store.pool().stream().anyMatch(t -> MountToken.id(t).equals(ids.get(1)));
        boolean newest = store.stockOf("skyrim/selftest:overflow").stream().anyMatch(t -> MountToken.id(t).equals(ids.get(ids.size() - 1)));
        c.check("a stable keeps 12 sold animals; the oldest overflow goes to the sold-on pool", bounded && oldestInPool && newest && store.holds(ids.get(0)), "");
        for (UUID id : ids) {
            if (store.take("skyrim/selftest:overflow", id) == null) store.takeFromPool(id);
        }
        // an animal out of reach gets its fate once, when it loads
        UUID far = UUID.randomUUID();
        store.fate(far, MountStable.Fate.DELETE);
        c.check("an unloaded animal's fate is remembered and consumed once", store.takeFate(far) == MountStable.Fate.DELETE && store.takeFate(far) == null, "");

        // legends: their token stays in the record when they step aside, and comes back with the same animal
        Mob legend = Mounts.grant(fp, MountKind.SHADOWMERE, at.north(9), null, true);
        spawned.add(legend);
        UUID legendId = legend == null ? null : MountToken.idOf(legend);
        MountStats lore = legend == null ? null : MountStats.of(legend, MountKind.SHADOWMERE);
        if (legend != null) {
            lore.addXp(300f);
            MountGrowth.commit(legend, MountKind.SHADOWMERE, lore, fp, true);
        }
        Mounts.dismissActive(fp);
        c.check("a legend's token stays in the record when it is not an entity", legend != null && MountGrowth.tokenOf(fp, MountKind.SHADOWMERE) != null
                && MountToken.id(MountGrowth.tokenOf(fp, MountKind.SHADOWMERE)).equals(legendId), "");
        Mounts.switchTo(fp, MountKind.SHADOWMERE, at.north(9));
        Entity back2 = Mounts.activeEntity(fp);
        if (back2 instanceof Mob bm) spawned.add(bm);
        c.check("brought out again it is the same animal with the stats it had", back2 instanceof Mob && MountToken.idOf(back2).equals(legendId)
                && lore != null && sameStats(MountStats.of(back2, MountKind.SHADOWMERE), lore), "");

        // clean up the second and third players
        for (FakePlayer fpx : new FakePlayer[]{buyer, third}) {
            Entity e = Mounts.activeEntity(fpx);
            if (e != null) e.discard();
            var r = Mounts.data(fpx);
            for (String key : new String[]{"stable", "active", "uuid", "tokens", "bags", "saddles", "barding", "stock_sold", "cooldown", "mstats"}) r.remove(key);
            fpx.getInventory().clearContent();
        }
        npc.discard();
        Mounts.dismissActive(fp);
        rec.remove("tokens");
        rec.remove("stable");
    }

    /** A distinct, light stick: the bag tests' unit of cargo ({@code mbt} = which kind, {@code kg} = weight of one). */
    private static ItemStack bagItem(int kindId, float kg, int count) {
        ItemStack s = new ItemStack(Items.STICK, count);
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putInt("mbt", kindId));
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putFloat(ItemWeights.WEIGHT_NBT, kg));
        return s;
    }

    private static boolean isBagKind(ItemStack s, int kindId) {
        return s.is(Items.STICK) && com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).contains("mbt") && com.nirncraft.inventory.ItemData.custom(s).getIntOr("mbt",0) == kindId;
    }

    /** How many of a bag-test kind the player carries (slots, offhand, pack). */
    private static int bagKind(FakePlayer fp, int kindId) {
        return com.nirncraft.inventory.Inv.count(fp, s -> isBagKind(s, kindId));
    }

    /** Every bag-test stick: carried, in the bag's slots and in its overflow. */
    private static long allSticks(FakePlayer fp, Saddlebags.Bag bag) {
        long n = com.nirncraft.inventory.Inv.count(fp, s -> s.is(Items.STICK) && com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).contains("mbt"));
        for (int i = Saddlebags.GEAR; i < 54; i++) {
            ItemStack s = bag.getItem(i);
            if (s.is(Items.STICK) && com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).contains("mbt")) n += s.getCount();
        }
        for (ItemStack s : bag.overflow().entries()) if (s.is(Items.STICK) && com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).contains("mbt")) n += s.getCount();
        return n;
    }

    /**
     * The saddlebags' overflow, driven through the real Skyrim take/store handlers: 80 distinct kinds into 52 cargo
     * slots (weight, not slots, limits), the weight rule over slots and overflow together, NBT round trip and reopen,
     * a take that is grouped over slot and overflow, a shrinking saddle that spills (rows first, nothing deleted) and
     * handing everything to the player.
     */
    private static void bagOverflow(FakePlayer fp, Checks c, Horse bay, MountKind kind) {
        fp.getInventory().clearContent();
        com.nirncraft.inventory.Pack.clear(fp);
        Saddlebags.emptyToPlayer(fp);
        fp.getInventory().clearContent();
        com.nirncraft.inventory.Pack.clear(fp);
        MountGear.equipSaddle(fp, bay, kind, withItem(fp, SaddleType.PACK));
        fp.getInventory().clearContent();
        Saddlebags.Bag bag = new Saddlebags.Bag(fp, bay, kind);
        SaddlebagMenu menu = new SaddlebagMenu(78, fp.getInventory(), bag);
        fp.containerMenu = menu;
        float cap = bag.capacity();
        float w = cap / 500f;
        int kinds = 80, each = 4;
        for (int i = 0; i < kinds; i++) com.nirncraft.inventory.Inv.give(fp, bagItem(i, w, each));
        c.check("bag overflow: the saddle has room to test with", cap > 0f && w > 0f, cap + " kg");
        com.nirncraft.vitals.ActionHandler.handleContainerStore(fp, 78, -1, true);
        int slotsUsed = 0;
        for (int i = Saddlebags.GEAR; i < 54; i++) if (!bag.getItem(i).isEmpty()) slotsUsed++;
        c.check("bag overflow: 80 distinct kinds go into 52 cargo slots, the rest into the overflow, nothing stays behind",
                slotsUsed == 52 && bag.overflow().size() == kinds - 52 && allSticks(fp, bag) == (long) kinds * each
                        && com.nirncraft.inventory.Inv.count(fp, s -> s.is(Items.STICK)) == 0,
                slotsUsed + " slots, " + bag.overflow().size() + " overflow rows, " + allSticks(fp, bag) + " items");
        c.check("bag overflow: the load counts slots and overflow (320 of 500 units)", Math.abs(bag.cargoWeight() - w * kinds * each) < 0.05f,
                bag.cargoWeight() + " of " + cap + " kg");

        // the weight rule over both: a too heavy item is refused outright, light things only up to the last kilogram
        ItemStack heavy = bagItem(900, cap, 3);
        int heavyTaken = new com.nirncraft.inventory.ContainerOverflow.Bound(bag.overflow(), bag).add(heavy);
        c.check("bag overflow: an item heavier than the room left is refused (room 0), the stack is untouched",
                heavyTaken == 0 && heavy.getCount() == 3 && bag.overflowRoom(heavy) == 0, heavyTaken + " taken");
        com.nirncraft.inventory.Inv.give(fp, bagItem(901, w, 1000));
        com.nirncraft.vitals.ActionHandler.handleContainerStore(fp, 78, -1, true);
        int stored = 0;
        for (int i = Saddlebags.GEAR; i < 54; i++) if (isBagKind(bag.getItem(i), 901)) stored += bag.getItem(i).getCount();
        for (ItemStack e : bag.overflow().entries()) if (isBagKind(e, 901)) stored += e.getCount();
        int left = bagKind(fp, 901);
        c.check("bag overflow: Store All of 1000 light items takes only what the saddle carries, the rest stays with the player",
                bag.cargoWeight() <= cap + 0.02f && cap - bag.cargoWeight() < w + 0.02f && stored > 0 && left > 0 && stored + left == 1000,
                "stored " + stored + ", kept " + left + ", load " + bag.cargoWeight() + "/" + cap);

        // persistence: the record's list survives NBT (compressed bytes) and a reopened bag sees the same rows
        com.nirncraft.inventory.BulkStore snap = com.nirncraft.inventory.BulkStore.loaded(bag.overflow().save(),
                com.nirncraft.inventory.ContainerOverflow.MAX_ENTRIES, com.nirncraft.inventory.ContainerOverflow.MAX_PER_ENTRY, null);
        net.minecraft.nbt.CompoundTag saved = SkyData.get(fp).module(Saddlebags.OVERFLOW_MODULE).copy();
        com.nirncraft.inventory.BulkStore back = null;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.CompoundTag wrap = new net.minecraft.nbt.CompoundTag();
            wrap.put("m", saved);
            net.minecraft.nbt.NbtIo.writeCompressed(wrap, bos);
            back = com.nirncraft.inventory.BulkStore.loaded(net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bos.toByteArray()), net.minecraft.nbt.NbtAccounter.create(256L * 1024 * 1024)).getCompoundOrEmpty("m"),
                    com.nirncraft.inventory.ContainerOverflow.MAX_ENTRIES, com.nirncraft.inventory.ContainerOverflow.MAX_PER_ENTRY, null);
        } catch (java.io.IOException e) {
            c.check("bag overflow: NBT round trip", false, e.toString());
        }
        c.check("bag overflow: the record's overflow survives saving to compressed NBT and loading",
                back != null && back.sameAs(snap) && back.size() == snap.size() && snap.size() > 0, back == null ? "no read" : back.size() + " rows");
        net.minecraft.nbt.CompoundTag syncTag = com.nirncraft.network.CorePackets.SyncData.of(SkyData.get(fp)).tag();
        c.check("bag overflow: the overflow is server data, not in the player data sync",
                !syncTag.getCompoundOrEmpty("modules").contains(Saddlebags.OVERFLOW_MODULE), "");
        menu.removed(fp);
        fp.containerMenu = fp.inventoryMenu; // closed for real: the new bag reads the record, not the old menu
        Saddlebags.Bag again = new Saddlebags.Bag(fp, bay, kind);
        c.check("bag overflow: reopening the bags (or relogging) shows the same rows and slots",
                again.overflow().sameAs(snap) && allSticks(fp, again) == (long) kinds * each + 1000,
                again.overflow().size() + " rows, " + allSticks(fp, again) + " items");

        // a shrinking saddle spills the overflow rows first and deletes nothing
        long beforeSpill = allSticks(fp, again);
        SaddlebagMenu menu2 = new SaddlebagMenu(79, fp.getInventory(), again);
        fp.containerMenu = menu2;
        Saddlebags.fit(fp, cap * 0.25f);
        c.check("bag overflow: a saddle that carries a quarter spills the surplus into the bags, never deletes",
                again.cargoWeight() <= cap * 0.25f + 0.02f && allSticks(fp, again) == beforeSpill
                        && com.nirncraft.inventory.Inv.count(fp, s -> s.is(Items.STICK)) > 0,
                again.cargoWeight() + " kg left, " + allSticks(fp, again) + " of " + beforeSpill + " items");
        Saddlebags.fit(fp, 0f);
        c.check("bag overflow: no saddle means empty bags and an empty overflow, everything carried",
                again.cargoWeight() < 0.001f && again.overflow().isEmpty() && allSticks(fp, again) == beforeSpill,
                again.cargoWeight() + " kg, " + allSticks(fp, again) + " items");

        // grouped take: a kind that is in a slot AND in the overflow comes out in one request
        fp.getInventory().clearContent();
        com.nirncraft.inventory.Pack.clear(fp);
        again.setItem(Saddlebags.GEAR, bagItem(700, w, 5));
        again.overflow().add(bagItem(700, w, 7));
        com.nirncraft.vitals.ActionHandler.handleContainerTake(fp, 79, Saddlebags.GEAR, false);
        c.check("bag overflow: one take of a slot row also takes that item's overflow row (5 + 7)",
                bagKind(fp, 700) == 12 && again.getItem(Saddlebags.GEAR).isEmpty() && again.overflow().isEmpty(),
                bagKind(fp, 700) + " taken, overflow rows " + again.overflow().size());
        // an overflow-only row is taken by its overflow slot id; the freed slot is not refilled by anything else
        again.overflow().add(bagItem(701, w, 9));
        for (int i = Saddlebags.GEAR; i < 54; i++) again.setItem(i, bagItem(1000 + i, w, 1));
        again.overflow().add(bagItem(702, w, 30));
        int idx = again.overflow().indexOf(bagItem(702, w, 1));
        com.nirncraft.vitals.ActionHandler.handleContainerTake(fp, 79, com.nirncraft.inventory.ContainerOverflow.SLOT_BASE + idx, false);
        c.check("bag overflow: an overflow-only row is taken by its overflow slot id",
                bagKind(fp, 702) == 30 && again.overflow().indexOf(bagItem(702, w, 1)) < 0 && bagKind(fp, 701) == 0, bagKind(fp, 702) + " taken");
        menu2.removed(fp);

        // everything back to the player (what retiring, selling or replacing a mount does)
        Saddlebags.overflowOf(fp).add(bagItem(703, w, 2500));
        long carriedBefore = com.nirncraft.inventory.Inv.count(fp, s -> s.is(Items.STICK));
        long inBags = 0;
        for (ItemStack s : Saddlebags.overflowOf(fp).entries()) inBags += s.getCount();
        for (int i = 0; i < 54 - Saddlebags.GEAR; i++) {
            net.minecraft.nbt.ListTag l = Mounts.data(fp).getListOrEmpty("bags");
            if (i < l.size()) inBags += com.nirncraft.inventory.ItemData.parse(l.getCompoundOrEmpty(i)).getCount();
        }
        Saddlebags.emptyToPlayer(fp);
        long carriedAfter = com.nirncraft.inventory.Inv.count(fp, s -> s.is(Items.STICK));
        c.check("bag overflow: emptying the bags hands over slots and overflow rows (with their int counts) to the player",
                carriedAfter == carriedBefore + inBags && Saddlebags.overflowOf(fp).isEmpty() && Mounts.data(fp).getListOrEmpty("bags").isEmpty(),
                carriedBefore + " + " + inBags + " = " + carriedAfter);
        fp.containerMenu = fp.inventoryMenu;
        fp.getInventory().clearContent();
        com.nirncraft.inventory.Pack.clear(fp);
        MountGear.equipSaddle(fp, bay, kind, SaddleType.PLAIN.stack());
    }

    private static ItemStack withItem(FakePlayer fp, SaddleType t) {
        fp.getInventory().add(t.stack());
        return find(fp, t.item());
    }

    private static ItemStack find(FakePlayer fp, net.minecraft.world.item.Item item) {
        for (ItemStack s : fp.getInventory().getNonEquipmentItems()) if (s.is(item)) return s;
        return ItemStack.EMPTY;
    }
}
