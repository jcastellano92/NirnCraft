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
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import java.util.function.Supplier;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The blocks of the realms: Deadlands rock, ash, bloodgrass and harrada, Daedric masonry and the Sigil Stone;
 * the Shivering Isles' giant mushroom caps and blooms; Apocrypha's pages, glyph stone and tentacles; the Soul
 * Cairn's soul ash and soul-gem crystal; Coldharbour's stone; and every realm's gate. Textures come from
 * tools/textures/realms.py. Gates, doors, the Sigil Stone and the Black Books have no item (structures place them).
 */
public final class RealmBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    /** Every block of this module in registration order. */
    public static final Map<String, Supplier<Block>> ALL = new LinkedHashMap<>();

    // ------------------------------------------------------------------------------------------ the Deadlands
    public static final Supplier<Block> DEADLANDS_ROCK = register("deadlands_rock",
            key -> new Block(stone(key, MapColor.COLOR_RED, 1.6f).sound(SoundType.BASALT)));
    public static final Supplier<Block> DEADLANDS_MAGMA_ROCK = register("deadlands_magma_rock",
            key -> new MagmaBlock(stone(key, MapColor.NETHER, 1.6f).sound(SoundType.BASALT).lightLevel(s -> 5)
                    .isValidSpawn((s, l, p, e) -> e.fireImmune()).postProcess((s, l, p) -> p)
                    .emissiveRendering(s -> true)));
    public static final Supplier<Block> DEADLANDS_ASH = register("deadlands_ash",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_GRAY).strength(0.5f)
                    .sound(SoundType.SAND).instrument(NoteBlockInstrument.SNARE)));
    public static final Supplier<Block> BLOODGRASS = register("bloodgrass",
            key -> new GroundPlantBlock.Short(plant(key, MapColor.COLOR_RED), RealmBlocks::realmSoil));
    public static final Supplier<Block> HARRADA = register("harrada",
            key -> new HarradaBlock(plant(key, MapColor.COLOR_RED).sound(SoundType.WEEPING_VINES), RealmBlocks::realmSoil));
    public static final Supplier<Block> DAEDRIC_BRICKS = register("daedric_bricks",
            key -> new Block(stone(key, MapColor.COLOR_BLACK, 2.5f).sound(SoundType.NETHER_BRICKS)));
    public static final Supplier<Block> DAEDRIC_GLYPH_BRICKS = register("daedric_glyph_bricks",
            key -> new Block(stone(key, MapColor.COLOR_BLACK, 2.5f).sound(SoundType.NETHER_BRICKS).lightLevel(s -> 7)
                    .emissiveRendering(s -> true)));
    public static final Supplier<Block> DAEDRIC_PILLAR = register("daedric_pillar",
            key -> new RotatedPillarBlock(stone(key, MapColor.COLOR_BLACK, 2.5f).sound(SoundType.NETHER_BRICKS)));
    public static final Supplier<Block> DAEDRIC_SPIKE = register("daedric_spike",
            key -> new SpikeBlock(stone(key, MapColor.COLOR_BLACK, 2.0f).sound(SoundType.NETHER_BRICKS).noOcclusion()));
    public static final Supplier<Block> SIGIL_STONE = registerNoItem("sigil_stone",
            key -> new SigilStoneBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_ORANGE).strength(-1f, 3600000f)
                    .lightLevel(s -> 15).noOcclusion().sound(SoundType.AMETHYST).emissiveRendering(s -> true)));
    public static final Supplier<Block> SIGIL_FLAME = registerNoItem("sigil_flame",
            key -> new SigilFlameBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.FIRE).noCollision().noOcclusion()
                    .strength(-1f, 3600000f).lightLevel(s -> 12).noLootTable().pushReaction(PushReaction.IMMOVEABLE)
                    .sound(SoundType.WOOL).replaceable()));
    public static final Supplier<Block> OBLIVION_PORTAL = registerNoItem("oblivion_portal",
            key -> new RealmPortalBlock(portal(key, MapColor.FIRE, 13), Realm.DEADLANDS, () -> ParticleTypes.FLAME));

    // ------------------------------------------------------------------------------------------ Sovngarde
    public static final Supplier<Block> VALOR_PORTAL = registerNoItem("valor_portal",
            key -> new RealmPortalBlock(portal(key, MapColor.GOLD, 14), Realm.SOVNGARDE, () -> ParticleTypes.END_ROD));

    // ------------------------------------------------------------------------------------------ the Shivering Isles
    public static final Supplier<Block> MADNESS_DOOR = registerNoItem("madness_door",
            key -> new RealmDoorBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_PURPLE).strength(-1f, 3600000f)
                    .noOcclusion().lightLevel(s -> 6).sound(SoundType.WOOD), Realm.SHIVERING_ISLES));
    public static final Supplier<Block> MANIA_STEM = register("mania_stem",
            key -> new RotatedPillarBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.WOOL).instrument(NoteBlockInstrument.BASS)
                    .strength(1.5f).sound(SoundType.STEM)));
    public static final Supplier<Block> MANIA_CAP_VIOLET = register("mania_cap_violet", key -> cap(key, MapColor.COLOR_PURPLE));
    public static final Supplier<Block> MANIA_CAP_TEAL = register("mania_cap_teal", key -> cap(key, MapColor.COLOR_CYAN));
    public static final Supplier<Block> MANIA_CAP_AMBER = register("mania_cap_amber", key -> cap(key, MapColor.COLOR_ORANGE));
    public static final Supplier<Block> MANIA_BLOOM = register("mania_bloom",
            key -> new GroundPlantBlock.Short(plant(key, MapColor.COLOR_MAGENTA).lightLevel(s -> 4), RealmBlocks::realmSoil));
    public static final Supplier<Block> DEMENTIA_BULB = register("dementia_bulb",
            key -> new GroundPlantBlock.Short(plant(key, MapColor.COLOR_GREEN).lightLevel(s -> 6), RealmBlocks::realmSoil));

    // ------------------------------------------------------------------------------------------ Apocrypha
    public static final Supplier<Block> PAGE_BLOCK = register("page_block",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.TERRACOTTA_WHITE).strength(0.8f)
                    .sound(SoundType.WOOL).instrument(NoteBlockInstrument.BASS)));
    public static final Supplier<Block> BLACK_PAGE_BLOCK = register("black_page_block",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_BLACK).strength(0.8f)
                    .sound(SoundType.WOOL).instrument(NoteBlockInstrument.BASS)));
    public static final Supplier<Block> APOCRYPHA_STONE = register("apocrypha_stone",
            key -> new Block(stone(key, MapColor.COLOR_GREEN, 1.6f).sound(SoundType.DEEPSLATE)));
    public static final Supplier<Block> APOCRYPHA_GLYPH_STONE = register("apocrypha_glyph_stone",
            key -> new Block(stone(key, MapColor.COLOR_GREEN, 1.6f).sound(SoundType.DEEPSLATE).lightLevel(s -> 7)
                    .emissiveRendering(s -> true)));
    public static final Supplier<Block> APOCRYPHA_TENTACLE = register("apocrypha_tentacle",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_GREEN).strength(1.2f)
                    .sound(SoundType.SLIME_BLOCK).instrument(NoteBlockInstrument.DIDGERIDOO)));
    public static final Supplier<Block> BLACK_BOOK = registerNoItem("black_book",
            key -> new BlackBookBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_BLACK).strength(-1f, 3600000f)
                    .noOcclusion().lightLevel(s -> 8).sound(SoundType.WOOD)));

    // ------------------------------------------------------------------------------------------ the Soul Cairn
    public static final Supplier<Block> SOUL_ASH = register("soul_ash",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_PURPLE).strength(0.5f)
                    .sound(SoundType.SOUL_SAND).instrument(NoteBlockInstrument.SNARE)));
    public static final Supplier<Block> SOUL_STONE = register("soul_stone",
            key -> new Block(stone(key, MapColor.COLOR_PURPLE, 1.6f).sound(SoundType.DEEPSLATE)));
    public static final Supplier<Block> SOUL_GEM_BLOCK = register("soul_gem_block",
            key -> new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_PURPLE).strength(1.5f)
                    .sound(SoundType.AMETHYST).lightLevel(s -> 10).requiresCorrectToolForDrops()
                    .emissiveRendering(s -> true)));
    public static final Supplier<Block> SOUL_GEM_CLUSTER = register("soul_gem_cluster",
            key -> new AmethystClusterBlock(7, 3, BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(MapColor.COLOR_PURPLE)
                    .forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER).strength(1.5f).lightLevel(s -> 7)
                    .pushReaction(PushReaction.POPPED)));
    public static final Supplier<Block> SOUL_PORTAL = registerNoItem("soul_portal",
            key -> new RealmPortalBlock(portal(key, MapColor.COLOR_PURPLE, 12), Realm.SOUL_CAIRN, () -> ParticleTypes.SOUL));

    // ------------------------------------------------------------------------------------------ Coldharbour
    public static final Supplier<Block> COLDHARBOUR_STONE = register("coldharbour_stone",
            key -> new Block(stone(key, MapColor.COLOR_BLUE, 1.8f).sound(SoundType.DEEPSLATE)));
    public static final Supplier<Block> COLDHARBOUR_BRICKS = register("coldharbour_bricks",
            key -> new Block(stone(key, MapColor.COLOR_BLUE, 2.2f).sound(SoundType.DEEPSLATE_BRICKS)));
    public static final Supplier<Block> COLDHARBOUR_PORTAL = registerNoItem("coldharbour_portal",
            key -> new RealmPortalBlock(portal(key, MapColor.COLOR_BLUE, 11), Realm.COLDHARBOUR, () -> ParticleTypes.SOUL_FIRE_FLAME));

    private RealmBlocks() {}

    // ------------------------------------------------------------------------------------------ helpers

    private static BlockBehaviour.Properties stone(Identifier key, MapColor color, float hardness) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops().strength(hardness, 6f);
    }

    private static BlockBehaviour.Properties plant(Identifier key, MapColor color) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).replaceable().noCollision().instabreak()
                .sound(SoundType.GRASS).offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.POPPED);
    }

    private static BlockBehaviour.Properties portal(Identifier key, MapColor color, int light) {
        return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).noCollision().noOcclusion().strength(-1f, 3600000f)
                .lightLevel(s -> light).noLootTable().pushReaction(PushReaction.IMMOVEABLE).sound(SoundType.GLASS);
    }

    private static Block cap(Identifier key, MapColor color) {
        return new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).mapColor(color).instrument(NoteBlockInstrument.BASS).strength(1.0f)
                .sound(SoundType.WART_BLOCK).lightLevel(s -> 11).emissiveRendering(s -> true));
    }

    /** Where the realms' plants grow: soft soil, ash, Deadlands rock and soul ash. */
    public static boolean realmSoil(BlockState state) {
        return GroundPlantBlock.softSoil(state) || state.is(DEADLANDS_ASH.get()) || state.is(DEADLANDS_ROCK.get())
                || state.is(SOUL_ASH.get()) || state.is(APOCRYPHA_STONE.get()) || state.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK);
    }

    private static Supplier<Block> register(String id, Function<Identifier, Block> factory) {
        Supplier<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        ITEMS.register(id, key -> new BlockItem(block.get(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key)).useBlockDescriptionPrefix()));
        return block;
    }

    private static Supplier<Block> registerNoItem(String id, Function<Identifier, Block> factory) {
        Supplier<Block> block = BLOCKS.register(id, factory);
        ALL.put(id, block);
        return block;
    }
}
