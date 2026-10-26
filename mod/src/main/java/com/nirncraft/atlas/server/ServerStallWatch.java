package com.nirncraft.atlas.server;

import com.nirncraft.NirnCraft;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Names what the server thread is doing when one tick runs long ("Can't keep up! Running 2003-3028 ms behind"). A daemon
 * thread looks at the tick's start every 100 ms; a tick over {@code -Dnirncraft.atlas.serverStallMs} (default 400 ms) logs the
 * server thread's stack, its state and, when it is blocked, the lock and the thread that holds it, again every second the tick
 * goes on (five at most), then the tick's total. Common code (a dedicated server too): no client classes. A line a stall, and at
 * most one stall in ten seconds logged, so a loading phase's long ticks cannot flood the log.
 */
public final class ServerStallWatch {
    private ServerStallWatch() {}

    private static final long LIMIT_NS = Long.getLong("nirncraft.atlas.serverStallMs", 400L) * 1_000_000L;
    private static final long QUIET_NS = 10_000_000_000L;

    private static volatile long tickStart;       // System.nanoTime() at the tick's start, 0 between ticks
    private static volatile Thread serverThread;
    private static volatile long tickNo;
    private static volatile boolean running;
    private static Thread watcher;

    /** ServerTickEvent.Pre (the server thread). */
    public static void tickBegin(long number) {
        serverThread = Thread.currentThread();
        tickNo = number;
        tickStart = System.nanoTime();
    }

    /** ServerTickEvent.Post. */
    public static void tickEnd() {
        tickStart = 0L;
    }

    public static synchronized void start() {
        if (running) return;
        running = true;
        watcher = new Thread(ServerStallWatch::watch, "nirncraft-server-stall-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static synchronized void stop() {
        running = false;
        tickStart = 0L;
        if (watcher != null) watcher.interrupt();
        watcher = null;
    }

    private static void watch() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long loggedTick = -1, lastLogged = 0, nextSample = 0;
        int samples = 0;
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            long start = tickStart;
            Thread t = serverThread;
            if (start == 0L || t == null) {
                loggedTick = -1;
                continue;
            }
            long now = System.nanoTime(), ran = now - start;
            if (ran < LIMIT_NS) continue;
            long no = tickNo;
            if (no != loggedTick) {
                if (lastLogged != 0 && now - lastLogged < QUIET_NS) continue;
                loggedTick = no;
                samples = 0;
                nextSample = 0;
            }
            if (samples >= 5 || ran < nextSample) continue;
            samples++;
            nextSample = ran + 1_000_000_000L;
            lastLogged = now;
            StringBuilder b = new StringBuilder("server tick ").append(no).append(" has run ").append(ran / 1_000_000L).append(" ms: ");
            try {
                ThreadInfo ti = mx.getThreadInfo(t.getId(), 16);
                if (ti != null) {
                    b.append(ti.getThreadState());
                    if (ti.getLockName() != null) b.append(" on ").append(ti.getLockName()).append(" held by ").append(ti.getLockOwnerName());
                    for (StackTraceElement e : ti.getStackTrace()) b.append("\n    at ").append(e);
                }
            } catch (RuntimeException e) {
                b.append("(stack unreadable: ").append(e).append(')');
            }
            NirnCraft.LOGGER.warn("{}", b);
        }
    }
}
