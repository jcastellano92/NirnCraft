package com.nirncraft.qa;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.atlas.AtlasGround;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.PlaceType;
import com.nirncraft.tamriel.Places;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /nirncraft-tour: the host's own camera tour of the world (any world, any game mode, the world's own seed), the
 * in-game side of the test harness's jump-in tours. For each stop the camera pose is planned from the exact terrain
 * (CameraPlanner, off the server thread), the player is moved there in spectator, the tour waits for the chunks to
 * load and settle, then holds the view; afterwards (or on stop / logout / server stop) the player gets their position,
 * dimension and game mode back.
 * <pre>
 *   /nirncraft-tour start towns|cities|landmarks|forts|dungeons|all|&lt;place ids or names, comma separated&gt;|file:&lt;name&gt;
 *                         [region:&lt;prefix&gt;] [type:&lt;type&gt;] [near:&lt;blocks&gt;] [limit:&lt;n&gt;]
 *                         [shot:aerial|approach|both] [dwell:&lt;s&gt;] [settle:&lt;s&gt;]
 *   /nirncraft-tour next | back | goto &lt;n&gt; | pause | resume | dwell &lt;s&gt; | status | stop
 * </pre>
 * Only the host can tour: the owner of a single-player / LAN world (cheats not needed), or an operator on a dedicated
 * server. file:NAME reads &lt;server folder&gt;/nirncraft-tours/NAME.json (written by the harness: tools/harness
 * export-tour): [{"name", "place"?, "shot"?, "x", "z", "y"? | "above"?, "yaw"?, "pitch"?}].
 */
public final class Tour {
    private Tour() {}

    static final int MIN_LOAD_TICKS = 40, MAX_LOAD_TICKS = 600;
    private static final Map<UUID, State> TOURS = new HashMap<>();
    private static AtlasGround ground;

    record Stop(String name, String placeId, double x, double z, int radius, CameraPlanner.Kind kind, Double y,
                Float yaw, Float pitch, Double above) {}

    enum Phase { PLAN, LOAD, SETTLE, VIEW }

    static final class State {
        final List<Stop> stops;
        final ResourceKey<Level> homeDim;
        final double hx, hy, hz;
        final float hyaw, hpitch;
        final GameType homeMode;
        int index = 0, ticks = 0, dwell, settle;
        boolean paused;
        Phase phase = Phase.PLAN;
        CompletableFuture<CameraPlanner.Shot> plan, next;

        State(ServerPlayer p, List<Stop> stops, int dwell, int settle) {
            this.stops = stops;
            this.homeDim = p.level().dimension();
            this.hx = p.getX();
            this.hy = p.getY();
            this.hz = p.getZ();
            this.hyaw = p.getYRot();
            this.hpitch = p.getXRot();
            this.homeMode = p.gameMode();
            this.dwell = dwell;
            this.settle = settle;
        }
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(Tour::commands);
        NeoForge.EVENT_BUS.addListener(Tour::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) end(p, null);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            for (UUID id : List.copyOf(TOURS.keySet())) {
                ServerPlayer p = e.getServer().getPlayerList().getPlayer(id);
                if (p != null) end(p, null);
            }
            TOURS.clear();
            ground = null;
        });
    }

    /** The host: the owner of an integrated (single-player / LAN) world, or an operator on a dedicated server. */
    static boolean host(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer p)) return false;
        MinecraftServer server = src.getServer();
        return server instanceof DedicatedServer ? Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src)
                : server.isSingleplayerOwner(p.nameAndId());
    }

    static void commands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-tour").requires(Tour::host)
                .then(Commands.literal("start").then(Commands.argument("spec", StringArgumentType.greedyString())
                        .executes(c -> start(c.getSource(), StringArgumentType.getString(c, "spec")))))
                .then(Commands.literal("next").executes(c -> step(c.getSource(), 1)))
                .then(Commands.literal("back").executes(c -> step(c.getSource(), -1)))
                .then(Commands.literal("goto").then(Commands.argument("n", IntegerArgumentType.integer(1))
                        .executes(c -> jump(c.getSource(), IntegerArgumentType.getInteger(c, "n") - 1))))
                .then(Commands.literal("pause").executes(c -> pause(c.getSource(), true)))
                .then(Commands.literal("resume").executes(c -> pause(c.getSource(), false)))
                .then(Commands.literal("dwell").then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                        .executes(c -> dwell(c.getSource(), IntegerArgumentType.getInteger(c, "seconds")))))
                .then(Commands.literal("status").executes(c -> status(c.getSource())))
                .then(Commands.literal("stop").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    return end(p, "Tour stopped") ? 1 : 0;
                })));
    }

    // ------------------------------------------------------------------------------------------------ selection
    static final Set<PlaceType> TOWNS = EnumSet.of(PlaceType.CAPITAL, PlaceType.CITY, PlaceType.TOWN, PlaceType.VILLAGE);
    static final Set<PlaceType> CITIES = EnumSet.of(PlaceType.CAPITAL, PlaceType.CITY);
    static final Set<PlaceType> LANDMARKS = EnumSet.of(PlaceType.LANDMARK, PlaceType.STANDING_STONE, PlaceType.WORD_WALL,
            PlaceType.DRAGON_MOUND, PlaceType.DAEDRIC_SHRINE);

    static int start(CommandSourceStack src, String spec) {
        ServerPlayer p;
        try {
            p = src.getPlayerOrException();
        } catch (Exception e) {
            return 0;
        }
        if (ground == null) ground = AtlasGround.forServer(src.getServer());
        if (ground == null) {
            src.sendFailure(Component.literal("Tours need the Tamriel overworld"));
            return 0;
        }
        String region = null, type = null, shot = "aerial", file = null;
        int near = 0, limit = 0, dwell = 12, settle = 4;
        List<String> names = new ArrayList<>();
        Set<PlaceType> types = null;
        for (String tok : spec.trim().split("\\s+")) {
            String t = tok.toLowerCase(Locale.ROOT);
            int c = t.indexOf(':');
            String k = c > 0 ? t.substring(0, c) : t, v = c > 0 ? t.substring(c + 1) : "";
            try {
                switch (k) {
                    case "towns" -> types = TOWNS;
                    case "cities" -> types = CITIES;
                    case "landmarks" -> types = LANDMARKS;
                    case "forts" -> types = EnumSet.of(PlaceType.FORT);
                    case "dungeons" -> types = EnumSet.of(PlaceType.DUNGEON_FIXED);
                    case "all" -> types = EnumSet.allOf(PlaceType.class);
                    case "region" -> region = v;
                    case "type" -> type = v;
                    case "near" -> near = Integer.parseInt(v);
                    case "limit" -> limit = Integer.parseInt(v);
                    case "shot" -> shot = v;
                    case "dwell" -> dwell = Math.max(1, Integer.parseInt(v));
                    case "settle" -> settle = Math.max(0, Integer.parseInt(v));
                    case "file" -> file = tok.substring(5);
                    default -> names.addAll(List.of(tok.split(",")));
                }
            } catch (NumberFormatException e) {
                src.sendFailure(Component.literal("Not a number: " + tok));
                return 0;
            }
        }
        List<CameraPlanner.Kind> kinds = switch (shot) {
            case "approach" -> List.of(CameraPlanner.Kind.APPROACH);
            case "both" -> List.of(CameraPlanner.Kind.AERIAL, CameraPlanner.Kind.APPROACH);
            default -> List.of(CameraPlanner.Kind.AERIAL);
        };
        List<Stop> stops = new ArrayList<>();
        if (file != null) {
            if (!readFile(src, file, kinds.get(0), stops)) return 0;
        } else {
            List<Place> places = new ArrayList<>();
            for (String n : names) {
                Place exact = Places.byId(n.toLowerCase(Locale.ROOT)).orElse(null);
                if (exact != null) {
                    places.add(exact);
                    continue;
                }
                String key = n.toLowerCase(Locale.ROOT).replace(' ', '_');
                for (Place pl : Places.all()) if (pl.id().contains(key)) places.add(pl);
            }
            if (names.isEmpty()) {
                Set<PlaceType> want = types != null ? types : type != null ? null : TOWNS;
                for (Place pl : Places.all()) if (want == null || want.contains(pl.type())) places.add(pl);
            }
            for (Place pl : places) {
                if (region != null && (pl.regionId() == null || !pl.regionId().startsWith(region))) continue;
                if (type != null && !pl.type().name().equalsIgnoreCase(type)) continue;
                if (near > 0 && pl.distSqr((int) p.getX(), (int) p.getZ()) > (long) near * near) continue;
                for (CameraPlanner.Kind k : kinds)
                    stops.add(new Stop(pl.name().getString(), pl.id(), pl.x(), pl.z(), pl.radius(), k, null, null, null, null));
            }
        }
        if (stops.isEmpty()) {
            src.sendFailure(Component.literal("No places match \"" + spec + "\""));
            return 0;
        }
        order(stops, p.getX(), p.getZ());
        if (limit > 0 && stops.size() > limit) stops = new ArrayList<>(stops.subList(0, limit));
        if (TOURS.containsKey(p.getUUID())) end(p, null);
        State s = new State(p, stops, dwell, settle);
        TOURS.put(p.getUUID(), s);
        p.setGameMode(GameType.SPECTATOR);  // no falls, no fights, no blocks in the way; restored at the end
        final int n = stops.size(), dw = dwell, se = settle;
        src.sendSuccess(() -> Component.literal("Tour: " + n + " stop(s), " + se + " s settle + " + dw + " s each. ")
                .append(buttons()), false);
        return n;
    }

    static boolean readFile(CommandSourceStack src, String name, CameraPlanner.Kind kind, List<Stop> stops) {
        Path f = src.getServer().getServerDirectory().resolve("nirncraft-tours").resolve(name.replaceAll("[^A-Za-z0-9_.-]", "") + ".json");
        try {
            JsonArray a = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : a) {
                JsonObject o = e.getAsJsonObject();
                Place pl = o.has("place") ? Places.byId(o.get("place").getAsString()).orElse(null) : null;
                CameraPlanner.Kind k = o.has("shot") && o.get("shot").getAsString().equals("approach")
                        ? CameraPlanner.Kind.APPROACH : kind;
                double x = o.has("x") ? o.get("x").getAsDouble() : pl != null ? pl.x() : 0;
                double z = o.has("z") ? o.get("z").getAsDouble() : pl != null ? pl.z() : 0;
                stops.add(new Stop(o.has("name") ? o.get("name").getAsString() : pl != null ? pl.name().getString() : "stop",
                        pl != null ? pl.id() : null, x, z, pl != null ? pl.radius() : 40, k,
                        o.has("y") ? o.get("y").getAsDouble() : null, o.has("yaw") ? o.get("yaw").getAsFloat() : null,
                        o.has("pitch") ? o.get("pitch").getAsFloat() : null, o.has("above") ? o.get("above").getAsDouble() : null));
            }
            return true;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Cannot read tour " + f + ": " + e.getMessage()));
            return false;
        }
    }

    /** Nearest neighbour from the player: each hop loads as little new world as possible. */
    static void order(List<Stop> stops, double x, double z) {
        List<Stop> left = new ArrayList<>(stops), out = new ArrayList<>();
        double cx = x, cz = z;
        while (!left.isEmpty()) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < left.size(); i++) {
                double d = Math.hypot(left.get(i).x() - cx, left.get(i).z() - cz);
                if (d < bd) { bd = d; best = i; }
            }
            Stop s = left.remove(best);
            out.add(s);
            cx = s.x();
            cz = s.z();
        }
        stops.clear();
        stops.addAll(out);
    }

    // ------------------------------------------------------------------------------------------------ control
    static State state(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? TOURS.get(p.getUUID()) : null;
    }

    static int step(CommandSourceStack src, int by) {
        State s = state(src);
        if (s == null) return none(src);
        return jump(src, Math.floorMod(s.index + by, s.stops.size()));
    }

    static int jump(CommandSourceStack src, int i) {
        State s = state(src);
        if (s == null) return none(src);
        if (i < 0 || i >= s.stops.size()) {
            src.sendFailure(Component.literal("Stops are 1.." + s.stops.size()));
            return 0;
        }
        CompletableFuture<CameraPlanner.Shot> prefetched = i == s.index + 1 ? s.next : null;  // planned for that stop only
        s.plan = prefetched;
        s.next = null;
        s.index = i;
        s.phase = Phase.PLAN;
        s.ticks = 0;
        return 1;
    }

    static int pause(CommandSourceStack src, boolean on) {
        State s = state(src);
        if (s == null) return none(src);
        s.paused = on;
        src.sendSuccess(() -> Component.literal(on ? "Tour paused" : "Tour resumed"), false);
        return 1;
    }

    static int dwell(CommandSourceStack src, int seconds) {
        State s = state(src);
        if (s == null) return none(src);
        s.dwell = seconds;
        src.sendSuccess(() -> Component.literal("Tour: " + seconds + " s per stop"), false);
        return 1;
    }

    static int status(CommandSourceStack src) {
        State s = state(src);
        if (s == null) return none(src);
        Stop st = s.stops.get(s.index);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Tour %d/%d: %s (%s) %s%s", s.index + 1,
                s.stops.size(), st.name(), st.kind().name().toLowerCase(Locale.ROOT), s.phase.name().toLowerCase(Locale.ROOT),
                s.paused ? ", paused" : "")).append(" ").append(buttons()), false);
        return 1;
    }

    static int none(CommandSourceStack src) {
        src.sendFailure(Component.literal("No tour running; /nirncraft-tour start towns"));
        return 0;
    }

    static boolean end(ServerPlayer p, String message) {
        State s = TOURS.remove(p.getUUID());
        if (s == null) return false;
        ServerLevel home = p.level().getServer().getLevel(s.homeDim);
        if (home != null) p.teleportTo(home, s.hx, s.hy, s.hz, Set.of(), s.hyaw, s.hpitch, true);
        p.setGameMode(s.homeMode);
        if (message != null) p.sendSystemMessage(Component.literal(message + "; you are back where you started"));
        return true;
    }

    static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY.withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    static MutableComponent buttons() {
        return Component.empty().append(button("Back", "/nirncraft-tour back", ChatFormatting.GRAY)).append(" ")
                .append(button("Pause", "/nirncraft-tour pause", ChatFormatting.YELLOW)).append(" ")
                .append(button("Resume", "/nirncraft-tour resume", ChatFormatting.GREEN)).append(" ")
                .append(button("Next", "/nirncraft-tour next", ChatFormatting.AQUA)).append(" ")
                .append(button("Stop", "/nirncraft-tour stop", ChatFormatting.RED));
    }

    // ------------------------------------------------------------------------------------------------ ticking
    static CompletableFuture<CameraPlanner.Shot> planAsync(Stop st) {
        AtlasGround g = ground;
        return CompletableFuture.supplyAsync(() -> {
            if (st.y() != null || st.above() != null) {  // a fixed pose from a tour file
                double y = st.y() != null ? st.y() : g.surface((int) st.x(), (int) st.z()) + st.above();
                float yaw = st.yaw() != null ? st.yaw() : 0, pitch = st.pitch() != null ? st.pitch() : 0;
                double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
                return new CameraPlanner.Shot(st.kind(), st.x(), y, st.z(), st.x() - Math.sin(ry) * Math.cos(rp) * 100,
                        y - Math.sin(rp) * 100, st.z() + Math.cos(ry) * Math.cos(rp) * 100, 0, 1);
            }
            List<CameraPlanner.Shot> shots = CameraPlanner.plan(new CameraPlanner.Cached(g), st.x(), st.z(),
                    Math.max(20, st.radius()), st.kind(), 1);
            if (!shots.isEmpty()) return shots.get(0);
            double y = g.surface((int) st.x(), (int) st.z()) + 60;  // no good ring: straight above, looking down
            return new CameraPlanner.Shot(st.kind(), st.x(), y, st.z() + 40, st.x(), y - 50, st.z(), 0, 0);
        });
    }

    static boolean loaded(ServerLevel level, double x, double z) {
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        for (int i = -2; i <= 2; i++)
            for (int j = -2; j <= 2; j++)
                if (!level.getChunkSource().hasChunk(cx + i, cz + j)) return false;
        return true;
    }

    static void tick(ServerTickEvent.Post event) {
        if (TOURS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, State> e : List.copyOf(TOURS.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            State s = e.getValue();
            if (p == null) {
                TOURS.remove(e.getKey());
                continue;
            }
            try {
                advance(server, p, s);
            } catch (RuntimeException ex) {
                NirnCraft.LOGGER.error("[tour] stop {} failed", s.index + 1, ex);
                end(p, "Tour failed at stop " + (s.index + 1) + ": " + ex.getMessage());
            }
        }
    }

    static void advance(MinecraftServer server, ServerPlayer p, State s) {
        Stop st = s.stops.get(s.index);
        String label = String.format(Locale.ROOT, "[%d/%d] %s", s.index + 1, s.stops.size(), st.name());
        if (s.phase == Phase.PLAN) {
            if (s.plan == null) s.plan = planAsync(st);
            if (!s.plan.isDone()) {
                p.sendSystemMessage(Component.literal(label + ": planning the view..."), true);
                return;
            }
            CameraPlanner.Shot shot = s.plan.join();
            ServerLevel level = server.overworld();
            p.teleportTo(level, shot.x(), shot.y(), shot.z(), Set.of(), shot.yaw(), shot.pitch(), true);
            s.phase = Phase.LOAD;
            s.ticks = 0;
            if (s.index + 1 < s.stops.size()) s.next = planAsync(s.stops.get(s.index + 1));  // ready by the next hop
            return;
        }
        if (s.paused) {
            p.sendSystemMessage(Component.literal(label + ": paused (/nirncraft-tour resume)"), true);
            return;
        }
        s.ticks++;
        if (s.phase == Phase.LOAD) {
            boolean ok = s.ticks >= MIN_LOAD_TICKS && loaded(server.overworld(), p.getX(), p.getZ());
            p.sendSystemMessage(Component.literal(label + ": loading..."), true);
            if (ok || s.ticks >= MAX_LOAD_TICKS) {
                s.phase = Phase.SETTLE;
                s.ticks = 0;
            }
        } else if (s.phase == Phase.SETTLE) {
            p.sendSystemMessage(Component.literal(label + ": settling..."), true);
            if (s.ticks >= s.settle * 20) {
                s.phase = Phase.VIEW;
                s.ticks = 0;
                p.sendSystemMessage(Component.literal(label + " (" + st.kind().name().toLowerCase(Locale.ROOT) + ") ")
                        .withStyle(ChatFormatting.GOLD).append(buttons()));
            }
        } else {
            int left = s.dwell - s.ticks / 20;
            p.sendSystemMessage(Component.literal(label + ": next in " + left + " s"), true);
            if (s.ticks >= s.dwell * 20) {
                if (s.index + 1 >= s.stops.size()) {
                    end(p, "Tour finished (" + s.stops.size() + " stops)");
                    return;
                }
                s.index++;
                s.plan = s.next;
                s.next = null;
                s.phase = Phase.PLAN;
                s.ticks = 0;
            }
        }
    }
}
