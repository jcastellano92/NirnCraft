package com.nirncraft.client.autotest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A route's settle waits on level 0's waiting sections only when level 0 would take one now (AtlasRoute.levelZeroWaits). */
class AtlasRouteSettleTest {
    private static JsonObject s(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void outOfReachSectionsDoNotKeepASettleBusy() {
        assertFalse(AtlasRoute.levelZeroWaits(s("{\"gpuWaiting\":10280,\"nearestMissing\":0,\"cutoff\":152,\"reach\":152}")));
        assertFalse(AtlasRoute.levelZeroWaits(s("{\"waiting\":30,\"nearestMissing\":200,\"cutoff\":120}")));  // (a full pool's cut-off)
        assertFalse(AtlasRoute.levelZeroWaits(s("{\"gpuWaiting\":0,\"waiting\":0,\"nearestMissing\":40,\"cutoff\":152}")));
    }

    @Test
    void sectionsLevelZeroWouldTakeDo() {
        assertTrue(AtlasRoute.levelZeroWaits(s("{\"gpuWaiting\":12,\"nearestMissing\":40,\"cutoff\":152}")));
        assertTrue(AtlasRoute.levelZeroWaits(s("{\"waiting\":3}")));  // (an older library: as before)
    }
}
