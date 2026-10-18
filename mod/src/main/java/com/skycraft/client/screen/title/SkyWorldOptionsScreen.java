package com.skycraft.client.screen.title;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WorldOptionsScreen;
import net.minecraft.world.level.Level;

/** Use vanilla's current LAN publishing, permissions and world-options implementation. */
public final class SkyWorldOptionsScreen extends WorldOptionsScreen {
    public SkyWorldOptionsScreen(Screen parent, Level level) {
        super(parent, level);
    }
}
