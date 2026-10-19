package com.nirncraft.client.pinned;

/**
 * A pinned box of blocks (both corners inclusive, normalised so x0 <= x1 and so on), in the world coordinates it was
 * taken from. Its cells are indexed x fastest, then z, then y, the order AtlasLabExport's volumes use.
 */
public record PinBox(int x0, int y0, int z0, int x1, int y1, int z1) {
    /** The largest box a pin takes (a town block with its yard; the exports' own limit is far above it). */
    public static final long MAX_CELLS = 4L << 20;

    public PinBox {
        if (x0 > x1 || y0 > y1 || z0 > z1) throw new IllegalArgumentException("box corners not normalised");
    }

    /** The box spanned by two corners in any order. */
    public static PinBox of(int ax, int ay, int az, int bx, int by, int bz) {
        return new PinBox(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by),
                Math.max(az, bz));
    }

    /**
     * Six coordinates as a command gives them: absolute, or relative to (px, py, pz) with a leading '~' ("~", "~-12",
     * "~4"), the way Minecraft's own commands read them.
     */
    public static PinBox parse(String[] six, int px, int py, int pz) {
        if (six.length != 6) throw new IllegalArgumentException("a box is six coordinates: x0 y0 z0 x1 y1 z1");
        int[] base = {px, py, pz};
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = coord(six[i], base[i % 3]);
        return of(v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /** One coordinate: "12", "~" or "~-3" (relative to base). */
    public static int coord(String token, int base) {
        String t = token.trim();
        if (t.startsWith("~")) return base + (t.length() == 1 ? 0 : Integer.parseInt(t.substring(1)));
        return Integer.parseInt(t);
    }

    public int sx() { return x1 - x0 + 1; }
    public int sy() { return y1 - y0 + 1; }
    public int sz() { return z1 - z0 + 1; }
    public long cells() { return (long) sx() * sy() * sz(); }

    public boolean contains(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }

    /** The cell of a block inside the box (x fastest, then z, then y); -1 outside. */
    public int index(int x, int y, int z) {
        if (!contains(x, y, z)) return -1;
        return (x - x0) + sx() * ((z - z0) + sz() * (y - y0));
    }

    /** The box's centre in block space (a box from 0 to 15 is centred on 8.0: blocks span [x, x + 1)). */
    public double cx() { return (x0 + x1 + 1) * 0.5; }
    public double cy() { return (y0 + y1 + 1) * 0.5; }
    public double cz() { return (z0 + z1 + 1) * 0.5; }

    /** Half the box's diagonal: every block of it lies within this distance of the centre. */
    public double radius() {
        return 0.5 * Math.sqrt((double) sx() * sx() + (double) sy() * sy() + (double) sz() * sz());
    }

    /** The 16-block sections the box touches, as section coordinates (inclusive). */
    public int sectionX0() { return x0 >> 4; }
    public int sectionY0() { return y0 >> 4; }
    public int sectionZ0() { return z0 >> 4; }
    public int sectionX1() { return x1 >> 4; }
    public int sectionY1() { return y1 >> 4; }
    public int sectionZ1() { return z1 >> 4; }

    @Override
    public String toString() {
        return x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1;
    }
}
