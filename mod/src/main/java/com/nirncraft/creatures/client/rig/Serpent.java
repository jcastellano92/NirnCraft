package com.nirncraft.creatures.client.rig;

/**
 * Parametric serpent / eel / fish plan (ice wraiths, sea serpents, sand serpents, slaughterfish, wyrms): a head with a
 * hinged jaw leading a chain of tapering segments, optional fin pairs and a tail fin. Swimming and flying use a wave
 * travelling down the chain.
 *
 * <p>Bone names: {@code head, jaw, seg1..segN, fin_right/left (on seg1), tail_fin}. The head is the root's child at
 * {@link #height} above the ground; the chain trails behind it (+z).</p>
 */
public final class Serpent {
    public final String name;
    public int headW = 6;
    public int headH = 5;
    public int headL = 7;
    public int jawH = 2;
    public int segs = 6;
    public int segLen = 6;
    public int segW = 5;
    public int segH = 5;
    /** Last segment's size as a fraction of the first. */
    public float taper = 0.4f;
    /** Head height above the ground (pixels). */
    public float height = 6;
    public boolean fins = true;
    public int finLen = 6;
    public boolean tailFin = true;
    /** Dorsal spines on each segment. */
    public boolean spines = false;
    /** Waves sideways (snakes, eels) or up and down (whale-like). */
    public boolean lateral = true;
    public String skin = "scales";
    public String belly = "belly";
    public String headMat = null;
    public String finMat = "fin";
    public float scale = 1f;

    public Rig rig;
    public Bone head;
    public Bone jawBone;
    public Bone[] seg;

    public Serpent(String name) {
        this.name = name;
    }

    public Rig build() {
        rig = new Rig(name);
        String hm = headMat != null ? headMat : skin;
        head = rig.root().child("head", 0, -height, 0);
        head.box(hm, -headW / 2f, -headH / 2f, -headL, headW, headH - jawH + 1, headL).tag("head");
        jawBone = head.child("jaw", 0, headH / 2f - jawH, -1);
        jawBone.box(hm, -headW / 2f + 0.5f, 0, -headL + 1.5f, headW - 1, jawH, headL - 1).tag("jaw");
        seg = new Bone[segs];
        Bone parent = head;
        for (int i = 0; i < segs; i++) {
            float f = segs == 1 ? 0 : (float) i / (segs - 1);
            int w = Math.max(1, Math.round(segW * (1 - (1 - taper) * f)));
            int h = Math.max(1, Math.round(segH * (1 - (1 - taper) * f)));
            Bone s = i == 0 ? parent.child("seg1", 0, 0, 0) : parent.child("seg" + (i + 1), 0, 0, segLen - 0.5f);
            s.box(skin, -w / 2f, -h / 2f, 0, w, h, segLen).tag("segment");
            if (belly != null && h > 2) s.box(belly, -w / 2f + 0.5f, h / 2f - 1, 0.5f, Math.max(1, w - 1), 1, segLen - 1, 0.05f).tag("belly");
            if (spines && i < segs - 1) s.box(finMat, -0.5f, -h / 2f - 2, 1, 1, 2, Math.max(1, segLen - 2)).tag("spine");
            seg[i] = s;
            parent = s;
        }
        if (fins) {
            seg[0].pair("fin", segW / 2f, segH / 4f, 1, (b, side) -> {
                b.rot(0, -30, 20);
                b.sbox(finMat, -finLen, 0, 0, finLen, 0, finLen - 1).tag("fin");
            });
        }
        if (tailFin) {
            Bone tf = parent.child("tail_fin", 0, 0, segLen - 0.5f);
            int fh = Math.max(3, segH + 2);
            if (lateral) tf.box(finMat, 0, -fh, 0, 0, fh * 2, segLen).tag("tail_fin");
            else tf.box(finMat, -fh, 0, 0, fh * 2, 0, segLen).tag("tail_fin");
        }
        return rig;
    }

    public CreatureDef def() {
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.5f, 0.35f, 0.6f);
        def.look("head", 0.7f).look("seg1", -0.3f);
        Anim wave = travel(1.2f, 14f);
        def.anim("idle", travel(3f, 6f)).anim("walk", wave).anim("run", travel(0.7f, 20f)).anim("swim", wave).anim("fly", wave)
                .anim("hover", travel(2f, 9f)).anim("attack", strike()).anim("attack2", bite()).anim("special", rear())
                .anim("emerge", rear()).anim("death", death()).anim("hurt", hurt());
        return def;
    }

    /** A wave travelling from head to tail. */
    public Anim travel(float len, float amp) {
        Anim a = Anim.loop(len);
        int axis = lateral ? 1 : 0;
        for (int i = 0; i < seg.length; i++) {
            a.wave(seg[i].name, Anim.Target.ROTATION, axis, amp * (0.5f + i * 0.12f), -i * 0.14f, 0, 1);
        }
        a.wave("head", Anim.Target.ROTATION, axis, amp * 0.4f, 0.1f, 0, 1);
        if (fins) {
            a.swing("fin_right", 2, 15f, 0f);
            a.swing("fin_left", 2, -15f, 0f);
        }
        if (rig.has("tail_fin")) a.wave("tail_fin", Anim.Target.ROTATION, axis, amp * 1.4f, -seg.length * 0.14f, 0, 1);
        a.bob("head", 1, 0.6f, 0f, 1);
        return a;
    }

    /** Coils back and strikes forward. */
    public Anim strike() {
        Anim a = Anim.once(0.8f);
        a.pos("head", 0, 0, 0, 0, 0.3f, 0, 3, 4, 0.42f, 0, -1, -6, 0.8f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, -25, 0, 0, 0.42f, 15, 0, 0, 0.8f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.3f, 50, 0, 0, 0.4f, 50, 0, 0, 0.45f, 0, 0, 0, 0.8f, 0, 0, 0).smooth();
        for (int i = 0; i < Math.min(3, seg.length); i++) {
            a.rot(seg[i].name, 0, 0, 0, 0, 0.3f, 30 - i * 10, (i % 2 == 0 ? 20 : -20), 0, 0.42f, -10, 0, 0, 0.8f, 0, 0, 0);
        }
        return a;
    }

    public Anim bite() {
        Anim a = Anim.once(0.55f);
        a.rot("head", 0, 0, 0, 0, 0.2f, -15, 0, 0, 0.3f, 10, 0, 0, 0.55f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.2f, 45, 0, 0, 0.28f, 45, 0, 0, 0.33f, 0, 0, 0, 0.55f, 0, 0, 0).smooth();
        a.pos("head", 0, 0, 0, 0, 0.3f, 0, 0, -2, 0.55f, 0, 0, 0);
        return a;
    }

    /** Rears up (rising out of sand or water, or threatening). */
    public Anim rear() {
        Anim a = Anim.once(1.4f);
        a.rot("head", 0, 0, 0, 0, 0.5f, -20, 0, 0, 1.0f, -15, 0, 0, 1.4f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.5f, 40, 0, 0, 1.0f, 40, 0, 0, 1.4f, 0, 0, 0).smooth();
        if (seg.length > 0) a.rot(seg[0].name, 0, 0, 0, 0, 0.5f, 60, 0, 0, 1.0f, 55, 0, 0, 1.4f, 0, 0, 0);
        if (seg.length > 1) a.rot(seg[1].name, 0, 0, 0, 0, 0.5f, -30, 0, 0, 1.0f, -28, 0, 0, 1.4f, 0, 0, 0);
        a.pos("head", 0, 0, 0, 0, 0.5f, 0, segLen * 1.2f, segLen * 0.6f, 1.0f, 0, segLen * 1.2f, segLen * 0.6f, 1.4f, 0, 0, 0);
        return a;
    }

    public Anim death() {
        Anim a = Anim.once(1.2f);
        a.pos("head", 0, 0, 0, 0, 0.8f, 0, -height + headH / 2f, 0, 1.2f, 0, -height + headH / 2f, 0);
        for (int i = 0; i < seg.length; i++) a.rot(seg[i].name, 0, 0, 0, 0, 0.8f, 0, (i % 2 == 0 ? 25 : -25), 0, 1.2f, 0, (i % 2 == 0 ? 20 : -20), 0);
        a.rot("jaw", 0, 0, 0, 0, 0.8f, 30, 0, 0, 1.2f, 30, 0, 0);
        return a;
    }

    public Anim hurt() {
        Anim a = Anim.once(0.4f);
        a.rot("head", 0, 0, 0, 0, 0.1f, -20, 0, 0, 0.4f, 0, 0, 0);
        a.rot("seg1", 0, 0, 0, 0, 0.1f, 0, 15, 0, 0.4f, 0, 0, 0);
        return a;
    }
}
