package com.nirncraft.loot.container;

import com.nirncraft.NirnCraft;
import com.nirncraft.platform.SkyProperties;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Blocks, items and the block entity of NirnCraft's loot containers, and the registry aliases that let a world keep its
 * Lootr containers after Lootr is removed ({@link LootrCompat}).
 */
public final class ContainerRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, NirnCraft.MODID);

    public static final Map<ContainerKind, Supplier<Block>> BLOCK = new EnumMap<>(ContainerKind.class);
    public static final Map<ContainerKind, Supplier<Item>> ITEM = new EnumMap<>(ContainerKind.class);

    static {
        for (ContainerKind k : ContainerKind.VALUES) {
            BLOCK.put(k, BLOCKS.register(k.id, () -> create(k)));
            ITEM.put(k, ITEMS.register(k.id, () -> new BlockItem(BLOCK.get(k).get(), SkyProperties.item(k.id).useBlockDescriptionPrefix())));
        }
    }

    public static final Supplier<BlockEntityType<LootContainerBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register("loot_container",
            () -> new BlockEntityType<>(LootContainerBlockEntity::new, BLOCK.values().stream().map(Supplier::get).toArray(Block[]::new)));

    private ContainerRegistry() {}

    private static Block create(ContainerKind k) {
        BlockBehaviour.Properties p = SkyProperties.block(k.id).strength(2.5f, 1200f).pushReaction(PushReaction.IMMOVEABLE);
        switch (k) {
            case STRONGBOX -> p.mapColor(MapColor.METAL).sound(SoundType.METAL).noOcclusion();
            case SACK -> p.mapColor(MapColor.WOOL).sound(SoundType.WOOL).noOcclusion().strength(0.8f, 1200f);
            case URN -> p.mapColor(MapColor.TERRACOTTA_BROWN).sound(SoundType.DECORATED_POT).noOcclusion().strength(1.0f, 1200f);
            case REMAINS -> p.mapColor(MapColor.SAND).sound(SoundType.BONE_BLOCK).noOcclusion().noCollision().strength(1.0f, 1200f);
            case BARREL, BOOKSHELF -> p.mapColor(MapColor.WOOD).sound(SoundType.WOOD);
            default -> p.mapColor(MapColor.WOOD).sound(SoundType.WOOD).noOcclusion();
        }
        return switch (k) {
            case BARREL -> new LootContainerBlock.Barrel(k, p);
            case BOOKSHELF -> new LootContainerBlock.Bookshelf(k, p);
            default -> k.styled ? new LootContainerBlock.Styled(k, p) : new LootContainerBlock(k, p);
        };
    }

    public static Block block(ContainerKind k) {
        return BLOCK.get(k).get();
    }

    public static void init(IEventBus modBus) {
        if (!LootrCompat.present()) {
            LootrCompat.addAliases(BLOCKS, BLOCK_ENTITIES);
            LootrCompat.addItemAliases(ITEMS);
        }
        LootrCompat.init(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(ContainerRegistry::creativeTab);
    }

    /** The main tab lists each container once; the styled ones get a stack per style (a block-state component). */
    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() != com.nirncraft.registry.ModCreativeTab.MAIN.get()) return;
        for (ContainerKind k : ContainerKind.VALUES) {
            if (!k.styled) continue;
            for (ContainerStyle s : ContainerStyle.VALUES) {
                if (s == ContainerStyle.NORDIC) continue; // the plain stack already is the default style
                ItemStack stack = new ItemStack(ITEM.get(k).get());
                stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(LootContainerBlock.STYLE, s));
                event.accept(stack, net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
            }
        }
    }

    static Identifier id(ContainerKind k) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, k.id);
    }
}
