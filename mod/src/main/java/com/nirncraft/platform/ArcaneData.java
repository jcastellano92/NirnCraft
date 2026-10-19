package com.nirncraft.platform;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.nirncraft.inventory.ItemData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Native registry-aware codecs for authored arcane payloads. No world registry is retained. */
public final class ArcaneData {
    private ArcaneData() {}

    public static List<MobEffectInstance> effects(ItemStack stack) {
        List<MobEffectInstance> effects = new ArrayList<>();
        stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects().forEach(effects::add);
        return effects;
    }

    public static int color(Iterable<MobEffectInstance> effects) {
        return PotionContents.getColorOptional(effects).orElse(PotionContents.BASE_POTION_COLOR) & 0xFFFFFF;
    }

    public static CompoundTag saveEffect(MobEffectInstance effect, HolderLookup.Provider registries) {
        return (CompoundTag) MobEffectInstance.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), effect).getOrThrow();
    }
    public static CompoundTag saveEffect(MobEffectInstance effect) {
        return saveEffect(effect, ItemData.currentRegistries());
    }
    public static @Nullable MobEffectInstance loadEffect(CompoundTag tag, HolderLookup.Provider registries) {
        return MobEffectInstance.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), tag).result().orElse(null);
    }

    public static String writeComponent(Component component, HolderLookup.Provider registries) {
        return ComponentSerialization.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), component).getOrThrow().toString();
    }
    public static String writeComponent(Component component) {
        var contextFree = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, component).result();
        if (contextFree.isPresent()) return contextFree.get().toString();
        return writeComponent(component, ItemData.currentRegistries());
    }
    public static Component readComponent(String json, HolderLookup.Provider registries) {
        return ComponentSerialization.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseString(json)).getOrThrow();
    }
    public static Component readComponent(String json) {
        var contextFree = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result();
        if (contextFree.isPresent()) return contextFree.get();
        return readComponent(json, ItemData.currentRegistries());
    }
}
