package com.nirncraft.loot;

import com.nirncraft.NirnCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Rolling the loot module's tables from code: quest rewards, explorers' placed items, the dev command.
 * <pre>
 *  LootApi.questReward(player, "standard")      // nirncraft:containers/reward/standard at the player's loot level
 *  LootApi.roll(level, Containers.dungeon("boss"), pos, player)
 *  LegendaryUniques.create("gauldur_blackblade")  // or the table nirncraft:uniques/gauldur_blackblade
 * </pre>
 */
public final class LootApi {
    private LootApi() {}

    /** Rolls a chest-type table as if {@code player} opened a container at {@code pos} (player may be null). */
    public static List<ItemStack> roll(ServerLevel level, Identifier table, BlockPos pos, @Nullable ServerPlayer player) {
        LootTable t = com.nirncraft.platform.LootTables.get(level, table);
        LootParams.Builder b = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos));
        if (player != null) b.withParameter(LootContextParams.THIS_ENTITY, player).withLuck(player.getLuck());
        return t.getRandomItems(b.create(LootContextParamSets.CHEST));
    }

    /** A quest reward of a tier ({@code minor}, {@code standard}, {@code major}, {@code legendary}). */
    public static List<ItemStack> questReward(ServerPlayer player, String tier) {
        return roll(player.level(), Identifier.fromNamespaceAndPath(NirnCraft.MODID, "containers/reward/" + tier), player.blockPosition(), player);
    }
}
