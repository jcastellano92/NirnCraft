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
}
