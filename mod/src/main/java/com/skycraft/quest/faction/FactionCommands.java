package com.skycraft.quest.faction;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Arrays;
import java.util.Optional;

/**
 * {@code /skycraft-faction}: the player's memberships ({@code list}); and for operators, the recorded guild halls and
 * jail of a city ({@code halls <place>}), a trip into one ({@code tp <place> jail|<faction>}) and a stay in a city's
 * jail ({@code imprison <place>}).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class FactionCommands {
    private FactionCommands() {}

    /**
     * Developer smoke tests: {@code -Dskycraft.autorun=cmd1|cmd2|...} (through {@code JAVA_TOOL_OPTIONS}) runs these
     * console commands one after another once the server has started, {@code skycraft.autorun.wait} ticks apart
     * (default 600), e.g. ending with {@code stop}.
     */
    private static final String AUTORUN = System.getProperty("skycraft.autorun", "");
    private static final int AUTORUN_WAIT = Integer.getInteger("skycraft.autorun.wait", 600);
    private static final java.util.ArrayDeque<String> QUEUE = new java.util.ArrayDeque<>();
    private static int autorunTicks;

    @SubscribeEvent
    public static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (AUTORUN.isEmpty()) return;
        // "+" stands for a space (JAVA_TOOL_OPTIONS splits on spaces)
        for (String c : AUTORUN.split("\\|")) if (!c.isBlank()) QUEUE.add(c.trim().replace('+', ' '));
        autorunTicks = 100;
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (QUEUE.isEmpty() || !(event instanceof net.neoforged.neoforge.event.tick.ServerTickEvent.Post) || --autorunTicks > 0) return;
        String cmd = QUEUE.poll();
        autorunTicks = AUTORUN_WAIT;
        var server = event.getServer();
        Skycraft.LOGGER.info("Autorun: {}", cmd);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("skycraft-faction")
                .then(Commands.literal("list").executes(FactionCommands::list))
                .then(Commands.literal("halls").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("place", StringArgumentType.word()).executes(FactionCommands::halls)))
                .then(Commands.literal("imprison").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("place", StringArgumentType.word()).executes(c -> {
                            com.skycraft.crime.Jail.imprison(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "place"));
                            return 1;
                        })))
                .then(Commands.literal("tp").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.argument("place", StringArgumentType.word())
                                .then(Commands.argument("what", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                java.util.stream.Stream.concat(java.util.stream.Stream.of("jail"), Arrays.stream(Faction.VALUES).map(f -> f.id)), b))
                                        .executes(FactionCommands::tp)))));
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        var data = SkyData.get(p);
        int n = 0;
        for (Faction f : Faction.VALUES) {
            int r = Factions.rank(data, f);
            if (r < 0) continue;
            n++;
            c.getSource().sendSuccess(() -> Component.literal("").append(f.displayName()).append(": ").append(f.rankName(r))
                    .append(" (" + Factions.points(data, f) + ")"), false);
        }
        if (n == 0) c.getSource().sendSuccess(() -> Component.translatable("journal.skycraft.not_member"), false);
        return n;
    }

    private static int halls(CommandContext<CommandSourceStack> c) {
        String place = StringArgumentType.getString(c, "place");
        ServerLevel level = c.getSource().getServer().overworld();
        Optional<SettlementAnchors.Entry> e = SettlementAnchors.find(level, place);
        if (e.isEmpty()) {
            c.getSource().sendFailure(Component.literal(place + " hasn't been generated yet"));
            return 0;
        }
        int n = 0;
        for (Anchor a : e.get().anchors()) {
            if (a.type() != AnchorType.GUILD_LEADER && a.type() != AnchorType.JAIL_CELL && a.type() != AnchorType.JAIL_EXIT) continue;
            n++;
            BlockPos p = a.pos();
            c.getSource().sendSuccess(() -> Component.literal(a.type().id() + " " + a.owner() + " at " + p.toShortString())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        int total = n;
        c.getSource().sendSuccess(() -> Component.literal(total + " hall and jail anchors in " + place), false);
        return n;
    }

    private static int tp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String place = StringArgumentType.getString(c, "place");
        String what = StringArgumentType.getString(c, "what");
        ServerLevel level = c.getSource().getServer().overworld();
        BlockPos at = null;
        if (what.equals("jail")) {
            at = SettlementAnchors.find(level, place).map(e -> e.first(AnchorType.JAIL_CELL)).map(Anchor::pos).orElse(null);
        } else {
            at = FactionStaff.hallSpot(c.getSource().getServer(), what, place);
        }
        if (at == null) {
            c.getSource().sendFailure(Component.literal("No " + what + " recorded in " + place));
            return 0;
        }
        p.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
        return 1;
    }
}
