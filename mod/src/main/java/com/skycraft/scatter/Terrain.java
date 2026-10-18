package com.skycraft.scatter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** What the ground is made of, and the rock that goes with it: props take their stone from the land they sit on. */
public final class Terrain {
    private Terrain() {}

    public enum Ground {
        SOIL, SAND, STONE, SNOW, MUD, ASH;

        public static Ground byId(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    /** Classifies a surface block by its registry path (works for Skycraft's own ground blocks too). */
    public static Ground ground(BlockState s) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        String p = key == null ? "" : key.getPath();
        if (p.contains("snow") || p.contains("ice") || p.contains("frost")) return Ground.SNOW;
        if (p.contains("ash") || p.contains("volcanic") || p.contains("basalt") || p.contains("blackstone")
                || p.contains("netherrack") || p.contains("magma") || p.contains("deadlands") || p.contains("soul")) return Ground.ASH;
        if (p.contains("sand") || p.contains("dune")) return Ground.SAND;
        if (p.contains("mud") || p.contains("clay") || p.contains("mangrove")) return Ground.MUD;
        if (p.contains("stone") || p.contains("cobble") || p.contains("andesite") || p.contains("granite") || p.contains("diorite")
                || p.contains("tuff") || p.contains("deepslate") || p.contains("gravel") || p.contains("calcite")
                || p.contains("terracotta") || p.contains("rock") || p.contains("ore") || p.contains("dripstone")) return Ground.STONE;
        return Ground.SOIL;
    }

    /** True for the packed-earth block a road or trail is made of (props keep off it). */
    public static boolean isPath(BlockState s) {
        return s.is(Blocks.DIRT_PATH);
    }

    /** The stones of one landscape: three tones and the slab, wall and stairs of the plainest. */
    public record RockSet(Block a, Block b, Block c, Block slab, Block wall, Block stairs) {
        public static final RockSet TEMPERATE = new RockSet(Blocks.STONE, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
                Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_WALL, Blocks.COBBLESTONE_STAIRS);
        public static final RockSet COLD = new RockSet(Blocks.STONE, Blocks.ANDESITE, Blocks.CALCITE,
                Blocks.ANDESITE_SLAB, Blocks.ANDESITE_WALL, Blocks.ANDESITE_STAIRS);
        public static final RockSet SAND = new RockSet(Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE,
                Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_WALL, Blocks.SANDSTONE_STAIRS);
        public static final RockSet RED_SAND = new RockSet(Blocks.RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE,
                Blocks.RED_SANDSTONE_SLAB, Blocks.RED_SANDSTONE_WALL, Blocks.RED_SANDSTONE_STAIRS);
        public static final RockSet MUD = new RockSet(Blocks.PACKED_MUD, Blocks.MUD_BRICKS, Blocks.MOSSY_COBBLESTONE,
                Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_WALL, Blocks.MUD_BRICK_STAIRS);
        public static final RockSet ASH = new RockSet(Blocks.BLACKSTONE, Blocks.BASALT, Blocks.TUFF,
                Blocks.BLACKSTONE_SLAB, Blocks.BLACKSTONE_WALL, Blocks.BLACKSTONE_STAIRS);

        public static RockSet of(Ground g, BlockState surface) {
            return switch (g) {
                case SAND -> {
                    Identifier k = BuiltInRegistries.BLOCK.getKey(surface.getBlock());
                    yield k != null && k.getPath().contains("red") ? RED_SAND : SAND;
                }
                case SNOW -> COLD;
                case MUD -> MUD;
                case ASH -> ASH;
                default -> TEMPERATE;
            };
        }

        public Block pick(net.minecraft.util.RandomSource r) {
            int i = r.nextInt(6);
            return i < 3 ? a : i < 5 ? b : c;
        }
    }

    /** A block by id ({@code minecraft:} assumed), or {@code fallback} when it is not registered. */
    public static Block block(String id, Block fallback) {
        Block b = lookup(id);
        return b == null ? fallback : b;
    }

    @Nullable
    public static Block lookup(String id) {
        Identifier rl = id.indexOf(':') < 0 ? Identifier.fromNamespaceAndPath("minecraft", id) : Identifier.parse(id);
        return BuiltInRegistries.BLOCK.containsKey(rl) ? BuiltInRegistries.BLOCK.getValue(rl) : null;
    }

    /** Applies {@code "axis=x,half=top"} style properties; unknown names and values are ignored. */
    public static BlockState withProps(BlockState s, String props) {
        for (String kv : props.split(",")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            String k = kv.substring(0, e).trim(), v = kv.substring(e + 1).trim();
            for (Property<?> p : s.getProperties()) {
                if (p.getName().equals(k)) {
                    s = setProp(s, p, v);
                    break;
                }
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState setProp(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(val -> s.setValue(p, val)).orElse(s);
    }

    public static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }
}
