package com.nirncraft.inventory.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.nirncraft.client.SkyKeys;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * Key bindings owned by the inventory module. FAVORITES defaults to R (the owner's layout; the quest journal took Q);
 * vanilla's drop key is unbound on first launch by the core's KeyConflictResolver.
 */
public final class InventoryKeys {
    public static final KeyMapping FAVORITES = new KeyMapping("key.nirncraft.favorites", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD, com.mojang.blaze3d.platform.InputConstants.KEY_R, SkyKeys.CATEGORY);

    private InventoryKeys() {}
}
