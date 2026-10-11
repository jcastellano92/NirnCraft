package com.skycraft.dungeons.qa;

import com.skycraft.economy.ItemValues;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Expected gold value of a loot table where it stands: the table is rolled {@link #ROLLS} times at the container's
 * position (so region-aware functions see the right province) and the items are priced with the economy's
 * {@link ItemValues}. Cached per table and region.
 */
final class QaLoot {
    static final int ROLLS = 40;
    private final Map<String, Double> cache = new HashMap<>();

    double expected(ServerLevel level, ResourceLocation table, BlockPos pos) {
        String key = table + "@" + TamrielMap.region(pos.getX(), pos.getZ()).id();
        Double v = cache.get(key);
        if (v != null) return v;
        LootTable lt = level.getServer().getLootData().getLootTable(table);
        double sum = 0;
        if (lt != LootTable.EMPTY) {
            for (int i = 0; i < ROLLS; i++) {
                LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .create(LootContextParamSets.CHEST);
                try {
                    for (ItemStack st : lt.getRandomItems(params)) sum += ItemValues.getStack(st);
                } catch (RuntimeException e) {
                    // a function needing a player: price what we have
                }
            }
        }
        double ev = sum / ROLLS;
        cache.put(key, ev);
        return ev;
    }
}
