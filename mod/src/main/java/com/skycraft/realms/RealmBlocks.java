package com.skycraft.realms;

import com.skycraft.Skycraft;
import com.skycraft.realms.block.BlackBookBlock;
import com.skycraft.realms.block.HarradaBlock;
import com.skycraft.realms.block.RealmDoorBlock;
import com.skycraft.realms.block.RealmPortalBlock;
import com.skycraft.realms.block.SigilFlameBlock;
import com.skycraft.realms.block.SigilStoneBlock;
import com.skycraft.realms.block.SpikeBlock;
import com.skycraft.tamriel.biome.block.GroundPlantBlock;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The blocks of the realms: Deadlands rock, ash, bloodgrass and harrada, Daedric masonry and the Sigil Stone;
 * the Shivering Isles' giant mushroom caps and blooms; Apocrypha's pages, glyph stone and tentacles; the Soul
 * Cairn's soul ash and soul-gem crystal; Coldharbour's stone; and every realm's gate. Textures come from
 * tools/textures/realms.py. Gates, doors, the Sigil Stone and the Black Books have no item (structures place them).
 */
public final class RealmBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);

    /** Every block of this module in registration order. */
    public static final Map<String, RegistryObject<Block>> ALL = new LinkedHashMap<>();

    // ------------------------------------------------------------------------------------------ the Deadlands
    public static final RegistryObject<Block> DEADLANDS_ROCK = register("deadlands_rock",
            () -> new Block(stone(MapColor.COLOR_RED, 1.6f).sound(SoundType.BASALT)));
    public static final RegistryObject<Block> DEADLANDS_MAGMA_ROCK = register("deadlands_magma_rock",
            () -> new MagmaBlock(stone(MapColor.NETHER, 1.6f).sound(SoundType.BASALT).lightLevel(s -> 5)
                    .isValidSpawn((s, l, p, e) -> e.fireImmune()).hasPostProcess((s, l, p) -> true)
                    .emissiveRendering((s, l, p) -> true)));
    public static final RegistryObject<Block> DEADLANDS_ASH = register("deadlands_ash",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.5f)
                    .sound(SoundType.SAND).instrument(NoteBlockInstrument.SNARE)));
    public static final RegistryObject<Block> BLOODGRASS = register("bloodgrass",
            () -> new GroundPlantBlock.Short(plant(MapColor.COLOR_RED), RealmBlocks::realmSoil));
    public static final RegistryObject<Block> HARRADA = register("harrada",
            () -> new HarradaBlock(plant(MapColor.COLOR_RED).sound(SoundType.WEEPING_VINES), RealmBlocks::realmSoil));
    public static final RegistryObject<Block> DAEDRIC_BRICKS = register("daedric_bricks",
            () -> new Block(stone(MapColor.COLOR_BLACK, 2.5f).sound(SoundType.NETHER_BRICKS)));
    public static final RegistryObject<Block> DAEDRIC_GLYPH_BRICKS = register("daedric_glyph_bricks",
            () -> new Block(stone(MapColor.COLOR_BLACK, 2.5f).sound(SoundType.NETHER_BRICKS).lightLevel(s -> 7)
                    .emissiveRendering((s, l, p) -> true)));
    public static final RegistryObject<Block> DAEDRIC_PILLAR = register("daedric_pillar",
            () -> new RotatedPillarBlock(stone(MapColor.COLOR_BLACK, 2.5f).sound(SoundType.NETHER_BRICKS)));
    public static final RegistryObject<Block> DAEDRIC_SPIKE = register("daedric_spike",
            () -> new SpikeBlock(stone(MapColor.COLOR_BLACK, 2.0f).sound(SoundType.NETHER_BRICKS).noOcclusion()));
    public static final RegistryObject<Block> SIGIL_STONE = registerNoItem("sigil_stone",
            () -> new SigilStoneBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(-1f, 3600000f)
                    .lightLevel(s -> 15).noOcclusion().sound(SoundType.AMETHYST).emissiveRendering((s, l, p) -> true)));
    public static final RegistryObject<Block> SIGIL_FLAME = registerNoItem("sigil_flame",
            () -> new SigilFlameBlock(BlockBehaviour.Properties.of().mapColor(MapColor.FIRE).noCollission().noOcclusion()
                    .strength(-1f, 3600000f).lightLevel(s -> 12).noLootTable().pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.WOOL).replaceable()));
    public static final RegistryObject<Block> OBLIVION_PORTAL = registerNoItem("oblivion_portal",
            () -> new RealmPortalBlock(portal(MapColor.FIRE, 13), Realm.DEADLANDS, () -> ParticleTypes.FLAME));

    // ------------------------------------------------------------------------------------------ Sovngarde
    public static final RegistryObject<Block> VALOR_PORTAL = registerNoItem("valor_portal",
            () -> new RealmPortalBlock(portal(MapColor.GOLD, 14), Realm.SOVNGARDE, () -> ParticleTypes.END_ROD));

    // ------------------------------------------------------------------------------------------ the Shivering Isles
    public static final RegistryObject<Block> MADNESS_DOOR = registerNoItem("madness_door",
            () -> new RealmDoorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(-1f, 3600000f)
                    .noOcclusion().lightLevel(s -> 6).sound(SoundType.WOOD), Realm.SHIVERING_ISLES));
    public static final RegistryObject<Block> MANIA_STEM = register("mania_stem",
            () -> new RotatedPillarBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).instrument(NoteBlockInstrument.BASS)
                    .strength(1.5f).sound(SoundType.STEM)));
    public static final RegistryObject<Block> MANIA_CAP_VIOLET = register("mania_cap_violet", () -> cap(MapColor.COLOR_PURPLE));
    public static final RegistryObject<Block> MANIA_CAP_TEAL = register("mania_cap_teal", () -> cap(MapColor.COLOR_CYAN));
    public static final RegistryObject<Block> MANIA_CAP_AMBER = register("mania_cap_amber", () -> cap(MapColor.COLOR_ORANGE));
    public static final RegistryObject<Block> MANIA_BLOOM = register("mania_bloom",
            () -> new GroundPlantBlock.Short(plant(MapColor.COLOR_MAGENTA).lightLevel(s -> 4), RealmBlocks::realmSoil));
    public static final RegistryObject<Block> DEMENTIA_BULB = register("dementia_bulb",
            () -> new GroundPlantBlock.Short(plant(MapColor.COLOR_GREEN).lightLevel(s -> 6), RealmBlocks::realmSoil));

    // ------------------------------------------------------------------------------------------ Apocrypha
    public static final RegistryObject<Block> PAGE_BLOCK = register("page_block",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(0.8f)
                    .sound(SoundType.WOOL).instrument(NoteBlockInstrument.BASS)));
    public static final RegistryObject<Block> BLACK_PAGE_BLOCK = register("black_page_block",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.8f)
                    .sound(SoundType.WOOL).instrument(NoteBlockInstrument.BASS)));
    public static final RegistryObject<Block> APOCRYPHA_STONE = register("apocrypha_stone",
            () -> new Block(stone(MapColor.COLOR_GREEN, 1.6f).sound(SoundType.DEEPSLATE)));
    public static final RegistryObject<Block> APOCRYPHA_GLYPH_STONE = register("apocrypha_glyph_stone",
            () -> new Block(stone(MapColor.COLOR_GREEN, 1.6f).sound(SoundType.DEEPSLATE).lightLevel(s -> 7)
                    .emissiveRendering((s, l, p) -> true)));
    public static final RegistryObject<Block> APOCRYPHA_TENTACLE = register("apocrypha_tentacle",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(1.2f)
                    .sound(SoundType.SLIME_BLOCK).instrument(NoteBlockInstrument.DIDGERIDOO)));
    public static final RegistryObject<Block> BLACK_BOOK = registerNoItem("black_book",
            () -> new BlackBookBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(-1f, 3600000f)
                    .noOcclusion().lightLevel(s -> 8).sound(SoundType.WOOD)));

    // ------------------------------------------------------------------------------------------ the Soul Cairn
    public static final RegistryObject<Block> SOUL_ASH = register("soul_ash",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(0.5f)
                    .sound(SoundType.SOUL_SAND).instrument(NoteBlockInstrument.SNARE)));
    public static final RegistryObject<Block> SOUL_STONE = register("soul_stone",
            () -> new Block(stone(MapColor.COLOR_PURPLE, 1.6f).sound(SoundType.DEEPSLATE)));
    public static final RegistryObject<Block> SOUL_GEM_BLOCK = register("soul_gem_block",
            () -> new Block(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(1.5f)
                    .sound(SoundType.AMETHYST).lightLevel(s -> 10).requiresCorrectToolForDrops()
                    .emissiveRendering((s, l, p) -> true)));
    public static final RegistryObject<Block> SOUL_GEM_CLUSTER = register("soul_gem_cluster",
            () -> new AmethystClusterBlock(7, 3, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE)
                    .forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER).strength(1.5f).lightLevel(s -> 7)
                    .pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<Block> SOUL_PORTAL = registerNoItem("soul_portal",
            () -> new RealmPortalBlock(portal(MapColor.COLOR_PURPLE, 12), Realm.SOUL_CAIRN, () -> ParticleTypes.SOUL));

    // ------------------------------------------------------------------------------------------ Coldharbour
    public static final RegistryObject<Block> COLDHARBOUR_STONE = register("coldharbour_stone",
            () -> new Block(stone(MapColor.COLOR_BLUE, 1.8f).sound(SoundType.DEEPSLATE)));
    public static final RegistryObject<Block> COLDHARBOUR_BRICKS = register("coldharbour_bricks",
            () -> new Block(stone(MapColor.COLOR_BLUE, 2.2f).sound(SoundType.DEEPSLATE_BRICKS)));
    public static final RegistryObject<Block> COLDHARBOUR_PORTAL = registerNoItem("coldharbour_portal",
            () -> new RealmPortalBlock(portal(MapColor.COLOR_BLUE, 11), Realm.COLDHARBOUR, () -> ParticleTypes.SOUL_FIRE_FLAME));

    private RealmBlocks() {}

    // ------------------------------------------------------------------------------------------ helpers

    private static BlockBehaviour.Properties stone(MapColor color, float hardness) {
        return BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(hardness, 6f);
    }

    private static BlockBehaviour.Properties plant(MapColor color) {
        return BlockBehaviour.Properties.of().mapColor(color).replaceable().noCollission().instabreak()
                .sound(SoundType.GRASS).offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties portal(MapColor color, int light) {
        return BlockBehaviour.Properties.of().mapColor(color).noCollission().noOcclusion().strength(-1f, 3600000f)
                .lightLevel(s -> light).noLootTable().pushReaction(PushReaction.BLOCK).sound(SoundType.GLASS);
    }

    private static Block cap(MapColor color) {
        return new Block(BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASS).strength(1.0f)
                .sound(SoundType.WART_BLOCK).lightLevel(s -> 11).emissiveRendering((s, l, p) -> true));
    }

    /** Where the realms' plants grow: soft soil, ash, Deadlands rock and soul ash. */
    public static boolean realmSoil(BlockState state) {
        return GroundPlantBlock.softSoil(state) || state.is(DEADLANDS_ASH.get()) || state.is(DEADLANDS_ROCK.get())
                || state.is(SOUL_ASH.get()) || state.is(APOCRYPHA_STONE.get()) || state.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK);
    }

    private static RegistryObject<Block> register(String id, Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        return block;
    }

    private static RegistryObject<Block> registerNoItem(String id, Supplier<Block> factory) {
        RegistryObject<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        return block;
    }
}
