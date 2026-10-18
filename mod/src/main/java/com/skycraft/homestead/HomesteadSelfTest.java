package com.skycraft.homestead;

import com.mojang.authlib.GameProfile;
import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.inventory.Inv;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.protect.Footprints;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import com.skycraft.world.WorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless self-test of the homestead rules (docs/HOMESTEAD.md): the land rules of founding, claim spacing, access
 * levels and per-block overrides, build mode (carrying a chest without losing what is in it), the map entries other
 * players get (hidden compass point, discovery, rename, removal), expansion and the build deny-list. Fake survival
 * players only; every block it touches is restored and its claims removed. Autorun task {@code homestead} and
 * {@code /skycraft-homestead selftest}.
 */
public final class HomesteadSelfTest {
    private static final GameProfile OWNER = new GameProfile(UUID.nameUUIDFromBytes("skycraft-homestead-owner".getBytes()), "[HomeOwner]");
    private static final GameProfile GUEST = new GameProfile(UUID.nameUUIDFromBytes("skycraft-homestead-guest".getBytes()), "[HomeGuest]");
    private static final GameProfile NEIGHBOUR = new GameProfile(UUID.nameUUIDFromBytes("skycraft-homestead-neighbour".getBytes()), "[HomeNeighbour]");

    private HomesteadSelfTest() {}

    public record Report(int passed, int total, List<String> lines, List<String> failed) {
        public int problems() {
            return total - passed;
        }

        public String summary() {
            return "Homestead selftest: " + passed + "/" + total + " checks passed, " + problems() + " problems"
                    + (failed.isEmpty() ? "" : " (" + String.join("; ", failed) + ")");
        }
    }

    public static void registerAutorun() {
        PerfAutorun.register("homestead", (t, done) -> {
            Report r;
            try {
                r = run(t.server());
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Homestead selftest crashed", e);
                r = new Report(0, 1, List.of(), List.of("crashed: " + e));
            }
            for (String l : r.lines()) Skycraft.LOGGER.info("[homestead] {}", l);
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
        List<Runnable> cleanup = new ArrayList<>();
        ServerLevel level = server.overworld();
        FakePlayer owner = FakePlayerFactory.get(level, OWNER);
        FakePlayer guest = FakePlayerFactory.get(level, GUEST);
        FakePlayer neighbour = FakePlayerFactory.get(level, NEIGHBOUR);
        try {
            for (FakePlayer fake : List.of(owner, guest, neighbour)) {
                if (fake.connection == null) new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), fake, net.minecraft.server.network.CommonListenerCookie.createInitial(fake.getGameProfile(), false));
            }
            runChecks(server, level, owner, guest, neighbour, c, cleanup);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Homestead selftest threw", e);
            c.check("selftest ran to the end", false, e.toString());
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                try {
                    cleanup.get(i).run();
                } catch (RuntimeException e) {
                    Skycraft.LOGGER.warn("Homestead selftest cleanup step failed", e);
                }
            }
            for (FakePlayer p : List.of(owner, guest, neighbour)) {
                p.getInventory().clearContent();
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
            HomesteadBuildMode.clearAll();
        }
        return new Report(c.passed, c.total, c.lines, c.failed);
    }

    private static void runChecks(MinecraftServer server, ServerLevel level, FakePlayer owner, FakePlayer guest, FakePlayer neighbour,
                                  Checks c, List<Runnable> cleanup) {
        HomesteadData homes = HomesteadData.get(server);
        Place town = Places.byId("whiterun").orElseGet(() -> Places.all().stream()
                .filter(p -> p.type().isSettlement() && p.type() != PlaceType.FARM && !"homestead".equals(p.variant())).findFirst().orElse(null));
        if (town == null) {
            c.check("a town to test near", false, "no settlement place defined");
            return;
        }

        // ---- 1. the land rules
        BlockPos townCentre = surface(level, town.x(), town.z());
        HomesteadData.Denial inTown = homes.checkCanClaim(owner, townCentre);
        c.check("founding in the middle of a town -> refused", inTown != null, inTown == null ? "allowed" : inTown.key());
        Place stone = Places.all().stream().filter(p -> p.type() == PlaceType.STANDING_STONE || p.type() == PlaceType.WORD_WALL
                || p.type() == PlaceType.DRAGON_MOUND).findFirst().orElse(null);
        if (stone != null) {
            HomesteadData.Denial near = HomesteadData.checkLand(level, surface(level, stone.x() + 6, stone.z()), 28, 0);
            c.check("founding beside a named place of the map -> refused (near_place)",
                    near != null && near.key().endsWith("near_place"), stone.id() + " -> " + (near == null ? "allowed" : near.key()));
        }
        Place plot = Places.all().stream().filter(p -> "homestead".equals(p.variant())).findFirst().orElse(null);
        if (plot != null) {
            HomesteadData.Denial open = HomesteadData.checkLand(level, surface(level, plot.x(), plot.z()), 28, 0);
            c.check("an open homestead plot passes the land rules", open == null || !open.key().endsWith("near_place"),
                    plot.id() + " -> " + (open == null ? "allowed" : open.key()));
        }

        // ---- 2. a free spot for the claims
        BlockPos home = findFree(level, town);
        if (home == null) {
            c.check("a free spot for the claims", false, "no spot clear of every footprint near " + town.id());
            return;
        }
        BlockPos near = home.offset(30, 0, 0);
        HomesteadClaim claim = homes.addClaim(owner, home);
        cleanup.add(() -> homes.removeClaim(owner.getUUID()));
        cleanup.add(() -> homes.removeClaim(neighbour.getUUID()));
        owner.setPos(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
        guest.setPos(home.getX() + 2.5, home.getY(), home.getZ() + 0.5);

        HomesteadData.Denial tooNear = homes.checkCanClaim(neighbour, near);
        c.check("a second homestead 30 blocks from the first -> refused (near_claim)", tooNear != null && tooNear.key().endsWith("near_claim"),
                tooNear == null ? "allowed" : tooNear.key());
        HomesteadData.Denial second = homes.checkCanClaim(owner, home.offset(300, 0, 0));
        c.check("a second homestead for the same player -> refused", second != null && second.key().endsWith("already_claimed"),
                second == null ? "allowed" : second.key());

        // ---- 3. access levels
        for (HomesteadClaim.Access kind : HomesteadClaim.Access.values()) {
            claim.setDoorPerm(0);
            claim.setContainerPerm(0);
            claim.setStationPerm(0);
            claim.setBedPerm(0);
            c.check(kind + " locked (owner only) -> a guest is refused", !claim.mayUse(guest, kind, home), "level 0");
            c.check(kind + " locked -> the owner may use it", claim.mayUse(owner, kind, home), "owner");
            switch (kind) {
                case DOOR -> claim.setDoorPerm(2);
                case CONTAINER -> claim.setContainerPerm(2);
                case STATION -> claim.setStationPerm(2);
                case BED -> claim.setBedPerm(2);
            }
            c.check(kind + " open to everyone -> a guest may use it", claim.mayUse(guest, kind, home), "level 2");
        }
        claim.setDoorPerm(2);
        BlockPos door = home.above(2);
        claim.cycleOverride(door); // owner only
        c.check("a single door set to owner only is locked while the claim's doors are open", !claim.mayUse(guest, HomesteadClaim.Access.DOOR, door)
                && claim.mayUse(guest, HomesteadClaim.Access.DOOR, home), "override 0");
        claim.cycleOverride(door);
        claim.cycleOverride(door);
        c.check("cycling an override: owner, party, everyone, back to the claim's level", claim.cycleOverride(door) == -1
                && claim.overrideAt(door) == -1, "4th step clears");

        // ---- 4. protection: nobody but the builder breaks or places, a guest with every perk included
        claim.setBuildPerm(HomesteadClaim.PERM_OWNER);
        c.check("a guest breaks stone in the homestead -> cancelled", breakCancelled(level, guest, home, Blocks.STONE.defaultBlockState()), "guest");
        c.check("a guest places planks in the homestead -> cancelled", placeCancelled(level, guest, home, Blocks.OAK_PLANKS.defaultBlockState()), "guest");
        c.check("the homestead's deny-list: TNT is never placed", !HomesteadBlocks.isAllowedBuildingBlock(Blocks.TNT.defaultBlockState()), "tnt");
        c.check("a forge, a blast furnace, farmland and wheat may be placed",
                HomesteadBlocks.isAllowedBuildingBlock(Blocks.BLAST_FURNACE.defaultBlockState())
                        && HomesteadBlocks.isAllowedBuildingBlock(Blocks.FARMLAND.defaultBlockState())
                        && HomesteadBlocks.isAllowedBuildingBlock(Blocks.WHEAT.defaultBlockState())
                        && HomesteadBlocks.isAllowedBuildingBlock(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "blacksmith_forge")).orElseThrow()
                                .defaultBlockState()), "stations and garden blocks");

        // ---- 5. build mode: a chest is carried with what is in it, and only then
        BlockPos chest = home.above(); // the first block above the ground of the free spot
        BlockState old = level.getBlockState(chest);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 18);
        cleanup.add(() -> {
            level.removeBlock(chest, false);
            level.setBlock(chest, old, 18);
            PlacedBlocks.mark(level, chest, false);
        });
        if (level.getBlockEntity(chest) instanceof ChestBlockEntity be) {
            be.setItem(0, new ItemStack(Items.DIAMOND, 5));
            be.setItem(13, new ItemStack(Items.GOLD_INGOT, 7));
        }
        c.check("breaking a chest outside build mode -> cancelled, the chest stays",
                postBreak(level, owner, chest) && level.getBlockState(chest).is(Blocks.CHEST), "build mode off");
        c.check("a guest breaking the chest -> cancelled, the chest stays",
                postBreak(level, guest, chest) && level.getBlockState(chest).is(Blocks.CHEST), "guest");
        boolean on = HomesteadBuildMode.toggle(owner, claim);
        c.check("the owner enters build mode inside the homestead", on && HomesteadBuildMode.isOn(owner), "on=" + on);
        boolean cancelled = postBreak(level, owner, chest);
        ItemStack carried = Inv.find(owner, s -> s.is(Blocks.CHEST.asItem()));
        CompoundTag tag = carried == null ? null : carried.has(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA) ? carried.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA).copyTagWithoutId() : null;
        c.check("build mode: the chest is taken up, not broken", cancelled && level.getBlockState(chest).isAir() && carried != null && !carried.isEmpty(),
                "block " + level.getBlockState(chest).getBlock());
        c.check("the carried chest holds its contents (diamonds and gold)", tag != null && tag.getListOrEmpty("Items").size() == 2,
                tag == null ? "no block entity data" : tag.getListOrEmpty("Items").size() + " stacks");
        c.check("nothing spilled onto the ground", level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(chest).inflate(3)).isEmpty(), "no item entities near the chest");
        // leaving the grounds turns it off
        owner.setPos(home.getX() + 400.5, home.getY(), home.getZ() + 0.5);
        net.neoforged.neoforge.event.tick.PlayerTickEvent tick = new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(owner);
        owner.tickCount = 20;
        NeoForge.EVENT_BUS.post(tick);
        c.check("leaving the homestead turns build mode off", !HomesteadBuildMode.isOn(owner), "400 blocks away");
        owner.setPos(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);

        // ---- 6. the map: a hidden point, discovery by approach, the owner's name, removal
        claim.setName("Test Hold");
        claim.setBuildPerm(HomesteadClaim.PERM_PARTY);
        guest.setPos(home.getX() + 120.5, home.getY(), home.getZ() + 0.5);
        HomesteadDiscovery.sync(owner);
        HomesteadDiscovery.sync(guest);
        String id = HomesteadDiscovery.id(owner.getUUID());
        CompoundTag mine = WorldData.find(SkyData.get(owner), id);
        CompoundTag hidden = WorldData.find(SkyData.get(guest), id);
        c.check("the owner knows the homestead by the name they set", mine != null && !mine.getBooleanOr("known",false) && "Test Hold".equals(mine.getStringOr("name","")),
                mine == null ? "no entry" : mine.getStringOr("name",""));
        c.check("a stranger 120 blocks away has only a hidden, nameless point", hidden != null && hidden.getBooleanOr("hidden",false)
                && hidden.getBooleanOr("known",false) && hidden.getStringOr("name", "").isEmpty(), hidden == null ? "no entry" : hidden.toString());
        guest.setPos(home.getX() + 600.5, home.getY(), home.getZ() + 0.5);
        c.check("fast travel refuses the hidden point", hidden != null && hidden.getBooleanOr("known",false), "known flag keeps FastTravelHandler out");
        guest.setPos(home.getX() + 3.5, home.getY(), home.getZ() + 0.5);
        HomesteadDiscovery.sync(guest);
        CompoundTag found = WorldData.find(SkyData.get(guest), id);
        c.check("coming close discovers it under its name (on the map and the fast-travel list)", found != null && !found.getBooleanOr("known",false)
                && !found.getBooleanOr("hidden",false) && "Test Hold".equals(found.getStringOr("name","")), found == null ? "no entry" : found.getStringOr("name",""));
        claim.setName("Renamed");
        HomesteadDiscovery.sync(guest);
        found = WorldData.find(SkyData.get(guest), id);
        c.check("a rename reaches the stranger who discovered it", found != null && "Renamed".equals(found.getStringOr("name","")),
                found == null ? "no entry" : found.getStringOr("name",""));
        c.check("names are cleaned (no formatting codes, 32 characters)", HomesteadClaim.sanitizeName("§cBad  Name<script>" + "x".repeat(40))
                .length() <= 32 && !HomesteadClaim.sanitizeName("§cBad").contains("§"), HomesteadClaim.sanitizeName("§cBad  Name<>"));

        // ---- 7. expansion
        int next = Hearthfire.nextRadius(claim);
        c.check("the grounds can be expanded by one step", next == claim.getRadius() + Hearthfire.EXPAND_STEP, "next radius " + next);
        HomesteadClaim other = homes.addClaim(neighbour, home.offset(70, 0, 0));
        HomesteadData.Denial blocked = homes.checkExpansion(level, claim, next);
        c.check("expanding into a neighbour's grounds -> refused", blocked != null && blocked.key().endsWith("near_claim"),
                blocked == null ? "allowed" : blocked.key());
        homes.removeClaim(neighbour.getUUID());
        c.check("the neighbour is gone, so nothing is in the way", other != null && homes.getClaim(neighbour.getUUID()) == null, "removed");

        // ---- 8. removal clears the lists
        homes.removeClaim(owner.getUUID());
        HomesteadDiscovery.sync(guest);
        HomesteadDiscovery.sync(owner);
        c.check("a dismantled homestead leaves everyone's map", WorldData.find(SkyData.get(guest), id) == null
                && WorldData.find(SkyData.get(owner), id) == null, "entries removed");
        cleanup.add(() -> {
            ListTag l1 = WorldData.discovered(SkyData.get(guest));
            for (int i = l1.size() - 1; i >= 0; i--) if (l1.getCompoundOrEmpty(i).getStringOr("id","").startsWith(HomesteadDiscovery.PREFIX)) l1.remove(i);
            ListTag l2 = WorldData.discovered(SkyData.get(owner));
            for (int i = l2.size() - 1; i >= 0; i--) if (l2.getCompoundOrEmpty(i).getStringOr("id","").startsWith(HomesteadDiscovery.PREFIX)) l2.remove(i);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        return p.immutable();
    }

    /** A column well away from the town and any footprint, with room around it. */
    private static BlockPos findFree(ServerLevel level, Place town) {
        int reach = Math.max(town.radius(), 120);
        for (int ring = 0; ring < 4; ring++) {
            int dist = reach + 120 + ring * 100;
            for (int a = 0; a < 12; a++) {
                double ang = a * Math.PI / 6;
                int x = town.x() + (int) Math.round(Math.cos(ang) * dist);
                int z = town.z() + (int) Math.round(Math.sin(ang) * dist);
                BlockPos p = surface(level, x, z);
                if (Footprints.at(level, p).built()) continue;
                if (HomesteadData.get(level.getServer()).getClaimHorizontal(level, p) != null) continue;
                return p;
            }
        }
        return null;
    }

    /** Posts a break event for the block at {@code pos} (as it is) and returns whether it was cancelled. */
    private static boolean postBreak(ServerLevel level, FakePlayer player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        net.neoforged.neoforge.event.level.block.BreakBlockEvent event = new net.neoforged.neoforge.event.level.block.BreakBlockEvent(level, pos, state, player);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    private static boolean breakCancelled(ServerLevel level, FakePlayer player, BlockPos pos, BlockState state) {
        BlockState old = level.getBlockState(pos);
        level.setBlock(pos, state, 18);
        try {
            return postBreak(level, player, pos);
        } finally {
            level.setBlock(pos, old, 18);
            PlacedBlocks.mark(level, pos, false);
        }
    }

    private static boolean placeCancelled(ServerLevel level, FakePlayer player, BlockPos pos, BlockState state) {
        BlockState old = level.getBlockState(pos);
        level.setBlock(pos, state, 18);
        try {
            net.neoforged.neoforge.common.util.BlockSnapshot snapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, pos);
            BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(snapshot, Blocks.STONE.defaultBlockState(), player);
            NeoForge.EVENT_BUS.post(event);
            return event.isCanceled();
        } finally {
            level.setBlock(pos, old, 18);
            PlacedBlocks.mark(level, pos, false);
        }
    }
}
