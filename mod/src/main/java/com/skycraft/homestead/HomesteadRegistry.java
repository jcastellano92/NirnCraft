package com.skycraft.homestead;

import com.skycraft.Skycraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * Hearthfire building materials (sawn logs, quarried stone, nails, hinges, locks, iron fittings) and the stone and
 * clay deposits that come with a purchased plot. The drafting table itself is registered with the crafting stations.
 */
public final class HomesteadRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(BuiltInRegistries.BLOCK, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(BuiltInRegistries.ITEM, Skycraft.MODID);

    public static final Supplier<Item> SAWN_LOG = ITEMS.register("sawn_log", () -> new Item(com.skycraft.platform.SkyProperties.item("sawn_log")));
    public static final Supplier<Item> QUARRIED_STONE = ITEMS.register("quarried_stone", () -> new Item(com.skycraft.platform.SkyProperties.item("quarried_stone")));
    public static final Supplier<Item> NAILS = ITEMS.register("nails", () -> new Item(com.skycraft.platform.SkyProperties.item("nails")));
    public static final Supplier<Item> HINGE = ITEMS.register("hinge", () -> new Item(com.skycraft.platform.SkyProperties.item("hinge")));
    public static final Supplier<Item> LOCK = ITEMS.register("lock", () -> new Item(com.skycraft.platform.SkyProperties.item("lock")));
    public static final Supplier<Item> IRON_FITTINGS = ITEMS.register("iron_fittings", () -> new Item(com.skycraft.platform.SkyProperties.item("iron_fittings")));

    public static final Supplier<Block> STONE_DEPOSIT = BLOCKS.register("stone_deposit",
            () -> new DepositBlock(DepositBlock.Kind.STONE, com.skycraft.platform.SkyProperties.block("stone_deposit").mapColor(MapColor.STONE)
                    .strength(-1.0f, 3600000.0f).sound(SoundType.STONE).noLootTable()));
    public static final Supplier<Block> CLAY_DEPOSIT = BLOCKS.register("clay_deposit",
            () -> new DepositBlock(DepositBlock.Kind.CLAY, com.skycraft.platform.SkyProperties.block("clay_deposit").mapColor(MapColor.CLAY)
                    .strength(-1.0f, 3600000.0f).sound(SoundType.GRAVEL).noLootTable()));
    public static final Supplier<Item> STONE_DEPOSIT_ITEM = ITEMS.register("stone_deposit",
            () -> new BlockItem(STONE_DEPOSIT.get(), com.skycraft.platform.SkyProperties.item("stone_deposit")));
    public static final Supplier<Item> CLAY_DEPOSIT_ITEM = ITEMS.register("clay_deposit",
            () -> new BlockItem(CLAY_DEPOSIT.get(), com.skycraft.platform.SkyProperties.item("clay_deposit")));

    private HomesteadRegistry() {}

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        HomesteadSelfTest.registerAutorun();
    }
}
