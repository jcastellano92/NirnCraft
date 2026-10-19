package com.nirncraft.creatures.client.rig;

/**
 * Parametric two-legged body plan for brutes and daedra (trolls, giants, ogres, minotaurs, frost giants, kamal,
 * rieklings, ash spawn, gargoyles, atronachs...): hips, a torso that can hunch forward, head with optional jaw, two
 * three-part arms, two legs (plantigrade or digitigrade), optional tail and wings, plus the matching animations.
 *
 * <p>Bone names: {@code hips, torso, head, jaw, arm_right/left, forearm_right/left, hand_right/left,
 * leg_right/left, shin_right/left, foot_right/left, tail1..n, wing_right/left, wing_tip_right/left}.</p>
 */
public final class Biped {
    public final String name;
    public int legLen = 12;
    public int legW = 4;
    public int hipW = 8;
    public int hipH = 4;
    public int torsoW = 8;
    public int torsoH = 12;
    public int torsoD = 5;
    /** Forward lean of the torso in degrees. */
    public float hunch = 0;
    public int armLen = 12;
    public int armW = 4;
    /** Fist / hand box size added below the forearm (0 = none). */
    public int handSize = 0;
    public int headW = 8;
    public int headH = 8;
    public int headD = 8;
    /** Head sits forward of the shoulders (hunched brutes). */
    public float headForward = 0;
    public boolean jaw = false;
    public int jawH = 2;
    /** Digitigrade legs (hooves / claws: minotaurs, gargoyles, daedra). */
    public boolean digitigrade = false;
    public int tailSegs = 0;
    public int tailLen = 10;
    public int tailW = 2;
    public boolean wings = false;
    public int wingLen = 18;
    public int wingSpan = 16;

    public String skin = "skin";
    public String headMat = null;
    public String armMat = null;
    public String legMat = null;
    public String handMat = null;
    public String footMat = null;
    public String hipMat = null;
    public String wingMat = "membrane";
    /** Renderer scale (gait speed is in world blocks). */
    public float scale = 1f;
    public float stride = 28f;
    /** Weapon swing: overhead two-handed (giants) instead of a right hook. */
    public boolean twoHanded = false;
    /** attack3 is a foot stomp (giants) instead of a two-fisted ground pound. */
    public boolean stomp = false;

    public Rig rig;
    public Bone hips;
    public Bone torso;
    public Bone head;
    public Bone jawBone;
    public Bone[] arms;
    public Bone[] forearms = new Bone[2];
    public Bone[] hands = new Bone[2];
    public Bone[] legs;
    public Bone[] shins = new Bone[2];
    public Bone[] feet = new Bone[2];
    public Bone[] tail = new Bone[0];
    public Bone[] wingBones;

    public Biped(String name) {
        this.name = name;
    }

    public Rig build() {
        rig = new Rig(name);
        String hm = headMat != null ? headMat : skin;
        String am = armMat != null ? armMat : skin;
        String lm = legMat != null ? legMat : skin;
        String hdm = handMat != null ? handMat : am;
        String fm = footMat != null ? footMat : lm;
        hips = rig.root().child("hips", 0, -legLen, 0);
        hips.box(hipMat != null ? hipMat : skin, -hipW / 2f, -hipH, -torsoD / 2f, hipW, hipH, torsoD).tag("hips");
        torso = hips.child("torso", 0, -hipH + 1, 0).rot(hunch, 0, 0);
        torso.box(skin, -torsoW / 2f, -torsoH, -torsoD / 2f, torsoW, torsoH, torsoD).tag("torso");
        head = torso.child("head", 0, -torsoH, -headForward).rot(-hunch, 0, 0);
        head.box(hm, -headW / 2f, -headH, -headD / 2f - 0.5f, headW, headH, headD).tag("head");
        if (jaw) {
            jawBone = head.child("jaw", 0, -jawH, -headD / 2f + 1.5f);
            jawBone.box(hm, -headW / 2f + 1, 0, -headD + 1.5f, headW - 2, jawH, headD - 2).tag("jaw");
        }
        float shoulderX = torsoW / 2f + armW / 2f - 0.5f;
        int upper = Math.round(armLen * 0.5f);
        int lower = armLen - upper;
        arms = torso.pair("arm", shoulderX, -torsoH + armW / 2f, 0, (b, side) -> {
            b.rot(-hunch * 0.85f, 0, 4);
            b.sbox(am, -armW / 2f, -armW / 2f, -armW / 2f, armW, upper + armW / 2, armW).tag("arm");
            Bone fore = b.child("forearm", 0, upper, 0);
            int fw = Math.max(2, armW - 1);
            fore.sbox(am, -fw / 2f, -0.5f, -fw / 2f, fw, lower + 1, fw).tag("forearm");
            Bone hand = fore.child("hand", 0, lower, 0);
            if (handSize > 0) hand.sbox(hdm, -handSize / 2f, -0.5f, -handSize / 2f, handSize, handSize, handSize).tag("hand");
        });
        for (int i = 0; i < 2; i++) {
            String s = i == 0 ? "_right" : "_left";
            forearms[i] = rig.bone("forearm" + s);
            hands[i] = rig.bone("hand" + s);
        }
        float legX = hipW / 2f - legW / 2f;
        int thigh = Math.round(legLen * 0.5f);
        int shin = legLen - thigh;
        legs = hips.pair("leg", legX, 0, 0, (b, side) -> {
            b.sbox(lm, -legW / 2f, -1, -legW / 2f, legW, thigh + 1, legW).tag("leg");
            Bone sh = b.child("shin", 0, thigh, 0);
            int sw = Math.max(2, legW - 1);
            if (digitigrade) {
                sh.rot(25, 0, 0);
                sh.sbox(lm, -sw / 2f, 0, -sw / 2f, sw, shin - 1, sw).tag("shin");
                Bone foot = sh.child("foot", 0, shin - 1, 0).rot(-25, 0, 0);
                foot.sbox(fm, -sw / 2f - 0.5f, -0.5f, -sw / 2f - 2, sw + 1, 2, sw + 2).tag("foot");
            } else {
                sh.sbox(lm, -sw / 2f, 0, -sw / 2f, sw, shin - 1, sw).tag("shin");
                Bone foot = sh.child("foot", 0, shin - 1, 0);
                foot.sbox(fm, -legW / 2f, -0.5f, -legW / 2f - 1.5f, legW, 1, legW + 2, 0.25f).tag("foot");
            }
        });
        for (int i = 0; i < 2; i++) {
            String s = i == 0 ? "_right" : "_left";
            shins[i] = rig.bone("shin" + s);
            feet[i] = rig.bone("foot" + s);
        }
        if (tailSegs > 0) {
            tail = new Bone[tailSegs];
            Bone parent = hips;
            float seg = (float) tailLen / tailSegs;
            for (int i = 0; i < tailSegs; i++) {
                Bone t = i == 0 ? parent.child("tail1", 0, -hipH / 2f, torsoD / 2f - 0.5f).rot(-40, 0, 0)
                        : parent.child("tail" + (i + 1), 0, 0, seg - 0.3f).rot(-8, 0, 0);
                int tw = Math.max(1, tailW - i * tailW / (tailSegs + 1));
                t.box(skin, -tw / 2f, -tw / 2f, 0, tw, tw, Math.max(1, Math.round(seg + 0.4f))).tag("tail");
                tail[i] = t;
                parent = t;
            }
        }
        if (wings) {
            int span = wingSpan;
            int len = wingLen;
            wingBones = torso.pair("wing", torsoW / 2f - 1, -torsoH + 3, torsoD / 2f - 1, (b, side) -> {
                b.rot(-20, -35, 20);
                b.sbox(am, -span / 2f, -1, -1, span / 2 + 1, 2, 2).tag("wing_arm");
                b.sbox(wingMat, -span / 2f, 0, 0, span / 2 + 1, 0, len).tag("membrane");
                Bone tip = b.child("wing_tip", -span / 2f, 0, 0);
                tip.sbox(am, -span / 2f, -0.5f, -0.5f, span / 2, 1, 1).tag("wing_arm");
                tip.sbox(wingMat, -span / 2f, 0, 0, span / 2, 0, Math.max(1, len - 3)).tag("membrane");
            });
        }
        return rig;
    }

    // ------------------------------------------------------------------------------------------------ animations

    public CreatureDef def() {
        CreatureDef def = new CreatureDef(rig);
        float strideBlocks = Math.max(0.4f, legLen * 2.2f / 16f * scale);
        def.gait(1.1f / strideBlocks, 0.7f / (strideBlocks * 1.5f), 0.6f);
        def.look("head", 1.0f);
        def.anim("idle", idle());
        def.anim("walk", walk());
        def.anim("run", run());
        def.anim("attack", twoHanded ? overhead() : hook());
        def.anim("attack2", swipe());
        def.anim("attack3", stomp ? stomp() : slam());
        def.anim("special", roar());
        def.anim("roar", roar());
        def.anim("cast", cast());
        def.anim("summon", summon());
        def.anim("death", death());
        def.anim("hurt", hurt());
        if (wings) {
            def.anim("fly", fly());
            def.anim("hover", hover());
        }
        return def;
    }

    private void tailSway(Anim a, float amp) {
        for (int i = 0; i < tail.length; i++) a.wave(tail[i].name, Anim.Target.ROTATION, 1, amp * (1 + i * 0.3f), -i * 0.12f, 0, 1);
    }

    public Anim idle() {
        Anim a = Anim.loop(3.5f);
        a.swing("torso", 0, 1.5f, 0f);
        a.bob("torso", 1, 0.25f, 0.25f, 1);
        a.swing("arm_right", 2, 2.5f, 0.1f, 0);
        a.swing("arm_left", 2, -2.5f, 0.1f, 0);
        a.swing("arm_right", 0, 3f, 0.3f);
        a.swing("arm_left", 0, 3f, 0.8f);
        a.swing("head", 1, 8f, 0.2f);
        if (jaw) a.rot("jaw", 0, 0, 0, 0, 2.0f, 0, 0, 0, 2.3f, 8, 0, 0, 2.7f, 0, 0, 0, 3.5f, 0, 0, 0);
        tailSway(a, 6f);
        if (wings) {
            a.hold("wing_right", 0, 0, 0);
            a.swing("wing_right", 2, 3f, 0f);
            a.swing("wing_left", 2, -3f, 0f);
        }
        return a;
    }

    public Anim walk() {
        Anim a = Anim.loop(1.1f);
        float s = stride;
        a.swing("leg_right", 0, s, 0f);
        a.swing("leg_left", 0, s, 0.5f);
        knee(a, "shin_right", s * 1.1f, 0f);
        knee(a, "shin_left", s * 1.1f, 0.5f);
        a.swing("arm_right", 0, s * 0.8f, 0.5f);
        a.swing("arm_left", 0, s * 0.8f, 0f);
        a.swing("forearm_right", 0, 10f, 0.6f, -12f);
        a.swing("forearm_left", 0, 10f, 0.1f, -12f);
        a.bob("hips", 1, 0.7f, 0.25f, 2);
        a.swing("torso", 1, 4f, 0f);
        a.swing("hips", 1, -3f, 0f);
        a.swingN("head", 0, 2f, 0f, 2);
        tailSway(a, 9f);
        return a;
    }

    /** Knee bends during the forward swing. */
    private static void knee(Anim a, String bone, float amp, float phase) {
        int n = 8;
        float[] keys = new float[(n + 1) * 4];
        for (int i = 0; i <= n; i++) {
            float f = (float) i / n;
            double v = Math.sin(2 * Math.PI * (f + phase + 0.25));
            keys[i * 4] = f * a.length;
            keys[i * 4 + 1] = (float) Math.max(0, v) * amp;
        }
        a.rot(bone, keys);
    }

    public Anim run() {
        Anim a = Anim.loop(0.7f);
        float s = stride * 1.6f;
        a.swing("leg_right", 0, s, 0f);
        a.swing("leg_left", 0, s, 0.5f);
        knee(a, "shin_right", s * 1.2f, 0f);
        knee(a, "shin_left", s * 1.2f, 0.5f);
        a.swing("arm_right", 0, s * 0.9f, 0.5f);
        a.swing("arm_left", 0, s * 0.9f, 0f);
        a.hold("forearm_right", -35, 0, 0);
        a.hold("forearm_left", -35, 0, 0);
        a.hold("torso", 12, 0, 0);
        a.bob("hips", 1, 1.2f, 0.25f, 2);
        tailSway(a, 12f);
        if (wings) {
            a.hold("wing_right", 0, 15, 0);
            a.hold("wing_left", 0, -15, 0);
        }
        return a;
    }

    /** Right hook / one-handed weapon swing. */
    public Anim hook() {
        Anim a = Anim.once(0.7f);
        a.rot("torso", 0, 0, 0, 0, 0.25f, -4, 30, 0, 0.38f, 8, -35, 0, 0.7f, 0, 0, 0);
        a.rot("arm_right", 0, 0, 0, 0, 0.25f, -110, 20, 30, 0.38f, -60, -10, -10, 0.5f, -20, 0, 0, 0.7f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.25f, -40, 0, 0, 0.38f, -10, 0, 0, 0.7f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.3f, 20, 0, -10, 0.7f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, 5, -15, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** Overhead two-handed smash (giant's club). */
    public Anim overhead() {
        Anim a = Anim.once(1.2f);
        a.rot("torso", 0, 0, 0, 0, 0.5f, -20, 0, 0, 0.65f, 25, 0, 0, 0.9f, 20, 0, 0, 1.2f, 0, 0, 0);
        for (String s : new String[]{"_right", "_left"}) {
            float y = s.equals("_right") ? 15 : -15;
            a.rot("arm" + s, 0, 0, 0, 0, 0.5f, -170, y, 0, 0.65f, -50, y * 0.5f, 0, 0.9f, -40, 0, 0, 1.2f, 0, 0, 0);
            a.rot("forearm" + s, 0, 0, 0, 0, 0.5f, -20, 0, 0, 0.65f, 0, 0, 0, 1.2f, 0, 0, 0);
        }
        a.rot("leg_right", 0, 0, 0, 0, 0.6f, -20, 0, 0, 1.2f, 0, 0, 0);
        a.pos("hips", 0, 0, 0, 0, 0.65f, 0, -1.5f, 0, 1.2f, 0, 0, 0);
        return a;
    }

    /** Backhand / left claw swipe. */
    public Anim swipe() {
        Anim a = Anim.once(0.65f);
        a.rot("torso", 0, 0, 0, 0, 0.22f, 0, -25, 0, 0.36f, 6, 30, 0, 0.65f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.22f, -80, -30, -60, 0.36f, -70, 40, 10, 0.5f, -20, 0, 0, 0.65f, 0, 0, 0);
        a.rot("forearm_left", 0, 0, 0, 0, 0.22f, -30, 0, 0, 0.65f, 0, 0, 0);
        return a;
    }

    /** Both fists pound the ground (or a stomp for short-armed creatures). */
    public Anim slam() {
        Anim a = Anim.once(1.1f);
        a.rot("torso", 0, 0, 0, 0, 0.45f, -25, 0, 0, 0.6f, 45, 0, 0, 0.85f, 40, 0, 0, 1.1f, 0, 0, 0);
        for (String s : new String[]{"_right", "_left"}) {
            a.rot("arm" + s, 0, 0, 0, 0, 0.45f, -160, 0, 0, 0.6f, -60, 0, 0, 0.85f, -55, 0, 0, 1.1f, 0, 0, 0);
            a.rot("forearm" + s, 0, 0, 0, 0, 0.45f, -30, 0, 0, 0.6f, -5, 0, 0, 1.1f, 0, 0, 0);
        }
        a.pos("hips", 0, 0, 0, 0, 0.6f, 0, -2f, 0, 0.85f, 0, -2f, 0, 1.1f, 0, 0, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.6f, -30, 0, 0, 0.85f, -30, 0, 0, 1.1f, 0, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.6f, -30, 0, 0, 0.85f, -30, 0, 0, 1.1f, 0, 0, 0);
        a.rot("shin_right", 0, 0, 0, 0, 0.6f, 50, 0, 0, 0.85f, 50, 0, 0, 1.1f, 0, 0, 0);
        a.rot("shin_left", 0, 0, 0, 0, 0.6f, 50, 0, 0, 0.85f, 50, 0, 0, 1.1f, 0, 0, 0);
        return a;
    }

    /** Raises a foot high and stamps it down. */
    public Anim stomp() {
        Anim a = Anim.once(1.1f);
        a.rot("leg_right", 0, 0, 0, 0, 0.5f, -75, 0, 0, 0.66f, 5, 0, 0, 0.9f, 0, 0, 0, 1.1f, 0, 0, 0);
        a.rot("shin_right", 0, 0, 0, 0, 0.5f, 60, 0, 0, 0.66f, 0, 0, 0, 1.1f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.5f, -10, 0, 0, 0.66f, 12, 0, 0, 1.1f, 0, 0, 0);
        a.rot("arm_right", 0, 0, 0, 0, 0.5f, -20, 0, 25, 0.7f, 0, 0, 10, 1.1f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.5f, -20, 0, -25, 0.7f, 0, 0, -10, 1.1f, 0, 0, 0);
        a.pos("hips", 0, 0, 0, 0, 0.5f, 0, 1, 0, 0.66f, 0, -1, 0, 1.1f, 0, 0, 0);
        return a;
    }

    /** Chest out, arms wide, head back: a roar or war cry. */
    public Anim roar() {
        Anim a = Anim.once(1.5f);
        a.rot("torso", 0, 0, 0, 0, 0.35f, -15, 0, 0, 1.1f, -15, 0, 0, 1.5f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.35f, -30, 0, 0, 0.7f, -25, 10, 0, 1.1f, -25, -10, 0, 1.5f, 0, 0, 0);
        if (jaw) a.rot("jaw", 0, 0, 0, 0, 0.35f, 40, 0, 0, 1.1f, 38, 0, 0, 1.5f, 0, 0, 0);
        a.rot("arm_right", 0, 0, 0, 0, 0.35f, -30, 0, 60, 1.1f, -35, 0, 65, 1.5f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.35f, -30, 0, -60, 1.1f, -35, 0, -65, 1.5f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.35f, -60, 0, 0, 1.1f, -60, 0, 0, 1.5f, 0, 0, 0);
        a.rot("forearm_left", 0, 0, 0, 0, 0.35f, -60, 0, 0, 1.1f, -60, 0, 0, 1.5f, 0, 0, 0);
        if (wings) {
            a.rot("wing_right", 0, 0, 0, 0, 0.35f, 0, 40, -30, 1.1f, 0, 40, -30, 1.5f, 0, 0, 0);
            a.rot("wing_left", 0, 0, 0, 0, 0.35f, 0, -40, 30, 1.1f, 0, -40, 30, 1.5f, 0, 0, 0);
        }
        return a;
    }

    /** One arm thrust forward: a spell or a thrown missile. */
    public Anim cast() {
        Anim a = Anim.once(1.0f);
        a.rot("arm_right", 0, 0, 0, 0, 0.35f, -150, 0, 15, 0.5f, -80, 0, 0, 0.7f, -75, 0, 0, 1.0f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.35f, -30, 0, 0, 0.5f, 0, 0, 0, 1.0f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.35f, -40, 0, -20, 0.7f, -30, 0, -20, 1.0f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.35f, -8, 20, 0, 0.5f, 6, -10, 0, 1.0f, 0, 0, 0);
        return a;
    }

    /** Both arms raised: summoning, calling. */
    public Anim summon() {
        Anim a = Anim.once(1.4f);
        a.rot("arm_right", 0, 0, 0, 0, 0.4f, -165, 0, 25, 1.0f, -165, 0, 25, 1.4f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.4f, -165, 0, -25, 1.0f, -165, 0, -25, 1.4f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -25, 0, 0, 1.0f, -25, 0, 0, 1.4f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.4f, -10, 0, 0, 1.0f, -10, 0, 0, 1.4f, 0, 0, 0);
        return a;
    }

    /** Knees buckle, torso slumps forward, arms fall. */
    public Anim death() {
        Anim a = Anim.once(1.0f);
        a.pos("hips", 0, 0, 0, 0, 0.6f, 0, -legLen * 0.35f, 0, 1f, 0, -legLen * 0.35f, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.6f, -40, 0, 0, 1f, -40, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.6f, -30, 0, 0, 1f, -30, 0, 0);
        a.rot("shin_right", 0, 0, 0, 0, 0.6f, 70, 0, 0, 1f, 70, 0, 0);
        a.rot("shin_left", 0, 0, 0, 0, 0.6f, 60, 0, 0, 1f, 60, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.6f, 25, 0, 0, 1f, 30, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.6f, 30, 15, 0, 1f, 30, 15, 0);
        a.rot("arm_right", 0, 0, 0, 0, 0.6f, -10, 0, 15, 1f, -10, 0, 15);
        a.rot("arm_left", 0, 0, 0, 0, 0.6f, -10, 0, -15, 1f, -10, 0, -15);
        return a;
    }

    public Anim hurt() {
        Anim a = Anim.once(0.45f);
        a.rot("torso", 0, 0, 0, 0, 0.1f, -10, 0, 0, 0.45f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.45f, 0, 0, 0);
        return a;
    }

    public Anim fly() {
        Anim a = Anim.loop(0.8f);
        a.swing("wing_right", 2, 45f, 0f, 10f);
        a.swing("wing_left", 2, -45f, 0f, -10f);
        a.swing("wing_tip_right", 2, 25f, -0.12f);
        a.swing("wing_tip_left", 2, -25f, -0.12f);
        a.hold("wing_right", 0, 30, 0);
        a.hold("wing_left", 0, -30, 0);
        a.hold("torso", 40, 0, 0);
        a.hold("leg_right", 35, 0, 0);
        a.hold("leg_left", 35, 0, 0);
        a.bob("hips", 1, 1.5f, 0.25f, 1);
        return a;
    }

    public Anim hover() {
        Anim a = Anim.loop(0.55f);
        a.swing("wing_right", 2, 40f, 0f, 5f);
        a.swing("wing_left", 2, -40f, 0f, -5f);
        a.swing("wing_tip_right", 2, 20f, -0.12f);
        a.swing("wing_tip_left", 2, -20f, -0.12f);
        a.hold("wing_right", 0, 25, 0);
        a.hold("wing_left", 0, -25, 0);
        a.hold("leg_right", 15, 0, 0);
        a.hold("leg_left", 10, 0, 0);
        a.bob("hips", 1, 1.0f, 0.25f, 1);
        return a;
    }
}
