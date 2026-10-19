package com.nirncraft.creatures.client.rig;

/**
 * Parametric four-legged body plan: torso, a neck of one or more segments, a head with snout, jaw and ears, four
 * two-part legs and a segmented tail, with matching idle / walk / run / bite / swipe / roar / sleep / death
 * animations scaled to the proportions. Creature definitions set the numbers, call {@link #build()}, then add their own
 * features (antlers, tusks, manes, fangs) to the returned bones before {@link #def()}.
 *
 * <p>Bone names: {@code body, neck1..n, head, snout, jaw, ear_right/left, leg_front_right/left,
 * leg_front_lower_right/left, leg_back_right/left, leg_back_lower_right/left, tail1..n}.</p>
 */
public final class Quad {
    public final String name;
    // torso
    public int bodyW = 8;
    public int bodyH = 8;
    public int bodyL = 16;
    /** Extra chest box (shoulder hump), 0 = none. */
    public int chestW;
    public int chestH;
    public int chestL;
    // legs
    public int legLen = 10;
    public int legW = 3;
    public int frontLegLen = -1;
    public int backLegLen = -1;
    /** Feet: "paw" (with claws), "hoof", "pad" (plain). */
    public String foot = "paw";
    // neck & head
    public int neckSegs = 1;
    public int neckLen = 4;
    public int neckW = 4;
    public int neckH = 5;
    /** Neck pitch in degrees (negative = raised). */
    public float neckPitch = -25f;
    public int headW = 6;
    public int headH = 6;
    public int headL = 6;
    public int snoutW = 4;
    public int snoutH = 3;
    public int snoutL = 4;
    public boolean jaw = true;
    /** Ear height (0 = none); ears point up and out. */
    public int earH = 3;
    public int earW = 2;
    // tail
    public int tailSegs = 3;
    public int tailLen = 12;
    public int tailW = 2;
    /** Tail droop in degrees (positive = hanging down). */
    public float tailDroop = 35f;
    // materials
    public String fur = "fur";
    public String belly = "belly";
    public String headMat = null;
    public String legMat = null;
    public String footMat = "claw";
    public String tailMat = null;
    public String neckMat = null;
    public String snoutMat = null;
    // motion
    /** Renderer scale (gait speed is in world blocks). */
    public float scale = 1f;
    public float stride = 26f;
    public float runStride = 42f;
    /** Gallop (bounding cats, dogs) instead of a fast trot when running. */
    public boolean gallop = true;
    /** Bite (attack) and swipe (attack2) or butt (horned grazers). */
    public boolean butt;

    public Rig rig;
    public Bone body;
    public Bone chest;
    public Bone[] neck;
    public Bone head;
    public Bone snout;
    public Bone jawBone;
    public Bone[] tail;
    public Bone[] legsUpper = new Bone[4];
    public Bone[] legsLower = new Bone[4];

    public Quad(String name) {
        this.name = name;
    }

    private int front() {
        return frontLegLen > 0 ? frontLegLen : legLen;
    }

    private int back() {
        return backLegLen > 0 ? backLegLen : legLen;
    }

    public Rig build() {
        rig = new Rig(name);
        String hm = headMat != null ? headMat : fur;
        String lm = legMat != null ? legMat : fur;
        String tm = tailMat != null ? tailMat : fur;
        int maxLeg = Math.max(front(), back());
        // body pivot at the torso centre
        body = rig.root().child("body", 0, -(maxLeg + bodyH / 2f), 0);
        body.box(fur, -bodyW / 2f, -bodyH / 2f, -bodyL / 2f, bodyW, bodyH, bodyL).tag("torso");
        if (belly != null) {
            body.box(belly, -bodyW / 2f + 1, bodyH / 2f - 2, -bodyL / 2f + 2, bodyW - 2, 2, bodyL - 4, 0.05f).tag("belly");
        }
        if (chestL > 0) {
            chest = body.child("chest", 0, 0, -bodyL / 2f + chestL / 2f);
            chest.box(fur, -chestW / 2f, -bodyH / 2f - (chestH - bodyH) * 0.7f, -chestL / 2f, chestW, chestH, chestL).tag("chest");
        }

        // neck chain
        neck = new Bone[neckSegs];
        Bone parent = body;
        float startY = -bodyH * 0.15f;
        for (int i = 0; i < neckSegs; i++) {
            Bone n = i == 0
                    ? parent.child("neck1", 0, startY, -bodyL / 2f + 1.5f)
                    : parent.child("neck" + (i + 1), 0, 0, -neckLen + 0.5f);
            n.rot(i == 0 ? neckPitch : 0, 0, 0);
            n.box(neckMat != null ? neckMat : fur, -neckW / 2f, -neckH / 2f, -neckLen, neckW, neckH, neckLen + 1).tag("neck");
            neck[i] = n;
            parent = n;
        }
        head = parent.child("head", 0, 0, -neckLen + 0.5f).rot(-neckPitch, 0, 0);
        head.box(hm, -headW / 2f, -headH / 2f, -headL, headW, headH, headL).tag("head");
        snout = head.child("snout", 0, 0, -headL);
        float snoutTop = headH / 2f - snoutH - (jaw ? 1 : 0);
        String sm = snoutMat != null ? snoutMat : hm;
        snout.box(sm, -snoutW / 2f, snoutTop, -snoutL, snoutW, snoutH, snoutL).tag("snout");
        if (jaw) {
            jawBone = head.child("jaw", 0, headH / 2f - 1.5f, -headL * 0.35f);
            int jl = Math.round(headL * 0.65f + snoutL - 0.5f);
            jawBone.box(sm, -snoutW / 2f + 0.5f, 0, -jl, Math.max(1, snoutW - 1), 2, jl).tag("jaw");
        }
        if (earH > 0) {
            head.pair("ear", headW / 2f - earW / 2f, -headH / 2f + 0.5f, -headL * 0.25f, (b, side) -> {
                b.rot(-10, 0, -20);
                b.sbox(hm, -earW / 2f, -earH, -0.5f, earW, earH, 1).tag("ear");
            });
        }

        // legs
        float sx = bodyW / 2f - legW / 2f;
        float fz = -bodyL / 2f + legW / 2f + 1;
        float bz = bodyL / 2f - legW / 2f - 1;
        leg(0, "leg_front", sx, fz, front(), maxLeg, lm);
        leg(2, "leg_back", sx, bz, back(), maxLeg, lm);

        // tail
        tail = new Bone[tailSegs];
        parent = body;
        float seg = tailSegs == 0 ? 0 : (float) tailLen / tailSegs;
        for (int i = 0; i < tailSegs; i++) {
            Bone t = i == 0
                    ? parent.child("tail1", 0, -bodyH / 2f + tailW / 2f + 0.5f, bodyL / 2f - 0.5f)
                    : parent.child("tail" + (i + 1), 0, 0, seg - 0.3f);
            if (i == 0) t.rot(-tailDroop, 0, 0);
            else t.rot(-tailDroop * 0.25f, 0, 0);
            int tw = Math.max(1, tailW - (i * tailW) / (tailSegs + 1));
            t.box(tm, -tw / 2f, -tw / 2f, 0, tw, tw, Math.max(1, Math.round(seg + 0.4f))).tag("tail");
            tail[i] = t;
            parent = t;
        }
        return rig;
    }

    private void leg(int index, String base, float sx, float z, int len, int maxLeg, String lm) {
        // the pivot sits inside the torso's lower half; shorter legs start lower
        float pivotY = bodyH / 2f - 1.5f + (maxLeg - len);
        int total = len + 2; // from pivot to ground
        int upper = Math.round(total * 0.52f);
        int lower = total - upper + 1;
        Bone[] pair = body.pair(base, sx, pivotY, z, (b, side) -> {
            b.sbox(lm, -legW / 2f, -1, -legW / 2f, legW, upper + 1, legW).tag("leg");
            Bone low = b.child(base + "_lower", 0, upper - 0.5f, 0);
            int lw = Math.max(1, legW - 1);
            low.sbox(lm, -lw / 2f, 0, -lw / 2f, lw, lower - 1, lw).tag("leg");
            if ("hoof".equals(foot)) {
                low.sbox(footMat, -lw / 2f - 0.25f, lower - 2, -lw / 2f - 0.25f, lw, 2, lw, 0.25f).tag("hoof");
            } else if ("paw".equals(foot)) {
                low.sbox(lm, -legW / 2f, lower - 2, -legW / 2f - 1, legW, 2, legW + 1).tag("paw");
                low.sbox(footMat, -legW / 2f, lower - 1, -legW / 2f - 2, legW, 1, 1).tag("claw");
            }
        });
        legsUpper[index] = pair[0];
        legsUpper[index + 1] = pair[1];
        legsLower[index] = rig.bone(base + "_lower_right");
        legsLower[index + 1] = rig.bone(base + "_lower_left");
    }

    // ------------------------------------------------------------------------------------------------ animations

    /** Builds the creature definition with the standard animation set (call after {@link #build()} and extras). */
    public CreatureDef def() {
        CreatureDef def = new CreatureDef(rig);
        float strideBlocks = Math.max(0.5f, (legLen * 2.6f / 16f) * scale);
        def.gait(1.0f / strideBlocks, 0.62f / (strideBlocks * 1.6f), 0.6f);
        def.look("head", 0.6f);
        if (neckSegs > 0) def.look("neck1", 0.4f);
        def.anim("idle", idle());
        def.anim("walk", walk());
        def.anim("run", run());
        def.anim("attack", butt ? butt() : bite());
        def.anim("attack2", butt ? kick() : swipe());
        def.anim("special", roar());
        def.anim("sleep", sleep());
        def.anim("death", death());
        def.anim("hurt", hurt());
        return def;
    }

    private void tailWave(Anim a, float amp, int cycles, float pitchAmp) {
        for (int i = 0; i < tail.length; i++) {
            a.wave(tail[i].name, Anim.Target.ROTATION, 1, amp * (1 + i * 0.35f), -i * 0.12f, 0, cycles);
            if (pitchAmp != 0) a.wave(tail[i].name, Anim.Target.ROTATION, 0, pitchAmp, -i * 0.1f, 0, cycles);
        }
    }

    public Anim idle() {
        Anim a = Anim.loop(4f);
        a.bob("body", 1, 0.25f, 0f, 2);
        a.wave("head", Anim.Target.ROTATION, 1, 9f, 0.1f, 0, 1);
        a.wave("head", Anim.Target.ROTATION, 0, 4f, 0.3f, 2f, 1);
        if (jaw) a.rot("jaw", 0, 0, 0, 0, 2.6f, 0, 0, 0, 2.9f, 6, 0, 0, 3.2f, 0, 0, 0, 4f, 0, 0, 0);
        if (earH > 0) {
            a.rot("ear_right", 0, 0, 0, 0, 1.0f, 0, 0, 0, 1.1f, 0, 0, -18, 1.25f, 0, 0, 0, 4f, 0, 0, 0);
            a.rot("ear_left", 0, 0, 0, 0, 2.7f, 0, 0, 0, 2.8f, 0, 0, 18, 2.95f, 0, 0, 0, 4f, 0, 0, 0);
        }
        tailWave(a, 7f, 1, 0);
        return a;
    }

    public Anim walk() {
        Anim a = Anim.loop(1.0f);
        float s = stride;
        // diagonal pairs: front-right with back-left
        a.swing("leg_front_right", 0, s, 0f);
        a.swing("leg_back_left", 0, s, 0f);
        a.swing("leg_front_left", 0, s, 0.5f);
        a.swing("leg_back_right", 0, s, 0.5f);
        // knees: front bends backwards on the lift, hind hocks bend forward
        kneeFront(a, "leg_front_lower_right", s * 0.9f, 0f);
        kneeFront(a, "leg_front_lower_left", s * 0.9f, 0.5f);
        kneeBack(a, "leg_back_lower_left", s * 0.7f, 0f);
        kneeBack(a, "leg_back_lower_right", s * 0.7f, 0.5f);
        a.bob("body", 1, 0.5f, 0.25f, 2);
        a.swingN("body", 2, 1.5f, 0f, 1);
        a.swingN("head", 0, 3f, 0.1f, 2);
        tailWave(a, 8f, 1, 0);
        return a;
    }

    /** Front lower leg: folds back (positive x) while the leg swings forward. */
    private static void kneeFront(Anim a, String bone, float amp, float phase) {
        int n = 8;
        float[] keys = new float[(n + 1) * 4];
        for (int i = 0; i <= n; i++) {
            float f = (float) i / n;
            double v = Math.sin(2 * Math.PI * (f + phase - 0.25));
            keys[i * 4] = f * a.length;
            keys[i * 4 + 1] = (float) Math.max(0, v) * amp;
        }
        a.rot(bone, keys);
    }

    /** Hind lower leg: hock flexes (negative x) during the lift. */
    private static void kneeBack(Anim a, String bone, float amp, float phase) {
        int n = 8;
        float[] keys = new float[(n + 1) * 4];
        for (int i = 0; i <= n; i++) {
            float f = (float) i / n;
            double v = Math.sin(2 * Math.PI * (f + phase + 0.25));
            keys[i * 4] = f * a.length;
            keys[i * 4 + 1] = -(float) Math.max(0, v) * amp;
        }
        a.rot(bone, keys);
    }

    public Anim run() {
        Anim a = Anim.loop(0.62f);
        float s = runStride;
        if (gallop) {
            a.swing("leg_front_right", 0, s, 0f);
            a.swing("leg_front_left", 0, s, 0.08f);
            a.swing("leg_back_right", 0, s, 0.5f);
            a.swing("leg_back_left", 0, s, 0.58f);
            kneeFront(a, "leg_front_lower_right", s * 0.8f, 0f);
            kneeFront(a, "leg_front_lower_left", s * 0.8f, 0.08f);
            kneeBack(a, "leg_back_lower_right", s * 0.6f, 0.5f);
            kneeBack(a, "leg_back_lower_left", s * 0.6f, 0.58f);
            a.swing("body", 0, 6f, 0.25f);
            a.bob("body", 1, 1.2f, 0.0f, 1);
            if (neckSegs > 0) a.swing("neck1", 0, 7f, 0.75f);
        } else {
            a.swing("leg_front_right", 0, s, 0f);
            a.swing("leg_back_left", 0, s, 0f);
            a.swing("leg_front_left", 0, s, 0.5f);
            a.swing("leg_back_right", 0, s, 0.5f);
            kneeFront(a, "leg_front_lower_right", s * 0.9f, 0f);
            kneeFront(a, "leg_front_lower_left", s * 0.9f, 0.5f);
            kneeBack(a, "leg_back_lower_left", s * 0.7f, 0f);
            kneeBack(a, "leg_back_lower_right", s * 0.7f, 0.5f);
            a.bob("body", 1, 0.9f, 0.25f, 2);
        }
        a.hold("head", 8f, 0, 0);
        for (int i = 0; i < tail.length; i++) a.wave(tail[i].name, Anim.Target.ROTATION, 0, 10f, -i * 0.12f, 12f, 1);
        return a;
    }

    /** Lunge and snap. */
    public Anim bite() {
        Anim a = Anim.once(0.65f);
        a.pos("body", 0, 0, 0, 0, 0.18f, 0, 0.5f, 1.5f, 0.3f, 0, -0.3f, -2.5f, 0.65f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.18f, -6, 0, 0, 0.3f, 5, 0, 0, 0.65f, 0, 0, 0);
        if (neckSegs > 0) a.rot("neck1", 0, 0, 0, 0, 0.18f, -15, 0, 0, 0.3f, 18, 0, 0, 0.65f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.18f, -12, 0, 0, 0.3f, 10, 0, 0, 0.65f, 0, 0, 0);
        if (jaw) a.linear().rot("jaw", 0, 0, 0, 0, 0.2f, 38, 0, 0, 0.29f, 38, 0, 0, 0.34f, 0, 0, 0, 0.65f, 0, 0, 0).smooth();
        a.rot("leg_front_right", 0, 0, 0, 0, 0.3f, -18, 0, 0, 0.65f, 0, 0, 0);
        a.rot("leg_front_left", 0, 0, 0, 0, 0.3f, -18, 0, 0, 0.65f, 0, 0, 0);
        return a;
    }

    /** Rear a little and rake with the right forepaw. */
    public Anim swipe() {
        Anim a = Anim.once(0.75f);
        a.rot("body", 0, 0, 0, 0, 0.25f, -14, 0, 4, 0.4f, -4, 0, -2, 0.75f, 0, 0, 0);
        a.rot("leg_front_right", 0, 0, 0, 0, 0.25f, -85, 0, 15, 0.38f, -20, 0, -5, 0.5f, 10, 0, 0, 0.75f, 0, 0, 0);
        a.rot("leg_front_lower_right", 0, 0, 0, 0, 0.25f, 40, 0, 0, 0.38f, 0, 0, 0, 0.75f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.4f, 6, 0, 0, 0.75f, 0, 0, 0);
        if (jaw) a.rot("jaw", 0, 0, 0, 0, 0.25f, 25, 0, 0, 0.5f, 5, 0, 0, 0.75f, 0, 0, 0);
        return a;
    }

    /** Lower the head and butt forwards (horned/antlered grazers). */
    public Anim butt() {
        Anim a = Anim.once(0.8f);
        if (neckSegs > 0) a.rot("neck1", 0, 0, 0, 0, 0.3f, 30, 0, 0, 0.45f, 22, 0, 0, 0.8f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, 25, 0, 0, 0.42f, -15, 0, 0, 0.55f, 5, 0, 0, 0.8f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.3f, 0, -0.5f, 1.5f, 0.42f, 0, 0, -3f, 0.8f, 0, 0, 0);
        a.rot("leg_back_right", 0, 0, 0, 0, 0.42f, 20, 0, 0, 0.8f, 0, 0, 0);
        a.rot("leg_back_left", 0, 0, 0, 0, 0.42f, 20, 0, 0, 0.8f, 0, 0, 0);
        return a;
    }

    /** Hind kick (grazers' second attack). */
    public Anim kick() {
        Anim a = Anim.once(0.7f);
        a.rot("body", 0, 0, 0, 0, 0.2f, 10, 0, 0, 0.35f, 14, 0, 0, 0.7f, 0, 0, 0);
        a.rot("leg_back_right", 0, 0, 0, 0, 0.2f, -20, 0, 0, 0.32f, 60, 0, 0, 0.7f, 0, 0, 0);
        a.rot("leg_back_left", 0, 0, 0, 0, 0.2f, -20, 0, 0, 0.32f, 60, 0, 0, 0.7f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, 15, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** Head up, howl / roar / bellow. */
    public Anim roar() {
        Anim a = Anim.once(1.6f);
        if (neckSegs > 0) a.rot("neck1", 0, 0, 0, 0, 0.35f, -35, 0, 0, 1.2f, -38, 0, 0, 1.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.35f, -25, 0, 0, 0.8f, -28, 6, 0, 1.2f, -25, -6, 0, 1.6f, 0, 0, 0);
        if (jaw) a.rot("jaw", 0, 0, 0, 0, 0.35f, 35, 0, 0, 1.2f, 32, 0, 0, 1.6f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.35f, -5, 0, 0, 1.2f, -5, 0, 0, 1.6f, 0, 0, 0);
        return a;
    }

    /** Lying down, slow breathing. */
    public Anim sleep() {
        Anim a = Anim.loop(4f);
        int drop = Math.max(front(), back()) - 1;
        a.pos("body", 0, 0, -drop, 0, 4f, 0, -drop, 0);
        a.bob("body", 1, 0.35f, 0f, 1);
        for (String side : new String[]{"_right", "_left"}) {
            a.hold("leg_front" + side, -80, 0, 0);
            a.hold("leg_front_lower" + side, 80, 0, 0);
            a.hold("leg_back" + side, 75, 0, 0);
            a.hold("leg_back_lower" + side, -85, 0, 0);
        }
        if (neckSegs > 0) a.hold("neck1", 22, 0, 0);
        a.hold("head", 10, 30, 0);
        for (int i = 0; i < tail.length; i++) a.hold(tail[i].name, 20, i == 0 ? 25 : 18, 0);
        return a;
    }

    /** Legs give way, head drops (the renderer then tips the body over). */
    public Anim death() {
        Anim a = Anim.once(1.0f);
        a.rot("leg_front_right", 0, 0, 0, 0, 0.4f, -40, 0, 10, 1f, -40, 0, 10);
        a.rot("leg_front_left", 0, 0, 0, 0, 0.4f, -40, 0, -10, 1f, -40, 0, -10);
        a.rot("leg_back_right", 0, 0, 0, 0, 0.4f, 35, 0, 10, 1f, 35, 0, 10);
        a.rot("leg_back_left", 0, 0, 0, 0, 0.4f, 35, 0, -10, 1f, 35, 0, -10);
        if (neckSegs > 0) a.rot("neck1", 0, 0, 0, 0, 0.5f, 30, 0, 0, 1f, 30, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.5f, 20, 10, 0, 1f, 20, 10, 0);
        if (jaw) a.rot("jaw", 0, 0, 0, 0, 0.5f, 18, 0, 0, 1f, 18, 0, 0);
        return a;
    }

    public Anim hurt() {
        Anim a = Anim.once(0.5f);
        a.rot("body", 0, 0, 0, 0, 0.1f, -4, 0, 5, 0.5f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.5f, 0, 0, 0);
        return a;
    }
}
