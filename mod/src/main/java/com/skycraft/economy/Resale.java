package com.skycraft.economy;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.item.ArtifactItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Map;

/**
 * What a shop may put back on its shelves. What a player sells goes on sale to every player afterwards
 * ({@link Shop#addSold}); some things must not: they are keepsakes, held for the seller alone (the buy-back list,
 * {@link Buyback#recordHeld}) and never shown to anybody else.
 *
 * <p>Keepsakes: the artifacts and anything in {@code #skycraft:no_resale} (house keys, monster trophies), legendary
 * items, anything carrying a curse (binding, vanishing, a modded curse), and player-written books. Quest items,
 * coins, conjured weapons and filled containers are not sold at all ({@link Barter#sellable}).</p>
 */
public final class Resale {
    public static final TagKey<Item> NO_RESALE = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Skycraft.MODID, "no_resale"));

    private Resale() {}

    /** True when the shop must keep this for the seller only instead of selling it on. */
    public static boolean isKeepsake(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item instanceof ArtifactItem || stack.is(NO_RESALE)) return true;
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        // a written book is somebody's words; a blank book and quill is just paper
        if (item instanceof WrittenBookItem) return true;
        if (item instanceof WritableBookItem && stack.getOrDefault(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT, net.minecraft.world.item.component.WritableBookContent.EMPTY).pages().size() > 0) return true;
        if (tag != null && (tag.getBooleanOr("skycraft_is_legendary",false) || tag.getBooleanOr("skycraft_unique",false))) return true;
        if (stack.isEnchanted() || stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)) {
            for (var e : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
                if (e.getKey().is(net.minecraft.tags.EnchantmentTags.CURSE)) return true;
            }
        }
        return false;
    }
}
