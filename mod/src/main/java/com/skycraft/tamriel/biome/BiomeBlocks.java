package com.skycraft.tamriel.biome;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.biome.block.AshLayerBlock;
import com.skycraft.tamriel.biome.block.FlammableBlocks;
import com.skycraft.tamriel.biome.block.GroundPlantBlock;
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
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.skycraft.scatter.DenBlock;
import com.skycraft.scatter.DenBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The blocks of Nirn's biomes: Morrowind's ash and volcanic rock, autumn aspens, giant mushrooms, Graht-oak, palm and
 * Hist wood, Summerset crystal, Thras coral stone, desert dunes, swamp reeds and ash plants. Every block gets a block
 * item. Textures come from tools/textures/tamriel.py.
 */
public final class BiomeBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Skycraft.MODID);

    /** Every block of this module in registration order. */
    public static final Map<String, RegistryObject<Block>> ALL = new LinkedHashMap<>();

    // ------------------------------------------------------------------------------------------ Morrowind ash
    public static final RegistryObject<Block> ASH_BLOCK = register("ash_block",
            () -> new SandBlock(0x6B6560, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY)
                    .instrument(NoteBlockInstrument.SNARE).strength(0.5f).sound(SoundType.SAND)));
    public static final RegistryObject<Block> ASH_LAYER = register("ash_layer",
            () -> new AshLayerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).replaceable()
                    .forceSolidOff().strength(0.1f).sound(SoundType.SAND).isViewBlocking((s, l, p) ->
                            s.getValue(AshLayerBlock.LAYERS) >= 8).pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> VOLCANIC_ROCK = register("volcanic_rock",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5f, 6f)
                    .sound(SoundType.BASALT)));
    public static final RegistryObject<Block> CRACKED_VOLCANIC_ROCK = register("cracked_volcanic_rock",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE).mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5f, 6f).sound(SoundType.BASALT)));
    public static final RegistryObject<Block> MAGMA_VOLCANIC_ROCK = register("magma_volcanic_rock",
            () -> new MagmaBlock(BlockBehaviour.Properties.of().mapColor(MapColor.NETHER)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().lightLevel(s -> 3)
                    .strength(1.5f, 6f).sound(SoundType.BASALT).isValidSpawn((s, l, p, e) -> e.fireImmune())
                    .hasPostProcess((s, l, p) -> true).emissiveRendering((s, l, p) -> true)));
    public static final RegistryObject<Block> SCATHECRAW = register("scathecraw",
            () -> new GroundPlantBlock.Short(plant(MapColor.COLOR_BROWN), BiomeBlocks::ashSoil));
    public static final RegistryObject<Block> ASH_GRASS = register("ash_grass",
            () -> new GroundPlantBlock.Short(plant(MapColor.COLOR_GRAY), BiomeBlocks::ashSoil));

    // --------------------------------------------------------------------------------- Morrowind mushrooms
    public static final RegistryObject<Block> PARASOL_STEM = register("parasol_stem",
            () -> new FlammableBlocks.FungusStem(BlockBehaviour.Properties.of().mapColor(MapColor.WOOL)
                    .instrument(NoteBlockInstrument.BASS).strength(1.5f).sound(SoundType.STEM)));
    public static final RegistryObject<Block> PARASOL_CAP = register("parasol_cap",
            () -> new FlammableBlocks.Fungus(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE)
                    .instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WOOD)));
    public static final RegistryObject<Block> TELVANNI_CAP = register("telvanni_cap",
            () -> new FlammableBlocks.Fungus(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE)
                    .instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WOOD)));
    public static final RegistryObject<Block> GLOWING_MUSHROOM_CAP = register("glowing_mushroom_cap",
            () -> new FlammableBlocks.Fungus(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN)
                    .instrument(NoteBlockInstrument.BASS).strength(1.0f).sound(SoundType.WART_BLOCK)
                    .lightLevel(s -> 13).emissiveRendering((s, l, p) -> true)));

    // ----------------------------------------------------------------------------------------- the trees
    public static final RegistryObject<Block> GOLDEN_ASPEN_LEAVES = register("golden_aspen_leaves",
            () -> new FlammableBlocks.Leaves(leaves(MapColor.GOLD)));
    public static final RegistryObject<Block> ORANGE_ASPEN_LEAVES = register("orange_aspen_leaves",
            () -> new FlammableBlocks.Leaves(leaves(MapColor.COLOR_ORANGE)));
    public static final RegistryObject<Block> SNOWY_SPRUCE_LEAVES = register("snowy_spruce_leaves",
            () -> new FlammableBlocks.Leaves(leaves(MapColor.SNOW)));
    public static final RegistryObject<Block> GRAHT_OAK_LOG = register("graht_oak_log",
            () -> new FlammableBlocks.Log(wood(MapColor.COLOR_BROWN)));
    public static final RegistryObject<Block> GRAHT_OAK_WOOD = register("graht_oak_wood",
            () -> new FlammableBlocks.Log(wood(MapColor.COLOR_BROWN)));
    public static final RegistryObject<Block> GRAHT_OAK_PLANKS = register("graht_oak_planks",
            () -> new FlammableBlocks.Planks(planks(MapColor.TERRACOTTA_BROWN)));
    public static final RegistryObject<Block> GRAHT_OAK_LEAVES = register("graht_oak_leaves",
            () -> new FlammableBlocks.Leaves(leaves(MapColor.PLANT)));
    public static final RegistryObject<Block> PALM_LOG = register("palm_log",
            () -> new FlammableBlocks.Log(wood(MapColor.SAND)));
    public static final RegistryObject<Block> PALM_PLANKS = register("palm_planks",
            () -> new FlammableBlocks.Planks(planks(MapColor.SAND)));
    public static final RegistryObject<Block> PALM_FRONDS = register("palm_fronds",
            () -> new FlammableBlocks.Leaves(leaves(MapColor.PLANT)));
    public static final RegistryObject<Block> HIST_LOG = register("hist_log",
            () -> new FlammableBlocks.Log(wood(MapColor.TERRACOTTA_GRAY)));
    public static final RegistryObject<Block> HIST_LEAVES = register("hist_leaves",
            () -> new FlammableBlocks.Leaves(leaves(MapColor.PLANT)));
    public static final RegistryObject<Block> HIST_SAP_BLOCK = register("hist_sap_block",
            () -> new HoneyBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).speedFactor(0.4f)
                    .jumpFactor(0.5f).noOcclusion().sound(SoundType.HONEY_BLOCK).lightLevel(s -> 4)));

    // ------------------------------------------------------------------------------ Summerset, Thras, deserts
    public static final RegistryObject<Block> CRYSTAL_BLOCK = register("crystal_block",
            () -> new HalfTransparentBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(1.5f).sound(SoundType.AMETHYST).requiresCorrectToolForDrops().noOcclusion()
                    .lightLevel(s -> 6).isValidSpawn((s, l, p, e) -> false).isRedstoneConductor((s, l, p) -> false)
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false)));
    public static final RegistryObject<Block> CRYSTAL_CLUSTER = register("crystal_cluster",
            () -> new AmethystClusterBlock(7, 3, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER).strength(1.5f)
                    .lightLevel(s -> 8).pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> CORAL_STONE = register("coral_stone",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PINK)
                    .instrument(NoteBlockInstrument.BASEDRUM).requiresCorrectToolForDrops().strength(1.5f, 6f)
                    .sound(SoundType.CORAL_BLOCK)));
    public static final RegistryObject<Block> DUNE_SAND = register("dune_sand",
            () -> new SandBlock(0xDCC08A, BlockBehaviour.Properties.of().mapColor(MapColor.SAND)
                    .instrument(NoteBlockInstrument.SNARE).strength(0.5f).sound(SoundType.SAND)));
    public static final RegistryObject<Block> FROSTED_STONE = register("frosted_stone",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE).mapColor(MapColor.SNOW)));
    public static final RegistryObject<Block> SWAMP_REEDS = register("swamp_reeds",
            () -> new GroundPlantBlock.Tall(BlockBehaviour.Properties.of().mapColor(MapColor.PLANT).replaceable()
                    .noCollission().instabreak().sound(SoundType.GRASS).ignitedByLava()
                    .offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.DESTROY),
                    s -> GroundPlantBlock.softSoil(s) || s.is(Blocks.MUD) || s.is(Blocks.MOSS_BLOCK)));

    public static final RegistryObject<Block> CREATURE_DEN = registerBlockOnly("creature_den", DenBlock::new);
    public static final RegistryObject<BlockEntityType<DenBlockEntity>> CREATURE_DEN_BE =
            BLOCK_ENTITIES.register("creature_den",
                    () -> BlockEntityType.Builder.of(DenBlockEntity::new, CREATURE_DEN.get()).build(null));

    private BiomeBlocks() {}

    private static RegistryObject<Block> register(String id, Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static RegistryObject<Block> registerBlockOnly(String id, Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        return block;
    }

    /** Ash plants grow on ash, volcanic rock and ordinary soil. */
    private static boolean ashSoil(BlockState state) {
        return GroundPlantBlock.softSoil(state) || state.is(ASH_BLOCK.get()) || state.is(VOLCANIC_ROCK.get())
                || state.is(CRACKED_VOLCANIC_ROCK.get());
    }

    private static BlockBehaviour.Properties plant(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).replaceable().noCollission().instabreak()
                .sound(SoundType.GRASS).offsetType(BlockBehaviour.OffsetType.XZ).ignitedByLava()
                .pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties leaves(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).strength(0.2f).randomTicks().sound(SoundType.GRASS)
                .noOcclusion().isValidSpawn((s, l, p, e) -> false).isSuffocating((s, l, p) -> false)
                .isViewBlocking((s, l, p) -> false).ignitedByLava().pushReaction(PushReaction.DESTROY)
                .isRedstoneConductor((s, l, p) -> false);
    }

    private static BlockBehaviour.Properties wood(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASS).strength(2.0f)
                .sound(SoundType.WOOD).ignitedByLava();
    }

    private static BlockBehaviour.Properties planks(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASS).strength(2.0f, 3.0f)
                .sound(SoundType.WOOD).ignitedByLava();
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
