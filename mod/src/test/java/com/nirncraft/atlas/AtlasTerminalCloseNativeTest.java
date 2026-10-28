package com.nirncraft.atlas;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real FFM/status/ticket bridge, CPU-only. Shared-device producer/consumer failures have separate native GPU cases. */
class AtlasTerminalCloseNativeTest {
    private static AtlasNative fixture() {
        String value = System.getenv("ATLAS_TERMINAL_CLOSE_TEST_LIBRARY");
        assumeTrue(value != null && !value.isBlank(), "UNVERIFIED: explicit freshly built ABI0.19 native fixture required");
        Path path = Path.of(value).toAbsolutePath();
        assertTrue(Files.isRegularFile(path), "explicit native fixture is missing");
        AtlasNative nativeApi = AtlasNative.load(path);
        assertTrue(nativeApi.hasCreateOnVulkan(), "fixture must bind the actual new native checked-close symbol");
        return nativeApi;
    }

    @Test
    void actualNativeUnsupportedStatusThrowsThroughContextFfm() {
        try (var context = fixture().create(0)) {
            var error = assertThrows(AtlasNative.AtlasException.class, context::vkPrepareClose);
            assertEquals(AtlasNative.ERR_UNSUPPORTED, error.status);
            assertTrue(error.getMessage().contains("atlas_vk_prepare_close"));
            assertTrue(context.info().contains("cpu")); // unsupported must not seal/delete a CPU context
        }
    }

    @Test
    void actualNativeFailureRetainsProductionTicketAndStoppingBarrier() throws Exception {
        try (var context = fixture().create(0)) {
            // Deliberately incompatible backend: the real C entry returns an error through the production FFM
            // wrapper and production ReleaseTicket constructor. No fake detach endpoint or Vulkan device is used.
            var ticket = new AtlasClient.ReleaseTicket(context, AtlasVulkan.Path.VK_SHARED, null,
                    new AtomicReference<>(), null, null, new CompletableFuture<>(), null, null);
            ticket.launch();
            assertThrows(ExecutionException.class, () -> ticket.done.get(2, TimeUnit.SECONDS));
            assertInstanceOf(AtlasNative.AtlasException.class, ticket.failure);
            assertEquals(AtlasNative.ERR_UNSUPPORTED, ((AtlasNative.AtlasException) ticket.failure).status);
            assertFalse(ticket.ready);
            assertFalse(ticket.renderDone.isDone());
            var tickets = new ArrayList<AtlasClient.ReleaseTicket>();
            tickets.add(ticket);
            var hostQueueTouches = new AtomicInteger();
            AtlasClient.pumpReleases(tickets, token -> hostQueueTouches.incrementAndGet());
            assertEquals(1, tickets.size(), "exceptional ticket must remain in the actual pump");
            assertFalse(AtlasClient.releaseBarrierSatisfied(true, tickets), "ClientStopping must not return into device.close");
            assertEquals(0, hostQueueTouches.get());
            assertTrue(context.info().contains("cpu"), "release worker must not invoke context.close after native failure");
        }
    }

    @Test
    void preAttachBuildUsesActualOwnershipFlagBeforePathSelection() throws Exception {
        try (var context = fixture().create(0)) {
            var build = CompletableFuture.completedFuture(new Object[]{context, false, true});
            var ticket = new AtlasClient.ReleaseTicket(null, AtlasVulkan.Path.VK_SHARED, null,
                    new AtomicReference<>(), null, null, new CompletableFuture<>(), build, null);
            ticket.launch();
            assertThrows(ExecutionException.class, () -> ticket.done.get(2, TimeUnit.SECONDS));
            assertInstanceOf(AtlasNative.AtlasException.class, ticket.failure);
            assertEquals(AtlasNative.ERR_UNSUPPORTED, ((AtlasNative.AtlasException) ticket.failure).status);
            assertFalse(ticket.ready);
            var tickets = new ArrayList<AtlasClient.ReleaseTicket>(); tickets.add(ticket);
            AtlasClient.pumpReleases(tickets, token -> fail("unattached pending build must not retire a host token"));
            assertFalse(AtlasClient.releaseBarrierSatisfied(true, tickets));
            assertTrue(context.info().contains("cpu"), "failed pending-build prepare must retain its context");
        }
    }
}
