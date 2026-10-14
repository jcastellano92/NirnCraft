package com.skycraft.economy;

import com.skycraft.Skycraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A kind of shop. Each type sells from the item tag {@code #skycraft:merchant/<id>} and buys the item categories
 * it deals in, anything in its stock tag, and a few extra tags (ores for blacksmiths, pelts for hunters...).
 */
public enum ShopType {
    BLACKSMITH(EnumSet.of(ItemCategory.WEAPONS, ItemCategory.APPAREL), false, "merchant/ores"),
    APOTHECARY(EnumSet.of(ItemCategory.POTIONS, ItemCategory.INGREDIENTS), false),
    GENERAL(EnumSet.noneOf(ItemCategory.class), true),
    ARCANE(EnumSet.of(ItemCategory.BOOKS, ItemCategory.POTIONS, ItemCategory.INGREDIENTS), false),
    FOOD(EnumSet.of(ItemCategory.FOOD), false, "merchant/crops"),
    FENCE(EnumSet.noneOf(ItemCategory.class), true),
    HUNTER(EnumSet.noneOf(ItemCategory.class), false, "merchant/pelts"),
    FISHER(EnumSet.noneOf(ItemCategory.class), false, "minecraft:fishes"),
    MASON(EnumSet.noneOf(ItemCategory.class), false),
    LIBRARY(EnumSet.of(ItemCategory.BOOKS), false),
    FLETCHER(EnumSet.noneOf(ItemCategory.class), false),
    EXOTIC(EnumSet.noneOf(ItemCategory.class), true),
    /** Jewellers buy and sell rings, necklaces, circlets, gems and precious metals. */
    JEWELER(EnumSet.noneOf(ItemCategory.class), false, "jewelry", "merchant/gems"),
    /** Tailors and clothiers: wool, leather and hide, and the light clothing made of them (#skycraft:merchant/tailor). */
    TAILOR(EnumSet.noneOf(ItemCategory.class), false);

    public static final TagKey<Item> CROPS = tag("merchant/crops");
    public static final TagKey<Item> PELTS = tag("merchant/pelts");
    public static final TagKey<Item> ORES = tag("merchant/ores");
    /** Rings, amulets, necklaces, circlets and the Dragon Priest masks' lesser kin: what jewellers deal in. */
    public static final TagKey<Item> JEWELRY = tag("jewelry");

    private final Set<ItemCategory> buys;
    private final boolean buysAnything;
    private final List<TagKey<Item>> extraBuyTags = new ArrayList<>();
    public final TagKey<Item> stockTag;

    ShopType(Set<ItemCategory> buys, boolean buysAnything, String... extraBuyTags) {
        this.buys = buys;
        this.buysAnything = buysAnything;
        this.stockTag = tag("merchant/" + id());
        for (String t : extraBuyTags) {
            this.extraBuyTags.add(t.contains(":")
                    ? TagKey.create(Registries.ITEM, new ResourceLocation(t))
                    : tag(t));
        }
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, new ResourceLocation(Skycraft.MODID, path));
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ShopType byId(String id) {
        for (ShopType t : values()) if (t.id().equals(id)) return t;
        return null;
    }

    /** Whether a shop of this type deals in this kind of merchandise. */
    public boolean buys(ItemStack stack) {
        if (buysAnything) return true;
        if (buys.contains(ItemCategory.of(stack))) return true;
        if (stack.is(stockTag)) return true;
        for (TagKey<Item> t : extraBuyTags) if (stack.is(t)) return true;
        return false;
    }

    /** Every item this type can stock (resolved from its tag; empty while tags are not loaded). */
    public List<Item> stockItems() {
        return items(stockTag);
    }

    /**
     * The province goods of this type: {@code #skycraft:merchant/<type>/<gear key>} (an apothecary in Morrowind sells
     * kwama eggs and saltrice, a Khajiit caravan moon sugar). Empty if the province has none.
     */
    public List<Item> provinceItems(String gearKey) {
        return items(tag("merchant/" + id() + "/" + gearKey));
    }

    private static List<Item> items(TagKey<Item> tag) {
        List<Item> out = new ArrayList<>();
        for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
            Item item = h.value();
            if (item != Items.AIR) out.add(item);
        }
        return out;
    }
}
