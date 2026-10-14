package com.skycraft.mounts;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.Arrays;

/**
 * {@code /skycraft-mounts}: {@code list} (your stable), {@code grant <kind> [player]} (also the quest/realm hook for
 * scripts), {@code call}, {@code spawn <kind>} (an unowned, saddled mount for testing: the first rider with a saddle
 * claims it).
 */
public final class MountCommands {
    private MountCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-mounts")
                .then(Commands.literal("list").executes(MountCommands::list))
                .then(Commands.literal("selftest").requires(s -> s.hasPermission(2)).executes(c -> {
                    MountsSelfTest.Report r = MountsSelfTest.run(c.getSource().getServer());
                    for (String line : r.lines()) c.getSource().sendSuccess(() -> Component.literal(line), false);
                    c.getSource().sendSuccess(() -> Component.literal(r.summary()), false);
                    return r.problems() == 0 ? 1 : 0;
                }))
                .then(Commands.literal("call").executes(c -> {
                    Mounts.call(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("grant").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(MountCommands::kinds)
                                .executes(c -> grant(c, c.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(c -> grant(c, EntityArgument.getPlayer(c, "player"))))))
                .then(Commands.literal("spawn").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("kind", StringArgumentType.word()).suggests(MountCommands::kinds)
                                .executes(MountCommands::spawn))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> kinds(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(Arrays.stream(MountKind.VALUES).map(k -> k.id), b);
    }

    private static int list(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        MountKind active = Mounts.active(p);
        for (MountKind k : Mounts.stable(p)) {
            c.getSource().sendSuccess(() -> Component.literal((k == active ? "* " : "  ")).append(k.displayName())
                    .append(" (" + k.id + ")"), false);
        }
        if (Mounts.stable(p).isEmpty()) c.getSource().sendSuccess(() -> Component.translatable("mounts.skycraft.none"), false);
        return 1;
    }

    private static int grant(CommandContext<CommandSourceStack> c, ServerPlayer p) {
        MountKind k = MountKind.byId(StringArgumentType.getString(c, "kind"));
        if (k == null) {
            c.getSource().sendFailure(Component.literal("Unknown mount kind"));
            return 0;
        }
        Mounts.grant(p, k, null);
        c.getSource().sendSuccess(() -> Component.literal("Granted ").append(k.displayName()).append(" to " + p.getScoreboardName()), true);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        MountKind k = MountKind.byId(StringArgumentType.getString(c, "kind"));
        if (k == null) return 0;
        var at = Mounts.safeSpot(p.serverLevel(), p.blockPosition().relative(p.getDirection(), 3));
        Mob m = Mounts.create(p.serverLevel(), k, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.getYRot() + 180, null);
        if (m == null) return 0;
        p.serverLevel().addFreshEntity(m);
        return 1;
    }
}
