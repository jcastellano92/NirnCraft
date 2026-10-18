package com.skycraft.realms;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/**
 * {@code /skycraft-realm} (operators): {@code go <realm> [chapter]} travels like a gate would (from Nirn, the Deadlands
 * keep of your cell), {@code home} returns, {@code where} prints the landmarks, and {@code map <realm> <x> <z> <radius>}
 * generates a square of a realm and writes a shaded top-down map to {@code realm-maps/} (worldgen smoke tests).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class RealmCommands {
    private RealmCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-realm").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("go").then(Commands.argument("realm", StringArgumentType.word())
                        .executes(c -> go(c, 1))
                        .then(Commands.argument("param", IntegerArgumentType.integer(0, 64)).executes(c -> go(c, IntegerArgumentType.getInteger(c, "param"))))))
                .then(Commands.literal("home").executes(c -> {
                    RealmTravel.returnHome(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("where").executes(RealmCommands::where))
                .then(Commands.literal("look").then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .then(Commands.argument("dx", IntegerArgumentType.integer())
                                        .then(Commands.argument("dz", IntegerArgumentType.integer())
                                                .then(Commands.argument("up", IntegerArgumentType.integer(-20, 200)).executes(RealmCommands::look)))))))
                .then(Commands.literal("map").then(Commands.argument("realm", StringArgumentType.word())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 1024)).executes(RealmCommands::map)))))));
    }

    /**
     * Worldgen smoke test for dedicated servers (dev only): {@code -Dskycraft.realms.smoke=<file>} runs each line of the
     * file as a console command once the server has started, one per few ticks, then stops the server.
     */
    @SubscribeEvent
    public static void smoke(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        String file = System.getProperty("skycraft.realms.smoke", "");
        if (file.isEmpty()) return;
        java.util.List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(file));
        } catch (Exception e) {
            Skycraft.LOGGER.error("Realm smoke test: cannot read {}", file, e);
            return;
        }
        var server = event.getServer();
        int delay = 40;
        for (String line : lines) {
            String cmd = line.trim();
            if (cmd.isEmpty() || cmd.startsWith("#")) continue;
            RealmTravel.schedule(server, delay, () -> {
                Skycraft.LOGGER.info("Realm smoke test: /{}", cmd);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            });
            delay += 20;
        }
        RealmTravel.schedule(server, delay + 100, () -> {
            Skycraft.LOGGER.info("Realm smoke test finished");
            server.halt(false);
        });
    }

    private static int go(CommandContext<CommandSourceStack> c, int param) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Realm r = Realm.byId(StringArgumentType.getString(c, "realm"));
        if (r == null) {
            c.getSource().sendFailure(Component.literal("Unknown realm: deadlands, sovngarde, shivering_isles, apocrypha, soul_cairn, coldharbour"));
            return 0;
        }
        if (r == Realm.DEADLANDS) {
            com.skycraft.realms.gate.OblivionGates.enterNear(p);
        } else {
            RealmTravel.enter(p, r, RealmTravel.arrival(r, p, r == Realm.APOCRYPHA ? param : 0));
        }
        return 1;
    }

    /** Stands the player {@code dx, dz} away from (x, z), {@code up} above the ground, looking at the target. */
    private static int look(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel level = p.level();
        int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
        int vx = x + IntegerArgumentType.getInteger(c, "dx"), vz = z + IntegerArgumentType.getInteger(c, "dz");
        level.getChunk(x >> 4, z >> 4);
        level.getChunk(vx >> 4, vz >> 4);
        int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int vy = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, vx, vz), ty) + IntegerArgumentType.getInteger(c, "up");
        double dx = x + 0.5 - (vx + 0.5), dz = z + 0.5 - (vz + 0.5), dy = ty + 3 - (vy + 1.6);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.teleportTo(level, vx + 0.5, vy, vz + 0.5, java.util.Set.of(),  yaw, pitch, true);
        return 1;
    }

    private static int where(CommandContext<CommandSourceStack> c) {
        CommandSourceStack s = c.getSource();
        s.sendSuccess(() -> Component.literal("Deadlands: a Sigil Keep every " + RealmLayout.DL_SPACING + " blocks (tower at cell*640+8, arrival +"
                + RealmLayout.DL_ARRIVAL_DZ + " z)"), false);
        s.sendSuccess(() -> Component.literal("Sovngarde: arrival 0," + RealmLayout.SG_ARRIVAL_Z + "; arena 0,0; bridge z "
                + RealmLayout.SG_BRIDGE_SOUTH + ".." + RealmLayout.SG_BRIDGE_NORTH + "; Hall of Valor 0," + RealmLayout.SG_HALL_Z), false);
        s.sendSuccess(() -> Component.literal("Shivering Isles: Fringe " + RealmLayout.SI_FRINGE_X + ",0; New Sheoth 0,0"), false);
        s.sendSuccess(() -> Component.literal("Apocrypha: chapters every " + RealmLayout.AP_SPACING + "; Summit at 8,8"), false);
        s.sendSuccess(() -> Component.literal("Soul Cairn: arrival 0," + RealmLayout.SC_ARRIVAL_Z + "; keep 0," + RealmLayout.SC_KEEP_Z
                + "; boneyard " + RealmLayout.SC_BONEYARD_X + "," + RealmLayout.SC_BONEYARD_Z), false);
        s.sendSuccess(() -> Component.literal("Coldharbour: arrival 0," + RealmLayout.CH_ARRIVAL_Z + "; Hollow City 0," + RealmLayout.CH_CITY_Z), false);
        return 1;
    }

    /** Generates (synchronously) a square of a realm and writes a shaded relief map. Slow: testing only. */
    private static int map(CommandContext<CommandSourceStack> c) {
        Realm r = Realm.byId(StringArgumentType.getString(c, "realm"));
        if (r == null) return 0;
        ServerLevel level = c.getSource().getServer().getLevel(r.dimension);
        if (level == null) return 0;
        int cx = IntegerArgumentType.getInteger(c, "x"), cz = IntegerArgumentType.getInteger(c, "z");
        int rad = IntegerArgumentType.getInteger(c, "radius");
        int size = rad * 2;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int[] heights = new int[size * size];
        Arrays.fill(heights, level.getMinY());
        long t0 = System.nanoTime();
        int chunks = 0;
        for (int qx = (cx - rad) >> 4; qx <= (cx + rad - 1) >> 4; qx++)
            for (int qz = (cz - rad) >> 4; qz <= (cz + rad - 1) >> 4; qz++) {
                ChunkAccess chunk = level.getChunk(qx, qz);
                chunks++;
                for (int lx = 0; lx < 16; lx++)
                    for (int lz = 0; lz < 16; lz++) {
                        int x = (qx << 4) + lx, z = (qz << 4) + lz;
                        int ix = x - (cx - rad), iz = z - (cz - rad);
                        if (ix < 0 || iz < 0 || ix >= size || iz >= size) continue;
                        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                        heights[iz * size + ix] = y;
                        BlockState s = chunk.getBlockState(new BlockPos(x, y, z));
                        MapColor mc = s.getMapColor(level, new BlockPos(x, y, z));
                        int rgb = mc == MapColor.NONE ? 0x101010 : mc.col;
                        img.setRGB(ix, iz, rgb);
                    }
            }
        // hill shading from the heights
        for (int iz = 1; iz < size; iz++)
            for (int ix = 1; ix < size; ix++) {
                int h = heights[iz * size + ix], hw = heights[iz * size + ix - 1], hn = heights[(iz - 1) * size + ix];
                double shade = 1.0 + ((h - hw) + (h - hn)) * 0.06 + (h - 64) * 0.002;
                shade = Math.max(0.45, Math.min(1.5, shade));
                int rgb = img.getRGB(ix, iz);
                int rr = (int) Math.min(255, ((rgb >> 16) & 255) * shade), gg = (int) Math.min(255, ((rgb >> 8) & 255) * shade),
                        bb = (int) Math.min(255, (rgb & 255) * shade);
                img.setRGB(ix, iz, (rr << 16) | (gg << 8) | bb);
            }
        File dir = new File("realm-maps");
        dir.mkdirs();
        File out = new File(dir, r.id + "_" + cx + "_" + cz + "_" + rad + ".png");
        try {
            ImageIO.write(img, "png", out);
        } catch (Exception e) {
            Skycraft.LOGGER.error("Could not write realm map", e);
            return 0;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int n = chunks;
        Skycraft.LOGGER.info("Realm map {}: {} chunks in {} ms -> {}", r.id, n, ms, out.getAbsolutePath());
        c.getSource().sendSuccess(() -> Component.literal("Wrote " + out.getPath() + " (" + n + " chunks, " + ms + " ms)"), false);
        return 1;
    }
}
