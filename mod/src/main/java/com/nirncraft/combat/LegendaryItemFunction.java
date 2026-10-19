package com.nirncraft.combat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.nirncraft.arsenal.LeveledGear;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** Loot function that generates a Fallout-4 style randomized legendary item (Contract 4). */
public class LegendaryItemFunction extends LootItemConditionalFunction {
    protected LegendaryItemFunction(java.util.Optional<net.minecraft.core.Holder<LootItemCondition>> conditions) {
        super(conditions);
    }

    @Override
    public MapCodec<? extends LootItemConditionalFunction> codec() {
        return LeveledGear.LEGENDARY_ITEM.get();
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        return LegendaryItem.generate(context);
    }

    public static final MapCodec<LegendaryItemFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i).apply(i, LegendaryItemFunction::new));
}

