package com.skycraft.dungeons.marquee;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.Skycraft;
import com.skycraft.dungeons.world.Kit;
import com.skycraft.dungeons.world.Painter;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A marquee dungeon's masonry: the culture kit's palette, with any of its parts replaced by the plan's own
 * {@code palette} block (weighted lists {@code "minecraft:calcite*8"}, single states, or {@code "kit"} to keep the kit's).
 */
final class Pal {
    private static final Map<String, BlockState> CACHE = new HashMap<>();

    private record Weighted(List<BlockState> states, List<Integer> weights, int total) {
        BlockState pick(int roll) {
            int r = roll % Math.max(1, total);
            for (int i = 0; i < states.size(); i++) {
                r -= weights.get(i);
                if (r < 0) return states.get(i);
            }
            return states.get(states.size() - 1);
        }
    }

    private final Kit.Palette kit;
    private final Weighted wall, floor, ceiling;
    final BlockState trim, light, dim, rail, pillar, cap, accent, rock;
    final net.minecraft.world.level.block.Block stair;

    Pal(MarqueePlans.Plan plan, Kit kitIn) {
        this.kit = (kitIn == Kit.NONE ? Kit.NORD : kitIn).palette();
        JsonObject o = plan.palette;
        wall = weighted(o, "wall");
        floor = weighted(o, "floor");
        ceiling = weighted(o, "ceiling");
        trim = single(o, "trim", kit.trim);
        light = single(o, "light", kit.light);
        dim = single(o, "dim", Blocks.REDSTONE_LAMP.defaultBlockState());
        rail = single(o, "rail", Blocks.IRON_BARS.defaultBlockState());
        pillar = single(o, "pillar", kit.pillar);
        cap = single(o, "cap", kit.pillarCap);
        accent = single(o, "accent", kit.accent);
        rock = single(o, "rock", kit.base != null ? kit.base : Blocks.STONE_BRICKS.defaultBlockState());
        stair = o.has("stair") ? parse(o.get("stair").getAsString()).getBlock() : kit.stairs;
    }

    BlockState wall(Painter p, int x, int y, int z, int rel) {
        if (wall != null) return rel <= 0 ? rock : wall.pick(p.roll(x, y, z, 1, 1000));
        return kit.wall(p, x, y, z, rel);
    }

    BlockState floor(Painter p, int x, int y, int z) {
        return floor != null ? floor.pick(p.roll(x, y, z, 2, 1000)) : kit.floor(p, x, y, z);
    }

    BlockState ceiling(Painter p, int x, int y, int z) {
        return ceiling != null ? ceiling.pick(p.roll(x, y, z, 3, 1000)) : kit.ceiling(p, x, y, z);
    }

    // ------------------------------------------------------------------ parsing

    @Nullable
    private static Weighted weighted(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return null;
        List<BlockState> states = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        JsonArray a = o.getAsJsonArray(key);
        for (JsonElement e : a) {
            String s = e.getAsString();
            int w = 1;
            int star = s.lastIndexOf('*');
            if (star > 0) {
                w = Integer.parseInt(s.substring(star + 1).trim());
                s = s.substring(0, star);
            }
            states.add(parse(s));
            weights.add(w);
            total += w;
        }
        return states.isEmpty() ? null : new Weighted(states, weights, total);
    }

    private static BlockState single(JsonObject o, String key, BlockState fallback) {
        if (!o.has(key)) return fallback;
        String s = o.get(key).getAsString();
        return s.equals("kit") ? fallback : parse(s);
    }

    /** A block state from text ({@code minecraft:candle[candles=3,lit=true]}); stone bricks if it cannot be read. */
    static synchronized BlockState parse(String text) {
        BlockState s = CACHE.get(text);
        if (s != null) return s;
        try {
            s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), text, false).blockState();
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Skycraft marquee: bad block {}", text);
            s = Blocks.STONE_BRICKS.defaultBlockState();
        }
        CACHE.put(text, s);
        return s;
    }
}
