package com.skycraft.dungeons.puzzle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The lever of a Nordic pillar puzzle. Three rotating glyph stones sit in the wall above the sealed door; their
 * answer is carved on three tablets over the doorway across the room (read left to right, as the pillars are when
 * facing the door). Pulling the lever with the wrong glyphs showing fires poisoned darts from the walls; with the
 * right ones it works like any lever and opens the door.
 *
 * <p>Geometry, from the lever: it hangs on the right jamb, facing into the room; the door's bottom-middle block is
 * one block back towards the wall and one to the left, one block down.</p>
 */
public class PuzzleLeverBlock extends LeverBlock {
    public static final int HINT_SEARCH = 16;

    public PuzzleLeverBlock(Properties props) {
        super(props);
    }

    /** Bottom-middle block of the door for a lever at {@code pos} facing {@code inward}. */
    public static BlockPos doorBase(BlockPos pos, Direction inward) {
        Direction right = inward.getOpposite().getClockWise();
        return pos.relative(inward.getOpposite()).relative(right.getOpposite()).below();
    }

    @Override
    protected InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return use(state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        return use(state, level, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || state.getValue(POWERED)) return super.useWithoutItem(state, level, pos, player, hit);
        Direction inward = state.getValue(FACING);
        if (solved(level, pos, inward)) return super.useWithoutItem(state, level, pos, player, hit);
        darts(level, pos, inward, player);
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            com.skycraft.core.Notifier.message(sp, Component.translatable("message.skycraft.puzzle.wrong"));
        }
        return InteractionResult.CONSUME;
    }

    /** True if every pillar shows the glyph its tablet asks for (no tablets found counts as solved). */
    public static boolean solved(BlockGetter level, BlockPos lever, Direction inward) {
        for (java.util.Map.Entry<BlockPos, Symbol> e : answer(level, lever, inward).entrySet()) {
            if (SymbolStoneBlock.symbol(level.getBlockState(e.getKey())) != e.getValue()) return false;
        }
        return true;
    }

    /**
     * What the puzzle asks: each glyph stone over the door and the glyph its tablet shows (empty when no tablets are
     * found, which counts as solved). The dungeon analyzer solves the puzzle with this.
     */
    public static java.util.Map<BlockPos, Symbol> answer(BlockGetter level, BlockPos lever, Direction inward) {
        java.util.Map<BlockPos, Symbol> out = new java.util.LinkedHashMap<>();
        BlockPos door = doorBase(lever, inward);
        Direction right = inward.getOpposite().getClockWise();
        BlockPos hintMid = null;
        for (int k = 1; k <= HINT_SEARCH; k++) {
            BlockPos p = door.relative(inward, k).above(3);
            if (level.getBlockState(p).getBlock() instanceof SymbolStoneBlock b && !b.rotating()) {
                hintMid = p;
                break;
            }
        }
        if (hintMid == null) return out;
        for (int lat = -1; lat <= 1; lat++) {
            Symbol want = SymbolStoneBlock.symbol(level.getBlockState(hintMid.relative(right, -lat)));
            if (want != null) out.put(door.relative(right, lat).above(3), want);
        }
        return out;
    }

    private static void darts(Level level, BlockPos lever, Direction inward, Player player) {
        BlockPos door = doorBase(lever, inward);
        Direction right = inward.getOpposite().getClockWise();
        Vec3 target = player.getEyePosition().subtract(0, 0.4, 0);
        for (int s : new int[]{-2, 2}) {
            BlockPos from = door.relative(right, s).relative(inward, 1).above(2);
            Vec3 origin = Vec3.atCenterOf(from);
            Vec3 d = target.subtract(origin);
            Arrow arrow = new Arrow(level, origin.x, origin.y, origin.z, net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.POISON), null);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            arrow.shoot(d.x, d.y, d.z, 1.4f, 3.0f);
            level.addFreshEntity(arrow);
        }
        level.playSound(null, lever, SoundEvents.DISPENSER_LAUNCH, SoundSource.BLOCKS, 1.0f, 1.3f);
        level.playSound(null, lever, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 1.0f, 0.5f);
    }
}
