package com.skycraft.crafting;

import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crime.Ownership;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.roads.Settlement;
import com.skycraft.society.Encounters;
import com.skycraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Places Skyrim crafting stations inside settlement buildings that make sense (smithy, apothecary,
 * library/mage, inn/kitchen, leatherworker) with connected paths, settlement ownership, and shop hours.
 */
public final class SettlementStations {
    private SettlementStations() {}

    public static void populate(ServerLevel level, Settlement s, BlockPos center, RandomSource r) {
        PoiManager poi = level.getPoiManager();

        // 1. Blacksmith Workshop (Forge, Smelter, Armor Workbench, Grindstone)
        BlockPos smithyPoi = poi.findClosest(
                h -> h.is(PoiTypes.ARMORER) || h.is(PoiTypes.WEAPONSMITH) || h.is(PoiTypes.TOOLSMITH),
                center, 48, PoiManager.Occupancy.ANY).orElse(null);
        BlockPos smithyBase = smithyPoi != null ? smithyPoi : findBuildingBase(level, center, 8, 20, r);
        if (smithyBase != null) {
            placeSmithyCluster(level, s, smithyBase, r);
        }

        // 2. Apothecary / Temple (Alchemy Lab)
        BlockPos clericPoi = poi.findClosest(
                h -> h.is(PoiTypes.CLERIC),
                center, 48, PoiManager.Occupancy.ANY).orElse(null);
        BlockPos alchemyBase = clericPoi != null ? clericPoi : findIndoorHouse(level, center, 6, 22, r);
        if (alchemyBase != null) {
            BlockPos labPos = findIndoorCounter(level, alchemyBase, 4);
            if (labPos != null) {
                placeStation(level, labPos, ArcaneRegistry.ALCHEMY_LAB.get(), s, "apothecary", "Apothecary");
                connectPathToRoad(level, labPos);
            }
        }

        // 3. Court Wizard / Scholar Library (Arcane Enchanter)
        BlockPos libPoi = poi.findClosest(
                h -> h.is(PoiTypes.LIBRARIAN),
                center, 48, PoiManager.Occupancy.ANY).orElse(null);
        BlockPos libBase = libPoi != null ? libPoi : findIndoorHouse(level, center, 10, 24, r);
        if (libBase != null) {
            BlockPos enchPos = findIndoorCounter(level, libBase, 4);
            if (enchPos != null) {
                placeStation(level, enchPos, ArcaneRegistry.ARCANE_ENCHANTER.get(), s, "mage", "Court Wizard");
                connectPathToRoad(level, enchPos);
            }
        }

        // 4. Inn / Tavern / Kitchen (Cooking Pot)
        BlockPos butcherPoi = poi.findClosest(
                h -> h.is(PoiTypes.BUTCHER) || h.is(PoiTypes.HOME),
                center, 48, PoiManager.Occupancy.ANY).orElse(null);
        BlockPos kitchenBase = butcherPoi != null ? butcherPoi : findIndoorHouse(level, center, 4, 18, r);
        if (kitchenBase != null) {
            BlockPos potPos = findIndoorCounter(level, kitchenBase, 4);
            if (potPos != null) {
                placeStation(level, potPos, SurvivalRegistry.COOKING_POT.get(), s, "inn", "Innkeeper");
                connectPathToRoad(level, potPos);
            }
        }

        // 5. Tanner / Leatherworker (Tanning Rack)
        BlockPos leatherPoi = poi.findClosest(
                h -> h.is(PoiTypes.LEATHERWORKER) || h.is(PoiTypes.FLETCHER),
                center, 48, PoiManager.Occupancy.ANY).orElse(null);
        BlockPos tanBase = leatherPoi != null ? leatherPoi : smithyBase;
        if (tanBase != null) {
            BlockPos tanPos = findShelteredOrOutdoorSpot(level, tanBase, 4);
            if (tanPos != null) {
                placeStation(level, tanPos, CraftingBlocks.STATIONS.get(StationType.TANNING_RACK).get(), s, "hunter", "Tanner");
                connectPathToRoad(level, tanPos);
            }
        }

        // 6. Placed world clutter inside houses (bread, cheese, potions, ingots)
        placeIndoorClutter(level, s, center, r);
    }

    private static void placeSmithyCluster(ServerLevel level, Settlement s, BlockPos base, RandomSource r) {
        BlockPos forgePos = findShelteredOrOutdoorSpot(level, base, 3);
        if (forgePos != null) {
            placeStation(level, forgePos, CraftingBlocks.STATIONS.get(StationType.FORGE).get(), s, "blacksmith", "Blacksmith");
            connectPathToRoad(level, forgePos);
        }

        BlockPos smelterPos = findShelteredOrOutdoorSpot(level, base.offset(1, 0, 1), 3);
        if (smelterPos != null && !smelterPos.equals(forgePos)) {
            placeStation(level, smelterPos, CraftingBlocks.STATIONS.get(StationType.SMELTER).get(), s, "blacksmith", "Blacksmith");
            connectPathToRoad(level, smelterPos);
        }

        BlockPos benchPos = findShelteredOrOutdoorSpot(level, base.offset(-1, 0, 1), 3);
        if (benchPos != null && !benchPos.equals(forgePos) && !benchPos.equals(smelterPos)) {
            placeStation(level, benchPos, CraftingBlocks.STATIONS.get(StationType.ARMOR_WORKBENCH).get(), s, "blacksmith", "Blacksmith");
            connectPathToRoad(level, benchPos);
        }

        BlockPos grindPos = findShelteredOrOutdoorSpot(level, base.offset(1, 0, -1), 3);
        if (grindPos != null && !grindPos.equals(forgePos) && !grindPos.equals(smelterPos) && !grindPos.equals(benchPos)) {
            placeStation(level, grindPos, CraftingBlocks.STATIONS.get(StationType.GRINDSTONE).get(), s, "blacksmith", "Blacksmith");
            connectPathToRoad(level, grindPos);
        }
    }

    private static void placeStation(ServerLevel level, BlockPos pos, Block block, Settlement s, String role, String roleTitle) {
        // never replace anything: only empty air that isn't a container or a villager's job site
        if (!isFreeSpot(level, pos)) return;
        BlockState state = block.defaultBlockState();
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom()));
        }
        level.setBlock(pos, state, 3);
        Ownership.setOwner(level, pos, "settlement:" + s.id + ":" + role, Component.literal(s.name + " " + roleTitle));
    }

    /** Traces a walkable path from the station position to the nearest settlement road/street. */
    public static void connectPathToRoad(ServerLevel level, BlockPos stationPos) {
        BlockPos nearestPath = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                int x = stationPos.getX() + dx;
                int z = stationPos.getZ() + dz;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y - 1, z);
                BlockState bs = level.getBlockState(p);
                if (bs.is(Blocks.DIRT_PATH) || bs.is(Blocks.GRAVEL) || bs.is(Blocks.COBBLESTONE)
                        || bs.is(Blocks.MOSSY_COBBLESTONE) || bs.is(Blocks.STONE_BRICKS) || bs.is(Blocks.SANDSTONE)) {
                    double d = stationPos.distSqr(p);
                    if (d < bestDist && d > 2) {
                        bestDist = d;
                        nearestPath = p;
                    }
                }
            }
        }

        if (nearestPath == null) return;
        Block pathMaterial = level.getBlockState(nearestPath).getBlock();
        if (pathMaterial == Blocks.AIR) pathMaterial = Blocks.DIRT_PATH;

        int x0 = stationPos.getX(), z0 = stationPos.getZ();
        int x1 = nearestPath.getX(), z1 = nearestPath.getZ();
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        if (steps <= 0) return;

        for (int i = 1; i <= steps; i++) {
            int cx = x0 + (x1 - x0) * i / steps;
            int cz = z0 + (z1 - z0) * i / steps;
            int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
            BlockPos ground = new BlockPos(cx, cy - 1, cz);
            BlockState current = level.getBlockState(ground);
            if (current.is(BlockTags.DIRT) || current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.PODZOL)
                    || current.is(Blocks.COARSE_DIRT) || current.is(Blocks.SAND)) {
                level.setBlock(ground, pathMaterial.defaultBlockState(), 3);
                BlockPos above = ground.above();
                if (level.getBlockState(above).canBeReplaced()) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static BlockPos findIndoorCounter(ServerLevel level, BlockPos origin, int radius) {
        return findIndoorCounter(level, origin, radius, Collections.emptySet(), false);
    }

    /**
     * An empty indoor spot ON TOP of a counter (barrel, crafting table, smoker, bookshelf, slab, stair) near
     * {@code origin}, i.e. the air block above it; the counter itself is never returned or replaced. Chests are
     * skipped (something on top would stop them opening). Without a counter: an empty indoor floor spot, where
     * {@code buildTable} first builds a small fence-and-plate table (for clutter) and returns the air above its top.
     */
    private static BlockPos findIndoorCounter(ServerLevel level, BlockPos origin, int radius, Set<BlockPos> exclude, boolean buildTable) {
        // 1. Look for existing tables, barrels, counters, slabs, and desks
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    BlockPos top = p.above();
                    if (exclude.contains(top)) continue;
                    BlockState surface = level.getBlockState(p);
                    if (isFreeSpot(level, top) && !level.canSeeSky(top)) {
                        if (surface.is(Blocks.BARREL) || surface.is(Blocks.CRAFTING_TABLE)
                                || surface.is(Blocks.SMOKER) || surface.is(Blocks.BOOKSHELF)
                                || surface.getBlock() instanceof SlabBlock
                                || surface.getBlock() instanceof StairBlock) {
                            return top;
                        }
                    }
                }
            }
        }
        // 2. Look for open floor space (optionally building a rustic fence-post table surface)
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (exclude.contains(p) || exclude.contains(p.above())) continue;
                    if (isFreeSpot(level, p) && isFreeSpot(level, p.above())) {
                        BlockState floor = level.getBlockState(p.below());
                        if (floor.isSolidRender() && !level.canSeeSky(p)) {
                            if (!buildTable) return p;
                            if (!isFreeSpot(level, p.above(2))) continue;
                            level.setBlock(p, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                            level.setBlock(p.above(), Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState(), 3);
                            return p.above(2);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Air that holds no block entity and is no villager job site / bed / bell (POI). */
    private static boolean isFreeSpot(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null
                && level.getPoiManager().getType(pos).isEmpty();
    }

    private static BlockPos findShelteredOrOutdoorSpot(ServerLevel level, BlockPos origin, int radius) {
        BlockPos indoor = findIndoorCounter(level, origin, radius, Collections.emptySet(), false);
        if (indoor != null) return indoor;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                if (isFreeSpot(level, p) && level.getBlockState(p.below()).isSolidRender()) {
                    return p;
                }
            }
        }
        return null;
    }

    private static BlockPos findIndoorHouse(ServerLevel level, BlockPos center, int minR, int maxR, RandomSource r) {
        for (int i = 0; i < 20; i++) {
            int dx = (r.nextBoolean() ? 1 : -1) * (minR + r.nextInt(Math.max(1, maxR - minR)));
            int dz = (r.nextBoolean() ? 1 : -1) * (minR + r.nextInt(Math.max(1, maxR - minR)));
            BlockPos p = center.offset(dx, 0, dz);
            BlockPos house = level.getPoiManager().findClosest(
                    h -> h.is(PoiTypes.HOME), p, 16, PoiManager.Occupancy.ANY).orElse(null);
            if (house != null && !level.canSeeSky(house)) return house;
        }
        return null;
    }

    private static BlockPos findBuildingBase(ServerLevel level, BlockPos center, int minR, int maxR, RandomSource r) {
        for (int i = 0; i < 16; i++) {
            int dx = (r.nextBoolean() ? 1 : -1) * (minR + r.nextInt(Math.max(1, maxR - minR)));
            int dz = (r.nextBoolean() ? 1 : -1) * (minR + r.nextInt(Math.max(1, maxR - minR)));
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            BlockPos ground = Encounters.groundAt(level, x, z);
            if (ground != null && Math.abs(ground.getY() - center.getY()) <= 6) {
                return ground;
            }
        }
        return center;
    }

    /** Persistent-data flag of placed clutter items (so a settlement never gets more than {@link #MAX_CLUTTER}). */
    private static final String CLUTTER_TAG = "skycraft_clutter";
    private static final int MAX_CLUTTER = 8;

    private static void placeIndoorClutter(ServerLevel level, Settlement s, BlockPos center, RandomSource r) {
        List<ItemStack> clutterPool = List.of(
                new ItemStack(Items.BREAD),
                new ItemStack(Items.APPLE),
                new ItemStack(Items.IRON_INGOT),
                new ItemStack(CraftingItems.LEATHER_STRIPS.get()),
                new ItemStack(CraftingItems.FIREWOOD.get())
        );

        // clutter never despawns, so keep it bounded even if a settlement is ever populated again
        int existing = level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(center).inflate(48),
                e -> e.getPersistentData().getBooleanOr(CLUTTER_TAG, false)).size();
        int budget = MAX_CLUTTER - existing;

        Set<BlockPos> usedSurfaces = new HashSet<>();
        for (ItemStack item : clutterPool) {
            if (budget <= 0) return;
            BlockPos house = findIndoorHouse(level, center, 4, 32, r);
            if (house != null) {
                BlockPos table = findIndoorCounter(level, house, 4, usedSurfaces, true);
                if (table != null) {
                    usedSurfaces.add(table);
                    placeClutter(level, s, table, item.copy(), r);
                    budget--;
                }
            }
        }

        // Place readable lore books and notes on desks and tables
        LoreBooks.Book randomBook = budget > 0 ? LoreBooks.random(r, b -> true) : null;
        if (randomBook != null) {
            BlockPos house = findIndoorHouse(level, center, 4, 32, r);
            if (house != null) {
                BlockPos table = findIndoorCounter(level, house, 4, usedSurfaces, true);
                if (table != null) {
                    usedSurfaces.add(table);
                    placeClutter(level, s, table, LoreBookItem.create(randomBook), r);
                }
            }
        }
    }

    /**
     * Lays an owned item on the surface below {@code spot} (an air block above a counter). It belongs to the
     * settlement ({@link Ownership#ENTITY_OWNER_KEY}: taking it is theft) and stays until someone takes it.
     */
    private static void placeClutter(ServerLevel level, Settlement s, BlockPos spot, ItemStack stack, RandomSource r) {
        double offX = 0.5 + (r.nextDouble() - 0.5) * 0.3;
        double offZ = 0.5 + (r.nextDouble() - 0.5) * 0.3;
        ItemEntity entity = new ItemEntity(level, spot.getX() + offX, spot.getY() + 0.05, spot.getZ() + offZ, stack);
        entity.setDeltaMovement(0, 0, 0);
        entity.lifespan = Integer.MAX_VALUE;
        entity.setExtendedLifetime();
        entity.getPersistentData().putString(Ownership.ENTITY_OWNER_KEY, "settlement:" + s.id);
        entity.getPersistentData().putBoolean(CLUTTER_TAG, true);
        level.addFreshEntity(entity);
    }
}
