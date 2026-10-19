package com.nirncraft.client.screen;

import com.mojang.blaze3d.platform.InputConstants;

/** Screen action codes are also used by NirnCraft's container packets. Native widgets receive the original SDL event. */
public final class UiInput {
    private UiInput() {}

    public static int actionButton(int nativeButton) {
        return switch (nativeButton) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> nativeButton;
        };
    }

    public static boolean shiftDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }
}
