package com.skycraft.atmosphere.weather;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Arrays;

/**
 * {@code /skycraft-weather info | set <kind> [minutes] | reroll} (operators only): the regional weather where the
 * executing player stands.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class WeatherCommand {
    private WeatherCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-weather").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("info").executes(WeatherCommand::info))
                .then(Commands.literal("reroll").executes(WeatherCommand::reroll))
                .then(Commands.literal("set").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(WeatherKind.values()).map(WeatherKind::id), b))
                        .executes(c -> set(c, 0))
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(c -> set(c, IntegerArgumentType.getInteger(c, "minutes") * 1200))))));
    }

    private static Region here(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        if (p.level().dimension() != Level.OVERWORLD) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.translatable("command.skycraft.weather.overworld_only")).create();
        }
        return Regions.at(p.level(), p.blockPosition());
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Region r = here(c);
        var server = c.getSource().getServer();
        WeatherKind kind = RegionalWeather.kindOf(server, r.id());
        long left = RegionalWeather.remaining(server, r.id()) / 20;
        WeatherProfile profile = WeatherProfile.of(r);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.weather.info",
                r.id(), profile.id, kind.id(), left / 60, left % 60), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> c, int ticks) throws CommandSyntaxException {
        Region r = here(c);
        WeatherKind kind = WeatherKind.byId(StringArgumentType.getString(c, "kind"));
        if (kind == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.weather.unknown"));
            return 0;
        }
        RegionalWeather.set(c.getSource().getServer(), r, kind, ticks);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.weather.set", r.id(), kind.id()), true);
        return 1;
    }

    private static int reroll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Region r = here(c);
        ServerLevel ow = c.getSource().getServer().overworld();
        WeatherProfile profile = WeatherProfile.of(r);
        WeatherKind kind = profile.roll(ow.getRandom(), RegionalWeather.kindOf(c.getSource().getServer(), r.id()));
        RegionalWeather.set(c.getSource().getServer(), r, kind, 0);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.weather.set", r.id(), kind.id()), true);
        return 1;
    }
}
