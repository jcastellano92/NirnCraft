package com.skycraft.fire;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.skycraft.Skycraft;
import com.skycraft.creatures.entity.CorpseEntity;
import com.skycraft.fire.aftermath.AftermathPopulation;
import com.skycraft.fire.aftermath.Tableau;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code /skycraft-fire} (operators): {@code stats}, {@code list [n]}, {@code add <intensity> <height> [smoke]},
 * {@code ignite <height>}, {@code douse}, {@code remove}, {@code burst <intensity> <radius>}, {@code scan} (register the
 * fire anchors of every recorded settlement), {@code census [place] [generate]} (counts of what an aftermath scene made:
 * fire sites, burnt blocks, bodies, huddled, workers, aid props), {@code respawn <place>} and {@code clear <place>}
 * (the tableau people of a settlement), {@code selftest}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class FireCommands {
    private FireCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-fire").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource(), 20))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 500)).executes(ctx -> list(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(0.1f, 2.5f))
                                .then(Commands.argument("height", IntegerArgumentType.integer(1, 48))
                                        .executes(ctx -> add(ctx.getSource(), FloatArgumentType.getFloat(ctx, "intensity"),
                                                IntegerArgumentType.getInteger(ctx, "height"), 2))
                                        .then(Commands.argument("smoke", IntegerArgumentType.integer(0, 3))
                                                .executes(ctx -> add(ctx.getSource(), FloatArgumentType.getFloat(ctx, "intensity"),
                                                        IntegerArgumentType.getInteger(ctx, "height"), IntegerArgumentType.getInteger(ctx, "smoke")))))))
                .then(Commands.literal("ignite")
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 12)).executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
                            int id = FireSites.ignite(level, p, 1.2f, IntegerArgumentType.getInteger(ctx, "height"));
                            ctx.getSource().sendSuccess(() -> Component.literal("ignited site " + id + " at " + p.toShortString()), true);
                            return 1;
                        })))
                .then(Commands.literal("douse").executes(ctx -> {
                    BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
                    FireSites.douse(ctx.getSource().getLevel(), p);
                    ctx.getSource().sendSuccess(() -> Component.literal("doused " + p.toShortString()), true);
                    return 1;
                }))
                .then(Commands.literal("remove").executes(ctx -> {
                    BlockPos p = BlockPos.containing(ctx.getSource().getPosition());
                    int n = FireSites.removeNear(ctx.getSource().getLevel(), p, 6);
                    ctx.getSource().sendSuccess(() -> Component.literal("removed " + n + " sites within 6 blocks"), true);
                    return n;
                }))
                .then(Commands.literal("burst")
                        .then(Commands.argument("intensity", FloatArgumentType.floatArg(0.2f, 3f))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 24)).executes(ctx -> {
                                    FireSites.burst(ctx.getSource().getLevel(), ctx.getSource().getPosition(),
                                            FloatArgumentType.getFloat(ctx, "intensity"), IntegerArgumentType.getInteger(ctx, "radius"));
                                    return 1;
                                }))))
                .then(Commands.literal("scan").executes(ctx -> {
                    FireAnchors.reset();
                    int n = FireAnchors.scan(ctx.getSource().getLevel(), true);
                    ctx.getSource().sendSuccess(() -> Component.literal("registered " + n + " fire anchors as sites"), true);
                    return n;
                }))
                .then(Commands.literal("census").executes(ctx -> census(ctx.getSource(), "helgen", false))
                        .then(Commands.argument("place", StringArgumentType.word()).executes(ctx -> census(ctx.getSource(),
                                StringArgumentType.getString(ctx, "place"), false))
                                .then(Commands.literal("generate").executes(ctx -> census(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "place"), true)))))
                .then(Commands.literal("respawn").then(Commands.argument("place", StringArgumentType.word()).executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Optional<SettlementAnchors.Entry> e = SettlementAnchors.find(level, StringArgumentType.getString(ctx, "place"));
                    if (e.isEmpty()) {
                        ctx.getSource().sendFailure(Component.literal("no recorded settlement of that id (generate it first)"));
                        return 0;
                    }
                    int cleared = AftermathPopulation.clear(level, e.get());
                    int n = AftermathPopulation.spawnFor(level, e.get(), true);
                    ctx.getSource().sendSuccess(() -> Component.literal("cleared " + cleared + ", spawned " + n + " tableau people and bodies"), true);
                    return n;
                })))
                .then(Commands.literal("clear").then(Commands.argument("place", StringArgumentType.word()).executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    Optional<SettlementAnchors.Entry> e = SettlementAnchors.find(level, StringArgumentType.getString(ctx, "place"));
                    if (e.isEmpty()) return 0;
                    int n = AftermathPopulation.clear(level, e.get());
                    ctx.getSource().sendSuccess(() -> Component.literal("removed " + n), true);
                    return n;
                })))
                .then(Commands.literal("selftest").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
                    int[] n = new int[2];
                    FireSelfTest.persistentFire(level.getServer(), level, at, false, (name, ok, detail) -> {
                        n[ok ? 0 : 1]++;
                        ctx.getSource().sendSuccess(() -> Component.literal((ok ? "ok   " : "FAIL ") + name + ": " + detail), false);
                    });
                    return n[1] == 0 ? 1 : 0;
                })));
    }

    private static int stats(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        List<FireSites.Site> all = FireSites.all(level);
        int persistent = 0, temp = 0, blocks = 0;
        int[] smoke = new int[4];
        for (FireSites.Site s : all) {
            if (s.persistent()) persistent++;
            else temp++;
            if ((s.flags() & FireSites.FLAG_BLOCKS) != 0) blocks++;
            smoke[Math.max(0, Math.min(3, s.smoke()))]++;
        }
        String line = String.format(Locale.ROOT, "fire sites: %d (%d persistent, %d temporary, %d on real fire blocks); smoke 0/1/2/3 = %d/%d/%d/%d; data version %d",
                all.size(), persistent, temp, blocks, smoke[0], smoke[1], smoke[2], smoke[3], FireSites.data(level).version());
        src.sendSuccess(() -> Component.literal(line), false);
        return all.size();
    }

    private static int list(CommandSourceStack src, int n) {
        ServerLevel level = src.getLevel();
        BlockPos at = BlockPos.containing(src.getPosition());
        List<FireSites.Site> all = FireSites.all(level);
        all.sort((a, b) -> Long.compare(dist(a, at), dist(b, at)));
        int shown = 0;
        for (FireSites.Site s : all) {
            if (shown++ >= n) break;
            String line = String.format(Locale.ROOT, "#%d %d %d %d  i=%.2f h=%d smoke=%d flags=%d %s  (%d blocks away)", s.id(), s.x(), s.y(), s.z(),
                    s.intensity(), s.height(), s.smoke(), s.flags(), s.persistent() ? "" : "temporary", (int) Math.sqrt(dist(s, at)));
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static long dist(FireSites.Site s, BlockPos at) {
        long dx = s.x() - at.getX(), dz = s.z() - at.getZ();
        return dx * dx + dz * dz;
    }

    private static int add(CommandSourceStack src, float intensity, int height, int smoke) {
        BlockPos p = BlockPos.containing(src.getPosition());
        int id = FireSites.add(src.getLevel(), p, intensity, height, smoke, 0);
        src.sendSuccess(() -> Component.literal("fire site " + id + " at " + p.toShortString()), true);
        return id;
    }

    // ------------------------------------------------------------------ census

    private static int census(CommandSourceStack src, String place, boolean generate) {
        ServerLevel level = src.getLevel();
        Optional<SettlementAnchors.Entry> opt = SettlementAnchors.find(level, place);
        com.skycraft.tamriel.Place pl = com.skycraft.tamriel.Places.byId(place).orElse(null);
        if (pl == null) {
            src.sendFailure(Component.literal("unknown place " + place));
            return 0;
        }
        int r = Math.max(pl.radius(), 56) + 12;
        if (generate) {
            for (int cx = (pl.x() - r) >> 4; cx <= (pl.x() + r) >> 4; cx++) {
                for (int cz = (pl.z() - r) >> 4; cz <= (pl.z() + r) >> 4; cz++) level.getChunk(cx, cz);
            }
        }
        // blocks, by registry path, in the loaded chunks of the footprint
        Map<String, Integer> blocks = new TreeMap<>();
        int floating = 0;
        List<String> floatSample = new java.util.ArrayList<>();
        int minCx = (pl.x() - r) >> 4, maxCx = (pl.x() + r) >> 4, minCz = (pl.z() - r) >> 4, maxCz = (pl.z() + r) >> 4;
        int chunks = 0;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                chunks++;
                ChunkPos cp = chunk.getPos();
                LevelChunkSection[] sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    if (!sec.maybeHas(st -> st.getBlock().builtInRegistryHolder().key().location().getNamespace().equals(Skycraft.MODID))) continue;
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState st = sec.getBlockState(x, y, z);
                                Block b = st.getBlock();
                                var key = BuiltInRegistries.BLOCK.getKey(b);
                                if (!key.getNamespace().equals(Skycraft.MODID)) continue;
                                int wx = cp.getMinBlockX() + x, wz = cp.getMinBlockZ() + z;
                                long dx = wx - pl.x(), dz = wz - pl.z();
                                if (dx * dx + dz * dz > (long) r * r) continue;
                                blocks.merge(key.getPath(), 1, Integer::sum);
                                if (com.skycraft.fire.aftermath.Supports.needsFloor(st)) {
                                    int wy = chunk.getMinBuildHeight() + si * 16 + y;
                                    BlockPos at = new BlockPos(wx, wy, wz);
                                    if (!com.skycraft.fire.aftermath.Supports.holds(chunk.getBlockState(at.below()))) {
                                        floating++;
                                        if (floatSample.size() < 4) floatSample.add(key.getPath() + "@" + at.toShortString());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        int fire = 0, burnt = 0, decals = 0, debris = 0;
        for (Map.Entry<String, Integer> e : blocks.entrySet()) {
            String k = e.getKey();
            switch (k) {
                case "big_fire", "flame_column", "flame_tip", "rubble_fire", "smouldering_embers" -> fire += e.getValue();
                case "charred_planks", "charred_planks_stairs", "charred_planks_slab", "charred_log", "charred_beam", "scorched_stone",
                        "scorched_stone_bricks" -> burnt += e.getValue();
                case "soot_stain", "blood_stain" -> decals += e.getValue();
                case "timber_debris", "splintered_wreckage", "stuck_arrows" -> debris += e.getValue();
                default -> {
                }
            }
        }
        int bedrolls = blocks.getOrDefault("bedroll", 0) / 2, pots = blocks.getOrDefault("cooking_pot", 0);
        // anchors and people
        int anchorsTableau = 0, anchorsFire = 0;
        Map<String, Integer> anchorPoses = new TreeMap<>();
        if (opt.isPresent()) {
            for (Anchor a : opt.get().anchors()) {
                if (a.type() == AnchorType.TABLEAU) {
                    anchorsTableau++;
                    anchorPoses.merge(a.owner().split("\\|")[0], 1, Integer::sum);
                } else if (a.type() == AnchorType.FIRE_SITE) anchorsFire++;
            }
        }
        int bodies = 0, lootable = 0, huddled = 0, kneeling = 0, weeping = 0, workers = 0, aid = 0, guards = 0, other = 0;
        List<Entity> actors = opt.map(e -> AftermathPopulation.actors(level, e)).orElse(List.of());
        for (Entity en : actors) {
            if (en instanceof CorpseEntity c) {
                bodies++;
                if (!c.isLocked()) lootable++;
            } else if (en instanceof NpcEntity n) {
                Tableau.Pose pose = Tableau.poseOf(n);
                if (n.isHuddled()) huddled++;
                if (n.isKneeling()) kneeling++;
                if (n.isWeeping()) weeping++;
                if (pose == null) continue;
                switch (pose) {
                    case DIG, PICK, AXE, HAUL, BUCKET -> workers++;
                    case TEND, LADLE, PATIENT -> aid++;
                    case GUARD, CAPTAIN -> guards++;
                    default -> other++;
                }
            }
        }
        int sites = 0;
        for (FireSites.Site s : FireSites.all(level)) {
            long dx = s.x() - pl.x(), dz = s.z() - pl.z();
            if (dx * dx + dz * dz <= (long) r * r) sites++;
        }
        List<String> out = new java.util.ArrayList<>();
        out.add(String.format(Locale.ROOT, "census of %s (radius %d, %d chunks loaded%s):", place, r, chunks, opt.isPresent() ? "" : "; NOT recorded yet"));
        out.add(String.format(Locale.ROOT, "  fire sites %d (anchors %d); fire blocks %d; burnt blocks (charred+scorched) %d; decals %d; debris %d",
                sites, anchorsFire, fire, burnt, decals, debris));
        out.add(String.format(Locale.ROOT, "  tableau anchors %d %s", anchorsTableau, anchorPoses));
        out.add(String.format(Locale.ROOT, "  spawned: bodies %d (%d lootable), huddled %d, kneeling %d, weeping %d, workers %d, aid people %d, guards %d, others %d",
                bodies, lootable, huddled, kneeling, weeping, workers, aid, guards, other));
        out.add(String.format(Locale.ROOT, "  aid camp props: bedrolls %d, cooking pots %d; floating fire/decal/bed blocks %d %s",
                bedrolls, pots, floating, floatSample));
        for (String line : out) {
            src.sendSuccess(() -> Component.literal(line), false);
            Skycraft.LOGGER.info("[fire] {}", line);
        }
        return sites;
    }
}
