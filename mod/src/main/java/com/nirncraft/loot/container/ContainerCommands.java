package com.nirncraft.loot.container;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nirncraft.NirnCraft;
import com.nirncraft.inventory.ItemWeights;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /nirncraft-containers} (operators), docs/CONTAINERS.md section 12:
 * <pre>
 *  info                          the container you look at: kind, style, table, copies, your copy's roll and room
 *  place &lt;kind&gt; [style] [table]   a world loot container where you look (default table by kind)
 *  restock                       backdates your copy of the container you look at, so the next open restocks it
 *  convert [radius]              converts vanilla/Lootr loot containers in loaded chunks around you now
 *  selftest                      the container selftest
 * </pre>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class ContainerCommands {
    private ContainerCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("nirncraft-containers").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("info").executes(ContainerCommands::info))
                .then(Commands.literal("restock").executes(ContainerCommands::restock))
                .then(Commands.literal("selftest").executes(ContainerCommands::selftest))
                .then(Commands.literal("convert").executes(c -> convert(c, 4))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 32)).executes(c -> convert(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("place")
                        .then(Commands.argument("kind", StringArgumentType.word()).executes(c -> place(c, null, null))
                                .then(Commands.argument("style", StringArgumentType.word()).executes(c -> place(c, StringArgumentType.getString(c, "style"), null))
                                        .then(Commands.argument("table", StringArgumentType.greedyString())
                                                .executes(c -> place(c, StringArgumentType.getString(c, "style"), StringArgumentType.getString(c, "table"))))))));
    }

    private static void out(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    @Nullable
    private static BlockPos looked(ServerPlayer p) {
        HitResult hit = p.pick(6.0, 0f, false);
        return hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK ? bhr.getBlockPos() : null;
    }

    @Nullable
    private static LootContainerBlockEntity lookedAt(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) {
            out(c.getSource(), "Only a player can look at a container.");
            return null;
        }
        BlockPos pos = looked(p);
        if (pos == null || !(p.level().getBlockEntity(pos) instanceof LootContainerBlockEntity be)) {
            out(c.getSource(), "Look at a NirnCraft loot container.");
            return null;
        }
        return be;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        LootContainerBlockEntity be = lookedAt(c);
        if (be == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        out(c.getSource(), be.kind().id + " " + be.getBlockState() + " | table " + be.table() + " | world loot " + be.isWorldLoot()
                + " | restock " + be.restockDays() + " days | stash " + (be.stashKg() < 0 ? "unlimited" : be.stashKg() + " kg")
                + " | copies " + be.instances().size() + " | system " + ContainersConfig.system() + (ContainersConfig.nirncraftRuns() ? " (ours)" : " (vanilla/Lootr)"));
        LootInstance mine = be.existing(be.keyFor(p));
        if (mine == null) {
            out(c.getSource(), "Your copy: not rolled yet (rolled when you first open it).");
        } else {
            long age = LootContainerBlockEntity.clock(p.level()) - mine.rolledAt();
            out(c.getSource(), "Your copy: " + mine.contents().size() + " rows, " + ItemWeights.format((float) mine.weight(ItemWeights::get)) + " (roll "
                    + ItemWeights.format(mine.rolledKg()) + "), rolled " + (mine.rolled() ? String.format("%.1f days ago", age / (double) LootInstance.DAY) : "never")
                    + (mine.holdsForeign() ? ", holds your things (no restock)" : ""));
        }
        return 1;
    }

    private static int restock(CommandContext<CommandSourceStack> c) {
        LootContainerBlockEntity be = lookedAt(c);
        if (be == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        LootInstance mine = be.existing(be.keyFor(p));
        if (mine == null || !mine.rolled()) {
            out(c.getSource(), "Your copy is not rolled yet: just open it.");
            return 0;
        }
        mine.backdate(LootContainerBlockEntity.clock(p.level()) - (be.restockDays() + 1L) * LootInstance.DAY);
        be.setChanged();
        out(c.getSource(), be.restockDays() <= 0 ? "This container never restocks." : "Backdated: your next open restocks it (unless your own things are inside).");
        return 1;
    }

    private static int convert(CommandContext<CommandSourceStack> c, int radius) {
        if (!(c.getSource().getLevel() instanceof ServerLevel level)) return 0;
        int n = LootContainers.convertAround(level, BlockPos.containing(c.getSource().getPosition()), radius);
        out(c.getSource(), "Converted or normalised " + n + " loot containers within " + radius + " chunks.");
        return n;
    }

    private static int place(CommandContext<CommandSourceStack> c, @Nullable String styleId, @Nullable String tableId) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ContainerKind kind = ContainerKind.byId(StringArgumentType.getString(c, "kind"));
        if (kind == null) {
            out(c.getSource(), "Kinds: chest, grand_chest, strongbox, barrel, sack, urn, bookshelf, remains (or their block ids).");
            return 0;
        }
        ContainerStyle style = styleId == null ? null : ContainerStyle.byId(styleId);
        if (styleId != null && style == null) {
            out(c.getSource(), "Styles: nordic, imperial, velothi, dwemer, elven, desert, rustic.");
            return 0;
        }
        BlockPos hit = looked(p);
        BlockPos pos = hit == null ? p.blockPosition().relative(p.getDirection(), 2) : hit.above();
        Identifier table = Identifier.parse(tableId != null ? tableId : defaultTable(kind));
        LootContainerBlockEntity be = LootContainers.place(p.level(), pos, kind, p.getDirection().getOpposite(), table, style);
        out(c.getSource(), be == null ? "Could not place it there." : "Placed " + be.getBlockState() + " with " + table + " at " + pos.toShortString());
        return be == null ? 0 : 1;
    }

    static String defaultTable(ContainerKind kind) {
        return NirnCraft.MODID + ":containers/" + switch (kind) {
            case CHEST -> "dungeon/standard";
            case GRAND_CHEST -> "dungeon/boss";
            case STRONGBOX -> "strongbox/adept";
            case BARREL -> "misc/barrel";
            case SACK -> "misc/satchel";
            case URN -> "misc/urn";
            case BOOKSHELF -> "bookshelf/scholar";
            case REMAINS -> "corpse/bandit";
        };
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        ContainerSelfTest.Report r = ContainerSelfTest.run(c.getSource().getServer());
        for (String line : r.lines()) {
            out(c.getSource(), line);
            NirnCraft.LOGGER.info("[containers] {}", line);
        }
        out(c.getSource(), r.summary());
        NirnCraft.LOGGER.info("{}", r.summary());
        return r.total() == r.passed() ? 1 : 0;
    }
}
