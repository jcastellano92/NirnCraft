package com.nirncraft.inventory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.nirncraft.NirnCraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/** {@code /nirncraft-inventory} (operators): {@code pack} (what your overflow pack holds) and {@code selftest}. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class InventoryCommands {
    private InventoryCommands() {}

    @SubscribeEvent
    public static void registerInventoryCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-inventory").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("pack").executes(InventoryCommands::pack))
                .then(Commands.literal("selftest").executes(InventoryCommands::selftest)));
    }

    private static void out(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static int pack(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) {
            out(c.getSource(), "Only a player has a pack.");
            return 0;
        }
        out(c.getSource(), "Pack: " + Pack.size(p) + " entries (cap " + Pack.MAX_ENTRIES + "), " + Pack.totalItems(p) + " items, weight "
                + ItemWeights.format(Pack.weight(p)) + "; carrying " + ItemWeights.format(CarryWeight.current(p)) + " of "
                + ItemWeights.format(CarryWeight.capacity(p)));
        return 1;
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        PackSelfTest.Report r = PackSelfTest.run(c.getSource().getServer());
        for (String line : r.lines()) {
            out(c.getSource(), line);
            NirnCraft.LOGGER.info("[inventory] {}", line);
        }
        out(c.getSource(), r.summary());
        NirnCraft.LOGGER.info("{}", r.summary());
        return r.problems() == 0 ? 1 : 0;
    }
}
