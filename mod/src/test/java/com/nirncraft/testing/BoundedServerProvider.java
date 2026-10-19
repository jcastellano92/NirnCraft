package com.nirncraft.testing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Bounds a managed instance of the real native JUnitServer, including joined shutdown before FML closes. */
public final class BoundedServerProvider implements ParameterResolver {
    private static final Duration STARTUP_LIMIT = Duration.ofSeconds(90);
    private static final ExtensionContext.Namespace SERVERS = ExtensionContext.Namespace.create(BoundedServerProvider.class);

    @Override public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == MinecraftServer.class;
    }

    @Override public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        ManagedNativeTestServer managed = context.getRoot().getStore(SERVERS).getOrComputeIfAbsent(
                ManagedNativeTestServer.class, ignored -> new ManagedNativeTestServer(), ManagedNativeTestServer.class);
        try {
            return await(managed.startup(), STARTUP_LIMIT, managed::startupFailure);
        } catch (InterruptedException interrupted) {
            abort(managed, "JUnit worker interrupted during native server startup", interrupted);
            Thread.currentThread().interrupt();
            throw new ParameterResolutionException("Interrupted during managed native test server startup", interrupted);
        } catch (ExecutionException | TimeoutException | IllegalStateException failure) {
            Path evidence = abort(managed, failure.toString(), failure);
            throw new ParameterResolutionException("Managed native test server startup failed; evidence: " + evidence, failure);
        }
    }

    static <T> T await(Future<T> startup, Duration limit, Supplier<String> failure) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            String reason = failure.get();
            if (reason != null) throw new IllegalStateException(reason);
            if (startup.isDone()) return startup.get();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Native test server did not reach its first tick within " + limit.toSeconds() + " seconds");
            try {
                return startup.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException pending) {
                // A completed future returns immediately; poll only to detect an actual server-thread crash early.
            }
        }
    }

    private static Path abort(ManagedNativeTestServer managed, String reason, Throwable failure) {
        Path evidence = Path.of("nirncraft-test-diagnostics", "server-startup-" + System.currentTimeMillis() + ".txt").toAbsolutePath();
        StringBuilder dump = new StringBuilder(reason).append('\n').append(failure).append('\n');
        MinecraftServer server = managed.currentServer();
        dump.append("startupComplete=").append(managed.startup().isDone()).append('\n');
        if (server != null) dump.append("serverRunning=").append(server.isRunning()).append(", serverStopped=").append(server.isStopped()).append('\n');
        for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
            dump.append('\n').append(thread.getKey().getName()).append(" [").append(thread.getKey().getState()).append("]\n");
            for (StackTraceElement frame : thread.getValue()) dump.append("  at ").append(frame).append('\n');
        }
        try {
            Files.createDirectories(evidence.getParent());
            Files.writeString(evidence, dump, StandardCharsets.UTF_8);
        } catch (java.io.IOException io) { failure.addSuppressed(io); }
        try { managed.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
        return evidence;
    }
}
