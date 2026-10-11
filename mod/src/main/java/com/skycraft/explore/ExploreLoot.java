package com.skycraft.explore;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * Loot of the minor sites ({@code data/skycraft/loot_tables/explore/*}) and two loot functions:
 * <ul>
 *     <li>{@code skycraft:explore_treasure_map}: turns the stack into a treasure map of the nearest treasure cache
 *     to the chest (not the chest's own site), or into a scrap of a lead note when there is none;</li>
 *     <li>{@code skycraft:explore_lead}: turns the stack into one of the "lead" notes ({@code ids}) and remembers
 *     where it was found, so reading it marks the nearest unknown location on the map
 *     ({@link ExploreEvents#onReadLead}).</li>
 * </ul>
 */
public final class ExploreLoot {
    public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Skycraft.MODID);

    public static final RegistryObject<LootItemFunctionType> TREASURE_MAP = FUNCTIONS.register("explore_treasure_map",
            () -> new LootItemFunctionType(new TreasureMapFn.Serializer()));
    public static final RegistryObject<LootItemFunctionType> LEAD = FUNCTIONS.register("explore_lead",
            () -> new LootItemFunctionType(new LeadFn.Serializer()));

    /** NBT key of a lead note's find position ({@code [x, z]}). */
    public static final String LEAD_TAG = "explore_lead";
    /** How far a treasure map looks for a cache from the chest it was found in. */
    public static final int MAP_RANGE = 1600;

    private ExploreLoot() {}

    public static void init(IEventBus modBus) {
        FUNCTIONS.register(modBus);
    }

    public static ResourceLocation table(String name) {
        return new ResourceLocation(Skycraft.MODID, "explore/" + name);
    }

    // ================================================================== function: skycraft:explore_treasure_map

    public static final class TreasureMapFn extends LootItemConditionalFunction {
        TreasureMapFn(LootItemCondition[] conditions) {
            super(conditions);
        }

        @Override
        public LootItemFunctionType getType() {
            return TREASURE_MAP.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
            ServerLevel level = context.getLevel();
            if (origin == null || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return ItemStack.EMPTY;
            int x = (int) Math.floor(origin.x), z = (int) Math.floor(origin.z);
            long seed = level.getSeed();
            long own = ChunkKey.cellKey(x, z);
            MinorSites.Site cache = MinorSites.nearest(seed, SiteKind.TREASURE_CACHE, x, z, MAP_RANGE, own);
            if (cache == null) return ItemStack.EMPTY;
            return TreasureMapItem.create(cache, context.getRandom());
        }

        public static class Serializer extends LootItemConditionalFunction.Serializer<TreasureMapFn> {
            @Override
            public TreasureMapFn deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
                return new TreasureMapFn(conditions);
            }
        }
    }

    // ================================================================== function: skycraft:explore_lead

    public static final class LeadFn extends LootItemConditionalFunction {
        private final List<String> ids;

        LeadFn(LootItemCondition[] conditions, List<String> ids) {
            super(conditions);
            this.ids = List.copyOf(ids);
        }

        @Override
        public LootItemFunctionType getType() {
            return LEAD.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            List<String> pool = ids.isEmpty() ? ExploreNotes.LEADS : ids;
            LoreBooks.Book book = LoreBooks.get(pool.get(context.getRandom().nextInt(pool.size())));
            if (book == null) return stack;
            ItemStack note = LoreBookItem.create(book);
            Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
            if (origin != null) {
                CompoundTag tag = note.getOrCreateTag();
                tag.putIntArray(LEAD_TAG, new int[]{(int) Math.floor(origin.x), (int) Math.floor(origin.z)});
            }
            return note;
        }

        public static class Serializer extends LootItemConditionalFunction.Serializer<LeadFn> {
            @Override
            public void serialize(JsonObject json, LeadFn fn, JsonSerializationContext ctx) {
                super.serialize(json, fn, ctx);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                fn.ids.forEach(arr::add);
                json.add("ids", arr);
            }

            @Override
            public LeadFn deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
                List<String> ids = new java.util.ArrayList<>();
                if (json.has("ids")) for (var e : GsonHelper.getAsJsonArray(json, "ids")) ids.add(e.getAsString());
                return new LeadFn(conditions, ids);
            }
        }
    }

    /** Cell keys of block positions. */
    static final class ChunkKey {
        static long cellKey(int x, int z) {
            return net.minecraft.world.level.ChunkPos.asLong(Math.floorDiv(x, MinorSites.CELL), Math.floorDiv(z, MinorSites.CELL));
        }
    }
}
