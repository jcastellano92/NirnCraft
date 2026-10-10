package com.skycraft.tamriel.biome.client;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.biome.BiomeBlocks;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.FoliageColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Biome tints: Graht-oak and Hist leaves take the biome's foliage colour (their textures are grey-scale), so
 * Valenwood and Black Marsh each keep their own greens. The other Tamriel plants are pre-coloured.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BiomeBlockColors {
    private BiomeBlockColors() {}

    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> level != null && pos != null
                        ? BiomeColors.getAverageFoliageColor(level, pos) : FoliageColor.getDefaultColor(),
                BiomeBlocks.GRAHT_OAK_LEAVES.get(), BiomeBlocks.HIST_LEAVES.get());
    }

    @SubscribeEvent
    public static void onItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tint) -> 0x4E9A32, BiomeBlocks.GRAHT_OAK_LEAVES.get());
        event.register((stack, tint) -> 0x5A7A3A, BiomeBlocks.HIST_LEAVES.get());
    }
}
