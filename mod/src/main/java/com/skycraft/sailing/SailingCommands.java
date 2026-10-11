package com.skycraft.sailing;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;

/**
 * {@code /skycraft-sailing ports | build <port> | tp <port> | voyage <port> | ship <type> | wind} (operators only).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SailingCommands {
    private SailingCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-sailing").requires(s -> s.hasPermission(2))
                .then(Commands.literal("ports").executes(SailingCommands::ports))
                .then(Commands.literal("build").then(port().executes(c -> build(c, false))))
                .then(Commands.literal("tp").then(port().executes(c -> build(c, true))))
                .then(Commands.literal("voyage").then(port().executes(SailingCommands::voyage)))
                .then(Commands.literal("ship").then(Commands.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(ShipType.VALUES).map(t -> t.id), b))
                        .executes(SailingCommands::ship)))
                .then(Commands.literal("probe").then(port().executes(SailingCommands::probe)))
                .then(Commands.literal("wreck").executes(SailingCommands::wreck))
                .then(Commands.literal("wind").executes(SailingCommands::wind)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> port() {
        return Commands.argument("port", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(Ports.all().stream().map(Port::id), b));
    }

    private static int ports(CommandContext<CommandSourceStack> c) {
        SailingData data = SailingData.get(c.getSource().getServer());
        for (Port p : Ports.all()) {
            PortSites.Site s = PortSites.of(p);
            SailingData.Dock dock = data.dock(p.id());
            String state = dock == null ? "not built" : (dock.adopted ? "adopted " : "pier ") + dock.arrival.toShortString();
            String line = String.format(Locale.ROOT, "%s: water %d,%d shore %d,%d (%s) - %s, %d routes", p.id(), s.waterX(), s.waterZ(),
                    s.shoreX(), s.shoreZ(), s.toLand().getName(), state, Ports.routesFrom(p.id()).size());
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        for (Route r : Ports.routes()) {
            String line = String.format(Locale.ROOT, "  %s - %s: %.0f blocks, %d Septims, %d h", r.a(), r.b(), SeaRoutes.length(r),
                    Ferries.fare(r), Ferries.hours(r));
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Ports.all().size();
    }

    private static Port portArg(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "port");
        return Ports.byId(id).orElse(null);
    }

    /** Loads the port's chunks right away, builds its pier and optionally teleports there. */
    private static int build(CommandContext<CommandSourceStack> c, boolean teleport) throws CommandSyntaxException {
        Port port = portArg(c);
        if (port == null) {
            c.getSource().sendFailure(Component.literal("Unknown port"));
            return 0;
        }
        ServerLevel level = c.getSource().getServer().overworld();
        int[] fp = DockBuilder.footprint(PortSites.of(port));
        for (int cx = fp[0] >> 4; cx <= fp[2] >> 4; cx++) {
            for (int cz = fp[1] >> 4; cz <= fp[3] >> 4; cz++) level.getChunk(cx, cz);
        }
        SailingData.Dock dock = Harbors.ensure(level, port);
        if (dock == null) {
            c.getSource().sendFailure(Component.literal("Could not build the pier of " + port.id()));
            return 0;
        }
        String msg = port.id() + ": " + (dock.adopted ? "adopted dock at " : "pier at ") + dock.arrival.toShortString();
        c.getSource().sendSuccess(() -> Component.literal(msg), true);
        if (teleport) {
            ServerPlayer p = c.getSource().getPlayerOrException();
            p.teleportTo(level, dock.arrival.getX() + 0.5, dock.arrival.getY(), dock.arrival.getZ() + 0.5,
                    DockBuilder.yawOf(PortSites.of(port).toLand().getOpposite()), 0f);
        }
        return 1;
    }

    private static int voyage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Port dest = portArg(c);
        ServerPlayer p = c.getSource().getPlayerOrException();
        if (dest == null) {
            c.getSource().sendFailure(Component.literal("Unknown port"));
            return 0;
        }
        Port from = Harbors.portNear(p.getBlockX(), p.getBlockZ(), 100000).orElse(dest);
        Route route = Ports.routesFrom(from.id()).stream().filter(r -> r.touches(dest.id())).findFirst()
                .orElse(new Route(from.id(), dest.id(), null, dest.isLanding(), -1));
        Ferries.start(p, from, dest, route);
        return 1;
    }

    /** Puts a ship of the given type on the nearest open water in front of the player. */
    private static int ship(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ShipType type = ShipType.byId(StringArgumentType.getString(c, "type"));
        ServerLevel level = p.serverLevel();
        Vec3 look = Vec3.directionFromRotation(0, p.getYRot());
        for (int dist = 4; dist <= 40; dist++) {
            for (int lat = 0; lat <= 12; lat++) {
                for (int sgn : new int[]{1, -1}) {
                    double x = p.getX() + look.x * dist - look.z * lat * sgn, z = p.getZ() + look.z * dist + look.x * lat * sgn;
                    BlockPos col = BlockPos.containing(x, 62, z);
                    if (!level.getFluidState(col).is(FluidTags.WATER) || !level.getBlockState(col.above()).isAir()) continue;
                    ShipEntity ship = SailingRegistry.type(type).create(level);
                    if (ship == null) return 0;
                    ship.moveTo(x, 62 + 0.889 - type.draft, z, p.getYRot(), 0f);
                    if (!level.noCollision(ship, ship.getBoundingBox().deflate(0.05)) || !ShipItem.roomFor(level, ship)) continue;
                    ship.initNew(p);
                    level.addFreshEntity(ship);
                    c.getSource().sendSuccess(() -> Component.literal("Launched a " + type.id), false);
                    return 1;
                }
            }
        }
        c.getSource().sendFailure(Component.literal("No open water nearby"));
        return 0;
    }

    /** Debug: the real columns along a port's site line (W = water at sea level, digits = ground height - 60). */
    private static int probe(CommandContext<CommandSourceStack> c) {
        Port port = portArg(c);
        if (port == null) return 0;
        ServerLevel level = c.getSource().getServer().overworld();
        PortSites.Site s = PortSites.of(port);
        StringBuilder sb = new StringBuilder(port.id() + " water " + s.waterX() + "," + s.waterZ() + " toLand " + s.toLand() + ": ");
        for (int t = -40; t <= 80; t += 4) {
            int x = s.waterX() + s.toLand().getStepX() * t, z = s.waterZ() + s.toLand().getStepZ() * t;
            level.getChunk(x >> 4, z >> 4);
            boolean w = level.getFluidState(new BlockPos(x, 62, z)).is(FluidTags.WATER);
            int g = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
            sb.append(w ? "W" : "").append(g - 60).append(' ');
        }
        String msg = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    /** Debug: places a sea wreck and flotsam on the nearest open water deep enough, and shows the player the spot. */
    private static int wreck(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel level = p.serverLevel();
        for (int r = 12; r <= 160; r += 4) {
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI / 12;
                int x = p.getBlockX() + (int) Math.round(Math.cos(a) * r), z = p.getBlockZ() + (int) Math.round(Math.sin(a) * r);
                BlockPos top = BlockPos.containing(x, 62, z);
                if (!level.getFluidState(top).is(FluidTags.WATER)) continue;
                int floor = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z);
                if (floor > 57) continue;
                BlockPos at = new BlockPos(x, floor, z);
                var random = level.getRandom();
                var gen = level.getChunkSource().getGenerator();
                boolean wreck = SailingRegistry.SEA_WRECK.get().place(net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE,
                        level, gen, random, at);
                boolean flotsam = SailingRegistry.FLOTSAM.get().place(net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE,
                        level, gen, random, new BlockPos(x + 6, 63, z + 6));
                double vx = x - Math.cos(a) * 9, vz = z - Math.sin(a) * 9;
                float yaw = (float) Math.toDegrees(Math.atan2(-(x - vx), z - vz));
                p.teleportTo(level, vx, 70, vz, yaw, 45f);
                String msg = "Wreck " + (wreck ? "placed" : "failed") + ", flotsam " + (flotsam ? "placed" : "failed") + " at " + x + " " + floor + " " + z;
                c.getSource().sendSuccess(() -> Component.literal(msg), false);
                return 1;
            }
        }
        c.getSource().sendFailure(Component.literal("No deep water nearby"));
        return 0;
    }

    private static int wind(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        float[] w = SeaWind.at(p.serverLevel(), p.getX(), p.getZ());
        String msg = String.format(Locale.ROOT, "Wind towards %.0f deg, strength %.2f", Mth.wrapDegrees(w[0]), w[1]);
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
