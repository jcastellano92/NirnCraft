package com.nirncraft.crafting.arcane;

import com.nirncraft.crafting.arcane.effect.ArcaneEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;

/**
 * Enchanting (Arcane Enchanter, disenchanting, soul gems) and alchemy (Alchemy Lab, ingredients, potions and
 * poisons). Initialised by CraftingModule.
 *
 * <ul>
 *   <li>{@link ArcaneRegistry}: blocks and items; {@link ArcaneEffects}: alchemy status effects.</li>
 *   <li>{@link com.nirncraft.crafting.arcane.enchant.Enchanting}, {@link com.nirncraft.crafting.arcane.alchemy.Alchemy}:
 *       station logic; {@link ArcaneEvents}: ingredient learning, perks, effect handling, creature drops.</li>
 *   <li>{@link com.nirncraft.crafting.SoulGems}: the cross-module soul-trap contract.</li>
 * </ul>
 */
public final class ArcaneModule {
    /** Name of this module's per-player storage: {@code data.module("arcane")}. */
    public static final String DATA = "arcane";

    private ArcaneModule() {}

    public static void init(IEventBus modBus) {
        ArcaneRegistry.init(modBus);
        ArcaneEffects.init(modBus);
    }

    public static void registerPackets() {
        ArcanePackets.register();
    }

    /** Server check that the player stands at a loaded station block of the given type. */
    public static boolean atStation(ServerPlayer player, BlockPos pos, Block block) {
        if (pos == null || !player.isAlive() || !player.level().isLoaded(pos)) return false;
        var state = player.level().getBlockState(pos);
        if (state.is(block)) return player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64.0;
        if (block == ArcaneRegistry.ARCANE_ENCHANTER.get() && state.is(net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE)) {
            return player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64.0;
        }
        if (block == ArcaneRegistry.ALCHEMY_LAB.get() && state.is(net.minecraft.world.level.block.Blocks.BREWING_STAND)) {
            return player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64.0;
        }
        return false;
    }
}
