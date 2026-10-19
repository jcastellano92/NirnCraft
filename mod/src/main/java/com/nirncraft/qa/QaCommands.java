package com.nirncraft.qa;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.landmarks.LandmarkSite;
import com.nirncraft.landmarks.Landmarks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The online side of the harness's tool validation ("online feature index"): what the real world generated around a
 * point, to compare with what the offline tools predicted (exact heights, biomes, seeded landmarks).
 * <pre>  /nirncraft-qa sample &lt;x&gt; &lt;z&gt; &lt;radius&gt; &lt;step&gt;</pre>
 * Generates the chunks of a (2r/step+1)^2 grid if needed and writes {@code nirncraft-qa/sample.json} in the server
 * folder: per point the surface heights (MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR, WORLD_SURFACE) and the biome id, and
 * every seeded landmark in the square (Landmarks.inRect, the same enumerator the far field uses). Host / operator only.
 */
public final class QaCommands {
    private QaCommands() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("nirncraft-qa").requires(src -> Tour.host(src)  // the host, or the server itself
                                || !(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
                                && Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src))
                        .then(Commands.literal("entities").executes(c -> entities(c.getSource())))
                        .then(Commands.literal("sample")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 512))
                                                        .then(Commands.argument("step", IntegerArgumentType.integer(1, 256))
                                                                .executes(c -> sample(c.getSource(),
                                                                        IntegerArgumentType.getInteger(c, "x"),
                                                                        IntegerArgumentType.getInteger(c, "z"),
                                                                        IntegerArgumentType.getInteger(c, "radius"),
                                                                        IntegerArgumentType.getInteger(c, "step"))))))))));
    }

    /**
     * {@code /nirncraft-qa entities}: what every NirnCraft entity type is, from the game itself (the harness gallery
     * reads it instead of guessing from source): kind boss / monster / creature / water / npc / vehicle / misc, its
     * MobCategory and size. Writes {@code nirncraft-qa/entities.json}. Instances are created to inspect, never added.
     */
    static int entities(CommandSourceStack src) {
        ServerLevel level = src.getServer().overworld();
        JsonArray out = new JsonArray();
        for (var type : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
            var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!"nirncraft".equals(key.getNamespace())) continue;
            JsonObject e = new JsonObject();
            e.addProperty("id", key.getPath());
            e.addProperty("category", type.getCategory().getName());
            e.addProperty("width", type.getDimensions().width());
            e.addProperty("height", type.getDimensions().height());
            String kind = "misc";
            try {
                var entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (entity != null) {
                    boolean boss = false;
                    for (Class<?> c = entity.getClass(); c != null && !boss; c = c.getSuperclass())
                        for (var f : c.getDeclaredFields())
                            if (net.minecraft.server.level.ServerBossEvent.class.isAssignableFrom(f.getType())) boss = true;
                    boolean water = entity instanceof net.minecraft.world.entity.animal.fish.WaterAnimal
                            || type.getCategory().getName().contains("water");
                    kind = boss ? "boss"
                            : entity instanceof com.nirncraft.society.entity.NpcEntity
                              || entity.getClass().getSimpleName().startsWith("Guard") ? "npc"
                            : entity instanceof com.nirncraft.sailing.ShipEntity
                              || entity instanceof net.minecraft.world.entity.vehicle.VehicleEntity ? "vehicle"
                            : entity instanceof com.nirncraft.mounts.MountBeast ? "mount"
                            : entity instanceof net.minecraft.world.entity.monster.Enemy ? "monster"
                            : water ? (type.getDimensions().width() < 1.0f ? "fish" : "water")
                            : entity instanceof net.minecraft.world.entity.PathfinderMob ? "creature" : "misc";
                    e.addProperty("multipart", entity.isMultipartEntity());
                    e.addProperty("class", entity.getClass().getSimpleName());
                    entity.discard();
                }
            } catch (RuntimeException ex) {
                e.addProperty("error", String.valueOf(ex.getMessage()));
            }
            if (!e.has("class")) {  // no instance (e.g. a monster in peaceful): its registered category decides
                String cat = type.getCategory().getName();
                kind = cat.equals("monster") ? "monster" : cat.contains("water") ? "water"
                        : cat.equals("creature") || cat.equals("ambient") ? "creature" : "misc";
                e.addProperty("kindFrom", "category");
            }
            e.addProperty("kind", kind);
            out.add(e);
        }
        try {
            Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("entities.json"), new GsonBuilder().create().toJson(out), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            src.sendFailure(Component.literal("Could not write nirncraft-qa/entities.json: " + ex.getMessage()));
            return 0;
        }
        final int n = out.size();
        src.sendSuccess(() -> Component.literal("qa entities: " + n + " types -> nirncraft-qa/entities.json"), false);
        return Math.max(1, n);
    }

    static int sample(CommandSourceStack src, int x, int z, int radius, int step) {
        ServerLevel level = src.getServer().overworld();
        JsonObject root = new JsonObject();
        root.addProperty("x", x);
        root.addProperty("z", z);
        root.addProperty("radius", radius);
        root.addProperty("step", step);
        root.addProperty("seed", level.getSeed());
        JsonArray points = new JsonArray();
        for (int px = x - radius; px <= x + radius; px += step) {
            for (int pz = z - radius; pz <= z + radius; pz += step) {
                level.getChunk(px >> 4, pz >> 4);  // generated (or loaded) for real
                JsonObject p = new JsonObject();
                p.addProperty("x", px);
                p.addProperty("z", pz);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                p.addProperty("motionNoLeaves", top);
                p.addProperty("oceanFloor", level.getHeight(Heightmap.Types.OCEAN_FLOOR, px, pz));
                p.addProperty("worldSurface", level.getHeight(Heightmap.Types.WORLD_SURFACE, px, pz));
                p.addProperty("biome", level.getBiome(new BlockPos(px, top, pz)).unwrapKey()
                        .map(k -> k.identifier().toString()).orElse("?"));
                points.add(p);
            }
        }
        root.add("points", points);
        JsonArray marks = new JsonArray();
        for (LandmarkSite s : Landmarks.inRect(level.getSeed(), x - radius, z - radius, x + radius, z + radius)) {
            JsonObject m = new JsonObject();
            m.addProperty("kind", String.valueOf(s.kind()).toLowerCase(java.util.Locale.ROOT));
            m.addProperty("x", s.x());
            m.addProperty("z", s.z());
            marks.add(m);
        }
        root.add("landmarks", marks);
        try {
            Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("sample.json"), new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            NirnCraft.LOGGER.error("[qa] sample not written", e);
            src.sendFailure(Component.literal("Could not write nirncraft-qa/sample.json: " + e.getMessage()));
            return 0;
        }
        final int n = points.size(), m = marks.size();
        src.sendSuccess(() -> Component.literal("qa sample: " + n + " points, " + m + " landmarks -> nirncraft-qa/sample.json"), false);
        return Math.max(1, n);
    }
}
