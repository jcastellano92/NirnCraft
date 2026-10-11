package com.skycraft.curse.entity;

import com.skycraft.curse.CurseAnims;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * Base of the curse creatures: keyframe animation states (started on the client by entity events the server
 * broadcasts with {@link #playAnim}) and the vampire kin's weakness to fire.
 */
public abstract class CurseMonster extends Monster implements CurseAnims.Animated {
    private final CurseAnims anims = new CurseAnims();

    protected CurseMonster(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    @Override
    public CurseAnims anims() {
        return anims;
    }

    /** Server: plays a one-shot animation for everyone watching. */
    public void playAnim(CurseAnims.Anim anim) {
        if (!this.level().isClientSide) this.level().broadcastEntityEvent(this, CurseAnims.event(anim));
    }

    @Override
    public void handleEntityEvent(byte id) {
        CurseAnims.Anim anim = CurseAnims.fromEvent(id);
        if (anim != null) anims.start(anim, this.tickCount);
        else super.handleEntityEvent(id);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) anims.tick(this.tickCount);
    }

    /** Extra damage taken from fire (vampires and their creatures: 0.5 = 50% more). */
    protected float fireWeakness() {
        return 0f;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (fireWeakness() > 0 && source.is(DamageTypeTags.IS_FIRE)) amount *= 1f + fireWeakness();
        return super.hurt(source, amount);
    }
}
