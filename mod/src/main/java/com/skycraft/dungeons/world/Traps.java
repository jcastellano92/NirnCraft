package com.skycraft.dungeons.world;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.Skill;
import com.skycraft.creatures.CreatureSpawns;
import com.skycraft.skills.Progression;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Corridor traps as tension beats, built from vanilla mechanisms and always readable before they bite: a tripwire
 * strung at ankle height between two hooks, and dart throwers above the hooks in both walls; and the barred window
 * (no trap: a view into the boss chamber through iron bars).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Traps {
    private Traps() {}

    /**
     * A tripwire across a 3-wide walk at position (x, f, z): hooks on the two side lanes, each fixed to a wall block
     * that carries its signal to the dart thrower above it. The string is visible and can be jumped or cut.
     *
     * @param anchor  the block the hooks hang on (must carry redstone: a full solid block)
     * @param natural a cave tunnel: shore up the rock around the trap first
     */
    static void tripwire(Painter p, int x, int f, int z, Direction latPlus, BlockState anchor, boolean natural) {
        int lx = latPlus.getStepX(), lz = latPlus.getStepZ();
        for (int s : new int[]{-1, 1}) {
            int wx = x + lx * 2 * s, wz = z + lz * 2 * s;
            if (natural) {
                for (int dy = -1; dy <= 2; dy++) p.set(wx, f + dy, wz, NaturalBuilder.rock(f + dy));
                for (int dy = 0; dy <= 2; dy++) p.air(x + lx * s, f + dy, z + lz * s);
            }
            p.set(wx, f, wz, anchor);
            Direction out = s < 0 ? latPlus : latPlus.getOpposite();
            p.dispenser(wx, f + 1, wz, out, Deco.ARROWS);
            p.set(x + lx * s, f, z + lz * s, Blocks.TRIPWIRE_HOOK.defaultBlockState()
                    .setValue(TripWireHookBlock.FACING, out).setValue(TripWireHookBlock.ATTACHED, true));
        }
        boolean xLat = latPlus.getAxis() == Direction.Axis.X;
        p.set(x, f, z, Blocks.TRIPWIRE.defaultBlockState().setValue(TripWireBlock.ATTACHED, true)
                .setValue(TripWireBlock.EAST, xLat).setValue(TripWireBlock.WEST, xLat)
                .setValue(TripWireBlock.NORTH, !xLat).setValue(TripWireBlock.SOUTH, !xLat));
        p.feature("trap:tripwire");
    }

    /** Iron bars across a 3-wide walk: a window, not a way. */
    static void bars(Painter p, int x, int f, int z, Direction latPlus) {
        boolean xLat = latPlus.getAxis() == Direction.Axis.X;
        BlockState bars = Blocks.IRON_BARS.defaultBlockState().setValue(CrossCollisionBlock.EAST, xLat).setValue(CrossCollisionBlock.WEST, xLat)
                .setValue(CrossCollisionBlock.NORTH, !xLat).setValue(CrossCollisionBlock.SOUTH, !xLat);
        for (int s = -1; s <= 1; s++) {
            for (int dy = 0; dy <= 2; dy++) p.set(x + latPlus.getStepX() * s, f + dy, z + latPlus.getStepZ() * s, bars);
        }
        p.feature("foreshadow:window");
    }

    /**
     * Crouching + right-clicking a tripwire, tripwire hook, or dungeon pressure plate disarms the trap without
     * triggering connected dispensers, yields string/arrows, and grants Lockpicking and Sneak XP.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || event.getHand() != InteractionHand.MAIN_HAND) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        boolean isTripwire = block instanceof TripWireBlock || block instanceof TripWireHookBlock;
        boolean isPlate = block instanceof BasePressurePlateBlock;
        if (!isTripwire && !isPlate) return;

        if (!(level instanceof ServerLevel sl)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (isPlate && CreatureSpawns.isSettlementOrHouse(sl, pos)) return;
        if (block instanceof TripWireHookBlock && !state.getValue(TripWireHookBlock.ATTACHED)) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(player instanceof ServerPlayer sp)) return;

        List<BlockPos> checkDispensers = new ArrayList<>();
        checkDispensers.add(pos);
        if (isTripwire) {
            List<BlockPos> wires = new ArrayList<>();
            List<BlockPos> hooks = new ArrayList<>();
            if (block instanceof TripWireBlock) wires.add(pos);
            if (block instanceof TripWireHookBlock) hooks.add(pos);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int d = 1; d <= 8; d++) {
                    BlockPos p = pos.relative(dir, d);
                    BlockState s = sl.getBlockState(p);
                    if (s.getBlock() instanceof TripWireBlock) {
                        wires.add(p);
                    } else if (s.getBlock() instanceof TripWireHookBlock) {
                        hooks.add(p);
                        checkDispensers.add(p);
                        break;
                    } else {
                        break;
                    }
                }
            }
            for (BlockPos hp : hooks) {
                BlockState hs = sl.getBlockState(hp);
                if (hs.getBlock() instanceof TripWireHookBlock) {
                    sl.setBlock(hp, hs.setValue(TripWireHookBlock.ATTACHED, false).setValue(TripWireHookBlock.POWERED, false),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
            for (BlockPos wp : wires) {
                BlockState ws = sl.getBlockState(wp);
                if (ws.getBlock() instanceof TripWireBlock) {
                    sl.setBlock(wp, ws.setValue(TripWireBlock.ATTACHED, false).setValue(TripWireBlock.POWERED, false),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    sl.setBlock(wp, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
            ActionHandler.addToBags(sp, new ItemStack(Items.STRING, 1));
        } else {
            sl.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            ActionHandler.addToBags(sp, new ItemStack(block.asItem(), 1));
        }

        // Disarm nearby dispensers and salvage a couple of arrows if loaded
        int salvagedArrows = 0;
        for (BlockPos anchor : checkDispensers) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos dp = anchor.offset(dx, dy, dz);
                        if (sl.getBlockEntity(dp) instanceof DispenserBlockEntity disp) {
                            if (!disp.getPersistentData().getBooleanOr("skycraft_stocked", false) && disp.isEmpty()) {
                                disp.setItem(0, Deco.ARROWS.copy());
                                disp.getPersistentData().putBoolean("skycraft_stocked", true);
                            }
                            for (int i = 0; i < disp.getContainerSize(); i++) {
                                ItemStack s = disp.getItem(i);
                                if (!s.isEmpty() && salvagedArrows < 3) {
                                    int take = Math.min(2, s.getCount());
                                    ItemStack arrow = s.split(take);
                                    salvagedArrows += arrow.getCount();
                                    ActionHandler.addToBags(sp, arrow);
                                }
                                disp.setItem(i, ItemStack.EMPTY);
                            }
                            disp.setChanged();
                        }
                    }
                }
            }
        }

        Progression.addSkillXpFromUse(sp, Skill.LOCKPICKING, 15f);
        Progression.addSkillXpFromUse(sp, Skill.SNEAK, 10f);
        sl.playSound(null, pos, SoundEvents.TRIPWIRE_DETACH, SoundSource.BLOCKS, 0.9f, 1.2f);
        Notifier.message(sp, Component.translatable("message.skycraft.dungeon.trap_disarmed"));
    }
}
