package com.nirncraft.roads;

import com.nirncraft.NirnCraft;
import com.nirncraft.survival.Divine;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;

/**
 * Wayside shrines of the Divines along the highways: which Divine a land honours. Nords favour Talos, Kynareth and
 * Mara; Imperials the whole pantheon with Akatosh and Stendarr first; Bretons Julianos and Dibella; Altmer Auri-El
 * (Akatosh) and Redguards Tu'whacca (Arkay). Dunmer, Argonian, Khajiit and Bosmer lands keep their own gods and get
 * no Divine shrines. Praying works like any other shrine (the survival module's blessings).
 */
final class Wayshrines {
    private static final Divine[] NORD = {Divine.TALOS, Divine.KYNARETH, Divine.MARA, Divine.ARKAY, Divine.TALOS};
    private static final Divine[] IMPERIAL = {Divine.AKATOSH, Divine.STENDARR, Divine.ZENITHAR, Divine.MARA, Divine.ARKAY,
            Divine.DIBELLA, Divine.JULIANOS, Divine.KYNARETH};
    private static final Divine[] BRETON = {Divine.JULIANOS, Divine.DIBELLA, Divine.MARA, Divine.STENDARR};
    private static final Divine[] ALTMER = {Divine.AKATOSH, Divine.JULIANOS};
    private static final Divine[] REDGUARD = {Divine.ARKAY, Divine.ZENITHAR, Divine.STENDARR};

    private Wayshrines() {}

    /** The Divine (ordinal) honoured by a shrine at (x, z), or -1 for lands without Divine shrines. */
    static int divineFor(int x, int z) {
        Divine[] list = switch (RoadStyle.cultureAt(x, z)) {
            case NORD, ATMORAN -> NORD;
            case IMPERIAL -> IMPERIAL;
            case BRETON -> BRETON;
            case ALTMER -> ALTMER;
            case REDGUARD, YOKUDAN -> REDGUARD;
            default -> null;
        };
        if (list == null) return -1;
        return list[Paver.roll(x, z) % list.length].ordinal();
    }

    /** The shrine block of a Divine (a lectern if the survival module's shrine is missing). */
    static Block block(int divine) {
        Divine d = divine >= 0 && divine < Divine.VALUES.length ? Divine.VALUES[divine] : Divine.TALOS;
        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(NirnCraft.MODID, d.shrineId()));
        return b == null || b == Blocks.AIR ? Blocks.LECTERN : b;
    }
}
