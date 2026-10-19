package com.nirncraft.dungeons.qa;

import com.nirncraft.dungeons.world.DungeonStructure;
import com.nirncraft.dungeons.world.Theme;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;

/**
 * One dungeon to analyse: a dungeon structure and the chunk its start is tried in (a named place's chunk, or a random
 * chunk for the random sites). The analyzer plans it through the structure's own {@code findGenerationPoint}.
 */
public record QaSample(String label, String category, Identifier structureId, DungeonStructure structure, int x, int z,
                       @Nullable String placeId) {
    public Theme theme() {
        return structure.theme;
    }

    /** Dungeons with rooms below ground (forts have their cellars). */
    public boolean underground() {
        return structure.theme.underground();
    }
}
