package com.skycraft.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WireProtocolTest {
    @Test
    void differentFieldSchemasRejectEvenWhenClassesAndModVersionAreUnchanged() {
        String registrations = "0:Dialogue:S2C;1:Action:C2S;";
        String a = WireProtocol.identity("0.1.0", 1, registrations, 2);
        String b = WireProtocol.identity("0.1.0", 2, registrations, 2);
        assertNotEquals(a, b);
        assertEquals(b, WireProtocol.identity("0.1.0", 2, registrations, 2));
        assertNotEquals(b, WireProtocol.identity("0.1.0", 2, "1:Action:C2S;0:Dialogue:S2C;", 2));
        assertNotEquals(b, WireProtocol.identity("0.1.1", 2, registrations, 2));
        String legacy = "0.1.0-" + Integer.toHexString(registrations.hashCode()) + "-2";
        assertNotEquals(legacy, b);
    }
}
