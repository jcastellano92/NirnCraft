package com.skycraft.creatures.client.kit;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A vanilla {@link AnimationDefinition} bound to one model's parts: the same sampling as
 * {@code KeyframeAnimations.animate} (keyframe search, interpolation, additive offsets), but with the bones resolved
 * once instead of per frame, and with a blend weight. Works for any model with named parts (rig models and the
 * humanoid model alike).
 */
public final class BoundAnim {
    private static final Vector3f CACHE = new Vector3f();

    public final AnimationDefinition def;
    private final ModelPart[] parts;
    private final AnimationChannel[] channels;

    public BoundAnim(AnimationDefinition def, Function<String, ModelPart> bones) {
        this.def = def;
        List<ModelPart> p = new ArrayList<>();
        List<AnimationChannel> c = new ArrayList<>();
        for (Map.Entry<String, List<AnimationChannel>> e : def.boneAnimations().entrySet()) {
            ModelPart part = bones.apply(e.getKey());
            if (part == null) continue;
            for (AnimationChannel ch : e.getValue()) {
                p.add(part);
                c.add(ch);
            }
        }
        this.parts = p.toArray(new ModelPart[0]);
        this.channels = c.toArray(new AnimationChannel[0]);
    }

    public float length() {
        return def.lengthInSeconds();
    }

    public boolean looping() {
        return def.looping();
    }

    /** Applies the pose at {@code seconds} (wrapped for loops, held at the end otherwise), scaled by {@code weight}. */
    public void apply(float seconds, float weight) {
        if (weight <= 0.001f) return;
        float len = def.lengthInSeconds();
        float t = def.looping() && len > 0 ? seconds % len : Math.min(seconds, len);
        if (t < 0) t += len;
        for (int n = 0; n < channels.length; n++) {
            Keyframe[] keys = channels[n].keyframes();
            final float time = t;
            int i = Math.max(0, Mth.binarySearch(0, keys.length, k -> time <= keys[k].timestamp()) - 1);
            int j = Math.min(keys.length - 1, i + 1);
            Keyframe a = keys[i];
            Keyframe b = keys[j];
            float f = j != i ? Mth.clamp((time - a.timestamp()) / (b.timestamp() - a.timestamp()), 0.0f, 1.0f) : 0.0f;
            b.interpolation().apply(CACHE, f, keys, i, j, weight);
            channels[n].target().apply(parts[n], CACHE);
        }
    }
}
