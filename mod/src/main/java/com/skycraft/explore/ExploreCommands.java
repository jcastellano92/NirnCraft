package com.skycraft.explore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.explore.audit.DensityAudit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code /skycraft-explore} (op 2): the density audit, and helpers for finding and testing minor sites.
 * <ul>
 *     <li>{@code audit [regions|tamriel|all] [perRegion] [realms]}: runs {@link DensityAudit} on a background thread
 *     and writes CSVs to {@code <server>/skycraft-explore/};</li>
 *     <li>{@code near [radius]}: the minor sites around you;</li>
 *     <li>{@code locate <kind> [radius]}: the nearest site of a kind;</li>
 *     <li>{@code map}: a treasure map of the nearest cache; {@code unique <id>}: a unique item; {@code uniques}: where
 *     they are.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ExploreCommands {
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private ExploreCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-explore").requires(s -> s.hasPermission(2))
                .then(Commands.literal("audit")
                        .executes(c -> audit(c, "all", 2, 8))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("regions", "tamriel", "all"), b))
                                .executes(c -> audit(c, StringArgumentType.getString(c, "mode"), 2, 8))
                                .then(Commands.argument("perRegion", IntegerArgumentType.integer(1, 8))
                                        .executes(c -> audit(c, StringArgumentType.getString(c, "mode"), IntegerArgumentType.getInteger(c, "perRegion"), 8))
                                        .then(Commands.argument("realms", IntegerArgumentType.integer(0, 32))
                                                .executes(c -> audit(c, StringArgumentType.getString(c, "mode"),
                                                        IntegerArgumentType.getInteger(c, "perRegion"), IntegerArgumentType.getInteger(c, "realms")))))))
                .then(Commands.literal("near")
                        .executes(c -> near(c, 400))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 4000)).executes(c -> near(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("locate")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(SiteKind.VALUES).map(k -> k.id), b))
                                .executes(c -> locate(c, StringArgumentType.getString(c, "kind"), 3000))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 20000))
                                        .executes(c -> locate(c, StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "radius"))))))
                .then(Commands.literal("built")
                        .executes(c -> built(c, 400))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 1500)).executes(c -> built(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("map").executes(ExploreCommands::map))
                .then(Commands.literal("uniques").executes(ExploreCommands::uniques))
                .then(Commands.literal("unique")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Uniques.all().stream().map(Uniques.UniqueSite::id), b))
                                .executes(ExploreCommands::unique))));
    }

    private static int audit(CommandContext<CommandSourceStack> c, String mode, int perRegion, int realms) {
        CommandSourceStack src = c.getSource();
        if (!BUSY.compareAndSet(false, true)) {
            src.sendFailure(Component.literal("A density audit is already running"));
            return 0;
        }
        MinecraftServer server = src.getServer();
        ServerLevel level = server.overworld();
        Path dir = server.getServerDirectory().toPath().resolve("skycraft-explore");
        src.sendSuccess(() -> Component.literal("Density audit (" + mode + ") started; this takes a few minutes"), true);
        Thread t = new Thread(() -> {
            try {
                DensityAudit audit = new DensityAudit(level, msg -> server.execute(() -> src.sendSuccess(() -> Component.literal(msg), false)));
                List<String> out = new ArrayList<>();
                if (!mode.equals("tamriel")) out.add(audit.run(audit.regionAreas(perRegion, realms), dir, "regions"));
                if (!mode.equals("regions")) out.add(audit.run(audit.tamrielTiles(), dir, "tamriel"));
                for (String s : out) {
                    Skycraft.LOGGER.info("Skycraft density audit: {}", s);
                    server.execute(() -> src.sendSuccess(() -> Component.literal(s), true));
                }
            } catch (Throwable e) {
                Skycraft.LOGGER.error("Density audit failed", e);
                server.execute(() -> src.sendFailure(Component.literal("Density audit failed: " + e)));
            } finally {
                BUSY.set(false);
            }
        }, "Skycraft density audit");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static int near(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        long seed = src.getLevel().getSeed();
        List<MinorSites.Site> sites = MinorSites.within(seed, (int) p.x, (int) p.z, radius);
        src.sendSuccess(() -> Component.literal(sites.size() + " minor sites within " + radius + " blocks"), false);
        for (MinorSites.Site s : sites.subList(0, Math.min(20, sites.size()))) {
            int d = (int) Math.sqrt(s.distSqr((int) p.x, (int) p.z));
            String line = String.format(Locale.ROOT, "  %s at %d %d (%d blocks)%s", s.kind().id, s.x(), s.z(), d,
                    s.unique() != null ? " [unique: " + s.unique() + "]" : "");
            src.sendSuccess(() -> Component.literal(line), false);
        }
        return sites.size();
    }

    /** Generates the chunks of every site within {@code radius} and reports which kinds were actually built. */
    private static int built(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getServer().overworld();
        Vec3 p = src.getPosition();
        List<MinorSites.Site> sites = MinorSites.within(level.getSeed(), (int) p.x, (int) p.z, radius);
        java.util.Map<SiteKind, int[]> tally = new java.util.EnumMap<>(SiteKind.class);
        for (MinorSites.Site s : sites) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(s.chunkX() + dx, s.chunkZ() + dz);
            boolean ok = ExploreEvents.built(level, s);
            int[] t = tally.computeIfAbsent(s.kind(), k -> new int[2]);
            t[ok ? 0 : 1]++;
        }
        int ok = 0;
        for (var e : tally.entrySet()) {
            ok += e.getValue()[0];
            String line = String.format(Locale.ROOT, "  %s: %d built, %d refused", e.getKey().id, e.getValue()[0], e.getValue()[1]);
            src.sendSuccess(() -> Component.literal(line), false);
        }
        int fOk = ok;
        src.sendSuccess(() -> Component.literal(fOk + "/" + sites.size() + " minor sites built within " + radius + " blocks"), false);
        return ok;
    }

    private static int locate(CommandContext<CommandSourceStack> c, String kindId, int radius) {
        CommandSourceStack src = c.getSource();
        SiteKind kind = null;
        for (SiteKind k : SiteKind.VALUES) if (k.id.equals(kindId)) kind = k;
        if (kind == null) {
            src.sendFailure(Component.literal("Unknown site kind " + kindId));
            return 0;
        }
        Vec3 p = src.getPosition();
        long seed = src.getLevel().getSeed();
        // search outwards ring by ring so the first hit is (nearly) the nearest
        for (int r = 400; r <= radius; r = Math.min(radius, r * 2)) {
            MinorSites.Site s = MinorSites.nearest(seed, kind, (int) p.x, (int) p.z, r, Long.MIN_VALUE);
            if (s != null) {
                String line = String.format(Locale.ROOT, "%s at %d %d (%d blocks) in %s", kind.id, s.x(), s.z(),
                        (int) Math.sqrt(s.distSqr((int) p.x, (int) p.z)), s.regionId());
                src.sendSuccess(() -> Component.literal(line), false);
                return 1;
            }
            if (r == radius) break;
        }
        src.sendFailure(Component.literal("No " + kind.id + " within " + radius + " blocks"));
        return 0;
    }

    private static int map(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (player.level().dimension() != Level.OVERWORLD) return 0;
        MinorSites.Site s = MinorSites.nearest(player.serverLevel().getSeed(), SiteKind.TREASURE_CACHE, player.getBlockX(),
                player.getBlockZ(), ExploreLoot.MAP_RANGE, Long.MIN_VALUE);
        if (s == null) {
            c.getSource().sendFailure(Component.literal("No treasure cache nearby"));
            return 0;
        }
        player.getInventory().add(TreasureMapItem.create(s, RandomSource.create()));
        return 1;
    }

    private static int uniques(CommandContext<CommandSourceStack> c) {
        for (Uniques.UniqueSite u : Uniques.all()) {
            String line = String.format(Locale.ROOT, "%s: %s at %d %d", u.id(), u.kind().id, u.x(), u.z());
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Uniques.all().size();
    }

    private static int unique(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack stack = Uniques.stack(StringArgumentType.getString(c, "id"));
        if (stack.isEmpty()) return 0;
        player.getInventory().add(stack);
        return 1;
    }
}
