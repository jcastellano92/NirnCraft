package com.nirncraft.loot.affix;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nirncraft.NirnCraft;
import com.nirncraft.arsenal.item.ArtifactItem;
import com.nirncraft.arsenal.item.EnchantedStaffItem;
import com.nirncraft.arsenal.item.PolearmItem;
import com.nirncraft.arsenal.item.SkyCrossbowItem;
import com.nirncraft.arsenal.item.StaffItem;
import com.nirncraft.crafting.Tempering;
import com.nirncraft.crafting.item.SkyAxeItem;
import com.nirncraft.crafting.item.SkyBowItem;
import com.nirncraft.crafting.item.SkyMeleeItem;
import com.nirncraft.crafting.item.SkyShieldItem;
import com.nirncraft.economy.EconomyLoot;
import com.nirncraft.economy.ItemValues;
import com.nirncraft.loot.LootFunctions;
import com.nirncraft.loot.LootLevels;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Region;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Skyrim-faithful item affixes (`R14`): found-item condition/quality, regional cultural makers citing worldsheet L5/L11,
 * tiered enchantment suffixes ("of Embers" .. "of the Inferno"), and rare boss-chest storied items with one-line L11
 * provenance (deterministic hash of place id and slot, never two identical in one world).
 *
 * <p>Affix state lives in the {@value #TAG} sub-compound on an {@link ItemStack} and survives both smithing tempering
 * ({@link Tempering}) and arcane enchanting ({@link com.nirncraft.crafting.arcane.enchant.Enchanting}).</p>
 */
public final class ItemAffixes {
    public static final String TAG = "NirnCraftAffix";
    private static final String BASE_PATH = "/nirncraft/loot/affixes/";
    private static final Gson GSON = new Gson();

    private static volatile boolean loaded = false;
    private static String defaultQualityId = "standard";
    private static String defaultEnchantFamilyId = "fortify_arms";
    private static float storiedBossChance = 0.05f;
    private static final Map<String, QualityDef> QUALITIES = new LinkedHashMap<>();
    private static final Map<String, SynonymDef> ALL_SYNONYMS = new LinkedHashMap<>();
    private static final Map<String, Float> MAKER_SOURCE_CHANCES = new HashMap<>();
    private static final Map<String, MakerDef> MAKERS = new LinkedHashMap<>();
    private static final Map<String, EnchantFamilyDef> ENCHANT_FAMILIES = new LinkedHashMap<>();
    private static final List<EpithetDef> EPITHETS = new ArrayList<>();
    private static final Map<String, EpithetDef> EPITHETS_BY_ID = new LinkedHashMap<>();
    private static final List<StoriedDef> STORIED = new ArrayList<>();
    private static final Map<String, StoriedDef> STORIED_BY_ID = new LinkedHashMap<>();

    /** Per-world set of already-issued storied combinations so no two identical storied items appear in one world. */
    private static final Set<String> ISSUED_STORIED = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> ISSUED_PLACE_SLOTS = Collections.synchronizedSet(new HashSet<>());
    private static final AtomicInteger ROLL_SLOT_COUNTER = new AtomicInteger();

    /** Optional thread-local region hint set when rolling regional gear for merchants or outfits. */
    private static final ThreadLocal<String> REGION_HINT = new ThreadLocal<>();

    public record SynonymDef(String id, String name, String langKey, String canonicalQualityId) {}

    public record QualityDef(
            String id,
            String name,
            String langKey,
            int tier,
            float damageMult,
            float armorMult,
            float durabilityMult,
            float valueMult,
            Map<String, Integer> weights,
            Map<String, SynonymDef> synonyms
    ) {}

    public record MakerDef(
            String id,
            String name,
            String langKey,
            String descKey,
            String desc,
            List<String> cultures,
            List<String> provinces,
            List<String> regions,
            float damageMult,
            float armorMult,
            float durabilityMult,
            float valueMult,
            List<String> why
    ) {}

    public record EnchantFamilyDef(
            String id,
            List<String> enchantments,
            List<String> keywords,
            List<String> tiers,
            List<String> langKeys
    ) {}

    public record EpithetDef(String id, String name, String langKey) {}

    public record StoriedDef(
            String id,
            String prefix,
            String provenance,
            String langPrefixKey,
            String langProvKey,
            List<String> regions,
            List<String> provinces,
            List<String> cultures,
            float damageMult,
            float armorMult,
            float durabilityMult,
            float valueMult,
            List<String> why
    ) {}

    private ItemAffixes() {}

    // ------------------------------------------------------------------ data loading

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        QUALITIES.clear();
        ALL_SYNONYMS.clear();
        MAKER_SOURCE_CHANCES.clear();
        MAKERS.clear();
        ENCHANT_FAMILIES.clear();
        EPITHETS.clear();
        EPITHETS_BY_ID.clear();
        STORIED.clear();
        STORIED_BY_ID.clear();

        loadQualities();
        loadMakers();
        loadEnchantTiers();
        loadStoried();
        loaded = true;
    }

    private static JsonObject readJson(String filename) {
        String path = BASE_PATH + filename;
        try (InputStream in = ItemAffixes.class.getResourceAsStream(path)) {
            if (in == null) {
                NirnCraft.LOGGER.error("Missing affix resource {}", path);
                return new JsonObject();
            }
            return GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            NirnCraft.LOGGER.error("Failed loading affix resource {}", path, e);
            return new JsonObject();
        }
    }

    private static List<String> stringList(JsonObject obj, String member) {
        if (!obj.has(member) || !obj.get(member).isJsonArray()) return List.of();
        JsonArray arr = obj.getAsJsonArray(member);
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) out.add(el.getAsString());
        return List.copyOf(out);
    }

    private static void loadQualities() {
        JsonObject root = readJson("qualities.json");
        defaultQualityId = GsonHelper.getAsString(root, "default_quality", "standard");
        JsonArray arr = GsonHelper.getAsJsonArray(root, "qualities", new JsonArray());
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String id = GsonHelper.getAsString(o, "id");
            String name = GsonHelper.getAsString(o, "name", id);
            String langKey = GsonHelper.getAsString(o, "lang_key", "affix.nirncraft.quality." + id);
            int tier = GsonHelper.getAsInt(o, "tier", 0);
            float dmg = GsonHelper.getAsFloat(o, "damage_mult", 1.0f);
            float arm = GsonHelper.getAsFloat(o, "armor_mult", 1.0f);
            float dur = GsonHelper.getAsFloat(o, "durability_mult", 1.0f);
            float val = GsonHelper.getAsFloat(o, "value_mult", 1.0f);
            Map<String, Integer> weights = new LinkedHashMap<>();
            if (o.has("weights") && o.get("weights").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("weights").entrySet()) {
                    weights.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            Map<String, SynonymDef> syns = new LinkedHashMap<>();
            if (o.has("synonyms") && o.get("synonyms").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("synonyms").entrySet()) {
                    JsonObject so = e.getValue().getAsJsonObject();
                    SynonymDef syn = new SynonymDef(
                            GsonHelper.getAsString(so, "id"),
                            GsonHelper.getAsString(so, "name"),
                            GsonHelper.getAsString(so, "lang_key"),
                            id
                    );
                    syns.put(e.getKey(), syn);
                    ALL_SYNONYMS.put(syn.id(), syn);
                }
            }
            QualityDef def = new QualityDef(id, name, langKey, tier, dmg, arm, dur, val, Map.copyOf(weights), Map.copyOf(syns));
            QUALITIES.put(id, def);
        }
    }

    private static void loadMakers() {
        JsonObject root = readJson("makers.json");
        if (root.has("source_chances") && root.get("source_chances").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("source_chances").entrySet()) {
                MAKER_SOURCE_CHANCES.put(e.getKey(), e.getValue().getAsFloat());
            }
        }
        JsonArray arr = GsonHelper.getAsJsonArray(root, "makers", new JsonArray());
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String id = GsonHelper.getAsString(o, "id");
            MakerDef def = new MakerDef(
                    id,
                    GsonHelper.getAsString(o, "name", id),
                    GsonHelper.getAsString(o, "lang_key", "affix.nirncraft.maker." + id),
                    GsonHelper.getAsString(o, "desc_key", "affix.nirncraft.maker." + id + ".desc"),
                    GsonHelper.getAsString(o, "desc", ""),
                    stringList(o, "cultures"),
                    stringList(o, "provinces"),
                    stringList(o, "regions"),
                    GsonHelper.getAsFloat(o, "damage_mult", 1.0f),
                    GsonHelper.getAsFloat(o, "armor_mult", 1.0f),
                    GsonHelper.getAsFloat(o, "durability_mult", 1.0f),
                    GsonHelper.getAsFloat(o, "value_mult", 1.0f),
                    stringList(o, "why")
            );
            MAKERS.put(id, def);
        }
    }

    private static void loadEnchantTiers() {
        JsonObject root = readJson("enchant_tiers.json");
        defaultEnchantFamilyId = GsonHelper.getAsString(root, "default_family", "fortify_arms");
        JsonArray arr = GsonHelper.getAsJsonArray(root, "families", new JsonArray());
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String id = GsonHelper.getAsString(o, "id");
            EnchantFamilyDef def = new EnchantFamilyDef(
                    id,
                    stringList(o, "enchantments"),
                    stringList(o, "keywords"),
                    stringList(o, "tiers"),
                    stringList(o, "lang_keys")
            );
            ENCHANT_FAMILIES.put(id, def);
        }
    }

    private static void loadStoried() {
        JsonObject root = readJson("storied.json");
        storiedBossChance = GsonHelper.getAsFloat(root, "boss_chance", 0.05f);
        JsonArray epArr = GsonHelper.getAsJsonArray(root, "epithets", new JsonArray());
        for (JsonElement el : epArr) {
            JsonObject o = el.getAsJsonObject();
            String id = GsonHelper.getAsString(o, "id");
            EpithetDef ep = new EpithetDef(
                    id,
                    GsonHelper.getAsString(o, "name", id),
                    GsonHelper.getAsString(o, "lang_key", "affix.nirncraft.storied.epithet." + id)
            );
            EPITHETS.add(ep);
            EPITHETS_BY_ID.put(id, ep);
        }
        JsonArray stArr = GsonHelper.getAsJsonArray(root, "entries", new JsonArray());
        for (JsonElement el : stArr) {
            JsonObject o = el.getAsJsonObject();
            String id = GsonHelper.getAsString(o, "id");
            StoriedDef st = new StoriedDef(
                    id,
                    GsonHelper.getAsString(o, "prefix", id),
                    GsonHelper.getAsString(o, "provenance", ""),
                    GsonHelper.getAsString(o, "lang_prefix_key", "affix.nirncraft.storied." + id + ".prefix"),
                    GsonHelper.getAsString(o, "lang_prov_key", "affix.nirncraft.storied." + id + ".provenance"),
                    stringList(o, "regions"),
                    stringList(o, "provinces"),
                    stringList(o, "cultures"),
                    GsonHelper.getAsFloat(o, "damage_mult", 1.05f),
                    GsonHelper.getAsFloat(o, "armor_mult", 1.05f),
                    GsonHelper.getAsFloat(o, "durability_mult", 1.15f),
                    GsonHelper.getAsFloat(o, "value_mult", 1.18f),
                    stringList(o, "why")
            );
            STORIED.add(st);
            STORIED_BY_ID.put(id, st);
        }
    }

    public static Map<String, QualityDef> qualities() {
        ensureLoaded();
        return Collections.unmodifiableMap(QUALITIES);
    }

    public static Map<String, MakerDef> makers() {
        ensureLoaded();
        return Collections.unmodifiableMap(MAKERS);
    }

    public static Map<String, EnchantFamilyDef> enchantFamilies() {
        ensureLoaded();
        return Collections.unmodifiableMap(ENCHANT_FAMILIES);
    }

    public static List<EpithetDef> epithets() {
        ensureLoaded();
        return Collections.unmodifiableList(EPITHETS);
    }

    public static List<StoriedDef> storiedEntries() {
        ensureLoaded();
        return Collections.unmodifiableList(STORIED);
    }

    public static void setRegionHint(@Nullable String regionOrGearKey) {
        if (regionOrGearKey == null) REGION_HINT.remove();
        else REGION_HINT.set(regionOrGearKey);
    }

    @Nullable
    public static String consumeRegionHint() {
        String h = REGION_HINT.get();
        REGION_HINT.remove();
        return h;
    }

    public static void resetWorldStoried() {
        ISSUED_STORIED.clear();
        ISSUED_PLACE_SLOTS.clear();
        ROLL_SLOT_COUNTER.set(0);
    }

    // ------------------------------------------------------------------ classification & culture

    public static boolean isAffixable(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getMaxStackSize() != 1) return false;
        Item item = stack.getItem();
        if (item == Items.AIR || item instanceof ArtifactItem) return false;
        if (com.nirncraft.inventory.ItemData.custom(stack) != null && com.nirncraft.inventory.ItemData.custom(stack).contains("nirncraft_collectible")) return false;
        return item instanceof SkyMeleeItem
                || item instanceof SkyAxeItem
                || item instanceof SkyBowItem
                || item instanceof SkyCrossbowItem
                || item instanceof PolearmItem
                || com.nirncraft.inventory.EquipmentData.isArmor(stack)
                || item instanceof ShieldItem
                || item instanceof SkyShieldItem
                || item instanceof StaffItem
                || item instanceof EnchantedStaffItem
                || stack.is(net.minecraft.tags.ItemTags.SWORDS)
                || stack.is(net.minecraft.tags.ItemTags.AXES)
                || stack.is(net.minecraft.tags.ItemTags.PICKAXES)
                || stack.is(net.minecraft.tags.ItemTags.SHOVELS)
                || stack.is(net.minecraft.tags.ItemTags.HOES)
                || item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem;
    }

    public static String normalizeRegion(@Nullable String regionOrGearKey) {
        if (regionOrGearKey == null || regionOrGearKey.isBlank()) return "skyrim/whiterun";
        String r = regionOrGearKey.trim().toLowerCase(Locale.ROOT);
        if (r.contains("/")) return r;
        return switch (r) {
            case "orsinium", "wrothgar" -> "high_rock/wrothgar";
            case "high_rock", "breton" -> "high_rock/glenumbra";
            case "hammerfell", "redguard", "yokuda" -> "hammerfell/alikr_desert";
            case "morrowind", "solstheim", "dunmer" -> "morrowind/vvardenfell";
            case "cyrodiil", "imperial" -> "cyrodiil/heartlands";
            case "summerset", "altmer" -> "summerset/auridon";
            case "valenwood", "bosmer" -> "valenwood/grahtwood";
            case "elsweyr", "khajiit" -> "elsweyr/anequina";
            case "black_marsh", "argonian" -> "black_marsh/shadowfen";
            case "akavir", "akaviri" -> "akavir/tsaesci";
            case "atmora" -> "skyrim/winterhold";
            default -> "skyrim/whiterun";
        };
    }

    public static String provinceOf(String regionId) {
        String norm = normalizeRegion(regionId);
        if (norm.equals("high_rock/wrothgar")) return "orsinium";
        int slash = norm.indexOf('/');
        return slash > 0 ? norm.substring(0, slash) : norm;
    }

    public static String inferCulture(ItemStack stack, String regionId) {
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = key != null ? key.getPath().toLowerCase(Locale.ROOT) : "";
        if (path.contains("dwarven") || path.contains("dwemer")) return "dwemer";
        if (path.contains("orcish") || path.contains("orichalcum")) return "orc";
        if (path.contains("elven") || path.contains("glass") || path.contains("thalmor")) return "altmer";
        if (path.contains("bonemold") || path.contains("chitin") || path.contains("dreugh") || path.contains("netch")) return "dunmer";
        if (path.contains("ra_gada") || path.contains("scimitar") || path.contains("redguard")) return "redguard";
        if (path.contains("imperial")) return "imperial";
        if (path.contains("akaviri") || path.contains("katana") || path.contains("wakizashi") || path.contains("tanto")) return "akaviri";
        if (path.contains("khajiit")) return "khajiit";
        if (path.contains("argonian")) return "argonian";
        if (path.contains("bosmer") || path.contains("wood_elf")) return "bosmer";
        if (path.contains("ayleid")) return "ayleid";

        String norm = normalizeRegion(regionId);
        if (norm.equals("high_rock/wrothgar")) return "orc";
        if (norm.equals("skyrim/the_reach") && path.contains("silver")) return "reachmen";
        String prov = provinceOf(norm);
        return switch (prov) {
            case "orsinium" -> "orc";
            case "high_rock" -> "breton";
            case "hammerfell" -> "redguard";
            case "morrowind" -> "dunmer";
            case "cyrodiil" -> "imperial";
            case "summerset" -> "altmer";
            case "valenwood" -> "bosmer";
            case "elsweyr" -> "khajiit";
            case "black_marsh" -> "argonian";
            case "akavir" -> "akaviri";
            default -> "nord";
        };
    }

    // ------------------------------------------------------------------ rolling & applying affixes

    /**
     * Infers the affix source kind ({@code corpse}, {@code boss}, {@code container}, {@code merchant}) and region from a
     * {@link LootContext} and applies affixes in-place.
     */
    public static void applyFromContext(ItemStack stack, LootContext context, float qualityChance) {
        if (!isAffixable(stack)) return;
        Region region = EconomyLoot.regionOf(context);
        String regionId = region != null ? region.id() : "skyrim/whiterun";
        String sourceKind;
        if (context.getOptional(LootContextParams.DAMAGE_SOURCE) != null
                || context.getOptional(LootContextParams.THIS_ENTITY) instanceof Mob) {
            sourceKind = "corpse";
        } else if (qualityChance >= 0.32f) {
            sourceKind = "boss";
        } else {
            sourceKind = "container";
        }
        String placeId = regionId;
        List<Place> places = LootFunctions.placesAt(context);
        if (!places.isEmpty()) {
            placeId = places.get(0).id();
        } else {
            Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
            if (origin != null) {
                BlockPos pos = BlockPos.containing(origin);
                placeId = regionId + "@" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
            }
        }
        int level = LootLevels.gear(context);
        int slot = ROLL_SLOT_COUNTER.incrementAndGet();
        apply(stack, regionId, sourceKind, level, context.getRandom(), placeId, slot);
    }

    /** Convenience overload used by deterministic tests and simple callers. */
    public static void apply(ItemStack stack, @Nullable String regionId, String sourceKind, long seed) {
        RandomSource random = RandomSource.create(seed);
        apply(stack, regionId, sourceKind, 20, random, normalizeRegion(regionId) + "#seed" + seed, (int) (seed & 0x7FFF));
    }

    /**
     * Rolls and writes {@value #TAG} on {@code stack} for the given region and source kind ({@code corpse},
     * {@code container}, {@code boss}, {@code merchant}).
     */
    public static void apply(
            ItemStack stack,
            @Nullable String regionId,
            String sourceKind,
            int lootLevel,
            RandomSource random,
            @Nullable String placeId,
            int slotIndex
    ) {
        ensureLoaded();
        if (!isAffixable(stack)) return;

        String normRegion = normalizeRegion(regionId);
        String normSource = normalizeSource(sourceKind);
        String culture = inferCulture(stack, normRegion);

        QualityDef quality = rollQuality(normSource, random);
        String qualityLabel = quality.id();
        SynonymDef syn = quality.synonyms().get(culture);
        if (syn != null && random.nextFloat() < 0.75f) {
            qualityLabel = syn.id();
        }

        // Roll a cultural maker when the item is at least standard quality (or on boss rolls)
        MakerDef maker = null;
        float makerChance = MAKER_SOURCE_CHANCES.getOrDefault(normSource, 0.15f);
        if (quality.tier() >= 0 && random.nextFloat() < makerChance) {
            maker = rollMaker(normRegion, culture, random);
        }

        CompoundTag affix = com.nirncraft.platform.ItemTags.childOrEmpty(stack, TAG);
        if (!com.nirncraft.platform.NbtData.hasType(affix, "base_name", Tag.TAG_STRING)) {
            affix.putString("base_name", Component.translatable(stack.getItem().getDescriptionId()).getString());
        }
        if (!com.nirncraft.platform.NbtData.hasType(affix, "base_value", com.nirncraft.platform.NbtData.ANY_NUMERIC)) {
            affix.putInt("base_value", Math.max(1, ItemValues.base(stack.getItem())));
        }
        affix.putString("quality", quality.id());
        affix.putString("quality_label", qualityLabel);
        affix.putString("culture", culture);
        affix.putString("region", normRegion);
        affix.putString("source", normSource);

        if (maker != null) {
            affix.putString("maker", maker.id());
        } else {
            affix.remove("maker");
        }

        // Rare storied items (~5% of boss chests), deterministic hash of (placeId, slotIndex)
        if ("boss".equals(normSource) && random.nextFloat() < storiedBossChance) {
            String effectivePlace = (placeId == null || placeId.isBlank()) ? normRegion : placeId;
            assignStoriedToTag(affix, normRegion, effectivePlace, slotIndex);
        }

        // If the item is already enchanted, assign its tiered enchantment suffix
        if (stack.isEnchanted()) {
            assignPrimaryEnchantToTag(stack, affix, lootLevel);
        }

        com.nirncraft.platform.ItemTags.setChild(stack, TAG, affix);
        recomputeMultipliersAndRefresh(stack);
    }

    /**
     * Deterministically applies a storied affix for {@code (regionId, placeId, slotIndex)}, guaranteeing no two storied
     * items in the same world are identical.
     */
    public static void applyStoried(ItemStack stack, @Nullable String regionId, String placeId, int slotIndex) {
        ensureLoaded();
        if (!isAffixable(stack)) return;
        String normRegion = normalizeRegion(regionId);
        String culture = inferCulture(stack, normRegion);
        CompoundTag affix = com.nirncraft.platform.ItemTags.childOrEmpty(stack, TAG);
        if (!com.nirncraft.platform.NbtData.hasType(affix, "base_name", Tag.TAG_STRING)) {
            affix.putString("base_name", Component.translatable(stack.getItem().getDescriptionId()).getString());
        }
        if (!com.nirncraft.platform.NbtData.hasType(affix, "base_value", com.nirncraft.platform.NbtData.ANY_NUMERIC)) {
            affix.putInt("base_value", Math.max(1, ItemValues.base(stack.getItem())));
        }
        if (!com.nirncraft.platform.NbtData.hasType(affix, "quality", Tag.TAG_STRING)) {
            affix.putString("quality", "well_made");
            affix.putString("quality_label", "well_made");
        }
        affix.putString("culture", culture);
        affix.putString("region", normRegion);
        affix.putString("source", "boss");
        assignStoriedToTag(affix, normRegion, placeId, slotIndex);
        com.nirncraft.platform.ItemTags.setChild(stack, TAG, affix);
        recomputeMultipliersAndRefresh(stack);
    }

    private static void assignStoriedToTag(CompoundTag affix, String normRegion, String placeId, int slotIndex) {
        if (STORIED.isEmpty() || EPITHETS.isEmpty()) return;
        List<StoriedDef> regional = new ArrayList<>();
        String prov = provinceOf(normRegion);
        for (StoriedDef st : STORIED) {
            if (st.regions().contains(normRegion)) regional.add(st);
        }
        if (regional.isEmpty()) {
            for (StoriedDef st : STORIED) {
                if (st.provinces().contains(prov)) regional.add(st);
            }
        }
        if (regional.isEmpty()) regional.addAll(STORIED);

        String placeSlotKey = placeId + "#" + slotIndex;
        long baseHash = mix64(placeSlotKey);

        StoriedDef chosenStoried = regional.get(Math.floorMod(baseHash, regional.size()));
        EpithetDef chosenEpithet = EPITHETS.get(Math.floorMod(baseHash >>> 16, EPITHETS.size()));

        // Ensure no two storied items in one world share the same (storied, epithet) combination
        int totalCombos = STORIED.size() * EPITHETS.size();
        for (int probe = 0; probe < totalCombos; probe++) {
            List<StoriedDef> pool = probe < regional.size() * EPITHETS.size() ? regional : STORIED;
            long h = mix64(placeSlotKey + ":" + probe);
            StoriedDef st = pool.get(Math.floorMod(h, pool.size()));
            EpithetDef ep = EPITHETS.get(Math.floorMod(h >>> 17, EPITHETS.size()));
            String comboKey = st.id() + ":" + ep.id();
            if (ISSUED_STORIED.add(comboKey)) {
                chosenStoried = st;
                chosenEpithet = ep;
                break;
            }
        }
        ISSUED_PLACE_SLOTS.add(placeSlotKey);
        affix.putString("storied", chosenStoried.id());
        affix.putString("epithet", chosenEpithet.id());
        affix.putString("storied_hash", placeSlotKey);
    }

    private static long mix64(String key) {
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 31;
        }
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static String normalizeSource(@Nullable String sourceKind) {
        if (sourceKind == null) return "container";
        String s = sourceKind.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "corpse", "boss", "merchant", "container" -> s;
            default -> "container";
        };
    }

    private static QualityDef rollQuality(String sourceKind, RandomSource random) {
        int total = 0;
        for (QualityDef q : QUALITIES.values()) {
            total += Math.max(0, q.weights().getOrDefault(sourceKind, 0));
        }
        if (total <= 0) {
            return QUALITIES.getOrDefault(defaultQualityId, QUALITIES.values().iterator().next());
        }
        int pick = random.nextInt(total);
        for (QualityDef q : QUALITIES.values()) {
            int w = Math.max(0, q.weights().getOrDefault(sourceKind, 0));
            pick -= w;
            if (pick < 0) return q;
        }
        return QUALITIES.getOrDefault(defaultQualityId, QUALITIES.values().iterator().next());
    }

    @Nullable
    private static MakerDef rollMaker(String regionId, String culture, RandomSource random) {
        if (MAKERS.isEmpty()) return null;
        String prov = provinceOf(regionId);
        List<MakerDef> candidates = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (MakerDef m : MAKERS.values()) {
            int w = 0;
            if (m.regions().contains(regionId)) w += 5;
            if (m.cultures().contains(culture)) w += 4;
            if (m.provinces().contains(prov)) w += 2;
            if (w > 0) {
                candidates.add(m);
                weights.add(w);
                total += w;
            }
        }
        if (total <= 0) return MAKERS.get("skyforge");
        int pick = random.nextInt(total);
        for (int i = 0; i < candidates.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) return candidates.get(i);
        }
        return candidates.get(0);
    }

    // ------------------------------------------------------------------ enchanting & tempering preservation

    public static EnchantFamilyDef resolveEnchantFamily(Enchantment ench) {
        ensureLoaded();
        Identifier rl = com.nirncraft.platform.EnchantmentsAccess.id(ench);
        String fullId = rl != null ? rl.toString().toLowerCase(Locale.ROOT) : "";
        String path = rl != null ? rl.getPath().toLowerCase(Locale.ROOT) : "";
        for (EnchantFamilyDef fam : ENCHANT_FAMILIES.values()) {
            for (String eid : fam.enchantments()) {
                if (eid.equalsIgnoreCase(fullId)) return fam;
            }
        }
        for (EnchantFamilyDef fam : ENCHANT_FAMILIES.values()) {
            for (String kw : fam.keywords()) {
                if (path.contains(kw.toLowerCase(Locale.ROOT))) return fam;
            }
        }
        EnchantFamilyDef fallback = ENCHANT_FAMILIES.get(defaultEnchantFamilyId);
        return fallback != null ? fallback : ENCHANT_FAMILIES.values().iterator().next();
    }

    /**
     * Maps an enchantment level and optional loot/skill context level (1..60) onto the 6-rung Skyrim enchantment tier
     * ladder (1..6).
     */
    public static int computeEnchantTier(Enchantment ench, int enchLevel, int contextLevel) {
        if (contextLevel > 0) {
            int fromContext = contextLevel >= 48 ? 6
                    : contextLevel >= 37 ? 5
                    : contextLevel >= 27 ? 4
                    : contextLevel >= 17 ? 3
                    : contextLevel >= 9 ? 2 : 1;
            int max = Math.max(1, ench.getMaxLevel());
            int fromEnch = max <= 1 ? fromContext : Mth.clamp(1 + Math.round((enchLevel - 1) * 5.0f / (max - 1)), 1, 6);
            return Mth.clamp((fromContext + fromEnch) / 2, 1, 6);
        }
        int max = Math.max(1, ench.getMaxLevel());
        if (enchLevel >= 1 && enchLevel <= 6 && max <= 2 && enchLevel > max) {
            return Mth.clamp(enchLevel, 1, 6);
        }
        if (max <= 1) return Mth.clamp(enchLevel, 1, 6);
        return Mth.clamp(1 + Math.round((enchLevel - 1) * 5.0f / (max - 1)), 1, 6);
    }

    private static void assignPrimaryEnchantToTag(ItemStack stack, CompoundTag affix, int contextLevel) {
        var map = stack.getEnchantments();
        if (map.isEmpty()) return;
        Enchantment bestEnch = null;
        int bestLvl = 0;
        for (var e : map.entrySet()) {
            if (bestEnch == null || e.getValue() > bestLvl) {
                bestEnch = e.getKey().value();
                bestLvl = e.getValue();
            }
        }
        if (bestEnch != null) {
            EnchantFamilyDef fam = resolveEnchantFamily(bestEnch);
            int tier = computeEnchantTier(bestEnch, bestLvl, contextLevel);
            affix.putString("enchant_family", fam.id());
            affix.putInt("enchant_tier", tier);
        }
    }

    /** Copies affix NBT from {@code source} to {@code target} (e.g. when splitting a stack or binding a staff). */
    public static void copyAffixes(ItemStack source, ItemStack target) {
        if (source == null || target == null || source.isEmpty() || target.isEmpty()) return;
        CompoundTag srcTag = com.nirncraft.inventory.ItemData.custom(source);
        if (srcTag != null && com.nirncraft.platform.NbtData.hasType(srcTag, TAG, Tag.TAG_COMPOUND)) {
            com.nirncraft.inventory.ItemData.updateCustom(target, customData263 -> customData263.put(TAG, srcTag.getCompoundOrEmpty(TAG).copy()));
            recomputeMultipliersAndRefresh(target);
        }
    }

    /** Called when an item is tempered at the Grindstone or Armor Workbench: preserves all affix NBT and name. */
    public static void onTemper(ItemStack stack) {
        ensureLoaded();
        if (stack == null || stack.isEmpty() || !hasAffixes(stack)) return;
        recomputeMultipliersAndRefresh(stack);
    }

    /** Called when an item is enchanted at the Arcane Enchanter: preserves affixes and applies the tiered suffix. */
    public static void onEnchant(ItemStack stack, Enchantment ench, int enchLevel) {
        onEnchant(stack, ench, enchLevel, 0);
    }

    /** Called when an item is enchanted with a known context level (or soul-scaled tier): preserves affixes and updates name. */
    public static void onEnchant(ItemStack stack, Enchantment ench, int enchLevel, int contextLevel) {
        ensureLoaded();
        if (stack == null || stack.isEmpty()) return;
        CompoundTag affix = com.nirncraft.platform.ItemTags.childOrEmpty(stack, TAG);
        if (!com.nirncraft.platform.NbtData.hasType(affix, "base_name", Tag.TAG_STRING)) {
            affix.putString("base_name", Component.translatable(stack.getItem().getDescriptionId()).getString());
        }
        if (!com.nirncraft.platform.NbtData.hasType(affix, "base_value", com.nirncraft.platform.NbtData.ANY_NUMERIC)) {
            affix.putInt("base_value", Math.max(1, ItemValues.base(stack.getItem())));
        }
        if (!com.nirncraft.platform.NbtData.hasType(affix, "quality", Tag.TAG_STRING)) {
            affix.putString("quality", defaultQualityId);
            affix.putString("quality_label", defaultQualityId);
        }
        EnchantFamilyDef fam = resolveEnchantFamily(ench);
        int tier = computeEnchantTier(ench, enchLevel, contextLevel);
        affix.putString("enchant_family", fam.id());
        affix.putInt("enchant_tier", tier);
        com.nirncraft.platform.ItemTags.setChild(stack, TAG, affix);
        recomputeMultipliersAndRefresh(stack);
    }

    /** Preview of the resulting item name for the Arcane Enchanter UI before the player clicks Enchant. */
    public static Component previewEnchantedName(ItemStack stack, Enchantment ench, int enchLevel) {
        ensureLoaded();
        ItemStack copy = stack.copy();
        onEnchant(copy, ench, enchLevel, 0);
        return copy.getHoverName();
    }

    // ------------------------------------------------------------------ multipliers, value & display name

    private static void recomputeMultipliersAndRefresh(ItemStack stack) {
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (tag == null || !com.nirncraft.platform.NbtData.hasType(tag, TAG, Tag.TAG_COMPOUND)) return;
        CompoundTag affix = tag.getCompoundOrEmpty(TAG);

        QualityDef q = QUALITIES.getOrDefault(affix.getStringOr("quality",""), QUALITIES.get(defaultQualityId));
        MakerDef m = com.nirncraft.platform.NbtData.hasType(affix, "maker", Tag.TAG_STRING) ? MAKERS.get(affix.getStringOr("maker","")) : null;
        StoriedDef st = com.nirncraft.platform.NbtData.hasType(affix, "storied", Tag.TAG_STRING) ? STORIED_BY_ID.get(affix.getStringOr("storied","")) : null;

        float dmg = q != null ? q.damageMult() : 1.0f;
        float arm = q != null ? q.armorMult() : 1.0f;
        float dur = q != null ? q.durabilityMult() : 1.0f;
        float val = q != null ? q.valueMult() : 1.0f;

        if (m != null) {
            dmg *= m.damageMult();
            arm *= m.armorMult();
            dur *= m.durabilityMult();
            val *= m.valueMult();
        }
        if (st != null) {
            dmg *= st.damageMult();
            arm *= st.armorMult();
            dur *= st.durabilityMult();
            val *= st.valueMult();
        }

        affix.putFloat("damage_mult", dmg);
        affix.putFloat("armor_mult", arm);
        affix.putFloat("durability_mult", dur);
        affix.putFloat("value_mult", val);

        int baseValue = com.nirncraft.platform.NbtData.hasType(affix, "base_value", com.nirncraft.platform.NbtData.ANY_NUMERIC)
                ? affix.getIntOr("base_value",0)
                : Math.max(1, ItemValues.base(stack.getItem()));
        if (Math.abs(val - 1.0f) > 0.001f) {
            tag.putInt(ItemValues.VALUE_NBT, Math.max(1, Math.round(baseValue * val)));
        } else {
            tag.remove(ItemValues.VALUE_NBT);
        }

        tag.put(TAG, affix);
        com.nirncraft.inventory.ItemData.setCustom(stack, tag);
        String displayName = buildDisplayName(stack, affix);
        String baseName = affix.getStringOr("base_name","");
        if (!displayName.isEmpty() && !displayName.equals(baseName)) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(displayName));
        }
    }

    public static String buildDisplayName(ItemStack stack, CompoundTag affix) {
        ensureLoaded();
        String baseName = com.nirncraft.platform.NbtData.hasType(affix, "base_name", Tag.TAG_STRING)
                ? affix.getStringOr("base_name","")
                : Component.translatable(stack.getItem().getDescriptionId()).getString();

        StringBuilder sb = new StringBuilder();
        StoriedDef st = com.nirncraft.platform.NbtData.hasType(affix, "storied", Tag.TAG_STRING) ? STORIED_BY_ID.get(affix.getStringOr("storied","")) : null;
        EpithetDef ep = com.nirncraft.platform.NbtData.hasType(affix, "epithet", Tag.TAG_STRING) ? EPITHETS_BY_ID.get(affix.getStringOr("epithet","")) : null;
        MakerDef maker = com.nirncraft.platform.NbtData.hasType(affix, "maker", Tag.TAG_STRING) ? MAKERS.get(affix.getStringOr("maker","")) : null;
        String qId = affix.getStringOr("quality","");
        String qLabelId = affix.getStringOr("quality_label","");

        if (st != null) {
            sb.append(st.prefix()).append(' ');
            if (maker != null && !baseName.toLowerCase(Locale.ROOT).contains(maker.name().toLowerCase(Locale.ROOT))) {
                sb.append(maker.name()).append(' ');
            }
        } else {
            if (!qId.isEmpty() && !qId.equals(defaultQualityId)) {
                String qName = qualityDisplayName(qLabelId.isEmpty() ? qId : qLabelId);
                if (!qName.isEmpty()) sb.append(qName).append(' ');
            }
            if (maker != null) {
                sb.append(maker.name()).append(' ');
            }
        }

        sb.append(baseName);

        String enchSuffix = enchantSuffixDisplay(affix);
        if (!enchSuffix.isEmpty()) {
            sb.append(' ').append(enchSuffix);
        } else if (st != null && ep != null) {
            sb.append(' ').append(ep.name());
        }

        return sb.toString().trim();
    }

    public static String qualityDisplayName(String qualityOrSynonymId) {
        ensureLoaded();
        QualityDef q = QUALITIES.get(qualityOrSynonymId);
        if (q != null) return q.name();
        SynonymDef syn = ALL_SYNONYMS.get(qualityOrSynonymId);
        return syn != null ? syn.name() : "";
    }

    public static String enchantSuffixDisplay(CompoundTag affix) {
        if (!com.nirncraft.platform.NbtData.hasType(affix, "enchant_family", Tag.TAG_STRING)) return "";
        EnchantFamilyDef fam = ENCHANT_FAMILIES.get(affix.getStringOr("enchant_family",""));
        if (fam == null || fam.tiers().isEmpty()) return "";
        int tier = Mth.clamp(affix.getIntOr("enchant_tier",0), 1, fam.tiers().size());
        return fam.tiers().get(tier - 1);
    }

    // ------------------------------------------------------------------ getters & tooltip

    public static boolean hasAffixes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        return tag != null && com.nirncraft.platform.NbtData.hasType(tag, TAG, Tag.TAG_COMPOUND);
    }

    @Nullable
    public static String qualityId(ItemStack stack) {
        if (!hasAffixes(stack)) return null;
        String q = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG).getStringOr("quality","");
        return q.isEmpty() ? null : q;
    }

    @Nullable
    public static String qualityLabelId(ItemStack stack) {
        if (!hasAffixes(stack)) return null;
        String q = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG).getStringOr("quality_label","");
        return q.isEmpty() ? qualityId(stack) : q;
    }

    @Nullable
    public static String makerId(ItemStack stack) {
        if (!hasAffixes(stack)) return null;
        String m = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG).getStringOr("maker","");
        return m.isEmpty() ? null : m;
    }

    @Nullable
    public static String storiedId(ItemStack stack) {
        if (!hasAffixes(stack)) return null;
        String s = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG).getStringOr("storied","");
        return s.isEmpty() ? null : s;
    }

    @Nullable
    public static String provenanceText(ItemStack stack) {
        ensureLoaded();
        String sid = storiedId(stack);
        if (sid == null) return null;
        StoriedDef st = STORIED_BY_ID.get(sid);
        return st != null ? st.provenance() : null;
    }

    public static float damageMultiplier(ItemStack stack) {
        if (!hasAffixes(stack)) return 1.0f;
        CompoundTag affix = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG);
        return com.nirncraft.platform.NbtData.hasType(affix, "damage_mult", com.nirncraft.platform.NbtData.ANY_NUMERIC) ? affix.getFloatOr("damage_mult",0F) : 1.0f;
    }

    public static float armorMultiplier(ItemStack stack) {
        if (!hasAffixes(stack)) return 1.0f;
        CompoundTag affix = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG);
        return com.nirncraft.platform.NbtData.hasType(affix, "armor_mult", com.nirncraft.platform.NbtData.ANY_NUMERIC) ? affix.getFloatOr("armor_mult",0F) : 1.0f;
    }

    public static float durabilityMultiplier(ItemStack stack) {
        if (!hasAffixes(stack)) return 1.0f;
        CompoundTag affix = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG);
        return com.nirncraft.platform.NbtData.hasType(affix, "durability_mult", com.nirncraft.platform.NbtData.ANY_NUMERIC) ? affix.getFloatOr("durability_mult",0F) : 1.0f;
    }

    public static float valueMultiplier(ItemStack stack) {
        if (!hasAffixes(stack)) return 1.0f;
        CompoundTag affix = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG);
        return com.nirncraft.platform.NbtData.hasType(affix, "value_mult", com.nirncraft.platform.NbtData.ANY_NUMERIC) ? affix.getFloatOr("value_mult",0F) : 1.0f;
    }

    /** Appends condition, maker craft lore, and storied provenance lines to the item tooltip. */
    public static void appendTooltip(ItemStack stack, List<Component> tooltip) {
        ensureLoaded();
        if (!hasAffixes(stack)) return;
        CompoundTag affix = com.nirncraft.inventory.ItemData.custom(stack).getCompoundOrEmpty(TAG);

        String qId = affix.getStringOr("quality","");
        String qLabel = affix.getStringOr("quality_label","");
        QualityDef qDef = QUALITIES.get(qId);
        SynonymDef synDef = ALL_SYNONYMS.get(qLabel);
        if (qDef != null && !qId.equals(defaultQualityId)) {
            ChatFormatting qColor = qDef.tier() > 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY;
            MutableComponent qComp = synDef != null
                    ? Component.translatable(synDef.langKey())
                    : Component.translatable(qDef.langKey());
            tooltip.add(Component.translatable("tooltip.nirncraft.affix.quality_line", qComp.withStyle(qColor))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (com.nirncraft.platform.NbtData.hasType(affix, "maker", Tag.TAG_STRING)) {
            MakerDef m = MAKERS.get(affix.getStringOr("maker",""));
            if (m != null) {
                tooltip.add(Component.translatable("tooltip.nirncraft.affix.maker_line",
                        Component.translatable(m.langKey()).withStyle(ChatFormatting.YELLOW))
                        .withStyle(ChatFormatting.DARK_GRAY));
                tooltip.add(Component.translatable(m.descKey()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
        }

        if (com.nirncraft.platform.NbtData.hasType(affix, "storied", Tag.TAG_STRING)) {
            StoriedDef st = STORIED_BY_ID.get(affix.getStringOr("storied",""));
            if (st != null) {
                tooltip.add(Component.translatable("tooltip.nirncraft.affix.storied_line",
                        Component.translatable(st.langProvKey()).withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC))
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }

    // ------------------------------------------------------------------ selftest

    /**
     * Runs consistency and crafting-survival checks for R14 item affixes; returns a list of problems (empty = PASS).
     */
    public static List<String> selftest() {
        ensureLoaded();
        List<String> problems = new ArrayList<>();

        if (QUALITIES.size() < 6) problems.add("affix qualities count < 6: " + QUALITIES.size());
        if (MAKERS.size() < 12) problems.add("affix makers count < 12: " + MAKERS.size());
        if (ENCHANT_FAMILIES.size() < 8) problems.add("affix enchant families count < 8: " + ENCHANT_FAMILIES.size());
        if (STORIED.size() < 20) problems.add("affix storied entries count < 20: " + STORIED.size());
        if (EPITHETS.size() < 8) problems.add("affix epithets count < 8: " + EPITHETS.size());

        for (MakerDef m : MAKERS.values()) {
            if (m.why().isEmpty()) problems.add("maker missing why citation: " + m.id());
        }
        for (StoriedDef st : STORIED) {
            if (st.why().isEmpty()) problems.add("storied entry missing why citation: " + st.id());
            if (st.provenance().isBlank()) problems.add("storied entry missing provenance: " + st.id());
        }

        // 1. Enchantment tier ladder check ("of Embers", "of Burning", "of Scorching", "of Searing", "of Blazing", "of the Inferno")
        EnchantFamilyDef fireFam = ENCHANT_FAMILIES.get("fire");
        if (fireFam == null || fireFam.tiers().size() != 6) {
            problems.add("fire enchant family missing 6 tiers");
        } else {
            List<String> expected = List.of("of Embers", "of Burning", "of Scorching", "of Searing", "of Blazing", "of the Inferno");
            if (!fireFam.tiers().equals(expected)) {
                problems.add("fire enchant tiers mismatch: " + fireFam.tiers());
            }
        }

        // 2. Deterministic & unique storied items check
        resetWorldStoried();
        Item swordItem = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(NirnCraft.MODID, "iron_sword"));
        if (swordItem == null || swordItem == Items.AIR) swordItem = Items.IRON_SWORD;

        ItemStack detA = new ItemStack(swordItem);
        applyStoried(detA, "skyrim/whiterun", "skyrim/whiterun/bleak_falls_barrow", 0);
        String nameA = detA.getHoverName().getString();
        String sidA = storiedId(detA);

        resetWorldStoried();
        ItemStack detB = new ItemStack(swordItem);
        applyStoried(detB, "skyrim/whiterun", "skyrim/whiterun/bleak_falls_barrow", 0);
        if (!nameA.equals(detB.getHoverName().getString()) || !String.valueOf(sidA).equals(storiedId(detB))) {
            problems.add("storied hash not deterministic across resets: " + nameA + " vs " + detB.getHoverName().getString());
        }

        resetWorldStoried();
        Set<String> seenNames = new HashSet<>();
        for (int slot = 0; slot < 24; slot++) {
            ItemStack stStack = new ItemStack(swordItem);
            applyStoried(stStack, "skyrim/whiterun", "skyrim/whiterun/bleak_falls_barrow", slot);
            String hName = stStack.getHoverName().getString();
            if (!seenNames.add(hName)) {
                problems.add("duplicate storied item in same world at slot " + slot + ": " + hName);
            }
        }

        // 3. Survival across tempering & enchanting
        ItemStack craftTest = new ItemStack(swordItem);
        CompoundTag affix = com.nirncraft.platform.ItemTags.childOrEmpty(craftTest, TAG);
        affix.putString("base_name", Component.translatable(craftTest.getItem().getDescriptionId()).getString());
        affix.putInt("base_value", Math.max(1, ItemValues.base(craftTest.getItem())));
        affix.putString("quality", "well_made");
        affix.putString("quality_label", "honed");
        affix.putString("culture", "nord");
        affix.putString("region", "skyrim/whiterun");
        affix.putString("source", "boss");
        affix.putString("maker", "skyforge");
        com.nirncraft.platform.ItemTags.setChild(craftTest, TAG, affix);
        recomputeMultipliersAndRefresh(craftTest);

        String preTemperName = craftTest.getHoverName().getString();
        Tempering.setQuality(craftTest, 3);
        onTemper(craftTest);
        if (!"well_made".equals(qualityId(craftTest)) || !"skyforge".equals(makerId(craftTest))) {
            problems.add("tempering lost quality or maker NBT");
        }
        if (!craftTest.getHoverName().getString().equals(preTemperName)) {
            problems.add("tempering lost custom affix name: " + craftTest.getHoverName().getString() + " != " + preTemperName);
        }

        var fireAspect = com.nirncraft.platform.EnchantmentsAccess.holder(Enchantments.FIRE_ASPECT);
        craftTest.enchant(fireAspect, 2);
        onEnchant(craftTest, fireAspect.value(), 2, 30);
        if (!"well_made".equals(qualityId(craftTest)) || !"skyforge".equals(makerId(craftTest))) {
            problems.add("enchanting lost quality or maker NBT");
        }
        String postEnchantName = craftTest.getHoverName().getString();
        if (!postEnchantName.contains("Skyforge") || (!postEnchantName.contains("of Searing") && !postEnchantName.contains("of Blazing"))) {
            problems.add("enchanted affix name missing maker or tiered suffix: " + postEnchantName);
        }
        if (Tempering.quality(craftTest) != 3) {
            problems.add("enchanting lost tempering quality");
        }

        resetWorldStoried();
        return problems;
    }
}
