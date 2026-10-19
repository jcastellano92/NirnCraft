package com.nirncraft.client.autotest;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Capture-only FIFO observation. A tick must be able to consume a probe completed by the preceding render update. */
final class LightQueueReadiness {
    private Object level;
    private AtomicBoolean witness;

    void reset() { level=null; witness=null; }

    void queueIfAbsent(Object currentLevel, Consumer<Runnable> queue) {
        if(level!=currentLevel || witness==null) enqueue(currentLevel,queue);
    }

    boolean isComplete(Object currentLevel) { return level==currentLevel && witness!=null && witness.get(); }

    void renewIfComplete(Object currentLevel, Consumer<Runnable> queue) {
        if(isComplete(currentLevel)) enqueue(currentLevel,queue);
    }

    private void enqueue(Object currentLevel, Consumer<Runnable> queue) {
        var marker=new AtomicBoolean();level=currentLevel;witness=marker;
        queue.accept(()->marker.set(true));
    }
}
