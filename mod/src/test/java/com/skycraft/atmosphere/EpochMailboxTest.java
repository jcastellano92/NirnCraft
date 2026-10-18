package com.skycraft.atmosphere;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class EpochMailboxTest {
    @Test void delayedPreviousWorldCannotReplaceCurrentWorldOrBlockItsWork() throws Exception {
        List<String> released=new ArrayList<>();
        EpochMailbox<String> mailbox=new EpochMailbox<>(released::add);
        long firstWorld=mailbox.advance();
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try {
            var delayed=worker.submit(()-> {
                started.countDown();
                try { if(!finish.await(5,TimeUnit.SECONDS)) throw new AssertionError("Test release missing"); }
                catch(InterruptedException e) { throw new AssertionError(e); }
                return mailbox.publish(firstWorld,"old clouds");
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            long nextWorld=mailbox.advance();
            assertTrue(mailbox.publish(nextWorld,"new clouds"));
            finish.countDown();
            assertFalse(delayed.get(5,TimeUnit.SECONDS));
            assertEquals("new clouds",mailbox.poll());
            assertNull(mailbox.poll());
            assertEquals(List.of("old clouds"),released);
        } finally { finish.countDown(); worker.shutdownNow(); }
    }

    @Test void readyBufferIsReleasedOnReloadAndConsumedBufferIsOwnedByConsumer() {
        List<String> released=new ArrayList<>();
        EpochMailbox<String> mailbox=new EpochMailbox<>(released::add);
        long epoch=mailbox.advance();
        mailbox.publish(epoch,"ready before reload");
        epoch=mailbox.advance();
        assertNull(mailbox.poll());
        mailbox.publish(epoch,"uploaded to GPU");
        assertEquals("uploaded to GPU",mailbox.poll());
        mailbox.advance();
        assertEquals(List.of("ready before reload"),released);
    }
}
