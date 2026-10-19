package com.nirncraft.tamriel.settlement.build;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.settlement.kit.Palette;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Furniture and NirnCraft stations. Station blocks are looked up by id (other modules own them) with a vanilla
 * stand-in, so a missing module never breaks worldgen.
 */
public final class Props {
    private Props() {}

    // ------------------------------------------------------------------ stations (nirncraft ids, vanilla fallbacks)

    public static BlockState forge() {
        return B.id("nirncraft:blacksmith_forge", Blocks.BLAST_FURNACE);
    }

    public static BlockState smelter() {
        return B.id("nirncraft:smelter", Blocks.FURNACE);
    }

    public static BlockState workbench() {
        return B.id("nirncraft:armor_workbench", Blocks.SMITHING_TABLE);
    }

    public static BlockState grindstone() {
        return B.id("nirncraft:grindstone_wheel", Blocks.GRINDSTONE);
    }

    public static BlockState tanningRack() {
        return B.id("nirncraft:tanning_rack", Blocks.LOOM);
    }

    public static BlockState alchemyLab() {
        return B.id("nirncraft:alchemy_lab", Blocks.BREWING_STAND);
    }

    public static BlockState enchanter() {
        return B.id("nirncraft:arcane_enchanter", Blocks.ENCHANTING_TABLE);
    }

    public static BlockState cookingPot() {
        return B.id("nirncraft:cooking_pot", Blocks.CAULDRON);
    }

    public static BlockState cheese() {
        return B.id("nirncraft:cheese_wheel", Blocks.CAKE);
    }

    public static BlockState shrine(String divine) {
        return B.id("nirncraft:shrine_of_" + divine, Blocks.LECTERN);
    }

    public static BlockState ore(String id, net.minecraft.world.level.block.Block fallback) {
        return B.id("nirncraft:" + id + "_ore", fallback);
    }

    public static final String[] DIVINES = {"akatosh", "arkay", "dibella", "julianos", "kynareth", "mara", "stendarr", "talos", "zenithar"};

    // ------------------------------------------------------------------ loot tables (household loot: theft is a crime)

    public static Identifier loot(String name) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/village/" + name);
    }

    private static String palName(Palette p) {
        return (p != null && p.name != null && !p.name.isEmpty()) ? p.name : "nord";
    }

    /** A custom 3D cultural table at (x, y, z). */
    public static void table(Canvas c, Palette p, int x, int y, int z) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.TABLE, palName(p), Direction.SOUTH);
    }

    /** A custom 3D cultural banquet table segment at (x, y, z). */
    public static void banquetTable(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.BANQUET_TABLE, palName(p), facing);
    }

    /** A custom 3D cultural nightstand at (x, y, z). */
    public static void nightstand(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.NIGHTSTAND, palName(p), facing);
    }

    /** A custom 3D cultural shop/inn counter at (x, y, z). */
    public static void counter(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.COUNTER, palName(p), facing);
    }

    /** A custom 3D cultural writing desk at (x, y, z) with slanted lectern, ledger, inkwell, and quill. */
    public static void desk(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.DESK, palName(p), facing);
    }

    /** A custom 3D cultural chair at (x, y, z) whose seat faces away from {@code back}. */
    public static void chair(Canvas c, Palette p, int x, int y, int z, Direction back) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CHAIR, palName(p), back.getOpposite());
    }

    /** A custom 3D cultural stool at (x, y, z). */
    public static void stool(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.STOOL, palName(p), facing);
    }

    /** A custom 3D cultural bench at (x, y, z) facing {@code facing}. */
    public static void bench(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.BENCH, palName(p), facing);
    }

    /** A custom 3D cultural throne at (x, y, z) facing {@code facing}. */
    public static void throne(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.THRONE, palName(p), facing);
    }

    /** A custom 3D cultural wardrobe container at (x, y, z) facing {@code facing}. */
    public static void wardrobe(Canvas c, Palette p, int x, int y, int z, Direction facing, String loot) {
        c.furnitureContainer(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WARDROBE,
                palName(p), facing, loot == null ? null : loot(loot));
    }

    /** A custom 3D cultural dresser container at (x, y, z) facing {@code facing}. */
    public static void dresser(Canvas c, Palette p, int x, int y, int z, Direction facing, String loot) {
        c.furnitureContainer(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.DRESSER,
                palName(p), facing, loot == null ? null : loot(loot));
    }

    /** A custom 3D cultural cabinet container at (x, y, z) facing {@code facing}. */
    public static void cabinet(Canvas c, Palette p, int x, int y, int z, Direction facing, String loot) {
        c.furnitureContainer(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CABINET,
                palName(p), facing, loot == null ? null : loot(loot));
    }

    /** A custom 3D cultural weapon rack with visible steel swords & shield at (x, y, z) facing {@code facing}. */
    public static void weaponRack(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.WEAPON_RACK, palName(p), facing);
    }

    /** A custom 3D cultural armor stand at (x, y, z) facing {@code facing}. */
    public static void armorStand(Canvas c, Palette p, int x, int y, int z, Direction facing) {
        c.furniture(x, y, z, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind.CLUTTER_ARMOR_STAND, palName(p), facing);
    }

    /** A custom 3D tabletop clutter piece at (x, y, z) facing {@code facing}. */
    public static void clutter(Canvas c, Palette p, int x, int y, int z,
                               com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind kind, Direction facing) {
        c.furniture(x, y, z, kind, palName(p), facing);
    }

    /** A custom 3D tabletop clutter piece at (x, y, z) facing {@code facing}. */
    public static void clutter(Canvas c, Palette p, int x, int y, int z,
                               Direction facing, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind kind) {
        c.furniture(x, y, z, kind, palName(p), facing);
    }

    /** A custom 3D wall mount/clutter piece at (x, y, z) facing outward {@code facing}. */
    public static void wallClutter(Canvas c, Palette p, int x, int y, int z,
                                   com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind kind, Direction facing) {
        c.furniture(x, y, z, kind, palName(p), facing);
    }

    /** A custom 3D wall mount/clutter piece at (x, y, z) facing outward {@code facing}. */
    public static void wallClutter(Canvas c, Palette p, int x, int y, int z,
                                   Direction facing, com.nirncraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind kind) {
        c.furniture(x, y, z, kind, palName(p), facing);
    }

    /** A chiseled bookshelf pre-populated with visible 3D leather-bound tomes facing {@code facing}. */
    public static void bookshelf(Canvas c, int x, int y, int z, Direction facing) {
        c.set(x, y, z, B.face(B.s(Blocks.CHISELED_BOOKSHELF), facing));
    }

    /** Places either a chiseled bookshelf with 3D visible books or a full bookshelf at (x, y, z) facing {@code facing}. */
    public static void bookshelf(Canvas c, int x, int y, int z, Direction facing, boolean chiseled) {
        if (chiseled) {
            c.set(x, y, z, B.face(B.s(Blocks.CHISELED_BOOKSHELF), facing));
        } else {
            c.set(x, y, z, B.s(Blocks.BOOKSHELF));
        }
    }

    public static void candles(Canvas c, int x, int y, int z, int count) {
        BlockState s = B.s(Blocks.CANDLE).setValue(CandleBlock.CANDLES, Math.max(1, Math.min(4, count))).setValue(CandleBlock.LIT, true);
        c.set(x, y, z, s);
    }

    public static void lantern(Canvas c, Palette p, int x, int y, int z, boolean hanging) {
        BlockState l = p.light;
        if (hanging && l.hasProperty(BlockStateProperties.HANGING)) {
            l = B.hanging(l);
            if (!c.recording() && c.get(x, y + 1, z).isAir()) {
                int roofY = -1;
                for (int cy = y + 2; cy <= y + 12; cy++) {
                    BlockState above = c.get(x, cy, z);
                    if (!above.isAir() && above.getFluidState().isEmpty() && !above.canBeReplaced()) {
                        roofY = cy;
                        break;
                    }
                }
                if (roofY < 0) return;
                for (int cy = y + 1; cy < roofY; cy++) c.set(x, cy, z, B.s(com.nirncraft.worldgen.VanillaBlocks.CHAIN));
            }
        }
        c.set(x, y, z, l);
    }

    public static void barrel(Canvas c, int x, int y, int z, String loot) {
        c.container(x, y, z, B.face(B.s(Blocks.BARREL), Direction.UP), loot == null ? null : loot(loot));
    }

    public static void chest(Canvas c, int x, int y, int z, Direction face, String loot) {
        c.container(x, y, z, B.face(B.s(Blocks.CHEST), face), loot == null ? null : loot(loot));
    }

    public static void rug(Canvas c, BlockState carpet, int x0, int y, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) c.setIfOpen(x, y, z, carpet);
    }

    public static void pot(Canvas c, int x, int y, int z, int salt) {
        BlockState[] pots = {B.s(Blocks.POTTED_FERN), B.s(Blocks.POTTED_RED_MUSHROOM), B.s(Blocks.POTTED_BLUE_ORCHID),
                B.s(Blocks.POTTED_DEAD_BUSH), B.s(Blocks.POTTED_ALLIUM), B.s(Blocks.POTTED_LILY_OF_THE_VALLEY), B.s(Blocks.FLOWER_POT)};
        BlockState s = pots[c.roll(x, y, z, salt, pots.length)];
        if (s.getBlock() instanceof FlowerPotBlock) c.set(x, y, z, s);
    }

    /** Fireplace in a wall: stone surround, fire, and a cooking pot hung over it. */
    public static void hearth(Canvas c, Palette p, int x, int y, int z, Direction intoRoom) {
        Direction side = intoRoom.getClockWise();
        c.set(x, y, z, B.lit(p.hearth, true));
        c.set(x, y + 1, z, cookingPot());
        c.set(x + side.getStepX(), y, z + side.getStepZ(), p.foundation);
        c.set(x - side.getStepX(), y, z - side.getStepZ(), p.foundation);
        c.set(x + side.getStepX(), y + 1, z + side.getStepZ(), p.foundation);
        c.set(x - side.getStepX(), y + 1, z - side.getStepZ(), p.foundation);
        c.set(x + side.getStepX(), y + 2, z + side.getStepZ(), B.stair(p.trim, intoRoom.getOpposite(), true));
        c.set(x - side.getStepX(), y + 2, z - side.getStepZ(), B.stair(p.trim, intoRoom.getOpposite(), true));
        c.set(x, y + 2, z, p.foundation);
        c.keepClear(x, y, y + 2, z);
        c.keepClear(x + side.getStepX(), y, y + 2, z + side.getStepZ());
        c.keepClear(x - side.getStepX(), y, y + 2, z - side.getStepZ());
        c.keepClear(x + intoRoom.getStepX(), y, y + 1, z + intoRoom.getStepZ());
    }

    /** Sunken Nordic fire pit with a cooking pot (the floor block is replaced by the fire). */
    public static void firePit(Canvas c, Palette p, int x, int y, int z, int len, boolean alongZ) {
        for (int i = 0; i < len; i++) {
            int fx = alongZ ? x : x + i, fz = alongZ ? z + i : z;
            c.set(fx, y - 1, fz, B.lit(p.hearth, true));
            c.air(fx, y, fz);
        }
        for (int i = -1; i <= len; i++) {
            int ax = alongZ ? x - 1 : x + i, az = alongZ ? z + i : z - 1;
            int bx = alongZ ? x + 1 : x + i, bz = alongZ ? z + i : z + 1;
            c.set(ax, y - 1, az, p.foundation);
            c.set(bx, y - 1, bz, p.foundation);
            c.set(ax, y, az, B.slab(p.trimSlab, false));
            c.set(bx, y, bz, B.slab(p.trimSlab, false));
        }
        int ex0 = alongZ ? x : x - 1, ez0 = alongZ ? z - 1 : z;
        int ex1 = alongZ ? x : x + len, ez1 = alongZ ? z + len : z;
        c.set(ex0, y - 1, ez0, p.foundation);
        c.set(ex1, y - 1, ez1, p.foundation);
        c.set(ex0, y, ez0, B.slab(p.trimSlab, false));
        c.set(ex1, y, ez1, B.slab(p.trimSlab, false));
        int mx = alongZ ? x : x + len / 2, mz = alongZ ? z + len / 2 : z;
        c.set(mx, y, mz, cookingPot());
    }

    /** Free-standing brazier: a fire on a stone stand. */
    public static void brazier(Canvas c, Palette p, int x, int y, int z) {
        c.set(x, y, z, p.foundation);
        c.set(x, y + 1, z, B.lit(B.s(Blocks.CAMPFIRE), true));
    }

    /** A banner-like hanging cloth (two blocks of accent colour) on a wall. */
    public static void tapestry(Canvas c, Palette p, int x, int y, int z) {
        c.set(x, y, z, p.accent);
        c.set(x, y - 1, z, p.accent);
    }

    /** A rack of three pelts / carpets on the wall. */
    public static void pelts(Canvas c, Palette p, int x, int y, int z) {
        c.setIfOpen(x, y, z, p.carpet2);
    }

    /**
     * 3D hanging shop sign: places a wall hanging sign block at {@code (x, y, z)} tagged with its
     * trade archetype and regional palette so {@code ShopSignRenderer} renders a bespoke 3D bracket,
     * regional plaque, and sculpted double-sided trade symbol.
     */
    public static void shopSign(Canvas c, Palette p, String useId, int x, int y, int z, Direction facing, Component title, Component subtitle) {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.sign.getBlock());
        BlockState hanging = id == null ? B.s(Blocks.SPRUCE_WALL_HANGING_SIGN)
                : B.id(id.toString().replace("_wall_sign", "_wall_hanging_sign"), Blocks.SPRUCE_WALL_HANGING_SIGN);
        Component emblem = Component.literal(tradeEmblem(useId));
        Component line2 = subtitle == null || subtitle.getString().equals(title.getString()) ? Component.empty() : subtitle;
        String safeUse = useId == null || useId.isEmpty() ? "general" : useId;
        String safePal = p == null || p.name == null || p.name.isEmpty() ? "nord" : p.name;
        Component meta = Component.literal("nirncraft:" + safeUse + ":" + safePal);
        c.sign(x, y, z, hanging, facing, emblem, title, line2, meta);
    }

    /**
     * An inn's frontage, shared by every inn builder so none comes out bare: the regional 3D sign is hung on the wall
     * beside the door (trying every free spot along the front) and a second one stands on a lit signpost at the street
     * edge, where travellers see it from the road. Barrels, a bench and wall torches round it off.
     *
     * @param dz the local row just in front of the wall (-1 for a building whose door wall is at z = 0)
     */
    public static void innFront(Canvas c, Palette p, int doorX, int minX, int maxX, int dz, Component title, Component subtitle) {
        if (c.recording()) return;
        int[] order = {1, -1, 2, -2, 3, -3, 4, -4};
        boolean hung = false;
        for (int o : order) {
            int sx = doorX + o;
            if (sx < minX + 1 || sx > maxX - 1) continue;
            if (!Canvas.open(c.get(sx, 3, dz)) || Canvas.open(c.get(sx, 3, dz + 1))) continue;
            c.set(sx, 3, dz, B.s(Blocks.AIR));
            shopSign(c, p, "inn", sx, 3, dz, Direction.EAST, title, subtitle);
            hung = true;
            break;
        }
        // a lit signpost out by the street, on the side away from the bench
        int px = doorX - 5 >= minX ? doorX - 5 : doorX + 5 <= maxX ? doorX + 5 : Integer.MIN_VALUE;
        int pz = dz - 1;
        if (px != Integer.MIN_VALUE && Canvas.open(c.get(px, 1, pz)) && Canvas.open(c.get(px, 3, pz - 1))) {
            c.setIfOpen(px, 0, pz, p.foundation);
            c.foundation(px, -1, pz, p.foundation, 3);
            for (int y = 1; y <= 4; y++) c.set(px, y, pz, B.axis(p.beam, Direction.Axis.Y));
            c.set(px, 5, pz, p.light);
            shopSign(c, p, "inn", px, 3, pz - 1, Direction.EAST, title, subtitle);
        } else if (!hung) {
            // nowhere to hang it: a sign on the lintel above the door
            c.set(doorX, 4, dz, B.s(Blocks.AIR));
            shopSign(c, p, "inn", doorX, 4, dz, Direction.EAST, title, subtitle);
        }
        // barrels and a bench on the other side of the door
        int bx = doorX + 4 <= maxX - 1 ? doorX + 4 : doorX - 4 >= minX + 1 ? doorX - 4 : Integer.MIN_VALUE;
        if (bx != Integer.MIN_VALUE) {
            for (int x = bx; x <= bx + 1 && x <= maxX; x++) {
                if (!Canvas.open(c.get(x, 1, dz))) continue;
                c.setIfOpen(x, 0, dz, p.foundation);
                c.foundation(x, -1, dz, p.foundation, 3);
                c.setIfOpen(x, 1, dz, x == bx ? B.face(B.s(Blocks.BARREL), Direction.UP) : B.stair(p.stairs, Direction.SOUTH, false));
            }
        }
        for (int o : new int[]{-2, 2}) {
            int tx = doorX + o;
            if (tx < minX + 1 || tx > maxX - 1) continue;
            BlockState torch = B.face(B.s(p.light.is(Blocks.SOUL_LANTERN) ? Blocks.SOUL_WALL_TORCH : Blocks.WALL_TORCH), Direction.NORTH);
            if (Canvas.open(c.get(tx, 2, dz)) && !Canvas.open(c.get(tx, 2, dz + 1))) c.set(tx, 2, dz, torch);
        }
    }

    private static String tradeEmblem(String useId) {
        if (useId == null) return "\u2726";
        return switch (useId) {
            case "smithy" -> "\u2692 \u2694 \u2692";
            case "apothecary", "alchemist" -> "\u2697 \u2740 \u2697";
            case "inn", "mead_hall" -> "\uD83C\uDF7A \u2668 \uD83C\uDF7A";
            case "general", "general_store", "store", "market_shop" -> "\u2696 \u2726 \u2696";
            case "fletcher" -> "\uD83C\uDFF9 \u27B3 \uD83C\uDFF9";
            case "mage", "enchanter", "bookseller", "mage_tower" -> "\u2721 \uD83D\uDCD6 \u2721";
            case "jeweler", "jeweller" -> "\u25C6 \uD83D\uDC8E \u25C6";
            case "bakery", "mill" -> "\u2740 \uD83E\uDD56 \u2740";
            case "tailor", "tannery" -> "\u2702 \uD83E\uDDF5 \u2702";
            case "butcher" -> "\uD83E\uDD69 \u2694 \uD83E\uDD69";
            case "fishery" -> "\uD83D\uDC1F \u2726 \uD83D\uDC1F";
            case "stables" -> "\u2658 \u2726 \u2658";
            case "temple", "hall_of_dead" -> "\u2727 \u2600 \u2727";
            case "keep", "palace", "longhouse", "hall" -> "\u265B \u2694 \u265B";
            case "barracks", "guardhouse", "jail" -> "\u26E8 \u2694 \u26E8";
            case "guild_hall", "guild" -> "\u2694 \u2726 \u2694";
            default -> "\u2726";
        };
    }
}
