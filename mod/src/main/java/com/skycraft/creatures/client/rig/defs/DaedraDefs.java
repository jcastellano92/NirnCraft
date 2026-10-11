package com.skycraft.creatures.client.rig.defs;

import com.skycraft.creatures.client.rig.Anim;
import com.skycraft.creatures.client.rig.Arachnid;
import com.skycraft.creatures.client.rig.Biped;
import com.skycraft.creatures.client.rig.Bone;
import com.skycraft.creatures.client.rig.CreatureDef;
import com.skycraft.creatures.client.rig.Rig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Daedra, Dwemer automatons and the dragon priests. */
final class DaedraDefs {
    private DaedraDefs() {}

    static void register() {
        Defs.reg("flame_atronach", DaedraDefs::flameAtronach);
        Defs.reg("frost_atronach", DaedraDefs::frostAtronach);
        Defs.reg("storm_atronach", DaedraDefs::stormAtronach);
        Defs.reg("dremora", DaedraDefs::dremora);
        Defs.reg("scamp", DaedraDefs::scamp);
        Defs.reg("imp", DaedraDefs::imp);
        Defs.reg("dragon_priest", DaedraDefs::dragonPriest);
        Defs.reg("dwarven_spider", DaedraDefs::dwarvenSpider);
        Defs.reg("dwarven_sphere", DaedraDefs::dwarvenSphere);
        Defs.reg("dwarven_centurion", DaedraDefs::dwarvenCenturion);
        Defs.reg("dwarven_ballista", DaedraDefs::dwarvenBallista);
    }

    /** Keeps only the listed animation slots (the parametric plans add a few this creature does not use). */
    private static CreatureDef only(CreatureDef def, String... slots) {
        Set<String> keep = new HashSet<>(Arrays.asList(slots));
        def.anims.keySet().retainAll(keep);
        return def;
    }

    /** Removes every channel of the given bones from an animation (to re-author them). */
    private static Anim drop(Anim a, String... bones) {
        Set<String> s = new HashSet<>(Arrays.asList(bones));
        a.channels.removeIf(c -> s.contains(c.bone));
        return a;
    }

    /** A full turn about y over the whole loop (orbiting parts). */
    private static Anim spin(Anim a, String bone, float turns) {
        a.linear().rot(bone, 0, 0, 0, 0, a.length, 0, 360 * turns, 0).smooth();
        return a;
    }

    // ============================================================================================ flame atronach

    /** Living fire in a woman's shape: ember body, floating obsidian plates, flame hair, a flame column for legs. */
    static CreatureDef flameAtronach() {
        Rig r = new Rig("flame_atronach");
        Bone body = r.root().child("body", 0, -15, 0);
        body.box("ember", -2, -2.5f, -1.5f, 4, 3, 3).tag("waist");
        body.box("obsidian", -3, -1, -2.6f, 6, 2, 1, 0.1f).tag("belt");
        body.box("obsidian", -3, -1, 1.6f, 6, 2, 1, 0.1f).tag("belt");
        body.pair("hip_plate", 3.2f, -0.8f, 0, (b, s) -> {
            b.rot(0, 0, 14);
            b.sbox("obsidian", -1, -0.5f, -2, 1, 4, 4, 0.1f).tag("plate");
        });
        // the flame column that replaces the legs
        Bone f1 = body.child("flame1", 0, 0.5f, 0);
        f1.box("flame", -2.5f, 0, -2, 5, 4, 4).tag("flame");
        f1.box("flame", -3.5f, 1, -0.5f, 1, 2, 1).tag("tongue");
        f1.box("flame", 2.5f, 1.5f, 0, 1, 2, 1).tag("tongue");
        Bone f2 = f1.child("flame2", 0, 3.5f, 0.3f);
        f2.box("flame", -2, 0, -1.5f, 4, 4, 3).tag("flame");
        f2.box("flame", -0.5f, 1, 1.5f, 1, 2, 1).tag("tongue");
        Bone f3 = f2.child("flame3", 0, 3.5f, 0.3f);
        f3.box("flame", -1.5f, 0, -1, 3, 4, 2).tag("flame");
        Bone f4 = f3.child("flame4", 0, 3.5f, 0.2f);
        f4.box("flame_tip", -0.5f, 0, -0.5f, 1, 3, 1).tag("flame_tip");
        // slender upper body
        Bone torso = body.child("torso", 0, -2, 0);
        torso.box("ember", -2, -4, -1.5f, 4, 4, 3).tag("belly");
        torso.box("ember", -3, -8, -1.5f, 6, 4, 3).tag("chest");
        torso.box("ember", -1, -9, -1, 2, 1, 2).tag("neck");
        torso.box("obsidian", -2.9f, -7.2f, -2.4f, 2, 2, 1, 0.2f).tag("cup");
        torso.box("obsidian", 0.9f, -7.2f, -2.4f, 2, 2, 1, 0.2f).tag("cup");
        torso.box("obsidian", -1.5f, -3.6f, -2.3f, 3, 2, 1).tag("plate");
        torso.box("obsidian", -2.5f, -8.2f, 1.3f, 5, 5, 1, 0.1f).tag("plate");
        Bone head = torso.child("head", 0, -9, -0.2f);
        head.box("ember", -2, -5, -2.5f, 4, 5, 4).tag("head");
        head.box("obsidian", -2, -5.6f, -2.8f, 4, 1, 1).tag("circlet");
        Bone hair = head.child("hair", 0, -4.5f, 0.5f);
        hair.box("flame", -2.5f, -1.5f, -3.2f, 5, 2, 5).tag("hair");
        hair.box("flame", -2.5f, -1.5f, 1.3f, 5, 5, 2).tag("hair");
        Bone plume = hair.child("hair_plume", 0, -1, 1.5f).rot(-45, 0, 0);
        plume.box("flame", -2.5f, -6, -1.5f, 5, 6, 3).tag("hair");
        Bone tip = plume.child("hair_tip", 0, -6, 0).rot(-25, 0, 0);
        tip.box("flame_tip", -1.5f, -5, -1, 3, 5, 2).tag("flame_tip");
        tip.box("flame_tip", -0.5f, -7, -0.5f, 1, 2, 1).tag("flame_tip");
        hair.pair("hair_side", 2.2f, -1, -0.5f, (h, s) -> {
            h.rot(-25, 0, -25);
            h.sbox("flame", -1, -4, -1, 2, 4, 2).tag("hair");
            h.sbox("flame_tip", -0.5f, -6, -0.5f, 1, 2, 1).tag("flame_tip");
        });
        torso.pair("arm", 3.6f, -7.5f, 0, (b, s) -> {
            b.rot(0, 0, 10);
            b.sbox("ember", -1, -1, -1, 2, 6, 2).tag("arm");
            b.sbox("obsidian", -2, -2.4f, -1.5f, 3, 2, 3, 0.25f).tag("pauldron");
            Bone fore = b.child("forearm", 0, 5, 0).rot(-20, 0, 0);
            fore.sbox("ember", -1, 0, -1, 2, 6, 2).tag("forearm");
            fore.sbox("obsidian", -1.5f, 2, -1.5f, 3, 2, 3, 0.1f).tag("bracer");
            Bone hand = fore.child("hand", 0, 6, 0);
            hand.sbox("ember", -1, -0.5f, -1, 2, 2, 2).tag("hand");
            hand.sbox("flame_tip", -1, 1.5f, -1, 1, 2, 1).tag("claw");
            hand.sbox("flame_tip", 0, 1.5f, -0.5f, 1, 2, 1).tag("claw");
        });
        // obsidian rocks orbiting the waist
        Bone rocks = body.child("rocks", 0, -3, 0);
        float[][] rp = {{8, 0, 1}, {-6.5f, 4, 5}, {3, -5, -7}, {-5, -3, -6}};
        int[][] rs = {{2, 2, 2}, {2, 1, 2}, {1, 2, 1}, {2, 2, 1}};
        for (int i = 0; i < rp.length; i++) {
            Bone rk = rocks.child("rock" + (i + 1), rp[i][0], rp[i][1], rp[i][2]).rot(25 * i, 40 * i, 15);
            rk.box("obsidian", -rs[i][0] / 2f, -rs[i][1] / 2f, -rs[i][2] / 2f, rs[i][0], rs[i][1], rs[i][2]).tag("rock");
        }

        CreatureDef def = new CreatureDef(r);
        def.gait(0.8f, 0.6f, 0.7f);
        def.look("head", 0.9f);
        def.anim("idle", flameIdle(Anim.loop(4f), false));
        def.anim("walk", flameIdle(Anim.loop(3.2f), true));

        Anim atk = Anim.once(0.75f);
        atk.rot("arm_right", 0, 0, 0, 0, 0.25f, -150, 20, 40, 0.38f, -40, -25, -35, 0.55f, -25, -10, -20, 0.75f, 0, 0, 0);
        atk.rot("forearm_right", 0, 0, 0, 0, 0.25f, -40, 0, 0, 0.38f, 10, 0, 0, 0.75f, 0, 0, 0);
        atk.rot("torso", 0, 0, 0, 0, 0.25f, -6, 30, 0, 0.38f, 10, -30, 0, 0.75f, 0, 0, 0);
        atk.rot("arm_left", 0, 0, 0, 0, 0.25f, 15, 0, -20, 0.38f, -20, 0, -10, 0.75f, 0, 0, 0);
        atk.rot("head", 0, 0, 0, 0, 0.25f, 0, -20, 0, 0.38f, 5, 15, 0, 0.75f, 0, 0, 0);
        atk.pos("body", 0, 0, 0, 0, 0.25f, 0, 1, 1, 0.38f, 0, 0, -2, 0.75f, 0, 0, 0);
        atk.rot("flame1", 0, 0, 0, 0, 0.38f, 15, 0, 0, 0.75f, 0, 0, 0);
        def.anim("attack", atk);

        Anim cast = Anim.once(0.9f);
        cast.rot("arm_right", 0, 0, 0, 0, 0.3f, -70, 30, 20, 0.42f, -90, -5, 0, 0.65f, -85, 0, 0, 0.9f, 0, 0, 0);
        cast.rot("forearm_right", 0, 0, 0, 0, 0.3f, -70, 0, 0, 0.42f, 20, 0, 0, 0.9f, 0, 0, 0);
        cast.rot("arm_left", 0, 0, 0, 0, 0.3f, -30, 0, -25, 0.42f, 20, 0, -20, 0.9f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.3f, -8, 25, 0, 0.42f, 6, -10, 0, 0.9f, 0, 0, 0);
        cast.scale("hair", 0, 1, 1, 1, 0.3f, 1.25f, 1.35f, 1.25f, 0.5f, 1.1f, 1.1f, 1.1f, 0.9f, 1, 1, 1);
        cast.scale("hand_right", 0, 1, 1, 1, 0.3f, 1.4f, 1.4f, 1.4f, 0.45f, 1.6f, 1.6f, 1.6f, 0.6f, 1, 1, 1);
        def.anim("cast", cast);

        Anim death = Anim.once(1f);
        death.pos("body", 0, 0, 0, 0, 0.7f, 0, -9, 0, 1f, 0, -10, 0);
        death.rot("torso", 0, 0, 0, 0, 0.6f, 35, 0, 10, 1f, 40, 0, 10);
        death.rot("head", 0, 0, 0, 0, 0.6f, 30, 20, 0, 1f, 35, 20, 0);
        death.rot("arm_right", 0, 0, 0, 0, 0.6f, -20, 0, 25, 1f, -20, 0, 25);
        death.rot("arm_left", 0, 0, 0, 0, 0.6f, -20, 0, -25, 1f, -20, 0, -25);
        for (int i = 1; i <= 4; i++) {
            death.scale("flame" + i, 0, 1, 1, 1, 0.8f, 0.6f, 0.15f, 0.6f, 1f, 0.5f, 0.1f, 0.5f);
            death.pos("rock" + i, 0, 0, 0, 0, 0.5f + i * 0.08f, 0, -9 + i, 0, 1f, 0, -9 + i, 0);
        }
        death.scale("hair", 0, 1, 1, 1, 1f, 0.6f, 0.4f, 0.6f);
        def.anim("death", death);

        Anim hurt = Anim.once(0.4f);
        hurt.rot("torso", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.4f, 0, 0, 0);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.4f, 0, 0, 0);
        hurt.pos("body", 0, 0, 0, 0, 0.1f, 0, 0, 1.5f, 0.4f, 0, 0, 0);
        def.anim("hurt", hurt);
        return def;
    }

    private static Anim flameIdle(Anim a, boolean glide) {
        a.bob("body", 1, glide ? 1.2f : 1.0f, 0, 2);
        if (glide) {
            a.hold("body", 14, 0, 0);
            a.hold("arm_right", 18, 0, -6);
            a.hold("arm_left", 18, 0, 6);
            a.hold("hair_plume", 15, 0, 0);
        }
        a.swing("body", 2, 2f, 0.1f);
        for (int i = 1; i <= 4; i++) {
            a.wave("flame" + i, Anim.Target.ROTATION, 2, 4 + i * 3, -i * 0.15f, 0, 2);
            a.wave("flame" + i, Anim.Target.ROTATION, 0, 3 + i * 2, -i * 0.15f + 0.25f, glide ? 8 + i * 3 : 0, 2);
        }
        spin(a, "rocks", 1);
        for (int i = 1; i <= 4; i++) {
            a.bob("rock" + i, 1, 0.8f, i * 0.25f, 2);
            a.swingN("rock" + i, 0, 30f, i * 0.2f, 1);
        }
        a.swing("arm_right", 2, 4f, 0f);
        a.swing("arm_left", 2, -4f, 0f);
        a.swing("forearm_right", 0, 7f, 0.2f);
        a.swing("forearm_left", 0, 7f, 0.7f);
        a.swingN("hair_plume", 0, 8f, 0, 4);
        a.swingN("hair_tip", 0, 14f, -0.1f, 4);
        a.swingN("hair_plume", 2, 5f, 0.3f, 2);
        a.swingN("hair_side_right", 2, 10f, 0.1f, 4);
        a.swingN("hair_side_left", 2, -10f, 0.35f, 4);
        a.swing("head", 1, 8f, 0.2f);
        return a;
    }

    // ============================================================================================ frost atronach

    /** Hulking ice golem: knuckle-walking fists, shard-crowned shoulders, a small head and a glowing core. */
    static CreatureDef frostAtronach() {
        Biped b = new Biped("frost_atronach");
        b.legLen = 14;
        b.legW = 6;
        b.hipW = 12;
        b.hipH = 4;
        b.torsoW = 16;
        b.torsoH = 14;
        b.torsoD = 10;
        b.hunch = 30;
        b.armLen = 20;
        b.armW = 6;
        b.handSize = 7;
        b.headW = 7;
        b.headH = 6;
        b.headD = 6;
        b.headForward = 7;
        b.skin = "packed_ice";
        b.headMat = "ice_head";
        b.armMat = "ice";
        b.legMat = "ice";
        b.handMat = "ice_fist";
        b.hipMat = "packed_ice";
        b.footMat = "ice_fist";
        b.stride = 20;
        b.build();
        // the small head sits low and forward between the shoulders
        b.head.y = -b.torsoH + 3;
        b.head.z = -3.5f;
        Bone t = b.torso;
        // glowing core between two slabs of chest ice
        t.box("core", -2.5f, -9, -5.8f, 5, 5, 1).tag("core");
        t.box("ice", -8, -10, -6.5f, 6, 6, 2).tag("slab");
        t.box("ice", 2, -10, -6.5f, 6, 6, 2).tag("slab");
        t.box("ice", -5, -3.5f, -6, 10, 3, 1).tag("slab");
        // ice spikes along the back
        float[][] back = {{-4, -13, 3, -30, 0, -15}, {3, -12, 2, -35, 0, 20}, {0, -9, 4, -55, 0, 0}, {-1, -14.5f, 1, -15, 0, 5}};
        for (int i = 0; i < back.length; i++) {
            Bone sp = t.child("back_shard" + (i + 1), back[i][0], back[i][1], back[i][2]).rot(back[i][3], back[i][4], back[i][5]);
            int len = i == 3 ? 7 : 5;
            sp.box("shard", -1, -len, -1, 2, len, 2).tag("shard");
            sp.box("shard", -0.5f, -len - 2, -0.5f, 1, 2, 1).tag("shard_tip");
        }
        for (Bone arm : b.arms) {
            arm.sbox("ice", -4, -4.5f, -4, 8, 6, 8).tag("shoulder");
            Bone s1 = arm.child("shoulder_shard", -1.5f, -4, 0).rot(-10, 0, -25);
            s1.sbox("shard", -1.5f, -7, -1.5f, 3, 7, 3).tag("shard");
            s1.sbox("shard", -1, -10, -1, 2, 3, 2).tag("shard_tip");
            Bone s2 = arm.child("shoulder_shard2", 1.5f, -4, 2).rot(-30, 0, -5);
            s2.sbox("shard", -1, -5, -1, 2, 5, 2).tag("shard");
            s2.sbox("shard", -0.5f, -7, -0.5f, 1, 2, 1).tag("shard_tip");
        }
        for (Bone fore : b.forearms) {
            fore.sbox("ice", -3, 2, -3, 6, 5, 6).tag("bracer");
            Bone sp = fore.child("forearm_shard", -2.5f, 3, 1).rot(-20, 0, -60);
            sp.sbox("shard", -1, -5, -1, 2, 5, 2).tag("shard");
            sp.sbox("shard", -0.5f, -7, -0.5f, 1, 2, 1).tag("shard_tip");
        }
        b.head.box("ice", -4, -6.5f, -4.2f, 8, 2, 2).tag("brow");
        b.head.box("shard", -1, -9, -1, 2, 3, 2).tag("crest");
        b.head.box("shard", -3.5f, -8, 0, 1, 2, 1).tag("crest");
        b.head.box("shard", 2.5f, -8, 0, 1, 2, 1).tag("crest");
        CreatureDef def = b.def();
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "death", "hurt");
    }

    // ============================================================================================ storm atronach

    /** Floating boulders bound round a crackling lightning core; no legs, a tapering column of rocks below. */
    static CreatureDef stormAtronach() {
        Rig r = new Rig("storm_atronach");
        Bone body = r.root().child("body", 0, -18, 0);
        body.box("rock", -3.5f, -2, -3, 7, 3, 6).tag("rock");
        body.child("thread", 0, 0, 0).box("lightning", -0.5f, 0, -0.5f, 1, 15, 1).tag("bolt");
        Bone c1 = body.child("col1", 0, 3.5f, 0);
        c1.box("rock", -3, -1.5f, -2.5f, 6, 4, 5).tag("rock");
        Bone c2 = c1.child("col2", 0.5f, 5, 0.3f);
        c2.box("rock", -2, -1.5f, -2, 4, 3, 4).tag("rock");
        Bone c3 = c2.child("col3", -0.5f, 4, 0.2f);
        c3.box("rock", -1.5f, -1.5f, -1.5f, 3, 3, 3).tag("rock");
        Bone c4 = c3.child("col4", 0.3f, 3.5f, 0);
        c4.box("rock", -1, -1, -1, 2, 2, 2).tag("rock");

        Bone chest = body.child("chest", 0, -2.5f, 0);
        Bone core = chest.child("core", 0, -6, -0.5f);
        core.box("lightning", -2, -2, -2, 4, 4, 4).tag("core");
        chest.box("rock", -7.5f, -11, -3, 5, 6, 7).tag("rock");
        chest.box("rock", 2.5f, -11, -3, 5, 6, 7).tag("rock");
        chest.box("rock", -5, -4.5f, -2.5f, 4, 4, 5).tag("rock");
        chest.box("rock", 1, -4.5f, -2.5f, 4, 4, 5).tag("rock");
        chest.box("rock", -4, -10, 2.5f, 8, 8, 3).tag("rock");
        chest.box("rock", -3, -13, -2, 6, 2, 5).tag("rock");
        Bone head = chest.child("head", 0, -14, -0.5f);
        head.box("rock", -3, -6, -3, 6, 6, 6).tag("head");
        head.box("rock", -2, -7.5f, -1.5f, 4, 2, 3).tag("rock");
        head.box("lightning", -0.5f, 0, -0.5f, 1, 2, 1).tag("bolt");
        chest.pair("arm", 9.5f, -10, 0.5f, (b, s) -> {
            b.rot(-5, 0, 10);
            b.sbox("rock", -4.5f, -4, -4, 8, 7, 8).tag("shoulder");
            b.sbox("rock", -3.5f, -6, -2, 4, 2, 4).tag("rock");
            Bone mid = b.child("arm_mid", 0, 4.5f, 0);
            mid.sbox("rock", -2, 0, -2, 4, 5, 4).tag("rock");
            mid.sbox("lightning", -0.5f, -1.5f, -0.5f, 1, 2, 1).tag("bolt");
            Bone fore = mid.child("forearm", 0, 6, 0).rot(-15, 0, 0);
            fore.sbox("rock", -2, 0, -2, 4, 5, 4).tag("rock");
            fore.sbox("lightning", -0.5f, -1.5f, -0.5f, 1, 2, 1).tag("bolt");
            Bone fist = fore.child("fist", 0, 6, 0);
            fist.sbox("rock", -2.5f, 0, -2.5f, 5, 5, 5).tag("fist");
            fist.sbox("lightning", -0.5f, -1.5f, -0.5f, 1, 2, 1).tag("bolt");
        });

        String[] floaters = {"col1", "col2", "col3", "col4", "head", "core", "arm_mid_right", "arm_mid_left",
                "forearm_right", "forearm_left", "fist_right", "fist_left"};
        CreatureDef def = new CreatureDef(r);
        def.gait(0.9f, 0.7f, 0.7f);
        def.look("head", 0.8f);
        def.anim("idle", stormIdle(Anim.loop(4f), floaters, false));
        def.anim("walk", stormIdle(Anim.loop(3.2f), floaters, true));

        Anim slam = Anim.once(1.0f);
        slam.rot("arm_right", 0, 0, 0, 0, 0.32f, -165, 0, 15, 0.46f, -55, 0, 0, 0.7f, -50, 0, 0, 1.0f, 0, 0, 0);
        slam.rot("forearm_right", 0, 0, 0, 0, 0.32f, -20, 0, 0, 0.46f, 15, 0, 0, 1.0f, 0, 0, 0);
        slam.pos("fist_right", 0, 0, 0, 0, 0.32f, 0, 1.5f, 0, 0.46f, 0, -2, 0, 0.7f, 0, -1, 0, 1.0f, 0, 0, 0);
        slam.rot("chest", 0, 0, 0, 0, 0.32f, -15, 15, 0, 0.46f, 22, -10, 0, 1.0f, 0, 0, 0);
        slam.rot("arm_left", 0, 0, 0, 0, 0.32f, 20, 0, -15, 0.46f, -15, 0, -10, 1.0f, 0, 0, 0);
        slam.pos("body", 0, 0, 0, 0, 0.32f, 0, 1, 1, 0.46f, 0, -2, -1.5f, 1.0f, 0, 0, 0);
        def.anim("attack", slam);

        Anim smash = Anim.once(1.2f);
        for (String sd : new String[]{"_right", "_left"}) {
            float z = sd.equals("_right") ? -15 : 15;
            smash.rot("arm" + sd, 0, 0, 0, 0, 0.45f, -170, 0, z, 0.6f, -60, 0, z * 0.3f, 0.85f, -55, 0, 0, 1.2f, 0, 0, 0);
            smash.pos("fist" + sd, 0, 0, 0, 0, 0.45f, 0, 2, 0, 0.6f, 0, -2, 0, 1.2f, 0, 0, 0);
        }
        smash.rot("chest", 0, 0, 0, 0, 0.45f, -20, 0, 0, 0.6f, 30, 0, 0, 0.85f, 28, 0, 0, 1.2f, 0, 0, 0);
        smash.pos("body", 0, 0, 0, 0, 0.45f, 0, 2, 0, 0.6f, 0, -3, 0, 0.85f, 0, -3, 0, 1.2f, 0, 0, 0);
        def.anim("attack2", smash);

        Anim cast = Anim.once(1.0f);
        cast.rot("arm_right", 0, 0, 0, 0, 0.3f, -40, 0, 50, 0.45f, -95, -15, 0, 0.7f, -90, -10, 0, 1.0f, 0, 0, 0);
        cast.rot("arm_left", 0, 0, 0, 0, 0.3f, -40, 0, -50, 0.45f, -95, 15, 0, 0.7f, -90, 10, 0, 1.0f, 0, 0, 0);
        cast.pos("fist_right", 0, 0, 0, 0, 0.45f, 0, -1.5f, 0, 0.7f, 0, -1.5f, 0, 1.0f, 0, 0, 0);
        cast.pos("fist_left", 0, 0, 0, 0, 0.45f, 0, -1.5f, 0, 0.7f, 0, -1.5f, 0, 1.0f, 0, 0, 0);
        cast.scale("core", 0, 1, 1, 1, 0.3f, 1.5f, 1.5f, 1.5f, 0.45f, 0.8f, 0.8f, 0.8f, 0.6f, 1.2f, 1.2f, 1.2f, 1.0f, 1, 1, 1);
        cast.rot("chest", 0, 0, 0, 0, 0.3f, -12, 0, 0, 0.45f, 8, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("head", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.45f, 5, 0, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);

        Anim death = Anim.once(1.0f);
        // the binding fails: the core gutters out and every rock drops away from its neighbours
        death.pos("body", 0, 0, 0, 0, 0.75f, 0, -5, 0, 1f, 0, -5.5f, 0);
        death.scale("core", 0, 1, 1, 1, 0.25f, 1.4f, 1.4f, 1.4f, 0.6f, 0.05f, 0.05f, 0.05f, 1f, 0.05f, 0.05f, 0.05f);
        death.scale("thread", 0, 1, 1, 1, 0.3f, 0.01f, 0.01f, 0.01f, 1f, 0.01f, 0.01f, 0.01f);
        death.pos("head", 0, 0, 0, 0, 0.6f, 1, -2, -3, 0.9f, 2, -5, -5, 1f, 2, -5, -5);
        death.rot("head", 0, 0, 0, 0, 0.9f, 50, 20, 30, 1f, 50, 20, 30);
        death.rot("chest", 0, 0, 0, 0, 0.75f, 25, 0, 0, 1f, 28, 0, 0);
        death.rot("arm_right", 0, 0, 0, 0, 0.7f, -30, 0, 35, 1f, -30, 0, 40);
        death.rot("arm_left", 0, 0, 0, 0, 0.75f, -20, 0, -30, 1f, -20, 0, -35);
        for (String sd : new String[]{"_right", "_left"}) {
            float m = sd.equals("_right") ? 1 : -1;
            death.pos("arm_mid" + sd, 0, 0, 0, 0, 0.8f, -m, -1.5f, 0, 1f, -m, -1.5f, 0);
            death.pos("forearm" + sd, 0, 0, 0, 0, 0.8f, -1.5f * m, -2, -1, 1f, -1.5f * m, -2, -1);
            death.pos("fist" + sd, 0, 0, 0, 0, 0.8f, -2 * m, -2.5f, -1, 1f, -2 * m, -2.5f, -1);
        }
        death.pos("col1", 0, 0, 0, 0, 0.6f, 2, -4, 1, 1f, 3, -6, 2);
        death.pos("col2", 0, 0, 0, 0, 0.6f, -4, 3, -1, 1f, -6, 4, -1);
        death.pos("col3", 0, 0, 0, 0, 0.6f, 4, 2, -3, 1f, 5, 3, -4);
        death.pos("col4", 0, 0, 0, 0, 0.6f, -3, 1.5f, 1, 1f, -4, 2, 1);
        death.rot("col1", 0, 0, 0, 0, 1f, 20, 30, 15);
        death.rot("col3", 0, 0, 0, 0, 1f, -30, 10, 40);
        def.anim("death", death);

        Anim hurt = Anim.once(0.45f);
        hurt.rot("chest", 0, 0, 0, 0, 0.1f, -10, 0, 0, 0.45f, 0, 0, 0);
        hurt.pos("head", 0, 0, 0, 0, 0.1f, 0, 1.5f, 1, 0.45f, 0, 0, 0);
        hurt.scale("core", 0, 1, 1, 1, 0.1f, 1.4f, 1.4f, 1.4f, 0.45f, 1, 1, 1);
        def.anim("hurt", hurt);
        return def;
    }

    private static Anim stormIdle(Anim a, String[] floaters, boolean glide) {
        a.bob("body", 1, 0.8f, 0, 1);
        for (int i = 0; i < floaters.length; i++) a.bob(floaters[i], 1, 0.5f, i * 0.13f, 2);
        for (int i = 1; i <= 4; i++) a.swing("col" + i, 1, (i % 2 == 0 ? -10 : 10), i * 0.1f, 0);
        spin(a, "core", 2);
        a.scale("core", 0, 1, 1, 1, a.length * 0.25f, 1.15f, 1.15f, 1.15f, a.length * 0.5f, 1, 1, 1,
                a.length * 0.75f, 1.15f, 1.15f, 1.15f, a.length, 1, 1, 1);
        a.swing("arm_right", 2, 3f, 0f);
        a.swing("arm_left", 2, -3f, 0f);
        a.swing("head", 1, 6f, 0.3f);
        if (glide) {
            a.hold("body", 10, 0, 0);
            for (int i = 1; i <= 4; i++) a.hold("col" + i, 6 + i * 2, 0, 0);
            a.swing("arm_right", 0, 14f, 0f);
            a.swing("arm_left", 0, 14f, 0.5f);
        }
        return a;
    }

    // ============================================================================================ dremora

    /** Tall horned daedra in black spiked daedric plate with red-glowing runes, wielding a greatsword. */
    static CreatureDef dremora() {
        Biped b = new Biped("dremora");
        b.legLen = 14;
        b.legW = 4;
        b.hipW = 8;
        b.hipH = 3;
        b.torsoW = 9;
        b.torsoH = 11;
        b.torsoD = 5;
        b.hunch = 4;
        b.armLen = 13;
        b.armW = 3;
        b.handSize = 3;
        b.headW = 5;
        b.headH = 6;
        b.headD = 5;
        b.skin = "armor";
        b.headMat = "face";
        b.handMat = "gauntlet";
        b.footMat = "armor";
        b.hipMat = "armor";
        b.stride = 30;
        b.build();
        Bone t = b.torso;
        t.box("armor", -5, -11, -3.3f, 10, 6, 1).tag("chestplate");
        t.box("armor", -4, -5, -3, 8, 4, 1).tag("abdomen_plate");
        t.box("armor", -3.5f, -12.5f, 0.5f, 7, 3, 2).tag("collar");
        t.box("spike", -3, -15, 1.5f, 1, 3, 1).tag("spike");
        t.box("spike", 2, -15, 1.5f, 1, 3, 1).tag("spike");
        t.box("cloth", -1.5f, -2, -3.1f, 3, 2, 1).tag("belt");
        b.hips.box("cloth", -1.5f, -1, -3.2f, 3, 9, 1).tag("tabard");
        b.hips.box("cloth", -3.5f, -1, 2.6f, 7, 10, 1).tag("tabard");
        for (Bone arm : b.arms) {
            arm.sbox("armor", -3, -3, -2.5f, 5, 3, 5, 0.1f).tag("pauldron");
            arm.sbox("armor", -2.5f, 0, -2, 1, 3, 4).tag("pauldron");
            Bone s1 = arm.child("pauldron_spike", -1.5f, -2.5f, -0.5f).rot(-10, 0, -30);
            s1.sbox("spike", -0.5f, -4, -0.5f, 1, 4, 1).tag("spike");
            Bone s2 = arm.child("pauldron_spike2", -1.5f, -2.5f, 1.5f).rot(-35, 0, -50);
            s2.sbox("spike", -0.5f, -3, -0.5f, 1, 3, 1).tag("spike");
        }
        for (Bone fore : b.forearms) {
            fore.sbox("armor", -1.5f, 2.5f, -1.5f, 3, 4, 3, 0.15f).tag("vambrace");
            fore.sbox("spike", -0.5f, -0.5f, 1, 1, 1, 2).tag("spike");
        }
        for (Bone leg : b.legs) {
            leg.sbox("armor", -2.5f, -1, -2.6f, 5, 6, 1).tag("tasset");
            leg.sbox("armor", -2.6f, -1, -2, 1, 6, 4).tag("tasset");
        }
        for (Bone shin : b.shins) {
            shin.sbox("armor", -2, -1, -2.6f, 4, 3, 1).tag("knee");
            shin.sbox("spike", -0.5f, -1.5f, -3.6f, 1, 2, 1).tag("spike");
            shin.sbox("armor", -2, 2.5f, -2, 4, 4, 4).tag("greave");
        }
        for (Bone foot : b.feet) foot.sbox("armor", -1, -0.5f, -4.5f, 2, 1, 1).tag("toe");
        b.forearms[0].rot(-40, 0, 0);
        b.forearms[1].rot(-15, 0, 0);
        b.head.box("hair", -2.5f, -6.4f, -2.6f, 5, 1, 5, 0.2f).tag("hair");
        b.head.box("hair", -1.5f, -5, 2, 3, 7, 1).tag("hair");
        b.head.pair("horn", 2, -5.5f, -0.5f, (h, s) -> {
            h.rot(-40, 0, -25);
            h.sbox("horn", -1, -3, -1, 2, 3, 2).tag("horn");
            Bone tip = h.child("horn_tip", 0, -3, 0).rot(-40, 0, 15);
            tip.sbox("horn", -0.5f, -3, -0.5f, 1, 3, 1).tag("horn_tip");
        });
        b.head.pair("ear", 2.5f, -3.5f, 0, (e, s) -> {
            e.rot(0, 25, 15);
            e.sbox("face", -2, -0.5f, -0.5f, 2, 1, 1).tag("ear");
        });
        // daedric greatsword in the right hand, held low and forward; the blade runs along -z
        Bone w = b.hands[0].child("weapon", 0, 1.5f, 0).rot(68, 0, 0);
        w.box("grip", -0.5f, -0.5f, -1.5f, 1, 1, 6).tag("grip");
        w.box("daedric", -1, -1, 4.5f, 2, 2, 2).tag("pommel");
        w.box("daedric", -1, -3.5f, -2.5f, 2, 7, 1).tag("guard");
        w.box("daedric", -0.5f, -4.5f, -4.5f, 1, 1, 2).tag("guard");
        w.box("daedric", -0.5f, 3.5f, -4.5f, 1, 1, 2).tag("guard");
        w.box("blade", -0.5f, -1.5f, -21.5f, 1, 3, 19).tag("blade");
        w.box("blade", -0.5f, -2.5f, -10, 1, 1, 3).tag("serration");
        w.box("blade", -0.5f, 1.5f, -14, 1, 1, 3).tag("serration");
        w.box("blade", -0.5f, -2.5f, -17, 1, 1, 2).tag("serration");
        w.box("blade", -0.5f, -1, -23.5f, 1, 2, 2).tag("tip");

        CreatureDef def = b.def();
        def.anim("run", drop(b.run(), "forearm_right"));
        // greatsword slash: wind up over the right shoulder, cut diagonally down to the left
        Anim slash = Anim.once(0.8f);
        slash.rot("arm_right", 0, 0, 0, 0, 0.3f, -150, 30, 30, 0.42f, -55, -40, -10, 0.6f, -35, -30, 0, 0.8f, 0, 0, 0);
        slash.rot("forearm_right", 0, 0, 0, 0, 0.3f, -15, 0, 0, 0.42f, 20, 0, 0, 0.8f, 0, 0, 0);
        slash.rot("weapon_right", 0, 0, 0, 0, 0.3f, -60, 0, 0, 0.42f, -40, 0, 0, 0.8f, 0, 0, 0);
        slash.rot("arm_left", 0, 0, 0, 0, 0.3f, -130, -10, -10, 0.42f, -60, 30, 10, 0.6f, -30, 20, 0, 0.8f, 0, 0, 0);
        slash.rot("forearm_left", 0, 0, 0, 0, 0.3f, -30, 0, 0, 0.42f, -20, 0, 0, 0.8f, 0, 0, 0);
        slash.rot("torso", 0, 0, 0, 0, 0.3f, -6, 30, 0, 0.42f, 10, -35, 0, 0.8f, 0, 0, 0);
        slash.rot("head", 0, 0, 0, 0, 0.3f, 0, -20, 0, 0.42f, 5, 20, 0, 0.8f, 0, 0, 0);
        slash.rot("leg_left", 0, 0, 0, 0, 0.42f, -20, 0, 0, 0.8f, 0, 0, 0);
        def.anim("attack", slash);
        // thrust: draw back, then drive the point straight forward
        Anim thrust = Anim.once(0.7f);
        thrust.rot("arm_right", 0, 0, 0, 0, 0.25f, 25, 0, 10, 0.38f, -78, 0, 0, 0.5f, -75, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("forearm_right", 0, 0, 0, 0, 0.25f, -60, 0, 0, 0.38f, 40, 0, 0, 0.5f, 40, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("weapon_right", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.38f, 10, 0, 0, 0.5f, 10, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("arm_left", 0, 0, 0, 0, 0.25f, -40, 0, -10, 0.38f, 30, 0, -10, 0.7f, 0, 0, 0);
        thrust.rot("torso", 0, 0, 0, 0, 0.25f, -4, 25, 0, 0.38f, 10, -12, 0, 0.7f, 0, 0, 0);
        thrust.rot("leg_right", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.38f, -35, 0, 0, 0.55f, -30, 0, 0, 0.7f, 0, 0, 0);
        thrust.rot("leg_left", 0, 0, 0, 0, 0.38f, 20, 0, 0, 0.7f, 0, 0, 0);
        thrust.pos("hips", 0, 0, 0, 0, 0.25f, 0, 0, 1, 0.38f, 0, -1, -3, 0.55f, 0, -1, -3, 0.7f, 0, 0, 0);
        def.anim("attack2", thrust);
        // overhead: both hands raise the blade behind the head and bring it down
        Anim over = Anim.once(1.1f);
        over.rot("arm_right", 0, 0, 0, 0, 0.5f, -170, 15, 0, 0.65f, -60, 5, 0, 0.85f, -55, 5, 0, 1.1f, 0, 0, 0);
        over.rot("forearm_right", 0, 0, 0, 0, 0.5f, 30, 0, 0, 0.65f, 30, 0, 0, 0.85f, 30, 0, 0, 1.1f, 0, 0, 0);
        over.rot("weapon_right", 0, 0, 0, 0, 0.5f, -85, 0, 0, 0.65f, 30, 0, 0, 0.85f, 30, 0, 0, 1.1f, 0, 0, 0);
        over.rot("arm_left", 0, 0, 0, 0, 0.5f, -165, -25, 0, 0.65f, -65, -25, 0, 0.85f, -60, -20, 0, 1.1f, 0, 0, 0);
        over.rot("torso", 0, 0, 0, 0, 0.5f, -15, 0, 0, 0.65f, 25, 0, 0, 0.85f, 22, 0, 0, 1.1f, 0, 0, 0);
        over.rot("leg_right", 0, 0, 0, 0, 0.6f, -25, 0, 0, 0.9f, -25, 0, 0, 1.1f, 0, 0, 0);
        over.pos("hips", 0, 0, 0, 0, 0.5f, 0, 0.5f, 0, 0.65f, 0, -1.5f, 0, 0.9f, 0, -1.5f, 0, 1.1f, 0, 0, 0);
        def.anim("attack3", over);
        // a spell from the free left hand
        Anim cast = Anim.once(1.0f);
        cast.rot("arm_left", 0, 0, 0, 0, 0.35f, -60, 30, -30, 0.5f, -90, -10, 0, 0.7f, -88, -5, 0, 1.0f, 0, 0, 0);
        cast.rot("forearm_left", 0, 0, 0, 0, 0.35f, -60, 0, 0, 0.5f, 15, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("torso", 0, 0, 0, 0, 0.35f, -6, -20, 0, 0.5f, 6, 15, 0, 1.0f, 0, 0, 0);
        cast.rot("head", 0, 0, 0, 0, 0.35f, 0, 15, 0, 0.5f, 0, -10, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);
        def.anim("special", b.roar());
        return only(def, "idle", "walk", "run", "attack", "attack2", "attack3", "cast", "special", "death", "hurt");
    }

    // ============================================================================================ scamp

    /** Small hunched fire daedra: orange-red leathery skin, flat face, big pointed ears, long arms, short legs. */
    static CreatureDef scamp() {
        Biped b = new Biped("scamp");
        b.legLen = 8;
        b.legW = 3;
        b.hipW = 6;
        b.hipH = 3;
        b.torsoW = 7;
        b.torsoH = 7;
        b.torsoD = 5;
        b.hunch = 30;
        b.armLen = 11;
        b.armW = 2;
        b.handSize = 2;
        b.headW = 6;
        b.headH = 5;
        b.headD = 5;
        b.headForward = 2;
        b.jaw = true;
        b.jawH = 2;
        b.digitigrade = true;
        b.tailSegs = 3;
        b.tailLen = 6;
        b.tailW = 2;
        b.skin = "scamp_skin";
        b.headMat = "scamp_skin";
        b.handMat = "scamp_dark";
        b.footMat = "scamp_dark";
        b.stride = 32;
        b.build();
        b.torso.box("scamp_belly", -2.5f, -6, -3, 5, 6, 1).tag("belly");
        b.torso.box("scamp_skin", -1, -7.5f, 1.8f, 2, 3, 1).tag("ridge");
        b.torso.box("scamp_skin", -1, -3.5f, 2, 2, 2, 1).tag("ridge");
        b.head.box("scamp_skin", -1, -3, -3.5f, 2, 1, 1).tag("nose");
        b.head.box("scamp_skin", -3, -4.5f, -3.2f, 6, 1, 1).tag("brow");
        b.head.box("scamp_skin", -1.5f, -5.6f, -1.5f, 3, 1, 3).tag("ridge");
        b.head.pair("ear", 3, -3.5f, 0, (e, s) -> {
            e.rot(0, 22, 10);
            e.sbox("scamp_skin", -4, -1.5f, -0.5f, 4, 3, 1).tag("ear");
            Bone tip = e.child("ear_tip", -4, 0, 0).rot(0, 12, 8);
            tip.sbox("scamp_skin", -3, -1, -0.5f, 3, 2, 1).tag("ear");
            tip.sbox("scamp_skin", -5, -0.5f, -0.5f, 2, 1, 1).tag("ear_tip");
        });
        for (Bone hand : b.hands) {
            hand.sbox("claw", -1, 1.5f, -1, 1, 2, 1).tag("claw");
            hand.sbox("claw", 0, 1.5f, -0.5f, 1, 2, 1).tag("claw");
        }
        b.tail[b.tail.length - 1].box("scamp_dark", -1, -1, 2, 2, 2, 1).tag("tail_tip");

        CreatureDef def = b.def();
        def.anim("attack", b.swipe());
        // cackle: head thrown back, jaw chattering, hands on the belly, bouncing
        Anim c = Anim.once(1.6f);
        c.rot("head", 0, 0, 0, 0, 0.2f, -30, 0, 0, 0.8f, -25, 10, 0, 1.3f, -28, -10, 0, 1.6f, 0, 0, 0);
        c.linear().rot("jaw", 0, 0, 0, 0, 0.2f, 35, 0, 0, 0.35f, 8, 0, 0, 0.5f, 35, 0, 0, 0.65f, 8, 0, 0, 0.8f, 35, 0, 0,
                0.95f, 8, 0, 0, 1.1f, 35, 0, 0, 1.3f, 0, 0, 0, 1.6f, 0, 0, 0).smooth();
        c.linear().pos("hips", 0, 0, 0, 0, 0.2f, 0, 1.5f, 0, 0.35f, 0, 0, 0, 0.5f, 0, 1.5f, 0, 0.65f, 0, 0, 0, 0.8f, 0, 1.5f, 0,
                0.95f, 0, 0, 0, 1.1f, 0, 1.2f, 0, 1.3f, 0, 0, 0, 1.6f, 0, 0, 0).smooth();
        c.rot("torso", 0, 0, 0, 0, 0.2f, -20, 0, 0, 1.3f, -18, 0, 0, 1.6f, 0, 0, 0);
        c.rot("arm_right", 0, 0, 0, 0, 0.2f, -35, 0, -25, 1.3f, -35, 0, -25, 1.6f, 0, 0, 0);
        c.rot("arm_left", 0, 0, 0, 0, 0.2f, -35, 0, 25, 1.3f, -35, 0, 25, 1.6f, 0, 0, 0);
        c.rot("forearm_right", 0, 0, 0, 0, 0.2f, -70, 0, 0, 1.3f, -70, 0, 0, 1.6f, 0, 0, 0);
        c.rot("forearm_left", 0, 0, 0, 0, 0.2f, -70, 0, 0, 1.3f, -70, 0, 0, 1.6f, 0, 0, 0);
        c.rot("tail1", 0, 0, 0, 0, 0.2f, -20, 25, 0, 0.6f, -20, -25, 0, 1.0f, -20, 25, 0, 1.6f, 0, 0, 0);
        def.anim("special", c);
        return only(def, "idle", "walk", "run", "attack", "cast", "special", "death", "hurt");
    }

    // ============================================================================================ imp

    /** Small winged daedra: gnarled grey-green skin, horns, big ears, bat wings, a thin spade-tipped tail. */
    static CreatureDef imp() {
        Biped b = new Biped("imp");
        b.legLen = 6;
        b.legW = 2;
        b.hipW = 4;
        b.hipH = 2;
        b.torsoW = 5;
        b.torsoH = 5;
        b.torsoD = 3;
        b.hunch = 15;
        b.armLen = 7;
        b.armW = 2;
        b.headW = 5;
        b.headH = 4;
        b.headD = 4;
        b.headForward = 1;
        b.jaw = true;
        b.jawH = 1;
        b.digitigrade = true;
        b.tailSegs = 4;
        b.tailLen = 8;
        b.tailW = 1;
        b.wings = true;
        b.wingLen = 6;
        b.wingSpan = 12;
        b.skin = "imp_skin";
        b.wingMat = "membrane";
        b.footMat = "imp_dark";
        b.stride = 34;
        b.build();
        b.torso.box("imp_belly", -1.5f, -4.5f, -2, 3, 4, 1).tag("belly");
        b.head.box("imp_skin", -0.5f, -2.5f, -3.5f, 1, 1, 1).tag("nose");
        b.head.box("imp_dark", -2.5f, -3.6f, -3.2f, 5, 1, 1).tag("brow");
        b.head.pair("horn", 1.5f, -3.5f, -0.5f, (h, s) -> {
            h.rot(-30, 0, -20);
            h.sbox("horn", -0.5f, -2, -0.5f, 1, 2, 1, 0.1f).tag("horn");
            Bone tip = h.child("horn_tip", 0, -2, 0).rot(-35, 0, 10);
            tip.sbox("horn", -0.5f, -2, -0.5f, 1, 2, 1, -0.1f).tag("horn_tip");
        });
        b.head.pair("ear", 2.5f, -2.5f, 0.5f, (e, s) -> {
            e.rot(0, 30, 12);
            e.sbox("imp_skin", -4, -1, -0.5f, 4, 3, 1).tag("ear");
            Bone tip = e.child("ear_tip", -4, 0, 0).rot(0, 10, 12);
            tip.sbox("imp_skin", -3, -0.5f, -0.5f, 3, 2, 1).tag("ear_tip");
        });
        for (Bone hand : b.hands) {
            hand.sbox("claw", -1, -0.5f, -1, 2, 1, 2).tag("hand");
            hand.sbox("claw", -1, 0.5f, -1, 1, 1, 1).tag("claw");
        }
        b.tail[b.tail.length - 1].box("imp_dark", -1.5f, -0.5f, 2, 3, 1, 2).tag("spade");
        CreatureDef def = b.def();
        // on the ground the bat wings fold along the back
        def.anim("idle", impFold(drop(b.idle(), "wing_right", "wing_left")));
        def.anim("walk", impFold(b.walk()));
        def.anim("attack", impFold(b.hook()));
        def.anim("cast", impFold(b.cast()));
        def.anim("death", impFold(b.death()));
        def.anim("hurt", impFold(b.hurt()));
        return only(def, "idle", "walk", "fly", "hover", "attack", "cast", "death", "hurt");
    }

    private static Anim impFold(Anim a) {
        a.hold("wing_right", 15, 95, -30);
        a.hold("wing_left", 15, -95, 30);
        a.hold("wing_tip_right", 0, 150, 0);
        a.hold("wing_tip_left", 0, -150, 0);
        if (a.loop) {
            a.swing("wing_right", 2, 3f, 0f);
            a.swing("wing_left", 2, -3f, 0f);
        }
        return a;
    }

    // ============================================================================================ dragon priest

    /** Floating mummified Nordic lich in ragged ceremonial robes, staff in hand, a dragon mask over its face. */
    static CreatureDef dragonPriest() {
        Rig r = new Rig("dragon_priest");
        Bone body = r.root().child("body", 0, -18, 0);
        body.box("robe", -4, -1, -3, 8, 5, 6).tag("robe");
        body.box("trim", -4.5f, -1.5f, -3.5f, 9, 1, 7).tag("belt");
        Bone skirt = body.child("skirt", 0, 3.5f, 0);
        skirt.box("robe", -5, 0, -4, 10, 5, 8).tag("robe");
        Bone low = skirt.child("skirt_low", 0, 4.5f, 0.3f);
        low.box("robe_dark", -5.5f, 0, -4.5f, 11, 3, 9).tag("hem");
        // ragged strips hanging from the hem, each swaying on its own: 1-4 front, 5 right, 6 left, 7-9 back
        float[][] strips = {{-4.6f, -4.7f, 0}, {-2.2f, -4.8f, 0}, {0.3f, -4.7f, 0}, {2.7f, -4.8f, 0},
                {-5.7f, -1.5f, 1}, {4.7f, 0.2f, 1}, {-4.2f, 3.7f, 0}, {-1.2f, 3.8f, 0}, {1.8f, 3.7f, 0}};
        for (int i = 0; i < strips.length; i++) {
            float[] sp = strips[i];
            Bone st = low.child("strip" + (i + 1), sp[0], 2.5f, sp[1]);
            int len = 4 + (i * 7) % 3;
            if (sp[2] == 1) st.box("rag", 0, 0, 0, 1, len, 2).tag("rag");
            else st.box("rag", 0, 0, 0, 2, len, 1).tag("rag");
        }
        Bone chest = body.child("chest", 0, -1, 0);
        Bone cape = chest.child("cape", 0, -9.5f, 2.3f).rot(12, 0, 0);
        cape.box("robe_dark", -4.5f, 0, 0, 9, 17, 1).tag("cape");
        chest.box("robe", -3.5f, -9, -2, 7, 9, 4).tag("chest");
        chest.box("mantle", -5.5f, -9.5f, -2.5f, 11, 3, 5).tag("mantle");
        chest.box("mantle", -3.5f, -12, 1, 7, 3, 1).tag("collar");
        chest.box("tabard", -1.5f, -6.5f, -2.6f, 3, 13, 1).tag("tabard");
        chest.box("bone", -2.5f, -6, -2.3f, 5, 1, 1).tag("ribs");
        Bone head = chest.child("head", 0, -9.5f, -0.5f);
        head.box("mummy", -2.5f, -6, -2.5f, 5, 6, 5).tag("head");
        head.box("robe_dark", -3, -6.5f, 0, 6, 7, 3).tag("hood");
        Bone mask = head.child("mask", 0, -3, -2.5f);
        mask.box("mask", -2.5f, -3.5f, -1, 5, 7, 1).tag("mask");
        mask.box("mask", -0.5f, -6, -1.4f, 1, 3, 1).tag("crest");
        mask.box("mask", -3.5f, -5, -0.6f, 1, 4, 1).tag("flare");
        mask.box("mask", 2.5f, -5, -0.6f, 1, 4, 1).tag("flare");
        mask.box("mask", -0.5f, -1.5f, -2, 1, 3, 1).tag("snout");
        mask.box("mask", -1.5f, 3.5f, -1.2f, 3, 1, 1).tag("chin");
        chest.pair("arm", 4.5f, -8, 0, (b, s) -> {
            b.rot(-5, 0, 6);
            b.sbox("robe", -1.5f, -1, -1.5f, 3, 6, 3).tag("sleeve");
            Bone fore = b.child("forearm", 0, 5, 0).rot(-20, 0, 0);
            fore.sbox("robe_dark", -2, 0, -2, 4, 5, 4).tag("sleeve");
            Bone hand = fore.child("hand", 0, 5, 0);
            hand.sbox("bone", -1, -0.5f, -1, 2, 2, 2).tag("hand");
            hand.sbox("bone", -1, 1.5f, -1, 1, 2, 1).tag("finger");
            hand.sbox("bone", 0, 1.5f, -1, 1, 2, 1).tag("finger");
        });
        Bone staff = r.bone("hand_right").child("weapon", 0, 0.5f, 0).rot(25, 0, 0);
        staff.box("staff", -0.5f, -17, -0.5f, 1, 28, 1).tag("staff");
        staff.box("staff_metal", -1.5f, -20, -1.5f, 3, 3, 3).tag("staff_head");
        staff.box("staff_metal", -1, -22, -1, 2, 2, 2).tag("staff_orb");
        staff.box("staff_metal", -2.5f, -19, -0.5f, 1, 3, 1).tag("staff_claw");
        staff.box("staff_metal", 1.5f, -19, -0.5f, 1, 3, 1).tag("staff_claw");
        staff.box("staff_metal", -1, 11, -1, 2, 2, 2).tag("staff_foot");

        CreatureDef def = new CreatureDef(r);
        def.gait(0.8f, 0.6f, 0.7f);
        def.look("head", 0.9f);
        def.anim("idle", priestFloat(Anim.loop(4f), 0, 1f));
        def.anim("hover", priestFloat(Anim.loop(2.5f), 0, 1.4f));
        def.anim("walk", priestFloat(Anim.loop(2.4f), 12, 1.2f));
        def.anim("fly", priestFloat(Anim.loop(1.6f), 28, 1.6f));

        Anim swing = Anim.once(0.85f);
        swing.rot("arm_right", 0, 0, 0, 0, 0.28f, -150, 0, 40, 0.42f, -70, -45, -20, 0.6f, -50, -35, -10, 0.85f, 0, 0, 0);
        swing.rot("forearm_right", 0, 0, 0, 0, 0.28f, -10, 0, 0, 0.42f, 10, 0, 0, 0.85f, 0, 0, 0);
        swing.rot("weapon_right", 0, 0, 0, 0, 0.28f, -30, 0, 0, 0.42f, -70, 0, 0, 0.6f, -60, 0, 0, 0.85f, 0, 0, 0);
        swing.rot("chest", 0, 0, 0, 0, 0.28f, -8, 30, 0, 0.42f, 10, -30, 0, 0.85f, 0, 0, 0);
        swing.rot("arm_left", 0, 0, 0, 0, 0.28f, 15, 0, -30, 0.42f, -20, 0, -20, 0.85f, 0, 0, 0);
        swing.pos("body", 0, 0, 0, 0, 0.28f, 0, 1, 1, 0.42f, 0, 0, -2, 0.85f, 0, 0, 0);
        def.anim("attack", swing);

        Anim cast = Anim.once(1.0f);
        cast.rot("arm_right", 0, 0, 0, 0, 0.35f, -30, 0, 15, 0.48f, -75, 0, 0, 0.7f, -72, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("forearm_right", 0, 0, 0, 0, 0.35f, -30, 0, 0, 0.48f, 15, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("weapon_right", 0, 0, 0, 0, 0.35f, -10, 0, 0, 0.48f, -50, 0, 0, 0.7f, -48, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("arm_left", 0, 0, 0, 0, 0.35f, -100, 0, -30, 0.48f, -85, 15, -10, 0.7f, -80, 10, -10, 1.0f, 0, 0, 0);
        cast.rot("forearm_left", 0, 0, 0, 0, 0.35f, -40, 0, 0, 0.48f, 0, 0, 0, 1.0f, 0, 0, 0);
        cast.rot("chest", 0, 0, 0, 0, 0.35f, -10, 0, 0, 0.48f, 12, 0, 0, 1.0f, 0, 0, 0);
        cast.pos("body", 0, 0, 0, 0, 0.35f, 0, 1, 1, 0.48f, 0, 0, -1.5f, 1.0f, 0, 0, 0);
        for (int i = 1; i <= STRIPS; i++) cast.rot("strip" + i, 0, 0, 0, 0, 0.48f, 30, 0, 0, 1.0f, 0, 0, 0);
        def.anim("cast", cast);

        Anim summon = Anim.once(1.5f);
        summon.rot("arm_right", 0, 0, 0, 0, 0.45f, -160, 0, 25, 1.1f, -160, 0, 25, 1.5f, 0, 0, 0);
        summon.rot("arm_left", 0, 0, 0, 0, 0.45f, -160, 0, -25, 1.1f, -160, 0, -25, 1.5f, 0, 0, 0);
        summon.rot("forearm_right", 0, 0, 0, 0, 0.45f, 20, 0, 0, 1.1f, 20, 0, 0, 1.5f, 0, 0, 0);
        summon.rot("forearm_left", 0, 0, 0, 0, 0.45f, 20, 0, 0, 1.1f, 20, 0, 0, 1.5f, 0, 0, 0);
        summon.rot("weapon_right", 0, 0, 0, 0, 0.45f, -20, 0, 0, 1.1f, -20, 0, 0, 1.5f, 0, 0, 0);
        summon.rot("head", 0, 0, 0, 0, 0.45f, -25, 0, 0, 1.1f, -25, 0, 0, 1.5f, 0, 0, 0);
        summon.rot("chest", 0, 0, 0, 0, 0.45f, -12, 0, 0, 1.1f, -12, 0, 0, 1.5f, 0, 0, 0);
        summon.pos("body", 0, 0, 0, 0, 0.45f, 0, 3, 0, 1.1f, 0, 3, 0, 1.5f, 0, 0, 0);
        flare(summon, 0.45f, 1.1f, 1.5f, 35);
        def.anim("summon", summon);

        Anim tele = Anim.once(1.3f);
        tele.linear().rot("body", 0, 0, 0, 0, 0.9f, 0, 360, 0, 1.3f, 0, 360, 0).smooth();
        tele.scale("skirt", 0, 1, 1, 1, 0.45f, 1.35f, 1.0f, 1.35f, 0.9f, 1.35f, 1.0f, 1.35f, 1.3f, 1, 1, 1);
        tele.scale("skirt_low", 0, 1, 1, 1, 0.45f, 1.3f, 1.1f, 1.3f, 0.9f, 1.3f, 1.1f, 1.3f, 1.3f, 1, 1, 1);
        tele.rot("arm_right", 0, 0, 0, 0, 0.3f, -40, 0, 70, 1.0f, -40, 0, 70, 1.3f, 0, 0, 0);
        tele.rot("arm_left", 0, 0, 0, 0, 0.3f, -40, 0, -70, 1.0f, -40, 0, -70, 1.3f, 0, 0, 0);
        tele.pos("body", 0, 0, 0, 0, 0.45f, 0, 2.5f, 0, 0.9f, 0, 2.5f, 0, 1.3f, 0, 0, 0);
        tele.scale("body", 0, 1, 1, 1, 0.6f, 0.85f, 1.1f, 0.85f, 0.8f, 1.05f, 0.95f, 1.05f, 1.3f, 1, 1, 1);
        flare(tele, 0.3f, 1.0f, 1.3f, 70);
        def.anim("special", tele);

        Anim death = Anim.once(1f);
        death.pos("body", 0, 0, 0, 0, 0.7f, 0, -13, 0, 1f, 0, -13.5f, 0);
        death.scale("skirt", 0, 1, 1, 1, 0.7f, 1.3f, 0.35f, 1.3f, 1f, 1.35f, 0.3f, 1.35f);
        death.scale("skirt_low", 0, 1, 1, 1, 0.7f, 1.2f, 0.5f, 1.2f, 1f, 1.25f, 0.45f, 1.25f);
        death.rot("chest", 0, 0, 0, 0, 0.6f, 40, 0, 8, 1f, 50, 0, 10);
        death.rot("head", 0, 0, 0, 0, 0.6f, 35, 20, 0, 1f, 40, 25, 0);
        death.rot("arm_right", 0, 0, 0, 0, 0.6f, -50, 0, 35, 1f, -45, 0, 45);
        death.rot("arm_left", 0, 0, 0, 0, 0.6f, -40, 0, -35, 1f, -35, 0, -45);
        death.rot("weapon_right", 0, 0, 0, 0, 0.6f, 40, 0, 30, 1f, 60, 0, 40);
        flare(death, 0.8f, 1f, 1f, 60);
        death.rot("cape", 0, 0, 0, 0, 0.7f, -35, 0, 0, 1f, -45, 0, 0);
        def.anim("death", death);

        Anim hurt = Anim.once(0.45f);
        hurt.rot("chest", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.45f, 0, 0, 0);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.45f, 0, 0, 0);
        hurt.pos("body", 0, 0, 0, 0, 0.1f, 0, 0, 1.5f, 0.45f, 0, 0, 0);
        def.anim("hurt", hurt);
        return def;
    }

    /** Robe strips flare outward (front ones forward, side ones out, back ones back) between t1 and t2. */
    private static void flare(Anim a, float t1, float t2, float end, float ang) {
        for (int i = 1; i <= STRIPS; i++) {
            float x = i <= 4 ? -ang : (i >= 7 ? ang : 0);
            float z = i == 5 ? ang : (i == 6 ? -ang : 0);
            if (end > t2) a.rot("strip" + i, 0, 0, 0, 0, t1, x, 0, z, t2, x, 0, z, end, 0, 0, 0);
            else a.rot("strip" + i, 0, 0, 0, 0, t1, x, 0, z, t2, x, 0, z);
        }
        if (end > t2) a.rot("cape", 0, 0, 0, 0, t1, ang * 0.6f, 0, 0, t2, ang * 0.6f, 0, 0, end, 0, 0, 0);
    }

    private static final int STRIPS = 9;

    /** Hovering loop: the body bobs, robes and strips stream behind by {@code lean} degrees. */
    private static Anim priestFloat(Anim a, float lean, float amp) {
        a.bob("body", 1, amp, 0, 1);
        a.swing("body", 2, 1.5f, 0.2f);
        if (lean > 0) {
            a.hold("body", lean * 0.6f, 0, 0);
            a.hold("arm_right", lean * 0.5f, 0, 0);
            a.hold("arm_left", lean, 0, 0);
        }
        a.swing("skirt", 0, 3f * amp, 0.1f, lean * 0.4f);
        a.swing("skirt_low", 0, 4f * amp, 0.0f, lean * 0.5f);
        for (int i = 1; i <= STRIPS; i++) {
            a.wave("strip" + i, Anim.Target.ROTATION, 0, 8 * amp, i * 0.13f, lean * 1.2f, 2);
            a.wave("strip" + i, Anim.Target.ROTATION, 2, 5 * amp, i * 0.21f + 0.3f, 0, 1);
        }
        a.wave("cape", Anim.Target.ROTATION, 0, 3 * amp, 0.15f, lean * 0.9f, 1);
        a.swing("arm_left", 0, 5f, 0.3f);
        a.swing("arm_left", 2, -4f, 0.1f);
        a.swing("arm_right", 0, 3f, 0.6f);
        a.swing("head", 1, 6f, 0.25f);
        a.swing("chest", 0, 2f, 0.4f);
        return a;
    }

    // ============================================================================================ dwarven spider

    /** Small Dwemer automaton: a round riveted bronze body with a glowing soul gem, six pointed legs, a shock prong. */
    static CreatureDef dwarvenSpider() {
        Arachnid a = new Arachnid("dwarven_spider");
        a.bodyW = 8;
        a.bodyH = 6;
        a.bodyL = 8;
        a.headW = 4;
        a.headH = 3;
        a.headL = 3;
        a.fangLen = 0;
        a.abdomenW = 6;
        a.abdomenH = 5;
        a.abdomenL = 3;
        a.abdomenTilt = 0;
        a.legPairs = 3;
        a.legUpper = 7;
        a.legLower = 8;
        a.legW = 2;
        a.legUp = 45;
        a.legDown = 72;
        a.shell = "dwemer";
        a.legMat = "dwemer_dark";
        a.abdomenMat = "dwemer";
        a.build();
        Bone body = a.body;
        body.box("dwemer", -3, -4, -3, 6, 1, 6).tag("dome");
        body.box("dwemer", -5, -2, -3, 10, 4, 6).tag("hull");
        body.box("dwemer", -3, 3, -3, 6, 1, 6).tag("keel");
        body.box("trim", -1.5f, -5, -1.5f, 3, 1, 3).tag("cage");
        body.box("soulgem", -1, -6.5f, -1, 2, 2, 2).tag("gem");
        body.box("trim", -4.5f, -0.5f, -4.4f, 9, 1, 1).tag("band");
        a.head.box("trim", -1.5f, -2.3f, -2.5f, 3, 1, 2).tag("crest");
        a.head.pair("prong", 1, 0.5f, -3, (p, s) -> {
            p.rot(10, 12, 0);
            p.sbox("dwemer_dark", -0.5f, -0.5f, -4, 1, 1, 4).tag("prong");
            p.sbox("shock", -0.5f, -0.5f, -5, 1, 1, 1).tag("spark");
        });
        for (int i = 1; i <= 3; i++) {
            for (String sd : new String[]{"_right", "_left"}) {
                Bone up = a.rig.bone("leg" + i + "_upper" + sd);
                up.sbox("trim", -1.5f, -1.5f, -1.5f, 3, 3, 3).tag("joint");
            }
        }
        CreatureDef def = a.def();
        Anim stab = Anim.once(0.6f);
        stab.rot("body", 0, 0, 0, 0, 0.22f, -15, 0, 0, 0.35f, 10, 0, 0, 0.6f, 0, 0, 0);
        stab.pos("body", 0, 0, 0, 0, 0.22f, 0, 1, 1.5f, 0.35f, 0, -0.5f, -2.5f, 0.6f, 0, 0, 0);
        stab.rot("head", 0, 0, 0, 0, 0.22f, -20, 0, 0, 0.35f, 12, 0, 0, 0.6f, 0, 0, 0);
        stab.pos("head", 0, 0, 0, 0, 0.22f, 0, 0, 1, 0.35f, 0, 0, -2, 0.6f, 0, 0, 0);
        stab.rot("prong_right", 0, 0, 0, 0, 0.22f, 0, 20, 0, 0.35f, 0, -8, 0, 0.6f, 0, 0, 0);
        stab.rot("prong_left", 0, 0, 0, 0, 0.22f, 0, -20, 0, 0.35f, 0, 8, 0, 0.6f, 0, 0, 0);
        def.anim("attack", stab);
        Anim lunge = Anim.once(0.8f);
        lunge.pos("body", 0, 0, 0, 0, 0.25f, 0, -1.5f, 2, 0.4f, 0, 4, -5, 0.55f, 0, 0, -6, 0.8f, 0, 0, 0);
        lunge.rot("body", 0, 0, 0, 0, 0.25f, 8, 0, 0, 0.4f, -15, 0, 0, 0.55f, 8, 0, 0, 0.8f, 0, 0, 0);
        for (String sd : new String[]{"_right", "_left"}) {
            float m = sd.equals("_right") ? 1 : -1;
            lunge.rot("leg1" + sd, 0, 0, 0, 0, 0.25f, 0, 0, -10 * m, 0.4f, 0, 15 * m, 35 * m, 0.55f, 0, 0, -5 * m, 0.8f, 0, 0, 0);
            lunge.rot("leg3" + sd, 0, 0, 0, 0, 0.25f, 0, 0, -15 * m, 0.4f, 0, -20 * m, 20 * m, 0.8f, 0, 0, 0);
        }
        lunge.rot("prong_right", 0, 0, 0, 0, 0.4f, 0, 20, 0, 0.55f, 0, -5, 0, 0.8f, 0, 0, 0);
        lunge.rot("prong_left", 0, 0, 0, 0, 0.4f, 0, -20, 0, 0.55f, 0, 5, 0, 0.8f, 0, 0, 0);
        def.anim("attack2", lunge);
        return only(def, "idle", "walk", "run", "attack", "attack2", "death", "hurt");
    }

    // ============================================================================================ dwarven sphere

    /** Dwemer sphere: a bronze ball that unfolds into a helmeted torso with a blade arm and a crossbow-shield arm. */
    static CreatureDef dwarvenSphere() {
        Rig r = new Rig("dwarven_sphere");
        Bone mount = r.root().child("ball_mount", 0, -5, 0);
        Bone ball = mount.child("ball", 0, 0, 0);
        ball.box("dwemer", -4, -4, -4, 8, 8, 8).tag("ball");
        ball.box("dwemer", -5, -3, -3, 10, 6, 6).tag("ball");
        ball.box("dwemer", -3, -5, -3, 6, 10, 6).tag("ball");
        ball.box("dwemer", -3, -3, -5, 6, 6, 10).tag("ball");
        ball.box("trim", -5.2f, -1, -5.2f, 10, 2, 10, 0.1f).tag("band");
        ball.box("trim", -5.5f, -2, -2, 1, 4, 4).tag("hub");
        ball.box("trim", 4.5f, -2, -2, 1, 4, 4).tag("hub");

        Bone torso = r.root().child("torso", 0, -9.5f, 0);
        torso.box("dwemer_dark", -1.5f, -4, -1.5f, 3, 5, 3).tag("piston");
        torso.box("trim", -2.5f, -1, -2.5f, 5, 1, 5).tag("collar");
        Bone chest = torso.child("chest", 0, -4, 0);
        chest.box("dwemer", -4, -8, -3, 8, 8, 6).tag("chest");
        chest.box("dwemer", -3, 0, -2, 6, 1, 4).tag("waist");
        chest.box("trim", -3, -7, -3.5f, 6, 4, 1).tag("chestplate");
        chest.box("core", -1, -2.5f, -3.4f, 2, 2, 1).tag("core");
        chest.box("dwemer_dark", -2.5f, -6, 3, 5, 5, 1).tag("backplate");
        Bone head = chest.child("head", 0, -8, -0.3f);
        head.box("dwemer", -2.5f, -5, -3, 5, 5, 5).tag("head");
        head.box("trim", -0.5f, -6.5f, -3.5f, 1, 2, 6).tag("crest");
        head.box("dwemer_dark", -2, -3.6f, -3.4f, 4, 1, 1).tag("visor");
        head.box("trim", -1.5f, -2.2f, -3.6f, 3, 2, 1).tag("beard");
        head.box("dwemer", -3, -3.5f, -2.5f, 1, 3, 4).tag("cheek");
        head.box("dwemer", 2, -3.5f, -2.5f, 1, 3, 4).tag("cheek");
        chest.pair("arm", 5, -6.5f, 0, (b, s) -> {
            b.rot(-5, 0, 8);
            b.sbox("dwemer", -2, -2, -2, 4, 4, 4).tag("shoulder");
            b.sbox("dwemer_dark", -1, 2, -1, 2, 4, 2).tag("arm");
        });
        // right forearm ends in a blade
        Bone fr = r.bone("arm_right").child("forearm", 0, 5.5f, 0).rot(-30, 0, 0);
        fr.box("dwemer", -1.5f, 0, -1.5f, 3, 5, 3).tag("forearm");
        Bone blade = fr.child("blade", 0, 4.5f, 0).rot(45, 0, 0);
        blade.box("dwemer", -1, -1.5f, -2, 2, 3, 3).tag("socket");
        blade.box("blade", -0.5f, -1, -13, 1, 2, 11).tag("blade");
        blade.box("blade", -0.5f, -0.5f, -14, 1, 1, 1).tag("tip");
        // left forearm carries the crossbow and a round shield
        Bone fl = r.bone("arm_left").child("forearm", 0, 5.5f, 0).rot(-70, 0, 0);
        fl.box("dwemer", -1.5f, 0, -1.5f, 3, 6, 3).tag("forearm");
        fl.box("trim", 1.5f, -0.5f, -3.5f, 1, 7, 7).tag("shield");
        fl.box("dwemer", 2.5f, 1.5f, -1.5f, 1, 3, 3).tag("boss");
        Bone xbow = fl.child("crossbow", 0, 3, -2.5f);
        xbow.box("dwemer_dark", -0.5f, -3, -1, 1, 8, 1).tag("stock");
        xbow.box("trim", -4, 4, -1, 8, 1, 1).tag("prod");
        xbow.box("bolt", -0.5f, -1, -1.8f, 1, 6, 1, -0.2f).tag("bolt");

        CreatureDef def = new CreatureDef(r);
        def.gait(0.5f, 0.3f, 0.6f);
        def.look("head", 0.8f);
        def.look("chest", 0.2f);
        Anim idle = Anim.loop(3f);
        idle.bob("torso", 1, 0.4f, 0, 1);
        idle.swing("chest", 1, 4f, 0.1f);
        idle.swing("head", 1, 10f, 0.3f);
        idle.swing("arm_right", 0, 3f, 0.2f);
        idle.swing("arm_left", 0, 3f, 0.7f);
        idle.swing("ball", 0, 4f, 0f);
        def.anim("idle", idle);

        Anim sleep = Anim.loop(4f);
        closed(sleep, 0, sleep.length);
        sleep.wave("torso", Anim.Target.POSITION, 1, 0.15f, 0, -9.5f, 1);
        def.anim("sleep", sleep);

        Anim emerge = Anim.once(1.6f);
        float[] e = {0, 0.4f, 1.0f, 1.3f, 1.6f};
        emerge.pos("torso", e[0], 0, -9.5f, 0, e[1], 0, -9.5f, 0, e[2], 0, 1.5f, 0, e[3], 0, -0.5f, 0, e[4], 0, 0, 0);
        emerge.scale("torso", e[0], 0.45f, 0.45f, 0.45f, e[1], 0.45f, 0.45f, 0.45f, e[2], 1.05f, 1.05f, 1.05f, e[4], 1, 1, 1);
        emerge.rot("arm_right", e[0], 0, 0, -20, e[1], 0, 0, -20, e[2] + 0.1f, -20, 0, 15, e[4], 0, 0, 0);
        emerge.rot("arm_left", e[0], -10, 0, 20, e[1], -10, 0, 20, e[2] + 0.1f, -20, 0, -15, e[4], 0, 0, 0);
        emerge.rot("forearm_right", e[0], -20, 0, 0, e[1], -20, 0, 0, e[2] + 0.15f, -10, 0, 0, e[4], 0, 0, 0);
        emerge.rot("blade_right", e[0], -80, 0, 0, e[1], -80, 0, 0, e[2] + 0.15f, 10, 0, 0, e[4], 0, 0, 0);
        emerge.rot("forearm_left", e[0], -10, 0, 0, e[1], -10, 0, 0, e[2] + 0.15f, -10, 0, 0, e[4], 0, 0, 0);
        emerge.rot("head", e[0], 0, 0, 0, e[2], 0, 0, 0, e[3], -15, 25, 0, e[4], 0, 0, 0);
        emerge.rot("ball", e[0], 0, 0, 0, e[1], 0, 0, 0, e[2], 0, 15, 0, e[4], 0, 0, 0);
        def.anim("emerge", emerge);

        def.anim("walk", sphereRoll(Anim.loop(0.8f), 8));
        def.anim("run", sphereRoll(Anim.loop(0.5f), 18));

        Anim slash = Anim.once(0.7f);
        slash.rot("arm_right", 0, 0, 0, 0, 0.25f, -110, 30, 40, 0.38f, -60, -45, -10, 0.55f, -40, -30, 0, 0.7f, 0, 0, 0);
        slash.rot("forearm_right", 0, 0, 0, 0, 0.25f, -20, 0, 0, 0.38f, 20, 0, 0, 0.7f, 0, 0, 0);
        slash.rot("chest", 0, 0, 0, 0, 0.25f, -5, 30, 0, 0.38f, 8, -30, 0, 0.7f, 0, 0, 0);
        slash.rot("arm_left", 0, 0, 0, 0, 0.25f, 10, 0, 0, 0.38f, -20, 0, 0, 0.7f, 0, 0, 0);
        def.anim("attack", slash);

        Anim bash = Anim.once(0.7f);
        bash.rot("arm_left", 0, 0, 0, 0, 0.22f, 25, -20, -20, 0.36f, -50, 25, 0, 0.5f, -45, 20, 0, 0.7f, 0, 0, 0);
        bash.rot("forearm_left", 0, 0, 0, 0, 0.22f, 10, 0, 0, 0.36f, 40, 0, 0, 0.7f, 0, 0, 0);
        bash.rot("chest", 0, 0, 0, 0, 0.22f, 0, -25, 0, 0.36f, 10, 25, 0, 0.7f, 0, 0, 0);
        bash.pos("torso", 0, 0, 0, 0, 0.22f, 0, 0, 1, 0.36f, 0, 0, -2, 0.7f, 0, 0, 0);
        def.anim("attack2", bash);

        Anim shoot = Anim.once(0.9f);
        shoot.rot("arm_left", 0, 0, 0, 0, 0.3f, -40, 15, 0, 0.42f, -35, 15, 0, 0.5f, -25, 15, 0, 0.9f, 0, 0, 0);
        shoot.rot("forearm_left", 0, 0, 0, 0, 0.3f, -10, 0, 0, 0.42f, -10, 0, 0, 0.5f, -20, 0, 0, 0.9f, 0, 0, 0);
        shoot.rot("chest", 0, 0, 0, 0, 0.3f, 0, -15, 0, 0.42f, -4, -15, 0, 0.9f, 0, 0, 0);
        shoot.rot("head", 0, 0, 0, 0, 0.3f, 0, 15, 0, 0.9f, 0, 0, 0);
        shoot.linear().pos("crossbow_left", 0, 0, 0, 0, 0.4f, 0, 0, 0, 0.44f, 0, 1.5f, 0, 0.6f, 0, 0, 0, 0.9f, 0, 0, 0);
        shoot.scale("crossbow_left", 0, 1, 1, 1, 0.42f, 1, 1, 1, 0.44f, 1.3f, 1, 1, 0.55f, 1, 1, 1, 0.9f, 1, 1, 1).smooth();
        def.anim("cast", shoot);

        Anim death = Anim.once(1f);
        death.pos("torso", 0, 0, 0, 0, 0.6f, 0, -4, 0, 1f, 0, -5, 0);
        death.rot("chest", 0, 0, 0, 0, 0.6f, 40, 0, 10, 1f, 45, 0, 12);
        death.rot("head", 0, 0, 0, 0, 0.6f, 35, 15, 0, 1f, 40, 15, 0);
        death.rot("arm_right", 0, 0, 0, 0, 0.6f, 20, 0, 30, 1f, 25, 0, 35);
        death.rot("arm_left", 0, 0, 0, 0, 0.6f, 20, 0, -30, 1f, 25, 0, -35);
        death.rot("forearm_left", 0, 0, 0, 0, 0.6f, 50, 0, 0, 1f, 55, 0, 0);
        def.anim("death", death);

        Anim hurt = Anim.once(0.4f);
        hurt.rot("chest", 0, 0, 0, 0, 0.1f, -12, 0, 0, 0.4f, 0, 0, 0);
        hurt.rot("head", 0, 0, 0, 0, 0.1f, -15, 0, 0, 0.4f, 0, 0, 0);
        def.anim("hurt", hurt);
        return def;
    }

    /** The closed pose: torso shrunk and lowered into the ball, arms tucked. */
    private static void closed(Anim a, float t0, float t1) {
        a.scale("torso", t0, 0.45f, 0.45f, 0.45f, t1, 0.45f, 0.45f, 0.45f);
        a.rot("arm_right", t0, 0, 0, -20, t1, 0, 0, -20);
        a.rot("arm_left", t0, -10, 0, 20, t1, -10, 0, 20);
        a.rot("forearm_right", t0, -20, 0, 0, t1, -20, 0, 0);
        a.rot("blade_right", t0, -80, 0, 0, t1, -80, 0, 0);
        a.rot("forearm_left", t0, -10, 0, 0, t1, -10, 0, 0);
    }

    private static Anim sphereRoll(Anim a, float lean) {
        a.linear().rot("ball", 0, 0, 0, 0, a.length, 360, 0, 0).smooth();
        a.hold("chest", lean, 0, 0);
        a.hold("head", -lean * 0.7f, 0, 0);
        a.hold("arm_right", lean * 0.8f, 0, 0);
        a.hold("arm_left", -lean * 0.5f, 0, 0);
        a.swingN("chest", 2, 3f, 0, 1);
        a.bob("torso", 1, 0.4f, 0, 2);
        return a;
    }

    // ============================================================================================ dwarven centurion

    /** Huge Dwemer steam construct: riveted boiler torso, visored head, hammer right arm, axe-blade left arm. */
    static CreatureDef dwarvenCenturion() {
        Biped b = new Biped("dwarven_centurion");
        b.legLen = 11;
        b.legW = 5;
        b.hipW = 9;
        b.hipH = 3;
        b.torsoW = 14;
        b.torsoH = 11;
        b.torsoD = 9;
        b.hunch = 8;
        b.armLen = 12;
        b.armW = 5;
        b.headW = 7;
        b.headH = 6;
        b.headD = 7;
        b.headForward = 3;
        b.jaw = true;
        b.jawH = 2;
        b.skin = "dwemer";
        b.headMat = "dwemer";
        b.armMat = "dwemer";
        b.legMat = "dwemer_dark";
        b.footMat = "dwemer";
        b.hipMat = "dwemer_dark";
        b.scale = 2f;
        b.stride = 18;
        b.build();
        Bone t = b.torso;
        t.box("dwemer", -6, -9, -5.5f, 12, 7, 1).tag("boiler");
        t.box("trim", -5, -10, -5.9f, 10, 1, 1).tag("band");
        t.box("trim", -5, -2.5f, -5.9f, 10, 1, 1).tag("band");
        t.box("core", -1.5f, -7.5f, -6.2f, 3, 3, 1).tag("core");
        t.box("pipe", -7.5f, -10, -2, 1, 8, 1).tag("pipe");
        t.box("pipe", 6.5f, -10, -2, 1, 8, 1).tag("pipe");
        t.box("dwemer_dark", -5, -10, 4.5f, 10, 8, 2).tag("firebox");
        t.pair("vent", 3.5f, -11, 3, (v, s) -> {
            v.sbox("pipe", -1, -4, -1, 2, 4, 2).tag("chimney");
            Bone cap = v.child("vent_cap", 0, -4, 1);
            cap.sbox("trim", -1.5f, -1, -2.5f, 3, 1, 3).tag("cap");
        });
        b.head.y -= 1.5f;
        b.head.box("dwemer_dark", -2.5f, 0, -2.5f, 5, 2, 5).tag("neck");
        b.head.box("trim", -0.5f, -7.5f, -4.5f, 1, 2, 7).tag("crest");
        b.head.box("dwemer_dark", -3, -4.6f, -4.6f, 6, 1, 1).tag("visor");
        b.head.box("trim", -3.5f, -5.6f, -4.7f, 7, 1, 1).tag("brow");
        b.head.box("dwemer", -4.5f, -5, -3.5f, 1, 5, 6).tag("cheek");
        b.head.box("dwemer", 3.5f, -5, -3.5f, 1, 5, 6).tag("cheek");
        b.jawBone.box("trim", -1.5f, 0.5f, -6, 3, 3, 1).tag("beard");
        for (Bone arm : b.arms) {
            arm.sbox("dwemer", -3.5f, -3.5f, -3.5f, 7, 5, 7).tag("pauldron");
            arm.sbox("trim", -3.5f, 1.5f, -3, 1, 2, 6).tag("pauldron_rim");
        }
        for (Bone fore : b.forearms) fore.sbox("trim", -2.5f, 1, -2.5f, 5, 2, 5).tag("cuff");
        for (Bone leg : b.legs) {
            leg.sbox("dwemer", -3, -1, -3, 6, 4, 6).tag("thigh_plate");
            leg.sbox("pipe", -2.5f, 3, 2, 1, 5, 1).tag("piston");
        }
        for (Bone shin : b.shins) {
            shin.sbox("dwemer", -2.5f, 1, -3.2f, 5, 4, 1).tag("greave");
            shin.sbox("trim", -2, -1, -2.6f, 4, 2, 1).tag("knee");
        }
        for (Bone foot : b.feet) foot.sbox("dwemer", -3, -1.5f, -3.5f, 6, 2, 5).tag("foot");
        // right: a massive hammer head fused to the wrist
        Bone hammer = b.hands[0].child("hammer", 0, 0, 0);
        hammer.box("dwemer_dark", -1, 0, -1, 2, 3, 2).tag("haft");
        hammer.box("dwemer", -3.5f, 2, -4, 7, 6, 8).tag("hammer");
        hammer.box("trim", -4, 3, -4.5f, 8, 4, 1).tag("hammer_face");
        hammer.box("trim", -4, 3, 3.5f, 8, 4, 1).tag("hammer_face");
        // left: a broad axe blade
        Bone axe = b.hands[1].child("axe", 0, 0, 0);
        axe.box("dwemer_dark", -1, 0, -1, 2, 4, 2).tag("haft");
        axe.box("trim", -1.5f, 2, -2, 3, 3, 3).tag("socket");
        axe.box("blade", -0.5f, 0, -9, 1, 9, 7).tag("axe");
        axe.box("blade", -0.5f, 1, -10, 1, 7, 1).tag("edge");

        CreatureDef def = b.def();
        Anim ham = Anim.once(1.1f);
        ham.rot("arm_right", 0, 0, 0, 0, 0.45f, -165, 10, 15, 0.6f, -45, 0, 0, 0.8f, -40, 0, 0, 1.1f, 0, 0, 0);
        ham.rot("forearm_right", 0, 0, 0, 0, 0.45f, -25, 0, 0, 0.6f, 0, 0, 0, 1.1f, 0, 0, 0);
        ham.rot("torso", 0, 0, 0, 0, 0.45f, -12, 15, 0, 0.6f, 18, -10, 0, 0.85f, 15, -8, 0, 1.1f, 0, 0, 0);
        ham.rot("arm_left", 0, 0, 0, 0, 0.45f, 15, 0, -15, 0.6f, -10, 0, -10, 1.1f, 0, 0, 0);
        ham.pos("hips", 0, 0, 0, 0, 0.45f, 0, 0.5f, 0, 0.6f, 0, -1, 0, 0.85f, 0, -1, 0, 1.1f, 0, 0, 0);
        def.anim("attack", ham);
        def.anim("attack2", b.swipe());
        def.anim("attack3", b.slam());
        Anim steam = Anim.once(2.8f);
        steam.rot("head", 0, 0, 0, 0, 0.4f, 25, 0, 0, 2.4f, 25, 0, 0, 2.8f, 0, 0, 0);
        steam.pos("head", 0, 0, 0, 0, 0.4f, 0, -1, -2.5f, 2.4f, 0, -1, -2.5f, 2.8f, 0, 0, 0);
        steam.rot("jaw", 0, 0, 0, 0, 0.4f, 35, 0, 0, 2.4f, 35, 0, 0, 2.8f, 0, 0, 0);
        steam.rot("torso", 0, 0, 0, 0, 0.4f, 10, 0, 0, 2.4f, 10, 0, 0, 2.8f, 0, 0, 0);
        steam.rot("vent_cap_right", 0, 0, 0, 0, 0.3f, -75, 0, 0, 2.4f, -75, 0, 0, 2.8f, 0, 0, 0);
        steam.rot("vent_cap_left", 0, 0, 0, 0, 0.3f, -75, 0, 0, 2.4f, -75, 0, 0, 2.8f, 0, 0, 0);
        steam.rot("arm_right", 0, 0, 0, 0, 0.4f, 15, 0, 20, 2.4f, 15, 0, 20, 2.8f, 0, 0, 0);
        steam.rot("arm_left", 0, 0, 0, 0, 0.4f, 15, 0, -20, 2.4f, 15, 0, -20, 2.8f, 0, 0, 0);
        steam.wave("head", Anim.Target.ROTATION, 1, 12, 0, 0, 2);
        def.anim("special", steam);
        return only(def, "idle", "walk", "attack", "attack2", "attack3", "special", "death", "hurt");
    }

    // ============================================================================================ dwarven ballista

    /** Dwemer automaton: four mechanical legs carrying a big bronze crossbow and a glowing core. */
    static CreatureDef dwarvenBallista() {
        Arachnid a = new Arachnid("dwarven_ballista");
        a.bodyW = 8;
        a.bodyH = 6;
        a.bodyL = 9;
        a.headW = 4;
        a.headH = 3;
        a.headL = 2;
        a.fangLen = 0;
        a.abdomenW = 7;
        a.abdomenH = 6;
        a.abdomenL = 4;
        a.abdomenTilt = 0;
        a.legPairs = 2;
        a.legUpper = 10;
        a.legLower = 14;
        a.legW = 3;
        a.legUp = 30;
        a.legDown = 80;
        a.shell = "dwemer";
        a.legMat = "dwemer_dark";
        a.abdomenMat = "dwemer";
        a.build();
        Bone body = a.body;
        body.box("trim", -4.5f, -1, -4, 9, 2, 8).tag("band");
        body.box("core", -1.5f, -1, -4.8f, 3, 3, 1).tag("core");
        a.abdomen.box("soulgem", -1, -4.5f, 1, 2, 2, 2).tag("gem");
        for (int i = 1; i <= 2; i++) {
            for (String sd : new String[]{"_right", "_left"}) {
                a.rig.bone("leg" + i + "_upper" + sd).sbox("trim", -2, -2, -2, 4, 4, 4).tag("joint");
                a.rig.bone("leg" + i + "_lower" + sd).sbox("trim", -14.5f, -1.5f, -1.5f, 2, 3, 3).tag("foot");
            }
        }
        Bone turret = body.child("turret", 0, -3, 0);
        turret.box("dwemer_dark", -2.5f, -2, -2.5f, 5, 2, 5).tag("turret");
        Bone bow = turret.child("bow", 0, -2, 0);
        bow.box("dwemer", -2, -3, -11, 4, 3, 16).tag("stock");
        bow.box("trim", -2.5f, -3.5f, 2, 5, 4, 3).tag("winch");
        bow.box("trim", -1.5f, -2, -12, 3, 2, 1).tag("muzzle");
        bow.pair("limb", 2, -1.5f, -9, (l, s) -> {
            l.rot(0, 12, 0);
            l.sbox("trim", -9, -1, -1, 9, 2, 2).tag("limb");
            Bone tip = l.child("limb_tip", -9, 0, 0).rot(0, 25, 0);
            tip.sbox("trim", -4, -0.5f, -0.5f, 4, 1, 1).tag("limb");
        });
        bow.box("string", -13, -2, -4.5f, 26, 1, 1, -0.3f).tag("string");
        Bone bolt = bow.child("bolt", 0, -3.5f, 0);
        bolt.box("bolt", -0.5f, -0.5f, -14, 1, 1, 14).tag("bolt");
        bolt.box("dwemer_dark", -1, -1, -15.5f, 2, 2, 2).tag("bolt_head");

        CreatureDef def = a.def();
        Anim kick = Anim.once(0.7f);
        kick.rot("leg1_right", 0, 0, 0, 0, 0.28f, 0, 25, 40, 0.42f, 0, -5, -10, 0.7f, 0, 0, 0);
        kick.rot("leg1_left", 0, 0, 0, 0, 0.3f, 0, -25, -40, 0.44f, 0, 5, 10, 0.7f, 0, 0, 0);
        kick.rot("body", 0, 0, 0, 0, 0.28f, -12, 0, 0, 0.42f, 6, 0, 0, 0.7f, 0, 0, 0);
        kick.pos("body", 0, 0, 0, 0, 0.28f, 0, 1.5f, 1, 0.42f, 0, -1, -1, 0.7f, 0, 0, 0);
        def.anim("attack", kick);
        Anim fire = Anim.once(1.0f);
        fire.rot("bow", 0, 0, 0, 0, 0.25f, -6, 0, 0, 0.35f, -6, 0, 0, 0.42f, -14, 0, 0, 0.7f, 0, 0, 0);
        fire.pos("body", 0, 0, 0, 0, 0.35f, 0, 0, 0, 0.42f, 0, 0.5f, 2.5f, 0.7f, 0, 0, 0);
        fire.rot("limb_right", 0, 0, 0, 0, 0.35f, 0, 0, 0, 0.4f, 0, -25, 0, 0.55f, 0, 8, 0, 0.8f, 0, 0, 0);
        fire.rot("limb_left", 0, 0, 0, 0, 0.35f, 0, 0, 0, 0.4f, 0, 25, 0, 0.55f, 0, -8, 0, 0.8f, 0, 0, 0);
        fire.linear().pos("bolt", 0, 0, 0, 0, 0.35f, 0, 0, 0, 0.45f, 0, 0, -30, 0.46f, 0, 0, 3, 0.85f, 0, 0, 3, 1.0f, 0, 0, 0);
        fire.scale("bolt", 0, 1, 1, 1, 0.43f, 1, 1, 1, 0.45f, 0.01f, 0.01f, 0.01f, 0.8f, 0.01f, 0.01f, 0.01f, 0.9f, 1, 1, 1).smooth();
        for (int i = 1; i <= 2; i++) {
            fire.rot("leg" + i + "_right", 0, 0, 0, 0, 0.42f, 0, 0, -6, 0.7f, 0, 0, 0);
            fire.rot("leg" + i + "_left", 0, 0, 0, 0, 0.42f, 0, 0, 6, 0.7f, 0, 0, 0);
        }
        def.anim("cast", fire);
        return only(def, "idle", "walk", "attack", "cast", "death", "hurt");
    }
}
