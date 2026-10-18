package com.skycraft.creatures.client.kit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skycraft.creatures.client.rig.Anim;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.entity.anim.AnimSlot;
import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.client.PoseEntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * The generic keyframe-animated creature model: a baked {@link com.skycraft.creatures.client.rig.Rig} plus its
 * {@link CreatureDef} animations, driven by the creature's state.
 *
 * <ul>
 *     <li>Loops: idle always (fading while moving); walk and run driven by distance walked (like vanilla's
 *     {@code animateWalk}); fly / hover / swim / sleep when the creature says so.</li>
 *     <li>One-shots: the creature's {@link AnimationState}s (attacks, specials, roars...), faded in and out.</li>
 *     <li>Hurt flinch from {@code hurtTime}, death from {@code deathTime}; then the look direction on the look bones.</li>
 * </ul>
 * Subclasses or the {@link #extra} hook add procedural touches (tail physics, glowing pulses, bone toggles).
 */
public class RigModel<T extends LivingEntity> extends PoseEntityModel<T> {
    /** Per-frame procedural hook, run after the keyframe animations. */
    @FunctionalInterface
    public interface Extra<T extends LivingEntity> {
        void apply(RigModel<T> model, T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch);
    }

    private final ModelPart meshRoot;
    private final Map<String, ModelPart> bones = new HashMap<>();
    private final ModelPart[] all;
    public final CreatureDef def;
    private final BoundAnim[] slots = new BoundAnim[AnimSlot.count()];
    private final ModelPart[] lookParts;
    private final float[] lookShare;
    private final List<Extra<T>> extras = new ArrayList<>();
    /** Colour multiplier for the next render (tinted variants), set by the renderer. */
    public float tintR = 1f;
    public float tintG = 1f;
    public float tintB = 1f;
    public float alpha = 1f;

    public RigModel(ModelPart meshRoot, CreatureDef def) {
        super(meshRoot);
        this.meshRoot = meshRoot;
        this.def = def;
        index(meshRoot);
        this.all = bones.values().toArray(new ModelPart[0]);
        for (AnimSlot slot : AnimSlot.values()) {
            Anim anim = def.get(slot.key());
            if (anim != null) slots[slot.ordinal()] = new BoundAnim(RigBaker.bake(anim), bones::get);
        }
        lookParts = new ModelPart[def.look.size()];
        lookShare = new float[def.look.size()];
        int i = 0;
        for (Entry<String, Float> e : def.look.entrySet()) {
            lookParts[i] = bones.get(e.getKey());
            lookShare[i] = e.getValue();
            i++;
        }
    }

    private void index(ModelPart part) {
        // ModelPart doesn't expose child names: walk the rig's own bone list instead
        for (com.skycraft.creatures.client.rig.Bone b : def.rig.bones()) {
            ModelPart found = find(part, b);
            if (found != null) bones.put(b.name, found);
        }
    }

    /** Follows the bone's parent chain down from the mesh root. */
    private static ModelPart find(ModelPart meshRoot, com.skycraft.creatures.client.rig.Bone bone) {
        List<String> path = new ArrayList<>();
        for (com.skycraft.creatures.client.rig.Bone b = bone; b != null; b = b.parent) path.add(0, b.name);
        ModelPart p = meshRoot;
        for (String name : path) {
            if (!p.hasChild(name)) return null;
            p = p.getChild(name);
        }
        return p;
    }

    public RigModel<T> withExtra(Extra<T> extra) {
        extras.add(extra);
        return this;
    }

    /** A bone by rig name, or null. */
    public ModelPart bone(String name) {
        return bones.get(name);
    }

    public boolean hasSlot(AnimSlot slot) {
        return slots[slot.ordinal()] != null;
    }

    /** Puts every bone back in its rest pose (hooks that replace the whole pose). */
    public void resetAll() {
        for (ModelPart p : all) p.resetPose();
    }

    /** Plays a slot's animation at a given time with a weight (for custom drivers in hooks). */
    public void applySlot(AnimSlot slot, float seconds, float weight) {
        BoundAnim a = slots[slot.ordinal()];
        if (a != null) a.apply(seconds, weight);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        for (ModelPart p : all) p.resetPose();
        float partial = ageInTicks - entity.tickCount;
        float seconds = ageInTicks / 20f;

        AnimatedCreature ac = entity instanceof AnimatedCreature a ? a : null;
        if (entity.deathTime > 0 || entity.isDeadOrDying()) {
            BoundAnim death = slots[AnimSlot.DEATH.ordinal()];
            if (death != null) {
                death.apply((entity.deathTime + partial) / 20f, 1f);
            } else {
                applyIdle(seconds, 1f);
            }
        } else {
            float move = Mth.clamp(limbSwingAmount * 1.4f, 0f, 1f);
            boolean sleeping = ac != null ? ac.animSleeping() : entity.isSleeping();
            boolean flying = ac != null && ac.animFlying();
            boolean swimming = ac != null ? ac.animSwimming() : entity.isInWater() && !entity.onGround();
            if (sleeping && slots[AnimSlot.SLEEP.ordinal()] != null) {
                slots[AnimSlot.SLEEP.ordinal()].apply(seconds, 1f);
            } else if (flying && slots[AnimSlot.FLY.ordinal()] != null) {
                BoundAnim hover = slots[AnimSlot.HOVER.ordinal()];
                if (hover != null) {
                    float fly = Mth.clamp(limbSwingAmount * 3f, 0f, 1f);
                    hover.apply(seconds, 1f - fly);
                    slots[AnimSlot.FLY.ordinal()].apply(seconds, fly);
                } else {
                    slots[AnimSlot.FLY.ordinal()].apply(seconds, 1f);
                }
            } else if (swimming && slots[AnimSlot.SWIM.ordinal()] != null) {
                slots[AnimSlot.SWIM.ordinal()].apply(seconds, 1f);
            } else {
                applyIdle(seconds, 1f - move * 0.75f);
                BoundAnim walk = slots[AnimSlot.WALK.ordinal()];
                BoundAnim run = slots[AnimSlot.RUN.ordinal()];
                float run01 = run == null ? 0f : Mth.clamp((limbSwingAmount - def.runThreshold) / 0.2f, 0f, 1f);
                float blocks = limbSwing * 0.25f;
                if (walk != null) walk.apply(blocks * def.walkSecondsPerBlock, move * (1f - run01));
                if (run != null && run01 > 0f) run.apply(blocks * def.runSecondsPerBlock, move * run01);
            }

            if (ac != null) {
                // one-shots started by the server (entity events)
                for (AnimSlot slot : AnimSlot.values()) {
                    if (!slot.oneShot) continue;
                    BoundAnim a = slots[slot.ordinal()];
                    if (a == null) continue;
                    AnimationState state = ac.anims().state(slot);
                    if (!state.isStarted()) continue;
                    float t = state.getTimeInMillis(ageInTicks) / 1000f;
                    float len = a.length();
                    if (t > len) {
                        state.stop();
                        continue;
                    }
                    float w = Math.min(1f, t / 0.08f) * Math.min(1f, (len - t) / 0.1f + 0.15f);
                    a.apply(t, Mth.clamp(w, 0f, 1f));
                }
            } else if (this.attackTime > 0f && slots[AnimSlot.ATTACK.ordinal()] != null) {
                // plain mobs: the vanilla arm swing drives the attack animation
                BoundAnim a = slots[AnimSlot.ATTACK.ordinal()];
                a.apply(this.attackTime * a.length(), 1f);
            }

            BoundAnim hurt = slots[AnimSlot.HURT.ordinal()];
            if (hurt != null && entity.hurtTime > 0) {
                hurt.apply((10 - entity.hurtTime + partial) / 20f, 1f);
            }

            float yaw = Mth.clamp(netHeadYaw, -75f, 75f) * Mth.DEG_TO_RAD;
            float pitch = Mth.clamp(headPitch, -45f, 45f) * Mth.DEG_TO_RAD;
            for (int i = 0; i < lookParts.length; i++) {
                if (lookParts[i] == null) continue;
                lookParts[i].yRot += yaw * lookShare[i];
                lookParts[i].xRot += pitch * lookShare[i];
            }
        }
        for (Extra<T> extra : extras) extra.apply(this, entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
    }

    private void applyIdle(float seconds, float weight) {
        BoundAnim idle = slots[AnimSlot.IDLE.ordinal()];
        if (idle != null) idle.apply(seconds, weight);
    }

}
