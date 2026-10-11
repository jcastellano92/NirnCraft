package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.build.B;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Block families named the short way the style sheets use them: a stone or wood <i>material</i> ({@code "spruce"},
 * {@code "deepslate_tile"}, {@code "mud_brick"}) gives its full block, stairs, slab and wall; a <i>wood</i> gives a door,
 * fence, gate, trapdoor, signs, log and stripped log; a <i>colour</i> gives wool, carpet, bed, wall banner, terracotta
 * and stained glass. Plain block ids ({@code "minecraft:calcite"}) are looked up as they are. Unknown names fall back
 * to sensible vanilla blocks, so a typo never breaks worldgen.
 */
public final class Materials {
    private static final ConcurrentHashMap<String, Family> FAMILIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Wood> WOODS = new ConcurrentHashMap<>();

    private Materials() {}

    /** A building material: full block, stairs, slab and wall (or fence). */
    public record Family(BlockState full, BlockState stairs, BlockState slab, BlockState wall) {
    }

    /** A wood kit. */
    public record Wood(BlockState planks, BlockState stairs, BlockState slab, BlockState fence, BlockState gate, BlockState door,
                       BlockState trapdoor, BlockState sign, BlockState hangingSign, BlockState log, BlockState stripped) {
    }

    static boolean exists(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && ForgeRegistries.BLOCKS.containsKey(rl) && ForgeRegistries.BLOCKS.getValue(rl) != Blocks.AIR;
    }

    static String ns(String name) {
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    /** A single block: a full id, or a bare vanilla name. */
    public static BlockState block(String id, Block fallback) {
        if (id == null || id.isEmpty()) return fallback.defaultBlockState();
        return B.id(ns(id), fallback);
    }

    public static Family family(String name) {
        if (name == null || name.isEmpty()) name = "cobblestone";
        return FAMILIES.computeIfAbsent(name, Materials::resolveFamily);
    }

    private static Family resolveFamily(String name) {
        String n = ns(name);
        String full = null;
        for (String c : new String[]{n + "s", n + "_planks", n + "_block", n}) {
            if (exists(c) && !c.equals("minecraft:bamboo")) {
                full = c;
                break;
            }
        }
        // stairs and slabs drop a trailing "s" family-wise (stone_bricks -> stone_brick_stairs)
        String base = n.endsWith("_bricks") || n.endsWith("_tiles") ? n.substring(0, n.length() - 1) : n;
        if (base.equals("minecraft:quartz_block")) base = "minecraft:quartz";
        if (base.equals("minecraft:purpur_block")) base = "minecraft:purpur";
        BlockState fullS = full != null ? B.id(full, Blocks.COBBLESTONE) : Blocks.COBBLESTONE.defaultBlockState();
        BlockState stairs = B.id(base + "_stairs", Blocks.STONE_BRICK_STAIRS);
        BlockState slab = B.id(base + "_slab", Blocks.STONE_BRICK_SLAB);
        BlockState wall = exists(base + "_wall") ? B.id(base + "_wall", Blocks.COBBLESTONE_WALL)
                : exists(base + "_fence") ? B.id(base + "_fence", Blocks.OAK_FENCE) : Blocks.COBBLESTONE_WALL.defaultBlockState();
        return new Family(fullS, stairs, slab, wall);
    }

    public static Wood wood(String name) {
        if (name == null || name.isEmpty()) name = "spruce";
        return WOODS.computeIfAbsent(name, Materials::resolveWood);
    }

    private static Wood resolveWood(String name) {
        String n = ns(name);
        boolean stem = n.endsWith("crimson") || n.endsWith("warped");
        boolean bamboo = n.endsWith("bamboo");
        String log = bamboo ? n + "_block" : stem ? n + "_stem" : n + "_log";
        String ns = n.substring(0, n.indexOf(':') + 1), path = n.substring(n.indexOf(':') + 1);
        String stripped = ns + "stripped_" + path + (bamboo ? "_block" : stem ? "_stem" : "_log");
        return new Wood(
                B.id(n + "_planks", Blocks.SPRUCE_PLANKS),
                B.id(n + "_stairs", Blocks.SPRUCE_STAIRS),
                B.id(n + "_slab", Blocks.SPRUCE_SLAB),
                B.id(n + "_fence", Blocks.SPRUCE_FENCE),
                B.id(n + "_fence_gate", Blocks.SPRUCE_FENCE_GATE),
                B.id(n + "_door", Blocks.SPRUCE_DOOR),
                B.id(n + "_trapdoor", Blocks.SPRUCE_TRAPDOOR),
                B.id(n + "_wall_sign", Blocks.SPRUCE_WALL_SIGN),
                B.id(n + "_wall_hanging_sign", Blocks.SPRUCE_WALL_HANGING_SIGN),
                B.id(log, Blocks.SPRUCE_LOG),
                B.id(stripped, Blocks.STRIPPED_SPRUCE_LOG));
    }

    /** Dyed blocks of a colour ({@code "red"}, {@code "light_blue"}...). */
    public static BlockState wool(String color) {
        return B.id("minecraft:" + color + "_wool", Blocks.WHITE_WOOL);
    }

    public static BlockState carpet(String color) {
        return B.id("minecraft:" + color + "_carpet", Blocks.WHITE_CARPET);
    }

    public static BlockState bed(String color) {
        return B.id("minecraft:" + color + "_bed", Blocks.WHITE_BED);
    }

    public static BlockState banner(String color) {
        return B.id("minecraft:" + color + "_wall_banner", Blocks.WHITE_WALL_BANNER);
    }

    public static BlockState terracotta(String color) {
        return B.id("minecraft:" + color + "_terracotta", Blocks.TERRACOTTA);
    }

    public static BlockState glassPane(String color) {
        return B.id("minecraft:" + color + "_stained_glass_pane", Blocks.GLASS_PANE);
    }

    /** The colour name of a dyed block ("red" for red wool), or {@code fallback}. */
    public static String colorOf(BlockState s, String fallback) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id == null) return fallback;
        String p = id.getPath();
        for (String suffix : new String[]{"_wool", "_carpet", "_terracotta", "_bed", "_concrete"}) {
            if (p.endsWith(suffix)) return p.substring(0, p.length() - suffix.length());
        }
        return fallback;
    }
}
