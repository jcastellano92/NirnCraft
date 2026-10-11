package com.skycraft.homestead;

import com.skycraft.Skycraft;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Hearthfire building materials (sawn logs, quarried stone, nails, hinges, locks, iron fittings) and the stone and
 * clay deposits that come with a purchased plot. The drafting table itself is registered with the crafting stations.
 */
public final class HomesteadRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Skycraft.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Skycraft.MODID);

    public static final RegistryObject<Item> SAWN_LOG = ITEMS.register("sawn_log", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> QUARRIED_STONE = ITEMS.register("quarried_stone", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> NAILS = ITEMS.register("nails", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> HINGE = ITEMS.register("hinge", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> LOCK = ITEMS.register("lock", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> IRON_FITTINGS = ITEMS.register("iron_fittings", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Block> STONE_DEPOSIT = BLOCKS.register("stone_deposit",
            () -> new DepositBlock(DepositBlock.Kind.STONE, BlockBehaviour.Properties.of().mapColor(MapColor.STONE)
                    .strength(-1.0f, 3600000.0f).sound(SoundType.STONE).noLootTable()));
    public static final RegistryObject<Block> CLAY_DEPOSIT = BLOCKS.register("clay_deposit",
            () -> new DepositBlock(DepositBlock.Kind.CLAY, BlockBehaviour.Properties.of().mapColor(MapColor.CLAY)
                    .strength(-1.0f, 3600000.0f).sound(SoundType.GRAVEL).noLootTable()));
    public static final RegistryObject<Item> STONE_DEPOSIT_ITEM = ITEMS.register("stone_deposit",
            () -> new BlockItem(STONE_DEPOSIT.get(), new Item.Properties()));
    public static final RegistryObject<Item> CLAY_DEPOSIT_ITEM = ITEMS.register("clay_deposit",
            () -> new BlockItem(CLAY_DEPOSIT.get(), new Item.Properties()));

    private HomesteadRegistry() {}

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}
