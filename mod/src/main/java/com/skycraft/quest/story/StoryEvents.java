package com.skycraft.quest.story;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus side of the story quests: scenes, essential quest NPCs (they are knocked down, never killed), removal of
 * stale NPC copies, and the shout hook called by the magic module.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class StoryEvents {
    private StoryEvents() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer() != null) Scenes.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        StoryData.ensureLoaded();
        Story.validateEntities();
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent event) {
        Scenes.reset();
    }

    /** Essential quest NPCs survive anything: they drop to a quarter of their health and stagger away. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || !QuestNpcs.isEssential(e)) return;
        if (e.getY() < e.level().getMinBuildHeight() - 32) return; // fell out of the world: let it go, it respawns
        event.setCanceled(true);
        e.setHealth(Math.max(1f, e.getMaxHealth() * 0.25f));
        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 2));
        e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
        if (e instanceof Mob mob) mob.setTarget(null);
        if (event.getSource().getEntity() instanceof ServerPlayer p) {
            Notifier.message(p, Component.translatable("story.skycraft.essential", e.getDisplayName()));
        }
    }

    /** A copy of a quest NPC that isn't the one on record (an old one loading from disk) never joins. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !(event.getLevel() instanceof ServerLevel level)) return;
        if (QuestNpcs.idOf(event.getEntity()).isEmpty()) return;
        if (QuestNpcs.isStaleCopy(level.getServer(), event.getEntity())) event.setCanceled(true);
    }

    /** Called by the magic module after a successful shout. */
    public static void onShout(ServerPlayer player, String shoutId, int words) {
        try {
            Story.onShout(player, shoutId);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Story shout hook failed", e);
        }
    }
}
