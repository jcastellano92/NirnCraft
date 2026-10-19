/*
 * Resource bootstrap derived from NeoForge EphemeralTestServerProvider.
 * Copyright (c) NeoForged and contributors; SPDX-License-Identifier: LGPL-2.1-only.
 * Source: testframework 26.3.0.90, net/neoforged/testframework/junit/EphemeralTestServerProvider.java.
 */
package com.nirncraft.testing;

import com.mojang.serialization.Lifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.commands.Commands;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.Difficulty;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Real NeoForge data-only server with bounded, joined, single-owner shutdown before FML closes. */
final class ManagedNativeTestServer implements ExtensionContext.Store.CloseableResource {
    private static final Duration CLEANUP_LIMIT = Duration.ofSeconds(15);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicReference<JUnitServer> server = new AtomicReference<>();
    private final FutureTask<MinecraftServer> startup;
    private final Thread bootstrap;

    ManagedNativeTestServer() {
        startup = new FutureTask<>(this::start);
        bootstrap = new Thread(startup, "NirnCraft managed native test server startup");
        bootstrap.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        bootstrap.setDaemon(true);
        // The callback runs while FML and all game classes are still available. The root JUnit
        // store normally closes first; this also covers engine startup/parameter-resolution failure.
        FMLLoader.getCurrent().addCloseCallback(this::close);
        bootstrap.start();
    }

    Future<MinecraftServer> startup() { return startup; }

    String startupFailure() {
        JUnitServer current = server.get();
        if (current != null && (current.isStopped() || current.getRunningThread().getState() == Thread.State.TERMINATED))
            return "Managed native test server stopped before startup completed";
        return closing.get() ? "Managed native test server is closing" : null;
    }

    MinecraftServer currentServer() { return server.get(); }

    private MinecraftServer start() throws Exception {
        Path temp = Files.createTempDirectory("nirncraft-test-mc-server-");
        LevelStorageSource.LevelStorageAccess access = null;
        try {
            LevelStorageSource storage = LevelStorageSource.createDefault(temp.resolve("world"));
            access = storage.validateAndCreateAccess("main");
            PackRepository packs = ServerPacksSource.createPackRepository(access);
            LevelStorageSource.LevelStorageAccess ownedAccess = access;
            JUnitServer created = MinecraftServer.spin(thread -> {
                JUnitServer nativeServer = create(thread, temp, ownedAccess, packs);
                server.set(nativeServer);
                if (closing.get()) nativeServer.halt(false);
                return nativeServer;
            });
            return created.ready.get();
        } catch (Exception | Error failure) {
            // Once constructed, only the native server loop owns its stop/files. Prior to that,
            // this bootstrap owns the open storage lock and can release its failed staging area.
            if (server.get() == null) {
                if (access != null) try { access.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                try { FileUtils.deleteDirectory(temp.toFile()); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static JUnitServer create(Thread thread, Path temp, LevelStorageSource.LevelStorageAccess access, PackRepository packs) {
        packs.reload();
        WorldDataConfiguration config = new WorldDataConfiguration(
                new DataPackConfig(new ArrayList<>(packs.getAvailableIds()), List.of()), FeatureFlags.REGISTRY.allFlags());
        LevelSettings settings = new LevelSettings("Test Level", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, config);
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, config, false, true);
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.OWNER);
        try {
            WorldStem stem = Util.blockUntilDone(exec -> WorldLoader.load(init, context -> {
                Registry<LevelStem> registry = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();
                WorldDimensions dimensions = context.datapackWorldRegistries().lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions();
                WorldDimensions.Complete complete = dimensions.bake(registry);
                var data = new LevelDataAndDimensions.WorldDataAndGenSettings(
                        new PrimaryLevelData(settings, complete.specialWorldProperty(), complete.lifecycle()),
                        new WorldGenSettings(new WorldOptions(0L, false, false), dimensions));
                return new WorldLoader.DataLoadOutput<>(data, complete.dimensionsRegistryAccess());
            }, WorldStem::new, Util.backgroundExecutor(), exec)).get();
            try {
                return new JUnitServer(thread, access, packs, stem, temp);
            } catch (RuntimeException | Error failure) {
                stem.close();
                throw failure;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted loading actual native test datapacks", interrupted);
        } catch (Exception failure) {
            // Preserve the actual decode exception for JUnit instead of the upstream System.exit(-1).
            throw new IllegalStateException("Failed loading actual native test datapacks", failure);
        }
    }

    @Override public synchronized void close() throws Exception {
        if (!closing.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + CLEANUP_LIMIT.toNanos();
        JUnitServer current = server.get();
        if (current != null) current.halt(false);
        startup.cancel(true);
        try {
            join(bootstrap, deadline);
            // Bootstrap may have completed construction as cancellation arrived.
            current = server.get();
            if (current != null) {
                current.halt(false);
                join(current.getRunningThread(), deadline);
                if (current.stopFailure.get() != null)
                    throw new IllegalStateException("Native test server cleanup failed", current.stopFailure.get());
                if (Files.exists(current.ownedTemp))
                    throw new java.io.IOException("Native test server retained its temporary world: " + current.ownedTemp);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void join(Thread thread, long deadline) throws InterruptedException {
        if (thread == Thread.currentThread()) throw new IllegalStateException("Cannot join the native server from its own thread");
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Native test shutdown exceeded " + CLEANUP_LIMIT.toSeconds() + " seconds: " + thread.getName());
            thread.join(Duration.ofNanos(remaining));
        }
    }

    private static final class JUnitServer extends EphemeralTestServerProvider.JUnitServer {
        private final AtomicBoolean stopOnce = new AtomicBoolean();
        private final AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        private final CompletableFuture<MinecraftServer> ready = new CompletableFuture<>();
        private final Path ownedTemp;

        JUnitServer(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs, WorldStem stem, Path temp) {
            super(thread, access, packs, stem, temp);
            ownedTemp = temp;
        }

        @Override public void tickServer(BooleanSupplier budget) {
            super.tickServer(budget);
            ready.complete(this);
        }

        @Override public void stopServer() {
            if (stopOnce.compareAndSet(false, true)) {
                try { super.stopServer(); }
                catch (RuntimeException | Error failure) { stopFailure.set(failure); throw failure; }
            }
        }
    }
}
