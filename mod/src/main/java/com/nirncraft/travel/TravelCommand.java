package com.nirncraft.travel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Dev and diagnostic command {@code /nirncraft-travel} for inspecting travel networks, routes, coverage, and selftests.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class TravelCommand {
    private TravelCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nirncraft-travel")
                .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .executes(TravelCommand::summary)
                .then(Commands.literal("list")
                        .executes(TravelCommand::summary)
                        .then(Commands.argument("node", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        TravelNetworks.allNodes().stream().map(TravelNode::id), builder))
                                .executes(ctx -> listNode(ctx, StringArgumentType.getString(ctx, "node")))))
                .then(Commands.literal("coverage")
                        .executes(ctx -> coverage(ctx, "skyrim"))
                        .then(Commands.argument("province", StringArgumentType.word())
                                .executes(ctx -> coverage(ctx, StringArgumentType.getString(ctx, "province")))))
                .then(Commands.literal("selftest")
                        .executes(TravelCommand::selftest))
                .then(Commands.literal("reload")
                        .executes(TravelCommand::reload))
                .then(Commands.literal("go")
                        .then(Commands.argument("from", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        TravelNetworks.allNodes().stream().map(TravelNode::id), builder))
                                .then(Commands.argument("to", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                TravelNetworks.allNodes().stream().map(TravelNode::id), builder))
                                        .executes(ctx -> go(ctx,
                                                StringArgumentType.getString(ctx, "from"),
                                                StringArgumentType.getString(ctx, "to"),
                                                null))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> go(ctx,
                                                        StringArgumentType.getString(ctx, "from"),
                                                        StringArgumentType.getString(ctx, "to"),
                                                        StringArgumentType.getString(ctx, "mode"))))))));
    }

    private static int summary(CommandContext<CommandSourceStack> ctx) {
        List<TravelNode> nodes = TravelNetworks.allNodes();
        List<TravelRoute> routes = TravelNetworks.allRoutes();
        long wild = nodes.stream().filter(TravelNode::isWilderness).count();
        Set<TravelMode> modes = EnumSet.noneOf(TravelMode.class);
        for (TravelRoute r : routes) modes.add(r.mode());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.nirncraft.travel.summary",
                TravelNetworks.provinces().size(),
                nodes.size(),
                wild,
                routes.size(),
                modes.size()
        ), false);
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            TravelNetworks.nearestNode(player.getBlockX(), player.getBlockZ(), 2000.0)
                    .ifPresent(near -> listNode(ctx, near.id()));
        }
        return 1;
    }

    private static int listNode(CommandContext<CommandSourceStack> ctx, String nodeId) {
        Optional<TravelNode> opt = TravelNetworks.node(nodeId);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("command.nirncraft.travel.unknown_node", nodeId));
            return 0;
        }
        TravelNode node = opt.get();
        String modesStr = String.join(", ", node.modes().stream().map(TravelMode::id).toList());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.nirncraft.travel.node_header",
                node.displayName(),
                node.id(),
                node.x(),
                node.z(),
                modesStr
        ), false);
        List<TravelNetworks.Offer> offers = TravelNetworks.offersFrom(node.id());
        for (TravelNetworks.Offer o : offers) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "command.nirncraft.travel.route_line",
                    o.destination().displayName(),
                    o.path(),
                    o.mode().id(),
                    o.fee(),
                    o.durationSeconds()
            ), false);
        }
        return offers.size() + 1;
    }

    private static int coverage(CommandContext<CommandSourceStack> ctx, String province) {
        TravelNetworks.CoverageStats cov = TravelNetworks.coverage(province);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.nirncraft.travel.coverage",
                cov.province(),
                cov.landCells(),
                cov.totalNodes(),
                String.format(Locale.ROOT, "%.2f", cov.nodesPerKm2()),
                String.format(Locale.ROOT, "%.1f", cov.p50()),
                String.format(Locale.ROOT, "%.1f", cov.p90()),
                String.format(Locale.ROOT, "%.1f", cov.max())
        ), false);
        for (TravelNetworks.HoldCoverage hc : cov.byRegion().values()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "command.nirncraft.travel.coverage_hold",
                    hc.regionId(),
                    String.format(Locale.ROOT, "%.1f", hc.p50()),
                    String.format(Locale.ROOT, "%.1f", hc.p90()),
                    String.format(Locale.ROOT, "%.1f", hc.max()),
                    hc.landCells()
            ), false);
        }
        return cov.p90() <= 500.0 && cov.max() <= 800.0 ? 1 : 0;
    }

    private static int selftest(CommandContext<CommandSourceStack> ctx) {
        TravelNetworks.SelftestResult res = TravelNetworks.selftest();
        for (String line : res.details()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + line), false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.nirncraft.travel.selftest",
                res.passed(),
                res.total(),
                res.problems()
        ), false);
        return res.problems() == 0 ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        TravelNetworks.reload();
        return summary(ctx);
    }

    private static int go(CommandContext<CommandSourceStack> ctx, String from, String to, String modeStr) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Player required"));
            return 0;
        }
        TravelMode mode = modeStr != null ? TravelMode.byId(modeStr).orElse(null) : null;
        boolean ok = TravelNetworks.travel(player, from, to, mode);
        return ok ? 1 : 0;
    }
}
