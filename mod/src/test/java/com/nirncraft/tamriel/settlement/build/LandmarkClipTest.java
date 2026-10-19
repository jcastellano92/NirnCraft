package com.nirncraft.tamriel.settlement.build;

import com.nirncraft.tamriel.settlement.plan.Archetype;
import com.nirncraft.tamriel.settlement.plan.Spec;
import com.nirncraft.testing.BoundedServerProvider;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/** Clipped structure replay must neither query remote chunks nor change painted output. */
@ExtendWith(BoundedServerProvider.class)
class LandmarkClipTest {
    @Test void largeRockRetainsWholePieceOutputWhenOnlyOneChunkIsAvailable(MinecraftServer server) {
        Spec spec = new Spec(Archetype.LANDMARK);
        spec.w = spec.d = 64;
        spec.floor = 80;
        spec.variant = Landmarks.ROCK;
        var complete = new HashMap<BlockPos, BlockState>();
        var chunk = new HashMap<BlockPos, BlockState>();
        BoundingBox fullBounds = new BoundingBox(-32, -64, -32, 31, 319, 31);
        BoundingBox chunkBounds = new BoundingBox(0, -64, 0, 15, 319, 15);
        Landmarks.rock(canvas(fullBounds, complete), null, spec);
        Landmarks.rock(canvas(chunkBounds, chunk), null, spec);
        complete.entrySet().removeIf(entry -> !chunkBounds.isInside(entry.getKey()));
        assertTrue(chunk.size() > 1000, "The test must paint a substantive part of the authored rock");
        assertEquals(complete, chunk, "Chunk clipping must preserve every visible block and material variant");
    }

    private static Canvas canvas(BoundingBox bounds, Map<BlockPos, BlockState> blocks) {
        WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getMinY" -> -64;
                        case "getMaxY" -> 319;
                        case "getHeight" -> {
                            if (args == null || args.length == 0) yield 384;
                            checkColumn(bounds, (int) args[1], (int) args[2]);
                            yield 64;
                        }
                        case "hasChunk" -> true;
                        case "getBlockState" -> {
                            BlockPos pos = (BlockPos) args[0];
                            checkColumn(bounds, pos.getX(), pos.getZ());
                            yield blocks.getOrDefault(pos, pos.getY() <= 63 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                        }
                        case "setBlock" -> {
                            BlockPos pos = (BlockPos) args[0];
                            assertTrue(bounds.isInside(pos), "No writes outside the current painting clip");
                            blocks.put(pos.immutable(), (BlockState) args[1]);
                            yield true;
                        }
                        case "toString" -> "strict bounded terrain fixture";
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });
        return new Canvas(level, bounds, -32, 80, -32, Direction.NORTH, 42L);
    }

    private static void checkColumn(BoundingBox bounds, int x, int z) {
        assertTrue(x >= bounds.minX() && x <= bounds.maxX() && z >= bounds.minZ() && z <= bounds.maxZ(),
                () -> "Unsafe remote terrain probe at " + x + "," + z + " outside " + bounds);
    }
}
