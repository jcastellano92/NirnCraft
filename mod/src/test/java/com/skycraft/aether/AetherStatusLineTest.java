package com.skycraft.aether;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The one status line (AetherStatusLine) and the never-crash hook guard (AetherGuard). */
class AetherStatusLineTest {
    static final String INFO = "{\"backend\":\"vulkan\",\"device\":\"NVIDIA GeForce RTX 3090\",\"type\":\"discrete\","
            + "\"interop\":\"zero-copy (win32 handles)\",\"devices\":[{\"name\":\"NVIDIA GeForce RTX 3090\"}]}";

    @Test
    void parsesDeviceAndInterop() {
        assertEquals("NVIDIA GeForce RTX 3090", AetherStatusLine.device(INFO));
        assertEquals("zero-copy", AetherStatusLine.interop(INFO));
        assertEquals("host-copy", AetherStatusLine.interop("{\"interop\":\"host-copy\",\"interopReason\":\"x\"}"));
        assertNull(AetherStatusLine.interop("{\"interop\":\"not attached\"}"));
        assertEquals("AMD \"Custom\" GPU", AetherStatusLine.device("{\"device\":\"AMD \\\"Custom\\\" GPU\"}"));
        assertNull(AetherStatusLine.device(null));
        assertNull(AetherStatusLine.device("{}"));
    }

    @Test
    void formatsOnAndOff() {
        assertEquals("Skycraft graphics: far field on (zero-copy) on NVIDIA GeForce RTX 3090, quality HIGH",
                AetherStatusLine.on(INFO, false, "HIGH"));
        assertEquals("Skycraft graphics: far field on (host-copy, no shader pack: fallback compositor) on AMD Radeon 780M, "
                        + "quality IGPU",
                AetherStatusLine.on("{\"device\":\"AMD Radeon 780M\",\"interop\":\"host-copy\"}", true, "IGPU"));
        assertEquals("Skycraft graphics: far field off: no macOS build", AetherStatusLine.off("no macOS build"));
        assertEquals("Skycraft graphics: far field off: unknown reason", AetherStatusLine.off(null));
    }

    @Test
    void classifiesFailures() {
        assertEquals(AetherStatusLine.Off.PLATFORM,
                AetherStatusLine.classify(new AetherLibrary.UnsupportedPlatformException("mac")));
        assertEquals(AetherStatusLine.Off.NATIVE_ACCESS,
                AetherStatusLine.classify(new AetherNative.NativeAccessDenied("denied", new IllegalCallerException())));
        assertEquals(AetherStatusLine.Off.NATIVE_ACCESS, AetherStatusLine.classify(new IllegalCallerException("restricted")));
        assertEquals(AetherStatusLine.Off.DEVICE_LOST,
                AetherStatusLine.classify(new AetherNative.AetherException(AetherNative.ERR_DEVICE_LOST, "lost")));
        assertEquals(AetherStatusLine.Off.DEVICE_LOST,
                AetherStatusLine.classify(new AetherNative.AetherException(AetherNative.ERR_TIMEOUT, "hung")));
        assertEquals(AetherStatusLine.Off.MEMORY,
                AetherStatusLine.classify(new RuntimeException(new AetherNative.AetherException(AetherNative.ERR_OUT_OF_MEMORY, "oom"))));
        assertEquals(AetherStatusLine.Off.NO_DEVICE, AetherStatusLine.classify(
                new AetherNative.AetherException(AetherNative.ERR_GPU, "aether_create(1) failed: GPU error (no device)")));
        assertEquals(AetherStatusLine.Off.ERROR, AetherStatusLine.classify(new IllegalStateException("x")));
    }

    @Test
    void noticesOnlyWhereThePlayerCanAct() {
        assertTrue(AetherStatusLine.notice(AetherStatusLine.Off.SAFE_MODE));
        assertTrue(AetherStatusLine.notice(AetherStatusLine.Off.NATIVE_ACCESS));
        assertTrue(AetherStatusLine.notice(AetherStatusLine.Off.DEVICE_LOST));
        assertFalse(AetherStatusLine.notice(AetherStatusLine.Off.PLATFORM));  // a Mac player sees no nag every launch
        assertFalse(AetherStatusLine.notice(AetherStatusLine.Off.SETTING));
        for (AetherStatusLine.Off o : AetherStatusLine.Off.values()) assertFalse(AetherStatusLine.noticeText(o).isBlank());
    }

    @Test
    void exceptionStatusNames() {
        var e = new AetherNative.AetherException(AetherNative.ERR_DEVICE_LOST, "x");
        assertTrue(e.deviceLost());
        assertFalse(e.outOfMemory());
        assertEquals("device lost", AetherNative.AetherException.statusName(AetherNative.ERR_DEVICE_LOST));
        assertEquals("status -42", AetherNative.AetherException.statusName(-42));
        assertEquals("0.11.0", AetherNative.abiString(AetherNative.ABI_VERSION));
        assertEquals("0.11.0", AetherNative.abiString(AetherNative.ABI_MIN));
        assertTrue(AetherNative.enableNativeAccessArg().startsWith("--enable-native-access="));
    }

    // ---------------------------------------------------------------- AetherGuard

    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void resetGuard() {
        AetherGuard.reset();
    }

    @Test
    void aFailingHookIsTurnedOffWithOneLine() {
        AetherGuard.warn = warnings::add;
        AetherGuard.reset();
        int[] calls = {0};
        for (int i = 0; i < 5; i++) {
            AetherGuard.run("test feature", () -> {
                calls[0]++;
                throw new IllegalStateException("boom");
            });
        }
        assertEquals(1, calls[0]);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("test feature off for this session: "), warnings.get(0));
        assertTrue(AetherGuard.off("test feature"));
        // other features are untouched; errors (not just exceptions) are caught too
        AetherGuard.run("other", () -> calls[0]++);
        assertEquals(2, calls[0]);
        AetherGuard.run("third", () -> {
            throw new StackOverflowError();
        });
        assertTrue(AetherGuard.off("third"));
        assertFalse(AetherGuard.off("other"));
    }

    @Test
    void aBrokenLoggerDoesNotEscape() {
        AetherGuard.warn = m -> {
            throw new RuntimeException("logger down");
        };
        AetherGuard.reset();
        assertDoesNotThrow(() -> AetherGuard.run("f", () -> {
            throw new RuntimeException("x");
        }));
    }
}
