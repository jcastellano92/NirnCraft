package com.nirncraft.protect.client;

import com.nirncraft.NirnCraft;
import com.nirncraft.protect.Footprints;
import com.nirncraft.protect.Protect;
import com.nirncraft.protect.ProtectPackets;
import com.nirncraft.survival.Harvesting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client half of the world protection: it asks the same cheap questions as {@link Protect} (is it a plant, a
 * tree, ground; is it inside a homestead I may build in; is it inside a named settlement) with what the server told
 * it ({@link ProtectPackets.Policy}) and what it learns from refusals ({@link ProtectPackets.Denied}), and stops the
 * attack before it starts: no cracks, no block that vanishes for a moment and comes back. The server still decides
 * everything; a wrong guess here only means a refused swing is sent (and a denied one is never shown).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID, value = Dist.CLIENT)
public final class ProtectClient {
    /** Learned refusals live this long (ticks) and cover this many columns (8x8 cells). */
    private static final long LEARNED_TICKS = 20 * 45L;
    private static final int CELL_SHIFT = 3;

    private static volatile boolean enabled;
    private static volatile boolean bypass;
    private static volatile List<ProtectPackets.ClaimView> claims = List.of();
    /** "block id|cell" -> game tick until which that kind is refused in that cell. */
    private static final Map<String, Long> LEARNED = new HashMap<>();

    private ProtectClient() {}

    public static void onPolicy(ProtectPackets.Policy policy) {
        enabled = policy.enabled();
        bypass = policy.bypass();
        claims = policy.claims();
    }

    public static void onDenied(ProtectPackets.Denied denied) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        synchronized (LEARNED) {
            if (LEARNED.size() > 512) LEARNED.clear();
            LEARNED.put(key(denied.blockId(), denied.pos()), level.getGameTime() + LEARNED_TICKS);
        }
    }

    private static String key(String blockId, BlockPos pos) {
        return blockId + "|" + (pos.getX() >> CELL_SHIFT) + "," + (pos.getZ() >> CELL_SHIFT) + "," + (pos.getY() >> CELL_SHIFT);
    }

    private static boolean learned(Level level, BlockState state, BlockPos pos) {
        String id = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        synchronized (LEARNED) {
            Long until = LEARNED.get(key(id, pos));
            if (until == null) return false;
            if (level.getGameTime() > until) {
                LEARNED.remove(key(id, pos));
                return false;
            }
            return true;
        }
    }

    @javax.annotation.Nullable
    private static ProtectPackets.ClaimView claimAt(BlockPos pos) {
        for (ProtectPackets.ClaimView c : claims) if (c.containsHorizontally(pos)) return c;
        return null;
    }

    /** Whether the server would refuse this player the break (as far as the client can tell without asking). */
    public static boolean breakDenied(Player player, Level level, BlockPos pos, BlockState state) {
        if (!enabled || bypass || player.isCreative() || player.isSpectator() || state.isAir()) return false;
        if (learned(level, state, pos)) return true;
        boolean plant = Protect.isAmbientPlant(state) || Harvesting.isHarvestable(state);
        ProtectPackets.ClaimView claim = claimAt(pos);
        if (claim != null) {
            if (claim.mine()) return false;
            return !plant; // wild plants are everyone's, whoever's land they grow on (a planted crop is the server's call)
        }
        if (plant || state.getBlock() instanceof com.nirncraft.survival.block.DepletedOreBlock) return false;
        boolean ore = state.is(Tags.Blocks.ORES);
        boolean tree = !ore && Protect.isTree(state);
        boolean terrain = !ore && !tree && Protect.isTerrain(state);
        if (ore || tree || terrain) {
            // footprint rules: a named settlement is known here, roads and structures are learned from refusals
            return level.dimension() == Level.OVERWORLD && Footprints.inNamedSettlement(pos.getX(), pos.getZ());
        }
        // everything else (glass, beds, doors, chests, furniture, walls...) is never ours to break outside a homestead
        return true;
    }

    /** Whether the server would refuse this player placing {@code placed} at {@code pos}. */
    public static boolean placeDenied(Player player, BlockPos pos, BlockState placed) {
        if (!enabled || bypass || player.isCreative() || player.isSpectator()) return false;
        ProtectPackets.ClaimView claim = claimAt(pos);
        if (claim != null) return !claim.mine();
        return !placed.is(Protect.PLACEABLE_ANYWHERE);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide() || event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.ABORT
                || event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.STOP) return;
        Player player = event.getEntity();
        if (player != Minecraft.getInstance().player) return;
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!breakDenied(player, event.getLevel(), event.getPos(), state)) return;
        // no crack, no ghost removal: the attack never starts (the server would refuse it anyway)
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        Player player = event.getEntity();
        if (player != Minecraft.getInstance().player) return;
        if (!(event.getItemStack().getItem() instanceof BlockItem item)) return;
        BlockPos target = event.getPos().relative(event.getFace());
        // clicking on something replaceable (grass, snow) places into that cell
        BlockState at = event.getLevel().getBlockState(event.getPos());
        if (at.canBeReplaced()) target = event.getPos();
        if (!placeDenied(player, target, item.getBlock().defaultBlockState())) return;
        // the block is not placed (no ghost block that pops away); using the block clicked (a door, a chest) still works
        event.setUseItem(net.minecraft.util.TriState.FALSE);
    }
}
