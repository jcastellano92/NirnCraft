package com.nirncraft.fire.aftermath;

import com.nirncraft.NirnCraft;
import com.nirncraft.fire.FireSounds;
import com.nirncraft.network.SkyNetwork;
import com.nirncraft.society.SocietyPackets;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;

import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.LogicalSide;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The slow life of the aftermath scenes, near players only: short spoken lines when somebody walks close, the soft
 * sobbing of the weeping (one sound every few seconds per person, positional, quiet), swinging shovels, buckets and
 * ladles, the keening of the mourners, and guards keeping to their post. Every sound and line has a cooldown per
 * person; nothing runs further than 48 blocks from a player.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class AftermathLife {
    private static final Map<UUID, Long> NEXT_LINE = new HashMap<>();
    private static final Map<UUID, Long> NEXT_SOB = new HashMap<>();
    private static final Map<UUID, Long> NEXT_SWING = new HashMap<>();
    private static long nextKeen;

    private AftermathLife() {}

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        NEXT_LINE.clear();
        NEXT_SOB.clear();
        NEXT_SWING.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.LevelTickEvent.Post)) return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long t = level.getGameTime();
        if (t % 10 != 3) return;
        if (NEXT_LINE.size() > 4000) {
            NEXT_LINE.clear();
            NEXT_SOB.clear();
            NEXT_SWING.clear();
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            var box = p.getBoundingBox().inflate(48, 24, 48);
            for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, box, n -> n.getPersistentData().contains(Tableau.KEY))) {
                Tableau.Pose pose = Tableau.poseOf(npc);
                if (pose == null) continue;
                actor(level, p, npc, pose, t);
            }
        }
    }

    private static void actor(ServerLevel level, ServerPlayer p, NpcEntity npc, Tableau.Pose pose, long t) {
        double d2 = p.distanceToSqr(npc);
        UUID id = npc.getUUID();
        // swing tools and buckets in a rhythm of their own (staggered by identity)
        if (pose.swingTicks > 0 && d2 < 40 * 40 && npc.isAlive()) {
            Long next = NEXT_SWING.get(id);
            if (next == null) next = t + Math.floorMod(id.getLeastSignificantBits(), pose.swingTicks);
            if (t >= next) {
                npc.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                next = t + pose.swingTicks;
            }
            NEXT_SWING.put(id, next);
        }
        // sobbing: soft, positional, rare
        if (pose.sobs && d2 < 22 * 22 && npc.isAlive()) {
            Long next = NEXT_SOB.get(id);
            if (next == null) next = t + Math.floorMod(id.getMostSignificantBits(), 120L);
            if (t >= next) {
                level.playSound(null, npc.getX(), npc.getY() + 0.6, npc.getZ(), FireSounds.SOBBING.get(), SoundSource.NEUTRAL,
                        0.32f, 0.88f + level.getRandom().nextFloat() * 0.25f);
                next = t + 100 + level.getRandom().nextInt(180);
            }
            NEXT_SOB.put(id, next);
        }
        // mourners keen now and then (a long, low sound; at most one in the area every ~two minutes)
        if (pose == Tableau.Pose.MOURN && d2 < 30 * 30 && t >= nextKeen) {
            nextKeen = t + 20 * (75 + level.getRandom().nextInt(90));
            level.playSound(null, npc.getX(), npc.getY() + 1.0, npc.getZ(), FireSounds.MOURNING.get(), SoundSource.NEUTRAL, 0.4f,
                    0.95f + level.getRandom().nextFloat() * 0.1f);
        }
        // a line for somebody who comes close and looks
        if (!pose.lines.isEmpty() && d2 < 6 * 6 && npc.isAlive()) {
            Long next = NEXT_LINE.get(id);
            if (next == null || t >= next) {
                int n = Tableau.lineCount(pose.lines);
                if (n > 0 && level.getRandom().nextInt(3) == 0) {
                    int k = 1 + level.getRandom().nextInt(n);
                    SkyNetwork.sendToPlayer(p, new SocietyPackets.Bark(npc.getId(),
                            Component.translatable("fire.nirncraft.line." + pose.lines + "." + k), 110));
                    NEXT_LINE.put(id, t + 500 + level.getRandom().nextInt(700));
                } else {
                    NEXT_LINE.put(id, t + 100);
                }
            }
        }
        // guards keep to their post (the restriction is not saved)
        if (pose == Tableau.Pose.GUARD && !npc.hasHome() && npc.getPersistentData().contains(Tableau.AT_KEY)) {
            npc.setHomeTo(net.minecraft.core.BlockPos.of(npc.getPersistentData().getLongOr(Tableau.AT_KEY, 0L)), 5);
        }
        // the frozen keep their AI off across reloads and conversions
        if (pose.frozen && !npc.isNoAi()) npc.setNoAi(true);
    }
}
