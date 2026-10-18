package com.skycraft.fire;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Self-test of the fire module's rules, usable on its own ({@code /skycraft-fire selftest}) and from the world
 * protection's self-test ({@link #persistentFire} with {@code vanilla = true}): a big fire block placed through
 * {@link FireSites#ignite} persists (nothing puts it out, nothing spreads from it), while vanilla fire outside a
 * homestead is still extinguished by the protection.
 */
public final class FireSelfTest {
    /** Receives one check result (the protect self-test's {@code Checks::check} fits). */
    @FunctionalInterface
    public interface Sink {
        void check(String name, boolean ok, String detail);
    }

    private FireSelfTest() {}

    /** Runs the tasks queued on the server thread (the protection's delayed extinguish) before returning. */
    private static void drain(MinecraftServer server) {
        int[] n = {0};
        server.managedBlock(() -> ++n[0] > 30);
    }

    /**
     * Runs the checks at {@code at} (a free spot on the surface, outside every homestead). Restores everything it
     * touches. {@code vanilla} adds the "vanilla fire is extinguished" check (only meaningful with world protection on).
     */
    public static void persistentFire(MinecraftServer server, ServerLevel level, BlockPos at, boolean vanilla, Sink sink) {
        BlockPos base = at.above(0);
        BlockPos fireAt = base.above(2);
        BlockPos vanillaAt = base.above(2).east(3);
        BlockState[] oldFire = new BlockState[4], oldVanilla = new BlockState[4];
        for (int dy = -1; dy < 4; dy++) {
            if (level.getBlockEntity(fireAt.above(dy)) != null || level.getBlockEntity(vanillaAt.above(dy)) != null)
                throw new IllegalArgumentException("Fire selftest needs a spot without block entities");
            if (dy >= 0) {
                oldFire[dy] = level.getBlockState(fireAt.above(dy));
                oldVanilla[dy] = level.getBlockState(vanillaAt.above(dy));
            }
        }
        BlockState oldBelow1 = level.getBlockState(fireAt.below()), oldBelow2 = level.getBlockState(vanillaAt.below());
        int sitesBefore = FireSites.all(level).size();
        try {
            level.setBlock(fireAt.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(vanillaAt.below(), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 0; dy < 4; dy++) {
                level.setBlock(fireAt.above(dy), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(vanillaAt.above(dy), Blocks.AIR.defaultBlockState(), 2);
            }
            // placed by FireSites: blocks and a far-visible site
            FireSites.ignite(level, fireAt, 1.0f, 3);
            drain(server);
            drain(server);
            boolean base1 = PersistentFire.is(level.getBlockState(fireAt));
            boolean top = PersistentFire.is(level.getBlockState(fireAt.above(2)));
            sink.check("a big fire placed by FireSites persists", base1 && top, "column of 3 at " + fireAt.toShortString());
            FireSites.Site site = FireSites.at(level, fireAt);
            sink.check("the fire site is registered", site != null && (site.flags() & FireSites.FLAG_BLOCKS) != 0,
                    site == null ? "none" : "id " + site.id());
            // it never spreads: no fire block appears next to it
            boolean spread = false;
            for (BlockPos n : new BlockPos[]{fireAt.north(), fireAt.south(), fireAt.east(), fireAt.west(), fireAt.above(3)}) {
                if (!level.getBlockState(n).isAir()) spread = true;
            }
            sink.check("a big fire does not spread", !spread, "neighbours are air");
            if (vanilla) {
                level.setBlock(vanillaAt, Blocks.FIRE.defaultBlockState(), 3);
                drain(server);
                drain(server);
                boolean gone = !level.getBlockState(vanillaAt).is(Blocks.FIRE);
                sink.check("vanilla fire outside a homestead is still extinguished", gone, level.getBlockState(vanillaAt).getBlock().getDescriptionId());
            }
        } finally {
            FireSites.douse(level, fireAt);
            for (int dy = 0; dy < 4; dy++) {
                level.setBlock(fireAt.above(dy), oldFire[dy], 2);
                level.setBlock(vanillaAt.above(dy), oldVanilla[dy], 2);
            }
            level.setBlock(fireAt.below(), oldBelow1, 2);
            level.setBlock(vanillaAt.below(), oldBelow2, 2);
            boolean restored = true;
            for (int dy = 0; dy < 4; dy++) restored &= level.getBlockState(fireAt.above(dy)).equals(oldFire[dy])
                    && level.getBlockState(vanillaAt.above(dy)).equals(oldVanilla[dy]);
            sink.check("fire test restores both complete columns", restored, "all eight touched block states");
            if (FireSites.all(level).size() != sitesBefore) {
                // a stray test site would be a leak
                FireSites.remove(level, fireAt);
            }
        }
    }
}
