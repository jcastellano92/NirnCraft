package com.skycraft.creatures.client.rig;

import java.util.ArrayList;
import java.util.List;

/**
 * A keyframe animation authored in plain Java, converted to a vanilla {@code AnimationDefinition} on the client
 * ({@code creatures.client.kit.AnimBaker}). Channels are additive offsets from the rest pose, exactly like vanilla's
 * keyframe animations: rotations in degrees, positions in model pixels (y up, like vanilla's {@code posVec}),
 * scales as factors (1 = unchanged).
 *
 * <p>Helpers write common shapes: {@link #rot}/{@link #pos}/{@link #scale} with explicit keys
 * ({@code t, x, y, z, t, x, y, z, ...}), and {@link #wave} for looping sine motion (gaits, breathing, flapping).</p>
 */
public final class Anim {
    public enum Target { ROTATION, POSITION, SCALE }

    /** One channel: a bone, a target and its keyframes (time in seconds, value xyz). */
    public static final class Channel {
        public final String bone;
        public final Target target;
        public final boolean smooth;
        public final float[] times;
        public final float[][] values;

        Channel(String bone, Target target, boolean smooth, float[] times, float[][] values) {
            this.bone = bone;
            this.target = target;
            this.smooth = smooth;
            this.times = times;
            this.values = values;
        }
    }

    public final float length;
    public final boolean loop;
    public final List<Channel> channels = new ArrayList<>();
    private boolean smooth = true;

    public Anim(float lengthSeconds, boolean loop) {
        this.length = lengthSeconds;
        this.loop = loop;
    }

    public static Anim loop(float seconds) {
        return new Anim(seconds, true);
    }

    public static Anim once(float seconds) {
        return new Anim(seconds, false);
    }

    /** Following channels interpolate linearly (snappy) instead of with Catmull-Rom splines (smooth). */
    public Anim linear() {
        smooth = false;
        return this;
    }

    public Anim smooth() {
        smooth = true;
        return this;
    }

    /** Rotation keys: {@code t, x, y, z} groups (seconds, degrees). */
    public Anim rot(String bone, float... keys) {
        return add(bone, Target.ROTATION, keys);
    }

    /** Position keys: {@code t, x, y, z} groups (seconds, pixels; y up). */
    public Anim pos(String bone, float... keys) {
        return add(bone, Target.POSITION, keys);
    }

    /** Scale keys: {@code t, x, y, z} groups (seconds, factors). */
    public Anim scale(String bone, float... keys) {
        return add(bone, Target.SCALE, keys);
    }

    private Anim add(String bone, Target target, float... keys) {
        if (keys.length % 4 != 0 || keys.length == 0) throw new IllegalArgumentException("keys must be t,x,y,z groups for " + bone);
        int n = keys.length / 4;
        float[] times = new float[n];
        float[][] values = new float[n][3];
        for (int i = 0; i < n; i++) {
            times[i] = keys[i * 4];
            values[i][0] = keys[i * 4 + 1];
            values[i][1] = keys[i * 4 + 2];
            values[i][2] = keys[i * 4 + 3];
        }
        channels.add(new Channel(bone, target, smooth, times, values));
        return this;
    }

    /**
     * A sine wave over the whole (looping) animation: {@code amp * sin(2π (t/len · cycles + phase)) + offset} on one
     * axis (0 = x, 1 = y, 2 = z) of a rotation (degrees) or position (pixels).
     */
    public Anim wave(String bone, Target target, int axis, float amp, float phase, float offset, int cycles) {
        int n = 8 * Math.max(1, cycles);
        float[] times = new float[n + 1];
        float[][] values = new float[n + 1][3];
        for (int i = 0; i <= n; i++) {
            float f = (float) i / n;
            times[i] = f * length;
            values[i][axis] = (float) (amp * Math.sin(2 * Math.PI * (f * cycles + phase))) + offset;
        }
        channels.add(new Channel(bone, target, true, times, values));
        return this;
    }

    /** Rotation wave about one axis (degrees), one cycle per loop. */
    public Anim swing(String bone, int axis, float amp, float phase) {
        return wave(bone, Target.ROTATION, axis, amp, phase, 0f, 1);
    }

    /** Rotation wave about one axis plus a constant offset. */
    public Anim swing(String bone, int axis, float amp, float phase, float offset) {
        return wave(bone, Target.ROTATION, axis, amp, phase, offset, 1);
    }

    /** Position bob on one axis (pixels), {@code cycles} per loop. */
    public Anim bob(String bone, int axis, float amp, float phase, int cycles) {
        return wave(bone, Target.POSITION, axis, amp, phase, 0f, cycles);
    }

    /** Rotation wave with several cycles per loop. */
    public Anim swingN(String bone, int axis, float amp, float phase, int cycles) {
        return wave(bone, Target.ROTATION, axis, amp, phase, 0f, cycles);
    }

    /** Holds a constant rotation for the whole animation (e.g. a pose layered under a loop). */
    public Anim hold(String bone, float x, float y, float z) {
        return rot(bone, 0, x, y, z, length, x, y, z);
    }

    /**
     * Mirrors every channel of a {@code _right} bone onto its {@code _left} twin (rotation y/z and position x
     * negated), optionally shifted in time by half a loop (alternating limbs).
     */
    public Anim mirrorSides(boolean halfPhase) {
        List<Channel> add = new ArrayList<>();
        for (Channel c : channels) {
            if (!c.bone.endsWith("_right")) continue;
            String other = c.bone.substring(0, c.bone.length() - 6) + "_left";
            float[][] vals = new float[c.values.length][3];
            float[] times = c.times.clone();
            for (int i = 0; i < vals.length; i++) {
                float[] v = c.values[i];
                if (c.target == Target.ROTATION) vals[i] = new float[]{v[0], -v[1], -v[2]};
                else if (c.target == Target.POSITION) vals[i] = new float[]{-v[0], v[1], v[2]};
                else vals[i] = v.clone();
            }
            if (halfPhase && loop) shiftHalf(times, vals);
            add.add(new Channel(other, c.target, c.smooth, times, vals));
        }
        channels.addAll(add);
        return this;
    }

    /** Rotates a looping key list by half the loop (keys re-sorted, wrap-around duplicated at both ends). */
    private void shiftHalf(float[] times, float[][] vals) {
        int n = times.length;
        // sample-based: rebuild as evenly spaced samples of the original curve shifted by length/2
        float[] t2 = new float[n];
        float[][] v2 = new float[n][3];
        for (int i = 0; i < n; i++) {
            float t = times[i];
            float src = (t + length / 2f) % length;
            t2[i] = t;
            v2[i] = sampleLinear(times, vals, src);
        }
        System.arraycopy(t2, 0, times, 0, n);
        for (int i = 0; i < n; i++) vals[i] = v2[i];
    }

    private static float[] sampleLinear(float[] times, float[][] vals, float t) {
        if (t <= times[0]) return vals[0].clone();
        for (int i = 1; i < times.length; i++) {
            if (t <= times[i]) {
                float f = (t - times[i - 1]) / Math.max(1e-6f, times[i] - times[i - 1]);
                float[] a = vals[i - 1];
                float[] b = vals[i];
                return new float[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
            }
        }
        return vals[vals.length - 1].clone();
    }
}
