package com.skycraft.lore.place;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.skycraft.Skycraft;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreModule;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The default placer's container half: every Skycraft container table a named place's chests roll (dungeon, camp,
 * site, household, shop, strongbox and loose containers) gets one extra pool with the function
 * {@code skycraft:place_lore_note}. It hands the opening player one note of the place the container stands in that
 * they haven't found yet: the boss chest prefers the {@code "at": "boss"} notes, every other container the
 * {@code chest} ones (and the lectern ones, in case the lectern couldn't be placed); boss notes turn up elsewhere
 * only now and then, for places without a boss chest. Places whose notes another module places itself
 * ({@link PlaceLore#claimPlacement}) are skipped. With Lootr every player rolls their own copy; without it the first
 * opener gets the note (they can share it).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlaceLoreLoot {
    public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Skycraft.MODID);
    public static final RegistryObject<LootItemFunctionType> PLACE_NOTE = FUNCTIONS.register("place_lore_note",
            () -> new LootItemFunctionType(new NoteFunction.Serializer()));

    /** Table path prefixes (namespace skycraft) that get the note pool. */
    private static final List<String> TABLES = List.of("containers/dungeon/boss", "containers/dungeon/standard", "containers/camp/",
            "containers/site/", "containers/household/", "containers/shop/", "containers/strongbox/", "containers/misc/satchel",
            "containers/misc/knapsack", "containers/misc/sarcophagus");

    private PlaceLoreLoot() {}

    public static void init(IEventBus modBus) {
        FUNCTIONS.register(modBus);
    }

    @SubscribeEvent
    public static void onTableLoad(LootTableLoadEvent event) {
        ResourceLocation id = event.getName();
        if (!id.getNamespace().equals(Skycraft.MODID)) return;
        String path = id.getPath();
        boolean match = false;
        for (String t : TABLES) if (path.startsWith(t)) match = true;
        if (!match || path.startsWith("containers/household_stores")) return;
        boolean boss = path.endsWith("/boss") || path.contains("dragon_lair");
        event.getTable().addPool(LootPool.lootPool().name("skycraft_place_lore").setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(LoreModule.BOOK.get()).apply(() -> new NoteFunction(new LootItemCondition[0], boss)))
                .build());
    }

    /** The note for the player opening a container at the loot's origin, or null. */
    @Nullable
    static PlaceLore.Note pick(LootContext context, boolean bossChest) {
        Vec3 o = context.getParamOrNull(LootContextParams.ORIGIN);
        Entity self = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (!(self instanceof ServerPlayer player) || o == null || context.getLevel().dimension() != Level.OVERWORLD) return null;
        int x = Mth.floor(o.x), z = Mth.floor(o.z);
        PlayerData data = SkyData.get(player);
        for (Place p : Places.overlapping(x, z, x, z)) {
            long r = p.radius();
            if (p.distSqr(x, z) > r * r || PlaceLore.claimed(p.id())) continue;
            PlaceLore.Content c = PlaceLore.contentFor(p.id()).orElse(null);
            if (c == null) continue;
            PlaceLore.Note n = bossChest ? first(c, data, PlaceLore.At.BOSS, PlaceLore.At.CHEST, PlaceLore.At.SITE)
                    : first(c, data, PlaceLore.At.CHEST, PlaceLore.At.SITE, null);
            if (n == null && !bossChest && context.getRandom().nextInt(3) == 0) n = first(c, data, PlaceLore.At.BOSS, null, null);
            if (n != null) return n;
        }
        return null;
    }

    @Nullable
    private static PlaceLore.Note first(PlaceLore.Content c, PlayerData data, PlaceLore.At a, @Nullable PlaceLore.At b, @Nullable PlaceLore.At d) {
        for (PlaceLore.At at : new PlaceLore.At[]{a, b, d}) {
            if (at == null) continue;
            for (PlaceLore.Note n : c.notes()) if (n.at() == at && !PlaceLoreEvents.hasFound(data, n.bookId())) return n;
        }
        return null;
    }

    /** {@code skycraft:place_lore_note {"boss": bool}}: turns the book into the place's next unfound note, or nothing. */
    public static final class NoteFunction extends LootItemConditionalFunction {
        private final boolean boss;

        NoteFunction(LootItemCondition[] conditions, boolean boss) {
            super(conditions);
            this.boss = boss;
        }

        @Override
        public LootItemFunctionType getType() {
            return PLACE_NOTE.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            PlaceLore.Note n = pick(context, boss);
            if (n == null) return ItemStack.EMPTY;
            Entity self = context.getParamOrNull(LootContextParams.THIS_ENTITY);
            if (self instanceof ServerPlayer player) PlaceLoreEvents.markFound(SkyData.get(player), n.bookId());
            return LoreBookItem.create(n.book());
        }

        public static class Serializer extends LootItemConditionalFunction.Serializer<NoteFunction> {
            @Override
            public void serialize(JsonObject json, NoteFunction f, JsonSerializationContext ctx) {
                super.serialize(json, f, ctx);
                json.addProperty("boss", f.boss);
            }

            @Override
            public NoteFunction deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
                return new NoteFunction(conditions, json.has("boss") && json.get("boss").getAsBoolean());
            }
        }
    }
}
