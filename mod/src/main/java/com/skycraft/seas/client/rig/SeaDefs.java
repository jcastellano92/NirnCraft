package com.skycraft.seas.client.rig;

import com.skycraft.creatures.client.rig.Anim;
import com.skycraft.creatures.client.rig.Bone;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.client.rig.Rig;
import com.skycraft.creatures.client.rig.Serpent;
import com.skycraft.creatures.client.rig.Winged;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Rig models of the seas module's creatures, in the creatures module's plain-Java rig kit (so this class has no
 * Minecraft dependencies: {@link SeaRigDump} exports the UV layouts for {@code tools/textures/seas.py}).
 * <ul>
 *     <li>dreugh (and the queen, scaled): a domed shell carapace, a small head with eye stalks, two clawed arms and a
 *     skirt of six tentacles;</li>
 *     <li>sea leviathan: a kraken, long mantle and fins behind a great-eyed head, eight jointed arms;</li>
 *     <li>giant slaughterfish, school fish, whale: fish and whale bodies on the serpent plan;</li>
 *     <li>sea netch: a pulsing bell trailing stinging threads;</li>
 *     <li>sea crab: a broad carapace on six jointed legs, two great claws, eyes on stalks;</li>
 *     <li>seal: a sleek body, flippers, a whiskered head;</li>
 *     <li>hawk: a raptor on the winged plan.</li>
 * </ul>
 */
public final class SeaDefs {
    public static final Map<String, Supplier<CreatureDef>> ALL = new LinkedHashMap<>();

    static {
        ALL.put("dreugh", SeaDefs::dreugh);
        ALL.put("sea_leviathan", SeaDefs::seaLeviathan);
        ALL.put("giant_slaughterfish", SeaDefs::giantSlaughterfish);
        ALL.put("sea_netch", SeaDefs::seaNetch);
        ALL.put("whale", SeaDefs::whale);
        ALL.put("sea_crab", SeaDefs::seaCrab);
        ALL.put("seal", SeaDefs::seal);
        ALL.put("hawk", SeaDefs::hawk);
        ALL.put("school_fish", SeaDefs::schoolFish);
    }

    private SeaDefs() {}

    // ================================================================================================ dreugh

    static CreatureDef dreugh() {
        Rig rig = new Rig("dreugh");
        Bone body = rig.root().child("body", 0, -16, 0);
        body.box("shell", -8, -5, -8, 16, 8, 16).tag("carapace");
        body.box("shell", -6, -8, -6, 12, 3, 12).tag("dome");
        body.box("shell", -1, -10, -5, 2, 2, 10).tag("ridge");
        body.box("flesh", -7, 3, -7, 14, 2, 14).tag("belly");
        // the head under the front of the shell, with eye stalks and mandibles
        Bone head = body.child("head", 0, 0, -8);
        head.box("flesh", -3, -2, -4, 6, 5, 4).tag("face");
        head.box("eye", -3, -5, -3, 1, 3, 1).tag("stalk");
        head.box("eye", 2, -5, -3, 1, 3, 1).tag("stalk");
        Bone jaw = head.child("jaw", 0, 3, -2);
        jaw.box("claw", -2, 0, -2, 4, 1, 2).tag("mandible");
        // two arms reaching forward with heavy claws
        body.pair("arm", 7, 0, -5, (b, side) -> {
            b.rot(10, 15, 0);
            b.sbox("shell", -1.5f, -1.5f, -7, 3, 3, 8).tag("arm");
            Bone claw = b.child("claw", 0, 0, -7);
            claw.sbox("shell", -2.5f, -2, -5, 5, 4, 5).tag("palm");
            claw.sbox("claw", -2, -1, -9, 2, 2, 4).tag("finger");
            Bone pincer = claw.child("pincer", 0.5f, 1, -5).rot(15, 0, 0);
            pincer.sbox("claw", -1, 0, -4, 2, 2, 4).tag("finger");
        });
        // a skirt of tentacles under the shell, three pairs, each in three joints
        float[] tz = {-4, 0, 4};
        String[] tn = {"tent_a", "tent_b", "tent_c"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            body.pair(tn[i], 5 - i * 0.5f, 4, tz[i], (b, side) -> {
                b.rot(0, 0, -12);
                b.sbox("flesh", -1, 0, -1, 2, 5, 2).tag("tentacle");
                Bone m = b.child(tn[idx] + "2", 0, 5, 0).rot(10, 0, 0);
                m.sbox("flesh", -1, 0, -1, 2, 5, 2).tag("tentacle");
                Bone e = m.child(tn[idx] + "3", 0, 5, 0).rot(10, 0, 0);
                e.sbox("flesh", -0.5f, 0, -0.5f, 1, 5, 1).tag("tentacle_tip");
            });
        }
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.6f, 0.4f, 0.6f);
        def.look("head", 0.6f);
        def.anim("idle", dreughIdle()).anim("walk", dreughWalk()).anim("run", dreughWalk()).anim("swim", dreughSwim())
                .anim("attack", dreughClaw(true)).anim("attack2", dreughSlam()).anim("special", dreughThreat())
                .anim("roar", dreughThreat()).anim("summon", dreughThreat()).anim("death", dreughDeath()).anim("hurt", dreughHurt());
        return def;
    }

    private static final String[] TENTS = {"tent_a", "tent_b", "tent_c"};

    private static void tentacles(Anim a, float amp, float offset, int cycles) {
        for (int i = 0; i < 3; i++) {
            for (String s : new String[]{"_right", "_left"}) {
                float ph = i * 0.17f + (s.equals("_left") ? 0.5f : 0f);
                a.wave(TENTS[i] + s, Anim.Target.ROTATION, 0, amp, ph, offset, cycles);
                a.wave(TENTS[i] + "2" + s, Anim.Target.ROTATION, 0, amp * 0.8f, ph - 0.1f, offset * 0.5f, cycles);
                a.wave(TENTS[i] + "3" + s, Anim.Target.ROTATION, 0, amp * 0.6f, ph - 0.2f, offset * 0.3f, cycles);
            }
        }
    }

    private static Anim dreughIdle() {
        Anim a = Anim.loop(3f);
        tentacles(a, 10f, 0f, 1);
        a.bob("body", 1, 0.5f, 0f, 1);
        a.swing("arm_right", 0, 5f, 0f);
        a.swing("arm_left", 0, 5f, 0.4f);
        a.swing("pincer_right", 0, 12f, 0.2f);
        a.swing("pincer_left", 0, 12f, 0.7f);
        a.swing("jaw", 0, 10f, 0f, 5f);
        return a;
    }

    private static Anim dreughWalk() {
        Anim a = Anim.loop(0.8f);
        tentacles(a, 24f, 0f, 1);
        a.bob("body", 1, 0.8f, 0.25f, 2);
        a.swingN("body", 2, 4f, 0f, 1);
        return a;
    }

    private static Anim dreughSwim() {
        Anim a = Anim.loop(1.2f);
        // tentacles trail behind and pulse; the shell rocks
        tentacles(a, 18f, 55f, 1);
        a.hold("arm_right", -10, 25, 0);
        a.hold("arm_left", -10, -25, 0);
        a.swing("body", 0, 6f, 0f, -8f);
        a.bob("body", 1, 1.2f, 0f, 1);
        return a;
    }

    private static Anim dreughClaw(boolean right) {
        Anim a = Anim.once(0.7f);
        String arm = right ? "arm_right" : "arm_left";
        String pincer = right ? "pincer_right" : "pincer_left";
        a.rot(arm, 0, 0, 0, 0, 0.25f, -45, 0, 0, 0.38f, 25, 0, 0, 0.7f, 0, 0, 0);
        a.linear().rot(pincer, 0, 0, 0, 0, 0.25f, 40, 0, 0, 0.38f, -10, 0, 0, 0.7f, 0, 0, 0).smooth();
        a.rot("body", 0, 0, 0, 0, 0.25f, -6, right ? -10 : 10, 0, 0.38f, 6, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    private static Anim dreughSlam() {
        Anim a = Anim.once(0.9f);
        for (String s : new String[]{"_right", "_left"}) {
            a.rot("arm" + s, 0, 0, 0, 0, 0.35f, -70, 0, 0, 0.5f, 35, 0, 0, 0.9f, 0, 0, 0);
            a.rot("pincer" + s, 0, 0, 0, 0, 0.35f, 45, 0, 0, 0.5f, 0, 0, 0, 0.9f, 0, 0, 0);
        }
        a.rot("body", 0, 0, 0, 0, 0.35f, -15, 0, 0, 0.5f, 12, 0, 0, 0.9f, 0, 0, 0);
        return a;
    }

    private static Anim dreughThreat() {
        Anim a = Anim.once(1.4f);
        a.rot("body", 0, 0, 0, 0, 0.4f, -25, 0, 0, 1.0f, -25, 0, 0, 1.4f, 0, 0, 0);
        for (String s : new String[]{"_right", "_left"}) {
            float o = s.equals("_right") ? 1 : -1;
            a.rot("arm" + s, 0, 0, 0, 0, 0.4f, -80, 30 * o, 0, 1.0f, -75, 30 * o, 0, 1.4f, 0, 0, 0);
            a.linear().rot("pincer" + s, 0, 0, 0, 0, 0.4f, 45, 0, 0, 0.6f, 0, 0, 0, 0.8f, 45, 0, 0, 1.0f, 0, 0, 0, 1.4f, 0, 0, 0).smooth();
        }
        a.rot("jaw", 0, 0, 0, 0, 0.4f, 35, 0, 0, 1.0f, 35, 0, 0, 1.4f, 0, 0, 0);
        return a;
    }

    private static Anim dreughDeath() {
        Anim a = Anim.once(1.2f);
        a.pos("body", 0, 0, 0, 0, 0.8f, 0, -10, 0, 1.2f, 0, -10, 0);
        a.rot("body", 0, 0, 0, 0, 0.8f, 0, 0, 160, 1.2f, 0, 0, 170);
        tentacles(a, 0f, 0f, 1);
        return a;
    }

    private static Anim dreughHurt() {
        Anim a = Anim.once(0.4f);
        a.rot("body", 0, 0, 0, 0, 0.1f, 10, 0, 6, 0.4f, 0, 0, 0);
        return a;
    }

    // ================================================================================================ sea leviathan

    static CreatureDef seaLeviathan() {
        Rig rig = new Rig("sea_leviathan");
        Bone body = rig.root().child("body", 0, -14, 0);
        Bone mantle = body.child("mantle", 0, -2, 2);
        mantle.box("hide", -7, -7, 0, 14, 13, 12).tag("mantle");
        Bone mantle2 = mantle.child("mantle2", 0, 0, 11);
        mantle2.box("hide", -6, -6, 0, 12, 11, 10).tag("mantle");
        Bone mantle3 = mantle2.child("mantle3", 0, 0, 9);
        mantle3.box("hide", -4, -4, 0, 8, 8, 8).tag("mantle_tip");
        mantle3.pair("fin", 4, 0, 2, (b, s) -> {
            b.rot(0, 0, 10);
            b.sbox("fin", -8, 0, 0, 8, 0, 8).tag("fin");
        });
        Bone head = body.child("head", 0, 0, 0);
        head.box("hide", -8, -8, -8, 16, 14, 10).tag("head");
        head.box("eye", -9, -4, -6, 1, 4, 4).tag("eye");
        head.box("eye", 8, -4, -6, 1, 4, 4).tag("eye");
        head.box("beak", -2, 2, -9, 4, 3, 2).tag("beak");
        // eight arms from the front of the head, in a ring
        float[][] ring = {{3.5f, -5}, {6, -1}, {5, 3}, {2, 5}};
        for (int i = 0; i < ring.length; i++) {
            final int idx = i;
            String n = "arm" + (char) ('a' + i);
            head.pair(n, ring[i][0], ring[i][1], -8, (b, side) -> {
                b.rot(ring[idx][1] * 3, 12 + idx * 4, 0);
                int w = 3;
                Bone prev = b;
                prev.sbox("arm", -w / 2f, -w / 2f, -8, w, w, 8).tag("arm");
                for (int seg = 2; seg <= 4; seg++) {
                    int ww = seg >= 4 ? 1 : 2;
                    Bone nb = prev.child(n + seg, 0, 0, -8);
                    nb.sbox(seg >= 3 ? "sucker" : "arm", -ww / 2f, -ww / 2f, -8, ww, ww, 8).tag(seg >= 4 ? "arm_tip" : "arm");
                    prev = nb;
                }
            });
        }
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.8f, 0.5f, 0.6f);
        def.look("head", 0.3f);
        Anim swim = krakenArms(Anim.loop(2.0f), 22f, 8f);
        swim.wave("mantle", Anim.Target.ROTATION, 0, 4f, 0f, 0, 1);
        swim.wave("mantle2", Anim.Target.ROTATION, 0, 6f, -0.1f, 0, 1);
        swim.swing("fin_right", 2, 20f, 0f);
        swim.swing("fin_left", 2, -20f, 0f);
        Anim idle = krakenArms(Anim.loop(4f), 12f, 15f);
        idle.bob("body", 1, 1f, 0f, 1);
        def.anim("idle", idle).anim("walk", swim).anim("swim", swim).anim("run", swim)
                .anim("attack", krakenLash()).anim("attack2", krakenGrab()).anim("special", krakenRear()).anim("roar", krakenRear())
                .anim("special2", krakenRear()).anim("death", krakenDeath()).anim("hurt", dreughHurt());
        return def;
    }

    private static Anim krakenArms(Anim a, float amp, float spread) {
        for (int i = 0; i < 4; i++) {
            String n = "arm" + (char) ('a' + i);
            for (String s : new String[]{"_right", "_left"}) {
                float ph = i * 0.13f + (s.equals("_left") ? 0.07f : 0f);
                a.wave(n + s, Anim.Target.ROTATION, 0, amp * 0.6f, ph, spread * 0.3f, 1);
                for (int seg = 2; seg <= 4; seg++) a.wave(n + seg + s, Anim.Target.ROTATION, 0, amp, ph - seg * 0.08f, spread * 0.2f, 1);
            }
        }
        return a;
    }

    private static Anim krakenLash() {
        Anim a = Anim.once(1.0f);
        for (String s : new String[]{"_right", "_left"}) {
            a.rot("arma" + s, 0, 0, 0, 0, 0.4f, -50, 0, 0, 0.6f, 40, 0, 0, 1.0f, 0, 0, 0);
            a.rot("arma2" + s, 0, 0, 0, 0, 0.4f, -30, 0, 0, 0.6f, 30, 0, 0, 1.0f, 0, 0, 0);
            a.rot("armb" + s, 0, 0, 0, 0, 0.45f, 0, 40, 0, 0.62f, 0, -30, 0, 1.0f, 0, 0, 0);
        }
        a.rot("head", 0, 0, 0, 0, 0.4f, -10, 0, 0, 0.6f, 8, 0, 0, 1.0f, 0, 0, 0);
        return a;
    }

    private static Anim krakenGrab() {
        Anim a = Anim.once(1.1f);
        for (int i = 0; i < 4; i++) {
            String n = "arm" + (char) ('a' + i);
            for (String s : new String[]{"_right", "_left"}) {
                a.rot(n + s, 0, 0, 0, 0, 0.4f, -20, 0, 0, 0.7f, 30, 0, 0, 1.1f, 0, 0, 0);
                a.rot(n + "3" + s, 0, 0, 0, 0, 0.4f, -10, 0, 0, 0.7f, 50, 0, 0, 1.1f, 0, 0, 0);
            }
        }
        return a;
    }

    private static Anim krakenRear() {
        Anim a = Anim.once(1.6f);
        a.rot("body", 0, 0, 0, 0, 0.5f, -45, 0, 0, 1.2f, -45, 0, 0, 1.6f, 0, 0, 0);
        krakenArms(a, 30f, -20f);
        return a;
    }

    private static Anim krakenDeath() {
        Anim a = Anim.once(1.6f);
        a.pos("body", 0, 0, 0, 0, 1.2f, 0, -8, 0, 1.6f, 0, -8, 0);
        a.rot("body", 0, 0, 0, 0, 1.2f, 20, 0, 90, 1.6f, 20, 0, 100);
        return a;
    }

    // ================================================================================================ fish and whales

    static CreatureDef giantSlaughterfish() {
        Serpent s = new Serpent("giant_slaughterfish");
        s.headW = 6;
        s.headH = 6;
        s.headL = 8;
        s.jawH = 2;
        s.segs = 4;
        s.segLen = 6;
        s.segW = 7;
        s.segH = 7;
        s.taper = 0.35f;
        s.height = 7;
        s.fins = true;
        s.finLen = 6;
        s.tailFin = true;
        s.spines = true;
        s.skin = "scales";
        s.belly = "belly";
        s.headMat = "head";
        s.finMat = "fin";
        s.build();
        s.head.box("teeth", -2.5f, 1, -8.5f, 5, 1, 2).tag("teeth");
        s.jawBone.box("teeth", -2, -1, -6.5f, 4, 1, 1).tag("teeth");
        s.head.box("head", -3.5f, -3.5f, -6, 7, 1, 5).tag("brow");
        return s.def();
    }

    static CreatureDef schoolFish() {
        Serpent s = new Serpent("school_fish");
        s.headW = 2;
        s.headH = 3;
        s.headL = 3;
        s.jawH = 1;
        s.segs = 2;
        s.segLen = 3;
        s.segW = 2;
        s.segH = 3;
        s.taper = 0.6f;
        s.height = 2;
        s.fins = true;
        s.finLen = 2;
        s.tailFin = true;
        s.skin = "scales";
        s.belly = null;
        s.headMat = "head";
        s.finMat = "fin";
        s.build();
        return s.def();
    }

    static CreatureDef whale() {
        Serpent s = new Serpent("whale");
        s.headW = 14;
        s.headH = 12;
        s.headL = 18;
        s.jawH = 4;
        s.segs = 5;
        s.segLen = 13;
        s.segW = 16;
        s.segH = 12;
        s.taper = 0.3f;
        s.height = 10;
        s.fins = true;
        s.finLen = 14;
        s.tailFin = true;
        s.lateral = false;
        s.skin = "hide";
        s.belly = "belly";
        s.headMat = "hide";
        s.finMat = "fin";
        s.build();
        // throat grooves and a blowhole
        s.head.box("belly", -6, 4, -17, 12, 1, 15).tag("throat");
        s.head.box("hide", -1, -7, -6, 2, 1, 2).tag("blowhole");
        CreatureDef def = s.def();
        // slow, majestic beats
        def.anim("swim", s.travel(3.2f, 10f)).anim("walk", s.travel(3.2f, 10f)).anim("idle", s.travel(5f, 5f));
        return def;
    }

    // ================================================================================================ sea netch

    static CreatureDef seaNetch() {
        Rig rig = new Rig("sea_netch");
        Bone bell = rig.root().child("bell", 0, -18, 0);
        bell.box("bell", -8, -4, -8, 16, 7, 16).tag("bell");
        bell.box("bell", -6, -7, -6, 12, 3, 12).tag("crown");
        bell.box("glow", -3, -8, -3, 6, 1, 6).tag("crown_glow");
        bell.box("rim", -9, 3, -9, 18, 1, 18).tag("rim");
        float[][] spots = {{6, -6}, {6, 6}, {7, 0}, {3, -7}, {3, 7}};
        for (int i = 0; i < spots.length; i++) {
            final int idx = i;
            String n = "thread_" + (char) ('a' + i);
            bell.pair(n, spots[i][0], 4, spots[i][1], (b, s) -> {
                b.sbox("thread", -0.5f, 0, -0.5f, 1, 6, 1).tag("thread");
                Bone m = b.child(n + "2", 0, 6, 0);
                m.sbox("thread", -0.5f, 0, -0.5f, 1, 6, 1).tag("thread");
                Bone e = m.child(n + "3", 0, 6, 0);
                e.sbox("thread", -0.5f, 0, -0.5f, 1, 5 - idx % 2, 1).tag("thread_tip");
            });
        }
        Bone heart = bell.child("heart", 0, 3, 0);
        heart.box("glow", -3, 0, -3, 6, 4, 6).tag("heart");
        CreatureDef def = new CreatureDef(rig);
        Anim pulse = Anim.loop(2f);
        pulse.scale("bell", 0, 1, 1, 1, 0.5f, 1.12f, 0.88f, 1.12f, 1.0f, 0.92f, 1.08f, 0.92f, 2f, 1, 1, 1);
        pulse.bob("bell", 1, 1.4f, 0.1f, 1);
        for (int i = 0; i < spots.length; i++) {
            String n = "thread_" + (char) ('a' + i);
            for (String s : new String[]{"_right", "_left"}) {
                float ph = i * 0.11f + (s.equals("_left") ? 0.3f : 0f);
                pulse.wave(n + s, Anim.Target.ROTATION, 0, 10f, ph, 0, 1);
                pulse.wave(n + "2" + s, Anim.Target.ROTATION, 0, 14f, ph - 0.1f, 0, 1);
                pulse.wave(n + "3" + s, Anim.Target.ROTATION, 2, 16f, ph - 0.2f, 0, 1);
            }
        }
        Anim sting = Anim.once(0.8f);
        sting.scale("bell", 0, 1, 1, 1, 0.3f, 1.2f, 0.8f, 1.2f, 0.8f, 1, 1, 1);
        Anim death = Anim.once(1.2f);
        death.scale("bell", 0, 1, 1, 1, 1.2f, 1.3f, 0.4f, 1.3f);
        death.pos("bell", 0, 0, 0, 0, 1.2f, 0, -12, 0);
        Anim hurt = Anim.once(0.4f);
        hurt.scale("bell", 0, 1, 1, 1, 0.1f, 0.9f, 1.1f, 0.9f, 0.4f, 1, 1, 1);
        def.anim("idle", pulse).anim("walk", pulse).anim("swim", pulse).anim("hover", pulse).anim("attack", sting)
                .anim("death", death).anim("hurt", hurt);
        return def;
    }

    // ================================================================================================ crabs and seals

    static CreatureDef seaCrab() {
        Rig rig = new Rig("sea_crab");
        Bone body = rig.root().child("body", 0, -9, 0);
        body.box("shell", -9, -4, -7, 18, 6, 14).tag("carapace");
        body.box("shell", -7, -6, -5, 14, 2, 10).tag("dome");
        body.box("flesh", -7, 2, -6, 14, 2, 12).tag("belly");
        body.box("shell", -5, -3, -8, 10, 3, 1).tag("brow");
        body.pair("eye", 2.5f, -4, -7, (b, s) -> b.sbox("eye", -0.5f, -3, -0.5f, 1, 3, 1).tag("stalk"));
        Bone mouth = body.child("mouth", 0, 0, -7);
        mouth.box("flesh", -2, 0, -1, 4, 2, 1).tag("mouth");
        // claws
        body.pair("claw_arm", 7, 0, -6, (b, s) -> {
            b.rot(0, -25, -10);
            b.sbox("shell", -2, -1.5f, -7, 3, 3, 7).tag("arm");
            Bone claw = b.child("claw", -0.5f, 0, -7);
            claw.sbox("shell", -3, -2.5f, -6, 6, 5, 6).tag("palm");
            claw.sbox("claw", -2.5f, -1.5f, -10, 2, 3, 4).tag("finger");
            Bone pincer = claw.child("pincer", 1.5f, 0, -6).rot(0, -20, 0);
            pincer.sbox("claw", -1, -1.5f, -4, 2, 3, 4).tag("finger");
        });
        // six walking legs
        float[] lz = {-3, 1, 5};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            String n = "leg_" + (char) ('a' + i);
            body.pair(n, 8, 0, lz[i], (b, s) -> {
                b.rot(0, (idx - 1) * -20, -30);
                b.sbox("shell", -8, -1, -1, 8, 2, 2).tag("leg");
                Bone low = b.child(n + "_low", -8, 0, 0).rot(0, 0, -55);
                low.sbox("shell", -8, -0.5f, -0.5f, 8, 1, 1).tag("leg_tip");
            });
        }
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.4f, 0.3f, 0.6f);
        def.look("body", 0.15f);
        Anim idle = Anim.loop(3f);
        idle.bob("body", 1, 0.3f, 0f, 1);
        idle.swing("pincer_right", 1, 10f, 0f);
        idle.swing("pincer_left", 1, -10f, 0.5f);
        idle.swing("eye_right", 0, 10f, 0.2f);
        idle.swing("eye_left", 0, 10f, 0.6f);
        Anim walk = Anim.loop(0.5f);
        for (int i = 0; i < 3; i++) {
            String n = "leg_" + (char) ('a' + i);
            walk.swing(n + "_right", 2, 14f, i * 0.33f);
            walk.swing(n + "_left", 2, -14f, i * 0.33f + 0.5f);
            walk.swing(n + "_right", 1, 12f, i * 0.33f + 0.25f);
            walk.swing(n + "_left", 1, -12f, i * 0.33f + 0.75f);
        }
        walk.bob("body", 1, 0.4f, 0f, 2);
        Anim attack = Anim.once(0.6f);
        attack.rot("claw_arm_right", 0, 0, 0, 0, 0.2f, -30, 20, 0, 0.32f, 10, -10, 0, 0.6f, 0, 0, 0);
        attack.linear().rot("pincer_right", 0, 0, 0, 0, 0.2f, 0, -30, 0, 0.3f, 0, 10, 0, 0.6f, 0, 0, 0).smooth();
        Anim attack2 = Anim.once(0.8f);
        for (String s : new String[]{"_right", "_left"}) {
            float o = s.equals("_right") ? 1 : -1;
            attack2.rot("claw_arm" + s, 0, 0, 0, 0, 0.3f, -45, 25 * o, 0, 0.45f, 15, -15 * o, 0, 0.8f, 0, 0, 0);
            attack2.linear().rot("pincer" + s, 0, 0, 0, 0, 0.3f, 0, -35 * o, 0, 0.42f, 0, 10 * o, 0, 0.8f, 0, 0, 0).smooth();
        }
        Anim death = Anim.once(1f);
        death.rot("body", 0, 0, 0, 0, 0.6f, 0, 0, 180, 1f, 0, 0, 180);
        death.pos("body", 0, 0, 0, 0, 0.6f, 0, 2, 0, 1f, 0, 2, 0);
        Anim hurt = Anim.once(0.4f);
        hurt.rot("body", 0, 0, 0, 0, 0.1f, 8, 0, 0, 0.4f, 0, 0, 0);
        def.anim("idle", idle).anim("walk", walk).anim("run", walk).anim("swim", walk).anim("attack", attack).anim("attack2", attack2)
                .anim("death", death).anim("hurt", hurt);
        return def;
    }

    static CreatureDef seal() {
        Rig rig = new Rig("seal");
        Bone body = rig.root().child("body", 0, -5, 0);
        body.box("fur", -4, -4, -6, 8, 8, 12).tag("body");
        body.box("belly", -3.5f, 3, -5, 7, 1, 10).tag("belly");
        Bone neck = body.child("neck", 0, -1, -6);
        neck.box("fur", -3, -3, -3, 6, 6, 3).tag("neck");
        Bone head = neck.child("head", 0, -1, -3);
        head.box("fur", -3, -3, -5, 6, 5, 5).tag("head");
        head.box("muzzle", -2, -1, -7, 4, 3, 2).tag("muzzle");
        Bone tail = body.child("tail", 0, 0, 6);
        tail.box("fur", -3, -3, 0, 6, 6, 5).tag("hips");
        Bone hind = tail.child("hind", 0, 1, 5);
        hind.pair("hind_flipper", 1, 0, 0, (b, s) -> {
            b.rot(0, 15, 0);
            b.sbox("flipper", -3, 0, 0, 3, 1, 5).tag("flipper");
        });
        body.pair("flipper", 4, 2, -3, (b, s) -> {
            b.rot(0, 30, -30);
            b.sbox("flipper", -4, 0, -1, 4, 1, 3).tag("flipper");
        });
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.7f, 0.5f, 0.6f);
        def.look("head", 0.7f).look("neck", 0.3f);
        Anim idle = Anim.loop(4f);
        idle.bob("body", 1, 0.3f, 0f, 1);
        idle.swing("head", 0, 6f, 0.2f, -5f);
        idle.swing("hind_flipper_right", 1, 10f, 0f);
        idle.swing("hind_flipper_left", 1, -10f, 0.3f);
        Anim walk = Anim.loop(0.8f);
        walk.swing("body", 0, 8f, 0f);
        walk.swing("tail", 0, -12f, 0.1f);
        walk.bob("body", 1, 1.2f, 0f, 1);
        walk.swing("flipper_right", 0, 30f, 0f);
        walk.swing("flipper_left", 0, 30f, 0f);
        Anim swim = Anim.loop(1.0f);
        swim.swing("body", 1, 6f, 0f);
        swim.swing("tail", 1, 18f, -0.1f);
        swim.swing("hind", 1, 25f, -0.2f);
        swim.hold("flipper_right", 0, 60, 0);
        swim.hold("flipper_left", 0, -60, 0);
        Anim death = Anim.once(1f);
        death.rot("body", 0, 0, 0, 0, 0.6f, 0, 0, 90, 1f, 0, 0, 90);
        Anim hurt = Anim.once(0.4f);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -25, 0, 0, 0.4f, 0, 0, 0);
        def.anim("idle", idle).anim("walk", walk).anim("run", walk).anim("swim", swim).anim("death", death).anim("hurt", hurt);
        return def;
    }

    // ================================================================================================ hawk

    static CreatureDef hawk() {
        Winged w = new Winged("hawk");
        w.bodyW = 3;
        w.bodyH = 3;
        w.bodyL = 6;
        w.neckLen = 1;
        w.neckW = 2;
        w.headW = 3;
        w.headH = 3;
        w.headL = 3;
        w.jawL = 2;
        w.wingSpan = 24;
        w.wingChord = 6;
        w.tailSegs = 1;
        w.tailLen = 3;
        w.tailW = 2;
        w.stand = 4;
        w.tailFin = false;
        w.skin = "feather";
        w.wingMat = "wing";
        w.headMat = "head";
        w.clawMat = "talon";
        w.flap = 40;
        w.build();
        w.body.box("breast", -1.5f, -1, -3.5f, 3, 3, 3).tag("breast");
        w.head.box("beak", -0.5f, -0.5f, -4.5f, 1, 2, 2).tag("beak");
        Bone tail = w.tail[0];
        tail.box("tailfeather", -1.5f, -0.5f, 1, 3, 0, 7).tag("tailfeather");
        tail.pair("tail_fan", 1, -0.4f, 1, (b, s) -> {
            b.rot(0, -12, 0);
            b.sbox("tailfeather", -1.5f, 0, 0, 3, 0, 6).tag("tailfeather");
        });
        CreatureDef def = w.def();
        // soaring: a long glide with wings held out and the odd beat
        Anim fly = Anim.loop(2.4f);
        fly.rot("wing_right", 0, 0, 0, 4, 0.2f, 0, 0, 40, 0.4f, 0, 0, -10, 0.6f, 0, 0, 4, 2.4f, 0, 0, 4);
        fly.rot("wing_left", 0, 0, 0, -4, 0.2f, 0, 0, -40, 0.4f, 0, 0, 10, 0.6f, 0, 0, -4, 2.4f, 0, 0, -4);
        fly.hold("leg_right", 70, 0, 0);
        fly.hold("leg_left", 70, 0, 0);
        fly.bob("body", 1, 0.8f, 0f, 1);
        def.anim("fly", fly).anim("hover", w.fly());
        return def;
    }
}
