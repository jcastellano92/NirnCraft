package com.nirncraft.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.Currency;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.crafting.RegionalGear;
import com.nirncraft.economy.EconomyItems;
import com.nirncraft.economy.SkillBookItem;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
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
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The loot module's loot functions and conditions (all {@code nirncraft:}):
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
    public static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.storage.loot.functions.LootItemFunction>> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends LootItemCondition>> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, NirnCraft.MODID);

    /** Thirty days: Skyrim's cleared-dungeon respawn time, and NirnCraft's restock time. */
    public static final long OFFER_COOLDOWN = 30L * 24000L;

    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> SCALED_GOLD = FUNCTIONS.register("scaled_gold",
            () -> ScaledGold.MAP_CODEC);
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> PLACE_COLLECTIBLES = FUNCTIONS.register("place_collectibles",
            () -> Simple.codecFor(Simple.Op.PLACE));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> RANDOM_UNIQUE = FUNCTIONS.register("random_unique",
            () -> Simple.codecFor(Simple.Op.RANDOM_UNIQUE));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> REGIONAL_TREASURE = FUNCTIONS.register("regional_treasure",
            () -> Simple.codecFor(Simple.Op.TREASURE));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> SKILL_BOOK = FUNCTIONS.register("skill_book",
            () -> Simple.codecFor(Simple.Op.SKILL_BOOK));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> REGIONAL_BOOK = FUNCTIONS.register("regional_book",
            () -> Simple.codecFor(Simple.Op.BOOK));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> COLLECTIBLE = FUNCTIONS.register("collectible",
            () -> Named.codecFor(Named.Op.COLLECTIBLE));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> MISSING_COLLECTIBLE = FUNCTIONS.register("missing_collectible",
            () -> Named.codecFor(Named.Op.MISSING));
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> UNIQUE = FUNCTIONS.register("unique",
            () -> Named.codecFor(Named.Op.UNIQUE));

    public static final Supplier<MapCodec<? extends LootItemCondition>> LOOT_LEVEL = CONDITIONS.register("loot_level",
            () -> LevelCondition.MAP_CODEC);
    public static final Supplier<MapCodec<? extends LootItemCondition>> COLLECTION_COMPLETE = CONDITIONS.register("collection_complete",
            () -> CompleteCondition.MAP_CODEC);

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
        CompoundTag offered = data.module(Collectibles.MODULE).getCompoundOrEmpty("offered");
        long at = offered.getLongOr(e.key(), 0L);
        long now = context.getLevel().getGameTime();
        return at <= 0 || now - at >= OFFER_COOLDOWN || now < at;
    }

    private static ItemStack offer(LootContext context, PlayerData data, Collectibles.Entry e) {
        ItemStack s = e.create();
        if (s.isEmpty()) return s;
        ROLLED.computeIfAbsent(context, k -> new HashSet<>()).add(e.key());
        CompoundTag module = data.module(Collectibles.MODULE);
        CompoundTag offered = module.getCompoundOrEmpty("offered");
        offered.putLong(e.key(), context.getLevel().getGameTime());
        module.put("offered", offered);
        return s;
    }

    @Nullable
    private static Vec3 origin(LootContext context) {
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        Entity self = context.getOptional(LootContextParams.THIS_ENTITY);
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
        if (volume > 1) com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putInt(SkillBookItem.VOLUME_NBT, Math.min(SkillBookItem.VOLUMES, volume)));
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

    // ================================================================== nirncraft:scaled_gold

    public static final class ScaledGold extends LootItemConditionalFunction {
        final int min, max;
        final float growth;

        ScaledGold(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, int min, int max, float growth) {
            super(conditions);
            this.min = min;
            this.max = Math.max(min, max);
            this.growth = growth;
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
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

        public static final MapCodec<ScaledGold> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).and(i.group(Codec.INT.optionalFieldOf("min", 1).forGetter(f -> f.min), Codec.INT.optionalFieldOf("max", 10).forGetter(f -> f.max), Codec.FLOAT.optionalFieldOf("growth", 0.04f).forGetter(f -> f.growth))).apply(i, ScaledGold::new));
    }

    // ================================================================== parameterless collectible functions

    public static final class Simple extends LootItemConditionalFunction {
        enum Op { PLACE, RANDOM_UNIQUE, TREASURE, SKILL_BOOK, BOOK }

        final Op op;

        Simple(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, Op op) {
            super(conditions);
            this.op = op;
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
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
                com.nirncraft.tamriel.Region region = com.nirncraft.economy.EconomyLoot.regionOf(context);
                com.nirncraft.lore.LoreBooks.Book b = com.nirncraft.lore.LoreBooks.randomFor(random, region == null ? "skyrim" : region.province(), x -> true);
                return b == null ? ItemStack.EMPTY : com.nirncraft.lore.LoreBookItem.create(b);
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

        public static MapCodec<Simple> codecFor(Op op) {
            return RecordCodecBuilder.mapCodec(i -> commonFields(i).apply(i, conditions -> new Simple(conditions, op)));
        }
    }

    // ================================================================== functions naming a collectible

    public static final class Named extends LootItemConditionalFunction {
        enum Op { COLLECTIBLE, MISSING, UNIQUE }

        final Op op;
        final String collection;
        final String id;
        final boolean province;

        Named(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, Op op, String collection, String id, boolean province) {
            super(conditions);
            this.op = op;
            this.collection = collection;
            this.id = id;
            this.province = province;
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
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

        public static MapCodec<Named> codecFor(Op op) {
            return switch (op) {
                case UNIQUE -> RecordCodecBuilder.mapCodec(i -> commonFields(i).and(
                        Codec.STRING.fieldOf("id").forGetter(f -> f.id))
                        .apply(i, (c, id) -> new Named(c, op, LegendaryUniques.COLLECTION, id, false)));
                case COLLECTIBLE -> RecordCodecBuilder.mapCodec(i -> commonFields(i).and(i.group(
                        Codec.STRING.fieldOf("collection").forGetter(f -> f.collection),
                        Codec.STRING.fieldOf("entry").forGetter(f -> f.id)))
                        .apply(i, (c, collection, id) -> new Named(c, op, collection, id, false)));
                case MISSING -> RecordCodecBuilder.mapCodec(i -> commonFields(i).and(i.group(
                        Codec.STRING.fieldOf("collection").forGetter(f -> f.collection),
                        Codec.BOOL.optionalFieldOf("province", false).forGetter(f -> f.province)))
                        .apply(i, (c, collection, province) -> new Named(c, op, collection, "", province)));
            };
        }
    }

    // ================================================================== condition: nirncraft:loot_level

    public static final class LevelCondition implements LootItemCondition {
        final int min, max;

        LevelCondition(int min, int max) {
            this.min = min;
            this.max = max;
        }

        @Override
        public MapCodec<? extends LootItemCondition> codec() {
            return LOOT_LEVEL.get();
        }

        @Override
        public boolean test(LootContext context) {
            int level = LootLevels.gear(context);
            return level >= min && level <= max;
        }

        public static final MapCodec<LevelCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(Codec.INT.optionalFieldOf("min", 1).forGetter(f -> f.min), Codec.INT.optionalFieldOf("max", Integer.MAX_VALUE).forGetter(f -> f.max)).apply(i, LevelCondition::new));
    }

    // ================================================================== condition: nirncraft:collection_complete

    public static final class CompleteCondition implements LootItemCondition {
        final String collection;

        CompleteCondition(String collection) {
            this.collection = collection;
        }

        @Override
        public MapCodec<? extends LootItemCondition> codec() {
            return COLLECTION_COMPLETE.get();
        }

        @Override
        public boolean test(LootContext context) {
            Player player = LootLevels.player(context);
            return player != null && Collectibles.rewarded(SkyData.get(player), collection);
        }

        public static final MapCodec<CompleteCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(Codec.STRING.fieldOf("collection").forGetter(f -> f.collection)).apply(i, CompleteCondition::new));
    }
}
