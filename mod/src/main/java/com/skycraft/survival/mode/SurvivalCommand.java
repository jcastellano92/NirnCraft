package com.skycraft.survival.mode;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import com.skycraft.survival.fishing.FishSpecies;
import com.skycraft.survival.fishing.Fishing;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * {@code /skycraft-survival} (operators): {@code info} shows your meters, warmth and the cold where you stand;
 * {@code set <hunger|fatigue|cold> <value> [players]} and {@code reset [players]} change them (testing);
 * {@code fish} lists what could bite in the water you look at.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class SurvivalCommand {
    private SurvivalCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        var set = Commands.literal("set");
        for (String meter : List.of("hunger", "fatigue", "cold")) {
            set.then(Commands.literal(meter).then(Commands.argument("value", FloatArgumentType.floatArg(0, 300))
                    .executes(c -> set(c, meter, List.of(c.getSource().getPlayerOrException())))
                    .then(Commands.argument("players", EntityArgument.players())
                            .executes(c -> set(c, meter, EntityArgument.getPlayers(c, "players"))))));
        }
        d.register(Commands.literal("skycraft-survival").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("info").executes(SurvivalCommand::info))
                .then(set)
                .then(Commands.literal("reset").executes(c -> reset(c, List.of(c.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(c -> reset(c, EntityArgument.getPlayers(c, "players")))))
                .then(Commands.literal("fish").executes(SurvivalCommand::fish)));
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Climate.Env env = Climate.sample(p);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.survival.info",
                SurvivalMode.enabled(p.level()) ? "on" : "off",
                Math.round(SurvivalMode.hunger(p)), Component.translatable("survival.skycraft.hunger.stage." + SurvivalMode.hungerStage(SurvivalMode.hunger(p))),
                Math.round(SurvivalMode.fatigue(p)), Component.translatable("survival.skycraft.fatigue.stage." + SurvivalMode.fatigueStage(SurvivalMode.fatigue(p))),
                Math.round(SurvivalMode.cold(p)), Component.translatable("survival.skycraft.cold.stage." + SurvivalMode.coldStage(SurvivalMode.cold(p))),
                Warmth.of(p), Math.round(env.target()), Math.round(env.base()),
                env.heat() ? "heat" : env.sheltered() ? "shelter" : env.inWater() ? "water" : "open"), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> c, String meter, Collection<ServerPlayer> players) {
        float value = FloatArgumentType.getFloat(c, "value");
        for (ServerPlayer p : players) SurvivalMode.set(p, meter, value);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.survival.set", meter, value, players.size()), true);
        return players.size();
    }

    private static int reset(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            SurvivalMode.set(p, "hunger", 0);
            SurvivalMode.set(p, "fatigue", 0);
            SurvivalMode.set(p, "cold", 0);
        }
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.survival.reset", players.size()), true);
        return players.size();
    }

    private static int fish(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        var hit = p.pick(24, 1f, true);
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(hit.getLocation());
        if (!p.level().getFluidState(pos).isEmpty() || !p.level().getFluidState(pos.below()).isEmpty()) {
            if (p.level().getFluidState(pos).isEmpty()) pos = pos.below();
        }
        Fishing.Waters w = Fishing.waters(p.level(), pos);
        StringJoiner names = new StringJoiner(", ");
        for (FishSpecies s : FishSpecies.all()) {
            if (Fishing.canBite(s, w)) names.add(s.id());
        }
        String kinds = w.kinds().toString();
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.survival.fish", kinds, w.province() + "/" + w.region(),
                w.depth(), names.length() == 0 ? "-" : names.toString()), false);
        return 1;
    }
}
