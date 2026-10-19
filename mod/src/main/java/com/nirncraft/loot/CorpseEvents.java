package com.nirncraft.loot;

import com.nirncraft.NirnCraft;
import com.nirncraft.creatures.entity.CorpseEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Coordinates the death-fall transition between a fatal blow / kill-cam and the resting {@link CorpseEntity} loot body.
 * While {@code com.nirncraft.creatures.CorpseEvents} creates the lootable {@link CorpseEntity} immediately on death so
 * loot and server state are never lost, this handler tracks newly spawned corpses during their initial 20-tick death
 * fall (or active slow-motion kill-cam) so client rendering smoothly topples the dying creature over instead of
 * snapping instantaneously to a flat horizontal pose.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class CorpseEvents {
    /** Vanilla death fall duration in ticks. */
    public static final int DEATH_FALL_TICKS = 20;
    private static final Map<CorpseEntity, Long> FRESH_CORPSES = new WeakHashMap<>();

    private CorpseEvents() {}

    @SubscribeEvent
    public static void onCorpseSpawn(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof CorpseEntity corpse && corpse.tickCount <= 2 && !corpse.isPersistent()) {
            FRESH_CORPSES.put(corpse, event.getLevel().getGameTime());
        }
    }

    /**
     * Returns the smooth death-fall progress in {@code [0, 1]} for a {@link CorpseEntity}'s render dummy, where
     * {@code 0.0} is upright at the moment of the killing blow and {@code 1.0} is lying flat on the ground.
     */
    public static float getDeathFallProgress(LivingEntity dummy, float partialTick, boolean killCamActive, float killCamProgress) {
        if (dummy.level() instanceof net.minecraft.server.level.ServerLevel server263 && server263.getEntity(dummy.getUUID()) == dummy || dummy.level() == null) return 1.0f;
        if (killCamActive) {
            // During a slow-motion kill-cam, stretch the death fall across the first 75% of the camera orbit
            float t = Mth.clamp(killCamProgress / 0.75f, 0.0f, 1.0f);
            return t * t * (3.0f - 2.0f * t);
        }
        for (Map.Entry<CorpseEntity, Long> entry : FRESH_CORPSES.entrySet()) {
            CorpseEntity corpse = entry.getKey();
            if (corpse == null || corpse.isRemoved()) continue;
            Entity renderDummy = corpse.getRenderDummy();
            if (renderDummy == dummy) {
                float elapsed = (corpse.level().getGameTime() - entry.getValue()) + partialTick;
                if (elapsed >= DEATH_FALL_TICKS) return 1.0f;
                float t = Mth.clamp(elapsed / (float) DEATH_FALL_TICKS, 0.0f, 1.0f);
                return t * t * (3.0f - 2.0f * t);
            }
        }
        return 1.0f;
    }
}
