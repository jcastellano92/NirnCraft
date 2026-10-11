package com.skycraft.curse.client.model;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

/**
 * Terse builders for vanilla keyframe animations ({@link AnimationDefinition}, the system the warden, camel and
 * sniffer use): {@code rot(part, k(t, x, y, z)...)} in degrees, {@code pos(part, k(...)...)} in model pixels,
 * Catmull-Rom smoothed.
 */
final class Keys {
    private Keys() {}

    /** A keyframe at {@code t} seconds; x/y/z are degrees for rotations and pixels for positions. */
    static float[] k(float t, float x, float y, float z) {
        return new float[]{t, x, y, z};
    }

    static Part rot(String part, float[]... frames) {
        return new Part(part, true, frames);
    }

    static Part pos(String part, float[]... frames) {
        return new Part(part, false, frames);
    }

    record Part(String name, boolean rotation, float[][] frames) {}

    static AnimationDefinition anim(float seconds, boolean loop, Part... parts) {
        AnimationDefinition.Builder b = AnimationDefinition.Builder.withLength(seconds);
        if (loop) b.looping();
        for (Part p : parts) {
            Keyframe[] kf = new Keyframe[p.frames.length];
            for (int i = 0; i < kf.length; i++) {
                float[] f = p.frames[i];
                kf[i] = new Keyframe(f[0], p.rotation ? KeyframeAnimations.degreeVec(f[1], f[2], f[3]) : KeyframeAnimations.posVec(f[1], f[2], f[3]),
                        AnimationChannel.Interpolations.CATMULLROM);
            }
            b.addAnimation(p.name, new AnimationChannel(p.rotation ? AnimationChannel.Targets.ROTATION : AnimationChannel.Targets.POSITION, kf));
        }
        return b.build();
    }
}
