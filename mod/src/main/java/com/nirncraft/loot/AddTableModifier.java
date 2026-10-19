package com.nirncraft.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nirncraft.NirnCraft;
import com.nirncraft.world.LocationTags;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

import java.util.function.Supplier;

/**
 * Global loot modifier {@code nirncraft:add_table}: rolls an extra loot table into every loot table whose id path
 * starts with {@code prefix} (default {@code "chests/"}), with the given chance. Modules use it to put Skyrim loot
 * (gold, spell tomes, soul gems, ingredients, gear) into dungeon and village chests, including modded structures.
 *
 * <pre>{ "type": "nirncraft:add_table", "conditions": [], "table": "nirncraft:chests/gold", "prefix": "chests/", "chance": 0.8 }</pre>
 */
public class AddTableModifier extends LootModifier {
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, NirnCraft.MODID);

    public static final Supplier<com.mojang.serialization.MapCodec<AddTableModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.mapCodec(inst -> codecStart(inst)
            .and(Identifier.CODEC.fieldOf("table").forGetter(m -> m.table))
            .and(Codec.STRING.optionalFieldOf("prefix", "chests/").forGetter(m -> m.prefix))
            .and(Codec.FLOAT.optionalFieldOf("chance", 1f).forGetter(m -> m.chance))
            .apply(inst, AddTableModifier::new)));

    public static final Supplier<com.mojang.serialization.MapCodec<AddTableModifier>> ADD_TABLE = SERIALIZERS.register("add_table", CODEC);

    private final Identifier table;
    private final String prefix;
    private final float chance;

    public AddTableModifier(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, int priority, Identifier table, String prefix, float chance) {
        super(conditions, priority);
        this.table = table;
        this.prefix = prefix;
        this.chance = chance;
    }

    public static void init(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    /** The one modifier (of the 11 rolling into the same chest) that is allowed to add lore books. */
    private static final Identifier LORE_TABLE = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "chests/lore_books");
    /** Lore books per dungeon chest on average. */
    private static final float DUNGEON_LORE = 0.6f;

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Identifier id = context.getQueriedLootTableId();
        if (id == null || id.equals(table) || !id.getPath().startsWith(prefix)) return generatedLoot;
        boolean village = inVillage(id, context);

        sanitize(generatedLoot, id, village);
        boolean lore = table.equals(LORE_TABLE);

        if (id.getNamespace().equals(NirnCraft.MODID)) {
            // NirnCraft's own chests (the containers/* taxonomy and its chests/* aliases) carry their own Skyrim loot,
            // lore books included
            return generatedLoot;
        }
        int before = generatedLoot.size();
        if (context.getRandom().nextFloat() <= chance) {
            LootTable extra = com.nirncraft.platform.LootTables.get(context.getLevel(), table);
            extra.getRandomItemsRaw(context, generatedLoot::add);
        }
        // dungeon chests elsewhere: top the lore table's chance up to ~DUNGEON_LORE books per chest
        if (lore && generatedLoot.size() == before && dungeonChest(id, context)) {
            float topUp = Math.max(0f, (DUNGEON_LORE - chance) / Math.max(0.01f, 1f - chance));
            if (context.getRandom().nextFloat() < topUp) addLoreBook(generatedLoot, context);
        }
        sanitize(generatedLoot, id, village);
        return generatedLoot;
    }

    private static void addLoreBook(ObjectArrayList<ItemStack> loot, LootContext context) {
        com.nirncraft.lore.LoreBooks.Book b = com.nirncraft.lore.LoreBooks.random(context.getRandom(), book -> true);
        if (b != null) loot.add(com.nirncraft.lore.LoreBookItem.create(b));
    }

    /** A chest in a dungeon: by loot table name, or because it stands inside a {@code #nirncraft:dungeons} structure. */
    private static boolean dungeonChest(Identifier id, LootContext context) {
        String path = id.getPath();
        if (path.contains("dungeon") || path.contains("stronghold") || path.contains("pyramid") || path.contains("barrow")
                || path.contains("temple") || path.contains("ruin") || path.contains("fort") || path.contains("crypt")
                || path.contains("lair") || path.contains("dwemer")) {
            return true;
        }
        return inStructure(context, LocationTags.DUNGEONS);
    }

    /** A chest in a town: by loot table name, or because it stands inside a {@code #nirncraft:towns} structure. */
    private static boolean inVillage(Identifier id, LootContext context) {
        return id.getPath().contains("village") || id.getPath().contains("farm") || inStructure(context, LocationTags.TOWNS);
    }

    private static boolean inStructure(LootContext context, TagKey<Structure> tag) {
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        return origin != null && LocationTags.pieceAt(context.getLevel(), BlockPos.containing(origin), tag);
    }

    private static void sanitize(ObjectArrayList<ItemStack> loot, Identifier id, boolean village) {
        loot.removeIf(stack -> {
            Item item = stack.getItem();
            if (item == Items.REDSTONE || item == Items.LAPIS_LAZULI || item == Items.EMERALD
                    || item == Items.MAP || item == Items.FILLED_MAP || item == Items.COMPASS) {
                return true;
            }
            if (village) {
                Identifier key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null && key.getNamespace().equals(NirnCraft.MODID)) {
                    String p = key.getPath();
                    if (p.startsWith("elven_") || p.startsWith("glass_") || p.startsWith("ebony_")
                            || p.startsWith("daedric_") || p.startsWith("dragon") || p.startsWith("orcish_")) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    @Override
    public com.mojang.serialization.MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
