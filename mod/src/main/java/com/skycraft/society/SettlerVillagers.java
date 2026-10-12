package com.skycraft.society;

import com.skycraft.Skycraft;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Settlement shopkeepers and farmers are vanilla villagers (economy contract); they never had a bed, so they stood
 * outside all night. Every ~2 s each settler villager without a HOME memory is given a recorded household bed of its
 * settlement (read from the settlement anchors); the vanilla schedule then walks it home and into bed.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SettlerVillagers {
    private SettlerVillagers() {}

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager v) || v.level().isClientSide) return;
        if ((v.tickCount + v.getId()) % 40 != 0 || !v.getPersistentData().getBoolean("skycraft_settler")) return;
        if (v.isSleeping() || v.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)) return;
        try {
            Settlers.homeForVillager(v);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.debug("Settler villager bed lookup failed", e);
        }
    }
}
