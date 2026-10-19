package com.nirncraft.dungeons.qa;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.dungeons.world.DungeonPiece;
import com.nirncraft.dungeons.world.DungeonStructure;
import com.nirncraft.dungeons.world.Painter;
import com.nirncraft.dungeons.world.Theme;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dungeon analyzer: {@code /nirncraft-dungeon-qa} (permission 2, a developer tool).
 * <ul>
 *     <li>{@code run <random count> [tag] [theme]}: plans {@code count} random-site dungeons (spread over every random
 *     dungeon structure, or one theme) plus every named Skyrim dungeon through the structures' own
 *     {@code findGenerationPoint}, paints each into an in-memory level ({@link QaLevel}: nothing touches the world),
 *     walks it with the bot ({@link QaDungeon}) and writes {@code dungeons.csv} and {@code report.md} to
 *     {@code dungeon_qa/<tag>/} in the server folder (and to the repository's {@code tools/dungeon_qa/<tag>/} when the
 *     server runs from {@code mod/run}).</li>
 *     <li>{@code place <id>}: one named dungeon, results in chat.</li>
 *     <li>{@code view <place id> [boss|start|room <n>]}: teleports you into that room of the real dungeon (worldgen
 *     builds it exactly as planned), for screenshots.</li>
 * </ul>
 * Start a run with {@code -Dnirncraft.dungeonqa.auto=<count>:<tag>} to have the server analyse on start-up and stop.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class DungeonQa {
    private static Job job;

    private DungeonQa() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-dungeon-qa").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("run")
                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 5000))
                                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "count"), "latest", null))
                                .then(Commands.argument("tag", StringArgumentType.word())
                                        .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
                                                StringArgumentType.getString(c, "tag"), null))
                                        .then(Commands.argument("theme", StringArgumentType.word())
                                                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "count"),
                                                        StringArgumentType.getString(c, "tag"), StringArgumentType.getString(c, "theme")))))))
                .then(Commands.literal("place").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> one(c.getSource(), StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("dump").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> dump(c.getSource(),
                                        StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "x"),
                                        IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z"))))))))
                .then(Commands.literal("export").then(Commands.argument("ids", StringArgumentType.greedyString())
                        .executes(c -> export(c.getSource(), StringArgumentType.getString(c, "ids")))))
                .then(Commands.literal("air-survey").then(Commands.argument("ids", StringArgumentType.greedyString())
                        .executes(c -> airSurvey(c.getSource(), StringArgumentType.getString(c,"ids")))))
                .then(Commands.literal("cutaway").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> cutaway(c.getSource(), StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("view").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> view(c.getSource(), StringArgumentType.getString(c, "id"), "boss", 0))
                        .then(Commands.literal("boss").executes(c -> view(c.getSource(), StringArgumentType.getString(c, "id"), "boss", 0)))
                        .then(Commands.literal("start").executes(c -> view(c.getSource(), StringArgumentType.getString(c, "id"), "start", 0)))
                        .then(Commands.literal("entrance").executes(c -> view(c.getSource(), StringArgumentType.getString(c, "id"), "entrance", 0)))
                        .then(Commands.literal("mouth").executes(c -> view(c.getSource(), StringArgumentType.getString(c,"id"),"mouth",0)))
                        .then(Commands.literal("hall").executes(c -> view(c.getSource(), StringArgumentType.getString(c,"id"),"hall",0)))
                        .then(Commands.literal("descent").executes(c -> view(c.getSource(), StringArgumentType.getString(c,"id"),"descent",0)))
                        .then(Commands.literal("feature").then(Commands.argument("name", StringArgumentType.word())
                                .executes(c -> view(c.getSource(), StringArgumentType.getString(c, "id"),
                                        "feature:" + StringArgumentType.getString(c, "name"), 0))))
                        .then(Commands.literal("room").then(Commands.argument("n", IntegerArgumentType.integer(0, 64))
                                .executes(c -> view(c.getSource(), StringArgumentType.getString(c, "id"), "room",
                                        IntegerArgumentType.getInteger(c, "n"))))))));
    }

    // ------------------------------------------------------------------ auto mode

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String auto = System.getProperty("nirncraft.dungeonqa.auto", "");
        if (auto.isEmpty()) return;
        String[] p = auto.split(":");
        int count = Integer.parseInt(p[0]);
        String tag = p.length > 1 ? p[1] : "auto";
        String theme = p.length > 2 ? p[2] : null;
        MinecraftServer server = event.getServer();
        // optional developer commands first (dumps, single dungeons): -Dnirncraft.dungeonqa.commands=a,b
        for (String cmd : System.getProperty("nirncraft.dungeonqa.commands", "").split(",")) {
            // (JAVA_TOOL_OPTIONS splits on spaces: '+' stands for a space)
            if (!cmd.isBlank()) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd.trim().replace('+', ' '));
        }
        if (count < 0) {
            server.halt(false);
            return;
        }
        NirnCraft.LOGGER.info("NirnCraft dungeon QA: auto run of {} random dungeons + every named one, tag {}", count, tag);
        start(server.createCommandSourceStack(), count, tag, theme);
        job.haltWhenDone = true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (job == null) return;
        long until = System.nanoTime() + 250_000_000L;
        while (job != null && System.nanoTime() < until) {
            if (!job.step()) {
                Job done = job;
                job = null;
                done.finish();
                if (done.haltWhenDone) done.server.halt(false);
            }
        }
    }

    // ------------------------------------------------------------------ the job

    private static int start(CommandSourceStack src, int count, String tag, String theme) {
        if (job != null) {
            src.sendFailure(Component.literal("A dungeon QA run is already going (" + job.done.size() + "/" + job.queue.size() + ")."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        Job j = new Job(server, src, tag);
        Theme filter = null;
        if (theme != null) {
            for (Theme t : Theme.values()) if (t.id.equals(theme)) filter = t;
            if (filter == null) {
                src.sendFailure(Component.literal("Unknown theme " + theme));
                return 0;
            }
        }
        j.plan(count, filter);
        job = j;
        src.sendSuccess(() -> Component.literal("Dungeon QA: " + j.queue.size() + " dungeons queued (" + count + " random + named)."), true);
        return 1;
    }

    private static int one(CommandSourceStack src, String id) {
        Job j = new Job(src.getServer(), src, "single");
        QaSample s = j.namedSample(id);
        if (s == null) {
            src.sendFailure(Component.literal("No dungeon place " + id));
            return 0;
        }
        QaDungeon d = j.analyse(s);
        if (d == null) {
            src.sendFailure(Component.literal("The structure did not start at " + id));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(s.label() + ": " + d.m), false);
        for (String b : d.blockers) src.sendSuccess(() -> Component.literal("BLOCKER: " + b), false);
        for (String w : d.warnings) src.sendSuccess(() -> Component.literal("warning: " + w), false);
        return 1;
    }

    /**
     * Writes horizontal slices (y-6..y+6, 24 blocks around x z) of a named dungeon as painted in memory to
     * {@code dungeon_qa/dump-<id>.txt}: # solid, . air, ~ water, s stairs/slab, | wall/fence, D door, L ladder,
     * o other solid shapes, + passable non-air.
     */
    private static int dump(CommandSourceStack src, String id, int cx, int cy, int cz) {
        Job j = new Job(src.getServer(), src, "dump");
        QaSample s = id.startsWith("r.") ? j.randomAt(id) : j.namedSample(id);
        if (s == null) return 0;
        Optional<Structure.GenerationStub> stub = j.stub(s);
        if (stub.isEmpty()) return 0;
        List<StructurePiece> pieces = stub.get().getPiecesBuilder().build().pieces();
        QaLevel lvl = j.paint(s, pieces, new HashMap<>(), stub.get().position().getY());
        if (lvl == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (StructurePiece sp : pieces) sb.append(sp instanceof DungeonPiece dp ? dp.kind + " role " + dp.role + " floor " + dp.floor : "?")
                .append(' ').append(sp.getBoundingBox()).append('\n');
        for (int y = cy + 6; y >= cy - 6; y--) {
            sb.append("y=").append(y).append("  (x ").append(cx - 24).append("..").append(cx + 24).append(" across, z down)\n");
            for (int z = cz - 24; z <= cz + 24; z++) {
                for (int x = cx - 24; x <= cx + 24; x++) sb.append(glyph(lvl, x, y, z));
                sb.append('\n');
            }
        }
        for (int[] axis : new int[][]{{1, 0}, {0, 1}}) {
            sb.append(axis[0] == 1 ? "section along x at z=" + cz : "section along z at x=" + cx).append(" (y down)\n");
            for (int y = cy + 12; y >= cy - 30; y--) {
                sb.append(String.format(Locale.ROOT, "%4d ", y));
                for (int k = -30; k <= 30; k++) sb.append(glyph(lvl, cx + k * axis[0], y, cz + k * axis[1]));
                sb.append('\n');
            }
        }
        Path out = src.getServer().getServerDirectory().resolve("dungeon_qa").resolve("dump-" + id + ".txt");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString());
        } catch (IOException e) {
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Wrote " + out), false);
        return 1;
    }

    /**
     * {@code export <place id>...}: paints each named dungeon in memory and writes {@code qa-exports/<id>.json.gz} in
     * the server folder (blocks + markers; see {@link QaExport}) for {@code tools/qa/render_dungeon.py} and
     * {@code tools/qa/dungeon_reach.py}. Nothing is generated in the world.
     */
    private static int export(CommandSourceStack src, String ids) {
        Path dir = src.getServer().getServerDirectory().resolve("qa-exports");
        int ok = 0;
        for (String id : ids.split("[\\s,;|]+")) {
            if (id.isBlank()) continue;
            Job j = new Job(src.getServer(), src, "export");
            try {
                QaSample s = id.startsWith("r.") ? j.randomAt(id) : j.namedSample(id);
                QaDungeon d = s == null ? null : j.analyse(s);
                if (d == null) {
                    NirnCraft.LOGGER.warn("NirnCraft dungeon QA export: {} is not a dungeon place or did not start", id);
                    src.sendFailure(Component.literal("export: no dungeon for " + id));
                    continue;
                }
                Path out = QaExport.write(d, id, dir);
                NirnCraft.LOGGER.info("NirnCraft dungeon QA export: {} -> {}", id, out);
                ok++;
            } catch (IOException | RuntimeException ex) {
                NirnCraft.LOGGER.error("NirnCraft dungeon QA export: {} failed", id, ex);
                src.sendFailure(Component.literal("export failed for " + id + ": " + ex));
            }
        }
        int n = ok;
        src.sendSuccess(() -> Component.literal("Exported " + n + " dungeons to " + dir), false);
        return ok;
    }

    private static char glyph(QaLevel lvl, int x, int y, int z) {
        net.minecraft.world.level.block.state.BlockState s = lvl.get(x, y, z);
        if (s.isAir()) return '.';
        if (!s.getFluidState().isEmpty() && s.getCollisionShape(lvl, BlockPos.ZERO).isEmpty()) return '~';
        if (s.is(net.minecraft.tags.BlockTags.CLIMBABLE)) return 'L';
        if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return 'D';
        if (s.getBlock() instanceof net.minecraft.world.level.block.StairBlock || s.getBlock() instanceof net.minecraft.world.level.block.SlabBlock) return 's';
        if (s.is(net.minecraft.tags.BlockTags.WALLS) || s.is(net.minecraft.tags.BlockTags.FENCES)) return '|';
        net.minecraft.world.phys.shapes.VoxelShape shape = s.getCollisionShape(lvl, BlockPos.ZERO);
        if (shape.isEmpty()) return '+';
        return net.minecraft.world.level.block.Block.isShapeFullBlock(shape) ? '#' : 'o';
    }

    /**
     * {@code /nirncraft-dungeon-qa cutaway ID}: the whole dungeon at a glance from its in-memory paint (the world is
     * not touched): nirncraft-qa/dungeon_ID_cutaway.png (iso, floors from the inside), _plan.png (top, by height) and
     * a JSON summary.
     */
    private static int cutaway(CommandSourceStack src, String id) {
        Job j = new Job(src.getServer(), src, "cutaway");
        QaSample s = j.namedSample(id);
        if (s == null) {
            src.sendFailure(Component.literal("No dungeon place " + id));
            return 0;
        }
        Optional<Structure.GenerationStub> stub = j.stub(s);
        if (stub.isEmpty()) return 0;
        List<StructurePiece> pieces = stub.get().getPiecesBuilder().build().pieces();
        QaLevel lvl = j.paint(s, pieces, new HashMap<>(), stub.get().position().getY());
        if (lvl == null) {
            src.sendFailure(Component.literal("Dungeon " + id + " has no pieces"));
            return 0;
        }
        BoundingBox all = null;
        for (StructurePiece sp : pieces) all = all == null ? BoundingBox.fromCorners(
                new net.minecraft.core.BlockPos(sp.getBoundingBox().minX(), sp.getBoundingBox().minY(), sp.getBoundingBox().minZ()),
                new net.minecraft.core.BlockPos(sp.getBoundingBox().maxX(), sp.getBoundingBox().maxY(), sp.getBoundingBox().maxZ()))
                : all.encapsulate(sp.getBoundingBox());
        try {
            java.nio.file.Path dir = src.getServer().getServerDirectory().resolve("nirncraft-qa");
            java.nio.file.Files.createDirectories(dir);
            var stats = DungeonCutaway.render(lvl, all, dir.resolve("dungeon_" + id + "_cutaway.png").toFile(),
                    dir.resolve("dungeon_" + id + "_plan.png").toFile());
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", id);
            o.addProperty("pieces", pieces.size());
            o.addProperty("floors", stats.floors());
            o.addProperty("levels", stats.maxY() - stats.minY());
            o.addProperty("width", stats.width());
            o.addProperty("depth", stats.depth());
            java.nio.file.Files.writeString(dir.resolve("dungeon_" + id + ".json"), o.toString());
            src.sendSuccess(() -> Component.literal("cutaway " + id + ": " + stats.floors() + " floor tiles"), false);
            return 1;
        } catch (java.io.IOException e) {
            src.sendFailure(Component.literal("cutaway failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int view(CommandSourceStack src, String id, String what, int n) {
        Job j = new Job(src.getServer(), src, "view");
        QaSample s = j.namedSample(id);
        ServerPlayer player = src.getPlayer();
        if (s == null || player == null) {
            src.sendFailure(Component.literal("No dungeon place " + id + " (or not a player)"));
            return 0;
        }
        Optional<Structure.GenerationStub> stub = j.stub(s);
        if (stub.isEmpty()) return 0;
        List<StructurePiece> pieces = stub.get().getPiecesBuilder().build().pieces();
        Map<BoundingBox, Set<String>> features = new HashMap<>();
        if (what.startsWith("feature:")) {
            // paint once in memory to find where the builders put that set piece
            QaLevel lvl = j.paint(s, pieces, features, stub.get().position().getY());
            if (lvl == null) return 0;
        }
        DungeonPiece target = null, entrance = null;
        int rooms = 0;
        for (StructurePiece sp : pieces) {
            if (!(sp instanceof DungeonPiece dp)) continue;
            if (dp.kind == DungeonPiece.Kind.TUNNEL) entrance = dp;
            if ((what.equals("hall") || what.equals("descent")) && dp.kind == DungeonPiece.Kind.CORRIDOR) {
                if ((!what.equals("descent") || Math.abs(dp.floor-dp.aux)>=2)
                        && (target==null || dp.corridorLength()>target.corridorLength())) target=dp;
            }
            if (dp.kind != DungeonPiece.Kind.ROOM) continue;
            boolean pick = switch (what) {
                case "boss" -> dp.role == DungeonPiece.ROLE_BOSS;
                case "start" -> dp.role == DungeonPiece.ROLE_START;
                case "room" -> rooms == n;
                default -> what.startsWith("feature:") && features.getOrDefault(dp.getBoundingBox(), Set.of()).stream()
                        .anyMatch(f -> f.contains(what.substring(8)));
            };
            rooms++;
            if (pick && target == null) target = dp;
        }
        ServerLevel level = src.getServer().overworld();
        double x, y, z;
        float yaw;
        if ("mouth".equals(what) && entrance != null) {
            int t=Math.max(1,entrance.tunnelLength()-7);
            x=entrance.frameX(t,0)+0.5; z=entrance.frameZ(t,0)+0.5;
            y=entrance.tunnelFloor(t)+0.05; yaw=entrance.facing().toYRot();
        } else if ("entrance".equals(what) && entrance != null) {
            Direction out = entrance.facing();
            int t = entrance.tunnelLength() + 12;
            x = entrance.frameX(t, 0) + 0.5;
            z = entrance.frameZ(t, 0) + 0.5;
            y = entrance.aux + 2;
            yaw = out.getOpposite().toYRot();
        } else if (target != null && target.kind==DungeonPiece.Kind.CORRIDOR) {
            BoundingBox b=target.getBoundingBox();
            boolean axisX=target.dir==0, highEnd=target.aux>target.floor;
            int k=highEnd?Math.max(0,target.corridorLength()-2):Math.min(1,target.corridorLength()-1);
            x=axisX?b.minX()+k+0.5:(b.minX()+b.maxX())*0.5+0.5;
            z=axisX?(b.minZ()+b.maxZ())*0.5+0.5:b.minZ()+k+0.5;
            y=target.corridorFloor(k)+0.05;
            yaw=axisX?(highEnd?90:-90):(highEnd?180:0);
        } else if (target != null) {
            BoundingBox b = target.getBoundingBox();
            Direction e = target.dir >= 0 ? Direction.from2DDataValue(target.dir) : Direction.NORTH;
            int half = e.getAxis() == Direction.Axis.X ? b.getXSpan() / 2 : b.getZSpan() / 2;
            int cx = (b.minX() + b.maxX()) / 2, cz = (b.minZ() + b.maxZ()) / 2;
            x = cx + e.getStepX() * (half - 1.5) + 0.5;
            z = cz + e.getStepZ() * (half - 1.5) + 0.5;
            y = target.floor + 1.2;
            yaw = e.getOpposite().toYRot();
        } else {
            src.sendFailure(Component.literal("No such room in " + id));
            return 0;
        }
        player.teleportTo(level, x, y, z, java.util.Set.of(),  yaw, what.equals("mouth")?-8f:what.equals("hall")?3f:15f, true);
        player.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NIGHT_VISION), 20 * 60, 0, false, false));
        double fx = x, fy = y, fz = z;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s %s at %.0f %.0f %.0f", id, what, fx, fy, fz)), false);
        return 1;
    }

    /** Camera selection evidence from the real planner; no demonstration rooms are created. */
    private static int airSurvey(CommandSourceStack src,String ids) {
        Job job=new Job(src.getServer(),src,"air-survey");
        List<Map<String,Object>> result=new ArrayList<>();
        for(String id:ids.trim().split("\\s+")) {
            QaSample sample=job.namedSample(id);
            if(sample==null) continue;
            var stub=job.stub(sample);
            if(stub.isEmpty()) continue;
            List<StructurePiece> pieces=stub.get().getPiecesBuilder().build().pieces();
            Map<BoundingBox,Set<String>> features=new HashMap<>();
            job.paint(sample,pieces,features,stub.get().position().getY());
            int roomIndex=0;
            for(var piece:pieces) if(piece instanceof DungeonPiece dp) {
                var b=dp.getBoundingBox(); Map<String,Object> row=new LinkedHashMap<>();
                row.put("place",id); row.put("kind",dp.kind.name());
                row.put("room",dp.kind==DungeonPiece.Kind.ROOM?roomIndex++:-1);
                row.put("box",List.of(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()));
                row.put("floor",dp.floor); row.put("aux",dp.aux); row.put("dir",dp.dir);
                row.put("theme",dp.theme.id); row.put("features",features.getOrDefault(b,Set.of()));
                if(dp.has(DungeonPiece.FLAG_GRAND)) {
                    var choice=com.nirncraft.dungeons.world.GrandCavern.choose(job.level,dp);
                    row.put("profile",choice.profile().id); row.put("shape",choice.shape().id);
                    row.put("flora",choice.profile().flora.stream().map(f->f.type().name()).toList());
                }
                result.add(row);
            }
        }
        Path output=src.getServer().getServerDirectory().resolve("dungeon_qa/atmosphere-scenes.json");
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch(IOException e) { src.sendFailure(Component.literal(e.toString())); return 0; }
        src.sendSuccess(()->Component.literal("Dungeon atmosphere survey: "+result.size()+" pieces -> "+output),false);
        return result.size();
    }

    static final class Job {
        final MinecraftServer server;
        final CommandSourceStack src;
        final String tag;
        final ServerLevel level;
        final Registry<Structure> registry;
        final List<QaSample> queue = new ArrayList<>();
        final List<QaDungeon> done = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        final QaLoot loot = new QaLoot();
        boolean haltWhenDone;
        int cursor;
        long started = System.currentTimeMillis();

        Job(MinecraftServer server, CommandSourceStack src, String tag) {
            this.server = server;
            this.src = src;
            this.tag = tag;
            this.level = server.overworld();
            this.registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        }

        /** Every named underground dungeon, then {@code count} random sites spread over the random structures. */
        void plan(int count, Theme filter) {
            List<Map.Entry<Identifier, DungeonStructure>> randoms = new ArrayList<>();
            for (Map.Entry<net.minecraft.resources.ResourceKey<Structure>, Structure> e : registry.entrySet()) {
                if (!(e.getValue() instanceof DungeonStructure ds)) continue;
                if (!ds.theme.underground() || !ds.sites.isEmpty()) continue;
                if (filter != null && ds.theme != filter) continue;
                Identifier id = e.getKey().identifier();
                if (ds.place.isPresent()) {
                    for (Place p : Places.all()) {
                        if (ds.place.get().matches(p)) queue.add(new QaSample(p.id(), "named", id, ds, p.x(), p.z(), p.id()));
                    }
                } else {
                    randoms.add(Map.entry(id, ds));
                }
            }
            randoms.sort(Map.Entry.comparingByKey());
            RandomSource r = RandomSource.create(0x5EED_D00DL);
            for (int i = 0; i < count && !randoms.isEmpty(); i++) {
                Map.Entry<Identifier, DungeonStructure> e = randoms.get(i % randoms.size());
                queue.add(new QaSample(e.getKey().getPath() + "#" + (i / randoms.size()), "random", e.getKey(), e.getValue(),
                        r.nextInt(), r.nextInt(), null));
            }
        }

        QaSample namedSample(String id) {
            Place pl = Places.byId(id).orElse(null);
            if (pl == null) return null;
            for (Map.Entry<net.minecraft.resources.ResourceKey<Structure>, Structure> e : registry.entrySet()) {
                if (e.getValue() instanceof DungeonStructure ds && ds.place.isPresent() && ds.place.get().matches(pl)) {
                    return new QaSample(pl.id(), "named", e.getKey().identifier(), ds, pl.x(), pl.z(), pl.id());
                }
            }
            return null;
        }

        /**
         * A random-site dungeon by structure and start: {@code r.<structure path, dots for slashes>.<x>.<z>} with
         * {@code m} for a minus sign, e.g. {@code r.ruin.atmoran_tomb.m348.m13989} (the x z of the report).
         */
        QaSample randomAt(String code) {
            String[] parts = code.substring(2).split("[.]");
            if (parts.length < 3) return null;
            int x = Integer.parseInt(parts[parts.length - 2].replace('m', '-')), z = Integer.parseInt(parts[parts.length - 1].replace('m', '-'));
            String path = String.join("/", java.util.Arrays.copyOf(parts, parts.length - 2));
            Structure st = registry.getValue(Identifier.fromNamespaceAndPath(NirnCraft.MODID, path));
            if (!(st instanceof DungeonStructure ds)) return null;
            return new QaSample(code, "named", Identifier.fromNamespaceAndPath(NirnCraft.MODID, path), ds, x, z, null);
        }

        boolean step() {
            if (cursor >= queue.size()) return false;
            QaSample s = queue.get(cursor++);
            try {
                QaDungeon d = analyse(s);
                if (d == null) failed.add(s.label() + ": no start");
                else done.add(d);
            } catch (RuntimeException ex) {
                NirnCraft.LOGGER.error("NirnCraft dungeon QA: {} failed", s.label(), ex);
                failed.add(s.label() + ": " + ex);
            }
            if (cursor % 25 == 0) {
                NirnCraft.LOGGER.info("NirnCraft dungeon QA: {}/{}", cursor, queue.size());
            }
            return true;
        }

        Optional<Structure.GenerationStub> stub(QaSample s) {
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            RandomState rs = level.getChunkSource().randomState();
            if ("named".equals(s.category())) {
                return s.structure().findGenerationPoint(context(gen, rs, new ChunkPos(s.x() >> 4, s.z() >> 4)));
            }
            // random sites: try chunks until the structure accepts one (its culture, dry ground, no town near)
            RandomSource r = RandomSource.create(((long) s.x() << 32) ^ s.z());
            for (int attempt = 0; attempt < 1500; attempt++) {
                int x, z;
                if (r.nextInt(4) == 0) {
                    // the foreign lands beyond the seas (Atmora, Akavir, Yokuda, Pyandonea, Thras)
                    int[] c = FOREIGN[r.nextInt(FOREIGN.length)];
                    x = c[0] - 3000 + r.nextInt(6000);
                    z = c[1] - 3000 + r.nextInt(6000);
                } else if (r.nextInt(7) == 0) {
                    // the endless lands
                    double a = r.nextDouble() * Math.PI * 2, d = 14000 + r.nextInt(30000);
                    x = (int) (Math.cos(a) * d);
                    z = (int) (Math.sin(a) * d);
                } else {
                    x = -10000 + r.nextInt(20000);
                    z = -6400 + r.nextInt(17500);
                }
                Optional<Structure.GenerationStub> st = s.structure().findGenerationPoint(context(gen, rs, new ChunkPos(x >> 4, z >> 4)));
                if (st.isPresent()) {
                    lastX = x;
                    lastZ = z;
                    return st;
                }
            }
            return Optional.empty();
        }

        int lastX, lastZ;

        /** Rough centres of the foreign lands (docs/TAMRIEL.md section 9). */
        private static final int[][] FOREIGN = {{0, -11000}, {17000, 3000}, {-19000, 7000}, {-17000, 19000}, {-15000, 16500}};

        private Structure.GenerationContext context(ChunkGenerator gen, RandomState rs, ChunkPos cp) {
            return new Structure.GenerationContext(level.registryAccess(), gen, gen.getBiomeSource(), rs.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED),  rs, server.getStructureTemplateManager(),
                    level.getSeed(), cp, level, b -> true);
        }

        QaDungeon analyse(QaSample s0) {
            Optional<Structure.GenerationStub> stub = stub(s0);
            if (stub.isEmpty()) return null;
            QaSample s = "random".equals(s0.category())
                    ? new QaSample(s0.label(), s0.category(), s0.structureId(), s0.structure(), lastX, lastZ, null) : s0;
            PiecesContainer pc = stub.get().getPiecesBuilder().build();
            List<StructurePiece> pieces = pc.pieces();
            Map<BoundingBox, Set<String>> features = new HashMap<>();
            int surfaceY = stub.get().position().getY();
            QaLevel lvl = paint(s, pieces, features, surfaceY);
            if (lvl == null) return null;
            QaDungeon d = new QaDungeon(s, pieces, lvl, features, surfaceY);
            try {
                d.run(loot);
            } catch (RuntimeException ex) {
                NirnCraft.LOGGER.error("NirnCraft dungeon QA: analysis of {} failed", s.label(), ex);
                d.blockers.add("analysis crashed: " + ex);
            }
            d.m.put("theme", s.theme().id);
            d.m.put("kit", s.structure().regionalKit ? kitOf(pieces) : s.structure().kit.id);
            d.m.put("special", s.structure().special.id);
            return d;
        }

        private static String kitOf(List<StructurePiece> pieces) {
            for (StructurePiece sp : pieces) if (sp instanceof DungeonPiece dp) return dp.kit.id;
            return "none";
        }

        /** Paints the pieces into a fresh in-memory level over the modelled terrain, chunk by chunk like worldgen. */
        QaLevel paint(QaSample s, List<StructurePiece> pieces, Map<BoundingBox, Set<String>> features, int surfaceY) {
            if (pieces.isEmpty()) return null;
            BoundingBox all = null;
            for (StructurePiece sp : pieces) {
                BoundingBox b = sp.getBoundingBox();
                all = all == null ? new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()) : all.encapsulate(b);
            }
            int minY = Math.max(level.getMinY(), all.minY() - 3);
            int maxY = Math.min((level.getMaxY() + 1) - 1, Math.max(all.maxY(), surfaceY + 8) + 6);
            BoundingBox area = new BoundingBox(all.minX() - 4, minY, all.minZ() - 4, all.maxX() + 4, maxY, all.maxZ() + 4);
            // exact terrain round the entrance (the tunnel's surface end and forecourt), interpolated elsewhere
            BoundingBox exact = null;
            for (StructurePiece sp : pieces) {
                if (sp instanceof DungeonPiece dp && (dp.kind == DungeonPiece.Kind.PORTAL || dp.kind == DungeonPiece.Kind.TUNNEL)) {
                    BoundingBox b = sp.getBoundingBox().inflatedBy(10);
                    exact = exact == null ? b : exact.encapsulate(b);
                }
            }
            QaLevel lvl = new QaLevel(level, area, ground(area, exact));
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            Painter.FEATURES.set((box, f) -> features.computeIfAbsent(box, k -> new LinkedHashSet<>()).add(f));
            try {
                for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
                    for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                        BoundingBox chunkBox = new BoundingBox(cx << 4, level.getMinY(), cz << 4, (cx << 4) + 15,
                                (level.getMaxY() + 1) - 1, (cz << 4) + 15);
                        ChunkPos cp = new ChunkPos(cx, cz);
                        for (StructurePiece sp : pieces) {
                            if (!sp.getBoundingBox().intersects(chunkBox)) continue;
                            sp.postProcess(lvl, level.structureManager(), gen, RandomSource.create(cp.pack()), chunkBox, cp, chunkBox.getCenter());
                        }
                    }
                }
            } finally {
                Painter.FEATURES.remove();
            }
            return lvl;
        }

        /** The real terrain's height, sampled every 4 blocks from the generator's noise and interpolated. */
        private QaLevel.Ground ground(BoundingBox area, BoundingBox exact) {
            ChunkGenerator gen = level.getChunkSource().getGenerator();
            RandomState rs = level.getChunkSource().randomState();
            int step = 4;
            int x0 = Math.floorDiv(area.minX(), step) * step, z0 = Math.floorDiv(area.minZ(), step) * step;
            int nx = (area.maxX() - x0) / step + 2, nz = (area.maxZ() - z0) / step + 2;
            int[] h = new int[nx * nz];
            for (int i = 0; i < nx; i++) {
                for (int k = 0; k < nz; k++) {
                    h[i * nz + k] = gen.getBaseHeight(x0 + i * step, z0 + k * step, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
                }
            }
            Map<Long, Integer> exactH = new HashMap<>();
            if (exact != null) {
                for (int x = Math.max(exact.minX(), area.minX()); x <= Math.min(exact.maxX(), area.maxX()); x++) {
                    for (int z = Math.max(exact.minZ(), area.minZ()); z <= Math.min(exact.maxZ(), area.maxZ()); z++) {
                        exactH.put(BlockPos.asLong(x, 0, z), gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1);
                    }
                }
            }
            return (x, z) -> {
                Integer ex = exactH.get(BlockPos.asLong(x, 0, z));
                if (ex != null) return ex;
                double fx = (x - x0) / (double) step, fz = (z - z0) / (double) step;
                int i = Math.max(0, Math.min(nx - 2, (int) Math.floor(fx))), k = Math.max(0, Math.min(nz - 2, (int) Math.floor(fz)));
                double tx = Math.max(0, Math.min(1, fx - i)), tz = Math.max(0, Math.min(1, fz - k));
                double a = h[i * nz + k] * (1 - tx) + h[(i + 1) * nz + k] * tx;
                double b = h[i * nz + k + 1] * (1 - tx) + h[(i + 1) * nz + k + 1] * tx;
                return (int) Math.floor(a * (1 - tz) + b * tz);
            };
        }

        // ------------------------------------------------------------------ report

        void finish() {
            QaReport.similarity(done);
            Path out = server.getServerDirectory().resolve("dungeon_qa").resolve(tag);
            List<Path> outs = new ArrayList<>();
            outs.add(out);
            Path repo = server.getServerDirectory().toAbsolutePath().normalize().getParent();
            if (repo != null && repo.getParent() != null && Files.isDirectory(repo.getParent().resolve("tools"))) {
                outs.add(repo.getParent().resolve("tools").resolve("dungeon_qa").resolve(tag));
            }
            long secs = (System.currentTimeMillis() - started) / 1000;
            for (Path p : outs) {
                try {
                    Files.createDirectories(p);
                    QaReport.csv(done, p.resolve("dungeons.csv"));
                    QaReport.markdown(done, failed, tag, secs, p.resolve("report.md"));
                } catch (IOException e) {
                    NirnCraft.LOGGER.error("NirnCraft dungeon QA: could not write {}", p, e);
                }
            }
            int blockers = 0;
            for (QaDungeon d : done) blockers += d.blockers.size();
            int fb = blockers;
            NirnCraft.LOGGER.info("NirnCraft dungeon QA: {} dungeons, {} blockers, {} failed; report in {}", done.size(), blockers, failed.size(), outs);
            src.sendSuccess(() -> Component.literal("Dungeon QA done: " + done.size() + " dungeons, " + fb + " blockers. Report: " + outs.get(outs.size() - 1)), true);
        }
    }

    static Map<String, List<QaDungeon>> byTheme(List<QaDungeon> done) {
        Map<String, List<QaDungeon>> g = new TreeMap<>();
        for (QaDungeon d : done) g.computeIfAbsent(String.valueOf(d.m.get("theme")), k -> new ArrayList<>()).add(d);
        return g;
    }
}
