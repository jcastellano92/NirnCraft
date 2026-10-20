package com.nirncraft.atlas;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The one status line (AtlasStatusLine) and the never-crash hook guard (AtlasGuard). */
class AtlasStatusLineTest {
    static final String INFO = "{\"backend\":\"vulkan\",\"device\":\"NVIDIA GeForce RTX 3090\",\"type\":\"discrete\","
            + "\"interop\":\"zero-copy (win32 handles)\",\"devices\":[{\"name\":\"NVIDIA GeForce RTX 3090\"}]}";

    @Test
    void parsesDeviceAndInterop() {
        assertEquals("NVIDIA GeForce RTX 3090", AtlasStatusLine.device(INFO));
        assertEquals("zero-copy", AtlasStatusLine.interop(INFO));
        assertEquals("host-copy", AtlasStatusLine.interop("{\"interop\":\"host-copy\",\"interopReason\":\"x\"}"));
        assertNull(AtlasStatusLine.interop("{\"interop\":\"not attached\"}"));
        assertEquals("AMD \"Custom\" GPU", AtlasStatusLine.device("{\"device\":\"AMD \\\"Custom\\\" GPU\"}"));
        assertNull(AtlasStatusLine.device(null));
        assertNull(AtlasStatusLine.device("{}"));
    }

    @Test
    void formatsOnAndOff() {
        assertEquals("NirnCraft: Infinite ATLAS far field on (zero-copy) on NVIDIA GeForce RTX 3090, quality HIGH",
                AtlasStatusLine.on(INFO, false, "HIGH"));
        assertEquals("NirnCraft: Infinite ATLAS far field on (host-copy, no shader pack: fallback compositor) on AMD Radeon 780M, "
                        + "quality IGPU",
                AtlasStatusLine.on("{\"device\":\"AMD Radeon 780M\",\"interop\":\"host-copy\"}", true, "IGPU"));
        assertEquals("NirnCraft: Infinite ATLAS far field off: no macOS build", AtlasStatusLine.off("no macOS build"));
        assertEquals("NirnCraft: Infinite ATLAS far field off: unknown reason", AtlasStatusLine.off(null));
    }

    @Test
    void classifiesFailures() {
        assertEquals(AtlasStatusLine.Off.PLATFORM,
                AtlasStatusLine.classify(new AtlasLibrary.UnsupportedPlatformException("mac")));
        assertEquals(AtlasStatusLine.Off.NATIVE_ACCESS,
                AtlasStatusLine.classify(new AtlasNative.NativeAccessDenied("denied", new IllegalCallerException())));
        assertEquals(AtlasStatusLine.Off.NATIVE_ACCESS, AtlasStatusLine.classify(new IllegalCallerException("restricted")));
        assertEquals(AtlasStatusLine.Off.DEVICE_LOST,
                AtlasStatusLine.classify(new AtlasNative.AtlasException(AtlasNative.ERR_DEVICE_LOST, "lost")));
        assertEquals(AtlasStatusLine.Off.DEVICE_LOST,
                AtlasStatusLine.classify(new AtlasNative.AtlasException(AtlasNative.ERR_TIMEOUT, "hung")));
        assertEquals(AtlasStatusLine.Off.MEMORY,
                AtlasStatusLine.classify(new RuntimeException(new AtlasNative.AtlasException(AtlasNative.ERR_OUT_OF_MEMORY, "oom"))));
        assertEquals(AtlasStatusLine.Off.NO_DEVICE, AtlasStatusLine.classify(
                new AtlasNative.AtlasException(AtlasNative.ERR_GPU, "atlas_create(1) failed: GPU error (no device)")));
        assertEquals(AtlasStatusLine.Off.ERROR, AtlasStatusLine.classify(new IllegalStateException("x")));
    }

    @Test
    void noticesOnlyWhereThePlayerCanAct() {
        assertTrue(AtlasStatusLine.notice(AtlasStatusLine.Off.SAFE_MODE));
        assertTrue(AtlasStatusLine.notice(AtlasStatusLine.Off.NATIVE_ACCESS));
        assertTrue(AtlasStatusLine.notice(AtlasStatusLine.Off.DEVICE_LOST));
        assertFalse(AtlasStatusLine.notice(AtlasStatusLine.Off.PLATFORM));  // a Mac player sees no nag every launch
        assertFalse(AtlasStatusLine.notice(AtlasStatusLine.Off.SETTING));
        for (AtlasStatusLine.Off o : AtlasStatusLine.Off.values()) assertFalse(AtlasStatusLine.noticeText(o).isBlank());
    }

    @Test
    void exceptionStatusNames() {
        var e = new AtlasNative.AtlasException(AtlasNative.ERR_DEVICE_LOST, "x");
        assertTrue(e.deviceLost());
        assertFalse(e.outOfMemory());
        assertEquals("device lost", AtlasNative.AtlasException.statusName(AtlasNative.ERR_DEVICE_LOST));
        assertEquals("status -42", AtlasNative.AtlasException.statusName(-42));
        assertEquals("0.16.0", AtlasNative.abiString(AtlasNative.ABI_VERSION));
        assertEquals("0.11.0", AtlasNative.abiString(AtlasNative.ABI_MIN));
        assertTrue(AtlasNative.enableNativeAccessArg().startsWith("--enable-native-access="));
    }

    // ---------------------------------------------------------------- AtlasGuard

    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void resetGuard() {
        AtlasGuard.reset();
    }

    @Test
    void aFailingHookIsTurnedOffWithOneLine() {
        AtlasGuard.warn = warnings::add;
        AtlasGuard.reset();
        int[] calls = {0};
        for (int i = 0; i < 5; i++) {
            AtlasGuard.run("test feature", () -> {
                calls[0]++;
                throw new IllegalStateException("boom");
            });
        }
        assertEquals(1, calls[0]);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("test feature off for this session: "), warnings.get(0));
        assertTrue(AtlasGuard.off("test feature"));
        // other features are untouched; errors (not just exceptions) are caught too
        AtlasGuard.run("other", () -> calls[0]++);
        assertEquals(2, calls[0]);
        AtlasGuard.run("third", () -> {
            throw new StackOverflowError();
        });
        assertTrue(AtlasGuard.off("third"));
        assertFalse(AtlasGuard.off("other"));
    }

    @Test
    void aBrokenLoggerDoesNotEscape() {
        AtlasGuard.warn = m -> {
            throw new RuntimeException("logger down");
        };
        AtlasGuard.reset();
        assertDoesNotThrow(() -> AtlasGuard.run("f", () -> {
            throw new RuntimeException("x");
        }));
    }
}
