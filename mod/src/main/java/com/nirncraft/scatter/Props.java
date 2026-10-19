package com.nirncraft.scatter;

import com.nirncraft.explore.ExploreLoot;
import com.nirncraft.explore.build.Palette;
import com.nirncraft.explore.build.SiteBuilder;
import com.nirncraft.scatter.ScatterRules.Entry;
import com.nirncraft.scatter.ScatterRules.Kind;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Builds data-defined props ({@code kinds.json} variants). Block specs are {@code id[props]} or a palette token:
 * <ul>
 *     <li>culture wood and stone ({@link Palette}): {@code $log $planks $slab $stairs $fence $trapdoor $wool $accent
 *     $carpet $stone $brick $mossy $wall $path};</li>
 *     <li>the rock of the ground it stands on ({@link Terrain.RockSet}): {@code $rock} (random tone), {@code $rockA
 *     $rockB $rockC $rockslab $rockwall $rockstairs};</li>
 *     <li>{@code $air} carves, {@code $ground} repeats the surface block.</li>
 * </ul>
 */
public final class Props {
    private Props() {}

    public static boolean build(Brush br, Kind k) {
        if (k.variants.isEmpty()) return false;
        List<Entry> entries = k.variants.get(br.r.nextInt(k.variants.size()));
        for (Entry e : entries) {
            if (e.chance < 1f && br.r.nextFloat() >= e.chance) continue;
            apply(br, k, e);
        }
        br.finish();
        return br.written > 0;
    }

    private static void apply(Brush br, Kind k, Entry e) {
        SiteBuilder b = br.b;
        switch (e.type) {
            case "fill" -> {
                int x0 = Math.min(e.p[0], e.p2[0]), x1 = Math.max(e.p[0], e.p2[0]);
                int y0 = Math.min(e.p[1], e.p2[1]), y1 = Math.max(e.p[1], e.p2[1]);
                int z0 = Math.min(e.p[2], e.p2[2]), z1 = Math.max(e.p[2], e.p2[2]);
                for (int x = x0; x <= x1; x++)
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++) block(br, k, x, y, z, e);
            }
            case "block" -> block(br, k, e.p[0], e.p[1], e.p[2], e);
            case "item" -> {
                Item item = item(e.item);
                if (item == null) return;
                ItemStack st = new ItemStack(item, Math.max(1, e.count));
                if (e.damage > 0 && st.isDamageableItem()) st.setDamageValue((int) (st.getMaxDamage() * e.damage));
                int dy = k.follow ? br.gy(e.p[0], e.p[2]) + e.p[1] : e.p[1];
                b.lying(e.p[0], dy, e.p[2], st);
            }
            case "chest", "barrel" -> {
                int dy = k.follow ? br.gy(e.p[0], e.p[2]) + e.p[1] : e.p[1];
                if (!b.free(e.p[0], dy, e.p[2])) return;
                Identifier loot = e.loot == null ? null : e.loot.indexOf(':') >= 0 ? Identifier.parse(e.loot) : ExploreLoot.table(e.loot);
                if (e.type.equals("chest")) b.chest(e.p[0], dy, e.p[2], dir(e.facing), loot == null ? ExploreLoot.table("camp") : loot);
                else b.barrel(e.p[0], dy, e.p[2], loot);
                br.written++;
            }
            case "skull" -> {
                int dy = (k.follow || e.p[1] == 0) ? br.gy(e.p[0], e.p[2]) + e.p[1] : e.p[1];
                if (e.p[1] > 0 && b.free(e.p[0], dy - 1, e.p[2])) return;
                if (b.free(e.p[0], dy, e.p[2])) {
                    b.skull(e.p[0], dy, e.p[2]);
                    br.written++;
                }
            }
            case "banner" -> {
                int dy = (k.follow || e.p[1] == 0) ? br.gy(e.p[0], e.p[2]) + e.p[1] : e.p[1];
                if (e.p[1] > 0 && b.free(e.p[0], dy - 1, e.p[2])) return;
                if (b.free(e.p[0], dy, e.p[2])) {
                    b.banner(e.p[0], dy, e.p[2], color(b, e.color));
                    br.written++;
                }
            }
            default -> { }
        }
    }

    private static void block(Brush br, Kind k, int dx, int dy, int dz, Entry e) {
        if (e.blocks.length == 0) return;
        String spec = e.blocks.length == 1 ? e.blocks[0] : e.blocks[br.r.nextInt(e.blocks.length)];
        BlockState s = resolve(br, spec);
        if (s == null) return;
        if (k.follow) {
            br.top(dx, dz, dy, s);
            return;
        }
        if (s.isAir()) {
            br.force(dx, dy, dz, s);
            return;
        }
        // Soft surface coverings (carpets, cobwebs) at dy == 0 snap directly onto the local ground
        if (dy == 0 && (s.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock || s.is(Blocks.COBWEB))) {
            br.top(dx, dz, 0, s);
            return;
        }
        // Never place an upper block (dy > 0) floating over air when its supporting block below was skipped
        if (dy > 0 && br.b.free(dx, dy - 1, dz)) {
            return;
        }
        if (br.set(dx, dy, dz, s) && dy == 0) {
            BlockState supportMat = s.isSolidRender()
                    ? Blocks.DIRT.defaultBlockState()
                    : Blocks.COARSE_DIRT.defaultBlockState();
            br.support(dx, dy, dz, supportMat);
        }
    }

    @Nullable
    public static BlockState resolve(Brush br, String spec) {
        String id = spec, props = null;
        int i = spec.indexOf('[');
        if (i >= 0 && spec.endsWith("]")) {
            id = spec.substring(0, i);
            props = spec.substring(i + 1, spec.length() - 1);
        }
        Block blk = id.startsWith("$") ? token(br, id) : Terrain.lookup(id);
        if (blk == null) return null;
        BlockState s = blk.defaultBlockState();
        return props == null ? s : Terrain.withProps(s, props);
    }

    @Nullable
    private static Block token(Brush br, String t) {
        Palette p = br.b.pal;
        return switch (t) {
            case "$log" -> p.log();
            case "$planks" -> p.planks();
            case "$slab" -> p.slab();
            case "$stairs" -> p.stairs();
            case "$fence" -> p.fence();
            case "$trapdoor" -> p.trapdoor();
            case "$wool" -> p.wool();
            case "$accent" -> p.accentWool();
            case "$carpet" -> p.carpet();
            case "$stone" -> p.stone();
            case "$brick" -> p.brick();
            case "$mossy" -> p.mossy();
            case "$wall" -> p.wall();
            case "$path" -> p.path();
            case "$rock" -> br.rock.pick(br.r);
            case "$rockA" -> br.rock.a();
            case "$rockB" -> br.rock.b();
            case "$rockC" -> br.rock.c();
            case "$rockslab" -> br.rock.slab();
            case "$rockwall" -> br.rock.wall();
            case "$rockstairs" -> br.rock.stairs();
            case "$air" -> Blocks.AIR;
            default -> null;
        };
    }

    @Nullable
    public static Item item(String id) {
        Identifier rl = id.indexOf(':') < 0 ? Identifier.fromNamespaceAndPath("minecraft", id) : Identifier.parse(id);
        return BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.getValue(rl) : null;
    }

    private static Direction dir(String s) {
        Direction d = Direction.byName(s);
        return d == null || d.getAxis().isVertical() ? Direction.SOUTH : d;
    }

    private static DyeColor color(SiteBuilder b, String c) {
        if (c.equals("$banner")) return b.dye(DyeColor.RED);
        DyeColor d = DyeColor.byName(c, null);
        return d == null ? b.dye(DyeColor.RED) : d;
    }
}
