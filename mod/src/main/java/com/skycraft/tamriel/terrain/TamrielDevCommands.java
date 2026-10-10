package com.skycraft.tamriel.terrain;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * Developer commands (permission level 2) for checking the Tamriel terrain without exploring:
 * <ul>
 *   <li>{@code /skycraft-tamriel where} and {@code /skycraft-tamriel sample <x> <z>}: the map at a column.</li>
 *   <li>{@code /skycraft-tamriel heightmap <x> <z> <radius> [step]}: writes a hypsometric PNG of the real generated
 *       surface heights (the noise generator's base height, no chunks are loaded) to
 *       {@code skycraft-heightmaps/} in the server folder, off the server thread.</li>
 *   <li>{@code /skycraft-tamriel bench}: times {@link TamrielMap#sample} and the generator's column heights.</li>
 * </ul>
 */
public final class TamrielDevCommands {
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private TamrielDevCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-tamriel").requires(s -> s.hasPermission(2))
                .then(Commands.literal("where").executes(c -> {
                    Vec3 p = c.getSource().getPosition();
                    return describe(c.getSource(), (int) Math.floor(p.x), (int) Math.floor(p.z));
                }))
                .then(Commands.literal("sample")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(c -> describe(c.getSource(), IntegerArgumentType.getInteger(c, "x"),
                                                IntegerArgumentType.getInteger(c, "z"))))))
                .then(Commands.literal("heightmap")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 20000))
                                                .executes(c -> heightmap(c, 0))
                                                .then(Commands.argument("step", IntegerArgumentType.integer(1, 256))
                                                        .executes(c -> heightmap(c, IntegerArgumentType.getInteger(c, "step"))))))))
                .then(Commands.literal("bench").executes(TamrielDevCommands::bench)));
    }

    private static int describe(CommandSourceStack src, int x, int z) {
        MapSample s = new MapSample();
        TamrielMap.sample(x, z, s);
        Region r = Regions.byIndex(s.region);
        Region b = Regions.byIndex(s.blendRegion);
        ServerLevel level = src.getLevel();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "(%d, %d) ", x, z)).append(r.name())
                .append(String.format(Locale.ROOT, " [%s, %s, %s, levels %d-%d, idx %d]", r.id(), r.kind(), r.culture(),
                        r.minLevel(), r.maxLevel(), s.region)), false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "map y %.1f, surface y %d, coast %.0f, ridge %.2f, rough %.2f, crag %.2f, dunes %.2f, wet %.2f, road %.2f",
                s.elevation, surface, s.coastDistance, s.ridge, s.roughness, s.crag, s.dunes, s.wetland, s.road)), false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "water %s, river %.2f (%.0f blocks, half width %.0f), lake %.2f (%.0f), blend %s %.2f, tamriel %s",
                Float.isNaN(s.waterLevel) ? "none" : String.format(Locale.ROOT, "%.1f", s.waterLevel), s.river,
                s.riverDistance, s.riverHalfWidth, s.lake, s.lakeDistance, b.id(), s.blendWeight, s.inTamriel)), false);
        return 1;
    }

    private static int heightmap(CommandContext<CommandSourceStack> c, int stepArg) {
        CommandSourceStack src = c.getSource();
        int cx = IntegerArgumentType.getInteger(c, "x"), cz = IntegerArgumentType.getInteger(c, "z");
        int radius = IntegerArgumentType.getInteger(c, "radius");
        int step = stepArg > 0 ? stepArg : Math.max(1, radius * 2 / 512);
        int size = radius * 2 / step;
        if (size > 2048) {
            src.sendFailure(Component.literal("Too many pixels (" + size + "), use a larger step"));
            return 0;
        }
        if (!BUSY.compareAndSet(false, true)) {
            src.sendFailure(Component.literal("A heightmap is already being rendered"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        MinecraftServer server = level.getServer();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        File dir = server.getServerDirectory().toPath().resolve("skycraft-heightmaps").toFile();
        src.sendSuccess(() -> Component.literal("Rendering " + size + "x" + size + " heightmap..."), false);
        Thread t = new Thread(() -> {
            try {
                long t0 = System.nanoTime();
                int[] heights = new int[size * size];
                IntStream.range(0, size).parallel().forEach(j -> {
                    for (int i = 0; i < size; i++) {
                        int x = cx - radius + i * step, z = cz - radius + j * step;
                        heights[j * size + i] = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                    }
                });
                long ns = System.nanoTime() - t0;
                BufferedImage img = render(heights, size, step, cx, cz, radius);
                dir.mkdirs();
                File f = new File(dir, String.format(Locale.ROOT, "hm_%d_%d_r%d_s%d.png", cx, cz, radius, step));
                ImageIO.write(img, "png", f);
                String msg = String.format(Locale.ROOT, "Wrote %s (%d columns, %.1f us/column)", f.getPath(),
                        heights.length, ns / 1000.0 / heights.length);
                Skycraft.LOGGER.info(msg);
                server.execute(() -> src.sendSuccess(() -> Component.literal(msg), false));
            } catch (Exception e) {
                Skycraft.LOGGER.error("Heightmap render failed", e);
                server.execute(() -> src.sendFailure(Component.literal("Heightmap failed: " + e)));
            } finally {
                BUSY.set(false);
            }
        }, "Skycraft heightmap");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static BufferedImage render(int[] h, int size, int step, int cx, int cz, int radius) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        MapSample s = new MapSample(), o = new MapSample();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int y = h[j * size + i];
                int x = cx - radius + i * step, z = cz - radius + j * step;
                TamrielMap.sample(x, z, s);
                boolean water = y < TamrielMap.SEA_LEVEL || (s.inWater() && y < s.waterLevel);
                int yl = h[j * size + Math.max(0, i - 1)], yu = h[Math.max(0, j - 1) * size + i];
                double shade = MapNoise.clamp(1.0 + ((y - yl) + (y - yu)) * 0.6 / step, 0.45, 1.4);
                int rgb = water ? waterColor(Math.min(y, TamrielMap.SEA_LEVEL)) : landColor(y);
                if (!water) rgb = scale(rgb, shade);
                if (i > 0) {
                    // thin region borders
                    TamrielMap.sample(x - step, z, o);
                    if (o.region != s.region && !water) rgb = scale(rgb, 0.6);
                }
                img.setRGB(i, j, rgb);
            }
        }
        return img;
    }

    private static int waterColor(int y) {
        double t = MapNoise.clamp((TamrielMap.SEA_LEVEL - y) / 70.0, 0, 1);
        return rgb(70 - 50 * t, 120 - 70 * t, 200 - 90 * t);
    }

    private static int landColor(int y) {
        double[][] stops = {{63, 200, 190, 140}, {66, 150, 175, 95}, {90, 95, 145, 70}, {130, 70, 115, 55},
                {170, 125, 115, 75}, {220, 140, 130, 115}, {270, 185, 185, 185}, {330, 245, 245, 250}, {400, 255, 255, 255}};
        for (int k = 0; k < stops.length - 1; k++) {
            if (y < stops[k + 1][0]) {
                double t = MapNoise.clamp((y - stops[k][0]) / (stops[k + 1][0] - stops[k][0]), 0, 1);
                return rgb(stops[k][1] + (stops[k + 1][1] - stops[k][1]) * t, stops[k][2] + (stops[k + 1][2] - stops[k][2]) * t,
                        stops[k][3] + (stops[k + 1][3] - stops[k][3]) * t);
            }
        }
        return rgb(255, 255, 255);
    }

    private static int scale(int rgb, double f) {
        return rgb(((rgb >> 16) & 255) * f, ((rgb >> 8) & 255) * f, (rgb & 255) * f);
    }

    private static int rgb(double r, double g, double b) {
        return ((int) MapNoise.clamp(r, 0, 255) << 16) | ((int) MapNoise.clamp(g, 0, 255) << 8) | (int) MapNoise.clamp(b, 0, 255);
    }

    private static int bench(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        MapSample s = new MapSample();
        java.util.Random rnd = new java.util.Random(42);
        // worldgen samples whole chunks at a time, so time random chunks swept column by column
        int n = 2_000_000;
        int[] xs = new int[n], zs = new int[n];
        for (int ch = 0; ch < n / 256; ch++) {
            int cx = (rnd.nextInt(24000) - 12000) & ~15, cz = (rnd.nextInt(20000) - 4000) & ~15;
            for (int k = 0; k < 256; k++) {
                xs[ch * 256 + k] = cx + (k & 15);
                zs[ch * 256 + k] = cz + (k >> 4);
            }
        }
        double sink = 0;
        for (int i = 0; i < 200_000; i++) {
            TamrielMap.sample(xs[i], zs[i], s);
            sink += s.elevation;
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            TamrielMap.sample(xs[i], zs[i], s);
            sink += s.elevation;
        }
        double inside = (System.nanoTime() - t0) / (double) n;
        t0 = System.nanoTime();
        for (int i = 0; i < n / 4; i++) {
            TamrielMap.sample(xs[i] * 4 + 60000, zs[i] * 4 - 40000, s);
            sink += s.elevation;
        }
        double beyond = (System.nanoTime() - t0) / (double) (n / 4);
        ServerLevel level = src.getLevel();
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        int cols = 4000;
        t0 = System.nanoTime();
        for (int i = 0; i < cols; i++) sink += gen.getBaseHeight(xs[i] / 2, zs[i] / 2, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        double col = (System.nanoTime() - t0) / (double) cols / 1000.0;
        final double fs = sink;
        String msg = String.format(Locale.ROOT, "TamrielMap.sample: %.0f ns (Tamriel), %.0f ns (beyond); getBaseHeight: %.1f us/column [%s]",
                inside, beyond, col, fs != 0 ? "ok" : "?");
        Skycraft.LOGGER.info(msg);
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
