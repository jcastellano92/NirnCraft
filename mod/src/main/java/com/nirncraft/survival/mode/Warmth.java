package com.nirncraft.survival.mode;

import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * Warmth rating of worn armor and clothing (Skyrim Survival Mode). Each piece counts by its slot and its warmth class:
 * cold-weather gear (fur, hide, Nordic, Stalhrim, bear, wolf, Stormcloak...) is warm, ordinary armor is average, and
 * fancy metal and glass armor (glass, ebony, Daedric, dragon armor) keeps little heat. Hoods and cloaks add their own
 * warmth. Items are classified by registry id keywords, so armor added by other modules is rated without a compile
 * dependency; the item tags {@code nirncraft:warmth/warm|normal|poor|cloak} and {@link #register} override that.
 */
public final class Warmth {
    /** Warmth per slot (head, chest, legs, feet) of each class. */
    public enum Kind {
        WARM(25, 70, 25, 25),
        NORMAL(12, 40, 12, 12),
        CLOTHING(10, 34, 10, 10),
        POOR(8, 28, 8, 8),
        NONE(0, 0, 0, 0);

        final int head, chest, legs, feet;

        Kind(int head, int chest, int legs, int feet) {
            this.head = head;
            this.chest = chest;
            this.legs = legs;
            this.feet = feet;
        }

        int forSlot(EquipmentSlot slot) {
            return switch (slot) {
                case HEAD -> head;
                case CHEST -> chest;
                case LEGS -> legs;
                case FEET -> feet;
                default -> 0;
            };
        }
    }

    public static final TagKey<Item> WARM = tag("warmth/warm");
    public static final TagKey<Item> NORMAL = tag("warmth/normal");
    public static final TagKey<Item> POOR = tag("warmth/poor");
    public static final TagKey<Item> CLOAK = tag("warmth/cloak");

    /** A hood keeps the head warm whatever it is made of. */
    public static final int HOOD = 30;
    /** A cloak worn over the armor (any slot) adds this much. */
    public static final int CLOAK_BONUS = 40;
    /** The highest rating that still counts (a full fur set with hood and cloak is about 190). */
    public static final int MAX = 250;

    private static final String[] WARM_WORDS = {"fur", "hide", "nordic", "nord_", "stalhrim", "bear", "wolf", "stormcloak", "skaal",
            "ancient_nord", "forsworn", "pelt", "wool", "snow", "fox", "sabre", "mammoth", "winter", "frost_"};
    private static final String[] POOR_WORDS = {"glass", "ebony", "daedric", "dragonplate", "dragonscale", "golden", "gold_", "diamond",
            "netherite", "elven", "dwarven", "falmer", "madness", "aetherial"};
    private static final String[] CLOTH_WORDS = {"robe", "clothes", "tunic", "dress", "shirt", "outfit", "garb", "circlet", "shoes"};

    private static final Map<Item, Kind> OVERRIDES = new HashMap<>();

    private Warmth() {}

    private static TagKey<Item> tag(String name) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, name));
    }

    /** Sets the warmth class of an item (other modules: call during common setup). */
    public static synchronized void register(Item item, Kind kind) {
        OVERRIDES.put(item, kind);
    }

    /** Total warmth rating of what the player wears, 0..{@link #MAX}. */
    public static int of(Player player) {
        int total = 0;
        boolean cloak = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            total += ofItem(stack, slot);
            if (isCloak(stack)) cloak = true;
        }
        if (cloak) total += CLOAK_BONUS;
        return Math.min(MAX, total);
    }

    /** Warmth of one worn piece in {@code slot} (its natural slot for the tooltip). */
    public static int ofItem(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (slot == EquipmentSlot.HEAD && isHood(item)) return HOOD;
        Kind kind = kind(stack);
        return kind.forSlot(slot);
    }

    /** Tooltip value: warmth of the item in its own armor slot, or 0 if it isn't wearable. */
    public static int tooltipValue(ItemStack stack) {
        if (com.nirncraft.inventory.EquipmentData.isArmor(stack)) {
            int v = ofItem(stack, com.nirncraft.inventory.EquipmentData.slot(stack));
            return isCloak(stack) ? v + CLOAK_BONUS : v;
        }
        return isCloak(stack) ? CLOAK_BONUS : 0;
    }

    public static Kind kind(ItemStack stack) {
        Item item = stack.getItem();
        synchronized (Warmth.class) {
            Kind o = OVERRIDES.get(item);
            if (o != null) return o;
        }
        if (stack.is(WARM)) return Kind.WARM;
        if (stack.is(POOR)) return Kind.POOR;
        if (stack.is(NORMAL)) return Kind.NORMAL;
        if (!(com.nirncraft.inventory.EquipmentData.isArmor(stack)) && !stack.is(CLOAK)) return Kind.NONE;
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        String path = id == null ? "" : id.getPath();
        if (has(path, WARM_WORDS)) return Kind.WARM;
        if (has(path, POOR_WORDS)) return Kind.POOR;
        if (has(path, CLOTH_WORDS)) return Kind.CLOTHING;
        // vanilla leather is Skyrim leather; chainmail, iron and steel are ordinary armor
        return Kind.NORMAL;
    }

    public static boolean isCloak(ItemStack stack) {
        if (stack.is(CLOAK)) return true;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getPath().contains("cloak") && !id.getPath().contains("spell");
    }

    private static boolean isHood(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && (id.getPath().contains("hood") || id.getPath().contains("cowl"));
    }

    private static boolean has(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }
}
