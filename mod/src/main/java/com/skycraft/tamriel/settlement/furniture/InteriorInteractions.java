package com.skycraft.tamriel.settlement.furniture;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.crime.Bounty;
import com.skycraft.crime.Crimes;
import com.skycraft.crime.Ownership;
import com.skycraft.crime.Theft;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.society.entity.NpcEntity;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlock;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlockEntity;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Handles Skyrim-style interior interactions:
 * <ul>
 *   <li>Sitting in custom 3D chairs, stools, benches, and thrones (both players and indoor NPCs).</li>
 *   <li>Taking weapons from 3D weapon racks and tabletop clutter items (tankards, feast platters,
 *       goblets, apothecary vials, merchant scales, ledgers), integrated with ownership & theft.</li>
 *   <li>Searching writing desks for regional lore notes and journals.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class InteriorInteractions {
    public static final String SEAT_TAG = "skycraft_seat";

    private InteriorInteractions() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FurnitureBlock)) return;
        if (!(level.getBlockEntity(pos) instanceof FurnitureBlockEntity fbe)) return;

        Player player = event.getEntity();
        FurnitureKind kind = fbe.kind();

        // 1. Sitting on chairs, stools, benches, and thrones
        if (kind.isSeat()) {
            if (player.isShiftKeyDown()) return;
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
                sitEntity(sl, pos, state, kind, sp);
            }
            return;
        }

        // 2. Taking weapons from weapon racks or items from tabletop clutter
        if (kind.isTakeableClutter() && !fbe.isEmpty()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
                takeClutter(sl, pos, fbe, kind, sp);
            }
            return;
        }

        // 3. Searching writing desks for notes/journals
        if (kind == FurnitureKind.DESK && !fbe.isEmpty()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
                searchDesk(sl, pos, fbe, sp);
            }
        }
    }

    public static boolean sitEntity(ServerLevel sl, BlockPos pos, BlockState state, FurnitureKind kind, LivingEntity rider) {
        if (rider.isPassenger()) {
            rider.stopRiding();
        }
        // Check if seat is already occupied
        List<ArmorStand> existing = sl.getEntitiesOfClass(ArmorStand.class, new AABB(pos).inflate(0.6),
                a -> a.getTags().contains(SEAT_TAG));
        for (ArmorStand stand : existing) {
            if (stand.isVehicle()) {
                Entity first = stand.getFirstPassenger();
                if (first instanceof Player) {
                    return false;
                }
                // If an NPC is sitting here and a player clicked the chair, politely vacate for the player
                if (rider instanceof Player && first != null) {
                    first.stopRiding();
                    stand.discard();
                } else {
                    return false;
                }
            } else {
                stand.discard();
            }
        }

        Direction facing = state.hasProperty(FurnitureBlock.FACING)
                ? state.getValue(FurnitureBlock.FACING)
                : Direction.SOUTH;
        float yaw = facing.toYRot();
        double seatSurfaceY = switch (kind) {
            case THRONE -> 0.58;
            case CHAIR -> 0.52;
            case BENCH, STOOL -> 0.48;
            default -> 0.50;
        };

        ArmorStand seat = new ArmorStand(sl, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        seat.setSilent(true);
        seat.setNoBasePlate(true);
        net.minecraft.nbt.CompoundTag markerTag = new net.minecraft.nbt.CompoundTag();
        seat.addAdditionalSaveData(markerTag);
        markerTag.putBoolean("Marker", true);
        markerTag.putBoolean("Small", true);
        seat.readAdditionalSaveData(markerTag);
        seat.addTag(SEAT_TAG);
        seat.setYRot(yaw);
        seat.setYHeadRot(yaw);
        seat.setYBodyRot(yaw);

        double standY = pos.getY() + seatSurfaceY - seat.getPassengersRidingOffset() - rider.getMyRidingOffset();
        seat.setPos(pos.getX() + 0.5, standY, pos.getZ() + 0.5);
        sl.addFreshEntity(seat);

        rider.setYRot(yaw);
        rider.setYHeadRot(yaw);
        rider.setYBodyRot(yaw);
        boolean mounted = rider.startRiding(seat, true);
        if (mounted) {
            sl.playSound(null, pos, SoundEvents.WOOD_STEP, SoundSource.BLOCKS, 0.65f, 0.9f);
        } else {
            seat.discard();
        }
        return mounted;
    }

    private static void takeClutter(ServerLevel sl, BlockPos pos, FurnitureBlockEntity fbe, FurnitureKind kind, ServerPlayer sp) {
        ItemStack stack = createClutterReward(sl, pos, kind);
        boolean owned = Ownership.isOwnedByOther(sp, sl, pos);
        if (owned) {
            Bounty.markStolen(stack);
            Bounty.increment(sp, "items_stolen", 1);
            if (Crimes.witnessed(sp, null)) {
                Theft.handleWitnessedTheft(sp, Crimes.findWitness(sp, null), pos, Bounty.MIN_THEFT);
            }
        }

        fbe.setEmpty(true);
        sl.playSound(null, pos,
                kind == FurnitureKind.WEAPON_RACK ? SoundEvents.ARMOR_EQUIP_IRON : SoundEvents.ITEM_PICKUP,
                SoundSource.BLOCKS, 0.8f, 1.05f);
        if (!ActionHandler.addToBags(sp, stack)) {
            sp.drop(stack, false);
        }
    }

    private static ItemStack createClutterReward(ServerLevel sl, BlockPos pos, FurnitureKind kind) {
        long h = pos.asLong();
        return switch (kind) {
            case WEAPON_RACK -> {
                int v = (int) Math.abs(h % 3);
                if (v == 0) yield new ItemStack(Items.IRON_SWORD);
                if (v == 1) yield new ItemStack(Items.IRON_AXE);
                yield new ItemStack(Items.SHIELD);
            }
            case CLUTTER_TANKARD -> {
                ItemStack mead = new ItemStack(Items.HONEY_BOTTLE);
                mead.setHoverName(Component.literal("Nordic Honningbrew Mead"));
                yield mead;
            }
            case CLUTTER_PLATTER -> new ItemStack(Items.COOKED_BEEF, 2);
            case CLUTTER_GOBLETS -> {
                ItemStack goblet = new ItemStack(Items.GOLD_NUGGET, 3);
                goblet.setHoverName(Component.literal("Silver Goblet"));
                yield goblet;
            }
            case CLUTTER_APOTHECARY -> new ItemStack(Items.EXPERIENCE_BOTTLE);
            case CLUTTER_SCALES -> new ItemStack(Items.GOLD_NUGGET, 5);
            case CLUTTER_LEDGER -> {
                String province = Regions.at(sl, pos).province();
                LoreBooks.Book book = LoreBooks.randomFor(sl.random, province, b -> true);
                yield book != null ? LoreBookItem.create(book) : new ItemStack(Items.BOOK);
            }
            default -> new ItemStack(Items.BREAD);
        };
    }

    private static void searchDesk(ServerLevel sl, BlockPos pos, FurnitureBlockEntity fbe, ServerPlayer sp) {
        fbe.setEmpty(true);
        sl.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.9f, 1.0f);
        String province = Regions.at(sl, pos).province();
        LoreBooks.Book book = LoreBooks.randomFor(sl.random, province, b -> true);
        if (book != null) {
            ItemStack stack = LoreBookItem.create(book);
            if (Ownership.isOwnedByOther(sp, sl, pos)) {
                Bounty.markStolen(stack);
            }
            Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_found", book.title()));
            if (!ActionHandler.addToBags(sp, stack)) {
                sp.drop(stack, false);
            }
        } else {
            Notifier.message(sp, Component.translatable("message.skycraft.lore.shelf_nothing"));
        }
    }

    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (!event.isDismounting()) return;
        Entity mounted = event.getEntityBeingMounted();
        // Unloading a rider or removing its seat also fires dismount. Do not teleport an
        // unloading entity or recursively remove the seat from inside its own removal.
        if (mounted == null || mounted.isRemoved() || event.getEntityMounting().isRemoved()) return;
        if (mounted instanceof ArmorStand stand && stand.getTags().contains(SEAT_TAG)) {
            Entity rider = event.getEntityMounting();
            if (!mounted.level().isClientSide) {
                BlockPos bpos = stand.blockPosition();
                BlockState state = mounted.level().getBlockState(bpos);
                Direction facing = state.hasProperty(FurnitureBlock.FACING)
                        ? state.getValue(FurnitureBlock.FACING)
                        : Direction.fromYRot(stand.getYRot());
                double exitX = bpos.getX() + 0.5 + facing.getStepX() * 0.45;
                double exitZ = bpos.getZ() + 0.5 + facing.getStepZ() * 0.45;
                double exitY = bpos.getY() + 0.6;
                rider.teleportTo(exitX, exitY, exitZ);
                stand.discard();
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel sl)) return;
        long time = sl.getGameTime();
        if (time % 20 == 0) {
            for (Entity e : sl.getAllEntities()) {
                if (e instanceof ArmorStand stand && stand.getTags().contains(SEAT_TAG)) {
                    BlockPos bpos = stand.blockPosition();
                    boolean validSeatBlock = sl.getBlockState(bpos).getBlock() instanceof FurnitureBlock
                            || sl.getBlockState(bpos.above()).getBlock() instanceof FurnitureBlock;
                    if (!stand.isVehicle() || !validSeatBlock) {
                        stand.discard();
                    } else if (stand.getFirstPassenger() instanceof NpcEntity npc) {
                        // Stand up if in combat or after ~35 seconds
                        if (npc.getTarget() != null || stand.tickCount > 700) {
                            npc.stopRiding();
                            stand.discard();
                        }
                    }
                }
            }
        }

        // Allow idle indoor townsfolk near a chair or bench to sit down occasionally
        if (time % 100 == 0) {
            for (ServerPlayer player : sl.players()) {
                List<NpcEntity> npcs = sl.getEntitiesOfClass(NpcEntity.class, player.getBoundingBox().inflate(24),
                        n -> n.isAlive() && !n.isPassenger() && n.getTarget() == null && n.getNavigation().isDone());
                for (NpcEntity npc : npcs) {
                    if (sl.random.nextFloat() > 0.25f) continue;
                    BlockPos base = npc.blockPosition();
                    if (sl.canSeeSky(base)) continue; // indoor seating only
                    seatLoop:
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BlockPos check = base.offset(dx, 0, dz);
                            BlockState st = sl.getBlockState(check);
                            if (st.getBlock() instanceof FurnitureBlock
                                    && sl.getBlockEntity(check) instanceof FurnitureBlockEntity fbe
                                    && fbe.kind().isSeat()) {
                                if (sitEntity(sl, check, st, fbe.kind(), npc)) {
                                    break seatLoop;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
