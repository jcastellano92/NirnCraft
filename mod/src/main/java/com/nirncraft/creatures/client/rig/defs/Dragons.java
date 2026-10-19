package com.nirncraft.creatures.client.rig.defs;

import com.nirncraft.creatures.client.rig.Anim;
import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.client.rig.Rig;

/**
 * Skyrim's dragons, built like the game's: two powerful hind legs and wings that double as forelegs on the ground
 * (the dragon walks on its wing wrists), a long six-segment neck, a horned head with a toothed jaw, three-part
 * membrane wings and a long spiked tail. Built at half size (the renderer scales it by 2).
 *
 * <p>The rest pose has the wings spread (flight); ground animations fold them. Bones: {@code body, chest,
 * neck1..neck6, head, jaw, wing_right/left, wing_fore_right/left, wing_hand_right/left, leg_right/left,
 * shin_right/left, foot_right/left, tail1..tail8}.</p>
 */
final class Dragons {
    static final int NECK = 6;
    static final int TAIL = 8;

    private Dragons() {}

    static void register() {
        Defs.reg("dragon", Dragons::dragon);
    }

    static CreatureDef dragon() {
        Rig rig = new Rig("dragon");
        Bone body = rig.root().child("body", 0, -19, 4);
        body.box("scales", -6, -6, -16, 12, 11, 24).tag("torso");
        body.box("belly", -5, 4, -15, 10, 2, 22, 0.05f).tag("belly");
        for (int i = 0; i < 5; i++) body.box("horn", -0.5f, -9, -14 + i * 5, 1, 3, 3).tag("spike");
        Bone chest = body.child("chest", 0, -1, -14);
        chest.box("scales", -7, -6, -6, 14, 12, 9).tag("chest");
        chest.box("belly", -5.5f, 5, -5.5f, 11, 2, 8, 0.05f).tag("belly");

        // neck: six segments rising from the chest
        Bone parent = chest;
        for (int i = 0; i < NECK; i++) {
            int w = 6 - i / 2;
            Bone n = i == 0 ? parent.child("neck1", 0, -2, -5) : parent.child("neck" + (i + 1), 0, 0, -4.6f);
            n.rot(i == 0 ? -38 : 4, 0, 0);
            n.box("scales", -w / 2f, -w / 2f, -5, w, w, 5).tag("neck");
            n.box("belly", -w / 2f + 1, w / 2f - 0.6f, -5, w - 2, 1, 5, 0.05f).tag("neck_belly");
            n.box("horn", -0.5f, -w / 2f - 2, -3.5f, 1, 2, 2).tag("spike");
            parent = n;
        }
        Bone head = parent.child("head", 0, 0, -4.6f).rot(14, 0, 0);
        head.box("scales", -3.5f, -3.5f, -8, 7, 6, 8).tag("head");
        head.box("scales", -2.5f, -2, -14, 5, 4, 6).tag("snout");
        head.box("horn", -4, -4.5f, -6, 8, 2, 4).tag("brow");
        head.box("horn", -1, -2.6f, -13.5f, 2, 1, 4).tag("nose_ridge");
        head.pair("horn", 2.5f, -3.5f, -2, (b, side) -> {
            b.rot(28, -18, 0);
            b.sbox("horn", -1, -1, 0, 2, 2, 9).tag("horn");
            Bone tip = b.child("horn_tip", 0, 0, 8.5f).rot(18, 0, 0);
            tip.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 5).tag("horn_tip");
        });
        head.pair("horn_side", 3.5f, -1, -3, (b, side) -> {
            b.rot(10, -40, 0);
            b.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 6).tag("horn");
        });
        head.pair("cheek", 3.5f, 1, -4, (b, side) -> {
            b.rot(0, -25, 0);
            b.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 4).tag("horn");
        });
        Bone jaw = head.child("jaw", 0, 2.5f, -3);
        jaw.box("scales", -2.5f, 0, -11, 5, 2, 11).tag("jaw");
        jaw.box("tooth", -2, -1, -10.5f, 4, 1, 7).tag("teeth");
        head.box("tooth", -2, 2, -13.5f, 4, 1, 6).tag("teeth_upper");

        // wings: humerus, forearm, hand with finger spars; membranes trail behind each
        body.pair("wing", 5.5f, -5, -12, (b, side) -> {
            b.sbox("wingbone", -14, -1.5f, -1.5f, 14, 3, 3).tag("wing_arm");
            b.sbox("membrane", -14, 0, 1.5f, 14, 0, 14).tag("membrane_inner");
            Bone fore = b.child("wing_fore", -13.5f, 0, 0);
            fore.sbox("wingbone", -18, -1, -1, 18, 2, 2).tag("wing_arm");
            fore.sbox("membrane", -18, 0, 1, 18, 0, 20).tag("membrane_mid");
            fore.sbox("horn", -1.5f, -1, -3, 2, 2, 3).tag("thumb_claw");
            Bone hand = fore.child("wing_hand", -17.5f, 0, 0);
            hand.sbox("wingbone", -20, -0.5f, -0.5f, 20, 1, 1).tag("finger");
            hand.sbox("wingbone", -16, -0.5f, 5, 16, 1, 1).tag("finger");
            hand.sbox("wingbone", -11, -0.5f, 12, 11, 1, 1).tag("finger");
            hand.sbox("membrane", -20, 0, 0, 20, 0, 22).tag("membrane_outer");
        });

        // hind legs: thigh, shin, digitigrade foot with talons
        body.pair("leg", 5, 2, 3, (b, side) -> {
            b.rot(-20, 0, 0);
            b.sbox("scales", -3, -2, -3.5f, 6, 10, 7).tag("thigh");
            Bone shin = b.child("shin", 0, 7.5f, 0.5f).rot(48, 0, 0);
            shin.sbox("scales", -2, 0, -2, 4, 9, 4).tag("shin");
            Bone foot = shin.child("foot", 0, 8.5f, 0).rot(-28, 0, 0);
            foot.sbox("scales", -2.5f, 0, -4, 5, 2, 6).tag("foot");
            foot.sbox("horn", -2.5f, 1, -6, 5, 1, 2).tag("talons");
        });

        // tail: eight tapering segments with dorsal spikes and a spade at the end
        parent = body;
        for (int i = 0; i < TAIL; i++) {
            int w = Math.max(2, 9 - i);
            int h = Math.max(2, 8 - i);
            Bone t = i == 0 ? parent.child("tail1", 0, -2, 7.5f).rot(-8, 0, 0) : parent.child("tail" + (i + 1), 0, 0, 5.6f);
            if (i > 0) t.rot(2, 0, 0);
            t.box("scales", -w / 2f, -h / 2f, 0, w, h, 6).tag("tail");
            if (i < TAIL - 1) t.box("horn", -0.5f, -h / 2f - 2, 1.5f, 1, 2, 3).tag("spike");
            parent = t;
        }
        parent.box("horn", -4, -0.5f, 4, 8, 1, 5).tag("tail_spade");

        CreatureDef def = new CreatureDef(rig);
        def.gait(0.9f, 0.6f, 0.65f);
        def.look("head", 0.45f).look("neck6", 0.25f).look("neck4", 0.15f).look("neck2", 0.15f);
        def.anim("idle", idle()).anim("walk", walk()).anim("run", run()).anim("fly", glide()).anim("hover", flap())
                .anim("attack", bite()).anim("attack2", tailSwipe()).anim("attack3", wingBuffet()).anim("special", breath())
                .anim("shout", shout()).anim("roar", roar()).anim("land", land()).anim("takeoff", takeoff())
                .anim("sleep", sleep()).anim("death", death()).anim("hurt", hurt());
        return def;
    }

    // ------------------------------------------------------------------------------------------------ poses

    /** Wings folded into forelegs: humerus down-forward, forearm back, hand folded along it; wrists on the ground. */
    private static void folded(Anim a, float lift) {
        a.hold("wing_right", 0, 0, -78 + lift);
        a.hold("wing_left", 0, 0, 78 - lift);
        a.hold("wing_fore_right", 0, -60, 0);
        a.hold("wing_fore_left", 0, 60, 0);
        a.hold("wing_hand_right", 0, 160, 0);
        a.hold("wing_hand_left", 0, -160, 0);
        // the membranes crumple as the wing folds
        for (String side : new String[]{"_right", "_left"}) {
            a.scale("wing" + side, 0, 1, 1, 0.35f, a.length, 1, 1, 0.35f);
            a.scale("wing_fore" + side, 0, 1, 1, 0.3f, a.length, 1, 1, 0.3f);
            a.scale("wing_hand" + side, 0, 1, 1, 0.25f, a.length, 1, 1, 0.25f);
        }
    }

    private static void neckWave(Anim a, int axis, float amp, float phase, int cycles) {
        for (int i = 1; i <= NECK; i++) a.wave("neck" + i, Anim.Target.ROTATION, axis, amp, phase - i * 0.05f, 0, cycles);
    }

    private static void tailWave(Anim a, int axis, float amp, int cycles) {
        for (int i = 1; i <= TAIL; i++) a.wave("tail" + i, Anim.Target.ROTATION, axis, amp * (0.6f + i * 0.12f), -i * 0.1f, 0, cycles);
    }

    private static Anim idle() {
        Anim a = Anim.loop(5f);
        folded(a, 0);
        a.hold("body", -6, 0, 0);
        a.hold("neck1", 4, 0, 0);
        a.bob("body", 1, 0.35f, 0f, 1);
        a.scale("chest", 0, 1, 1, 1, 1.25f, 1.03f, 1.04f, 1, 2.5f, 1, 1, 1, 3.75f, 1.03f, 1.04f, 1, 5f, 1, 1, 1);
        neckWave(a, 1, 3f, 0f, 1);
        a.swing("head", 1, 10f, 0.2f);
        a.rot("jaw", 0, 0, 0, 0, 3.0f, 0, 0, 0, 3.3f, 12, 0, 0, 3.8f, 0, 0, 0, 5f, 0, 0, 0);
        tailWave(a, 1, 4f, 1);
        return a;
    }

    /** Hind legs and wing-forelegs alternate (a four-beat lumber). */
    private static Anim walk() {
        Anim a = Anim.loop(1.6f);
        folded(a, 0);
        a.hold("body", -6, 0, 0);
        a.swing("leg_right", 0, 24f, 0f);
        a.swing("leg_left", 0, 24f, 0.5f);
        a.swing("shin_right", 0, 14f, 0.1f);
        a.swing("shin_left", 0, 14f, 0.6f);
        a.swing("wing_right", 1, 18f, 0.5f);
        a.swing("wing_left", 1, -18f, 0f);
        a.swing("wing_right", 2, 8f, 0.75f);
        a.swing("wing_left", 2, -8f, 0.25f);
        a.bob("body", 1, 0.8f, 0.25f, 2);
        a.swing("body", 2, 3f, 0f);
        neckWave(a, 1, 4f, 0.2f, 1);
        neckWave(a, 0, 2f, 0f, 2);
        tailWave(a, 1, 6f, 1);
        return a;
    }

    private static Anim run() {
        Anim a = Anim.loop(0.9f);
        folded(a, 6);
        a.hold("body", -2, 0, 0);
        a.swing("leg_right", 0, 38f, 0f);
        a.swing("leg_left", 0, 38f, 0.5f);
        a.swing("shin_right", 0, 22f, 0.1f);
        a.swing("shin_left", 0, 22f, 0.6f);
        a.swing("wing_right", 1, 28f, 0.5f);
        a.swing("wing_left", 1, -28f, 0f);
        a.bob("body", 1, 1.4f, 0.25f, 2);
        neckWave(a, 0, 3f, 0f, 2);
        tailWave(a, 1, 8f, 1);
        return a;
    }

    /** Fast flight: wings outstretched, a slow beat, legs tucked, tail streaming. */
    private static Anim glide() {
        Anim a = Anim.loop(2.4f);
        a.swing("wing_right", 2, 12f, 0f, 4f);
        a.swing("wing_left", 2, -12f, 0f, -4f);
        a.swing("wing_fore_right", 2, 6f, -0.1f);
        a.swing("wing_fore_left", 2, -6f, -0.1f);
        a.swing("wing_hand_right", 2, 5f, -0.2f);
        a.swing("wing_hand_left", 2, -5f, -0.2f);
        tucked(a);
        a.bob("body", 1, 1.2f, 0.25f, 1);
        neckWave(a, 0, 1.5f, 0.1f, 1);
        for (int i = 1; i <= NECK; i++) a.hold("neck" + i, i == 1 ? 30 : -3, 0, 0);
        a.hold("head", -14, 0, 0);
        tailWave(a, 1, 3f, 1);
        return a;
    }

    /** Hovering, landing or slow flight: deep, powerful wingbeats. */
    private static Anim flap() {
        Anim a = Anim.loop(1.1f);
        a.swing("wing_right", 2, 48f, 0f, 6f);
        a.swing("wing_left", 2, -48f, 0f, -6f);
        a.swing("wing_right", 1, 14f, 0.25f);
        a.swing("wing_left", 1, -14f, 0.25f);
        a.swing("wing_fore_right", 2, 26f, -0.1f);
        a.swing("wing_fore_left", 2, -26f, -0.1f);
        a.swing("wing_hand_right", 2, 22f, -0.2f);
        a.swing("wing_hand_left", 2, -22f, -0.2f);
        tucked(a);
        a.hold("body", -12, 0, 0);
        a.bob("body", 1, 2.5f, 0.5f, 1);
        for (int i = 1; i <= NECK; i++) a.hold("neck" + i, i == 1 ? 20 : -2, 0, 0);
        tailWave(a, 0, 4f, 1);
        return a;
    }

    private static void tucked(Anim a) {
        a.hold("leg_right", 55, 0, 0);
        a.hold("leg_left", 55, 0, 0);
        a.hold("shin_right", 30, 0, 0);
        a.hold("shin_left", 30, 0, 0);
        a.hold("foot_right", 40, 0, 0);
        a.hold("foot_left", 40, 0, 0);
    }

    // ------------------------------------------------------------------------------------------------ actions

    private static Anim bite() {
        Anim a = Anim.once(0.8f);
        a.rot("neck1", 0, 0, 0, 0, 0.28f, -15, 0, 0, 0.42f, 28, 0, 0, 0.8f, 0, 0, 0);
        a.rot("neck3", 0, 0, 0, 0, 0.28f, -10, 0, 0, 0.42f, 14, 0, 0, 0.8f, 0, 0, 0);
        a.rot("neck5", 0, 0, 0, 0, 0.28f, -8, 0, 0, 0.42f, 10, 0, 0, 0.8f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.28f, -12, 0, 0, 0.42f, 12, 0, 0, 0.8f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.26f, 40, 0, 0, 0.38f, 40, 0, 0, 0.44f, 0, 0, 0, 0.8f, 0, 0, 0).smooth();
        a.pos("body", 0, 0, 0, 0, 0.28f, 0, 0, 1.5f, 0.42f, 0, -0.6f, -2.5f, 0.8f, 0, 0, 0);
        return a;
    }

    /** Swings round and sweeps the tail across. */
    private static Anim tailSwipe() {
        Anim a = Anim.once(1.1f);
        a.rot("body", 0, 0, 0, 0, 0.35f, 0, 25, 0, 0.6f, 0, -35, 0, 1.1f, 0, 0, 0);
        for (int i = 1; i <= TAIL; i++) {
            a.rot("tail" + i, 0, 0, 0, 0, 0.35f, 0, 8, 0, 0.55f + i * 0.02f, -2, -12 - i, 0, 1.1f, 0, 0, 0);
        }
        a.rot("neck1", 0, 0, 0, 0, 0.6f, 0, 20, 0, 1.1f, 0, 0, 0);
        return a;
    }

    /** Raises a wing and slams it down / forward. */
    private static Anim wingBuffet() {
        Anim a = Anim.once(0.9f);
        a.rot("wing_right", 0, 0, 0, 0, 0.3f, 0, -20, 60, 0.45f, 0, 30, -15, 0.9f, 0, 0, 0);
        a.rot("wing_fore_right", 0, 0, 0, 0, 0.3f, 0, 60, 0, 0.45f, 0, 0, 0, 0.9f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.3f, -6, 10, 0, 0.45f, 4, -10, 0, 0.9f, 0, 0, 0);
        return a;
    }

    /** Breath weapon: rears the neck, then thrusts it forward with the jaw wide open and holds. */
    private static Anim breath() {
        Anim a = Anim.once(2.4f);
        a.rot("neck1", 0, 0, 0, 0, 0.4f, -25, 0, 0, 0.7f, 15, 0, 0, 2.0f, 15, 0, 0, 2.4f, 0, 0, 0);
        a.rot("neck4", 0, 0, 0, 0, 0.4f, -10, 0, 0, 0.7f, 8, 0, 0, 2.0f, 8, 0, 0, 2.4f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -20, 0, 0, 0.7f, 6, 0, 0, 2.0f, 6, 0, 0, 2.4f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.4f, 10, 0, 0, 0.7f, 42, 0, 0, 2.0f, 40, 0, 0, 2.4f, 0, 0, 0);
        a.scale("chest", 0, 1, 1, 1, 0.4f, 1.08f, 1.1f, 1, 0.8f, 1, 1, 1, 2.4f, 1, 1, 1);
        return a;
    }

    /** A Thu'um: the neck draws back, then snaps forward as the Word is spoken. */
    private static Anim shout() {
        Anim a = Anim.once(1.3f);
        a.rot("neck1", 0, 0, 0, 0, 0.45f, -30, 0, 0, 0.6f, 20, 0, 0, 1.0f, 15, 0, 0, 1.3f, 0, 0, 0);
        a.rot("neck3", 0, 0, 0, 0, 0.45f, -12, 0, 0, 0.6f, 10, 0, 0, 1.3f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.45f, -25, 0, 0, 0.6f, 10, 0, 0, 1.3f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.45f, 8, 0, 0, 0.55f, 45, 0, 0, 0.95f, 40, 0, 0, 1.3f, 0, 0, 0).smooth();
        a.pos("body", 0, 0, 0, 0, 0.45f, 0, 0, 2, 0.6f, 0, 0, -1.5f, 1.3f, 0, 0, 0);
        return a;
    }

    private static Anim roar() {
        Anim a = Anim.once(2.0f);
        a.rot("neck1", 0, 0, 0, 0, 0.4f, -30, 0, 0, 1.6f, -30, 0, 0, 2.0f, 0, 0, 0);
        a.rot("neck5", 0, 0, 0, 0, 0.4f, -10, 0, 0, 1.6f, -10, 0, 0, 2.0f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -25, 0, 0, 1.0f, -25, 12, 0, 1.6f, -25, -12, 0, 2.0f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.4f, 45, 0, 0, 1.6f, 42, 0, 0, 2.0f, 0, 0, 0);
        a.rot("wing_right", 0, 0, 0, 0, 0.4f, 0, -10, 30, 1.6f, 0, -10, 30, 2.0f, 0, 0, 0);
        a.rot("wing_left", 0, 0, 0, 0, 0.4f, 0, 10, -30, 1.6f, 0, 10, -30, 2.0f, 0, 0, 0);
        return a;
    }

    /** Touchdown: wings flared, legs absorb the impact. */
    private static Anim land() {
        Anim a = Anim.once(1.0f);
        a.rot("wing_right", 0, 0, 0, 40, 0.3f, 0, 0, 30, 1.0f, 0, 0, 0);
        a.rot("wing_left", 0, 0, 0, -40, 0.3f, 0, 0, -30, 1.0f, 0, 0, 0);
        a.pos("body", 0, 0, 2, 0, 0.25f, 0, -3, 0, 0.6f, 0, -1, 0, 1.0f, 0, 0, 0);
        a.rot("leg_right", 0, -30, 0, 0, 0.25f, -35, 0, 0, 1.0f, 0, 0, 0);
        a.rot("leg_left", 0, -30, 0, 0, 0.25f, -35, 0, 0, 1.0f, 0, 0, 0);
        a.rot("shin_right", 0, 0, 0, 0, 0.25f, 30, 0, 0, 1.0f, 0, 0, 0);
        a.rot("shin_left", 0, 0, 0, 0, 0.25f, 30, 0, 0, 1.0f, 0, 0, 0);
        return a;
    }

    /** Crouch, leap, a great downbeat. */
    private static Anim takeoff() {
        Anim a = Anim.once(1.2f);
        a.pos("body", 0, 0, 0, 0, 0.35f, 0, -3, 0, 0.6f, 0, 4, 0, 1.2f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.35f, 6, 0, 0, 0.6f, -20, 0, 0, 1.2f, 0, 0, 0);
        a.rot("wing_right", 0, 0, 0, 0, 0.35f, 0, 0, 55, 0.6f, 0, 0, -40, 1.2f, 0, 0, 0);
        a.rot("wing_left", 0, 0, 0, 0, 0.35f, 0, 0, -55, 0.6f, 0, 0, 40, 1.2f, 0, 0, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.35f, -30, 0, 0, 0.6f, 30, 0, 0, 1.2f, 0, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.35f, -30, 0, 0, 0.6f, 30, 0, 0, 1.2f, 0, 0, 0);
        return a;
    }

    /** Resting on a perch: lying down, neck curled round, tail wrapped. */
    private static Anim sleep() {
        Anim a = Anim.loop(6f);
        folded(a, 10);
        a.pos("body", 0, 0, -10, 0, 6f, 0, -10, 0);
        a.bob("body", 1, 0.4f, 0f, 1);
        a.hold("leg_right", -70, 0, 20);
        a.hold("leg_left", -70, 0, -20);
        a.hold("shin_right", 100, 0, 0);
        a.hold("shin_left", 100, 0, 0);
        a.hold("neck1", 20, 25, 0);
        for (int i = 2; i <= NECK; i++) a.hold("neck" + i, 4, 12, 0);
        a.hold("head", 10, 20, 0);
        for (int i = 1; i <= TAIL; i++) a.hold("tail" + i, 3, i < 3 ? 6 : 12, 0);
        return a;
    }

    /** Crashes down: legs give, wings sprawl, the neck falls. */
    private static Anim death() {
        Anim a = Anim.once(1.6f);
        a.pos("body", 0, 0, 0, 0, 0.8f, 0, -12, 0, 1.6f, 0, -12, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.8f, -60, 0, 25, 1.6f, -60, 0, 25);
        a.rot("leg_left", 0, 0, 0, 0, 0.8f, -60, 0, -25, 1.6f, -60, 0, -25);
        a.rot("shin_right", 0, 0, 0, 0, 0.8f, 80, 0, 0, 1.6f, 80, 0, 0);
        a.rot("shin_left", 0, 0, 0, 0, 0.8f, 80, 0, 0, 1.6f, 80, 0, 0);
        a.rot("wing_right", 0, 0, 0, 0, 0.8f, 0, 25, -12, 1.6f, 0, 25, -12);
        a.rot("wing_left", 0, 0, 0, 0, 0.8f, 0, -25, 12, 1.6f, 0, -25, 12);
        a.rot("wing_fore_right", 0, 0, 0, 0, 0.8f, 0, 20, 8, 1.6f, 0, 20, 8);
        a.rot("wing_fore_left", 0, 0, 0, 0, 0.8f, 0, -20, -8, 1.6f, 0, -20, -8);
        a.rot("neck1", 0, 0, 0, 0, 0.9f, 35, 15, 0, 1.6f, 38, 18, 0);
        a.rot("neck3", 0, 0, 0, 0, 0.9f, 8, 8, 0, 1.6f, 8, 8, 0);
        a.rot("head", 0, 0, 0, 0, 0.9f, 10, 15, 25, 1.6f, 10, 15, 25);
        a.rot("jaw", 0, 0, 0, 0, 0.9f, 25, 0, 0, 1.6f, 25, 0, 0);
        for (int i = 1; i <= TAIL; i++) a.rot("tail" + i, 0, 0, 0, 0, 1.2f, 4, 6, 0, 1.6f, 4, 6, 0);
        return a;
    }

    private static Anim hurt() {
        Anim a = Anim.once(0.5f);
        a.rot("neck1", 0, 0, 0, 0, 0.12f, -12, 0, 0, 0.5f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.12f, -15, 0, 0, 0.5f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.12f, 25, 0, 0, 0.5f, 0, 0, 0);
        return a;
    }
}
