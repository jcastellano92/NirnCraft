package com.skycraft.sailing;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A ship ready to launch: use it on open water to put the ship in, bow pointing where you look. */
public class ShipItem extends Item {
    public final ShipType type;

    public ShipItem(ShipType type, Properties props) {
        super(props);
        this.type = type;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack);
        BlockPos pos = hit.getBlockPos();
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("sailing.skycraft.launch_water"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        // the water surface at the clicked column
        BlockPos.MutableBlockPos p = pos.mutable();
        while (level.getFluidState(p.above()).is(FluidTags.WATER) && p.getY() < pos.getY() + 8) p.move(0, 1, 0);
        double surface = p.getY() + level.getFluidState(p).getHeight(level, p);
        ShipEntity ship = SailingRegistry.type(type).create(level);
        if (ship == null) return InteractionResultHolder.fail(stack);
        Vec3 at = hit.getLocation();
        ship.moveTo(at.x, surface - type.draft, at.z, player.getYRot(), 0f);
        if (!level.noCollision(ship, ship.getBoundingBox().deflate(0.05)) || !roomFor(level, ship)) {
            player.displayClientMessage(Component.translatable("sailing.skycraft.launch_room"), true);
            return InteractionResultHolder.fail(stack);
        }
        ship.initNew(player);
        if (stack.hasTag() && stack.getTag().contains("Hull")) ship.setHull(stack.getTag().getFloat("Hull"));
        if (stack.hasCustomHoverName()) ship.setCustomName(stack.getHoverName());
        level.addFreshEntity(ship);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, at);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.awardStat(Stats.ITEM_USED.get(this));
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.translatable("sailing.skycraft.launched", ship.getDisplayName()), true);
        }
        return InteractionResultHolder.consume(stack);
    }

    /** The whole hull, bow to stern, must lie on water free of blocks. */
    public static boolean roomFor(Level level, ShipEntity ship) {
        float half = ship.shipType.length / 2f - 0.5f;
        for (float z = -half; z <= half; z += 1f) {
            for (float x : new float[]{-ship.shipType.beam / 2f + 0.3f, 0, ship.shipType.beam / 2f - 0.3f}) {
                Vec3 w = ship.toWorld(x, ship.shipType.draft + 0.3, z);
                BlockPos bp = BlockPos.containing(w);
                if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return false;
                if (!level.getFluidState(BlockPos.containing(w.x, w.y - 0.4, w.z)).is(FluidTags.WATER)) return false;
            }
        }
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("sailing.skycraft.tooltip." + type.id).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("sailing.skycraft.tooltip.seats", type.seats.length, type.horseSeats, type.cargoSlots)
                .withStyle(ChatFormatting.DARK_GRAY));
        if (stack.hasTag() && stack.getTag().contains("Hull")) {
            tooltip.add(Component.translatable("sailing.skycraft.tooltip.hull", Math.round(stack.getTag().getFloat("Hull")),
                    Math.round(type.maxHull)).withStyle(ChatFormatting.RED));
        }
        tooltip.add(Component.translatable("sailing.skycraft.tooltip.launch").withStyle(ChatFormatting.DARK_AQUA));
    }
}
