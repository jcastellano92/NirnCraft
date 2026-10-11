package com.skycraft.lore.place;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.quest.Quests;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /skylore} (operators): check and hand out place lore.
 * <pre>
 *  /skylore stats                    coverage: places with lore, readables, mini-quests, regions
 *  /skylore show &lt;place&gt;             the place's hook, rumour, notes and quest
 *  /skylore give &lt;place&gt; [note]      the note items (all of the place's without a note id)
 *  /skylore read &lt;place&gt; [note]      opens a note as if read (runs its reveal and quest start)
 *  /skylore lectern &lt;place&gt;           places the place's lectern now (if the player stands near its centre)
 * </pre>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class PlaceLoreCommands {
    private PlaceLoreCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("skylore").requires(s -> s.hasPermission(2))
                .then(Commands.literal("stats").executes(PlaceLoreCommands::stats))
                .then(Commands.literal("show").then(place().executes(PlaceLoreCommands::show)))
                .then(Commands.literal("give").then(place().executes(c -> give(c, ""))
                        .then(Commands.argument("note", StringArgumentType.word()).executes(c -> give(c, StringArgumentType.getString(c, "note"))))))
                .then(Commands.literal("read").then(place().executes(c -> read(c, ""))
                        .then(Commands.argument("note", StringArgumentType.word()).executes(c -> read(c, StringArgumentType.getString(c, "note"))))))
                .then(Commands.literal("lectern").then(place().executes(PlaceLoreCommands::lectern))));
    }

    /**
     * Smoke test: when {@code skycraft-lore-selftest.request} exists in the server directory, the server runs
     * {@code /skylore stats} and both story selftests (their results go to the log) once started, then stops.
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        java.io.File request = new java.io.File("skycraft-lore-selftest.request");
        if (!request.exists()) return;
        net.minecraft.server.MinecraftServer server = event.getServer();
        CommandSourceStack src = server.createCommandSourceStack();
        for (String cmd : List.of("skylore stats", "skycraft-quest selftest", "skycraft-quest selftest flow")) {
            Skycraft.LOGGER.info("Lore selftest: /{}", cmd);
            server.getCommands().performPrefixedCommand(src, cmd);
        }
        if (!request.delete()) Skycraft.LOGGER.warn("Couldn't delete {}", request);
        server.halt(false);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> place() {
        return Commands.argument("place", StringArgumentType.word()).suggests((c, b) -> {
            List<String> ids = new ArrayList<>();
            for (PlaceLore.Content x : PlaceLore.all()) ids.add(x.placeId());
            return SharedSuggestionProvider.suggest(ids, b);
        });
    }

    private static PlaceLore.Content content(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "place");
        PlaceLore.Content x = PlaceLore.contentFor(id).orElse(null);
        if (x == null) c.getSource().sendFailure(Component.literal("No lore for place " + id));
        return x;
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        int places = 0, notes = 0, quests = 0;
        for (PlaceLore.Content x : PlaceLore.all()) {
            places++;
            notes += x.notes().size();
            boolean q = !x.quest().isEmpty();
            for (PlaceLore.Note n : x.notes()) q |= !n.starts().isEmpty();
            if (q) quests++;
        }
        int total = Places.all().size();
        int p = places, n = notes, q = quests;
        c.getSource().sendSuccess(() -> Component.literal("Place lore: " + p + "/" + total + " places, " + n + " readables, "
                + q + " places with a mini-quest"), false);
        return p;
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        PlaceLore.Content x = content(c);
        if (x == null) return 0;
        c.getSource().sendSuccess(() -> x.hook().c().withStyle(ChatFormatting.GOLD), false);
        c.getSource().sendSuccess(() -> x.rumour().c().withStyle(ChatFormatting.GRAY), false);
        for (PlaceLore.Note n : x.notes()) {
            c.getSource().sendSuccess(() -> Component.literal(" - " + n.id() + " (" + n.kind() + ", " + n.at() + ") ").append(n.title().c())
                    .append(n.starts().isEmpty() ? "" : " starts " + n.starts()).append(n.reveals().isEmpty() ? "" : " reveals " + n.reveals()), false);
        }
        if (!x.quest().isEmpty()) c.getSource().sendSuccess(() -> Component.literal(" quest: " + x.quest()), false);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> c, String noteId) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        PlaceLore.Content x = content(c);
        if (x == null) return 0;
        int n = 0;
        for (PlaceLore.Note note : x.notes()) {
            if (!noteId.isEmpty() && !note.id().equals(noteId)) continue;
            Quests.give(p, LoreBookItem.create(note.book()));
            n++;
        }
        return n;
    }

    private static int read(CommandContext<CommandSourceStack> c, String noteId) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        PlaceLore.Content x = content(c);
        if (x == null) return 0;
        for (PlaceLore.Note note : x.notes()) {
            if (!noteId.isEmpty() && !note.id().equals(noteId)) continue;
            LoreBookItem.read(p, note.book());
            return 1;
        }
        c.getSource().sendFailure(Component.literal("No such note"));
        return 0;
    }

    private static int lectern(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        PlaceLore.Content x = content(c);
        Place place = x == null ? null : Places.byId(x.placeId()).orElse(null);
        if (place == null) return 0;
        List<PlaceLore.Note> notes = PlaceLorePlacer.lecternNotes(x);
        PlaceLore.Note note = notes.isEmpty() ? x.notes().get(0) : notes.get(0);
        boolean ok = PlaceLorePlacer.placeLectern(p.serverLevel(), place, note);
        c.getSource().sendSuccess(() -> Component.literal(ok ? "Lectern placed" : "No free loaded spot near the place's centre"), false);
        return ok ? 1 : 0;
    }
}
