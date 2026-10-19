package com.nirncraft.dungeons;

import net.minecraft.resources.ResourceKey;
import com.nirncraft.NirnCraft;
import com.nirncraft.dungeons.entity.DwarvenCenturion;
import com.nirncraft.dungeons.entity.DwarvenSphere;
import com.nirncraft.dungeons.entity.DwarvenSpider;
import com.nirncraft.dungeons.puzzle.Claw;
import com.nirncraft.dungeons.puzzle.ClawKeyholeBlock;
import com.nirncraft.dungeons.puzzle.DragonClawItem;
import com.nirncraft.dungeons.puzzle.PuzzleLeverBlock;
import com.nirncraft.dungeons.puzzle.SymbolStoneBlock;
import com.nirncraft.dungeons.world.DungeonPiece;
import com.nirncraft.dungeons.world.DungeonStructure;
import com.nirncraft.dungeons.world.SitesPlacement;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/** Registry objects of the dungeons module: the dungeon structure type and piece type, automatons and their eggs. */
public final class DungeonsRegistry {
    private DungeonsRegistry() {}

    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, NirnCraft.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, NirnCraft.MODID);

    public static final Supplier<StructureType<DungeonStructure>> DUNGEON_STRUCTURE =
            STRUCTURE_TYPES.register("dungeon", () -> () -> DungeonStructure.CODEC);
    public static final Supplier<StructurePieceType> DUNGEON_PIECE =
            PIECE_TYPES.register("dungeon_piece", () -> (StructurePieceType) DungeonPiece::new);

    /** {@code nirncraft:sites}: start chunks from a site list computed in Java (the 7,000 Steps, Blackreach tiles). */
    public static final DeferredRegister<MapCodec<? extends StructurePlacement>> PLACEMENTS = DeferredRegister.create(Registries.STRUCTURE_PLACEMENT, NirnCraft.MODID);
    public static final Supplier<MapCodec<? extends StructurePlacement>> SITES_PLACEMENT =
            PLACEMENTS.register("sites", () -> SitesPlacement.CODEC);

    public static final Supplier<EntityType<DwarvenSpider>> DWARVEN_SPIDER = ENTITIES.register("dwarven_spider",
            () -> EntityType.Builder.<DwarvenSpider>of(DwarvenSpider::new, MobCategory.MONSTER)
                    .sized(0.9f, 0.65f).fireImmune().clientTrackingRange(8).build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dwarven_spider"))));
    public static final Supplier<EntityType<DwarvenSphere>> DWARVEN_SPHERE = ENTITIES.register("dwarven_sphere",
            () -> EntityType.Builder.<DwarvenSphere>of(DwarvenSphere::new, MobCategory.MONSTER)
                    .sized(0.9f, 1.5f).fireImmune().clientTrackingRange(8).build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dwarven_sphere"))));
    public static final Supplier<EntityType<DwarvenCenturion>> DWARVEN_CENTURION = ENTITIES.register("dwarven_centurion",
            () -> EntityType.Builder.<DwarvenCenturion>of(DwarvenCenturion::new, MobCategory.MONSTER)
                    .sized(1.6f, 3.6f).fireImmune().clientTrackingRange(10).build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dwarven_centurion"))));

    public static final Supplier<Item> DWARVEN_SPIDER_EGG = ITEMS.register("dwarven_spider_spawn_egg",
            key -> new SpawnEggItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key)).spawnEgg(DWARVEN_SPIDER.get())));
    public static final Supplier<Item> DWARVEN_SPHERE_EGG = ITEMS.register("dwarven_sphere_spawn_egg",
            key -> new SpawnEggItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key)).spawnEgg(DWARVEN_SPHERE.get())));
    public static final Supplier<Item> DWARVEN_CENTURION_EGG = ITEMS.register("dwarven_centurion_spawn_egg",
            key -> new SpawnEggItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key)).spawnEgg(DWARVEN_CENTURION.get())));

    // ---- Nordic puzzles: glyph stones, claw doors and the dragon claws that open them
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, NirnCraft.MODID);

    private static BlockBehaviour.Properties puzzleStone() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1f, 3600000f).noLootTable()
                .sound(SoundType.STONE).pushReaction(PushReaction.IMMOVEABLE).isValidSpawn((s, l, p, t) -> false);
    }

    public static final Supplier<Block> SYMBOL_PILLAR = BLOCKS.register("symbol_pillar",
            key -> new SymbolStoneBlock(true, puzzleStone().setId(ResourceKey.create(Registries.BLOCK, key))));
    public static final Supplier<Block> SYMBOL_TABLET = BLOCKS.register("symbol_tablet",
            key -> new SymbolStoneBlock(false, puzzleStone().setId(ResourceKey.create(Registries.BLOCK, key))));
    public static final Supplier<Block> CLAW_KEYHOLE = BLOCKS.register("claw_keyhole",
            key -> new ClawKeyholeBlock(puzzleStone().setId(ResourceKey.create(Registries.BLOCK, key))));
    public static final Supplier<Block> PUZZLE_LEVER = BLOCKS.register("puzzle_lever",
            key -> new PuzzleLeverBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, key)).noCollision().strength(-1f, 3600000f).noLootTable()
                    .sound(SoundType.STONE).pushReaction(PushReaction.POPPED)));

    public static final Map<Claw, Supplier<Item>> CLAWS = new EnumMap<>(Claw.class);

    static {
        for (Claw claw : Claw.VALUES) {
            CLAWS.put(claw, ITEMS.register(claw.itemId(), key -> new DragonClawItem(claw, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, key)).stacksTo(1).rarity(Rarity.UNCOMMON))));
        }
    }

    /** {@code #nirncraft:automatons}: every Dwemer automaton (dungeons owns). */
    public static final TagKey<EntityType<?>> AUTOMATONS = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "automatons"));
    /** {@code #nirncraft:dungeon_bosses}: boss creatures this module adds (the Centurion). */
    public static final TagKey<EntityType<?>> DUNGEON_BOSSES = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(NirnCraft.MODID, "dungeon_bosses"));

    static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        PLACEMENTS.register(modBus);
        STRUCTURE_TYPES.register(modBus);
        PIECE_TYPES.register(modBus);
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }

    private static String id(String path) {
        return NirnCraft.MODID + ":" + path;
    }
}
