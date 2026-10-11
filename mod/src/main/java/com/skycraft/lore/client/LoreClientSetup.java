package com.skycraft.lore.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.skycraft.Skycraft;
import com.skycraft.client.SkyKeys;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Mod-bus client registration for the lore module: stone power key, HUD banner, book cover models, text reload. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LoreClientSetup {
    /** Uses the active Standing Stone's once-a-day power. Owned by the lore module. */
    public static final KeyMapping STONE_POWER = new KeyMapping("key.skycraft.stone_power", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), SkyKeys.CATEGORY);

    public static final ResourceLocation COVER = new ResourceLocation(Skycraft.MODID, "cover");

    private LoreClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(STONE_POWER);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("lore_stone", StoneHud::render);
    }

    @SubscribeEvent
    public static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> BookTexts.clear());
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(LoreModule.BOOK.get(), COVER,
                (stack, level, entity, seed) -> LoreBookItem.coverOf(stack) / 10f + 0.01f));
    }
}
