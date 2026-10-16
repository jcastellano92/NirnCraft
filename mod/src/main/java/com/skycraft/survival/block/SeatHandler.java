package com.skycraft.survival.block;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Sittable chairs and benches: right-click any bottom-half stair chair or bench with an empty hand to sit down.
 * Uses an invisible marker {@link ArmorStand} seat mount that cleans itself up immediately when dismounted or broken.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class SeatHandler {
    public static final String SEAT_TAG = "skycraft_seat";

    private SeatHandler() {}

    @SubscribeEvent
    public static void onSitOnChair(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Player player = event.getEntity();
        if (player.isShiftKeyDown() || player.isPassenger() || !player.getMainHandItem().isEmpty()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        if (pos.distToCenterSqr(player.position()) > 12.25) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof StairBlock)) return;
        if (state.getValue(StairBlock.HALF) != Half.BOTTOM) return;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) return;
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) return;

        // Skip stairs that are part of an ascending/descending staircase run between floors
        Direction back = state.getValue(StairBlock.FACING);
        BlockState stepUp = level.getBlockState(pos.relative(back).above());
        if (stepUp.getBlock() instanceof StairBlock && stepUp.getValue(StairBlock.HALF) == Half.BOTTOM
                && stepUp.getValue(StairBlock.FACING) == back) {
            return;
        }
        BlockState stepDown = level.getBlockState(pos.relative(back.getOpposite()).below());
        if (stepDown.getBlock() instanceof StairBlock && stepDown.getValue(StairBlock.HALF) == Half.BOTTOM
                && stepDown.getValue(StairBlock.FACING) == back) {
            return;
        }

        List<ArmorStand> existing = level.getEntitiesOfClass(ArmorStand.class, new AABB(pos),
                e -> e.getTags().contains(SEAT_TAG));
        if (!existing.isEmpty()) {
            for (ArmorStand stand : existing) {
                if (!stand.getPassengers().isEmpty()) return;
                if (!level.isClientSide) stand.discard();
            }
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level.isClientSide) return;

        ArmorStand seat = EntityType.ARMOR_STAND.create(level);
        if (seat == null) return;
        float seatYaw = back.getOpposite().toYRot();
        seat.moveTo(pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, seatYaw, 0.0f);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setSilent(true);
        seat.setInvulnerable(true);
        seat.addTag(SEAT_TAG);
        // Set Marker via NBT so hitbox is zero-size and never interferes with clicks
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        seat.saveWithoutId(tag);
        tag.putBoolean("Marker", true);
        tag.putBoolean("Small", true);
        seat.load(tag);

        level.addFreshEntity(seat);
        player.setYRot(seatYaw);
        player.setYHeadRot(seatYaw);
        player.startRiding(seat, true);
        level.playSound(null, pos, SoundEvents.WOOD_STEP, SoundSource.BLOCKS, 0.6f, 0.9f);
    }

    @SubscribeEvent
    public static void onDismountSeat(EntityMountEvent event) {
        if (!event.isDismounting()) return;
        Entity mount = event.getEntityBeingMounted();
        if (mount instanceof ArmorStand stand && stand.getTags().contains(SEAT_TAG)) {
            if (!mount.level().isClientSide) {
                Entity rider = event.getEntityMounting();
                BlockPos seatPos = stand.blockPosition();
                stand.discard();
                if (rider != null) {
                    rider.teleportTo(seatPos.getX() + 0.5, seatPos.getY() + 1.0, seatPos.getZ() + 0.5);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChairBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide) {
            BlockPos pos = event.getPos();
            for (ArmorStand seat : level.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(0.5),
                    e -> e.getTags().contains(SEAT_TAG))) {
                seat.ejectPassengers();
                seat.discard();
            }
        }
    }
}
