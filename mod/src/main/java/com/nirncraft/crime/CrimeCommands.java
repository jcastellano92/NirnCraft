package com.nirncraft.crime;

import com.mojang.brigadier.context.CommandContext;
import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** {@code /nirncraft-crime selftest} (operators): the headless check of the crime module's decisions. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class CrimeCommands {
    private CrimeCommands() {}

    @SubscribeEvent
    public static void registerCrimeCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("nirncraft-crime").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("selftest").executes(CrimeCommands::selftest)));
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        CrimeSelfTest.Report r = CrimeSelfTest.run(c.getSource().getServer());
        for (String line : r.lines()) c.getSource().sendSuccess(() -> Component.literal(line), false);
        c.getSource().sendSuccess(() -> Component.literal(r.summary()), false);
        NirnCraft.LOGGER.info(r.summary());
        return r.problems() == 0 ? 1 : 0;
    }
}
