package com.skycraft.inventory.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.skycraft.client.SkyKeys;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Key bindings owned by the inventory module. FAVORITES defaults to Q (Skyrim's favorites key); vanilla's drop key is
 * unbound on first launch by the core's KeyConflictResolver.
 */
public final class InventoryKeys {
    public static final KeyMapping FAVORITES = new KeyMapping("key.skycraft.favorites", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q, SkyKeys.CATEGORY);

    private InventoryKeys() {}
}
