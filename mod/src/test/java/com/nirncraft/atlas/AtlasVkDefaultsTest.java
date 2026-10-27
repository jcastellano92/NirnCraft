package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

class AtlasVkDefaultsTest {
    @Test
    @ResourceLock("nirncraft.atlas.vkSharedSync")
    void sharedTransportIsDefaultAndExplicitHostControlRemainsAvailable() {
        String key = "nirncraft.atlas.vkSharedSync", previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertTrue(AtlasVkShare.sharedSyncEnabled());
            System.setProperty(key, "false");
            assertFalse(AtlasVkShare.sharedSyncEnabled());
            System.setProperty(key, "true");
            assertTrue(AtlasVkShare.sharedSyncEnabled());
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }
}
