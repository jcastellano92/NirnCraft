package com.skycraft.protect;

import com.mojang.authlib.GameProfile;
import com.skycraft.Skycraft;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.homestead.HomesteadData;
import com.skycraft.network.CorePackets;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.plan.Cities;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.bus.api.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless self-test of the world protection: a fake survival player breaks, places, chops, mines and gets
 * exploded at in a real town and in the wilderness, and the verdicts of the whole handler chain (not just this
 * module's) are read back from the events. Every block it touches is restored and its test homestead removed.
 * Runs as the autorun task {@code protect} and as {@code /skycraft-protect selftest}.
 */
public final class ProtectSelfTest {
    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("skycraft-protect-selftest".getBytes()), "[ProtectTest]");
    private static final GameProfile STRANGER = new GameProfile(UUID.nameUUIDFromBytes("skycraft-protect-stranger".getBytes()), "[ProtectStranger]");

    private ProtectSelfTest() {}

    /** Results: one line per check, {@link #summary()} for the QA log. */
    public record Report(int passed, int total, List<String> lines, List<String> failed) {
        public int problems() {
            return total - passed;
        }

        public String summary() {
            return "Protect selftest: " + passed + "/" + total + " checks passed, " + problems() + " problems"
                    + (failed.isEmpty() ? "" : " (" + String.join("; ", failed) + ")");
        }
    }

    public static void registerAutorun() {
        PerfAutorun.register("protect", (t, done) -> {
            Report r;
            try {
                r = run(t.server());
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Protect selftest crashed", e);
                r = new Report(0, 1, List.of(), List.of("crashed: " + e));
            }
            for (String l : r.lines()) Skycraft.LOGGER.info("[protect] {}", l);
            Skycraft.LOGGER.info("{}", r.summary());
            done.run();
        });
    }

    /** Collects checks. */
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
        List<Runnable> cleanup = new ArrayList<>();
        ServerLevel level = server.overworld();
        FakePlayer player = FakePlayerFactory.get(level, PROFILE);
        FakePlayer stranger = FakePlayerFactory.get(level, STRANGER);
        try {
            runChecks(server, level, player, stranger, c, cleanup);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Protect selftest threw", e);
            c.check("selftest ran to the end", false, e.toString());
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                try {
                    cleanup.get(i).run();
                } catch (RuntimeException e) {
                    Skycraft.LOGGER.warn("Protect selftest cleanup step failed", e);
                }
            }
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            Protect.forget(player.getUUID());
            Protect.forget(stranger.getUUID());
        }
        return new Report(c.passed, c.total, c.lines, c.failed);
    }

    private static void runChecks(MinecraftServer server, ServerLevel level, FakePlayer player, FakePlayer stranger,
                                  Checks c, List<Runnable> cleanup) {
        Place town = Places.byId("whiterun").orElseGet(() -> Places.all().stream()
                .filter(p -> p.type().isSettlement() && !"homestead".equals(p.variant())).findFirst().orElse(null));
        if (town == null) {
            c.check("a town to test in", false, "no settlement place is defined");
            return;
        }
        boolean wasEnabled = Protect.enabled();
        if (!wasEnabled) {
            c.check("protection enabled", false, "skycraft-protect.toml has enabled=false: nothing to test");
            return;
        }

        // ---- the places: the town, a house of a homestead inside it (claim precedence), the wilderness
        BlockPos T = surface(level, town.x(), town.z(), 1);
        BlockPos C = surface(level, town.x() + 42, town.z(), 1);
        BlockPos W = findWilderness(level, town);
        c.check("town footprint is a settlement", Footprints.at(level, T) == Footprints.Kind.SETTLEMENT, "whiterun centre " + fmt(T)
                + " -> " + Footprints.at(level, T));
        if (W == null) {
            c.check("a wilderness spot", false, "no free spot within reach of " + town.id());
            return;
        }
        c.check("wilderness footprint is none", Footprints.at(level, W) == Footprints.Kind.NONE, fmt(W) + " -> " + Footprints.at(level, W));

        for (FakePlayer fake : List.of(player, stranger)) {
            // a fake player has no connection: give it one that queues packets, so every handler may message it
            if (fake.connection == null) new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), fake, net.minecraft.server.network.CommonListenerCookie.createInitial(fake.getGameProfile(), false));
        }
        player.setPos(T.getX() + 0.5, T.getY(), T.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));

        // ---- 1. breaking in a town: glass and a bed are never breakable
        c.check("break glass in town -> cancelled", breakCancelled(level, player, T, Blocks.GLASS.defaultBlockState()), "glass at " + fmt(T));
        c.check("break glass pane in town -> cancelled", breakCancelled(level, player, T.above(), Blocks.GLASS_PANE.defaultBlockState()), "pane");
        c.check("break a bed in town -> cancelled", breakCancelled(level, player, T, com.skycraft.worldgen.VanillaBlocks.RED_BED.defaultBlockState()), "red bed");
        c.check("break a chest in town -> cancelled", breakCancelled(level, player, T, Blocks.CHEST.defaultBlockState()), "chest");
        c.check("break a torch in the wilderness -> cancelled", breakCancelled(level, player, W, Blocks.TORCH.defaultBlockState()), "torch");

        // ---- 2. the designated harvests
        Block veinBlock = BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(Skycraft.MODID, "orichalcum_ore")).orElse(Blocks.IRON_ORE);
        c.check("mine an ore vein with a pickaxe -> allowed", !breakCancelled(level, player, W, veinBlock.defaultBlockState()),
                BuiltInRegistries.BLOCK.getKey(veinBlock) + " at " + fmt(W));
        c.check("mine an ore vein in town -> cancelled", breakCancelled(level, player, T, Blocks.IRON_ORE.defaultBlockState()), "iron ore in the plaza");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
        c.check("chop a tree away from roads and towns -> allowed", !breakCancelled(level, player, W, Blocks.OAK_LOG.defaultBlockState()),
                "oak log at " + fmt(W));
        c.check("chop a tree inside a settlement footprint -> cancelled", breakCancelled(level, player, T, Blocks.OAK_LOG.defaultBlockState()),
                "oak log at " + fmt(T));
        // (a real break would be turned into a direct harvest by the survival module: ask the rule itself)
        c.check("pick a wild flower in town (decision) -> allowed", Protect.checkBreak(player, level, T, Blocks.POPPY.defaultBlockState()).allowed(), "poppy");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // ---- 3. terrain follows the configured mode, never inside a town
        c.check("dig terrain in town (decision) -> denied", !Protect.checkBreak(player, level, T, Blocks.DIRT.defaultBlockState()).allowed(),
                Protect.checkBreak(player, level, T, Blocks.DIRT.defaultBlockState()).reason());
        Protect.Decision wild = Protect.checkBreak(player, level, W, Blocks.STONE.defaultBlockState());
        boolean strict = "homestead".equals(ProtectConfig.terrainMode());
        c.check("dig terrain in the wild follows terrainDigging=" + ProtectConfig.terrainMode(), wild.allowed() != strict, wild.reason());

        // ---- 4. placing
        c.check("place glass in the wilderness -> cancelled", placeCancelled(level, player, W, Blocks.GLASS.defaultBlockState()), "glass");
        c.check("place a bedroll anywhere -> allowed", !placeCancelled(level, player, W,
                com.skycraft.survival.SurvivalRegistry.BEDROLL.get().defaultBlockState()), "skycraft:bedroll");

        // ---- 5. a homestead: its builder may, a stranger may not
        HomesteadData homes = HomesteadData.get(server);
        homes.addClaim(player, C);
        cleanup.add(() -> homes.removeClaim(player.getUUID()));
        cleanup.add(() -> PlacedBlocks.mark(level, C, false));
        stranger.setPos(C.getX() + 0.5, C.getY(), C.getZ() + 0.5);
        player.setPos(C.getX() + 0.5, C.getY(), C.getZ() + 0.5);
        // owner decision (playtest 4): digging at home still needs the Mining perks (homesteadFreeDiggingWithoutPerks=false)
        boolean freeDig = ProtectConfig.HOMESTEAD_FREE_DIGGING.get();
        c.check("break stone in your homestead without the Mining perk follows homesteadFreeDiggingWithoutPerks=" + freeDig,
                breakCancelled(level, player, C, Blocks.STONE.defaultBlockState()) != freeDig,
                "stone at " + fmt(C) + ", claim inside a town");
        c.check("break glass in your homestead -> allowed", !breakCancelled(level, player, C, Blocks.GLASS.defaultBlockState()), "glass");
        c.check("break stone in someone else's homestead -> cancelled", breakCancelled(level, stranger, C, Blocks.STONE.defaultBlockState()),
                "stranger");
        // wild plants are everyone's, on anyone's land; a crop somebody planted at home is not
        c.check("pick a wild flower in someone else's homestead (decision) -> allowed",
                Protect.checkBreak(stranger, level, C, Blocks.POPPY.defaultBlockState()).allowed(), "poppy");
        PlacedBlocks.mark(level, C, true);
        c.check("pick a planted crop in someone else's homestead (decision) -> denied",
                !Protect.checkBreak(stranger, level, C, Blocks.POPPY.defaultBlockState()).allowed(), "planted poppy");
        PlacedBlocks.mark(level, C, false);
        c.check("place planks in your homestead -> allowed", !placeCancelled(level, player, C, Blocks.OAK_PLANKS.defaultBlockState()), "planks");
        c.check("place planks in someone else's homestead -> cancelled", placeCancelled(level, stranger, C, Blocks.OAK_PLANKS.defaultBlockState()),
                "stranger");
        c.check("a player's own TNT in their homestead leaves every block (damage only)", explosionKeepsBlocks(level, C, player),
                "synthetic detonation lit by the owner");
        homes.removeClaim(player.getUUID());

        // ---- 6. the op bypass
        Protect.toggleBypass(player.getUUID());
        c.check("op bypass breaks glass in town -> allowed", !breakCancelled(level, player, T, Blocks.GLASS.defaultBlockState()), "bypass on");
        Protect.toggleBypass(player.getUUID());

        // ---- 7. griefing
        player.setPos(T.getX() + 0.5, T.getY(), T.getZ() + 0.5);
        c.check("explosion in town (event) leaves every block", explosionKeepsBlocks(level, T), "synthetic detonation of 3 blocks");
        c.check("explosion in town (real) changes no block", realExplosionKeepsBlocks(level, T), "8 glass blocks next to a power 4 blast");
        EntityMobGriefingEvent zombie = new EntityMobGriefingEvent(level, net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT));
        NeoForge.EVENT_BUS.post(zombie);
        c.check("zombies may not grief (doors, blocks)", !zombie.canGrief(), "result " + zombie.canGrief());
        EntityMobGriefingEvent villager = new EntityMobGriefingEvent(level, net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT));
        NeoForge.EVENT_BUS.post(villager);
        c.check("villagers keep their farm work", villager.canGrief(), "result " + villager.canGrief());

        // ---- 8. taking things is never blocked
        player.setPos(T.getX() + 0.5, T.getY(), T.getZ() + 0.5);
        ItemEntity item = new ItemEntity(level, T.getX() + 0.5, T.getY() + 0.2, T.getZ() + 0.5, new ItemStack(Items.STICK, 3));
        level.addFreshEntity(item);
        cleanup.add(item::discard);
        ActionHandler.handle(player, CorePackets.Action.TAKE_WORLD_ITEM, item.getId());
        int sticks = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.STICK)) sticks += player.getInventory().getItem(i).getCount();
        }
        c.check("pick an item up off the ground (Take) -> allowed", sticks == 3 && item.isRemoved(), sticks + " sticks in the bags");
    }

    // ------------------------------------------------------------------ helpers

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    /** The first free block above the ground of a column (loads the chunk). */
    private static BlockPos surface(ServerLevel level, int x, int z, int above) {
        level.getChunk(x >> 4, z >> 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + above, z);
        // a free, block-entity-free spot: the test puts blocks here and puts the old ones back
        while (p.getY() < (level.getMaxY()+1) - 40 && (!level.getBlockState(p).isAir() || level.getBlockEntity(p) != null)) p.move(0, 1, 0);
        return p.immutable();
    }

    /** A column well away from the town, its roads and every other footprint. */
    private static BlockPos findWilderness(ServerLevel level, Place town) {
        int reach;
        try {
            reach = Math.max(Cities.reach(town), Cities.layoutRadius(town));
        } catch (RuntimeException e) {
            reach = town.radius();
        }
        for (int ring = 0; ring < 3; ring++) {
            int dist = reach + 70 + ring * 90;
            for (int a = 0; a < 12; a++) {
                double ang = a * Math.PI / 6;
                int x = town.x() + (int) Math.round(Math.cos(ang) * dist);
                int z = town.z() + (int) Math.round(Math.sin(ang) * dist);
                if (Footprints.at(level, new BlockPos(x, 90, z)).built()) continue; // cheap screen, no chunk load
                BlockPos p = surface(level, x, z, 1);
                if (!Footprints.at(level, p).built()) return p;
            }
        }
        return null;
    }

    /** Puts {@code state} at {@code pos}, posts a break event for the player and restores the block afterwards. */
    private static boolean breakCancelled(ServerLevel level, FakePlayer player, BlockPos pos, BlockState state) {
        BlockState old = level.getBlockState(pos);
        level.setBlock(pos, state, 18);
        try {
            net.neoforged.neoforge.event.level.block.BreakBlockEvent event = new net.neoforged.neoforge.event.level.block.BreakBlockEvent(level, pos, state, player);
            NeoForge.EVENT_BUS.post(event);
            return event.isCanceled();
        } finally {
            level.setBlock(pos, old, 18);
            if (old.isAir()) level.removeBlockEntity(pos);
            PlacedBlocks.mark(level, pos, false);
        }
    }

    private static boolean placeCancelled(ServerLevel level, FakePlayer player, BlockPos pos, BlockState state) {
        BlockState old = level.getBlockState(pos);
        level.setBlock(pos, state, 18);
        try {
            BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
            BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(snapshot, Blocks.STONE.defaultBlockState(), player);
            NeoForge.EVENT_BUS.post(event);
            return event.isCanceled();
        } finally {
            level.setBlock(pos, old, 18);
            if (old.isAir()) level.removeBlockEntity(pos);
            PlacedBlocks.mark(level, pos, false);
        }
    }

    /** A detonation whose list names three blocks of the town: the handler must empty it. */
    private static boolean explosionKeepsBlocks(ServerLevel level, BlockPos at) {
        return explosionKeepsBlocks(level, at, null);
    }

    /** The same, lit by {@code igniter} (a player's own TNT): it must leave every block as well, at home too. */
    private static boolean explosionKeepsBlocks(ServerLevel level, BlockPos at, net.minecraft.world.entity.Entity igniter) {
        List<BlockPos> blocks = new ArrayList<>(List.of(at.below(), at.east(), at.north()));
        net.minecraft.world.level.ServerExplosion explosion = new net.minecraft.world.level.ServerExplosion(level, igniter, null, null, net.minecraft.world.phys.Vec3.atCenterOf(at), 3f, false, Explosion.BlockInteraction.DESTROY);
        ExplosionEvent.Detonate event = new ExplosionEvent.Detonate(level, explosion, new ArrayList<>(), blocks);
        NeoForge.EVENT_BUS.post(event);
        return event.getAffectedBlocks().isEmpty();
    }

    /** A real blast (power 4, TNT rules, no igniter) beside a cube of glass high above the town centre. */
    private static boolean realExplosionKeepsBlocks(ServerLevel level, BlockPos at) {
        BlockPos base = at.above(24);
        List<BlockPos> cube = new ArrayList<>();
        List<BlockState> before = new ArrayList<>();
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    cube.add(p);
                    before.add(level.getBlockState(p));
                    level.setBlock(p, Blocks.GLASS.defaultBlockState(), 18);
                }
            }
        }
        try {
            level.explode(null, base.getX() + 2.5, base.getY() + 0.5, base.getZ() + 0.5, 4.0f, Level.ExplosionInteraction.TNT);
            for (BlockPos p : cube) {
                if (!level.getBlockState(p).is(Blocks.GLASS)) return false;
            }
            return true;
        } finally {
            for (int i = 0; i < cube.size(); i++) level.setBlock(cube.get(i), before.get(i), 18);
        }
    }
}
