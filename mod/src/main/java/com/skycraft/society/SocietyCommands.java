package com.skycraft.society;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /skycraft-society npc <role> | encounter [kind] | squad [faction] | rep <player> [faction value]}
 * (operators only).
 */
final class SocietyCommands {
    private SocietyCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        List<String> roles = new ArrayList<>();
        for (NpcRole r : NpcRole.VALUES) roles.add(r.id);
        List<String> kinds = new ArrayList<>();
        for (Encounters.Kind k : Encounters.Kind.values()) kinds.add(k.id());
        List<String> factions = Arrays.asList(Reputation.FACTIONS);

        // the answers of a highway ambush (clickable chat options); anyone may use them, the server checks the state
        d.register(Commands.literal("skycraft-ambush")
                .then(Commands.argument("choice", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("pay", "persuade", "flee", "fight"), b))
                        .executes(c -> {
                            Ambush.choose(c.getSource().getPlayerOrException(), StringArgumentType.getString(c, "choice"));
                            return 1;
                        })));

        d.register(Commands.literal("skycraft-society").requires(s -> s.hasPermission(2))
                .then(Commands.literal("npc")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(roles, b))
                                .executes(SocietyCommands::npc)))
                // dev: how many people of which spawn path stand around a place (and a fake player visit to drive population)
                .then(Commands.literal("census")
                        .then(Commands.argument("place", StringArgumentType.word())
                                .executes(c -> census(c, 80))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 400))
                                        .executes(c -> census(c, IntegerArgumentType.getInteger(c, "radius"))))))
                .then(Commands.literal("visit")
                        .then(Commands.argument("place", StringArgumentType.word()).executes(SocietyCommands::visit)))
                // dev: a fake player at x z asks the road traffic for one group of a kind, then lists what road people are near
                .then(Commands.literal("traveller")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.asList(com.skycraft.roads.Travelers.KINDS), b))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer()).executes(c -> {
                                            var level = c.getSource().getLevel();
                                            var fake = net.minecraftforge.common.util.FakePlayerFactory.get(level,
                                                    new com.mojang.authlib.GameProfile(java.util.UUID.nameUUIDFromBytes("skycraft-society-census".getBytes()), "[Census]"));
                                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                                            fake.setPos(x + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
                                            String made = com.skycraft.roads.Travelers.devSpawn(level, fake, StringArgumentType.getString(c, "kind"));
                                            int n = 0;
                                            for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                                                    fake.getBoundingBox().inflate(150, 200, 150), m -> m.getPersistentData().contains(com.skycraft.roads.Travelers.TAG))) {
                                                String kind = e.getPersistentData().getCompound(com.skycraft.roads.Travelers.TAG).getString("kind");
                                                String line = "  " + kind + ": " + e.getType().builtInRegistryHolder().key().location().getPath()
                                                        + (e instanceof com.skycraft.society.entity.NpcEntity npc ? "/" + npc.role().id + "/" + npc.race().name().toLowerCase() : "")
                                                        + " '" + e.getDisplayName().getString() + "' at " + e.blockPosition().toShortString();
                                                c.getSource().sendSuccess(() -> Component.literal(line), false);
                                                n++;
                                            }
                                            final int count = n;
                                            c.getSource().sendSuccess(() -> Component.literal("spawned " + made + "; " + count + " road people near"), false);
                                            return made == null ? 0 : 1;
                                        })))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("place", StringArgumentType.word()).executes(c -> {
                            var place = com.skycraft.tamriel.Places.byId(StringArgumentType.getString(c, "place"));
                            if (place.isEmpty()) return 0;
                            int n = SocietyCensus.repopulate(c.getSource().getLevel(), place.get(), 140);
                            c.getSource().sendSuccess(() -> Component.literal("Removed " + n + " people; " + place.get().id()
                                    + " fills again on the next visit"), false);
                            return n;
                        })))
                // runs for about a minute of server time (the night needs the people to walk home); the report follows
                .then(Commands.literal("selftest").executes(c -> {
                    CommandSourceStack src = c.getSource();
                    boolean started = SocietySelfTest.start(src.getServer(), r -> {
                        for (String l : r.lines()) src.sendSuccess(() -> Component.literal(l), false);
                        src.sendSuccess(() -> Component.literal(r.summary()), false);
                        com.skycraft.Skycraft.LOGGER.info("{}", r.summary());
                    });
                    src.sendSuccess(() -> Component.literal(started ? "Society selftest started (about a minute)" : "Society selftest already running"), false);
                    return started ? 1 : 0;
                }).then(Commands.literal("trainer").executes(c -> {
                    CommandSourceStack src = c.getSource();
                    SocietySelfTest.Report r = SocietySelfTest.runTrainerSelfTest(src.getServer());
                    for (String l : r.lines()) src.sendSuccess(() -> Component.literal(l), false);
                    src.sendSuccess(() -> Component.literal(r.summary()), false);
                    com.skycraft.Skycraft.LOGGER.info("{}", r.summary());
                    return r.problems() == 0 ? 1 : 0;
                })))
                .then(Commands.literal("populate")
                        .then(Commands.argument("size", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("farm", "village", "town", "city"), b))
                                .executes(SocietyCommands::populate)))
                .then(Commands.literal("encounter")
                        .executes(c -> encounter(c, null))
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(kinds, b))
                                .executes(c -> encounter(c, StringArgumentType.getString(c, "kind")))))
                .then(Commands.literal("squad")
                        .executes(c -> squad(c, null))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(factions, b))
                                .executes(c -> squad(c, StringArgumentType.getString(c, "faction")))))
                .then(Commands.literal("rep")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(SocietyCommands::showRep)
                                .then(Commands.argument("faction", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(factions, b))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(Reputation.MIN, Reputation.MAX))
                                                .executes(SocietyCommands::setRep))))));
    }

    private static int census(CommandContext<CommandSourceStack> c, int radius) {
        String id = StringArgumentType.getString(c, "place");
        var place = com.skycraft.tamriel.Places.byId(id);
        if (place.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No place " + id));
            return 0;
        }
        List<String> lines = SocietyCensus.report(c.getSource().getLevel(), place.get(), radius);
        for (String l : lines) c.getSource().sendSuccess(() -> Component.literal(l), false);
        com.skycraft.Skycraft.LOGGER.info("{}", String.join(" | ", lines));
        return lines.size();
    }

    private static int visit(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "place");
        var place = com.skycraft.tamriel.Places.byId(id);
        if (place.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No place " + id));
            return 0;
        }
        String s = SocietyCensus.visit(c.getSource().getLevel(), place.get());
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static int npc(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String role = StringArgumentType.getString(c, "role");
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        Mob mob = Npcs.spawn(c.getSource().getLevel(), pos, role);
        if (mob == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.unknown", role));
            return 0;
        }
        // townsfolk spawned by command live where they were spawned (routines, bed claims)
        if (mob instanceof com.skycraft.society.entity.NpcEntity n && n.role().townsfolk()) n.setHome(pos, 24);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.society.npc", mob.getDisplayName()), true);
        return 1;
    }

    /** Gives the spot a settlement's whole roster (testing, or towns built by hand). */
    private static int populate(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "size");
        Settlers.Size size;
        try {
            size = Settlers.Size.valueOf(id.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.unknown", id));
            return 0;
        }
        int n = Settlers.populateHere(c.getSource().getLevel(), BlockPos.containing(c.getSource().getPosition()), size);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.society.populated", n), true);
        return n;
    }

    private static int encounter(CommandContext<CommandSourceStack> c, String kindId) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Encounters.Kind kind = kindId == null ? null : Encounters.Kind.byId(kindId);
        if (kindId != null && kind == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.unknown", kindId));
            return 0;
        }
        Encounters.Kind spawned = kind != null ? Encounters.trySpawn(player, kind) : Encounters.trySpawn(player, null);
        if (spawned == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.encounter_failed"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.society.encounter", spawned.id()), true);
        return 1;
    }

    private static int squad(CommandContext<CommandSourceStack> c, String faction) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (faction != null && HitSquads.roleFor(faction) == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.unknown", faction));
            return 0;
        }
        String sent = HitSquads.send(player, faction);
        if (sent == null) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.squad_failed"));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.society.squad", Reputation.factionName(sent)), true);
        return 1;
    }

    private static int showRep(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(c, "player");
        MutableComponent out = Component.translatable("command.skycraft.society.rep_header", player.getDisplayName());
        for (String f : Reputation.FACTIONS) {
            int v = Reputation.get(player, f);
            out.append("\n").append(Component.translatable("command.skycraft.society.rep_line", Reputation.factionName(f), v,
                    Reputation.Tier.of(v).displayName()));
        }
        c.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static int setRep(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(c, "player");
        String faction = StringArgumentType.getString(c, "faction");
        if (!Reputation.isFaction(faction)) {
            c.getSource().sendFailure(Component.translatable("command.skycraft.society.unknown", faction));
            return 0;
        }
        int value = IntegerArgumentType.getInteger(c, "value");
        Reputation.set(player, faction, value);
        c.getSource().sendSuccess(() -> Component.translatable("command.skycraft.society.rep_set", player.getDisplayName(),
                Reputation.factionName(faction), value), true);
        return 1;
    }
}
