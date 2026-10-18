package com.skycraft.combat;

import com.skycraft.Skycraft;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Uses public reduction callbacks to keep stamina/XP/effects off hits rejected by damage cooldowns. */
@EventBusSubscriber(modid = Skycraft.MODID)
public final class DamageStages {
    private DamageStages() {}
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncoming(LivingIncomingDamageEvent incoming) {
        if (incoming.getEntity().level().isClientSide()) return;
        incoming.addReductionModifier(DamageContainer.Reduction.ARMOR, (container, nativeReduction) -> {
            float before = container.getNewDamage();
            BeforeArmorDamageEvent accepted = NeoForge.EVENT_BUS.post(new BeforeArmorDamageEvent(incoming.getEntity(), incoming.getSource(), before));
            float after = accepted.isCanceled() ? 0 : accepted.getAmount();
            if (after == before) return nativeReduction;
            container.setNewDamage(after);
            if (after == 0 || incoming.getSource().is(DamageTypeTags.BYPASSES_ARMOR)) return 0;
            var victim = incoming.getEntity();
            // Pure health math: never call getDamageAfterArmorAbsorb, which would damage equipment a second time.
            // Native durability has already been charged using the original amount; this supported API cannot
            // retroactively change that charge to the modified amount as the legacy before-armor event did.
            float absorbed = CombatRules.getDamageAfterAbsorb(victim, after, incoming.getSource(), victim.getArmorValue(),
                    (float) victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
            return after - absorbed;
        });
    }
}
