package com.nirncraft.mounts;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The saddles of Nirn. A saddle is an item (hold it in your hand through the inventory like a weapon, sneak up to a
 * wild mount and use it); once fitted it sets what the mount can carry, how big its saddlebags are, how long it runs and
 * how fast it is. Which breed takes which type is {@link MountGear#accepts}. The plain type is the vanilla
 * {@code minecraft:saddle} (so every existing loot table, shop and recipe still works); the others are
 * {@link MountItems} items.
 */
public enum SaddleType {
    /** The common saddle: no extra gear. */
    PLAIN("plain", 0f, 20f, 1.00f, 0.00f, 0.00f, 60),
    /** Travel or pack saddle: broad panniers. Carries and stores the most, tires the mount sooner and is a little slower. */
    PACK("pack", 60f, 80f, 0.95f, -0.04f, 0.00f, 220),
    /** War saddle: stirrups, a high cantle and a lance rest. A longer gallop and a harder charge. */
    WAR("war", 10f, 30f, 1.10f, 0.02f, 0.15f, 400),
    /** Noble or Imperial court saddle: light, fine and fast; a good bag and a rider who is seen. */
    NOBLE("noble", 20f, 50f, 1.15f, 0.05f, 0.05f, 900);

    public static final SaddleType[] VALUES = values();

    public final String id;
    /** Extra carry weight (kg, the unit of {@code ItemWeights}) the mount takes from its rider while ridden. */
    public final float carryKg;
    /** Saddlebag capacity (kg) of a mount with two bag rows; smaller and larger beasts scale it ({@link MountGear#bagKg}). */
    public final float bagKg;
    /** Multiplier of the mount's stamina. */
    public final float staminaMult;
    /** Movement speed modifier (multiplicative, +0.05 is five percent faster). */
    public final float speedMod;
    /** Extra damage of a mounted strike (added to the charge bonus). */
    public final float damageBonus;
    /** Price at a stable. */
    public final int cost;

    SaddleType(String id, float carryKg, float bagKg, float staminaMult, float speedMod, float damageBonus, int cost) {
        this.id = id;
        this.carryKg = carryKg;
        this.bagKg = bagKg;
        this.staminaMult = staminaMult;
        this.speedMod = speedMod;
        this.damageBonus = damageBonus;
        this.cost = cost;
    }

    public Item item() {
        return switch (this) {
            case PLAIN -> Items.SADDLE;
            case PACK -> MountItems.PACK_SADDLE.get();
            case WAR -> MountItems.WAR_SADDLE.get();
            case NOBLE -> MountItems.NOBLE_SADDLE.get();
        };
    }

    public ItemStack stack() {
        return new ItemStack(item());
    }

    public Component displayName() {
        return Component.translatable("mounts.nirncraft.saddle." + id);
    }

    /** The type of a saddle item, or null when the stack is not a saddle. */
    @Nullable
    public static SaddleType of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item it = stack.getItem();
        if (it == Items.SADDLE) return PLAIN;
        if (it instanceof MountItems.SaddleItem s) return s.type;
        return null;
    }

    @Nullable
    public static SaddleType byId(@Nullable String id) {
        if (id == null || id.isEmpty()) return null;
        String low = id.toLowerCase(Locale.ROOT);
        for (SaddleType t : VALUES) if (t.id.equals(low)) return t;
        return null;
    }
}
