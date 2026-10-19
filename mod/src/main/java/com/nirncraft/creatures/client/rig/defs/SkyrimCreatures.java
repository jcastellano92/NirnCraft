package com.nirncraft.creatures.client.rig.defs;

import com.nirncraft.creatures.client.rig.Anim;
import com.nirncraft.creatures.client.rig.Arachnid;
import com.nirncraft.creatures.client.rig.Biped;
import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.client.rig.Rig;
import com.nirncraft.creatures.client.rig.Serpent;

import java.util.function.Consumer;

/** Skyrim's creatures (remodelled wildlife and monsters, and the new Skyrim kinds). */
final class SkyrimCreatures {
    private SkyrimCreatures() {}

    static void register() {
        Defs.reg("frostbite_spider", SkyrimCreatures::frostbiteSpider);
        Defs.reg("chaurus", SkyrimCreatures::chaurus);
        Defs.reg("chaurus_hunter", SkyrimCreatures::chaurusHunter);
        Defs.reg("riekling", SkyrimCreatures::riekling);
        Defs.reg("ash_spawn", SkyrimCreatures::ashSpawn);
        Defs.reg("gargoyle", SkyrimCreatures::gargoyle);
        Defs.reg("spriggan", SkyrimCreatures::spriggan);
        Defs.reg("hagraven", SkyrimCreatures::hagraven);
        Defs.reg("ice_wraith", SkyrimCreatures::iceWraith);
        Defs.reg("wispmother", SkyrimCreatures::wispmother);
        Defs.reg("wisp", SkyrimCreatures::wisp);
        Defs.reg("will_o_wisp", SkyrimCreatures::willOWisp);
    }

    // ============================================================================================== helpers

    /** Runs {@code f} on both bones of a side pair (to add mirrored {@code sbox}es after a body plan built them). */
    private static void both(Rig rig, String base, Consumer<Bone> f) {
        f.accept(rig.bone(base + "_right"));
        f.accept(rig.bone(base + "_left"));
    }

    /** Rotation keys for {@code <base>_right}, mirrored (y/z negated) onto {@code <base>_left}. */
    private static void rotPair(Anim a, String base, float... keys) {
        a.rot(base + "_right", keys);
        float[] m = keys.clone();
        for (int i = 0; i < m.length; i += 4) {
            m[i + 2] = -m[i + 2];
            m[i + 3] = -m[i + 3];
        }
        a.rot(base + "_left", m);
    }

    /** A rotation wave on {@code <base>_right} and its mirror (y/z axes negated) on {@code <base>_left}. */
    private static void swingPair(Anim a, String base, int axis, float amp, float phase, float leftPhase) {
        a.swing(base + "_right", axis, amp, phase);
        a.swing(base + "_left", axis, axis == 0 ? amp : -amp, leftPhase);
    }

    /**
     * Arachnid builds its leg fan with yaw +50 (front) .. -50 (back), but a positive yaw swings a leg that points
     * along -x toward +z (backward), so the legs cross. Re-fans them: front pairs reach forward, back pairs back.
     */
    private static void fanLegs(Arachnid a, float front, float back) {
        for (int i = 0; i < a.legPairs; i++) {
            float f = a.legPairs == 1 ? 0.5f : (float) i / (a.legPairs - 1);
            float yaw = front + (back - front) * f;
            a.rig.bone("leg" + (i + 1) + "_right").rot(0, -yaw, 0);
            a.rig.bone("leg" + (i + 1) + "_left").rot(0, -yaw, 0);
        }
    }

    /**
     * Many-legged walk for {@link Arachnid} rigs: alternating tetrapod gait where each leg swings forward lifted
     * (z pulse only upward, so the feet never dig into the ground) and pushes back planted.
     */
    private static Anim arachWalk(int pairs, float len, float amp, float lift) {
        Anim a = Anim.loop(len);
        int n = 12;
        for (int i = 1; i <= pairs; i++) {
            float phase = (i % 2 == 0) ? 0.5f : 0f;
            for (int s = 0; s < 2; s++) {
                float ph = phase + (s == 0 ? 0f : 0.5f);
                float sign = s == 0 ? 1 : -1;
                float[] keys = new float[(n + 1) * 4];
                for (int k = 0; k <= n; k++) {
                    double f = (double) k / n + ph;
                    keys[k * 4] = (float) k / n * len;
                    keys[k * 4 + 2] = sign * (float) (amp * Math.sin(2 * Math.PI * f));
                    keys[k * 4 + 3] = sign * (float) (lift * Math.max(0, Math.sin(2 * Math.PI * (f - 0.25))));
                }
                a.rot("leg" + i + (s == 0 ? "_right" : "_left"), keys);
            }
        }
        a.bob("body", 1, 0.4f, 0f, 2);
        a.swingN("abdomen", 1, 3f, 0f, 1);
        a.swingN("body", 2, 2f, 0f, 2);
        return a;
    }

    /** A uniform scale pulse 1 ± amp over a loop (all three axes keyed: a scale channel replaces every axis). */
    private static Anim pulse(Anim a, String bone, float amp, int cycles, float phase) {
        int n = 8 * cycles;
        float[] keys = new float[(n + 1) * 4];
        for (int i = 0; i <= n; i++) {
            float f = (float) i / n;
            float v = 1 + amp * (float) Math.sin(2 * Math.PI * (f * cycles + phase));
            keys[i * 4] = f * a.length;
            keys[i * 4 + 1] = v;
            keys[i * 4 + 2] = v;
            keys[i * 4 + 3] = v;
        }
        return a.scale(bone, keys);
    }

    /** A constant rotation on a side pair (mirrored). */
    private static void holdPair(Anim a, String base, float x, float y, float z) {
        a.hold(base + "_right", x, y, z);
        a.hold(base + "_left", x, -y, -z);
    }

    // ============================================================================================== frostbite spider

    /**
     * Frostbite spider: pale, hairy, banded giant spider with a huge frost-patterned abdomen, a cluster of eyes,
     * hairy chelicerae with fangs and pedipalps. The giant variant is the same model drawn about twice as large.
     */
    static CreatureDef frostbiteSpider() {
        Arachnid a = new Arachnid("frostbite_spider");
        a.bodyW = 9;
        a.bodyH = 6;
        a.bodyL = 9;
        a.headW = 7;
        a.headH = 5;
        a.headL = 5;
        a.fangLen = 4;
        a.abdomenW = 14;
        a.abdomenH = 8;
        a.abdomenL = 14;
        a.abdomenTilt = -14;
        a.legPairs = 4;
        a.legUpper = 12;
        a.legLower = 15;
        a.legW = 3;
        a.legUp = 56;
        a.legDown = 70;
        a.shell = "carapace";
        a.legMat = "leg";
        a.abdomenMat = "abdomen";
        a.fangMat = "fang";
        Rig rig = a.build();
        fanLegs(a, 55, -55);
        // carapace dome on the thorax, raised eye mound on the head
        a.body.box("carapace", -3.5f, -4f, -3.5f, 7, 1, 7).tag("dome");
        a.head.box("carapace", -2.5f, -3.5f, -5f, 5, 1, 3).tag("eyes");
        // bulbous abdomen: three crossing blocks round it off (tall, long and wide), a waist and spinnerets
        a.abdomen.box("abdomen", -5, -6, 1.5f, 10, 12, 12).tag("abdomen_top");
        a.abdomen.box("abdomen", -5, -4, -0.5f, 10, 8, 18).tag("abdomen_rear");
        a.abdomen.box("abdomen", -6, -5, 0.5f, 12, 10, 15, 0.01f).tag("abdomen_side");
        a.abdomen.box("abdomen", -3, -7, 4, 6, 1, 8).tag("abdomen_crown");
        a.abdomen.box("carapace", -2, -2, -2f, 4, 4, 2).tag("waist");
        a.abdomen.box("fang", -1.5f, 1.5f, 17f, 3, 2, 2).tag("spinneret");
        // hairy chelicerae over the fangs
        both(rig, "fang", f -> f.sbox("hair", -1.5f, -1.5f, -1.5f, 3, 3, 3).tag("chelicera"));
        // pedipalps
        a.head.pair("palp", 3f, 1f, -4.5f, (b, side) -> {
            b.rot(-50, 0, 12);
            b.sbox("leg", -1, 0, -1, 2, 4, 2).tag("palp");
            b.child("palp_tip", 0, 3.5f, 0).rot(40, 0, 0).sbox("leg", -0.5f, 0, -0.5f, 1, 3, 1).tag("palp");
        });
        CreatureDef d = a.def();
        d.get("idle").swing("palp_right", 0, 8, 0f).swing("palp_left", 0, 8, 0.5f);
        d.anim("walk", arachWalk(4, 0.8f, 16, 22).swing("palp_right", 0, 10, 0f).swing("palp_left", 0, 10, 0.5f));
        d.anim("run", arachWalk(4, 0.5f, 22, 26).swing("palp_right", 0, 12, 0f).swing("palp_left", 0, 12, 0.5f));
        d.anim("attack", spiderStrike());
        d.anim("attack2", spiderStrike());
        d.anim("special", spiderSpit());
        return d;
    }

    /** Rears up on the back legs, front legs and palps raised, fangs spread, then drives the fangs down. */
    private static Anim spiderStrike() {
        Anim a = Anim.once(0.75f);
        a.rot("body", 0, 0, 0, 0, 0.25f, -28, 0, 0, 0.38f, 10, 0, 0, 0.55f, 6, 0, 0, 0.75f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.25f, 0, 3, 2, 0.38f, 0, 0, -3, 0.55f, 0, 0, -2, 0.75f, 0, 0, 0);
        a.rot("abdomen", 0, 0, 0, 0, 0.25f, 26, 0, 0, 0.38f, -6, 0, 0, 0.75f, 0, 0, 0);
        rotPair(a, "leg1", 0, 0, 0, 0, 0.25f, 0, 20, 45, 0.38f, 0, -10, 16, 0.55f, 0, -5, 12, 0.75f, 0, 0, 0);
        rotPair(a, "leg2", 0, 0, 0, 0, 0.25f, 0, 15, 30, 0.38f, 0, -5, 12, 0.55f, 0, -3, 8, 0.75f, 0, 0, 0);
        // the back pairs counter the body's pitch so their feet stay planted
        rotPair(a, "leg3", 0, 0, 0, 0, 0.25f, 26, 0, 10, 0.38f, 0, 0, 4, 0.75f, 0, 0, 0);
        rotPair(a, "leg4", 0, 0, 0, 0, 0.25f, 26, 0, 14, 0.38f, 0, 0, 4, 0.75f, 0, 0, 0);
        rotPair(a, "fang", 0, 0, 0, 0, 0.25f, -45, 0, -25, 0.36f, 35, 0, 10, 0.5f, 25, 0, 5, 0.75f, 0, 0, 0);
        rotPair(a, "palp", 0, 0, 0, 0, 0.25f, -50, 0, 25, 0.38f, -35, 0, 15, 0.6f, -20, 0, 10, 0.75f, 0, 0, 0);
        return a;
    }

    /** Abdomen pumps and squeezes, the head jerks forward with the fangs flared: spitting frost venom. */
    private static Anim spiderSpit() {
        Anim a = Anim.once(0.9f);
        a.rot("abdomen", 0, 0, 0, 0, 0.3f, 22, 0, 0, 0.45f, 8, 0, 0, 0.9f, 0, 0, 0);
        a.scale("abdomen", 0, 1, 1, 1, 0.3f, 1.08f, 1.12f, 0.94f, 0.45f, 0.93f, 0.92f, 1.04f, 0.9f, 1, 1, 1);
        a.rot("body", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.45f, 8, 0, 0, 0.9f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.3f, 0, 1.5f, 1, 0.45f, 0, 0, -1, 0.9f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.32f, -12, 0, 0, 0.42f, 9, 0, 0, 0.9f, 0, 0, 0);
        rotPair(a, "fang", 0, 0, 0, 0, 0.3f, -30, 0, -30, 0.45f, -30, 0, -30, 0.7f, 0, 0, 0, 0.9f, 0, 0, 0);
        for (String leg : new String[]{"leg1", "leg2", "leg3", "leg4"}) {
            rotPair(a, leg, 0, 0, 0, 0, 0.3f, 12, 0, 6, 0.45f, 0, 0, 10, 0.9f, 0, 0, 0);
        }
        rotPair(a, "palp", 0, 0, 0, 0, 0.3f, -20, 0, 10, 0.45f, -45, 0, 15, 0.9f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== chaurus

    /**
     * Chaurus: low, six-legged Falmer livestock insect with a heavily segmented, glossy carapace that arches up over
     * the abdomen and tapers down to a spiked tail, a small shielded head and big curved mandibles.
     */
    static CreatureDef chaurus() {
        Arachnid a = new Arachnid("chaurus");
        a.bodyW = 10;
        a.bodyH = 6;
        a.bodyL = 9;
        a.headW = 6;
        a.headH = 4;
        a.headL = 4;
        a.fangLen = 0;
        a.abdomenW = 12;
        a.abdomenH = 8;
        a.abdomenL = 7;
        a.abdomenTilt = -16;
        a.legPairs = 3;
        a.legUpper = 9;
        a.legLower = 11;
        a.legW = 3;
        a.legUp = 38;
        a.legDown = 68;
        a.shell = "carapace";
        a.legMat = "leg";
        a.build();
        fanLegs(a, 45, -50);
        // overhanging dorsal plates, head shield
        a.body.box("plate", -6, -5, -5, 12, 3, 10).tag("plate");
        a.body.box("belly", -4, 2.5f, -4, 8, 1, 8).tag("belly");
        a.head.box("plate", -3.5f, -3, -4.5f, 7, 2, 5).tag("shield");
        a.head.box("plate", -2.5f, -4, -3, 5, 1, 3).tag("crest");
        // the arched, segmented abdomen
        a.abdomen.box("plate", -6.5f, -5, -0.5f, 13, 2, 8).tag("plate");
        Bone ab2 = a.abdomen.child("abdomen2", 0, 0, 6.5f).rot(-22, 0, 0);
        ab2.box("carapace", -5.5f, -3.5f, 0, 11, 7, 6).tag("segment");
        ab2.box("plate", -6, -4.5f, -0.5f, 12, 2, 7).tag("plate");
        Bone ab3 = ab2.child("abdomen3", 0, 0.5f, 5.5f).rot(-24, 0, 0);
        ab3.box("carapace", -4.5f, -3, 0, 9, 6, 5).tag("segment");
        ab3.box("plate", -5, -4, -0.5f, 10, 2, 6).tag("plate");
        Bone ab4 = ab3.child("abdomen4", 0, 0.5f, 4.5f).rot(-18, 0, 0);
        ab4.box("carapace", -3, -2, 0, 6, 4, 4).tag("segment");
        ab4.box("plate", -3.5f, -3, -0.5f, 7, 2, 5).tag("plate");
        Bone tip = ab4.child("tail_tip", 0, 0, 3.5f).rot(5, 0, 0);
        tip.box("spike", -1, -1.5f, 0, 2, 2, 4).tag("spike");
        // curved mandibles
        a.head.pair("mandible", 2f, 1f, -4f, (b, side) -> {
            b.rot(10, 28, 0);
            b.sbox("mandible", -1, -0.5f, -5, 2, 2, 5).tag("mandible");
            b.child("mandible_tip", 0, 0, -4.5f).rot(0, -60, 0).sbox("mandible", -0.5f, 0, -4, 1, 1, 4).tag("mandible_tip");
        });
        // antennae
        a.head.pair("antenna", 1.5f, -2.5f, -3.5f, (b, side) -> {
            b.rot(-35, 25, 0);
            b.sbox("leg", -0.5f, -0.5f, -6, 1, 1, 6).tag("antenna");
        });
        CreatureDef d = a.def();
        Anim idle = d.get("idle");
        idle.swingN("mandible_right", 1, 6, 0f, 2).swingN("mandible_left", 1, -6, 0f, 2);
        idle.swing("antenna_right", 1, 10, 0f).swing("antenna_left", 1, -10, 0.3f);
        idle.swing("abdomen2", 0, 2, 0.1f).swing("abdomen3", 0, 2, 0.2f).swing("tail_tip", 0, 4, 0.3f);
        for (int r = 0; r < 2; r++) {
            Anim walk = r == 0 ? arachWalk(3, 0.8f, 16, 20) : arachWalk(3, 0.5f, 22, 24);
            walk.swing("abdomen3", 1, 4, 0.1f).swing("tail_tip", 1, 6, 0.2f);
            walk.swing("antenna_right", 0, 10, 0f).swing("antenna_left", 0, 10, 0.5f);
            d.anim(r == 0 ? "walk" : "run", walk);
        }
        d.anim("attack", chaurusBite());
        d.anim("attack2", chaurusBite());
        d.anim("special", chaurusSpit());
        return d;
    }

    /** Lunges with the head low, mandibles flung wide, then snaps them shut. */
    private static Anim chaurusBite() {
        Anim a = Anim.once(0.7f);
        a.rot("body", 0, 0, 0, 0, 0.25f, -12, 0, 0, 0.38f, 8, 0, 0, 0.7f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.25f, 0, 1, 2, 0.38f, 0, -0.5f, -3, 0.55f, 0, 0, -2, 0.7f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.25f, -15, 0, 0, 0.38f, 15, 0, 0, 0.7f, 0, 0, 0);
        rotPair(a, "mandible", 0, 0, 0, 0, 0.25f, 0, 35, 0, 0.35f, 0, -22, 0, 0.45f, 0, -18, 0, 0.7f, 0, 0, 0);
        rotPair(a, "leg1", 0, 0, 0, 0, 0.25f, 0, 15, 20, 0.38f, 0, -5, 14, 0.55f, 0, 0, 8, 0.7f, 0, 0, 0);
        rotPair(a, "leg2", 0, 0, 0, 0, 0.25f, 12, 0, 6, 0.38f, 0, 0, 8, 0.7f, 0, 0, 0);
        rotPair(a, "leg3", 0, 0, 0, 0, 0.25f, 12, 0, 12, 0.38f, 0, 0, 4, 0.7f, 0, 0, 0);
        a.rot("abdomen", 0, 0, 0, 0, 0.25f, 14, 0, 0, 0.38f, -6, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** The arched abdomen pumps, the head rears and jerks forward: spitting poison. */
    private static Anim chaurusSpit() {
        Anim a = Anim.once(0.9f);
        a.rot("abdomen", 0, 0, 0, 0, 0.3f, 24, 0, 0, 0.45f, -10, 0, 0, 0.9f, 0, 0, 0);
        for (String leg : new String[]{"leg1", "leg2", "leg3"}) {
            rotPair(a, leg, 0, 0, 0, 0, 0.3f, 14, 0, 12, 0.45f, 0, 0, 10, 0.9f, 0, 0, 0);
        }
        a.scale("abdomen2", 0, 1, 1, 1, 0.3f, 1.1f, 1.12f, 1, 0.45f, 0.94f, 0.94f, 1, 0.9f, 1, 1, 1);
        a.rot("abdomen3", 0, 0, 0, 0, 0.3f, 10, 0, 0, 0.45f, -8, 0, 0, 0.9f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.3f, -14, 0, 0, 0.45f, 8, 0, 0, 0.9f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.32f, -15, 0, 0, 0.42f, 18, 0, 0, 0.9f, 0, 0, 0);
        rotPair(a, "mandible", 0, 0, 0, 0, 0.3f, 0, 30, 0, 0.6f, 0, 30, 0, 0.9f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== chaurus hunter

    static final float HUNTER_HEIGHT = 14.5f;

    /**
     * Chaurus hunter: the winged, upright form of the chaurus. Stands on two long jointed hind legs with two
     * mantis-like raptorial forelegs held ready, a segmented abdomen hanging behind, a crested head with long
     * mandibles and antennae, and two pairs of veined insect wings folded down its back.
     */
    static CreatureDef chaurusHunter() {
        Rig rig = new Rig("chaurus_hunter");
        Bone body = rig.root().child("body", 0, -HUNTER_HEIGHT, 0).rot(12, 0, 0);
        body.box("carapace", -4, -8, -3, 8, 8, 6).tag("thorax");
        body.box("belly", -3, -7, -3.5f, 6, 6, 1).tag("chest");
        body.box("plate", -4.5f, -9, 0, 9, 4, 4).tag("plate");
        body.box("plate", -4, -5.5f, 0.5f, 8, 4, 4).tag("plate");
        body.box("plate", -3.5f, -2, 0.5f, 7, 3, 3).tag("plate");
        body.box("carapace", -2.5f, -1, -2.5f, 5, 2, 5).tag("waist");
        // head: crest, compound eyes, long mandibles, antennae
        Bone head = body.child("head", 0, -8.5f, -1).rot(-12, 0, 0);
        head.box("carapace", -2.5f, -4, -4.5f, 5, 4, 5).tag("head");
        head.box("plate", -1.5f, -5.5f, -3, 3, 2, 6).tag("crest");
        head.box("eye", -3.5f, -3.5f, -4, 1, 2, 3).tag("eye");
        head.box("eye", 2.5f, -3.5f, -4, 1, 2, 3).tag("eye");
        head.pair("mandible", 1.5f, -0.5f, -4f, (b, side) -> {
            b.rot(35, 15, 0);
            b.sbox("mandible", -1, -0.5f, -7, 2, 1, 7).tag("mandible");
            b.child("mandible_tip", 0, 0, -6.5f).rot(0, -50, 0).sbox("mandible", -0.5f, -0.5f, -4, 1, 1, 4).tag("mandible_tip");
        });
        head.pair("antenna", 1, -4, -4, (b, side) -> {
            b.rot(-45, 25, 0);
            b.sbox("leg", -0.5f, -0.5f, -7, 1, 1, 7).tag("antenna");
            b.child("antenna_tip", 0, 0, -6.5f).rot(35, 0, 0).sbox("leg", -0.5f, -0.5f, -5, 1, 1, 5).tag("antenna");
        });
        // hanging segmented abdomen with a stinger
        Bone ab = body.child("abdomen", 0, -0.5f, 2).rot(-57, 0, 0);
        ab.box("carapace", -3, -2.5f, 0, 6, 5, 6).tag("segment");
        ab.box("plate", -3.5f, -3.5f, -0.5f, 7, 2, 7).tag("plate");
        Bone ab2 = ab.child("abdomen2", 0, 0, 5.5f).rot(-15, 0, 0);
        ab2.box("carapace", -2.5f, -2, 0, 5, 4, 5).tag("segment");
        ab2.box("plate", -3, -3, -0.5f, 6, 2, 6).tag("plate");
        Bone ab3 = ab2.child("abdomen3", 0, 0, 4.5f).rot(-20, 0, 0);
        ab3.box("carapace", -1.5f, -1.5f, 0, 3, 3, 4).tag("segment");
        ab3.box("spike", -0.5f, -0.5f, 3.5f, 1, 1, 3).tag("spike");
        // long jointed hind legs (knee forward, hock back)
        body.pair("leg", 3f, -1f, 0.5f, (b, side) -> {
            b.rot(-42, 0, 8);
            b.sbox("leg", -1.5f, -1, -1.5f, 3, 10, 3).tag("thigh");
            Bone shin = b.child("shin", 0, 9, 0).rot(75, 0, -4);
            shin.sbox("leg", -1, 0, -1, 2, 10, 2).tag("shin");
            shin.sbox("spike", -0.5f, 2, 0.5f, 1, 1, 2).tag("spur");
            Bone foot = shin.child("foot", 0, 9.5f, 0).rot(-45, 0, -4);
            foot.sbox("claw", -1.5f, -0.5f, -3.5f, 3, 1, 5).tag("foot");
        });
        // mantis-like raptorial forelegs, folded and ready
        body.pair("arm", 3.5f, -6.5f, -2f, (b, side) -> {
            b.rot(-130, 0, 8);
            b.sbox("blade", -1.5f, -1.5f, -1.5f, 3, 9, 3).tag("arm");
            Bone fore = b.child("forearm", 0, 7.5f, 0).rot(150, 0, 0);
            fore.sbox("blade", -1, -0.5f, -1, 2, 10, 3).tag("blade");
            fore.sbox("spike", -0.5f, 2, -2, 1, 1, 1).tag("spike");
            fore.sbox("spike", -0.5f, 5, -2, 1, 1, 1).tag("spike");
            fore.sbox("spike", -0.5f, 8, -2.5f, 1, 3, 2).tag("spike");
        });
        // two pairs of veined wings, folded flat down the back
        body.pair("wing", 1.5f, -7.5f, 4f, (b, side) -> {
            b.rot(90, 8, -78);
            b.sbox("wing", -16, 0, -2.5f, 16, 0, 5).tag("wing");
        });
        body.pair("hwing", 1.5f, -5.5f, 4f, (b, side) -> {
            b.rot(90, 4, -64);
            b.sbox("wing", -13, 0, -2.5f, 13, 0, 5).tag("hwing");
        });

        CreatureDef d = new CreatureDef(rig);
        d.gait(0.55f, 0.38f, 0.7f);
        d.look("head", 0.8f).look("body", 0.2f);
        d.anim("idle", hunterIdle()).anim("walk", hunterWalk()).anim("run", hunterWalk()).anim("fly", hunterFly())
                .anim("hover", hunterHover()).anim("attack", hunterStrike()).anim("attack2", hunterBite())
                .anim("special", hunterSpit()).anim("death", hunterDeath()).anim("hurt", hunterHurt());
        return d;
    }

    private static Anim hunterIdle() {
        Anim a = Anim.loop(2.4f);
        a.bob("body", 1, 0.3f, 0f, 1);
        a.swing("body", 0, 1.5f, 0.1f);
        a.swingN("mandible_right", 1, 7, 0f, 2).swingN("mandible_left", 1, -7, 0f, 2);
        swingPair(a, "antenna", 1, 12, 0f, 0.35f);
        swingPair(a, "arm", 0, 4, 0.2f, 0.7f);
        a.swing("abdomen", 0, 3, 0.4f);
        a.swing("abdomen2", 0, 3, 0.5f);
        // the wings shiver now and then
        rotPair(a, "wing", 0, 0, 0, 0, 1.6f, 0, 0, 0, 1.7f, 0, 0, 6, 1.8f, 0, 0, -3, 1.9f, 0, 0, 0, 2.4f, 0, 0, 0);
        return a;
    }

    private static Anim hunterWalk() {
        Anim a = Anim.loop(0.8f);
        a.swing("leg_right", 0, 24, 0f);
        a.swing("leg_left", 0, 24, 0.5f);
        a.swing("shin_right", 0, 14, 0.25f, 4);
        a.swing("shin_left", 0, 14, 0.75f, 4);
        a.swing("foot_right", 0, 12, 0.1f);
        a.swing("foot_left", 0, 12, 0.6f);
        a.bob("body", 1, 0.6f, 0.25f, 2);
        a.swing("body", 2, 4, 0f);
        a.swing("abdomen", 1, 6, 0.1f);
        a.swing("abdomen2", 1, 6, 0.2f);
        swingPair(a, "arm", 0, 5, 0f, 0.5f);
        swingPair(a, "antenna", 0, 8, 0f, 0.5f);
        return a;
    }

    /** Wings spread out of the folded pose (offsets cancel the rest rotation). */
    private static void wingsOpen(Anim a, float flap, float phase, int cycles) {
        a.wave("wing_right", Anim.Target.ROTATION, 2, flap, phase, 78, cycles);
        a.wave("wing_left", Anim.Target.ROTATION, 2, -flap, phase, -78, cycles);
        a.wave("hwing_right", Anim.Target.ROTATION, 2, flap * 0.9f, phase + 0.4f, 70, cycles);
        a.wave("hwing_left", Anim.Target.ROTATION, 2, -flap * 0.9f, phase + 0.4f, -70, cycles);
        a.hold("wing_right", -90, 10, 0);
        a.hold("wing_left", -90, -10, 0);
        a.hold("hwing_right", -90, 25, 0);
        a.hold("hwing_left", -90, -25, 0);
    }

    private static Anim hunterFly() {
        Anim a = Anim.loop(0.6f);
        wingsOpen(a, 32, 0f, 6);
        a.hold("body", 38, 0, 0);
        a.bob("body", 1, 1.2f, 0.25f, 1);
        holdPair(a, "leg", 40, 0, 4);
        holdPair(a, "shin", -30, 0, 0);
        holdPair(a, "arm", 20, 0, 0);
        a.hold("abdomen", 25, 0, 0);
        a.swing("abdomen2", 0, 6, 0f);
        holdPair(a, "antenna", 25, 0, 0);
        return a;
    }

    private static Anim hunterHover() {
        Anim a = Anim.loop(1.2f);
        wingsOpen(a, 30, 0f, 10);
        a.hold("body", 6, 0, 0);
        a.bob("body", 1, 1.2f, 0.25f, 1);
        holdPair(a, "leg", 18, 0, 6);
        holdPair(a, "shin", -25, 0, 0);
        a.swing("leg_right", 0, 5, 0f);
        a.swing("leg_left", 0, 5, 0.4f);
        a.swing("abdomen", 0, 6, 0.2f);
        a.swing("abdomen2", 0, 6, 0.3f);
        swingPair(a, "arm", 0, 6, 0f, 0.5f);
        swingPair(a, "antenna", 1, 10, 0f, 0.5f);
        return a;
    }

    /** Raptorial forelegs cock back and lash out, the blades unfolding into the target. */
    private static Anim hunterStrike() {
        Anim a = Anim.once(0.7f);
        a.rot("body", 0, 0, 0, 0, 0.25f, -8, 0, 0, 0.38f, 18, 0, 0, 0.7f, 0, 0, 0);
        rotPair(a, "arm", 0, 0, 0, 0, 0.25f, -25, 0, 10, 0.38f, 12, 0, -5, 0.5f, 8, 0, 0, 0.7f, 0, 0, 0);
        rotPair(a, "forearm", 0, 0, 0, 0, 0.25f, 15, 0, 0, 0.38f, -150, 0, 0, 0.5f, -135, 0, 0, 0.7f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.4f, 8, 0, 0, 0.7f, 0, 0, 0);
        rotPair(a, "wing", 0, 0, 0, 0, 0.25f, 0, 0, 10, 0.4f, 0, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** Lunging bite: head thrust forward, mandibles wide, then snapping shut. */
    private static Anim hunterBite() {
        Anim a = Anim.once(0.6f);
        a.rot("body", 0, 0, 0, 0, 0.22f, -6, 0, 0, 0.35f, 22, 0, 0, 0.6f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.35f, 0, -1, -3, 0.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.22f, -15, 0, 0, 0.35f, 15, 0, 0, 0.6f, 0, 0, 0);
        rotPair(a, "mandible", 0, 0, 0, 0, 0.22f, -10, 40, 0, 0.33f, 0, -18, 0, 0.42f, 0, -15, 0, 0.6f, 0, 0, 0);
        rotPair(a, "arm", 0, 0, 0, 0, 0.3f, 20, 0, 15, 0.6f, 0, 0, 0);
        return a;
    }

    /** Abdomen curls forward and pumps, the head rears back and jerks forward: spitting poison. */
    private static Anim hunterSpit() {
        Anim a = Anim.once(0.9f);
        a.rot("abdomen", 0, 0, 0, 0, 0.3f, 30, 0, 0, 0.45f, 10, 0, 0, 0.9f, 0, 0, 0);
        a.rot("abdomen2", 0, 0, 0, 0, 0.3f, 20, 0, 0, 0.45f, 5, 0, 0, 0.9f, 0, 0, 0);
        a.scale("abdomen", 0, 1, 1, 1, 0.3f, 1.12f, 1.12f, 0.95f, 0.45f, 0.94f, 0.94f, 1.03f, 0.9f, 1, 1, 1);
        a.rot("body", 0, 0, 0, 0, 0.3f, -10, 0, 0, 0.45f, 12, 0, 0, 0.9f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.32f, -22, 0, 0, 0.43f, 20, 0, 0, 0.9f, 0, 0, 0);
        rotPair(a, "mandible", 0, 0, 0, 0, 0.3f, 0, 35, 0, 0.65f, 0, 35, 0, 0.9f, 0, 0, 0);
        rotPair(a, "wing", 0, 0, 0, 0, 0.3f, 0, 0, 15, 0.6f, 0, 0, 15, 0.9f, 0, 0, 0);
        return a;
    }

    private static Anim hunterDeath() {
        Anim a = Anim.once(1f);
        a.pos("body", 0, 0, 0, 0, 0.6f, 0, -9, 0, 1f, 0, -10, 0);
        a.rot("body", 0, 0, 0, 0, 0.6f, 25, 0, 0, 1f, 30, 0, 0);
        rotPair(a, "leg", 0, 0, 0, 0, 0.6f, -50, 0, 25, 1f, -55, 0, 30);
        rotPair(a, "shin", 0, 0, 0, 0, 0.6f, 40, 0, 0, 1f, 45, 0, 0);
        rotPair(a, "arm", 0, 0, 0, 0, 0.6f, 60, 0, 20, 1f, 70, 0, 25);
        rotPair(a, "forearm", 0, 0, 0, 0, 0.6f, -40, 0, 0, 1f, -50, 0, 0);
        rotPair(a, "wing", 0, 0, 0, 0, 0.6f, 0, 0, 25, 1f, 0, 0, 30);
        rotPair(a, "hwing", 0, 0, 0, 0, 0.6f, 0, 0, 20, 1f, 0, 0, 25);
        a.rot("head", 0, 0, 0, 0, 0.6f, 35, 0, 0, 1f, 40, 0, 0);
        a.rot("abdomen", 0, 0, 0, 0, 0.6f, 35, 0, 0, 1f, 40, 0, 0);
        return a;
    }

    private static Anim hunterHurt() {
        Anim a = Anim.once(0.4f);
        a.rot("body", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.4f, 0, 0, 0);
        rotPair(a, "wing", 0, 0, 0, 0, 0.1f, 0, 0, 12, 0.4f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.4f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== riekling

    /**
     * Riekling: small, stooped Solstheim goblin with blue-grey skin, an oversized head, a jutting tusked underbite,
     * long pointed ears, a fur mantle with bone trinkets and a bone-tipped spear held ready in the right hand.
     */
    static CreatureDef riekling() {
        Biped b = new Biped("riekling");
        b.legLen = 6;
        b.legW = 3;
        b.hipW = 6;
        b.hipH = 3;
        b.torsoW = 7;
        b.torsoH = 6;
        b.torsoD = 4;
        b.hunch = 12;
        b.armLen = 8;
        b.armW = 3;
        b.handSize = 2;
        b.headW = 8;
        b.headH = 6;
        b.headD = 7;
        b.headForward = 1.5f;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "skin";
        b.hipMat = "hide";
        b.footMat = "hide";
        b.stride = 32;
        Rig rig = b.build();
        // face: heavy brow, flat wide nose, tusks rising from the underbite
        b.head.box("skin", -3.5f, -4.5f, -4.6f, 7, 1, 1).tag("brow");
        b.head.box("skin", -1.5f, -3.5f, -5f, 3, 2, 1).tag("nose");
        b.jawBone.box("tusk", -3, -2, -5.5f, 1, 2, 1).tag("tusk");
        b.jawBone.box("tusk", 2, -2, -5.5f, 1, 2, 1).tag("tusk");
        // hide headband with a bone charm, a tuft of coarse hair on the crown
        b.head.box("leather", -4.5f, -5.6f, -4.3f, 9, 1, 8).tag("headband");
        b.head.box("bone", -1, -6, -4.8f, 2, 2, 1).tag("charm");
        b.head.box("fur", -1.5f, -7, -2, 3, 1, 5).tag("tuft");
        // long pointed ears sweeping out and back
        b.head.pair("ear", 3.5f, -3.5f, 0.5f, (e, side) -> {
            e.rot(0, 30, 14);
            e.sbox("skin", -5, -1.5f, -0.5f, 5, 3, 1).tag("ear");
            e.sbox("skin", -8, -1, -0.5f, 3, 2, 1).tag("ear");
            e.sbox("skin", -10, -0.5f, -0.5f, 2, 1, 1).tag("ear_tip");
        });
        // fur mantle, bone necklace, belt and loincloth flaps, a bone pauldron on the left shoulder
        b.torso.box("fur", -4.5f, -6.5f, -2.5f, 9, 3, 5, 0.2f).tag("mantle");
        b.torso.box("bone", -2.5f, -4.8f, -2.6f, 5, 1, 1).tag("necklace");
        b.hips.box("leather", -3.5f, -3.5f, -2.5f, 7, 1, 5, 0.1f).tag("belt");
        b.hips.box("hide", -2.5f, -2.5f, -2.8f, 5, 5, 1).tag("flap");
        b.hips.box("hide", -2.5f, -2.5f, 1.8f, 5, 5, 1).tag("flap");
        rig.bone("arm_left").box("bone", -2f, -2.2f, -2f, 4, 2, 4).tag("pauldron");
        // the spear: ash-wood shaft, hide binding, a long bone point
        b.arms[0].rot(-15, 0, 6);
        b.forearms[0].rot(-45, 0, 0);
        Bone spear = b.hands[0].child("weapon", 0, 0.5f, 0).rot(33, 0, 0);
        spear.box("wood", -0.5f, -0.5f, -9, 1, 1, 17).tag("shaft");
        spear.box("leather", -1, -1, -10, 2, 2, 2).tag("binding");
        spear.box("bone", -0.5f, -1, -14, 1, 2, 4).tag("spearhead");
        spear.box("bone", -0.5f, -0.5f, -16, 1, 1, 2).tag("spear_tip");

        CreatureDef d = b.def();
        d.get("idle").swing("ear_right", 2, 5, 0f).swing("ear_left", 2, -5, 0.15f);
        d.get("walk").swingN("ear_right", 2, 6, 0f, 2).swingN("ear_left", 2, -6, 0f, 2);
        d.anim("attack", rieklingThrust());
        d.anim("cast", rieklingThrow());
        d.anim("special", rieklingWarCry());
        d.anim("roar", rieklingWarCry());
        return d;
    }

    /** Draws the spear back and drives it forward in a lunge. */
    private static Anim rieklingThrust() {
        Anim a = Anim.once(0.65f);
        a.rot("arm_right", 0, 0, 0, 0, 0.22f, 35, 0, 5, 0.36f, -55, 0, 0, 0.5f, -50, 0, 0, 0.65f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.22f, -30, 0, 0, 0.36f, 45, 0, 0, 0.5f, 40, 0, 0, 0.65f, 0, 0, 0);
        a.rot("weapon_right", 0, 0, 0, 0, 0.22f, -10, 0, 0, 0.36f, 15, 0, 0, 0.65f, 0, 0, 0);
        a.pos("arm_right", 0, 0, 0, 0, 0.22f, 0, 0, 1.5f, 0.36f, 0, 0, -1.5f, 0.65f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.22f, -5, 22, 0, 0.36f, 12, -25, 0, 0.5f, 10, -20, 0, 0.65f, 0, 0, 0);
        a.pos("hips", 0, 0, 0, 0, 0.22f, 0, 0, 1, 0.36f, 0, -0.5f, -2, 0.5f, 0, -0.5f, -2, 0.65f, 0, 0, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.36f, -35, 0, 0, 0.5f, -35, 0, 0, 0.65f, 0, 0, 0);
        a.rot("shin_right", 0, 0, 0, 0, 0.36f, 25, 0, 0, 0.5f, 25, 0, 0, 0.65f, 0, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.36f, 20, 0, 0, 0.5f, 20, 0, 0, 0.65f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.36f, 25, 0, -15, 0.65f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.3f, 0, 0, 0, 0.36f, 25, 0, 0, 0.55f, 20, 0, 0, 0.65f, 0, 0, 0).smooth();
        return a;
    }

    /** Raises the spear overhead, hurls it (the spear vanishes on release) and draws another. */
    private static Anim rieklingThrow() {
        Anim a = Anim.once(1.0f);
        a.rot("arm_right", 0, 0, 0, 0, 0.35f, -165, 0, 10, 0.48f, -60, 0, 0, 0.7f, -45, 0, 0, 1.0f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.35f, -35, 0, 0, 0.48f, 40, 0, 0, 0.7f, 30, 0, 0, 1.0f, 0, 0, 0);
        a.rot("weapon_right", 0, 0, 0, 0, 0.35f, -150, 0, 0, 0.48f, -40, 0, 0, 1.0f, 0, 0, 0);
        a.linear().scale("weapon_right", 0, 1, 1, 1, 0.46f, 1, 1, 1, 0.47f, 0.01f, 0.01f, 0.01f, 0.85f, 0.01f, 0.01f, 0.01f,
                1.0f, 1, 1, 1).smooth();
        a.rot("torso", 0, 0, 0, 0, 0.35f, -12, 25, 0, 0.48f, 15, -20, 0, 0.7f, 10, -10, 0, 1.0f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.35f, -60, 0, -20, 0.5f, 20, 0, -10, 1.0f, 0, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.35f, -25, 0, 0, 0.6f, -25, 0, 0, 1.0f, 0, 0, 0);
        a.rot("leg_right", 0, 0, 0, 0, 0.35f, 15, 0, 0, 0.6f, 15, 0, 0, 1.0f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.35f, -10, -15, 0, 0.5f, 5, 0, 0, 1.0f, 0, 0, 0);
        return a;
    }

    /** Spear brandished high, head thrown back, hopping in place: a riekling war cry. */
    private static Anim rieklingWarCry() {
        Anim a = Anim.once(1.4f);
        a.rot("torso", 0, 0, 0, 0, 0.3f, -18, 0, 0, 1.1f, -15, 0, 0, 1.4f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, -32, 0, 0, 0.7f, -28, 10, 0, 1.1f, -28, -10, 0, 1.4f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.3f, 40, 0, 0, 1.1f, 38, 0, 0, 1.4f, 0, 0, 0);
        a.rot("arm_right", 0, 0, 0, 0, 0.3f, -150, 0, 15, 0.55f, -165, 0, 15, 0.8f, -150, 0, 15, 1.1f, -165, 0, 15, 1.4f, 0, 0, 0);
        a.rot("weapon_right", 0, 0, 0, 0, 0.3f, 75, 0, 0, 1.1f, 75, 0, 0, 1.4f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.3f, -30, 0, -65, 1.1f, -35, 0, -70, 1.4f, 0, 0, 0);
        a.rot("forearm_left", 0, 0, 0, 0, 0.3f, -60, 0, 0, 1.1f, -60, 0, 0, 1.4f, 0, 0, 0);
        a.pos("hips", 0, 0, 0, 0, 0.35f, 0, 1.5f, 0, 0.5f, 0, 0, 0, 0.65f, 0, 1.5f, 0, 0.8f, 0, 0, 0, 0.95f, 0, 1.5f, 0,
                1.1f, 0, 0, 0, 1.4f, 0, 0, 0);
        a.rot("ear_right", 0, 0, 0, 0, 0.3f, 0, 15, 15, 1.1f, 0, 15, 15, 1.4f, 0, 0, 0);
        a.rot("ear_left", 0, 0, 0, 0, 0.3f, 0, -15, -15, 1.1f, 0, -15, -15, 1.4f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== ash spawn

    /**
     * Ash spawn: a gaunt, hunched figure of packed ash and cinders, cracked all over with glowing ember seams,
     * burning eyes, a jagged cinder crown and spine, clawed hands. Rises out of ash piles (emerge).
     */
    static CreatureDef ashSpawn() {
        Biped b = new Biped("ash_spawn");
        b.legLen = 13;
        b.legW = 3;
        b.hipW = 6;
        b.hipH = 3;
        b.torsoW = 8;
        b.torsoH = 11;
        b.torsoD = 5;
        b.hunch = 22;
        b.armLen = 16;
        b.armW = 3;
        b.handSize = 3;
        b.headW = 6;
        b.headH = 7;
        b.headD = 6;
        b.headForward = 2;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "ash";
        b.headMat = "ash";
        b.handMat = "cinder";
        b.footMat = "cinder";
        b.stride = 24;
        Rig rig = b.build();
        // lumpy cinder shoulders, shard spine, jagged crown, sunken brow
        b.torso.box("cinder", -5.5f, -11.8f, -2.5f, 4, 3, 5).tag("lump");
        b.torso.box("cinder", 2f, -11.3f, -2, 3, 2, 4).tag("lump");
        b.torso.box("cinder", -1, -10.5f, 2, 2, 3, 2).tag("shard");
        b.torso.box("cinder", -1.5f, -7, 2, 3, 2, 2).tag("shard");
        b.torso.box("cinder", -1, -4, 2, 2, 2, 2).tag("shard");
        b.torso.box("ash", -3, -6, -3, 6, 4, 1).tag("ribs");
        b.head.box("cinder", -3.5f, -5.5f, -4, 7, 1, 1).tag("brow");
        b.head.box("cinder", -2.5f, -8.5f, -1, 1, 2, 2).tag("crown");
        b.head.box("cinder", -0.5f, -9.5f, -0.5f, 1, 3, 2).tag("crown");
        b.head.box("cinder", 1.5f, -8.5f, 0, 1, 2, 2).tag("crown");
        both(rig, "hand", h -> {
            h.sbox("claw", -1.5f, 2.5f, -1.5f, 1, 2, 1).tag("claw");
            h.sbox("claw", -0.5f, 2.5f, -1.5f, 1, 3, 1).tag("claw");
            h.sbox("claw", 0.5f, 2.5f, -1.5f, 1, 2, 1).tag("claw");
        });
        // the ash ball, hidden inside the right hand until it is conjured (cast)
        b.hands[0].child("ashball", 0, 1, 0).box("ember", -1, -1, -1, 2, 2, 2).tag("ashball");

        CreatureDef d = b.def();
        d.anim("cast", ashThrow());
        d.anim("emerge", ashEmerge(b));
        d.get("idle").swingN("head", 2, 4, 0.1f, 3);
        return d;
    }

    /** Conjures a ball of burning ash in the raised right hand and hurls it. */
    private static Anim ashThrow() {
        Anim a = Anim.once(1.0f);
        a.rot("arm_right", 0, 0, 0, 0, 0.35f, -160, 0, 15, 0.48f, -70, 0, 0, 0.7f, -60, 0, 0, 1.0f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.35f, -30, 0, 0, 0.48f, 0, 0, 0, 1.0f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.35f, -45, 0, -25, 0.6f, -20, 0, -10, 1.0f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.35f, -10, 25, 0, 0.48f, 10, -15, 0, 1.0f, 0, 0, 0);
        a.linear().scale("ashball_right", 0, 1, 1, 1, 0.1f, 1, 1, 1, 0.35f, 2.6f, 2.6f, 2.6f, 0.47f, 2.6f, 2.6f, 2.6f, 0.48f, 1, 1, 1,
                1.0f, 1, 1, 1).smooth();
        a.pos("ashball_right", 0, 0, 0, 0, 0.35f, 0, -2.5f, 0, 0.47f, 0, -2.5f, 0, 0.48f, 0, 0, 0, 1.0f, 0, 0, 0);
        return a;
    }

    /** Starts as a crumpled heap low in the ash, then hauls itself upright and arches back. */
    private static Anim ashEmerge(Biped b) {
        Anim a = Anim.once(1.8f);
        float down = b.legLen * 0.72f;
        a.pos("hips", 0, 0, -down + 1.2f, 2, 0.45f, 0, -down + 1.2f, 2, 1.1f, 0, -2, 0.5f, 1.45f, 0, 0.5f, 0, 1.8f, 0, 0, 0);
        a.rot("leg_right", 0, -85, 0, 10, 0.45f, -85, 0, 10, 1.1f, -30, 0, 0, 1.45f, 0, 0, 0, 1.8f, 0, 0, 0);
        a.rot("leg_left", 0, -60, 0, -10, 0.45f, -60, 0, -10, 1.1f, -20, 0, 0, 1.45f, 0, 0, 0, 1.8f, 0, 0, 0);
        a.rot("shin_right", 0, 140, 0, 0, 0.45f, 140, 0, 0, 1.1f, 50, 0, 0, 1.45f, 0, 0, 0, 1.8f, 0, 0, 0);
        a.rot("shin_left", 0, 125, 0, 0, 0.45f, 125, 0, 0, 1.1f, 40, 0, 0, 1.45f, 0, 0, 0, 1.8f, 0, 0, 0);
        a.rot("foot_right", 0, -55, 0, 0, 0.45f, -55, 0, 0, 1.1f, -20, 0, 0, 1.45f, 0, 0, 0, 1.8f, 0, 0, 0);
        a.rot("foot_left", 0, -65, 0, 0, 0.45f, -65, 0, 0, 1.1f, -20, 0, 0, 1.45f, 0, 0, 0, 1.8f, 0, 0, 0);
        a.rot("torso", 0, 60, 0, 0, 0.45f, 55, 0, 0, 1.1f, 25, 0, 0, 1.45f, -18, 0, 0, 1.8f, 0, 0, 0);
        a.rot("head", 0, 40, 0, 0, 0.45f, 35, 0, 0, 1.1f, 10, 0, 0, 1.45f, -30, 0, 0, 1.8f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 1.2f, 0, 0, 0, 1.45f, 35, 0, 0, 1.8f, 0, 0, 0);
        a.rot("arm_right", 0, -100, 0, 20, 0.45f, -100, 0, 20, 1.1f, -40, 0, 30, 1.45f, -20, 0, 50, 1.8f, 0, 0, 0);
        a.rot("arm_left", 0, -95, 0, -25, 0.45f, -95, 0, -25, 1.1f, -40, 0, -30, 1.45f, -20, 0, -50, 1.8f, 0, 0, 0);
        a.rot("forearm_right", 0, -60, 0, 0, 0.45f, -60, 0, 0, 1.1f, -30, 0, 0, 1.45f, -40, 0, 0, 1.8f, 0, 0, 0);
        a.rot("forearm_left", 0, -70, 0, 0, 0.45f, -70, 0, 0, 1.1f, -30, 0, 0, 1.45f, -40, 0, 0, 1.8f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== gargoyle

    /**
     * Gargoyle: Harkon's living stone sentinel. Weathered grey stone; horned, muzzled demon head with faint red
     * eyes; heavy hunched shoulders, long arms ending in claws, digitigrade clawed legs, a spade-tipped tail and
     * folded stone wings. Sleeps as a perfectly still crouching statue and bursts out of the pose (emerge).
     */
    static CreatureDef gargoyle() {
        Biped b = new Biped("gargoyle");
        b.legLen = 14;
        b.legW = 4;
        b.hipW = 8;
        b.hipH = 4;
        b.torsoW = 11;
        b.torsoH = 12;
        b.torsoD = 7;
        b.hunch = 25;
        b.armLen = 18;
        b.armW = 4;
        b.handSize = 4;
        b.headW = 7;
        b.headH = 7;
        b.headD = 7;
        b.headForward = 2;
        b.jaw = true;
        b.jawH = 2;
        b.digitigrade = true;
        b.tailSegs = 4;
        b.tailLen = 16;
        b.tailW = 3;
        b.skin = "stone";
        b.stride = 24;
        Rig rig = b.build();
        // head: brow, muzzle, fangs, swept horns, pointed ears
        b.head.box("stone", -3.5f, -5.5f, -4.6f, 7, 1, 1).tag("brow");
        b.head.box("stone", -2, -3.5f, -6, 4, 2, 2).tag("muzzle");
        b.jawBone.box("tooth", -2, -1, -5, 1, 1, 1).tag("fang");
        b.jawBone.box("tooth", 1, -1, -5, 1, 1, 1).tag("fang");
        b.head.pair("horn", 2.5f, -6.5f, -1, (h, side) -> {
            h.rot(-35, 0, -22);
            h.sbox("horn", -1, -4, -1, 2, 4, 2).tag("horn");
            h.child("horn_tip", 0, -3.5f, 0).rot(-45, 0, 0).sbox("horn", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn_tip");
        });
        b.head.pair("ear", 3.5f, -5, 0.5f, (e, side) -> {
            e.rot(0, 30, -15);
            e.sbox("stone", -3, -1.5f, -0.5f, 3, 2, 1).tag("ear");
        });
        // chest plates, shoulder knobs, spinal ridge
        b.torso.box("stone", -4.5f, -12, -4, 9, 5, 1).tag("chest");
        b.torso.box("stone", -6.5f, -12.8f, -2, 3, 3, 4).tag("knob");
        b.torso.box("stone", 3.5f, -12.8f, -2, 3, 3, 4).tag("knob");
        b.torso.box("stone", -1, -11.5f, 3.5f, 2, 3, 2).tag("ridge");
        b.torso.box("stone", -1, -7.5f, 3.5f, 2, 3, 2).tag("ridge");
        // claws on hands and feet, spade on the tail
        both(rig, "hand", h -> {
            h.sbox("claw", -2, 3.5f, -2, 1, 3, 1).tag("claw");
            h.sbox("claw", -0.5f, 3.5f, -2, 1, 3, 1).tag("claw");
            h.sbox("claw", 1, 3.5f, -2, 1, 3, 1).tag("claw");
        });
        both(rig, "foot", f -> {
            f.sbox("claw", -2, 0.5f, -4.5f, 1, 1, 1).tag("toe");
            f.sbox("claw", -0.5f, 0.5f, -4.5f, 1, 1, 1).tag("toe");
            f.sbox("claw", 1, 0.5f, -4.5f, 1, 1, 1).tag("toe");
        });
        rig.bone("tail4").box("stone", -2, -0.5f, 3, 4, 1, 3).tag("spade");
        // folded stone wings
        b.torso.pair("wing", 3, -10.5f, 3, (w, side) -> {
            w.rot(GWING_X, GWING_Y, GWING_Z);
            w.sbox("stone", -10, -1, -1, 10, 2, 2).tag("wing_arm");
            w.sbox("membrane", -10, 0, 0, 10, 0, 10).tag("membrane");
            Bone tip = w.child("wing_tip", -9.5f, 0, 0).rot(0, GWING_TIP_Y, GWING_TIP_Z);
            tip.sbox("stone", -9, -0.5f, -0.5f, 9, 1, 1).tag("wing_arm");
            tip.sbox("membrane", -9, 0, 0, 9, 0, 9).tag("membrane_tip");
            tip.sbox("claw", -10, -1, -1, 1, 2, 2).tag("wing_claw");
        });

        CreatureDef d = b.def();
        d.get("idle").swing("wing_right", 2, 2, 0f).swing("wing_left", 2, -2, 0f);
        Anim run = d.get("run");
        rotPair(run, "wing", 0, 0, 0, 0, 0.35f, 0, -10, -15, 0.7f, 0, 0, 0);
        d.anim("special", gargoyleRoar());
        d.anim("roar", gargoyleRoar());
        d.anim("sleep", gargoyleStatue(Anim.loop(4f), 0, 4f));
        d.anim("emerge", gargoyleEmerge());
        Anim death = d.get("death");
        rotPair(death, "wing", 0, 0, 0, 0, 0.6f, 20, 0, -30, 1f, 20, 0, -35);
        return d;
    }

    static final float GWING_X = 15;
    static final float GWING_Y = 40;
    static final float GWING_Z = 55;
    static final float GWING_TIP_Y = 15;
    static final float GWING_TIP_Z = -140;

    /** Wings thrown wide: offsets that open the folded rest pose. */
    private static void gargoyleWingsOpen(Anim a, float t0, float t1, float t2, float t3) {
        rotPair(a, "wing", t0, 0, 0, 0, t1, -GWING_X, -GWING_Y + 5, -GWING_Z + 20, t2, -GWING_X, -GWING_Y + 5, -GWING_Z + 15,
                t3, 0, 0, 0);
        rotPair(a, "wing_tip", t0, 0, 0, 0, t1, 0, -GWING_TIP_Y, -GWING_TIP_Z - 10, t2, 0, -GWING_TIP_Y, -GWING_TIP_Z - 10,
                t3, 0, 0, 0);
    }

    private static Anim gargoyleRoar() {
        Anim a = Anim.once(1.6f);
        a.rot("torso", 0, 0, 0, 0, 0.4f, -20, 0, 0, 1.2f, -18, 0, 0, 1.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -30, 0, 0, 0.8f, -25, 8, 0, 1.2f, -25, -8, 0, 1.6f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.4f, 45, 0, 0, 1.2f, 42, 0, 0, 1.6f, 0, 0, 0);
        rotPair(a, "arm", 0, 0, 0, 0, 0.4f, -40, 0, 55, 1.2f, -45, 0, 60, 1.6f, 0, 0, 0);
        rotPair(a, "forearm", 0, 0, 0, 0, 0.4f, -50, 0, 0, 1.2f, -50, 0, 0, 1.6f, 0, 0, 0);
        gargoyleWingsOpen(a, 0, 0.4f, 1.2f, 1.6f);
        return a;
    }

    /** The crouched statue pose (held constant over [t0, t1]); also the start of {@link #gargoyleEmerge}. */
    private static Anim gargoyleStatue(Anim a, float t0, float t1) {
        float[][] pose = statuePose();
        for (float[] p : pose) {
            // p = {boneIndex, kind(0 rot / 1 pos), x, y, z}
            String bone = STATUE_BONES[(int) p[0]];
            if (p[1] == 0) a.rot(bone, t0, p[2], p[3], p[4], t1, p[2], p[3], p[4]);
            else a.pos(bone, t0, p[2], p[3], p[4], t1, p[2], p[3], p[4]);
        }
        return a;
    }

    static final String[] STATUE_BONES = {"hips", "leg_right", "leg_left", "shin_right", "shin_left", "foot_right",
            "foot_left", "torso", "head", "arm_right", "arm_left", "forearm_right", "forearm_left", "wing_right",
            "wing_left", "tail1", "tail2", "tail3", "tail4"};

    private static float[][] statuePose() {
        return new float[][]{
                {0, 1, 0, -6.5f, 1},
                {1, 0, -75, 0, 8}, {2, 0, -75, 0, -8},
                {3, 0, 100, 0, 0}, {4, 0, 100, 0, 0},
                {5, 0, -25, 0, 0}, {6, 0, -25, 0, 0},
                {7, 0, 20, 0, 0}, {8, 0, -12, 0, 0},
                {9, 0, -46, 0, -8}, {10, 0, -46, 0, 8},
                {11, 0, 0, 0, 0}, {12, 0, 0, 0, 0},
                {13, 0, 0, 0, 12}, {14, 0, 0, 0, -12},
                {15, 0, 30, 35, 0}, {16, 0, 10, 30, 0}, {17, 0, 0, 30, 0}, {18, 0, 0, 25, 0},
        };
    }

    /** Holds the statue pose, the stone shudders, then it bursts upright with wings flung open and a roar. */
    private static Anim gargoyleEmerge() {
        Anim a = Anim.once(2.0f);
        for (float[] p : statuePose()) {
            String bone = STATUE_BONES[(int) p[0]];
            if (p[1] == 0) {
                a.rot(bone, 0, p[2], p[3], p[4], 0.5f, p[2], p[3], p[4], 0.9f, p[2] * 0.15f, p[3] * 0.15f, p[4] * 0.15f,
                        2.0f, 0, 0, 0);
            } else {
                a.pos(bone, 0, p[2], p[3], p[4], 0.5f, p[2], p[3], p[4], 0.9f, 0, 1, 0, 1.1f, 0, 0, 0, 2.0f, 0, 0, 0);
            }
        }
        // the shudder as the stone cracks
        a.linear().pos("hips", 0, 0, 0, 0, 0.3f, 0, 0, 0, 0.35f, 0.4f, 0, 0, 0.4f, -0.4f, 0, 0, 0.45f, 0.3f, 0, 0, 0.5f, 0, 0, 0)
                .smooth();
        a.rot("torso", 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.9f, -25, 0, 0, 1.6f, -20, 0, 0, 2.0f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.9f, -30, 0, 0, 1.6f, -25, 0, 0, 2.0f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.7f, 0, 0, 0, 0.9f, 45, 0, 0, 1.6f, 40, 0, 0, 2.0f, 0, 0, 0);
        rotPair(a, "arm", 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.9f, -40, 0, 55, 1.6f, -40, 0, 55, 2.0f, 0, 0, 0);
        gargoyleWingsOpen(a, 0.5f, 0.9f, 1.6f, 2.0f);
        return a;
    }

    // ============================================================================================== spriggan

    /**
     * Spriggan: a slender female figure of living wood. Grained bark skin with moss, long twig fingers, root feet,
     * branching antlers with leaf clusters and a leafy mane, amber eyes and a glowing sap core in the chest.
     */
    static CreatureDef spriggan() {
        Biped b = new Biped("spriggan");
        b.legLen = 13;
        b.legW = 3;
        b.hipW = 5;
        b.hipH = 3;
        b.torsoW = 5;
        b.torsoH = 10;
        b.torsoD = 3;
        b.hunch = 4;
        b.armLen = 15;
        b.armW = 2;
        b.handSize = 0;
        b.headW = 5;
        b.headH = 6;
        b.headD = 5;
        b.headForward = 0.5f;
        b.skin = "bark";
        b.headMat = "face";
        b.stride = 24;
        Rig rig = b.build();
        // a wider bark bodice over the chest, flared hips, shoulder spurs
        b.torso.box("bark", -3, -10, -2, 6, 5, 4).tag("chest");
        b.torso.box("bark", -1.5f, -4, -1.8f, 3, 3, 1).tag("navel");
        b.hips.box("bark", -3, -3.5f, -2, 6, 3, 4).tag("hip");
        b.torso.box("wood", -3.5f, -11.5f, -0.5f, 1, 3, 1).tag("spur");
        b.torso.box("wood", 2.5f, -11.5f, -0.5f, 1, 3, 1).tag("spur");
        // the glowing sap core, cradled in the chest
        b.torso.child("core", 0, -7.5f, -2).box("sap", -1, -1.5f, -0.6f, 2, 3, 1).tag("core");
        // face: narrow chin, brow ridge
        b.head.box("face", -1.5f, -1, -3.3f, 3, 1, 1).tag("chin");
        b.head.box("bark", -2.5f, -5, -3.6f, 5, 1, 1).tag("brow");
        // branching antlers with leaf clusters, and a leafy mane down the back of the head
        b.head.pair("branch", 1.5f, -5.5f, 0, (br, side) -> {
            br.rot(-28, 0, -38);
            br.sbox("wood", -0.5f, -5, -0.5f, 1, 5, 1).tag("branch");
            Bone twig = br.child("twig", 0, -2.5f, 0).rot(10, 0, -45);
            twig.sbox("wood", -0.5f, -3, -0.5f, 1, 3, 1).tag("branch");
            twig.sbox("leaves", -1, -5, -1, 2, 2, 2).tag("leaf");
            Bone tip = br.child("branch_tip", 0, -4.5f, 0).rot(-30, 0, 30);
            tip.sbox("wood", -0.5f, -4, -0.5f, 1, 4, 1).tag("branch");
            tip.sbox("leaves", -1.5f, -6, -1.5f, 3, 3, 3).tag("leaf");
        });
        Bone mane = b.head.child("mane", 0, -5.5f, 1.5f).rot(28, 0, 0);
        mane.box("leaves", -3, -1, 0, 6, 3, 7).tag("mane");
        mane.box("leaves", -2, 1, 5, 4, 3, 4).tag("mane");
        // long twig fingers and root toes
        both(rig, "hand", h -> {
            h.sbox("wood", -1, -0.5f, -1, 1, 5, 1).tag("finger");
            h.sbox("wood", 0, -0.5f, -1, 1, 4, 1).tag("finger");
            h.sbox("wood", -0.5f, -0.5f, 0, 1, 4, 1).tag("finger");
        });
        both(rig, "foot", f -> {
            f.sbox("wood", -2, 0, -4.5f, 1, 1, 2).tag("root");
            f.sbox("wood", 1, 0, -4.5f, 1, 1, 2).tag("root");
        });

        CreatureDef d = b.def();
        for (String slot : new String[]{"idle", "walk"}) {
            Anim a = d.get(slot);
            swingPair(a, "branch", 2, 4, 0f, 0.3f);
            a.swing("mane", 0, 4, 0.2f);
            pulse(a, "core", 0.12f, 2, 0f);
        }
        d.anim("cast", sprigganHeal());
        Anim summon = d.get("summon");
        summon.scale("core", 0, 1, 1, 1, 0.4f, 1.4f, 1.4f, 1.4f, 1.0f, 1.4f, 1.4f, 1.4f, 1.4f, 1, 1, 1);
        rotPair(summon, "branch", 0, 0, 0, 0, 0.4f, -10, 0, -10, 1.0f, -10, 0, -10, 1.4f, 0, 0, 0);
        return d;
    }

    /** Arms open wide, chest out, head back; the sap core swells and pulses as she heals. */
    private static Anim sprigganHeal() {
        Anim a = Anim.once(1.5f);
        rotPair(a, "arm", 0, 0, 0, 0, 0.4f, -25, 0, 75, 1.1f, -30, 0, 80, 1.5f, 0, 0, 0);
        rotPair(a, "forearm", 0, 0, 0, 0, 0.4f, -25, 0, 0, 1.1f, -30, 0, 0, 1.5f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.4f, -14, 0, 0, 1.1f, -14, 0, 0, 1.5f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -30, 0, 0, 1.1f, -30, 0, 0, 1.5f, 0, 0, 0);
        a.scale("core", 0, 1, 1, 1, 0.4f, 1.5f, 1.5f, 1.5f, 0.6f, 1.25f, 1.25f, 1.25f, 0.8f, 1.6f, 1.6f, 1.6f, 1.0f, 1.3f,
                1.3f, 1.3f, 1.2f, 1.5f, 1.5f, 1.5f, 1.5f, 1, 1, 1);
        rotPair(a, "branch", 0, 0, 0, 0, 0.4f, -12, 0, -12, 1.1f, -12, 0, -12, 1.5f, 0, 0, 0);
        a.pos("hips", 0, 0, 0, 0, 0.4f, 0, 0.8f, 0, 1.1f, 0, 0.8f, 0, 1.5f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== hagraven

    /**
     * Hagraven: a hunched, gaunt crone becoming a raven. Grey-mauve wrinkled skin, a cape of black feathers over
     * the shoulders and back, black feathers through stringy hair, a hooked beak for a nose, long clawed fingers,
     * scaly bird legs with talons, ragged skirts. Casts fire from a conjured fireball in the right hand.
     */
    static CreatureDef hagraven() {
        Biped b = new Biped("hagraven");
        b.legLen = 12;
        b.legW = 3;
        b.hipW = 6;
        b.hipH = 3;
        b.torsoW = 7;
        b.torsoH = 11;
        b.torsoD = 5;
        b.hunch = 32;
        b.armLen = 15;
        b.armW = 2;
        b.handSize = 2;
        b.headW = 6;
        b.headH = 7;
        b.headD = 6;
        b.headForward = 2;
        b.digitigrade = true;
        b.skin = "skin";
        b.legMat = "scaly";
        b.footMat = "scaly";
        b.hipMat = "rags";
        b.stride = 26;
        Rig rig = b.build();
        // feather mantle over the shoulders and a long feather cape down the back
        b.torso.box("feathers", -4.5f, -11.6f, -3, 9, 3, 6, 0.2f).tag("mantle");
        b.torso.box("feathers", -6, -12, -1.5f, 3, 3, 4).tag("tuft");
        b.torso.box("feathers", 3, -12, -1.5f, 3, 3, 4).tag("tuft");
        // the cape: overlapping tiers of feathers, each a little wider, ending in ragged tips
        Bone cape = b.torso.child("cape", 0, -11, 2.2f).rot(0, 0, 0);
        cape.box("feathers", -4.5f, 0, -0.5f, 9, 5, 2).tag("cape");
        cape.box("feathers", -5, 4, 0, 10, 5, 2).tag("cape");
        cape.box("feathers", -5.5f, 8, 0.5f, 11, 4, 2).tag("cape");
        Bone capeLow = cape.child("cape_low", 0, 11.5f, 1).rot(-30, 0, 0);
        capeLow.box("feathers", -5.5f, 0, -0.5f, 11, 4, 2).tag("cape_low");
        capeLow.box("feathers", -6, 3, 0, 12, 5, 2).tag("cape_low");
        // feathered upper arms (the raven showing through)
        both(rig, "arm", a -> a.sbox("feathers", -1.5f, -1.5f, -1, 3, 5, 3).tag("sleeve"));
        // ragged skirts
        b.hips.box("rags", -3.5f, -2, -3, 7, 6, 1).tag("skirt");
        b.hips.box("rags", -3.5f, -2, 2, 7, 6, 1).tag("skirt");
        // head: stringy hair down the back, black plumes, hooked beak nose, sunken brow
        b.head.box("hair", -3.5f, -7.5f, -1, 7, 9, 4).tag("hair");
        b.head.box("hair", -3.5f, -7.6f, -3.5f, 7, 1, 3).tag("hair_top");
        b.head.box("skin", -3, -5, -3.9f, 6, 1, 1).tag("brow");
        b.head.box("beak", -1, -4.5f, -6, 2, 2, 3).tag("beak");
        b.head.box("beak", -1, -2.5f, -6.5f, 2, 1, 2).tag("beak_hook");
        b.head.box("beak", -0.5f, -1.5f, -6.5f, 1, 1, 1).tag("beak_hook");
        b.head.pair("plume", 2, -7, 1, (p, side) -> {
            p.rot(-40, 0, -30);
            p.sbox("plume", -0.5f, -6, -1, 1, 6, 2).tag("plume");
        });
        b.head.child("plume_mid", 0, -7.5f, 2).rot(-55, 0, 0).box("plume", -0.5f, -7, -1, 1, 7, 2).tag("plume");
        // long clawed fingers
        both(rig, "hand", h -> {
            h.sbox("claw", -1, 1.5f, -1, 1, 4, 1).tag("claw");
            h.sbox("claw", 0, 1.5f, -1, 1, 4, 1).tag("claw");
            h.sbox("claw", -0.5f, 1.5f, 0, 1, 3, 1).tag("claw");
        });
        // three forward talons and a hind spur
        both(rig, "foot", f -> {
            f.sbox("talon", -1.5f, 0.5f, -4.5f, 1, 1, 2).tag("talon");
            f.sbox("talon", -0.5f, 0.5f, -5, 1, 1, 2).tag("talon");
            f.sbox("talon", 0.5f, 0.5f, -4.5f, 1, 1, 2).tag("talon");
            f.sbox("talon", -0.5f, 0.5f, 1, 1, 1, 2).tag("spur");
        });
        // conjured fireball, hidden inside the right hand until cast
        b.hands[0].child("fireball", 0, 0.5f, 0).box("fire", -0.5f, -0.5f, -0.5f, 1, 1, 1).tag("fireball");

        CreatureDef d = b.def();
        Anim idle = d.get("idle");
        // jerky, bird-like head turns
        idle.linear().rot("head", 0, 0, 0, 0, 0.6f, 0, 0, 0, 0.7f, 0, 25, 8, 1.4f, 0, 25, 8, 1.5f, 0, -10, 0, 2.4f, 0, -10, 0,
                2.5f, 0, 0, 0, 3.5f, 0, 0, 0).smooth();
        idle.swing("cape", 0, 3, 0.2f).swing("cape_low", 0, 4, 0.3f);
        d.get("walk").swing("cape", 0, 6, 0.25f).swing("cape_low", 0, 8, 0.35f);
        d.get("run").hold("cape", -15, 0, 0);
        d.anim("cast", hagravenFireball());
        return d;
    }

    /** Conjures a fireball in the clawed right hand, cocks it back and flings it. */
    private static Anim hagravenFireball() {
        Anim a = Anim.once(1.1f);
        a.rot("arm_right", 0, 0, 0, 0, 0.3f, -70, 0, 20, 0.45f, -150, 0, 15, 0.58f, -70, 0, 0, 0.8f, -60, 0, 0, 1.1f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.3f, -60, 0, 0, 0.45f, -30, 0, 0, 0.58f, 0, 0, 0, 1.1f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.3f, -50, 0, -30, 0.58f, -20, 0, -20, 1.1f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.3f, -12, 20, 0, 0.45f, -15, 25, 0, 0.58f, 10, -20, 0, 1.1f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, -10, 0, 0, 0.58f, 10, 0, 0, 1.1f, 0, 0, 0);
        a.linear().scale("fireball_right", 0, 1, 1, 1, 0.1f, 1, 1, 1, 0.3f, 4.4f, 4.4f, 4.4f, 0.45f, 5.6f, 5.6f, 5.6f, 0.56f, 5.6f, 5.6f,
                5.6f, 0.57f, 1, 1, 1, 1.1f, 1, 1, 1).smooth();
        a.pos("fireball_right", 0, 0, 0, 0, 0.3f, 0, -2.5f, 0, 0.56f, 0, -2.5f, 0, 0.57f, 0, 0, 0, 1.1f, 0, 0, 0);
        a.rot("cape", 0, 0, 0, 0, 0.58f, -15, 0, 0, 1.1f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== ice wraith

    /** Rest curl of the ice wraith's body (x rotation per segment): an S-coil hanging in the air. */
    static final float[] WRAITH_CURL = {22, 18, -14, -28, -24, 8, 26, 20};

    /**
     * Ice wraith: a flying serpent of living ice. Horned, skull-like head with glowing eyes and fangs, a segmented
     * spiny body coiling in the air and tapering to a finned tail, small fins, and a glowing core inside the
     * translucent body.
     */
    static CreatureDef iceWraith() {
        Serpent s = new Serpent("ice_wraith");
        s.headW = 5;
        s.headH = 5;
        s.headL = 7;
        s.jawH = 2;
        s.segs = 8;
        s.segLen = 4;
        s.segW = 5;
        s.segH = 5;
        s.taper = 0.3f;
        s.height = 15;
        s.fins = true;
        s.finLen = 6;
        s.tailFin = false;
        s.spines = true;
        s.lateral = false;
        s.skin = "ice";
        s.belly = "ice_belly";
        s.headMat = "ice_bone";
        s.finMat = "ice_fin";
        s.build();
        for (int i = 0; i < s.seg.length; i++) s.seg[i].rot(WRAITH_CURL[i], 0, 0);
        // skull: brow ridge over deep sockets, a crest, swept-back horns, cheek spurs and fangs
        s.head.box("ice_bone", -3, -3, -6.5f, 6, 1, 3).tag("brow");
        s.head.box("ice_fin", -0.5f, -4.5f, -4, 1, 2, 5).tag("crest");
        s.head.pair("horn", 2, -2, -2.5f, (h, side) -> {
            h.rot(28, -28, 0);
            h.sbox("ice_bone", -1, -1, 0, 2, 2, 5).tag("horn");
            h.child("horn_tip", 0, 0, 4.5f).rot(22, -12, 0).sbox("ice_bone", -0.5f, -0.5f, 0, 1, 1, 5).tag("horn_tip");
        });
        s.head.pair("cheek", 2.5f, 0.5f, -3, (c, side) -> {
            c.rot(0, -35, 20);
            c.sbox("ice_fin", -1, -0.5f, 0, 1, 1, 4).tag("cheek");
        });
        s.jawBone.box("ice_bone", -1.5f, -1, -5, 1, 1, 1).tag("fang");
        s.jawBone.box("ice_bone", 0.5f, -1, -5, 1, 1, 1).tag("fang");
        // the glowing core running through the front of the body
        for (int i = 0; i < 4; i++) {
            int w = i < 2 ? 2 : 1;
            s.seg[i].box("core", -w / 2f, -w / 2f, 1, w, w, s.segLen - 2).tag("core");
        }
        // a small crossed ice-blade fin at the tail tip
        Bone tf = s.seg[s.seg.length - 1].child("tail_fin", 0, 0, s.segLen - 0.5f);
        tf.box("ice_fin", -3, 0, 0, 6, 0, 5).tag("tail_fin");
        tf.box("ice_fin", 0, -3, 0, 0, 6, 5).tag("tail_fin");
        // a second, smaller pair of fins halfway down
        s.seg[4].pair("fin2", 1.5f, 1, 1, (f, side) -> {
            f.rot(0, -30, 20);
            f.sbox("ice_fin", -4, 0, 0, 4, 0, 4).tag("fin");
        });
        CreatureDef d = s.def();
        for (String slot : new String[]{"idle", "walk", "fly", "hover", "run", "swim"}) {
            Anim a = d.get(slot);
            if (a == null) continue;
            a.swing("fin2_right", 2, 15, 0.3f).swing("fin2_left", 2, -15, 0.3f);
            pulse(a, "seg1", 0.04f, 2, 0f);
        }
        d.look("head", 0.7f);
        return d;
    }

    // ============================================================================================== wispmother

    static final float WISPMOTHER_HEIGHT = 19;
    static final int WISP_RIBBONS = 8;

    /**
     * Wispmother: a pale, ethereal woman drifting above the ground. Slender arms in bell sleeves, long hair flowing
     * down her back, glowing eyes, and a long gown that dissolves into trailing wisps instead of legs.
     */
    static CreatureDef wispmother() {
        Rig rig = new Rig("wispmother");
        Bone body = rig.root().child("body", 0, -WISPMOTHER_HEIGHT, 0);
        Bone torso = body.child("torso", 0, 0, 0);
        torso.box("skin", -2.5f, -7, -1.5f, 5, 7, 3).tag("torso");
        torso.box("gown", -3, -7.5f, -2, 6, 4, 4).tag("bodice");
        torso.box("gown", -3, -1.5f, -2, 6, 2, 4, 0.1f).tag("sash");
        torso.box("skin", -1, -8.5f, -1, 2, 2, 2).tag("neck");
        Bone head = torso.child("head", 0, -8.3f, -0.3f);
        head.box("skin", -2.5f, -5, -2.5f, 5, 5, 5).tag("head");
        head.box("hair", -3, -5.6f, -2.7f, 6, 2, 6).tag("hair_top");
        head.box("hair", -3, -4, -2, 1, 6, 4).tag("lock");
        head.box("hair", 2, -4, -2, 1, 6, 4).tag("lock");
        Bone hair = head.child("hair", 0, -4.5f, 2.5f).rot(8, 0, 0);
        hair.box("hair", -3, 0, -0.5f, 6, 11, 2).tag("hair");
        Bone hairTip = hair.child("hair_tip", 0, 10.5f, 0.5f).rot(6, 0, 0);
        hairTip.box("hair", -2.5f, 0, -0.5f, 5, 7, 1).tag("hair_tip");
        torso.pair("arm", 3.2f, -6.8f, 0, (a, side) -> {
            a.rot(-4, 0, 12);
            a.sbox("skin", -1, -1, -1, 2, 7, 2).tag("arm");
            Bone fore = a.child("forearm", 0, 6.5f, 0).rot(-18, 0, -4);
            fore.sbox("skin", -1, 0, -1, 2, 6, 2).tag("forearm");
            fore.sbox("gown", -1.5f, 1, -1.5f, 3, 5, 3).tag("sleeve");
            Bone hand = fore.child("hand", 0, 6, 0);
            hand.sbox("skin", -1, -0.5f, -1, 2, 2, 1).tag("hand");
            hand.sbox("claw", -1, 1.5f, -1, 1, 3, 1).tag("finger");
            hand.sbox("claw", 0, 1.5f, -1, 1, 2, 1).tag("finger");
        });
        // the gown: three flaring tiers, then wisps trailing away instead of legs
        Bone skirt = body.child("skirt", 0, 0, 0);
        skirt.box("gown", -3.5f, 0, -2.5f, 7, 4, 5).tag("skirt");
        Bone skirt2 = skirt.child("skirt2", 0, 3.5f, 0).rot(4, 0, 0);
        skirt2.box("gown", -4, 0, -3, 8, 4, 6).tag("skirt");
        Bone skirt3 = skirt2.child("skirt3", 0, 3.5f, 0.3f).rot(5, 0, 0);
        skirt3.box("gown", -4.5f, 0, -3.5f, 9, 3, 7).tag("skirt_hem");
        // translucent ribbons of mist around the hem: wispN tilts in the body's frame (flare / trailing), its
        // ribbon child turns the plane to face outward, and the tip curls
        float[][] wisps = {{-3.4f, -3.3f, 180, 18, 22}, {3.4f, -3.3f, 180, 18, -22}, {-4.4f, 0, -90, 0, 26},
                {4.4f, 0, 90, 0, -26}, {-2.5f, 3.4f, 0, 26, 12}, {2.5f, 3.4f, 0, 26, -12}, {0, 3.6f, 0, 32, 0},
                {0, -3.5f, 180, 14, 0}};
        for (int i = 0; i < WISP_RIBBONS; i++) {
            float[] w = wisps[i];
            float rx = w[1] < 0 ? -w[3] : w[3];
            Bone t = skirt3.child("wisp" + (i + 1), w[0], 2.5f, w[1]).rot(rx, 0, w[4]);
            Bone ribbon = t.child("wisp" + (i + 1) + "_ribbon", 0, 0, 0).rot(0, w[2], 0);
            ribbon.box("wisp", -2, -0.5f, 0, 4, 4, 0).tag("wisp");
            Bone tip = ribbon.child("wisp" + (i + 1) + "_tip", 0, 3.3f, 0).rot(16, 0, 0);
            tip.box("wisp", -1.5f, 0, 0, 3, 4, 0).tag("wisp_tip");
        }

        CreatureDef d = new CreatureDef(rig);
        d.gait(0.7f, 0.5f, 0.8f);
        d.look("head", 0.8f).look("torso", 0.2f);
        d.anim("idle", wispmotherIdle(Anim.loop(3.2f), 1f));
        d.anim("hover", wispmotherIdle(Anim.loop(3.2f), 1f));
        d.anim("walk", wispmotherDrift(1.6f));
        d.anim("run", wispmotherDrift(1.1f));
        d.anim("fly", wispmotherDrift(1.6f));
        d.anim("attack", wispmotherClaw());
        d.anim("cast", wispmotherBolt());
        d.anim("summon", wispmotherSummon());
        d.anim("death", wispmotherDeath());
        d.anim("hurt", wispmotherHurt());
        return d;
    }

    /** The floating loop: slow bob, gown and wisps rippling, arms drifting, hair stirring. */
    private static Anim wispmotherIdle(Anim a, float k) {
        a.bob("body", 1, 1.0f * k, 0f, 1);
        a.swing("skirt2", 0, 3 * k, 0.1f).swing("skirt3", 0, 4 * k, 0.2f);
        a.swing("skirt2", 2, 2 * k, 0.3f).swing("skirt3", 2, 3 * k, 0.4f);
        for (int i = 1; i <= WISP_RIBBONS; i++) {
            a.swing("wisp" + i, 0, 10 * k, i * 0.17f).swing("wisp" + i, 2, 8 * k, i * 0.23f + 0.1f);
            a.swing("wisp" + i + "_tip", 0, 14 * k, i * 0.17f + 0.15f);
        }
        a.swing("arm_right", 2, 5 * k, 0f).swing("arm_left", 2, -5 * k, 0.1f);
        a.swing("arm_right", 0, 6 * k, 0.25f).swing("arm_left", 0, 6 * k, 0.6f);
        a.swing("forearm_right", 0, 6 * k, 0.35f).swing("forearm_left", 0, 6 * k, 0.7f);
        a.swing("hair", 0, 4 * k, 0.2f).swing("hair_tip", 0, 6 * k, 0.35f).swing("hair", 2, 3 * k, 0.5f);
        a.swing("head", 2, 3 * k, 0.4f);
        a.swing("torso", 0, 2 * k, 0.1f);
        return a;
    }

    /** Drifting forward: leaning in, gown, wisps and hair streaming back. */
    private static Anim wispmotherDrift(float len) {
        Anim a = wispmotherIdle(Anim.loop(len), 0.7f);
        a.hold("body", 10, 0, 0);
        a.hold("skirt2", 10, 0, 0);
        a.hold("skirt3", 12, 0, 0);
        for (int i = 1; i <= WISP_RIBBONS; i++) a.hold("wisp" + i, 25, 0, 0);
        holdPair(a, "arm", 18, 0, 8);
        a.hold("hair", 25, 0, 0);
        a.hold("hair_tip", 15, 0, 0);
        a.hold("head", -8, 0, 0);
        return a;
    }

    /** Rises, draws the right hand back and rakes it down across the target. */
    private static Anim wispmotherClaw() {
        Anim a = Anim.once(0.7f);
        a.rot("arm_right", 0, 0, 0, 0, 0.25f, -150, 0, 25, 0.38f, -40, 0, -15, 0.5f, -25, 0, -10, 0.7f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.25f, -30, 0, 0, 0.38f, 0, 0, 0, 0.7f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.25f, -8, 25, 0, 0.38f, 12, -25, 0, 0.7f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.25f, 0, 1.5f, 1, 0.38f, 0, -0.5f, -2.5f, 0.7f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.3f, 15, 0, -25, 0.7f, 0, 0, 0);
        a.rot("hair", 0, 0, 0, 0, 0.38f, 20, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** Leans back gathering cold, then thrusts the right hand forward: a frost bolt. */
    private static Anim wispmotherBolt() {
        Anim a = Anim.once(1.0f);
        a.rot("arm_right", 0, 0, 0, 0, 0.3f, -40, 0, 40, 0.45f, -95, 0, 0, 0.7f, -90, 0, 0, 1.0f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.3f, -50, 0, 0, 0.45f, 5, 0, 0, 0.7f, 0, 0, 0, 1.0f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.3f, -40, 0, -40, 0.45f, -10, 0, -30, 1.0f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.3f, -10, 15, 0, 0.45f, 10, -10, 0, 1.0f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, -5, 0, 0, 0.45f, 8, 0, 0, 1.0f, 0, 0, 0);
        a.rot("hair", 0, 0, 0, 0, 0.45f, 22, 0, 0, 1.0f, 0, 0, 0);
        return a;
    }

    /** Arms raised, rising, the gown flaring: calling her wisps. */
    private static Anim wispmotherSummon() {
        Anim a = Anim.once(1.4f);
        rotPair(a, "arm", 0, 0, 0, 0, 0.4f, -165, 0, 25, 1.0f, -165, 0, 30, 1.4f, 0, 0, 0);
        rotPair(a, "forearm", 0, 0, 0, 0, 0.4f, 10, 0, 0, 1.4f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.4f, -28, 0, 0, 1.0f, -28, 0, 0, 1.4f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.4f, -10, 0, 0, 1.0f, -10, 0, 0, 1.4f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.4f, 0, 2.5f, 0, 1.0f, 0, 2.5f, 0, 1.4f, 0, 0, 0);
        a.scale("skirt3", 0, 1, 1, 1, 0.4f, 1.25f, 1, 1.25f, 1.0f, 1.25f, 1, 1.25f, 1.4f, 1, 1, 1);
        for (int i = 1; i <= WISP_RIBBONS; i++) {
            a.rot("wisp" + i, 0, 0, 0, 0, 0.4f, -15, 0, (i % 2 == 0 ? -1 : 1) * 15, 1.0f, -15, 0, (i % 2 == 0 ? -1 : 1) * 15,
                    1.4f, 0, 0, 0);
        }
        return a;
    }

    /** She sinks to the ground, folding over, the gown crumpling. */
    private static Anim wispmotherDeath() {
        Anim a = Anim.once(1.0f);
        a.pos("body", 0, 0, 0, 0, 0.7f, 0, -WISPMOTHER_HEIGHT + 9, 0, 1f, 0, -WISPMOTHER_HEIGHT + 9, 0);
        a.scale("skirt", 0, 1, 1, 1, 0.7f, 1.2f, 0.55f, 1.2f, 1f, 1.2f, 0.55f, 1.2f);
        a.rot("torso", 0, 0, 0, 0, 0.7f, 40, 0, 0, 1f, 45, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.7f, 30, 0, 10, 1f, 35, 0, 10);
        rotPair(a, "arm", 0, 0, 0, 0, 0.7f, -30, 0, 20, 1f, -30, 0, 25);
        for (int i = 1; i <= WISP_RIBBONS; i++) a.rot("wisp" + i, 0, 0, 0, 0, 0.7f, 40, 0, 0, 1f, 45, 0, 0);
        return a;
    }

    private static Anim wispmotherHurt() {
        Anim a = Anim.once(0.4f);
        a.rot("torso", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.4f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.4f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.1f, 0, 0, 1.5f, 0.4f, 0, 0, 0);
        return a;
    }

    // ============================================================================================== wisps

    /** A full turn about y over the loop (linear, so it wraps seamlessly). */
    private static Anim spin(Anim a, String bone, float turns) {
        return a.linear().rot(bone, 0, 0, 0, 0, a.length / 2, 0, 180 * turns, 0, a.length, 0, 360 * turns, 0).smooth();
    }

    /**
     * Wisp: a small glowing blue-white orb (bright core inside a translucent shell) trailing three wispy tendrils,
     * with motes of light circling it.
     */
    static CreatureDef wisp() {
        Rig rig = new Rig("wisp");
        Bone orb = rig.root().child("orb", 0, -5, 0);
        // bright core first (drawn before the translucent shells), then a rounded, layered halo: overlapping
        // translucent blocks get denser toward the middle
        orb.box("core", -1, -1, -1, 2, 2, 2).tag("core");
        orb.box("shell", -2, -2, -2, 4, 4, 4).tag("shell");
        orb.box("halo", -3, -2, -2, 6, 4, 4).tag("halo");
        orb.box("halo", -2, -3, -2, 4, 6, 4).tag("halo");
        orb.box("halo", -2, -2, -3, 4, 4, 6).tag("halo");
        float[][] tails = {{0, 1, 2, 30, 0}, {-1.2f, 0.5f, 1.8f, 22, -25}, {1.2f, 0.5f, 1.8f, 22, 25}};
        for (int i = 0; i < tails.length; i++) {
            float[] t = tails[i];
            Bone tail = orb.child("tendril" + (i + 1), t[0], t[1], t[2]).rot(t[3], t[4], 0);
            tail.box("wisp", -1, -1, 0, 2, 2, 3).tag("tendril");
            Bone tip = tail.child("tendril" + (i + 1) + "_tip", 0, 0, 2.5f).rot(12, 0, 0);
            tip.box("wisp", -0.5f, -0.5f, 0, 1, 1, 3).tag("tendril_tip");
        }
        Bone motes = orb.child("motes", 0, 0, 0);
        motes.box("mote", -4.5f, -1, -0.5f, 1, 1, 1).tag("mote");
        motes.box("mote", 3.5f, 1, 0, 1, 1, 1).tag("mote");
        motes.box("mote", 0, -3, 3.5f, 1, 1, 1).tag("mote");
        motes.box("mote", -0.5f, 2, -4.5f, 1, 1, 1).tag("mote");

        CreatureDef d = new CreatureDef(rig);
        d.gait(0.5f, 0.35f, 0.8f);
        d.anim("idle", wispFloat(Anim.loop(2f), 1)).anim("hover", wispFloat(Anim.loop(1.4f), 1));
        Anim fly = wispFloat(Anim.loop(0.8f), 2);
        for (int i = 1; i <= 3; i++) fly.hold("tendril" + i, -15, 0, 0);
        d.anim("fly", fly).anim("walk", fly).anim("run", fly);
        d.anim("attack", wispDart());
        d.anim("death", orbDeath(Anim.once(1f), "orb", 3));
        d.anim("hurt", orbHurt());
        return d;
    }

    private static Anim wispFloat(Anim a, int turns) {
        a.bob("orb", 1, 0.8f, 0f, 1);
        spin(a, "motes", turns);
        pulse(a, "orb", 0.06f, 2, 0f);
        for (int i = 1; i <= 3; i++) {
            a.swing("tendril" + i, 0, 12, i * 0.2f).swing("tendril" + i, 1, 15, i * 0.27f);
            a.swing("tendril" + i + "_tip", 0, 18, i * 0.2f + 0.15f);
        }
        return a;
    }

    /** Darts forward at the target in a flash and slips back. */
    private static Anim wispDart() {
        Anim a = Anim.once(0.6f);
        a.pos("orb", 0, 0, 0, 0, 0.15f, 0, 0.5f, 1.5f, 0.32f, 0, -0.5f, -7, 0.45f, 0, 0, -5, 0.6f, 0, 0, 0);
        a.scale("orb", 0, 1, 1, 1, 0.15f, 0.85f, 0.85f, 0.85f, 0.32f, 1.3f, 1.3f, 1.3f, 0.6f, 1, 1, 1);
        for (int i = 1; i <= 3; i++) a.rot("tendril" + i, 0, 0, 0, 0, 0.32f, -20, 0, 0, 0.6f, 0, 0, 0);
        return a;
    }

    /** The light flickers, swells once and gutters out, sinking. */
    private static Anim orbDeath(Anim a, String bone, float sink) {
        a.linear().scale(bone, 0, 1, 1, 1, 0.15f, 1.25f, 1.25f, 1.25f, 0.25f, 0.8f, 0.8f, 0.8f, 0.35f, 1.1f, 1.1f, 1.1f,
                0.5f, 0.6f, 0.6f, 0.6f, 0.6f, 0.75f, 0.75f, 0.75f, 0.85f, 0.25f, 0.25f, 0.25f, 1f, 0.05f, 0.05f, 0.05f).smooth();
        a.pos(bone, 0, 0, 0, 0, 1f, 0, -sink, 0);
        return a;
    }

    private static Anim orbHurt() {
        Anim a = Anim.once(0.35f);
        a.linear().pos("orb", 0, 0, 0, 0, 0.07f, 1, 0, 1, 0.14f, -1, 0.5f, 0, 0.21f, 0.5f, 0, 0.5f, 0.35f, 0, 0, 0).smooth();
        a.scale("orb", 0, 1, 1, 1, 0.1f, 0.8f, 0.8f, 0.8f, 0.35f, 1, 1, 1);
        return a;
    }

    /**
     * Will-o'-the-wisp (Cyrodiil): a larger pale green-blue orb of cold light around a dark, skull-like core with
     * glowing pinpoint eyes, wrapped in four tendrils that swirl around it and a wispy tail trailing below.
     */
    static CreatureDef willOWisp() {
        Rig rig = new Rig("will_o_wisp");
        Bone orb = rig.root().child("orb", 0, -12, 0);
        // the dark skull first, so the translucent light around it is drawn over it
        Bone skull = orb.child("skull", 0, 0, 0);
        skull.box("skull", -1.5f, -2, -1.5f, 3, 3, 3).tag("skull");
        skull.box("skull", -1, 1, -1.5f, 2, 1, 2).tag("skull_jaw");
        // layered, rounded orb of light: an inner glow and a cross of blocks that thins toward the edge
        Bone shell = orb.child("shell", 0, 0, 0);
        shell.box("glowshell", -3, -3, -3, 6, 6, 6).tag("inner");
        shell.box("shell", -4, -3, -3, 8, 6, 6).tag("shell");
        shell.box("shell", -3, -4, -3, 6, 8, 6).tag("shell");
        shell.box("shell", -3, -3, -4, 6, 6, 8).tag("shell");
        // tendrils curling around the orb like ribbons, spun by the swirl bone
        Bone swirl = orb.child("swirl", 0, 0, 0);
        float[] heights = {-2.5f, 0.5f, -0.5f, 2.5f};
        for (int i = 0; i < 4; i++) {
            double ang = Math.toRadians(i * 90 + 20);
            float x = (float) (4.5 * Math.cos(ang));
            float z = (float) (4.5 * Math.sin(ang));
            Bone t = swirl.child("tendril" + (i + 1), x, heights[i], z).rot(72, -(i * 90 + 20), 0);
            t.box("wisp", -1, 0, -1, 2, 4, 2).tag("tendril");
            Bone tip = t.child("tendril" + (i + 1) + "_tip", 0, 3.5f, 0).rot(10, 0, 35);
            tip.box("wisp", -0.5f, 0, -0.5f, 1, 4, 1).tag("tendril_tip");
        }
        Bone tail = orb.child("tail", 0, 3.5f, 0.5f).rot(10, 0, 0);
        tail.box("wisp", -1.5f, 0, -1.5f, 3, 4, 3).tag("tail");
        Bone tailTip = tail.child("tail_tip", 0, 3.5f, 0).rot(12, 0, 0);
        tailTip.box("wisp", -0.5f, 0, -0.5f, 1, 4, 1).tag("tail_tip");
        Bone motes = orb.child("motes", 0, 0, 0);
        motes.box("mote", -6.5f, -2, 0, 1, 1, 1).tag("mote");
        motes.box("mote", 5.5f, 1, 1, 1, 1, 1).tag("mote");
        motes.box("mote", 1, -5, 5.5f, 1, 1, 1).tag("mote");
        motes.box("mote", -1, 3, -6.5f, 1, 1, 1).tag("mote");
        motes.box("mote", 4, -4, -4, 1, 1, 1).tag("mote");

        CreatureDef d = new CreatureDef(rig);
        d.gait(0.6f, 0.4f, 0.8f);
        d.look("skull", 0.7f);
        d.anim("idle", willFloat(Anim.loop(3f), 1)).anim("hover", willFloat(Anim.loop(2f), 1));
        Anim fly = willFloat(Anim.loop(1.2f), 2);
        fly.hold("tail", 30, 0, 0).hold("tail_tip", 15, 0, 0).hold("orb", 10, 0, 0);
        d.anim("fly", fly).anim("walk", fly).anim("run", fly);
        d.anim("attack", willLunge());
        d.anim("cast", willCast());
        // the light gutters out and the skull drops out of it to the ground
        Anim death = Anim.once(1f);
        for (String part : new String[]{"shell", "swirl", "tail", "motes"}) orbDeath(death, part, 0);
        death.pos("skull", 0, 0, 0, 0, 0.45f, 0, 0.5f, 0, 1f, 0, -10.5f, 0);
        death.rot("skull", 0, 0, 0, 0, 0.45f, -10, 0, 0, 1f, 70, 0, 35);
        d.anim("death", death);
        d.anim("hurt", orbHurt());
        return d;
    }

    private static Anim willFloat(Anim a, int turns) {
        a.bob("orb", 1, 1.0f, 0f, 1);
        spin(a, "swirl", turns);
        a.linear().rot("motes", 0, 0, 0, 0, a.length / 2, 0, -180 * turns, 0, a.length, 0, -360 * turns, 0).smooth();
        pulse(a, "shell", 0.05f, 2, 0f);
        a.swing("skull", 1, 20, 0.1f).swing("skull", 0, 6, 0.3f);
        a.swing("tail", 0, 10, 0.2f).swing("tail", 2, 8, 0.45f).swing("tail_tip", 0, 15, 0.35f);
        for (int i = 1; i <= 4; i++) a.swing("tendril" + i + "_tip", 0, 12, i * 0.25f);
        return a;
    }

    /** Lunges forward and flares, the skull snapping at the target. */
    private static Anim willLunge() {
        Anim a = Anim.once(0.7f);
        a.pos("orb", 0, 0, 0, 0, 0.2f, 0, 1, 2, 0.38f, 0, -1, -6, 0.5f, 0, 0, -4, 0.7f, 0, 0, 0);
        a.scale("orb", 0, 1, 1, 1, 0.2f, 0.9f, 0.9f, 0.9f, 0.38f, 1.25f, 1.25f, 1.25f, 0.7f, 1, 1, 1);
        a.rot("skull", 0, 0, 0, 0, 0.2f, -20, 0, 0, 0.38f, 15, 0, 0, 0.7f, 0, 0, 0);
        a.rot("tail", 0, 0, 0, 0, 0.38f, 35, 0, 0, 0.7f, 0, 0, 0);
        return a;
    }

    /** Rises and swells with gathered light, the swirl racing, then releases it in a pulse. */
    private static Anim willCast() {
        Anim a = Anim.once(1.0f);
        a.pos("orb", 0, 0, 0, 0, 0.4f, 0, 2.5f, 0, 0.55f, 0, 1.5f, -1, 1.0f, 0, 0, 0);
        a.scale("orb", 0, 1, 1, 1, 0.4f, 1.3f, 1.3f, 1.3f, 0.5f, 1.4f, 1.4f, 1.4f, 0.56f, 0.85f, 0.85f, 0.85f, 1.0f, 1, 1, 1);
        a.linear().rot("swirl", 0, 0, 0, 0, 0.5f, 0, 540, 0, 1.0f, 0, 720, 0).smooth();
        a.scale("motes", 0, 1, 1, 1, 0.45f, 0.6f, 0.6f, 0.6f, 0.6f, 1.7f, 1.7f, 1.7f, 1.0f, 1, 1, 1);
        a.rot("skull", 0, 0, 0, 0, 0.4f, -15, 0, 0, 0.55f, 10, 0, 0, 1.0f, 0, 0, 0);
        return a;
    }
}
