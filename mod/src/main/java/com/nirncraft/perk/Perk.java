package com.nirncraft.perk;

import com.nirncraft.core.Skill;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A node in a skill constellation. Perk ids are {@code "<skill>.<name>"}, e.g. {@code "one_handed.armsman"}.
 *
 * @param requiredLevels skill level needed for each rank (length == max rank)
 * @param parents        perk ids, at least one of which must be owned (empty = root)
 * @param x              horizontal position in the constellation, -1..1
 * @param y              vertical position, 0 (bottom) .. 1 (top)
 */
public record Perk(String id, Skill skill, int[] requiredLevels, List<String> parents, float x, float y) {

    public int maxRank() {
        return requiredLevels.length;
    }

    public int requiredLevel(int rank) {
        return requiredLevels[Math.max(0, Math.min(rank, requiredLevels.length) - 1)];
    }

    public String shortName() {
        return id.substring(id.indexOf('.') + 1);
    }

    public Component displayName() {
        return Component.translatable("perk.nirncraft." + id);
    }

    public Component description() {
        return Component.translatable("perk.nirncraft." + id + ".desc");
    }
}
