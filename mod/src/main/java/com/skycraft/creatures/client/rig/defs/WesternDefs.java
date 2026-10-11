package com.skycraft.creatures.client.rig.defs;

import com.skycraft.creatures.client.rig.Anim;
import com.skycraft.creatures.client.rig.Arachnid;
import com.skycraft.creatures.client.rig.Biped;
import com.skycraft.creatures.client.rig.Bone;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.client.rig.Quad;
import com.skycraft.creatures.client.rig.Rig;
import com.skycraft.creatures.client.rig.Serpent;

import java.util.function.Consumer;

/** Creatures of Hammerfell, High Rock, Valenwood, Summerset, the foreign lands and the endless realms. */
final class WesternDefs {
    private WesternDefs() {}

    static void register() {
        Defs.reg("sand_serpent", WesternDefs::sandSerpent);
        Defs.reg("giant_scorpion", WesternDefs::giantScorpion);
        Defs.reg("harpy", WesternDefs::harpy);
        Defs.reg("indrik", WesternDefs::indrik);
        Defs.reg("sea_serpent", WesternDefs::seaSerpent);
        Defs.reg("echatere", WesternDefs::echatere);
        Defs.reg("durzog", WesternDefs::durzog);
        Defs.reg("frost_giant", WesternDefs::frostGiant);
        Defs.reg("kamal", WesternDefs::kamal);
        Defs.reg("tsaesci", WesternDefs::tsaesci);
        Defs.reg("realm_stalker", WesternDefs::realmStalker);
        Defs.reg("realm_grazer", WesternDefs::realmGrazer);
        Defs.reg("realm_wyrm", WesternDefs::realmWyrm);
    }

    // ================================================================================================ helpers

    /** A child bone holding one box that points up (-y) from its pivot: spikes, spines, quills, horns. */
    private static Bone spike(Bone parent, String name, String mat, float x, float y, float z, float rx, float ry, float rz,
                              int w, int h, int d) {
        Bone s = parent.child(name, x, y, z).rot(rx, ry, rz);
        s.sbox(mat, -w / 2f, -h, -d / 2f, w, h, d).tag("spike");
        return s;
    }

    /** Runs the same builder on {@code <base>_right} then {@code <base>_left} (use sbox inside). */
    private static void both(Rig rig, String base, Consumer<Bone> build) {
        build.accept(rig.bone(base + "_right"));
        build.accept(rig.bone(base + "_left"));
    }

    /** Keeps only the listed animation slots. */
    private static CreatureDef only(CreatureDef d, String... slots) {
        java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(slots));
        d.anims.keySet().removeIf(k -> !keep.contains(k));
        return d;
    }

    /**
     * Sleep for long-legged hoofed grazers: lying on the belly with the forelegs folded under at the knee and the hind
     * legs tucked forward, neck lowered and head turned (the standard Quad sleep leaves long legs sticking out).
     */
    private static Anim tuckedSleep(Quad q, float drop, float neckDown) {
        Anim a = Anim.loop(4f);
        a.pos("body", 0, 0, -drop, 0, 4f, 0, -drop, 0);
        a.bob("body", 1, 0.3f, 0f, 1);
        for (String s : new String[]{"_right", "_left"}) {
            float out = s.equals("_right") ? 1 : -1;
            a.hold("leg_front" + s, -88, 0, 4 * out);
            a.hold("leg_front_lower" + s, 172, 0, 0);
            a.hold("leg_back" + s, -72, 0, 12 * out);
            a.hold("leg_back_lower" + s, 155, 0, 0);
        }
        if (q.neckSegs > 0) a.hold("neck1", neckDown, 0, 0);
        a.hold("head", 12, 28, 0);
        for (int i = 0; i < q.tail.length; i++) a.hold(q.tail[i].name, 10, i == 0 ? 20 : 12, 0);
        return a;
    }

    /** Body segment height of a serpent chain (same formula as {@link Serpent#build()}). */
    private static int segH(Serpent s, int i) {
        float f = s.segs == 1 ? 0 : (float) i / (s.segs - 1);
        return Math.max(1, Math.round(s.segH * (1 - (1 - s.taper) * f)));
    }

    /** Serpent collapse: the chain flattens (undoing its rest pitch), the head drops to the ground, coils go slack. */
    private static Anim serpentDeath(Serpent s, float[] restPitch) {
        Anim a = Anim.once(1.2f);
        float drop = -(s.height - s.headH / 2f);
        a.pos("head", 0, 0, 0, 0, 0.8f, 0, drop, 0, 1.2f, 0, drop, 0);
        a.rot("head", 0, 0, 0, 0, 0.8f, 8, 0, 15, 1.2f, 8, 0, 20);
        for (int i = 0; i < s.seg.length; i++) {
            float p = i < restPitch.length ? -restPitch[i] : 0;
            float yaw = (i % 2 == 0 ? 22 : -22);
            a.rot(s.seg[i].name, 0, 0, 0, 0, 0.8f, p, yaw, 0, 1.2f, p, yaw * 0.85f, 0);
        }
        a.rot("jaw", 0, 0, 0, 0, 0.8f, 30, 0, 0, 1.2f, 30, 0, 0);
        return a;
    }

    // ================================================================================================ Hammerfell

    /**
     * Sand serpent of the Alik'r: a huge burrowing desert worm-snake. Armoured, horned head with scything mandibles;
     * the neck dives back into the sand in an S and the banded, spined body trails along the ground.
     */
    static CreatureDef sandSerpent() {
        Serpent s = new Serpent("sand_serpent");
        s.headW = 8;
        s.headH = 6;
        s.headL = 10;
        s.jawH = 2;
        s.segs = 9;
        s.segLen = 7;
        s.segW = 8;
        s.segH = 7;
        s.taper = 0.35f;
        s.height = 15;
        s.fins = false;
        s.tailFin = false;
        s.spines = false;
        s.skin = "hide";
        s.belly = "belly";
        s.headMat = "carapace";
        s.scale = 1.75f;
        s.build();
        final float[] rest = {-40, -30, 55, 15};
        for (int i = 0; i < rest.length; i++) s.seg[i].rot(rest[i], 0, 0);
        // armoured crown and nose plate
        s.head.box("carapace", -4.5f, -4.2f, -9.5f, 9, 2, 9).tag("crown");
        s.head.box("carapace", -3, -3.6f, -11.5f, 6, 3, 2).tag("nose");
        // swept-back horns
        s.head.pair("horn", 3.2f, -3.5f, -2, (b, side) -> {
            b.rot(16, -28, 0);
            b.sbox("horn", -1, -1, 0, 2, 2, 6).tag("horn");
            Bone tip = b.child("horn_tip", 0, 0, 5.5f).rot(20, 0, 0);
            tip.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 4).tag("horn_tip");
        });
        // scything mandibles at the corners of the mouth
        s.head.pair("mandible", 3.2f, 1, -8, (b, side) -> {
            b.rot(0, -15, 0);
            b.sbox("tusk", -1, -1, -5, 2, 2, 5).tag("mandible");
            Bone tip = b.child("mandible_tip", 0, 0, -4.5f).rot(0, -40, 0);
            tip.sbox("tusk", -0.5f, -0.5f, -4, 1, 1, 4).tag("mandible_tip");
        });
        // dorsal spines raking back
        for (int i = 0; i < s.segs - 2; i++) {
            int h = segH(s, i);
            spike(s.seg[i], "spine" + (i + 1), "spine", 0, -h / 2f + 0.5f, s.segLen * 0.5f, -38, 0, 0, 1, i < 5 ? 4 : 3, 2);
        }

        CreatureDef d = new CreatureDef(s.rig);
        d.gait(0.55f, 0.4f, 0.6f);
        d.look("head", 0.7f).look("seg1", -0.3f);
        Anim idle = s.travel(3f, 5f);
        idle.swingN("mandible_right", 1, 6f, 0f, 2);
        idle.swingN("mandible_left", 1, -6f, 0f, 2);
        d.anim("idle", idle);
        d.anim("walk", s.travel(1.3f, 13f));
        Anim strike = s.strike();
        strike.rot("mandible_right", 0, 0, 0, 0, 0.3f, 0, 35, 0, 0.42f, 0, -10, 0, 0.8f, 0, 0, 0);
        strike.rot("mandible_left", 0, 0, 0, 0, 0.3f, 0, -35, 0, 0.42f, 0, 10, 0, 0.8f, 0, 0, 0);
        d.anim("attack", strike);
        Anim bite = s.bite();
        bite.rot("mandible_right", 0, 0, 0, 0, 0.2f, 0, 35, 0, 0.3f, 0, -12, 0, 0.55f, 0, 0, 0);
        bite.rot("mandible_left", 0, 0, 0, 0, 0.2f, 0, -35, 0, 0.3f, 0, 12, 0, 0.55f, 0, 0, 0);
        d.anim("attack2", bite);
        // bursts up out of the sand: starts buried, rises steeply, head high and jaws agape, then settles
        Anim e = Anim.once(1.6f);
        e.pos("head", 0, 0, -28, 5, 0.65f, 0, 5, -2, 1.2f, 0, 5, -2, 1.6f, 0, 0, 0);
        e.rot("head", 0, -55, 0, 0, 0.65f, -32, 0, 0, 1.2f, -26, 0, 0, 1.6f, 0, 0, 0);
        e.rot("seg1", 0, 20, 0, 0, 0.65f, 15, 0, 0, 1.2f, 12, 0, 0, 1.6f, 0, 0, 0);
        e.linear().rot("jaw", 0, 0, 0, 0, 0.4f, 0, 0, 0, 0.65f, 45, 0, 0, 1.2f, 42, 0, 0, 1.45f, 0, 0, 0, 1.6f, 0, 0, 0).smooth();
        e.rot("mandible_right", 0, 0, 0, 0, 0.65f, 0, 40, 0, 1.2f, 0, 35, 0, 1.6f, 0, 0, 0);
        e.rot("mandible_left", 0, 0, 0, 0, 0.65f, 0, -40, 0, 1.2f, 0, -35, 0, 1.6f, 0, 0, 0);
        d.anim("emerge", e);
        d.anim("death", serpentDeath(s, rest));
        d.anim("hurt", s.hurt());
        return d;
    }

    /** Giant desert scorpion: broad flat carapace, heavy pincers, eight legs and a five-jointed tail arched overhead. */
    static CreatureDef giantScorpion() {
        Arachnid a = new Arachnid("giant_scorpion");
        a.bodyW = 10;
        a.bodyH = 5;
        a.bodyL = 9;
        a.headW = 8;
        a.headH = 4;
        a.headL = 4;
        a.fangLen = 2;
        a.abdomenW = 10;
        a.abdomenH = 5;
        a.abdomenL = 13;
        a.abdomenTilt = 0;
        a.legPairs = 4;
        a.legUpper = 8;
        a.legLower = 10;
        a.legW = 2;
        a.legUp = 40;
        a.legDown = 65;
        a.pincers = false;
        a.pincerLen = 7;
        a.tailSegs = 5;
        a.tailSegLen = 5;
        a.tailW = 4;
        a.shell = "chitin";
        a.legMat = "leg";
        a.abdomenMat = "chitin";
        a.fangMat = "fang";
        a.scale = 1.4f;
        a.build();
        // the tail arches up and over the back (positive pitch raises a backward-pointing chain)
        a.abdomen.rot(4, 0, 0);
        a.tail[0].rot(62, 0, 0);
        for (int i = 1; i < a.tail.length; i++) a.tail[i].rot(30, 0, 0);
        a.rig.bone("sting").rot(40, 0, 0);
        // raised carapace ridge and segment plates on the abdomen
        a.body.box("chitin", -4, -3.3f, -4, 8, 1, 8).tag("ridge");
        for (int i = 0; i < 3; i++) a.abdomen.box("plate", -5.5f, -3, 1 + i * 4, 11, 2, 3).tag("plate");
        // heavy pincers: upper arm reaching out, forearm folding in, a bulging chela with fixed and moving fingers
        a.body.pair("claw", a.bodyW / 2f - 1.5f, a.bodyH / 4f, -a.bodyL / 2f + 1, (b, side) -> {
            b.rot(-12, 50, 0);
            b.sbox("chitin", -1.5f, -1.5f, -6, 3, 3, 6).tag("arm");
            Bone low = b.child("claw_lower", 0, 0, -5.5f).rot(0, -62, 0);
            low.sbox("chitin", -1.5f, -1.5f, -6, 3, 3, 6).tag("arm");
            Bone hand = low.child("claw_hand", 0, 0, -5.5f).rot(8, -8, 0);
            hand.sbox("chitin", -2.5f, -2.5f, -6, 5, 5, 6).tag("palm");
            hand.sbox("chitin", -2.5f, -1, -10, 2, 2, 4).tag("finger");
            Bone p = hand.child("pincer", 1.5f, 0, -5.5f);
            p.sbox("chitin", -1, -1, -4.5f, 2, 2, 5).tag("pincer");
        });

        CreatureDef d = a.def();
        d.anim("attack", pinch(true));
        d.anim("attack2", pinch(false));
        d.anim("attack3", a.sting());
        Anim idle = a.idle();
        idle.swing("claw_right", 1, 4f, 0f);
        idle.swing("claw_left", 1, -4f, 0.4f);
        idle.swingN("pincer_right", 1, 8f, 0.1f, 2);
        idle.swingN("pincer_left", 1, -8f, 0.6f, 2);
        d.anim("idle", idle);
        return only(d, "idle", "walk", "run", "attack", "attack2", "attack3", "death", "hurt");
    }

    /** One pincer reaches forward, opens and snaps shut (hit at ~0.33 s). */
    private static Anim pinch(boolean right) {
        String s = right ? "_right" : "_left";
        float m = right ? 1 : -1;
        Anim a = Anim.once(0.65f);
        a.rot("claw" + s, 0, 0, 0, 0, 0.2f, -18, -15 * m, 0, 0.33f, 4, -22 * m, 0, 0.65f, 0, 0, 0);
        a.rot("claw_lower" + s, 0, 0, 0, 0, 0.2f, 0, 30 * m, 0, 0.33f, 0, 45 * m, 0, 0.65f, 0, 0, 0);
        a.rot("claw_hand" + s, 0, 0, 0, 0, 0.2f, -10, 0, 0, 0.33f, 10, 0, 0, 0.65f, 0, 0, 0);
        a.linear().rot("pincer" + s, 0, 0, 0, 0, 0.18f, 0, -45 * m, 0, 0.3f, 0, -45 * m, 0, 0.34f, 0, 4 * m, 0, 0.65f, 0, 0, 0).smooth();
        a.rot("body", 0, 0, 0, 0, 0.33f, -5, -8 * m, 0, 0.65f, 0, 0, 0);
        a.pos("body", 0, 0, 0, 0, 0.33f, 0, 0, -1.5f, 0.65f, 0, 0, 0);
        return a;
    }

    // ================================================================================================ High Rock

    /** Harpy: a wild-haired woman's head and torso, feathered wings for arms, feathered hips and taloned bird legs. */
    static CreatureDef harpy() {
        Biped b = new Biped("harpy");
        b.legLen = 12;
        b.legW = 3;
        b.hipW = 6;
        b.hipH = 3;
        b.torsoW = 6;
        b.torsoH = 8;
        b.torsoD = 4;
        b.hunch = 8;
        b.armLen = 12;
        b.armW = 2;
        b.headW = 5;
        b.headH = 6;
        b.headD = 5;
        b.jaw = false;
        b.digitigrade = true;
        b.skin = "skin";
        b.armMat = "wingarm";
        b.legMat = "birdleg";
        b.footMat = "birdleg";
        b.hipMat = "plumage";
        b.scale = 1f;
        b.stride = 24;
        b.build();
        Rig r = b.rig;
        // a small hinged jaw flush with the face (opens for the screech)
        Bone jaw = b.head.child("jaw", 0, -1.6f, -2);
        jaw.box("skin", -1.5f, 0, -1.1f, 3, 2, 2).tag("jaw");
        // wild hair: crown, mane down the back, side locks, stray tufts
        b.head.box("hair", -3, -7, -3.2f, 6, 2, 6, 0.3f).tag("hair_top");
        b.head.box("hair", -3, -6, 1.5f, 6, 10, 2, 0.3f).tag("hair_back");
        b.head.box("hair", -3.3f, -5.5f, -2.2f, 1, 7, 4, 0.2f).tag("hair_side");
        b.head.box("hair", 2.3f, -5.5f, -2.2f, 1, 7, 4, 0.2f).tag("hair_side");
        spike(b.head, "tuft_right", "hair", -3, -4.5f, 0.5f, -20, 0, -125, 2, 4, 2);
        spike(b.head, "tuft_left", "hair", 3, -4, 1, -25, 0, 118, 2, 4, 2);
        spike(b.head, "tuft_back", "hair", 0, -6.5f, 2, -105, 0, 0, 3, 4, 2);
        b.head.box("skin", -0.5f, -3.5f, -3.6f, 1, 2, 1).tag("nose");
        // feathered breast and shoulders, feather skirt, tail fan
        b.torso.box("plumage", -3.5f, -5.5f, -2.5f, 7, 6, 5, 0.15f).tag("breast");
        b.hips.box("plumage", -3.5f, -2.5f, -2.5f, 7, 5, 5, 0.3f).tag("skirt");
        Bone fan = b.hips.child("tail_fan", 0, -1, 2.2f).rot(-58, 0, 0);
        fan.box("feather", -3.5f, 0, 0, 7, 0, 9).tag("tail_fan");
        // feathered thighs ("trousers")
        both(r, "leg", l -> l.sbox("plumage", -2, -1, -2, 4, 6, 4, 0.2f).tag("thigh"));
        // three forward talons and a hind toe
        both(r, "foot", f -> {
            f.sbox("talon", -1.6f, 0.5f, -5.5f, 1, 1, 3).tag("talon");
            f.sbox("talon", 0.6f, 0.5f, -5.5f, 1, 1, 3).tag("talon");
            f.sbox("talon", -0.5f, 0.5f, 0.5f, 1, 1, 2).tag("talon");
        });
        // wings: feather vanes on their own bones along the arm; folded down along the arm at rest, swung out
        // perpendicular (straight back) when the wings open (anims add +HARPY_FOLD)
        both(r, "arm", a -> a.child("wing_covert", 0, -1, 0.5f).rot(-HARPY_FOLD[0], 0, 0)
                .sbox("wing", 0, 0, 0, 0, 7, 6).tag("covert"));
        both(r, "forearm", a -> a.child("wing_secondary", 0, -0.5f, 0.5f).rot(-HARPY_FOLD[1], 0, 0)
                .sbox("wing", 0, 0, 0, 0, 7, 8).tag("secondary"));
        both(r, "hand", a -> a.child("wing_primary", 0, 0, -0.5f).rot(-HARPY_FOLD[2], 0, 0)
                .sbox("wing", 0, 0, 0, 0, 7, 8).tag("primary"));

        CreatureDef d = b.def();
        d.anim("fly", harpyFly(0.8f, 40f, 80f, true));
        d.anim("hover", harpyFly(0.55f, 38f, 62f, false));
        // talon rake: hop up, both feet swing forward and rake back (hit ~0.4 s)
        Anim rake = Anim.once(0.85f);
        rake.pos("hips", 0, 0, 0, 0, 0.25f, 0, 5, 0, 0.42f, 0, 4, -2, 0.85f, 0, 0, 0);
        rake.rot("torso", 0, 0, 0, 0, 0.28f, -25, 0, 0, 0.45f, 10, 0, 0, 0.85f, 0, 0, 0);
        rake.rot("leg_right", 0, 0, 0, 0, 0.28f, -80, 0, 0, 0.42f, 15, 0, 0, 0.85f, 0, 0, 0);
        rake.rot("leg_left", 0, 0, 0, 0, 0.3f, -75, 0, 0, 0.45f, 20, 0, 0, 0.85f, 0, 0, 0);
        rake.rot("shin_right", 0, 0, 0, 0, 0.28f, -20, 0, 0, 0.42f, 30, 0, 0, 0.85f, 0, 0, 0);
        rake.rot("shin_left", 0, 0, 0, 0, 0.3f, -20, 0, 0, 0.45f, 30, 0, 0, 0.85f, 0, 0, 0);
        rake.rot("arm_right", 0, 0, 0, 0, 0.15f, 10, 0, 90, 0.3f, 10, 0, 55, 0.45f, 10, 0, 95, 0.85f, 0, 0, 0);
        rake.rot("arm_left", 0, 0, 0, 0, 0.15f, 10, 0, -90, 0.3f, 10, 0, -55, 0.45f, 10, 0, -95, 0.85f, 0, 0, 0);
        harpyWings(rake, 0.15f, 0.6f, 0.85f);
        d.anim("attack", rake);
        // wing buffet: both wings sweep from behind to the front
        Anim buffet = Anim.once(0.75f);
        buffet.rot("arm_right", 0, 0, 0, 0, 0.25f, 35, 0, 75, 0.4f, -85, 0, 25, 0.55f, -60, 0, 15, 0.75f, 0, 0, 0);
        buffet.rot("arm_left", 0, 0, 0, 0, 0.25f, 35, 0, -75, 0.4f, -85, 0, -25, 0.55f, -60, 0, -15, 0.75f, 0, 0, 0);
        buffet.rot("torso", 0, 0, 0, 0, 0.25f, -12, 0, 0, 0.4f, 14, 0, 0, 0.75f, 0, 0, 0);
        buffet.rot("head", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.4f, -10, 0, 0, 0.75f, 0, 0, 0);
        harpyWings(buffet, 0.2f, 0.55f, 0.75f);
        d.anim("attack2", buffet);
        // screech: head thrust out, mouth wide, wings flung open
        Anim screech = Anim.once(1.4f);
        screech.rot("head", 0, 0, 0, 0, 0.25f, -22, 0, 0, 0.7f, -18, 8, 0, 1.1f, -20, -8, 0, 1.4f, 0, 0, 0);
        screech.pos("head", 0, 0, 0, 0, 0.25f, 0, 0, -1.2f, 1.1f, 0, 0, -1.2f, 1.4f, 0, 0, 0);
        screech.rot("jaw", 0, 0, 0, 0, 0.25f, 38, 0, 0, 1.1f, 36, 0, 0, 1.4f, 0, 0, 0);
        screech.rot("torso", 0, 0, 0, 0, 0.25f, -14, 0, 0, 1.1f, -12, 0, 0, 1.4f, 0, 0, 0);
        screech.rot("arm_right", 0, 0, 0, 0, 0.25f, 25, 0, 105, 1.1f, 20, 0, 100, 1.4f, 0, 0, 0);
        screech.rot("arm_left", 0, 0, 0, 0, 0.25f, 25, 0, -105, 1.1f, 20, 0, -100, 1.4f, 0, 0, 0);
        screech.rot("forearm_right", 0, 0, 0, 0, 0.25f, -20, 0, 0, 1.1f, -20, 0, 0, 1.4f, 0, 0, 0);
        screech.rot("forearm_left", 0, 0, 0, 0, 0.25f, -20, 0, 0, 1.1f, -20, 0, 0, 1.4f, 0, 0, 0);
        harpyWings(screech, 0.25f, 1.1f, 1.4f);
        d.anim("special", screech);
        return only(d, "idle", "walk", "fly", "hover", "attack", "attack2", "special", "death", "hurt");
    }

    /** How far each feather row of the harpy's wings is folded down along the arm at rest (degrees). */
    private static final float[] HARPY_FOLD = {55, 62, 66};
    private static final String[] HARPY_FEATHERS = {"wing_covert", "wing_secondary", "wing_primary"};

    /** Opens the harpy's wing feathers (straight back from the arm) over a one-shot: open by t0, hold to t1, folded at t2. */
    private static void harpyWings(Anim a, float t0, float t1, float t2) {
        for (int i = 0; i < 3; i++) {
            for (String s : new String[]{"_right", "_left"}) {
                a.rot(HARPY_FEATHERS[i] + s, 0, 0, 0, 0, t0, HARPY_FOLD[i], 0, 0, t1, HARPY_FOLD[i], 0, 0, t2, 0, 0, 0);
            }
        }
    }

    /** Wings raised to the sides and flapping; flying leans forward with the legs tucked back, hovering hangs upright. */
    private static Anim harpyFly(float len, float amp, float raise, boolean forward) {
        Anim a = Anim.loop(len);
        for (int i = 0; i < 3; i++) {
            a.hold(HARPY_FEATHERS[i] + "_right", HARPY_FOLD[i], 0, 0);
            a.hold(HARPY_FEATHERS[i] + "_left", HARPY_FOLD[i], 0, 0);
        }
        a.swing("arm_right", 2, amp, 0f, raise);
        a.swing("arm_left", 2, -amp, 0f, -raise);
        a.swing("forearm_right", 2, amp * 0.35f, -0.12f);
        a.swing("forearm_left", 2, -amp * 0.35f, -0.12f);
        a.swing("hand_right", 2, amp * 0.3f, -0.2f);
        a.swing("hand_left", 2, -amp * 0.3f, -0.2f);
        a.swing("arm_right", 0, 8f, 0.25f, forward ? 10 : 0);
        a.swing("arm_left", 0, 8f, 0.25f, forward ? 10 : 0);
        a.bob("hips", 1, forward ? 1.2f : 1.0f, 0.25f, 1);
        if (forward) {
            a.hold("torso", 40, 0, 0);
            a.hold("head", -35, 0, 0);
            a.hold("leg_right", 55, 0, 0);
            a.hold("leg_left", 55, 0, 0);
            a.hold("shin_right", 25, 0, 0);
            a.hold("shin_left", 25, 0, 0);
            a.hold("tail_fan", 50, 0, 0);
        } else {
            a.hold("torso", 8, 0, 0);
            a.swing("leg_right", 0, 6f, 0.3f, -15);
            a.swing("leg_left", 0, 6f, 0.8f, -10);
            a.hold("shin_right", 20, 0, 0);
            a.hold("shin_left", 20, 0, 0);
            a.hold("tail_fan", 25, 0, 0);
        }
        return a;
    }

    // ================================================================================================ Summerset & Valenwood

    /** Indrik: a graceful deer-horse with a feathered crest, wing-like feathered antlers and a long plumed tail. */
    static CreatureDef indrik() {
        Quad q = new Quad("indrik");
        q.bodyW = 8;
        q.bodyH = 8;
        q.bodyL = 17;
        q.chestW = 9;
        q.chestH = 9;
        q.chestL = 7;
        q.legLen = 14;
        q.legW = 3;
        q.foot = "hoof";
        q.footMat = "hoof";
        q.neckSegs = 2;
        q.neckLen = 5;
        q.neckW = 4;
        q.neckH = 5;
        q.neckPitch = -50;
        q.headW = 5;
        q.headH = 5;
        q.headL = 5;
        q.snoutW = 4;
        q.snoutH = 3;
        q.snoutL = 4;
        q.jaw = false;
        q.earH = 3;
        q.earW = 2;
        q.tailSegs = 4;
        q.tailLen = 16;
        q.tailW = 3;
        q.tailDroop = 45;
        q.fur = "coat";
        q.belly = "belly";
        q.legMat = "leg";
        q.tailMat = "coat";
        q.neckMat = "coat";
        q.headMat = "coat";
        q.snoutMat = "muzzle";
        q.scale = 1f;
        q.stride = 24;
        q.runStride = 40;
        q.build();
        // feathered crest along the neck and over the brow
        for (Bone n : q.neck) n.box("crest", 0, -q.neckH / 2f - 3, -q.neckLen, 0, 3, q.neckLen + 1).tag("crest");
        q.head.box("crest", 0, -q.headH / 2f - 3, -3.5f, 0, 3, 4).tag("crest");
        q.neck[0].box("plume", -2.5f, -3, -4, 5, 5, 3, 0.3f).tag("ruff");
        // wing-like feathered antlers sweeping up and back
        q.head.pair("antler", 1.5f, -q.headH / 2f + 0.5f, -1.5f, (b, side) -> {
            b.rot(-28, -35, -24);
            b.sbox("antler", -0.5f, -6, -0.5f, 1, 6, 1).tag("quill");
            b.sbox("plume", 0, -6, 0.5f, 0, 6, 5).tag("vane");
            Bone tip = b.child("antler_tip", 0, -5.5f, 0).rot(-35, 0, 0);
            tip.sbox("antler", -0.5f, -5, -0.5f, 1, 5, 1).tag("quill");
            tip.sbox("plume", 0, -8, 0.5f, 0, 8, 7).tag("vane_tip");
        });
        // long plumed tail: crossed feather vanes on each segment, widening towards the tip
        for (int i = 0; i < q.tail.length; i++) {
            int wpl = 4 + i * 2;
            int len = Math.round((float) q.tailLen / q.tailSegs + 0.4f);
            q.tail[i].box("plume", -wpl / 2f, 0, 0, wpl, 0, len).tag("tail_plume");
            q.tail[i].box("plume", 0, -wpl / 2f + 1, 0, 0, wpl - 2, len).tag("tail_plume");
        }

        CreatureDef d = q.def();
        // rears up on the hind legs, forehooves pawing the air
        Anim rear = Anim.once(1.6f);
        rear.rot("body", 0, 0, 0, 0, 0.45f, -38, 0, 0, 1.1f, -35, 0, 0, 1.6f, 0, 0, 0);
        rear.pos("body", 0, 0, 0, 0, 0.45f, 0, 3.4f, 0, 1.1f, 0, 3.2f, 0, 1.6f, 0, 0, 0);
        rear.rot("leg_back_right", 0, 0, 0, 0, 0.45f, 38, 0, 0, 1.1f, 35, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("leg_back_left", 0, 0, 0, 0, 0.45f, 38, 0, 0, 1.1f, 35, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("leg_front_right", 0, 0, 0, 0, 0.45f, -75, 0, 0, 0.75f, -40, 0, 0, 1.1f, -70, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("leg_front_lower_right", 0, 0, 0, 0, 0.45f, 85, 0, 0, 0.75f, 50, 0, 0, 1.1f, 80, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("leg_front_left", 0, 0, 0, 0, 0.45f, -45, 0, 0, 0.75f, -80, 0, 0, 1.1f, -45, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("leg_front_lower_left", 0, 0, 0, 0, 0.45f, 55, 0, 0, 0.75f, 90, 0, 0, 1.1f, 55, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("neck1", 0, 0, 0, 0, 0.45f, 12, 0, 0, 1.1f, 10, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("head", 0, 0, 0, 0, 0.45f, 15, 0, 0, 1.1f, 12, 0, 0, 1.6f, 0, 0, 0);
        rear.rot("antler_right", 0, 0, 0, 0, 0.45f, 0, 0, -12, 1.1f, 0, 0, -12, 1.6f, 0, 0, 0);
        rear.rot("antler_left", 0, 0, 0, 0, 0.45f, 0, 0, 12, 1.1f, 0, 0, 12, 1.6f, 0, 0, 0);
        rear.rot("tail1", 0, 0, 0, 0, 0.45f, 25, 0, 0, 1.1f, 25, 0, 0, 1.6f, 0, 0, 0);
        d.anim("special", rear);
        Anim idle = q.idle();
        idle.swing("antler_right", 2, 3f, 0f);
        idle.swing("antler_left", 2, -3f, 0f);
        d.anim("idle", idle);
        d.anim("sleep", tuckedSleep(q, q.legLen - 0.5f, 30));
        return only(d, "idle", "walk", "run", "special", "sleep", "death", "hurt");
    }

    /** Sea serpent of the Abecean: crested, horned head held high, finned and spined teal body, pale belly. */
    static CreatureDef seaSerpent() {
        Serpent s = new Serpent("sea_serpent");
        s.headW = 7;
        s.headH = 6;
        s.headL = 10;
        s.jawH = 2;
        s.segs = 9;
        s.segLen = 7;
        s.segW = 7;
        s.segH = 7;
        s.taper = 0.3f;
        s.height = 10;
        s.fins = true;
        s.finLen = 7;
        s.tailFin = true;
        s.lateral = true;
        s.skin = "scales";
        s.belly = "belly";
        s.headMat = "head";
        s.finMat = "fin";
        s.scale = 3f;
        s.build();
        final float[] rest = {-25, 25};
        for (int i = 0; i < rest.length; i++) s.seg[i].rot(rest[i], 0, 0);
        // finned crest, brow horns and cheek frills
        s.head.box("fin", 0, -s.headH / 2f - 5, -7, 0, 5, 9).tag("crest");
        s.head.box("head", -3.5f, -3.6f, -9, 7, 1, 6).tag("brow");
        s.head.pair("horn", 2.6f, -3, -2.5f, (b, side) -> {
            b.rot(25, -22, 0);
            b.sbox("horn", -1, -1, 0, 2, 2, 5).tag("horn");
            Bone tip = b.child("horn_tip", 0, 0, 4.5f).rot(25, 0, 0);
            tip.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 4).tag("horn_tip");
        });
        s.head.pair("frill", s.headW / 2f, -0.5f, -2.5f, (b, side) -> {
            b.rot(0, -35, 0);
            b.sbox("fin", 0, -3, 0, 0, 6, 6).tag("frill");
        });
        // dorsal sail fins with spine rays along the body
        for (int i = 0; i < s.segs - 1; i++) {
            int h = segH(s, i);
            int fh = Math.max(2, 5 - i / 2);
            s.seg[i].box("fin", 0, -h / 2f - fh, 0.5f, 0, fh, s.segLen - 1).tag("dorsal");
        }

        CreatureDef d = new CreatureDef(s.rig);
        d.gait(0.6f, 0.45f, 0.6f);
        d.look("head", 0.7f).look("seg1", -0.3f);
        d.anim("idle", s.travel(3.5f, 5f));
        Anim swim = s.travel(1.6f, 11f);
        for (int i = 2; i < s.seg.length; i++) swim.wave(s.seg[i].name, Anim.Target.ROTATION, 0, 4f, -i * 0.12f, 0, 1);
        d.anim("swim", swim);
        d.anim("walk", s.travel(1.3f, 13f));
        d.anim("attack", s.bite());
        d.anim("attack2", s.strike());
        // rears high out of the water, jaws open, frills flared
        Anim rear = Anim.once(2.0f);
        rear.pos("head", 0, 0, 0, 0, 0.6f, 0, 10, -3, 1.4f, 0, 10, -3, 2.0f, 0, 0, 0);
        rear.rot("head", 0, 0, 0, 0, 0.6f, -12, 0, 0, 1.0f, -18, 8, 0, 1.4f, -14, -8, 0, 2.0f, 0, 0, 0);
        rear.rot("seg1", 0, 0, 0, 0, 0.6f, -50, 0, 0, 1.4f, -50, 0, 0, 2.0f, 0, 0, 0);
        rear.rot("seg2", 0, 0, 0, 0, 0.6f, 25, 0, 0, 1.4f, 25, 0, 0, 2.0f, 0, 0, 0);
        rear.rot("seg3", 0, 0, 0, 0, 0.6f, 25, 0, 0, 1.4f, 25, 0, 0, 2.0f, 0, 0, 0);
        rear.linear().rot("jaw", 0, 0, 0, 0, 0.6f, 40, 0, 0, 1.4f, 40, 0, 0, 1.7f, 0, 0, 0, 2.0f, 0, 0, 0).smooth();
        rear.rot("frill_right", 0, 0, 0, 0, 0.6f, 0, 30, 0, 1.4f, 0, 30, 0, 2.0f, 0, 0, 0);
        rear.rot("frill_left", 0, 0, 0, 0, 0.6f, 0, -30, 0, 1.4f, 0, -30, 0, 2.0f, 0, 0, 0);
        rear.rot("fin_right", 0, 0, 0, 0, 0.6f, 0, 0, 30, 1.4f, 0, 0, 30, 2.0f, 0, 0, 0);
        rear.rot("fin_left", 0, 0, 0, 0, 0.6f, 0, 0, -30, 1.4f, 0, 0, -30, 2.0f, 0, 0, 0);
        d.anim("special", rear);
        d.anim("death", serpentDeath(s, rest));
        d.anim("hurt", s.hurt());
        return d;
    }

    // ================================================================================================ Wrothgar

    /** Echatere: the Orcs' huge shaggy livestock, a boar crossed with a mammoth: low head, flat snout, short tusks. */
    static CreatureDef echatere() {
        Quad q = new Quad("echatere");
        q.bodyW = 14;
        q.bodyH = 12;
        q.bodyL = 20;
        q.chestW = 16;
        q.chestH = 15;
        q.chestL = 9;
        q.legLen = 8;
        q.legW = 5;
        q.foot = "hoof";
        q.footMat = "hoof";
        q.neckSegs = 1;
        q.neckLen = 3;
        q.neckW = 9;
        q.neckH = 10;
        q.neckPitch = 18;
        q.headW = 10;
        q.headH = 9;
        q.headL = 7;
        q.snoutW = 8;
        q.snoutH = 5;
        q.snoutL = 3;
        q.jaw = true;
        q.earH = 3;
        q.earW = 3;
        q.tailSegs = 2;
        q.tailLen = 6;
        q.tailW = 2;
        q.tailDroop = 60;
        q.fur = "shag";
        q.belly = "belly";
        q.headMat = "headfur";
        q.snoutMat = "snout";
        q.legMat = "legfur";
        q.tailMat = "shag";
        q.neckMat = "shag";
        q.scale = 1.25f;
        q.stride = 18;
        q.runStride = 28;
        q.gallop = false;
        q.butt = true;
        q.build();
        Rig r = q.rig;
        // long hair skirt hanging off the flanks over the legs, a woolly hump, a ridge down the back
        q.body.box("shag", -q.bodyW / 2f - 0.5f, q.bodyH / 2f - 3, -q.bodyL / 2f + 1, q.bodyW + 1, 6, q.bodyL - 2, 0.3f).tag("skirt");
        q.chest.box("mane", -6.5f, -11.5f, -4.5f, 13, 5, 9, 0.4f).tag("hump");
        q.body.box("mane", -4.5f, -q.bodyH / 2f - 1.5f, -5, 9, 2, 13, 0.2f).tag("ridge");
        q.head.box("mane", -4, -q.headH / 2f - 2, -5, 8, 3, 5, 0.2f).tag("forelock");
        q.neck[0].box("mane", -4, 2.5f, -3.5f, 8, 5, 4, 0.2f).tag("throat");
        // drooping ears
        both(r, "ear", e -> e.rot(15, 0, -95));
        // short upturned tusks at the corners of the snout
        q.snout.pair("tusk", 3.2f, 1.5f, -2, (b, side) -> {
            b.rot(25, 0, -25);
            b.sbox("tusk", -1, -3, -1, 2, 3, 2).tag("tusk");
            Bone tip = b.child("tusk_tip", 0, -2.5f, 0).rot(-30, 0, 15);
            tip.sbox("tusk", -0.5f, -2, -0.5f, 1, 2, 1).tag("tusk_tip");
        });

        CreatureDef d = q.def();
        Anim bellow = q.roar();
        bellow.rot("body", 0, 0, 0, 0, 0.35f, -6, 0, 0, 0.6f, -5, 0, 2, 0.9f, -5, 0, -2, 1.2f, -5, 0, 0, 1.6f, 0, 0, 0);
        bellow.scale("body", 0, 1, 1, 1, 0.35f, 1.04f, 1.05f, 1, 1.2f, 1.04f, 1.05f, 1, 1.6f, 1, 1, 1);
        d.anim("special", bellow);
        return only(d, "idle", "walk", "run", "attack", "special", "sleep", "death", "hurt");
    }

    /** Durzog: the Orcs' war beast, a reptilian hyena-dog with a toothy beak, a spined back and sinewy legs. */
    static CreatureDef durzog() {
        Quad q = new Quad("durzog");
        q.bodyW = 6;
        q.bodyH = 6;
        q.bodyL = 13;
        q.chestW = 7;
        q.chestH = 8;
        q.chestL = 6;
        q.legLen = 10;
        q.frontLegLen = 10;
        q.backLegLen = 9;
        q.legW = 3;
        q.foot = "paw";
        q.footMat = "claw";
        q.neckSegs = 1;
        q.neckLen = 4;
        q.neckW = 4;
        q.neckH = 5;
        q.neckPitch = -18;
        q.headW = 5;
        q.headH = 5;
        q.headL = 4;
        q.snoutW = 3;
        q.snoutH = 2;
        q.snoutL = 6;
        q.jaw = true;
        q.earH = 2;
        q.earW = 2;
        q.tailSegs = 4;
        q.tailLen = 12;
        q.tailW = 3;
        q.tailDroop = 25;
        q.fur = "hide";
        q.belly = "belly";
        q.headMat = "hide";
        q.snoutMat = "beak";
        q.legMat = "leg";
        q.tailMat = "hide";
        q.scale = 1f;
        q.stride = 30;
        q.runStride = 46;
        q.build();
        // a hyena's slope: shoulders high, rump low
        q.body.rot(-6, 0, 0);
        q.head.rot(24, 0, 0);
        // hooked beak tip and a row of teeth under the upper beak
        float snoutTop = q.headH / 2f - q.snoutH - 1;
        q.snout.box("beak", -1.5f, snoutTop - 0.5f, -q.snoutL - 1, 3, 3, 2).tag("beak_tip");
        q.snout.box("tooth", -1.5f, snoutTop + q.snoutH, -q.snoutL + 0.5f, 3, 1, q.snoutL - 1).tag("teeth");
        q.jawBone.box("tooth", -1, -1, -6.5f, 2, 1, 5).tag("teeth");
        // brow ridges
        q.head.box("hide", -2.5f, -q.headH / 2f - 0.6f, -q.headL, 5, 1, 3).tag("brow");
        // spines down the neck and back
        spike(q.neck[0], "neck_spine", "spine", 0, -q.neckH / 2f + 0.5f, -2, -40, 0, 0, 1, 3, 2);
        float[] zs = {-5.5f, -3, -0.5f, 2, 4.5f};
        int[] hs = {4, 4, 3, 3, 2};
        for (int i = 0; i < zs.length; i++) {
            spike(q.body, "spine" + (i + 1), "spine", 0, -q.bodyH / 2f + 0.5f, zs[i], -42, 0, 0, 1, hs[i], 2);
        }
        spike(q.tail[0], "tail_spine", "spine", 0, -1, 2, -45, 0, 0, 1, 2, 2);

        CreatureDef d = q.def();
        // lunge: springs forward with forelegs reaching and jaws wide (hit ~0.38 s)
        Anim lunge = Anim.once(0.85f);
        lunge.pos("body", 0, 0, 0, 0, 0.15f, 0, -1, 2, 0.38f, 0, 3, -8, 0.55f, 0, 1, -6, 0.85f, 0, 0, 0);
        lunge.rot("body", 0, 0, 0, 0, 0.15f, 6, 0, 0, 0.38f, -10, 0, 0, 0.55f, 4, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("leg_front_right", 0, 0, 0, 0, 0.15f, 20, 0, 0, 0.38f, -65, 0, 0, 0.55f, -20, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("leg_front_left", 0, 0, 0, 0, 0.15f, 20, 0, 0, 0.38f, -60, 0, 0, 0.55f, -20, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("leg_back_right", 0, 0, 0, 0, 0.15f, -20, 0, 0, 0.38f, 50, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("leg_back_left", 0, 0, 0, 0, 0.15f, -20, 0, 0, 0.38f, 50, 0, 0, 0.85f, 0, 0, 0);
        lunge.rot("neck1", 0, 0, 0, 0, 0.38f, 15, 0, 0, 0.85f, 0, 0, 0);
        lunge.linear().rot("jaw", 0, 0, 0, 0, 0.2f, 45, 0, 0, 0.36f, 45, 0, 0, 0.42f, 0, 0, 0, 0.85f, 0, 0, 0).smooth();
        d.anim("attack2", lunge);
        // snarl: crouched, head low, spines raised, jaws working
        Anim snarl = Anim.once(1.4f);
        snarl.pos("body", 0, 0, 0, 0, 0.25f, 0, -1.5f, 1, 1.1f, 0, -1.5f, 1, 1.4f, 0, 0, 0);
        snarl.rot("body", 0, 0, 0, 0, 0.25f, 6, 0, 0, 1.1f, 6, 0, 0, 1.4f, 0, 0, 0);
        snarl.rot("neck1", 0, 0, 0, 0, 0.25f, 18, 0, 0, 1.1f, 18, 0, 0, 1.4f, 0, 0, 0);
        snarl.rot("head", 0, 0, 0, 0, 0.25f, -8, 0, 0, 0.5f, -8, 6, 0, 0.8f, -8, -6, 0, 1.1f, -8, 0, 0, 1.4f, 0, 0, 0);
        snarl.rot("jaw", 0, 0, 0, 0, 0.25f, 30, 0, 0, 0.45f, 15, 0, 0, 0.65f, 32, 0, 0, 0.85f, 12, 0, 0, 1.1f, 30, 0, 0, 1.4f, 0, 0, 0);
        for (int i = 1; i <= 5; i++) snarl.rot("spine" + i, 0, 0, 0, 0, 0.25f, 30, 0, 0, 1.1f, 30, 0, 0, 1.4f, 0, 0, 0);
        snarl.rot("neck_spine", 0, 0, 0, 0, 0.25f, 30, 0, 0, 1.1f, 30, 0, 0, 1.4f, 0, 0, 0);
        snarl.rot("tail1", 0, 0, 0, 0, 0.25f, -20, 0, 0, 1.1f, -20, 0, 0, 1.4f, 0, 0, 0);
        d.anim("special", snarl);
        return only(d, "idle", "walk", "run", "attack", "attack2", "special", "death", "hurt");
    }

    // ================================================================================================ Atmora & Akavir

    /** Frost giant of Atmora: hulking, ice-blue skin, frosted beard, fur and bone armour, ice-crusted club. */
    static CreatureDef frostGiant() {
        Biped b = new Biped("frost_giant");
        b.legLen = 13;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 4;
        b.torsoW = 12;
        b.torsoH = 13;
        b.torsoD = 7;
        b.hunch = 10;
        b.armLen = 17;
        b.armW = 5;
        b.handSize = 4;
        b.headW = 7;
        b.headH = 8;
        b.headD = 7;
        b.headForward = 1;
        b.skin = "skin";
        b.hipMat = "leather";
        b.footMat = "fur";
        b.scale = 2.2f;
        b.stride = 22;
        b.stomp = true;
        b.build();
        Rig r = b.rig;
        // face: brow, nose, frosted beard and hair
        b.head.box("skin", -3.5f, -6, -4.7f, 7, 1, 1).tag("brow");
        b.head.box("skin", -1, -5, -5.2f, 2, 2, 1).tag("nose");
        b.head.box("beard", -3.5f, -3, -4.8f, 7, 6, 2).tag("beard");
        b.head.box("beard", -2.5f, 3, -4.5f, 5, 3, 2).tag("beard_tip");
        b.head.box("hair", -4, -9, -4.4f, 8, 2, 8).tag("hair_top");
        b.head.box("hair", -4, -7.5f, 2.2f, 8, 10, 2).tag("hair_back");
        // fur mantle, bone rib-plate, belt with a skull buckle, fur kilt
        b.torso.box("fur", -7, -14, -4.5f, 14, 4, 9, 0.2f).tag("mantle");
        b.torso.box("bone", -3.5f, -10, -4.1f, 7, 5, 1).tag("ribs");
        Bone strap = b.torso.child("strap", 0, -6.5f, -3.9f).rot(0, 0, 38);
        strap.box("leather", -1, -9.5f, -0.5f, 2, 19, 1).tag("strap");
        b.hips.box("fur", -5.5f, -2, -4, 11, 7, 8, 0.1f).tag("kilt");
        b.hips.box("leather", -5.5f, -4.5f, -4, 11, 2, 8, 0.2f).tag("belt");
        b.hips.box("bone", -1.5f, -5, -4.8f, 3, 3, 1).tag("skull");
        // bone pauldrons with horn spikes, leather bracers, fur boots
        both(r, "arm", a -> a.sbox("bone", -3.5f, -3.5f, -3, 7, 4, 6).tag("pauldron"));
        for (String s : new String[]{"_right", "_left"}) {
            spike(r.bone("arm" + s), "pauldron_spike", "horn", -1.5f, -3, 0, 0, 0, -30, 1, 3, 1);
        }
        both(r, "forearm", a -> a.sbox("leather", -2.5f, 3, -2.5f, 5, 4, 5).tag("bracer"));
        both(r, "shin", s -> s.sbox("fur", -2.5f, 0.5f, -2.5f, 5, 4, 5, 0.2f).tag("boot"));
        // the ice-encrusted club, held in the right hand
        // held low and forward, head angled down towards the ground
        Bone w = b.hands[0].child("weapon", 0, 2, 0).rot(-148, 0, 0);
        w.box("wood", -1.5f, -1.5f, -3, 3, 3, 16).tag("shaft");
        w.box("ice", -2.5f, -2.5f, 9, 5, 5, 9).tag("ice");
        w.box("ice", -3.5f, -1, 11, 7, 2, 2).tag("crystal");
        w.box("ice", -1, -3.5f, 14, 2, 7, 2).tag("crystal");
        w.box("ice", -1, -1, 17.5f, 2, 2, 3).tag("crystal");

        CreatureDef d = b.def();
        // club swing: raised high behind, brought down hard (hit ~0.42 s)
        Anim swing = Anim.once(1.0f);
        swing.rot("arm_right", 0, 0, 0, 0, 0.3f, -165, 15, 15, 0.42f, -45, -10, -5, 0.6f, -35, -5, 0, 1.0f, 0, 0, 0);
        swing.rot("forearm_right", 0, 0, 0, 0, 0.3f, -35, 0, 0, 0.42f, -5, 0, 0, 1.0f, 0, 0, 0);
        swing.rot("torso", 0, 0, 0, 0, 0.3f, -12, 20, 0, 0.42f, 18, -15, 0, 0.6f, 15, -10, 0, 1.0f, 0, 0, 0);
        swing.rot("arm_left", 0, 0, 0, 0, 0.3f, -25, 0, -20, 0.42f, 10, 0, -10, 1.0f, 0, 0, 0);
        swing.rot("leg_left", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.6f, -15, 0, 0, 1.0f, 0, 0, 0);
        swing.pos("hips", 0, 0, 0, 0, 0.42f, 0, -1.5f, 0, 0.6f, 0, -1.5f, 0, 1.0f, 0, 0, 0);
        d.anim("attack", swing);
        // throws an ice boulder: both hands overhead, leaning back, then a forward heave (release ~0.65 s)
        Anim heave = Anim.once(1.3f);
        heave.rot("arm_right", 0, 0, 0, 0, 0.45f, -175, 0, -12, 0.65f, -70, 0, -8, 0.85f, -55, 0, 0, 1.3f, 0, 0, 0);
        heave.rot("arm_left", 0, 0, 0, 0, 0.45f, -175, 0, 12, 0.65f, -70, 0, 8, 0.85f, -55, 0, 0, 1.3f, 0, 0, 0);
        heave.rot("forearm_right", 0, 0, 0, 0, 0.45f, -40, 0, 0, 0.65f, 0, 0, 0, 1.3f, 0, 0, 0);
        heave.rot("forearm_left", 0, 0, 0, 0, 0.45f, -40, 0, 0, 0.65f, 0, 0, 0, 1.3f, 0, 0, 0);
        heave.rot("torso", 0, 0, 0, 0, 0.45f, -18, 0, 0, 0.65f, 22, 0, 0, 0.85f, 18, 0, 0, 1.3f, 0, 0, 0);
        heave.rot("head", 0, 0, 0, 0, 0.45f, -15, 0, 0, 0.65f, 5, 0, 0, 1.3f, 0, 0, 0);
        heave.rot("leg_right", 0, 0, 0, 0, 0.5f, -20, 0, 0, 0.85f, -20, 0, 0, 1.3f, 0, 0, 0);
        d.anim("cast", heave);
        return only(d, "idle", "walk", "run", "attack", "attack3", "special", "cast", "death", "hurt");
    }

    /** Kamal: Akaviri snow demon. Tall and gaunt, curled horns, a fanged underbite, long clawed arms, digitigrade legs. */
    static CreatureDef kamal() {
        Biped b = new Biped("kamal");
        b.legLen = 15;
        b.legW = 4;
        b.hipW = 7;
        b.hipH = 3;
        b.torsoW = 9;
        b.torsoH = 11;
        b.torsoD = 5;
        b.hunch = 18;
        b.armLen = 20;
        b.armW = 3;
        b.handSize = 3;
        b.headW = 7;
        b.headH = 6;
        b.headD = 6;
        b.headForward = 2;
        b.jaw = true;
        b.jawH = 2;
        b.digitigrade = true;
        b.skin = "skin";
        b.hipMat = "rag";
        b.footMat = "skin";
        b.scale = 1f;
        b.stride = 28;
        b.build();
        Rig r = b.rig;
        // heavy brow, short ape-like muzzle and an underbite with upturned fangs
        b.head.box("skin", -3.5f, -5.2f, -4.3f, 7, 1, 1).tag("brow");
        b.head.box("skin", -2.5f, -3, -5, 5, 2, 2).tag("muzzle");
        b.jawBone.box("tooth", -2.3f, -1.8f, -4.4f, 1, 2, 1).tag("fang");
        b.jawBone.box("tooth", 1.3f, -1.8f, -4.4f, 1, 2, 1).tag("fang");
        b.jawBone.box("tooth", -0.5f, -0.8f, -4.4f, 1, 1, 1).tag("tooth");
        // curled horns: up, back, then hooking forward
        b.head.pair("horn", 2.2f, -5.5f, -0.5f, (h, side) -> {
            h.rot(-15, 0, -40);
            h.sbox("horn", -1.5f, -4, -1.5f, 3, 4, 3).tag("horn");
            Bone mid = h.child("horn_mid", 0, -3.5f, 0).rot(-50, 0, 0);
            mid.sbox("horn", -1, -4, -1, 2, 4, 2).tag("horn");
            Bone tip = mid.child("horn_tip", 0, -3.5f, 0).rot(-55, 0, 0);
            tip.sbox("horn", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn_tip");
        });
        // ragged fur: shoulder ruff, spine strip, arm fur, loin rags
        b.torso.box("fur", -5.5f, -11.2f, -3.5f, 11, 4, 7, 0.3f).tag("ruff");
        b.torso.box("fur", -2, -9, 1.8f, 4, 9, 2, 0.2f).tag("spine_fur");
        b.hips.box("rag", -4, -1, -3, 8, 6, 6, 0.1f).tag("rags");
        both(r, "forearm", a -> a.sbox("fur", -2, 0.5f, -2, 4, 4, 4, 0.2f).tag("arm_fur"));
        both(r, "leg", l -> l.sbox("fur", -2.5f, -1, -2.5f, 5, 5, 5, 0.2f).tag("thigh_fur"));
        // long claws on hands and feet
        both(r, "hand", hd -> {
            hd.sbox("claw", -1.6f, 2.3f, -1.4f, 1, 3, 1).tag("claw");
            hd.sbox("claw", -0.5f, 2.3f, -1.4f, 1, 3, 1).tag("claw");
            hd.sbox("claw", 0.6f, 2.3f, -1.4f, 1, 3, 1).tag("claw");
        });
        both(r, "foot", f -> {
            f.sbox("claw", -2, 0.5f, -5.5f, 1, 1, 2).tag("claw");
            f.sbox("claw", -0.5f, 0.5f, -5.5f, 1, 1, 2).tag("claw");
            f.sbox("claw", 1, 0.5f, -5.5f, 1, 1, 2).tag("claw");
        });

        CreatureDef d = b.def();
        // frost breath: leans in, head forward, maw wide, held for two seconds
        Anim breath = Anim.once(2.5f);
        breath.rot("torso", 0, 0, 0, 0, 0.3f, 14, 0, 0, 2.1f, 14, 0, 0, 2.5f, 0, 0, 0);
        breath.rot("head", 0, 0, 0, 0, 0.3f, -6, 0, 0, 0.9f, -4, 10, 0, 1.5f, -4, -10, 0, 2.1f, -6, 0, 0, 2.5f, 0, 0, 0);
        breath.pos("head", 0, 0, 0, 0, 0.3f, 0, 0, -1.5f, 2.1f, 0, 0, -1.5f, 2.5f, 0, 0, 0);
        breath.rot("jaw", 0, 0, 0, 0, 0.3f, 42, 0, 0, 2.1f, 42, 0, 0, 2.5f, 0, 0, 0);
        breath.rot("arm_right", 0, 0, 0, 0, 0.3f, 25, 0, 25, 2.1f, 25, 0, 25, 2.5f, 0, 0, 0);
        breath.rot("arm_left", 0, 0, 0, 0, 0.3f, 25, 0, -25, 2.1f, 25, 0, -25, 2.5f, 0, 0, 0);
        breath.rot("forearm_right", 0, 0, 0, 0, 0.3f, -40, 0, 0, 2.1f, -40, 0, 0, 2.5f, 0, 0, 0);
        breath.rot("forearm_left", 0, 0, 0, 0, 0.3f, -40, 0, 0, 2.1f, -40, 0, 0, 2.5f, 0, 0, 0);
        breath.rot("leg_right", 0, 0, 0, 0, 0.3f, -12, 0, 0, 2.1f, -12, 0, 0, 2.5f, 0, 0, 0);
        d.anim("special", breath);
        return only(d, "idle", "walk", "run", "attack", "attack2", "special", "cast", "death", "hurt");
    }

    /**
     * Tsaesci: Akaviri snake-folk warrior. Golden-scaled serpent body rising into a scaled humanoid torso in lacquered
     * plates, a hooded snake head, and a curved sword in the right hand.
     */
    static CreatureDef tsaesci() {
        Rig r = new Rig("tsaesci");
        // upright serpent column, pivoting at the ground so it can lean and sway
        Bone body = r.root().child("body", 0, -2, 0);
        body.box("scales", -3.5f, -13, -3, 7, 15, 6).tag("column");
        body.box("belly", -2.5f, -12, -3.6f, 5, 13, 1).tag("belly_plates");
        // the bend where the upright column turns back into the trailing tail
        Bone coil = body.child("coil", 0, -5.5f, 1.5f).rot(-40, 0, 0);
        coil.box("scales", -3, -2, -1, 6, 5, 7).tag("coil");
        // humanoid torso in lacquered armour, leaning slightly forward
        Bone torso = body.child("torso", 0, -13, 0).rot(6, 0, 0);
        torso.box("scales", -4, -11, -2.5f, 8, 11, 5).tag("torso");
        torso.box("lacquer", -4.5f, -10.5f, -3, 9, 6, 6).tag("cuirass");
        torso.box("cloth", -4.5f, -4.5f, -3, 9, 2, 6, 0.1f).tag("sash");
        torso.box("lacquer", -4.5f, -2.5f, -3.3f, 4, 4, 1).tag("tasset");
        torso.box("lacquer", 0.5f, -2.5f, -3.3f, 4, 4, 1).tag("tasset");
        // hooded snake head
        Bone head = torso.child("head", 0, -11, 0).rot(-6, 0, 0);
        head.box("scales", -1.5f, -3, -1.5f, 3, 3, 3).tag("neck");
        head.box("scales", -2.5f, -7.5f, -3, 5, 5, 6).tag("head");
        head.box("scales", -2, -6.5f, -6, 4, 3, 3).tag("snout");
        Bone jaw = head.child("jaw", 0, -3.5f, -2);
        jaw.box("scales", -2, 0, -4, 4, 1, 5).tag("jaw");
        Bone hood = head.child("hood", 0, -5, 1.5f);
        hood.box("hood", -5, -4, 0, 10, 8, 1).tag("hood");
        // arms
        torso.pair("arm", 5, -9.5f, 0, (b, side) -> {
            b.rot(0, 0, 6);
            b.sbox("scales", -1.5f, -1.5f, -1.5f, 3, 7, 3).tag("arm");
            b.sbox("lacquer", -2.2f, -2.2f, -2, 4, 3, 4, 0.2f).tag("pauldron");
            Bone fore = b.child("forearm", 0, 5.5f, 0);
            fore.sbox("scales", -1, -0.5f, -1, 2, 6, 2).tag("forearm");
            fore.sbox("lacquer", -1.5f, 1.5f, -1.5f, 3, 3, 3).tag("bracer");
            Bone hand = fore.child("hand", 0, 5.5f, 0);
            hand.sbox("scales", -1, 0, -1, 2, 2, 2).tag("hand");
        });
        // curved Akaviri sword held in a low forward guard
        Bone w = r.bone("hand_right").child("weapon", 0, 1, 0).rot(30, 0, 0);
        w.box("cloth", -0.5f, -0.5f, -1, 1, 1, 4).tag("grip");
        w.box("metal", -1, -1, -2, 2, 2, 1).tag("guard");
        w.box("blade", -0.5f, -1, -9, 1, 2, 7).tag("blade");
        Bone wt = w.child("weapon_tip", 0, 0, -8.5f).rot(-14, 0, 0);
        wt.box("blade", -0.5f, -1, -6, 1, 2, 6).tag("blade");
        wt.box("blade", -0.5f, -0.5f, -8, 1, 1, 2).tag("blade_tip");
        // serpent tail trailing behind in an S, its underside on the ground
        int[] tw = {6, 6, 5, 5, 4, 3, 2};
        int[] th = {5, 5, 4, 4, 3, 3, 2};
        float[] yaw = {20, -30, -25, 20, 30, 20, -15};
        int len = 6;
        Bone[] tail = new Bone[tw.length];
        Bone parent = body;
        for (int i = 0; i < tw.length; i++) {
            Bone t = i == 0 ? parent.child("tail1", 0, -0.5f, 1.5f)
                    : parent.child("tail" + (i + 1), 0, (th[i - 1] - th[i]) / 2f, len - 0.5f);
            t.rot(0, yaw[i], 0);
            t.box("scales", -tw[i] / 2f, -th[i] / 2f, 0, tw[i], th[i], len + (i == 0 ? 1 : 0)).tag("tail");
            tail[i] = t;
            parent = t;
        }

        CreatureDef d = new CreatureDef(r);
        d.gait(0.6f, 0.4f, 0.6f);
        d.look("head", 0.7f).look("torso", 0.3f);
        // idle: slow sway of the column, tail tip flicking, hood breathing
        Anim idle = Anim.loop(3.5f);
        idle.swing("body", 2, 2f, 0f);
        idle.swing("body", 0, 1.5f, 0.25f);
        idle.swing("torso", 2, -1.5f, 0f);
        idle.swing("head", 1, 10f, 0.2f);
        idle.swing("arm_right", 0, 3f, 0.3f);
        idle.swing("arm_left", 0, 3f, 0.8f);
        idle.scale("hood", 0, 1, 1, 1, 1.75f, 1.06f, 1.03f, 1, 3.5f, 1, 1, 1);
        for (int i = 0; i < tail.length; i++) idle.wave(tail[i].name, Anim.Target.ROTATION, 1, 3f + i, -i * 0.12f, 0, 1);
        d.anim("idle", idle);
        d.anim("walk", slither(tail, 1.2f, 12f, 0f));
        d.anim("run", slither(tail, 0.7f, 20f, 14f));
        // sword slash: raised high, diagonal cut down across the body (hit ~0.4 s)
        Anim slash = Anim.once(0.75f);
        slash.rot("arm_right", 0, 0, 0, 0, 0.25f, -150, 20, 35, 0.4f, -45, -35, -15, 0.55f, -30, -30, -10, 0.75f, 0, 0, 0);
        slash.rot("forearm_right", 0, 0, 0, 0, 0.25f, -30, 0, 0, 0.4f, -5, 0, 0, 0.75f, 0, 0, 0);
        slash.rot("torso", 0, 0, 0, 0, 0.25f, -5, 25, 0, 0.4f, 8, -30, 0, 0.75f, 0, 0, 0);
        slash.rot("body", 0, 0, 0, 0, 0.25f, -4, 0, 0, 0.4f, 10, 0, 0, 0.75f, 0, 0, 0);
        slash.rot("arm_left", 0, 0, 0, 0, 0.25f, -20, 0, -25, 0.4f, 10, 0, -15, 0.75f, 0, 0, 0);
        d.anim("attack", slash);
        // lunge bite: the column whips forward, hood flared, jaws snap (hit ~0.36 s)
        Anim bite = Anim.once(0.7f);
        bite.rot("body", 0, 0, 0, 0, 0.18f, -10, 0, 0, 0.34f, 28, 0, 0, 0.7f, 0, 0, 0);
        bite.pos("body", 0, 0, 0, 0, 0.18f, 0, 0, 1.5f, 0.34f, 0, 0, -3, 0.7f, 0, 0, 0);
        bite.rot("torso", 0, 0, 0, 0, 0.34f, 10, 0, 0, 0.7f, 0, 0, 0);
        bite.rot("head", 0, 0, 0, 0, 0.18f, -15, 0, 0, 0.34f, 5, 0, 0, 0.7f, 0, 0, 0);
        bite.linear().rot("jaw", 0, 0, 0, 0, 0.15f, 50, 0, 0, 0.3f, 50, 0, 0, 0.36f, 0, 0, 0, 0.7f, 0, 0, 0).smooth();
        bite.scale("hood", 0, 1, 1, 1, 0.2f, 1.3f, 1.2f, 1, 0.5f, 1.3f, 1.2f, 1, 0.7f, 1, 1, 1);
        bite.rot("arm_right", 0, 0, 0, 0, 0.34f, 20, 0, 20, 0.7f, 0, 0, 0);
        bite.rot("arm_left", 0, 0, 0, 0, 0.34f, 20, 0, -20, 0.7f, 0, 0, 0);
        d.anim("attack2", bite);
        // cast: the free left hand thrust forward
        Anim cast = Anim.once(1.0f);
        cast.rot("arm_left", 0, 0, 0, 0, 0.3f, -130, 0, -15, 0.45f, -90, 0, 0, 0.7f, -85, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("forearm_left", 0, 0, 0, 0, 0.3f, -40, 0, 0, 0.45f, 0, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.3f, -6, -20, 0, 0.45f, 6, 15, 0, 1.0f, 0, 0, 0);
        cast.rot("arm_right", 0, 0, 0, 0, 0.3f, 15, 0, 15, 1.0f, 0, 0, 0);
        cast.scale("hood", 0, 1, 1, 1, 0.45f, 1.15f, 1.1f, 1, 1.0f, 1, 1, 1);
        d.anim("cast", cast);
        // special: rears up tall, hood fully flared, hissing with the sword raised
        Anim hiss = Anim.once(1.6f);
        hiss.pos("body", 0, 0, 0, 0, 0.35f, 0, 3, 0, 1.2f, 0, 3, 0, 1.6f, 0, 0, 0);
        hiss.rot("body", 0, 0, 0, 0, 0.35f, -10, 0, 0, 1.2f, -10, 0, 0, 1.6f, 0, 0, 0);
        hiss.rot("head", 0, 0, 0, 0, 0.35f, -10, 0, 0, 0.8f, -12, 8, 0, 1.2f, -10, -6, 0, 1.6f, 0, 0, 0);
        hiss.rot("jaw", 0, 0, 0, 0, 0.35f, 40, 0, 0, 1.2f, 40, 0, 0, 1.6f, 0, 0, 0);
        hiss.scale("hood", 0, 1, 1, 1, 0.35f, 1.4f, 1.25f, 1, 1.2f, 1.4f, 1.25f, 1, 1.6f, 1, 1, 1);
        hiss.rot("arm_right", 0, 0, 0, 0, 0.35f, -100, 0, 40, 1.2f, -100, 0, 40, 1.6f, 0, 0, 0);
        hiss.rot("arm_left", 0, 0, 0, 0, 0.35f, -20, 0, -45, 1.2f, -20, 0, -45, 1.6f, 0, 0, 0);
        for (int i = 0; i < tail.length; i++) hiss.rot(tail[i].name, 0, 0, 0, 0, 0.35f, 0, (i % 2 == 0 ? 10 : -10), 0, 1.2f, 0, (i % 2 == 0 ? 10 : -10), 0, 1.6f, 0, 0, 0);
        d.anim("special", hiss);
        // death: the column topples forward, arms slack, tail goes limp
        Anim death = Anim.once(1.0f);
        death.rot("body", 0, 0, 0, 0, 0.7f, 50, 0, 8, 1f, 55, 0, 8);
        death.rot("torso", 0, 0, 0, 0, 0.7f, 20, 0, 0, 1f, 25, 0, 0);
        death.rot("head", 0, 0, 0, 0, 0.7f, 25, 20, 0, 1f, 25, 20, 0);
        death.rot("jaw", 0, 0, 0, 0, 0.7f, 25, 0, 0, 1f, 25, 0, 0);
        death.rot("arm_right", 0, 0, 0, 0, 0.7f, -30, 0, 20, 1f, -30, 0, 20);
        death.rot("arm_left", 0, 0, 0, 0, 0.7f, -30, 0, -20, 1f, -30, 0, -20);
        // the tail counter-rotates so it stays on the ground while the column topples
        for (int i = 0; i < tail.length; i++) {
            float p = i == 0 ? -55 : 0;
            death.rot(tail[i].name, 0, 0, 0, 0, 0.7f, p * 50 / 55f, -yaw[i] * 0.5f, 0, 1f, p, -yaw[i] * 0.5f, 0);
        }
        d.anim("death", death);
        Anim hurt = Anim.once(0.45f);
        hurt.rot("body", 0, 0, 0, 0, 0.1f, -8, 0, 4, 0.45f, 0, 0, 0);
        hurt.rot("torso", 0, 0, 0, 0, 0.1f, -8, 0, 0, 0.45f, 0, 0, 0);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.45f, 0, 0, 0);
        d.anim("hurt", hurt);
        return d;
    }

    /** Travelling S-wave down the tail with the column swaying against it. */
    private static Anim slither(Bone[] tail, float len, float amp, float lean) {
        Anim a = Anim.loop(len);
        for (int i = 0; i < tail.length; i++) {
            a.wave(tail[i].name, Anim.Target.ROTATION, 1, amp * (0.6f + i * 0.15f), -i * 0.14f, 0, 1);
        }
        a.swing("body", 1, amp * 0.4f, 0.1f);
        a.swing("body", 2, amp * 0.25f, 0.1f);
        if (lean != 0) a.hold("torso", lean, 0, 0);
        a.swing("torso", 1, -amp * 0.3f, 0.1f);
        a.swing("head", 1, -amp * 0.2f, 0.1f);
        a.swing("arm_right", 0, amp, 0.5f);
        a.swing("arm_left", 0, amp, 0f);
        a.bob("body", 1, 0.4f, 0f, 2);
        return a;
    }

    // ================================================================================================ the endless lands
    // Textures of the realm creatures are pale grey: the game tints them per region with a colour multiplier.

    /** Realm stalker: lean, quill-backed predator with a cluster of eyes and a long whip tail ending in a barb. */
    static CreatureDef realmStalker() {
        Quad q = new Quad("realm_stalker");
        q.bodyW = 6;
        q.bodyH = 6;
        q.bodyL = 15;
        q.chestW = 7;
        q.chestH = 7;
        q.chestL = 6;
        q.legLen = 10;
        q.legW = 3;
        q.foot = "paw";
        q.footMat = "claw";
        q.neckSegs = 1;
        q.neckLen = 4;
        q.neckW = 4;
        q.neckH = 4;
        q.neckPitch = -12;
        q.headW = 5;
        q.headH = 4;
        q.headL = 4;
        q.snoutW = 4;
        q.snoutH = 2;
        q.snoutL = 4;
        q.jaw = true;
        q.earH = 0;
        q.tailSegs = 6;
        q.tailLen = 27;
        q.tailW = 2;
        q.tailDroop = 10;
        q.fur = "hide";
        q.belly = "belly";
        q.legMat = "leg";
        q.tailMat = "hide";
        q.headMat = "hide";
        q.snoutMat = "hide";
        q.scale = 1f;
        q.stride = 30;
        q.runStride = 48;
        q.build();
        // whip tail arcing up behind (positive pitch raises a backward chain), a bladed barb at the tip
        q.tail[0].rot(-2, 0, 0);
        for (int i = 1; i < q.tail.length; i++) q.tail[i].rot(13, 0, 0);
        Bone barb = q.tail[q.tail.length - 1].child("barb", 0, 0, 4);
        barb.box("spine", -1.5f, -0.5f, 0, 3, 1, 3).tag("barb");
        barb.box("spine", -0.5f, -1.5f, 1, 1, 3, 3).tag("barb");
        // swept horns and quills along the back
        q.head.pair("horn", 1.6f, -q.headH / 2f + 0.5f, -1, (b, side) -> {
            b.rot(-65, 0, -12);
            b.sbox("spine", -0.5f, -5, -0.5f, 1, 5, 1).tag("horn");
        });
        String[] qn = {"quill_a", "quill_b", "quill_c", "quill_d"};
        float[] qz = {-6, -3, 0, 3};
        int[] qh = {5, 6, 5, 4};
        for (int i = 0; i < qn.length; i++) {
            final int h = qh[i];
            q.body.pair(qn[i], 1.3f, -q.bodyH / 2f + 0.5f, qz[i], (b, side) -> {
                b.rot(-50, 0, -18);
                b.sbox("spine", -0.5f, -h, -0.5f, 1, h, 1).tag("quill");
            });
        }
        spike(q.body, "quill_mid", "spine", 0, -q.bodyH / 2f + 0.5f, 5.5f, -55, 0, 0, 1, 3, 1);
        // fangs
        q.snout.box("tooth", -1.5f, q.headH / 2f - q.snoutH - 1 + q.snoutH, -q.snoutL + 0.5f, 3, 1, 1).tag("fangs");

        CreatureDef d = q.def();
        // attack2: the whip tail lashes forward over the back (hit ~0.4 s)
        Anim lash = Anim.once(0.8f);
        lash.rot("body", 0, 0, 0, 0, 0.25f, 6, 0, 0, 0.4f, 8, 0, 0, 0.8f, 0, 0, 0);
        lash.rot("tail1", 0, 0, 0, 0, 0.25f, 30, 0, 0, 0.4f, 55, 0, 0, 0.8f, 0, 0, 0);
        for (int i = 1; i < q.tail.length; i++) {
            lash.rot(q.tail[i].name, 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.4f, 24, 0, 0, 0.55f, 16, 0, 0, 0.8f, 0, 0, 0);
        }
        lash.rot("head", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.8f, 0, 0, 0);
        d.anim("attack2", lash);
        // special: threat display, quills raised and rattling, crouched, jaws open, tail high
        Anim threat = Anim.once(1.5f);
        threat.pos("body", 0, 0, 0, 0, 0.3f, 0, -1.5f, 0, 1.2f, 0, -1.5f, 0, 1.5f, 0, 0, 0);
        threat.rot("body", 0, 0, 0, 0, 0.3f, 7, 0, 0, 1.2f, 7, 0, 0, 1.5f, 0, 0, 0);
        threat.rot("head", 0, 0, 0, 0, 0.3f, -5, 0, 0, 1.2f, -5, 0, 0, 1.5f, 0, 0, 0);
        threat.rot("jaw", 0, 0, 0, 0, 0.3f, 40, 0, 0, 1.2f, 40, 0, 0, 1.5f, 0, 0, 0);
        for (int i = 0; i < qn.length; i++) {
            threat.rot(qn[i] + "_right", 0, 0, 0, 0, 0.3f, 30, 0, -12, 0.5f, 25, 0, -8, 0.7f, 32, 0, -14, 0.9f, 25, 0, -8, 1.2f, 30, 0, -12, 1.5f, 0, 0, 0);
            threat.rot(qn[i] + "_left", 0, 0, 0, 0, 0.3f, 30, 0, 12, 0.5f, 25, 0, 8, 0.7f, 32, 0, 14, 0.9f, 25, 0, 8, 1.2f, 30, 0, 12, 1.5f, 0, 0, 0);
        }
        threat.rot("tail1", 0, 0, 0, 0, 0.3f, 25, 0, 0, 1.2f, 25, 0, 0, 1.5f, 0, 0, 0);
        for (int i = 1; i < q.tail.length; i++) threat.rot(q.tail[i].name, 0, 0, 0, 0, 0.3f, 8, 6, 0, 0.6f, 8, -6, 0, 0.9f, 8, 6, 0, 1.2f, 8, 0, 0, 1.5f, 0, 0, 0);
        d.anim("special", threat);
        // asleep, the whip tail lies flat and curls round beside the body
        Anim sleep = q.sleep();
        sleep.channels.removeIf(c -> c.bone.startsWith("tail"));
        sleep.hold("tail1", -12, 35, 0);
        for (int i = 1; i < q.tail.length; i++) sleep.hold(q.tail[i].name, -13, 24, 0);
        d.anim("sleep", sleep);
        return only(d, "idle", "walk", "run", "attack", "attack2", "special", "sleep", "death", "hurt");
    }

    /** Realm grazer: placid, bulky long-necked grazer with curled ram horns and a shaggy back. */
    static CreatureDef realmGrazer() {
        Quad q = new Quad("realm_grazer");
        q.bodyW = 11;
        q.bodyH = 10;
        q.bodyL = 18;
        q.chestW = 12;
        q.chestH = 12;
        q.chestL = 8;
        q.legLen = 11;
        q.legW = 4;
        q.foot = "hoof";
        q.footMat = "hoof";
        q.neckSegs = 2;
        q.neckLen = 6;
        q.neckW = 5;
        q.neckH = 6;
        q.neckPitch = -42;
        q.headW = 6;
        q.headH = 5;
        q.headL = 6;
        q.snoutW = 4;
        q.snoutH = 3;
        q.snoutL = 3;
        q.jaw = true;
        q.earH = 2;
        q.earW = 2;
        q.tailSegs = 2;
        q.tailLen = 6;
        q.tailW = 2;
        q.tailDroop = 60;
        q.fur = "hide";
        q.belly = "belly";
        q.headMat = "face";
        q.legMat = "leg";
        q.neckMat = "hide";
        q.tailMat = "shag";
        q.scale = 1f;
        q.stride = 22;
        q.runStride = 34;
        q.gallop = false;
        q.butt = true;
        q.build();
        // shaggy saddle over the back with fringes down the flanks, a shoulder hump, mane along the neck
        q.chest.box("shag", -5.5f, -8.8f, -4, 11, 4, 8, 0.3f).tag("hump");
        q.body.box("shag", -q.bodyW / 2f - 0.5f, -q.bodyH / 2f - 1.5f, -q.bodyL / 2f + 0.5f, q.bodyW + 1, 4, q.bodyL - 1, 0.2f).tag("saddle");
        q.body.box("shag", -q.bodyW / 2f - 1, -q.bodyH / 2f + 1, -q.bodyL / 2f + 2, 1, 7, q.bodyL - 4, 0.2f).tag("fringe");
        q.body.box("shag", q.bodyW / 2f, -q.bodyH / 2f + 1, -q.bodyL / 2f + 2, 1, 7, q.bodyL - 4, 0.2f).tag("fringe");
        for (Bone n : q.neck) n.box("shag", -q.neckW / 2f - 0.5f, -q.neckH / 2f - 1.5f, -q.neckLen, q.neckW + 1, 2, q.neckLen + 1, 0.1f).tag("mane");
        // curled ram horns: back, down, then forward round the ears
        q.head.pair("horn", 2.2f, -q.headH / 2f + 0.5f, -2, (b, side) -> {
            b.rot(30, -35, 0);
            b.sbox("horn", -1.5f, -1.5f, 0, 3, 3, 5).tag("horn");
            Bone mid = b.child("horn_mid", 0, 0, 4.5f).rot(-75, 0, 0);
            mid.sbox("horn", -1.5f, -1.5f, 0, 3, 3, 4).tag("horn");
            Bone mid2 = mid.child("horn_curl", 0, 0, 3.5f).rot(-70, 0, 0);
            mid2.sbox("horn", -1, -1, 0, 2, 2, 4).tag("horn");
            Bone tip = mid2.child("horn_tip", 0, 0, 3.5f).rot(-60, 0, 0);
            tip.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 3).tag("horn_tip");
        });

        CreatureDef d = q.def();
        d.anim("sleep", tuckedSleep(q, q.legLen - 0.5f, 30));
        return only(d, "idle", "walk", "run", "attack", "attack2", "sleep", "death", "hurt");
    }

    /** Realm wyrm: a legless, crested serpent with fan frills behind the head and frilled dorsal ridge. */
    static CreatureDef realmWyrm() {
        Serpent s = new Serpent("realm_wyrm");
        s.headW = 6;
        s.headH = 5;
        s.headL = 8;
        s.jawH = 2;
        s.segs = 8;
        s.segLen = 5;
        s.segW = 6;
        s.segH = 5;
        s.taper = 0.3f;
        s.height = 10;
        s.fins = false;
        s.tailFin = true;
        s.lateral = true;
        s.skin = "scales";
        s.belly = "belly";
        s.headMat = "scales";
        s.finMat = "frill";
        s.scale = 1.2f;
        s.build();
        final float[] rest = {-35, -15, 50};
        for (int i = 0; i < rest.length; i++) s.seg[i].rot(rest[i], 0, 0);
        // the tail tip curls up off the ground
        s.seg[s.segs - 2].rot(10, 0, 0);
        s.seg[s.segs - 1].rot(14, 0, 0);
        // fan frills behind the jaws, swept back at rest
        s.head.pair("frill", s.headW / 2f - 0.5f, -0.5f, -1.5f, (b, side) -> {
            b.rot(0, 40, 0);
            b.sbox("frill", -6, -5, 0, 6, 8, 0).tag("frill");
        });
        // crest and brow horns
        s.head.box("frill", 0, -s.headH / 2f - 4, -6, 0, 4, 7).tag("crest");
        s.head.pair("horn", 2, -2.5f, -2, (b, side) -> {
            b.rot(30, -15, 0);
            b.sbox("horn", -0.5f, -0.5f, 0, 1, 1, 4).tag("horn");
        });
        // frilled dorsal ridge
        for (int i = 0; i < s.segs - 1; i++) {
            int h = segH(s, i);
            s.seg[i].box("frill", 0, -h / 2f - 3, 0.5f, 0, 3, s.segLen - 1).tag("dorsal");
        }

        CreatureDef d = new CreatureDef(s.rig);
        d.gait(0.5f, 0.35f, 0.6f);
        d.look("head", 0.7f).look("seg1", -0.3f);
        Anim idle = s.travel(3f, 5f);
        idle.swingN("frill_right", 1, 5f, 0f, 2);
        idle.swingN("frill_left", 1, -5f, 0f, 2);
        d.anim("idle", idle);
        d.anim("walk", s.travel(1.0f, 15f));
        Anim strike = s.strike();
        strike.rot("frill_right", 0, 0, 0, 0, 0.3f, 0, -45, 0, 0.6f, 0, -45, 0, 0.8f, 0, 0, 0);
        strike.rot("frill_left", 0, 0, 0, 0, 0.3f, 0, 45, 0, 0.6f, 0, 45, 0, 0.8f, 0, 0, 0);
        d.anim("attack", strike);
        d.anim("attack2", s.bite());
        // special: rears up, frills snap open into a wide fan, hissing
        Anim flare = Anim.once(1.6f);
        flare.pos("head", 0, 0, 0, 0, 0.4f, 0, 5, 1, 1.2f, 0, 5, 1, 1.6f, 0, 0, 0);
        flare.rot("head", 0, 0, 0, 0, 0.4f, -15, 0, 0, 1.2f, -15, 0, 0, 1.6f, 0, 0, 0);
        flare.rot("seg1", 0, 0, 0, 0, 0.4f, -20, 0, 0, 1.2f, -20, 0, 0, 1.6f, 0, 0, 0);
        flare.rot("seg3", 0, 0, 0, 0, 0.4f, 20, 0, 0, 1.2f, 20, 0, 0, 1.6f, 0, 0, 0);
        flare.linear().rot("jaw", 0, 0, 0, 0, 0.4f, 40, 0, 0, 1.2f, 40, 0, 0, 1.45f, 0, 0, 0, 1.6f, 0, 0, 0).smooth();
        flare.rot("frill_right", 0, 0, 0, 0, 0.3f, 0, -55, 0, 1.2f, 0, -55, 0, 1.6f, 0, 0, 0);
        flare.rot("frill_left", 0, 0, 0, 0, 0.3f, 0, 55, 0, 1.2f, 0, 55, 0, 1.6f, 0, 0, 0);
        flare.scale("frill_right", 0, 1, 1, 1, 0.3f, 1.3f, 1.3f, 1, 1.2f, 1.3f, 1.3f, 1, 1.6f, 1, 1, 1);
        flare.scale("frill_left", 0, 1, 1, 1, 0.3f, 1.3f, 1.3f, 1, 1.2f, 1.3f, 1.3f, 1, 1.6f, 1, 1, 1);
        d.anim("special", flare);
        d.anim("death", serpentDeath(s, rest));
        d.anim("hurt", s.hurt());
        return d;
    }
}
