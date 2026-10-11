package com.skycraft.explore.build;

import com.skycraft.crafting.CraftingBlocks;
import com.skycraft.crafting.SkyOre;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.explore.ExploreLoot;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Sites of the land itself: overlooks, exposed veins, herb gardens, rare reagents, hot springs, Hist saplings, moon
 * sugar fields, giant beehives, crystal outcrops, Dwemer vents and broken mage towers.
 */
public final class Nature {
    private Nature() {}

    // ------------------------------------------------------------------ overlook

    public static boolean overlook(SiteBuilder b) {
        if (b.waterAt(0, 0)) return false;
        b.settle(2);
        b.level(3, null, Blocks.STONE.defaultBlockState(), 6);
        // a flagstone landing with a low wall on the view side (local +z), a bench, a beacon brazier
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (x * x + z * z > 6) continue;
                b.pillarDown(x, 0, z, Blocks.STONE.defaultBlockState());
                b.set(x, -1, z, (b.r.nextInt(3) == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState());
            }
        }
        for (int x = -2; x <= 2; x++) b.set(x, 0, 2, b.pal.wall().defaultBlockState());
        b.set(-1, 0, 1, b.stair(b.pal.stairs(), Direction.NORTH));
        b.set(0, 0, 1, b.stair(b.pal.stairs(), Direction.NORTH));
        b.set(2, 0, -2, b.pal.brick().defaultBlockState());
        b.campfire(2, 1, -2, true, false);
        b.set(-2, 0, -1, b.pal.fence().defaultBlockState());
        b.set(-2, 1, -1, b.pal.fence().defaultBlockState());
        b.set(-2, 2, -1, b.pal.fence().defaultBlockState());
        b.banner(-2, 3, -1, b.pal.banner());
        b.chest(1, 0, -2, Direction.SOUTH, ExploreLoot.table("overlook"));
        b.lying(1, 0, 1, new ItemStack(Items.SPYGLASS));
        return true;
    }

    // ------------------------------------------------------------------ mineral vein

    static SkyOre[] oresFor(String province) {
        return switch (province) {
            case "skyrim", "atmora" -> new SkyOre[]{SkyOre.CORUNDUM, SkyOre.ORICHALCUM, SkyOre.SILVER, SkyOre.MOONSTONE, SkyOre.QUICKSILVER, SkyOre.EBONY};
            case "morrowind" -> new SkyOre[]{SkyOre.EBONY, SkyOre.MALACHITE, SkyOre.CORUNDUM, SkyOre.ORICHALCUM};
            case "summerset", "valenwood" -> new SkyOre[]{SkyOre.MOONSTONE, SkyOre.MALACHITE, SkyOre.QUICKSILVER, SkyOre.SILVER};
            case "hammerfell", "yokuda", "elsweyr" -> new SkyOre[]{SkyOre.CORUNDUM, SkyOre.SILVER, SkyOre.MOONSTONE, SkyOre.ORICHALCUM};
            case "high_rock" -> new SkyOre[]{SkyOre.ORICHALCUM, SkyOre.SILVER, SkyOre.CORUNDUM, SkyOre.QUICKSILVER};
            default -> SkyOre.values();
        };
    }

    public static boolean mineralVein(SiteBuilder b) {
        if (b.waterAt(0, 0)) return false;
        b.y = b.ground(0, 0) + 1;
        b.clear(4, 6);
        SkyOre[] ores = oresFor(b.site.province());
        SkyOre main = ores[b.r.nextInt(Math.min(3, ores.length))];
        boolean iron = b.r.nextInt(3) == 0;
        BlockState rock = (b.site.province().equals("morrowind") ? Blocks.BASALT : b.r.nextBoolean() ? Blocks.ANDESITE : Blocks.STONE).defaultBlockState();
        // a craggy outcrop with the ore showing on its faces
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                int d2 = x * x + z * z;
                if (d2 > 10) continue;
                int h = d2 <= 2 ? 4 + b.r.nextInt(2) : d2 <= 5 ? 2 + b.r.nextInt(2) : 1;
                int base = b.ground(x, z) + 1 - b.y;
                for (int k = -1; k < h; k++) {
                    boolean face = k == h - 1 || d2 >= 4;
                    BlockState s = face && b.r.nextInt(3) == 0
                            ? (iron && b.r.nextBoolean() ? Blocks.IRON_ORE.defaultBlockState() : CraftingBlocks.ORES.get(main).get().defaultBlockState())
                            : rock;
                    b.set(x, base + k, z, s);
                }
            }
        }
        // the prospector's leavings
        b.barrel(3, b.ground(3, 2) + 1 - b.y, 2, ExploreLoot.table("miner"));
        b.lying(-3, b.ground(-3, 2) + 1 - b.y, 2, new ItemStack(Items.IRON_PICKAXE));
        b.set(2, b.ground(2, 3) + 1 - b.y, 3, Blocks.LANTERN);
        return true;
    }

    // ------------------------------------------------------------------ herb garden

    static BlockState[] herbsFor(String province) {
        return switch (province) {
            case "skyrim", "atmora" -> new BlockState[]{ArcaneRegistry.NIGHTSHADE.get().defaultBlockState(), ArcaneRegistry.DEATHBELL.get().defaultBlockState(),
                    Blocks.CORNFLOWER.defaultBlockState(), Blocks.LILY_OF_THE_VALLEY.defaultBlockState(), ArcaneRegistry.FROST_MIRRIAM.get().defaultBlockState()};
            case "morrowind" -> new BlockState[]{ArcaneRegistry.DEATHBELL.get().defaultBlockState(), Blocks.FIRE_CORAL_FAN.defaultBlockState(),
                    Blocks.RED_TULIP.defaultBlockState(), Blocks.CRIMSON_FUNGUS.defaultBlockState(), Blocks.ALLIUM.defaultBlockState()};
            case "black_marsh" -> new BlockState[]{ArcaneRegistry.DEATHBELL.get().defaultBlockState(), Blocks.BLUE_ORCHID.defaultBlockState(),
                    Blocks.BROWN_MUSHROOM.defaultBlockState(), Blocks.FERN.defaultBlockState()};
            case "elsweyr", "hammerfell", "yokuda" -> new BlockState[]{Blocks.ORANGE_TULIP.defaultBlockState(), Blocks.DANDELION.defaultBlockState(),
                    Blocks.ALLIUM.defaultBlockState(), ArcaneRegistry.NIGHTSHADE.get().defaultBlockState()};
            default -> new BlockState[]{ArcaneRegistry.NIGHTSHADE.get().defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(),
                    Blocks.POPPY.defaultBlockState(), Blocks.AZURE_BLUET.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(),
                    ArcaneRegistry.DEATHBELL.get().defaultBlockState()};
        };
    }

    public static boolean herbGarden(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 7) return false;
        b.level(5, null, Blocks.DIRT.defaultBlockState(), 4);
        BlockState[] herbs = herbsFor(b.site.province());
        // a fenced bed of herbs, half of it gone wild
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                if (edge) {
                    if (!(x == 0 && z == 3) && b.r.nextInt(5) > 0) b.set(x, 0, z, b.pal.fence().defaultBlockState());
                    continue;
                }
                b.set(x, -1, z, (z % 2 == 0 ? Blocks.ROOTED_DIRT : Blocks.GRASS_BLOCK).defaultBlockState());
                if (z % 2 != 0 && b.r.nextInt(4) > 0) b.set(x, 0, z, herbs[b.r.nextInt(herbs.length)]);
            }
        }
        // the herbalist's table and satchel, a scarecrow
        b.set(4, 0, -2, Blocks.BARREL);
        b.set(4, 1, -2, Blocks.BREWING_STAND);
        b.chest(4, 0, 0, Direction.WEST, ExploreLoot.table("herbalist"));
        b.set(-4, 0, 0, b.pal.fence());
        b.set(-4, 1, 0, Blocks.HAY_BLOCK);
        b.set(-4, 2, 0, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        return true;
    }

    // ------------------------------------------------------------------ rare reagent

    public static boolean rareReagent(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        String p = b.site.province();
        if (p.equals("skyrim") || p.equals("atmora") || p.equals("akavir")) return giantsGrave(b);
        if (p.equals("morrowind") || p.equals("hammerfell") || p.equals("yokuda")) return fireVent(b);
        return glowPool(b);
    }

    /** The bones of a giant, with the toe-bones still worth the taking. */
    private static boolean giantsGrave(SiteBuilder b) {
        b.clear(6, 7);
        BlockState bone = Blocks.BONE_BLOCK.defaultBlockState();
        // a rib cage along local x: arches of bone
        for (int x = -3; x <= 3; x += 2) {
            for (int z : new int[]{-2, 2}) {
                b.pillarDown(x, 0, z, bone);
                b.set(x, 0, z, bone);
                b.set(x, 1, z, bone);
                b.set(x, 2, z - Integer.signum(z), bone);
            }
            b.set(x, 3, 0, Math.abs(x) < 3 ? bone : Blocks.AIR.defaultBlockState());
        }
        for (int x = -4; x <= 4; x++) b.set(x, 0, 0, bone.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        // the club and the skull
        for (int z = 3; z <= 6; z++) b.set(-4, 0, z, Blocks.STRIPPED_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        b.set(5, 0, 0, Blocks.BONE_BLOCK);
        b.set(5, 1, 0, Blocks.BONE_BLOCK);
        b.set(6, 0, 0, Blocks.BONE_BLOCK);
        b.chest(2, 0, -4, Direction.SOUTH, ExploreLoot.table("reagent_giant"));
        return true;
    }

    /** A smouldering vent crusted with fire salts. */
    private static boolean fireVent(SiteBuilder b) {
        b.clear(4, 5);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                int d2 = x * x + z * z;
                if (d2 > 5) continue;
                b.set(x, -1, z, d2 <= 1 ? Blocks.MAGMA_BLOCK.defaultBlockState()
                        : (b.r.nextBoolean() ? Blocks.BLACKSTONE : Blocks.BASALT).defaultBlockState());
                if (d2 >= 4 && b.r.nextBoolean()) b.set(x, 0, z, Blocks.BLACKSTONE_SLAB.defaultBlockState());
            }
        }
        b.campfire(0, 0, 0, true, true);
        b.set(0, -1, 0, Blocks.HAY_BLOCK);
        b.set(0, -2, 0, Blocks.MAGMA_BLOCK);
        b.chest(3, 0, 0, Direction.WEST, ExploreLoot.table("reagent_fire"));
        b.scatter(4, 4, Blocks.DEAD_BUSH.defaultBlockState(), true);
        return true;
    }

    /** A still pool ringed with glowing mushrooms, nirnroot humming at its edge. */
    private static boolean glowPool(SiteBuilder b) {
        b.level(4, null, Blocks.DIRT.defaultBlockState(), 4);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            b.set(x, -1, z, Blocks.WATER.defaultBlockState());
            b.set(x, -2, z, Blocks.CLAY.defaultBlockState());
        }
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int dx = (int) Math.round(Math.cos(a) * 2.6), dz = (int) Math.round(Math.sin(a) * 2.6);
            b.set(dx, -1, dz, Blocks.MOSS_BLOCK.defaultBlockState());
            b.set(dx, 0, dz, i % 2 == 0 ? ArcaneRegistry.NIRNROOT.get().defaultBlockState() : Blocks.GLOW_LICHEN.defaultBlockState()
                    .setValue(BlockStateProperties.DOWN, true));
        }
        b.set(3, -1, 0, Blocks.MOSS_BLOCK);
        b.set(3, 0, 0, BiomeBlocks.GLOWING_MUSHROOM_CAP.get().defaultBlockState());
        b.set(-3, -1, 1, Blocks.MOSS_BLOCK);
        b.set(-3, 0, 1, Blocks.SHROOMLIGHT);
        b.chest(0, 0, -3, Direction.SOUTH, ExploreLoot.table("reagent"));
        return true;
    }

    // ------------------------------------------------------------------ hot spring

    public static boolean hotSpring(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 8) return false;
        b.level(5, null, Blocks.STONE.defaultBlockState(), 4);
        // a basin two deep, warmed from below (bubbling), ringed with stone
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                int d2 = x * x + z * z;
                if (d2 > 11) continue;
                if (d2 <= 6) {
                    b.set(x, -3, z, d2 == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.CALCITE.defaultBlockState());
                    b.set(x, -2, z, d2 == 0 ? Blocks.WATER.defaultBlockState() : Blocks.WATER.defaultBlockState());
                    b.set(x, -1, z, Blocks.WATER.defaultBlockState());
                    b.set(x, 0, z, Blocks.AIR.defaultBlockState());
                } else {
                    b.set(x, -1, z, (b.r.nextBoolean() ? Blocks.TUFF : Blocks.CALCITE).defaultBlockState());
                    b.set(x, -2, z, Blocks.STONE.defaultBlockState());
                    if (b.r.nextInt(4) == 0) b.set(x, 0, z, Blocks.COBBLESTONE_SLAB.defaultBlockState());
                }
            }
        }
        // steam vents at the rim (smoke you can follow)
        b.set(4, -1, 0, Blocks.HAY_BLOCK);
        b.set(4, 0, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.SIGNAL_FIRE, true));
        b.set(4, -2, 0, Blocks.STONE);
        // a bather's things left on the rocks
        b.chest(-4, 0, 1, Direction.EAST, ExploreLoot.table("bather"));
        b.lying(-3, 0, -3, new ItemStack(Items.LEATHER_BOOTS));
        return true;
    }

    // ------------------------------------------------------------------ Hist sapling

    public static boolean histSapling(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 9) return false;
        b.level(6, null, Blocks.MUD.defaultBlockState(), 12);
        BlockState log = BiomeBlocks.HIST_LOG.get().defaultBlockState();
        BlockState leaves = BiomeBlocks.HIST_LEAVES.get().defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
        // a ring of still water around the roots
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                int d2 = x * x + z * z;
                if (d2 >= 6 && d2 <= 14) {
                    b.set(x, -1, z, Blocks.WATER.defaultBlockState());
                    b.set(x, -2, z, Blocks.MUD.defaultBlockState());
                    if (b.r.nextInt(6) == 0) b.set(x, 0, z, Blocks.LILY_PAD.defaultBlockState());
                }
            }
        }
        int h = 6 + b.r.nextInt(2);
        for (int k = 0; k < h; k++) b.set(0, k, 0, log);
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            b.set(d.getStepX(), 0, d.getStepZ(), log);
            b.set(d.getStepX(), 3, d.getStepZ(), BiomeBlocks.HIST_SAP_BLOCK.get().defaultBlockState());
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int k = h - 2; k <= h + 1; k++) {
                    int rr = k == h + 1 ? 2 : 3;
                    if (x * x + z * z > rr * rr) continue;
                    if (x == 0 && z == 0 && k < h) continue;
                    b.set(x, k, z, leaves);
                }
            }
        }
        // offerings at the roots: carved stones, a basket
        b.set(2, 0, 0, Blocks.MUD_BRICK_WALL);
        b.set(2, 1, 0, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
        b.chest(0, 0, 2, Direction.SOUTH, ExploreLoot.table("hist"));
        return true;
    }

    // ------------------------------------------------------------------ moon sugar field

    public static boolean moonSugarField(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 6) return false;
        b.level(7, null, Blocks.COARSE_DIRT.defaultBlockState(), 4);
        // rows of cane between irrigation ditches
        for (int x = -5; x <= 3; x++) {
            for (int z = -5; z <= 5; z++) {
                if (x % 3 == 0) {
                    b.set(x, -1, z, Blocks.WATER.defaultBlockState());
                    b.set(x, -2, z, Blocks.COARSE_DIRT.defaultBlockState());
                } else {
                    b.set(x, -1, z, Blocks.SAND.defaultBlockState());
                    if (b.r.nextInt(5) > 0) {
                        int tall = 1 + b.r.nextInt(3);
                        for (int k = 0; k < tall; k++) b.set(x, k, z, Blocks.SUGAR_CANE.defaultBlockState().setValue(SugarCaneBlock.AGE, 0));
                    }
                }
            }
        }
        // the Khajiit cart at the edge, with the harvest
        for (int z = -1; z <= 1; z++) {
            b.set(5, 0, z, b.pal.planks());
            b.set(6, 0, z, b.pal.planks());
        }
        b.set(4, 0, -1, Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        b.set(4, 0, 1, Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
        b.barrel(5, 1, 0, ExploreLoot.table("moon_sugar"));
        b.set(6, 1, -1, Blocks.HAY_BLOCK);
        b.set(6, 1, 1, b.pal.fence());
        b.set(6, 2, 1, b.pal.fence());
        b.banner(6, 3, 1, DyeColor.PURPLE);
        return true;
    }

    // ------------------------------------------------------------------ giant beehive

    public static boolean giantBeehive(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 10) return false;
        b.level(5, null, Blocks.PODZOL.defaultBlockState(), 13);
        BlockState log = b.pal.log().defaultBlockState();
        // a thick old trunk with a crown
        for (int k = 0; k < 10; k++) for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) b.set(x, k, z, log);
        BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
        for (int x = -3; x <= 4; x++) for (int z = -3; z <= 4; z++) for (int k = 9; k <= 11; k++) {
            double dx = x - 0.5, dz = z - 0.5;
            if (dx * dx + dz * dz > (k == 11 ? 6 : 13)) continue;
            if (b.free(x, k, z)) b.set(x, k, z, leaves);
        }
        // the hive: a hanging mass of comb on the branch, dripping honey
        for (int x = 2; x <= 3; x++) b.set(x, 7, 0, log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        for (int k = 4; k <= 6; k++) {
            for (int x = 2; x <= 4; x++) {
                for (int z = -1; z <= 1; z++) {
                    if ((k == 4 || k == 6) && (x != 3 || z != 0) && (Math.abs(z) + Math.abs(x - 3)) > 1) continue;
                    b.set(x, k, z, (k == 5 && x == 3 && z == 1 ? Blocks.BEE_NEST : Blocks.HONEYCOMB_BLOCK).defaultBlockState());
                }
            }
        }
        b.set(3, 3, 0, Blocks.HONEY_BLOCK);
        b.set(3, 0, 0, Blocks.HONEY_BLOCK);
        // the gatherers' baskets
        b.barrel(-2, 0, 2, ExploreLoot.table("beehive"));
        b.set(-2, 0, 1, Blocks.BARREL);
        for (int i = 0; i < 3; i++) b.spawn(EntityType.BEE, 3, 2);
        return true;
    }

    // ------------------------------------------------------------------ crystal outcrop

    public static boolean crystalOutcrop(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 10) return false;
        b.clear(6, 8);
        BlockState crystal = BiomeBlocks.CRYSTAL_BLOCK.get().defaultBlockState();
        // a cluster of leaning spires from a calcite base, glowing at night
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            if (x * x + z * z > 10) continue;
            b.pillarDown(x, 0, z, Blocks.CALCITE.defaultBlockState());
            b.set(x, -1, z, (b.r.nextBoolean() ? Blocks.CALCITE : Blocks.AMETHYST_BLOCK).defaultBlockState());
        }
        int[][] spires = {{0, 0, 7}, {2, 1, 4}, {-2, 1, 5}, {1, -2, 3}, {-1, -2, 4}};
        for (int[] s : spires) {
            for (int k = 0; k < s[2]; k++) b.set(s[0] + (k > 3 && s[0] != 0 ? Integer.signum(s[0]) : 0), k, s[1], crystal);
            b.set(s[0] + (s[2] > 4 && s[0] != 0 ? Integer.signum(s[0]) : 0), s[2], s[1],
                    BiomeBlocks.CRYSTAL_CLUSTER.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        }
        b.scatter(4, 6, Blocks.MEDIUM_AMETHYST_BUD.defaultBlockState(), true);
        // a gem-cutter's cache tucked between the spires
        b.chest(-3, 0, 0, Direction.EAST, ExploreLoot.table("crystal"));
        return true;
    }

    // ------------------------------------------------------------------ Dwemer vent

    public static boolean dwemerVent(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 10) return false;
        b.level(4, null, Blocks.STONE.defaultBlockState(), 6);
        BlockState metal = Blocks.WAXED_CUT_COPPER.defaultBlockState();
        // a grated shaft head breathing steam
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            b.set(x, -1, z, x == 0 && z == 0 ? Blocks.HAY_BLOCK.defaultBlockState() : metal);
        }
        b.set(0, 0, 0, Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.SIGNAL_FIRE, true));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) b.set(x, 0, z, Blocks.WAXED_CUT_COPPER_SLAB);
        }
        // pipes running into the ground, a sealed hatch
        BlockState pipeX = Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, Direction.EAST);
        for (int x = 2; x <= 4; x++) b.set(x, 1, 0, pipeX);
        b.set(5, 1, 0, Blocks.WAXED_COPPER_BLOCK);
        b.set(5, 0, 0, Blocks.WAXED_COPPER_BLOCK);
        BlockState pipeUp = Blocks.LIGHTNING_ROD.defaultBlockState().setValue(LightningRodBlock.FACING, Direction.UP);
        for (int k = 0; k <= 3; k++) b.set(-3, k, -2, k == 3 ? Blocks.WAXED_EXPOSED_COPPER.defaultBlockState() : pipeUp);
        b.set(-3, 4, -2, Blocks.LANTERN);
        b.set(2, 0, -3, Blocks.IRON_TRAPDOOR);
        b.set(2, -1, -3, Blocks.CHAIN.defaultBlockState());
        b.chest(-3, 0, 2, Direction.EAST, ExploreLoot.table("dwemer"));
        if (b.r.nextInt(4) == 0) Shrines.spawnById(b, "skycraft:dwarven_spider", 3, 3, true);
        return true;
    }

    // ------------------------------------------------------------------ mage tower ruin

    public static boolean mageRuin(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 9) return false;
        b.level(6, null, Blocks.STONE.defaultBlockState(), 10);
        // a round tower, broken to uneven heights
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > 4.5) continue;
                b.pillarDown(x, 0, z, b.pal.stone().defaultBlockState());
                if (d >= 3.5) {
                    double ang = Math.atan2(z, x);
                    int h = (int) (2 + 5 * (0.5 + 0.5 * Math.sin(ang * 2 + b.site.seed() % 7)));
                    if (x == 0 && z == 4) h = 0; // the doorway
                    for (int k = 0; k < h; k++) b.set(x, k, z, b.rubble());
                    b.set(x, -1, z, b.pal.brick().defaultBlockState());
                } else {
                    b.set(x, -1, z, (b.r.nextInt(4) == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.POLISHED_ANDESITE).defaultBlockState());
                }
            }
        }
        b.scatter(3, 5, b.pal.brickSlab().defaultBlockState(), false);
        // what the mage left: a lectern, shelves, a summoning circle gone cold
        b.set(0, 0, -2, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
        b.set(-2, 0, -2, Blocks.BOOKSHELF);
        b.set(-2, 1, -2, Blocks.BOOKSHELF);
        b.set(2, 0, -2, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        b.set(0, -1, 1, Blocks.CRYING_OBSIDIAN);
        for (int[] c : new int[][]{{-1, 1}, {1, 1}, {0, 0}, {0, 2}}) b.set(c[0], 0, c[1], Blocks.PURPLE_CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, b.r.nextBoolean()));
        b.set(0, 0, 1, Blocks.SOUL_LANTERN);
        b.chest(2, 0, 1, Direction.WEST, ExploreLoot.table("mage"));
        b.banner(-3, 0, 2, DyeColor.PURPLE);
        return true;
    }
}
