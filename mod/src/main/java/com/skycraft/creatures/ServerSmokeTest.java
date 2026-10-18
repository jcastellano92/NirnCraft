package com.skycraft.creatures;

import com.skycraft.Skycraft;
import net.minecraft.server.MinecraftServer;

import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Developer tool for dedicated-server smoke tests: when {@code skycraft-smoketest.txt} exists in the server
 * directory, its lines run as console commands once the server has started, one per tick ({@code wait <ticks>} lines
 * pause). Lets automated tests summon creatures, make them fight and stop the server without a console.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class ServerSmokeTest {
    private static final String FILE = "skycraft-smoketest.txt";
    private static Deque<String> queue;
    private static int wait;

    private ServerSmokeTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (event.getServer().overworld() == null) return;
        File f = event.getServer().getServerDirectory().resolve(FILE).toFile();
        if (!f.exists()) return;
        try {
            queue = new ArrayDeque<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            Skycraft.LOGGER.info("Smoke test: {} lines from {}", queue.size(), f.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.error("Smoke test: cannot read {}", f, e);
        }
    }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || queue == null) return;
        if (wait > 0) {
            wait--;
            return;
        }
        MinecraftServer server = event.getServer();
        while (!queue.isEmpty()) {
            String line = queue.poll().trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("wait ")) {
                wait = Integer.parseInt(line.substring(5).trim());
                return;
            }
            Skycraft.LOGGER.info("Smoke test> {}", line);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), line);
            return;
        }
        queue = null;
    }
}
