package com.nirncraft.tamriel.settlement;

import com.nirncraft.society.Housing;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HousingCacheLifecycleTest {
    private static SettlementAnchors.Entry settlement(AnchorType job) {
        var e = new SettlementAnchors.Entry("gen:999,999", 16000, 16000, 30);
        e.anchors.add(new Anchor(AnchorType.BED, new BlockPos(16000, 80, 16000), "home#1"));
        e.anchors.add(new Anchor(job, new BlockPos(16001, 80, 16000), "work#1"));
        return e;
    }
    @Test void twoSavesWithIdenticalNamesAndCountsDoNotSharePlans() {
        var first = settlement(AnchorType.SMITH);
        var second = settlement(AnchorType.MERCHANT);
        var old = SettlementPopulation.housing(first);
        var current = SettlementPopulation.housing(second);
        assertNotSame(old, current);
        assertEquals(Housing.plan(second, SettlementPopulation.tier(second)).bySlot, current.bySlot);
    }
    @Test void replacingAnAnchorWithoutChangingCountInvalidatesPlan() {
        var entry = settlement(AnchorType.SMITH);
        var old = SettlementPopulation.housing(entry);
        entry.anchors.set(1, new Anchor(AnchorType.MERCHANT, new BlockPos(16002, 80, 16000), "shop#1"));
        var current = SettlementPopulation.housing(entry);
        assertNotSame(old, current);
        assertEquals(Housing.plan(entry, SettlementPopulation.tier(entry)).bySlot, current.bySlot);
        assertSame(current, SettlementPopulation.housing(entry));
    }
}
