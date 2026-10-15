package com.skycraft.tamriel.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Locale;

/**
 * Debug command {@code /skycraft-world sheet <scope> [layer]} and background sheet preloader (MS22).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldSheetCommand {
    private WorldSheetCommand() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        WorldSheets.preloadAsync();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("skycraft-world").requires(s -> s.hasPermission(2))
                .then(Commands.literal("sheet")
                        .then(Commands.argument("scope", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(WorldSheets.scopes(), b))
                                .executes(ctx -> showSheet(ctx, null))
                                .then(Commands.argument("layer", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(WorldSheets.LAYERS, b))
                                        .executes(ctx -> showSheet(ctx, StringArgumentType.getString(ctx, "layer"))))))
                .then(Commands.literal("field")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(WorldSheetCommand::showField))));
    }

    private static int showSheet(CommandContext<CommandSourceStack> ctx, String layerArg) {
        String rawScope = StringArgumentType.getString(ctx, "scope");
        WorldSheets.SheetBlock block = WorldSheets.scope(rawScope);
        if (block == null) {
            ctx.getSource().sendFailure(Component.literal("[worldsheet] Unknown scope: " + rawScope));
            return 0;
        }
        if (layerArg == null || layerArg.isBlank()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[worldsheet] " + block.scope() + " (" + block.province() + "): " + block.truth()), false);
            for (String lyr : WorldSheets.LAYERS) {
                List<WorldSheets.Field> flist = block.fieldsIn(lyr);
                String ids = flist.stream().map(WorldSheets.Field::id).reduce((a, b) -> a + ", " + b).orElse("(none)");
                ctx.getSource().sendSuccess(() -> Component.literal(
                        "  " + lyr + " (" + flist.size() + "): " + ids), false);
            }
            return flistCount(block);
        }

        String normLayer = layerArg.trim().toUpperCase(Locale.ROOT);
        List<WorldSheets.Field> fields = block.fieldsIn(normLayer);
        if (fields.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "[worldsheet] No fields in " + block.scope() + " " + normLayer));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[worldsheet] " + block.scope() + " " + normLayer + " (" + fields.size() + " fields):"), false);
        for (WorldSheets.Field f : fields) {
            String citesStr = f.cites().isEmpty() ? "root" : String.join(", ", f.cites());
            boolean allResolve = f.cites().stream().allMatch(WorldSheets::resolves);
            String status = allResolve ? "OK" : "UNRESOLVED";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "  " + f.globalId() + " [" + status + " <- " + citesStr + "]: " + f.text()), false);
        }
        return fields.size();
    }

    private static int showField(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        WorldSheets.Field f = WorldSheets.field(id);
        if (f == null) {
            ctx.getSource().sendFailure(Component.literal("[worldsheet] Field does not resolve: " + id));
            return 0;
        }
        String citesStr = f.cites().isEmpty() ? "root" : String.join(", ", f.cites());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[worldsheet] " + f.globalId() + " (cites: " + citesStr + "): " + f.text()), false);
        return 1;
    }

    private static int flistCount(WorldSheets.SheetBlock block) {
        int total = 0;
        for (String lyr : WorldSheets.LAYERS) total += block.fieldsIn(lyr).size();
        return total;
    }
}
