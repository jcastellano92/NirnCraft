package com.nirncraft.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.LeveledGear;
import com.nirncraft.core.SkyData;
import com.nirncraft.crafting.RegionalGear;
import com.nirncraft.crafting.arcane.alchemy.Alchemy;
import com.nirncraft.crafting.arcane.alchemy.AlchemyEffect;
import com.nirncraft.magic.MagicRegistry;
import com.nirncraft.magic.spell.School;
import com.nirncraft.magic.spell.Spell;
import com.nirncraft.magic.spell.Spells;
import com.nirncraft.magic.spell.Tier;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Province- and level-aware loot for chests, corpses, flora and shops:
 * <ul>
 *   <li>condition {@code nirncraft:region} {@code {"provinces": ["morrowind"], "gear_keys": ["solstheim"]}}: the loot's
 *   origin lies in one of the provinces / gear keys ({@link RegionalGear#gearKey}) / region ids;</li>
 *   <li>function {@code nirncraft:regional_tag} {@code {"tag": "nirncraft:loot/ingredients"}}: replaces the item with a
 *   random item of {@code #<tag>/<gear key>} (falling back to {@code #<tag>/skyrim}), keeping the count;</li>
 *   <li>function {@code nirncraft:leveled_tome} {@code {"schools": ["destruction"]}}: a spell tome whose level suits the
 *   player (no Master tomes at level 1);</li>
 *   <li>function {@code nirncraft:skyrim_potion} {@code {"poison": false}}: a Skyrim potion (or poison) whose strength
 *   follows the player's level.</li>
 * </ul>
 */
public final class EconomyLoot {
    public static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.storage.loot.functions.LootItemFunction>> FUNCTIONS = DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, NirnCraft.MODID);
    public static final DeferredRegister<MapCodec<? extends LootItemCondition>> CONDITIONS = DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, NirnCraft.MODID);

    public static final Supplier<MapCodec<? extends LootItemCondition>> REGION = CONDITIONS.register("region",
            () -> RegionCondition.MAP_CODEC);
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> REGIONAL_TAG = FUNCTIONS.register("regional_tag",
            () -> RegionalTag.MAP_CODEC);
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> LEVELED_TOME = FUNCTIONS.register("leveled_tome",
            () -> LeveledTome.MAP_CODEC);
    public static final Supplier<MapCodec<? extends LootItemConditionalFunction>> SKYRIM_POTION = FUNCTIONS.register("skyrim_potion",
            () -> SkyrimPotion.MAP_CODEC);

    private EconomyLoot() {}

    public static void init(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        CONDITIONS.register(modBus);
    }

    // ------------------------------------------------------------------ shared helpers

    /** The region the loot is generated in (null without an origin or region data). */
    @Nullable
    public static Region regionOf(LootContext context) {
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        if (origin == null && context.getOptional(LootContextParams.THIS_ENTITY) != null) {
            origin = context.getOptional(LootContextParams.THIS_ENTITY).position();
        }
        return origin == null ? null : regionAt(context.getLevel(), BlockPos.containing(origin));
    }

    @Nullable
    public static Region regionAt(Level level, BlockPos pos) {
        try {
            return Regions.at(level, pos);
        } catch (RuntimeException e) {
            return null; // another dimension's generator, or no map data
        }
    }

    public static TagKey<Item> itemTag(String id) {
        return TagKey.create(Registries.ITEM, Identifier.parse(id));
    }

    /** A random item of {@code #<base>/<key>}, else of {@code #<base>/skyrim}; null if both are empty. */
    @Nullable
    public static Item randomRegional(String base, String key, RandomSource random) {
        Item item = randomOf(itemTag(base + "/" + key), random);
        if (item == null && !"skyrim".equals(key)) item = randomOf(itemTag(base + "/skyrim"), random);
        return item;
    }

    @Nullable
    public static Item randomOf(TagKey<Item> tag, RandomSource random) {
        List<Item> items = new ArrayList<>();
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            if (h.value() != Items.AIR) items.add(h.value());
        }
        return items.isEmpty() ? null : items.get(random.nextInt(items.size()));
    }

    // ------------------------------------------------------------------ spell tomes

    /** Lowest character level from which loot holds tomes of a spell level (Skyrim's leveled lists, roughly). */
    public static int lootLevel(Tier tier) {
        return switch (tier) {
            case NOVICE -> 1;
            case APPRENTICE -> 6;
            case ADEPT -> 14;
            case EXPERT -> 26;
            case MASTER -> 40;
        };
    }

    /** School skill from which court wizards sell tomes of a spell level (Skyrim's vendor lists, roughly). */
    public static int vendorSkill(Tier tier) {
        return switch (tier) {
            case NOVICE -> 0;
            case APPRENTICE -> 15;
            case ADEPT -> 40;
            case EXPERT -> 65;
            case MASTER -> 90;
        };
    }

    /**
     * A random spell tome. For loot ({@code player == null} or {@code vendor == false}) the tier is gated by character
     * level; for a court wizard's stock by the player's skill in the spell's school (and the shop's size: Expert tomes
     * need a level 3 shop, Master tomes a level 5 one). Weighted by {@link Tier#lootWeight}.
     */
    public static ItemStack tome(int level, @Nullable Player player, boolean vendor, int shopLevel, Set<School> schools, RandomSource random) {
        List<Item> items = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (Spell spell : Spells.all()) {
            if (!schools.isEmpty() && !schools.contains(spell.school)) continue;
            Supplier<Item> ro = MagicRegistry.TOMES.get(spell.id);
            if (ro == null) continue;
            boolean ok;
            if (vendor) {
                int skill = player == null ? 15 + 10 * shopLevel : SkyData.get(player).getSkill(spell.school.skill);
                ok = skill >= vendorSkill(spell.tier)
                        && (spell.tier != Tier.EXPERT || shopLevel >= 3) && (spell.tier != Tier.MASTER || shopLevel >= 5);
            } else {
                ok = level >= lootLevel(spell.tier);
            }
            if (!ok) continue;
            int w = Math.max(1, spell.tier.lootWeight);
            items.add(ro.get());
            weights.add(w);
            total += w;
        }
        if (total == 0) return ItemStack.EMPTY;
        int pick = random.nextInt(total);
        for (int i = 0; i < items.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) return new ItemStack(items.get(i));
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ Skyrim potions

    private static final AlchemyEffect[] POTIONS = {
            AlchemyEffect.RESTORE_HEALTH, AlchemyEffect.RESTORE_HEALTH, AlchemyEffect.RESTORE_HEALTH,
            AlchemyEffect.RESTORE_MAGICKA, AlchemyEffect.RESTORE_MAGICKA, AlchemyEffect.RESTORE_STAMINA,
            AlchemyEffect.RESTORE_STAMINA, AlchemyEffect.CURE_DISEASE, AlchemyEffect.CURE_POISON,
            AlchemyEffect.RESIST_FIRE, AlchemyEffect.RESIST_FROST, AlchemyEffect.RESIST_SHOCK, AlchemyEffect.RESIST_POISON,
            AlchemyEffect.RESIST_MAGIC, AlchemyEffect.FORTIFY_HEALTH, AlchemyEffect.FORTIFY_MAGICKA, AlchemyEffect.FORTIFY_STAMINA,
            AlchemyEffect.FORTIFY_ONE_HANDED, AlchemyEffect.FORTIFY_TWO_HANDED, AlchemyEffect.FORTIFY_ARCHERY,
            AlchemyEffect.FORTIFY_BLOCK, AlchemyEffect.FORTIFY_SNEAK, AlchemyEffect.FORTIFY_LOCKPICKING,
            AlchemyEffect.FORTIFY_BARTER, AlchemyEffect.FORTIFY_DESTRUCTION, AlchemyEffect.FORTIFY_RESTORATION,
            AlchemyEffect.FORTIFY_CONJURATION, AlchemyEffect.FORTIFY_ILLUSION, AlchemyEffect.FORTIFY_ALTERATION,
            AlchemyEffect.FORTIFY_CARRY_WEIGHT, AlchemyEffect.INVISIBILITY, AlchemyEffect.WATERBREATHING,
            AlchemyEffect.NIGHT_EYE, AlchemyEffect.REGENERATE_HEALTH, AlchemyEffect.REGENERATE_MAGICKA,
            AlchemyEffect.REGENERATE_STAMINA
    };
    private static final AlchemyEffect[] POISONS = {
            AlchemyEffect.DAMAGE_HEALTH, AlchemyEffect.DAMAGE_HEALTH, AlchemyEffect.LINGERING_DAMAGE_HEALTH,
            AlchemyEffect.PARALYSIS, AlchemyEffect.DAMAGE_MAGICKA, AlchemyEffect.DAMAGE_STAMINA, AlchemyEffect.RAVAGE_HEALTH,
            AlchemyEffect.LINGERING_DAMAGE_MAGICKA, AlchemyEffect.LINGERING_DAMAGE_STAMINA, AlchemyEffect.FEAR,
            AlchemyEffect.FRENZY, AlchemyEffect.SLOW, AlchemyEffect.WEAKNESS_TO_FIRE, AlchemyEffect.WEAKNESS_TO_FROST,
            AlchemyEffect.WEAKNESS_TO_SHOCK, AlchemyEffect.WEAKNESS_TO_MAGIC, AlchemyEffect.WEAKNESS_TO_POISON
    };

    /** A Skyrim potion or poison (one effect) whose strength grows with the level: weak, standard, strong, vigorous... */
    public static ItemStack skyrimPotion(boolean poison, int level, RandomSource random) {
        AlchemyEffect[] pool = poison ? POISONS : POTIONS;
        return skyrimPotion(pool[random.nextInt(pool.length)], level, random);
    }

    /** A Skyrim potion (or poison, for a harmful effect) of one effect, its strength following the level. */
    public static ItemStack skyrimPotion(AlchemyEffect effect, int level, RandomSource random) {
        boolean poison = !effect.positive;
        float strength = 1f + Math.min(3f, Math.max(0, level - 1 + random.nextInt(8) - 3) / 15f);
        Alchemy.Brew brew = new Alchemy.Brew(List.of(new Alchemy.BrewEffect(effect, strength)), poison);
        return Alchemy.createItem(brew);
    }

    // ================================================================== condition: nirncraft:region

    public static final class RegionCondition implements LootItemCondition {
        private final Set<String> provinces;
        private final Set<String> gearKeys;
        private final Set<String> regions;

        RegionCondition(Set<String> provinces, Set<String> gearKeys, Set<String> regions) {
            this.provinces = provinces;
            this.gearKeys = gearKeys;
            this.regions = regions;
        }

        @Override
        public MapCodec<? extends LootItemCondition> codec() {
            return REGION.get();
        }

        @Override
        public boolean test(LootContext context) {
            Region region = regionOf(context);
            if (region == null) return provinces.contains("skyrim") || gearKeys.contains("skyrim");
            return provinces.contains(region.province()) || gearKeys.contains(RegionalGear.gearKey(region))
                    || regions.contains(region.id());
        }

        public static final MapCodec<RegionCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(Codec.STRING.listOf().xmap(v -> (Set<String>) new LinkedHashSet<>(v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()), v -> new java.util.ArrayList<>(v)).optionalFieldOf("provinces", Set.of()).forGetter(f -> f.provinces), Codec.STRING.listOf().xmap(v -> (Set<String>) new LinkedHashSet<>(v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()), v -> new java.util.ArrayList<>(v)).optionalFieldOf("gear_keys", Set.of()).forGetter(f -> f.gearKeys), Codec.STRING.listOf().xmap(v -> (Set<String>) new LinkedHashSet<>(v.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()), v -> new java.util.ArrayList<>(v)).optionalFieldOf("regions", Set.of()).forGetter(f -> f.regions)).apply(i, RegionCondition::new));
    }

    private static JsonArray array(Set<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static Set<String> strings(JsonObject json, String key) {
        Set<String> out = new LinkedHashSet<>();
        if (json.has(key)) {
            for (JsonElement e : GsonHelper.getAsJsonArray(json, key)) out.add(e.getAsString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ================================================================== function: nirncraft:regional_tag

    public static final class RegionalTag extends LootItemConditionalFunction {
        private final String tag;

        RegionalTag(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, String tag) {
            super(conditions);
            this.tag = tag;
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
            return REGIONAL_TAG.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            Item item = randomRegional(tag, RegionalGear.gearKey(regionOf(context)), context.getRandom());
            if (item == null) return stack;
            ItemStack out = new ItemStack(item, Math.max(1, Math.min(item.getMaxStackSize(new ItemStack(item)), stack.getCount())));
            return out;
        }

        public static final MapCodec<RegionalTag> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).and(Codec.STRING.fieldOf("tag").forGetter(f -> f.tag)).apply(i, RegionalTag::new));
    }

    // ================================================================== function: nirncraft:leveled_tome

    public static final class LeveledTome extends LootItemConditionalFunction {
        private final Set<School> schools;

        LeveledTome(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, Set<School> schools) {
            super(conditions);
            this.schools = schools;
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
            return LEVELED_TOME.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            ItemStack out = tome(LeveledGear.levelOf(context), null, false, 0, schools, context.getRandom());
            return out.isEmpty() ? stack : out;
        }

        public static final MapCodec<LeveledTome> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).and(com.nirncraft.platform.SkyCodecs.enumNames(School.class).listOf().xmap(v -> (Set<School>) new java.util.HashSet<>(v), v -> new java.util.ArrayList<>(v)).optionalFieldOf("schools", Set.of()).forGetter(f -> f.schools)).apply(i, LeveledTome::new));
    }

    // ================================================================== function: nirncraft:skyrim_potion

    public static final class SkyrimPotion extends LootItemConditionalFunction {
        private final boolean poison;

        SkyrimPotion(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions, boolean poison) {
            super(conditions);
            this.poison = poison;
        }

        @Override
        public MapCodec<? extends LootItemConditionalFunction> codec() {
            return SKYRIM_POTION.get();
        }

        @Override
        protected ItemStack run(ItemStack stack, LootContext context) {
            ItemStack out = skyrimPotion(poison, LeveledGear.levelOf(context), context.getRandom());
            out.setCount(Math.max(1, Math.min(out.getMaxStackSize(), stack.getCount())));
            return out;
        }

        public static final MapCodec<SkyrimPotion> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).and(Codec.BOOL.optionalFieldOf("poison", false).forGetter(f -> f.poison)).apply(i, SkyrimPotion::new));
    }
}
