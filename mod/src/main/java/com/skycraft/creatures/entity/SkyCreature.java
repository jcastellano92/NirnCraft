package com.skycraft.creatures.entity;

import com.skycraft.creatures.entity.anim.AnimatedCreature;
import com.skycraft.creatures.spec.CreatureSpec;

/** A bestiary creature driven by a {@link CreatureSpec} (see {@link SkyMonster} and {@link SkyBeast}). */
public interface SkyCreature extends AnimatedCreature, Ranked {
    CreatureSpec spec();

    int getVariant();

    void setVariant(int variant);

    /** False while the creature can't use its moves (a burrowed sand serpent). */
    default boolean canAttackNow() {
        return true;
    }

    /** Dormant (a statue, a sleeper) until a player comes close. */
    default boolean isDormant() {
        return false;
    }

    /** Persistent tag of creatures placed by the region spawner (they despawn when nobody is around). */
    String REGION_SPAWN_TAG = "skycraft.region_spawn";
}
