package com.nirncraft.mounts.client;

import com.nirncraft.mounts.MountView;

/** What the client last heard about its own mount, for the container screen's stat sheet. */
public final class MountClientState {
    /** The sheet of the mount whose menu is (about to be) open. */
    public static MountView sheet;
    /** The server wants the screen to start on the sheet (an empty-handed sneak-use). */
    public static boolean openOnSheet;

    private MountClientState() {}

    /** True once after the server asked for the sheet first. */
    public static boolean takeOpenOnSheet() {
        boolean v = openOnSheet;
        openOnSheet = false;
        return v;
    }
}
