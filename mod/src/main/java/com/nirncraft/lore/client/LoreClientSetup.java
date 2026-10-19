package com.nirncraft.lore.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.nirncraft.NirnCraft;
import com.nirncraft.client.SkyKeys;
import com.nirncraft.lore.LoreBookItem;
import com.nirncraft.lore.LoreModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Mod-bus client registration for the lore module: stone power key, HUD banner, book cover models, text reload. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class LoreClientSetup {
    /** Uses the active Standing Stone's once-a-day power. Owned by the lore module. */
    public static final KeyMapping STONE_POWER = new KeyMapping("key.nirncraft.stone_power", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), SkyKeys.CATEGORY);

    public static final Identifier COVER = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "cover");

    private LoreClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(STONE_POWER);
    }

    @SubscribeEvent
    public static void overlays(RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "lore_stone"), StoneHud::render);
        event.registerAboveAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("nirncraft", "lore_read_prompt"), ReadPrompt::render);
    }

    @SubscribeEvent
    public static void reloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("nirncraft","book_texts"),(ResourceManagerReloadListener)manager->BookTexts.clear());
    }


}
