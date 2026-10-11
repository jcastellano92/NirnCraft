package com.skycraft.creatures.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.anim.AnimSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Developer tool: a creature photo booth. Only active when {@code skycraft-gallery.txt} exists in the game directory.
 * It then creates (or loads) a superflat world named {@code skycraft_gallery}, and for every line of the file
 * ({@code <entity id> [variant=N] [anim=<slot>@<ticks>] [nbt={...}]}) summons the creature in front of the camera,
 * optionally plays an animation, and saves a screenshot to {@code screenshots/gallery/}. Quits when done.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, value = Dist.CLIENT)
public final class CreatureGallery {
    private static final String FILE = "skycraft-gallery.txt";
    private static final String WORLD = "skycraft_gallery";
    private static Boolean enabled;
    private static boolean worldRequested;
    private static int menuTicks;
    private static int ticks;
    private static int wait;
    private static Deque<String> queue;
    private static String current;
    private static int stage;

    private CreatureGallery() {}

    private static boolean enabled() {
        if (enabled == null) enabled = new File(Minecraft.getInstance().gameDirectory, FILE).exists();
        return enabled;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            // the main menu (vanilla's or Skycraft's own) or the first-launch accessibility screen
            if (!worldRequested && mc.screen != null && mc.getOverlay() == null && ++menuTicks > 40) {
                String screen = mc.screen.getClass().getSimpleName();
                if (mc.screen instanceof TitleScreen || screen.contains("Title") || screen.contains("Onboarding")) {
                    worldRequested = true;
                    openWorld(mc);
                }
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        if (mc.screen != null) mc.setScreen(null); // race selection, journals...: keep the view clear
        ticks++;
        if (queue == null) {
            if (ticks < 80) return;
            try {
                List<String> lines = Files.readAllLines(new File(mc.gameDirectory, FILE).toPath(), StandardCharsets.UTF_8);
                queue = new ArrayDeque<>();
                for (String l : lines) {
                    if (!l.isBlank() && !l.startsWith("#")) queue.add(l.trim());
                }
            } catch (Exception e) {
                Skycraft.LOGGER.error("Gallery: cannot read {}", FILE, e);
                queue = new ArrayDeque<>();
            }
            run(server, "gamerule doDaylightCycle false", "gamerule doMobSpawning false", "gamerule doWeatherCycle false",
                    "time set 6000", "weather clear", "difficulty easy", "gamemode spectator @a");
            mc.options.hideGui = true;
            wait = 20;
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (current == null) {
            if (queue.isEmpty()) {
                Skycraft.LOGGER.info("Gallery: done");
                mc.stop();
                return;
            }
            current = queue.poll();
            stage = 0;
        }
        String[] parts = current.split(" ");
        String id = parts[0];
        String variant = null;
        String anim = null;
        int animTicks = 10;
        String nbt = "";
        double forcedDist = -1;
        float yaw = 155f;
        String tagSuffix = "";
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("variant=")) variant = p.substring(8);
            else if (p.startsWith("anim=")) {
                String a = p.substring(5);
                int at = a.indexOf('@');
                anim = at < 0 ? a : a.substring(0, at);
                if (at >= 0) animTicks = Integer.parseInt(a.substring(at + 1));
            } else if (p.startsWith("nbt=")) nbt = p.substring(4);
            else if (p.startsWith("dist=")) forcedDist = Double.parseDouble(p.substring(5));
            else if (p.startsWith("yaw=")) {
                yaw = Float.parseFloat(p.substring(4));
                tagSuffix += "_y" + p.substring(4);
            } else if (p.startsWith("name=")) tagSuffix += "_" + p.substring(5);
        }
        EntityType<?> type = EntityType.byString(id).orElse(null);
        if (type == null) {
            Skycraft.LOGGER.warn("Gallery: unknown entity {}", id);
            current = null;
            return;
        }
        float size = Math.max(type.getHeight(), type.getWidth());
        double dist = forcedDist > 0 ? forcedDist : Math.max(2.6, size * 1.7 + 1.2);
        double eye = -60 + Math.max(0.6, type.getHeight() * 0.55);
        switch (stage) {
            case 0 -> {
                // out of sight without death animations, drops or particles (the void finishes them off)
                run(server, "tp @e[type=!minecraft:player] 0.5 -250 0.5");
                String tag = "{Tags:[\"gallery\"],NoAI:1b,PersistenceRequired:1b,Rotation:[" + yaw + "f,0f]"
                        + (variant != null ? ",Variant:" + variant : "") + (nbt.isEmpty() ? "" : "," + nbt.substring(1, nbt.length() - 1)) + "}";
                run(server, "summon " + id + " 0.5 -60 0.5 " + tag);
                run(server, String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 0 8", 0.5 - dist * 0.25, eye, 0.5 - dist));
                stage = 1;
                wait = 25;
            }
            case 1 -> {
                if (anim != null) {
                    AnimSlot slot = AnimSlot.valueOf(anim.toUpperCase(Locale.ROOT));
                    server.execute(() -> {
                        ServerLevel level = server.overworld();
                        for (Entity e : level.getAllEntities()) {
                            if (e.getTags().contains("gallery")) level.broadcastEntityEvent(e, (byte) (AnimSlot.EVENT_BASE + slot.ordinal()));
                        }
                    });
                    wait = animTicks;
                }
                stage = 2;
            }
            default -> {
                String name = id.replace(':', '_') + (variant != null ? "_v" + variant : "") + (anim != null ? "_" + anim : "") + tagSuffix;
                capture(mc, name);
                current = null;
                wait = 2;
            }
        }
    }

    private static void run(MinecraftServer server, String... commands) {
        server.execute(() -> {
            for (String c : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c);
        });
    }

    private static void openWorld(Minecraft mc) {
        try {
            if (mc.getLevelSource().levelExists(WORLD)) {
                mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);
                return;
            }
            GameRules rules = new GameRules();
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
        } catch (Exception e) {
            Skycraft.LOGGER.error("Gallery: cannot open the gallery world", e);
        }
    }

    private static void capture(Minecraft mc, String name) {
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots").resolve("gallery");
            Files.createDirectories(dir);
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            img.writeToFile(dir.resolve(name + ".png"));
            img.close();
        } catch (Exception e) {
            Skycraft.LOGGER.warn("Gallery: screenshot {} failed", name, e);
        }
    }
}
