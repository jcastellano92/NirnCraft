package com.skycraft.loot;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.RegionalGear;
import com.skycraft.economy.EconomyItems;
import com.skycraft.economy.SkillBookItem;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The loot module's loot functions and conditions (all {@code skycraft:}):
 * <pre>
 *  scaled_gold         {"min": 8, "max": 30, "growth": 0.04}  Septims (a purse above 64) scaled by loot level and danger
 *  place_collectibles  {}                                     a collectible bound to the named place the loot is in
 *  collectible         {"collection": "black_books", "entry": "hidden_twilight"}
 *  missing_collectible {"collection": "artifacts_of_legend", "province": true}   one the player lacks
 *  unique              {"id": "gauldur_blackblade"}           a legendary unique ({@link LegendaryUniques})
 *  random_unique       {}                                     an unplaced unique of the loot's province
 *  regional_treasure   {}                                     a treasure of the loot's province set
 *  skill_book          {}                                     a skill book volume (1-5)
 *  regional_book       {}                                     a lore book, the loot's province's own 8x as likely
 *  condition loot_level          {"min": 12, "max": 99}       on the gear level ({@link LootLevels})
 *  condition collection_complete {"collection": "stones_of_barenziah"}
 * </pre>
 * Functions that hand out collectibles return an empty stack (nothing) when there is nothing left to find; every
 * collectible is offered to a character once per {@value #OFFER_COOLDOWN} ticks at most (a stone left in a chest that
 * restocks can come back, but two chests of one dungeon never both hold it).
 */
public final class LootFunctions {
    public static final DeferredRegister<LootItemFunctionType> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Skycraft.MODID);
    public static final DeferredRegister<LootItemConditionType> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, Skycraft.MODID);

    /** Thirty days: Skyrim's cleared-dungeon respawn time, and Skycraft's restock time. */
    public static final long OFFER_COOLDOWN = 30L * 24000L;

    public static final RegistryObject<LootItemFunctionType> SCALED_GOLD = FUNCTIONS.register("scaled_gold",
            () -> new LootItemFunctionType(new ScaledGold.Serializer()));
    public static final RegistryObject<LootItemFunctionType> PLACE_COLLECTIBLES = FUNCTIONS.register("place_collectibles",
            () -> new LootItemFunctionType(new Simple.Serializer(Simple.Op.PLACE)));
    public static final RegistryObject<LootItemFunctionType> RANDOM_UNIQUE = FUNCTIONS.register("random_unique",
            () -> new LootItemFunctionType(new Simple.Serializer(Simple.Op.RANDOM_UNIQUE)));
    public static final RegistryObject<LootItemFunctionType> REGIONAL_TREASURE = FUNCTIONS.register("regional_treasure",
            () -> new LootItemFunctionType(new Simple.Serializer(Simple.Op.TREASURE)));
    public static final RegistryObject<LootItemFunctionType> SKILL_BOOK = FUNCTIONS.register("skill_book",
            () -> new LootItemFunctionType(new Simple.Serializer(Simple.Op.SKILL_BOOK)));
    public static final RegistryObject<LootItemFunctionType> REGIONAL_BOOK = FUNCTIONS.register("regional_book",
            () -> new LootItemFunctionType(new Simple.Serializer(Simple.Op.BOOK)));
    public static final RegistryObject<LootItemFunctionType> COLLECTIBLE = FUNCTIONS.register("collectible",
            () -> new LootItemFunctionType(new Named.Serializer(Named.Op.COLLECTIBLE)));
    public static final RegistryObject<LootItemFunctionType> MISSING_COLLECTIBLE = FUNCTIONS.register("missing_collectible",
            () -> new LootItemFunctionType(new Named.Serializer(Named.Op.MISSING)));
    public static final RegistryObject<LootItemFunctionType> UNIQUE = FUNCTIONS.register("unique",
            () -> new LootItemFunctionType(new Named.Serializer(Named.Op.UNIQUE)));

    public static final RegistryObject<LootItemConditionType> LOOT_LEVEL = CONDITIONS.register("loot_level",
            () -> new LootItemConditionType(new LevelCondition.Serializer()));
    public static final RegistryObject<LootItemConditionType> COLLECTION_COMPLETE = CONDITIONS.register("collection_complete",
            () -> new LootItemConditionType(new CompleteCondition.Serializer()));

    private LootFunctions() {}

    public static void init(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        CONDITIONS.register(modBus);
    }

    // ------------------------------------------------------------------ shared helpers

    /** Collectible keys already handed out while one loot table is being rolled (two pools never give the same one). */
    private static final Map<LootContext, Set<String>> ROLLED = Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean offerable(LootContext context, PlayerData data, Collectibles.Entry e) {
        if (Collectibles.has(data, e)) return false;
        Set<String> rolled = ROLLED.get(context);
        if (rolled != null && rolled.contains(e.key())) return false;
        CompoundTag offered = data.module(Collectibles.MODULE).getCompound("offered");
        long at = offered.getLong(e.key());
        long now = context.getLevel().getGameTime();
        return at <= 0 || now - at >= OFFER_COOLDOWN || now < at;
    }

    private static ItemStack offer(LootContext context, PlayerData data, Collectibles.Entry e) {
        ItemStack s = e.create();
        if (s.isEmpty()) return s;
        ROLLED.computeIfAbsent(context, k -> new HashSet<>()).add(e.key());
        CompoundTag module = data.module(Collectibles.MODULE);
        CompoundTag offered = module.getCompound("offered");
        offered.putLong(e.key(), context.getLevel().getGameTime());
        module.put("offered", offered);
        return s;
    }

    @Nullable
    private static Vec3 origin(LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        Entity self = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (origin == null && self != null) origin = self.position();
        return origin;
    }

    /** Named places whose radius covers the loot's origin (overworld only). */
    public static List<Place> placesAt(LootContext context) {
        Vec3 o = origin(context);
        if (o == null || context.getLevel().dimension() != Level.OVERWORLD) return List.of();
        int x = Mth.floor(o.x), z = Mth.floor(o.z);
        List<Place> out = new ArrayList<>();
        for (Place p : Places.overlapping(x, z, x, z)) {
            long r = p.radius();
            if (p.distSqr(x, z) <= r * r) out.add(p);
        }
        return out;
    }

    /** A skill book of the skill and volume (1-5). */
    public static ItemStack skillBook(@Nullable Skill skill, int volume) {
        if (skill == null || !EconomyItems.SKILL_BOOKS.containsKey(skill)) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(EconomyItems.SKILL_BOOKS.get(skill).get());
        if (volume > 1) s.getOrCreateTag().putInt(SkillBookItem.VOLUME_NBT, Math.min(SkillBookItem.VOLUMES, volume));
        return s;
    }

    /** A random skill book: any skill, volume 1 most often; half the time a volume the reader hasn't read yet. */
    public static ItemStack randomSkillBook(@Nullable PlayerData data, RandomSource random) {
        if (data != null && random.nextBoolean()) {
            List<int[]> unread = new ArrayList<>();
            for (Skill s : Skill.VALUES) {
                for (int v = 1; v <= SkillBookItem.VOLUMES; v++) if (!SkillBookItem.hasRead(data, s, v)) unread.add(new int[]{s.ordinal(), v});
            }
            if (!unread.isEmpty()) {
                int[] pick = unread.get(random.nextInt(unread.size()));
                return skillBook(Skill.VALUES[pick[0]], pick[1]);
            }
        }
        Skill skill = Skill.VALUES[random.nextInt(Skill.VALUES.length)];
        int v = random.nextInt(10) < 4 ? 1 : 2 + random.nextInt(SkillBookItem.VOLUMES - 1);
        return skillBook(skill, v);
    }

    /** The treasure set of a gear key (Solstheim shares Morrowind's, Orsinium High Rock's), or null. */
    @Nullable
    public static String treasureProvince(String gearKey) {
        String key = switch (gearKey) {
            case "solstheim" -> "morrowind";
            case "orsinium" -> "high_rock";
            default -> gearKey;
        };
        for (String p : Collectibles.TREASURE_PROVINCES) if (p.equals(key)) return p;
        return null;
    }

    // ================================================================== skycraft:scaled_gold

    public static final class ScaledGold extends LootItemConditionalFunction {
        final int min, max;
        final float growth;

        ScaledGold(LootItemCondition[] conditions, int min, int max, float growth) {
            super(conditions);
            this.min = min;
            this.max = Math.max(min, max);
            this.growth = growth;
        }

        @Override
        public LootItemFunctionType getType() {
            return SCALED_GOLD.get();
        }

        /** Gold of one roll (also used by the dev command and mirrored by tools/loot_sim.py). */
        public static long amount(int min, int max, float growth, LootLevels.Level level, RandomSource random) {
            float base = min + random.nextFloat() * (max - min);
            return Math.max(1, Math.round(base * LootLevels.goldScale(level.gear(), growth) * level.danger()));
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            return Currency.coins(amount(min, max, growth, LootLevels.of(context), context.getRandom()));
        }

        public static class Serializer extends LootItemConditionalFunction.Serializer<ScaledGold> {
            @Override
            public void serialize(JsonObject json, ScaledGold fn, JsonSerializationContext ctx) {
                super.serialize(json, fn, ctx);
                json.addProperty("min", fn.min);
                json.addProperty("max", fn.max);
                json.addProperty("growth", fn.growth);
            }

            @Override
            public ScaledGold deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
                return new ScaledGold(conditions, GsonHelper.getAsInt(json, "min", 1), GsonHelper.getAsInt(json, "max", 10),
                        GsonHelper.getAsFloat(json, "growth", 0.04f));
            }
        }
    }

    // ================================================================== parameterless collectible functions

    public static final class Simple extends LootItemConditionalFunction {
        enum Op { PLACE, RANDOM_UNIQUE, TREASURE, SKILL_BOOK, BOOK }

        final Op op;

        Simple(LootItemCondition[] conditions, Op op) {
            super(conditions);
            this.op = op;
        }

        @Override
        public LootItemFunctionType getType() {
            return switch (op) {
                case PLACE -> PLACE_COLLECTIBLES.get();
                case RANDOM_UNIQUE -> RANDOM_UNIQUE.get();
                case TREASURE -> REGIONAL_TREASURE.get();
                case SKILL_BOOK -> SKILL_BOOK.get();
                case BOOK -> REGIONAL_BOOK.get();
            };
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            Player player = LootLevels.player(context);
            PlayerData data = player == null ? null : SkyData.get(player);
            RandomSource random = context.getRandom();
            if (op == Op.SKILL_BOOK) return randomSkillBook(data, random);
            if (op == Op.BOOK) {
                com.skycraft.tamriel.Region region = com.skycraft.economy.EconomyLoot.regionOf(context);
                com.skycraft.lore.LoreBooks.Book b = com.skycraft.lore.LoreBooks.randomFor(random, region == null ? "skyrim" : region.province(), x -> true);
                return b == null ? ItemStack.EMPTY : com.skycraft.lore.LoreBookItem.create(b);
            }
            if (data == null) return ItemStack.EMPTY; // collectibles are per character: no player, nothing
            LootLevels.Level level = LootLevels.of(context);
            switch (op) {
                case PLACE -> {
                    for (Place place : placesAt(context)) {
                        for (Collectibles.Entry e : Collectibles.boundTo(place)) {
                            if (e.minLevel() <= level.player() && offerable(context, data, e)) return offer(context, data, e);
                        }
                    }
                    return ItemStack.EMPTY;
                }
                case RANDOM_UNIQUE -> {
                    String key = RegionalGear.gearKey(level.region());
                    List<Collectibles.Entry> pool = new ArrayList<>();
                    Collectibles.Collection c = Collectibles.get(LegendaryUniques.COLLECTION);
                    if (c == null) return ItemStack.EMPTY;
                    for (Collectibles.Entry e : c.entries) {
                        if (e.places().isEmpty() && key.equals(e.province()) && e.minLevel() <= level.gear() && offerable(context, data, e)) pool.add(e);
                    }
                    return pool.isEmpty() ? ItemStack.EMPTY : offer(context, data, pool.get(random.nextInt(pool.size())));
                }
                default -> {
                    String province = treasureProvince(RegionalGear.gearKey(level.region()));
                    Collectibles.Collection c = province == null ? null : Collectibles.get(Collectibles.TREASURES + province);
                    if (c == null || c.entries.isEmpty()) return ItemStack.EMPTY;
                    // missing treasures three times as likely as ones already found (those still sell to a fence)
                    List<Collectibles.Entry> pool = new ArrayList<>();
                    for (Collectibles.Entry e : c.entries) {
                        boolean have = Collectibles.has(data, e);
                        for (int i = 0; i < (have ? 1 : 3); i++) pool.add(e);
                    }
                    return pool.get(random.nextInt(pool.size())).create();
                }
            }
        }

        public static class Serializer extends LootItemConditionalFunction.Serializer<Simple> {
            private final Op op;

            Serializer(Op op) {
                this.op = op;
            }

            @Override
            public Simple deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
                return new Simple(conditions, op);
            }
        }
    }

    // ================================================================== functions naming a collectible

    public static final class Named extends LootItemConditionalFunction {
        enum Op { COLLECTIBLE, MISSING, UNIQUE }

        final Op op;
        final String collection;
        final String id;
        final boolean province;

        Named(LootItemCondition[] conditions, Op op, String collection, String id, boolean province) {
            super(conditions);
            this.op = op;
            this.collection = collection;
            this.id = id;
            this.province = province;
        }

        @Override
        public LootItemFunctionType getType() {
            return switch (op) {
                case COLLECTIBLE -> COLLECTIBLE.get();
                case MISSING -> MISSING_COLLECTIBLE.get();
                case UNIQUE -> UNIQUE.get();
            };
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            Player player = LootLevels.player(context);
            PlayerData data = player == null ? null : SkyData.get(player);
            String key = op == Op.UNIQUE ? LegendaryUniques.COLLECTION + "/" + id : collection + "/" + id;
            if (op != Op.MISSING) {
                Collectibles.Entry e = Collectibles.entry(key);
                if (e == null) return ItemStack.EMPTY;
                // a placed collectible: each character finds it once (Lootr gives every player their own chest)
                if (data != null && Collectibles.has(data, e)) return ItemStack.EMPTY;
                return data == null ? e.create() : offer(context, data, e);
            }
            if (data == null) return ItemStack.EMPTY;
            LootLevels.Level level = LootLevels.of(context);
            String where = province ? RegionalGear.gearKey(level.region()) : null;
            Collectibles.Collection c = Collectibles.get(collection);
            if (c == null) return ItemStack.EMPTY;
            List<Collectibles.Entry> pool = new ArrayList<>();
            for (Collectibles.Entry e : c.entries) {
                if (e.minLevel() > level.gear() || !e.places().isEmpty()) continue; // placed ones wait in their place
                if (where != null && e.province() != null && !e.province().equals(where)) continue;
                if (offerable(context, data, e)) pool.add(e);
            }
            return pool.isEmpty() ? ItemStack.EMPTY : offer(context, data, pool.get(context.getRandom().nextInt(pool.size())));
        }

        public static class Serializer extends LootItemConditionalFunction.Serializer<Named> {
            private final Op op;

            Serializer(Op op) {
                this.op = op;
            }

            @Override
            public void serialize(JsonObject json, Named fn, JsonSerializationContext ctx) {
                super.serialize(json, fn, ctx);
                if (fn.op != Op.UNIQUE) json.addProperty("collection", fn.collection);
                if (fn.op != Op.MISSING) json.addProperty(fn.op == Op.UNIQUE ? "id" : "entry", fn.id);
                if (fn.op == Op.MISSING && fn.province) json.addProperty("province", true);
            }

            @Override
            public Named deserialize(JsonObject json, JsonDeserializationContext ctx, LootItemCondition[] conditions) {
                return switch (op) {
                    case UNIQUE -> new Named(conditions, op, LegendaryUniques.COLLECTION, GsonHelper.getAsString(json, "id"), false);
                    case COLLECTIBLE -> new Named(conditions, op, GsonHelper.getAsString(json, "collection"),
                            GsonHelper.getAsString(json, "entry"), false);
                    case MISSING -> new Named(conditions, op, GsonHelper.getAsString(json, "collection"), "",
                            GsonHelper.getAsBoolean(json, "province", false));
                };
            }
        }
    }

    // ================================================================== condition: skycraft:loot_level

    public static final class LevelCondition implements LootItemCondition {
        final int min, max;

        LevelCondition(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public net.minecraft.world.level.storage.loot.predicates.LootItemConditionType getType() {
            return LOOT_LEVEL.get();
        }

        @Override
        public boolean test(LootContext context) {
            int level = LootLevels.gear(context);
            return level >= min && level <= max;
        }

        public static class Serializer implements net.minecraft.world.level.storage.loot.Serializer<LevelCondition> {
            @Override
            public void serialize(JsonObject json, LevelCondition c, JsonSerializationContext ctx) {
                json.addProperty("min", c.min);
                json.addProperty("max", c.max);
            }

            @Override
            public LevelCondition deserialize(JsonObject json, JsonDeserializationContext ctx) {
                return new LevelCondition(GsonHelper.getAsInt(json, "min", 1), GsonHelper.getAsInt(json, "max", Integer.MAX_VALUE));
            }
        }
    }

    // ================================================================== condition: skycraft:collection_complete

    public static final class CompleteCondition implements LootItemCondition {
        final String collection;

        CompleteCondition(String collection) {
            this.collection = collection;
        }

        @Override
        public net.minecraft.world.level.storage.loot.predicates.LootItemConditionType getType() {
            return COLLECTION_COMPLETE.get();
        }

        @Override
        public boolean test(LootContext context) {
            Player player = LootLevels.player(context);
            return player != null && Collectibles.rewarded(SkyData.get(player), collection);
        }

        public static class Serializer implements net.minecraft.world.level.storage.loot.Serializer<CompleteCondition> {
            @Override
            public void serialize(JsonObject json, CompleteCondition c, JsonSerializationContext ctx) {
                json.addProperty("collection", c.collection);
            }

            @Override
            public CompleteCondition deserialize(JsonObject json, JsonDeserializationContext ctx) {
                return new CompleteCondition(GsonHelper.getAsString(json, "collection"));
            }
        }
    }
}
