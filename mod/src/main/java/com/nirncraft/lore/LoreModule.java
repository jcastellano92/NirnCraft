package com.nirncraft.lore;

import com.nirncraft.NirnCraft;
import com.nirncraft.registry.ModCreativeTab;
import com.nirncraft.skills.Progression;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.nirncraft.worldgen.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Standing Stones (Guardian Stones), readable Skyrim-style books and notes.
 *
 * <ul>
 *     <li>{@link StandingStone} / {@link StandingStoneBlock} / {@link StandingStones}: the thirteen signs, their
 *     blocks, blessings and daily powers (key {@code key.nirncraft.stone_power}).</li>
 *     <li>{@link StandingStoneShrineFeature}: world generation ({@code nirncraft:standing_stone_shrine}).</li>
 *     <li>{@link LoreBooks} / {@link LoreBookItem}: {@code nirncraft:book} with NBT {@code book}; texts in
 *     {@code assets/nirncraft/lore/books/<id>.json}, read with {@code client.BookScreen}.</li>
 *     <li>{@link LoreEvents}: browsing bookshelves.</li>
 * </ul>
 */
public final class LoreModule {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, NirnCraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, NirnCraft.MODID);
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends Feature>> FEATURES = DeferredRegister.create(BuiltInRegistries.FEATURE_TYPE, NirnCraft.MODID);

    public static final Map<StandingStone, Supplier<Block>> STONES = new EnumMap<>(StandingStone.class);

    static {
        for (StandingStone sign : StandingStone.VALUES) {
            Supplier<Block> block = BLOCKS.register("standing_stone_" + sign.id(), key -> new StandingStoneBlock(sign,
                    BlockBehaviour.Properties.of().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,key))
                            .mapColor(MapColor.STONE)
                            .strength(-1f, 3600000f)
                            .noLootTable()
                            .noOcclusion()
                            .lightLevel(state -> 7)
                            .sound(SoundType.STONE)
                            .pushReaction(PushReaction.IMMOVEABLE)
                            .isValidSpawn((state, level, pos, type) -> false)));
            STONES.put(sign, block);
            ITEMS.register("standing_stone_" + sign.id(), key -> new BlockItem(block.get(), new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,key)).rarity(Rarity.EPIC)));
        }
    }

    /** Solstheim's All-Maker stones ({@link AllMakerStones}). */
    public static final Map<AllMakerStone, Supplier<Block>> ALL_MAKER_STONES = new EnumMap<>(AllMakerStone.class);

    static {
        for (AllMakerStone stone : AllMakerStone.VALUES) {
            String id = "standing_stone_allmaker_" + stone.id();
            Supplier<Block> block = BLOCKS.register(id, key -> new AllMakerStoneBlock(stone,
                    BlockBehaviour.Properties.of().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,key))
                            .mapColor(MapColor.STONE)
                            .strength(-1f, 3600000f)
                            .noLootTable()
                            .noOcclusion()
                            .lightLevel(state -> 7)
                            .sound(SoundType.STONE)
                            .pushReaction(PushReaction.IMMOVEABLE)
                            .isValidSpawn((state, level, pos, type) -> false)));
            ALL_MAKER_STONES.put(stone, block);
            ITEMS.register(id, key -> new BlockItem(block.get(), new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,key)).rarity(Rarity.EPIC)));
        }
    }

    public static final Supplier<Item> BOOK =ITEMS.register("book", key -> new LoreBookItem(new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,key)).stacksTo(16)));

    public static final Supplier<com.mojang.serialization.MapCodec<? extends Feature>> STANDING_STONE_SHRINE = FEATURES.register("standing_stone_shrine",
            () -> new StandingStoneShrineFeature(NoneFeatureConfiguration.CODEC).codec());

    private LoreModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener(LoreModule::creativeTab);
        Progression.registerXpModifier(StandingStones::xpMultiplier);
        com.nirncraft.lore.place.PlaceLoreModule.init(modBus);
    }

    /** Registers this module's packets via {@link com.nirncraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        LorePackets.register();
    }

    /** Every book (with its NBT) in the NirnCraft creative tab; the tab itself already lists the plain item. */
    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getResourceKey(ModCreativeTab.MAIN.get()).orElseThrow())) return;
        for (LoreBooks.Book book : LoreBooks.all()) event.accept(LoreBookItem.create(book));
    }
}
