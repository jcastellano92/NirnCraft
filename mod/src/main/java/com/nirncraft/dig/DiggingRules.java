package com.nirncraft.dig;

import com.nirncraft.SkyConfig;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.Notifier;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.perk.Perks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "You start as a nobody": in Skyrim you can't dig. Terrain is locked behind Mining perks:
 * <ul>
 *     <li>{@code nirncraft:soft_terrain} (dirt, sand, gravel, clay, snow...) needs <b>Excavator</b></li>
 *     <li>{@code nirncraft:hard_terrain} (stone, deepslate, granite...) needs <b>Stonebreaker</b></li>
 *     <li>{@code nirncraft:deep_terrain} (obsidian, end stone, basalt...) needs <b>Deep Delver</b></li>
 * </ul>
 * Ores need a pickaxe, logs need an axe. Player-placed blocks and {@code nirncraft:always_breakable} are always fine.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class DiggingRules {
    public static final TagKey<Block> SOFT = tag("soft_terrain");
    public static final TagKey<Block> HARD = tag("hard_terrain");
    public static final TagKey<Block> DEEP = tag("deep_terrain");
    public static final TagKey<Block> ALWAYS = tag("always_breakable");

    private static final Map<UUID, Long> LAST_MESSAGE = new HashMap<>();

    private DiggingRules() {}

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(NirnCraft.MODID, name));
    }

    /** Returns the translation key of the reason the player can't break this block, or null if allowed. */
    public static String denyReason(Player player, BlockState state, BlockPos pos) {
        if (player.isCreative() || player.isSpectator()) return null;
        // wild plants are everyone's to pick, on anyone's land (owner decision, playtest 4); a crop somebody planted
        // in a homestead keeps that homestead's rules below
        boolean wildPlant = com.nirncraft.protect.Protect.isAmbientPlant(state) || com.nirncraft.survival.Harvesting.isHarvestable(state);
        if (wildPlant && !PlacedBlocks.isPlayerPlaced(player.level(), pos)) return null;
        boolean ownGround = false;
        if (player instanceof ServerPlayer sp && sp.level().getServer() != null) {
            com.nirncraft.homestead.HomesteadClaim claim = com.nirncraft.homestead.HomesteadData.get(sp.level().getServer()).getClaimHorizontal(sp.level(), pos);
            if (claim != null) {
                if (!claim.canBuild(sp)) return "message.nirncraft.homestead.no_build_permission";
                if (pos.getY() < claim.getCenter().getY() - claim.getMinDepth()) return "message.nirncraft.homestead.depth_limit";
                if (PlacedBlocks.isPlayerPlaced(player.level(), pos)) return null;
                ownGround = true;
            }
        }
        if (state.is(ALWAYS)) return null;
        if (PlacedBlocks.isPlayerPlaced(player.level(), pos)) return null;
        ItemStack tool = player.getMainHandItem();
        Block b = state.getBlock();
        // Flowers, mushrooms, bushes, and crops are freely harvestable by hand (Skyrim style)
        if (b instanceof net.minecraft.world.level.block.FlowerBlock
                || b instanceof net.minecraft.world.level.block.MushroomBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.DoublePlantBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock
                || state.is(net.minecraft.tags.BlockTags.FLOWERS)
                || state.is(net.minecraft.tags.BlockTags.CROPS)) {
            return null;
        }
        if (SkyConfig.REQUIRE_CORRECT_TOOL.get()) {
            if (state.is(Tags.Blocks.ORES) && !(tool.is(net.minecraft.tags.ItemTags.PICKAXES))) return "message.nirncraft.need_pickaxe";
            if (state.is(BlockTags.LOGS) && !(tool.is(net.minecraft.tags.ItemTags.AXES))) return "message.nirncraft.need_axe";
        }
        if (!SkyConfig.RESTRICT_DIGGING.get()) return null;
        // your own homestead is yours to dig: the Mining perks only gate the wilderness (world protection, docs/FEATURES.md)
        if (ownGround && com.nirncraft.protect.ProtectConfig.HOMESTEAD_FREE_DIGGING.get()) return null;
        if (state.is(DEEP) && !Perks.has(player, "mining.deep_delver")) return "message.nirncraft.need_deep_delver";
        if (state.is(HARD) && !Perks.has(player, "mining.stonebreaker")) return "message.nirncraft.need_stonebreaker";
        if (state.is(SOFT) && !Perks.has(player, "mining.excavator")) return "message.nirncraft.need_excavator";
        return null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        BlockPos pos = event.getPosition().orElse(null);
        if (pos == null) return;
        BlockState state = event.getState();
        if (denyReason(player, state, pos) != null) {
            event.setNewSpeed(0f);
            return;
        }
        float speed = event.getNewSpeed();
        var data = SkyData.get(player);
        if (state.is(Tags.Blocks.ORES) || state.is(HARD) || state.is(SOFT) || state.is(DEEP)) {
            speed *= 1f + data.getSkill(Skill.MINING) * 0.005f;
            if (Perks.has(player, "mining.tunneler")) speed *= 1.5f;
        } else if (state.is(BlockTags.LOGS)) {
            speed *= 1f + data.getSkill(Skill.WOODCUTTING) * 0.005f + 0.2f * Perks.rank(player, "woodcutting.lumberjack");
        }
        event.setNewSpeed(speed);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        Player player = event.getPlayer();
        String reason = denyReason(player, event.getState(), event.getPos());
        if (reason != null) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) warn(sp, reason);
            return;
        }
        PlacedBlocks.mark(player.level(), event.getPos(), false);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && !player.level().isClientSide()) {
            if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
                // Beds, doors, tall plants: every part counts as placed.
                for (var snapshot : multi.getReplacedBlockSnapshots()) PlacedBlocks.mark(player.level(), snapshot.getPos(), true);
            } else {
                PlacedBlocks.mark(player.level(), event.getPos(), true);
            }
        }
    }

    /** Called client-and-server side when a player starts hitting a forbidden block, so they learn why. */
    @SubscribeEvent
    public static void onLeftClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            String reason = denyReason(sp, sp.level().getBlockState(event.getPos()), event.getPos());
            if (reason != null) warn(sp, reason);
        }
    }

    private static void warn(ServerPlayer player, String key) {
        long now = player.level().getGameTime();
        Long last = LAST_MESSAGE.get(player.getUUID());
        if (last != null && now - last < 40) return;
        LAST_MESSAGE.put(player.getUUID(), now);
        Notifier.message(player, Component.translatable(key));
    }
}
