package com.skycraft.lore;

import com.skycraft.Skycraft;
import com.skycraft.registry.ModCreativeTab;
import com.skycraft.skills.Progression;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * Standing Stones (Guardian Stones), readable Skyrim-style books and notes.
 *
 * <ul>
 *     <li>{@link StandingStone} / {@link StandingStoneBlock} / {@link StandingStones}: the thirteen signs, their
 *     blocks, blessings and daily powers (key {@code key.skycraft.stone_power}).</li>
 *     <li>{@link StandingStoneShrineFeature}: world generation ({@code skycraft:standing_stone_shrine}).</li>
 *     <li>{@link LoreBooks} / {@link LoreBookItem}: {@code skycraft:book} with NBT {@code book}; texts in
 *     {@code assets/skycraft/lore/books/<id>.json}, read with {@code client.BookScreen}.</li>
 *     <li>{@link LoreEvents}: browsing bookshelves.</li>
 * </ul>
 */
public final class LoreModule {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, Skycraft.MODID);

    public static final Map<StandingStone, RegistryObject<Block>> STONES = new EnumMap<>(StandingStone.class);

    static {
        for (StandingStone sign : StandingStone.VALUES) {
            RegistryObject<Block> block = BLOCKS.register("standing_stone_" + sign.id(), () -> new StandingStoneBlock(sign,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(-1f, 3600000f)
                            .noLootTable()
                            .noOcclusion()
                            .lightLevel(state -> 7)
                            .sound(SoundType.STONE)
                            .pushReaction(PushReaction.BLOCK)
                            .isValidSpawn((state, level, pos, type) -> false)));
            STONES.put(sign, block);
            ITEMS.register("standing_stone_" + sign.id(), () -> new BlockItem(block.get(), new Item.Properties().rarity(Rarity.EPIC)));
        }
    }

    /** Solstheim's All-Maker stones ({@link AllMakerStones}). */
    public static final Map<AllMakerStone, RegistryObject<Block>> ALL_MAKER_STONES = new EnumMap<>(AllMakerStone.class);

    static {
        for (AllMakerStone stone : AllMakerStone.VALUES) {
            String id = "standing_stone_allmaker_" + stone.id();
            RegistryObject<Block> block = BLOCKS.register(id, () -> new AllMakerStoneBlock(stone,
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(-1f, 3600000f)
                            .noLootTable()
                            .noOcclusion()
                            .lightLevel(state -> 7)
                            .sound(SoundType.STONE)
                            .pushReaction(PushReaction.BLOCK)
                            .isValidSpawn((state, level, pos, type) -> false)));
            ALL_MAKER_STONES.put(stone, block);
            ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties().rarity(Rarity.EPIC)));
        }
    }

    public static final RegistryObject<Item> BOOK =ITEMS.register("book", () -> new LoreBookItem(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> STANDING_STONE_SHRINE = FEATURES.register("standing_stone_shrine",
            () -> new StandingStoneShrineFeature(NoneFeatureConfiguration.CODEC));

    private LoreModule() {}

    /** Registers this module's DeferredRegisters and mod-bus listeners. Called from the mod constructor. */
    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        FEATURES.register(modBus);
        modBus.addListener(LoreModule::creativeTab);
        Progression.registerXpModifier(StandingStones::xpMultiplier);
    }

    /** Registers this module's packets via {@link com.skycraft.network.SkyNetwork#register}. Called once at startup. */
    public static void registerPackets() {
        LorePackets.register();
    }

    /** Every book (with its NBT) in the Skycraft creative tab; the tab itself already lists the plain item. */
    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(ModCreativeTab.MAIN.getKey())) return;
        for (LoreBooks.Book book : LoreBooks.all()) event.accept(LoreBookItem.create(book));
    }
}
