package com.nirncraft.render.quality;

/**
 * CPU reservation for the far field's background work when this machine is also a server: atlas's exact columns
 * (TamrielHeight per column on a small pool) compete with the integrated server's ticks and chunk generation. Pure
 * policy; the client and the tile server apply it.
 *
 * <ul>
 *   <li>Single player (no guests) and a remote client: a quarter of the cores for the client's columns, as before.</li>
 *   <li>Hosting LAN guests (an integrated server opened to LAN with someone connected): the client's own columns and
 *   the tile server's (which compute for the guests; the tile cache is shared, so each tile is made once) share an
 *   eighth of the cores each, one thread each while the server's tick runs long.</li>
 *   <li>A dedicated server: a quarter of the cores while ticks are short, one thread under load.</li>
 * </ul>
 * Pregeneration (tiles for guests before they look) runs only while ticks are short, less often as they lengthen.
 */
public final class HostLoad {
    /** A tick this long (ms) means the server needs every core it has. */
    public static final double BUSY_MS = 40;
    /** Below this the server is idle enough to pregenerate. */
    public static final double IDLE_MS = 25;

    private HostLoad() {}

    /** Column threads for the client's exact far field. */
    public static int clientColumnThreads(int cores, boolean hostingGuests, double serverMspt) {
        int quarter = Math.max(1, cores / 4);
        if (!hostingGuests) return quarter;
        if (serverMspt > BUSY_MS) return 1;
        return Math.max(1, cores / 8);
    }

    /** Column threads for the tile server (dedicated or integrated). */
    public static int serverColumnThreads(int cores, boolean dedicated, boolean hostingGuests, double mspt) {
        if (mspt > BUSY_MS) return 1;
        if (dedicated) return Math.max(1, cores / 4);
        return hostingGuests ? Math.max(1, cores / 8) : 1;  // integrated without guests: the client makes its own
    }

    /** Ticks between pregeneration attempts (0 = none now). */
    public static int pregenIntervalTicks(double mspt) {
        if (mspt > IDLE_MS) return 0;
        return mspt < 12 ? 20 : 60;
    }

    /** Extra milliseconds the client's far-field worker rests between passes while hosting a busy server. */
    public static int clientWorkerRestMs(boolean hostingGuests, double serverMspt) {
        if (!hostingGuests) return 0;
        return serverMspt > BUSY_MS ? 200 : serverMspt > IDLE_MS ? 40 : 0;
    }
}
