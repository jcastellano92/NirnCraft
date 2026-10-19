package com.nirncraft.client;

/** Client-only transient state received from the server. */
public final class ClientState {
    public static byte vitalsFlags;
    public static long lastFullVitalsTime;

    private ClientState() {}

    public static boolean has(byte flag) {
        return (vitalsFlags & flag) != 0;
    }
}
