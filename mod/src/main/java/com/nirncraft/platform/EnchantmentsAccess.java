package com.nirncraft.platform;

import com.nirncraft.inventory.ItemData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;

/** Resolves native datapack enchantments against the caller's world without retaining its registries. */
public final class EnchantmentsAccess {
    private EnchantmentsAccess() {}

    public static Holder<Enchantment> holder(ResourceKey<Enchantment> key, HolderLookup.Provider registries) {
        return registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }
    public static Holder<Enchantment> holder(ResourceKey<Enchantment> key) {
        return holder(key, ItemData.currentRegistries());
    }
    public static @Nullable Holder<Enchantment> find(Identifier id, HolderLookup.Provider registries) {
        return registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, canonical(id)))
                .map(value -> (Holder<Enchantment>) value).orElse(null);
    }
    public static @Nullable Enchantment lookup(Identifier id, HolderLookup.Provider registries) {
        Holder<Enchantment> found = find(id, registries);
        return found == null ? null : found.value();
    }
    public static @Nullable Enchantment lookup(Identifier id) {
        return lookup(id, ItemData.currentRegistries());
    }
    public static Holder<Enchantment> holder(Enchantment enchantment, HolderLookup.Provider registries) {
        return registries.lookupOrThrow(Registries.ENCHANTMENT).listElements()
                .filter(value -> value.value() == enchantment).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Enchantment does not belong to the caller's world registry"));
    }
    public static Holder<Enchantment> holder(Enchantment enchantment) {
        return holder(enchantment, ItemData.currentRegistries());
    }
    public static Identifier id(Enchantment enchantment, HolderLookup.Provider registries) {
        return holder(enchantment, registries).unwrapKey().orElseThrow().identifier();
    }
    public static Identifier id(Enchantment enchantment) {
        return id(enchantment, ItemData.currentRegistries());
    }
    private static Identifier canonical(Identifier id) {
        // This vanilla id changed after the old 1.20.1 authored definitions.
        return id.equals(Identifier.withDefaultNamespace("sweeping"))
                ? Identifier.withDefaultNamespace("sweeping_edge") : id;
    }
}
