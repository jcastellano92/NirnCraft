package com.skycraft.creatures;

import com.skycraft.Skycraft;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ServerSmokeTest {
    private static final String FILE = "skycraft-smoketest.txt";
    private static Deque<String> queue;
    private static int wait;

    private ServerSmokeTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        File f = new File(event.getServer().getServerDirectory(), FILE);
        if (!f.exists()) return;
        try {
            queue = new ArrayDeque<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            Skycraft.LOGGER.info("Smoke test: {} lines from {}", queue.size(), f.getAbsolutePath());
        } catch (Exception e) {
            Skycraft.LOGGER.error("Smoke test: cannot read {}", f, e);
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || queue == null) return;
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
