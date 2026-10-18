package com.skycraft.dev;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, bounded navigation probe using the engine's real collision/path rules.
 * The villager is never added to the world. This proves a planned path exists,
 * not that a live NPC completed its schedule, opened a door or survived combat. */
final class DevRoute {
    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("route")
                .then(Commands.argument("tag", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("start", BlockPosArgument.blockPos())
                                .then(Commands.argument("end", BlockPosArgument.blockPos()).executes(c -> run(c.getSource(),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(c,"tag"),
                                        BlockPosArgument.getLoadedBlockPos(c,"start"), BlockPosArgument.getLoadedBlockPos(c,"end"))))));
    }

    private static int run(CommandSourceStack src, String tag, BlockPos start, BlockPos end) {
        if (!tag.matches("[A-Za-z0-9_-]{1,64}") || start.distSqr(end)>128*128) {
            src.sendFailure(Component.literal("Route requires a safe tag and endpoints within128 blocks.")); return 0;
        }
        var level=src.getLevel();
        int search=(int)Math.ceil(Math.sqrt(start.distSqr(end)))+24;
        // Never turn a read-only route check into synchronous chunk generation.
        for(int z=(start.getZ()-search-8)>>4;z<=(start.getZ()+search+8)>>4;z++)
            for(int x=(start.getX()-search-8)>>4;x<=(start.getX()+search+8)>>4;x++)
                if(level.getChunkSource().getChunkNow(x,z)==null) {
                    src.sendFailure(Component.literal("Route NOT_READY: load the full search square (radius "+(search+8)+") first.")); return 0;
                }
        var mob=EntityType.VILLAGER.create(level);
        if(mob==null) return 0;
        mob.setPos(start.getX()+0.5,start.getY(),start.getZ()+0.5); mob.setOnGround(true);
        mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(search);
        var nav=(GroundPathNavigation)mob.getNavigation();
        nav.setCanOpenDoors(true); nav.setCanPassDoors(true);
        nav.setMaxVisitedNodesMultiplier(4f);
        long began=System.nanoTime();
        var path=nav.createPath(end,0);
        double ms=(System.nanoTime()-began)/1_000_000.0;
        boolean clear=level.noCollision(mob,mob.getBoundingBox());
        boolean reached=clear && path!=null && path.canReach();
        List<List<Integer>> nodes=new ArrayList<>();
        if(path!=null) for(int i=0;i<path.getNodeCount();i++) {
            var n=path.getNode(i); nodes.add(List.of(n.x,n.y,n.z));
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("tag",tag); report.put("dimension",level.dimension().location().toString());
        report.put("seed",level.getSeed()); report.put("bakeInputs",com.skycraft.bake.Bake.inputsHash());
        report.put("start",List.of(start.getX(),start.getY(),start.getZ()));
        report.put("end",List.of(end.getX(),end.getY(),end.getZ()));
        report.put("startClear",clear); report.put("reached",reached); report.put("searchRadius",search);
        report.put("milliseconds",ms); report.put("nodes",nodes);
        report.put("limits","Engine villager path existence with door opening allowed; not a live movement/schedule/door-interaction test.");
        try {
            Path folder=Path.of(DevServer.DIR).resolve("routes"); Files.createDirectories(folder);
            Files.writeString(folder.resolve(tag+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch(java.io.IOException e) { src.sendFailure(Component.literal("Route evidence write failed: "+e)); return 0; }
        String result="ROUTE "+(reached?"PASS":"FAIL")+" "+tag+" nodes="+nodes.size()+" ms="+String.format(java.util.Locale.ROOT,"%.3f",ms);
        if(reached) src.sendSuccess(()->Component.literal(result),false); else src.sendFailure(Component.literal(result));
        return reached?1:0;
    }
}
