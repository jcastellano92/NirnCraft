package com.nirncraft.creatures.entity;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** A creature with Skyrim-style leveled rank names ("Bandit Thug", "Draugr Wight"...). */
public interface Ranked {
    /** The rank name for a creature of this level, or null to keep the plain type name. */
    @Nullable
    Component rankName(int level);

    /** Called when the creature's level is set (after its rank name), e.g. to show rank-dependent gear. */
    default void onLevelApplied(int level) {}
}
