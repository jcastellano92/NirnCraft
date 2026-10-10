package com.skycraft.dungeons.world;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.Places;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The grounds of the great named barrows and mines ({@link Special}): Bleak Falls' terrace of tall arches and
 * its great stair, Ustengrav's marsh ruins, the College's dig at Saarthal, Forelhost's fortified terraces,
 * Kolskeggr's mining camp and Ysgramor's statue over his tomb; and what waits in their last chambers (the
 * Dragonstone, the Horn's empty pedestal, the Eye of Magnus, Rahgot, Morokei).
 *
 * <p>The exterior piece is centred in front of the entrance; the portal's own approach stays untouched.</p>
 */
public final class Exteriors {
    private Exteriors() {}

    public static void build(Painter p, DungeonPiece c) {
        BoundingBox b = c.getBoundingBox();
        Frame fr = new Frame(p, (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2, c.floor, c.facing());
        int ef = fr.fwd(c.doors, c.variant); // the entrance, in local coordinates (about -18, 0)
        switch (c.special) {
            case BLEAK_FALLS -> bleakFalls(fr, ef);
            case USTENGRAV -> ustengrav(fr, ef);
            case SAARTHAL -> saarthal(fr, ef);
            case FORELHOST -> forelhost(fr, ef, c);
            case KOLSKEGGR -> kolskeggr(fr, ef);
            case YSGRAMOR -> ysgramor(fr, ef);
            default -> {
            }
        }
    }

    /** True for the portal's arch and approach, which the exterior must leave alone. */
    private static boolean reserved(int ef, int fw, int lt) {
        return fw >= ef - 4 && fw <= ef + 11 && Math.abs(lt) <= 6;
    }

    // ------------------------------------------------------------------ Bleak Falls Barrow

    private static void bleakFalls(Frame fr, int ef) {
        Painter p = fr.p;
        Frame.Masonry stone = Uniques.nordStone();
        // the terrace in front of the doors
        fr.all((fw, lt, x, z) -> {
            if (reserved(ef, fw, lt) || fw < ef || fw > ef + 22 || Math.abs(lt) > 14) return;
            p.clear(x, fr.base, z, fr.base + 14);
            p.set(x, fr.base - 1, z, BuiltBuilder.floor(p, Theme.BARROW, x, fr.base - 1, z));
            p.foundation(x, fr.base - 2, z, Blocks.STONE.defaultBlockState(), 14);
        });
        // the tall arches: paired pillars bridged by stepped stone, across the terrace
        for (int lt : new int[]{-12, -8, 8, 12}) {
            for (int fw : new int[]{ef + 4, ef + 12}) {
                for (int dy = 0; dy <= 11; dy++) fr.set(fw, dy, lt, dy == 0 || dy == 11 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : stone.at(p, fr.x(fw, lt), fr.base + dy, fr.z(fw, lt)));
            }
            for (int fw = ef + 5; fw <= ef + 11; fw++) {
                int arch = 11 - Math.max(0, 3 - Math.min(fw - (ef + 4), (ef + 12) - fw));
                fr.set(fw, arch, lt, Blocks.STONE_BRICKS);
            }
        }
        // the great stair down the mountain
        for (int i = 0; i <= 10; i++) {
            for (int lt = -3; lt <= 3; lt++) {
                int fw = ef + 23 + i;
                fr.set(fw, -1 - i, lt, fr.stair(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH, false));
                fr.clear(fw, -i, lt, fw, 3, lt);
                fr.foundation(fw, -2 - i, lt, Blocks.STONE.defaultBlockState(), 12);
            }
        }
        // the bandits who got here first
        Towers.tent(fr, ef + 16, -9, Blocks.BROWN_WOOL.defaultBlockState());
        Camps.fire(fr, ef + 17, 9);
        fr.container(ef + 15, 0, -12, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.spawn(Mobs.bandit(), ef + 14, 0, 10, fr.f, null);
        fr.spawn(Mobs.bandit(), ef + 18, 0, -6, fr.f, null);
        fr.spawn(Mobs.bandit(), ef + 9, 0, 11, fr.f, null);
    }

    // ------------------------------------------------------------------ Ustengrav

    private static void ustengrav(Frame fr, int ef) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            if (reserved(ef, fw, lt) || Math.hypot(fw - ef - 6, lt) > 16) return;
            double n = p.noise(x, 0, z, 3.0, 1000);
            if (n > 0.45 && p.ownsColumn(x, z)) {
                p.set(x, fr.base - 1, z, Blocks.WATER.defaultBlockState());
                if (p.chance(x, 0, z, 1001, 4)) p.set(x, fr.base, z, Blocks.LILY_PAD.defaultBlockState());
            } else if (n < -0.4) {
                int h = 1 + p.roll(x, 0, z, 1002, 3);
                for (int dy = 0; dy < h; dy++) p.set(x, fr.base + dy, z, BuiltBuilder.wall(p, Theme.BARROW, x, fr.base + dy, z, 1));
            }
        });
        fr.set(ef + 8, 0, 8, Blocks.MOSSY_COBBLESTONE);
        fr.set(ef + 8, 1, 8, Blocks.MOSSY_COBBLESTONE_WALL);
        fr.spawn(Mobs.necromancer(), ef + 10, 0, -7, fr.f, null);
        fr.spawn("minecraft:skeleton", ef + 12, 0, 6, fr.f, null);
    }

    // ------------------------------------------------------------------ Saarthal

    private static void saarthal(Frame fr, int ef) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            if (reserved(ef, fw, lt) || Math.hypot(fw - ef - 8, lt) > 16) return;
            p.clear(x, fr.base, z, fr.base + 6);
            if (p.chance(x, 0, z, 1010, 3)) p.set(x, fr.base - 1, z, Blocks.COARSE_DIRT.defaultBlockState());
        });
        // the College's tents and scaffolds
        Towers.tent(fr, ef + 14, -8, Blocks.LIGHT_BLUE_WOOL.defaultBlockState());
        Towers.tent(fr, ef + 14, 8, Blocks.LIGHT_BLUE_WOOL.defaultBlockState());
        for (int dy = 0; dy <= 4; dy++) {
            fr.set(ef + 4, dy, -8, Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0));
            fr.set(ef + 4, dy, 8, Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0));
        }
        // survey stakes with flags
        for (int i = 0; i < 6; i++) {
            int fw = ef + 6 + fr.roll(i, 0, 1011, 12), lt = fr.roll(i, 1, 1011, 25) - 12;
            if (reserved(ef, fw, lt)) continue;
            fr.set(fw, 0, lt, Blocks.SPRUCE_FENCE);
            fr.set(fw, 1, lt, Blocks.WHITE_BANNER.defaultBlockState());
        }
        fr.set(ef + 12, 0, 0 + 9, Blocks.CARTOGRAPHY_TABLE);
        fr.container(ef + 12, 0, -10, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        fr.set(ef + 13, 0, -10, Blocks.BARREL);
        Camps.fire(fr, ef + 10, 0 + 12);
    }

    // ------------------------------------------------------------------ Forelhost

    private static void forelhost(Frame fr, int ef, DungeonPiece c) {
        Painter p = fr.p;
        Frame.Masonry stone = Uniques.nordStone();
        // three terraces stepping down from the doors, each with a battlemented wall
        int[] edges = {ef + 8, ef + 15, ef + 22};
        fr.all((fw, lt, x, z) -> {
            if (reserved(ef, fw, lt) || fw < ef || fw > ef + 22 || Math.abs(lt) > 16) return;
            int tier = fw <= edges[0] ? 0 : fw <= edges[1] ? 1 : 2;
            int floor = -tier * 3;
            p.clear(x, fr.base + floor, z, fr.base + 10);
            for (int dy = floor - 1; dy < 0; dy++) p.air(x, fr.base + dy, z);
            p.set(x, fr.base + floor - 1, z, BuiltBuilder.floor(p, Theme.BARROW, x, fr.base + floor - 1, z));
            p.foundation(x, fr.base + floor - 2, z, Blocks.STONE.defaultBlockState(), 14);
            boolean edge = fw == edges[tier] && tier < 2 || Math.abs(lt) == 16;
            boolean stair = Math.abs(lt) <= 2 && fw == edges[tier] && tier < 2;
            if (edge && !stair) {
                p.set(x, fr.base + floor, z, stone.at(p, x, fr.base + floor, z));
                if (Math.floorMod(fw + lt, 2) == 0) p.set(x, fr.base + floor + 1, z, stone.at(p, x, fr.base + floor + 1, z));
            }
        });
        // stairs between the terraces
        for (int t = 0; t < 2; t++) {
            for (int i = 0; i < 3; i++) for (int lt = -2; lt <= 2; lt++) {
                fr.set(edges[t] + 1 + i, -t * 3 - 1 - i, lt, fr.stair(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH, false));
            }
        }
        // banners of the dragon cult, braziers, and the Word Wall on the upper terrace
        for (int lt : new int[]{-10, 10}) {
            for (int dy = 0; dy <= 4; dy++) fr.set(ef + 2, dy, lt, stone.at(p, fr.x(ef + 2, lt), fr.base + dy, fr.z(ef + 2, lt)));
            fr.set(ef + 3, 3, lt, Blocks.BLACK_BANNER.defaultBlockState());
            fr.set(ef + 6, 0, lt, Blocks.CHISELED_STONE_BRICKS);
            fr.set(ef + 6, 1, lt, Deco.campfire(false, true));
        }
        for (int lt = 11; lt <= 15; lt++) for (int dy = 0; dy <= 4; dy++) {
            if (lt == 13 && dy == 1) continue;
            fr.set(ef + 1, dy, lt, Blocks.STONE_BRICKS);
        }
        Deco.wordWall(p, fr.x(ef + 1, 13), fr.base + 1, fr.z(ef + 1, 13), fr.f, c.param);
        fr.p.spawnDormant(Mobs.draugr(), fr.x(ef + 12, -6), fr.base - 3, fr.z(ef + 12, -6), 0f);
        fr.p.spawnDormant(Mobs.draugr(), fr.x(ef + 12, 6), fr.base - 3, fr.z(ef + 12, 6), 0f);
    }

    // ------------------------------------------------------------------ Kolskeggr

    private static void kolskeggr(Frame fr, int ef) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            if (reserved(ef, fw, lt) || Math.hypot(fw - ef - 8, lt) > 17) return;
            p.clear(x, fr.base, z, fr.base + 8);
            p.set(x, fr.base - 1, z, p.chance(x, 0, z, 1020, 3) ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
        });
        // the ore heap and the sluice
        for (int i = 0; i < 7; i++) {
            int fw = ef + 12 + fr.roll(i, 0, 1021, 4), lt = 8 + fr.roll(i, 1, 1021, 4);
            fr.set(fw, 0, lt, i % 3 == 0 ? Blocks.RAW_GOLD_BLOCK : Blocks.GRAVEL);
        }
        // a timber headframe
        for (int[] leg : new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) for (int dy = 0; dy <= 5; dy++) fr.set(ef + 14 + leg[0], dy, -9 + leg[1], Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        fr.fill(ef + 13, 6, -10, ef + 15, 6, -8, Blocks.SPRUCE_PLANKS);
        fr.set(ef + 14, 5, -9, Blocks.CHAIN);
        // the Forsworn have taken it
        Camps.fire(fr, ef + 16, 0);
        Towers.tent(fr, ef + 20, -4, Blocks.BROWN_WOOL.defaultBlockState());
        fr.container(ef + 19, 0, 6, Deco.chest(fr.f), Deco.LOOT_MINE);
        fr.spawn(Mobs.forsworn(), ef + 15, 0, 3, fr.f, null);
        fr.spawn(Mobs.forsworn(), ef + 18, 0, -7, fr.f, null);
    }

    // ------------------------------------------------------------------ Ysgramor's Tomb

    private static void ysgramor(Frame fr, int ef) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            if (reserved(ef, fw, lt) || fw < ef || fw > ef + 18 || Math.abs(lt) > 14) return;
            p.clear(x, fr.base, z, fr.base + 20);
            p.set(x, fr.base - 1, z, BuiltBuilder.floor(p, Theme.BARROW, x, fr.base - 1, z));
            p.foundation(x, fr.base - 2, z, Blocks.STONE.defaultBlockState(), 12);
        });
        // the Harbinger of the Five Hundred, axe in hand, on a plinth beside the door
        int sf = ef + 4, sl = -10;
        fr.fill(sf - 2, 0, sl - 2, sf + 2, 1, sl + 2, Blocks.STONE_BRICKS);
        BlockState body = Blocks.POLISHED_ANDESITE.defaultBlockState();
        fr.fill(sf, 2, sl - 1, sf, 6, sl + 1, body);
        fr.fill(sf, 7, sl - 2, sf, 9, sl + 2, body);
        fr.fill(sf, 10, sl, sf, 11, sl, body);
        fr.set(sf, 12, sl - 1, Blocks.BONE_BLOCK);
        fr.set(sf, 12, sl + 1, Blocks.BONE_BLOCK);
        for (int dy = 4; dy <= 12; dy++) fr.set(sf + 1, dy, sl + 3, Deco.pillar(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y));
        fr.fill(sf + 1, 12, sl + 3, sf + 2, 13, sl + 3, Blocks.IRON_BLOCK);
        for (int lt : new int[]{-4, 4}) {
            fr.set(ef + 8, 0, lt, Blocks.CHISELED_STONE_BRICKS);
            fr.set(ef + 8, 1, lt, Deco.campfire(false, true));
        }
    }

    // ------------------------------------------------------------------ the last chambers

    /** Extras in a named barrow's boss room. */
    public static void bossRoom(Painter p, Room room) {
        Direction entered = room.entered();
        Direction f = entered != null ? entered.getOpposite() : Direction.NORTH;
        Direction back = f.getOpposite();
        switch (room.piece.special) {
            case BLEAK_FALLS -> {
                // the Dragonstone: a map of the dragon burials, carved and copied onto a page
                int x = room.localX(f, 0, -3), z = room.localZ(f, 0, -3);
                p.set(x, room.floor, z, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, back).setValue(LecternBlock.HAS_BOOK, true));
                if (p.blockEntity(x, room.floor, z) instanceof LecternBlockEntity lectern) lectern.setBook(dragonstone());
            }
            case SAARTHAL -> {
                // the Eye of Magnus: a slowly burning orb of light filling the hall
                int cx = room.cx, cy = room.floor + Math.max(4, room.height / 2), cz = room.cz;
                for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > 2.4) continue;
                    p.set(cx + dx, cy + dy, cz + dz, d < 1.2 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
                }
                for (int y = room.floor; y < cy - 2; y++) p.set(cx, y, cz, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
            }
            case USTENGRAV -> {
                // the Horn's pedestal: empty, a note where the horn should be
                int x = room.localX(f, 0, 0), z = room.localZ(f, 0, 0);
                p.set(x, room.floor, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                p.set(x, room.floor + 1, z, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, back).setValue(LecternBlock.HAS_BOOK, true));
                if (p.blockEntity(x, room.floor + 1, z) instanceof LecternBlockEntity lectern) {
                    lectern.setBook(book("A Note", List.of(Component.translatable("book.skycraft.ustengrav_note.text"))));
                }
            }
            case FORELHOST -> p.spawn(Mobs.deathlord(), room.localX(f, 1, 0), room.floor, room.localZ(f, 1, 0), Painter.yaw(back), "Rahgot");
            case LABYRINTHIAN -> p.spawn(Mobs.deathlord(), room.localX(f, 1, 0), room.floor, room.localZ(f, 1, 0), Painter.yaw(back), "Morokei");
            case YSGRAMOR -> {
                int x = room.localX(f, 2, 0), z = room.localZ(f, 2, 0);
                p.set(x, room.floor, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                p.set(x, room.floor + 1, z, Blocks.CHISELED_DEEPSLATE.defaultBlockState());
            }
            default -> {
            }
        }
    }

    /**
     * A written book (lectern or chest). Pages are components, so translation keys and place names resolve on the
     * reader's client; the title is plain text.
     */
    static ItemStack book(String title, List<Component> pages) {
        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString("title", title);
        tag.putString("author", "?");
        ListTag list = new ListTag();
        for (Component page : pages) list.add(StringTag.valueOf(Component.Serializer.toJson(page)));
        tag.put("pages", list);
        tag.putBoolean("resolved", true);
        return stack;
    }

    /** The Dragonstone's map, as a rubbing: every dragon burial mound. */
    static ItemStack dragonstone() {
        List<Component> pages = new ArrayList<>();
        net.minecraft.network.chat.MutableComponent page = Component.translatable("book.skycraft.dragonstone.intro").append("\n\n");
        int lines = 5;
        for (Place mound : Places.all()) {
            if (mound.type() != PlaceType.DRAGON_MOUND) continue;
            if (lines >= 12) {
                pages.add(page);
                page = Component.empty();
                lines = 0;
            }
            page.append("- ").append(mound.name()).append("\n");
            lines++;
        }
        pages.add(page);
        return book("Dragonstone", pages);
    }
}
