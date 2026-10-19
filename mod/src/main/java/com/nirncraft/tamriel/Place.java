package com.nirncraft.tamriel;

import net.minecraft.network.chat.Component;

/**
 * A named place on the Tamriel map with fixed block coordinates. See docs/TAMRIEL.md section 6.
 *
 * @param variant optional builder hint, e.g. a dungeon theme ({@code barrow}) or a unique layout ({@code whiterun});
 *                empty when unused
 */
public record Place(String id, Component name, PlaceType type, String culture, String regionId,
                    int x, int z, int radius, String variant) {

    public long distSqr(int bx, int bz) {
        long dx = bx - x;
        long dz = bz - z;
        return dx * dx + dz * dz;
    }
}
