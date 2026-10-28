package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtlasVkDefaultsTest {
    @Test
    void sharedVulkanIsTheOnlyRuntimePath() {
        assertEquals(AtlasVulkan.Path.VK_SHARED, AtlasVulkan.path());
        assertArrayEquals(new AtlasVulkan.Path[]{AtlasVulkan.Path.VK_SHARED}, AtlasVulkan.Path.values());
    }
}
