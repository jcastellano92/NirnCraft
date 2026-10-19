package com.nirncraft.crafting.arcane.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.crafting.arcane.ArcaneRegistry;
import com.nirncraft.crafting.arcane.item.SoulGemItem;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.function.Supplier;

/** Mod-bus client setup: the {@code nirncraft:filled} item property that switches soul gems to their glowing model. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class ArcaneClientSetup {
    public static final Identifier FILLED = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "filled");

    private ArcaneClientSetup() {}


}
