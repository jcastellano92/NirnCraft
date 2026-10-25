package com.nirncraft.hold;

import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Operator tools for the player hold. {@code status} says who is held and for how long; {@code release} is the way out
 * for a client that cannot report ready (a hold is never released silently, so an operator does it knowingly);
 * {@code hold} puts a player back under the hold (their client then reports ready as at login, which the harness uses);
 * {@code selftest} runs {@link HoldSelftest} on stand-in players and prints "Hold selftest: ... N problems".
 */
@EventBusSubscriber(modid = NirnCraft.MODID)
public final class HoldCommands {
    private HoldCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nirncraft-hold")
                .requires(src -> Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(src))
                .then(Commands.literal("status").executes(c -> status(c.getSource(), List.copyOf(c.getSource().getServer().getPlayerList().getPlayers())))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> status(c.getSource(), List.of(EntityArgument.getPlayer(c, "player"))))))
                .then(Commands.literal("release")
                        .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            boolean did = PlayerHold.release(p, "an operator released them");
                            c.getSource().sendSuccess(() -> Component.literal(did ? "released " + p.getGameProfile().name() : p.getGameProfile().name() + " is not held"), true);
                            return did ? 1 : 0;
                        })))
                .then(Commands.literal("hold")
                        .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            PlayerHold.begin(p, null);
                            c.getSource().sendSuccess(() -> Component.literal("holding " + p.getGameProfile().name() + " until their client reports ready"), true);
                            return 1;
                        })))
                .then(Commands.literal("selftest").executes(c -> selftest(c.getSource()))));
    }

    private static int status(CommandSourceStack src, List<ServerPlayer> players) {
        int held = 0;
        for (ServerPlayer p : players) {
            if (PlayerHold.isHeld(p)) held++;
            src.sendSuccess(() -> Component.literal(PlayerHold.describe(p)), false);
        }
        int n = held;
        src.sendSuccess(() -> Component.literal("Hold status: " + n + " of " + players.size() + " held"), false);
        return held;
    }

    private static int selftest(CommandSourceStack src) {
        List<String> info = new ArrayList<>();
        List<String> problems = HoldSelftest.run(src.getServer(), info);
        for (String line : info) src.sendSuccess(() -> Component.literal(line), false);
        for (String line : problems) src.sendFailure(Component.literal("PROBLEM " + line));
        String summary = "Hold selftest: " + problems.size() + " problems";
        NirnCraft.LOGGER.info(summary);
        for (String line : problems) NirnCraft.LOGGER.error("Hold selftest problem: {}", line);
        src.sendSuccess(() -> Component.literal(summary), false);
        return problems.isEmpty() ? 1 : 0;
    }
}
