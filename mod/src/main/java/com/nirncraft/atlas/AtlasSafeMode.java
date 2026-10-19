package com.nirncraft.atlas;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * Crash-loop guard for the far field. atlas runs native code (a Vulkan driver, shared GL memory): if a driver bug
 * kills the process while atlas starts or draws its first frames, every launch would die the same way. So atlas
 * records where it is in a small marker, {@code config/nirncraft-atlas-guard.properties}:
 * <pre>
 *   state     idle | starting | frames | running   (written on each change: four small writes per session)
 *   pid       the process that wrote it, started   its start time (a reused pid is not the same process)
 *   crashes   how many sessions in a row died in starting / frames
 *   safe      sessions run with the far field off since the last crash
 *   key       mod version + GPU + driver the record is about
 * </pre>
 * At the next start, a marker still in {@code starting} or {@code frames} whose process is gone means that session
 * died there: {@code crashes} goes up and this session starts with the far field off (one WARN line says why and how
 * to override). It is tried again after 1 safe session for the first crash, 3 after the second, and not
 * automatically after a third (until the mod, the GPU or its driver change, which resets the record). Reaching
 * {@code running} (enough good frames) clears it. Overrides: {@code -Dnirncraft.atlas.safeMode=false} (always try),
 * {@code -Dnirncraft.atlas.safeMode=true} (always off), or delete the file. A marker that cannot be written (a
 * read-only config) just disables the guard.
 *
 * <p>Pure java.nio (unit-tested without the game: AtlasSafeModeTest).</p>
 */
final class AtlasSafeMode {
    static final String FILE = "nirncraft-atlas-guard.properties";
    /** Good frames (and seconds) after which a start counts as having worked. */
    static final int RUNNING_FRAMES = 240;
    static final long RUNNING_NANOS = 8_000_000_000L;

    enum State { IDLE, STARTING, FRAMES, RUNNING }

    /** What to do this session. */
    record Decision(boolean allowed, String reason) {}

    private final Path file;
    private final long pid;
    private final Instant started;
    private final ProcessProbe probe;
    private Properties props = new Properties();
    private State state = State.IDLE;
    private boolean writable = true;
    private int goodFrames;
    private long framesSince = -1;

    /** Whether a recorded process is still alive (tests replace it). */
    interface ProcessProbe {
        boolean alive(long pid, Instant started);
    }

    static final ProcessProbe SYSTEM = (pid, started) -> {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty() || !h.get().isAlive()) return false;
        Optional<Instant> s = h.get().info().startInstant();
        // the pid was reused by another process when it started after the record's
        return s.isEmpty() || started == null || !s.get().isAfter(started.plusSeconds(2));
    };

    AtlasSafeMode(Path configDir) {
        this(configDir.resolve(FILE), ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElse(Instant.now()), SYSTEM);
    }

    AtlasSafeMode(Path file, long pid, Instant started, ProcessProbe probe) {
        this.file = file;
        this.pid = pid;
        this.started = started;
        this.probe = probe;
    }

    /**
     * Reads the marker and decides whether atlas may start this session. key: mod version, GPU and driver (a change
     * resets the record). override: the nirncraft.atlas.safeMode property (null, "true" or "false").
     */
    Decision decide(String key, String override) {
        load();
        String k = key == null ? "" : key;
        if (!k.equals(props.getProperty("key", k))) props = new Properties();  // new mod / GPU / driver: a clean slate
        props.setProperty("key", k);
        int crashes = intOf("crashes");
        int safe = intOf("safe");
        State last = stateOf(props.getProperty("state"));
        long lastPid = longOf("pid");
        Instant lastStart = instantOf(props.getProperty("started"));
        String lastPhase = props.getProperty("state", "idle");
        String lastTime = props.getProperty("time", "?");
        boolean died = (last == State.STARTING || last == State.FRAMES) && !(lastPid == pid)
                && !probe.alive(lastPid, lastStart);
        if (died) {
            crashes++;
            safe = 0;
            props.setProperty("lastCrash", lastPhase + " at " + lastTime);
        }
        props.setProperty("crashes", Integer.toString(crashes));
        if ("false".equalsIgnoreCase(override)) {
            props.setProperty("safe", Integer.toString(safe));
            save(State.IDLE);
            return new Decision(true, died ? "the last session ended during atlas's " + lastPhase
                    + " phase, but nirncraft.atlas.safeMode=false" : null);
        }
        if ("true".equalsIgnoreCase(override)) {
            save(State.IDLE);
            return new Decision(false, "nirncraft.atlas.safeMode=true");
        }
        int wait = crashes <= 0 ? 0 : crashes == 1 ? 1 : crashes == 2 ? 3 : Integer.MAX_VALUE;
        if (crashes > 0 && safe < wait) {
            props.setProperty("safe", Integer.toString(safe + 1));
            save(State.IDLE);
            String when = wait == Integer.MAX_VALUE ? "it stays off until the mod, the GPU or its driver changes"
                    : "it is tried again after " + (wait - safe - 1) + " more session(s)";
            return new Decision(false, (died ? "the last session" : crashes + " earlier session(s)") + " ended while the far "
                    + "field was starting (" + props.getProperty("lastCrash", "?") + "): safe mode, far field off this session; "
                    + when + " (-Dnirncraft.atlas.safeMode=false forces it on, or delete config/" + FILE + ")");
        }
        props.setProperty("safe", Integer.toString(safe));
        save(State.IDLE);
        return new Decision(true, crashes > 0 ? "retrying after " + crashes + " crash(es) during start-up" : null);
    }

    /** atlas is about to load the library / create its context. */
    void starting() {
        save(State.STARTING);
    }

    /** The first frame is about to be drawn. */
    void firstFrames() {
        if (state == State.STARTING || state == State.IDLE) save(State.FRAMES);
    }

    /** One good frame: after RUNNING_FRAMES of them over RUNNING_NANOS the start counts as a success. */
    void frame(long now) {
        if (state != State.FRAMES) return;
        if (framesSince < 0) framesSince = now;
        if (++goodFrames >= RUNNING_FRAMES && now - framesSince >= RUNNING_NANOS) {
            props.setProperty("crashes", "0");
            props.setProperty("safe", "0");
            props.remove("lastCrash");
            save(State.RUNNING);
        }
    }

    /** atlas stopped on purpose (an error it handled, the far field turned off, the game closing): not a crash. */
    void stopped() {
        if (state == State.STARTING || state == State.FRAMES) save(State.IDLE);
        goodFrames = 0;
        framesSince = -1;
    }

    State state() {
        return state;
    }

    boolean writable() {
        return writable;
    }

    // ---------------------------------------------------------------- file

    private void load() {
        props = new Properties();
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException | RuntimeException e) {
            props = new Properties();  // unreadable: start over
        }
    }

    private void save(State s) {
        state = s;
        if (!writable) return;
        props.setProperty("state", s.name().toLowerCase(java.util.Locale.ROOT));
        props.setProperty("pid", Long.toString(pid));
        props.setProperty("started", started.toString());
        props.setProperty("time", Instant.now().toString());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(w, "NirnCraft far-field crash guard (see AtlasSafeMode); delete to reset");
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            writable = false;  // a read-only config: no guard, atlas still runs
        }
    }

    private int intOf(String k) {
        try {
            return Math.max(0, Integer.parseInt(props.getProperty(k, "0").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long longOf(String k) {
        try {
            return Long.parseLong(props.getProperty(k, "-1").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Instant instantOf(String s) {
        try {
            return s == null ? null : Instant.parse(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static State stateOf(String s) {
        if (s == null) return State.IDLE;
        try {
            return State.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return State.IDLE;
        }
    }
}
