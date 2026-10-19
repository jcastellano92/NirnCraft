package com.nirncraft.creatures.client.rig.defs;

import com.nirncraft.creatures.client.rig.Biped;
import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.CreatureDef;

/** Skyrim's monsters with hand-tuned bodies: trolls and giants. */
final class SkyrimMonsters {
    private SkyrimMonsters() {}

    static void register() {
        Defs.reg("troll", SkyrimMonsters::troll);
        Defs.reg("giant", SkyrimMonsters::giant);
    }

    /** Hunched, long-armed, shaggy white ape with a heavy jaw and three eyes. */
    static CreatureDef troll() {
        Biped b = new Biped("troll");
        b.legLen = 11;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 5;
        b.torsoW = 14;
        b.torsoH = 13;
        b.torsoD = 9;
        b.hunch = 38;
        b.armLen = 22;
        b.armW = 5;
        b.handSize = 5;
        b.headW = 8;
        b.headH = 7;
        b.headD = 7;
        b.headForward = 3;
        b.jaw = true;
        b.jawH = 3;
        b.skin = "fur";
        b.headMat = "face";
        b.handMat = "hide";
        b.footMat = "hide";
        b.hipMat = "fur";
        b.scale = 1.15f;
        b.stride = 26;
        b.build();
        // shaggy shoulder mane and back ridge
        b.torso.box("mane", -7.5f, -14, -4, 15, 6, 9, 0.25f).tag("mane");
        // brow ridge over the three eyes
        b.head.box("face", -4.5f, -7.5f, -4.5f, 9, 2, 2).tag("brow");
        // tusks
        b.jawBone.box("tooth", -3, -1.5f, -6, 1, 2, 1).tag("tusk");
        b.jawBone.box("tooth", 2, -1.5f, -6, 1, 2, 1).tag("tusk");
        return b.def();
    }

    /** Tall, lanky, leathery giant in a hide loincloth, with a bone-studded club. */
    static CreatureDef giant() {
        Biped b = new Biped("giant");
        b.legLen = 16;
        b.legW = 5;
        b.hipW = 10;
        b.hipH = 4;
        b.torsoW = 11;
        b.torsoH = 15;
        b.torsoD = 6;
        b.hunch = 8;
        b.armLen = 18;
        b.armW = 4;
        b.handSize = 4;
        b.headW = 7;
        b.headH = 8;
        b.headD = 7;
        b.headForward = 1;
        b.skin = "skin";
        b.hipMat = "loincloth";
        b.footMat = "skin";
        b.scale = 2.0f;
        b.stride = 24;
        b.stomp = true;
        b.build();
        // nose, ears and hair knot
        b.head.box("skin", -1, -4.5f, -b.headD / 2f - 2.2f, 2, 3, 2).tag("nose");
        b.head.box("hair", -3.5f, -9.5f, -1, 7, 2, 5).tag("hair");
        b.head.box("hair", -1.5f, -11.5f, 1, 3, 3, 3).tag("topknot");
        // hide loincloth flaps
        b.hips.box("loincloth", -5, -1, -3.5f, 10, 5, 1).tag("flap");
        b.hips.box("loincloth", -5, -1, 2.5f, 10, 6, 1).tag("flap");
        // bone necklace
        b.torso.box("bone", -4, -14.6f, -3.6f, 8, 1, 1).tag("necklace");
        // the club: a knotted trunk held in the right hand, pointing forward-down
        Bone club = b.hands[0].child("club", 0, 2, 0).rot(-80, 0, 0);
        club.box("wood", -1.5f, -1.5f, -2, 3, 3, 20).tag("club");
        club.box("wood", -2.5f, -2.5f, 12, 5, 5, 10).tag("club_head");
        club.box("bone", -3, -1, 15, 6, 1, 1).tag("spike");
        club.box("bone", -1, -3, 18, 1, 6, 1).tag("spike");
        return b.def();
    }
}
