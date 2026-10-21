package com.nirncraft.atlas;

import com.mojang.brigadier.Command;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Wipes atlas's caches (dev): the instance's atlas-cache/ (the self-baking cache, the horizon cache, the cold tier,
 * the server tiles a client keeps) and the single-player world's far tiles (nirncraft_atlas/tiles-*), not the
 * pipeline cache (the driver's, valid across builds). Every cache is keyed by the build anyway (a code change starts
 * fresh by itself); this is for when that is not enough.
 *
 * <p>{@code -Dnirncraft.atlas.wipeCache=true}: once, before atlas's first start this session. {@code
 * /nirncraft-atlas-wipecache}: at the next world join (the caches in use are not deleted under the running world).
 */
final class AtlasCacheWipe {
    private static volatile boolean pending = Boolean.getBoolean("nirncraft.atlas.wipeCache");

    private AtlasCacheWipe() {}

    static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nirncraft-atlas-wipecache").executes(ctx -> {
            pending = true;
            ctx.getSource().sendSuccess(() -> Component.literal("atlas caches: wiped at the next world join (leave and join again)"), false);
            return Command.SINGLE_SUCCESS;
        }));
    }

    /** Before atlas starts for a world: the wipe asked for, if any. */
    static void applyIfPending(Minecraft mc) {
        if (!pending) return;
        pending = false;
        long n = delete(mc.gameDirectory.toPath().resolve("atlas-cache"), "pipelines.bin");
        var sp = mc.getSingleplayerServer();
        if (sp != null) {
            Path root = sp.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").resolve("nirncraft_atlas");
            n += delete(root, null);
        }
        com.nirncraft.NirnCraft.LOGGER.info("atlas caches wiped ({} files)", n);
    }

    /** Deletes dir's tree, sparing files named keep (and their parent); the count deleted. */
    private static long delete(Path dir, String keep) {
        if (!Files.isDirectory(dir)) return 0;
        long[] n = {0};
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                if (p.equals(dir) || (keep != null && p.getFileName().toString().startsWith(keep))) return;
                try {
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> l = Files.list(p)) {
                            if (l.findAny().isPresent()) return;  // (kept files inside)
                        }
                    }
                    Files.deleteIfExists(p);
                    n[0]++;
                } catch (IOException e) {
                    com.nirncraft.NirnCraft.LOGGER.warn("atlas cache wipe: {} stays ({})", p, e.toString());
                }
            });
        } catch (IOException e) {
            com.nirncraft.NirnCraft.LOGGER.warn("atlas cache wipe: {}", e.toString());
        }
        return n[0];
    }
}
