package com.nirncraft.creatures.client.rig.defs;

import com.nirncraft.creatures.client.rig.Bone;
import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.client.rig.Rig;

/**
 * Costume pieces worn over the humanoid (player-shaped) model: helmets, hoods, ears, antlers, capes, pauldrons and
 * skirts. Each costume rig has the humanoid's six parts as top-level bones ({@code head, body, right_arm, left_arm,
 * right_leg, left_leg}); the costume layer copies the humanoid's pose onto them every frame, so the pieces follow the
 * body. Optional pieces are separate bones the renderer shows or hides (draugr helmets by rank).
 */
final class Costumes {
    private Costumes() {}

    static void register() {
        Defs.reg("costume_draugr", Costumes::draugr);
        Defs.reg("costume_deathlord", Costumes::deathlord);
        Defs.reg("costume_falmer", Costumes::falmer);
        Defs.reg("costume_forsworn", Costumes::forsworn);
        Defs.reg("costume_vampire", Costumes::vampire);
        Defs.reg("costume_necromancer", Costumes::necromancer);
    }

    /** The six humanoid parts at their rest pivots (the layer overwrites them with the live pose). */
    private static Bone[] parts(Rig rig) {
        Bone root = rig.root();
        return new Bone[]{
                root.child("head", 0, -24, 0),
                root.child("body", 0, -24, 0),
                root.child("right_arm", -5, -22, 0),
                root.child("left_arm", 5, -22, 0),
                root.child("right_leg", -1.9f, -12, 0),
                root.child("left_leg", 1.9f, -12, 0)};
    }

    private static void horn(Bone head, String name, float x, float y, float z, float rx, float ry, float rz, int len, String mat) {
        Bone h = head.child(name, x, y, z).rot(rx, ry, rz);
        h.box(mat, -1, -1, -1, 2, 2, Math.max(2, len / 2)).tag("horn");
        Bone t = h.child(name + "_tip", 0, 0, len / 2f - 0.5f).rot(-25, 0, 0);
        t.box(mat, -0.5f, -0.5f, 0, 1, 1, len - len / 2).tag("horn");
    }

    /** Draugr: burial wraps and Nordic iron, the helmet grows grander with rank. */
    static CreatureDef draugr() {
        Rig rig = new Rig("costume_draugr");
        Bone[] p = parts(rig);
        Bone head = p[0];
        // restless: a ragged burial hood
        Bone hood = head.child("hood", 0, 0, 0);
        hood.box("wrap", -4.5f, -8.6f, -4.5f, 9, 5, 9, 0.1f).tag("hood");
        hood.box("wrap", -4.5f, -3.6f, 3.6f, 9, 4, 1).tag("hood_back");
        // draugr: a plain iron cap with a nasal guard
        Bone iron = head.child("helm_iron", 0, 0, 0);
        iron.box("iron", -4.5f, -8.8f, -4.5f, 9, 4, 9, 0.15f).tag("cap");
        iron.box("iron", -0.5f, -5, -5.2f, 1, 3, 1).tag("nasal");
        // wight: a horned helm
        Bone horned = head.child("helm_horned", 0, 0, 0);
        horned.box("iron", -4.5f, -8.8f, -4.5f, 9, 4, 9, 0.2f).tag("cap");
        horned.box("iron", -4.6f, -5.2f, -4.6f, 9, 1, 9, 0.2f).tag("rim");
        horn(horned, "horn_r1", -4.5f, -7, 0, -40, -80, 0, 7, "bone");
        horn(horned, "horn_l1", 4.5f, -7, 0, -40, 80, 0, 7, "bone");
        // scourge: face guard with eye slits and swept horns
        Bone scourge = head.child("helm_scourge", 0, 0, 0);
        scourge.box("iron", -4.5f, -8.8f, -4.5f, 9, 4, 9, 0.25f).tag("cap");
        scourge.box("iron", -4.5f, -5, -5.3f, 9, 5, 1).tag("faceguard");
        scourge.box("iron", -5, -6, -4, 1, 6, 8).tag("cheek");
        scourge.box("iron", 4, -6, -4, 1, 6, 8).tag("cheek");
        horn(scourge, "horn_r2", -4.5f, -7.5f, -1, -60, -60, 0, 9, "bone");
        horn(scourge, "horn_l2", 4.5f, -7.5f, -1, -60, 60, 0, 9, "bone");
        // overlord: a crowned helm with great horns
        Bone overlord = head.child("helm_overlord", 0, 0, 0);
        overlord.box("iron", -4.5f, -8.8f, -4.5f, 9, 4, 9, 0.3f).tag("cap");
        overlord.box("iron", -4.5f, -5, -5.3f, 9, 5, 1).tag("faceguard");
        overlord.box("gold", -4.5f, -10, -4.5f, 9, 2, 1).tag("crown");
        overlord.box("gold", -1, -12, -4.6f, 2, 2, 1).tag("crown_point");
        horn(overlord, "horn_r3", -4.5f, -8, 0, -70, -55, 0, 12, "bone");
        horn(overlord, "horn_l3", 4.5f, -8, 0, -70, 55, 0, 12, "bone");
        // rank armour: pauldrons and a fur mantle
        Bone body = p[1];
        Bone mantle = body.child("mantle", 0, 0, 0);
        mantle.box("fur", -5, -0.5f, -3, 10, 3, 6, 0.1f).tag("mantle");
        p[2].child("pauldron_right", 0, 0, 0).box("iron", -4, -2.8f, -2.6f, 5, 3, 5, 0.1f).tag("pauldron");
        p[3].child("pauldron_left", 0, 0, 0).box("iron", -1, -2.8f, -2.6f, 5, 3, 5, 0.1f).tag("pauldron");
        // burial loincloth for all
        body.box("wrap", -3.5f, 10, -2.4f, 7, 7, 0).tag("flap");
        body.box("wrap", -3.5f, 10, 2.4f, 7, 6, 0).tag("flap");
        // wrapped forearms
        p[2].box("wrap", -3, 6, -2, 4, 4, 4, 0.15f).tag("bracer");
        p[3].box("wrap", -1, 6, -2, 4, 4, 4, 0.15f).tag("bracer");
        return new CreatureDef(rig);
    }

    /** Deathlord: blackened ancient plate, a spiked crown-helm and great curled horns. */
    static CreatureDef deathlord() {
        Rig rig = new Rig("costume_deathlord");
        Bone[] p = parts(rig);
        Bone head = p[0];
        head.box("plate", -4.5f, -8.8f, -4.5f, 9, 9, 9, 0.3f).tag("helm");
        head.box("plate", -4.5f, -5, -5.4f, 9, 5, 1).tag("faceguard");
        for (int i = 0; i < 5; i++) head.box("plate", -4 + i * 2, -11, -4.5f, 1, 3, 1).tag("spike");
        horn(head, "horn_r", -4.5f, -7, 1, -30, -100, 20, 12, "horn");
        horn(head, "horn_l", 4.5f, -7, 1, -30, 100, -20, 12, "horn");
        Bone body = p[1];
        body.box("plate", -4.5f, -0.5f, -2.6f, 9, 8, 5, 0.25f).tag("cuirass");
        body.box("fur", -5, -1, -3, 10, 3, 6, 0.2f).tag("mantle");
        body.box("plate", -3.5f, 10, -2.6f, 7, 6, 0).tag("flap");
        p[2].box("plate", -4, -2.8f, -2.6f, 5, 4, 5, 0.2f).tag("pauldron");
        p[2].box("plate", -4.5f, -4, -1, 1, 2, 2).tag("spike");
        p[3].box("plate", -1, -2.8f, -2.6f, 5, 4, 5, 0.2f).tag("pauldron");
        p[3].box("plate", 3.5f, -4, -1, 1, 2, 2).tag("spike");
        p[2].box("plate", -3, 6, -2, 4, 5, 4, 0.2f).tag("gauntlet");
        p[3].box("plate", -1, 6, -2, 4, 5, 4, 0.2f).tag("gauntlet");
        p[4].box("plate", -2, 6, -2, 4, 6, 4, 0.2f).tag("greave");
        p[5].box("plate", -2, 6, -2, 4, 6, 4, 0.2f).tag("greave");
        return new CreatureDef(rig);
    }

    /** Falmer: long pointed ears, a ridge of chitin over the blind eyes, chaurus-shell armour. */
    static CreatureDef falmer() {
        Rig rig = new Rig("costume_falmer");
        Bone[] p = parts(rig);
        Bone head = p[0];
        head.pair("ear", 4, -5, 0.5f, (b, side) -> {
            b.rot(-20, -25, 0);
            b.sbox("skin", -1, -1, 0, 1, 2, 7).tag("ear");
            b.sbox("skin", -1, -2, 3, 1, 1, 3).tag("ear_tip");
        });
        head.box("chitin", -4.5f, -6, -4.6f, 9, 2, 2, 0.1f).tag("brow");
        head.box("chitin", -4, -9.2f, -3.5f, 8, 2, 8).tag("crest");
        head.box("chitin", -0.5f, -11, -3, 1, 2, 7).tag("crest_fin");
        Bone body = p[1];
        body.box("chitin", -4.5f, 0, -2.7f, 9, 7, 1, 0.1f).tag("chest");
        body.box("chitin", -4.5f, 0, 1.8f, 9, 9, 1, 0.1f).tag("back");
        body.box("leather", -3.5f, 10, -2.4f, 7, 6, 0).tag("flap");
        p[2].box("chitin", -4, -2.8f, -2.6f, 5, 3, 5, 0.15f).tag("pauldron");
        p[2].box("chitin", -4.5f, -3.5f, 0, 1, 2, 3).tag("spike");
        p[3].box("chitin", -1, -2.8f, -2.6f, 5, 3, 5, 0.15f).tag("pauldron");
        p[3].box("chitin", 3.5f, -3.5f, 0, 1, 2, 3).tag("spike");
        p[4].box("chitin", -2, 4, -2.6f, 4, 4, 1).tag("knee");
        p[5].box("chitin", -2, 4, -2.6f, 4, 4, 1).tag("knee");
        return new CreatureDef(rig);
    }

    /** Forsworn: antlers and feathers, a fur wrap and bone charms. */
    static CreatureDef forsworn() {
        Rig rig = new Rig("costume_forsworn");
        Bone[] p = parts(rig);
        Bone head = p[0];
        head.box("hide", -4.5f, -8.7f, -4.5f, 9, 3, 9, 0.1f).tag("band");
        head.pair("antler", 3, -8, 0, (b, side) -> {
            b.rot(-15, -20, -25);
            b.sbox("bone", -0.5f, -8, -0.5f, 1, 8, 1).tag("antler");
            Bone tine = b.child("tine", 0, -4, 0).rot(0, 0, -55);
            tine.sbox("bone", -0.5f, -4, -0.5f, 1, 4, 1).tag("antler");
            Bone tine2 = b.child("tine2", 0, -6.5f, 0).rot(-40, 0, 20);
            tine2.sbox("bone", -0.5f, -3, -0.5f, 1, 3, 1).tag("antler");
        });
        head.box("feather", 2.5f, -12, 3.5f, 0, 6, 2).tag("feather");
        head.box("feather", -2.5f, -11, 3.5f, 0, 5, 2).tag("feather");
        Bone body = p[1];
        body.box("fur", -5, -0.5f, -3, 10, 4, 6, 0.15f).tag("wrap");
        body.box("bone", -2, 4, -2.6f, 4, 1, 1).tag("charm");
        body.box("hide", -3.5f, 10, -2.4f, 7, 6, 0).tag("flap");
        body.box("hide", -3.5f, 10, 2.4f, 7, 7, 0).tag("flap");
        p[2].box("fur", -3.5f, -2.5f, -2.5f, 5, 3, 5, 0.05f).tag("shoulder_fur");
        p[4].box("hide", -2, 7, -2, 4, 4, 4, 0.2f).tag("wrap_leg");
        p[5].box("hide", -2, 7, -2, 4, 4, 4, 0.2f).tag("wrap_leg");
        return new CreatureDef(rig);
    }

    /** Vampire: a high stiff collar and a long cape. */
    static CreatureDef vampire() {
        Rig rig = new Rig("costume_vampire");
        Bone[] p = parts(rig);
        Bone body = p[1];
        body.box("collar", -5, -4, 1.5f, 10, 5, 1).tag("collar");
        body.box("collar", -5, -3, -2.5f, 1, 4, 4).tag("collar_side");
        body.box("collar", 4, -3, -2.5f, 1, 4, 4).tag("collar_side");
        Bone cape = body.child("cape", 0, 0, 2.6f).rot(6, 0, 0);
        cape.box("cape", -5, 0, 0, 10, 21, 0).tag("cape");
        body.box("trim", -4.5f, 0, -2.5f, 9, 1, 5, 0.1f).tag("trim");
        return new CreatureDef(rig);
    }

    /** Necromancer: a deep hood, a mantle and robes to the ankle, a skull at the belt. */
    static CreatureDef necromancer() {
        Rig rig = new Rig("costume_necromancer");
        Bone[] p = parts(rig);
        Bone head = p[0];
        head.box("robe", -4.5f, -8.8f, -3.5f, 9, 9, 8, 0.2f).tag("hood");
        head.box("robe", -4.5f, -9.4f, -4.6f, 9, 2, 1, 0.2f).tag("hood_brim");
        head.box("robe", -1.5f, -11, 3, 3, 3, 3).tag("hood_point");
        Bone body = p[1];
        body.box("trim", -5, -0.5f, -3, 10, 4, 6, 0.1f).tag("mantle");
        body.box("bone", -1.5f, 9, -3.2f, 3, 3, 1).tag("skull");
        body.box("trim", -4.5f, 9.5f, -2.6f, 9, 1, 5, 0.1f).tag("belt");
        Bone skirt = body.child("skirt", 0, 12, 0);
        skirt.box("robe", -4.5f, 0, -2.6f, 9, 11, 0).tag("skirt");
        skirt.box("robe", -4.5f, 0, 2.6f, 9, 11, 0).tag("skirt");
        skirt.box("robe", -4.6f, 0, -2.5f, 0, 11, 5).tag("skirt");
        skirt.box("robe", 4.6f, 0, -2.5f, 0, 11, 5).tag("skirt");
        p[2].box("robe", -3, 5, -2, 4, 5, 4, 0.3f).tag("sleeve");
        p[3].box("robe", -1, 5, -2, 4, 5, 4, 0.3f).tag("sleeve");
        return new CreatureDef(rig);
    }
}
