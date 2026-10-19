package com.nirncraft.landmarks.build;

import com.nirncraft.crafting.arcane.ArcaneRegistry;
import com.nirncraft.landmarks.Lore;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

import java.util.Locale;
import java.util.Optional;

/**
 * Trailside and trail's-end vignettes: the small, specific things a path leads to or passes on the way, each made to
 * belong where it stands (a collapsed adit in a mountainside, a bench where the land falls away, a hunter's tree stand
 * in the woods, a castaway's shelter on the shore, a lone cabin with someone living in it, a waystone in the culture's
 * own stone, a wolf den under an overhang, a shrine cut into the rock...). The scene plan picks the kind by the spot's
 * family, elevation, water and province; the builder then dresses it for the province and the ground. Local +z faces
 * the trail.
 */
final class Trailside {
    private Trailside() {}

    // ------------------------------------------------------------------ helpers

    private static void flat(Stage s, int radius) {
        BlockState fill = s.sandy ? Blocks.SANDSTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        s.b.level(radius, null, fill, 6);
    }

    private static ItemStack note(Stage s, String category, String title) {
        RandomSource r = RandomSource.create(s.seed ^ 0x51ED2701L);
        return Lore.book(title, Lore.person(s.site.province(), r), s.story(category));
    }

    private static BlockState log(Stage s, Direction.Axis axis) {
        return s.wood.log().defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    private static BlockState slab(Block b, SlabType t) {
        return b.defaultBlockState().setValue(SlabBlock.TYPE, t);
    }

    private static BlockState hanging() {
        return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
    }

    private static BlockState leaves(Stage s) {
        return s.wood.leaves().defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    private static Block block(String id, Block fallback) {
        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        return b == null || b == Blocks.AIR ? fallback : b;
    }

    private static ItemStack worn(net.minecraft.world.item.Item item, double wear) {
        ItemStack st = new ItemStack(item);
        if (st.isDamageableItem()) st.setDamageValue((int) (st.getMaxDamage() * wear));
        return st;
    }

    /** A two-block door of the culture's wood facing a local direction. */
    private static void door(Stage s, int dx, int dy, int dz, Direction face) {
        BlockState lo = s.b.pal.door().defaultBlockState().setValue(DoorBlock.FACING, face).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        s.set(dx, dy, dz, lo);
        s.set(dx, dy + 1, dz, lo.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** The province's ores (the same sets the prospectors of the scatter know). */
    private static Block[] ores(String province) {
        Block iron = Blocks.IRON_ORE, coal = Blocks.COAL_ORE, copper = Blocks.COPPER_ORE;
        return switch (province) {
            case "skyrim", "atmora", "solstheim" -> new Block[]{block("nirncraft:silver_ore", iron), block("nirncraft:corundum_ore", iron), iron, coal};
            case "morrowind" -> new Block[]{block("nirncraft:ebony_ore", iron), block("nirncraft:malachite_ore", iron), iron, coal};
            case "hammerfell", "elsweyr", "yokuda" -> new Block[]{block("nirncraft:moonstone_ore", iron), block("nirncraft:orichalcum_ore", iron), Blocks.GOLD_ORE, copper};
            case "black_marsh", "valenwood" -> new Block[]{block("nirncraft:malachite_ore", iron), block("nirncraft:quicksilver_ore", iron), copper, iron};
            default -> new Block[]{block("nirncraft:quicksilver_ore", iron), block("nirncraft:orichalcum_ore", iron), copper, iron, coal};
        };
    }

    /** The local direction the ground falls away most at {@code d} blocks (at least 2 blocks), or null on flat ground. */
    private static Direction downhill(Stage s, int d) {
        Direction best = null;
        int lowest = s.top(0, 0) - 1;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int h = s.top(dir.getStepX() * d, dir.getStepZ() * d);
            if (h < lowest) {
                lowest = h;
                best = dir;
            }
        }
        return best;
    }

    /** The nearest named place and the way to it, for a sign: {"Riverwood", "north"} or null. */
    private static String[] nearest(Stage s) {
        Optional<Place> p = Places.nearest(null, s.site.x(), s.site.z(), 2400);
        if (p.isEmpty()) return null;
        String name;
        try {
            name = com.nirncraft.world.PlaceText.plain(p.get());
        } catch (RuntimeException e) {
            name = p.get().id().replace('_', ' ');
        }
        double dx = p.get().x() - s.site.x(), dz = p.get().z() - s.site.z();
        String dir = Math.abs(dx) > Math.abs(dz) * 2 ? (dx > 0 ? "east" : "west")
                : Math.abs(dz) > Math.abs(dx) * 2 ? (dz > 0 ? "south" : "north")
                : (dz > 0 ? "south" : "north") + "-" + (dx > 0 ? "east" : "west");
        int dist = (int) Math.round(Math.hypot(dx, dz) / 10.0) * 10;
        return new String[]{name, dir, dist >= 1000 ? String.format(Locale.ROOT, "%.1f km", dist / 1000.0) : dist + " m"};
    }

    // ------------------------------------------------------------------ a collapsed mine adit (mountain, cliff, hill)

    static boolean adit(Stage s) {
        int g = 0;
        boolean dwemer = s.kind.style().equals("dwemer");
        // the hill face behind the mouth (local -z; +z faces the trail): a craggy mound that merges into the real slope
        s.blob(0, g + 2, -4, 6.5, 5.5, 5.0, 0.3, 3);
        // the drive: 5 wide, 3 high, running back from the mouth at z = 2 to z = -4, its floor gravel and rock
        for (int dz = -4; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                s.set(dx, g - 1, dz, s.chance(dx * 7 + dz + 9, 0.4) ? Blocks.GRAVEL.defaultBlockState() : s.rk(dx, g - 1, dz));
                for (int dy = 0; dy <= 2; dy++) s.air(dx, g + dy, dz);
            }
        }
        // timber (or bronze) sets at the mouth and halfway in
        BlockState post = dwemer ? com.nirncraft.worldgen.VanillaBlocks.WAXED_OXIDIZED_CUT_COPPER.defaultBlockState() : log(s, Direction.Axis.Y);
        BlockState beam = dwemer ? com.nirncraft.worldgen.VanillaBlocks.WAXED_WEATHERED_CUT_COPPER.defaultBlockState() : log(s, Direction.Axis.X);
        for (int dz : new int[]{1, -2}) {
            for (int dy = 0; dy <= 2; dy++) {
                s.set(-2, g + dy, dz, post);
                s.set(2, g + dy, dz, post);
            }
            for (int dx = -2; dx <= 2; dx++) s.set(dx, g + 3, dz, beam);
        }
        // the fall: the roof came down at the back, and took a beam with it
        for (int dz = -4; dz <= -3; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int h = dz == -3 ? 1 + s.vary(dx + 3, 0, 1) : 3;
                for (int dy = 0; dy < h; dy++) s.set(dx, g + dy, dz, s.chance(dx * 3 + dy + dz + 9, 0.5) ? Blocks.GRAVEL.defaultBlockState() : s.rubble(dx, dy, dz));
            }
        }
        s.set(1, g + 3, -2, Blocks.AIR.defaultBlockState());
        s.set(1, g + 1, -2, slab(s.wood.slab(), SlabType.BOTTOM));
        // seams of ore showing in the walls
        Block[] ores = ores(s.site.province());
        for (int i = 0; i < 9; i++) {
            int x = s.chance(10 + i, 0.5) ? -3 : 3, z = s.vary(20 + i, -4, 1), y = g + s.vary(30 + i, 0, 2);
            if (s.solidAt(x, y, z)) s.set(x, y, z, ores[Math.floorMod(s.vary(40 + i, 0, 99), ores.length)]);
        }
        // rails out of the drive across the spoil yard, and the chest cart that never left
        for (int dz = -2; dz <= 5; dz++) {
            if (!s.solidAt(0, g - 1, dz)) s.set(0, g - 1, dz, s.rk(0, g - 1, dz));
            if (dz == 4 && s.chance(5, 0.5)) continue; // a missing length of rail
            s.set(0, g, dz, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH));
        }
        cart(s, 0, g, 0, "explore/miner");
        s.set(-1, g + 2, 1, dwemer ? com.nirncraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState() : hanging());
        if (dwemer) s.set(-1, g + 1, 1, hanging());
        // the yard: a spoil heap with ore, the barrel with the last entry, a dropped pick, a warning
        s.set(-3, g, 4, Blocks.GRAVEL);
        s.set(-3, g + 1, 4, ores[0]);
        s.set(-4, g, 4, ores[ores.length - 1]);
        s.set(-3, g, 5, Blocks.GRAVEL);
        BlockPos b = s.barrel(3, g, 3, "explore/miner");
        s.put(b, note(s, "miner", dwemer ? "Excavation Log" : "Miner's Last Entry"));
        s.lying(2, g, 5, worn(Items.IRON_PICKAXE, 0.7));
        s.standingSign(-2, g, 6, 8, "DANGER", dwemer ? "Lower gallery" : "Roof came down", "Do not enter", "");
        s.rubbleRing(0, 4, 4, 5);
        return true;
    }

    /** A chest minecart with a loot table on the rail at local (dx, dy, dz) (vanilla mineshafts do the same). */
    private static void cart(Stage s, int dx, int dy, int dz, String table) {
        BlockPos p = s.b.at(dx, dy, dz);
        if (!s.b.writable(p)) return;
        try {
            var cart = new net.minecraft.world.entity.vehicle.minecart.MinecartChest(net.minecraft.world.entity.EntityTypes.CHEST_MINECART, s.level.getLevel());
            cart.setPos( p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            cart.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("nirncraft", table)), s.seed);
            s.level.addFreshEntity(cart);
        } catch (RuntimeException e) {
            s.barrel(dx, dy, dz, table);
        }
    }

    // ------------------------------------------------------------------ a bench where the land falls away (overlook)

    static boolean overlook(Stage s) {
        Direction view = downhill(s, 7);
        if (view == null) view = Direction.SOUTH;
        Direction back = view.getOpposite(), along = view.getClockWise();
        // a little flagged standing on the ground as it lies
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3 || !s.dry(dx, dz)) continue;
                int y = s.top(dx, dz) - 1;
                s.set(dx, y, dz, s.chance(dx * 5 + dz, 0.6) ? s.b.pal.path().defaultBlockState() : s.rk(dx, y, dz));
                for (int k = 1; k <= 3; k++) if (!s.free(dx, y + k, dz) && !s.solidAt(dx, y + k, dz)) s.air(dx, y + k, dz);
            }
        }
        // the bench: two seats facing the view, a backrest on the uphill side
        int bx = back.getStepX(), bz = back.getStepZ();
        boolean stone = s.snowy || s.sandy || s.ashy || s.chance(3, 0.3);
        Block seat = stone ? s.b.pal.brickStairs() : s.b.pal.stairs();
        for (int k = 0; k <= 1; k++) {
            int x = bx + along.getStepX() * k, z = bz + along.getStepZ() * k;
            s.set(x, s.top(x, z), z, s.b.stair(seat, back));
        }
        // a stump table with a lantern at one end, the traveller's satchel at the other
        int tx = bx + along.getStepX() * 2, tz = bz + along.getStepZ() * 2;
        int ty = s.top(tx, tz);
        s.set(tx, ty, tz, log(s, Direction.Axis.Y));
        s.lantern(tx, ty + 1, tz, false);
        int px = bx - along.getStepX(), pz = bz - along.getStepZ();
        BlockPos bag = s.barrel(px, s.top(px, pz), pz, "explore/overlook");
        if (s.chance(4, 0.6)) s.put(bag, note(s, "overlook", "Written at the Overlook"));
        // a cairn on the edge, a token on top
        int cx = view.getStepX() * 3 + along.getStepX() * 2, cz = view.getStepZ() * 3 + along.getStepZ() * 2;
        if (s.dry(cx, cz)) {
            int cy = s.top(cx, cz);
            s.set(cx, cy, cz, s.rk(cx, cy, cz));
            s.set(cx, cy + 1, cz, s.rubble(cx, cy + 1, cz));
            s.lying(cx, cy + 2, cz, Vignettes.token(s, 8));
        }
        String[] near = nearest(s);
        int sx = bx * 2 - along.getStepX() * 2, sz = bz * 2 - along.getStepZ() * 2;
        if (near != null) s.standingSign(sx, s.top(sx, sz), sz, 8, "Rest a while.", near[0], "lies " + near[1], near[2]);
        for (int i = 0; i < 4; i++) {
            int x = s.vary(50 + i, -3, 3), z = s.vary(60 + i, -3, 3);
            if (s.dry(x, z)) s.dress(x, s.top(x, z), z);
        }
        return true;
    }

    // ------------------------------------------------------------------ a tree stand / tree house (forest, jungle)

    static boolean treehouse(Stage s) {
        int g = s.top(0, 0);
        String style = s.kind.style();
        Block planks = s.wood.planks();
        int p = g + 5;
        int h = s.vary(1, 12, 14);
        // the tree: a trunk with root flares
        for (int y = g; y < g + h; y++) s.set(0, y, 0, log(s, Direction.Axis.Y));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int x = d.getStepX(), z = d.getStepZ();
            s.set(x, s.top(x, z), z, log(s, d.getAxis()));
        }
        // the deck: 5x5 round the trunk, with a hatch for the ladder on the trail side
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx == 0 && dz == 1) continue; // the hatch
                s.set(dx, p, dz, planks);
            }
        }
        // the ladder up the trunk's trail side, through the hatch
        for (int y = g; y <= p; y++) {
            s.set(0, y, 1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
        }
        // corner stilts down to the ground and a railing
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            s.b.pillarDown(c[0], p, c[1], log(s, Direction.Axis.Y));
            for (int y = p + 1; y <= p + 3; y++) s.set(c[0], y, c[1], log(s, Direction.Axis.Y));
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                if (!edge || corner || (dx == 0 && dz == 2)) continue;
                s.set(dx, p + 1, dz, s.wood.fence());
            }
        }
        // the roof, a slab canopy, and the living crown above it
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                s.set(dx, p + 4, dz, slab(s.wood.slab(), SlabType.BOTTOM));
            }
        }
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = p + 5; y <= g + h + 1; y++) {
                    double d = Math.hypot(dx, dz) + Math.abs(y - (g + h - 1)) * 0.6;
                    if (d > 3.4 || (dx == 0 && dz == 0 && y < g + h)) continue;
                    if (s.chance(70 + dx * 9 + dz * 5 + y, 0.15)) continue;
                    if (s.free(dx, y, dz)) s.set(dx, y, dz, leaves(s));
                }
            }
        }
        // inside: who lives or watches here
        s.set(1, p + 3, -1, hanging());
        switch (style) {
            case "hermit" -> {
                Vignettes.bed(s, -1, p + 1, -1, Direction.WEST, Vignettes.bedFor(s));
                s.set(-1, p + 1, 1, Blocks.BOOKSHELF);
                s.lectern(1, p + 1, 1, Direction.WEST, note(s, "hermit", "Notes from the Tree"));
                s.chest(1, p + 1, -1, Direction.WEST, "explore/hermit");
            }
            case "lookout" -> {
                s.chest(-1, p + 1, -1, Direction.EAST, "explore/lookout");
                s.set(1, p + 1, 1, Blocks.HAY_BLOCK);
                s.lying(-1, p + 1, 1, new ItemStack(Items.SPYGLASS));
                s.b.banner(2, p + 4, 0, s.b.dye(net.minecraft.world.item.DyeColor.GREEN));
            }
            default -> { // a hunter's stand
                Vignettes.bed(s, -1, p + 1, -1, Direction.WEST, Vignettes.bedFor(s));
                BlockPos c = s.chest(1, p + 1, -1, Direction.WEST, "explore/hunter");
                s.put(c, note(s, "hunter", "Tally from the Stand"));
                s.lying(1, p + 1, 1, worn(Items.BOW, 0.4));
                s.set(-1, p + 1, 1, com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET);
            }
        }
        s.dress(2, s.top(2, 2), 2);
        s.dress(-2, s.top(-2, -2), -2);
        return true;
    }

    // ------------------------------------------------------------------ an explorer who froze (tundra, high peaks)

    static boolean frozen(Stage s) {
        int g = s.top(0, 0);
        // the tent: one side still standing, the other fallen under the snow
        for (int dz = -1; dz <= 2; dz++) {
            s.set(-3, s.top(-3, dz), dz, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL);
            if (dz <= 0) s.set(-2, g + 1, dz, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL);
            s.set(-2, s.top(-2, dz), dz, dz <= 0 ? Blocks.AIR.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WHITE_CARPET.defaultBlockState());
        }
        s.set(-2, g, -2, s.wood.fence());
        s.set(-2, g + 1, -2, s.wood.fence());
        // the explorer, curled by the fire that went out
        s.set(1, g, 0, Blocks.BONE_BLOCK.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        s.b.skull(2, g, 0);
        s.set(1, g, 1, com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET);
        s.set(2, g, -2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        Vignettes.ring(s, 2, -2, 1, true);
        s.set(2, g, -2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        BlockPos pack = s.barrel(-1, g, -2, "explore/adventurer");
        s.put(pack, note(s, "explorer", "A Frozen Journal"));
        s.lying(0, g, 1, worn(s.chance(6, 0.5) ? Items.IRON_AXE : Items.IRON_PICKAXE, 0.8));
        s.lantern(-1, g, -1, true);
        // the last signal: a flag on a pole
        for (int y = 0; y < 3; y++) s.set(3, g + y, 2, s.wood.fence());
        s.b.banner(3, g + 3, 2, s.b.dye(net.minecraft.world.item.DyeColor.RED));
        // ice and snow drift over all of it
        for (int i = 0; i < 6; i++) {
            int x = s.vary(80 + i, -4, 4), z = s.vary(90 + i, -4, 4);
            if (s.dry(x, z)) Vignettes.patch(s, x, z, Blocks.PACKED_ICE.defaultBlockState());
        }
        for (int i = 0; i < 18; i++) {
            int x = s.vary(100 + i, -4, 4), z = s.vary(130 + i, -4, 4);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z)) {
                s.set(x, s.top(x, z), z, Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 1 + s.vary(160 + i, 0, 2)));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ a castaway's shelter (coast, beach)

    static boolean castaway(Stage s) {
        int g = s.top(0, 0);
        Block drift = Blocks.STRIPPED_OAK_LOG;
        // a lean-to of driftwood against two forked posts
        for (int dx = -2; dx <= 2; dx += 4) {
            s.set(dx, g, -1, s.wood.fence());
            s.set(dx, g + 1, -1, s.wood.fence());
        }
        for (int dx = -2; dx <= 2; dx++) {
            s.set(dx, g + 2, -1, drift.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            s.set(dx, g + 1, -2, slab(Blocks.OAK_SLAB, SlabType.TOP));
            s.set(dx, g, -3, slab(Blocks.OAK_SLAB, SlabType.BOTTOM));
        }
        Vignettes.bed(s, -1, g, -2, Direction.EAST, com.nirncraft.worldgen.VanillaBlocks.WHITE_BED);
        // the overturned boat beside it
        for (int dz = 1; dz <= 4; dz++) {
            for (int dx = 3; dx <= 4; dx++) s.set(dx, s.top(dx, dz), dz, slab(s.wood.slab(), SlabType.TOP));
        }
        // the fire, the chest of what washed up, the log of days
        s.set(0, g, 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        Vignettes.ring(s, 0, 1, 1, true);
        s.set(0, g, 1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        BlockPos c = s.chest(-3, g, 0, Direction.EAST, "explore/shipwreck");
        s.put(c, note(s, "castaway", "Days on the Shore"));
        s.lying(2, g, 2, new ItemStack(Items.FISHING_ROD));
        s.lying(-1, g, 2, new ItemStack(Items.GLASS_BOTTLE));
        // HELP spelled in stones, mostly kicked away by the tide
        for (int i = 0; i < 6; i++) {
            int x = -4 + i, z = 4;
            if (s.dry(x, z) && s.chance(170 + i, 0.75)) s.set(x, s.top(x, z), z, s.rubble(x, 0, z));
        }
        // driftwood strewn about
        for (int i = 0; i < 4; i++) {
            int x = s.vary(180 + i, -5, 5), z = s.vary(190 + i, -5, 5);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, drift.defaultBlockState().setValue(BlockStateProperties.AXIS,
                    i % 2 == 0 ? Direction.Axis.X : Direction.Axis.Z));
        }
        return true;
    }

    // ------------------------------------------------------------------ an angler's spot (river bank, lake shore)

    static boolean angler(Stage s) {
        // the water: the nearest side where it lies within 6 blocks
        Direction toWater = null;
        int dist = 0;
        for (int d = 2; d <= 6 && toWater == null; d++) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (!s.dry(dir.getStepX() * d, dir.getStepZ() * d)) {
                    toWater = dir;
                    dist = d;
                    break;
                }
            }
        }
        if (toWater == null) return false;
        int g = s.top(0, 0);
        int wx = toWater.getStepX(), wz = toWater.getStepZ();
        // a jetty out over the water on log piles
        for (int k = 1; k <= dist + 2; k++) {
            int x = wx * k, z = wz * k;
            s.set(x, g - 1, z, s.wood.planks());
            if (k >= dist && (k - dist) % 2 == 0) s.b.pillarDown(x, g - 1, z, log(s, Direction.Axis.Y));
        }
        int ex = wx * (dist + 2), ez = wz * (dist + 2);
        s.set(ex, g, ez, s.b.stair(s.b.pal.stairs(), toWater.getOpposite()));
        // the bank: a stool, the bait barrel, a drying rack with the catch
        BlockPos b = s.barrel(-wz * 2, g, wx * 2, "explore/fisher");
        s.put(b, note(s, "angler", "The Angler's Book"));
        s.set(wz * 2, g, -wx * 2, s.b.stair(s.b.pal.stairs(), toWater.getOpposite()));
        int rx = -wx * 2, rz = -wz * 2;
        for (int k = -1; k <= 1; k++) {
            int x = rx + wz * k, z = rz - wx * k;
            s.set(x, s.top(x, z), z, s.wood.fence());
        }
        s.lying(rx, s.top(rx, rz), rz, new ItemStack(Items.COD));
        s.lying(wx * (dist + 1), g, wz * (dist + 1), new ItemStack(Items.FISHING_ROD));
        s.lying(-wz, g, wx, new ItemStack(Items.BUCKET));
        s.set(wz * 3, g, -wx * 3, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, s.chance(9, 0.3)));
        return true;
    }

    // ------------------------------------------------------------------ a waystone in the culture's own stone

    static boolean waystone(Stage s) {
        int g = s.top(0, 0);
        String prov = s.site.province();
        Block base, shaft, cap;
        BlockState light;
        switch (prov) {
            case "morrowind" -> {
                base = Blocks.POLISHED_BLACKSTONE_BRICKS;
                shaft = Blocks.CHISELED_POLISHED_BLACKSTONE;
                cap = Blocks.POLISHED_BLACKSTONE_BRICK_SLAB;
                light = Blocks.SOUL_LANTERN.defaultBlockState();
            }
            case "hammerfell", "yokuda" -> {
                base = Blocks.CUT_SANDSTONE;
                shaft = Blocks.CHISELED_SANDSTONE;
                cap = Blocks.SMOOTH_SANDSTONE_SLAB;
                light = Blocks.LANTERN.defaultBlockState();
            }
            case "elsweyr" -> {
                base = Blocks.CUT_RED_SANDSTONE;
                shaft = Blocks.CHISELED_RED_SANDSTONE;
                cap = Blocks.RED_SANDSTONE_SLAB;
                light = Blocks.LANTERN.defaultBlockState();
            }
            case "summerset" -> {
                base = Blocks.QUARTZ_BRICKS;
                shaft = Blocks.CHISELED_QUARTZ_BLOCK;
                cap = Blocks.QUARTZ_SLAB;
                light = Blocks.SEA_LANTERN.defaultBlockState();
            }
            case "cyrodiil" -> {
                base = s.chance(2, 0.4) ? Blocks.CALCITE : Blocks.STONE_BRICKS; // an Ayleid stone now and then
                shaft = base == Blocks.CALCITE ? Blocks.CALCITE : Blocks.CHISELED_STONE_BRICKS;
                cap = Blocks.SMOOTH_STONE_SLAB;
                light = base == Blocks.CALCITE ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.LANTERN.defaultBlockState();
            }
            case "valenwood", "black_marsh" -> {
                base = Blocks.MOSSY_COBBLESTONE;
                shaft = Blocks.MUD_BRICKS;
                cap = Blocks.MOSS_CARPET;
                light = Blocks.SHROOMLIGHT.defaultBlockState();
            }
            default -> { // Nord and the rest: a rune-cut standing stone
                base = Blocks.MOSSY_STONE_BRICKS;
                shaft = Blocks.CHISELED_STONE_BRICKS;
                cap = Blocks.STONE_BRICK_SLAB;
                light = Blocks.LANTERN.defaultBlockState();
            }
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (s.dry(dx, dz)) s.set(dx, s.top(dx, dz) - 1, dz, s.rk(dx, 0, dz));
        }
        s.set(0, g, 0, base);
        s.set(0, g + 1, 0, shaft);
        s.set(0, g + 2, 0, shaft);
        s.set(0, g + 3, 0, cap);
        if (!light.is(Blocks.SHROOMLIGHT) && !light.is(Blocks.SEA_LANTERN)) {
            s.set(0, g + 3, 0, base);
            s.set(0, g + 4, 0, light);
        } else {
            s.set(0, g + 2, 0, light);
        }
        String[] near = nearest(s);
        if (near != null) s.wallSign(0, g + 1, 1, Direction.SOUTH, near[0], "lies " + near[1], near[2], "");
        Vignettes.candle(s, 1, s.top(1, 1), 1, 1 + s.vary(3, 0, 2), s.chance(4, 0.5));
        s.lying(-1, s.top(-1, 1), 1, Vignettes.offering(s, 5));
        for (int i = 0; i < 3; i++) {
            int x = s.vary(200 + i, -2, 2), z = s.vary(210 + i, -2, 2);
            if (s.lush && s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, Vignettes.flower(s, 220 + i));
        }
        return true;
    }

    // ------------------------------------------------------------------ a beast's den under an overhang

    static boolean den(Stage s) {
        int g = s.top(0, 0);
        // the overhang: a big craggy mound with a hollow on the trail side
        s.blob(0, g + 1, -1, 5.5, 4.0, 4.5, 0.35, 7);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                int hh = 2 + (Math.abs(dx) <= 1 && dz <= 1 ? 1 : 0);
                for (int dy = 0; dy < hh; dy++) s.air(dx, g + dy, dz);
                s.set(dx, g - 1, dz, s.chance(dx * 11 + dz, 0.4) ? Blocks.COARSE_DIRT.defaultBlockState() : s.rk(dx, g - 1, dz));
            }
        }
        // what it dragged home
        s.set(-1, g, -1, Blocks.BONE_BLOCK);
        s.set(1, g, -2, Blocks.BONE_BLOCK.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        s.b.skull(0, g, -2);
        s.lying(1, g, 0, new ItemStack(Items.BONE));
        s.lying(-2, g, 1, new ItemStack(Items.LEATHER));
        s.set(-1, g, 1, com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET);
        s.set(0, g, 0, s.snowy ? com.nirncraft.worldgen.VanillaBlocks.WHITE_CARPET : Blocks.MOSS_CARPET);
        // a victim's pack half buried in the back
        BlockPos c = s.chest(2, g, -1, Direction.WEST, "explore/cache");
        if (s.chance(7, 0.5)) s.put(c, note(s, "traveller", "Torn Pages"));
        // gnawed bones and a warning outside
        for (int i = 0; i < 4; i++) {
            int x = s.vary(230 + i, -4, 4), z = s.vary(240 + i, 3, 6);
            if (s.dry(x, z)) s.lying(x, s.top(x, z), z, new ItemStack(Items.BONE));
        }
        if (s.chance(8, 0.6)) s.standingSign(3, s.top(3, 5), 5, 8, "", Lore.warning(RandomSource.create(s.seed ^ 0x77L)), "", "");
        return true;
    }

    // ------------------------------------------------------------------ a lone cabin someone lives in

    static boolean cabin(Stage s) {
        String style = s.kind.style();
        boolean stilt = style.equals("stilt");
        boolean stone = style.equals("stone");
        boolean mud = style.equals("mud");
        flat(s, stilt ? 4 : 6);
        int g = stilt ? 3 : 0;
        Block wall = stone ? s.b.pal.stone() : mud ? (s.sandy ? Blocks.SMOOTH_SANDSTONE : Blocks.MUD_BRICKS) : s.wood.planks();
        Block corner = stone ? s.b.pal.brick() : mud ? (s.sandy ? Blocks.CUT_SANDSTONE : Blocks.PACKED_MUD) : s.wood.log();
        // stilts for a marsh hut, with a ladder up the front
        if (stilt) {
            for (int[] c : new int[][]{{-3, -2}, {3, -2}, {-3, 3}, {3, 3}, {0, -2}, {0, 3}}) {
                for (int y = 0; y < g; y++) s.set(c[0], y, c[1], log(s, Direction.Axis.Y));
                s.b.pillarDown(c[0], 0, c[1], log(s, Direction.Axis.Y));
            }
            for (int y = 0; y < g; y++) s.set(1, y, 4, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
            for (int dx = -1; dx <= 2; dx++) s.set(dx, g - 1, 4, s.wood.planks());
        }
        // floor and walls: 7 x 6
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                s.set(dx, g - 1, dz, s.wood.planks());
                boolean edge = Math.abs(dx) == 3 || dz == -2 || dz == 3;
                if (!edge) {
                    for (int y = 0; y <= 3; y++) s.air(dx, g + y, dz);
                    continue;
                }
                boolean isCorner = Math.abs(dx) == 3 && (dz == -2 || dz == 3);
                for (int y = 0; y <= 2; y++) s.set(dx, g + y, dz, isCorner ? corner : wall);
            }
        }
        // door on the trail side, windows on the flanks
        door(s, 1, g, 3, Direction.SOUTH);
        s.set(-3, g + 1, 0, Blocks.GLASS_PANE);
        s.set(3, g + 1, 1, Blocks.GLASS_PANE);
        s.set(-1, g + 1, 3, Blocks.GLASS_PANE);
        // the roof: a gable of slabs and stairs for timber and stone, flat with an awning for mud brick
        if (mud) {
            for (int dx = -4; dx <= 4; dx++) for (int dz = -3; dz <= 4; dz++) s.set(dx, g + 3, dz, slab(Blocks.SMOOTH_SANDSTONE_SLAB, SlabType.BOTTOM));
            for (int dx = -1; dx <= 3; dx++) s.set(dx, g + 2, 5, s.b.pal.wool());
            s.set(-1, g, 5, s.wood.fence());
            s.set(-1, g + 1, 5, s.wood.fence());
            s.set(3, g, 5, s.wood.fence());
            s.set(3, g + 1, 5, s.wood.fence());
        } else {
            Block stairs = s.b.pal.stairs();
            for (int dx = -4; dx <= 4; dx++) {
                for (int k = 0; k <= 3; k++) {
                    int y = g + 3 + k;
                    if (k < 3) {
                        s.set(dx, y, -3 + k, s.b.stair(stairs, Direction.SOUTH));
                        s.set(dx, y, 4 - k, s.b.stair(stairs, Direction.NORTH));
                    } else {
                        s.set(dx, y - 1, 0, slab(s.wood.slab(), SlabType.TOP));
                        s.set(dx, y - 1, 1, slab(s.wood.slab(), SlabType.TOP));
                    }
                }
            }
            for (int k = 0; k < 3; k++) {
                for (int dz = -2 + k; dz <= 3 - k; dz++) {
                    s.set(-3, g + 3 + k, dz, wall);
                    s.set(3, g + 3 + k, dz, wall);
                }
            }
            // the chimney, smoking
            for (int y = g; y <= g + 6; y++) s.set(-2, y, -1, Blocks.COBBLESTONE);
            s.set(-2, g + 7, -1, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
            s.set(-2, g, 0, Blocks.FURNACE.defaultBlockState());
        }
        // inside: a life
        Vignettes.bed(s, 2, g, -1, Direction.NORTH, Vignettes.bedFor(s));
        s.set(-2, g, 2, Blocks.BOOKSHELF);
        s.lectern(-1, g, -1, Direction.SOUTH, note(s, "cabin", "Kept by the Hearth"));
        s.set(0, g, 1, s.wood.fence());
        s.set(0, g + 1, 1, Blocks.OAK_PRESSURE_PLATE);
        s.set(-1, g, 1, s.b.stair(s.b.pal.stairs(), Direction.WEST));
        s.barrel(2, g, 2, "explore/hermit_food");
        s.chest(-2, g, 1, Direction.EAST, "explore/hermit");
        s.set(1, g, 0, Blocks.CAULDRON);
        s.set(0, g + 2, 0, hanging());
        s.set(1, g + 1, -2, Blocks.FLOWER_POT);
        // outside: a woodpile and a patch of garden (not for a hut on stilts)
        if (!stilt) {
            for (int dz = -1; dz <= 1; dz++) {
                s.set(4, s.top(4, dz), dz, log(s, Direction.Axis.Z));
                if (dz != 1) s.set(4, s.top(4, dz), dz, log(s, Direction.Axis.Z));
            }
            s.lying(5, s.top(5, 0), 0, worn(Items.IRON_AXE, 0.5));
            Block crop = mud ? Blocks.CARROTS : s.snowy ? Blocks.POTATOES : s.chance(11, 0.5) ? Blocks.WHEAT : Blocks.BEETROOTS;
            for (int i = 0; i < 6; i++) {
                int x = -5 + i % 3, z = -1 + i / 3;
                if (!s.dry(x, z)) continue;
                int y = s.top(x, z);
                s.set(x, y - 1, z, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE, 7));
                s.set(x, y, z, crop);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ a shrine cut into the rock

    static boolean alcove(Stage s) {
        int g = 0;
        String prov = s.site.province();
        // the rock: an outcrop behind (merging with the slope), the niche cut into its face
        s.blob(0, g + 2, -3, 5.0, 4.5, 3.5, 0.3, 11);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -3; dz <= 0; dz++) for (int dy = 0; dy <= 2; dy++) s.air(dx, g + dy, dz);
        }
        // the floor, the altar and its keeper
        for (int dx = -1; dx <= 1; dx++) for (int dz = -3; dz <= 1; dz++) s.set(dx, g - 1, dz, s.cutStone ? s.b.pal.brick().defaultBlockState() : s.rk(dx, g - 1, dz));
        s.set(0, g, -3, s.b.pal.brick());
        s.set(0, g + 1, -3, Blocks.CHISELED_STONE_BRICKS);
        switch (prov) {
            case "morrowind" -> { // an ancestor shrine: urns and soul light
                s.set(-1, g, -3, Blocks.DECORATED_POT);
                s.set(1, g, -3, Blocks.DECORATED_POT);
                s.set(0, g + 2, -3, Blocks.SOUL_LANTERN);
                s.lying(0, g, -2, new ItemStack(Items.BONE_MEAL));
            }
            case "valenwood", "black_marsh" -> { // Y'ffre's green, or the Hist's roots
                s.set(-1, g, -3, Blocks.MOSS_BLOCK);
                s.set(1, g, -3, Blocks.MANGROVE_ROOTS);
                s.set(0, g + 2, -3, Blocks.SHROOMLIGHT);
                s.set(0, g, -2, Vignettes.flower(s, 3));
            }
            case "hammerfell", "yokuda" -> { // Tu'whacca's sand and a lamp
                s.set(-1, g, -3, Blocks.SAND);
                s.set(1, g, -3, Blocks.SAND);
                s.lantern(0, g + 2, -3, false);
            }
            case "elsweyr" -> { // the moons: a pale and a dark disc
                s.set(-1, g + 1, -3, Blocks.CALCITE);
                s.set(1, g + 1, -3, Blocks.BLACKSTONE);
                s.lying(0, g, -2, new ItemStack(Items.SUGAR));
            }
            default -> { // the Divines: candles and a banner
                Vignettes.candle(s, -1, g, -3, 3, true);
                Vignettes.candle(s, 1, g, -3, 2, true);
                s.b.banner(0, g + 2, -3, s.b.dye(net.minecraft.world.item.DyeColor.WHITE));
            }
        }
        s.lying(0, g, -1, Vignettes.offering(s, 4));
        s.lectern(1, g, -1, Direction.WEST, note(s, "pilgrim", "Words Left at the Shrine"));
        // worn steps up to it, and a path of candles along both sides leading the way in from the trail
        for (int dx = -1; dx <= 1; dx++) s.set(dx, s.top(dx, 2), 2, s.b.stair(s.b.pal.brickStairs(), Direction.NORTH));
        for (int dz = 3; dz <= s.rad; dz += 2) {
            for (int dx : new int[]{-2, 2}) {
                if (!s.dry(dx, dz) || !s.free(dx, s.top(dx, dz), dz)) continue;
                int y = s.top(dx, dz);
                s.set(dx, y - 1, dz, s.rk(dx, y - 1, dz));
                Vignettes.candle(s, dx, y, dz, 1 + s.vary(400 + dz, 0, 2), s.chance(410 + dz * 3 + dx, 0.7));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ a traveller's rest at a well (desert, savanna)

    static boolean well(Stage s) {
        flat(s, 5);
        int g = 0;
        // the well: a sandstone ring round water, a post-and-beam frame with a bucket hanging
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    s.set(0, g - 1, 0, Blocks.WATER);
                    s.set(0, g - 2, 0, Blocks.WATER);
                    s.set(0, g - 3, 0, Blocks.CLAY);
                } else {
                    s.set(dx, g, dz, Blocks.CUT_SANDSTONE);
                    s.set(dx, g - 1, dz, Blocks.SANDSTONE);
                    s.set(dx, g - 2, dz, Blocks.SANDSTONE);
                }
            }
        }
        for (int dx : new int[]{-1, 1}) {
            s.set(dx, g + 1, 0, s.wood.fence());
            s.set(dx, g + 2, 0, s.wood.fence());
        }
        s.set(0, g + 3, 0, log(s, Direction.Axis.X));
        s.set(-1, g + 3, 0, log(s, Direction.Axis.X));
        s.set(1, g + 3, 0, log(s, Direction.Axis.X));
        s.set(0, g + 2, 0, com.nirncraft.worldgen.VanillaBlocks.CHAIN);
        // an awning on poles for the heat of the day
        for (int[] c : new int[][]{{2, 2}, {4, 2}, {2, 4}, {4, 4}}) {
            s.set(c[0], g, c[1], s.wood.fence());
            s.set(c[0], g + 1, c[1], s.wood.fence());
        }
        for (int dx = 2; dx <= 4; dx++) for (int dz = 2; dz <= 4; dz++) s.set(dx, g + 2, dz, s.chance(dx + dz, 0.5) ? s.b.pal.wool() : s.b.pal.accentWool());
        Vignettes.bed(s, 3, g, 3, Direction.EAST, Vignettes.bedFor(s));
        s.barrel(-3, g, 2, "explore/camp");
        s.set(-3, g, 3, Blocks.FLOWER_POT);
        s.set(-2, g, 3, Blocks.DECORATED_POT);
        s.standingSign(0, g, 2, 8, "Water for all.", "Take what you", "need, no more.", "");
        return true;
    }

    // ------------------------------------------------------------------ an apiary at the edge of a meadow

    static boolean apiary(Stage s) {
        flat(s, 5);
        int g = 0;
        for (int[] c : new int[][]{{-2, -1}, {0, -2}, {2, -1}}) {
            s.set(c[0], g, c[1], s.wood.fence());
            s.set(c[0], g + 1, c[1], Blocks.BEEHIVE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        }
        s.set(-3, g, 1, log(s, Direction.Axis.Y));
        s.set(-3, g + 1, 1, Blocks.BEE_NEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        for (int i = 0; i < 14; i++) {
            int x = s.vary(250 + i, -4, 4), z = s.vary(270 + i, 0, 4);
            if (s.dry(x, z) && s.free(x, s.top(x, z), z)) s.set(x, s.top(x, z), z, Vignettes.flower(s, 290 + i));
        }
        BlockPos b = s.barrel(3, g, 1, "explore/beehive");
        s.put(b, note(s, "beekeeper", "The Keeper's Book"));
        s.set(3, g, 2, Blocks.CAULDRON);
        s.lying(2, g, 2, new ItemStack(Items.GLASS_BOTTLE, 2));
        s.set(-1, g, 2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        return true;
    }

    // ------------------------------------------------------------------ an alchemist's herb patch

    static boolean herbs(Stage s) {
        flat(s, 5);
        int g = 0;
        Block[] pool = s.snowy ? new Block[]{ArcaneRegistry.FROST_MIRRIAM.get(), ArcaneRegistry.floraBlock("snowberries"), ArcaneRegistry.floraBlock("thistle_branch")}
                : s.wet ? new Block[]{ArcaneRegistry.DEATHBELL.get(), ArcaneRegistry.floraBlock("swamp_fungal_pod"), ArcaneRegistry.floraBlock("canis_root")}
                : new Block[]{ArcaneRegistry.NIGHTSHADE.get(), ArcaneRegistry.floraBlock("juniper_berries"), ArcaneRegistry.floraBlock("jazbay_grapes"),
                        ArcaneRegistry.floraBlock("imp_stool"), ArcaneRegistry.floraBlock("bleeding_crown")};
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 0; dz++) {
                if (dz == -1) {
                    s.set(dx, g - 1, dz, s.b.pal.path());
                    continue;
                }
                s.set(dx, g - 1, dz, Blocks.PODZOL);
                if (s.chance(300 + dx * 7 + dz, 0.8)) s.set(dx, g, dz, pool[Math.floorMod(s.vary(320 + dx * 5 + dz, 0, 99), pool.length)]);
            }
        }
        for (int dx = -4; dx <= 4; dx++) {
            s.set(dx, s.top(dx, -4), -4, s.wood.fence());
            s.set(dx, s.top(dx, 1), 1, dx == 0 ? Blocks.AIR.defaultBlockState() : s.wood.fence().defaultBlockState());
        }
        // a scarecrow, the brewing table, the herbalist's notes
        s.set(4, g, -2, s.wood.fence());
        s.set(4, g + 1, -2, Blocks.HAY_BLOCK);
        s.set(4, g + 2, -2, Blocks.CARVED_PUMPKIN.defaultBlockState());
        s.set(-2, g, 3, Blocks.BREWING_STAND);
        s.set(-1, g, 3, Blocks.CAULDRON);
        s.lectern(1, g, 3, Direction.NORTH, note(s, "herbalist", "Herbalist's Notes"));
        s.barrel(2, g, 3, "explore/herbalist");
        return true;
    }

    // ------------------------------------------------------------------ an old watch post, fallen in

    static boolean watchPost(Stage s) {
        flat(s, 5);
        int g = 0;
        // four posts: two still up, two down in the grass
        s.set(-2, g, -2, log(s, Direction.Axis.Y));
        for (int y = 0; y <= 4; y++) s.set(-2, g + y, -2, log(s, Direction.Axis.Y));
        for (int y = 0; y <= 4; y++) s.set(2, g + y, -2, log(s, Direction.Axis.Y));
        for (int k = 0; k <= 4; k++) s.set(-2 + k / 2, g, 3 + k % 2, log(s, Direction.Axis.Z));
        for (int k = 0; k <= 4; k++) s.set(3, g, -1 + k, log(s, Direction.Axis.Z));
        // what is left of the deck between the standing posts, the rest slid down as a ramp
        for (int dx = -2; dx <= 2; dx++) {
            s.set(dx, g + 5, -2, s.wood.planks());
            s.set(dx, g + 5, -1, slab(s.wood.slab(), SlabType.TOP));
        }
        for (int k = 0; k < 4; k++) s.set(-1 + (k % 2), g + 3 - k, k, slab(s.wood.slab(), k % 2 == 0 ? SlabType.TOP : SlabType.BOTTOM));
        // the brazier gone cold, the horn, the old guard's gear and a torn standard
        s.set(0, g + 6, -2, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
        s.b.standWith(1, 1, 200f, new ItemStack(Items.IRON_HELMET), new ItemStack(Items.CHAINMAIL_CHESTPLATE), ItemStack.EMPTY, ItemStack.EMPTY, worn(Items.IRON_SWORD, 0.7));
        s.lying(-1, g, 1, new ItemStack(Items.GOAT_HORN));
        BlockPos c = s.chest(-3, g, 0, Direction.EAST, "explore/lookout");
        s.put(c, note(s, "watch", "The Watch Log"));
        for (int y = 0; y < 3; y++) s.set(3, g + y, 3, s.wood.fence());
        s.b.banner(3, g + 3, 3, s.b.dye(net.minecraft.world.item.DyeColor.RED));
        s.rubbleRing(0, 0, 5, 6);
        return true;
    }

    // ------------------------------------------------------------------ a water trough for beasts of burden (unused helper kept small)

    static void trough(Stage s, int dx, int dy, int dz) {
        s.set(dx, dy, dz, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
    }
}
