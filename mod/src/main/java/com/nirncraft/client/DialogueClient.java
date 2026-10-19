package com.nirncraft.client;

import com.nirncraft.client.screen.DialogueScreen;
import com.nirncraft.dialogue.DialoguePackets;
import net.minecraft.client.Minecraft;

/** Client-only entry point for dialogue packets (keeps client classes out of common code). */
public final class DialogueClient {
    private DialogueClient() {}

    public static void open(DialoguePackets.OpenDialogue msg) {
        DialogueCamera.start(msg.entityId());
        Minecraft.getInstance().gui.setScreen(new DialogueScreen(msg));
    }
}
