package com.skycraft.explore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

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
    public static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.storage.loot.functions.LootItemFunction>> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Skycraft.MODID);

    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> TREASURE_MAP = FUNCTIONS.register("explore_treasure_map",
            () -> TreasureMapFn.MAP_CODEC);
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> LEAD = FUNCTIONS.register("explore_lead",
            () -> LeadFn.MAP_CODEC);

    /** NBT key of a lead note's find position ({@code [x, z]}). */
    public static final String LEAD_TAG = "explore_lead";
    /** How far a treasure map looks for a cache from the chest it was found in. */
    public static final int MAP_RANGE = 1600;

    private ExploreLoot() {}

    public static void init(IEventBus modBus) {
        FUNCTIONS.register(modBus);
    }

    public static Identifier table(String name) {
        return Identifier.fromNamespaceAndPath(Skycraft.MODID, "explore/" + name);
    }

    // ================================================================== function: skycraft:explore_treasure_map

    public static final class TreasureMapFn extends LootItemConditionalFunction {
        TreasureMapFn(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions) {
            super(conditions);
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
            return TREASURE_MAP.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
            ServerLevel level = context.getLevel();
            if (origin == null || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return ItemStack.EMPTY;
            int x = (int) Math.floor(origin.x), z = (int) Math.floor(origin.z);
            long seed = level.getSeed();
            long own = ChunkKey.cellKey(x, z);
            MinorSites.Site cache = MinorSites.nearest(seed, SiteKind.TREASURE_CACHE, x, z, MAP_RANGE, own);
            if (cache == null) return ItemStack.EMPTY;
            return TreasureMapItem.create(cache, context.getRandom());
        }

        public static final MapCodec<TreasureMapFn> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).apply(i, TreasureMapFn::new));
    }

    // ================================================================== function: skycraft:explore_lead

    public static final class LeadFn extends LootItemConditionalFunction {
        private final List<String> ids;

        LeadFn(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, List<String> ids) {
            super(conditions);
            this.ids = List.copyOf(ids);
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
            return LEAD.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            List<String> pool = ids.isEmpty() ? ExploreNotes.LEADS : ids;
            LoreBooks.Book book = LoreBooks.get(pool.get(context.getRandom().nextInt(pool.size())));
            if (book == null) return stack;
            ItemStack note = LoreBookItem.create(book);
            Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
            if (origin != null) {
                CompoundTag tag = com.skycraft.inventory.ItemData.customOrEmpty(note);
                tag.putIntArray(LEAD_TAG, new int[]{(int) Math.floor(origin.x), (int) Math.floor(origin.z)});
                com.skycraft.inventory.ItemData.setCustom(note, tag);
            }
            return note;
        }

        public static final MapCodec<LeadFn> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).and(Codec.STRING.listOf().optionalFieldOf("ids", List.of()).forGetter(f -> f.ids)).apply(i, LeadFn::new));
    }

    /** Cell keys of block positions. */
    static final class ChunkKey {
        static long cellKey(int x, int z) {
            return net.minecraft.world.level.ChunkPos.pack(Math.floorDiv(x, MinorSites.CELL), Math.floorDiv(z, MinorSites.CELL));
        }
    }
}
