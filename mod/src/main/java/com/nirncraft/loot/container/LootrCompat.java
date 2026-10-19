package com.nirncraft.loot.container;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Living with Lootr, and after it (docs/CONTAINERS.md section 11). This is the only class that knows Lootr's ids.
 *
 * <ul>
 *     <li><b>While Lootr is installed</b> nothing here registers anything: Lootr's blocks are Lootr's. The converter
 *     ({@link LootContainers}) may turn Lootr containers into ours when {@code convertExisting} is on; it reads only the
 *     loot table, which Lootr keeps in the vanilla {@code LootTable} field.</li>
 *     <li><b>After Lootr is removed</b> its block, block-entity, item and entity ids are registry aliases of ours (or of
 *     the vanilla thing they wrapped): a chunk saved with a Lootr chest loads it as a NirnCraft chest whose block entity
 *     reads the same {@code LootTable}, and fixes its kind and style on the next tick. Nothing turns into air.
 *     Lootr's per-player copies lived in Lootr's own files and are not read: after the switch every player rolls
 *     the container afresh (at their level), once.</li>
 * </ul>
 */
public final class LootrCompat {
    public static final String MODID = "lootr";
    private static Boolean present;
    private static final DeferredRegister<EntityType<?>> ENTITY_ALIASES = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, NirnCraft.MODID);

    private LootrCompat() {}

    public static boolean present() {
        if (present == null) present = ContainersConfig.lootrLoaded();
        return present;
    }

    /** True for a block of Lootr's (only ever true while Lootr is installed). */
    public static boolean isLootrBlock(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && MODID.equals(id.getNamespace());
    }

    private static Identifier lootr(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    private static Identifier ours(String path) {
        return Identifier.fromNamespaceAndPath(NirnCraft.MODID, path);
    }

    /** Lootr's ids as aliases (called at mod construction, only when Lootr is absent). */
    static void addAliases(DeferredRegister<Block> blocks, DeferredRegister<BlockEntityType<?>> blockEntities) {
        String chest = ContainerKind.CHEST.id;
        for (String p : new String[]{"chest", "trapped_chest", "inventory", "shulker_box", "copper_chest", "exposed_copper_chest",
                "weathered_copper_chest", "oxidized_copper_chest"}) {
            blocks.addAlias(lootr(p), ours(chest));
            blockEntities.addAlias(lootr(p), ours("loot_container"));
        }
        blocks.addAlias(lootr("barrel"), ours(ContainerKind.BARREL.id));
        blockEntities.addAlias(lootr("barrel"), ours("loot_container"));
        blocks.addAlias(lootr("decorated_pot"), ours(ContainerKind.URN.id));
        blockEntities.addAlias(lootr("decorated_pot"), ours("loot_container"));
        // brushable blocks were vanilla ones with a per-player roll: they go back to being vanilla
        blocks.addAlias(lootr("suspicious_sand"), Identifier.withDefaultNamespace("suspicious_sand"));
        blocks.addAlias(lootr("suspicious_gravel"), Identifier.withDefaultNamespace("suspicious_gravel"));
        blockEntities.addAlias(lootr("brushable_block"), Identifier.withDefaultNamespace("brushable_block"));
        ENTITY_ALIASES.addAlias(lootr("chest_minecart"), Identifier.withDefaultNamespace("chest_minecart"));
        ENTITY_ALIASES.addAlias(lootr("item_frame"), Identifier.withDefaultNamespace("item_frame"));
    }

    /** Lootr's block items, in inventories and chests, become ours too. */
    static void addItemAliases(DeferredRegister<net.minecraft.world.item.Item> items) {
        for (String p : new String[]{"chest", "trapped_chest", "inventory", "shulker_box"}) items.addAlias(lootr(p), ours(ContainerKind.CHEST.id));
        items.addAlias(lootr("barrel"), ours(ContainerKind.BARREL.id));
    }

    static void init(IEventBus modBus) {
        if (!present()) ENTITY_ALIASES.register(modBus);
    }
}
