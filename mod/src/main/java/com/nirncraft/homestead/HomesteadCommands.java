package com.nirncraft.homestead;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** {@code /nirncraft-homestead} (operators): {@code list} the claims, {@code selftest}. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class HomesteadCommands {
    private HomesteadCommands() {}

    @SubscribeEvent
    public static void registerHomesteadCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-homestead").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("list").executes(HomesteadCommands::list))
                .then(Commands.literal("selftest").executes(HomesteadCommands::selftest)));
    }

    private static void out(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        var claims = HomesteadData.get(src.getServer()).allClaims();
        out(src, claims.size() + " homesteads");
        for (HomesteadClaim h : claims) {
            out(src, h.plainName() + " (" + h.getOwnerName() + ") at " + h.getCenter().getX() + " " + h.getCenter().getY() + " "
                    + h.getCenter().getZ() + ", radius " + h.getRadius() + ", built " + h.getBuilt().size()
                    + ", doors/containers/stations/beds/build " + h.getDoorPerm() + "/" + h.getContainerPerm() + "/"
                    + h.getStationPerm() + "/" + h.getBedPerm() + "/" + h.getBuildPerm());
        }
        return claims.size();
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        HomesteadSelfTest.Report r = HomesteadSelfTest.run(c.getSource().getServer());
        for (String line : r.lines()) out(c.getSource(), line);
        out(c.getSource(), r.summary());
        return r.problems() == 0 ? 1 : 0;
    }
}
