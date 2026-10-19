package com.skycraft.aether;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** The far field's crash-loop guard (AetherSafeMode), with simulated sessions and dead processes. */
class AetherSafeModeTest {
    static final AetherSafeMode.ProcessProbe DEAD = (pid, started) -> false;
    static final AetherSafeMode.ProcessProbe ALIVE = (pid, started) -> true;
    static final String KEY = "1.0 | RTX 3090 | 616.56";
    private long nextPid = 100;

    private AetherSafeMode session(Path file, AetherSafeMode.ProcessProbe probe) {
        return new AetherSafeMode(file, nextPid++, Instant.now(), probe);
    }

    /** A session that starts aether and dies in the given state (the process is gone afterwards). */
    private void crashIn(Path file, AetherSafeMode.State where) {
        AetherSafeMode s = session(file, DEAD);
        assertTrue(s.decide(KEY, null).allowed());
        s.starting();
        if (where == AetherSafeMode.State.FRAMES) s.firstFrames();
        assertEquals(where, s.state());
    }

    @Test
    void cleanSessionsStayOn(@TempDir Path tmp) {
        Path f = tmp.resolve("config").resolve(AetherSafeMode.FILE);
        for (int i = 0; i < 3; i++) {
            AetherSafeMode s = session(f, DEAD);
            AetherSafeMode.Decision d = s.decide(KEY, null);
            assertTrue(d.allowed());
            assertNull(d.reason());
            s.starting();
            s.firstFrames();
            long t = 0;
            for (int k = 0; k < AetherSafeMode.RUNNING_FRAMES; k++, t += 50_000_000L) s.frame(t);
            assertEquals(AetherSafeMode.State.RUNNING, s.state());
            s.stopped();
        }
        assertTrue(Files.exists(f));
    }

    @Test
    void aCrashDuringStartupTurnsTheNextSessionOff(@TempDir Path tmp) {
        Path f = tmp.resolve(AetherSafeMode.FILE);
        crashIn(f, AetherSafeMode.State.STARTING);
        AetherSafeMode.Decision d = session(f, DEAD).decide(KEY, null);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("safe mode") && d.reason().contains("starting"), d.reason());
        assertTrue(d.reason().contains("skycraft.aether.safeMode=false"), d.reason());
        // one safe session after a first crash, then it is tried again
        assertTrue(session(f, DEAD).decide(KEY, null).allowed());
    }

    @Test
    void repeatedCrashesWaitLongerThenStayOff(@TempDir Path tmp) {
        Path f = tmp.resolve(AetherSafeMode.FILE);
        crashIn(f, AetherSafeMode.State.FRAMES);
        assertFalse(session(f, DEAD).decide(KEY, null).allowed());  // crash 1: one safe session
        crashIn(f, AetherSafeMode.State.FRAMES);                    // the retry dies too
        for (int i = 0; i < 3; i++) assertFalse(session(f, DEAD).decide(KEY, null).allowed(), "safe session " + i);
        crashIn(f, AetherSafeMode.State.STARTING);                  // and the next retry
        for (int i = 0; i < 10; i++) {
            AetherSafeMode.Decision d = session(f, DEAD).decide(KEY, null);
            assertFalse(d.allowed());
            if (i == 0) assertTrue(d.reason().contains("until the mod, the GPU or its driver changes"), d.reason());
        }
        // a new driver (or mod version) starts over
        assertTrue(session(f, DEAD).decide("1.0 | RTX 3090 | 620.02", null).allowed());
    }

    @Test
    void reachingRunningClearsTheRecord(@TempDir Path tmp) {
        Path f = tmp.resolve(AetherSafeMode.FILE);
        crashIn(f, AetherSafeMode.State.STARTING);
        assertFalse(session(f, DEAD).decide(KEY, null).allowed());
        AetherSafeMode ok = session(f, DEAD);
        assertTrue(ok.decide(KEY, null).allowed());
        ok.starting();
        ok.firstFrames();
        long t = 0;
        for (int k = 0; k < AetherSafeMode.RUNNING_FRAMES; k++, t += 50_000_000L) ok.frame(t);
        assertEquals(AetherSafeMode.State.RUNNING, ok.state());
        // a later unrelated crash (while running) does not count
        assertTrue(session(f, DEAD).decide(KEY, null).allowed());
        crashIn(f, AetherSafeMode.State.FRAMES);
        assertFalse(session(f, DEAD).decide(KEY, null).allowed());  // counted from zero again: one safe session
        assertTrue(session(f, DEAD).decide(KEY, null).allowed());
    }

    @Test
    void notEnoughFramesOrTimeIsNotRunning(@TempDir Path tmp) {
        AetherSafeMode s = session(tmp.resolve(AetherSafeMode.FILE), DEAD);
        s.decide(KEY, null);
        s.starting();
        s.firstFrames();
        for (int k = 0; k < AetherSafeMode.RUNNING_FRAMES * 4; k++) s.frame(k * 1_000_000L);  // 4 s of frames
        assertEquals(AetherSafeMode.State.FRAMES, s.state());
    }

    @Test
    void aHandledStopOrALiveProcessIsNotACrash(@TempDir Path tmp) {
        Path f = tmp.resolve(AetherSafeMode.FILE);
        AetherSafeMode s = session(f, DEAD);
        s.decide(KEY, null);
        s.starting();
        s.stopped();  // aether failed cleanly (no device) or the game closed
        assertTrue(session(f, DEAD).decide(KEY, null).allowed());
        // another instance in the same game dir, still starting
        AetherSafeMode other = session(f, ALIVE);
        other.decide(KEY, null);
        other.starting();
        assertTrue(session(f, ALIVE).decide(KEY, null).allowed());
    }

    @Test
    void overrides(@TempDir Path tmp) {
        Path f = tmp.resolve(AetherSafeMode.FILE);
        crashIn(f, AetherSafeMode.State.STARTING);
        AetherSafeMode.Decision forced = session(f, DEAD).decide(KEY, "false");
        assertTrue(forced.allowed());
        assertTrue(forced.reason().contains("skycraft.aether.safeMode=false"), forced.reason());
        AetherSafeMode.Decision off = session(f, DEAD).decide(KEY, "true");
        assertFalse(off.allowed());
    }

    @Test
    void unwritableConfigDisablesTheGuardNotTheFarField(@TempDir Path tmp) throws Exception {
        Path blocker = tmp.resolve("config");
        Files.writeString(blocker, "a file where the config dir should be");
        AetherSafeMode s = session(blocker.resolve(AetherSafeMode.FILE), DEAD);
        assertTrue(s.decide(KEY, null).allowed());
        s.starting();
        assertFalse(s.writable());
    }

    @Test
    void garbageInTheFileIsIgnored(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve(AetherSafeMode.FILE);
        Files.writeString(f, "state=frames\npid=notanumber\ncrashes=lots\nkey=" + KEY + "\n");
        assertDoesNotThrow(() -> session(f, DEAD).decide(KEY, null));  // a dead "frames" record: one safe session
        Files.write(f, new byte[] {(byte) 0xff, 0, 1, 2});
        assertTrue(session(f, DEAD).decide(KEY, null).allowed());
    }
}
