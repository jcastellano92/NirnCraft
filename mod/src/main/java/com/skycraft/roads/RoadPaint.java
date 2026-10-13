package com.skycraft.roads;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.world.PlacementMask;
import com.skycraft.worldplan.PlanGround;
import com.skycraft.worldplan.RoadPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.concurrent.atomic.LongAdder;

/**
 * Pipeline stage S8 + S9 (docs/WORLDGEN-PIPELINE.md P3): paints the baked road plan into every chunk as it is
 * generated, at the last decoration step (after the vegetation, like the landmarks). Pure per-column evaluation of the
 * plan ({@link RoadPlan#at}): the feature reads and writes only the chunk it decorates, needs no neighbour and does
 * not depend on the order chunks are generated in. Running it twice gives the same blocks (idempotent). Distant
 * Horizons generates through the same chunk generator, so its LODs show the roads too.
 *
 * <p>Per column inside a road's band (distance from the centre line up to the class half width, edge stones on
 * highways, worn verge beyond): the bed is refilled under the surface (carver pits, caves, water) and cleared above it
 * (4 blocks of headroom: leaves, logs, plants, snow, props), and the surface block of the culture's road style is laid
 * at {@code floor(profile)}, with a slab where the fraction of the profile height asks for a half step. Spans:</p>
 * <ul>
 *   <li><b>bridge</b> (and gap bridge): deck at the profile height, stone arches with piers at the planned positions
 *   for highways and roads, a timber deck on log posts for lanes and trails, parapets (wall or fence) with a post and
 *   lantern every 8 blocks, solid abutments at both ends;</li>
 *   <li><b>culvert</b>: the road embankment crosses the stream: the channel columns stay open under the deck, lined
 *   with stone-brick walls and a lintel;</li>
 *   <li><b>ford</b>: a gravel and cobble bed flush with the water (stepping stones);</li>
 *   <li><b>stairs</b>: stair blocks facing uphill, flat landings where the plan flattens, a lantern post every 8 blocks;</li>
 *   <li><b>tunnel</b>: a bore through the rock between the mouths, stone-brick lined, a lantern every 8 blocks,
 *   portal frames at both ends.</li>
 * </ul>
 *
 * <p>Structure pieces are respected: a column inside the box of a piece of a structure start the region holds is only
 * blended (a natural top block with room above becomes road surface, so a road still runs through a gate), never cleared.
 * Everything is deterministic from the plan and the culture of the land.</p>
 */
public class RoadPaint extends Feature<NoneFeatureConfiguration> {
    /** Block writes without neighbour updates (worldgen). */
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    private static final int HEADROOM = 4;
    private static final int MAX_FILL = 28;
    /** Verge (worn ground) width beyond the half width, blocks. */
    private static final float VERGE = 1.5f;

    // statistics (the dev command and the benchmark read these)
    static final LongAdder CHUNKS = new LongAdder(), COLUMNS = new LongAdder(), NANOS = new LongAdder(), BLOCKS = new LongAdder();

    public RoadPaint(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    /** Logs the totals when a server stops (the benchmark numbers of the generation that just ran). */
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Skycraft.MODID)
    public static final class Log {
        private Log() {}

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onStop(net.minecraftforge.event.server.ServerStoppingEvent e) {
            Skycraft.LOGGER.info(stats());
        }
    }

    static String stats() {
        long c = CHUNKS.sum();
        return String.format("RoadPaint: %d chunks painted, %d columns, %d blocks, avg %.1f us per painted chunk", c, COLUMNS.sum(),
                BLOCKS.sum(), c == 0 ? 0.0 : NANOS.sum() / 1000.0 / c);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) return false;
        try {
            if (!RoadsConfig.PAINT.get()) return false;
        } catch (IllegalStateException e) {
            // config not loaded (tools): paint
        }
        ChunkPos chunk = new ChunkPos(ctx.origin());
        int x0 = chunk.getMinBlockX(), z0 = chunk.getMinBlockZ();
        RoadPlan plan = RoadPlan.get();
        RoadPlan.Hit hit = new RoadPlan.Hit();
        // the plan answers within 32 blocks: nothing within reach of the chunk centre means nothing in the chunk
        if (!plan.at(x0 + 8, z0 + 8, hit)) return false;
        long t0 = System.nanoTime();
        Painter p = new Painter(level, plan, x0, z0);
        try {
            p.run();
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Skycraft roads: painting chunk {} {} failed", chunk.x, chunk.z, e);
        }
        if (p.columns > 0) {
            CHUNKS.increment();
            COLUMNS.add(p.columns);
            BLOCKS.add(p.blocks);
            NANOS.add(System.nanoTime() - t0);
        }
        return p.blocks > 0;
    }

    // ================================================================================================ painter

    /** One chunk's painting. */
    private static final class Painter {
        final WorldGenLevel level;
        final RoadPlan plan;
        final int x0, z0, minY, maxY;
        final RoadPlan.Hit hit = new RoadPlan.Hit(), other = new RoadPlan.Hit();
        final MapSample sample = new MapSample();
        final PlanGround.Bed bed = PlanGround.scratch();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        byte[] structures;
        int columns, blocks;
        /** Profile height at the column's corner, the point the terrain's bed was shaped for (P2), plus its hair. */
        float height;

        Painter(WorldGenLevel level, RoadPlan plan, int x0, int z0) {
            this.level = level;
            this.plan = plan;
            this.x0 = x0;
            this.z0 = z0;
            this.minY = level.getMinBuildHeight();
            this.maxY = level.getMaxBuildHeight() - 1;
        }

        void run() {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int x = x0 + dx, z = z0 + dz;
                    if (!plan.at(x + 0.5, z + 0.5, hit)) continue;
                    float d = hit.distance, hw = hit.halfWidth;
                    float reach = hw + (hit.span == RoadPlan.TUNNEL ? 2.0f : hit.span == RoadPlan.BRIDGE ? 1.0f : VERGE);
                    if (d > reach) continue;
                    if (structures == null) {
                        structures = PlacementMask.structureGrid(level, x0 >> 4, z0 >> 4, 0);
                    }
                    columns++;
                    // the terrain was pulled to the profile at the integer corner (PlanGround.bed): use that height so
                    // the painted surface and the top solid block agree to the block
                    height = plan.at(x, z, other) && other.segment == hit.segment ? other.height : hit.height;
                    // on a steep stretch (stairs, a mountain track) the corner and the centre differ by a block: use the centre
                    int ev = hit.vertex;
                    float el = plan.varc[ev + 1] - plan.varc[ev];
                    if (el > 1e-3f && Math.abs(plan.vh[ev + 1] - plan.vh[ev]) / el > 0.2f) height = hit.height;
                    height += 0.002f;
                    boolean reserved = structures[dz << 4 | dx] == 0;
                    paint(x, z, d, hw, reserved);
                }
            }
        }

        // ---------------------------------------------------------------- one column

        void paint(int x, int z, float d, float hw, boolean reserved) {
            byte kind = hit.kind;
            byte span = hit.span;
            Culture c = RoadStyle.cultureAt(x, z);
            int roll = Paver.roll(x, z);
            float h = height;
            int sy = (int) Math.floor(h);
            boolean halfStep = kind <= 1 && h - sy >= 0.5f && span != RoadPlan.FORD && span != RoadPlan.STAIRS;
            boolean onRoad = d <= hw;
            boolean bridge = span == RoadPlan.BRIDGE;
            boolean tunnel = span == RoadPlan.TUNNEL;

            if (reserved) {
                blend(x, z, sy, c, kind, roll, onRoad);
                return;
            }
            if (bridge && d <= hw + 1.0f) {
                bridgeColumn(x, z, d, hw, sy, halfStep, c, kind, roll);
                return;
            }
            if (span == RoadPlan.CULVERT && d <= hw + 1.0f && culvertColumn(x, z, d, hw, sy, c, kind, roll)) return;
            if (tunnel && tunnelColumn(x, z, d, hw, sy, c, kind, roll)) return;
            if (!onRoad) {
                verge(x, z, kind, roll);
                // a lantern post just beside the stairs at every 8th row (a landing)
                if (span == RoadPlan.STAIRS && d <= hw + 1.0f && ((int) Math.floor(hit.along)) % 8 == 0) {
                    BlockState post = kind >= 2 ? RoadStyle.wood(c).fence().defaultBlockState() : wallFor(stoneOf(c).block);
                    set(x, sy + 1, z, post);
                    set(x, sy + 2, z, Blocks.LANTERN.defaultBlockState());
                }
                return;
            }

            // ---- ordinary road surface (normal, cut, fill, switchback, ford, stairs)
            BlockState surf;
            if (span == RoadPlan.FORD) {
                surf = roll < 55 ? Blocks.GRAVEL.defaultBlockState() : roll < 85 ? Blocks.COBBLESTONE.defaultBlockState()
                        : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            } else if (kind == 0 && d > hw - 0.9f) {
                surf = RoadStyle.edge(c, roll);
            } else {
                surf = RoadStyle.surface(c, kind, roll);
            }
            if (span == RoadPlan.STAIRS) {
                stairColumn(x, z, d, hw, sy, c, kind, surf);
                return;
            }
            if (surf.getBlock() instanceof FallingBlock) {
                // gravel and sand need support: the refill below gives it; over a ford it is the bed
                if (span != RoadPlan.FORD && roll % 3 == 0) surf = Blocks.COARSE_DIRT.defaultBlockState();
            }
            fillBelow(x, z, sy);
            clearAbove(x, z, sy + (halfStep ? 1 : 0), span == RoadPlan.FORD);
            set(x, sy, z, surf);
            if (halfStep) set(x, sy + 1, z, RoadStyle.deckSlab(c));
            else if (kind <= 1 && onRoad && d > hw - 1.0f && curb(x, z)) set(x, sy + 1, z, RoadStyle.deckSlab(c));
        }

        /** Town approaches get a half-block curb on the edge columns of highways and roads. */
        boolean curb(int x, int z) {
            PlanGround g = PlanGround.get();
            return g.pad(x, z, bed) && bed.padWeight > 0.35f;
        }

        // ---------------------------------------------------------------- verge, blending

        /** Beside the bed: nothing tall within the band's clear margin, and a worn edge on grass. */
        void verge(int x, int z, byte kind, int roll) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = Math.min(maxY, top + HEADROOM); y >= top; y--) {
                BlockState s = get(x, y, z);
                if (s.isAir()) continue;
                if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || clearable(s)) set(x, y, z, Blocks.AIR.defaultBlockState());
                else break;
            }
            if (kind <= 1 && roll % 100 < 38) {
                BlockState g = get(x, top - 1, z);
                if (g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) || g.is(Blocks.PODZOL)) set(x, top - 1, z, Blocks.COARSE_DIRT.defaultBlockState());
            }
        }

        /** A column inside a structure piece: only a plain ground top with free air above becomes road. */
        void blend(int x, int z, int sy, Culture c, byte kind, int roll, boolean onRoad) {
            if (!onRoad) return;
            for (int y = sy + 1; y >= sy - 1; y--) {
                BlockState s = get(x, y, z);
                if (!isGround(s) || !s.getFluidState().isEmpty()) continue;
                BlockState a1 = get(x, y + 1, z), a2 = get(x, y + 2, z);
                if (!(a1.isAir() || clearable(a1)) || !(a2.isAir() || clearable(a2))) return;
                if (!a1.isAir()) set(x, y + 1, z, Blocks.AIR.defaultBlockState());
                set(x, y, z, RoadStyle.surface(c, kind, roll));
                return;
            }
        }

        // ---------------------------------------------------------------- bridges

        void bridgeColumn(int x, int z, float d, float hw, int sy, boolean halfStep, Culture c, byte kind, int roll) {
            boolean stone = kind <= 1;
            boolean rail = d > hw;
            Stone st = stone ? stoneOf(c) : null;
            RoadStyle.Wood wood = stone ? null : RoadStyle.wood(c);
            BlockState deck = stone ? (roll % 7 == 0 ? st.mossy : st.block) : wood.planks().defaultBlockState();
            int span = plan.spanAt(hit.segment, hit.t < 0.5f ? hit.vertex : hit.vertex + 1);
            float from = 0, len = 0;
            if (span >= 0) {
                from = plan.varc[plan.spanFrom[span]];
                len = plan.varc[plan.spanTo[span]] - from;
            }
            float at = hit.along - from;
            boolean abutment = span < 0 || at < 3.5f || len - at < 3.5f;
            // supports first (they read the ground), deck over them
            if (abutment) {
                fillBelow(x, z, sy);
            } else {
                // the deck's own thickness: one block of masonry (two under the crown of an arch) or a plank + beam
                float base = Float.isNaN(hit.water) ? hit.ground : hit.water;
                int rise = Math.max(0, Math.min(6, sy - 3 - (int) Math.floor(base)));
                if (stone) {
                    stoneSupports(x, z, sy, d, hw, span, at, len, rise, st);
                } else {
                    woodSupports(x, z, sy, d, hw, span, at, c, wood);
                }
            }
            clearAbove(x, z, sy, false);
            set(x, sy, z, deck);
            if (!rail) {
                if (halfStep) set(x, sy + 1, z, stone ? st.slab : wood.slab().defaultBlockState());
                return;
            }
            // parapet: wall or fence, connected to the rail columns beside it, a taller post with a lantern every 8
            BlockState r = stone ? wallFor(st.block) : wood.fence().defaultBlockState();
            r = connect(r, x, z);
            set(x, sy + 1, z, r);
            if (((int) Math.floor(hit.along)) % 8 == 0) {
                set(x, sy + 2, z, stone ? wallFor(st.block) : wood.fence().defaultBlockState());
                set(x, sy + 3, z, Blocks.LANTERN.defaultBlockState());
            }
        }

        void stoneSupports(int x, int z, int sy, float d, float hw, int span, float at, float len, int rise, Stone st) {
            if (span < 0) {
                set(x, sy - 1, z, st.block);
                return;
            }
            // bay boundaries: span start, the planned piers, span end
            int k0 = plan.pierOff[span], k1 = plan.pierOff[span + 1];
            float lo = 0f, hi = len;
            boolean pier = false;
            for (int k = k0; k < k1; k++) {
                float p = plan.pierAt[k];
                if (Math.abs(at - p) <= 1.0f) pier = true;
                if (p <= at && p > lo) lo = p;
                if (p > at && p < hi) hi = p;
            }
            if (pier && d <= hw + 1.0f) {
                pierDown(x, z, sy - 1, st.block);
                return;
            }
            float bay = hi - lo;
            if (rise <= 0 || bay < 4f) {
                // a flat span: two blocks of masonry (the lintel) over open water
                set(x, sy - 1, z, st.block);
                if (rise <= 0) return;
            }
            float u = bay <= 0 ? 0.5f : (at - lo) / bay;
            float t = 2f * u - 1f;
            int open = (int) Math.round(rise * Math.sqrt(Math.max(0.0, 1.0 - t * t)));
            int spring = sy - 2 - rise;
            int top = spring + open; // highest open block of the arch
            for (int y = sy - 1; y > top; y--) set(x, y, z, st.block);
        }

        void pierDown(int x, int z, int fromY, BlockState material) {
            for (int y = fromY, n = 0; y > minY && n < 80; y--, n++) {
                BlockState s = get(x, y, z);
                if (isSupport(s) && s.getFluidState().isEmpty()) return;
                set(x, y, z, material);
            }
        }

        void woodSupports(int x, int z, int sy, float d, float hw, int span, float at, Culture c, RoadStyle.Wood wood) {
            boolean edge = d > hw - 1.0f;
            // a beam under each edge, running along the deck
            if (edge) {
                BlockState log = wood.log().defaultBlockState();
                Direction.Axis axis = Math.abs(hit.dirX) >= Math.abs(hit.dirZ) ? Direction.Axis.X : Direction.Axis.Z;
                set(x, sy - 1, z, log.setValue(RotatedPillarBlock.AXIS, axis));
            }
            if (span < 0 || !edge) return;
            int k0 = plan.pierOff[span], k1 = plan.pierOff[span + 1];
            for (int k = k0; k < k1; k++) {
                if (Math.abs(at - plan.pierAt[k]) <= 1.0f) {
                    BlockState log = wood.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
                    pierDown(x, z, sy - 2, log);
                    return;
                }
            }
        }

        // ---------------------------------------------------------------- culverts

        /** True when the column was handled (a channel column or a lining column). */
        boolean culvertColumn(int x, int z, float d, float hw, int sy, Culture c, byte kind, int roll) {
            boolean channel = channel(x, z);
            boolean lining = false;
            if (!channel) {
                lining = channel(x + 1, z) || channel(x - 1, z) || channel(x, z + 1) || channel(x, z - 1);
            }
            if (!channel && !lining) return false;
            Stone st = stoneOf(c);
            float w = Float.isNaN(hit.water) ? sy - 2 : hit.water;
            int water = (int) Math.floor(w);
            BlockState surf = RoadStyle.surface(c, kind, roll);
            if (surf.getBlock() instanceof FallingBlock) surf = Blocks.COBBLESTONE.defaultBlockState();
            if (channel) {
                // open under the deck: clear the arch (nothing may hang in the stream), lintel, deck
                for (int y = water; y < sy - 1; y++) {
                    BlockState s = get(x, y, z);
                    if (!s.isAir() && s.getFluidState().isEmpty()) set(x, y, z, Blocks.AIR.defaultBlockState());
                }
                set(x, sy - 1, z, st.block);
                clearAbove(x, z, sy, false);
                set(x, sy, z, surf);
            } else {
                // wall of the culvert: stone brick from the stream bed up to the lintel, then the road
                for (int y = water; y <= sy - 1; y++) {
                    BlockState s = get(x, y, z);
                    if (y == water && !s.getFluidState().isEmpty()) continue;
                    set(x, y, z, st.block);
                }
                fillBelow(x, z, water);
                clearAbove(x, z, sy, false);
                set(x, sy, z, surf);
            }
            return true;
        }

        boolean channel(int x, int z) {
            TamrielMap.sample(x, z, sample);
            return sample.inWater();
        }

        // ---------------------------------------------------------------- stairs

        void stairColumn(int x, int z, float d, float hw, int sy, Culture c, byte kind, BlockState surf) {
            BlockState flat = kind >= 2 ? RoadStyle.wood(c).planks().defaultBlockState() : stoneOf(c).block;
            fillBelow(x, z, sy);
            clearAbove(x, z, sy, false);
            set(x, sy, z, stairFloor(sy, c, kind, flat));
        }

        /**
         * The floor block of a steep stretch: a stair block facing uphill where the row starts a new step, a full block
         * where the row below is at the same height (a landing); {@code flat} on a gentle stretch.
         */
        BlockState stairFloor(int sy, Culture c, byte kind, BlockState flat) {
            int i = hit.vertex;
            float dh = plan.vh[i + 1] - plan.vh[i];
            float len = plan.varc[i + 1] - plan.varc[i];
            if (len < 1e-3f || Math.abs(dh) / len <= 0.25f) return flat;
            float ux = hit.dirX, uz = hit.dirZ;
            if (dh < 0) {
                ux = -ux;
                uz = -uz;
            }
            float lower = heightAtArc(hit.along + (dh >= 0 ? -1.0f : 1.0f));
            if ((int) Math.floor(lower) >= sy) return flat;
            Block stairs = kind >= 2 ? woodStairs(c) : stoneStairs(c);
            return stairs.defaultBlockState().setValue(StairBlock.FACING, Direction.getNearest(ux, 0, uz));
        }

        /** Profile height at {@code arc} blocks along the segment of the current hit (clamped to the segment). */
        float heightAtArc(float arc) {
            int s = hit.segment;
            int a = plan.segStart[s], b = plan.segEnd[s] - 1;
            if (arc <= plan.varc[a]) return plan.vh[a];
            if (arc >= plan.varc[b]) return plan.vh[b];
            int i = Math.max(a, Math.min(b - 1, hit.vertex));
            while (i > a && plan.varc[i] > arc) i--;
            while (i < b - 1 && plan.varc[i + 1] < arc) i++;
            float l = plan.varc[i + 1] - plan.varc[i];
            float t = l < 1e-4f ? 0f : (arc - plan.varc[i]) / l;
            return plan.vh[i] + (plan.vh[i + 1] - plan.vh[i]) * t;
        }

        // ---------------------------------------------------------------- tunnels

        /** True when the column was handled (inside the bore or its lining). */
        boolean tunnelColumn(int x, int z, float d, float hw, int sy, Culture c, byte kind, int roll) {
            int span = plan.spanAt(hit.segment, hit.t < 0.5f ? hit.vertex : hit.vertex + 1);
            if (span < 0) return false;
            float from = plan.varc[plan.spanFrom[span]], len = plan.varc[plan.spanTo[span]] - from;
            float at = hit.along - from;
            boolean portal = at < 2.5f || len - at < 2.5f;
            int height = kind <= 1 ? 4 : 3;
            Stone st = stoneOf(c);
            BlockState wall = roll % 5 == 0 ? st.mossy : st.block;
            float reach = hw + (portal ? 2.0f : 1.0f);
            if (d > reach) return false;
            fillBelow(x, z, sy);
            set(x, sy, z, stairFloor(sy, c, kind, RoadStyle.surface(c, kind, roll)));
            int top = height + (portal ? 2 : 1);
            boolean inside = d <= hw;
            for (int y = sy + 1; y <= sy + top && y <= maxY; y++) {
                BlockState s;
                if (inside) {
                    // the bore, with a lining ceiling
                    s = y <= sy + height ? Blocks.AIR.defaultBlockState() : wall;
                } else if (portal && d <= hw + 2.0f || !portal && d <= hw + 1.0f) {
                    s = wall;
                } else {
                    continue;
                }
                set(x, y, z, s);
            }
            if (portal && inside) {
                // the lintel course of the portal frame
                set(x, sy + height + 2, z, wall);
            }
            // a lantern every 8 blocks under the ceiling on the centre line; both sides at the portals
            if (inside && ((int) Math.floor(hit.along)) % 8 == 0 && d < 0.8f + (portal ? hw : 0f)) {
                set(x, sy + height, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
            return true;
        }

        // ---------------------------------------------------------------- shared pieces

        /** Fills the column below {@code sy} with soil and stone until it rests on something solid. */
        void fillBelow(int x, int z, int sy) {
            for (int y = sy - 1, n = 0; y > minY && n < MAX_FILL; y--, n++) {
                BlockState s = get(x, y, z);
                if (isSupport(s) && s.getFluidState().isEmpty()) return;
                set(x, y, z, n < 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }

        /** Clears everything from {@code y + 1} up for {@link #HEADROOM} blocks (water stays: a ford is awash). */
        void clearAbove(int x, int z, int y, boolean keepFluid) {
            for (int yy = y + 1; yy <= y + HEADROOM && yy <= maxY; yy++) {
                BlockState s = get(x, yy, z);
                if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                set(x, yy, z, Blocks.AIR.defaultBlockState());
            }
        }

        /** The rail columns beside (x, z) decide its connections (a deterministic read of the plan, no neighbour chunk). */
        BlockState connect(BlockState s, int x, int z) {
            boolean n = railAt(x, z - 1), e = railAt(x + 1, z), so = railAt(x, z + 1), w = railAt(x - 1, z);
            if (s.getBlock() instanceof WallBlock) {
                return s.setValue(WallBlock.NORTH_WALL, n ? WallSide.LOW : WallSide.NONE)
                        .setValue(WallBlock.EAST_WALL, e ? WallSide.LOW : WallSide.NONE)
                        .setValue(WallBlock.SOUTH_WALL, so ? WallSide.LOW : WallSide.NONE)
                        .setValue(WallBlock.WEST_WALL, w ? WallSide.LOW : WallSide.NONE)
                        .setValue(WallBlock.UP, !(n && so && !e && !w) && !(e && w && !n && !so));
            }
            if (s.hasProperty(CrossCollisionBlock.NORTH)) {
                return s.setValue(CrossCollisionBlock.NORTH, n).setValue(CrossCollisionBlock.EAST, e)
                        .setValue(CrossCollisionBlock.SOUTH, so).setValue(CrossCollisionBlock.WEST, w);
            }
            return s;
        }

        boolean railAt(int x, int z) {
            if (!plan.at(x + 0.5, z + 0.5, other)) return false;
            return other.span == RoadPlan.BRIDGE && other.distance > other.halfWidth && other.distance <= other.halfWidth + 1.0f;
        }

        BlockState get(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
        }

        void set(int x, int y, int z, BlockState s) {
            if (y < minY || y > maxY) return;
            pos.set(x, y, z);
            if (level.getBlockState(pos) == s) return;
            if (level.setBlock(pos, s, FLAGS)) blocks++;
        }
    }

    // ================================================================================================ materials

    /** A culture's masonry for bridges, culverts and tunnels. */
    private record Stone(BlockState block, BlockState mossy, BlockState slab) {
    }

    private static Stone stoneOf(Culture c) {
        RoadStyle.Stone s = RoadStyle.stone(c);
        return new Stone(s.block().defaultBlockState(), s.mossy().defaultBlockState(), s.slab().defaultBlockState());
    }

    private static BlockState wallFor(BlockState masonry) {
        Block b = masonry.getBlock();
        Block wall = b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE ? Blocks.COBBLESTONE_WALL
                : b == Blocks.SANDSTONE || b == Blocks.CUT_SANDSTONE ? Blocks.SANDSTONE_WALL
                : b == Blocks.POLISHED_BLACKSTONE_BRICKS ? Blocks.POLISHED_BLACKSTONE_BRICK_WALL
                : b == Blocks.CALCITE || b == Blocks.QUARTZ_BRICKS ? Blocks.DIORITE_WALL
                : b == Blocks.MUD_BRICKS ? Blocks.MUD_BRICK_WALL
                : Blocks.STONE_BRICK_WALL;
        return wall.defaultBlockState();
    }

    private static Block stoneStairs(Culture c) {
        Block b = RoadStyle.stone(c).block();
        return b == Blocks.COBBLESTONE ? Blocks.COBBLESTONE_STAIRS
                : b == Blocks.SANDSTONE ? Blocks.SANDSTONE_STAIRS
                : b == Blocks.POLISHED_BLACKSTONE_BRICKS ? Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS
                : b == Blocks.CALCITE ? Blocks.QUARTZ_STAIRS
                : b == Blocks.MUD_BRICKS ? Blocks.MUD_BRICK_STAIRS
                : Blocks.STONE_BRICK_STAIRS;
    }

    private static Block woodStairs(Culture c) {
        Block planks = RoadStyle.wood(c).planks();
        return planks == Blocks.OAK_PLANKS ? Blocks.OAK_STAIRS
                : planks == Blocks.BIRCH_PLANKS ? Blocks.BIRCH_STAIRS
                : planks == Blocks.DARK_OAK_PLANKS ? Blocks.DARK_OAK_STAIRS
                : planks == Blocks.MANGROVE_PLANKS ? Blocks.MANGROVE_STAIRS
                : planks == Blocks.ACACIA_PLANKS ? Blocks.ACACIA_STAIRS
                : Blocks.SPRUCE_STAIRS;
    }

    /** Ground a road may be laid on (soil, sand, stone, snow...). */
    private static boolean isGround(BlockState s) {
        return Paver.isNaturalGround(s) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MOSS_BLOCK);
    }

    /** A block the road can rest on: solid, not a plant, leaf, log or fluid. */
    private static boolean isSupport(BlockState s) {
        if (s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return false;
        if (s.getBlock() instanceof BushBlock || !s.getFluidState().isEmpty()) return false;
        return s.canOcclude();
    }

    /** Plants, snow layers, leaves and props that may be removed above a road. */
    private static boolean clearable(BlockState s) {
        return Paver.clearable(s) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS);
    }
}
