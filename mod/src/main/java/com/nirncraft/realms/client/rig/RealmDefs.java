package com.nirncraft.realms.client.rig;

import com.nirncraft.creatures.client.rig.Anim;
import com.nirncraft.creatures.client.rig.Arachnid;
import com.nirncraft.creatures.client.rig.Biped;
import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.client.rig.Quad;
import com.nirncraft.creatures.client.rig.Rig;
import com.nirncraft.creatures.client.rig.Theropod;
import com.nirncraft.creatures.client.rig.defs.Defs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rig models of the realm creatures: the Deadlands (clannfear, daedroth, spider daedra), the Shivering Isles
 * (grummites, baliwogs, gnarls, elytra, flesh atronachs), Apocrypha (seekers, lurkers), the Soul Cairn (keepers,
 * bonemen, mistmen, wrathmen, wandering souls), Sovngarde (heroes, Tsun), Coldharbour (harvesters) and Miraak.
 *
 * <p>Plain Java on the rig kit (no Minecraft classes): {@link RealmRigDump} exports the UV layouts for
 * {@code tools/textures/bestiary_realms.py}. Each definition's name is also its texture base name.</p>
 */
public final class RealmDefs {
    /** Every realm creature definition, in registration order. */
    public static final List<String> NAMES = List.of("clannfear", "daedroth", "spider_daedra", "grummite", "baliwog", "gnarl",
            "elytra", "flesh_atronach", "seeker", "lurker", "keeper", "boneman", "mistman", "wrathman", "wandering_soul",
            "sovngarde_hero", "tsun", "harvester", "miraak");

    private RealmDefs() {}

    /** Puts the realm definitions into {@link Defs#ALL} (idempotent: names already present are left alone). */
    public static synchronized void register() {
        put("clannfear", RealmDefs::clannfear);
        put("daedroth", RealmDefs::daedroth);
        put("spider_daedra", RealmDefs::spiderDaedra);
        put("grummite", RealmDefs::grummite);
        put("baliwog", RealmDefs::baliwog);
        put("gnarl", RealmDefs::gnarl);
        put("elytra", RealmDefs::elytra);
        put("flesh_atronach", RealmDefs::fleshAtronach);
        put("seeker", RealmDefs::seeker);
        put("lurker", RealmDefs::lurker);
        put("keeper", RealmDefs::keeper);
        put("boneman", RealmDefs::boneman);
        put("mistman", RealmDefs::mistman);
        put("wrathman", RealmDefs::wrathman);
        put("wandering_soul", RealmDefs::wanderingSoul);
        put("sovngarde_hero", RealmDefs::sovngardeHero);
        put("tsun", RealmDefs::tsun);
        put("harvester", RealmDefs::harvester);
        put("miraak", RealmDefs::miraak);
    }

    private static void put(String name, Supplier<CreatureDef> def) {
        Defs.ALL.putIfAbsent(name, def);
    }

    // ================================================================================================ helpers

    /** Keeps only the listed animation slots. */
    static CreatureDef only(CreatureDef def, String... slots) {
        Set<String> keep = new HashSet<>(Arrays.asList(slots));
        def.anims.keySet().retainAll(keep);
        return def;
    }

    /** Removes every channel of the given bones from an animation (to re-author them). */
    static Anim drop(Anim a, String... bones) {
        Set<String> s = new HashSet<>(Arrays.asList(bones));
        a.channels.removeIf(c -> s.contains(c.bone));
        return a;
    }

    /** Rotation keys for {@code <base>_right}, mirrored onto {@code <base>_left} (y and z negated). */
    static Anim rotLR(Anim a, String base, float... keys) {
        a.rot(base + "_right", keys);
        float[] m = keys.clone();
        for (int i = 0; i + 3 < m.length; i += 4) {
            m[i + 2] = -m[i + 2];
            m[i + 3] = -m[i + 3];
        }
        a.rot(base + "_left", m);
        return a;
    }

    /** A full turn about y over the whole animation. */
    static Anim spin(Anim a, String bone, float turns) {
        a.linear().rot(bone, 0, 0, 0, 0, a.length, 0, 360 * turns, 0).smooth();
        return a;
    }

    /** A chain of tapering segments hanging from {@code parent} (tentacles, tails, mist streamers). */
    static Bone[] chain(Bone parent, String name, float x, float y, float z, String mat, int segs, int len, int w0, int w1,
                        float restPitch, float bend) {
        Bone[] out = new Bone[segs];
        Bone p = parent;
        for (int i = 0; i < segs; i++) {
            float f = segs == 1 ? 0 : (float) i / (segs - 1);
            int w = Math.max(1, Math.round(w0 + (w1 - w0) * f));
            Bone b = i == 0 ? p.child(name + "1", x, y, z).rot(restPitch, 0, 0) : p.child(name + (i + 1), 0, len - 0.4f, 0).rot(bend, 0, 0);
            b.box(mat, -w / 2f, 0, -w / 2f, w, len, w).tag(i == segs - 1 ? "tip" : "segment");
            out[i] = b;
            p = b;
        }
        return out;
    }

    /** Sways a chain: a travelling wave on one axis. */
    static void sway(Anim a, Bone[] chain, int axis, float amp, float phase, float offset) {
        for (int i = 0; i < chain.length; i++) {
            a.wave(chain[i].name, Anim.Target.ROTATION, axis, amp * (0.6f + i * 0.3f), phase - i * 0.12f, offset, 1);
        }
    }

    // ================================================================================================ the Deadlands

    /**
     * Clannfear: a fast, beaked, two-legged daedric reptile, red-brown scaled, with a fan-shaped bony frill crowned
     * with horns behind the skull, small clawed arms and a long balancing tail.
     */
    static CreatureDef clannfear() {
        Theropod t = new Theropod("clannfear");
        t.bodyW = 8;
        t.bodyH = 8;
        t.bodyL = 11;
        t.legLen = 13;
        t.legW = 4;
        t.neckLen = 4;
        t.neckW = 4;
        t.neckH = 5;
        t.neckPitch = -35;
        t.headW = 6;
        t.headH = 6;
        t.headL = 6;
        t.jawH = 2;
        t.armLen = 6;
        t.tailSegs = 5;
        t.tailLen = 18;
        t.tailW = 5;
        t.tailRaise = 8;
        t.skin = "clann_scales";
        t.belly = "clann_belly";
        t.headMat = "clann_head";
        t.legMat = "clann_scales";
        t.clawMat = "claw";
        t.stride = 34;
        t.build();
        Bone h = t.head;
        // hooked beak, a heavy brow and a nose horn
        h.box("beak", -1.5f, -2.5f, -9, 3, 3, 3).tag("beak");
        h.box("beak", -1, -1, -10, 2, 2, 1).tag("beak_tip");
        h.box("bone", -3.5f, -3.8f, -5.5f, 7, 1, 3).tag("brow");
        h.box("horn", -0.5f, -4.5f, -8.5f, 1, 2, 2).tag("nose_horn");
        t.jawBone.box("beak", -1, 0, -8, 2, 1, 3).tag("beak_low");
        // the bony frill fanning up and back from the skull, ringed with horns
        Bone frill = h.child("frill", 0, -2.5f, -1).rot(-55, 0, 0);
        frill.box("frill", -5, -7, 0, 10, 7, 1).tag("frill");
        frill.box("bone", -1, -7.5f, -0.6f, 2, 8, 2).tag("frill_ridge");
        frill.pair("frill_horn", 4, -6, 0.5f, (b, s) -> {
            b.rot(0, 0, -30);
            b.sbox("horn", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn");
            b.child("frill_horn_tip", 0, -4, 0).rot(0, 0, 18).sbox("horn", -0.5f, -2, -0.5f, 1, 2, 1).tag("horn_tip");
        });
        frill.pair("frill_spike", 5, -2.5f, 0.5f, (b, s) -> {
            b.rot(0, 0, -75);
            b.sbox("horn", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn");
        });
        frill.child("frill_crest", 0, -7.5f, 0.5f).rot(15, 0, 0).box("horn", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn_tip");
        h.pair("cheek_frill", 3, 0, -2, (b, s) -> {
            b.rot(0, 25, -35);
            b.sbox("frill", -1, -4, -1, 1, 5, 4).tag("cheek_frill");
        });
        // dorsal spines down the back and the base of the tail
        for (int i = 0; i < 3; i++) t.body.box("horn", -0.5f, -6, -3.5f + i * 3, 1, 2, 2).tag("spine");
        for (int i = 0; i < 3; i++) {
            int tw = Math.max(1, t.tailW - (i * t.tailW) / (t.tailSegs + 1));
            t.tail[i].box("horn", -0.5f, -tw / 2f - 1.5f, 1, 1, 2, 2).tag("spine");
        }
        for (String sd : new String[]{"_right", "_left"}) {
            Bone arm = t.rig.bone("arm" + sd);
            arm.sbox("claw", -1, t.armLen - 1, -3, 1, 2, 1).tag("claw");
        }

        CreatureDef def = t.def();
        // pounce: a deep crouch, a leap with the claws reaching, a tuck and a landing (hit ~0.3 s, the push-off)
        Anim p = Anim.once(1.1f);
        p.pos("body", 0, 0, 0, 0, 0.25f, 0, -3, 2, 0.42f, 0, 3, -4, 0.7f, 0, 2, -2, 1.1f, 0, 0, 0);
        p.rot("body", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.42f, -18, 0, 0, 0.7f, 6, 0, 0, 1.1f, 0, 0, 0);
        rotLR(p, "leg", 0, 0, 0, 0, 0.25f, -35, 0, 0, 0.42f, 45, 0, 0, 0.7f, -30, 0, 0, 1.1f, 0, 0, 0);
        rotLR(p, "shin", 0, 0, 0, 0, 0.25f, 40, 0, 0, 0.42f, -10, 0, 0, 0.7f, 35, 0, 0, 1.1f, 0, 0, 0);
        rotLR(p, "arm", 0, 0, 0, 0, 0.25f, 20, 0, 0, 0.42f, -75, 0, 10, 0.7f, -40, 0, 0, 1.1f, 0, 0, 0);
        p.rot("neck", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.42f, 22, 0, 0, 1.1f, 0, 0, 0);
        p.linear().rot("jaw", 0, 0, 0, 0, 0.3f, 40, 0, 0, 0.62f, 40, 0, 0, 0.7f, 0, 0, 0, 1.1f, 0, 0, 0).smooth();
        p.rot("frill", 0, 0, 0, 0, 0.3f, 25, 0, 0, 0.8f, 25, 0, 0, 1.1f, 0, 0, 0);
        for (int i = 0; i < t.tail.length; i++) p.rot(t.tail[i].name, 0, 0, 0, 0, 0.42f, -8, 0, 0, 0.7f, 6, 0, 0, 1.1f, 0, 0, 0);
        def.anim("attack2", p);
        // rake: lunges and tears downwards with both claws in turn (hit ~0.35 s)
        Anim rake = Anim.once(0.7f);
        rake.rot("body", 0, 0, 0, 0, 0.18f, -12, 0, 0, 0.35f, 14, 0, 0, 0.7f, 0, 0, 0);
        rake.pos("body", 0, 0, 0, 0, 0.18f, 0, 0.5f, 1.5f, 0.35f, 0, -1, -3, 0.7f, 0, 0, 0);
        rake.rot("arm_right", 0, 0, 0, 0, 0.18f, -80, 0, 25, 0.32f, 45, 0, -10, 0.7f, 0, 0, 0);
        rake.rot("arm_left", 0, 0, 0, 0, 0.24f, -80, 0, -25, 0.4f, 45, 0, 10, 0.7f, 0, 0, 0);
        rake.rot("neck", 0, 0, 0, 0, 0.18f, -20, 0, 0, 0.35f, 15, 0, 0, 0.7f, 0, 0, 0);
        rake.rot("jaw", 0, 0, 0, 0, 0.18f, 30, 0, 0, 0.4f, 10, 0, 0, 0.7f, 0, 0, 0);
        def.anim("attack3", rake);
        // the screech: neck stretched up, beak gaping, frill raised and spread
        Anim roar = t.roar();
        roar.rot("frill", 0, 0, 0, 0, 0.35f, 30, 0, 0, 1.1f, 30, 0, 0, 1.5f, 0, 0, 0);
        roar.scale("frill", 0, 1, 1, 1, 0.35f, 1.25f, 1.2f, 1, 1.1f, 1.25f, 1.2f, 1, 1.5f, 1, 1, 1);
        rotLR(roar, "cheek_frill", 0, 0, 0, 0, 0.35f, 0, 25, -20, 1.1f, 0, 25, -20, 1.5f, 0, 0, 0);
        rotLR(roar, "arm", 0, 0, 0, 0, 0.35f, -40, 0, 20, 1.1f, -40, 0, 20, 1.5f, 0, 0, 0);
        def.anim("special", roar);
        // idle: the frill twitches now and then
        Anim idle = def.get("idle");
        idle.rot("frill", 0, 0, 0, 0, 1.6f, 0, 0, 0, 1.8f, 12, 0, 0, 2.1f, 0, 0, 0, 3f, 0, 0, 0);
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "special", "sleep", "death", "hurt");
    }

    /**
     * Daedroth: a hulking, hunched crocodile-headed daedra of the Deadlands. Red-orange scales, a long toothed snout,
     * horned brow, spiked spine and tail, clawed hands and digitigrade legs. Breathes and spits fire.
     */
    static CreatureDef daedroth() {
        Biped b = new Biped("daedroth");
        b.legLen = 13;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 4;
        b.torsoW = 13;
        b.torsoH = 13;
        b.torsoD = 8;
        b.hunch = 22;
        b.armLen = 16;
        b.armW = 5;
        b.handSize = 4;
        b.headW = 7;
        b.headH = 6;
        b.headD = 7;
        b.headForward = 4;
        b.digitigrade = true;
        b.tailSegs = 4;
        b.tailLen = 16;
        b.tailW = 4;
        b.skin = "daedroth_scales";
        b.headMat = "daedroth_head";
        b.handMat = "daedroth_dark";
        b.footMat = "daedroth_dark";
        b.hipMat = "daedroth_scales";
        b.scale = 1.15f;
        b.stride = 24;
        b.build();
        Bone h = b.head;
        // the long crocodile snout over a hinged lower jaw, teeth along both
        h.box("daedroth_head", -2.5f, -5, -11, 5, 3, 7).tag("snout");
        h.box("daedroth_dark", -1.5f, -5.6f, -11.2f, 3, 1, 2).tag("nostrils");
        h.box("daedroth_dark", -3.5f, -6.5f, -4.5f, 7, 2, 3).tag("brow");
        h.box("tooth", -2.6f, -2, -10, 1, 1, 1).tag("tooth");
        h.box("tooth", 1.6f, -2, -10, 1, 1, 1).tag("tooth");
        h.box("tooth", -2.6f, -2, -7, 1, 1, 1).tag("tooth");
        h.box("tooth", 1.6f, -2, -7, 1, 1, 1).tag("tooth");
        Bone jaw = h.child("jaw", 0, -2, -3);
        jaw.box("daedroth_head", -2.5f, 0, -8, 5, 2, 8).tag("jaw");
        jaw.box("tooth", -2.6f, -1, -6.5f, 1, 1, 1).tag("tooth");
        jaw.box("tooth", 1.6f, -1, -6.5f, 1, 1, 1).tag("tooth");
        jaw.box("tooth", -2.6f, -1, -3.5f, 1, 1, 1).tag("tooth");
        jaw.box("tooth", 1.6f, -1, -3.5f, 1, 1, 1).tag("tooth");
        jaw.box("daedroth_belly", -2, 1.5f, -7, 4, 1, 6).tag("throat");
        h.pair("horn", 3, -6, 0, (hb, s) -> {
            hb.rot(-35, 0, -30);
            hb.sbox("horn", -1, -4, -1, 2, 4, 2).tag("horn");
            hb.child("horn_tip", 0, -4, 0).rot(-30, 0, 15).sbox("horn", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn_tip");
        });
        h.pair("frill", 3.5f, -3, 2, (f, s) -> {
            f.rot(0, 30, -15);
            f.sbox("daedroth_dark", -1, -3, 0, 1, 4, 3).tag("cheek_spike");
        });
        Bone t = b.torso;
        t.box("daedroth_belly", -5, -12, -4.6f, 10, 11, 1).tag("belly");
        for (int i = 0; i < 4; i++) {
            Bone sp = t.child("spine" + (i + 1), 0, -12 + i * 3, 4).rot(-55, 0, 0);
            sp.box("horn", -1, -4 + (i == 0 ? -1 : 0), -1, 2, 4 + (i == 0 ? 1 : 0), 2).tag("spine");
        }
        for (Bone arm : b.arms) {
            arm.sbox("daedroth_dark", -3, -3, -3, 6, 4, 6).tag("shoulder");
            arm.child("shoulder_spike", -2, -3, 0).rot(-10, 0, -40).sbox("horn", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn");
        }
        for (Bone hand : b.hands) {
            hand.sbox("claw", -2, 3, -2, 1, 3, 1).tag("claw");
            hand.sbox("claw", -0.5f, 3, -2, 1, 3, 1).tag("claw");
            hand.sbox("claw", 1, 3, -2, 1, 3, 1).tag("claw");
        }
        for (Bone foot : b.feet) foot.sbox("claw", -2.5f, 0.5f, -5.5f, 5, 1, 1).tag("claw");
        for (int i = 0; i < b.tail.length - 1; i++) b.tail[i].box("horn", -0.5f, -3, 1, 1, 2, 2).tag("spine");

        CreatureDef def = b.def();
        Anim idle = def.get("idle");
        idle.rot("jaw", 0, 0, 0, 0, 2.2f, 0, 0, 0, 2.5f, 12, 0, 0, 2.9f, 0, 0, 0, 3.5f, 0, 0, 0);
        // bite: the head drives forward and the jaws slam shut (hit ~0.4 s)
        Anim bite = Anim.once(0.8f);
        bite.rot("torso", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.4f, 18, 0, 0, 0.8f, 0, 0, 0);
        bite.rot("head", 0, 0, 0, 0, 0.25f, -20, 0, 0, 0.4f, 12, 0, 0, 0.8f, 0, 0, 0);
        bite.linear().rot("jaw", 0, 0, 0, 0, 0.25f, 50, 0, 0, 0.36f, 50, 0, 0, 0.42f, 0, 0, 0, 0.8f, 0, 0, 0).smooth();
        bite.pos("hips", 0, 0, 0, 0, 0.25f, 0, 0, 1.5f, 0.4f, 0, -0.5f, -3, 0.8f, 0, 0, 0);
        rotLR(bite, "arm", 0, 0, 0, 0, 0.25f, -20, 0, 20, 0.4f, -40, 0, 10, 0.8f, 0, 0, 0);
        def.anim("attack", bite);
        // fire breath: rears, then holds a long open-jawed gout of flame, head sweeping (2 s)
        Anim breath = Anim.once(2.0f);
        breath.rot("torso", 0, 0, 0, 0, 0.35f, -15, 0, 0, 0.5f, 10, 0, 0, 1.7f, 10, 0, 0, 2.0f, 0, 0, 0);
        breath.rot("head", 0, 0, 0, 0, 0.35f, -25, 0, 0, 0.5f, 5, -12, 0, 1.1f, 5, 12, 0, 1.7f, 5, -5, 0, 2.0f, 0, 0, 0);
        breath.rot("jaw", 0, 0, 0, 0, 0.35f, 25, 0, 0, 0.5f, 45, 0, 0, 1.7f, 45, 0, 0, 2.0f, 0, 0, 0);
        rotLR(breath, "arm", 0, 0, 0, 0, 0.35f, -20, 0, 35, 1.7f, -20, 0, 35, 2.0f, 0, 0, 0);
        rotLR(breath, "forearm", 0, 0, 0, 0, 0.35f, -40, 0, 0, 1.7f, -40, 0, 0, 2.0f, 0, 0, 0);
        breath.scale("throat", 0, 1, 1, 1, 0.35f, 1.2f, 1.6f, 1, 0.5f, 1, 1, 1, 2.0f, 1, 1, 1);
        def.anim("special", breath);
        // spit: a short lunge of the head, jaws flung open (hit ~0.5 s)
        Anim spit = Anim.once(1.0f);
        spit.rot("torso", 0, 0, 0, 0, 0.35f, -12, 0, 0, 0.5f, 12, 0, 0, 1.0f, 0, 0, 0);
        spit.rot("head", 0, 0, 0, 0, 0.35f, -20, 0, 0, 0.5f, 10, 0, 0, 1.0f, 0, 0, 0);
        spit.rot("jaw", 0, 0, 0, 0, 0.35f, 15, 0, 0, 0.5f, 50, 0, 0, 0.7f, 30, 0, 0, 1.0f, 0, 0, 0);
        spit.scale("throat", 0, 1, 1, 1, 0.35f, 1.3f, 1.8f, 1, 0.5f, 1, 1, 1, 1.0f, 1, 1, 1);
        def.anim("cast", spit);
        Anim roar = b.roar();
        roar.rot("jaw", 0, 0, 0, 0, 0.35f, 45, 0, 0, 1.1f, 42, 0, 0, 1.5f, 0, 0, 0);
        def.anim("roar", roar);
        Anim death = def.get("death");
        death.rot("jaw", 0, 0, 0, 0, 0.6f, 30, 0, 0, 1f, 30, 0, 0);
        return only(def, "idle", "walk", "run", "attack", "attack2", "special", "cast", "roar", "death", "hurt");
    }

    /**
     * Spider daedra: Mephala's servant. A purple-black chitinous spider body with eight long legs, from whose front
     * rises a pale woman's torso in chitin plates: clawed gauntlets, a spiked crown of black hair and a cluster of eyes.
     */
    static CreatureDef spiderDaedra() {
        Arachnid a = new Arachnid("spider_daedra");
        a.bodyW = 10;
        a.bodyH = 6;
        a.bodyL = 10;
        a.headW = 6;
        a.headH = 4;
        a.headL = 3;
        a.fangLen = 3;
        a.abdomenW = 12;
        a.abdomenH = 10;
        a.abdomenL = 13;
        a.abdomenTilt = 20;
        a.legPairs = 4;
        a.legUpper = 10;
        a.legLower = 15;
        a.legW = 2;
        a.legUp = 50;
        a.legDown = 78;
        a.shell = "sd_chitin";
        a.legMat = "sd_leg";
        a.abdomenMat = "sd_abdomen";
        a.fangMat = "sd_claw";
        a.build();
        a.abdomen.box("sd_chitin", -4, -6, 2, 8, 2, 9).tag("abdomen_ridge");
        a.abdomen.box("sd_leg", -1, -2, 12.5f, 2, 2, 2).tag("spinneret");
        for (int i = 1; i <= 4; i++) {
            for (String sd : new String[]{"_right", "_left"}) {
                a.rig.bone("leg" + i + "_upper" + sd).sbox("sd_chitin", -2, -1.5f, -1.5f, 2, 3, 3).tag("joint");
                Bone low = a.rig.bone("leg" + i + "_lower" + sd);
                low.sbox("sd_claw", -16, -0.5f, -0.5f, 2, 1, 1).tag("leg_tip");
            }
        }
        // the woman's torso rising from the front of the spider body
        Bone torso = a.body.child("torso", 0, -2.5f, -3).rot(8, 0, 0);
        torso.box("sd_skin", -3, -8, -2, 6, 8, 4).tag("waist");
        torso.box("sd_chitin", -3.5f, -4, -2.5f, 7, 4, 5, 0.1f).tag("girdle");
        torso.box("sd_skin", -3.5f, -13, -2.5f, 7, 5, 5).tag("chest");
        torso.box("sd_chitin", -3.2f, -12.4f, -3.2f, 3, 3, 1).tag("cup");
        torso.box("sd_chitin", 0.2f, -12.4f, -3.2f, 3, 3, 1).tag("cup");
        torso.box("sd_chitin", -3, -13.5f, 1.8f, 6, 6, 1).tag("back_plate");
        torso.box("sd_skin", -1, -14, -1, 2, 1, 2).tag("neck");
        Bone face = torso.child("face", 0, -14, -0.3f);
        face.box("sd_skin", -2.5f, -5, -2.5f, 5, 5, 5).tag("face");
        face.box("sd_hair", -3, -5.8f, -2.8f, 6, 2, 6).tag("hair");
        face.box("sd_hair", -2.5f, -4, 2.2f, 5, 8, 1).tag("hair_back");
        face.box("sd_chitin", -2.5f, -5.6f, -3, 5, 1, 1).tag("crown");
        face.pair("hair_spike", 2, -5, 1, (hs, s) -> {
            hs.rot(-55, 0, -25);
            hs.sbox("sd_hair", -1, -5, -1, 2, 5, 2).tag("hair_spike");
            hs.child("hair_spike_tip", 0, -5, 0).rot(-15, 0, 10).sbox("sd_hair", -0.5f, -3, -0.5f, 1, 3, 1).tag("hair_spike");
        });
        face.child("crest", 0, -5.5f, 0).rot(-30, 0, 0).box("sd_chitin", -0.5f, -4, -0.5f, 1, 4, 1).tag("crest");
        torso.pair("arm", 4.5f, -11.5f, 0, (b, s) -> {
            b.rot(0, 0, 12);
            b.sbox("sd_skin", -1, -1, -1, 2, 6, 2).tag("arm");
            b.sbox("sd_chitin", -2, -2, -1.5f, 3, 3, 3, 0.2f).tag("pauldron");
            b.child("pauldron_spike", -1, -1.5f, 0).rot(0, 0, -35).sbox("sd_claw", -0.5f, -3, -0.5f, 1, 3, 1).tag("spike");
            Bone fore = b.child("forearm", 0, 5, 0).rot(-25, 0, 0);
            fore.sbox("sd_chitin", -1, 0, -1, 2, 6, 2, 0.1f).tag("gauntlet");
            Bone hand = fore.child("hand", 0, 6, 0);
            hand.sbox("sd_chitin", -1, -0.5f, -1, 2, 2, 2).tag("hand");
            hand.sbox("sd_claw", -1, 1.5f, -1, 1, 3, 1).tag("claw");
            hand.sbox("sd_claw", 0, 1.5f, -0.5f, 1, 3, 1).tag("claw");
        });

        CreatureDef def = a.def();
        def.look.clear();
        def.look("face", 0.65f).look("torso", 0.35f);
        Anim idle = def.get("idle");
        idle.swing("torso", 2, 2.5f, 0.1f);
        idle.swing("torso", 0, 2f, 0.35f);
        idle.swing("face", 1, 9f, 0.2f);
        idle.swing("arm_right", 2, 4f, 0f);
        idle.swing("arm_left", 2, -4f, 0f);
        idle.swing("forearm_right", 0, 8f, 0.2f);
        idle.swing("forearm_left", 0, 8f, 0.7f);
        idle.swingN("hair_spike_right", 2, 4f, 0f, 2);
        idle.swingN("hair_spike_left", 2, -4f, 0.25f, 2);
        for (String slot : new String[]{"walk", "run"}) {
            Anim w = def.get(slot);
            w.hold("torso", 8, 0, 0);
            w.swing("arm_right", 0, 18f, 0.5f);
            w.swing("arm_left", 0, 18f, 0f);
            w.swingN("torso", 2, 3f, 0f, 2);
        }
        // claws: rears on the spider body and rakes down with both gauntlets in turn (hit ~0.38 s)
        Anim claw = Anim.once(0.75f);
        claw.rot("body", 0, 0, 0, 0, 0.25f, -12, 0, 0, 0.4f, 8, 0, 0, 0.75f, 0, 0, 0);
        claw.rot("torso", 0, 0, 0, 0, 0.25f, -15, 25, 0, 0.4f, 18, -20, 0, 0.75f, 0, 0, 0);
        claw.rot("arm_right", 0, 0, 0, 0, 0.25f, -160, 20, 30, 0.38f, -40, -20, -10, 0.75f, 0, 0, 0);
        claw.rot("forearm_right", 0, 0, 0, 0, 0.25f, -20, 0, 0, 0.38f, 10, 0, 0, 0.75f, 0, 0, 0);
        claw.rot("arm_left", 0, 0, 0, 0, 0.32f, -150, -20, -30, 0.46f, -40, 20, 10, 0.75f, 0, 0, 0);
        claw.rot("face", 0, 0, 0, 0, 0.25f, -10, -15, 0, 0.4f, 10, 10, 0, 0.75f, 0, 0, 0);
        def.anim("attack", claw);
        // venom bite: the spider body lunges and the fangs stab (hit ~0.36 s)
        Anim bite = a.strike();
        bite.rot("torso", 0, 0, 0, 0, 0.25f, -20, 0, 0, 0.38f, 25, 0, 0, 0.7f, 0, 0, 0);
        rotLR(bite, "arm", 0, 0, 0, 0, 0.25f, -60, 0, 40, 0.38f, -30, 0, 20, 0.7f, 0, 0, 0);
        def.anim("attack2", bite);
        // shock: both hands thrust forward, crackling (hit ~0.5 s)
        Anim cast = Anim.once(1.0f);
        rotLR(cast, "arm", 0, 0, 0, 0, 0.3f, -40, 0, 60, 0.48f, -90, -20, 0, 0.75f, -88, -15, 0, 1.0f, 0, 0, 0);
        rotLR(cast, "forearm", 0, 0, 0, 0, 0.3f, -50, 0, 0, 0.48f, 20, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.48f, 10, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("face", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.48f, 5, 0, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);
        // web spit: the abdomen pumps and swings forward under the body (hit ~0.45 s)
        Anim spit = a.spit();
        spit.rot("abdomen", 0, 0, 0, 0, 0.3f, -35, 0, 0, 0.45f, 25, 0, 0, 0.9f, 0, 0, 0);
        spit.rot("torso", 0, 0, 0, 0, 0.3f, -10, 0, 0, 0.45f, 8, 0, 0, 0.9f, 0, 0, 0);
        def.anim("special", spit);
        Anim death = def.get("death");
        death.rot("torso", 0, 0, 0, 0, 0.6f, 40, 0, 15, 1f, 55, 0, 20);
        death.rot("face", 0, 0, 0, 0, 0.6f, 30, 20, 0, 1f, 35, 25, 0);
        rotLR(death, "arm", 0, 0, 0, 0, 0.6f, -20, 0, 30, 1f, -15, 0, 40);
        Anim hurt = def.get("hurt");
        hurt.rot("torso", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.4f, 0, 0, 0);
        return only(def, "idle", "walk", "run", "attack", "attack2", "cast", "special", "death", "hurt");
    }

    // ================================================================================================ the Shivering Isles

    /**
     * Grummite: a small hunched fish-frog daedra of the Isles' swamps: grey-green warty skin, a wide lipless mouth
     * with an inflating throat, bulging eyes, ear fins and a dorsal fin, webbed feet; it carries a spiked club. The
     * magus variant swaps the club for a shell-topped staff and wears a ragged hood skirt.
     */
    static CreatureDef grummite() {
        Biped b = new Biped("grummite");
        b.legLen = 8;
        b.legW = 3;
        b.hipW = 6;
        b.hipH = 3;
        b.torsoW = 8;
        b.torsoH = 8;
        b.torsoD = 6;
        b.hunch = 28;
        b.armLen = 11;
        b.armW = 3;
        b.handSize = 3;
        b.headW = 7;
        b.headH = 5;
        b.headD = 6;
        b.headForward = 3;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "grum_skin";
        b.headMat = "grum_skin";
        b.handMat = "grum_dark";
        b.footMat = "grum_dark";
        b.hipMat = "grum_cloth";
        b.stride = 32;
        b.build();
        Bone h = b.head;
        h.pair("eye", 2.5f, -4.5f, -1.5f, (e, s) -> e.sbox("grum_eye", -1.5f, -1.5f, -1.5f, 3, 2, 3).tag("eye_bulb"));
        h.pair("fin", 3.5f, -2.5f, 0.5f, (f, s) -> {
            f.rot(0, -35, 0);
            f.sbox("grum_fin", 0, -2, 0, 0, 4, 4).tag("ear_fin");
        });
        h.box("grum_dark", -1, -3.5f, -3.9f, 2, 1, 1).tag("nostrils");
        b.jawBone.box("grum_belly", -2, 1, -4, 4, 1, 3).tag("chin");
        Bone throat = h.child("throat", 0, 0.5f, -1.5f);
        throat.box("grum_belly", -2, -0.5f, -2, 4, 2, 3).tag("throat");
        b.torso.box("grum_belly", -3, -7, -3.4f, 6, 7, 1).tag("belly");
        b.torso.box("grum_fin", 0, -11, 1, 0, 6, 4).tag("dorsal_fin");
        b.torso.box("grum_cloth", -4.5f, -9, -3.5f, 9, 2, 7, 0.1f).tag("strap");
        b.hips.box("grum_cloth", -3, -1, -3.4f, 6, 5, 1).tag("loincloth");
        b.hips.box("grum_cloth", -3, -1, 2.4f, 6, 4, 1).tag("loincloth");
        for (Bone foot : b.feet) foot.sbox("grum_dark", -2, 0, -5, 4, 1, 2).tag("webbed");
        for (Bone hand : b.hands) {
            hand.sbox("claw", -1.5f, 2.5f, -1.5f, 1, 2, 1).tag("claw");
            hand.sbox("claw", 0.5f, 2.5f, -1.5f, 1, 2, 1).tag("claw");
        }
        // the grummite's club (variant 0)
        Bone club = b.hands[0].child("club", 0, 1.5f, 0).rot(70, 0, 0);
        club.box("wood", -0.5f, -0.5f, -2, 1, 1, 5).tag("handle");
        club.box("wood_dark", -1.5f, -1.5f, -9, 3, 3, 7).tag("club_head");
        club.box("bone", -0.5f, -2.5f, -8, 1, 1, 1).tag("spike");
        club.box("bone", -2.5f, -0.5f, -6, 1, 1, 1).tag("spike");
        club.box("bone", 1.5f, -0.5f, -7.5f, 1, 1, 1).tag("spike");
        // the magus's staff and hooded robe (variant 1)
        Bone staff = b.hands[0].child("staff", 0, 1, 0).rot(10, 0, 0);
        staff.box("wood", -0.5f, -15, -0.5f, 1, 21, 1).tag("staff");
        staff.box("shell", -1.5f, -18, -1.5f, 3, 3, 3).tag("staff_head");
        staff.box("wood_dark", -2, -16, -0.5f, 1, 2, 1).tag("staff_prong");
        staff.box("wood_dark", 1, -16, -0.5f, 1, 2, 1).tag("staff_prong");
        Bone robe = b.hips.child("magus_robe", 0, 0, 0);
        robe.box("robe", -4, -1, -3.6f, 8, 7, 7).tag("robe_skirt");
        Bone hood = b.torso.child("magus_hood", 0, -8, 1);
        hood.box("robe", -4.5f, -3, -2, 9, 4, 5).tag("hood_mantle");

        CreatureDef def = b.def();
        Anim idle = def.get("idle");
        idle.scale("throat", 0, 1, 1, 1, 1.0f, 1.25f, 1.6f, 1.15f, 1.5f, 1, 1, 1, 2.6f, 1, 1, 1, 3.0f, 1.25f, 1.6f, 1.15f, 3.5f, 1, 1, 1);
        idle.swingN("fin_right", 1, 8f, 0f, 2);
        idle.swingN("fin_left", 1, -8f, 0f, 2);
        // the croak: puffed-out throat, head thrown back, arms spread
        Anim croak = b.roar();
        croak.scale("throat", 0, 1, 1, 1, 0.35f, 1.6f, 2.4f, 1.4f, 1.1f, 1.6f, 2.4f, 1.4f, 1.5f, 1, 1, 1);
        rotLR(croak, "fin", 0, 0, 0, 0, 0.35f, 0, -30, 0, 1.1f, 0, -30, 0, 1.5f, 0, 0, 0);
        def.anim("special", croak);
        // magus cast: the staff raised high, then levelled (hit ~0.5 s)
        Anim cast = Anim.once(1.0f);
        cast.rot("arm_right", 0, 0, 0, 0, 0.32f, -160, 0, 15, 0.5f, -85, 0, 0, 0.75f, -80, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("forearm_right", 0, 0, 0, 0, 0.32f, -10, 0, 0, 0.5f, 0, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("staff_right", 0, 0, 0, 0, 0.32f, 0, 0, 0, 0.5f, -60, 0, 0, 0.75f, -60, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("arm_left", 0, 0, 0, 0, 0.32f, -60, 0, -30, 0.5f, -30, 0, -20, 1.0f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.32f, -12, 0, 0, 0.5f, 8, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("jaw", 0, 0, 0, 0, 0.32f, 25, 0, 0, 0.6f, 25, 0, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);
        return only(def, "idle", "walk", "run", "attack", "attack2", "cast", "special", "death", "hurt");
    }

    /**
     * Baliwog: the grummites' larval form, a low newt-like amphibian with a broad toothy head, frilled external gills, a
     * finned back and a long paddle tail on four splayed legs.
     */
    static CreatureDef baliwog() {
        Quad q = new Quad("baliwog");
        q.bodyW = 7;
        q.bodyH = 5;
        q.bodyL = 12;
        q.legLen = 4;
        q.legW = 2;
        q.foot = "paw";
        q.neckSegs = 1;
        q.neckLen = 2;
        q.neckW = 6;
        q.neckH = 4;
        q.neckPitch = -6;
        q.headW = 7;
        q.headH = 4;
        q.headL = 5;
        q.snoutW = 6;
        q.snoutH = 2;
        q.snoutL = 2;
        q.jaw = true;
        q.earH = 0;
        q.tailSegs = 5;
        q.tailLen = 15;
        q.tailW = 3;
        q.tailDroop = 4;
        q.fur = "bali_skin";
        q.belly = "bali_belly";
        q.legMat = "bali_skin";
        q.footMat = "claw";
        q.tailMat = "bali_skin";
        q.stride = 34;
        q.runStride = 44;
        q.gallop = false;
        q.scale = 1.2f;
        q.build();
        Bone h = q.head;
        h.pair("eye", 2.5f, -2, -2.5f, (e, s) -> e.sbox("bali_eye", -1, -1.5f, -1, 2, 2, 2).tag("eye_bulb"));
        h.pair("gill", 3.5f, 0, -0.5f, (g, s) -> {
            g.rot(0, -20, 0);
            for (int i = 0; i < 3; i++) {
                Bone st = g.child("gill_stalk" + (i + 1), 0, -1.5f + i * 1.2f, 0).rot(-30 + i * 30, -40, 0);
                st.sbox("bali_gill", -0.5f, -0.5f, 0, 1, 1, 4).tag("gill");
            }
        });
        q.body.box("bali_fin", 0, -5.5f, -4, 0, 3, 9).tag("dorsal_fin");
        for (int i = 0; i < q.tail.length; i++) {
            int tw = Math.max(1, q.tailW - (i * q.tailW) / (q.tailSegs + 1));
            q.tail[i].box("bali_fin", 0, -tw / 2f - 2, 0, 0, tw + 4, Math.round(15f / 5 + 0.4f)).tag("tail_fin");
        }
        if (q.jawBone != null) {
            q.jawBone.box("tooth", -2, -1, -5, 1, 1, 1).tag("tooth");
            q.jawBone.box("tooth", 1, -1, -5, 1, 1, 1).tag("tooth");
        }
        CreatureDef def = q.def();
        for (String slot : new String[]{"idle", "walk", "run"}) {
            Anim a = def.get(slot);
            float amp = slot.equals("idle") ? 8 : 14;
            for (String sd : new String[]{"_right", "_left"}) {
                for (int i = 1; i <= 3; i++) a.swingN("gill_stalk" + i + sd, 0, amp, i * 0.15f, 2);
            }
        }
        // lunge: belly-skids forward with the jaws open (hit ~0.35 s)
        Anim lunge = Anim.once(0.8f);
        lunge.pos("body", 0, 0, 0, 0, 0.2f, 0, -1, 2, 0.35f, 0, 1, -5, 0.6f, 0, 0, -3, 0.8f, 0, 0, 0);
        lunge.rot("head", 0, 0, 0, 0, 0.2f, -15, 0, 0, 0.35f, 8, 0, 0, 0.8f, 0, 0, 0);
        lunge.linear().rot("jaw", 0, 0, 0, 0, 0.2f, 40, 0, 0, 0.32f, 40, 0, 0, 0.38f, 0, 0, 0, 0.8f, 0, 0, 0).smooth();
        rotLR(lunge, "leg_front", 0, 0, 0, 0, 0.2f, 30, 0, 0, 0.35f, -50, 0, 0, 0.8f, 0, 0, 0);
        rotLR(lunge, "leg_back", 0, 0, 0, 0, 0.2f, 30, 0, 0, 0.35f, 40, 0, 0, 0.8f, 0, 0, 0);
        for (int i = 0; i < q.tail.length; i++) lunge.rot(q.tail[i].name, 0, 0, 0, 0, 0.35f, 0, (i % 2 == 0 ? 25 : -25), 0, 0.8f, 0, 0, 0);
        def.anim("attack2", lunge);
        return only(def, "idle", "walk", "run", "attack", "attack2", "special", "sleep", "death", "hurt");
    }

    /**
     * Gnarl: a walking tree of the Isles. A knotted bark body on root legs, long branch arms ending in twig fingers,
     * a knot-hole face with ember eyes and a crown of leafy branches. Variants: gnarl, elder gnarl, ancient gnarl.
     */
    static CreatureDef gnarl() {
        Biped b = new Biped("gnarl");
        b.legLen = 13;
        b.legW = 5;
        b.hipW = 9;
        b.hipH = 4;
        b.torsoW = 11;
        b.torsoH = 13;
        b.torsoD = 7;
        b.hunch = 14;
        b.armLen = 18;
        b.armW = 4;
        b.handSize = 4;
        b.headW = 7;
        b.headH = 7;
        b.headD = 6;
        b.headForward = 2;
        b.skin = "bark";
        b.headMat = "bark_face";
        b.handMat = "bark";
        b.footMat = "root";
        b.hipMat = "bark";
        b.stride = 22;
        b.build();
        Bone t = b.torso;
        t.box("bark_dark", -4, -11, 3, 8, 6, 2).tag("burl");
        t.box("moss", -5.5f, -13.5f, -4, 11, 2, 8, 0.2f).tag("moss_mantle");
        t.box("bark_dark", -2.5f, -8, -4, 5, 4, 1).tag("hollow");
        b.head.box("bark_dark", -3.5f, -7.5f, -4, 7, 2, 2).tag("brow");
        b.head.box("bark", -1, -4, -5, 2, 2, 1).tag("knot_nose");
        b.head.pair("branch", 2.5f, -7, 0, (br, s) -> {
            br.rot(-15, 0, -30);
            br.sbox("bark", -1, -6, -1, 2, 6, 2).tag("branch");
            Bone tw = br.child("twig", 0, -6, 0).rot(10, 0, 30);
            tw.sbox("bark", -0.5f, -4, -0.5f, 1, 4, 1).tag("twig");
            tw.sbox("leaf", -2, -7, -2, 4, 3, 4).tag("leaves");
            Bone tw2 = br.child("twig2", 0, -3, 0).rot(-30, 0, -40);
            tw2.sbox("bark", -0.5f, -3, -0.5f, 1, 3, 1).tag("twig");
            tw2.sbox("leaf", -1.5f, -5, -1.5f, 3, 2, 3).tag("leaves");
        });
        b.head.child("crown", 0, -7, 1).rot(-25, 0, 0).box("bark", -1, -5, -1, 2, 5, 2).tag("branch")
                .box("leaf", -2.5f, -8, -2.5f, 5, 3, 5).tag("leaves");
        for (Bone arm : b.arms) {
            arm.sbox("bark_dark", -3, -3, -3, 6, 4, 6).tag("shoulder");
            Bone sb = arm.child("shoulder_branch", -1.5f, -3, 1).rot(-20, 0, -35);
            sb.sbox("bark", -0.5f, -5, -0.5f, 1, 5, 1).tag("twig");
            sb.sbox("leaf", -1.5f, -7, -1.5f, 3, 2, 3).tag("leaves");
        }
        for (Bone fore : b.forearms) fore.sbox("bark_dark", -2.5f, 3, -2.5f, 5, 3, 5).tag("knot");
        for (Bone hand : b.hands) {
            hand.sbox("root", -2, 3, -2, 1, 5, 1).tag("finger");
            hand.sbox("root", -0.5f, 3, -2, 1, 6, 1).tag("finger");
            hand.sbox("root", 1, 3, -2, 1, 5, 1).tag("finger");
            hand.sbox("root", -2.5f, 2, 0.5f, 1, 4, 1).tag("finger");
        }
        for (Bone foot : b.feet) {
            foot.sbox("root", -3, 0, -5, 1, 1, 3).tag("toe");
            foot.sbox("root", 2, 0, -5, 1, 1, 3).tag("toe");
            foot.sbox("root", -0.5f, 0, -6, 1, 1, 3).tag("toe");
        }
        for (Bone shin : b.shins) shin.sbox("root", -3, 2, -1, 1, 4, 1).tag("root");

        CreatureDef def = b.def();
        for (String slot : new String[]{"idle", "walk"}) {
            Anim a = def.get(slot);
            a.swingN("branch_right", 2, 4f, 0f, 2);
            a.swingN("branch_left", 2, -4f, 0.3f, 2);
            a.swingN("twig_right", 2, 6f, 0.1f, 2);
            a.swingN("twig_left", 2, -6f, 0.4f, 2);
            a.swingN("crown", 0, 5f, 0.2f, 2);
        }
        Anim death = Anim.once(1.4f);
        // topples forward like a felled trunk
        death.rot("hips", 0, 0, 0, 0, 0.5f, 15, 0, 0, 1.1f, 80, 0, 0, 1.4f, 78, 0, 0);
        death.pos("hips", 0, 0, 0, 0, 1.1f, 0, -9, -4, 1.4f, 0, -9.5f, -4);
        death.rot("torso", 0, 0, 0, 0, 0.5f, 10, 0, 5, 1.4f, 5, 0, 5);
        rotLR(death, "arm", 0, 0, 0, 0, 0.8f, -60, 0, 20, 1.4f, -150, 0, 30);
        rotLR(death, "leg", 0, 0, 0, 0, 1.1f, -70, 0, 0, 1.4f, -75, 0, 0);
        def.anim("death", death);
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "special", "death", "hurt");
    }

    /**
     * Elytra: the Isles' great insects. A glossy iridescent six-legged body with folded wing cases over the abdomen,
     * a pair of mantis scythes, mandibles, long antennae and big compound eyes.
     */
    static CreatureDef elytra() {
        Arachnid a = new Arachnid("elytra");
        a.bodyW = 6;
        a.bodyH = 5;
        a.bodyL = 8;
        a.headW = 5;
        a.headH = 4;
        a.headL = 4;
        a.fangLen = 2;
        a.abdomenW = 8;
        a.abdomenH = 6;
        a.abdomenL = 11;
        a.abdomenTilt = 6;
        a.legPairs = 3;
        a.legUpper = 7;
        a.legLower = 10;
        a.legW = 2;
        a.legUp = 45;
        a.legDown = 75;
        a.shell = "ely_shell";
        a.legMat = "ely_leg";
        a.abdomenMat = "ely_abdomen";
        a.fangMat = "ely_blade";
        a.build();
        a.head.pair("antenna", 1.5f, -2, -3, (an, s) -> {
            an.rot(-40, 20, 0);
            an.sbox("ely_leg", -0.5f, -0.5f, -6, 1, 1, 6).tag("antenna");
            an.child("antenna_tip", 0, 0, -6).rot(25, 10, 0).sbox("ely_leg", -0.5f, -0.5f, -4, 1, 1, 4).tag("antenna");
        });
        a.head.pair("eye", 2.2f, -1, -2.5f, (e, s) -> e.sbox("ely_eye", -1, -1.5f, -1.5f, 2, 3, 3).tag("compound_eye"));
        // mantis scythes, raised and folded
        a.body.pair("scythe", 2.5f, -1, -3.5f, (sc, s) -> {
            sc.rot(30, 10, 0);
            sc.sbox("ely_leg", -1, -7, -1, 2, 7, 2).tag("scythe_arm");
            Bone blade = sc.child("scythe_blade", 0, -7, 0).rot(7, 0, 0);
            blade.sbox("ely_blade", -0.5f, -0.5f, -8, 1, 2, 8).tag("blade");
            blade.sbox("ely_blade", -0.5f, 1.5f, -6, 1, 1, 1).tag("spike");
            blade.sbox("ely_blade", -0.5f, 1.5f, -3, 1, 1, 1).tag("spike");
        });
        // the wing cases over the abdomen, membranous wings tucked beneath
        a.abdomen.pair("wing_case", 0.5f, -3.2f, 0.5f, (w, s) -> {
            w.sbox("ely_case", -4.5f, -0.5f, 0, 5, 1, 11).tag("wing_case");
            w.sbox("ely_case", -4.5f, 0.5f, 0, 1, 2, 11).tag("case_rim");
        });
        a.abdomen.pair("wing", 0.5f, -2.6f, 1, (w, s) -> w.sbox("ely_wing", -4.5f, 0, 0, 4, 0, 11).tag("wing"));
        a.body.box("ely_shell", -2, -3.5f, -3, 4, 1, 6).tag("pronotum");

        CreatureDef def = a.def();
        Anim idle = def.get("idle");
        idle.swing("antenna_right", 1, 10f, 0f);
        idle.swing("antenna_left", 1, -10f, 0.3f);
        idle.swingN("antenna_tip_right", 0, 8f, 0.1f, 2);
        idle.swingN("antenna_tip_left", 0, 8f, 0.4f, 2);
        idle.swing("scythe_right", 0, 4f, 0f);
        idle.swing("scythe_left", 0, 4f, 0.5f);
        for (String slot : new String[]{"walk", "run"}) {
            Anim w = def.get(slot);
            w.swing("antenna_right", 0, 12f, 0f);
            w.swing("antenna_left", 0, 12f, 0.5f);
            w.swingN("scythe_right", 0, 6f, 0f, 2);
            w.swingN("scythe_left", 0, 6f, 0.5f, 2);
        }
        // scythe strike: both blades flick up and snap down (hit ~0.35 s)
        Anim strike = Anim.once(0.7f);
        rotLR(strike, "scythe", 0, 0, 0, 0, 0.22f, -45, 0, 0, 0.35f, 40, 0, 0, 0.5f, 30, 0, 0, 0.7f, 0, 0, 0);
        rotLR(strike, "scythe_blade", 0, 0, 0, 0, 0.22f, -40, 0, 0, 0.35f, 45, 0, 0, 0.7f, 0, 0, 0);
        strike.rot("body", 0, 0, 0, 0, 0.22f, -12, 0, 0, 0.35f, 10, 0, 0, 0.7f, 0, 0, 0);
        strike.pos("body", 0, 0, 0, 0, 0.22f, 0, 1, 1, 0.35f, 0, -0.5f, -2.5f, 0.7f, 0, 0, 0);
        def.anim("attack", strike);
        def.anim("attack2", a.strike());
        // display: wing cases spring open and the wings buzz
        Anim buzz = Anim.once(1.4f);
        rotLR(buzz, "wing_case", 0, 0, 0, 0, 0.2f, 0, 0, -55, 1.1f, 0, 0, -55, 1.4f, 0, 0, 0);
        buzz.linear();
        float[] keys = new float[4 * 15];
        for (int i = 0; i < 15; i++) {
            float tt = i * 0.1f;
            keys[i * 4] = tt;
            keys[i * 4 + 3] = (i == 0 || i >= 12) ? 0 : (i % 2 == 0 ? -40 : -70);
            keys[i * 4 + 2] = (i == 0 || i >= 12) ? 0 : -20;
        }
        rotLR(buzz, "wing", keys);
        buzz.smooth();
        buzz.rot("body", 0, 0, 0, 0, 0.2f, -15, 0, 0, 1.1f, -15, 0, 0, 1.4f, 0, 0, 0);
        rotLR(buzz, "scythe", 0, 0, 0, 0, 0.2f, -40, 0, -20, 1.1f, -40, 0, -20, 1.4f, 0, 0, 0);
        def.anim("special", buzz);
        return only(def, "idle", "walk", "run", "attack", "attack2", "special", "death", "hurt");
    }

    /**
     * Flesh atronach: a hulking golem of stitched pale flesh held together with rusted iron bands and leather straps,
     * a sack-like sewn head and a glowing sigil plate set in its chest.
     */
    static CreatureDef fleshAtronach() {
        Biped b = new Biped("flesh_atronach");
        b.legLen = 13;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 4;
        b.torsoW = 14;
        b.torsoH = 14;
        b.torsoD = 9;
        b.hunch = 18;
        b.armLen = 18;
        b.armW = 5;
        b.handSize = 6;
        b.headW = 6;
        b.headH = 6;
        b.headD = 6;
        b.headForward = 3;
        b.skin = "flesh";
        b.headMat = "flesh_head";
        b.handMat = "flesh_dark";
        b.footMat = "flesh_dark";
        b.hipMat = "leather";
        b.scale = 1.05f;
        b.stride = 22;
        b.build();
        Bone t = b.torso;
        t.box("iron", -3.5f, -11, -5.2f, 7, 6, 1).tag("sigil_plate");
        t.box("sigil", -1.5f, -10, -5.6f, 3, 4, 1).tag("sigil");
        t.box("iron", -7.5f, -5, -5, 15, 2, 10).tag("band");
        t.box("iron", -4, -15, -3.5f, 8, 2, 7).tag("collar");
        t.box("leather", -7.2f, -14, -4.8f, 3, 13, 1).tag("strap");
        t.box("leather", 4.2f, -14, 3.8f, 3, 13, 1).tag("strap");
        t.box("flesh_dark", -4, -13, 4, 8, 6, 2).tag("hump");
        t.box("iron", -0.5f, -15.5f, 4.5f, 1, 2, 1).tag("nail");
        b.head.box("iron", -3.5f, -2, -4, 7, 1, 7).tag("head_band");
        b.head.box("flesh_dark", -2, -7, -2, 4, 1, 4).tag("knot");
        b.hips.box("leather", -5.5f, -1, -5, 11, 6, 1).tag("apron");
        for (Bone arm : b.arms) arm.sbox("iron", -3, -3, -3, 6, 3, 6).tag("pauldron");
        for (Bone fore : b.forearms) fore.sbox("iron", -2.5f, 2, -2.5f, 5, 3, 5, 0.1f).tag("cuff");
        for (Bone leg : b.legs) leg.sbox("iron", -3, 3, -3, 6, 2, 6).tag("cuff");
        CreatureDef def = b.def();
        Anim idle = def.get("idle");
        idle.scale("sigil", 0, 1, 1, 1, 1.75f, 1.15f, 1.15f, 1, 3.5f, 1, 1, 1);
        idle.swingN("head", 2, 4f, 0f, 1);
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "special", "death", "hurt");
    }

    // ================================================================================================ Apocrypha

    private static final int SEEKER_STRIPS = 8;

    /**
     * Seeker: Hermaeus Mora's floating servant. A hooded robe of dark green tatters, a face that is nothing but a mass
     * of tentacles around a faint eldritch glow, long-fingered hands, a pair of lesser tentacle arms and more tentacles
     * trailing beneath the hem.
     */
    static CreatureDef seeker() {
        Rig r = new Rig("seeker");
        Bone body = r.root().child("body", 0, -18, 0);
        body.box("robe", -3.5f, -1, -2.5f, 7, 4, 5).tag("waist");
        body.box("robe_trim", -4, -1.5f, -3, 8, 1, 6).tag("belt");
        Bone skirt = body.child("skirt", 0, 3, 0);
        skirt.box("robe", -4, 0, -3, 8, 4, 6).tag("robe");
        Bone low = skirt.child("skirt_low", 0, 3.5f, 0.3f);
        low.box("robe_dark", -4.5f, 0, -3.5f, 9, 3, 7).tag("hem");
        // ragged strips hanging from the hem: 1-3 front, 4 right, 5 left, 6-8 back
        float[][] strips = {{-3.8f, -3.7f, 0}, {-1, -3.8f, 0}, {1.8f, -3.7f, 0}, {-4.7f, -0.5f, 1}, {3.7f, 0.5f, 1},
                {-3.2f, 2.7f, 0}, {-0.5f, 2.8f, 0}, {2.2f, 2.7f, 0}};
        for (int i = 0; i < strips.length; i++) {
            float[] sp = strips[i];
            Bone st = low.child("strip" + (i + 1), sp[0], 2.5f, sp[1]);
            int len = 3 + (i * 5) % 3;
            if (sp[2] == 1) st.box("robe_rag", 0, 0, 0, 1, len, 2).tag("rag");
            else st.box("robe_rag", 0, 0, 0, 2, len, 1).tag("rag");
        }
        float[][] tp = {{-2, -1.5f}, {2, -1.5f}, {-2, 1.5f}, {2, 1.5f}};
        Bone[][] tent = new Bone[tp.length][];
        for (int i = 0; i < tp.length; i++) {
            tent[i] = chain(low, "tentacle" + (i + 1) + "_", tp[i][0], 2.5f, tp[i][1], "seeker_flesh", 3, 3, 2, 1, i < 2 ? -12 : 12, i < 2 ? -10 : 10);
        }
        Bone chest = body.child("chest", 0, -1, 0);
        chest.box("robe", -4, -9, -2.5f, 8, 9, 5).tag("chest");
        chest.box("robe_dark", -5, -9.5f, -3, 10, 3, 6).tag("mantle");
        chest.box("robe_trim", -1.5f, -8.5f, -2.8f, 3, 9, 1).tag("panel");
        Bone cape = chest.child("cape", 0, -9, 2.4f).rot(10, 0, 0);
        cape.box("robe_dark", -4.5f, 0, 0, 9, 16, 1).tag("cape");
        Bone head = chest.child("head", 0, -9.5f, -0.5f);
        head.box("robe_dark", -3, -7, -3, 6, 7, 6).tag("hood");
        head.box("robe_dark", -1.5f, -8.5f, -1, 3, 2, 4).tag("hood_peak");
        head.box("robe_dark", -3.5f, -1, -3.5f, 7, 2, 7).tag("cowl");
        head.box("seeker_face", -2.5f, -6, -3.3f, 5, 5, 1).tag("face");
        Bone[][] face = new Bone[5][];
        for (int k = 0; k < 5; k++) {
            face[k] = chain(head, "face_tentacle" + (k + 1) + "_", -2 + k, -1.5f, -3, "seeker_flesh", 2, 3, 1, 1, -10, 12);
        }
        chest.pair("arm", 5, -8, 0, (b, s) -> {
            b.rot(-5, 0, 8);
            b.sbox("robe", -1.5f, -1, -1.5f, 3, 6, 3).tag("sleeve");
            Bone fore = b.child("forearm", 0, 5, 0).rot(-25, 0, 0);
            fore.sbox("robe_dark", -2, 0, -2, 4, 5, 4).tag("bell_sleeve");
            Bone hand = fore.child("hand", 0, 5, 0);
            hand.sbox("seeker_flesh", -1, -0.5f, -1, 2, 2, 2).tag("hand");
            hand.sbox("seeker_claw", -1, 1.5f, -1, 1, 4, 1).tag("finger");
            hand.sbox("seeker_claw", 0, 1.5f, -1, 1, 4, 1).tag("finger");
            hand.sbox("seeker_claw", -0.5f, 1.5f, 0, 1, 3, 1).tag("finger");
        });
        chest.pair("arm_low", 4, -4.5f, -0.5f, (b, s) -> {
            b.rot(-20, 0, 25);
            b.sbox("seeker_flesh", -0.5f, 0, -0.5f, 1, 5, 1).tag("tentacle_arm");
            b.child("arm_low_tip", 0, 5, 0).rot(-30, 0, 0).sbox("seeker_flesh", -0.5f, 0, -0.5f, 1, 5, 1).tag("tentacle_arm");
        });

        CreatureDef def = new CreatureDef(r);
        def.gait(0.8f, 0.6f, 0.7f);
        def.look("head", 0.9f);
        def.anim("idle", seekerFloat(Anim.loop(4f), 0, 1f, tent, face));
        def.anim("walk", seekerFloat(Anim.loop(2.4f), 14, 1.3f, tent, face));

        // lash: lunges, both hands rake while the tentacles thrust forward (hit ~0.4 s)
        Anim lash = Anim.once(0.8f);
        lash.pos("body", 0, 0, 0, 0, 0.25f, 0, 1, 1.5f, 0.4f, 0, -0.5f, -3, 0.8f, 0, 0, 0);
        lash.rot("chest", 0, 0, 0, 0, 0.25f, -12, 0, 0, 0.4f, 15, 0, 0, 0.8f, 0, 0, 0);
        lash.rot("arm_right", 0, 0, 0, 0, 0.25f, -140, 20, 35, 0.4f, -50, -25, -10, 0.8f, 0, 0, 0);
        lash.rot("arm_left", 0, 0, 0, 0, 0.3f, -140, -20, -35, 0.45f, -50, 25, 10, 0.8f, 0, 0, 0);
        rotLR(lash, "arm_low", 0, 0, 0, 0, 0.25f, -20, 0, 40, 0.4f, -80, 0, 0, 0.8f, 0, 0, 0);
        for (int k = 0; k < 5; k++) lash.rot(face[k][0].name, 0, 0, 0, 0, 0.3f, -40, 0, (k - 2) * 12, 0.45f, -60, 0, (k - 2) * 15, 0.8f, 0, 0, 0);
        def.anim("attack", lash);
        // cast: hands brought together and thrust forward, the face tentacles flaring open (hit ~0.5 s)
        Anim cast = Anim.once(1.0f);
        rotLR(cast, "arm", 0, 0, 0, 0, 0.3f, -60, 0, 50, 0.5f, -95, -25, 0, 0.75f, -90, -20, 0, 1.0f, 0, 0, 0);
        rotLR(cast, "forearm", 0, 0, 0, 0, 0.3f, -40, 0, 0, 0.5f, 15, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("chest", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.5f, 8, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("head", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.5f, 6, 0, 0, 1.0f, 0, 0, 0);
        for (int k = 0; k < 5; k++) cast.rot(face[k][0].name, 0, 0, 0, 0, 0.3f, -50, 0, (k - 2) * 20, 0.75f, -50, 0, (k - 2) * 20, 1.0f, 0, 0, 0);
        for (int i = 1; i <= SEEKER_STRIPS; i++) cast.rot("strip" + i, 0, 0, 0, 0, 0.5f, 30, 0, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);
        // blink: whirls on the spot and folds into itself, then unfolds elsewhere
        Anim blink = Anim.once(0.8f);
        blink.linear().rot("body", 0, 0, 0, 0, 0.4f, 0, 540, 0, 0.8f, 0, 720, 0).smooth();
        blink.scale("body", 0, 1, 1, 1, 0.35f, 0.25f, 1.3f, 0.25f, 0.45f, 0.25f, 1.3f, 0.25f, 0.8f, 1, 1, 1);
        rotLR(blink, "arm", 0, 0, 0, 0, 0.2f, -30, 0, 70, 0.6f, -30, 0, 70, 0.8f, 0, 0, 0);
        def.anim("special", blink);
        // death: the hood sags, the body sinks and dissolves into itself
        Anim death = Anim.once(1.2f);
        death.pos("body", 0, 0, 0, 0, 0.9f, 0, -12, 0, 1.2f, 0, -13, 0);
        death.scale("body", 0, 1, 1, 1, 0.8f, 0.9f, 0.7f, 0.9f, 1.2f, 0.6f, 0.4f, 0.6f);
        death.rot("chest", 0, 0, 0, 0, 0.6f, 35, 0, 10, 1.2f, 45, 0, 10);
        death.rot("head", 0, 0, 0, 0, 0.6f, 35, 20, 0, 1.2f, 40, 20, 0);
        rotLR(death, "arm", 0, 0, 0, 0, 0.6f, -10, 0, 30, 1.2f, -5, 0, 40);
        for (Bone[] tc : tent) for (int j = 0; j < tc.length; j++) death.rot(tc[j].name, 0, 0, 0, 0, 1.0f, 30, 0, 0, 1.2f, 40, 0, 0);
        def.anim("death", death);
        Anim hurt = Anim.once(0.45f);
        hurt.rot("chest", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.45f, 0, 0, 0);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.45f, 0, 0, 0);
        hurt.pos("body", 0, 0, 0, 0, 0.1f, 0, 0, 1.5f, 0.45f, 0, 0, 0);
        def.anim("hurt", hurt);
        return def;
    }

    /** Seeker hover loop: bobbing, robe and strips streaming back by {@code lean}, tentacles writhing. */
    private static Anim seekerFloat(Anim a, float lean, float amp, Bone[][] tent, Bone[][] face) {
        a.bob("body", 1, amp, 0, 1);
        a.swing("body", 2, 2f, 0.2f);
        if (lean > 0) {
            a.hold("body", lean * 0.6f, 0, 0);
            a.hold("arm_right", lean * 0.8f, 0, 0);
            a.hold("arm_left", lean * 0.8f, 0, 0);
        }
        a.swing("skirt", 0, 3f * amp, 0.1f, lean * 0.4f);
        a.swing("skirt_low", 0, 4f * amp, 0f, lean * 0.5f);
        for (int i = 1; i <= SEEKER_STRIPS; i++) {
            a.wave("strip" + i, Anim.Target.ROTATION, 0, 8 * amp, i * 0.13f, lean * 1.2f, 2);
            a.wave("strip" + i, Anim.Target.ROTATION, 2, 5 * amp, i * 0.21f + 0.3f, 0, 1);
        }
        for (int i = 0; i < tent.length; i++) {
            sway(a, tent[i], 0, 10 * amp, i * 0.27f, lean);
            sway(a, tent[i], 2, 8 * amp, i * 0.31f + 0.2f, 0);
        }
        for (int k = 0; k < face.length; k++) sway(a, face[k], 0, 9f, k * 0.19f, 0);
        a.wave("cape", Anim.Target.ROTATION, 0, 3 * amp, 0.15f, lean * 0.9f, 1);
        a.swing("arm_right", 2, 4f, 0f);
        a.swing("arm_left", 2, -4f, 0.1f);
        a.swing("forearm_right", 0, 6f, 0.2f);
        a.swing("forearm_left", 0, 6f, 0.6f);
        a.swingN("arm_low_right", 2, 12f, 0f, 2);
        a.swingN("arm_low_left", 2, -12f, 0.25f, 2);
        a.swingN("arm_low_tip_right", 0, 20f, 0.1f, 2);
        a.swingN("arm_low_tip_left", 0, 20f, 0.35f, 2);
        a.swing("head", 1, 10f, 0.25f);
        a.swing("chest", 0, 2f, 0.4f);
        return a;
    }

    /**
     * Lurker: Apocrypha's hulking brute. A hunched green-black hide with a spined hump, ichor-weeping wounds, a crested
     * head with a beard of tentacles round its jaws, enormous clawed arms and digitigrade legs.
     */
    static CreatureDef lurker() {
        Biped b = new Biped("lurker");
        b.legLen = 16;
        b.legW = 6;
        b.hipW = 12;
        b.hipH = 5;
        b.torsoW = 16;
        b.torsoH = 16;
        b.torsoD = 10;
        b.hunch = 35;
        b.armLen = 22;
        b.armW = 6;
        b.handSize = 6;
        b.headW = 8;
        b.headH = 7;
        b.headD = 7;
        b.headForward = 6;
        b.jaw = true;
        b.jawH = 3;
        b.digitigrade = true;
        b.skin = "lurker_hide";
        b.headMat = "lurker_head";
        b.handMat = "lurker_dark";
        b.footMat = "lurker_dark";
        b.hipMat = "lurker_dark";
        b.scale = 1.35f;
        b.stride = 20;
        b.build();
        Bone t = b.torso;
        t.box("lurker_belly", -6, -14, -5.6f, 12, 13, 1).tag("belly");
        t.box("lurker_hide", -7, -18, 1, 14, 9, 7).tag("hump");
        int n = 0;
        for (int i = 0; i < 3; i++) {
            for (int sd = -1; sd <= 1; sd += 2) {
                n++;
                Bone sp = t.child("spine" + n, sd * 4, -17 + i * 4, 6.5f).rot(-50 + i * 12, 0, sd * 20);
                sp.box("lurker_spine", -1, -5 + i, -1, 2, 5 - i, 2).tag("spine");
            }
        }
        Bone h = b.head;
        h.box("lurker_dark", -4.5f, -8, -3, 9, 2, 6).tag("crest");
        h.box("lurker_spine", -0.5f, -10.5f, -2, 1, 3, 5).tag("crest_fin");
        h.pair("brow_horn", 3.5f, -7, -2.5f, (hb, s) -> {
            hb.rot(-20, 0, -45);
            hb.sbox("lurker_spine", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn");
        });
        Bone[][] beard = new Bone[5][];
        for (int k = 0; k < 5; k++) {
            beard[k] = chain(b.jawBone, "beard" + (k + 1) + "_", -2.4f + k * 1.2f, 2.5f, -4.5f + Math.abs(k - 2) * 0.8f,
                    "lurker_tentacle", 3, 3, 2, 1, -10, 10);
        }
        for (Bone arm : b.arms) {
            arm.sbox("lurker_dark", -4, -4, -4, 8, 5, 8).tag("shoulder");
            arm.child("shoulder_spine", -2, -4, 1).rot(-20, 0, -35).sbox("lurker_spine", -1, -5, -1, 2, 5, 2).tag("spine");
        }
        for (Bone fore : b.forearms) {
            fore.child("forearm_spine", 2, 3, 2).rot(30, 0, 30).sbox("lurker_spine", -0.5f, -4, -0.5f, 1, 4, 1).tag("spine");
        }
        for (Bone hand : b.hands) {
            hand.sbox("lurker_claw", -3, 5, -3, 1, 4, 1).tag("claw");
            hand.sbox("lurker_claw", -0.5f, 5, -3, 1, 5, 1).tag("claw");
            hand.sbox("lurker_claw", 2, 5, -3, 1, 4, 1).tag("claw");
        }
        for (Bone foot : b.feet) foot.sbox("lurker_claw", -3, 0.5f, -5.5f, 6, 1, 1).tag("claw");

        CreatureDef def = b.def();
        for (String slot : new String[]{"idle", "walk", "run"}) {
            Anim a = def.get(slot);
            for (int k = 0; k < beard.length; k++) sway(a, beard[k], 0, slot.equals("idle") ? 8f : 12f, k * 0.2f, slot.equals("run") ? 20 : 0);
            for (int k = 0; k < beard.length; k++) sway(a, beard[k], 2, 6f, k * 0.17f + 0.3f, 0);
        }
        // ink spit: the head lunges, jaws and tentacles flare (hit ~0.5 s)
        Anim spit = Anim.once(1.0f);
        spit.rot("torso", 0, 0, 0, 0, 0.35f, -15, 0, 0, 0.5f, 10, 0, 0, 1.0f, 0, 0, 0);
        spit.rot("head", 0, 0, 0, 0, 0.35f, -25, 0, 0, 0.5f, 10, 0, 0, 1.0f, 0, 0, 0);
        spit.rot("jaw", 0, 0, 0, 0, 0.35f, 20, 0, 0, 0.5f, 45, 0, 0, 0.75f, 30, 0, 0, 1.0f, 0, 0, 0);
        for (int k = 0; k < beard.length; k++) spit.rot(beard[k][0].name, 0, 0, 0, 0, 0.35f, 0, 0, 0, 0.5f, -50, 0, (k - 2) * 15, 1.0f, 0, 0, 0);
        rotLR(spit, "arm", 0, 0, 0, 0, 0.35f, -20, 0, 25, 1.0f, 0, 0, 0);
        def.anim("cast", spit);
        Anim roar = def.get("special");
        for (int k = 0; k < beard.length; k++) roar.rot(beard[k][0].name, 0, 0, 0, 0, 0.35f, -40, 0, (k - 2) * 18, 1.1f, -40, 0, (k - 2) * 18, 1.5f, 0, 0, 0);
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "cast", "special", "death", "hurt");
    }

    // ================================================================================================ the Soul Cairn

    /**
     * Keeper: the Soul Cairn's towering guardian, a skeleton in ornate soul-iron plate: spiked pauldrons, a crested
     * open helm over a grinning skull with violet eyes, a soul gem burning in its breastplate, a long cloth tabard and
     * cape, and a great two-handed war axe.
     */
    static CreatureDef keeper() {
        Biped b = new Biped("keeper");
        b.legLen = 16;
        b.legW = 4;
        b.hipW = 8;
        b.hipH = 3;
        b.torsoW = 10;
        b.torsoH = 12;
        b.torsoD = 6;
        b.hunch = 8;
        b.armLen = 15;
        b.armW = 3;
        b.handSize = 3;
        b.headW = 5;
        b.headH = 6;
        b.headD = 5;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "keeper_plate";
        b.headMat = "keeper_skull";
        b.armMat = "keeper_bone";
        b.legMat = "keeper_bone";
        b.handMat = "keeper_bone";
        b.footMat = "keeper_plate";
        b.hipMat = "keeper_plate";
        b.scale = 1.15f;
        b.stride = 26;
        b.twoHanded = true;
        b.build();
        Bone t = b.torso;
        t.box("keeper_plate", -5.5f, -12, -3.6f, 11, 7, 1).tag("breastplate");
        t.box("soul", -1.5f, -10, -4, 3, 3, 1).tag("soul_gem");
        t.box("keeper_plate", -4, -13.5f, 0, 8, 2, 4).tag("collar");
        t.box("keeper_plate", -4.5f, -5, -3.4f, 9, 4, 7, 0.1f).tag("fauld");
        t.child("collar_spike_right", -3, -13, 2).rot(-25, 0, -20).box("keeper_spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("spike");
        t.child("collar_spike_left", 3, -13, 2).rot(-25, 0, 20).box("keeper_spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("spike");
        Bone cape = t.child("cape", 0, -12, 3.2f).rot(8, 0, 0);
        cape.box("keeper_cloth", -4.5f, 0, 0, 9, 22, 1).tag("cape");
        b.hips.box("keeper_cloth", -2.5f, -1, -3.6f, 5, 13, 1).tag("tabard");
        Bone h = b.head;
        h.box("keeper_plate", -3, -7, -3.5f, 6, 3, 6).tag("helm");
        h.box("keeper_plate", -3, -4, 0.5f, 6, 4, 2).tag("helm_back");
        h.box("keeper_plate", -3.2f, -4.5f, -3.2f, 1, 4, 3).tag("cheek_guard");
        h.box("keeper_plate", 2.2f, -4.5f, -3.2f, 1, 4, 3).tag("cheek_guard");
        h.child("crest", 0, -7, 0).rot(-10, 0, 0).box("keeper_spike", -0.5f, -4, -3, 1, 4, 7).tag("crest");
        h.pair("horn", 3, -6, -1, (hb, s) -> {
            hb.rot(30, 0, -60);
            hb.sbox("keeper_spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn");
            hb.child("horn_tip", 0, -4, 0).rot(30, 0, 25).sbox("keeper_spike", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn_tip");
        });
        for (Bone arm : b.arms) {
            arm.sbox("keeper_plate", -3, -3.5f, -3, 6, 4, 6, 0.1f).tag("pauldron");
            arm.sbox("keeper_plate", -3.2f, 0.5f, -2.5f, 1, 3, 5).tag("pauldron_lame");
            arm.child("pauldron_spike", -1.5f, -3.5f, -1).rot(-15, 0, -25).sbox("keeper_spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("spike");
            arm.child("pauldron_spike2", -1.5f, -3.5f, 1.5f).rot(-40, 0, -35).sbox("keeper_spike", -0.5f, -3, -0.5f, 1, 3, 1).tag("spike");
        }
        for (Bone fore : b.forearms) fore.sbox("keeper_plate", -2, 2.5f, -2, 4, 5, 4).tag("vambrace");
        for (Bone leg : b.legs) leg.sbox("keeper_plate", -2.5f, -1, -2.6f, 5, 7, 1).tag("tasset");
        for (Bone shin : b.shins) shin.sbox("keeper_plate", -2, 0, -2.4f, 4, 7, 4).tag("greave");
        b.forearms[0].rot(-45, 0, 0);
        b.forearms[1].rot(-35, 0, 0);
        // the great war axe, held in a forward guard: haft along -z, a broad crescent head at the far end
        Bone w = b.hands[0].child("weapon", 0, 1.5f, 0).rot(22, 0, 0);
        w.box("keeper_haft", -0.5f, -0.5f, -24, 1, 1, 31).tag("haft");
        w.box("keeper_plate", -1, -1, 6, 2, 2, 2).tag("pommel");
        w.box("keeper_plate", -1, -1, -16, 2, 2, 3).tag("collar");
        w.box("keeper_blade", -0.5f, -7, -26, 1, 8, 6).tag("axe_blade");
        w.box("keeper_blade", -0.5f, -8, -27, 1, 2, 3).tag("axe_horn");
        w.box("keeper_blade", -0.5f, 1, -25, 1, 3, 3).tag("axe_beard");
        w.box("keeper_blade", -0.5f, -0.5f, -29, 1, 1, 3).tag("spike");
        w.box("keeper_blade", -0.5f, 1, -21, 1, 2, 2).tag("back_spike");

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right", "forearm_left"));
        def.get("attack").rot("weapon_right", 0, 0, 0, 0, 0.5f, 30, 0, 0, 0.65f, 75, 0, 0, 0.9f, 60, 0, 0, 1.2f, 0, 0, 0);
        // sweep: the axe hauled round in a flat arc across the front (hit ~0.5 s)
        Anim sweep = Anim.once(1.0f);
        sweep.rot("torso", 0, 0, 0, 0, 0.35f, -5, 50, 0, 0.5f, 5, -45, 0, 0.75f, 3, -55, 0, 1.0f, 0, 0, 0);
        sweep.rot("arm_right", 0, 0, 0, 0, 0.35f, -70, 50, 40, 0.5f, -80, -40, 0, 0.75f, -60, -50, 0, 1.0f, 0, 0, 0);
        sweep.rot("arm_left", 0, 0, 0, 0, 0.35f, -60, 40, 10, 0.5f, -80, -30, 20, 0.75f, -60, -40, 20, 1.0f, 0, 0, 0);
        sweep.rot("weapon_right", 0, 0, 0, 0, 0.35f, 80, 0, 0, 0.5f, 105, 0, 0, 1.0f, 0, 0, 0);
        sweep.rot("leg_left", 0, 0, 0, 0, 0.5f, -20, 0, 0, 1.0f, 0, 0, 0);
        sweep.pos("hips", 0, 0, 0, 0, 0.5f, 0, -1, 0, 1.0f, 0, 0, 0);
        def.anim("attack2", sweep);
        Anim roar = def.get("special");
        roar.rot("weapon_right", 0, 0, 0, 0, 0.35f, -60, 0, 0, 1.1f, -60, 0, 0, 1.5f, 0, 0, 0);
        Anim idle = def.get("idle");
        idle.scale("soul_gem", 0, 1, 1, 1, 1.75f, 1.2f, 1.2f, 1, 3.5f, 1, 1, 1);
        idle.swing("cape", 0, 3f, 0.2f);
        Anim walk = def.get("walk");
        walk.swing("cape", 0, 6f, 0.1f, 8f);
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "special", "death", "hurt");
    }

    /**
     * Boneman: a Soul Cairn skeleton of violet-tinged bone in rusted scraps of armour. A gapped ribcage over the spine,
     * a grinning skull with soul-lit sockets. Variants: sword and shield (0) or bow and quiver (1).
     */
    static CreatureDef boneman() {
        Biped b = new Biped("boneman");
        b.legLen = 12;
        b.legW = 2;
        b.hipW = 6;
        b.hipH = 2;
        b.torsoW = 3;
        b.torsoH = 10;
        b.torsoD = 2;
        b.hunch = 6;
        b.armLen = 12;
        b.armW = 2;
        b.handSize = 2;
        b.headW = 6;
        b.headH = 6;
        b.headD = 6;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "bone";
        b.headMat = "skull";
        b.hipMat = "bone";
        b.stride = 30;
        b.build();
        b.arms[0].x = -4.5f;
        b.arms[1].x = 4.5f;
        Bone t = b.torso;
        t.box("ribs", -3.5f, -10, -2.5f, 7, 6, 4).tag("ribcage");
        t.box("bone", -4.5f, -10.5f, -1, 9, 1, 2).tag("clavicle");
        b.hips.box("bone", -3.5f, -3, -1.5f, 7, 2, 3).tag("pelvis");
        b.hips.box("rag", -3, -1, -1.8f, 6, 6, 1).tag("rag");
        b.hips.box("rag", -3, -1, 0.8f, 6, 5, 1).tag("rag");
        b.hips.box("rust_metal", -3.5f, -2.5f, -2, 7, 1, 4, 0.1f).tag("belt");
        b.head.box("rust_metal", -3.5f, -6.6f, -4, 7, 3, 7).tag("helm");
        b.head.box("rust_metal", -0.5f, -7.6f, -4, 1, 1, 7).tag("helm_ridge");
        b.head.box("rust_metal", -0.5f, -4, -4.3f, 1, 3, 1).tag("nasal");
        b.arms[1].sbox("rust_metal", -2, -2.5f, -2, 4, 3, 4, 0.1f).tag("pauldron");
        for (Bone shin : b.shins) shin.sbox("bone", -1.5f, -1, -1.5f, 3, 2, 3).tag("knee");
        b.forearms[0].rot(-40, 0, 0);
        b.forearms[1].rot(-20, 0, 0);
        // variant 0: rusted sword and a battered round shield
        Bone sw = b.hands[0].child("sword", 0, 1, 0).rot(68, 0, 0);
        sw.box("leather", -0.5f, -0.5f, -1, 1, 1, 3).tag("grip");
        sw.box("rust_metal", -1.5f, -0.5f, -2, 3, 1, 1).tag("guard");
        sw.box("blade", -0.5f, -1, -12, 1, 2, 10).tag("blade");
        sw.box("blade", -0.5f, -0.5f, -13, 1, 1, 1).tag("blade_tip");
        Bone shield = b.forearms[1].child("shield", -1.5f, 3, 0);
        shield.sbox("rust_shield", -1, -4, -4, 1, 8, 8).tag("shield");
        shield.sbox("rust_metal", -2, -1, -1, 1, 2, 2).tag("boss");
        // variant 1: a bone-and-sinew bow and a quiver
        Bone bow = b.hands[1].child("bow", 0, 1, -0.5f);
        bow.sbox("bow_wood", -0.5f, -2, -0.5f, 1, 4, 1).tag("grip");
        bow.child("bow_upper", 0, -2, 0).rot(-25, 0, 0).sbox("bow_wood", -0.5f, -7, -0.5f, 1, 7, 1).tag("limb");
        bow.child("bow_lower", 0, 2, 0).rot(25, 0, 0).sbox("bow_wood", -0.5f, 0, -0.5f, 1, 7, 1).tag("limb");
        bow.sbox("string", -0.5f, -8, 2.9f, 1, 16, 0).tag("string");
        Bone quiver = t.child("quiver", 1.5f, -9, 3).rot(0, 0, 20);
        quiver.box("leather", -1, 0, -1, 2, 8, 2).tag("quiver");
        quiver.box("feather", -1, -2, -1, 2, 2, 2).tag("fletching");

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right", "forearm_left"));
        Anim idle = def.get("idle");
        idle.linear().rot("jaw", 0, 0, 0, 0, 2.6f, 0, 0, 0, 2.7f, 14, 0, 0, 2.8f, 0, 0, 0, 2.9f, 14, 0, 0, 3.0f, 0, 0, 0, 3.5f, 0, 0, 0).smooth();
        // slash: the sword cuts down and across (hit ~0.4 s)
        Anim slash = Anim.once(0.75f);
        slash.rot("arm_right", 0, 0, 0, 0, 0.25f, -150, 20, 30, 0.4f, -50, -35, -10, 0.55f, -30, -25, 0, 0.75f, 0, 0, 0);
        slash.rot("forearm_right", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.4f, 25, 0, 0, 0.75f, 0, 0, 0);
        slash.rot("sword_right", 0, 0, 0, 0, 0.25f, -50, 0, 0, 0.4f, 20, 0, 0, 0.55f, 15, 0, 0, 0.75f, 0, 0, 0);
        slash.rot("torso", 0, 0, 0, 0, 0.25f, -5, 30, 0, 0.4f, 8, -30, 0, 0.75f, 0, 0, 0);
        slash.rot("arm_left", 0, 0, 0, 0, 0.25f, -30, 0, -15, 0.4f, -10, 0, -10, 0.75f, 0, 0, 0);
        slash.rot("jaw", 0, 0, 0, 0, 0.25f, 20, 0, 0, 0.5f, 5, 0, 0, 0.75f, 0, 0, 0);
        def.anim("attack", slash);
        // shield bash / bow-butt jab with the left arm (hit ~0.32 s)
        Anim bash = Anim.once(0.65f);
        bash.rot("arm_left", 0, 0, 0, 0, 0.2f, -30, 30, -20, 0.32f, -80, -10, 0, 0.45f, -75, -5, 0, 0.65f, 0, 0, 0);
        bash.rot("torso", 0, 0, 0, 0, 0.2f, -5, -20, 0, 0.32f, 10, 20, 0, 0.65f, 0, 0, 0);
        bash.pos("hips", 0, 0, 0, 0, 0.2f, 0, 0, 1, 0.32f, 0, 0, -2.5f, 0.65f, 0, 0, 0);
        bash.rot("leg_left", 0, 0, 0, 0, 0.32f, -25, 0, 0, 0.65f, 0, 0, 0);
        def.anim("attack2", bash);
        // bow: raised in the left hand, the right draws to the cheek and looses (release ~0.6 s)
        Anim draw = Anim.once(1.1f);
        draw.rot("arm_left", 0, 0, 0, 0, 0.3f, -85, -20, 0, 0.9f, -85, -20, 0, 1.1f, 0, 0, 0);
        draw.rot("forearm_left", 0, 0, 0, 0, 0.3f, 20, 0, 0, 0.9f, 20, 0, 0, 1.1f, 0, 0, 0);
        draw.rot("bow_left", 0, 0, 0, 0, 0.3f, 85, 0, 0, 0.9f, 85, 0, 0, 1.1f, 0, 0, 0);
        draw.rot("arm_right", 0, 0, 0, 0, 0.3f, -85, 20, 0, 0.58f, -90, 50, 0, 0.62f, -85, 30, 0, 0.9f, -85, 30, 0, 1.1f, 0, 0, 0);
        draw.rot("forearm_right", 0, 0, 0, 0, 0.3f, -40, 0, 0, 0.58f, -110, 0, 0, 0.62f, -60, 0, 0, 0.9f, -60, 0, 0, 1.1f, 0, 0, 0);
        draw.rot("torso", 0, 0, 0, 0, 0.3f, 0, -35, 0, 0.9f, 0, -35, 0, 1.1f, 0, 0, 0);
        draw.rot("head", 0, 0, 0, 0, 0.3f, 0, 30, 0, 0.9f, 0, 30, 0, 1.1f, 0, 0, 0);
        def.anim("cast", draw);
        // death: the bones come apart and clatter down
        Anim death = Anim.once(1.0f);
        death.pos("hips", 0, 0, 0, 0, 0.5f, 0, -7, 0, 0.8f, 0, -11, 0, 1f, 0, -11, 0);
        death.rot("torso", 0, 0, 0, 0, 0.5f, 30, 0, 10, 0.8f, 70, 0, 20, 1f, 75, 0, 20);
        death.pos("head", 0, 0, 0, 0, 0.5f, 0, 0, 0, 0.8f, 3, -3, -2, 1f, 4, -4, -2);
        death.rot("head", 0, 0, 0, 0, 0.8f, 40, 30, 60, 1f, 60, 40, 80);
        death.rot("jaw", 0, 0, 0, 0, 0.6f, 35, 0, 0, 1f, 35, 0, 0);
        rotLR(death, "arm", 0, 0, 0, 0, 0.6f, -40, 0, 30, 1f, -60, 0, 50);
        rotLR(death, "leg", 0, 0, 0, 0, 0.5f, -50, 0, 10, 1f, -80, 0, 15);
        rotLR(death, "shin", 0, 0, 0, 0, 0.5f, 90, 0, 0, 1f, 100, 0, 0);
        def.anim("death", death);
        return only(def, "idle", "walk", "run", "attack", "attack2", "cast", "death", "hurt");
    }

    /**
     * Mistman: a wraith of the Soul Cairn's mists: a gaunt pale torso and hooded head with burning frost eyes, long
     * clawed arms trailing wisps, and a coiling tail of fog where its legs should be.
     */
    static CreatureDef mistman() {
        Rig r = new Rig("mistman");
        Bone body = r.root().child("body", 0, -14, 0);
        body.box("mist", -3, -9, -2, 6, 9, 4).tag("torso");
        body.box("mist_dark", -3.5f, -9.5f, -2.5f, 7, 3, 5).tag("shoulders");
        body.box("mist_wisp", -3, 0, 2, 6, 6, 0).tag("streamer");
        Bone head = body.child("head", 0, -9.5f, -0.5f);
        head.box("mist", -2.5f, -6, -2.5f, 5, 6, 5).tag("head");
        head.box("mist_dark", -3, -6.5f, -1, 6, 4, 5).tag("hood");
        head.box("mist", -1.5f, -1.5f, -3, 3, 2, 1).tag("jaw");
        Bone hair = head.child("hair", 0, -5, 2.5f).rot(50, 0, 0);
        hair.box("mist_wisp", -2.5f, 0, 0, 5, 8, 0).tag("streamer");
        Bone hairTip = hair.child("hair_tip", 0, 8, 0).rot(20, 0, 0);
        hairTip.box("mist_wisp", -2, 0, 0, 4, 6, 0).tag("streamer");
        body.pair("arm", 4, -8.5f, 0, (b, s) -> {
            b.rot(-10, 0, 15);
            b.sbox("mist", -1, -1, -1, 2, 7, 2).tag("arm");
            Bone fore = b.child("forearm", 0, 6, 0).rot(-30, 0, 0);
            fore.sbox("mist", -1, 0, -1, 2, 6, 2).tag("forearm");
            fore.sbox("mist_wisp", 0, 1, 1, 0, 6, 4).tag("streamer");
            Bone hand = fore.child("hand", 0, 6, 0);
            hand.sbox("mist_claw", -1, -0.5f, -1, 2, 2, 2).tag("hand");
            hand.sbox("mist_claw", -1, 1.5f, -1, 1, 4, 1).tag("claw");
            hand.sbox("mist_claw", 0, 1.5f, -1, 1, 4, 1).tag("claw");
            hand.sbox("mist_claw", -0.5f, 1.5f, 0, 1, 3, 1).tag("claw");
        });
        Bone[] tail = chain(body, "tail", 0, 0, 0.5f, "mist", 5, 4, 5, 1, 20, 12);
        for (int i = 0; i < 3; i++) tail[i].box("mist_wisp", 0, 0, 1, 0, 4, 4).tag("streamer");

        CreatureDef def = new CreatureDef(r);
        def.gait(0.8f, 0.6f, 0.7f);
        def.look("head", 0.9f);
        def.anim("idle", mistDrift(Anim.loop(3.6f), tail, 0, 1f));
        def.anim("walk", mistDrift(Anim.loop(2.2f), tail, 16, 1.4f));
        // claw: rears back and rakes with both hands (hit ~0.38 s)
        Anim claw = Anim.once(0.75f);
        claw.rot("body", 0, 0, 0, 0, 0.25f, -15, 0, 0, 0.38f, 18, 0, 0, 0.75f, 0, 0, 0);
        claw.pos("body", 0, 0, 0, 0, 0.25f, 0, 1, 1.5f, 0.38f, 0, -0.5f, -3, 0.75f, 0, 0, 0);
        claw.rot("arm_right", 0, 0, 0, 0, 0.25f, -160, 20, 30, 0.38f, -40, -20, -10, 0.75f, 0, 0, 0);
        claw.rot("arm_left", 0, 0, 0, 0, 0.3f, -160, -20, -30, 0.44f, -40, 20, 10, 0.75f, 0, 0, 0);
        claw.rot("head", 0, 0, 0, 0, 0.25f, -20, 0, 0, 0.38f, 10, 0, 0, 0.75f, 0, 0, 0);
        def.anim("attack", claw);
        // frost: both hands pushed forward, cold gathering between them (hit ~0.5 s)
        Anim cast = Anim.once(1.0f);
        rotLR(cast, "arm", 0, 0, 0, 0, 0.3f, -40, 0, 50, 0.5f, -95, -25, 0, 0.75f, -90, -20, 0, 1.0f, 0, 0, 0);
        rotLR(cast, "forearm", 0, 0, 0, 0, 0.3f, -50, 0, 0, 0.5f, 25, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("body", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.5f, 8, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("head", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.5f, 5, 0, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);
        // wail: head back, arms flung wide
        Anim wail = Anim.once(1.4f);
        wail.rot("body", 0, 0, 0, 0, 0.35f, -15, 0, 0, 1.0f, -15, 0, 0, 1.4f, 0, 0, 0);
        wail.rot("head", 0, 0, 0, 0, 0.35f, -35, 0, 0, 1.0f, -30, 0, 0, 1.4f, 0, 0, 0);
        rotLR(wail, "arm", 0, 0, 0, 0, 0.35f, -30, 0, 75, 1.0f, -35, 0, 80, 1.4f, 0, 0, 0);
        wail.scale("hair", 0, 1, 1, 1, 0.35f, 1.3f, 1.4f, 1, 1.0f, 1.3f, 1.4f, 1, 1.4f, 1, 1, 1);
        def.anim("special", wail);
        // death: unravels into mist
        Anim death = Anim.once(1.2f);
        death.pos("body", 0, 0, 0, 0, 1.2f, 0, -6, 0);
        death.scale("body", 0, 1, 1, 1, 0.5f, 1.15f, 0.9f, 1.15f, 1.2f, 0.05f, 0.05f, 0.05f);
        death.rot("head", 0, 0, 0, 0, 0.5f, -30, 0, 0, 1.2f, 20, 0, 0);
        rotLR(death, "arm", 0, 0, 0, 0, 0.5f, -40, 0, 60, 1.2f, -20, 0, 30);
        def.anim("death", death);
        Anim hurt = Anim.once(0.45f);
        hurt.rot("body", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.45f, 0, 0, 0);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.45f, 0, 0, 0);
        hurt.pos("body", 0, 0, 0, 0, 0.1f, 0, 0, 1.5f, 0.45f, 0, 0, 0);
        def.anim("hurt", hurt);
        return def;
    }

    /** Mist loop: bobbing, the fog tail coiling, arms adrift, hair streaming by {@code lean}. */
    private static Anim mistDrift(Anim a, Bone[] tail, float lean, float amp) {
        a.bob("body", 1, amp, 0, 1);
        a.swing("body", 2, 2.5f, 0.2f);
        if (lean > 0) {
            a.hold("body", lean, 0, 0);
            a.hold("arm_right", lean * 1.2f, 0, 0);
            a.hold("arm_left", lean * 1.2f, 0, 0);
        }
        sway(a, tail, 1, 9 * amp, 0, 0);
        sway(a, tail, 0, 5 * amp, 0.25f, lean * 0.6f);
        a.swing("arm_right", 2, 6f, 0f);
        a.swing("arm_left", 2, -6f, 0.15f);
        a.swing("arm_right", 0, 6f, 0.3f);
        a.swing("arm_left", 0, 6f, 0.8f);
        a.swing("forearm_right", 0, 8f, 0.4f);
        a.swing("forearm_left", 0, 8f, 0.9f);
        a.swingN("hair", 0, 8f * amp, 0f, 2);
        a.swingN("hair_tip", 0, 12f * amp, -0.1f, 2);
        a.swing("head", 1, 10f, 0.25f);
        return a;
    }

    /**
     * Wrathman: a towering spectral knight of the Soul Cairn in heavy horned plate, wrath burning red-violet through
     * the visor and the cracks of its armour, a tattered cape and a broad greatsword.
     */
    static CreatureDef wrathman() {
        Biped b = new Biped("wrathman");
        b.legLen = 15;
        b.legW = 5;
        b.hipW = 9;
        b.hipH = 3;
        b.torsoW = 11;
        b.torsoH = 12;
        b.torsoD = 6;
        b.hunch = 8;
        b.armLen = 14;
        b.armW = 4;
        b.handSize = 3;
        b.headW = 6;
        b.headH = 7;
        b.headD = 6;
        b.skin = "wrath_plate";
        b.headMat = "wrath_helm";
        b.handMat = "wrath_plate";
        b.footMat = "wrath_plate";
        b.hipMat = "wrath_plate";
        b.legMat = "wrath_plate";
        b.scale = 1.15f;
        b.stride = 26;
        b.build();
        Bone t = b.torso;
        t.box("wrath_plate", -6, -12, -3.6f, 12, 7, 1).tag("breastplate");
        t.box("wrath_plate", -5, -5, -3.4f, 10, 4, 7, 0.1f).tag("fauld");
        t.box("wrath_plate", -4.5f, -13.5f, -1, 9, 2, 5).tag("gorget");
        Bone cape = t.child("cape", 0, -12, 3.2f).rot(8, 0, 0);
        cape.box("wrath_cloth", -5, 0, 0, 10, 20, 1).tag("cape");
        b.hips.box("wrath_cloth", -2.5f, -1, -3.6f, 5, 11, 1).tag("tabard");
        Bone h = b.head;
        h.box("wrath_helm", -3.5f, -7.5f, -4, 7, 2, 7).tag("helm_top");
        h.box("wrath_plate", -0.5f, -8.5f, -4, 1, 1, 7).tag("helm_ridge");
        h.pair("horn", 3.5f, -6.5f, -0.5f, (hb, s) -> {
            hb.rot(0, 30, -20);
            hb.sbox("wrath_horn", -4, -1, -1, 4, 2, 2).tag("horn");
            Bone mid = hb.child("horn_mid", -4, 0, 0).rot(0, 35, 30);
            mid.sbox("wrath_horn", -3, -0.5f, -0.5f, 3, 1, 1, 0.2f).tag("horn");
            mid.child("horn_tip", -3, 0, 0).rot(0, 20, 35).sbox("wrath_horn", -3, -0.5f, -0.5f, 3, 1, 1).tag("horn_tip");
        });
        for (Bone arm : b.arms) {
            arm.sbox("wrath_plate", -3.5f, -4, -3.5f, 7, 5, 7, 0.1f).tag("pauldron");
            arm.sbox("wrath_plate", -3.6f, 1, -3, 1, 3, 6).tag("pauldron_lame");
            arm.child("pauldron_spike", -2, -4, 0).rot(-10, 0, -40).sbox("wrath_horn", -0.5f, -4, -0.5f, 1, 4, 1).tag("spike");
        }
        for (Bone fore : b.forearms) fore.sbox("wrath_plate", -2.5f, 2.5f, -2.5f, 5, 4, 5, 0.1f).tag("vambrace");
        for (Bone leg : b.legs) leg.sbox("wrath_plate", -3, -1, -3.1f, 6, 7, 1).tag("tasset");
        for (Bone shin : b.shins) shin.sbox("wrath_plate", -2.5f, -1, -3, 5, 3, 1).tag("knee");
        b.forearms[0].rot(-40, 0, 0);
        b.forearms[1].rot(-15, 0, 0);
        Bone w = b.hands[0].child("weapon", 0, 1.5f, 0).rot(68, 0, 0);
        w.box("wrath_grip", -0.5f, -0.5f, -1.5f, 1, 1, 7).tag("grip");
        w.box("wrath_plate", -1, -1, 5.5f, 2, 2, 2).tag("pommel");
        w.box("wrath_plate", -1, -4, -2.5f, 2, 8, 1).tag("guard");
        w.box("wrath_blade", -0.5f, -2, -22.5f, 1, 4, 20).tag("blade");
        w.box("wrath_blade", -0.5f, -1, -24.5f, 1, 2, 2).tag("tip");

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right"));
        Anim slash = Anim.once(0.8f);
        slash.rot("arm_right", 0, 0, 0, 0, 0.3f, -150, 30, 30, 0.42f, -55, -40, -10, 0.6f, -35, -30, 0, 0.8f, 0, 0, 0);
        slash.rot("forearm_right", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.42f, 20, 0, 0, 0.8f, 0, 0, 0);
        slash.rot("weapon_right", 0, 0, 0, 0, 0.3f, -60, 0, 0, 0.42f, 20, 0, 0, 0.6f, 15, 0, 0, 0.8f, 0, 0, 0);
        slash.rot("arm_left", 0, 0, 0, 0, 0.3f, -130, -10, -10, 0.42f, -60, 30, 10, 0.6f, -30, 20, 0, 0.8f, 0, 0, 0);
        slash.rot("forearm_left", 0, 0, 0, 0, 0.3f, -30, 0, 0, 0.42f, -20, 0, 0, 0.8f, 0, 0, 0);
        slash.rot("torso", 0, 0, 0, 0, 0.3f, -6, 30, 0, 0.42f, 10, -35, 0, 0.8f, 0, 0, 0);
        slash.rot("leg_left", 0, 0, 0, 0, 0.42f, -20, 0, 0, 0.8f, 0, 0, 0);
        slash.swing("cape", 0, 10f, 0f);
        def.anim("attack", slash);
        Anim sweep = Anim.once(1.0f);
        sweep.rot("torso", 0, 0, 0, 0, 0.35f, -5, 50, 0, 0.5f, 5, -45, 0, 0.75f, 3, -55, 0, 1.0f, 0, 0, 0);
        sweep.rot("arm_right", 0, 0, 0, 0, 0.35f, -70, 60, 40, 0.5f, -80, -40, 0, 0.75f, -60, -50, 0, 1.0f, 0, 0, 0);
        sweep.rot("arm_left", 0, 0, 0, 0, 0.35f, -60, 50, 10, 0.5f, -80, -30, 20, 0.75f, -60, -40, 20, 1.0f, 0, 0, 0);
        sweep.rot("weapon_right", 0, 0, 0, 0, 0.35f, 30, 0, 0, 0.5f, 40, 0, 0, 1.0f, 0, 0, 0);
        sweep.rot("leg_left", 0, 0, 0, 0, 0.5f, -20, 0, 0, 1.0f, 0, 0, 0);
        sweep.pos("hips", 0, 0, 0, 0, 0.5f, 0, -1, 0, 1.0f, 0, 0, 0);
        def.anim("attack2", sweep);
        Anim over = Anim.once(1.1f);
        over.rot("arm_right", 0, 0, 0, 0, 0.5f, -170, 15, 0, 0.65f, -60, 5, 0, 0.85f, -55, 5, 0, 1.1f, 0, 0, 0);
        over.rot("forearm_right", 0, 0, 0, 0, 0.5f, 30, 0, 0, 0.85f, 30, 0, 0, 1.1f, 0, 0, 0);
        over.rot("weapon_right", 0, 0, 0, 0, 0.5f, -85, 0, 0, 0.65f, 30, 0, 0, 0.85f, 30, 0, 0, 1.1f, 0, 0, 0);
        over.rot("arm_left", 0, 0, 0, 0, 0.5f, -165, -25, 0, 0.65f, -65, -25, 0, 0.85f, -60, -20, 0, 1.1f, 0, 0, 0);
        over.rot("torso", 0, 0, 0, 0, 0.5f, -15, 0, 0, 0.65f, 25, 0, 0, 0.85f, 22, 0, 0, 1.1f, 0, 0, 0);
        over.rot("leg_right", 0, 0, 0, 0, 0.6f, -25, 0, 0, 0.9f, -25, 0, 0, 1.1f, 0, 0, 0);
        over.pos("hips", 0, 0, 0, 0, 0.5f, 0, 0.5f, 0, 0.65f, 0, -1.5f, 0, 0.9f, 0, -1.5f, 0, 1.1f, 0, 0, 0);
        def.anim("attack3", over);
        Anim idle = def.get("idle");
        idle.swing("cape", 0, 3f, 0.2f);
        Anim walk = def.get("walk");
        walk.swing("cape", 0, 6f, 0.1f, 8f);
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "special", "death", "hurt");
    }

    /** Wandering soul: a faint, drifting figure of a lost soul, head bowed, arms adrift, fading into a wisp below. */
    static CreatureDef wanderingSoul() {
        Rig r = new Rig("wandering_soul");
        Bone body = r.root().child("body", 0, -13, 0);
        body.box("soul", -2.5f, -8, -1.5f, 5, 8, 3).tag("torso");
        body.box("soul_shroud", -3, -9, -2, 6, 4, 4).tag("shroud");
        Bone head = body.child("head", 0, -9, -0.3f).rot(15, 0, 0);
        head.box("soul", -2, -5, -2, 4, 5, 4).tag("head");
        head.box("soul_shroud", -2.5f, -5.5f, -1, 5, 7, 4).tag("hair");
        body.pair("arm", 3.5f, -8, 0, (b, s) -> {
            b.rot(5, 0, 10);
            b.sbox("soul", -1, -0.5f, -1, 2, 6, 2).tag("arm");
            Bone fore = b.child("forearm", 0, 5.5f, 0).rot(-15, 0, 0);
            fore.sbox("soul", -1, 0, -1, 2, 5, 2).tag("forearm");
            fore.sbox("soul_shroud", -1, 0, 1, 2, 5, 0).tag("sleeve");
        });
        Bone[] tail = chain(body, "tail", 0, 0, 0, "soul_tail", 4, 4, 4, 1, 25, 12);

        CreatureDef def = new CreatureDef(r);
        def.gait(0.9f, 0.7f, 0.8f);
        def.look("head", 0.6f);
        def.anim("idle", soulDrift(Anim.loop(5f), tail, 0));
        def.anim("walk", soulDrift(Anim.loop(3.2f), tail, 14));
        Anim death = Anim.once(1.5f);
        death.pos("body", 0, 0, 0, 0, 1.5f, 0, 10, 0);
        death.scale("body", 0, 1, 1, 1, 0.6f, 1.1f, 1.2f, 1.1f, 1.5f, 0.05f, 0.4f, 0.05f);
        rotLR(death, "arm", 0, 0, 0, 0, 0.6f, -20, 0, 60, 1.5f, -20, 0, 80);
        death.rot("head", 0, 0, 0, 0, 0.6f, -40, 0, 0, 1.5f, -45, 0, 0);
        def.anim("death", death);
        Anim hurt = Anim.once(0.5f);
        hurt.pos("body", 0, 0, 0, 0, 0.12f, 0, 0, 2, 0.5f, 0, 0, 0);
        hurt.scale("body", 0, 1, 1, 1, 0.12f, 0.85f, 1.1f, 0.85f, 0.5f, 1, 1, 1);
        def.anim("hurt", hurt);
        return def;
    }

    private static Anim soulDrift(Anim a, Bone[] tail, float lean) {
        a.bob("body", 1, 1.2f, 0, 1);
        a.swing("body", 2, 3f, 0.2f);
        if (lean > 0) a.hold("body", lean, 0, 0);
        sway(a, tail, 1, 8f, 0, 0);
        sway(a, tail, 0, 6f, 0.3f, lean);
        a.swing("arm_right", 2, 5f, 0f);
        a.swing("arm_left", 2, -5f, 0.2f);
        a.swing("arm_right", 0, 5f, 0.4f, lean * 0.8f);
        a.swing("arm_left", 0, 5f, 0.9f, lean * 0.8f);
        a.swing("head", 1, 14f, 0.15f);
        a.swing("head", 0, 4f, 0.4f);
        return a;
    }

    // ================================================================================================ Sovngarde

    /**
     * Hero of Sovngarde: a shining Nord spirit in mail and gold-trimmed plate under a fur mantle. Variants share one
     * model and show their own gear: the warrior (horned helm, beard, axe), the shieldmaiden (braids, circlet, sword
     * and round shield) and the archer (hood, beard, bow and quiver).
     */
    static CreatureDef sovngardeHero() {
        Biped b = new Biped("sovngarde_hero");
        b.legLen = 13;
        b.legW = 4;
        b.hipW = 8;
        b.hipH = 3;
        b.torsoW = 9;
        b.torsoH = 11;
        b.torsoD = 5;
        b.hunch = 3;
        b.armLen = 12;
        b.armW = 4;
        b.handSize = 3;
        b.headW = 6;
        b.headH = 6;
        b.headD = 6;
        b.skin = "hero_mail";
        b.headMat = "hero_face";
        b.handMat = "hero_leather";
        b.footMat = "hero_leather";
        b.hipMat = "hero_leather";
        b.legMat = "hero_cloth";
        b.stride = 30;
        b.build();
        nordGear(b, "hero");
        Bone h = b.head;
        h.box("hero_hair", -3.3f, -6.4f, -2.2f, 7, 2, 7).tag("hair_top");
        h.box("hero_hair", -3.2f, -5, 1.6f, 6, 6, 2).tag("hair_back");
        // warrior: horned helm, beard and a bearded axe
        Bone helm = h.child("helm_horned", 0, 0, 0);
        helm.box("hero_steel", -3.5f, -6.8f, -4, 7, 3, 7).tag("helm");
        helm.box("hero_gold", -3.6f, -4.2f, -4.1f, 7, 1, 7).tag("helm_band");
        helm.box("hero_steel", -0.5f, -4, -4.4f, 1, 3, 1).tag("nasal");
        helm.pair("helm_horn", 3.5f, -5.5f, -0.5f, (hb, s) -> {
            hb.rot(0, 10, -55);
            hb.sbox("hero_horn", -1, -4, -1, 2, 4, 2).tag("horn");
            hb.child("helm_horn_tip", 0, -4, 0).rot(0, 0, 30).sbox("hero_horn", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn_tip");
        });
        Bone beard = h.child("beard", 0, -1, -3.5f).rot(-8, 0, 0);
        beard.box("hero_hair", -2.5f, -1, -0.5f, 5, 4, 2).tag("beard");
        beard.box("hero_hair", -1.5f, 3, -0.5f, 3, 2, 1).tag("beard_tip");
        Bone axe = b.hands[0].child("axe", 0, 1.5f, 0).rot(68, 0, 0);
        axe.box("hero_wood", -0.5f, -0.5f, -12, 1, 1, 15).tag("haft");
        axe.box("hero_steel", -0.5f, -1, -13, 1, 5, 3).tag("axe_head");
        axe.box("hero_steel", -0.5f, 3, -14, 1, 2, 2).tag("axe_beard");
        // shieldmaiden: braids, a circlet, sword and round shield
        Bone braids = h.child("braids", 0, -3, 2.5f);
        braids.box("hero_hair", -3, 0, -0.5f, 2, 8, 2).tag("braid");
        braids.box("hero_hair", 1, 0, -0.5f, 2, 8, 2).tag("braid");
        braids.box("hero_gold", -3, 7, -0.5f, 2, 1, 2).tag("braid_ring");
        braids.box("hero_gold", 1, 7, -0.5f, 2, 1, 2).tag("braid_ring");
        h.child("circlet", 0, 0, 0).box("hero_gold", -3.4f, -5.4f, -4, 7, 1, 7).tag("circlet");
        Bone sword = b.hands[0].child("sword", 0, 1.5f, 0).rot(68, 0, 0);
        sword.box("hero_leather", -0.5f, -0.5f, -1, 1, 1, 4).tag("grip");
        sword.box("hero_gold", -1.5f, -0.5f, -2, 3, 1, 1).tag("guard");
        sword.box("hero_steel", -0.5f, -1, -13, 1, 2, 11).tag("blade");
        sword.box("hero_gold", -0.5f, -0.5f, 3, 1, 1, 1).tag("pommel");
        Bone shield = b.forearms[1].child("round_shield", -1.5f, 3, 0);
        shield.sbox("hero_shield", -1, -4.5f, -4.5f, 1, 9, 9).tag("shield");
        shield.sbox("hero_gold", -2, -1, -1, 1, 2, 2).tag("boss");
        // archer: hood, bow and quiver
        Bone hood = h.child("hood", 0, 0, 0);
        hood.box("hero_cloth", -3.5f, -6.8f, -3.8f, 7, 5, 7).tag("hood");
        hood.box("hero_cloth", -3, -2, 1.5f, 6, 3, 2).tag("hood_back");
        Bone bow = b.hands[1].child("bow", 0, 1, -0.5f);
        bow.sbox("hero_wood", -0.5f, -2, -0.5f, 1, 4, 1).tag("grip");
        bow.child("bow_upper", 0, -2, 0).rot(-25, 0, 0).sbox("hero_wood", -0.5f, -8, -0.5f, 1, 8, 1).tag("limb");
        bow.child("bow_lower", 0, 2, 0).rot(25, 0, 0).sbox("hero_wood", -0.5f, 0, -0.5f, 1, 8, 1).tag("limb");
        bow.sbox("string", -0.5f, -9, 3.3f, 1, 18, 0).tag("string");
        Bone quiver = b.torso.child("quiver", 1.5f, -10, 3).rot(0, 0, 20);
        quiver.box("hero_leather", -1, 0, -1, 2, 8, 2).tag("quiver");
        quiver.box("hero_feather", -1, -2, -1, 2, 2, 2).tag("fletching");
        b.forearms[0].rot(-40, 0, 0);
        b.forearms[1].rot(-20, 0, 0);

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right", "forearm_left"));
        def.anim("attack", heroSlash("axe_right", "sword_right"));
        Anim bash = Anim.once(0.65f);
        bash.rot("arm_left", 0, 0, 0, 0, 0.2f, -30, 30, -20, 0.32f, -80, -10, 0, 0.45f, -75, -5, 0, 0.65f, 0, 0, 0);
        bash.rot("torso", 0, 0, 0, 0, 0.2f, -5, -20, 0, 0.32f, 10, 20, 0, 0.65f, 0, 0, 0);
        bash.pos("hips", 0, 0, 0, 0, 0.2f, 0, 0, 1, 0.32f, 0, 0, -2.5f, 0.65f, 0, 0, 0);
        bash.rot("leg_left", 0, 0, 0, 0, 0.32f, -25, 0, 0, 0.65f, 0, 0, 0);
        def.anim("attack2", bash);
        def.anim("cast", bowDraw("bow_left"));
        return only(def, "idle", "walk", "run", "attack", "attack2", "cast", "special", "death", "hurt");
    }

    /** Nord armour common to the heroes and Tsun: breastplate, belt, fur mantle, kilt, bracers and boots. */
    private static void nordGear(Biped b, String p) {
        Bone t = b.torso;
        float hw = b.torsoW / 2f;
        t.box(p + "_plate", -hw - 0.5f, -b.torsoH, -b.torsoD / 2f - 0.6f, b.torsoW + 1, 7, 1).tag("breastplate");
        t.box(p + "_gold", -1, -b.torsoH + 0.5f, -b.torsoD / 2f - 1, 2, 6, 1).tag("sternum");
        t.box(p + "_leather", -hw - 0.5f, -3, -b.torsoD / 2f - 0.5f, b.torsoW + 1, 2, b.torsoD + 1).tag("belt");
        t.box(p + "_gold", -1, -3.2f, -b.torsoD / 2f - 0.8f, 2, 2, 1).tag("buckle");
        t.box(p + "_fur", -hw - 2, -b.torsoH - 1.5f, -b.torsoD / 2f - 0.5f, b.torsoW + 4, 3, b.torsoD + 2).tag("mantle");
        b.hips.box(p + "_cloth", -b.hipW / 2f - 0.5f, -1, -b.torsoD / 2f - 0.5f, b.hipW + 1, 6, b.torsoD + 1).tag("kilt");
        for (Bone arm : b.arms) arm.sbox(p + "_plate", -b.armW / 2f - 1, -b.armW / 2f - 1, -b.armW / 2f - 1, b.armW + 2, 3, b.armW + 2).tag("pauldron");
        for (Bone fore : b.forearms) fore.sbox(p + "_leather", -b.armW / 2f, 2, -b.armW / 2f, b.armW, 3, b.armW, 0.2f).tag("bracer");
        for (Bone shin : b.shins) shin.sbox(p + "_leather", -b.legW / 2f, 1, -b.legW / 2f, b.legW, 4, b.legW, 0.2f).tag("boot");
    }

    /** A one-handed weapon cut (the bone that holds the weapon varies), hit ~0.4 s. */
    private static Anim heroSlash(String... weapons) {
        Anim a = Anim.once(0.75f);
        a.rot("arm_right", 0, 0, 0, 0, 0.25f, -150, 20, 30, 0.4f, -50, -35, -10, 0.55f, -30, -25, 0, 0.75f, 0, 0, 0);
        a.rot("forearm_right", 0, 0, 0, 0, 0.25f, -10, 0, 0, 0.4f, 25, 0, 0, 0.75f, 0, 0, 0);
        for (String w : weapons) a.rot(w, 0, 0, 0, 0, 0.25f, -50, 0, 0, 0.4f, 20, 0, 0, 0.55f, 15, 0, 0, 0.75f, 0, 0, 0);
        a.rot("torso", 0, 0, 0, 0, 0.25f, -5, 30, 0, 0.4f, 8, -30, 0, 0.75f, 0, 0, 0);
        a.rot("arm_left", 0, 0, 0, 0, 0.25f, -30, 0, -15, 0.4f, -10, 0, -10, 0.75f, 0, 0, 0);
        a.rot("leg_left", 0, 0, 0, 0, 0.4f, -20, 0, 0, 0.75f, 0, 0, 0);
        return a;
    }

    /** Raises the bow in the left hand, draws to the cheek and looses at ~0.6 s. */
    private static Anim bowDraw(String bow) {
        Anim draw = Anim.once(1.1f);
        draw.rot("arm_left", 0, 0, 0, 0, 0.3f, -85, -20, 0, 0.9f, -85, -20, 0, 1.1f, 0, 0, 0);
        draw.rot("forearm_left", 0, 0, 0, 0, 0.3f, 20, 0, 0, 0.9f, 20, 0, 0, 1.1f, 0, 0, 0);
        draw.rot(bow, 0, 0, 0, 0, 0.3f, 85, 0, 0, 0.9f, 85, 0, 0, 1.1f, 0, 0, 0);
        draw.rot("arm_right", 0, 0, 0, 0, 0.3f, -85, 20, 0, 0.58f, -90, 50, 0, 0.62f, -85, 30, 0, 0.9f, -85, 30, 0, 1.1f, 0, 0, 0);
        draw.rot("forearm_right", 0, 0, 0, 0, 0.3f, -40, 0, 0, 0.58f, -110, 0, 0, 0.62f, -60, 0, 0, 0.9f, -60, 0, 0, 1.1f, 0, 0, 0);
        draw.rot("torso", 0, 0, 0, 0, 0.3f, 0, -35, 0, 0.9f, 0, -35, 0, 1.1f, 0, 0, 0);
        draw.rot("head", 0, 0, 0, 0, 0.3f, 0, 30, 0, 0.9f, 0, 30, 0, 1.1f, 0, 0, 0);
        return draw;
    }

    /**
     * Tsun, Shield-Thane of Shor: a towering bearded Nord in gold-trimmed silver plate and a bear-fur mantle, a horned
     * helm and a great two-handed battle axe.
     */
    static CreatureDef tsun() {
        Biped b = new Biped("tsun");
        b.legLen = 15;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 4;
        b.torsoW = 13;
        b.torsoH = 13;
        b.torsoD = 7;
        b.hunch = 4;
        b.armLen = 15;
        b.armW = 5;
        b.handSize = 4;
        b.headW = 7;
        b.headH = 7;
        b.headD = 7;
        b.skin = "tsun_mail";
        b.headMat = "tsun_face";
        b.armMat = "tsun_skin";
        b.handMat = "tsun_leather";
        b.footMat = "tsun_plate";
        b.hipMat = "tsun_leather";
        b.legMat = "tsun_cloth";
        b.scale = 1.35f;
        b.stride = 26;
        b.twoHanded = true;
        b.build();
        nordGear(b, "tsun");
        Bone h = b.head;
        h.box("tsun_plate", -4, -7.8f, -4.5f, 8, 3, 8).tag("helm");
        h.box("tsun_gold", -4.1f, -5, -4.6f, 8, 1, 8).tag("helm_band");
        h.box("tsun_plate", -0.5f, -5, -5, 1, 4, 1).tag("nasal");
        h.box("tsun_plate", -4.1f, -5, -2, 1, 4, 5).tag("cheek_guard");
        h.box("tsun_plate", 3.1f, -5, -2, 1, 4, 5).tag("cheek_guard");
        h.pair("horn", 4, -6.5f, -0.5f, (hb, s) -> {
            hb.rot(0, 15, -40);
            hb.sbox("tsun_horn", -1.5f, -5, -1.5f, 3, 5, 3).tag("horn");
            Bone mid = hb.child("horn_mid", 0, -5, 0).rot(0, 0, 25);
            mid.sbox("tsun_horn", -1, -4, -1, 2, 4, 2).tag("horn");
            mid.child("horn_tip", 0, -4, 0).rot(0, 0, 25).sbox("tsun_horn", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn_tip");
        });
        h.box("tsun_hair", -3.6f, -4.5f, 1.8f, 7, 6, 2).tag("hair_back");
        Bone beard = h.child("beard", 0, -1.5f, -4).rot(-6, 0, 0);
        beard.box("tsun_hair", -3.5f, -1.5f, -0.5f, 7, 5, 3).tag("beard");
        beard.box("tsun_hair", -2.5f, 3.5f, -0.5f, 5, 3, 2).tag("beard");
        Bone braid = beard.child("beard_braid", 0, 6, 0).rot(-10, 0, 0);
        braid.box("tsun_hair", -1, 0, -0.5f, 2, 5, 2).tag("braid");
        braid.box("tsun_gold", -1.5f, 4, -1, 3, 1, 3).tag("braid_ring");
        b.head.box("tsun_hair", -4, -2.5f, -4.4f, 8, 1, 1).tag("moustache");
        Bone cape = b.torso.child("cape", 0, -13, 3.6f).rot(6, 0, 0);
        cape.box("tsun_fur", -6, 0, 0, 12, 24, 1).tag("cape");
        b.forearms[0].rot(-45, 0, 0);
        b.forearms[1].rot(-35, 0, 0);
        Bone w = b.hands[0].child("weapon", 0, 1.5f, 0).rot(22, 0, 0);
        w.box("tsun_wood", -0.5f, -0.5f, -22, 1, 1, 29).tag("haft");
        w.box("tsun_gold", -1, -1, 6, 2, 2, 2).tag("pommel");
        w.box("tsun_gold", -1, -1, -17, 2, 2, 2).tag("collar");
        w.box("tsun_blade", -0.5f, -7, -24, 1, 7, 6).tag("axe_blade");
        w.box("tsun_blade", -0.5f, 1, -24, 1, 7, 6).tag("axe_blade");
        w.box("tsun_gold", -1, -1.5f, -23.5f, 2, 3, 5).tag("axe_socket");
        w.box("tsun_blade", -0.5f, -0.5f, -26, 1, 1, 2).tag("spike");

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right", "forearm_left"));
        def.get("attack").rot("weapon_right", 0, 0, 0, 0, 0.5f, 30, 0, 0, 0.65f, 75, 0, 0, 0.9f, 60, 0, 0, 1.2f, 0, 0, 0);
        Anim sweep = Anim.once(1.1f);
        sweep.rot("torso", 0, 0, 0, 0, 0.4f, -5, 50, 0, 0.55f, 5, -45, 0, 0.8f, 3, -55, 0, 1.1f, 0, 0, 0);
        sweep.rot("arm_right", 0, 0, 0, 0, 0.4f, -70, 50, 40, 0.55f, -80, -40, 0, 0.8f, -60, -50, 0, 1.1f, 0, 0, 0);
        sweep.rot("arm_left", 0, 0, 0, 0, 0.4f, -60, 40, 10, 0.55f, -80, -30, 20, 0.8f, -60, -40, 20, 1.1f, 0, 0, 0);
        sweep.rot("weapon_right", 0, 0, 0, 0, 0.4f, 80, 0, 0, 0.55f, 105, 0, 0, 1.1f, 0, 0, 0);
        sweep.rot("leg_left", 0, 0, 0, 0, 0.55f, -20, 0, 0, 1.1f, 0, 0, 0);
        sweep.pos("hips", 0, 0, 0, 0, 0.55f, 0, -1, 0, 1.1f, 0, 0, 0);
        def.anim("attack2", sweep);
        Anim roar = def.get("special");
        roar.rot("weapon_right", 0, 0, 0, 0, 0.35f, -60, 0, 0, 1.1f, -60, 0, 0, 1.5f, 0, 0, 0);
        roar.rot("beard", 0, 0, 0, 0, 0.35f, -20, 0, 0, 1.1f, -20, 0, 0, 1.5f, 0, 0, 0);
        Anim idle = def.get("idle");
        idle.swing("cape", 0, 2f, 0.2f);
        idle.swing("beard_braid", 0, 4f, 0.3f);
        Anim walk = def.get("walk");
        walk.swing("cape", 0, 5f, 0.1f, 6f);
        walk.swingN("beard_braid", 0, 6f, 0.2f, 2);
        return only(def, "idle", "walk", "run", "attack", "attack2", "special", "death", "hurt");
    }

    // ================================================================================================ Coldharbour

    /**
     * Harvester: Molag Bal's soul-reaper, a long serpent tail rising into a gaunt grey woman's body with four arms in
     * spiked dark carapace, a mane of black hair and swept-back horns.
     */
    static CreatureDef harvester() {
        Rig r = new Rig("harvester");
        Bone body = r.root().child("body", 0, -2, 0);
        body.box("harv_scales", -3, -13, -2.5f, 6, 15, 5).tag("column");
        body.box("harv_belly", -2, -12, -3.1f, 4, 13, 1).tag("belly_plates");
        Bone coil = body.child("coil", 0, -5.5f, 1.5f).rot(-40, 0, 0);
        coil.box("harv_scales", -2.5f, -2, -1, 5, 5, 6).tag("coil");
        Bone torso = body.child("torso", 0, -13, 0).rot(8, 0, 0);
        torso.box("harv_skin", -3.5f, -10, -2, 7, 10, 4).tag("torso");
        torso.box("harv_carapace", -4, -10.5f, 1.2f, 8, 8, 2).tag("back_carapace");
        torso.box("harv_carapace", -3.5f, -9.5f, -2.6f, 7, 4, 1).tag("breastplate");
        torso.box("harv_carapace", -4, -3, -2.5f, 8, 3, 5, 0.1f).tag("girdle");
        for (int i = 0; i < 3; i++) {
            torso.child("back_spike" + (i + 1), 0, -10 + i * 3, 3).rot(-50, 0, 0).box("harv_spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("spike");
        }
        Bone head = torso.child("head", 0, -10.5f, -0.3f).rot(-8, 0, 0);
        head.box("harv_skin", -2.5f, -5, -2.5f, 5, 5, 5).tag("head");
        head.box("harv_hair", -3, -5.6f, -2, 6, 2, 5).tag("hair_top");
        Bone mane = head.child("mane", 0, -4, 2).rot(15, 0, 0);
        mane.box("harv_hair", -2.5f, 0, 0, 5, 10, 2).tag("mane");
        head.pair("horn", 2, -4.5f, 0, (hb, s) -> {
            hb.rot(-60, 0, -20);
            hb.sbox("harv_spike", -1, -4, -1, 2, 4, 2).tag("horn");
            hb.child("horn_tip", 0, -4, 0).rot(-25, 0, 10).sbox("harv_spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("horn_tip");
        });
        for (String arm : new String[]{"arm", "arm_low"}) {
            boolean upper = arm.equals("arm");
            torso.pair(arm, upper ? 4.5f : 4, upper ? -9 : -5.5f, 0, (b, s) -> {
                b.rot(0, 0, upper ? 8 : 25);
                b.sbox("harv_skin", -1, -1, -1, 2, 6, 2).tag("arm");
                if (upper) b.sbox("harv_carapace", -2, -2, -1.5f, 3, 3, 3, 0.2f).tag("pauldron");
                Bone fore = b.child(upper ? "forearm" : "forearm_low", 0, 5.5f, 0).rot(upper ? -20 : -45, 0, 0);
                fore.sbox("harv_carapace", -1, 0, -1, 2, 5, 2, 0.1f).tag("forearm");
                Bone hand = fore.child(upper ? "hand" : "hand_low", 0, 5, 0);
                hand.sbox("harv_skin", -1, -0.5f, -1, 2, 2, 2).tag("hand");
                hand.sbox("harv_spike", -1, 1.5f, -1, 1, 4, 1).tag("claw");
                hand.sbox("harv_spike", 0, 1.5f, -0.5f, 1, 4, 1).tag("claw");
            });
        }
        int[] tw = {6, 6, 5, 5, 4, 3, 2};
        int[] th = {5, 5, 4, 4, 3, 3, 2};
        float[] yaw = {25, -30, -25, 20, 30, 20, -15};
        int len = 6;
        Bone[] tail = new Bone[tw.length];
        Bone parent = body;
        for (int i = 0; i < tw.length; i++) {
            Bone tb = i == 0 ? parent.child("tail1", 0, -0.5f, 1.5f) : parent.child("tail" + (i + 1), 0, (th[i - 1] - th[i]) / 2f, len - 0.5f);
            tb.rot(0, yaw[i], 0);
            tb.box("harv_scales", -tw[i] / 2f, -th[i] / 2f, 0, tw[i], th[i], len + (i == 0 ? 1 : 0)).tag("tail");
            if (i % 2 == 0 && i < tw.length - 1) tb.box("harv_spike", -0.5f, -th[i] / 2f - 1.5f, 2, 1, 2, 2).tag("spike");
            tail[i] = tb;
            parent = tb;
        }

        CreatureDef d = new CreatureDef(r);
        d.gait(0.6f, 0.4f, 0.6f);
        d.look("head", 0.7f).look("torso", 0.3f);
        Anim idle = Anim.loop(3.5f);
        idle.swing("body", 2, 2f, 0f);
        idle.swing("body", 0, 1.5f, 0.25f);
        idle.swing("torso", 2, -1.5f, 0f);
        idle.swing("head", 1, 10f, 0.2f);
        idle.swing("mane", 0, 4f, 0.3f);
        for (String a : new String[]{"arm", "arm_low"}) {
            idle.swing(a + "_right", 0, 4f, 0.3f);
            idle.swing(a + "_left", 0, 4f, 0.8f);
            idle.swing(a + "_right", 2, 3f, 0.1f);
            idle.swing(a + "_left", 2, -3f, 0.6f);
        }
        for (int i = 0; i < tail.length; i++) idle.wave(tail[i].name, Anim.Target.ROTATION, 1, 3f + i, -i * 0.12f, 0, 1);
        d.anim("idle", idle);
        d.anim("walk", harvSlither(tail, 1.2f, 12f, 0f));
        d.anim("run", harvSlither(tail, 0.7f, 20f, 14f));
        // four-armed rake (hit ~0.4 s)
        Anim rake = Anim.once(0.8f);
        rake.rot("body", 0, 0, 0, 0, 0.25f, -8, 0, 0, 0.4f, 14, 0, 0, 0.8f, 0, 0, 0);
        rake.rot("torso", 0, 0, 0, 0, 0.25f, -10, 20, 0, 0.4f, 12, -20, 0, 0.8f, 0, 0, 0);
        rake.rot("arm_right", 0, 0, 0, 0, 0.25f, -160, 20, 30, 0.4f, -40, -20, -10, 0.8f, 0, 0, 0);
        rake.rot("arm_left", 0, 0, 0, 0, 0.3f, -150, -20, -30, 0.45f, -40, 20, 10, 0.8f, 0, 0, 0);
        rake.rot("arm_low_right", 0, 0, 0, 0, 0.3f, -100, 0, 40, 0.45f, -30, 0, 0, 0.8f, 0, 0, 0);
        rake.rot("arm_low_left", 0, 0, 0, 0, 0.25f, -100, 0, -40, 0.4f, -30, 0, 0, 0.8f, 0, 0, 0);
        d.anim("attack", rake);
        // drain: lower hands spread, upper hands reach and pull the soul in (hit ~0.5 s)
        Anim cast = Anim.once(1.0f);
        rotLR(cast, "arm", 0, 0, 0, 0, 0.3f, -60, 0, 50, 0.5f, -95, -20, 0, 0.75f, -90, -15, 0, 1.0f, 0, 0, 0);
        rotLR(cast, "arm_low", 0, 0, 0, 0, 0.3f, -30, 0, 70, 0.75f, -30, 0, 70, 1.0f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.5f, 8, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("head", 0, 0, 0, 0, 0.3f, -20, 0, 0, 0.5f, 5, 0, 0, 1.0f, 0, 0, 0);
        d.anim("cast", cast);
        // rearing hiss
        Anim hiss = Anim.once(1.6f);
        hiss.pos("body", 0, 0, 0, 0, 0.35f, 0, 3, 0, 1.2f, 0, 3, 0, 1.6f, 0, 0, 0);
        hiss.rot("body", 0, 0, 0, 0, 0.35f, -10, 0, 0, 1.2f, -10, 0, 0, 1.6f, 0, 0, 0);
        hiss.rot("head", 0, 0, 0, 0, 0.35f, -20, 0, 0, 0.8f, -22, 8, 0, 1.2f, -20, -6, 0, 1.6f, 0, 0, 0);
        rotLR(hiss, "arm", 0, 0, 0, 0, 0.35f, -100, 0, 50, 1.2f, -100, 0, 50, 1.6f, 0, 0, 0);
        rotLR(hiss, "arm_low", 0, 0, 0, 0, 0.35f, -20, 0, 60, 1.2f, -20, 0, 60, 1.6f, 0, 0, 0);
        d.anim("special", hiss);
        Anim death = Anim.once(1.0f);
        death.rot("body", 0, 0, 0, 0, 0.7f, 50, 0, 8, 1f, 55, 0, 8);
        death.rot("torso", 0, 0, 0, 0, 0.7f, 20, 0, 0, 1f, 25, 0, 0);
        death.rot("head", 0, 0, 0, 0, 0.7f, 25, 20, 0, 1f, 25, 20, 0);
        rotLR(death, "arm", 0, 0, 0, 0, 0.7f, -30, 0, 20, 1f, -30, 0, 20);
        rotLR(death, "arm_low", 0, 0, 0, 0, 0.7f, -10, 0, 30, 1f, -10, 0, 30);
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

    private static Anim harvSlither(Bone[] tail, float len, float amp, float lean) {
        Anim a = Anim.loop(len);
        for (int i = 0; i < tail.length; i++) a.wave(tail[i].name, Anim.Target.ROTATION, 1, amp * (0.6f + i * 0.15f), -i * 0.14f, 0, 1);
        a.swing("body", 1, amp * 0.4f, 0.1f);
        a.swing("body", 2, amp * 0.25f, 0.1f);
        if (lean != 0) a.hold("torso", lean, 0, 0);
        a.swing("torso", 1, -amp * 0.3f, 0.1f);
        a.swing("head", 1, -amp * 0.2f, 0.1f);
        a.swing("mane", 0, amp * 0.4f, 0.2f, lean);
        a.swing("arm_right", 0, amp, 0.5f);
        a.swing("arm_left", 0, amp, 0f);
        a.swing("arm_low_right", 0, amp * 0.8f, 0f);
        a.swing("arm_low_left", 0, amp * 0.8f, 0.5f);
        a.bob("body", 1, 0.4f, 0f, 2);
        return a;
    }

    // ================================================================================================ Miraak

    /**
     * Miraak, the First Dragonborn: a tall figure in layered robes of dark green and bronze scale, a hood over a golden
     * mask fringed with tentacles, and a curved, tentacle-wrought sword.
     */
    static CreatureDef miraak() {
        Biped b = new Biped("miraak");
        b.legLen = 13;
        b.legW = 4;
        b.hipW = 8;
        b.hipH = 3;
        b.torsoW = 9;
        b.torsoH = 11;
        b.torsoD = 5;
        b.hunch = 2;
        b.armLen = 12;
        b.armW = 4;
        b.handSize = 3;
        b.headW = 6;
        b.headH = 6;
        b.headD = 6;
        b.skin = "mir_robe";
        b.headMat = "mir_hood";
        b.handMat = "mir_glove";
        b.footMat = "mir_dark";
        b.hipMat = "mir_robe";
        b.legMat = "mir_dark";
        b.stride = 28;
        b.build();
        Bone t = b.torso;
        t.box("mir_scale", -5.5f, -11.5f, -3, 11, 4, 6).tag("mantle");
        t.box("mir_trim", -1.5f, -11, -3.1f, 3, 11, 1).tag("panel");
        t.box("mir_dark", -5, -3, -3, 10, 2, 6).tag("belt");
        t.box("mir_mask", -1, -3.2f, -3.4f, 2, 2, 1).tag("buckle");
        for (int k = 0; k < 4; k++) {
            t.child("mantle_tentacle" + (k + 1), -4.5f + k * 3, -8, -3).rot(-10, 0, 0).box("mir_scale", -0.5f, 0, -0.5f, 1, 3, 1).tag("fringe");
        }
        b.hips.child("robe_front", 0, -1, -3).box("mir_robe", -4.5f, 0, -0.5f, 9, 13, 1).tag("robe_panel");
        b.hips.child("robe_back", 0, -1, 3).box("mir_robe", -4.5f, 0, -0.5f, 9, 14, 1).tag("robe_panel");
        b.hips.pair("robe_side", 4.5f, -1, 0, (p, s) -> p.sbox("mir_robe", -0.5f, 0, -3, 1, 12, 6).tag("robe_side"));
        Bone h = b.head;
        h.box("mir_hood", -3.6f, -6.8f, -2.5f, 7, 7, 7).tag("hood");
        h.box("mir_hood", -2, -7.5f, 1, 4, 2, 3).tag("hood_peak");
        Bone mask = h.child("mask", 0, -3, -3.6f);
        mask.box("mir_mask", -2.5f, -3, -0.5f, 5, 6, 1).tag("mask");
        mask.box("mir_mask", -3.5f, -4.5f, 0, 1, 4, 1).tag("flare");
        mask.box("mir_mask", 2.5f, -4.5f, 0, 1, 4, 1).tag("flare");
        mask.box("mir_mask", -0.5f, -5, -0.8f, 1, 3, 1).tag("crest");
        Bone[][] tent = new Bone[4][];
        for (int k = 0; k < 4; k++) {
            tent[k] = chain(mask, "mask_tentacle" + (k + 1) + "_", -1.8f + k * 1.2f, 2.5f, -0.3f, "mir_mask", 2, 3, 1, 1, -12 + k * 2, 18);
        }
        for (Bone arm : b.arms) arm.sbox("mir_scale", -3, -3, -3, 6, 4, 6, 0.1f).tag("pauldron");
        for (Bone fore : b.forearms) fore.sbox("mir_robe", -2.5f, 2, -2.5f, 5, 4, 5).tag("cuff");
        b.forearms[0].rot(-40, 0, 0);
        b.forearms[1].rot(-15, 0, 0);
        Bone w = b.hands[0].child("weapon", 0, 1.5f, 0).rot(68, 0, 0);
        w.box("mir_dark", -0.5f, -0.5f, -1, 1, 1, 4).tag("grip");
        w.box("mir_mask", -1.5f, -1, -2, 3, 2, 1).tag("guard");
        w.box("mir_blade", -0.5f, -1, -11, 1, 2, 9).tag("blade");
        Bone tip = w.child("weapon_tip", 0, 0, -10.5f).rot(-15, 0, 0);
        tip.box("mir_blade", -0.5f, -1, -5, 1, 2, 5).tag("blade");
        tip.box("mir_blade", -0.5f, -0.5f, -7, 1, 1, 2).tag("blade_tip");

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right"));
        for (String slot : new String[]{"idle", "walk", "run"}) {
            Anim a = def.get(slot);
            for (int k = 0; k < tent.length; k++) sway(a, tent[k], 0, slot.equals("idle") ? 8f : 12f, k * 0.23f, 0);
            for (int k = 1; k <= 4; k++) a.swingN("mantle_tentacle" + k, 0, 10f, k * 0.2f, 2);
        }
        Anim walk = def.get("walk");
        walk.swing("robe_front", 0, -14f, 0f, -4f);
        walk.swing("robe_back", 0, 10f, 0f, 6f);
        Anim run = def.get("run");
        run.hold("robe_front", -20, 0, 0);
        run.hold("robe_back", 25, 0, 0);
        def.anim("attack", heroSlash("weapon_right"));
        Anim thrust = Anim.once(0.7f);
        thrust.rot("arm_right", 0, 0, 0, 0, 0.25f, 25, 0, 10, 0.38f, -78, 0, 0, 0.5f, -75, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("forearm_right", 0, 0, 0, 0, 0.25f, -60, 0, 0, 0.38f, 40, 0, 0, 0.5f, 40, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("weapon_right", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.5f, 10, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("torso", 0, 0, 0, 0, 0.25f, -4, 25, 0, 0.38f, 10, -12, 0, 0.7f, 0, 0, 0);
        thrust.rot("leg_right", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.38f, -35, 0, 0, 0.55f, -30, 0, 0, 0.7f, 0, 0, 0);
        thrust.pos("hips", 0, 0, 0, 0, 0.25f, 0, 0, 1, 0.38f, 0, -1, -3, 0.55f, 0, -1, -3, 0.7f, 0, 0, 0);
        def.anim("attack2", thrust);
        // left-hand spell (hit ~0.5 s)
        Anim cast = Anim.once(1.0f);
        cast.rot("arm_left", 0, 0, 0, 0, 0.35f, -60, 30, -30, 0.5f, -90, -10, 0, 0.7f, -88, -5, 0, 1.0f, 0, 0, 0);
        cast.rot("forearm_left", 0, 0, 0, 0, 0.35f, -60, 0, 0, 0.5f, 15, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.35f, -6, -20, 0, 0.5f, 6, 15, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);
        // the Thu'um: braced stance, head driven forward (hit ~0.6 s)
        Anim shout = Anim.once(1.3f);
        shout.rot("torso", 0, 0, 0, 0, 0.45f, -18, 0, 0, 0.62f, 18, 0, 0, 1.0f, 14, 0, 0, 1.3f, 0, 0, 0);
        shout.rot("head", 0, 0, 0, 0, 0.45f, -20, 0, 0, 0.62f, 12, 0, 0, 1.0f, 10, 0, 0, 1.3f, 0, 0, 0);
        rotLR(shout, "arm", 0, 0, 0, 0, 0.45f, 30, 0, 30, 0.62f, 20, 0, 45, 1.0f, 20, 0, 40, 1.3f, 0, 0, 0);
        shout.rot("leg_right", 0, 0, 0, 0, 0.45f, -25, 0, 0, 1.0f, -25, 0, 0, 1.3f, 0, 0, 0);
        shout.pos("hips", 0, 0, 0, 0, 0.45f, 0, -1.5f, 0, 1.0f, 0, -1.5f, 0, 1.3f, 0, 0, 0);
        for (int k = 0; k < tent.length; k++) shout.rot(tent[k][0].name, 0, 0, 0, 0, 0.62f, -60, 0, (k - 1.5f) * 15, 1.0f, -40, 0, 0, 1.3f, 0, 0, 0);
        def.anim("shout", shout);
        // blink: wreathed in a whirl and gone
        Anim blink = Anim.once(0.8f);
        blink.linear().rot("hips", 0, 0, 0, 0, 0.4f, 0, 540, 0, 0.8f, 0, 720, 0).smooth();
        rotLR(blink, "arm", 0, 0, 0, 0, 0.2f, -30, 0, 70, 0.6f, -30, 0, 70, 0.8f, 0, 0, 0);
        blink.scale("hips", 0, 1, 1, 1, 0.4f, 0.3f, 1.2f, 0.3f, 0.5f, 0.3f, 1.2f, 0.3f, 0.8f, 1, 1, 1);
        def.anim("special", blink);
        return only(def, "idle", "walk", "run", "attack", "attack2", "cast", "summon", "shout", "special", "death", "hurt");
    }
}
