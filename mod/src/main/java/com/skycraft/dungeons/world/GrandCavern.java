package com.skycraft.dungeons.world;

import com.skycraft.dungeons.world.cavern.CavernContext;
import com.skycraft.dungeons.world.cavern.CavernSelector;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A grand cavern: the epic chamber of caves, mines, lairs and the deep levels of barrows and Dwemer delves. One
 * piece 40-75 blocks across and 15-32 tall, carved as a bounded volume: a footprint (a dome over a ring of ledges and
 * a bowl, chasm, gorge, shaft, hive, city or one of the other macro-shapes) with a ring walkway at the doors' level,
 * a cross of bridges to a central platform, galleries on the wall side, columns, stalactites, flora and light, a camp
 * or den, a set piece, a lore lectern, loot and sleepers.
 *
 * <p>Variety is data: the {@link com.skycraft.dungeons.world.cavern.CavernSelector} picks one of the profiles in
 * {@code skycraft/dungeons/caverns/} (a look: blocks, fluid, flora, features, set piece, inhabitants, loot) and one of
 * its shapes from the dungeon's region, theme, depth and seed; {@link CavernGen} builds the terrain and structures and
 * {@link CavernDress} the rest. Everything is a pure function of position and the piece's seed, so every chunk
 * agrees; fluids sit in basins whose rim is never lower than the fluid's surface, so they stay sealed.</p>
 */
public final class GrandCavern {
    /** How far the cavern's piece box reaches below its rim level (the chasm's depth). */
    public static final int BELOW = 18;
    public static final int MIN_HEIGHT = 15;
    public static final int MAX_HEIGHT = 32;

    private static final ConcurrentHashMap<Long, CavernSelector.Choice> CHOICES = new ConcurrentHashMap<>();

    private GrandCavern() {}

    /** Interior height a cavern may have under ground reaching {@code surfaceTop} when its rim lies at {@code floor}. */
    public static int heightFor(int surfaceTop, int floor) {
        return Math.min(MAX_HEIGHT, surfaceTop - floor - 5);
    }

    /** The look and shape of a piece's cavern (cached: every chunk of the piece asks). */
    public static CavernSelector.Choice choose(net.minecraft.world.level.WorldGenLevel level, DungeonPiece piece) {
        var b = piece.getBoundingBox();
        long key = piece.seed * 31L + b.minX() * 7919L + b.minZ() * 104729L + piece.floor;
        CavernSelector.Choice c = CHOICES.get(key);
        if (c == null) {
            c = CavernSelector.select(CavernContext.of(level, piece));
            if (CHOICES.size() > 2048) CHOICES.clear();
            CHOICES.put(key, c);
        }
        return c;
    }

    public static void build(Painter p, DungeonPiece piece) {
        Room room = new Room(piece);
        CavernSelector.Choice choice = choose(p.level, piece);
        p.feature("room:grand_cavern");
        new CavernGen(p, room, choice).run();
    }
}
