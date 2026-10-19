package com.nirncraft.tamriel.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 0 unit tests for {@link WorldSheets} loader, causal citation resolution, and consumer lookups (MS22).
 */
class WorldSheetsTest {

    @Test
    void worldSheetsLoadsAndResolvesCitations() {
        WorldSheets.ProvinceSheet skyrim = WorldSheets.province("skyrim");
        assertNotNull(skyrim, "skyrim.json province sheet must load");
        assertFalse(skyrim.truth().isBlank(), "skyrim truth paragraph must be non-empty");
        assertEquals(9, skyrim.regions().size(), "skyrim must have 9 hold sub-sheets");

        WorldSheets.SheetBlock reach = WorldSheets.region("the_reach");
        assertNotNull(reach, "the_reach region sheet must load");
        assertEquals(reach, WorldSheets.region("reach"), "alias 'reach' must resolve to the_reach");
        assertEquals(reach, WorldSheets.region("skyrim/the_reach"), "prefixed 'skyrim/the_reach' must resolve to the_reach");
        assertFalse( WorldSheets.truth("the_reach").isBlank(), "the_reach truth must be non-empty");

        for (String layer : WorldSheets.LAYERS) {
            assertFalse(skyrim.fieldsIn(layer).isEmpty(), "skyrim " + layer + " must be non-empty");
            for (WorldSheets.SheetBlock hold : skyrim.regions().values()) {
                List<WorldSheets.Field> fields = hold.fieldsIn(layer);
                assertFalse(fields.isEmpty(), hold.scope() + " " + layer + " must be non-empty");
                for (WorldSheets.Field f : fields) {
                    if (f.layerNum() >= 2) {
                        assertFalse(f.cites().isEmpty(), f.globalId() + " must have explicit cites");
                        boolean hasHigher = false;
                        for (String cite : f.cites()) {
                            WorldSheets.Field target = WorldSheets.field(cite);
                            assertNotNull(target, f.globalId() + " cite " + cite + " must resolve");
                            if (target.layerNum() < f.layerNum()) {
                                hasHigher = true;
                            }
                        }
                        assertTrue(hasHigher, f.globalId() + " must cite at least one higher layer");
                    }
                }
            }
        }

        WorldSheets.Field silverMining = WorldSheets.field("the_reach.L5.silver_mining");
        assertNotNull(silverMining, "the_reach.L5.silver_mining must resolve");
        assertEquals(silverMining, WorldSheets.field("reach.L5.silver_mining"));
        assertTrue(silverMining.cites().contains("the_reach.L1.silver_veins"));

        WorldSheets.Field civilWar = WorldSheets.field("skyrim.L9.civil_war");
        assertNotNull(civilWar, "skyrim.L9.civil_war must resolve");
        assertEquals(civilWar, WorldSheets.field("sk.L9.civil_war"));

        List<String> minerWhy = WorldSheets.whyForJob("skyrim/the_reach", "miner");
        assertFalse(minerWhy.isEmpty(), "whyForJob(the_reach, miner) must return causal field IDs");
        assertTrue(minerWhy.stream().allMatch(WorldSheets::resolves), "all whyForJob IDs must resolve");
        assertTrue(WorldSheets.jobWeight("skyrim/the_reach", "miner") >= 2, "silver/small mines in The Reach should boost miner weight");

        List<String> forswornWhy = WorldSheets.whyForDungeon("skyrim/the_reach", "forsworn");
        assertFalse(forswornWhy.isEmpty(), "whyForDungeon(the_reach, forsworn) must return L10 field IDs");
        assertTrue(forswornWhy.stream().allMatch(WorldSheets::resolves), "all whyForDungeon IDs must resolve");
    }
}
