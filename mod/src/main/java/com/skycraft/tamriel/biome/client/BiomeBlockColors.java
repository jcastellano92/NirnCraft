package com.skycraft.tamriel.biome.client;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.FoliageColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Biome tints: Graht-oak and Hist leaves take the biome's foliage colour (their textures are grey-scale), so
 * Valenwood and Black Marsh each keep their own greens. The other Tamriel plants are pre-coloured.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class BiomeBlockColors {
    private BiomeBlockColors() {}

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.BlockTintSources event) {
        event.register(java.util.List.of(net.minecraft.client.color.block.BlockTintSources.foliage()),
            BiomeBlocks.GRAHT_OAK_LEAVES.get(),BiomeBlocks.HIST_LEAVES.get());
    }
}
