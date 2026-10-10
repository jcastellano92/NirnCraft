package com.skycraft.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.Skycraft;
import com.skycraft.world.LocationTags;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Global loot modifier {@code skycraft:add_table}: rolls an extra loot table into every loot table whose id path
 * starts with {@code prefix} (default {@code "chests/"}), with the given chance. Modules use it to put Skyrim loot
 * (gold, spell tomes, soul gems, ingredients, gear) into dungeon and village chests, including modded structures.
 *
 * <pre>{ "type": "skycraft:add_table", "conditions": [], "table": "skycraft:chests/gold", "prefix": "chests/", "chance": 0.8 }</pre>
 */
public class AddTableModifier extends LootModifier {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Skycraft.MODID);

    public static final Supplier<Codec<AddTableModifier>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.create(inst -> codecStart(inst)
            .and(ResourceLocation.CODEC.fieldOf("table").forGetter(m -> m.table))
            .and(Codec.STRING.optionalFieldOf("prefix", "chests/").forGetter(m -> m.prefix))
            .and(Codec.FLOAT.optionalFieldOf("chance", 1f).forGetter(m -> m.chance))
            .apply(inst, AddTableModifier::new)));

    public static final RegistryObject<Codec<AddTableModifier>> ADD_TABLE = SERIALIZERS.register("add_table", CODEC);

    private final ResourceLocation table;
    private final String prefix;
    private final float chance;

    public AddTableModifier(LootItemCondition[] conditions, ResourceLocation table, String prefix, float chance) {
        super(conditions);
        this.table = table;
        this.prefix = prefix;
        this.chance = chance;
    }

    public static void init(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    /** The one modifier (of the 11 rolling into the same chest) that is allowed to add lore books. */
    private static final ResourceLocation LORE_TABLE = new ResourceLocation(Skycraft.MODID, "chests/lore_books");
    /** Lore books per dungeon chest on average. */
    private static final float DUNGEON_LORE = 0.6f;

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        ResourceLocation id = context.getQueriedLootTableId();
        if (id == null || id.equals(table) || !id.getPath().startsWith(prefix)) return generatedLoot;
        boolean village = inVillage(id, context);

        sanitize(generatedLoot, id, village);
        boolean lore = table.equals(LORE_TABLE);

        if (id.getNamespace().equals(Skycraft.MODID) && id.getPath().startsWith("chests/")) {
            // Skycraft's own chests already carry Skyrim loot: only the lore modifier adds a book, once
            if (lore && dungeonChest(id, context) && context.getRandom().nextFloat() < DUNGEON_LORE) addLoreBook(generatedLoot, context);
            return generatedLoot;
        }
        int before = generatedLoot.size();
        if (context.getRandom().nextFloat() <= chance) {
            LootTable extra = context.getResolver().getLootTable(table);
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
        com.skycraft.lore.LoreBooks.Book b = com.skycraft.lore.LoreBooks.random(context.getRandom(), book -> true);
        if (b != null) loot.add(com.skycraft.lore.LoreBookItem.create(b));
    }

    /** A chest in a dungeon: by loot table name, or because it stands inside a {@code #skycraft:dungeons} structure. */
    private static boolean dungeonChest(ResourceLocation id, LootContext context) {
        String path = id.getPath();
        if (path.contains("dungeon") || path.contains("stronghold") || path.contains("pyramid") || path.contains("barrow")
                || path.contains("temple") || path.contains("ruin") || path.contains("fort") || path.contains("crypt")
                || path.contains("lair") || path.contains("dwemer")) {
            return true;
        }
        return inStructure(context, LocationTags.DUNGEONS);
    }

    /** A chest in a town: by loot table name, or because it stands inside a {@code #skycraft:towns} structure. */
    private static boolean inVillage(ResourceLocation id, LootContext context) {
        return id.getPath().contains("village") || id.getPath().contains("farm") || inStructure(context, LocationTags.TOWNS);
    }

    private static boolean inStructure(LootContext context, TagKey<Structure> tag) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        return origin != null && LocationTags.pieceAt(context.getLevel(), BlockPos.containing(origin), tag);
    }

    private static void sanitize(ObjectArrayList<ItemStack> loot, ResourceLocation id, boolean village) {
        loot.removeIf(stack -> {
            Item item = stack.getItem();
            if (item == Items.REDSTONE || item == Items.LAPIS_LAZULI || item == Items.EMERALD
                    || item == Items.MAP || item == Items.FILLED_MAP || item == Items.COMPASS) {
                return true;
            }
            if (village) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                if (key != null && key.getNamespace().equals(Skycraft.MODID)) {
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
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
