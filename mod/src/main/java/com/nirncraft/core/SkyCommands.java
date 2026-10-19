package com.nirncraft.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.nirncraft.NirnCraft;
import com.nirncraft.skills.Progression;
import com.nirncraft.vitals.Vitals;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Arrays;

/**
 * {@code /nirncraft ...} admin commands plus player commands {@code /pay}, {@code /withdraw} and {@code /gold}.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SkyCommands {
    private SkyCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        // the /nirncraft root is shared with other modules (e.g. /nirncraft magic), so each admin subcommand carries its own op check
        d.register(Commands.literal("nirncraft")
                .then(Commands.literal("setskill").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("skill", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Skill.VALUES).map(Skill::id), b))
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, 100)).executes(c -> {
                                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                    Skill skill = Skill.byId(StringArgumentType.getString(c, "skill"));
                                    if (skill == null) return 0;
                                    SkyData.get(p).setSkill(skill, IntegerArgumentType.getInteger(c, "level"));
                                    c.getSource().sendSuccess(() -> Component.literal("Set " + skill.id()), true);
                                    return 1;
                                })))))
                .then(Commands.literal("addskill").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("skill", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(Skill.VALUES).map(Skill::id), b))
                                .then(Commands.argument("levels", IntegerArgumentType.integer(1, 100)).executes(c -> {
                                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                    Skill skill = Skill.byId(StringArgumentType.getString(c, "skill"));
                                    if (skill == null) return 0;
                                    Progression.increaseSkill(p, skill, IntegerArgumentType.getInteger(c, "levels"));
                                    return 1;
                                })))))
                .then(Commands.literal("perkpoints").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            PlayerData data = SkyData.get(p);
                            data.setPerkPoints(data.getPerkPoints() + IntegerArgumentType.getInteger(c, "amount"));
                            return 1;
                        }))))
                .then(Commands.literal("gold").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", LongArgumentType.longArg()).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            long amount = LongArgumentType.getLong(c, "amount");
                            PlayerData data = SkyData.get(p);
                            data.setGold(data.getGold() + amount);
                            return 1;
                        }))))
                .then(Commands.literal("resetrace").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    SkyData.get(p).setRace(null);
                    return 1;
                })))
                .then(Commands.literal("reset").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)).then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    SkyData.get(p).load(new PlayerData().save());
                    Vitals.refreshAttributes(p);
                    c.getSource().sendSuccess(() -> Component.literal("Reset NirnCraft data of " + p.getScoreboardName()), true);
                    return 1;
                }))));

        d.register(Commands.literal("gold").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            c.getSource().sendSuccess(() -> Component.translatable("command.nirncraft.gold", Currency.balance(p)), false);
            return 1;
        }));
        d.register(Commands.literal("pay").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(c -> {
                    ServerPlayer from = c.getSource().getPlayerOrException();
                    ServerPlayer to = EntityArgument.getPlayer(c, "player");
                    long amount = LongArgumentType.getLong(c, "amount");
                    if (!Currency.take(from, amount)) {
                        c.getSource().sendFailure(Component.translatable("message.nirncraft.not_enough_gold"));
                        return 0;
                    }
                    Currency.give(to, amount, false); // a transfer is no find
                    c.getSource().sendSuccess(() -> Component.translatable("command.nirncraft.paid", amount, to.getDisplayName()), false);
                    return 1;
                }))));
        d.register(Commands.literal("withdraw").then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            long amount = LongArgumentType.getLong(c, "amount");
            if (!Currency.take(p, amount)) {
                c.getSource().sendFailure(Component.translatable("message.nirncraft.not_enough_gold"));
                return 0;
            }
            var stack = Currency.coins(amount);
            Currency.markNotFound(p, amount);
            com.nirncraft.inventory.Pack.giveOrDrop(p, stack);
            return 1;
        })));
    }
}
