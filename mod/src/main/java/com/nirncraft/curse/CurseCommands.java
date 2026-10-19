package com.nirncraft.curse;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer commands (operators): {@code /nirncraft-curse infect | vampire | stage <1-4> | lord | beast |
 * cure [vampirism|lycanthropy|all] | revert | feed <werewolf|vampire_lord> <n> | status}, each with optional
 * {@code [targets]}. {@code lord} and {@code beast} grant the curse and transform at once.
 */
public final class CurseCommands {
    private CurseCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("nirncraft-curse").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(each("infect", Curses::infect))
                .then(each("vampire", Curses::turnVampire))
                .then(Commands.literal("stage").then(withTargets(Commands.argument("stage", IntegerArgumentType.integer(1, 4)),
                        c -> IntegerArgumentType.getInteger(c, "stage"), (p, stage) -> {
                            if (!Curses.isVampire(p)) Curses.turnVampire(p);
                            CompoundTag t = Curses.tag(p);
                            t.putLong(Curses.FED, p.level().getGameTime() - (long) (stage - 1) * Vampirism.DAY - 20);
                            Curses.dirty(p);
                        })))
                .then(each("lord", p -> {
                    Curses.grantVampireLord(p);
                    if (Curses.form(p) == Form.BEAST) Lycanthropy.revert(p, false);
                    if (Curses.form(p) == Form.NONE) VampireLord.transform(p);
                }))
                .then(each("beast", p -> {
                    Curses.grantBeastBlood(p);
                    if (Curses.form(p) == Form.NONE) {
                        Curses.tag(p).remove(Curses.BEAST_READY);
                        Lycanthropy.transform(p);
                    }
                }))
                .then(each("revert", p -> {
                    if (Curses.form(p) == Form.BEAST) Lycanthropy.revert(p, true);
                    else if (Curses.form(p) == Form.LORD) VampireLord.revert(p, true);
                }))
                .then(Commands.literal("cure")
                        .executes(c -> run(c, List.of(c.getSource().getPlayerOrException()), p -> cureAll(p)))
                        .then(withTargets(Commands.argument("curse", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("vampirism", "lycanthropy", "all"), b)),
                                c -> StringArgumentType.getString(c, "curse"), (p, which) -> {
                                    CurseType type = CurseType.byId(which);
                                    if (type == null) cureAll(p);
                                    else Curses.cure(p, type);
                                })))
                .then(Commands.literal("feed").then(Commands.argument("tree", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("werewolf", "vampire_lord"), b))
                        .then(withTargets(Commands.argument("count", IntegerArgumentType.integer(1, 200)),
                                c -> StringArgumentType.getString(c, "tree") + ":" + IntegerArgumentType.getInteger(c, "count"), (p, spec) -> {
                                    String[] parts = spec.split(":");
                                    CursePerks.Tree tree = parts[0].startsWith("vampire") ? CursePerks.Tree.VAMPIRE_LORD : CursePerks.Tree.WEREWOLF;
                                    for (int i = Integer.parseInt(parts[1]); i > 0; i--) CursePerks.addFeed(p, tree);
                                }))))
                .then(Commands.literal("status")
                        .executes(c -> status(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> status(c, EntityArgument.getPlayer(c, "target"))))));
    }

    private static void cureAll(ServerPlayer p) {
        Curses.cure(p, CurseType.VAMPIRISM);
        Curses.cure(p, CurseType.LYCANTHROPY);
    }

    private static ArgumentBuilder<CommandSourceStack, ?> each(String name, Consumer<ServerPlayer> action) {
        return Commands.literal(name)
                .executes(c -> run(c, List.of(c.getSource().getPlayerOrException()), action))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(c -> run(c, EntityArgument.getPlayers(c, "targets"), action)));
    }

    private interface Getter<T> {
        T get(CommandContext<CommandSourceStack> c) throws CommandSyntaxException;
    }

    private static <T> ArgumentBuilder<CommandSourceStack, ?> withTargets(ArgumentBuilder<CommandSourceStack, ?> arg, Getter<T> value,
                                                                          java.util.function.BiConsumer<ServerPlayer, T> action) {
        return arg.executes(c -> {
                    T v = value.get(c);
                    return run(c, List.of(c.getSource().getPlayerOrException()), p -> action.accept(p, v));
                })
                .then(Commands.argument("targets", EntityArgument.players()).executes(c -> {
                    T v = value.get(c);
                    return run(c, EntityArgument.getPlayers(c, "targets"), p -> action.accept(p, v));
                }));
    }

    private static int run(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players, Consumer<ServerPlayer> action) {
        for (ServerPlayer p : players) {
            action.accept(p);
            Vampirism.refreshBonuses(p);
            CursePackets.sync(p);
            c.getSource().sendSuccess(() -> describe(p), true);
        }
        return players.size();
    }

    private static int status(CommandContext<CommandSourceStack> c, ServerPlayer p) {
        c.getSource().sendSuccess(() -> describe(p), false);
        return 1;
    }

    private static Component describe(ServerPlayer p) {
        CompoundTag t = Curses.tag(p);
        String s = p.getGameProfile().name() + ": form=" + Curses.form(p).name().toLowerCase()
                + (Curses.isVampire(p) ? ", vampire stage " + Curses.stage(p) + (Curses.hasVampireLord(p) ? " (lord)" : "") : "")
                + (Curses.hasSanguinare(p) ? ", sanguinare day " + t.getIntOr(Curses.SANG_DAYS, 0) : "")
                + (Curses.isWerewolf(p) ? ", werewolf" : "")
                + (Vampirism.inSun(p) ? ", in sun" : "")
                + ", feeds wolf/lord=" + CursePerks.feeds(p, CursePerks.Tree.WEREWOLF) + "/" + CursePerks.feeds(p, CursePerks.Tree.VAMPIRE_LORD)
                + ", perk points wolf/lord=" + CursePerks.available(p, CursePerks.Tree.WEREWOLF) + "/" + CursePerks.available(p, CursePerks.Tree.VAMPIRE_LORD);
        return Component.literal(s);
    }
}
