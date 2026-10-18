package com.skycraft.seas;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import com.skycraft.sailing.ShipEntity;
import com.skycraft.seas.traffic.SeaTraffic;
import com.skycraft.seas.traffic.ShipKind;
import com.skycraft.seas.world.CoastSiteFeature;
import com.skycraft.seas.world.IsletFeature;
import com.skycraft.seas.world.SeaSiteFeature;
import com.skycraft.seas.world.SiteKit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /skycraft-seas ship <kind> | site <kind> | traffic} (operators only): put a ship of the traffic on the water
 * near you, build a sea, islet or coast site in your chunk, list the traffic.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class SeasCommands {
    private SeasCommands() {}

    private static List<String> siteNames() {
        List<String> out = new ArrayList<>();
        for (SeaSiteFeature.Kind k : SeaSiteFeature.Kind.values()) out.add("sea_" + k.name().toLowerCase(Locale.ROOT));
        for (IsletFeature.Kind k : IsletFeature.Kind.values()) out.add("islet_" + k.name().toLowerCase(Locale.ROOT));
        for (CoastSiteFeature.Kind k : CoastSiteFeature.Kind.values()) out.add("coast_" + k.name().toLowerCase(Locale.ROOT));
        return out;
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-seas").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("ship").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(ShipKind.values()).map(k -> k.id), b))
                        .executes(SeasCommands::ship)))
                .then(Commands.literal("site").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(siteNames(), b))
                        .executes(SeasCommands::site)))
                .then(Commands.literal("traffic").executes(SeasCommands::traffic)));
    }

    private static int ship(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ShipKind kind = ShipKind.byId(StringArgumentType.getString(c, "kind"));
        if (kind == null) {
            c.getSource().sendFailure(Component.literal("Unknown ship kind"));
            return 0;
        }
        ShipEntity ship = SeaTraffic.trySpawn(p.level(), p, kind);
        if (ship == null) {
            c.getSource().sendFailure(Component.literal("No open water 40-60 blocks around"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal(kind.id + " at " + ship.blockPosition().toShortString()), false);
        return 1;
    }

    private static int site(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel level = p.level();
        String name = StringArgumentType.getString(c, "kind").toLowerCase(Locale.ROOT);
        SiteKit k = new SiteKit(level, level.getRandom(), p.blockPosition());
        boolean ok;
        try {
            if (name.startsWith("sea_")) {
                ok = SeaSiteFeature.build(k, SeaSiteFeature.Kind.valueOf(name.substring(4).toUpperCase(Locale.ROOT)));
            } else if (name.startsWith("islet_")) {
                ok = IsletFeature.build(k, IsletFeature.Kind.valueOf(name.substring(6).toUpperCase(Locale.ROOT)));
            } else if (name.startsWith("coast_")) {
                Direction toSea = Direction.fromYRot(p.getYRot());
                ok = CoastSiteFeature.build(k, CoastSiteFeature.Kind.valueOf(name.substring(6).toUpperCase(Locale.ROOT)), toSea);
            } else {
                ok = false;
            }
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        boolean done = ok;
        c.getSource().sendSuccess(() -> Component.literal((done ? "Built " : "Could not build ") + name + " at " + k.cx + ", " + k.cz), false);
        return done ? 1 : 0;
    }

    private static int traffic(CommandContext<CommandSourceStack> c) {
        List<ShipEntity> list = SeaTraffic.traffic(c.getSource().getLevel());
        for (ShipEntity s : list) {
            String line = String.format(Locale.ROOT, "%s %s at %s", s.getDisplayName().getString(), s.getLivery(), s.blockPosition().toShortString());
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        c.getSource().sendSuccess(() -> Component.literal(list.size() + " ships of the traffic"), false);
        return list.size();
    }
}
