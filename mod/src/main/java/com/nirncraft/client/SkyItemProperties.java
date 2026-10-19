package com.nirncraft.client;

import com.mojang.serialization.MapCodec;
import com.nirncraft.crafting.arcane.item.SoulGemItem;
import com.nirncraft.lore.LoreBookItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;

/** Native model properties for soul gems and authored book covers. */
@EventBusSubscriber(modid="nirncraft",value=Dist.CLIENT)
public final class SkyItemProperties {
    public record Filled() implements RangeSelectItemModelProperty {
        static final MapCodec<Filled> CODEC=MapCodec.unit(new Filled());
        @Override public float get(ItemStack stack,ClientLevel level,ItemOwner owner,int seed) { return SoulGemItem.getSoul(stack)>0?1:0; }
        @Override public MapCodec<Filled> type() { return CODEC; }
    }
    public record Cover() implements RangeSelectItemModelProperty {
        static final MapCodec<Cover> CODEC=MapCodec.unit(new Cover());
        @Override public float get(ItemStack stack,ClientLevel level,ItemOwner owner,int seed) { return LoreBookItem.coverOf(stack)/10f+.01f; }
        @Override public MapCodec<Cover> type() { return CODEC; }
    }
    @SubscribeEvent public static void register(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath("nirncraft","filled"),Filled.CODEC);
        event.register(Identifier.fromNamespaceAndPath("nirncraft","cover"),Cover.CODEC);
    }
}
