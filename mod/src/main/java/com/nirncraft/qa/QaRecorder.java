package com.nirncraft.qa;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Behaviour recording for harness scenarios ({@code /nirncraft-qa record start|watch|stop}): every N ticks, every
 * entity in a box around the scenario (actor name from its {@code qa_actor_<name>} tag, type, position, health, its
 * target's actor name, alive) and the state of watched blocks (a door's open property ...). The harness judges the
 * time series against a scenario's expectations over many trials. Nothing runs unless a recording is active.
 */
public final class QaRecorder {
    private QaRecorder() {}

    private static String name;
    private static ServerLevel level;
    private static AABB box;
    private static int every = 10, ticks;
    private static final List<BlockPos> watched = new ArrayList<>();
    private static JsonArray samples;

    static void init() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (samples != null && ++ticks % every == 0) sample();
        });
    }

    static void start(ServerLevel lvl, String n, Vec3 centre, double radius, int everyTicks) {
        name = n;
        level = lvl;
        box = new AABB(centre, centre).inflate(radius);
        every = Math.max(1, everyTicks);
        ticks = 0;
        watched.clear();
        samples = new JsonArray();
        sample();
    }

    static void watch(BlockPos pos) {
        watched.add(pos.immutable());
    }

    static String actor(Entity e) {
        for (String tag : e.entityTags()) if (tag.startsWith("qa_actor_")) return tag.substring(9);
        return null;
    }

    private static void sample() {
        JsonObject s = new JsonObject();
        s.addProperty("tick", ticks);
        JsonArray ents = new JsonArray();
        for (Entity e : level.getEntities((Entity) null, box, x -> !(x instanceof net.minecraft.world.entity.player.Player))) {
            JsonObject o = new JsonObject();
            String a = actor(e);
            if (a != null) o.addProperty("actor", a);
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("x", Math.round(e.getX() * 100) / 100.0);
            o.addProperty("y", Math.round(e.getY() * 100) / 100.0);
            o.addProperty("z", Math.round(e.getZ() * 100) / 100.0);
            o.addProperty("alive", e.isAlive());
            if (e instanceof LivingEntity l) o.addProperty("health", l.getHealth());
            if (e instanceof Mob m && m.getTarget() != null) {
                String t = actor(m.getTarget());
                o.addProperty("target", t != null ? t : BuiltInRegistries.ENTITY_TYPE.getKey(m.getTarget().getType()).toString());
            }
            ents.add(o);
        }
        s.add("entities", ents);
        JsonArray blocks = new JsonArray();
        for (BlockPos p : watched) {
            JsonObject o = new JsonObject();
            o.addProperty("x", p.getX());
            o.addProperty("y", p.getY());
            o.addProperty("z", p.getZ());
            o.addProperty("state", level.getBlockState(p).toString());
            blocks.add(o);
        }
        s.add("blocks", blocks);
        samples.add(s);
    }

    static Path stop(MinecraftServer server) throws java.io.IOException {
        if (samples == null) throw new IllegalStateException("no recording");
        sample();
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("every", every);
        root.addProperty("ticks", ticks);
        root.add("samples", samples);
        samples = null;
        Path dir = server.getServerDirectory().resolve("nirncraft-qa");
        Files.createDirectories(dir);
        Path out = dir.resolve("record-" + name + ".json");
        Files.writeString(out, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
        return out;
    }
}
