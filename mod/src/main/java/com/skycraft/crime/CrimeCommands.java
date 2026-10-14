package com.skycraft.crime;

import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** {@code /skycraft-crime selftest} (operators): the headless check of the crime module's decisions. */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class CrimeCommands {
    private CrimeCommands() {}

    @SubscribeEvent
    public static void registerCrimeCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("skycraft-crime").requires(s -> s.hasPermission(2))
                .then(Commands.literal("selftest").executes(CrimeCommands::selftest)));
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        CrimeSelfTest.Report r = CrimeSelfTest.run(c.getSource().getServer());
        for (String line : r.lines()) c.getSource().sendSuccess(() -> Component.literal(line), false);
        c.getSource().sendSuccess(() -> Component.literal(r.summary()), false);
        Skycraft.LOGGER.info(r.summary());
        return r.problems() == 0 ? 1 : 0;
    }
}
