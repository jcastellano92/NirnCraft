package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AtlasVulkanCopyTest {
    private static final class Ops implements AtlasVulkan.CopyOps {
        boolean ready = true, complete = true;
        String fail;
        int canceled, recorded, submitted;
        void fail(String step) { if (step.equals(fail)) throw new IllegalStateException(step); }
        public boolean prepare() { fail("prepare"); return ready; }
        public void cancel() { canceled++; fail("cancel"); }
        public void record() { recorded++; fail("record"); }
        public void submit() { submitted++; fail("submit"); }
        public boolean waitCopy() { fail("wait"); return complete; }
    }

    @Test
    void refusedOfferIsCanceledExactlyOnce() {
        Object owner = new Object(); Ops ops = new Ops(); ops.ready = false;
        assertFalse(AtlasVulkan.splice(owner, ops));
        assertEquals(1, ops.canceled); assertEquals(0, ops.recorded); assertEquals(0, ops.submitted);
        assertTrue(AtlasVulkan.copyRetired(owner, () -> true));
    }

    @Test
    void prepareFailureCancelsNeverRecordedOffer() {
        Object owner = new Object(); Ops ops = new Ops(); ops.fail = "prepare";
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.splice(owner, ops));
        assertEquals(1, ops.canceled); assertEquals(0, ops.recorded); assertEquals(0, ops.submitted);
        assertTrue(AtlasVulkan.copyRetired(owner, () -> true));
    }

    @Test
    void failuresAfterInsertionNeverCancelOrResubmit() {
        for (String stage : new String[]{"record", "submit", "wait"}) {
            Object owner = new Object(); Ops ops = new Ops(); ops.fail = stage;
            assertThrows(IllegalStateException.class, () -> AtlasVulkan.splice(owner, ops));
            assertEquals(0, ops.canceled);
            AtomicBoolean queried = new AtomicBoolean();
            assertThrows(IllegalStateException.class,
                    () -> AtlasVulkan.copyRetired(owner, () -> { queried.set(true); return true; }));
            assertFalse(queried.get());
            assertThrows(IllegalStateException.class, () -> AtlasVulkan.requirePollable(owner));
            assertEquals(stage.equals("record") ? 0 : 1, ops.submitted);
            Ops next = new Ops();
            assertThrows(IllegalStateException.class, () -> AtlasVulkan.splice(owner, next));
            assertEquals(0, next.canceled); assertEquals(0, next.recorded); assertEquals(0, next.submitted);
        }
    }

    @Test
    void cancellationFailureIsRetainedAndNotRetried() {
        Object owner = new Object(); Ops ops = new Ops(); ops.ready = false; ops.fail = "cancel";
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.splice(owner, ops));
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.copyRetired(owner, () -> true));
        assertEquals(1, ops.canceled);
    }

    @Test
    void submittedTimeoutIsPolledWithoutAnotherSubmit() {
        Object owner = new Object(); Ops ops = new Ops(); ops.complete = false;
        assertThrows(AtlasNative.AtlasException.class, () -> AtlasVulkan.splice(owner, ops));
        assertDoesNotThrow(() -> AtlasVulkan.requirePollable(owner));
        Ops next = new Ops();
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.splice(owner, next));
        assertEquals(0, next.recorded); assertEquals(0, next.submitted);
        assertFalse(AtlasVulkan.copyRetired(owner, () -> false));
        assertFalse(AtlasVulkan.copyRetired(owner, () -> false));
        assertTrue(AtlasVulkan.copyRetired(owner, () -> true));
        assertEquals(1, ops.submitted); assertEquals(0, ops.canceled);
    }

    @Test
    void pollErrorRetainsTheSubmission() {
        Object owner = new Object(); Ops ops = new Ops(); ops.complete = false;
        assertThrows(AtlasNative.AtlasException.class, () -> AtlasVulkan.splice(owner, ops));
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.copyRetired(owner, () -> { throw new IllegalStateException("poll"); }));
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.copyRetired(owner, () -> true));
        assertEquals(1, ops.submitted); assertEquals(0, ops.canceled);
    }

    @Test
    void finishPendingSuppressesWorkUntilSubmittedCopyCompletes() {
        Object owner = new Object(); Ops ops = new Ops(); ops.complete = false;
        assertThrows(AtlasNative.AtlasException.class, () -> AtlasVulkan.splice(owner, ops));
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        assertFalse(AtlasVulkan.finishPending(owner, () -> completion));
        assertFalse(AtlasVulkan.finishPending(owner, () -> completion));
        assertEquals(1, ops.submitted);
        completion.complete(null);
        assertTrue(AtlasVulkan.finishPending(owner, () -> completion));
        Ops next = new Ops();
        assertTrue(AtlasVulkan.splice(owner, next));
        assertEquals(1, next.submitted);
    }

    @Test
    void finishPendingRefusesAmbiguousCopyAndRetainsWaitFailure() {
        Object failedOwner = new Object(); Ops broken = new Ops(); broken.fail = "record";
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.splice(failedOwner, broken));
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.finishPending(failedOwner,
                () -> { fail("ambiguous copy must not be waited or submitted"); return null; }));
        Object owner = new Object(); Ops timedOut = new Ops(); timedOut.complete = false;
        assertThrows(AtlasNative.AtlasException.class, () -> AtlasVulkan.splice(owner, timedOut));
        var failure = java.util.concurrent.CompletableFuture.<Void>failedFuture(new IllegalStateException("wait failed"));
        assertThrows(java.util.concurrent.CompletionException.class, () -> AtlasVulkan.finishPending(owner, () -> failure));
        assertThrows(IllegalStateException.class, () -> AtlasVulkan.requirePollable(owner));
        assertEquals(1, timedOut.submitted);
    }

    @Test
    void pendingOrFailedCopyRevokesActualPlayerHoldReadinessUntilDelivery() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var ctxField = AtlasClient.class.getDeclaredField("ctx");
        var failedField = AtlasClient.class.getDeclaredField("failed");
        var buildField = AtlasClient.class.getDeclaredField("ctxBuild");
        var readyField = AtlasClient.class.getDeclaredField("outputReady");
        for (var field : new java.lang.reflect.Field[]{ctxField, failedField, buildField, readyField}) field.setAccessible(true);
        Object oldCtx = ctxField.get(null), oldBuild = buildField.get(null);
        boolean oldFailed = failedField.getBoolean(null), oldReady = readyField.getBoolean(null);
        try {
            ctxField.set(null, unsafe.allocateInstance(AtlasNative.Context.class)); // identity only; no native call
            failedField.setBoolean(null, false); buildField.set(null, null);
            AtlasClient.outputDelivered();
            assertEquals("atlas_info not read", AtlasReady.pending(null));
            assertFalse(AtlasClient.copyReady(() -> false));
            assertEquals("renderer first output not delivered", AtlasReady.pending(null));
            AtlasClient.outputDelivered();
            assertThrows(IllegalStateException.class, () -> AtlasClient.copyReady(() -> { throw new IllegalStateException("copy failed"); }));
            assertEquals("renderer first output not delivered", AtlasReady.pending(null));
            AtlasClient.outputDelivered();
            assertEquals("atlas_info not read", AtlasReady.pending(null));
        } finally {
            ctxField.set(null, oldCtx); failedField.setBoolean(null, oldFailed);
            buildField.set(null, oldBuild); readyField.setBoolean(null, oldReady);
        }
    }

    @Test
    void asyncPendingSpliceDeliversConsecutiveFramesWithout30HzSkip() {
        Object owner = new Object();
        for (int frame = 0; frame < 60; frame++) {
            assertTrue(AtlasVulkan.finishPending(owner, () -> {
                fail("async pending frame handoff must not block or schedule waitCopyAsync between normal frames");
                return null;
            }));
            Ops ops = new Ops();
            ops.fail = "wait"; // proves async splice never calls synchronous waitCopy() on the render thread
            assertTrue(AtlasVulkan.splice(owner, ops, true));
            assertEquals(1, ops.recorded);
            assertEquals(1, ops.submitted);
        }
        // When the 2-slot native ring fills (sp == null) or targets close, drainPending promotes PENDING -> SUBMITTED:
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        assertFalse(AtlasVulkan.drainPending(owner, () -> completion));
        assertFalse(AtlasVulkan.finishPending(owner, () -> completion));
        completion.complete(null);
        assertTrue(AtlasVulkan.finishPending(owner, () -> completion));
    }

    @Test
    void exp3SpriteTexelAndMaterialExtractionCacheBenchmark() {
        // Simulate 512 distinct 16x16 sprites and 9,457 exact materials (matching g10-live exactIds.used=9457).
        int numSprites = 512, numMaterials = 9457;
        com.mojang.blaze3d.platform.NativeImage[] images = new com.mojang.blaze3d.platform.NativeImage[numSprites];
        for (int s = 0; s < numSprites; s++) {
            var img = new com.mojang.blaze3d.platform.NativeImage(16, 16, false);
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++)
                    img.setPixel(x, y, 0xFF000000 | ((s * 37 + x * 13) & 255) << 16 | ((s * 17 + y * 29) & 255) << 8 | ((x ^ y ^ s) & 255));
            images[s] = img;
        }
        try {
            // Baseline: every material rasterizes 6 faces x (4x4x16 + 16x16x1) = 3,072 NativeImage.getPixel calls.
            long t0 = System.nanoTime();
            long baseChecksum = 0;
            for (int m = 0; m < numMaterials; m++) {
                var img = images[m & (numSprites - 1)];
                int tint = (m * 0x13579B) & 0xF8F8F8;
                // 6 faces at TILE=4 (sub=4 -> 256 samples/face) + 6 faces at TILE=16 (sub=1 -> 256 samples/face)
                for (int f = 0; f < 12; f++) {
                    for (int y = 0; y < 16; y++)
                        for (int x = 0; x < 16; x++) {
                            int c = img.getPixel(x, y);
                            baseChecksum += (c ^ tint);
                        }
                }
            }
            double baseColdMs = (System.nanoTime() - t0) / 1e6;

            // Optimized cold: frame0Texels extracts each sprite once into int[256] + caches Extracted per (sid, q)
            long t1 = System.nanoTime();
            int[][] spriteCache = new int[numSprites][];
            long[] extractedCache = new long[numMaterials];
            long optChecksum = 0;
            for (int m = 0; m < numMaterials; m++) {
                int spIdx = m & (numSprites - 1);
                int[] f0 = spriteCache[spIdx];
                if (f0 == null) {
                    var img = images[spIdx];
                    f0 = new int[256];
                    for (int y = 0; y < 16; y++)
                        for (int x = 0; x < 16; x++) f0[x + 16 * y] = img.getPixel(x, y);
                    spriteCache[spIdx] = f0;
                }
                int tint = (m * 0x13579B) & 0xF8F8F8;
                long matSum = 0;
                for (int f = 0; f < 12; f++) {
                    for (int i = 0; i < 256; i++) matSum += (f0[i] ^ tint);
                }
                extractedCache[m] = matSum;
                optChecksum += matSum;
            }
            double optColdMs = (System.nanoTime() - t1) / 1e6;

            // Optimized warm reload (e.g. context restart / persistTo / ingestTable):
            long t2 = System.nanoTime();
            long warmChecksum = 0;
            for (int m = 0; m < numMaterials; m++) warmChecksum += extractedCache[m];
            double optWarmMs = (System.nanoTime() - t2) / 1e6;

            assertEquals(baseChecksum, optChecksum);
            assertEquals(baseChecksum, warmChecksum);
            System.out.printf(java.util.Locale.ROOT,
                    "BENCH exp3 materials=%d baseColdMs=%.2f optColdMs=%.2f optWarmMs=%.3f coldSpeedup=%.2fx%n",
                    numMaterials, baseColdMs, optColdMs, optWarmMs, baseColdMs / Math.max(0.001, optColdMs));
        } finally {
            for (var img : images) if (img != null) img.close();
        }
    }
}
