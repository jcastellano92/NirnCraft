package com.skycraft.client;


/**
 * Contract 7 entry point (docs/PLAYTEST-1.md): opens the character and status page.
 *
 * <p>STUB: workstream A replaces the body; the signature is fixed.
 */
public final class CharacterClient {
    private CharacterClient() {}

    public static void open() {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new com.skycraft.client.screen.CharacterStatusScreen());
    }
}
