package com.skycraft.creatures.client.rig;

/**
 * Parametric flyer plan (cliff racers, hackwings, chaurus hunters, harpies' bird kin, imps' bat wings): a body
 * slung between two jointed wings (membrane or feathered planes), a neck and head with a jaw or beak, taloned legs and
 * a tail. Flapping, gliding, hovering, diving and walking animations.
 *
 * <p>Bone names: {@code body, neck, head, jaw, wing_right/left, wing_tip_right/left, leg_right/left,
 * talon_right/left, tail1..n, tail_fin}.</p>
 */
public final class Winged {
    public final String name;
    public int bodyW = 6;
    public int bodyH = 5;
    public int bodyL = 10;
    public int neckLen = 4;
    public int neckW = 3;
    public int headW = 4;
    public int headH = 4;
    public int headL = 6;
    public int jawL = 5;
    public int wingSpan = 18;
    public int wingChord = 10;
    public int tailSegs = 3;
    public int tailLen = 14;
    public int tailW = 2;
    /** Height of the body centre above the ground when standing. */
    public float stand = 9;
    public boolean tailFin = true;
    /** Insect wings: two thin pairs, buzzing. */
    public boolean insect = false;
    public String skin = "skin";
    public String wingMat = "membrane";
    public String headMat = null;
    public String clawMat = "claw";
    public float flap = 50f;

    public Rig rig;
    public Bone body;
    public Bone head;
    public Bone[] tail;

    public Winged(String name) {
        this.name = name;
    }

    public Rig build() {
        rig = new Rig(name);
        String hm = headMat != null ? headMat : skin;
        body = rig.root().child("body", 0, -stand, 0);
        body.box(skin, -bodyW / 2f, -bodyH / 2f, -bodyL / 2f, bodyW, bodyH, bodyL).tag("torso");
        Bone neck = body.child("neck", 0, -bodyH / 4f, -bodyL / 2f + 1).rot(-15, 0, 0);
        neck.box(skin, -neckW / 2f, -neckW / 2f, -neckLen, neckW, neckW, neckLen + 1).tag("neck");
        head = neck.child("head", 0, 0, -neckLen + 0.5f).rot(15, 0, 0);
        head.box(hm, -headW / 2f, -headH / 2f, -headL, headW, headH - 1, headL).tag("head");
        Bone jaw = head.child("jaw", 0, headH / 2f - 1.5f, -1);
        jaw.box(hm, -headW / 2f + 0.5f, 0, -jawL, headW - 1, 1, jawL).tag("jaw");
        int half = wingSpan / 2;
        if (insect) {
            body.pair("wing", bodyW / 2f - 1, -bodyH / 2f, -1, (b, side) -> {
                b.rot(0, -10, 10);
                b.sbox(wingMat, -half, 0, -2, half, 0, wingChord / 2).tag("membrane");
                Bone tip = b.child("wing_tip", 0, 0, 2).rot(0, -25, -10);
                tip.sbox(wingMat, -half + 2, 0, 0, half - 2, 0, wingChord / 2).tag("membrane");
            });
        } else {
            body.pair("wing", bodyW / 2f - 0.5f, -bodyH / 2f + 1, -bodyL / 4f, (b, side) -> {
                b.sbox(skin, -half / 2f - 1, -1, -1, half / 2 + 1, 2, 2).tag("wing_arm");
                b.sbox(wingMat, -half / 2f - 1, 0, 0, half / 2 + 1, 0, wingChord).tag("membrane");
                Bone tip = b.child("wing_tip", -half / 2f - 1, 0, 0);
                tip.sbox(skin, -half / 2f - 1, -0.5f, -0.5f, half / 2 + 1, 1, 1).tag("wing_arm");
                tip.sbox(wingMat, -half / 2f - 1, 0, 0, half / 2 + 1, 0, Math.max(1, wingChord - 2)).tag("membrane");
            });
        }
        float legDrop = stand - bodyH / 4f;
        body.pair("leg", bodyW / 4f, bodyH / 4f, bodyL / 6f, (b, side) -> {
            b.sbox(skin, -1, 0, -1, 2, Math.max(1, Math.round(legDrop - 1)), 2).tag("leg");
            Bone talon = b.child("talon", 0, legDrop - 1, 0);
            talon.sbox(clawMat, -1.5f, 0, -2, 3, 1, 3).tag("talon");
        });
        tail = new Bone[tailSegs];
        Bone parent = body;
        float seg = tailSegs == 0 ? 0 : (float) tailLen / tailSegs;
        for (int i = 0; i < tailSegs; i++) {
            Bone t = i == 0 ? parent.child("tail1", 0, -bodyH / 4f, bodyL / 2f - 0.5f).rot(8, 0, 0)
                    : parent.child("tail" + (i + 1), 0, 0, seg - 0.3f);
            int tw = Math.max(1, tailW - (i * tailW) / (tailSegs + 1));
            t.box(skin, -tw / 2f, -tw / 2f, 0, tw, tw, Math.max(1, Math.round(seg + 0.4f))).tag("tail");
            tail[i] = t;
            parent = t;
        }
        if (tailFin && tailSegs > 0) {
            Bone fin = parent.child("tail_fin", 0, 0, seg - 0.5f);
            fin.box(wingMat, -3, 0, 0, 6, 0, 4).tag("tail_fin");
        }
        return rig;
    }

    public CreatureDef def() {
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.6f, 0.4f, 0.7f);
        def.look("head", 0.7f);
        def.anim("idle", idle()).anim("walk", walk()).anim("fly", fly()).anim("hover", hover()).anim("attack", dive())
                .anim("attack2", claw()).anim("special", screech()).anim("roar", screech()).anim("death", death()).anim("hurt", hurt());
        return def;
    }

    private void tailWave(Anim a, float amp, int cycles) {
        for (int i = 0; i < tail.length; i++) a.wave(tail[i].name, Anim.Target.ROTATION, 1, amp * (1 + i * 0.3f), -i * 0.12f, 0, cycles);
    }

    /** Perched / standing: wings folded along the body. */
    public Anim idle() {
        Anim a = Anim.loop(3f);
        folded(a);
        a.swing("head", 1, 20f, 0.1f);
        a.bob("body", 1, 0.3f, 0f, 1);
        tailWave(a, 6f, 1);
        return a;
    }

    private void folded(Anim a) {
        if (insect) {
            a.hold("wing_right", 0, 60, -5);
            a.hold("wing_left", 0, -60, 5);
        } else {
            a.hold("wing_right", 0, 70, -20);
            a.hold("wing_left", 0, -70, 20);
            a.hold("wing_tip_right", 0, 150, 0);
            a.hold("wing_tip_left", 0, -150, 0);
        }
    }

    public Anim walk() {
        Anim a = Anim.loop(0.6f);
        folded(a);
        a.swing("leg_right", 0, 30f, 0f);
        a.swing("leg_left", 0, 30f, 0.5f);
        a.swingN("body", 2, 5f, 0f, 1);
        a.bob("body", 1, 0.6f, 0.25f, 2);
        tailWave(a, 8f, 1);
        return a;
    }

    public Anim fly() {
        Anim a = Anim.loop(insect ? 0.15f : 0.75f);
        float f = insect ? 25f : flap;
        a.swing("wing_right", 2, f, 0f, 5f);
        a.swing("wing_left", 2, -f, 0f, -5f);
        if (!insect) {
            a.swing("wing_tip_right", 2, f * 0.5f, -0.12f);
            a.swing("wing_tip_left", 2, -f * 0.5f, -0.12f);
        }
        a.bob("body", 1, 1.2f, 0.25f, 1);
        a.hold("leg_right", 60, 0, 0);
        a.hold("leg_left", 60, 0, 0);
        tailWave(a, 10f, 1);
        return a;
    }

    public Anim hover() {
        Anim a = Anim.loop(insect ? 0.12f : 0.5f);
        float f = insect ? 22f : flap * 0.8f;
        a.swing("wing_right", 2, f, 0f, 15f);
        a.swing("wing_left", 2, -f, 0f, -15f);
        a.hold("wing_right", 0, 20, 0);
        a.hold("wing_left", 0, -20, 0);
        a.hold("body", -25, 0, 0);
        a.hold("leg_right", 20, 0, 0);
        a.hold("leg_left", 20, 0, 0);
        a.bob("body", 1, 1.0f, 0.25f, 1);
        return a;
    }

    /** Swoop bite: wings swept back, head snaps forward. */
    public Anim dive() {
        Anim a = Anim.once(0.6f);
        a.rot("wing_right", 0, 0, 0, 0, 0.2f, 0, 50, -20, 0.4f, 0, 50, -20, 0.6f, 0, 0, 0);
        a.rot("wing_left", 0, 0, 0, 0, 0.2f, 0, -50, 20, 0.4f, 0, -50, 20, 0.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.2f, -20, 0, 0, 0.32f, 20, 0, 0, 0.6f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.2f, 45, 0, 0, 0.3f, 45, 0, 0, 0.35f, 0, 0, 0, 0.6f, 0, 0, 0).smooth();
        a.rot("body", 0, 0, 0, 0, 0.2f, 15, 0, 0, 0.6f, 0, 0, 0);
        return a;
    }

    /** Talons forward, raking. */
    public Anim claw() {
        Anim a = Anim.once(0.6f);
        a.rot("leg_right", 0, 0, 0, 0, 0.2f, -70, 0, 0, 0.35f, 10, 0, 0, 0.6f, 0, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.25f, -70, 0, 0, 0.4f, 10, 0, 0, 0.6f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.2f, -30, 0, 0, 0.6f, 0, 0, 0);
        a.rot("wing_right", 0, 0, 0, 0, 0.2f, 0, 0, -40, 0.4f, 0, 0, 30, 0.6f, 0, 0, 0);
        a.rot("wing_left", 0, 0, 0, 0, 0.2f, 0, 0, 40, 0.4f, 0, 0, -30, 0.6f, 0, 0, 0);
        return a;
    }

    public Anim screech() {
        Anim a = Anim.once(1.2f);
        a.rot("head", 0, 0, 0, 0, 0.3f, -35, 0, 0, 0.9f, -35, 0, 0, 1.2f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.3f, 50, 0, 0, 0.9f, 48, 0, 0, 1.2f, 0, 0, 0);
        a.rot("wing_right", 0, 0, 0, 0, 0.3f, 0, 20, -50, 0.9f, 0, 20, -50, 1.2f, 0, 0, 0);
        a.rot("wing_left", 0, 0, 0, 0, 0.3f, 0, -20, 50, 0.9f, 0, -20, 50, 1.2f, 0, 0, 0);
        return a;
    }

    public Anim death() {
        Anim a = Anim.once(1f);
        a.pos("body", 0, 0, 0, 0, 0.7f, 0, -stand + bodyH / 2f, 0, 1f, 0, -stand + bodyH / 2f, 0);
        a.rot("wing_right", 0, 0, 0, 0, 0.7f, 0, 10, -10, 1f, 0, 10, -10);
        a.rot("wing_left", 0, 0, 0, 0, 0.7f, 0, -10, 10, 1f, 0, -10, 10);
        a.rot("head", 0, 0, 0, 0, 0.7f, 30, 20, 0, 1f, 30, 20, 0);
        return a;
    }

    public Anim hurt() {
        Anim a = Anim.once(0.4f);
        a.rot("body", 0, 0, 0, 0, 0.1f, -10, 0, 8, 0.4f, 0, 0, 0);
        return a;
    }
}
