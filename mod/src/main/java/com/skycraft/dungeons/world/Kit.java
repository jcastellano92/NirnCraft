package com.skycraft.dungeons.world;

import com.mojang.serialization.Codec;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * A culture's building kit for dungeons: the masonry palette (walls, floors, ceilings, pillars, stairs, lights) and
 * the roster that haunts its ruins. {@link #NONE} keeps each theme's own Skyrim palette. Saved by ordinal in pieces,
 * so new kits are only ever appended.
 *
 * <p>Rosters name entities by id and resolve them at generation time ({@link Mobs#orElse}): the creature workstreams
 * can add real Ayleid liches or Dremora later and the ruins pick them up without changes here.</p>
 */
public enum Kit implements StringRepresentable {
    NONE("none"),
    NORD("nord"),
    IMPERIAL("imperial"),
    AYLEID("ayleid"),
    VELOTHI("velothi"),
    DAEDRIC("daedric"),
    XANMEER("xanmeer"),
    KHAJIIT("khajiit"),
    BOSMER("bosmer"),
    ALTMER("altmer"),
    REDGUARD("redguard"),
    BRETON("breton"),
    ORC("orc"),
    AKAVIRI("akaviri"),
    ATMORAN("atmoran"),
    MAORMER("maormer"),
    SLOAD("sload"),
    FALMER("falmer"),
    ICE("ice"),
    FORSWORN("forsworn"),
    BRETON_KEEP_RUIN("breton_keep_ruin"),
    SCORPION_DEN("scorpion_den");

    public static final Codec<Kit> CODEC = StringRepresentable.fromEnum(Kit::values);

    public final String id;
    private Palette palette;

    Kit(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public static Kit byOrdinal(int ordinal) {
        Kit[] v = values();
        return ordinal >= 0 && ordinal < v.length ? v[ordinal] : NONE;
    }

    @Nullable
    public static Kit forCulture(@Nullable String culture) {
        if (culture == null || culture.isEmpty()) return null;
        return "forsworn".equals(culture) ? FORSWORN : null;
    }

    /** Built lazily: the palette refers to block instances, which must exist first. */
    public Palette palette() {
        Palette p = palette;
        if (p == null) {
            p = Palette.of(this);
            palette = p;
        }
        return p;
    }

    // ------------------------------------------------------------------ rosters

    /** A common inhabitant of this culture's ruins; {@code roll} varies the pick. */
    public Spawn minion(int roll) {
        return switch (this) {
            case AYLEID -> roll % 3 == 0 ? new Spawn("minecraft:stray", "Ayleid Wraith") : new Spawn("minecraft:skeleton", "Skeletal Guardian");
            case VELOTHI -> roll % 2 == 0 ? new Spawn("minecraft:zombie", "Bonewalker") : new Spawn("minecraft:skeleton", "Ancestral Guardian");
            case DAEDRIC -> roll % 3 == 0 ? new Spawn("minecraft:blaze", "Flame Atronach")
                    : new Spawn(Mobs.orElse("skycraft:dremora", "minecraft:vindicator"), "Dremora Churl");
            case XANMEER -> roll % 2 == 0 ? new Spawn("minecraft:drowned", "Mire Lurker") : new Spawn(Mobs.spider(), null);
            case KHAJIIT -> roll % 3 == 0 ? new Spawn("minecraft:husk", "Moon-Cursed Dead") : new Spawn(Mobs.bandit(), "Sand Raider");
            case BOSMER -> roll % 2 == 0 ? new Spawn(Mobs.spriggan(), null) : new Spawn("minecraft:skeleton", "Bosmer Ghost");
            case ALTMER -> roll % 2 == 0 ? new Spawn("minecraft:vex", "Crystal Sentinel") : new Spawn("minecraft:skeleton", "Aldmeri Guardian");
            case REDGUARD -> roll % 2 == 0 ? new Spawn("minecraft:husk", "Yokudan Mummy") : new Spawn("minecraft:skeleton", "Ansei Ghost");
            case BRETON -> roll % 3 == 0 ? new Spawn("minecraft:witch", "Witch") : new Spawn(Mobs.bandit(), null);
            case BRETON_KEEP_RUIN -> roll % 3 == 0 ? new Spawn(Mobs.necromancer(), "Keep Necromancer")
                    : roll % 2 == 0 ? new Spawn("minecraft:skeleton", "Fallen Knight")
                    : new Spawn(Mobs.bandit(), "Keep Brigand");
            case SCORPION_DEN -> roll % 3 == 0 ? new Spawn(Mobs.spider(), "Dune Spider") : new Spawn(Mobs.giantScorpion(), "Giant Scorpion");
            case ORC -> new Spawn(Mobs.bandit(), "Orc Berserker");
            case AKAVIRI -> roll % 2 == 0 ? new Spawn("minecraft:vindicator", "Tsaesci Warrior") : new Spawn("minecraft:pillager", "Tsaesci Archer");
            case ATMORAN -> roll % 3 == 0 ? new Spawn(Mobs.iceWraith(), null) : new Spawn(Mobs.draugr(), null);
            case MAORMER -> roll % 2 == 0 ? new Spawn("minecraft:drowned", "Maormer Raider") : new Spawn("minecraft:skeleton", "Drowned Sailor");
            case SLOAD -> roll % 2 == 0 ? new Spawn("minecraft:slime", "Sload Spawn") : new Spawn("minecraft:drowned", "Coral Thrall");
            case FALMER -> roll % 3 == 0 ? new Spawn(Mobs.spider(), "Chaurus") : new Spawn(Mobs.falmer(), null);
            case ICE -> roll % 2 == 0 ? new Spawn(Mobs.iceWraith(), null) : new Spawn(Mobs.spider(), null);
            case FORSWORN -> new Spawn(Mobs.forsworn(), null);
            case IMPERIAL -> new Spawn(Mobs.bandit(), null);
            default -> new Spawn(Mobs.draugr(), null);
        };
    }

    /** The master of the ruin, waiting in its deepest room. */
    public Spawn boss() {
        return switch (this) {
            case AYLEID -> new Spawn(Mobs.orElse("skycraft:lich", "minecraft:wither_skeleton"), "Ayleid Lich");
            case VELOTHI -> new Spawn("minecraft:wither_skeleton", "Bonelord");
            case DAEDRIC -> new Spawn(Mobs.orElse("skycraft:dremora", "minecraft:evoker"), "Dremora Valkynaz");
            case XANMEER -> new Spawn(Mobs.orElse("skycraft:troll", "minecraft:ravager"), "Hist Guardian");
            case KHAJIIT -> new Spawn(Mobs.banditChief(), "Moon Priest");
            case BOSMER -> new Spawn(Mobs.wispmother(), null);
            case ALTMER -> new Spawn("minecraft:evoker", "Aldmeri Sorcerer");
            case REDGUARD -> new Spawn("minecraft:wither_skeleton", "Ansei Sword-Lord");
            case BRETON -> new Spawn(Mobs.vampireMaster(), "Lord of the Keep");
            case BRETON_KEEP_RUIN -> new Spawn(Mobs.vampireMaster(), "Castellan of the Keep");
            case SCORPION_DEN -> new Spawn(Mobs.giantScorpion(), "Broodmother Scorpion");
            case ORC -> new Spawn(Mobs.banditChief(), "Orc Warlord");
            case AKAVIRI -> new Spawn(Mobs.deathlord(), "Tsaesci Lord");
            case ATMORAN -> new Spawn(Mobs.deathlord(), "Atmoran Ice-King");
            case MAORMER -> new Spawn("minecraft:vindicator", "Maormer Captain");
            case SLOAD -> new Spawn("minecraft:witch", "Sload Necromancer");
            case FALMER -> new Spawn(Mobs.falmer(), "Falmer Gloomlurker");
            case ICE -> new Spawn(Mobs.orElse("skycraft:troll", "minecraft:ravager"), "Frost Troll");
            case FORSWORN -> new Spawn(Mobs.forsworn(), "Forsworn Briarheart");
            case IMPERIAL -> new Spawn(Mobs.necromancer(), "Rogue Battlemage");
            default -> new Spawn(Mobs.deathlord(), null);
        };
    }

    /** An entity id with an optional custom name (only shown on vanilla stand-ins). */
    public record Spawn(@Nullable String id, @Nullable String name) {
        /** The name to give the mob: custom names only go on generic stand-ins. */
        @Nullable
        public String label() {
            return id != null && id.startsWith("minecraft:") ? name : null;
        }
    }

    // ------------------------------------------------------------------ palettes

    /**
     * Blocks a kit builds with. Weighted picks use the painter's position hash, so each block stays the same whatever
     * chunk paints it.
     */
    public static final class Palette {
        final BlockState[] walls;
        final int[] wallWeights;
        final BlockState[] floors;
        final int[] floorWeights;
        final BlockState[] ceilings;
        final int[] ceilingWeights;
        public final BlockState base;
        public final BlockState trim;
        public final BlockState pillar;
        public final BlockState pillarCap;
        public final Block stairs;
        public final Block slab;
        public final Block wallBlock;
        public final BlockState light;
        public final BlockState accent;
        public final BlockState rock;
        public final BlockState caveFloor;
        /** Whether walls of this culture are dressed with cobwebs (old tombs) or kept clean (crystal, coral). */
        public final boolean webs;

        private Palette(Builder b) {
            this.walls = b.walls;
            this.wallWeights = b.wallWeights;
            this.floors = b.floors;
            this.floorWeights = b.floorWeights;
            this.ceilings = b.ceilings;
            this.ceilingWeights = b.ceilingWeights;
            this.base = b.base;
            this.trim = b.trim;
            this.pillar = b.pillar;
            this.pillarCap = b.pillarCap;
            this.stairs = b.stairs;
            this.slab = b.slab;
            this.wallBlock = b.wallBlock;
            this.light = b.light;
            this.accent = b.accent;
            this.rock = b.rock;
            this.caveFloor = b.caveFloor;
            this.webs = b.webs;
        }

        public BlockState wall(Painter p, int x, int y, int z, int rel) {
            if (rel <= 0 && base != null) return base;
            return pick(walls, wallWeights, p.roll(x, y, z, 1, 100));
        }

        public BlockState floor(Painter p, int x, int y, int z) {
            return pick(floors, floorWeights, p.roll(x, y, z, 2, 100));
        }

        public BlockState ceiling(Painter p, int x, int y, int z) {
            return pick(ceilings, ceilingWeights, p.roll(x, y, z, 3, 100));
        }

        public BlockState pillar(int rel, int top) {
            return rel == 0 || rel == top ? pillarCap : pillar;
        }

        private static BlockState pick(BlockState[] states, int[] weights, int roll) {
            int total = 0;
            for (int w : weights) total += w;
            int r = roll % Math.max(1, total);
            for (int i = 0; i < states.length; i++) {
                r -= weights[i];
                if (r < 0) return states[i];
            }
            return states[states.length - 1];
        }

        static Palette of(Kit kit) {
            Builder b = new Builder();
            switch (kit) {
                case IMPERIAL -> b.walls(Blocks.STONE_BRICKS, 9, Blocks.CRACKED_STONE_BRICKS, 3, Blocks.ANDESITE, 3, Blocks.POLISHED_ANDESITE, 4, Blocks.MOSSY_STONE_BRICKS, 1)
                        .floors(Blocks.POLISHED_ANDESITE, 6, Blocks.STONE_BRICKS, 4, Blocks.SMOOTH_STONE, 3)
                        .ceilings(Blocks.STONE_BRICKS, 5, Blocks.POLISHED_ANDESITE, 2)
                        .pillar(Blocks.POLISHED_ANDESITE, Blocks.CHISELED_STONE_BRICKS).stairs(Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL)
                        .light(Deco.lantern(false, true)).accent(com.skycraft.worldgen.VanillaBlocks.RED_WOOL).trim(Blocks.SMOOTH_STONE);
                case AYLEID -> b.walls(Blocks.CALCITE, 8, Blocks.QUARTZ_BRICKS, 6, Blocks.SMOOTH_QUARTZ, 4, Blocks.POLISHED_DIORITE, 3, Blocks.DIORITE, 1)
                        .floors(Blocks.POLISHED_DIORITE, 5, Blocks.CALCITE, 4, Blocks.SMOOTH_QUARTZ, 3)
                        .ceilings(Blocks.SMOOTH_QUARTZ, 5, Blocks.QUARTZ_BRICKS, 3)
                        .pillar(Blocks.QUARTZ_PILLAR, Blocks.CHISELED_QUARTZ_BLOCK).stairs(Blocks.QUARTZ_STAIRS, Blocks.QUARTZ_SLAB, Blocks.DIORITE_WALL)
                        .light(Blocks.SEA_LANTERN.defaultBlockState()).accent(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS).trim(Blocks.CHISELED_QUARTZ_BLOCK).clean();
                case VELOTHI -> b.walls(Blocks.MUD_BRICKS, 8, Blocks.PACKED_MUD, 4, Blocks.TUFF, 2, com.skycraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA, 2)
                        .floors(Blocks.PACKED_MUD, 4, Blocks.MUD_BRICKS, 4, Blocks.TUFF, 2)
                        .ceilings(Blocks.MUD_BRICKS, 5, Blocks.PACKED_MUD, 3)
                        .pillar(Blocks.MUD_BRICKS, Blocks.CHISELED_SANDSTONE).stairs(Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_WALL)
                        .light(Deco.candle(com.skycraft.worldgen.VanillaBlocks.RED_CANDLE, 3, true)).accent(Blocks.BONE_BLOCK).trim(Blocks.SMOOTH_SANDSTONE);
                case DAEDRIC -> b.walls(Blocks.POLISHED_BLACKSTONE_BRICKS, 8, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, 3, Blocks.BLACKSTONE, 3, Blocks.RED_NETHER_BRICKS, 2, Blocks.CRYING_OBSIDIAN, 1)
                        .floors(Blocks.POLISHED_BLACKSTONE, 5, Blocks.BLACKSTONE, 3, Blocks.RED_NETHER_BRICKS, 1)
                        .ceilings(Blocks.POLISHED_BLACKSTONE_BRICKS, 5, Blocks.NETHER_BRICKS, 2)
                        .pillar(Blocks.POLISHED_BASALT, Blocks.CHISELED_POLISHED_BLACKSTONE).stairs(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB, Blocks.POLISHED_BLACKSTONE_BRICK_WALL)
                        .light(Blocks.SHROOMLIGHT.defaultBlockState()).accent(Blocks.CRYING_OBSIDIAN).trim(Blocks.GILDED_BLACKSTONE)
                        .rock(Blocks.BLACKSTONE, Blocks.BASALT);
                case XANMEER -> b.walls(Blocks.MOSSY_STONE_BRICKS, 7, Blocks.MOSSY_COBBLESTONE, 4, Blocks.TUFF, 2, com.skycraft.worldgen.VanillaBlocks.GREEN_TERRACOTTA, 2, Blocks.CRACKED_STONE_BRICKS, 2)
                        .floors(Blocks.MOSSY_STONE_BRICKS, 4, Blocks.MUD, 2, Blocks.MOSS_BLOCK, 2, Blocks.MOSSY_COBBLESTONE, 2)
                        .ceilings(Blocks.MOSSY_STONE_BRICKS, 5, Blocks.TUFF, 2)
                        .pillar(Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS).stairs(Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_WALL)
                        .light(Blocks.VERDANT_FROGLIGHT.defaultBlockState()).accent(com.skycraft.worldgen.VanillaBlocks.OXIDIZED_COPPER).trim(Blocks.CHISELED_STONE_BRICKS)
                        .rock(Blocks.MUD, Blocks.TUFF);
                case KHAJIIT -> b.walls(Blocks.CUT_SANDSTONE, 7, Blocks.SANDSTONE, 4, Blocks.SMOOTH_SANDSTONE, 3, com.skycraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA, 1)
                        .floors(Blocks.SMOOTH_SANDSTONE, 5, Blocks.CUT_SANDSTONE, 3, Blocks.SANDSTONE, 2)
                        .ceilings(Blocks.CUT_SANDSTONE, 4, Blocks.SMOOTH_SANDSTONE, 3)
                        .pillar(Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE).stairs(Blocks.SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE_SLAB, Blocks.SANDSTONE_WALL)
                        .light(Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState()).accent(Blocks.GOLD_BLOCK).trim(Blocks.CHISELED_SANDSTONE)
                        .rock(Blocks.SANDSTONE, Blocks.SANDSTONE);
                case BOSMER -> b.walls(Blocks.JUNGLE_LOG, 5, Blocks.STRIPPED_JUNGLE_LOG, 3, Blocks.ROOTED_DIRT, 3, Blocks.MUD, 2, Blocks.MOSS_BLOCK, 2)
                        .floors(Blocks.ROOTED_DIRT, 4, Blocks.MOSS_BLOCK, 3, Blocks.JUNGLE_PLANKS, 3)
                        .ceilings(Blocks.JUNGLE_LOG, 4, Blocks.ROOTED_DIRT, 3)
                        .pillar(Blocks.JUNGLE_LOG, Blocks.STRIPPED_JUNGLE_WOOD).stairs(Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_SLAB, Blocks.JUNGLE_FENCE)
                        .light(Blocks.SHROOMLIGHT.defaultBlockState()).accent(Blocks.MANGROVE_ROOTS).trim(Blocks.STRIPPED_JUNGLE_LOG)
                        .rock(Blocks.ROOTED_DIRT, Blocks.DIRT);
                case ALTMER -> b.walls(Blocks.SMOOTH_QUARTZ, 7, Blocks.QUARTZ_BRICKS, 4, com.skycraft.worldgen.VanillaBlocks.WHITE_CONCRETE, 2, Blocks.AMETHYST_BLOCK, 1)
                        .floors(Blocks.SMOOTH_QUARTZ, 5, Blocks.POLISHED_DIORITE, 2, Blocks.QUARTZ_BRICKS, 2)
                        .ceilings(Blocks.SMOOTH_QUARTZ, 5, Blocks.AMETHYST_BLOCK, 1)
                        .pillar(Blocks.QUARTZ_PILLAR, Blocks.CHISELED_QUARTZ_BLOCK).stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB, Blocks.DIORITE_WALL)
                        .light(Blocks.END_ROD.defaultBlockState()).accent(Blocks.AMETHYST_BLOCK).trim(Blocks.GOLD_BLOCK).clean();
                case REDGUARD -> b.walls(Blocks.CUT_RED_SANDSTONE, 6, Blocks.RED_SANDSTONE, 4, Blocks.SMOOTH_SANDSTONE, 3, com.skycraft.worldgen.VanillaBlocks.WHITE_TERRACOTTA, 2, com.skycraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA, 1)
                        .floors(Blocks.SMOOTH_RED_SANDSTONE, 4, Blocks.CUT_SANDSTONE, 3, Blocks.TERRACOTTA, 2)
                        .ceilings(Blocks.CUT_RED_SANDSTONE, 4, Blocks.SMOOTH_SANDSTONE, 3)
                        .pillar(Blocks.CUT_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE).stairs(Blocks.RED_SANDSTONE_STAIRS, Blocks.CUT_RED_SANDSTONE_SLAB, Blocks.RED_SANDSTONE_WALL)
                        .light(Deco.lantern(false, true)).accent(Blocks.GOLD_BLOCK).trim(Blocks.CHISELED_RED_SANDSTONE)
                        .rock(Blocks.RED_SANDSTONE, Blocks.SANDSTONE);
                case BRETON -> b.walls(Blocks.STONE_BRICKS, 7, Blocks.COBBLESTONE, 4, Blocks.MOSSY_STONE_BRICKS, 3, Blocks.CRACKED_STONE_BRICKS, 3)
                        .floors(Blocks.COBBLESTONE, 4, Blocks.STONE_BRICKS, 3, Blocks.SPRUCE_PLANKS, 2)
                        .ceilings(Blocks.DARK_OAK_PLANKS, 3, Blocks.STONE_BRICKS, 4)
                        .pillar(Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STONE_BRICKS).stairs(Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.COBBLESTONE_WALL)
                        .light(Deco.lantern(false, true)).accent(com.skycraft.worldgen.VanillaBlocks.BLUE_WOOL).trim(Blocks.DEEPSLATE_TILES);
                case ORC -> b.walls(Blocks.COBBLED_DEEPSLATE, 6, Blocks.BLACKSTONE, 3, Blocks.DEEPSLATE_BRICKS, 3, Blocks.SPRUCE_LOG, 2)
                        .floors(Blocks.COARSE_DIRT, 4, Blocks.COBBLED_DEEPSLATE, 3, Blocks.SPRUCE_PLANKS, 2)
                        .ceilings(Blocks.SPRUCE_PLANKS, 3, Blocks.COBBLED_DEEPSLATE, 4)
                        .pillar(Blocks.SPRUCE_LOG, Blocks.STRIPPED_SPRUCE_LOG).stairs(Blocks.COBBLED_DEEPSLATE_STAIRS, Blocks.COBBLED_DEEPSLATE_SLAB, Blocks.COBBLED_DEEPSLATE_WALL)
                        .light(Deco.lantern(false, true)).accent(Blocks.BONE_BLOCK).trim(Blocks.DEEPSLATE_TILES);
                case AKAVIRI -> b.walls(Blocks.POLISHED_BLACKSTONE_BRICKS, 6, Blocks.DEEPSLATE_TILES, 4, com.skycraft.worldgen.VanillaBlocks.RED_TERRACOTTA, 2, Blocks.MANGROVE_PLANKS, 2)
                        .floors(Blocks.DEEPSLATE_TILES, 5, Blocks.MANGROVE_PLANKS, 3, Blocks.POLISHED_DEEPSLATE, 2)
                        .ceilings(Blocks.MANGROVE_PLANKS, 4, Blocks.DEEPSLATE_TILES, 3)
                        .pillar(Blocks.STRIPPED_MANGROVE_LOG, Blocks.CHISELED_POLISHED_BLACKSTONE).stairs(Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_WALL)
                        .light(Deco.lantern(false, true)).accent(com.skycraft.worldgen.VanillaBlocks.RED_WOOL).trim(Blocks.GILDED_BLACKSTONE);
                case ATMORAN -> b.walls(Blocks.STONE_BRICKS, 5, Blocks.PACKED_ICE, 4, Blocks.BLUE_ICE, 1, Blocks.CRACKED_STONE_BRICKS, 3, Blocks.SNOW_BLOCK, 1)
                        .floors(Blocks.PACKED_ICE, 3, Blocks.STONE_BRICKS, 4, Blocks.SNOW_BLOCK, 1)
                        .ceilings(Blocks.PACKED_ICE, 4, Blocks.STONE_BRICKS, 3)
                        .pillar(Blocks.PACKED_ICE, Blocks.CHISELED_STONE_BRICKS).stairs(Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL)
                        .light(Deco.lantern(true, true)).accent(Blocks.BLUE_ICE).trim(Blocks.CHISELED_STONE_BRICKS)
                        .rock(Blocks.PACKED_ICE, Blocks.SNOW_BLOCK);
                case MAORMER -> b.walls(Blocks.PRISMARINE_BRICKS, 6, Blocks.PRISMARINE, 5, Blocks.DARK_PRISMARINE, 3, Blocks.DEAD_TUBE_CORAL_BLOCK, 1)
                        .floors(Blocks.PRISMARINE_BRICKS, 4, Blocks.SMOOTH_SANDSTONE, 2, Blocks.DARK_PRISMARINE, 2)
                        .ceilings(Blocks.DARK_PRISMARINE, 4, Blocks.PRISMARINE, 3)
                        .pillar(Blocks.DARK_PRISMARINE, Blocks.PRISMARINE_BRICKS).stairs(Blocks.PRISMARINE_BRICK_STAIRS, Blocks.PRISMARINE_BRICK_SLAB, Blocks.PRISMARINE_WALL)
                        .light(Blocks.SEA_LANTERN.defaultBlockState()).accent(Blocks.DEAD_BRAIN_CORAL_BLOCK).trim(Blocks.DARK_PRISMARINE).clean()
                        .rock(Blocks.PRISMARINE, Blocks.SANDSTONE);
                case SLOAD -> b.walls(Blocks.DEAD_BRAIN_CORAL_BLOCK, 4, Blocks.DEAD_TUBE_CORAL_BLOCK, 4, Blocks.DEAD_HORN_CORAL_BLOCK, 3, Blocks.TUFF, 2, Blocks.SANDSTONE, 2)
                        .floors(Blocks.SMOOTH_SANDSTONE, 4, Blocks.DEAD_FIRE_CORAL_BLOCK, 2, Blocks.MUD, 2)
                        .ceilings(Blocks.DEAD_BUBBLE_CORAL_BLOCK, 4, Blocks.DEAD_TUBE_CORAL_BLOCK, 3)
                        .pillar(Blocks.DEAD_HORN_CORAL_BLOCK, Blocks.DEAD_BRAIN_CORAL_BLOCK).stairs(Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_WALL)
                        .light(Blocks.OCHRE_FROGLIGHT.defaultBlockState()).accent(Blocks.SLIME_BLOCK).trim(Blocks.DEAD_FIRE_CORAL_BLOCK).clean()
                        .rock(Blocks.DEAD_TUBE_CORAL_BLOCK, Blocks.SANDSTONE);
                case FALMER -> b.walls(Blocks.BROWN_MUSHROOM_BLOCK, 4, Blocks.MUSHROOM_STEM, 3, Blocks.MUD, 3, Blocks.TUFF, 2)
                        .floors(Blocks.MUD, 3, Blocks.MYCELIUM, 3, Blocks.TUFF, 2)
                        .ceilings(Blocks.BROWN_MUSHROOM_BLOCK, 4, Blocks.TUFF, 3)
                        .pillar(Blocks.MUSHROOM_STEM, Blocks.SHROOMLIGHT).stairs(Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_WALL)
                        .light(Blocks.SHROOMLIGHT.defaultBlockState()).accent(Blocks.GLOW_LICHEN).trim(Blocks.MUSHROOM_STEM)
                        .rock(Blocks.TUFF, Blocks.MUD);
                case ICE -> b.walls(Blocks.PACKED_ICE, 6, Blocks.BLUE_ICE, 2, Blocks.SNOW_BLOCK, 2, Blocks.STONE, 3)
                        .floors(Blocks.SNOW_BLOCK, 4, Blocks.PACKED_ICE, 3)
                        .ceilings(Blocks.PACKED_ICE, 5, Blocks.BLUE_ICE, 2)
                        .pillar(Blocks.PACKED_ICE, Blocks.BLUE_ICE).stairs(Blocks.STONE_STAIRS, Blocks.STONE_SLAB, Blocks.COBBLESTONE_WALL)
                        .light(Deco.lantern(true, true)).accent(Blocks.BLUE_ICE).trim(Blocks.BLUE_ICE).clean()
                        .rock(Blocks.PACKED_ICE, Blocks.SNOW_BLOCK);
                case FORSWORN -> b.walls(Blocks.TUFF, 5, Blocks.COBBLESTONE, 4, Blocks.MOSSY_COBBLESTONE, 3, Blocks.STRIPPED_SPRUCE_LOG, 2)
                        .floors(Blocks.COARSE_DIRT, 4, Blocks.PODZOL, 2, Blocks.TUFF, 3)
                        .ceilings(Blocks.SPRUCE_PLANKS, 3, Blocks.TUFF, 4)
                        .pillar(Blocks.SPRUCE_LOG, Blocks.BONE_BLOCK).stairs(Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB, Blocks.COBBLESTONE_WALL)
                        .light(Deco.campfire(false, true)).accent(Blocks.BONE_BLOCK).trim(Blocks.MOSSY_COBBLESTONE);
                case BRETON_KEEP_RUIN -> b.walls(Blocks.STONE_BRICKS, 6, Blocks.MOSSY_STONE_BRICKS, 4, Blocks.CRACKED_STONE_BRICKS, 3, Blocks.DEEPSLATE_TILES, 3)
                        .floors(Blocks.DEEPSLATE_TILES, 5, Blocks.STONE_BRICKS, 4, Blocks.MOSSY_STONE_BRICKS, 2)
                        .ceilings(Blocks.DEEPSLATE_TILES, 4, Blocks.STONE_BRICKS, 4)
                        .pillar(Blocks.DEEPSLATE_TILES, Blocks.CHISELED_STONE_BRICKS).stairs(Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.STONE_BRICK_WALL)
                        .light(Deco.lantern(true, true)).accent(com.skycraft.worldgen.VanillaBlocks.BLUE_WOOL).trim(Blocks.DEEPSLATE_TILES)
                        .rock(Blocks.COBBLESTONE, Blocks.STONE_BRICKS);
                case SCORPION_DEN -> b.walls(Blocks.CUT_SANDSTONE, 5, Blocks.RED_SANDSTONE, 4, Blocks.SANDSTONE, 4, Blocks.TERRACOTTA, 2, com.skycraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA, 1)
                        .floors(Blocks.SAND, 4, Blocks.RED_SAND, 3, Blocks.CUT_SANDSTONE, 3)
                        .ceilings(Blocks.SANDSTONE, 5, Blocks.RED_SANDSTONE, 3)
                        .pillar(Blocks.CUT_RED_SANDSTONE, Blocks.CHISELED_RED_SANDSTONE).stairs(Blocks.RED_SANDSTONE_STAIRS, Blocks.CUT_SANDSTONE_SLAB, Blocks.SANDSTONE_WALL)
                        .light(Deco.lantern(false, true)).accent(Blocks.BONE_BLOCK).trim(Blocks.CHISELED_SANDSTONE)
                        .rock(Blocks.SANDSTONE, Blocks.SAND);
                default -> b.walls(Blocks.STONE_BRICKS, 9, Blocks.MOSSY_STONE_BRICKS, 4, Blocks.CRACKED_STONE_BRICKS, 5, Blocks.TUFF, 1)
                        .floors(Blocks.DEEPSLATE_TILES, 11, Blocks.CRACKED_DEEPSLATE_TILES, 4, Blocks.STONE_BRICKS, 3, Blocks.MOSSY_STONE_BRICKS, 2)
                        .ceilings(Blocks.STONE_BRICKS, 10, Blocks.MOSSY_STONE_BRICKS, 5, Blocks.CRACKED_STONE_BRICKS, 5)
                        .pillar(Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS).stairs(Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL)
                        .light(Deco.lantern(true, true)).accent(Blocks.CHISELED_STONE_BRICKS).trim(Blocks.CHISELED_STONE_BRICKS);
            }
            return new Palette(b);
        }
    }

    private static final class Builder {
        BlockState[] walls, floors, ceilings;
        int[] wallWeights, floorWeights, ceilingWeights;
        BlockState base, trim, pillar, pillarCap, light, accent;
        BlockState rock = Blocks.STONE.defaultBlockState();
        BlockState caveFloor = Blocks.COARSE_DIRT.defaultBlockState();
        Block stairs, slab, wallBlock;
        boolean webs = true;

        private static BlockState[] states(Object[] pairs) {
            BlockState[] out = new BlockState[pairs.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = ((Block) pairs[i * 2]).defaultBlockState();
            return out;
        }

        private static int[] weights(Object[] pairs) {
            int[] out = new int[pairs.length / 2];
            for (int i = 0; i < out.length; i++) out[i] = (Integer) pairs[i * 2 + 1];
            return out;
        }

        Builder walls(Object... pairs) {
            walls = states(pairs);
            wallWeights = weights(pairs);
            return this;
        }

        Builder floors(Object... pairs) {
            floors = states(pairs);
            floorWeights = weights(pairs);
            return this;
        }

        Builder ceilings(Object... pairs) {
            ceilings = states(pairs);
            ceilingWeights = weights(pairs);
            return this;
        }

        Builder pillar(Block body, Block cap) {
            pillar = body.defaultBlockState().hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                    ? Deco.pillar(body, Direction.Axis.Y) : body.defaultBlockState();
            pillarCap = cap.defaultBlockState();
            return this;
        }

        Builder stairs(Block stairs, Block slab, Block wall) {
            this.stairs = stairs;
            this.slab = slab;
            this.wallBlock = wall;
            return this;
        }

        Builder light(BlockState light) {
            this.light = light;
            return this;
        }

        Builder accent(Block accent) {
            this.accent = accent.defaultBlockState();
            return this;
        }

        Builder trim(Block trim) {
            this.trim = trim.defaultBlockState();
            return this;
        }

        Builder rock(Block rock, Block floor) {
            this.rock = rock.defaultBlockState();
            this.caveFloor = floor.defaultBlockState();
            return this;
        }

        Builder clean() {
            this.webs = false;
            return this;
        }
    }

    /** Lazily resolved block for optional content. */
    static Supplier<BlockState> lazy(String id, BlockState fallback) {
        return () -> Painter.block(id, fallback);
    }
}
