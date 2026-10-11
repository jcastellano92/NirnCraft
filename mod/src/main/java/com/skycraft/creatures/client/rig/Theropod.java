package com.skycraft.creatures.client.rig;

/**
 * Parametric two-legged reptile plan (guar, alit, wamasu, kagouti, raptors of the endless lands): a horizontal body
 * balanced over strong digitigrade legs, a long counterweight tail, a neck and head with jaw, optional small arms.
 *
 * <p>Bone names: {@code body, neck, head, jaw, leg_right/left, shin_right/left, foot_right/left,
 * arm_right/left (optional), tail1..n}.</p>
 */
public final class Theropod {
    public final String name;
    public int bodyW = 8;
    public int bodyH = 8;
    public int bodyL = 12;
    public int legLen = 12;
    public int legW = 4;
    public int neckLen = 5;
    public int neckW = 4;
    public int neckH = 4;
    /** Neck pitch (negative = raised). */
    public float neckPitch = -30;
    public int headW = 6;
    public int headH = 5;
    public int headL = 8;
    public int jawH = 2;
    public int armLen = 0;
    public int tailSegs = 4;
    public int tailLen = 18;
    public int tailW = 5;
    public float tailRaise = 5f;
    public String skin = "skin";
    public String belly = "belly";
    public String headMat = null;
    public String legMat = null;
    public String clawMat = "claw";
    public float scale = 1f;
    public float stride = 32f;

    public Rig rig;
    public Bone body;
    public Bone neck;
    public Bone head;
    public Bone jawBone;
    public Bone[] tail;

    public Theropod(String name) {
        this.name = name;
    }

    public Rig build() {
        rig = new Rig(name);
        String hm = headMat != null ? headMat : skin;
        String lm = legMat != null ? legMat : skin;
        body = rig.root().child("body", 0, -legLen - bodyH / 2f + 1, 0);
        body.box(skin, -bodyW / 2f, -bodyH / 2f, -bodyL / 2f, bodyW, bodyH, bodyL).tag("torso");
        if (belly != null) body.box(belly, -bodyW / 2f + 1, bodyH / 2f - 1.5f, -bodyL / 2f + 1, bodyW - 2, 2, bodyL - 2, 0.05f).tag("belly");
        neck = body.child("neck", 0, -bodyH / 4f, -bodyL / 2f + 1).rot(neckPitch, 0, 0);
        neck.box(skin, -neckW / 2f, -neckH / 2f, -neckLen, neckW, neckH, neckLen + 1).tag("neck");
        head = neck.child("head", 0, 0, -neckLen + 0.5f).rot(-neckPitch, 0, 0);
        head.box(hm, -headW / 2f, -headH / 2f, -headL, headW, headH - jawH + 1, headL).tag("head");
        jawBone = head.child("jaw", 0, headH / 2f - jawH, -1);
        jawBone.box(hm, -headW / 2f + 0.5f, 0, -headL + 1.5f, headW - 1, jawH, headL - 1).tag("jaw");
        int thigh = Math.round(legLen * 0.45f);
        int shin = legLen - thigh;
        body.pair("leg", bodyW / 2f - legW / 2f + 0.5f, bodyH / 4f, bodyL / 6f, (b, side) -> {
            b.rot(-20, 0, 0);
            b.sbox(lm, -legW / 2f, -2, -legW / 2f - 1, legW, thigh + 2, legW + 2).tag("thigh");
            Bone sh = b.child("shin", 0, thigh, 0).rot(45, 0, 0);
            int sw = Math.max(2, legW - 2);
            sh.sbox(lm, -sw / 2f, -0.5f, -sw / 2f, sw, shin, sw).tag("shin");
            Bone foot = sh.child("foot", 0, shin - 0.5f, 0).rot(-25, 0, 0);
            foot.sbox(lm, -sw / 2f - 0.5f, -0.5f, -sw / 2f - 3, sw + 1, 2, sw + 3).tag("foot");
            foot.sbox(clawMat, -sw / 2f - 0.5f, 0.5f, -sw / 2f - 4, sw + 1, 1, 1).tag("claw");
        });
        if (armLen > 0) {
            body.pair("arm", bodyW / 2f - 0.5f, bodyH / 4f, -bodyL / 2f + 2, (b, side) -> {
                b.rot(40, 0, 0);
                b.sbox(lm, -1, 0, -1, 2, armLen, 2).tag("arm");
                b.sbox(clawMat, -1, armLen - 1, -2, 2, 1, 1).tag("claw");
            });
        }
        tail = new Bone[tailSegs];
        Bone parent = body;
        float seg = (float) tailLen / tailSegs;
        for (int i = 0; i < tailSegs; i++) {
            Bone t = i == 0 ? parent.child("tail1", 0, -bodyH / 4f, bodyL / 2f - 1).rot(tailRaise, 0, 0)
                    : parent.child("tail" + (i + 1), 0, 0, seg - 0.3f).rot(-3, 0, 0);
            int tw = Math.max(1, tailW - (i * tailW) / (tailSegs + 1));
            t.box(skin, -tw / 2f, -tw / 2f, 0, tw, tw, Math.max(1, Math.round(seg + 0.4f))).tag("tail");
            tail[i] = t;
            parent = t;
        }
        return rig;
    }

    public CreatureDef def() {
        CreatureDef def = new CreatureDef(rig);
        float strideBlocks = Math.max(0.5f, legLen * 2.6f / 16f * scale);
        def.gait(1.0f / strideBlocks, 0.6f / (strideBlocks * 1.6f), 0.6f);
        def.look("head", 0.6f).look("neck", 0.4f);
        def.anim("idle", idle()).anim("walk", walk()).anim("run", run()).anim("attack", bite()).anim("attack2", tailWhip())
                .anim("special", roar()).anim("roar", roar()).anim("sleep", sleep()).anim("death", death()).anim("hurt", hurt());
        return def;
    }

    private void tailWave(Anim a, float amp, int cycles) {
        for (int i = 0; i < tail.length; i++) a.wave(tail[i].name, Anim.Target.ROTATION, 1, amp * (1 + i * 0.3f), -i * 0.1f, 0, cycles);
    }

    public Anim idle() {
        Anim a = Anim.loop(3f);
        a.bob("body", 1, 0.3f, 0f, 1);
        a.swing("neck", 0, 3f, 0.2f);
        a.swing("head", 1, 12f, 0.1f);
        a.rot("jaw", 0, 0, 0, 0, 1.8f, 0, 0, 0, 2.0f, 10, 0, 0, 2.3f, 0, 0, 0, 3f, 0, 0, 0);
        tailWave(a, 5f, 1);
        return a;
    }

    public Anim walk() {
        Anim a = Anim.loop(1.0f);
        a.swing("leg_right", 0, stride, 0f);
        a.swing("leg_left", 0, stride, 0.5f);
        a.swing("shin_right", 0, stride * 0.6f, 0.15f);
        a.swing("shin_left", 0, stride * 0.6f, 0.65f);
        a.swing("foot_right", 0, stride * 0.5f, 0.3f);
        a.swing("foot_left", 0, stride * 0.5f, 0.8f);
        a.bob("body", 1, 0.8f, 0.25f, 2);
        a.swing("body", 2, 3f, 0f);
        a.swingN("neck", 0, 4f, 0f, 2);
        a.swingN("head", 0, -4f, 0f, 2);
        tailWave(a, 6f, 1);
        return a;
    }

    public Anim run() {
        Anim a = Anim.loop(0.6f);
        a.swing("leg_right", 0, stride * 1.5f, 0f);
        a.swing("leg_left", 0, stride * 1.5f, 0.5f);
        a.swing("shin_right", 0, stride, 0.15f);
        a.swing("shin_left", 0, stride, 0.65f);
        a.swing("foot_right", 0, stride * 0.7f, 0.3f);
        a.swing("foot_left", 0, stride * 0.7f, 0.8f);
        a.bob("body", 1, 1.4f, 0.25f, 2);
        a.hold("neck", 15, 0, 0);
        a.hold("head", 5, 0, 0);
        for (int i = 0; i < tail.length; i++) a.hold(tail[i].name, -4, 0, 0);
        tailWave(a, 4f, 1);
        return a;
    }

    public Anim bite() {
        Anim a = Anim.once(0.7f);
        a.rot("neck", 0, 0, 0, 0, 0.22f, -25, 0, 0, 0.34f, 25, 0, 0, 0.7f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.22f, -15, 0, 0, 0.34f, 15, 0, 0, 0.7f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.22f, 45, 0, 0, 0.32f, 45, 0, 0, 0.37f, 0, 0, 0, 0.7f, 0, 0, 0).smooth();
        a.pos("body", 0, 0, 0, 0, 0.22f, 0, 0, 1.5f, 0.34f, 0, -0.5f, -2.5f, 0.7f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.34f, 6, 0, 0, 0.7f, 0, 0, 0);
        for (int i = 0; i < tail.length; i++) a.rot(tail[i].name, 0, 0, 0, 0, 0.34f, -6, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** Spins half round and lashes with the tail. */
    public Anim tailWhip() {
        Anim a = Anim.once(0.9f);
        a.rot("body", 0, 0, 0, 0, 0.3f, 0, 40, 0, 0.45f, 0, -60, 0, 0.9f, 0, 0, 0);
        for (int i = 0; i < tail.length; i++) {
            a.rot(tail[i].name, 0, 0, 0, 0, 0.3f, 0, 20, 0, 0.45f + i * 0.03f, 0, -35 - i * 5, 0, 0.9f, 0, 0, 0);
        }
        a.rot("neck", 0, 0, 0, 0, 0.45f, 0, 30, 0, 0.9f, 0, 0, 0);
        return a;
    }

    public Anim roar() {
        Anim a = Anim.once(1.5f);
        a.rot("body", 0, 0, 0, 0, 0.35f, -12, 0, 0, 1.1f, -12, 0, 0, 1.5f, 0, 0, 0);
        a.rot("neck", 0, 0, 0, 0, 0.35f, -30, 0, 0, 1.1f, -30, 0, 0, 1.5f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.35f, -15, 0, 0, 0.7f, -15, 10, 0, 1.1f, -15, -10, 0, 1.5f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.35f, 45, 0, 0, 1.1f, 42, 0, 0, 1.5f, 0, 0, 0);
        return a;
    }

    public Anim sleep() {
        Anim a = Anim.loop(4f);
        a.pos("body", 0, 0, -legLen * 0.6f, 0, 4f, 0, -legLen * 0.6f, 0);
        a.bob("body", 1, 0.3f, 0f, 1);
        a.hold("leg_right", -50, 0, 0);
        a.hold("leg_left", -50, 0, 0);
        a.hold("shin_right", 60, 0, 0);
        a.hold("shin_left", 60, 0, 0);
        a.hold("neck", 30, 30, 0);
        a.hold("head", 0, 20, 0);
        for (int i = 0; i < tail.length; i++) a.hold(tail[i].name, 5, i == 0 ? 25 : 15, 0);
        return a;
    }

    public Anim death() {
        Anim a = Anim.once(1f);
        a.pos("body", 0, 0, 0, 0, 0.6f, 0, -legLen * 0.4f, 0, 1f, 0, -legLen * 0.4f, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.6f, -45, 0, 0, 1f, -45, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.6f, -40, 0, 0, 1f, -40, 0, 0);
        a.rot("neck", 0, 0, 0, 0, 0.6f, 35, 15, 0, 1f, 35, 15, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.6f, 25, 0, 0, 1f, 25, 0, 0);
        return a;
    }

    public Anim hurt() {
        Anim a = Anim.once(0.45f);
        a.rot("body", 0, 0, 0, 0, 0.1f, -5, 0, 4, 0.45f, 0, 0, 0);
        a.rot("neck", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.45f, 0, 0, 0);
        return a;
    }
}
