package com.skycraft.tamriel;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Region lookup for gameplay code. Overworld positions come from {@link TamrielMap}; other dimensions (Oblivion,
 * Sovngarde, the jail) get a region named after the dimension.
 *
 * <p>CONTRACT STUB: the Phase 2 regions workstream loads {@code skycraft/tamriel/regions.json}; signatures are
 * fixed.</p>
 */
public final class Regions {
    private static final Region WHITERUN = new Region("skyrim/whiterun", "skyrim", "hold", "nord",
            Component.translatable("region.skycraft.skyrim.whiterun"), 1, 12, "whiterun_tundra", "skyrim",
            "skyrim_temperate", "whiterun");

    private Regions() {}

    public static Region at(Level level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) {
            String dim = level.dimension().location().getPath();
            return new Region(dim, dim, "dimension", "none", Component.literal(dim), 1, 60, dim, dim, dim, null);
        }
        return TamrielMap.region(pos.getX(), pos.getZ());
    }

    public static Optional<Region> byId(String id) {
        return WHITERUN.id().equals(id) ? Optional.of(WHITERUN) : Optional.empty();
    }

    /** Authored regions only (generated regions are created on demand). */
    public static Collection<Region> all() {
        return List.of(WHITERUN);
    }

    /** Used while the region table is loading or for unknown indices. */
    public static Region fallback() {
        return WHITERUN;
    }
}
