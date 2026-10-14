package com.skycraft.homestead;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.crafting.block.StationBlock;
import com.skycraft.dig.PlacedBlocks;
import com.skycraft.inventory.Inv;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantmentTableBlock;
import net.minecraft.world.level.block.FletchingTableBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Build mode of a homestead: while the owner has it on, the crafting stations, containers and lecterns of the home can
 * be picked up (left click) and placed again anywhere in the homestead. A picked-up container keeps its contents (they
 * travel inside the item, like a shulker box; nothing spills), a station is simply carried. Outside build mode these
 * blocks are protected so a stray swing never loses a forge or empties a chest.
 *
 * <p>Build mode is switched on at the drafting table (button, or sneak and use the table with empty hands) and off by
 * the same, and it is off automatically when the owner leaves the homestead, logs out, dies or changes dimension, and
 * on a server restart (it is not saved).</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class HomesteadBuildMode {
    private static final Set<UUID> ON = ConcurrentHashMap.newKeySet();

    private HomesteadBuildMode() {}

    public static boolean isOn(net.minecraft.world.entity.player.Player player) {
        return player != null && ON.contains(player.getUUID());
    }

    /** Switches build mode of the claim's owner; returns the new state. */
    public static boolean toggle(ServerPlayer player, HomesteadClaim claim) {
        if (!player.getUUID().equals(claim.getOwnerId())) return false;
        if (ON.remove(player.getUUID())) {
            Notifier.message(player, Component.translatable("message.skycraft.homestead.build_mode.off"));
            return false;
        }
        if (!claim.isHorizontallyInside(player.level(), player.blockPosition())) {
            Notifier.message(player, Component.translatable("message.skycraft.homestead.build_mode.outside"));
            return false;
        }
        ON.add(player.getUUID());
        player.level().playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 0.6f, 1.2f);
        Notifier.message(player, Component.translatable("message.skycraft.homestead.build_mode.on"));
        return true;
    }

    private static void off(ServerPlayer player, String reasonKey) {
        if (ON.remove(player.getUUID()) && reasonKey != null) {
            Notifier.message(player, Component.translatable(reasonKey));
        }
    }

    /** Forgets everything (tests, server stop). */
    public static void clearAll() {
        ON.clear();
    }

    // ------------------------------------------------------------------ automatic exits

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player) || ON.isEmpty()) return;
        if (player.tickCount % 20 != 0 || !ON.contains(player.getUUID())) return;
        HomesteadClaim claim = HomesteadData.get(player.server).getClaim(player.getUUID());
        if (claim == null || !claim.isHorizontallyInside(player.level(), player.blockPosition())) {
            off(player, "message.skycraft.homestead.build_mode.left");
            return;
        }
        // a quiet reminder while it is on
        player.displayClientMessage(Component.translatable("message.skycraft.homestead.build_mode.hud").withStyle(net.minecraft.ChatFormatting.GOLD), true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ON.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) off(player, "message.skycraft.homestead.build_mode.left");
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        ON.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        ON.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ON.clear();
    }

    // ------------------------------------------------------------------ which blocks can be picked up

    /** Crafting stations: Skycraft's own and the vanilla ones a home uses. Beds and doors are not (they come apart as usual). */
    public static boolean isStation(BlockState state) {
        var b = state.getBlock();
        if (b instanceof StationBlock || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock
                || b instanceof SmithingTableBlock || b instanceof LoomBlock || b instanceof CartographyTableBlock
                || b instanceof StonecutterBlock || b instanceof EnchantmentTableBlock || b instanceof FletchingTableBlock
                || b instanceof AbstractFurnaceBlock || b instanceof BrewingStandBlock || b instanceof LecternBlock) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        return id != null && Skycraft.MODID.equals(id.getNamespace())
                && (id.getPath().equals("alchemy_lab") || id.getPath().equals("arcane_enchanter") || id.getPath().equals("cooking_pot"));
    }

    public static boolean isContainer(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
        var b = state.getBlock();
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock) return true;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container && !isStation(state);
    }

    /** True for blocks a builder carries rather than breaks. */
    public static boolean isMovable(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DraftingTableBlock) return false;
        return isStation(state) || isContainer(level, pos, state);
    }

    /**
     * Takes the block at {@code pos} up into the player's bags: containers keep their contents inside the item (the
     * block entity data travels with it and is restored when it is placed), nothing is dropped. Returns false if the
     * block cannot be carried.
     */
    public static boolean pickUp(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(state.getBlock());
        if (stack.isEmpty()) return false;
        BlockEntity be = level.getBlockEntity(pos);
        // a lectern gives its book back to the world as it comes down; every other block entity travels in the item
        if (be != null && !(be instanceof net.minecraft.world.level.block.entity.LecternBlockEntity)) {
            CompoundTag data = be.saveWithoutMetadata();
            if (be instanceof Container c) c.clearContent(); // empty before the block goes, so nothing spills
            if (!data.isEmpty()) BlockItem.setBlockEntityData(stack, be.getType(), data);
        }
        level.removeBlock(pos, false);
        PlacedBlocks.mark(level, pos, false);
        Inv.giveOrDrop(player, stack);
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6f, 1.0f);
        Notifier.message(player, Component.translatable("message.skycraft.homestead.picked_up", stack.getHoverName()));
        return true;
    }
}
