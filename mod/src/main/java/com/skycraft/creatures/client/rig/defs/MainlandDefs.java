package com.skycraft.creatures.client.rig.defs;

import com.skycraft.creatures.client.rig.Anim;
import com.skycraft.creatures.client.rig.Biped;
import com.skycraft.creatures.client.rig.Bone;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.client.rig.Quad;
import com.skycraft.creatures.client.rig.Rig;
import com.skycraft.creatures.client.rig.Theropod;
import com.skycraft.creatures.client.rig.Winged;

/** Creatures of Morrowind, Cyrodiil, Black Marsh and Elsweyr. */
final class MainlandDefs {
    private MainlandDefs() {}

    private static final Anim.Target ROT = Anim.Target.ROTATION;

    static void register() {
        Defs.reg("netch", MainlandDefs::netch);
        Defs.reg("cliff_racer", MainlandDefs::cliffRacer);
        Defs.reg("guar", MainlandDefs::guar);
        Defs.reg("kagouti", MainlandDefs::kagouti);
        Defs.reg("alit", MainlandDefs::alit);
        Defs.reg("nix_hound", MainlandDefs::nixHound);
        Defs.reg("silt_strider", MainlandDefs::siltStrider);
        Defs.reg("minotaur", MainlandDefs::minotaur);
        Defs.reg("ogre", MainlandDefs::ogre);
        Defs.reg("land_dreugh", MainlandDefs::landDreugh);
        Defs.reg("wamasu", MainlandDefs::wamasu);
        Defs.reg("swamp_leviathan", MainlandDefs::swampLeviathan);
        Defs.reg("hackwing", MainlandDefs::hackwing);
        Defs.reg("senche_tiger", MainlandDefs::sencheTiger);
        Defs.reg("alfiq", MainlandDefs::alfiq);
    }

    // ============================================================================================ helpers

    /** Rotation keys on {@code <base>_right} and the mirrored keys (y, z negated) on {@code <base>_left}. */
    private static void rotLR(Anim a, String base, float... keys) {
        a.rot(base + "_right", keys);
        float[] m = keys.clone();
        for (int i = 0; i < m.length; i += 4) {
            m[i + 2] = -m[i + 2];
            m[i + 3] = -m[i + 3];
        }
        a.rot(base + "_left", m);
    }

    /** A rotation wave on both sides of a pair (y/z axes mirrored on the left), optionally half a cycle apart. */
    private static void waveLR(Anim a, String base, int axis, float amp, float phase, float offset, int cycles, boolean alternate) {
        a.wave(base + "_right", ROT, axis, amp, phase, offset, cycles);
        float sg = axis == 0 ? 1 : -1;
        a.wave(base + "_left", ROT, axis, amp * sg, phase + (alternate ? 0.5f : 0f), offset * sg, cycles);
    }

    private static String[] names(Bone[] bones) {
        String[] n = new String[bones.length];
        for (int i = 0; i < bones.length; i++) n[i] = bones[i].name;
        return n;
    }

    // ============================================================================================ netch

    private static final String[] NETCH_CENTRE = {"tent_fore", "tent_rear"};
    private static final String[] NETCH_PAIRS = {"tent_front", "tent_mid", "tent_back"};

    /**
     * Netch: a huge leathery gas bladder (domed, with hard plates on top for the bull) floating over a ring of thick
     * dangling tentacles. Built for renderer scale 1.5. The {@code plates} bone carries every armour plate.
     */
    static CreatureDef netch() {
        Rig rig = new Rig("netch");
        // body pivot = bottom of the bladder, 14 px above the ground; tentacle tips just reach the ground
        Bone body = rig.root().child("body", 0, -14, 0);
        Bone bladder = body.child("bladder", 0, 0, 0);
        bladder.box("hide", -7, -2, -8, 14, 2, 16).tag("dome_low");
        bladder.box("hide", -9, -8, -10, 18, 6, 20).tag("dome");
        bladder.box("hide", -10, -7, -8, 20, 4, 16).tag("dome_side");
        bladder.box("hide", -7, -7, -11, 14, 4, 22).tag("dome_end");
        bladder.box("hide", -8, -10, -9, 16, 2, 18).tag("dome_mid");
        bladder.box("hide", -6, -12, -7, 12, 2, 14).tag("dome_top");
        bladder.box("hide", -4, -13, -5, 8, 1, 10).tag("dome_cap");
        Bone plates = bladder.child("plates", 0, 0, 0);
        plates.box("plate", -3.5f, -14, -8, 7, 2, 5).tag("plate");
        plates.box("plate", -4.5f, -14.5f, -3.5f, 9, 2, 6).tag("plate");
        plates.box("plate", -3.5f, -14, 2, 7, 2, 5).tag("plate");
        plates.box("plate", -2.5f, -13, 6.5f, 5, 2, 3).tag("plate");
        plates.pair("plate_side", 5, -12, 0, (b, s) -> {
            b.rot(0, 0, -40);
            b.sbox("plate", -6, -1.5f, -6, 6, 2, 5).tag("plate");
            b.sbox("plate", -6, -1, -0.5f, 6, 2, 6).tag("plate");
        });
        Bone mantle = body.child("mantle", 0, 0, 0);
        mantle.box("skin", -5, 0, -6, 10, 2, 12).tag("mantle");
        mantle.box("skin", -7, -0.5f, -8, 14, 1, 16).tag("skirt");
        body.box("skin", -4, -3, -10.5f, 8, 3, 2).tag("face");

        netchTentacle(body.child("tent_fore", 0, 2, -5.5f), "tent_fore_tip", -10, 0, 5);
        netchTentacle(body.child("tent_rear", 0, 2, 5.5f), "tent_rear_tip", 8, 0, 5);
        body.pair("tent_front", 3.5f, 2, -4, (b, s) -> netchTentacle(b, "tent_front_tip", -6, 10, 5));
        body.pair("tent_mid", 5, 2, 0, (b, s) -> netchTentacle(b, "tent_mid_tip", 2, 14, 4));
        body.pair("tent_back", 3.5f, 2, 4, (b, s) -> netchTentacle(b, "tent_back_tip", 8, 9, 5));

        CreatureDef def = new CreatureDef(rig);
        def.gait(0.9f, 0.6f, 0.8f);
        def.anim("idle", netchFloat(4f, 1f, 0f));
        def.anim("hover", netchFloat(3f, 1.2f, 0f));
        def.anim("walk", netchDrift(3f, 4f, 14f));
        def.anim("fly", netchDrift(2.4f, 7f, 28f));
        def.anim("attack", netchLash());
        def.anim("special", netchSpray());
        def.anim("death", netchDeath());
        def.anim("hurt", netchHurt());
        return def;
    }

    private static void netchTentacle(Bone b, String tipName, float pitch, float splay, int mid) {
        b.rot(pitch, 0, splay);
        b.sbox("tentacle", -1.5f, -0.5f, -1.5f, 3, 5, 3).tag("tentacle");
        Bone tip = b.child(tipName, 0, 4.5f, 0).rot(-pitch * 0.6f, 0, -splay * 0.7f);
        tip.sbox("tentacle", -1, 0, -1, 2, mid, 2).tag("tentacle_tip");
        tip.sbox("tentacle", -0.5f, mid - 0.5f, -0.5f, 1, 3, 1).tag("tentacle_end");
    }

    /** Every tentacle sways with its own phase; {@code trail} bends them backwards (positive = tips trail behind). */
    private static void netchSway(Anim a, float amp, int cycles, float trail) {
        float ph = 0;
        for (String n : NETCH_CENTRE) {
            a.wave(n, ROT, 0, amp, ph, trail, cycles);
            a.wave(n + "_tip", ROT, 0, amp * 1.6f, ph - 0.15f, trail * 0.5f, cycles);
            ph += 0.37f;
        }
        for (String n : NETCH_PAIRS) {
            for (int s = 0; s < 2; s++) {
                String sfx = s == 0 ? "_right" : "_left";
                float sg = s == 0 ? 1 : -1;
                a.wave(n + sfx, ROT, 0, amp, ph, trail, cycles);
                a.wave(n + sfx, ROT, 2, amp * 0.6f * sg, ph + 0.25f, 0, cycles);
                a.wave(n + "_tip" + sfx, ROT, 0, amp * 1.6f, ph - 0.15f, trail * 0.5f, cycles);
                ph += 0.29f;
            }
        }
    }

    private static Anim netchFloat(float len, float bob, float trail) {
        Anim a = Anim.loop(len);
        a.bob("body", 1, bob, 0f, 1);
        a.swing("body", 2, 2f, 0.3f);
        a.swing("body", 0, 1.5f, 0.1f);
        a.scale("bladder", 0, 1, 1, 1, len * 0.5f, 1.035f, 1.06f, 1.035f, len, 1, 1, 1);
        a.scale("mantle", 0, 1, 1, 1, len * 0.5f, 0.96f, 1, 0.96f, len, 1, 1, 1);
        netchSway(a, 9f, 1, trail);
        return a;
    }

    private static Anim netchDrift(float len, float pitch, float trail) {
        Anim a = Anim.loop(len);
        a.hold("body", pitch, 0, 0);
        a.bob("body", 1, 1.2f, 0f, 1);
        a.swing("body", 2, 3f, 0.25f);
        a.scale("bladder", 0, 1, 1, 1, len * 0.5f, 1.03f, 1.05f, 1.03f, len, 1, 1, 1);
        netchSway(a, 10f, 1, trail);
        return a;
    }

    /** The front tentacles rear back and lash forward. */
    private static Anim netchLash() {
        Anim a = Anim.once(0.85f);
        a.rot("body", 0, 0, 0, 0, 0.2f, -10, 0, 0, 0.38f, 12, 0, 0, 0.85f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.2f, 0, 1, 1, 0.38f, 0, -0.5f, -2.5f, 0.85f, 0, 0, 0);
        a.scale("bladder", 0, 1, 1, 1, 0.2f, 0.97f, 1.05f, 0.97f, 0.38f, 1.05f, 0.94f, 1.05f, 0.85f, 1, 1, 1);
        a.rot("tent_fore", 0, 0, 0, 0, 0.2f, 50, 0, 0, 0.38f, -95, 0, 0, 0.55f, -70, 0, 0, 0.85f, 0, 0, 0);
        a.rot("tent_fore_tip", 0, 0, 0, 0, 0.2f, 30, 0, 0, 0.38f, -40, 0, 0, 0.55f, -10, 0, 0, 0.85f, 0, 0, 0);
        rotLR(a, "tent_front", 0, 0, 0, 0, 0.2f, 45, 0, 10, 0.38f, -85, 0, -8, 0.55f, -60, 0, 0, 0.85f, 0, 0, 0);
        rotLR(a, "tent_front_tip", 0, 0, 0, 0, 0.2f, 25, 0, 0, 0.38f, -35, 0, 0, 0.55f, -10, 0, 0, 0.85f, 0, 0, 0);
        for (String n : new String[]{"tent_mid", "tent_back"}) {
            rotLR(a, n, 0, 0, 0, 0, 0.38f, 22, 0, 10, 0.85f, 0, 0, 0);
            rotLR(a, n + "_tip", 0, 0, 0, 0, 0.45f, 20, 0, 0, 0.85f, 0, 0, 0);
        }
        a.rot("tent_rear", 0, 0, 0, 0, 0.38f, 25, 0, 0, 0.85f, 0, 0, 0);
        return a;
    }

    /** The bladder squeezes and the tentacles flare open as a cloud of poison bursts out underneath. */
    private static Anim netchSpray() {
        Anim a = Anim.once(1.7f);
        a.pos("body", 0, 0, 0, 0, 0.3f, 0, -1.5f, 0, 0.5f, 0, 2.5f, 0, 1.2f, 0, 1.5f, 0, 1.7f, 0, 0, 0);
        a.scale("bladder", 0, 1, 1, 1, 0.3f, 1.1f, 0.84f, 1.1f, 0.45f, 0.9f, 1.12f, 0.9f, 0.7f, 0.94f, 1.05f, 0.94f,
                1.7f, 1, 1, 1);
        a.scale("mantle", 0, 1, 1, 1, 0.3f, 0.9f, 1, 0.9f, 0.45f, 1.25f, 1.4f, 1.25f, 1.0f, 1.15f, 1.2f, 1.15f, 1.7f, 1, 1, 1);
        a.rot("tent_fore", 0, 0, 0, 0, 0.3f, 15, 0, 0, 0.45f, -55, 0, 0, 1.1f, -45, 0, 0, 1.7f, 0, 0, 0);
        a.rot("tent_rear", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.45f, 55, 0, 0, 1.1f, 45, 0, 0, 1.7f, 0, 0, 0);
        a.rot("tent_fore_tip", 0, 0, 0, 0, 0.45f, -25, 0, 0, 1.1f, -20, 0, 0, 1.7f, 0, 0, 0);
        a.rot("tent_rear_tip", 0, 0, 0, 0, 0.45f, 25, 0, 0, 1.1f, 20, 0, 0, 1.7f, 0, 0, 0);
        float[] pitch = {-25, 0, 25};
        for (int i = 0; i < NETCH_PAIRS.length; i++) {
            String n = NETCH_PAIRS[i];
            float p = pitch[i];
            rotLR(a, n, 0, 0, 0, 0, 0.3f, -p * 0.3f, 0, -10, 0.45f, p, 0, 55, 1.1f, p * 0.8f, 0, 45, 1.7f, 0, 0, 0);
            rotLR(a, n + "_tip", 0, 0, 0, 0, 0.45f, 0, 0, 25, 1.1f, 0, 0, 20, 1.7f, 0, 0, 0);
        }
        return a;
    }

    /** The bladder deflates and the netch sinks, tentacles sprawling. */
    private static Anim netchDeath() {
        Anim a = Anim.once(1f);
        a.scale("bladder", 0, 1, 1, 1, 0.7f, 1.12f, 0.62f, 1.12f, 1f, 1.14f, 0.58f, 1.14f);
        a.pos("body", 0, 0, 0, 0, 0.7f, 0, -8, 0, 1f, 0, -8, 0);
        a.rot("body", 0, 0, 0, 0, 0.7f, 6, 0, 8, 1f, 6, 0, 8);
        a.rot("tent_fore", 0, 0, 0, 0, 0.7f, -70, 0, 0, 1f, -72, 0, 0);
        a.rot("tent_rear", 0, 0, 0, 0, 0.7f, 70, 0, 0, 1f, 72, 0, 0);
        float[] pitch = {-30, 0, 30};
        for (int i = 0; i < NETCH_PAIRS.length; i++) {
            rotLR(a, NETCH_PAIRS[i], 0, 0, 0, 0, 0.7f, pitch[i], 0, 65, 1f, pitch[i], 0, 68);
            rotLR(a, NETCH_PAIRS[i] + "_tip", 0, 0, 0, 0, 0.7f, 0, 0, -15, 1f, 0, 0, -18);
        }
        return a;
    }

    private static Anim netchHurt() {
        Anim a = Anim.once(0.5f);
        a.rot("body", 0, 0, 0, 0, 0.1f, -6, 0, 6, 0.5f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.1f, 0, 1.5f, 0, 0.5f, 0, 0, 0);
        a.scale("bladder", 0, 1, 1, 1, 0.1f, 1.06f, 0.9f, 1.06f, 0.3f, 0.98f, 1.03f, 0.98f, 0.5f, 1, 1, 1);
        for (String n : NETCH_PAIRS) rotLR(a, n, 0, 0, 0, 0, 0.12f, 18, 0, 8, 0.5f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ cliff racer

    /** Cliff racer: long thin body, toothy beak-snout, leathery wings, tall dorsal sail, spiked tail fin. */
    static CreatureDef cliffRacer() {
        Winged w = new Winged("cliff_racer");
        w.bodyW = 4;
        w.bodyH = 4;
        w.bodyL = 12;
        w.neckLen = 4;
        w.neckW = 3;
        w.headW = 3;
        w.headH = 3;
        w.headL = 4;
        w.jawL = 9;
        w.wingSpan = 32;
        w.wingChord = 9;
        w.tailSegs = 4;
        w.tailLen = 18;
        w.tailW = 2;
        w.stand = 6;
        w.skin = "skin";
        w.wingMat = "membrane";
        w.flap = 45;
        w.build();
        Rig r = w.rig;
        w.head.box("skin", -1, -0.5f, -10, 2, 1, 6).tag("beak");
        w.head.box("crest", -0.5f, -3, -3, 1, 2, 6).tag("crest");
        Bone sail = w.body.child("sail", 0, -2, -1);
        sail.box("sail", 0, -7, -4, 0, 7, 3).tag("sail");
        sail.box("sail", 0, -6, -1, 0, 6, 3).tag("sail");
        sail.box("sail", 0, -4, 2, 0, 4, 4).tag("sail");
        sail.box("spine", -0.5f, -8, -4.5f, 1, 8, 1).tag("spine");
        Bone fin = r.bone("tail_fin");
        fin.box("sail", 0, -3, 0, 0, 6, 4).tag("tail_fin_v");
        fin.box("spike", -0.5f, -0.5f, 3, 1, 1, 3).tag("spike");
        fin.box("spike", -3.5f, -0.5f, 2.5f, 1, 1, 2).tag("spike");
        fin.box("spike", 2.5f, -0.5f, 2.5f, 1, 1, 2).tag("spike");

        CreatureDef def = w.def();
        String[] tail = names(w.tail);
        Anim strike = Anim.once(0.8f);
        strike.rot("body", 0, 0, 0, 0, 0.2f, 22, 0, 0, 0.4f, 12, 0, 0, 0.8f, 0, 0, 0);
        rotLR(strike, "wing", 0, 0, 0, 0, 0.2f, 0, 45, -15, 0.45f, 0, 40, -10, 0.8f, 0, 0, 0);
        for (int i = 0; i < tail.length; i++) {
            strike.rot(tail[i], 0, 0, 0, 0, 0.2f, -12, 0, 0, 0.4f + i * 0.015f, 38, 0, 0, 0.55f, 20, 0, 0, 0.8f, 0, 0, 0);
        }
        strike.rot("head", 0, 0, 0, 0, 0.2f, -15, 0, 0, 0.8f, 0, 0, 0);
        def.anim("attack", strike);
        def.anim("attack2", w.dive());
        Anim screech = Anim.once(1.3f);
        screech.rot("head", 0, 0, 0, 0, 0.3f, -35, 0, 0, 0.6f, -30, 8, 0, 0.9f, -32, -8, 0, 1.3f, 0, 0, 0);
        screech.rot("neck", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.9f, -15, 0, 0, 1.3f, 0, 0, 0);
        screech.rot("jaw", 0, 0, 0, 0, 0.3f, 50, 0, 0, 0.9f, 48, 0, 0, 1.3f, 0, 0, 0);
        rotLR(screech, "wing", 0, -40, 75, 25, 0.3f, -15, 25, 40, 0.9f, -15, 25, 45, 1.3f, -40, 75, 25);
        rotLR(screech, "wing_tip", 0, 0, 0, 165, 0.3f, 0, 0, -15, 0.9f, 0, 0, -20, 1.3f, 0, 0, 165);
        screech.rot("body", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.9f, -12, 0, 0, 1.3f, 0, 0, 0);
        screech.scale("sail", 0, 1, 1, 1, 0.3f, 1, 1.35f, 1, 0.9f, 1, 1.35f, 1, 1.3f, 1, 1, 1);
        def.anim("special", screech);
        def.anim("roar", screech);
        perched(def, tail);
        def.get("idle").scale("sail", 0, 1, 1, 1, 1.5f, 1, 1.12f, 1, 3f, 1, 1, 1);
        return def;
    }

    /** Perched idle and walk for {@link Winged} flyers: wings folded along the flanks instead of drooping. */
    private static void perched(CreatureDef def, String[] tail) {
        Anim idle = Anim.loop(3f);
        racerFold(idle);
        idle.swing("head", 1, 20f, 0.1f);
        idle.swing("neck", 0, 4f, 0.3f);
        idle.bob("body", 1, 0.3f, 0f, 1);
        for (int i = 0; i < tail.length; i++) idle.wave(tail[i], ROT, 1, 6f * (1 + i * 0.3f), -i * 0.12f, 0, 1);
        def.anim("idle", idle);
        Anim walk = Anim.loop(0.6f);
        racerFold(walk);
        walk.swing("leg_right", 0, 30f, 0f);
        walk.swing("leg_left", 0, 30f, 0.5f);
        walk.swing("body", 2, 5f, 0f);
        walk.bob("body", 1, 0.6f, 0.25f, 2);
        walk.swingN("head", 0, 6f, 0f, 2);
        for (int i = 0; i < tail.length; i++) walk.wave(tail[i], ROT, 1, 8f * (1 + i * 0.3f), -i * 0.12f, 0, 1);
        def.anim("walk", walk);
    }

    private static void racerFold(Anim a) {
        rotLR(a, "wing", 0, -40, 75, 25, a.length, -40, 75, 25);
        rotLR(a, "wing_tip", 0, 0, 0, 165, a.length, 0, 0, 165);
    }

    // ============================================================================================ guar

    /** Guar: stout bipedal pack lizard. Bones {@code saddle} and {@code packs} are for the pack variant only. */
    static CreatureDef guar() {
        Theropod t = new Theropod("guar");
        t.bodyW = 10;
        t.bodyH = 10;
        t.bodyL = 14;
        t.legLen = 11;
        t.legW = 5;
        t.neckLen = 3;
        t.neckW = 7;
        t.neckH = 7;
        t.neckPitch = -20;
        t.headW = 9;
        t.headH = 7;
        t.headL = 9;
        t.jawH = 3;
        t.armLen = 4;
        t.tailSegs = 4;
        t.tailLen = 16;
        t.tailW = 6;
        t.tailRaise = 8;
        t.skin = "hide";
        t.belly = "belly";
        t.stride = 28;
        t.scale = 1.1f;
        t.build();
        t.body.y += 1.2f; // plant the feet
        // blunt head: brow bumps over the small eyes, a nose pad and a ridge
        t.head.box("hide", -4.5f, -4.5f, -6, 3, 1, 3).tag("brow");
        t.head.box("hide", 1.5f, -4.5f, -6, 3, 1, 3).tag("brow");
        t.head.box("ridge", -1, -4.5f, -3, 2, 1, 4).tag("ridge");
        t.head.box("hide", -2.5f, -2.5f, -9.5f, 5, 2, 1).tag("nose");
        t.body.box("ridge", -1.5f, -6, -5, 3, 1, 4).tag("ridge");
        t.body.box("ridge", -1.5f, -6, 0, 3, 1, 4).tag("ridge");
        t.tail[0].box("ridge", -1, -4, 0, 2, 1, 3).tag("ridge");

        Bone saddle = t.body.child("saddle", 0, -5, -1);
        saddle.box("cloth", -5.5f, -0.5f, -4, 11, 1, 9).tag("blanket");
        saddle.box("cloth", -5.5f, 0.5f, -3, 1, 5, 7).tag("blanket_side");
        saddle.box("cloth", 4.5f, 0.5f, -3, 1, 5, 7).tag("blanket_side");
        saddle.box("leather", -4, -2, -2.5f, 8, 2, 6).tag("seat");
        saddle.box("leather", -3.5f, -4, 3, 7, 2, 1).tag("cantle");
        saddle.box("leather", -1, -4, -3, 2, 2, 1).tag("pommel");
        Bone packs = t.body.child("packs", 0, -2, 2);
        packs.pair("bag", 5.5f, 0, 0, (b, s) -> {
            b.rot(0, 0, 4);
            b.sbox("bag", -3, 0, -3, 3, 6, 6).tag("bag");
            b.sbox("leather", -3.5f, -0.5f, -3, 4, 2, 6).tag("bag_flap");
        });
        packs.box("cloth", -5, -6, 3, 10, 3, 3).tag("bedroll");

        CreatureDef def = t.def();
        Anim butt = Anim.once(0.75f);
        butt.rot("neck", 0, 0, 0, 0, 0.25f, 28, 0, 0, 0.38f, -12, 0, 0, 0.75f, 0, 0, 0);
        butt.rot("head", 0, 0, 0, 0, 0.25f, 18, 0, 0, 0.38f, -18, 0, 0, 0.75f, 0, 0, 0);
        butt.pos("body", 0, 0, 0, 0, 0.25f, 0, -0.5f, 1.5f, 0.38f, 0, 0.5f, -3.5f, 0.75f, 0, 0, 0);
        butt.rot("body", 0, 0, 0, 0, 0.25f, 8, 0, 0, 0.38f, -5, 0, 0, 0.75f, 0, 0, 0);
        butt.rot("leg_right", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.38f, 18, 0, 0, 0.75f, 0, 0, 0);
        butt.rot("leg_left", 0, 0, 0, 0, 0.3f, 10, 0, 0, 0.45f, 12, 0, 0, 0.75f, 0, 0, 0);
        def.anim("attack", butt);
        return def;
    }

    // ============================================================================================ kagouti

    /** Kagouti: compact, hunched Ashlands brute with a big low head, flat snout, two upward tusks, dorsal plates. */
    static CreatureDef kagouti() {
        Quad q = new Quad("kagouti");
        q.bodyW = 10;
        q.bodyH = 8;
        q.bodyL = 13;
        q.chestW = 11;
        q.chestH = 10;
        q.chestL = 7;
        q.legLen = 7;
        q.frontLegLen = 8;
        q.backLegLen = 7;
        q.legW = 4;
        q.neckSegs = 1;
        q.neckLen = 3;
        q.neckW = 8;
        q.neckH = 7;
        q.neckPitch = 18;
        q.headW = 10;
        q.headH = 8;
        q.headL = 6;
        q.snoutW = 9;
        q.snoutH = 4;
        q.snoutL = 5;
        q.earH = 0;
        q.tailSegs = 2;
        q.tailLen = 6;
        q.tailW = 3;
        q.tailDroop = 30;
        q.fur = "hide";
        q.belly = "belly";
        q.snoutMat = "muzzle";
        q.stride = 28;
        q.runStride = 40;
        q.gallop = false;
        q.scale = 0.85f;
        q.build();
        groundQuad(q, -8);
        // tusks: from the lower jaw, up past the snout, curving back and out
        q.jawBone.pair("tusk", 5.5f, 1, -6, (b, s) -> {
            b.rot(-12, 0, -10);
            b.sbox("tusk", -1, -6, -1, 2, 6, 2).tag("tusk");
            Bone tip = b.child("tusk_tip", 0, -5.5f, 0).rot(-30, 0, -8);
            tip.sbox("tusk", -0.5f, -3, -0.5f, 1, 3, 1).tag("tusk_tip");
        });
        // dorsal plates down the hump and back, each with a bony knob; a brow plate and cheek plates
        float top = -q.bodyH / 2f - (q.chestH - q.bodyH) * 0.7f;
        q.chest.box("plate", -5, top - 1.5f, -3.5f, 10, 2, 5).tag("plate");
        q.chest.box("spike", -1, top - 3, -2, 2, 2, 2).tag("spike");
        q.chest.box("plate", -6.5f, top + 0.5f, -2.5f, 3, 5, 4).tag("plate_side");
        q.chest.box("plate", 3.5f, top + 0.5f, -2.5f, 3, 5, 4).tag("plate_side");
        q.body.box("plate", -4.5f, -5.5f, -2, 9, 2, 4).tag("plate");
        q.body.box("spike", -1, -7, -1, 2, 2, 2).tag("spike");
        q.body.box("plate", -4, -5.3f, 2.5f, 8, 2, 4).tag("plate");
        q.body.box("spike", -0.5f, -6.5f, 3.5f, 1, 2, 2).tag("spike");
        q.neck[0].box("plate", -3.5f, -4.3f, -3, 7, 1, 4).tag("plate");
        q.head.box("plate", -4.5f, -4.5f, -5.5f, 9, 1, 5).tag("plate");
        q.head.box("hide", -5.5f, -2, -5, 1, 4, 4).tag("cheek");
        q.head.box("hide", 4.5f, -2, -5, 1, 4, 4).tag("cheek");
        q.snout.box("hide", -2.5f, -1.6f, -4.5f, 5, 2, 1).tag("nose");

        CreatureDef def = q.def();
        Anim charge = Anim.once(1.0f);
        charge.rot("neck1", 0, 0, 0, 0, 0.3f, 22, 0, 0, 0.6f, 22, 0, 0, 1f, 0, 0, 0);
        charge.rot("head", 0, 0, 0, 0, 0.3f, 18, 0, 0, 0.5f, 22, 0, 0, 0.62f, -8, 0, 0, 1f, 0, 0, 0);
        charge.rot("body", 0, 0, 0, 0, 0.3f, 6, 0, 0, 0.5f, 4, 0, 0, 1f, 0, 0, 0);
        charge.pos("body", 0, 0, 0, 0, 0.3f, 0, -1, 2, 0.5f, 0, -0.5f, -5, 0.65f, 0, 0, -4, 1f, 0, 0, 0);
        rotLR(charge, "leg_front", 0, 0, 0, 0, 0.3f, 20, 0, 0, 0.5f, -25, 0, 0, 1f, 0, 0, 0);
        rotLR(charge, "leg_back", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.5f, 30, 0, 0, 1f, 0, 0, 0);
        charge.rot("jaw", 0, 0, 0, 0, 0.5f, 12, 0, 0, 1f, 0, 0, 0);
        def.anim("attack2", charge);
        return def;
    }

    /**
     * Quad's paws end 1 px below the ground; this lifts the body so they stand on it, and optionally tilts the
     * torso (negative = front up, a hunch) with the legs and neck counter-rotated.
     */
    private static void groundQuad(Quad q, float tilt) {
        q.body.y -= 1;
        if (tilt != 0) {
            q.body.rot(tilt, 0, 0);
            for (Bone leg : q.legsUpper) leg.rot(-tilt, 0, 0);
            if (q.neck.length > 0) q.neck[0].rx -= tilt;
            else q.head.rx -= tilt;
        }
    }

    // ============================================================================================ alit

    /** Alit: a huge head-mouth on two legs: no neck, no arms, a tail. */
    static CreatureDef alit() {
        Theropod t = new Theropod("alit");
        t.bodyW = 9;
        t.bodyH = 8;
        t.bodyL = 9;
        t.legLen = 8;
        t.legW = 5;
        t.neckLen = 2;
        t.neckW = 6;
        t.neckH = 6;
        t.neckPitch = -5;
        t.headW = 11;
        t.headH = 11;
        t.headL = 11;
        t.jawH = 4;
        t.armLen = 0;
        t.tailSegs = 3;
        t.tailLen = 12;
        t.tailW = 5;
        t.tailRaise = 6;
        t.skin = "hide";
        t.belly = "belly";
        t.stride = 34;
        t.build();
        t.head.z += 4; // the head-mouth swallows the neck and half the body
        t.head.rx += 6; // snout slightly down
        // fangs over the lips on both sides, a ridged crown and eye bumps
        for (int i = 0; i < 4; i++) {
            float z = -9.5f + i * 2.4f;
            t.jawBone.box("tooth", -5.8f, -1.5f, z, 1, 2, 1).tag("fang");
            t.jawBone.box("tooth", 4.8f, -1.5f, z, 1, 2, 1).tag("fang");
        }
        t.jawBone.box("tooth", -2.5f, -1.5f, -9.8f, 1, 2, 1).tag("fang");
        t.jawBone.box("tooth", 1.5f, -1.5f, -9.8f, 1, 2, 1).tag("fang");
        t.head.box("hide", -2, -6.5f, -9, 4, 1, 4).tag("crown");
        t.head.box("hide", -1.5f, -6.5f, -4, 3, 1, 3).tag("crown");
        t.head.box("hide", -6, -6.5f, -7, 3, 2, 3).tag("eye_ridge");
        t.head.box("hide", 3, -6.5f, -7, 3, 2, 3).tag("eye_ridge");
        t.body.box("hide", -1, -5, 0, 2, 1, 3).tag("knob");
        t.tail[0].box("hide", -1, -3.5f, 0.5f, 2, 1, 2).tag("knob");
        t.body.y += 0.6f;

        CreatureDef def = t.def();
        Anim lunge = Anim.once(0.85f);
        lunge.pos("body", 0, 0, 0, 0, 0.25f, 0, -1, 2, 0.42f, 0, 1.5f, -6, 0.6f, 0, 0, -5, 0.85f, 0, 0, 0);
        lunge.rot("body", 0, 0, 0, 0, 0.25f, -8, 0, 0, 0.42f, 10, 0, 0, 0.85f, 0, 0, 0);
        lunge.linear().rot("jaw", 0, 0, 0, 0, 0.25f, 50, 0, 0, 0.4f, 50, 0, 0, 0.46f, 0, 0, 0, 0.85f, 0, 0, 0).smooth();
        lunge.rot("head", 0, 0, 0, 0, 0.25f, -12, 0, 0, 0.42f, 6, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("leg_right", 0, 0, 0, 0, 0.25f, -20, 0, 0, 0.42f, 30, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("leg_left", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.42f, 25, 0, 0, 0.85f, 0, 0, 0);
        for (Bone b : t.tail) lunge.rot(b.name, 0, 0, 0, 0, 0.42f, -10, 0, 0, 0.85f, 0, 0, 0);
        def.anim("attack2", lunge);
        return def;
    }

    // ============================================================================================ nix-hound

    /** Nix-hound: insectoid hound on thin long legs, long narrow snout, segmented carapace, small head crest. */
    static CreatureDef nixHound() {
        Quad q = new Quad("nix_hound");
        q.bodyW = 6;
        q.bodyH = 5;
        q.bodyL = 12;
        q.legLen = 11;
        q.legW = 2;
        q.neckSegs = 1;
        q.neckLen = 4;
        q.neckW = 3;
        q.neckH = 3;
        q.neckPitch = -22;
        q.headW = 4;
        q.headH = 4;
        q.headL = 3;
        q.snoutW = 2;
        q.snoutH = 2;
        q.snoutL = 7;
        q.earH = 0;
        q.tailSegs = 2;
        q.tailLen = 5;
        q.tailW = 2;
        q.tailDroop = 25;
        q.foot = "pad";
        q.fur = "carapace";
        q.belly = "joint";
        q.legMat = "leg";
        q.stride = 32;
        q.runStride = 46;
        q.gallop = false;
        q.scale = 0.8f;
        q.build();
        groundQuad(q, -4);
        // pointed insect feet
        for (Bone low : q.legsLower) low.sbox("claw", -0.5f, 6, -1.5f, 1, 1, 2).tag("toe");
        // overlapping carapace segments down the back, a rear abdomen plate
        int[] w = {7, 7, 6, 5};
        for (int i = 0; i < 4; i++) {
            q.body.box("carapace", -w[i] / 2f, -3.6f + (i % 2) * 0.3f, -6.5f + i * 3.2f, w[i], 2, 4).tag("segment");
        }
        q.body.box("carapace", -2.5f, -2.5f, 5, 5, 4, 3).tag("abdomen");
        // head crest and cheek spurs
        q.head.box("crest", -0.5f, -4.5f, -2.5f, 1, 3, 3).tag("crest");
        q.head.box("crest", -2.5f, -3, -1, 1, 2, 2).tag("spur");
        q.head.box("crest", 1.5f, -3, -1, 1, 2, 2).tag("spur");
        q.neck[0].box("carapace", -2, -2.2f, -3.5f, 4, 1, 4).tag("segment");
        CreatureDef def = q.def();
        def.anim("roar", def.get("special"));
        return def;
    }

    // ============================================================================================ silt strider

    private static final String[] STRIDER_LEGS = {"leg_front_right", "leg_front_left", "leg_back_right", "leg_back_left"};

    /**
     * Silt strider: a domed flea-like shell with a passenger hollow (wooden deck, seat and rope rail), four extremely
     * long, high-kneed legs and a small head with a long drooping proboscis. Built for renderer scale 3.5.
     */
    static CreatureDef siltStrider() {
        Rig rig = new Rig("silt_strider");
        Bone body = rig.root().child("body", 0, -23.6f, 0);
        // shell: keel, lower shell with rounded bands, rim walls around the hollow, rear abdomen
        body.box("shell", -6, 0, -8, 12, 2, 16).tag("keel");
        body.box("shell", -8, -5, -10, 16, 5, 20).tag("shell");
        body.box("shell", -9, -4, -8, 18, 3, 16).tag("shell_band");
        body.box("shell", -6, -4, -11, 12, 3, 22).tag("shell_band");
        body.box("shell", -7, -8, -10, 14, 3, 3).tag("rim");
        body.box("shell", -7, -8, 7, 14, 3, 3).tag("rim");
        body.box("shell", -8, -8, -7, 2, 3, 14).tag("rim_side");
        body.box("shell", 6, -8, -7, 2, 3, 14).tag("rim_side");
        body.box("shell", -6, -6, 10, 12, 7, 4).tag("abdomen");
        body.box("shell", -4, -4, 14, 8, 5, 2).tag("abdomen_tip");
        // the passengers' deck: planks, a seat with a backrest, corner posts and a rope rail
        Bone deck = body.child("deck", 0, -5, 0);
        deck.box("wood", -5, -1, -5, 10, 1, 10).tag("deck");
        deck.box("leather", -2, -2, -1, 4, 1, 3).tag("seat");
        deck.box("leather", -2, -4, 2, 4, 2, 1).tag("backrest");
        for (float x : new float[]{-5, 4}) {
            for (float z : new float[]{-5, 4}) deck.box("wood", x, -5, z, 1, 4, 1).tag("post");
        }
        deck.box("rope", -4.5f, -4.5f, -4.5f, 9, 1, 0).tag("rope");
        deck.box("rope", -4.5f, -4.5f, 4.5f, 9, 1, 0).tag("rope");
        deck.box("rope", -4.5f, -4.5f, -4.5f, 0, 1, 9).tag("rope");
        deck.box("rope", 4.5f, -4.5f, -4.5f, 0, 1, 9).tag("rope");
        // head low at the front, mandible plates, feelers and the long proboscis
        Bone head = body.child("head", 0, 0, -10);
        head.box("skin", -3, -2, -5, 6, 4, 5).tag("head");
        head.box("shell", -3.5f, -3, -4, 7, 2, 4).tag("brow");
        head.pair("feeler", 2, -2.5f, -4, (b, s) -> {
            b.rot(-35, 0, -20);
            b.sbox("skin", -0.5f, -0.5f, -5, 1, 1, 5).tag("feeler");
        });
        Bone prob = head.child("proboscis", 0, 1, -5).rot(40, 0, 0);
        prob.box("skin", -1, -1, -7, 2, 2, 7).tag("proboscis");
        Bone tip = prob.child("proboscis_tip", 0, 0, -6.5f).rot(30, 0, 0);
        tip.box("skin", -0.5f, -0.5f, -7, 1, 1, 7).tag("proboscis_tip");
        tip.box("skin", -1, -1, -8, 2, 2, 1).tag("proboscis_end");
        // four stilt legs: the thigh rises out and up to a high knee, the shank drops to the ground
        body.pair("leg_front", 7.5f, -1, -6, (b, s) -> striderLeg(b, "leg_front_low", -25));
        body.pair("leg_back", 7.5f, -1, 6, (b, s) -> striderLeg(b, "leg_back_low", 25));

        CreatureDef def = new CreatureDef(rig);
        def.gait(1.1f, 0.8f, 0.95f);
        def.look("head", 0.5f);
        def.anim("idle", striderIdle());
        def.anim("walk", striderWalk());
        def.anim("special", striderTrumpet());
        def.anim("roar", striderTrumpet());
        def.anim("death", striderDeath());
        def.anim("hurt", striderHurt());
        return def;
    }

    private static void striderLeg(Bone b, String lowName, float tilt) {
        // thigh up and out at 45 degrees; the shank's rest rotation undoes it so the shank stands nearly upright
        b.rot(tilt, 0, 135);
        b.sbox("leg", -1, -1, -1, 2, 10, 2).tag("leg_upper");
        b.sbox("shell", -1.5f, 7.5f, -1.5f, 3, 3, 3).tag("knee");
        Bone low = b.child(lowName, 0, 9, 0).rot(tilt * 0.7f, 0, -129);
        low.sbox("leg", -1, 0, -1, 2, 20, 2).tag("leg_lower");
        low.sbox("leg", -0.5f, 19.5f, -0.5f, 1, 10, 1).tag("leg_tip");
    }

    private static Anim striderIdle() {
        Anim a = Anim.loop(6f);
        a.bob("body", 1, 0.35f, 0f, 1);
        a.swing("body", 2, 1.2f, 0.2f);
        a.swing("head", 1, 8f, 0.1f);
        a.swing("head", 0, 3f, 0.4f);
        a.swing("proboscis", 0, 6f, 0.3f);
        a.swing("proboscis", 1, 6f, 0.05f);
        a.swing("proboscis_tip", 0, 9f, 0.15f);
        waveLR(a, "feeler", 1, 12f, 0f, 0f, 3, true);
        return a;
    }

    /** Slow diagonal gait: front-right with back-left, the knee lifting as each leg swings forward. */
    private static Anim striderWalk() {
        Anim a = Anim.loop(3.2f);
        float[] phase = {0f, 0.5f, 0.5f, 0f};
        for (int i = 0; i < 4; i++) {
            String leg = STRIDER_LEGS[i];
            boolean right = leg.endsWith("_right");
            float sg = right ? 1 : -1;
            String low = leg.replace("leg_front", "leg_front_low").replace("leg_back", "leg_back_low");
            // forward / back swing about the hip (x), knee lift (z) during the forward half
            a.wave(leg, ROT, 0, 12f, phase[i], 0, 1);
            int n = 8;
            float[] lift = new float[(n + 1) * 4];
            float[] bend = new float[(n + 1) * 4];
            for (int k = 0; k <= n; k++) {
                float f = (float) k / n;
                double v = Math.max(0, Math.sin(2 * Math.PI * (f + phase[i] + 0.25f)));
                lift[k * 4] = f * a.length;
                lift[k * 4 + 3] = (float) (v * 9) * sg;
                bend[k * 4] = f * a.length;
                bend[k * 4 + 3] = (float) (-v * 6) * sg;
            }
            a.rot(leg, lift);
            a.rot(low, bend);
        }
        a.bob("body", 1, 0.6f, 0.25f, 2);
        a.swing("body", 2, 1.5f, 0f);
        a.swingN("head", 0, 3f, 0f, 2);
        a.swing("proboscis", 0, 8f, 0.2f);
        a.swing("proboscis_tip", 0, 10f, 0.05f);
        return a;
    }

    /** The trumpet call: head and proboscis rise, the call booms, then everything settles. */
    private static Anim striderTrumpet() {
        Anim a = Anim.once(3f);
        a.rot("head", 0, 0, 0, 0, 0.6f, -30, 0, 0, 2.3f, -32, 0, 0, 3f, 0, 0, 0);
        a.rot("proboscis", 0, 0, 0, 0, 0.6f, -85, 0, 0, 1.2f, -95, 0, 0, 2.3f, -90, 0, 0, 3f, 0, 0, 0);
        a.rot("proboscis_tip", 0, 0, 0, 0, 0.6f, -40, 0, 0, 1.2f, -25, 0, 0, 2.3f, -30, 0, 0, 3f, 0, 0, 0);
        a.scale("proboscis_tip", 0, 1, 1, 1, 0.7f, 1, 1, 1, 0.9f, 1.4f, 1.4f, 1, 1.1f, 1, 1, 1, 1.3f, 1.4f, 1.4f, 1,
                1.5f, 1, 1, 1, 3f, 1, 1, 1);
        a.rot("body", 0, 0, 0, 0, 0.6f, -4, 0, 0, 2.3f, -4, 0, 0, 3f, 0, 0, 0);
        rotLR(a, "feeler", 0, 0, 0, 0, 0.6f, -30, 0, 20, 2.3f, -30, 0, 20, 3f, 0, 0, 0);
        return a;
    }

    /** The legs splay and give way; the shell sinks to the ground. */
    private static Anim striderDeath() {
        Anim a = Anim.once(1f);
        a.pos("body", 0, 0, 0, 0, 0.8f, 0, -22, 0, 1f, 0, -24, 0);
        for (String leg : STRIDER_LEGS) {
            float sg = leg.endsWith("_right") ? 1 : -1;
            a.rot(leg, 0, 0, 0, 0, 0.8f, 0, 0, -45 * sg, 1f, 0, 0, -50 * sg);
            String low = leg.replace("leg_front", "leg_front_low").replace("leg_back", "leg_back_low");
            a.rot(low, 0, 0, 0, 0, 0.8f, 0, 0, 60 * sg, 1f, 0, 0, 65 * sg);
        }
        a.rot("head", 0, 0, 0, 0, 0.8f, 25, 0, 0, 1f, 25, 0, 0);
        a.rot("proboscis", 0, 0, 0, 0, 0.8f, 30, 0, 0, 1f, 30, 0, 0);
        return a;
    }

    private static Anim striderHurt() {
        Anim a = Anim.once(0.6f);
        a.rot("body", 0, 0, 0, 0, 0.12f, -3, 0, 3, 0.3f, 2, 0, -2, 0.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.12f, -20, 0, 0, 0.6f, 0, 0, 0);
        a.rot("proboscis", 0, 0, 0, 0, 0.12f, -30, 0, 0, 0.6f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ minotaur

    /** Minotaur: bull-headed giant on hooved digitigrade legs, loincloth, a huge warhammer in the right hand. */
    static CreatureDef minotaur() {
        Biped b = new Biped("minotaur");
        b.legLen = 14;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 4;
        b.torsoW = 14;
        b.torsoH = 13;
        b.torsoD = 8;
        b.hunch = 12;
        b.armLen = 16;
        b.armW = 5;
        b.handSize = 5;
        b.headW = 7;
        b.headH = 7;
        b.headD = 8;
        b.headForward = 2;
        b.digitigrade = true;
        b.skin = "fur";
        b.headMat = "fur";
        b.handMat = "skin";
        b.footMat = "hoof";
        b.hipMat = "loincloth";
        b.scale = 1.1f;
        b.stride = 26;
        b.twoHanded = true;
        b.stomp = true;
        b.build();
        // bull face: muzzle with a nose ring, ears, a thick neck and shoulder mane
        b.head.box("muzzle", -3, -4, -8.5f, 6, 4, 4).tag("muzzle");
        b.head.box("fur", -3.5f, -6, -5.5f, 7, 1, 1).tag("brow");
        b.head.box("metal", -1, -0.2f, -8.8f, 2, 1, 1).tag("ring");
        b.head.box("fur", -5, -5.5f, -1, 2, 1, 2).tag("ear");
        b.head.box("fur", 3, -5.5f, -1, 2, 1, 2).tag("ear");
        b.head.box("fur", -3, -7.5f, -2, 6, 1, 4).tag("forelock");
        b.head.pair("horn", 3.5f, -6, -1, (h, s) -> {
            h.rot(0, -15, 20);
            h.sbox("horn", -4, -1, -1, 4, 2, 2).tag("horn");
            Bone mid = h.child("horn_mid", -3.5f, 0, 0).rot(0, -20, 45);
            mid.sbox("horn", -3, -0.75f, -0.75f, 3, 2, 2, -0.25f).tag("horn");
            Bone tip = mid.child("horn_tip", -2.5f, 0, 0).rot(0, -10, 40);
            tip.sbox("horn", -3, -0.5f, -0.5f, 3, 1, 1).tag("horn_tip");
        });
        b.torso.box("mane", -6, -14.5f, -2, 12, 4, 7, 0.2f).tag("mane");
        b.torso.box("fur", -4, -15, -3.5f, 8, 3, 6).tag("neck");
        // loincloth flaps, belt and bracers
        b.hips.box("loincloth", -4.5f, -1, -3, 9, 7, 1).tag("flap");
        b.hips.box("loincloth", -4.5f, -1, 2.5f, 9, 6, 1).tag("flap");
        b.hips.box("leather", -5.5f, -4.5f, -4.5f, 11, 2, 9).tag("belt");
        for (Bone f : b.forearms) f.sbox("metal", -2.5f, 2, -2.5f, 5, 3, 5, 0.1f).tag("bracer");
        // the warhammer: a long haft forward-down from the fist, a heavy double-faced head
        Bone weapon = b.hands[0].child("weapon", 0, 2, 0).rot(11, 0, 0);
        weapon.box("wood", -1, -1, -19, 2, 2, 23).tag("haft");
        weapon.box("metal", -1.5f, -1.5f, 4, 3, 3, 2).tag("pommel");
        weapon.box("metal", -3, -4.5f, -23, 6, 9, 5).tag("hammer");
        weapon.box("metal", -3.5f, -1.5f, -23.5f, 7, 3, 6).tag("hammer_band");
        weapon.box("metal", -1, -6.5f, -21.5f, 2, 2, 2).tag("spike");

        CreatureDef def = b.def();
        Anim gore = Anim.once(0.95f);
        gore.rot("torso", 0, 0, 0, 0, 0.3f, 30, 0, 0, 0.45f, 26, 0, 0, 0.6f, 6, 0, 0, 0.95f, 0, 0, 0);
        gore.rot("head", 0, 0, 0, 0, 0.3f, 32, 0, 0, 0.42f, 28, 0, 0, 0.55f, -28, 0, 0, 0.95f, 0, 0, 0);
        gore.pos("hips", 0, 0, 0, 0, 0.3f, 0, -1.5f, 2, 0.45f, 0, -0.5f, -6, 0.6f, 0, 0, -5, 0.95f, 0, 0, 0);
        gore.rot("leg_right", 0, 0, 0, 0, 0.3f, -25, 0, 0, 0.45f, 30, 0, 0, 0.95f, 0, 0, 0);
        gore.rot("leg_left", 0, 0, 0, 0, 0.3f, 10, 0, 0, 0.45f, -35, 0, 0, 0.95f, 0, 0, 0);
        gore.rot("arm_right", 0, 0, 0, 0, 0.3f, 10, 0, 12, 0.45f, 12, 0, 18, 0.95f, 0, 0, 0);
        gore.rot("arm_left", 0, 0, 0, 0, 0.3f, 10, 0, -12, 0.45f, 12, 0, -18, 0.95f, 0, 0, 0);
        def.anim("attack2", gore);
        return def;
    }

    // ============================================================================================ ogre

    /** Ogre: a hunched brute with a huge belly, small head, underbite tusks and long knuckle-dragging arms. */
    static CreatureDef ogre() {
        Biped b = new Biped("ogre");
        b.legLen = 10;
        b.legW = 6;
        b.hipW = 12;
        b.hipH = 5;
        b.torsoW = 16;
        b.torsoH = 14;
        b.torsoD = 10;
        b.hunch = 28;
        b.armLen = 19;
        b.armW = 6;
        b.handSize = 6;
        b.headW = 7;
        b.headH = 6;
        b.headD = 6;
        b.headForward = 4;
        b.jaw = true;
        b.jawH = 3;
        b.skin = "skin";
        b.hipMat = "hide";
        b.scale = 1.25f;
        b.stride = 24;
        b.build();
        // the belly, sagging over the loincloth
        b.torso.box("belly", -7, -10, -8, 14, 10, 4).tag("belly");
        b.torso.box("belly", -5.5f, -9, -9.5f, 11, 8, 2).tag("belly_front");
        b.torso.box("belly", -6, -1, -7, 12, 2, 3).tag("belly_low");
        // small head: brow, ears, a hair tuft; the underbite jaw with tusks
        b.head.box("skin", -3.5f, -6.5f, -4, 7, 2, 2).tag("brow");
        b.head.box("skin", -4.5f, -4.5f, -1, 1, 3, 2).tag("ear");
        b.head.box("skin", 3.5f, -4.5f, -1, 1, 3, 2).tag("ear");
        b.head.box("hair", -1.5f, -7.5f, -1, 3, 2, 4).tag("tuft");
        b.head.box("skin", -1, -4, -4.5f, 2, 2, 1).tag("nose");
        b.jawBone.box("tooth", -2.5f, -2.5f, -4.5f, 1, 3, 1).tag("tusk");
        b.jawBone.box("tooth", 1.5f, -2.5f, -4.5f, 1, 3, 1).tag("tusk");
        // hide loincloth, shoulder pelt and a bone necklace
        b.hips.box("hide", -6.5f, -1, -5.5f, 13, 6, 1).tag("flap");
        b.hips.box("hide", -6.5f, -1, 4.5f, 13, 5, 1).tag("flap");
        b.torso.box("hide", -8.5f, -14.8f, -5.5f, 7, 4, 11, 0.1f).tag("pelt");
        b.torso.box("bone", -5, -12, -9.2f, 10, 1, 1).tag("necklace");

        CreatureDef def = b.def();
        // two-fist slam sized to the long arms: the fists land on the ground, not through it
        Anim slam = Anim.once(1.1f);
        slam.rot("torso", 0, 0, 0, 0, 0.45f, -22, 0, 0, 0.62f, 30, 0, 0, 0.85f, 26, 0, 0, 1.1f, 0, 0, 0);
        rotLR(slam, "arm", 0, 0, 0, 0, 0.45f, -165, 0, -8, 0.62f, -30, 0, 4, 0.85f, -27, 0, 4, 1.1f, 0, 0, 0);
        rotLR(slam, "forearm", 0, 0, 0, 0, 0.45f, -35, 0, 0, 0.62f, -5, 0, 0, 1.1f, 0, 0, 0);
        slam.pos("hips", 0, 0, 0, 0, 0.62f, 0, -1.5f, 0, 0.85f, 0, -1.5f, 0, 1.1f, 0, 0, 0);
        rotLR(slam, "leg", 0, 0, 0, 0, 0.62f, -20, 0, 0, 0.85f, -20, 0, 0, 1.1f, 0, 0, 0);
        rotLR(slam, "shin", 0, 0, 0, 0, 0.62f, 30, 0, 0, 0.85f, 30, 0, 0, 1.1f, 0, 0, 0);
        slam.rot("jaw", 0, 0, 0, 0, 0.45f, 30, 0, 0, 0.7f, 10, 0, 0, 1.1f, 0, 0, 0);
        def.anim("attack3", slam);
        return def;
    }

    // ============================================================================================ land dreugh

    /** Land dreugh: upright crab-like humanoid: carapace back and helmet, crab claws, face feelers, extra legs. */
    static CreatureDef landDreugh() {
        Biped b = new Biped("land_dreugh");
        b.legLen = 11;
        b.legW = 4;
        b.hipW = 9;
        b.hipH = 4;
        b.torsoW = 11;
        b.torsoH = 12;
        b.torsoD = 6;
        b.hunch = 12;
        b.armLen = 13;
        b.armW = 4;
        b.handSize = 0;
        b.headW = 6;
        b.headH = 5;
        b.headD = 6;
        b.headForward = 1;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "flesh";
        b.armMat = "shell";
        b.legMat = "shell";
        b.headMat = "flesh";
        b.hipMat = "shell";
        b.stride = 24;
        b.scale = 0.95f;
        b.build();
        // carapace: a domed back shell in two layers, chest plates, a helmet with a ridge and eye stalks
        b.torso.box("carapace", -6.5f, -13, 0.5f, 13, 12, 4).tag("back_shell");
        b.torso.box("carapace", -5.5f, -12, 4.5f, 11, 9, 2).tag("back_dome");
        b.torso.box("carapace", -6, -13.5f, -3.5f, 12, 3, 5).tag("collar");
        b.torso.box("shell", -4.5f, -10, -3.6f, 9, 8, 1).tag("plastron");
        b.head.box("carapace", -3.5f, -6, -4, 7, 2, 7).tag("helmet");
        b.head.box("carapace", -0.5f, -7, -4.5f, 1, 1, 6).tag("ridge");
        b.head.box("eye", -2.5f, -7, -4, 1, 2, 1).tag("stalk");
        b.head.box("eye", 1.5f, -7, -4, 1, 2, 1).tag("stalk");
        b.jawBone.pair("feeler", 1.5f, 1.5f, -3.5f, (f, s) -> {
            f.rot(15, 0, -8);
            f.sbox("flesh", -0.5f, 0, -0.5f, 1, 4, 1).tag("feeler");
        });
        b.jawBone.pair("feeler_out", 2.5f, 1, -2, (f, s) -> {
            f.rot(10, 0, -25);
            f.sbox("flesh", -0.5f, 0, -0.5f, 1, 3, 1).tag("feeler");
        });
        // shoulder plates
        for (Bone arm : b.arms) arm.sbox("carapace", -3, -3, -3, 6, 4, 6).tag("pauldron");
        // crab claws: a heavy palm, a fixed finger in front and a hinged pincer behind
        for (Bone hand : b.hands) {
            hand.sbox("carapace", -2.5f, -0.5f, -2.5f, 5, 5, 5).tag("palm");
            hand.sbox("claw", -1.5f, 4.5f, -2.5f, 3, 6, 2).tag("finger");
            Bone p = hand.child("pincer", 0, 4.5f, 1).rot(25, 0, 0);
            p.sbox("claw", -1.5f, 0, -1, 3, 5, 2).tag("finger");
        }
        // two pairs of small crab legs folded at the hips
        b.hips.pair("crab_leg", 4.5f, -2, -1, (l, s) -> crabLeg(l, "crab_leg_low"));
        b.hips.pair("crab_leg_back", 4.5f, -2, 1.5f, (l, s) -> crabLeg(l, "crab_leg_back_low"));

        CreatureDef def = b.def();
        // pincers snap on every claw attack
        for (String slot : new String[]{"attack", "attack2"}) {
            Anim a = def.get(slot);
            a.linear();
            rotLR(a, "pincer", 0, 0, 0, 0, 0.2f, 25, 0, 0, 0.36f, -25, 0, 0, 0.5f, -25, 0, 0, a.length, 0, 0, 0);
            a.smooth();
        }
        def.anim("special", dreughThreat());
        Anim idle = def.get("idle");
        waveLR(idle, "feeler", 0, 12f, 0f, 0f, 2, true);
        waveLR(idle, "feeler_out", 2, 10f, 0.3f, 0f, 2, true);
        waveLR(idle, "crab_leg", 0, 6f, 0f, 0f, 1, true);
        waveLR(idle, "crab_leg_back", 0, 6f, 0.25f, 0f, 1, true);
        Anim walk = def.get("walk");
        waveLR(walk, "crab_leg", 0, 18f, 0f, 0f, 1, true);
        waveLR(walk, "crab_leg_back", 0, 18f, 0.5f, 0f, 1, true);
        return def;
    }

    private static void crabLeg(Bone l, String lowName) {
        l.rot(0, 0, 50);
        l.sbox("shell", -1, -0.5f, -1, 2, 5, 2).tag("crab_leg");
        Bone low = l.child(lowName, 0, 4.5f, 0).rot(0, 0, -75);
        low.sbox("shell", -0.5f, 0, -0.5f, 1, 5, 1).tag("crab_leg_low");
    }

    /** Rears up, claws raised wide and snapping, feelers and little legs splayed. */
    private static Anim dreughThreat() {
        Anim a = Anim.once(1.8f);
        a.rot("torso", 0, 0, 0, 0, 0.4f, -14, 0, 0, 1.4f, -14, 0, 0, 1.8f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -12, 0, 0, 1.4f, -12, 0, 0, 1.8f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.4f, 30, 0, 0, 1.4f, 30, 0, 0, 1.8f, 0, 0, 0);
        rotLR(a, "arm", 0, 0, 0, 0, 0.4f, -40, 0, 105, 1.4f, -40, 0, 110, 1.8f, 0, 0, 0);
        rotLR(a, "forearm", 0, 0, 0, 0, 0.4f, -50, 0, 0, 1.4f, -50, 0, 0, 1.8f, 0, 0, 0);
        a.linear();
        rotLR(a, "pincer", 0, 0, 0, 0, 0.4f, 35, 0, 0, 0.55f, -20, 0, 0, 0.7f, 35, 0, 0, 0.85f, -20, 0, 0, 1.0f, 35, 0, 0,
                1.15f, -20, 0, 0, 1.4f, 30, 0, 0, 1.8f, 0, 0, 0);
        a.smooth();
        rotLR(a, "feeler", 0, 0, 0, 0, 0.4f, -40, 0, 30, 1.4f, -40, 0, 30, 1.8f, 0, 0, 0);
        rotLR(a, "feeler_out", 0, 0, 0, 0, 0.4f, -30, 0, 40, 1.4f, -30, 0, 40, 1.8f, 0, 0, 0);
        rotLR(a, "crab_leg", 0, 0, 0, 0, 0.4f, -20, 0, 30, 1.4f, -20, 0, 30, 1.8f, 0, 0, 0);
        rotLR(a, "crab_leg_back", 0, 0, 0, 0, 0.4f, 20, 0, 30, 1.4f, 20, 0, 30, 1.8f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ wamasu

    /** Wamasu: Black Marsh storm raptor with a swept crest and back spines that crackle (glow layer). */
    static CreatureDef wamasu() {
        Theropod t = new Theropod("wamasu");
        t.bodyW = 9;
        t.bodyH = 9;
        t.bodyL = 14;
        t.legLen = 13;
        t.legW = 5;
        t.neckLen = 6;
        t.neckW = 5;
        t.neckH = 5;
        t.neckPitch = -35;
        t.headW = 6;
        t.headH = 6;
        t.headL = 10;
        t.jawH = 3;
        t.armLen = 6;
        t.tailSegs = 5;
        t.tailLen = 25;
        t.tailW = 6;
        t.tailRaise = 4;
        t.skin = "scales";
        t.belly = "belly";
        t.stride = 32;
        t.build();
        t.body.y += 1.2f;
        // head crest: three swept-back blades with a membrane between them
        Bone crest = t.head.child("crest", 0, -2.5f, -3).rot(-40, 0, 0);
        crest.box("crest_skin", 0, -6, -3, 0, 6, 6).tag("crest_skin");
        crest.box("spine", -0.5f, -4, -3, 1, 4, 1).tag("spine");
        crest.box("spine", -0.5f, -6, -0.5f, 1, 6, 1).tag("spine");
        crest.box("spine", -0.5f, -7, 2, 1, 7, 1).tag("spine");
        t.head.box("scales", -3.5f, -3.5f, -7, 7, 1, 3).tag("brow");
        t.jawBone.box("tooth", -2.5f, -1, -8, 1, 1, 1).tag("tooth");
        t.jawBone.box("tooth", 1.5f, -1, -8, 1, 1, 1).tag("tooth");
        // spines down the neck, back and tail
        wamasuSpine(t.neck, "spine_neck1", -2.5f, -4, 3);
        wamasuSpine(t.neck, "spine_neck2", -2.5f, -1, 3);
        float top = -t.bodyH / 2f;
        wamasuSpine(t.body, "spine_back1", top, -5, 4);
        wamasuSpine(t.body, "spine_back2", top, -1.5f, 5);
        wamasuSpine(t.body, "spine_back3", top, 2, 4);
        wamasuSpine(t.body, "spine_back4", top, 5.5f, 3);
        for (int i = 0; i < 3; i++) wamasuSpine(t.tail[i], "spine_tail" + (i + 1), -(t.tailW - i) / 2f, 2, 3 - i / 2);

        CreatureDef def = t.def();
        Anim breath = Anim.once(2.6f);
        breath.rot("body", 0, 0, 0, 0, 0.4f, 6, 0, 0, 2.2f, 6, 0, 0, 2.6f, 0, 0, 0);
        breath.pos("body", 0, 0, 0, 0, 0.4f, 0, -0.5f, 1.5f, 2.2f, 0, -0.5f, 1.5f, 2.6f, 0, 0, 0);
        breath.rot("neck", 0, 0, 0, 0, 0.4f, 28, 0, 0, 2.2f, 28, 0, 0, 2.6f, 0, 0, 0);
        breath.rot("head", 0, 0, 0, 0, 0.4f, -8, 0, 0, 2.2f, -8, 0, 0, 2.6f, 0, 0, 0);
        breath.linear().rot("jaw", 0, 0, 0, 0, 0.4f, 42, 0, 0, 0.7f, 36, 0, 0, 0.95f, 44, 0, 0, 1.2f, 37, 0, 0,
                1.45f, 44, 0, 0, 1.7f, 36, 0, 0, 1.95f, 43, 0, 0, 2.2f, 40, 0, 0, 2.6f, 0, 0, 0).smooth();
        breath.rot("crest", 0, 0, 0, 0, 0.4f, 20, 0, 0, 2.2f, 20, 0, 0, 2.6f, 0, 0, 0);
        breath.rot("leg_right", 0, 0, 0, 0, 0.4f, -12, 0, 0, 2.2f, -12, 0, 0, 2.6f, 0, 0, 0);
        breath.rot("leg_left", 0, 0, 0, 0, 0.4f, 14, 0, 0, 2.2f, 14, 0, 0, 2.6f, 0, 0, 0);
        for (Bone b : t.tail) breath.rot(b.name, 0, 0, 0, 0, 0.4f, -6, 0, 0, 2.2f, -6, 0, 0, 2.6f, 0, 0, 0);
        for (String sp : new String[]{"spine_back1", "spine_back2", "spine_back3", "spine_back4"}) {
            breath.scale(sp, 0, 1, 1, 1, 0.4f, 1, 1.3f, 1, 2.2f, 1, 1.3f, 1, 2.6f, 1, 1, 1);
        }
        def.anim("special", breath);
        return def;
    }

    private static void wamasuSpine(Bone parent, String name, float y, float z, int h) {
        Bone s = parent.child(name, 0, y + 0.5f, z).rot(-25, 0, 0);
        s.box("spine", -0.5f, -h, -1, 1, h, 2).tag("spine");
    }

    // ============================================================================================ swamp leviathan

    /** Swamp leviathan: broad flat crocodilian head, heavy low body on splayed legs, mossy back, long tail. */
    static CreatureDef swampLeviathan() {
        Quad q = new Quad("swamp_leviathan");
        q.bodyW = 14;
        q.bodyH = 7;
        q.bodyL = 18;
        q.legLen = 4;
        q.legW = 4;
        q.neckSegs = 1;
        q.neckLen = 3;
        q.neckW = 10;
        q.neckH = 6;
        q.neckPitch = 0;
        q.headW = 11;
        q.headH = 5;
        q.headL = 6;
        q.snoutW = 9;
        q.snoutH = 3;
        q.snoutL = 8;
        q.earH = 0;
        q.tailSegs = 5;
        q.tailLen = 20;
        q.tailW = 6;
        q.tailDroop = 6;
        q.fur = "hide";
        q.belly = "belly";
        q.scale = 2.5f;
        q.stride = 24;
        q.runStride = 32;
        q.gallop = false;
        q.build();
        groundQuad(q, 0);
        // splayed crocodilian legs
        for (Bone leg : q.legsUpper) leg.rot(0, 0, 35);
        for (Bone low : q.legsLower) low.rot(0, 0, -35);
        q.body.y += 0.8f;
        // eye bumps, nostril knob, interlocking teeth
        q.head.box("hide", -5.5f, -3.5f, -5, 3, 2, 3).tag("eye_bump");
        q.head.box("hide", 2.5f, -3.5f, -5, 3, 2, 3).tag("eye_bump");
        float st = q.headH / 2f - q.snoutH - 1;
        q.snout.box("hide", -2, st - 1, -q.snoutL, 4, 1, 2).tag("nostrils");
        for (int i = 0; i < 4; i++) {
            float z = -q.snoutL + 0.5f + i * 2;
            q.snout.box("tooth", -4.5f, st + q.snoutH - 0.5f, z, 1, 2, 1).tag("tooth");
            q.snout.box("tooth", 3.5f, st + q.snoutH - 0.5f, z, 1, 2, 1).tag("tooth");
        }
        q.jawBone.box("tooth", -4, -1.5f, -10, 1, 2, 1).tag("tooth");
        q.jawBone.box("tooth", 3, -1.5f, -10, 1, 2, 1).tag("tooth");
        // scute rows, moss mats and growths on the back
        for (int i = 0; i < 4; i++) {
            float z = -7 + i * 4.2f;
            q.body.box("scute", -4.5f, -4.5f, z, 2, 1, 3).tag("scute");
            q.body.box("scute", 2.5f, -4.5f, z, 2, 1, 3).tag("scute");
        }
        q.body.box("moss", -5, -4.8f, -5, 7, 1, 6).tag("moss");
        q.body.box("moss", -1, -5, 2, 6, 1, 5).tag("moss");
        q.body.box("moss", -6.5f, -4.2f, 3, 3, 3, 4).tag("moss_side");
        q.body.box("moss", 4.5f, -4.2f, -6, 3, 3, 5).tag("moss_side");
        q.body.box("growth", 1, -6.5f, -3, 2, 2, 2).tag("growth");
        q.body.box("growth", -3, -6, 4, 2, 2, 2).tag("growth");
        q.body.box("growth", 3.5f, -5.5f, 5, 1, 1, 1).tag("growth");
        q.neck[0].box("moss", -3, -3.6f, -2, 6, 1, 3).tag("moss");
        q.head.box("moss", -2, -3, -2, 4, 1, 2).tag("moss");
        for (int i = 0; i < q.tail.length - 1; i++) {
            int tw = Math.max(1, q.tailW - (i * q.tailW) / (q.tailSegs + 1));
            q.tail[i].box("scute", -1.5f, -tw / 2f - 1, 0.5f, 1, 1, 3).tag("scute");
            q.tail[i].box("scute", 0.5f, -tw / 2f - 1, 0.5f, 1, 1, 3).tag("scute");
        }

        CreatureDef def = q.def();
        def.anim("swim", leviathanSwim(q));
        def.anim("attack2", leviathanSweep(q));
        def.anim("roar", def.get("special"));
        return def;
    }

    private static Anim leviathanSwim(Quad q) {
        Anim a = Anim.loop(1.6f);
        rotLR(a, "leg_front", 0, 70, 0, -20, 1.6f, 70, 0, -20);
        rotLR(a, "leg_back", 0, 75, 0, -20, 1.6f, 75, 0, -20);
        a.wave("body", ROT, 1, 6f, 0f, 0, 1);
        a.wave("neck1", ROT, 1, -4f, 0f, 0, 1);
        a.wave("head", ROT, 1, -3f, 0.05f, 0, 1);
        for (int i = 0; i < q.tail.length; i++) a.wave(q.tail[i].name, ROT, 1, 12f + i * 4, -0.12f * (i + 1), 0, 1);
        a.bob("body", 1, 0.5f, 0.25f, 1);
        return a;
    }

    private static Anim leviathanSweep(Quad q) {
        Anim a = Anim.once(1.0f);
        a.rot("body", 0, 0, 0, 0, 0.3f, 0, 25, 0, 0.5f, 0, -35, 0, 1f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.3f, 0, -10, 0, 0.5f, 0, 15, 0, 1f, 0, 0, 0);
        for (int i = 0; i < q.tail.length; i++) {
            float d = i * 0.025f;
            a.rot(q.tail[i].name, 0, 0, 0, 0, 0.3f + d, 0, 22, 0, 0.5f + d, 0, -38, 0, 0.7f + d, 0, -15, 0, 1f, 0, 0, 0);
        }
        return a;
    }

    // ============================================================================================ hackwing

    /** Hackwing: Black Marsh raptor-bird with a long hooked beak, crest, fanned tail feathers and a red throat. */
    static CreatureDef hackwing() {
        Winged w = new Winged("hackwing");
        w.bodyW = 4;
        w.bodyH = 4;
        w.bodyL = 7;
        w.neckLen = 3;
        w.neckW = 3;
        w.headW = 3;
        w.headH = 4;
        w.headL = 3;
        w.jawL = 3;
        w.wingSpan = 26;
        w.wingChord = 8;
        w.tailSegs = 1;
        w.tailLen = 3;
        w.tailW = 3;
        w.stand = 6;
        w.tailFin = false;
        w.skin = "feather";
        w.wingMat = "wing";
        w.clawMat = "talon";
        w.flap = 55;
        w.build();
        Rig r = w.rig;
        w.body.box("feather", -2.5f, -1.5f, -4.5f, 5, 4, 3).tag("breast");
        // long hooked beak over the short lower bill
        w.head.box("beak", -1, -1.5f, -6.5f, 2, 2, 4).tag("beak");
        w.head.box("beak", -0.5f, 0, -7.5f, 1, 2, 1).tag("hook");
        // swept crest
        w.head.box("crest", -0.5f, -3.5f, -2, 1, 2, 3).tag("crest");
        w.head.box("crest", 0, -5, -1, 0, 3, 5).tag("crest_plume");
        // red throat wattle
        r.bone("neck").box("throat", -1, 0.5f, -3.5f, 2, 1, 4).tag("throat");
        // fanned tail feathers
        Bone tail = w.tail[0];
        tail.box("tailfeather", -1.5f, -0.6f, 1, 3, 0, 12).tag("tailfeather");
        tail.pair("tail_fan", 1, -0.4f, 1, (b, s) -> {
            b.rot(0, -14, 0);
            b.sbox("tailfeather", -1.5f, 0, 0, 3, 0, 10).tag("tailfeather");
        });
        CreatureDef def = w.def();
        perched(def, names(w.tail));
        // the tail fan spreads in flight and when screeching
        for (String slot : new String[]{"fly", "hover"}) waveLR(def.get(slot), "tail_fan", 1, 4f, 0f, -10f, 1, false);
        rotLR(def.get("special"), "tail_fan", 0, 0, 0, 0, 0.3f, 0, -14, 0, 0.9f, 0, -14, 0, 1.2f, 0, 0, 0);
        return def;
    }

    // ============================================================================================ senche tiger

    /** Senche-tiger: Elsweyr's great striped cat: muscular, long tail, cheek ruff and white face markings. */
    static CreatureDef sencheTiger() {
        Quad q = new Quad("senche_tiger");
        q.bodyW = 9;
        q.bodyH = 9;
        q.bodyL = 18;
        q.chestW = 10;
        q.chestH = 10;
        q.chestL = 8;
        q.legLen = 10;
        q.legW = 4;
        q.neckSegs = 1;
        q.neckLen = 3;
        q.neckW = 6;
        q.neckH = 6;
        q.neckPitch = -12;
        q.headW = 8;
        q.headH = 7;
        q.headL = 5;
        q.snoutW = 5;
        q.snoutH = 3;
        q.snoutL = 3;
        q.earH = 2;
        q.earW = 2;
        q.tailSegs = 4;
        q.tailLen = 18;
        q.tailW = 3;
        q.tailDroop = 45;
        q.fur = "fur";
        q.belly = "belly";
        q.headMat = "face";
        q.snoutMat = "muzzle";
        q.tailMat = "tail";
        q.scale = 1.0f;
        q.stride = 28;
        q.runStride = 46;
        q.build();
        groundQuad(q, 0);
        // cheek ruff, nose, fangs
        q.head.box("ruff", -5, -1.5f, -4, 2, 4, 3).tag("ruff");
        q.head.box("ruff", 3, -1.5f, -4, 2, 4, 3).tag("ruff");
        float st = q.headH / 2f - q.snoutH - 1;
        q.snout.box("nose", -1, st - 0.3f, -q.snoutL - 0.4f, 2, 1, 1).tag("nose");
        q.snout.box("tooth", -2, st + q.snoutH - 0.5f, -q.snoutL + 0.2f, 1, 2, 1).tag("fang");
        q.snout.box("tooth", 1, st + q.snoutH - 0.5f, -q.snoutL + 0.2f, 1, 2, 1).tag("fang");

        CreatureDef def = q.def();
        Anim p = Anim.once(1.0f);
        p.pos("body", 0, 0, 0, 0, 0.28f, 0, -2.5f, 1.5f, 0.45f, 0, 6, -7, 0.6f, 0, 1, -10, 0.8f, 0, 0, -9, 1f, 0, 0, 0);
        p.rot("body", 0, 0, 0, 0, 0.28f, 8, 0, 0, 0.45f, -16, 0, 0, 0.6f, 6, 0, 0, 1f, 0, 0, 0);
        rotLR(p, "leg_front", 0, 0, 0, 0, 0.28f, 25, 0, 0, 0.45f, -75, 0, 0, 0.6f, -35, 0, 0, 1f, 0, 0, 0);
        rotLR(p, "leg_front_lower", 0, 0, 0, 0, 0.28f, 40, 0, 0, 0.45f, -10, 0, 0, 0.6f, 20, 0, 0, 1f, 0, 0, 0);
        rotLR(p, "leg_back", 0, 0, 0, 0, 0.28f, -30, 0, 0, 0.45f, 55, 0, 0, 0.6f, 20, 0, 0, 1f, 0, 0, 0);
        rotLR(p, "leg_back_lower", 0, 0, 0, 0, 0.28f, -40, 0, 0, 0.45f, 10, 0, 0, 1f, 0, 0, 0);
        p.rot("head", 0, 0, 0, 0, 0.28f, -6, 0, 0, 0.45f, 10, 0, 0, 1f, 0, 0, 0);
        p.linear().rot("jaw", 0, 0, 0, 0, 0.3f, 35, 0, 0, 0.5f, 35, 0, 0, 0.56f, 0, 0, 0, 1f, 0, 0, 0).smooth();
        for (int i = 0; i < q.tail.length; i++) p.rot(q.tail[i].name, 0, 0, 0, 0, 0.45f, 18, 0, 0, 1f, 0, 0, 0);
        def.anim("attack3", p);
        def.anim("roar", def.get("special"));
        return def;
    }

    // ============================================================================================ alfiq

    /** Alfiq: a housecat-sized Khajiit furstock with big ears, a fluffy tail and glowing arcane markings. */
    static CreatureDef alfiq() {
        Quad q = new Quad("alfiq");
        q.bodyW = 4;
        q.bodyH = 4;
        q.bodyL = 8;
        q.legLen = 5;
        q.legW = 2;
        q.neckSegs = 1;
        q.neckLen = 2;
        q.neckW = 3;
        q.neckH = 3;
        q.neckPitch = -30;
        q.headW = 5;
        q.headH = 4;
        q.headL = 4;
        q.snoutW = 2;
        q.snoutH = 1;
        q.snoutL = 1;
        q.earH = 3;
        q.earW = 2;
        q.tailSegs = 3;
        q.tailLen = 9;
        q.tailW = 3;
        q.tailDroop = 12;
        q.fur = "fur";
        q.belly = "belly";
        q.headMat = "face";
        q.tailMat = "tail";
        q.stride = 30;
        q.runStride = 46;
        q.scale = 0.7f;
        q.build();
        groundQuad(q, 0);
        // fluffy tail tip, cheek tufts, a gold earring and collar charm
        q.tail[2].box("tail", -1.5f, -1.5f, 1, 3, 3, 3, 0.3f).tag("tail_tuft");
        q.tail[1].rx += 18; // the tail curls up towards its fluffy tip
        q.tail[2].rx += 28;
        q.head.box("face", -3, -0.5f, -3, 1, 2, 2).tag("tuft");
        q.head.box("face", 2, -0.5f, -3, 1, 2, 2).tag("tuft");
        q.rig.bone("ear_left").box("gold", -1.5f, -1.5f, -0.2f, 1, 1, 1).tag("earring");
        q.neck[0].box("gold", -1, 1, -2.5f, 2, 1, 1).tag("charm");

        CreatureDef def = q.def();
        def.anim("attack", q.swipe());
        Anim c = Anim.once(1.3f);
        c.rot("body", 0, 0, 0, 0, 0.35f, -55, 0, 0, 0.95f, -55, 0, 0, 1.3f, 0, 0, 0);
        c.pos("body", 0, 0, 0, 0, 0.35f, 0, 2.5f, 1.5f, 0.95f, 0, 2.5f, 1.5f, 1.3f, 0, 0, 0);
        rotLR(c, "leg_back", 0, 0, 0, 0, 0.35f, 55, 0, 0, 0.95f, 55, 0, 0, 1.3f, 0, 0, 0);
        rotLR(c, "leg_front", 0, 0, 0, 0, 0.35f, -10, 0, 8, 0.55f, -45, 0, 5, 0.95f, -40, 0, 5, 1.3f, 0, 0, 0);
        rotLR(c, "leg_front_lower", 0, 0, 0, 0, 0.35f, 40, 0, 0, 0.55f, -10, 0, 0, 0.95f, -5, 0, 0, 1.3f, 0, 0, 0);
        c.rot("neck1", 0, 0, 0, 0, 0.35f, 25, 0, 0, 0.95f, 25, 0, 0, 1.3f, 0, 0, 0);
        c.rot("head", 0, 0, 0, 0, 0.35f, 25, 0, 0, 0.95f, 25, 0, 0, 1.3f, 0, 0, 0);
        for (int i = 0; i < q.tail.length; i++) c.rot(q.tail[i].name, 0, 0, 0, 0, 0.35f, 40, 0, 0, 0.95f, 40, 0, 0, 1.3f, 0, 0, 0);
        rotLR(c, "ear", 0, 0, 0, 0, 0.35f, -15, 0, 15, 0.95f, -15, 0, 15, 1.3f, 0, 0, 0);
        def.anim("cast", c);
        return def;
    }
}
