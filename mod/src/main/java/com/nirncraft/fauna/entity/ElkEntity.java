package com.nirncraft.fauna.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/** {@code nirncraft:elk}: bigger and a little bolder than deer; bulls carry great antlers (Large Antlers). */
public class ElkEntity extends DeerEntity {
    public ElkEntity(EntityType<? extends ElkEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.FOLLOW_RANGE, 20.0);
    }

    @Override
    protected float spookDistance() {
        return 12f;
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.75f;
    }
}
