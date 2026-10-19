package com.nirncraft.creatures.client.rig.defs;

import com.nirncraft.creatures.client.rig.CreatureDef;
import com.nirncraft.creatures.client.rig.Quad;

/** Skyrim's wildlife: wolves, cats, bears, deer, elk, mammoths, horkers, skeevers, mudcrabs, slaughterfish. */
final class SkyrimBeasts {
    private SkyrimBeasts() {}

    static void register() {
        Defs.reg("wolf", SkyrimBeasts::wolf);
    }

    /** Timber / ice wolf: lean, long-legged, thick ruff and a bushy tail. */
    static CreatureDef wolf() {
        Quad q = new Quad("wolf");
        q.bodyW = 7;
        q.bodyH = 7;
        q.bodyL = 15;
        q.chestW = 8;
        q.chestH = 9;
        q.chestL = 7;
        q.legLen = 11;
        q.legW = 3;
        q.neckLen = 4;
        q.neckW = 5;
        q.neckH = 6;
        q.neckPitch = -28;
        q.headW = 6;
        q.headH = 6;
        q.headL = 5;
        q.snoutW = 3;
        q.snoutH = 3;
        q.snoutL = 5;
        q.earH = 3;
        q.tailSegs = 3;
        q.tailLen = 13;
        q.tailW = 3;
        q.tailDroop = 50;
        q.neckMat = "ruff";
        q.legMat = "leg";
        q.snoutMat = "muzzle";
        q.tailMat = "tail";
        q.build();
        q.body.y -= 1; // the paws reach one pixel below the legs: stand on the ground, not in it
        // nose tip
        q.snout.box("nose", -1f, q.headH / 2f - q.snoutH - 1.2f, -q.snoutL - 0.5f, 2, 1, 1).tag("nose");
        return q.def();
    }
}
