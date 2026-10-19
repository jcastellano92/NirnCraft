package com.nirncraft.dungeons.marquee;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MarqueePlansTest {
    @Test
    void everyPackagedIndexEntryLoadsThroughTheRealLoader() throws Exception {
        try (var in = getClass().getResourceAsStream("/nirncraft/dungeons/marquee/index.json")) {
            assertNotNull(in);
            var index = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(122, index.size());
            for (var entry : index) {
                String id = entry.getAsString().replaceFirst("\\.json$", "");
                var plan = MarqueePlans.get(id);
                assertNotNull(plan, "Packaged plan is unreachable: " + id);
                assertEquals(id, plan.id);
                assertFalse(plan.rooms.isEmpty(), id);
                assertSame(plan, MarqueePlans.byHash(plan.hash));
            }
            assertEquals(index.size(), MarqueePlans.ids().size(), "Duplicate or missing plans");
        }
    }
}
