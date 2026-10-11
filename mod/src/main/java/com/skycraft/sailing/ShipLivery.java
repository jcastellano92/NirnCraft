package com.skycraft.sailing;

/**
 * How a ship is painted and rigged: the hull tint, the sail and its colours, the pennant. Synced with the ship; the
 * sea traffic of {@code com.skycraft.seas} flies them (merchants, the Imperial navy, Nord raiders, pirates of the Sea
 * of Ghosts, the Abecean and the Iliac Bay, Maormer warships out of Pyandonea, Sload hulks off Thras, and the ghost
 * ships seen at night).
 */
public enum ShipLivery {
    /** The ship as built: Nord red-and-white or the Imperial dragon. */
    DEFAULT(1f, 1f, 1f, 1f, -1, 0xC03030),
    /** Plain merchant canvas, a trading house's green pennant. */
    MERCHANT(1f, 0.97f, 0.92f, 1f, 0, 0x3A8A40),
    /** The Imperial navy: red sail with the gold dragon, gilt hull. */
    NAVY(1f, 0.95f, 0.9f, 1f, 1, 0xD8B040),
    /** Nord raiders: the striped sail of the Sea of Ghosts. */
    NORD(0.95f, 0.95f, 0.95f, 1f, -1, 0x2A4A8A),
    /** Pirates: tarred hull, black patched sail with the skull. */
    PIRATE(0.62f, 0.58f, 0.55f, 1f, 2, 0x111111),
    /** Maormer: pale sea-elf hull, the blue and white serpent sail. */
    MAORMER(0.82f, 0.95f, 1.0f, 1f, 3, 0x3070D0),
    /** Sload: slimy green-black hulk, rotten sail. */
    SLOAD(0.45f, 0.62f, 0.42f, 1f, 4, 0x6A8A30),
    /** A ghost ship: pale, translucent, glowing faintly. */
    GHOST(0.75f, 0.92f, 1.0f, 0.55f, 5, 0x8AE0D0);

    public static final ShipLivery[] VALUES = values();

    /** Hull tint and alpha (alpha < 1 draws translucent). */
    public final float r, g, b, a;
    /** Sail picture in the seas sail atlas (-1 = the ship type's own sail). */
    public final int sail;
    /** Pennant colour. */
    public final int pennant;

    ShipLivery(float r, float g, float b, float a, int sail, int pennant) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.sail = sail;
        this.pennant = pennant;
    }

    public static ShipLivery byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : DEFAULT;
    }
}
