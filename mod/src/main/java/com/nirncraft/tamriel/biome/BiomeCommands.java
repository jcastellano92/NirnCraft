package com.nirncraft.tamriel.biome;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nirncraft.NirnCraft;
import com.nirncraft.tamriel.TamrielBiomeSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * {@code /skybiome [pos]} (operators): what the Tamriel biome source picks at a position, with the region, palette,
 * terrain context and map sample behind the choice. {@code /skybiome census <radius> <palette>} counts the surface
 * biomes a palette would give around you (no chunks are generated).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class BiomeCommands {
    private BiomeCommands() {}

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("skybiome").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(c -> describe(c, BlockPos.containing(c.getSource().getPosition())))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> describe(c, BlockPosArgument.getBlockPos(c, "pos"))))
                .then(Commands.literal("census").then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192))
                        .then(Commands.argument("palette", StringArgumentType.greedyString())
                                .executes(c -> census(c, StringArgumentType.getString(c, "palette"),
                                        IntegerArgumentType.getInteger(c, "radius")))))));
    }

    /** {@code /skybiome census <radius> <palette>}: which surface biomes a palette yields around you. */
    private static int census(CommandContext<CommandSourceStack> c, String palette, int radius) {
        ServerLevel level = c.getSource().getLevel();
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof TamrielBiomeSource source)) return 0;
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        var counts = source.census(palette, at.getX(), at.getZ(), radius, Math.max(8, radius / 40),
                level.getChunkSource().randomState().createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED));
        c.getSource().sendSuccess(() -> Component.literal("census " + palette + " r" + radius + ": " + counts), false);
        return counts.size();
    }

    private static int describe(CommandContext<CommandSourceStack> c, BlockPos pos) {
        ServerLevel level = c.getSource().getLevel();
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof TamrielBiomeSource source)) {
            c.getSource().sendFailure(Component.literal("This dimension doesn't use the Tamriel biome source."));
            return 0;
        }
        String text = source.describe(pos.getX(), pos.getY(), pos.getZ(), level.getChunkSource().randomState().createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED));
        c.getSource().sendSuccess(() -> Component.literal(pos.toShortString() + ": " + text), false);
        return 1;
    }
}
