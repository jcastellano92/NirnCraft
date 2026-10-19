package com.nirncraft.creatures.client.rig;

/**
 * Parametric many-legged plan (frostbite spiders, chaurus, giant scorpions, mudcrabs, dwarven spiders): a
 * cephalothorax, head with fangs or mandibles, an abdomen, N pairs of two-part legs splayed out, optional pincers and an
 * optional segmented tail (scorpion stinger arching over the back).
 *
 * <p>Bone names: {@code body, head, fang_right/left, abdomen, leg1_right..legN_left, leg1_lower_right...,
 * claw_right/left, claw_lower_right/left, pincer_right/left, tail1..n, sting}.</p>
 */
public final class Arachnid {
    public final String name;
    public int bodyW = 8;
    public int bodyH = 5;
    public int bodyL = 8;
    public int headW = 6;
    public int headH = 4;
    public int headL = 4;
    public int fangLen = 3;
    public int abdomenW = 10;
    public int abdomenH = 8;
    public int abdomenL = 12;
    /** Abdomen tilt in degrees (positive = the abdomen's tail end dips; a chain pointing backwards rises with negative
     * rotations here because the rest rotation is applied as {@code -abdomenTilt}). */
    public float abdomenTilt = 15;
    public int legPairs = 4;
    public int legUpper = 9;
    public int legLower = 11;
    public int legW = 2;
    /** Upper leg angle above horizontal and lower leg angle below horizontal (degrees). */
    public float legUp = 40;
    public float legDown = 70;
    /** Body centre height above the ground (computed from the legs). */
    public float clearance;
    public boolean pincers = false;
    public int pincerLen = 7;
    public int tailSegs = 0;
    public int tailSegLen = 4;
    public int tailW = 3;
    public String shell = "chitin";
    public String legMat = null;
    public String abdomenMat = null;
    public String fangMat = "fang";
    public float scale = 1f;

    public Rig rig;
    public Bone body;
    public Bone head;
    public Bone abdomen;
    public Bone[] tail = new Bone[0];

    public Arachnid(String name) {
        this.name = name;
    }

    public Rig build() {
        rig = new Rig(name);
        String lm = legMat != null ? legMat : shell;
        double drop = -legUpper * Math.sin(Math.toRadians(legUp)) + legLower * Math.sin(Math.toRadians(legDown));
        clearance = (float) (drop + bodyH / 4f);
        body = rig.root().child("body", 0, -clearance, 0);
        body.box(shell, -bodyW / 2f, -bodyH / 2f, -bodyL / 2f, bodyW, bodyH, bodyL).tag("thorax");
        head = body.child("head", 0, 0, -bodyL / 2f);
        if (headL > 0) {
            head.box(shell, -headW / 2f, -headH / 2f, -headL, headW, headH, headL).tag("head");
        }
        if (fangLen > 0) {
            head.pair("fang", headW / 4f, headH / 2f - 1, -headL, (b, side) -> {
                b.rot(20, 0, 0);
                b.sbox(fangMat, -1, 0, -1, 2, fangLen, 2).tag("fang");
            });
        }
        abdomen = body.child("abdomen", 0, -bodyH / 4f, bodyL / 2f).rot(-abdomenTilt, 0, 0);
        if (abdomenL > 0) {
            abdomen.box(abdomenMat != null ? abdomenMat : shell, -abdomenW / 2f, -abdomenH / 2f, 0, abdomenW, abdomenH, abdomenL).tag("abdomen");
        }
        // legs fan out from front to back along the thorax sides
        for (int i = 0; i < legPairs; i++) {
            float f = legPairs == 1 ? 0.5f : (float) i / (legPairs - 1);
            float z = -bodyL / 2f + 1 + f * (bodyL - 2);
            float yaw = -(50 - f * 100); // front legs reach forward, back legs back (positive yaw swings a -x leg backwards)
            final int idx = i + 1;
            body.pair("leg" + idx, bodyW / 2f - 0.5f, bodyH / 4f, z, (b, side) -> {
                b.rot(0, yaw, 0);
                Bone up = b.child("leg" + idx + "_upper", 0, 0, 0).rot(0, 0, legUp);
                up.sbox(lm, -legUpper, -legW / 2f, -legW / 2f, legUpper, legW, legW).tag("leg");
                Bone low = up.child("leg" + idx + "_lower", -legUpper + 0.5f, 0, 0).rot(0, 0, -(legUp + legDown));
                int lw = Math.max(1, legW - 1);
                low.sbox(lm, -legLower, -lw / 2f, -lw / 2f, legLower, lw, lw).tag("leg");
            });
        }
        if (pincers) {
            body.pair("claw", bodyW / 2f - 1, bodyH / 4f, -bodyL / 2f, (b, side) -> {
                b.rot(0, 40, 10);
                b.sbox(lm, -pincerLen, -1.5f, -1.5f, pincerLen, 3, 3).tag("arm");
                Bone low = b.child("claw_lower", -pincerLen + 0.5f, 0, 0).rot(0, -70, 0);
                low.sbox(shell, -pincerLen, -2, -2.5f, pincerLen, 4, 5).tag("pincer");
                Bone p = low.child("pincer", -pincerLen + 1, 0, -2).rot(0, 20, 0);
                p.sbox(shell, -pincerLen / 2f - 1, -1, -2, pincerLen / 2 + 1, 2, 2).tag("pincer");
            });
        }
        if (tailSegs > 0) {
            tail = new Bone[tailSegs];
            Bone parent = abdomen;
            for (int i = 0; i < tailSegs; i++) {
                Bone t = i == 0 ? parent.child("tail1", 0, -abdomenH / 4f, abdomenL - 1).rot(-35, 0, 0)
                        : parent.child("tail" + (i + 1), 0, 0, tailSegLen - 0.5f).rot(-30, 0, 0);
                int tw = Math.max(2, tailW - i / 2);
                t.box(shell, -tw / 2f, -tw / 2f, 0, tw, tw, tailSegLen).tag("tail");
                tail[i] = t;
                parent = t;
            }
            Bone sting = parent.child("sting", 0, 0, tailSegLen - 0.5f).rot(-40, 0, 0);
            sting.box(shell, -tailW / 2f, -tailW / 2f, 0, tailW, tailW, 3).tag("sting_bulb");
            sting.box(fangMat, -0.5f, 0, 2.5f, 1, 3, 1).tag("sting");
        }
        return rig;
    }

    public CreatureDef def() {
        CreatureDef def = new CreatureDef(rig);
        float strideBlocks = Math.max(0.5f, (legUpper + legLower) * 1.4f / 16f * scale);
        def.gait(0.8f / strideBlocks, 0.5f / (strideBlocks * 1.4f), 0.65f);
        def.look("head", 0.4f).look("body", 0.3f);
        def.anim("idle", idle()).anim("walk", walk(0.8f, 18f)).anim("run", walk(0.5f, 24f)).anim("attack", strike())
                .anim("attack2", pincers ? pinch() : tailSegs > 0 ? sting() : strike()).anim("special", spit())
                .anim("death", death()).anim("hurt", hurt());
        if (tailSegs > 0) def.anim("attack3", sting());
        return def;
    }

    private String leg(int i, boolean right) {
        return "leg" + i + (right ? "_right" : "_left");
    }

    public Anim idle() {
        Anim a = Anim.loop(2.5f);
        a.wave("abdomen", Anim.Target.ROTATION, 0, 3f, 0f, 0f, 1);
        a.bob("body", 1, 0.3f, 0.2f, 1);
        if (fangLen > 0) {
            a.swingN("fang_right", 2, 10f, 0f, 2);
            a.swingN("fang_left", 2, -10f, 0f, 2);
        }
        for (int i = 1; i <= legPairs; i++) {
            a.swing(leg(i, true), 1, 3f, i * 0.2f);
            a.swing(leg(i, false), 1, -3f, i * 0.2f + 0.5f);
        }
        for (int i = 0; i < tail.length; i++) a.swing(tail[i].name, 0, 4f, -i * 0.1f);
        return a;
    }

    /** Alternating tetrapod gait: legs 1 and 3 on one side move with 2 and 4 on the other. */
    public Anim walk(float len, float amp) {
        Anim a = Anim.loop(len);
        for (int i = 1; i <= legPairs; i++) {
            float phase = (i % 2 == 0) ? 0.5f : 0f;
            a.swing(leg(i, true), 1, amp, phase);
            a.swing(leg(i, false), 1, -amp, phase + 0.5f);
            // lift: z rotation pulses up during the forward stroke
            a.swing(leg(i, true), 2, amp * 0.6f, phase + 0.25f);
            a.swing(leg(i, false), 2, -amp * 0.6f, phase + 0.75f);
        }
        a.bob("body", 1, 0.5f, 0f, 2);
        a.swingN("abdomen", 1, 3f, 0f, 1);
        return a;
    }

    /** Rears up and drives the fangs / mandibles down. */
    public Anim strike() {
        Anim a = Anim.once(0.7f);
        a.rot("body", 0, 0, 0, 0, 0.25f, -25, 0, 0, 0.38f, 12, 0, 0, 0.7f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.25f, 0, 1.5f, 1, 0.38f, 0, -0.5f, -2, 0.7f, 0, 0, 0);
        a.rot(leg(1, true), 0, 0, 0, 0, 0.25f, 0, 30, -40, 0.38f, 0, 0, 0, 0.7f, 0, 0, 0);
        a.rot(leg(1, false), 0, 0, 0, 0, 0.25f, 0, -30, 40, 0.38f, 0, 0, 0, 0.7f, 0, 0, 0);
        if (fangLen > 0) {
            a.rot("fang_right", 0, 0, 0, 0, 0.25f, -30, 0, -20, 0.36f, 30, 0, 10, 0.7f, 0, 0, 0);
            a.rot("fang_left", 0, 0, 0, 0, 0.25f, -30, 0, 20, 0.36f, 30, 0, -10, 0.7f, 0, 0, 0);
        }
        return a;
    }

    public Anim pinch() {
        Anim a = Anim.once(0.6f);
        a.rot("claw_right", 0, 0, 0, 0, 0.2f, 0, 40, -25, 0.32f, 0, -10, 0, 0.6f, 0, 0, 0);
        a.rot("pincer_right", 0, 0, 0, 0, 0.2f, 0, 35, 0, 0.3f, 0, -5, 0, 0.6f, 0, 0, 0);
        a.rot("claw_left", 0, 0, 0, 0, 0.25f, 0, -40, 25, 0.37f, 0, 10, 0, 0.6f, 0, 0, 0);
        a.rot("pincer_left", 0, 0, 0, 0, 0.25f, 0, -35, 0, 0.35f, 0, 5, 0, 0.6f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.3f, -6, 0, 0, 0.6f, 0, 0, 0);
        return a;
    }

    public Anim sting() {
        Anim a = Anim.once(0.8f);
        for (int i = 0; i < tail.length; i++) {
            a.rot(tail[i].name, 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.42f, 25, 0, 0, 0.8f, 0, 0, 0);
        }
        a.rot("sting", 0, 0, 0, 0, 0.3f, -20, 0, 0, 0.42f, 40, 0, 0, 0.8f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.42f, 8, 0, 0, 0.8f, 0, 0, 0);
        return a;
    }

    /** Abdomen pumps, head jerks forward: spitting venom. */
    public Anim spit() {
        Anim a = Anim.once(0.9f);
        a.rot("abdomen", 0, 0, 0, 0, 0.3f, -20, 0, 0, 0.45f, 10, 0, 0, 0.9f, 0, 0, 0);
        a.scale("abdomen", 0, 1, 1, 1, 0.3f, 1.1f, 1.1f, 0.95f, 0.45f, 0.95f, 0.95f, 1.05f, 0.9f, 1, 1, 1);
        a.rot("body", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.45f, 8, 0, 0, 0.9f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.35f, -10, 0, 0, 0.45f, 15, 0, 0, 0.9f, 0, 0, 0);
        return a;
    }

    /** Legs curl in, body sinks. */
    public Anim death() {
        Anim a = Anim.once(1f);
        a.pos("body", 0, 0, 0, 0, 0.6f, 0, -clearance + bodyH / 2f, 0, 1f, 0, -clearance + bodyH / 2f, 0);
        for (int i = 1; i <= legPairs; i++) {
            a.rot(leg(i, true), 0, 0, 0, 0, 0.6f, 0, 0, 40, 1f, 0, 0, 45);
            a.rot(leg(i, false), 0, 0, 0, 0, 0.6f, 0, 0, -40, 1f, 0, 0, -45);
            a.rot(leg(i, true) .replace("_right", "_lower_right"), 0, 0, 0, 0, 0.6f, 0, 0, -40, 1f, 0, 0, -45);
            a.rot(leg(i, false).replace("_left", "_lower_left"), 0, 0, 0, 0, 0.6f, 0, 0, 40, 1f, 0, 0, 45);
        }
        return a;
    }

    public Anim hurt() {
        Anim a = Anim.once(0.4f);
        a.rot("body", 0, 0, 0, 0, 0.1f, -8, 0, 0, 0.4f, 0, 0, 0);
        return a;
    }
}
