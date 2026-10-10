package com.skycraft.dungeons;

import com.skycraft.Skycraft;
import com.skycraft.dungeons.entity.DwarvenCenturion;
import com.skycraft.dungeons.entity.DwarvenSphere;
import com.skycraft.dungeons.entity.DwarvenSpider;
import com.skycraft.dungeons.puzzle.Claw;
import com.skycraft.dungeons.puzzle.ClawKeyholeBlock;
import com.skycraft.dungeons.puzzle.DragonClawItem;
import com.skycraft.dungeons.puzzle.PuzzleLeverBlock;
import com.skycraft.dungeons.puzzle.SymbolStoneBlock;
import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.DungeonStructure;
import com.skycraft.dungeons.world.SitesPlacement;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Registry objects of the dungeons module: the dungeon structure type and piece type, automatons and their eggs. */
public final class DungeonsRegistry {
    private DungeonsRegistry() {}

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, Skycraft.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, Skycraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, Skycraft.MODID);

    public static final RegistryObject<StructureType<DungeonStructure>> DUNGEON_STRUCTURE =
            STRUCTURE_TYPES.register("dungeon", () -> () -> DungeonStructure.CODEC);
    public static final RegistryObject<StructurePieceType> DUNGEON_PIECE =
            PIECE_TYPES.register("dungeon_piece", () -> (StructurePieceType) DungeonPiece::new);

    /** {@code skycraft:sites}: start chunks from a site list computed in Java (the 7,000 Steps, Blackreach tiles). */
    public static final DeferredRegister<StructurePlacementType<?>> PLACEMENTS = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, Skycraft.MODID);
    public static final RegistryObject<StructurePlacementType<SitesPlacement>> SITES_PLACEMENT =
            PLACEMENTS.register("sites", () -> () -> SitesPlacement.CODEC);

    public static final RegistryObject<EntityType<DwarvenSpider>> DWARVEN_SPIDER = ENTITIES.register("dwarven_spider",
            () -> EntityType.Builder.<DwarvenSpider>of(DwarvenSpider::new, MobCategory.MONSTER)
                    .sized(0.9f, 0.65f).fireImmune().clientTrackingRange(8).build(id("dwarven_spider")));
    public static final RegistryObject<EntityType<DwarvenSphere>> DWARVEN_SPHERE = ENTITIES.register("dwarven_sphere",
            () -> EntityType.Builder.<DwarvenSphere>of(DwarvenSphere::new, MobCategory.MONSTER)
                    .sized(0.9f, 1.5f).fireImmune().clientTrackingRange(8).build(id("dwarven_sphere")));
    public static final RegistryObject<EntityType<DwarvenCenturion>> DWARVEN_CENTURION = ENTITIES.register("dwarven_centurion",
            () -> EntityType.Builder.<DwarvenCenturion>of(DwarvenCenturion::new, MobCategory.MONSTER)
                    .sized(1.6f, 3.6f).fireImmune().clientTrackingRange(10).build(id("dwarven_centurion")));

    public static final RegistryObject<Item> DWARVEN_SPIDER_EGG = ITEMS.register("dwarven_spider_spawn_egg",
            () -> new ForgeSpawnEggItem(DWARVEN_SPIDER, 0xB0803A, 0x4FD8FF, new Item.Properties()));
    public static final RegistryObject<Item> DWARVEN_SPHERE_EGG = ITEMS.register("dwarven_sphere_spawn_egg",
            () -> new ForgeSpawnEggItem(DWARVEN_SPHERE, 0xA87432, 0x3A3A3A, new Item.Properties()));
    public static final RegistryObject<Item> DWARVEN_CENTURION_EGG = ITEMS.register("dwarven_centurion_spawn_egg",
            () -> new ForgeSpawnEggItem(DWARVEN_CENTURION, 0x8C5E2A, 0xFFB040, new Item.Properties()));

    // ---- Nordic puzzles: glyph stones, claw doors and the dragon claws that open them
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, Skycraft.MODID);

    private static BlockBehaviour.Properties puzzleStone() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1f, 3600000f).noLootTable()
                .sound(SoundType.STONE).pushReaction(PushReaction.BLOCK).isValidSpawn((s, l, p, t) -> false);
    }

    public static final RegistryObject<Block> SYMBOL_PILLAR = BLOCKS.register("symbol_pillar",
            () -> new SymbolStoneBlock(true, puzzleStone()));
    public static final RegistryObject<Block> SYMBOL_TABLET = BLOCKS.register("symbol_tablet",
            () -> new SymbolStoneBlock(false, puzzleStone()));
    public static final RegistryObject<Block> CLAW_KEYHOLE = BLOCKS.register("claw_keyhole",
            () -> new ClawKeyholeBlock(puzzleStone()));
    public static final RegistryObject<Block> PUZZLE_LEVER = BLOCKS.register("puzzle_lever",
            () -> new PuzzleLeverBlock(BlockBehaviour.Properties.of().noCollission().strength(-1f, 3600000f).noLootTable()
                    .sound(SoundType.STONE).pushReaction(PushReaction.DESTROY)));

    public static final Map<Claw, RegistryObject<Item>> CLAWS = new EnumMap<>(Claw.class);

    static {
        for (Claw claw : Claw.VALUES) {
            CLAWS.put(claw, ITEMS.register(claw.itemId(), () -> new DragonClawItem(claw, new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON))));
        }
    }

    /** {@code #skycraft:automatons}: every Dwemer automaton (dungeons owns). */
    public static final TagKey<EntityType<?>> AUTOMATONS = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "automatons"));
    /** {@code #skycraft:dungeon_bosses}: boss creatures this module adds (the Centurion). */
    public static final TagKey<EntityType<?>> DUNGEON_BOSSES = TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(Skycraft.MODID, "dungeon_bosses"));

    static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        PLACEMENTS.register(modBus);
        STRUCTURE_TYPES.register(modBus);
        PIECE_TYPES.register(modBus);
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    private static String id(String path) {
        return Skycraft.MODID + ":" + path;
    }
}
