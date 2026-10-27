package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AtlasOwnedSlotTest {
    @Test
    void failedThreadStartRollsBackAndOldOwnerCannotClearReplacement() {
        var slot = new AtlasOwnedSlot<Object>();
        Object old = new Object(), replacement = new Object();
        assertThrows(IllegalStateException.class, () -> slot.publish(old, () -> {
            assertSame(old, slot.get());
            throw new IllegalStateException("injected thread start failure");
        }));
        assertNull(slot.get());
        slot.publish(replacement, () -> {});
        slot.clear(old);
        assertSame(replacement, slot.get());
        assertThrows(IllegalStateException.class, () -> slot.publish(old, () -> fail("must not start")));
        slot.clear(replacement);
        assertNull(slot.get());
    }
}
