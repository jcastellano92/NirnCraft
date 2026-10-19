package com.nirncraft.creatures.client.rig.defs;

import com.nirncraft.creatures.client.rig.Anim;
import com.nirncraft.creatures.client.rig.Arachnid;
import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.client.rig.Quad;
import com.nirncraft.creatures.client.rig.Rig;
import com.nirncraft.creatures.client.rig.Serpent;

import java.util.function.Consumer;

/** Skyrim's wildlife (remodelled): cats, bears, deer, elk, mammoths, horkers, mudcrabs, skeevers, fish, death hounds. */
final class SkyrimFauna {
    private SkyrimFauna() {}

    static void register() {
        Defs.reg("sabre_cat", SkyrimFauna::sabreCat);
        Defs.reg("bear", SkyrimFauna::bear);
        Defs.reg("deer", SkyrimFauna::deer);
        Defs.reg("elk", SkyrimFauna::elk);
        Defs.reg("mammoth", SkyrimFauna::mammoth);
        Defs.reg("horker", SkyrimFauna::horker);
        Defs.reg("mudcrab", SkyrimFauna::mudcrab);
        Defs.reg("skeever", SkyrimFauna::skeever);
        Defs.reg("slaughterfish", SkyrimFauna::slaughterfish);
        Defs.reg("death_hound", SkyrimFauna::deathHound);
    }

    // ============================================================================================ helpers

    /** Runs {@code f} on both {@code <base>_right} and {@code <base>_left} (use sbox inside to mirror). */
    private static void sides(Rig rig, String base, Consumer<Bone> f) {
        f.accept(rig.bone(base + "_right"));
        f.accept(rig.bone(base + "_left"));
    }

    /**
     * Quad paws and hooves reach one pixel below the leg box (to the ground plus one): lift the body so the feet stand
     * on y = 24.
     */
    private static void ground(Quad q) {
        q.body.y -= 1;
    }

    /** Moves a (side) bone's pivot; x is given for the right side and mirrored for the left. */
    private static void place(Bone b, float x, float y, float z) {
        b.x = b.side == 0 ? x : b.side * Math.abs(x);
        b.y = y;
        b.z = z;
    }

    /**
     * Position offset (animation space: y up) that keeps the body-local point (py, pz) in place when the body pitches
     * by {@code deg} about its pivot (rearing on the hind legs). Returns {y, z}.
     */
    private static float[] pivotFix(float py, float pz, float deg) {
        double r = Math.toRadians(deg);
        double c = Math.cos(r);
        double s = Math.sin(r);
        double ny = c * py - s * pz;
        double nz = s * py + c * pz;
        return new float[]{(float) -(py - ny), (float) (pz - nz)};
    }

    /** Hind hip pivot of a Quad, relative to the body pivot: {y, z}. */
    private static float[] hip(Quad q) {
        int front = q.frontLegLen > 0 ? q.frontLegLen : q.legLen;
        int back = q.backLegLen > 0 ? q.backLegLen : q.legLen;
        int maxLeg = Math.max(front, back);
        return new float[]{q.bodyH / 2f - 1.5f + (maxLeg - back), q.bodyL / 2f - q.legW / 2f - 1};
    }

    /** Both sides of a limb get the same key list (rotation y/z mirrored). */
    private static void rotBoth(Anim a, String base, float... keys) {
        a.rot(base + "_right", keys);
        float[] m = keys.clone();
        for (int i = 0; i < m.length; i += 4) {
            m[i + 2] = -m[i + 2];
            m[i + 3] = -m[i + 3];
        }
        a.rot(base + "_left", m);
    }

    /** Grazing idle for deer and elk: look around, lower the head to the grass and chew, flick ears and tail. */
    private static Anim grazeIdle(Quad q, float neckDown, float headDown) {
        Anim a = Anim.loop(8f);
        a.bob("body", 1, 0.2f, 0f, 2);
        a.rot("neck1", 0, 0, 0, 0, 1.6f, 0, 0, 0, 2.4f, neckDown, 0, 0, 5.2f, neckDown, 0, 0, 6.0f, 0, 0, 0, 8f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.5f, 0, 28, 0, 1.1f, 0, 28, 0, 1.5f, 0, -12, 0, 1.8f, 0, 0, 0, 2.4f, headDown, 0, 0,
                3.6f, headDown, 6, 0, 4.4f, headDown, -6, 0, 5.2f, headDown, 0, 0, 6.0f, 0, 0, 0, 6.8f, 0, -20, 0, 7.4f, 0, 0, 0, 8f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 2.6f, 0, 0, 0, 2.8f, 12, 0, 0, 3.0f, 0, 0, 0, 3.2f, 12, 0, 0, 3.4f, 0, 0, 0,
                3.8f, 0, 0, 0, 4.0f, 12, 0, 0, 4.2f, 0, 0, 0, 4.4f, 12, 0, 0, 4.6f, 0, 0, 0, 8f, 0, 0, 0).smooth();
        a.rot("ear_right", 0, 0, 0, 0, 3.0f, 0, 0, 0, 3.1f, 0, 0, -25, 3.25f, 0, 0, 0, 6.5f, 0, 0, 0, 6.6f, 0, 0, -25, 6.75f, 0, 0, 0, 8f, 0, 0, 0);
        a.rot("ear_left", 0, 0, 0, 0, 4.2f, 0, 0, 0, 4.3f, 0, 0, 25, 4.45f, 0, 0, 0, 8f, 0, 0, 0);
        a.rot("tail1", 0, 0, 0, 0, 1.0f, 0, 0, 0, 1.1f, -35, 0, 0, 1.25f, 0, 0, 0, 1.35f, -35, 0, 0, 1.5f, 0, 0, 0, 8f, 0, 0, 0);
        return a;
    }

    /** Crouch back and spring forward with the jaws open (skeevers, death hounds). */
    private static Anim lunge(float k) {
        Anim a = Anim.once(0.75f);
        a.pos("body", 0, 0, 0, 0, 0.2f, 0, -1f * k, 2f * k, 0.36f, 0, 1.5f * k, -5f * k, 0.5f, 0, 0.3f * k, -4f * k, 0.75f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.2f, 6, 0, 0, 0.36f, -10, 0, 0, 0.5f, 3, 0, 0, 0.75f, 0, 0, 0);
        rotBoth(a, "leg_front", 0, 0, 0, 0, 0.2f, 15, 0, 0, 0.36f, -60, 0, 0, 0.5f, -25, 0, 0, 0.75f, 0, 0, 0);
        rotBoth(a, "leg_front_lower", 0, 0, 0, 0, 0.2f, 25, 0, 0, 0.36f, 5, 0, 0, 0.5f, 20, 0, 0, 0.75f, 0, 0, 0);
        rotBoth(a, "leg_back", 0, 0, 0, 0, 0.2f, -25, 0, 0, 0.36f, 50, 0, 0, 0.5f, 20, 0, 0, 0.75f, 0, 0, 0);
        rotBoth(a, "leg_back_lower", 0, 0, 0, 0, 0.2f, -20, 0, 0, 0.36f, 10, 0, 0, 0.75f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.2f, -10, 0, 0, 0.36f, 15, 0, 0, 0.75f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.2f, -10, 0, 0, 0.36f, 0, 0, 0, 0.75f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.22f, 0, 0, 0, 0.3f, 42, 0, 0, 0.4f, 42, 0, 0, 0.46f, 0, 0, 0, 0.75f, 0, 0, 0).smooth();
        return a;
    }

    // ============================================================================================ sabre cat

    /** Big sabre-toothed cat: heavy shoulders, long downward fangs, cheek ruffs, short tail. */
    static CreatureDef sabreCat() {
        Quad q = new Quad("sabre_cat");
        q.bodyW = 10;
        q.bodyH = 9;
        q.bodyL = 20;
        q.chestW = 11;
        q.chestH = 11;
        q.chestL = 9;
        q.legLen = 10;
        q.legW = 4;
        q.neckSegs = 1;
        q.neckLen = 4;
        q.neckW = 7;
        q.neckH = 7;
        q.neckPitch = -14;
        q.headW = 8;
        q.headH = 7;
        q.headL = 6;
        q.snoutW = 5;
        q.snoutH = 3;
        q.snoutL = 3;
        q.earH = 2;
        q.earW = 2;
        q.tailSegs = 3;
        q.tailLen = 10;
        q.tailW = 3;
        q.tailDroop = 50;
        q.headMat = "face";
        q.snoutMat = "muzzle";
        q.legMat = "leg";
        q.tailMat = "tail";
        q.stride = 28;
        q.runStride = 46;
        q.build();
        ground(q);
        Rig rig = q.rig;
        // the sabre fangs hang from the upper jaw, in front of the lower jaw
        q.snout.box("fang", -2.5f, 2.5f, -3f, 1, 5, 1, 0.1f).tag("fang");
        q.snout.box("fang", 1.5f, 2.5f, -3f, 1, 5, 1, 0.1f).tag("fang");
        q.snout.box("nose", -1f, -0.9f, -3.4f, 2, 1, 1).tag("nose");
        // cheek ruffs and a heavy brow
        q.head.box("ruff", -4.5f, 0.5f, -3.5f, 9, 4, 3).tag("ruff");
        q.head.box("face", -3.5f, -4f, -6.3f, 7, 2, 2).tag("brow");
        // muscular shoulders and thighs
        sides(rig, "leg_front", b -> b.sbox("leg", -2.5f, -2f, -2.5f, 5, 6, 5).tag("shoulder"));
        sides(rig, "leg_back", b -> b.sbox("fur", -2.5f, -2.5f, -3f, 5, 7, 6).tag("thigh"));
        CreatureDef def = q.def();
        def.anim("attack3", pounce());
        def.anim("special", catRoar(q));
        return def;
    }

    /** Crouch, spring into the air stretched out, land with forepaws first. Hit around 0.45 s. */
    private static Anim pounce() {
        Anim a = Anim.once(1.2f);
        a.pos("body", 0, 0, 0, 0, 0.3f, 0, -2.5f, 1.5f, 0.42f, 0, 5, -4, 0.58f, 0, 5, -6, 0.78f, 0, -1.2f, -3, 1.2f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.3f, 4, 0, 0, 0.42f, -14, 0, 0, 0.58f, 4, 0, 0, 0.78f, 4, 0, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_front", 0, 0, 0, 0, 0.3f, -40, 0, 0, 0.42f, -80, 0, 0, 0.58f, -72, 0, 0, 0.78f, -12, 0, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_front_lower", 0, 0, 0, 0, 0.3f, 70, 0, 0, 0.42f, 10, 0, 0, 0.58f, 15, 0, 0, 0.78f, 15, 0, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_back", 0, 0, 0, 0, 0.3f, 35, 0, 0, 0.42f, 65, 0, 0, 0.58f, 50, 0, 0, 0.78f, -20, 0, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_back_lower", 0, 0, 0, 0, 0.3f, -60, 0, 0, 0.42f, 5, 0, 0, 0.58f, 0, 0, 0, 0.78f, -15, 0, 0, 1.2f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.3f, 12, 0, 0, 0.42f, -6, 0, 0, 0.58f, 0, 0, 0, 0.78f, 10, 0, 0, 1.2f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.42f, -6, 0, 0, 0.58f, 8, 0, 0, 1.2f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.34f, 0, 0, 0, 0.44f, 40, 0, 0, 0.58f, 40, 0, 0, 0.66f, 0, 0, 0, 1.2f, 0, 0, 0).smooth();
        a.rot("tail1", 0, 0, 0, 0, 0.3f, -10, 25, 0, 0.42f, -35, 0, 0, 0.78f, -20, 0, 0, 1.2f, 0, 0, 0);
        a.rot("tail2", 0, 0, 0, 0, 0.42f, 15, 0, 0, 1.2f, 0, 0, 0);
        return a;
    }

    /** Low snarling roar: shoulders down, head forward and up, jaws gaping wide around the fangs. */
    private static Anim catRoar(Quad q) {
        Anim a = Anim.once(1.6f);
        a.rot("body", 0, 0, 0, 0, 0.3f, 2, 0, 0, 1.2f, 2, 0, 0, 1.6f, 0, 0, 0);
        rotBoth(a, "leg_front", 0, 0, 0, 0, 0.3f, -22, 0, 0, 1.2f, -22, 0, 0, 1.6f, 0, 0, 0);
        rotBoth(a, "leg_front_lower", 0, 0, 0, 0, 0.3f, 36, 0, 0, 1.2f, 36, 0, 0, 1.6f, 0, 0, 0);
        rotBoth(a, "leg_back", 0, 0, 0, 0, 0.3f, -2, 0, 0, 1.2f, -2, 0, 0, 1.6f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.3f, -15, 0, 0, 1.2f, -15, 0, 0, 1.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, -18, 0, 0, 0.7f, -20, 8, 0, 1.0f, -20, -8, 0, 1.2f, -18, 0, 0, 1.6f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.3f, 50, 0, 0, 1.15f, 48, 0, 0, 1.6f, 0, 0, 0);
        rotBoth(a, "ear", 0, 0, 0, 0, 0.3f, 30, 0, 25, 1.2f, 30, 0, 25, 1.6f, 0, 0, 0);
        a.rot("tail1", 0, 0, 0, 0, 0.3f, 0, 20, 0, 0.6f, 0, -20, 0, 0.9f, 0, 20, 0, 1.2f, 0, -20, 0, 1.6f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ bear

    /** Heavy bear: shoulder hump, broad head with round ears and a short snout, big clawed paws. */
    static CreatureDef bear() {
        Quad q = new Quad("bear");
        q.bodyW = 12;
        q.bodyH = 11;
        q.bodyL = 20;
        q.chestW = 13;
        q.chestH = 14;
        q.chestL = 9;
        q.legLen = 9;
        q.legW = 5;
        q.neckSegs = 1;
        q.neckLen = 5;
        q.neckW = 8;
        q.neckH = 8;
        q.neckPitch = -14;
        q.headW = 8;
        q.headH = 7;
        q.headL = 6;
        q.snoutW = 5;
        q.snoutH = 3;
        q.snoutL = 4;
        q.earH = 3;
        q.earW = 3;
        q.tailSegs = 1;
        q.tailLen = 3;
        q.tailW = 3;
        q.tailDroop = 45;
        q.headMat = "face";
        q.snoutMat = "muzzle";
        q.legMat = "leg";
        q.stride = 22;
        q.runStride = 36;
        q.build();
        ground(q);
        Rig rig = q.rig;
        q.snout.box("nose", -1.5f, -0.9f, -4.4f, 3, 2, 1).tag("nose");
        // shaggy cheeks and a thick scruff behind the head
        q.head.box("fur", -4.5f, -1f, -3.5f, 9, 5, 4).tag("cheek");
        q.neck[0].box("fur", -4.5f, -4.5f, -3f, 9, 3, 4).tag("scruff");
        // rounded rump
        q.body.box("fur", -5.5f, -6.5f, 4f, 11, 3, 6).tag("rump");
        // big forepaws with long claws
        sides(rig, "leg_front_lower", b -> {
            b.sbox("leg", -3f, 3f, -3.5f, 6, 3, 6).tag("paw");
            b.sbox("claw", -2.5f, 5f, -5f, 5, 1, 2).tag("claw");
        });
        CreatureDef def = q.def();
        def.anim("special", bearRear(q));
        return def;
    }

    /** Rears onto the hind legs, roars with the forepaws raised, then slams back down. */
    private static Anim bearRear(Quad q) {
        Anim a = Anim.once(2.2f);
        float[] h = hip(q);
        float[] up = pivotFix(h[0], h[1], -62);
        float[] up2 = pivotFix(h[0], h[1], -58);
        a.rot("body", 0, 0, 0, 0, 0.6f, -62, 0, 0, 1.5f, -58, 0, 0, 1.75f, 2, 0, 0, 2.2f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.6f, 0, up[0], up[1], 1.5f, 0, up2[0], up2[1], 1.75f, 0, -0.3f, 0, 2.2f, 0, 0, 0);
        rotBoth(a, "leg_back", 0, 0, 0, 0, 0.6f, 62, 0, 0, 1.5f, 58, 0, 0, 1.75f, -4, 0, 0, 2.2f, 0, 0, 0);
        rotBoth(a, "leg_back_lower", 0, 0, 0, 0, 0.6f, -6, 0, 0, 1.5f, -6, 0, 0, 1.75f, 0, 0, 0);
        a.rot("leg_front_right", 0, 0, 0, 0, 0.6f, 35, 0, 18, 0.9f, 25, 0, 22, 1.2f, 38, 0, 15, 1.5f, 30, 0, 18, 1.75f, -30, 0, 0, 2.2f, 0, 0, 0);
        a.rot("leg_front_left", 0, 0, 0, 0, 0.6f, 30, 0, -18, 0.9f, 40, 0, -15, 1.2f, 25, 0, -22, 1.5f, 30, 0, -18, 1.75f, -30, 0, 0, 2.2f, 0, 0, 0);
        rotBoth(a, "leg_front_lower", 0, 0, 0, 0, 0.6f, -45, 0, 0, 1.5f, -45, 0, 0, 1.75f, 10, 0, 0, 2.2f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.6f, 38, 0, 0, 1.5f, 36, 0, 0, 1.75f, -5, 0, 0, 2.2f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.6f, 5, 0, 0, 0.9f, -5, 10, 0, 1.2f, -5, -10, 0, 1.5f, 5, 0, 0, 1.75f, 10, 0, 0, 2.2f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.6f, 10, 0, 0, 0.75f, 42, 0, 0, 1.4f, 40, 0, 0, 1.6f, 5, 0, 0, 2.2f, 0, 0, 0);
        rotBoth(a, "ear", 0, 0, 0, 0, 0.6f, 25, 0, 20, 1.5f, 25, 0, 20, 1.9f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ deer & elk

    /** Slender Skyrim deer: long legs, upright neck, big ears, small forked antlers, white rump and throat. */
    static CreatureDef deer() {
        Quad q = new Quad("deer");
        q.bodyW = 6;
        q.bodyH = 7;
        q.bodyL = 14;
        q.legLen = 11;
        q.legW = 3;
        q.foot = "hoof";
        q.footMat = "hoof";
        q.neckSegs = 2;
        q.neckLen = 4;
        q.neckW = 3;
        q.neckH = 4;
        q.neckPitch = -48;
        q.headW = 4;
        q.headH = 4;
        q.headL = 4;
        q.snoutW = 3;
        q.snoutH = 2;
        q.snoutL = 3;
        q.earH = 3;
        q.earW = 2;
        q.tailSegs = 1;
        q.tailLen = 3;
        q.tailW = 2;
        q.tailDroop = 20;
        q.headMat = "face";
        q.snoutMat = "muzzle";
        q.legMat = "leg";
        q.neckMat = "neck";
        q.tailMat = "tail";
        q.butt = true;
        q.gallop = true;
        q.stride = 26;
        q.runStride = 48;
        q.build();
        ground(q);
        Rig rig = q.rig;
        q.snout.box("nose", -1f, -1.4f, -3.4f, 2, 1, 1).tag("nose");
        // haunch and shoulder muscles
        q.body.box("fur", -3.5f, -3f, 3f, 7, 6, 4).tag("rump");
        // ears out to the sides, behind the antlers
        sides(rig, "ear", b -> {
            place(b, -2f, -1f, -0.8f);
            b.rot(-15, -20, -65);
        });
        // small forked antlers
        q.head.pair("antler", 1.2f, -2f, -2.2f, (b, side) -> {
            b.rot(-25, 0, -22);
            b.sbox("antler", -0.5f, -4f, -0.5f, 1, 4, 1).tag("antler");
            b.sbox("antler", -0.5f, -2.5f, -2.3f, 1, 1, 2).tag("tine");
            Bone tip = b.child("antler_tip", 0, -3.6f, 0).rot(-30, 0, 15);
            tip.sbox("antler", -0.5f, -3f, -0.5f, 1, 3, 1).tag("antler");
            tip.sbox("antler", -0.5f, -2f, -2f, 1, 1, 2).tag("tine");
        });
        CreatureDef def = q.def();
        def.anim("idle", grazeIdle(q, 62, 30));
        def.anims.remove("special");
        return def;
    }

    /** Big elk: shoulder hump, dark shaggy neck mane, long face, pale rump and huge branching antlers. */
    static CreatureDef elk() {
        Quad q = new Quad("elk");
        q.bodyW = 9;
        q.bodyH = 10;
        q.bodyL = 20;
        q.chestW = 10;
        q.chestH = 12;
        q.chestL = 8;
        q.legLen = 14;
        q.legW = 4;
        q.foot = "hoof";
        q.footMat = "hoof";
        q.neckSegs = 2;
        q.neckLen = 5;
        q.neckW = 5;
        q.neckH = 6;
        q.neckPitch = -40;
        q.headW = 5;
        q.headH = 5;
        q.headL = 5;
        q.snoutW = 4;
        q.snoutH = 3;
        q.snoutL = 5;
        q.earH = 3;
        q.earW = 2;
        q.tailSegs = 1;
        q.tailLen = 3;
        q.tailW = 2;
        q.tailDroop = 40;
        q.headMat = "face";
        q.snoutMat = "muzzle";
        q.legMat = "leg";
        q.neckMat = "mane";
        q.tailMat = "rump";
        q.butt = true;
        q.gallop = false;
        q.stride = 24;
        q.runStride = 40;
        q.scale = 1f;
        q.build();
        ground(q);
        Rig rig = q.rig;
        q.snout.box("nose", -1.5f, -1.9f, -5.4f, 3, 2, 1).tag("nose");
        // shaggy mane hanging under the neck and over the chest front
        for (Bone n : q.neck) n.box("mane", -3f, 1.5f, -5f, 6, 4, 6, 0.2f).tag("beard");
        q.chest.box("mane", -4.5f, -5f, -5f, 9, 9, 2, 0.3f).tag("bib");
        // pale rump patch
        q.body.box("rump", -4f, -4.5f, 8f, 8, 7, 3, 0.2f).tag("rump");
        sides(rig, "ear", b -> {
            place(b, -2.4f, -1.5f, -1f);
            b.rot(-10, -20, -60);
        });
        // huge branching antlers: three beam segments sweeping back and out, tines pointing forward
        q.head.pair("antler", 1.5f, -2.5f, -2f, (b, side) -> {
            b.rot(-15, 0, -45);
            b.sbox("antler", -0.5f, -6f, -0.5f, 1, 6, 1, 0.2f).tag("antler");
            b.sbox("antler", -0.5f, -2f, -4.5f, 1, 1, 4).tag("tine");
            b.sbox("antler", -0.5f, -4.5f, -4f, 1, 1, 3).tag("tine");
            Bone mid = b.child("antler_mid", 0, -5.6f, 0).rot(-30, 0, 18);
            mid.sbox("antler", -0.5f, -6f, -0.5f, 1, 6, 1, 0.15f).tag("antler");
            mid.sbox("antler", -0.5f, -3.5f, -5f, 1, 1, 5).tag("tine");
            Bone top = mid.child("antler_top", 0, -5.6f, 0).rot(-25, 0, 20);
            top.sbox("antler", -0.5f, -5f, -0.5f, 1, 5, 1, 0.1f).tag("antler");
            top.sbox("antler", -0.5f, -2.5f, -3.5f, 1, 1, 3).tag("tine");
            top.sbox("antler", -0.5f, -5.5f, 0.5f, 1, 1, 2).tag("tine");
        });
        CreatureDef def = q.def();
        def.anim("idle", grazeIdle(q, 58, 28));
        def.anims.remove("special");
        return def;
    }

    // ============================================================================================ mammoth

    /**
     * Woolly mammoth (renderer scale 2): high shoulder hump, domed head, long curved tusks, a four-segment trunk,
     * shaggy fur skirts and small ears.
     */
    static CreatureDef mammoth() {
        Quad q = new Quad("mammoth");
        q.bodyW = 16;
        q.bodyH = 13;
        q.bodyL = 24;
        q.chestW = 17;
        q.chestH = 17;
        q.chestL = 12;
        q.legLen = 11;
        q.legW = 6;
        q.foot = "pad";
        q.neckSegs = 1;
        q.neckLen = 3;
        q.neckW = 11;
        q.neckH = 11;
        q.neckPitch = 0;
        q.headW = 11;
        q.headH = 12;
        q.headL = 9;
        q.snoutW = 5;
        q.snoutH = 4;
        q.snoutL = 2;
        q.earH = 0;
        q.tailSegs = 2;
        q.tailLen = 7;
        q.tailW = 2;
        q.tailDroop = 70;
        q.fur = "wool";
        q.belly = null;
        q.headMat = "wool";
        q.legMat = "leg";
        q.snoutMat = "trunk";
        q.neckMat = "wool";
        q.tailMat = "wool";
        q.gallop = false;
        q.stride = 18;
        q.runStride = 28;
        q.scale = 2f;
        q.build();
        Rig rig = q.rig;
        Bone head = q.head;
        // domed crown
        head.box("wool", -4.5f, -9f, -7.5f, 9, 4, 7).tag("dome");
        // shaggy skirts: long hair hanging from the flanks, chest and throat
        q.body.box("shag", -8.5f, 2f, -11f, 1, 7, 22).tag("skirt");
        q.body.box("shag", 7.5f, 2f, -11f, 1, 7, 22).tag("skirt");
        q.body.box("shag", -7f, 5f, -2f, 14, 4, 13).tag("belly_skirt");
        q.chest.box("shag", -8f, 4f, -6.5f, 16, 6, 2).tag("bib");
        q.neck[0].box("shag", -5f, 3f, -3f, 10, 6, 3).tag("throat");
        // small round ears flat against the head
        head.pair("ear", 5.5f, -2f, -3f, (b, side) -> {
            b.rot(0, 20, 0);
            b.sbox("ear", -1f, -2f, -1f, 1, 4, 3).tag("ear");
        });
        // shaggy upper legs
        sides(rig, "leg_front", b -> b.sbox("shag", -3.5f, -1f, -3.5f, 7, 7, 7).tag("leg_shag"));
        sides(rig, "leg_back", b -> b.sbox("shag", -3.5f, -1f, -3.5f, 7, 6, 7).tag("leg_shag"));
        // toenails
        sides(rig, "leg_front_lower", b -> b.sbox("nail", -2.5f, 5f, -3f, 5, 1, 1).tag("nails"));
        sides(rig, "leg_back_lower", b -> b.sbox("nail", -2.5f, 5f, -3f, 5, 1, 1).tag("nails"));
        // tail tuft
        q.tail[1].box("shag", -1.5f, -1f, 3f, 3, 3, 3).tag("tuft");
        // the trunk: four tapering segments hanging from the snout, curling forward at the tip
        Bone t1 = q.snout.child("trunk1", 0, 1.5f, -1f).rot(4, 0, 0);
        t1.box("trunk", -2.5f, 0, -2f, 5, 6, 4).tag("trunk");
        Bone t2 = t1.child("trunk2", 0, 5.5f, 0).rot(2, 0, 0);
        t2.box("trunk", -2f, 0, -1.5f, 4, 5, 3).tag("trunk");
        Bone t3 = t2.child("trunk3", 0, 4.6f, 0).rot(-8, 0, 0);
        t3.box("trunk", -1.5f, 0, -1.5f, 3, 4, 3).tag("trunk");
        Bone t4 = t3.child("trunk4", 0, 3.6f, 0).rot(-28, 0, 0);
        t4.box("trunk", -1f, 0, -1.5f, 2, 4, 2).tag("trunk_tip");
        // the tusks: long ivory curves, down and forward, then up and inward
        head.pair("tusk", 3.5f, 4.5f, -7.5f, (b, side) -> {
            b.rot(55, 12, 0);
            b.sbox("tusk", -1f, -1f, -6f, 2, 2, 6, 0.2f).tag("tusk");
            Bone mid = b.child("tusk_mid", 0, 0, -5.6f).rot(-45, -6, 0);
            mid.sbox("tusk", -1f, -1f, -6f, 2, 2, 6, 0.1f).tag("tusk");
            Bone tip = mid.child("tusk_tip", 0, 0, -5.6f).rot(-50, -18, 0);
            tip.sbox("tusk", -0.5f, -0.5f, -5f, 1, 1, 5, 0.2f).tag("tusk_tip");
        });
        CreatureDef def = q.def();
        def.look("head", 0.5f).look("neck1", 0.3f);
        Anim idle = q.idle();
        trunkSway(idle, 1, 1f);
        def.anim("idle", idle);
        Anim walk = q.walk();
        trunkSway(walk, 1, 1.6f);
        def.anim("walk", walk);
        Anim run = q.run();
        trunkSway(run, 1, 2.2f);
        def.anim("run", run);
        def.anim("attack", tuskSwing());
        def.anim("attack2", mammothStomp(q));
        def.anim("special", trumpet());
        Anim death = q.death();
        death.rot("trunk1", 0, 0, 0, 0, 0.6f, 25, 0, 10, 1f, 25, 0, 10);
        death.rot("trunk2", 0, 0, 0, 0, 0.6f, -15, 0, 0, 1f, -15, 0, 0);
        def.anim("death", death);
        Anim hurt = q.hurt();
        hurt.rot("trunk1", 0, 0, 0, 0, 0.12f, -40, 0, 0, 0.5f, 0, 0, 0);
        hurt.rot("trunk2", 0, 0, 0, 0, 0.15f, -25, 0, 0, 0.5f, 0, 0, 0);
        def.anim("hurt", hurt);
        def.anims.remove("sleep");
        return def;
    }

    /** Pendulum sway of the trunk layered over a loop (more amplitude down the chain). */
    private static void trunkSway(Anim a, int cycles, float amp) {
        String[] t = {"trunk1", "trunk2", "trunk3", "trunk4"};
        for (int i = 0; i < t.length; i++) {
            a.wave(t[i], Anim.Target.ROTATION, 2, 4f * amp * (1 + i * 0.4f), -i * 0.1f, 0, cycles);
            a.wave(t[i], Anim.Target.ROTATION, 0, 2f * amp * (1 + i * 0.4f), 0.25f - i * 0.1f, -2f * amp, cycles);
        }
    }

    /** Head lowers to the right and sweeps the tusks hard across to the left. Hit around 0.42 s. */
    private static Anim tuskSwing() {
        Anim a = Anim.once(1.1f);
        a.rot("neck1", 0, 0, 0, 0, 0.3f, 12, 22, 0, 0.45f, -10, -28, 0, 0.65f, -6, -24, 0, 1.1f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.3f, 15, 18, 12, 0.45f, -18, -26, -14, 0.65f, -10, -20, -8, 1.1f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.3f, 2, 6, 2, 0.45f, -2, -6, -2, 1.1f, 0, 0, 0);
        a.rot("trunk1", 0, 0, 0, 0, 0.3f, 10, 0, -25, 0.45f, -15, 0, 30, 0.7f, 0, 0, 15, 1.1f, 0, 0, 0);
        a.rot("trunk2", 0, 0, 0, 0, 0.3f, 0, 0, -15, 0.45f, 0, 0, 25, 0.75f, 0, 0, 10, 1.1f, 0, 0, 0);
        a.rot("trunk3", 0, 0, 0, 0, 0.3f, 0, 0, -10, 0.5f, 0, 0, 25, 0.8f, 0, 0, 5, 1.1f, 0, 0, 0);
        a.rot("leg_front_left", 0, 0, 0, 0, 0.45f, -12, 0, 0, 1.1f, 0, 0, 0);
        return a;
    }

    /** Rears onto the hind legs and stamps the forefeet down. Stamp lands at 0.45 s. */
    private static Anim mammothStomp(Quad q) {
        Anim a = Anim.once(1.2f);
        float[] h = hip(q);
        float[] up = pivotFix(h[0], h[1], -24);
        a.rot("body", 0, 0, 0, 0, 0.28f, -24, 0, 0, 0.45f, 3, 0, 0, 0.6f, 2, 0, 0, 1.2f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.28f, 0, up[0], up[1], 0.45f, 0, -1.2f, 0, 0.6f, 0, -0.8f, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_back", 0, 0, 0, 0, 0.28f, 24, 0, 0, 0.45f, -3, 0, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_front", 0, 0, 0, 0, 0.28f, -10, 0, 0, 0.45f, -6, 0, 0, 0.6f, -4, 0, 0, 1.2f, 0, 0, 0);
        rotBoth(a, "leg_front_lower", 0, 0, 0, 0, 0.28f, 55, 0, 0, 0.42f, 0, 0, 0, 1.2f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.28f, 10, 0, 0, 0.45f, 8, 0, 0, 1.2f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.28f, -8, 0, 0, 0.45f, 12, 0, 0, 1.2f, 0, 0, 0);
        a.rot("trunk1", 0, 0, 0, 0, 0.28f, -45, 0, 0, 0.45f, 10, 0, 0, 0.7f, -5, 0, 0, 1.2f, 0, 0, 0);
        a.rot("trunk2", 0, 0, 0, 0, 0.28f, -30, 0, 0, 0.45f, 10, 0, 0, 1.2f, 0, 0, 0);
        a.rot("trunk3", 0, 0, 0, 0, 0.28f, -25, 0, 0, 0.5f, 10, 0, 0, 1.2f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.28f, 25, 0, 0, 0.6f, 10, 0, 0, 1.2f, 0, 0, 0);
        a.rot("tail1", 0, 0, 0, 0, 0.28f, -30, 0, 0, 0.6f, 0, 0, 0);
        return a;
    }

    /** Trumpet: head up, trunk raised in an S over the brow, ears flared, shaking call. */
    private static Anim trumpet() {
        Anim a = Anim.once(2.2f);
        a.rot("neck1", 0, 0, 0, 0, 0.5f, -14, 0, 0, 1.7f, -14, 0, 0, 2.2f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.5f, -22, 0, 0, 1.0f, -24, 5, 0, 1.4f, -24, -5, 0, 1.7f, -22, 0, 0, 2.2f, 0, 0, 0);
        a.rot("trunk1", 0, 0, 0, 0, 0.5f, -95, 0, 0, 1.7f, -95, 0, 0, 2.2f, 0, 0, 0);
        a.rot("trunk2", 0, 0, 0, 0, 0.5f, -45, 0, 0, 1.7f, -45, 0, 0, 2.2f, 0, 0, 0);
        a.rot("trunk3", 0, 0, 0, 0, 0.5f, -30, 0, 0, 1.7f, -30, 0, 0, 2.2f, 0, 0, 0);
        a.rot("trunk4", 0, 0, 0, 0, 0.5f, 10, 0, 0, 1.7f, 10, 0, 0, 2.2f, 0, 0, 0);
        a.linear();
        float[] k = new float[4 * 12];
        for (int i = 0; i < 12; i++) {
            float t = 0.6f + i * 0.1f;
            k[i * 4] = t;
            k[i * 4 + 2] = (i % 2 == 0 ? 6 : -6) * (i == 0 || i == 11 ? 0 : 1);
        }
        a.rot("trunk3", k);
        a.smooth();
        a.rot("jaw", 0, 0, 0, 0, 0.5f, 28, 0, 0, 1.7f, 28, 0, 0, 2.2f, 0, 0, 0);
        rotBoth(a, "ear", 0, 0, 0, 0, 0.5f, 0, -45, 0, 1.7f, 0, -45, 0, 2.2f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.5f, -4, 0, 0, 1.7f, -4, 0, 0, 2.2f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.5f, 0, 0.7f, 0, 1.7f, 0, 0.7f, 0, 2.2f, 0, 0, 0);
        a.rot("tail1", 0, 0, 0, 0, 0.5f, -35, 0, 0, 1.7f, -35, 0, 0, 2.2f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ horker

    /**
     * Horker: walrus-like sea mammal. Custom plan: a fat body with a raised chest carrying the head, a tapering hind
     * end with tail flippers, front flippers on the chest, a whiskered muzzle and long downward tusks.
     *
     * <p>Bones: body, chest, head, jaw, tusk_right/left, whisker_right/left, flipper_right/left, hind,
     * tail_flipper_right/left.</p>
     */
    static CreatureDef horker() {
        Rig rig = new Rig("horker");
        Bone body = rig.root().child("body", 0, -7, 2);
        body.box("hide", -9, -7, -8, 18, 14, 16).tag("torso");
        body.box("belly", -8, 5, -7, 16, 2, 14, 0.1f).tag("belly");
        Bone hind = body.child("hind", 0, 1, 7.5f);
        hind.box("hide", -7, -5, 0, 14, 11, 8).tag("hind");
        hind.box("hide", -5, -2, 7, 10, 8, 4).tag("hind_tip");
        hind.pair("tail_flipper", 2.5f, 4.5f, 10, (b, side) -> {
            b.rot(-8, -22, 0);
            b.sbox("flipper", -2.5f, 0, -1, 4, 1, 5).tag("tail_flipper");
            b.sbox("flipper", -3.5f, 0.2f, 3, 5, 1, 3).tag("tail_flipper_web");
        });
        Bone chest = body.child("chest", 0, -1, -7).rot(-10, 0, 0);
        chest.box("hide", -8, -9, -9, 16, 15, 10).tag("chest");
        chest.box("hide", -6.5f, -11, -8f, 13, 3, 7).tag("neck_fold");
        chest.box("belly", -7, 4, -8.5f, 14, 2, 9, 0.1f).tag("belly");
        chest.pair("flipper", 7, 4f, -4.5f, (b, side) -> {
            b.rot(8, 28, -24);
            b.sbox("flipper", -5, -1, -2, 6, 2, 4).tag("flipper");
            b.sbox("flipper", -9, -0.5f, -2.5f, 5, 1, 6).tag("flipper_hand");
        });
        Bone head = chest.child("head", 0, -7, -7.5f).rot(4, 0, 0);
        head.box("hide", -5.5f, -5, -7, 11, 9, 8).tag("head");
        head.box("muzzle", -5, -0.5f, -10, 10, 5, 3).tag("muzzle");
        Bone jaw = head.child("jaw", 0, 4, -5);
        jaw.box("muzzle", -3.5f, 0, -4.5f, 7, 2, 5).tag("jaw");
        head.pair("tusk", 3.6f, 3.5f, -8.8f, (b, side) -> {
            b.rot(12, 0, 4);
            b.sbox("tusk", -0.5f, 0, -0.5f, 1, 9, 1, 0.2f).tag("tusk");
        });
        head.pair("whisker", 5, 2.5f, -8.5f, (b, side) -> {
            b.rot(0, 20, 12);
            b.sbox("whisker", -4, 0, -1.5f, 4, 0, 3).tag("whisker");
        });
        CreatureDef def = new CreatureDef(rig);
        def.gait(0.9f, 0.6f, 0.7f);
        def.look("head", 0.8f).look("chest", 0.15f);
        def.anim("idle", horkerIdle()).anim("walk", horkerWalk()).anim("run", horkerWalk()).anim("swim", horkerSwim())
                .anim("attack", horkerLunge()).anim("special", horkerBark()).anim("sleep", horkerSleep())
                .anim("death", horkerDeath()).anim("hurt", horkerHurt());
        return def;
    }

    private static Anim horkerIdle() {
        Anim a = Anim.loop(5f);
        a.scale("body", 0, 1, 1, 1, 1.25f, 1.03f, 1.04f, 1.0f, 2.5f, 1, 1, 1, 3.75f, 1.03f, 1.04f, 1.0f, 5f, 1, 1, 1);
        a.rot("head", 0, 0, 0, 0, 1.0f, -6, 18, 0, 2.0f, -6, 18, 0, 2.6f, 4, 0, 0, 3.4f, -4, -16, 4, 4.3f, -4, -16, 4, 5f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 2.5f, 0, 0, 0, 2.7f, 10, 0, 0, 2.9f, 0, 0, 0, 5f, 0, 0, 0);
        rotBoth(a, "whisker", 0, 0, 0, 0, 0.4f, 0, 0, 8, 0.6f, 0, 0, 0, 3.0f, 0, 0, 0, 3.2f, 0, 0, 8, 3.4f, 0, 0, 0, 5f, 0, 0, 0);
        a.swing("tail_flipper_right", 0, 6f, 0f);
        a.swing("tail_flipper_left", 0, 6f, 0.1f);
        a.rot("flipper_right", 0, 0, 0, 0, 3.6f, 0, 0, 0, 3.9f, 0, -15, 10, 4.2f, 0, 0, 0, 5f, 0, 0, 0);
        return a;
    }

    /** Galumphing hump: chest heaves up while the hind drags, flippers push. */
    private static Anim horkerWalk() {
        Anim a = Anim.loop(1.0f);
        a.rot("chest", 0, 0, 0, 0, 0.25f, -9, 0, 0, 0.5f, 0, 0, 0, 0.75f, 6, 0, 0, 1f, 0, 0, 0);
        a.rot("hind", 0, 0, 0, 0, 0.25f, 8, 0, 0, 0.5f, 0, 0, 0, 0.75f, -10, 0, 0, 1f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.25f, 0, 1.5f, 0, 0.5f, 0, 0, 0, 0.75f, 0, 0.6f, 0, 1f, 0, 0, 0);
        a.rot("body", 0, 0, 0, 0, 0.25f, -3, 0, 0, 0.75f, 3, 0, 0, 1f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.25f, 8, 0, 0, 0.75f, -6, 0, 0, 1f, 0, 0, 0);
        rotBoth(a, "flipper", 0, 0, -25, 0, 0.3f, 0, -30, 15, 0.6f, 0, 25, 0, 1f, 0, -25, 0);
        rotBoth(a, "tail_flipper", 0, 0, 0, 0, 0.5f, -25, 0, 0, 0.75f, 10, 0, 0, 1f, 0, 0, 0);
        a.swingN("tail_flipper_right", 1, 6f, 0f, 1);
        return a;
    }

    /** Swimming: streamlined, head forward, body undulating up and down, flippers sculling. */
    private static Anim horkerSwim() {
        Anim a = Anim.loop(1.8f);
        a.hold("head", 42, 0, 0);
        a.hold("chest", 14, 0, 0);
        a.wave("body", Anim.Target.ROTATION, 0, 6f, 0f, -4f, 1);
        a.wave("hind", Anim.Target.ROTATION, 0, 14f, -0.15f, 0f, 1);
        a.wave("tail_flipper_right", Anim.Target.ROTATION, 0, 30f, -0.3f, 0f, 1);
        a.wave("tail_flipper_left", Anim.Target.ROTATION, 0, 30f, -0.3f, 0f, 1);
        a.wave("flipper_right", Anim.Target.ROTATION, 1, 25f, 0f, 30f, 1);
        a.wave("flipper_left", Anim.Target.ROTATION, 1, -25f, 0f, -30f, 1);
        a.wave("flipper_right", Anim.Target.ROTATION, 2, 12f, 0.25f, 10f, 1);
        a.wave("flipper_left", Anim.Target.ROTATION, 2, -12f, 0.25f, -10f, 1);
        a.bob("body", 1, 1f, 0.1f, 1);
        return a;
    }

    /** Rears the head back and drives the tusks down and forward. Hit at 0.42 s. */
    private static Anim horkerLunge() {
        Anim a = Anim.once(0.9f);
        a.rot("head", 0, 0, 0, 0, 0.25f, -28, 0, 0, 0.42f, 42, 0, 0, 0.6f, 30, 0, 0, 0.9f, 0, 0, 0);
        a.rot("chest", 0, 0, 0, 0, 0.25f, -12, 0, 0, 0.42f, 18, 0, 0, 0.6f, 12, 0, 0, 0.9f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.25f, 0, 1, 1.5f, 0.42f, 0, 0, -3, 0.6f, 0, 0, -2, 0.9f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.25f, 20, 0, 0, 0.42f, 5, 0, 0, 0.9f, 0, 0, 0);
        rotBoth(a, "flipper", 0, 0, 0, 0, 0.25f, 0, -20, 20, 0.42f, 0, 10, -10, 0.9f, 0, 0, 0);
        return a;
    }

    /** Head thrown up, three hoarse barks. */
    private static Anim horkerBark() {
        Anim a = Anim.once(1.4f);
        a.rot("head", 0, 0, 0, 0, 0.25f, -38, 0, 0, 0.45f, -30, 0, 0, 0.6f, -38, 0, 0, 0.75f, -30, 0, 0, 0.9f, -38, 0, 0,
                1.1f, -32, 0, 0, 1.4f, 0, 0, 0);
        a.rot("chest", 0, 0, 0, 0, 0.25f, -10, 0, 0, 1.1f, -10, 0, 0, 1.4f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.25f, 0, 0, 0, 0.32f, 35, 0, 0, 0.45f, 4, 0, 0, 0.62f, 35, 0, 0, 0.75f, 4, 0, 0,
                0.92f, 35, 0, 0, 1.1f, 0, 0, 0, 1.4f, 0, 0, 0).smooth();
        rotBoth(a, "whisker", 0, 0, 0, 0, 0.3f, 0, 0, 15, 1.1f, 0, 0, 15, 1.4f, 0, 0, 0);
        rotBoth(a, "flipper", 0, 0, 0, 0, 0.3f, 0, 0, 12, 1.1f, 0, 0, 12, 1.4f, 0, 0, 0);
        return a;
    }

    private static Anim horkerSleep() {
        Anim a = Anim.loop(4.5f);
        a.hold("head", 38, 0, 6);
        a.hold("chest", 12, 0, 0);
        a.pos("body", 0, 0, -0.5f, 0, 4.5f, 0, -0.5f, 0);
        a.scale("body", 0, 1, 1, 1, 2.0f, 1.04f, 1.05f, 1, 4.5f, 1, 1, 1);
        rotBoth(a, "flipper", 0, 0, 30, 0, 4.5f, 0, 30, 0);
        rotBoth(a, "tail_flipper", 0, 10, 0, 0, 4.5f, 10, 0, 0);
        return a;
    }

    private static Anim horkerDeath() {
        Anim a = Anim.once(1f);
        a.rot("head", 0, 0, 0, 0, 0.5f, 45, 0, 18, 1f, 45, 0, 18);
        a.rot("chest", 0, 0, 0, 0, 0.5f, 14, 0, 0, 1f, 14, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.5f, 25, 0, 0, 1f, 25, 0, 0);
        rotBoth(a, "flipper", 0, 0, 0, 0, 0.5f, 0, 20, 25, 1f, 0, 20, 25);
        rotBoth(a, "tail_flipper", 0, 0, 0, 0, 0.6f, 15, 0, 0, 1f, 15, 0, 0);
        return a;
    }

    private static Anim horkerHurt() {
        Anim a = Anim.once(0.45f);
        a.rot("head", 0, 0, 0, 0, 0.1f, -22, 0, 0, 0.45f, 0, 0, 0);
        a.rot("chest", 0, 0, 0, 0, 0.1f, -6, 0, 4, 0.45f, 0, 0, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.1f, 25, 0, 0, 0.45f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ mudcrab

    /** Mudcrab: flat wide spiky-rimmed carapace, eye stalks, six legs, two pincers (the right one bigger). */
    static CreatureDef mudcrab() {
        Arachnid a = new Arachnid("mudcrab");
        a.bodyW = 14;
        a.bodyH = 4;
        a.bodyL = 10;
        a.headW = 8;
        a.headH = 2;
        a.headL = 0;
        a.fangLen = 0;
        a.abdomenW = 8;
        a.abdomenH = 2;
        a.abdomenL = 0;
        a.abdomenTilt = 0;
        a.legPairs = 3;
        a.legUpper = 5;
        a.legLower = 6;
        a.legW = 2;
        a.legUp = 32;
        a.legDown = 62;
        a.pincers = true;
        a.pincerLen = 4;
        a.shell = "shell";
        a.legMat = "leg";
        a.abdomenMat = "under";
        a.build();
        Rig rig = a.rig;
        Bone body = a.body;
        // broad rock-like domed carapace with sloped front/rear aprons and lateral overhangs
        body.box("shell", -8, -1.5f, -4.5f, 1, 3, 9).tag("rim_r");
        body.box("shell", 7, -1.5f, -4.5f, 1, 3, 9).tag("rim_l");
        body.box("shell", -6, -1.5f, -5.8f, 12, 3, 1).tag("rim_front");
        body.box("shell", -5.5f, -1.2f, 4.8f, 11, 3, 1).tag("rim_back");
        body.box("plate", -6, -3.2f, -4.5f, 12, 2, 9).tag("plate");
        body.box("plate", -4, -4.5f, -3f, 8, 2, 6).tag("plate_top");
        body.box("plate", -2, -5.1f, -1.5f, 4, 1, 3).tag("plate_peak");
        body.box("under", -6f, 1.5f, -4.5f, 12, 1, 9).tag("underside");
        for (int i = 0; i < 3; i++) {
            float z = -3.2f + i * 3.0f;
            body.box("spike", -8.8f, -1.0f, z - 0.5f, 1, 1, 2).tag("spike");
            body.box("spike", 7.8f, -1.0f, z - 0.5f, 1, 1, 2).tag("spike");
        }
        body.box("spike", -4.5f, -2.0f, -6.5f, 2, 1, 1).tag("spike");
        body.box("spike", 2.5f, -2.0f, -6.5f, 2, 1, 1).tag("spike");
        body.box("spike", -1.5f, -2.6f, -6.2f, 1, 1, 1).tag("spike");
        body.box("spike", 0.5f, -2.6f, -6.2f, 1, 1, 1).tag("spike");
        // low-set crab eye stalks and tucked mandibles under the front rim
        a.head.pair("eyestalk", 2.2f, -1.0f, -0.6f, (b, side) -> {
            b.rot(-18, 0, -16);
            b.sbox("leg", -0.5f, -2.2f, -0.5f, 1, 2, 1).tag("stalk");
            b.sbox("eye", -0.5f, -3.0f, -0.5f, 1, 1, 1, 0.15f).tag("eye");
        });
        a.head.box("under", -2.5f, 0.4f, -1.2f, 5, 1, 1).tag("mouth");
        // heavy asymmetrical crab chelipeds (right crusher claw, left cutter claw)
        rig.bone("claw_lower_right").box("shell", -4.5f, -2.5f, -3f, 5, 5, 6, 0.1f).tag("crusher");
        rig.bone("pincer_right").box("shell", -3.5f, -1.5f, -2.5f, 4, 3, 3).tag("crusher_finger");
        rig.bone("claw_lower_left").box("shell", -0.5f, -2.0f, -2.8f, 4, 4, 5, 0.05f).tag("cutter");
        CreatureDef def = a.def();
        def.anims.remove("special");
        def.anim("attack", crabPinch(true));
        def.anim("attack2", crabPinch(false));
        Anim idle = def.get("idle");
        idle.rot("pincer_right", 0, 0, 0, 0, 0.6f, 0, 20, 0, 0.8f, 0, 0, 0, 2.5f, 0, 0, 0);
        idle.rot("pincer_left", 0, 0, 0, 0, 1.6f, 0, 0, 0, 1.8f, 0, -20, 0, 2.0f, 0, 0, 0, 2.5f, 0, 0, 0);
        rotBoth(idle, "eyestalk", 0, 0, 0, 0, 1.0f, 10, 10, 0, 1.6f, -5, -8, 0, 2.5f, 0, 0, 0);
        return def;
    }

    /** One claw raised and opened, then snapped shut forward. Hit at 0.35 s. */
    private static Anim crabPinch(boolean right) {
        Anim a = Anim.once(0.65f);
        String s = right ? "_right" : "_left";
        float m = right ? 1 : -1;
        a.rot("claw" + s, 0, 0, 0, 0, 0.2f, 0, 30 * m, -35 * m, 0.35f, 0, -25 * m, 5 * m, 0.65f, 0, 0, 0);
        a.rot("claw_lower" + s, 0, 0, 0, 0, 0.2f, 0, 10 * m, 0, 0.35f, 0, -10 * m, 0, 0.65f, 0, 0, 0);
        a.linear().rot("pincer" + s, 0, 0, 0, 0, 0.2f, 0, 45 * m, 0, 0.32f, 0, 45 * m, 0, 0.36f, 0, -5 * m, 0, 0.65f, 0, 0, 0).smooth();
        a.rot("body", 0, 0, 0, 0, 0.2f, -6, 8 * m, 5 * m, 0.35f, 4, -8 * m, -3 * m, 0.65f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.2f, 0, 0.6f, 0.5f, 0.35f, 0, 0, -1.5f, 0.65f, 0, 0, 0);
        return a;
    }

    // ============================================================================================ skeever

    /** Giant rat: hunched back, wedge head with buck teeth, pink ears, paws and a long segmented tail. */
    static CreatureDef skeever() {
        Quad q = new Quad("skeever");
        q.bodyW = 6;
        q.bodyH = 5;
        q.bodyL = 11;
        q.legLen = 4;
        q.frontLegLen = 3;
        q.backLegLen = 4;
        q.legW = 2;
        q.neckSegs = 1;
        q.neckLen = 2;
        q.neckW = 4;
        q.neckH = 4;
        q.neckPitch = -8;
        q.headW = 4;
        q.headH = 4;
        q.headL = 4;
        q.snoutW = 3;
        q.snoutH = 2;
        q.snoutL = 4;
        q.earH = 2;
        q.earW = 2;
        q.tailSegs = 6;
        q.tailLen = 18;
        q.tailW = 2;
        q.tailDroop = 14;
        q.headMat = "face";
        q.snoutMat = "face";
        q.legMat = "leg";
        q.footMat = "claw";
        q.tailMat = "tail";
        q.stride = 32;
        q.runStride = 50;
        q.build();
        ground(q);
        Rig rig = q.rig;
        // hunched back and haunches
        q.body.box("fur", -2.5f, -4.5f, -1.5f, 5, 2, 7).tag("hump");
        q.body.box("fur", -3.5f, -2f, 2f, 7, 4, 4).tag("haunch");
        // pink nose tip and yellow buck teeth
        q.snout.box("pink", -1f, -1.2f, -4.8f, 2, 1, 1).tag("nose");
        q.snout.box("tooth", -1f, 0.8f, -4.3f, 2, 2, 1).tag("teeth");
        // round ears set back on the head, whisker-scruff cheeks
        sides(rig, "ear", b -> {
            place(b, -1.6f, -1.6f, -1.2f);
            b.rot(-15, -15, -35);
        });
        q.head.box("face", -2.5f, -0.5f, -3f, 5, 2, 2).tag("cheek");
        // pink paws with claws already come from the Quad paws (legMat), tint them pink in the painter by tag "paw"
        CreatureDef def = q.def();
        def.anim("attack2", lunge(1f));
        def.anim("special", skeeverHiss(q));
        Anim idle = def.get("idle");
        for (int i = 0; i < q.tail.length; i++) idle.wave(q.tail[i].name, Anim.Target.ROTATION, 0, 5f, -i * 0.15f, 0, 2);
        idle.linear().rot("snout", 0, 0, 0, 0, 0.6f, 0, 0, 0, 0.7f, -6, 0, 0, 0.8f, 0, 0, 0, 0.9f, -6, 0, 0, 1.0f, 0, 0, 0, 4f, 0, 0, 0).smooth();
        Anim walk = def.get("walk");
        for (int i = 0; i < q.tail.length; i++) walk.wave(q.tail[i].name, Anim.Target.ROTATION, 1, 6f * (1 + i * 0.3f), -i * 0.15f, 0, 2);
        return def;
    }

    /** Rears up on the hind legs, forepaws raised, jaws open, hissing (trembling jaw), tail lashing. */
    private static Anim skeeverHiss(Quad q) {
        Anim a = Anim.once(1.5f);
        float[] h = hip(q);
        float[] up = pivotFix(h[0], h[1], -40);
        a.rot("body", 0, 0, 0, 0, 0.35f, -40, 0, 0, 1.15f, -38, 0, 0, 1.5f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.35f, 0, up[0], up[1], 1.15f, 0, up[0], up[1], 1.5f, 0, 0, 0);
        rotBoth(a, "leg_back", 0, 0, 0, 0, 0.35f, 40, 0, 0, 1.15f, 38, 0, 0, 1.5f, 0, 0, 0);
        rotBoth(a, "leg_front", 0, 0, 0, 0, 0.35f, -10, 0, 10, 0.7f, -30, 0, 15, 0.9f, -5, 0, 10, 1.15f, -20, 0, 10, 1.5f, 0, 0, 0);
        rotBoth(a, "leg_front_lower", 0, 0, 0, 0, 0.35f, -40, 0, 0, 1.15f, -40, 0, 0, 1.5f, 0, 0, 0);
        a.rot("neck1", 0, 0, 0, 0, 0.35f, 20, 0, 0, 1.15f, 20, 0, 0, 1.5f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.35f, 10, 0, 0, 1.15f, 10, 0, 0, 1.5f, 0, 0, 0);
        a.linear();
        float[] k = new float[4 * 10];
        for (int i = 0; i < 10; i++) {
            k[i * 4] = i == 0 ? 0 : i == 9 ? 1.5f : 0.3f + i * 0.1f;
            k[i * 4 + 1] = i == 0 || i == 9 ? 0 : (i % 2 == 0 ? 34 : 26);
        }
        a.rot("jaw", k);
        a.smooth();
        rotBoth(a, "ear", 0, 0, 0, 0, 0.35f, 30, 0, 20, 1.15f, 30, 0, 20, 1.5f, 0, 0, 0);
        for (int i = 0; i < q.tail.length; i++) {
            a.rot(q.tail[i].name, 0, 0, 0, 0, 0.35f, 10, 25 + i * 4, 0, 0.6f, 10, -25 - i * 4, 0, 0.85f, 10, 25 + i * 4, 0,
                    1.15f, 10, -20, 0, 1.5f, 0, 0, 0);
        }
        return a;
    }

    // ============================================================================================ slaughterfish

    /** Slaughterfish: big underbite jaw full of teeth, spiny dorsal fin, pectoral fins, deep body tapering to the tail. */
    static CreatureDef slaughterfish() {
        Serpent s = new Serpent("slaughterfish");
        s.headW = 5;
        s.headH = 6;
        s.headL = 6;
        s.jawH = 2;
        s.segs = 3;
        s.segLen = 5;
        s.segW = 5;
        s.segH = 6;
        s.taper = 0.45f;
        s.height = 4;
        s.fins = true;
        s.finLen = 4;
        s.tailFin = true;
        s.lateral = true;
        s.skin = "scales";
        s.belly = "belly";
        s.headMat = "head";
        s.finMat = "fin";
        s.build();
        Rig rig = s.rig;
        Bone head = s.head;
        Bone jaw = s.jawBone;
        // the underbite: lower jaw juts forward and up past the snout, lined with teeth
        jaw.box("head", -2.5f, -1f, -6.5f, 5, 3, 2).tag("chin");
        jaw.box("tooth", -2f, -2f, -6.3f, 4, 1, 1).tag("teeth_front");
        jaw.box("tooth", -2.3f, -1f, -4.5f, 0, 1, 4).tag("teeth_side");
        jaw.box("tooth", 2.3f, -1f, -4.5f, 0, 1, 4).tag("teeth_side");
        head.box("tooth", -2f, 0f, -5.6f, 4, 1, 0).tag("teeth_upper");
        // brow ridge
        head.box("head", -2.5f, -3.5f, -4f, 5, 1, 3).tag("brow");
        // forked tail fin instead of the serpent's tall paddle
        Bone tf = rig.bone("tail_fin");
        tf.boxes.clear();
        tf.box("fin", 0, -4.5f, 0, 0, 9, 5).tag("tail_fin");
        // spiny dorsal fin (flat ray fins along the back) and a ventral fin
        head.box("fin", 0, -6f, -1.5f, 0, 3, 3).tag("dorsal");
        rig.bone("seg1").box("fin", 0, -6f, 0, 0, 3, 5).tag("dorsal");
        rig.bone("seg2").box("fin", 0, -4.5f, 0, 0, 2, 4).tag("dorsal");
        rig.bone("seg2").box("fin", 0, 2f, 1, 0, 2, 3).tag("ventral");
        CreatureDef def = s.def();
        def.anims.remove("fly");
        def.anims.remove("hover");
        def.anims.remove("emerge");
        def.anims.remove("special");
        def.anims.remove("attack2");
        def.anim("attack", fishBite());
        def.anim("walk", fishFlop(s));
        def.anim("run", fishFlop(s));
        def.gait(0.6f, 0.4f, 0.6f);
        return def;
    }

    /** Darts forward with the underbite gaping and snaps. Hit at 0.35 s. */
    private static Anim fishBite() {
        Anim a = Anim.once(0.6f);
        a.pos("head", 0, 0, 0, 0, 0.18f, 0, 0.5f, 2, 0.35f, 0, -0.5f, -4, 0.6f, 0, 0, 0);
        a.rot("head", 0, 0, 0, 0, 0.18f, -12, 0, 0, 0.35f, 8, 0, 0, 0.6f, 0, 0, 0);
        a.linear().rot("jaw", 0, 0, 0, 0, 0.12f, 0, 0, 0, 0.24f, 48, 0, 0, 0.32f, 48, 0, 0, 0.37f, 0, 0, 0, 0.6f, 0, 0, 0).smooth();
        a.rot("seg1", 0, 0, 0, 0, 0.18f, 0, 18, 0, 0.35f, 0, -10, 0, 0.6f, 0, 0, 0);
        a.rot("seg2", 0, 0, 0, 0, 0.18f, 0, -24, 0, 0.35f, 0, 18, 0, 0.6f, 0, 0, 0);
        a.rot("seg3", 0, 0, 0, 0, 0.18f, 0, 30, 0, 0.35f, 0, -24, 0, 0.6f, 0, 0, 0);
        a.rot("tail_fin", 0, 0, 0, 0, 0.18f, 0, -30, 0, 0.35f, 0, 30, 0, 0.6f, 0, 0, 0);
        return a;
    }

    /** Stranded on land: lying on its side, flopping (the side-to-side body wave becomes vertical) and gasping. */
    private static Anim fishFlop(Serpent s) {
        Anim a = Anim.loop(0.8f);
        a.hold("head", 0, 0, 85);
        float low = -(s.height - s.headW / 2f - 0.5f);
        a.pos("head", 0, 0, low, 0, 0.15f, 0, low + 3.5f, 0, 0.3f, 0, low, 0, 0.55f, 0, low + 1.5f, 0, 0.8f, 0, low, 0);
        a.rot("seg1", 0, 0, 20, 0, 0.15f, 0, -25, 0, 0.3f, 0, 20, 0, 0.55f, 0, -10, 0, 0.8f, 0, 20, 0);
        a.rot("seg2", 0, 0, 25, 0, 0.15f, 0, -35, 0, 0.3f, 0, 25, 0, 0.55f, 0, -15, 0, 0.8f, 0, 25, 0);
        a.rot("seg3", 0, 0, 30, 0, 0.15f, 0, -40, 0, 0.3f, 0, 30, 0, 0.55f, 0, -20, 0, 0.8f, 0, 30, 0);
        a.rot("tail_fin", 0, 0, 30, 0, 0.15f, 0, -45, 0, 0.3f, 0, 30, 0, 0.55f, 0, -20, 0, 0.8f, 0, 30, 0);
        a.rot("jaw", 0, 0, 0, 0, 0.2f, 30, 0, 0, 0.4f, 5, 0, 0, 0.6f, 25, 0, 0, 0.8f, 0, 0, 0);
        a.swing("fin_right", 2, 25f, 0f);
        a.swing("fin_left", 2, -25f, 0f);
        return a;
    }

    // ============================================================================================ death hound

    /** Vampire death hound: gaunt, bony (spine and hips standing out), ragged ears, whip tail, glowing red eyes. */
    static CreatureDef deathHound() {
        Quad q = new Quad("death_hound");
        q.bodyW = 6;
        q.bodyH = 6;
        q.bodyL = 14;
        q.chestW = 7;
        q.chestH = 9;
        q.chestL = 6;
        q.legLen = 10;
        q.legW = 3;
        q.neckSegs = 1;
        q.neckLen = 5;
        q.neckW = 4;
        q.neckH = 4;
        q.neckPitch = -24;
        q.headW = 5;
        q.headH = 5;
        q.headL = 5;
        q.snoutW = 3;
        q.snoutH = 3;
        q.snoutL = 5;
        q.earH = 4;
        q.earW = 2;
        q.tailSegs = 3;
        q.tailLen = 12;
        q.tailW = 2;
        q.tailDroop = 60;
        q.fur = "hide";
        q.belly = null;
        q.headMat = "face";
        q.snoutMat = "face";
        q.legMat = "leg";
        q.chestL = 6;
        q.stride = 30;
        q.runStride = 48;
        q.build();
        ground(q);
        Rig rig = q.rig;
        // spine: a ridge of vertebrae along the back and neck; hip and shoulder blades jutting out
        for (int i = 0; i < 5; i++) q.body.box("bone", -0.5f, -3.8f, -4.5f + i * 2.4f, 1, 1, 1, 0.15f).tag("vertebra");
        q.neck[0].box("bone", -0.5f, -2.8f, -4f, 1, 1, 1, 0.1f).tag("vertebra");
        q.neck[0].box("bone", -0.5f, -2.8f, -2f, 1, 1, 1, 0.1f).tag("vertebra");
        q.body.box("bone", -3.5f, -3.5f, 4.5f, 7, 2, 2).tag("pelvis");
        q.chest.box("hide", -4f, -6.8f, -1f, 8, 2, 3).tag("shoulder_blades");
        // ribs: three bony hoops around the chest
        for (int i = 0; i < 3; i++) q.chest.box("rib", -3.5f, -3.5f, -2.5f + i * 2f, 7, 6, 1, 0.2f).tag("rib");
        q.body.box("rib", -3f, -2.5f, -3.5f, 6, 5, 1, 0.2f).tag("rib");
        // brow ridge, fangs and a hooked nose
        q.head.box("face", -2.5f, -3.2f, -5.3f, 5, 1, 2).tag("brow");
        q.snout.box("nose", -1f, -1.3f, -5.4f, 2, 1, 1).tag("nose");
        q.snout.box("tooth", -1.5f, 1.5f, -4.5f, 1, 2, 1).tag("fang");
        q.snout.box("tooth", 0.5f, 1.5f, -4.5f, 1, 2, 1).tag("fang");
        // ragged ears laid back
        sides(rig, "ear", b -> b.rot(-30, 0, -28));
        CreatureDef def = q.def();
        def.anim("attack2", lunge(1.3f));
        Anim howl = q.roar();
        rotBoth(howl, "ear", 0, 0, 0, 0, 0.35f, -30, 0, 0, 1.2f, -30, 0, 0, 1.6f, 0, 0, 0);
        howl.rot("tail1", 0, 0, 0, 0, 0.35f, 20, 0, 0, 1.2f, 20, 0, 0, 1.6f, 0, 0, 0);
        rotBoth(howl, "leg_front", 0, 0, 0, 0, 0.35f, -8, 0, 0, 1.2f, -8, 0, 0, 1.6f, 0, 0, 0);
        def.anim("special", howl);
        return def;
    }
}
