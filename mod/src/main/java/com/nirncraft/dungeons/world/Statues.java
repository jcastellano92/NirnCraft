package com.nirncraft.dungeons.world;

import com.nirncraft.dungeons.world.SiteParams.Prince;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The shrines of the Daedric Princes: a statue of the Prince over a plaza of their stone, an altar for offerings,
 * braziers and (sometimes) their worshippers. Each Prince has a figure of their own (Azura's raised moon and star,
 * Dagon's four arms, Mephala's spider limbs, Hircine's antlers, Sheogorath's split motley, Peryite's dragon form,
 * Hermaeus Mora's tentacled mass...). Azura's and Meridia's great statues in Skyrim are the same figures, giant.
 */
public final class Statues {
    private Statues() {}

    /** How a Prince is carved: materials, pose and what the figure holds. */
    private record Look(Block body, Block robe, Block accent, Block floor, Block trim, BlockState light,
                        int arms, boolean raised, String head, String held) {
    }

    private static Look look(Prince prince) {
        return switch (prince) {
            case AZURA -> new Look(Blocks.SMOOTH_QUARTZ, Blocks.CALCITE, Blocks.GOLD_BLOCK, Blocks.CALCITE, Blocks.QUARTZ_BRICKS,
                    Blocks.SEA_LANTERN.defaultBlockState(), 2, true, "hair", "moon_star");
            case BOETHIAH -> new Look(Blocks.POLISHED_BLACKSTONE, Blocks.RED_NETHER_BRICKS, Blocks.GOLD_BLOCK, Blocks.BLACKSTONE,
                    Blocks.POLISHED_BLACKSTONE_BRICKS, Deco.campfire(false, true), 2, false, "horns", "sword");
            case CLAVICUS_VILE -> new Look(Blocks.POLISHED_ANDESITE, com.nirncraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA, Blocks.GOLD_BLOCK, Blocks.STONE_BRICKS,
                    Blocks.CHISELED_STONE_BRICKS, Deco.lantern(false, false), 2, false, "horns", "staff");
            case HERMAEUS_MORA -> new Look(Blocks.DARK_PRISMARINE, Blocks.PRISMARINE, Blocks.SEA_LANTERN, Blocks.DARK_PRISMARINE,
                    Blocks.PRISMARINE_BRICKS, Blocks.SEA_LANTERN.defaultBlockState(), 0, false, "eyes", "none");
            case HIRCINE -> new Look(Blocks.STRIPPED_SPRUCE_WOOD, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL, Blocks.BONE_BLOCK, Blocks.PODZOL,
                    Blocks.MOSSY_COBBLESTONE, Deco.campfire(false, true), 2, false, "antlers", "spear");
            case MALACATH -> new Look(Blocks.COBBLED_DEEPSLATE, Blocks.BLACKSTONE, Blocks.BONE_BLOCK, Blocks.COARSE_DIRT,
                    Blocks.COBBLED_DEEPSLATE, Deco.campfire(false, true), 2, false, "horns", "club");
            case MEHRUNES_DAGON -> new Look(Blocks.RED_NETHER_BRICKS, Blocks.NETHER_BRICKS, Blocks.GILDED_BLACKSTONE, Blocks.BLACKSTONE,
                    Blocks.RED_NETHER_BRICKS, Blocks.MAGMA_BLOCK.defaultBlockState(), 4, true, "horns", "razor");
            case MEPHALA -> new Look(com.nirncraft.worldgen.VanillaBlocks.BLACK_CONCRETE, Blocks.BLACKSTONE, Blocks.CRYING_OBSIDIAN, Blocks.POLISHED_BLACKSTONE,
                    Blocks.POLISHED_BLACKSTONE_BRICKS, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.BLACK_CANDLE, 4, true), 6, false, "hood", "none");
            case MERIDIA -> new Look(Blocks.GOLD_BLOCK, com.nirncraft.worldgen.VanillaBlocks.WHITE_CONCRETE, Blocks.GLOWSTONE, Blocks.SMOOTH_QUARTZ,
                    Blocks.CHISELED_QUARTZ_BLOCK, Blocks.GLOWSTONE.defaultBlockState(), 2, true, "crown", "sword");
            case MOLAG_BAL -> new Look(Blocks.POLISHED_DEEPSLATE, Blocks.BLACKSTONE, com.nirncraft.worldgen.VanillaBlocks.CHAIN, Blocks.POLISHED_BLACKSTONE_BRICKS,
                    Blocks.CHISELED_POLISHED_BLACKSTONE, Deco.campfire(true, true), 2, false, "horns", "mace");
            case NAMIRA -> new Look(Blocks.TUFF, Blocks.MOSSY_COBBLESTONE, Blocks.BONE_BLOCK, Blocks.MUD,
                    Blocks.MOSSY_COBBLESTONE, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.BROWN_CANDLE, 3, true), 2, false, "hood", "none");
            case NOCTURNAL -> new Look(Blocks.OBSIDIAN, com.nirncraft.worldgen.VanillaBlocks.BLACK_WOOL, Blocks.AMETHYST_BLOCK, Blocks.DEEPSLATE_TILES,
                    Blocks.POLISHED_DEEPSLATE, Deco.lantern(true, false), 2, false, "hood", "raven");
            case PERYITE -> new Look(com.nirncraft.worldgen.VanillaBlocks.OXIDIZED_COPPER, com.nirncraft.worldgen.VanillaBlocks.GREEN_TERRACOTTA, Blocks.SLIME_BLOCK, Blocks.MOSS_BLOCK,
                    Blocks.MOSSY_STONE_BRICKS, Blocks.VERDANT_FROGLIGHT.defaultBlockState(), 0, false, "dragon", "none");
            case SANGUINE -> new Look(com.nirncraft.worldgen.VanillaBlocks.RED_TERRACOTTA, com.nirncraft.worldgen.VanillaBlocks.RED_WOOL, Blocks.GOLD_BLOCK, Blocks.RED_NETHER_BRICKS,
                    Blocks.NETHER_BRICKS, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.RED_CANDLE, 4, true), 2, true, "horns", "goblet");
            case SHEOGORATH -> new Look(Blocks.GOLD_BLOCK, com.nirncraft.worldgen.VanillaBlocks.PURPLE_WOOL, com.nirncraft.worldgen.VanillaBlocks.YELLOW_TERRACOTTA, Blocks.PURPUR_BLOCK,
                    Blocks.PURPUR_PILLAR, Blocks.JACK_O_LANTERN.defaultBlockState(), 2, false, "crown", "staff");
            case VAERMINA -> new Look(Blocks.PURPUR_BLOCK, com.nirncraft.worldgen.VanillaBlocks.PURPLE_TERRACOTTA, Blocks.AMETHYST_BLOCK, Blocks.PURPUR_BLOCK,
                    Blocks.PURPUR_PILLAR, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.PURPLE_CANDLE, 4, true), 2, true, "hood", "skull_staff");
        };
    }

    public static void build(Frame fr, DungeonPiece c) {
        Prince prince = Prince.byIndex(c.param);
        boolean giant = c.special == Special.AZURA || c.special == Special.MERIDIA;
        int s = giant ? 3 : 1;
        Look look = look(prince);
        Painter p = fr.p;
        int r = giant ? 22 : 10;
        // the plaza: a disc of the Prince's stone, a trim ring, steps down at the front
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > r + 0.5) return;
            p.clear(x, fr.base, z, fr.base + (giant ? 50 : 20));
            BlockState ground = d > r - 1 ? look.trim().defaultBlockState() : p.chance(x, 0, z, 950, 5)
                    ? look.trim().defaultBlockState() : look.floor().defaultBlockState();
            p.set(x, fr.base - 1, z, ground);
            p.foundation(x, fr.base - 2, z, Blocks.STONE.defaultBlockState(), giant ? 24 : 12);
        });
        int fc = giant ? -6 : -3;
        // pedestal
        int ped = giant ? 5 : 2;
        int pedH = giant ? 4 : 1;
        fr.fill(fc - ped, 0, -ped, fc + ped, pedH - 1, ped, look.trim());
        if (giant) {
            // stairs up the pedestal's face
            for (int i = 0; i < pedH; i++) for (int lt = -2; lt <= 2; lt++) fr.set(fc + ped + pedH - i, i, lt, fr.stair(Blocks.QUARTZ_STAIRS, Direction.SOUTH, false));
        }
        int base = pedH;
        if ("dragon".equals(look.head())) dragonForm(fr, look, fc, base, s);
        else if ("eyes".equals(look.head())) moraForm(fr, look, fc, base, s);
        else figure(fr, look, prince, fc, base, s);
        // altar and offerings
        int af = giant ? 10 : 3;
        for (int lt = -1; lt <= 1; lt++) fr.set(af, 0, lt, lt == 0 ? look.accent().defaultBlockState() : look.trim().defaultBlockState());
        fr.set(af, 1, -1, Deco.candle(Blocks.CANDLE, 3, true));
        fr.set(af, 1, 1, Deco.candle(Blocks.CANDLE, 2, true));
        fr.container(af - 1, 0, 2, Deco.chest(fr.f), Deco.LOOT_SHRINE);
        // braziers
        int b = giant ? 14 : 7;
        for (int[] br : new int[][]{{b / 2, -b + 2}, {b / 2, b - 2}, {-b + 2, -b / 2 - 1}, {-b + 2, b / 2 + 1}}) {
            fr.set(br[0], 0, br[1], look.trim());
            fr.set(br[0], 1, br[1], look.trim());
            fr.set(br[0], 2, br[1], look.light());
        }
        extras(fr, prince, look, giant);
        if (!giant && Math.floorMod(c.flavor, 3) == 0) {
            fr.spawn(Mobs.orElse("nirncraft:cultist", "minecraft:vindicator"), 5, 0, -2, fr.back(), "Daedra Worshipper");
            fr.spawn(Mobs.orElse("nirncraft:cultist", "minecraft:evoker"), 5, 0, 2, fr.back(), "Cult Priest");
        }
    }

    // ------------------------------------------------------------------ the figure

    /** Box in local frame scaled about the figure's feet: offsets in "statue units" times {@code s}. */
    private static void part(Frame fr, int fc, int base, int s, double f0, double f1, double y0, double y1, double l0, double l1, BlockState st) {
        int fa = fc + (int) Math.floor(f0 * s), fb = fc + (int) Math.ceil(f1 * s) - 1;
        int ya = base + (int) Math.floor(y0 * s), yb = base + (int) Math.ceil(y1 * s) - 1;
        int la = (int) Math.floor(l0 * s), lb = (int) Math.ceil(l1 * s) - 1;
        fr.fill(fa, ya, la, fb, yb, lb, st);
    }

    private static void figure(Frame fr, Look look, Prince prince, int fc, int base, int s) {
        BlockState body = look.body().defaultBlockState();
        BlockState robe = look.robe().defaultBlockState();
        BlockState accent = look.accent().defaultBlockState();
        boolean split = prince == Prince.SHEOGORATH;
        // robe/legs: wide at the hem
        part(fr, fc, base, s, -1, 1, 0, 2, -1.5, 1.5, robe);
        part(fr, fc, base, s, -1, 1, 2, 5, -1, 1, robe);
        // torso and shoulders
        part(fr, fc, base, s, -0.5, 0.5, 5, 8, -1.5, 1.5, body);
        if (split) part(fr, fc, base, s, -0.5, 0.5, 2, 8, 0, 1.5, Blocks.PURPUR_BLOCK.defaultBlockState());
        part(fr, fc, base, s, -0.5, 0.5, 8, 8.6, -0.5, 0.5, body);
        // head
        part(fr, fc, base, s, -0.5, 0.5, 8.6, 10, -0.5, 0.5, body);
        switch (look.head()) {
            case "horns" -> {
                part(fr, fc, base, s, -0.2, 0.2, 9.6, 10.6, -1.2, -0.5, accent);
                part(fr, fc, base, s, -0.2, 0.2, 9.6, 10.6, 0.5, 1.2, accent);
            }
            case "antlers" -> {
                for (int side : new int[]{-1, 1}) {
                    part(fr, fc, base, s, -0.2, 0.2, 9.8, 11.6, side < 0 ? -1.4 : 0.6, side < 0 ? -0.6 : 1.4, Blocks.BONE_BLOCK.defaultBlockState());
                    part(fr, fc, base, s, -0.2, 0.2, 11.2, 11.6, side < 0 ? -2.2 : 1.4, side < 0 ? -1.4 : 2.2, Blocks.BONE_BLOCK.defaultBlockState());
                }
            }
            case "hood" -> part(fr, fc, base, s, -0.8, 0.6, 8.6, 10.4, -0.8, 0.8, robe);
            case "crown" -> part(fr, fc, base, s, -0.5, 0.5, 10, 10.5, -0.6, 0.6, accent);
            case "hair" -> part(fr, fc, base, s, -0.9, -0.4, 7, 10, -0.6, 0.6, robe);
            default -> {
            }
        }
        // arms
        int arms = Math.max(2, look.arms());
        for (int i = 0; i < arms; i++) {
            int side = i % 2 == 0 ? -1 : 1;
            double tier = (i / 2) * 1.2;
            if (look.raised() && i < 2) {
                part(fr, fc, base, s, -0.3, 0.3, 7.5 - tier, 11.5 - tier, side < 0 ? -2.3 : 1.5, side < 0 ? -1.5 : 2.3, body);
            } else if (i >= 2) {
                // extra limbs spread outward (Dagon's arms, Mephala's spider legs)
                part(fr, fc, base, s, -0.3, 0.3, 6.6 - tier, 7.2 - tier, side < 0 ? -3.4 : 1.5, side < 0 ? -1.5 : 3.4, body);
                part(fr, fc, base, s, -0.3, 0.3, 4.6 - tier, 7.2 - tier, side < 0 ? -3.4 : 2.8, side < 0 ? -2.8 : 3.4, body);
            } else {
                part(fr, fc, base, s, -0.3, 0.3, 4.5, 8, side < 0 ? -2.1 : 1.5, side < 0 ? -1.5 : 2.1, body);
            }
        }
        // what the right hand holds (or both raised hands)
        int handY = look.raised() ? 11 : 4;
        switch (look.held()) {
            case "moon_star" -> {
                // the moon (a gold crescent) in the left hand, the star (a glowing point) in the right
                part(fr, fc, base, s, -0.3, 0.3, 11.5, 12.8, -3.0, -2.6, Blocks.GOLD_BLOCK.defaultBlockState());
                part(fr, fc, base, s, -0.3, 0.3, 12.4, 12.8, -2.6, -1.6, Blocks.GOLD_BLOCK.defaultBlockState());
                part(fr, fc, base, s, -0.3, 0.3, 11.6, 12.6, 1.6, 2.6, Blocks.SEA_LANTERN.defaultBlockState());
                part(fr, fc, base, s, -0.1, 0.1, 12.6, 13.4, 1.9, 2.3, Blocks.END_ROD.defaultBlockState());
            }
            case "sword" -> part(fr, fc, base, s, 0.4, 0.8, handY - 1, handY + 4, 1.7, 2.0, Blocks.IRON_BLOCK.defaultBlockState());
            case "mace", "club" -> {
                part(fr, fc, base, s, 0.4, 0.8, handY - 3, handY + 1, 1.7, 2.0, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                part(fr, fc, base, s, 0.2, 1.0, handY - 4, handY - 3, 1.5, 2.3, look.held().equals("mace") ? Blocks.POLISHED_BLACKSTONE.defaultBlockState()
                        : Blocks.COBBLED_DEEPSLATE.defaultBlockState());
            }
            case "razor" -> part(fr, fc, base, s, 0.4, 1.6, handY + 0.5, handY + 1, 2.3, 2.6, Blocks.IRON_BLOCK.defaultBlockState());
            case "staff", "spear", "skull_staff" -> {
                part(fr, fc, base, s, 0.4, 0.7, 0, handY + 3, 1.7, 2.0, Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                BlockState tip = switch (look.held()) {
                    case "spear" -> Blocks.IRON_BARS.defaultBlockState();
                    case "skull_staff" -> Blocks.BONE_BLOCK.defaultBlockState();
                    default -> Blocks.JACK_O_LANTERN.defaultBlockState();
                };
                part(fr, fc, base, s, 0.4, 0.7, handY + 3, handY + 4, 1.7, 2.0, tip);
            }
            case "goblet" -> part(fr, fc, base, s, 0.3, 0.8, 12, 12.6, 1.6, 2.2, Blocks.GOLD_BLOCK.defaultBlockState());
            case "raven" -> {
                part(fr, fc, base, s, -0.2, 0.3, 8.6, 9.4, 1.0, 1.6, com.nirncraft.worldgen.VanillaBlocks.BLACK_WOOL.defaultBlockState());
                part(fr, fc, base, s, 0.3, 0.6, 8.8, 9.2, 1.1, 1.5, Blocks.BLACKSTONE.defaultBlockState());
            }
            default -> {
            }
        }
    }

    /** Peryite as the old carvings show him: a crouching dragon, wings half spread. */
    private static void dragonForm(Frame fr, Look look, int fc, int base, int s) {
        BlockState body = look.body().defaultBlockState();
        BlockState wing = look.robe().defaultBlockState();
        part(fr, fc, base, s, -3, 2, 1, 3, -1, 1, body);         // body
        part(fr, fc, base, s, 2, 4, 2, 4, -0.5, 0.5, body);       // neck
        part(fr, fc, base, s, 4, 5.5, 3, 4.5, -0.6, 0.6, body);   // head
        part(fr, fc, base, s, -6, -3, 1, 2, -0.4, 0.4, body);     // tail
        for (int side : new int[]{-1, 1}) {
            part(fr, fc, base, s, -1, 1, 0, 1, side < 0 ? -1.5 : 1, side < 0 ? -1 : 1.5, body);          // legs
            part(fr, fc, base, s, -2, 1, 3, 3.5, side < 0 ? -5 : 1, side < 0 ? -1 : 5, wing);            // wings
            part(fr, fc, base, s, -1, 0, 3.5, 5, side < 0 ? -4 : 3, side < 0 ? -3 : 4, wing);
        }
        part(fr, fc, base, s, 5.2, 5.6, 3.8, 4.2, -0.4, 0.4, look.light());
    }

    /** Hermaeus Mora: an orb of eyes and coiling tentacles rising from the plaza. */
    private static void moraForm(Frame fr, Look look, int fc, int base, int s) {
        Painter p = fr.p;
        int cy = 5 * s;
        int rad = 3 * s;
        for (int fw = -rad; fw <= rad; fw++) for (int lt = -rad; lt <= rad; lt++) for (int dy = -rad; dy <= rad; dy++) {
            double d = Math.sqrt(fw * fw + lt * lt + dy * dy);
            if (d > rad + 0.3) continue;
            int x = fr.x(fc + fw, lt), z = fr.z(fc + fw, lt), y = fr.base + base + cy + dy;
            boolean eye = d > rad - 1 && p.chance(x, y, z, 960, 7);
            p.set(x, y, z, eye ? Blocks.SEA_LANTERN.defaultBlockState() : look.body().defaultBlockState());
        }
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            for (int k = 0; k < 6 * s; k++) {
                double rr = rad + k * 0.5;
                int fw = fc + (int) Math.round(Math.cos(a + k * 0.15) * rr), lt = (int) Math.round(Math.sin(a + k * 0.15) * rr);
                int dy = cy - k;
                if (dy < 0) break;
                fr.set(fw, base + dy, lt, look.robe());
            }
        }
    }

    // ------------------------------------------------------------------ prince-specific dressing

    private static void extras(Frame fr, Prince prince, Look look, boolean giant) {
        switch (prince) {
            case CLAVICUS_VILE -> {
                // Barbas, the faithful hound, sits at his master's feet
                fr.fill(-1, 0, 3, 1, 1, 4, com.nirncraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA);
                fr.set(1, 2, 4, com.nirncraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA);
                fr.set(2, 2, 4, com.nirncraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA);
                fr.set(-2, 0, 4, com.nirncraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA);
            }
            case MOLAG_BAL -> {
                for (int lt : new int[]{-5, 5}) for (int dy = 0; dy <= 3; dy++) fr.set(-1, dy, lt, dy == 3 ? com.nirncraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
            }
            case MEPHALA, NAMIRA -> {
                for (int i = 0; i < 8; i++) fr.setIfOpen(fr.roll(i, 0, 961, 13) - 6, fr.roll(i, 1, 961, 3), fr.roll(i, 2, 961, 13) - 6, Blocks.COBWEB.defaultBlockState());
                if (prince == Prince.NAMIRA) for (int i = 0; i < 4; i++) fr.set(fr.roll(i, 3, 962, 9) - 4, 0, fr.roll(i, 4, 962, 9) - 4, Deco.skull(Blocks.SKELETON_SKULL, i * 4));
            }
            case SANGUINE -> {
                fr.fill(4, 0, -5, 6, 0, -4, Blocks.SPRUCE_PLANKS);
                fr.set(5, 1, -5, Blocks.BARREL);
                fr.set(5, 1, -4, Blocks.SWEET_BERRY_BUSH);
                for (int fw = -2; fw <= 6; fw++) fr.set(fw, 0, 0, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET);
            }
            case HIRCINE -> {
                for (int i = 0; i < 5; i++) fr.set(fr.roll(i, 5, 963, 13) - 6, 0, fr.roll(i, 6, 963, 13) - 6, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
            }
            case SHEOGORATH -> {
                for (int i = 0; i < 3; i++) fr.set(4 + i, 0, 5, com.nirncraft.worldgen.VanillaBlocks.YELLOW_TERRACOTTA);
            }
            case VAERMINA -> {
                for (int i = 0; i < 10; i++) fr.setIfOpen(fr.roll(i, 7, 964, 13) - 6, 4 + fr.roll(i, 8, 964, 4), fr.roll(i, 9, 964, 13) - 6,
                        com.nirncraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS.defaultBlockState());
            }
            case MERIDIA -> {
                if (giant) for (int dy = 40; dy <= 44; dy++) fr.set(-6, dy, 0, Blocks.GLOWSTONE);
                fr.set(-3, 13, 0, Blocks.END_ROD);
            }
            case MEHRUNES_DAGON -> {
                for (int lt : new int[]{-6, 6}) fr.set(-1, -1, lt, Blocks.LAVA);
            }
            default -> {
            }
        }
    }
}
