package com.nirncraft.tamriel.biome;

import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.biome.block.AshLayerBlock;
import com.nirncraft.tamriel.biome.block.FlammableBlocks;
import com.nirncraft.tamriel.biome.block.GroundPlantBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.SandBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.LinkedHashMap;
import java.util.Map;

import com.nirncraft.scatter.DenBlock;
import com.nirncraft.scatter.DenBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The blocks of Nirn's biomes: Morrowind's ash and volcanic rock, autumn aspens, giant mushrooms, Graht-oak, palm and
 * Hist wood, Summerset crystal, Thras coral stone, desert dunes, swamp reeds and ash plants. Every block gets a block
 * item. Textures come from tools/textures/tamriel.py.
 */
public final class BiomeBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, NirnCraft.MODID);

    /** Every block of this module in registration order. */
    public static final Map<String, Supplier<Block>> ALL = new LinkedHashMap<>();

    // ------------------------------------------------------------------------------------------ Morrowind ash
    public static final Supplier<Block> ASH_BLOCK = register("ash_block",
            key -> new SandBlock(new net.minecraft.util.ColorRGBA(0x6B6560), BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_GRAY)
                    .instrument(NoteBlockInstrument.SNARE).strength(0.5f).sound(SoundType.SAND)));
    public static final Supplier<Block> ASH_LAYER = register("ash_layer",
            key -> new AshLayerBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_GRAY).replaceable()
                    .forceSolidOff().strength(0.1f).sound(SoundType.SAND).isViewBlocking((s, l, p, nearPlane) ->
                            s.getValue(AshLayerBlock.LAYERS) >= 8).pushReaction(PushReaction.POPPED)));
    public static final Supplier<Block> VOLCANIC_ROCK = register("volcanic_rock",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_BLACK)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5f, 6f)
                    .sound(SoundType.BASALT)));
    public static final Supplier<Block> CRACKED_VOLCANIC_ROCK = register("cracked_volcanic_rock",
            key -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5f, 6f).sound(SoundType.BASALT)));
    public static final Supplier<Block> MAGMA_VOLCANIC_ROCK = register("magma_volcanic_rock",
            key -> new MagmaBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.NETHER)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().lightLevel(s -> 3)
                    .strength(1.5f, 6f).sound(SoundType.BASALT).isValidSpawn((s, l, p, e) -> e.fireImmune())
                    .postProcess((s, l, p) -> p).emissiveRendering(s -> true)));
    public static final Supplier<Block> SCATHECRAW = register("scathecraw",
            key -> new GroundPlantBlock.Short(plant(key, MapColor.COLOR_BROWN), BiomeBlocks::ashSoil));
    public static final Supplier<Block> ASH_GRASS = register("ash_grass",
            key -> new GroundPlantBlock.Short(plant(key, MapColor.COLOR_GRAY), BiomeBlocks::ashSoil));

    // --------------------------------------------------------------------------------- Morrowind mushrooms
    public static final Supplier<Block> PARASOL_STEM = register("parasol_stem",
            key -> new FlammableBlocks.FungusStem(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.WOOL)
                    .instrument(NoteBlockInstrument.BASS).strength(1.5f).sound(SoundType.STEM)));
    public static final Supplier<Block> PARASOL_CAP = register("parasol_cap",
            key -> new FlammableBlocks.Fungus(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_ORANGE)
                    .instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WOOD)));
    public static final Supplier<Block> TELVANNI_CAP = register("telvanni_cap",
            key -> new FlammableBlocks.Fungus(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_PURPLE)
                    .instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WOOD)));
    public static final Supplier<Block> GLOWING_MUSHROOM_CAP = register("glowing_mushroom_cap",
            key -> new FlammableBlocks.Fungus(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_CYAN)
                    .instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WART_BLOCK)
                    .lightLevel(s -> 13).emissiveRendering(s -> true)));

    // ----------------------------------------------------------------------------------------- the trees
    public static final Supplier<Block> GOLDEN_ASPEN_LEAVES = register("golden_aspen_leaves",
            key -> new FlammableBlocks.Leaves(leaves(key, MapColor.GOLD)));
    public static final Supplier<Block> ORANGE_ASPEN_LEAVES = register("orange_aspen_leaves",
            key -> new FlammableBlocks.Leaves(leaves(key, MapColor.COLOR_ORANGE)));
    public static final Supplier<Block> SNOWY_SPRUCE_LEAVES = register("snowy_spruce_leaves",
            key -> new FlammableBlocks.Leaves(leaves(key, MapColor.SNOW)));
    public static final Supplier<Block> GRAHT_OAK_LOG = register("graht_oak_log",
            key -> new FlammableBlocks.Log(wood(key, MapColor.COLOR_BROWN)));
    public static final Supplier<Block> GRAHT_OAK_WOOD = register("graht_oak_wood",
            key -> new FlammableBlocks.Log(wood(key, MapColor.COLOR_BROWN)));
    public static final Supplier<Block> GRAHT_OAK_PLANKS = register("graht_oak_planks",
            key -> new FlammableBlocks.Planks(planks(key, MapColor.TERRACOTTA_BROWN)));
    public static final Supplier<Block> GRAHT_OAK_LEAVES = register("graht_oak_leaves",
            key -> new FlammableBlocks.Leaves(leaves(key, MapColor.PLANT)));
    public static final Supplier<Block> PALM_LOG = register("palm_log",
            key -> new FlammableBlocks.Log(wood(key, MapColor.SAND)));
    public static final Supplier<Block> PALM_PLANKS = register("palm_planks",
            key -> new FlammableBlocks.Planks(planks(key, MapColor.SAND)));
    public static final Supplier<Block> PALM_FRONDS = register("palm_fronds",
            key -> new FlammableBlocks.Leaves(leaves(key, MapColor.PLANT)));
    public static final Supplier<Block> HIST_LOG = register("hist_log",
            key -> new FlammableBlocks.Log(wood(key, MapColor.TERRACOTTA_GRAY)));
    public static final Supplier<Block> HIST_LEAVES = register("hist_leaves",
            key -> new FlammableBlocks.Leaves(leaves(key, MapColor.PLANT)));
    public static final Supplier<Block> HIST_SAP_BLOCK = register("hist_sap_block",
            key -> new HoneyBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_ORANGE).speedFactor(0.4f)
                    .jumpFactor(0.5f).noOcclusion().sound(SoundType.HONEY_BLOCK).lightLevel(s -> 4)));

    // ------------------------------------------------------------------------------ Summerset, Thras, deserts
    public static final Supplier<Block> CRYSTAL_BLOCK = register("crystal_block",
            key -> new HalfTransparentBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(1.5f).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().noOcclusion()
                    .lightLevel(s -> 6).isValidSpawn((s, l, p, e) -> false).isRedstoneConductor((s, l, p) -> false)
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p, nearPlane) -> false)));
    public static final Supplier<Block> CRYSTAL_CLUSTER = register("crystal_cluster",
            key -> new AmethystClusterBlock(7, 3, BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER).strength(1.5f)
                    .lightLevel(s -> 8).pushReaction(PushReaction.POPPED)));
    public static final Supplier<Block> CORAL_STONE = register("coral_stone",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_PINK)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5f, 6f)
                    .sound(SoundType.CORAL_BLOCK)));
    public static final Supplier<Block> DUNE_SAND = register("dune_sand",
            key -> new SandBlock(new net.minecraft.util.ColorRGBA(0xDCC08A), BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.SAND)
                    .instrument(NoteBlockInstrument.SNARE).strength(0.5f).sound(SoundType.SAND)));
    public static final Supplier<Block> FROSTED_STONE = register("frosted_stone",
            key -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.SNOW)));
    public static final Supplier<Block> SWAMP_REEDS = register("swamp_reeds",
            key -> new GroundPlantBlock.Tall(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.PLANT).replaceable()
                    .noCollision().instabreak().sound(SoundType.GRASS).ignitedByLava()
                    .offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.POPPED),
                    s -> GroundPlantBlock.softSoil(s) || s.is(Blocks.MUD) || s.is(Blocks.MOSS_BLOCK)));

    public static final Supplier<Block> CREATURE_DEN = registerBlockOnly("creature_den", key -> new DenBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key))));
    public static final Supplier<BlockEntityType<DenBlockEntity>> CREATURE_DEN_BE =
            BLOCK_ENTITIES.register("creature_den",
                    () -> new BlockEntityType<>(DenBlockEntity::new, CREATURE_DEN.get()));

    private BiomeBlocks() {}

    private static Supplier<Block> register(String id, Function<Identifier, Block> factory) {
        Supplier<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        ITEMS.register(id, key -> new BlockItem(block.get(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key)).useBlockDescriptionPrefix()));
        return block;
    }

    private static Supplier<Block> registerBlockOnly(String id, Function<Identifier, Block> factory) {
        Supplier<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        return block;
    }

    /** Ash plants grow on ash, volcanic rock and ordinary soil. */
    private static boolean ashSoil(BlockState state) {
        return GroundPlantBlock.softSoil(state) || state.is(ASH_BLOCK.get()) || state.is(VOLCANIC_ROCK.get())
                || state.is(CRACKED_VOLCANIC_ROCK.get());
    }

    private static BlockBehaviour.Properties plant(Identifier key, MapColor color) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).replaceable().noCollision().instabreak()
                .sound(SoundType.GRASS).offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava()
                .pushReaction(PushReaction.POPPED);
    }

    private static BlockBehaviour.Properties leaves(Identifier key, MapColor color) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).strength(0.2f).randomTicks().sound(SoundType.GRASS)
                .noOcclusion().isValidSpawn((s, l, p, e) -> false).isSuffocating((s, l, p) -> false)
                .isViewBlocking((s, l, p, nearPlane) -> false).ignitedByLava().pushReaction(PushReaction.POPPED)
                .isRedstoneConductor((s, l, p) -> false);
    }

    private static BlockBehaviour.Properties wood(Identifier key, MapColor color) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).instrument(NoteBlockInstrument.BASS).strength(2.0f)
                .sound(SoundType.WOOD).ignitedByLava();
    }

    private static BlockBehaviour.Properties planks(Identifier key, MapColor color) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).instrument(NoteBlockInstrument.BASS).strength(2.0f, 3.0f)
                .sound(SoundType.WOOD).ignitedByLava();
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
