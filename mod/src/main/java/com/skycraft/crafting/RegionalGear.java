package com.skycraft.crafting;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.skycraft.Skycraft;
import com.skycraft.arsenal.LeveledGear;
import com.skycraft.crafting.item.SkyAxeItem;
import com.skycraft.crafting.item.SkyBowItem;
import com.skycraft.crafting.item.SkyMeleeItem;
import com.skycraft.crafting.item.SkyShieldItem;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Province-aware leveled gear. The loot function {@code skycraft:regional_gear} replaces the rolled item with a weapon,
 * armour piece or shield of the province the loot is generated in, picked from the item tag
 * {@code #skycraft:gear/<province>} (Imperial Legion gear in Cyrodiil, Bonemold and Chitin in Morrowind, Thalmor and
 * Glass in Summerset, Ra Gada and scimitars in Hammerfell, katanas in Akavir...) and filtered by the looting player's
 * level against each material's {@code minLevel}, like Skyrim's leveled lists:
 *
 * <pre>{"function": "skycraft:regional_gear", "kind": "any", "enchant_chance": 0.1, "quality_chance": 0.2}</pre>
 *
 * <p>kind is {@code any}, {@code weapon}, {@code armor} or {@code shield}. The province key is the region's province,
 * except Wrothgar ({@code orsinium}) and Solstheim ({@code solstheim}); Thras shares Pyandonea's gear and the seas and
 * other dimensions use Skyrim's. Materials near the player's level are three times as likely as old ones.</p>
 */
public class RegionalGear extends LootItemConditionalFunction {
    public static final MapCodec<RegionalGear> CODEC = RecordCodecBuilder.mapCodec(instance ->
            commonFields(instance).and(instance.group(
                    Codec.STRING.xmap(value -> Kind.valueOf(value.toUpperCase(Locale.ROOT)), value -> value.name().toLowerCase(Locale.ROOT))
                            .optionalFieldOf("kind", Kind.ANY).forGetter(value -> value.kind),
                    Codec.FLOAT.optionalFieldOf("enchant_chance", 0f).forGetter(value -> value.enchantChance),
                    Codec.FLOAT.optionalFieldOf("quality_chance", 0f).forGetter(value -> value.qualityChance)))
                    .apply(instance, RegionalGear::new));
    public static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.storage.loot.functions.LootItemFunction>> FUNCTIONS =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Skycraft.MODID);
    public static final Supplier<MapCodec<RegionalGear>> REGIONAL_GEAR = FUNCTIONS.register("regional_gear", () -> CODEC);

    public enum Kind { ANY, WEAPON, ARMOR, SHIELD }

    private final Kind kind;
    private final float enchantChance;
    private final float qualityChance;

    protected RegionalGear(java.util.Optional<Holder<LootItemCondition>> conditions, Kind kind, float enchantChance, float qualityChance) {
        super(conditions);
        this.kind = kind;
        this.enchantChance = enchantChance;
        this.qualityChance = qualityChance;
    }

    @Override
    public MapCodec<RegionalGear> codec() {
        return REGIONAL_GEAR.get();
    }

    public static TagKey<Item> tag(String province) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, "gear/" + province));
    }

    /** The gear tag key of a region: its province, with Wrothgar and Solstheim apart. */
    public static String gearKey(@Nullable Region region) {
        if (region == null) return "skyrim";
        if (region.id().equals("high_rock/wrothgar")) return "orsinium";
        if (region.id().equals("morrowind/solstheim")) return "solstheim";
        return switch (region.province()) {
            case "cyrodiil", "morrowind", "summerset", "valenwood", "hammerfell", "high_rock", "black_marsh", "elsweyr", "akavir",
                    "atmora", "yokuda", "pyandonea", "realm" -> region.province();
            case "thras" -> "pyandonea";
            default -> "skyrim";
        };
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        Region region = null;
        if (origin != null) {
            try {
                region = Regions.at(context.getLevel(), BlockPos.containing(origin));
            } catch (RuntimeException ignored) {
                // no region data (another dimension's generator): Skyrim's gear
            }
        }
        int level = LeveledGear.levelOf(context);
        ItemStack out = roll(gearKey(region), kind, level, context.getRandom());
        if (out.isEmpty() && !"skyrim".equals(gearKey(region))) out = roll("skyrim", kind, level, context.getRandom());
        if (out.isEmpty()) return stack;
        LeveledGear.decorate(out, level, context.getRandom(), enchantChance, qualityChance);
        com.skycraft.loot.affix.ItemAffixes.applyFromContext(out, context, qualityChance);
        return out;
    }

    /** A random item of the province's gear tag that suits the kind and the character level (empty if none). */
    public static ItemStack roll(String province, Kind kind, int level, RandomSource random) {
        List<Item> items = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (Holder<Item> h : BuiltInRegistries.ITEM.get(tag(province)).stream().flatMap(set -> set.stream()).toList()) {
            Item item = h.value();
            if (!matches(kind, item)) continue;
            int min = minLevel(item);
            if (min > level) continue;
            int w = level - min < 12 ? 3 : 1;
            items.add(item);
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

    private static boolean matches(Kind kind, Item item) {
        return switch (kind) {
            case ANY -> true;
            case WEAPON -> item instanceof SkyMeleeItem || item instanceof SkyAxeItem || item instanceof SkyBowItem
                    || item instanceof com.skycraft.arsenal.item.PolearmItem
                    || item instanceof com.skycraft.arsenal.item.SkyCrossbowItem;
            case ARMOR -> com.skycraft.inventory.EquipmentData.isArmor(new ItemStack(item));
            case SHIELD -> item instanceof SkyShieldItem;
        };
    }

    /** Character level from which an item of the catalogue drops (1 for anything else): {@link com.skycraft.loot.LootTiers#unlock(Item)}. */
    public static int minLevel(Item item) {
        return com.skycraft.loot.LootTiers.unlock(item);
    }

}
