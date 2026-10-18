package com.skycraft.calendar;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Registers the {@code /skycraft-calendar} dev and QA command (MS16):
 * <ul>
 *   <li>{@code /skycraft-calendar list [region]}</li>
 *   <li>{@code /skycraft-calendar today}</li>
 *   <li>{@code /skycraft-calendar trigger <id>}</li>
 *   <li>{@code /skycraft-calendar selftest}</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class FestivalCommand {
    private FestivalCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("skycraft-calendar")
                        .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                        .executes(FestivalCommand::cmdToday)
                        .then(Commands.literal("today")
                                .executes(FestivalCommand::cmdToday))
                        .then(Commands.literal("list")
                                .executes(ctx -> cmdList(ctx, null))
                                .then(Commands.argument("region", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(FestivalCalendar.SKYRIM_HOLDS, builder))
                                        .executes(ctx -> cmdList(ctx, StringArgumentType.getString(ctx, "region")))))
                        .then(Commands.literal("trigger")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                FestivalCalendar.all().stream().map(FestivalCalendar.Festival::id), builder))
                                        .executes(ctx -> cmdTrigger(ctx, StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("selftest")
                                .executes(FestivalCommand::cmdSelftest))
        );
    }

    private static int cmdToday(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        FestivalCalendar.TamrielDate date = FestivalCalendar.dateAt(level);

        String hold = null;
        if (src.getEntity() instanceof ServerPlayer player) {
            Region reg = Regions.at(level, player.blockPosition());
            hold = FestivalCalendar.shortHoldId(reg.id());
        }

        List<FestivalCalendar.Festival> active = FestivalCalendar.activeFestivals(level, null);
        src.sendSuccess(() -> Component.translatable(
                "skycraft.calendar.command.today",
                date.formatComponent(),
                active.size()
        ).withStyle(ChatFormatting.GOLD), false);

        for (FestivalCalendar.Festival f : active) {
            src.sendSuccess(() -> Component.literal(" - " + f.id() + " (" + f.region() + "/" + f.settlement() + "): ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(f.titleComponent()), false);
        }
        if (hold != null) {
            final String h = hold;
            List<FestivalCalendar.Festival> holdFestivals = FestivalCalendar.all().stream()
                    .filter(f -> f.matchesRegion(h))
                    .toList();
            src.sendSuccess(() -> Component.literal("Hold festivals in " + h + ": " + holdFestivals.size())
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int cmdList(CommandContext<CommandSourceStack> ctx, @Nullable String regionFilter) {
        CommandSourceStack src = ctx.getSource();
        List<FestivalCalendar.Festival> matching = FestivalCalendar.all().stream()
                .filter(f -> f.matchesRegion(regionFilter))
                .toList();

        String label = regionFilter == null ? "all" : regionFilter;
        src.sendSuccess(() -> Component.translatable(
                "skycraft.calendar.command.list.header",
                label,
                matching.size()
        ).withStyle(ChatFormatting.GOLD), false);

        for (FestivalCalendar.Festival f : matching) {
            src.sendSuccess(() -> Component.literal(String.format(
                    " - %s [%d %s, day %d, %dd] (%s -> %s) quest=%s",
                    f.id(), f.day(), f.monthName(), f.dayOfYear(), f.durationDays(),
                    f.region(), f.settlement(), f.festivalQuest()
            )).withStyle(ChatFormatting.AQUA), false);
        }
        return matching.size();
    }

    private static int cmdTrigger(CommandContext<CommandSourceStack> ctx, String id) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getPlayer();
        boolean ok = FestivalCalendar.triggerFestival(level, id, player);
        if (!ok) {
            src.sendFailure(Component.translatable("skycraft.calendar.command.trigger.unknown", id));
            return 0;
        }
        src.sendSuccess(() -> Component.translatable("skycraft.calendar.command.trigger.ok", id)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int cmdSelftest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        FestivalCalendar.SelfTestResult res = FestivalCalendar.selftest();
        if (!res.ok()) {
            for (String err : res.errors()) {
                src.sendFailure(Component.literal("[FAIL] " + err));
            }
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[PASS] skycraft-calendar selftest: ")
                .append(res.festivalCount()).append(" festivals across ")
                .append(res.monthCount()).append(" Tamrielic months; holds=");
        for (Map.Entry<String, Integer> e : res.holdCounts().entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append(' ');
        }
        src.sendSuccess(() -> Component.literal(sb.toString().trim()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
