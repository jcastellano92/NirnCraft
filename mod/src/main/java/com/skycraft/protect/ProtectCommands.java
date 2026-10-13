package com.skycraft.protect;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.skycraft.Skycraft;
import com.skycraft.homestead.HomesteadClaim;
import com.skycraft.homestead.HomesteadData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /skycraft-protect} (operators): {@code status}, {@code bypass} (toggle for yourself), {@code check [x y z]}
 * (what the rules say about a block, and why) and {@code selftest}.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ProtectCommands {
    private ProtectCommands() {}

    @SubscribeEvent
    public static void registerProtectCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skycraft-protect").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(ProtectCommands::status))
                .then(Commands.literal("bypass").executes(ProtectCommands::bypass))
                .then(Commands.literal("check").executes(c -> check(c, null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> check(c, BlockPosArgument.getLoadedBlockPos(c, "pos")))))
                .then(Commands.literal("selftest").executes(ProtectCommands::selftest)));
    }

    private static void out(CommandSourceStack src, String line) {
        src.sendSuccess(() -> Component.literal(line), false);
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        out(src, "World protection: " + (Protect.enabled() ? "ENABLED" : "disabled") + " (terrain digging: " + ProtectConfig.terrainMode()
                + ", homestead digging free: " + ProtectConfig.HOMESTEAD_FREE_DIGGING.get() + ", ores: " + ProtectConfig.ORE_MINING.get()
                + ", trees: " + ProtectConfig.TREE_CHOPPING.get() + ")");
        out(src, "Griefing off: " + ProtectConfig.GRIEFING_OFF.get() + ", fire: " + ProtectConfig.FIRE_SPREAD_OFF.get()
                + ", fluids: " + ProtectConfig.FLUIDS_OFF.get() + ", tools: " + ProtectConfig.TOOL_ACTIONS_OFF.get()
                + ", decor entities: " + ProtectConfig.PROTECT_ENTITIES.get());
        out(src, "Homesteads: " + HomesteadData.get(src.getServer()).allClaims().size() + " claims; " + Footprints.stats());
        if (src.getEntity() instanceof ServerPlayer p) {
            out(src, "You: bypass " + (Protect.hasBypass(p.getUUID()) ? "ON" : "off")
                    + (p.isCreative() && ProtectConfig.CREATIVE_BYPASS.get() ? " (creative mode bypasses too)" : ""));
        }
        return 1;
    }

    private static int bypass(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        boolean on = Protect.toggleBypass(p.getUUID());
        c.getSource().sendSuccess(() -> Component.literal("World protection bypass " + (on ? "ON: you can break and place anywhere"
                : "off: the rules apply to you again")).withStyle(on ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);
        return 1;
    }

    private static int check(CommandContext<CommandSourceStack> c, BlockPos given) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = src.getEntity() instanceof ServerPlayer p ? p : null;
        BlockPos pos = given != null ? given : player != null ? player.blockPosition().below() : BlockPos.ZERO;
        if (!level.isLoaded(pos)) {
            out(src, "That position is not loaded.");
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        out(src, "Block " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        HomesteadClaim claim = Protect.claimAt(level, pos);
        out(src, claim == null ? "Homestead: none" : "Homestead: " + claim.getOwnerName() + "'s"
                + (player != null ? (claim.canBuild(player) ? " (you may build)" : " (you may not build)") : ""));
        out(src, "Footprint: " + Footprints.at(level, pos) + (Footprints.inStructure(level, pos, 1) ? " (inside a structure piece)" : ""));
        out(src, "Class: " + (Protect.isAmbientPlant(state) ? "plant" : state.is(net.minecraftforge.common.Tags.Blocks.ORES) ? "ore"
                : Protect.isTree(state) ? "tree" : Protect.isTerrain(state) ? "terrain" : "built/other")
                + (com.skycraft.dig.PlacedBlocks.isPlayerPlaced(level, pos) ? ", player-placed" : ""));
        Protect.Decision b = Protect.checkBreak(player, level, pos, state);
        Protect.Decision pl = Protect.checkPlace(player, level, pos, state);
        out(src, "Break: " + (b.allowed() ? "ALLOWED" : "DENIED") + " (" + b.reason() + ")");
        out(src, "Place this block: " + (pl.allowed() ? "ALLOWED" : "DENIED") + " (" + pl.reason() + ")");
        return b.allowed() ? 1 : 0;
    }

    private static int selftest(CommandContext<CommandSourceStack> c) {
        ProtectSelfTest.Report r = ProtectSelfTest.run(c.getSource().getServer());
        for (String line : r.lines()) out(c.getSource(), line);
        out(c.getSource(), r.summary());
        return r.problems() == 0 ? 1 : 0;
    }
}
