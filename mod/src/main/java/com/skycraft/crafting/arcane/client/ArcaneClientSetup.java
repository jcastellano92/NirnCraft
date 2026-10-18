package com.skycraft.crafting.arcane.client;

import com.skycraft.Skycraft;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crafting.arcane.item.SoulGemItem;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.function.Supplier;

/** Mod-bus client setup: the {@code skycraft:filled} item property that switches soul gems to their glowing model. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class ArcaneClientSetup {
    public static final Identifier FILLED = Identifier.fromNamespaceAndPath(Skycraft.MODID, "filled");

    private ArcaneClientSetup() {}


}
