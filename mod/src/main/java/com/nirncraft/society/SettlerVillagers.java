package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

/**
 * Settlement shopkeepers and farmers are vanilla villagers (economy contract). Every ~2 s each settler villager:
 * <ul>
 *     <li>without a HOME memory is given a recorded household bed of its settlement (the housing model hands out
 *     specific beds at spawn; this is the fallback for villagers from before it), so the vanilla schedule sends it to
 *     bed at night;</li>
 *     <li>with a workplace ({@link Housing#WORK_KEY}, the counter/forge/field anchor it was spawned at) goes back to
 *     it for working hours, takes lunch and the evening at its own spot around a meeting point
 *     ({@link Workplaces#leisureFor}), instead of strolling off down the street.</li>
 * </ul>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class SettlerVillagers {
    private SettlerVillagers() {}

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager v) || v.level().isClientSide()) return;
        if ((v.tickCount + v.getId()) % 40 != 0 || !v.getPersistentData().getBooleanOr("nirncraft_settler",false)) return;
        if (v.isSleeping()) return;
        try {
            if (!v.getBrain().hasMemoryValue(MemoryModuleType.HOME)) Settlers.homeForVillager(v);
            keepHours(v);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.debug("Settler villager routine failed", e);
        }
    }

    /** Sends the villager back to its post (or its plaza spot) when it has drifted away at the wrong hour. */
    private static void keepHours(Villager v) {
        if (!(v.level() instanceof ServerLevel level) || v.getTradingPlayer() != null || v.isBaby()) return;
        CompoundTag pd = v.getPersistentData();
        if (!pd.contains(Housing.WORK_KEY)) return;
        int h = Schedule.hour(level);
        BlockPos post = BlockPos.of(pd.getLongOr(Housing.WORK_KEY, 0L));
        BlockPos target;
        int within;
        if ((h >= 8 && h < 12) || (h >= 13 && h < 20)) {
            target = post;
            within = 5;
        } else if (h >= 12 && h < 13 || h >= 20 && h < 22) {
            target = Workplaces.leisureFor(level, v, post);
            within = 5;
        } else {
            return; // night and early morning: the vanilla schedule (bed, breakfast)
        }
        if (target == null || !level.isLoaded(target)) return;
        if (v.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) <= within * within
                && Math.abs(v.getY() - target.getY()) < 4) return;
        if (v.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET) && !v.getNavigation().isDone()) return;
        v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, 0.5F, 2));
    }
}
