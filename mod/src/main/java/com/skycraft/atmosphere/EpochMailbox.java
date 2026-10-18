package com.skycraft.atmosphere;

import java.util.function.Consumer;

/** Single-result worker handoff. Advancing the owner epoch releases every stale result exactly once. */
public final class EpochMailbox<T> {
    private final Consumer<T> release;
    private long epoch;
    private T ready;

    public EpochMailbox(Consumer<T> release) { this.release=release; }

    public synchronized long advance() {
        epoch++;
        if(ready!=null) { release.accept(ready); ready=null; }
        return epoch;
    }

    public synchronized boolean publish(long owner,T value) {
        if(owner!=epoch) { release.accept(value); return false; }
        if(ready!=null) release.accept(ready);
        ready=value;
        return true;
    }

    public synchronized T poll() {
        T value=ready;
        ready=null;
        return value;
    }
}
