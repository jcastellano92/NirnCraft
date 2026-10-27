package com.nirncraft.render.quality;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class QualityClientShutdownTest {
    private final Map<Field, Object> previous = new LinkedHashMap<>();
    private boolean[] previousPending;

    private static final class Pool implements GpuQueryPool {
        int closes;
        RuntimeException failure;
        public int size() { return 16; }
        public OptionalLong getValue(int index) { throw new AssertionError("closed pool read"); }
        public OptionalLong[] getValues(int index, int count) { throw new AssertionError("closed pool read"); }
        public void close() { closes++; if (failure != null) throw failure; }
    }

    private static Field field(String name) throws Exception {
        Field field = QualityClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @BeforeEach
    void isolateStaticOwner() throws Exception {
        previousPending = ((boolean[]) field("pending").get(null)).clone();
        for (String name : new String[]{"pool", "poolDevice", "stopping", "shutdownAttempted", "shutdownFailure", "measuring"}) {
            Field f = field(name); previous.put(f, f.get(null));
        }
        field("pool").set(null, null);
        field("poolDevice").set(null, null);
        field("stopping").setBoolean(null, false);
        field("shutdownAttempted").setBoolean(null, false);
        field("shutdownFailure").set(null, null);
    }

    @AfterEach
    void restoreStaticOwner() throws Exception {
        for (var entry : previous.entrySet()) entry.getKey().set(null, entry.getValue());
        System.arraycopy(previousPending, 0, field("pending").get(null), 0, previousPending.length);
    }

    @Test
    void actualTerminalCloseQueuesOnceAndRejectsSubsequentFrameGpuWork() throws Exception {
        Pool pool = new Pool();
        field("pool").set(null, pool);
        field("measuring").setBoolean(null, true);
        QualityClient.closeAtShutdown();
        QualityClient.closeAtShutdown();
        assertEquals(1, pool.closes);
        assertNull(field("pool").get(null));
        assertFalse(field("measuring").getBoolean(null));
        // These are the real event/timestamp entry points. Null events/device must remain untouched after sealing.
        for (var method : QualityClient.class.getDeclaredMethods()) {
            if (method.getName().equals("beginFrame") || method.getName().equals("endFrame")) {
                method.setAccessible(true);
                assertDoesNotThrow(() -> method.invoke(null, new Object[]{null}));
            } else if (method.getName().equals("start")) {
                method.setAccessible(true);
                assertEquals(Boolean.FALSE, method.invoke(null, new Object[]{null}));
            }
        }
        var timestamps = QualityClient.class.getDeclaredMethod("timestamps", long.class);
        timestamps.setAccessible(true);
        assertDoesNotThrow(() -> timestamps.invoke(null, 1L));
        assertNull(field("pool").get(null));
        assertEquals(1, pool.closes);
    }

    @Test
    void failedPoolCloseRetainsOwnerAndCannotTurnSuccessfulOnRetry() throws Exception {
        Pool pool = new Pool(); pool.failure = new IllegalStateException("refused deferred close");
        field("pool").set(null, pool);
        assertSame(pool.failure, assertThrows(IllegalStateException.class, QualityClient::closeAtShutdown).getCause());
        assertSame(pool.failure, assertThrows(IllegalStateException.class, QualityClient::closeAtShutdown).getCause());
        assertSame(pool, field("pool").get(null));
        assertTrue(field("stopping").getBoolean(null));
        assertEquals(1, pool.closes);
    }
}
