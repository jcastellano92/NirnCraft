package com.skycraft.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

/** An accepted hit, after blocking/cooldown rejection and before armor/magic health reductions. */
public final class BeforeArmorDamageEvent extends LivingEvent implements ICancellableEvent {
    private final DamageSource source;
    private final float originalAmount;
    private float amount;
    public BeforeArmorDamageEvent(LivingEntity entity, DamageSource source, float amount) {
        super(entity); this.source = source; this.originalAmount = amount; this.amount = amount;
    }
    public DamageSource getSource() { return source; }
    public float getAmount() { return amount; }
    public float getOriginalAmount() { return originalAmount; }
    public void setAmount(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite combat damage");
        amount = Math.max(0, value);
    }
}
