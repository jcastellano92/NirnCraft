package com.skycraft.loot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.skycraft.Skycraft;
import com.skycraft.core.Currency;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.economy.ItemValues;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * {@code /skyloot} (operators): sample the loot tables at any level, print their curves, give collectibles.
 * <pre>
 *  /skyloot sample &lt;table&gt; [level] [rolls]   one roll listed, then the averages of many
 *  /skyloot curve &lt;table&gt; [rolls]             averages at levels 1, 6, 12, 18, 27, 32, 46, 60
 *  /skyloot collections                       the caller's collection progress
 *  /skyloot unique &lt;id&gt;                       a legendary unique
 *  /skyloot collectible &lt;collection&gt; &lt;entry&gt;
 * </pre>
 * When {@code skycraft-loot-sample.request} exists in the server directory, the server writes the curve of every
 * {@code skycraft:containers/*} table to {@code skycraft-loot-sample.txt} on start (used by the smoke test).
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class LootCommand {
    private static final String REQUEST = "skycraft-loot-sample.request";
    private static final String OUTPUT = "skycraft-loot-sample.txt";
    public static final int[] LEVELS = {1, 6, 12, 18, 27, 32, 46, 60};

    private LootCommand() {}

    /** Averages of many rolls of one table at one level. */
    public record Stats(double value, double gold, double items, double enchanted, int topTier, String topItem) {
        String line(int level) {
            return String.format(Locale.ROOT, "L%-3d value %7.1f  gold %6.1f  items %4.1f  enchanted %4.2f  top tier L%d (%s)",
                    level, value, gold, items, enchanted, topTier, topItem);
        }
    }

    public static Stats stats(ServerLevel level, ResourceLocation table, BlockPos pos, int lootLevel, int rolls, ServerPlayer player) {
        double value = 0, gold = 0, items = 0, enchanted = 0;
        int top = 0;
        String topItem = "-";
        LootLevels.override(lootLevel);
        try {
            for (int i = 0; i < rolls; i++) {
                for (ItemStack s : LootApi.roll(level, table, pos, player)) {
                    long coins = Currency.valueOf(s);
                    if (coins > 0) gold += coins;
                    else value += ItemValues.getStack(s);
                    items++;
                    if (s.isEnchanted()) enchanted++;
                    int tier = LootTiers.unlock(s.getItem());
                    if (tier > top) {
                        top = tier;
                        topItem = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()));
                    }
                }
            }
        } finally {
            LootLevels.override(null);
        }
        double n = Math.max(1, rolls);
        return new Stats((value + gold) / n, gold / n, items / n, enchanted / n, top, topItem);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skyloot").requires(s -> s.hasPermission(2))
                .then(Commands.literal("sample").then(Commands.argument("table", ResourceLocationArgument.id())
                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(tables(c.getSource().getServer()), b))
                        .executes(c -> sample(c, -1, 200))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(c -> sample(c, IntegerArgumentType.getInteger(c, "level"), 200))
                                .then(Commands.argument("rolls", IntegerArgumentType.integer(1, 5000))
                                        .executes(c -> sample(c, IntegerArgumentType.getInteger(c, "level"), IntegerArgumentType.getInteger(c, "rolls")))))))
                .then(Commands.literal("curve").then(Commands.argument("table", ResourceLocationArgument.id())
                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(tables(c.getSource().getServer()), b))
                        .executes(c -> curve(c, 300))
                        .then(Commands.argument("rolls", IntegerArgumentType.integer(1, 5000)).executes(c -> curve(c, IntegerArgumentType.getInteger(c, "rolls"))))))
                .then(Commands.literal("collections").executes(LootCommand::collections))
                .then(Commands.literal("unique").then(Commands.argument("id", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(LegendaryUniques.all().stream().map(LegendaryUniques.Unique::id), b))
                        .executes(c -> give(c, LegendaryUniques.create(StringArgumentType.getString(c, "id"))))))
                .then(Commands.literal("collectible").then(Commands.argument("collection", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Collectibles.all().stream().map(x -> x.id), b))
                        .then(Commands.argument("entry", StringArgumentType.word()).executes(c -> {
                            Collectibles.Entry e = Collectibles.entry(StringArgumentType.getString(c, "collection") + "/" + StringArgumentType.getString(c, "entry"));
                            return give(c, e == null ? ItemStack.EMPTY : e.create());
                        })))));
        d.register(Commands.literal("skycraft-items").requires(s -> s.hasPermission(2))
                .then(Commands.literal("roll").then(Commands.argument("table", ResourceLocationArgument.id())
                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(tables(c.getSource().getServer()), b))
                        .executes(c -> rollItems(c, -1))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 100))
                                .executes(c -> rollItems(c, IntegerArgumentType.getInteger(c, "level"))))))
                .then(Commands.literal("selftest").executes(c -> {
                    List<String> problems = com.skycraft.loot.affix.ItemAffixes.selftest();
                    for (String p : problems) c.getSource().sendFailure(Component.literal("PROBLEM: " + p));
                    c.getSource().sendSuccess(() -> Component.literal(problems.isEmpty() ? "affix selftest OK" : "affix selftest FAILED: " + problems.size()), false);
                    return problems.isEmpty() ? 1 : 0;
                })));
    }

    private static int rollItems(CommandContext<CommandSourceStack> c, int level) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ResourceLocation table = ResourceLocationArgument.getId(c, "table");
        int lvl = level > 0 ? level : SkyData.get(player).getLevel();
        LootLevels.override(lvl);
        List<ItemStack> rolled;
        try {
            rolled = LootApi.roll(player.serverLevel(), table, player.blockPosition(), player);
        } finally {
            LootLevels.override(null);
        }
        c.getSource().sendSuccess(() -> Component.literal("Rolled " + table + " at L" + lvl + " (" + rolled.size() + " stacks):"), false);
        for (ItemStack s : rolled) {
            String affixSummary = com.skycraft.loot.affix.ItemAffixes.hasAffixes(s)
                    ? " [quality=" + com.skycraft.loot.affix.ItemAffixes.qualityLabelId(s)
                    + (com.skycraft.loot.affix.ItemAffixes.makerId(s) != null ? ", maker=" + com.skycraft.loot.affix.ItemAffixes.makerId(s) : "")
                    + (com.skycraft.loot.affix.ItemAffixes.storiedId(s) != null ? ", storied=" + com.skycraft.loot.affix.ItemAffixes.storiedId(s) : "")
                    + "]" : "";
            String line = "  " + s.getCount() + "x " + s.getHoverName().getString() + " (" + ItemValues.getStack(s) + "g)" + affixSummary;
            c.getSource().sendSuccess(() -> Component.literal(line), false);
            com.skycraft.inventory.Inv.giveOrDrop(player, s.copy());
        }
        return rolled.size();
    }

    private static List<ResourceLocation> tables(MinecraftServer server) {
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : new TreeSet<>(server.getLootData().getKeys(LootDataType.TABLE))) {
            if (id.getNamespace().equals(Skycraft.MODID) && (id.getPath().startsWith("containers/") || id.getPath().startsWith("uniques/"))) out.add(id);
        }
        return out;
    }

    private static int sample(CommandContext<CommandSourceStack> c, int level, int rolls) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ResourceLocation table = ResourceLocationArgument.getId(c, "table");
        int lvl = level > 0 ? level : SkyData.get(player).getLevel();
        LootLevels.override(lvl);
        List<ItemStack> one;
        try {
            one = LootApi.roll(player.serverLevel(), table, player.blockPosition(), player);
        } finally {
            LootLevels.override(null);
        }
        c.getSource().sendSuccess(() -> Component.literal(table + " at level " + lvl + ", one roll:"), false);
        for (ItemStack s : one) {
            String line = "  " + s.getCount() + "x " + s.getHoverName().getString() + " (" + ItemValues.getStack(s) + "g"
                    + (s.isEnchanted() ? ", enchanted" : "") + ")";
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        Stats st = stats(player.serverLevel(), table, player.blockPosition(), lvl, rolls, null);
        c.getSource().sendSuccess(() -> Component.literal("average of " + rolls + ": " + st.line(lvl)), false);
        return 1;
    }

    private static int curve(CommandContext<CommandSourceStack> c, int rolls) {
        ResourceLocation table = ResourceLocationArgument.getId(c, "table");
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPos.containing(c.getSource().getPosition());
        c.getSource().sendSuccess(() -> Component.literal(table + " (" + rolls + " rolls per level):"), false);
        for (int lvl : LEVELS) {
            Stats st = stats(level, table, pos, lvl, rolls, null);
            c.getSource().sendSuccess(() -> Component.literal(st.line(lvl)), false);
        }
        return 1;
    }

    private static int collections(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        PlayerData data = SkyData.get(player);
        for (Collectibles.Collection col : Collectibles.all()) {
            String line = col.name().getString() + ": " + Collectibles.progress(data, col) + "/" + col.entries.size()
                    + (Collectibles.rewarded(data, col.id) ? " (complete)" : "");
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> c, ItemStack stack) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (stack.isEmpty()) {
            c.getSource().sendFailure(Component.literal("Unknown collectible"));
            return 0;
        }
        com.skycraft.inventory.Pack.giveOrDrop(player, stack);
        return 1;
    }

    // ------------------------------------------------------------------ smoke test dump

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        File dir = server.getServerDirectory();
        if (!new File(dir, REQUEST).exists()) return;
        ServerLevel level = server.overworld();
        // Red Mountain: a region whose band (28-45) never caps the curve, so the levels show uncapped
        BlockPos pos = new BlockPos(7100, 100, -400);
        StringBuilder out = new StringBuilder();
        long start = System.currentTimeMillis();
        for (ResourceLocation table : tables(server)) {
            if (!table.getPath().startsWith("containers/")) continue;
            out.append(table).append('\n');
            for (int lvl : LEVELS) out.append("  ").append(stats(level, table, pos, lvl, 200, null).line(lvl)).append('\n');
        }
        try (Writer w = Files.newBufferedWriter(new File(dir, OUTPUT).toPath(), StandardCharsets.UTF_8)) {
            w.write(out.toString());
            Skycraft.LOGGER.info("Loot sample: wrote {} in {} ms", OUTPUT, System.currentTimeMillis() - start);
        } catch (Exception e) {
            Skycraft.LOGGER.error("Loot sample failed", e);
        }
    }
}
